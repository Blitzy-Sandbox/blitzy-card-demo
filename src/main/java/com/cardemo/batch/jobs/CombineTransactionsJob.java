/*
 * ******************************************************************
 * Program     : CombineTransactionsJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Combine transaction backup and interest generations, sort by transaction id and bulk-load the cluster.
 * Source      : app/jcl/COMBTRAN.jcl (52 lines, no COBOL program - DFSORT + IDCAMS control cards only) + app/ctl/REPROCT.ctl @ 7756d89
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.cardemo.batch.GenerationPrefixContract;
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;

/**
 * The whole of {@code app/jcl/COMBTRAN.jcl}: sort the concatenated transaction backup and interest
 * generations by {@code TRAN-ID}, emit one combined generation, then bulk-load it into the transaction
 * relation.
 *
 * <h2>This job has NO COBOL program, and none may be invented</h2>
 * <strong>Every other job in this package translates a {@code .cbl} member. This one does not, because none
 * exists.</strong> {@code app/jcl/COMBTRAN.jcl} is two utility steps and nothing else -
 * {@code //STEP05R  EXEC PGM=SORT} at {@code app/jcl/COMBTRAN.jcl:L22} and
 * {@code //STEP10 EXEC PGM=IDCAMS} at {@code :L41} - so its behaviour lives entirely in DFSORT and IDCAMS
 * control cards. <b>The JCL member is therefore the source of truth, and every behavioural claim below cites
 * {@code app/jcl/COMBTRAN.jcl} or {@code app/ctl/REPROCT.ctl} and nothing else.</b> Where a shape is borrowed
 * from elsewhere in the corpus - the file-status guard, the abend contract, the record geometry - it is cited
 * as borrowed rather than presented as this job's own. A reader who notices the absent {@code .cbl} citation
 * is seeing the truth, not an omission.
 *
 * <h2>What it does</h2>
 * <b>{@code STEP05R} - the sort.</b> {@code SORTIN} is <b>two DD statements, the second unnamed</b>, which is
 * how JCL expresses dataset concatenation:
 * <pre>
 * //SORTIN   DD DISP=SHR,                          &lt;- app/jcl/COMBTRAN.jcl:L23
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)   &lt;- app/jcl/COMBTRAN.jcl:L24
 * //         DD DISP=SHR,                          &lt;- app/jcl/COMBTRAN.jcl:L25
 * //         DSN=AWS.M2.CARDDEMO.SYSTRAN(0)         &lt;- app/jcl/COMBTRAN.jcl:L26
 * </pre>
 * {@code SYMNAMES} declares the sort key {@code TRAN-ID,1,16,CH} ({@code :L28}) and {@code SYSIN} declares
 * the ordering {@code SORT FIELDS=(TRAN-ID,A)} ({@code :L30}). {@code SORTOUT} writes
 * {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} ({@code :L37}) under {@code DISP=(NEW,CATLG,DELETE)}
 * ({@code :L33}), and <b>{@code DCB=(*.SORTIN)} ({@code :L35}) makes the output inherit the input's geometry
 * rather than declaring its own</b> - which {@code app/jcl/TRANBKP.jcl:L31} pins at
 * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} where it creates {@code TRANSACT.BKUP(+1)}, and
 * {@code app/jcl/INTCALC.jcl:L39} pins identically where it creates {@code SYSTRAN(+1)}. Hence
 * {@value #COMBINED_RECORD_LENGTH} bytes per record, fixed-block, on both inputs and the output.
 * <p>
 * <b>{@code STEP10} - the load.</b> {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} ({@code :L48}) copies
 * the combined generation ({@code :L43-L44}) into {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 * ({@code :L45-L46}). The same control card, parameterised, is {@code app/ctl/REPROCT.ctl:L15}
 * - {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}. Here it is a
 * {@link JdbcTemplate#batchUpdate(String, List)} against the {@code transaction} relation.
 * <b>No external process is spawned</b>: there is no {@code Runtime.exec}, no {@code ProcessBuilder}, no
 * host-installed sort or copy utility and no shell of any kind, which Rule 1 Clause D requires
 * independently of parity.
 *
 * <h2>Inputs, outputs and side effects</h2>
 * Stated explicitly, because everything this job mutates is mutated outside its own process.
 * <ul>
 *   <li><b>Inputs (read only):</b> the current {@code TRANSACT.BKUP} generation and the current
 *       {@code SYSTRAN} generation, reached through {@link CombinedTransactionReader}. Neither is modified,
 *       deleted or expired by this job.</li>
 *   <li><b>Outputs:</b> one new {@code TRANSACT.COMBINED} object of {@value #COMBINED_RECORD_LENGTH}-byte
 *       records, and one row per record inserted into the {@code transaction} relation.</li>
 *   <li><b>Side effects, in order:</b> one temporary staging file is created under {@code java.io.tmpdir}
 *       and deleted again whatever the outcome; a single object is written to the generation bucket; three
 *       entries are written to the job execution context ({@value #COMBINED_OBJECT_KEY_CONTEXT_ENTRY},
 *       {@value #COMBINED_RECORD_COUNT_CONTEXT_ENTRY} and
 *       {@value #COMBINED_LOADED_RECORD_COUNT_CONTEXT_ENTRY}) and are therefore persisted by the job
 *       repository; rows are inserted into the relation inside the load step's transaction; and two
 *       diagnostic-context entries are set for the duration of the run and cleared afterwards. <b>No
 *       Micrometer counter is advanced by this job</b> - see
 *       {@link #publishLoadedRecordCount(StepExecution, int)}.</li>
 *   <li><b>Not a side effect, and this one is worth stating because it was one:</b> no application metric is
 *       advanced. {@code carddemo.batch.records.processed} counts the daily-transaction records the POSTTRAN
 *       job read ({@code app/cbl/CBTRN02C.cbl:L206}) and nothing else; the rows this job loads were already
 *       counted by the run that posted them. This job's volume lives in
 *       {@value #COMBINED_RECORD_COUNT_CONTEXT_ENTRY} and in the Spring Batch step metrics, both of which
 *       carry a job dimension that an untagged counter cannot. See finding H-01.</li>
 *   <li><b>Not a side effect:</b> nothing is deleted, no generation is expired, no queue message is sent and
 *       no other job is launched. Retention is documented rather than enforced, so this job never removes an
 *       object.</li>
 *   <li><b>Idempotency:</b> <b>this job is not idempotent, deliberately.</b> A second run over the same two
 *       input generations writes a second combined object and then fails the load on the first duplicate
 *       {@code TRAN-ID}, because {@code REPRO} into a keyed cluster fails on a duplicate key. That failure is
 *       the contract, not a defect to smooth over.</li>
 * </ul>
 *
 * <h2>Ordering: where the sort actually happens</h2>
 * {@code SORT FIELDS=(TRAN-ID,A)} over {@code TRAN-ID,1,16,CH} is a <b>single ascending key compared as
 * characters, not as a number</b> - {@code CH} is DFSORT's character format, so a leading space sorts below a
 * digit and a non-digit sorts by its code point. That translation is
 * {@link TransactionCombineProcessor#TRAN_ID_ASCENDING}, and <b>this class references it rather than
 * declaring a second comparator</b>, because two spellings of one ordering are two things that can diverge.
 * The global merge itself is performed by {@link CombinedTransactionReader}, which reads each source in
 * {@code TRAN-ID} order and interleaves them with that comparator, so {@link CombinedTransactionReader#read()}
 * already yields globally ascending records. <b>This job therefore does not re-sort</b>; it consumes an
 * ordering that is explicit at every hop and never relies on insertion order, hash iteration order or an
 * unordered query.
 *
 * <h2>Generation semantics, and the {@code (+1)} trap</h2>
 * A relative generation reference becomes an object key over a versioned bucket: {@code (0)} is the
 * lexicographically greatest existing prefix, {@code (+1)} is a new object under a monotonically increasing
 * prefix.
 * <p>
 * <b>{@code app/jcl/COMBTRAN.jcl:L43-L44} re-references {@code TRANSACT.COMBINED(+1)}, not {@code (0)}</b> -
 * the generation the preceding step created, inside the same job. This class reproduces that by having
 * {@code STEP05R} <b>publish the concrete created key into the job execution context</b> under
 * {@value #COMBINED_OBJECT_KEY_CONTEXT_ENTRY}, and {@code STEP10} read <b>that exact key</b> back.
 * <b>The latest generation is never re-resolved between the two steps.</b> Were it re-resolved, a concurrent
 * writer or a clock skew would let the load read an object the sort did not write - a defect that would
 * appear only under load and would corrupt the relation silently.
 * <p>
 * Because both inputs are {@code (0)}, <b>only the most recent {@code TRANSACT.BKUP} generation and only the
 * most recent {@code SYSTRAN} generation are consumed, so only the latest interest run is ever merged.</b>
 * Earlier {@code SYSTRAN} generations are never combined by this job and reach the relation only if a later
 * run re-creates them.
 * <p>
 * Retention is <b>documented, not enforced</b>. {@code app/jcl/DEFGDGB.jcl} declares
 * {@code LIMIT(5) SCRATCH} for all six bases it defines, including {@code TRANSACT.BKUP} ({@code :L25-L27}),
 * {@code SYSTRAN} ({@code :L49-L51}) and {@code TRANSACT.COMBINED} ({@code :L55-L57}); object versioning
 * supersedes it and no lifecycle rule is applied here. The separate {@code TRANREPT} retention conflict is
 * resolved in {@link TransactionReportJob} and is not restated.
 *
 * <h2>Duplicate identifiers are a hard failure, never an upsert</h2>
 * {@link InterestCalculationJob} writes to a <b>fresh sequential generation</b> - {@code app/jcl/INTCALC.jcl:L37-L41}
 * allocates {@code SYSTRAN(+1)} with {@code DISP=(NEW,CATLG,DELETE)} on every run - so it performs no
 * duplicate-key detection of its own and needs none. <b>The exposure materialises here, in this job's load
 * step</b>: a repeated interest date parameter regenerates the same sixteen-digit identifiers, and
 * {@code REPRO} into a keyed cluster fails on a duplicate key.
 * <p>
 * That failure is reproduced exactly. A colliding primary key surfaces
 * {@link com.cardemo.exception.DuplicateRecordException} through
 * {@link TransactionCombineProcessor#translateLoadFailure(Transaction, DataAccessException)}, with the root
 * cause preserved, and fails the step - <b>return code 8</b>. There is <b>no upsert, no
 * {@code ON CONFLICT DO NOTHING}, no {@code ON CONFLICT DO UPDATE}, no merge, no retry and no
 * swallow-and-continue</b> anywhere on this path. Absorbing the collision would let two runs of the interest
 * job silently produce one relation state, which is the precise divergence the boundary parity comparison
 * exists to catch.
 *
 * <h2>Exit status</h2>
 * Return codes map {@code 0} completed, {@code 4} completed-with-rejects, {@code 8} failed, {@code 12} abend.
 * <b>This job has no COBOL program and therefore no return-code-4 path of its own.</b> The only numeric
 * literal assignment to {@code RETURN-CODE} in the corpus is {@code MOVE 4 TO RETURN-CODE} at
 * {@code app/cbl/CBTRN02C.cbl:L230}, which belongs to the posting job's reject counter; this job has no
 * reject concept, so its normal outcome is {@code 0} and a duplicate identifier, a sort failure or a load
 * failure is {@code 8}. An unexpected condition is an abend:
 * {@link FatalProcessingException} carrying abend code {@value FatalProcessingException#BATCH_ABEND_CODE}
 * and process return code {@value FatalProcessingException#BATCH_RETURN_CODE}. Return code 4 and return
 * code 12 are independent paths, and <b>no {@code System.exit}, {@code Runtime.halt} or shutdown hook is
 * used</b> - the return code is carried by the exit status, not by terminating the JVM.
 *
 * <h2>The absence of gating is deliberate - do not "fix" it</h2>
 * <b>{@code //STEP10 EXEC PGM=IDCAMS} at {@code app/jcl/COMBTRAN.jcl:L41} carries no {@code COND}
 * parameter</b>, and neither does {@code //STEP05R  EXEC PGM=SORT} at {@code :L22}. Unlike
 * {@code app/jcl/CREASTMT.JCL}, which does gate its later steps, this member gates nothing: absent
 * {@code COND}, a step runs regardless of a preceding step's return code and is suppressed only by an abend.
 * The flow therefore routes <b>every non-failing outcome of {@code STEP05R} straight to {@code STEP10}</b>
 * and places <b>no decider between the two steps</b>. Inventing gating the source does not have is as much a
 * divergence as dropping gating it does have, so this is recorded here rather than left to inference. The
 * decider that does exist sits <b>after</b> the load, and on the failure arms, purely to distinguish return
 * codes 8 and 12 for the orchestrated pipeline.
 *
 * <h2>Record geometry, and the timestamps that must not be touched</h2>
 * Every record on both inputs and the output is {@value #COMBINED_RECORD_LENGTH} bytes, the one-based offset
 * map of {@code app/cpy/CVTRA05Y.cpy} which {@code SYMNAMES} at {@code app/jcl/COMBTRAN.jcl:L28} corroborates
 * for the key: identifier 1-16, type 17-18, category 19-22, source 23-32, description 33-132, amount 133-143,
 * merchant identifier 144-152, merchant name 153-202, merchant city 203-252, merchant postcode 253-262, card
 * number 263-278, originating timestamp 279-304, processing timestamp 305-330, filler 331-350.
 * <p>
 * <b>Both timestamps are twenty-six-character text and are passed through untouched.</b> They are
 * {@code String} over {@code CHAR(26)} end to end - never {@code LocalDateTime}, {@code Timestamp},
 * {@code Instant}, {@code OffsetDateTime} or {@code LocalDate} - because the corpus has three mutually
 * incompatible producers and the batch producer emits <b>hundredths-of-a-second</b> precision followed by
 * four literal zeros - not millisecond precision, which an earlier revision of this sentence claimed and which
 * would need seven fraction characters where {@code app/cbl/CBTRN02C.cbl:L159-L174} declares six.
 * Parsing and re-rendering would normalise that fourth-zero tail away and every generated timestamp would
 * differ from the baseline. This job performs no parsing, no reformatting, no normalising and no timezone
 * conversion on either field. Bytes are read and written through
 * {@link StandardCharsets#ISO_8859_1}, declared once as {@link #FIXED_WIDTH_CHARSET}: never UTF-8, never the
 * platform default, and no EBCDIC decoding.
 *
 * <h2>Configuration and defaults</h2>
 * <ul>
 *   <li>{@code carddemo.batch.jobs.combtran.name} - the registered job name, default
 *       {@value #DEFAULT_JOB_NAME}.</li>
 *   <li>{@code carddemo.batch.combtran.chunk-size} - rows per bulk-load batch, falling back to
 *       {@code carddemo.batch.chunk-size} and then to {@value #DEFAULT_CHUNK_SIZE}.</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} - the versioned generation bucket, supplied by
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} with no committed value.</li>
 *   <li>{@code carddemo.aws.s3.gdg-prefixes.transact-combined} - the {@code TRANSACT.COMBINED} key prefix,
 *       default {@value #DEFAULT_COMBINED_PREFIX}.</li>
 * </ul>
 * No value is read from the environment directly: there is no {@code System.getenv} call, no absolute host
 * path, and no reliance on a default locale, charset or timezone.
 *
 * <h2>How to run, build and test</h2>
 * Jobs do not auto-launch - {@code spring.batch.job.enabled: false} - so this one is started by
 * {@code BatchPipelineOrchestrator} or by the queue listener that replaces the JES2 internal reader. Build
 * and verify with {@code ./mvnw -B -ntp clean verify}. Tests belong in
 * {@code src/test/java/com/cardemo/unit/batch} for the comparator, the decode and the decider arms, and in
 * {@code src/test/java/com/cardemo/integration/batch} for the concatenation order, the {@code (+1)} key
 * handoff, the geometry and the duplicate collision.
 *
 * <h2>Failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>{@code DuplicateRecordException} from the load</b> means a {@code TRAN-ID} already exists in the
 *       relation or repeats within the generation. Ordinarily the interest job ran twice with the same date
 *       parameter. Re-drive the pipeline with an unused date parameter; do not retry, upsert or substitute a
 *       database sequence.</li>
 *   <li><b>{@code FatalProcessingException} naming {@code SORTOUT} or {@code TRANSACT}</b> is an
 *       object-storage failure on the upload or the download, carrying the rendered legacy file status.</li>
 *   <li><b>A combined object whose size is not a multiple of {@value #COMBINED_RECORD_LENGTH}</b> means
 *       something other than this job wrote the key, or wrote it with a different geometry. The load refuses
 *       it rather than parsing a misaligned stream.</li>
 *   <li><b>A load step that reports zero rows</b> means {@code STEP05R} published an empty generation; the
 *       sort step logs the per-source counts, so the empty source is named rather than inferred.</li>
 *   <li><b>An absent current generation on either leg IS an error</b>, reported as file status {@code '35'}
 *       with the searched prefix and the producing member named. {@code app/jcl/COMBTRAN.jcl:L23-L26} carries
 *       {@code DISP=SHR} on both legs, and an uncatalogued {@code DISP=SHR} dataset fails <b>allocation</b>
 *       before {@code SORT} is given control; the absence of {@code COND=} on {@code :L22} says nothing about
 *       that, because {@code COND=} gates step <em>execution</em> and not allocation. Produce the generation
 *       first: {@code TRANSACT.BKUP} comes from {@code STEP01R} of {@code app/proc/TRANREPT.prc:L21} and
 *       {@code SYSTRAN} from {@code app/jcl/INTCALC.jcl:L37-L41}. {@code CombinedTransactionReader} owns the
 *       decision and it is not re-implemented here.
 *       <p>Two earlier revisions of this entry are withdrawn. The first abended on an absent
 *       {@code TRANSACT.BKUP} generation while treating an absent {@code SYSTRAN} generation as an empty read;
 *       the second removed both failures and called an absent generation on either leg a successful empty
 *       read. Neither matches the member: the disposition is the same on both legs, so the outcome is the same
 *       on both legs.</li>
 *   <li><b>A generation that exists and holds nothing is a different case, and is legitimately empty.</b> It
 *       is the object-store counterpart of {@code DISP=(NEW,CATLG,DELETE)} cataloguing a dataset to which
 *       nothing was written - which {@code app/jcl/INTCALC.jcl:L37-L41} does whenever
 *       {@code app/cbl/CBACT04C.cbl:L214} suppressed every write - so it is read as zero records. A run in
 *       which both legs are present and both are empty still completes, creating an empty combined generation
 *       and publishing a record count of zero, because {@code :L33-L37} allocates {@code SORTOUT}
 *       unconditionally and {@code :L48} copies it, so a copy of nothing succeeds; the sort step logs the
 *       per-source counts so the empty leg is named rather than inferred.
 *       <p>Inside the pipeline the first leg is never absent, because the archive-and-reset step below
 *       writes {@code TRANSACT.BKUP(+1)} from the master before the sort runs; the two guarantees are
 *       complementary - one produces the generation, the other refuses to invent one.</li>
 * </ul>
 *
 * <h2>{@code app/jcl/TRANBKP.jcl} is implemented here, as the archive-and-reset step (finding C-06)</h2>
 *
 * <p>{@code app/jcl/TRANBKP.jcl} is the member that produces the {@code TRANSACT.BKUP} generations this job's
 * first {@code SORTIN} leg reads. It is three steps and no COBOL program: {@code :L23}
 * {@code //STEP05R EXEC PROC=REPROC} copies {@code TRANSACT.VSAM.KSDS} to {@code TRANSACT.BKUP(+1)} at
 * {@code :L31} {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)}; {@code //STEP05 EXEC PGM=IDCAMS} then
 * {@code DELETE}s the cluster and its alternate index, each followed by
 * {@code IF MAXCC LE 08 THEN SET MAXCC = 0}; and {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)} re-issues
 * {@code DEFINE CLUSTER} with {@code KEYS(16 0) RECORDSIZE(350 350)}.
 *
 * <p><b>It is the reason the daily stream is repeatable, so it is reproduced rather than documented away.</b>
 * An earlier revision recorded a decision that this member had no Java analogue, reasoning that inserting
 * rows into a relation needs no unload-empty-reload cycle and that the end state was the same either way.
 * <b>The end state is not the same</b>, and that reasoning was withdrawn as finding C-06. The delete and
 * redefine leaves {@code REPRO} an <em>empty</em> target, so {@code :L41-L48} loads the merged set into a
 * master holding nothing. Without it the combine loads the merged set into a master that still holds every
 * row the first leg just supplied, so every identifier collides: the load correctly refuses with
 * {@link com.cardemo.exception.DuplicateRecordException} and return code 8, and the stream cannot be run
 * twice. Repeatability is a property of the source that a migration either reproduces or loses.
 *
 * <h3>Why it is a step of this job rather than a sixth pipeline stage</h3>
 *
 * <p>{@link com.cardemo.batch.jobs.BatchPipelineOrchestrator} composes exactly five stages and declares no
 * step logic of its own, and the authored inventory admits no further file under {@code batch/jobs/**}. The
 * archive is therefore a step here, which is also where it belongs behaviourally: it is the step that
 * prepares this job's own input and output, and it must not run when this job runs standalone. The gate is
 * {@link #JOB_PARAMETER_ARCHIVE_MASTER} - a per-run instruction from the stream rather than a deployment
 * property, set non-identifying by the orchestrator on its stage-3 launch. Absent the instruction the step
 * is a logged no-operation, so a standalone combine still never empties anybody's master.
 *
 * <p>The copy leg is the same shared {@code REPROC} invocation that {@code TransactionReportJob}'s
 * {@code STEP01R} ({@code app/proc/TRANREPT.prc:L21}) implements - the {@code EXEC PROC=} census puts
 * {@code app/jcl/TRANBKP.jcl:L23} and {@code app/proc/TRANREPT.prc:L21} on the same procedure - so that base
 * now has two producers, exactly as the mainframe catalogue does. Both write
 * {@code <prefix>/generation=<19-digit>/TRANSACT.BKUP} so that neither shadows the other; see
 * {@link #BACKUP_OBJECT_KEY_TEMPLATE} for why a divergent key shape there would silently resolve the wrong
 * generation.
 *
 * <p>The delete-and-redefine leg becomes one {@code DELETE} over the relation. The schema stays in the
 * Flyway migrations, which is where a relational {@code DEFINE CLUSTER} equivalent belongs; the
 * {@code IF MAXCC LE 08} tolerance of a not-found cluster has no analogue, because removing no rows from an
 * already-empty table is not an error.
 *
 * <p><b>Ordering, and what it guarantees.</b> The archive object is uploaded <em>before</em> a single row is
 * removed, so a failure between the two leaves the master intact and the archive merely redundant - the safe
 * direction. The reverse order could lose the master outright. A restarted archive replaces its own object
 * rather than adding a sibling to the same generation.
 *
 * <h2>Findings carried by this file, classified per Rule 1 Clause F</h2>
 * <ul>
 *   <li><b>Blocker</b> - the two timestamps must remain {@code String} over {@code CHAR(26)}. Addressed
 *       above; {@link Transaction#getOrigTs()} and {@link Transaction#getProcTs()} are already {@code String},
 *       and this job neither parses nor reformats them.</li>
 *   <li><b>Blocker, found during validation and fixed</b> - leaving {@code @Configuration} unnamed made the
 *       component scan register this class under the decapitalised class name, which is the same name the
 *       {@link Job} factory method uses. Bean-definition overriding is disabled, so context refresh aborted
 *       with a {@code BeanDefinitionOverrideException} and <b>every</b> Spring context in the test suite
 *       failed to load - not merely this job's. Remediation, applied: name the configuration explicitly
 *       through {@link #CONFIGURATION_BEAN_NAME}, as the sibling jobs already do. Recorded here because the
 *       defect is invisible in isolation - the class compiles, and only a context refresh reveals it.</li>
 *   <li><b>Medium</b> - three internal types are imported that this file's declared dependency list does not
 *       name, and each edge is recorded here rather than left for a reviewer to discover.
 *       {@link TransactionWriter} is the canonical {@value #COMBINED_RECORD_LENGTH}-byte renderer and is
 *       reused through {@link TransactionWriter#composeFixedWidthImage(Transaction)}; re-implementing the
 *       encoding would have produced a second renderer able to diverge from the first, which Clause C
 *       forbids. {@link CorrelationIdFilter} owns the MDC key names and the propagation helpers the listener
 *       below calls, so using it avoids duplicating the literals {@code logback-spring.xml} consumes.
 *       {@link Transaction} is unavoidable: it is the element type
 *       {@link CombinedTransactionReader#read()} returns, so consuming a declared dependency's API requires
 *       it. Remediation: record all three edges, which this entry does.</li>
 *   <li><b>Medium</b> - the environment variable behind the generation bucket is
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}, not {@code CARDDEMO_S3_OUTPUT_BUCKET}. The name in
 *       {@code src/main/resources/application.yml} governs and is the one bound above.</li>
 *   <li><b>Low</b> - {@code app/jcl/COMBTRAN.jcl} is <b>52</b> lines on disk, not 53. Re-counted with CR
 *       stripped; the member is LF-terminated throughout.</li>
 *   <li><b>Low</b> - {@code app/ctl/REPROCT.ctl:L1-L14} carries only the Apache half of the standard banner,
 *       with no {@code Program}/{@code Application}/{@code Type}/{@code Function} header. Not remediated: the
 *       legacy corpus is frozen byte-for-byte.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 * Stated plainly rather than guessed, per Rule 1 Clause F:
 * <ul>
 *   <li><b>The object-storage record-framing convention is Not available.</b> Nothing in the corpus or the
 *       repository states whether a downstream consumer expects newline-delimited output. This job writes
 *       {@value #COMBINED_RECORD_LENGTH}-byte records with <b>no delimiter</b>, matching {@code RECFM=FB},
 *       and its own load step reads them back on that assumption. Needed to close it: the framing contract
 *       of any non-CardDemo consumer of {@code TRANSACT.COMBINED}.</li>
 *   <li><b>The intended behaviour when a {@code (0)} generation prefix is absent is Not available in the
 *       JCL.</b> {@code app/jcl/COMBTRAN.jcl} does not specify it, so the decision is
 *       {@link CombinedTransactionReader}'s and is documented above rather than duplicated. Needed to close
 *       it: an operational runbook for a first run against empty generation bases.</li>
 *   <li><b>A service-level objective for this job is Not available.</b> The source publishes no throughput
 *       and no latency target, so none is asserted, and the performance gate records a measured baseline
 *       rather than a threshold. Needed to close it: a stated objective from the service owner.</li>
 *   <li><b>Container-runtime dependent gate results are Not available from source alone.</b> The end-to-end,
 *       fixture, contract and integration gates need a container runtime with an accessible socket. Needed
 *       to close them: that runtime. No pass is claimed here on their behalf.</li>
 * </ul>
 *
 * <h2>Thread safety and state</h2>
 * This class is a stateless singleton. Every injected collaborator is {@code private final}; <b>there is no
 * static mutable field</b>, the only static members being the logger and immutable constants. All per-run
 * state - the staging file, the resolved key, the counters - lives in method locals inside the two tasklets or
 * in the execution context, so two job executions cannot observe each other. The staging file is created with
 * a unique name per call and deleted in a {@code finally}, so two concurrent executions cannot collide on it
 * either. Every JCL step,
 * DD statement, {@code SYMNAMES} entry, {@code SORT FIELDS} specification and control card maps to exactly
 * one private method or documented constant below, each citing its {@code path:line}; that mapping is what
 * keeps {@code TRACEABILITY_MATRIX.md} provable for a job with no program.
 *
 * @see CombinedTransactionReader
 * @see TransactionCombineProcessor
 * @see InterestCalculationJob
 * @see com.cardemo.batch.readers.TransactionBackupReader
 */
