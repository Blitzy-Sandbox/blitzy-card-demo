/*
 * ******************************************************************
 * Program     : CombinedTransactionReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (concatenated GDG sources)
 * Function    : Read the concatenated SORTIN of the transaction combine
 *               step - TRANSACT.BKUP(0) followed by SYSTRAN(0) - as one
 *               stream ordered ascending by TRAN-ID, decoding the 350-byte
 *               CVTRA05Y layout.
 * Source      : app/jcl/COMBTRAN.jcl:L22-L37 (STEP05R, EXEC PGM=SORT)
 *               app/jcl/COMBTRAN.jcl:L23-L26 (concatenated SORTIN, both (0))
 *               app/jcl/COMBTRAN.jcl:L28,L30 (SYMNAMES TRAN-ID,1,16,CH)
 *               app/jcl/COMBTRAN.jcl:L41-L48 (STEP10, IDCAMS REPRO)
 *               app/cpy/CVTRA05Y.cpy (350-byte TRAN-RECORD)
 *               No COBOL program exists for this job.
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
package com.cardemo.batch.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Presents the <b>concatenated</b> {@code SORTIN} of the transaction-combine step as a single stream of
 * {@link Transaction} records ordered ascending by {@code TRAN-ID}.
 * <p>
 * <b>There is no COBOL program for this job.</b> {@code app/jcl/COMBTRAN.jcl} is two utility steps and
 * nothing else - {@code STEP05R  EXEC PGM=SORT} at {@code :L22} and {@code STEP10 EXEC PGM=IDCAMS} at
 * {@code :L41} - so the JCL member itself is the source of truth and every assertion below cites it by
 * line. Where a shape is borrowed from elsewhere in the corpus, such as the file-status guard, it is cited
 * as borrowed rather than presented as this step's own.
 *
 * <h2>What it does</h2>
 * The legacy {@code SORTIN} is <b>two DD statements, the second of them unnamed</b>, which is how JCL
 * expresses dataset concatenation:
 * <pre>
 * //SORTIN   DD DISP=SHR,                                &lt;- app/jcl/COMBTRAN.jcl:L23
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)         &lt;- app/jcl/COMBTRAN.jcl:L24
 * //         DD DISP=SHR,                                 &lt;- app/jcl/COMBTRAN.jcl:L25
 * //         DSN=AWS.M2.CARDDEMO.SYSTRAN(0)               &lt;- app/jcl/COMBTRAN.jcl:L26
 * </pre>
 * {@code SYMNAMES} declares the sort key as {@code TRAN-ID,1,16,CH} ({@code :L28}) and {@code SYSIN}
 * declares the ordering as {@code SORT FIELDS=(TRAN-ID,A)} ({@code :L30}). <b>There is no
 * {@code INCLUDE COND} on this step</b>, unlike {@code app/proc/TRANREPT.prc} {@code STEP05R} which does
 * carry one, so no date filter is applied here and none may be imported. {@code SORTOUT} inherits the
 * record geometry of the concatenated input through {@code DCB=(*.SORTIN)} ({@code :L35}), which is what
 * fixes it at {@value #RECORD_LENGTH} bytes.
 * <p>
 * This class is therefore <b>one ordered stream over two current generations</b>, and nothing more. It does
 * not write. The {@code STEP10} bulk load - {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} at
 * {@code :L48}, loading {@code TRANSACT.COMBINED(+1)} into {@code TRANSACT.VSAM.KSDS} ({@code :L43-L46}) -
 * belongs to the planned {@code com.cardemo.batch.jobs.CombineTransactionsJob}, realised as a
 * {@code JdbcTemplate.batchUpdate}, with {@code com.cardemo.batch.processors.TransactionCombineProcessor}
 * owning the per-record merge and ordering semantics. <b>No external sort process is spawned here or
 * anywhere in this file</b>: the DFSORT specification becomes the {@link Comparator} of
 * {@link #TRAN_ID_ASCENDING} and there is no {@code Runtime.exec} and no {@code ProcessBuilder}.
 * <p>
 * <b>The 350-byte {@code app/cpy/CVTRA05Y.cpy} layout, one-based and inclusive, summing to
 * {@value #RECORD_LENGTH}.</b> The offsets are declared once, as the constants named in the last column,
 * and every extraction goes through {@link #fixedWidthField(String, int, int, String, ConcatenatedSource,
 * long)} so that no call site can disagree with the copybook.
 * <table border="1">
 * <caption>{@code app/cpy/CVTRA05Y.cpy:L5-L18}, {@code RECLN = 350} at {@code :L2}</caption>
 * <tr><th>COBOL field<th>PIC<th>Bytes<th>{@link Transaction} property<th>Constants
 * <tr><td>{@code TRAN-ID}<td>{@code X(16)}<td>1-16<td>{@code transactionId}, the {@code @Id}
 *     <td>{@link #TRANSACTION_ID_START}, {@link #TRANSACTION_ID_END}
 * <tr><td>{@code TRAN-TYPE-CD}<td>{@code X(02)}<td>17-18<td>{@code typeCode}
 *     <td>{@link #TYPE_CODE_START}, {@link #TYPE_CODE_END}
 * <tr><td>{@code TRAN-CAT-CD}<td>{@code 9(04)}<td>19-22<td>{@code categoryCode}
 *     <td>{@link #CATEGORY_CODE_START}, {@link #CATEGORY_CODE_END}
 * <tr><td>{@code TRAN-SOURCE}<td>{@code X(10)}<td>23-32<td>{@code transactionSource}, a plain
 *     {@code String} and never an enum<td>{@link #TRANSACTION_SOURCE_START},
 *     {@link #TRANSACTION_SOURCE_END}
 * <tr><td>{@code TRAN-DESC}<td>{@code X(100)}<td>33-132<td>{@code description}
 *     <td>{@link #DESCRIPTION_START}, {@link #DESCRIPTION_END}
 * <tr><td>{@code TRAN-AMT}<td>{@code S9(09)V99}<td>133-143<td>{@code amount}, the layout's <b>only</b>
 *     signed field<td>{@link #AMOUNT_START}, {@link #AMOUNT_END}
 * <tr><td>{@code TRAN-MERCHANT-ID}<td>{@code 9(09)}<td>144-152<td>{@code merchantId}
 *     <td>{@link #MERCHANT_ID_START}, {@link #MERCHANT_ID_END}
 * <tr><td>{@code TRAN-MERCHANT-NAME}<td>{@code X(50)}<td>153-202<td>{@code merchantName}
 *     <td>{@link #MERCHANT_NAME_START}, {@link #MERCHANT_NAME_END}
 * <tr><td>{@code TRAN-MERCHANT-CITY}<td>{@code X(50)}<td>203-252<td>{@code merchantCity}
 *     <td>{@link #MERCHANT_CITY_START}, {@link #MERCHANT_CITY_END}
 * <tr><td>{@code TRAN-MERCHANT-ZIP}<td>{@code X(10)}<td>253-262<td>{@code merchantZip}
 *     <td>{@link #MERCHANT_ZIP_START}, {@link #MERCHANT_ZIP_END}
 * <tr><td>{@code TRAN-CARD-NUM}<td>{@code X(16)}<td>263-278<td>{@code cardNumber}, <b>never logged</b>
 *     <td>{@link #CARD_NUMBER_START}, {@link #CARD_NUMBER_END}
 * <tr><td>{@code TRAN-ORIG-TS}<td>{@code X(26)}<td>279-304<td>{@code origTs}, a {@code String}
 *     <td>{@link #ORIG_TS_START}, {@link #ORIG_TS_END}
 * <tr><td>{@code TRAN-PROC-TS}<td>{@code X(26)}<td>305-330<td>{@code procTs}, a {@code String}
 *     <td>{@link #PROC_TS_START}, {@link #PROC_TS_END}
 * <tr><td>{@code FILLER}<td>{@code X(20)}<td>331-350<td><b>deliberately not modelled</b><td>-
 * </table>
 * <p>
 * {@code SYMNAMES TRAN-ID,1,16,CH} ({@code app/jcl/COMBTRAN.jcl:L28}) corroborates the identifier at bytes
 * 1-16 exactly, so <b>the sort key is the primary key</b>; {@code app/catlg/LISTCAT.txt:L3593} records
 * {@code KEYLEN 16} at {@code RKP 0} with {@code AVGLRECL 350} for
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} and {@code app/jcl/TRANFILE.jcl:L53-L54} declares
 * {@code KEYS(16 0)} and {@code RECORDSIZE(350 350)}. <b>Every offset cited in this class is one-based and
 * inclusive</b>, matching both the copybook and the {@code SYMNAMES} card.
 * <p>
 * The {@code TRAN-AMT} decode is <b>not implemented here</b>. It is
 * {@link TransactionBackupReader#decodeSignedTransactionAmount(String, long)}, which is the canonical owner
 * of the {@code CVTRA05Y} zoned-decimal overpunch decode and is package-private precisely so that this
 * class can reuse it. Reusing it rather than restating it is Rule 1 clause C3, <i>avoid duplication</i>: one
 * position-aware decoder per record layout, colocated with the reader that owns that layout, with
 * {@link DailyTransactionReader} owning the identically-shaped {@code app/cpy/CVTRA06Y.cpy} the same way. A
 * second copy would drift from the first, and promoting it into a shared codec class would add an eighth
 * file to a package that is complete at seven.
 *
 * <h3>Blocker: both references are {@code (0)}, so only the latest interest run is merged</h3>
 * <b>This is the defining fact of this class and it differs from {@link TransactionBackupReader}.</b> Both
 * {@code app/jcl/COMBTRAN.jcl:L24} and {@code :L26} name the <b>current</b> generation, {@code (0)}, not
 * {@code (+1)}:
 * <ul>
 * <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} - the latest transaction backup.</li>
 * <li>{@code AWS.M2.CARDDEMO.SYSTRAN(0)} - the latest interest-generated transaction file.
 *     {@code app/jcl/INTCALC.jcl:L37-L41} allocates {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} with
 *     {@code DISP=(NEW,CATLG,DELETE)} and {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}, so the interest job
 *     writes a <b>brand-new sequential generation on every run</b>;
 *     {@code app/cbl/CBACT04C.cbl:L53-L55} confirms the target is
 *     {@code ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS SEQUENTIAL} and <b>not</b> the keyed cluster.</li>
 * </ul>
 * <b>Consequence: only the most recent interest run is ever merged.</b> Earlier {@code SYSTRAN}
 * generations are never combined and their interest transactions never reach the master. That is the source
 * behaviour, it is preserved verbatim, and it is <b>not</b> "improved" by widening the read to every
 * generation - doing so would post interest twice.
 *
 * <h3>Blocker: a generation key is resolved once and then carried forward, never re-resolved</h3>
 * A relative generation reference is resolved once per job on z/OS and then held. The evidence is in this
 * very member: {@code :L37} writes {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} in {@code STEP05R} and
 * {@code :L43-L44} reads {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} in {@code STEP10} - the same
 * spelling, the same generation - and the pattern recurs at {@code app/proc/TRANREPT.prc:L31} to
 * {@code :L37} and again at {@code :L53} to {@code :L64}.
 * <p>
 * This reader's two inputs are {@code (0)}, so they <i>are</i> resolved by a lexical-greatest listing - but
 * <b>only inside {@link #open(ExecutionContext)}, exactly once, and the resolved keys are then recorded in
 * the execution context and reused for the remainder of the step</b>. {@link #read()} consults no listing,
 * no prefix and no "latest" rule. Re-resolving per chunk would produce a reader that passes every isolated
 * test and <b>races in the pipeline</b>, because a concurrent job writing a newer generation between two
 * chunks would shift the input underneath it. <b>Remediation if the symptom appears:</b> if a run is
 * observed to change generation part-way through a step, a key was re-resolved instead of carried forward;
 * the fix is to route it through {@link #resolveGeneration(ConcatenatedSource, String, String,
 * ExecutionContext)} at open time and through nothing else.
 *
 * <h3>Blocker: end of the first source is a transition, not end of input</h3>
 * {@link #read()} answers {@code null} <b>only when both sources are exhausted</b>. End of data on
 * {@code TRANSACT.BKUP} is the transition to {@code SYSTRAN}, modelled as the explicit state change
 * recorded by {@link #currentSource} and logged once. Treating it as end of input <b>silently drops every
 * interest transaction</b>, which is why the transition is an explicit, named, logged step rather than a
 * fall-through.
 *
 * <h3>Blocker: the two timestamps are 26-byte text and are never parsed</h3>
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} carrying three mutually incompatible
 * producer formats across the corpus, and <b>this reader's concatenated input mixes producers by
 * construction</b>: {@code TRANSACT.BKUP} carries posted transactions while {@code SYSTRAN} carries
 * interest transactions whose originating and processing timestamps are the same generated value. Any
 * attempt to parse would therefore fail on at least one of the two sources, which is why the text form
 * matters here more than anywhere else in the package. They are {@code String} over {@code CHAR(26)} and
 * <b>never a temporal type</b>: there is no {@code LocalDateTime}, {@code LocalDate}, {@code Timestamp},
 * {@code Instant}, {@code OffsetDateTime} or {@code DateTimeFormatter} anywhere in this file. A blank
 * timestamp survives as <b>26 spaces</b> - not {@code null}, not the empty string, not trimmed and not an
 * epoch.
 *
 * <h3>High: duplicate identifiers must fail the load, and are never collapsed here</h3>
 * The interest program writes to a fresh sequential generation and so performs <b>no duplicate-key
 * detection of its own</b>. Its identifiers are built by {@code app/cbl/CBACT04C.cbl:L474-L480} as
 * {@code STRING PARM-DATE, WS-TRANID-SUFFIX ... INTO TRAN-ID} - the ten-character date parameter of
 * {@code app/jcl/INTCALC.jcl:L22} ({@code PARM='2022071800'}) concatenated with the run-sequential
 * six-digit {@code WS-TRANID-SUFFIX} of {@code :L173} - so <b>a repeated date parameter across two interest
 * runs produces colliding identifiers</b>.
 * <p>
 * Those collisions surface for the first time in this job's {@code STEP10} load. The required behaviour is
 * that the load fails with {@code com.cardemo.exception.DuplicateRecordException} - file status
 * {@code '22'} - and a FAILED exit status, and <b>never a silent upsert, never {@code saveOrUpdate}, never
 * {@code ON CONFLICT DO UPDATE} and never an ignore</b>. <b>This reader therefore does not deduplicate, does
 * not collapse equal identifiers and does not drop a repeat</b>: doing any of those would hide the very
 * defect the failure exists to expose. It deliberately does not throw the exception either, because DFSORT
 * emits both records and it is the {@code REPRO} that rejects the second; pre-empting that here would move
 * the failure to the wrong step. What it does instead is <b>warn</b>, naming the two sources and the row
 * numbers, so that the subsequent load failure is already explained in the log. <b>Remediation:</b> a
 * duplicate-identifier failure means the interest job ran twice with the same date parameter; rerun it with
 * the correct parameter, or discard the redundant generation. It is the intended signal, not a bug, and it
 * must never be resolved by relaxing the load.
 * <p>
 * Because interest identifiers lead with the ten-character date they are numerically large and dominate the
 * descending-key browse used for identifier generation elsewhere once an interest run has occurred. That is
 * context only and is not this reader's concern.
 *
 * <h3>Medium: the ordering design, and the tradeoff it accepts</h3>
 * {@code SORT FIELDS=(TRAN-ID,A)} over a <b>concatenated</b> input means the <b>combined</b> stream is
 * ascending by {@code TRAN-ID}, not merely each source independently. Two designs are correct. This class
 * implements the first, and Rule 1 clause A5 requires the choice to be justified in writing:
 * <ul>
 * <li><b>Chosen - ordered merge.</b> Each source is read in {@code TRAN-ID} order and the two are
 *     interleaved by {@link #TRAN_ID_ASCENDING}. Memory is one look-ahead record per source, so the cost is
 *     O(1) in the input size and the whole step is a single pass. It is correct <b>only if each source is
 *     itself ordered</b>, which is a precondition rather than an assumption - see below.</li>
 * <li><b>Rejected - full ordering after concatenation.</b> Buffering the concatenated stream and sorting it
 *     is faithful to DFSORT without any precondition, but it is O(n) in memory or requires a spill, and
 *     "stream, never buffer a whole generation" is the standing constraint on this tier. A backup of the
 *     transaction cluster is unbounded in principle, so the buffering design trades a bounded resident set
 *     for a guarantee that can be obtained more cheaply by checking the precondition.</li>
 * </ul>
 * <b>The precondition is independently evidenced and is nevertheless checked.</b> It is evidenced because
 * {@code TRANSACT.BKUP} is a {@code REPRO} of an {@code INDEXED} cluster
 * ({@code app/ctl/REPROCT.ctl:L15}, {@code app/catlg/LISTCAT.txt:L3593} {@code KEYLEN 16} at {@code RKP 0}),
 * and a sequential copy of a KSDS is emitted in key order; and because {@code SYSTRAN}'s identifiers are a
 * constant date prefix followed by a monotonically incremented suffix
 * ({@code app/cbl/CBACT04C.cbl:L173}, {@code :L474-L480}). It is checked anyway, per source and per record,
 * by {@link #requireNonDescending(ConcatenatedSource, String, String, long)}: a <b>strictly decreasing</b>
 * pair within one source is a data defect and raises {@link DataIntegrityException}. DFSORT makes no
 * ordering assumption about its input, so the one capability given up relative to it is the ability to
 * <i>repair</i> an unsorted source - which is deliberate, because a mis-ordered backup is a defect that must
 * surface rather than be silently corrected. <b>Remediation if it fires:</b> the named generation was not
 * produced by a sequential copy of the keyed cluster; re-run the producing step rather than sorting the
 * object in place.
 * <p>
 * <b>Equal identifiers are explicitly not an ordering defect.</b> Both the per-source check and the
 * merge-output check are <i>non-decreasing</i>, not strictly increasing, so a duplicate passes through to
 * the load, where it belongs. Rejecting it as an ordering fault here would report the wrong defect at the
 * wrong step.
 *
 * <h3>Low: there is no {@code COND=} gating in this member</h3>
 * Elsewhere in the corpus {@code COND=(0,NE)} becomes a {@code JobExecutionDecider}. <b>{@code
 * app/jcl/COMBTRAN.jcl} contains no {@code COND=} at all and both of its steps run unconditionally</b>, so
 * no decider is warranted and none is added. Inventing one would be a behaviour change.
 *
 * <h3>Low: {@code SYSTRAN} is batch-only</h3>
 * {@code SYSTRAN} is one of the seven GDG bases ({@code app/catlg/LISTCAT.txt:L3942} reports
 * {@code GDG 7}) and it has <b>no CICS file definition</b> in {@code app/csd/CARDDEMO.CSD}, so it is
 * reachable from batch alone. Nothing online may read it and nothing here exposes it.
 *
 * <h2>How to run, build and test</h2>
 * The owning {@code Job} and {@code Step} are wired in {@code com.cardemo.config.BatchConfig} and will be
 * launched by the planned {@code com.cardemo.batch.jobs.CombineTransactionsJob} through the planned
 * {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}. <b>Neither of those two classes exists at the
 * time of writing</b> - they are the peers named in {@code docs/technical-specifications.md} - so
 * this reader is deliberately self-sufficient: it constructs from its own properties, resolves its own
 * generations and needs no collaborator from {@code com.cardemo.batch.jobs} to be usable or testable.
 * {@code spring.batch.job.enabled} is {@code false} ({@code src/main/resources/application.yml}), so
 * nothing runs at application startup.
 * <p>
 * Build and static gates, from the repository root. The host carries JDK 25 and Maven 3.9.11 on the
 * {@code PATH}, and Docker Engine with {@code docker compose} is available for the container-backed tiers:
 * <ul>
 * <li>{@code set -a; . ./.env; set +a} - the profile values, including the mandatory JWT secret. The file
 *     is git-ignored and must never be committed.</li>
 * <li>{@code ./mvnw -B -ntp -Ddependency-check.skip=true clean compile} - compiles the tree under
 *     {@code -Xlint:all -Werror} with {@code failOnWarning}, at {@code release 25}.</li>
 * <li>{@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify} - the full gate, including JaCoCo at
 *     an 0.80 line floor with no exclusions and the Javadoc gate at {@code failOnWarnings}.</li>
 * <li>The pinned container is an equivalent route where a host toolchain is not wanted:
 *     {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests
 *     compile}.</li>
 * </ul>
 * Tests belong in {@code src/test/java/com/cardemo/unit/batch} for the decode, the merge and the
 * boundary conditions; {@code src/test/java/com/cardemo/integration/batch} for the step and its restart;
 * and {@code src/test/java/com/cardemo/integration/aws} for the object-storage path against LocalStack.
 * The assertions that matter are: that the concatenation yields a <b>non-zero count from each source</b>;
 * that the <b>combined</b> stream is ascending by {@code TRAN-ID}; that
 * {@code 0000005047G}, {@code 0000009190}<code>&#125;</code> and {@code 0000000678H} decode to
 * {@code 504.77}, {@code -919.00} and {@code 67.88}; that two equal identifiers both reach the load; and
 * that the resolved keys are stable across chunks.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 * <li>{@value #PROPERTY_SOURCE} - {@code repository} or {@code object-storage}, case-insensitive and
 *     accepting a hyphen or an underscore. Default {@code repository}: the backup comes from the ordered
 *     relation, while {@code SYSTRAN} still comes from its current generation when the output bucket is
 *     configured. <b>Both values are reachable, so neither branch is dead code.</b></li>
 * <li>{@value #PROPERTY_PAGE_SIZE} - rows per database round trip on the {@code repository} path. Default
 *     {@value #DEFAULT_PAGE_SIZE}. Must be at least one.</li>
 * <li>{@value #PROPERTY_OUTPUT_BUCKET} - the versioned generation bucket, declared once in
 *     {@code src/main/resources/application.yml} as {@code ${CARDDEMO_S3_BATCH_OUTPUT_BUCKET}} with no
 *     literal default. Required non-blank on the {@code object-storage} path; on the repository path an
 *     omitted bucket makes the optional, not-yet-produced {@code SYSTRAN} source explicitly empty.</li>
 * <li>{@value #PROPERTY_BACKUP_GENERATION_PREFIX} - the {@code TRANSACT.BKUP} generation prefix. Default
 *     {@value #DEFAULT_BACKUP_GENERATION_PREFIX}, matching {@code app/proc/TRANREPT.prc:L21}
 *     {@code STEP01R}.</li>
 * <li>{@value #PROPERTY_SYSTRAN_GENERATION_PREFIX} - the {@code SYSTRAN} generation prefix. Default
 *     {@value #DEFAULT_SYSTRAN_GENERATION_PREFIX}, matching {@code app/jcl/DEFGDGB.jcl:L49}.</li>
 * <li>Chunk and commit interval are the owning step's, from {@code com.cardemo.config.BatchConfig}; this
 *     class owns only its page size.</li>
 * <li>The record charset is {@link #RECORD_CHARSET}, applied explicitly at the stream and never left to the
 *     platform default. {@code app/data/EBCDIC/**} is never parsed by the build.</li>
 * <li><b>Retention is documented intent, not enforced code.</b> Six of the seven GDG bases declare
 *     {@code LIMIT(5)}, {@code SYSTRAN} among them at {@code app/jcl/DEFGDGB.jcl:L49-L50}. The one source
 *     conflict is the {@code TRANREPT} base, {@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:L37-L38}
 *     against {@code LIMIT(10)} at {@code app/jcl/REPTFILE.jcl:L26-L27}; it is <b>resolved to 10</b>, the
 *     larger, so nothing the legacy system would have kept is discarded. That is the only legacy
 *     inconsistency the migration resolves and only because a single lifecycle value must be chosen. It
 *     concerns a base this reader does not read and is recorded here for completeness.</li>
 * <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} and {@code spring.jpa.open-in-view} is
 *     {@code false}, so no schema is generated and no lazy load escapes a service transaction.</li>
 * <li>The emulator endpoint override is a profile concern and is present in {@code local} and {@code test}
 *     only, which is what makes <b>no live-cloud path structurally reachable</b> from here. Clients arrive
 *     from {@code com.cardemo.config.AwsConfig}; <b>none is constructed here and no credential is handled
 *     here</b>. S3 lifecycle and versioning behaviour cannot be demonstrated from this class and is
 *     <b>{@code Not available}</b> without a running LocalStack container and the provisioning of
 *     {@code localstack-init/init-aws.sh}.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>End of input occurs only when both sources are exhausted.</b> {@link #read()} answering
 *     {@code null} is the Spring Batch end-of-input signal and the analogue of file status {@code '10'}
 *     driving {@code MOVE 16 TO APPL-RESULT} against {@code 88 APPL-EOF VALUE 16}
 *     ({@code app/cbl/CBTRN02C.cbl:L144}). It is not an error.</li>
 * <li><b>Interest transactions missing from the output</b> means the first source's end of file was wrongly
 *     treated as end of input. Check that {@link #currentSource} advanced to
 *     {@link ConcatenatedSource#SYSTRAN} and that the transition was logged.</li>
 * <li><b>A combined stream that is not globally ascending by {@code TRAN-ID}</b> means per-source order was
 *     assumed instead of validated. {@link #requireNonDescending(ConcatenatedSource, String, String, long)}
 *     is the check; if it did not fire, the merge selection is at fault rather than the data.</li>
 * <li><b>A duplicate-identifier failure at the load is the intended signal</b>, as set out above. Never
 *     resolve it with an upsert.</li>
 * <li><b>A record length other than {@value #RECORD_LENGTH}</b>, or an unrecognised terminal overpunch
 *     character, raises {@link DataIntegrityException} naming <b>the source</b>, the row number and the
 *     byte offsets - and <b>never the record content</b>. Naming the source is essential here: with two
 *     concatenated inputs, "row 4213 is malformed" is useless without knowing which generation it came
 *     from.</li>
 * <li><b>Reading a different generation part-way through a step</b> means a key was re-resolved instead of
 *     carried forward. See the carry-forward section above.</li>
 * <li><b>A {@code null} or trimmed 26-byte timestamp</b> means it was wrongly normalised. It must be 26
 *     characters, spaces included.</li>
 * <li><b>An absent {@code TRANSACT.BKUP} generation is a failure</b>, reported as {@code '35'} - the file
 *     is not available - because combining nothing into the master would silently leave the cluster
 *     unchanged while reporting success. <b>An absent or empty {@code SYSTRAN} generation is a successful
 *     empty read</b>, because {@code app/jcl/INTCALC.jcl} is a separate job that need not have run and the
 *     combine step carries no {@code COND=} requiring it to have. The two cases are deliberately
 *     asymmetric.</li>
 * <li><b>Any status that is neither {@code '00'} nor {@code '10'}</b> renders the legacy line
 *     {@code FILE STATUS IS: NNNN} followed by four characters ({@code app/cbl/CBTRN02C.cbl:L714-L731})
 *     and abends with abend code {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}
 *     and return code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.</li>
 * <li><b>Return code 4</b> - completed with rejects - belongs to the daily posting job and is never
 *     produced by this reader.</li>
 * </ul>
 *
 * <h2>Log hygiene and PII</h2>
 * A {@code CVTRA05Y} record carries a 16-character card number at bytes 263-278 and a monetary amount at
 * bytes 133-143. <b>No card number, no amount and no record content is ever logged, placed in an exception
 * message, or written to the {@link ExecutionContext}.</b> What is emitted is the source name, the
 * operation, row numbers, per-source and total counts, the two validated object keys, and the rendered file
 * status. There is <b>no per-record log event</b> on any path.
 *
 * <h2>Thread safety</h2>
 * Not thread safe, and not required to be. The {@code step} scope gives each step execution its own
 * instance and Spring Batch drives a reader from one thread per step. Every mutable field is an instance
 * field; <b>there is no static mutable state anywhere in this class</b> - the only static members are the
 * logger, immutable constants, the comparator, and pure functions.
 *
 * @see TransactionBackupReader
 * @see DailyTransactionReader
 * @see TransactionRepository
 * @see FileStatusMapper
 */
