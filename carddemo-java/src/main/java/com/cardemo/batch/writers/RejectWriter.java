package com.cardemo.batch.writers;

import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that captures the <strong>rejected</strong> daily-transaction
 * records of the Daily Transaction Posting job, reproducing the legacy AWS CardDemo batch
 * paragraph <strong>{@code 2500-WRITE-REJECT-REC}</strong> of
 * {@code app/cbl/CBTRN02C.cbl} (Daily Transaction Posting) in the greenfield Java&nbsp;25 LTS +
 * Spring&nbsp;Boot&nbsp;3.5.x migration.
 *
 * <h2>Provenance &amp; governance</h2>
 * <p>Translated from COBOL {@code app/cbl/CBTRN02C.cbl} at the frozen legacy baseline commit SHA
 * {@code 27d6c6f}. The COBOL source is <strong>read-only</strong> reference material and is
 * <strong>never copied</strong> into this repository; traceability is by commit SHA and paragraph
 * locator only (AAP &sect;0.7.2). Per the <strong>Minimal Change Clause</strong> (AAP &sect;0.7.1)
 * this class reproduces the COBOL behaviour <em>exactly</em> &mdash; no business-rule changes, no
 * feature additions &mdash; and documents every technology substitution at its point of use. The
 * application base package is {@code com.cardemo} (decision <strong>D-006</strong>, deliberately
 * <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>What this writer reproduces ({@code CBTRN02C} reject branch)</h2>
 * <p>In {@code CBTRN02C} the main read loop clears {@code WS-VALIDATION-FAIL-REASON} and runs the
 * {@code 1500-VALIDATE-TRAN} cascade; when the reason is non-zero it performs
 * {@code 2500-WRITE-REJECT-REC}, which assembles a fixed-length <strong>430-byte</strong> reject
 * record and {@code WRITE}s it to the {@code DALYREJS} GDG output (LRECL&nbsp;430). The COBOL
 * paragraph is, verbatim by intent (never copied):</p>
 * <pre>
 *   2500-WRITE-REJECT-REC.
 *       MOVE DALYTRAN-RECORD       TO REJECT-TRAN-DATA     (bytes 1-350, byte-for-byte)
 *       MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER   (bytes 351-430)
 *       WRITE FD-REJS-RECORD FROM REJECT-RECORD
 *       IF DALYREJS-STATUS NOT = '00' -&gt; DISPLAY + 9999-ABEND-PROGRAM
 * </pre>
 *
 * <h2>The reject record layout &mdash; PRESERVED EXACTLY (external interface contract, AAP &sect;0.7.2)</h2>
 * <p>Each reject record is <strong>430 bytes</strong>, composed of two fixed-width parts:</p>
 * <ol>
 *   <li><strong>Bytes 1-350 &mdash; {@code REJECT-TRAN-DATA PIC X(350)}:</strong> the original
 *       daily-transaction record (the {@code CVTRA06Y} 350-byte {@code DALYTRAN-RECORD} layout),
 *       byte-for-byte.</li>
 *   <li><strong>Bytes 351-430 &mdash; {@code VALIDATION-TRAILER PIC X(80)}:</strong> the validation
 *       failure trailer ({@code WS-VALIDATION-TRAILER}), itself:
 *       <ul>
 *         <li><strong>Bytes 351-354 &mdash; {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}:</strong>
 *             the 4-digit reject reason code, <strong>zero-padded</strong> (e.g. {@code 0100}).</li>
 *         <li><strong>Bytes 355-430 &mdash; {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}:</strong>
 *             the reason description text, <strong>left-justified, space-padded</strong> to 76.</li>
 *       </ul>
 *   </li>
 * </ol>
 * <p>The 350&nbsp;+&nbsp;80&nbsp;=&nbsp;430-byte LRECL, field offsets, the zero-padding of the
 * 4-digit code and the space-padding of the 76-char description are part of the preserved external
 * interface and are reproduced to the byte (asserted defensively in {@link #formatRejectRecord}).</p>
 *
 * <h2>Reject codes ({@link RejectCode})</h2>
 * <p>The reject reason originates upstream in the validation cascade
 * ({@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-ACCT}) and the account-update path
 * ({@code 2800-UPDATE-ACCOUNT-REC}). The numeric code and its 76-char description are taken from the
 * canonical {@link RejectCode} enum &mdash; {@link RejectCode#getCode()} and
 * {@link RejectCode#getDescription()} &mdash; so the trailer round-trips byte-faithfully:
 * {@code 100} (INVALID CARD NUMBER FOUND), {@code 101}/{@code 109} (ACCOUNT RECORD NOT FOUND),
 * {@code 102} (OVERLIMIT TRANSACTION), {@code 103} (TRANSACTION RECEIVED AFTER ACCT EXPIRATION).
 * Codes {@code 104}-{@code 108} do not exist in the COBOL source and are never produced.</p>
 *
 * <h2>Input item contract &mdash; {@link PostedTransactionResult}</h2>
 * <p>This writer consumes the rejected output of the Daily Transaction Posting processor through the
 * shared, model-layer carrier {@link PostedTransactionResult} (the same DTO produced by
 * {@code TransactionPostingProcessor} and consumed by the sibling accepted-path writer). It
 * implements {@code ItemWriter<PostedTransactionResult>}; only the {@link PostedTransactionResult#isRejected()
 * rejected} items are written, mirroring the COBOL branch that routes only non-zero
 * {@code WS-VALIDATION-FAIL-REASON} records to {@code 2500-WRITE-REJECT-REC}. Accepted items that
 * reach this writer are skipped defensively. Per the AAP folder layering rule the generic type is a
 * model-layer type and this class imports <strong>no</strong> sibling {@code com.cardemo.batch.*}
 * package ({@code depends_on_folders = []}).</p>
 *
 * <h2>Bytes 1-350 fidelity &mdash; re-serialization design decision (documented)</h2>
 * <p>The COBOL {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} is a verbatim byte copy of the
 * record as read. The strongly-preferred design is for the input item to carry the verbatim original
 * 350-byte record text; however, the established shared {@link PostedTransactionResult} carries the
 * <em>parsed</em> {@link DailyTransaction} entity ({@link PostedTransactionResult#originalTransaction()}),
 * not the raw line. This writer therefore <strong>re-serializes</strong> the {@link DailyTransaction}
 * to the exact {@code CVTRA06Y} fixed-width 350-byte layout by <em>precisely reversing</em> the
 * tokenization performed by {@code com.cardemo.batch.readers.DailyTransactionReader} &mdash; identical
 * field offsets/lengths, the same zoned-decimal <strong>overpunch</strong> encoding for
 * {@code DALYTRAN-AMT PIC S9(09)V99}, the same {@code yyyy-MM-dd HH:mm:ss.SSSSSS} timestamp text, and
 * the same {@link StandardCharsets#ISO_8859_1 ISO-8859-1} 1&nbsp;byte&nbsp;=&nbsp;1&nbsp;char charset.
 * See {@link #serializeDalytranRecord(DailyTransaction)} and {@link #encodeSignedOverpunch(BigDecimal)}.</p>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Sequential GDG {@code WRITE FD-REJS-RECORD} to {@code DALYREJS(+1)} (LRECL 430)
 *       &rarr; AWS S3 versioned object</strong> (GDG&nbsp;&rarr;&nbsp;S3, decision <strong>D-003</strong>,
 *       AAP &sect;0.1.2 / &sect;0.7.7). The chunk's 430-byte reject records are uploaded as a single
 *       object in the config-resolved batch-output bucket ({@code carddemo-batch-output}); generation
 *       numbering is realized through S3 object versioning plus a generation-prefixed key. The bucket
 *       is read from {@link AwsConfig.AwsResourceProperties} and is never hardcoded; the upload uses
 *       the auto-configured {@link S3Template}, so the endpoint resolves only to LocalStack with zero
 *       live AWS credentials. See {@link #writeToS3(byte[], String)}.</li>
 *   <li><strong>COBOL ABEND on write error</strong> ({@code 2500} L457-L464, {@code DALYREJS-STATUS}
 *       not {@code '00'} &rarr; {@code 9999-ABEND-PROGRAM}) <strong>&rarr; propagated exception</strong>.
 *       Any S3 failure propagates out of {@link #write(Chunk)} so the Spring Batch step fails / rolls
 *       back (AAP &sect;0.7.5), exactly as the legacy program abended rather than silently dropping a
 *       reject.</li>
 *   <li><strong>Fixed 430-byte {@code DALYREJS} records &rarr; newline-framed S3 lines.</strong> The
 *       legacy {@code RECFM=FB} dataset has no in-record delimiter; when serialized into a single S3
 *       text object each 430-byte record is followed by a single {@code '\n'} (LF) record terminator,
 *       matching the project's newline-framed fixed-width fixtures (e.g.
 *       {@code app/data/ASCII/dailytran.txt}). The per-record LRECL stays exactly 430 bytes.</li>
 * </ul>
 *
 * <h2>Idempotency / transactional note</h2>
 * <p>S3 is not part of the JPA transaction. The object key is deterministic and generation-prefixed
 * (see {@link #buildObjectKey(String, String)}), so a Spring Batch retry of the same chunk re-emits
 * the same key and <strong>overwrites</strong> rather than duplicating &mdash; an at-least-once write
 * with an idempotent key.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The writer is stateless apart from its injected, immutable collaborators (an {@link S3Template},
 * the {@link AwsConfig.AwsResourceProperties} name holder, and a {@link Clock}); all mutable state is
 * confined to {@link #write(Chunk)} locals, so a single Spring-managed singleton is safe to share
 * across batch threads.</p>
 *
 * @see PostedTransactionResult
 * @see RejectCode
 * @see ItemWriter
 * @see AwsConfig.AwsResourceProperties
 */
@Component("rejectWriter")
public class RejectWriter implements ItemWriter<PostedTransactionResult> {

    /** Total fixed length of the original daily-transaction record ({@code CVTRA06Y}, RECLN 350). */
    static final int DALYTRAN_RECORD_LENGTH = 350;

    /** Length of the validation trailer ({@code VALIDATION-TRAILER PIC X(80)}). */
    static final int TRAILER_LENGTH = 80;

    /** Length of the 4-digit reject reason code ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    static final int REASON_CODE_LENGTH = 4;

    /** Length of the reason description ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). */
    static final int REASON_DESC_LENGTH = 76;

    /** Total fixed length of one reject record ({@code FD-REJS-RECORD}: 350 + 80 = 430). */
    static final int REJECT_RECORD_LENGTH = DALYTRAN_RECORD_LENGTH + TRAILER_LENGTH;

    /** Length of the {@code DALYTRAN-AMT S9(09)V99} zoned-decimal field (10 digits + 1 overpunch byte). */
    private static final int AMOUNT_FIELD_LENGTH = 11;

    /** Implied decimal places of {@code DALYTRAN-AMT S9(09)V99} (the {@code V99} fraction). */
    private static final int IMPLIED_DECIMAL_PLACES = 2;

    /** Width of the {@code DALYTRAN-CAT-CD PIC 9(04)} numeric field. */
    private static final int CAT_CD_LENGTH = 4;

    /** Width of the {@code DALYTRAN-MERCHANT-ID PIC 9(09)} numeric field. */
    private static final int MERCHANT_ID_LENGTH = 9;

    /** Width of the {@code DALYTRAN-ORIG-TS} / {@code DALYTRAN-PROC-TS PIC X(26)} timestamp fields. */
    private static final int TIMESTAMP_LENGTH = 26;

    /** Record terminator used to frame each fixed-width 430-byte record inside the S3 text object. */
    private static final char RECORD_TERMINATOR = '\n';

    /**
     * Charset for serializing the reject records: ISO-8859-1 maps one {@code char} to exactly one
     * byte, identical to the {@code ISO_8859_1} encoding {@code DailyTransactionReader} reads with, so
     * the re-serialized bytes 1-350 stay byte-faithful even for any non-ASCII merchant text.
     */
    private static final java.nio.charset.Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Fixed key prefix under which reject records are stored (GDG &rarr; S3, decision D-003). */
    private static final String S3_KEY_PREFIX = "rejects/";

    /** Suffix for the reject S3 object. */
    private static final String S3_OBJECT_SUFFIX = ".dat";

    /** Content type of the reject S3 object (plain text, fixed-width 430-byte lines). */
    private static final String REJECT_CONTENT_TYPE = "text/plain";

    /** Fallback S3 key segment when a daily-transaction id is null/blank. */
    private static final String UNKNOWN_KEY_SEGMENT = "unknown";

    /** Generation-prefix date pattern for the S3 key (emulates a GDG generation bucket). */
    private static final DateTimeFormatter S3_GENERATION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Formatter that renders {@link DailyTransaction} timestamps back to the exact 26-character
     * {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS} text form {@code yyyy-MM-dd HH:mm:ss.SSSSSS}
     * (10 + 1 + 8 + 1 + 6 = 26), the inverse of the reader's timestamp parse.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Auto-configured S3 template used to upload the reject object. The reject GDG generation becomes
     * a versioned S3 object (decision D-003); built by Spring Cloud AWS from {@code spring.cloud.aws.*},
     * so no client is hand-built and the endpoint resolves only to LocalStack.
     */
    private final S3Template s3Template;

    /**
     * Binder for the application-owned AWS resource names. The reject bucket is read from
     * {@code getS3().getBatchOutputBucket()} ({@code carddemo-batch-output}); it is never hardcoded.
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Clock backing the S3 key's {@code yyyyMMdd} generation prefix. Defaults to the system clock in
     * production and is injectable so tests can pin a fixed instant and assert the S3 key
     * deterministically.
     */
    private final Clock clock;

    /**
     * Production constructor used by Spring for component injection. Uses the system-default-zone
     * {@link Clock} so the S3 generation prefix reflects the current date.
     *
     * @param s3Template            the auto-configured S3 template (reject-object upload); must not be
     *                              {@code null}
     * @param awsResourceProperties the bound AWS resource-name properties supplying the batch-output
     *                              bucket; must not be {@code null}
     */
    @Autowired
    public RejectWriter(final S3Template s3Template,
                        final AwsConfig.AwsResourceProperties awsResourceProperties) {
        this(s3Template, awsResourceProperties, Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor allowing a fixed {@link Clock} so the generated S3 key is
     * deterministic. Behaves identically to the production constructor in every other respect.
     *
     * @param s3Template            the S3 template; must not be {@code null}
     * @param awsResourceProperties the bound AWS resource-name properties; must not be {@code null}
     * @param clock                 the clock used for the S3 generation prefix; must not be
     *                              {@code null}
     */
    public RejectWriter(final S3Template s3Template,
                        final AwsConfig.AwsResourceProperties awsResourceProperties,
                        final Clock clock) {
        this.s3Template = s3Template;
        this.awsResourceProperties = awsResourceProperties;
        this.clock = clock;
    }

    /**
     * Writes the rejected daily-transaction records of a chunk to the reject sink, reproducing the
     * COBOL {@code CBTRN02C} reject branch ({@code 2500-WRITE-REJECT-REC}).
     *
     * <p>Only {@link PostedTransactionResult#isRejected() rejected} items are written &mdash; mirroring
     * the COBOL routing that performs {@code 2500-WRITE-REJECT-REC} solely for records whose
     * {@code WS-VALIDATION-FAIL-REASON} is non-zero. Accepted items
     * ({@link PostedTransactionResult#isAccepted()} {@code == true}) are skipped defensively, since the
     * accepted path is owned by the sibling posting writer. When the chunk contains no rejects, no S3
     * object is written (the COBOL program only {@code WRITE}s a {@code DALYREJS} record when a reject
     * occurs).</p>
     *
     * <p>For each reject, a fixed 430-byte record is assembled by {@link #formatRejectRecord(PostedTransactionResult)}
     * (350-byte original record + 80-byte validation trailer) and the records are accumulated &mdash;
     * each followed by a {@code '\n'} record terminator &mdash; into a single payload uploaded to the
     * batch-output S3 bucket by {@link #writeToS3(byte[], String)}. The COBOL {@code WRITE} loop wrote
     * one record at a time to the {@code DALYREJS} GDG; here the chunk's rejects are batched into one
     * generation-prefixed S3 object (GDG&nbsp;&rarr;&nbsp;S3, decision D-003). Any S3 failure propagates
     * so the step fails, mirroring the COBOL {@code DALYREJS-STATUS} abend path.</p>
     *
     * @param chunk the Spring Batch chunk of validation outcomes; never {@code null}
     */
    // COBOL: CBTRN02C main loop reject branch -> PERFORM 2500-WRITE-REJECT-REC for non-zero
    // WS-VALIDATION-FAIL-REASON; accepted records (reason 0) go to 2000-POST-TRANSACTION instead.
    @Override
    public void write(final Chunk<? extends PostedTransactionResult> chunk) {
        // Build the 430-byte reject records for this chunk, preserving input (physical) order.
        final List<String> rejectRecords = new ArrayList<>(chunk.size());
        String firstRejectId = null;
        String lastRejectId = null;

        for (final PostedTransactionResult item : chunk) {
            // Skip accepted items defensively: only non-zero-reason records are rejected (COBOL
            // IF WS-VALIDATION-FAIL-REASON = 0 routes to posting, not to 2500-WRITE-REJECT-REC).
            if (item == null || !item.isRejected()) {
                continue;
            }
            rejectRecords.add(formatRejectRecord(item));

            // Track the first/last rejected daily-transaction id for the deterministic S3 key.
            final String dalytranId = item.originalTransaction().getDalytranId();
            if (firstRejectId == null) {
                firstRejectId = dalytranId;
            }
            lastRejectId = dalytranId;
        }

        // No rejects in this chunk -> nothing to write (COBOL only WRITEs DALYREJS on a reject).
        if (rejectRecords.isEmpty()) {
            return;
        }

        // Frame each fixed 430-byte record with a single '\n' terminator (RECFM=FB -> newline-framed
        // S3 text object); the per-record LRECL stays exactly 430 bytes.
        final StringBuilder payload = new StringBuilder(rejectRecords.size() * (REJECT_RECORD_LENGTH + 1));
        for (final String record : rejectRecords) {
            payload.append(record).append(RECORD_TERMINATOR);
        }

        final byte[] bytes = payload.toString().getBytes(RECORD_CHARSET);
        final String objectKey = buildObjectKey(firstRejectId, lastRejectId);
        // COBOL: WRITE FD-REJS-RECORD FROM REJECT-RECORD TO DALYREJS(+1) -> S3 PutObject.
        writeToS3(bytes, objectKey);
    }

    /**
     * Assembles one fixed-length 430-byte reject record from a rejected validation outcome,
     * reproducing the COBOL {@code 2500-WRITE-REJECT-REC} record assembly.
     *
     * <p>The record is {@code REJECT-TRAN-DATA (350)} + {@code VALIDATION-TRAILER (80)}, where the
     * trailer is the 4-digit zero-padded reason code ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)})
     * followed by the 76-char left-justified, space-padded reason description
     * ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). Both code and description are taken from the
     * canonical {@link RejectCode} so they round-trip byte-faithfully. The assembled length is asserted
     * to be exactly {@value #REJECT_RECORD_LENGTH} (defensive guard; a mismatch fails the step,
     * mirroring the COBOL abend on a malformed write).</p>
     *
     * @param item the rejected outcome (its {@link PostedTransactionResult#rejectCode()} and
     *             {@link PostedTransactionResult#originalTransaction()} are non-null by contract)
     * @return the exact {@value #REJECT_RECORD_LENGTH}-character reject record
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #REJECT_RECORD_LENGTH} characters
     */
    // COBOL: 2500-WRITE-REJECT-REC
    //   MOVE DALYTRAN-RECORD       TO REJECT-TRAN-DATA  (bytes 1-350)
    //   MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER (bytes 351-430:
    //        WS-VALIDATION-FAIL-REASON PIC 9(04) + WS-VALIDATION-FAIL-REASON-DESC PIC X(76))
    String formatRejectRecord(final PostedTransactionResult item) {
        final RejectCode rejectCode = item.rejectCode();

        // Bytes 1-350: the original daily-transaction record, byte-for-byte (CVTRA06Y).
        final String original350 = serializeDalytranRecord(item.originalTransaction());

        // Bytes 351-354: PIC 9(04) reason code, zero-padded 4 digits (e.g. 0100, 0109).
        final String code4 = String.format("%0" + REASON_CODE_LENGTH + "d", rejectCode.getCode());

        // Bytes 355-430: PIC X(76) reason description, left-justified and space-padded/truncated to 76.
        final String desc76 = String.format("%-" + REASON_DESC_LENGTH + "." + REASON_DESC_LENGTH + "s",
                nullToEmpty(rejectCode.getDescription()));

        final String trailer80 = code4 + desc76;
        final String rec430 = original350 + trailer80;

        // Defensive external-interface guard (AAP S0.7.2): the 350 + 4 + 76 = 430 LRECL is exact.
        if (rec430.length() != REJECT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Reject record must be exactly " + REJECT_RECORD_LENGTH + " characters (350 original + "
                            + "4 reason code + 76 description) but was " + rec430.length()
                            + " for reject code " + rejectCode.getCode());
        }
        return rec430;
    }

    /**
     * Re-serializes a parsed {@link DailyTransaction} into the exact {@code CVTRA06Y} fixed-width
     * 350-byte {@code DALYTRAN-RECORD} layout, the precise inverse of the tokenization performed by
     * {@code com.cardemo.batch.readers.DailyTransactionReader}.
     *
     * <p>This produces the byte-for-byte original record that the COBOL
     * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} copies into bytes 1-350 of the reject record.
     * Each field is written at its exact offset/length using the COBOL field semantics: {@code PIC X}
     * fields are left-justified and space-padded; {@code PIC 9} numeric fields are right-justified and
     * zero-padded; the {@code S9(09)V99} amount is encoded as a zoned-decimal overpunch (see
     * {@link #encodeSignedOverpunch(BigDecimal)}); and the two {@code PIC X(26)} timestamps are rendered
     * as {@code yyyy-MM-dd HH:mm:ss.SSSSSS} text (a {@code null}, e.g. an unposted {@code DALYTRAN-PROC-TS},
     * becomes 26 spaces, matching the blank field the reader mapped to {@code null}). The trailing
     * 20-byte {@code FILLER} is reproduced as spaces. The assembled length is asserted to be exactly
     * {@value #DALYTRAN_RECORD_LENGTH}.</p>
     *
     * <p>A {@code null} value for any field is rendered as a blank field of the exact width, which is
     * the faithful inverse of the reader's blank-&rarr;{@code null} mapping.</p>
     *
     * @param tran the parsed daily-transaction entity (never {@code null} by the input-item contract)
     * @return the exact {@value #DALYTRAN_RECORD_LENGTH}-character {@code DALYTRAN-RECORD}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #DALYTRAN_RECORD_LENGTH} characters
     */
    // COBOL: CVTRA06Y 01 DALYTRAN-RECORD (RECLN 350) -- field offsets reverse DailyTransactionReader.
    private String serializeDalytranRecord(final DailyTransaction tran) {
        final StringBuilder sb = new StringBuilder(DALYTRAN_RECORD_LENGTH);
        sb.append(padText(tran.getDalytranId(), 16));              //   1- 16 DALYTRAN-ID            X(16)
        sb.append(padText(tran.getDalytranTypeCd(), 2));           //  17- 18 DALYTRAN-TYPE-CD       X(02)
        sb.append(padNumeric(tran.getDalytranCatCd(), CAT_CD_LENGTH)); //  19- 22 DALYTRAN-CAT-CD    9(04)
        sb.append(padText(tran.getDalytranSource(), 10));          //  23- 32 DALYTRAN-SOURCE        X(10)
        sb.append(padText(tran.getDalytranDesc(), 100));           //  33-132 DALYTRAN-DESC          X(100)
        sb.append(encodeSignedOverpunch(tran.getDalytranAmt()));   // 133-143 DALYTRAN-AMT           S9(09)V99
        sb.append(padNumeric(tran.getDalytranMerchantId(), MERCHANT_ID_LENGTH)); // 144-152 MERCHANT-ID 9(09)
        sb.append(padText(tran.getDalytranMerchantName(), 50));    // 153-202 DALYTRAN-MERCHANT-NAME X(50)
        sb.append(padText(tran.getDalytranMerchantCity(), 50));    // 203-252 DALYTRAN-MERCHANT-CITY X(50)
        sb.append(padText(tran.getDalytranMerchantZip(), 10));     // 253-262 DALYTRAN-MERCHANT-ZIP  X(10)
        sb.append(padText(tran.getDalytranCardNum(), 16));         // 263-278 DALYTRAN-CARD-NUM      X(16)
        sb.append(formatTimestamp(tran.getDalytranOrigTs()));      // 279-304 DALYTRAN-ORIG-TS       X(26)
        sb.append(formatTimestamp(tran.getDalytranProcTs()));      // 305-330 DALYTRAN-PROC-TS       X(26)
        sb.append(padText(null, 20));                              // 331-350 FILLER                 X(20) -> spaces

        final String record = sb.toString();
        if (record.length() != DALYTRAN_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Re-serialized DALYTRAN-RECORD must be exactly " + DALYTRAN_RECORD_LENGTH
                            + " characters (CVTRA06Y) but was " + record.length()
                            + " for daily-transaction id '" + tran.getDalytranId() + "'");
        }
        return record;
    }

    /**
     * Renders a COBOL {@code PIC X(n)} alphanumeric field: left-justified, space-padded on the right
     * to exactly {@code width}, truncating any excess (the COBOL {@code MOVE} to a shorter {@code X}
     * field truncates on the right). A {@code null} value yields {@code width} spaces, the faithful
     * inverse of the reader's blank-&rarr;{@code null}/trim mapping.
     *
     * @param value the field value (may be {@code null})
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String padText(final String value, final int width) {
        final String safe = value == null ? "" : value;
        if (safe.length() == width) {
            return safe;
        }
        if (safe.length() > width) {
            // COBOL MOVE to a shorter alphanumeric field truncates on the right.
            return safe.substring(0, width);
        }
        // Left-justify, pad on the right with spaces to the fixed field width.
        final StringBuilder sb = new StringBuilder(width).append(safe);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Renders a COBOL unsigned {@code PIC 9(n)} numeric display field: right-justified and zero-padded
     * on the left to exactly {@code width}. A {@code null} value yields {@code width} spaces (the
     * faithful inverse of the reader mapping a blank numeric field to {@code null}); a value with more
     * than {@code width} digits is truncated on the high-order (left) side, matching COBOL numeric
     * {@code MOVE} truncation.
     *
     * @param value the numeric value (may be {@code null}); rendered from its absolute magnitude
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String padNumeric(final Number value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        // Unsigned PIC 9 display: use the absolute magnitude so no sign char enters the digits.
        final long magnitude = Math.abs(value.longValue());
        final String digits = String.format("%0" + width + "d", magnitude);
        // High-order truncation if the magnitude has more digits than the field (COBOL MOVE semantics).
        return digits.length() > width ? digits.substring(digits.length() - width) : digits;
    }

    /**
     * Encodes a {@link BigDecimal} amount into the 11-character COBOL zoned-decimal overpunch field
     * {@code DALYTRAN-AMT PIC S9(09)V99}, the exact inverse of the reader's overpunch decode.
     *
     * <p>The scale-2 amount is taken to its 11-digit signed magnitude ({@code abs(amount) *
     * 10^2}); the first ten digits are emitted as plain characters and the eleventh (last) digit is
     * folded together with the sign into the trailing <strong>overpunch</strong> byte:</p>
     * <ul>
     *   <li><strong>Positive:</strong> last digit 0&rarr;{@code '{'}, 1-9&rarr;{@code 'A'}..{@code 'I'}.</li>
     *   <li><strong>Negative:</strong> last digit 0&rarr;{@code '}'}, 1-9&rarr;{@code 'J'}..{@code 'R'}.</li>
     * </ul>
     * <p>Zero is treated as positive ({@code '{'}). Worked examples (matching the fixture):
     * {@code 504.77 -> "0000005047G"}; {@code -919.00 -> "0000009190}"}. No {@code float}/{@code double}
     * is used (AAP &sect;0.7.3). A {@code null} amount &mdash; which cannot arise for a record that was
     * successfully read (the reader requires the field) &mdash; defensively yields
     * {@value #AMOUNT_FIELD_LENGTH} spaces.</p>
     *
     * @param amount the monetary amount (scale 2 by the entity contract; may be {@code null} only
     *               defensively)
     * @return the exact {@value #AMOUNT_FIELD_LENGTH}-character zoned-decimal overpunch field
     */
    // COBOL: DALYTRAN-AMT PIC S9(09)V99 zoned-decimal overpunch (inverse of DailyTransactionReader#parseSignedOverpunch).
    private static String encodeSignedOverpunch(final BigDecimal amount) {
        if (amount == null) {
            return " ".repeat(AMOUNT_FIELD_LENGTH);
        }
        final boolean negative = amount.signum() < 0;
        // Unscaled 11-digit magnitude: abs(amount) at scale 2, decimal point moved right by 2.
        final java.math.BigInteger magnitude =
                amount.abs().movePointRight(IMPLIED_DECIMAL_PLACES).toBigInteger();
        // Eleven total digits (9 integer + 2 fractional), zero-padded on the left.
        final String digits11 = String.format("%0" + AMOUNT_FIELD_LENGTH + "d", magnitude);
        final String leadingDigits = digits11.substring(0, AMOUNT_FIELD_LENGTH - 1);
        final int lastDigit = digits11.charAt(AMOUNT_FIELD_LENGTH - 1) - '0';
        return leadingDigits + overpunchChar(lastDigit, negative);
    }

    /**
     * Maps a final digit (0-9) and a sign to its COBOL zoned-decimal overpunch character.
     *
     * @param lastDigit the last (units) digit, 0-9
     * @param negative  {@code true} for a negative value
     * @return the overpunch character ({@code '{'}/{@code 'A'}-{@code 'I'} positive;
     *         {@code '}'}/{@code 'J'}-{@code 'R'} negative)
     */
    private static char overpunchChar(final int lastDigit, final boolean negative) {
        if (lastDigit == 0) {
            return negative ? '}' : '{';
        }
        // Positive 1-9 -> 'A'..'I'; negative 1-9 -> 'J'..'R'.
        return (char) ((negative ? 'J' : 'A') + (lastDigit - 1));
    }

    /**
     * Renders a {@link LocalDateTime} back to the exact 26-character {@code DALYTRAN-ORIG-TS}/
     * {@code DALYTRAN-PROC-TS} text form {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. A {@code null} timestamp
     * (e.g. an unposted {@code DALYTRAN-PROC-TS}) yields {@value #TIMESTAMP_LENGTH} spaces, matching the
     * blank field the reader mapped to {@code null}.
     *
     * @param value the timestamp (may be {@code null})
     * @return a string of exactly {@value #TIMESTAMP_LENGTH} characters
     */
    private static String formatTimestamp(final LocalDateTime value) {
        if (value == null) {
            return " ".repeat(TIMESTAMP_LENGTH);
        }
        // SSSSSS emits exactly six fractional-second (microsecond) digits -> total width 26.
        final String text = value.format(TIMESTAMP_FORMATTER);
        // Defensive width guard: pad/truncate to the fixed 26-byte field if the rendering differs.
        return padText(text, TIMESTAMP_LENGTH);
    }

    /**
     * Returns the given string, or an empty string when it is {@code null}.
     *
     * @param value the value (may be {@code null})
     * @return {@code value}, or {@code ""} when {@code null}
     */
    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Uploads the chunk's assembled reject payload to AWS S3 &mdash; the new capability that replaces
     * the legacy {@code WRITE FD-REJS-RECORD} to the {@code DALYREJS} GDG generation (AAP &sect;0.1.2 /
     * &sect;0.7.7, decision D-003).
     *
     * <p>The destination bucket is resolved from configuration
     * ({@code awsResourceProperties.getS3().getBatchOutputBucket()} &rarr; {@code carddemo-batch-output});
     * it is never hardcoded. The upload uses the auto-configured {@link S3Template}, so the endpoint
     * resolves only to LocalStack (zero live AWS credentials). Any S3 failure propagates to the caller
     * so the Spring Batch step fails, mirroring the COBOL abend taken when {@code DALYREJS-STATUS} is
     * not {@code '00'} ({@code 2500} L457-L464).</p>
     *
     * @param payload   the serialized, newline-framed reject records (each record exactly 430 bytes);
     *                  never empty
     * @param objectKey the deterministic, generation-prefixed S3 object key
     */
    // COBOL: GDG DALYREJS(+1) WRITE -> S3 PutObject (GDG -> S3 versioned object, decision D-003).
    private void writeToS3(final byte[] payload, final String objectKey) {
        final String bucket = awsResourceProperties.getS3().getBatchOutputBucket();
        final ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(REJECT_CONTENT_TYPE)
                .contentLength((long) payload.length)
                .build();
        // ByteArrayInputStream is backed by the byte[] and holds no external resource, so it needs no
        // explicit close; S3Template fully consumes it during the upload.
        s3Template.upload(bucket, objectKey, new ByteArrayInputStream(payload), metadata);
    }

    /**
     * Builds the deterministic, generation-prefixed S3 key for a chunk's reject object:
     * {@code rejects/<yyyyMMdd>/<firstRejectId>-<lastRejectId>.dat}.
     *
     * <p>The {@code <yyyyMMdd>} segment (from {@link #clock}) emulates a GDG generation bucket; the
     * first/last rejected {@code DALYTRAN-ID} bound makes the key stable for the same chunk content so
     * a Spring Batch retry overwrites rather than duplicating (idempotent at-least-once write). Ids are
     * trimmed and any {@code '/'} is replaced so the key stays a single valid S3 path segment.</p>
     *
     * @param firstRejectId the first rejected daily-transaction id in the chunk (may be {@code null}/blank)
     * @param lastRejectId  the last rejected daily-transaction id in the chunk (may be {@code null}/blank)
     * @return the deterministic S3 object key
     */
    private String buildObjectKey(final String firstRejectId, final String lastRejectId) {
        final String generation = LocalDate.now(clock).format(S3_GENERATION_DATE);
        final String first = safeKeySegment(firstRejectId);
        final String last = safeKeySegment(lastRejectId);
        return S3_KEY_PREFIX + generation + "/" + first + "-" + last + S3_OBJECT_SUFFIX;
    }

    /**
     * Normalizes a daily-transaction id into a safe single S3 key segment by trimming surrounding
     * whitespace and replacing any path separator.
     *
     * @param dalytranId the daily-transaction id (may be {@code null} or blank)
     * @return a non-blank key segment ({@value #UNKNOWN_KEY_SEGMENT} when the id is {@code null}/blank)
     */
    private static String safeKeySegment(final String dalytranId) {
        if (dalytranId == null) {
            return UNKNOWN_KEY_SEGMENT;
        }
        final String trimmed = dalytranId.trim();
        return trimmed.isEmpty() ? UNKNOWN_KEY_SEGMENT : trimmed.replace('/', '_');
    }
}
