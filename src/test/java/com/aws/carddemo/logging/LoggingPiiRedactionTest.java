/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.logging;

// ---------------------------------------------------------------------------
// Logback API imports — needed because the redaction contract under test
// is a Logback configuration concern (logback-test.xml's CONSOLE appender
// pattern). The test reaches into the running LoggerContext, looks up the
// configured CONSOLE appender, reads its encoder pattern, and applies that
// SAME pattern via a programmatic OutputStreamAppender attached to a
// dedicated test logger. The result is an integration test of the
// production-test config rather than a unit test of duplicated regexes —
// any drift in logback-test.xml is detected automatically.
//
//   * LoggerContext — Logback's root context, accessible via
//     org.slf4j.LoggerFactory.getILoggerFactory() and cast to LoggerContext.
//   * Logger — Logback's classic logger (NOT org.slf4j.Logger), exposed by
//     LoggerContext#getLogger(String) and used to attach/detach the
//     OutputStreamAppender for capture.
//   * ConsoleAppender — the standard System.out-bound appender configured
//     in logback-test.xml; this test reads its encoder pattern to drive
//     the assertions.
//   * OutputStreamAppender — a Logback appender that writes encoded
//     bytes to an arbitrary OutputStream; configured here with a
//     ByteArrayOutputStream so the rendered log line can be inspected
//     by AssertJ.
//   * PatternLayoutEncoder — the encoder that applies the Logback
//     conversion-word pattern (including the %replace chain) to each
//     LoggingEvent before writing bytes to the appender's stream.
//   * ILoggingEvent — Logback's logging-event SPI type; used as the
//     OutputStreamAppender's generic parameter so the encoder/event
//     type bindings align.
//   * Level — Logback's severity-level enum; explicitly set to TRACE
//     on the test logger so every level reaches the appender (the
//     redaction patterns must mask PII at every log level per the
//     "no financial data written to logs at any level" directive).
// ---------------------------------------------------------------------------
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;

// ---------------------------------------------------------------------------
// JUnit 5 + AssertJ imports — JUnit Jupiter only (per AAP §0.10.7), no
// JUnit 4, no PowerMock, no Hamcrest matchers (per AAP §0.10.10).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// SLF4J import for the LoggerFactory entry-point used to acquire the
// running LoggerContext. The cast from org.slf4j.ILoggerFactory to
// ch.qos.logback.classic.LoggerContext is the canonical Logback bootstrap
// pattern documented in the Logback manual chapter on programmatic
// configuration.
// ---------------------------------------------------------------------------
import org.slf4j.LoggerFactory;

