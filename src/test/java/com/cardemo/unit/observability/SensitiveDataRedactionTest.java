/*
 * ******************************************************************
 * Program     : SensitiveDataRedactionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (log hygiene and dependency hygiene)
 * Function    : Proves that no balance, credit limit, transaction amount,
 *               category balance or merchant identifier reaches a shared
 *               log, at each of the four emission sites the review named
 *               and in the logging configuration behind them; that the
 *               preserved COBOL DISPLAY reproductions are NOT over-redacted;
 *               that no source comment still asserts the absence of a
 *               configuration file that now exists; and that the two
 *               duplicated transitive artefacts are gone from the graph.
 * Source      : app/cpy/CVACT01Y.cpy:L7-L14 (five PIC S9(10)V99 money
 *               fields), app/cpy/CVTRA05Y.cpy:L10-L14 (TRAN-AMT and the
 *               merchant fields), app/cpy/CVTRA01Y.cpy:L9 (TRAN-CAT-BAL),
 *               app/cbl/CBTRN03C.cbl:L180 and :L198-L199 (DISPLAY
 *               TRAN-RECORD, and the two scalar DISPLAYs that stay
 *               verbatim), app/cbl/CBACT04C.cbl:L193 (DISPLAY
 *               TRAN-CAT-BAL-RECORD), app/cbl/COTRN01C.cbl:49 (the edited
 *               amount mask) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionDetailService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Pins the resolution of the Medium-severity sensitive-data-logging finding, the Low-severity stale
 * documentation finding and the Low-severity duplicate-transitive finding.
 *
 * <p>Two things are asserted in tandem throughout, because either alone would be misleading. The financial
 * value must be absent from the log, and the surrounding diagnostic must still be present: a redaction that
 * removed the field names, the ordering or the record geometry would have destroyed the only thing these
 * emissions exist for. The tests therefore assert both the absence of the value and the survival of its
 * context.
 */
@DisplayName("Sensitive data never reaches a shared log")
class SensitiveDataRedactionTest {

    /** A distinctive balance whose digits must not appear anywhere in log output. */
    private static final BigDecimal SECRET_BALANCE = new BigDecimal("867530.91");

    /** A distinctive credit limit, chosen so a partial leak is still detectable. */
    private static final BigDecimal SECRET_LIMIT = new BigDecimal("4815162.34");

    /** A distinctive transaction amount. */
    private static final BigDecimal SECRET_AMOUNT = new BigDecimal("271828.18");

    /** A distinctive merchant identifier. */
    private static final long SECRET_MERCHANT_ID = 314159265L;

    /** Sixteen digits standing in for {@code TRAN-CARD-NUM PIC X(16)}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Twenty-six characters standing in for a {@code PIC X(26)} timestamp inside the reporting period. */
    private static final String TIMESTAMP = "2026-01-15-10.30.45.123456";

    /** Every appender attached during a test, detached again afterwards. */
    private final List<Runnable> detachers = new ArrayList<>();

    /**
     * Attaches an in-memory appender to one class's logger at {@code TRACE}, so that a debug-guarded
     * emission actually fires and can be inspected.
     *
     * @param type the class whose logger to capture
     * @return the appender holding the captured events
     */
    private ListAppender<ILoggingEvent> capture(final Class<?> type) {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        attach(appender, (Logger) LoggerFactory.getLogger(type));
        return appender;
    }

    /**
     * Attaches one in-memory appender to a class's logger <em>and</em> to the per-program parity logger that
     * class emits its record reproductions on, so that both streams land in one ordered list.
     *
     * <p>Both are required, and attaching only the first is a real trap: a per-program parity logger such as
     * {@code com.cardemo.parity.CBTRN03C} is not a descendant of the class logger, so a level or an appender
     * set on {@code com.cardemo.batch.processors.TransactionReportProcessor} reaches nothing emitted through
     * it. The record-level reproductions this class asserts on - the 350-byte {@code TRAN-RECORD} line, the
     * {@code TRAN-CAT-BAL-RECORD} line and the two closing scalar {@code DISPLAY}s - all travel that channel
     * precisely because every shipped profile pins it to {@code OFF}, which is the routing half of the
     * redaction contract under test.
     *
     * @param type the class whose logger to capture
     * @param parityLoggerName the per-program parity logger that class declares
     * @return the appender holding the captured events from both loggers, in emission order
     */
    private ListAppender<ILoggingEvent> capture(final Class<?> type, final String parityLoggerName) {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        attach(appender, (Logger) LoggerFactory.getLogger(type));
        attach(appender, (Logger) LoggerFactory.getLogger(parityLoggerName));
        return appender;
    }

