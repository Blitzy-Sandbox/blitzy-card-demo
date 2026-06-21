package com.carddemo.batch.jobs;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.batch.processors.InterestCalculationProcessor;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

/**
 * Spring Batch configuration that re-hosts the mainframe JCL job {@code INTCALC.jcl}
 * ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}) and the monthly interest-calculation
 * COBOL program {@code CBACT04C.cbl} (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY
 * &mdash; the COBOL/JCL is never copied).
 *
 * <p>The job is a single chunk-oriented {@link Step}
 * (<em>reader &rarr; processor &rarr; job-local writer</em>) over the transaction-category-balance
 * dataset (COBOL {@code TCATBALF}, opened INPUT / read-only). The {@code PARM='2022071800'} of the
 * JCL becomes the {@value #PARM_DATE_KEY} {@link org.springframework.batch.core.JobParameter}
 * (a 10-character date string &mdash; the high-order part of every interest {@code TRAN-ID}); the
 * orchestrator must supply it when launching {@link #interestCalculationJob}. Jobs are
 * <strong>not</strong> auto-run ({@code spring.batch.job.enabled=false}); they are launched
 * explicitly by the batch pipeline orchestrator.</p>
 *
 * <h2>Two decoupled effects of CBACT04C (faithfully preserved, never conflated)</h2>
 * <ol>
 *   <li><strong>Interest-transaction emission</strong> ({@code 1300-B-WRITE-TX}): per
 *       category-balance row with a non-zero disclosure-group rate, exactly one interest
 *       {@link Transaction} is emitted by {@link InterestCalculationProcessor} and staged by this
 *       job's writer to the {@code SYSTRAN} S3 object (COBOL {@code TRANSACT} DD &rarr;
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(+1)} GDG, {@code RECFM=F LRECL=350}; GDG generation &rarr; S3
 *       versioned object).</li>
 *   <li><strong>Account control-break rollup</strong> ({@code 1050-UPDATE-ACCOUNT}): the per-account
 *       total interest is accumulated across all of an account's category rows and, on account
 *       change and at end of input, added to {@code ACCT-CURR-BAL}; the current-cycle credit and
 *       debit fields are then reset to zero and the account is rewritten.</li>
 * </ol>
 *
 * <p>The transaction-category-balance table is <strong>never</strong> written (COBOL {@code TCATBAL}
 * is INPUT-only), and this job does <strong>not</strong> insert interest rows into the
 * {@code transaction} table: those reach the master table only through {@code CombineTransactionsJob}
 * (COMBTRAN merges {@code BKUP(0)+SYSTRAN(0)}), so inserting here would double-load them.</p>
 *
 * <p>The interest writer is implemented <em>job-local</em> (the {@link InterestTransactionWriter}
 * nested type) rather than reusing the shared {@code TransactionWriter}, because that writer always
 * upserts the category balance and adds to the cycle credit/debit totals &mdash; which would regress
 * the read-only-TCATBAL and zero-the-cycle-fields semantics of this program.</p>
 *
 * <p>All monetary values are handled with {@link BigDecimal} (scale&nbsp;2,
 * {@link RoundingMode#HALF_EVEN} where rounding applies); {@code float}/{@code double} are never used
 * for money. Decimal comparisons use {@code compareTo}, never {@code equals}.</p>
 */
@Configuration(value = "interestCalculationJobConfig", proxyBeanMethods = false)
public final class InterestCalculationJob {

    /** Canonical Spring Batch job name (also the {@code INTCALC.jcl} job identity). */
    public static final String JOB_NAME = "interestCalculationJob";

    /** Bean name and step name of the single chunk-oriented interest step. */
    public static final String STEP_NAME = "interestCalculationStep";

    /** Bean name of the step-scoped category-balance reader. */
    public static final String READER_NAME = "interestCategoryBalanceReader";

