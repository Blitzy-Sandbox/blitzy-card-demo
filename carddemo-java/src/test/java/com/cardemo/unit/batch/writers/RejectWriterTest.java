package com.cardemo.unit.batch.writers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

/**
 * Unit test for {@link RejectWriter} &mdash; Java migration of {@code CBTRN02C}
 * {@code 2500-WRITE-REJECT-REC} 430-byte reject record (COBOL ref SHA {@code 27d6c6f}, not copied).
 *
 * <p>Fast, fully-mocked JUnit&nbsp;5 + Mockito + AssertJ unit test for the Spring Batch
 * {@code ItemWriter<PostedTransactionResult>} that reproduces the reject branch of the legacy AWS
 * CardDemo daily-posting batch program {@code app/cbl/CBTRN02C.cbl} (paragraph
 * {@code 2500-WRITE-REJECT-REC}, lines&nbsp;446-451). The COBOL source is <strong>read-only</strong>
 * reference at the frozen baseline commit SHA {@code 27d6c6f}; it is <strong>never copied</strong> into
 * this repository and is referenced only by SHA and paragraph locator (AAP &sect;0.7.2). The base
 * package is {@code com.cardemo} (decision <strong>D-006</strong>, deliberately <em>not</em>
 * {@code com.carddemo}).</p>
 *
 * <h2>External-interface byte contract under test (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>The reject record is a preserved external interface and must be reproduced <strong>byte-for-byte</strong>
 * with no "improvements" (Minimal Change Clause, AAP &sect;0.7.1). Each record is exactly
 * <strong>430 bytes</strong>, assembled from the COBOL {@code REJECT-RECORD} layout
 * ({@code REJECT-TRAN-DATA PIC X(350)} + {@code VALIDATION-TRAILER PIC X(80)}):</p>
 * <ul>
 *   <li><strong>Bytes [0,&nbsp;350)</strong> &mdash; {@code REJECT-TRAN-DATA}: the original
 *       {@code DALYTRAN-RECORD} ({@code CVTRA06Y}, RECLN 350), re-serialized verbatim from the
 *       {@link DailyTransaction} the carrier holds (COBOL {@code MOVE DALYTRAN-RECORD TO
 *       REJECT-TRAN-DATA}).</li>
 *   <li><strong>Bytes [350,&nbsp;354)</strong> &mdash; {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}: the
 *       4-digit reject reason code, zero-padded ({@code String.format("%04d", code)}; e.g. {@code 100}
 *       &rarr; {@code "0100"}).</li>
 *   <li><strong>Bytes [354,&nbsp;430)</strong> &mdash; {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}:
 *       the reason description, left-justified and space-padded/truncated to 76
 *       ({@code String.format("%-76.76s", desc)}).</li>
 * </ul>
 * <p>The production writer batches every reject of a chunk into a single S3 text object, framing each
 * fixed 430-byte record with one trailing {@code '\n'} (0x0A) record terminator (RECFM=FB &rarr;
 * newline-framed S3 lines, decision <strong>D-003</strong>), so an object of {@code N} rejects is
 * {@code N * 431} bytes encoded as {@link StandardCharsets#ISO_8859_1 ISO-8859-1} (one byte per char).
 * The object is uploaded to the config-resolved {@code carddemo-batch-output} bucket. Only the codes the
 * COBOL estate actually emits ({@code 100}, {@code 101}, {@code 102}, {@code 103}, {@code 109}) ever
 * appear; {@code 104}-{@code 108} do not exist.</p>
 *
 * <h2>Test strategy</h2>
 * <p>Pure unit test: {@code @ExtendWith(MockitoExtension.class)} with a mocked {@link S3Template}, a
 * <em>real</em> {@link AwsConfig.AwsResourceProperties} (its defaults expose the
 * {@code carddemo-batch-output} bucket) and a fixed {@link Clock} so the S3 generation prefix
 * ({@code 20240115}) is deterministic. No Spring context, database, real AWS, Testcontainers, or
 * LocalStack (those belong to {@code integration/batch/}). Mockito runs in default {@code STRICT_STUBS};
 * each test stubs only what its path consumes. The {@link S3Template} mock does not consume the captured
 * {@link InputStream}, so each captured stream is read back in full (ISO-8859-1) to assert the emitted
 * bytes. The 350-byte original is checked against an <em>independently</em> re-derived {@code CVTRA06Y}
 * serialization plus literal anchors cross-checked against {@code app/data/ASCII/dailytran.txt} record
 * #1 (which is reference ground-truth only and is deliberately not loaded).</p>
 *
 * @see RejectWriter
 * @see RejectCode
 * @see PostedTransactionResult
 * @see AwsConfig.AwsResourceProperties
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RejectWriter — CBTRN02C 2500-WRITE-REJECT-REC 430-byte reject record (SHA 27d6c6f)")
class RejectWriterTest {

    /** Config-resolved batch-output bucket exposed by the real {@link AwsConfig.AwsResourceProperties} defaults. */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /** Total fixed LRECL of one reject record ({@code FD-REJS-RECORD}: 350 + 80). */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Length of the original daily-transaction record ({@code CVTRA06Y}, RECLN 350). */
    private static final int DALYTRAN_RECORD_LENGTH = 350;

    /** Length of the validation trailer ({@code VALIDATION-TRAILER PIC X(80)}). */
    private static final int TRAILER_LENGTH = 80;

    /** Length of the 4-digit reason code ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    private static final int REASON_CODE_LENGTH = 4;

    /** Length of the reason description ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). */
    private static final int REASON_DESC_LENGTH = 76;

    /** Record terminator framing each fixed-width 430-byte record inside the S3 text object. */
    private static final char RECORD_TERMINATOR = '\n';

    /** Length of one framed record inside the S3 object (430-byte record + one terminator). */
    private static final int FRAMED_RECORD_LENGTH = REJECT_RECORD_LENGTH + 1;

    /** Charset of the emitted reject object (ISO-8859-1: one byte per char, matching the writer). */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Documented content type of the reject S3 object. */
    private static final String REJECT_CONTENT_TYPE = "text/plain";

    /**
     * Fixed instant ({@code 2024-01-15T10:20:30Z}) so the {@code yyyyMMdd} S3 generation prefix is
     * deterministic ({@code 20240115}); identical to the sibling writer tests' convention.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-15T10:20:30Z"), ZoneOffset.UTC);

    /** The deterministic generation prefix produced by {@link #FIXED_CLOCK}. */
    private static final String GENERATION_PREFIX = "20240115";

    /**
     * Formatter that re-derives the exact 26-char {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS} text
     * ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}); independent of, but identical in shape to, the production one.
     */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** Mocked S3 sink; the writer's only collaborator that performs I/O (GDG &rarr; S3, decision D-003). */
    @Mock
    private S3Template s3Template;

    /** Observability facade (AAP §0.7.7) — mocked so rejected-record recording is asserted. */
    @Mock
    private MetricsConfig.BusinessMetrics businessMetrics;

    /**
     * Real (not mocked) AWS resource-name holder. Its defaults expose
     * {@code getS3().getBatchOutputBucket() == carddemo-batch-output}, so the writer resolves the bucket
     * from configuration rather than a literal &mdash; and the test avoids any STRICT_STUBS noise.
     */
    private final AwsConfig.AwsResourceProperties awsProps = new AwsConfig.AwsResourceProperties();

    /** System under test, built directly with the mocked collaborator and a fixed clock. */
    private RejectWriter writer;

    @BeforeEach
    void setUp() {
        // Test-friendly constructor pins the clock so the S3 generation prefix is deterministic.
        writer = new RejectWriter(s3Template, awsProps, businessMetrics, FIXED_CLOCK);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures (in-memory only — app/data/ASCII/dailytran.txt is reference ground-truth, NOT loaded)
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the primary daily-transaction fixture, mirroring {@code dailytran.txt} record #1 so the
     * re-serialized 350-byte body has known, ground-truth anchors (notably the overpunch amount
     * {@code 504.77 -> "0000005047G"}). {@code DALYTRAN-PROC-TS} is intentionally left {@code null} to
     * exercise the blank 26-byte rendering.
     *
     * @return a fully-populated {@link DailyTransaction}
     */
    private static DailyTransaction sampleDailyTransaction() {
        return dailyTransaction("DTX0000000000001");
    }

    /**
     * Builds a populated daily-transaction fixture with the given 16-char id, all other fields fixed.
     *
     * @param id the {@code DALYTRAN-ID} (expected exactly 16 chars)
     * @return a populated {@link DailyTransaction}
     */
    private static DailyTransaction dailyTransaction(final String id) {
        final DailyTransaction t = new DailyTransaction();
        t.setDalytranId(id);                                       // X(16)
        t.setDalytranTypeCd("01");                                 // X(02)
        t.setDalytranCatCd(1);                                     // 9(04) -> "0001"
        t.setDalytranSource("POS TERM");                           // X(10) -> "POS TERM  "
        t.setDalytranDesc("PURCHASE AT TEST MERCHANT STORE");      // X(100)
        t.setDalytranAmt(new BigDecimal("504.77"));                // S9(09)V99 -> "0000005047G"
        t.setDalytranMerchantId(800000000L);                       // 9(09) -> "800000000"
        t.setDalytranMerchantName("TEST MERCHANT");                // X(50)
        t.setDalytranMerchantCity("TEST CITY");                    // X(50)
        t.setDalytranMerchantZip("12345");                         // X(10)
        t.setDalytranCardNum("4859452612877065");                  // X(16)
        t.setDalytranOrigTs(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 0)); // X(26)
        t.setDalytranProcTs(null);                                 // X(26) null -> 26 spaces
        return t;
    }

    /**
     * Wraps a daily transaction and reject reason into the carrier the writer consumes, on the reject
     * path: {@code transaction}=null, {@code account}=null, {@code crossReference}=null.
     *
     * @param code     the reject reason (non-{@link RejectCode#NONE})
     * @param original the raw daily-transaction record
     * @return a rejected {@link PostedTransactionResult}
     */
    private static PostedTransactionResult reject(final RejectCode code, final DailyTransaction original) {
        return new PostedTransactionResult(null, code, original, null, null);
    }

    /**
     * Wraps a daily transaction into an <em>accepted</em> carrier ({@link RejectCode#NONE}); such items
     * must be skipped by the reject writer.
     *
     * @param original the raw daily-transaction record
     * @return an accepted {@link PostedTransactionResult}
     */
    private static PostedTransactionResult accepted(final DailyTransaction original) {
        return new PostedTransactionResult(null, RejectCode.NONE, original, null, null);
    }

    // ---------------------------------------------------------------------------------------------
    // Independent CVTRA06Y layout helpers (re-derived from the copybook, separate from production)
    // ---------------------------------------------------------------------------------------------

    /** COBOL {@code PIC X(n)}: left-justified, space-padded on the right; truncates excess on the right. */
    private static String picX(final String value, final int width) {
        final String safe = value == null ? "" : value;
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        final StringBuilder sb = new StringBuilder(width).append(safe);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** COBOL unsigned {@code PIC 9(n)}: right-justified, zero-padded; {@code null} renders as spaces. */
    private static String pic9(final Number value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        final long magnitude = Math.abs(value.longValue());
        final String digits = String.format("%0" + width + "d", magnitude);
        return digits.length() > width ? digits.substring(digits.length() - width) : digits;
    }

    /**
     * COBOL {@code S9(09)V99} zoned-decimal overpunch (11 chars): the scale-2 magnitude is taken to 11
     * digits and the last digit is folded with the sign into the trailing overpunch byte
     * (positive 0&rarr;{@code '{'}, 1-9&rarr;{@code 'A'}-{@code 'I'}; negative 0&rarr;{@code '}'},
     * 1-9&rarr;{@code 'J'}-{@code 'R'}). {@code null} renders as 11 spaces.
     */
    private static String overpunch(final BigDecimal amount) {
        if (amount == null) {
            return " ".repeat(11);
        }
        final boolean negative = amount.signum() < 0;
        final BigInteger magnitude = amount.abs().movePointRight(2).toBigInteger();
        final String digits11 = String.format("%011d", magnitude);
        final String leading = digits11.substring(0, 10);
        final int lastDigit = digits11.charAt(10) - '0';
        final char overpunchChar;
        if (lastDigit == 0) {
            overpunchChar = negative ? '}' : '{';
        } else {
            overpunchChar = (char) ((negative ? 'J' : 'A') + (lastDigit - 1));
        }
        return leading + overpunchChar;
    }

    /** COBOL {@code PIC X(26)} timestamp text ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}); {@code null} = 26 spaces. */
    private static String ts26(final LocalDateTime value) {
        if (value == null) {
            return " ".repeat(26);
        }
        return picX(value.format(TS_FORMAT), 26);
    }

    /**
     * Independently re-derives the exact 350-byte {@code CVTRA06Y DALYTRAN-RECORD} layout from a
     * {@link DailyTransaction}, in field order, so it can be compared byte-for-byte with the writer's
     * own re-serialization.
     *
     * @param t the daily-transaction entity
     * @return the exact 350-character {@code DALYTRAN-RECORD}
     */
    private static String expectedDalytran350(final DailyTransaction t) {
        return picX(t.getDalytranId(), 16)                 //   1- 16 DALYTRAN-ID            X(16)
                + picX(t.getDalytranTypeCd(), 2)           //  17- 18 DALYTRAN-TYPE-CD       X(02)
                + pic9(t.getDalytranCatCd(), 4)            //  19- 22 DALYTRAN-CAT-CD        9(04)
                + picX(t.getDalytranSource(), 10)          //  23- 32 DALYTRAN-SOURCE        X(10)
                + picX(t.getDalytranDesc(), 100)           //  33-132 DALYTRAN-DESC          X(100)
                + overpunch(t.getDalytranAmt())            // 133-143 DALYTRAN-AMT           S9(09)V99
                + pic9(t.getDalytranMerchantId(), 9)       // 144-152 DALYTRAN-MERCHANT-ID   9(09)
                + picX(t.getDalytranMerchantName(), 50)    // 153-202 DALYTRAN-MERCHANT-NAME X(50)
                + picX(t.getDalytranMerchantCity(), 50)    // 203-252 DALYTRAN-MERCHANT-CITY X(50)
                + picX(t.getDalytranMerchantZip(), 10)     // 253-262 DALYTRAN-MERCHANT-ZIP  X(10)
                + picX(t.getDalytranCardNum(), 16)         // 263-278 DALYTRAN-CARD-NUM      X(16)
                + ts26(t.getDalytranOrigTs())              // 279-304 DALYTRAN-ORIG-TS       X(26)
                + ts26(t.getDalytranProcTs())              // 305-330 DALYTRAN-PROC-TS       X(26)
                + " ".repeat(20);                          // 331-350 FILLER                 X(20)
    }

    // ---------------------------------------------------------------------------------------------
    // Capture helpers (the S3Template mock does not consume the stream, so it can be read back fully)
    // ---------------------------------------------------------------------------------------------

    /** Reads a captured upload stream fully (it is a {@code ByteArrayInputStream} the mock never touched). */
    private static byte[] readBytes(final InputStream in) {
        try {
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decodes a captured upload stream as ISO-8859-1 (the writer's record charset). */
    private static String readIso(final InputStream in) {
        return new String(readBytes(in), RECORD_CHARSET);
    }

    /**
     * Verifies that exactly one S3 upload occurred and returns its payload decoded as ISO-8859-1.
     *
     * @return the full emitted S3 object content
     */
    private String captureSingleUploadPayload() {
        final ArgumentCaptor<InputStream> contentCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(s3Template).upload(any(String.class), any(String.class),
                contentCaptor.capture(), any(ObjectMetadata.class));
        return readIso(contentCaptor.getValue());
    }

    // ---------------------------------------------------------------------------------------------
    // 430-byte LRECL + framing
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("one reject -> a single 430-byte record (350 + 80) framed by one 0x0A terminator (431 bytes)")
    void write_rejectRecordIsExactly430Bytes() {
        writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, sampleDailyTransaction())));

        final String payload = captureSingleUploadPayload();

        // The S3 text object frames the single fixed-width record with one trailing terminator.
        assertThat(payload).hasSize(FRAMED_RECORD_LENGTH);
        assertThat(payload.charAt(REJECT_RECORD_LENGTH)).isEqualTo(RECORD_TERMINATOR);

        // The reject LRECL itself (the framed record minus the terminator) is exactly 430 = 350 + 80.
        final String record = payload.substring(0, REJECT_RECORD_LENGTH);
        assertThat(record).hasSize(REJECT_RECORD_LENGTH);
        assertThat(REJECT_RECORD_LENGTH).isEqualTo(DALYTRAN_RECORD_LENGTH + TRAILER_LENGTH);
    }

    // ---------------------------------------------------------------------------------------------
    // Bytes [0,350): original DALYTRAN-RECORD preserved verbatim
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("bytes [0,350) are the original DALYTRAN-RECORD re-serialized verbatim (CVTRA06Y)")
    void write_preservesOriginal350BytesVerbatim() {
        final DailyTransaction original = sampleDailyTransaction();

        writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, original)));

        final String record = captureSingleUploadPayload().substring(0, REJECT_RECORD_LENGTH);
        final String original350 = record.substring(0, DALYTRAN_RECORD_LENGTH);

        // COBOL MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA: compare against the independently re-derived
        // 350-byte CVTRA06Y serialization, byte-for-byte.
        assertThat(original350).hasSize(DALYTRAN_RECORD_LENGTH).isEqualTo(expectedDalytran350(original));

        // Ground-truth anchors (cross-checked against app/data/ASCII/dailytran.txt record #1):
        assertThat(original350.substring(0, 16)).isEqualTo("DTX0000000000001");          // DALYTRAN-ID  X(16)
        assertThat(original350.substring(16, 18)).isEqualTo("01");                        // TYPE-CD      X(02)
        assertThat(original350.substring(18, 22)).isEqualTo("0001");                      // CAT-CD       9(04) zero-padded
        assertThat(original350.substring(22, 32)).isEqualTo("POS TERM  ");                // SOURCE       X(10) space-padded
        assertThat(original350.substring(132, 143)).isEqualTo("0000005047G");             // AMT          S9(09)V99 overpunch (504.77)
        assertThat(original350.substring(143, 152)).isEqualTo("800000000");               // MERCHANT-ID  9(09)
        assertThat(original350.substring(262, 278)).isEqualTo("4859452612877065");        // CARD-NUM     X(16)
        assertThat(original350.substring(278, 304)).isEqualTo("2022-06-10 19:27:53.000000"); // ORIG-TS   X(26)
        assertThat(original350.substring(304, 330)).isEqualTo(" ".repeat(26));            // PROC-TS      X(26) null -> spaces
        assertThat(original350.substring(330, 350)).isEqualTo(" ".repeat(20));            // FILLER       X(20) -> spaces
    }

    // ---------------------------------------------------------------------------------------------
    // Bytes [350,354): 4-digit zero-padded reason code
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("bytes [350,354) are the PIC 9(04) reason code, zero-padded to width 4 (100 -> \"0100\")")
    void write_trailerCodeIsFourDigitZeroPadded() {
        writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, sampleDailyTransaction())));

        final String record = captureSingleUploadPayload().substring(0, REJECT_RECORD_LENGTH);
        final String codeField =
                record.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH);

        assertThat(codeField).hasSize(REASON_CODE_LENGTH).containsOnlyDigits();
        assertThat(codeField).isEqualTo("0100")
                .isEqualTo(String.format("%04d", RejectCode.INVALID_CARD_NUMBER.getCode()));

        // Observability wiring (AAP §0.7.7): each rejected record increments the rejected-counter
        // tagged with the reject-code name, proving BusinessMetrics is invoked by the reject flow.
        verify(businessMetrics).recordBatchRecordRejected("INVALID_CARD_NUMBER");
    }

    // ---------------------------------------------------------------------------------------------
    // Bytes [354,430): 76-char left-justified, space-padded reason description
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("bytes [354,430) are the PIC X(76) reason description, left-justified + space-padded to 76")
    void write_trailerDescriptionIsRightPaddedTo76Chars() {
        // Use the longest real description (42 chars) to exercise the padding without truncation.
        final RejectCode code = RejectCode.TRANSACTION_AFTER_EXPIRATION;

        writer.write(Chunk.of(reject(code, sampleDailyTransaction())));

        final String record = captureSingleUploadPayload().substring(0, REJECT_RECORD_LENGTH);
        final String descField =
                record.substring(DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH, REJECT_RECORD_LENGTH);

        // (a) the trailer description slice is exactly 76 chars and equals %-76.76s of the real description.
        assertThat(descField).hasSize(REASON_DESC_LENGTH)
                .isEqualTo(String.format("%-76.76s", code.getDescription()));
        // (b) it starts with the verbatim enum description text.
        assertThat(descField).startsWith(code.getDescription());
        // (c) the remainder is pure spaces (no NULs, no truncation of a <76 description).
        final String remainder = descField.substring(code.getDescription().length());
        assertThat(remainder).isEqualTo(" ".repeat(REASON_DESC_LENGTH - code.getDescription().length()));
        assertThat(descField).doesNotContain("\u0000");
    }

    // ---------------------------------------------------------------------------------------------
    // Every reject code -> correct trailer
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "reject code {0}")
    @EnumSource(value = RejectCode.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every reject code -> [350,354] = %04d(code) and [354,430] = %-76.76s(description)")
    void write_allRejectCodes_produceCorrectTrailer(final RejectCode code) {
        writer.write(Chunk.of(reject(code, sampleDailyTransaction())));

        final String record = captureSingleUploadPayload().substring(0, REJECT_RECORD_LENGTH);
        final String codeField =
                record.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH);
        final String descField =
                record.substring(DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH, REJECT_RECORD_LENGTH);

        assertThat(codeField).isEqualTo(String.format("%04d", code.getCode()));
        assertThat(descField).isEqualTo(String.format("%-76.76s", code.getDescription()));
        // The only reject codes the COBOL estate emits are 100,101,102,103,109 (never 104-108).
        assertThat(code.getCode()).isIn(100, 101, 102, 103, 109);
    }

    @Test
    @DisplayName("RejectCode set is exactly {0,100,101,102,103,109}; codes 104-108 do not exist")
    void rejectCodeSet_isExactlyExpectedAndExcludes104To108() {
        final Set<Integer> allCodes = Arrays.stream(RejectCode.values())
                .map(RejectCode::getCode)
                .collect(Collectors.toSet());
        assertThat(allCodes).containsExactlyInAnyOrder(0, 100, 101, 102, 103, 109);

        final Set<Integer> rejectionCodes = Arrays.stream(RejectCode.values())
                .filter(RejectCode::isRejection)
                .map(RejectCode::getCode)
                .collect(Collectors.toSet());
        assertThat(rejectionCodes).containsExactlyInAnyOrder(100, 101, 102, 103, 109);
        assertThat(rejectionCodes).doesNotContain(104, 105, 106, 107, 108);
    }

    // ---------------------------------------------------------------------------------------------
    // S3 persistence: bucket, framing of multiple rejects, metadata, key
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the reject object is uploaded to the config-resolved carddemo-batch-output bucket")
    void write_emitsToS3OutputBucket() {
        writer.write(Chunk.of(reject(RejectCode.OVERLIMIT_TRANSACTION, sampleDailyTransaction())));

        final ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Template).upload(bucketCaptor.capture(), any(String.class),
                any(InputStream.class), any(ObjectMetadata.class));

        // The bucket is never hardcoded: it must equal the value the config holder resolves.
        assertThat(bucketCaptor.getValue())
                .isEqualTo(BATCH_OUTPUT_BUCKET)
                .isEqualTo(awsProps.getS3().getBatchOutputBucket());
    }

    @Test
    @DisplayName("multiple rejects in one chunk -> one S3 object of N*431 framed records, in input order")
    void write_multipleRejects_allWritten() {
        final DailyTransaction first = dailyTransaction("DTX0000000000001");
        final DailyTransaction second = dailyTransaction("DTX0000000000002");

        writer.write(Chunk.of(
                reject(RejectCode.INVALID_CARD_NUMBER, first),       // code 100
                reject(RejectCode.OVERLIMIT_TRANSACTION, second)));  // code 102

        // All rejects of a chunk are batched into exactly ONE S3 object (one upload).
        final String payload = captureSingleUploadPayload();
        assertThat(payload).hasSize(2 * FRAMED_RECORD_LENGTH);

        // Record 0 occupies [0,430) with a terminator at 430; record 1 occupies [431,861) with one at 861.
        assertThat(payload.charAt(REJECT_RECORD_LENGTH)).isEqualTo(RECORD_TERMINATOR);
        assertThat(payload.charAt(FRAMED_RECORD_LENGTH + REJECT_RECORD_LENGTH)).isEqualTo(RECORD_TERMINATOR);

        final String record0 = payload.substring(0, REJECT_RECORD_LENGTH);
        final String record1 = payload.substring(FRAMED_RECORD_LENGTH, FRAMED_RECORD_LENGTH + REJECT_RECORD_LENGTH);
        assertThat(record0).hasSize(REJECT_RECORD_LENGTH);
        assertThat(record1).hasSize(REJECT_RECORD_LENGTH);

        // Order preserved: first record carries id #1 + code 0100; second carries id #2 + code 0102.
        assertThat(record0.substring(0, 16)).isEqualTo("DTX0000000000001");
        assertThat(record0.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH))
                .isEqualTo("0100");
        assertThat(record1.substring(0, 16)).isEqualTo("DTX0000000000002");
        assertThat(record1.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH))
                .isEqualTo("0102");

        // Observability wiring (AAP §0.7.7): one rejected-counter increment per record, each tagged
        // with its own distinct reject-code name (proves per-reason tagging across a multi-item chunk).
        verify(businessMetrics).recordBatchRecordRejected("INVALID_CARD_NUMBER");
        verify(businessMetrics).recordBatchRecordRejected("OVERLIMIT_TRANSACTION");
    }

    @Test
    @DisplayName("upload metadata is text/plain with content length equal to the emitted byte count")
    void write_uploadMetadataIsTextPlainWithExactLength() {
        writer.write(Chunk.of(reject(RejectCode.ACCOUNT_NOT_FOUND, sampleDailyTransaction())));

        final ArgumentCaptor<InputStream> contentCaptor = ArgumentCaptor.forClass(InputStream.class);
        final ArgumentCaptor<ObjectMetadata> metaCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(s3Template).upload(any(String.class), any(String.class),
                contentCaptor.capture(), metaCaptor.capture());

        final byte[] bytes = readBytes(contentCaptor.getValue());
        final ObjectMetadata metadata = metaCaptor.getValue();
        assertThat(metadata.getContentType()).isEqualTo(REJECT_CONTENT_TYPE);
        assertThat(metadata.getContentLength()).isEqualTo((long) bytes.length);
        // One reject -> one framed 431-byte object; ISO-8859-1 keeps one byte per char.
        assertThat(bytes).hasSize(FRAMED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("S3 key is rejects/<yyyyMMdd>/<firstId>-<lastId>.dat (GDG generation prefix from the clock)")
    void write_s3ObjectKeyIsGenerationPrefixed() {
        writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, dailyTransaction("DTX0000000000001"))));

        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Template).upload(any(String.class), keyCaptor.capture(),
                any(InputStream.class), any(ObjectMetadata.class));

        // Single reject -> first id == last id; generation prefix 20240115 from the fixed clock.
        assertThat(keyCaptor.getValue())
                .isEqualTo("rejects/" + GENERATION_PREFIX + "/DTX0000000000001-DTX0000000000001.dat");
    }

    // ---------------------------------------------------------------------------------------------
    // Defensive routing (COBOL WRITEs DALYREJS only on a non-zero WS-VALIDATION-FAIL-REASON)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a chunk of only accepted items writes no reject object")
    void write_noRejects_doesNotCallS3() {
        writer.write(Chunk.of(accepted(sampleDailyTransaction())));

        verifyNoInteractions(s3Template);
    }

    @Test
    @DisplayName("an empty chunk performs no upload")
    void write_emptyChunk_doesNotCallS3() {
        writer.write(new Chunk<PostedTransactionResult>());

        verifyNoInteractions(s3Template);
    }

    @Test
    @DisplayName("a null item is skipped; only the real reject is emitted (one framed record)")
    void write_nullItemSkipped() {
        writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, sampleDailyTransaction()), null));

        final String payload = captureSingleUploadPayload();
        assertThat(payload).hasSize(FRAMED_RECORD_LENGTH);

        final String record = payload.substring(0, REJECT_RECORD_LENGTH);
        assertThat(record.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH))
                .isEqualTo("0100");
    }

    @Test
    @DisplayName("null optional DALYTRAN fields serialize as blank fixed-width fields (inverse of reader blank->null)")
    void write_nullOptionalFields_serializeAsBlankFixedWidthFields() {
        // Only the id is populated; every other field is null and must render as its blank fixed width.
        final DailyTransaction sparse = new DailyTransaction();
        sparse.setDalytranId("DTX0000000000009");

        writer.write(Chunk.of(reject(RejectCode.ACCOUNT_NOT_FOUND_ON_UPDATE, sparse)));

        final String record = captureSingleUploadPayload().substring(0, REJECT_RECORD_LENGTH);
        final String original350 = record.substring(0, DALYTRAN_RECORD_LENGTH);

        // Independently derived: the 16-char id followed by 334 blank bytes for every unset field.
        assertThat(original350).isEqualTo(expectedDalytran350(sparse));
        assertThat(original350.substring(0, 16)).isEqualTo("DTX0000000000009");
        assertThat(original350.substring(16, DALYTRAN_RECORD_LENGTH))
                .isEqualTo(" ".repeat(DALYTRAN_RECORD_LENGTH - 16));

        // The trailer still carries the reject code/description even when the body is sparse (109).
        assertThat(record.substring(DALYTRAN_RECORD_LENGTH, DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH))
                .isEqualTo("0109");
        assertThat(record.substring(DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH, REJECT_RECORD_LENGTH))
                .isEqualTo(String.format("%-76.76s", RejectCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription()));
    }

    // ---------------------------------------------------------------------------------------------
    // Write-error parity (COBOL DALYREJS-STATUS not '00' -> 9999-ABEND-PROGRAM)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an S3 upload failure propagates so the Spring Batch step fails (COBOL ABEND-on-write parity)")
    void write_s3FailurePropagates() {
        when(s3Template.upload(any(String.class), any(String.class),
                any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        assertThatThrownBy(() ->
                writer.write(Chunk.of(reject(RejectCode.INVALID_CARD_NUMBER, sampleDailyTransaction()))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("S3 unavailable");

        // The single upload was attempted (and failed); nothing is silently swallowed.
        verify(s3Template, times(1)).upload(any(String.class), any(String.class),
                any(InputStream.class), any(ObjectMetadata.class));
    }
}
