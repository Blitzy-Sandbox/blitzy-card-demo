/*
 * Program     : StatementWriter.java
 * Application : CardDemo
 * Type        : Spring Batch ItemWriter
 * Function    : Emits the 80-byte text statement and the 100-byte HTML statement.
 * Source      : app/jcl/CREASTMT.JCL STEP040 :L79 (STMTFILE LRECL=80 :L89, HTMLFILE LRECL=100 :L94);
 *               app/cbl/CBSTM03A.CBL :L45 FD-STMTFILE-REC X(80), :L47 FD-HTMLFILE-REC X(100),
 *               :L149 HTML-FIXED-LN X(100) (26 paragraphs, 924 lines);
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Emits the two account-statement outputs of the z/OS statement-generation job, byte-exactly.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the output side of {@code app/jcl/CREASTMT.JCL} STEP040 ({@code :L79}), the step that runs
 * {@code app/cbl/CBSTM03A.CBL}. That step produces <strong>two</strong> datasets, and their record
 * geometries differ:
 *
 * <ul>
 *   <li><strong>{@code STMTFILE}</strong> - the plain-text statement, {@code RECFM=FB} at
 *       <strong>exactly 80 bytes per record</strong>. Declared twice in the corpus:
 *       {@code app/jcl/CREASTMT.JCL:L89} {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} and
 *       {@code app/cbl/CBSTM03A.CBL:L45} {@code 01 FD-STMTFILE-REC PIC X(80).}. Its dataset name is
 *       {@code AWS.M2.CARDDEMO.STATEMNT.PS} ({@code app/jcl/CREASTMT.JCL:L91}).</li>
 *   <li><strong>{@code HTMLFILE}</strong> - the HTML statement, {@code RECFM=FB} at
 *       <strong>exactly 100 bytes per record</strong>. Declared three times in the corpus:
 *       {@code app/jcl/CREASTMT.JCL:L94} {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)},
 *       {@code app/cbl/CBSTM03A.CBL:L47} {@code 01 FD-HTMLFILE-REC PIC X(100).} and
 *       {@code app/cbl/CBSTM03A.CBL:L149} {@code 05 HTML-FIXED-LN PIC X(100).} - the single field every
 *       markup fragment is moved into before being written. Its dataset name is
 *       {@code AWS.M2.CARDDEMO.STATEMNT.HTML} ({@code app/jcl/CREASTMT.JCL:L96}).</li>
 * </ul>
 *
 * <p><strong>The two widths are never harmonised.</strong> Every text record is validated to be exactly
 * {@value #TEXT_RECORD_LENGTH} characters and every HTML record to be exactly
 * {@value #HTML_RECORD_LENGTH} characters <em>before</em> it is appended, and a record that is longer than
 * its declared width is rejected rather than silently truncated. Because both datasets are
 * {@code RECFM=FB} - fixed blocked, with no record delimiters stored in the dataset - the records are
 * concatenated with <strong>no separator</strong>, so the finished text object size is always an exact
 * multiple of {@value #TEXT_RECORD_LENGTH} and the HTML object size an exact multiple of
 * {@value #HTML_RECORD_LENGTH}.
 *
 * <p>Every citation in this file is keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}. Note the case of the source
 * members: {@code app/jcl/CREASTMT.JCL}, {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL} and
 * {@code app/cpy/COSTM01.CPY} are all uppercase and all CRLF-terminated. {@code app/jcl} holds
 * <strong>29</strong> members and {@code CREASTMT.JCL} is the only one with an uppercase extension, so a
 * glob of {@code app/jcl/*.jcl} silently drops it - and with it the sole source for statement generation.
 *
 * <h2>Scope boundaries</h2>
 *
 * <p><strong>This class bypasses the shared file service for its output and owns its own emission.</strong>
 * The two statement datasets are {@code SELECT}ed and {@code FD}'d inside {@code CBSTM03A.CBL} itself -
 * {@code SELECT STMT-FILE ASSIGN TO STMTFILE.} at {@code :L39}, {@code SELECT HTML-FILE ASSIGN TO
 * HTMLFILE.} at {@code :L40}, {@code FD STMT-FILE.} at {@code :L44} and {@code FD HTML-FILE.} at
 * {@code :L46} - so the program writes them directly. They are <strong>not</strong> among the four
 * read-only DD names that {@code CBSTM03B.CBL}, and therefore
 * {@code com.cardemo.service.shared.FileStatusMapper}'s sibling
 * {@code com.cardemo.service.shared.FileService}, serves: {@code TRNXFILE}, {@code XREFFILE},
 * {@code ACCTFILE} and {@code CUSTFILE}, listed at {@code app/jcl/CREASTMT.JCL:L83-L86}. The statement job
 * separately <em>reads</em> the transaction cluster through that service; the bypass is output-side only.
 *
 * <p>Three geometries deliberately live elsewhere and are never emitted here. The <strong>133-byte</strong>
 * report line of {@code app/cpy/CVTRA07Y.cpy} belongs to
 * {@code com.cardemo.batch.processors.TransactionReportProcessor}; the <strong>430-byte</strong> reject
 * record and the <strong>350-byte</strong> transaction image belong to the sibling writers in this package.
 * There is no shared fixed-width codec between the three: each owns its own emission, which is deliberate
 * and is what keeps this package to exactly three classes.
 *
 * <p>The transaction record's originating and processing time stamps pass through this class untouched and
 * remain <strong>26-byte text</strong>. They are character fields, never a date-time object, and nothing
 * here parses, reformats or re-renders them. The statement layouts of {@code :L85-L146} carry no such field
 * at all, so there is no conversion for this class to get wrong.
 *
 * <h2>Paragraph correspondence</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} is 924 lines and carries 26 paragraph labels. The emission-side labels
 * are mapped one-to-one onto private methods of this class, each carrying its own source citation:
 *
 * <table border="1">
 *   <caption>Emission-side paragraph map</caption>
 *   <tr><th>COBOL paragraph</th><th>Locator</th><th>Private method</th></tr>
 *   <tr><td>{@code 5000-CREATE-STATEMENT}</td><td>{@code :L458-L504}</td>
 *       <td>{@link #create5000Statement}</td></tr>
 *   <tr><td>{@code 5100-WRITE-HTML-HEADER}</td><td>{@code :L506-L552}</td>
 *       <td>{@link #write5100HtmlHeader}</td></tr>
 *   <tr><td>{@code 5100-EXIT}</td><td>{@code :L554-L555}</td><td>{@link #exit5100}</td></tr>
 *   <tr><td>{@code 5200-WRITE-HTML-NMADBS}</td><td>{@code :L558-L669}</td>
 *       <td>{@link #write5200HtmlNameAddressBasics}</td></tr>
 *   <tr><td>{@code 5200-EXIT}</td><td>{@code :L671-L672}</td><td>{@link #exit5200}</td></tr>
 *   <tr><td>{@code 6000-WRITE-TRANS}</td><td>{@code :L675-L723}</td>
 *       <td>{@link #write6000Trans}</td></tr>
 *   <tr><td>{@code 4000-TRNXFILE-GET} (emission side only)</td><td>{@code :L433-L454}</td>
 *       <td>{@link #emit4000TrnxFileTotals}</td></tr>
 * </table>
 *
 * <p>The {@code 5100}/{@code 5100-EXIT} and {@code 5200}/{@code 5200-EXIT} paired exit idiom is
 * reproduced as separate methods and is <strong>never collapsed</strong>: the source performs each with
 * {@code PERFORM ... THRU ...-EXIT} ({@code :L461} and {@code :L486}), so the exit paragraph is part of
 * the executed range. Only the emission half of {@code 4000-TRNXFILE-GET} lives here; its in-memory table
 * walk ({@code :L417-L432}) has no counterpart in this class, because the Java pipeline streams the
 * transactions into {@link #write(Chunk)} instead of indexing a fixed table.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and test with the pinned wrapper from the repository root: {@code ./mvnw -B clean test} for the
 * unit tier and {@code ./mvnw -B clean verify} for the full gate. The toolchain is Java 25 with
 * {@code maven.compiler.release} 25, no preview features, and {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so this file must compile warning-free. At run time the bean is driven by the
 * statement-generation job: the job opens the per-account outputs, writes the header, streams the
 * transaction chunks through {@link #write(Chunk)}, writes the totals footer, and closes the outputs.
 * Object storage is reached through Spring Cloud AWS against LocalStack in the {@code local} and
 * {@code test} profiles; bring the stack up with {@code docker compose up -d}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Configuration read by this component</caption>
 *   <tr><th>Property</th><th>Environment variable</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code carddemo.aws.s3.statements-bucket}</td><td>{@code CARDDEMO_S3_STATEMENTS_BUCKET}</td>
 *       <td>{@code carddemo-statements}</td>
 *       <td>Bucket that receives both statement objects. The default matches
 *           {@code .env.example:L82}, {@code docker-compose.yml:L99} and
 *           {@code localstack-init/init-aws.sh:L531}, so a developer stack needs no override. A bucket
 *           name is an identity, not a credential; no secret is defaulted anywhere in this class.</td></tr>
 * </table>
 *
 * <p>No other configuration is read. This class never reads an environment variable directly, never constructs a
 * storage client, and never names an endpoint or a credential: the client is injected and the LocalStack
 * endpoint override lives only in the {@code local} and {@code test} profiles, so no live-AWS path is
 * structurally reachable from here.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <table border="1">
 *   <caption>Failure modes</caption>
 *   <tr><th>Symptom</th><th>Exception</th><th>What to check</th></tr>
 *   <tr><td>A composed record is longer than its declared width</td>
 *       <td>{@code com.cardemo.exception.FatalProcessingException} (abend
 *           {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, return code
 *           {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE})</td>
 *       <td>A caller supplied an over-length line. The diagnostic reports the logical file name, the
 *           expected width and the observed length, and <strong>never the record content</strong>,
 *           because statement records carry customer data.</td></tr>
 *   <tr><td>A line is written while no statement output is open</td>
 *       <td>{@code com.cardemo.exception.FatalProcessingException}</td>
 *       <td>{@link #openStatementOutputs(String, String)} must precede every emission, mirroring
 *           {@code OPEN OUTPUT STMT-FILE HTML-FILE} at {@code app/cbl/CBSTM03A.CBL:L293}.</td></tr>
 *   <tr><td>The upload is rejected by object storage</td>
 *       <td>{@code com.cardemo.exception.FileAccessException} carrying {@code STMTFILE} or
 *           {@code HTMLFILE} and the attempted operation</td>
 *       <td>The bucket must exist and be writable. Run {@code localstack-init/init-aws.sh}, which is
 *           idempotent, and confirm the bucket name resolved from the property above.</td></tr>
 *   <tr><td>The statement object size is not a multiple of its record length</td>
 *       <td>None - this cannot happen</td>
 *       <td>Every record is length-validated before being appended and the charset is byte-transparent,
 *           so the invariant is established at the point of entry rather than checked at the end.</td></tr>
 * </table>
 *
 * <h2>Findings recorded against this component (Rule 1 clause F)</h2>
 *
 * <p>Every finding below is evidence-based, severity-classified and carries its remediation. None of them
 * is repaired in code: the legacy behaviour is the parity contract.
 *
 * <ol>
 *   <li><strong>Medium - the 80-versus-100 {@code HTMLFILE} record-length contradiction.</strong> The
 *       pre-delete step declares {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} for {@code HTMLFILE}
 *       ({@code app/jcl/CREASTMT.JCL:L69}, inside {@code STEP030 EXEC PGM=IEFBR14} at {@code :L66}) while
 *       the step that actually writes the dataset declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} ({@code :L94}).
 *       <em>Resolution:</em> <strong>100 is correct</strong>, on the triple confirmation of
 *       {@code CREASTMT.JCL:L94}, {@code CBSTM03A.CBL:L47} and {@code CBSTM03A.CBL:L149}. The 80 at
 *       {@code :L69} is a defect in a step that only deletes the dataset and therefore never writes a
 *       record. <em>Remediation:</em> none applied - the widths are <strong>not</strong> harmonised, and
 *       the contradiction is recorded in {@code DECISION_LOG.md}.</li>
 *   <li><strong>Low - the corrupted {@code STMTFILE} DD continuation.</strong>
 *       {@code app/jcl/CREASTMT.JCL:L90} reads, verbatim,
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - a botched paste that
 *       left the fragments {@code 00,RECFM=FB)} and {@code ATA.VSAM.KSDS} inside the continuation.
 *       <em>What the line was meant to say is</em> <strong>{@code Not available}</strong>. The
 *       prerequisite for recovering it would be a pre-corruption revision of the member, and no such
 *       revision exists at {@code 7756d89}. <em>Remediation:</em> the line is quoted here and in
 *       {@code DECISION_LOG.md} and is <strong>never reconstructed</strong>; the surrounding
 *       {@code :L89} and {@code :L91} clauses are unaffected and supply the record length and the
 *       dataset name.</li>
 *   <li><strong>Medium - the upstream projection truncates two timestamp bytes.</strong>
 *       {@code app/jcl/CREASTMT.JCL:L54} {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} copies only
 *       50 bytes from offset 279, which is the whole 26-byte originating timestamp plus the
 *       <strong>first 24 of the 26</strong> processing-timestamp bytes, and drops the 20-byte trailing
 *       filler entirely - 328 bytes written into a 350-byte record. The projected processing timestamp
 *       therefore arrives as a 24-character value padded to 26. <em>Resolution:</em> that truncation is
 *       produced by the job's in-job projection, not by this writer. <em>Remediation:</em> none - the
 *       value is consumed exactly as received and is <strong>never repaired</strong>.</li>
 *   <li><strong>Low - {@code HTML-LTDS} is declared but never activated.</strong> The condition name
 *       {@code 88 HTML-LTDS VALUE '&lt;td&gt;'} is declared at {@code app/cbl/CBSTM03A.CBL:L161}, yet a
 *       census of the procedure division finds <strong>no</strong> {@code SET HTML-LTDS TO TRUE}
 *       anywhere: 33 of the 34 declared fragments are activated, across 64 {@code SET} statements.
 *       <em>Resolution:</em> it is a declared-but-unactivated artefact of the source, not an omission
 *       here. <em>Remediation:</em> the fragment is <strong>retained</strong> in
 *       {@link #htmlFragments()} so the fragment table matches the source declaration count exactly. It
 *       is deliberately not deleted; deleting it would make the table diverge from
 *       {@code :L148-L211} and would break the fragment census the coverage gate reads.</li>
 *   <li><strong>Medium - the legacy 510-transaction ceiling is removed.</strong>
 *       {@code app/cbl/CBSTM03A.CBL:L225-L233} declares {@code WS-CARD-TBL OCCURS 51 TIMES} each holding
 *       {@code WS-TRAN-TBL OCCURS 10 TIMES}, a hard ceiling of
 *       {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions
 *       per run, and the table-building loop increments both subscripts with no bounds check at all.
 *       <em>Resolution:</em> this is a <strong>labelled deviation, not parity</strong>. Java streams the
 *       transactions chunk by chunk, so the ceiling and its unguarded storage-overrun hazard are both
 *       gone. <em>Remediation:</em> justified in writing in {@code DECISION_LOG.md} with the historical
 *       limit recorded in {@code TRACEABILITY_MATRIX.md}. The ceiling is <strong>not</strong> claimed to
 *       have been preserved.</li>
 *   <li><strong>Medium - the object-storage record-framing convention is unspecified.</strong> The corpus
 *       defines {@code RECFM=FB} for both datasets but specifies no mainframe-to-object-storage transfer
 *       convention, so whether a downstream consumer expects newline-delimited output is
 *       <strong>{@code Not available}</strong>. The prerequisite is an explicit transfer specification,
 *       and none exists at {@code 7756d89}. <em>Remediation:</em> this class emits undelimited
 *       fixed-length records, which is what {@code RECFM=FB} means. A newline-delimited variant would be
 *       a labelled deviation requiring a {@code DECISION_LOG.md} entry, never a silent change.</li>
 *   <li><strong>Low - no service-level objective exists to assert against.</strong> The corpus publishes
 *       no throughput or latency target for statement generation, so any threshold would be invented.
 *       The required figure is <strong>{@code Not available}</strong>; the prerequisite is a stated
 *       objective, which the source does not contain. <em>Remediation:</em> the performance gate records
 *       a <em>measured baseline</em> rather than a pass-or-fail threshold.</li>
 *   <li><strong>Medium - the "millisecond precision followed by four zeros" description of the batch
 *       timestamp is arithmetically impossible.</strong> Millisecond precision is three digits, so three
 *       plus four zeros is <strong>27</strong> characters, whereas the field is 26. The verified layout is
 *       {@code DB2-MIL PIC 9(002)} followed by {@code DB2-REST PIC X(04)}
 *       ({@code app/cbl/CBTRN02C.cbl:L170-L174}, generated at {@code :L701}), that is
 *       <strong>centisecond</strong> precision - two digits - plus four zeros, giving the pattern
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}, which sums to exactly 26. <em>Resolution:</em> the 26-character
 *       layout in the source governs. <em>Remediation:</em> recorded in {@code DECISION_LOG.md}. This class
 *       is unaffected because it emits no timestamp, but the description would produce a one-byte-wide
 *       divergence in any component that generated one from it.</li>
 *   <li><strong>Low - the HTML declares a character set that does not govern the record encoding.</strong>
 *       The fragment at {@code app/cbl/CBSTM03A.CBL:L153} emits {@code <meta charset="utf-8">} as document
 *       <em>content</em>. That is a statement about how a browser should interpret the markup, and it has
 *       <strong>no bearing</strong> on the encoding of the {@code RECFM=FB} record that carries it.
 *       <em>Resolution:</em> the record encoding is {@link #RECORD_CHARSET}, chosen because it is
 *       byte-transparent, so a 100-character line is exactly 100 bytes. <em>Remediation:</em> none needed -
 *       the fragment is emitted verbatim and the two concerns are simply distinct. Encoding the records as
 *       UTF-8 to "match" the declaration would silently break the geometry the moment any byte above
 *       {@code 0x7F} appeared.</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 *
 * <p>The per-account buffers are <strong>instance</strong> state; this class declares no mutable static
 * field. Its public mutating methods are synchronised on the instance, so a singleton bean driven by a
 * single-threaded step behaves deterministically and a multi-threaded step serialises rather than
 * interleaving records - interleaving would corrupt the record geometry. The fragment and line tables are
 * {@code private static final} and unmodifiable, which is a constant, not mutable state.
 *
 * @see StatementTransaction
 */