@Component
@StepScope
public class CombinedTransactionReader implements ItemStreamReader<Transaction> {

    /** The sole logging channel for this reader. */
    private static final Logger LOG = LoggerFactory.getLogger(CombinedTransactionReader.class);

    /**
     * Rows fetched per repository round trip when {@value #PROPERTY_PAGE_SIZE} is not configured.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * Default object-key prefix for {@code AWS.M2.CARDDEMO.TRANSACT.BKUP} generations.
     */
    public static final String DEFAULT_BACKUP_GENERATION_PREFIX = "gdg/transact-bkup/";

    /**
     * Default object-key prefix for {@code AWS.M2.CARDDEMO.SYSTRAN} generations.
     */
    public static final String DEFAULT_SYSTRAN_GENERATION_PREFIX = "gdg/systran/";

    /** Property selecting the repository or object-storage input substrate. */
    private static final String PROPERTY_SOURCE =
            "carddemo.batch.combined-transaction-reader.source";

    /** Property controlling the repository keyset-window size. */
    private static final String PROPERTY_PAGE_SIZE =
            "carddemo.batch.combined-transaction-reader.page-size";

    /** Property naming the versioned generation bucket. */
    private static final String PROPERTY_OUTPUT_BUCKET =
            "carddemo.aws.s3.batch-output-bucket";

