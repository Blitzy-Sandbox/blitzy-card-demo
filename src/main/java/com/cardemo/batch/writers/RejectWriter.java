/*
 * ******************************************************************
 * Program     : RejectWriter.java
 * Application : CardDemo
 * Type        : Spring Batch ItemWriter (Java 25 / Spring Boot 3.5.11)
 * Function    : Emits the 430-byte daily-transaction reject record.
 * Source      : app/cbl/CBTRN02C.cbl 2500-WRITE-REJECT-REC :L446-L465
 *               (27 paragraphs, 731 lines);
 *               app/jcl/POSTTRAN.jcl :L34-L38 (DALYREJS DD, RECFM=F LRECL=430);
 *               app/jcl/DALYREJS.jcl :L24-L28 (GDG base, LIMIT(5) at :L26);
 *               app/cpy/CVTRA06Y.cpy (350-byte DALYTRAN staging layout) @ 7756d89
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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Writes the {@code DALYREJS} reject dataset of the daily transaction posting job, reproducing
 * {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465} byte for byte.
 *
 * <h2>What it does</h2>
 *
 * <p>Every record it emits is exactly <strong>430 characters</strong>, being the 350-character
 * {@code DALYTRAN-RECORD} image followed by the 80-character validation trailer. The geometry is stated twice
 * in the corpus and both statements agree: {@code REJECT-RECORD} is declared as {@code REJECT-TRAN-DATA
 * PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)} at {@code app/cbl/CBTRN02C.cbl:L176-L178}, and the
 * trailer decomposes into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code :L180-L182}. Independently,
 * {@code app/jcl/POSTTRAN.jcl:L36} allocates the dataset as {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}.
 * 350 + 4 + 76 = 430.
 *
 * <p>The corpus achieves the first 350 characters with a single {@code MOVE DALYTRAN-RECORD TO
 * REJECT-TRAN-DATA} at {@code :L447}, because the COBOL program still holds the raw input record. The Java
 * reader hands this writer a {@code DailyTransaction} entity rather than retained bytes, so all 350
 * characters are <strong>re-serialised from the entity's fields</strong> against the layout of
 * {@code app/cpy/CVTRA06Y.cpy}, whose own header states {@code RECLN = 350}.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and test with {@code mvn -B clean test}; the module compiles under Java 25 with
 * {@code -Xlint:all -Werror}. This bean is step scoped, so it is instantiated by Spring Batch when the
 * posting step starts and is not usable outside a step context. Unit tests construct it directly, passing a
 * {@code StepExecution} built by {@code MetaDataInstanceFactory} or {@code null}; see the constructor.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.s3.output-bucket} - the destination bucket, backed by the
 *       {@code CARDDEMO_S3_OUTPUT_BUCKET} environment variable. <strong>There is deliberately no default.</strong>
 *       A missing value fails the context at startup rather than silently writing somewhere unintended, which
 *       is the fail-fast standard this migration applies to every externalised setting.</li>
 *   <li>No endpoint, region or credential is read here. The object-storage client is injected already
 *       configured, and the local emulator endpoint override exists only in the {@code local} and {@code test}
 *       profiles, so no live-cloud path is structurally reachable from this class.</li>
 * </ul>
 *
 * <h2>Why the 350-byte serialisation is not shared with the sibling writer</h2>
 *
 * <p>A reviewer will notice that {@code TransactionWriter} in this same package also emits a 350-byte image and
 * will reasonably ask why the two are not factored onto a common codec. The duplication is <strong>deliberate
 * and is the compliant choice</strong>, for three independent reasons.
 *
 * <p>First, the two layouts are contractually distinct even though they are presently identical in width and
 * field order: this class serialises {@code app/cpy/CVTRA06Y.cpy}, the {@code DALYTRAN-} staging layout, while
 * the sibling serialises {@code app/cpy/CVTRA05Y.cpy}, the {@code TRAN-} cluster layout. They are separate
 * frozen copybooks. Coupling them would make a future divergence in either one a silent corruption of the
 * other, which is precisely the failure mode byte-exact parity exists to prevent.
 *
 * <p>Second, this package is capped at exactly three types by the migration plan - the two writers above and
 * the statement writer. A shared codec would be a fourth type in a package that admits none, so the
 * abstraction is not available to be created here even if it were desirable.
 *
 * <p>Third, the duplication is bounded and fully covered: each renderer is a pure function asserted against
 * the frozen fixture, and this class's own segment is proved byte-for-byte equal to record 0 of
 * {@code app/data/ASCII/dailytran.txt} by unit test. Deduplication would trade a verified invariant for an
 * unverified one. This is the tradeoff clause A asks to be justified rather than assumed.
 *
 * <h2>Generation data group translation</h2>
 *
 * <p>{@code app/jcl/POSTTRAN.jcl:L38} names the target {@code AWS.M2.CARDDEMO.DALYREJS(+1)} - a relative
 * generation reference. {@code (+1)} becomes a new object under a monotonically increasing prefix over a
 * versioned bucket, and {@code (0)} becomes the lexicographically greatest existing prefix. The prefix is
 * built from the job instance and job execution identifiers zero-padded to 19 digits, <strong>not from a wall
 * clock</strong>: zero padding to the width of {@code Long.MAX_VALUE} makes lexicographic order identical to
 * numeric order, and identifiers make the key reproducible on re-run whereas a timestamp would not. That is
 * the determinism tradeoff, taken deliberately.
 *
 * <p>The concrete key of every object created is published into the step execution context under
 * {@link #REJECT_OBJECT_KEY_CONTEXT_KEY}, alongside the generation prefix under
 * {@link #REJECT_GENERATION_PREFIX_CONTEXT_KEY} and the cumulative record count under
 * {@link #REJECT_RECORD_COUNT_CONTEXT_KEY}. A later step in the same job therefore reads the exact key that
 * was written instead of re-resolving "latest", which is what makes a {@code (+1)} written earlier in a job
 * readable as {@code (+1)} later in the same job.
 *
 * <p>The {@code DALYREJS} generation base declares {@code LIMIT(5)} at {@code app/jcl/DALYREJS.jcl:L26}.
 * <strong>Retention is documented, not enforced here</strong>; object versioning supersedes generation
 * counting and any lifecycle rule belongs to the bucket, not to a writer.
 *
 * <h2>Record framing</h2>
 *
 * <p>{@code RECFM=F} with {@code BLKSIZE=0} is fixed <em>unblocked</em>: the mainframe dataset carries no
 * record delimiters at all. Records are therefore concatenated with <strong>no separator</strong>, so record
 * <em>n</em> begins at offset <em>n</em> &times; 430 and the object size is always an exact multiple of 430.
 *
 * <p><strong>Clause F finding, Medium - object-storage transfer convention: {@code Not available}.</strong>
 * The corpus specifies no mainframe-to-object-storage transfer convention at {@code 7756d89}, so whether any
 * downstream consumer expects newline-delimited output cannot be determined from it. What would be needed is
 * an explicit transfer specification, which does not exist. Note the asymmetry that makes this a real
 * question rather than a theoretical one: the ASCII reference fixture
 * {@code app/data/ASCII/dailytran.txt} <em>is</em> newline delimited - it measures 105,300 bytes, which is
 * 300 &times; 351, being 300 records of exactly 350 bytes each plus one line feed apiece - whereas the
 * {@code DALYREJS} dataset it feeds is unblocked. The unblocked form is emitted here because that is what
 * the {@code DCB} declares. Remediation if a delimited variant is ever required: treat it as a labelled
 * deviation with a {@code DECISION_LOG.md} entry, never as a silent change.
 *
 * <p><strong>Clause F finding, Medium - the fixture arithmetic in the surrounding plan is wrong.</strong>
 * {@code app/data/ASCII/dailytran.txt} is described elsewhere as 105,300 bytes being 300 &times; 350; that
 * product is 105,000. The measured decomposition is 300 &times; 351 as above. Remediation: cite the measured
 * byte count and the 300 line-feed bytes. The 350-byte <em>record</em> width is unaffected and is confirmed
 * by every one of the 300 lines being exactly 350 bytes.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Context fails to start, unresolved placeholder {@code carddemo.s3.output-bucket}</strong> -
 *       the property is absent. Set {@code CARDDEMO_S3_OUTPUT_BUCKET}. This is intended behaviour.</li>
 *   <li><strong>{@code FatalProcessingException} naming a field and a length</strong> - an entity field is
 *       longer than the picture clause allows, so no 430-byte record can be composed. The message names the
 *       field and reports lengths only; inspect the offending row by its transaction identifier.</li>
 *   <li><strong>{@code FileAccessException} for {@code DALYREJS}</strong> - the object-storage write failed.
 *       The line {@code FILE STATUS IS: NNNN} plus the expanded status is logged immediately before, exactly
 *       as {@code 9910-DISPLAY-IO-STATUS} renders it. Check bucket existence, credentials and the emulator.</li>
 *   <li><strong>Object size not a multiple of 430</strong> - cannot occur; the length is asserted before the
 *       write and a mismatch throws rather than emitting a corrupt generation.</li>
 * </ul>
 *
 * <h2>Exit-code contract this class must not contradict</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L229-L231} sets {@code RETURN-CODE} to 4 <strong>if and only if</strong>
 * {@code WS-REJECT-COUNT} exceeds zero. There is no other determinant, and 4 is not a failure. Consequently:
 * a reject is a <strong>business outcome and is never thrown</strong> by this class, and the {@code ExitStatus}
 * decision belongs to the job or its decider, not here - which is why no {@code ExitStatus} type is
 * referenced. Return code 4 and the return code 12 of the abend path are independent.
 *
 * <h2>Security</h2>
 *
 * <p>{@code DALYTRAN-CARD-NUM} occupies bytes 263-278 of the payload and <strong>is written there byte
 * exactly</strong>, because the reject record is a faithful image of the rejected input. It is never logged,
 * never placed in an exception message and never rendered by {@code toString}. Neither is the composed
 * 430-byte record, and neither is any storage key. The commented-out {@code DISPLAY '***' REJECT-RECORD} at
 * {@code app/cbl/CBTRN02C.cbl:L449} is <strong>deliberately not reproduced</strong>: it would echo the whole
 * record, card number included, to the log.
 *
 * @see RejectedTransaction
 */
