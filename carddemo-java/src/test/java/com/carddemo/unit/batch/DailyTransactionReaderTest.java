package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.exception.FileAccessException;
import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Unit tests for {@link DailyTransactionReader} (COBOL {@code CBTRN01C}/{@code CVTRA06Y} 350-byte
 * fixed-width read, source commit {@code 27d6c6f}). Verifies the byte-exact field offsets, every
 * trailing-sign overpunch decode branch, the {@link BigDecimal} scale-2 amount fidelity, EOF
 * handling, open/parse failure mapping to {@link FileAccessException}, and the structured SLF4J
 * logging added for the Observability rule (R1) — including that the raw record line and the card
 * number (PAN) are never logged.
 */
class DailyTransactionReaderTest {

    private static final String FAKE_PAN = "4111111111111111";
    private static final String LOCATION = "classpath:dailytran-test.txt";

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(DailyTransactionReader.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    /** Right-pads (left-justifies) a value to an exact fixed width, truncating if longer. */
    private static String pad(String v, int width) {
        String s = (v == null) ? "" : v;
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /** Builds an exactly-350-byte CVTRA06Y record with the given id, amount token, and card number. */
    private static String record(String id, String amountToken, String cardNum) {
        String rec = pad(id, 16)            // 1-16   dalytranId
                + pad("DB", 2)              // 17-18  dalytranTypeCd
                + pad("5", 4)               // 19-22  dalytranCatCd  (numeric -> 5)
                + pad("POS", 10)            // 23-32  dalytranSource
                + pad("PURCHASE", 100)      // 33-132 dalytranDesc
                + pad(amountToken, 11)      // 133-143 dalytranAmt (zoned overpunch)
                + pad("123", 9)             // 144-152 dalytranMerchantId (numeric -> 123)
                + pad("ACME STORE", 50)     // 153-202 dalytranMerchantName
                + pad("ANYTOWN", 50)        // 203-252 dalytranMerchantCity
                + pad("12345", 10)          // 253-262 dalytranMerchantZip
                + pad(cardNum, 16)          // 263-278 dalytranCardNum
                + pad("2024-01-01.00.00.00.000000", 26)  // 279-304 dalytranOrigTs
                + pad("2024-01-01.00.00.01.000000", 26)  // 305-330 dalytranProcTs
                + pad("", 20);              // 331-350 filler
        if (rec.length() != 350) {
            throw new IllegalStateException("test record builder produced " + rec.length() + " bytes, expected 350");
        }
        return rec;
    }

    /** A {@link ResourceLoader} returning the given content as an in-memory ISO-8859-1 resource. */
    private static ResourceLoader loaderFor(String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(bytes, "in-memory dailytran");
            }

            @Override
            public ClassLoader getClassLoader() {
                return DailyTransactionReaderTest.class.getClassLoader();
            }
        };
    }

    /** A {@link ResourceLoader} returning a non-existent classpath resource (forces an open failure). */
    private static ResourceLoader missingLoader() {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ClassPathResource("this-daily-tran-file-does-not-exist.dat");
            }

            @Override
            public ClassLoader getClassLoader() {
                return DailyTransactionReaderTest.class.getClassLoader();
            }
        };
    }

    private static DailyTransactionReader newReader(ResourceLoader loader) throws Exception {
        DailyTransactionReader reader = new DailyTransactionReader(loader, LOCATION);
        reader.afterPropertiesSet();
        return reader;
    }

    @Test
    @DisplayName("Reads one record with byte-exact fields and a scale-2 positive amount; logs lifecycle without the PAN")
    void readsSingleRecord() throws Exception {
        DailyTransactionReader reader = newReader(loaderFor(record("TXN0000000000001", "0000001234E", FAKE_PAN)));
        reader.open(new ExecutionContext());

        DailyTransaction tran = reader.read();
        assertThat(tran).isNotNull();
        assertThat(tran.getDalytranId()).isEqualTo("TXN0000000000001");
        assertThat(tran.getDalytranCatCd()).isEqualTo(5);
        assertThat(tran.getDalytranMerchantId()).isEqualTo(123L);
        assertThat(tran.getDalytranCardNum()).isEqualTo(FAKE_PAN);
        assertThat(tran.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(tran.getDalytranAmt().scale()).isEqualTo(2);

        assertThat(reader.read()).isNull(); // EOF
        reader.close();

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("Opened daily transaction input"));
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("Closing daily transaction reader")
                && e.getFormattedMessage().contains("recordsRead=1"));
        // The raw record line and the card number must never be logged.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(FAKE_PAN));
    }

    @Test
    @DisplayName("Decodes every trailing-sign overpunch branch into the correct scale-2 BigDecimal")
    void decodesAllOverpunchBranches() throws Exception {
        String content = String.join("\n",
                record("ID01", "0000001234E", FAKE_PAN),  // 'A'-'I' (E=5) -> +123.45
                record("ID02", "0000000000{", FAKE_PAN),  // '{'           -> +0.00
                record("ID03", "0000001234N", FAKE_PAN),  // 'J'-'R' (N=5) -> -123.45
                record("ID04", "0000000000}", FAKE_PAN),  // '}'           -> -0.00 == 0.00
                record("ID05", "00000123455", FAKE_PAN),  // '0'-'9' (5)   -> +1234.55
                record("ID06", "           ", FAKE_PAN)); // blank          -> 0.00

        DailyTransactionReader reader = newReader(loaderFor(content));
        reader.open(new ExecutionContext());

        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-123.45"));
        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("1234.55"));
        assertThat(reader.read().getDalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("An invalid overpunch sign maps to FileAccessException and logs only the line number")
    void invalidOverpunchMapsToFileAccessException() throws Exception {
        DailyTransactionReader reader = newReader(loaderFor(record("BADTXN", "0000001234*", FAKE_PAN)));
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read)
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining("line");

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("Failed to parse daily transaction record"));
        // The raw line / PAN must not appear in the error diagnostic.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(FAKE_PAN));
    }

    @Test
    @DisplayName("A missing input object maps to FileAccessException on open and logs an ERROR")
    void openFailureMapsToFileAccessException() throws Exception {
        DailyTransactionReader reader = newReader(missingLoader());

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining("opening daily transaction file");

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("Failed to open daily transaction input"));
    }
}
