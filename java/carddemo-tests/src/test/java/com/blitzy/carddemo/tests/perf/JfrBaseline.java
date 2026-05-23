/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.blitzy.carddemo.tests.perf;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * JFR (Java Flight Recorder) performance regression test harness.
 *
 * <p>This is the {@code @Tag("perf")} test class mandated by AAP &sect;0.6.11 and
 * &sect;0.7.2. It captures Java Flight Recorder recordings of representative
 * batch workloads, parses selected JFR events to compute aggregate metrics
 * (wall-clock duration, total GC pause time, peak committed heap, allocation
 * count), and asserts that each metric is within a <strong>10% regression
 * band</strong> versus a checked-in baseline captured on equivalent hardware.
 *
 * <h2>Workloads (per AAP &sect;0.6.11)</h2>
 * <ul>
 *   <li>{@code PostTransactions} &mdash; composes CBTRN02C use case (full
 *       posting engine, {@code app/cbl/CBTRN02C.cbl}); the MOST CRITICAL
 *       workload for posting-throughput TPS</li>
 *   <li>{@code InterestCalculation} &mdash; composes CBACT04C use case
 *       ({@code app/cbl/CBACT04C.cbl}); CRITICAL for monetary-arithmetic
 *       throughput. Exercises the COBOL formula
 *       <code>WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200</code>
 *       at {@code app/cbl/CBACT04C.cbl:L462-L468}</li>
 *   <li>{@code CombineTransactions} &mdash; sort + merge workload (driven by
 *       {@code app/jcl/COMBTRAN.jcl})</li>
 *   <li>{@code CreateStatements} &mdash; composes CBSTM03A use case
 *       ({@code app/cbl/CBSTM03A.CBL})</li>
 * </ul>
 *
 * <h2>JVM Tuning Baseline (per AAP &sect;0.3.4)</h2>
 * Recommended JVM flags when running this test for production-representative
 * measurements:
 * <pre>
 *   -XX:+UseCompactObjectHeaders                                (JEP 519 Final, ~20-30% heap reduction)
 *   -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational      (JEP 521 Final, low-pause batch)
 * </pre>
 * <strong>FORBIDDEN:</strong> {@code --enable-preview} (per AAP &sect;0.6.7,
 * &sect;0.7.4).
 *
 * <h2>PR Gate Separation</h2>
 * Per AAP &sect;0.6.11 this test is the <em>CI-only</em> nightly performance
 * gate; the byte-for-byte golden-record harness is the PR gate. This separation
 * prevents performance variability from blocking correctness-focused PRs.
 * Surefire's standard configuration excludes {@code @Tag("perf")} unless the
 * {@code -Dgroups=perf} system property opts in, AND the file-name pattern of
 * this class ({@code JfrBaseline.java}) intentionally does not match the
 * default Surefire {@code *Test.java}/{@code *Tests.java}/{@code *Properties.java}
 * include set defined in the parent {@code pom.xml} &mdash; defence-in-depth so
 * the harness is opt-in by explicit invocation
 * ({@code mvn test -Dtest=JfrBaseline -Dgroups=perf -Dperf.enabled=true}).
 *
 * <h2>Baseline Files</h2>
 * Checked-in baseline metrics live under
 * {@code src/test/resources/perf/baseline-<workload>.properties}. Runtime JFR
 * recordings are written to {@code target/jfr/<workload>-<timestamp>.jfr} (the
 * directory may be overridden via the {@code jfr.recording.dir} system
 * property). The recordings directory is gitignored per {@code java/.gitignore}.
 *
 * <h2>Baseline Regeneration</h2>
 * Procedure documented in {@code java/MIGRATION_NOTES.md} (per AAP &sect;0.7.5
 * TODO resolution).
 *
 * <h2>Observability Boundary (per AAP &sect;0.6.12)</h2>
 * JFR is the SOLE observability tool for performance regression. No Micrometer,
 * Prometheus, Grafana, Jaeger, JMH or GCViewer. SLF4J + Logback is the only
 * permitted logging mechanism.
 *
 * @see jdk.jfr.Recording
 * @see jdk.jfr.consumer.RecordingFile
 */
@Tag("perf")
public class JfrBaseline {

    // ---------------------------------------------------------------------
    // Logger (SLF4J only; NO System.out / System.err / printf per AAP §0.6.12)
    // ---------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(JfrBaseline.class);

    // ---------------------------------------------------------------------
    // Regression-band constants (BigDecimal; NEVER double/float per AAP §0.6.1)
    // ---------------------------------------------------------------------