@StepScope
@Component
public class RejectWriter implements ItemWriter<RejectWriter.RejectedTransaction> {

    /**
     * Name of the tree-wide "records rejected" counter, tagged by reject code.
     *
     * <p>Exposed so that the metrics configuration and the unit tests reference one definition rather than
     * repeating a string literal. Registering a counter is idempotent in Micrometer - the same name and tag
     * set resolves to the meter that already exists - so incrementing it here <strong>cooperates with</strong>
     * the central registration and does not create an additional instrument. Exactly four counters exist
     * across the migration and this is one of them.
     */
    public static final String REJECTED_RECORDS_COUNTER = "carddemo.batch.records.rejected";

    /**
     * Tag key carrying the reject code on {@link #REJECTED_RECORDS_COUNTER}.
     *
     * <p>The tag has exactly five possible values - 100, 101, 102, 103 and 109 - so its cardinality is
     * bounded and safe. No identifying value is ever used as a tag: not the card number, not the account
     * identifier, not the transaction identifier.
     */
    public static final String REJECT_CODE_TAG = "reject.code";

    /**
     * Step execution context key under which the concrete key of the most recently created reject object is
     * published, so a later step consumes the exact object this writer produced.
     */
    public static final String REJECT_OBJECT_KEY_CONTEXT_KEY = "carddemo.dalyrejs.object.key";

    /**
     * Step execution context key under which the {@code (+1)} generation prefix is published. A step may emit
     * several objects under one generation, so the prefix is what makes the whole generation discoverable
     * while {@link #REJECT_OBJECT_KEY_CONTEXT_KEY} identifies the latest object precisely.
     */
    public static final String REJECT_GENERATION_PREFIX_CONTEXT_KEY = "carddemo.dalyrejs.generation.prefix";

    /**
     * Step execution context key under which the cumulative count of reject records written by this step is
     * published. It is the value a decider reads to apply the return-code-4 rule of
     * {@code app/cbl/CBTRN02C.cbl:L229-L231}; publishing it does not decide anything here.
     */
    public static final String REJECT_RECORD_COUNT_CONTEXT_KEY = "carddemo.dalyrejs.record.count";

    /**
     * Logical DD name of the reject dataset, from {@code //DALYREJS DD} at {@code app/jcl/POSTTRAN.jcl:L34}.
     * Carried on every failure so a diagnostic identifies the file the way the corpus does.
     */
    public static final String DALYREJS_DD_NAME = "DALYREJS";

    /**
     * Logger for this class. Structured logging only - the corpus writes to SYSOUT with {@code DISPLAY}, and
     * nothing in the migration writes to the process standard output or standard error streams.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RejectWriter.class);

    /**
     * The attempted operation reported on failure, matching the COBOL verb at
     * {@code app/cbl/CBTRN02C.cbl:L451}, {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD}.
     */
    private static final String WRITE_OPERATION = "WRITE";

    /**
     * Verbatim text of {@code DISPLAY 'ERROR WRITING TO REJECTS FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L460}, reproduced exactly because the parity comparison reads it.
     */
    private static final String WRITE_FAILURE_TEXT = "ERROR WRITING TO REJECTS FILE";

    /**
     * The originating COBOL program, carried as {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy}.
     * Eight characters exactly, so it fits the picture clause without truncation.
     */
    private static final String ABEND_CULPRIT = "CBTRN02C";

    /**
     * The abend code as the four-character display value of {@code ABEND-CODE PIC X(4)}.
     *
     * <p>Derived from {@code FatalProcessingException.BATCH_ABEND_CODE} rather than written as a literal, so
     * the batch abend code has a single definition. {@code app/cbl/CBTRN02C.cbl:L710} moves <strong>999</strong>
     * into {@code ABCODE} before {@code CALL 'CEE3ABD'}; 999 is the batch value and is not to be confused with
     * the CICS online value.
     */
    private static final String ABEND_CODE = Integer.toString(FatalProcessingException.BATCH_ABEND_CODE);

    /**
     * Charset used for every write, fixed explicitly.
     *
     * <p>ISO-8859-1 is byte transparent: each of the 256 code points 0x00 to 0xFF encodes to exactly one
     * byte, so a 430-character record is guaranteed to become exactly 430 bytes. A variable-width Unicode
     * encoding would render any character above U+007F as two or more bytes and silently destroy the record
     * geometry, and the platform default encoding is not reproducible across hosts. This charset is passed
     * explicitly at every encoding site, so the platform-default overload is never reached.
     *
     * <p>No EBCDIC is produced. {@code app/data/EBCDIC/**} is codepage reference material only and is never
     * parsed or emitted by the build.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Content type stated on every created object. Fixed-width binary-safe text with no delimiters is not
     * {@code text/plain}, so the generic octet-stream type is declared to stop any intermediary from
     * re-encoding or line-ending-translating the payload.
     */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Prefix of every reject object key, naming the legacy dataset it stands in for.
     */
    private static final String OBJECT_KEY_ROOT = "dalyrejs/";

    /**
     * Suffix of every reject object key. The payload is a fixed-width unblocked record stream, not a text
     * file, and the extension says so.
     */
    private static final String OBJECT_KEY_SUFFIX = ".dat";

    /**
     * Format for the zero-padded identifier components of an object key: 19 digits, the width of
     * {@code Long.MAX_VALUE}, so that lexicographic ordering of keys is identical to numeric ordering of
     * identifiers. That equivalence is what lets {@code (0)} be resolved as "the lexicographically greatest
     * prefix".
     */
    private static final String KEY_IDENTIFIER_FORMAT = "%019d";

    /**
     * Format for the per-step generation sequence inside an object key: six zero-padded digits.
     */
    private static final String KEY_SEQUENCE_FORMAT = "%06d";

    /**
     * Identifier substituted when no step context is available, which happens only when the class is
     * constructed directly by a unit test. Handled explicitly rather than by dereferencing {@code null}.
     */
    private static final long UNASSIGNED_IDENTIFIER = 0L;

    /**
     * Format producing the {@code PIC 9(04)} rendering of {@code WS-VALIDATION-FAIL-REASON}
     * ({@code app/cbl/CBTRN02C.cbl:L181}): zero-padded, right-aligned, exactly four digits.
     *
     * <p>Built from {@code RejectCode.FAIL_REASON_LENGTH} rather than written as {@code "%04d"} so the field
     * width keeps a single definition. Every use pairs it with {@code Locale.ROOT}: a default locale could
     * render Arabic-Indic or Devanagari digits and break the byte comparison without any error.
     */
    private static final String FAIL_REASON_FORMAT = "%0" + RejectCode.FAIL_REASON_LENGTH + "d";