    /**
     * Name of the job parameter carrying the 10-character processing date (COBOL
     * {@code PARM-DATE PIC X(10)}, the {@code PARM='2022071800'} of {@code INTCALC.jcl}). The
     * orchestrator must pass this parameter; the locked {@link InterestCalculationProcessor} reads it
     * via {@code @Value("#{jobParameters['parmDate']}")} to build each interest {@code TRAN-ID}.
     */
    public static final String PARM_DATE_KEY = "parmDate";

    /** Chunk size (commit interval) of the interest step. */
    static final int CHUNK_SIZE = 100;

    /** Page size of the {@link RepositoryItemReader}; aligned with {@link #CHUNK_SIZE}. */
    static final int PAGE_SIZE = 100;

    /** JPA property path of the embedded-id account id (control-break major sort key). */
    private static final String SORT_ACCOUNT_ID = "id.accountId";

    /** JPA property path of the embedded-id transaction-type code (second sort key). */
    private static final String SORT_TYPE_CODE = "id.typeCode";

    /** JPA property path of the embedded-id transaction-category code (third sort key). */
    private static final String SORT_CATEGORY_CODE = "id.categoryCode";

    /** Synchronous S3 client (bean from {@code com.carddemo.config.AwsConfig}) for SYSTRAN staging. */
    private final S3Client s3Client;

    /** Account repository (re-platforms ACCTDAT); used by the writer's control-break rollup. */
    private final AccountRepository accountRepository;

    /** Card cross-reference repository (re-platforms CARDXREF); resolves card number &rarr; account id. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** S3 bucket holding the interest staging object (SYSTRAN); read by {@code CombineTransactionsJob}. */
    private final String systranBucket;

    /** S3 object key of the interest staging object (SYSTRAN); read by {@code CombineTransactionsJob}. */
    private final String systranKey;

