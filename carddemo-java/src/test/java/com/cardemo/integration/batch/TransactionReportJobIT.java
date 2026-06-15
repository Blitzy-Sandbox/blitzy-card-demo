/*
 * CardDemo COBOL-to-Java migration — Stage-4b Transaction Report integration test.
 *
 * Traceability (COBOL is NEVER copied; reference by source commit SHA 27d6c6f only):
 *   - JCL  : app/jcl/TRANREPT.jcl   (STEP05R unload+SORT+INCLUDE, STEP10R PGM=CBTRN03C, LRECL=133)
 *   - COBOL: app/cbl/CBTRN03C.cbl   (date-filtered transaction report; paragraphs 1000/1100/1110/1120 + EOF)
 *
 * This integration test pins the production {@code transactionReportJob} (Stage-4b, runs in parallel
 * with Stage-4a after Stage-3) to the EXACT accumulator behaviour of COBOL CBTRN03C, including its two
 * famous, non-obvious quirks:
 *   (a) the EOF retained-record re-add — the LAST in-range transaction's amount is counted TWICE in the
 *       grand total because the final {@code READ INTO} at EOF leaves the record area intact and the EOF
 *       ELSE branch performs {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} once more; and
 *   (b) the absence of a final account-totals line at EOF — CBTRN03C performs 1110 (page totals) and
 *       1110 (grand totals) at EOF but NEVER 1120 (account totals), so the last card's account subtotal
 *       is never written.
 * It also validates inclusive date filtering, card-number ordering, the page-total flush every 20 lines,
 * and the 133-character report record written to S3 ({@code carddemo-batch-output}).
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Integration test for the production {@code transactionReportJob} bean (Stage-4b of the batch
 * pipeline), the Java migration of JCL {@code TRANREPT.jcl} driving COBOL {@code CBTRN03C}.
 *
 * <p><strong>Why this is the report-parity crown jewel.</strong> CBTRN03C's accumulator logic has two
 * behaviours that are easy to get wrong and impossible to discover by reading the happy path. Each is
 * asserted here against the real 133-character report produced into S3:</p>
 * <ol>
 *   <li><em>EOF retained-record re-add</em> (CBTRN03C EOF ELSE branch): grand total =
 *       {@code sum(all in-range amounts) + lastAmountInCardSortedOrder}.</li>
 *   <li><em>No final account-totals line</em> (CBTRN03C performs 1110+1110, never 1120, at EOF).</li>
 * </ol>
 *
 * <p><strong>Test strategy.</strong> The base {@code transaction} table is NOT seeded by Flyway V3, so
 * each test fully controls it: rows are seeded with deterministic card numbers (drawn from the V3
 * {@code card_xref} so the processor's mandatory cross-reference lookups succeed), processing dates, and
 * {@link BigDecimal} amounts. The job is launched, and the report object is read back from
 * {@code carddemo-batch-output} and parsed by locating total lines via their column-0 label markers
 * (formatting is inspect-and-adapt; the NUMERIC totals, compared with {@link BigDecimal#compareTo} at
 * scale 2, are the true parity invariants). No {@code float}/{@code double} is used anywhere.</p>
 *
 * <p>Conventions inherited from {@link AbstractBatchJobIT}: real PostgreSQL + LocalStack Testcontainers,
 * {@code webEnvironment=NONE}, {@code @ActiveProfiles("test")}, manual {@code launchJob(...)} (six {@code Job}
 * beans exist, so {@code @SpringBatchTest} auto-wiring would be ambiguous), and per-launch
 * {@code uniqueParams(...)}. The Spring Batch metadata tables are provisioned by Flyway
 * ({@code db/migration/V5__batch_metadata.sql}) exactly as in production, so this IT runs with the
 * PRODUCTION {@code spring.batch.jdbc.initialize-schema=never} (inherited from {@code application.yml} —
 * no override); and {@code SecurityCorsTestConfig} supplies the {@link CorsConfigurationSource} bean the
 * production security graph requires under {@code webEnvironment=NONE}.</p>
 */
@Import(TransactionReportJobIT.SecurityCorsTestConfig.class)
@DisplayName("TransactionReportJob (Stage-4b) integration — CBTRN03C report parity")
class TransactionReportJobIT extends AbstractBatchJobIT {

