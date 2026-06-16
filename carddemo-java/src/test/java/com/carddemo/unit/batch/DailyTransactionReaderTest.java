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
 * Unit tests for {@link DailyTransactionReader}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/copybook not copied; source commit {@code 27d6c6f}):
 * the reader re-platforms the sequential {@code DALYTRAN-FILE} read of batch program
 * {@code app/cbl/CBTRN01C.cbl} ({@code ORGANIZATION IS SEQUENTIAL}, {@code FILE STATUS IS
 * DALYTRAN-STATUS}) over the 350-byte {@code DALYTRAN-RECORD} layout of copybook
 * {@code app/cpy/CVTRA06Y.cpy}. These tests assert the three binding parities of that
 * re-platforming:</p>
 *
 * <ul>
 *   <li><b>External record layout preserved exactly</b> (AAP section 0.8.1): the 350-byte
 *       record is decomposed by the 14 fixed-width {@code FixedLengthTokenizer} ranges that
 *       mirror {@code CVTRA06Y} byte-for-byte, and every field reaches the matching
 *       {@link DailyTransaction} getter.</li>
 *   <li><b>Signed zoned-decimal overpunch decode</b> (AAP section 0.8.2): the trailing-sign
 *       {@code DALYTRAN-AMT PIC S9(09)V99} is decoded to a {@link BigDecimal} of scale 2 and
 *       verified by value with {@code compareTo} semantics, never by scale-sensitive
 *       {@code BigDecimal.equals}. The canonical vectors are {@code "0000005047G"} -&gt;
 *       {@code +504.77} (overpunch {@code G} = positive 7) and {@code "0000009190}"} -&gt;
 *       {@code -919.00} (overpunch {@code }} = negative 0).</li>
 *   <li><b>FILE STATUS / I/O failure becomes an exception</b> (AAP section 0.8.4): a failed
 *       open and a malformed (wrong-length) record are surfaced as {@link FileAccessException},
 *       the Java equivalent of the COBOL abend path.</li>
 * </ul>
 *
 * <p>The reader has a single {@link ResourceLoader} collaborator, so these tests mock only
 * that loader (Mockito) and feed fixed-width content through an in-memory
 * {@link ByteArrayResource}: no Spring context, no Testcontainers, no LocalStack, and no live
 * AWS dependency. Because the production class configures the inherited
 * {@code FlatFileItemReader} in {@code afterPropertiesSet()} (not in its constructor, which
 * only late-binds the step-scoped collaborators), {@link #newReader(String)} invokes
 * {@code afterPropertiesSet()} exactly as the Spring container would before {@code open()}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionReader - CVTRA06Y 350-byte mapping, overpunch decode, FileAccessException wrapping")
class DailyTransactionReaderTest {

    /**
     * Step-scoped {@code s3://} location passed to the reader. The value is supplied directly
     * to the constructor in these unit tests (the {@code @Value} SpEL default is only resolved
     * inside a Spring container), and is the exact argument the mocked loader is stubbed for.
     */
    private static final String LOC = "s3://carddemo-batch-input/dailytran.txt";

    /** Loader that the reader uses to resolve {@link #LOC} into a readable resource. */
    @Mock
    private ResourceLoader resourceLoader;

    /**
     * Left-justifies {@code v} into a fixed {@code width} column, space-padding on the right
     * (or truncating) so the assembled record honours the exact {@code CVTRA06Y} byte offsets.
     *
     * @param v     the raw field value ({@code null} is treated as empty)
     * @param width the fixed column width in bytes
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
     * Builds one exactly-350-character {@code DALYTRAN-RECORD} line. All columns are valid
     * fixtures: {@code DALYTRAN-CAT-CD} is the 4-digit {@code "0005"} and
     * {@code DALYTRAN-MERCHANT-ID} the 9-digit {@code "123456789"} so the numeric reads
     * succeed, while {@code amt11} is placed verbatim into the 11-byte {@code DALYTRAN-AMT}
     * column so the caller controls the overpunch token.
     *
     * @param amt11 the raw 11-character zoned-decimal amount token (10 head digits + 1
     *              trailing-sign overpunch character)
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
     * Stubs the loader to return the supplied content as an in-memory ISO-8859-1
     * {@link ByteArrayResource} (one byte per char, preserving the fixed-width offsets),
     * constructs the reader, and runs {@code afterPropertiesSet()} so the inherited
     * {@code FlatFileItemReader} is fully configured exactly as the Spring container would
     * arrange it prior to {@code open()}.
     *
     * @param content the raw file content the reader will parse
     * @return a fully configured, not-yet-opened reader
     * @throws Exception if {@code afterPropertiesSet()} fails
     */
    private DailyTransactionReader newReader(String content) throws Exception {
        when(resourceLoader.getResource(LOC))
                .thenReturn(new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1)));
        DailyTransactionReader reader = new DailyTransactionReader(resourceLoader, LOC);
        reader.afterPropertiesSet();
        return reader;
    }

    @Test
    @DisplayName("read() maps every CVTRA06Y fixed-width field onto the DailyTransaction entity")
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

        // Monetary parity: assert by value (compareTo) and assert the scale explicitly; never
        // BigDecimal.equals (which is scale-sensitive and would mask a precision regression).
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);

        // A single-record file: the next read reaches end-of-file and returns null.
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("decodes positive overpunch units 'G' to +504.77 (scale 2)")
    void decodesPositiveOverpunch_G_is504_77() throws Exception {
        String line = line350("0000005047G");
        assertThat(line.length()).isEqualTo(350);

        DailyTransactionReader reader = newReader(line);
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();
        assertThat(dt).isNotNull();
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("decodes negative overpunch units '}' to -919.00 (scale 2)")
    void decodesNegativeOverpunch_brace_is_minus919_00() throws Exception {
        String line = line350("0000009190}");
        assertThat(line.length()).isEqualTo(350);

        DailyTransactionReader reader = newReader(line);
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();
        assertThat(dt).isNotNull();
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("decodes positive-zero overpunch units '{' to 0.00 (scale 2)")
    void decodesZeroPositiveOverpunch_brace() throws Exception {
        String line = line350("0000000000{");
        assertThat(line.length()).isEqualTo(350);

        DailyTransactionReader reader = newReader(line);
        reader.open(new ExecutionContext());

        DailyTransaction dt = reader.read();
        assertThat(dt).isNotNull();
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(dt.getDalytranAmt().scale()).isEqualTo(2);
        reader.close();
    }

    @Test
    @DisplayName("read() wraps a too-short (349-byte) record as FileAccessException")
    void wrongLengthLine_throwsFileAccessException() throws Exception {
        String badLine = line350("0000005047G").substring(0, 349);
        assertThat(badLine.length()).isEqualTo(349);

        DailyTransactionReader reader = newReader(badLine);
        reader.open(new ExecutionContext());

        // Strict FixedLengthTokenizer -> IncorrectLineLengthException -> FlatFileParseException,
        // which the read() override rewraps as FileAccessException (the abend-equivalent path).
        assertThatThrownBy(reader::read).isInstanceOf(FileAccessException.class);
    }

    @Test
    @DisplayName("read() wraps a too-long (351-byte) record as FileAccessException")
    void tooLongLine_throwsFileAccessException() throws Exception {
        String badLine = line350("0000005047G") + "X";
        assertThat(badLine.length()).isEqualTo(351);

        DailyTransactionReader reader = newReader(badLine);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read).isInstanceOf(FileAccessException.class);
    }

    @Test
    @DisplayName("open() wraps a missing resource as FileAccessException")
    void openMissingResource_throwsFileAccessException() throws Exception {
        when(resourceLoader.getResource(LOC))
                .thenReturn(new FileSystemResource("/nonexistent/path/dailytran-xyz.txt"));
        DailyTransactionReader reader = new DailyTransactionReader(resourceLoader, LOC);
        // afterPropertiesSet only asserts the line mapper is set; the missing-resource failure
        // surfaces from open() (strict=true), where it is rewrapped as FileAccessException.
        reader.afterPropertiesSet();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(FileAccessException.class);
    }
}
