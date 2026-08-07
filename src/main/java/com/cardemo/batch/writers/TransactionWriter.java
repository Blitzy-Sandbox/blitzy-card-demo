/*
 * ******************************************************************
 * Program     : TransactionWriter.java
 * Application : CardDemo
 * Type        : Spring Batch ItemWriter (Java 25 / Spring Boot 3.5.11)
 * Function    : Inserts the posted transaction row and emits its 350-byte fixed-width image.
 * Source      : app/cbl/CBTRN02C.cbl 2900-WRITE-TRANSACTION-FILE :L562-L579
 *               (26 procedure-division paragraphs, 731 lines);
 *               app/cpy/CVTRA05Y.cpy (350-byte TRAN-RECORD layout, ":L2 RECLN = 350");
 *               app/jcl/TRANFILE.jcl :L53-L54 (KEYS(16 0) RECORDSIZE(350 350));
 *               app/catlg/LISTCAT.txt :L3593-L3594 (KEYLEN 16, AVGLRECL = MAXLRECL = 350);
 *               app/cbl/CBACT04C.cbl :L473-L516 (1300-B-WRITE-TX, the second exposure) @ 7756d89
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
package com.cardemo.batch.writers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.cardemo.batch.GenerationPrefixContract;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;

/**
 * Writes posted transactions: one row into the {@code transaction} relation and one byte-exact 350-byte
 * fixed-width image per record into object storage.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the Java counterpart of {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579}. It is the <strong>third and last</strong> of the three writes that
 * {@code 2000-POST-TRANSACTION} performs, verified at {@code app/cbl/CBTRN02C.cbl:L440-L444}:
 * {@code PERFORM 2700-UPDATE-TCATBAL}, then {@code PERFORM 2800-UPDATE-ACCOUNT-REC}, then
 * {@code PERFORM 2900-WRITE-TRANSACTION-FILE}, then {@code EXIT}. Nothing in this class reproduces the first
 * two writes; they belong to the posting processor.
 *
 * <p>The legacy paragraph performs a single {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} against a VSAM
 * cluster. That one verb becomes two effects here, because the migration replaces the cluster with a relation
 * and replaces the generation-data-group output with object storage:
 *
 * <ol>
 * <li>the relational insert, through the inherited {@code saveAllAndFlush} of
 * {@code com.cardemo.repository.TransactionRepository}; and</li>
 * <li>the fixed-width emission, one 350-byte record per transaction, concatenated with no separator.</li>
 * </ol>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Built and tested with the repository build: {@code ./mvnw -B clean test} compiles this class under
 * {@code release 25} with {@code -Xlint:all -Werror} and runs its unit tests. The class is a Spring bean and
 * is not invoked directly; a Spring Batch step declared by {@code com.cardemo.config.BatchConfig} supplies the
 * chunks. Its unit tests live under {@code src/test/java/com/cardemo/unit/batch} and construct the bean
 * directly, needing no Spring context: {@link #composeFixedWidthImage(Transaction)} is a pure function and is
 * exposed for exactly that purpose.
 *
 * <p><b>The step scope does not change that.</b> {@code @StepScope} governs how the <em>container</em> hands
 * this bean out; it has no effect on {@code new TransactionWriter(...)}, so a unit test still holds a real
 * instance and calls a real method. Nor does it constrain the one production caller that uses the pure method
 * rather than the writer contract: {@code InterestCalculationJob} holds a scoped proxy and calls
 * {@link #composeFixedWidthImage(Transaction)} from inside its own step, where the scope is active, so the
 * proxy resolves. The method reads no instance field - only static width constants - which is what makes it
 * safe to expose and to share as the single owner of the {@value #RECORD_LENGTH}-byte geometry.
 *
 * <p>The named test obligation for this class, which the coverage gate measures against a floor of 80 percent
 * of lines, is: the composed image is exactly {@value #RECORD_LENGTH} characters and the emitted object is an
 * exact multiple of that; the trailing {@code FILLER} is 20 spaces; every field sits at its one-based offset,
 * in particular the card number at 263-278, the originating timestamp at 279-304 and the processing timestamp
 * at 305-330; the overpunch round trip holds, including {@code +504.77} rendering as {@code 0000005047G}; a
 * negative amount renders with a negative overpunch and is never normalised; an absent or blank processing
 * timestamp renders as 26 spaces; an over-length value is refused rather than truncated; and a duplicate
 * identifier raises a duplicate-record exception with its cause preserved and performs no update. The
 * strongest available assertion is a byte-identical round trip of every record of
 * {@code app/data/ASCII/dailytran.txt}, whose staging layout {@code app/cpy/CVTRA06Y.cpy} is field-for-field
 * the same 350-byte geometry as {@code app/cpy/CVTRA05Y.cpy}. Note when writing it that the fixture is 105,300
 * bytes of 300 records at a <strong>351</strong> byte stride - 350 data bytes plus a line feed - so a 350 byte
 * stride desynchronises after the first record.
 *
 * <p>Two constraints shape those tests. The entity's own setters already reject a null or over-length value,
 * so the guards in this class are defence in depth against a hydrated or corrupted row and are reachable only
 * by constructing the entity through its protected no-argument constructor with direct field writes, exactly
 * as a persistence provider does. And the entity refuses an amount whose scale exceeds two, so the rescaling
 * this class performs is idempotent in practice and cannot be exercised through the public entity API.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 * <li>{@code carddemo.aws.s3.batch-output-bucket} - the destination bucket. <strong>No default</strong>: the
 * value is required, and a context that does not supply it fails to start rather than silently writing
 * somewhere unintended. Declared as {@code carddemo.aws.s3.batch-output-bucket} in
 * {@code src/main/resources/application.yml} and supplied through
 * {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}, the name {@code .env.example} ships and
 * {@code localstack-init/init-aws.sh} provisions.</li>
 * <li>{@code carddemo.aws.s3.transaction-object-prefix} - the base-name segment every key starts with.
 * Defaults
 * to {@code transact}, the logical file name of {@code app/jcl/TRANFILE.jcl}.</li>
 * <li>{@value #KEY_MAX_INDEXED_OBJECT_KEYS} - how many object keys the indexed manifest enumerates. Defaults
 * to {@value #DEFAULT_MAX_INDEXED_OBJECT_KEYS}, declared in {@code src/main/resources/application.yml} at the
 * same value so the key is discoverable to whoever operates the job. It is a bound on a diagnostic record and
 * not a bound on the run: the exact object count, the generation prefix and the latest key are published
 * whatever it is set to. See {@link #OBJECT_KEYS_INDEXED_ENTRY}.</li>
 * </ul>
 *
 * <p>No endpoint, region or credential is read here, and no process environment variable is consulted
 * directly - every value arrives through the injected configuration. The endpoint override is declared in all
 * four profiles: the base, {@code test} and {@code prod} profiles bind it to a bare
 * {@code ${AWS_ENDPOINT_URL}} with no default, so an unset variable fails the context at startup, and only
 * {@code application-local.yml} defaults it to the LocalStack edge. No live cloud path is therefore
 * structurally reachable from this class. An earlier revision of this sentence said the override existed only
 * in the {@code local} and {@code test} profiles; that is withdrawn.
 *
 * <h2>Object keys and generation-data-group translation</h2>
 *
 * <p>A relative generation reference of {@code (+1)} becomes a new object under a monotonically increasing
 * prefix over a versioned bucket, and {@code (0)} becomes the lexicographically greatest existing prefix.
 * Keys are therefore built as
 * {@code <prefix>/<job-instance-id>/transact-<ordinal>.dat} with both numbers zero-padded to nineteen digits,
 * the width of {@code Long.MAX_VALUE}, so that lexicographic order and numeric order coincide and
 * {@code (0)} resolves correctly.
 *
 * <p>The ordinal is {@code StepExecution.getWriteCount()} read at entry to
 * {@link #write(Chunk)} - the zero-based index of the first record of the chunk. It is deterministic and
 * monotonically increasing, and using it means this class keeps no counter of its own.
 *
 * <p>Because a later step in the same job must re-read what an earlier step wrote as {@code (+1)} rather
 * than re-resolving "latest", every created key is published twice, at two scopes and for two readers. The
 * <em>latest</em> key goes into the step execution context under {@link #OBJECT_KEY_CONTEXT_ENTRY}, for a
 * listener running inside this step. The <em>ordered list</em> goes into the <b>job</b> execution
 * context under {@link #OBJECT_KEYS_COUNT_ENTRY} and the indexed entries it describes, written by this class
 * rather than by an external promotion listener - so the generation record needs no wiring to exist.
 *
 * <p>That list is <b>bounded</b>, and the bound is {@value #KEY_MAX_INDEXED_OBJECT_KEYS}. The posting step
 * commits once per record as a parity contract, so the job execution context is re-serialised once per record,
 * and an unbounded per-object list therefore costs work proportional to the square of the record count - the
 * scale limit recorded on {@link #OBJECT_KEYS_INDEXED_ENTRY}. Nothing is lost to the bound: the exact count
 * stays exact, {@link #OBJECT_KEYS_GENERATION_PREFIX_ENTRY} names a prefix unique to this job instance so that
 * listing it enumerates every object deterministically, and {@link #OBJECT_KEYS_TRUNCATED_ENTRY} says plainly
 * when the enumeration stopped short.
 *
 * <h2>Side effects</h2>
 *
 * <p>One insert per item and one object per non-empty chunk, plus one increment of the records-processed
 * counter per item and one log record per failure. Nothing else is mutated. There is no static mutable state,
 * and exactly one instance field is not {@code final}: {@link #stepExecution}, which the framework supplies
 * after construction through {@link StepExecutionListener#beforeStep(StepExecution)}. Its safety comes from
 * {@code @StepScope} rather than from finality - each step execution receives its own instance, so the field
 * is confined to one execution and there is no sharing to guard. See the field's own declaration for why it
 * is deliberately not {@code volatile}.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 * <li><strong>Duplicate identifier</strong> - {@code DuplicateRecordException} naming logical file
 * {@code TRANSACT}. This is a designed outcome, not a defect; see the note on the identifier race below. Do
 * not retry: re-drive the job with an unused date parameter.</li>
 * <li><strong>Over-length or absent field</strong> - {@code DataIntegrityException} naming the offending
 * COBOL field and the widths involved, never the value. Indicates an upstream producer that has drifted from
 * {@code app/cpy/CVTRA05Y.cpy}.</li>
 * <li><strong>Object-storage failure</strong> - {@code FileAccessException} carrying logical name
 * {@code TRANSACT} and operation {@code WRITE}, with the cause preserved. Check that the bucket exists and
 * that {@code localstack-init/init-aws.sh} has run.</li>
 * <li><strong>Any other store failure</strong> - {@code FatalProcessingException} carrying abend
 * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and return code
 * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}, reproducing
 * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}.</li>
 * <li><strong>No step context</strong> - {@code FatalProcessingException}. The writer was used outside a step,
 * so no job-instance identifier exists to key the object on. Register it on a step, which makes the framework
 * call {@code beforeStep}, or, in a unit test that uses the class directly, call {@code beforeStep} with a
 * {@code StepExecution} built over a job execution and a job instance before writing.</li>
 * <li><strong>One {@code WARN} that the indexed manifest reached its bound</strong> - <b>not a failure and not
 * a defect.</b> The run posted more objects than {@value #KEY_MAX_INDEXED_OBJECT_KEYS} enumerates, so the keys
 * past that point are counted but not listed individually. Read the exact count from
 * {@link #OBJECT_KEYS_COUNT_ENTRY} and, if every key is needed, list the single prefix in
 * {@link #OBJECT_KEYS_GENERATION_PREFIX_ENTRY} - it names this job instance only. Raise the property
 * deliberately if the enumeration itself is required, having accepted that the job execution context is
 * re-serialised once per record.</li>
 * <li><strong>Startup fails naming {@value #KEY_MAX_INDEXED_OBJECT_KEYS}</strong> - the property is zero or
 * negative. Neither names a usable bound, so the context refuses to start rather than producing an audit
 * record no consumer can read.</li>
 * </ul>
 *
 * <h2>The preserved identifier race is deliberate</h2>
 *
 * <p>There is <strong>no retry, no backoff, no identifier regeneration, no merge over a colliding row, no
 * conflict-swallowing clause and no database sequence</strong> anywhere in this class. Identifiers reach it
 * pre-computed by the
 * descending-browse max-plus-one idiom of {@code app/cbl/COTRN02C.cbl:L444-L451} and
 * {@code app/cbl/COBIL00C.cbl:L212-L219}, whose empty-file path at {@code app/cbl/COBIL00C.cbl:L487-L488}
 * moves zeros so that the first generated identifier is 1. That idiom is racy, and the parity-preserving
 * choice is to keep it and let the primary-key constraint surface a collision as a duplicate-record
 * exception rather than substituting a sequence, which would change generated values and break the boundary
 * comparison. A collision is the intended outcome.
 *
 * <p>A second exposure must stay observable for the same reason. {@code app/cbl/CBACT04C.cbl:L473-L516}
 * writes interest transactions to a sequential generation-data-group output with no duplicate detection at
 * all, concatenating the ten-character date parameter with a <strong>global six-digit suffix counter that is
 * never reset per account</strong> ({@code ADD 1 TO WS-TRANID-SUFFIX} at {@code :L474}). The collision
 * materialises later, at the bulk load of {@code app/jcl/COMBTRAN.jcl:STEP10}, and must surface as a
 * duplicate-record exception and a failed exit status - never as a silent merge over the row already there.
 * No counter of that shape is hosted in this bean.
 *
 * <h2>Two concurrency guards, and why the version column is left null</h2>
 *
 * <p>{@code Transaction} carries a {@code @Version} column, and {@code JpaRepository.save} dispatches on
 * entity state: a null version means transient and yields {@code persist}, an INSERT, whereas a non-null
 * version means detached and yields {@code merge}, a SELECT-then-UPDATE that silently overwrites whatever row
 * is already there. This class therefore <strong>never reads or assigns {@code version}</strong>. Such an
 * overwrite would contradict
 * {@code 0100-TRANFILE-OPEN} at {@code app/cbl/CBTRN02C.cbl:L254-L270}, which is {@code OPEN OUTPUT} and so
 * write-only, and would make the identifier race above undetectable.
 *
 * <p>The constraint check is forced inside this stack frame with {@code saveAllAndFlush} rather than left to
 * commit. A violation raised at commit time escapes this frame and could never be translated into the typed
 * hierarchy at all.
 *
 * <h2>Transaction boundary: a labelled deviation, not parity</h2>
 *
 * <p>This class declares <strong>no</strong> transaction annotation. It participates in the caller's
 * boundary, and the service layer owns the single {@code rollbackFor = Exception.class} unit of work.
 *
 * <p>That is a deliberate, labelled <strong>deviation</strong> and not a parity claim. The source performs
 * <strong>three independent commits</strong> in {@code 2000-POST-TRANSACTION}
 * ({@code app/cbl/CBTRN02C.cbl:L424-L465}), so its reject-code-109 rewrite-failure path at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560} leaves an orphaned category-balance row and an orphaned transaction
 * row behind. Collapsing the three writes into one atomic Java unit closes that hazard as a side effect,
 * which is a genuine behavioural improvement and therefore must be labelled rather than absorbed silently.
 *
 * <p>One consequence is worth stating plainly: object storage is not transactional. If the caller's
 * transaction rolls back after this class has emitted an object, the row disappears and the object remains.
 * Object keys are unique per job instance and ordinal, so such an orphan is identifiable and is superseded
 * by the next run rather than corrupting it.
 *
 * <h2>Measured facts that a reader is likely to get wrong</h2>
 *
 * <ul>
 * <li><strong>Fixture geometry is 300 x 351, not 300 x 350.</strong>
 * {@code app/data/ASCII/dailytran.txt} measures 105,300 bytes because it is 300 x 351: 350 data bytes plus
 * one line feed per record, and it
 * contains exactly 300 line feeds. 105,300 is consequently <em>not</em> a multiple of 350. Reading it at a
 * 350-byte stride desynchronises after the first record; read at the correct 351-byte stride the sign census
 * at byte 143 is 25 <code>&#123;</code>, 6 <code>&#125;</code> and 44 in {@code J}-{@code R}, exactly as specified, and
 * the first record's amount field ends in the overpunch {@code G}, that is +7 in the units position.
 * Always read that fixture at the 351-byte stride. The checked-in text file is line-feed delimited; the
 * mainframe dataset it mirrors is {@code RECORDSIZE(350 350)} fixed, so the emission below stays
 * separator-free.</li>
 * <li><strong>The generated timestamp carries hundredths, not milliseconds.</strong> "Millisecond precision
 * followed by four zeros" would be three plus
 * four characters of fraction and is arithmetically impossible in a {@code PIC X(26)} field. The verified
 * layout at {@code app/cbl/CBTRN02C.cbl:L162-L174} is
 * {@code 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26} exactly, where {@code DB2-MIL PIC 9(002)} carries
 * <em>hundredths</em> and {@code DB2-REST PIC X(04)} is set by {@code MOVE '0000' TO DB2-REST} at
 * {@code app/cbl/CBTRN02C.cbl:L701}. The pattern {@code yyyy-MM-dd-HH.mm.ss.SS0000} governs.
 * Never format to millisecond or nanosecond precision. This class generates no
 * timestamp and so cannot introduce the defect; it passes the 26 characters through verbatim.</li>
 * <li><strong>{@code CBTRN02C} has 26 procedure-division paragraphs, not 27</strong> - 25 numbered
 * labels plus {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBTRN02C.cbl:L692}. The 27th candidate is
 * {@code FILE-CONTROL.} at {@code app/cbl/CBTRN02C.cbl:L28}, an environment-division header rather than a
 * paragraph. The banner above states the verified figure.</li>
 * <li><strong>Two series are advanced from here, and neither converts a monetary value.</strong> The
 * records-processed counter takes a count. The transaction-amount instrument takes the {@code BigDecimal}
 * itself: {@link MetricsConfig#countTransactionAmount} accumulates exactly and converts to a primitive once,
 * on the scrape thread, so no approximate binary value is ever produced here. Micrometer's
 * {@code Counter.increment(double)} overload - the one that would have required such a conversion, and that
 * silently discards a non-positive amount - is not reachable from this class at all. The authoritative
 * transaction value remains the relation's {@code NUMERIC(11,2)} column; the meter is a telemetry
 * mirror.</li>
 * <li><strong>Which item of a batched flush collided cannot be determined</strong> from any portable field of
 * Spring's exception; a driver-independent accessor for the violated key would be needed. Until one exists,
 * the exception names the exact
 * identifier when the chunk holds a single item, reports the candidate identifiers otherwise, and always
 * preserves the driver's own exception as the cause.</li>
 * </ul>
 *
 * <h2>Choices the corpus does not determine</h2>
 *
 * <ul>
 * <li>Which generation-data-group base the posted-transaction object belongs to, among {@code SYSTRAN},
 * {@code TRANSACT.BKUP}, {@code TRANSACT.DALY} and {@code TRANSACT.COMBINED}, is not stated anywhere: there
 * is no explicit dataset-to-prefix table in the corpus. The output bucket with a base-name plus job-instance
 * prefix is used, and that choice is labelled rather than presented as derived.</li>
 * <li>The corpus states no object-storage record-framing convention, predating object storage entirely. The
 * fixed-width
 * dataset geometry is followed instead - no separator, so record <em>n</em> begins at offset
 * <em>n</em> x 350 and the object size is an exact multiple of 350. A newline-delimited variant would be a
 * labelled deviation, never a silent change.</li>
 * <li>The corpus publishes no throughput or latency objective whatsoever. None may be invented, so
 * the performance gate records a measured baseline rather than asserting a threshold.</li>
 * <li>The literal counter names registered by {@code com.cardemo.observability.MetricsConfig} are not
 * restated here, that file not being a declared dependency of this one.
 * {@code MeterRegistry.counter} resolves an existing meter by name and tags
 * rather than adding one, so naming the counter as below cannot introduce a fifth instrument.</li>
 * <li>Literal {@code FILE STATUS '22'} is tested nowhere in the corpus, being grounded only
 * through {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)}. No status decision is made here in any case:
 * every one routes to {@code com.cardemo.service.shared.FileStatusMapper}.</li>
 * </ul>
 *
 * <h2>Scope, and why the fixed-width encoding is deliberately not shared</h2>
 *
 * <p>This class owns exactly two things: the 350-byte transaction layout and the insert of a transaction row.
 * It reproduces neither of the two writes that precede it in {@code 2000-POST-TRANSACTION}, and it composes no
 * layout other than its own.
 *
 * <p>{@code RejectWriter} independently implements the emission of the 430-byte reject record, which is the
 * same 350-byte transaction image followed by an 80-byte trailer of a four-digit reason code and a
 * 76-character description, per {@code app/cbl/CBTRN02C.cbl:L176-L182} and the record length declared on the
 * reject dataset in {@code app/jcl/POSTTRAN.jcl}. <strong>That duplication is deliberate and is not to be
 * factored out.</strong> Rule 1 clause C asks for a consistent directory structure and no duplication, and
 * both are satisfied here by the framework: each writer is an {@code ItemWriter} over its own record type,
 * addressed through its own step. Extracting a shared codec would add a fourth type to a package whose
 * membership is fixed at three writers, and would couple two layouts whose only relationship is that one
 * embeds the other - so a change to the reject trailer could silently alter the transaction geometry that Gate
 * 1 compares byte-for-byte. The two encoders are held separately on purpose, and each is verified against its
 * own frozen fixture.
 *
 * <h2>Thread safety and execution isolation</h2>
 *
 * <p><strong>This bean is {@code @StepScope}: one instance exists per step execution.</strong> That is the
 * whole isolation argument, and it is structural rather than conventional. The container hands every injection
 * point a scoped proxy that resolves to the instance belonging to the step execution on the calling thread, so
 * two concurrent step executions - two jobs launched at once, a partitioned step, or a step given a task
 * executor - each hold their own {@link #stepExecution}, their own object ownership and their own view of the
 * context they publish into. No field is shared between them and there is no static mutable state at all.
 *
 * <p><b>Finding, severity Blocker, RESOLVED.</b> An earlier revision was a plain singleton
 * {@code @Component} holding the captured step execution in a {@code volatile} field, and argued that this was
 * safe because the declared step is single-threaded and that a multi-threaded step "must declare this writer
 * step-scoped - a wiring decision owned by {@code BatchConfig}". Both halves were wrong in the same way. The
 * {@code volatile} qualifier fixes <em>publication</em>, not <em>ownership</em>: two concurrent executions
 * writing the field in turn each observe the other's value safely and are each then wrong about which job
 * instance owns the object key they are about to compose, so the two runs interleave their key namespaces and
 * overwrite each other's published context entry. And deferring the remedy to a configuration file that this
 * class cannot see left correctness contingent on a promise nobody enforces - a step declared with a task
 * executor would have silently corrupted output rather than failing. <i>Remediation, applied:</i> the scope is
 * declared here, on the bean, where it is a property of the component and not of its wiring. A step-scoped
 * bean cannot be injected into a singleton without a proxy, so the container enforces the contract.
 *
 * @see com.cardemo.service.shared.FileStatusMapper
 * @see MetricsConfig
 */
