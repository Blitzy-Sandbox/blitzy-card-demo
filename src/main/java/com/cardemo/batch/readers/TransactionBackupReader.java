/*
 * ******************************************************************
 * Program     : TransactionBackupReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (GDG generation, fixed-width)
 * Function    : Read the TRANSACT.BKUP generation created by the backup
 *               REPRO step, decoding the 350-byte CVTRA05Y layout with
 *               position-aware zoned-decimal overpunch decoding. Canonical
 *               owner of the CVTRA05Y decoder.
 * Source      : app/proc/TRANREPT.prc:L21-L31 (STEP01R backup REPRO)
 *               app/proc/REPROC.prc:L21,L27-L28 (IDCAMS invocation)
 *               app/ctl/REPROCT.ctl:L15 (REPRO control card)
 *               app/cpy/CVTRA05Y.cpy (350-byte TRAN-RECORD)
 *               app/catlg/LISTCAT.txt:L3593 (KEYLEN 16 / AVGLRECL 350)
 *               No COBOL program exists for this step.
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
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Sequential reader for the {@code AWS.M2.CARDDEMO.TRANSACT.BKUP} generation that the transaction-report
 * job stream backs up before it filters and reports, and the <b>canonical owner of the only position-aware
 * zoned-decimal decoder for the {@code app/cpy/CVTRA05Y.cpy} record layout</b>.
 *
 * <h2>What it does</h2>
 * Hands one backed-up transaction to the step at a time, in key order, and answers {@code null} when the
 * generation is exhausted. It supplies the ordered stream and nothing else: it does not sort, does not
 * filter by date, does not aggregate and does not write.
 *
 * <p><b>There is no COBOL program for this step, and that is the single most important fact about it.</b>
 * The backup is three JCL members deep and stops at a utility control card, so the JCL, the procedure and
 * the control statement <i>are</i> the source of truth. All three links are cited because no one of them is
 * sufficient:
 * <ol>
 *   <li><b>{@code app/proc/TRANREPT.prc:L21-L31}</b>, {@code STEP01R}. {@code :L21} is
 *       {@code //STEP01R EXEC PROC=REPROC,} with {@code :L22} supplying
 *       {@code CNTLLIB=AWS.M2.CARDDEMO.CNTL}. {@code :L24-L25} override the input as
 *       {@code //PRC001.FILEIN DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, and
 *       {@code :L27-L31} override the output as {@code //PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE),}
 *       {@code UNIT=SYSDA, DCB=(LRECL=350,RECFM=FB,BLKSIZE=0), SPACE=(CYL,(1,1),RLSE),}
 *       {@code DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}.</li>
 *   <li><b>{@code app/proc/REPROC.prc}</b>, the invoked procedure. {@code :L21} is
 *       {@code //PRC001 EXEC PGM=IDCAMS} - a utility, not a compiled program. {@code :L23-L24} and
 *       {@code :L25-L26} declare {@code FILEIN} and {@code FILEOUT} as
 *       {@code DISP=SHR, DSN=NULLFILE}; <b>both are placeholders that the caller overrides</b>, which is
 *       why the record geometry has to be read from {@code STEP01R} and not from here. {@code :L27-L28}
 *       point {@code SYSIN} at {@code DISP=SHR, DSN=&CNTLLIB(REPROCT)} and {@code :L29} is
 *       {@code // PEND}.</li>
 *   <li><b>{@code app/ctl/REPROCT.ctl:L15}</b>, the whole of the logic:
 *       {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}. It is the file's only non-comment line -
 *       {@code :L1-L14} are the Apache notice in {@code /*}&#8230;{@code *}{@code /} form - so the backup
 *       is a straight record-for-record copy with no selection, no reformatting and no arithmetic.</li>
 * </ol>
 * The step therefore copies {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} to
 * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} at {@code LRECL=350, RECFM=FB, BLKSIZE=0}, and this class reads
 * that generation back.
 *
 * <p><b>{@code RECFM=FB} here is not {@code RECFM=F} elsewhere, and the difference is deliberate.</b>
 * {@code app/proc/TRANREPT.prc:L29} specifies {@code RECFM=FB} - fixed <i>blocked</i>, with
 * {@code BLKSIZE=0} letting the system pick the block size - whereas the 430-byte reject generation of the
 * posting job specifies {@code RECFM=F} at {@code app/jcl/POSTTRAN.jcl:L36}, fixed and unblocked. Blocking
 * is a physical grouping of records on the volume and changes no record's bytes, so it has no counterpart
 * in an object stream; the asymmetry is recorded rather than homogenised, and the record length is what
 * carries across. Cite both record formats wherever either is quoted and carry only the record length into
 * Java; do not "harmonise" the two declarations, because the difference is
 * in the source and a reviewer comparing them needs to find it recorded rather than erased.
 *
 * <p><b>The member declares an internal procedure name that is not its own.</b>
 * {@code app/proc/TRANREPT.prc:L1} reads {@code //REPROC PROC} - identical to
 * {@code app/proc/REPROC.prc:L1} - although the member is named {@code TRANREPT}. A z/OS
 * {@code EXEC PROC=} resolves the <b>member</b> name, not the internal one, and
 * {@code EXEC PROC=TRANREPT} occurs exactly once in the corpus, inside the embedded job deck of the report
 * submission program at {@code app/cbl/CORPT00C.cbl:L94}. The discrepancy is documented here and neither name
 * is changed - the member name is what an invocation resolves and the internal
 * name is what the member declares, so "correcting" either would break a citation and change nothing that
 * executes. {@value #ABEND_CULPRIT} is used as the abend culprit for exactly this reason.
 *
 * <h3>The 350-byte record layout, from {@code app/cpy/CVTRA05Y.cpy}</h3>
 * {@code :L2} declares {@code RECLN = 350} and {@code :L4} opens {@code 01 TRAN-RECORD}. Offsets below are
 * <b>one-based and inclusive</b> and the widths sum to exactly 350. The geometry is byte-for-byte that of
 * {@code app/cpy/CVTRA06Y.cpy}, which {@link DailyTransactionReader} owns, differing only in the field-name
 * prefix; the two decoders are colocated with their own readers rather than shared, which is the
 * repository's one-codec-per-record-layout convention.
 * <ul>
 *   <li>{@code TRAN-ID PIC X(16)} ({@code :L5}), bytes 1-16, to {@code transactionId}. The base cluster
 *       key: {@code app/catlg/LISTCAT.txt:L3593} records {@code KEYLEN 16} with {@code AVGLRECL 350} and
 *       {@code :L3594} records {@code RKP 0}, and {@code app/jcl/TRANFILE.jcl:L53-L54} declares
 *       {@code KEYS(16 0)} and {@code RECORDSIZE(350 350)}.</li>
 *   <li>{@code TRAN-TYPE-CD PIC X(02)} ({@code :L6}), bytes 17-18, to {@code typeCode}.</li>
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} ({@code :L7}), bytes 19-22, to {@code categoryCode}.</li>
 *   <li>{@code TRAN-SOURCE PIC X(10)} ({@code :L8}), bytes 23-32, to {@code transactionSource} as a plain
 *       ten-character string and <b>never as an enum</b>.</li>
 *   <li>{@code TRAN-DESC PIC X(100)} ({@code :L9}), bytes 33-132, to {@code description}.</li>
 *   <li><b>{@code TRAN-AMT PIC S9(09)V99} ({@code :L10}), bytes 133-143</b>, to {@code amount}. Eleven
 *       characters, no separate sign byte, trailing-sign overpunch on byte 143. <b>The only signed field in
 *       the layout, and the only place the decoder runs.</b></li>
 *   <li>{@code TRAN-MERCHANT-ID PIC 9(09)} ({@code :L11}), bytes 144-152, to {@code merchantId}.</li>
 *   <li>{@code TRAN-MERCHANT-NAME PIC X(50)} ({@code :L12}), bytes 153-202, to {@code merchantName}.</li>
 *   <li>{@code TRAN-MERCHANT-CITY PIC X(50)} ({@code :L13}), bytes 203-252, to {@code merchantCity}.</li>
 *   <li>{@code TRAN-MERCHANT-ZIP PIC X(10)} ({@code :L14}), bytes 253-262, to {@code merchantZip}.</li>
 *   <li><b>{@code TRAN-CARD-NUM PIC X(16)} ({@code :L15}), bytes 263-278</b>, to {@code cardNumber}. Never
 *       logged, never placed in an exception message and never written to the execution context.</li>
 *   <li>{@code TRAN-ORIG-TS PIC X(26)} ({@code :L16}), bytes 279-304, to {@code origTs} as 26 characters of
 *       text.</li>
 *   <li><b>{@code TRAN-PROC-TS PIC X(26)} ({@code :L17}), bytes 305-330</b>, to {@code procTs} as 26
 *       characters of text.</li>
 *   <li>{@code FILLER PIC X(20)} ({@code :L18}), bytes 331-350. Pads the record out to the catalogued 350
 *       bytes, carries no data, and is deliberately not modelled.</li>
 * </ul>
 *
 * <p><b>The offset map has three independent corroborations, which is why it can be relied on.</b> The
 * copybook widths are the first. The second is the sort deck of the step that consumes this generation:
 * {@code app/proc/TRANREPT.prc:L39-L40} declares {@code TRAN-CARD-NUM,263,16,ZD} and
 * {@code TRAN-PROC-DT,305,10,CH} - <b>one-based offsets</b> that land exactly on the card number and on the
 * first ten characters of the processing timestamp. The third is the alternate index:
 * {@code app/catlg/LISTCAT.txt:L3676} records {@code AXRKP 304}, which is <b>zero-based</b> and therefore
 * the same byte 305, with {@code KEYLEN 26} at {@code :L3674} matching the 26-character stamp. Whenever an
 * offset is cited in this file it is one-based unless it is quoted from {@code AXRKP}.
 *
 * <p><b>Timestamps are 26-byte text and are never parsed.</b> Both stamps are {@code PIC X(26)} and the
 * corpus has mutually incompatible producers for them, so no temporal type can represent the field without
 * choosing one producer and corrupting the others. Decisively, the filter that consumes this generation is
 * <b>lexical</b>: {@code app/proc/TRANREPT.prc:L45-L46} is
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)} over the
 * {@code CH} - character - symbol declared at {@code :L40}, compared against the character literals
 * {@code C'2022-01-01'} and {@code C'2022-07-06'} at {@code :L41-L42}. A temporal round trip would change
 * which rows that comparison admits, so the text form is load-bearing rather than incidental.
 * A blank stamp survives as exactly 26 spaces - not {@code null}, not empty, not trimmed and not an
 * epoch.
 *
 * <p><b>What this reader does not own.</b> The ascending card-number ordering of
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} ({@code app/proc/TRANREPT.prc:L44}) and the inclusive date filter
 * of {@code :L45-L46} belong to the planned {@code com.cardemo.batch.jobs.TransactionReportJob} and to
 * {@link com.cardemo.batch.processors.TransactionReportProcessor}, which is authored; the 133-byte report
 * line of {@code :L76} is emitted by that processor's own output path. This class supplies the stream they
 * consume. <b>No external sort process is spawned by anything in this file</b>: DFSORT becomes a
 * {@link java.util.Comparator} and {@code IDCAMS REPRO} becomes a bulk load, and neither invokes a shell.
 * There is no {@code Runtime.exec} and no {@code ProcessBuilder} anywhere here.
 *
 * <h3>Generation semantics, and the one thing that must not be got wrong</h3>
 * A relative generation reference becomes an object key under a monotonically increasing prefix over a
 * versioned bucket: a {@code (+1)} write creates a new object, and a {@code (0)} read takes the
 * lexicographically greatest existing key.
 *
 * <p><b>Within one job, a {@code (+1)} written by an earlier step is re-read as {@code (+1)} by a later
 * step.</b> {@code app/proc/TRANREPT.prc:L31} creates {@code TRANSACT.BKUP(+1)} in {@code STEP01R} and
 * {@code :L37} reads {@code TRANSACT.BKUP(+1)} as {@code SORTIN} in {@code STEP05R} - the same generation,
 * still spelled {@code (+1)}, because z/OS resolves a relative reference once per job and holds it. The
 * pattern repeats immediately: {@code :L53} creates {@code TRANSACT.DALY(+1)} and {@code :L64} reads it in
 * {@code STEP10R}, and {@code app/jcl/COMBTRAN.jcl:L43-L44} does the same again.
 *
 * <p><b>Therefore the resolved object key is carried forward through the execution context and is never
 * re-resolved as "latest" once a step is running.</b> {@link #open(ExecutionContext)} resolves it exactly
 * once, in a fixed precedence - the step's own restart checkpoint, then the key a prior step promoted into
 * the job execution context, then and only then a lexical-greatest listing for a standalone invocation -
 * and {@link #read()} never resolves anything. Re-resolving mid-job produces a reader that passes in
 * isolation and <b>races in the pipeline</b>, because a concurrent job's newer generation would be picked
 * up between two steps of this one and the report would describe a backup nobody took.
 *
 * <p><b>Record length is preserved byte-exactly at the object-storage boundary: 350 characters per
 * record.</b> No re-blocking, no re-encoding, no trimming and no padding.
 *
 * <p><b>Retention is documented intent and not enforced code, and one source conflict is resolved.</b>
 * {@code app/catlg/LISTCAT.txt:L3942} reports {@code GDG 7}, so seven generation bases exist:
 * {@code DALYREJS}, {@code SYSTRAN}, {@code TCATBALF.BKUP}, {@code TRANREPT}, {@code TRANSACT.BKUP},
 * {@code TRANSACT.COMBINED} and {@code TRANSACT.DALY}. Six declare {@code LIMIT(5)}, but the
 * {@code TRANREPT} base is declared twice and inconsistently: {@code app/jcl/DEFGDGB.jcl:L37} names it and
 * {@code :L38} declares {@code LIMIT(5)}, while {@code app/jcl/REPTFILE.jcl:L26} names it and {@code :L27}
 * declares {@code LIMIT(10)}. A single lifecycle value must be chosen, so <b>10 is used</b> - the larger,
 * so nothing the legacy system would have kept is discarded - and it is recorded at
 * {@code src/main/resources/application.yml} as {@code carddemo.aws.s3.gdg-retention-generations: 10}.
 * <b>This is the only legacy inconsistency the migration resolves</b>; every other one is preserved and
 * cited. The single value lives in configuration, where both locators and the choice are recorded together,
 * and no code acts on it - this class enforces no retention,
 * because object versioning supersedes generation counting and a reader that deleted generations would be a
 * writer.
 *
 * <p><b>Two constraints the surrounding evidence imposes.</b> The alternate-index path
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH} ({@code app/catlg/LISTCAT.txt:L3663}) has <b>no
 * {@code DEFINE FILE} in {@code app/csd/CARDDEMO.CSD}</b>, which is the evidence that the
 * processing-timestamp finder is batch-only rather than an online access path; only the base cluster
 * {@code TRANSACT} is defined to CICS, and the two {@code DEFINE FILE} entries that do name an
 * {@code AIX.PATH} dataset are {@code CARDAIX} and {@code CXACAIX}. The finder therefore stays out of the
 * online authorisation surface, because exposing it would invent an access path the CSD never defined. And
 * {@code app/jcl/TRANIDX.jcl:L25-L27} declares {@code KEYS(26 304)} with its path at {@code :L42-L44}, so it
 * defines the <b>{@code TRANSACT}</b> alternate index and not a card index - which is why it is cited here
 * for the transaction repository and nowhere for the card one. Neither index is used by this reader.
 *
 * <h2>How to run, build and test</h2>
 * The bean is {@code @StepScope}, so one instance exists per step execution and nothing runs at application
 * start: every profile sets {@code spring.batch.job.enabled: false}. The {@code Job} and {@code Step} that
 * drive it are declared by {@link com.cardemo.config.BatchConfig} and are launched by the planned
 * {@code com.cardemo.batch.jobs.TransactionReportJob} through the planned
 * {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}.
 *
 * <p>Build and static gates, from the repository root:
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. The compiler runs at
 * {@code release 25} with {@code -Xlint:all -Werror} and {@code failOnWarning}, and the documentation gate
 * runs {@code javadoc-no-fork} with {@code doclint=all}, {@code failOnWarnings=true} and
 * {@code show=private}, so every private member of this file is inside that gate. Compile only:
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true -DskipTests compile}. Docker Engine and
 * {@code docker compose} are available, so the pinned container is an equivalent that needs no host
 * toolchain: {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q
 * -DskipTests compile}.
 *
 * <p>Tests belong in {@code src/test/java/com/cardemo/unit/batch} for the decoder and the status rendering,
 * {@code src/test/java/com/cardemo/integration/batch} for the step against a Testcontainers PostgreSQL, and
 * {@code src/test/java/com/cardemo/integration/aws} for the generation resolution against a LocalStack
 * emulator. Those suites must assert three things in particular: the worked decodes
 * {@code 0000005047G} to {@code 504.77}, {@code 0000009190}<code>&#125;</code> to {@code -919.00} and
 * {@code 0000000678H} to {@code 67.88}, all at scale 2; that a key written by one step is the key this
 * reader opens in the next, rather than whatever listing happens to be greatest; and that a 350-character
 * image round-trips unchanged, with a blank {@code TRAN-PROC-TS} still 26 spaces. No test file is created by
 * this class.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 *   <li>{@value #PROPERTY_SOURCE} - the input selector, one of {@code repository} or
 *       {@code object-storage}, case-insensitive and accepting either a hyphen or an underscore.
 *       <b>Default {@code repository}.</b> See {@link InputSource}; both values are reachable and neither
 *       branch is dead code.</li>
 *   <li>{@value #PROPERTY_PAGE_SIZE} - rows per database round trip on the {@code repository} path,
 *       defaulting to {@value #DEFAULT_PAGE_SIZE}, which is the value the four sibling verification readers
 *       declare at {@code src/main/resources/application.yml}. A fetch size, not a pagination contract: the
 *       parity page sizes of 7, 10 and 10 live under {@code carddemo.pagination} and are unrelated.</li>
 *   <li>{@value #PROPERTY_GENERATION_PREFIX} - the key prefix under which {@code TRANSACT.BKUP} generations
 *       are written, declared at {@code src/main/resources/application.yml} as {@code gdg/transact-bkup}
 *       with a comment citing {@code app/proc/TRANREPT.prc:L21 STEP01R}. Bound here with
 *       {@value #DEFAULT_GENERATION_PREFIX} as its fallback so the class remains constructible in a unit
 *       test that loads no profile.</li>
 *   <li>{@value #PROPERTY_OUTPUT_BUCKET} - the versioned generation bucket, declared at
 *       {@code src/main/resources/application.yml} as {@code ${CARDDEMO_S3_BATCH_OUTPUT_BUCKET}} and
 *       provisioned by {@code localstack-init/init-aws.sh}. Bound with an empty default and required
 *       non-blank <b>only</b> when the {@code object-storage} path is selected, so the default path carries
 *       no cloud prerequisite.</li>
 *   <li>The job-execution-context entry named by {@value #CONTEXT_KEY_GENERATION_OBJECT_KEY}, injected
 *       through a <code>#&#123;jobExecutionContext[...]&#125;</code> expression - the concrete generation
 *       key a prior step promoted. Absent on a standalone invocation, which is the only case in which a
 *       lexical-greatest listing is performed.</li>
 *   <li>Record geometry is <b>not</b> configuration. {@value #RECORD_LENGTH} is a compile-time constant: a
 *       settable byte contract would let a deployment break parity by editing a profile.</li>
 *   <li>The charset is fixed in code at {@code ISO-8859-1} and passed explicitly at the one place bytes
 *       become characters. No platform default charset, locale or time zone is ever consulted, and
 *       {@link Locale#ROOT} is used for every case fold and every formatted message.</li>
 *   <li>Chunk size and the step's commit interval come from {@code com.cardemo.config.BatchConfig} and
 *       {@code carddemo.batch.chunk-size}; they are not this class's to set.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} and {@code spring.jpa.open-in-view: false} hold in
 *       every profile, so the mapping is verified against the Flyway schema and no lazy load escapes the
 *       step.</li>
 *   <li>The emulator endpoint override is declared in all four profiles - bound to a bare
 *       {@code ${AWS_ENDPOINT_URL}} with no default in the base, {@code test} and {@code prod} profiles, so an
 *       unset variable fails the context at startup, and defaulted to the LocalStack edge only in
 *       {@code application-local.yml} - so <b>no live-cloud path is structurally reachable</b> and no
 *       credential is handled here. An earlier revision of this item said the override existed only in the
 *       {@code local} and {@code test} profiles; that is withdrawn.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>End of data is not an error.</b> {@link #read()} answers {@code null}, which is the Spring Batch
 *       end-of-input signal and the exact analogue of file status {@code '10'} driving
 *       {@code MOVE 16 TO APPL-RESULT}, where {@code 88 APPL-EOF VALUE 16} is declared at
 *       {@code app/cbl/CBTRN02C.cbl:L144}. An empty generation is a successful run with a row count of
 *       zero, because {@code REPRO} of an empty cluster legitimately produces an empty generation.</li>
 *   <li><b>An absent generation is a failure, not an empty read.</b> It is reported as file status
 *       {@code '35'} and abends, because {@code app/proc/TRANREPT.prc} creates the generation at
 *       {@code :L27-L31} before reading it at {@code :L36-L37}: if it is missing, the pipeline ran out of
 *       order and reporting on stale data would be worse than failing. Run the backup step first, or invoke
 *       this reader standalone against a bucket that already holds a generation.</li>
 *   <li><b>Any other status abends.</b> A status that is neither {@code '00'} nor {@code '10'} renders
 *       {@code FILE STATUS IS: NNNN} followed by four characters - status {@code '23'} renders exactly
 *       {@code FILE STATUS IS: NNNN0023}, because the {@code NNNN} is part of the fixed 20-character
 *       literal and not a placeholder - and then throws {@link FatalProcessingException} with abend code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.</li>
 *   <li><b>A record length other than {@value #RECORD_LENGTH}</b> throws {@link DataIntegrityException}
 *       naming the row number, the observed length and the expected length - never the record content.</li>
 *   <li><b>An unrecognised terminal overpunch character</b> in bytes 133-143 throws
 *       {@link DataIntegrityException} naming the row number and the offending byte position, never the
 *       character and never the record.</li>
 *   <li><b>Reading a different generation than the step before it wrote</b> means the object key was
 *       re-resolved instead of carried forward, and it is the single most likely defect in this class.
 *       <i>How to tell, without the key appearing in a log line:</i> generation resolution emits exactly one
 *       of three mutually exclusive events - carried forward from the execution context, carried forward
 *       from a prior step, or resolved by the standalone {@code (0)} semantic - so the third of those
 *       appearing inside a pipeline run <b>is</b> the symptom. The key itself is checkpointed by
 *       {@link #update(ExecutionContext)} and is readable from the job repository, which is where a value
 *       that identifies a backup belongs. Confirm the writing step promotes
 *       {@value #CONTEXT_KEY_GENERATION_OBJECT_KEY} to the job execution context.</li>
 *   <li><b>Amounts appearing positive where negatives are expected</b> means a normalisation pass was
 *       wrongly introduced. There is none here: no absolute value, no sign stripping and no unconditional
 *       negation.</li>
 *   <li><b>A null, empty or trimmed 26-character timestamp</b> means it was wrongly normalised, and it will
 *       silently change which rows the downstream lexical date filter admits.</li>
 *   <li><b>Return code 4 is not this reader's outcome and not a failure of it.</b> It is set if and only if
 *       a reject count exceeds zero, which is the posting job's decision
 *       ({@code app/cbl/CBTRN02C.cbl:L229-L231}); this reader produces no rejects.</li>
 *   <li><b>Object-storage lifecycle behaviour cannot be demonstrated here and is <i>Not available</i>.</b>
 *       <i>Prerequisite:</i> a running LocalStack container with a versioned bucket and a lifecycle
 *       configuration applied, which is a validation-time dependency rather than a code one. The retention
 *       value is recorded as intent above; nothing in this file acts on it.</li>
 * </ul>
 *
 * <h2>Log hygiene: what this class will not name</h2>
 *
 * <p><b>Finding, severity Medium, resolved - CWE-532 and CWE-200.</b> Seven {@code INFO} emissions named
 * either a generation object key or the checkpointed transaction identifier. A generation key identifies one
 * concrete backup of the transaction cluster and the run that produced it; the checkpointed identifier is a
 * business record key. Both were published at a level enabled in every deployment, so both travelled
 * wherever the log stream travels - aggregated, retained and replicated well outside the boundary that
 * protects the row. Neither is named any longer:
 *
 * <ul>
 *   <li><b>Generation keys</b> are reduced to a {@linkplain #generationPresenceMarker(String) two-valued
 *       marker} on open and close, to the <em>name of the context entry</em> they were carried forward under,
 *       and - where the identity genuinely matters, in the standalone {@code (0)} resolution - to
 *       {@linkplain #resolvedGenerationCount an ordinal out of a count}. The key is passed to object storage
 *       and checkpointed to the job repository, and to nothing else.</li>
 *   <li><b>The checkpointed transaction identifier</b> is reported as {@code present} or {@code absent}. The
 *       record count is the restart position; the identifier only corroborates it.</li>
 *   <li><b>No record field</b> reaches any event: the length, overpunch and timestamp diagnostics name a row
 *       number and a byte position and never the content, as the troubleshooting entries above already
 *       state.</li>
 *   </ul>
 *
 * <p>What remains is the logical dataset name, the physical dataset name, the configured prefix, the
 * selected source, the row and record counts, the generation count, the rendered {@code FILE STATUS} and the
 * verbatim error literals - every one a property of the run rather than of anybody's account. The assertion
 * that holds this is {@code src/test/java/com/cardemo/unit/batch/BatchLogHygieneTest.java}.
 *
 * <p><b>Thread safety.</b> Not thread safe, and not required to be: the {@code step} scope gives each step
 * execution its own instance and Spring Batch drives a reader from one thread per step. Every mutable field
 * is an instance field, and <b>there is no static mutable state anywhere in this class</b> - the only static
 * members are the logger, immutable constants, and pure functions.
 *
 * @see DailyTransactionReader
 * @see TransactionRepository
 * @see FileStatusMapper
 */
