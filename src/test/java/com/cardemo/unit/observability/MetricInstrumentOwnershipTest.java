/*
 * ******************************************************************
 * Program     : MetricInstrumentOwnershipTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves MetricsConfig is the sole owner of the four named
 *               instruments - no writer can reach a MeterRegistry - and that
 *               every facade method has a production caller which moves the
 *               series it is the producer for.
 * Source      : app/cbl/CBTRN02C.cbl:L214 (ADD 1 TO WS-REJECT-COUNT)
 *               app/cbl/CBTRN02C.cbl:L227 (TRANSACTIONS PROCESSED :)
 *               app/cbl/CBTRN02C.cbl:L228 (TRANSACTIONS REJECTED  :)
 *               app/cbl/COSGN00C.cbl       (sign-on, uncounted in the source)
 *               @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.observability.MetricsConfig.AuthenticationOutcome;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Instrument ownership: that {@code MetricsConfig} is the only place an instrument is created, that every
 * facade method has a production caller, and that each caller moves the series it is the producer for.
 *
 * <p>The four-instrument contract of AAP section 0.7.7 was previously asserted only in prose. Three writers
 * each took a {@code MeterRegistry} and resolved counters of their own, two of them re-declaring the metric
 * name and one re-declaring the reject-code tag key as well. Micrometer's registration being idempotent kept
 * that working, so nothing failed - but nothing checked that the four spellings agreed either, and the tag key
 * genuinely had diverged in one place. Meanwhile two of the four facade methods had no caller at all, which
 * meant two of the four documented series could never move.
 *
 * <p>The tests below pin both halves. Ownership is asserted structurally - no writer constructor accepts a
 * registry, so none of them can register anything - and the wiring is asserted behaviourally, by driving each
 * writer against a real {@link SimpleMeterRegistry} and reading the series back.
 */
@DisplayName("Metrics: one owner for four instruments, and a real producer for each")
class MetricInstrumentOwnershipTest {

    /** Registry every subject in this class shares, so a stray registration would be visible. */
    private MeterRegistry registry;

    /** The single owner of the four instruments. */
    private MetricsConfig metrics;

    @BeforeEach
    void buildRegistry() {
        registry = new SimpleMeterRegistry();
        metrics = new MetricsConfig(registry);
    }

    @Nested
    @DisplayName("1. Ownership is structural, not conventional")
    class OwnershipIsStructural {