// ---------------------------------------------------------------------------
// JDK imports — ByteArrayOutputStream for in-memory capture; UTF-8
// charset constant for deterministic decoding.
// ---------------------------------------------------------------------------
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// ---------------------------------------------------------------------------
// Static AssertJ import — fluent assertions only (AAP §0.10.10).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the PCI / PII redaction patterns configured in
 * {@code src/test/resources/logback-test.xml} actually mask financial and
 * authentication data when the standard Logback rendering pipeline emits a
 * log event.
 *
 * <h2>What the user / AAP requires</h2>
 *
 * <p>AAP §0.10.5 (Security Constraints, NON-NEGOTIABLE) and the user's
 * prompt directive {@code "No financial data written to logs at any level"}
 * impose a defence-in-depth contract:
 *
 * <ol>
 *   <li><strong>Production code MUST NOT log PII.</strong> The migrated
 *       services intentionally avoid {@code log.info("balance={}", amount)}
 *       and {@code log.debug("card={}", pan)} idioms.</li>
 *   <li><strong>If any code accidentally does log PII, the Logback
 *       appender MUST mask the substring before emission.</strong> The
 *       five-stage %replace chain in {@code logback-test.xml}'s CONSOLE
 *       appender is the safety net that catches such regressions.</li>
 *   <li><strong>This test class enforces the safety net.</strong> It
 *       drives synthetic PII messages through the rendering pipeline and
 *       asserts that every PII shape (BCrypt hashes, card numbers, CVVs,
 *       account IDs, balances/amounts) is masked.</li>
 * </ol>
 *
 * <h2>How the test works (defence-in-depth integration)</h2>
 *
 * <p>The test does NOT duplicate the regex patterns from
 * {@code logback-test.xml}. Instead, it reaches into the running
 * {@link LoggerContext}, locates the {@code CONSOLE} appender configured
 * by Logback at bootstrap, reads its encoder pattern (which contains the
 * nested %replace pipeline), and applies that same pattern to a
 * test-managed {@link OutputStreamAppender} backed by a
 * {@link ByteArrayOutputStream}. Synthetic log events are then emitted via
 * a dedicated test logger and the captured bytes are asserted against the
 * expected redaction outcome.
 *
 * <p>This approach has two important properties:
 * <ul>
 *   <li><strong>It tests the production-test config verbatim.</strong>
 *       Any drift in {@code logback-test.xml}'s pattern (someone deletes
 *       a %replace, mistypes a regex, changes a mask token) fails this
 *       test rather than silently weakening redaction.</li>
 *   <li><strong>It tests the actual Logback rendering pipeline.</strong>
 *       Regex unit tests in isolation cannot catch issues with Logback's
 *       conversion-word parsing, %replace nesting order, or pattern
 *       layout escape semantics. Running the real
 *       {@link PatternLayoutEncoder} is the only way to verify the
 *       contract holds end-to-end.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This test calls the production-test Logback config (the
 * {@code CONSOLE} appender's encoder pattern) and the production Logback
 * library (the actual {@link PatternLayoutEncoder} + the actual
 * {@link OutputStreamAppender} as configured by Spring Boot's
 * {@code LogbackLoggingSystem}). It does not reimplement any of the
 * redaction logic in test bodies; all masking is performed by the real
 * Logback rendering pipeline.
 *
 * <h2>Coverage scope</h2>
 *
 * <p>The {@code logback-test.xml} CONSOLE pattern chains five %replace
 * conversions (innermost to outermost):
 * <ol>
 *   <li>BCrypt hashes (60-char {@code $2[abxy]$NN$...} literal)</li>
 *   <li>Card numbers (13–19 consecutive digits, PAN range)</li>
 *   <li>CVV / CVC codes (3–4 digits adjacent to a {@code cvv} /
 *       {@code cvc} / {@code csc} / {@code cid} label)</li>
 *   <li>Account numbers (exactly 11 consecutive digits, CardDemo
 *       CVACT01Y.cpy width)</li>
 *   <li>Balances / amounts (label {@code balance} or {@code amount}
 *       followed by a signed scale-2 decimal)</li>
 * </ol>
 *
 * <p>One dedicated test method covers each pattern; an additional
 * "everything at once" test covers the realistic case where a single
 * log line contains multiple PII shapes. A final "configuration
 * present" test asserts that the CONSOLE appender is actually wired
 * with a %replace pattern (catches the regression where a future
 * agent simplifies the config without realising it disables redaction).
 *
 * @see <a href="file:src/test/resources/logback-test.xml">logback-test.xml</a>
 */
@DisplayName("Logback PII Redaction — logback-test.xml CONSOLE appender contract (AAP §0.10.5)")
final class LoggingPiiRedactionTest {

    /**
     * Logback {@link LoggerContext} acquired robustly at class-load time.
     *
     * <p>SLF4J 2.x binds to its provider (Logback in this project) lazily
     * on the first call to {@link LoggerFactory#getILoggerFactory()}. The
     * binding is synchronized internally, but during the window when one
     * thread is performing initialization, parallel callers see SLF4J's
     * bootstrap {@code SubstituteLoggerFactory} instead of the real
     * {@link LoggerContext}. Under JUnit 5 class-level parallel execution
     * (configured in {@code junit-platform.properties}), multiple test
     * classes can race for this binding when the suite first starts.
     *
     * <p>The {@link #resolveLoggerContext()} helper handles the race by:
     * <ol>
     *   <li>Calling {@link LoggerFactory#getLogger(String)} to nudge SLF4J
     *       into starting its provider lookup if it has not begun yet.</li>
     *   <li>Polling {@link LoggerFactory#getILoggerFactory()} up to 500 ms
     *       (50 attempts × 10 ms) for the binding to resolve to a real
     *       {@link LoggerContext}.</li>
     *   <li>Failing fast with a clear diagnostic if the binding never
     *       resolves — that would indicate a classpath problem (e.g.,
     *       missing {@code ch.qos.logback:logback-classic}).</li>
     * </ol>
     *
     * <p>The result is cached in this {@code static final} field so the
     * per-test {@code @BeforeEach} can use it directly without re-running
     * the resolution.
     */
    private static final LoggerContext LOGBACK_CONTEXT = resolveLoggerContext();

    /**
     * Robustly resolves the SLF4J binding to Logback's {@link LoggerContext}.
     * See {@link #LOGBACK_CONTEXT} for the rationale.
     *
     * @return the {@link LoggerContext} bound by SLF4J
     * @throws IllegalStateException when the binding does not resolve to
     *                               Logback within 500 ms — indicates a
     *                               classpath problem in the test runtime
     */
    private static LoggerContext resolveLoggerContext() {
        // Nudge SLF4J into starting its provider lookup if it has not yet.
        LoggerFactory.getLogger("com.aws.carddemo.logging.PII_REDACTION_INIT");

        // Poll for binding to resolve. Each attempt sleeps 10 ms; 50
        // attempts give a 500 ms upper bound. In normal operation the
        // binding resolves on the first attempt (post-nudge).
        for (int attempt = 0; attempt < 50; attempt++) {
            org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext loggerContext) {
                return loggerContext;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for SLF4J → Logback binding", ex);
            }
        }
        throw new IllegalStateException(
                "SLF4J binding did not resolve to ch.qos.logback.classic.LoggerContext "
                        + "within 500 ms. Verify that ch.qos.logback:logback-classic is on "
                        + "the test classpath and that no other SLF4J provider is shadowing "
                        + "it. Current factory class: "
                        + LoggerFactory.getILoggerFactory().getClass().getName());
    }

    /**
     * Logger name used for the test capture. Distinct from any production
     * package so the test logger does not accidentally inherit additional
     * appenders from the {@code com.aws.carddemo.*} hierarchy. The logger
     * is created/cached by the {@link LoggerContext} at first
     * {@link LoggerContext#getLogger(String)} call.
     */
    private static final String TEST_LOGGER_NAME = "com.aws.carddemo.logging.PII_REDACTION_TEST_CAPTURE";

    /**
     * Logback appender name registered by this test. Distinct from
     * "CONSOLE" (the production-test appender) so the test capture and
     * the production CONSOLE output coexist without interference.
     */
    private static final String CAPTURE_APPENDER_NAME = "PII_REDACTION_TEST_CAPTURE_APPENDER";

    /** Captured rendered output for the current test method. */
    private ByteArrayOutputStream captured;

    /** Test-managed appender attached for the current test method. */
    private OutputStreamAppender<ILoggingEvent> captureAppender;

    /** Test-managed logger used to emit synthetic PII for the current test. */
    private Logger testLogger;

    /**
     * Configures a fresh capture pipeline before each test. Reads the
     * production CONSOLE appender's encoder pattern from the running
     * LoggerContext (so the test is sensitive to drift in
     * {@code logback-test.xml}), creates an {@link OutputStreamAppender}
     * with a {@link PatternLayoutEncoder} that uses the SAME pattern, and
     * attaches the appender to the dedicated test logger.
     *
     * <p>The capture buffer ({@link ByteArrayOutputStream}) is recreated
     * per test to ensure full isolation across @Test methods.
     */
    @BeforeEach
    void attachCaptureAppender() {
        // Use the class-load-time-resolved LoggerContext to avoid the
        // SLF4J parallel-binding race documented on LOGBACK_CONTEXT.
        LoggerContext context = LOGBACK_CONTEXT;

        // Read the production CONSOLE appender's encoder pattern. The
        // ROOT logger owns the CONSOLE appender per logback-test.xml's
        // <root> declaration.
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> consoleAppender =
                (ConsoleAppender<ILoggingEvent>) rootLogger.getAppender("CONSOLE");
        assertThat(consoleAppender)
                .as("logback-test.xml must define a CONSOLE appender on the root logger; "
                        + "without it the PII redaction patterns are not wired in")
                .isNotNull();
        PatternLayoutEncoder consoleEncoder =
                (PatternLayoutEncoder) consoleAppender.getEncoder();
        String patternFromProductionTestConfig = consoleEncoder.getPattern();

        // Build the in-memory capture pipeline using the SAME pattern so
        // any drift in the CONSOLE pattern fails this test.
        captured = new ByteArrayOutputStream();
        PatternLayoutEncoder captureEncoder = new PatternLayoutEncoder();
        captureEncoder.setContext(context);
        captureEncoder.setPattern(patternFromProductionTestConfig);
        captureEncoder.setCharset(StandardCharsets.UTF_8);
        captureEncoder.start();

        captureAppender = new OutputStreamAppender<>();
        captureAppender.setContext(context);
        captureAppender.setName(CAPTURE_APPENDER_NAME);
        captureAppender.setEncoder(captureEncoder);
        captureAppender.setOutputStream(captured);
        captureAppender.setImmediateFlush(true);
        captureAppender.start();

        testLogger = context.getLogger(TEST_LOGGER_NAME);
        // TRACE so every level reaches the appender — the redaction
        // patterns must mask PII at every level (the user directive is
        // "no financial data written to logs at any level").
        testLogger.setLevel(Level.TRACE);
        // additivity=false prevents the test logger from also writing to
        // the production CONSOLE appender on stdout; this keeps test
        // output clean and isolates the capture stream from any other
        // appender interference.
        testLogger.setAdditive(false);
        testLogger.addAppender(captureAppender);
    }

    /**
     * Tears down the capture pipeline after each test so the next test
     * starts from a clean state. The test logger is restored to defaults
     * (additivity on, no extra appenders) so a misbehaving test cannot
     * leak appenders into sibling test classes.
     */
    @AfterEach
    void detachCaptureAppender() {
        if (testLogger != null && captureAppender != null) {
            testLogger.detachAppender(captureAppender);
        }
        if (captureAppender != null) {
            captureAppender.stop();
        }
        if (testLogger != null) {
            // Reset to defaults so the next test (or sibling test class)
            // sees a clean logger.
            testLogger.setAdditive(true);
            testLogger.setLevel(null);
        }
    }

    // =====================================================================
    // Per-pattern redaction tests
    // =====================================================================

    /**
     * Verifies BCrypt password hashes are masked. The 60-character
     * {@code $2b$10$<22-salt><31-hash>} literal is the most security-
     * sensitive PII in CardDemo (a leaked hash enables offline brute
     * force); the redaction pattern runs first in the %replace chain so
     * its body characters (some of which are digits) cannot mis-match
     * subsequent patterns.
     */
    @Test
    @DisplayName("BCrypt hash $2b$10$… is masked to ****BCRYPT-HASH-MASKED****")
    void redacts_bcryptHash() {
        // 60-char BCrypt literal: prefix=$2b$10$ (7 chars), salt=22 chars,
        // hash=31 chars (total body=53 chars). All body chars are from the
        // Base64-OpenBSD alphabet [A-Za-z0-9./].
        String bcryptHash = "$2b$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQ";
        assertThat(bcryptHash).hasSize(60);

        testLogger.info("authentication success: hash={}", bcryptHash);
        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("BCrypt hash must be masked; captured output: %s", output)
                .doesNotContain(bcryptHash)
                .doesNotContain("$2b$10")
                .contains("****BCRYPT-HASH-MASKED****");
    }

    /**
     * Verifies card numbers (PANs) are masked across the supported card
     * brand widths: 13 (Visa legacy), 15 (Amex), 16 (Visa/Mastercard/
     * Discover), 19 (Maestro). The 12-digit lower bound was deliberately
     * lifted to 13 in {@code logback-test.xml} to avoid mis-masking
     * 12-digit timestamps or counts.
     */
    @Test
    @DisplayName("Card numbers (13–19 digits, all PAN widths) are masked to ****CARD-MASKED****")
    void redacts_cardNumberAllWidths() {
        // 16-digit Visa-style PAN.
        testLogger.info("transaction posted: card=4111111111111111 result=approved");
        // 15-digit Amex-style PAN.
        testLogger.info("amex card 378282246310005 used");
        // 19-digit Maestro-style PAN.
        testLogger.info("maestro card 6759649826438453123 processed");

        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("Card numbers must be masked at every PAN width; captured: %s", output)
                .doesNotContain("4111111111111111")
                .doesNotContain("378282246310005")
                .doesNotContain("6759649826438453123")
                .contains("****CARD-MASKED****");
    }

    /**
     * Verifies CVV / CVC / CSC / CID codes are masked when adjacent to a
     * label. The regex's label anchor avoids mis-masking unrelated 3-or-4
     * digit subsequences (response codes, year suffixes) while catching
     * every CVV log call that follows the project's "label=value"
     * convention.
     */
    @Test
    @DisplayName("CVV/CVC codes adjacent to a label are masked to ****CVV-MASKED****")
    void redacts_cvvCodes() {
        testLogger.info("card detail: cvv=123 status=ok");
        testLogger.info("verification: CVC2 4567 captured");
        testLogger.info("amex code: cid:1234 received");

        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("CVV-labelled codes must be masked; captured: %s", output)
                .doesNotContain("cvv=123")
                .doesNotContain("CVC2 4567")
                .doesNotContain("cid:1234")
                .contains("****CVV-MASKED****");
    }

    /**
     * Verifies 11-digit account IDs are masked. CardDemo's
     * {@code CVACT01Y.cpy} account-id width is exactly 11 digits; fixture
     * IDs run from {@code 00000000010} through {@code 00000000060}.
     */
    @Test
    @DisplayName("11-digit account IDs are masked to ****ACCT-MASKED****")
    void redacts_accountId() {
        testLogger.info("processing account 00000000010 status=active");
        testLogger.warn("rejection on account 12345678901 reason=insufficient-credit");

        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("11-digit account IDs must be masked; captured: %s", output)
                .doesNotContain("00000000010")
                .doesNotContain("12345678901")
                .contains("****ACCT-MASKED****");
    }

    /**
     * Verifies labelled monetary amounts at PIC V99 scale=2 are masked.
     * The label anchor ({@code balance} or {@code amount}, case-
     * insensitive) avoids mis-masking response times, page sizes, or
     * coverage percentages while catching every business-amount log
     * call.
     */
    @Test
    @DisplayName("Labelled balance/amount values (scale-2) are masked to label=****AMT-MASKED****")
    void redacts_balanceAndAmount() {
        testLogger.info("interest calculated: balance=1250.50 rate=18.00");
        testLogger.info("posting: amount=-100.25 fee=0.00");
        testLogger.warn("over-limit: Balance: 9999.99 limit=5000.00");

        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("Labelled monetary amounts must be masked; captured: %s", output)
                .doesNotContain("1250.50")
                .doesNotContain("-100.25")
                .doesNotContain("9999.99")
                .contains("****AMT-MASKED****");
    }

    /**
     * Verifies that a single log line carrying multiple PII shapes has
     * EVERY shape masked. This catches the realistic regression where a
     * service accidentally logs a complete transaction record at DEBUG
     * level — every secret in that record must be masked.
     */
    @Test
    @DisplayName("Mixed-PII log line — every PII shape in the same message is masked")
    void redacts_mixedPiiInSingleLine() {
        // One pathological line: account + card + cvv + balance + hash.
        testLogger.info(
                "AUDIT: account=00000000010 card=4111111111111111 cvv=123 "
                        + "balance=1234.56 hash=$2b$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQ");
        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("Every PII shape in a mixed-PII line must be masked; captured: %s", output)
                .doesNotContain("00000000010")
                .doesNotContain("4111111111111111")
                .doesNotContain("cvv=123")
                .doesNotContain("1234.56")
                .doesNotContain("$2b$10")
                .contains("****ACCT-MASKED****")
                .contains("****CARD-MASKED****")
                .contains("****CVV-MASKED****")
                .contains("****AMT-MASKED****")
                .contains("****BCRYPT-HASH-MASKED****");
    }

    /**
     * Verifies that redaction fires at EVERY log level (TRACE, DEBUG,
     * INFO, WARN, ERROR). The user directive "no financial data written
     * to logs at any level" requires this; %replace runs in the encoder
     * which is invoked after level filtering, so the redaction applies
     * to every emitted event regardless of its level.
     */
    @Test
    @DisplayName("Redaction applies at every log level — TRACE through ERROR")
    void redacts_atEveryLogLevel() {
        testLogger.trace("trace account=00000000011");
        testLogger.debug("debug account=00000000022");
        testLogger.info("info account=00000000033");
        testLogger.warn("warn account=00000000044");
        testLogger.error("error account=00000000055");

        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("Account IDs must be masked at every level; captured: %s", output)
                .doesNotContain("00000000011")
                .doesNotContain("00000000022")
                .doesNotContain("00000000033")
                .doesNotContain("00000000044")
                .doesNotContain("00000000055");
        // The mask token should appear once per logged statement (5 in total).
        long maskOccurrences = output.lines().filter(l -> l.contains("****ACCT-MASKED****")).count();
        assertThat(maskOccurrences)
                .as("Expected the account-mask token on every level's emitted line")
                .isEqualTo(5L);
    }

    /**
     * Defensive: verifies that benign text NOT matching any redaction
     * pattern passes through unchanged. This proves the patterns are not
     * over-broad (e.g., that a 4-digit year does not get mis-masked as
     * a CVV when no CVV label is adjacent).
     */
    @Test
    @DisplayName("Benign text (no PII shape) passes through unchanged")
    void redacts_doesNotMaskBenignText() {
        testLogger.info("processed 7 records in 250 ms with cache hit ratio 0.85");
        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(output)
                .as("Benign text must not be masked; captured: %s", output)
                .contains("processed 7 records in 250 ms with cache hit ratio 0.85")
                .doesNotContain("****");
    }

    // =====================================================================
    // Configuration-presence assertion
    // =====================================================================

    /**
     * Defensive: verifies that the production CONSOLE appender's encoder
     * pattern actually contains the %replace conversion words. Without
     * this assertion, a future agent could silently weaken the redaction
     * by simplifying the pattern; the per-pattern tests above would still
     * pass (because the test appender reads the same pattern), masking
     * the regression.
     *
     * <p>This test is the canary: it asserts that the
     * {@code logback-test.xml} CONSOLE pattern contains FIVE %replace
     * conversions (one per PII shape) and all five mask tokens. If
     * someone deletes a %replace, this test fails.
     */
    @Test
    @DisplayName("Configuration canary — logback-test.xml CONSOLE pattern carries five %replace pipelines")
    void consolePatternContainsAllFiveReplacePipelines() {
        // Use the class-load-time-resolved LoggerContext (see LOGBACK_CONTEXT).
        LoggerContext context = LOGBACK_CONTEXT;
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> consoleAppender =
                (ConsoleAppender<ILoggingEvent>) rootLogger.getAppender("CONSOLE");
        PatternLayoutEncoder consoleEncoder =
                (PatternLayoutEncoder) consoleAppender.getEncoder();
        String pattern = consoleEncoder.getPattern();

        // Count distinct %replace conversion-word occurrences.
        long replaceCount = pattern.split("%replace", -1).length - 1;
        assertThat(replaceCount)
                .as("logback-test.xml CONSOLE pattern must chain five %%replace conversions; "
                        + "pattern as configured: %s", pattern)
                .isEqualTo(5L);

        // Assert every mask token is present (catches the regression
        // where someone keeps %replace but changes the mask string to
        // something diagnostic-unfriendly like "***" or "REDACTED").
        assertThat(pattern)
                .as("logback-test.xml CONSOLE pattern must carry every mask token; "
                        + "pattern: %s", pattern)
                .contains("****BCRYPT-HASH-MASKED****")
                .contains("****CARD-MASKED****")
                .contains("****CVV-MASKED****")
                .contains("****ACCT-MASKED****")
                .contains("****AMT-MASKED****");
    }
}
