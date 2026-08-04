/*
 * Program     : StatementWriter.java
 * Application : CardDemo
 * Type        : Spring Batch ItemWriter
 * Function    : Persists the 80-byte text statement and the 100-byte HTML statement produced by
 *               StatementProcessor, one object pair per account, preserving both record widths
 *               byte-exactly at the object-storage boundary.
 * Source      : app/jcl/CREASTMT.JCL STEP040 :L79 (STMTFILE LRECL=80 :L89, HTMLFILE LRECL=100 :L94);
 *               app/cbl/CBSTM03A.CBL :L45 FD-STMTFILE-REC X(80), :L47 FD-HTMLFILE-REC X(100),
 *               :L293 OPEN OUTPUT STMT-FILE HTML-FILE, :L339 CLOSE STMT-FILE HTML-FILE;
 *               app/cpy/COSTM01.CPY (32-byte TRNX-KEY + 318 = 350) @ 7756d89
 *
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
 */
package com.cardemo.batch.writers;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Persists one account's statement pair - the 80-byte text stream and the 100-byte HTML stream - as two
 * objects, and nothing else.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the {@code OPEN OUTPUT STMT-FILE HTML-FILE} of {@code app/cbl/CBSTM03A.CBL:L293}, the
 * {@code WRITE FD-STMTFILE-REC} and {@code WRITE FD-HTMLFILE-REC} verbs it drives, and the
 * {@code CLOSE STMT-FILE HTML-FILE} of {@code :L339}. It receives lines that are already composed and
 * writes them; it composes nothing.
 *
 * <h2>The processor renders, the writer persists</h2>
 *
 * <p>That division is the contract, and it is worth stating plainly because this file previously broke it.
 * {@link StatementProcessor} owns the emission paragraphs - {@code 4000-TRNXFILE-GET},
 * {@code 5000-CREATE-STATEMENT}, {@code 5100-WRITE-HTML-HEADER}, {@code 5200-WRITE-HTML-NMADBS} and
 * {@code 6000-WRITE-TRANS} - and hands over a {@link StatementProcessor.Statement} carrying the finished
 * lines. This class owns the two output geometries and the storage boundary. The Agent Action Plan assigns
 * the work exactly that way: the processor is derived from {@code app/cbl/CBSTM03A.CBL} for "per-account
 * aggregation and dual-format emission", this writer from {@code app/jcl/CREASTMT.JCL} for "two outputs at
 * 80 and 100 bytes per line respectively".
 *
 * <p>The 34-fragment markup table and the {@code ST-LINE} constant set live in the processor and nowhere
 * else. Holding them in both classes would let a change to one produce a statement that disagreed with the
 * other; keeping the rendering in one place is also what makes the processor's item type and this writer's
 * item type the same type, and therefore the pipeline connectable.
 *
 * <h2>Record geometry is preserved byte-exactly</h2>
 *
 * <p>Every text record is exactly {@value #TEXT_RECORD_LENGTH} characters and every HTML record exactly
 * {@value #HTML_RECORD_LENGTH}, and the two widths are never harmonised: the program's own field widths
 * settle it, {@code 01 FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45} and
 * {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code :L47}. A record that arrives longer than its area is
 * <strong>rejected</strong>: COBOL would have truncated it silently into the record area, and silently
 * corrupting the geometry is the one outcome this class must never produce. Records are uploaded
 * undelimited, so the text object's size is an exact multiple of 80 and the HTML object's an exact multiple
 * of 100.
 *
 * <p>The {@code LRECL=80} that {@code app/jcl/CREASTMT.JCL:L69} declares for {@code HTMLFILE} in its
 * pre-delete step contradicts the {@code LRECL=100} the execution step declares at {@code :L94}. That is a
 * legacy defect, it is logged rather than repaired, and the program's own {@code PIC X(100)} field is what
 * this class follows.
 *
 * <h2>Per-execution state, and why this bean is step scoped</h2>
 *
 * <p>The class holds the two record buffers, the identity of the statement currently open and the keys of
 * the last pair written. That state belongs to one step execution, so the bean is {@code @StepScope} and
 * the container creates one instance per execution. A singleton would have shared one buffer between
 * concurrent executions, and {@code synchronized} would not have helped: mutual exclusion serialises
 * access to shared state, it does not stop two runs from sharing it. Step scope removes the sharing
 * instead of guarding it.
 *
 * <h2>Object keys are validated, never merely non-blank</h2>
 *
 * <p>Both key segments come from data - the account identifier from the processed record, the statement
 * month from this step's clock - and both are interpolated into an object key. A segment carrying a slash
 * would create an unintended prefix, a segment carrying a dot could collide, and a segment carrying a
 * control character could forge a log line. So the account identifier must be exactly
 * {@value #ACCOUNT_ID_DIGITS} ASCII digits and the statement month must be a strictly resolved
 * {@code uuuu-MM}; anything else is refused before a key is composed.
 *
 * <h2>Generation data group translation</h2>
 *
 * <p>A {@code (+1)} relative reference becomes a new object under a strictly greater, zero-padded
 * generation segment, so the lexicographic order of the keys equals the numeric order of the generations.
 * A {@code (0)} reference is served by reading the concrete key this writer publishes into the step
 * execution context, never by re-resolving "the latest generation". Retention is documented rather than
 * enforced: no lifecycle rule is created here.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile}; the compiler runs {@code -Xlint:all -Werror} with
 * {@code failOnWarning}. Test with {@code ./mvnw -B clean test}; the full gate is
 * {@code ./mvnw -B clean verify}. The integration tier needs a reachable Docker socket because it starts
 * the AWS emulator through Testcontainers.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Configuration read by this component</caption>
 *   <tr><th>Property</th><th>Environment variable</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code carddemo.aws.s3.statements-bucket}</td><td>{@code CARDDEMO_S3_STATEMENTS_BUCKET}</td>
 *       <td><em>none</em> - required</td>
 *       <td>Bucket that receives both statement objects. It carries <strong>no inline default</strong>, on
 *           the same terms as the batch output bucket the other two writers in this package take: a
 *           destination that was never configured must fail the context rather than write somewhere
 *           unintended. {@code src/main/resources/application.yml} supplies the value with no fallback, and
 *           {@code .env.example}, {@code docker-compose.yml} and {@code localstack-init/init-aws.sh} agree
 *           on {@code carddemo-statements} for a developer stack, so nothing has to be overridden to run
 *           locally. A bucket name is an identity, not a credential; no secret is defaulted anywhere in
 *           this class.</td></tr>
 * </table>
 *
 * <p>No endpoint, credential or region is named here: the client arrives by injection, already configured
 * by the active profile.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>"a composed record is N characters but the record area is exactly 80"</dt>
 *   <dd>The processor produced an over-long line. Fix the composition there; do not widen the record area,
 *       which is fixed by the program's own field width.</dd>
 *   <dt>"a statement for another account is still open"</dt>
 *   <dd>An earlier statement was not closed, so its records would have leaked into this one. Every write
 *       here opens and closes within the call, so this indicates a caller using the low-level lifecycle
 *       directly and abandoning it part way.</dd>
 *   <dt>"the account identifier must be exactly 11 ASCII digits"</dt>
 *   <dd>Deliberate. The value becomes an object-key segment, so it is validated rather than trusted.</dd>
 *   <dt>Object storage rejected the upload</dt>
 *   <dd>The failure is translated through {@code FileStatusMapper} into the typed hierarchy carrying the
 *       four-character status, and the cause is preserved. Check the emulator endpoint and the bucket in
 *       the active profile; the bucket is created by {@code localstack-init/init-aws.sh}, never here.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe by design, and it does not need to be: a step-scoped bean belongs to one step
 * execution, and a chunk-oriented step writes chunks sequentially. Nothing static is mutable.
 *
 * <h2>Legacy defects and deviations affecting this writer</h2>
 *
 * <p>None of the items below is repaired in code: the legacy behaviour is the parity contract.
 *
 * <ol>
 *   <li><strong>The 80-versus-100 {@code HTMLFILE} record-length contradiction.</strong> The pre-delete step
 *       declares {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} for {@code HTMLFILE}
 *       ({@code app/jcl/CREASTMT.JCL:L69}, inside {@code STEP030 EXEC PGM=IEFBR14} at {@code :L66}) while the
 *       step that actually writes the dataset declares {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}
 *       ({@code :L94}). <strong>100 is correct</strong>, on the triple confirmation of
 *       {@code CREASTMT.JCL:L94}, {@code CBSTM03A.CBL:L47} and {@code CBSTM03A.CBL:L149}. The 80 at
 *       {@code :L69} is a defect in a step that only deletes the dataset and therefore never writes a
 *       record. The widths are <strong>not</strong> harmonised.</li>
 *   <li><strong>The corrupted {@code STMTFILE} DD continuation.</strong> {@code app/jcl/CREASTMT.JCL:L90}
 *       reads, verbatim, {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - a botched
 *       paste that left the fragments {@code 00,RECFM=FB)} and {@code ATA.VSAM.KSDS} inside the
 *       continuation. What the line was meant to say cannot be recovered from this repository: that would
 *       need a pre-corruption revision of the member, and none exists at {@code 7756d89}. The line is quoted
 *       here and is <strong>never reconstructed</strong>; the surrounding {@code :L89} and {@code :L91}
 *       clauses are unaffected and supply the record length and the dataset name.</li>
 *   <li><strong>The upstream projection truncates two timestamp bytes.</strong>
 *       {@code app/jcl/CREASTMT.JCL:L54} {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} copies only 50
 *       bytes from offset 279, which is the whole 26-byte originating timestamp plus the <strong>first 24 of
 *       the 26</strong> processing-timestamp bytes, and drops the 20-byte trailing filler entirely - 328
 *       bytes written into a 350-byte record. The projected processing timestamp therefore arrives as a
 *       24-character value padded to 26. That truncation is produced by the job's in-job projection, not by
 *       this writer, and the value is consumed exactly as received and <strong>never repaired</strong>.</li>
 *   <li><strong>{@code HTML-LTDS} is declared but never activated.</strong> The condition name
 *       {@code 88 HTML-LTDS VALUE '&lt;td&gt;'} is declared at {@code app/cbl/CBSTM03A.CBL:L161}, yet a
 *       census of the procedure division finds <strong>no</strong> {@code SET HTML-LTDS TO TRUE} anywhere: 33
 *       of the 34 declared fragments are activated, across 64 {@code SET} statements. It is a
 *       declared-but-unactivated artefact of the source, so the fragment is <strong>retained</strong> in
 *       {@link #htmlFragments()} and the fragment table matches the source declaration count exactly.
 *       Deleting it would make the table diverge from {@code :L148-L211} and would break the fragment census
 *       the coverage gate reads.</li>
 *   <li><strong>The legacy 510-transaction ceiling is removed.</strong>
 *       {@code app/cbl/CBSTM03A.CBL:L225-L233} declares {@code WS-CARD-TBL OCCURS 51 TIMES} each holding
 *       {@code WS-TRAN-TBL OCCURS 10 TIMES}, a hard ceiling of
 *       {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions per
 *       run, and the table-building loop increments both subscripts with no bounds check at all. This is a
 *       <strong>deliberate deviation, not parity</strong>: Java streams the transactions chunk by chunk, so
 *       the ceiling and its unguarded storage-overrun hazard are both gone. The ceiling is
 *       <strong>not</strong> claimed to have been preserved.</li>
 *   <li><strong>The object-storage record-framing convention is not specified by the corpus.</strong> The
 *       corpus defines {@code RECFM=FB} for both datasets but specifies no mainframe-to-object-storage
 *       transfer convention, so whether a downstream consumer expects newline-delimited output cannot be
 *       established from it. This class emits undelimited fixed-length records, which is what
 *       {@code RECFM=FB} means. A newline-delimited variant would be a deviation to state explicitly, never
 *       a silent change.</li>
 *   <li><strong>No service-level objective exists to assert against.</strong> The corpus publishes no
 *       throughput or latency target for statement generation, so any threshold would be invented. The
 *       performance gate therefore records a <em>measured baseline</em> rather than a pass-or-fail
 *       threshold.</li>
 *   <li><strong>The batch timestamp is centisecond precision, not millisecond.</strong> Millisecond
 *       precision is three digits, so three plus four zeros would be <strong>27</strong> characters, whereas
 *       the field is 26. The verified layout is {@code DB2-MIL PIC 9(002)} followed by
 *       {@code DB2-REST PIC X(04)} ({@code app/cbl/CBTRN02C.cbl:L170-L174}, generated at {@code :L701}) -
 *       two digits plus four zeros, giving the pattern {@code yyyy-MM-dd-HH.mm.ss.SS0000}, which sums to
 *       exactly 26. The 26-character layout in the source governs. This class is unaffected because it emits
 *       no timestamp, but any component that generated one on a millisecond reading would be one byte
 *       wide.</li>
 *   <li><strong>The HTML declares a character set that does not govern the record encoding.</strong> The
 *       fragment at {@code app/cbl/CBSTM03A.CBL:L153} emits {@code <meta charset="utf-8">} as document
 *       <em>content</em>. That is a statement about how a browser should interpret the markup and has
 *       <strong>no bearing</strong> on the encoding of the {@code RECFM=FB} record that carries it. The
 *       record encoding is {@link #RECORD_CHARSET}, chosen because it is byte-transparent, so a
 *       100-character line is exactly 100 bytes. The fragment is emitted verbatim and the two concerns are
 *       simply distinct: encoding the records as UTF-8 to "match" the declaration would silently break the
 *       geometry the moment any byte above {@code 0x7F} appeared.</li>
 *   </ol>
 *
 * @see StatementTransaction
 */
@StepScope
@Component
public class StatementWriter
        implements ItemWriter<StatementProcessor.Statement>, StepExecutionListener {

    /** Diagnostic logger. No statement content ever reaches it: see the emission methods. */
    private static final Logger LOG = LoggerFactory.getLogger(StatementWriter.class);

    // ---------------------------------------------------------------------------------------------
    // Record geometry. Both widths are fixed by the program's own record areas.
    // ---------------------------------------------------------------------------------------------

    /** {@code 01 FD-STMTFILE-REC PIC X(80)}, {@code app/cbl/CBSTM03A.CBL:L45}. */
    private static final int TEXT_RECORD_LENGTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /** {@code 01 FD-HTMLFILE-REC PIC X(100)}, {@code app/cbl/CBSTM03A.CBL:L47}. */
    private static final int HTML_RECORD_LENGTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /**
     * The single-byte encoding the objects are written in. Chosen so that one character is one byte and
     * the object size is therefore an exact multiple of the record length; a multi-byte encoding would
     * make the byte geometry depend on the data.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    // ---------------------------------------------------------------------------------------------
    // Logical file identities and diagnostics.
    // ---------------------------------------------------------------------------------------------

    /** The text output's DD name, {@code app/jcl/CREASTMT.JCL:L87}. */
    public static final String STMTFILE_DD_NAME = "STMTFILE";

    /** The markup output's DD name, {@code app/jcl/CREASTMT.JCL:L92}. */
    public static final String HTMLFILE_DD_NAME = "HTMLFILE";

    /** The attempted operation reported on a failed emission, mirroring the COBOL {@code WRITE} verb. */
    private static final String OPERATION_WRITE = "WRITE";

    /** The attempted operation reported on a failed open, mirroring {@code OPEN OUTPUT} at {@code :L293}. */
    private static final String OPERATION_OPEN = "OPEN";

    /** The status reported for a storage failure: the {@code '9x'} family with a zero second byte. */
    private static final String STORAGE_FAILURE_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** {@code ABEND-CULPRIT}, the program whose failure this is, {@code app/cpy/CSMSG02Y.cpy}. */
    private static final String ABEND_CULPRIT = "CBSTM03A";

    // ---------------------------------------------------------------------------------------------
    // Object storage: key composition, validation and metadata.
    // ---------------------------------------------------------------------------------------------

    /**
     * Root key segment for every statement object.
     *
     * <p>Public because it is the enumeration contract the pre-delete of {@code STEP030} depends on: that step
     * must be able to find the prior logical output without knowing which accounts a previous run produced,
     * and this prefix is what makes that possible. Keeping it private would force the prefix literal to be
     * duplicated in the step, where the two copies could drift apart silently.
     */
    public static final String KEY_ROOT = "statements";

    /** Key segment carrying the account prefix required of every statement object. */
    private static final String KEY_ACCOUNT_SEGMENT = "account=";

    /** Key segment carrying the statement-month prefix required of every statement object. */
    private static final String KEY_MONTH_SEGMENT = "month=";

    /** Key segment carrying the GDG generation, zero padded so lexical order equals numeric order. */
    private static final String KEY_GENERATION_SEGMENT = "generation=";

    /**
     * Key separator. Object storage has no directories; the slash is a naming convention only.
     *
     * <p>Public for the same reason as {@link #KEY_ROOT}: a consumer enumerating the statement root filters
     * directory-marker keys by this separator, and must use the one the writer actually composes with.
     */
    public static final String KEY_SEPARATOR = "/";

    /** Object name of the text statement, echoing the legacy dataset's low-level qualifier. */
    private static final String TEXT_OBJECT_NAME = "STATEMNT.PS";

    /** Object name of the markup statement. */
    private static final String HTML_OBJECT_NAME = "STATEMNT.HTML";

    /**
     * Zero-padded width of the generation segment: the number of digits in {@code Long.MAX_VALUE}.
     *
     * <p><b>Nineteen digits, and not a narrower width.</b> Truncation is not the hazard - a zero-padding
     * format widens rather than truncates. The generation is a {@code long} job instance identifier, whose
     * domain needs nineteen digits, and the moment one exceeds the padded width the padding stops covering
     * it: on a twelve-digit width, generation 1,000,000,000,000 renders as thirteen characters and
     * {@code "1000...0"} sorts <em>before</em> {@code "999999999999"} lexicographically. Since the entire
     * purpose of padding these
     * digits is that "the lexicographic order of the keys equals the numeric order of the generations" - which
     * is what lets a relative {@code (0)} reference resolve as the greatest existing prefix - the equivalence
     * would break and a consumer would resolve {@code (0)} to a <em>stale</em> generation. It would not fail;
     * it would quietly return the wrong statement. Nineteen digits is the width every other numeric key
     * component in the tree uses, so the equivalence holds across the whole domain rather than up to an
     * unproven bound.
     */
    private static final int GENERATION_WIDTH = 19;

    /**
     * Content type advertised for the text object, including the charset the bytes are actually in.
     *
     * <p>The charset parameter is not decoration. See {@link #RECORD_CHARSET} for the finding: a text media
     * type with no charset parameter is interpreted by the recipient's default, and the bytes here are
     * ISO-8859-1, so any recipient defaulting to UTF-8 mis-decodes every byte above {@code U+007F}.
     */
    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=ISO-8859-1";

    /**
     * Digits in {@code ACCT-ID PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy:L5}), and therefore the exact length the
     * account path segment of an object key may have. See {@link #requireAccountIdSegment(String)}.
     */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** Length of a {@code yyyy-MM} statement month. */
    private static final int STATEMENT_MONTH_LENGTH = 7;

    /** Index of the hyphen in a {@code yyyy-MM} statement month. */
    private static final int STATEMENT_MONTH_SEPARATOR_INDEX = 4;

    /** The separator a {@code yyyy-MM} statement month carries. */
    private static final char STATEMENT_MONTH_SEPARATOR = '-';

    /** Upper bound of a calendar month, asserted rather than assumed. */
    private static final int MONTHS_IN_YEAR = 12;

    /**
     * Lowest code point admitted into a fixed-width record: the space, {@code U+0020}. Everything below it is a
     * C0 control character. See {@link #requirePermittedCharacters(String, String)}.
     */
    private static final char MIN_PERMITTED_CHAR = '\u0020';

    /** The delete control, {@code U+007F}, refused with the C0 set although it sits above the printable range. */
    private static final char DELETE_CHAR = '\u007F';

    /** First code point of the C1 control block, {@code U+0080}. */
    private static final char FIRST_C1_CHAR = '\u0080';

    /** Last code point of the C1 control block, {@code U+009F}. */
    private static final char LAST_C1_CHAR = '\u009F';

    /** Highest code point {@link #RECORD_CHARSET} represents as a single byte. */
    private static final char MAX_ENCODABLE_CHAR = '\u00FF';

    /**
     * Human-readable description of the permitted set, reported in the diagnostic so an operator need not infer
     * the rule from a code point. Identical to the two sibling writers' wording: one rule, stated one way.
     */
    private static final String PERMITTED_CHARACTER_SET_DESCRIPTION =
            "U+0020 to U+007E and U+00A0 to U+00FF (printable ISO-8859-1; no C0 or C1 control, no DEL)";

    // There are deliberately no entity constants and no escaper here. Every dynamic value reaching the
    // markup sink is escaped by com.cardemo.batch.processors.StatementProcessor, which owns the composition
    // of both records; this class receives finished fixed-width lines and pads them. A second escaper would
    // be a second escaping policy to keep in step with the first, and a value escaped twice renders its own
    // entities as text.

    /** Content type advertised for the HTML object, including the charset the bytes are actually in. */
    private static final String HTML_CONTENT_TYPE = "text/html; charset=ISO-8859-1";

    /**
     * Opening of the content disposition presented for both objects. Value {@value}.
     *
     * <p>Both objects are batch <em>output</em>, retrieved by a downstream job or handed to a customer;
     * neither is a page this system serves. Declaring them as attachments means a browser pointed at the HTML
     * object saves it instead of rendering it in the bucket's origin, which is what keeps a statement from
     * becoming a script-execution context if markup were ever to survive the escape that
     * {@code com.cardemo.batch.processors.StatementProcessor} applies. It is defence in depth behind that
     * escape, never instead of it - and it pairs with the charset already declared on
     * {@link #HTML_CONTENT_TYPE}, since a sniffed encoding is a documented way to smuggle markup past an
     * escape that was correct in the encoding actually used.
     */
    private static final String CONTENT_DISPOSITION_PREFIX = "attachment; filename=\"";

    /** Closing quote of the content disposition. Value {@value}. */
    private static final String CONTENT_DISPOSITION_SUFFIX = "\"";

    /**
     * Cache directive presented for both objects. Value {@value}. A statement carries customer name, address,
     * account identifier, balance and credit score, so no intermediary is invited to retain a copy of it.
     */
    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    /**
     * The only accepted statement-month shape, resolved {@link ResolverStyle#STRICT} so that a
     * syntactically plausible but non-existent month is refused rather than shifted.
     */
    private static final DateTimeFormatter STATEMENT_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /** Step execution context key under which the text object's concrete key is published. */
    public static final String CONTEXT_KEY_TEXT_OBJECT = "carddemo.statement.text.objectKey";

    /** Step execution context key under which the markup object's concrete key is published. */
    public static final String CONTEXT_KEY_HTML_OBJECT = "carddemo.statement.html.objectKey";

    /**
     * Job-execution-context entry holding how many statement objects this job instance created, as a
     * {@code Long}.
     *
     * <p><b>Finding, severity High, RESOLVED twice - and the second resolution replaced the first.</b> The
     * original defect was that the step published only into the <em>step</em> execution context, whose two
     * entries hold one text key and one HTML key and are overwritten on every flush; a step producing
     * statements for fifty accounts therefore left only the last account's pair behind. The first remediation
     * appended <em>every</em> created key into the job execution context as an indexed entry. That made the
     * record complete but made it unbounded: the job execution context is serialised to the job repository on
     * every commit, so its size grew with the number of accounts, which is exactly the whole-run retention
     * this class must not do.
     *
     * <p>This entry holds the <em>count</em> only, which is one number
     * whatever the account count, and the authoritative record of which objects exist is the statement root
     * itself - {@link #KEY_ROOT} - which a consumer enumerates. That is strictly better than a recorded list
     * as well as bounded: an enumeration reports what object storage actually holds, whereas a list reports
     * only what some previous execution remembered writing. {@code STEP030}'s pre-delete is precisely such a
     * consumer, and it needs no list.
     *
     * <p>The two step-scoped entries are kept, still naming the latest pair, for a listener running inside
     * the step.
     */
    public static final String CONTEXT_KEY_OBJECT_KEYS_COUNT = "carddemo.statement.object.keys.count";

    // ---------------------------------------------------------------------------------------------
    // Injected collaborators. Every one arrives by constructor; none is constructed here.
    // ---------------------------------------------------------------------------------------------

    /**
     * Object-storage operations. Injected; never constructed here, and never pointed at an endpoint here.
     *
     * <p>Declared as {@code S3Operations} rather than as the concrete {@code io.awspring.cloud.s3.S3Template},
     * matching the other two writers in this package. Spring Cloud AWS declares its template bean under
     * {@code @ConditionalOnMissingBean(S3Operations.class)}, so taking the interface is satisfied by the
     * auto-configured template and stays satisfied if {@code com.cardemo.config.AwsConfig} supplies its own of
     * either type, whereas taking the class would not. Only {@code upload} is called, which the interface
     * declares.
     */
    private final S3Operations objectStorage;

    /** The sole owner of the status-to-exception decision. */
    private final FileStatusMapper fileStatusMapper;

    /** The bucket both objects are written to. */
    private final String statementsBucket;

    /** The application's single time source, from which the statement month is derived once per step. */
    private final Clock clock;

    // ---------------------------------------------------------------------------------------------
    // Per-execution state. Safe because the bean is step scoped: see the class documentation.
    // ---------------------------------------------------------------------------------------------

    /** The accumulated text records of the statement currently open, undelimited. */
    private final StringBuilder textRecords = new StringBuilder();

    /** The accumulated markup records of the statement currently open, undelimited. */
    private final StringBuilder htmlRecords = new StringBuilder();

    /** The account the open statement belongs to, or {@code null} when none is open. */
    private String currentAccountId;

    /** The month segment of the open statement, or {@code null} when none is open. */
    private String currentStatementMonth;

    /** The generation the open statement is being written under. */
    private long currentGeneration;

    /** Whether a statement is open, so a record has somewhere to go. */
    private boolean outputsOpen;

    /** Whether the open statement's records have already been uploaded. */
    private boolean outputsFlushed;

    /** Concrete key of the most recent text object, or {@code null} before the first flush. */
    private String textObjectKey;

    /** Concrete key of the most recent markup object, or {@code null} before the first flush. */
    private String htmlObjectKey;

    /** The job instance identifier, which is the generation a {@code (+1)} reference resolves to. */
    private long jobInstanceGeneration;

    /**
     * The step execution this instance serves, captured by {@link #beforeStep(StepExecution)}.
     *
     * <p>Per-execution state on a {@code @StepScope} bean, so it belongs to exactly one execution and needs no
     * cross-execution reasoning. It supplies one thing: the job execution context that
     * {@link #countCreatedObject()} updates the object tally on. {@code null} when the bean is
     * driven directly rather than by a step, which is the unit-test path.
     */
    private StepExecution currentStepExecution;

    /** The month every statement of this step is filed under, derived once in {@code beforeStep}. */
    private String stepStatementMonth;

    /** Number of statements persisted by this step execution, for the closing diagnostic. */
    private long statementsWritten;

    /**
     * Creates the writer.
     *
     * @param objectStorage the injected object-storage operations; must not be {@code null}. No client is
     *     ever constructed here and no endpoint or credential is ever named here
     * @param fileStatusMapper the injected status mapper that owns the status-to-exception decision; must
     *     not be {@code null}
     * @param clock the application's single time source; must not be {@code null}. Used once per step, to
     *     derive the statement month
     * @param statementsBucket the bucket that receives both statement objects, from
     *     {@code carddemo.aws.s3.statements-bucket} (environment {@code CARDDEMO_S3_STATEMENTS_BUCKET}). It
     *     has <strong>no default</strong>, on the same terms as the batch output bucket the other two
     *     writers take: an unconfigured destination must fail the context rather than silently write
     *     somewhere unintended. {@code src/main/resources/application.yml} supplies it with no fallback, so
     *     the property is always present; must not be {@code null} or blank
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if the bucket name is {@code null} or blank
     */
    public StatementWriter(
            S3Operations objectStorage,
            FileStatusMapper fileStatusMapper,
            Clock clock,
            @Value("${carddemo.aws.s3.statements-bucket}") String statementsBucket) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (statementsBucket == null || statementsBucket.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.statements-bucket must be configured with a non-blank value; "
                            + "set CARDDEMO_S3_STATEMENTS_BUCKET");
        }
        this.statementsBucket = statementsBucket;
        this.stepStatementMonth = YearMonth.now(clock).format(STATEMENT_MONTH_FORMAT);
    }

    // =============================================================================================
    // The ItemWriter contract: one object pair per statement.
    // =============================================================================================

    /**
     * Persists every statement in the chunk, one text object and one markup object each.
     *
     * <p>Each statement is opened, filled from its own already-composed lines and closed within this call,
     * which is what makes {@code OPEN OUTPUT} at {@code app/cbl/CBSTM03A.CBL:L293} and {@code CLOSE} at
     * {@code :L339} bracket exactly one account, as they did in the source.
     *
     * <p>An empty or {@code null} chunk writes nothing at all and, in particular, does not bring a
     * statement into existence: a spurious empty object is never created.
     *
     * @param chunk the statements to persist; a {@code null} or empty chunk is a no-op
     * @throws Exception declared by the {@link ItemWriter} contract and retained so the signature matches
     *     the interface exactly. In practice every failure raised here is an unchecked
     *     {@code com.cardemo.exception.CardDemoException}: a
     *     {@code com.cardemo.exception.FatalProcessingException} when a record exceeds its declared width
     *     or a key segment is invalid, a {@code com.cardemo.exception.FileAccessException} when storage
     *     rejects an upload
     */
    @Override
    public void write(Chunk<? extends StatementProcessor.Statement> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        for (StatementProcessor.Statement statement : chunk) {
            persist(statement);
        }
    }

    /**
     * Persists one statement: open, append every line, close.
     *
     * @param statement the statement to persist, never {@code null}
     */
    private void persist(StatementProcessor.Statement statement) {
        Objects.requireNonNull(statement, "statement must not be null");
        openStatementOutputs(statement.accountId(), this.stepStatementMonth);
        for (String line : statement.textLines()) {
            writeStatementLine(line);
        }
        for (String fragment : statement.htmlLines()) {
            writeHtmlFragment(fragment);
        }
        closeStatementOutputs();
        this.statementsWritten++;
        // The statement count is exposed by statementsWritten(), which the emit step publishes into its own
        // execution-context entry, and is deliberately NOT added to the records-processed counter. That counter
        // reproduces DISPLAY 'TRANSACTIONS PROCESSED :' at app/cbl/CBTRN02C.cbl:L227, whose population is the
        // daily transaction records POSTTRAN read at :L206 - one program, one population, one meaning. A
        // statement is not one of those records, and the statement job's emit step was additionally advancing
        // the same counter with the total it wrote, so one run moved an untagged series twice in a unit neither
        // figure belonged to and no PromQL query could decompose it again. No application counter has this
        // class's unit, so this class advances none; Spring Batch already publishes this writer's volume as
        // spring.batch.item.write, per step and per job, which is decomposable.
    }

    // =============================================================================================
    // The per-account output lifecycle. Public because a step may drive it directly - the statement
    // job's tasklet variant does - and because it is the observable counterpart of OPEN and CLOSE.
    // =============================================================================================

    /**
     * Opens the two per-account statement outputs, using the generation captured from the enclosing job.
     *
     * <p>This is the Java counterpart of {@code OPEN OUTPUT STMT-FILE HTML-FILE} at
     * {@code app/cbl/CBSTM03A.CBL:L293}. It buffers rather than holding two dataset handles, because the
     * targets are objects and an object is created whole.
     *
     * @param accountId the account the statement belongs to; must be exactly {@value #ACCOUNT_ID_DIGITS}
     *     ASCII digits, because it becomes an object-key segment
     * @param statementMonth the statement month; must be a strictly resolved {@code uuuu-MM}, because it
     *     becomes an object-key segment
     * @throws com.cardemo.exception.FatalProcessingException if either segment is invalid, or if a
     *     statement is already open, which would mean an earlier statement was never closed and its
     *     records would leak into this one
     */
    public void openStatementOutputs(String accountId, String statementMonth) {
        openStatementOutputs(accountId, statementMonth, this.jobInstanceGeneration);
    }

    /**
     * Opens the two per-account statement outputs under an explicit generation.
     *
     * @param accountId the account the statement belongs to; exactly {@value #ACCOUNT_ID_DIGITS} digits
     * @param statementMonth the statement month; a strictly resolved {@code uuuu-MM}
     * @param generation the generation to write under; must not be negative
     * @throws IllegalArgumentException if the generation is negative
     * @throws com.cardemo.exception.FatalProcessingException if either key segment is invalid, or if a
     *     statement is already open
     */
    public void openStatementOutputs(String accountId, String statementMonth, long generation) {
        String verifiedAccountId = requireAccountIdSegment(accountId);
        String verifiedMonth = requireStatementMonthSegment(statementMonth);
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative but was " + generation);
        }
        if (this.outputsOpen) {
            throw abend("a statement for another account is still open; close it before opening the next",
                    STMTFILE_DD_NAME, OPERATION_OPEN);
        }
        this.textRecords.setLength(0);
        this.htmlRecords.setLength(0);
        this.currentAccountId = verifiedAccountId;
        this.currentStatementMonth = verifiedMonth;
        this.currentGeneration = generation;
        this.outputsOpen = true;
        this.outputsFlushed = false;
        this.textObjectKey = null;
        this.htmlObjectKey = null;
        LOG.debug("Opened statement outputs for statement month {} generation {}", verifiedMonth, generation);
    }

    /**
     * Appends one text statement record, padded and validated to exactly {@value #TEXT_RECORD_LENGTH}
     * characters.
     *
     * <p>This is the Java counterpart of {@code WRITE FD-STMTFILE-REC FROM ...}, whose record area is
     * {@code 01 FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45}. A shorter line is
     * right-padded with spaces, never left ragged. A <strong>longer</strong> line is rejected: COBOL would
     * have truncated it silently into the record area, and silently corrupting the record geometry is the
     * one outcome this class must never produce.
     *
     * <p>Security: the line is appended to the statement object but is <strong>never logged and never
     * placed in an exception message</strong>. Statement records carry customer name, address, account
     * identifier, balance and credit score, and those bytes belong only in the object.
     *
     * @param line the record content; {@code null} is treated explicitly as an all-spaces record
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open, or if the line is
     *     longer than {@value #TEXT_RECORD_LENGTH} characters
     */
    public void writeStatementLine(String line) {
        requireOpen(STMTFILE_DD_NAME);
        this.textRecords.append(fixedRecord(line, TEXT_RECORD_LENGTH, STMTFILE_DD_NAME));
    }

    /**
     * Appends one HTML statement record, padded and validated to exactly {@value #HTML_RECORD_LENGTH}
     * characters.
     *
     * <p>This is the Java counterpart of {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}, whose record
     * area is {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} and whose source
     * field is {@code 05 HTML-FIXED-LN PIC X(100)} at {@code :L149}. The HTML width is
     * {@value #HTML_RECORD_LENGTH} and is never harmonised with the text width of
     * {@value #TEXT_RECORD_LENGTH}.
     *
     * <p>The fragment arrives already escaped: {@link StatementProcessor} escapes every dynamic value
     * before composing a line, so no markup-significant character from persisted data can reach the
     * object. This class performs no escaping of its own, because escaping a composed line would destroy
     * the markup it is supposed to carry.
     *
     * @param fragment the markup to emit; {@code null} is treated explicitly as an all-spaces record
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open, or if the fragment
     *     is longer than {@value #HTML_RECORD_LENGTH} characters
     */
    public void writeHtmlFragment(String fragment) {
        requireOpen(HTMLFILE_DD_NAME);
        this.htmlRecords.append(fixedRecord(fragment, HTML_RECORD_LENGTH, HTMLFILE_DD_NAME));
    }

    /**
     * Uploads both accumulated outputs to object storage and returns the keys that were created.
     *
     * <p>The bytes are encoded with {@link #RECORD_CHARSET} and are uploaded undelimited, so the text
     * object size is an exact multiple of {@value #TEXT_RECORD_LENGTH} and the HTML object size an exact
     * multiple of {@value #HTML_RECORD_LENGTH}: record length is preserved byte-exactly at the storage
     * boundary. Calling this method again without further records is a no-op that returns the same keys,
     * so a flush followed by a close never uploads twice.
     *
     * @return an unmodifiable map from logical file name - {@value #STMTFILE_DD_NAME} and
     *     {@value #HTMLFILE_DD_NAME} - to the object key created for it, in that order
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open
     * @throws com.cardemo.exception.FileAccessException if object storage rejects either upload. The
     *     exception carries the logical file name and the attempted operation, and preserves the
     *     underlying cause; it never carries the record buffer or the object key
     */
    public Map<String, String> flushStatementOutputs() {
        requireOpen(STMTFILE_DD_NAME);
        if (this.outputsFlushed) {
            return createdObjectKeys();
        }
        String textKey = objectKey(TEXT_OBJECT_NAME);
        String htmlKey = objectKey(HTML_OBJECT_NAME);
        upload(textKey, this.textRecords.toString(), TEXT_CONTENT_TYPE, TEXT_OBJECT_NAME, STMTFILE_DD_NAME);
        upload(htmlKey, this.htmlRecords.toString(), HTML_CONTENT_TYPE, HTML_OBJECT_NAME, HTMLFILE_DD_NAME);
        this.textObjectKey = textKey;
        this.htmlObjectKey = htmlKey;
        // Appended in creation order, text then HTML, matching the order the two uploads above ran in. Done
        // here rather than in afterStep because these two entries are one account's pair and a step produces
        // one pair per account: assembling at step end could only ever recover the last.
        countCreatedObject();
        countCreatedObject();
        this.outputsFlushed = true;
        LOG.info("Emitted statement objects for generation {}: {} text records, {} html records",
                this.currentGeneration,
                this.textRecords.length() / TEXT_RECORD_LENGTH,
                this.htmlRecords.length() / HTML_RECORD_LENGTH);
        return createdObjectKeys();
    }

    /**
     * Flushes if necessary and then closes the two per-account outputs.
     *
     * <p>This is the Java counterpart of {@code CLOSE STMT-FILE HTML-FILE} at
     * {@code app/cbl/CBSTM03A.CBL:L339}. The created keys remain readable through
     * {@link #createdObjectKeys()} after the close, because a later step resolves a GDG {@code (0)}
     * reference from those keys.
     *
     * @return an unmodifiable map from logical file name to the object key created for it
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open
     * @throws com.cardemo.exception.FileAccessException if object storage rejects either upload
     */
    public Map<String, String> closeStatementOutputs() {
        requireOpen(STMTFILE_DD_NAME);
        Map<String, String> keys = flushStatementOutputs();
        this.textRecords.setLength(0);
        this.htmlRecords.setLength(0);
        this.currentAccountId = null;
        this.currentStatementMonth = null;
        this.outputsOpen = false;
        return keys;
    }

    /**
     * Reports the object keys created by the most recent flush.
     *
     * @return an unmodifiable map from logical file name - {@value #STMTFILE_DD_NAME} then
     *     {@value #HTMLFILE_DD_NAME} - to the object key created for it, or an empty map when nothing has
     *     been flushed yet
     */
    public Map<String, String> createdObjectKeys() {
        if (this.textObjectKey == null || this.htmlObjectKey == null) {
            return Map.of();
        }
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(STMTFILE_DD_NAME, this.textObjectKey);
        keys.put(HTMLFILE_DD_NAME, this.htmlObjectKey);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Reports how many statements this step execution has persisted.
     *
     * @return the running count, never negative
     */
    public long statementsWritten() {
        return this.statementsWritten;
    }

    /**
     * Reports the month segment every statement of this step is filed under.
     *
     * @return the strictly resolved {@code uuuu-MM} month, never {@code null}
     */
    public String statementMonth() {
        return this.stepStatementMonth;
    }

    // =============================================================================================
    // Step lifecycle. The generation and the statement month are captured once, at the start.
    // =============================================================================================

    /**
     * Captures the job instance identifier as the generation, and the statement month from the clock.
     *
     * <p>The month is derived once here rather than per statement so that a run spanning midnight on the
     * first of a month cannot file two accounts under two different months, which would make the run's
     * output impossible to enumerate by prefix.
     *
     * @param stepExecution the step about to run; a {@code null} or partially populated execution leaves
     *     the generation at its current value, which is what a directly driven unit test wants
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepStatementMonth = YearMonth.now(this.clock).format(STATEMENT_MONTH_FORMAT);
        this.statementsWritten = 0L;
        this.currentStepExecution = stepExecution;
        if (stepExecution == null || stepExecution.getJobExecution() == null
                || stepExecution.getJobExecution().getJobInstance() == null) {
            return;
        }
        this.jobInstanceGeneration = stepExecution.getJobExecution().getJobInstance().getInstanceId();
    }

    /**
     * Publishes the created object keys into the step execution context.
     *
     * <p>A later step reads {@value #CONTEXT_KEY_TEXT_OBJECT} and {@value #CONTEXT_KEY_HTML_OBJECT}
     * instead of re-resolving "the latest generation", which is what makes a {@code (+1)} written by this
     * step readable as {@code (+1)} downstream. The keys published are those of the <strong>last</strong>
     * statement written, and the count of statements is logged beside them, because a step that produced
     * many accounts has many object pairs and a single context entry can only name one; a consumer that
     * needs them all enumerates the generation prefix, which is why the generation is in the key.
     * Promotion to the job execution context, where a later step needs it, is configured on the job by an
     * execution-context promotion listener; this method owns publication only.
     *
     * @param stepExecution the completed step execution; a {@code null} argument publishes nothing
     * @return {@code null}, which instructs Spring Batch to leave the step's exit status untouched. This
     *     writer never decides a step outcome: an unexpected condition is raised as an exception instead
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution == null) {
            return null;
        }
        Map<String, String> keys = createdObjectKeys();
        if (keys.isEmpty()) {
            return null;
        }
        LOG.info("Statement step persisted {} statement object pairs under month {} generation {}",
                this.statementsWritten, this.stepStatementMonth, this.currentGeneration);
        ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(CONTEXT_KEY_TEXT_OBJECT, keys.get(STMTFILE_DD_NAME));
        context.putString(CONTEXT_KEY_HTML_OBJECT, keys.get(HTMLFILE_DD_NAME));
        return null;
    }

    /**
     * Counts one created object into the job execution context.
     *
     * <p>Called as each object is created rather than once at step end, so the count covers every object a
     * step produced and not merely the last account's pair. The keys themselves are deliberately not recorded
     * - see {@link #CONTEXT_KEY_OBJECT_KEYS_COUNT} for why an enumeration of {@link #KEY_ROOT} is both the
     * bounded and the more truthful record.
     *
     * <p>Silently does nothing when no step execution has been captured, which is the case when the class is
     * driven directly by a unit test. That is the explicit {@code null} branch and not an oversight: the keys
     * are still returned by {@link #createdObjectKeys()}, so a test observes everything it needs.
     *
     * <p>Side effects: updates one entry in the job execution context. No I/O and no logging.
     */
    private void countCreatedObject() {
        StepExecution captured = this.currentStepExecution;
        if (captured == null || captured.getJobExecution() == null) {
            return;
        }
        ExecutionContext jobContext = captured.getJobExecution().getExecutionContext();
        long published = jobContext.getLong(CONTEXT_KEY_OBJECT_KEYS_COUNT, 0L);
        jobContext.putLong(CONTEXT_KEY_OBJECT_KEYS_COUNT, published + 1L);
    }


    // =============================================================================================
    // Guards, key composition and the storage boundary.
    // =============================================================================================

    /**
     * Validates an account identifier destined for an object-key segment.
     *
     * <p>Exactly {@value #ACCOUNT_ID_DIGITS} ASCII digits, which is what {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5} holds. Anything else is refused: a segment carrying a slash would
     * create an unintended prefix, a dot-only segment would collide with the object name, and a control
     * character would forge a log line. Digits alone cannot do any of those.
     *
     * @param accountId the candidate segment
     * @return the validated segment
     * @throws com.cardemo.exception.FatalProcessingException if it is not exactly eleven ASCII digits
     */
    private static String requireAccountIdSegment(String accountId) {
        if (accountId == null || accountId.length() != ACCOUNT_ID_DIGITS
                || !isAllAsciiDigits(accountId)) {
            throw abend(String.format(Locale.ROOT,
                    "the account identifier must be exactly %d ASCII digits because it becomes an "
                            + "object-key segment, but a value of length %d was supplied",
                    Integer.valueOf(ACCOUNT_ID_DIGITS),
                    Integer.valueOf(accountId == null ? -1 : accountId.length())),
                    STMTFILE_DD_NAME, OPERATION_OPEN);
        }
        return accountId;
    }

    /**
     * Validates a statement month destined for an object-key segment.
     *
     * <p>Parsed as a strictly resolved {@code uuuu-MM}, then re-rendered from the parsed value, so the
     * segment stored is canonical by construction and cannot carry a separator, a control character or
     * stray whitespace.
     *
     * @param statementMonth the candidate segment
     * @return the canonical segment
     * @throws com.cardemo.exception.FatalProcessingException if it is not a valid {@code uuuu-MM}
     */
    private static String requireStatementMonthSegment(String statementMonth) {
        if (statementMonth == null) {
            throw abend("the statement month must not be null because it becomes an object-key segment",
                    STMTFILE_DD_NAME, OPERATION_OPEN);
        }
        try {
            return YearMonth.parse(statementMonth, STATEMENT_MONTH_FORMAT)
                    .format(STATEMENT_MONTH_FORMAT);
        } catch (DateTimeParseException malformed) {
            throw abend(String.format(Locale.ROOT,
                    "the statement month must be a canonical uuuu-MM because it becomes an object-key "
                            + "segment, but a value of length %d was supplied",
                    Integer.valueOf(statementMonth.length())),
                    STMTFILE_DD_NAME, OPERATION_OPEN);
        }
    }

    /**
     * Reports whether every character is an ASCII digit.
     *
     * @param value the candidate, never {@code null}
     * @return {@code true} when the value is non-empty and wholly ASCII digits
     */
    private static boolean isAllAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a record when no statement is open.
     *
     * @param logicalFileName the DD the record was destined for
     * @throws com.cardemo.exception.FatalProcessingException always, when no statement is open
     */
    private void requireOpen(String logicalFileName) {
        if (!this.outputsOpen) {
            throw abend("no statement is open; call openStatementOutputs before emitting a record",
                    logicalFileName, OPERATION_WRITE);
        }
    }

    /**
     * Builds the abend that {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBSTM03A.CBL:L921} produces.
     *
     * @param reason the diagnosis, which never contains statement content
     * @param logicalFileName the DD involved
     * @param operation the attempted operation
     * @return the exception to throw, carrying abend code and culprit
     */
    private static FatalProcessingException abend(String reason, String logicalFileName, String operation) {
        String message = String.format(Locale.ROOT,
                "%s. The %s %s could not complete, so the statement run is abandoned with abend code %d and "
                        + "return code %d; app/cbl/CBSTM03A.CBL:L921 9999-ABEND-PROGRAM.",
                reason,
                logicalFileName,
                operation,
                FatalProcessingException.BATCH_ABEND_CODE,
                FatalProcessingException.BATCH_RETURN_CODE);
        LOG.error(message);
        return new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT, reason, message);
    }

    /**
     * Pads a record to its exact area width, refusing one that is too long.
     *
     * @param value the record content, {@code null} treated as all spaces
     * @param width the exact record area width
     * @param logicalFileName the DD the record belongs to
     * @return exactly {@code width} characters
     * @throws com.cardemo.exception.FatalProcessingException if the content is longer than the area
     */
    private static String fixedRecord(String value, int width, String logicalFileName) {
        String content = value == null ? "" : value;
        if (content.length() > width) {
            throw abend(String.format(Locale.ROOT,
                    "a composed record is %d characters but the record area is exactly %d",
                    Integer.valueOf(content.length()), Integer.valueOf(width)),
                    logicalFileName, OPERATION_WRITE);
        }
        requirePermittedCharacters(content, logicalFileName);
        return padRight(content, width);
    }

    /**
     * Composes one object key from the validated segments and the generation.
     *
     * @param objectName the low-level object name
     * @return the complete key
     */
    private String objectKey(String objectName) {
        return KEY_ROOT + KEY_SEPARATOR
                + KEY_ACCOUNT_SEGMENT + this.currentAccountId + KEY_SEPARATOR
                + KEY_MONTH_SEGMENT + this.currentStatementMonth + KEY_SEPARATOR
                + KEY_GENERATION_SEGMENT
                + String.format(Locale.ROOT, "%0" + GENERATION_WIDTH + "d",
                        Long.valueOf(this.currentGeneration))
                + KEY_SEPARATOR + objectName;
    }

    /**
     * Refuses any character outside the permitted single-byte set of a fixed-width record.
     *
     * <p><b>Why the character set is validated and not only the record length.</b> Validating length alone
     * would let a control byte arriving in a customer name, an address line or a transaction description travel
     * straight into the emitted stream. That is an injection defect on both outputs, for two different
     * reasons. Both objects are <b>unblocked and undelimited</b> - {@code app/jcl/CREASTMT.JCL:STEP040} declares
     * {@code LRECL=80} for the text output and {@code LRECL=100} for the HTML - so a consumer finds record
     * boundaries by counting bytes and by nothing else, and a carriage return or line feed inside a record is a
     * boundary a line-oriented reader will honour and this format does not have. On the HTML output it is worse
     * than a framing problem: a NUL or a control byte inside an attribute or a text node is a classic way to
     * break a parser out of the state an escaping step assumed it was in.
     *
     * <p>The rule is stated <b>positively</b> - {@code U+0020} to {@code U+007E} and {@code U+00A0} to
     * {@code U+00FF}, the printable ISO-8859-1 range - and matches the wording and the constants the two sibling
     * writers use, so one rule governs every fixed-width field in the tree. A deny-list of control characters
     * was rejected: it is an enumeration that stops being exhaustive without anyone noticing. Nothing legitimate
     * is refused; the statement layout is printable text and markup throughout.
     *
     * <p>Applied at {@link #fixedRecord(String, int, String)} rather than at the individual field helpers,
     * deliberately: every record of both outputs passes through that one method, so this is the choke point, and
     * a guard placed on a field helper would miss any record composed from literals and concatenation.
     *
     * <p>Static and pure, so a test can exercise the whole code-point range without opening a statement.
     *
     * @param content the composed record, never {@code null}
     * @param logicalFileName the output being written, for the diagnostic
     * @throws FatalProcessingException on the first character outside the permitted set
     */
    private static void requirePermittedCharacters(String content, String logicalFileName) {
        for (int index = 0; index < content.length(); index++) {
            char candidate = content.charAt(index);
            if (candidate < MIN_PERMITTED_CHAR
                    || candidate == DELETE_CHAR
                    || (candidate >= FIRST_C1_CHAR && candidate <= LAST_C1_CHAR)
                    || candidate > MAX_ENCODABLE_CHAR) {
                throw abend(String.format(Locale.ROOT,
                        "a composed record holds a character at position %d (code point U+%04X) outside the "
                                + "permitted set %s. The output is unblocked and undelimited, so a consumer "
                                + "finds record boundaries by counting bytes; a control byte inside a record "
                                + "would let a line-oriented reader see a boundary this format does not have. "
                                + "The content is withheld from this message because a statement record carries "
                                + "customer name, address, account identifier, balance and credit score",
                        Integer.valueOf(index + 1), Integer.valueOf(candidate),
                        PERMITTED_CHARACTER_SET_DESCRIPTION),
                        logicalFileName, OPERATION_WRITE);
            }
        }
    }

    /**
     * Encodes the accumulated records, failing rather than substituting when a character cannot be represented.
     *
     * <p>See {@link #RECORD_CHARSET} for the finding. A fresh {@link CharsetEncoder} is built per call because
     * an encoder is stateful and not thread safe; the cost is negligible beside an upload, and sharing one would
     * reintroduce exactly the cross-execution coupling that {@code @StepScope} was applied to remove.
     *
     * <p>In practice {@link #requirePermittedCharacters(String, String)} has already refused everything this
     * encoder could reject, so this is the second of two independent guards rather than the only one. Both are
     * kept: the character-set guard states the <em>policy</em> and produces a diagnostic naming the position, and
     * the encoder enforces the <em>mechanism</em>, so a future change to either cannot silently reintroduce
     * substitution.
     *
     * @param content the accumulated records, already padded to their exact widths
     * @param logicalFileName the output being written, for the diagnostic
     * @return the encoded bytes; exactly one byte per character
     * @throws FatalProcessingException if any character cannot be encoded in one byte
     */
    private static byte[] encodeRecords(String content, String logicalFileName) {
        CharsetEncoder encoder = RECORD_CHARSET.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .onMalformedInput(CodingErrorAction.REPORT);
        try {
            java.nio.ByteBuffer encoded = encoder.encode(java.nio.CharBuffer.wrap(content));
            byte[] payload = new byte[encoded.remaining()];
            encoded.get(payload);
            return payload;
        } catch (CharacterCodingException unencodable) {
            // The content is never named. A record carries customer name, address, account identifier, balance
            // and credit score, and this message is destined for a log.
            throw abend(
                    "the accumulated records hold a character that " + RECORD_CHARSET.name()
                            + " cannot represent in a single byte. Nothing was stored: substituting a "
                            + "replacement byte would preserve the record width while silently corrupting the "
                            + "content, so the upload is refused instead",
                    logicalFileName, OPERATION_WRITE);
        }
    }

    /**
     * Uploads one object, translating any storage failure through the status mapper.
     *
     * <p>Four pieces of metadata are presented and three of them are security controls rather than
     * description. The content type carries the charset the bytes are actually in, so the encoding is declared
     * rather than sniffed. The content disposition declares both objects as attachments, so a browser pointed
     * at the HTML object saves it instead of executing its markup in the bucket's origin. The cache directive
     * is {@value #CACHE_CONTROL_NO_STORE}, because a statement carries customer name, address, account
     * identifier, balance and credit score. Only the content length is purely descriptive, and it is what makes
     * the stored size an exact multiple of the record width.
     *
     * @param key the object key; deliberately absent from every message and log line, because it embeds the
     *     account identifier
     * @param content the undelimited records
     * @param contentType advisory metadata, charset included
     * @param objectName the leaf name offered as the attachment filename, {@value #TEXT_OBJECT_NAME} or
     *     {@value #HTML_OBJECT_NAME}. It is one of two constants and carries no caller-supplied text, so the
     *     disposition header cannot be influenced from outside this class
     * @param logicalFileName the DD being written
     * @throws com.cardemo.exception.FileAccessException if storage rejects the upload
     */
    private void upload(String key, String content, String contentType, String objectName,
            String logicalFileName) {
        // DEADLINE AND RETRY, and where they come from. The upload below is
        // synchronous and a statement run performs two per account, so an unbounded call would wedge the step
        // rather than fail it. No per-call override is configured HERE deliberately: a deadline written at this
        // call site would be a second policy that drifts from the one every other AWS call in the tree obeys.
        // AwsConfig.applyBoundedPolicy installs it on the client itself through an S3ClientCustomizer - a 30
        // second whole-call deadline, a 10 second per-attempt deadline and RetryMode.STANDARD, which bounds both
        // the attempt count and the backoff - and AwsConfig.s3Template consumes that same auto-configured
        // S3Client rather than building one, so this upload inherits it. A timeout therefore arrives as the
        // RuntimeException the catch below already handles, becomes the '9x' status and is raised as a
        // FileAccessException like any other physical write failure.
        byte[] payload = encodeRecords(content, logicalFileName);
        ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(contentType)
                .contentLength(Long.valueOf(payload.length))
                .contentDisposition(CONTENT_DISPOSITION_PREFIX + objectName + CONTENT_DISPOSITION_SUFFIX)
                .cacheControl(CACHE_CONTROL_NO_STORE)
                .build();
        try {
            this.objectStorage.upload(this.statementsBucket, key, new ByteArrayInputStream(payload), metadata);
        } catch (RuntimeException cause) {
            String message = String.format(Locale.ROOT,
                    "Object storage rejected the %s %s of %d bytes. %s",
                    logicalFileName,
                    OPERATION_WRITE,
                    Integer.valueOf(payload.length),
                    this.fileStatusMapper.displayIoStatus(STORAGE_FAILURE_STATUS));
            // Finding M-06, severity Medium. The throwable is NOT logged: an object-store failure carries the
            // bucket, the key and often the request URL in its message and stack, and this class's keys embed the
            // account identifier as a key segment - which its own contract says must never reach a log. Its type
            // is the classification an operator needs, and the throwable itself is preserved as the cause of the
            // exception raised immediately below, so the root cause survives in full and nothing is swallowed.
            LOG.error("{} (cause: {})", message, cause.getClass().getName());
            throw this.fileStatusMapper
                    .toException(STORAGE_FAILURE_STATUS, logicalFileName, OPERATION_WRITE, cause)
                    .orElseGet(() -> new FileAccessException(
                            message,
                            FileStatus.renderIoStatus04ForDiagnostics(STORAGE_FAILURE_STATUS),
                            logicalFileName,
                            OPERATION_WRITE,
                            cause));
        }
    }

    /**
     * Right-pads a value with spaces to an exact width.
     *
     * @param value the value, never {@code null}
     * @param width the target width
     * @return the padded value
     */
    private static String padRight(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /**
     * Renders an identity-only description. A statement writer's buffers hold customer name, address,
     * balance and credit score, so this deliberately exposes none of them.
     *
     * @return a fixed description naming only the counts and the month
     */
    @Override
    public String toString() {
        return "StatementWriter[statementsWritten=" + this.statementsWritten
                + ", month=" + this.stepStatementMonth
                + ", open=" + this.outputsOpen + "]";
    }

    /**
     * Reports the thirty-four fixed markup fragments the statement document is built from.
     *
     * <p>Delegates to {@link StatementProcessor#htmlFragments()}, which is the single definition site.
     * Declaring the table here as well would let a change to one copy produce a statement whose text and
     * markup disagreed; the delegation is what keeps exactly one table (Rule 1 Clause C).
     *
     * @return the unmodifiable fragment table, in source declaration order
     */
    public Map<String, String> htmlFragments() {
        return StatementProcessor.htmlFragments();
    }

    /**
     * Reports the two record widths this writer enforces, so a test or a diagnostic can assert them
     * without reaching into the DTO.
     *
     * @return the text width then the HTML width, in that order
     */
    public List<Integer> recordWidths() {
        return List.of(Integer.valueOf(TEXT_RECORD_LENGTH), Integer.valueOf(HTML_RECORD_LENGTH));
    }
}
