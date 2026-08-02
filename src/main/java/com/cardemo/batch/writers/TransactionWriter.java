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
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 * is not invoked directly; a Spring Batch step declared by {@code com.cardemo.config.BatchConfig} supplies
 * the chunks. Its unit tests live under {@code src/test/java/com/cardemo/unit} and construct the bean
 * directly, needing no Spring context: {@link #composeFixedWidthImage(Transaction)} is a pure function and is
 * exposed for exactly that purpose.
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
 * <li>{@code carddemo.s3.output-bucket} - the destination bucket. <strong>No default</strong>: the value is
 * required, and a context that does not supply it fails to start rather than silently writing somewhere
 * unintended. Supplied through {@code CARDDEMO_S3_OUTPUT_BUCKET} in the {@code application*.yml} profiles.</li>
 * <li>{@code carddemo.s3.transaction-object-prefix} - the base-name segment every key starts with. Defaults
 * to {@code transact}, the logical file name of {@code app/jcl/TRANFILE.jcl}.</li>
 * </ul>
 *
 * <p>No endpoint, region or credential is read here, and no process environment variable is consulted
 * directly - every value arrives through the injected configuration. The LocalStack endpoint override exists
 * only in the {@code local} and {@code test} profiles, so no live cloud path is structurally reachable from
 * this class.
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
 * than re-resolving "latest", the concrete created key is published into the step execution context under
 * {@link #OBJECT_KEY_CONTEXT_ENTRY}. Promotion to the job execution context, where a later step needs it, is
 * a step-listener concern owned by {@code BatchConfig}; this class only publishes.
 *
 * <h2>Side effects</h2>
 *
 * <p>One insert per item and one object per non-empty chunk, plus one increment of the records-processed
 * counter per item and one log record per failure. Nothing else is mutated. There is no static mutable
 * state; the only mutable field is the per-step execution context captured by {@link #beforeStep(StepExecution)}.
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
 * <li><strong>No step context</strong> - {@code FatalProcessingException}. The writer was invoked outside a
 * step, so no job-instance identifier exists to key the object on. Register it on a step, or call
 * {@link #beforeStep(StepExecution)} first in a test.</li>
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
 * <p>That is a deliberate <strong>deviation</strong> of severity <strong>Medium</strong>, recorded in
 * {@code DECISION_LOG.md} per AAP section 0.7.2.7, and not a parity claim. The source performs
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
 * <h2>Findings recorded under Rule 1 clause F</h2>
 *
 * <ul>
 * <li><strong>Medium - fixture geometry.</strong> The requirements describe
 * {@code app/data/ASCII/dailytran.txt} as "105,300 bytes = 300 x 350", but 300 x 350 is 105,000. The file
 * measures 105,300 bytes because it is 300 x 351: 350 data bytes plus one line feed per record, and it
 * contains exactly 300 line feeds. 105,300 is consequently <em>not</em> a multiple of 350. Reading it at a
 * 350-byte stride desynchronises after the first record; read at the correct 351-byte stride the sign census
 * at byte 143 is 25 <code>&#123;</code>, 6 <code>&#125;</code> and 44 in {@code J}-{@code R}, exactly as specified, and
 * the first record's amount field is literally {@code 0000005047G}, that is +504.77. <em>Remediation</em>:
 * cite the 351-byte stride when reading that fixture. The checked-in text file is line-feed delimited; the
 * mainframe dataset it mirrors is {@code RECORDSIZE(350 350)} fixed, so the emission below stays
 * separator-free.</li>
 * <li><strong>Medium - timestamp precision.</strong> AAP section 0.7.2.8 and two sibling specifications
 * describe the generated timestamp as "millisecond precision followed by four zeros". That is three plus
 * four characters of fraction and is arithmetically impossible in a {@code PIC X(26)} field. The verified
 * layout at {@code app/cbl/CBTRN02C.cbl:L162-L174} is
 * {@code 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26} exactly, where {@code DB2-MIL PIC 9(002)} carries
 * <em>hundredths</em> and {@code DB2-REST PIC X(04)} is set by {@code MOVE '0000' TO DB2-REST} at
 * {@code app/cbl/CBTRN02C.cbl:L701}. The pattern {@code yyyy-MM-dd-HH.mm.ss.SS0000} governs.
 * <em>Remediation</em>: never format to millisecond or nanosecond precision. This class generates no
 * timestamp and so cannot introduce the defect; it passes the 26 characters through verbatim.</li>
 * <li><strong>Low - paragraph census.</strong> The specification for this file states 27 paragraphs for
 * {@code CBTRN02C}. Direct inspection finds <strong>26</strong> procedure-division paragraphs - 25 numbered
 * labels plus {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBTRN02C.cbl:L692}. The 27th candidate is
 * {@code FILE-CONTROL.} at {@code app/cbl/CBTRN02C.cbl:L28}, an environment-division header rather than a
 * paragraph. <em>Remediation</em>: the banner above states the verified figure.</li>
 * <li><strong>Low - metrics.</strong> Only the records-processed counter is incremented. Adding to a
 * total-transaction-amount counter is not reachable, because the amount-taking counter overload accepts only
 * an approximate binary primitive and converting a monetary {@code BigDecimal} into one is forbidden outright
 * for financial fields and checked by the security gate. <em>Remediation</em>: expose transaction value
 * through the relation, which holds it exactly as {@code NUMERIC(11,2)}, rather than through an
 * approximate-arithmetic meter.</li>
 * <li><strong>Low - duplicate attribution in a multi-item chunk.</strong> Which item of a batched flush
 * collided is <em>Not available</em> from a portable field of Spring's exception. What would be needed is a
 * driver-independent accessor for the violated key. Until one exists, the exception names the exact
 * identifier when the chunk holds a single item, reports the candidate identifiers otherwise, and always
 * preserves the driver's own exception as the cause.</li>
 * </ul>
 *
 * <h2>Information not available</h2>
 *
 * <ul>
 * <li>Which generation-data-group base the posted-transaction object belongs to, among {@code SYSTRAN},
 * {@code TRANSACT.BKUP}, {@code TRANSACT.DALY} and {@code TRANSACT.COMBINED}, is <strong>Not
 * available</strong>. What would be needed is an explicit dataset-to-prefix table, which does not exist at
 * {@code 7756d89}. The output bucket with a base-name plus job-instance prefix is used, and the choice is
 * recorded in {@code DECISION_LOG.md}.</li>
 * <li>An object-storage record-framing convention is <strong>Not available</strong> from the corpus, which
 * predates object storage entirely. What would be needed is a stated framing contract. The fixed-width
 * dataset geometry is followed instead - no separator, so record <em>n</em> begins at offset
 * <em>n</em> x 350 and the object size is an exact multiple of 350. A newline-delimited variant would be a
 * labelled deviation requiring a {@code DECISION_LOG.md} entry, never a silent change.</li>
 * <li>Any throughput or latency objective is <strong>Not available</strong>: the corpus publishes no service
 * level whatsoever. What would be needed is a stated objective from the business. None may be invented, so
 * the performance gate records a measured baseline rather than asserting a threshold.</li>
 * <li>The literal counter names registered by {@code com.cardemo.observability.MetricsConfig} are
 * <strong>Not available</strong>, that file not being a declared dependency of this one. What would be needed
 * is its published name registry. {@code MeterRegistry.counter} resolves an existing meter by name and tags
 * rather than adding one, so naming the counter as below cannot introduce a fifth instrument.</li>
 * <li>A census divergence between sibling specifications over the literal {@code FILE STATUS} occurrences -
 * {@code '00'} 88 times, {@code '10'} 11 and {@code '23'} 3, against 82, 7 and 1 - is unresolved and is
 * disclosed rather than silently decided. It does not affect this class, which routes every status decision
 * to {@code com.cardemo.service.shared.FileStatusMapper}. The {@code DFHRESP} census is consistent
 * everywhere, and literal {@code FILE STATUS '22'} is tested nowhere in the corpus, being grounded only
 * through {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)} - severity <strong>Medium</strong>.</li>
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
 * <h2>Thread safety</h2>
 *
 * <p>Safe for the single-threaded chunk-oriented step this pipeline declares. The captured step execution is
 * {@code volatile}, so a step that hands reading and writing to different threads still publishes it safely.
 * A multi-threaded or partitioned step must declare this writer step-scoped, so that each execution captures
 * its own context; that is a wiring decision owned by {@code BatchConfig}.
 *
 * @see com.cardemo.service.shared.FileStatusMapper
 */
@Component
public class TransactionWriter implements ItemWriter<Transaction> {

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
     * Step execution context entry under which the concrete created object key is published.
     *
     * <p>Public so that {@code BatchConfig} can name it when configuring promotion to the job execution
     * context, which is what lets a later step re-read the object an earlier step wrote as {@code (+1)}
     * instead of re-resolving "latest".
     */
    public static final String OBJECT_KEY_CONTEXT_ENTRY = "carddemo.transaction.object.key";

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

    /**
     * The records-processed counter, one of the four instruments that replace the end-of-run
     * {@code DISPLAY 'TRANSACTIONS PROCESSED :'} of {@code app/cbl/CBTRN02C.cbl:L227}.
     *
     * <p>Resolved by name and without tags, so no high-cardinality dimension is introduced and no fifth
     * instrument is created: {@code MeterRegistry.counter} returns the already-registered meter of that same
     * name rather than adding another one.
     */
    private static final String RECORDS_PROCESSED_COUNTER = "carddemo.batch.records.processed";

    /** Placeholder used in a diagnostic when no identifier could be read, so no message is ever ragged. */
    private static final String ABSENT_KEY = "(absent)";

    private final TransactionRepository transactionRepository;

    private final S3Operations objectStorage;

    private final FileStatusMapper fileStatusMapper;

    private final Counter recordsProcessedCounter;

    private final String outputBucket;

    private final String objectPrefix;

    /**
     * The step execution of the step currently running this writer, captured by
     * {@link #beforeStep(StepExecution)}.
     *
     * <p>This is the only mutable field on the class and there is no static mutable state at all. It is
     * {@code volatile} so that a step which reads and writes on different threads still publishes it safely.
     * It supplies three things and nothing else: the job-instance identifier that scopes the object key, the
     * write count that orders the objects within the step, and the context the created key is published into.
     */
    private volatile StepExecution stepExecution;

    /**
     * Creates the writer with its collaborators and its two configuration values.
     *
     * <p>Constructor injection only, so every collaborator is final and the bean cannot exist half-configured.
     * Nothing is read from the environment and no client is constructed here.
     *
     * <p>Side effects: resolves the records-processed counter from the registry, which registers it if no
     * meter of that name exists yet and returns the existing one otherwise. Nothing else.
     *
     * <p>Error modes: rejects a blank bucket or prefix, so a context that has not supplied
     * {@code carddemo.s3.output-bucket} fails at startup rather than at the first write.
     *
     * @param transactionRepository the repository whose inherited {@code saveAllAndFlush} performs the
     *        insert. Never {@code null}
     * @param objectStorage the object-storage abstraction. Declared as {@code S3Operations} rather than as
     *        the concrete {@code io.awspring.cloud.s3.S3Template} because Spring Cloud AWS declares that bean
     *        under {@code @ConditionalOnMissingBean(S3Operations.class)}: taking the interface is satisfied by
     *        the auto-configured {@code S3Template} and stays satisfied if {@code AwsConfig} supplies its own
     *        of either type, whereas taking the class would not. Never {@code null}
     * @param fileStatusMapper the sole owner of the status-to-exception decision. Never {@code null}
     * @param meterRegistry the meter registry the records-processed counter is resolved from. Never
     *        {@code null}
     * @param outputBucket the destination bucket, required and never defaulted
     * @param objectPrefix the base-name segment every key starts with, defaulting to {@code transact}
     * @throws IllegalArgumentException if any collaborator is {@code null} or either configuration value is
     *         blank
     */
    public TransactionWriter(TransactionRepository transactionRepository,
            S3Operations objectStorage,
            FileStatusMapper fileStatusMapper,
            MeterRegistry meterRegistry,
            @Value("${carddemo.s3.output-bucket}") String outputBucket,
            @Value("${carddemo.s3.transaction-object-prefix:transact}") String objectPrefix) {
        this.transactionRepository = requireCollaborator(transactionRepository, "transactionRepository");
        this.objectStorage = requireCollaborator(objectStorage, "objectStorage");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.outputBucket = requireConfigured(outputBucket, "carddemo.s3.output-bucket");
        this.objectPrefix = requireConfigured(objectPrefix, "carddemo.s3.transaction-object-prefix");
        this.recordsProcessedCounter =
                requireCollaborator(meterRegistry, "meterRegistry").counter(RECORDS_PROCESSED_COUNTER);
    }

    /**
     * Captures the step execution this writer is running under.
     *
     * <p>The writer needs three things from it and nothing more: the job-instance identifier that scopes
     * every object key, the write count that orders the objects within the step, and the execution context the
     * created key is published into. None of the three is reachable through the {@code ItemWriter} contract,
     * which takes only a chunk, so this listener callback is the supported way to obtain them.
     *
     * <p>Side effects: replaces the captured context. Idempotent per step.
     *
     * <p>Error modes: none. A {@code null} argument is stored as {@code null} and reported later, by
     * {@link #write(Chunk)}, with the wiring remediation attached - which is a better diagnostic than failing
     * here, before the step name is known.
     *
     * @param execution the step execution supplied by the framework, or {@code null} if a caller supplies
     *        none
     */
    @BeforeStep
    public void beforeStep(StepExecution execution) {
        this.stepExecution = execution;
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
     * <li><strong>Emit the object</strong>, under the guard reproduced from
     * {@code app/cbl/CBTRN02C.cbl:L562-L579}.</li>
     * <li><strong>Publish the created key</strong> into the step execution context.</li>
     * <li><strong>Count the records.</strong></li>
     * </ol>
     *
     * <p>Side effects: one row per item, one object per call, one counter increment per item, and one context
     * entry. No timestamp is generated: the two 26-character timestamp fields are passed through exactly as
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

        persistChunk(items);
        String objectKey = writeTransactionFile(payload, execution, ordinal);
        publishObjectKey(execution, objectKey);

        countRecords(items.size());
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
                            + "reduce the configured chunk size in BatchConfig",
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
     * @param items the transactions to insert, never empty
     * @throws DuplicateRecordException if an identifier already exists
     * @throws DataIntegrityException if a referential or check constraint refuses a row
     * @throws FatalProcessingException for any other store failure
     */
    private void persistChunk(List<? extends Transaction> items) {
        try {
            transactionRepository.saveAllAndFlush(items);
        } catch (DuplicateKeyException cause) {
            // Tested before DataIntegrityViolationException, which it extends: reversing the two would
            // classify every collision as a plain constraint violation and lose the identifier race.
            throw duplicateIdentifier(items, cause);
        } catch (DataIntegrityViolationException cause) {
            throw constraintViolation(items, cause);
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
     * reproduced control flow rather than untracked residue and is recorded in {@code DECISION_LOG.md}: its
     * purpose in the source is that a write which neither succeeds nor raises cannot be mistaken for success.
     *
     * <p>The constants are referenced from {@code FileStatusMapper}, which already declares
     * {@code APPL_RESULT_INITIAL}, {@code APPL_AOK}, {@code APPL_EOF} and {@code APPL_FAILURE} from
     * {@code app/cbl/CBTRN02C.cbl:L142-L144}; restating them here would duplicate a published contract.
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
     * future relaxation of that contract could never turn an abend into a silent success. It is documented and
     * tracked in {@code DECISION_LOG.md}, which is what Rule 1 clause B requires of a deliberately retained
     * branch. Returning the exception rather than throwing it internally keeps the control flow explicit at
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

        String reason = String.format(Locale.ROOT, "%s (bucket %s, object %s, operation %s)",
                WRITE_FAILURE_TEXT, outputBucket, objectKey, OPERATION_WRITE);
        LOG.error(reason);
        fileStatusMapper.requireSuccess(ioStatus, LOGICAL_FILE, OPERATION_WRITE, cause);

        String message = String.format(Locale.ROOT,
                "%s. app/cbl/CBTRN02C.cbl:L562-L579 2900-WRITE-TRANSACTION-FILE could not complete and the "
                        + "status mapper returned instead of raising, so the failure is escalated here.",
                reason);
        LOG.error(message);
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
     * {@code TRANSACT.BKUP}, {@code TRANSACT.DALY} and {@code TRANSACT.COMBINED}, is <strong>Not
     * available</strong> from the corpus; an explicit dataset-to-prefix table would be needed and none exists
     * at {@code 7756d89}. The configured prefix is used instead and the choice is recorded in
     * {@code DECISION_LOG.md}.
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
     * Publishes the concrete created key into the step execution context.
     *
     * <p>Within one job a later step must re-read what an earlier step wrote as {@code (+1)}, so the exact key
     * is recorded rather than left to be re-resolved as "latest" - a resolution that would race with any
     * concurrent producer. Promotion to the job execution context, where a later step needs it, is configured
     * by {@code BatchConfig} against {@link #OBJECT_KEY_CONTEXT_ENTRY}.
     *
     * <p>Side effects: writes one context entry, overwriting any previous value so that the entry always names
     * the most recently created object.
     *
     * @param execution the captured step execution
     * @param objectKey the key just created
     */
    private void publishObjectKey(StepExecution execution, String objectKey) {
        execution.getExecutionContext().putString(OBJECT_KEY_CONTEXT_ENTRY, objectKey);
    }

    /**
     * Advances the records-processed counter once per record written.
     *
     * <p>This is one of the four instruments that replace the end-of-run
     * {@code DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT} of
     * {@code app/cbl/CBTRN02C.cbl:L227}. No new instrument is introduced and no tag is attached, so no
     * high-cardinality dimension - never a card number, an account identifier or a transaction identifier -
     * can enter the metric namespace.
     *
     * <p>The counter is advanced one step at a time rather than by the batch size in a single call. The
     * amount-taking overload of the counter accepts only an approximate binary numeric primitive, and this
     * class holds monetary and count values as exact types; converting to an approximate type purely to move a
     * meter is not a trade worth making, and the security gate forbids approximate arithmetic on financial
     * fields outright. The loop is therefore the exact-arithmetic way to advance by N, and its cost is one
     * atomic add per record.
     *
     * @param written the number of records written, never negative
     */
    private void countRecords(int written) {
        for (int index = 0; index < written; index++) {
            recordsProcessedCounter.increment();
        }
    }

    /**
     * Returns the captured step execution, or fails with the wiring remediation attached.
     *
     * @return the step execution, never {@code null}
     * @throws FatalProcessingException if no step execution was captured
     */
    private StepExecution requireStepContext() {
        StepExecution captured = this.stepExecution;
        if (captured == null) {
            String reason = "no step execution was captured before the first write";
            String message = reason
                    + ". This writer must be registered on a Spring Batch step so that the framework "
                    + "invokes its listener callback; a test must call beforeStep first. Without it there "
                    + "is no job instance to scope the object key on.";
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
     * <p>Which item of a batched flush collided is <strong>Not available</strong> from any portable field of
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
        LOG.error(message);
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
        LOG.error(message);
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
        LOG.error(message);
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
            if (text.charAt(index) > MAX_ENCODABLE_CHAR) {
                throw unloadable(transactionId, String.format(Locale.ROOT,
                        "%s holds a character at position %d that %s cannot represent in a single byte, so "
                                + "the record could not be emitted without substituting it",
                        field, Integer.valueOf(index + 1), FIXED_WIDTH_CHARSET.name()));
            }
        }
        image.append(text);
        for (int index = text.length(); index < width; index++) {
            image.append(' ');
        }
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
        LOG.error(message);
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
}
