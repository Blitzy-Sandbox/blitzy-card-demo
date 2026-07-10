package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.LoggerFactory;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Fast, database-free unit test for {@link PrintReferenceJobs}, the five print/reference batch jobs
 * migrated from the AWS CardDemo COBOL programs {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C},
 * {@code CBCUS01C}, and {@code CBTRN01C} (source referenced read-only at commit SHA {@code 27d6c6f}).
 *
 * <p>The suite is deliberately split into two concerns, each matching the module's established
 * lightweight testing convention (Spring Boot's {@link ApplicationContextRunner} for wiring and plain
 * JUnit 5 + Mockito for behavior) so it runs in milliseconds and needs no PostgreSQL, network, or
 * full application context:</p>
 * <ol>
 *   <li><strong>Wiring</strong> &mdash; the configuration exposes exactly the five {@code print*Step},
 *       five {@code print*Job}, five reader, and five writer beans under their contractually required
 *       names, and no {@link JobLauncherApplicationRunner} is registered (so the jobs never auto-run;
 *       they are launched explicitly, e.g. by the SQS-triggered report bridge or an operator).</li>
 *   <li><strong>Writer behavior</strong> &mdash; each logging writer renders every item without side
 *       effects (the {@code 1100-DISPLAY-*-RECORD} analogue); the transaction writer performs the
 *       optional {@code CBTRN01C} cross-reference&rarr;account enrichment, warning (never failing) on a
 *       missing parent; and a {@code null} record aborts the job with a {@link FileProcessingException}
 *       (the {@code 9999-ABEND-PROGRAM} analogue), never {@code System.exit}.</li>
 * </ol>
 *
 * <p>End-to-end job execution against a real database (seed rows &rarr; launch job &rarr; assert
 * {@code COMPLETED} and the exact read count) is exercised separately as a Testcontainers integration
 * test; this class intentionally keeps the committed suite DB-free and deterministic.</p>
 */
@DisplayName("PrintReferenceJobs — print/reference batch jobs (wiring + writer behavior)")
class PrintReferenceJobsTest {

    private static final List<String> STEP_BEAN_NAMES = List.of(
            "printAccountStep", "printCardStep", "printCardXrefStep",
            "printCustomerStep", "printTransactionStep");

    private static final List<String> JOB_BEAN_NAMES = List.of(
            "printAccountJob", "printCardJob", "printCardXrefJob",
            "printCustomerJob", "printTransactionJob");

    private static final String SAMPLE_CARD_NUMBER = "4111111111111111";

    // ------------------------------------------------------------------------------------------------
    // 1) Wiring — bean inventory and "no auto-run" contract, verified in an isolated context.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("bean wiring")
    class Wiring {

        /**
         * Isolated context loading ONLY {@link PrintReferenceJobs} plus mocked collaborators. Because
         * no auto-configuration is loaded, every reader/writer/step/job bean discovered must originate
         * from the configuration under test — which is what makes the exact-count assertions meaningful.
         * Building the step/job objects does not touch the database, so mocked infrastructure suffices.
         */
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(BatchCorrelationIdListener.class)
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(CardRepository.class, () -> mock(CardRepository.class))
                .withBean(CardXrefRepository.class, () -> mock(CardXrefRepository.class))
                .withBean(CustomerRepository.class, () -> mock(CustomerRepository.class))
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withPropertyValues("carddemo.batch.chunk-size=25")
                .withUserConfiguration(PrintReferenceJobs.class);