    /** Regression band tolerance per AAP &sect;0.6.11 = 10 (percent). */
    private static final BigDecimal REGRESSION_BAND_PERCENT = new BigDecimal("10");

    /** Multiplier for upper bound: 1.10 (10% over baseline). */
    private static final BigDecimal UPPER_BOUND_MULTIPLIER = new BigDecimal("1.10");

    // ---------------------------------------------------------------------
    // JFR recording configuration
    // ---------------------------------------------------------------------

    /** Default JFR configuration name ("profile" = comprehensive event capture). */
    private static final String JFR_CONFIG_NAME = "profile";

    /** Maximum recording size hint (1 GiB); JFR rotates older events if exceeded. */
    private static final long MAX_RECORDING_SIZE_BYTES = 1024L * 1024L * 1024L;

    /** Default maximum recording age (java.time.Duration per AAP §0.6.4). */
    private static final Duration MAX_RECORDING_AGE = Duration.ofHours(1);

    // ---------------------------------------------------------------------
    // Baseline resource path conventions
    // ---------------------------------------------------------------------

    /** Classpath-relative baseline resources root (under src/test/resources/perf/). */
    private static final String BASELINE_RESOURCE_PREFIX = "/perf/baseline-";

    /** Classpath-relative baseline resources suffix. */
    private static final String BASELINE_RESOURCE_SUFFIX = ".properties";

    // ---------------------------------------------------------------------
    // Recording-directory system property (override target/jfr/)
    // ---------------------------------------------------------------------

    /** System property name for JFR recording output directory. */
    private static final String JFR_RECORDING_DIR_PROPERTY = "jfr.recording.dir";

    /** Default JFR recording directory (relative to module root) if system property unset. */
    private static final String DEFAULT_JFR_RECORDING_DIR = "target/jfr";

    // ---------------------------------------------------------------------
    // Filename-safe timestamp (java.time per AAP §0.6.4; NEVER SimpleDateFormat)
    // ---------------------------------------------------------------------

    /** Filename-safe timestamp formatter (ISO-like, no colons). */
    private static final DateTimeFormatter FILENAME_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // =====================================================================
    // Test methods (one @Test per workload, double-gated by @Tag + @EnabledIfSystemProperty)
    // =====================================================================

    /**
     * JFR baseline regression test for the POST-TRANSACTIONS workload
     * (COBOL source: {@code app/cbl/CBTRN02C.cbl} &mdash; full posting engine).
     *
     * @throws Exception if recording or workload execution fails
     */
    @Test
    @Tag("perf")
    @EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
    void postTransactionsWithinRegressionBand() throws Exception {
        runWorkloadAndAssertBand(new Workload.PostTransactions());
    }

    /**
     * JFR baseline regression test for the INTEREST-CALCULATION workload
     * (COBOL source: {@code app/cbl/CBACT04C.cbl} &mdash; CBACT04C interest engine).
     *
     * @throws Exception if recording or workload execution fails
     */
    @Test
    @Tag("perf")
    @EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
    void interestCalculationWithinRegressionBand() throws Exception {
        runWorkloadAndAssertBand(new Workload.InterestCalculation());
    }

    /**
     * JFR baseline regression test for the COMBINE-TRANSACTIONS workload
     * (JCL source: {@code app/jcl/COMBTRAN.jcl} &mdash; sort + merge orchestration).
     *
     * @throws Exception if recording or workload execution fails
     */
    @Test
    @Tag("perf")
    @EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
    void combineTransactionsWithinRegressionBand() throws Exception {
        runWorkloadAndAssertBand(new Workload.CombineTransactions());
    }

    /**
     * JFR baseline regression test for the CREATE-STATEMENTS workload
     * (COBOL source: {@code app/cbl/CBSTM03A.CBL} &mdash; statement generation).
     *
     * @throws Exception if recording or workload execution fails
     */
    @Test
    @Tag("perf")
    @EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
    void createStatementsWithinRegressionBand() throws Exception {
        runWorkloadAndAssertBand(new Workload.CreateStatements());
    }

    // =====================================================================
    // Top-level orchestrator (Phase 5 of agent prompt)
    // =====================================================================