    // --- V3-seeded card_xref entries (card_number -> account_id). The processor's 1500-A cross-reference
    //     lookup is FATAL on a miss, so seeded transactions MUST use these card numbers. Note the
    //     lexical ordering: "0500024453765740" < "0923877193247330", so CARD_ACCT_2 sorts AFTER
    //     CARD_ACCT_50 under the reader's ORDER BY t.tranCardNum — i.e. account 2 is the LAST card group.
    private static final String CARD_ACCT_50 = "0500024453765740";
    private static final String CARD_ACCT_2 = "0923877193247330";
    private static final long ACCT_50 = 50L;
    private static final long ACCT_2 = 2L;

    // --- V3-seeded reference codes used by the processor's 1500-B / 1500-C lookups (also FATAL on miss).
    private static final String VALID_TYPE = "01"; // transaction_type 'Purchase'
    private static final int VALID_CATEGORY = 1; // transaction_category ('01',1) 'Regular Sales Draft'

    // --- Report shape / location constants.
    private static final int RECORD_WIDTH = 133; // COBOL report record PIC X(133)
    private static final String REPORT_PREFIX = "tranrept/"; // S3 key prefix written by the writer
    private static final BigDecimal ZERO_SCALE2 = BigDecimal.ZERO.setScale(2);

    // --- Column-0 label markers emitted by the production writer (used to classify report lines).
    private static final String MARK_NAME_HEADER = "DALYREPT";
    private static final String MARK_HEADER1 = "Transaction ID";
    private static final String MARK_PAGE_TOTAL = "Page Total";
    private static final String MARK_ACCOUNT_TOTAL = "Account Total";
    private static final String MARK_GRAND_TOTAL = "Grand Total";

    // --- The dotted leader that precedes every total amount; the amount is everything after the dot-run.
    private static final Pattern DOT_RUN = Pattern.compile("\\.{10,}");

    /** Autowired by bean name per the production contract (six Job beans exist in the context). */
    @Autowired
    private Job transactionReportJob;

    /** The base {@code transaction} table is unseeded; this test owns it entirely. */
    @Autowired
    private TransactionRepository transactionRepository;

    // ---------------------------------------------------------------------------------------------------
    // Per-test hygiene (Phase 6). The base class clears the Spring Batch metadata before each test via
    // clearJobRepository(); here we additionally guarantee a clean transaction table and a clean output
    // bucket both before and after each test so report-object counts and totals are unambiguous.
    // ---------------------------------------------------------------------------------------------------

    @BeforeEach
    void resetBeforeEach() {
        resetState();
    }

    @AfterEach
    void resetAfterEach() {
        resetState();
    }