@Component
public final class StatementWriter implements ItemWriter<StatementTransaction>, StepExecutionListener {

    /** Logger. Statement content is never logged: see the security note on {@link #writeStatementLine}. */
    private static final Logger LOG = LoggerFactory.getLogger(StatementWriter.class);

    // ---------------------------------------------------------------------------------------------
    // Record geometry. The two widths are distinct and are never harmonised.
    // ---------------------------------------------------------------------------------------------

    /**
     * Length of every text statement record, {@value}. Declared by {@code app/jcl/CREASTMT.JCL:L89}
     * {@code DCB=(LRECL=80,...)} and by {@code app/cbl/CBSTM03A.CBL:L45}
     * {@code 01 FD-STMTFILE-REC PIC X(80).}. Sourced from the single owning constant on
     * {@link StatementTransaction} rather than restated, so the two files cannot drift apart.
     */
    private static final int TEXT_RECORD_LENGTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /**
     * Length of every HTML statement record, {@value}. Triply declared by
     * {@code app/jcl/CREASTMT.JCL:L94} {@code DCB=(LRECL=100,...)}, by
     * {@code app/cbl/CBSTM03A.CBL:L47} {@code 01 FD-HTMLFILE-REC PIC X(100).} and by
     * {@code app/cbl/CBSTM03A.CBL:L149} {@code 05 HTML-FIXED-LN PIC X(100).}. Sourced from the single
     * owning constant on {@link StatementTransaction}.
     */
    private static final int HTML_RECORD_LENGTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /**
     * The charset every record is encoded with, on every write, without exception.
     *
     * <p>ISO-8859-1 is chosen because it is <strong>byte-transparent</strong>: each character in
     * {@code U+0000}-{@code U+00FF} encodes to exactly one byte, and any character outside that range is
     * replaced by a single {@code '?'} byte. The character count of a record is therefore always its byte
     * count, which is what makes an {@value #TEXT_RECORD_LENGTH}-character line exactly
     * {@value #TEXT_RECORD_LENGTH} bytes and an {@value #HTML_RECORD_LENGTH}-character line exactly
     * {@value #HTML_RECORD_LENGTH} bytes.
     *
     * <p>UTF-8 is <strong>not</strong> usable here: a single character above {@code U+007F} would encode
     * to two or more bytes and silently destroy the fixed-record geometry. The platform default is not
     * usable either, because it is not deterministic across hosts. Note the irony worth recording: the
     * HTML fragment at {@code app/cbl/CBSTM03A.CBL:L153} declares {@code charset="utf-8"} as document
     * <em>content</em>, which says nothing about - and does not change - the encoding of the
     * {@value #HTML_RECORD_LENGTH}-byte records that carry it.
     *
     * <p>No EBCDIC is ever produced. {@code app/data/EBCDIC/**} is codepage reference material only and
     * is neither parsed nor written by this build.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    // ---------------------------------------------------------------------------------------------
    // Logical file identities and diagnostics.
    // ---------------------------------------------------------------------------------------------

    /**
     * Logical name of the text statement output, from {@code SELECT STMT-FILE ASSIGN TO STMTFILE.} at
     * {@code app/cbl/CBSTM03A.CBL:L39} and the {@code //STMTFILE DD} statement at
     * {@code app/jcl/CREASTMT.JCL:L87}. Value {@value}.
     */
    public static final String STMTFILE_DD_NAME = "STMTFILE";

    /**
     * Logical name of the HTML statement output, from {@code SELECT HTML-FILE ASSIGN TO HTMLFILE.} at
     * {@code app/cbl/CBSTM03A.CBL:L40} and the {@code //HTMLFILE DD} statement at
     * {@code app/jcl/CREASTMT.JCL:L92}. Value {@value}.
     */
    public static final String HTMLFILE_DD_NAME = "HTMLFILE";

    /** The attempted operation reported on a failed emission, mirroring the COBOL {@code WRITE} verb. */
    private static final String OPERATION_WRITE = "WRITE";

    /** The attempted operation reported on a failed open, mirroring {@code OPEN OUTPUT} at {@code :L293}. */
    private static final String OPERATION_OPEN = "OPEN";

    /**
     * The file status an object-storage failure is reported as: a member of the {@code '9x'} family,
     * which {@code com.cardemo.service.shared.FileStatusMapper} maps to
     * {@code com.cardemo.exception.FileAccessException}. The leading byte is taken from the owning enum
     * rather than hardcoded, so the family definition lives in exactly one place.
     */
    private static final String STORAGE_FAILURE_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /**
     * The component reported in {@code ABEND-CULPRIT PIC X(8)} ({@code app/cpy/CSMSG02Y.cpy:L24}) when this
     * writer abends: the name of the originating program, exactly eight characters wide. Value {@value}.
     */
    private static final String ABEND_CULPRIT = "CBSTM03A";

    // ---------------------------------------------------------------------------------------------
    // Object storage: key composition and metadata.
    // ---------------------------------------------------------------------------------------------

    /** Root key segment for every statement object. */
    private static final String KEY_ROOT = "statements";

    /** Key segment carrying the account prefix required of every statement object. */
    private static final String KEY_ACCOUNT_SEGMENT = "account=";

    /** Key segment carrying the statement-month prefix required of every statement object. */
    private static final String KEY_MONTH_SEGMENT = "month=";

    /**
     * Key segment carrying the generation prefix that replaces a GDG relative reference. A
     * {@code (+1)} write becomes a new object under a strictly greater, zero-padded generation, so the
     * lexicographic order of the prefixes equals the numeric order of the generations.
     */
    private static final String KEY_GENERATION_SEGMENT = "generation=";

    /** Key separator. Object storage has no directories; the slash is a naming convention only. */
    private static final String KEY_SEPARATOR = "/";

    /**
     * Terminal key segment of the text object, mirroring the dataset name
     * {@code AWS.M2.CARDDEMO.STATEMNT.PS} at {@code app/jcl/CREASTMT.JCL:L91}. Value {@value}.
     */
    private static final String TEXT_OBJECT_NAME = "STATEMNT.PS";

    /**
     * Terminal key segment of the HTML object, mirroring the dataset name
     * {@code AWS.M2.CARDDEMO.STATEMNT.HTML} at {@code app/jcl/CREASTMT.JCL:L96}. Value {@value}.
     */
    private static final String HTML_OBJECT_NAME = "STATEMNT.HTML";

    /** Zero-padded width of the generation segment, wide enough that padding never truncates. */
    private static final int GENERATION_WIDTH = 12;

    /** Content type advertised for the text object. Advisory metadata; it never affects the bytes. */
    private static final String TEXT_CONTENT_TYPE = "text/plain";

    /** Content type advertised for the HTML object. Advisory metadata; it never affects the bytes. */
    private static final String HTML_CONTENT_TYPE = "text/html";

    /**
     * Step-execution-context key under which the created text object key is published. A later step reads
     * the concrete key from the context instead of re-resolving "the latest generation", which is what
     * makes a {@code (+1)} written earlier in a job readable as {@code (+1)} later in the same job.
     * Value {@value}.
     */
    public static final String CONTEXT_KEY_TEXT_OBJECT = "carddemo.statement.text.objectKey";

