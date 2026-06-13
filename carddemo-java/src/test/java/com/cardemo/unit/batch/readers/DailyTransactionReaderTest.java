package com.cardemo.unit.batch.readers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

/**
 * Fast, pure-JVM unit test for {@link DailyTransactionReader} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.5.x migration of the legacy AWS CardDemo batch reader
 * <strong>{@code CBTRN01C}</strong> ("Post the records from daily transaction file"). The single
 * piece of non-trivial, pure-JVM-testable logic this reader carries is the conversion of the COBOL
 * <strong>zoned-decimal, trailing-sign overpunch</strong> field
 * {@code DALYTRAN-AMT PIC S9(09)V99} (record bytes 133-143 of the 350-byte {@code DALYTRAN-RECORD})
 * into a {@link java.math.BigDecimal} of <strong>scale exactly 2</strong>. That decode is
 * <strong>THE</strong> focus of this test: it is the parity guardian for the COBOL money
 * representation.
 *
 * <h2>Provenance / governance (AAP &sect;0.7.1, &sect;0.7.2, &sect;0.7.7)</h2>
 * <p>Parity source: COBOL {@code app/cbl/CBTRN01C.cbl} (sequential read cycle
 * {@code 1000-DALYTRAN-GET-NEXT}); record layout {@code app/cpy/CVTRA06Y.cpy}
 * ({@code DALYTRAN-AMT PIC S9(09)V99}, {@code RECLN 350}); golden fixture
 * {@code app/data/ASCII/dailytran.txt} (300 records, each exactly 350 bytes). Every worked example
 * asserted below was verified to appear verbatim at bytes 133-143 of that fixture. The COBOL is
 * <strong>referenced, never copied</strong>; traceability is by the frozen legacy baseline commit
 * SHA {@code 27d6c6f} only. The application base package is {@code com.cardemo} (decision
 * <strong>D-006</strong>, deliberately <em>not</em> {@code com.carddemo}).
 *
 * <h2>Why this test drives the public {@link FlatFileItemReader} seam in-memory</h2>
 * <p>This test lives in {@code com.cardemo.unit.batch.readers}, a <em>different</em> package from the
 * production {@code com.cardemo.batch.readers}. The production decode helper
 * ({@code parseSignedOverpunch}), the {@code FixedLengthTokenizer} factory and the
 * {@code FieldSetMapper} are all <strong>package-private</strong>, so they are not visible here, and
 * reflection is forbidden (AAP &sect;0.7.8 unsafe-code audit). The only <strong>public</strong> seam
 * that exercises the overpunch decode is the reader factory
 * {@link DailyTransactionReader#dailyTransactionItemReader(String)}, which returns a
 * {@link FlatFileItemReader}. This test therefore drives that real reader against an
 * <strong>in-memory</strong> {@link ByteArrayResource} (constructed 350-byte fixed-width records),
 * supplied through a mocked {@link ResourceLoader} so that no real file or S3 object is ever
 * touched. A {@link ByteArrayResource} is an in-memory buffer, not a real file/S3 object, so the
 * "no real I/O" rule is honoured. The {@link AwsConfig.AwsResourceProperties} collaborator is a
 * <em>real</em> instance (its S3 bucket name defaults to {@code carddemo-batch-input}), so no nested
 * mocking is required. Calling the {@code @Bean}/{@code @StepScope} factory method directly is an
 * ordinary method call &mdash; those annotations are inert without a Spring context.
 *
 * <h2>Test isolation &amp; decimal-fidelity rules honoured (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li>No {@code @SpringBootTest} / no Spring {@code ApplicationContext}; no {@code spring-batch-test};
 *       no database; no AWS/S3/SQS; no Testcontainers; no reflection; no real file/S3 I/O. Pure,
 *       millisecond, in-memory tests using only the {@code spring-boot-starter-test} bundle
 *       (JUnit&nbsp;5 + Mockito + AssertJ) plus in-memory {@code spring-batch-infrastructure} /
 *       {@code spring-core} helpers already on the classpath.</li>
 *   <li>Every decoded amount is compared with {@code compareTo} (via AssertJ
 *       {@code isEqualByComparingTo}), <strong>never</strong> {@code equals} (which is
 *       scale-sensitive), and its {@code scale()} is asserted to be exactly {@code 2} and its
 *       {@code signum()} asserted for both sign families. No {@code float}/{@code double} appears
 *       anywhere &mdash; {@link BigDecimal} only.</li>
 * </ul>
 *
 * @see DailyTransactionReader
 * @see DailyTransaction
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CBTRN01C DailyTransactionReader — DALYTRAN-AMT zoned-decimal overpunch decode (pure-JVM)")
class DailyTransactionReaderTest {

    /**
     * Total fixed record length of the {@code DALYTRAN} feed ({@code CVTRA06Y}, {@code RECLN 350}).
     * Declared locally because the production constant is package-private and not visible to this
     * cross-package test. The reader's strict {@code FixedLengthTokenizer} requires each line to be
     * exactly this many characters.
     */
    private static final int RECORD_LENGTH = 350;

    /** Object key passed to the reader factory; the mocked loader ignores it (any string matches). */
    private static final String OBJECT_KEY = "dailytran.txt";

    /**
     * Mocked Spring {@link ResourceLoader}. Stubbed per test to resolve the reader's
     * {@code s3://<bucket>/<key>} location to an in-memory {@link ByteArrayResource}, so the real
     * {@link FlatFileItemReader} reads constructed records without any real file or S3 access.
     */
    @Mock
    private ResourceLoader resourceLoader;

    /** Class under test &mdash; the reader factory, instantiated directly (no Spring context). */
    private DailyTransactionReader readerProvider;

    @BeforeEach
    void setUp() {
        // Real AwsResourceProperties: getS3().getBatchInputBucket() defaults to "carddemo-batch-input"
        // (non-null), so the factory builds a valid s3:// location without any nested mocking.
        readerProvider = new DailyTransactionReader(new AwsConfig.AwsResourceProperties(), resourceLoader);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase B — positive overpunch decode (COBOL DALYTRAN-AMT, trailing-sign zoned decimal).
    // ---------------------------------------------------------------------------------------------

    /**
     * Positive overpunch family: the trailing glyph {@code '{'} encodes +0 and {@code 'A'..'I'}
     * encode +1..+9. The eleven digits form the unsigned magnitude with the implied {@code V99}
     * decimal point before the last two digits, so the decoded value has scale exactly 2 and a
     * positive sign. Every token below was verified at bytes 133-143 of
     * {@code app/data/ASCII/dailytran.txt}.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1} (positive overpunch)")
    @CsvSource({
        "0000005047G,504.77",
        "0000000678H,67.88",
        "0000004546F,454.66",
        "0000008499I,849.99",
        "0000004161A,416.11",
        "0000002502B,250.22",
        "0000000943C,94.33",
        "0000003250{,325.00"
    })
    @DisplayName("CBTRN01C DALYTRAN-AMT: positive overpunch ({,A-I) -> BigDecimal scale 2, signum +1")
    void positiveOverpunchDecodesToScaleTwoPositive(final String rawToken, final String expected)
            throws Exception {
        final DailyTransaction transaction = mapSingleRecord(buildRecordWithAmount(rawToken));
        final BigDecimal actual = transaction.getDalytranAmt();

        // §0.7.3: value compared by compareTo (AssertJ isEqualByComparingTo), never scale-sensitive equals.
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.signum()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase C — negative overpunch decode (sign carried by the trailing glyph).
    // ---------------------------------------------------------------------------------------------

    /**
     * Negative overpunch family: the trailing glyph {@code '}'} encodes -0 and {@code 'J'..'R'}
     * encode -1..-9. Each decodes to a scale-2 {@link BigDecimal} with a negative sign. Every token
     * below was verified at bytes 133-143 of {@code app/data/ASCII/dailytran.txt}.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1} (negative overpunch)")
    @CsvSource({
        "0000009190},-919.00",
        "0000000567P,-56.77",
        "0000005358Q,-535.88",
        "0000000709R,-70.99"
    })
    @DisplayName("CBTRN01C DALYTRAN-AMT: negative overpunch (},J-R) -> BigDecimal scale 2, signum -1")
    void negativeOverpunchDecodesToScaleTwoNegative(final String rawToken, final String expected)
            throws Exception {
        final DailyTransaction transaction = mapSingleRecord(buildRecordWithAmount(rawToken));
        final BigDecimal actual = transaction.getDalytranAmt();

        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.signum()).isEqualTo(-1);
    }

    /**
     * Parity-critical: {@code '}'} is the <em>negative-zero</em> overpunch glyph. Applied to a
     * non-zero magnitude ({@code 0000009190}} = magnitude {@code 00000091900}) the result is
     * <strong>negative</strong> ({@code -919.00}), proving the sign is taken from the trailing glyph
     * and not from the magnitude.
     */
    @Test
    @DisplayName("CBTRN01C DALYTRAN-AMT: '}' is negative-zero overpunch (sign from glyph, not magnitude)")
    void negativeZeroGlyphTakesSignFromGlyphNotMagnitude() throws Exception {
        final DailyTransaction transaction = mapSingleRecord(buildRecordWithAmount("0000009190}"));
        final BigDecimal actual = transaction.getDalytranAmt();

        assertThat(actual).isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(actual.signum()).isEqualTo(-1);
        assertThat(actual.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase D — scale & comparison-semantics guards (§0.7.3).
    // ---------------------------------------------------------------------------------------------

    /**
     * Documents the §0.7.3 comparison rule: a decoded amount is value-equal to the same number at a
     * different scale under {@code compareTo} (AssertJ {@code isEqualByComparingTo}) but is
     * <strong>not</strong> {@code equals}-equal to it, because {@link BigDecimal#equals(Object)} is
     * scale-sensitive. This is exactly why parity comparisons must use {@code compareTo}. The decoded
     * value's own scale is fixed at 2.
     */
    @Test
    @DisplayName("CBTRN01C DALYTRAN-AMT: compared by compareTo at scale 2, never scale-sensitive equals")
    void amountIsComparedByCompareToNotEquals() throws Exception {
        final DailyTransaction transaction = mapSingleRecord(buildRecordWithAmount("0000005047G"));
        final BigDecimal actual = transaction.getDalytranAmt();

        assertThat(actual.compareTo(new BigDecimal("504.77"))).isZero();
        assertThat(actual.scale()).isEqualTo(2);
        // Same numeric value, different scale: compareTo-equal yet NOT equals-equal.
        assertThat(actual).isEqualByComparingTo(new BigDecimal("504.7700"));
        assertThat(actual).isNotEqualTo(new BigDecimal("504.7700"));
    }

    /**
     * Constructed (not from the fixture) true-zero edge: a zero magnitude has {@code signum() == 0}
     * regardless of whether the overpunch glyph is positive ({@code '{'}) or negative ({@code '}'}),
     * while the scale is still exactly 2. The eleven-character tokens are ten {@code '0'} digits plus
     * the glyph (the {@code DALYTRAN-AMT} field is exactly 11 characters).
     */
    @ParameterizedTest(name = "[{index}] true-zero magnitude \"{0}\" -> 0.00 (signum 0)")
    @CsvSource({"0000000000{", "0000000000}"})
    @DisplayName("CBTRN01C DALYTRAN-AMT: zero magnitude is signum 0 regardless of overpunch glyph")
    void zeroMagnitudeHasSignumZeroRegardlessOfGlyph(final String rawToken) throws Exception {
        final DailyTransaction transaction = mapSingleRecord(buildRecordWithAmount(rawToken));
        final BigDecimal actual = transaction.getDalytranAmt();

        assertThat(actual).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.signum()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Phase E — full fixed-width field mapping through the real tokenizer + field-set mapper.
    // ---------------------------------------------------------------------------------------------

    /**
     * Maps one complete, valid 350-byte record and asserts every mapped {@link DailyTransaction}
     * field: the overpunch amount ({@code 504.77}, scale 2), the numeric category ({@link Integer})
     * and merchant id ({@link Long}), the trimmed text fields, the parsed {@code DALYTRAN-ORIG-TS}
     * ({@link LocalDateTime}) and the blank {@code DALYTRAN-PROC-TS} (mapped to {@code null}). This
     * exercises the strict {@code FixedLengthTokenizer} + {@code FieldSetMapper} contract end-to-end.
     */
    @Test
    @DisplayName("CBTRN01C CVTRA06Y: full 350-byte record maps every DALYTRAN field (amount scale 2)")
    void fullRecordMapsEveryField() throws Exception {
        final String line = buildRecord(
                "TXN0000000000042", // DALYTRAN-ID
                "05",               // DALYTRAN-TYPE-CD
                "0007",             // DALYTRAN-CAT-CD  -> 7
                "POS",              // DALYTRAN-SOURCE
                "GROCERY PURCHASE", // DALYTRAN-DESC
                "0000005047G",      // DALYTRAN-AMT     -> 504.77
                "000123456",        // DALYTRAN-MERCHANT-ID -> 123456
                "ACME STORE",       // DALYTRAN-MERCHANT-NAME
                "SEATTLE",          // DALYTRAN-MERCHANT-CITY
                "98101",            // DALYTRAN-MERCHANT-ZIP
                "4111111111111111", // DALYTRAN-CARD-NUM
                "2024-01-15 10:30:00", // DALYTRAN-ORIG-TS -> 2024-01-15T10:30
                "");                // DALYTRAN-PROC-TS (blank -> null)

        final DailyTransaction transaction = mapSingleRecord(line);

        assertThat(transaction).isNotNull();
        assertThat(transaction.getDalytranId()).isEqualTo("TXN0000000000042");
        assertThat(transaction.getDalytranTypeCd()).isEqualTo("05");
        assertThat(transaction.getDalytranCatCd()).isEqualTo(7);
        assertThat(transaction.getDalytranSource()).isEqualTo("POS");
        assertThat(transaction.getDalytranDesc()).isEqualTo("GROCERY PURCHASE");
        assertThat(transaction.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(transaction.getDalytranAmt().scale()).isEqualTo(2);
        assertThat(transaction.getDalytranMerchantId()).isEqualTo(123456L);
        assertThat(transaction.getDalytranMerchantName()).isEqualTo("ACME STORE");
        assertThat(transaction.getDalytranMerchantCity()).isEqualTo("SEATTLE");
        assertThat(transaction.getDalytranMerchantZip()).isEqualTo("98101");
        assertThat(transaction.getDalytranCardNum()).isEqualTo("4111111111111111");
        assertThat(transaction.getDalytranOrigTs()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        assertThat(transaction.getDalytranProcTs()).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Phase F — sequential read cycle + end-of-file (COBOL 1000-DALYTRAN-GET-NEXT / FILE STATUS '10').
    // ---------------------------------------------------------------------------------------------

    /**
     * Reproduces the {@code CBTRN01C} {@code 1000-DALYTRAN-GET-NEXT} loop: {@code read()} returns each
     * record in physical (front-to-back) order, then returns {@code null} once the input is exhausted
     * &mdash; the Spring Batch equivalent of the COBOL EOF status {@code '10'} setting
     * {@code END-OF-DAILY-TRANS-FILE = 'Y'}. Two records (one positive, one negative amount) are
     * streamed in-memory.
     */
    @Test
    @DisplayName("CBTRN01C 1000-DALYTRAN-GET-NEXT: read() streams records then null at EOF (FILE STATUS '10')")
    void readStreamsEachRecordThenNullAtEof() throws Exception {
        final String twoRecords = buildRecordWithAmount("0000005047G")
                + "\n"
                + buildRecordWithAmount("0000009190}");

        final FlatFileItemReader<DailyTransaction> reader = openReaderFor(twoRecords);
        try {
            final DailyTransaction first = reader.read();
            final DailyTransaction second = reader.read();
            final DailyTransaction afterEof = reader.read();

            assertThat(first).isNotNull();
            assertThat(first.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(first.getDalytranAmt().scale()).isEqualTo(2);
            assertThat(second).isNotNull();
            assertThat(second.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(second.getDalytranAmt().scale()).isEqualTo(2);
            // COBOL FILE STATUS '10' -> END-OF-DAILY-TRANS-FILE='Y' -> Spring Batch read() == null.
            assertThat(afterEof).isNull();
        } finally {
            reader.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test infrastructure: build fixed-width records and drive the real reader in-memory.
    // ---------------------------------------------------------------------------------------------

    /**
     * Stubs the loader to serve {@code content} from memory, builds the production
     * {@link FlatFileItemReader} and opens it. The returned reader streams the supplied records via
     * {@code read()} (the caller is responsible for closing it).
     *
     * @param content the raw record stream (one or more 350-char lines, {@code "\n"}-separated)
     * @return an opened {@link FlatFileItemReader} over the in-memory content
     */
    private FlatFileItemReader<DailyTransaction> openReaderFor(final String content) {
        when(resourceLoader.getResource(anyString()))
                .thenReturn(new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1)));
        final FlatFileItemReader<DailyTransaction> reader = readerProvider.dailyTransactionItemReader(OBJECT_KEY);
        reader.open(new ExecutionContext());
        return reader;
    }

    /**
     * Maps exactly one constructed 350-char record through the real reader pipeline (tokenizer +
     * field-set mapper + overpunch decode) and returns the resulting {@link DailyTransaction}.
     *
     * @param line a single 350-char fixed-width record
     * @return the mapped {@link DailyTransaction} (never {@code null} for a valid record)
     * @throws Exception if {@code read()} fails (the COBOL abend path)
     */
    private DailyTransaction mapSingleRecord(final String line) throws Exception {
        final FlatFileItemReader<DailyTransaction> reader = openReaderFor(line);
        try {
            return reader.read();
        } finally {
            reader.close();
        }
    }

    /**
     * Convenience builder for a valid 350-char record whose {@code DALYTRAN-AMT} field (bytes
     * 133-143) is set to {@code amtToken}; every other field is a benign, parseable placeholder and
     * both timestamp fields are blank (mapped to {@code null}). Used by the decode-focused tests so
     * only the amount varies.
     *
     * @param amtToken the exact 11-character {@code DALYTRAN-AMT} zoned-decimal token to embed
     * @return a 350-char fixed-width record
     */
    private static String buildRecordWithAmount(final String amtToken) {
        return buildRecord(
                "DT00000000000001", // DALYTRAN-ID            X(16)
                "01",               // DALYTRAN-TYPE-CD       X(02)
                "0001",             // DALYTRAN-CAT-CD        9(04)
                "POS",              // DALYTRAN-SOURCE        X(10)
                "DAILY TRANSACTION",// DALYTRAN-DESC          X(100)
                amtToken,           // DALYTRAN-AMT           S9(09)V99 (overpunch, exactly 11)
                "000000001",        // DALYTRAN-MERCHANT-ID   9(09)
                "MERCHANT",         // DALYTRAN-MERCHANT-NAME X(50)
                "CITY",             // DALYTRAN-MERCHANT-CITY X(50)
                "00000",            // DALYTRAN-MERCHANT-ZIP  X(10)
                "4111111111111111", // DALYTRAN-CARD-NUM      X(16)
                "",                 // DALYTRAN-ORIG-TS       X(26) (blank -> null)
                "");                // DALYTRAN-PROC-TS       X(26) (blank -> null)
    }

    /**
     * Assembles a single fixed-width 350-byte {@code DALYTRAN-RECORD} from its thirteen business
     * fields plus the trailing 20-byte {@code FILLER}, padding/truncating each field to its exact
     * {@code CVTRA06Y} width. The {@code amt} field is placed exactly (an 11-char zoned-decimal
     * token); a final guard asserts the strict {@value #RECORD_LENGTH}-char contract the reader's
     * {@code FixedLengthTokenizer} enforces.
     *
     * @return the assembled 350-char record
     */
    private static String buildRecord(final String id, final String typeCd, final String catCd,
            final String source, final String desc, final String amt, final String merchantId,
            final String merchantName, final String merchantCity, final String merchantZip,
            final String cardNum, final String origTs, final String procTs) {
        final StringBuilder sb = new StringBuilder(RECORD_LENGTH);
        sb.append(fixed(id, 16));            //   1- 16
        sb.append(fixed(typeCd, 2));         //  17- 18
        sb.append(fixed(catCd, 4));          //  19- 22
        sb.append(fixed(source, 10));        //  23- 32
        sb.append(fixed(desc, 100));         //  33-132
        sb.append(fixed(amt, 11));           // 133-143  (token is exactly 11; no padding applied)
        sb.append(fixed(merchantId, 9));     // 144-152
        sb.append(fixed(merchantName, 50));  // 153-202
        sb.append(fixed(merchantCity, 50));  // 203-252
        sb.append(fixed(merchantZip, 10));   // 253-262
        sb.append(fixed(cardNum, 16));       // 263-278
        sb.append(fixed(origTs, 26));        // 279-304
        sb.append(fixed(procTs, 26));        // 305-330
        sb.append(" ".repeat(20));           // 331-350  FILLER X(20)
        final String record = sb.toString();
        if (record.length() != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "test record must be " + RECORD_LENGTH + " chars but was " + record.length());
        }
        return record;
    }

    /**
     * Right-pads {@code value} with spaces (COBOL {@code X}-field convention) or truncates it to the
     * given {@code width}, so a field always occupies its exact fixed-width column span.
     */
    private static String fixed(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }
}
