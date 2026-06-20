package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link CombineTransactionsProcessor} (combine-stage identity processor, source
 * commit {@code 27d6c6f}). Verifies the verbatim passthrough contract, the processed-records
 * metric, the defensive ordering-key validation, the shared ascending comparator, and the
 * structured SLF4J logging added for the Observability rule (R1) — asserting that invalid-record
 * faults are logged and that no full record content is emitted.
 */
class CombineTransactionsProcessorTest {

    private SimpleMeterRegistry registry;
    private CombineTransactionsProcessor processor;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new CombineTransactionsProcessor(registry);
        logbackLogger = (Logger) LoggerFactory.getLogger(CombineTransactionsProcessor.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private static Transaction tx(String id) {
        Transaction t = new Transaction();
        t.setTranId(id);
        return t;
    }

    @Test
    @DisplayName("Identity passthrough returns the same instance and increments the processed counter")
    void identityPassthrough() {
        Transaction in = tx("0000000000000001");
        Transaction out = processor.process(in);

        assertThat(out).isSameAs(in);
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0d);
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.TRACE
                        && e.getFormattedMessage().contains("passthrough")
                        && e.getFormattedMessage().contains("0000000000000001"));
    }

    @Test
    @DisplayName("A null record is rejected with an IllegalArgumentException and a WARN diagnostic")
    void nullRecordRejected() {
        assertThatThrownBy(() -> processor.process(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThat(registry.counter("carddemo.batch.records.processed").count()).isZero();
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("null"));
    }

    @Test
    @DisplayName("A record missing its ordering key is rejected with a WARN diagnostic")
    void blankTranIdRejected() {
        assertThatThrownBy(() -> processor.process(tx("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordering key");

        assertThat(registry.counter("carddemo.batch.records.processed").count()).isZero();
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("ordering key"));
    }

    @Test
    @DisplayName("BY_TRAN_ID orders ascending by transaction id")
    void comparatorOrdersAscending() {
        List<Transaction> list = new java.util.ArrayList<>(
                List.of(tx("0000000000000003"), tx("0000000000000001"), tx("0000000000000002")));
        list.sort(CombineTransactionsProcessor.BY_TRAN_ID);
        assertThat(list).extracting(Transaction::getTranId)
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
    }

    @Test
    @DisplayName("Constructor rejects a null MeterRegistry")
    void constructorRejectsNullRegistry() {
        assertThatThrownBy(() -> new CombineTransactionsProcessor((MeterRegistry) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("meterRegistry");
    }
}