    /**
     * Raises one logger to {@code TRACE}, attaches the appender to it and registers the restore.
     *
     * @param appender the appender to attach, already started
     * @param logger the logger to raise and attach to
     */
    private void attach(final ListAppender<ILoggingEvent> appender, final Logger logger) {
        final Level restore = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        detachers.add(() -> {
            logger.detachAppender(appender);
            logger.setLevel(restore);
        });
    }

    /**
     * Restores every logger this test touched, so one test cannot influence another.
     *
     * <p>An appender attached to two loggers is detached from both here. It is not stopped: a
     * {@code ListAppender} holds no resource that needs releasing, and stopping it once per attachment - which
     * is what a per-logger restore would do - would be the only alternative.
     */
    @AfterEach
    void detachAppenders() {
        detachers.forEach(Runnable::run);
        detachers.clear();
    }

    /**
     * Renders every captured event as one searchable string: the formatted message and every argument.
     *
     * @param appender the appender holding the events
     * @return the concatenated output, never {@code null}
     */
    private static String textOf(final ListAppender<ILoggingEvent> appender) {
        final StringBuilder text = new StringBuilder(1024);
        for (final ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
        }
        return text.toString();
    }

    /**
     * Every textual form a decimal might leak in: the plain string, the unscaled digits, and the integer
     * part alone. Asserting on all three closes the gap a single {@code toPlainString} check would leave.
     *
     * @param value the value that must not appear
     * @return the forms to search for
     */
    private static List<String> leakForms(final BigDecimal value) {
        return List.of(
                value.toPlainString(),
                value.unscaledValue().toString(),
                value.toBigInteger().toString());
    }

    /** The four Java emission sites the review named. */
    @Nested
    @DisplayName("The four emission sites")
    class EmissionSites {

        @Test
        @DisplayName("AccountReader renders every money field as a fixed-width placeholder")
        void accountReaderRedactsEveryMoneyField() throws Exception {
            final Account account = new Account(11111111111L, "Y",
                    SECRET_BALANCE, SECRET_LIMIT, new BigDecimal("1020.00"),
                    "2020-01-01", "2030-01-01", "2025-01-01",
                    new BigDecimal("100.00"), new BigDecimal("-50.00"),
                    "12345", "DEFAULT   ");

            final Method render =
                    AccountReader.class.getDeclaredMethod("renderRedactedAccountRecord", Account.class);
            render.setAccessible(true);
            final String image = (String) render.invoke(null, account);

            assertThat(image)
                    .as("the 300-byte geometry of app/cpy/CVACT01Y.cpy must survive the redaction")
                    .hasSize(300);
            for (final BigDecimal secret : List.of(SECRET_BALANCE, SECRET_LIMIT)) {
                for (final String form : leakForms(secret)) {
                    assertThat(image).as("money must not appear as %s", form).doesNotContain(form);
                }
            }
            assertThat(image)
                    .as("five PIC S9(10)V99 fields, each twelve characters, all redacted")
                    .contains("*".repeat(12));
            assertThat(image)
                    .as("the key and the non-financial fields are still readable")
                    .contains("11111111111")
                    .contains("2020-01-01")
                    .contains("12345");
        }

        @Test
        @DisplayName("AccountReader renderRedactedMoney is total: a placeholder for any value, spaces for null")
        void accountReaderRenderMoneyIsTotal() throws Exception {
            final Method renderRedactedMoney =
                    AccountReader.class.getDeclaredMethod("renderRedactedMoney", BigDecimal.class);
            renderRedactedMoney.setAccessible(true);

            assertThat((String) renderRedactedMoney.invoke(null, (Object) null))
                    .as("an absent value keeps its own distinguishable rendering")
                    .isEqualTo(" ".repeat(12));
            for (final String value : List.of("0.00", "-1.00", "9999999999.99", "867530.91")) {
                assertThat((String) renderRedactedMoney.invoke(null, new BigDecimal(value)))
                        .as("every value renders identically, so nothing is inferable from the shape")
                        .isEqualTo("*".repeat(12));
            }
        }