@Configuration(CombineTransactionsJob.CONFIGURATION_BEAN_NAME)
public class CombineTransactionsJob {

    /**
     * Bean name of this configuration holder.
     *
     * <p><b>It must differ from {@link #JOB_BEAN_NAME}.</b> The component scan registers the configuration
     * class under its own name, which defaults to the decapitalised class name - and that default is exactly
     * {@value #JOB_BEAN_NAME}, the name the {@link Job} factory method below registers the job under. Leaving
     * the default in place makes the two definitions collide and aborts context refresh with a
     * {@code BeanDefinitionOverrideException}, because bean-definition overriding is disabled. Naming the
     * configuration explicitly is what keeps the two apart, and it is the convention the sibling jobs in this
     * package already follow.
     */
    static final String CONFIGURATION_BEAN_NAME = "combineTransactionsJobConfiguration";

    /** The sole logging channel for this job. */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /**
     * Bean name of the sort step, translating {@code //STEP05R  EXEC PGM=SORT} at
     * {@code app/jcl/COMBTRAN.jcl:L22}. Uniquely prefixed so it cannot collide with a step
     * {@code com.cardemo.config.BatchConfig} may later declare.
     */
    private static final String SORT_STEP_BEAN_NAME = "combineTransactionsSortStep";

    /**
     * Bean name of the load step, translating {@code //STEP10 EXEC PGM=IDCAMS} at
     * {@code app/jcl/COMBTRAN.jcl:L41}.
     */
    private static final String LOAD_STEP_BEAN_NAME = "combineTransactionsLoadStep";

    /** Bean name of the two-step flow. */
    private static final String FLOW_BEAN_NAME = "combineTransactionsFlow";

    /**
     * Bean name of the job itself. Distinct from {@link #CONFIGURATION_BEAN_NAME}; see that constant for why
     * the distinction is load-bearing rather than cosmetic.
     */
    static final String JOB_BEAN_NAME = "combineTransactionsJob";

    /** Registered job name when {@code carddemo.batch.jobs.combtran.name} is not configured. */
    private static final String DEFAULT_JOB_NAME = "COMBTRAN";

    /** Rows per bulk-load batch when neither combine-specific nor global chunk size is configured. */
    private static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * Bytes buffered on the {@code SORTOUT} staging file and on the {@code REPRO} input stream: {@code 65536}.
     *
     * <p>A stream buffer, not a bound on the run. {@code app/jcl/COMBTRAN.jcl:L33-L37} allocates
     * {@code SORTOUT} on {@code UNIT=SYSDA} with {@code SPACE=(CYL,(1,1),RLSE)}, so the sort's output has
     * always been disk with a buffer in front of it, and this constant is that buffer. It is the only quantity
     * in this step that scales with anything other than the chunk size, which is why no per-run record cap
     * exists any longer: an earlier revision accumulated the whole generation in a {@code StringBuilder}, a
     * {@code String} and a {@code byte[]} at once and needed an invented
     * {@code carddemo.batch.combtran.max-records-per-run} ceiling of one million records to keep that from
     * exhausting the heap. {@code app/jcl/COMBTRAN.jcl} declares no such ceiling anywhere, and removing the
     * accumulation removed the need to invent one.
     */
    private static final int WORK_BUFFER_BYTES = 64 * 1024;

    /** Filename prefix of the {@code SORTOUT} staging file, so an orphan left by a crash is attributable. */
    private static final String WORK_FILE_PREFIX = "carddemo-combtran-sortout-";

    /** Filename suffix of the {@code SORTOUT} staging file: fixed-length records, not a text document. */
    private static final String WORK_FILE_SUFFIX = ".dat";

    /**
     * Default key prefix for {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} generations, the base defined at
     * {@code app/jcl/DEFGDGB.jcl:L55} and written as {@code (+1)} by {@code app/jcl/COMBTRAN.jcl:L37}.
     */
    private static final String DEFAULT_COMBINED_PREFIX = "gdg/transact-combined";

    /**
     * Length in bytes of one combined transaction record: {@value}. Inherited by {@code SORTOUT} from the
     * concatenated input through {@code DCB=(*.SORTIN)} at {@code app/jcl/COMBTRAN.jcl:L35}, and pinned at
     * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} by {@code app/jcl/TRANBKP.jcl:L31} and
     * {@code app/jcl/INTCALC.jcl:L39} where the two inputs are created.
     *
     * <p>Taken from {@link TransactionCombineProcessor#COMBINED_RECORD_LENGTH} rather than restated, so one
     * declaration governs the processor, this job and the boundary comparison.
     */
    private static final int COMBINED_RECORD_LENGTH = TransactionCombineProcessor.COMBINED_RECORD_LENGTH;