    /**
     * Step-execution-context key under which the created HTML object key is published. Value {@value}.
     */
    public static final String CONTEXT_KEY_HTML_OBJECT = "carddemo.statement.html.objectKey";

    /**
     * The one meter this component touches: the batch records-processed counter that replaces the legacy
     * end-of-run {@code DISPLAY} of counters. It is one of exactly four counters in the whole tree, is
     * registered without tags to match the dashboard expression
     * {@code sum(carddemo_batch_records_processed_total)}, and no fifth instrument is introduced.
     * Value {@value}.
     */
    private static final String RECORDS_PROCESSED_COUNTER = "carddemo.batch.records.processed";

    // ---------------------------------------------------------------------------------------------
    // The text statement layout, app/cbl/CBSTM03A.CBL :L85-L146. Seventeen ST-LINE* groups, and every
    // single one of them sums to exactly TEXT_RECORD_LENGTH. The arithmetic is stated per group.
    // ---------------------------------------------------------------------------------------------

    /** Width of {@code ST-NAME}, {@code PIC X(75)} at {@code app/cbl/CBSTM03A.CBL:L91}. */
    private static final int ST_NAME_WIDTH = 75;

    /** Width of {@code ST-ADD1}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L94}. */
    private static final int ST_ADD1_WIDTH = 50;

    /** Width of {@code ST-ADD2}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L97}. */
    private static final int ST_ADD2_WIDTH = 50;

    /** Width of {@code ST-ADD3}, {@code PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L100}. */
    private static final int ST_ADD3_WIDTH = 80;

    /** Width of {@code ST-ACCT-ID}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L109}. */
    private static final int ST_ACCT_ID_WIDTH = 20;

    /** Width of {@code ST-FICO-SCORE}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L118}. */
    private static final int ST_FICO_SCORE_WIDTH = 20;

    /** Width of {@code ST-TRANID}, {@code PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L133}. */
    private static final int ST_TRANID_WIDTH = 16;

    /** Width of {@code ST-TRANDT}, {@code PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135}. */
    private static final int ST_TRANDT_WIDTH = 49;

    /**
     * Rendered width of every edited money field on the statement, {@value}. Both mask shapes occupy the
     * same 13 positions: {@code PIC Z(9).99-} ({@code ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L137},
     * {@code ST-TOTAL-TRAMT} at {@code :L142}) and {@code PIC 9(9).99-} ({@code ST-CURR-BAL} at
     * {@code :L113}) are each nine integer positions, a decimal point, two decimal digits and one
     * <strong>trailing</strong> sign position.
     */
    private static final int EDITED_AMOUNT_WIDTH = 13;

    /** Integer digit positions in both money masks: the {@code Z(9)} and {@code 9(9)} runs. */
    private static final int EDITED_AMOUNT_INTEGER_DIGITS = 9;

    /**
     * {@code ST-LINE0}, {@code app/cbl/CBSTM03A.CBL:L86-L89}: 31 asterisks, the literal
     * {@code 'START OF STATEMENT'} in {@code PIC X(18)}, and 31 more asterisks. 31 + 18 + 31 = 80.
     */
    private static final String ST_LINE0 = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /**
     * The full-width rule, {@code FILLER VALUE ALL '-' PIC X(80)}. Three separate COBOL groups carry this
     * identical value, and each keeps its own named alias below so that the paragraph map stays provable.
     */
    private static final String RULE_LINE = "-".repeat(TEXT_RECORD_LENGTH);

    /** {@code ST-LINE5}, {@code app/cbl/CBSTM03A.CBL:L101-L102}. Written <strong>twice</strong> by 5000. */
    private static final String ST_LINE5 = RULE_LINE;

    /** {@code ST-LINE10}, {@code app/cbl/CBSTM03A.CBL:L120-L121}. */
    private static final String ST_LINE10 = RULE_LINE;

    /**
     * {@code ST-LINE12}, {@code app/cbl/CBSTM03A.CBL:L126-L127}. Written <strong>twice</strong> by 5000
     * and once more by the emission half of 4000.
     */
    private static final String ST_LINE12 = RULE_LINE;

    /**
     * {@code ST-LINE6}, {@code app/cbl/CBSTM03A.CBL:L103-L106}: 33 spaces, the literal
     * {@code 'Basic Details'} in {@code PIC X(14)} - 13 characters space-padded to 14 - and 33 spaces.
     * 33 + 14 + 33 = 80.
     */
    private static final String ST_LINE6 = " ".repeat(33) + padRight("Basic Details", 14) + " ".repeat(33);

    /** Label of {@code ST-LINE7}, {@code app/cbl/CBSTM03A.CBL:L108}, {@code PIC X(20)}. */
    private static final String ST_LINE7_LABEL = "Account ID         :";

    /** Label of {@code ST-LINE8}, {@code app/cbl/CBSTM03A.CBL:L112}, {@code PIC X(20)}. */
    private static final String ST_LINE8_LABEL = "Current Balance    :";

    /** Label of {@code ST-LINE9}, {@code app/cbl/CBSTM03A.CBL:L117}, {@code PIC X(20)}. */
    private static final String ST_LINE9_LABEL = "FICO Score         :";

    /**
     * {@code ST-LINE11}, {@code app/cbl/CBSTM03A.CBL:L122-L125}: 30 spaces, the literal
     * {@code 'TRANSACTION SUMMARY '} - already 20 characters, its trailing space included - and 30
     * spaces. 30 + 20 + 30 = 80.
     */
    private static final String ST_LINE11 = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

    /**
     * {@code ST-LINE13}, {@code app/cbl/CBSTM03A.CBL:L128-L131}: {@code 'Tran ID         '} in
     * {@code PIC X(16)}, {@code 'Tran Details    '} in {@code PIC X(51)} - 16 characters space-padded to
     * 51 - and {@code '  Tran Amount'} in {@code PIC X(13)}. 16 + 51 + 13 = 80.
     */
    private static final String ST_LINE13 =
            "Tran ID         " + padRight("Tran Details    ", 51) + "  Tran Amount";

    /** Label of {@code ST-LINE14A}, {@code app/cbl/CBSTM03A.CBL:L139}, {@code PIC X(10)}. */
    private static final String ST_LINE14A_LABEL = "Total EXP:";

    /**
     * {@code ST-LINE15}, {@code app/cbl/CBSTM03A.CBL:L143-L146}: 32 asterisks, the literal
     * {@code 'END OF STATEMENT'} in {@code PIC X(16)}, and 32 more asterisks. 32 + 16 + 32 = 80.
     */
    private static final String ST_LINE15 = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /**
     * The one-byte {@code FILLER VALUE '$'} that precedes both edited money fields on the transaction and
     * total lines, {@code app/cbl/CBSTM03A.CBL:L136} and {@code :L141}. It is a separate filler byte and
     * is deliberately <strong>not</strong> part of either money mask.
     */
    private static final String CURRENCY_FILLER = "$";

    // ---------------------------------------------------------------------------------------------
    // The HTML statement layout, app/cbl/CBSTM03A.CBL :L148-L211.
    //
    // The source declares 01 HTML-LINES (:L148) whose first child is the single field
    // 05 HTML-FIXED-LN PIC X(100) (:L149), followed by THIRTY-FOUR 88-level condition names, each
    // carrying one markup fragment as its VALUE. The COBOL idiom is: SET the condition name TRUE, then
    // WRITE the one 100-byte field. Every fragment below is byte-identical to its source literal.
    //
    // Fragments whose literal is continued across two source lines are reassembled here into a single
    // literal, because a COBOL continuation joins the two halves with NO inserted character: L08
    // (:L157-L158), L10 (:L163-L164), L15 (:L165-L166), L22-35 (:L173-L175), L30-42 (:L176-L178),
    // L47 (:L183-L185), L50 (:L188-L190), L53 (:L193-L195), L58 (:L198-L200), L61 (:L201-L203) and
    // L64 (:L204-L206). Note the two spaces after '<table' in L08 - they are in the source.
    // ---------------------------------------------------------------------------------------------

    /** {@code HTML-L01}, {@code app/cbl/CBSTM03A.CBL:L150}. Value {@code <!DOCTYPE html>}. */
    private static final String HTML_L01 = "<!DOCTYPE html>";

    /** {@code HTML-L02}, {@code app/cbl/CBSTM03A.CBL:L151}. Value {@code <html lang="en">}. */
    private static final String HTML_L02 = "<html lang=\"en\">";

    /** {@code HTML-L03}, {@code app/cbl/CBSTM03A.CBL:L152}. Value {@code <head>}. */
    private static final String HTML_L03 = "<head>";

    /**
     * {@code HTML-L04}, {@code app/cbl/CBSTM03A.CBL:L153}. Value {@code <meta charset="utf-8">}. This
     * declares the encoding of the document <em>content</em>; the records that carry it are encoded with
     * {@link #RECORD_CHARSET}, and the two are independent.
     */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";

    /** {@code HTML-L05}, {@code app/cbl/CBSTM03A.CBL:L154}. Value {@code <title>HTML Table Layout</title>}. */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";

    /** {@code HTML-L06}, {@code app/cbl/CBSTM03A.CBL:L155}. Value {@code </head>}. */
    private static final String HTML_L06 = "</head>";

    /** {@code HTML-L07}, {@code app/cbl/CBSTM03A.CBL:L156}. Value {@code <body style="margin:0px;">}. */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";

    /** {@code HTML-L08}, {@code app/cbl/CBSTM03A.CBL:L157-L158}, reassembled from the continuation. */
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    /** {@code HTML-LTRS}, {@code app/cbl/CBSTM03A.CBL:L159}. Value {@code <tr>}. */
    private static final String HTML_LTRS = "<tr>";

    /** {@code HTML-LTRE}, {@code app/cbl/CBSTM03A.CBL:L160}. Value {@code </tr>}. */
    private static final String HTML_LTRE = "</tr>";

    /**
     * {@code HTML-LTDS}, {@code app/cbl/CBSTM03A.CBL:L161}. Value {@code <td>}.
     *
     * <p><strong>Retained for parity.</strong> A census of the procedure division finds no
     * {@code SET HTML-LTDS TO TRUE} anywhere: 33 of the 34 declared fragments are activated, this one is
     * not. It is kept in {@link #htmlFragments()} so the fragment table matches the source declaration
     * count exactly, and it is deliberately not deleted. See finding 4 in the class documentation.
     */
    private static final String HTML_LTDS = "<td>";

    /** {@code HTML-LTDE}, {@code app/cbl/CBSTM03A.CBL:L162}. Value {@code </td>}. Activated 13 times. */
    private static final String HTML_LTDE = "</td>";

    /** {@code HTML-L10}, {@code app/cbl/CBSTM03A.CBL:L163-L164}, reassembled from the continuation. */
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    /** {@code HTML-L15}, {@code app/cbl/CBSTM03A.CBL:L165-L166}, reassembled from the continuation. */
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    /** {@code HTML-L16}, {@code app/cbl/CBSTM03A.CBL:L167-L168}. */
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    /** {@code HTML-L17}, {@code app/cbl/CBSTM03A.CBL:L169-L170}. Value {@code <p>410 Terry Ave N</p>}. */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";

    /** {@code HTML-L18}, {@code app/cbl/CBSTM03A.CBL:L171-L172}. Value {@code <p>Seattle WA 99999</p>}. */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";

    /** {@code HTML-L22-35}, {@code app/cbl/CBSTM03A.CBL:L173-L175}, reassembled. Activated twice. */
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    /** {@code HTML-L30-42}, {@code app/cbl/CBSTM03A.CBL:L176-L178}, reassembled. Activated twice. */
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

    /** {@code HTML-L31}, {@code app/cbl/CBSTM03A.CBL:L179-L180}. */
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";

    /** {@code HTML-L43}, {@code app/cbl/CBSTM03A.CBL:L181-L182}. */
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";

    /** {@code HTML-L47}, {@code app/cbl/CBSTM03A.CBL:L183-L185}, reassembled from the continuation. */
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    /** {@code HTML-L48}, {@code app/cbl/CBSTM03A.CBL:L186-L187}. */
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";

    /** {@code HTML-L50}, {@code app/cbl/CBSTM03A.CBL:L188-L190}, reassembled from the continuation. */
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    /** {@code HTML-L51}, {@code app/cbl/CBSTM03A.CBL:L191-L192}. */
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";

    /** {@code HTML-L53}, {@code app/cbl/CBSTM03A.CBL:L193-L195}, reassembled from the continuation. */
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";

    /** {@code HTML-L54}, {@code app/cbl/CBSTM03A.CBL:L196-L197}. */
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";

