package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.InterestCalculationProcessor;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
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

/**
 * Spring Batch re-host of the mainframe monthly interest calculator
 * {@code app/jcl/INTCALC.jcl} ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}) driving COBOL
 * program {@code app/cbl/CBACT04C.cbl} (source commit {@code 27d6c6f}; REFERENCE ONLY — no
 * COBOL/JCL is copied, lineage is preserved by the commit SHA).
 *
 * <p>The original program reads the transaction-category-balance file ({@code TCATBALF}, opened
 * INPUT — never written) in ascending account/type/category order and performs two strictly
 * decoupled effects that this job reproduces without conflating them:</p>
 * <ol>
 *   <li><b>Interest-transaction emission</b> ({@code 1300-COMPUTE-INTEREST} /
 *       {@code 1300-B-WRITE-TX}): for every balance row whose disclosure-group rate is non-zero,
 *       one interest {@link Transaction} ({@code TRAN-TYPE-CD='01'}, {@code TRAN-CAT-CD='05'},
 *       {@code TRAN-SOURCE='System'}) is written to the {@code TRANSACT} DD, which
 *       {@code INTCALC.jcl} routes to the {@code SYSTRAN(+1)} generation-data group
 *       ({@code RECFM=F LRECL=350}) — a staging dataset, <em>not</em> the master transaction
 *       file. This re-platforms (decision D-003) to a versioned S3 object holding byte-exact
 *       350-byte {@code CVTRA05Y} records.</li>
 *   <li><b>Account control-break roll-up</b> ({@code 1050-UPDATE-ACCOUNT}): the per-account total
 *       interest ({@code WS-TOTAL-INT}) is added to {@code ACCT-CURR-BAL} and both current-cycle
 *       credit and debit totals are zeroed, then the account is rewritten. This happens when the
 *       account key changes and for the final account at end-of-file.</li>
 * </ol>
 *
 * <p><b>Why the writer is job-local.</b> The shared {@code TransactionWriter} always upserts the
 * {@link TransactionCategoryBalance} and adds each amount to the account's cycle credit/debit; both
 * are wrong here (interest never mutates {@code TCATBAL}, and the cycle totals must be zeroed, not
 * incremented). Reusing it would regress behavioural parity (AAP §0.8.1), so this job defines its
 * own {@link InterestTransactionWriter}. The rationale is recorded in {@code DECISION_LOG.md}, not
 * in code comments.</p>
 *
 * <p><b>Double-load guard.</b> Interest transactions are staged to S3 {@code SYSTRAN} here and reach
 * the master {@code transaction} table only through {@code CombineTransactionsJob} (which merges
 * {@code TRANSACT.BKUP} with {@code SYSTRAN}). This job therefore never inserts interest rows into
 * the {@code transaction} table, exactly mirroring the COBOL pipeline.</p>
 *
 * <p><b>Job parameter contract.</b> The orchestrator must launch {@link #JOB_NAME} with a string
 * job parameter named {@value #PARM_DATE_KEY} (the COBOL {@code PARM='2022071800'}): a ten-character
 * run date that forms the first ten characters of each interest transaction id. Jobs are not
 * auto-run ({@code spring.batch.job.enabled=false}); a pipeline orchestrator launches this job.</p>
 */
@Configuration
public class InterestCalculationJob {

    /** Canonical job name; referenced by the pipeline orchestrator to launch this job. */
    public static final String JOB_NAME = "interestCalculationJob";

    /** Canonical step name for the single chunk-oriented interest-calculation step. */
    public static final String STEP_NAME = "interestCalculationStep";

    /** Bean name of the category-balance reader; also its {@link ExecutionContext} key prefix. */
    public static final String READER_BEAN_NAME = "interestCategoryBalanceReader";

    /**
     * Job-parameter key carrying the COBOL {@code PARM='2022071800'} run date. The orchestrator
     * supplies it; the (locked) {@link InterestCalculationProcessor} late-binds it as the
     * transaction-id prefix.
     */
    public static final String PARM_DATE_KEY = "parmDate";

    /** Chunk size: items processed per chunk transaction (and per S3 staging flush boundary). */
    public static final int CHUNK_SIZE = 100;

    /** Page size of the paged repository scan; matches the sibling readers' convention. */
    static final int PAGE_SIZE = 100;

    /** Sort path for the account id of the embedded key; account-major order drives the control break. */
    private static final String SORT_ACCOUNT_ID = "id.accountId";

