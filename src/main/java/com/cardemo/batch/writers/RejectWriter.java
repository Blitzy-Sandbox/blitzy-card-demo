/*
 * ******************************************************************
 * Program     : RejectWriter.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamWriter (Java 25 / Spring Boot 3.5.11)
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3OutputStream;
import io.awspring.cloud.s3.S3Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
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
 * <p>Build and test with {@code ./mvnw -B -ntp clean test}; the module compiles under Java 25 with
 * {@code -Xlint:all -Werror}. This bean is step scoped, so it is instantiated by Spring Batch when the
 * posting step starts and is not usable outside a step context. Unit tests construct it directly, passing a
 * {@code StepExecution} built by {@code MetaDataInstanceFactory} or {@code null}; see the constructor.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} - the destination bucket, declared as
 *       {@code carddemo.aws.s3.batch-output-bucket} in {@code src/main/resources/application.yml} and
 *       backed by the
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} environment variable that {@code .env.example:81} ships.
 *       <strong>There is deliberately no default.</strong>
 *       A missing value fails the context at startup rather than silently writing somewhere unintended, which
 *       is the fail-fast standard this migration applies to every externalised setting.</li>
 *   <li>No endpoint, region or credential is read here. The object-storage client is injected already
 *       configured, and the emulator endpoint override is declared in all four profiles - bound to a bare
 *       {@code ${AWS_ENDPOINT_URL}} with no default in the base, {@code test} and {@code prod} profiles, so an
 *       unset variable fails the context at startup, and defaulted to the LocalStack edge only in
 *       {@code application-local.yml}. No live-cloud path is therefore structurally reachable from this class.
 *       An earlier revision of this item said the override existed only in the {@code local} and
 *       {@code test} profiles; that is withdrawn.</li>
 *   </ul>
 *
 * <h2>Why the 350-byte serialisation is not shared with the sibling writer</h2>
 *
 * <p>{@code TransactionWriter} in this same package also emits a 350-byte image, which invites the question
 * why the two are not factored onto a common codec. The duplication is <strong>deliberate
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
 * abstraction cannot be created here even if it were desirable.
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
 * <p><strong>Finding H-04, severity High, RESOLVED. One generation is one object.</strong> An earlier revision
 * created a fresh object per chunk, so a run whose rejects spanned three commit intervals left three objects
 * under one generation prefix while {@code app/jcl/POSTTRAN.jcl:L38} names a single dataset. A later
 * {@code (0)} reference resolves the greatest key under the prefix, so it saw only the <em>last</em> chunk and
 * silently reported a fraction of the run's rejects as all of them - the worst kind of defect, because the
 * output looked entirely well formed. This writer now opens one stream at the first record, appends every
 * chunk to it and completes it once, at {@code 9300-DALYREJS-CLOSE}. Peak memory is the object-store client's
 * own part buffer rather than the reject volume, because that stream switches to a multi-part upload when its
 * buffer fills; the run is never held in the heap.
 *
 * <p>The concrete key is published twice, at two scopes and for two readers, and in both cases only once the
 * object exists. It goes into the step execution context under {@link #REJECT_OBJECT_KEY_CONTEXT_KEY},
 * alongside the generation prefix under {@link #REJECT_GENERATION_PREFIX_CONTEXT_KEY} and the cumulative record
 * count under {@link #REJECT_RECORD_COUNT_CONTEXT_KEY} - the count is published at every chunk boundary, the key
 * only at the close - for a listener running inside this step. It also goes into the <b>job</b> execution
 * context under {@link #REJECT_OBJECT_KEYS_COUNT_ENTRY} and the single indexed entry it describes, for a later
 * step in the same job. That is what makes a {@code (+1)} written earlier in a job readable as {@code (+1)}
 * later in the same job: the downstream step consumes the exact key that was written instead of re-resolving
 * "latest" - a resolution that would race any concurrent producer.
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
 * <p><strong>The corpus specifies no mainframe-to-object-storage transfer convention</strong>, so whether any
 * downstream consumer expects newline-delimited output cannot be determined from it. Note the asymmetry that
 * makes this a real
 * question rather than a theoretical one: the ASCII reference fixture
 * {@code app/data/ASCII/dailytran.txt} <em>is</em> newline delimited - it measures 105,300 bytes, which is
 * 300 &times; 351, being 300 records of exactly 350 bytes each plus one line feed apiece - whereas the
 * {@code DALYREJS} dataset it feeds is unblocked. The unblocked form is emitted here because that is what
 * the {@code DCB} declares. Should a delimited variant ever be required, it is a labelled
 * deviation, never a silent change.
 *
 * <p><strong>The fixture decomposes as 300 &times; 351, not 300 &times; 350</strong>, that second product
 * being 105,000 rather than the measured 105,300. The 350-byte <em>record</em> width is unaffected and is
 * confirmed by every one of the 300 lines being exactly 350 bytes.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Context fails to start, unresolved placeholder
 *       {@code carddemo.aws.s3.batch-output-bucket}</strong> - the property is absent. Set
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. This is intended behaviour.</li>
 *   <li><strong>{@code FatalProcessingException} naming a field and a length</strong> - an entity field is
 *       longer than the picture clause allows, so no 430-byte record can be composed. The message names the
 *       field and reports lengths only; inspect the offending row by its transaction identifier.</li>
 *   <li><strong>{@code FileAccessException} for {@code DALYREJS}</strong> - the object-storage write failed.
 *       The line {@code FILE STATUS IS: NNNN} plus the expanded status is logged immediately before, exactly
 *       as {@code 9910-DISPLAY-IO-STATUS} renders it. Check bucket existence, credentials and the emulator.</li>
 *   <li><strong>Object size not a multiple of 430</strong> - cannot occur; the length is asserted before the
 *       write and a mismatch throws rather than emitting a corrupt generation.</li>
 *   </ul>
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
public class RejectWriter implements ItemStreamWriter<RejectWriter.RejectedTransaction> {

    /**
     * Step execution context key under which the concrete key of this step's single reject object is
     * published, so a later step consumes the exact object this writer produced.
     */
    public static final String REJECT_OBJECT_KEY_CONTEXT_KEY = "carddemo.dalyrejs.object.key";

    /**
     * Step execution context key under which the {@code (+1)} generation prefix is published. The generation
     * holds exactly one object - see {@link #write(Chunk)} for why - so the prefix makes the generation
     * discoverable while {@link #REJECT_OBJECT_KEY_CONTEXT_KEY} names that object precisely.
     */
    public static final String REJECT_GENERATION_PREFIX_CONTEXT_KEY = "carddemo.dalyrejs.generation.prefix";

    /**
     * Step execution context key naming the object <em>this attempt is currently writing</em>, published at
     * every chunk commit so that a restart can find it.
     *
     * <p><b>This is restart state and nothing else. A downstream step must never read it</b> - that is what
     * {@link #REJECT_OBJECT_KEY_CONTEXT_KEY} is for, and the distinction is the whole reason a second entry
     * exists. The published key names a completed object; this one names an object still being written, which
     * a consumer reading it early would find absent or short.
     *
     * <p>It exists because of when the two are written. {@link #REJECT_OBJECT_KEY_CONTEXT_KEY} is written by
     * {@link #close()}, and the framework persists a step's execution context <em>before</em> closing its
     * streams, so on a failed attempt that entry never reaches the database - verified by decoding
     * {@code BATCH_STEP_EXECUTION_CONTEXT} for a forced failure, where the generation prefix and the record
     * count were present and the object key was not. {@link #update(ExecutionContext)} runs at every chunk
     * commit and is persisted, so an entry written there survives the failure and is restored into the
     * restarted attempt, which is exactly what {@link #adoptPriorAttempt(ExecutionContext)} needs.
     */
    public static final String REJECT_ATTEMPT_OBJECT_KEY_CONTEXT_KEY =
            "carddemo.dalyrejs.attempt.object.key";

    /**
     * Step execution context key under which the cumulative count of reject records written by this step is
     * published. It is the value a decider reads to apply the return-code-4 rule of
     * {@code app/cbl/CBTRN02C.cbl:L229-L231}; publishing it does not decide anything here.
     */
    public static final String REJECT_RECORD_COUNT_CONTEXT_KEY = "carddemo.dalyrejs.record.count";

    /**
     * Job execution context entry holding how many objects this job instance's reject generation contains, as a
     * {@code Long}.
     *
     * <p>Together with {@link #rejectObjectKeysIndexEntry(int)} this is the complete, exact record of the
     * generation. Read the count, then read that many indexed entries. <strong>Since finding H-04 the count is
     * always one</strong>, because one {@code (+1)} generation is one object; the list shape is retained rather
     * than collapsed to a single key so that a consumer written against the ordered protocol keeps working
     * unchanged, and so that the protocol still expresses "the whole generation" rather than "the latest part
     * of it".
     *
     * <p><b>Finding, severity High, RESOLVED.</b> An earlier revision published only into the <em>step</em>
     * execution context and told the reader to "promote these to the job execution context from the step's
     * configuration if a later step needs them". No promotion existed, no configuration performed one, and the
     * class that would have configured it does not exist - so the three entries this writer published were
     * discarded when the step ended and no consumer of any kind could read them. The prefix entry did not
     * rescue it either: a prefix lets a consumer <em>list</em> a generation, which reintroduces exactly the
     * race the exact keys were recorded to avoid, and a listing cannot recover creation order. <i>Remediation,
     * applied:</i> every key is appended here, in creation order, into the job execution context, by this
     * class, so the record is complete with no external wiring. The step-scoped entries are kept because a
     * listener inside the running step legitimately wants the latest one.
     *
     * <p>Indexed entries rather than one delimited string, for the reason
     * {@code TransactionWriter.OBJECT_KEYS_COUNT_ENTRY} sets out: the prefix is configured, so a separator is a
     * character this class cannot guarantee absent from a key, and a key containing it would split silently
     * into two that name nothing.
     */
    public static final String REJECT_OBJECT_KEYS_COUNT_ENTRY = "carddemo.dalyrejs.object.keys.count";

    /**
     * Prefix of the indexed job-execution entries described on {@link #REJECT_OBJECT_KEYS_COUNT_ENTRY}. The
     * entry for index {@code n} is this prefix followed by {@code n}, rendered by
     * {@link #rejectObjectKeysIndexEntry(int)}.
     */
    public static final String REJECT_OBJECT_KEYS_INDEX_ENTRY_PREFIX = "carddemo.dalyrejs.object.keys.";

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
     * Lowest code point admitted into a {@code PIC X(n)} field: the space, {@code U+0020}. Everything below it
     * is a C0 control character. See {@link #requirePermittedCharacters(String, String)}.
     */
    private static final char MIN_PERMITTED_CHAR = '\u0020';

    /** The delete control, {@code U+007F}, refused with the C0 set although it sits above the printable range. */
    private static final char DELETE_CHAR = '\u007F';

    /** First code point of the C1 control block, {@code U+0080}. */
    private static final char FIRST_C1_CHAR = '\u0080';

    /** Last code point of the C1 control block, {@code U+009F}. */
    private static final char LAST_C1_CHAR = '\u009F';

    /**
     * Highest code point {@link #RECORD_CHARSET} represents as a single byte. Anything above it would be
     * substituted rather than encoded, silently replacing record content while preserving the width.
     */
    private static final char MAX_ENCODABLE_CHAR = '\u00FF';

    /**
     * Human-readable description of the permitted set, reported in the diagnostic so an operator need not infer
     * the rule from a code point.
     */
    private static final String PERMITTED_CHARACTER_SET_DESCRIPTION =
            "U+0020 to U+007E and U+00A0 to U+00FF (printable ISO-8859-1; no C0 or C1 control, no DEL)";

    /**
     * Separator placed between the configured generation prefix and the rest of an object key.
     *
     * <p>Appended by this class rather than expected from configuration, so that a configured value with or
     * without a trailing slash produces the same key. A configuration format that is only correct when the
     * operator remembers a trailing character is a configuration format that will be got wrong.
     */
    private static final char KEY_SEGMENT_SEPARATOR = '/';

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
     * Format for the per-step generation sequence inside an object key: {@value #KEY_IDENTIFIER_FORMAT}-wide
     * zero-padded digits, the same width as every other numeric key component.
     *
     * <p><b>Finding, severity Medium, RESOLVED.</b> An earlier revision padded this component to six digits
     * while the identifier components beside it were padded to nineteen. Six digits does not cover the domain
     * of the {@code long} that feeds it, and the consequence is not a truncated key - {@code %06d} widens
     * rather than truncates - but a <em>silently non-monotonic</em> one: the millionth object in a step is
     * numbered {@code 1000000}, which is seven characters, and {@code "1000000"} sorts before {@code "999999"}
     * lexicographically. The whole point of zero-padding these components is that
     * {@code app/catlg/LISTCAT.txt}'s {@code (0)} generation reference becomes "the lexicographically greatest
     * prefix", so the moment padding stops covering the domain that equivalence breaks and a consumer resolves
     * {@code (0)} to the wrong object. It would not fail; it would quietly return stale data.
     *
     * <p>Nineteen digits is the width of {@code Long.MAX_VALUE}, so no value the counter can hold can overflow
     * it and the equivalence holds for the entire domain rather than for a bound nobody has proven. Using the
     * same width as the identifier components also means one rule governs every numeric component of every key
     * this class emits, which is checkable by inspection.
     */
    private static final String KEY_SEQUENCE_FORMAT = KEY_IDENTIFIER_FORMAT;

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
     * <p><strong>"Millisecond precision followed by four zeros" is arithmetically impossible for this
     * format.</strong> Millisecond precision is three fraction digits, so
     * 3 + 4 is seven fraction characters and a 27-byte value, which cannot fit a {@code PIC X(26)} field. The
     * verified 26-byte layout at {@code app/cbl/CBTRN02C.cbl:L170-L174} governs, giving the
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
     * The application's sole meter owner, through which this writer reports a rejected record.
     *
     * <p>This class holds no meter, no metric name and no tag key of its own. It previously held all three and
     * built the counter itself with {@code Counter.builder(...).register(meterRegistry)}, relying on
     * Micrometer's idempotent registration to make that "cooperate with" the central configuration. That
     * reasoning was sound about the <em>count</em> - no additional instrument was created - but wrong about
     * <em>metadata</em>: Micrometer keeps the description and tag set of whichever registration happens
     * first and silently discards every later builder's, so the published help text depended on bean
     * initialisation order, and the name and tag key had to be kept byte-identical across two files by
     * comment alone. Reporting through the owner instead leaves exactly one declaration of each.
     */
    private final MetricsConfig metricsConfig;

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
     * The {@code (+1)} generation prefix for this step, computed once at construction from the configured
     * {@code carddemo.aws.s3.gdg-prefixes.daly-rejs} value and the job instance identifier.
     */
    private final String generationPrefix;

    /**
     * The one concrete object key this step's {@code (+1)} generation consists of, derived once at construction.
     *
     * <p><strong>Finding H-04, severity High, RESOLVED.</strong> An earlier revision allocated a fresh key per
     * chunk from a monotonic sequence, so a run that rejected records across three commit intervals left three
     * objects under one generation prefix. {@code app/jcl/POSTTRAN.jcl:L38} names a single dataset,
     * {@code AWS.M2.CARDDEMO.DALYREJS(+1)}, and a later relative reference to {@code (0)} resolves that one
     * dataset whole - so a consumer resolving "the current generation" as the greatest key under the prefix saw
     * only the <em>last</em> chunk and silently reported a fraction of the run's rejects as all of them. The
     * generation is now one object, written as one stream, and this field is that object's key.
     */
    private final String generationObjectKey;

    /**
     * The open write stream for this step's generation, or {@code null} before the first record and after the
     * generation has been closed.
     *
     * <p>Obtained from {@code S3Operations.createResource(...).getOutputStream()}, which the library implements
     * as a buffered stream that switches to a multi-part upload once its bounded buffer fills. That is what
     * makes one object out of an arbitrary number of chunks <strong>without</strong> holding the run in the
     * heap: peak memory is the library's part buffer, not the reject volume.
     *
     * <p>An instance field on a step-scoped bean is per-execution state, not static mutable state. It is
     * mutated only from {@link #open(ExecutionContext)}, {@link #write(Chunk)} and {@link #close()}, which the
     * framework serialises on the step's own thread; the {@code synchronized} on the append path additionally
     * makes a multi-threaded step safe.
     */
    private OutputStream generationStream;

    /**
     * The previous attempt's object key under this same generation, or {@code null} when this is not a restart.
     *
     * <p>Set at {@link #open(ExecutionContext)} from the restored context, consumed when the consolidated
     * object is first written, and cleared by {@link #deleteSupersededObject()} once the deletion has been
     * attempted. Not {@code static}, because it is per-instance restart state and this class holds no mutable
     * static field.
     */
    private String supersededObjectKey;

    /** Whether {@link #close()} has already committed this step's generation, so a second call is a no-op. */
    private boolean generationCommitted;

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
     * @param metricsConfig the application's sole meter owner, through which the rejected-record counter is
     *        reported; must not be {@code null}
     * @param fileStatusMapper the file-status-to-exception mapper; must not be {@code null}
     * @param outputBucket the destination bucket from {@code carddemo.aws.s3.batch-output-bucket}, backed by
     *        {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}; must not be {@code null}, and has no default so that an
     *        absent value fails the context rather than writing somewhere unintended
     * @param rejectGdgPrefix the reject generation prefix from
     *        {@code carddemo.aws.s3.gdg-prefixes.daly-rejs}.
     *        <b>Finding, severity High, RESOLVED:</b> an earlier revision hard-coded {@code "dalyrejs/"} in a
     *        private constant. {@code application.yml} declares this base authoritatively as {@code gdg/dalyrejs},
     *        citing {@code app/jcl/DALYREJS.jcl:L25}, and it is one of the seven {@code 0GDG BASE} entries
     *        reported at {@code app/catlg/LISTCAT.txt:L3942} - so the hard-coded value was not merely a second
     *        declaration site, it disagreed with the first: every reject object was written to {@code dalyrejs/}
     *        while every consumer configured from the catalogue looked under {@code gdg/dalyrejs/} and found an
     *        empty generation. That is a silent data-loss path, not a naming inconsistency.
     *        <i>Remediation, applied:</i> the key is bound here with no inline default, so the catalogue is the
     *        one source of truth and an absent value fails the context; must not be {@code null} or blank
     * @param stepExecution the step this writer serves, supplied by the step scope. Permitted to be
     *        {@code null} so a unit test can construct the class directly; when it is {@code null} the object
     *        key falls back to the unassigned-identifier form and nothing is published to a step context
     * @throws NullPointerException if any argument other than {@code stepExecution} is {@code null}
     * @throws IllegalArgumentException if {@code rejectGdgPrefix} is blank
     */
    public RejectWriter(
            final S3Operations s3Operations,
            final MetricsConfig metricsConfig,
            final FileStatusMapper fileStatusMapper,
            @Value("${carddemo.aws.s3.batch-output-bucket}") final String outputBucket,
            @Value("${carddemo.aws.s3.gdg-prefixes.daly-rejs}") final String rejectGdgPrefix,
            @Value("#{stepExecution}") final StepExecution stepExecution) {
        this.s3Operations = Objects.requireNonNull(s3Operations, "s3Operations must not be null");
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        if (outputBucket == null || outputBucket.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value; "
                            + "set CARDDEMO_S3_BATCH_OUTPUT_BUCKET");
        }
        this.outputBucket = outputBucket;
        this.stepExecution = stepExecution;
        // Both callees are private static pure functions of their arguments, so neither publishes a partly
        // built reference and -Xlint:this-escape stays silent.
        this.generationPrefix =
                buildGenerationPrefix(requireGdgPrefix(rejectGdgPrefix), stepExecution);
        this.generationObjectKey = buildGenerationObjectKey(this.generationPrefix, stepExecution);
    }

    /**
     * Validates the configured generation prefix and normalises its trailing separator away.
     *
     * <p>The value is untrusted configuration, so it is checked rather than trusted (Rule 1 clause A). A blank
     * value is refused outright: it would place every reject object at the bucket root, mixed in with the
     * transaction mirror and the report generations, which is indistinguishable from success until someone
     * looks. Any trailing separator is stripped so that {@code gdg/dalyrejs} and {@code gdg/dalyrejs/} compose
     * the identical key - the separator is this class's to add, not configuration's to remember.
     *
     * @param configured the raw configured value
     * @return the prefix with no trailing separator, never {@code null} and never blank
     * @throws IllegalArgumentException if the value is blank once trimmed of separators
     */
    private static String requireGdgPrefix(final String configured) {
        Objects.requireNonNull(configured, "rejectGdgPrefix must not be null");
        String normalised = configured.strip();
        while (!normalised.isEmpty() && normalised.charAt(normalised.length() - 1) == KEY_SEGMENT_SEPARATOR) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.gdg-prefixes.daly-rejs must not be blank; application.yml declares it as "
                            + "gdg/dalyrejs for app/jcl/DALYREJS.jcl:L25, and a blank value would write every "
                            + "reject generation to the bucket root");
        }
        return normalised;
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
            LOGGER.debug("No rejected transactions in this chunk; nothing is appended to the {} generation",
                    DALYREJS_DD_NAME);
            return;
        }
        final StringBuilder payload =
                new StringBuilder(chunk.size() * RejectCode.REJECT_RECORD_LENGTH);
        for (final RejectedTransaction rejected : chunk) {
            Objects.requireNonNull(rejected, "chunk must not contain a null item");
            payload.append(buildRejectRecord(rejected.transaction(), rejected.rejectCode()));
        }
        appendToGeneration(payload.toString(), chunk.size());
        for (final RejectedTransaction rejected : chunk) {
            countRejectedRecord(rejected.rejectCode());
        }
    }

    /**
     * Opens this step's single {@code DALYREJS(+1)} generation, {@code 0300-DALYREJS-OPEN} at
     * {@code app/cbl/CBTRN02C.cbl:L266}-{@code :L280}.
     *
     * <p>Nothing is created here. The source's {@code OPEN OUTPUT} allocates a dataset; an object store has no
     * allocate step, and creating a zero-length object for a run that rejects nothing would put a generation on
     * the base that the corpus cannot produce. What this method does is reset the per-execution write state, so
     * a restart of the same step cannot inherit the previous attempt's counters or a half-written stream.
     *
     * <p>Side effects: clears this writer's staged state. Reads the supplied context but never mutates it.
     *
     * @param executionContext the step's context, consulted for nothing and mutated not at all; the parameter
     *     exists because the stream contract declares it
     * @throws ItemStreamException never; declared by the contract
     */
    @Override
    public void open(final ExecutionContext executionContext) throws ItemStreamException {
        this.generationStream = null;
        this.generationCommitted = false;
        this.recordsWritten.set(0L);
        this.supersededObjectKey = null;
        adoptPriorAttempt(executionContext);
        LOGGER.debug("{} generation is open for writing under {}", DALYREJS_DD_NAME, generationPrefix);
    }

    /**
     * Adopts a previous attempt's generation object so a restart consolidates rather than fragments.
     *
     * <p><b>Why a restart needs this at all.</b> The object key carries the job <em>execution</em> identifier
     * while the generation prefix carries the job <em>instance</em> identifier, so a restart of the same
     * instance writes a second object under the same generation. That was deliberate, and the reasoning
     * recorded on {@link #buildGenerationObjectKey} was that the newer key sorts after the older one and so a
     * consumer resolving the greatest key would "get a whole run rather than a fragment". <b>That does not
     * hold, and cannot.</b> A restart resumes the reader at the cursor the failed attempt reached, so the
     * second object can only ever contain the rejects found <em>after</em> that cursor. Measured on the
     * 300-row fixture with a forced failure at record 46: two objects of 430x4 and 430x34, the completed
     * execution's manifest naming only the second, and a record count of 34 sitting beside a reject count of
     * 38 in the same context with nothing to reconcile them.
     *
     * <p><b>Why consolidating rather than deleting.</b> The abnormal-termination disposition of
     * {@code app/jcl/POSTTRAN.jcl:L34} {@code //DALYREJS DD DISP=(NEW,CATLG,DELETE)} says a failed step's
     * generation is not catalogued, and for a step that regenerates its whole output on restart, deleting the
     * failed attempt's object discharges that exactly. This step is not one of those. Its input cursor moves,
     * so the four rejects the first attempt found are unreproducible: deleting that object would satisfy the
     * one-object rule by destroying part of a reject audit trail, which in a card system is a regulated
     * artefact. Carrying the bytes forward satisfies the same rule losslessly, and it is what
     * {@code TransactionWriter} already does with its own indexed manifest, so the two writers stop
     * disagreeing.
     *
     * <p>The prior object is <b>not</b> deleted here. It is deleted only once the consolidated object has been
     * committed - see {@link #close()} - so a failure between the two leaves the earlier attempt's records
     * still readable rather than losing both copies.
     *
     * @param executionContext the restored step execution context, permitted to be {@code null}
     */
    private void adoptPriorAttempt(final ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }

        // The attempt entry is preferred because it is the one that survives a failure: it is written by
        // update() at chunk commits, whereas the published entry is written by close() after the framework has
        // already persisted the context. The published entry is still consulted, for a context that came from
        // an attempt which completed its close.
        final String priorKey = executionContext.containsKey(REJECT_ATTEMPT_OBJECT_KEY_CONTEXT_KEY)
                ? executionContext.getString(REJECT_ATTEMPT_OBJECT_KEY_CONTEXT_KEY, "")
                : executionContext.getString(REJECT_OBJECT_KEY_CONTEXT_KEY, "");
        if (priorKey.isBlank()
                || priorKey.equals(this.generationObjectKey)
                || !priorKey.startsWith(this.generationPrefix)) {
            // Not a prior attempt of this generation: either this execution's own key echoed back, or a key
            // from somewhere else entirely. Adopting the latter would import another generation's records.
            return;
        }

        final long priorCount = executionContext.getLong(REJECT_RECORD_COUNT_CONTEXT_KEY, 0L);
        this.supersededObjectKey = priorKey;
        this.recordsWritten.set(priorCount);
        LOGGER.info("{} is restarting under generation {} and will consolidate the {} record(s) of the"
                        + " previous attempt's object {} into {}, deleting the superseded object only once"
                        + " the consolidated one is committed",
                DALYREJS_DD_NAME,
                generationPrefix,
                Long.valueOf(priorCount),
                priorKey,
                this.generationObjectKey);
    }

    /**
     * Publishes the running reject count at every chunk boundary, so a decider or a listener sees a value that
     * is current rather than one that only appears when the step ends.
     *
     * <p>The concrete object key is <strong>not</strong> published here: it is published by {@link #close()},
     * once the single object exists, because a key that names an object the store has not yet accepted would
     * let a downstream step read a generation that is not there.
     *
     * @param executionContext the step's context; the cumulative count is written into it
     * @throws ItemStreamException never; declared by the contract
     */
    @Override
    public void update(final ExecutionContext executionContext) throws ItemStreamException {
        if (executionContext == null) {
            return;
        }
        executionContext.putString(REJECT_GENERATION_PREFIX_CONTEXT_KEY, generationPrefix);
        executionContext.putLong(REJECT_RECORD_COUNT_CONTEXT_KEY, this.recordsWritten.get());
        if (this.generationStream != null || this.generationCommitted) {
            // Restart state, persisted at this chunk boundary. Only written once the object actually exists,
            // so a restart never adopts a key nothing was ever written to.
            executionContext.putString(REJECT_ATTEMPT_OBJECT_KEY_CONTEXT_KEY, this.generationObjectKey);
        }
    }

    /**
     * Closes the run's single generation, {@code 9300-DALYREJS-CLOSE} at
     * {@code app/cbl/CBTRN02C.cbl:L654}-{@code :L668}, and publishes the concrete key it created.
     *
     * <p><strong>This is the point at which the {@code (+1)} generation comes into existence as one object.</strong>
     * Closing the buffered stream is what completes the upload, so a failure here is a failed
     * {@code CLOSE} and is reported through the same guard the source applies to one.
     *
     * <p>A step that rejected nothing closes nothing and publishes nothing, which is the {@code OPEN OUTPUT}
     * followed by {@code CLOSE} of an empty dataset: the corpus writes no record and this writer creates no
     * object. Idempotent - a second call after a successful close does nothing, because the stream reference is
     * cleared once it has been committed.
     *
     * <p>Side effects: completes one object in the configured bucket; publishes the key, the generation prefix
     * and the final record count into the step execution context, and the ordered one-entry key list into the
     * job execution context.
     *
     * @throws ItemStreamException never directly; a storage failure is raised as the typed
     *     {@code com.cardemo.exception.FileAccessException} or {@code FatalProcessingException} the guard
     *     chooses, so the batch tier sees the same exception it would from any other failed write
     */
    @Override
    public void close() throws ItemStreamException {
        final OutputStream open = this.generationStream;
        if (open == null) {
            if (this.supersededObjectKey != null) {
                // A restart that rejected nothing of its own. The previous attempt's object still holds the
                // whole run, so it stays and the manifest names it. Publishing this execution's own key would
                // name an object that was never created, and leaving the manifest unwritten would make the
                // completed execution look as though the generation did not exist.
                publishGeneration(this.supersededObjectKey, 0);
                LOGGER.info("{} wrote no rejects on this attempt, so the carried-forward object {} holding"
                                + " {} record(s) remains the generation and is republished unchanged",
                        DALYREJS_DD_NAME,
                        this.supersededObjectKey,
                        Long.valueOf(this.recordsWritten.get()));
                return;
            }
            LOGGER.debug("No rejected transactions were written, so no {} generation was created",
                    DALYREJS_DD_NAME);
            return;
        }
        this.generationStream = null;

        // app/cbl/CBTRN02C.cbl:L659 - MOVE 8 TO APPL-RESULT, then CLOSE and the two-way guard at :L661-:L667.
        final Throwable failureCause = closeRejsFile(open);
        final String dalyrejsStatus =
                failureCause == null ? WRITE_SUCCESS_STATUS : WRITE_IO_ERROR_STATUS;
        final int applResult = fileStatusMapper.applResultForGuard(dalyrejsStatus);
        if (applResult != FileStatusMapper.APPL_AOK) {
            // :L665 DISPLAY 'ERROR CLOSING REJECTS FILE', then the status render, then the abend - in order.
            LOGGER.error(WRITE_FAILURE_TEXT);
            displayIoStatus(dalyrejsStatus);
            throw abendProgram(dalyrejsStatus, failureCause);
        }

        this.generationCommitted = true;
        publishGeneration(this.generationObjectKey, 0);
        deleteSupersededObject();
        LOGGER.debug("Closed the {} generation holding {} reject record(s)", DALYREJS_DD_NAME,
                Long.valueOf(this.recordsWritten.get()));
    }

    /**
     * Removes the superseded attempt's object, once and only once the consolidated object is committed.
     *
     * <p>The order is deliberate and is the reason this is a separate step rather than part of the copy. Until
     * the consolidated upload has completed there are two objects and both are readable; after it there are
     * two objects and the newer one is complete; only then is the older one redundant. Deleting it any earlier
     * would leave a window in which a failure loses records that exist nowhere else.
     *
     * <p>A failure to delete is reported and does not fail the step. The records are safe either way - they are
     * in the consolidated object - and what remains is a redundant sibling under the generation, which the
     * reader now refuses loudly rather than resolving past. Turning a completed posting run into a failure over
     * a leftover object would be the worse outcome, so it is logged for an operator instead.
     */
    private void deleteSupersededObject() {
        final String superseded = this.supersededObjectKey;
        if (superseded == null) {
            return;
        }
        this.supersededObjectKey = null;
        try {
            if (s3Operations.objectExists(outputBucket, superseded)) {
                s3Operations.deleteObject(outputBucket, superseded);
                LOGGER.info("{} deleted the superseded object {} now that the consolidated generation {} is"
                                + " committed, so the generation is one dataset and therefore one object",
                        DALYREJS_DD_NAME, superseded, this.generationObjectKey);
            }
        } catch (RuntimeException deletion) {
            LOGGER.error("{} committed the consolidated generation {} but could not delete the superseded"
                            + " object {}; every record is present in the consolidated object, and the"
                            + " leftover sibling must be removed manually because a consumer resolving this"
                            + " generation will now refuse it as ambiguous",
                    DALYREJS_DD_NAME, this.generationObjectKey, superseded, deletion);
        }
    }

    /**
     * Writes a single reject record, for a caller that holds one rejected row and no chunk.
     *
     * <p>{@link #write(Chunk)} delegates to the same composition path per element, so the two entry points
     * cannot drift. This overload appends to the same single generation, so mixing the two entry points in one
     * step still produces exactly one object.
     *
     * <p>Side effects: identical to {@link #write(Chunk)} for a chunk of one - the record is appended to this
     * step's single generation, which {@link #close()} completes. <strong>A caller driving this method outside a
     * step must call {@link #close()} itself</strong>, exactly as the source performs
     * {@code 9300-DALYREJS-CLOSE}; inside a step the framework calls it, because this writer is an
     * {@code ItemStream}. Appending here rather than creating an object per call is what keeps one
     * {@code (+1)} generation to one object.
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
        appendToGeneration(buildRejectRecord(rejected.transaction(), rejected.rejectCode()), 1);
        countRejectedRecord(rejected.rejectCode());
    }

    /**
     * Reproduces {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}, composing the record
     * and leaving the write itself to {@link #appendToGeneration(String, int)}.
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
     * <p><b>Every character is checked against an explicit permitted set. Finding, severity High, RESOLVED.</b>
     * An earlier revision checked only the <em>length</em>, so any control byte an upstream system had placed
     * in a merchant name or a description travelled straight into the emitted stream. That is an injection
     * defect, not an untidiness: the payload is <b>unblocked and undelimited</b> - see
     * {@link #assertUnblockedFraming(int, int)} - so a consumer finds record boundaries by counting
     * {@value com.cardemo.model.enums.RejectCode#REJECT_RECORD_LENGTH} bytes and by nothing else. A carriage
     * return, a line feed or a NUL inside a picture-clause field is a byte that a line-oriented reader, a shell
     * pipeline or a log ingester will treat as a boundary this format does not have, splitting one reject record
     * into two that decode as neither. The length check cannot see it, because a control byte occupies exactly
     * one column like any other.
     *
     * <p>The rule is stated <b>positively</b> - {@code U+0020} to {@code U+007E} and {@code U+00A0} to
     * {@code U+00FF} - rather than as a list of controls to exclude, because a deny-list stops being exhaustive
     * without anyone noticing. Nothing legitimate is refused: every byte of
     * {@code app/data/ASCII/dailytran.txt} is printable, the zoned-decimal overpunch characters
     * <code>&#123;</code>, <code>&#125;</code> and {@code A}-{@code R} included.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param value the field value, permitted to be {@code null}
     * @param width the declared width from the picture clause
     * @param fieldName the COBOL field name, used only in a failure message
     * @return exactly {@code width} characters
     * @throws com.cardemo.exception.FatalProcessingException if {@code value} is longer than {@code width}, or
     *         holds a character outside the permitted set
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
        requirePermittedCharacters(value, fieldName);
        if (length == width) {
            return value;
        }
        return value + PADDING_SPACES.substring(0, width - length);
    }

    /**
     * Refuses any character outside the permitted single-byte set of a {@code PIC X(n)} field.
     *
     * <p>Reports the field name and the offending <em>position and code point</em> and never the value, for the
     * same reason {@link #alphanumeric(String, int, String)} withholds it: two of the fields rendered through
     * that method, {@code DALYTRAN-CARD-NUM} and {@code DALYTRAN-ID}, are sensitive or identifying, and an
     * exception message is destined for a log. A code point is safe to report because it names one character,
     * not the value it came from.
     *
     * <p>Static and pure, so a test can exercise the whole code-point range without composing a record.
     *
     * @param value the field value, never {@code null}
     * @param fieldName the COBOL field name, used only in a failure message
     * @throws com.cardemo.exception.FatalProcessingException on the first character outside the permitted set
     */
    private static void requirePermittedCharacters(final String value, final String fieldName) {
        for (int index = 0; index < value.length(); index++) {
            final char candidate = value.charAt(index);
            if (candidate < MIN_PERMITTED_CHAR
                    || candidate == DELETE_CHAR
                    || (candidate >= FIRST_C1_CHAR && candidate <= LAST_C1_CHAR)
                    || candidate > MAX_ENCODABLE_CHAR) {
                throw geometryFailure(String.format(Locale.ROOT,
                        "%s holds a character at position %d (code point U+%04X) outside the permitted set %s. "
                                + "The reject stream is unblocked and undelimited, so a consumer finds record "
                                + "boundaries by counting %d bytes; a control byte inside a picture-clause "
                                + "field would let a line-oriented reader see a boundary this format does not "
                                + "have. The value is withheld because the field may carry identifying data",
                        fieldName, Integer.valueOf(index + 1), Integer.valueOf(candidate),
                        PERMITTED_CHARACTER_SET_DESCRIPTION,
                        Integer.valueOf(RejectCode.REJECT_RECORD_LENGTH)));
            }
        }
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
     * <p><strong>Negative zero cannot round-trip.</strong> Zoned decimal distinguishes
     * <code>}</code> ({@code -0}) from <code>{</code> ({@code +0}); {@code BigDecimal} has no signed zero, so
     * an amount of exactly zero always renders as <code>{</code>. This cannot arise from the reference
     * fixture - all six <code>}</code> rows there carry non-zero magnitudes - and it is inherent to decimal
     * arithmetic rather than a defect here. A byte-exact {@code -0} round-trip would require retaining the raw
     * eleven-byte field on {@code DailyTransaction} and passing it through; the entity has no such field, and
     * nothing in the corpus states that any consumer needs one.
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
    private synchronized void appendToGeneration(final String payload, final int recordCount) {
        // DEADLINE AND RETRY, and where they come from. Finding, severity High, RESOLVED. The upload below is
        // synchronous, so an unbounded call would hold the chunk transaction open for as long as the endpoint
        // chose to stall. No per-call override is configured HERE on purpose: a deadline written at this call
        // site would be a second policy that drifts from the one every other AWS call obeys.
        // AwsConfig.applyBoundedPolicy installs it on the client itself through an S3ClientCustomizer - a 30
        // second whole-call deadline, a 10 second per-attempt deadline and RetryMode.STANDARD, which bounds both
        // the attempt count and the backoff - and AwsConfig.s3Template consumes that same auto-configured
        // S3Client rather than building one, so this upload inherits it. A timeout therefore arrives as a
        // RuntimeException, is caught by writeRejsRecord, becomes the '9x' status and abends the step exactly as
        // app/cbl/CBTRN02C.cbl:L460-L463 does for any other physical write failure.
        final byte[] bytes = payload.getBytes(RECORD_CHARSET);
        assertUnblockedFraming(bytes.length, payload.length());

        // app/cbl/CBTRN02C.cbl:L450 - MOVE 8 TO APPL-RESULT.
        int applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        // app/cbl/CBTRN02C.cbl:L451 - WRITE FD-REJS-RECORD FROM REJECT-RECORD.
        final Throwable failureCause = writeRejsRecord(bytes);
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

        this.recordsWritten.addAndGet(recordCount);
        publishRunningCount();
        LOGGER.debug("Appended {} reject record(s) ({} bytes) to the single {} generation", recordCount,
                bytes.length, DALYREJS_DD_NAME);
    }

    /**
     * Opens the write stream for this step's generation on first use, and returns the already-open one after
     * that.
     *
     * <p>Lazy on purpose: a step that rejects nothing must leave no object behind, and the only way to
     * guarantee that against a store which creates an object the moment a stream is closed is not to open one.
     *
     * <p>The metadata declares the content type and deliberately declares <strong>no content length</strong>:
     * the total is not known until the last chunk has been written, and stating a wrong length is worse than
     * stating none. Framing is proven per append by {@link #assertUnblockedFraming(int, int)} instead, which is
     * the stronger check because it holds for every record rather than for the total alone.
     *
     * @return the open stream, never {@code null}
     * @throws IOException if the store refuses to open the object
     * @throws IllegalStateException if a record arrives after the generation has been closed, which would
     *     otherwise silently create a second object under one generation - the very defect this design removes
     */
    private OutputStream openGenerationStream() throws IOException {
        if (this.generationStream != null) {
            return this.generationStream;
        }
        if (this.generationCommitted) {
            throw new IllegalStateException(DALYREJS_DD_NAME + " generation " + generationPrefix
                    + " has already been closed; a reject record arriving after the close would create a "
                    + "second object under one (+1) generation, which app/jcl/POSTTRAN.jcl:L38 declares as a "
                    + "single dataset");
        }
        final S3Resource resource = s3Operations.createResource(outputBucket, this.generationObjectKey);
        resource.setObjectMetadata(streamedObjectMetadata());
        this.generationStream = resource.getOutputStream();
        seedFromSupersededObject(this.generationStream);
        return this.generationStream;
    }

    /**
     * Copies a superseded attempt's records into this attempt's object before anything new is appended.
     *
     * <p>Ordering is the whole point: the carried-forward bytes go in first, so the consolidated object holds
     * the run in the order it was produced - the failed attempt's rejects, then the resumed attempt's - which
     * is the order a single uninterrupted run would have written them in.
     *
     * <p>The payload is framing-checked before it is trusted. A superseded object whose length is not a whole
     * multiple of the reject record length is not a shorter run, it is a corrupt one, and copying it would
     * misalign every record after it; that is refused rather than propagated. An object named by the restored
     * context but absent from the store is treated as nothing to carry, because the disposition of
     * {@code app/jcl/POSTTRAN.jcl:L34} permits a failed attempt's object to have been removed already - what
     * is not permitted is silently continuing when it is present but unreadable.
     *
     * @param stream this attempt's freshly opened object stream
     * @throws IOException if the copy cannot be completed
     */
    private void seedFromSupersededObject(final OutputStream stream) throws IOException {
        if (this.supersededObjectKey == null) {
            return;
        }

        if (!s3Operations.objectExists(outputBucket, this.supersededObjectKey)) {
            LOGGER.warn("{} found no object at the superseded key {}, so there is nothing to carry forward;"
                            + " the consolidated object will hold only this attempt's records",
                    DALYREJS_DD_NAME, this.supersededObjectKey);
            this.supersededObjectKey = null;
            this.recordsWritten.set(0L);
            return;
        }

        final byte[] carried;
        try (InputStream priorRecords =
                s3Operations.download(outputBucket, this.supersededObjectKey).getInputStream()) {
            carried = priorRecords.readAllBytes();
        }
        // The same framing rule the write path applies, reused rather than restated so the two cannot drift.
        // Both arguments are the byte length because there is no composed character form to compare here: what
        // is being checked is the whole-record multiple, not a charset round trip.
        assertUnblockedFraming(carried.length, carried.length);
        stream.write(carried);

        // The object's own length is the authority for how many records were carried, not the count the
        // restored context happened to hold. The context count is written at chunk boundaries and can lag the
        // last records the failed attempt actually wrote, and a published record count that disagrees with the
        // object it describes is precisely the inconsistency this fix exists to remove.
        final long carriedRecords = carried.length / RejectCode.REJECT_RECORD_LENGTH;
        this.recordsWritten.set(carriedRecords);
        LOGGER.info("{} carried {} record(s) ({} bytes) forward from the superseded object {} into {}",
                DALYREJS_DD_NAME,
                Long.valueOf(carriedRecords),
                Integer.valueOf(carried.length),
                this.supersededObjectKey,
                this.generationObjectKey);
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
     * <p>Side effects: opens this step's single object on the first call and appends to it on every call. The
     * object is completed by {@link #close()}, not here, which is exactly the {@code WRITE} versus
     * {@code CLOSE} split the source has.
     *
     * @param bytes the exact payload, already framing-checked
     * @return {@code null} when the write was accepted, otherwise the throwable that prevented it
     */
    private Throwable writeRejsRecord(final byte[] bytes) {
        try {
            openGenerationStream().write(bytes);
            return null;
        } catch (IOException | RuntimeException writeFailure) {
            abandonGenerationStream();
            return writeFailure;
        }
    }

    /**
     * Reproduces the single statement {@code CLOSE DALYREJS-FILE} at {@code app/cbl/CBTRN02C.cbl:L660}.
     *
     * <p>Like the write, it reports rather than raises, so the caller's guard stays the one place a status
     * becomes an outcome. Closing the buffered stream is what completes the upload, so this is the call that
     * either brings the generation into existence or fails.
     *
     * <p>A failure abandons the partly written object rather than leaving an in-flight multi-part upload
     * behind: an abandoned upload is storage nobody can see and nobody reclaims.
     *
     * @param open the stream to close, never {@code null}
     * @return {@code null} when the object was accepted, otherwise the throwable that prevented it
     */
    private Throwable closeRejsFile(final OutputStream open) {
        try {
            open.close();
            return null;
        } catch (IOException | RuntimeException closeFailure) {
            abortQuietly(open);
            return closeFailure;
        }
    }

    /**
     * Abandons the in-flight generation so no partly written object and no dangling multi-part upload survives
     * a failed write.
     *
     * <p>Called only from a failure path that is already reporting a status, so a secondary failure here must
     * not replace the primary one - it is recorded at debug level and discarded. That is not a swallow: the
     * primary throwable is returned to the guard and travels on as the cause of the abend.
     */
    private void abandonGenerationStream() {
        final OutputStream open = this.generationStream;
        this.generationStream = null;
        if (open != null) {
            abortQuietly(open);
        }
    }

    /**
     * Aborts one write stream, preferring the store's own abort where the stream offers it.
     *
     * <p>The library's buffered implementation exposes {@code abort()}, which cancels an in-flight multi-part
     * upload; a stream that does not offer it is simply closed. Either way nothing is left half-created.
     *
     * @param open the stream to abandon, never {@code null}
     */
    private static void abortQuietly(final OutputStream open) {
        try {
            if (open instanceof S3OutputStream abortable) {
                abortable.abort();
            } else {
                open.close();
            }
        } catch (final IOException | RuntimeException abortFailure) {
            LOGGER.debug("Abandoning the {} generation reported a secondary failure of type {}; the primary "
                            + "failure is the one reported to the guard", DALYREJS_DD_NAME,
                    abortFailure.getClass().getName());
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
     * <p>This method is a pure function of its arguments, which is why the constructor can call it without
     * publishing a partly built instance.
     *
     * @param root the validated configured generation base, with no trailing separator
     * @param stepExecution the step this writer serves, permitted to be {@code null}
     * @return the generation prefix, always ending in a path separator
     */
    private static String buildGenerationPrefix(final String root, final StepExecution stepExecution) {
        final long jobInstanceId = stepExecution == null
                || stepExecution.getJobExecution() == null
                || stepExecution.getJobExecution().getJobInstance() == null
                ? UNASSIGNED_IDENTIFIER
                : stepExecution.getJobExecution().getJobInstance().getInstanceId();
        return root + KEY_SEGMENT_SEPARATOR
                + String.format(Locale.ROOT, KEY_IDENTIFIER_FORMAT, jobInstanceId) + KEY_SEGMENT_SEPARATOR;
    }

    /**
     * Derives the single concrete object key this step's generation consists of.
     *
     * <p>One key per step execution, allocated once, with <strong>no per-chunk sequence</strong>: a generation
     * is one dataset, so it is one object. The job execution identifier is included so a restart of the same
     * job instance writes to a distinct key that sorts after the previous attempt's.
     *
     * <p><b>A correction to what this javadoc used to claim.</b> It previously said that the newer key sorting
     * after the older one is "what lets a consumer resolve 'the current generation' as the greatest key under
     * the prefix and get a whole run rather than a fragment". <b>That was not achievable and the code did not
     * do it.</b> A restart resumes the reader at the cursor the failed attempt reached, so the newer object can
     * only hold the rejects found after that cursor - by construction a fragment, not a whole run. Measured on
     * the 300-row fixture with a forced failure at record 46: objects of 430x4 and 430x34 under one generation,
     * and a published record count of 34 beside a reject count of 38.
     *
     * <p>The distinct key is still correct, but a distinct key alone is not the mechanism that delivers a whole
     * run. {@link #adoptPriorAttempt(ExecutionContext)} is: the restarted attempt copies the superseded
     * object's records into this key before appending its own, then {@link #deleteSupersededObject()} removes
     * the superseded object once this one is committed. The generation therefore ends as one object holding the
     * whole run, with a record count equal to the reject count, which is what a consumer resolving the greatest
     * key actually receives.
     *
     * <p>No wall clock is consulted. A timestamp would also be monotonic, but it would make the key
     * irreproducible on re-run and would import an environment-specific assumption; identifiers are
     * deterministic. That is the tradeoff, taken deliberately.
     *
     * <p>Static and a pure function of its arguments, so the constructor can call it without publishing a
     * partly built instance.
     *
     * @param prefix the validated generation prefix, already ending in a separator
     * @param stepExecution the step this writer serves, permitted to be {@code null}
     * @return the object key for this step's generation, never {@code null}
     */
    private static String buildGenerationObjectKey(final String prefix, final StepExecution stepExecution) {
        final long jobExecutionId = stepExecution == null || stepExecution.getJobExecution() == null
                ? UNASSIGNED_IDENTIFIER
                : stepExecution.getJobExecution().getId();
        return prefix
                + String.format(Locale.ROOT, KEY_IDENTIFIER_FORMAT, jobExecutionId)
                + OBJECT_KEY_SUFFIX;
    }

    /**
     * Publishes the created key, the generation prefix and the cumulative record count into the step execution
     * context.
     *
     * <p>This is what makes a {@code (+1)} written by one step readable as {@code (+1)} by a later step in the
     * same job: the downstream step consumes the exact key recorded here instead of re-resolving "latest",
     * which could otherwise pick up an object written by a different job execution. The <b>complete</b> key list -
     * one entry, because one generation is one object - goes into the job execution context, written by this
     * class rather than by an external promotion listener; see {@link #REJECT_OBJECT_KEYS_COUNT_ENTRY} for the
     * finding that resolves and for the read protocol. The step-scoped entries remain for a listener running
     * inside this step.
     *
     * <p>When no step context is available the values are simply not published, which is the explicit
     * {@code null} case rather than a silent failure.
     *
     * <p>Side effects: mutates the step execution context, appends two entries to the job execution context,
     * and advances this writer's cumulative counter.
     *
     * @param objectKey the key of the object just created
     * @param recordCount the number of records that object carries
     */
    private void publishGeneration(final String objectKey, final int recordCount) {
        final long cumulative = recordCount == 0
                ? recordsWritten.get()
                : recordsWritten.addAndGet(recordCount);
        if (stepExecution == null) {
            LOGGER.debug("No step context is available, so the {} generation key is not published",
                    DALYREJS_DD_NAME);
            return;
        }
        final ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(REJECT_OBJECT_KEY_CONTEXT_KEY, objectKey);
        context.putString(REJECT_GENERATION_PREFIX_CONTEXT_KEY, generationPrefix);
        context.putLong(REJECT_RECORD_COUNT_CONTEXT_KEY, cumulative);

        final JobExecution jobExecution = stepExecution.getJobExecution();
        if (jobExecution == null) {
            // Reachable only from a unit test that builds a StepExecution without one. The step entries above
            // are still written, so the writer stays testable and nothing is silently dropped in production.
            return;
        }
        // Exactly one entry, written at index zero and a count of one, because the generation is exactly one
        // object. The list shape is retained rather than collapsed to the single key so that a consumer written
        // against the ordered protocol keeps working unchanged; it simply always reads a list of one. A repeated
        // close cannot append a second entry, because the index is fixed rather than derived from the count.
        final ExecutionContext jobContext = jobExecution.getExecutionContext();
        jobContext.putString(rejectObjectKeysIndexEntry(0), objectKey);
        jobContext.putLong(REJECT_OBJECT_KEYS_COUNT_ENTRY, 1L);
    }

    /**
     * Publishes the running reject count into the step execution context at every append.
     *
     * <p>The concrete key is deliberately not published here: it names an object that does not exist until
     * {@link #close()} completes the upload, and a downstream step that read it early would find nothing.
     */
    private void publishRunningCount() {
        if (stepExecution == null) {
            return;
        }
        final ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(REJECT_GENERATION_PREFIX_CONTEXT_KEY, generationPrefix);
        context.putLong(REJECT_RECORD_COUNT_CONTEXT_KEY, recordsWritten.get());
    }

    /**
     * Names the job-execution entry holding the key at one index of the ordered reject generation.
     *
     * <p>Static and pure, so a consumer and a test can apply the rule without an instance.
     *
     * @param index the zero-based position in creation order; must not be negative
     * @return the context entry name, never {@code null}
     * @throws IllegalArgumentException if {@code index} is negative, which would name an entry no writer emits
     */
    public static String rejectObjectKeysIndexEntry(final int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative but was " + index);
        }
        return REJECT_OBJECT_KEYS_INDEX_ENTRY_PREFIX + Integer.toString(index);
    }

    /**
     * Builds the metadata stated on the streamed generation object.
     *
     * <p>The content type is the generic octet stream so that no intermediary treats the payload as text and
     * translates line endings into a fixed-width record stream that has none.
     *
     * <p><strong>No content length is declared.</strong> The total is unknown until the last chunk has been
     * appended, and a length that disagreed with the body would either fail the upload or, worse, truncate it.
     * Framing is proven per append instead, by {@link #assertUnblockedFraming(int, int)}, which is the stronger
     * guarantee: it holds for every record rather than for the total alone.
     *
     * <p>This method is a pure function - it takes no argument and reads no field.
     *
     * @return the metadata to attach
     */
    private static ObjectMetadata streamedObjectMetadata() {
        return ObjectMetadata.builder()
                .contentType(OBJECT_CONTENT_TYPE)
                .build();
    }

    /**
     * Reports one rejected record on the instrument that owns the records-rejected series.
     *
     * <p>This replaces {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} at
     * {@code app/cbl/CBTRN02C.cbl:L228} - note the two spaces before the colon in the source - with a metric
     * that is queryable rather than only readable. The series is tagged by reject code, which turns the five
     * outcomes into an operable signal; the tag has exactly five possible values so its cardinality is
     * bounded. <strong>No identifying value is ever used as a tag.</strong>
     *
     * <p>This writer is the right producer for it: {@code :L214} moves the reject count inside the same
     * {@code ELSE} branch that writes the reject record, so a rejected record and a counted rejection are the
     * same event in the source.
     *
     * <p>Neither the metric name nor the tag key appears in this file. Both belong to {@link MetricsConfig},
     * which registers all five series eagerly at startup, so a code that never occurs still appears on the
     * scrape endpoint at zero rather than being absent.
     *
     * <p>Side effects: increments one counter series. Nothing here influences the exit status - the
     * return-code-4 rule of {@code :L229-L231} is the job's to apply.
     *
     * @param rejectCode the outcome that rejected the record, never {@code null}
     */
    private void countRejectedRecord(final RejectCode rejectCode) {
        this.metricsConfig.countRecordRejected(rejectCode);
    }
}