    /** Property naming the {@code TRANSACT.BKUP} generation prefix. */
    private static final String PROPERTY_BACKUP_GENERATION_PREFIX =
            "carddemo.aws.s3.gdg-prefixes.transact-bkup";

    /** Property naming the {@code SYSTRAN} generation prefix. */
    private static final String PROPERTY_SYSTRAN_GENERATION_PREFIX =
            "carddemo.aws.s3.gdg-prefixes.systran";

    /** Environment variable that supplies {@link #PROPERTY_OUTPUT_BUCKET}. */
    private static final String ENV_OUTPUT_BUCKET = "CARDDEMO_S3_BATCH_OUTPUT_BUCKET";

    /** Logical JCL DD name carried by diagnostics. */
    private static final String LOGICAL_FILE = "SORTIN";

    /** Eight-character abend culprit naming the JCL member. */
    private static final String ABEND_CULPRIT = "COMBTRAN";

    /** Operation label used for open failures. */
    private static final String OPERATION_OPEN = "OPEN";

    /** Operation label used for read failures. */
    private static final String OPERATION_READ = "READ";

    /** Operation label used for close failures. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /** Legacy abend diagnostic emitted immediately before a fatal exception. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    /** Exact file status {@code 00}, successful I/O. */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /** Exact file status {@code 10}, normal end of file. */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /** Exact file status {@code 22}, duplicate key. */
    private static final String STATUS_DUPLICATE_KEY = requireExactCode(FileStatus.DUPLICATE_KEY);

    /** Exact file status {@code 23}, record not found. */
    private static final String STATUS_RECORD_NOT_FOUND =
            requireExactCode(FileStatus.RECORD_NOT_FOUND);

    /** Exact file status {@code 35}, file unavailable. */
    private static final String STATUS_FILE_UNAVAILABLE =
            requireExactCode(FileStatus.FILE_UNAVAILABLE);

    /** Generic physical-I/O member of the {@code 9x} status family. */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + '0';

    /** Object-key path separator. */
    private static final String KEY_SEPARATOR = "/";

    /** Maximum accepted object-key length before a request is attempted. */
    private static final int MAX_OBJECT_KEY_LENGTH = 1024;

    /** Execution-context marker preserving that no current generation existed. */
    private static final String ABSENT_GENERATION_MARKER = "<absent>";

    /** Execution-context marker identifying the relational backup substrate. */
    private static final String REPOSITORY_SOURCE_MARKER = "<repository>";

    /** Context key carrying the resolved {@code TRANSACT.BKUP(0)} object key. */
    private static final String CONTEXT_KEY_BACKUP_OBJECT_KEY =
            "carddemo.gdg.transact-bkup.objectKey";

    /** Context key carrying the resolved {@code SYSTRAN(0)} object key. */
    private static final String CONTEXT_KEY_SYSTRAN_OBJECT_KEY =
            "carddemo.gdg.systran.objectKey";

    /** Context key pinning the selected input substrate across a restart. */
    private static final String CONTEXT_KEY_INPUT_SOURCE =
            "CombinedTransactionReader.inputSource";

    /** Context key identifying the source that emitted the latest transaction. */
    private static final String CONTEXT_KEY_ACTIVE_SOURCE =
            "CombinedTransactionReader.activeSource";

    /** Context key carrying the total emitted row count. */
    private static final String CONTEXT_KEY_RECORDS_READ =
            "CombinedTransactionReader.recordsRead";

    /** Context key carrying the emitted backup row count. */
    private static final String CONTEXT_KEY_BACKUP_RECORDS_READ =
            "CombinedTransactionReader.backupRecordsRead";

    /** Context key carrying the emitted {@code SYSTRAN} row count. */
    private static final String CONTEXT_KEY_SYSTRAN_RECORDS_READ =
            "CombinedTransactionReader.systranRecordsRead";

    /** Context key carrying the globally latest emitted {@code TRAN-ID}. */
    private static final String CONTEXT_KEY_LAST_TRANSACTION_ID =
            "CombinedTransactionReader.lastTransactionId";

    /** Context key carrying the latest emitted backup {@code TRAN-ID}. */
    private static final String CONTEXT_KEY_BACKUP_LAST_TRANSACTION_ID =
            "CombinedTransactionReader.backupLastTransactionId";

    /** Context key carrying the latest emitted {@code SYSTRAN} {@code TRAN-ID}. */
    private static final String CONTEXT_KEY_SYSTRAN_LAST_TRANSACTION_ID =
            "CombinedTransactionReader.systranLastTransactionId";

    /** Fixed {@code CVTRA05Y} record length in bytes and ISO-8859-1 characters. */
    private static final int RECORD_LENGTH = 350;

    /** Byte-transparent charset preserving one input byte as one character. */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Character-stream buffer holding 32 complete records plus optional terminators. */
    private static final int STREAM_BUFFER_CHARS = 32 * (RECORD_LENGTH + 1);

    /** Sentinel returned by a reader at end of stream. */
    private static final int END_OF_STREAM = -1;

    /** Optional line-feed record terminator. */
    private static final char LINE_FEED = '\n';

    /** Optional carriage-return record terminator. */
    private static final char CARRIAGE_RETURN = '\r';

    /** One-based inclusive start of {@code TRAN-ID}. */
    private static final int TRANSACTION_ID_START = 1;

    /** One-based inclusive end of {@code TRAN-ID}. */
    private static final int TRANSACTION_ID_END = 16;

    /** One-based inclusive start of {@code TRAN-TYPE-CD}. */
    private static final int TYPE_CODE_START = 17;

    /** One-based inclusive end of {@code TRAN-TYPE-CD}. */
    private static final int TYPE_CODE_END = 18;

    /** One-based inclusive start of {@code TRAN-CAT-CD}. */
    private static final int CATEGORY_CODE_START = 19;

    /** One-based inclusive end of {@code TRAN-CAT-CD}. */
    private static final int CATEGORY_CODE_END = 22;

    /** One-based inclusive start of {@code TRAN-SOURCE}. */
    private static final int TRANSACTION_SOURCE_START = 23;

    /** One-based inclusive end of {@code TRAN-SOURCE}. */
    private static final int TRANSACTION_SOURCE_END = 32;

    /** One-based inclusive start of {@code TRAN-DESC}. */
    private static final int DESCRIPTION_START = 33;

    /** One-based inclusive end of {@code TRAN-DESC}. */
    private static final int DESCRIPTION_END = 132;

    /** One-based inclusive start of signed {@code TRAN-AMT}. */
    private static final int AMOUNT_START = 133;

    /** One-based inclusive end of signed {@code TRAN-AMT}. */
    private static final int AMOUNT_END = 143;

    /** One-based inclusive start of {@code TRAN-MERCHANT-ID}. */
    private static final int MERCHANT_ID_START = 144;

    /** One-based inclusive end of {@code TRAN-MERCHANT-ID}. */
    private static final int MERCHANT_ID_END = 152;

    /** One-based inclusive start of {@code TRAN-MERCHANT-NAME}. */
    private static final int MERCHANT_NAME_START = 153;

    /** One-based inclusive end of {@code TRAN-MERCHANT-NAME}. */
    private static final int MERCHANT_NAME_END = 202;

    /** One-based inclusive start of {@code TRAN-MERCHANT-CITY}. */
    private static final int MERCHANT_CITY_START = 203;

    /** One-based inclusive end of {@code TRAN-MERCHANT-CITY}. */
    private static final int MERCHANT_CITY_END = 252;

    /** One-based inclusive start of {@code TRAN-MERCHANT-ZIP}. */
    private static final int MERCHANT_ZIP_START = 253;

    /** One-based inclusive end of {@code TRAN-MERCHANT-ZIP}. */
    private static final int MERCHANT_ZIP_END = 262;