    /**
     * Creates the interest-calculation job configuration with its writer collaborators and the
     * externalized S3 coordinates for the SYSTRAN staging object.
     *
     * @param s3Client                     synchronous S3 client bean (from {@code AwsConfig}); the
     *                                     endpoint, region, and credentials are resolved from
     *                                     {@code spring.cloud.aws.*} and are never hardcoded here
     * @param accountRepository            account repository for the control-break rollup
     * @param cardCrossReferenceRepository card cross-reference repository for card&rarr;account resolution
     * @param systranBucket                S3 bucket for the SYSTRAN staging object, resolved from
     *                                     {@code carddemo.aws.s3.bucket-output} (default
     *                                     {@code carddemo-batch-output}) &mdash; the same bucket
     *                                     {@code CombineTransactionsJob} reads
     * @param systranKey                   S3 key of the SYSTRAN staging object, resolved from
     *                                     {@code carddemo.batch.interest.systran-key} (default
     *                                     {@code SYSTRAN}) &mdash; the same key
     *                                     {@code CombineTransactionsJob} reads
     */
    public InterestCalculationJob(
            final S3Client s3Client,
            final AccountRepository accountRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") final String systranBucket,
            @Value("${carddemo.batch.interest.systran-key:SYSTRAN}") final String systranKey) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.systranBucket = systranBucket;
        this.systranKey = systranKey;
    }

    /**
     * Step-scoped reader over the transaction-category-balance dataset (COBOL {@code TCATBALF}). The
     * three-level ascending sort by the embedded-id fields (account id <em>major</em>, then type
     * code, then category code) is mandatory: the writer's per-account control break relies on all of
     * an account's rows arriving contiguously and in account order.
     *
     * @param transactionCategoryBalanceRepository the category-balance repository (inherited
     *                                             {@code findAll(Pageable)} drives the paged read)
     * @return a restart-safe, paged {@link RepositoryItemReader} of category-balance rows
     */
    @Bean(READER_NAME)
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> interestCategoryBalanceReader(
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository) {
        final Map<String, Sort.Direction> sort = new LinkedHashMap<>();
        sort.put(SORT_ACCOUNT_ID, Sort.Direction.ASC);
        sort.put(SORT_TYPE_CODE, Sort.Direction.ASC);
        sort.put(SORT_CATEGORY_CODE, Sort.Direction.ASC);

        final RepositoryItemReader<TransactionCategoryBalance> reader = new RepositoryItemReader<>();
        reader.setRepository(transactionCategoryBalanceRepository);
        reader.setMethodName("findAll");
        reader.setPageSize(PAGE_SIZE);
        reader.setSort(sort);
        reader.setName(READER_NAME);
        reader.setSaveState(true);
        return reader;
    }

    /**
     * Defines the single chunk-oriented interest step
     * ({@code <TransactionCategoryBalance, Transaction>}). The job-local writer is registered both as
     * the chunk {@code writer} and as a {@code stream}, so its {@link ItemStreamWriter#open},
     * {@link ItemStreamWriter#update}, and {@link ItemStreamWriter#close} lifecycle callbacks fire
     * (the S3 buffer is allocated on open and flushed on close, and the account accumulation state is
     * checkpointed for restart). The chunk transaction is the unit of work, so the writer's
     * account-update save participates in &mdash; and is rolled back with &mdash; the chunk.
     *
     * @param jobRepository       the auto-configured Spring Batch job repository
     * @param transactionManager  the auto-configured platform transaction manager
     * @param reader              the step-scoped category-balance reader
     * @param processor           the locked interest-calculation processor (rate &rarr; interest tx, or
     *                            {@code null} when the rate is zero)
     * @param interestWriter      the job-local interest writer (S3 staging + account rollup)
     * @return the configured interest step
     */
    @Bean(STEP_NAME)
    public Step interestCalculationStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier(READER_NAME) final RepositoryItemReader<TransactionCategoryBalance> reader,
            final InterestCalculationProcessor processor,
            @Qualifier("interestWriter") final ItemStreamWriter<Transaction> interestWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(interestWriter)
                .stream(interestWriter)
                .build();
    }

    /**
     * Defines the interest-calculation job: a single-step job wrapping {@link #interestCalculationStep}.
     * The orchestrator launches it with the {@value #PARM_DATE_KEY} job parameter.
     *
     * @param jobRepository           the auto-configured Spring Batch job repository
     * @param interestCalculationStep the interest step bean
     * @return the configured interest-calculation job
     */
    @Bean(JOB_NAME)
    public Job interestCalculationJob(
            final JobRepository jobRepository,
            @Qualifier(STEP_NAME) final Step interestCalculationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Job-local interest writer bean. It is a plain singleton (not step-scoped): all per-execution
     * state is owned by the {@link ItemStreamWriter} lifecycle (allocated in {@code open}, checkpointed
     * in {@code update}, flushed/released in {@code close}), which is the same pattern the sibling
     * {@code TransactionWriter} uses for sequential, single-threaded batch execution.
     *
     * @return the job-local {@link InterestTransactionWriter}
     */
    @Bean("interestWriter")
    public ItemStreamWriter<Transaction> interestWriter() {
        return new InterestTransactionWriter(
                this.s3Client,
                this.accountRepository,
                this.cardCrossReferenceRepository,
                this.systranBucket,
                this.systranKey);
    }

    /**
     * Job-local {@link ItemStreamWriter} that faithfully reproduces the two decoupled effects of
     * COBOL {@code CBACT04C} for each interest {@link Transaction} produced by
     * {@link InterestCalculationProcessor}, without ever touching the transaction-category-balance
     * table and without inserting interest rows into the {@code transaction} table.
     *
     * <h2>Effect 1 &mdash; SYSTRAN staging ({@code 1300-B-WRITE-TX})</h2>
     * <p>Each interest transaction is serialized to its byte-exact 350-byte {@code CVTRA05Y} record
     * ({@code RECFM=F LRECL=350}, ISO-8859-1, no delimiter) and appended to a per-run buffer that
     * {@link #close()} uploads once to the SYSTRAN S3 object. Because the bucket is versioned, each run
     * produces a new object version &mdash; the cloud equivalent of a new {@code SYSTRAN(+1)} GDG
     * generation. When no interest transaction is produced, nothing is written
     * (faithful to the source not creating an empty generation).</p>
     *
     * <h2>Effect 2 &mdash; account control-break rollup ({@code 1050-UPDATE-ACCOUNT})</h2>
     * <p>Interest is accumulated per owning account; the account id is resolved from the transaction's
     * card number through the card cross-reference (cached per card). Because the reader sorts by
     * account id, an account change is a simple &quot;current&nbsp;&ne;&nbsp;last&quot; comparison.
     * When the account changes &mdash; and once more at {@link #close()} for the final account &mdash;
     * the owning account is loaded, its current balance is increased by the accumulated interest, its
     * current-cycle credit and debit fields are reset to zero, and it is saved; the accumulator is then
     * reset. The save participates in the chunk transaction.</p>
     *
     * <h2>Restart safety</h2>
     * <p>The in-progress account id and its running accumulated interest are checkpointed to the step
     * {@link ExecutionContext} in {@link #update(ExecutionContext)} and restored in
     * {@link #open(ExecutionContext)}. Combined with the restart-safe reader (which resumes after the
     * last committed chunk, so committed rows are neither re-read nor re-applied), this guarantees the
     * account balances are correct after a restart and that no interest is applied twice.</p>
     */
    static final class InterestTransactionWriter implements ItemStreamWriter<Transaction> {

        /** Lifecycle/diagnostics logger (counts only; never card numbers or other record content). */
        private static final Logger LOGGER = LoggerFactory.getLogger(InterestTransactionWriter.class);

        /** Fixed length of a {@code TRAN-RECORD} ({@code app/cpy/CVTRA05Y.cpy}, RECLN 350). */
        private static final int RECORD_LENGTH = 350;

        /** Monetary scale of {@code TRAN-AMT}/{@code ACCT-*} fields ({@code S9(...)V99}). */
        private static final int AMOUNT_SCALE = 2;

        /** Single-byte charset preserving the byte-exact fixed-width offsets of the 350-byte layout. */
        private static final java.nio.charset.Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

        /** MIME content type recorded on the staged SYSTRAN object (raw fixed-width bytes). */
        private static final String CONTENT_TYPE = "application/octet-stream";

        /** Scale-2 zero used to initialize/reset the accumulator and to zero the cycle fields. */
        private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(AMOUNT_SCALE);

        /**
         * Trailing-overpunch characters for a non-negative zoned-decimal units digit, indexed by the
         * digit value {@code 0..9}: {@code 0} maps to <code>'{'</code> and {@code 1..9} map to
         * {@code 'A'..'I'}.
         */
        private static final char[] POSITIVE_OVERPUNCH =
                {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

        /**
         * Trailing-overpunch characters for a negative zoned-decimal units digit, indexed by the digit
         * value {@code 0..9}: {@code 0} maps to <code>'}'</code> and {@code 1..9} map to
         * {@code 'J'..'R'}.
         */
        private static final char[] NEGATIVE_OVERPUNCH =
                {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

        /** Execution-context key for the in-progress account id (restart checkpoint). */
        private static final String CTX_LAST_ACCOUNT_ID =
                "carddemo.interest.writer.lastAccountId";

        /** Execution-context key for the in-progress accumulated interest (restart checkpoint). */
        private static final String CTX_ACCUMULATED_INTEREST =
                "carddemo.interest.writer.accumulatedInterest";

        /** Synchronous S3 client used to upload the staged SYSTRAN object. */
        private final S3Client s3Client;

        /** Account repository for the control-break rollup ({@code 1050-UPDATE-ACCOUNT}). */
        private final AccountRepository accountRepository;

        /** Card cross-reference repository for card&rarr;account resolution ({@code XREF-ACCT-ID}). */
        private final CardCrossReferenceRepository cardCrossReferenceRepository;

        /** Target S3 bucket for the staged SYSTRAN object. */
        private final String bucket;

        /** Target S3 object key for the staged SYSTRAN object. */
        private final String key;

        /** Per-run accumulation buffer for the staged 350-byte records; {@code null} outside a run. */
        private ByteArrayOutputStream stageBuffer;

        /** Per-run card-number &rarr; account-id cache; {@code null} outside a run. */
        private Map<String, Long> cardToAccountCache;

        /** Id of the account currently being accumulated (COBOL {@code WS-LAST-ACCT-NUM}); may be null. */
        private Long lastAccountId;

        /** Running interest total for {@link #lastAccountId} (COBOL {@code WS-TOTAL-INT}). */
        private BigDecimal accumulatedInterest = ZERO_AMOUNT;

        /** Count of accounts updated this run (logged on close; not a business value). */
        private int accountsUpdated;

        /**
         * Creates the job-local interest writer.
         *
         * @param s3Client                     synchronous S3 client (endpoint/credentials never hardcoded)
         * @param accountRepository            account repository for the control-break rollup
         * @param cardCrossReferenceRepository card cross-reference repository for card&rarr;account resolution
         * @param bucket                       S3 bucket for the staged SYSTRAN object
         * @param key                          S3 object key for the staged SYSTRAN object
         */
        InterestTransactionWriter(
                final S3Client s3Client,
                final AccountRepository accountRepository,
                final CardCrossReferenceRepository cardCrossReferenceRepository,
                final String bucket,
                final String key) {
            this.s3Client = s3Client;
            this.accountRepository = accountRepository;
            this.cardCrossReferenceRepository = cardCrossReferenceRepository;
            this.bucket = bucket;
            this.key = key;
        }

        /**
         * Allocates the per-run staging buffer and card cache, and restores the account-accumulation
         * checkpoint (in-progress account id and running interest) from the execution context so a
         * restart resumes the control break exactly where it left off.
         *
         * @param executionContext the step execution context (may carry a prior checkpoint on restart)
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            this.stageBuffer = new ByteArrayOutputStream();
            this.cardToAccountCache = new HashMap<>();
            this.accountsUpdated = 0;
            this.lastAccountId = executionContext.containsKey(CTX_LAST_ACCOUNT_ID)
                    ? executionContext.getLong(CTX_LAST_ACCOUNT_ID)
                    : null;
            this.accumulatedInterest = executionContext.containsKey(CTX_ACCUMULATED_INTEREST)
                    ? new BigDecimal(executionContext.getString(CTX_ACCUMULATED_INTEREST))
                    : ZERO_AMOUNT;
        }

        /**
         * Stages each interest transaction to the SYSTRAN buffer and advances the per-account
         * control-break accumulation. On an account change the previously accumulated account is
         * flushed (its balance increased and its cycle fields zeroed) before the new account's
         * accumulation begins. Any exception propagates to roll back the entire chunk.
         *
         * @param chunk the chunk of interest transactions produced by the processor
         */
        @Override
        public void write(final Chunk<? extends Transaction> chunk) {
            for (final Transaction interestTransaction : chunk) {
                stageBuffer.writeBytes(buildTranRecord(interestTransaction));

                final Long accountId = resolveAccountId(interestTransaction.getTranCardNum());
                if (lastAccountId != null && !lastAccountId.equals(accountId)) {
                    flushAccount(lastAccountId, accumulatedInterest);
                    accumulatedInterest = ZERO_AMOUNT;
                }
                lastAccountId = accountId;
                accumulatedInterest = accumulatedInterest.add(interestTransaction.getTranAmt());
            }
        }

        /**
         * Checkpoints the in-progress account id and running accumulated interest to the execution
         * context. Invoked at each chunk commit, this keeps the checkpoint consistent with the
         * reader's committed read position so a restart neither loses nor double-applies interest.
         *
         * @param executionContext the step execution context to update
         */
        @Override
        public void update(final ExecutionContext executionContext) {
            if (lastAccountId != null) {
                executionContext.putLong(CTX_LAST_ACCOUNT_ID, lastAccountId);
            }
            executionContext.putString(CTX_ACCUMULATED_INTEREST, accumulatedInterest.toString());
        }

        /**
         * Flushes the final accumulated account (COBOL end-of-input {@code 1050-UPDATE-ACCOUNT}) and
         * uploads the staged SYSTRAN object, then releases all per-run state. The account flush
         * precedes the upload, mirroring the source order (final account rewrite before the TRANSACT
         * file is closed).
         */
        @Override
        public void close() {
            try {
                if (lastAccountId != null) {
                    flushAccount(lastAccountId, accumulatedInterest);
                }
                uploadStagedTransactions();
            } finally {
                LOGGER.info("Interest writer finished: {} account(s) updated, SYSTRAN bytes staged={}",
                        accountsUpdated, stageBuffer == null ? 0 : stageBuffer.size());
                this.stageBuffer = null;
                this.cardToAccountCache = null;
                this.lastAccountId = null;
                this.accumulatedInterest = ZERO_AMOUNT;
            }
        }

        /**
         * Resolves the owning account id for a card number through the card cross-reference (COBOL
         * {@code XREF-ACCT-ID}), caching the result per card. The cross-reference always exists here
         * because the processor obtained the card number from this account's cross-reference; an absent
         * row is therefore an internal-consistency failure (the COBOL {@code INVALID KEY} abend path).
         *
         * @param cardNumber the interest transaction's card number ({@code TRAN-CARD-NUM})
         * @return the owning account id ({@code XREF-ACCT-ID})
         * @throws IllegalStateException if no cross-reference exists for {@code cardNumber}
         */
        private Long resolveAccountId(final String cardNumber) {
            final Long cached = cardToAccountCache.get(cardNumber);
            if (cached != null) {
                return cached;
            }
            final Long accountId = cardCrossReferenceRepository.findById(cardNumber)
                    .orElseThrow(() -> new IllegalStateException(
                            "Card cross-reference not found while staging interest transaction"))
                    .getXrefAcctId();
            cardToAccountCache.put(cardNumber, accountId);
            return accountId;
        }

        /**
         * Applies the per-account interest rollup ({@code 1050-UPDATE-ACCOUNT}): adds the accumulated
         * interest to the current balance, zeroes the current-cycle credit and debit fields, and saves
         * the account. The transaction-category-balance table is never touched.
         *
         * @param accountId       the account to update
         * @param totalInterest   the accumulated interest for the account (COBOL {@code WS-TOTAL-INT})
         * @throws IllegalStateException if no account exists for {@code accountId} (the COBOL
         *                               {@code INVALID KEY} abend path)
         */
        private void flushAccount(final Long accountId, final BigDecimal totalInterest) {
            final Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account " + accountId + " not found while applying interest rollup"));
            account.setAcctCurrBal(account.getAcctCurrBal().add(totalInterest));
            account.setAcctCurrCycCredit(ZERO_AMOUNT);
            account.setAcctCurrCycDebit(ZERO_AMOUNT);
            accountRepository.save(account);
            accountsUpdated++;
        }

        /**
         * Uploads the accumulated SYSTRAN records to S3 as a single object. When nothing was staged the
         * object is not written (no empty generation). The bucket and key are externalized; the S3
         * endpoint/credentials come from configuration and are never hardcoded.
         *
         * @throws IllegalStateException if the S3 upload fails
         */
        private void uploadStagedTransactions() {
            if (stageBuffer == null || stageBuffer.size() == 0) {
                return;
            }
            final byte[] payload = stageBuffer.toByteArray();
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
            } catch (final SdkException sdkFailure) {
                throw new IllegalStateException(
                        "Failed to stage interest transactions to S3 " + bucket + "/" + key, sdkFailure);
            }
        }

        /**
         * Builds the byte-exact 350-byte {@code TRAN-RECORD} ({@code app/cpy/CVTRA05Y.cpy}) for one
         * interest transaction. Offsets (zero-based): {@code TRAN-ID} 0/16, {@code TRAN-TYPE-CD} 16/2,
         * {@code TRAN-CAT-CD} 18/4, {@code TRAN-SOURCE} 22/10, {@code TRAN-DESC} 32/100,
         * {@code TRAN-AMT} 132/11 (signed zoned-decimal, trailing overpunch, scale 2),
         * {@code TRAN-MERCHANT-ID} 143/9, {@code TRAN-MERCHANT-NAME} 152/50,
         * {@code TRAN-MERCHANT-CITY} 202/50, {@code TRAN-MERCHANT-ZIP} 252/10, {@code TRAN-CARD-NUM}
         * 262/16, {@code TRAN-ORIG-TS} 278/26, {@code TRAN-PROC-TS} 304/26, {@code FILLER} 330/20.
         *
         * @param t the interest transaction whose fields populate the record
         * @return a byte array of exactly {@value #RECORD_LENGTH} bytes
         */
        private static byte[] buildTranRecord(final Transaction t) {
            final byte[] out = new byte[RECORD_LENGTH];
            Arrays.fill(out, (byte) ' ');
            putAlpha(out, 0, 16, t.getTranId());
            putAlpha(out, 16, 2, t.getTranTypeCd());
            putNum(out, 18, 4, t.getTranCatCd());
            putAlpha(out, 22, 10, t.getTranSource());
            putAlpha(out, 32, 100, t.getTranDesc());
            putSignedZoned(out, 132, t.getTranAmt());
            putNum(out, 143, 9, t.getTranMerchantId());
            putAlpha(out, 152, 50, t.getTranMerchantName());
            putAlpha(out, 202, 50, t.getTranMerchantCity());
            putAlpha(out, 252, 10, t.getTranMerchantZip());
            putAlpha(out, 262, 16, t.getTranCardNum());
            putAlpha(out, 278, 26, t.getTranOrigTs());
            putAlpha(out, 304, 26, t.getTranProcTs());
            return out;
        }

        /**
         * Writes an alphanumeric field left-justified at the given offset, leaving the remaining
         * positions as the space fill already present. A {@code null} value writes nothing.
         *
         * @param out    the record buffer being populated
         * @param offset the zero-based start position of the field
         * @param len    the fixed field width in bytes
         * @param value  the field value, or {@code null} for an all-blank field
         */
        private static void putAlpha(final byte[] out, final int offset, final int len, final String value) {
            final byte[] bytes = (value == null ? "" : value).getBytes(RECORD_CHARSET);
            final int n = Math.min(len, bytes.length);
            System.arraycopy(bytes, 0, out, offset, n);
        }

        /**
         * Writes an unsigned numeric field zero-padded to {@code len} digits at the given offset,
         * retaining the low-order {@code len} digits when the value is longer (COBOL high-order
         * truncation of an oversized numeric move).
         *
         * @param out    the record buffer being populated
         * @param offset the zero-based start position of the field
         * @param len    the fixed field width in digits
         * @param value  the numeric value to format
         */
        private static void putNum(final byte[] out, final int offset, final int len, final long value) {
            String digits = String.format("%0" + len + "d", value);
            if (digits.length() > len) {
                digits = digits.substring(digits.length() - len);
            }
            final byte[] bytes = digits.getBytes(RECORD_CHARSET);
            System.arraycopy(bytes, 0, out, offset, len);
        }

        /**
         * Writes an {@code S9(09)V99} signed amount as an 11-byte zoned-decimal field with a trailing
         * sign overpunch on the units digit. The amount is rounded to scale 2
         * ({@link RoundingMode#HALF_EVEN}), converted to its unscaled cents magnitude, and formatted as
         * eleven digits (low-order eleven retained if longer); the units digit is then replaced by its
         * overpunch character (positive or negative table).
         *
         * @param out    the record buffer being populated
         * @param offset the zero-based start position of the 11-byte field
         * @param amount the signed amount to encode
         */
        private static void putSignedZoned(final byte[] out, final int offset, final BigDecimal amount) {
            final BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
            final BigInteger unscaledAbs = scaled.abs().movePointRight(AMOUNT_SCALE).toBigInteger();
            String digits = String.format("%011d", unscaledAbs);
            if (digits.length() > 11) {
                digits = digits.substring(digits.length() - 11);
            }
            final String head = digits.substring(0, 10);
            final int unitsDigit = digits.charAt(10) - '0';
            final char[] table = scaled.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
            final String field = head + table[unitsDigit];
            final byte[] bytes = field.getBytes(RECORD_CHARSET);
            System.arraycopy(bytes, 0, out, offset, 11);
        }
    }
}

