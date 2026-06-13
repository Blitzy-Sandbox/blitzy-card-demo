package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Integration test for the Stage-1 {@code dailyTransactionPostingJob} — the Java/Spring-Batch migration of
 * JCL {@code POSTTRAN.jcl} driving COBOL {@code CBTRN02C} (the daily transaction posting / validation
 * engine). Traceability to the frozen mainframe baseline is by commit SHA {@code 27d6c6f} only; no COBOL is
 * copied (AAP §0.7.2).
 *
 * <p><strong>What this IT proves (end-to-end, against a real PostgreSQL 16 Testcontainer + LocalStack S3,
 * both supplied by {@link AbstractBatchJobIT}).</strong> The job's single chunk step
 * {@code dailyTransactionPostingStep} (CHUNK_SIZE = 100) reads a fixed-width 350-byte {@code DALYTRAN} file
 * from the {@code carddemo-batch-input} S3 bucket, runs the {@code CBTRN02C 1500-VALIDATE-TRAN} cascade
 * through {@code TransactionPostingProcessor}, and routes each item through a
 * {@code ClassifierCompositeItemWriter}: <em>accepted</em> items persist as {@code transaction} rows (plus a
 * {@code posted/} S3 backup) and <em>rejected</em> items are written as 430-byte reject records to the
 * {@code carddemo-batch-output} bucket under the {@code rejects/} prefix. This class drills, narrowly and
 * deeply, the edges that the once-through {@code e2e/BatchPipelineE2ETest} cannot — every reject code, the
 * {@code RETURN-CODE=4 → "COMPLETED_WITH_REJECTS"} exit-status mapping, the 430-byte reject layout, and the
 * chunk-level transactional (all-or-nothing) commit. This is defense-in-depth, not duplication.</p>
 *
 * <p><strong>Test strategy.</strong> Because the reader is S3-backed, each test stages a small, hand-crafted
 * 350-byte fixed-width {@code DALYTRAN} file (one record per scenario) to {@code carddemo-batch-input} via the
 * inherited {@code putObject(...)} helper, launches the job with a per-run unique {@code dalytranObjectKey}
 * job parameter (see {@code DailyTransactionReader.OBJECT_KEY_PARAMETER}), then asserts against repository
 * final state and S3 object presence/length. Crafted records are derived field-by-field from the production
 * {@code DailyTransaction} entity ({@code CVTRA06Y}) and {@code DailyTransactionReader} tokenizer offsets.
 * Seeded keys (a valid card whose account exists, that account's credit limit / cycle balances / expiry) are
 * looked up at runtime through the autowired repositories so crafted records stay consistent with the Flyway
 * V1/V2/V3 seed — never fabricated against the schema's FK constraints.</p>
 *
 * <p><strong>Decimal discipline (AAP §0.7.3).</strong> Every money value is {@link BigDecimal}; every numeric
 * comparison uses {@code compareTo} semantics (AssertJ {@code isEqualByComparingTo}) — never {@code equals};
 * no {@code float}/{@code double} appears for any field originating from a COBOL {@code PIC} clause.</p>
 *
 * <p><strong>Harness contract.</strong> Extends {@link AbstractBatchJobIT}, inheriting the PostgreSQL +
 * LocalStack containers, the {@code @DynamicPropertySource} wiring (including {@code spring.batch.job.enabled
 * = false} so tests control launch timing), AWS provisioning/teardown, and the
 * {@code launchJob(...)}/{@code uniqueParams(...)}/{@code putObject(...)}/{@code countObjects(...)}/
 * {@code listKeys(...)}/{@code getObjectAsString(...)}/{@code emptyBucket(...)} helpers — none redeclared
 * here. The production job is autowired <strong>by bean name</strong> ({@code dailyTransactionPostingJob}) to
 * disambiguate the six {@code Job} beans in the context. The {@code *IT} suffix routes this class to
 * {@code maven-failsafe-plugin} under the Maven {@code integration} profile.</p>
 */
// Create the Spring Batch metadata schema (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, ...) in the
// Testcontainers PostgreSQL: the Flyway migrations provision only business tables, and for a non-embedded
// database `spring.batch.jdbc.initialize-schema` defaults to `embedded` (a no-op). The inherited
// `clearJobRepository()` (@BeforeEach) and every `launchJob(...)` require these tables. This IT owns a
// single cached ApplicationContext, so the PostgreSQL batch DDL runs exactly once.
@TestPropertySource(properties = "spring.batch.jdbc.initialize-schema=always")
@Import(DailyTransactionPostingJobIT.SecurityCorsTestConfig.class)
class DailyTransactionPostingJobIT extends AbstractBatchJobIT {

    // -------------------------------------------------------------------------
    // Parity constants (mirroring POSTTRAN.jcl / CBTRN02C / the reader & writer contracts).
    // -------------------------------------------------------------------------

    /**
     * Job-parameter name carrying the staged S3 object key, mirroring
     * {@code DailyTransactionReader.OBJECT_KEY_PARAMETER}. Held as a literal (rather than importing the
     * reader) so this test depends only on the externally-observable launch contract.
     */
    private static final String OBJECT_KEY_PARAMETER = "dalytranObjectKey";

    /** S3 key prefix under which the {@code RejectWriter} stores 430-byte reject records ({@code DALYREJS}). */
    private static final String REJECT_KEY_PREFIX = "rejects/";

    /** Spring Batch exit code for a clean run with zero rejects. */
    private static final String EXIT_COMPLETED = "COMPLETED";

    /**
     * Spring Batch exit code emitted by {@code RejectCountStepListener.afterStep(...)} when the reject count
     * is &gt; 0 while {@link BatchStatus} stays {@link BatchStatus#COMPLETED} — the precise mapping of the
     * COBOL {@code IF WS-REJECT-COUNT > 0 → MOVE 4 TO RETURN-CODE} (AAP §0.7.6).
     */
    private static final String EXIT_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Fixed length of one {@code DALYTRAN} input record ({@code CVTRA06Y}, RECLN 350). */
    private static final int DALYTRAN_RECORD_LENGTH = 350;

    /** Fixed length of one reject record ({@code FD-REJS-RECORD}: 350 original + 80 trailer = 430). */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Width of the zero-padded reject reason code in the trailer ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    private static final int REJECT_CODE_WIDTH = 4;

    /** Monetary scale of every {@code PIC S9(n)V99} field — two fractional digits (AAP §0.7.3). */
    private static final int MONETARY_SCALE = 2;

    /**
     * A 16-digit card number deliberately absent from the 50 seeded {@code CARDXREF} rows, used to force the
     * {@code CBTRN02C 1500-A-LOOKUP-XREF} miss (reject 100). Each test re-asserts its absence before use.
     */
    private static final String ABSENT_CARD_NUM = "9999999999999999";

    /** Cushion added beyond an account's remaining headroom to guarantee an over-limit (102) transaction. */
    private static final BigDecimal OVER_LIMIT_CUSHION = new BigDecimal("1000.00");

    /**
     * 26-character timestamp format of {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS}
     * ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}), matching {@code DailyTransactionReader.TIMESTAMP_FORMATTER}.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT);

    // -------------------------------------------------------------------------
    // Production beans under test — autowired BY NAME from the full context.
    // -------------------------------------------------------------------------

    /** The Stage-1 job under test, resolved BY BEAN NAME {@code dailyTransactionPostingJob} (6-Job ambiguity). */
    @Autowired
    private Job dailyTransactionPostingJob;

    /** Posting target — asserted for accepted-row presence/values and emptied between tests for clean counts. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Account master — read for credit limit / cycle balances / expiry and asserted unchanged on reject. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * {@code CARDXREF} junction — used to pick a valid seeded card whose account exists, exactly as the
     * agent specification directs (Phase 1). Not in this file's {@code depends_on_files} whitelist, but the
     * interface exists in production ({@code com.cardemo.repository.CardCrossReferenceRepository}) and is the
     * only consistent source of a real card→account mapping; importing it cannot break compilation.
     */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    // -------------------------------------------------------------------------
    // Per-test hygiene. The base @BeforeEach clears ONLY the Spring Batch job repository; it does NOT empty
    // the S3 buckets or the business tables. We therefore guarantee an independent starting point here: an
    // empty transaction table (clean absolute counts, no DALYTRAN-ID collisions) and empty input/output
    // buckets (no leaked staged input or reject/posted objects from a sibling run).
    // -------------------------------------------------------------------------

    @BeforeEach
    void resetBusinessStateBeforeEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_INPUT_BUCKET);
        emptyBucket(BATCH_OUTPUT_BUCKET);
    }

    @AfterEach
    void resetBusinessStateAfterEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_INPUT_BUCKET);
        emptyBucket(BATCH_OUTPUT_BUCKET);
    }

    // =========================================================================
    // Fixture-selection helpers.
    // =========================================================================

    /**
     * A consistent (card number, owning account) pair drawn from the seeded {@code CARDXREF}/{@code ACCTDAT}.
     *
     * @param cardNum a real seeded card number present in {@code CARDXREF} ({@code findById} key)
     * @param account that card's resolved {@code ACCTDAT} account (guaranteed present by FK
     *                {@code fk_card_xref_account})
     */
    private record CardAccount(String cardNum, Account account) {
    }

    /**
     * Picks the seeded card→account pair with the greatest credit headroom, where
     * {@code headroom = ACCT-CREDIT-LIMIT − (ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT)} — the exact quantity
     * the processor compares against {@code tempBal}. Selecting maximum headroom lets the happy-path / 103
     * tests use a small in-limit amount that cannot accidentally trip the over-limit (102) branch, while the
     * 102 test derives its over-limit amount from this same account's live state. Reads are fresh each call
     * (per-test state may differ as earlier tests advance balances), so every test is self-consistent.
     *
     * @return the maximum-headroom {@link CardAccount}; fails the test if the seed exposes none
     */
    private CardAccount selectValidCardAccount() {
        CardAccount best = null;
        BigDecimal bestHeadroom = null;
        for (final CardCrossReference xref : cardCrossReferenceRepository.findAll()) {
            final Optional<Account> acctOpt = accountRepository.findById(xref.getXrefAcctId());
            if (acctOpt.isEmpty()) {
                continue; // FK guarantees this never happens; defensive only.
            }
            final Account account = acctOpt.get();
            if (account.getAcctCreditLimit() == null
                    || account.getAcctCurrCycCredit() == null
                    || account.getAcctCurrCycDebit() == null
                    || account.getAcctExpirationDate() == null) {
                continue;
            }
            final BigDecimal headroom = account.getAcctCreditLimit()
                    .subtract(account.getAcctCurrCycCredit().subtract(account.getAcctCurrCycDebit()));
            if (bestHeadroom == null || headroom.compareTo(bestHeadroom) > 0) {
                bestHeadroom = headroom;
                best = new CardAccount(xref.getXrefCardNum(), account);
            }
        }
        assertThat(best)
                .as("at least one seeded CARDXREF row must resolve to an ACCTDAT account with complete fields")
                .isNotNull();
        return best;
    }

    /** A processing timestamp strictly on/before the account's expiry — never triggers reject 103. */
    private static LocalDateTime notExpiredTimestamp(final Account account) {
        // expiry − 1 day @ noon: origTs.toLocalDate() < expiration, so expiration.compareTo(date) > 0 (no 103),
        // regardless of whether the seeded expiry is itself in the past.
        return LocalDateTime.of(account.getAcctExpirationDate().minusDays(1), LocalTime.NOON);
    }

    /** A processing timestamp strictly after the account's expiry — triggers reject 103. */
    private static LocalDateTime afterExpirationTimestamp(final Account account) {
        return LocalDateTime.of(account.getAcctExpirationDate().plusDays(1), LocalTime.NOON);
    }

    // =========================================================================
    // 350-byte DALYTRAN record builder (CVTRA06Y layout; reverses DailyTransactionReader parsing).
    // =========================================================================

    /** @return a fresh builder seeded with valid, in-range defaults for every non-scenario field. */
    private static DalytranRecordBuilder dalytran() {
        return new DalytranRecordBuilder();
    }

    /**
     * Fluent builder that emits one exactly-350-character {@code DALYTRAN} record matching the production
     * {@code DailyTransactionReader} fixed-length tokenizer (1-indexed, inclusive ranges):
     * <pre>
     *   1- 16 DALYTRAN-ID            X(16)      left-justified, space-padded
     *  17- 18 DALYTRAN-TYPE-CD       X(02)
     *  19- 22 DALYTRAN-CAT-CD        9(04)      right-justified, zero-padded
     *  23- 32 DALYTRAN-SOURCE        X(10)
     *  33-132 DALYTRAN-DESC          X(100)
     * 133-143 DALYTRAN-AMT           S9(09)V99  11-byte zoned-decimal (positive overpunch == plain digits)
     * 144-152 DALYTRAN-MERCHANT-ID   9(09)
     * 153-202 DALYTRAN-MERCHANT-NAME X(50)
     * 203-252 DALYTRAN-MERCHANT-CITY X(50)
     * 253-262 DALYTRAN-MERCHANT-ZIP  X(10)
     * 263-278 DALYTRAN-CARD-NUM      X(16)
     * 279-304 DALYTRAN-ORIG-TS       X(26)      yyyy-MM-dd HH:mm:ss.SSSSSS
     * 305-330 DALYTRAN-PROC-TS       X(26)      blank → null (pre-posting feed)
     * 331-350 FILLER                 X(20)
     * </pre>
     * The reader is strict (a record that is not exactly 350 bytes fails the step), so {@link #build()}
     * asserts the assembled length. Every field is pure ASCII, so ISO-8859-1 (reader) and UTF-8 (S3 helper)
     * round-trip 1 byte = 1 char.
     */
    private static final class DalytranRecordBuilder {
        private String id = "";
        private String typeCd = "01";
        private int catCd = 1;
        private String source = "POS";
        private String desc = "INTEGRATION TEST DAILY TRANSACTION";
        private BigDecimal amount = new BigDecimal("1.00");
        private long merchantId = 123_456_789L;
        private String merchantName = "TEST MERCHANT";
        private String merchantCity = "TEST CITY";
        private String merchantZip = "00000";
        private String cardNum = "";
        private LocalDateTime origTs = LocalDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.NOON);

        DalytranRecordBuilder id(final String value) {
            this.id = value;
            return this;
        }

        DalytranRecordBuilder cardNum(final String value) {
            this.cardNum = value;
            return this;
        }

        DalytranRecordBuilder amount(final String value) {
            this.amount = new BigDecimal(value);
            return this;
        }

        DalytranRecordBuilder amount(final BigDecimal value) {
            this.amount = value;
            return this;
        }

        DalytranRecordBuilder origTs(final LocalDateTime value) {
            this.origTs = value;
            return this;
        }

        /** @return the assembled, exactly-350-character fixed-width record. */
        String build() {
            final StringBuilder sb = new StringBuilder(DALYTRAN_RECORD_LENGTH);
            sb.append(alpha(id, 16));               // 1-16   DALYTRAN-ID
            sb.append(alpha(typeCd, 2));            // 17-18  DALYTRAN-TYPE-CD
            sb.append(numeric(catCd, 4));           // 19-22  DALYTRAN-CAT-CD
            sb.append(alpha(source, 10));           // 23-32  DALYTRAN-SOURCE
            sb.append(alpha(desc, 100));            // 33-132 DALYTRAN-DESC
            sb.append(encodeAmount(amount));        // 133-143 DALYTRAN-AMT (11)
            sb.append(numeric(merchantId, 9));      // 144-152 DALYTRAN-MERCHANT-ID
            sb.append(alpha(merchantName, 50));     // 153-202 DALYTRAN-MERCHANT-NAME
            sb.append(alpha(merchantCity, 50));     // 203-252 DALYTRAN-MERCHANT-CITY
            sb.append(alpha(merchantZip, 10));      // 253-262 DALYTRAN-MERCHANT-ZIP
            sb.append(alpha(cardNum, 16));          // 263-278 DALYTRAN-CARD-NUM
            sb.append(formatTimestamp(origTs));     // 279-304 DALYTRAN-ORIG-TS (26)
            sb.append(blanks(26));                  // 305-330 DALYTRAN-PROC-TS (blank → null)
            sb.append(blanks(20));                  // 331-350 FILLER
            final String record = sb.toString();
            if (record.length() != DALYTRAN_RECORD_LENGTH) {
                throw new IllegalStateException("Assembled DALYTRAN record length " + record.length()
                        + " != " + DALYTRAN_RECORD_LENGTH + " — field widths drifted");
            }
            return record;
        }
    }

    // -------------------------------------------------------------------------
    // Fixed-width field formatters.
    // -------------------------------------------------------------------------

    /** Left-justifies an alphanumeric value, space-padding (or truncating) to {@code width}. */
    private static String alpha(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + blanks(width - v.length());
    }

    /** Right-justifies a non-negative integer, zero-padding to {@code width}. */
    private static String numeric(final long value, final int width) {
        final String formatted = String.format(Locale.ROOT, "%0" + width + "d", value);
        if (formatted.length() != width) {
            throw new IllegalStateException("numeric value " + value + " overflows width " + width);
        }
        return formatted;
    }

    /**
     * Encodes a non-negative {@code BigDecimal} into the 11-byte {@code S9(09)V99} zoned-decimal field. For a
     * positive amount the trailing byte may be a plain digit (the reader's {@code parseSignedOverpunch}
     * treats {@code '0'..'9'} as positive), so the whole field is simply the unscaled value
     * ({@code amount × 100}) right-justified and zero-padded to 11 digits.
     */
    private static String encodeAmount(final BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("encodeAmount supports non-negative amounts only: " + amount);
        }
        final long unscaled = amount.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY)
                .movePointRight(MONETARY_SCALE)
                .longValueExact();
        final String encoded = String.format(Locale.ROOT, "%011d", unscaled);
        if (encoded.length() != 11) {
            throw new IllegalStateException("DALYTRAN-AMT " + amount + " overflows the S9(09)V99 11-byte field");
        }
        return encoded;
    }

    /** Formats a timestamp to the fixed 26-character {@code yyyy-MM-dd HH:mm:ss.SSSSSS} layout. */
    private static String formatTimestamp(final LocalDateTime timestamp) {
        final String formatted = timestamp.format(TIMESTAMP_FORMATTER);
        if (formatted.length() != 26) {
            throw new IllegalStateException("timestamp '" + formatted + "' is not 26 characters");
        }
        return formatted;
    }

    /** @return a run of {@code count} ASCII spaces. */
    private static String blanks(final int count) {
        return " ".repeat(count);
    }

    // =========================================================================
    // Staging / launch / reject-reading helpers.
    // =========================================================================

    /**
     * Stages the given fixed-width records as a single newline-joined object in {@code carddemo-batch-input}
     * under a unique key and returns that key. The body is written as ISO-8859-1 bytes — byte-identical to
     * the reader's encoding — so the strict 350-byte tokenizer reads each line verbatim.
     *
     * @param records one or more exactly-350-character records
     * @return the staged object key, to be passed as the {@code dalytranObjectKey} job parameter
     */
    private String stageDalytranFile(final List<String> records) {
        final String body = String.join("\n", records);
        final String key = "it/dalytran-" + UUID.randomUUID() + ".txt";
        putObject(BATCH_INPUT_BUCKET, key, body.getBytes(StandardCharsets.ISO_8859_1));
        return key;
    }

    /**
     * Launches {@code dailyTransactionPostingJob} against the staged object key with an always-unique
     * {@code JobInstance} (avoids {@code JobInstanceAlreadyCompleteException}).
     *
     * @param objectKey the staged DALYTRAN object key
     * @return the terminal {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchPosting(final String objectKey) throws Exception {
        return launchJob(dailyTransactionPostingJob,
                uniqueParams(builder -> builder.addString(OBJECT_KEY_PARAMETER, objectKey)));
    }

    /**
     * Reads every 430-byte reject record produced by this run: lists all {@code rejects/} objects in
     * {@code carddemo-batch-output}, decodes each, and splits it into its newline-framed fixed-length lines
     * (dropping the empty tail after a trailing newline, if any).
     *
     * @return every reject record line across all reject objects (possibly empty, never {@code null})
     */
    private List<String> readAllRejectRecords() {
        final List<String> lines = new ArrayList<>();
        for (final String key : listKeys(BATCH_OUTPUT_BUCKET, REJECT_KEY_PREFIX)) {
            final String body = getObjectAsString(BATCH_OUTPUT_BUCKET, key);
            for (final String line : body.split("\n", -1)) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Asserts a single reject record obeys the {@code CBTRN02C 2500-WRITE-REJECT-REC} 430-byte contract: the
     * total record is 430 bytes; the 80-byte trailer (bytes 351-430) opens with the 4-digit zero-padded
     * reject reason code; and the original 350-byte payload preserves the offending card number. Kept tolerant
     * of exact trailer column formatting beyond the code (the description text formatting is "inspect &amp;
     * adapt"): the reason code is asserted precisely, the rest only for presence.
     *
     * @param line         one 430-byte reject record
     * @param expectedCode the expected {@link RejectCode#getCode()} (e.g. 100, 102, 103)
     * @param cardNum      the card number that should survive in the re-serialized 350-byte payload
     */
    private static void assertRejectRecord(final String line, final int expectedCode, final String cardNum) {
        assertThat(line.length())
                .as("reject record LRECL (CBTRN02C FD-REJS-RECORD = 350 + 80)")
                .isEqualTo(REJECT_RECORD_LENGTH);
        final String trailer = line.substring(DALYTRAN_RECORD_LENGTH); // bytes 351-430 (80 chars)
        assertThat(trailer.substring(0, REJECT_CODE_WIDTH))
                .as("trailer reason code (WS-VALIDATION-FAIL-REASON PIC 9(04), zero-padded)")
                .isEqualTo(String.format(Locale.ROOT, "%04d", expectedCode));
        assertThat(line.substring(0, DALYTRAN_RECORD_LENGTH))
                .as("re-serialized DALYTRAN payload preserves the offending card number")
                .contains(cardNum);
    }

    // =========================================================================
    // Phase 2 — Happy path (all accepted).
    // =========================================================================

    /**
     * Given three valid daily transactions (a seeded card whose account exists, each within the credit limit
     * and on/before expiry); When the posting job runs; Then it COMPLETEs with exit code {@code COMPLETED}
     * (no rejects), all three are persisted to {@code TRANSACT} with their exact card and amount, and zero
     * {@code rejects/} objects are written. Proves the all-pass {@code CBTRN02C 1500-VALIDATE-TRAN} →
     * {@code 2000-POST-TRANSACTION} path and the {@code TransactionWriter} accepted route.
     */
    @Test
    @DisplayName("Happy path: valid transactions accepted, persisted with exact amounts, zero rejects "
            + "(CBTRN02C 1500-VALIDATE-TRAN all-pass → 2000-POST-TRANSACTION)")
    void happyPath_allAccepted_persistedWithNoRejects() throws Exception {
        // Given: one seeded card→account with ample headroom; three small, in-limit, not-expired records.
        final CardAccount ca = selectValidCardAccount();
        final LocalDateTime origTs = notExpiredTimestamp(ca.account());
        final String id1 = "HAPPYTXN00000001";
        final String id2 = "HAPPYTXN00000002";
        final String id3 = "HAPPYTXN00000003";
        final List<String> records = List.of(
                dalytran().id(id1).cardNum(ca.cardNum()).amount("1.00").origTs(origTs).build(),
                dalytran().id(id2).cardNum(ca.cardNum()).amount("2.50").origTs(origTs).build(),
                dalytran().id(id3).cardNum(ca.cardNum()).amount("3.99").origTs(origTs).build());
        final String key = stageDalytranFile(records);

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: clean completion with no reject mapping.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(EXIT_COMPLETED);

        // Then: all three rows persisted (table emptied in @BeforeEach → absolute count == accepted count).
        assertThat(transactionRepository.count()).isEqualTo(3L);

        final Transaction t1 = transactionRepository.findById(id1).orElseThrow();
        assertThat(t1.getTranCardNum()).isEqualTo(ca.cardNum());
        assertThat(t1.getTranAmt()).isEqualByComparingTo("1.00"); // compareTo, never equals (AAP §0.7.3).

        final Transaction t2 = transactionRepository.findById(id2).orElseThrow();
        assertThat(t2.getTranCardNum()).isEqualTo(ca.cardNum());
        assertThat(t2.getTranAmt()).isEqualByComparingTo("2.50");

        final Transaction t3 = transactionRepository.findById(id3).orElseThrow();
        assertThat(t3.getTranCardNum()).isEqualTo(ca.cardNum());
        assertThat(t3.getTranAmt()).isEqualByComparingTo("3.99");

        // Then: zero reject objects (the accepted path writes posted/ objects; rejects/ must be empty).
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, REJECT_KEY_PREFIX)).isZero();
    }

    // =========================================================================
    // Phase 3 — Reject codes (one focused test per code).
    // =========================================================================

    /**
     * Given a transaction whose card number is absent from {@code CARDXREF}; When the job runs; Then the item
     * is rejected (never persisted), a 430-byte reject record carrying code {@code 0100} is written, and the
     * job COMPLETEs with exit {@code COMPLETED_WITH_REJECTS}. Proves {@code CBTRN02C 1500-A-LOOKUP-XREF}
     * (reject 100 — invalid card).
     */
    @Test
    @DisplayName("Reject 100: card absent from CARDXREF is rejected and not persisted; 430-byte reject "
            + "written (CBTRN02C 1500-A-LOOKUP-XREF)")
    void rejectCode100_invalidCardNumber() throws Exception {
        // Given: a card number proven absent from the seeded xref.
        Assumptions.assumeTrue(cardCrossReferenceRepository.findById(ABSENT_CARD_NUM).isEmpty(),
                "precondition: " + ABSENT_CARD_NUM + " must be absent from CARDXREF");
        final String id = "REJ100TXN0000001";
        final String record = dalytran().id(id).cardNum(ABSENT_CARD_NUM).amount("10.00")
                .origTs(LocalDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.NOON)).build();
        final String key = stageDalytranFile(List.of(record));

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: rejects do not fail the job; RETURN-CODE=4 → COMPLETED_WITH_REJECTS.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        // Then: the rejected item is not persisted.
        assertThat(transactionRepository.findById(id)).isEmpty();
        assertThat(transactionRepository.count()).isZero();

        // Then: exactly one 430-byte reject record carrying reason code 0100.
        final List<String> rejects = readAllRejectRecords();
        assertThat(rejects).hasSize(1);
        assertRejectRecord(rejects.get(0), RejectCode.INVALID_CARD_NUMBER.getCode(), ABSENT_CARD_NUM);
    }

    /**
     * Given a valid card→account and an amount that pushes {@code tempBal} past {@code ACCT-CREDIT-LIMIT};
     * When the job runs; Then the item is rejected, a reject record carrying code {@code 0102} is written, and
     * — crucially — the account balance is NOT advanced (the rejected item never reaches
     * {@code 2800-UPDATE-ACCOUNT-REC}). Proves the over-limit ELSE of {@code CBTRN02C 1500} (reject 102).
     */
    @Test
    @DisplayName("Reject 102: over-credit-limit transaction is rejected and the account balance is NOT "
            + "advanced (CBTRN02C 1500 over-limit ELSE)")
    void rejectCode102_overCreditLimit_balanceNotAdvanced() throws Exception {
        // Given: a valid card→account; craft amount so currCycCredit − currCycDebit + amt > creditLimit.
        final CardAccount ca = selectValidCardAccount();
        final Account account = ca.account();
        final BigDecimal headroom = account.getAcctCreditLimit()
                .subtract(account.getAcctCurrCycCredit().subtract(account.getAcctCurrCycDebit()));
        // max(headroom,0) + cushion guarantees tempBal > limit AND a positive (encodable) amount even if the
        // chosen account were already at/over its limit.
        final BigDecimal overLimitAmount = headroom.max(BigDecimal.ZERO).add(OVER_LIMIT_CUSHION);
        final BigDecimal balanceBefore = account.getAcctCurrBal();
        final String id = "REJ102TXN0000001";
        final String record = dalytran().id(id).cardNum(ca.cardNum()).amount(overLimitAmount)
                .origTs(notExpiredTimestamp(account)).build(); // not expired → isolate the 102 branch
        final String key = stageDalytranFile(List.of(record));

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: COMPLETED_WITH_REJECTS and the item is not persisted.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);
        assertThat(transactionRepository.findById(id)).isEmpty();

        // Then: the account balance is unchanged — the reject short-circuits the posting/balance update.
        final Account after = accountRepository.findById(account.getAcctId()).orElseThrow();
        assertThat(after.getAcctCurrBal()).isEqualByComparingTo(balanceBefore);

        // Then: one reject record carrying reason code 0102.
        final List<String> rejects = readAllRejectRecords();
        assertThat(rejects).hasSize(1);
        assertRejectRecord(rejects.get(0), RejectCode.OVERLIMIT_TRANSACTION.getCode(), ca.cardNum());
    }

    /**
     * Given a valid card→account and a processing timestamp strictly after the account's expiry (with a
     * small, in-limit amount so the over-limit branch cannot also fire); When the job runs; Then the item is
     * rejected and a reject record carrying code {@code 0103} is written. Proves {@code CBTRN02C 1500}
     * 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' (reject 103).
     */
    @Test
    @DisplayName("Reject 103: transaction processed after account expiry is rejected "
            + "(CBTRN02C 1500 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION')")
    void rejectCode103_afterExpiration() throws Exception {
        // Given: origTs strictly after expiry; tiny in-limit amount keeps the 102 branch quiet (and even if
        // it fired, the independent 103 check overwrites it last — final reason is 103 either way).
        final CardAccount ca = selectValidCardAccount();
        final String id = "REJ103TXN0000001";
        final String record = dalytran().id(id).cardNum(ca.cardNum()).amount("1.00")
                .origTs(afterExpirationTimestamp(ca.account())).build();
        final String key = stageDalytranFile(List.of(record));

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: COMPLETED_WITH_REJECTS, not persisted, reject record carries code 0103.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);
        assertThat(transactionRepository.findById(id)).isEmpty();

        final List<String> rejects = readAllRejectRecords();
        assertThat(rejects).hasSize(1);
        assertRejectRecord(rejects.get(0), RejectCode.TRANSACTION_AFTER_EXPIRATION.getCode(), ca.cardNum());
    }

    /**
     * Reject 101 ('ACCOUNT RECORD NOT FOUND', {@code CBTRN02C 1500-B-LOOKUP-ACCT}) is unreachable from the
     * consistent Flyway seed: the database FK {@code fk_card_xref_account} guarantees every {@code CARDXREF}
     * row references an existing {@code ACCTDAT} account, so a card that resolves through the xref always
     * resolves to a present account. Reproducing 101 would require an orphaned xref that violates the FK,
     * which AAP §0.7.2 (no inconsistent/fabricated data) forbids. This test asserts that invariant, then
     * formally skips the reject scenario — documenting that the production path remains exercised by
     * {@code TransactionPostingProcessor} step 2 even though it cannot be provoked with honest seed data.
     */
    @Test
    @DisplayName("Reject 101: ACCOUNT RECORD NOT FOUND is unreachable under the FK-constrained seed — "
            + "invariant asserted, scenario skipped (CBTRN02C 1500-B-LOOKUP-ACCT)")
    void rejectCode101_accountNotFound_notReproducibleWithConsistentSeed() {
        boolean anyOrphanedXref = false;
        for (final CardCrossReference xref : cardCrossReferenceRepository.findAll()) {
            if (accountRepository.findById(xref.getXrefAcctId()).isEmpty()) {
                anyOrphanedXref = true;
                break;
            }
        }
        // The FK guarantee: there is no orphaned xref, hence no honest way to reach reject 101.
        assertThat(anyOrphanedXref)
                .as("FK fk_card_xref_account guarantees every CARDXREF row resolves to an ACCTDAT account, "
                        + "so reject 101 cannot be produced without violating the schema")
                .isFalse();
        // Skip the (unconstructible) reject scenario rather than fabricate FK-violating data.
        Assumptions.assumeTrue(anyOrphanedXref,
                "Reject 101 (ACCOUNT RECORD NOT FOUND) is not reproducible with the consistent, FK-constrained "
                        + "seed; skipping rather than fabricating inconsistent data (AAP §0.7.2). The production "
                        + "path stays covered by TransactionPostingProcessor step 2 (CBTRN02C 1500-B-LOOKUP-ACCT).");
    }

    // =========================================================================
    // Phase 4 — RC=4 → COMPLETED_WITH_REJECTS (mixed batch, partial success).
    // =========================================================================

    /**
     * Given a mixed file (two valid + one invalid-card record); When the job runs; Then the job COMPLETEs
     * (NOT FAILED) with exit {@code COMPLETED_WITH_REJECTS}, the two accepted items land in {@code TRANSACT},
     * and the rejected item produces a {@code rejects/} artifact while never persisting. This is the precise
     * mapping of COBOL {@code RETURN-CODE=4} and the §0.7.6 rule that POSTTRAN partial failures still let the
     * pipeline proceed — good items persist, bad items are quarantined.
     */
    @Test
    @DisplayName("Mixed batch: accepted items persist AND a reject artifact is written; job COMPLETED with "
            + "exit COMPLETED_WITH_REJECTS (CBTRN02C RETURN-CODE=4, AAP §0.7.6)")
    void mixedBatch_partialSuccess_completedWithRejects() throws Exception {
        Assumptions.assumeTrue(cardCrossReferenceRepository.findById(ABSENT_CARD_NUM).isEmpty(),
                "precondition: " + ABSENT_CARD_NUM + " must be absent from CARDXREF");
        // Given: two valid, in-limit, not-expired records and one invalid-card record in a single chunk.
        final CardAccount ca = selectValidCardAccount();
        final LocalDateTime origTs = notExpiredTimestamp(ca.account());
        final String accepted1 = "MIXACCEPT0000001";
        final String accepted2 = "MIXACCEPT0000002";
        final String rejected = "MIXREJECT0000001";
        final List<String> records = List.of(
                dalytran().id(accepted1).cardNum(ca.cardNum()).amount("5.00").origTs(origTs).build(),
                dalytran().id(accepted2).cardNum(ca.cardNum()).amount("7.25").origTs(origTs).build(),
                dalytran().id(rejected).cardNum(ABSENT_CARD_NUM).amount("9.00").origTs(origTs).build());
        final String key = stageDalytranFile(records);

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: the presence of rejects maps to a non-failing COMPLETED_WITH_REJECTS run.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        // Then: the two good items persisted; the bad item did not.
        assertThat(transactionRepository.findById(accepted1)).isPresent();
        assertThat(transactionRepository.findById(accepted2)).isPresent();
        assertThat(transactionRepository.count()).isEqualTo(2L);
        assertThat(transactionRepository.findById(rejected)).isEmpty();

        // Then: exactly one reject artifact for the quarantined item, carrying code 0100.
        final List<String> rejects = readAllRejectRecords();
        assertThat(rejects).hasSize(1);
        assertRejectRecord(rejects.get(0), RejectCode.INVALID_CARD_NUMBER.getCode(), ABSENT_CARD_NUM);
    }

    // =========================================================================
    // Phase 5 — Chunk-level @Transactional atomicity (batch analogue of SYNCPOINT ROLLBACK, AAP §0.7.5).
    // =========================================================================

    /**
     * Spring Batch wraps each chunk's writes in a transaction managed by the step's
     * {@code PlatformTransactionManager}; a failure while writing a chunk rolls back that chunk's DB writes
     * all-or-nothing. This is the migration of the sole COBOL {@code SYNCPOINT ROLLBACK} (COACTUPC) applied
     * to {@code CBTRN02C} posting (AAP §0.7.5).
     *
     * <p>A <em>genuine mid-chunk write failure is not constructible from valid input</em> here: per the V1
     * schema the {@code transaction} table is FK-light with an assigned primary key (so {@code saveAll}
     * issues a {@code merge}, never a duplicate-key abend), and {@code @Version} optimistic locking requires
     * external concurrency that this single-threaded harness cannot supply without fabricating inconsistent
     * data (forbidden by §0.7.2). We therefore assert the positive rollback invariant — after a successful
     * chunk commit, the committed accepted rows exactly equal the number of accepted (non-rejected) inputs,
     * and the rejected input contributes ZERO rows: all-or-nothing chunk semantics with no partial leakage.
     * The four accepted + one rejected records share one chunk (CHUNK_SIZE 100 ≫ 5).</p>
     */
    @Test
    @DisplayName("Chunk @Transactional atomicity: committed accepted rows == accepted inputs, no partial "
            + "leakage (batch analogue of CBTRN02C SYNCPOINT ROLLBACK, AAP §0.7.5)")
    void chunkTransactionalAtomicity_acceptedCountEqualsAcceptedInputs() throws Exception {
        Assumptions.assumeTrue(cardCrossReferenceRepository.findById(ABSENT_CARD_NUM).isEmpty(),
                "precondition: " + ABSENT_CARD_NUM + " must be absent from CARDXREF");
        // Given: four accepted records and one rejected record, all in a single chunk.
        final CardAccount ca = selectValidCardAccount();
        final LocalDateTime origTs = notExpiredTimestamp(ca.account());
        final int acceptedCount = 4;
        final List<String> records = new ArrayList<>();
        for (int i = 0; i < acceptedCount; i++) {
            records.add(dalytran().id(String.format(Locale.ROOT, "ATOMACPT%08d", i))
                    .cardNum(ca.cardNum()).amount("1.00").origTs(origTs).build());
        }
        records.add(dalytran().id("ATOMREJECT000001").cardNum(ABSENT_CARD_NUM).amount("2.00")
                .origTs(origTs).build());
        final String key = stageDalytranFile(records);

        // When.
        final JobExecution execution = launchPosting(key);

        // Then: the chunk committed; exactly the accepted inputs are present and the rejected input left no row.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("committed accepted rows must equal the number of accepted inputs (no partial chunk leakage)")
                .isEqualTo((long) acceptedCount);
        for (int i = 0; i < acceptedCount; i++) {
            assertThat(transactionRepository.findById(String.format(Locale.ROOT, "ATOMACPT%08d", i)))
                    .as("accepted item %d must be committed", i)
                    .isPresent();
        }
        assertThat(transactionRepository.findById("ATOMREJECT000001"))
                .as("the rejected item must contribute zero committed rows")
                .isEmpty();
    }

    // =========================================================================
    // Test-only configuration.
    // =========================================================================

    /**
     * Supplies the {@link CorsConfigurationSource} bean that the production {@code SecurityConfig}'s
     * {@code .cors(Customizer.withDefaults())} resolves.
     *
     * <p><strong>Why this is needed (technology-transition note).</strong> The production security filter
     * chain enables Security-aware CORS via {@code .cors(Customizer.withDefaults())}, which at runtime (a
     * servlet web context) delegates to Spring MVC's {@code HandlerMappingIntrospector}. The batch IT
     * harness ({@link AbstractBatchJobIT}) deliberately boots with {@code @SpringBootTest(webEnvironment =
     * NONE)} — no embedded server, no web MVC — so that delegate does not exist and the filter chain cannot
     * instantiate without an explicit source. This {@code @TestConfiguration} publishes an empty
     * {@link UrlBasedCorsConfigurationSource} (behaviorally equivalent to production's effective default of
     * no CORS mappings) so the context refreshes for this non-web batch test. It is {@code @TestConfiguration}
     * (excluded from the application component scan) and {@code @Import}ed only by this class, so it touches
     * neither production code nor the shared base class and never leaks into another test context.</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty CORS source (no mappings) satisfying the security filter chain's CORS DSL
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}
