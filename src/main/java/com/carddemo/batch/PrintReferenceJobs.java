package com.carddemo.batch;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

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

/**
 * Spring Batch configuration that defines the five <em>print / reference</em> utility jobs migrated
 * from the AWS CardDemo standalone batch "read-and-display" programs. Each job reads every row of a
 * single reference file (entity) in primary-key order and logs it &mdash; the modern analogue of the
 * legacy COBOL {@code DISPLAY} statement.
 *
 * <h2>COBOL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>Every job in this class is a one-to-one translation of a self-contained COBOL batch program.
 * These programs are <strong>not</strong> copied into this repository; they are referenced by commit
 * SHA for traceability only (see {@code docs/traceability-matrix.md}):</p>
 * <ul>
 *   <li>{@code app/cbl/CBACT01C.cbl} &mdash; "Read and print account data file" &rarr;
 *       {@link #printAccountJob()} over {@link Account}.</li>
 *   <li>{@code app/cbl/CBACT02C.cbl} &mdash; "Read and print card data file" &rarr;
 *       {@link #printCardJob()} over {@link Card}.</li>
 *   <li>{@code app/cbl/CBACT03C.cbl} &mdash; "Read and print card cross-reference file" &rarr;
 *       {@link #printCardXrefJob()} over {@link CardXref}.</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl} &mdash; "Read and print customer data file" &rarr;
 *       {@link #printCustomerJob()} over {@link Customer}.</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} &mdash; "Read and print (daily) transaction file", enriching each
 *       row with cross-reference/account context &rarr; {@link #printTransactionJob()} over
 *       {@link Transaction}.</li>
 * </ul>
 *
 * <h2>Preserved control flow (OPEN &rarr; READ-loop &rarr; DISPLAY &rarr; CLOSE)</h2>
 * <p>Each source program follows an identical structured shape, verified in {@code CBACT01C}:</p>
 * <pre>
 *   OPEN INPUT &lt;file&gt;
 *   PERFORM UNTIL END-OF-FILE = 'Y'
 *       READ &lt;file&gt; INTO &lt;record&gt;
 *       EVALUATE FILE STATUS
 *           WHEN '00'  DISPLAY &lt;record&gt;        (via 1100-DISPLAY-*-RECORD)
 *           WHEN '10'  MOVE 'Y' TO END-OF-FILE   (normal end of data)
 *           WHEN OTHER PERFORM 9999-ABEND-PROGRAM (unrecoverable I/O error)
 *   END-PERFORM
 *   CLOSE &lt;file&gt;
 * </pre>
 * <p>The COBOL {@code PERFORM UNTIL END-OF-FILE} read loop maps to a Spring Batch chunk-oriented step:
 * a {@link RepositoryItemReader} plays the role of {@code READ INTO} (returning {@code null} at the end
 * of data, which is the framework's normal-termination signal &mdash; the {@code FILE STATUS '10'}
 * analogue, never an exception), and an inline logging {@link ItemWriter} plays the role of the
 * {@code 1100-DISPLAY-*-RECORD} paragraph. The {@code 9999-ABEND-PROGRAM} path (an unexpected
 * {@code FILE STATUS} that is neither {@code '00'} nor {@code '10'}) maps to a thrown
 * {@link FileProcessingException} &mdash; the job fails cleanly through the framework rather than
 * calling {@code System.exit} (the mainframe {@code CALL 'CEE3ABD'} analogue).</p>
 *
 * <h2>Scope and budget</h2>
 * <p>These are intentionally simple <strong>utility / reference</strong> jobs. They are <em>not</em>
 * part of the ordered five-stage business pipeline
 * (POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT / TRANREPT) and are <em>not</em> subject to
 * the byte-parity validation gates (Gate&nbsp;1 / Gate&nbsp;4). To keep the documented component budget
 * intact (five readers / five processors / three writers, all reserved for the business pipeline),
 * this configuration deliberately uses <strong>only</strong> the Spring Batch built-in
 * {@link RepositoryItemReader} plus inline lambda {@link ItemWriter}s &mdash; it introduces no custom
 * {@code ItemReader}, {@code ItemProcessor}, or {@code ItemWriter} classes, and no processor step
 * (the read&rarr;display mapping is the identity).</p>
 *
 * <h2>Observability</h2>
 * <p>Every job registers {@link BatchCorrelationIdListener}, which seeds an MDC {@code correlationId}
 * for the duration of the job execution so that all reader/writer log lines emitted on the launching
 * thread are correlated (Observability rule, AAP&nbsp;&sect;0.7.1). All logging is performed through
 * SLF4J at {@code INFO} (record dumps) or {@code WARN} (missing enrichment parents); this class holds
 * no business logic and performs no persistence &mdash; it reads and logs only.</p>
 *
 * @see BatchCorrelationIdListener
 * @see RepositoryItemReader
 */
