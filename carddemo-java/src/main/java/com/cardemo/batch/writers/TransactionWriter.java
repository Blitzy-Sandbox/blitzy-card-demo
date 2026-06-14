package com.cardemo.batch.writers;

import com.cardemo.config.AwsConfig;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Batch {@link ItemWriter} that persists the <strong>accepted/posted</strong> daily
 * transactions of the Daily Transaction Posting job &mdash; the transactional core of the migrated
 * batch tier.
 *
 * <h2>COBOL provenance (read-only reference, never copied)</h2>
 * <p>This class is the Java translation of the <em>output side</em> of the legacy AWS CardDemo batch
 * program {@code app/cbl/CBTRN02C.cbl}, specifically paragraph {@code 2000-POST-TRANSACTION} and the
 * three sub-paragraphs it performs in strict order:</p>
 * <ol>
 *   <li>{@code 2700-UPDATE-TCATBAL} (&rarr; {@code 2700-A-CREATE-TCATBAL-REC} /
 *       {@code 2700-B-UPDATE-TCATBAL-REC}) &mdash; the category-balance read-then-write/rewrite;</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} &mdash; the account running- and cycle-balance update;</li>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} &mdash; the transaction-file insert.</li>
 * </ol>
 * <p>The upstream field mapping ({@code 2000-POST} {@code MOVE}s) and the {@code 1500-VALIDATE-TRAN}
 * cascade are owned by {@code TransactionPostingProcessor}; the routing branch
 * ({@code IF WS-VALIDATION-FAIL-REASON = 0}) is reproduced by {@link PostedTransactionResult}. The
 * <em>reject</em> path ({@code 2500-WRITE-REJECT-REC}) is owned by a sibling {@code RejectWriter}.
 * This writer is therefore the <strong>accepted-only</strong> sink and processes only items for which
 * {@link PostedTransactionResult#isAccepted()} is {@code true}; any non-accepted item that reaches it
 * is skipped defensively (its {@link PostedTransactionResult#transaction()} is {@code null}).</p>
 *
 * <p>Traceability is to the frozen COBOL baseline at commit SHA {@code 27d6c6f} only; the COBOL source
 * is read-only reference material and is <strong>never copied</strong> into this repository.</p>
 *
 * <h2>Technology substitutions (documented per AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM {@code TCATBALF} KSDS read-then-{@code WRITE}/{@code REWRITE}</strong>
 *       ({@code 2700}) &rarr; {@link TransactionCategoryBalanceRepository#findById(Object)} +
 *       {@code save(...)} upsert. The COBOL {@code FILE STATUS} branch maps directly:
 *       {@code '23'} (INVALID KEY, not found) &rarr; insert; {@code '00'} (found) &rarr; update.</li>
 *   <li><strong>VSAM {@code ACCTDAT} KSDS {@code REWRITE}</strong> ({@code 2800}) &rarr;
 *       {@link AccountRepository#findById(Object)} + {@code save(...)}, optimistic-locked via the
 *       {@code Account} {@code @Version} column.</li>
 *   <li><strong>VSAM {@code TRANSACT} KSDS {@code WRITE}</strong> ({@code 2900}) &rarr;
 *       {@link TransactionRepository#saveAll(Iterable)} (one bulk JPA insert per chunk).</li>
 *   <li><strong>CICS/batch implicit unit-of-work</strong> &rarr; the Spring Batch
 *       <em>chunk-level transaction</em>. The TCATBAL upsert, the account update and the transaction
 *       insert for the whole chunk commit or roll back atomically (AAP &sect;0.7.5). Any thrown
 *       exception (missing account, optimistic-lock failure, constraint violation) rolls the chunk
 *       back &mdash; faithful to the COBOL {@code ABEND}-on-error behaviour of {@code 2700}/{@code 2900}.</li>
 *   <li><strong>(NEW) GDG generations &rarr; S3 versioned objects</strong> (AAP &sect;0.1.2 /
 *       &sect;0.7.7). After the database writes succeed, the chunk's posted records are backed up to
 *       AWS S3 under a deterministic, generation-prefixed key. This is the only genuinely new
 *       behaviour; it is isolated to {@link #backupToS3(List)} and is config-driven and
 *       LocalStack-verifiable (zero live AWS).</li>
 * </ul>
 *
 * <h2>Decimal precision (AAP &sect;0.7.3)</h2>
 * <p>Every monetary value is a {@link BigDecimal}; there is <strong>no</strong> {@code float}/
 * {@code double} anywhere in this class. Accumulation uses {@link BigDecimal#add(BigDecimal)} and the
 * cycle credit/debit split uses {@link BigDecimal#signum()} (never {@code equals}). Scales are
 * preserved exactly: {@code Transaction.tranAmt} and {@code TransactionCategoryBalance.tranCatBal} are
 * scale&nbsp;2 and {@code Account} balances are scale&nbsp;2; no arithmetic here changes scale.</p>
 *
 * <h2>Persistence-context accumulation (correctness)</h2>
 * <p>Because the whole chunk runs in one transaction and therefore one {@code EntityManager}, a
 * repeated {@code findById} of the <em>same</em> account or the <em>same</em> category balance within
 * the chunk returns the already-managed (already-updated) instance from the first-level cache. Items
 * are processed in the chunk's natural order, so sequential read-modify-write accumulation across
 * multiple records that hit the same key matches the COBOL record-by-record posting exactly. No manual
 * {@code flush}/{@code clear} is performed (it would defeat this).</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The writer is stateless apart from its injected, immutable collaborators (three repositories, an
 * {@link S3Template}, the {@link AwsConfig.AwsResourceProperties} name holder and a {@link Clock}), so a
 * single Spring-managed singleton is safe to share across batch threads; all mutable state is confined
 * to {@link #write(Chunk)} locals.</p>
 *
 * @see PostedTransactionResult
 * @see ItemWriter
 * @see TransactionRepository
 * @see TransactionCategoryBalanceRepository
 * @see AccountRepository
 */
@Component("transactionWriter")
public class TransactionWriter implements ItemWriter<PostedTransactionResult> {

    /**
     * Date/time portion of the DB2 timestamp format produced by COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}: {@code yyyy-MM-dd-HH.mm.ss.} (hyphen between date and time,
     * dot separators within the time, up to and including the dot before the fractional seconds).
     */
    // COBOL: 2000 Z-GET-DB2-FORMAT-TIMESTAMP layout EEEE-MM-DD-UU.MM.SS. + fraction.
    private static final DateTimeFormatter DB2_TIMESTAMP_BASE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.");

    /**
     * Full 26-character DB2 timestamp pattern (six fractional-second digits) used to parse the string
     * built by {@link #currentProcessingTimestamp()} back into a {@link LocalDateTime} carrying exactly
     * the COBOL hundredths-of-second precision (remaining digits zeroed).
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /** Generation-prefix date pattern for the S3 backup key (emulates a GDG generation bucket). */
    private static final DateTimeFormatter S3_GENERATION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Fixed key prefix under which posted-stream backups are stored (GDG &rarr; S3, decision D-003). */
    private static final String S3_KEY_PREFIX = "posted/";

    /** Suffix for the posted-stream backup object. */
    private static final String S3_OBJECT_SUFFIX = ".dat";

    /** Content type of the posted-stream backup object (plain text, decimal-faithful lines). */
    private static final String BACKUP_CONTENT_TYPE = "text/plain";

    /** Field delimiter for the minimal posted-stream backup serialization. */
    private static final char FIELD_DELIMITER = '|';

    /**
     * Transaction repository &mdash; JPA replacement for the {@code WRITE FD-TRANFILE-REC} of
     * {@code 2900-WRITE-TRANSACTION-FILE}. Typed {@code JpaRepository<Transaction, String>} (key
     * {@code TRAN-ID PIC X(16)}); the writer uses only the inherited {@code saveAll(...)}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Category-balance repository &mdash; JPA replacement for the {@code TCATBALF} read-then-write of
     * {@code 2700-UPDATE-TCATBAL}. Keyed by the composite {@link TransactionCategoryBalanceId}; the
     * writer uses the inherited {@code findById(...)} and {@code save(...)}.
     */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * Account repository &mdash; JPA replacement for the {@code ACCTDAT} {@code REWRITE} of
     * {@code 2800-UPDATE-ACCOUNT-REC}. Typed {@code JpaRepository<Account, Long>} (key
     * {@code ACCT-ID PIC 9(11)}); {@code save(...)} honours the {@code Account} {@code @Version}
     * optimistic lock.
     */
    private final AccountRepository accountRepository;

    /**
     * Spring Cloud AWS S3 abstraction used by {@link #backupToS3(List)} for the (new) posted-stream
     * backup. Auto-configured by {@code spring-cloud-aws-starter-s3} from {@code spring.cloud.aws.*};
     * never hand-built here, so the endpoint can only ever resolve to LocalStack (zero live AWS).
     */
    private final S3Template s3Template;

    /**
     * Strongly-typed holder of the application-owned AWS resource <em>names</em>. The posted-stream
     * backup bucket is read from {@code getS3().getBatchOutputBucket()} ({@code carddemo-batch-output});
     * the bucket name is therefore resolved from configuration and never hardcoded (AAP &sect;0.7.7).
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Clock backing the {@code TRAN-PROC-TS} fallback stamp and the S3 generation-prefix date. Defaults
     * to the system zone in production and is injectable so tests pin a fixed instant and assert the
     * stamped timestamp and the deterministic S3 key. This is the {@code java.time} replacement for the
     * implicit system clock read by COBOL {@code FUNCTION CURRENT-DATE} in {@code Z-GET-DB2-FORMAT-TIMESTAMP}.
     */
    private final Clock clock;

    /**
     * Business-metrics facade (observability cross-cutting requirement, AAP §0.7.7).
     *
     * <p>Technology substitution: the legacy COBOL batch ({@code CBTRN02C}) emitted no telemetry. After
     * each chunk's accepted transactions are persisted, this writer increments
     * {@code carddemo.batch.records.processed} (tag {@code job=DailyTransactionPosting}) once per posted
     * record and records each posted amount onto {@code carddemo.transaction.amount.total} via
     * {@link MetricsConfig.BusinessMetrics}. Telemetry-only; it never alters the posting result.</p>
     */
    private final MetricsConfig.BusinessMetrics businessMetrics;

    /** Metric {@code job} tag value for this writer's batch job (matches {@code DailyTransactionPostingJob}). */
    private static final String JOB_NAME = "DailyTransactionPosting";

    /**
     * Production constructor used by Spring for component injection. Uses the system-default-zone
     * {@link Clock} so the {@code TRAN-PROC-TS} fallback and the S3 generation prefix are stamped from
     * the current time (faithful to {@code FUNCTION CURRENT-DATE}).
     *
     * @param transactionRepository     the transaction repository ({@code 2900} sink); must not be {@code null}
     * @param categoryBalanceRepository the category-balance repository ({@code 2700} upsert); must not be {@code null}
     * @param accountRepository         the account repository ({@code 2800} update); must not be {@code null}
     * @param s3Template                the auto-configured S3 template (posted-stream backup); must not be {@code null}
     * @param awsResourceProperties     the AWS resource-name holder (output-bucket source); must not be {@code null}
     * @param businessMetrics           the observability facade (AAP §0.7.7) onto which posted records
     *                                  and amounts are recorded; must not be {@code null}
     */
    @Autowired
    public TransactionWriter(final TransactionRepository transactionRepository,
                             final TransactionCategoryBalanceRepository categoryBalanceRepository,
                             final AccountRepository accountRepository,
                             final S3Template s3Template,
                             final AwsConfig.AwsResourceProperties awsResourceProperties,
                             final MetricsConfig.BusinessMetrics businessMetrics) {
        this(transactionRepository, categoryBalanceRepository, accountRepository,
                s3Template, awsResourceProperties, businessMetrics, Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor allowing a fixed {@link Clock} so the {@code TRAN-PROC-TS} fallback and
     * the S3 generation-prefix date are deterministic. Behaves identically to the production constructor
     * in every other respect.
     *
     * @param transactionRepository     the transaction repository; must not be {@code null}
     * @param categoryBalanceRepository the category-balance repository; must not be {@code null}
     * @param accountRepository         the account repository; must not be {@code null}
     * @param s3Template                the S3 template; must not be {@code null}
     * @param awsResourceProperties     the AWS resource-name holder; must not be {@code null}
     * @param businessMetrics           the observability facade (AAP §0.7.7); must not be {@code null}
     * @param clock                     the clock used for the timestamp fallback and S3 key; must not be {@code null}
     */
    public TransactionWriter(final TransactionRepository transactionRepository,
                             final TransactionCategoryBalanceRepository categoryBalanceRepository,
                             final AccountRepository accountRepository,
                             final S3Template s3Template,
                             final AwsConfig.AwsResourceProperties awsResourceProperties,
                             final MetricsConfig.BusinessMetrics businessMetrics,
                             final Clock clock) {
        this.transactionRepository = transactionRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.s3Template = s3Template;
        this.awsResourceProperties = awsResourceProperties;
        this.businessMetrics = businessMetrics;
        this.clock = clock;
    }

    /**
     * Posts every <strong>accepted</strong> transaction in the chunk, then backs the posted stream up to
     * S3. For each accepted item, in chunk order, the COBOL {@code 2000-POST-TRANSACTION} cascade is
     * reproduced in its exact sequence:
     * <ol>
     *   <li><strong>Step A</strong> &mdash; ensure the {@code TRAN-PROC-TS} processing timestamp is
     *       stamped ({@code 2000} L437-438);</li>
     *   <li><strong>Step B</strong> &mdash; upsert the transaction-category balance
     *       ({@code 2700-UPDATE-TCATBAL});</li>
     *   <li><strong>Step C</strong> &mdash; update the account running and cycle balances
     *       ({@code 2800-UPDATE-ACCOUNT-REC});</li>
     *   <li><strong>Step D</strong> &mdash; collect the transaction for the per-chunk bulk insert
     *       ({@code 2900-WRITE-TRANSACTION-FILE}).</li>
     * </ol>
     * <p>After the loop the collected transactions are inserted with a single
     * {@link TransactionRepository#saveAll(Iterable)} and, finally, <strong>Step E</strong> backs the
     * posted records up to S3 (the new GDG &rarr; S3 capability). Deferring the transaction inserts to a
     * single {@code saveAll} per chunk yields the identical committed end-state as the COBOL
     * record-by-record {@code WRITE}, because the entire chunk commits atomically in one transaction.</p>
     *
     * <p>Non-accepted items ({@link PostedTransactionResult#isAccepted()} {@code == false}; their
     * {@link PostedTransactionResult#transaction()} is {@code null}) are skipped defensively: the reject
     * path ({@code 2500-WRITE-REJECT-REC}) is owned by the sibling {@code RejectWriter}, so a misrouted
     * reject can neither post a balance nor raise a {@link NullPointerException} here.</p>
     *
     * <p><strong>Atomicity (AAP &sect;0.7.5).</strong> Spring Batch wraps this call in the chunk
     * transaction managed by the auto-configured JPA {@code PlatformTransactionManager}. The
     * {@code @Transactional} annotation (default {@code REQUIRED} propagation) participates in that same
     * transaction for clarity &mdash; it does <em>not</em> open a second transaction manager. If any
     * database operation throws (missing account, optimistic-lock failure, constraint violation), the
     * whole chunk rolls back, faithful to the COBOL {@code ABEND}-on-error behaviour.</p>
     *
     * @param chunk the chunk of posting results supplied by Spring Batch; never {@code null}
     */
    @Override
    @Transactional
    public void write(final Chunk<? extends PostedTransactionResult> chunk) {
        // Collected accepted transactions for the single per-chunk bulk insert (COBOL: 2900).
        final List<Transaction> postedThisChunk = new ArrayList<>(chunk.size());

        for (final PostedTransactionResult item : chunk) {
            // Accepted-only sink: skip nulls and rejected records (routed to RejectWriter, COBOL 2500).
            if (item == null || !item.isAccepted()) {
                continue;
            }

            final Transaction transaction = item.transaction();
            final Long accountId = resolveAccountId(item);
            final BigDecimal amount = transaction.getTranAmt();

            // Step A — COBOL: 2000 L437-438 (Z-GET-DB2-FORMAT-TIMESTAMP -> TRAN-PROC-TS).
            stampProcessingTimestampIfAbsent(transaction);

            // Step B — COBOL: 2700-UPDATE-TCATBAL (+ 2700-A create / 2700-B update).
            upsertCategoryBalance(accountId, transaction);

            // Step C — COBOL: 2800-UPDATE-ACCOUNT-REC.
            updateAccountBalance(accountId, amount);

            // Step D (collect) — COBOL: 2900-WRITE-TRANSACTION-FILE, deferred to one saveAll per chunk.
            postedThisChunk.add(transaction);
        }

        if (postedThisChunk.isEmpty()) {
            // Nothing accepted in this chunk: no DB write and no S3 backup to emit.
            return;
        }

        // Step D (persist) — COBOL: 2900. One bulk JPA insert replacing the per-record VSAM WRITE.
        transactionRepository.saveAll(postedThisChunk);

        // Observability (AAP §0.7.7): record one processed record and its amount per posted
        // transaction, AFTER the authoritative DB write succeeds (a failed saveAll propagates above
        // and these counters are not touched). Telemetry-only; never feeds back into posting logic.
        for (final Transaction posted : postedThisChunk) {
            businessMetrics.recordBatchRecordProcessed(JOB_NAME);
            businessMetrics.recordTransactionAmount(posted.getTranAmt());
        }

        // Step E — NEW (GDG -> S3): back up the posted stream AFTER the DB writes succeed.
        backupToS3(postedThisChunk);
    }

    /**
     * Resolves the account id used to key the category-balance upsert ({@code 2700}) and the account
     * update ({@code 2800}).
     *
     * <p>COBOL keys both updates on {@code XREF-ACCT-ID}, which {@code 1500-A-LOOKUP-XREF} resolved and
     * {@code 1500-B-LOOKUP-ACCT} used to read the {@code ACCOUNT-RECORD}. The upstream processor carried
     * that already-resolved {@link Account} on the accepted result, so the id is taken from it here
     * ({@code account().getAcctId()} equals {@code XREF-ACCT-ID}). The cross-reference is deliberately
     * <strong>not</strong> re-resolved in this writer &mdash; that is upstream validation work and
     * {@code CardCrossReferenceRepository} is intentionally not a dependency.</p>
     *
     * @param item an accepted posting result (its {@link PostedTransactionResult#account()} is non-null)
     * @return the resolved account id ({@code XREF-ACCT-ID})
     * @throws RecordNotFoundException if the accepted result carries no resolved account (cannot happen
     *                                 on the normal accept path; thrown so the chunk rolls back rather
     *                                 than posting an orphaned balance)
     */
    // COBOL: 2700/2800 key source — XREF-ACCT-ID resolved upstream (1500-A/1500-B), carried on the result.
    private Long resolveAccountId(final PostedTransactionResult item) {
        final Account resolved = item.account();
        if (resolved == null || resolved.getAcctId() == null) {
            throw new RecordNotFoundException(
                    "Accepted PostedTransactionResult carried no resolved account; cannot derive account id");
        }
        return resolved.getAcctId();
    }

    /**
     * Stamps the {@code TRAN-PROC-TS} processing timestamp only when it is unset.
     *
     * <p>COBOL {@code 2000-POST-TRANSACTION} performs {@code Z-GET-DB2-FORMAT-TIMESTAMP} and moves
     * {@code DB2-FORMAT-TS} into {@code TRAN-PROC-TS} at posting time. In the migrated pipeline the
     * upstream {@code TransactionPostingProcessor} (which performs the {@code 2000-POST} field mapping)
     * already stamps it, so this writer stamps only as a defensive fallback when the value is
     * {@code null}. That preserves the single posting-time stamp of {@code 2000-POST} and avoids
     * double-stamping a record that the processor already timestamped.</p>
     *
     * @param transaction the posted transaction whose {@code TRAN-PROC-TS} is ensured
     */
    // COBOL: 2000 L437-438 — PERFORM Z-GET-DB2-FORMAT-TIMESTAMP; MOVE DB2-FORMAT-TS TO TRAN-PROC-TS.
    private void stampProcessingTimestampIfAbsent(final Transaction transaction) {
        if (transaction.getTranProcTs() == null) {
            transaction.setTranProcTs(currentProcessingTimestamp());
        }
    }

    /**
     * Produces the {@code TRAN-PROC-TS} value, reproducing {@code Z-GET-DB2-FORMAT-TIMESTAMP} on the
     * current {@link #clock} time and truncating to the COBOL hundredths-of-second precision.
     *
     * <p>COBOL builds a 26-character DB2-format string ({@code yyyy-MM-dd-HH.mm.ss.} + 2-digit
     * hundredths + the literal {@code "0000"}) from {@code FUNCTION CURRENT-DATE}. Because the migrated
     * {@code Transaction.tranProcTs} is a {@link LocalDateTime}, this method renders that exact
     * 26-character string and parses it back, so the stored value carries precisely the COBOL hundredths
     * precision (remaining digits zeroed) and round-trips to the identical external rendering.</p>
     *
     * @return the processing timestamp truncated to COBOL hundredths precision
     */
    // COBOL: 2000 Z-GET-DB2-FORMAT-TIMESTAMP — COB-MIL hundredths + COB-REST '0000'.
    private LocalDateTime currentProcessingTimestamp() {
        final LocalDateTime now = LocalDateTime.now(clock);
        final int hundredths = now.getNano() / 10_000_000;
        final String db2 = DB2_TIMESTAMP_BASE.format(now) + String.format("%02d", hundredths) + "0000";
        return LocalDateTime.parse(db2, DB2_TIMESTAMP_FULL);
    }

    /**
     * Upserts the transaction-category balance for the posted transaction.
     *
     * <p>The composite key is {@code (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD)}. The COBOL
     * {@code FILE STATUS} branch of {@code 2700-UPDATE-TCATBAL} maps directly to a JPA upsert:</p>
     * <ul>
     *   <li><strong>Not found</strong> (INVALID KEY &rarr; status {@code '23'} &rarr;
     *       {@code 2700-A-CREATE-TCATBAL-REC}): create a new balance whose value is the transaction
     *       amount &mdash; COBOL {@code INITIALIZE} zeroes {@code TRAN-CAT-BAL} then
     *       {@code ADD DALYTRAN-AMT}, i.e. {@code balance = amount} &mdash; and {@code save} (insert).</li>
     *   <li><strong>Found</strong> (status {@code '00'} &rarr; {@code 2700-B-UPDATE-TCATBAL-REC}):
     *       {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} and {@code save} (update).</li>
     * </ul>
     * <p>Accumulation uses {@link BigDecimal#add(BigDecimal)} only and never changes scale (AAP
     * &sect;0.7.3). Within the chunk transaction, repeated keys resolve to the managed first-level-cache
     * instance, so multiple records hitting the same balance accumulate exactly as the COBOL
     * record-by-record read-modify-write.</p>
     *
     * @param accountId   the resolved account id ({@code XREF-ACCT-ID})
     * @param transaction the posted transaction supplying the type code, category code and amount
     */
    // COBOL: 2700-UPDATE-TCATBAL (+2700-A '23'->insert / 2700-B '00'->update).
    private void upsertCategoryBalance(final Long accountId, final Transaction transaction) {
        final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                accountId, transaction.getTranTypeCd(), transaction.getTranCatCd());
        final BigDecimal amount = transaction.getTranAmt();

        final Optional<TransactionCategoryBalance> existing = categoryBalanceRepository.findById(key);
        if (existing.isEmpty()) {
            // '23' INVALID KEY -> 2700-A: INITIALIZE (balance 0) then ADD DALYTRAN-AMT -> balance = amount.
            categoryBalanceRepository.save(new TransactionCategoryBalance(key, amount));
        } else {
            // '00' -> 2700-B: ADD DALYTRAN-AMT TO TRAN-CAT-BAL (scale preserved).
            final TransactionCategoryBalance balance = existing.get();
            balance.setTranCatBal(balance.getTranCatBal().add(amount));
            categoryBalanceRepository.save(balance);
        }
    }

    /**
     * Updates the account running balance and the signed cycle credit/debit, reproducing
     * {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * <p>The account is re-read by id (the same managed instance the processor resolved is returned from
     * the chunk's first-level cache). The COBOL {@code REWRITE ... INVALID KEY} can-not-happen path moves
     * reason {@code 109} and falls through to still write the transaction; under the transactional
     * integrity mandate (AAP &sect;0.7.5) the faithful Java behaviour is instead to <strong>throw</strong>
     * so the chunk rolls back rather than committing an orphaned transaction (decision recorded in the
     * DECISION_LOG; the legacy {@code 109} quirk is preserved as documentation only).</p>
     *
     * <p>Then, exactly as COBOL:</p>
     * <ul>
     *   <li>{@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} &mdash; <strong>unconditional</strong>;</li>
     *   <li>{@code IF DALYTRAN-AMT >= 0} add to {@code ACCT-CURR-CYC-CREDIT} else add to
     *       {@code ACCT-CURR-CYC-DEBIT}. For a negative amount the negative value is <em>added</em> to
     *       the debit field (the debit becomes more negative); the amount is never negated.</li>
     * </ul>
     * <p>The sign test uses {@link BigDecimal#signum()} ({@code >= 0} matches COBOL {@code >= 0}, so a
     * zero amount is credited), never {@code equals} (AAP &sect;0.7.3). {@code save} honours the
     * {@code Account} {@code @Version} optimistic lock; an
     * {@code ObjectOptimisticLockingFailureException} propagates and rolls the chunk back.</p>
     *
     * @param accountId the resolved account id ({@code XREF-ACCT-ID})
     * @param amount    the signed transaction amount ({@code DALYTRAN-AMT})
     * @throws RecordNotFoundException if the account row is absent (the COBOL {@code 109} INVALID KEY path)
     */
    // COBOL: 2800-UPDATE-ACCOUNT-REC (unconditional ACCT-CURR-BAL add; signed cycle split; 109 -> throw).
    private void updateAccountBalance(final Long accountId, final BigDecimal amount) {
        final Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException("Account", String.valueOf(accountId)));

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL (unconditional, COBOL L547).
        account.setAcctCurrBal(account.getAcctCurrBal().add(amount));

        // IF DALYTRAN-AMT >= 0 -> cycle credit ELSE -> cycle debit (negative added to debit; COBOL L548-552).
        if (amount.signum() >= 0) {
            account.setAcctCurrCycCredit(account.getAcctCurrCycCredit().add(amount));
        } else {
            account.setAcctCurrCycDebit(account.getAcctCurrCycDebit().add(amount));
        }

        // REWRITE FD-ACCTFILE-REC; @Version optimistic lock is honoured by save.
        accountRepository.save(account);
    }

    /**
     * Backs the chunk's posted records up to AWS S3 &mdash; the new capability that replaces the legacy
     * GDG generations of the posted transaction file (AAP &sect;0.1.2 / &sect;0.7.7).
     *
     * <p>The destination bucket is resolved from configuration
     * ({@code awsResourceProperties.getS3().getBatchOutputBucket()} &rarr; {@code carddemo-batch-output});
     * it is never hardcoded. The object key is deterministic and idempotent (see
     * {@link #buildBackupObjectKey(List)}): because S3 is <em>not</em> part of the JPA transaction, a
     * Spring Batch retry of the same chunk re-emits the same key and <strong>overwrites</strong> rather
     * than duplicating (an at-least-once backup with an idempotent key). The upload uses the
     * auto-configured {@link S3Template}, so the endpoint resolves only to LocalStack (zero live AWS).</p>
     *
     * <p>The backup is written <em>after</em> the database writes succeed within {@code write(...)}, so a
     * failed posting never leaves a backup of records that were not committed in the same call.</p>
     *
     * @param posted the accepted transactions persisted in this chunk (never empty)
     */
    // COBOL: GDG generations -> S3 versioned/generation-prefixed objects (new migration capability).
    private void backupToS3(final List<Transaction> posted) {
        final String bucket = awsResourceProperties.getS3().getBatchOutputBucket();
        final String objectKey = buildBackupObjectKey(posted);
        final byte[] payload = serializePostedRecords(posted).getBytes(StandardCharsets.UTF_8);
        final ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(BACKUP_CONTENT_TYPE)
                .contentLength((long) payload.length)
                .build();
        // ByteArrayInputStream is backed by the byte[] and holds no external resource, so it needs no
        // explicit close; S3Template fully consumes it during the upload.
        s3Template.upload(bucket, objectKey, new ByteArrayInputStream(payload), metadata);
    }

    /**
     * Builds the deterministic, generation-prefixed S3 key for a chunk's posted-stream backup:
     * {@code posted/<yyyyMMdd>/<firstTranId>-<lastTranId>.dat}.
     *
     * <p>The {@code <yyyyMMdd>} segment (from {@link #clock}) emulates a GDG generation bucket; the
     * first/last {@code TRAN-ID} bound makes the key stable for the same chunk content so a retry
     * overwrites rather than duplicates. Transaction ids are trimmed and any {@code '/'} is replaced so
     * the key stays a single, valid S3 path segment.</p>
     *
     * @param posted the chunk's posted transactions (never empty)
     * @return the deterministic S3 object key
     */
    private String buildBackupObjectKey(final List<Transaction> posted) {
        final String generation = LocalDate.now(clock).format(S3_GENERATION_DATE);
        final String firstId = safeKeySegment(posted.get(0).getTranId());
        final String lastId = safeKeySegment(posted.get(posted.size() - 1).getTranId());
        return S3_KEY_PREFIX + generation + "/" + firstId + "-" + lastId + S3_OBJECT_SUFFIX;
    }

    /**
     * Serializes the posted records into a minimal, decimal-faithful, pipe-delimited text payload &mdash;
     * one line per record with the posting fields
     * {@code tranId|typeCd|catCd|amount|cardNum|procTs}.
     *
     * <p>This is a compact backup of the posted stream (the GDG-replacement artifact), not a
     * re-implementation of any COBOL report. The monetary amount is rendered through
     * {@link #formatMoney(BigDecimal)} at scale&nbsp;2 with no {@code float}/{@code double}, preserving
     * decimal fidelity (AAP &sect;0.7.3). The full card number is included to preserve the legacy posted
     * record's field layout (AAP &sect;0.7.2); the artifact is written only to the LocalStack-backed
     * batch-output bucket.</p>
     *
     * @param posted the chunk's posted transactions (never empty)
     * @return the serialized payload
     */
    private String serializePostedRecords(final List<Transaction> posted) {
        final StringBuilder builder = new StringBuilder(posted.size() * 64);
        for (final Transaction transaction : posted) {
            builder.append(nullSafe(transaction.getTranId())).append(FIELD_DELIMITER)
                    .append(nullSafe(transaction.getTranTypeCd())).append(FIELD_DELIMITER)
                    .append(transaction.getTranCatCd() == null
                            ? "" : transaction.getTranCatCd().toString()).append(FIELD_DELIMITER)
                    .append(formatMoney(transaction.getTranAmt())).append(FIELD_DELIMITER)
                    .append(nullSafe(transaction.getTranCardNum())).append(FIELD_DELIMITER)
                    .append(transaction.getTranProcTs() == null
                            ? "" : transaction.getTranProcTs().toString())
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * Formats a monetary {@link BigDecimal} at scale&nbsp;2 for the S3 backup serialization.
     *
     * <p>This is <strong>output formatting only</strong> and is never used in posting arithmetic. Posted
     * amounts already carry scale&nbsp;2, so {@link BigDecimal#setScale(int, RoundingMode)} here never
     * actually rounds; {@link RoundingMode#HALF_EVEN} (banker's rounding, AAP &sect;0.7.3) is specified
     * for completeness. Using {@link BigDecimal#toPlainString()} guarantees a plain decimal string with
     * zero {@code float}/{@code double} involvement.</p>
     *
     * @param value the monetary value (may be {@code null})
     * @return the scale-2 plain-string rendering, or an empty string when {@code value} is {@code null}
     */
    private static String formatMoney(final BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Returns the given string, or an empty string when it is {@code null}, for null-safe serialization.
     *
     * @param value the value to render (may be {@code null})
     * @return {@code value}, or {@code ""} when {@code null}
     */
    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Normalizes a transaction id into a safe single S3 key segment by trimming surrounding whitespace
     * and replacing any path separator.
     *
     * @param tranId the transaction id (may be {@code null} or blank)
     * @return a non-blank key segment ({@code "unknown"} when the id is {@code null}/blank)
     */
    private static String safeKeySegment(final String tranId) {
        if (tranId == null) {
            return "unknown";
        }
        final String trimmed = tranId.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed.replace('/', '_');
    }
}