    /** {@code HTML-L58}, {@code app/cbl/CBSTM03A.CBL:L198-L200}, reassembled from the continuation. */
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    /** {@code HTML-L61}, {@code app/cbl/CBSTM03A.CBL:L201-L203}, reassembled from the continuation. */
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    /** {@code HTML-L64}, {@code app/cbl/CBSTM03A.CBL:L204-L206}, reassembled from the continuation. */
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    /** {@code HTML-L75}, {@code app/cbl/CBSTM03A.CBL:L207-L208}. Value {@code <h3>End of Statement</h3>}. */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";

    /** {@code HTML-L78}, {@code app/cbl/CBSTM03A.CBL:L209}. Value {@code </table>}. */
    private static final String HTML_L78 = "</table>";

    /** {@code HTML-L79}, {@code app/cbl/CBSTM03A.CBL:L210}. Value {@code </body>}. */
    private static final String HTML_L79 = "</body>";

    /** {@code HTML-L80}, {@code app/cbl/CBSTM03A.CBL:L211}. Value {@code </html>}. */
    private static final String HTML_L80 = "</html>";

    /**
     * The thirty-four fixed markup fragments of {@code 01 HTML-LINES}, keyed by their COBOL condition
     * names with hyphens rendered as underscores - {@code HTML_L01} through {@code HTML_L80}, plus
     * {@code HTML_LTRS}, {@code HTML_LTRE}, {@code HTML_LTDS} and {@code HTML_LTDE}.
     *
     * <p>The map is built in source-declaration order and wrapped unmodifiable, so iteration is
     * deterministic. An unmodifiable {@code static final} map is a constant, not mutable static state.
     */
    private static final Map<String, String> HTML_FRAGMENTS = buildHtmlFragments();