@Configuration
public class PrintReferenceJobs {

    /** SLF4J logger; its output carries the MDC {@code correlationId} seeded by the batch listener. */
    private static final Logger log = LoggerFactory.getLogger(PrintReferenceJobs.class);

    /**
     * Number of trailing Primary Account Number (PAN) digits left visible when a card number is
     * masked for a diagnostic log line, matching the last-four convention used elsewhere in the
     * application. No full PAN &mdash; and no card verification value (CVV) &mdash; is ever written
     * to a log (AAP &sect;0.3.2, no sensitive card data in logs).
     */
    private static final int PAN_VISIBLE_DIGITS = 4;

    /** Shared job repository (Spring Boot auto-configured) used to build every job and step. */
    private final JobRepository jobRepository;

    /** Transaction manager (Spring Boot auto-configured) bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Listener that establishes the per-execution MDC correlation id; registered on every job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /** Repository backing the account print reader (CBACT01C). */
    private final AccountRepository accountRepository;

    /** Repository backing the card print reader (CBACT02C). */
    private final CardRepository cardRepository;

    /** Repository backing the card cross-reference print reader (CBACT03C) and transaction enrichment. */
    private final CardXrefRepository cardXrefRepository;

    /** Repository backing the customer print reader (CBCUS01C). */
    private final CustomerRepository customerRepository;

    /** Repository backing the transaction print reader (CBTRN01C). */
    private final TransactionRepository transactionRepository;

    /**
     * Chunk size (also used as the reader page size), externalized from the JCL {@code SYSIN}/PARM
     * model to Spring configuration. Defaults to {@code 100} when {@code carddemo.batch.chunk-size} is
     * not supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Creates the print/reference job configuration with all collaborators injected by constructor
     * (the single constructor is auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobRepository              the auto-configured Spring Batch job repository
     * @param transactionManager         the auto-configured platform transaction manager
     * @param batchCorrelationIdListener the correlation-id listener registered on every job
     * @param accountRepository          repository for {@link Account} (CBACT01C)
     * @param cardRepository             repository for {@link Card} (CBACT02C)
     * @param cardXrefRepository         repository for {@link CardXref} (CBACT03C) and enrichment
     * @param customerRepository         repository for {@link Customer} (CBCUS01C)
     * @param transactionRepository      repository for {@link Transaction} (CBTRN01C)
     * @param chunkSize                  chunk / reader page size ({@code carddemo.batch.chunk-size},
     *                                   default {@code 100})
     */
    PrintReferenceJobs(final JobRepository jobRepository,
                       final PlatformTransactionManager transactionManager,
                       final BatchCorrelationIdListener batchCorrelationIdListener,
                       final AccountRepository accountRepository,
                       final CardRepository cardRepository,
                       final CardXrefRepository cardXrefRepository,
                       final CustomerRepository customerRepository,
                       final TransactionRepository transactionRepository,
                       @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.chunkSize = chunkSize;
    }