        @Test
        @DisplayName("TransactionReportProcessor redacts TRAN-AMT on the per-record diagnostic")
        void reportProcessorRedactsTheAmount() {
            final CardCrossReferenceRepository xrefs = mock(CardCrossReferenceRepository.class);
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);

            // A present cross-reference: an absent one abends with INVALID CARD NUMBER at
            // app/cbl/CBTRN03C.cbl:L211, which is correct behaviour but would cut the record short before
            // the rest of the paragraph runs.
            when(xrefs.findById(CARD_NUMBER)).thenReturn(Optional.of(
                    new CardCrossReference(CARD_NUMBER, 123456789L, 11111111111L)));
            // The reference tables are loaded once per step execution rather than read per record, so the
            // stubs are on findAll(). See TransactionReportProcessor.transactionTypes().
            when(types.findAll()).thenReturn(List.of(new TransactionType("01", "PURCHASE")));
            when(categories.findAll()).thenReturn(List.of(new TransactionCategory(
                    new TransactionCategoryId("01", 5), "RETAIL")));

            final TransactionReportProcessor processor = new TransactionReportProcessor(
                    mock(TransactionRepository.class),
                    xrefs, types, categories, new FileStatusMapper(), "2026-01-01", "2026-12-31");

            final ListAppender<ILoggingEvent> events =
                    capture(TransactionReportProcessor.class, "com.cardemo.parity.CBTRN03C");
            processor.process(new Transaction("0000000000000001", "01", 5, "POS       ",
                    "COFFEE", SECRET_AMOUNT, SECRET_MERCHANT_ID, "ACME", "SEATTLE", "12345-0001",
                    CARD_NUMBER, TIMESTAMP, TIMESTAMP));

            final String text = textOf(events);
            assertThat(text).as("the diagnostic must have fired").contains("TRAN-RECORD");
            for (final String form : leakForms(SECRET_AMOUNT)) {
                assertThat(text).as("TRAN-AMT must not appear as %s", form).doesNotContain(form);
            }
            assertThat(text)
                    .as("eleven digit positions of PIC S9(09)V99, all redacted")
                    .contains("amount=" + "*".repeat(11));
            assertThat(text)
                    .as("the record is still identifiable and the card is still masked")
                    .contains("id=0000000000000001")
                    .doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("InterestCalculationProcessor redacts TRAN-CAT-BAL but keeps all three key fields")
        void interestProcessorRedactsTheCategoryBalance() {
            final InterestCalculationProcessor processor = new InterestCalculationProcessor(
                    mock(DisclosureGroupRepository.class),
                    mock(AccountRepository.class),
                    mock(CardCrossReferenceRepository.class),
                    new FileStatusMapper(),
                    "2026010100",
                    Clock.fixed(Instant.parse("2026-01-15T10:30:45Z"), ZoneOffset.UTC));

            final ListAppender<ILoggingEvent> events =
                    capture(InterestCalculationProcessor.class, "com.cardemo.parity.CBACT04C");
            try {
                processor.process(new TransactionCategoryBalance(
                        new TransactionCategoryBalanceId(11111111111L, "01", 5), SECRET_BALANCE));
            } catch (final RuntimeException expected) {
                // The unstubbed account read abends further down the paragraph chain. The diagnostic under
                // test is emitted at app/cbl/CBACT04C.cbl:L193, before that point, so it has already fired.
                assertThat(expected).isNotNull();
            }

            final String text = textOf(events);
            assertThat(text).as("the diagnostic must have fired").contains("TRAN-CAT-BAL-RECORD");
            for (final String form : leakForms(SECRET_BALANCE)) {
                assertThat(text).as("the balance must not appear as %s", form).doesNotContain(form);
            }
            assertThat(text).contains("TRAN-CAT-BAL=" + "*".repeat(11));
            assertThat(text)
                    .as("the key identifies the row, which is what this diagnostic is for")
                    .contains("TRANCAT-ACCT-ID=11111111111")
                    .contains("TRANCAT-TYPE-CD=01")
                    .contains("TRANCAT-CD=5");
        }

