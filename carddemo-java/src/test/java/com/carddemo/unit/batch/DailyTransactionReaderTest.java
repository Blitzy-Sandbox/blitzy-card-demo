package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.exception.FileAccessException;
import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Unit tests for {@link DailyTransactionReader}.
 *
 * <p>Traceability (REFERENCE-ONLY; COBOL not copied; source commit {@code 27d6c6f}): the reader
 * re-platforms the sequential {@code DALYTRAN} read of {@code CBTRN01C}/{@code POSTTRAN.jcl}. These
 * tests drive the reader with in-memory 350-byte fixed-width records (ISO-8859-1) to verify the
 * {@code CVTRA06Y} field mapping, the zoned-decimal trailing-sign overpunch decode (all sign
 * classes plus the invalid-sign error), and the open/read failure paths that map to
 * {@link FileAccessException}.</p>
 */
@DisplayName("DailyTransactionReader - CVTRA06Y fixed-width parsing and overpunch decode")
class DailyTransactionReaderTest {

    /** Builds a fixed-width column, right-padding with spaces or truncating to the exact width. */
    private static String field(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /** Assembles one valid 350-byte CVTRA06Y record, injecting the 11-char amount field. */
    private static String line(String amount11) {
        String record =
                field("0000000000000001", 16)
                        + field("01", 2)
                        + field("0001", 4)
                        + field("POS", 10)
                        + field("PURCHASE", 100)
                        + field(amount11, 11)
                        + field("000000123", 9)
                        + field("MERCHANT", 50)
                        + field("CITY", 50)
                        + field("12345", 10)
                        + field("4111111111111111", 16)
                        + field("2024-01-15", 26)
                        + field("2024-01-15", 26)
                        + field("", 20);
        if (record.length() != 350) {
            throw new IllegalStateException("test record is not 350 bytes: " + record.length());
        }
        return record;
    }

    private static ResourceLoader loaderFor(Resource resource) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    private static DailyTransactionReader newReader(Resource resource) throws Exception {
        DailyTransactionReader reader =
                new DailyTransactionReader(loaderFor(resource), "s3://carddemo-batch-input/dailytran.txt");
        reader.afterPropertiesSet();
        return reader;
    }

    private static Resource resourceOf(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Nested
    @DisplayName("Successful parsing")
    class Success {

        @Test
        @DisplayName("Maps the CVTRA06Y columns to a DailyTransaction")
        void mapsFields() throws Exception {
            DailyTransactionReader reader = newReader(resourceOf(line("0000001234{")));
            reader.open(new ExecutionContext());

            DailyTransaction tran = reader.read();

            assertThat(tran).isNotNull();
            assertThat(tran.getDalytranId()).isEqualTo("0000000000000001");
            assertThat(tran.getDalytranTypeCd()).isEqualTo("01");
            assertThat(tran.getDalytranCatCd()).isEqualTo(1);
            assertThat(tran.getDalytranSource()).isEqualTo("POS");
            assertThat(tran.getDalytranMerchantId()).isEqualTo(123L);
            assertThat(tran.getDalytranCardNum()).isEqualTo("4111111111111111");
            assertThat(reader.read()).isNull();
            reader.close();
        }

        @Test
        @DisplayName("Decodes every overpunch sign class and a blank amount to BigDecimal scale 2")
        void decodesOverpunchSigns() throws Exception {
            String content = String.join("\n",
                    line("0000001234{"),   // '{'  -> +x..0 -> 123.40
                    line("0000001234A"),   // 'A'  -> +x..1 -> 123.41
                    line("0000001234}"),   // '}'  -> -x..0 -> -123.40
                    line("0000001234J"),   // 'J'  -> -x..1 -> -123.41
                    line("00000012345"),   // '5'  -> +x..5 -> 123.45
                    line("           "));  // blank      -> 0.00
            DailyTransactionReader reader = newReader(resourceOf(content));
            reader.open(new ExecutionContext());

            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("123.40"));
            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("123.41"));
            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-123.40"));
            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-123.41"));
            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
            assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(reader.read()).isNull();
            reader.close();
        }
    }

    @Nested
    @DisplayName("Failure paths map to FileAccessException")
    class Failures {

        @Test
        @DisplayName("Missing resource fails open with the abend-equivalent message")
        void openFailure() throws Exception {
            DailyTransactionReader reader =
                    newReader(new FileSystemResource("/nonexistent/blitzy_missing_dailytran.txt"));

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessage("Error opening daily transaction file");
        }

        @Test
        @DisplayName("Invalid overpunch sign surfaces as a read failure")
        void invalidOverpunchSign() throws Exception {
            DailyTransactionReader reader = newReader(resourceOf(line("0000001234*")));
            reader.open(new ExecutionContext());

            assertThatThrownBy(reader::read)
                    .isInstanceOf(FileAccessException.class)
                    .hasMessageContaining("Error reading daily transaction record at line 1");
            reader.close();
        }

        @Test
        @DisplayName("A record of the wrong fixed-width length surfaces as a read failure")
        void wrongLengthRecord() throws Exception {
            DailyTransactionReader reader = newReader(resourceOf("TOO-SHORT"));
            reader.open(new ExecutionContext());

            assertThatThrownBy(reader::read)
                    .isInstanceOf(FileAccessException.class)
                    .hasMessageContaining("Error reading daily transaction record at line 1");
            reader.close();
        }
    }
}