    // ------------------------------------------------------------------------------------------------
    // Readers — one RepositoryItemReader per reference file (the COBOL "READ <file> INTO <record>").
    // findAll is paged and ordered by the entity primary key to reproduce the sequential key order in
    // which the legacy VSAM/QSAM programs read each file. End of data -> reader returns null (the
    // FILE STATUS '10' analogue) -> the step terminates normally.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reader for the account print job (CBACT01C), streaming every {@link Account} ordered by
     * {@code acctId} ascending.
     *
     * @return a paged repository reader over {@link Account}
     */
    @Bean
    RepositoryItemReader<Account> accountPrintReader() {
        final RepositoryItemReader<Account> reader = new RepositoryItemReader<>();
        reader.setRepository(accountRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("acctId", Sort.Direction.ASC));
        reader.setPageSize(chunkSize);
        reader.setName("accountPrintReader");
        return reader;
    }

    /**
     * Reader for the card print job (CBACT02C), streaming every {@link Card} ordered by
     * {@code cardNum} ascending.
     *
     * @return a paged repository reader over {@link Card}
     */
    @Bean
    RepositoryItemReader<Card> cardPrintReader() {
        final RepositoryItemReader<Card> reader = new RepositoryItemReader<>();
        reader.setRepository(cardRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("cardNum", Sort.Direction.ASC));
        reader.setPageSize(chunkSize);
        reader.setName("cardPrintReader");
        return reader;
    }

    /**
     * Reader for the card cross-reference print job (CBACT03C), streaming every {@link CardXref}
     * ordered by {@code xrefCardNum} ascending.
     *
     * @return a paged repository reader over {@link CardXref}
     */
    @Bean
    RepositoryItemReader<CardXref> cardXrefPrintReader() {
        final RepositoryItemReader<CardXref> reader = new RepositoryItemReader<>();
        reader.setRepository(cardXrefRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("xrefCardNum", Sort.Direction.ASC));
        reader.setPageSize(chunkSize);
        reader.setName("cardXrefPrintReader");
        return reader;
    }

    /**
     * Reader for the customer print job (CBCUS01C), streaming every {@link Customer} ordered by
     * {@code custId} ascending.
     *
     * @return a paged repository reader over {@link Customer}
     */
    @Bean
    RepositoryItemReader<Customer> customerPrintReader() {
        final RepositoryItemReader<Customer> reader = new RepositoryItemReader<>();
        reader.setRepository(customerRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("custId", Sort.Direction.ASC));
        reader.setPageSize(chunkSize);
        reader.setName("customerPrintReader");
        return reader;
    }

    /**
     * Reader for the transaction print job (CBTRN01C), streaming every {@link Transaction} ordered by
     * {@code tranId} ascending.
     *
     * @return a paged repository reader over {@link Transaction}
     */
    @Bean
    RepositoryItemReader<Transaction> transactionPrintReader() {
        final RepositoryItemReader<Transaction> reader = new RepositoryItemReader<>();
        reader.setRepository(transactionRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("tranId", Sort.Direction.ASC));
        reader.setPageSize(chunkSize);
        reader.setName("transactionPrintReader");
        return reader;
    }

    // ------------------------------------------------------------------------------------------------
    // Writers — inline logging ItemWriters (the COBOL 1100-DISPLAY-*-RECORD paragraphs). Each writer is
    // side-effect-free with respect to persistence: it only logs. A null item in a chunk is impossible
    // for a healthy RepositoryItemReader, so it is treated as the 9999-ABEND-PROGRAM path and aborts
    // the job with a FileProcessingException (never System.exit).
    // ------------------------------------------------------------------------------------------------

    /**
     * Writer for the account print job (CBACT01C, paragraph {@code 1100-DISPLAY-ACCT-RECORD}).
     *
     * @return an {@link ItemWriter} that logs each {@link Account} at {@code INFO}
     */
    @Bean
    ItemWriter<Account> accountPrintWriter() {
        return loggingWriter("ACCOUNT", PrintReferenceJobs::describeAccount);
    }

    /**
     * Writer for the card print job (CBACT02C, which issues {@code DISPLAY CARD-RECORD}).
     *
     * @return an {@link ItemWriter} that logs each {@link Card} at {@code INFO}
     */
    @Bean
    ItemWriter<Card> cardPrintWriter() {
        return loggingWriter("CARD", PrintReferenceJobs::describeCard);
    }