    /**
     * Recording lifecycle orchestrator: starts a JFR Recording, dispatches to
     * the workload runner, dumps the recording to disk, parses events, compares
     * against the checked-in baseline, and asserts every metric is within the
     * 10% regression band per AAP &sect;0.6.11.
     *
     * @param workload  the workload to execute
     * @throws IOException     if recording, baseline I/O or directory creation fails
     * @throws ParseException  if the JFR {@value #JFR_CONFIG_NAME} configuration is malformed
     */
    private void runWorkloadAndAssertBand(Workload workload) throws IOException, ParseException {
        LOG.info("Starting JFR baseline test for workload={} (COBOL source={})",
            workload.name(), workload.cobolSourcePath());

        Path recordingDir = resolveRecordingDir();
        Files.createDirectories(recordingDir);

        String timestamp = LocalDateTime.now().format(FILENAME_TIMESTAMP);
        Path recordingPath = recordingDir.resolve(workload.name() + "-" + timestamp + ".jfr");

        Instant start;
        Instant end;

        try (Recording recording = newRecording()) {
            recording.setName("carddemo-" + workload.name());
            recording.setDestination(recordingPath);
            recording.setMaxSize(MAX_RECORDING_SIZE_BYTES);
            recording.setMaxAge(MAX_RECORDING_AGE);
            recording.setDumpOnExit(true);

            recording.start();
            start = Instant.now();
            try {
                executeWorkload(workload);
            } finally {
                end = Instant.now();
                recording.stop();
            }
            // recording.dump() is implicit via setDestination + close
        }

        LOG.info("JFR recording written to {}", recordingPath);

        Duration measuredWallClock = Duration.between(start, end);
        AggregateMetrics measured = parseJfrRecording(recordingPath, measuredWallClock);
        LOG.info("Measured metrics for {}: {}", workload.name(), measured);

        BaselineMetrics baseline = loadBaseline(workload);
        LOG.info("Baseline metrics for {}: {}", workload.name(), baseline);

        List<RegressionVerdict> verdicts = compare(workload, baseline, measured);
        assertAllWithinBand(workload, verdicts);
    }

    // =====================================================================
    // Helper: resolveRecordingDir (Phase 5.1)
    // =====================================================================

    /**
     * Resolves the JFR recording output directory from the
     * {@value #JFR_RECORDING_DIR_PROPERTY} system property, falling back to
     * {@value #DEFAULT_JFR_RECORDING_DIR} if unset. Uses {@link Path}
     * exclusively per AAP &sect;0.6.5 (NEVER {@code java.io.File}).
     *
     * @return absolute Path to recording directory
     */
    private static Path resolveRecordingDir() {
        String configured = System.getProperty(JFR_RECORDING_DIR_PROPERTY, DEFAULT_JFR_RECORDING_DIR);
        return Paths.get(configured).toAbsolutePath();
    }

    // =====================================================================
    // Helper: newRecording (Phase 5.2)
    // =====================================================================

    /**
     * Creates a new (unstarted) JFR {@link Recording} using the "profile"
     * configuration which captures GC events, allocation events, lock events,
     * CPU load, and class loading at low overhead.
     *
     * @return a new (unstarted) {@link Recording}
     * @throws IOException     if the JFR configuration cannot be loaded
     * @throws ParseException  if the JFR configuration is malformed
     */
    private static Recording newRecording() throws IOException, ParseException {
        return new Recording(Configuration.getConfiguration(JFR_CONFIG_NAME));
    }

    // =====================================================================
    // Workload execution dispatch (Phase 6 — pattern-matching switch; AAP §0.6.7)
    // =====================================================================

    /**
     * Dispatches to the appropriate workload runner using a pattern-matching
     * switch with compiler-enforced exhaustiveness over the sealed
     * {@link Workload} hierarchy. A {@code default} branch is FORBIDDEN per
     * AAP &sect;0.6.7 &mdash; adding a new permit without updating this switch
     * yields a compilation error rather than a silent runtime miss.
     *
     * <p>Implementation note: each branch is currently a stub that simulates
     * representative work because the corresponding batch driver classes
     * ({@code PostTransactionsBatch}, {@code InterestCalculationBatch},
     * {@code CombineTransactionsBatch}, {@code CreateStatementsBatch}) are
     * authored by descendant agents in the {@code carddemo-batch} module.
     * When those drivers are wired in, replace each stub body with a direct
     * invocation, e.g.:
     *
     * <pre>{@code
     * case Workload.PostTransactions w -> new PostTransactionsBatch(...).execute();
     * }</pre>
     *
     * Until then, the stubs ensure the harness compiles and runs end-to-end.
     *
     * @param workload  the workload to execute
     */
    private void executeWorkload(Workload workload) {
        switch (workload) {
            case Workload.PostTransactions w    -> runPostTransactionsStub();
            case Workload.InterestCalculation w -> runInterestCalculationStub();
            case Workload.CombineTransactions w -> runCombineTransactionsStub();
            case Workload.CreateStatements w    -> runCreateStatementsStub();
        }
    }