    /** Sort path for the transaction type code of the embedded key. */
    private static final String SORT_TYPE_CODE = "id.typeCode";

    /** Sort path for the transaction category code of the embedded key. */
    private static final String SORT_CATEGORY_CODE = "id.categoryCode";

    /**
     * Paged, sorted reader over the transaction-category-balance table (the {@code TCATBALF}
     * sequential scan). The three-level, account-major sort ({@code id.accountId} then
     * {@code id.typeCode} then {@code id.categoryCode}, all ascending) is mandatory: the writer's
     * per-account control break relies on every row of one account arriving contiguously. The
     * reader is {@link StepScope step-scoped} so each step execution gets a fresh, restart-aware
     * instance.
     *
     * @param transactionCategoryBalanceRepository repository whose inherited
     *                                              {@code findAll(Pageable)} backs the paged scan
     * @return the configured category-balance reader
     */
    @Bean(READER_BEAN_NAME)
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> interestCategoryBalanceReader(
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository) {
        Map<String, Sort.Direction> sorts = new LinkedHashMap<>();
        sorts.put(SORT_ACCOUNT_ID, Sort.Direction.ASC);
        sorts.put(SORT_TYPE_CODE, Sort.Direction.ASC);
        sorts.put(SORT_CATEGORY_CODE, Sort.Direction.ASC);
        return new RepositoryItemReaderBuilder<TransactionCategoryBalance>()
                .name(READER_BEAN_NAME)
                .repository(transactionCategoryBalanceRepository)
                .methodName("findAll")
                .pageSize(PAGE_SIZE)
                .sorts(sorts)
                .build();
    }

    /**
     * The job-local interest writer that stages interest transactions to S3 {@code SYSTRAN} and
     * performs the account control-break roll-up. The S3 endpoint, region, and credentials are
     * resolved by {@link com.carddemo.config.AwsConfig} from configuration (LocalStack for the
     * {@code local}/{@code test} profiles), never here; only the resource names are bound, so they
     * stay externalized.
     *
     * @param accountRepository            repository for the versioned account roll-up update
     * @param cardCrossReferenceRepository repository resolving the owning account id from a card
     * @param s3Client                     the synchronous S3 client bean (from {@code AwsConfig})
     * @param stagingBucket                S3 bucket holding the {@code SYSTRAN} staging object
     * @param systranKey                   S3 object key of the {@code SYSTRAN} staging object
     * @return the configured interest writer
     */
    @Bean
    public InterestTransactionWriter interestTransactionWriter(
            AccountRepository accountRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            S3Client s3Client,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String stagingBucket,
            @Value("${carddemo.batch.interest.systran-key:SYSTRAN}") String systranKey) {
        return new InterestTransactionWriter(
                accountRepository, cardCrossReferenceRepository, s3Client, stagingBucket, systranKey);
    }