    /**
     * Writer for the card cross-reference print job (CBACT03C, which issues
     * {@code DISPLAY CARD-XREF-RECORD}).
     *
     * @return an {@link ItemWriter} that logs each {@link CardXref} at {@code INFO}
     */
    @Bean
    ItemWriter<CardXref> cardXrefPrintWriter() {
        return loggingWriter("CARD-XREF", PrintReferenceJobs::describeCardXref);
    }

    /**
     * Writer for the customer print job (CBCUS01C, which issues {@code DISPLAY CUSTOMER-RECORD}).
     *
     * <p>To avoid leaking personally identifiable information into logs, this writer logs a stable,
     * identifying subset of the customer record (id, name, locality, and FICO score) rather than the
     * full 500-byte record the legacy program dumped; these print jobs are display-only and are not
     * byte-parity gates, so the reduced projection does not affect any interface contract.</p>
     *
     * @return an {@link ItemWriter} that logs each {@link Customer} at {@code INFO}
     */
    @Bean
    ItemWriter<Customer> customerPrintWriter() {
        return loggingWriter("CUSTOMER", PrintReferenceJobs::describeCustomer);
    }

    /**
     * Writer for the transaction print job (CBTRN01C). It logs each {@link Transaction} and then, as an
     * optional enrichment faithful to the source program, resolves the owning cross-reference and
     * account for display.
     *
     * <p>CBTRN01C, for each transaction, performs {@code 2000-LOOKUP-XREF} (by card number) followed by
     * {@code 3000-READ-ACCOUNT} (by account id), emitting {@code "CARD NUMBER ... COULD NOT BE VERIFIED"}
     * or {@code "ACCOUNT ... NOT FOUND"} when a parent is absent but never aborting on it. This writer
     * reproduces that behavior via {@link #enrichAndLogTransaction(Transaction)}: enrichment is
     * best-effort and a missing parent is a {@code WARN}, not a step failure.</p>
     *
     * @return an {@link ItemWriter} that logs each {@link Transaction} and its enriched context
     */
    @Bean
    ItemWriter<Transaction> transactionPrintWriter() {
        return chunk -> {
            for (final Transaction transaction : chunk) {
                requirePresentRecord(transaction, "TRANSACTION");
                log.info("TRANSACTION :: {}", describeTransaction(transaction));
                enrichAndLogTransaction(transaction);
            }
        };
    }

    // ------------------------------------------------------------------------------------------------
    // Steps — chunk-oriented read->display steps (no processor; the read->display mapping is identity).
    // Exact bean names are contractually required by the AAP file specification.
    // ------------------------------------------------------------------------------------------------

    /**
     * Account print step (CBACT01C): reads all accounts and logs each one.
     *
     * @return the {@code printAccountStep} step
     */
    @Bean
    Step printAccountStep() {
        return buildPrintStep("printAccountStep", accountPrintReader(), accountPrintWriter(),
                jobRepository, transactionManager, chunkSize);
    }

    /**
     * Card print step (CBACT02C): reads all cards and logs each one.
     *
     * @return the {@code printCardStep} step
     */
    @Bean
    Step printCardStep() {
        return buildPrintStep("printCardStep", cardPrintReader(), cardPrintWriter(),
                jobRepository, transactionManager, chunkSize);
    }

    /**
     * Card cross-reference print step (CBACT03C): reads all cross-reference rows and logs each one.
     *
     * @return the {@code printCardXrefStep} step
     */
    @Bean
    Step printCardXrefStep() {
        return buildPrintStep("printCardXrefStep", cardXrefPrintReader(), cardXrefPrintWriter(),
                jobRepository, transactionManager, chunkSize);
    }

    /**
     * Customer print step (CBCUS01C): reads all customers and logs each one.
     *
     * @return the {@code printCustomerStep} step
     */
    @Bean
    Step printCustomerStep() {
        return buildPrintStep("printCustomerStep", customerPrintReader(), customerPrintWriter(),
                jobRepository, transactionManager, chunkSize);
    }