        @Test
        @DisplayName("exposes the five print steps and five print jobs under their exact bean names")
        void exposesAllPrintStepAndJobBeansByName() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                STEP_BEAN_NAMES.forEach(name -> assertThat(context).hasBean(name));
                JOB_BEAN_NAMES.forEach(name -> assertThat(context).hasBean(name));
            });
        }

        @Test
        @DisplayName("defines exactly five readers, five writers, five steps, and five jobs")
        void definesExactlyFiveOfEachComponent() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(RepositoryItemReader.class)).hasSize(5);
                assertThat(context.getBeansOfType(ItemWriter.class)).hasSize(5);
                assertThat(context.getBeansOfType(Step.class)).hasSize(5);
                assertThat(context.getBeansOfType(Job.class)).hasSize(5);
            });
        }

        @Test
        @DisplayName("registers no JobLauncherApplicationRunner, so the print jobs never auto-run")
        void registersNoAutoRunRunner() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JobLauncherApplicationRunner.class);
            });
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 2) Writer behavior — logging, enrichment, and the ABEND (null-record) guard.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("writer behavior")
    class Writers {

        private final AccountRepository accountRepository = mock(AccountRepository.class);
        private final CardRepository cardRepository = mock(CardRepository.class);
        private final CardXrefRepository cardXrefRepository = mock(CardXrefRepository.class);
        private final CustomerRepository customerRepository = mock(CustomerRepository.class);
        private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

        private final PrintReferenceJobs config = new PrintReferenceJobs(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                new BatchCorrelationIdListener(),
                accountRepository, cardRepository, cardXrefRepository,
                customerRepository, transactionRepository,
                25);

        @Test
        @DisplayName("simple logging writers render each record without throwing")
        void loggingWritersRenderRecords() throws Exception {
            assertThatCode(() -> {
                config.accountPrintWriter().write(chunkOf(sampleAccount()));
                config.cardPrintWriter().write(chunkOf(sampleCard()));
                config.cardXrefPrintWriter().write(chunkOf(sampleCardXref()));
                config.customerPrintWriter().write(chunkOf(sampleCustomer()));
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("transaction writer enriches via a single batched preload (no per-row findById)")
        void transactionWriterEnrichesWhenParentsPresent() throws Exception {
            when(cardXrefRepository.findAllById(anyIterable())).thenReturn(List.of(sampleCardXref()));
            when(accountRepository.findAllById(anyIterable())).thenReturn(List.of(sampleAccount()));

            config.transactionPrintWriter().write(chunkOf(sampleTransaction(SAMPLE_CARD_NUMBER)));

            // The enrichment resolves the parents from the chunk-level preload — exactly one bulk read
            // per repository — and never falls back to the legacy per-row findById lookups (QA Issue 8).
            verify(cardXrefRepository).findAllById(anyIterable());
            verify(accountRepository).findAllById(anyIterable());
            verify(cardXrefRepository, never()).findById(anyString());
            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("transaction writer warns (does not fail, does not read account) when the xref is missing")
        void transactionWriterWarnsWhenXrefMissing() throws Exception {
            when(cardXrefRepository.findAllById(anyIterable())).thenReturn(List.of());

            assertThatCode(() ->
                    config.transactionPrintWriter().write(chunkOf(sampleTransaction(SAMPLE_CARD_NUMBER))))
                    .doesNotThrowAnyException();

            // The cross-reference preload ran but resolved nothing, so the account preload is skipped
            // entirely (no account ids to fetch) — the batched analogue of "no account read when the
            // xref is absent". No per-row findById is ever issued.
            verify(cardXrefRepository).findAllById(anyIterable());
            verify(accountRepository, never()).findAllById(anyIterable());
            verify(cardXrefRepository, never()).findById(anyString());
            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("transaction writer warns (does not fail) when the referenced account is missing")
        void transactionWriterWarnsWhenAccountMissing() throws Exception {
            when(cardXrefRepository.findAllById(anyIterable())).thenReturn(List.of(sampleCardXref()));
            when(accountRepository.findAllById(anyIterable())).thenReturn(List.of());

            assertThatCode(() ->
                    config.transactionPrintWriter().write(chunkOf(sampleTransaction(SAMPLE_CARD_NUMBER))))
                    .doesNotThrowAnyException();

            // The account is fetched via the batched preload (never the legacy per-row findById), and its
            // absence produces the "ACCOUNT ... NOT FOUND" warning rather than a step failure.
            verify(accountRepository).findAllById(anyIterable());
            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("transaction writer skips enrichment (no repository reads) when the card number is blank")
        void transactionWriterSkipsEnrichmentWhenCardNumberBlank() throws Exception {
            assertThatCode(() ->
                    config.transactionPrintWriter().write(chunkOf(sampleTransaction("   "))))
                    .doesNotThrowAnyException();

            // A blank card number contributes no key to the preload, so neither the batched bulk read
            // nor any per-row lookup is issued.
            verify(cardXrefRepository, never()).findAllById(anyIterable());
            verify(accountRepository, never()).findAllById(anyIterable());
            verify(cardXrefRepository, never()).findById(anyString());
            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("a null record aborts with FileProcessingException (the 9999-ABEND-PROGRAM analogue)")
        void nullRecordTriggersFileProcessingException() {
            final Account missing = null;
            assertThatThrownBy(() -> config.accountPrintWriter().write(chunkOf(missing)))
                    .isInstanceOf(FileProcessingException.class)
                    .hasMessageContaining("ABEND");

            final Transaction missingTransaction = null;
            assertThatThrownBy(() -> config.transactionPrintWriter().write(chunkOf(missingTransaction)))
                    .isInstanceOf(FileProcessingException.class);
        }
    }

    /**
     * Verifies the CP3 data-protection fixes: no diagnostic log line emitted by any print writer may
     * contain a full Primary Account Number (PAN) or a card verification value (CVV). Each test
     * attaches a Logback {@link ListAppender} to the {@link PrintReferenceJobs} logger, drives a real
     * writer, and asserts on the captured formatted messages that the PAN is masked to its last four
     * digits and the CVV is absent entirely.
     */
    @Nested
    @DisplayName("logging redaction (no full PAN / no CVV in logs)")
    class LoggingRedaction {

        /** The masked rendering the fix must produce: twelve asterisks then the last four digits. */
        private static final String MASKED_CARD_NUMBER = "************1111";

        private final AccountRepository accountRepository = mock(AccountRepository.class);
        private final CardRepository cardRepository = mock(CardRepository.class);
        private final CardXrefRepository cardXrefRepository = mock(CardXrefRepository.class);
        private final CustomerRepository customerRepository = mock(CustomerRepository.class);
        private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

        private final PrintReferenceJobs config = new PrintReferenceJobs(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                new BatchCorrelationIdListener(),
                accountRepository, cardRepository, cardXrefRepository,
                customerRepository, transactionRepository,
                25);

        @Test
        @DisplayName("card print writer masks the PAN and never logs the CVV")
        void cardPrintWriterMasksPanAndOmitsCvv() throws Exception {
            final List<String> messages =
                    captureLogsWhile(() -> config.cardPrintWriter().write(chunkOf(sampleCard())));

            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
            assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
            // The CVV token was removed from the rendering entirely.
            assertThat(messages).noneMatch(message -> message.contains("cvv="));
        }

        @Test
        @DisplayName("card cross-reference print writer masks the PAN")
        void cardXrefPrintWriterMasksPan() throws Exception {
            final List<String> messages =
                    captureLogsWhile(() -> config.cardXrefPrintWriter().write(chunkOf(sampleCardXref())));

            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
            assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
        }

        @Test
        @DisplayName("transaction print writer masks the PAN in both the record render and enrichment")
        void transactionPrintWriterMasksPan() throws Exception {
            when(cardXrefRepository.findAllById(anyIterable())).thenReturn(List.of(sampleCardXref()));
            when(accountRepository.findAllById(anyIterable())).thenReturn(List.of(sampleAccount()));

            final List<String> messages = captureLogsWhile(() ->
                    config.transactionPrintWriter().write(chunkOf(sampleTransaction(SAMPLE_CARD_NUMBER))));

            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
            assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
        }

        @Test
        @DisplayName("transaction enrichment warning masks the PAN when the cross-reference is missing")
        void transactionEnrichmentWarningMasksPan() throws Exception {
            when(cardXrefRepository.findAllById(anyIterable())).thenReturn(List.of());

            final List<String> messages = captureLogsWhile(() ->
                    config.transactionPrintWriter().write(chunkOf(sampleTransaction(SAMPLE_CARD_NUMBER))));

            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
            assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
        }

        /**
         * Runs {@code action} with a {@link ListAppender} attached to the {@link PrintReferenceJobs}
         * logger at {@code DEBUG} level, then detaches it and returns the captured formatted
         * messages. The logger level is restored afterwards so tests remain isolated.
         *
         * @param action the writer invocation to capture logs for
         * @return the formatted log messages emitted during {@code action}
         */
        private List<String> captureLogsWhile(final ThrowingRunnable action) throws Exception {
            final Logger logger = (Logger) LoggerFactory.getLogger(PrintReferenceJobs.class);
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            final Level previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            try {
                action.run();
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
            }
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    /** A checked-exception-tolerant {@link Runnable} so log-capture blocks can call writer methods. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------------------------------------
    // Sample-record and chunk helpers.
    // ------------------------------------------------------------------------------------------------

    private static <T> Chunk<T> chunkOf(final T item) {
        final Chunk<T> chunk = new Chunk<>();
        chunk.add(item);
        return chunk;
    }

    private static Account sampleAccount() {
        final Account account = new Account();
        account.setAcctId(9990001111L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpirationDate(LocalDate.of(2030, 1, 1));
        account.setAcctReissueDate(LocalDate.of(2025, 1, 1));
        account.setAcctCurrCycCredit(new BigDecimal("100.00"));
        account.setAcctCurrCycDebit(new BigDecimal("50.00"));
        account.setAcctGroupId("GRP01");
        return account;
    }

    private static Card sampleCard() {
        final Card card = new Card();
        card.setCardNum(SAMPLE_CARD_NUMBER);
        card.setCardAcctId(9990001111L);
        card.setCardCvvCd(123);
        card.setCardEmbossedName("JANE Q PUBLIC");
        card.setCardExpirationDate(LocalDate.of(2030, 12, 31));
        card.setCardActiveStatus("Y");
        return card;
    }

    private static CardXref sampleCardXref() {
        final CardXref xref = new CardXref();
        xref.setXrefCardNum(SAMPLE_CARD_NUMBER);
        xref.setXrefCustId(77001L);
        xref.setXrefAcctId(9990001111L);
        return xref;
    }

    private static Customer sampleCustomer() {
        final Customer customer = new Customer();
        customer.setCustId(77001L);
        customer.setCustFirstName("JANE");
        customer.setCustLastName("PUBLIC");
        customer.setCustAddrStateCd("TX");
        customer.setCustAddrZip("75001");
        customer.setCustFicoCreditScore(720);
        return customer;
    }

    private static Transaction sampleTransaction(final String cardNumber) {
        final Transaction transaction = new Transaction();
        transaction.setTranId("0000000000000001");
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(5000);
        transaction.setTranSource("POS");
        transaction.setTranDesc("PURCHASE");
        transaction.setTranAmt(new BigDecimal("42.00"));
        transaction.setTranMerchantId(123456789L);
        transaction.setTranMerchantName("ACME STORE");
        transaction.setTranCardNum(cardNumber);
        transaction.setTranOrigTs("2022-07-19-23.12.31.000000");
        transaction.setTranProcTs("2022-07-20-01.00.00.000000");
        return transaction;
    }
}