    // ---------------------------------------------------------------------------------------------
    // Composed HTML lines. These are NOT fixed fragments and are deliberately kept out of the fragment
    // map: the source builds each one at run time from customer or transaction data, either into the
    // group HTML-L11 / HTML-L23 (:L212-L220) or into one of the three PIC X(100) work fields
    // HTML-ADDR-LN, HTML-BSIC-LN and HTML-TRAN-LN (:L221-L223).
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@code PIC X(34)} filler that opens {@code HTML-L11}, {@code app/cbl/CBSTM03A.CBL:L213-L214}.
     * With {@code L11-ACCT PIC X(20)} ({@code :L215}) and the {@code PIC X(05)} closer ({@code :L216})
     * the group composes to 34 + 20 + 5 = 59 characters before it is padded to
     * {@value #HTML_RECORD_LENGTH}.
     */
    private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: ";

    /** The {@code PIC X(05)} filler that closes {@code HTML-L11}, {@code app/cbl/CBSTM03A.CBL:L216}. */
    private static final String HTML_L11_SUFFIX = "</h3>";

    /**
     * The {@code PIC X(26)} filler that opens {@code HTML-L23}, {@code app/cbl/CBSTM03A.CBL:L218-L219}.
     * With {@code L23-NAME PIC X(50)} ({@code :L220}) the group's <strong>declared width is 26 + 50 = 76</strong>.
     *
     * <p>That 76 is the group budget, <strong>not</strong> the length of the emitted record, and the
     * distinction matters. Unlike {@code HTML-L11}, which {@code :L530} writes directly as a group and which
     * therefore always emits its full 59 declared characters, {@code HTML-L23} is <strong>never written</strong>:
     * {@code :L560} moves the name into its {@code L23-NAME} subfield and {@code :L561-L568} then compose a
     * separate record into {@code FD-HTMLFILE-REC} by {@code STRING}, taking {@code L23-NAME DELIMITED BY '  '}.
     * The emitted content is therefore 26 characters of prefix, plus as much of the 50-character name field as
     * precedes its first pair of consecutive spaces, plus two spaces and the four character closing tag - so it
     * reaches the full 76 character field budget only when the name occupies all 50 positions without an
     * internal pair of consecutive spaces, and is shorter for every ordinary name. Padding to
     * {@value #HTML_RECORD_LENGTH} happens on write either way.
     */
    private static final String HTML_L23_PREFIX = "<p style=\"font-size:16px\">";

    /** Width of {@code L11-ACCT}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L215}. */
    private static final int HTML_L11_ACCT_WIDTH = 20;

    /** Width of {@code L23-NAME}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L220}. */
    private static final int HTML_L23_NAME_WIDTH = 50;

    /** Opening paragraph tag used by the {@code STRING} statements that build the composed lines. */
    private static final String PARAGRAPH_OPEN = "<p>";

    /** Closing paragraph tag used by the {@code STRING} statements that build the composed lines. */
    private static final String PARAGRAPH_CLOSE = "</p>";

    /** {@code HTML-BSIC-LN} account label, {@code app/cbl/CBSTM03A.CBL:L614}. 24 characters. */
    private static final String HTML_BSIC_ACCOUNT_LABEL = "<p>Account ID         : ";

    /** {@code HTML-BSIC-LN} balance label, {@code app/cbl/CBSTM03A.CBL:L621}. 24 characters. */
    private static final String HTML_BSIC_BALANCE_LABEL = "<p>Current Balance    : ";

    /** {@code HTML-BSIC-LN} FICO label, {@code app/cbl/CBSTM03A.CBL:L628}. 24 characters. */
    private static final String HTML_BSIC_FICO_LABEL = "<p>FICO Score         : ";

    /**
     * The two-space delimiter of the {@code STRING ... DELIMITED BY '  '} clauses at
     * {@code app/cbl/CBSTM03A.CBL:L563}, {@code :L571}, {@code :L579} and {@code :L587}, and the literal
     * {@code '  ' DELIMITED BY SIZE} that follows each of them.
     */
    private static final String DOUBLE_SPACE = "  ";

    /** The single-space delimiter of the {@code STRING} clauses at {@code :L462-L481}. */
    private static final String SINGLE_SPACE = " ";

    // ---------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection only; there is no setter and no static mutable
    // field anywhere in this class.
    // ---------------------------------------------------------------------------------------------

    /** Object-storage client. Injected; never constructed here, and never pointed at an endpoint here. */
    private final S3Template s3Template;

    /** The sole owner of the file-status-to-exception decision. Every write failure is routed through it. */
    private final FileStatusMapper fileStatusMapper;

    /** The batch records-processed counter. One of exactly four counters tree-wide; registered untagged. */
    private final Counter recordsProcessedCounter;

    /** Bucket that receives both statement objects, resolved from configuration. */
    private final String statementsBucket;

    // ---------------------------------------------------------------------------------------------
    // Per-account output state. INSTANCE state, guarded by this instance's monitor. The two builders
    // stand in for the two open datasets of OPEN OUTPUT STMT-FILE HTML-FILE, app/cbl/CBSTM03A.CBL:L293.
    // ---------------------------------------------------------------------------------------------

    /** Accumulated text records, each exactly {@value #TEXT_RECORD_LENGTH} characters, undelimited. */
    private final StringBuilder textRecords = new StringBuilder();

    /** Accumulated HTML records, each exactly {@value #HTML_RECORD_LENGTH} characters, undelimited. */
    private final StringBuilder htmlRecords = new StringBuilder();

    /** Account identifier of the statement currently open, or {@code null} when no statement is open. */
    private String currentAccountId;

    /** Statement month of the statement currently open, or {@code null} when no statement is open. */
    private String currentStatementMonth;

    /** Generation prefix of the statement currently open. */
    private long currentGeneration;

    /** Whether a statement is open for emission. */
    private boolean outputsOpen;

    /** Whether the currently open statement has already been uploaded, making a further flush a no-op. */
    private boolean outputsFlushed;

    /** Key of the most recently created text object, or {@code null} before the first flush. */
    private String textObjectKey;

    /** Key of the most recently created HTML object, or {@code null} before the first flush. */
    private String htmlObjectKey;

    /**
     * Generation supplied by the enclosing job, captured in {@link #beforeStep(StepExecution)} from the
     * job instance identifier. Job instance identifiers increase monotonically, which is exactly the
     * property a GDG {@code (+1)} reference needs. Zero when no step has begun, which is the case when
     * the bean is driven directly rather than by a job.
     */
    private long jobInstanceGeneration;

    /**
     * Groups the per-account header data that {@code 5000-CREATE-STATEMENT} moves onto the statement, so
     * that {@link #writeStatementHeader(StatementHeader)} takes one meaningful argument instead of twelve
     * positional ones.
     *
     * <p>It is declared as a nested record deliberately: this file must declare exactly one top-level
     * type, and {@link StatementTransaction} establishes the same nested-record convention for its own
     * grouped projections. Every component may be {@code null}; a null text field is rendered as spaces
     * and a null balance as zero, matching what {@code INITIALIZE STATEMENT-LINES}
     * ({@code app/cbl/CBSTM03A.CBL:L459}) leaves behind for alphanumeric and numeric-edited items
     * respectively.
     *
     * @param customerFirstName {@code CUST-FIRST-NAME}, consumed by the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L462}
     * @param customerMiddleName {@code CUST-MIDDLE-NAME}, {@code app/cbl/CBSTM03A.CBL:L464}
     * @param customerLastName {@code CUST-LAST-NAME}, {@code app/cbl/CBSTM03A.CBL:L466}
     * @param addressLine1 {@code CUST-ADDR-LINE-1}, moved to {@code ST-ADD1} at
     * {@code app/cbl/CBSTM03A.CBL:L470}
     * @param addressLine2 {@code CUST-ADDR-LINE-2}, moved to {@code ST-ADD2} at
     * {@code app/cbl/CBSTM03A.CBL:L471}
     * @param addressLine3 {@code CUST-ADDR-LINE-3}, consumed by the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L472}
     * @param addressStateCode {@code CUST-ADDR-STATE-CD}, {@code app/cbl/CBSTM03A.CBL:L474}
     * @param addressCountryCode {@code CUST-ADDR-COUNTRY-CD}, {@code app/cbl/CBSTM03A.CBL:L476}
     * @param addressZip {@code CUST-ADDR-ZIP}, {@code app/cbl/CBSTM03A.CBL:L478}
     * @param accountId {@code ACCT-ID}, moved to {@code ST-ACCT-ID} at
     * {@code app/cbl/CBSTM03A.CBL:L483} and to {@code L11-ACCT} at {@code :L529}
     * @param currentBalance {@code ACCT-CURR-BAL}, moved to {@code ST-CURR-BAL} at
     * {@code app/cbl/CBSTM03A.CBL:L484}. A decimal value; no binary floating point is used anywhere on
     * this path
     * @param ficoScore {@code CUST-FICO-CREDIT-SCORE}, moved to {@code ST-FICO-SCORE} at
     * {@code app/cbl/CBSTM03A.CBL:L485}
     */
    public record StatementHeader(
            String customerFirstName,
            String customerMiddleName,
            String customerLastName,
            String addressLine1,
            String addressLine2,
            String addressLine3,
            String addressStateCode,
            String addressCountryCode,
            String addressZip,
            String accountId,
            BigDecimal currentBalance,
            String ficoScore) {

        /**
         * Renders an identity-only description. Statement headers carry customer name, address, balance
         * and credit score, so this deliberately exposes <strong>none</strong> of them: the fields belong
         * in the statement object, never in a log line or an exception message.
         *
         * @return a fixed, data-free description
         */
        @Override
        public String toString() {
            return "StatementHeader[fields withheld]";
        }
    }

    /**
     * Creates the writer.
     *
     * @param s3Template the injected object-storage client; must not be {@code null}. No client is ever
     * constructed here and no endpoint or credential is ever named here
     * @param meterRegistry the injected meter registry; must not be {@code null}. Exactly one counter is
     * derived from it, {@value #RECORDS_PROCESSED_COUNTER}, registered without tags
     * @param fileStatusMapper the injected status mapper that owns the status-to-exception decision; must
     * not be {@code null}
     * @param statementsBucket the bucket that receives both statement objects, from
     * {@code carddemo.aws.s3.statements-bucket} (environment {@code CARDDEMO_S3_STATEMENTS_BUCKET},
     * default {@code carddemo-statements}); must not be {@code null} or blank
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if the bucket name is blank
     */
    public StatementWriter(
            S3Template s3Template,
            MeterRegistry meterRegistry,
            FileStatusMapper fileStatusMapper,
            @Value("${carddemo.aws.s3.statements-bucket:carddemo-statements}") String statementsBucket) {
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        Objects.requireNonNull(statementsBucket, "statementsBucket must not be null");
        if (statementsBucket.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.statements-bucket must not be blank; "
                            + "set CARDDEMO_S3_STATEMENTS_BUCKET");
        }
        this.statementsBucket = statementsBucket;
        this.recordsProcessedCounter = meterRegistry.counter(RECORDS_PROCESSED_COUNTER);
    }

    // =============================================================================================
    // Public API - the per-account output lifecycle.
    // =============================================================================================

    /**
     * Opens the two per-account statement outputs, using the generation captured from the enclosing job.
     *
     * <p>This is the Java counterpart of {@code OPEN OUTPUT STMT-FILE HTML-FILE} at
     * {@code app/cbl/CBSTM03A.CBL:L293}. It buffers rather than holding two dataset handles, because the
     * targets are objects and an object is created whole.
     *
     * @param accountId the account the statement belongs to; must not be {@code null} or blank. It becomes
     * the account prefix of both object keys
     * @param statementMonth the statement month, conventionally {@code yyyy-MM}; must not be {@code null}
     * or blank. It becomes the month prefix of both object keys
     * @throws IllegalArgumentException if either argument is {@code null} or blank
     * @throws com.cardemo.exception.FatalProcessingException if a statement is already open, which would
     * mean an earlier statement was never closed and its records would leak into this one
     */
    public synchronized void openStatementOutputs(String accountId, String statementMonth) {
        openStatementOutputs(accountId, statementMonth, this.jobInstanceGeneration);
    }

    /**
     * Opens the two per-account statement outputs under an explicit generation.
     *
     * <p>The generation implements the GDG translation. A {@code (+1)} relative reference becomes a new
     * object under a strictly greater, zero-padded generation segment, so the lexicographic order of the
     * keys equals the numeric order of the generations; a {@code (0)} reference is served by reading the
     * concrete key this writer publishes into the step execution context, never by re-resolving "the
     * latest generation", which is what allows a {@code (+1)} written by an earlier step to be read as
     * {@code (+1)} by a later step in the same job. Retention is documented rather than enforced: no
     * lifecycle rule is created here.
     *
     * @param accountId the account the statement belongs to; must not be {@code null} or blank
     * @param statementMonth the statement month, conventionally {@code yyyy-MM}; must not be {@code null}
     * or blank
     * @param generation the generation to write under; must not be negative
     * @throws IllegalArgumentException if either identifier is {@code null} or blank, or the generation is
     * negative
     * @throws com.cardemo.exception.FatalProcessingException if a statement is already open
     */
    public synchronized void openStatementOutputs(String accountId, String statementMonth, long generation) {
        requireText(accountId, "accountId");
        requireText(statementMonth, "statementMonth");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative but was " + generation);
        }
        if (this.outputsOpen) {
            throw abend("a statement for another account is still open; close it before opening the next",
                    STMTFILE_DD_NAME, OPERATION_OPEN);
        }
        this.textRecords.setLength(0);
        this.htmlRecords.setLength(0);
        this.currentAccountId = accountId;
        this.currentStatementMonth = statementMonth;
        this.currentGeneration = generation;
        this.outputsOpen = true;
        this.outputsFlushed = false;
        this.textObjectKey = null;
        this.htmlObjectKey = null;
        LOG.debug("Opened statement outputs for statement month {} generation {}", statementMonth, generation);
    }

    /**
     * Emits the whole per-account statement header onto both outputs.
     *
     * <p>Delegates to {@link #create5000Statement(StatementHeader)}, the one-to-one counterpart of
     * {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504}. That paragraph writes
     * {@code ST-LINE0}, performs the HTML header and the HTML name-address-basics ranges, and then writes
     * {@code ST-LINE1} through {@code ST-LINE13} - including {@code ST-LINE5} twice and {@code ST-LINE12}
     * twice.
     *
     * @param header the header data; must not be {@code null}. Individual components may be {@code null}
     * and are then rendered as spaces, or as zero for the balance
     * @throws NullPointerException if {@code header} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open, or if a composed
     * record exceeds its declared width
     */
    public synchronized void writeStatementHeader(StatementHeader header) {
        Objects.requireNonNull(header, "header must not be null");
        requireOpen(STMTFILE_DD_NAME);
        create5000Statement(header);
    }

    /**
     * Emits one transaction onto both outputs for every item in the chunk.
     *
     * <p>This is the {@link ItemWriter} contract. Spring Batch 5 replaced the list-based signature with
     * {@link Chunk}, and the pinned version is 5.2.4, so this is the only signature that overrides the
     * interface method. Each item is emitted by {@link #write6000Trans(StatementTransaction)}, the
     * counterpart of {@code 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L675-L723}.
     *
     * <p>An empty or {@code null} chunk writes nothing at all and, in particular, does not bring a
     * statement into existence: a spurious empty object is never created.
     *
     * @param chunk the transactions to emit; a {@code null} or empty chunk is a no-op
     * @throws Exception declared by the {@link ItemWriter} contract and retained so the signature matches
     * the interface exactly. In practice every failure this method raises is an unchecked
     * {@code com.cardemo.exception.CardDemoException}: a
     * {@code com.cardemo.exception.FatalProcessingException} when no statement is open or a record
     * exceeds its declared width
     */
    @Override
    public synchronized void write(Chunk<? extends StatementTransaction> chunk) throws Exception {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        requireOpen(STMTFILE_DD_NAME);
        for (StatementTransaction transaction : chunk) {
            write6000Trans(transaction);
            this.recordsProcessedCounter.increment();
        }
    }

    /**
     * Emits the per-account totals and the end-of-statement trailer onto both outputs.
     *
     * <p>Delegates to {@link #emit4000TrnxFileTotals(BigDecimal)}, the counterpart of the emission half of
     * {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L433-L454}.
     *
     * @param totalAmount the accumulated transaction total for the account, corresponding to
     * {@code WS-TOTAL-AMT} moved through {@code WS-TRN-AMT} into {@code ST-TOTAL-TRAMT} at
     * {@code app/cbl/CBSTM03A.CBL:L433-L434}. A {@code null} total is rendered as zero, exactly as the
     * source's {@code COMP-3} field initialised to {@code VALUE 0} would be. The value is signed and is
     * never normalised to its magnitude
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open, or if a composed
     * record exceeds its declared width
     */
    public synchronized void writeStatementFooter(BigDecimal totalAmount) {
        requireOpen(STMTFILE_DD_NAME);
        emit4000TrnxFileTotals(totalAmount);
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
     * longer than {@value #TEXT_RECORD_LENGTH} characters
     */
    public synchronized void writeStatementLine(String line) {
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
     * @param fragment the markup to emit, either one of the thirty-four fixed fragments of
     * {@link #htmlFragments()} or a composed line; {@code null} is treated explicitly as an all-spaces
     * record
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open, or if the fragment
     * is longer than {@value #HTML_RECORD_LENGTH} characters
     */
    public synchronized void writeHtmlFragment(String fragment) {
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
     * {@value #HTMLFILE_DD_NAME} - to the object key created for it, in that order
     * @throws com.cardemo.exception.FatalProcessingException if no statement is open
     * @throws com.cardemo.exception.FileAccessException if object storage rejects either upload. The
     * exception carries the logical file name and the attempted operation, and preserves the underlying
     * cause; it never carries the record buffer or the object key
     */
    public synchronized Map<String, String> flushStatementOutputs() {
        requireOpen(STMTFILE_DD_NAME);
        if (this.outputsFlushed) {
            return createdObjectKeys();
        }
        String textKey = objectKey(TEXT_OBJECT_NAME);
        String htmlKey = objectKey(HTML_OBJECT_NAME);
        upload(textKey, this.textRecords.toString(), TEXT_CONTENT_TYPE, STMTFILE_DD_NAME);
        upload(htmlKey, this.htmlRecords.toString(), HTML_CONTENT_TYPE, HTMLFILE_DD_NAME);
        this.textObjectKey = textKey;
        this.htmlObjectKey = htmlKey;
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
    public synchronized Map<String, String> closeStatementOutputs() {
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
     * {@value #HTMLFILE_DD_NAME} - to the object key created for it, or an empty map when nothing has been
     * flushed yet
     */
    public synchronized Map<String, String> createdObjectKeys() {
        if (this.textObjectKey == null || this.htmlObjectKey == null) {
            return Map.of();
        }
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(STMTFILE_DD_NAME, this.textObjectKey);
        keys.put(HTMLFILE_DD_NAME, this.htmlObjectKey);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Reports the thirty-four fixed markup fragments of {@code 01 HTML-LINES},
     * {@code app/cbl/CBSTM03A.CBL:L148-L211}, keyed by their COBOL condition names with hyphens rendered
     * as underscores.
     *
     * <p>Iteration order is the source declaration order, so the table is deterministic. All thirty-four
     * are present, including {@code HTML_LTDS}, which the source declares at {@code :L161} but never
     * activates - see finding 4 in the class documentation.
     *
     * @return the unmodifiable fragment table
     */
    public Map<String, String> htmlFragments() {
        return HTML_FRAGMENTS;
    }

    // =============================================================================================
    // Spring Batch step lifecycle - publishing the created keys.
    // =============================================================================================

    /**
     * Captures the job instance identifier as the generation for a GDG {@code (+1)} write.
     *
     * <p>Job instance identifiers increase monotonically, so using one as the generation segment makes the
     * lexicographic order of the object keys equal the numeric order of the generations, which is the
     * property a relative generation reference depends on.
     *
     * <p>The identifier is reached through {@code getJobExecution().getJobInstance()}, because
     * {@link StepExecution} exposes no direct accessor. Both links are checked explicitly: a step execution
     * assembled outside a running job - as one is in a unit test - can carry neither, and in that case the
     * generation is left at its current value rather than dereferencing a {@code null}.
     *
     * @param stepExecution the step execution Spring Batch is about to run; a {@code null} argument leaves
     * the generation unchanged
     */
    @Override
    public synchronized void beforeStep(StepExecution stepExecution) {
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
     * step readable as {@code (+1)} downstream. Promotion to the job execution context, where a later step
     * needs it, is configured on the job by an execution-context promotion listener; this method owns
     * publication only.
     *
     * @param stepExecution the completed step execution; a {@code null} argument publishes nothing
     * @return {@code null}, which instructs Spring Batch to leave the step's exit status untouched. This
     * writer never decides a step outcome: an unexpected condition is raised as an exception instead
     */
    @Override
    public synchronized ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution == null) {
            return null;
        }
        Map<String, String> keys = createdObjectKeys();
        if (keys.isEmpty()) {
            return null;
        }
        ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(CONTEXT_KEY_TEXT_OBJECT, keys.get(STMTFILE_DD_NAME));
        context.putString(CONTEXT_KEY_HTML_OBJECT, keys.get(HTMLFILE_DD_NAME));
        return null;
    }

    // =============================================================================================
    // Paragraph-for-paragraph emission logic. One private method per COBOL label, never consolidated.
    // =============================================================================================

    /**
     * {@code 5000-CREATE-STATEMENT}, {@code app/cbl/CBSTM03A.CBL:L458-L504}.
     *
     * <p>Reproduces the paragraph in its source order: the {@code INITIALIZE} at {@code :L459}, the
     * {@code ST-LINE0} banner at {@code :L460}, the HTML header range at {@code :L461}, the name and
     * address {@code STRING} statements at {@code :L462-L481}, the three basic-detail moves at
     * {@code :L483-L485}, the HTML name-address-basics range at {@code :L486}, and then the fifteen text
     * writes at {@code :L488-L502}.
     *
     * <p><strong>{@code ST-LINE5} is written twice</strong> - {@code :L492} and {@code :L494}, with
     * {@code ST-LINE6} between them - and <strong>{@code ST-LINE12} is written twice</strong> -
     * {@code :L500} and {@code :L502}, with {@code ST-LINE13} between them. Both duplications are
     * behaviour: the parity comparison is made against the emitted byte stream, so the calls are
     * reproduced one for one and are never collapsed, deduplicated or turned into a loop.
     *
     * <p>On {@code INITIALIZE STATEMENT-LINES} at {@code :L459}: the IBM Enterprise COBOL statement leaves
     * elementary {@code FILLER} items untouched, which is precisely why every constant literal in the
     * layout survives it and only the named items reset - alphanumeric items to spaces and the
     * numeric-edited items to zero. Because every named item is re-established below before the line that
     * carries it is written, the reset has no observable effect on the emitted bytes; it is expressed here
     * by composing each line afresh from the header rather than by mutating shared state, which is also
     * what keeps this class free of the source's working-storage globals.
     *
     * @param header the per-account header data
     */
    private void create5000Statement(StatementHeader header) {
        // :L460 WRITE FD-STMTFILE-REC FROM ST-LINE0.
        writeStatementLine(ST_LINE0);

        // :L461 PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT.
        write5100HtmlHeader(header.accountId());
        exit5100();

        // :L462-L469 STRING CUST-FIRST-NAME / CUST-MIDDLE-NAME / CUST-LAST-NAME INTO ST-NAME.
        // Each name part is DELIMITED BY ' ', so only the characters before its first space transfer,
        // and each is followed by a literal ' ' DELIMITED BY SIZE.
        String stName = fitField(
                upToFirstSpace(header.customerFirstName()) + SINGLE_SPACE
                        + upToFirstSpace(header.customerMiddleName()) + SINGLE_SPACE
                        + upToFirstSpace(header.customerLastName()) + SINGLE_SPACE,
                ST_NAME_WIDTH);

        // :L470 MOVE CUST-ADDR-LINE-1 TO ST-ADD1.  :L471 MOVE CUST-ADDR-LINE-2 TO ST-ADD2.
        String stAdd1 = fitField(header.addressLine1(), ST_ADD1_WIDTH);
        String stAdd2 = fitField(header.addressLine2(), ST_ADD2_WIDTH);

        // :L472-L481 STRING CUST-ADDR-LINE-3 / STATE-CD / COUNTRY-CD / ZIP INTO ST-ADD3.
        String stAdd3 = fitField(
                upToFirstSpace(header.addressLine3()) + SINGLE_SPACE
                        + upToFirstSpace(header.addressStateCode()) + SINGLE_SPACE
                        + upToFirstSpace(header.addressCountryCode()) + SINGLE_SPACE
                        + upToFirstSpace(header.addressZip()) + SINGLE_SPACE,
                ST_ADD3_WIDTH);

        // :L483-L485 MOVE ACCT-ID / ACCT-CURR-BAL / CUST-FICO-CREDIT-SCORE to their statement fields.
        String stAcctId = fitField(header.accountId(), ST_ACCT_ID_WIDTH);
        String stCurrBal = formatZeroFilledAmount(header.currentBalance());
        String stFicoScore = fitField(header.ficoScore(), ST_FICO_SCORE_WIDTH);

        // :L486 PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT.
        write5200HtmlNameAddressBasics(stName, stAdd1, stAdd2, stAdd3, stAcctId, stCurrBal, stFicoScore);
        exit5200();

        // :L488 ST-LINE1 = ST-NAME X(75) + FILLER SPACES X(05).
        writeStatementLine(stName + spaces(5));
        // :L489 ST-LINE2 = ST-ADD1 X(50) + FILLER SPACES X(30).
        writeStatementLine(stAdd1 + spaces(30));
        // :L490 ST-LINE3 = ST-ADD2 X(50) + FILLER SPACES X(30).
        writeStatementLine(stAdd2 + spaces(30));
        // :L491 ST-LINE4 = ST-ADD3 X(80).
        writeStatementLine(stAdd3);
        // :L492 ST-LINE5 - first of two writes.
        writeStatementLine(ST_LINE5);
        // :L493 ST-LINE6.
        writeStatementLine(ST_LINE6);
        // :L494 ST-LINE5 AGAIN - second of two writes. Deliberately not collapsed with :L492.
        writeStatementLine(ST_LINE5);
        // :L495 ST-LINE7 = label X(20) + ST-ACCT-ID X(20) + FILLER SPACES X(40).
        writeStatementLine(ST_LINE7_LABEL + stAcctId + spaces(40));
        // :L496 ST-LINE8 = label X(20) + ST-CURR-BAL 9(9).99- + FILLER X(07) + FILLER X(40).
        writeStatementLine(ST_LINE8_LABEL + stCurrBal + spaces(7) + spaces(40));
        // :L497 ST-LINE9 = label X(20) + ST-FICO-SCORE X(20) + FILLER SPACES X(40).
        writeStatementLine(ST_LINE9_LABEL + stFicoScore + spaces(40));
        // :L498 ST-LINE10.
        writeStatementLine(ST_LINE10);
        // :L499 ST-LINE11.
        writeStatementLine(ST_LINE11);
        // :L500 ST-LINE12 - first of two writes.
        writeStatementLine(ST_LINE12);
        // :L501 ST-LINE13 column headings.
        writeStatementLine(ST_LINE13);
        // :L502 ST-LINE12 AGAIN - second of two writes. Deliberately not collapsed with :L500.
        writeStatementLine(ST_LINE12);
    }

    /**
     * {@code 5100-WRITE-HTML-HEADER}, {@code app/cbl/CBSTM03A.CBL:L506-L552}.
     *
     * <p>Emits the document preamble and the two banner rows. Every write in the source is a
     * {@code SET HTML-Lnn TO TRUE} followed by {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}, except
     * the account banner at {@code :L529-L530}, which moves the account identifier into
     * {@code L11-ACCT PIC X(20)} and writes the composed group {@code HTML-L11} instead. That group
     * composes to 34 + 20 + 5 = 59 characters and is then padded to {@value #HTML_RECORD_LENGTH}.
     *
     * @param accountId the account identifier moved into {@code L11-ACCT} at {@code :L529}
     */
    private void write5100HtmlHeader(String accountId) {
        writeHtmlFragment(HTML_L01);                                             // :L508-L509
        writeHtmlFragment(HTML_L02);                                             // :L510-L511
        writeHtmlFragment(HTML_L03);                                             // :L512-L513
        writeHtmlFragment(HTML_L04);                                             // :L514-L515
        writeHtmlFragment(HTML_L05);                                             // :L516-L517
        writeHtmlFragment(HTML_L06);                                             // :L518-L519
        writeHtmlFragment(HTML_L07);                                             // :L520-L521
        writeHtmlFragment(HTML_L08);                                             // :L522-L523
        writeHtmlFragment(HTML_LTRS);                                            // :L524-L525
        writeHtmlFragment(HTML_L10);                                             // :L526-L527
        // :L529-L530 MOVE ACCT-ID TO L11-ACCT / WRITE FD-HTMLFILE-REC FROM HTML-L11.
        writeHtmlFragment(HTML_L11_PREFIX + fitField(accountId, HTML_L11_ACCT_WIDTH) + HTML_L11_SUFFIX);
        writeHtmlFragment(HTML_LTDE);                                            // :L531-L532
        writeHtmlFragment(HTML_LTRE);                                            // :L533-L534
        writeHtmlFragment(HTML_LTRS);                                            // :L535-L536
        writeHtmlFragment(HTML_L15);                                             // :L537-L538
        writeHtmlFragment(HTML_L16);                                             // :L539-L540
        writeHtmlFragment(HTML_L17);                                             // :L541-L542
        writeHtmlFragment(HTML_L18);                                             // :L543-L544
        writeHtmlFragment(HTML_LTDE);                                            // :L545-L546
        writeHtmlFragment(HTML_LTRE);                                            // :L547-L548
        writeHtmlFragment(HTML_LTRS);                                            // :L549-L550
        writeHtmlFragment(HTML_L22_35);                                          // :L551-L552
    }

    /**
     * {@code 5100-EXIT}, {@code app/cbl/CBSTM03A.CBL:L554-L555}.
     *
     * <p>The source paragraph body is the single statement {@code EXIT}, and {@code :L461} enters the
     * range with {@code PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT}, so the exit paragraph is part of
     * the performed range and is a real, reachable label rather than a comment.
     *
     * <p><strong>Retained for parity.</strong> This method is intentionally empty, exactly as the source
     * paragraph is. Collapsing the paired exit idiom into its predecessor would erase a label the
     * paragraph map is verified against, so the emptiness is deliberate and tracked, not abandoned
     * residue.
     */
    private void exit5100() {
        // Intentionally empty: the source paragraph body is EXIT and nothing else.
    }

    /**
     * {@code 5200-WRITE-HTML-NMADBS}, {@code app/cbl/CBSTM03A.CBL:L558-L669}.
     *
     * <p>Emits the customer name row, the three address rows, the basic-details rows and the transaction
     * column headings. Four of the rows are composed by {@code STRING} rather than taken from a fixed
     * fragment, and the delimiter on each clause decides exactly how much of the field transfers:
     *
     * <ul>
     *   <li>{@code DELIMITED BY '  '} - two spaces - at {@code :L563}, {@code :L571}, {@code :L579} and
     *       {@code :L587} transfers only the characters before the first pair of consecutive spaces, so a
     *       space-padded field contributes its content and not its padding. Each is followed by a literal
     *       {@code '  ' DELIMITED BY SIZE}, which is why two spaces always precede the closing tag.</li>
     *   <li>{@code DELIMITED BY '*'} at {@code :L614-L616}, {@code :L621-L623} and {@code :L628-L630}
     *       names a character that does not occur in the operand, so the <strong>whole</strong> field
     *       transfers, trailing spaces included.</li>
     * </ul>
     *
     * <p>Note also {@code MOVE ST-NAME TO L23-NAME} at {@code :L560}: it moves {@code PIC X(75)} into
     * {@code PIC X(50)}, so the name is truncated to 50 characters before the {@code STRING} sees it.
     *
     * @param stName the already-composed {@code ST-NAME} field
     * @param stAdd1 the already-composed {@code ST-ADD1} field
     * @param stAdd2 the already-composed {@code ST-ADD2} field
     * @param stAdd3 the already-composed {@code ST-ADD3} field
     * @param stAcctId the already-composed {@code ST-ACCT-ID} field
     * @param stCurrBal the already-edited {@code ST-CURR-BAL} field
     * @param stFicoScore the already-composed {@code ST-FICO-SCORE} field
     */
    private void write5200HtmlNameAddressBasics(
            String stName,
            String stAdd1,
            String stAdd2,
            String stAdd3,
            String stAcctId,
            String stCurrBal,
            String stFicoScore) {
        // :L560-L568 MOVE ST-NAME TO L23-NAME (X(75) into X(50)) then STRING into FD-HTMLFILE-REC.
        String l23Name = fitField(stName, HTML_L23_NAME_WIDTH);
        writeHtmlFragment(HTML_L23_PREFIX + upToDoubleSpace(l23Name) + DOUBLE_SPACE + PARAGRAPH_CLOSE);
        // :L569-L576, :L577-L584, :L585-L592 the three HTML-ADDR-LN rows.
        writeHtmlFragment(addressRow(stAdd1));
        writeHtmlFragment(addressRow(stAdd2));
        writeHtmlFragment(addressRow(stAdd3));

        writeHtmlFragment(HTML_LTDE);                                            // :L594-L595
        writeHtmlFragment(HTML_LTRE);                                            // :L596-L597
        writeHtmlFragment(HTML_LTRS);                                            // :L598-L599
        writeHtmlFragment(HTML_L30_42);                                          // :L600-L601
        writeHtmlFragment(HTML_L31);                                             // :L602-L603
        writeHtmlFragment(HTML_LTDE);                                            // :L604-L605
        writeHtmlFragment(HTML_LTRE);                                            // :L606-L607
        writeHtmlFragment(HTML_LTRS);                                            // :L608-L609
        writeHtmlFragment(HTML_L22_35);                                          // :L610-L611

        // :L613-L619, :L620-L626, :L627-L633 the three HTML-BSIC-LN rows, whole fields transferred.
        writeHtmlFragment(HTML_BSIC_ACCOUNT_LABEL + stAcctId + PARAGRAPH_CLOSE);
        writeHtmlFragment(HTML_BSIC_BALANCE_LABEL + stCurrBal + PARAGRAPH_CLOSE);
        writeHtmlFragment(HTML_BSIC_FICO_LABEL + stFicoScore + PARAGRAPH_CLOSE);

        writeHtmlFragment(HTML_LTDE);                                            // :L634-L635
        writeHtmlFragment(HTML_LTRE);                                            // :L636-L637
        writeHtmlFragment(HTML_LTRS);                                            // :L638-L639
        writeHtmlFragment(HTML_L30_42);                                          // :L640-L641
        writeHtmlFragment(HTML_L43);                                             // :L642-L643
        writeHtmlFragment(HTML_LTDE);                                            // :L644-L645
        writeHtmlFragment(HTML_LTRE);                                            // :L646-L647
        writeHtmlFragment(HTML_LTRS);                                            // :L648-L649
        writeHtmlFragment(HTML_L47);                                             // :L650-L651
        writeHtmlFragment(HTML_L48);                                             // :L652-L653
        writeHtmlFragment(HTML_LTDE);                                            // :L654-L655
        writeHtmlFragment(HTML_L50);                                             // :L656-L657
        writeHtmlFragment(HTML_L51);                                             // :L658-L659
        writeHtmlFragment(HTML_LTDE);                                            // :L660-L661
        writeHtmlFragment(HTML_L53);                                             // :L662-L663
        writeHtmlFragment(HTML_L54);                                             // :L664-L665
        writeHtmlFragment(HTML_LTDE);                                            // :L666-L667
        writeHtmlFragment(HTML_LTRE);                                            // :L668-L669
    }

    /**
     * {@code 5200-EXIT}, {@code app/cbl/CBSTM03A.CBL:L671-L672}.
     *
     * <p>The source paragraph body is the single statement {@code EXIT}, and {@code :L486} enters the
     * range with {@code PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT}.
     *
     * <p><strong>Retained for parity</strong>, for the same reason as {@link #exit5100()}: the label is
     * part of the performed range and the paragraph map is verified against it.
     */
    private void exit5200() {
        // Intentionally empty: the source paragraph body is EXIT and nothing else.
    }

    /**
     * {@code 6000-WRITE-TRANS}, {@code app/cbl/CBSTM03A.CBL:L675-L723}.
     *
     * <p>Emits one transaction: the text detail line {@code ST-LINE14} at {@code :L679}, then the HTML
     * table row at {@code :L681-L721} whose three cells carry the identifier, the description and the
     * amount, each composed into {@code HTML-TRAN-LN} by a {@code STRING} and each wrapped in the fixed
     * cell fragments.
     *
     * <p>Two field-level truncations are reproduced rather than corrected. {@code MOVE TRNX-DESC TO
     * ST-TRANDT} at {@code :L677} moves {@code PIC X(100)} into {@code PIC X(49)}, so a description is cut
     * to 49 characters. {@code MOVE TRNX-AMT TO ST-TRANAMT} at {@code :L678} renders a signed
     * {@code PIC S9(09)V99} through the zero-suppressed mask {@code PIC Z(9).99-}, whose sign is
     * <strong>trailing</strong>; the amount is signed and is never normalised to its magnitude, which is
     * exactly why that mask has a sign position at all.
     *
     * @param transaction the transaction to emit
     */
    private void write6000Trans(StatementTransaction transaction) {
        if (transaction == null) {
            throw abend("a null transaction reached the statement writer", STMTFILE_DD_NAME, OPERATION_WRITE);
        }
        // :L676-L678 the three moves onto the detail line.
        String stTranId = fitField(transaction.transactionId(), ST_TRANID_WIDTH);
        String stTranDt = fitField(transaction.description(), ST_TRANDT_WIDTH);
        String stTranAmt = formatZeroSuppressedAmount(transaction.amount());

        // :L679 ST-LINE14 = ST-TRANID X(16) + FILLER ' ' X(01) + ST-TRANDT X(49) + FILLER '$' X(01)
        //                   + ST-TRANAMT Z(9).99-.
        writeStatementLine(stTranId + SINGLE_SPACE + stTranDt + CURRENCY_FILLER + stTranAmt);

        writeHtmlFragment(HTML_LTRS);                                            // :L681-L682
        writeHtmlFragment(HTML_L58);                                             // :L684-L685
        writeHtmlFragment(transactionCell(stTranId));                            // :L686-L692
        writeHtmlFragment(HTML_LTDE);                                            // :L693-L694
        writeHtmlFragment(HTML_L61);                                             // :L696-L697
        writeHtmlFragment(transactionCell(stTranDt));                            // :L698-L704
        writeHtmlFragment(HTML_LTDE);                                            // :L705-L706
        writeHtmlFragment(HTML_L64);                                             // :L708-L709
        writeHtmlFragment(transactionCell(stTranAmt));                           // :L710-L716
        writeHtmlFragment(HTML_LTDE);                                            // :L717-L718
        writeHtmlFragment(HTML_LTRE);                                            // :L720-L721
    }

    /**
     * The emission half of {@code 4000-TRNXFILE-GET}, {@code app/cbl/CBSTM03A.CBL:L433-L454}.
     *
     * <p>Writes the account totals and the end-of-statement trailer: {@code ST-LINE12} at {@code :L435},
     * {@code ST-LINE14A} carrying the edited total at {@code :L436}, {@code ST-LINE15} at {@code :L437},
     * and then the eight closing HTML fragments at {@code :L439-L454}.
     *
     * <p>The other half of the source paragraph, the in-memory table walk at {@code :L417-L432}, has
     * <strong>no counterpart in this class</strong>. That walk indexes {@code WS-CARD-TBL OCCURS 51} by
     * {@code WS-TRAN-TBL OCCURS 10} ({@code :L225-L233}) and is replaced by streaming the transactions
     * through {@link #write(Chunk)}. Removing the resulting
     * {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}-transaction
     * ceiling, which the source enforced with no bounds check at all, is a labelled deviation and not
     * parity - see finding 5 in the class documentation.
     *
     * @param totalAmount the accumulated account total; {@code null} is rendered as zero
     */
    private void emit4000TrnxFileTotals(BigDecimal totalAmount) {
        // :L433-L434 MOVE WS-TOTAL-AMT TO WS-TRN-AMT / MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT.
        String stTotalTranAmt = formatZeroSuppressedAmount(totalAmount);

        writeStatementLine(ST_LINE12);                                           // :L435
        // :L436 ST-LINE14A = 'Total EXP:' X(10) + FILLER SPACES X(56) + FILLER '$' X(01)
        //                    + ST-TOTAL-TRAMT Z(9).99-.
        writeStatementLine(ST_LINE14A_LABEL + spaces(56) + CURRENCY_FILLER + stTotalTranAmt);
        writeStatementLine(ST_LINE15);                                           // :L437

        writeHtmlFragment(HTML_LTRS);                                            // :L439-L440
        writeHtmlFragment(HTML_L10);                                             // :L441-L442
        writeHtmlFragment(HTML_L75);                                             // :L443-L444
        writeHtmlFragment(HTML_LTDE);                                            // :L445-L446
        writeHtmlFragment(HTML_LTRE);                                            // :L447-L448
        writeHtmlFragment(HTML_L78);                                             // :L449-L450
        writeHtmlFragment(HTML_L79);                                             // :L451-L452
        writeHtmlFragment(HTML_L80);                                             // :L453-L454
    }

    // =============================================================================================
    // Guards, abend construction and the storage boundary.
    // =============================================================================================

    /**
     * Rejects a {@code null} or blank identifier before it can reach an object key.
     *
     * <p>Inputs are treated as untrusted, so this is an explicit boundary check rather than a reliance on
     * the storage client to complain later about a malformed key.
     *
     * @param value the candidate value
     * @param name the parameter name, used in the diagnostic and never a value
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Rejects an emission attempted while no statement is open.
     *
     * <p>The COBOL counterpart cannot reach this state: {@code OPEN OUTPUT STMT-FILE HTML-FILE} at
     * {@code app/cbl/CBSTM03A.CBL:L293} runs once during initialisation and the record areas exist for the
     * whole run. A Java bean, being reusable across accounts, can be driven out of order, so the condition
     * is checked explicitly and fails fast rather than silently accumulating records into the wrong
     * statement.
     *
     * @param logicalFileName the output being written, for the diagnostic
     * @throws FatalProcessingException if no statement is open
     */
    private void requireOpen(String logicalFileName) {
        if (!this.outputsOpen) {
            throw abend("no statement is open; call openStatementOutputs before emitting a record",
                    logicalFileName, OPERATION_WRITE);
        }
    }

    /**
     * Builds the fatal abend for an unrecoverable emission condition.
     *
     * <p>Reproduces the contract of {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBSTM03A.CBL:L921-L923},
     * whose body displays the abend message and then issues {@code CALL 'CEE3ABD'}. The abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and the process return code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} are
     * <strong>referenced</strong> from {@code com.cardemo.exception.FatalProcessingException} and are never
     * redeclared here. The batch value is 999; the four digit 9999 belongs to the CICS online path and is
     * not used anywhere in this class.
     *
     * <p>The message names the output and the operation only. It never carries a record, a composed line,
     * an object key, an account identifier or any other statement content.
     *
     * @param reason the condition, carrying {@code ABEND-REASON}
     * @param logicalFileName the output involved
     * @param operation the attempted operation
     * @return the exception for the caller to throw, so that the call site reads as a {@code throw}
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
     * Pads and validates one fixed length record.
     *
     * <p>A shorter value is right-padded with spaces, because a {@code RECFM=FB} record is always its full
     * declared width. A <strong>longer</strong> value is rejected: COBOL would have truncated it silently
     * into the record area, and a silently corrupted record geometry is the one outcome that would pass
     * every functional test and still fail the byte comparison.
     *
     * <p>Pure function of its arguments. The diagnostic reports the offending <em>length</em> and never the
     * content, because a statement record carries customer name, address, account identifier, balance and
     * credit score.
     *
     * @param value the record content; {@code null} is treated explicitly as an all-spaces record
     * @param width the declared record width, {@value #TEXT_RECORD_LENGTH} or {@value #HTML_RECORD_LENGTH}
     * @param logicalFileName the output being written, for the diagnostic
     * @return the record, exactly {@code width} characters long
     * @throws FatalProcessingException if the value is longer than the declared width
     */
    private static String fixedRecord(String value, int width, String logicalFileName) {
        String content = value == null ? "" : value;
        if (content.length() > width) {
            throw abend(String.format(Locale.ROOT,
                    "a composed record is %d characters but the record area is exactly %d",
                    content.length(), width),
                    logicalFileName, OPERATION_WRITE);
        }
        return padRight(content, width);
    }

    /**
     * Composes the object key for one of the two outputs of the statement currently open.
     *
     * <p>The generation segment is zero-padded to {@value #GENERATION_WIDTH} digits so that the
     * lexicographic order of the keys equals the numeric order of the generations, which is what lets a
     * relative {@code (0)} reference be resolved as "the lexicographically greatest existing prefix".
     * {@link Locale#ROOT} is used explicitly, so the digits never depend on the platform default locale.
     *
     * @param objectName the leaf name, {@value #TEXT_OBJECT_NAME} or {@value #HTML_OBJECT_NAME}
     * @return the full object key
     */
    private String objectKey(String objectName) {
        return KEY_ROOT + KEY_SEPARATOR
                + KEY_ACCOUNT_SEGMENT + this.currentAccountId + KEY_SEPARATOR
                + KEY_MONTH_SEGMENT + this.currentStatementMonth + KEY_SEPARATOR
                + KEY_GENERATION_SEGMENT
                + String.format(Locale.ROOT, "%0" + GENERATION_WIDTH + "d", this.currentGeneration)
                + KEY_SEPARATOR + objectName;
    }

    /**
     * Uploads one accumulated output to object storage, undelimited and byte-exact.
     *
     * <p>The content is encoded once, with {@link #RECORD_CHARSET} named explicitly, and the resulting
     * length is declared to the storage client, so the stored object size is an exact multiple of the
     * record width.
     *
     * <p>Every failure is routed through {@code com.cardemo.service.shared.FileStatusMapper}, the sole
     * owner of the status-to-exception decision, under the {@value #STORAGE_FAILURE_STATUS} status of the
     * {@code '9x'} family. The mapper's answer for that family is
     * {@code com.cardemo.exception.FileAccessException}; the {@code orElseGet} branch exists solely so that
     * this method can <strong>never</strong> return normally after a failure - swallowing the exception is
     * not a reachable outcome - and it produces the same typed exception with the same preserved cause.
     *
     * <p>The status line in the message comes from {@code FileStatusMapper.displayIoStatus(String)}, which
     * is the owner's own composition of the fixed twenty character literal
     * {@code com.cardemo.model.enums.FileStatus.DISPLAY_MESSAGE_PREFIX} followed immediately by the four
     * rendered characters. That literal ends in four {@code N} characters and is <strong>not</strong> a
     * placeholder: for a status of {@code '23'} the whole line reads
     * {@code FILE STATUS IS: NNNN0023}. It is referenced through its owner rather than redeclared here, it
     * is never prefixed a second time, and it is never reformatted.
     *
     * @param key the object key; deliberately absent from every message and log line, because it embeds
     * the account identifier
     * @param content the accumulated records, already padded to their exact widths
     * @param contentType the metadata content type
     * @param logicalFileName the output being written, {@value #STMTFILE_DD_NAME} or
     * {@value #HTMLFILE_DD_NAME}
     * @throws FileAccessException if object storage rejects the upload
     */
    private void upload(String key, String content, String contentType, String logicalFileName) {
        byte[] payload = content.getBytes(RECORD_CHARSET);
        ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(contentType)
                .contentLength((long) payload.length)
                .build();
        try {
            this.s3Template.upload(this.statementsBucket, key, new ByteArrayInputStream(payload), metadata);
        } catch (RuntimeException cause) {
            String message = String.format(Locale.ROOT,
                    "Object storage rejected the %s %s of %d bytes. %s",
                    logicalFileName,
                    OPERATION_WRITE,
                    payload.length,
                    this.fileStatusMapper.displayIoStatus(STORAGE_FAILURE_STATUS));
            LOG.error(message, cause);
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

    // =============================================================================================
    // Pure composition helpers. Every one is a function of its arguments alone: no field is read, no
    // field is written, no clock, no locale default, no charset default.
    // =============================================================================================

    /**
     * Right-pads with spaces to the requested width, and never truncates.
     *
     * @param value the value to pad; must not be {@code null}
     * @param width the target width
     * @return the value padded to {@code width}, or the value unchanged when it is already at least that
     * long
     */
    private static String padRight(String value, int width) {
        return value.length() >= width ? value : value + spaces(width - value.length());
    }

    /**
     * Applies COBOL fixed-field semantics for a single field: pad right with spaces, or truncate on the
     * right.
     *
     * <p>This reproduces two source behaviours at once. {@code MOVE} into an alphanumeric field
     * space-fills a short sender and truncates a long one - which is exactly how
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L677} cuts a
     * {@code PIC X(100)} description down to {@code PIC X(49)}, and how
     * {@code MOVE ST-NAME TO L23-NAME} at {@code :L560} cuts {@code PIC X(75)} down to
     * {@code PIC X(50)}. {@code STRING} into a field that {@code INITIALIZE} has just cleared leaves the
     * same result.
     *
     * <p>Field-level truncation is parity and is deliberate. It is <strong>not</strong> the same decision
     * as {@link #fixedRecord(String, int, String)}, which rejects an over-length <em>record</em>: a field
     * that overflows its picture is legacy behaviour, whereas a record that overflows its declared width
     * would corrupt the geometry of every record after it.
     *
     * @param value the field content; {@code null} is treated explicitly as all spaces
     * @param width the declared field width
     * @return the field, exactly {@code width} characters long
     */
    private static String fitField(String value, int width) {
        String content = value == null ? "" : value;
        return content.length() > width ? content.substring(0, width) : padRight(content, width);
    }

    /**
     * Produces a run of spaces, standing in for a {@code FILLER ... VALUE SPACES} item.
     *
     * @param count how many spaces; must not be negative
     * @return the requested spaces
     */
    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * Applies {@code DELIMITED BY ' '}: transfers only the characters before the first single space.
     *
     * <p>Used by the six name clauses at {@code app/cbl/CBSTM03A.CBL:L462-L469} and the eight address
     * clauses at {@code :L472-L481}, which is how a space-padded {@code PIC X(25)} name contributes its
     * content and not its padding.
     *
     * @param value the sending field; {@code null} contributes nothing
     * @return the leading characters up to, but excluding, the first space
     */
    private static String upToFirstSpace(String value) {
        String content = value == null ? "" : value;
        int end = content.indexOf(SINGLE_SPACE);
        return end < 0 ? content : content.substring(0, end);
    }

    /**
     * Applies {@code DELIMITED BY '  '}: transfers only the characters before the first pair of
     * consecutive spaces.
     *
     * <p>Used by the name row at {@code app/cbl/CBSTM03A.CBL:L563} and the three address rows at
     * {@code :L571}, {@code :L579} and {@code :L587}. It differs from {@link #upToFirstSpace(String)} in
     * exactly the way the source intends: a value containing single spaces - a street address, a
     * multi-word city - survives intact, and only the trailing padding is dropped.
     *
     * @param value the sending field; {@code null} contributes nothing
     * @return the leading characters up to, but excluding, the first pair of consecutive spaces
     */
    private static String upToDoubleSpace(String value) {
        String content = value == null ? "" : value;
        int end = content.indexOf(DOUBLE_SPACE);
        return end < 0 ? content : content.substring(0, end);
    }

    /**
     * Composes one {@code HTML-ADDR-LN} row, {@code app/cbl/CBSTM03A.CBL:L569-L592}.
     *
     * <p>The source {@code STRING} is {@code '<p>' DELIMITED BY '*'}, then the address field
     * {@code DELIMITED BY '  '}, then a literal {@code '  ' DELIMITED BY SIZE}, then
     * {@code '</p>' DELIMITED BY '*'} - so the two spaces before the closing tag are part of the output
     * and are reproduced here.
     *
     * @param address the {@code ST-ADD1}, {@code ST-ADD2} or {@code ST-ADD3} field
     * @return the composed row, before padding to {@value #HTML_RECORD_LENGTH}
     */
    private static String addressRow(String address) {
        return PARAGRAPH_OPEN + upToDoubleSpace(address) + DOUBLE_SPACE + PARAGRAPH_CLOSE;
    }

    /**
     * Composes one {@code HTML-TRAN-LN} cell, {@code app/cbl/CBSTM03A.CBL:L686-L716}.
     *
     * <p>All three clauses of the source {@code STRING} are {@code DELIMITED BY '*'}, and no operand
     * contains an asterisk, so each operand transfers <strong>whole</strong> - trailing spaces of the
     * fixed-width field included. That is why this helper passes the cell through untouched rather than
     * trimming it.
     *
     * @param cell the already fixed-width {@code ST-TRANID}, {@code ST-TRANDT} or {@code ST-TRANAMT} field
     * @return the composed cell, before padding to {@value #HTML_RECORD_LENGTH}
     */
    private static String transactionCell(String cell) {
        return PARAGRAPH_OPEN + cell + PARAGRAPH_CLOSE;
    }

    /**
     * Renders an amount through {@code PIC Z(9).99-}, the mask of {@code ST-TRANAMT}
     * ({@code app/cbl/CBSTM03A.CBL:L137}) and {@code ST-TOTAL-TRAMT} ({@code :L142}).
     *
     * @param amount the signed amount; {@code null} renders as zero
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters, leading zeros suppressed to spaces and the
     * sign trailing
     */
    private static String formatZeroSuppressedAmount(BigDecimal amount) {
        return editedAmount(amount, true);
    }

    /**
     * Renders an amount through {@code PIC 9(9).99-}, the mask of {@code ST-CURR-BAL}
     * ({@code app/cbl/CBSTM03A.CBL:L113}).
     *
     * <p>The mask uses {@code 9} rather than {@code Z}, so leading zeros are <strong>kept</strong>. This is
     * the one edited field on the statement that is not zero-suppressed, and harmonising the two masks
     * would change 80 bytes of every statement.
     *
     * @param amount the signed balance; {@code null} renders as zero
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters, zero-filled and the sign trailing
     */
    private static String formatZeroFilledAmount(BigDecimal amount) {
        return editedAmount(amount, false);
    }

    /**
     * Renders a signed amount through one of the two thirteen character edited masks.
     *
     * <p>Layout, for both masks: {@value #EDITED_AMOUNT_INTEGER_DIGITS} integer positions, a decimal point,
     * {@value com.cardemo.model.dto.StatementTransaction#AMOUNT_SCALE} decimal digits, and one
     * <strong>trailing</strong> sign position - {@code 9 + 1 + 2 + 1 =}
     * {@value #EDITED_AMOUNT_WIDTH}. The trailing sign renders {@code -} when the value is negative and a
     * <strong>space</strong> when it is positive or zero, which is what the {@code -} symbol at the end of
     * a COBOL picture means. There is no leading sign and no currency symbol inside the mask: the
     * {@code '$'} on the statement is a separate one-byte {@code FILLER} at {@code :L136} and {@code :L141}.
     *
     * <p>Three parity details are deliberate:
     *
     * <ul>
     *   <li>Arithmetic is {@link BigDecimal} and {@link BigInteger} throughout, with
     *       {@link RoundingMode#HALF_EVEN}. No binary floating-point type appears anywhere
     *       in this class; every monetary value is exact decimal throughout.</li>
     *   <li>The value is <strong>signed</strong> and is never normalised to its magnitude. The magnitude is
     *       taken only to render the digits, and the sign is then placed in its own trailing position.</li>
     *   <li>A {@code MOVE} into a picture with {@value #EDITED_AMOUNT_INTEGER_DIGITS} integer positions
     *       truncates <em>high-order</em> digits that do not fit, so a balance held as
     *       {@code PIC S9(10)V99} loses its tenth integer digit on the way onto the statement. The
     *       {@code mod} below reproduces that truncation rather than widening the field or rejecting the
     *       value.</li>
     * </ul>
     *
     * @param amount the signed amount; {@code null} renders as zero, matching a {@code COMP-3} field
     * declared {@code VALUE 0}
     * @param suppressLeadingZeros {@code true} for the {@code Z(9)} mask, {@code false} for the
     * {@code 9(9)} mask
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters
     * @throws FatalProcessingException if the rendered field is not exactly
     * {@value #EDITED_AMOUNT_WIDTH} characters, which no input can produce and which would otherwise
     * corrupt the record silently
     */
    private static String editedAmount(BigDecimal amount, boolean suppressLeadingZeros) {
        BigDecimal scaled = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(StatementTransaction.AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigInteger unscaled = scaled.abs().unscaledValue();
        BigInteger fractionModulus = BigInteger.TEN.pow(StatementTransaction.AMOUNT_SCALE);
        BigInteger integerModulus = BigInteger.TEN.pow(EDITED_AMOUNT_INTEGER_DIGITS);
        BigInteger integerValue = unscaled.divide(fractionModulus).mod(integerModulus);
        BigInteger fractionValue = unscaled.mod(fractionModulus);

        String integerField = padLeftZeros(integerValue.toString(), EDITED_AMOUNT_INTEGER_DIGITS);
        if (suppressLeadingZeros) {
            integerField = suppressLeadingZeros(integerField);
        }
        String edited = integerField
                + "."
                + padLeftZeros(fractionValue.toString(), StatementTransaction.AMOUNT_SCALE)
                + (negative ? "-" : SINGLE_SPACE);
        if (edited.length() != EDITED_AMOUNT_WIDTH) {
            throw abend(String.format(Locale.ROOT,
                    "an edited amount rendered %d characters but the mask is exactly %d",
                    edited.length(), EDITED_AMOUNT_WIDTH),
                    STMTFILE_DD_NAME, OPERATION_WRITE);
        }
        return edited;
    }

    /**
     * Left-pads a digit string with zeros, standing in for the {@code 9} picture symbol.
     *
     * @param digits the digits; must not be {@code null}
     * @param width the number of digit positions
     * @return the digits padded to {@code width}
     */
    private static String padLeftZeros(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Applies the {@code Z} picture symbol: replaces leading zeros with spaces.
     *
     * <p>IBM Enterprise COBOL ends zero suppression at the first non-zero digit or at the decimal point,
     * whichever comes first. Because every integer position of {@code PIC Z(9).99-} is a suppression
     * symbol, an integer part of zero renders as {@value #EDITED_AMOUNT_INTEGER_DIGITS} spaces and the two
     * decimal digits - which are {@code 9} symbols, not {@code Z} - are still shown.
     *
     * @param digits the zero-filled integer field; must not be {@code null}
     * @return the field with its leading zeros replaced by spaces
     */
    private static String suppressLeadingZeros(String digits) {
        StringBuilder suppressed = new StringBuilder(digits);
        for (int index = 0; index < suppressed.length() && suppressed.charAt(index) == '0'; index++) {
            suppressed.setCharAt(index, ' ');
        }
        return suppressed.toString();
    }

    /**
     * Builds the unmodifiable map of the thirty-four fixed markup fragments of {@code 01 HTML-LINES},
     * {@code app/cbl/CBSTM03A.CBL:L148-L211}.
     *
     * <p>The keys are the COBOL condition names with hyphens rendered as underscores, and the entries are
     * inserted in source-declaration order into a {@link LinkedHashMap}, so iteration is deterministic.
     * All thirty-four are present, including {@code HTML_LTDS}: that condition name is declared at
     * {@code :L161} but is never activated anywhere in the program, and it is retained rather than dropped
     * because the fragment inventory is part of the paragraph map - see finding 4 in the class
     * documentation.
     *
     * @return the thirty-four fragments, unmodifiable and in declaration order
     */
    private static Map<String, String> buildHtmlFragments() {
        Map<String, String> fragments = new LinkedHashMap<>();
        fragments.put("HTML_L01", HTML_L01);
        fragments.put("HTML_L02", HTML_L02);
        fragments.put("HTML_L03", HTML_L03);
        fragments.put("HTML_L04", HTML_L04);
        fragments.put("HTML_L05", HTML_L05);
        fragments.put("HTML_L06", HTML_L06);
        fragments.put("HTML_L07", HTML_L07);
        fragments.put("HTML_L08", HTML_L08);
        fragments.put("HTML_LTRS", HTML_LTRS);
        fragments.put("HTML_LTRE", HTML_LTRE);
        fragments.put("HTML_LTDS", HTML_LTDS);
        fragments.put("HTML_LTDE", HTML_LTDE);
        fragments.put("HTML_L10", HTML_L10);
        fragments.put("HTML_L15", HTML_L15);
        fragments.put("HTML_L16", HTML_L16);
        fragments.put("HTML_L17", HTML_L17);
        fragments.put("HTML_L18", HTML_L18);
        fragments.put("HTML_L22_35", HTML_L22_35);
        fragments.put("HTML_L30_42", HTML_L30_42);
        fragments.put("HTML_L31", HTML_L31);
        fragments.put("HTML_L43", HTML_L43);
        fragments.put("HTML_L47", HTML_L47);
        fragments.put("HTML_L48", HTML_L48);
        fragments.put("HTML_L50", HTML_L50);
        fragments.put("HTML_L51", HTML_L51);
        fragments.put("HTML_L53", HTML_L53);
        fragments.put("HTML_L54", HTML_L54);
        fragments.put("HTML_L58", HTML_L58);
        fragments.put("HTML_L61", HTML_L61);
        fragments.put("HTML_L64", HTML_L64);
        fragments.put("HTML_L75", HTML_L75);
        fragments.put("HTML_L78", HTML_L78);
        fragments.put("HTML_L79", HTML_L79);
        fragments.put("HTML_L80", HTML_L80);
        return Collections.unmodifiableMap(fragments);
    }
}