    /** One-based inclusive start of {@code TRAN-CARD-NUM}. */
    private static final int CARD_NUMBER_START = 263;

    /** One-based inclusive end of {@code TRAN-CARD-NUM}. */
    private static final int CARD_NUMBER_END = 278;

    /** One-based inclusive start of {@code TRAN-ORIG-TS}. */
    private static final int ORIG_TS_START = 279;

    /** One-based inclusive end of {@code TRAN-ORIG-TS}. */
    private static final int ORIG_TS_END = 304;

    /** One-based inclusive start of {@code TRAN-PROC-TS}. */
    private static final int PROC_TS_START = 305;

    /** One-based inclusive end of {@code TRAN-PROC-TS}. */
    private static final int PROC_TS_END = 330;

    /** Sixteen-space lower bound used for the first inclusive keyset page. */
    private static final String LOW_VALUES_TRANSACTION_ID =
            " ".repeat(TRANSACTION_ID_END);

    /** Global ascending comparator implementing {@code SORT FIELDS=(TRAN-ID,A)}. */
    private static final Comparator<Transaction> TRAN_ID_ASCENDING =
            Comparator.comparing(Transaction::getTransactionId);

    /** Configured substrate for the primary backup source. */
    private enum InputSource {

        /** Read the backup from the ordered transaction relation. */
        REPOSITORY,

        /** Read both sources from resolved generation objects. */
        OBJECT_STORAGE
    }

    /** The two DDs in {@code SORTIN}, in their source-card order. */
    private enum ConcatenatedSource {

        /** First DD: {@code TRANSACT.BKUP(0)}. */
        BACKUP("TRANSACT.BKUP", "AWS.M2.CARDDEMO.TRANSACT.BKUP", 1),

        /** Second, unnamed continuation DD: {@code SYSTRAN(0)}. */
        SYSTRAN("SYSTRAN", "AWS.M2.CARDDEMO.SYSTRAN", 2);

        /** Short source name used in diagnostics. */
        private final String displayName;

        /** Full legacy generation-base name. */
        private final String datasetName;

        /** One-based DD ordinal within the JCL concatenation. */
        private final int ddOrdinal;

        /**
         * Creates one immutable source descriptor.
         *
         * @param displayName short diagnostic name
         * @param datasetName full generation-base name
         * @param ddOrdinal one-based DD order in {@code SORTIN}
         */
        ConcatenatedSource(
                final String displayName,
                final String datasetName,
                final int ddOrdinal) {
            this.displayName = displayName;
            this.datasetName = datasetName;
            this.ddOrdinal = ddOrdinal;
        }
    }

    /**
     * Mutable cursor state for one member of the JCL concatenation.
     *
     * <p>The reference is final on the reader; only its step-scoped contents move.
     */
    private static final class SourceCursor {

        /** Immutable source described by this cursor. */
        private final ConcatenatedSource source;

        /** Concrete object key resolved at open, or {@code null} when no object is used. */
        private String resolvedObjectKey;

        /** Open fixed-width character stream on an object source. */
        private BufferedReader recordStream;

        /** Reusable exact-record buffer for object reads. */
        private char[] recordBuffer;

        /** Current bounded repository page. */
        private List<Transaction> pageBuffer;

        /** Index of the next transaction in {@link #pageBuffer}. */
        private int pageBufferIndex;

        /** Whether the previous repository slice advertised a successor. */
        private boolean morePagesAvailable;

        /** Exclusive keyset continuation for the next repository page. */
        private String pageKeyTransactionId;

        /** One fetched but not yet emitted merge candidate. */
        private Transaction pendingRecord;

        /** Whether {@link #pendingRecord} currently carries a candidate. */
        private boolean lookAheadLoaded;

        /** Whether this source has reached normal end of file. */
        private boolean exhausted;

        /** Number of source records fetched, including a pending look-ahead. */
        private long fetchedCount;

        /** Number of source records actually returned by {@link #read()}. */
        private long emittedCount;

        /** Latest fetched identifier used to validate per-source order. */
        private String previousTransactionId;

        /** Latest emitted identifier used for exact restart positioning. */
        private String lastEmittedTransactionId;

        /**
         * Creates a cold cursor for one source.
         *
         * @param source immutable source identity
         */
        SourceCursor(final ConcatenatedSource source) {
            this.source = source;
            reset();
        }

        /** Restores every mutable cursor member to its cold-start value. */
        private void reset() {
            resolvedObjectKey = null;
            recordStream = null;
            recordBuffer = null;
            pageBuffer = List.of();
            pageBufferIndex = 0;
            morePagesAvailable = true;
            pageKeyTransactionId = null;
            pendingRecord = null;
            lookAheadLoaded = false;
            exhausted = false;
            fetchedCount = 0L;
            emittedCount = 0L;
            previousTransactionId = null;
            lastEmittedTransactionId = null;
        }
    }

    /** Read-only transaction relation gateway. */
    private final TransactionRepository transactionRepository;

    /** Read-only object-storage gateway. */
    private final S3Operations objectStorage;

    /** Client used only for complete paged listings. */
    private final S3Client objectStoreClient;

    /** Shared translator for file-status arithmetic and rendering. */
    private final FileStatusMapper fileStatusMapper;

    /** Validated primary-source substrate. */
    private final InputSource inputSource;

    /** Positive repository keyset-window size. */
    private final int pageSize;

    /** Validated generation bucket, empty only when no object source is required. */
    private final String outputBucket;

    /** Validated {@code TRANSACT.BKUP} object-key prefix. */
    private final String backupGenerationPrefix;

    /** Validated {@code SYSTRAN} object-key prefix. */
    private final String systranGenerationPrefix;

    /** Step-scoped cursor for the first concatenated source. */
    private final SourceCursor backupCursor;

    /** Step-scoped cursor for the second concatenated source. */
    private final SourceCursor systranCursor;

    /** Source that most recently emitted, or the source expected first on a cold start. */
    private ConcatenatedSource currentSource;

    /** Source of the latest globally emitted transaction. */
    private ConcatenatedSource lastEmittedSource;

    /** One-based source row of the latest globally emitted transaction. */
    private long lastEmittedSourceRow;

    /** Total number of transactions returned to Spring Batch. */
    private long recordsRead;

    /** Latest globally emitted {@code TRAN-ID}, retained for order and restart checks. */
    private String lastTransactionId;

    /** Whether the first-source EOF transition has already been logged. */
    private boolean backupTransitionLogged;

    /** Whether {@link #open(ExecutionContext)} completed successfully. */
    private boolean fileOpen;

    /**
     * Creates the step-scoped reader and validates every injected configuration value.
     *
     * @param transactionRepository read-only access to the ordered transaction browse; must not be
     *     {@code null}
     * @param objectStorage read-only object access supplied by {@code AwsConfig}; must not be {@code null}
     * @param objectStoreClient read-only paged-listing client supplied by {@code AwsConfig}; must not be
     *     {@code null}
     * @param fileStatusMapper the shared file-status translator; must not be {@code null}
     * @param configuredSource {@code repository} or {@code object-storage}, from {@value #PROPERTY_SOURCE}
     * @param pageSize repository rows per bounded fetch, from {@value #PROPERTY_PAGE_SIZE}
     * @param outputBucket the generation bucket, from {@value #PROPERTY_OUTPUT_BUCKET}
     * @param backupGenerationPrefix the {@code TRANSACT.BKUP} prefix
     * @param systranGenerationPrefix the {@code SYSTRAN} prefix
     * @throws NullPointerException if a collaborator is {@code null}
     * @throws IllegalArgumentException if a selector, page size, prefix, or required bucket is invalid
     */
    public CombinedTransactionReader(
            final TransactionRepository transactionRepository,
            final S3Operations objectStorage,
            final S3Client objectStoreClient,
            final FileStatusMapper fileStatusMapper,
            @Value("${" + PROPERTY_SOURCE + ":repository}") final String configuredSource,
            @Value("${" + PROPERTY_PAGE_SIZE + ":" + DEFAULT_PAGE_SIZE + "}") final int pageSize,
            @Value("${" + PROPERTY_OUTPUT_BUCKET + ":}") final String outputBucket,
            @Value("${" + PROPERTY_BACKUP_GENERATION_PREFIX + ":"
                    + DEFAULT_BACKUP_GENERATION_PREFIX + "}")
                    final String backupGenerationPrefix,
            @Value("${" + PROPERTY_SYSTRAN_GENERATION_PREFIX + ":"
                    + DEFAULT_SYSTRAN_GENERATION_PREFIX + "}")
                    final String systranGenerationPrefix) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.objectStoreClient =
                Objects.requireNonNull(objectStoreClient, "objectStoreClient must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.inputSource = requireInputSource(configuredSource);
        this.pageSize = requirePositivePageSize(pageSize);
        this.outputBucket = requireOutputBucket(outputBucket, this.inputSource);
        this.backupGenerationPrefix = requireGenerationPrefix(
                backupGenerationPrefix,
                PROPERTY_BACKUP_GENERATION_PREFIX,
                DEFAULT_BACKUP_GENERATION_PREFIX);
        this.systranGenerationPrefix = requireGenerationPrefix(
                systranGenerationPrefix,
                PROPERTY_SYSTRAN_GENERATION_PREFIX,
                DEFAULT_SYSTRAN_GENERATION_PREFIX);
        this.backupCursor = new SourceCursor(ConcatenatedSource.BACKUP);
        this.systranCursor = new SourceCursor(ConcatenatedSource.SYSTRAN);
    }

    /**
     * Resolves both current-generation keys once, restores any restart cursor, and opens the selected input.
     *
     * <p>On the repository path the relation is the in-database equivalent of the backup source; the current
     * {@code SYSTRAN} generation remains the second source because no relation represents that pre-load file.
     * The object-storage path reads both sources as byte-faithful objects. No key is ever resolved by
     * {@link #read()}.
     *
     * @param executionContext the step execution context, or {@code null} for a cold standalone invocation
     * @throws FatalProcessingException if an object listing or open fails, or the required backup is absent
     * @throws DataIntegrityException if restart state is internally inconsistent
     */
    @Override
    public void open(final ExecutionContext executionContext) {
        if (fileOpen) {
            throw new IllegalStateException("open(ExecutionContext) called while SORTIN is already open");
        }

        resetState();
        restoreRestartState(executionContext);

        try {
            if (inputSource == InputSource.OBJECT_STORAGE) {
                backupCursor.resolvedObjectKey = resolveGeneration(
                        ConcatenatedSource.BACKUP,
                        backupGenerationPrefix,
                        CONTEXT_KEY_BACKUP_OBJECT_KEY,
                        executionContext);
                systranCursor.resolvedObjectKey = resolveGeneration(
                        ConcatenatedSource.SYSTRAN,
                        systranGenerationPrefix,
                        CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                        executionContext);
                recordResolvedGenerationKeys(executionContext);
                openObjectSource(backupCursor);
                openObjectSource(systranCursor);
                positionObjectCursorAfterRestart(backupCursor);
                positionObjectCursorAfterRestart(systranCursor);
            } else {
                configureRepositoryPath(executionContext);
            }

            fileOpen = true;
            LOG.info(
                    "{} opened; substrate={} backupKey={} systranKey={} backupResumeRows={} "
                            + "systranResumeRows={}",
                    LOGICAL_FILE,
                    inputSource,
                    displayGenerationKey(backupCursor.resolvedObjectKey, REPOSITORY_SOURCE_MARKER),
                    displayGenerationKey(systranCursor.resolvedObjectKey, ABSENT_GENERATION_MARKER),
                    Long.valueOf(backupCursor.emittedCount),
                    Long.valueOf(systranCursor.emittedCount));
        } catch (RuntimeException failure) {
            closeAfterOpenFailure(failure);
            throw failure;
        }
    }