    /**
     * The single chunk-oriented step: read each category balance, compute its interest transaction
     * (the locked processor filters zero-rate rows by returning {@code null}), then stage the
     * transaction and roll the per-account interest into the account. The writer is registered as a
     * stream ({@code .stream(...)}) so its {@code open}/{@code update}/{@code close} lifecycle runs:
     * the S3 buffer is opened/flushed there and the account roll-up state is made restart-safe via
     * the {@link ExecutionContext}. The account roll-up save participates in the chunk transaction
     * owned by the supplied transaction manager (the CICS {@code SYNCPOINT}/batch-commit boundary).
     *
     * @param jobRepository                 the Spring Batch job repository (Boot auto-configured)
     * @param transactionManager            the platform transaction manager (Boot auto-configured)
     * @param interestCategoryBalanceReader the category-balance reader (resolved by name)
     * @param interestCalculationProcessor  the locked per-record interest processor
     * @param interestTransactionWriter     the job-local interest writer (also the step stream)
     * @return the configured interest-calculation step
     */
    @Bean
    public Step interestCalculationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier(READER_BEAN_NAME)
            RepositoryItemReader<TransactionCategoryBalance> interestCategoryBalanceReader,
            InterestCalculationProcessor interestCalculationProcessor,
            InterestTransactionWriter interestTransactionWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(interestCategoryBalanceReader)
                .processor(interestCalculationProcessor)
                .writer(interestTransactionWriter)
                .stream(interestTransactionWriter)
                .build();
    }

    /**
     * The single-step interest-calculation job. Launched explicitly by the pipeline orchestrator
     * with the {@value #PARM_DATE_KEY} job parameter; not auto-run.
     *
     * @param jobRepository           the Spring Batch job repository (Boot auto-configured)
     * @param interestCalculationStep the interest-calculation step bean (injected by name)
     * @return the configured interest-calculation job, registered under {@link #JOB_NAME}
     */
    @Bean
    public Job interestCalculationJob(JobRepository jobRepository, Step interestCalculationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Job-local {@link ItemStreamWriter} faithfully reproducing the two decoupled effects of COBOL
     * {@code CBACT04C} that the shared {@code TransactionWriter} cannot (it would mutate
     * {@code TCATBAL} and increment, rather than zero, the account cycle totals).
     *
     * <p><b>Effect 1 — S3 {@code SYSTRAN} staging.</b> Each interest transaction is serialized to a
     * byte-exact 350-byte {@code CVTRA05Y} record (no delimiters; trailing sign overpunch on the
     * amount; {@link StandardCharsets#ISO_8859_1} so the fixed offsets stay byte-accurate),
     * buffered in {@link #open(ExecutionContext)} and flushed as a single S3 object in
     * {@link #close()}. The layout is identical to the one {@code CombineTransactionsJob} parses, so
     * the staged object honours the {@code SYSTRAN} contract.</p>
     *
     * <p><b>Effect 2 — account control-break roll-up.</b> Interest amounts are summed per account;
     * when the account changes (and once more in {@link #close()} for the final account) the account
     * balance is increased by the accumulated interest and both current-cycle credit and debit are
     * zeroed. The owning account id is resolved from the transaction's card number through the card
     * cross-reference (cached per card). The {@link TransactionCategoryBalance} table is never
     * written, and interest rows are never inserted into the {@code transaction} table here.</p>
     *
     * <p><b>Restart safety.</b> The account control-break state ({@code lastAccountId} and the
     * running accumulator) is persisted to the {@link ExecutionContext} on every chunk boundary and
     * restored in {@link #open(ExecutionContext)}, so a restart resumes the roll-up correctly. The
     * S3 staging buffer is per-run (it is not persisted to the execution context); the design
     * assumes a fresh execution for staging, matching the sibling staging writer. The writer assumes
     * single-threaded, sequential chunk processing, matching the COBOL batch.</p>
     */
    public static class InterestTransactionWriter implements ItemStreamWriter<Transaction> {

        private static final Logger LOGGER = LoggerFactory.getLogger(InterestTransactionWriter.class);

        /** Fixed length of a {@code TRAN-RECORD} (copybook {@code CVTRA05Y}), in bytes. */
        private static final int TRAN_RECORD_LENGTH = 350;

        /** Implied decimal positions of the {@code TRAN-AMT} field ({@code PIC S9(09)V99}). */
        private static final int AMOUNT_SCALE = 2;

        /** Byte length of the zoned-decimal {@code TRAN-AMT S9(09)V99} field. */
        private static final int AMOUNT_FIELD_LENGTH = 11;

        /** Content type recorded on the binary, fixed-width {@code SYSTRAN} S3 object. */
        private static final String CONTENT_TYPE = "application/octet-stream";

        // CVTRA05Y fixed-width field offsets and lengths (0-based), mirroring the 350-byte layout.
        private static final int TRAN_ID_OFFSET = 0;
        private static final int TRAN_ID_LENGTH = 16;
        private static final int TYPE_CD_OFFSET = 16;
        private static final int TYPE_CD_LENGTH = 2;
        private static final int CAT_CD_OFFSET = 18;
        private static final int CAT_CD_LENGTH = 4;
        private static final int SOURCE_OFFSET = 22;
        private static final int SOURCE_LENGTH = 10;
        private static final int DESC_OFFSET = 32;
        private static final int DESC_LENGTH = 100;
        private static final int AMT_OFFSET = 132;
        private static final int MERCHANT_ID_OFFSET = 143;
        private static final int MERCHANT_ID_LENGTH = 9;
        private static final int MERCHANT_NAME_OFFSET = 152;
        private static final int MERCHANT_NAME_LENGTH = 50;
        private static final int MERCHANT_CITY_OFFSET = 202;
        private static final int MERCHANT_CITY_LENGTH = 50;
        private static final int MERCHANT_ZIP_OFFSET = 252;
        private static final int MERCHANT_ZIP_LENGTH = 10;
        private static final int CARD_NUM_OFFSET = 262;
        private static final int CARD_NUM_LENGTH = 16;
        private static final int ORIG_TS_OFFSET = 278;
        private static final int ORIG_TS_LENGTH = 26;
        private static final int PROC_TS_OFFSET = 304;
        private static final int PROC_TS_LENGTH = 26;

        /**
         * Trailing-overpunch characters for a non-negative units digit, indexed by
         * {@code digit - '0'}: digit 0 maps to the open-brace character and digits 1 through 9 map
         * to the letters A through I (ASCII zoned-decimal representation).
         */
        private static final char[] POSITIVE_OVERPUNCH =
                {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

        /**
         * Trailing-overpunch characters for a negative units digit, indexed by {@code digit - '0'}:
         * digit 0 maps to the close-brace character and digits 1 through 9 map to the letters J
         * through R.
         */
        private static final char[] NEGATIVE_OVERPUNCH =
                {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

        /** Execution-context key for the last seen account id (restart-safe control break). */
        private static final String CTX_LAST_ACCOUNT_ID = "interest.writer.lastAccountId";

        /** Execution-context key for the running per-account interest accumulator. */
        private static final String CTX_ACCUMULATED_INTEREST = "interest.writer.accumulatedInterest";

        private final AccountRepository accountRepository;
        private final CardCrossReferenceRepository cardCrossReferenceRepository;
        private final S3Client s3Client;
        private final String stagingBucket;
        private final String systranKey;

        /** Per-run staging buffer; created in {@link #open} and released in {@link #close}. */
        private ByteArrayOutputStream stageBuffer;

        /** Per-run card-number to account-id cache, avoiding repeated cross-reference reads. */
        private Map<String, Long> cardToAccountCache;

        /** Account id of the most recently processed interest transaction, or {@code null}. */
        private Long lastAccountId;

        /** Running sum of interest amounts for {@link #lastAccountId} (COBOL {@code WS-TOTAL-INT}). */
        private BigDecimal accumulatedInterest;

        /**
         * Creates the interest writer with its collaborators and externalized resource names.
         *
         * @param accountRepository            repository for the versioned account roll-up update
         * @param cardCrossReferenceRepository repository resolving the owning account id from a card
         * @param s3Client                     the synchronous S3 client bean
         * @param stagingBucket                S3 bucket holding the {@code SYSTRAN} staging object
         * @param systranKey                   S3 object key of the {@code SYSTRAN} staging object
         */
        public InterestTransactionWriter(
                AccountRepository accountRepository,
                CardCrossReferenceRepository cardCrossReferenceRepository,
                S3Client s3Client,
                String stagingBucket,
                String systranKey) {
            this.accountRepository = accountRepository;
            this.cardCrossReferenceRepository = cardCrossReferenceRepository;
            this.s3Client = s3Client;
            this.stagingBucket = stagingBucket;
            this.systranKey = systranKey;
        }

        /**
         * Initializes the per-run staging buffer and card cache, and restores the account
         * control-break state from the execution context so a restart resumes the roll-up exactly
         * where the last committed chunk left off. On a fresh run no state is present, so the
         * accumulator starts at zero with no last account.
         *
         * @param executionContext the step execution context (carries restart state)
         */
        @Override
        public void open(ExecutionContext executionContext) {
            this.stageBuffer = new ByteArrayOutputStream();
            this.cardToAccountCache = new HashMap<>();
            this.lastAccountId = executionContext.containsKey(CTX_LAST_ACCOUNT_ID)
                    ? executionContext.getLong(CTX_LAST_ACCOUNT_ID)
                    : null;
            this.accumulatedInterest = executionContext.containsKey(CTX_ACCUMULATED_INTEREST)
                    ? new BigDecimal(executionContext.getString(CTX_ACCUMULATED_INTEREST))
                    : BigDecimal.ZERO;
        }

        /**
         * Persists the account control-break state at each chunk boundary. Because Spring Batch
         * commits this update in the same transaction as the chunk's writes, the persisted state and
         * any account roll-up performed in {@link #write(Chunk)} commit atomically.
         *
         * @param executionContext the step execution context to update
         */
        @Override
        public void update(ExecutionContext executionContext) {
            if (lastAccountId != null) {
                executionContext.putLong(CTX_LAST_ACCOUNT_ID, lastAccountId);
            }
            executionContext.putString(CTX_ACCUMULATED_INTEREST, accumulatedInterest.toString());
        }

        /**
         * Stages each interest transaction to the S3 buffer and folds its amount into the owning
         * account's running total, flushing the previous account's roll-up whenever the account
         * changes. Any exception propagates and rolls back the surrounding chunk transaction.
         *
         * @param chunk the chunk of interest transactions produced by the processor
         */
        @Override
        public void write(Chunk<? extends Transaction> chunk) {
            for (Transaction interestTransaction : chunk) {
                stageBuffer.writeBytes(buildTranRecord(interestTransaction));
                accumulateForAccount(interestTransaction);
            }
        }

        /**
         * Adds the transaction's interest amount to the current account's accumulator, first rolling
         * up the previous account when the account key changes (COBOL control break:
         * {@code TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM}). Since items arrive account-major, a simple
         * "current differs from last" comparison is sufficient.
         *
         * @param interestTransaction the interest transaction whose amount is accumulated
         */
        private void accumulateForAccount(Transaction interestTransaction) {
            Long accountId = resolveAccountId(interestTransaction.getTranCardNum());
            if (lastAccountId != null && !lastAccountId.equals(accountId)) {
                applyAccumulatedInterest(lastAccountId);
                accumulatedInterest = BigDecimal.ZERO;
            }
            lastAccountId = accountId;
            accumulatedInterest = accumulatedInterest.add(interestTransaction.getTranAmt());
        }

        /**
         * Performs the COBOL {@code 1050-UPDATE-ACCOUNT} roll-up for one account: add the accumulated
         * interest to the current balance and zero both current-cycle credit and debit totals, then
         * save (the {@code @Version} field guards the update). The transaction-category-balance table
         * is intentionally left untouched.
         *
         * @param accountId the account to roll up
         */
        private void applyAccumulatedInterest(Long accountId) {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account not found for interest roll-up: " + accountId));
            account.setAcctCurrBal(account.getAcctCurrBal().add(accumulatedInterest));
            account.setAcctCurrCycCredit(BigDecimal.ZERO);
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            accountRepository.save(account);
            LOGGER.debug("Applied interest {} to account {} and zeroed cycle totals",
                    accumulatedInterest, accountId);
        }

        /**
         * Resolves the owning account id for a card number through the card cross-reference,
         * memoizing the result so repeated transactions for the same card avoid a second read. An
         * absent cross-reference is a fatal data error, mirroring the COBOL abend on a failed XREF
         * read.
         *
         * @param cardNumber the transaction's card number (COBOL {@code TRAN-CARD-NUM})
         * @return the cross-referenced account id (COBOL {@code XREF-ACCT-ID})
         */
        private Long resolveAccountId(String cardNumber) {
            Long cached = cardToAccountCache.get(cardNumber);
            if (cached != null) {
                return cached;
            }
            Long resolved = cardCrossReferenceRepository.findById(cardNumber)
                    .map(xref -> xref.getXrefAcctId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Card cross-reference not found for card number: " + cardNumber));
            cardToAccountCache.put(cardNumber, resolved);
            return resolved;
        }

        /**
         * Rolls up the final account (COBOL end-of-file {@code 1050-UPDATE-ACCOUNT}) and flushes the
         * accumulated 350-byte records to the single {@code SYSTRAN} S3 object, then releases all
         * per-run state. The final roll-up runs in its own repository transaction because the step's
         * chunk transactions have already completed by the time {@code close} is invoked.
         */
        @Override
        public void close() {
            try {
                if (lastAccountId != null) {
                    applyAccumulatedInterest(lastAccountId);
                }
                flushStagedRecords();
            } finally {
                this.stageBuffer = null;
                this.cardToAccountCache = null;
                this.lastAccountId = null;
                this.accumulatedInterest = BigDecimal.ZERO;
            }
        }

        /**
         * Uploads the buffered interest-transaction records as a single {@code SYSTRAN} S3 object.
         * Nothing is written when no interest transaction was staged.
         *
         * @throws ItemStreamException if the S3 put fails
         */
        private void flushStagedRecords() {
            if (stageBuffer == null || stageBuffer.size() == 0) {
                return;
            }
            byte[] payload = stageBuffer.toByteArray();
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(stagingBucket)
                                .key(systranKey)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
            } catch (SdkException e) {
                throw new ItemStreamException(
                        "Failed to stage interest transactions to S3 " + stagingBucket + "/" + systranKey, e);
            }
        }

        /**
         * Builds one byte-exact 350-byte {@code TRAN-RECORD} (copybook {@code CVTRA05Y}). The buffer
         * is space-filled first, so any short field is right-padded with spaces and any over-long
         * field is truncated to its fixed width; the trailing 20-byte filler stays spaces.
         *
         * @param t the interest transaction to serialize
         * @return a new array of exactly 350 bytes
         */
        private byte[] buildTranRecord(Transaction t) {
            byte[] out = new byte[TRAN_RECORD_LENGTH];
            Arrays.fill(out, (byte) ' ');

            putAlpha(out, TRAN_ID_OFFSET, TRAN_ID_LENGTH, t.getTranId());
            putAlpha(out, TYPE_CD_OFFSET, TYPE_CD_LENGTH, t.getTranTypeCd());
            putNum(out, CAT_CD_OFFSET, CAT_CD_LENGTH, t.getTranCatCd());
            putAlpha(out, SOURCE_OFFSET, SOURCE_LENGTH, t.getTranSource());
            putAlpha(out, DESC_OFFSET, DESC_LENGTH, t.getTranDesc());
            putSignedZoned(out, AMT_OFFSET, t.getTranAmt());
            putNum(out, MERCHANT_ID_OFFSET, MERCHANT_ID_LENGTH, t.getTranMerchantId());
            putAlpha(out, MERCHANT_NAME_OFFSET, MERCHANT_NAME_LENGTH, t.getTranMerchantName());
            putAlpha(out, MERCHANT_CITY_OFFSET, MERCHANT_CITY_LENGTH, t.getTranMerchantCity());
            putAlpha(out, MERCHANT_ZIP_OFFSET, MERCHANT_ZIP_LENGTH, t.getTranMerchantZip());
            putAlpha(out, CARD_NUM_OFFSET, CARD_NUM_LENGTH, t.getTranCardNum());
            putAlpha(out, ORIG_TS_OFFSET, ORIG_TS_LENGTH, t.getTranOrigTs());
            putAlpha(out, PROC_TS_OFFSET, PROC_TS_LENGTH, t.getTranProcTs());

            return out;
        }

        /**
         * Writes an alphanumeric value left-justified into a fixed-width slot, truncating to the
         * slot length and leaving any unused trailing bytes as the pre-filled spaces.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the slot
         * @param len    the fixed width of the slot
         * @param value  the value to write; {@code null} is treated as empty
         */
        private void putAlpha(byte[] out, int offset, int len, String value) {
            String safe = value == null ? "" : value;
            byte[] bytes = safe.getBytes(StandardCharsets.ISO_8859_1);
            int n = Math.min(len, bytes.length);
            System.arraycopy(bytes, 0, out, offset, n);
        }

        /**
         * Writes an unsigned numeric value as a zero-padded, right-justified field, keeping only the
         * low-order {@code len} digits if the formatted value is longer than the slot.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the slot
         * @param len    the fixed width of the slot
         * @param value  the numeric value to write
         */
        private void putNum(byte[] out, int offset, int len, long value) {
            String formatted = String.format("%0" + len + "d", value);
            if (formatted.length() > len) {
                formatted = formatted.substring(formatted.length() - len);
            }
            byte[] bytes = formatted.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(bytes, 0, out, offset, len);
        }

        /**
         * Writes a signed {@code S9(09)V99} value as an 11-byte zoned-decimal field with a trailing
         * sign overpunch on the units digit (the byte-exact COBOL {@code DISPLAY} representation).
         * The value is scaled to two fraction digits ({@link RoundingMode#HALF_EVEN}), reduced to its
         * eleven low-order unscaled digits, and the final digit is replaced by the overpunch
         * character for its sign.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the 11-byte slot
         * @param amount the signed monetary amount to encode
         */
        private void putSignedZoned(byte[] out, int offset, BigDecimal amount) {
            BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
            BigInteger unscaledAbs = scaled.abs().movePointRight(AMOUNT_SCALE).toBigInteger();
            String digits = String.format("%0" + AMOUNT_FIELD_LENGTH + "d", unscaledAbs);
            if (digits.length() > AMOUNT_FIELD_LENGTH) {
                digits = digits.substring(digits.length() - AMOUNT_FIELD_LENGTH);
            }
            String head = digits.substring(0, AMOUNT_FIELD_LENGTH - 1);
            int unitsDigit = digits.charAt(AMOUNT_FIELD_LENGTH - 1) - '0';
            char overpunched = scaled.signum() < 0
                    ? NEGATIVE_OVERPUNCH[unitsDigit]
                    : POSITIVE_OVERPUNCH[unitsDigit];
            byte[] bytes = (head + overpunched).getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(bytes, 0, out, offset, bytes.length);
        }
    }
}
