package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.exception.FileAccessException;
import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ResourceLoader;

/**
 * Unit tests for {@link DailyTransactionReader} — the Spring Batch reader that replaces the
 * sequential {@code DALYTRAN} read of COBOL {@code CBTRN01C} over the 350-byte {@code CVTRA06Y}
 * record layout (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}).
 *
 * <p>These are pure-JVM tests: the {@link ResourceLoader} is a Mockito mock returning an in-memory
 * {@link ByteArrayResource}, so no Spring context, Testcontainers, or LocalStack is involved. The
 * reader is an {@code InitializingBean}, so each fixture calls {@code afterPropertiesSet()} (which
 * configures the resource, ISO-8859-1 encoding, strict mode, and the fixed-width line mapper) the
 * same way the Spring lifecycle would before {@code open()}.
 *
 * <p>Three contracts mandated by AAP §0.8.1 / §0.8.2 / §0.8.4 are verified:
 * <ul>
 *   <li>the 14-range {@code FixedLengthTokenizer} field-set mapping preserves the exact 350-byte
 *       external record layout (every named range maps to its entity field);</li>
 *   <li>the signed zoned-decimal trailing-sign overpunch decode of {@code DALYTRAN-AMT}
 *       ({@code PIC S9(09)V99}) yields a scale-2 {@link BigDecimal} compared with {@code compareTo}
 *       (never {@code equals}): a {@code 'G'} units character decodes {@code "0000005047G"} to
 *       {@code +504.77}, while the negative right-brace units character decodes to {@code -919.00};
 *       </li>
 *   <li>open and read failures surface as {@link FileAccessException} — the idiomatic replacement
 *       for the COBOL {@code FILE STATUS} abend path.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionReader — CVTRA06Y 350-byte mapping, signed overpunch decode, FileAccessException wrapping")
class DailyTransactionReaderTest {

    /** Late-bound input location matching the reader's default {@code @Value} resolution. */
    private static final String LOC = "s3://carddemo-batch-input/dailytran.txt";

    /** Resolver for the batch input; stubbed to return an in-memory or filesystem resource. */
    @Mock
    private ResourceLoader resourceLoader;

    /**
     * Renders a single fixed-width field: left-justified and space-padded to {@code width}, or
     * truncated to {@code width} when the value is longer (a {@code null} value is treated as
     * empty). Mirrors the COBOL {@code PIC X(n)} display representation.
     *
     * @param v     the raw value (may be {@code null})
     * @param width the fixed column width
     * @return a string of exactly {@code width} characters
     */
    private static String field(String v, int width) {
        String s = v == null ? "" : v;
        if (s.length() > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /**
     * Assembles an exactly 350-character {@code CVTRA06Y} record. Numeric ranges are populated with
     * parseable content — {@code dalytranCatCd} = {@code "0005"} and {@code dalytranMerchantId} =
     * {@code "123456789"} — while the 11-character {@code dalytranAmt} token is inserted verbatim so
     * the overpunch decode can be exercised.
     *
     * @param amt11 the raw 11-character signed-overpunch amount token (for example
     *              {@code "0000005047G"})
     * @return the assembled 350-character record line
     */
    private static String line350(String amt11) {
        return field("DT00000000000001", 16)
                + field("01", 2)
                + field("0005", 4)
                + field("POS TERM", 10)
                + field("GROCERY", 100)
                + field(amt11, 11)
                + field("123456789", 9)
                + field("ACME", 50)
                + field("SPRINGFIELD", 50)
                + field("12345-6789", 10)
                + field("1234567890123456", 16)
                + field("2022-07-18-00.00.00.000000", 26)
                + field("2022-07-18-10.15.30.123456", 26)
                + field("", 20);
    }

    /**
     * Stubs the resource loader to return the given content as an ISO-8859-1 {@link ByteArrayResource}
     * (1:1 byte-to-character mapping, preserving the fixed-width offsets), constructs the reader, and
     * runs {@code afterPropertiesSet()} so the resource, encoding, strict mode, and line mapper are
     * configured exactly as the Spring lifecycle would configure them.
     *
     * @param content the full file content to expose to the reader
     * @return a fully initialized reader ready to {@code open()}
     * @throws Exception if reader initialization fails
     */
    private DailyTransactionReader newReader(String content) throws Exception {
        when(resourceLoader.getResource(LOC))
                .thenReturn(new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1)));
        DailyTransactionReader reader = new DailyTransactionReader(resourceLoader, LOC);
        reader.afterPropertiesSet();
        return reader;
    }

    @Test
    @DisplayName("maps all 13 CVTRA06Y fields from a 350-byte line; positive overpunch decodes to +504.77")
    void mapsAllFields_fromFixedWidthLine() throws Exception {
        String line = line350("0000005047G");
        assertThat(line.length()).isEqualTo(350);

        DailyTransactionReader reader = newReader(line);
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt).isNotNull();
        assertThat(dt.getDalytranId()).isEqualTo("DT00000000000001");
        assertThat(dt.getDalytranTypeCd()).isEqualTo("01");
        assertThat(dt.getDalytranCatCd()).isEqualTo(5);
        assertThat(dt.getDalytranSource()).isEqualTo("POS TERM");
        assertThat(dt.getDalytranDesc()).isEqualTo("GROCERY");
        assertThat(dt.getDalytranMerchantId()).isEqualTo(123456789L);
        assertThat(dt.getDalytranMerchantName()).isEqualTo("ACME");
        assertThat(dt.getDalytranMerchantCity()).isEqualTo("SPRINGFIELD");
        assertThat(dt.getDalytranMerchantZip()).isEqualTo("12345-6789");
        assertThat(dt.getDalytranCardNum()).isEqualTo("1234567890123456");
        assertThat(dt.getDalytranOrigTs()).isEqualTo("2022-07-18-00.00.00.000000");
        assertThat(dt.getDalytranProcTs()).isEqualTo("2022-07-18-10.15.30.123456");

        // Amount fidelity: compareTo (never equals), and the scale is exactly 2 (AAP §0.8.2).
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);

        // A single record yields EOF on the next read (COBOL FILE STATUS '10').
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("decodes positive overpunch units 'G' as +504.77 with scale 2")
    void decodesPositiveOverpunch_G_is504_77() throws Exception {
        DailyTransactionReader reader = newReader(line350("0000005047G"));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("decodes negative overpunch units (right brace) as -919.00 with scale 2")
    void decodesNegativeOverpunch_brace_isMinus919_00() throws Exception {
        DailyTransactionReader reader = newReader(line350("0000009190}"));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        assertThat(dt.getDalytranAmt().signum()).isEqualTo(-1);
        reader.close();
    }

    @Test
    @DisplayName("decodes positive-zero overpunch units (left brace) as 0.00 with scale 2")
    void decodesZeroPositiveOverpunch_brace_is0_00() throws Exception {
        DailyTransactionReader reader = newReader(line350("0000000000{"));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("decodes negative non-zero overpunch units 'J' as -919.01 with scale 2")
    void decodesNegativeNonZeroOverpunch_J_isMinus919_01() throws Exception {
        DailyTransactionReader reader = newReader(line350("0000009190J"));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.01"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        assertThat(dt.getDalytranAmt().signum()).isEqualTo(-1);
        reader.close();
    }

    @Test
    @DisplayName("decodes a plain trailing digit (no overpunch) as positive +504.77 with scale 2")
    void decodesPlainDigitUnits_noOverpunch_is504_77() throws Exception {
        DailyTransactionReader reader = newReader(line350("00000050477"));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("a blank amount field decodes to 0.00 with scale 2")
    void blankAmount_decodesToZero() throws Exception {
        DailyTransactionReader reader = newReader(line350(""));
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();

        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("a too-short (349-char) line surfaces as FileAccessException on read (strict tokenizer)")
    void wrongLengthLine_throwsFileAccessException() throws Exception {
        String shortLine = line350("0000005047G").substring(0, 349);
        assertThat(shortLine.length()).isEqualTo(349);

        DailyTransactionReader reader = newReader(shortLine);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read).isInstanceOf(FileAccessException.class);
        reader.close();
    }

    @Test
    @DisplayName("a too-long (351-char) line surfaces as FileAccessException on read (strict tokenizer)")
    void tooLongLine_throwsFileAccessException() throws Exception {
        String longLine = line350("0000005047G") + "X";
        assertThat(longLine.length()).isEqualTo(351);

        DailyTransactionReader reader = newReader(longLine);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read).isInstanceOf(FileAccessException.class);
        reader.close();
    }

    @Test
    @DisplayName("an invalid overpunch sign surfaces as FileAccessException on read")
    void invalidOverpunchSign_throwsFileAccessException() throws Exception {
        DailyTransactionReader reader = newReader(line350("0000005047*"));
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read).isInstanceOf(FileAccessException.class);
        reader.close();
    }

    @Test
    @DisplayName("opening a missing resource (strict mode) surfaces as FileAccessException")
    void openMissingResource_throwsFileAccessException() throws Exception {
        when(resourceLoader.getResource(LOC))
                .thenReturn(new FileSystemResource("/nonexistent/path/dailytran-xyz.txt"));
        DailyTransactionReader reader = new DailyTransactionReader(resourceLoader, LOC);
        reader.afterPropertiesSet();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(FileAccessException.class);
    }
}