    /**
     * Returns the next transaction in global {@code TRAN-ID} order.
     *
     * <p>One look-ahead record is held per source. Equal identifiers are emitted backup-first and are never
     * collapsed. Exhaustion of {@code TRANSACT.BKUP} records the explicit transition to {@code SYSTRAN};
     * {@code null} is returned only after both cursors are exhausted.
     *
     * @return the next ordered transaction, or {@code null} only when both sources are exhausted
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}
     * @throws DataIntegrityException if either source descends or the merged output descends
     * @throws FatalProcessingException if an input reports an I/O failure
     */
    @Override
    public Transaction read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); generation keys are resolved only at open");
        }

        ensureLookAhead(backupCursor);
        ensureLookAhead(systranCursor);

        final SourceCursor selected = selectNextCursor();
        if (selected == null) {
            return null;
        }

        final Transaction next = selected.pendingRecord;
        final String transactionId = requireTransactionId(next, selected.source, selected.fetchedCount);
        requireMergedOrder(transactionId, selected);

        selected.pendingRecord = null;
        selected.lookAheadLoaded = false;
        selected.emittedCount++;
        selected.lastEmittedTransactionId = transactionId;
        recordsRead++;
        currentSource = selected.source;
        lastEmittedSource = selected.source;
        lastEmittedSourceRow = selected.emittedCount;
        lastTransactionId = transactionId;
        return next;
    }

    /**
     * Checkpoints both generation keys and the exact emitted position of both source cursors.
     *
     * <p>The context contains only object keys, source names, row counts, and transaction identifiers. It
     * never contains a card number, amount, merchant value, or record image.
     *
     * @param executionContext the step execution context to mutate; {@code null} is ignored
     */
    @Override
    public void update(final ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }

        recordResolvedGenerationKeys(executionContext);
        executionContext.putString(CONTEXT_KEY_INPUT_SOURCE, inputSource.name());
        executionContext.putString(
                CONTEXT_KEY_ACTIVE_SOURCE,
                currentSource == null ? ConcatenatedSource.BACKUP.name() : currentSource.name());
        executionContext.putLong(CONTEXT_KEY_RECORDS_READ, recordsRead);
        executionContext.putLong(CONTEXT_KEY_BACKUP_RECORDS_READ, backupCursor.emittedCount);
        executionContext.putLong(CONTEXT_KEY_SYSTRAN_RECORDS_READ, systranCursor.emittedCount);
        putOptionalString(executionContext, CONTEXT_KEY_LAST_TRANSACTION_ID, lastTransactionId);
        putOptionalString(
                executionContext,
                CONTEXT_KEY_BACKUP_LAST_TRANSACTION_ID,
                backupCursor.lastEmittedTransactionId);
        putOptionalString(
                executionContext,
                CONTEXT_KEY_SYSTRAN_LAST_TRANSACTION_ID,
                systranCursor.lastEmittedTransactionId);
    }

    /**
     * Releases both source streams and logs per-source and total emitted counts.
     *
     * @throws FatalProcessingException if either stream cannot be closed; both close attempts are still made
     */
    @Override
    public void close() {
        IOException closeFailure = null;
        ConcatenatedSource failingSource = null;

        try {
            closeCursorStream(backupCursor);
        } catch (IOException failure) {
            closeFailure = failure;
            failingSource = ConcatenatedSource.BACKUP;
        }

        try {
            closeCursorStream(systranCursor);
        } catch (IOException failure) {
            if (closeFailure == null) {
                closeFailure = failure;
                failingSource = ConcatenatedSource.SYSTRAN;
            } else {
                closeFailure.addSuppressed(failure);
            }
        }

        fileOpen = false;
        clearTransientBuffers(backupCursor);
        clearTransientBuffers(systranCursor);

        LOG.info(
                "{} closed; substrate={} backupKey={} systranKey={} backupRecords={} systranRecords={} "
                        + "totalRecords={}",
                LOGICAL_FILE,
                inputSource,
                displayGenerationKey(backupCursor.resolvedObjectKey, REPOSITORY_SOURCE_MARKER),
                displayGenerationKey(systranCursor.resolvedObjectKey, ABSENT_GENERATION_MARKER),
                Long.valueOf(backupCursor.emittedCount),
                Long.valueOf(systranCursor.emittedCount),
                Long.valueOf(recordsRead));

        if (closeFailure != null) {
            final ConcatenatedSource source =
                    failingSource == null ? ConcatenatedSource.BACKUP : failingSource;
            throw statusException(
                    source,
                    STATUS_PHYSICAL_IO_ERROR,
                    OPERATION_CLOSE,
                    closeFailure,
                    "ERROR CLOSING " + source.displayName + " FILE");
        }
    }

    /**
     * Returns the total number of records emitted from both sources.
     *
     * @return the total emitted count since the most recent {@link #open(ExecutionContext)}
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    /**
     * Returns the number of emitted {@code TRANSACT.BKUP} records.
     *
     * @return the first-source emitted count
     */
    public long getBackupRecordsRead() {
        return backupCursor.emittedCount;
    }

    /**
     * Returns the number of emitted {@code SYSTRAN} records.
     *
     * @return the second-source emitted count
     */
    public long getSystranRecordsRead() {
        return systranCursor.emittedCount;
    }

    /**
     * Returns the resolved {@code TRANSACT.BKUP(0)} object key.
     *
     * @return the concrete key on the object-storage path, or {@code null} on the repository path and before
     *     {@link #open(ExecutionContext)}
     */
    public String getResolvedBackupObjectKey() {
        return backupCursor.resolvedObjectKey;
    }

    /**
     * Returns the resolved {@code SYSTRAN(0)} object key.
     *
     * @return the concrete key on the object-storage path, or {@code null} when no current generation exists,
     *     no generation bucket is configured, or before {@link #open(ExecutionContext)}
     */
    public String getResolvedSystranObjectKey() {
        return systranCursor.resolvedObjectKey;
    }

    /** Resets both cursors and all merge-level mutable state before an open. */
    private void resetState() {
        backupCursor.reset();
        systranCursor.reset();
        currentSource = ConcatenatedSource.BACKUP;
        lastEmittedSource = null;
        lastEmittedSourceRow = 0L;
        recordsRead = 0L;
        lastTransactionId = null;
        backupTransitionLogged = false;
        fileOpen = false;
    }

    /**
     * Restores and cross-validates the restart counters, source, and identifiers.
     *
     * @param executionContext context supplied to {@link #open(ExecutionContext)}
     */
    private void restoreRestartState(final ExecutionContext executionContext) {
        if (executionContext == null || !executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            return;
        }

        final String checkpointedInput = executionContext.getString(
                CONTEXT_KEY_INPUT_SOURCE,
                inputSource.name());
        if (!inputSource.name().equals(checkpointedInput)) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Combined transaction restart selected substrate %s but its checkpoint records %s; "
                            + "changing substrate across a restart can skip or repeat rows",
                    inputSource,
                    checkpointedInput),
                    CONTEXT_KEY_INPUT_SOURCE,
                    LOGICAL_FILE);
        }

        recordsRead = requireNonNegativeContextLong(
                executionContext,
                CONTEXT_KEY_RECORDS_READ);
        backupCursor.emittedCount = requireNonNegativeContextLong(
                executionContext,
                CONTEXT_KEY_BACKUP_RECORDS_READ);
        systranCursor.emittedCount = requireNonNegativeContextLong(
                executionContext,
                CONTEXT_KEY_SYSTRAN_RECORDS_READ);

        final long sourceTotal;
        try {
            sourceTotal = Math.addExact(
                    backupCursor.emittedCount,
                    systranCursor.emittedCount);
        } catch (ArithmeticException overflow) {
            throw new DataIntegrityException(
                    "Combined transaction restart row counters overflow a signed 64-bit total",
                    CONTEXT_KEY_RECORDS_READ,
                    LOGICAL_FILE,
                    overflow);
        }
        if (recordsRead != sourceTotal) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Combined transaction restart total is %d but its two source counters sum to %d",
                    Long.valueOf(recordsRead),
                    Long.valueOf(sourceTotal)),
                    CONTEXT_KEY_RECORDS_READ,
                    LOGICAL_FILE);
        }

        lastTransactionId = getOptionalContextString(
                executionContext,
                CONTEXT_KEY_LAST_TRANSACTION_ID);
        backupCursor.lastEmittedTransactionId = getOptionalContextString(
                executionContext,
                CONTEXT_KEY_BACKUP_LAST_TRANSACTION_ID);
        systranCursor.lastEmittedTransactionId = getOptionalContextString(
                executionContext,
                CONTEXT_KEY_SYSTRAN_LAST_TRANSACTION_ID);

        currentSource = requireContextSource(executionContext);
        lastEmittedSource = recordsRead == 0L ? null : currentSource;
        if (lastEmittedSource == ConcatenatedSource.BACKUP) {
            lastEmittedSourceRow = backupCursor.emittedCount;
        } else if (lastEmittedSource == ConcatenatedSource.SYSTRAN) {
            lastEmittedSourceRow = systranCursor.emittedCount;
        }

        requireRestartIdentifier(
                backupCursor,
                CONTEXT_KEY_BACKUP_LAST_TRANSACTION_ID);
        requireRestartIdentifier(
                systranCursor,
                CONTEXT_KEY_SYSTRAN_LAST_TRANSACTION_ID);
        if (recordsRead > 0L && lastTransactionId == null) {
            throw new DataIntegrityException(
                    "Combined transaction restart has emitted rows but no global TRAN-ID checkpoint",
                    CONTEXT_KEY_LAST_TRANSACTION_ID,
                    LOGICAL_FILE);
        }

        LOG.info(
                "{} restart restored; substrate={} activeSource={} backupRows={} systranRows={} "
                        + "checkpointedKey={}",
                LOGICAL_FILE,
                inputSource,
                currentSource,
                Long.valueOf(backupCursor.emittedCount),
                Long.valueOf(systranCursor.emittedCount),
                lastTransactionId == null ? "absent" : "present");
    }

    /**
     * Configures the repository-backed first source and optional object-backed second source.
     *
     * @param executionContext context used to carry the second-source key
     */
    private void configureRepositoryPath(final ExecutionContext executionContext) {
        backupCursor.pageKeyTransactionId = backupCursor.lastEmittedTransactionId;
        backupCursor.fetchedCount = backupCursor.emittedCount;
        backupCursor.previousTransactionId = backupCursor.lastEmittedTransactionId;

        if (outputBucket.isEmpty()) {
            if (executionContext != null
                    && executionContext.containsKey(CONTEXT_KEY_SYSTRAN_OBJECT_KEY)
                    && !ABSENT_GENERATION_MARKER.equals(executionContext.getString(
                            CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                            ""))) {
                throw new DataIntegrityException(
                        "Repository-path restart carried a SYSTRAN key but no generation bucket is configured",
                        CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                        ConcatenatedSource.SYSTRAN.datasetName);
            }
            if (systranCursor.emittedCount != 0L
                    || systranCursor.lastEmittedTransactionId != null) {
                throw new DataIntegrityException(
                        "Repository-path restart carries SYSTRAN rows but no generation bucket is configured",
                        CONTEXT_KEY_SYSTRAN_RECORDS_READ,
                        LOGICAL_FILE);
            }
            systranCursor.exhausted = true;
        } else {
            systranCursor.resolvedObjectKey = resolveGeneration(
                    ConcatenatedSource.SYSTRAN,
                    systranGenerationPrefix,
                    CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                    executionContext);
            recordResolvedGenerationKeys(executionContext);
            openObjectSource(systranCursor);
            positionObjectCursorAfterRestart(systranCursor);
        }

        if (outputBucket.isEmpty()) {
            recordResolvedGenerationKeys(executionContext);
        }
        LOG.info(
                "{} repository substrate opened; {} is the ordered relation and {} is the current "
                        + "generation when a bucket is configured",
                LOGICAL_FILE,
                ConcatenatedSource.BACKUP.displayName,
                ConcatenatedSource.SYSTRAN.displayName);
    }

    /**
     * Resolves one current generation from a checkpoint or a complete paged listing.
     *
     * @param source logical concatenated source
     * @param generationPrefix validated generation prefix
     * @param contextKey execution-context key carrying a prior resolution
     * @param executionContext current step context, or {@code null}
     * @return validated concrete object key, or {@code null} for an absent optional source
     */
    private String resolveGeneration(
            final ConcatenatedSource source,
            final String generationPrefix,
            final String contextKey,
            final ExecutionContext executionContext) {
        if (executionContext != null && executionContext.containsKey(contextKey)) {
            final String checkpointed = executionContext.getString(contextKey, "");
            if (ABSENT_GENERATION_MARKER.equals(checkpointed)) {
                if (source == ConcatenatedSource.SYSTRAN) {
                    LOG.info(
                            "{} carried forward the absence of {} under execution-context key '{}'",
                            LOGICAL_FILE,
                            source.datasetName,
                            contextKey);
                    return null;
                }
                throw statusException(
                        source,
                        STATUS_FILE_UNAVAILABLE,
                        OPERATION_OPEN,
                        null,
                        "ERROR OPENING " + source.displayName);
            }
            if (REPOSITORY_SOURCE_MARKER.equals(checkpointed)) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "Object-storage restart found repository marker under execution-context key '%s'; "
                                + "changing substrate across a restart is not permitted",
                        contextKey),
                        contextKey,
                        source.datasetName);
            }
            return requireKeyWithinGeneration(
                    checkpointed,
                    generationPrefix,
                    source,
                    "the step execution context");
        }

        String greatestKey = null;
        try {
            for (final S3Object listed : objectStoreClient.listObjectsV2Paginator(
                    ListObjectsV2Request.builder()
                            .bucket(outputBucket)
                            .prefix(generationPrefix)
                            .build())
                    .contents()) {
                final String candidate = listed.key();
                if (candidate == null
                        || candidate.isBlank()
                        || candidate.endsWith(KEY_SEPARATOR)) {
                    continue;
                }
                final String verified = requireKeyWithinGeneration(
                        candidate,
                        generationPrefix,
                        source,
                        "the paged object listing");
                if (greatestKey == null || verified.compareTo(greatestKey) > 0) {
                    greatestKey = verified;
                }
            }
        } catch (FatalProcessingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw statusException(
                    source,
                    STATUS_PHYSICAL_IO_ERROR,
                    OPERATION_OPEN,
                    failure,
                    "ERROR LISTING " + source.displayName);
        }

        if (greatestKey == null && source == ConcatenatedSource.BACKUP) {
            throw statusException(
                    source,
                    STATUS_FILE_UNAVAILABLE,
                    OPERATION_OPEN,
                    null,
                    "ERROR OPENING " + source.displayName);
        }
        if (greatestKey == null) {
            LOG.info(
                    "{} found no current {} generation under prefix '{}'; the second source is empty",
                    LOGICAL_FILE,
                    source.datasetName,
                    generationPrefix);
            return null;
        }

        LOG.info(
                "{} resolved {}(0) once at open; key={}",
                LOGICAL_FILE,
                source.datasetName,
                greatestKey);
        return greatestKey;
    }

    /**
     * Records both fixed source selections immediately after resolution.
     *
     * @param executionContext context to mutate, or {@code null}
     */
    private void recordResolvedGenerationKeys(final ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }

        if (inputSource == InputSource.OBJECT_STORAGE) {
            executionContext.putString(
                    CONTEXT_KEY_BACKUP_OBJECT_KEY,
                    backupCursor.resolvedObjectKey == null
                            ? ABSENT_GENERATION_MARKER
                            : backupCursor.resolvedObjectKey);
            executionContext.putString(
                    CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                    systranCursor.resolvedObjectKey == null
                            ? ABSENT_GENERATION_MARKER
                            : systranCursor.resolvedObjectKey);
        } else {
            executionContext.putString(
                    CONTEXT_KEY_BACKUP_OBJECT_KEY,
                    REPOSITORY_SOURCE_MARKER);
            executionContext.putString(
                    CONTEXT_KEY_SYSTRAN_OBJECT_KEY,
                    systranCursor.resolvedObjectKey == null
                            ? ABSENT_GENERATION_MARKER
                            : systranCursor.resolvedObjectKey);
        }
    }

    /**
     * Opens one resolved object as a fixed-width character stream.
     *
     * @param cursor source cursor whose key has already been resolved
     */
    private void openObjectSource(final SourceCursor cursor) {
        if (cursor.resolvedObjectKey == null) {
            cursor.exhausted = true;
            return;
        }

        final boolean exists;
        try {
            exists = objectStorage.objectExists(
                    outputBucket,
                    cursor.resolvedObjectKey);
        } catch (RuntimeException failure) {
            throw statusException(
                    cursor.source,
                    STATUS_PHYSICAL_IO_ERROR,
                    OPERATION_OPEN,
                    failure,
                    "ERROR OPENING " + cursor.source.displayName);
        }
        if (!exists) {
            throw statusException(
                    cursor.source,
                    STATUS_FILE_UNAVAILABLE,
                    OPERATION_OPEN,
                    null,
                    "ERROR OPENING " + cursor.source.displayName);
        }

        try {
            final S3Resource resource = objectStorage.download(
                    outputBucket,
                    cursor.resolvedObjectKey);
            final InputStream byteStream = resource.getInputStream();
            cursor.recordStream = new BufferedReader(
                    new InputStreamReader(byteStream, RECORD_CHARSET),
                    STREAM_BUFFER_CHARS);
            cursor.recordBuffer = new char[RECORD_LENGTH];
        } catch (IOException | RuntimeException failure) {
            throw statusException(
                    cursor.source,
                    STATUS_PHYSICAL_IO_ERROR,
                    OPERATION_OPEN,
                    failure,
                    "ERROR OPENING " + cursor.source.displayName);
        }
    }

    /**
     * Skips exactly the rows already emitted and validates the restart landing record.
     *
     * @param cursor open object cursor carrying restored emitted state
     */
    private void positionObjectCursorAfterRestart(final SourceCursor cursor) {
        if (cursor.emittedCount == 0L) {
            return;
        }
        if (cursor.exhausted || cursor.recordStream == null) {
            throw statusException(
                    cursor.source,
                    STATUS_FILE_UNAVAILABLE,
                    OPERATION_OPEN,
                    null,
                    String.format(Locale.ROOT,
                            "%s restart reports %d emitted rows for %s but no resolved generation is open",
                            LOGICAL_FILE,
                            Long.valueOf(cursor.emittedCount),
                            cursor.source.datasetName));
        }

        Transaction lastSkipped = null;
        try {
            for (long skipped = 0L; skipped < cursor.emittedCount; skipped++) {
                final String status = readNextRecordFromObject(cursor);
                if (!STATUS_SUCCESS.equals(status)) {
                    throw statusException(
                            cursor.source,
                            STATUS_FILE_UNAVAILABLE,
                            OPERATION_OPEN,
                            null,
                            String.format(Locale.ROOT,
                                    "%s restart expected %d previously emitted rows in %s but reached end "
                                            + "after %d",
                                    LOGICAL_FILE,
                                    Long.valueOf(cursor.emittedCount),
                                    cursor.source.datasetName,
                                    Long.valueOf(skipped)));
                }
                lastSkipped = cursor.pendingRecord;
                cursor.pendingRecord = null;
            }
        } catch (IOException failure) {
            throw statusException(
                    cursor.source,
                    STATUS_PHYSICAL_IO_ERROR,
                    OPERATION_OPEN,
                    failure,
                    "ERROR POSITIONING " + cursor.source.displayName);
        }

        if (lastSkipped == null) {
            throw new DataIntegrityException(
                    "Restart positioning emitted a positive row count but produced no landing record",
                    CONTEXT_KEY_RECORDS_READ,
                    cursor.source.datasetName);
        }
        final String observed = requireTransactionId(
                lastSkipped,
                cursor.source,
                cursor.emittedCount);
        if (!observed.equals(cursor.lastEmittedTransactionId)) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s restart cannot resume %s because the last skipped row does not match its checkpoint",
                    LOGICAL_FILE,
                    cursor.source.datasetName),
                    sourceLastIdentifierContextKey(cursor.source),
                    cursor.source.datasetName);
        }
    }

    /**
     * Releases partially opened streams while preserving close failures as suppressed causes.
     *
     * @param primaryFailure failure that aborted open
     */
    private void closeAfterOpenFailure(final RuntimeException primaryFailure) {
        try {
            closeCursorStream(backupCursor);
        } catch (IOException closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
        try {
            closeCursorStream(systranCursor);
        } catch (IOException closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
        fileOpen = false;
    }

    /**
     * Closes one object stream when present.
     *
     * @param cursor cursor to release
     * @throws IOException if the stream refuses to close
     */
    private static void closeCursorStream(final SourceCursor cursor) throws IOException {
        if (cursor.recordStream != null) {
            cursor.recordStream.close();
            cursor.recordStream = null;
        }
    }

    /**
     * Releases non-checkpointed page, look-ahead, and record buffers.
     *
     * @param cursor cursor whose transient buffers are cleared
     */
    private static void clearTransientBuffers(final SourceCursor cursor) {
        cursor.pageBuffer = List.of();
        cursor.pageBufferIndex = 0;
        cursor.pendingRecord = null;
        cursor.lookAheadLoaded = false;
        cursor.recordBuffer = null;
    }

    /**
     * Loads at most one merge candidate and translates its sequential file status.
     *
     * @param cursor source needing a candidate
     */
    private void ensureLookAhead(final SourceCursor cursor) {
        if (cursor.lookAheadLoaded || cursor.exhausted) {
            return;
        }

        Throwable inputFailure = null;
        String status;
        try {
            status = readNextRecord(cursor);
        } catch (IOException | DataAccessException failure) {
            inputFailure = failure;
            status = STATUS_PHYSICAL_IO_ERROR;
        }

        final int applResult = fileStatusMapper.applResultForSequentialRead(status);
        if (applResult == FileStatusMapper.APPL_AOK) {
            if (cursor.pendingRecord == null) {
                throw statusException(
                        cursor.source,
                        STATUS_PHYSICAL_IO_ERROR,
                        OPERATION_READ,
                        null,
                        "ERROR READING " + cursor.source.displayName + " FILE");
            }
            cursor.lookAheadLoaded = true;
            return;
        }

        if (applResult == FileStatusMapper.APPL_EOF) {
            cursor.pendingRecord = null;
            cursor.exhausted = true;
            if (cursor.source == ConcatenatedSource.BACKUP && !backupTransitionLogged) {
                backupTransitionLogged = true;
                currentSource = ConcatenatedSource.SYSTRAN;
                LOG.info(
                        "{} first source exhausted after {} rows; transitioning to {} rather than ending input",
                        LOGICAL_FILE,
                        Long.valueOf(cursor.emittedCount),
                        ConcatenatedSource.SYSTRAN.datasetName);
            }
            return;
        }

        throw statusException(
                cursor.source,
                status,
                OPERATION_READ,
                inputFailure,
                "ERROR READING " + cursor.source.displayName + " FILE");
    }

    /**
     * Selects the lower of the two look-ahead identifiers, backup first on equality.
     *
     * @return cursor whose pending record must be emitted, or {@code null} when both are exhausted
     */
    private SourceCursor selectNextCursor() {
        if (!backupCursor.lookAheadLoaded && !systranCursor.lookAheadLoaded) {
            return null;
        }
        if (!backupCursor.lookAheadLoaded) {
            return systranCursor;
        }
        if (!systranCursor.lookAheadLoaded) {
            return backupCursor;
        }

        final int comparison = TRAN_ID_ASCENDING.compare(
                backupCursor.pendingRecord,
                systranCursor.pendingRecord);
        return comparison <= 0 ? backupCursor : systranCursor;
    }

    /**
     * Verifies global non-descending order and reports, but retains, equal identifiers.
     *
     * @param transactionId candidate identifier
     * @param selected cursor that supplied the candidate
     */
    private void requireMergedOrder(
            final String transactionId,
            final SourceCursor selected) {
        if (lastTransactionId != null) {
            final int comparison = transactionId.compareTo(lastTransactionId);
            if (comparison < 0) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "Merged SORTIN output descends at total row %d while selecting %s row %d; "
                                + "app/jcl/COMBTRAN.jcl:L30 requires TRAN-ID ascending",
                        Long.valueOf(recordsRead + 1L),
                        selected.source.datasetName,
                        Long.valueOf(selected.emittedCount + 1L)),
                        "SORT FIELDS=(TRAN-ID,A)",
                        LOGICAL_FILE);
            }
            if (comparison == 0) {
                LOG.warn(
                        "{} duplicate TRAN-ID retained for the load; previousSource={} previousRow={} "
                                + "currentSource={} currentRow={}",
                        LOGICAL_FILE,
                        lastEmittedSource,
                        Long.valueOf(lastEmittedSourceRow),
                        selected.source,
                        Long.valueOf(selected.emittedCount + 1L));
            }
        }
    }

    /**
     * Dispatches one source read to its configured substrate.
     *
     * @param cursor source to advance
     * @return raw two-character file status
     * @throws IOException if an object stream fails
     */
    private String readNextRecord(final SourceCursor cursor) throws IOException {
        if (inputSource == InputSource.REPOSITORY) {
            if (cursor.source == ConcatenatedSource.BACKUP) {
                return readNextRecordFromRepository(cursor);
            }
            return readNextRecordFromObject(cursor);
        }
        return readNextRecordFromObject(cursor);
    }

    /**
     * Advances the ordered keyset browse by one transaction.
     *
     * @param cursor repository cursor
     * @return success, end-of-file, or physical-I/O status
     */
    private String readNextRecordFromRepository(final SourceCursor cursor) {
        while (cursor.pageBufferIndex >= cursor.pageBuffer.size()) {
            if (!cursor.morePagesAvailable) {
                cursor.pendingRecord = null;
                return STATUS_END_OF_FILE;
            }

            final Slice<Transaction> page = cursor.pageKeyTransactionId == null
                    ? transactionRepository
                            .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                                    LOW_VALUES_TRANSACTION_ID,
                                    PageRequest.of(0, pageSize))
                    : transactionRepository
                            .findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                                    cursor.pageKeyTransactionId,
                                    PageRequest.of(0, pageSize));
            cursor.pageBuffer = page.getContent();
            cursor.pageBufferIndex = 0;
            cursor.morePagesAvailable = page.hasNext();

            if (cursor.pageBuffer.isEmpty()) {
                cursor.pendingRecord = null;
                return STATUS_END_OF_FILE;
            }

            final Transaction highest = cursor.pageBuffer.get(cursor.pageBuffer.size() - 1);
            if (highest == null || highest.getTransactionId() == null) {
                cursor.pendingRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            cursor.pageKeyTransactionId = highest.getTransactionId();
        }

        final Transaction next = cursor.pageBuffer.get(cursor.pageBufferIndex);
        cursor.pageBufferIndex++;
        if (next == null) {
            cursor.pendingRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        final long rowNumber = cursor.fetchedCount + 1L;
        final String transactionId = requireTransactionId(
                next,
                cursor.source,
                rowNumber);
        requireNonDescending(
                cursor.source,
                cursor.previousTransactionId,
                transactionId,
                rowNumber);
        cursor.previousTransactionId = transactionId;
        cursor.fetchedCount = rowNumber;
        cursor.pendingRecord = next;
        return STATUS_SUCCESS;
    }

    /**
     * Reads, decodes, and order-validates one fixed-width object record.
     *
     * @param cursor open object cursor
     * @return success or end-of-file status
     * @throws IOException if the stream fails
     */
    private String readNextRecordFromObject(final SourceCursor cursor) throws IOException {
        final String image = readFixedWidthImage(cursor);
        if (image == null) {
            cursor.pendingRecord = null;
            return STATUS_END_OF_FILE;
        }

        final long rowNumber = cursor.fetchedCount + 1L;
        final Transaction decoded = decodeRecord(
                image,
                cursor.source,
                rowNumber);
        final String transactionId = requireTransactionId(
                decoded,
                cursor.source,
                rowNumber);
        requireNonDescending(
                cursor.source,
                cursor.previousTransactionId,
                transactionId,
                rowNumber);
        cursor.previousTransactionId = transactionId;
        cursor.fetchedCount = rowNumber;
        cursor.pendingRecord = decoded;
        return STATUS_SUCCESS;
    }

    /**
     * Reads exactly one 350-character image and consumes an optional terminator.
     *
     * @param cursor open object cursor
     * @return exact image, or {@code null} at a record-boundary end of stream
     * @throws IOException if the stream fails
     */
    private String readFixedWidthImage(final SourceCursor cursor) throws IOException {
        if (cursor.recordStream == null || cursor.recordBuffer == null) {
            throw new FileAccessException(String.format(Locale.ROOT,
                    "%s attempted to read %s without an open object stream",
                    LOGICAL_FILE,
                    cursor.source.datasetName),
                    STATUS_FILE_UNAVAILABLE,
                    cursor.source.displayName,
                    OPERATION_READ);
        }

        int filled = 0;
        while (filled < RECORD_LENGTH) {
            final int read = cursor.recordStream.read(
                    cursor.recordBuffer,
                    filled,
                    RECORD_LENGTH - filled);
            if (read == END_OF_STREAM) {
                break;
            }
            if (read == 0) {
                final int single = cursor.recordStream.read();
                if (single == END_OF_STREAM) {
                    break;
                }
                cursor.recordBuffer[filled] = (char) single;
                filled++;
            } else {
                filled += read;
            }
        }

        if (filled == 0) {
            return null;
        }
        if (filled != RECORD_LENGTH) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d has observed length %d; "
                            + "app/cpy/CVTRA05Y.cpy:L2 requires %d bytes",
                    LOGICAL_FILE,
                    cursor.source.datasetName,
                    Long.valueOf(cursor.fetchedCount + 1L),
                    Integer.valueOf(filled),
                    Integer.valueOf(RECORD_LENGTH)),
                    "CVTRA05Y RECLN",
                    cursor.source.datasetName);
        }

        consumeRecordTerminator(cursor);
        return new String(cursor.recordBuffer, 0, RECORD_LENGTH);
    }

    /**
     * Consumes an optional LF, CRLF, or CR without consuming the next record's first byte.
     *
     * @param cursor open object cursor
     * @throws IOException if mark, read, or reset fails
     */
    private static void consumeRecordTerminator(final SourceCursor cursor) throws IOException {
        if (cursor.recordStream == null) {
            return;
        }

        cursor.recordStream.mark(2);
        final int first = cursor.recordStream.read();
        if (first == END_OF_STREAM || first == LINE_FEED) {
            return;
        }
        if (first == CARRIAGE_RETURN) {
            cursor.recordStream.mark(1);
            final int second = cursor.recordStream.read();
            if (second != END_OF_STREAM && second != LINE_FEED) {
                cursor.recordStream.reset();
            }
            return;
        }
        cursor.recordStream.reset();
    }

    /**
     * Decodes one exact {@code CVTRA05Y} image without trimming any text field.
     *
     * @param image 350-character record image
     * @param source source carrying the image
     * @param rowNumber one-based source row
     * @return validated transaction entity
     */
    private static Transaction decodeRecord(
            final String image,
            final ConcatenatedSource source,
            final long rowNumber) {
        requireExactRecordLength(image, source, rowNumber);
        return new Transaction(
                fixedWidthField(
                        image,
                        TRANSACTION_ID_START,
                        TRANSACTION_ID_END,
                        "TRAN-ID",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        TYPE_CODE_START,
                        TYPE_CODE_END,
                        "TRAN-TYPE-CD",
                        source,
                        rowNumber),
                unsignedInteger(
                        fixedWidthField(
                                image,
                                CATEGORY_CODE_START,
                                CATEGORY_CODE_END,
                                "TRAN-CAT-CD",
                                source,
                                rowNumber),
                        "TRAN-CAT-CD",
                        CATEGORY_CODE_START,
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        TRANSACTION_SOURCE_START,
                        TRANSACTION_SOURCE_END,
                        "TRAN-SOURCE",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        DESCRIPTION_START,
                        DESCRIPTION_END,
                        "TRAN-DESC",
                        source,
                        rowNumber),
                TransactionBackupReader.decodeSignedTransactionAmount(
                        fixedWidthField(
                                image,
                                AMOUNT_START,
                                AMOUNT_END,
                                "TRAN-AMT",
                                source,
                                rowNumber),
                        rowNumber),
                unsignedLong(
                        fixedWidthField(
                                image,
                                MERCHANT_ID_START,
                                MERCHANT_ID_END,
                                "TRAN-MERCHANT-ID",
                                source,
                                rowNumber),
                        "TRAN-MERCHANT-ID",
                        MERCHANT_ID_START,
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        MERCHANT_NAME_START,
                        MERCHANT_NAME_END,
                        "TRAN-MERCHANT-NAME",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        MERCHANT_CITY_START,
                        MERCHANT_CITY_END,
                        "TRAN-MERCHANT-CITY",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        MERCHANT_ZIP_START,
                        MERCHANT_ZIP_END,
                        "TRAN-MERCHANT-ZIP",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        CARD_NUMBER_START,
                        CARD_NUMBER_END,
                        "TRAN-CARD-NUM",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        ORIG_TS_START,
                        ORIG_TS_END,
                        "TRAN-ORIG-TS",
                        source,
                        rowNumber),
                fixedWidthField(
                        image,
                        PROC_TS_START,
                        PROC_TS_END,
                        "TRAN-PROC-TS",
                        source,
                        rowNumber));
    }

    /**
     * Extracts one one-based inclusive copybook field after validating all bounds.
     *
     * @param image exact record image
     * @param startOneBased first field byte
     * @param endOneBased last field byte
     * @param cobolField copybook field name
     * @param source source carrying the image
     * @param rowNumber one-based source row
     * @return field text with every trailing space preserved
     */
    private static String fixedWidthField(
            final String image,
            final int startOneBased,
            final int endOneBased,
            final String cobolField,
            final ConcatenatedSource source,
            final long rowNumber) {
        if (image == null) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d has no record image for field %s",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    cobolField),
                    cobolField,
                    source.datasetName);
        }
        if (startOneBased < 1
                || endOneBased < startOneBased
                || endOneBased > RECORD_LENGTH) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid one-based CVTRA05Y offsets %d-%d for %s",
                    Integer.valueOf(startOneBased),
                    Integer.valueOf(endOneBased),
                    cobolField));
        }

        final int observedLength = image.length();
        if (observedLength != RECORD_LENGTH || endOneBased > observedLength) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d has observed length %d, so field %s at bytes %d-%d cannot be read; "
                            + "app/cpy/CVTRA05Y.cpy:L2 requires %d",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    Integer.valueOf(observedLength),
                    cobolField,
                    Integer.valueOf(startOneBased),
                    Integer.valueOf(endOneBased),
                    Integer.valueOf(RECORD_LENGTH)),
                    cobolField,
                    source.datasetName);
        }
        return image.substring(startOneBased - 1, endOneBased);
    }

    /**
     * Rejects any image whose observed length differs from {@value #RECORD_LENGTH}.
     *
     * @param image candidate image
     * @param source source carrying the image
     * @param rowNumber one-based source row
     */
    private static void requireExactRecordLength(
            final String image,
            final ConcatenatedSource source,
            final long rowNumber) {
        final int observedLength = image == null ? 0 : image.length();
        if (image == null || observedLength != RECORD_LENGTH) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d has observed length %d; expected %d from "
                            + "app/cpy/CVTRA05Y.cpy:L2",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    Integer.valueOf(observedLength),
                    Integer.valueOf(RECORD_LENGTH)),
                    "CVTRA05Y RECLN",
                    source.datasetName);
        }
    }

    /**
     * Converts a validated unsigned display field to an integer.
     *
     * @param field fixed-width field text
     * @param cobolField copybook field name
     * @param startOneBased first field byte
     * @param source source carrying the field
     * @param rowNumber one-based source row
     * @return decoded integer
     */
    private static Integer unsignedInteger(
            final String field,
            final String cobolField,
            final int startOneBased,
            final ConcatenatedSource source,
            final long rowNumber) {
        requireDigits(
                field,
                cobolField,
                startOneBased,
                source,
                rowNumber);
        try {
            return Integer.valueOf(field);
        } catch (NumberFormatException failure) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d field %s is outside the Java integer range",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    cobolField),
                    cobolField,
                    source.datasetName,
                    failure);
        }
    }

    /**
     * Converts a validated unsigned display field to a long.
     *
     * @param field fixed-width field text
     * @param cobolField copybook field name
     * @param startOneBased first field byte
     * @param source source carrying the field
     * @param rowNumber one-based source row
     * @return decoded long
     */
    private static Long unsignedLong(
            final String field,
            final String cobolField,
            final int startOneBased,
            final ConcatenatedSource source,
            final long rowNumber) {
        requireDigits(
                field,
                cobolField,
                startOneBased,
                source,
                rowNumber);
        try {
            return Long.valueOf(field);
        } catch (NumberFormatException failure) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d field %s is outside the Java long range",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    cobolField),
                    cobolField,
                    source.datasetName,
                    failure);
        }
    }

    /**
     * Validates a {@code PIC 9(n)} image one character at a time.
     *
     * @param field fixed-width field text
     * @param cobolField copybook field name
     * @param startOneBased first field byte
     * @param source source carrying the field
     * @param rowNumber one-based source row
     */
    private static void requireDigits(
            final String field,
            final String cobolField,
            final int startOneBased,
            final ConcatenatedSource source,
            final long rowNumber) {
        for (int index = 0; index < field.length(); index++) {
            final char value = field.charAt(index);
            if (value < '0' || value > '9') {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s source %s row %d field %s has a non-digit at byte %d; "
                                + "the record content is not reported",
                        LOGICAL_FILE,
                        source.datasetName,
                        Long.valueOf(rowNumber),
                        cobolField,
                        Integer.valueOf(startOneBased + index)),
                        cobolField,
                        source.datasetName);
            }
        }
    }

    /**
     * Returns a non-null identifier of the exact copybook width.
     *
     * @param transaction decoded or repository transaction
     * @param source source carrying the transaction
     * @param rowNumber one-based source row
     * @return exact 16-character identifier
     */
    private static String requireTransactionId(
            final Transaction transaction,
            final ConcatenatedSource source,
            final long rowNumber) {
        if (transaction == null || transaction.getTransactionId() == null) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d has no TRAN-ID",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber)),
                    "TRAN-ID",
                    source.datasetName);
        }
        final String transactionId = transaction.getTransactionId();
        if (transactionId.length() != TRANSACTION_ID_END) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s row %d TRAN-ID has observed length %d; expected %d",
                    LOGICAL_FILE,
                    source.datasetName,
                    Long.valueOf(rowNumber),
                    Integer.valueOf(transactionId.length()),
                    Integer.valueOf(TRANSACTION_ID_END)),
                    "TRAN-ID",
                    source.datasetName);
        }
        return transactionId;
    }

    /**
     * Enforces the ordered-merge precondition for one source.
     *
     * @param source source being validated
     * @param previousTransactionId prior fetched identifier, or {@code null}
     * @param currentTransactionId current fetched identifier
     * @param rowNumber current one-based source row
     */
    private static void requireNonDescending(
            final ConcatenatedSource source,
            final String previousTransactionId,
            final String currentTransactionId,
            final long rowNumber) {
        if (previousTransactionId != null
                && currentTransactionId.compareTo(previousTransactionId) < 0) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s source %s (SORTIN DD %d) descends between rows %d and %d; ordered merge requires "
                            + "each source to be non-decreasing by TRAN-ID",
                    LOGICAL_FILE,
                    source.datasetName,
                    Integer.valueOf(source.ddOrdinal),
                    Long.valueOf(rowNumber - 1L),
                    Long.valueOf(rowNumber)),
                    "SORT FIELDS=(TRAN-ID,A)",
                    source.datasetName);
        }
    }

    /**
     * Converts a non-success status to the required typed failure after rendering it.
     *
     * @param source source reporting the status
     * @param status raw two-character status
     * @param operation attempted operation
     * @param cause underlying failure, or {@code null}
     * @param message contextual diagnostic
     * @return exception to throw
     */
    private RuntimeException statusException(
            final ConcatenatedSource source,
            final String status,
            final String operation,
            final Throwable cause,
            final String message) {
        final String renderedStatus = fileStatusMapper.displayIoStatus(status);
        LOG.error("{}; source={} operation={}", message, source.datasetName, operation);
        LOG.error(renderedStatus);

        if (STATUS_DUPLICATE_KEY.equals(status)) {
            return new DuplicateRecordException(String.format(Locale.ROOT,
                    "%s reported duplicate-key status while reading %s; equal identifiers are intentionally "
                            + "retained here, so a source adapter must not manufacture this status",
                    LOGICAL_FILE,
                    source.datasetName),
                    cause);
        }

        final Throwable contextualCause;
        if (STATUS_RECORD_NOT_FOUND.equals(status)
                || STATUS_FILE_UNAVAILABLE.equals(status)
                || status.startsWith(String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE))) {
            contextualCause = new FileAccessException(
                    message,
                    status,
                    source.displayName,
                    operation,
                    cause);
        } else {
            contextualCause = cause;
        }

        LOG.error(ABENDING_PROGRAM_MESSAGE);
        return new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                operation,
                String.format(Locale.ROOT,
                        "%s (%s, %s, %s)",
                        message,
                        source.datasetName,
                        operation,
                        renderedStatus),
                contextualCause);
    }

    /**
     * Confines an untrusted carried or listed object key to one generation namespace.
     *
     * @param key candidate key
     * @param generationPrefix required prefix
     * @param source source whose namespace is required
     * @param origin diagnostic origin of the value
     * @return unchanged validated key
     */
    private String requireKeyWithinGeneration(
            final String key,
            final String generationPrefix,
            final ConcatenatedSource source,
            final String origin) {
        String rejection = null;
        if (key == null || key.isBlank()) {
            rejection = "is blank";
        } else if (key.length() > MAX_OBJECT_KEY_LENGTH) {
            rejection = "exceeds the object-key length limit";
        } else if (!isPrintableAscii(key)) {
            rejection = "contains a character outside printable ASCII";
        } else if (!key.startsWith(generationPrefix)) {
            rejection = "does not begin with the configured generation prefix";
        } else if (key.contains("..") || key.contains("//")) {
            rejection = "contains a disallowed '..' or '//' segment";
        } else if (key.endsWith(KEY_SEPARATOR)) {
            rejection = "names a prefix rather than an object";
        }

        if (rejection == null) {
            return key;
        }
        throw statusException(
                source,
                STATUS_FILE_UNAVAILABLE,
                OPERATION_OPEN,
                null,
                String.format(Locale.ROOT,
                        "ERROR OPENING %s: the key from %s %s",
                        source.displayName,
                        origin,
                        rejection));
    }

    /**
     * Parses the input selector deterministically with {@link Locale#ROOT}.
     *
     * @param configured configured selector
     * @return validated input source
     */
    private static InputSource requireInputSource(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be 'repository' or 'object-storage' and must not be blank",
                    PROPERTY_SOURCE));
        }

        final String normalised = configured
                .strip()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        try {
            return InputSource.valueOf(normalised);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be 'repository' or 'object-storage'; the supplied value names neither",
                    PROPERTY_SOURCE),
                    failure);
        }
    }

    /**
     * Rejects a repository window that cannot advance.
     *
     * @param configured configured page size
     * @return positive page size
     */
    private static int requirePositivePageSize(final int configured) {
        if (configured < 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be at least 1 but was %d",
                    PROPERTY_PAGE_SIZE,
                    Integer.valueOf(configured)));
        }
        return configured;
    }

    /**
     * Normalises a generation prefix to one trailing separator.
     *
     * @param configured configured prefix
     * @param property property named in diagnostics
     * @param defaultValue documented fallback
     * @return validated normalised prefix
     */
    private static String requireGenerationPrefix(
            final String configured,
            final String property,
            final String defaultValue) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must not be blank; its documented default is '%s'",
                    property,
                    defaultValue));
        }

        final String stripped = configured.strip();
        if (!isPrintableAscii(stripped)
                || stripped.contains("..")
                || stripped.contains("//")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be a printable object-key prefix without '..' or '//' segments",
                    property));
        }
        return stripped.endsWith(KEY_SEPARATOR)
                ? stripped
                : stripped + KEY_SEPARATOR;
    }

    /**
     * Validates the bucket required by the selected source.
     *
     * @param configured configured bucket
     * @param selected selected input substrate
     * @return stripped bucket, possibly empty on a repository-only invocation
     */
    private static String requireOutputBucket(
            final String configured,
            final InputSource selected) {
        final String normalised = configured == null ? "" : configured.strip();
        if (selected == InputSource.OBJECT_STORAGE && normalised.isEmpty()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be configured when %s is 'object-storage'; set %s",
                    PROPERTY_OUTPUT_BUCKET,
                    PROPERTY_SOURCE,
                    ENV_OUTPUT_BUCKET));
        }
        if (!normalised.isEmpty()
                && (!isPrintableAscii(normalised) || normalised.indexOf('/') >= 0)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be a printable bucket name and must not contain '/'",
                    PROPERTY_OUTPUT_BUCKET));
        }
        return normalised;
    }

    /**
     * Tests whether a value is safe for an object request and single-line diagnostic.
     *
     * @param value value to inspect
     * @return {@code true} when every character is printable ASCII
     */
    private static boolean isPrintableAscii(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (current < ' ' || current > '~') {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts the exact code of a non-family file status.
     *
     * @param status exact status constant
     * @return two-character code
     */
    private static String requireExactCode(final FileStatus status) {
        return status.code().orElseThrow(() -> new IllegalStateException(String.format(Locale.ROOT,
                "FileStatus.%s does not expose an exact status code",
                status.name())));
    }

    /**
     * Reads and validates a non-negative row counter from restart state.
     *
     * @param executionContext restart context
     * @param key counter key
     * @return non-negative counter
     */
    private static long requireNonNegativeContextLong(
            final ExecutionContext executionContext,
            final String key) {
        final long value;
        try {
            value = executionContext.getLong(key, 0L);
        } catch (RuntimeException failure) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Execution-context entry '%s' is not a valid row counter",
                    key),
                    key,
                    LOGICAL_FILE,
                    failure);
        }
        if (value < 0L) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Execution-context entry '%s' is negative",
                    key),
                    key,
                    LOGICAL_FILE);
        }
        return value;
    }

    /**
     * Reads an optional string without trimming its fixed-width content.
     *
     * @param executionContext restart context
     * @param key value key
     * @return exact stored value, or {@code null} when absent
     */
    private static String getOptionalContextString(
            final ExecutionContext executionContext,
            final String key) {
        if (!executionContext.containsKey(key)) {
            return null;
        }
        try {
            final String value = executionContext.getString(key, "");
            return value.isEmpty() ? null : value;
        } catch (RuntimeException failure) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Execution-context entry '%s' is not a string",
                    key),
                    key,
                    LOGICAL_FILE,
                    failure);
        }
    }

    /**
     * Stores a present string or removes a stale optional entry.
     *
     * @param executionContext context to mutate
     * @param key value key
     * @param value exact value, or {@code null}
     */
    private static void putOptionalString(
            final ExecutionContext executionContext,
            final String key,
            final String value) {
        if (value == null) {
            executionContext.remove(key);
        } else {
            executionContext.putString(key, value);
        }
    }

    /**
     * Parses the checkpointed active source.
     *
     * @param executionContext restart context
     * @return valid concatenated source
     */
    private static ConcatenatedSource requireContextSource(
            final ExecutionContext executionContext) {
        final String configured;
        try {
            configured = executionContext.getString(
                    CONTEXT_KEY_ACTIVE_SOURCE,
                    ConcatenatedSource.BACKUP.name());
        } catch (RuntimeException failure) {
            throw new DataIntegrityException(
                    "Combined transaction active-source checkpoint is not a string",
                    CONTEXT_KEY_ACTIVE_SOURCE,
                    LOGICAL_FILE,
                    failure);
        }

        try {
            return ConcatenatedSource.valueOf(configured);
        } catch (IllegalArgumentException failure) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "Combined transaction active-source checkpoint '%s' is not BACKUP or SYSTRAN",
                    configured),
                    CONTEXT_KEY_ACTIVE_SOURCE,
                    LOGICAL_FILE,
                    failure);
        }
    }

    /**
     * Ensures each positive source count has one corresponding identifier.
     *
     * @param cursor restored cursor
     * @param contextKey identifier key named in an error
     */
    private static void requireRestartIdentifier(
            final SourceCursor cursor,
            final String contextKey) {
        if (cursor.emittedCount > 0L && cursor.lastEmittedTransactionId == null) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s restart has %d emitted %s rows but no source TRAN-ID checkpoint",
                    LOGICAL_FILE,
                    Long.valueOf(cursor.emittedCount),
                    cursor.source.datasetName),
                    contextKey,
                    cursor.source.datasetName);
        }
        if (cursor.emittedCount == 0L && cursor.lastEmittedTransactionId != null) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s restart has a %s TRAN-ID checkpoint but zero emitted rows for that source",
                    LOGICAL_FILE,
                    cursor.source.datasetName),
                    contextKey,
                    cursor.source.datasetName);
        }
    }

    /**
     * Returns the source-specific last-identifier checkpoint key.
     *
     * @param source source whose key is needed
     * @return context key
     */
    private static String sourceLastIdentifierContextKey(
            final ConcatenatedSource source) {
        return source == ConcatenatedSource.BACKUP
                ? CONTEXT_KEY_BACKUP_LAST_TRANSACTION_ID
                : CONTEXT_KEY_SYSTRAN_LAST_TRANSACTION_ID;
    }

    /**
     * Produces a safe structured-log value for an optional resolved key.
     *
     * @param resolvedKey concrete key, or {@code null}
     * @param absentMarker marker used when no key exists
     * @return concrete key or marker
     */
    private static String displayGenerationKey(
            final String resolvedKey,
            final String absentMarker) {
        return resolvedKey == null ? absentMarker : resolvedKey;
    }
}
