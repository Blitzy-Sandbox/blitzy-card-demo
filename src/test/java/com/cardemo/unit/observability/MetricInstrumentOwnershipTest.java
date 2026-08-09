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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.jobs.CombineTransactionsJob;
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.batch.jobs.TransactionReportJob;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig.AuthenticationOutcome;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Instrument ownership: that {@code MetricsConfig} is the only place an instrument is created, that every
 * facade method has a production caller, and that each caller moves the series it is the producer for.
 *
 * <p>The four-instrument contract of AAP section 0.7.7 is also stated in prose, which cannot hold it. Three
 * writers each taking a {@code MeterRegistry} and resolving counters of their own - two re-declaring the metric
 * name and one re-declaring the reject-code tag key as well - keeps working, because Micrometer's registration
 * is idempotent, so nothing fails; but nothing checks that the four spellings agree either, and a tag key
 * diverging in one place is exactly what that permits. A facade method with no caller is the mirror image: a
 * documented series that can never move.
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
        @DisplayName("Constructing the facade registers all ten series eagerly, every one a counter")
        void theFacadeRegistersExactlyTenCounterSeries() {
            // One untagged processed counter, five reject-code series, two outcome series and two amount
            // series - credit and debit. Eager registration is what makes a code that never occurs show as
            // zero on the scrape endpoint rather than being absent from it.
            //
            // EVERY series is a COUNTER, which AAP section 0.7.7 requires by naming four counters. The amount
            // total reached that through a FunctionCounter reading an exact BigDecimal accumulator rather than
            // a Counter advanced by increment(double): money never lives in a binary floating-point field, and
            // the single conversion happens at the scrape. Its two series exist because a Prometheus counter
            // may not carry a negative value - the client rejects one at scrape time and fails the whole
            // response - while the amounts are genuinely signed, so the instrument is partitioned on the same
            // >= 0 predicate app/cbl/CBTRN02C.cbl:L548-L552 uses for its own credit and debit accumulators.
            assertThat(registry.getMeters()).hasSize(10);
            assertThat(registry.getMeters())
                    .as("no gauge, no timer, no summary: four counters and nothing else")
                    .allSatisfy(meter -> assertThat(meter.getId().getType()).isEqualTo(Meter.Type.COUNTER));
            assertThat(registry.getMeters())
                    .as("the amount total is exactly two series, one per sign")
                    .filteredOn(meter -> MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL
                            .equals(meter.getId().getName()))
                    .hasSize(2)
                    .extracting(meter -> meter.getId().getTag(MetricsConfig.TAG_SIGN))
                    .containsExactlyInAnyOrder(MetricsConfig.SIGN_CREDIT, MetricsConfig.SIGN_DEBIT);
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).distinct())
                    .as("four names, which is the contract; the sign is a dimension, not a fifth instrument")
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
        @DisplayName("Every writer that reports a series takes the facade instead")
        void everyReportingWriterTakesTheFacade() {
            // TransactionWriter reports processed records and their amounts; RejectWriter reports rejections
            // by code. StatementWriter is deliberately absent: it reports no series at all, so it takes no
            // MetricsConfig either. It used to advance the records-processed counter once per statement, which
            // mixed statements into a series that reproduces DISPLAY 'TRANSACTIONS PROCESSED :' at
            // app/cbl/CBTRN02C.cbl:L227 - the daily transaction records POSTTRAN read. Holding an injected
            // collaborator it never calls would be dead code, so the dependency went with the call.
            Stream.of(TransactionWriter.class, RejectWriter.class)
                    .forEach(writer -> assertThat(writer.getDeclaredConstructors())
                            .as("%s constructors", writer.getSimpleName())
                            .anySatisfy(constructor -> assertThat(parameterTypes(constructor))
                                    .contains(MetricsConfig.class)));
            assertThat(StatementWriter.class.getDeclaredConstructors())
                    .as("StatementWriter reports no series, so it must hold no meter owner")
                    .allSatisfy(constructor -> assertThat(parameterTypes(constructor))
                            .doesNotContain(MetricsConfig.class));
        }

        @Test
        @DisplayName("StatementWriter takes no meter owner at all, so it cannot advance any series")
        void theStatementWriterHoldsNoMeterOwner() {
            // Finding H-09, severity High, asserted at the type level so it cannot silently
            // regress. A statement is a rendering of transactions posted on an earlier run, so it shares no
            // unit with any of the four instruments: not the DALYTRAN records-considered counter of
            // app/cbl/CBTRN02C.cbl:L206, not the reject counter of :L214, not a sign-on attempt, and not a
            // posted amount. Withholding the facade - not merely declining to call it - is what makes that
            // permanent: a class that holds no meter owner and no registry cannot contaminate a series
            // however it is edited later.
            assertThat(StatementWriter.class.getDeclaredConstructors())
                    .as("StatementWriter constructors")
                    .allSatisfy(constructor -> assertThat(parameterTypes(constructor))
                            .doesNotContain(MetricsConfig.class)
                            .doesNotContain(MeterRegistry.class)
                            .doesNotContain(Counter.class));
        }

        @Test
        @DisplayName("The combine and report jobs hold no meter owner, so neither can recount posted rows")
        void theCombineAndReportJobsHoldNoMeterOwner() {
            // Finding F-010, severity High, asserted at the type level for the same reason
            // StatementWriter is above: withholding the facade is permanent where declining to call it is a
            // convention. carddemo.batch.records.processed reproduces DISPLAY 'TRANSACTIONS PROCESSED :' at
            // app/cbl/CBTRN02C.cbl:L236, which is the DALYTRAN population POSTTRAN read. COMBTRAN merges the
            // transaction backup with the interest generation, and TRANREPT reads the sorted generation to
            // format a report; every row either of them touches was counted when it was posted or emitted, so
            // incrementing here added one run's work to the sum twice. The series carries NO tag, so there was
            // no dimension along which a query could separate the duplicate contributions afterwards - which
            // is what made the inflation irrecoverable rather than merely wrong, and why the type-level guard
            // is warranted.
            //
            // InterestCalculationJob is asserted alongside them because MetricsConfig's own documentation
            // claimed for a while that it increments once per interest record. It never did, and this is where
            // that claim is checked rather than trusted.
            Stream.of(CombineTransactionsJob.class, TransactionReportJob.class,
                            InterestCalculationJob.class)
                    .forEach(job -> assertThat(job.getDeclaredConstructors())
                            .as("%s reads rows an earlier run already counted, so it must hold no meter owner "
                                    + "and no registry", job.getSimpleName())
                            .allSatisfy(constructor -> assertThat(parameterTypes(constructor))
                                    .doesNotContain(MetricsConfig.class)
                                    .doesNotContain(MeterRegistry.class)
                                    .doesNotContain(Counter.class)));
        }

        @Test
        @DisplayName("The posting job is the one job that does take the facade, and it counts rejects")
        void thePostingJobTakesTheFacade() {
            // The discriminator for the assertion above: if withholding the facade were the rule everywhere,
            // that test would pass on a tree with no metrics at all. DailyTransactionPostingJob is the single
            // job that legitimately holds it, because the rejected records leave its step by a path
            // TransactionWriter never sees and app/cbl/CBTRN02C.cbl:L206 counts them before validation
            // decides anything.
            assertThat(DailyTransactionPostingJob.class.getDeclaredConstructors())
                    .as("DailyTransactionPostingJob constructors")
                    .anySatisfy(constructor -> assertThat(parameterTypes(constructor))
                            .contains(MetricsConfig.class));
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
        @DisplayName("countTransactionAmount routes each amount by its sign and loses neither")
        void amountIsRoutedByItsSign() {
            // app/cbl/CBTRN02C.cbl:L548-L552 tests IF DALYTRAN-AMT >= 0 and accumulates into
            // ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT accordingly. These two series are those two
            // accumulators, so a negative amount is neither discarded nor allowed to make a counter negative.
            metrics.countTransactionAmount(new BigDecimal("125.75"));
            metrics.countTransactionAmount(new BigDecimal("-25.75"));

            assertThat(creditTotal()).as("the >= 0 branch").isEqualTo(125.75d);
            assertThat(debitTotal()).as("the ELSE branch, carrying the magnitude").isEqualTo(25.75d);
            assertThat(netAmountTotal()).as("the signed net is exactly recoverable").isEqualTo(100.0d);
        }

        @Test
        @DisplayName("a zero amount is a credit, exactly as IF DALYTRAN-AMT >= 0 makes it")
        void zeroIsACredit() {
            metrics.countTransactionAmount(BigDecimal.ZERO);

            assertThat(creditTotal()).isZero();
            assertThat(debitTotal()).isZero();
            assertThat(registry.get(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL)
                    .tag(MetricsConfig.TAG_SIGN, MetricsConfig.SIGN_CREDIT).functionCounter())
                    .as("the credit series exists and was the one addressed")
                    .isNotNull();
        }

        @Test
        @DisplayName("neither amount series can go negative, which is what keeps a counter legal")
        void neitherAmountSeriesGoesNegative() {
            metrics.countTransactionAmount(new BigDecimal("-1000.00"));
            metrics.countTransactionAmount(new BigDecimal("-0.01"));

            assertThat(creditTotal()).isNotNegative();
            assertThat(debitTotal()).isNotNegative().isEqualTo(1000.01d);
            assertThat(netAmountTotal()).as("the net is negative, and that is carried by the difference")
                    .isEqualTo(-1000.01d);
        }
    }

    @Nested
    @DisplayName("3. Each writer is the producer for the series it reports")
    class EachWriterProducesItsSeries {

        @Test
        @DisplayName("TransactionWriter reports both processed records and their amounts, per record")
        void theTransactionWriterReportsBothOfItsSeries() throws Exception {
            TransactionWriter writer = new TransactionWriter(
                    mock(TransactionRepository.class), mock(S3Operations.class), mock(S3Client.class),
                    new FileStatusMapper(), metrics, "carddemo-batch-output", "transact");
            StepExecution execution = MetaDataInstanceFactory.createStepExecution();
            writer.beforeStep(execution);

            writer.write(Chunk.of(postedTransaction("12.34"), postedTransaction("-2.34")));

            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isEqualTo(2.0d);
            assertThat(creditTotal()).isEqualTo(12.34d);
            assertThat(debitTotal()).isEqualTo(2.34d);
            assertThat(netAmountTotal()).isEqualTo(10.0d);
        }

        @Test
        @DisplayName("TransactionWriter reports nothing for an empty chunk")
        void theTransactionWriterReportsNothingForAnEmptyChunk() throws Exception {
            TransactionWriter writer = new TransactionWriter(
                    mock(TransactionRepository.class), mock(S3Operations.class), mock(S3Client.class),
                    new FileStatusMapper(), metrics, "carddemo-batch-output", "transact");
            writer.beforeStep(MetaDataInstanceFactory.createStepExecution());

            writer.write(Chunk.of());

            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isZero();
            assertThat(creditTotal()).isZero();
            assertThat(debitTotal()).isZero();
        }

        @Test
        @DisplayName("RejectWriter reports the rejection under its own reject code, and nothing else")
        void theRejectWriterReportsItsOwnSeries() {
            S3Operations objectStorage = mock(S3Operations.class);
            // The reject writer streams into ONE (+1) generation object, so its store call is createResource
            // rather than upload; see the H-04 note on RejectWriter.
            S3Resource resource = mock(S3Resource.class);
            try {
                when(resource.getOutputStream()).thenReturn((OutputStream) new ByteArrayOutputStream());
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException("stubbing cannot fail", impossible);
            }
            when(objectStorage.createResource(anyString(), anyString())).thenReturn(resource);
            RejectWriter writer = new RejectWriter(objectStorage, mock(S3Client.class), metrics,
                    new FileStatusMapper(), "carddemo-batch-output", "gdg/dalyrejs", null);

            writer.writeReject(stagedTransaction(), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(count(MetricsConfig.METRIC_RECORDS_REJECTED, MetricsConfig.TAG_REJECT_CODE,
                    Integer.toString(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()))).isEqualTo(1.0d);
            // A rejected record is not a processed record: CBTRN02C counts them in different accumulators,
            // at :L214 and :L227 respectively.
            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED)).isZero();
        }

        @Test
        @DisplayName("StatementWriter reports its volume through its own accessor and no application counter")
        void theStatementWriterReportsNoApplicationSeries() throws Exception {
            StatementWriter writer = new StatementWriter(mock(S3Operations.class),
                    new FileStatusMapper(), Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"),
                            ZoneOffset.UTC), "carddemo-statements");

            // Deliberately not pre-opened. StatementWriter.write opens, appends and closes one statement at a
            // time, reproducing the per-account OPEN/CLOSE pair at app/cbl/CBSTM03A.CBL:L293 and :L339;
            // opening here would leave a statement open when write reached its own open, which the writer
            // refuses because an unclosed statement means a lost one.
            writer.write(Chunk.of(statement()));

            // The statement IS counted - by statementsWritten(), which the statement job reads and publishes
            // to its execution context - but NOT into carddemo.batch.records.processed. That series reproduces
            // DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:L227, whose
            // population is the daily transaction records POSTTRAN read. A statement is not one of them, and
            // summing two unrelated populations into one untagged series produced a figure belonging to no
            // job and decomposable by no query.
            assertThat(writer.statementsWritten()).isEqualTo(1L);
            assertThat(count(MetricsConfig.METRIC_RECORDS_PROCESSED))
                    .as("no application counter is advanced by this writer")
                    .isZero();
            // Nor any other series, which is the stronger claim: this writer holds no meter owner at all, so
            // it cannot advance one. Asserted against both sign-tagged series rather than one aggregate,
            // because the amount total is partitioned on the sign branch of app/cbl/CBTRN02C.cbl:L547-L552
            // and an untouched instrument has to read zero on each side of it.
            assertThat(creditTotal())
                    .as("the credit side of the amount total is untouched by a statement")
                    .isZero();
            assertThat(debitTotal())
                    .as("and so is the debit side")
                    .isZero();
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
     * Reads the {@code credit} series of the transaction amount total.
     *
     * <p>Read as a {@link io.micrometer.core.instrument.FunctionCounter}, which is the type registered: it
     * reads an exact {@link java.math.BigDecimal} accumulator and converts once, at the scrape, which is the
     * only conversion out of decimal in the facade.
     *
     * @return the accumulated credit total, {@code 0.0} if nothing has been added
     */
    private double creditTotal() {
        return amountSeries(MetricsConfig.SIGN_CREDIT);
    }

    /**
     * Reads the {@code debit} series of the transaction amount total, which carries magnitudes.
     *
     * @return the accumulated debit magnitude, {@code 0.0} if nothing has been added
     */
    private double debitTotal() {
        return amountSeries(MetricsConfig.SIGN_DEBIT);
    }

    /**
     * Derives the signed net exactly as a dashboard does, as {@code credit - debit}.
     *
     * @return the signed net total
     */
    private double netAmountTotal() {
        return creditTotal() - debitTotal();
    }

    /**
     * Reads one sign-tagged series of the transaction amount total.
     *
     * @param sign the value of {@link MetricsConfig#TAG_SIGN} identifying the series, never {@code null}
     * @return the accumulated value of that series
     */
    private double amountSeries(final String sign) {
        return registry.get(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL)
                .tag(MetricsConfig.TAG_SIGN, sign).functionCounter().count();
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

    // ====================================================================================================
    // The rendered Prometheus names, measured against a real registry and matched to the dashboard queries.
    // ====================================================================================================

    @Nested
    @DisplayName("The rendered series names are what the dashboard queries, measured and not reasoned about")
    class RenderedNamesMatchTheDashboard {

        /** The provisioned dashboard, the one consumer whose queries must resolve. */
        private static final Path DASHBOARD =
                Path.of("observability", "grafana", "dashboards", "carddemo-dashboard.json");

        /**
         * Scrapes a real Prometheus registry after every instrument has moved at least once.
         *
         * @return the scrape body, never {@code null}
         */
        private String scrapeAfterOneOfEach() {
            final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            final MetricsConfig config = new MetricsConfig(registry);
            config.countRecordProcessed();
            config.countRecordRejected(RejectCode.OVERLIMIT_TRANSACTION);
            config.countAuthenticationAttempt(AuthenticationOutcome.SUCCESS);
            config.countTransactionAmount(new BigDecimal("12.34"));
            return registry.scrape();
        }

        @Test
        @DisplayName("the amount series renders WITH the _total suffix and a sign tag, which is what the "
                + "dashboard queries")
        void theAmountSeriesRendersWithTheTotalSuffix() {
            final String scrape = scrapeAfterOneOfEach();

            assertThat(scrape)
                    .as("MEASURED, not inferred, and it is the reason the instrument is a counter. The "
                            + "Prometheus client reserves the _total suffix for counters and STRIPS it from a "
                            + "gauge, so an earlier gauge over one signed accumulator rendered as "
                            + "carddemo_transaction_amount while every panel queried "
                            + "carddemo_transaction_amount_total and got an empty result with no error")
                    .contains("carddemo_transaction_amount_total")
                    .contains("sign=\"credit\"");
            assertThat(scrape)
                    .as("a positive amount reaches the credit series at its own magnitude; the debit series "
                            + "exists at 0.0 because both are registered eagerly")
                    .contains("carddemo_transaction_amount_total{sign=\"credit\"} 12.34")
                    .contains("carddemo_transaction_amount_total{sign=\"debit\"} 0.0");
            assertThat(scrape)
                    .as("and it really is a counter, which is what keeps the suffix. Monotonicity is not "
                            + "violated by a signed stream because the sign partitions it: "
                            + "app/cbl/CBTRN02C.cbl:L548-L552 adds a negative amount to the cycle DEBIT "
                            + "accumulator, and this instrument keeps the same two accumulators of magnitudes")
                    .contains("# TYPE carddemo_transaction_amount_total counter");
        }

        @Test
        @DisplayName("all four series carry _total, so the suffix is uniform across the instrument set")
        void allFourSeriesCarryTheTotalSuffix() {
            final String scrape = scrapeAfterOneOfEach();

            assertThat(scrape)
                    .contains("carddemo_batch_records_processed_total")
                    .contains("carddemo_batch_records_rejected_total")
                    .contains("carddemo_auth_attempts_total")
                    .contains("carddemo_transaction_amount_total");
        }

        @Test
        @DisplayName("every carddemo series the dashboard queries exists in the scrape, so no panel can be "
                + "silently empty")
        void everyDashboardQueryResolvesToARenderedSeries() throws IOException {
            final String scrape = scrapeAfterOneOfEach();
            final String dashboard = Files.readString(DASHBOARD, StandardCharsets.UTF_8);

            final Matcher queried = Pattern.compile("\"expr\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(dashboard);
            final Set<String> series = new LinkedHashSet<>();
            while (queried.find()) {
                final Matcher names = Pattern.compile("carddemo_[a-z0-9_]+").matcher(queried.group(1));
                while (names.find()) {
                    series.add(names.group());
                }
            }

            assertThat(series)
                    .as("the dashboard must query something, or this test would pass by asserting nothing")
                    .isNotEmpty();
            assertThat(series).allSatisfy(name -> assertThat(scrape)
                    .as("the dashboard queries %s, which must be a series the registry actually renders. A "
                            + "query naming a series that does not exist produces an EMPTY PANEL WITH NO "
                            + "ERROR anywhere - the failure mode this test exists to make loud", name)
                    .contains(name));
        }
    }

    /**
     * The one meter filter this class declares, and the framework double-registration it resolves.
     *
     * <p>Spring Batch 5.2.4 registers {@code spring.batch.job.active} twice with different tag keys. The
     * legacy {@code BatchMetrics} binder in {@code AbstractJob.execute} creates it first, carrying the single
     * key {@code spring.batch.job.active.name}; the {@code BatchJobObservation} started immediately afterwards
     * makes Micrometer's {@code DefaultMeterObservationHandler} derive the same name from the observation
     * {@code spring.batch.job}, carrying {@code spring.batch.job.name} and {@code spring.batch.job.status}.
     * Prometheus admits one label set per metric name, so the second registration failed and its series was
     * dropped, logging a warning at <em>every</em> job launch.
     *
     * <p>These tests are written to fail if the remediation is ever reverted, and one of them
     * <strong>reproduces the defect on purpose</strong> against a real Prometheus registry - because a test
     * that only asserts the fixed state cannot show that the fix was necessary, and a filter predicate can
     * silently stop matching after a framework upgrade renames a tag key.
     */
    @Nested
    @DisplayName("the batch active-job meter has exactly one label set, so every job meter registers")
    class BatchActiveJobMeterNameIsUnambiguous {

        /** The filter under test, obtained the same way Spring obtains it: from the static factory. */
        private final MeterFilter filter = MetricsConfig.springBatchActiveJobMeterNameFilter();

        /**
         * Builds the identifier the legacy {@code BatchMetrics} binder registers.
         *
         * @param registry the registry the identifier is minted against, never {@code null}
         * @return the legacy active-job long-task timer identifier, never {@code null}
         */
        private Meter.Id legacyId(final MeterRegistry registry) {
            return LongTaskTimer.builder(MetricsConfig.METER_SPRING_BATCH_JOB_ACTIVE)
                    .description("Active jobs")
                    .tag(MetricsConfig.TAG_SPRING_BATCH_JOB_ACTIVE_NAME, "POSTTRAN")
                    .register(registry)
                    .getId();
        }

        /**
         * Builds the identifier {@code DefaultMeterObservationHandler} derives from the batch job observation.
         *
         * @param registry the registry the identifier is minted against, never {@code null}
         * @return the Observation-derived active-job long-task timer identifier, never {@code null}
         */
        private Meter.Id observationId(final MeterRegistry registry) {
            return LongTaskTimer.builder(MetricsConfig.METER_SPRING_BATCH_JOB_ACTIVE)
                    .tag(MetricsConfig.TAG_SPRING_BATCH_JOB_NAME, "POSTTRAN")
                    .tag("spring.batch.job.status", "UNKNOWN")
                    .register(registry)
                    .getId();
        }

        @Test
        @DisplayName("the legacy variant is denied and the Observation-derived variant is left alone")
        void onlyTheLegacyVariantIsDenied() {
            final MeterRegistry minting = new SimpleMeterRegistry();

            assertThat(filter.accept(legacyId(minting)))
                    .as("the legacy binder's variant is the one suppressed, because its only tag key appears "
                            + "on no other meter and so joins to nothing")
                    .isEqualTo(MeterFilterReply.DENY);
            assertThat(filter.accept(observationId(minting)))
                    .as("the Observation-derived variant survives: its job-name key is the same one "
                            + "spring_batch_job_seconds carries, so the two batch series share one vocabulary")
                    .isEqualTo(MeterFilterReply.NEUTRAL);
        }

        @Test
        @DisplayName("the predicate is the tag key, not the tag count, so common tags cannot defeat it")
        void theTagKeyAndNotTheTagCountIsWhatSelects() {
            final MeterRegistry minting = new SimpleMeterRegistry();
            final Meter.Id legacyWithCommonTags = legacyId(minting)
                    .withTags(List.of(Tag.of("application", "carddemo"),
                            Tag.of("component", "modular-monolith")));

            assertThat(filter.accept(legacyWithCommonTags))
                    .as("management.metrics.tags contributes application and component to every framework "
                            + "meter, so a predicate counting tags would stop matching the moment that list "
                            + "changed")
                    .isEqualTo(MeterFilterReply.DENY);
        }

        @Test
        @DisplayName("nothing else is filtered - not the four instruments, not any other framework meter")
        void everyOtherMeterIsNeutral() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MetricsConfig(registry);

            assertThat(registry.getMeters())
                    .as("the ten carddemo series must be visible to this assertion at all")
                    .hasSize(10)
                    .allSatisfy(meter -> assertThat(filter.accept(meter.getId()))
                            .as("%s must be untouched: this filter exists for one framework meter and may "
                                    + "never reach an instrument MetricsConfig defines", meter.getId())
                            .isEqualTo(MeterFilterReply.NEUTRAL));

            final MeterRegistry minting = new SimpleMeterRegistry();
            final List<Meter.Id> neighbours = List.of(
                    LongTaskTimer.builder("http.server.requests.active").register(minting).getId(),
                    Counter.builder("spring.batch.job")
                            .tag(MetricsConfig.TAG_SPRING_BATCH_JOB_NAME, "POSTTRAN").register(minting).getId(),
                    Counter.builder("spring.batch.step").register(minting).getId());

            assertThat(neighbours).allSatisfy(id -> assertThat(filter.accept(id))
                    .as("%s shares a prefix or a tag key with the filtered meter and must still pass. A "
                            + "name-prefix filter would have taken spring_batch_job_seconds with it, and a "
                            + "global long-task-timer switch would have taken the HTTP one", id)
                    .isEqualTo(MeterFilterReply.NEUTRAL));
        }

        @Test
        @DisplayName("without the filter a real Prometheus registry loses one variant, which is the defect")
        void theDefectReproducesWithoutTheFilter() {
            final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

            legacyId(registry);
            observationId(registry);
            final String scrape = registry.scrape();

            assertThat(scrape)
                    .as("MEASURED, not reasoned about: the legacy variant is created first and wins the name")
                    .contains("spring_batch_job_active_name=\"POSTTRAN\"");
            assertThat(scrape)
                    .as("""
                        and the Observation-derived variant is DROPPED - this is exactly the runtime state \
                        that logged a PrometheusMeterRegistry warning at every job launch. If this assertion \
                        ever fails because both label sets render, the framework has stopped \
                        double-registering and the filter can be retired.""")
                    .doesNotContain("spring_batch_job_name=\"POSTTRAN\"");
        }

        @Test
        @DisplayName("with the filter installed the surviving variant is the Observation-derived one")
        void theFilterLeavesExactlyTheObservationVariant() {
            final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            registry.config().meterFilter(filter);

            legacyId(registry);
            observationId(registry);
            final String scrape = registry.scrape();

            assertThat(scrape)
                    .as("the denied registration never reaches the Prometheus collector, so it leaves the "
                            + "name free rather than occupying it with a no-op")
                    .doesNotContain("spring_batch_job_active_name");
            assertThat(scrape)
                    .as("and the variant that does register carries the job-name and job-status keys, which "
                            + "is what makes it joinable to spring_batch_job_seconds")
                    .contains("spring_batch_job_active_seconds")
                    .contains("spring_batch_job_name=\"POSTTRAN\"")
                    .contains("spring_batch_job_status=\"UNKNOWN\"");
        }

        @Test
        @DisplayName("the factory is a static @Bean method, which is what stops it needing the registry")
        void theFactoryIsAStaticBeanMethod() throws NoSuchMethodException {
            final Method factory =
                    MetricsConfig.class.getDeclaredMethod("springBatchActiveJobMeterNameFilter");

            assertThat(factory.getAnnotation(Bean.class))
                    .as("Spring must own the filter, or it is never applied to the registry")
                    .isNotNull();
            assertThat(Modifier.isStatic(factory.getModifiers()))
                    .as("""
                        MUST STAY STATIC. Spring applies MeterFilter beans while post-processing the \
                        MeterRegistry bean. MetricsConfig's constructor takes the registry, so an instance \
                        @Bean method here would need the registry in order to build the instance that \
                        produces the filter the registry is waiting for. A static factory needs no \
                        instance, which breaks the cycle.""")
                    .isTrue();
            assertThat(factory.getReturnType()).isEqualTo(MeterFilter.class);
            assertThat(factory.getParameterCount())
                    .as("it reads no configuration and holds no collaborator")
                    .isZero();
        }

        @Test
        @DisplayName("it is the only bean this class declares, so the four instruments stay non-beans")
        void itIsTheOnlyBeanDeclared() {
            final List<String> beanMethods = Stream.of(MetricsConfig.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(Bean.class) != null)
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(beanMethods)
                    .as("""
                        The four instruments are deliberately NOT beans: the increment facade is the only \
                        path to one, which is what stopped callers resolving their own series. A second bean \
                        method here would be a second way to obtain something this class owns.""")
                    .containsExactly("springBatchActiveJobMeterNameFilter");
        }
    }

    /**
     * Who may advance the untagged processed counter, asserted over the tree rather than promised in prose.
     *
     * <p><strong>Finding H-01, severity High - these tests pin the remediation.</strong> Two jobs had become
     * producers of {@code carddemo.batch.records.processed}: the transaction-combination job counted rows it
     * re-read out of a generation object, which an earlier posting run had already counted, and the report job
     * counted report <em>lines</em>. Neither unit is a daily-transaction record read by POSTTRAN, which is
     * exactly and only what {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L206} tallies.
     *
     * <p>Why an untagged series makes this severe rather than untidy: a tag lets a query separate contributors
     * after the fact, and this counter has none, so the exported number belonged to no job and could be
     * decomposed by no expression. The dashboard panel that reads it says "SCOPE: POSTTRAN ONLY" in as many
     * words, so the panel and the counter had come to disagree with no error anywhere.
     *
     * <p>The producer set is asserted two ways, because one alone is not enough. A source scan catches a call
     * added anywhere in the tree, including in a class this test does not import. A constructor-shape assertion
     * makes the two former producers <em>unable</em> to advance anything, whatever they are edited to do later -
     * the same permanence {@code theStatementWriterHoldsNoMeterOwner} establishes for the statement writer.
     */
    @Nested
    @DisplayName("the processed counter has exactly two producers, and the tree proves it")
    class ProcessedCounterProducerSet {

        /**
         * The two facade invocations that advance the untagged processed series.
         *
         * <p>The leading dot is deliberate and load-bearing: it matches an invocation on a receiver and not the
         * declarations in {@code MetricsConfig} itself, which owns the two methods and is therefore not a caller
         * of them. Matching the bare name would count the owner and make this assertion unsatisfiable.
         */
        private static final List<String> FACADE_CALLS =
                List.of(".countRecordProcessed(", ".countRecordsProcessed(");

        /** The only two production files permitted to call either of them. */
        private static final List<String> PERMITTED_CALLERS =
                List.of("DailyTransactionPostingJob.java", "TransactionWriter.java");

        @Test
        @DisplayName("no production file outside the posting flow advances the processed counter")
        void onlyThePostingFlowAdvancesTheProcessedCounter() throws IOException {
            try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
                final List<String> callers = paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(ProcessedCounterProducerSet::callsTheProcessedFacade)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();

                assertThat(callers)
                        .as("""
                            The population of carddemo.batch.records.processed is the daily-transaction \
                            records POSTTRAN read, and nothing else. A third caller anywhere in the tree \
                            contaminates an untagged series that no query can decompose afterwards - which is \
                            why this is asserted over the whole tree and not over an import list.""")
                        .containsExactlyElementsOf(PERMITTED_CALLERS);
            }
        }

        /**
         * Whether one source file calls either processed-counter facade method.
         *
         * <p>The call is matched rather than the method name alone, so that a class discussing the counter in
         * prose - and several do, including each non-producing job explaining why it does not advance it - is
         * not counted as a caller. A commented-out call is excluded for the same reason: a line whose first
         * non-blank characters open a comment is not code.
         *
         * @param path the source file to inspect
         * @return {@code true} when the file contains a live call to either facade method
         */
        private static boolean callsTheProcessedFacade(final Path path) {
            try {
                return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(line -> !line.startsWith("//") && !line.startsWith("*")
                                && !line.startsWith("/*"))
                        .anyMatch(line -> FACADE_CALLS.stream().anyMatch(line::contains));
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }

        @Test
        @DisplayName("neither former producer can reach a counter, a registry or the facade any more")
        void neitherFormerProducerHoldsAMeterOwner() {
            Stream.of(CombineTransactionsJob.class, TransactionReportJob.class)
                    .forEach(job -> assertThat(job.getDeclaredConstructors())
                            .as("%s constructors", job.getSimpleName())
                            .allSatisfy(constructor -> assertThat(parameterTypes(constructor))
                                    .as("""
                                        Withholding the facade - not merely declining to call it - is what \
                                        makes the fix permanent: a class that holds no meter owner and no \
                                        registry cannot contaminate a series however it is edited later. Its \
                                        volume belongs in its own execution-context entries and in the Spring \
                                        Batch step metrics, which carry a job dimension.""")
                                    .doesNotContain(MetricsConfig.class)
                                    .doesNotContain(MeterRegistry.class)
                                    .doesNotContain(Counter.class)));
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

        @Test
        @DisplayName("the posting flow still advances it, so the remediation removed noise and not the signal")
        void thePostingFlowStillAdvancesIt() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final MetricsConfig owner = new MetricsConfig(registry);

            owner.countRecordProcessed();
            owner.countRecordsProcessed(4);

            assertThat(registry.find(MetricsConfig.METRIC_RECORDS_PROCESSED).counter())
                    .isNotNull()
                    .extracting(Counter::count)
                    .as("one plus four: the two facade methods are numerically interchangeable, which is what "
                            + "lets a chunk writer advance once for a chunk of records without looping")
                    .isEqualTo(5.0D);
        }
    }
}