    /**
     * Width of the sort key in characters: {@value}. Fixed by the sort symbol {@code TRAN-ID,1,16,CH} at
     * {@code app/jcl/COMBTRAN.jcl:L28}. Taken from {@link TransactionCombineProcessor#TRAN_ID_LENGTH}.
     */
    private static final int TRAN_ID_LENGTH = TransactionCombineProcessor.TRAN_ID_LENGTH;

    /**
     * The charset for every fixed-width byte boundary: {@link StandardCharsets#ISO_8859_1}.
     *
     * <p>Declared explicitly and used on both the upload and the download so that one byte in equals one
     * character out. A multi-byte charset would make an object's length stop being a multiple of
     * {@value #COMBINED_RECORD_LENGTH} the moment a record carried a byte above {@code 0x7F}, and the
     * platform default would make the outcome depend on the host - which Rule 1 Clause C forbids.
     */
    private static final Charset FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Job execution context entry carrying the concrete key {@code STEP05R} created.
     *
     * <p><b>This is the {@code (+1)} handoff.</b> {@code app/jcl/COMBTRAN.jcl:L44} re-references
     * {@code TRANSACT.COMBINED(+1)} - the generation the previous step created in the same job - so the key
     * is published here by the sort and read back by the load, and the latest generation is never
     * re-resolved in between.
     */
    static final String COMBINED_OBJECT_KEY_CONTEXT_ENTRY = "carddemo.transact.combined.object.key";

    /**
     * Job execution context entry carrying the record count {@code STEP05R} wrote, so the load can assert
     * that the object it downloaded holds exactly the records the sort emitted.
     */
    static final String COMBINED_RECORD_COUNT_CONTEXT_ENTRY = "carddemo.transact.combined.record.count";

    /**
     * Job execution context entry carrying how many rows {@code STEP10}'s {@code REPRO} loaded, {@value}.
     *
     * <p>This step's volume metric, and deliberately job-local rather than a Micrometer counter: see
     * {@link #publishLoadedRecordCount(StepExecution, int)} for why advancing the shared untagged
     * records-processed counter from here corrupted it.
     */
    static final String COMBINED_LOADED_RECORD_COUNT_CONTEXT_ENTRY =
            "carddemo.transact.combined.loaded.record.count";

    /**
     * Digits in {@code Long.MAX_VALUE}, and therefore the zero-padding width used by
     * {@link #COMBINED_OBJECT_KEY_TEMPLATE}.
     *
     * <p>Padding is what makes {@code (0)} - "the lexicographically greatest existing prefix" - agree with
     * "the numerically greatest generation". Without it generation 10 would sort below generation 9.
     */
    private static final int KEY_NUMBER_WIDTH = 19;

    /**
     * Key template for one {@code TRANSACT.COMBINED} generation:
     * {@code <prefix>/<job-instance-id>/transact-combined-<ordinal>.dat}.
     */
    private static final String COMBINED_OBJECT_KEY_TEMPLATE =
            "%s/%0" + KEY_NUMBER_WIDTH + "d/transact-combined-%0" + KEY_NUMBER_WIDTH + "d.dat";

    /** Content type for a fixed-width generation: deliberately binary, because trailing padding is data. */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Bean name of the archive-and-reset step that replaces {@code app/jcl/TRANBKP.jcl}, {@value}.
     */
    private static final String ARCHIVE_STEP_BEAN_NAME = "combineTransactionsArchiveStep";

    /**
     * Job parameter instructing this job to archive and reset the transaction relation first, {@value}.
     *
     * <p><b>Finding C-06, severity Critical.</b> {@code app/jcl/COMBTRAN.jcl:L48} REPROs the combined
     * generation into the transaction cluster, and that cluster is empty when it does so - not by accident,
     * but because {@code app/jcl/TRANBKP.jcl} runs first and empties it:
     * {@code :L23-L33} REPROs the cluster out to {@code TRANSACT.BKUP(+1)}, {@code :L37-L45} DELETEs the
     * cluster and its alternate index, and {@code :L51-L67} DEFINEs the cluster afresh with
     * {@code KEYS(16 0) RECORDSIZE(350 350)}. Loading into an already-populated relation collides on the
     * first repeated identifier, so the daily stream could be run once and not again.
     *
     * <p><b>Why a parameter rather than an unconditional step.</b> Whether the archive and reset precede the
     * load is a property of the job <em>stream</em>, not of {@code COMBTRAN}: on the mainframe they are a
     * separate member that an operator schedules ahead of it. A standalone combine against generations that
     * are already in hand is a legitimate use of this job and must not silently empty the relation - which is
     * why the instruction is explicit, is carried as a non-identifying parameter by
     * {@code BatchPipelineOrchestrator}, and defaults to off.
     */
    public static final String JOB_PARAMETER_ARCHIVE_MASTER = "archiveAndResetMaster";

    /** Logical name of the archive output, from the {@code FILEOUT} DD of {@code app/jcl/TRANBKP.jcl:L29}. */
    private static final String DD_TRANSACT_BKUP = "TRANSACT.BKUP";

    /** Default generation prefix for {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}. */
    private static final String DEFAULT_BACKUP_PREFIX = "gdg/transact-bkup";

    /**
     * Archive key template: prefix, then the job instance as the generation, then the fixed object name.
     *
     * <p><b>The {@code generation=} segment and the fixed terminal name are both load-bearing; neither is
     * decoration.</b> {@link com.cardemo.batch.jobs.TransactionReportJob} already writes this base with
     * {@code <prefix>/generation=<19-digit>/TRANSACT.BKUP}, and
     * {@link CombinedTransactionReader} resolves {@code (0)} by taking the lexicographically greatest
     * <em>generation segment</em>. A bare numeric segment would sort <b>below</b> every
     * {@code generation=...} segment, because {@code '0'} precedes {@code 'g'} - so an archive written under
     * one would never be resolved as the current generation while any report-branch backup existed, and the
     * combine would silently sort last run's master instead of this run's. The two producers of this base
     * must therefore share one convention.
     *
     * <p>The terminal name is fixed rather than carrying the step execution, so a restarted archive
     * <b>replaces</b> its own object instead of adding a second one to the same generation - which
     * {@link CombinedTransactionReader} rejects as an ambiguous generation, correctly, since a GDG
     * generation is one dataset.
     */
    private static final String BACKUP_OBJECT_KEY_TEMPLATE =
            "%s/generation=%0" + KEY_NUMBER_WIDTH + "d/" + DD_TRANSACT_BKUP;

    /** Rows read per page while archiving, so the whole relation is never resident. */
    private static final int ARCHIVE_PAGE_SIZE = 1_000;

    /** Job execution context entry carrying the archive object key this run created. */
    public static final String BACKUP_OBJECT_KEY_CONTEXT_ENTRY = "carddemo.gdg.transact-bkup.createdKey";

    /** Job execution context entry carrying how many records the archive holds. */
    public static final String BACKUP_RECORD_COUNT_CONTEXT_ENTRY =
            "carddemo.gdg.transact-bkup.recordCount";

    /**
     * Logical name of the sort output, the DD name at {@code app/jcl/COMBTRAN.jcl:L33} that
     * {@code DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} at {@code :L37} allocates.
     */
    private static final String DD_SORTOUT = "SORTOUT";

    /**
     * Logical name of the concatenated sort input, the DD name at {@code app/jcl/COMBTRAN.jcl:L23} whose
     * second, unnamed statement at {@code :L25} concatenates {@code SYSTRAN(0)}.
     */
    private static final String DD_SORTIN = "SORTIN";

    /**
     * Logical name of the load input, the DD name at {@code app/jcl/COMBTRAN.jcl:L43} that
     * {@code REPRO INFILE(TRANSACT)} at {@code :L48} names.
     */
    private static final String DD_TRANSACT = "TRANSACT";

    /**
     * Logical name of the load target, the DD name at {@code app/jcl/COMBTRAN.jcl:L45} that
     * {@code REPRO OUTFILE(TRANVSAM)} at {@code :L48} names, resolving to
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} at {@code :L46}.
     */
    private static final String DD_TRANVSAM = "TRANVSAM";

    /**
     * Eight-character abend culprit. The JCL member name stands in for the program name every other job
     * reports, because <b>this job has no program</b>. Matches the culprit
     * {@link TransactionCombineProcessor} reports, so one run yields one culprit.
     */
    private static final String ABEND_CULPRIT = "COMBTRAN";

    /** Abend code {@value FatalProcessingException#BATCH_ABEND_CODE}, rendered as the four-character field. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Operation label carried by a failed upload of {@code SORTOUT}. */
    private static final String OPERATION_WRITE = "WRITE";

    /** Operation label carried by a failed download of the combined generation. */
    private static final String OPERATION_READ = "READ";

    /** Operation label carried by a failed bulk load into the transaction relation. */
    private static final String OPERATION_LOAD = "LOAD";

    /** Exact file status {@code '00'}: the only value the guards accept. */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.code().orElseThrow();

    /**
     * A representative member of the {@code '9x'} physical-I/O family, used when object storage fails.
     *
     * <p>Built from {@link FileStatus#IO_ERROR_FIRST_BYTE} rather than written as a literal, so the family's
     * first byte has one definition. <b>None of the three tree-wide leniency carve-outs applies to this
     * job</b> - they are the category-balance upsert, the default disclosure-group retry and the file-service
     * call sites - so every status other than {@code '00'} is an error here.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** Legacy abend diagnostic emitted immediately before a fatal exception. */
    private static final String MSG_ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** Diagnostic for a failed emission of the combined generation. */
    private static final String MSG_ERROR_WRITING_COMBINED_FILE = "ERROR WRITING COMBINED TRANSACTION FILE";

    /**
     * Diagnostic for a failed archive write, in the corpus's own register: an upper-case statement of what
     * was being done, naming the dataset rather than the mechanism.
     */
    private static final String MSG_ERROR_WRITING_BACKUP = "ERROR WRITING TRANSACTION BACKUP FILE";

    /** Diagnostic for a failed read of the combined generation. */
    private static final String MSG_ERROR_READING_COMBINED_FILE = "ERROR READING COMBINED TRANSACTION FILE";

    /** Diagnostic for a failed bulk load into the transaction relation. */
    private static final String MSG_ERROR_LOADING_TRANSACTION_FILE = "ERROR LOADING TRANSACTION FILE";

    /** Abend reason when the sort input cannot be read. */
    private static final String REASON_SORT_FAILED = "SORT FAILED";

    /** Abend reason when the combined generation cannot be written. */
    private static final String REASON_WRITE_FAILED = "WRITE FAILED";

    /** Abend reason when the {@code TRANSACT.BKUP} archive could not be written. */
    private static final String REASON_BACKUP_WRITE_FAILED = "BACKUP WRITE FAILED";

    /** Abend reason when the combined generation cannot be read back. */
    private static final String REASON_READ_FAILED = "READ FAILED";

    /** Abend reason when a record is not exactly {@value #COMBINED_RECORD_LENGTH} bytes. */
    private static final String REASON_BAD_RECORD_LENGTH = "RECORD LENGTH VIOLATION";

    /** Abend reason when the {@code (+1)} key the sort published is missing from the execution context. */
    private static final String REASON_NO_GENERATION_KEY = "NO GENERATION KEY";

    /** Abend reason when a zoned-decimal amount cannot be decoded. */
    private static final String REASON_BAD_NUMERIC = "NUMERIC FIELD VIOLATION";

    /** Exit code of a step or flow that completed cleanly: return code 0. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /**
     * Exit code standing for return code 4.
     *
     * <p><b>This job never originates it</b>, for the reason given in the class documentation: it has no
     * COBOL program and therefore no reject counter. The arm exists because the decider's contract is shared
     * with the orchestrated pipeline, which gates on the full code set, and because an outcome the decider
     * did not recognise would otherwise fall through to success.
     */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** Exit code of a failed step or flow: return code 8. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /** Exit code of an abended step or flow: return code 12. */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** The Spring Batch wildcard transition pattern. */
    private static final String EXIT_CODE_ANY = "*";

    /** Prefix of the deterministic correlation identifier this job mints when no outer scope owns one. */
    private static final String CORRELATION_ID_PREFIX = "combtran-";

    // ------------------------------------------------------------------------------------------------
    // The 350-byte offset map of app/cpy/CVTRA05Y.cpy, expressed as zero-based [begin, end) bounds.
    //
    // These exist because REPRO INFILE at app/jcl/COMBTRAN.jcl:L48 has to read back what SORTOUT at
    // :L37 wrote, and no shared decoder exists: TransactionWriter#composeFixedWidthImage is the
    // canonical encoder, and the inverse operation is owned by nothing. The bounds are therefore the
    // encoder's field widths, read in the same order, and #verifyOffsetMap asserts at class
    // initialisation that they still tile the record exactly - so a width changed on one side and not
    // the other fails fast at startup instead of silently shifting every field.
    //
    // SYMNAMES at :L28 corroborates the first entry: TRAN-ID,1,16,CH can only be bytes 1-16.
    // ------------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)}, bytes 1-16, and the sort key of {@code app/jcl/COMBTRAN.jcl:L28}. */
    private static final int TRAN_ID_BEGIN = 0;

    /** {@code TRAN-TYPE-CD PIC X(02)}, bytes 17-18. */
    private static final int TRAN_TYPE_CD_BEGIN = TRAN_ID_BEGIN + TRAN_ID_LENGTH;

    /** {@code TRAN-CAT-CD PIC 9(04)}, bytes 19-22. */
    private static final int TRAN_CAT_CD_BEGIN = TRAN_TYPE_CD_BEGIN + 2;

    /** {@code TRAN-SOURCE PIC X(10)}, bytes 23-32. */
    private static final int TRAN_SOURCE_BEGIN = TRAN_CAT_CD_BEGIN + 4;

    /** {@code TRAN-DESC PIC X(100)}, bytes 33-132. */
    private static final int TRAN_DESC_BEGIN = TRAN_SOURCE_BEGIN + 10;