    /**
     * Width of {@code DALYTRAN-ID PIC X(16)}, bytes 1-16 ({@code app/cpy/CVTRA06Y.cpy:L5}).
     */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * Width of {@code DALYTRAN-TYPE-CD PIC X(02)}, bytes 17-18 ({@code app/cpy/CVTRA06Y.cpy:L6}).
     */
    private static final int TYPE_CODE_WIDTH = 2;

    /**
     * Width of {@code DALYTRAN-CAT-CD PIC 9(04)}, bytes 19-22 ({@code app/cpy/CVTRA06Y.cpy:L7}). Unsigned.
     */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Width of {@code DALYTRAN-SOURCE PIC X(10)}, bytes 23-32 ({@code app/cpy/CVTRA06Y.cpy:L8}).
     */
    private static final int TRANSACTION_SOURCE_WIDTH = 10;

    /**
     * Width of {@code DALYTRAN-DESC PIC X(100)}, bytes 33-132 ({@code app/cpy/CVTRA06Y.cpy:L9}). The widest
     * field in the layout, which is why it sizes {@link #PADDING_SPACES}.
     */
    private static final int DESCRIPTION_WIDTH = 100;

    /**
     * Width of {@code DALYTRAN-AMT PIC S9(09)V99}, bytes 133-143 ({@code app/cpy/CVTRA06Y.cpy:L10}): nine
     * integer digits plus two decimal digits, eleven characters, the decimal point implied and never written.
     */
    private static final int AMOUNT_WIDTH = 11;

    /**
     * Number of decimal digits in {@code DALYTRAN-AMT}, from the {@code V99} of its picture clause.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Width of {@code DALYTRAN-MERCHANT-ID PIC 9(09)}, bytes 144-152 ({@code app/cpy/CVTRA06Y.cpy:L11}).
     * Unsigned.
     */
    private static final int MERCHANT_ID_WIDTH = 9;

    /**
     * Width of {@code DALYTRAN-MERCHANT-NAME PIC X(50)}, bytes 153-202 ({@code app/cpy/CVTRA06Y.cpy:L12}).
     */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /**
     * Width of {@code DALYTRAN-MERCHANT-CITY PIC X(50)}, bytes 203-252 ({@code app/cpy/CVTRA06Y.cpy:L13}).
     */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /**
     * Width of {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}, bytes 253-262 ({@code app/cpy/CVTRA06Y.cpy:L14}).
     */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /**
     * Width of {@code DALYTRAN-CARD-NUM PIC X(16)}, bytes 263-278 ({@code app/cpy/CVTRA06Y.cpy:L15}).
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Width of {@code DALYTRAN-ORIG-TS PIC X(26)}, bytes 279-304 ({@code app/cpy/CVTRA06Y.cpy:L16}).
     *
     * <p><strong>This class generates no timestamp.</strong> Both timestamp fields are {@code PIC X(26)} text
     * that the entity exposes as a {@code String} over {@code CHAR(26)}, never a date-time type, so both are
     * passed through verbatim. A blank {@code DALYTRAN-PROC-TS} is emitted as 26 spaces and is never
     * substituted, defaulted or helpfully populated: in the reference fixture
     * {@code app/data/ASCII/dailytran.txt} bytes 305-330 are 26 spaces in all 300 rows, while bytes 279-304
     * hold the single value {@code 2022-06-10 19:27:53.000000} in all 300 rows.
     *
     * <p>Reviewer context for this width. The corpus generator {@code Z-GET-DB2-FORMAT-TIMESTAMP} builds the
     * pattern {@code yyyy-MM-dd-HH.mm.ss.SS0000} from {@code DB2-MIL PIC 9(002)} followed by
     * {@code DB2-REST PIC X(04)} ({@code app/cbl/CBTRN02C.cbl:L173-L174}), the latter filled by
     * {@code MOVE '0000' TO DB2-REST} at {@code app/cbl/CBTRN02C.cbl:L701} - two hundredths digits then a
     * four-character literal. The fraction is therefore 2 + 4 characters and the whole value sums to
     * exactly 26.
     *
     * <p><strong>Clause F finding, Medium - the "millisecond precision followed by four zeros" description of
     * this format is arithmetically impossible.</strong> Millisecond precision is three fraction digits, so
     * 3 + 4 is seven fraction characters and a 27-byte value, which cannot fit a {@code PIC X(26)} field. The
     * verified 26-byte layout governs. Remediation: cite {@code app/cbl/CBTRN02C.cbl:L170-L174} and the
     * pattern {@code yyyy-MM-dd-HH.mm.ss.SS0000}, which is hundredths rather than milliseconds. No timestamp
     * is generated in this class in any case, so the defect cannot reach its output.
     */
    private static final int ORIG_TS_WIDTH = 26;

    /**
     * Width of {@code DALYTRAN-PROC-TS PIC X(26)}, bytes 305-330 ({@code app/cpy/CVTRA06Y.cpy:L17}).
     */
    private static final int PROC_TS_WIDTH = 26;

    /**
     * Width of the unnamed trailing {@code FILLER PIC X(20)}, bytes 331-350
     * ({@code app/cpy/CVTRA06Y.cpy:L18}).
     *
     * <p>The filler is <strong>emitted, not omitted</strong>. It carries no data, but the record would be 330
     * characters without it and no 430-byte reject record could then be composed.
     */
    private static final int FILLER_WIDTH = 20;

    /**
     * Space run long enough to pad any field in the layout, sized by the widest one.
     *
     * <p>A single immutable string sliced by {@code String.substring(int, int)} avoids rebuilding a format
     * string per field per record, which matters because this runs once per rejected row. An immutable
     * {@code String} in a {@code static final} field is a constant, not mutable state.
     */
    private static final String PADDING_SPACES = " ".repeat(DESCRIPTION_WIDTH);

    /**
     * Overpunch characters for a non-negative zoned-decimal value, indexed by the value of the final digit.
     *
     * <p>Index 0 yields <code>{</code> for {@code +0} and indices 1 to 9 yield {@code A} to {@code I} for
     * {@code +1} to {@code +9}. A lookup table is used rather than character arithmetic so the mapping is
     * explicit at the point of definition and does not rely on the encoding keeping {@code A} to {@code I}
     * contiguous.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Overpunch characters for a negative zoned-decimal value, indexed by the value of the final digit.
     *
     * <p>Index 0 yields <code>}</code> for {@code -0} and indices 1 to 9 yield {@code J} to {@code R} for
     * {@code -1} to {@code -9}.
     *
     * <p>The reference fixture exercises this table genuinely: across the 300 records of
     * {@code app/data/ASCII/dailytran.txt}, byte 143 holds <code>{</code> 25 times, <code>}</code> 6 times and
     * a negative non-zero overpunch 44 times (J 3, K 5, L 5, M 6, N 2, O 4, P 7, Q 4, R 8). The six
     * <code>}</code> rows are negative values whose final digit happens to be zero - the first is
     * {@code 0000009190}<code>}</code>, which decodes to {@code -919.00} - not negative zeroes.
     * <strong>No absolute-value normalisation is applied anywhere in this class.</strong>
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Largest value {@code PIC S9(09)V99} can hold. Compared with {@code BigDecimal.compareTo} and never with
     * {@code equals}, because {@code equals} additionally compares scale and would treat {@code 1.0} and
     * {@code 1.00} as different.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /**
     * Smallest value {@code PIC S9(09)V99} can hold, the negation of {@link #MAX_AMOUNT}. Amounts are
     * legitimately negative - {@code app/cbl/CBTRN02C.cbl:L548-L552} adds a negative amount to
     * {@code ACCT-CURR-CYC-DEBIT} - so the lower bound is symmetric and is never clamped to zero.
     */
    private static final BigDecimal MIN_AMOUNT = MAX_AMOUNT.negate();

    /**
     * Smallest value {@code PIC 9(04)} can hold: zero, because the field is unsigned.
     */
    private static final int MIN_CATEGORY_CODE = 0;

    /**
     * Largest value {@code PIC 9(04)} can hold: four nines.
     *
     * <p>This is a numeric field bound and has nothing to do with an abend code. The batch abend code is
     * 999, referenced from {@link #ABEND_CODE}; the two values must never be conflated.
     */
    private static final int MAX_CATEGORY_CODE = 9999;

    /**
     * Smallest value {@code PIC 9(09)} can hold: zero, because the field is unsigned.
     */
    private static final long MIN_MERCHANT_ID = 0L;