        @Test
        @DisplayName("TransactionDetailService redacts the amount and the merchant identifier")
        void detailServiceRedactsAmountAndMerchant() {
            final TransactionRepository transactions = mock(TransactionRepository.class);
            final String transactionId = "0000000000000001";
            when(transactions.findById(transactionId)).thenReturn(Optional.of(
                    new Transaction(transactionId, "01", 5, "POS       ", "COFFEE",
                            SECRET_AMOUNT, SECRET_MERCHANT_ID, "ACME", "SEATTLE", "12345-0001",
                            CARD_NUMBER, TIMESTAMP, TIMESTAMP)));

            final TransactionDetailService service = new TransactionDetailService(
                    transactions, new FileStatusMapper(), mock(EntityManager.class),
                    Clock.fixed(Instant.parse("2026-01-15T10:30:45Z"), ZoneOffset.UTC));

            final ListAppender<ILoggingEvent> events = capture(TransactionDetailService.class);
            service.viewTransaction(transactionId);

            final String text = textOf(events);
            assertThat(text).as("the diagnostic must have fired").contains("CT01 detail populated");
            for (final String form : leakForms(SECRET_AMOUNT)) {
                assertThat(text).as("the amount must not appear as %s", form).doesNotContain(form);
            }
            assertThat(text)
                    .as("the merchant identifier must not appear in any form")
                    .doesNotContain(String.valueOf(SECRET_MERCHANT_ID))
                    .doesNotContain("00" + SECRET_MERCHANT_ID);
            assertThat(text)
                    .as("twelve characters of PIC +99999999.99 and nine of PIC 9(09), both redacted")
                    .contains("amount=" + "*".repeat(12))
                    .contains("merchantId=" + "*".repeat(9));
            assertThat(text)
                    .as("the record is still identifiable and the card number is still masked")
                    .contains("transactionId=" + transactionId)
                    .doesNotContain(CARD_NUMBER);
        }
    }

    /**
     * The preserved COBOL {@code DISPLAY} reproductions must survive untouched. Redacting these would trade
     * a documented output-equivalence guarantee for the removal of an unlinkable aggregate.
     */
    @Nested
    @DisplayName("Preserved DISPLAY reproductions are not over-redacted")
    class PreservedDisplays {

        @Test
        @DisplayName("the two closing scalar DISPLAYs of CBTRN03C:L198-L199 keep their exact text")
        void theClosingDisplaysAreVerbatim() {
            final TransactionReportProcessor processor = new TransactionReportProcessor(
                    mock(TransactionRepository.class),
                    mock(CardCrossReferenceRepository.class),
                    mock(TransactionTypeRepository.class),
                    mock(TransactionCategoryRepository.class),
                    new FileStatusMapper(), "2026-01-01", "2026-12-31");

            final ListAppender<ILoggingEvent> events =
                    capture(TransactionReportProcessor.class, "com.cardemo.parity.CBTRN03C");
            processor.finishReport();

            final String text = textOf(events);
            // The literal at :L198 carries its own trailing space; the one at :L199 carries none. Both are
            // parity text, and neither line bears an identifier, so neither is linkable to a cardholder.
            assertThat(text).contains("TRAN-AMT 0");
            assertThat(text).contains("WS-PAGE-TOTAL0");
        }
    }

    /** The logging configuration's own contribution, and the boundary it must not cross. */
    @Nested
    @DisplayName("logback-spring.xml masks financial field names")
    class LoggingConfiguration {

        /** The committed logging configuration. */
        private static final Path CONFIG =
                Path.of("src", "main", "resources", "logback-spring.xml");

        /**
         * Collects every field name listed in the configuration's field-name masking layer.
         *
         * @return the declared paths, lower-cased for comparison
         * @throws IOException if the configuration cannot be read
         */
        private static Set<String> declaredPaths() throws IOException {
            final String xml = Files.readString(CONFIG, StandardCharsets.UTF_8);
            final Matcher matcher = Pattern.compile("<paths>([^<]*)</paths>").matcher(xml);
            final Set<String> names = new LinkedHashSet<>();
            while (matcher.find()) {
                Stream.of(matcher.group(1).split(","))
                        .map(String::strip)
                        .filter(name -> !name.isEmpty())
                        .forEach(name -> names.add(name.toLowerCase(java.util.Locale.ROOT)));
            }
            return names;
        }