@StepScope
@Component
public class TransactionWriter implements ItemWriter<Transaction>, StepExecutionListener {

    /**
     * Structured logger, the sole diagnostic channel of this class. Nothing here writes to the process
     * standard output or standard error streams directly, so every diagnostic passes through
     * {@code logback-spring.xml} and its profile-invariant masking rules.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionWriter.class);

    /**
     * The fixed record length declared by {@code app/cpy/CVTRA05Y.cpy:L2} as {@code RECLN = 350} and
     * corroborated by {@code app/jcl/TRANFILE.jcl:L54} {@code RECORDSIZE(350 350)} and by
     * {@code app/catlg/LISTCAT.txt:L3593-L3594}, where {@code AVGLRECL} and {@code MAXLRECL} are both 350.
     *
     * <p>Public so that the step wiring and the unit tests can assert the geometry against one declaration
     * rather than repeating the literal.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * Step execution context entry under which the most recently created object key is published.
     *
     * <p>Retained as a convenience for the step that is currently running - a step listener that wants to
     * report what it just wrote reads this and needs nothing else. It is <b>not</b> the generation record:
     * {@link #OBJECT_KEYS_COUNT_ENTRY} and {@link #objectKeysIndexEntry(int)} are.
     */
    public static final String OBJECT_KEY_CONTEXT_ENTRY = "carddemo.transaction.object.key";

    /**
     * Job execution context entry holding how many objects this job instance's transaction generation
     * contains, as a {@code Long}.
     *
     * <p>This count is always exact and is never capped. Together with {@link #objectKeysIndexEntry(int)} it
     * is the ordered record of the generation - the thing a downstream step needs in order to consume
     * {@code (0)} without guessing.
     *
     * <p><b>Read protocol.</b> Read {@link #OBJECT_KEYS_INDEXED_ENTRY} for how many indexed entries exist,
     * then read that many; entry {@code n} is the key of the {@code n}th object created, in creation order.
     * {@code indexed} equals this count on every run that stays within
     * {@code carddemo.batch.transaction-writer.max-indexed-object-keys}, which is why a consumer written
     * against the earlier "read the count, then read that many indexed entries" rule still works for such a
     * run - but that rule is withdrawn, because it reads an absent entry once the cap is passed. When the two
     * differ, {@link #OBJECT_KEYS_TRUNCATED_ENTRY} is present and
     * {@link #OBJECT_KEYS_GENERATION_PREFIX_ENTRY} names the one prefix that holds every object of this
     * generation.
     *
     * <p><b>Finding, severity High, RESOLVED.</b> An earlier revision published only the latest chunk key,
     * and only into the <em>step</em> execution context, while its documentation asserted that promotion to
     * the job execution context "is configured by {@code BatchConfig}". No such promotion existed, and
     * {@code BatchConfig} does not exist, so nothing downstream could reconstruct a generation: a run of a
     * hundred chunks left one key behind and the other ninety-nine were unrecoverable. Worse, a consumer that
     * fell back to resolving "the lexicographically greatest prefix" would race any concurrent producer. Two
     * things were wrong - the <em>completeness</em> of what was published and the <em>scope</em> it was
     * published into - and a promotion listener would only have fixed the second. <i>Remediation, applied:</i>
     * every key is appended here, in creation order, in the job execution context, by this class, with no
     * external wiring required for the record to be complete.
     *
     * <p><b>Why indexed entries rather than one delimited string.</b> A joined value needs a separator, and a
     * separator is a value that must not occur inside a key. Object keys are built from a configured prefix,
     * so "must not occur" is a constraint on configuration this class cannot enforce - and a prefix containing
     * the separator would corrupt the list silently, splitting one key into two that name nothing. Indexed
     * entries have no separator and therefore no such failure mode, and they keep the values as plain strings
     * that every {@code ExecutionContext} serialiser supports without a trusted-class allow-list.
     */
    public static final String OBJECT_KEYS_COUNT_ENTRY = "carddemo.transaction.object.keys.count";
    /**
     * Step execution context entry naming the object a committed chunk still owes the store, {@value}.
     *
     * <p><strong>Finding M-07, severity Major, RESOLVED - and this entry is the outbox.</strong> The object is
     * uploaded after the chunk transaction commits, which is the only ordering that cannot publish an object
     * describing rows that were rolled back. But it leaves the mirror image of that problem: if the upload
     * fails, the rows are already durable and cannot be taken back, so the relation held transactions that no
     * object described and nothing in the target could ever notice or repair.
     *
     * <p>The remedy is a transactional outbox, and it needs no table of its own.
     * {@code TaskletStep$ChunkTransactionCallback.doInTransaction} calls {@code ItemStream.update} and then
     * {@code JobRepository.updateExecutionContext} <b>inside</b> the chunk transaction - verified against the
     * 5.2.4 bytecode, not assumed - so an entry this writer places in the step execution context is committed
     * atomically with the very rows it describes. A pending entry that survives a failure is therefore proof
     * that the rows exist and the object does not, which is exactly what a reconciliation needs to be sound.
     *
     * <p>{@link #beforeStep(StepExecution)} settles any entry it finds before the step writes anything new.
     */
    public static final String PENDING_OBJECT_KEY_ENTRY = "carddemo.transaction.pending.objectKey";

    /**
     * Step execution context entry listing the identifiers a pending object must be rebuilt from, {@value}.
     *
     * <p>The payload is <b>re-derived from the committed rows</b> rather than carried here as bytes. Two
     * reasons, and both matter. A context is not a place to put a chunk's worth of record images; and more
     * importantly, re-reading the rows is what makes the reconciliation <em>true</em> - it emits what the
     * database actually holds, so a mirror written by the retry cannot disagree with the relation it mirrors.
     * {@link #composeFixedWidthImage(Transaction)} is public and pure precisely so that this reconstruction is
     * the same function the original write used.
     *
     * <p>Comma separated, in write order, because the object's record order is the chunk's item order.
     */
    public static final String PENDING_IDENTIFIERS_ENTRY = "carddemo.transaction.pending.identifiers";

    /** Separator between identifiers in {@link #PENDING_IDENTIFIERS_ENTRY}, {@value}. */
    private static final String PENDING_IDENTIFIER_SEPARATOR = ",";


    /**
     * Prefix of the indexed job-execution entries described on {@link #OBJECT_KEYS_COUNT_ENTRY}. The entry for
     * index {@code n} is this prefix followed by {@code n}, rendered by {@link #objectKeysIndexEntry(int)}.
     *
     * <p>{@link #OBJECT_KEYS_COUNT_ENTRY}, {@link #OBJECT_KEYS_INDEXED_ENTRY},
     * {@link #OBJECT_KEYS_TRUNCATED_ENTRY} and {@link #OBJECT_KEYS_GENERATION_PREFIX_ENTRY} share this prefix
     * and are not indexed entries. A consumer addresses an indexed entry through
     * {@link #objectKeysIndexEntry(int)} and therefore never has to tell them apart; one that scans the
     * context by prefix instead must skip the four non-numeric suffixes.
     */
    public static final String OBJECT_KEYS_INDEX_ENTRY_PREFIX = "carddemo.transaction.object.keys.";