    /**
     * Transaction print step (CBTRN01C): reads all transactions and logs each one with enrichment.
     *
     * @return the {@code printTransactionStep} step
     */
    @Bean
    Step printTransactionStep() {
        return buildPrintStep("printTransactionStep", transactionPrintReader(), transactionPrintWriter(),
                jobRepository, transactionManager, chunkSize);
    }

    // ------------------------------------------------------------------------------------------------
    // Jobs — one single-step job per reference file. Each registers the correlation-id listener and a
    // RunIdIncrementer so the job is re-runnable (distinct JobInstance per launch). Exact bean names
    // are contractually required by the AAP file specification.
    // ------------------------------------------------------------------------------------------------

    /**
     * Account print job (CBACT01C).
     *
     * @return the {@code printAccountJob} job
     */
    @Bean
    Job printAccountJob() {
        return buildPrintJob("printAccountJob", printAccountStep());
    }

    /**
     * Card print job (CBACT02C).
     *
     * @return the {@code printCardJob} job
     */
    @Bean
    Job printCardJob() {
        return buildPrintJob("printCardJob", printCardStep());
    }

    /**
     * Card cross-reference print job (CBACT03C).
     *
     * @return the {@code printCardXrefJob} job
     */
    @Bean
    Job printCardXrefJob() {
        return buildPrintJob("printCardXrefJob", printCardXrefStep());
    }

    /**
     * Customer print job (CBCUS01C).
     *
     * @return the {@code printCustomerJob} job
     */
    @Bean
    Job printCustomerJob() {
        return buildPrintJob("printCustomerJob", printCustomerStep());
    }

    /**
     * Transaction print job (CBTRN01C).
     *
     * @return the {@code printTransactionJob} job
     */
    @Bean
    Job printTransactionJob() {
        return buildPrintJob("printTransactionJob", printTransactionStep());
    }

    // ------------------------------------------------------------------------------------------------
    // Private helpers — builders and the shared logging/enrichment logic.
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a chunk-oriented read&rarr;display step: reader &rarr; (identity) &rarr; logging writer,
     * with no processor. The chunk size doubles as the reader page size so a single page is fully read
     * and logged per transaction boundary.
     *
     * @param stepName              the step (and bean) name
     * @param reader                the item reader supplying records (the {@code READ INTO} analogue)
     * @param writer                the logging writer (the {@code DISPLAY} analogue)
     * @param jobRepositoryRef      the job repository backing step meta-data
     * @param transactionManagerRef the transaction manager bounding each chunk
     * @param chunk                 the chunk size / commit interval
     * @param <T>                   the record type read and logged by this step
     * @return a fully built {@link Step}
     */
    private <T> Step buildPrintStep(final String stepName,
                                    final ItemReader<T> reader,
                                    final ItemWriter<T> writer,
                                    final JobRepository jobRepositoryRef,
                                    final PlatformTransactionManager transactionManagerRef,
                                    final int chunk) {
        return new StepBuilder(stepName, jobRepositoryRef)
                .<T, T>chunk(chunk, transactionManagerRef)
                .reader(reader)
                .writer(writer)
                .build();
    }