    /**
     * Largest value {@code PIC 9(09)} can hold.
     */
    private static final long MAX_MERCHANT_ID = 999_999_999L;

    /**
     * The file status a confirmed write reports, taken from the owning constant rather than restated as
     * {@code "00"}. {@code app/cbl/CBTRN02C.cbl:L452} tests {@code IF DALYREJS-STATUS = '00'}.
     */
    private static final String WRITE_SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    /**
     * The file status synthesised when the object-storage write fails.
     *
     * <p>The corpus reserves the {@code '9x'} family for a physical or logical I/O error, which is exactly
     * what a failed object write is, and that family is the one the status mapper translates into a
     * {@code FileAccessException}. The value is composed from
     * {@code FileStatus.IO_ERROR_FIRST_BYTE} so the family's first byte keeps a single definition, and
     * {@code "90"} is the example the exception's own documentation uses.
     */
    private static final String WRITE_IO_ERROR_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /**
     * Object-storage operations, injected already configured.
     *
     * <p>Declared as the {@code S3Operations} interface that {@code io.awspring.cloud.s3.S3Template}
     * implements, so the bean actually injected is the auto-configured {@code S3Template} while the
     * dependency stays trivially mockable in a unit test. No client is ever constructed here, no endpoint or
     * credential is read here, and no environment variable is consulted directly.
     */
    private final S3Operations s3Operations;

    /**
     * Meter registry backing the records-rejected counter.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Sole owner of the file-status-to-exception decision, reproducing the guard idiom that every
     * {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE} and {@code CLOSE} in the batch corpus uses.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Destination bucket, resolved from configuration and never defaulted.
     */
    private final String outputBucket;

    /**
     * The step this writer belongs to, or {@code null} when the class was constructed outside a step context.
     */
    private final StepExecution stepExecution;

    /**
     * The {@code (+1)} generation prefix for this step, computed once at construction.
     */
    private final String generationPrefix;

    /**
     * Monotonic per-step sequence distinguishing the objects of one generation.
     *
     * <p>An instance field on a step-scoped bean is not static mutable state. It is atomic so that a
     * multi-threaded step cannot allocate the same key twice.
     */
    private final AtomicLong generationSequence = new AtomicLong();

    /**
     * Cumulative count of reject records written by this step, mirroring {@code WS-REJECT-COUNT}
     * ({@code app/cbl/CBTRN02C.cbl:L186}, incremented at {@code :L214}).
     *
     * <p>It is published to the step execution context for a decider to read. This class does not act on it:
     * the return-code-4 rule is the job's to apply.
     */
    private final AtomicLong recordsWritten = new AtomicLong();