    // =====================================================================
    // Workload stub implementations (Phase 6.1)
    //
    // Each stub simulates representative work using BigDecimal arithmetic
    // (NEVER double/float per AAP §0.6.1) and java.nio.file.Path (NEVER
    // java.io.File per AAP §0.6.5). Stubs warm up enough to produce
    // meaningful JFR events (GC, allocation, lock, CPU) at the chosen
    // sampling rates in the "profile" configuration.
    // =====================================================================

    /**
     * Simulates the POST-TRANSACTIONS workload until the real
     * {@code PostTransactionsBatch} is wired in. Performs ~50,000 BigDecimal
     * additions to exercise the monetary-arithmetic and allocation paths the
     * real workload will dominate.
     */
    private void runPostTransactionsStub() {
        BigDecimal balance = new BigDecimal("0.00");
        BigDecimal amount = new BigDecimal("12.34");
        MathContext mc = MathContext.DECIMAL128;
        for (int i = 0; i < 50_000; i++) {
            balance = balance.add(amount, mc).setScale(2, RoundingMode.DOWN);
        }
        LOG.debug("PostTransactions stub finished; final balance scale={}", balance.scale());
    }

    /**
     * Simulates the INTEREST-CALCULATION workload following the COBOL formula
     * {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} from
     * {@code app/cbl/CBACT04C.cbl:L462-L468}. Uses {@link MathContext#DECIMAL128}
     * for intermediate products and {@link RoundingMode#DOWN} (truncation) for
     * the final {@code scale=2} divisor &mdash; matching the COBOL default
     * unrounded semantics per AAP &sect;0.6.1.
     */
    private void runInterestCalculationStub() {
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal rate = new BigDecimal("18.00");
        BigDecimal divisor = new BigDecimal("1200");
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < 25_000; i++) {
            BigDecimal product = balance.multiply(rate, mc);
            BigDecimal interest = product.divide(divisor, 2, RoundingMode.DOWN);
            total = total.add(interest, mc).setScale(2, RoundingMode.DOWN);
        }
        LOG.debug("InterestCalculation stub finished; total scale={}", total.scale());
    }

    /**
     * Simulates the COMBINE-TRANSACTIONS workload by sorting a 20,000-entry
     * in-memory list of synthetic transaction-ID strings. Per AAP &sect;0.6.6,
     * virtual threads are PERMITTED for parallel fan-out where order does not
     * matter, but the sort order itself MUST be preserved &mdash; hence the
     * single-threaded {@link List#sort} here rather than a parallel sort.
     */
    private void runCombineTransactionsStub() {
        List<String> records = new ArrayList<>(20_000);
        for (int i = 0; i < 20_000; i++) {
            records.add(String.format("TRAN%010d", (19999 - i)));
        }
        records.sort(String::compareTo);
        LOG.debug("CombineTransactions stub sorted {} records", records.size());
    }

    /**
     * Simulates the CREATE-STATEMENTS workload by generating 1,000 statement
     * blocks via Java 15+ text blocks (per AAP &sect;0.6.7). Accumulates total
     * character count via {@link AtomicLong} &mdash; this anticipates the
     * lambda-friendly accumulation pattern the real {@code CreateStatementsBatch}
     * will use when running per-statement work on virtual threads per AAP
     * &sect;0.6.6.
     */
    private void runCreateStatementsStub() {
        AtomicLong totalChars = new AtomicLong();
        for (int i = 0; i < 1000; i++) {
            String stmt = """
                ============================================================
                Statement for Account %011d
                ============================================================
                Beginning Balance:    %s
                Current Balance:      %s
                Available Credit:     %s
                """.formatted(i, "1000.00", "1500.00", "500.00");
            totalChars.addAndGet(stmt.length());
        }
        LOG.debug("CreateStatements stub produced {} total chars", totalChars.get());
    }

    // =====================================================================
    // JFR event parsing (Phase 7)
    // =====================================================================

    /**
     * Parses a JFR recording file and extracts aggregate performance metrics.
     *
     * <p>Selected events:
     * <ul>
     *   <li>{@code jdk.GarbageCollection} / {@code jdk.YoungGarbageCollection} /
     *       {@code jdk.OldGarbageCollection} &mdash; for GC pause duration and count</li>
     *   <li>{@code jdk.GCHeapSummary} &mdash; for peak heap usage</li>
     *   <li>{@code jdk.ObjectAllocationInNewTLAB} / {@code jdk.ObjectAllocationOutsideTLAB} /
     *       {@code jdk.ObjectAllocationSample} &mdash; for allocation count</li>
     * </ul>
     *
     * <p>The recording is read through {@link RecordingFile} inside a
     * try-with-resources block per AAP &sect;0.6.5. Missing or untyped event
     * fields are tolerated via {@link RecordedEvent#hasField(String)} checks.
     *
     * @param recordingPath      absolute {@link Path} to the JFR recording
     *                           (per AAP &sect;0.6.5 &mdash; never {@code java.io.File})
     * @param wallClockDuration  externally measured wall-clock duration
     * @return aggregate metrics extracted from the recording
     * @throws IOException if the recording cannot be read
     */
    private AggregateMetrics parseJfrRecording(Path recordingPath, Duration wallClockDuration)
            throws IOException {

        Duration totalGcPause = Duration.ZERO;
        long gcCount = 0L;
        long peakHeap = 0L;
        long allocCount = 0L;

        // Set.of(...) produces an immutable Set<String> per AAP §0.6.7 (records and
        // sealed types for closed taxonomies; immutable collection literals where
        // ordinary value sets are needed).
        Set<String> gcEvents = Set.of(
            "jdk.GarbageCollection",
            "jdk.YoungGarbageCollection",
            "jdk.OldGarbageCollection"
        );
        Set<String> allocEvents = Set.of(
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample"
        );

        try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                String type = event.getEventType().getName();

                if (gcEvents.contains(type)) {
                    gcCount++;
                    Duration eventDuration = event.getDuration();
                    if (eventDuration != null && !eventDuration.isNegative()) {
                        totalGcPause = totalGcPause.plus(eventDuration);
                    }
                } else if ("jdk.GCHeapSummary".equals(type)) {
                    if (event.hasField("heapUsed")) {
                        long heapUsed = event.getLong("heapUsed");
                        if (heapUsed > peakHeap) {
                            peakHeap = heapUsed;
                        }
                    }
                } else if (allocEvents.contains(type)) {
                    allocCount++;
                }
            }
        }

        return new AggregateMetrics(
            wallClockDuration,
            totalGcPause,
            gcCount,
            peakHeap,
            allocCount
        );
    }

    // =====================================================================
    // Baseline loading (Phase 8)
    // =====================================================================

    /**
     * Loads the checked-in baseline metrics for {@code workload} from the
     * classpath resource
     * {@code /perf/baseline-<workload.name()>.properties}.
     *
     * <p>If the resource is missing, the test fails with an explicit message
     * pointing to {@code java/MIGRATION_NOTES.md} for the regeneration
     * procedure (per AAP &sect;0.7.5 TODO resolution). This is intentional:
     * silent passes on missing baselines would defeat the purpose of the
     * regression gate.
     *
     * @param workload  the workload whose baseline to load
     * @return parsed baseline metrics
     * @throws IOException if the baseline resource cannot be opened or read
     */
    private BaselineMetrics loadBaseline(Workload workload) throws IOException {
        String resourceName = BASELINE_RESOURCE_PREFIX + workload.name() + BASELINE_RESOURCE_SUFFIX;
        Properties props = new Properties();
        try (InputStream stream = getClass().getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IOException(
                    "Baseline resource not found on classpath: " + resourceName
                    + ". See java/MIGRATION_NOTES.md for the COBOL baseline capture procedure.");
            }
            props.load(stream);
        }

        long wallMillis = Long.parseLong(props.getProperty("wall.clock.millis", "0"));
        long gcPauseMillis = Long.parseLong(props.getProperty("gc.pause.millis", "0"));
        long gcCount = Long.parseLong(props.getProperty("gc.collection.count", "0"));
        long peakHeap = Long.parseLong(props.getProperty("peak.heap.bytes", "0"));
        long allocCount = Long.parseLong(props.getProperty("allocation.count", "0"));
        String capturedOn = props.getProperty("captured.on", "unknown");
        String hardware = props.getProperty("captured.hardware", "unknown");

        return new BaselineMetrics(
            Duration.ofMillis(wallMillis),
            Duration.ofMillis(gcPauseMillis),
            gcCount,
            peakHeap,
            allocCount,
            capturedOn,
            hardware
        );
    }

    // =====================================================================
    // Comparison + assertion (Phase 9)
    // =====================================================================

    /**
     * Compares measured metrics against the baseline and produces one
     * {@link RegressionVerdict} per metric.
     *
     * <p>All comparisons use {@link BigDecimal} arithmetic with
     * {@link MathContext#DECIMAL128} (per AAP &sect;0.6.1 / &sect;0.3.3 &mdash;
     * NEVER {@code double}/{@code float}). Upper bound is
     * {@code baseline * 1.10} per the 10% band in AAP &sect;0.6.11.
     *
     * @param workload  the workload (for log/diagnostic context)
     * @param baseline  the checked-in baseline
     * @param measured  the JFR-extracted measurements
     * @return list of per-metric verdicts (5 entries)
     */
    private List<RegressionVerdict> compare(Workload workload, BaselineMetrics baseline,
                                            AggregateMetrics measured) {
        // workload is logged for context; unused parameter would otherwise lint as warning.
        LOG.debug("Computing regression verdicts for workload={} (band={}%)",
            workload.name(), REGRESSION_BAND_PERCENT.toPlainString());

        List<RegressionVerdict> verdicts = new ArrayList<>();
        verdicts.add(verdict("wall-clock-millis",
            BigDecimal.valueOf(baseline.wallClockDuration().toMillis()),
            BigDecimal.valueOf(measured.wallClockDuration().toMillis())));
        verdicts.add(verdict("gc-pause-millis",
            BigDecimal.valueOf(baseline.totalGcPause().toMillis()),
            BigDecimal.valueOf(measured.totalGcPause().toMillis())));
        verdicts.add(verdict("gc-collection-count",
            BigDecimal.valueOf(baseline.gcCollectionCount()),
            BigDecimal.valueOf(measured.gcCollectionCount())));
        verdicts.add(verdict("peak-heap-bytes",
            BigDecimal.valueOf(baseline.peakHeapBytes()),
            BigDecimal.valueOf(measured.peakHeapBytes())));
        verdicts.add(verdict("allocation-count",
            BigDecimal.valueOf(baseline.allocationCount()),
            BigDecimal.valueOf(measured.allocationCount())));
        return verdicts;
    }

    /**
     * Builds a single {@link RegressionVerdict} for one metric.
     *
     * <p>{@code upperBound = baselineValue * UPPER_BOUND_MULTIPLIER} using
     * {@link MathContext#DECIMAL128}. {@code withinBand} is true iff
     * {@code measuredValue.compareTo(upperBound) <= 0}. {@link BigDecimal#compareTo}
     * is used (not {@code equals}) because it ignores scale differences &mdash;
     * canonical pattern per Java 25 numeric semantics.
     *
     * @param metricName     metric label
     * @param baselineValue  baseline value
     * @param measuredValue  measured value
     * @return constructed verdict
     */
    private RegressionVerdict verdict(String metricName, BigDecimal baselineValue,
                                      BigDecimal measuredValue) {
        BigDecimal upperBound =
            baselineValue.multiply(UPPER_BOUND_MULTIPLIER, MathContext.DECIMAL128);
        boolean withinBand = measuredValue.compareTo(upperBound) <= 0;
        return new RegressionVerdict(metricName, baselineValue, measuredValue, upperBound, withinBand);
    }

    /**
     * Asserts that every verdict is within the 10% regression band.
     *
     * <p>If any verdict has {@code withinBand == false}, the test fails with a
     * comprehensive multi-line message that lists every out-of-band metric
     * (baseline, upperBound, measured) and the workload's COBOL source path
     * for audit traceability per AAP &sect;0.8.1.
     *
     * <p>Uses AssertJ's {@code fail(String)} and a global {@code assertThat(violations).isEmpty()}
     * for redundant safety (the {@code fail} call also raises an
     * {@link AssertionError} but the {@code assertThat} call is documented as
     * the canonical project pattern per the carddemo-tests module
     * conventions).
     *
     * @param workload  the workload (for failure message context)
     * @param verdicts  the per-metric verdicts
     */
    private void assertAllWithinBand(Workload workload, List<RegressionVerdict> verdicts) {
        List<RegressionVerdict> violations = verdicts.stream()
            .filter(v -> !v.withinBand())
            .toList();

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder()
                .append("JFR performance regression detected for workload=")
                .append(workload.name())
                .append(" (COBOL source=")
                .append(workload.cobolSourcePath())
                .append(").\n")
                .append("Regression band: ")
                .append(REGRESSION_BAND_PERCENT.toPlainString())
                .append("% (per AAP §0.6.11)\n")
                .append("Violations:\n");
            for (RegressionVerdict v : violations) {
                msg.append("  - ").append(v.metricName())
                    .append(": baseline=").append(v.baselineValue().toPlainString())
                    .append(", upperBound=").append(v.upperBound().toPlainString())
                    .append(", measured=").append(v.measuredValue().toPlainString())
                    .append('\n');
            }
            fail(msg.toString());
        }

        // Canonical AssertJ-style global assertion (also catches any state-mismatch).
        assertThat(violations)
            .as("All metrics for workload=%s must be within the %s%% regression band",
                workload.name(), REGRESSION_BAND_PERCENT.toPlainString())
            .isEmpty();
    }

    // =====================================================================
    // Nested types — Workload taxonomy (sealed interface per AAP §0.6.7)
    // =====================================================================

    /**
     * Closed taxonomy of measured workloads per AAP &sect;0.6.11.
     *
     * <p>Sealed interface with one record permit per workload &mdash; this
     * enables compiler-enforced exhaustiveness checking at every dispatch
     * site per AAP &sect;0.6.7. Adding a new permit without updating the
     * {@link JfrBaseline#executeWorkload(Workload)} pattern-matching switch
     * causes a compilation error rather than a silent runtime miss.
     */
    public sealed interface Workload
            permits Workload.PostTransactions,
                    Workload.InterestCalculation,
                    Workload.CombineTransactions,
                    Workload.CreateStatements {

        /** Workload name used for baseline file naming and JFR recording filename. */
        String name();

        /** COBOL source program reference for traceability per AAP &sect;0.8.1. */
        String cobolSourcePath();

        /** Composes CBTRN02C use case &mdash; full posting engine. */
        record PostTransactions() implements Workload {
            @Override public String name() { return "post-transactions"; }
            @Override public String cobolSourcePath() { return "app/cbl/CBTRN02C.cbl"; }
        }

        /** Composes CBACT04C use case &mdash; interest calculation. */
        record InterestCalculation() implements Workload {
            @Override public String name() { return "interest-calculation"; }
            @Override public String cobolSourcePath() { return "app/cbl/CBACT04C.cbl"; }
        }

        /** Sort + merge workload driven by COMBTRAN JCL. */
        record CombineTransactions() implements Workload {
            @Override public String name() { return "combine-transactions"; }
            @Override public String cobolSourcePath() { return "app/jcl/COMBTRAN.jcl"; }
        }

        /** Composes CBSTM03A use case &mdash; statement generation. */
        record CreateStatements() implements Workload {
            @Override public String name() { return "create-statements"; }
            @Override public String cobolSourcePath() { return "app/cbl/CBSTM03A.CBL"; }
        }
    }

    // =====================================================================
    // Nested types — Metrics carrier records (per AAP §0.6.7)
    // =====================================================================

    /**
     * Aggregate performance metrics extracted from a JFR recording.
     *
     * <p>Every numeric field uses an appropriate type from the standard
     * library: {@link Duration} for time (per AAP &sect;0.6.4 &mdash; NEVER
     * {@code long millis} arithmetic) and {@code long} for monotonic counters.
     * {@code peakHeapBytes} is the high-water mark of the
     * {@code heapUsed} field across observed {@code jdk.GCHeapSummary} events.
     *
     * @param wallClockDuration  total wall-clock duration of the workload
     * @param totalGcPause       sum of all GC pause durations
     * @param gcCollectionCount  number of GC cycles observed
     * @param peakHeapBytes      peak heap usage in bytes (jdk.GCHeapSummary.heapUsed)
     * @param allocationCount    count of object-allocation JFR events observed
     */
    public record AggregateMetrics(
        Duration wallClockDuration,
        Duration totalGcPause,
        long gcCollectionCount,
        long peakHeapBytes,
        long allocationCount
    ) {
        /**
         * Validation invariants via JEP 513 compact-form canonical constructor
         * (per AAP &sect;0.6.7). Verifies non-{@code null} durations and
         * non-negative counters before binding the record's fields.
         */
        public AggregateMetrics {
            if (wallClockDuration == null) {
                throw new IllegalArgumentException("wallClockDuration must not be null");
            }
            if (totalGcPause == null) {
                throw new IllegalArgumentException("totalGcPause must not be null");
            }
            if (wallClockDuration.isNegative()) {
                throw new IllegalArgumentException(
                    "wallClockDuration must not be negative: " + wallClockDuration);
            }
            if (totalGcPause.isNegative()) {
                throw new IllegalArgumentException(
                    "totalGcPause must not be negative: " + totalGcPause);
            }
            if (gcCollectionCount < 0L) {
                throw new IllegalArgumentException(
                    "gcCollectionCount must not be negative: " + gcCollectionCount);
            }
            if (peakHeapBytes < 0L) {
                throw new IllegalArgumentException(
                    "peakHeapBytes must not be negative: " + peakHeapBytes);
            }
            if (allocationCount < 0L) {
                throw new IllegalArgumentException(
                    "allocationCount must not be negative: " + allocationCount);
            }
        }
    }

    /**
     * Baseline metrics loaded from
     * {@code src/test/resources/perf/baseline-<workload>.properties}.
     *
     * <p>Properties file format (all values are {@code long} millis or bytes):
     * <pre>
     *   wall.clock.millis=12345
     *   gc.pause.millis=120
     *   gc.collection.count=4
     *   peak.heap.bytes=104857600
     *   allocation.count=58000
     *   captured.on=2025-09-16T12:34:56
     *   captured.hardware=z15-equivalent
     * </pre>
     *
     * @param wallClockDuration  baseline wall-clock duration
     * @param totalGcPause       baseline total GC pause duration
     * @param gcCollectionCount  baseline GC cycle count
     * @param peakHeapBytes      baseline peak heap usage in bytes
     * @param allocationCount    baseline allocation event count
     * @param capturedOn         human-readable capture timestamp (free-form)
     * @param capturedHardware   capture hardware identifier (free-form)
     */
    public record BaselineMetrics(
        Duration wallClockDuration,
        Duration totalGcPause,
        long gcCollectionCount,
        long peakHeapBytes,
        long allocationCount,
        String capturedOn,
        String capturedHardware
    ) {
        /**
         * JEP 513 compact-form canonical constructor with null-to-"unknown"
         * coercion for optional metadata fields. Durations and counters use
         * the same validation rules as {@link AggregateMetrics}.
         */
        public BaselineMetrics {
            if (wallClockDuration == null) {
                throw new IllegalArgumentException("wallClockDuration must not be null");
            }
            if (totalGcPause == null) {
                throw new IllegalArgumentException("totalGcPause must not be null");
            }
            if (wallClockDuration.isNegative()) {
                throw new IllegalArgumentException(
                    "wallClockDuration must not be negative: " + wallClockDuration);
            }
            if (totalGcPause.isNegative()) {
                throw new IllegalArgumentException(
                    "totalGcPause must not be negative: " + totalGcPause);
            }
            if (gcCollectionCount < 0L) {
                throw new IllegalArgumentException(
                    "gcCollectionCount must not be negative: " + gcCollectionCount);
            }
            if (peakHeapBytes < 0L) {
                throw new IllegalArgumentException(
                    "peakHeapBytes must not be negative: " + peakHeapBytes);
            }
            if (allocationCount < 0L) {
                throw new IllegalArgumentException(
                    "allocationCount must not be negative: " + allocationCount);
            }
            // Optional metadata: coerce nulls to "unknown" (compact-form
            // canonical constructor permits parameter reassignment before
            // implicit field binding).
            if (capturedOn == null) {
                capturedOn = "unknown";
            }
            if (capturedHardware == null) {
                capturedHardware = "unknown";
            }
        }
    }

    /**
     * Per-metric regression verdict.
     *
     * <p>All numeric values use {@link BigDecimal} per AAP &sect;0.6.1 (NEVER
     * {@code double}/{@code float}). The {@code upperBound} is
     * {@code baselineValue * 1.10} per the 10% regression band mandated by
     * AAP &sect;0.6.11. {@code withinBand} is {@code true} when
     * {@code measuredValue.compareTo(upperBound) <= 0}.
     *
     * @param metricName     short metric name (e.g. {@code "wall-clock-millis"})
     * @param baselineValue  baseline value
     * @param measuredValue  measured value
     * @param upperBound     baseline * 1.10 (10% band per AAP &sect;0.6.11)
     * @param withinBand     {@code true} iff {@code measuredValue <= upperBound}
     */
    public record RegressionVerdict(
        String metricName,
        BigDecimal baselineValue,
        BigDecimal measuredValue,
        BigDecimal upperBound,
        boolean withinBand
    ) {
        /**
         * JEP 513 compact-form canonical constructor with non-{@code null}
         * and non-blank validation for {@code metricName}, and non-{@code null}
         * validation for the three {@link BigDecimal} value fields.
         */
        public RegressionVerdict {
            if (metricName == null || metricName.isBlank()) {
                throw new IllegalArgumentException("metricName must not be null or blank");
            }
            if (baselineValue == null) {
                throw new IllegalArgumentException("baselineValue must not be null");
            }
            if (measuredValue == null) {
                throw new IllegalArgumentException("measuredValue must not be null");
            }
            if (upperBound == null) {
                throw new IllegalArgumentException("upperBound must not be null");
            }
        }
    }
}