    /** {@code TRAN-AMT PIC S9(09)V99}, bytes 133-143, zoned decimal with a trailing sign overpunch. */
    private static final int TRAN_AMT_BEGIN = TRAN_DESC_BEGIN + 100;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}, bytes 144-152. */
    private static final int TRAN_MERCHANT_ID_BEGIN = TRAN_AMT_BEGIN + 11;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}, bytes 153-202. */
    private static final int TRAN_MERCHANT_NAME_BEGIN = TRAN_MERCHANT_ID_BEGIN + 9;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}, bytes 203-252. */
    private static final int TRAN_MERCHANT_CITY_BEGIN = TRAN_MERCHANT_NAME_BEGIN + 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}, bytes 253-262. */
    private static final int TRAN_MERCHANT_ZIP_BEGIN = TRAN_MERCHANT_CITY_BEGIN + 50;

    /** {@code TRAN-CARD-NUM PIC X(16)}, bytes 263-278. Never logged in full; see {@link #maskCardNumber}. */
    private static final int TRAN_CARD_NUM_BEGIN = TRAN_MERCHANT_ZIP_BEGIN + 10;

    /** {@code TRAN-ORIG-TS PIC X(26)}, bytes 279-304. Twenty-six characters, passed through untouched. */
    private static final int TRAN_ORIG_TS_BEGIN = TRAN_CARD_NUM_BEGIN + TRAN_ID_LENGTH;

    /** {@code TRAN-PROC-TS PIC X(26)}, bytes 305-330. Twenty-six characters, passed through untouched. */
    private static final int TRAN_PROC_TS_BEGIN = TRAN_ORIG_TS_BEGIN + 26;

    /** {@code FILLER PIC X(20)}, bytes 331-350: not a column, but part of the record. */
    private static final int TRAN_FILLER_BEGIN = TRAN_PROC_TS_BEGIN + 26;

    /** Number of decimal places {@code TRAN-AMT PIC S9(09)V99} carries: {@value}. */
    private static final int TRAN_AMT_SCALE = 2;

    /** Zero-based index of the sign overpunch within {@code TRAN-AMT}: its final byte. */
    private static final int TRAN_AMT_SIGN_INDEX = TRAN_MERCHANT_ID_BEGIN - 1;

    /**
     * Positive zoned-decimal overpunch alphabet: <code>'&#123;'</code> is {@code +0} and {@code 'A'} to {@code 'I'} are
     * {@code +1} to {@code +9}. The character's index in this string is its digit value.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Negative zoned-decimal overpunch alphabet: <code>'&#125;'</code> is {@code -0} and {@code 'J'} to {@code 'R'} are
     * {@code -1} to {@code -9}.
     *
     * <p>Both alphabets are required, not decorative. {@code app/data/ASCII/dailytran.txt} carries genuinely
     * negative amounts, so a decode that accepted only the positive alphabet would abend on real data, and one
     * that normalised the sign away would corrupt the cycle arithmetic downstream.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Version a freshly loaded row carries, matching the {@code NOT NULL BIGINT} {@code version} column. */
    private static final long INITIAL_VERSION = 0L;

    /** Number of bind parameters in {@link #LOAD_INSERT_SQL}: fourteen columns, fourteen placeholders. */
    private static final int LOAD_PARAMETER_COUNT = 14;

    /**
     * The bulk load, translating {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} at
     * {@code app/jcl/COMBTRAN.jcl:L48} - the same card that {@code app/ctl/REPROCT.ctl:L15} carries in its
     * parameterised form.
     *
     * <p><b>A static, fully parameterised constant with {@code ?} placeholders.</b> It is never assembled by
     * concatenation, never interpolated and never built from a value that arrived with the data, which is what
     * Rule 1 Clause D asks for when it names shell injection as a pattern to flag. The relation name is
     * quoted because {@code transaction} is a reserved word.
     *
     * <p><b>There is deliberately no {@code ON CONFLICT} clause.</b> {@code REPRO} into a keyed cluster fails
     * on a duplicate key, so the insert must fail too; see
     * {@link TransactionCombineProcessor#translateLoadFailure(Transaction, DataAccessException)}.
     */
    private static final String LOAD_INSERT_SQL = """
            INSERT INTO "transaction" (
                tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
                tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip,
                tran_card_num, tran_orig_ts, tran_proc_ts, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    static {
        verifyOffsetMap();
    }

    /** Spring Batch metadata store, injected and never declared here. */
    private final JobRepository jobRepository;

    /** Transaction manager the two steps run under, injected and never declared here. */
    private final PlatformTransactionManager transactionManager;

    /**
     * The canonical {@value #COMBINED_RECORD_LENGTH}-byte encoder.
     *
     * <p>Reused rather than re-implemented: {@link TransactionWriter#composeFixedWidthImage(Transaction)} is a
     * pure function and already the single definition of the record image, so a second encoder here would be
     * a second thing able to drift from {@code app/cpy/CVTRA05Y.cpy}. Clause C, avoid duplication.
     */
    private final TransactionWriter transactionWriter;

    /** The bulk-load channel. Injected, never constructed; this class declares no {@code JdbcTemplate} bean. */
    private final JdbcTemplate jdbcTemplate;

    /** Object access for the generation bucket, supplied by {@code AwsConfig}. Never constructed here. */
    private final S3Operations objectStorage;

    /** The shared file-status translator: every I/O outcome on both steps passes through it. */
    private final FileStatusMapper fileStatusMapper;

    /** Registered job name, from {@code carddemo.batch.jobs.combtran.name}. */
    private final String jobName;

    /** Rows per bulk-load batch, from {@code carddemo.batch.combtran.chunk-size}. */
    private final int chunkSize;

    /** The versioned generation bucket, from {@code carddemo.aws.s3.batch-output-bucket}. */
    private final String outputBucket;

    /** The {@code TRANSACT.COMBINED} key prefix, from {@code carddemo.aws.s3.gdg-prefixes.transact-combined}. */
    private final String combinedPrefix;

    /**
     * The transaction relation, read in key order to produce the {@code TRANSACT.BKUP(+1)} archive of
     * {@code app/jcl/TRANBKP.jcl:L23-L33}. See {@link #JOB_PARAMETER_ARCHIVE_MASTER}.
     */
    private final TransactionRepository transactionRepository;

    /** Generation prefix replacing {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}. */
    private final String backupPrefix;

    /**
     * Creates the job configuration and validates every injected value, so a mis-wired context fails during
     * refresh with a diagnosis rather than at first use with a {@link NullPointerException}.
     *
     * @param jobRepository the Spring Batch metadata store; must not be {@code null}
     * @param transactionManager the transaction manager both steps run under; must not be {@code null}
     * @param transactionWriter the canonical fixed-width encoder; must not be {@code null}
     * @param jdbcTemplate the bulk-load channel; must not be {@code null}
     * @param objectStorage read and write access to the generation bucket; must not be {@code null}
     * @param fileStatusMapper the shared file-status translator; must not be {@code null}
     * @param configuredJobName the registered job name, from {@code carddemo.batch.jobs.combtran.name}
     * @param configuredChunkSize rows per bulk-load batch, from {@code carddemo.batch.combtran.chunk-size}
     * @param configuredOutputBucket the generation bucket, from {@code carddemo.aws.s3.batch-output-bucket}
     * @param configuredCombinedPrefix the {@code TRANSACT.COMBINED} prefix, from
     * @param transactionRepository source of the {@code app/jcl/TRANBKP.jcl} archive unload
     * @param configuredBackupPrefix {@code TRANSACT.BKUP} generation prefix the archive writes, default
     *     {@value #DEFAULT_BACKUP_PREFIX}
     *     {@code carddemo.aws.s3.gdg-prefixes.transact-combined}
     * @throws NullPointerException if a collaborator is {@code null}
     * @throws IllegalArgumentException if a name, size, bucket or prefix is absent or out of range
     */
    public CombineTransactionsJob(
            final JobRepository jobRepository,
            @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
            final TransactionWriter transactionWriter,
            final JdbcTemplate jdbcTemplate,
            final S3Operations objectStorage,
            final FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.jobs.combtran.name:" + DEFAULT_JOB_NAME + "}")
                    final String configuredJobName,
            @Value("${carddemo.batch.combtran.chunk-size:${carddemo.batch.chunk-size:"
                    + DEFAULT_CHUNK_SIZE + "}}") final int configuredChunkSize,
            @Value("${carddemo.aws.s3.batch-output-bucket:}") final String configuredOutputBucket,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-combined:" + DEFAULT_COMBINED_PREFIX + "}")
                    final String configuredCombinedPrefix,
            final TransactionRepository transactionRepository,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:" + DEFAULT_BACKUP_PREFIX + "}")
                    final String configuredBackupPrefix) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactionWriter =
                Objects.requireNonNull(transactionWriter, "transactionWriter must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.jobName = requireConfiguredText(configuredJobName, "carddemo.batch.jobs.combtran.name");
        this.chunkSize = requirePositive(configuredChunkSize, "carddemo.batch.combtran.chunk-size");
        this.outputBucket =
                requireConfiguredText(configuredOutputBucket, "carddemo.aws.s3.batch-output-bucket");
        this.combinedPrefix = requireGenerationPrefix(
                configuredCombinedPrefix, "carddemo.aws.s3.gdg-prefixes.transact-combined");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.backupPrefix = requireGenerationPrefix(
                configuredBackupPrefix, "carddemo.aws.s3.gdg-prefixes.transact-bkup");
    }

    /**
     * {@code STEP05R} - {@code //STEP05R  EXEC PGM=SORT} at {@code app/jcl/COMBTRAN.jcl:L22}.
     *
     * <p>Reads the concatenated {@code SORTIN} of {@code :L23-L26} in {@code TRAN-ID} order, validates each
     * record through the processor, and emits one {@code TRANSACT.COMBINED(+1)} generation of
     * {@value #COMBINED_RECORD_LENGTH}-byte records, publishing its concrete key for {@code STEP10}.
     *
     * <p>A tasklet rather than a chunk-oriented step, for two reasons that are both about correctness rather
     * than taste. {@code SORTOUT} at {@code :L33-L37} is a <b>single</b> generation, and a chunk-oriented
     * writer emits one object per chunk, which would leave {@code :L44}'s {@code (+1)} reference ambiguous.
     * And a tasklet keeps every piece of per-run state - the staging file, the counters, the resolved key -
     * in method locals, so this singleton holds no mutable state that two concurrent executions could
     * share.
     *
     * <p>The reader and processor arrive as method parameters rather than fields because
     * {@link CombinedTransactionReader} is {@code @StepScope}: injecting the scoped proxy per bean method is
     * the idiom the sibling jobs use, and it keeps this singleton free of step-scoped references.
     *
     * @param combinedTransactionReader the ordered concatenated {@code SORTIN}; must not be {@code null}
     * @param transactionCombineProcessor the per-record validator; must not be {@code null}
     * @return the sort step, registered as {@value #SORT_STEP_BEAN_NAME}, never {@code null}
     */
    @Bean(SORT_STEP_BEAN_NAME)
    public Step combineTransactionsSortStep(
            final CombinedTransactionReader combinedTransactionReader,
            final TransactionCombineProcessor transactionCombineProcessor) {

        return new StepBuilder(SORT_STEP_BEAN_NAME, jobRepository)
                .tasklet(
                        combineTransactionsSortTasklet(
                                combinedTransactionReader, transactionCombineProcessor),
                        transactionManager)
                .build();
    }

    /**
     * {@code app/jcl/TRANBKP.jcl} - archive the transaction master, then leave it empty.
     *
     * <p>Runs before {@code STEP05R} and only when {@value #JOB_PARAMETER_ARCHIVE_MASTER} instructs it; see
     * that constant for finding C-06 and for why the instruction is explicit rather than implied.
     *
     * <p>The member's three steps become one, and the consolidation is deliberate. {@code :L23-L33} REPROs
     * the cluster to {@code TRANSACT.BKUP(+1)} with {@code DCB=(LRECL=350,RECFM=FB)}; {@code :L37-L45}
     * deletes the cluster and its alternate index, each tolerating a not-found with
     * {@code IF MAXCC LE 08 THEN SET MAXCC = 0}; {@code :L51-L67} defines the cluster again under
     * {@code COND=(4,LT)}. The delete-and-redefine pair has one relational meaning - <em>the relation exists
     * and holds nothing</em> - and the table itself is owned by the Flyway migrations rather than by a batch
     * step, so nothing is dropped and nothing is created. Performing the archive and the emptying in one step
     * is additionally the safer ordering: the sequence that must never occur is emptying without having
     * archived, and a single unit of work cannot produce it.
     *
     * <p>The archive is a single object, because {@code TRANSACT.BKUP(+1)} is a single sequential dataset -
     * the same invariant the interest job's generation carries. It is streamed a page at a time rather than
     * assembled whole, so a relation larger than the heap is still archivable.
     *
     * @return the archive-and-reset step, registered as {@value #ARCHIVE_STEP_BEAN_NAME}, never {@code null}
     */
    @Bean(ARCHIVE_STEP_BEAN_NAME)
    public Step combineTransactionsArchiveStep() {
        return new StepBuilder(ARCHIVE_STEP_BEAN_NAME, jobRepository)
                .tasklet(combineTransactionsArchiveTasklet(), transactionManager)
                .build();
    }

    /**
     * {@code STEP10} - {@code //STEP10 EXEC PGM=IDCAMS} at {@code app/jcl/COMBTRAN.jcl:L41}.
     *
     * <p>Reads back the exact generation {@code STEP05R} published and bulk-loads it into the transaction
     * relation, translating {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} at {@code :L48}.
     *
     * <p>The processor is injected here solely for
     * {@link TransactionCombineProcessor#translateLoadFailure(Transaction, DataAccessException)}, which owns
     * the store-failure taxonomy for this job so that the duplicate-key message has one definition.
     *
     * @param transactionCombineProcessor the owner of the load-failure translation; must not be {@code null}
     * @return the load step, registered as {@value #LOAD_STEP_BEAN_NAME}, never {@code null}
     */
    @Bean(LOAD_STEP_BEAN_NAME)
    public Step combineTransactionsLoadStep(
            final TransactionCombineProcessor transactionCombineProcessor) {

        return new StepBuilder(LOAD_STEP_BEAN_NAME, jobRepository)
                .tasklet(combineTransactionsLoadTasklet(transactionCombineProcessor), transactionManager)
                .build();
    }

    /**
     * The archive, sort and load steps in source order, with <b>no gate between the sort and the load</b>.
     *
     * <p>This is the one structural fact about {@code app/jcl/COMBTRAN.jcl} that is easiest to get wrong, so
     * it is spelled out. <b>Neither {@code //STEP05R  EXEC PGM=SORT} at {@code :L22} nor
     * {@code //STEP10 EXEC PGM=IDCAMS} at {@code :L41} carries a {@code COND} parameter.</b> Absent
     * {@code COND}, a JCL step runs regardless of a preceding step's return code and is suppressed only by an
     * abend. The wildcard transition from the sort to the load therefore reproduces the source exactly, and
     * <b>no {@link JobExecutionDecider} is placed between the two steps</b>: inventing gating the member does
     * not have would diverge just as surely as dropping gating it does have.
     *
     * <p>Spring Batch resolves the most specific matching pattern first, so the explicit {@code FAILED} and
     * {@code ABEND} arms take precedence over the wildcard and only those two divert the sort away from the
     * load - which is precisely the abend suppression the platform applies. The decider is reached
     * <b>after</b> the load, or on a diverted failure, and exists only to distinguish the four return codes
     * for the orchestrated pipeline; the unrecognised arm fails rather than falling through, so a future
     * outcome cannot silently pass.
     *
     * @param archiveStep the {@code app/jcl/TRANBKP.jcl} archive-and-reset step, injected by bean name
     * @param sortStep {@code STEP05R}, injected by bean name so the flow cannot bind to another assignable
     *     step
     * @param loadStep {@code STEP10}, injected by bean name
     * @return the complete combine flow, never {@code null}
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow combineTransactionsFlow(
            @Qualifier(ARCHIVE_STEP_BEAN_NAME) final Step archiveStep,
            @Qualifier(SORT_STEP_BEAN_NAME) final Step sortStep,
            @Qualifier(LOAD_STEP_BEAN_NAME) final Step loadStep) {

        final JobExecutionDecider returnCodeDecider = new CombineTransactionsReturnCodeDecider();

        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                // app/jcl/TRANBKP.jcl precedes the member this job replaces, and its failure arms mirror the
                // sort's: only a failure or an abend diverts, because an archive that did not complete must
                // not be followed by a load into a relation whose emptying is now in doubt.
                .start(archiveStep).on(EXIT_CODE_FAILED).to(returnCodeDecider)
                .from(archiveStep).on(EXIT_CODE_ABEND).to(returnCodeDecider)
                .from(archiveStep).on(EXIT_CODE_ANY).to(sortStep)
                .from(sortStep).on(EXIT_CODE_FAILED).to(returnCodeDecider)
                .from(sortStep).on(EXIT_CODE_ABEND).to(returnCodeDecider)
                .from(sortStep).on(EXIT_CODE_ANY).to(loadStep)
                .from(loadStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCodeDecider).on(EXIT_CODE_FAILED).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ABEND).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * The job itself: the whole of {@code app/jcl/COMBTRAN.jcl}.
     *
     * <p>No {@link org.springframework.batch.core.JobParametersValidator} is attached, because
     * <b>the member declares no parameter</b>: it has no {@code PARM}, no {@code DATEPARM} card and no
     * symbolic substitution - only the job card at {@code :L1-L2} and the two {@code EXEC} statements. A
     * validator here would assert a contract the source does not have. No incrementer is declared either, so
     * a re-run with the same parameters is refused by Spring Batch rather than quietly producing a second
     * combined generation from the same inputs.
     *
     * <p>The diagnostic-context listener is a plain nested object rather than a bean: this package may
     * contribute only {@link Job}, {@link Step} and {@link Flow} beans, and the listener needs no container
     * singleton.
     *
     * @param combineTransactionsFlow the archive, sort and load flow, injected by bean name
     * @return the job, registered under the configured name, never {@code null}
     */
    @Bean(JOB_BEAN_NAME)
    public Job combineTransactionsJob(
            @Qualifier(FLOW_BEAN_NAME) final Flow combineTransactionsFlow) {

        return new JobBuilder(jobName, jobRepository)
                .listener(new CombineTransactionsJobListener())
                .start(combineTransactionsFlow)
                .end()
                .build();
    }

    /**
     * Creates the {@code app/jcl/TRANBKP.jcl} tasklet without registering an additional bean.
     *
     * @return the archive-and-reset tasklet, never {@code null}
     */
    private Tasklet combineTransactionsArchiveTasklet() {
        return (contribution, chunkContext) -> {
            executeTranbkp(chunkContext.getStepContext().getStepExecution());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Archives the transaction relation to {@code TRANSACT.BKUP(+1)} and then empties it.
     *
     * <p>A no-operation unless {@value #JOB_PARAMETER_ARCHIVE_MASTER} is {@code true}. The parameter is read
     * from the job rather than a property because it is a per-run instruction from the stream, not a
     * deployment setting.
     *
     * <p>An empty relation still writes its generation. A zero-length object is how an empty dataset is
     * expressed - the generation exists and holds nothing - and suppressing it would make the following
     * {@code SORTIN} leg absent rather than empty, which are different states the reader distinguishes.
     *
     * @param stepExecution the running step, whose context receives the created key
     */
    private void executeTranbkp(final StepExecution stepExecution) {
        final JobExecution jobExecution = stepExecution.getJobExecution();
        if (!archiveInstructed(jobExecution)) {
            LOG.info("{} not instructed for this run, so the transaction relation is neither archived nor"
                            + " emptied; app/jcl/TRANBKP.jcl is a separate member of the stream and a"
                            + " standalone combine does not include it", DD_TRANSACT_BKUP);
            return;
        }

        final long rowCount = transactionRepository.count();
        final long expectedBytes = rowCount * TransactionWriter.RECORD_LENGTH;
        final String objectKey =
                composeBackupObjectKey(jobExecution.getJobInstance().getInstanceId());

        String ioStatus = STATUS_SUCCESS;
        RuntimeException failure = null;
        try (InputStream archive = new SequenceInputStream(new ArchivePageEnumeration(rowCount))) {
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(OBJECT_CONTENT_TYPE)
                    .contentLength(Long.valueOf(expectedBytes))
                    .build();
            objectStorage.upload(outputBucket, objectKey, archive, metadata);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException | RuntimeException cause) {
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            failure = cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(cause.getMessage(), cause);
        }
        guardObjectOperation(ioStatus, DD_TRANSACT_BKUP, OPERATION_WRITE,
                MSG_ERROR_WRITING_BACKUP, REASON_BACKUP_WRITE_FAILED, failure);

        // Only now is the relation emptied. app/jcl/TRANBKP.jcl:L37-L45 deletes the cluster and :L51-L67
        // defines it again; relationally that is one statement, and the schema stays where it belongs, in the
        // Flyway migrations. The DELETE tolerating a not-found cluster has no analogue: a table that is
        // already empty simply removes no rows.
        final int removed = jdbcTemplate.update("DELETE FROM \"transaction\"");

        stepExecution.getExecutionContext().putString(BACKUP_OBJECT_KEY_CONTEXT_ENTRY, objectKey);
        stepExecution.getExecutionContext().putLong(BACKUP_RECORD_COUNT_CONTEXT_ENTRY, rowCount);
        final ExecutionContext jobContext = jobExecution.getExecutionContext();
        jobContext.putString(BACKUP_OBJECT_KEY_CONTEXT_ENTRY, objectKey);
        jobContext.putLong(BACKUP_RECORD_COUNT_CONTEXT_ENTRY, rowCount);

        LOG.info("{} archived {} records ({} bytes) and emptied the transaction relation, removing {} rows;"
                        + " app/jcl/COMBTRAN.jcl:L48 now loads into an empty target, which is what makes the"
                        + " daily stream repeatable", DD_TRANSACT_BKUP, Long.valueOf(rowCount),
                Long.valueOf(expectedBytes), Integer.valueOf(removed));
    }

    /**
     * Reads the per-run archive instruction.
     *
     * @param jobExecution the running execution
     * @return {@code true} when the stream asked for the archive and reset
     */
    private static boolean archiveInstructed(final JobExecution jobExecution) {
        return Boolean.parseBoolean(
                jobExecution.getJobParameters().getString(JOB_PARAMETER_ARCHIVE_MASTER, "false"));
    }

    /**
     * The key of the {@code TRANSACT.BKUP(+1)} generation this run creates.
     *
     * <p>The job instance is the generation, which makes the generation monotonic in creation time: Spring
     * Batch allocates instance identifiers from one sequence, so a later run always sorts above an earlier
     * one <b>across jobs as well as within one</b>. That is what lets the report branch's producer and this
     * one share the base without either shadowing the other, and it is the property
     * {@code (0)} depends on.
     *
     * @param jobInstanceId the job instance, scoping the generation to one logical run
     * @return the object key, never {@code null}
     */
    private String composeBackupObjectKey(final long jobInstanceId) {
        return String.format(Locale.ROOT, BACKUP_OBJECT_KEY_TEMPLATE, backupPrefix,
                Long.valueOf(jobInstanceId));
    }

    /**
     * Serves the archive one page of rows at a time, so the whole relation is never resident.
     *
     * <p>{@link SequenceInputStream} pulls lazily, so each page is fetched, rendered and discarded before the
     * next is read. Ordering is by the identifier, which is the cluster's own key at
     * {@code app/jcl/TRANBKP.jcl:L58} {@code KEYS(16 0)}, so the archive is in the order a sequential
     * unload of the cluster would have produced.
     */
    private final class ArchivePageEnumeration implements Enumeration<InputStream> {

        /** How many rows the archive was declared to hold. */
        private final long declaredRows;

        /** The next page to fetch. */
        private int page;

        /** How many rows have been served, so a relation that changed under the read is detectable. */
        private long served;

        /**
         * Creates the enumeration.
         *
         * @param declaredRows the row count the content length was computed from
         */
        private ArchivePageEnumeration(final long declaredRows) {
            this.declaredRows = declaredRows;
        }

        @Override
        public boolean hasMoreElements() {
            return served < declaredRows;
        }

        @Override
        public InputStream nextElement() {
            if (!hasMoreElements()) {
                throw new NoSuchElementException("the declared archive length has already been served");
            }
            final List<Transaction> rows = transactionRepository.findAll(
                    PageRequest.of(page, ARCHIVE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "transactionId")))
                    .getContent();
            page++;
            if (rows.isEmpty()) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s declared %d records but the relation ran out after %d. The archive of"
                                + " app/jcl/TRANBKP.jcl:L23-L33 must be a complete unload, so a relation"
                                + " that shrank under the read is a failure rather than a short object.",
                        DD_TRANSACT_BKUP, Long.valueOf(declaredRows), Long.valueOf(served)),
                        BACKUP_OBJECT_KEY_CONTEXT_ENTRY, DD_TRANSACT_BKUP);
            }
            final StringBuilder image =
                    new StringBuilder(rows.size() * TransactionWriter.RECORD_LENGTH);
            for (final Transaction row : rows) {
                if (served >= declaredRows) {
                    break;
                }
                image.append(transactionWriter.composeFixedWidthImage(row));
                served++;
            }
            return new ByteArrayInputStream(image.toString().getBytes(FIXED_WIDTH_CHARSET));
        }
    }

    /**
     * Creates the {@code STEP05R} tasklet without registering an additional bean.
     *
     * @param reader the ordered concatenated {@code SORTIN}
     * @param processor the per-record validator
     * @return the sort tasklet, never {@code null}
     */
    private Tasklet combineTransactionsSortTasklet(
            final CombinedTransactionReader reader,
            final TransactionCombineProcessor processor) {

        return (contribution, chunkContext) -> {
            executeStep05r(chunkContext.getStepContext().getStepExecution(), reader, processor);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Creates the {@code STEP10} tasklet without registering an additional bean.
     *
     * @param processor the owner of the load-failure translation
     * @return the load tasklet, never {@code null}
     */
    private Tasklet combineTransactionsLoadTasklet(final TransactionCombineProcessor processor) {
        return (contribution, chunkContext) -> {
            executeStep10(chunkContext.getStepContext().getStepExecution(), processor);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@code //STEP05R  EXEC PGM=SORT} - {@code app/jcl/COMBTRAN.jcl:L22} through {@code :L37}.
     *
     * <p>The step in source order:
     * <ol>
     *   <li><b>Open the concatenated {@code SORTIN}</b> ({@code :L23-L26}).
     *       {@link CombinedTransactionReader#open(ExecutionContext)} resolves both {@code (0)} generations
     *       once and only once, so no key is ever resolved mid-read.</li>
     *   <li><b>Read in {@code TRAN-ID} order</b> ({@code :L28}, {@code :L30}). The reader interleaves the two
     *       sources with {@link TransactionCombineProcessor#TRAN_ID_ASCENDING} and validates that neither
     *       source nor the merged output descends, so this loop <b>does not re-sort</b>; a {@code null}
     *       return is end of file and is loop termination, never an exception.</li>
     *   <li><b>Validate and render each record</b> at exactly {@value #COMBINED_RECORD_LENGTH} bytes, the
     *       geometry {@code DCB=(*.SORTIN)} at {@code :L35} inherits.</li>
     *   <li><b>Write one {@code SORTOUT} generation</b> ({@code :L33-L37}) and publish its concrete key for
     *       {@code STEP10}'s {@code (+1)} reference at {@code :L44}.</li>
     *   <li><b>Close the input</b>, unconditionally, in a {@code finally}.</li>
     * </ol>
     *
     * <p><b>Each record image is written straight through to a bounded staging file</b>, because
     * {@code SORTOUT} is one generation and {@code :L33-L37} allocates it on {@code UNIT=SYSDA} - the sort's
     * output is disk, and staging it to disk is the faithful model as well as the bounded one. An earlier
     * revision accumulated the whole generation in storage and needed an invented per-run record cap to keep
     * that from exhausting the heap; the JCL declares no such cap, and with the accumulation gone none is
     * needed. Contrast {@code app/cbl/CBSTM03A.CBL}, whose fixed table silently overran - that hazard is not
     * reproduced here, and streaming rather than a ceiling is what replaces it. (That member's name is upper
     * case on disk, unlike its twenty-six lower-case siblings; the casing is load-bearing in a citation.)
     *
     * @param stepExecution the running step, carrying the job execution the key is published on
     * @param reader the ordered concatenated {@code SORTIN}
     * @param processor the per-record validator
     * @throws FatalProcessingException if the input cannot be read, a record is not exactly
     *     {@value #COMBINED_RECORD_LENGTH} bytes, or the write fails
     * @throws CardDemoException if the reader or processor rejects a record; the typed subtype is theirs
     */
    private void executeStep05r(
            final StepExecution stepExecution,
            final CombinedTransactionReader reader,
            final TransactionCombineProcessor processor) {

        final ExecutionContext stepContext = stepExecution.getExecutionContext();
        final Path work = createWorkFile();
        int recordCount = 0;

        try {
            // 1000-SORTIN-OPEN equivalent: app/jcl/COMBTRAN.jcl:L23-L26. The reader owns both (0)
            // resolutions and owns the absent-generation decisions; neither is duplicated here.
            openSortInput(reader, stepContext);
            try (OutputStream sortOut = new BufferedOutputStream(
                    Files.newOutputStream(work, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    WORK_BUFFER_BYTES)) {

                // The read loop of SORT FIELDS=(TRAN-ID,A) at :L30 over TRAN-ID,1,16,CH at :L28. Each
                // 350-byte image is written straight through to the SORTOUT work file, so the resident set
                // is one record plus the stream buffer whatever the generation holds.
                for (Transaction record = readNextSortInput(reader);
                        record != null;
                        record = readNextSortInput(reader)) {

                    final Transaction validated = processor.process(record);
                    sortOut.write(requireCombinedRecordImage(validated).getBytes(FIXED_WIDTH_CHARSET));
                    recordCount++;
                }
            } catch (final IOException cause) {
                throw abendProgram(MSG_ERROR_WRITING_COMBINED_FILE, REASON_WRITE_FAILED, DD_SORTOUT,
                        OPERATION_WRITE, cause);
            } finally {
                closeSortInput(reader);
            }

            LOG.info("{} read {} records in TRAN-ID order: {} from TRANSACT.BKUP(0), {} from SYSTRAN(0)",
                    DD_SORTIN, Integer.valueOf(recordCount), Long.valueOf(reader.getBackupRecordsRead()),
                    Long.valueOf(reader.getSystranRecordsRead()));
            logResolvedInputGenerations(reader);

            // SORTOUT at :L33-L37, one generation, then the (+1) handoff for :L44.
            final String objectKey = writeCombinedGeneration(stepExecution, work, recordCount);
            publishCombinedGeneration(stepExecution, objectKey, recordCount);
        } finally {
            deleteWorkFile(work);
        }
    }

    /**
     * Creates the bounded staging file that stands in for {@code SORTOUT}'s allocated space.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl:L33-L37} allocates {@code SORTOUT} on {@code UNIT=SYSDA} with
     * {@code SPACE=(CYL,(1,1),RLSE)} - the sort's output has always been <em>disk</em>, never storage. An
     * earlier revision of this step accumulated the whole generation in a {@code StringBuilder}, converted it
     * to a {@code String} and then to a {@code byte[]}, which held three full copies of the output at once and
     * needed an invented per-run record cap to stop a large input exhausting the heap. Staging to a temporary
     * file restores the source's own storage model, makes the resident set independent of the record count, and
     * removes the reason the cap existed.
     *
     * <p>The file lives wherever {@code java.io.tmpdir} points, is created with the platform's default
     * owner-only permissions, and is deleted in a {@code finally} block whether the step succeeds or fails.
     * It holds transaction images, so it is deleted rather than left for inspection.
     *
     * @return the empty work file, never {@code null}
     * @throws FatalProcessingException if the file cannot be created
     */
    private Path createWorkFile() {
        try {
            return Files.createTempFile(WORK_FILE_PREFIX, WORK_FILE_SUFFIX);
        } catch (final IOException cause) {
            LOG.error("{} could not allocate the SORTOUT staging file of app/jcl/COMBTRAN.jcl:L33-L37",
                    DD_SORTOUT);
            throw abendProgram(MSG_ERROR_WRITING_COMBINED_FILE, REASON_WRITE_FAILED, DD_SORTOUT,
                    OPERATION_WRITE, cause);
        }
    }

    /**
     * Deletes the staging file, reporting a failure to delete without masking the step's own outcome.
     *
     * <p>A failed delete is logged rather than thrown: the step's result is already decided by the time this
     * runs, and replacing a real failure - or a real success - with a housekeeping error would lose the
     * outcome that matters. The file is named in the warning so an operator can remove it.
     *
     * @param work the staging file, never {@code null}
     */
    private static void deleteWorkFile(final Path work) {
        try {
            Files.deleteIfExists(work);
        } catch (final IOException cause) {
            LOG.warn("Could not delete the SORTOUT staging file {}; remove it manually", work, cause);
        }
    }

    /**
     * {@code //STEP10 EXEC PGM=IDCAMS} - {@code app/jcl/COMBTRAN.jcl:L41} through {@code :L48}.
     *
     * <p>Translates {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} ({@code :L48}), reading
     * {@code TRANSACT.COMBINED(+1)} ({@code :L43-L44}) into the relation behind
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} ({@code :L45-L46}).
     *
     * <p><b>The key comes from the execution context, not from a fresh listing.</b> That is the whole point of
     * {@code :L44} naming {@code (+1)} rather than {@code (0)}: the object to load is the one this job's sort
     * step created, and re-resolving the latest generation here would let a concurrent writer's object be
     * loaded instead.
     *
     * <p>Records are loaded in batches of {@link #chunkSize} through
     * {@link JdbcTemplate#batchUpdate(String, List)}. On a store failure the offending record is identified
     * from the driver's own update counts and handed to
     * {@link TransactionCombineProcessor#translateLoadFailure(Transaction, DataAccessException)}, which raises
     * {@link com.cardemo.exception.DuplicateRecordException} for a duplicate key with the cause preserved -
     * <b>never an upsert and never a swallow</b>.
     *
     * @param stepExecution the running step, carrying the job execution the key was published on
     * @param processor the owner of the load-failure translation
     * @throws FatalProcessingException if the published key is absent, the download fails, or the object's
     *     size is not a whole number of {@value #COMBINED_RECORD_LENGTH}-byte records
     * @throws CardDemoException if the load is rejected; the typed subtype is the processor's
     */
    private void executeStep10(
            final StepExecution stepExecution,
            final TransactionCombineProcessor processor) {

        // :L43-L44 - the exact generation STEP05R created, never a re-resolved "latest".
        final String objectKey = requirePublishedGenerationKey(stepExecution);

        // REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM) - app/jcl/COMBTRAN.jcl:L48 - streamed rather than
        // materialised. An earlier revision downloaded the whole generation with readAllBytes(), copied it
        // into a String, and decoded it into one List<Transaction> before loading anything, which held four
        // full copies of the generation at peak. IDCAMS REPRO is a record-at-a-time copy with a buffer, so a
        // bounded batch is both the faithful shape and the one whose cost does not grow with the input.
        int decoded = 0;
        int loaded = 0;
        final List<Transaction> batch = new ArrayList<>(chunkSize);
        try (InputStream generation = openCombinedGeneration(objectKey)) {
            final byte[] recordBytes = new byte[COMBINED_RECORD_LENGTH];
            while (true) {
                final int filled = generation.readNBytes(recordBytes, 0, COMBINED_RECORD_LENGTH);
                if (filled == 0) {
                    break;
                }
                if (filled != COMBINED_RECORD_LENGTH) {
                    LOG.error("{} ends {} bytes into a record; app/jcl/COMBTRAN.jcl:L35 DCB=(*.SORTIN) fixes"
                            + " the geometry at {} bytes, so the object was not written by this job or was"
                            + " written with a different one", objectKey, Integer.valueOf(filled),
                            Integer.valueOf(COMBINED_RECORD_LENGTH));
                    throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_BAD_RECORD_LENGTH,
                            DD_TRANSACT, OPERATION_READ, null);
                }
                batch.add(decodeRecord(new String(recordBytes, FIXED_WIDTH_CHARSET)));
                decoded++;
                if (batch.size() == chunkSize) {
                    loaded += loadBatch(batch, processor);
                    batch.clear();
                }
            }
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException cause) {
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_READ_FAILED, DD_TRANSACT,
                    OPERATION_READ, cause);
        }
        if (!batch.isEmpty()) {
            loaded += loadBatch(batch, processor);
        }

        requireExpectedRecordCount(stepExecution, decoded, objectKey);

        if (decoded == 0) {
            // Explicit, not silent: an empty generation is a real outcome when both (0) inputs were empty,
            // and STEP05R has already logged the per-source counts that say which one was.
            LOG.warn("{} loaded 0 records from {}; app/jcl/COMBTRAN.jcl:L48 REPRO copied an empty generation"
                    + " because both SORTIN sources were empty", DD_TRANVSAM, objectKey);
            return;
        }

        // FINDING H-01, severity HIGH. metricsConfig.countRecordsProcessed(loaded) stood here and has been
        // removed with its collaborator. carddemo.batch.records.processed mirrors ADD 1 TO WS-TRANSACTION-COUNT
        // at app/cbl/CBTRN02C.cbl:L206, so its population is the daily-transaction records the POSTTRAN job
        // read - and nothing else. The rows this step loads are rows an EARLIER run already counted: they
        // entered the transaction relation once through the posting job and are being re-read here out of a
        // generation object, so counting them again made an untagged series the sum of two populations.
        // Untagged is what makes that irreversible: there is no job dimension to group away afterwards, so no
        // PromQL expression could recover either figure.
        //
        // This step's volume is not lost. It is published to the job execution context as
        // COMBINED_LOADED_RECORD_COUNT_CONTEXT_ENTRY - and COMBINED_RECORD_COUNT_CONTEXT_ENTRY, which
        // requireExpectedRecordCount has already proved equal to the decoded count - and it is available
        // continuously as the Spring Batch step metrics spring_batch_step_seconds_count and
        // spring_batch_item_write_seconds_count, both tagged by name. Those carry a job dimension by
        // construction, which is exactly what this application counter cannot.
        publishLoadedRecordCount(stepExecution, loaded);
        LOG.info("{} loaded {} records from {} into the transaction relation", DD_TRANVSAM,
                Integer.valueOf(loaded), objectKey);
    }

    /**
     * Opens the concatenated {@code SORTIN} of {@code app/jcl/COMBTRAN.jcl:L23-L26}.
     *
     * <p>The step execution context is handed over so a restart resumes the reader's cursor rather than
     * re-emitting records already written. Every failure the reader raises is already a typed
     * {@code com.cardemo.exception} subtype and is rethrown untouched; anything else is an abend with the
     * cause preserved.
     *
     * @param reader the concatenated input
     * @param stepContext the step execution context, for restart state
     * @throws FatalProcessingException if the open fails for an untyped reason
     * @throws CardDemoException if the reader raises its own typed failure, including an absent
     *     {@code TRANSACT.BKUP} current generation
     */
    private void openSortInput(final CombinedTransactionReader reader, final ExecutionContext stepContext) {
        try {
            reader.open(stepContext);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_SORT_FAILED, DD_SORTIN,
                    OPERATION_READ, cause);
        }
    }

    /**
     * Reads the next record of the ordered concatenated input.
     *
     * <p>A {@code null} return is <b>end of file and is loop termination, never an exception</b> - the file
     * status {@code '10'} arm of the universal guard idiom. A returned record is {@code '00'}. Anything the
     * reader throws is the abend arm.
     *
     * @param reader the concatenated input
     * @return the next record in {@code TRAN-ID} order, or {@code null} at end of file
     * @throws FatalProcessingException if the read fails for an untyped reason
     * @throws CardDemoException if the reader raises its own typed failure, including a descending source
     */
    private Transaction readNextSortInput(final CombinedTransactionReader reader) {
        try {
            return reader.read();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_SORT_FAILED, DD_SORTIN,
                    OPERATION_READ, cause);
        }
    }

    /**
     * Closes the concatenated {@code SORTIN}.
     *
     * <p>Called from a {@code finally}, so it runs whether the read loop completed or abended. A close failure
     * is logged and <b>not</b> rethrown: doing so from a {@code finally} would replace the original failure
     * with a secondary one and lose the diagnosis that matters. This is not swallowing - the throwable is
     * logged in full, with its cause, before being dropped in favour of the primary failure.
     *
     * @param reader the concatenated input
     */
    private void closeSortInput(final CombinedTransactionReader reader) {
        try {
            reader.close();
        } catch (final RuntimeException closeFailure) {
            LOG.error("{} failed to close; {} - reporting it here rather than rethrowing from a finally so"
                    + " that a primary failure is not replaced by this one", DD_SORTIN,
                    fileStatusMapper.displayIoStatus(STATUS_PHYSICAL_IO_ERROR), closeFailure);
        }
    }

    /**
     * Logs which {@code (0)} generations were actually consumed, and names an absent one explicitly.
     *
     * <p>Both {@code SORTIN} references are {@code (0)} ({@code app/jcl/COMBTRAN.jcl:L24}, {@code :L26}), so
     * <b>only the most recent generation of each is read and only the latest interest run is merged</b>. An
     * absent {@code SYSTRAN} current generation is the state before the first interest run and is not an
     * error; it is logged so that a run which combined nothing new is diagnosable rather than mysterious.
     *
     * @param reader the concatenated input, already opened
     */
    private void logResolvedInputGenerations(final CombinedTransactionReader reader) {
        final String backupKey = reader.getResolvedBackupObjectKey();
        final String systranKey = reader.getResolvedSystranObjectKey();
        if (systranKey == null) {
            LOG.warn("{} found no current SYSTRAN generation for app/jcl/COMBTRAN.jcl:L26 SYSTRAN(0), so no"
                    + " interest transactions were merged; this is the state before the first INTCALC run,"
                    + " not a failure", DD_SORTIN);
        }
        LOG.debug("{} resolved TRANSACT.BKUP(0)={} and SYSTRAN(0)={}", DD_SORTIN, backupKey, systranKey);
    }

    /**
     * Renders one record at exactly {@value #COMBINED_RECORD_LENGTH} bytes.
     *
     * <p>Delegates to {@link TransactionWriter#composeFixedWidthImage(Transaction)}, the single definition of
     * the record image, then re-asserts the length here. The second check is not redundant: the geometry this
     * step must honour is fixed by {@code DCB=(*.SORTIN)} at {@code app/jcl/COMBTRAN.jcl:L35}, so a future
     * change to the encoder that altered the width must fail <b>this</b> step rather than silently produce an
     * object the load step would then refuse to align.
     *
     * @param transaction the validated record
     * @return the record image, exactly {@value #COMBINED_RECORD_LENGTH} characters
     * @throws FatalProcessingException if the composed image is not exactly
     *     {@value #COMBINED_RECORD_LENGTH} characters
     * @throws CardDemoException if the encoder rejects the record; the typed subtype is its own
     */
    private String requireCombinedRecordImage(final Transaction transaction) {
        final String image = transactionWriter.composeFixedWidthImage(transaction);
        if (image.length() != COMBINED_RECORD_LENGTH) {
            throw abendProgram(MSG_ERROR_WRITING_COMBINED_FILE, REASON_BAD_RECORD_LENGTH, DD_SORTOUT,
                    OPERATION_WRITE, null);
        }
        return image;
    }

    /**
     * {@code //SORTOUT  DD DISP=(NEW,CATLG,DELETE) ... DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} -
     * {@code app/jcl/COMBTRAN.jcl:L33-L37}.
     *
     * <p>Streams the staged work file as <b>one</b> object under a new, monotonically increasing key, which is
     * what {@code (+1)} means over a versioned bucket. The staged length is asserted to be a whole number of
     * {@value #COMBINED_RECORD_LENGTH}-byte records before the request is made, so a geometry defect is caught
     * on this side of the boundary rather than by the load step. The content length is declared from that same
     * measured length, so the request never has to buffer the payload to discover its size and the whole
     * generation is never resident in the heap.
     *
     * @param stepExecution the running step, for the job instance the key is scoped to
     * @param work the staged work file holding the sorted, fixed-width record images
     * @param recordCount the number of records the work file holds
     * @return the concrete object key created, never {@code null}
     * @throws FatalProcessingException if the payload geometry is wrong or the upload fails
     */
    private String writeCombinedGeneration(
            final StepExecution stepExecution, final Path work, final int recordCount) {

        final long stagedBytes = workFileLength(work);
        final long expectedBytes = (long) recordCount * COMBINED_RECORD_LENGTH;
        if (stagedBytes != expectedBytes) {
            LOG.error("{} composed {} bytes for {} records but app/jcl/COMBTRAN.jcl:L35 DCB=(*.SORTIN) fixes"
                    + " the record at {} bytes, so {} were expected", DD_SORTOUT,
                    Long.valueOf(stagedBytes), Integer.valueOf(recordCount),
                    Integer.valueOf(COMBINED_RECORD_LENGTH), Long.valueOf(expectedBytes));
            throw abendProgram(MSG_ERROR_WRITING_COMBINED_FILE, REASON_BAD_RECORD_LENGTH, DD_SORTOUT,
                    OPERATION_WRITE, null);
        }

        final long jobInstanceId = stepExecution.getJobExecution().getJobInstance().getInstanceId();
        final String objectKey = composeCombinedObjectKey(jobInstanceId, stepExecution.getId());

        String ioStatus = STATUS_SUCCESS;
        RuntimeException failure = null;
        // The staged file is streamed to the object store with its length declared up front, so the client
        // sends it without buffering it: nothing here holds more than the stream buffer, whatever the
        // generation's size.
        try (InputStream staged = new BufferedInputStream(Files.newInputStream(work), WORK_BUFFER_BYTES)) {
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(OBJECT_CONTENT_TYPE)
                    .contentLength(Long.valueOf(stagedBytes))
                    .build();
            objectStorage.upload(outputBucket, objectKey, staged, metadata);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException | RuntimeException cause) {
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            failure = cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(cause.getMessage(), cause);
        }
        guardObjectOperation(ioStatus, DD_SORTOUT, OPERATION_WRITE, MSG_ERROR_WRITING_COMBINED_FILE,
                REASON_WRITE_FAILED, failure);

        LOG.info("{} wrote {} records ({} bytes) to {}", DD_SORTOUT, Integer.valueOf(recordCount),
                Long.valueOf(stagedBytes), objectKey);
        return objectKey;
    }

    /**
     * Measures the staged {@code SORTOUT} file.
     *
     * @param work the staging file
     * @return its length in bytes
     * @throws FatalProcessingException if the length cannot be read
     */
    private long workFileLength(final Path work) {
        try {
            return Files.size(work);
        } catch (final IOException cause) {
            throw abendProgram(MSG_ERROR_WRITING_COMBINED_FILE, REASON_WRITE_FAILED, DD_SORTOUT,
                    OPERATION_WRITE, cause);
        }
    }

    /**
     * Publishes the {@code (+1)} generation for {@code app/jcl/COMBTRAN.jcl:L44} to read back.
     *
     * <p>Both the key and the record count go into the <b>job</b> execution context rather than the step's, so
     * the following step can read them; a step execution context is not visible across steps.
     *
     * @param stepExecution the running step
     * @param objectKey the concrete key created
     * @param recordCount the records the generation holds
     */
    private void publishCombinedGeneration(
            final StepExecution stepExecution, final String objectKey, final int recordCount) {

        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        jobContext.putString(COMBINED_OBJECT_KEY_CONTEXT_ENTRY, objectKey);
        jobContext.putInt(COMBINED_RECORD_COUNT_CONTEXT_ENTRY, recordCount);
        LOG.debug("Published TRANSACT.COMBINED(+1)={} with {} records for app/jcl/COMBTRAN.jcl:L44",
                objectKey, Integer.valueOf(recordCount));
    }

    /**
     * Publishes how many rows {@code STEP10}'s {@code REPRO} loaded, into this job's own execution context.
     *
     * <p><strong>This is deliberately not a Micrometer counter.</strong>
     * {@code com.cardemo.observability.MetricsConfig#METRIC_RECORDS_PROCESSED} is defined as the
     * {@code DALYTRAN} population of {@code app/cbl/CBTRN02C.cbl:L206} - one increment per record that job
     * read - and the counter is untagged, so no query can afterwards separate a second source's contribution
     * from it. An earlier revision advanced it here with the loaded row count, which double-counted every
     * transaction the posting job had already counted and then counted the interest rows a second time on any
     * subsequent combine, inflating an untagged series irrecoverably. The volume this step moves is real
     * information, so it is published where it belongs: the job execution context, keyed and readable by an
     * operator, a test and the pipeline orchestrator alike.
     *
     * @param stepExecution the running step
     * @param loaded the rows the bulk load reported
     */
    private static void publishLoadedRecordCount(final StepExecution stepExecution, final int loaded) {
        stepExecution.getJobExecution().getExecutionContext()
                .putInt(COMBINED_LOADED_RECORD_COUNT_CONTEXT_ENTRY, loaded);
    }

    /**
     * {@code //TRANSACT DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} -
     * {@code app/jcl/COMBTRAN.jcl:L43-L44}.
     *
     * <p><b>Reads the key the sort step published and nothing else.</b> The reference at {@code :L44} is
     * {@code (+1)}, not {@code (0)}: it names the generation created earlier in this same job. Listing the
     * bucket for the greatest prefix instead would be a different object whenever another writer had
     * intervened, so the latest generation is deliberately never re-resolved here.
     *
     * @param stepExecution the running step
     * @return the concrete key {@code STEP05R} created, never {@code null} or blank
     * @throws FatalProcessingException if the entry is absent or blank, which means {@code STEP05R} did not
     *     run or did not complete
     */
    private String requirePublishedGenerationKey(final StepExecution stepExecution) {
        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        final String objectKey = jobContext.getString(COMBINED_OBJECT_KEY_CONTEXT_ENTRY, null);
        if (objectKey == null || objectKey.isBlank()) {
            LOG.error("{} found no published TRANSACT.COMBINED(+1) key under {}; app/jcl/COMBTRAN.jcl:L44"
                    + " re-references the generation STEP05R creates, so the load cannot proceed and must"
                    + " not fall back to resolving the latest generation", DD_TRANSACT,
                    COMBINED_OBJECT_KEY_CONTEXT_ENTRY);
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_NO_GENERATION_KEY, DD_TRANSACT,
                    OPERATION_READ, null);
        }
        return objectKey;
    }

    /**
     * Downloads the combined generation as fixed-width text.
     *
     * <p>Read through {@link #FIXED_WIDTH_CHARSET} so one byte is one character, which is what lets the record
     * boundary be a simple index. <b>No line-oriented reader is used</b>: {@code RECFM=FB} records carry no
     * delimiter, and trailing spaces are data rather than something to strip.
     *
     * @param objectKey the concrete key published by {@code STEP05R}
     * @return the whole generation as text, never {@code null}
     * @throws FatalProcessingException if the object is absent or the download fails
     */
    private InputStream openCombinedGeneration(final String objectKey) {
        String ioStatus = STATUS_SUCCESS;
        RuntimeException failure = null;
        InputStream stream = null;
        try {
            final S3Resource resource = objectStorage.download(outputBucket, objectKey);
            stream = new BufferedInputStream(resource.getInputStream(), WORK_BUFFER_BYTES);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException | RuntimeException cause) {
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            failure = cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(cause.getMessage(), cause);
        }
        guardObjectOperation(ioStatus, DD_TRANSACT, OPERATION_READ, MSG_ERROR_READING_COMBINED_FILE,
                REASON_READ_FAILED, failure);
        return stream;
    }

    /**
     * Decodes one {@value #COMBINED_RECORD_LENGTH}-byte record using the offset map of
     * {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>This is the {@code REPRO INFILE} side of {@code app/jcl/COMBTRAN.jcl:L48} and the exact inverse of
     * {@link TransactionWriter#composeFixedWidthImage(Transaction)}. It exists here because no shared decoder
     * does: the encoder is public and is reused, and the inverse operation is owned by nothing.
     *
     * <p><b>The two timestamps are lifted as twenty-six-character text and handed on untouched.</b> Nothing
     * parses, reformats, normalises or timezone-converts them, because the batch producer's
     * hundredths-plus-four-zeros rendering is a byte-level contract that any round trip through a
     * date type would quietly rewrite. Character fields keep their space padding, which is data on a
     * {@code CHAR(n)} column rather than noise to trim.
     *
     * @param record exactly {@value #COMBINED_RECORD_LENGTH} characters
     * @return the decoded record, never {@code null}
     * @throws FatalProcessingException if a numeric field is not decodable
     */
    private Transaction decodeRecord(final String record) {
        return new Transaction(
                record.substring(TRAN_ID_BEGIN, TRAN_TYPE_CD_BEGIN),
                record.substring(TRAN_TYPE_CD_BEGIN, TRAN_CAT_CD_BEGIN),
                Integer.valueOf(decodeUnsignedInt(
                        record.substring(TRAN_CAT_CD_BEGIN, TRAN_SOURCE_BEGIN), "TRAN-CAT-CD")),
                record.substring(TRAN_SOURCE_BEGIN, TRAN_DESC_BEGIN),
                record.substring(TRAN_DESC_BEGIN, TRAN_AMT_BEGIN),
                decodeSignedAmount(record.substring(TRAN_AMT_BEGIN, TRAN_MERCHANT_ID_BEGIN)),
                Long.valueOf(decodeUnsignedLong(
                        record.substring(TRAN_MERCHANT_ID_BEGIN, TRAN_MERCHANT_NAME_BEGIN),
                        "TRAN-MERCHANT-ID")),
                record.substring(TRAN_MERCHANT_NAME_BEGIN, TRAN_MERCHANT_CITY_BEGIN),
                record.substring(TRAN_MERCHANT_CITY_BEGIN, TRAN_MERCHANT_ZIP_BEGIN),
                record.substring(TRAN_MERCHANT_ZIP_BEGIN, TRAN_CARD_NUM_BEGIN),
                record.substring(TRAN_CARD_NUM_BEGIN, TRAN_ORIG_TS_BEGIN),
                record.substring(TRAN_ORIG_TS_BEGIN, TRAN_PROC_TS_BEGIN),
                record.substring(TRAN_PROC_TS_BEGIN, TRAN_FILLER_BEGIN));
    }

    /**
     * Decodes {@code TRAN-AMT PIC S9(09)V99} from its eleven-byte zoned-decimal representation.
     *
     * <p>The final byte is a <b>sign overpunch</b>, not a digit: <code>'&#123;'</code> is {@code +0}, {@code 'A'} to
     * {@code 'I'} are {@code +1} to {@code +9}, <code>'&#125;'</code> is {@code -0} and {@code 'J'} to {@code 'R'} are
     * {@code -1} to {@code -9}. Decoding is position-aware - only this field's final byte is read as an
     * overpunch - because the same letters occur legitimately inside the description, merchant name and city.
     *
     * <p>Arithmetic is {@link BigDecimal} throughout at scale {@value #TRAN_AMT_SCALE}, matching the
     * {@code NUMERIC(11,2)} column. There is <b>no {@code float} and no {@code double}</b> anywhere on this
     * path. {@link RoundingMode#HALF_EVEN} is stated on the {@code setScale} even though
     * {@link BigDecimal#movePointLeft(int)} already yields exactly that scale, so the field's rounding
     * contract is declared at the one place the scale is fixed rather than left to be inferred.
     * <b>No absolute value is taken and no sign is normalised</b>: a negative amount is a real amount, and
     * losing its sign here would corrupt the cycle arithmetic that consumes it.
     *
     * @param field exactly eleven characters
     * @return the signed amount at scale {@value #TRAN_AMT_SCALE}, never {@code null}
     * @throws FatalProcessingException if the leading digits are not digits or the overpunch is unrecognised
     */
    private BigDecimal decodeSignedAmount(final String field) {
        final String digits = field.substring(0, field.length() - 1);
        final char overpunch = field.charAt(field.length() - 1);

        int finalDigit = POSITIVE_OVERPUNCH.indexOf(overpunch);
        boolean negative = false;
        if (finalDigit < 0) {
            finalDigit = NEGATIVE_OVERPUNCH.indexOf(overpunch);
            negative = true;
        }
        if (finalDigit < 0) {
            LOG.error("TRAN-AMT at bytes {}-{} of the record ends with a byte that is neither a positive nor"
                    + " a negative zoned-decimal overpunch, so its sign and final digit cannot be recovered",
                    Integer.valueOf(TRAN_AMT_BEGIN + 1), Integer.valueOf(TRAN_MERCHANT_ID_BEGIN));
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_BAD_NUMERIC, DD_TRANSACT,
                    OPERATION_READ, null);
        }

        final BigDecimal unscaled =
                new BigDecimal(requireDigits(digits, "TRAN-AMT") + finalDigit);
        final BigDecimal amount =
                unscaled.movePointLeft(TRAN_AMT_SCALE).setScale(TRAN_AMT_SCALE, RoundingMode.HALF_EVEN);
        return negative ? amount.negate() : amount;
    }

    /**
     * Decodes an unsigned zoned field such as {@code TRAN-CAT-CD PIC 9(04)} into an {@code int}.
     *
     * @param field the fixed-width digits
     * @param cobolField the field name, for the diagnostic
     * @return the decoded value
     * @throws FatalProcessingException if the field is not all digits
     */
    private int decodeUnsignedInt(final String field, final String cobolField) {
        return Integer.parseInt(requireDigits(field, cobolField));
    }

    /**
     * Decodes an unsigned zoned field such as {@code TRAN-MERCHANT-ID PIC 9(09)} into a {@code long}.
     *
     * @param field the fixed-width digits
     * @param cobolField the field name, for the diagnostic
     * @return the decoded value
     * @throws FatalProcessingException if the field is not all digits
     */
    private long decodeUnsignedLong(final String field, final String cobolField) {
        return Long.parseLong(requireDigits(field, cobolField));
    }

    /**
     * Asserts that a fixed-width numeric field holds only ASCII digits.
     *
     * <p>{@link Character#isDigit(char)} is deliberately not used: it accepts the decimal digits of every
     * Unicode script, and {@link Integer#parseInt(String)} would then accept a value this record layout cannot
     * hold. The comparison is against {@code '0'} and {@code '9'} directly, so it depends on no locale.
     *
     * @param field the field text
     * @param cobolField the field name, for the diagnostic
     * @return {@code field} unchanged
     * @throws FatalProcessingException if any character is not an ASCII digit
     */
    private String requireDigits(final String field, final String cobolField) {
        for (int index = 0; index < field.length(); index++) {
            final char digit = field.charAt(index);
            if (digit < '0' || digit > '9') {
                LOG.error("{} of the combined record is not all digits at offset {}, so it cannot be loaded"
                        + " into its NUMERIC column", cobolField, Integer.valueOf(index + 1));
                throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_BAD_NUMERIC, DD_TRANSACT,
                        OPERATION_READ, null);
            }
        }
        return field;
    }

    /**
     * Cross-checks the decoded record count against the count {@code STEP05R} published.
     *
     * <p>Cheap, and it closes the one gap the key handoff alone leaves: if the published key were ever
     * overwritten between the steps, the object would still align on the record boundary while holding
     * different content. A count mismatch names that condition instead of loading it.
     *
     * @param stepExecution the running step
     * @param decodedCount records decoded from the downloaded object
     * @param objectKey the key loaded, for the diagnostic
     * @throws FatalProcessingException if the counts disagree
     */
    private void requireExpectedRecordCount(
            final StepExecution stepExecution, final int decodedCount, final String objectKey) {

        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        if (!jobContext.containsKey(COMBINED_RECORD_COUNT_CONTEXT_ENTRY)) {
            return;
        }
        final int publishedCount = jobContext.getInt(COMBINED_RECORD_COUNT_CONTEXT_ENTRY);
        if (publishedCount != decodedCount) {
            LOG.error("{} holds {} records but STEP05R published {}; the object at the key of"
                    + " app/jcl/COMBTRAN.jcl:L44 is not the generation this job wrote", objectKey,
                    Integer.valueOf(decodedCount), Integer.valueOf(publishedCount));
            throw abendProgram(MSG_ERROR_READING_COMBINED_FILE, REASON_BAD_RECORD_LENGTH, DD_TRANSACT,
                    OPERATION_READ, null);
        }
    }

    /**
     * Loads one batch through {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} -
     * {@code app/jcl/COMBTRAN.jcl:L48}.
     *
     * <p>On a store failure the offending record is identified from the driver's own update counts - see
     * {@link #identifyFailedRecord} - and handed to
     * {@link TransactionCombineProcessor#translateLoadFailure(Transaction, DataAccessException)}, which raises
     * the typed subtype and preserves the cause. <b>Nothing here retries, upserts, merges, regenerates an
     * identifier or continues past the failure</b>, because {@code REPRO} into a keyed cluster does none of
     * those: it fails, and the step must fail with it at return code 8.
     *
     * <p>{@code translateLoadFailure} always throws. The rethrow after it is unreachable in practice and is
     * there so the compiler can see that this method cannot fall through to a bogus count; it also means that
     * a future revision which made the translator return would still not silently succeed.
     *
     * @param batch the records to load, never empty
     * @param processor the owner of the load-failure translation
     * @return the number of rows loaded
     * @throws com.cardemo.exception.DuplicateRecordException if a {@code TRAN-ID} collides
     * @throws com.cardemo.exception.DataIntegrityException if another constraint rejects a record
     * @throws FatalProcessingException for any other store failure
     */
    private int loadBatch(final List<Transaction> batch, final TransactionCombineProcessor processor) {
        final List<Object[]> arguments = new ArrayList<>(batch.size());
        for (final Transaction record : batch) {
            arguments.add(toLoadParameters(record));
        }

        try {
            final int[] updateCounts = jdbcTemplate.batchUpdate(LOAD_INSERT_SQL, arguments);
            // The template's contract is one count per statement, so a null array is not a documented
            // outcome - but Clause B asks for null to be handled rather than assumed away, and the
            // alternative to handling it is a NullPointerException that would be reported as a load failure
            // with no diagnosis. Every argument was submitted, so the batch size is the count to report.
            return updateCounts == null ? batch.size() : updateCounts.length;
        } catch (final DataAccessException cause) {
            final Transaction offending = identifyFailedRecord(batch, cause);
            LOG.error("{} rejected the bulk load of app/jcl/COMBTRAN.jcl:L48; translating the store failure",
                    DD_TRANVSAM);
            processor.translateLoadFailure(offending, cause);
            throw abendProgram(MSG_ERROR_LOADING_TRANSACTION_FILE, REASON_READ_FAILED, DD_TRANVSAM,
                    OPERATION_LOAD, cause);
        }
    }

    /**
     * Identifies which record of a batch the store rejected.
     *
     * <p>A JDBC driver reports one update count per statement it managed to execute before the failure, so the
     * number of counts is the zero-based index of the statement that failed. That is the precise answer and it
     * costs no extra query. When the driver reports no counts - permitted by the specification - the first
     * record of the batch is named instead, and the log says so rather than implying more precision than is
     * available.
     *
     * @param batch the batch that failed, never empty
     * @param cause the failure reported by the load
     * @return the record to name in the diagnostic, never {@code null}
     */
    private Transaction identifyFailedRecord(final List<Transaction> batch, final DataAccessException cause) {
        for (Throwable candidate = cause; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof BatchUpdateException batchFailure) {
                final int[] counts = batchFailure.getUpdateCounts();
                if (counts != null && counts.length < batch.size()) {
                    return describeIdentifiedRecord(batch.get(counts.length), counts.length);
                }
                break;
            }
        }
        LOG.debug("The store failure carried no usable update counts, so the batch's first record is named;"
                + " the retained cause holds the driver's own detail");
        return describeIdentifiedRecord(batch.get(0), 0);
    }

    /**
     * Emits the one diagnostic that names the rejected record, then returns it.
     *
     * <p>At {@code DEBUG} only, and with both values rendered safely: the identifier through
     * {@link FileStatus#escapeForDiagnostics(String)}, because sixteen bytes lifted from an object-storage
     * record could carry a {@code CR} or {@code LF} and forge a log line, and the card number through
     * {@link #maskCardNumber(String)}, because the field at bytes 263-278 is a primary account number.
     * <b>The full field is never emitted at any level.</b>
     *
     * @param record the record the store rejected
     * @param batchIndex its zero-based position within the failed batch
     * @return {@code record} unchanged
     */
    private static Transaction describeIdentifiedRecord(final Transaction record, final int batchIndex) {
        LOG.debug("The store rejected the record at batch index {}: TRAN-ID {}, card {}",
                Integer.valueOf(batchIndex),
                FileStatus.escapeForDiagnostics(record.getTransactionId()),
                maskCardNumber(record.getCardNumber()));
        return record;
    }

    /**
     * Binds one record to the fourteen placeholders of {@link #LOAD_INSERT_SQL}.
     *
     * <p>Order is the column order of the statement, which is the field order of
     * {@code app/cpy/CVTRA05Y.cpy}. The two timestamps are bound as the {@code String}s they arrived as, so
     * the {@code CHAR(26)} columns receive the record's own bytes. {@code version} is bound explicitly because
     * the column is {@code NOT NULL} and this insert bypasses the provider that would otherwise seed it.
     *
     * @param record the decoded record
     * @return exactly {@value #LOAD_PARAMETER_COUNT} bind values in statement order
     */
    private Object[] toLoadParameters(final Transaction record) {
        final Object[] parameters = new Object[LOAD_PARAMETER_COUNT];
        int index = 0;
        parameters[index++] = record.getTransactionId();
        parameters[index++] = record.getTypeCode();
        parameters[index++] = record.getCategoryCode();
        parameters[index++] = record.getTransactionSource();
        parameters[index++] = record.getDescription();
        parameters[index++] = record.getAmount();
        parameters[index++] = record.getMerchantId();
        parameters[index++] = record.getMerchantName();
        parameters[index++] = record.getMerchantCity();
        parameters[index++] = record.getMerchantZip();
        parameters[index++] = record.getCardNumber();
        parameters[index++] = record.getOrigTs();
        parameters[index++] = record.getProcTs();
        parameters[index] = Long.valueOf(INITIAL_VERSION);
        return parameters;
    }

    /**
     * Composes the key of one {@code TRANSACT.COMBINED} generation.
     *
     * <p>{@code (+1)} at {@code app/jcl/COMBTRAN.jcl:L37} becomes a new object under a monotonically
     * increasing prefix. Both numbers are zero-padded to {@value #KEY_NUMBER_WIDTH} digits so that
     * lexicographic order and numeric order agree, which is what makes a later {@code (0)} resolution - "the
     * greatest existing prefix" - pick the newest generation rather than the one whose decimal spelling sorts
     * highest. {@link Locale#ROOT} is explicit so the digits cannot be localised.
     *
     * @param jobInstanceId the job instance, scoping the generation to one logical run
     * @param stepExecutionId the step execution, making the key unique across restarts of that instance
     * @return the object key, never {@code null}
     */
    private String composeCombinedObjectKey(final long jobInstanceId, final long stepExecutionId) {
        return String.format(Locale.ROOT, COMBINED_OBJECT_KEY_TEMPLATE, combinedPrefix,
                Long.valueOf(jobInstanceId), Long.valueOf(stepExecutionId));
    }

    /**
     * The universal I/O guard, applied to every object-storage operation on both steps.
     *
     * <p>The corpus's shape is one status field tested against {@code '00'}, and this reproduces it through the
     * shared {@link FileStatusMapper} so the status-to-exception map has one definition:
     * {@code '00'} continues, {@code '22'} is a duplicate, {@code '23'} is not found, {@code '35'} is
     * unavailable, the {@code '9x'} family is an access failure carrying the four-character expanded status,
     * and anything else is fatal. <b>None of the three tree-wide leniency carve-outs applies to this job</b>,
     * so no status other than {@code '00'} is tolerated here.
     *
     * @param ioStatus the two-character status the operation produced
     * @param logicalFile the DD name, for identity
     * @param operation the attempted operation
     * @param message the legacy diagnostic to emit before failing
     * @param reason the abend reason
     * @param failure the underlying throwable, or {@code null}; declared as {@link Throwable} because a
     *     streamed write fails with a checked {@link IOException} and wrapping it here would add a synthetic
     *     frame to a cause chain the mapper and the abend both already preserve
     * @throws CardDemoException if {@code ioStatus} is not exactly {@code '00'}; the subtype is the mapper's
     */
    private void guardObjectOperation(
            final String ioStatus,
            final String logicalFile,
            final String operation,
            final String message,
            final String reason,
            final Throwable failure) {

        if (STATUS_SUCCESS.equals(ioStatus)) {
            return;
        }
        LOG.error("{} - {}", message, fileStatusMapper.displayIoStatus(ioStatus), failure);
        fileStatusMapper.requireSuccess(ioStatus, logicalFile, operation, failure);

        // requireSuccess throws for every status other than '00', and the early return above has already
        // excluded '00'. This throw is not residue: it is the guard against that contract changing, because
        // without it a revision of the mapper that returned would let a failed operation continue as a
        // success. It is reached only if that happens, and then it abends rather than continuing.
        throw abendProgram(message, reason, logicalFile, operation, failure);
    }

    /**
     * Builds the abend, reproducing the corpus's termination contract for a job that has no program of its own.
     *
     * <p>Carries abend code {@value FatalProcessingException#BATCH_ABEND_CODE} - the batch value, <b>not</b>
     * the CICS online value - and drives process return code
     * {@value FatalProcessingException#BATCH_RETURN_CODE}. The culprit is the JCL member name, because there is
     * no program name to report. The exception is <b>returned rather than thrown</b> so that every call site
     * reads as a {@code throw}, which keeps the control flow visible at the point it happens.
     *
     * <p>No {@code System.exit}, no {@code Runtime.halt} and no shutdown hook: the return code travels as an
     * exit status, and terminating the JVM would deny Spring Batch the chance to record the failure.
     *
     * @param message the legacy diagnostic, becoming the abend message
     * @param reason the abend reason
     * @param logicalFile the DD name involved, appended to the message for identity
     * @param operation the attempted operation
     * @param cause the underlying throwable to preserve, or {@code null}
     * @return the abend to throw, never {@code null}
     */
    private FatalProcessingException abendProgram(
            final String message,
            final String reason,
            final String logicalFile,
            final String operation,
            final Throwable cause) {

        LOG.error(MSG_ABENDING_PROGRAM);
        final String abendMessage = String.format(Locale.ROOT,
                "%s (DD %s, operation %s). app/jcl/COMBTRAN.jcl has no COBOL program, so the culprit is the"
                        + " JCL member itself.", message, logicalFile, operation);
        return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, abendMessage, cause);
    }

    /**
     * Masks a card number for a diagnostic, keeping only the last four digits.
     *
     * <p>Present so that the field at bytes 263-278 of the record has a safe rendering available should a
     * diagnostic ever need to name it. <b>No call site logs the field in full</b>, and this projection is the
     * only form permitted, at {@code DEBUG} only. Rule 1 Clause D: no primary account number in code, log or
     * comment.
     *
     * @param cardNumber the sixteen-character field, tolerated when {@code null} or shorter
     * @return the masked projection, never {@code null}
     */
    private static String maskCardNumber(final String cardNumber) {
        if (cardNumber == null) {
            return "****";
        }
        final String trimmed = cardNumber.strip();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * Rejects an absent or blank configuration value with a message naming the property.
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

    /**
     * Rejects a non-positive size or bound.
     *
     * @param value the injected value
     * @param property the property name, for the diagnostic
     * @return {@code value} unchanged
     * @throws IllegalArgumentException if {@code value} is not positive
     */
    private static int requirePositive(final int value, final String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    property + " must be greater than zero but was " + value);
        }
        return value;
    }

    /**
     * Validates a generation prefix against the one shared grammar.
     *
     * <p><strong>Finding m-02, severity Minor, RESOLVED.</strong> This method used to strip whitespace and
     * trailing separators and check nothing else, and five sibling classes each carried a variant of it that
     * differed. {@link GenerationPrefixContract#requireRelativePrefix(String, String)} is now the only
     * grammar, and it refuses a trailing separator rather than trimming one, so a configured value and the
     * value in force can no longer differ silently. Both declared values - {@code gdg/transact-combined} and
     * {@code gdg/transact-bkup} - satisfy it, so no shipped configuration changes behaviour.
     *
     * @param value the injected prefix
     * @param property the property name, for the diagnostic
     * @return the prefix unchanged, once it satisfies the shared grammar
     * @throws IllegalArgumentException if {@code value} is absent, blank or malformed
     */
    private static String requireGenerationPrefix(final String value, final String property) {
        return GenerationPrefixContract.requireRelativePrefix(value, property);
    }

    /**
     * Asserts at class initialisation that the offset map still tiles the record exactly.
     *
     * <p>The bounds above are derived from the encoder's field widths. Should a width ever change on one side
     * and not the other, every field after it would shift and the corruption would be invisible in review.
     * This check turns that into a startup failure naming the discrepancy.
     *
     * @throws IllegalStateException if the fields do not tile {@value #COMBINED_RECORD_LENGTH} bytes exactly,
     *     or if the borrowed constants disagree with the sort symbol at {@code app/jcl/COMBTRAN.jcl:L28}
     */
    private static void verifyOffsetMap() {
        if (TRAN_ID_LENGTH != 16) {
            throw new IllegalStateException("TRAN-ID must be 16 characters per app/jcl/COMBTRAN.jcl:L28"
                    + " TRAN-ID,1,16,CH but TransactionCombineProcessor reports " + TRAN_ID_LENGTH);
        }
        if (TRAN_FILLER_BEGIN + 20 != COMBINED_RECORD_LENGTH) {
            throw new IllegalStateException("The CVTRA05Y offset map tiles "
                    + (TRAN_FILLER_BEGIN + 20) + " bytes but app/jcl/COMBTRAN.jcl:L35 DCB=(*.SORTIN) fixes"
                    + " the record at " + COMBINED_RECORD_LENGTH);
        }
        if (TRAN_AMT_SIGN_INDEX != TRAN_MERCHANT_ID_BEGIN - 1) {
            throw new IllegalStateException(
                    "The TRAN-AMT sign overpunch must be the field's final byte");
        }
        if (POSITIVE_OVERPUNCH.length() != NEGATIVE_OVERPUNCH.length()) {
            throw new IllegalStateException(
                    "The two zoned-decimal overpunch alphabets must both hold ten digits");
        }
    }

    /**
     * Maps the flow's outcome onto the four legacy return codes.
     *
     * <p><b>This decider is not a {@code COND} gate.</b> {@code app/jcl/COMBTRAN.jcl:L41} carries no
     * {@code COND} parameter and neither does {@code :L22}, so nothing in this member gates one step on
     * another; see {@link CombineTransactionsJob#combineTransactionsFlow(Step, Step, Step)} for how the
     * absence is modelled. This decider exists only to distinguish outcomes <b>after</b> the work, because
     * without it a failed step and an abended step would both simply fail the flow and the orchestrated
     * pipeline could not tell return code 8 from return code 12.
     *
     * <p>The mapping:
     * <ul>
     *   <li>{@code COMPLETED} to {@code COMPLETED} - return code 0, the normal outcome.</li>
     *   <li>{@code COMPLETED WITH REJECTS} passed through - return code 4. <b>This job never originates
     *       it</b>: the only numeric literal assignment to {@code RETURN-CODE} in the corpus is
     *       {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}, which belongs to the posting
     *       job's reject counter, and this job has no program and no reject concept. The arm is recognised
     *       rather than mapped to success so that the orchestrated pipeline, whose deciders gate on the full
     *       code set, receives the same vocabulary from every job.</li>
     *   <li>{@code FAILED} to {@code FAILED} - return code 8, which is where a duplicate {@code TRAN-ID}
     *       arrives.</li>
     *   <li>Anything else to {@code ABEND} - return code 12. An unrecognised outcome is deliberately
     *       <b>not</b> treated as success; a future exit code must fail loudly rather than pass silently.</li>
     * </ul>
     *
     * <p>Stateless and immutable, so one instance serves every execution: it reads the outcome from the
     * {@link JobExecution} and holds nothing.
     */
    private static final class CombineTransactionsReturnCodeDecider implements JobExecutionDecider {

        /** Creates the decider. Stateless: every value it needs comes from the execution it is handed. */
        private CombineTransactionsReturnCodeDecider() {
            // No state: see the class documentation.
        }

        /**
         * {@inheritDoc}
         *
         * @param jobExecution the execution whose outcome is being classified
         * @param stepExecution the step that has just finished, or {@code null} when the decider is reached
         *     without one
         * @return the flow status naming the legacy return code, never {@code null}
         */
        @Override
        public FlowExecutionStatus decide(
                final JobExecution jobExecution, final StepExecution stepExecution) {

            final ExitStatus exitStatus = stepExecution == null
                    ? jobExecution.getExitStatus()
                    : stepExecution.getExitStatus();
            final String exitCode = exitStatus == null ? null : exitStatus.getExitCode();

            if (EXIT_CODE_COMPLETED.equals(exitCode)) {
                return new FlowExecutionStatus(EXIT_CODE_COMPLETED);
            }
            if (EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return new FlowExecutionStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
            }
            if (EXIT_CODE_FAILED.equals(exitCode)) {
                return new FlowExecutionStatus(EXIT_CODE_FAILED);
            }
            LOG.error("{} reported the unrecognised exit code {}; classifying it as an abend rather than"
                    + " letting it fall through to success", DEFAULT_JOB_NAME, exitCode);
            return new FlowExecutionStatus(EXIT_CODE_ABEND);
        }
    }

    /**
     * Establishes the diagnostic context for the run and restores it afterwards.
     *
     * <p>{@link CorrelationIdFilter}'s request path is HTTP-scoped and never runs for batch work, so a batch
     * job that did nothing here would emit records carrying no {@code jobInstanceId} and no
     * {@code correlationId} - and the observability package is not permitted a {@link JobExecutionListener} of
     * its own. This listener therefore establishes the context, and it does so through
     * {@link CorrelationIdFilter}'s own scope helpers rather than through literals and hand-rolled
     * put-and-remove pairs, so the key names have one definition and the lifecycle has one implementation.
     * {@code traceId} and {@code spanId} are published by the tracing bridge from real span identity and are
     * never set here.
     *
     * <p><b>Finding H-03, severity High.</b> This listener used to publish the job instance identifier with a
     * bare {@code propagateJobInstanceId} whose return value it discarded, and then end the run with an
     * unconditional {@code propagateJobInstanceId(null)}. Because Spring Batch runs jobs on pooled threads and
     * this job is launched from inside {@code BatchPipelineOrchestrator}'s stream, that removal erased the
     * enclosing pipeline's identity: every pipeline event emitted after the nested job finished carried no
     * {@code jobInstanceId} at all, and the correlation between a run's logs and its output objects - the
     * whole reason the key exists - was lost for the remainder of the stream. The correlation identifier was
     * already handled correctly by a mint-if-absent, clear-only-if-ours rule; the job instance identifier was
     * not, and one entry restored while the other is removed is the worst of both.
     *
     * <p>The fix is not a second hand-rolled snapshot. Both entries now go through
     * {@link CorrelationIdFilter#enterBatchScope(long, String)} and
     * {@link CorrelationIdFilter#exitBatchScope()}, which is the single authoritative implementation of the
     * park-and-restore contract - an entry that was absent is removed, an entry that existed is put back, and
     * scopes nest so an inner job restores its caller's context rather than the absence of one. See finding
     * M-02 for why five copies of that contract became one.
     *
     * <p><b>The correlation identifier is derived, not random.</b> {@value #CORRELATION_ID_PREFIX} followed by
     * the job execution identifier is deterministic, so a run's identifier can be recomputed from its
     * execution record when the logs are read back. The listener itself still carries <b>no mutable state at
     * all</b>: the displaced values live on the thread that displaced them, which is the only scope that could
     * correctly own them.
     */
    private static final class CombineTransactionsJobListener implements JobExecutionListener {

        /** Creates the listener. Stateless: every value it needs comes from the {@link JobExecution}. */
        private CombineTransactionsJobListener() {
            // No state: see the class documentation.
        }

        /**
         * {@inheritDoc}
         *
         * <p>Publishes the job instance identifier, and mints a correlation identifier only when no outer
         * scope has already established one. What either entry displaced is remembered on the thread so that
         * {@link #afterJob(JobExecution)} can put it back.
         *
         * @param jobExecution the starting execution
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            CorrelationIdFilter.enterBatchScope(
                    jobExecution.getJobInstance().getInstanceId(), mintedCorrelationId(jobExecution));
            LOG.info("START OF EXECUTION OF app/jcl/COMBTRAN.jcl - no COBOL program, SORT then IDCAMS REPRO");
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>Restores</b> the diagnostic context in a {@code finally}, so an exception raised while logging
         * the end of the run cannot leave this job's entries on a pooled thread for an unrelated later job to
         * inherit and be mislabelled by - and so an enclosing scope's entries survive this job rather than
         * being cleared along with it. Restoring serves both obligations; removing serves only the first.
         *
         * @param jobExecution the finishing execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                LOG.info("END OF EXECUTION OF app/jcl/COMBTRAN.jcl with exit status {}",
                        jobExecution.getExitStatus());
            } finally {
                CorrelationIdFilter.exitBatchScope();
            }
        }

        /**
         * The deterministic correlation identifier this listener would mint for an execution.
         *
         * <p>Composed of {@value #CORRELATION_ID_PREFIX} and the execution identifier, so it satisfies the
         * validated character set and length that {@link CorrelationIdFilter#propagate(String)} enforces.
         *
         * @param jobExecution the execution
         * @return the identifier, never {@code null}
         */
        private static String mintedCorrelationId(final JobExecution jobExecution) {
            return CORRELATION_ID_PREFIX + jobExecution.getId();
        }
    }
}