    /**
     * Builds a single-step job that registers the correlation-id listener (Observability rule) and a
     * {@link RunIdIncrementer} so each launch produces a distinct {@code JobInstance} and the job is
     * re-runnable (the JCL rerun analogue).
     *
     * @param jobName the job (and bean) name
     * @param step    the single step the job executes
     * @return a fully built {@link Job}
     */
    private Job buildPrintJob(final String jobName, final Step step) {
        return new JobBuilder(jobName, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(step)
                .build();
    }

    /**
     * Creates a logging {@link ItemWriter} that renders each item via {@code describer} and logs it at
     * {@code INFO} under {@code recordLabel} &mdash; the generic form of the COBOL
     * {@code 1100-DISPLAY-*-RECORD} paragraphs. A {@code null} item aborts the job through
     * {@link #requirePresentRecord(Object, String)} (the {@code 9999-ABEND-PROGRAM} analogue).
     *
     * @param recordLabel a short label identifying the record type in the log line
     * @param describer   a side-effect-free function rendering an item to a log string
     * @param <T>         the record type
     * @return a logging writer for {@code T}
     */
    private <T> ItemWriter<T> loggingWriter(final String recordLabel,
                                            final Function<? super T, String> describer) {
        return chunk -> {
            for (final T item : chunk) {
                requirePresentRecord(item, recordLabel);
                log.info("{} :: {}", recordLabel, describer.apply(item));
            }
        };
    }

    /**
     * Best-effort enrichment for the transaction print job (CBTRN01C): resolves the transaction's card
     * cross-reference and then its owning account, logging the enriched view. A missing cross-reference
     * or account is logged as a {@code WARN} and does not fail the step, exactly mirroring the source
     * program's {@code "... COULD NOT BE VERIFIED"} / {@code "... NOT FOUND"} messages.
     *
     * @param transaction the transaction to enrich and log (never {@code null})
     */
    private void enrichAndLogTransaction(final Transaction transaction) {
        final String cardNumber = transaction.getTranCardNum();
        if (cardNumber == null || cardNumber.isBlank()) {
            return;
        }
        final Optional<CardXref> crossReference = cardXrefRepository.findById(cardNumber);
        if (crossReference.isEmpty()) {
            log.warn("CARD NUMBER {} COULD NOT BE VERIFIED (no cross-reference); "
                    + "skipping enrichment for transaction {}", maskPan(cardNumber), transaction.getTranId());
            return;
        }
        final Long accountId = crossReference.get().getXrefAcctId();
        final Optional<Account> account = accountRepository.findById(accountId);
        if (account.isEmpty()) {
            log.warn("ACCOUNT {} NOT FOUND for transaction {} (referenced via card {})",
                    accountId, transaction.getTranId(), maskPan(cardNumber));
            return;
        }
        log.info("TRANSACTION-ENRICHED :: tranId={} cardNum={} custId={} {}",
                transaction.getTranId(), maskPan(cardNumber), crossReference.get().getXrefCustId(),
                describeAccount(account.get()));
    }

    /**
     * Guards against a {@code null} record in a chunk. A healthy {@link RepositoryItemReader} never
     * emits {@code null} into a chunk ({@code null} from {@code read()} signals end-of-data to the
     * framework), so a {@code null} here indicates an unexpected/corrupt state. This is the Java
     * analogue of the COBOL {@code 9999-ABEND-PROGRAM} path (an unexpected {@code FILE STATUS}); it
     * throws a {@link FileProcessingException} to fail the job cleanly instead of calling
     * {@code System.exit} (the mainframe {@code CALL 'CEE3ABD'}).
     *
     * @param item        the record to check
     * @param recordLabel a short label identifying the record type for the error message
     * @param <T>         the record type
     */
    private static <T> void requirePresentRecord(final T item, final String recordLabel) {
        if (item == null) {
            throw new FileProcessingException(
                    "Unexpected null " + recordLabel + " record encountered while printing; aborting job. "
                    + "Java analogue of the COBOL 9999-ABEND-PROGRAM path "
                    + "(unexpected FILE STATUS, neither '00' success nor '10' end-of-file).");
        }
    }

    /**
     * Renders an {@link Account} for logging, mirroring the fields displayed by
     * {@code CBACT01C} paragraph {@code 1100-DISPLAY-ACCT-RECORD}.
     *
     * @param account the account to render (never {@code null})
     * @return a single-line description of the account
     */
    private static String describeAccount(final Account account) {
        return String.format(
                "acctId=%s status=%s currBal=%s creditLimit=%s cashCreditLimit=%s openDate=%s "
                + "expirationDate=%s reissueDate=%s currCycCredit=%s currCycDebit=%s groupId=%s",
                account.getAcctId(), account.getAcctActiveStatus(), account.getAcctCurrBal(),
                account.getAcctCreditLimit(), account.getAcctCashCreditLimit(), account.getAcctOpenDate(),
                account.getAcctExpirationDate(), account.getAcctReissueDate(),
                account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit(), account.getAcctGroupId());
    }

    /**
     * Renders a {@link Card} for logging (CBACT02C {@code DISPLAY CARD-RECORD}).
     *
     * @param card the card to render (never {@code null})
     * @return a single-line description of the card
     */
    private static String describeCard(final Card card) {
        // The PAN is masked to its last four digits and the CVV is intentionally omitted entirely:
        // neither the full card number nor the card verification value may appear in any log line
        // (AAP 0.3.2, no sensitive card data in logs).
        return String.format(
                "cardNum=%s acctId=%s embossedName=%s expirationDate=%s status=%s",
                maskPan(card.getCardNum()), card.getCardAcctId(),
                card.getCardEmbossedName(), card.getCardExpirationDate(), card.getCardActiveStatus());
    }

    /**
     * Renders a {@link CardXref} for logging (CBACT03C {@code DISPLAY CARD-XREF-RECORD}).
     *
     * @param xref the cross-reference row to render (never {@code null})
     * @return a single-line description of the cross-reference row
     */
    private static String describeCardXref(final CardXref xref) {
        return String.format("xrefCardNum=%s custId=%s acctId=%s",
                maskPan(xref.getXrefCardNum()), xref.getXrefCustId(), xref.getXrefAcctId());
    }

    /**
     * Renders a {@link Customer} for logging (CBCUS01C {@code DISPLAY CUSTOMER-RECORD}), projecting a
     * stable, non-sensitive subset (id, name, locality, FICO score) rather than the full record.
     *
     * @param customer the customer to render (never {@code null})
     * @return a single-line description of the customer
     */
    private static String describeCustomer(final Customer customer) {
        return String.format(
                "custId=%s firstName=%s lastName=%s stateCd=%s zip=%s ficoScore=%s",
                customer.getCustId(), customer.getCustFirstName(), customer.getCustLastName(),
                customer.getCustAddrStateCd(), customer.getCustAddrZip(),
                customer.getCustFicoCreditScore());
    }

    /**
     * Renders a {@link Transaction} for logging (CBTRN01C transaction display).
     *
     * @param transaction the transaction to render (never {@code null})
     * @return a single-line description of the transaction
     */
    private static String describeTransaction(final Transaction transaction) {
        return String.format(
                "tranId=%s typeCd=%s catCd=%s source=%s desc=%s amt=%s merchantId=%s merchantName=%s "
                + "cardNum=%s origTs=%s procTs=%s",
                transaction.getTranId(), transaction.getTranTypeCd(), transaction.getTranCatCd(),
                transaction.getTranSource(), transaction.getTranDesc(), transaction.getTranAmt(),
                transaction.getTranMerchantId(), transaction.getTranMerchantName(),
                maskPan(transaction.getTranCardNum()), transaction.getTranOrigTs(),
                transaction.getTranProcTs());
    }

    /**
     * Masks a card number (PAN) for diagnostic logging, leaving only the final
     * {@value #PAN_VISIBLE_DIGITS} digits visible and replacing every earlier character with an
     * asterisk. Any surrounding whitespace is stripped first; a {@code null} value yields
     * {@code "null"} (so it stays distinguishable in a log line) and a value of
     * {@value #PAN_VISIBLE_DIGITS} or fewer characters is fully masked. No full PAN is ever written
     * to a log, satisfying the CardDemo data-protection rule (AAP &sect;0.3.2).
     *
     * @param cardNumber the raw card number (may be {@code null})
     * @return the masked card number safe for logging (never {@code null})
     */
    private static String maskPan(final String cardNumber) {
        if (cardNumber == null) {
            return "null";
        }
        final String normalized = cardNumber.strip();
        final int length = normalized.length();
        if (length <= PAN_VISIBLE_DIGITS) {
            return "*".repeat(length);
        }
        return "*".repeat(length - PAN_VISIBLE_DIGITS)
                + normalized.substring(length - PAN_VISIBLE_DIGITS);
    }
}