        @ParameterizedTest(name = "{0} is a masked field name")
        @ValueSource(strings = {
            "currentbalance", "creditlimit", "cashcreditlimit", "currentcyclecredit",
            "currentcycledebit", "acct-curr-bal", "acct-credit-limit", "amount", "amountvalue",
            "tran-amt", "dalytran-amt", "balance", "tran-cat-bal", "interestrate", "dis-int-rate",
            "merchantid", "merchantname", "merchantcity", "merchantzip", "tran-merchant-id",
        })
        @DisplayName("every financial and merchant field the copybooks declare is masked by name")
        void financialFieldNamesAreMasked(final String name) throws IOException {
            assertThat(declaredPaths()).contains(name);
        }

        @Test
        @DisplayName("the account identifier is deliberately not masked")
        void theAccountIdentifierIsNotMasked() throws IOException {
            assertThat(declaredPaths())
                    .as("a pseudonymous key that the report deliverable carries anyway, and the "
                            + "only field that makes these diagnostics reconcilable")
                    .doesNotContain("accountid")
                    .doesNotContain("acct-id")
                    .doesNotContain("acctid");
        }

        @Test
        @DisplayName("no paths list carries a space, because the delimiter parser does not trim")
        void noPathsListCarriesASpace() throws IOException {
            final String xml = Files.readString(CONFIG, StandardCharsets.UTF_8);
            final Matcher matcher = Pattern.compile("<paths>([^<]*)</paths>").matcher(xml);
            int lists = 0;
            while (matcher.find()) {
                lists++;
                assertThat(matcher.group(1))
                        .as("a space would silently become part of a field name")
                        .doesNotContain(" ");
            }
            assertThat(lists).as("the field-name layer must not have been emptied").isGreaterThan(20);
        }

        @Test
        @DisplayName("the value layer still carries no rule that could match a bare decimal")
        void theValueLayerDoesNotMatchABareDecimal() throws IOException {
            final String xml = Files.readString(CONFIG, StandardCharsets.UTF_8);
            final Matcher matcher = Pattern.compile("<value><!\\[CDATA\\[(.*?)]]></value>")
                    .matcher(xml);
            final List<String> patterns = new ArrayList<>();
            while (matcher.find()) {
                patterns.add(matcher.group(1));
            }
            assertThat(patterns).as("the value layer must still exist").isNotEmpty();

            // The six benign look-alikes the configuration documents. If any value rule matched one of
            // these, a preserved DISPLAY reproduction would be silently rewritten.
            final List<String> benign = List.of(
                    "TRANSACTIONS PROCESSED : 300",
                    "TRANSACTIONS REJECTED  : 12",
                    "accountId=11111111111 TRAN-AMT 867530.91",
                    "0000000000000001",
                    "0023",
                    "TRAN-AMT 0");
            for (final String pattern : patterns) {
                final Pattern compiled = Pattern.compile(pattern);
                for (final String probe : benign) {
                    assertThat(compiled.matcher(probe).find())
                            .as("value rule %s must not match the benign line %s", pattern, probe)
                            .isFalse();
                }
            }
        }
    }

    /** No source comment may assert the absence of a file that exists. */
    @Nested
    @DisplayName("No stale absence claim survives")
    class StaleAbsenceClaims {

        /**
         * Walks every Java source file under the main tree.
         *
         * @return the file paths
         * @throws IOException if the tree cannot be walked
         */
        private static List<Path> mainSources() throws IOException {
            try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
                return files.filter(path -> path.toString().endsWith(".java")).toList();
            }
        }