@Component
@StepScope
public class TransactionBackupReader implements ItemStreamReader<Transaction> {

    /**
     * The class logger, and the only static member of this class that is neither a constant nor a pure
     * function.
     * <p>
     * SLF4J is the sole logging channel: there is no {@code System.out}, no {@code System.err} and no
     * {@code printStackTrace()} anywhere in this file.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionBackupReader.class);

    /**
     * Rows fetched per database round trip on the {@code repository} path when
     * {@value #PROPERTY_PAGE_SIZE} is absent.
     * <p>
     * 100 is the value every sibling reader in this package uses and the value
     * {@code src/main/resources/application.yml} declares for each of them, so this reader introduces no
     * second convention. It exceeds the 311 records the backed-up cluster held at the time of the catalogue
     * listing ({@code app/catlg/LISTCAT.txt} reports {@code REC-TOTAL 311} for
     * {@code TRANSACT.VSAM.KSDS.DATA}) by less than a factor of one, which is deliberate: a
     * catalogue-sized run takes four round trips and therefore exercises the page-refill boundary rather
     * than hiding it.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The key prefix under which {@code TRANSACT.BKUP} generations are written, used when
     * {@value #PROPERTY_GENERATION_PREFIX} is absent.
     * <p>
     * It matches the value {@code src/main/resources/application.yml} declares, whose own comment cites
     * {@code app/proc/TRANREPT.prc:L21 STEP01R} as its origin, so the fallback and the declared value
     * cannot disagree.
     */
    public static final String DEFAULT_GENERATION_PREFIX = "gdg/transact-bkup/";

    /**
     * The key separator that terminates a generation prefix.
     *
     * <p>Object storage has no directories; the slash is a naming convention. It matters here because a prefix
     * match is a plain string match, so the separator is what makes {@value #DEFAULT_GENERATION_PREFIX} name a
     * segment rather than merely a run of leading characters.
     */
    private static final String KEY_SEPARATOR = "/";

    /** The property that selects the input path, quoted in diagnostics so an operator can find it. */
    private static final String PROPERTY_SOURCE = "carddemo.batch.transaction-backup-reader.source";

    /** The property that sets the database round-trip size on the {@code repository} path. */
    private static final String PROPERTY_PAGE_SIZE = "carddemo.batch.transaction-backup-reader.page-size";

    /**
     * The property that names the generation key prefix, shared with every other holder of a
     * {@code TRANSACT.BKUP} reference so that exactly one authoritative value exists.
     */
    private static final String PROPERTY_GENERATION_PREFIX = "carddemo.aws.s3.gdg-prefixes.transact-bkup";

    /** The property that names the versioned generation bucket, declared once in {@code application.yml}. */
    private static final String PROPERTY_OUTPUT_BUCKET = "carddemo.aws.s3.batch-output-bucket";

    /** The environment variable behind {@link #PROPERTY_OUTPUT_BUCKET}, named in its failure message. */
    private static final String ENV_OUTPUT_BUCKET = "CARDDEMO_S3_BATCH_OUTPUT_BUCKET";

    /**
     * The DD name under which the backup generation is <b>read</b>, {@code app/proc/TRANREPT.prc:L36}
     * {@code //SORTIN DD DISP=SHR,} with {@code :L37} naming {@code TRANSACT.BKUP(+1)}.
     * <p>
     * This reader replaces that read, so this is the logical file name its statuses and exceptions carry.
     * The write side of the same generation is a different DD - {@code PRC001.FILEOUT} at {@code :L27} - and
     * there is no COBOL {@code FD} anywhere from which a single name could be taken, because the step runs
     * a utility rather than a program. Used in log events and exception context <b>in place of</b> the
     * record itself.
     */
    private static final String LOGICAL_FILE = "SORTIN";

    /**
     * The generation base whose objects this reader consumes,
     * {@code app/proc/TRANREPT.prc:L31} {@code DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}, with the relative
     * reference stripped because a resolved object key stands in its place.
     */
    private static final String DATASET_NAME = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /**
     * The abend culprit, which is a JCL member and not a program because <b>no COBOL program exists for
     * this step</b>.
     * <p>
     * {@code TRANREPT} is the member name that {@code EXEC PROC=TRANREPT} resolves - the only such
     * invocation in the corpus is at {@code app/cbl/CORPT00C.cbl:L94} - and it is exactly eight characters,
     * which is the width {@code ABEND-CULPRIT PIC X(08)} of {@code app/cpy/CSMSG02Y.cpy} allows. The
     * internal procedure name declared at {@code app/proc/TRANREPT.prc:L1} is {@code REPROC}, not
     * {@code TRANREPT}; the member name is used here because that is what the invocation resolves, and the
     * discrepancy is documented in the class comment rather than corrected.
     */
    private static final String ABEND_CULPRIT = "TRANREPT";

    /** The {@code OPEN} operation name, used only as exception and log context. */
    private static final String OPERATION_OPEN = "OPEN";

    /** The {@code READ} operation name, used only as exception and log context. */
    private static final String OPERATION_READ = "READ";

    /** The {@code CLOSE} operation name, used only as exception and log context. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /**
     * The open-failure diagnostic.
     * <p>
     * Composed rather than quoted, because <b>this step has no COBOL program and therefore no
     * {@code DISPLAY} literal to reproduce</b>. The corpus-wide shape is followed -
     * {@code 'ERROR OPENING &lt;file&gt;'} as at {@code app/cbl/CBTRN02C.cbl:L247} - with this reader's own
     * DD name substituted. Inventing a quotation would be worse than composing an honest message.
     */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING " + LOGICAL_FILE;

    /** The read-failure diagnostic, composed on the shape of {@code app/cbl/CBTRN02C.cbl:L363}. */
    private static final String ERROR_READING_MESSAGE = "ERROR READING " + LOGICAL_FILE + " FILE";

    /** The close-failure diagnostic, composed on the shape of {@code app/cbl/CBTRN02C.cbl:L593}. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING " + LOGICAL_FILE + " FILE";

    /** {@code DISPLAY 'ABENDING PROGRAM'}, the literal at {@code app/cbl/CBTRN02C.cbl:L708}. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    // ----------------------------------------------------------------------------------------------------
    // Record geometry, app/cpy/CVTRA05Y.cpy:L2 and :L5-L18. Compile-time constants and deliberately not
    // configuration: a settable byte contract would let a deployment break parity by editing a profile.
    // Offsets are ONE-BASED and INCLUSIVE, matching the copybook, the SYMNAMES deck at
    // app/proc/TRANREPT.prc:L39-L40 and every citation in this file. They are converted to Java's
    // zero-based half-open form in exactly one place, in fixedWidthField.
    // ----------------------------------------------------------------------------------------------------

    /**
     * The record length, 350 characters.
     * <p>
     * Corroborated four times over: {@code RECLN = 350} at {@code app/cpy/CVTRA05Y.cpy:L2} with the field
     * widths at {@code :L5-L18} summing to it; {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} on the generation
     * itself at {@code app/proc/TRANREPT.prc:L29}; {@code AVGLRECL 350} with {@code MAXLRECL 350} at
     * {@code app/catlg/LISTCAT.txt:L3593-L3594}; and {@code RECORDSIZE(350 350)} at
     * {@code app/jcl/TRANFILE.jcl:L54}.
     */
    private static final int RECORD_LENGTH = 350;

    /** {@code TRAN-ID PIC X(16)} start, byte 1 ({@code app/cpy/CVTRA05Y.cpy:L5}). */
    private static final int TRANSACTION_ID_START = 1;