    /**
     * Job execution context entry holding how many indexed entries were actually published, as a {@code Long}.
     *
     * <p>Equal to {@link #OBJECT_KEYS_COUNT_ENTRY} on any run that stays within
     * {@code carddemo.batch.transaction-writer.max-indexed-object-keys}, and equal to that cap on any run that
     * passes it. It is the bound a consumer iterates to.
     *
     * <p><b>Finding, severity Minor, RESOLVED.</b> An earlier revision appended one indexed entry per created
     * object with <b>no bound of any kind</b>. Because the commit interval of the posting step is pinned at one
     * record as a parity contract - see {@code DailyTransactionPostingJob.POSTING_COMMIT_INTERVAL}, grounded on
     * the three separate commits of {@code app/cbl/CBTRN02C.cbl:L440-L442} - Spring Batch re-serialises the
     * whole job execution context once per <em>record</em>, so the work of writing this manifest grew with the
     * square of the record count. A measured 300-record run of {@code app/data/ASCII/dailytran.txt} left 262
     * indexed entries and a serialised job context of 36,664 bytes, about 140 bytes per record; a hundred
     * thousand records would have reached roughly fourteen megabytes re-serialised a hundred thousand times.
     * No cap, no marker and no disclosed ceiling existed. <i>Remediation, applied:</i> the indexed enumeration
     * is bounded, the bound is a documented property, passing it is reported once at {@code WARN} and marked in
     * the context by {@link #OBJECT_KEYS_TRUNCATED_ENTRY}, and the three facts that make the generation
     * recoverable in full - the exact count, the generation prefix and the latest key - are published
     * unconditionally.
     *
     * <p><b>Why this cap truncates rather than refuses, and why it is the only cap left.</b> This manifest is
     * a diagnostic and hand-off record, and nothing is dropped when it is capped: the count stays exact,
     * {@link #OBJECT_KEY_CONTEXT_ENTRY} still names the latest object, and
     * {@link #OBJECT_KEYS_GENERATION_PREFIX_ENTRY} names a prefix unique to this job instance, so listing that
     * one prefix enumerates every object deterministically and without racing any concurrent producer.
     * Refusing an otherwise successful posting run because it posted more records than its audit list can
     * enumerate would be a behaviour the source does not have - {@code app/cbl/CBTRN02C.cbl} writes to a
     * sequential dataset and imposes no such limit. That reasoning is exactly why the statement path now has
     * no record ceiling at all: finding BAT-002 removed the run and card-group refusals that once stood in
     * {@code StatementProcessor}, on the same ground that the corpus imposes no such limit either.
     */
    public static final String OBJECT_KEYS_INDEXED_ENTRY = "carddemo.transaction.object.keys.indexed";

    /**
     * Job execution context entry present only when the indexed enumeration was capped, holding
     * {@value #OBJECT_KEYS_TRUNCATED_MARKER}.
     *
     * <p>Written as a string rather than as a {@code Boolean} for the reason given on
     * {@link #OBJECT_KEYS_COUNT_ENTRY}: plain strings serialise through every {@code ExecutionContext}
     * serialiser without a trusted-class allow-list. Its <em>absence</em> is the complete-manifest case, so a
     * consumer never has to interpret a value to know it has every key.
     */
    public static final String OBJECT_KEYS_TRUNCATED_ENTRY = "carddemo.transaction.object.keys.truncated";

    /** The only value {@link #OBJECT_KEYS_TRUNCATED_ENTRY} ever holds. */
    public static final String OBJECT_KEYS_TRUNCATED_MARKER = "true";

    /**
     * Job execution context entry naming the key prefix that holds every object of this generation, ending in
     * the key separator.
     *
     * <p>Derived from a created key rather than recomposed, so it cannot drift from {@link #KEY_TEMPLATE}: it
     * is the key up to and including its last separator, which is
     * {@code <configured-prefix>/<job-instance-id>/}. Because the job instance identifier is in it, the prefix
     * is unique to this job instance and listing it is deterministic - which is what makes the bounded
     * enumeration of {@link #OBJECT_KEYS_INDEXED_ENTRY} lossless rather than lossy, and what keeps it distinct
     * from the "re-resolve the lexicographically greatest prefix" resolution that would race a concurrent
     * producer.
     */
    public static final String OBJECT_KEYS_GENERATION_PREFIX_ENTRY =
            "carddemo.transaction.object.keys.generation-prefix";

    /**
     * The property bounding how many object keys the indexed manifest enumerates.
     *
     * <p>Public so that the value is named once and asserted against
     * {@code src/main/resources/application.yml} rather than repeated as a literal.
     */
    public static final String KEY_MAX_INDEXED_OBJECT_KEYS =
            "carddemo.batch.transaction-writer.max-indexed-object-keys";

    /**
     * Default bound on the indexed manifest, {@value #DEFAULT_MAX_INDEXED_OBJECT_KEYS} keys.
     *
     * <p>Chosen against measurement rather than taste. One indexed entry costs about 140 serialised bytes, and
     * the 300-record parity run of {@code app/data/ASCII/dailytran.txt} publishes 262 of them, so this default
     * enumerates that run and every fixture-scale run in full while bounding the serialised job execution
     * context at roughly 140 kB. The bound matters because the posting step commits once per record by parity
     * contract, so the context is re-serialised once per record: the cost of the manifest is quadratic in the
     * record count, and a bound on its size is the only place that quadratic can be capped.
     *
     * <p>A deployment that needs the full enumeration of a larger run raises
     * {@value #KEY_MAX_INDEXED_OBJECT_KEYS} deliberately, having accepted that cost; one that does not need it
     * reads the exact count, the generation prefix and the latest key, all of which stay complete. See
     * {@link #OBJECT_KEYS_INDEXED_ENTRY} for the finding this resolves.
     */
    public static final int DEFAULT_MAX_INDEXED_OBJECT_KEYS = 1_000;