        @ParameterizedTest(name = "no source claims {0} is absent")
        @ValueSource(strings = {
            "no {@code logback-spring.xml} exists",
            "No {@code logback-spring.xml} exists",
            "logback-spring.xml} is planned but",
            "repository contains no {@code logback-spring.xml}",
            "publishes no {@code application*.yml}",
            "contains no {@code application*.yml}",
            "no {@code application*.yml} profile exists",
            "no {@code application*.yml} in which",
            "no {@code application*.yml} in this repository",
            "no {@code application*.yml} does either",
            "no {@code application*.yml} that could re-enable",
            "no {@code application*.yml} to enable",
        })
        @DisplayName("the configuration files this migration created are no longer described as missing")
        void noSourceClaimsAConfigurationFileIsAbsent(final String claim) throws IOException {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : mainSources()) {
                if (Files.readString(source, StandardCharsets.UTF_8).contains(claim)) {
                    offenders.add(source.toString());
                }
            }
            assertThat(offenders)
                    .as("these files assert the absence of a file that exists on disk")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} exists, so no source may say otherwise")
        @ValueSource(strings = {
            "src/main/resources/logback-spring.xml",
            "src/main/resources/application.yml",
            "src/main/resources/application-local.yml",
            "src/main/resources/application-test.yml",
            "src/main/resources/application-prod.yml",
            "src/main/java/com/cardemo/CardDemoApplication.java",
        })
        @DisplayName("the premise of the stale claims is false: each named file is present")
        void eachNamedConfigurationFileExists(final String path) {
            assertThat(Path.of(path)).exists();
        }

        @Test
        @DisplayName("fail-on-unknown-properties really is enabled, which the old comments denied")
        void failOnUnknownPropertiesIsEnabled() throws IOException {
            assertThat(Files.readString(
                    Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8))
                    .contains("fail-on-unknown-properties: true");
        }
    }

    /** The duplicated transitive artefacts must be gone, and their replacements present. */
    @Nested
    @DisplayName("Duplicate transitive artefacts are excluded")
    class DuplicateTransitives {

        @Test
        @DisplayName("commons-logging is gone and spring-jcl provides the API instead")
        void commonsLoggingIsProvidedBySpringJcl() {
            final String location = org.apache.commons.logging.LogFactory.class
                    .getProtectionDomain().getCodeSource().getLocation().toString();
            assertThat(location)
                    .as("LogFactory must resolve from spring-jcl, not from commons-logging")
                    .contains("spring-jcl")
                    .doesNotContain("commons-logging");
        }

        @Test
        @DisplayName("aopalliance is gone and spring-aop provides the interfaces instead")
        void aopAllianceIsProvidedBySpringAop() {
            final String location = org.aopalliance.intercept.MethodInterceptor.class
                    .getProtectionDomain().getCodeSource().getLocation().toString();
            assertThat(location)
                    .as("MethodInterceptor must resolve from spring-aop, not from aopalliance")
                    .contains("spring-aop")
                    .doesNotContain("aopalliance");
        }

        @ParameterizedTest(name = "{0} still resolves after the exclusion")
        @ValueSource(strings = {
            "org.apache.commons.logging.Log",
            "org.apache.commons.logging.LogFactory",
            "org.apache.commons.logging.impl.NoOpLog",
            "org.apache.commons.logging.impl.SimpleLog",
            "org.aopalliance.aop.Advice",
            "org.aopalliance.aop.AspectException",
            "org.aopalliance.intercept.ConstructorInterceptor",
            "org.aopalliance.intercept.ConstructorInvocation",
            "org.aopalliance.intercept.Interceptor",
            "org.aopalliance.intercept.Invocation",
            "org.aopalliance.intercept.Joinpoint",
            "org.aopalliance.intercept.MethodInterceptor",
            "org.aopalliance.intercept.MethodInvocation",
        })
        @DisplayName("every type either artefact contributed is still on the classpath")
        void everyContributedTypeStillResolves(final String className) throws Exception {
            assertThat(Class.forName(className)).isNotNull();
        }

        @Test
        @DisplayName("the pom records the exclusions on every declaring dependency")
        void thePomRecordsTheExclusions() throws IOException {
            final String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
            // Three AWS starters reach apache-client, so an exclusion on one alone would let Maven
            // resolve the artefact through a sibling subtree instead.
            assertThat(countOf(pom, "<artifactId>commons-logging</artifactId>"))
                    .as("one exclusion per AWS starter")
                    .isEqualTo(3);
            assertThat(countOf(pom, "<artifactId>aopalliance</artifactId>"))
                    .as("aopalliance has exactly one path, through the tracing bridge")
                    .isEqualTo(1);
        }

        /**
         * Counts non-overlapping occurrences of a literal.
         *
         * @param text the text to search
         * @param literal the literal to count
         * @return the number of occurrences
         */
        private static int countOf(final String text, final String literal) {
            int count = 0;
            for (int at = text.indexOf(literal); at >= 0; at = text.indexOf(literal, at + 1)) {
                count++;
            }
            return count;
        }
    }
}