    /** {@code TRAN-ID PIC X(16)} end, byte 16. */
    private static final int TRANSACTION_ID_END = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)} start, byte 17 ({@code app/cpy/CVTRA05Y.cpy:L6}). */
    private static final int TYPE_CODE_START = 17;

    /** {@code TRAN-TYPE-CD PIC X(02)} end, byte 18. */
    private static final int TYPE_CODE_END = 18;

    /** {@code TRAN-CAT-CD PIC 9(04)} start, byte 19 ({@code app/cpy/CVTRA05Y.cpy:L7}). */
    private static final int CATEGORY_CODE_START = 19;

    /** {@code TRAN-CAT-CD PIC 9(04)} end, byte 22. */
    private static final int CATEGORY_CODE_END = 22;

    /** {@code TRAN-SOURCE PIC X(10)} start, byte 23 ({@code app/cpy/CVTRA05Y.cpy:L8}). */
    private static final int TRANSACTION_SOURCE_START = 23;

    /** {@code TRAN-SOURCE PIC X(10)} end, byte 32. Plain text at full width, never an enum. */
    private static final int TRANSACTION_SOURCE_END = 32;

    /** {@code TRAN-DESC PIC X(100)} start, byte 33 ({@code app/cpy/CVTRA05Y.cpy:L9}). */
    private static final int DESCRIPTION_START = 33;

    /** {@code TRAN-DESC PIC X(100)} end, byte 132. */
    private static final int DESCRIPTION_END = 132;

    /**
     * {@code TRAN-AMT PIC S9(09)V99} start, byte 133 ({@code app/cpy/CVTRA05Y.cpy:L10}).
     * <p>
     * <b>The overpunch decoder is applied here and nowhere else.</b>
     */
    private static final int AMOUNT_START = 133;

    /**
     * {@code TRAN-AMT PIC S9(09)V99} end, byte 143, which is also the overpunch position.
     * <p>
     * Eleven characters with no separate sign byte: nine integer digits, two decimal digits, and the sign
     * overpunched onto the last of them.
     */
    private static final int AMOUNT_END = 143;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} start, byte 144 ({@code app/cpy/CVTRA05Y.cpy:L11}). */
    private static final int MERCHANT_ID_START = 144;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} end, byte 152. */
    private static final int MERCHANT_ID_END = 152;

    /**
     * {@code TRAN-MERCHANT-NAME PIC X(50)} start, byte 153 ({@code app/cpy/CVTRA05Y.cpy:L12}).
     * <p>
     * A pure text field, and the standing proof that overpunch decoding must be position aware: across the
     * 300 rows of the identically-shaped fixture {@code app/data/ASCII/dailytran.txt} this field contains
     * every one of the eighteen overpunch letter codes {@code A} through {@code R}.
     */
    private static final int MERCHANT_NAME_START = 153;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} end, byte 202. */
    private static final int MERCHANT_NAME_END = 202;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} start, byte 203 ({@code app/cpy/CVTRA05Y.cpy:L13}). */
    private static final int MERCHANT_CITY_START = 203;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} end, byte 252. */
    private static final int MERCHANT_CITY_END = 252;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} start, byte 253 ({@code app/cpy/CVTRA05Y.cpy:L14}). */
    private static final int MERCHANT_ZIP_START = 253;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} end, byte 262. */
    private static final int MERCHANT_ZIP_END = 262;

    /**
     * {@code TRAN-CARD-NUM PIC X(16)} start, byte 263 ({@code app/cpy/CVTRA05Y.cpy:L15}).
     * <p>
     * Independently corroborated by {@code TRAN-CARD-NUM,263,16,ZD} at
     * {@code app/proc/TRANREPT.prc:L39}, the one-based symbol the downstream sort orders on. Never logged
     * and never placed in an exception message or the execution context.
     */
    private static final int CARD_NUMBER_START = 263;

    /** {@code TRAN-CARD-NUM PIC X(16)} end, byte 278. */
    private static final int CARD_NUMBER_END = 278;

    /** {@code TRAN-ORIG-TS PIC X(26)} start, byte 279 ({@code app/cpy/CVTRA05Y.cpy:L16}). Text, never parsed. */
    private static final int ORIG_TS_START = 279;

    /** {@code TRAN-ORIG-TS PIC X(26)} end, byte 304. */
    private static final int ORIG_TS_END = 304;

    /**
     * {@code TRAN-PROC-TS PIC X(26)} start, byte 305 ({@code app/cpy/CVTRA05Y.cpy:L17}). Text, never parsed.
     * <p>
     * Corroborated twice: {@code TRAN-PROC-DT,305,10,CH} at {@code app/proc/TRANREPT.prc:L40} is one-based
     * and takes the first ten characters, and {@code AXRKP 304} at {@code app/catlg/LISTCAT.txt:L3676} is
     * zero-based and denotes the same byte.
     */
    private static final int PROC_TS_START = 305;

    /** {@code TRAN-PROC-TS PIC X(26)} end, byte 330. */
    private static final int PROC_TS_END = 330;

    /**
     * The scale of {@code TRAN-AMT}, taken from the {@code V99} of {@code PIC S9(09)V99}.
     * <p>
     * The decoded value carries exactly this scale, and the invariant is asserted rather than assumed.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Charset used for every decode, fixed explicitly and never the platform default.
     * <p>
     * ISO-8859-1 is byte transparent: each of the 256 code points {@code 0x00} to {@code 0xFF} maps to
     * exactly one byte and back, so a 350-byte record becomes exactly 350 characters and every offset the
     * copybook declares lands on the byte it names. A variable-width Unicode encoding would decode any byte
     * above {@code 0x7F} as a replacement character, or fold two bytes into one, and silently shift every
     * offset after it; the platform default is not reproducible across hosts. The same charset is used by
     * the sibling readers and by the fixed-width writers of this module, so a record round-trips unchanged.
     * <p>
     * <b>No EBCDIC is decoded.</b> {@code app/data/EBCDIC/**} is codepage reference material only and is
     * never parsed by the build.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Buffer size for the character stream on the {@code object-storage} path, in characters.
     * <p>
     * Sized to hold whole records: 32 records of {@value #RECORD_LENGTH} characters plus their optional
     * terminators. It bounds heap use independently of the generation size, which is what keeps the read
     * streaming rather than materialising.
     */
    private static final int STREAM_BUFFER_CHARS = 32 * (RECORD_LENGTH + 1);

    /**
     * The object-store key length limit, in characters, and therefore the bound a carried key must satisfy.
     * <p>
     * Stated rather than assumed: an object key may be up to 1024 UTF-8 bytes, so a value past that cannot name
     * an object and is refused before it reaches a request or a log line. See
     * {@link #requireKeyWithinGeneration(String, String)}.
     */
    private static final int MAX_OBJECT_KEY_LENGTH = 1024;

    // ----------------------------------------------------------------------------------------------------
    // Trailing-sign overpunch table for app/cpy/CVTRA05Y.cpy. The sign of a zoned-decimal field is carried
    // by its LAST character, which encodes both the sign and the final digit. THIS CLASS IS THE CANONICAL
    // OWNER of this decode for the CVTRA05Y layout: the planned
    // com.cardemo.batch.readers.CombinedTransactionReader, which does not exist at this commit, is to reuse
    // decodeSignedTransactionAmount rather than re-implement it, which is why that method is
    // package-private. One codec per record layout, colocated with the reader that owns that layout, is the
    // repository convention; DailyTransactionReader owns the identically-shaped app/cpy/CVTRA06Y.cpy.
    // ----------------------------------------------------------------------------------------------------

    /** The overpunch code for positive zero, <code>&#123;</code>. */
    private static final char OVERPUNCH_POSITIVE_ZERO = '{';

    /** The overpunch code for negative zero, <code>&#125;</code>. */
    private static final char OVERPUNCH_NEGATIVE_ZERO = '}';

    /** The first positive overpunch letter, {@code A}, which encodes a final digit of one. */
    private static final char OVERPUNCH_POSITIVE_FIRST = 'A';

    /** The last positive overpunch letter, {@code I}, which encodes a final digit of nine. */
    private static final char OVERPUNCH_POSITIVE_LAST = 'I';

    /** The first negative overpunch letter, {@code J}, which encodes a final digit of one. */
    private static final char OVERPUNCH_NEGATIVE_FIRST = 'J';

    /** The last negative overpunch letter, {@code R}, which encodes a final digit of nine. */
    private static final char OVERPUNCH_NEGATIVE_LAST = 'R';

    /** The lowest plain decimal digit, accepted in the terminal position as an unsigned image. */
    private static final char DIGIT_ZERO = '0';

    /** The highest plain decimal digit. */
    private static final char DIGIT_NINE = '9';

    /** The digit the two zero overpunch codes stand for. */
    private static final char DIGIT_ONE = '1';

    // ----------------------------------------------------------------------------------------------------
    // Row terminators. A RECFM=FB generation carries none at all, while a classpath or ASCII fixture
    // carries one per row, so both shapes are handled rather than one being assumed.
    // ----------------------------------------------------------------------------------------------------

    /** Line feed, the terminator an ASCII fixture carries. */
    private static final char LINE_FEED = '\n';

    /** Carriage return, stripped defensively so a record produced on another platform still aligns. */
    private static final char CARRIAGE_RETURN = '\r';

    /** The value {@link java.io.Reader#read()} returns at end of stream. */
    private static final int END_OF_STREAM = -1;

    // ----------------------------------------------------------------------------------------------------
    // WORKING-STORAGE counterparts. No COBOL program exists for this step, so the shape is borrowed from
    // the corpus-wide guard idiom of app/cbl/CBTRN02C.cbl:L131-L148 and cited as borrowed.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBTRN02C.cbl:L146}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBTRN02C.cbl:L361}). */
    private static final String END_OF_FILE_YES = "Y";

    /**
     * The {@code '0'} that occupies the second byte of {@link #STATUS_PHYSICAL_IO_ERROR}.
     * <p>
     * It is the character a COBOL {@code MOVE} into a numeric display item pads with on the left, which is
     * why it is the right stand-in for an unavailable subcode rather than a space.
     */
    private static final char NUMERIC_SUBCODE_NONE = '0';

    /** {@code '00'}: the success status every guard in the corpus tests for. */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /** {@code '10'}: end of file, driving {@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L352}. */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /**
     * {@code '35'}: the file is not available, which is what an absent backup generation corresponds to.
     * <p>
     * It is the counterpart of the CICS {@code NOTOPEN} condition and it is <b>not</b> an empty read: see
     * {@link #openInputSource()} for why a missing generation is a failure here.
     */
    private static final String STATUS_FILE_UNAVAILABLE = requireExactCode(FileStatus.FILE_UNAVAILABLE);

    /**
     * The member of the {@code '9x'} family this reader reports when the underlying store or stream rejects
     * an operation.
     * <p>
     * {@link FileStatus#IO_ERROR} is a family rather than a value: its first byte is fixed at
     * {@link FileStatus#IO_ERROR_FIRST_BYTE} and the second carries an implementation-defined subcode.
     * Neither a relational store nor an object store reports a VSAM subcode, so the subcode is set to
     * {@code '0'} to mean "no further subcode available from this layer". The underlying detail is never
     * discarded: it travels as the cause of the thrown exception.
     * <p>
     * The specific z/OS VSAM subcode a given failure would have produced on the mainframe cannot be
     * established from this repository, because mainframe-runtime reproduction is out of scope. Should a
     * byte-exact subcode ever be required, the translation belongs at the {@link FileStatusMapper} layer,
     * where the status vocabulary already lives, and not in this reader.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_SUBCODE_NONE;

    /**
     * The lower bound that stands in for {@code LOW-VALUES} when the {@code repository} browse starts.
     * <p>
     * Sixteen spaces, which is the width of {@code TRAN-ID PIC X(16)} and, in the {@code CHAR(16)} column
     * the key occupies, compares less than or equal to every stored identifier. It is the analogue of the
     * {@code MOVE LOW-VALUES} that positions a browse at the start of a VSAM cluster; the inclusive finder
     * is then correct for the first page and no synthesised predecessor key is needed.
     */
    private static final String LOW_VALUES_TRANSACTION_ID = " ".repeat(TRANSACTION_ID_END);

    /**
     * Execution-context key holding the resolved generation object key.
     * <p>
     * <b>This is the carry-forward mechanism, and it is the reason this class is correct inside a
     * pipeline.</b> It is deliberately namespaced on the generation base rather than on this class, because
     * the step that <i>writes</i> the generation promotes the same key and the two must agree on one
     * spelling. A key, a row count and an identifier are the only things that ever reach the context: no
     * card number, no amount and no record content.
     */
    private static final String CONTEXT_KEY_GENERATION_OBJECT_KEY = "carddemo.gdg.transact-bkup.objectKey";

    /** Execution-context key holding the number of records emitted so far. */
    private static final String CONTEXT_KEY_RECORDS_READ = "TransactionBackupReader.recordsRead";

    /**
     * Execution-context key holding the {@code TRAN-ID} of the most recently emitted record.
     * <p>
     * The identifier is checkpointed because it <b>is</b> the cluster key
     * ({@code app/catlg/LISTCAT.txt:L3593-L3594}, {@code KEYLEN 16} at {@code RKP 0}), so a resumed run can
     * seek to it exactly rather than counting rows. Nothing else from the record is stored.
     */
    private static final String CONTEXT_KEY_LAST_TRANSACTION_ID = "TransactionBackupReader.lastTransactionId";

    /**
     * Where the 350-byte {@code TRANSACT.BKUP} records are read from.
     * <p>
     * Both constants are reachable through {@value #PROPERTY_SOURCE}, so neither branch is dead code. They
     * exist because the legacy generation and the Java target hold the same records by two different
     * routes: {@code app/ctl/REPROCT.ctl:L15} copies the whole transaction cluster into the generation
     * without selection, so the generation's content and the {@code transaction} relation's content are the
     * same set of records, and either is a faithful source for a read-only report input.
     * <p>
     * A nested enum is used deliberately: this package is capped at seven classes, and a nested type adds
     * no file.
     */
    private enum InputSource {

        /**
         * Read the rows from the {@code transaction} relation through the repository's keyset browse, in
         * ascending {@code TRAN-ID} order.
         * <p>
         * The default, for two reasons. It is the in-database equivalent of
         * {@code REPRO INFILE(FILEIN)} reading {@code TRANSACT.VSAM.KSDS}
         * ({@code app/proc/TRANREPT.prc:L24-L25}) - the very cluster the backup copies - so it needs no
         * generation to exist and no bucket to be configured, and it is the choice the sibling readers of
         * this package already make, so this reader introduces no second convention.
         */
        REPOSITORY,

        /**
         * Read and decode the 350-byte records from the resolved {@code TRANSACT.BKUP} generation object.
         * <p>
         * The closer analogue of {@code //SORTIN DD} at {@code app/proc/TRANREPT.prc:L36-L37}, because it
         * consumes the same fixed-width image the generation held, and the path that exercises both the
         * carry-forward rule and the position-aware decoder.
         */
        OBJECT_STORAGE
    }

    // ----------------------------------------------------------------------------------------------------
    // Collaborators and configuration, injected through the constructor and never reassigned. No field is
    // annotated @Autowired and there is no setter injection.
    // ----------------------------------------------------------------------------------------------------

    /**
     * The transaction-table access point. Read-only: the only methods reached are the two ordered keyset
     * finders, and there is no {@code save}, {@code delete}, {@code @Modifying} query or
     * {@code EntityManager} reference anywhere in this file.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The object-store access point, supplied as the {@code S3Operations} interface by
     * {@code com.cardemo.config.AwsConfig}.
     * <p>
     * No client is constructed here and no credential is handled here; the emulator endpoint override is
     * declared in all four profiles - required with no default in the base, {@code test} and {@code prod}
     * profiles, and defaulted to the LocalStack edge only in {@code application-local.yml} - so no live-cloud
     * path is structurally reachable. Only {@code listObjects}, {@code objectExists} and {@code download} are
     * called, all three read-only.
     */
    private final S3Operations objectStorage;

    /**
     * The object-store client, held for exactly one purpose: paging a listing.
     * <p>
     * {@code S3Operations.listObjects} issues one {@code ListObjectsV2} call and returns that single page, so
     * once a generation base held more keys than one page carries, "the lexicographically greatest key" was
     * computed over an arbitrary subset and the current generation resolved <b>stale</b> - a wrong answer that
     * looks exactly like a right one. This client's paginator walks every page, which is what makes the
     * {@code (0)} semantic correct at any scale.
     * <p>
     * It is injected, never constructed; no endpoint and no credential is read here. It is used read-only, for
     * {@code listObjectsV2Paginator} and nothing else - every read and existence probe still goes through
     * {@code S3Operations}.
     */
    private final S3Client objectStoreClient;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already renders
     * the {@code 9910-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT} arithmetic of
     * both the two-way guard and the three-way sequential-read guard. Duplicating any of that here would be
     * the parallel mapping the repository-hygiene standard forbids.
     */
    private final FileStatusMapper fileStatusMapper;

    /** The selected input path, resolved and validated once at construction. */
    private final InputSource inputSource;

    /** Rows per database round trip on the {@code repository} path; validated at construction. */
    private final int pageSize;

    /**
     * The versioned generation bucket, used only on the {@code object-storage} path.
     * <p>
     * Empty when unset, which is permitted on the {@code repository} path and refused at construction on
     * the {@code object-storage} one.
     */
    private final String outputBucket;

    /** The key prefix under which generations are written; validated and normalised at construction. */
    private final String generationPrefix;

    /**
     * The concrete generation key a prior step promoted into the job execution context, or {@code null}
     * when this reader was invoked standalone.
     * <p>
     * <b>This is the {@code (+1)}-written-then-{@code (+1)}-read contract of
     * {@code app/proc/TRANREPT.prc:L31} and {@code :L37} expressed in Spring Batch terms.</b> It is
     * injected rather than looked up so that the dependency is visible in the constructor signature and so
     * that a unit test can supply it without a running job.
     */
    private final String promotedGenerationObjectKey;

    // ----------------------------------------------------------------------------------------------------
    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item and is therefore an
    // INSTANCE field: never static, never shared. The step scope gives each step execution its own
    // instance.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01)}. Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /** {@code APPL-RESULT PIC S9(9) COMP}, tested through {@code APPL-AOK} and {@code APPL-EOF}. */
    private int applResult;

    /** {@code IO-STATUS}, the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /** {@code TRAN-RECORD}, the record area that {@code COPY CVTRA05Y} would declare. */
    private Transaction transactionRecord;

    /**
     * The generation object key resolved once by {@link #open(ExecutionContext)} and then held fixed.
     * <p>
     * <b>It is never recomputed while a step is running.</b> {@code null} on the {@code repository} path.
     */
    private String resolvedGenerationObjectKey;

    /**
     * How many generations the listing held when the standalone {@code (0)} semantic resolved one.
     * <p>
     * This is the <b>log-safe</b> identity of the selected generation, and it is what a diagnostic names
     * instead of the key. Because keys are written under a monotonically increasing prefix and the
     * {@code (0)} semantic takes the lexicographically greatest, the selected generation is always the last
     * of the listing - so "generation <i>n</i> of <i>n</i>" both identifies the selection and states how
     * many exist, which is the datum that distinguishes "the backup step ran once" from "it has run
     * fifty times". Zero on every path that does not perform a listing, namely the {@code repository} path
     * and both carry-forward paths.
     */
    private int resolvedGenerationCount;

    /** Rows of the page currently buffered on the {@code repository} path. */
    private List<Transaction> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /**
     * Whether a further page may follow, as last reported by {@code Slice.hasNext()}.
     * <p>
     * Seeded {@code true} so the first fetch is always attempted; once it is {@code false} the next fetch
     * attempt reports end of file instead of issuing a query that is known to be empty.
     */
    private boolean morePagesAvailable = true;

    /**
     * The identifier the next {@code repository} page is keyed on, or {@code null} before the first page.
     * <p>
     * This is what makes the browse a <b>keyset</b> browse rather than an offset one, and it is why a
     * restart is exact rather than approximate.
     */
    private String pageKeyTransactionId;

    /**
     * The character stream over the generation object on the {@code object-storage} path, held open between
     * {@link #open(ExecutionContext)} and {@link #close()} exactly as the source holds a dataset open for
     * the life of a step. {@code null} on the {@code repository} path and after the close.
     */
    private BufferedReader recordStream;

    /**
     * Scratch buffer for one record on the {@code object-storage} path, allocated once per open so the read
     * loop allocates no array per record.
     */
    private char[] recordBuffer;

    /** Rows emitted so far, the counter an end-of-run summary reports. */
    private long recordsRead;

    /**
     * {@code TRAN-ID} of the most recently emitted record, checkpointed by
     * {@link #update(ExecutionContext)}. Never a card number and never an amount.
     */
    private String lastTransactionId;

    /** Whether the open completed successfully, mirroring an open VSAM ACB or an open dataset. */
    private boolean fileOpen;

    /**
     * Creates a reader bound to the transaction-backup generation.
     *
     * @param transactionRepository the transaction-table access point; must not be {@code null}
     * @param objectStorage the object-store access point supplied by {@code com.cardemo.config.AwsConfig};
     *     must not be {@code null}
     * @param objectStoreClient the object-store client, used <strong>only</strong> to page a listing.
     *     {@code S3Operations} exposes a single-page {@code listObjects} - one {@code ListObjectsV2} call
     *     returning at most one page - so resolving the current generation through it stopped being correct
     *     once a prefix held more keys than one page returns. That is finding H-07, severity High, and this
     *     parameter is its remediation: the client offers the paginator the operations interface does not.
     *     Every other access still goes through {@code S3Operations}. Must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param configuredSource the input selector from {@value #PROPERTY_SOURCE}, one of
     *     {@code repository} or {@code object-storage}, case-insensitive and accepting either a hyphen or an
     *     underscore; defaults to {@code repository}
     * @param pageSize rows per database round trip from {@value #PROPERTY_PAGE_SIZE}, defaulting to
     *     {@value #DEFAULT_PAGE_SIZE}; must be at least one
     * @param outputBucket the versioned generation bucket from {@value #PROPERTY_OUTPUT_BUCKET}, defaulting
     *     to empty and required non-blank only when the {@code object-storage} path is selected
     * @param generationPrefix the generation key prefix from {@value #PROPERTY_GENERATION_PREFIX},
     *     defaulting to {@value #DEFAULT_GENERATION_PREFIX}; must not be blank
     * @param promotedGenerationObjectKey the concrete generation key a prior step promoted into the job
     *     execution context under {@value #CONTEXT_KEY_GENERATION_OBJECT_KEY}, or {@code null} when this
     *     reader was invoked standalone; blank is treated as absent
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if the selector names neither path, if {@code pageSize} is less than
     *     one, if {@code generationPrefix} is blank, or if the {@code object-storage} path is selected with
     *     a blank bucket
     */
    public TransactionBackupReader(
            final TransactionRepository transactionRepository,
            final S3Operations objectStorage,
            final S3Client objectStoreClient,
            final FileStatusMapper fileStatusMapper,
            @Value("${" + PROPERTY_SOURCE + ":repository}") final String configuredSource,
            @Value("${" + PROPERTY_PAGE_SIZE + ":" + DEFAULT_PAGE_SIZE + "}") final int pageSize,
            @Value("${" + PROPERTY_OUTPUT_BUCKET + ":}") final String outputBucket,
            @Value("${" + PROPERTY_GENERATION_PREFIX + ":" + DEFAULT_GENERATION_PREFIX + "}")
                    final String generationPrefix,
            @Value("#{jobExecutionContext['" + CONTEXT_KEY_GENERATION_OBJECT_KEY + "']}")
                    final String promotedGenerationObjectKey) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an
        // overridable instance method from the constructor of a non-final class would publish a partially
        // built reference, which -Xlint:all -Werror reports as this-escape; the step scope forbids a final
        // class because it proxies by subclassing.
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.objectStoreClient =
                Objects.requireNonNull(objectStoreClient, "objectStoreClient must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.inputSource = requireInputSource(configuredSource);
        this.pageSize = requirePositivePageSize(pageSize);
        this.generationPrefix = requireGenerationPrefix(generationPrefix);
        this.outputBucket = requireOutputBucket(outputBucket, this.inputSource);
        this.promotedGenerationObjectKey = blankToNull(promotedGenerationObjectKey);
    }

    // ====================================================================================================
    // The ItemStream lifecycle. There is no COBOL mainline to reproduce, because no COBOL program exists
    // for this step: app/proc/TRANREPT.prc:L21 delegates to app/proc/REPROC.prc:L21 EXEC PGM=IDCAMS, whose
    // whole logic is the single control card at app/ctl/REPROCT.ctl:L15. The open-read-close skeleton and
    // the guard idiom are therefore borrowed from the corpus-wide batch shape and cited as borrowed.
    // ====================================================================================================

    /**
     * Opens the backup generation, resolving its object key exactly once and never again.
     * <p>
     * <b>The resolution order is the whole point of this method</b> and is fixed:
     * {@link #resolveGenerationObjectKey(ExecutionContext)} prefers the key this very step checkpointed,
     * then the key a prior step promoted, and only then falls back to a lexical-greatest listing. See that
     * method for why the order matters.
     * <p>
     * <b>Side effects.</b> Resets every cursor field, restores the restart cursor when the context carries
     * one, resolves the generation key, opens either a keyset cursor position or a character stream over the
     * generation object, and writes log events. No row is read and nothing is written anywhere.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of the
     *     same step instance is honoured when present, and a {@code null} context is treated as a cold start
     *     so the reader remains usable outside a step
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the generation cannot be reached or is absent, reproducing the
     *     corpus shape {@code DISPLAY 'ERROR OPENING ...'} then {@code PERFORM 9999-ABEND-PROGRAM} of
     *     {@code app/cbl/CBTRN02C.cbl:L247-L250}
     */
    @Override
    public void open(final ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous run's
        // position. Ordering matters only in that this happens before the restart cursor is restored.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        transactionRecord = null;
        resolvedGenerationObjectKey = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        morePagesAvailable = true;
        pageKeyTransactionId = null;
        recordStream = null;
        recordBuffer = null;
        recordsRead = 0L;
        lastTransactionId = null;
        fileOpen = false;

        // A null context is an explicit, handled case rather than a guarded assumption. So is a context
        // that exists but carries no checkpoint, which is a cold start.
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // Resolved ONCE, here, and held for the life of the step.
        resolvedGenerationObjectKey = resolveGenerationObjectKey(executionContext);

        openBackupGeneration();

        // Advance a resumed run to the record after the last one emitted. A cold start returns immediately.
        positionAfterRestart();
    }

    /**
     * Returns the next backed-up transaction, or {@code null} once the generation is exhausted.
     * <p>
     * <b>Nothing is resolved here.</b> The generation key was fixed by {@link #open(ExecutionContext)} and
     * this method never consults a listing, a prefix or a "latest" rule, which is what keeps the reader
     * correct when another job writes a newer generation between two steps of this one.
     * <p>
     * <b>End of data is loop termination and not an error.</b> Answering {@code null} is the Spring Batch
     * end-of-input signal and the analogue of file status {@code '10'} driving
     * {@code MOVE 16 TO APPL-RESULT} and {@code MOVE 'Y' TO END-OF-FILE}
     * ({@code app/cbl/CBTRN02C.cbl:L352}, {@code :L361}, with {@code 88 APPL-EOF VALUE 16} at
     * {@code :L144}).
     * <p>
     * <b>No per-record log event is emitted.</b> The record carries a 16-character card number at bytes
     * 263-278 and a monetary amount, so a per-record dump would publish exactly what must never be logged;
     * and the legacy step, being a {@code REPRO}, emitted nothing per record either.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this
     * method inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a
     * method that merely <em>participates</em> in an existing transaction rather than starting one.
     * Annotating {@code readOnly = true} here would read as an enforced guarantee while enforcing nothing.
     * Read-only is guaranteed structurally instead: the only repository methods this class can reach are
     * {@link TransactionRepository#findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(String,
     * org.springframework.data.domain.Pageable)} and
     * {@link TransactionRepository#findByTransactionIdGreaterThanOrderByTransactionIdAsc(String,
     * org.springframework.data.domain.Pageable)}, and the only object-store calls are
     * {@code listObjects}, {@code objectExists} and {@code download}.
     *
     * @return the next transaction in key order, or {@code null} at end of data
     * @throws FatalProcessingException if the input reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path of {@code app/cbl/CBTRN02C.cbl:L363-L366}
     * @throws DataIntegrityException if a record is not exactly {@value #RECORD_LENGTH} characters or
     *     carries an unrecognised overpunch or a non-digit where the copybook declares digits
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}
     */
    @Override
    public Transaction read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); the generation object key is resolved "
                            + "exactly once during open and read() must never resolve it, so reading "
                            + "before opening cannot be serviced");
        }

        // PERFORM UNTIL END-OF-FILE = 'Y' - the framework owns the iteration, so the terminating condition
        // becomes an explicit early return that keeps answering null after the input has been exhausted.
        if (END_OF_FILE_YES.equals(endOfFile)) {
            return null;
        }

        final Transaction record = getNextTransaction();
        if (record == null || !END_OF_FILE_NO.equals(endOfFile)) {
            return null;
        }

        recordsRead++;
        lastTransactionId = record.getTransactionId();
        return record;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step resumes on the <b>same generation</b> and at the
     * record after the last one emitted.
     * <p>
     * Exactly three values are stored, and together they are the whole of the cursor:
     * <ul>
     *   <li>the resolved generation object key, which is what makes the resume land on the same generation
     *       rather than on whatever is newest by the time the step restarts - this is the carry-forward
     *       contract of {@code app/proc/TRANREPT.prc:L31} and {@code :L37} made durable;</li>
     *   <li>the number of records emitted so far, which is the position; and</li>
     *   <li>the {@code TRAN-ID} of the most recently emitted record, which is the cluster key
     *       ({@code app/catlg/LISTCAT.txt:L3593-L3594}) and therefore an exact seek target.</li>
     * </ul>
     * Nothing else from the record is stored - <b>no card number, no amount, no merchant detail, no entity
     * and no record image ever reaches the context</b>.
     * <p>
     * <b>Side effects.</b> Mutates {@code executionContext} only. Performs no I/O and logs nothing.
     *
     * @param executionContext the step execution context to write into; a {@code null} context is ignored,
     *     which makes the reader usable outside a step for unit testing
     * @throws org.springframework.batch.item.ItemStreamException never thrown; this method cannot fail
     */
    @Override
    public void update(final ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }
        executionContext.putLong(CONTEXT_KEY_RECORDS_READ, recordsRead);
        if (resolvedGenerationObjectKey != null) {
            executionContext.putString(CONTEXT_KEY_GENERATION_OBJECT_KEY, resolvedGenerationObjectKey);
        }
        if (lastTransactionId != null) {
            executionContext.putString(CONTEXT_KEY_LAST_TRANSACTION_ID, lastTransactionId);
        }
    }

    /**
     * Closes the generation, releasing the page buffer or the character stream.
     * <p>
     * The order is the corpus's: the close precedes the summary, so a close failure abends before the
     * summary is written and the summary is therefore evidence that the read completed. The resolved
     * generation key is reported alongside the row count, because those two values together are what make a
     * pipeline run traceable - and neither is sensitive.
     * <p>
     * <b>Side effects.</b> Releases the buffer or the stream, resets the cursor and writes at least one log
     * event.
     *
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly
     * @throws FatalProcessingException if releasing the input fails, reproducing the corpus shape
     *     {@code DISPLAY 'ERROR CLOSING ...'} then {@code PERFORM 9999-ABEND-PROGRAM} of
     *     {@code app/cbl/CBTRN02C.cbl:L593-L596}
     */
    @Override
    public void close() {
        // Captured before the close, which clears the field. Reduced to a present-or-absent marker rather
        // than the key itself: see the class documentation's log-hygiene section.
        final String closedGeneration = generationPresenceMarker(resolvedGenerationObjectKey);
        closeBackupGeneration();

        LOG.info("{} closed; dataset={} source={} generation={} recordsRead={}",
                LOGICAL_FILE, DATASET_NAME, inputSource, closedGeneration, Long.valueOf(recordsRead));
    }

    /**
     * Returns the number of records emitted so far.
     * <p>
     * Exposed so the sibling-owned Micrometer "records processed" counter can observe this step without this
     * class registering an instrument of its own. <b>No meter, timer or gauge is created here</b>: the four
     * named counters are owned by {@code com.cardemo.observability.MetricsConfig}, and adding a fifth
     * instrument from a reader would duplicate that ownership. In particular an object key must never become
     * a metric tag, because a new generation per run makes it unbounded in cardinality.
     *
     * @return the count of records returned by {@link #read()} since the last
     *     {@link #open(ExecutionContext)}, never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    /**
     * Returns the generation object key this step is reading, for diagnostics and for tests that assert the
     * carry-forward contract.
     *
     * @return the resolved object key on the {@code object-storage} path, or {@code null} on the
     *     {@code repository} path and before {@link #open(ExecutionContext)} has run
     */
    public String getResolvedGenerationObjectKey() {
        return resolvedGenerationObjectKey;
    }

    // ====================================================================================================
    // GENERATION RESOLUTION. The single most consequential method in this class.
    // ====================================================================================================

    /**
     * Resolves the concrete generation object key, once, in a fixed precedence.
     * <p>
     * <b>This order must not be changed.</b> A relative generation reference is resolved once per
     * job on z/OS and then held: {@code app/proc/TRANREPT.prc:L31} creates
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} in {@code STEP01R} and {@code :L37} reads
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} in {@code STEP05R} - the same spelling, the same generation.
     * The same pattern recurs at {@code :L53} to {@code :L64} for {@code TRANSACT.DALY} and again at
     * {@code app/jcl/COMBTRAN.jcl:L43-L44}. Treating {@code (+1)} on the read side as "whatever is newest"
     * yields a reader that passes every isolated test and races in the pipeline, because a concurrent job's
     * generation would be selected between two steps of this one.
     * <p>
     * The order is therefore:
     * <ol>
     *   <li><b>The step execution context.</b> Set by {@link #update(ExecutionContext)} on a previous run of
     *       this same step instance, so a restart resumes on the identical generation, and also the entry a
     *       job-level promotion copies down. Highest precedence, because a restart must not change
     *       generation mid-flight.</li>
     *   <li><b>The key promoted by a prior step of this job</b>, injected at construction from
     *       {@code jobExecutionContext} under {@value #CONTEXT_KEY_GENERATION_OBJECT_KEY}. This is the
     *       {@code STEP01R}-writes-then-{@code STEP05R}-reads case and the normal pipeline path.</li>
     *   <li><b>A lexical-greatest listing.</b> Only when neither of the above supplied a key, which is the
     *       documented standalone-invocation case: the {@code (0)} rather than the {@code (+1)} semantic. It
     *       is a listing and a maximum taken in {@link Comparator#naturalOrder()} order - <b>no external
     *       process is spawned, here or anywhere in this file</b>.</li>
     * </ol>
     *
     * @param executionContext the step execution context, or {@code null} outside a step
     * @return the resolved object key, or {@code null} on the {@code repository} path where no generation is
     *     involved at all
     * @throws FatalProcessingException if the {@code object-storage} path is selected and no generation
     *     exists to read; see {@link #resolveLatestGenerationObjectKey()} for why that is a failure rather
     *     than an empty read
     */
    private String resolveGenerationObjectKey(final ExecutionContext executionContext) {
        if (inputSource == InputSource.REPOSITORY) {
            // No generation is read on this path, so there is nothing to resolve and nothing to carry
            // forward. Stated explicitly rather than left to be inferred from a null.
            return null;
        }

        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_GENERATION_OBJECT_KEY)) {
            final String checkpointed = blankToNull(
                    executionContext.getString(CONTEXT_KEY_GENERATION_OBJECT_KEY, ""));
            if (checkpointed != null) {
                // Validated BEFORE it is read from, not after: a value already read from is a redirected
                // read, and this validator is what forecloses that. The key itself is not named in the
                // emission - the context entry it arrived under is the diagnostic, and the key is readable
                // from the job repository, which is where a value identifying a backup belongs.
                final String verified = requireKeyWithinGeneration(checkpointed, "this step's own checkpoint");
                LOG.info("{} generation carried forward from the execution context under '{}'",
                        LOGICAL_FILE, CONTEXT_KEY_GENERATION_OBJECT_KEY);
                return verified;
            }
        }

        if (promotedGenerationObjectKey != null) {
            final String verified = requireKeyWithinGeneration(promotedGenerationObjectKey,
                    "the job execution context entry " + CONTEXT_KEY_GENERATION_OBJECT_KEY);
            LOG.info("{} generation carried forward from a prior step under '{}'",
                    LOGICAL_FILE, CONTEXT_KEY_GENERATION_OBJECT_KEY);
            return verified;
        }

        final String latest = resolveLatestGenerationObjectKey();
        LOG.info("{} no generation was promoted by a prior step, so the standalone (0) semantic applies; "
                        + "resolved generation {} of {} under prefix '{}'",
                LOGICAL_FILE, Integer.valueOf(resolvedGenerationCount),
                Integer.valueOf(resolvedGenerationCount), generationPrefix);
        return latest;
    }

    /**
     * Resolves the lexicographically greatest existing generation key under the configured prefix, which is
     * the {@code (0)} - current generation - semantic.
     * <p>
     * Keys are written under a monotonically increasing prefix, so lexical order and generation order
     * coincide and the greatest key is the current generation. The maximum is taken in
     * {@link Comparator#naturalOrder()} order in one pass over the listing, which also counts the
     * generations so the diagnostic can name the selected one by ordinal rather than by key; <b>no sort
     * utility is invoked and no process is spawned</b>.
     * <p>
     * <b>The listing is paged.</b> Finding H-07, severity High, RESOLVED: an earlier revision called
     * {@code S3Operations.listObjects}, which issues one {@code ListObjectsV2} request and returns only that
     * page. Past one page of keys under the base the maximum was therefore taken over an arbitrary subset, so
     * "the current generation" silently resolved to a stale one and the report was produced from an old backup
     * while looking entirely healthy. Every page is now walked.
     * <p>
     * <b>An absent generation is a failure, not an empty read, and the distinction is deliberate.</b>
     * {@code app/proc/TRANREPT.prc} creates the generation at {@code :L27-L31} and only then reads it at
     * {@code :L36-L37}, so within the job stream a generation always exists by the time this read happens.
     * If none exists, either the backup step did not run or it wrote elsewhere; reporting on nothing would
     * silently produce an empty report that looks like a quiet day's trading. It is therefore reported as
     * file status {@code '35'} - "the file is not available" - and abends. An <i>empty</i> generation is a
     * different case and <b>is</b> a successful zero-row read, because {@code REPRO} of an empty cluster
     * legitimately produces an empty object.
     *
     * @return the greatest existing object key under the prefix, never {@code null} and never blank
     * @throws FatalProcessingException if the listing is empty, or if the object store rejects the listing
     */
    private String resolveLatestGenerationObjectKey() {
        Optional<String> greatest;
        int observedGenerations = 0;
        try {
            // EVERY page, not the first. objectStoreClient exists for this call alone; see its field
            // documentation for the finding it resolves. The paginator issues one request per page and streams
            // the results, so the greatest key below is computed over the complete key set at any scale while
            // peak memory stays one page rather than one listing.
            //
            // Iterated rather than reduced by a stream because two values are wanted from one pass: the
            // greatest key, and how many generations the listing held. The count is what lets the caller name
            // the selected generation by ORDINAL instead of by key, which is the whole reason no emission on
            // this path carries the key itself. Collecting the keys first to count them would reintroduce the
            // unbounded listing this method exists to avoid, and a second listing call would be a second
            // request against a set that can change between the two.
            String greatestKey = null;
            for (final S3Object listed : objectStoreClient.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(outputBucket)
                            .prefix(generationPrefix)
                            .build())
                    .contents()) {
                final String key = listed.key();
                if (key == null || key.isBlank() || key.endsWith("/")) {
                    continue;
                }
                observedGenerations++;
                if (greatestKey == null || key.compareTo(greatestKey) > 0) {
                    greatestKey = key;
                }
            }
            greatest = Optional.ofNullable(greatestKey);
        } catch (RuntimeException failure) {
            // Nothing is swallowed: the throwable is translated into the status vocabulary and then travels
            // as the cause of the abend.
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            throw abendException(ERROR_OPENING_MESSAGE, OPERATION_OPEN, failure);
        }

        // Keys are written under a monotonically increasing prefix, so the greatest key is the last generation
        // and its one-based ordinal is the count of generations that exist.
        resolvedGenerationCount = observedGenerations;

        if (greatest.isEmpty()) {
            ioStatus = STATUS_FILE_UNAVAILABLE;
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            throw abendException(String.format(Locale.ROOT,
                    "%s: no %s generation exists under prefix '%s' in bucket '%s'. "
                            + "app/proc/TRANREPT.prc:L27-L31 creates the generation before :L36-L37 reads "
                            + "it, so an absent generation means the backup step did not run or wrote "
                            + "elsewhere; reporting on no data would be worse than failing",
                    ERROR_OPENING_MESSAGE, DATASET_NAME, generationPrefix, outputBucket),
                    OPERATION_OPEN, null);
        }
        return greatest.get();
    }

    /**
     * Confines a carried generation key to this reader's own generation namespace before it is logged or read.
     * <p>
     * <b>Finding M-11, severity Medium, RESOLVED.</b> A key arriving from an execution context is
     * <em>untrusted input</em>: the batch metadata tables are writable by anything holding the datasource, and
     * the promoted value is injected straight from {@code jobExecutionContext}. An earlier revision took it
     * verbatim, logged it and read it, so a substituted value could redirect this step's read to any other
     * object in the same bucket - the report generations, the statement work objects or another base's backups -
     * and the report would be produced from that content without a word of complaint. Worse, the value reached a
     * log line unfiltered, so a carriage return in it could forge a log entry.
     * <p>
     * Five conditions, all necessary, checked before anything is emitted or read:
     * <ol>
     *   <li>non-blank, because a blank key names the bucket rather than an object;</li>
     *   <li>no longer than {@value #MAX_OBJECT_KEY_LENGTH} characters, the object-store key limit, so an
     *       unbounded value cannot be carried into a request or a log;</li>
     *   <li>every character printable ASCII, which is what forecloses the log-forging newline and any control
     *       byte;</li>
     *   <li>the configured generation prefix is a genuine prefix of it, and it contains no {@code ..} or
     *       {@code //} segment - together, exact membership of this base's namespace;</li>
     *   <li>it does not end in a separator, because that names a prefix and not an object.</li>
     * </ol>
     * Each rejection abends with file status {@code '35'} - the dataset is not available - rather than reading
     * something else, because reading the wrong generation is worse than failing.
     *
     * @param key the carried key; must not be {@code null}
     * @param origin where the key came from, named in the failure so an operator knows which entry to correct
     * @return {@code key} unchanged, never {@code null}
     * @throws FatalProcessingException if any condition fails
     */
    private String requireKeyWithinGeneration(final String key, final String origin) {
        String rejection = null;
        if (key == null || key.isBlank()) {
            rejection = "is blank";
        } else if (key.length() > MAX_OBJECT_KEY_LENGTH) {
            rejection = "is " + key.length() + " characters, past the " + MAX_OBJECT_KEY_LENGTH
                    + "-character object-key limit";
        } else if (!isPrintableAscii(key)) {
            rejection = "carries a character outside printable ASCII";
        } else if (!key.startsWith(generationPrefix)) {
            rejection = "does not begin with the configured generation prefix";
        } else if (key.contains("..") || key.contains("//")) {
            rejection = "carries a '..' or '//' segment, so it does not name one object inside the prefix";
        } else if (key.endsWith("/")) {
            rejection = "ends with a separator, so it names a prefix rather than an object";
        }
        if (rejection == null) {
            return key;
        }
        ioStatus = STATUS_FILE_UNAVAILABLE;
        LOG.error(ERROR_OPENING_MESSAGE);
        LOG.error(displayIoStatus(ioStatus));
        // The rejected value is deliberately absent from the message: it failed validation, so it is exactly
        // the value that must not be echoed. The entry that carried it is named instead, which is what an
        // operator needs in order to correct it.
        throw abendException(String.format(Locale.ROOT,
                "%s: the %s generation key carried by %s %s, so it is not a member of the '%s' namespace this "
                        + "step is configured to read. app/proc/TRANREPT.prc:L31 writes the generation and :L37 "
                        + "reads that same generation; reading anything else would report on data this job "
                        + "never backed up",
                ERROR_OPENING_MESSAGE, DATASET_NAME, origin, rejection, generationPrefix),
                OPERATION_OPEN, null);
    }

    /**
     * Reports whether every character of a value is printable ASCII, space through tilde.
     * <p>
     * A pure function of its argument. Used to foreclose control bytes in a value that reaches an
     * object-store request, and in one that reaches an abend message naming the rejection.
     *
     * @param value the value to inspect; must not be {@code null}
     * @return {@code true} when every character is in the range 0x20 to 0x7E inclusive
     */
    private static boolean isPrintableAscii(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < ' ' || character > '~') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a resolved generation key as the log-safe marker {@code resolved} or {@code n/a}.
     *
     * <p><strong>Finding, severity Medium, resolved - CWE-532 and CWE-200.</strong> The open and close
     * emissions named the resolved generation object key in full. A generation key identifies one concrete
     * backup of the transaction cluster and the run that produced it, and because it is the same key for
     * every record of a run, naming it once names it for the whole run. It is passed to object storage and
     * to nothing else. What a diagnostic needs is whether a generation was in play at all - which
     * distinguishes the {@code object-storage} path from the {@code repository} path, and a successful
     * resolution from a failed one - and that is a two-valued fact, so a two-valued marker states it
     * exactly. Where the selected generation's identity genuinely matters, the standalone {@code (0)}
     * resolution reports it as {@linkplain #resolvedGenerationCount an ordinal out of a count} instead.
     *
     * @param generationObjectKey the resolved key, or {@code null} when none was resolved
     * @return {@code "resolved"} when a generation is in play, {@code "n/a"} otherwise, never {@code null}
     */
    private static String generationPresenceMarker(final String generationObjectKey) {
        return generationObjectKey == null ? "n/a" : "resolved";
    }

    // ====================================================================================================
    // OPEN. Borrowed shape: app/cbl/CBTRN02C.cbl:L236-L252, since this step has no program of its own.
    // ====================================================================================================

    /**
     * Opens the input and applies the two-way guard of the corpus-wide open idiom
     * ({@code app/cbl/CBTRN02C.cbl:L236-L252}).
     * <p>
     * The shape is preserved: {@code MOVE 8 TO APPL-RESULT} ({@code :L237}) seeds the result with a failure
     * value that only a successful open clears; the open is attempted ({@code :L238}); {@code '00'} yields
     * {@code MOVE 0} and anything else {@code MOVE 12} ({@code :L239-L243}); then the guard either continues
     * ({@code :L244-L245}) or reports and abends ({@code :L247-L250}).
     * <p>
     * <b>Side effects.</b> One round trip or one object-store request; sets the open flag; allocates the
     * per-record buffer on the {@code object-storage} path; writes one log event on success and two before
     * abending on failure.
     *
     * @throws FatalProcessingException if the input cannot be reached or the generation is absent
     */
    private void openBackupGeneration() {
        // MOVE 8 TO APPL-RESULT (:L237). Eight is neither APPL-AOK nor APPL-EOF, so an open that never
        // completes cannot be mistaken for one that succeeded.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        Throwable openFailure = null;
        try {
            ioStatus = openInputSource();
        } catch (IOException | RuntimeException failure) {
            // A relational store reports by throwing a DataAccessException and an object store by throwing
            // an unchecked SDK exception or an IOException, so both shapes are translated into the status
            // vocabulary here. Nothing is swallowed: the throwable is retained and travels as the cause.
            // A FatalProcessingException already raised by the generation resolution is rethrown unchanged
            // rather than re-wrapped, so its own diagnosis is not buried.
            if (failure instanceof FatalProcessingException alreadyTyped) {
                throw alreadyTyped;
            }
            openFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF status = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 (:L239-L243). Delegated rather than restated.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        if (applResult == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE (:L244-L245)
            fileOpen = true;
            LOG.info("{} opened; dataset={} source={} generation={}",
                    LOGICAL_FILE, DATASET_NAME, inputSource,
                    generationPresenceMarker(resolvedGenerationObjectKey));
        } else {
            // ELSE DISPLAY ... / MOVE status TO IO-STATUS / PERFORM 9910-DISPLAY-IO-STATUS /
            // PERFORM 9999-ABEND-PROGRAM (:L247-L250)
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            throw abendException(ERROR_OPENING_MESSAGE, OPERATION_OPEN, openFailure);
        }
    }

    /**
     * Performs the work behind the {@code OPEN INPUT} verb and reports its outcome as a COBOL file status.
     * <p>
     * On the {@code object-storage} path a generation key was already resolved by
     * {@link #resolveGenerationObjectKey(ExecutionContext)}, so the existence probe here catches the
     * remaining case: a key that was carried forward or promoted but no longer names an object. That is
     * reported as {@code '35'} rather than as a {@code '9x'} error, because {@code '35'} is precisely "the
     * file is not available" and is the counterpart of the CICS {@code NOTOPEN} condition.
     * <p>
     * The charset is applied here, once, and explicitly. Every subsequent operation is on characters, so no
     * platform-default byte-to-character conversion is reachable anywhere in this class.
     *
     * @return {@link #STATUS_SUCCESS}, or {@link #STATUS_FILE_UNAVAILABLE} when the resolved generation no
     *     longer exists
     * @throws IOException if the generation object's stream cannot be opened
     * @throws DataAccessException if the relation cannot be reached; translated by the caller
     */
    private String openInputSource() throws IOException {
        if (inputSource == InputSource.REPOSITORY) {
            // M-07: no count() here. An exact count is a full scan of the relation this reader is about to
            // walk in bounded windows anyway, and it was issued only to be logged - so the scan bought a log
            // line and nothing else. Reachability is established by the first bounded fetch, which has to
            // happen regardless, and emptiness is reported at the end of the run from the emitted-row
            // counter, where it is a fact rather than a prediction. An operator is told either way; see the
            // end-of-run summary.
            LOG.info("{} source relation opened; the first bounded window establishes whether it holds rows",
                    LOGICAL_FILE);
            return STATUS_SUCCESS;
        }

        if (!objectStorage.objectExists(outputBucket, resolvedGenerationObjectKey)) {
            return STATUS_FILE_UNAVAILABLE;
        }

        final S3Resource resource = objectStorage.download(outputBucket, resolvedGenerationObjectKey);
        final InputStream bytes = resource.getInputStream();
        recordStream = new BufferedReader(new InputStreamReader(bytes, RECORD_CHARSET), STREAM_BUFFER_CHARS);
        recordBuffer = new char[RECORD_LENGTH];
        return STATUS_SUCCESS;
    }

    /**
     * Positions a resumed run on the record after the last one the previous run emitted.
     * <p>
     * <b>Additive: the legacy step has no restart concept at all.</b> A rerun of the report job stream takes
     * a fresh backup and reprocesses it, so nothing here reproduces a paragraph. It exists because Spring
     * Batch offers restartability and a checkpoint that was written but never honoured would be state with
     * no purpose.
     * <p>
     * The two paths position differently and both are handled. On the {@code repository} path the position
     * is already encoded in the keyset cursor by {@link #restoreRestartCursor(ExecutionContext)} - the
     * resumed browse simply starts strictly after the checkpointed key, which is <b>exact</b> because
     * {@code TRAN-ID} is the cluster key. On the {@code object-storage} path the stream must actually be
     * advanced, which {@link #skipAlreadyEmittedRecords(long, String)} does, verifying as it goes that it
     * landed on the checkpointed record.
     * <p>
     * <b>Side effects.</b> Advances the character stream on the {@code object-storage} path and writes one
     * log event; does nothing at all on a cold start.
     *
     * @throws FatalProcessingException if the stream fails while skipping
     * @throws FileAccessException if the generation holds fewer records than the checkpoint claims
     * @throws DataIntegrityException if the generation changed between runs
     */
    private void positionAfterRestart() {
        if (recordsRead <= 0L || inputSource != InputSource.OBJECT_STORAGE) {
            return;
        }
        try {
            skipAlreadyEmittedRecords(recordsRead, lastTransactionId);
        } catch (IOException failure) {
            LOG.error(ERROR_READING_MESSAGE);
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            LOG.error(displayIoStatus(ioStatus));
            throw abendException(ERROR_READING_MESSAGE, OPERATION_OPEN, failure);
        }
        LOG.info("{} resumed: skipped {} records already emitted from the resolved generation",
                LOGICAL_FILE, Long.valueOf(recordsRead));
    }

    // ====================================================================================================
    // READ. Borrowed shape: the three-way sequential-read guard of app/cbl/CBTRN02C.cbl:L345-L369.
    // ====================================================================================================

    /**
     * Reads the next record and applies the three-way sequential-read guard
     * ({@code app/cbl/CBTRN02C.cbl:L345-L369}).
     * <p>
     * The shape is preserved exactly: the read sets a status ({@code :L346}); {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} ({@code :L348}); {@code '10'} yields {@code MOVE 16} ({@code :L352});
     * anything else yields {@code MOVE 12} ({@code :L354}). The guard then either continues
     * ({@code :L357-L358}), sets {@code END-OF-FILE} to {@code 'Y'} ({@code :L360-L361}), or reports and
     * abends ({@code :L363-L366}).
     * <p>
     * <b>End of file is loop termination, not an error.</b> {@code 88 APPL-EOF VALUE 16}
     * ({@code app/cbl/CBTRN02C.cbl:L144}) is a normal outcome and nothing is thrown for it.
     * <p>
     * The {@code APPL-RESULT} arithmetic is not restated here: {@link FileStatusMapper} already implements
     * this exact nested test, so duplicating it would be a parallel mapping.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying the
     *     underlying failure as its cause when one was raised
     * @throws DataIntegrityException when a fixed-width record is malformed; propagated unwrapped so the row
     *     number and field position it carries are not buried under a file status
     */
    private Transaction getNextTransaction() {
        Throwable inputFailure = null;
        try {
            ioStatus = readNextRecord();
        } catch (IOException | DataAccessException failure) {
            // A stream reports by throwing an IOException and a relational store by throwing a
            // DataAccessException. Either is translated to the '9x' family and then RETAINED as the cause,
            // never swallowed. A DataIntegrityException raised by the decoder is deliberately NOT caught
            // here: it is already typed and already names the row and the field position, and burying it
            // under a file status would lose both.
            inputFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        // IF APPL-AOK CONTINUE (:L357-L358)
        if (applResult == FileStatusMapper.APPL_AOK) {
            return transactionRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE (:L360-L361)
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            transactionRecord = null;
            return null;
        }

        // ELSE DISPLAY ... / PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM (:L363-L366)
        LOG.error(ERROR_READING_MESSAGE);
        LOG.error(displayIoStatus(ioStatus));
        throw abendException(ERROR_READING_MESSAGE, OPERATION_READ, inputFailure);
    }

    /**
     * Performs the work behind the {@code READ} verb and reports its outcome as a COBOL file status.
     * <p>
     * Dispatch is on the configured {@link InputSource} and both branches are reachable, so neither is dead
     * code.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area,
     *     {@link #STATUS_END_OF_FILE} when the input is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when
     *     a buffered element is {@code null}, which a {@code NOT NULL} relation cannot legitimately produce
     * @throws IOException if the character stream fails on the {@code object-storage} path
     * @throws DataAccessException if the store rejects the query on the {@code repository} path
     * @throws DataIntegrityException if a fixed-width record is malformed
     */
    private String readNextRecord() throws IOException {
        return switch (inputSource) {
            case REPOSITORY -> readNextRecordFromRepository();
            case OBJECT_STORAGE -> readNextRecordFromObject();
        };
    }

    /**
     * Reads the next row from the {@code transaction} relation, refilling the page buffer when it is
     * exhausted, using a <b>keyset</b> browse.
     * <p>
     * <b>Ordering is the contract and it is fixed in the finders' names.</b> The first page comes from
     * {@link TransactionRepository#findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(String,
     * org.springframework.data.domain.Pageable)} seeded with {@link #LOW_VALUES_TRANSACTION_ID}, which is
     * the {@code MOVE LOW-VALUES} that positions a VSAM browse at the start of a cluster and is why the
     * <em>inclusive</em> finder is the correct one there. Every subsequent page comes from
     * {@link TransactionRepository#findByTransactionIdGreaterThanOrderByTransactionIdAsc(String,
     * org.springframework.data.domain.Pageable)} keyed on the last identifier of the previous page, which is
     * why the <em>exclusive</em> finder is the correct one there. Using the inclusive finder for a
     * continuation would repeat a row and using the exclusive finder for the first page would drop the
     * lowest key.
     * <p>
     * <b>Why keyset and not offset.</b> {@code TRAN-ID} is the cluster key
     * ({@code app/catlg/LISTCAT.txt:L3593-L3594}, {@code KEYLEN 16} at {@code RKP 0};
     * {@code app/jcl/TRANFILE.jcl:L53} {@code KEYS(16 0)}), so it is unique and totally ordered. A keyset
     * browse over a unique key cannot skip or repeat a row when a page boundary moves, and a restart resumes
     * exactly rather than by arithmetic on a row ordinal. <b>No bare {@code findAll()} is called anywhere</b>
     * and no repository method is added from here.
     * <p>
     * A {@code Slice} rather than a {@code Page} avoids a counting query that nothing reads: the legacy step
     * never asks the cluster how many records it holds.
     *
     * @return {@link #STATUS_SUCCESS}, {@link #STATUS_END_OF_FILE} or {@link #STATUS_PHYSICAL_IO_ERROR}
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecordFromRepository() {
        while (pageBufferIndex >= pageBuffer.size()) {
            if (!morePagesAvailable) {
                // The previous page reported no successor, so end of data is known without issuing a query
                // that is certain to come back empty. FILE STATUS '10', not an error.
                transactionRecord = null;
                return STATUS_END_OF_FILE;
            }

            final Slice<Transaction> page = pageKeyTransactionId == null
                    ? transactionRepository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                            LOW_VALUES_TRANSACTION_ID, PageRequest.of(0, pageSize))
                    : transactionRepository.findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                            pageKeyTransactionId, PageRequest.of(0, pageSize));
            pageBuffer = page.getContent();
            morePagesAvailable = page.hasNext();
            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                // An empty first page is an empty relation: a successful run with a row count of zero,
                // stated explicitly rather than inferred from silence.
                transactionRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Advance the keyset cursor to the highest key of this page, so the next fetch continues
            // strictly after it. Done here rather than per row so that a page is fetched exactly once.
            final Transaction highest = pageBuffer.get(pageBuffer.size() - 1);
            if (highest == null) {
                transactionRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            pageKeyTransactionId = highest.getTransactionId();
        }

        final Transaction next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch: every column of this relation is NOT NULL and TRAN-ID is its primary key, so
        // a null element means the result set is not what the schema promises. It is reported through the
        // status vocabulary rather than allowed to become a NullPointerException further down.
        if (next == null) {
            transactionRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        transactionRecord = next;
        return STATUS_SUCCESS;
    }

    /**
     * Reads and decodes the next {@value #RECORD_LENGTH}-character record from the generation object.
     * <p>
     * The image is obtained by {@link #readFixedWidthImage()} and decoded by
     * {@link #decodeRecord(String, long)}; the two are separate so that a stream failure and a data defect
     * cannot be confused with one another. The row number handed to the decoder is {@code recordsRead + 1},
     * making it the one-based position of the record within the generation.
     *
     * @return {@link #STATUS_SUCCESS} when a record was decoded, or {@link #STATUS_END_OF_FILE} when the
     *     object is exhausted at a record boundary
     * @throws IOException if the character stream fails
     * @throws DataIntegrityException if the record is not exactly {@value #RECORD_LENGTH} characters or
     *     carries an unrecognised overpunch or a non-digit where the copybook declares digits
     */
    private String readNextRecordFromObject() throws IOException {
        final String image = readFixedWidthImage();
        if (image == null) {
            transactionRecord = null;
            return STATUS_END_OF_FILE;
        }
        transactionRecord = decodeRecord(image, recordsRead + 1L);
        return STATUS_SUCCESS;
    }

    // ====================================================================================================
    // Fixed-width stream primitives. RECFM=FB semantics: exactly RECORD_LENGTH characters per record and NO
    // terminator at all. A terminator is nevertheless tolerated, because an ASCII fixture carries one per
    // row, and assuming either shape would break on the other. RECORD LENGTH IS PRESERVED BYTE-EXACTLY AT
    // THE OBJECT-STORAGE BOUNDARY: no re-blocking, no re-encoding, no trimming, no padding.
    // ====================================================================================================

    /**
     * Reads exactly {@value #RECORD_LENGTH} characters and consumes an optional single terminator.
     * <p>
     * <b>The charset is explicit and applied once, at the stream.</b> The bytes were decoded through
     * {@link #RECORD_CHARSET} by the {@link InputStreamReader} created in {@link #openInputSource()}, so one
     * byte is one character and every offset the copybook declares lands where it should. The image itself is
     * built with the {@code char[]} constructor of {@link String}, which takes no charset at all, so the
     * platform-default {@code new String(byte[])} overload is never reached anywhere in this class.
     * <p>
     * <b>The terminator is optional and is not assumed to be the platform separator.</b> A lone {@code \n},
     * a {@code \r\n} pair and a lone {@code \r} are each consumed, and anything else is pushed back so the
     * next record starts exactly where it should. That is what lets one implementation read both an
     * unterminated {@code RECFM=FB} image and a line-terminated ASCII fixture.
     * <p>
     * <b>Bounds are checked before anything is used.</b> Zero characters at a record boundary is a clean end
     * of data. Anything from one to {@value #RECORD_LENGTH} minus one is a truncated record, which is a data
     * defect and is reported as one rather than silently padded, silently skipped, or allowed to shift every
     * subsequent offset. The message names the row and the observed length and <b>never the record
     * content</b>.
     *
     * @return the {@value #RECORD_LENGTH}-character image, or {@code null} at a clean end of data
     * @throws IOException if the underlying stream fails
     * @throws DataIntegrityException if a partial record is present at the end of the stream
     */
    private String readFixedWidthImage() throws IOException {
        int filled = 0;
        while (filled < RECORD_LENGTH) {
            final int read = recordStream.read(recordBuffer, filled, RECORD_LENGTH - filled);
            if (read == END_OF_STREAM) {
                break;
            }
            filled += read;
        }

        if (filled == 0) {
            // End of data exactly on a record boundary: FILE STATUS '10', reported by the caller.
            return null;
        }

        if (filled != RECORD_LENGTH) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d is %d characters but app/cpy/CVTRA05Y.cpy:L2 declares RECLN = %d and "
                            + "app/proc/TRANREPT.prc:L29 declares DCB=(LRECL=%d,RECFM=FB,BLKSIZE=0); the "
                            + "resolved generation in bucket '%s' is truncated at that record and no field "
                            + "offset after character %d can be trusted",
                    LOGICAL_FILE, Long.valueOf(recordsRead + 1L), Integer.valueOf(filled),
                    Integer.valueOf(RECORD_LENGTH), Integer.valueOf(RECORD_LENGTH),
                    outputBucket, Integer.valueOf(filled)),
                    LOGICAL_FILE, DATASET_NAME);
        }

        consumeRecordTerminator();
        return new String(recordBuffer, 0, RECORD_LENGTH);
    }

    /**
     * Consumes the optional single row terminator that follows a record, leaving the stream positioned at the
     * first character of the next record.
     * <p>
     * A carriage return is stripped defensively: the generation may have been produced on a platform that
     * writes {@code \r\n}, and a terminator shape is not something to assume. Stripping it changes no field
     * geometry, because it happens strictly after {@value #RECORD_LENGTH} characters have been taken.
     *
     * @throws IOException if the underlying stream fails, or if it does not support the mark needed to push a
     *     non-terminator character back
     */
    private void consumeRecordTerminator() throws IOException {
        recordStream.mark(2);
        final int first = recordStream.read();
        if (first == END_OF_STREAM || first == LINE_FEED) {
            return;
        }
        if (first == CARRIAGE_RETURN) {
            recordStream.mark(1);
            final int second = recordStream.read();
            if (second != END_OF_STREAM && second != LINE_FEED) {
                // A lone CR terminated the row; the character just read belongs to the next record.
                recordStream.reset();
            }
            return;
        }
        // No terminator at all - the RECFM=FB case - so the character just read is the first of the next
        // record and is pushed back.
        recordStream.reset();
    }

    /**
     * Discards the given number of whole records so a resumed run continues where the previous one stopped,
     * and verifies that it landed on the expected record.
     * <p>
     * <b>This is exact on this path, because an object is immutable once written and because the generation
     * key was carried forward rather than re-resolved.</b> Skipping {@code alreadyEmitted} records positions
     * the stream on the record after the last one emitted, and the {@code TRAN-ID} of the last skipped record
     * is compared against the checkpointed identifier. A mismatch means the generation is not the one the
     * previous run read, so the resume position is wrong and rows would be silently skipped or silently
     * repeated; that is reported loudly rather than downgraded. Only bytes 1-16 of the skipped record are
     * looked at, so no amount and no card number is touched.
     *
     * @param alreadyEmitted the number of records the previous run emitted; must be positive
     * @param expectedLastTransactionId the checkpointed {@code TRAN-ID} of the last emitted record, or
     *     {@code null} when the context carried none, in which case no comparison is made
     * @throws IOException if the underlying stream fails
     * @throws FileAccessException if the generation holds fewer records than the checkpoint claims were
     *     already emitted
     * @throws DataIntegrityException if a skipped record is truncated, or if the last skipped record does not
     *     carry the checkpointed identifier
     */
    private void skipAlreadyEmittedRecords(final long alreadyEmitted,
            final String expectedLastTransactionId) throws IOException {
        String lastSkippedImage = null;
        for (long skipped = 0L; skipped < alreadyEmitted; skipped++) {
            lastSkippedImage = readFixedWidthImage();
            if (lastSkippedImage == null) {
                throw new FileAccessException(String.format(Locale.ROOT,
                        "%s restart cannot resume: the resolved generation in bucket '%s' holds only %d "
                                + "records but the execution context reports %d already emitted, so this is "
                                + "not the generation the previous run read",
                        LOGICAL_FILE, outputBucket, Long.valueOf(skipped),
                        Long.valueOf(alreadyEmitted)),
                        STATUS_FILE_UNAVAILABLE, LOGICAL_FILE, OPERATION_OPEN);
            }
        }

        if (expectedLastTransactionId == null || lastSkippedImage == null) {
            return;
        }

        final String observed = fixedWidthField(lastSkippedImage, TRANSACTION_ID_START, TRANSACTION_ID_END,
                "TRAN-ID", alreadyEmitted);
        if (!observed.equals(expectedLastTransactionId)) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s restart cannot resume: record %d of the resolved generation in bucket '%s' does "
                            + "not carry the TRAN-ID the execution context checkpointed, so the generation "
                            + "changed between runs and resuming would skip or repeat records",
                    LOGICAL_FILE, Long.valueOf(alreadyEmitted), outputBucket),
                    CONTEXT_KEY_LAST_TRANSACTION_ID, DATASET_NAME);
        }
    }

    // ====================================================================================================
    // POSITION-AWARE DECODE OF app/cpy/CVTRA05Y.cpy. Every extraction goes through fixedWidthField, so every
    // one of them is bounds checked against the actual image length before any substring is taken.
    // ====================================================================================================

    /**
     * Decodes one {@value #RECORD_LENGTH}-character {@code TRAN-RECORD} image into an entity.
     * <p>
     * The thirteen constructor arguments are supplied in the COBOL field order of
     * {@code app/cpy/CVTRA05Y.cpy:L5-L17}. The 20-byte {@code FILLER} at {@code :L18} carries no data and is
     * deliberately not passed.
     * <p>
     * <b>Nothing is trimmed, padded, upper-cased, re-signed, rounded or reformatted.</b> Every right-padded
     * {@code PIC X(n)} field keeps its trailing spaces exactly, which is load-bearing in two places:
     * {@code TRAN-PROC-TS} may legitimately be 26 spaces and must survive as 26 spaces - not {@code null},
     * not empty, not trimmed, not an epoch - and {@code TRAN-SOURCE} must survive at its full width of ten.
     * {@code Transaction} re-checks every width against its picture clause, so a defect in this method
     * surfaces as a named property rather than as a constraint violation at flush time.
     * <p>
     * <b>{@code TRAN-SOURCE} is plain text, not a closed domain.</b>
     * {@code com.cardemo.model.enums.TransactionSource} is neither imported nor referenced by this class:
     * validating a ten-character text column against a two-constant enum would reject values the legacy
     * cluster legitimately holds.
     * <p>
     * This method is a pure function of its arguments: it reads and writes no field of this instance.
     *
     * @param image the {@value #RECORD_LENGTH}-character record image; must not be {@code null}
     * @param rowNumber the one-based position of this record within the generation, used only as exception
     *     context
     * @return the decoded transaction, never {@code null}
     * @throws DataIntegrityException if the image is not {@value #RECORD_LENGTH} characters, if a field the
     *     copybook declares as digits holds a non-digit, or if the amount carries an unrecognised terminal
     *     overpunch character
     * @throws IllegalArgumentException if a decoded value is outside the range its picture clause admits, as
     *     {@code Transaction} determines
     */
    private static Transaction decodeRecord(final String image, final long rowNumber) {
        return new Transaction(
                fixedWidthField(image, TRANSACTION_ID_START, TRANSACTION_ID_END, "TRAN-ID", rowNumber),
                fixedWidthField(image, TYPE_CODE_START, TYPE_CODE_END, "TRAN-TYPE-CD", rowNumber),
                unsignedInteger(
                        fixedWidthField(image, CATEGORY_CODE_START, CATEGORY_CODE_END, "TRAN-CAT-CD",
                                rowNumber),
                        "TRAN-CAT-CD PIC 9(04)", CATEGORY_CODE_START, rowNumber),
                fixedWidthField(image, TRANSACTION_SOURCE_START, TRANSACTION_SOURCE_END, "TRAN-SOURCE",
                        rowNumber),
                fixedWidthField(image, DESCRIPTION_START, DESCRIPTION_END, "TRAN-DESC", rowNumber),
                decodeSignedTransactionAmount(
                        fixedWidthField(image, AMOUNT_START, AMOUNT_END, "TRAN-AMT", rowNumber), rowNumber),
                unsignedLong(
                        fixedWidthField(image, MERCHANT_ID_START, MERCHANT_ID_END, "TRAN-MERCHANT-ID",
                                rowNumber),
                        "TRAN-MERCHANT-ID PIC 9(09)", MERCHANT_ID_START, rowNumber),
                fixedWidthField(image, MERCHANT_NAME_START, MERCHANT_NAME_END, "TRAN-MERCHANT-NAME",
                        rowNumber),
                fixedWidthField(image, MERCHANT_CITY_START, MERCHANT_CITY_END, "TRAN-MERCHANT-CITY",
                        rowNumber),
                fixedWidthField(image, MERCHANT_ZIP_START, MERCHANT_ZIP_END, "TRAN-MERCHANT-ZIP", rowNumber),
                fixedWidthField(image, CARD_NUMBER_START, CARD_NUMBER_END, "TRAN-CARD-NUM", rowNumber),
                fixedWidthField(image, ORIG_TS_START, ORIG_TS_END, "TRAN-ORIG-TS", rowNumber),
                fixedWidthField(image, PROC_TS_START, PROC_TS_END, "TRAN-PROC-TS", rowNumber));
    }

    /**
     * Extracts one field by its one-based inclusive copybook offsets, after bounds-checking them against the
     * actual image length.
     * <p>
     * <b>This is the only place a substring of a record image is taken</b>, and the only place the copybook's
     * one-based inclusive offsets are converted to Java's zero-based half-open form. Both properties are
     * deliberate: a single conversion site cannot disagree with itself, and a single bounds check cannot be
     * forgotten at one of thirteen call sites. The check is against the observed length rather than against
     * {@value #RECORD_LENGTH}, so it holds even if a caller ever passes a shorter image.
     * <p>
     * Trailing spaces are preserved exactly: there is no {@code trim}, no {@code strip} and no normalisation
     * to {@code null} or to the empty string anywhere in this class's decode path.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param image the record image; must not be {@code null}
     * @param startOneBased the first byte of the field, one-based and inclusive
     * @param endOneBased the last byte of the field, one-based and inclusive
     * @param cobolField the copybook field name, used only as exception context
     * @param rowNumber the one-based position of the record within the generation, used only as exception
     *     context
     * @return the field text, exactly {@code endOneBased - startOneBased + 1} characters long
     * @throws DataIntegrityException if the image is too short to contain the declared field, which the
     *     message reports together with the row, the field and the offsets - never the content
     */
    private static String fixedWidthField(final String image, final int startOneBased, final int endOneBased,
            final String cobolField, final long rowNumber) {
        final int length = image.length();
        if (endOneBased > length) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d is %d characters, so field %s at bytes %d-%d declared by "
                            + "app/cpy/CVTRA05Y.cpy cannot be read; a %s record is %d characters",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(length), cobolField,
                    Integer.valueOf(startOneBased), Integer.valueOf(endOneBased), DATASET_NAME,
                    Integer.valueOf(RECORD_LENGTH)),
                    cobolField, DATASET_NAME);
        }
        return image.substring(startOneBased - 1, endOneBased);
    }

    /**
     * Decodes the trailing-sign zoned-decimal {@code TRAN-AMT} field of {@code app/cpy/CVTRA05Y.cpy} into a
     * {@link BigDecimal}.
     * <p>
     * <b>THIS CLASS IS THE CANONICAL OWNER OF THIS DECODE FOR THE {@code CVTRA05Y} LAYOUT.</b> The method is
     * package-private precisely so that the planned
     * {@code com.cardemo.batch.readers.CombinedTransactionReader} - which does not exist at this commit and
     * which reads the same 350-byte layout out of a concatenated input
     * ({@code app/jcl/COMBTRAN.jcl:L43-L44}) - can <b>reuse</b> it rather than re-implement it. It is
     * deliberately <i>not</i> promoted to a class of its own: one codec per record layout, colocated with the
     * reader that owns that layout, is this repository's convention, and a separate codec class would also
     * exceed this package's file budget. {@link DailyTransactionReader} owns the identically-shaped
     * {@code app/cpy/CVTRA06Y.cpy} the same way.
     * <p>
     * <b>It is {@code static} because it is a pure function with no state, and for no other reason.</b> It
     * reads no field, writes no field and touches no collaborator; the row number is a parameter exactly so
     * that a failure can be located without the method consulting instance state. A reviewer should not read
     * the {@code static} as global mutable state, because there is none anywhere in this class.
     * <p>
     * <b>The field.</b> {@code PIC S9(09)V99} at bytes 133-143 ({@code app/cpy/CVTRA05Y.cpy:L10}), eleven
     * characters with no separate sign byte, the sign overpunched onto byte 143. <b>It is the only signed
     * field in the layout</b>, and this method is called from exactly one place on exactly those eleven
     * characters.
     * <p>
     * <b>The decode table.</b> <code>&#123;</code> is {@code +0}; {@code A} through {@code I} are {@code +1}
     * to {@code +9}; <code>&#125;</code> is {@code -0}; {@code J} through {@code R} are {@code -1} to
     * {@code -9}. A plain digit in the terminal position is accepted and read as positive, which is what an
     * unsigned {@code MOVE} leaves there.
     * <p>
     * <b>DECODING IS POSITION AWARE, DRIVEN FROM THE PICTURE CLAUSES, AND A GLOBAL TEXT REPLACEMENT WOULD BE
     * WRONG.</b> The same letters occur legitimately inside text fields, so a pass that replaced them
     * wherever it found them would corrupt records wholesale. The proof is measured on the fixture that
     * shares this exact geometry: across the 300 rows of {@code app/data/ASCII/dailytran.txt}, the
     * merchant-name field at bytes 153-202 - a pure {@code PIC X(50)} text field - contains the complete set
     * {@code ABCDEFGHIJKLMNOPQR}, every one of the eighteen letter codes, so <b>300 of 300 rows would be
     * corrupted</b>. A sibling layout corroborates it independently: bytes 103-112 of
     * {@code app/data/ASCII/acctdata.txt} hold the literal {@code A000000000} in all 50 rows, because
     * {@code ACCT-ADDR-ZIP} is {@code PIC X(10)} text whose leading {@code A} a global pass would turn into a
     * digit. Accordingly this method is applied at bytes 133-143 and nowhere else: it never scans, never
     * searches and never replaces.
     * <p>
     * <b>How a decode resolves.</b> For this exact eleven-character {@code S9(09)V99} geometry the first ten
     * characters are digits and the eleventh carries both the sign and the final digit, so
     * {@code dddddddddd} followed by {@code G} yields a positive value whose last digit is 7,
     * {@code dddddddddd} followed by <code>&#125;</code> yields a negative value whose last digit is 0, and
     * {@code dddddddddd} followed by {@code H} yields a positive value whose last digit is 8. The implied
     * decimal point sits two digits from the right in every case. The sibling geometries decode on the same
     * rule at their own widths: twelve characters for the {@code S9(10)V99} money fields of
     * {@code app/data/ASCII/acctdata.txt}, eleven for {@code app/data/ASCII/tcatbal.txt}, and six for the
     * {@code S9(04)V99} rate tail of {@code app/data/ASCII/discgrp.txt}.
     * <p>
     * <b>No normalisation of any kind is applied.</b> There is no absolute value, no sign stripping and no
     * unconditional negation: the sign is applied if and only if the terminal character encodes one. Negative
     * amounts are real and drive the cycle-debit branch downstream -
     * {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to {@code ACCT-CURR-CYC-DEBIT}, which is
     * exactly why the over-limit formula subtracts that accumulator - so normalising them would silently
     * disable a whole branch of the parity comparison.
     * <p>
     * <b>The scale is exactly {@value #AMOUNT_SCALE} by construction, and the invariant is asserted.</b>
     * {@link BigDecimal#movePointLeft(int)} applied to a scale-zero integer yields precisely that scale and
     * {@link BigDecimal#negate()} preserves it. The assertion compares {@link BigDecimal#scale()}, an
     * {@code int}; <b>{@code equals} is never invoked on a {@code BigDecimal}</b> in this class, and there is
     * no {@code float} and no {@code double} anywhere in this file.
     * <p>
     * <b>No rounding occurs, so no {@link java.math.RoundingMode} is referenced.</b> The field carries nine
     * integer digits and two decimal digits and the decode is an exact decimal shift; there is nothing to
     * round. The domain bound is structural for the same reason: eleven digits at scale two cannot exceed the
     * {@code 999999999.99} that {@code PIC S9(09)V99} admits, so a range comparison here would be
     * unreachable code, and {@code Transaction} re-checks the bound in any case.
     * <p>
     * <b>Negative zero cannot be represented.</b> <code>&#125;</code> denotes {@code -0} and
     * {@code BigDecimal} has no signed zero, so {@code 00000000000}<code>&#125;</code> decodes to
     * {@code 0.00}. That is inherent to decimal arithmetic and to the {@code NUMERIC(11,2)} column rather
     * than a defect here.
     *
     * @param rawField the eleven characters at bytes 133-143; must not be {@code null}
     * @param rowNumber the one-based position of the record within its input, used only as exception context
     * @return the amount at scale exactly {@value #AMOUNT_SCALE}, never {@code null}
     * @throws DataIntegrityException if the field is not eleven characters, if any of its first ten
     *     characters is not a digit, or if its terminal character is neither a digit nor a member of the
     *     overpunch table; the message names the row and the byte position and never the record content
     * @throws IllegalStateException if the decoded value does not carry scale {@value #AMOUNT_SCALE}, which
     *     the arithmetic makes impossible and which is asserted rather than assumed
     */
    static BigDecimal decodeSignedTransactionAmount(final String rawField, final long rowNumber) {
        final int width = AMOUNT_END - AMOUNT_START + 1;
        if (rawField.length() != width) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d field TRAN-AMT is %d characters but app/cpy/CVTRA05Y.cpy:L10 declares "
                            + "PIC S9(09)V99 at bytes %d-%d, which is %d characters",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(rawField.length()),
                    Integer.valueOf(AMOUNT_START), Integer.valueOf(AMOUNT_END), Integer.valueOf(width)),
                    "TRAN-AMT PIC S9(09)V99", DATASET_NAME);
        }

        // The terminal character carries BOTH the sign and the final digit. It is read from byte AMOUNT_END
        // and from nowhere else: no scan, no search and no replacement over the record.
        final char overpunch = rawField.charAt(width - 1);
        final boolean negative = overpunch == OVERPUNCH_NEGATIVE_ZERO
                || (overpunch >= OVERPUNCH_NEGATIVE_FIRST && overpunch <= OVERPUNCH_NEGATIVE_LAST);

        final char finalDigit;
        if (overpunch >= DIGIT_ZERO && overpunch <= DIGIT_NINE) {
            finalDigit = overpunch;
        } else if (overpunch == OVERPUNCH_POSITIVE_ZERO || overpunch == OVERPUNCH_NEGATIVE_ZERO) {
            finalDigit = DIGIT_ZERO;
        } else if (overpunch >= OVERPUNCH_POSITIVE_FIRST && overpunch <= OVERPUNCH_POSITIVE_LAST) {
            finalDigit = (char) (DIGIT_ONE + (overpunch - OVERPUNCH_POSITIVE_FIRST));
        } else if (overpunch >= OVERPUNCH_NEGATIVE_FIRST && overpunch <= OVERPUNCH_NEGATIVE_LAST) {
            finalDigit = (char) (DIGIT_ONE + (overpunch - OVERPUNCH_NEGATIVE_FIRST));
        } else {
            // Untrusted input, checked rather than trusted. The offending character is reported by POSITION,
            // not by value, so nothing of the record reaches the message.
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d carries an unrecognised trailing-sign overpunch character at byte %d; "
                            + "app/cpy/CVTRA05Y.cpy:L10 declares PIC S9(09)V99 there, whose terminal "
                            + "position admits a digit, an opening or closing brace, or one of the letters "
                            + "%c-%c or %c-%c",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(AMOUNT_END),
                    Character.valueOf(OVERPUNCH_POSITIVE_FIRST), Character.valueOf(OVERPUNCH_POSITIVE_LAST),
                    Character.valueOf(OVERPUNCH_NEGATIVE_FIRST), Character.valueOf(OVERPUNCH_NEGATIVE_LAST)),
                    "TRAN-AMT PIC S9(09)V99", DATASET_NAME);
        }

        final StringBuilder digits = new StringBuilder(width);
        for (int index = 0; index < width - 1; index++) {
            final char current = rawField.charAt(index);
            if (current < DIGIT_ZERO || current > DIGIT_NINE) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s record %d carries a non-digit at byte %d, inside TRAN-AMT at bytes %d-%d, which "
                                + "app/cpy/CVTRA05Y.cpy:L10 declares as PIC S9(09)V99",
                        LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(AMOUNT_START + index),
                        Integer.valueOf(AMOUNT_START), Integer.valueOf(AMOUNT_END)),
                        "TRAN-AMT PIC S9(09)V99", DATASET_NAME);
            }
            digits.append(current);
        }
        digits.append(finalDigit);

        final BigDecimal magnitude = new BigDecimal(digits.toString()).movePointLeft(AMOUNT_SCALE);
        final BigDecimal decoded = negative ? magnitude.negate() : magnitude;

        // Asserted, not assumed: the column is NUMERIC(11,2) and the parity comparison is made on two
        // decimal places, so a scale other than two would be a defect worth failing on.
        if (decoded.scale() != AMOUNT_SCALE) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s record %d decoded TRAN-AMT at scale %d; PIC S9(09)V99 and column tran_amt "
                            + "NUMERIC(11,2) both require scale %d",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(decoded.scale()),
                    Integer.valueOf(AMOUNT_SCALE)));
        }
        return decoded;
    }

    /**
     * Decodes an unsigned {@code PIC 9(n)} display field into an {@link Integer}.
     * <p>
     * <b>No overpunch decoding is applied here, because the picture clause is unsigned.</b> That is the
     * position-aware rule in its plainest form: {@code TRAN-CAT-CD} is {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:L7}, so every one of its four characters must be a digit, and a letter
     * there is a defect rather than a sign.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field, used to report an offending position
     * @param rowNumber the one-based position of the record within the generation
     * @return the decoded value, never {@code null}
     * @throws DataIntegrityException if any character is not a digit; the message names the row and the byte
     *     position and never the record content
     * @throws ArithmeticException if the decoded value overflows an {@code int}, which a four-digit picture
     *     clause cannot produce and which is therefore checked rather than assumed away
     */
    private static Integer unsignedInteger(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        return Integer.valueOf(
                Math.toIntExact(unsignedDigits(rawField, pictureClause, startOneBased, rowNumber)));
    }

    /**
     * Decodes an unsigned {@code PIC 9(n)} display field into a {@link Long}.
     * <p>
     * Used for {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11}, whose nine
     * digits exceed no {@code long} bound. As with
     * {@link #unsignedInteger(String, String, int, long)}, no overpunch decoding is applied, because the
     * picture clause is unsigned.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field, used to report an offending position
     * @param rowNumber the one-based position of the record within the generation
     * @return the decoded value, never {@code null}
     * @throws DataIntegrityException if any character is not a digit
     */
    private static Long unsignedLong(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        return Long.valueOf(unsignedDigits(rawField, pictureClause, startOneBased, rowNumber));
    }

    /**
     * Validates that every character of an unsigned display field is a digit and returns its value.
     * <p>
     * The accumulation is done digit by digit rather than through {@link Long#parseLong(String)} so that an
     * offending character can be reported by its <b>byte position within the record</b>, which is the context
     * an operator needs and which a parse failure does not supply. A leading-zero image such as {@code 0001}
     * is the normal case for a COBOL display field and needs no special handling.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field within the record
     * @param rowNumber the one-based position of the record within the generation
     * @return the non-negative decoded value
     * @throws DataIntegrityException if any character is not a digit
     */
    private static long unsignedDigits(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        long value = 0L;
        for (int index = 0; index < rawField.length(); index++) {
            final char current = rawField.charAt(index);
            if (current < DIGIT_ZERO || current > DIGIT_NINE) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s record %d carries a non-digit at byte %d, inside %s; an unsigned COBOL display "
                                + "field admits digits only and carries no overpunch sign",
                        LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(startOneBased + index),
                        pictureClause),
                        pictureClause, DATASET_NAME);
            }
            value = (value * 10L) + (current - DIGIT_ZERO);
        }
        return value;
    }

    // ====================================================================================================
    // CLOSE. Borrowed shape: app/cbl/CBTRN02C.cbl:L582-L598.
    // ====================================================================================================

    /**
     * Releases the input and applies the same two-way guard the corpus applies to a {@code CLOSE}
     * ({@code app/cbl/CBTRN02C.cbl:L582-L598}).
     * <p>
     * <b>What stands in for {@code CLOSE}.</b> Releasing the page buffer on the {@code repository} path and
     * closing the character stream on the {@code object-storage} one. No store round trip is needed and none
     * is made: a read-only pass holds nothing that requires committing. The teardown is nevertheless guarded
     * exactly as the source guards its close, so the failure branch stays reachable for any fault raised while
     * releasing the stream rather than being unreachable by construction.
     * <p>
     * <b>Idempotent.</b> Calling it twice is harmless, which matters because Spring Batch may close a stream
     * it failed to open. The stream reference is cleared before the guard runs, so a second call has nothing
     * left to release.
     * <p>
     * <b>Side effects.</b> Clears the buffer, the stream, the record area, the resolved generation key and
     * the open flag. Writes two log events only when the close fails.
     *
     * @throws FatalProcessingException if releasing the input raises a fault
     */
    private void closeBackupGeneration() {
        // MOVE 8 TO APPL-RESULT (:L583).
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        Throwable closeFailure = null;
        final BufferedReader closing = recordStream;
        recordStream = null;
        recordBuffer = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        transactionRecord = null;
        resolvedGenerationObjectKey = null;
        fileOpen = false;
        try {
            // CLOSE (:L584)
            if (closing != null) {
                closing.close();
            }
            ioStatus = STATUS_SUCCESS;
        } catch (IOException | RuntimeException failure) {
            closeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF status = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 (:L585-L589).
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE (:L590-L591)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY ... / PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM (:L593-L596)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            throw abendException(ERROR_CLOSING_MESSAGE, OPERATION_CLOSE, closeFailure);
        }
    }

    // ====================================================================================================
    // ABEND and STATUS RENDERING. Borrowed shapes: app/cbl/CBTRN02C.cbl:L707-L711 and :L714-L731.
    // ====================================================================================================

    /**
     * Builds the abend that terminates the step, reproducing {@code 9999-ABEND-PROGRAM}
     * ({@code app/cbl/CBTRN02C.cbl:L707-L711}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L708}), zeroes {@code TIMING} ({@code :L709}),
     * moves {@code 999} into {@code ABCODE} ({@code :L710}) and calls the Language Environment abend service
     * ({@code :L711}). The Java counterpart carries the full {@code app/cpy/CSMSG02Y.cpy} abend payload:
     * abend code {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, the culprit
     * {@value #ABEND_CULPRIT}, the failing operation as the reason, and the diagnostic as the message.
     * Process return code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is what
     * the job's status mapping derives from this exception; it is not set here, because a reader does not own
     * the process exit code.
     * <p>
     * {@code TIMING} has no counterpart: it is a Language Environment parameter selecting whether a dump is
     * taken, and there is no dump facility to select. The exception carries the stack trace that replaces it.
     * <p>
     * <b>The exception is returned rather than thrown</b>, so that every call site reads
     * {@code throw abendException(...)}. That keeps the compiler's reachability analysis honest: a method that
     * ends in a {@code throw} needs no unreachable trailing {@code return}, which {@code -Xlint:all -Werror}
     * would otherwise have to be argued with.
     * <p>
     * The message names the logical file, the operation and the rendered status. It <b>never</b> names a card
     * number, an amount or any record content.
     *
     * @param message the diagnostic that preceded the abend, used as both the reason and part of the abend
     *     message so the failing operation is identifiable from either field
     * @param operation the attempted operation, one of {@value #OPERATION_OPEN}, {@value #OPERATION_READ} or
     *     {@value #OPERATION_CLOSE}
     * @param cause the throwable that provoked the abend, or {@code null} when the status alone identified the
     *     fault; always attached when present, so the root cause is never lost
     * @return the exception to throw, never {@code null}
     */
    private FatalProcessingException abendException(final String message, final String operation,
            final Throwable cause) {
        // DISPLAY 'ABENDING PROGRAM' (:L708)
        LOG.error(ABENDING_PROGRAM_MESSAGE);

        // MOVE 0 TO TIMING (:L709) / MOVE 999 TO ABCODE (:L710) / CALL 'CEE3ABD'. (:L711)
        return new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                message,
                String.format(Locale.ROOT, "%s (%s, %s, %s)",
                        message, DATASET_NAME, operation, displayIoStatus(ioStatus)),
                cause);
    }

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBTRN02C.cbl:L714-L731}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is {@code '9'}
     * ({@code :L715-L716}), byte one is copied into position one and byte two is widened into three digits
     * ({@code :L717-L720}). Otherwise the field is set to {@code '0000'} and the two status characters are
     * overlaid at positions three and four ({@code :L723-L724}). Both branches emit
     * {@code 'FILE STATUS IS: NNNN'} followed by the four rendered characters ({@code :L721}, {@code :L725}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The
     * {@code NNNN} is part of the constant text and the four rendered characters follow it, so status
     * {@code '23'} renders as {@code FILE STATUS IS: NNNN0023} and never as {@code FILE STATUS IS: 0023}.
     * Substituting the digits into the {@code NNNN} would be a parity break, and the parity comparison is made
     * on the emitted line.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate: the identical paragraph
     * recurs across the batch corpus and {@link FileStatusMapper#displayIoStatus(String)} is the single
     * implementation of it, with {@link FileStatus#DISPLAY_MESSAGE_PREFIX} the single definition of the
     * literal. Re-deriving either here would be a parallel mapping. It is retained rather than inlined so the
     * paragraph stays individually traceable.
     * <p>
     * It is a pure function of its argument: it reads and writes no field of this instance.
     *
     * @param fileStatus the raw status, ordinarily two characters, and tolerated when {@code null}, shorter or
     *     longer, exactly as a COBOL {@code MOVE} into a two-byte group tolerates a mismatched sending field
     * @return the complete legacy line, never {@code null}, always 24 characters: the 20-character prefix
     *     followed by exactly four rendered characters
     */
    private String displayIoStatus(final String fileStatus) {
        return fileStatusMapper.displayIoStatus(fileStatus);
    }

    // ====================================================================================================
    // RESTART SUPPORT and CONSTRUCTION-TIME VALIDATION. Every validator is private static, so the constructor
    // can call it without invoking an overridable method: that would publish a partially constructed
    // reference, which -Xlint:all -Werror reports as this-escape, and the class cannot be final because the
    // step scope proxies by subclassing.
    // ====================================================================================================

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting records.
     * <p>
     * <b>The resume is exact on both paths, and on the {@code repository} path it is exact because
     * {@code TRAN-ID} is the cluster key.</b> {@code app/catlg/LISTCAT.txt:L3593-L3594} records
     * {@code KEYLEN 16} at {@code RKP 0} and {@code app/jcl/TRANFILE.jcl:L53} declares {@code KEYS(16 0)}, so
     * the identifier is unique and totally ordered; the keyset browse simply resumes strictly after it, and no
     * row can be skipped or repeated even if the relation changed between runs. On the
     * {@code object-storage} path {@link #skipAlreadyEmittedRecords(long, String)} advances the stream and
     * verifies the landing record, which is exact because an object is immutable once written <b>and</b>
     * because the generation key is carried forward rather than re-resolved.
     * <p>
     * The generation key itself is not restored here: {@link #resolveGenerationObjectKey(ExecutionContext)}
     * owns that, so there is exactly one place where a generation is chosen.
     * <p>
     * A non-positive checkpoint is ignored and the read starts from the beginning, which is the correct reading
     * of a checkpoint written before any record was emitted. A checkpoint that carries a row count but no
     * identifier is also handled: the row count still positions the {@code object-storage} path, and the
     * {@code repository} path restarts from the beginning rather than guessing a key.
     *
     * @param executionContext the step execution context, already known to contain the record-count key
     */
    private void restoreRestartCursor(final ExecutionContext executionContext) {
        final long checkpointed = executionContext.getLong(CONTEXT_KEY_RECORDS_READ, 0L);
        if (checkpointed <= 0L) {
            return;
        }

        recordsRead = checkpointed;
        lastTransactionId = executionContext.containsKey(CONTEXT_KEY_LAST_TRANSACTION_ID)
                ? blankToNull(executionContext.getString(CONTEXT_KEY_LAST_TRANSACTION_ID, ""))
                : null;

        // The keyset cursor IS the resume position on the repository path: an exclusive continuation from the
        // last emitted key. Left null when no identifier was checkpointed, which restarts that path from the
        // beginning rather than seeking to a key that was never recorded.
        pageKeyTransactionId = lastTransactionId;

        // The checkpointed transaction identifier is reported as present or absent, never by value: the
        // record count above IS the restart position, and the identifier only corroborates it.
        LOG.info("Resuming {} read after {} records; source={} checkpointedKey={}",
                LOGICAL_FILE, Long.valueOf(recordsRead), inputSource,
                lastTransactionId == null ? "absent" : "present");
    }

    /**
     * Resolves and validates the configured input selector.
     * <p>
     * The value is untrusted configuration, so it is checked rather than trusted. It is accepted
     * case-insensitively and with either a hyphen or an underscore, because {@code object-storage} is the
     * natural spelling in a YAML profile while {@code OBJECT_STORAGE} is the natural spelling of the constant.
     * {@link Locale#ROOT} is used for the case fold so the result cannot depend on the host locale: a Turkish
     * default locale would otherwise fold {@code i} to a dotless capital and make {@code repository}
     * unrecognisable.
     *
     * @param configured the raw configured value
     * @return the selected path, never {@code null}
     * @throws IllegalArgumentException if the value is {@code null}, blank, or names neither path
     */
    private static InputSource requireInputSource(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be either 'repository' or 'object-storage' but was blank; the default is "
                            + "'repository', so remove the key rather than emptying it",
                    PROPERTY_SOURCE));
        }
        final String normalised = configured.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return InputSource.valueOf(normalised);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be either 'repository' or 'object-storage'; '%s' names neither. 'repository' "
                            + "browses the transaction relation in ascending TRAN-ID order and "
                            + "'object-storage' decodes the 350-byte images from the resolved %s generation",
                    PROPERTY_SOURCE, configured, DATASET_NAME), unknown);
        }
    }

    /**
     * Validates the injected page size.
     *
     * @param pageSize the configured value
     * @return {@code pageSize}, unchanged
     * @throws IllegalArgumentException if {@code pageSize} is less than one, because a page of zero or fewer
     *     rows could never advance the browse
     */
    private static int requirePositivePageSize(final int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be at least 1 but was %d; a non-positive page cannot advance the sequential "
                            + "browse of %s",
                    PROPERTY_PAGE_SIZE, Integer.valueOf(pageSize), DATASET_NAME));
        }
        return pageSize;
    }

    /**
     * Validates and normalises the configured generation key prefix.
     * <p>
     * Surrounding whitespace is stripped, because a YAML value can pick it up and a prefix with a trailing
     * space addresses a different key space. A blank value is refused outright: it would list the whole bucket
     * and could select a generation of an entirely different base, which is a silent parity break rather than
     * a visible failure.
     *
     * @param configured the raw configured value
     * @return the prefix with surrounding whitespace removed, never blank
     * @throws IllegalArgumentException if the value is {@code null} or blank
     */
    private static String requireGenerationPrefix(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must name the key prefix under which %s generations are written and must not be "
                            + "blank, because a blank prefix would list the whole bucket and could select a "
                            + "generation of a different base; the default is '%s'",
                    PROPERTY_GENERATION_PREFIX, DATASET_NAME, DEFAULT_GENERATION_PREFIX));
        }
        final String stripped = configured.strip();

        // L-04: object storage has no directories, so a prefix match is a plain string match. Without a
        // trailing separator 'gdg/transact-bkup' also matches 'gdg/transact-bkup-shadow', and a generation of
        // that unrelated base could be selected as the greatest key - silently reporting on the wrong data.
        // Exactly one separator is appended, so a value that already ends in one is not doubled into a key
        // segment with an empty name.
        return stripped.endsWith(KEY_SEPARATOR) ? stripped : stripped + KEY_SEPARATOR;
    }

    /**
     * Validates the configured generation bucket against the selected path.
     * <p>
     * The bucket is required <b>only</b> on the {@code object-storage} path, and it is required <b>at
     * construction</b> rather than at the first read, so a misconfiguration fails before any work is done. On
     * the {@code repository} path an absent value is legitimate and yields an empty string: the default path
     * must carry no cloud prerequisite, because assuming one would be exactly the environment-specific
     * assumption the repository-hygiene standard rules out.
     * <p>
     * The key itself is declared once, in {@code src/main/resources/application.yml}, as
     * {@code ${CARDDEMO_S3_BATCH_OUTPUT_BUCKET}} with no literal default, so in any context that loads that
     * file an absent environment variable already fails the refresh. The empty default here can therefore only
     * take effect in a context that deliberately omits the key, such as a unit test.
     *
     * @param configured the raw configured value, permitted to be {@code null} or blank on the
     *     {@code repository} path
     * @param selected the resolved input path
     * @return the bucket name with surrounding whitespace removed, or the empty string when none is configured
     *     and none is needed
     * @throws IllegalArgumentException if the {@code object-storage} path is selected with no bucket
     */
    private static String requireOutputBucket(final String configured, final InputSource selected) {
        final String normalised = configured == null ? "" : configured.strip();
        if (selected == InputSource.OBJECT_STORAGE && normalised.isEmpty()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be configured with a non-blank value when %s is 'object-storage'; set %s. The "
                            + "bucket is versioned and is provisioned idempotently by "
                            + "localstack-init/init-aws.sh",
                    PROPERTY_OUTPUT_BUCKET, PROPERTY_SOURCE, ENV_OUTPUT_BUCKET));
        }
        return normalised;
    }

    /**
     * Normalises an optional <b>machine-written</b> value to {@code null} when it carries no value, and
     * returns it <b>byte-for-byte unchanged</b> otherwise.
     * <p>
     * A {@code null} and an all-whitespace value both mean "absent" for the three optional values this class
     * reads - the promoted generation key, a checkpointed generation key and a checkpointed {@code TRAN-ID} -
     * so collapsing them here means each consumer tests one condition rather than two.
     * <p>
     * <b>It deliberately does not strip, and that is a correctness requirement rather than a preference.</b>
     * All three values are written by a machine and read back for exact comparison, unlike the configuration
     * values of {@link #requireGenerationPrefix(String)} and {@link #requireOutputBucket(String, InputSource)}
     * which a human types into a profile and which are stripped at those sites. Two consequences make the
     * distinction load-bearing:
     * <ul>
     *   <li>A checkpointed {@code TRAN-ID} is a {@code CHAR(16)} value that may legitimately carry trailing
     *       spaces, and {@link #skipAlreadyEmittedRecords(long, String)} compares it against the raw bytes
     *       1-16 of the record it lands on. Stripping the checkpoint would make that comparison fail on a
     *       generation that had not changed at all, aborting a restart that was perfectly valid.</li>
     *   <li>An object key is opaque and a key differing only in surrounding whitespace is a different key, so
     *       altering one carried forward from an earlier step would break the very
     *       {@code (+1)}-written-then-{@code (+1)}-read contract this class exists to honour.</li>
     * </ul>
     * <p>
     * This method is a pure function of its argument.
     *
     * @param value the raw value, which may be {@code null}, empty or all whitespace
     * @return the value exactly as supplied, or {@code null} when it was {@code null}, empty or all whitespace
     */
    private static String blankToNull(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /**
     * Extracts the two-character code of a {@link FileStatus} that is expected to be an exact value rather
     * than a family, so the literals this class compares against derive from the single definition of the
     * status vocabulary instead of being restated as string constants.
     *
     * @param status the status constant, expected to be an exact value
     * @return its two-character code
     * @throws IllegalStateException if {@code status} is a family and exposes no exact code, which would mean
     *     the enum contract had changed underneath this class
     */
    private static String requireExactCode(final FileStatus status) {
        return status.code().orElseThrow(() -> new IllegalStateException(String.format(Locale.ROOT,
                "FileStatus.%s must expose an exact two-character code; it reports itself as a family",
                status.name())));
    }
}