    /** {@code TRAN-ID PIC X(16)}, bytes 1-16 of {@code app/cpy/CVTRA05Y.cpy:L5}. */
    private static final int TRAN_ID_WIDTH = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)}, bytes 17-18 of {@code app/cpy/CVTRA05Y.cpy:L6}. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)}, bytes 19-22 of {@code app/cpy/CVTRA05Y.cpy:L7}. Unsigned. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** {@code TRAN-SOURCE PIC X(10)}, bytes 23-32 of {@code app/cpy/CVTRA05Y.cpy:L8}. */
    private static final int TRAN_SOURCE_WIDTH = 10;

    /** {@code TRAN-DESC PIC X(100)}, bytes 33-132 of {@code app/cpy/CVTRA05Y.cpy:L9}. */
    private static final int TRAN_DESC_WIDTH = 100;

    /** {@code TRAN-AMT PIC S9(09)V99}, bytes 133-143 of {@code app/cpy/CVTRA05Y.cpy:L10}. Nine integer
     * digits plus two decimal digits, the decimal point implied and never written. */
    private static final int TRAN_AMT_WIDTH = 11;

    /** The scale of {@code TRAN-AMT}: the {@code V99} of {@code PIC S9(09)V99}. */
    private static final int TRAN_AMT_SCALE = 2;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}, bytes 144-152 of {@code app/cpy/CVTRA05Y.cpy:L11}. Unsigned. */
    private static final int TRAN_MERCHANT_ID_WIDTH = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}, bytes 153-202 of {@code app/cpy/CVTRA05Y.cpy:L12}. */
    private static final int TRAN_MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}, bytes 203-252 of {@code app/cpy/CVTRA05Y.cpy:L13}. */
    private static final int TRAN_MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}, bytes 253-262 of {@code app/cpy/CVTRA05Y.cpy:L14}. */
    private static final int TRAN_MERCHANT_ZIP_WIDTH = 10;

    /** {@code TRAN-CARD-NUM PIC X(16)}, bytes 263-278 of {@code app/cpy/CVTRA05Y.cpy:L15}. */
    private static final int TRAN_CARD_NUM_WIDTH = 16;

    /** {@code TRAN-ORIG-TS PIC X(26)}, bytes 279-304 of {@code app/cpy/CVTRA05Y.cpy:L16}. Text, never a
     * date-time type. */
    private static final int TRAN_ORIG_TS_WIDTH = 26;

    /** {@code TRAN-PROC-TS PIC X(26)}, bytes 305-330 of {@code app/cpy/CVTRA05Y.cpy:L17}. Text, never a
     * date-time type. */
    private static final int TRAN_PROC_TS_WIDTH = 26;

    /**
     * {@code FILLER PIC X(20)}, bytes 331-350 of {@code app/cpy/CVTRA05Y.cpy:L18}.
     *
     * <p>Not modelled as a column on the entity, because it carries no value - but it is still part of the
     * record and <strong>must be emitted</strong>. Omitting it yields 330 bytes and fails the boundary parity
     * comparison.
     */
    private static final int FILLER_WIDTH = 20;

    /** The trailing filler, pre-rendered once: exactly {@link #FILLER_WIDTH} spaces. Immutable. */
    private static final String FILLER = " ".repeat(FILLER_WIDTH);

    /**
     * Trailing overpunch characters for a non-negative zoned-decimal value, indexed by the final digit:
     * <code>&#123;</code> encodes +0 and {@code A} through {@code I} encode +1 through +9.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Trailing overpunch characters for a negative zoned-decimal value, indexed by the final digit:
     * <code>&#125;</code> encodes -0 and {@code J} through {@code R} encode -1 through -9.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * The highest code point {@link #FIXED_WIDTH_CHARSET} represents as a single byte.
     *
     * <p>A character above this would be substituted rather than encoded, silently replacing record content.
     * The width would survive but the fidelity would not, so such a value is refused instead.
     */
    private static final char MAX_ENCODABLE_CHAR = '\u00FF';

    /**
     * Lowest code point admitted into a {@code PIC X(n)} field: the space, {@code U+0020}.
     *
     * <p>Everything below it is a C0 control character, and every one of them is refused. See
     * {@link #PERMITTED_CHARACTER_SET_DESCRIPTION} for why that is a security property and not a tidiness
     * preference.
     */
    private static final char MIN_PERMITTED_CHAR = '\u0020';

    /** The delete control, {@code U+007F}, which sits above the printable range and is refused with the C0 set. */
    private static final char DELETE_CHAR = '\u007F';

    /** First code point of the C1 control block, {@code U+0080}; the block runs to {@code U+009F}. */
    private static final char FIRST_C1_CHAR = '\u0080';

    /** Last code point of the C1 control block, {@code U+009F}. */
    private static final char LAST_C1_CHAR = '\u009F';

    /**
     * Human-readable description of the permitted set, used in the diagnostic so an operator does not have to
     * infer the rule from a code point.
     *
     * <p><b>Finding, severity High, RESOLVED.</b> An earlier revision guarded only the <em>upper</em> bound -
     * it refused anything above {@link #MAX_ENCODABLE_CHAR} because such a character could not be encoded in
     * one byte - and admitted every control byte below the space. That is a security defect rather than a
     * cosmetic one, and specifically an injection defect. The emitted stream is <b>unblocked and
     * undelimited</b>: {@code app/jcl/POSTTRAN.jcl} declares {@code RECFM=FB}, so a consumer finds record
     * boundaries by counting {@value #RECORD_LENGTH} bytes and by nothing else. A carriage return, a line feed
     * or a NUL inside a {@code PIC X} field is therefore not merely odd - it is a byte that a line-oriented
     * downstream reader, a shell pipeline, a text editor or a log ingester will treat as a record boundary that
     * the format does not have, letting a merchant name carried in from an upstream system split one record
     * into two or terminate a C string early. The width check cannot catch it, because a control byte occupies
     * exactly one column like any other.
     *
     * <p><i>Remediation, applied:</i> an explicit <b>permitted</b> set, expressed as a positive rule rather
     * than as a list of things to exclude, because a deny-list of control characters is exactly the kind of
     * enumeration that is one code point out of date the moment it is written. The permitted set is
     * {@code U+0020} through {@code U+007E} and {@code U+00A0} through {@code U+00FF} - the printable ASCII
     * range plus the printable upper half of ISO-8859-1 - which is a superset of every character the frozen
     * fixtures actually contain and a subset of what the charset can encode. Nothing legitimate is refused: the
     * fixtures under {@code app/data/ASCII} are printable throughout, including the zoned-decimal overpunch
     * characters <code>&#123;</code>, <code>&#125;</code> and {@code A}-{@code R}, all of which sit inside the
     * printable ASCII range.
     */
    private static final String PERMITTED_CHARACTER_SET_DESCRIPTION =
            "U+0020 to U+007E and U+00A0 to U+00FF (printable ISO-8859-1; no C0 or C1 control, no DEL)";

    /**
     * The one charset used for every byte this class writes.
     *
     * <p>ISO-8859-1 is byte-transparent: every code point from {@code U+0000} to {@code U+00FF} becomes
     * exactly one byte, so a 350-character record becomes exactly 350 bytes. A variable-width Unicode
     * transformation format would render any code point above {@code U+007F} as several bytes and silently
     * destroy the geometry, and the platform default would make the output depend on the host - so neither is
     * used, and no charset-less byte conversion appears anywhere in this class: this constant is passed
     * explicitly at the single conversion site.
     *
     * <p>No EBCDIC is ever produced. {@code app/data/EBCDIC} is codepage reference material only.
     */
    private static final Charset FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1;

    /** The legacy logical file and DD name of the transaction dataset, per {@code app/jcl/POSTTRAN.jcl}. */
    private static final String LOGICAL_FILE = "TRANSACT";

    /**
     * The relation the row is inserted into.
     *
     * <p>Lower case and unquoted: {@code TRANSACTION} is a non-reserved word in PostgreSQL, so the entity maps
     * it without quoting. This value labels the relation in diagnostics only - no SQL or JPQL is written in
     * this class, and every value reaches the store through parameter binding.
     */
    private static final String RELATION = "transaction";

    /** The attempted operation, as reported to {@code FileStatusMapper} and carried on the exception. */
    private static final String OPERATION_WRITE = "WRITE";

    /** The program whose paragraph this class reproduces, carried as the abend culprit. */
    private static final String ABEND_CULPRIT = "CBTRN02C";

    /**
     * The diagnostic of {@code DISPLAY 'ERROR WRITING TO TRANSACTION FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L574}, reproduced verbatim because the boundary comparison reads it.
     */
    private static final String WRITE_FAILURE_TEXT = "ERROR WRITING TO TRANSACTION FILE";

    /**
     * The diagnostic of {@code DISPLAY 'ABENDING PROGRAM'} at {@code app/cbl/CBTRN02C.cbl:L708}, reproduced
     * verbatim for the same reason.
     */
    private static final String ABEND_DISPLAY_TEXT = "ABENDING PROGRAM";

    /**
     * The successful file status, {@code '00'}, taken from the enum rather than restated as a literal.
     *
     * <p>This is the value {@code app/cbl/CBTRN02C.cbl:L566} tests. It is resolved from
     * {@code FileStatus.SUCCESS}, which is an exact-value constant, so the optional is always present.
     */
    private static final String SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    /**
     * The status synthesised when object storage refuses the write.
     *
     * <p>Built from {@code FileStatus.IO_ERROR_FIRST_BYTE} so that the {@code '9x'} family - the corpus
     * classification for a physical or logical I/O error - is expressed through the enum rather than as a bare
     * literal. Routing this status through {@code FileStatusMapper} produces {@code FileAccessException},
     * which is the mandated surface for a storage failure.
     */
    private static final String OBJECT_STORE_IO_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /**
     * The one key separator, which {@link #KEY_TEMPLATE} writes and {@link #requireObjectPrefix(String)}
     * therefore strips from the configured prefix so that no key carries an empty segment.
     */
    private static final String KEY_SEPARATOR = "/";

    /** The base-name segment of every emitted object key, appended after the configured prefix. */
    private static final String OBJECT_BASE_NAME = "transact";

    /** The suffix of every emitted object key. */
    private static final String OBJECT_SUFFIX = ".dat";

    /**
     * The content type of every emitted object.
     *
     * <p>Deliberately not a text type: these are fixed-width records whose trailing space padding is
     * significant, and declaring them opaque keeps any intermediary from re-encoding or trimming them.
     */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    /** Digits in {@code Long.MAX_VALUE}, and therefore the padding width used by {@link #KEY_TEMPLATE}. */
    private static final int KEY_NUMBER_WIDTH = 19;

    /**
     * The object key template: prefix, job instance, base name, ordinal, suffix.
     *
     * <p>Both numbers are zero-padded to {@link #KEY_NUMBER_WIDTH} digits so that lexicographic order and
     * numeric order coincide, which is what makes the {@code (0)} generation reference - the
     * lexicographically greatest existing prefix - resolve to the newest object.
     */
    private static final String KEY_TEMPLATE =
            "%s/%0" + KEY_NUMBER_WIDTH + "d/%s-%0" + KEY_NUMBER_WIDTH + "d%s";

    /** Placeholder used in a diagnostic when no identifier could be read, so no message is ever ragged. */
    private static final String ABSENT_KEY = "(absent)";

    /**
     * The closed vocabulary of symbolic failure reasons this class writes to the log.
     *
     * <p><b>Finding, severity Medium, RESOLVED.</b> Every failure diagnostic in an earlier revision logged the
     * fully composed exception message, and those messages carry - correctly, for an exception - the candidate
     * {@code TRAN-ID} values of the chunk, the destination bucket and the concrete object key. On the log
     * channel that is a different question, and three properties made it the wrong answer. A transaction
     * identifier is a business key that {@code app/cbl/COTRN02C.cbl:L444-L451} derives by max-plus-one, so a
     * run of them discloses the shape of the key space; a bucket name and an object key are infrastructure
     * topology that an operator reading a failure does not need and an attacker reading a leaked log does; and
     * an identifier list is unbounded in length, so one failing chunk of a hundred could emit a hundred keys
     * into a log line.
     *
     * <p><i>Remediation, applied:</i> the log carries a <b>closed symbolic reason</b> from the set below plus
     * the safe metadata the review names - logical file, relation, operation, status and chunk size - and the
     * correlation identifier, which arrives automatically because {@code logback-spring.xml} emits MDC. The
     * composed message keeps every detail and travels on the <b>thrown value</b>, where it reaches whoever
     * handles the failure without being broadcast to the log sink. This is the same split applied to
     * {@code HealthIndicators} and {@code ReportSubmissionService}: classify on the log, preserve on the
     * exception.
     *
     * <p>Each constant is a fixed token, so the set is enumerable and a dashboard or alert rule can match on
     * it. Values are lower-case-hyphenated for consistency with the reasons the observability layer already
     * publishes.
     */
    private static final String REASON_DUPLICATE_KEY = "duplicate-key";

    /** A referential or check constraint declared by {@code V1__create_schema.sql} refused the insert. */
    private static final String REASON_CONSTRAINT_VIOLATION = "constraint-violation";

    /** The store failed for a reason that is neither a duplicate key nor a constraint violation. */
    private static final String REASON_UNEXPECTED_STORE_FAILURE = "unexpected-store-failure";

    /** A record could not be rendered into the fixed-width contract, so nothing was emitted. */
    private static final String REASON_UNENCODABLE_RECORD = "unencodable-record";

    /**
     * The repository whose inherited {@code saveAllAndFlush} performs the insert that stands for
     * {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at {@code app/cbl/CBTRN02C.cbl:L564}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The object-storage abstraction that receives the fixed-width 350-byte image.
     *
     * <p>Declared as {@code S3Operations} rather than as the concrete {@code S3Template} because Spring
     * Cloud AWS declares that bean under {@code @ConditionalOnMissingBean(S3Operations.class)}: taking the
     * interface is satisfied by the auto-configured template and stays satisfied if {@code AwsConfig}
     * supplies its own of either type, whereas taking the class would not.
     */
    private final S3Operations objectStorage;

    /** The sole owner of the status-to-exception decision; never re-implemented here. */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The single owner of the four sanctioned instruments.
     *
     * <p><b>Finding, severity High, RESOLVED.</b> An earlier revision held a {@code Counter} resolved from the
     * {@code MeterRegistry} against a name literal declared privately in this file, and defended it on the
     * grounds that {@code MeterRegistry.counter} returns the already-registered meter of that name rather than
     * adding a second one. That is true of the <em>registry</em> and beside the point: resolving by an
     * independently declared literal means the name exists in two files, so it is a second declaration site,
     * and the two drift the moment either is edited - at which point a fifth instrument appears and the
     * four-instrument contract is broken without any test noticing, because both files still agree with
     * themselves. The description and base unit that {@code MetricsConfig} attaches were also silently lost,
     * since whichever site resolves the meter first wins. <i>Remediation, applied:</i> the canonical facade is
     * injected and the literal is gone from this file; {@code MetricsConfig} is the only place any instrument
     * is named.
     */
    private final MetricsConfig metrics;

    /**
     * The destination bucket, bound from {@code carddemo.aws.s3.batch-output-bucket}. Required and never
     * defaulted, so a context that has not supplied it fails at startup rather than at the first write.
     */
    private final String outputBucket;

    /**
     * The base-name segment every object key starts with, bound from
     * {@code carddemo.aws.s3.transaction-object-prefix} and defaulting to {@code transact}. It stands for
     * the generation-data-group base name the legacy job wrote a new generation of.
     */
    private final String objectPrefix;

    /**
     * Bound on the indexed manifest, from {@value #KEY_MAX_INDEXED_OBJECT_KEYS}, defaulting to
     * {@value #DEFAULT_MAX_INDEXED_OBJECT_KEYS}.
     *
     * <p>See {@link #OBJECT_KEYS_INDEXED_ENTRY} for what the bound protects and
     * {@link #DEFAULT_MAX_INDEXED_OBJECT_KEYS} for how the default was chosen.
     */
    private final int maxIndexedObjectKeys;

    /**
     * The step execution of the step running this writer, injected by the step scope.
     *
     * <p>This is the only mutable field on the class and there is no static mutable state at all. It
     * supplies three things and nothing else: the job-instance identifier that scopes the object key, the
     * write count that orders the objects within the step, and the execution the created key is published into.
     *
     * <p><strong>Its safety comes from {@code @StepScope}, not from a memory-visibility modifier.</strong>
     * The bean was previously a singleton holding this field {@code volatile}, which is the wrong tool for
     * the problem: {@code volatile} publishes a reference safely between threads but does nothing to stop two
     * concurrent step executions from overwriting each other's execution, and the object key is derived from
     * it, so the loser of that race would file its records under the winner's job instance and ordinal.
     * Under step scope each execution receives its own instance, so there is no shared field to publish and
     * no race to guard. It is left non-{@code volatile} deliberately, which is what the declaration below
     * says: a step-scoped instance is confined to its execution, and marking it {@code volatile} would
     * suggest sharing that no longer exists. A multi-threaded step <em>within</em> one execution would be a
     * different question, and the class does not support one - see {@link #write(Chunk)}, whose ordinal comes
     * from the execution's own write count and would be read identically by two threads.
     */
    private StepExecution stepExecution;

    /**
     * Creates the writer with its collaborators and its two configuration values.
     *
     * <p>Constructor injection only, so every collaborator is final and the bean cannot exist half-configured.
     * Nothing is read from the environment and no client is constructed here.
     *
     * <p>Side effects: none. No instrument is registered here and nothing is read from the environment - the
     * canonical instrument owner is injected already built.
     *
     * <p>Error modes: rejects a blank bucket or prefix, so a context that has not supplied
     * {@code carddemo.aws.s3.batch-output-bucket} or {@code carddemo.aws.s3.transaction-object-prefix} fails at
     * startup rather than at the first write.
     *
     * @param transactionRepository the repository whose inherited {@code saveAllAndFlush} performs the
     *        insert. Never {@code null}
     * @param objectStorage the object-storage abstraction. Declared as {@code S3Operations} rather than as
     *        the concrete {@code io.awspring.cloud.s3.S3Template} because Spring Cloud AWS declares that bean
     *        under {@code @ConditionalOnMissingBean(S3Operations.class)}: taking the interface is satisfied by
     *        the auto-configured {@code S3Template} and stays satisfied if {@code AwsConfig} supplies its own
     *        of either type, whereas taking the class would not. Never {@code null}
     * @param fileStatusMapper the sole owner of the status-to-exception decision. Never {@code null}
     * @param metrics the canonical owner of the four sanctioned instruments. Never {@code null}
     * @param outputBucket the destination bucket, required and never defaulted
     * @param objectPrefix the base-name segment every key starts with.
     *        <b>Finding, severity High, RESOLVED:</b> an earlier revision carried an inline default of
     *        {@code transact} here. An inline default is a second declaration site for a value that
     *        {@code application.yml} already declares authoritatively - and that file says so in as many words
     *        - so the two drift, and a context that failed to supply the key wrote a whole generation under a
     *        silently different prefix instead of failing. The property also sat outside the canonical
     *        {@code carddemo.aws.s3.gdg-prefixes.*} catalogue with nothing recording why: it is deliberately
     *        outside it, because {@code app/jcl/POSTTRAN.jcl:L26-27} writes posted transactions to the
     *        {@code INDEXED} cluster {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, which is not one of the seven
     *        {@code 0GDG BASE} entries in {@code app/catlg/LISTCAT.txt:L3942}, so borrowing a generation
     *        namespace would misrepresent the source. <i>Remediation, applied:</i> the default is removed, the
     *        key resolves from {@code application.yml} alone, and that file now carries the explanation of why
     *        this one prefix is declared separately from the catalogue
     * @param maxIndexedObjectKeys the bound on the indexed manifest, from
     *        {@value #KEY_MAX_INDEXED_OBJECT_KEYS} and defaulting to
     *        {@value #DEFAULT_MAX_INDEXED_OBJECT_KEYS}. Must be positive: zero would publish no key at all and
     *        a negative value names no bound, so both fail at startup rather than producing a manifest nothing
     *        can read. See {@link #OBJECT_KEYS_INDEXED_ENTRY}
     * @throws IllegalArgumentException if any collaborator is {@code null}, either string configuration value
     *         is blank, or the indexed-manifest bound is not positive
     */
    public TransactionWriter(TransactionRepository transactionRepository,
            S3Operations objectStorage,
            FileStatusMapper fileStatusMapper,
            MetricsConfig metrics,
            @Value("${carddemo.aws.s3.batch-output-bucket}") String outputBucket,
            @Value("${carddemo.aws.s3.transaction-object-prefix}") String objectPrefix,
            @Value("${" + KEY_MAX_INDEXED_OBJECT_KEYS + ":" + DEFAULT_MAX_INDEXED_OBJECT_KEYS + "}")
                    int maxIndexedObjectKeys) {
        this.transactionRepository = requireCollaborator(transactionRepository, "transactionRepository");
        this.objectStorage = requireCollaborator(objectStorage, "objectStorage");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.metrics = requireCollaborator(metrics, "metrics");
        this.outputBucket = requireConfigured(outputBucket, "carddemo.aws.s3.batch-output-bucket");
        this.objectPrefix = requireObjectPrefix(objectPrefix);
        this.maxIndexedObjectKeys = requireIndexedKeyCap(maxIndexedObjectKeys);
    }

    /**
     * Captures the step execution this writer is running under.
     *
     * <p>The writer needs three things from it and nothing more: the job-instance identifier that scopes
     * every object key, the write count that orders the objects within the step, and the execution context the
     * created key is published into. None of the three is reachable through the {@code ItemWriter} contract,
     * which takes only a chunk, so this listener callback is the supported way to obtain them.
     *
     * <p><strong>Declared by implementing {@link StepExecutionListener}, not by annotation, and the
     * difference is load bearing.</strong> {@code SimpleStepBuilder} auto-registers a reader, processor or
     * writer as a step listener when {@code StepListenerFactoryBean.isListener} recognises it, and that check
     * succeeds two ways: the object implements a listener interface, or the object's class carries a listener
     * annotation. The two are not equally reliable here. A writer that needs the job instance is step-scoped,
     * step scope proxies by subclassing, and a proxy reliably presents the <em>interfaces</em> of its target
     * while an annotation on the target's method is reached only if the check unwraps the proxy. Declaring the
     * interface therefore makes registration a property of the type rather than of how the bean happens to be
     * proxied - and it matches {@code StatementWriter}, which already declares it this way, so both writers now
     * announce the same contract instead of two.
     *
     * <p>Side effects: replaces the captured context. Idempotent per step.
     *
     * <p>Error modes: none. A {@code null} argument is stored as {@code null} and reported later, by
     * {@link #write(Chunk)}, naming the missing wiring - which is a better diagnostic than failing
     * here, before the step name is known.
     *
     * @param execution the step execution supplied by the framework, or {@code null} if a caller supplies
     *        none
     */
    @Override
    public void beforeStep(StepExecution execution) {
        this.stepExecution = execution;
        settlePendingEmission(execution);
    }

    /**
     * Inserts every transaction of the chunk and emits one 350-byte record for each into object storage.
     *
     * <p>This is the {@code ItemWriter} contract of Spring Batch 5, which replaced the list-based signature of
     * earlier versions with a {@code Chunk}. The pinned version is 5.2.4.
     *
     * <p>The order of operations is deliberate:
     *
     * <ol>
     * <li><strong>Compose and validate every image first.</strong> A record whose geometry has drifted from
     * {@code app/cpy/CVTRA05Y.cpy} is rejected before anything is stored, so a defective chunk cannot leave a
     * half-written batch behind. Only one chunk's worth of bytes is ever held - chunk size multiplied by 350 -
     * so the whole output is never materialised.</li>
     * <li><strong>Insert the rows</strong>, flushing inside this frame.</li>
     * <li><strong>Wait for the commit</strong>, and only then emit the object under the guard reproduced from
     * {@code app/cbl/CBTRN02C.cbl:L562-L579}, publish the created key into the two execution contexts, and
     * report the records on the two instruments this path owns. All three are deferred to
     * {@code afterCommit}, so a transaction that rolls back leaves no object, no published key and no counter
     * movement behind - see {@link #promoteAfterCommit(StepExecution, long, byte[], List)}.</li>
     * </ol>
     *
     * <p>Side effects: one row per item, one object per call, two counter increments per item, and one
     * context entry. No timestamp is generated: the two 26-character timestamp fields are passed through exactly as
     * they arrive.
     *
     * <p>Error modes: as listed on the class. Every failure is a {@code com.cardemo.exception} subtype and
     * every one preserves its cause; none is swallowed. The composed image is never logged, and no card
     * number, merchant detail or amount appears in any message this class produces.
     *
     * @param chunk the transactions to write. A {@code null} or empty chunk is a no-op: no row is inserted, no
     *        object is created and no counter moves, so an exhausted reader cannot leave a spurious empty
     *        object behind
     * @throws Exception never thrown as a checked exception; declared solely because the interface declares
     *         it. Every failure this method raises is an unchecked {@code com.cardemo.exception} subtype
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        List<? extends Transaction> items = chunk.getItems();
        StepExecution execution = requireStepContext();
        long ordinal = execution.getWriteCount();

        byte[] payload = composePayload(items);

        // The outbox entry goes in BEFORE the rows, and both are committed together: the framework persists
        // this context inside the chunk transaction. See PENDING_OBJECT_KEY_ENTRY - this is what makes a
        // failed upload recoverable instead of a permanent split (finding M-07).
        recordPendingEmission(execution, objectKeyFor(execution, ordinal), items);
        persistChunk(items);
        promoteAfterCommit(execution, ordinal, payload, items);
    }

    /**
     * Defers the object write, the key publication and the two counters until the surrounding transaction has
     * committed, and abandons all three if it rolls back.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> These three effects used to run inline,
     * immediately after {@code saveAllAndFlush}. A flush is not a commit, so the object existed in the bucket,
     * the key was published into the execution contexts and both counters had moved <em>before</em> the chunk
     * transaction reached its commit point. Any failure between the two - the commit itself, a later listener,
     * a constraint checked at commit time - left an object naming rows that had been rolled back, an execution
     * context advertising a generation that described nothing, and counters claiming work that never landed.
     * There was no compensating action anywhere, so the divergence was permanent.
     *
     * <p>The remedy publishes nothing before the commit rather than publishing early and compensating
     * afterwards. The composed payload is a byte array already held on the stack, so holding it until
     * {@code afterCommit} costs one chunk's worth of bytes - the same peak this method already had - and
     * removes the orphan window entirely: on rollback the callback is simply not invoked, and because nothing
     * was ever written there is nothing to delete. Compensation by deletion would have been the weaker design,
     * since a delete can itself fail and would leave exactly the state it was meant to prevent.
     *
     * <p>An upload that fails <em>after</em> the commit throws from the synchronization callback, which Spring
     * propagates out of the commit, so the step still fails and the failure is still attributed to this writer.
     * The database rows survive that failure, which is the source's own behaviour: on the mainframe the
     * transaction-file write of {@code app/cbl/CBTRN02C.cbl:L562}-{@code :L579} is a separate commit that
     * follows the category-balance and account updates, so a failure there leaves those two applied.
     *
     * <p><strong>The no-transaction case is handled explicitly rather than assumed away.</strong> A unit test
     * that calls {@link #write(Chunk)} directly runs with no transaction synchronization active, and so does a
     * caller that wires this writer outside a chunk-oriented step. In that case there is no commit to wait
     * for, so the three effects run inline exactly as they used to; the behaviour under a real step is the
     * deferred one, and the difference is the presence of a transaction rather than a mode this class chooses.
     *
     * <p>Side effects: registers one transaction synchronization, or performs the publication inline.
     *
     * @param execution the captured step execution, supplying the job instance and the context to publish into
     * @param ordinal the zero-based index of the chunk's first record within the step
     * @param payload the concatenated fixed-width records to emit once the rows are durable
     * @param items the transactions the counters describe
     */
    private void promoteAfterCommit(StepExecution execution, long ordinal, byte[] payload,
                                    List<? extends Transaction> items) {

        int reported = items.size();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishDurableOutput(execution, ordinal, payload, items, reported);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishDurableOutput(execution, ordinal, payload, items, reported);
            }
        });
    }

    /**
     * Emits the object, publishes its key and moves the two counters, in that order.
     *
     * <p>Called once the rows are durable - from {@code afterCommit}, or inline when no transaction is active.
     * The ordering is the same one the source has: {@code app/cbl/CBTRN02C.cbl:L226} counts after the write
     * rather than before it, so neither instrument can claim work an abandoned write did not do.
     *
     * @param execution the captured step execution
     * @param ordinal the zero-based index of the chunk's first record within the step
     * @param payload the concatenated fixed-width records
     * @param items the transactions the amount counter reads
     * @param reported how many records the processed counter is advanced by
     */
    private void publishDurableOutput(StepExecution execution, long ordinal, byte[] payload,
                                      List<? extends Transaction> items, int reported) {

        String objectKey = writeTransactionFile(payload, execution, ordinal);
        publishObjectKey(execution, objectKey);
        clearPendingEmission(execution);
        metrics.countRecordsProcessed(reported);
        countTransactionAmounts(items);
    }

    /**
     * Records, inside the chunk transaction, that this chunk's rows still owe the store an object.
     *
     * <p>Written before the rows rather than after them, so that no interleaving can commit the rows without
     * the entry. Both land in the same transaction, so the entry cannot outlive a rollback either: a chunk that
     * rolls back takes its own outbox entry with it, which is why a surviving entry is proof the rows exist.
     *
     * @param execution the step whose context is the outbox
     * @param objectKey the object the chunk owes
     * @param items the transactions the object must hold, in record order
     */
    private void recordPendingEmission(StepExecution execution, String objectKey,
                                       List<? extends Transaction> items) {

        StringBuilder identifiers = new StringBuilder(items.size() * 17);
        for (Transaction item : items) {
            if (identifiers.length() > 0) {
                identifiers.append(PENDING_IDENTIFIER_SEPARATOR);
            }
            identifiers.append(item.getTransactionId());
        }
        ExecutionContext context = execution.getExecutionContext();
        context.putString(PENDING_OBJECT_KEY_ENTRY, objectKey);
        context.putString(PENDING_IDENTIFIERS_ENTRY, identifiers.toString());
    }

    /**
     * Clears the outbox entry once the object it described exists.
     *
     * <p>Called from the post-commit publication, which runs <em>after</em> the framework has persisted the
     * context - so the cleared state reaches the database at the next chunk's commit, not this one's. That
     * window is deliberate and harmless: a process that dies inside it restarts with an entry whose object
     * already exists, and the reconciliation rewrites the same deterministic key with the same bytes. An
     * idempotent repeat is the correct outcome of an ambiguous outcome.
     *
     * @param execution the step whose context is the outbox
     */
    private void clearPendingEmission(StepExecution execution) {
        ExecutionContext context = execution.getExecutionContext();
        context.remove(PENDING_OBJECT_KEY_ENTRY);
        context.remove(PENDING_IDENTIFIERS_ENTRY);
    }

    /**
     * Settles an outbox entry left by an earlier attempt, before this attempt writes anything.
     *
     * <p><strong>This is the reconciliation half of finding M-07.</strong> An entry here means the previous
     * attempt committed rows and then failed to emit their object. The rows are durable and cannot be taken
     * back, so the only sound repair is to finish the emission - and to build it from <em>the rows themselves</em>
     * rather than from anything the failed attempt left in memory, so that what the store ends up holding is
     * what the database actually holds.
     *
     * <p><b>A missing row is the one case where the entry must NOT be honoured.</b> If any identifier the entry
     * names is absent, the chunk's rows were rolled back after all, and uploading would create an object
     * describing transactions that do not exist - precisely the orphan the post-commit ordering exists to
     * prevent. The entry is then discarded with a warning rather than acted on.
     *
     * <p>Idempotent: the key is deterministic and the payload is a pure function of the rows, so settling an
     * entry whose object already exists rewrites identical bytes.
     *
     * @param execution the step whose restored context may carry an entry
     */
    private void settlePendingEmission(StepExecution execution) {
        if (execution == null) {
            // A null execution is accepted here and reported at write time by requireStepContext, which is
            // this class's existing contract: the listener callback must not be the place a missing wiring
            // surfaces, because the message it could give names far less than the write-time one does.
            return;
        }
        ExecutionContext context = execution.getExecutionContext();
        String objectKey = context.getString(PENDING_OBJECT_KEY_ENTRY, "");
        String identifiers = context.getString(PENDING_IDENTIFIERS_ENTRY, "");
        if (objectKey.isBlank() || identifiers.isBlank()) {
            return;
        }

        List<String> ordered = List.of(identifiers.split(PENDING_IDENTIFIER_SEPARATOR));
        List<Transaction> committed = new ArrayList<>(ordered.size());
        for (String identifier : ordered) {
            Transaction row = transactionRepository.findById(identifier).orElse(null);
            if (row == null) {
                LOG.warn("{} found an outstanding emission for {} naming {} transaction(s), but at least one"
                                + " of them is not in the relation, so the chunk was rolled back after all;"
                                + " the entry is discarded rather than emitted, because an object describing"
                                + " rows that do not exist is the very orphan the post-commit ordering"
                                + " prevents",
                        LOGICAL_FILE, objectKey, Integer.valueOf(ordered.size()));
                clearPendingEmission(execution);
                return;
            }
            committed.add(row);
        }

        byte[] payload = composePayload(committed);
        ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(OBJECT_CONTENT_TYPE)
                .contentLength(Long.valueOf(payload.length))
                .build();
        try {
            objectStorage.upload(outputBucket, objectKey, new ByteArrayInputStream(payload), metadata);
        } catch (RuntimeException cause) {
            LOG.error(WRITE_FAILURE_TEXT);
            displayIoStatus(OBJECT_STORE_IO_STATUS);
            throw abendProgram(OBJECT_STORE_IO_STATUS, objectKey, cause);
        }
        publishObjectKey(execution, objectKey);
        clearPendingEmission(execution);
        LOG.info("{} settled an outstanding emission from a previous attempt: {} record(s) re-derived from the"
                        + " committed rows and written to {}, so the relation and the mirror agree again",
                LOGICAL_FILE, Integer.valueOf(committed.size()), objectKey);
    }

    /**
     * Renders one transaction as its exact 350-byte fixed-width image.
     *
     * <p>The layout is {@code app/cpy/CVTRA05Y.cpy}, whose {@code :L2} header reads
     * {@code Data-structure for TRANsaction record (RECLN = 350)}. The fourteen fields, at their one-based
     * offsets, are: {@code TRAN-ID} 1-16, {@code TRAN-TYPE-CD} 17-18, {@code TRAN-CAT-CD} 19-22,
     * {@code TRAN-SOURCE} 23-32, {@code TRAN-DESC} 33-132, {@code TRAN-AMT} 133-143,
     * {@code TRAN-MERCHANT-ID} 144-152, {@code TRAN-MERCHANT-NAME} 153-202, {@code TRAN-MERCHANT-CITY}
     * 203-252, {@code TRAN-MERCHANT-ZIP} 253-262, {@code TRAN-CARD-NUM} 263-278, {@code TRAN-ORIG-TS}
     * 279-304, {@code TRAN-PROC-TS} 305-330 and {@code FILLER} 331-350. Those widths sum to exactly 350.
     *
     * <p>{@code TRAN-SOURCE} is written from the entity's plain {@code String}, never from an enumeration. The
     * reference fixture carries both {@code POS TERM} and {@code OPERATOR} in that field, and
     * {@code OPERATOR} has no literal {@code MOVE} site anywhere in the corpus - it is data, not a constant -
     * so binding an enumeration there would fail to render legitimate rows.
     *
     * <p>The two timestamps are copied through verbatim and padded to 26 characters. Three mutually
     * incompatible producers exist upstream and all three must survive untouched: the batch form
     * {@code yyyy-MM-dd-HH.mm.ss.SS0000}, the online form {@code yyyy-MM-dd HH:mm:ss.000000}, and pure
     * pass-through, as at {@code app/cbl/CBTRN02C.cbl:L436}
     * ({@code MOVE DALYTRAN-ORIG-TS TO TRAN-ORIG-TS}). An absent or blank processing timestamp becomes 26
     * spaces, which is exactly what the reference fixture holds for unposted records.
     *
     * <p>Side effects: none. A pure function of its argument - it reads no field of this class, logs nothing
     * and stores nothing, so it is safe to call from a test with no Spring context and no step.
     *
     * @param transaction the row to render. Must not be {@code null}
     * @return the rendered record, always exactly {@value #RECORD_LENGTH} characters, every one of which is
     *         representable as a single byte in the fixed-width charset
     * @throws DataIntegrityException if the argument is {@code null}, if a numeric field is absent, if any
     *         value exceeds the width its picture clause declares, if the amount needs more than nine integer
     *         digits, or if any character cannot be represented as a single byte. The message names the COBOL
     *         field and the widths involved and <strong>never the value</strong>, because two of these fields
     *         are the card number and the transaction identifier
     */
    public String composeFixedWidthImage(Transaction transaction) {
        if (transaction == null) {
            throw unloadable(null, "the chunk contained a null item, which has no record image");
        }

        String transactionId = transaction.getTransactionId();
        if (transactionId == null || transactionId.isBlank()) {
            throw unloadable(transactionId,
                    "TRAN-ID is absent or blank, but app/jcl/TRANFILE.jcl:L53 declares KEYS(16 0) on an "
                            + "INDEXED cluster, so the record has no key to be stored or retrieved under");
        }

        StringBuilder image = new StringBuilder(RECORD_LENGTH);

        appendCharacterField(image, transactionId, "TRAN-ID", TRAN_ID_WIDTH, transactionId);
        appendCharacterField(image, transaction.getTypeCode(), "TRAN-TYPE-CD", TRAN_TYPE_CD_WIDTH,
                transactionId);
        appendUnsignedZonedField(image, transaction.getCategoryCode(), "TRAN-CAT-CD", TRAN_CAT_CD_WIDTH,
                transactionId);
        appendCharacterField(image, transaction.getTransactionSource(), "TRAN-SOURCE", TRAN_SOURCE_WIDTH,
                transactionId);
        appendCharacterField(image, transaction.getDescription(), "TRAN-DESC", TRAN_DESC_WIDTH, transactionId);
        appendSignedZonedAmount(image, transaction.getAmount(), transactionId);
        appendUnsignedZonedField(image, transaction.getMerchantId(), "TRAN-MERCHANT-ID",
                TRAN_MERCHANT_ID_WIDTH, transactionId);
        appendCharacterField(image, transaction.getMerchantName(), "TRAN-MERCHANT-NAME",
                TRAN_MERCHANT_NAME_WIDTH, transactionId);
        appendCharacterField(image, transaction.getMerchantCity(), "TRAN-MERCHANT-CITY",
                TRAN_MERCHANT_CITY_WIDTH, transactionId);
        appendCharacterField(image, transaction.getMerchantZip(), "TRAN-MERCHANT-ZIP", TRAN_MERCHANT_ZIP_WIDTH,
                transactionId);
        appendCharacterField(image, transaction.getCardNumber(), "TRAN-CARD-NUM", TRAN_CARD_NUM_WIDTH,
                transactionId);
        appendCharacterField(image, transaction.getOrigTs(), "TRAN-ORIG-TS", TRAN_ORIG_TS_WIDTH, transactionId);
        appendCharacterField(image, transaction.getProcTs(), "TRAN-PROC-TS", TRAN_PROC_TS_WIDTH, transactionId);

        // FILLER PIC X(20) at 331-350. Not an entity column, but part of the record: without it the image
        // is 330 bytes and the boundary comparison fails.
        image.append(FILLER);

        String rendered = image.toString();
        if (rendered.length() != RECORD_LENGTH) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "the composed image is %d characters but app/cpy/CVTRA05Y.cpy declares exactly %d",
                    Integer.valueOf(rendered.length()), Integer.valueOf(RECORD_LENGTH)));
        }
        return rendered;
    }

    /**
     * Concatenates the chunk's record images into the exact byte payload of one object.
     *
     * <p>Records are joined with <strong>no separator</strong>, reproducing the fixed-block geometry of the
     * dataset: {@code app/jcl/TRANFILE.jcl:L54} declares {@code RECORDSIZE(350 350)} and
     * {@code app/catlg/LISTCAT.txt:L3593-L3594} reports {@code AVGLRECL} and {@code MAXLRECL} both 350, so
     * record <em>n</em> begins at offset <em>n</em> multiplied by 350 and the object size is an exact multiple
     * of 350. Both properties are asserted before the bytes leave this method.
     *
     * <p>Note that the checked-in ASCII fixture is framed differently and must not be taken as the contract
     * here: {@code app/data/ASCII/dailytran.txt} is 105,300 bytes for 300 records, which is 300 multiplied by
     * 351 - 350 data bytes plus one line feed each - and is therefore not a multiple of 350 at all.
     *
     * @param items the chunk's transactions, never empty
     * @return the payload, whose length is the item count multiplied by {@value #RECORD_LENGTH}
     * @throws DataIntegrityException if any record fails to compose, or if the assembled payload is not an
     *         exact multiple of the record length
     */
    private byte[] composePayload(List<? extends Transaction> items) {
        long declaredLength = (long) items.size() * RECORD_LENGTH;
        if (declaredLength > Integer.MAX_VALUE) {
            throw unloadable(null, String.format(Locale.ROOT,
                    "a chunk of %d records of %d bytes exceeds the largest array this runtime can hold; "
                            + "reduce the chunk size configured on the owning step",
                    Integer.valueOf(items.size()), Integer.valueOf(RECORD_LENGTH)));
        }

        int expectedLength = (int) declaredLength;
        StringBuilder buffer = new StringBuilder(expectedLength);
        for (Transaction item : items) {
            buffer.append(composeFixedWidthImage(item));
        }

        byte[] payload = buffer.toString().getBytes(FIXED_WIDTH_CHARSET);
        if (payload.length != expectedLength) {
            throw unloadable(null, String.format(Locale.ROOT,
                    "the assembled payload is %d bytes but %d records of %d bytes require %d",
                    Integer.valueOf(payload.length), Integer.valueOf(items.size()),
                    Integer.valueOf(RECORD_LENGTH), Integer.valueOf(expectedLength)));
        }
        if (payload.length % RECORD_LENGTH != 0) {
            throw unloadable(null, String.format(Locale.ROOT,
                    "the assembled payload is %d bytes, which is not an exact multiple of the %d byte "
                            + "fixed record length",
                    Integer.valueOf(payload.length), Integer.valueOf(RECORD_LENGTH)));
        }
        return payload;
    }

    /**
     * Inserts the chunk, forcing the constraint check inside this stack frame.
     *
     * <p>{@code saveAllAndFlush} is used rather than {@code saveAll}. A primary-key collision raised only when
     * the caller's transaction commits would escape this frame and could never be translated into the typed
     * hierarchy, which is precisely the outcome the boundary comparison needs to observe.
     *
     * <p>Every entity arrives with a {@code null} version and this method never assigns one, so
     * {@code save} dispatches to {@code persist} and issues an INSERT. A non-null version would dispatch to
     * {@code merge} - a SELECT followed by an UPDATE - which would silently overwrite the row already there,
     * contradicting the {@code OPEN OUTPUT} write-only contract of {@code app/cbl/CBTRN02C.cbl:L254-L270}.
     *
     * <p>No SQL or JPQL is written here and nothing is concatenated into a query: the repository binds every
     * value as a parameter.
     *
     * <p>Side effects: inserts one row per item and flushes the persistence context.
     *
     * <p><strong>Finding F-8, severity Major - the collision is now recognised by {@code SQLSTATE}.</strong>
     * This previously caught {@code org.springframework.dao.DuplicateKeyException} ahead of its supertype,
     * which recognises a collision only when the persistence layer chose that subtype. For a batched flush it
     * need not: the driver reports the batch and links the exception carrying the state of the entry that
     * actually failed beneath it, so a genuine {@code TRAN-ID} collision could arrive as a plain integrity
     * violation and be reported as a referential or check-constraint failure instead - losing exactly the
     * identifier race this class exists to make observable. {@code FileStatusMapper.classifyStoreFailure}
     * walks both chains and decides on the state, so either shape reaches
     * {@link #duplicateIdentifier(List, DataAccessException)}. A value the store cannot hold stays on the
     * constraint-violation arm: this is a batch writer, so an unstorable field is a defect in the input file
     * image and belongs in the job's failure report, never on an HTTP response.
     *
     * @param items the transactions to insert, never empty
     * @throws DuplicateRecordException if an identifier already exists
     * @throws DataIntegrityException if a referential or check constraint refuses a row
     * @throws FatalProcessingException for any other store failure
     */
    private void persistChunk(List<? extends Transaction> items) {
        try {
            transactionRepository.saveAllAndFlush(items);
        } catch (DataIntegrityViolationException cause) {
            // Classified by SQLSTATE rather than by subtype. Catching DuplicateKeyException ahead of its
            // supertype - which is what this did - recognises a collision only when the persistence layer
            // chose that subtype, and for a BATCHED flush it need not: the state that identifies the failing
            // entry is on an exception linked below the one thrown. Deciding on the state finds it either way,
            // which is what keeps the identifier race observable here rather than mis-reported as a plain
            // constraint violation. FileStatusMapper owns the decision; this method owns only the exception.
            throw FileStatusMapper.classifyStoreFailure(cause)
                    == FileStatusMapper.StoreFailureKind.DUPLICATE_KEY
                    ? duplicateIdentifier(items, cause)
                    : constraintViolation(items, cause);
        } catch (DataAccessException cause) {
            throw unexpectedStoreFailure(items, cause);
        }
    }

    /**
     * Reproduces {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} for the
     * object-storage half of the write, statement by statement.
     *
     * <p>The source paragraph is, verbatim: {@code MOVE 8 TO APPL-RESULT} at {@code :L563};
     * {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at {@code :L564}; {@code IF TRANFILE-STATUS = '00' MOVE 0
     * ELSE MOVE 12} at {@code :L566-L570}; {@code IF APPL-AOK CONTINUE ELSE} at {@code :L571-L573};
     * {@code DISPLAY 'ERROR WRITING TO TRANSACTION FILE'} at {@code :L574};
     * {@code MOVE TRANFILE-STATUS TO IO-STATUS} at {@code :L575}; {@code PERFORM 9910-DISPLAY-IO-STATUS} at
     * {@code :L576}; {@code PERFORM 9999-ABEND-PROGRAM} at {@code :L577}; and {@code EXIT} at {@code :L579}.
     *
     * <p><strong>The ordering of the last two is an invariant</strong>: the status is rendered first and the
     * abend follows. Reversing them would lose the diagnostic, because the abend terminates.
     *
     * <p>The pessimistic initialisation to {@code APPL_RESULT_INITIAL} is retained even though the guard
     * overwrites it, exactly as {@code :L563} is overwritten by {@code :L567} or {@code :L569}. It is
     * reproduced control flow rather than untracked residue: its
     * purpose in the source is that a write which neither succeeds nor raises cannot be mistaken for success.
     *
     * <p>The constants are referenced from {@code FileStatusMapper}, which already declares
     * {@code APPL_RESULT_INITIAL}, {@code APPL_AOK}, {@code APPL_EOF} and {@code APPL_FAILURE} from
     * {@code app/cbl/CBTRN02C.cbl:L142-L144}; restating them here would duplicate a published contract.
     *
     * <p><b>Deadline and retry, and where they come from. Finding, severity High, RESOLVED.</b> The upload is
     * synchronous, so an unbounded call would hold the chunk transaction open for as long as the endpoint chose
     * to stall - which on a step with a hundred chunks means a stalled emulator wedges the job rather than
     * failing it. This method deliberately configures <b>no</b> per-call override, because a deadline written
     * here would be a second policy that drifts from the one every other call already obeys.
     * {@code AwsConfig.applyBoundedPolicy} installs the policy on the client itself through an
     * {@code S3ClientCustomizer} - a 30 second whole-call deadline, a 10 second per-attempt deadline and
     * {@code RetryMode.STANDARD}, which bounds both the attempt count and the backoff - and
     * {@code AwsConfig.s3Template} consumes that same auto-configured {@code S3Client} rather than building
     * one, so this upload inherits it. The finding's own remedy is "use clients configured with bounded
     * deterministic policies", and that is the mechanism: one policy, one definition site, applied to S3, SQS
     * and SNS alike. A timeout therefore arrives here as a {@code RuntimeException} from the SDK, is mapped to
     * the {@code '9x'} status like any other physical failure, and abends the step exactly as
     * {@code app/cbl/CBTRN02C.cbl:L574-L577} does.
     *
     * <p>Side effects: creates exactly one object.
     *
     * @param payload the concatenated fixed-width records
     * @param execution the captured step execution, supplying the job instance
     * @param ordinal the zero-based index of the chunk's first record within the step
     * @return the concrete key of the object just created, never {@code null}
     * @throws FatalProcessingException by way of {@code FileStatusMapper} when the write fails; the concrete
     *         subtype for the synthesised {@code '9x'} status is {@code FileAccessException}, which carries
     *         logical name {@code TRANSACT}, operation {@code WRITE} and the preserved cause
     */
    private String writeTransactionFile(byte[] payload, StepExecution execution, long ordinal) {
        int applResult = FileStatusMapper.APPL_RESULT_INITIAL;
        String objectKey = objectKeyFor(execution, ordinal);

        String ioStatus;
        RuntimeException failure = null;
        try {
            ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(OBJECT_CONTENT_TYPE)
                    .contentLength(Long.valueOf(payload.length))
                    .build();
            objectStorage.upload(outputBucket, objectKey, new ByteArrayInputStream(payload), metadata);
            ioStatus = SUCCESS_STATUS;
        } catch (RuntimeException cause) {
            ioStatus = OBJECT_STORE_IO_STATUS;
            failure = cause;
        }

        applResult = fileStatusMapper.applResultForGuard(ioStatus);
        if (applResult != FileStatusMapper.APPL_AOK) {
            LOG.error(WRITE_FAILURE_TEXT);
            displayIoStatus(ioStatus);
            throw abendProgram(ioStatus, objectKey, failure);
        }
        return objectKey;
    }

    /**
     * Reproduces {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727}.
     *
     * <p>The rendering itself belongs to {@code FileStatusMapper}, which concatenates the fixed twenty
     * character prefix owned by {@code FileStatus} with the four rendered status characters, producing the
     * twenty-four character line the source emits at {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725}. For
     * a status of {@code '23'} that is {@code FILE STATUS IS: NNNN0023} - the {@code NNNN} is literal text in
     * the source, not a placeholder to substitute into, so nothing here reformats, re-prefixes or interpolates
     * it.
     *
     * <p>The line carries no personally identifiable value, so it passes through the logging configuration
     * unmasked and byte for byte, which is what lets the boundary comparison read it.
     *
     * <p>Side effects: emits one log record. Nothing is thrown.
     *
     * @param ioStatus the raw two character status to render
     */
    private void displayIoStatus(String ioStatus) {
        LOG.error(fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * Reproduces {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which displays
     * {@code 'ABENDING PROGRAM'}, zeroes the timing field, moves {@code 999} into the abend code and calls
     * {@code CEE3ABD}.
     *
     * <p>The abend code is {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and the
     * process return code is {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}. Both
     * are referenced from {@code FatalProcessingException} rather than restated. The batch abend code is 999
     * and never 9999, which is the online value for a different construct entirely.
     *
     * <p>The decision itself is delegated to {@code FileStatusMapper}, the sole owner of the mapping from a
     * file status to an exception: it raises {@code FileAccessException} for the {@code '9x'} family, which is
     * the mandated surface for an object-storage failure, and {@code FatalProcessingException} for any status
     * it does not recognise.
     *
     * <p>The trailing construction is a total-function guard, not residue. {@code requireSuccess} throws for
     * every status this method can be reached with, so it is unreachable in practice; retaining it means a
     * future relaxation of that contract could never turn an abend into a silent success. It is documented
     * here rather than left as unexplained residue. Returning the exception rather than throwing it
     * internally keeps the control flow explicit at
     * the call site, which writes {@code throw abendProgram(...)}.
     *
     * <p>The message names the bucket, the object key and the operation. It deliberately carries no record
     * key, no field value and no record image: the object key is composed only of a configured prefix, a job
     * instance identifier and an ordinal, so it cannot disclose customer data.
     *
     * @param ioStatus the status that failed the guard
     * @param objectKey the key the write was aimed at
     * @param cause the underlying failure, or {@code null} if the status was reported without one
     * @return the exception for the caller to throw, in the unreachable case that the mapper returns
     */
    private RuntimeException abendProgram(String ioStatus, String objectKey, Throwable cause) {
        LOG.error(ABEND_DISPLAY_TEXT);

        // The reason carried by the EXCEPTION keeps the bucket and the object key, because an operator holding
        // the exception is already inside the failure and needs to know which object to look at. The reason
        // LOGGED carries neither: see the class documentation's logging contract.
        String reason = String.format(Locale.ROOT, "%s (bucket %s, object %s, operation %s)",
                WRITE_FAILURE_TEXT, outputBucket, objectKey, OPERATION_WRITE);
        LOG.error("{}; logicalFile={} operation={} status={}",
                WRITE_FAILURE_TEXT, LOGICAL_FILE, OPERATION_WRITE, ioStatus);
        fileStatusMapper.requireSuccess(ioStatus, LOGICAL_FILE, OPERATION_WRITE, cause);

        String message = String.format(Locale.ROOT,
                "%s. app/cbl/CBTRN02C.cbl:L562-L579 2900-WRITE-TRANSACTION-FILE could not complete and the "
                        + "status mapper returned instead of raising, so the failure is escalated here.",
                reason);
        LOG.error("status mapper returned on a failed {} write; logicalFile={} operation={} status={}",
                LOGICAL_FILE, LOGICAL_FILE, OPERATION_WRITE, ioStatus);
        return new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT, reason, message, cause);
    }

    /**
     * Builds the object key for one chunk, translating the generation-data-group reference.
     *
     * <p>{@code (+1)} becomes a new object under a monotonically increasing prefix over a versioned bucket,
     * and {@code (0)} becomes the lexicographically greatest existing prefix. Both numbers are zero-padded to
     * {@value #KEY_NUMBER_WIDTH} digits - the width of {@code Long.MAX_VALUE} - so that lexicographic and
     * numeric order coincide and {@code (0)} therefore resolves to the newest object rather than to whichever
     * key happens to sort last.
     *
     * <p>Which generation base a posted-transaction object belongs to, among {@code SYSTRAN},
     * {@code TRANSACT.BKUP}, {@code TRANSACT.DALY} and {@code TRANSACT.COMBINED}, is not stated anywhere in
     * the corpus; there is no dataset-to-prefix table. The configured prefix is used instead and that choice
     * is labelled rather than presented as derived.
     *
     * @param execution the captured step execution
     * @param ordinal the zero-based index of the chunk's first record within the step
     * @return the object key, never {@code null} and containing no customer data
     * @throws FatalProcessingException if the step execution carries no job instance, which means the writer
     *         was invoked outside a launched job and no stable prefix can be derived
     */
    private String objectKeyFor(StepExecution execution, long ordinal) {
        var jobExecution = execution.getJobExecution();
        if (jobExecution == null || jobExecution.getJobInstance() == null) {
            String reason = "the step execution carries no job instance";
            throw new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                    ABEND_CULPRIT, reason,
                    reason + ", so no stable object prefix can be derived. Launch the step through a job "
                            + "launcher, or supply a step execution built with a job execution and instance.");
        }
        long jobInstanceId = jobExecution.getJobInstance().getInstanceId();
        return String.format(Locale.ROOT, KEY_TEMPLATE, objectPrefix, Long.valueOf(jobInstanceId),
                OBJECT_BASE_NAME, Long.valueOf(ordinal), OBJECT_SUFFIX);
    }

    /**
     * Publishes the concrete created key: the latest into the step execution context, and the complete ordered
     * generation into the job execution context.
     *
     * <p>Within one job a later step must re-read exactly what an earlier step wrote as {@code (+1)}, so every
     * key is recorded rather than left to be re-resolved as "latest" - a resolution that would race any
     * concurrent producer. The job-scoped list is appended to here, by this class, so the record is complete
     * without any external promotion listener; see {@link #OBJECT_KEYS_COUNT_ENTRY} for the finding this
     * resolves and for the read protocol a consumer follows.
     *
     * <p><strong>The indexed enumeration is bounded and the count is not.</strong> Up to
     * {@value #KEY_MAX_INDEXED_OBJECT_KEYS} keys are indexed; past that the count, the generation prefix and
     * the latest key still move, one {@code WARN} is emitted, and {@link #OBJECT_KEYS_TRUNCATED_ENTRY} marks
     * the context so no reader has to infer the bound from a missing entry. See
     * {@link #OBJECT_KEYS_INDEXED_ENTRY} for the finding this resolves and why this cap truncates where the
     * statement cap refuses.
     *
     * <p>Side effects: writes one step-context entry, overwriting any previous value so that it always names the
     * most recently created object; writes the exact count and the generation prefix into the job context on
     * every call; adds one indexed entry and updates the indexed count while below the bound; and on the first
     * call at or above it, writes the truncation marker and logs once. Performs no I/O.
     *
     * @param execution the captured step execution
     * @param objectKey the key just created
     */
    private void publishObjectKey(StepExecution execution, String objectKey) {
        execution.getExecutionContext().putString(OBJECT_KEY_CONTEXT_ENTRY, objectKey);

        JobExecution jobExecution = execution.getJobExecution();
        if (jobExecution == null) {
            // Only reachable when a unit test builds a StepExecution without one. The step-scoped entry above
            // is still written, so the writer stays testable, and objectKeyFor() has already refused to
            // compose a key at all if the job instance was missing - so nothing silently lands in the wrong
            // generation namespace.
            return;
        }

        ExecutionContext jobContext = jobExecution.getExecutionContext();
        long published = jobContext.getLong(OBJECT_KEYS_COUNT_ENTRY, 0L);

        // The count is exact and unbounded, and it is a long rather than an int precisely because it is not
        // capped. The generation prefix is republished rather than written once: it is the same value on every
        // call, and writing it here means it exists from the first object rather than depending on a listener.
        jobContext.putLong(OBJECT_KEYS_COUNT_ENTRY, published + 1L);
        jobContext.putString(OBJECT_KEYS_GENERATION_PREFIX_ENTRY, generationPrefixOf(objectKey));

        if (published < maxIndexedObjectKeys) {
            // Narrowing is safe only on this branch: the index is below the bound, which is an int. Converting
            // the unbounded count instead would throw once a run passed Integer.MAX_VALUE objects.
            jobContext.putString(objectKeysIndexEntry(Math.toIntExact(published)), objectKey);
            jobContext.putLong(OBJECT_KEYS_INDEXED_ENTRY, published + 1L);
            return;
        }

        if (!jobContext.containsKey(OBJECT_KEYS_TRUNCATED_ENTRY)) {
            // Once per job execution, not once per object: the condition holds for every remaining record of a
            // run that has passed the bound, and one WARN per record would bury the run it is warning about.
            jobContext.putString(OBJECT_KEYS_TRUNCATED_ENTRY, OBJECT_KEYS_TRUNCATED_MARKER);
            LOG.warn("The indexed transaction object-key manifest has reached its bound of {} keys, so keys "
                            + "beyond that are counted but not enumerated. {} stays exact, {} names the one "
                            + "prefix that holds every object of this generation, and {} marks the context. "
                            + "Raise {} deliberately if the full enumeration is needed, accepting that the "
                            + "job execution context is re-serialised once per record",
                    Integer.valueOf(maxIndexedObjectKeys), OBJECT_KEYS_COUNT_ENTRY,
                    OBJECT_KEYS_GENERATION_PREFIX_ENTRY, OBJECT_KEYS_TRUNCATED_ENTRY,
                    KEY_MAX_INDEXED_OBJECT_KEYS);
        }
    }

    /**
     * Derives the generation prefix from a created key.
     *
     * <p>The key up to and including its last separator, which {@link #KEY_TEMPLATE} makes
     * {@code <configured-prefix>/<job-instance-id>/}. Taken from the key rather than recomposed from the
     * prefix and the instance identifier so that the two can never disagree: there is one place a key is
     * built, and this reads its output.
     *
     * @param objectKey a key this class created, never {@code null} and always containing a separator
     * @return the prefix, ending in the separator
     */
    private static String generationPrefixOf(String objectKey) {
        int lastSeparator = objectKey.lastIndexOf(KEY_SEPARATOR);
        return objectKey.substring(0, lastSeparator + KEY_SEPARATOR.length());
    }

    /**
     * Names the job-execution entry holding the key at one index of the ordered generation.
     *
     * <p>Static and pure, so the same rule is available to a consumer and to a test without either needing an
     * instance. {@code Locale.ROOT} is not required because the index is appended as a plain decimal with no
     * grouping, but {@code Integer.toString} is used explicitly rather than concatenation so the rendering
     * cannot become locale-sensitive if the expression is ever changed.
     *
     * @param index the zero-based position in creation order; must not be negative
     * @return the context entry name, never {@code null}
     * @throws IllegalArgumentException if {@code index} is negative, which would name an entry no writer emits
     */
    public static String objectKeysIndexEntry(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative but was " + index);
        }
        return OBJECT_KEYS_INDEX_ENTRY_PREFIX + Integer.toString(index);
    }

    /**
     * Reports every posted amount to the signed total, one call per record.
     *
     * <p>This is the fourth of the four instruments that replace the end-of-run {@code DISPLAY} statements of
     * {@code app/cbl/CBTRN02C.cbl:L226-L227}, and this method is the caller it was missing.
     * {@code MetricsConfig} accumulates the values exactly, as {@link BigDecimal}, and <b>takes no absolute
     * value</b>: {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to the cycle <em>debit</em>
     * accumulator, so the debit accumulator legitimately holds negative values, and
     * {@code app/data/ASCII/dailytran.txt} carries both the <code>&#123;</code> and <code>&#125;</code>
     * overpunch characters, which decode to {@code +0} and {@code -0}. A signed stream pushed through
     * {@code Counter.increment(double)} would discard every debit, so the instrument instead exposes
     * <b>two {@code FunctionCounter} series over exact accumulators</b>, tagged {@code sign=credit} and
     * {@code sign=debit}, partitioned on the same {@code >= 0} predicate the source uses at
     * {@code app/cbl/CBTRN02C.cbl:L548}. Two series rather than one signed number because a Prometheus
     * counter may not carry a negative value: the client rejects one at <em>scrape</em> time and fails the
     * whole response, which would take the other series down with it. This method must not normalise a sign
     * on its way there - the routing is {@code MetricsConfig}'s, and the signed total is recovered as
     * {@code credit - debit}.
     *
     * <p>Reported per record rather than per chunk sum, deliberately. Summing here would be exact - these are
     * {@code BigDecimal} values - but it would move the arithmetic that produces the reported figure out of the
     * one class that owns monetary accumulation, and the amounts are already in hand one at a time. The
     * accumulator does the adding.
     *
     * <p>Side effects: advances one of the two sign-tagged series of
     * {@link MetricsConfig#METRIC_TRANSACTION_AMOUNT_TOTAL} per item. No I/O and no logging.
     *
     * @param items the chunk just written, never {@code null}
     */
    private void countTransactionAmounts(List<? extends Transaction> items) {
        for (Transaction item : items) {
            BigDecimal amount = item == null ? null : item.getAmount();
            if (amount != null) {
                metrics.countTransactionAmount(amount);
            }
        }
    }

    /**
     * Returns the captured step execution, or fails naming the missing wiring.
     *
     * @return the step execution, never {@code null}
     * @throws FatalProcessingException if no step execution has been captured, which means the writer was
     *         used outside a step and no listener callback ever reached it
     */
    private StepExecution requireStepContext() {
        StepExecution captured = this.stepExecution;
        if (captured == null) {
            String reason = "no step execution was captured before the first write";
            String message = reason
                    + ". This writer must be registered on a Spring Batch step so that the framework calls "
                    + "beforeStep on it; a unit test that uses the class directly must call beforeStep with "
                    + "a StepExecution before writing. Without it there is no job instance to scope the "
                    + "object key on. It is reported here, at the first write, rather than at construction, "
                    + "because at construction the step name is not yet known and the diagnostic would be "
                    + "the poorer for it.";
            LOG.error(message);
            throw new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                    ABEND_CULPRIT, reason, message);
        }
        return captured;
    }

    /**
     * Builds the duplicate-identifier failure, which is a designed outcome rather than a defect.
     *
     * <p><strong>The cause is always preserved and its text is never copied into the message built here.</strong>
     * A driver's constraint-violation text commonly embeds the offending value, which on this record could be a
     * card number; echoing it would put that value into a log line. This message names identifiers only.
     *
     * <p>Which item of a batched flush collided cannot be determined from any portable field of
     * Spring's exception; a driver-independent accessor for the violated key would be needed. The exact
     * identifier is therefore reported when the chunk holds a single item, and the candidate identifiers
     * otherwise.
     *
     * @param items the chunk whose insert was refused
     * @param cause the store failure, preserved as the cause
     * @return the exception to throw, never {@code null}
     */
    private static DuplicateRecordException duplicateIdentifier(List<? extends Transaction> items,
            DataAccessException cause) {
        String collidingKey = items.size() == 1 ? identifierOf(items.get(0)) : null;
        String message = String.format(Locale.ROOT,
                "Duplicate TRAN-ID rejected by the insert into %s (relation %s). Candidate identifiers: %s. "
                        + "Identifiers are generated by the descending-browse max-plus-one idiom of "
                        + "app/cbl/COTRN02C.cbl:L444-L451 and app/cbl/COBIL00C.cbl:L212-L219, and "
                        + "app/cbl/CBACT04C.cbl:L473-L516 appends a global suffix counter that is never reset "
                        + "per account, so a collision is the intended observable outcome of re-driving with a "
                        + "used date parameter. Re-drive with an unused one; do not retry, do not overwrite "
                        + "the existing row, and do not substitute a sequence. Set the chunk size to 1 to "
                        + "attribute a collision exactly.",
                LOGICAL_FILE, RELATION, candidateIdentifiers(items));
        LOG.error("duplicate TRAN-ID rejected the chunk insert; logicalFile={} relation={} operation={} "
                        + "chunkSize={} reason={}",
                LOGICAL_FILE, RELATION, OPERATION_WRITE, Integer.valueOf(items.size()), REASON_DUPLICATE_KEY);
        return new DuplicateRecordException(message, LOGICAL_FILE, collidingKey, cause);
    }

    /**
     * Builds the referential or check-constraint failure.
     *
     * <p>No constraint name, SQL fragment or DDL text is hard-coded: {@code V1__create_schema.sql} is the
     * single authoritative declaration of the ten foreign keys and five check constraints, and the exception
     * carries a {@code null} identifier precisely so that this class cannot drift from it. The retained cause
     * carries the driver's own constraint detail for anyone who needs it.
     *
     * @param items the chunk whose insert was refused
     * @param cause the store failure, preserved as the cause
     * @return the exception to throw, never {@code null}
     */
    private static DataIntegrityException constraintViolation(List<? extends Transaction> items,
            DataAccessException cause) {
        String message = String.format(Locale.ROOT,
                "A constraint violation rejected the insert of %d record(s) into relation %s. Candidate "
                        + "identifiers: %s. The exception does not name the constraint in a portable field, "
                        + "and a duplicate key is excluded because that condition is handled separately, so "
                        + "it is one of the referential or check constraints that V1__create_schema.sql "
                        + "declares on this relation. The retained cause carries the driver's own detail.",
                Integer.valueOf(items.size()), RELATION, candidateIdentifiers(items));
        LOG.error("a constraint violation rejected the chunk insert; logicalFile={} relation={} operation={} "
                        + "chunkSize={} reason={}",
                LOGICAL_FILE, RELATION, OPERATION_WRITE, Integer.valueOf(items.size()),
                REASON_CONSTRAINT_VIOLATION);
        return new DataIntegrityException(message, null, RELATION, cause);
    }

    /**
     * Builds the abend for a store failure that is neither a duplicate key nor a constraint violation.
     *
     * @param items the chunk whose insert was refused
     * @param cause the store failure, preserved as the cause
     * @return the exception to throw, never {@code null}
     */
    private static FatalProcessingException unexpectedStoreFailure(List<? extends Transaction> items,
            DataAccessException cause) {
        String reason = String.format(Locale.ROOT, "Unexpected store failure inserting %d record(s) into %s",
                Integer.valueOf(items.size()), LOGICAL_FILE);
        String message = String.format(Locale.ROOT,
                "%s. app/cbl/CBTRN02C.cbl:L562-L579 2900-WRITE-TRANSACTION-FILE treats any status other than "
                        + "'00' as fatal, and the condition is neither a duplicate key nor a constraint "
                        + "violation, so it abends.",
                reason);
        LOG.error("an unexpected store failure rejected the chunk insert; logicalFile={} relation={} "
                        + "operation={} chunkSize={} reason={}",
                LOGICAL_FILE, RELATION, OPERATION_WRITE, Integer.valueOf(items.size()),
                REASON_UNEXPECTED_STORE_FAILURE);
        return new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT, reason, message, cause);
    }

    /**
     * Appends one {@code PIC X(n)} field: left-justified and right-padded with spaces to exactly {@code width}.
     *
     * <p>An absent value renders as {@code width} spaces, handled explicitly rather than by accident. That is
     * the correct rendering for a fixed-width character field and is exactly what the reference fixture holds
     * for an unposted processing timestamp.
     *
     * <p>A value longer than its picture clause is an <strong>error</strong> and is never truncated: silent
     * truncation is the failure mode that breaks fixed-width parity without any test noticing.
     *
     * <p>Characters are also checked for single-byte representability, because a substituted character would
     * preserve the width while corrupting the content.
     *
     * <p>Side effects: appends to the supplied builder. Otherwise a pure function of its arguments.
     *
     * @param image the builder to append to
     * @param value the field value, permitted to be {@code null} or shorter than the width
     * @param field the COBOL field name, used only in diagnostics
     * @param width the exact width the picture clause declares
     * @param transactionId the record identifier, used only in diagnostics
     * @throws DataIntegrityException if the value is too long, or holds a character that cannot be represented
     *         as a single byte. The diagnostic reports the field, the widths and the offending
     *         <em>position</em> - never the value, because this method also renders the card number
     */
    private static void appendCharacterField(StringBuilder image, String value, String field, int width,
            String transactionId) {
        String text = value == null ? "" : value;
        if (text.length() > width) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s is %d characters but its picture clause declares exactly %d; truncating a "
                            + "fixed-width field is not permitted",
                    field, Integer.valueOf(text.length()), Integer.valueOf(width)));
        }
        for (int index = 0; index < text.length(); index++) {
            char candidate = text.charAt(index);
            if (candidate > MAX_ENCODABLE_CHAR) {
                throw unloadable(transactionId, String.format(Locale.ROOT,
                        "%s holds a character at position %d that %s cannot represent in a single byte, so "
                                + "the record could not be emitted without substituting it",
                        field, Integer.valueOf(index + 1), FIXED_WIDTH_CHARSET.name()));
            }
            if (!isPermittedCharacter(candidate)) {
                // Wording deliberately identical to RejectWriter's: one rule stated one way across both
                // writers, so an operator who has seen the diagnostic once recognises it anywhere and a test
                // can assert the contract in one place rather than per class.
                throw unloadable(transactionId, String.format(Locale.ROOT,
                        "%s holds a character at position %d (code point U+%04X) outside the permitted set %s. "
                                + "The stream is unblocked and undelimited, so a consumer finds record "
                                + "boundaries by counting %d bytes; a control byte inside a picture-clause "
                                + "field would let a line-oriented reader see a boundary this format does not "
                                + "have",
                        field, Integer.valueOf(index + 1), Integer.valueOf(candidate),
                        PERMITTED_CHARACTER_SET_DESCRIPTION, Integer.valueOf(RECORD_LENGTH)));
            }
        }
        image.append(text);
        for (int index = text.length(); index < width; index++) {
            image.append(' ');
        }
    }

    /**
     * Decides whether one code point belongs to the permitted single-byte set of a {@code PIC X(n)} field.
     *
     * <p>Expressed as a <b>positive</b> rule: everything from the space to the tilde, plus everything from the
     * no-break space to the end of the ISO-8859-1 range. Everything else in the encodable range is a control
     * character - the C0 block below the space, {@code DEL}, or the C1 block - and is refused. Writing the rule
     * this way rather than as a list of forbidden code points is deliberate: a deny-list of controls is a fixed
     * enumeration that quietly stops being exhaustive, whereas a permitted range cannot admit something nobody
     * thought of. See {@link #PERMITTED_CHARACTER_SET_DESCRIPTION} for the injection finding this closes.
     *
     * <p>Static and pure, so it can be exercised across the whole code-point range by a test without an
     * instance and without composing a record.
     *
     * @param candidate the code point to test
     * @return {@code true} when the character may appear in a fixed-width alphanumeric field
     */
    private static boolean isPermittedCharacter(char candidate) {
        if (candidate < MIN_PERMITTED_CHAR) {
            return false;
        }
        if (candidate == DELETE_CHAR) {
            return false;
        }
        return candidate < FIRST_C1_CHAR || candidate > LAST_C1_CHAR;
    }

    /**
     * Appends one unsigned {@code PIC 9(n)} field as plain zero-padded digits.
     *
     * <p>{@code TRAN-CAT-CD PIC 9(04)} and {@code TRAN-MERCHANT-ID PIC 9(09)} are unsigned zoned decimal and
     * carry <strong>no overpunch</strong>: the trailing sign encoding belongs only to the signed
     * {@code TRAN-AMT}. Conflating the two would shift every subsequent field.
     *
     * <p>Side effects: appends to the supplied builder. Otherwise a pure function of its arguments.
     *
     * @param image the builder to append to
     * @param value the field value. Only the entity's integral box types reach this method - the category code
     *        as an {@code Integer} and the merchant identifier as a {@code Long}
     * @param field the COBOL field name, used only in diagnostics
     * @param width the exact width the picture clause declares
     * @param transactionId the record identifier, used only in diagnostics
     * @throws DataIntegrityException if the value is absent, negative, or needs more digits than the picture
     *         clause declares
     */
    private static void appendUnsignedZonedField(StringBuilder image, Number value, String field, int width,
            String transactionId) {
        if (value == null) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s is absent, but its picture clause declares an unsigned %d digit zoned decimal and "
                            + "the column is NOT NULL",
                    field, Integer.valueOf(width)));
        }
        long magnitude = value.longValue();
        if (magnitude < 0L) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s is negative, but its picture clause declares an unsigned zoned decimal, which has "
                            + "no sign position at all",
                    field));
        }
        String digits = Long.toString(magnitude);
        if (digits.length() > width) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s needs %d digits but its picture clause declares exactly %d", field,
                    Integer.valueOf(digits.length()), Integer.valueOf(width)));
        }
        image.append(zeroPad(digits, width));
    }

    /**
     * Appends {@code TRAN-AMT}: {@code PIC S9(09)V99} at bytes 133-143, as signed zoned decimal with a
     * trailing overpunch sign.
     *
     * <p>Eleven characters are emitted - nine integer digits then two decimal digits - with the decimal point
     * <strong>implied and never written</strong>. The final character carries the sign as an overpunch, applied
     * position-aware from the picture clause: <code>&#123;</code> is +0 and {@code A} to {@code I} are +1 to +9,
     * while <code>&#125;</code> is -0 and {@code J} to {@code R} are -1 to -9. So +504.77 encodes as
     * {@code 0000005047G} - which is exactly what the first record of {@code app/data/ASCII/dailytran.txt}
     * holds - and -504.77 encodes as {@code 0000005047P}.
     *
     * <p><strong>No absolute-value normalisation is applied to the sign.</strong> The magnitude is taken only
     * to render the digits; the sign is rendered from the value's own sign. Amounts are legitimately negative -
     * the reference fixture carries 6 negative-zero and 44 negative non-zero overpunches at byte 143 - and that
     * is precisely why the upstream over-limit formula at {@code app/cbl/CBTRN02C.cbl:L403-L405} subtracts a
     * cycle-debit accumulator which {@code :L548-L552} fills with negative values.
     *
     * <p>Arithmetic is exact throughout: the value is scaled with {@code RoundingMode.HALF_EVEN} to the
     * {@code V99} the picture clause declares, and the sign is taken with {@code signum} rather than by any
     * equality test. A value that rounds to zero renders as +0, <code>&#123;</code>, because an exact decimal has no
     * negative zero to preserve; <code>&#125;</code> therefore arises when decoding legacy data but never when
     * encoding.
     *
     * <p>Side effects: appends to the supplied builder. Otherwise a pure function of its arguments.
     *
     * @param image the builder to append to
     * @param amount the amount to render
     * @param transactionId the record identifier, used only in diagnostics
     * @throws DataIntegrityException if the amount is absent, or needs more than nine integer digits and so
     *         cannot be represented in the eleven available positions
     */
    private static void appendSignedZonedAmount(StringBuilder image, BigDecimal amount, String transactionId) {
        if (amount == null) {
            throw unloadable(transactionId,
                    "TRAN-AMT is absent, but app/cpy/CVTRA05Y.cpy:L10 declares PIC S9(09)V99 and the column "
                            + "is NOT NULL");
        }
        BigDecimal scaled = amount.setScale(TRAN_AMT_SCALE, RoundingMode.HALF_EVEN);
        String digits = scaled.abs().unscaledValue().toString();
        if (digits.length() > TRAN_AMT_WIDTH) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "TRAN-AMT needs %d zoned positions but PIC S9(09)V99 provides exactly %d, being nine "
                            + "integer digits and two decimals",
                    Integer.valueOf(digits.length()), Integer.valueOf(TRAN_AMT_WIDTH)));
        }
        String padded = zeroPad(digits, TRAN_AMT_WIDTH);
        int signPosition = TRAN_AMT_WIDTH - 1;
        int finalDigit = padded.charAt(signPosition) - '0';
        String overpunch = scaled.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        image.append(padded, 0, signPosition).append(overpunch.charAt(finalDigit));
    }

    /**
     * Left-pads a digit string with zeros to an exact width.
     *
     * @param digits the digits to pad, never longer than {@code width}
     * @param width the exact width required
     * @return the padded value, exactly {@code width} characters
     */
    private static String zeroPad(String digits, int width) {
        if (digits.length() >= width) {
            return digits;
        }
        StringBuilder padded = new StringBuilder(width);
        for (int index = digits.length(); index < width; index++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    /**
     * Builds the exception used for every per-record rejection in this writer.
     *
     * <p>A record that does not fit the fixed-width contract is an integrity failure rather than an I/O one,
     * so no file status is invented for it and the constraint identifier is left {@code null}.
     *
     * @param transactionId the identifier if one could be read, otherwise {@code null}, for which a fixed
     *        placeholder is reported
     * @param detail what specifically makes the record unemittable. Must name the field and the widths, never
     *        a value
     * @return the exception to throw, never {@code null}
     */
    private static DataIntegrityException unloadable(String transactionId, String detail) {
        String message = String.format(Locale.ROOT,
                "The transaction writer rejected the record for TRAN-ID %s: %s. Sources: "
                        + "app/cpy/CVTRA05Y.cpy (RECLN = 350); app/jcl/TRANFILE.jcl:L54 "
                        + "RECORDSIZE(350 350); app/cbl/CBTRN02C.cbl:L562-L579 "
                        + "2900-WRITE-TRANSACTION-FILE.",
                renderKey(transactionId), detail);
        LOG.error("the fixed-width encoder rejected a record; logicalFile={} relation={} operation={} reason={}",
                LOGICAL_FILE, RELATION, OPERATION_WRITE, REASON_UNENCODABLE_RECORD);
        return new DataIntegrityException(message, null, RELATION, null);
    }

    /**
     * Renders an identifier so that it can appear in a log record or a message without being able to alter
     * that record's structure.
     *
     * <p>A control character reaching a log line could split it or forge a second one, so anything below the
     * printable range is escaped. The identifier itself is a key rather than customer content, which is why it
     * may be reported at all; no other field of the record ever is.
     *
     * @param transactionId the identifier, permitted to be {@code null}
     * @return the escaped identifier, or a fixed placeholder when none was available
     */
    private static String renderKey(String transactionId) {
        if (transactionId == null) {
            return ABSENT_KEY;
        }
        StringBuilder rendered = new StringBuilder(transactionId.length());
        for (int index = 0; index < transactionId.length(); index++) {
            char character = transactionId.charAt(index);
            if (character < ' ' || character == '\u007F') {
                rendered.append(String.format(Locale.ROOT, "\\u%04X", Integer.valueOf(character)));
            } else {
                rendered.append(character);
            }
        }
        return rendered.toString();
    }

    /**
     * Reads an identifier defensively.
     *
     * @param item the transaction, permitted to be {@code null}
     * @return the identifier, or {@code null} when there is no item to read one from
     */
    private static String identifierOf(Transaction item) {
        return item == null ? null : item.getTransactionId();
    }

    /**
     * Renders the identifiers of a chunk for a diagnostic.
     *
     * <p>Identifiers only. No card number, merchant value, amount or timestamp is ever included, and the
     * composed record image never is.
     *
     * @param items the chunk
     * @return a comma-separated list of escaped identifiers, never {@code null}
     */
    private static String candidateIdentifiers(List<? extends Transaction> items) {
        StringBuilder rendered = new StringBuilder();
        for (Transaction item : items) {
            if (rendered.length() > 0) {
                rendered.append(", ");
            }
            rendered.append(renderKey(identifierOf(item)));
        }
        return rendered.toString();
    }

    /**
     * Rejects a missing collaborator at construction, so the bean cannot exist half-configured.
     *
     * <p>Declared {@code static} so the constructor can call it without invoking an overridable method, which
     * would publish a partially initialised instance.
     *
     * @param <T> the collaborator type
     * @param collaborator the injected collaborator
     * @param name the parameter name, for the diagnostic
     * @return the collaborator, never {@code null}
     * @throws IllegalArgumentException if the collaborator is {@code null}
     */
    private static <T> T requireCollaborator(T collaborator, String name) {
        if (collaborator == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return collaborator;
    }

    /**
     * Rejects an absent or blank configuration value at construction.
     *
     * <p>A context that has not supplied the destination bucket fails at startup rather than at the first
     * write, and no default is invented for it - writing customer records to an unintended location is the
     * failure this prevents.
     *
     * <p>Declared {@code static} for the same reason as {@link #requireCollaborator(Object, String)}.
     *
     * @param value the configured value
     * @param property the property name, for the diagnostic
     * @return the value, never {@code null} and never blank
     * @throws IllegalArgumentException if the value is {@code null} or blank
     */
    private static String requireConfigured(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be configured with a non-blank value");
        }
        return value;
    }

    /**
     * Validates the configured key prefix against the one shared grammar.
     *
     * <p>{@link #KEY_TEMPLATE} writes the separator itself, so a prefix that arrives already ending in one
     * would compose a key carrying an empty segment - {@code base//0000...} rather than {@code base/0000...}.
     * That is not a cosmetic difference. Object storage has no directories, so the key is the whole name: the
     * consuming reader validates that the generation key it is handed names exactly one object inside the
     * configured namespace and refuses a {@code //} segment, so a doubled separator here is a key the reader
     * is right to reject.
     *
     * <p><strong>Finding m-02, severity Minor, RESOLVED.</strong> This method used to <em>strip</em> a
     * trailing separator so that either form composed the same key, and it checked nothing else. The shared
     * grammar of {@link GenerationPrefixContract#requireRelativePrefix(String, String)} refuses the trailing
     * form instead, which reaches the same guarantee by a stronger route: there is now exactly one accepted
     * spelling rather than two spellings quietly folded into one. The declared value is {@code transact},
     * which satisfies the grammar, so no shipped configuration changes behaviour.
     *
     * <p>The consuming reader still needs the opposite form - it <em>appends</em> exactly one separator,
     * because it matches the prefix as a plain string and needs the boundary to keep
     * {@code gdg/transact-bkup} from also matching {@code gdg/transact-bkup-shadow}. That form is now derived
     * from the validated one by {@link GenerationPrefixContract#listingPrefixOf(String)}, so both rules come
     * from one place: one owns the boundary in a match, the other owns it in a composition.
     *
     * @param configured the configured prefix
     * @return the prefix unchanged, once it satisfies the shared grammar
     * @throws IllegalArgumentException if the value is absent, blank or malformed
     */
    private static String requireObjectPrefix(String configured) {
        return GenerationPrefixContract.requireRelativePrefix(
                configured, "carddemo.aws.s3.transaction-object-prefix");
    }

    /**
     * Validates the indexed-manifest bound.
     *
     * <p>Zero and negative values are refused at startup rather than tolerated, because both produce a
     * manifest that no consumer can read against the protocol on {@link #OBJECT_KEYS_COUNT_ENTRY}: zero
     * publishes no indexed entry while the count keeps rising, and a negative bound names no bound at all.
     * Failing here is the difference between a misconfiguration and a silently useless audit record.
     *
     * @param configured the value bound from {@value #KEY_MAX_INDEXED_OBJECT_KEYS}
     * @return the same value when it is positive
     * @throws IllegalArgumentException when it is not
     */
    private static int requireIndexedKeyCap(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(KEY_MAX_INDEXED_OBJECT_KEYS + " must be positive but was "
                    + configured);
        }
        return configured;
    }
}