    private void resetState() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_OUTPUT_BUCKET);
    }

    // ---------------------------------------------------------------------------------------------------
    // Fixture + parsing helpers.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Builds a single {@link Transaction} for seeding. Uses V3-seeded reference values ({@link #VALID_TYPE}
     * / {@link #VALID_CATEGORY} and a {@code card_xref}-backed card number) so the processor's mandatory
     * lookups succeed. The processing timestamp is fixed at noon on {@code procDate} so that
     * {@code SUBSTRING(CAST(tranProcTs AS string),1,10)} (the reader's inclusive date filter) yields exactly
     * {@code procDate}.
     *
     * @param seq        a unique sequence number; rendered to the 16-digit {@code TRAN-ID} primary key
     * @param cardNumber a V3 {@code card_xref} card number (drives the reader sort and the xref lookup)
     * @param procDate   the {@code yyyy-MM-dd} processing date placed into {@code TRAN-PROC-TS}
     * @param amount     the {@code TRAN-AMT} as a decimal string (parsed to a scale-2 {@link BigDecimal})
     * @return a fully-populated, unsaved {@link Transaction}
     */
    private Transaction txn(int seq, String cardNumber, String procDate, String amount) {
        Transaction t = new Transaction();
        t.setTranId(String.format("%016d", seq));
        t.setTranTypeCd(VALID_TYPE);
        t.setTranCatCd(VALID_CATEGORY);
        t.setTranSource("REPORT");
        t.setTranDesc("RPT-" + seq);
        t.setTranAmt(new BigDecimal(amount));
        t.setTranMerchantId(800000000L);
        t.setTranMerchantName("ACME MERCHANT");
        t.setTranMerchantCity("SEATTLE");
        t.setTranMerchantZip("98101");
        t.setTranCardNum(cardNumber);
        LocalDateTime ts = LocalDate.parse(procDate).atTime(12, 0, 0);
        t.setTranOrigTs(ts);
        t.setTranProcTs(ts);
        return t;
    }

    /**
     * Launches {@code transactionReportJob} with explicit {@code startDate}/{@code endDate} job parameters
     * (both {@code yyyy-MM-dd}) plus the base class's unique run identifiers, and returns the execution.
     */
    private JobExecution launchReport(String startDate, String endDate) throws Exception {
        return launchJob(
                transactionReportJob,
                uniqueParams(b -> b.addString("startDate", startDate).addString("endDate", endDate)));
    }

    /**
     * Reads the single report object written under {@link #REPORT_PREFIX} in {@link #BATCH_OUTPUT_BUCKET}
     * and returns its lines. An empty (zero-length) object yields an empty list (the documented empty-input
     * case). Asserts that exactly one report object exists so per-run object counting is unambiguous.
     */
    private List<String> readReportLines() {
        List<String> keys = listKeys(BATCH_OUTPUT_BUCKET, REPORT_PREFIX);
        assertThat(keys)
                .as("exactly one report object expected under '%s' in bucket '%s'", REPORT_PREFIX, BATCH_OUTPUT_BUCKET)
                .hasSize(1);
        String body = getObjectAsString(BATCH_OUTPUT_BUCKET, keys.get(0));
        List<String> lines = new ArrayList<>();
        if (body.isEmpty()) {
            return lines;
        }
        for (String line : body.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    /** Collects every report line satisfying {@code predicate}, preserving order. */
    private static List<String> collect(List<String> lines, Predicate<String> predicate) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (predicate.test(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean isAllHyphens(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankLine(String line) {
        return !line.isEmpty() && line.trim().isEmpty();
    }

    private static boolean isNameHeader(String line) {
        return line.startsWith(MARK_NAME_HEADER);
    }

    private static boolean isHeader1(String line) {
        return line.startsWith(MARK_HEADER1);
    }

    private static boolean isPageTotal(String line) {
        return line.startsWith(MARK_PAGE_TOTAL);
    }

    private static boolean isAccountTotal(String line) {
        return line.startsWith(MARK_ACCOUNT_TOTAL);
    }

    private static boolean isGrandTotal(String line) {
        return line.startsWith(MARK_GRAND_TOTAL);
    }

    /** A detail line is any populated report line that is not a header, separator, blank, or total line. */
    private static boolean isDetail(String line) {
        if (line.isEmpty() || isBlankLine(line) || isAllHyphens(line)) {
            return false;
        }
        return !isNameHeader(line) && !isHeader1(line)
                && !isPageTotal(line) && !isAccountTotal(line) && !isGrandTotal(line);
    }

    /**
     * Extracts the account id printed on a detail line. The production detail layout is
     * {@code field(tranId,16) + ' ' + String.format("%011d", accountId) + ' ' + ...}, so the 11-digit
     * account id occupies columns [17,28).
     */
    private static long detailAccountId(String detailLine) {
        return Long.parseLong(detailLine.substring(17, 28).trim());
    }

    /** Ordered list of the account ids printed on the report's detail lines. */
    private static List<Long> detailAccountIds(List<String> lines) {
        List<Long> ids = new ArrayList<>();
        for (String line : lines) {
            if (isDetail(line)) {
                ids.add(detailAccountId(line));
            }
        }
        return ids;
    }

    /**
     * Parses the numeric value from a total line ("Page Total"/"Account Total"/"Grand Total"). The amount
     * follows a dotted leader ({@link #DOT_RUN}); spaces and grouping commas are stripped, and a blank or
     * sign-only field (the writer's representation of a zero magnitude) parses to {@code 0.00}.
     */
    private static BigDecimal parseTotalAmount(String totalLine) {
        Matcher matcher = DOT_RUN.matcher(totalLine);
        assertThat(matcher.find())
                .as("total line must contain the dotted leader before its amount: [%s]", totalLine)
                .isTrue();
        String afterDots = totalLine.substring(matcher.end());
        String cleaned = afterDots.replace(" ", "").replace(",", "");
        if (cleaned.isEmpty() || "+".equals(cleaned) || "-".equals(cleaned)) {
            return ZERO_SCALE2;
        }
        return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Returns the single grand-total value from the report (asserts exactly one grand-total line). */
    private static BigDecimal grandTotalValue(List<String> lines) {
        List<String> grand = collect(lines, TransactionReportJobIT::isGrandTotal);
        assertThat(grand).as("exactly one grand-total line expected in the report").hasSize(1);
        return parseTotalAmount(grand.get(0));
    }

    /** Sums decimal-string amounts at scale 2 (no {@code float}/{@code double}). */
    private static BigDecimal sum(String... amounts) {
        BigDecimal total = ZERO_SCALE2;
        for (String amount : amounts) {
            total = total.add(new BigDecimal(amount));
        }
        return total.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Phase 1 — inclusive date filtering (CBTRN03C main loop, the date-filter sentence
     * {@code IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE} — INCLUSIVE of both ends).
     *
     * <p><strong>Given</strong> transactions whose processing dates fall exactly on {@code startDate},
     * exactly on {@code endDate}, one day before {@code startDate}, and one day after {@code endDate}.
     * <strong>When</strong> the report runs for the window {@code [2022-03-01, 2022-03-31]}.
     * <strong>Then</strong> only the three in-range rows appear (both boundary rows included, both
     * out-of-range rows excluded), and the grand total equals the in-range sum plus the EOF re-add of the
     * last in-range amount in card-sorted order.</p>
     *
     * <p>In-range = {@code 100.00} (on start, card 50), {@code 200.00} (mid, card 50), {@code 50.00}
     * (on end, card 2 — the lexically-highest card, hence the LAST detail in reader order). Out-of-range =
     * {@code 999.99} (day before start) and {@code 888.88} (day after end). Expected grand total =
     * {@code (100.00 + 200.00 + 50.00) + 50.00 = 400.00} (the trailing {@code 50.00} is the CBTRN03C EOF
     * retained-record re-add; see {@link #eofRetainedRecordReAddDoubleCountsGrandTotal()}).</p>
     */
    @Test
    @DisplayName("Phase 1: date filter is inclusive of both boundary dates and excludes out-of-range rows")
    void inclusiveDateFilteringIncludesBoundariesExcludesOutOfRange() throws Exception {
        // Given: boundary and out-of-range rows around the window [2022-03-01, 2022-03-31].
        transactionRepository.saveAll(List.of(
                txn(1, CARD_ACCT_50, "2022-02-28", "999.99"), // one day BEFORE start  -> EXCLUDED
                txn(2, CARD_ACCT_50, "2022-03-01", "100.00"), // exactly ON start       -> INCLUDED
                txn(3, CARD_ACCT_50, "2022-03-15", "200.00"), // mid-window             -> INCLUDED
                txn(4, CARD_ACCT_2, "2022-03-31", "50.00"),   // exactly ON end         -> INCLUDED (last card)
                txn(5, CARD_ACCT_2, "2022-04-01", "888.88"))); // one day AFTER end     -> EXCLUDED

        // When.
        JobExecution execution = launchReport("2022-03-01", "2022-03-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> lines = readReportLines();

        // Exactly the three in-range rows are reported: if either boundary were treated as exclusive the
        // count would be 2; if an out-of-range row leaked in it would be 4+.
        assertThat(collect(lines, TransactionReportJobIT::isDetail))
                .as("only the three in-range transactions should produce detail lines")
                .hasSize(3);

        // The distinctive out-of-range amounts must never appear anywhere in the report.
        String joined = String.join("\n", lines);
        assertThat(joined).doesNotContain("999.99");
        assertThat(joined).doesNotContain("888.88");

        // Grand total = sum(in-range) + EOF re-add of the last in-range amount (50.00, card 2).
        assertThat(grandTotalValue(lines))
                .as("grand total reflects only in-range amounts, plus the EOF retained-record re-add")
                .isEqualByComparingTo(new BigDecimal("400.00"));
    }

    /**
     * Phase 2 — card ordering and per-account break totals (CBTRN03C card-break logic
     * {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM ... PERFORM 1120-WRITE-ACCOUNT-TOTALS} and the reader's
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} subsumed into {@code ORDER BY t.tranCardNum}).
     *
     * <p><strong>Given</strong> five in-range transactions across two cards, inserted INTERLEAVED so insertion
     * order differs from card-sorted order: card {@code 0923...} (account 2) with {@code 10/20/30} and card
     * {@code 0500...} (account 50) with {@code 100/200}. <strong>When</strong> the report runs.
     * <strong>Then</strong> detail lines appear in ascending card-number order — i.e. account 50's two rows
     * first (card {@code 0500...}), then account 2's three rows (card {@code 0923...}) — proving the reader
     * sort, not insertion order, governs. Exactly ONE account-totals line is emitted, at the single
     * {@code 50 -> 2} card break, equal to card 50's running subtotal {@code 300.00}; the account total then
     * RESETS (the last card, account 2, never emits an account-totals line — see Phase 4).</p>
     */
    @Test
    @DisplayName("Phase 2: details ordered by card number; one account-break subtotal that resets (1120)")
    void cardOrderingAndAccountBreakTotals() throws Exception {
        // Given: interleaved insertion order to prove the reader's ORDER BY card sorts the output.
        transactionRepository.saveAll(List.of(
                txn(10, CARD_ACCT_2, "2022-06-15", "10.00"),
                txn(11, CARD_ACCT_50, "2022-06-15", "100.00"),
                txn(12, CARD_ACCT_2, "2022-06-15", "20.00"),
                txn(13, CARD_ACCT_50, "2022-06-15", "200.00"),
                txn(14, CARD_ACCT_2, "2022-06-15", "30.00")));

        // When.
        JobExecution execution = launchReport("2022-01-01", "2022-12-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> lines = readReportLines();

        // Card-sorted order: card 0500...(acct 50) precedes card 0923...(acct 2), so the detail account-id
        // sequence is [50, 50, 2, 2, 2] regardless of the interleaved insertion order.
        assertThat(detailAccountIds(lines))
                .as("detail lines must be ordered by ascending card number (reader ORDER BY t.tranCardNum)")
                .containsExactly(ACCT_50, ACCT_50, ACCT_2, ACCT_2, ACCT_2);

        // Exactly one account-totals line (the single 50 -> 2 break); its value is card 50's subtotal.
        List<String> accountTotals = collect(lines, TransactionReportJobIT::isAccountTotal);
        assertThat(accountTotals)
                .as("exactly one account-totals line: emitted on the 50 -> 2 card break (1120)")
                .hasSize(1);
        assertThat(parseTotalAmount(accountTotals.get(0)))
                .as("account subtotal equals card 50's running total (100.00 + 200.00)")
                .isEqualByComparingTo(new BigDecimal("300.00"));

        // The account total RESETS after the break: the single account-totals line equals only card 50's
        // sum (300.00) and does NOT carry card 2's amounts (which would make it 360.00). Positionally, every
        // detail line BEFORE the account-totals line is account 50, and every detail line AFTER it is
        // account 2 — the break sits exactly between the two card groups.
        int accountTotalIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isAccountTotal(lines.get(i))) {
                accountTotalIdx = i;
                break;
            }
        }
        assertThat(accountTotalIdx).as("account-totals line must be present").isGreaterThanOrEqualTo(0);
        for (int i = 0; i < lines.size(); i++) {
            if (isDetail(lines.get(i))) {
                long acct = detailAccountId(lines.get(i));
                if (i < accountTotalIdx) {
                    assertThat(acct).as("detail before the break belongs to card 50").isEqualTo(ACCT_50);
                } else {
                    assertThat(acct).as("detail after the break belongs to card 2").isEqualTo(ACCT_2);
                }
            }
        }
    }

    /**
     * Phase 3 — page totals every 20 lines (CBTRN03C {@code 1100-WRITE-TRANSACTION-REPORT} page-break test
     * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0 PERFORM 1110-WRITE-PAGE-TOTALS} with
     * {@code WS-PAGE-SIZE = 20}, and {@code 1110-WRITE-PAGE-TOTALS} which adds the page total to the grand
     * total and resets the page total to zero).
     *
     * <p><strong>Given</strong> 25 in-range transactions for a SINGLE card, each {@code 10.00} (so the value
     * is independent of the unspecified intra-card row order). <strong>When</strong> the report runs.
     * <strong>Then</strong> at least one mid-run page-totals line is emitted at the 20-line boundary (the EOF
     * flush alone would yield exactly one, so {@code >= 2} proves a boundary flush occurred), headers are
     * re-emitted at that boundary ({@code >= 2} name-header lines), and — the decisive proof that the page
     * total RESETS and rolls into the grand total — the grand total equals {@code sum(all) + lastAmount =
     * 250.00 + 10.00 = 260.00}. Were the page total NOT reset after each flush, the grand total would be
     * inflated well beyond 260.00. A single card produces NO account-totals lines.</p>
     */
    @Test
    @DisplayName("Phase 3: page-totals flush at the 20-line boundary, reset, and roll into the grand total")
    void pageTotalsEveryTwentyLinesRollUpToGrandTotal() throws Exception {
        // Given: 25 single-card in-range rows of equal amount (order-independent expected total).
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(txn(100 + i, CARD_ACCT_50, "2022-06-15", "10.00"));
        }
        transactionRepository.saveAll(rows);

        // When.
        JobExecution execution = launchReport("2022-01-01", "2022-12-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> lines = readReportLines();

        assertThat(collect(lines, TransactionReportJobIT::isDetail))
                .as("all 25 in-range rows produce detail lines").hasSize(25);

        // A mid-run page-totals flush fired at the 20-line boundary: the EOF flush always yields one
        // page-totals line, so a count of >= 2 proves the every-20-lines boundary flush also fired.
        assertThat(collect(lines, TransactionReportJobIT::isPageTotal))
                .as("page-totals line at the 20-line boundary plus the EOF page-totals flush")
                .hasSizeGreaterThanOrEqualTo(2);

        // Header re-emission accompanies the page break (1110 writes a fresh header block).
        assertThat(collect(lines, TransactionReportJobIT::isNameHeader))
                .as("headers re-emitted at the page boundary (initial block + boundary block)")
                .hasSizeGreaterThanOrEqualTo(2);

        // Single card => no card break => no account-totals line at all.
        assertThat(collect(lines, TransactionReportJobIT::isAccountTotal))
                .as("a single card produces no account-break, hence no account-totals line")
                .isEmpty();

        // Decisive reset + roll-up proof: grand total = sum(all 25 * 10.00) + EOF re-add of last 10.00.
        assertThat(grandTotalValue(lines))
                .as("page totals reset after each flush and accumulate into the grand total")
                .isEqualByComparingTo(new BigDecimal("260.00"));
    }

    /**
     * Phase 4 (headline #1) — the EOF retained-record re-add (CBTRN03C end-of-file ELSE branch:
     * {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} executed once more at EOF, then
     * {@code PERFORM 1110-WRITE-PAGE-TOTALS} and {@code PERFORM 1110-WRITE-GRAND-TOTALS}).
     *
     * <p>At end-of-file the final {@code READ INTO} leaves the previous record image intact, so CBTRN03C
     * adds the LAST in-range transaction's amount a SECOND time before flushing the closing page total into
     * the grand total. The amount is therefore counted TWICE. The "last" record is the last in
     * card-sorted order (the reader's {@code ORDER BY t.tranCardNum}).</p>
     *
     * <p><strong>Given</strong> card {@code 0500...}(acct 50) rows {@code 100.00 / 150.00 / 250.00} and card
     * {@code 0923...}(acct 2) a SINGLE distinctive row {@code 333.33}. Card {@code 0923...} is the lexically
     * highest card, so {@code 333.33} is unambiguously the last record in reader order.
     * <strong>When</strong> the report runs. <strong>Then</strong> the grand total =
     * {@code sum(all in-range) + lastAmount = (100.00 + 150.00 + 250.00 + 333.33) + 333.33 = 1166.66} — the
     * {@code 333.33} appears twice, which is the precise reproduction of the CBTRN03C EOF
     * {@code ADD TRAN-AMT} quirk. (Were the quirk absent, the grand total would be {@code 833.33}.)</p>
     */
    @Test
    @DisplayName("Phase 4: EOF re-adds the last in-range amount, double-counting it in the grand total")
    void eofRetainedRecordReAddDoubleCountsGrandTotal() throws Exception {
        // Given: the last card (0923..., highest) holds a single, distinctive amount so the double-count is
        // unambiguous; 333.33 is the last record in card-sorted order and is re-added at EOF.
        transactionRepository.saveAll(List.of(
                txn(30, CARD_ACCT_50, "2022-06-15", "100.00"),
                txn(31, CARD_ACCT_50, "2022-06-15", "150.00"),
                txn(32, CARD_ACCT_50, "2022-06-15", "250.00"),
                txn(33, CARD_ACCT_2, "2022-06-15", "333.33"))); // LAST record in reader order

        // When.
        JobExecution execution = launchReport("2022-01-01", "2022-12-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> lines = readReportLines();

        assertThat(collect(lines, TransactionReportJobIT::isDetail))
                .as("all four in-range rows produce detail lines").hasSize(4);

        // sum(all in-range) = 833.33; the EOF re-add adds the last amount (333.33) once more => 1166.66.
        BigDecimal expectedGrand = sum("100.00", "150.00", "250.00", "333.33").add(new BigDecimal("333.33"));
        assertThat(expectedGrand)
                .as("self-check: 833.33 + 333.33 (EOF re-add of the last record) = 1166.66")
                .isEqualByComparingTo(new BigDecimal("1166.66"));
        assertThat(grandTotalValue(lines))
                .as("grand total double-counts the last in-range amount per the CBTRN03C EOF ADD TRAN-AMT")
                .isEqualByComparingTo(expectedGrand);
    }

    /**
     * Phase 4 (headline #2) — NO final account-totals line at EOF (CBTRN03C performs
     * {@code 1110-WRITE-PAGE-TOTALS} and {@code 1110-WRITE-GRAND-TOTALS} at end-of-file but NEVER
     * {@code 1120-WRITE-ACCOUNT-TOTALS}; the last card's account subtotal is consequently never written).
     *
     * <p><strong>Given</strong> card {@code 0500...}(acct 50) rows {@code 40.00 / 60.00} and card
     * {@code 0923...}(acct 2) a single row {@code 25.00}; account 2 is the LAST card. <strong>When</strong>
     * the report runs. <strong>Then</strong> exactly ONE account-totals line is emitted — for account 50 at
     * the {@code 50 -> 2} break, value {@code 100.00} — and the report tail AFTER the final detail line
     * contains only page-totals and grand-totals lines, never an account-totals line. The last card's
     * would-be subtotal (whether the raw {@code 25.00} or, with the EOF re-add, {@code 50.00}) appears
     * NOWHERE, because the missing {@code 1120} at EOF means it is never written.</p>
     */
    @Test
    @DisplayName("Phase 4: EOF writes page+grand totals but NO final account-totals line (missing 1120)")
    void eofWritesNoFinalAccountTotalsLine() throws Exception {
        // Given: account 50 (two rows) then the LAST card account 2 (single row).
        transactionRepository.saveAll(List.of(
                txn(40, CARD_ACCT_50, "2022-06-15", "40.00"),
                txn(41, CARD_ACCT_50, "2022-06-15", "60.00"),
                txn(42, CARD_ACCT_2, "2022-06-15", "25.00"))); // last card; no account line at EOF

        // When.
        JobExecution execution = launchReport("2022-01-01", "2022-12-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> lines = readReportLines();

        // Exactly one account-totals line, for the non-last card (account 50), value 40.00 + 60.00.
        List<String> accountTotals = collect(lines, TransactionReportJobIT::isAccountTotal);
        assertThat(accountTotals)
                .as("only the non-last card (account 50) emits an account-totals line, at its break")
                .hasSize(1);
        assertThat(parseTotalAmount(accountTotals.get(0)))
                .as("the single account-totals line is account 50's subtotal").isEqualByComparingTo(new BigDecimal("100.00"));

        // The last card's would-be subtotal is NEVER written: neither the raw 25.00 nor the EOF-re-added
        // 50.00 appears as an account-totals line (CBTRN03C performs no 1120 at EOF).
        assertThat(parseTotalAmount(accountTotals.get(0))).isNotEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(parseTotalAmount(accountTotals.get(0))).isNotEqualByComparingTo(new BigDecimal("50.00"));

        // Scan the tail: after the final detail line there must be NO account-totals line — only the EOF
        // page-totals and grand-totals lines. This is the direct evidence that 1120 is absent at EOF.
        int lastDetailIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isDetail(lines.get(i))) {
                lastDetailIdx = i;
            }
        }
        assertThat(lastDetailIdx).as("at least one detail line must exist").isGreaterThanOrEqualTo(0);
        for (int i = lastDetailIdx + 1; i < lines.size(); i++) {
            assertThat(isAccountTotal(lines.get(i)))
                    .as("no account-totals line may follow the final detail line at EOF (missing 1120): [%s]", lines.get(i))
                    .isFalse();
        }

        // A grand-totals line IS present at EOF (1110-WRITE-GRAND-TOTALS), confirming EOF closing occurred.
        assertThat(collect(lines, TransactionReportJobIT::isGrandTotal))
                .as("EOF still writes the grand-totals line").hasSize(1);
    }

    /**
     * Phase 4 (edge) — empty in-range result (CBTRN03C never enters the per-record path; {@code WS-FIRST-TIME}
     * stays {@code 'Y'}, no detail/total lines are produced).
     *
     * <p><strong>Given</strong> only out-of-range transactions (one before the window, one after).
     * <strong>When</strong> the report runs for {@code [2022-03-01, 2022-03-31]}. <strong>Then</strong> the
     * job COMPLETES without error, exactly one report object is written, and that object contains no detail
     * lines and no grand-total line (the production writer uploads a zero-length object when no rows are
     * read — a documented divergence; COBOL behaviour on truly empty input is undefined).</p>
     */
    @Test
    @DisplayName("Phase 4: empty in-range result completes with an empty report (no detail, no totals)")
    void emptyRangeCompletesWithEmptyReport() throws Exception {
        // Given: both rows lie outside the [2022-03-01, 2022-03-31] window.
        transactionRepository.saveAll(List.of(
                txn(50, CARD_ACCT_50, "2021-12-31", "111.00"), // before window
                txn(51, CARD_ACCT_2, "2022-12-25", "222.00")));  // after window

        // When.
        JobExecution execution = launchReport("2022-03-01", "2022-03-31");

        // Then: job completes (no exception) and writes exactly one (empty) report object.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, REPORT_PREFIX))
                .as("exactly one report object is written even for an empty in-range result").isEqualTo(1L);

        List<String> lines = readReportLines();
        assertThat(collect(lines, TransactionReportJobIT::isDetail))
                .as("no in-range rows => no detail lines (WS-FIRST-TIME stays 'Y')").isEmpty();
        assertThat(collect(lines, TransactionReportJobIT::isGrandTotal))
                .as("no in-range rows => no grand-total line").isEmpty();
    }

    /**
     * Phase 5 — report shape and S3 placement (parity with the COBOL report record {@code PIC X(133)} and
     * the {@code TRANREPT.jcl} STEP10R {@code LRECL=133} output), plus confirmation that this job performs
     * NO SQS publish.
     *
     * <p><strong>Given</strong> a small in-range two-card set. <strong>When</strong> the report runs.
     * <strong>Then</strong> exactly one report object is written to {@code carddemo-batch-output} under the
     * {@code tranrept/} prefix, every report line is exactly 133 characters wide, and the report-jobs SQS
     * queue holds zero messages — the report job itself does not publish to SQS (that is the service-layer
     * {@code ReportSubmissionService}'s concern, out of scope here).</p>
     */
    @Test
    @DisplayName("Phase 5: one 133-char-wide report object in S3; the job publishes no SQS message")
    void reportShapeOneObject133CharsAndNoSqsMessage() throws Exception {
        // Given.
        transactionRepository.saveAll(List.of(
                txn(60, CARD_ACCT_50, "2022-06-15", "12.34"),
                txn(61, CARD_ACCT_2, "2022-06-15", "56.78")));

        // When.
        JobExecution execution = launchReport("2022-01-01", "2022-12-31");

        // Then.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Exactly one report object under the tranrept/ prefix.
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, REPORT_PREFIX))
                .as("exactly one report object per run").isEqualTo(1L);

        // Every report record is 133 characters wide (PIC X(133) parity).
        List<String> lines = readReportLines();
        assertThat(lines).as("a populated report must contain lines").isNotEmpty();
        for (String line : lines) {
            assertThat(line.length())
                    .as("each report record must be %d characters wide: [%s]", RECORD_WIDTH, line)
                    .isEqualTo(RECORD_WIDTH);
        }

        // No SQS message: the report job writes only to S3 and never publishes to the report-jobs queue.
        String queueUrl = sqsClient()
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(REPORT_JOBS_QUEUE).build())
                .queueUrl();
        String approximateMessages = sqsClient()
                .getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build())
                .attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        assertThat(approximateMessages)
                .as("transactionReportJob must not publish any SQS message")
                .isEqualTo("0");
    }

    /**
     * Supplies the {@link CorsConfigurationSource} bean that the production {@code SecurityConfig}/
     * {@code WebConfig} graph delegates to. Under {@code webEnvironment=NONE} the MVC
     * {@code HandlerMappingIntrospector} that would otherwise provide it is unavailable, so the context
     * fails to start without this bean. Mirrors the sibling batch ITs.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}