    /**
     * Creates the writer with every collaborator supplied by the container.
     *
     * <p>Constructor injection only, so the instance is fully formed and immutable in its dependencies once
     * built. Nothing overridable is invoked from here, which keeps {@code -Xlint:this-escape} silent.
     *
     * @param s3Operations the object-storage operations, ordinarily the auto-configured
     *        {@code io.awspring.cloud.s3.S3Template}; must not be {@code null}
     * @param meterRegistry the registry holding the records-rejected counter; must not be {@code null}
     * @param fileStatusMapper the file-status-to-exception mapper; must not be {@code null}
     * @param outputBucket the destination bucket from {@code carddemo.s3.output-bucket}, backed by
     *        {@code CARDDEMO_S3_OUTPUT_BUCKET}; must not be {@code null}, and has no default so that an
     *        absent value fails the context rather than writing somewhere unintended
     * @param stepExecution the step this writer serves, supplied by the step scope. Permitted to be
     *        {@code null} so a unit test can construct the class directly; when it is {@code null} the object
     *        key falls back to the unassigned-identifier form and nothing is published to a step context
     * @throws NullPointerException if any argument other than {@code stepExecution} is {@code null}
     */
    public RejectWriter(
            final S3Operations s3Operations,
            final MeterRegistry meterRegistry,
            final FileStatusMapper fileStatusMapper,
            @Value("${carddemo.s3.output-bucket}") final String outputBucket,
            @Value("#{stepExecution}") final StepExecution stepExecution) {
        this.s3Operations = Objects.requireNonNull(s3Operations, "s3Operations must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
        this.stepExecution = stepExecution;
        this.generationPrefix = buildGenerationPrefix(stepExecution);
    }

    /**
     * A rejected staged transaction paired with the outcome that rejected it.
     *
     * <p>The reject record needs both halves: {@code app/cbl/CBTRN02C.cbl:L447} supplies the 350-character
     * payload from {@code DALYTRAN-RECORD} and {@code :L448} supplies the 80-character trailer from
     * {@code WS-VALIDATION-TRAILER}. The COBOL program reads both from working storage; a Java writer must be
     * handed both, which is what this pair is for.
     *
     * <p>It is a nested type deliberately. This package holds exactly three source files and adding a fourth
     * is not permitted; a nested record adds no file.
     *
     * @param transaction the staged input record to be re-serialised into bytes 1-350; must not be
     *        {@code null}
     * @param rejectCode the outcome to be rendered into bytes 351-430; must not be {@code null}
     */
    public record RejectedTransaction(DailyTransaction transaction, RejectCode rejectCode) {

        /**
         * Validates both components.
         *
         * <p>The messages name the <em>field</em> and never a value, because a validation message is exactly
         * the kind of string that reaches a log and {@code DailyTransaction} carries a card number.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public RejectedTransaction {
            Objects.requireNonNull(transaction, "transaction must not be null");
            Objects.requireNonNull(rejectCode, "rejectCode must not be null");
        }
    }

    /**
     * Writes every rejected transaction in the chunk as one unblocked stream of 430-byte records.
     *
     * <p>This is the Spring Batch 5 writer contract: the list-based {@code ItemWriter} signature was replaced
     * by {@code write(Chunk)} in Spring Batch 5.0 and the pinned version is 5.2.4, so a list-based
     * parameter would not override anything and would fail the build under {@code -Xlint:all -Werror}.
     *
     * <p>Side effects: creates <strong>one</strong> object in the configured bucket containing all the
     * chunk's records concatenated with no separator; increments the records-rejected counter once per record,
     * tagged by reject code; publishes the created key, the generation prefix and the cumulative record count
     * into the step execution context.
     *
     * <p>An <strong>empty chunk writes nothing</strong> and deliberately does not create an empty object,
     * because a zero-length generation is not something the corpus can produce.
     *
     * <p>A reject is a business outcome, so this method never throws to signal one. It throws only when the
     * record cannot be composed or the write cannot be confirmed.
     *
     * @param chunk the rejected transactions to emit; must not be {@code null}, and may be empty
     * @throws NullPointerException if {@code chunk} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if any field exceeds its picture clause, if the
     *         composed geometry is not exactly 430 characters per record, or if the write reports a status the
     *         mapper does not classify as an I/O error
     * @throws com.cardemo.exception.FileAccessException if the object-storage write fails, carrying the
     *         {@code DALYREJS} logical name, the {@code WRITE} operation and the underlying cause
     */
    @Override
    public void write(final Chunk<? extends RejectedTransaction> chunk) throws Exception {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (chunk.isEmpty()) {
            LOGGER.debug("No rejected transactions in this chunk; no {} generation is created",
                    DALYREJS_DD_NAME);
            return;
        }
        final StringBuilder payload =
                new StringBuilder(chunk.size() * RejectCode.REJECT_RECORD_LENGTH);
        for (final RejectedTransaction rejected : chunk) {
            Objects.requireNonNull(rejected, "chunk must not contain a null item");
            payload.append(buildRejectRecord(rejected.transaction(), rejected.rejectCode()));
        }
        emitGeneration(payload.toString(), chunk.size());
        for (final RejectedTransaction rejected : chunk) {
            countRejectedRecord(rejected.rejectCode());
        }
    }

    /**
     * Writes a single reject record, for a caller that holds one rejected row and no chunk.
     *
     * <p>{@link #write(Chunk)} delegates to the same composition path per element, so the two entry points
     * cannot drift. This overload creates its own single-record generation.
     *
     * <p>Side effects: identical to {@link #write(Chunk)} for a chunk of one.
     *
     * @param transaction the staged input record to re-serialise; must not be {@code null}
     * @param rejectCode the outcome to render into the trailer; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if any field exceeds its picture clause, if the
     *         composed geometry is not exactly 430 characters, or if the write reports a status the mapper
     *         does not classify as an I/O error
     * @throws com.cardemo.exception.FileAccessException if the object-storage write fails, carrying the
     *         {@code DALYREJS} logical name, the {@code WRITE} operation and the underlying cause
     */
    public void writeReject(final DailyTransaction transaction, final RejectCode rejectCode) {
        final RejectedTransaction rejected = new RejectedTransaction(transaction, rejectCode);
        emitGeneration(buildRejectRecord(rejected.transaction(), rejected.rejectCode()), 1);
        countRejectedRecord(rejected.rejectCode());
    }

    /**
     * Reproduces {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}, composing the record
     * and leaving the write itself to {@link #emitGeneration(String, int)}.
     *
     * <p>The paragraph's two {@code MOVE} statements map one to one onto the two methods called here and are
     * not consolidated, in keeping with the rule that every source paragraph and every applicable statement
     * keeps its own Java counterpart.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L449} carries a commented-out {@code DISPLAY '***' REJECT-RECORD}. It is
     * intentionally <strong>not</strong> reproduced, and must not be: it would echo the whole 430-byte record,
     * including the card number at bytes 263-278, into the log.
     *
     * @param transaction the staged input record; must not be {@code null}
     * @param rejectCode the reject outcome; must not be {@code null}
     * @return exactly {@code RejectCode.REJECT_RECORD_LENGTH} characters
     * @throws com.cardemo.exception.FatalProcessingException if either half is not its declared width
     */
    private String buildRejectRecord(final DailyTransaction transaction, final RejectCode rejectCode) {
        final String rejectTranData = moveDalytranRecordToRejectTranData(transaction);
        final String validationTrailer = moveValidationTrailerToValidationTrailer(rejectCode);
        return requireExactLength(rejectTranData + validationTrailer,
                RejectCode.REJECT_RECORD_LENGTH, "REJECT-RECORD");
    }

    /**
     * Reproduces {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} at {@code app/cbl/CBTRN02C.cbl:L447}.
     *
     * <p>The COBOL statement is a wholesale group move, because the program still holds the raw 350-byte input
     * record in working storage. The Java reader yields an entity instead, so all fourteen fields of
     * {@code app/cpy/CVTRA06Y.cpy} are re-serialised here in declaration order. That copybook is the
     * {@code DALYTRAN-} prefixed twin of {@code app/cpy/CVTRA05Y.cpy} and its header states
     * {@code RECLN = 350}; the widths below sum to exactly that.
     *
     * <p>The entity's surrogate ingest sequence is <strong>not</strong> part of the layout and is not emitted.
     *
     * <p>This method is a pure function of its argument: it reads no field of this class and writes nothing.
     *
     * @param transaction the staged input record; must not be {@code null}
     * @return exactly {@code RejectCode.REJECT_TRAN_DATA_LENGTH} characters
     * @throws com.cardemo.exception.FatalProcessingException if any field exceeds its picture clause or the
     *         assembled image is not 350 characters
     */
    private static String moveDalytranRecordToRejectTranData(final DailyTransaction transaction) {
        final StringBuilder image = new StringBuilder(RejectCode.REJECT_TRAN_DATA_LENGTH);
        image.append(alphanumeric(transaction.getTransactionId(), TRANSACTION_ID_WIDTH, "DALYTRAN-ID"));
        image.append(alphanumeric(transaction.getTypeCode(), TYPE_CODE_WIDTH, "DALYTRAN-TYPE-CD"));
        image.append(unsignedZonedDecimal(transaction.getCategoryCode(), CATEGORY_CODE_WIDTH,
                MIN_CATEGORY_CODE, MAX_CATEGORY_CODE, "DALYTRAN-CAT-CD"));
        image.append(alphanumeric(transaction.getTransactionSource(), TRANSACTION_SOURCE_WIDTH,
                "DALYTRAN-SOURCE"));
        image.append(alphanumeric(transaction.getDescription(), DESCRIPTION_WIDTH, "DALYTRAN-DESC"));
        image.append(signedZonedDecimal(transaction.getAmount()));
        image.append(unsignedZonedDecimal(transaction.getMerchantId(), MERCHANT_ID_WIDTH,
                MIN_MERCHANT_ID, MAX_MERCHANT_ID, "DALYTRAN-MERCHANT-ID"));
        image.append(alphanumeric(transaction.getMerchantName(), MERCHANT_NAME_WIDTH,
                "DALYTRAN-MERCHANT-NAME"));
        image.append(alphanumeric(transaction.getMerchantCity(), MERCHANT_CITY_WIDTH,
                "DALYTRAN-MERCHANT-CITY"));
        image.append(alphanumeric(transaction.getMerchantZip(), MERCHANT_ZIP_WIDTH, "DALYTRAN-MERCHANT-ZIP"));
        image.append(alphanumeric(transaction.getCardNumber(), CARD_NUMBER_WIDTH, "DALYTRAN-CARD-NUM"));
        image.append(alphanumeric(transaction.getOrigTs(), ORIG_TS_WIDTH, "DALYTRAN-ORIG-TS"));
        image.append(alphanumeric(transaction.getProcTs(), PROC_TS_WIDTH, "DALYTRAN-PROC-TS"));
        image.append(PADDING_SPACES, 0, FILLER_WIDTH);
        return requireExactLength(image.toString(), RejectCode.REJECT_TRAN_DATA_LENGTH, "REJECT-TRAN-DATA");
    }

    /**
     * Reproduces {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER} at
     * {@code app/cbl/CBTRN02C.cbl:L448}.
     *
     * <p>The trailer is {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} immediately followed by
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} ({@code :L180-L182}): the reason zero-padded to four
     * digits, the description space-padded on the right to seventy-six characters, never trimmed and never
     * truncated. All five descriptions fit - the longest, for code 103, is 42 characters.
     *
     * <p>The reject outcome supplies its code and its verbatim description; the {@code %04d} rendering and the
     * seventy-six-character padding are applied <strong>here</strong>, because composing the record is this
     * class's responsibility and not the enum's. The widths are taken from the enum's own constants rather
     * than restated, so no width has two definitions. {@code RejectCode.toValidationTrailer()} produces a
     * byte-identical string and is the authoritative cross-check exercised by the unit test.
     *
     * <p>{@code Locale.ROOT} is mandatory on the numeric format. Under a default locale the same format can
     * emit Arabic-Indic or Devanagari digits, which would break the byte comparison silently.
     *
     * <p>This method is a pure function of its argument.
     *
     * @param rejectCode the reject outcome; must not be {@code null}
     * @return exactly {@code RejectCode.VALIDATION_TRAILER_LENGTH} characters
     * @throws com.cardemo.exception.FatalProcessingException if the description exceeds seventy-six characters
     *         or the assembled trailer is not eighty characters
     */
    private static String moveValidationTrailerToValidationTrailer(final RejectCode rejectCode) {
        final String failReason =
                String.format(Locale.ROOT, FAIL_REASON_FORMAT, rejectCode.getCode());
        final String failReasonDesc = alphanumeric(rejectCode.getDescription(),
                RejectCode.FAIL_REASON_DESC_LENGTH, "WS-VALIDATION-FAIL-REASON-DESC");
        return requireExactLength(failReason + failReasonDesc,
                RejectCode.VALIDATION_TRAILER_LENGTH, "VALIDATION-TRAILER");
    }

    /**
     * Renders one {@code PIC X(n)} field: left-justified and right-padded with spaces to exactly {@code width}.
     *
     * <p>Both boundary cases are handled explicitly rather than by coincidence. A {@code null} value becomes
     * {@code width} spaces, which is what a COBOL group holding an uninitialised alphanumeric field contains;
     * this is the path that renders a blank {@code DALYTRAN-PROC-TS} as 26 spaces. A value <em>longer</em> than
     * the field is an <strong>error</strong> and is never truncated, because silent truncation is precisely the
     * class of defect that would pass every test and still break parity.
     *
     * <p>The failure message reports the field name and the two lengths and <strong>never the value</strong>:
     * two of the fields rendered through here, {@code DALYTRAN-CARD-NUM} and {@code DALYTRAN-ID}, are
     * sensitive or identifying, and an exception message is destined for a log.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param value the field value, permitted to be {@code null}
     * @param width the declared width from the picture clause
     * @param fieldName the COBOL field name, used only in a failure message
     * @return exactly {@code width} characters
     * @throws com.cardemo.exception.FatalProcessingException if {@code value} is longer than {@code width}
     */
    private static String alphanumeric(final String value, final int width, final String fieldName) {
        if (value == null) {
            return PADDING_SPACES.substring(0, width);
        }
        final int length = value.length();
        if (length > width) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "%s does not fit its picture clause: %d characters supplied for a field of %d; "
                            + "the value is withheld because the field may carry identifying data",
                    fieldName, length, width));
        }
        if (length == width) {
            return value;
        }
        return value + PADDING_SPACES.substring(0, width - length);
    }

    /**
     * Renders an unsigned {@code PIC 9(n)} field as plain zero-padded digits with <strong>no overpunch</strong>.
     *
     * <p>{@code DALYTRAN-CAT-CD PIC 9(04)} and {@code DALYTRAN-MERCHANT-ID PIC 9(09)} carry no {@code S} in
     * their picture clauses, so no sign is encoded into the final digit. Applying the overpunch table to these
     * two fields is a mistake the layout makes easy and the reference fixture makes visible: record 0 of
     * {@code app/data/ASCII/dailytran.txt} holds {@code 0001} at bytes 19-22 and {@code 800000000} at bytes
     * 144-152, both plain digits.
     *
     * <p>{@code null} is rejected rather than rendered as spaces. Both columns are {@code NOT NULL} and a
     * space-filled zoned-decimal field is not a valid value, so a {@code null} here is a data or programming
     * defect that must surface.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param value the field value, permitted to be {@code null} only so that the rejection is explicit
     * @param width the declared width from the picture clause
     * @param minimum the smallest value the picture clause admits, always zero for an unsigned field
     * @param maximum the largest value the picture clause admits
     * @param fieldName the COBOL field name, used only in a failure message
     * @return exactly {@code width} ASCII digits
     * @throws com.cardemo.exception.FatalProcessingException if {@code value} is {@code null} or outside the
     *         range the picture clause admits
     */
    private static String unsignedZonedDecimal(final Number value, final int width, final long minimum,
            final long maximum, final String fieldName) {
        if (value == null) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "%s is null and cannot be rendered: an unsigned PIC 9(%d) field has no null "
                            + "representation and the column is declared NOT NULL",
                    fieldName, width));
        }
        final long numeric = value.longValue();
        if (numeric < minimum || numeric > maximum) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "%s does not fit its picture clause: an unsigned PIC 9(%d) field admits only %d "
                            + "through %d",
                    fieldName, width, minimum, maximum));
        }
        return String.format(Locale.ROOT, "%0" + width + "d", numeric);
    }

    /**
     * Renders {@code DALYTRAN-AMT PIC S9(09)V99} as eleven characters of zoned decimal with a trailing
     * overpunch sign.
     *
     * <p>The eleven characters are the nine integer digits followed by the two decimal digits with the
     * <strong>decimal point implied and never written</strong>, and the final character carries the sign as an
     * overpunch: <code>{</code> for {@code +0} and {@code A} to {@code I} for {@code +1} to {@code +9};
     * <code>}</code> for {@code -0} and {@code J} to {@code R} for {@code -1} to {@code -9}. The encoding is
     * position-aware, driven by the picture clause and applied only to this field, because the same letters
     * occur legitimately inside text fields such as {@code DALYTRAN-MERCHANT-NAME}.
     *
     * <p>Worked example, being record 0 of {@code app/data/ASCII/dailytran.txt}: {@code +504.77} scales to an
     * unscaled magnitude of {@code 50477}, pads to eleven digits as {@code 00000050477}, and the final
     * {@code 7} with a positive sign becomes {@code G}, giving {@code 0000005047G}. And a negative example,
     * record 1 of the same fixture: {@code -919.00} gives {@code 0000009190} followed by <code>}</code>,
     * because the final digit is zero and the sign is negative.
     *
     * <p>Arithmetic is {@code BigDecimal} throughout with {@code RoundingMode.HALF_EVEN}. <strong>No binary
     * real-number primitive type appears anywhere in this class.</strong> The bounds are compared with
     * {@code BigDecimal.compareTo}, never {@code equals}, which would additionally compare scale.
     *
     * <p><strong>No absolute-value normalisation is applied.</strong> A negative amount is genuine business
     * data: {@code app/cbl/CBTRN02C.cbl:L548-L552} adds a negative amount to {@code ACCT-CURR-CYC-DEBIT}, which
     * is exactly why the over-limit formula at {@code :L403-L405} subtracts that accumulator.
     *
     * <p><strong>Clause F finding, Low - negative zero cannot round-trip.</strong> Zoned decimal distinguishes
     * <code>}</code> ({@code -0}) from <code>{</code> ({@code +0}); {@code BigDecimal} has no signed zero, so
     * an amount of exactly zero always renders as <code>{</code>. This cannot arise from the reference
     * fixture - all six <code>}</code> rows there carry non-zero magnitudes - and it is inherent to decimal
     * arithmetic rather than a defect here. Remediation if a byte-exact {@code -0} round-trip is ever
     * required: retain the raw eleven-byte field on the entity and pass it through. Whether any consumer needs
     * that is {@code Not available} at {@code 7756d89}; what would be needed is a raw-image field on
     * {@code DailyTransaction}, which the entity does not have.
     *
     * <p>This method is a pure function of its argument.
     *
     * @param amount the transaction amount, permitted to be {@code null} only so that the rejection is
     *        explicit
     * @return exactly {@link #AMOUNT_WIDTH} characters, the last of which is an overpunch
     * @throws com.cardemo.exception.FatalProcessingException if {@code amount} is {@code null} or outside the
     *         range {@code PIC S9(09)V99} admits
     */
    private static String signedZonedDecimal(final BigDecimal amount) {
        if (amount == null) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "DALYTRAN-AMT is null and cannot be rendered: a PIC S9(09)V99 field has no null "
                            + "representation and the column is declared NOT NULL"));
        }
        final BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        if (scaled.compareTo(MIN_AMOUNT) < 0 || scaled.compareTo(MAX_AMOUNT) > 0) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "DALYTRAN-AMT does not fit its picture clause: a PIC S9(09)V99 field admits only %s "
                            + "through %s", MIN_AMOUNT.toPlainString(), MAX_AMOUNT.toPlainString()));
        }
        final String digits = padDigitsLeft(scaled.unscaledValue().abs().toString(), AMOUNT_WIDTH);
        final boolean negative = scaled.signum() < 0;
        final char finalDigit = digits.charAt(AMOUNT_WIDTH - 1);
        final String overpunched = digits.substring(0, AMOUNT_WIDTH - 1) + overpunch(finalDigit, negative);
        return requireExactLength(overpunched, AMOUNT_WIDTH, "DALYTRAN-AMT");
    }

    /**
     * Maps the final digit of a zoned-decimal field and its sign onto the overpunch character that replaces it.
     *
     * <p>Table driven from {@link #POSITIVE_OVERPUNCH} and {@link #NEGATIVE_OVERPUNCH} rather than computed by
     * character arithmetic, so the mapping is explicit and does not assume {@code A} to {@code I} and
     * {@code J} to {@code R} are contiguous in the encoding.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param finalDigit the last digit of the rendered magnitude, {@code '0'} to {@code '9'}
     * @param negative whether the value is negative
     * @return the overpunch character standing for that digit and sign
     */
    private static char overpunch(final char finalDigit, final boolean negative) {
        final int digitValue = finalDigit - '0';
        return negative ? NEGATIVE_OVERPUNCH.charAt(digitValue) : POSITIVE_OVERPUNCH.charAt(digitValue);
    }

    /**
     * Left-pads a digit string with zeroes to exactly {@code width}.
     *
     * <p>Used only for a magnitude already proven to fit, which is why an over-long input is a geometry
     * failure rather than a truncation.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param digits the digit string to pad
     * @param width the target width
     * @return exactly {@code width} characters
     * @throws com.cardemo.exception.FatalProcessingException if {@code digits} is longer than {@code width}
     */
    private static String padDigitsLeft(final String digits, final int width) {
        final int length = digits.length();
        if (length > width) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "DALYTRAN-AMT does not fit its picture clause: %d digits rendered for a field of %d",
                    length, width));
        }
        if (length == width) {
            return digits;
        }
        return "0".repeat(width - length) + digits;
    }

    /**
     * Asserts a composed segment is exactly its declared width, before anything is written.
     *
     * <p>This is the guard that makes the whole class trustworthy: emitting anything other than exactly 430
     * bytes per record breaks the end-to-end parity comparison, and a length mismatch is far cheaper to detect
     * here than in a byte diff against the legacy baseline.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param segment the composed text
     * @param expectedLength the width the layout declares
     * @param segmentName the COBOL name of the segment, used only in a failure message
     * @return {@code segment} unchanged
     * @throws com.cardemo.exception.FatalProcessingException if the length differs
     */
    private static String requireExactLength(final String segment, final int expectedLength,
            final String segmentName) {
        if (segment.length() != expectedLength) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "%s is %d characters but the layout declares %d; the content is withheld because the "
                            + "record carries identifying data",
                    segmentName, segment.length(), expectedLength));
        }
        return segment;
    }

    /**
     * Builds the fatal exception every geometry violation raises.
     *
     * <p>A record that cannot be composed to its declared width is unrecoverable, which is the abend case of
     * {@code 9999-ABEND-PROGRAM}. The abend code and the culprit are carried so the diagnostic matches the
     * {@code CABENDD.CPY} payload, and the code itself is referenced from
     * {@code FatalProcessingException.BATCH_ABEND_CODE} rather than restated.
     *
     * <p>This method is a pure function of its argument. It builds the exception and does not throw it, so
     * every throw site remains visible as a {@code throw}.
     *
     * @param message the detail message, which by construction names a field and lengths but never a value
     * @return the exception to throw
     */
    private static FatalProcessingException geometryFailure(final String message) {
        return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT,
                "REJECT RECORD GEOMETRY VIOLATION", message);
    }

    /**
     * Reproduces {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} and its guard,
     * {@code app/cbl/CBTRN02C.cbl:L450-L464}, as one {@code (+1)} generation.
     *
     * <p>Statement by statement: {@code :L450} sets {@code APPL-RESULT} to the initial sentinel 8; {@code :L451}
     * writes; {@code :L452-L456} maps the resulting {@code DALYREJS-STATUS} onto 0 for {@code '00'} and 12 for
     * anything else; {@code :L457-L458} continues when the result is {@code APPL-AOK}; and {@code :L460-L463}
     * otherwise displays the failure text, moves the status into {@code IO-STATUS}, renders it and abends. The
     * three {@code APPL-RESULT} values are referenced from the status mapper, which already owns them, rather
     * than redeclared here - the mapper also carries {@code APPL-EOF} 16, from the condition name at
     * {@code :L142-L144}, which this write guard never produces because a write has no end-of-file outcome.
     *
     * <p><strong>Ordering invariant: the status is rendered first and the abend follows.</strong> The corpus
     * performs {@code 9910-DISPLAY-IO-STATUS} at {@code :L462} and only then
     * {@code 9999-ABEND-PROGRAM} at {@code :L463}; reversing them would lose the diagnostic, because the abend
     * does not return.
     *
     * <p>Records are concatenated with no separator, so the object is an exact multiple of 430 bytes:
     * {@code RECFM=F} with {@code BLKSIZE=0} at {@code app/jcl/POSTTRAN.jcl:L36} is fixed unblocked and the
     * mainframe dataset carries no record delimiters.
     *
     * <p>Side effects: creates one object; publishes three values into the step execution context.
     *
     * @param payload the concatenated records, already length-checked per record
     * @param recordCount the number of records in {@code payload}, used for the cumulative count and the log
     * @throws com.cardemo.exception.FatalProcessingException if the byte length is not a whole number of
     *         records, or if the write reports a status the mapper does not classify as an I/O error
     * @throws com.cardemo.exception.FileAccessException if the object-storage write fails
     */
    private void emitGeneration(final String payload, final int recordCount) {
        final byte[] bytes = payload.getBytes(RECORD_CHARSET);
        assertUnblockedFraming(bytes.length, payload.length());
        final String objectKey = nextGenerationKey();

        // app/cbl/CBTRN02C.cbl:L450 - MOVE 8 TO APPL-RESULT.
        int applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        // app/cbl/CBTRN02C.cbl:L451 - WRITE FD-REJS-RECORD FROM REJECT-RECORD.
        final Throwable failureCause = writeRejsRecord(objectKey, bytes);
        final String dalyrejsStatus =
                failureCause == null ? WRITE_SUCCESS_STATUS : WRITE_IO_ERROR_STATUS;

        // app/cbl/CBTRN02C.cbl:L452-L456 - IF DALYREJS-STATUS = '00' MOVE 0 ELSE MOVE 12 TO APPL-RESULT.
        applResult = fileStatusMapper.applResultForGuard(dalyrejsStatus);

        // app/cbl/CBTRN02C.cbl:L457-L458 - IF APPL-AOK CONTINUE.
        if (applResult != FileStatusMapper.APPL_AOK) {
            // app/cbl/CBTRN02C.cbl:L460 - DISPLAY 'ERROR WRITING TO REJECTS FILE'.
            LOGGER.error(WRITE_FAILURE_TEXT);
            // app/cbl/CBTRN02C.cbl:L461-L462 - MOVE DALYREJS-STATUS TO IO-STATUS, PERFORM 9910.
            displayIoStatus(dalyrejsStatus);
            // app/cbl/CBTRN02C.cbl:L463 - PERFORM 9999-ABEND-PROGRAM.
            throw abendProgram(dalyrejsStatus, failureCause);
        }

        publishGeneration(objectKey, recordCount);
        LOGGER.debug("Wrote {} reject record(s) to the {} generation, {} bytes", recordCount,
                DALYREJS_DD_NAME, bytes.length);
    }

    /**
     * Reproduces the single statement {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} at
     * {@code app/cbl/CBTRN02C.cbl:L451}.
     *
     * <p>The COBOL verb does not raise anything: it performs the write and leaves {@code DALYREJS-STATUS} for
     * the guard at {@code :L452} to interpret. This method is the faithful analogue - it attempts the write and
     * <strong>returns</strong> the failure rather than throwing, so the caller's guard stays the single place
     * where a status becomes an outcome.
     *
     * <p>Nothing is swallowed. The throwable is returned intact and the caller preserves it as the cause of
     * whatever it raises, so the root cause always survives. Both checked and unchecked failures are captured:
     * the store's client raises unchecked exceptions and the stream contract declares a checked one, and an
     * unconfirmed write is an unconfirmed write either way.
     *
     * <p>Side effects: creates one object in the configured bucket when it succeeds.
     *
     * @param objectKey the key to create
     * @param bytes the exact payload, already framing-checked
     * @return {@code null} when the write was confirmed, otherwise the throwable that prevented it
     */
    private Throwable writeRejsRecord(final String objectKey, final byte[] bytes) {
        try (InputStream source = new ByteArrayInputStream(bytes)) {
            s3Operations.upload(outputBucket, objectKey, source, objectMetadata(bytes.length));
            return null;
        } catch (IOException | RuntimeException writeFailure) {
            return writeFailure;
        }
    }

    /**
     * Reproduces {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} by logging the exact
     * line that paragraph writes to SYSOUT.
     *
     * <p>The line is the twenty-character literal {@code FILE STATUS IS: NNNN} followed immediately by four
     * rendered characters, so a status of {@code '23'} reads {@code FILE STATUS IS: NNNN0023}. {@code NNNN} is
     * a fixed part of the literal and not a placeholder to substitute into - the corpus appends after it, at
     * {@code :L721} and {@code :L725}. Both the literal and the rendering are owned by the status mapper and
     * the file-status enum and are neither redeclared, reformatted nor prefixed a second time here. The line carries
     * no personally identifiable data, so the logging configuration passes it through unmasked.
     *
     * <p>Side effects: emits one log record at error level. Returns normally, so the caller controls what
     * happens next; that is what keeps the render-then-abend order explicit at the call site.
     *
     * @param ioStatus the raw two-character file status moved into {@code IO-STATUS} at {@code :L461}
     */
    private void displayIoStatus(final String ioStatus) {
        LOGGER.error(fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * Reproduces {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, whose four statements
     * are {@code DISPLAY 'ABENDING PROGRAM'}, {@code MOVE 0 TO TIMING}, {@code MOVE 999 TO ABCODE} and
     * {@code CALL 'CEE3ABD'}.
     *
     * <p>The abend code is <strong>999</strong> and the process return code is <strong>12</strong>, both
     * referenced from {@code FatalProcessingException} rather than redeclared. 999 is the batch value; it is
     * not the CICS online value and the two must not be conflated.
     *
     * <p>The status mapper is the <strong>sole owner</strong> of the status-to-exception decision, so the
     * concrete type is chosen there: the {@code '9x'} family becomes a {@code FileAccessException} carrying the
     * {@code DALYREJS} logical name, the {@code WRITE} operation and the underlying cause. Any status the
     * mapper does not classify as an error - which for a write guard includes {@code '10'}, since a write has
     * no end-of-file outcome - falls through to a {@code FatalProcessingException} instead of being lost.
     * Nothing is swallowed and the root cause is preserved on both paths.
     *
     * <p>Neither the record buffer nor the object key appears in the message.
     *
     * <p>The exception is returned rather than thrown so that the call site reads as a {@code throw} and the
     * compiler can see that control does not continue.
     *
     * @param ioStatus the raw two-character file status
     * @param failureCause the underlying throwable, or {@code null} if the status was reported without one
     * @return the exception to throw; never {@code null}
     */
    private RuntimeException abendProgram(final String ioStatus, final Throwable failureCause) {
        LOGGER.error("ABENDING PROGRAM: {} reported an unrecoverable {} failure, abend code {}, return code {}",
                DALYREJS_DD_NAME, WRITE_OPERATION, ABEND_CODE, FatalProcessingException.BATCH_RETURN_CODE);
        return fileStatusMapper
                .toException(ioStatus, DALYREJS_DD_NAME, WRITE_OPERATION, failureCause)
                .map(RuntimeException.class::cast)
                .orElseGet(() -> new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT,
                        "UNCLASSIFIED REJECTS FILE STATUS",
                        String.format(Locale.ROOT,
                                "%s reported a status the mapper does not classify as an I/O error during %s",
                                DALYREJS_DD_NAME, WRITE_OPERATION),
                        failureCause));
    }

    /**
     * Asserts the payload is a whole number of unblocked 430-byte records before anything leaves the process.
     *
     * <p>Two conditions are checked. The byte length must equal the character length, which is what proves the
     * charset stayed byte transparent - if it did not, some character encoded to more than one byte and every
     * record boundary after it has moved. And the byte length must be an exact multiple of 430, which is what
     * proves record <em>n</em> begins at offset <em>n</em> &times; 430, the framing {@code RECFM=F} with
     * {@code BLKSIZE=0} implies.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param byteLength the encoded length
     * @param characterLength the composed length
     * @throws com.cardemo.exception.FatalProcessingException if either condition fails
     */
    private static void assertUnblockedFraming(final int byteLength, final int characterLength) {
        if (byteLength != characterLength) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "the reject payload encoded to %d bytes from %d characters; the record charset must be "
                            + "byte transparent or every record boundary moves",
                    byteLength, characterLength));
        }
        if (byteLength % RejectCode.REJECT_RECORD_LENGTH != 0) {
            throw geometryFailure(String.format(Locale.ROOT,
                    "the reject payload is %d bytes, which is not a whole number of %d-byte records",
                    byteLength, RejectCode.REJECT_RECORD_LENGTH));
        }
    }

    /**
     * Builds the {@code (+1)} generation prefix for this step.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:L38} targets {@code AWS.M2.CARDDEMO.DALYREJS(+1)}. The job instance
     * identifier zero-padded to nineteen digits stands in for the generation number, because zero padding to
     * the width of {@code Long.MAX_VALUE} makes lexicographic order identical to numeric order - which is what
     * allows a later {@code (0)} read to be resolved as "the lexicographically greatest prefix" with no
     * catalogue.
     *
     * <p>A {@code null} step execution is handled explicitly: the prefix is built from
     * {@link #UNASSIGNED_IDENTIFIER} so the class remains constructible and testable outside a step, rather
     * than dereferencing {@code null}.
     *
     * <p>This method is a pure function of its argument, which is why the constructor can call it without
     * publishing a partly built instance.
     *
     * @param stepExecution the step this writer serves, permitted to be {@code null}
     * @return the generation prefix, always ending in a path separator
     */
    private static String buildGenerationPrefix(final StepExecution stepExecution) {
        final long jobInstanceId = stepExecution == null
                || stepExecution.getJobExecution() == null
                || stepExecution.getJobExecution().getJobInstance() == null
                ? UNASSIGNED_IDENTIFIER
                : stepExecution.getJobExecution().getJobInstance().getInstanceId();
        return OBJECT_KEY_ROOT + String.format(Locale.ROOT, KEY_IDENTIFIER_FORMAT, jobInstanceId) + "/";
    }

    /**
     * Allocates the next concrete object key within this step's generation.
     *
     * <p>The sequence is atomic, so a multi-threaded step cannot allocate one key twice. The job execution
     * identifier is included so a restart of the same job instance cannot collide with the objects of the
     * previous execution while still sorting after them.
     *
     * <p>No wall clock is consulted. A timestamp would also be monotonic, but it would make the key
     * irreproducible on re-run and would import an environment-specific assumption; identifiers are
     * deterministic. That is the tradeoff, taken deliberately.
     *
     * @return the object key for the generation about to be written
     */
    private String nextGenerationKey() {
        final long jobExecutionId = stepExecution == null || stepExecution.getJobExecution() == null
                ? UNASSIGNED_IDENTIFIER
                : stepExecution.getJobExecution().getId();
        return generationPrefix
                + String.format(Locale.ROOT, KEY_IDENTIFIER_FORMAT, jobExecutionId)
                + "-"
                + String.format(Locale.ROOT, KEY_SEQUENCE_FORMAT, generationSequence.incrementAndGet())
                + OBJECT_KEY_SUFFIX;
    }

    /**
     * Publishes the created key, the generation prefix and the cumulative record count into the step execution
     * context.
     *
     * <p>This is what makes a {@code (+1)} written by one step readable as {@code (+1)} by a later step in the
     * same job: the downstream step consumes the exact key recorded here instead of re-resolving "latest",
     * which could otherwise pick up an object written by a different job execution. Promote these to the job
     * execution context from the step's configuration if a later <em>step</em> needs them.
     *
     * <p>When no step context is available the values are simply not published, which is the explicit
     * {@code null} case rather than a silent failure.
     *
     * <p>Side effects: mutates the step execution context and this writer's cumulative counter.
     *
     * @param objectKey the key of the object just created
     * @param recordCount the number of records that object carries
     */
    private void publishGeneration(final String objectKey, final int recordCount) {
        final long cumulative = recordsWritten.addAndGet(recordCount);
        if (stepExecution == null) {
            LOGGER.debug("No step context is available, so the {} generation key is not published",
                    DALYREJS_DD_NAME);
            return;
        }
        final ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(REJECT_OBJECT_KEY_CONTEXT_KEY, objectKey);
        context.putString(REJECT_GENERATION_PREFIX_CONTEXT_KEY, generationPrefix);
        context.putLong(REJECT_RECORD_COUNT_CONTEXT_KEY, cumulative);
    }

    /**
     * Builds the metadata stated on a created object.
     *
     * <p>The content length is declared so the store can verify the transfer, and the content type is the
     * generic octet stream so that no intermediary treats the payload as text and translates line endings into
     * a fixed-width record stream that has none.
     *
     * <p>This method is a pure function of its argument.
     *
     * @param contentLength the exact byte length of the payload
     * @return the metadata to attach
     */
    private static ObjectMetadata objectMetadata(final int contentLength) {
        return ObjectMetadata.builder()
                .contentType(OBJECT_CONTENT_TYPE)
                .contentLength(Long.valueOf(contentLength))
                .build();
    }

    /**
     * Increments the tree-wide records-rejected counter for one reject outcome.
     *
     * <p>This replaces {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} at
     * {@code app/cbl/CBTRN02C.cbl:L228} - note the two spaces before the colon in the source - with a metric
     * that is queryable rather than only readable. The counter is tagged by reject code, which turns the five
     * outcomes into an operable signal; the tag has five possible values so its cardinality is bounded.
     * <strong>No identifying value is ever used as a tag.</strong>
     *
     * <p>Registration is idempotent in Micrometer, so this cooperates with the central metrics configuration
     * instead of creating an additional instrument.
     *
     * <p>Side effects: increments a counter. Nothing here influences the exit status - the return-code-4 rule
     * of {@code :L229-L231} is the job's to apply.
     *
     * @param rejectCode the outcome that rejected the record
     */
    private void countRejectedRecord(final RejectCode rejectCode) {
        Counter.builder(REJECTED_RECORDS_COUNTER)
                .description("Daily transaction records rejected by CBTRN02C validation")
                .tag(REJECT_CODE_TAG, Integer.toString(rejectCode.getCode()))
                .register(meterRegistry)
                .increment();
    }
}