        @Test
        @DisplayName("Constructing the facade registers all nine series eagerly and nothing else")
        void theFacadeRegistersExactlyNineSeries() {
            // One untagged processed counter, five reject-code series, two outcome series, one amount
            // GAUGE. Eager registration is what makes a code that never occurs show as zero on the
            // scrape endpoint rather than being absent from it.
            //
            // The amount total is the one series that is not a counter, and deliberately so. A Micrometer
            // Counter accumulates into a double, and money must never live in a binary floating-point field:
            // the amount total is therefore a gauge reading an exact BigDecimal accumulator, converted once
            // by the scrape thread at the reporting boundary. Asserting COUNTER for all nine would have
            // required that violation, so the type is asserted per series instead.
            assertThat(registry.getMeters()).hasSize(9);
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getType())
                    .isEqualTo(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL.equals(meter.getId().getName())
                            ? Meter.Type.GAUGE : Meter.Type.COUNTER));
            assertThat(registry.getMeters())
                    .as("exactly one gauge, and it is the amount total")
                    .filteredOn(meter -> meter.getId().getType() == Meter.Type.GAUGE)
                    .singleElement()
                    .satisfies(meter -> assertThat(meter.getId().getName())
                            .isEqualTo(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL));
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).distinct())
                    .containsExactlyInAnyOrder(
                            MetricsConfig.METRIC_RECORDS_PROCESSED,
                            MetricsConfig.METRIC_RECORDS_REJECTED,
                            MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS,
                            MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL);
        }

        @Test
        @DisplayName("No writer constructor accepts a MeterRegistry, so no writer can register an instrument")
        void noWriterCanReachARegistry() {
            // This is the assertion that makes the four-instrument contract checkable rather than a promise:
            // a class that cannot obtain a registry cannot add a fifth instrument, whatever it is edited to
            // do later.
            Stream.of(TransactionWriter.class, RejectWriter.class, StatementWriter.class)
                    .forEach(writer -> assertThat(writer.getDeclaredConstructors())
                            .as("%s constructors", writer.getSimpleName())
                            .allSatisfy(constructor -> assertThat(parameterTypes(constructor))
                                    .doesNotContain(MeterRegistry.class)
                                    .doesNotContain(Counter.class)));
        }

        @Test
        @DisplayName("Every writer takes the facade instead")
        void everyWriterTakesTheFacade() {
            Stream.of(TransactionWriter.class, RejectWriter.class, StatementWriter.class)
                    .forEach(writer -> assertThat(writer.getDeclaredConstructors())
                            .as("%s constructors", writer.getSimpleName())
                            .anySatisfy(constructor -> assertThat(parameterTypes(constructor))
                                    .contains(MetricsConfig.class)));
        }

        /**
         * Reads a constructor's parameter types.
         *
         * @param constructor the constructor to inspect, never {@code null}
         * @return its parameter types in declaration order, never {@code null}
         */
        private List<Class<?>> parameterTypes(final Constructor<?> constructor) {
            return List.of(constructor.getParameterTypes());
        }
    }

    @Nested
    @DisplayName("2. Each facade method moves exactly its own series")
    class EachFacadeMethodMovesItsOwnSeries {

        @Test
        @DisplayName("countRecordProcessed advances the untagged processed counter by one")
        void processedAdvancesByOne() {
            metrics.countRecordProcessed();
            metrics.countRecordProcessed();

            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isEqualTo(2.0d);
        }

        @Test
        @DisplayName("countRecordRejected advances only the series carrying its own reject code")
        void rejectedAdvancesOnlyItsOwnTag() {
            metrics.countRecordRejected(RejectCode.OVERLIMIT_TRANSACTION);

            for (RejectCode code : RejectCode.values()) {
                double expected = code == RejectCode.OVERLIMIT_TRANSACTION ? 1.0d : 0.0d;
                assertThat(count(MetricsConfig.METRIC_RECORDS_REJECTED,
                        MetricsConfig.TAG_REJECT_CODE, Integer.toString(code.getCode())))
                        .as("series for reject code %d", Integer.valueOf(code.getCode()))
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("countAuthenticationAttempt advances only the series carrying its own outcome")
        void authenticationAdvancesOnlyItsOwnTag() {
            metrics.countAuthenticationAttempt(AuthenticationOutcome.FAILURE);

            assertThat(count(MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS,
                    MetricsConfig.TAG_OUTCOME, AuthenticationOutcome.FAILURE.getTagValue()))
                    .isEqualTo(1.0d);
            assertThat(count(MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS,
                    MetricsConfig.TAG_OUTCOME, AuthenticationOutcome.SUCCESS.getTagValue()))
                    .isEqualTo(0.0d);
        }

        @Test
        @DisplayName("countTransactionAmount adds the reported amount with its sign intact")
        void amountIsAddedWithItsSign() {
            // SimpleMeterRegistry accepts a non-positive increment, which is why it is the registry to
            // assert sign handling against; the Prometheus implementation would discard the negative one.
            // No absolute value is taken anywhere, because app/cbl/CBTRN02C.cbl:L548-L552 adds negative
            // amounts to the cycle debit accumulator.
            metrics.countTransactionAmount(new BigDecimal("125.75"));
            metrics.countTransactionAmount(new BigDecimal("-25.75"));

            assertThat(amountTotal()).isEqualTo(100.0d);
        }
    }

    @Nested
    @DisplayName("3. Each writer is the producer for the series it reports")
    class EachWriterProducesItsSeries {

        @Test
        @DisplayName("TransactionWriter reports both processed records and their amounts, per record")
        void theTransactionWriterReportsBothOfItsSeries() throws Exception {
            TransactionWriter writer = new TransactionWriter(
                    mock(TransactionRepository.class), mock(S3Operations.class), new FileStatusMapper(),
                    metrics, "carddemo-batch-output", "transact");
            StepExecution execution = MetaDataInstanceFactory.createStepExecution();
            writer.beforeStep(execution);

            writer.write(Chunk.of(postedTransaction("12.34"), postedTransaction("-2.34")));

            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isEqualTo(2.0d);
            assertThat(amountTotal()).isEqualTo(10.0d);
        }

        @Test
        @DisplayName("TransactionWriter reports nothing for an empty chunk")
        void theTransactionWriterReportsNothingForAnEmptyChunk() throws Exception {
            TransactionWriter writer = new TransactionWriter(
                    mock(TransactionRepository.class), mock(S3Operations.class), new FileStatusMapper(),
                    metrics, "carddemo-batch-output", "transact");
            writer.beforeStep(MetaDataInstanceFactory.createStepExecution());

            writer.write(Chunk.of());

            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isZero();
            assertThat(amountTotal()).isZero();
        }

        @Test
        @DisplayName("RejectWriter reports the rejection under its own reject code, and nothing else")
        void theRejectWriterReportsItsOwnSeries() {
            RejectWriter writer = new RejectWriter(mock(S3Operations.class), metrics, new FileStatusMapper(),
                    "carddemo-batch-output", "gdg/dalyrejs", null);

            writer.writeReject(stagedTransaction(), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(count(MetricsConfig.METRIC_RECORDS_REJECTED, MetricsConfig.TAG_REJECT_CODE,
                    Integer.toString(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()))).isEqualTo(1.0d);
            // A rejected record is not a processed record: CBTRN02C counts them in different accumulators,
            // at :L214 and :L227 respectively.
            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isZero();
        }

        @Test
        @DisplayName("StatementWriter reports one processed record per persisted statement")
        void theStatementWriterReportsProcessedRecords() throws Exception {
            StatementWriter writer = new StatementWriter(mock(S3Operations.class), metrics,
                    new FileStatusMapper(), Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"),
                            ZoneOffset.UTC), "carddemo-statements");

            // Deliberately not pre-opened. StatementWriter.write opens, appends, closes and counts one
            // statement at a time, reproducing the per-account OPEN/CLOSE pair at
            // app/cbl/CBSTM03A.CBL:L293 and :L339; opening here would leave a statement open when write
            // reached its own open, which the writer refuses because an unclosed statement means a lost one.
            writer.write(Chunk.of(statement()));

            // One count per account statement, not per emitted line: CBSTM03A displays a per-account total,
            // and the two line streams below are one statement and therefore one processed record.
            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isEqualTo(1.0d);
        }
    }

    /**
     * Reads the current value of an untagged counter series.
     *
     * @param name the metric name, never {@code null}
     * @return the accumulated value, {@code 0.0} if the series has never been incremented
     */
    private double count(final String name) {
        return registry.get(name).counter().count();
    }

    /**
     * Reads the current value of the transaction amount total.
     *
     * <p>Read through {@link io.micrometer.core.instrument.Gauge} rather than
     * {@link io.micrometer.core.instrument.Counter}, because a counter holds a {@code double} and the
     * amount total must accumulate exactly. The registered gauge reads a {@link java.math.BigDecimal}
     * accumulator and converts once, on the scrape, which is the only conversion out of decimal in the
     * facade. Sign is preserved by that conversion, which is what {@code amountIsAddedWithItsSign} relies
     * on.
     *
     * @return the accumulated signed total, {@code 0.0} if nothing has been added
     */
    private double amountTotal() {
        return registry.get(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL).gauge().value();
    }

    /**
     * Reads the current value of a single-tagged counter series.
     *
     * @param name the metric name, never {@code null}
     * @param tagKey the tag key, never {@code null}
     * @param tagValue the tag value identifying the series, never {@code null}
     * @return the accumulated value, {@code 0.0} if that series has never been incremented
     */
    private double count(final String name, final String tagKey, final String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    /**
     * A posted transaction carrying the given amount, valid for the 350-byte image.
     *
     * @param amount the transaction amount, never {@code null}
     * @return the transaction, never {@code null}
     */
    private static Transaction postedTransaction(final String amount) {
        return new Transaction("0000000000000001", "PR", 1, "POS TERM", "A PURCHASE",
                new BigDecimal(amount), 1L, "A MERCHANT", "A CITY", "0000012345", "4111111111111111",
                "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }

    /**
     * A staged daily transaction, valid against every width {@code DailyTransaction} enforces.
     *
     * @return the staged record, never {@code null}
     */
    private static DailyTransaction stagedTransaction() {
        return new DailyTransaction(1L, "0000000000000001", "PR", 1, "POS TERM", "A PURCHASE",
                new BigDecimal("12.34"), 1L, "A MERCHANT", "A CITY", "0000012345", "4111111111111111",
                "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }

    /**
     * A single-account statement carrying one line on each of the two fixed-width streams.
     *
     * <p>The widths are the contract, not a convenience: {@code app/jcl/CREASTMT.JCL:STEP040} declares
     * {@code LRECL=80} for {@code STMTFILE} and {@code LRECL=100} for {@code HTMLFILE}, and
     * {@link com.cardemo.batch.processors.StatementProcessor.Statement} refuses a line of any other width.
     *
     * @return the statement, never {@code null}
     */
    private static StatementProcessor.Statement statement() {
        return new StatementProcessor.Statement("00000000001", new BigDecimal("12.34"),
                List.of("A".repeat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)),
                List.of("B".repeat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)));
    }
}
