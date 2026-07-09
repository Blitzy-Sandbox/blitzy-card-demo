package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;

/**
 * Fast, dependency-free unit test for {@link TransactionReportItemWriter}, the stateful Spring Batch
 * writer that renders the CardDemo <em>transaction detail report</em> and is the structured-Java
 * translation of the report-writing paragraphs of the legacy batch program {@code CBTRN03C.CBL}
 * (source reference-only, SHA {@code 27d6c6f}).
 *
 * <p>The suite runs in milliseconds against pure Mockito mocks (no Spring context, no PostgreSQL, no
 * network and no live AWS): it drives the writer's {@code open() / write() / close()} lifecycle
 * directly and captures the report payload that the writer streams to S3. Because the production
 * writer deletes its temporary buffer immediately after the upload, the payload is captured
 * <em>during</em> the mocked {@link S3Template#upload(String, String, InputStream, ObjectMetadata)}
 * call rather than read back afterwards.</p>
 *
 * <p>The assertions verify the behaviours mandated by the CardDemo migration for this component:</p>
 * <ul>
 *   <li><strong>Byte parity (Gate&nbsp;1 / Gate&nbsp;5).</strong> Every emitted record &mdash; page
 *       header, blank line, column header, separator, detail, page/account/grand total &mdash; is
 *       exactly {@value #RECORD_LENGTH} characters wide, matching the legacy {@code TRANREPT} dataset
 *       ({@code LRECL=133, RECFM=FB}).</li>
 *   <li><strong>Pagination.</strong> A page-header block is written at the very top of the report
 *       ({@code WS-FIRST-TIME}) and again after every page break
 *       ({@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}), and the per-page total resets on
 *       each new page.</li>
 *   <li><strong>Account control break.</strong> A per-account subtotal
 *       ({@code 1120-WRITE-ACCOUNT-TOTALS}) is emitted whenever the account id changes, and the final
 *       account's subtotal is flushed before the grand total.</li>
 *   <li><strong>Decimal fidelity (AAP&nbsp;§0.8.2).</strong> The grand total reconciles exactly to the
 *       sum of every detail amount, and the page totals and account totals each reconcile to the grand
 *       total &mdash; all in {@link BigDecimal} of scale&nbsp;2, compared with {@code compareTo} so the
 *       test is independent of any incidental {@code BigDecimal} scale artefact.</li>
 *   <li><strong>Sink contract.</strong> Exactly one object is uploaded, to bucket
 *       {@value #OUTPUT_BUCKET} and key {@value #OBJECT_KEY}, on {@link TransactionReportItemWriter#close()}.</li>
 * </ul>
 *
 * <p>The writer is stateful across chunks, so every scenario feeds input pre-sorted by account id
 * (the precondition guaranteed upstream by the reader/comparator); one scenario additionally proves
 * the output is identical regardless of how the input is partitioned into chunks.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportItemWriter — 133-char report, control breaks & totals (← CBTRN03C)")
class TransactionReportItemWriterTest {

    /** Fixed record width of the {@code TRANREPT} dataset ({@code LRECL=133}). */
    private static final int RECORD_LENGTH = 133;

    /**
     * Width of the literal prefix on every total line ({@code REPORT-*-TOTALS}); the edited amount
     * field begins immediately after it. All three prefixes ({@code Page Total}, {@code Account
     * Total}, {@code Grand Total}) are exactly this wide in copybook {@code CVTRA07Y}.
     */
    private static final int TOTAL_PREFIX_LENGTH = 97;

    /** Width of the edited amount field on a total line ({@code +ZZZ,ZZZ,ZZZ.ZZ}): sign + 14. */
    private static final int AMOUNT_FIELD_LENGTH = 15;

    /**
     * Column at which the {@code "Date Range: "} field begins in the {@code REPORT-NAME-HEADER}
     * ({@code CVTRA07Y}): immediately after {@code pad("DALYREPT",38)} + {@code pad("Daily
     * Transaction Report",41)} = 79 characters.
     */
    private static final int NAME_HEADER_DATE_RANGE_OFFSET = 79;

    /**
     * Width of the assembled {@code "Date Range: "} field: the 12-character label
     * ({@code REPT-DATE-HEADER}) + {@code REPT-START-DATE PIC X(10)} + the 4-character {@code " to "}
     * separator + {@code REPT-END-DATE PIC X(10)} = 36 characters.
     */
    private static final int DATE_RANGE_FIELD_LENGTH = 36;

    /** Target S3 bucket asserted by the sink-contract tests (legacy GDG generation → S3 object). */
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";

    /** Stable S3 object key asserted by the sink-contract tests. */
    private static final String OBJECT_KEY = "tranrept.dat";

    /** Small page size so a modest input crosses several page boundaries (drives pagination tests). */
    private static final int SMALL_PAGE_SIZE = 5;

    /** Page size large enough that the pagination logic never triggers (isolates the account break). */
    private static final int LARGE_PAGE_SIZE = 1_000;

    /** Charset of the emitted report ({@code Files.newBufferedWriter(..., ISO_8859_1)} in production). */
    private static final java.nio.charset.Charset REPORT_CHARSET = StandardCharsets.ISO_8859_1;

    @Mock
    private S3Template s3Template;

    // ------------------------------------------------------------------------------------------------
    // Phase 1 — construction contract (validates the injected configuration up front)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects a null S3Template")
    void constructorRejectsNullS3Template() {
        assertThatNullPointerException().isThrownBy(() ->
                new TransactionReportItemWriter(null, SMALL_PAGE_SIZE, OUTPUT_BUCKET, OBJECT_KEY, "", ""));
    }

    @Test
    @DisplayName("constructor rejects a non-positive page size")
    void constructorRejectsNonPositivePageSize() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TransactionReportItemWriter(s3Template, 0, OUTPUT_BUCKET, OBJECT_KEY, "", ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TransactionReportItemWriter(s3Template, -3, OUTPUT_BUCKET, OBJECT_KEY, "", ""));
    }

    @Test
    @DisplayName("constructor rejects a blank output bucket or object key")
    void constructorRejectsBlankBucketOrKey() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TransactionReportItemWriter(s3Template, SMALL_PAGE_SIZE, "   ", OBJECT_KEY, "", ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TransactionReportItemWriter(s3Template, SMALL_PAGE_SIZE, OUTPUT_BUCKET, "", "", ""));
    }

    // ------------------------------------------------------------------------------------------------
    // Test fixtures / helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * Constructs a writer wired to the mocked {@link S3Template} with the given page-break threshold
     * and the canonical bucket/key. All configuration is supplied through the constructor, so no
     * Spring context or reflection is required.
     *
     * @param pageSize the page-break threshold ({@code WS-PAGE-SIZE})
     * @return a fully initialised writer ready for {@code open()}
     */
    private TransactionReportItemWriter newWriter(int pageSize) {
        return newWriter(pageSize, "", "");
    }

    /**
     * Constructs a writer wired to the mocked {@link S3Template} with the given page-break threshold,
     * the canonical bucket/key, and an explicit report date window &mdash; the values a
     * job-parameter-driven launch resolves for {@code REPT-START-DATE} / {@code REPT-END-DATE} via the
     * step-scoped {@code #{jobParameters['startDate'] ?: ...}} binding. All configuration is supplied
     * through the constructor, so no Spring context or reflection is required.
     *
     * @param pageSize  the page-break threshold ({@code WS-PAGE-SIZE})
     * @param startDate the report start date rendered in the name header ({@code REPT-START-DATE})
     * @param endDate   the report end date rendered in the name header ({@code REPT-END-DATE})
     * @return a fully initialised writer ready for {@code open()}
     */
    private TransactionReportItemWriter newWriter(int pageSize, String startDate, String endDate) {
        return new TransactionReportItemWriter(
                s3Template, pageSize, OUTPUT_BUCKET, OBJECT_KEY, startDate, endDate);
    }

    /**
     * Builds a {@link ReportDetailLine} from the three fields that matter to the writer &mdash; the
     * transaction id, the control-break account id and the monetary amount &mdash; supplying stable,
     * non-blank placeholders for the descriptive columns so the rendered detail line is well formed.
     *
     * @param transactionId the transaction id ({@code TRAN-REPORT-TRANS-ID})
     * @param accountId     the control-break account id ({@code TRAN-REPORT-ACCOUNT-ID})
     * @param amount        the amount as a decimal string (normalised to scale 2 by the record)
     * @return the assembled detail row
     */
    private static ReportDetailLine line(String transactionId, Long accountId, String amount) {
        return new ReportDetailLine(
                transactionId, accountId, "DB", "Purchase", 5, "Retail", "POS", new BigDecimal(amount));
    }

    /**
     * Drives the full writer lifecycle for {@code items}, partitioning them into chunks of
     * {@code chunkSize} (to exercise cross-chunk statefulness), and returns the rendered report as a
     * list of fixed-width lines. The report payload is captured during the mocked S3 upload because
     * the production writer deletes its temp buffer immediately afterwards.
     *
     * @param pageSize  the page-break threshold for this run
     * @param chunkSize the number of items fed per {@code write(Chunk)} call; must be positive
     * @param items     the pre-sorted (by account id) detail rows to render
     * @return the emitted report records, each stripped of its trailing {@code '\n'}
     */
    private List<String> render(int pageSize, int chunkSize, List<ReportDetailLine> items) {
        return render(pageSize, chunkSize, items, "", "");
    }

    /**
     * Drives the full writer lifecycle for {@code items} using an explicit report date window (the
     * values a job-parameter-driven launch resolves), partitioning them into chunks of
     * {@code chunkSize} (to exercise cross-chunk statefulness), and returns the rendered report as a
     * list of fixed-width lines. The report payload is captured during the mocked S3 upload because
     * the production writer deletes its temp buffer immediately afterwards.
     *
     * @param pageSize  the page-break threshold for this run
     * @param chunkSize the number of items fed per {@code write(Chunk)} call; must be positive
     * @param items     the pre-sorted (by account id) detail rows to render
     * @param startDate the report start date rendered in the name header ({@code REPT-START-DATE})
     * @param endDate   the report end date rendered in the name header ({@code REPT-END-DATE})
     * @return the emitted report records, each stripped of its trailing {@code '\n'}
     */
    private List<String> render(int pageSize, int chunkSize, List<ReportDetailLine> items,
                                String startDate, String endDate) {
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        doAnswer(invocation -> {
            InputStream uploaded = invocation.getArgument(2);
            captured.writeBytes(uploaded.readAllBytes());
            return null;
        }).when(s3Template).upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class));

        TransactionReportItemWriter writer = newWriter(pageSize, startDate, endDate);
        writer.open(new ExecutionContext());
        for (int i = 0; i < items.size(); i += chunkSize) {
            List<ReportDetailLine> slice =
                    new ArrayList<>(items.subList(i, Math.min(i + chunkSize, items.size())));
            writer.write(new Chunk<>(slice));
        }
        writer.close();

        return splitRecords(captured.toString(REPORT_CHARSET));
    }

    /**
     * Splits a captured report payload into its constituent records, dropping the trailing empty
     * element produced by the final line terminator.
     *
     * @param payload the raw report bytes decoded with the production charset
     * @return the ordered list of records (without line terminators)
     */
    private static List<String> splitRecords(String payload) {
        List<String> records = new ArrayList<>(Arrays.asList(payload.split("\n", -1)));
        if (!records.isEmpty() && records.get(records.size() - 1).isEmpty()) {
            records.remove(records.size() - 1);
        }
        return records;
    }

    /**
     * Parses the {@link BigDecimal} amount out of a total line ({@code Page/Account/Grand Total}),
     * reversing the COBOL edited picture {@code +ZZZ,ZZZ,ZZZ.ZZ}: the first field character is the
     * sign and the remaining 14 characters are the zero-suppressed, comma-grouped numeric field.
     *
     * @param totalLine a rendered total record
     * @return the parsed amount, normalised to scale 2
     */
    private static BigDecimal totalAmountOf(String totalLine) {
        String field = totalLine.substring(TOTAL_PREFIX_LENGTH, TOTAL_PREFIX_LENGTH + AMOUNT_FIELD_LENGTH);
        char sign = field.charAt(0);
        String digits = field.substring(1).replace(",", "").replace(" ", "");
        BigDecimal value = digits.isEmpty() ? BigDecimal.ZERO : new BigDecimal(digits);
        if (sign == '-') {
            value = value.negate();
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sums the amounts of the given detail rows to scale 2 &mdash; the independent expectation used to
     * reconcile the writer's emitted totals.
     *
     * @param items the detail rows
     * @return the exact sum at scale 2
     */
    private static BigDecimal sumOf(List<ReportDetailLine> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ReportDetailLine item : items) {
            sum = sum.add(item.amount());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** @return the amounts of every total line matching {@code prefix}, in emission order. */
    private static List<BigDecimal> totalsWithPrefix(List<String> records, String prefix) {
        List<BigDecimal> totals = new ArrayList<>();
        for (String record : records) {
            if (record.startsWith(prefix)) {
                totals.add(totalAmountOf(record));
            }
        }
        return totals;
    }

    /** @return {@code true} if the record is a report name-header line ({@code REPORT-NAME-HEADER}). */
    private static boolean isNameHeader(String record) {
        return record.startsWith("DALYREPT");
    }

    /** @return {@code true} if the record is the column-header line ({@code TRANSACTION-HEADER-1}). */
    private static boolean isColumnHeader(String record) {
        return record.startsWith("Transaction ID");
    }

    /** @return {@code true} if the record is a full-width separator line ({@code ALL '-'}). */
    private static boolean isSeparator(String record) {
        return record.length() == RECORD_LENGTH && record.chars().allMatch(c -> c == '-');
    }

    /** @return the index of the first record starting with {@code prefix}, or {@code -1}. */
    private static int firstIndexMatching(List<String> records, String prefix) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** @return the index of the last record starting with {@code prefix}, or {@code -1}. */
    private static int lastIndexMatching(List<String> records, String prefix) {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Builds a canonical, pre-sorted input of two accounts with six detail rows each &mdash; enough,
     * at {@link #SMALL_PAGE_SIZE}, to cross several page boundaries and to force one account control
     * break. The rows are returned strictly in ascending account order (the precondition the upstream
     * reader/comparator guarantees for this stateful writer).
     *
     * @return the two-account, twelve-row fixture
     */
    private static List<ReportDetailLine> twoAccountsAcrossPages() {
        final long accountA = 100000000001L;
        final long accountB = 100000000002L;
        final String[] amountsA = {"10.00", "20.50", "5.25", "3.00", "1.75", "8.10"};
        final String[] amountsB = {"100.00", "2.20", "30.30", "4.40", "50.50", "6.60"};
        final List<ReportDetailLine> items = new ArrayList<>();
        for (int i = 0; i < amountsA.length; i++) {
            items.add(line(String.format("TXNA%012d", i + 1), accountA, amountsA[i]));
        }
        for (int i = 0; i < amountsB.length; i++) {
            items.add(line(String.format("TXNB%012d", i + 1), accountB, amountsB[i]));
        }
        return items;
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 2 — fixed 133-character record width (byte parity, Gate 1 / Gate 5)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 2 — every emitted record (header/detail/total) is exactly 133 characters")
    void everyEmittedRecordIsExactly133Chars() {
        List<ReportDetailLine> items = twoAccountsAcrossPages();
        List<String> records = render(SMALL_PAGE_SIZE, 3, items);

        assertThat(records).as("the run must emit records").isNotEmpty();
        assertThat(records).allSatisfy(record ->
                assertThat(record).as("fixed record width").hasSize(RECORD_LENGTH));

        // Confirm the run actually exercised every record category whose width we just asserted.
        assertThat(records).as("name header present").anyMatch(TransactionReportItemWriterTest::isNameHeader);
        assertThat(records).as("column header present").anyMatch(TransactionReportItemWriterTest::isColumnHeader);
        assertThat(records).as("separator present").anyMatch(TransactionReportItemWriterTest::isSeparator);
        assertThat(records).as("page total present").anyMatch(r -> r.startsWith("Page Total"));
        assertThat(records).as("account total present").anyMatch(r -> r.startsWith("Account Total"));
        assertThat(records).as("grand total present").anyMatch(r -> r.startsWith("Grand Total"));
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 3 — page headers and pagination (WS-FIRST-TIME + MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 3 — a page header is written at the top and re-emitted after each page break")
    void pageHeaderPrintedAtTopAndAfterEachPageBreak() {
        List<String> records = render(SMALL_PAGE_SIZE, 4, twoAccountsAcrossPages());

        // The opening header block sits at the very top: name header, blank, column header, separator.
        assertThat(records).hasSizeGreaterThanOrEqualTo(4);
        assertThat(isNameHeader(records.get(0))).as("record 0 is the report name header").isTrue();
        assertThat(records.get(1)).as("record 1 is the blank line").isEqualTo(" ".repeat(RECORD_LENGTH));
        assertThat(isColumnHeader(records.get(2))).as("record 2 is the column header").isTrue();
        assertThat(isSeparator(records.get(3))).as("record 3 is the separator").isTrue();

        // More than one header block ⇒ the header was re-emitted after at least one page break.
        long headerBlocks = records.stream().filter(TransactionReportItemWriterTest::isNameHeader).count();
        assertThat(headerBlocks).as("number of page-header blocks").isGreaterThanOrEqualTo(2);

        // Structural invariant: the ONLY producer of a header after the first is the page-break path,
        // which always emits a page-total line + separator immediately before the fresh header block.
        for (int i = 1; i < records.size(); i++) {
            if (isNameHeader(records.get(i))) {
                assertThat(i).as("a re-emitted header follows a full page-break block").isGreaterThanOrEqualTo(2);
                assertThat(isSeparator(records.get(i - 1)))
                        .as("a separator immediately precedes the re-emitted header at index %d", i).isTrue();
                assertThat(records.get(i - 2))
                        .as("a page total precedes the separator before the header at index %d", i)
                        .startsWith("Page Total");
            }
        }
    }

    @Test
    @DisplayName("Phase 3 — per-page totals reset each page and sum back to the grand total")
    void pageTotalsResetEachPageAndReconcileToGrandTotal() {
        List<ReportDetailLine> items = twoAccountsAcrossPages();
        List<String> records = render(SMALL_PAGE_SIZE, 2, items);

        List<BigDecimal> pageTotals = totalsWithPrefix(records, "Page Total");
        assertThat(pageTotals).as("multiple page totals across the run").hasSizeGreaterThanOrEqualTo(2);

        BigDecimal pageTotalSum = pageTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grandTotal = totalsWithPrefix(records, "Grand Total").get(0);

        // Had pageTotal not reset each page, the cumulative page totals would exceed the grand total.
        assertThat(pageTotalSum).as("Σ(page totals) reconciles to the grand total")
                .isEqualByComparingTo(grandTotal);
        assertThat(grandTotal).as("grand total equals Σ(all details)").isEqualByComparingTo(sumOf(items));
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 3b — report name-header "Date Range" (REPT-START-DATE / REPT-END-DATE ← job parameters)
    // ------------------------------------------------------------------------------------------------

    /**
     * The report name header renders the resolved reporting window in its {@code "Date Range: "} field
     * ({@code REPT-START-DATE} / {@code REPT-END-DATE} of {@code CVTRA07Y}). The window a run is
     * launched with — for the SQS report bridge, the {@code startDate} / {@code endDate} job
     * parameters resolved by the writer's {@code #{jobParameters['startDate'] ?: ...}} binding — must
     * appear verbatim in the header, byte-for-byte with the legacy
     * {@code MOVE WS-START-DATE TO REPT-START-DATE} / {@code MOVE WS-END-DATE TO REPT-END-DATE}
     * (CBTRN03C). This is the regression guard for the CP4 report-contract fix: the writer must render
     * the launched window, never a static/blank default, so the header can never disagree with the
     * window {@code TransactionReportProcessor} actually filtered on.
     *
     * <p>Two distinct windows are rendered to prove the dates are carried through rather than a
     * constant: each header reflects its own supplied window, at the exact columns the copybook fixes
     * (the {@code "Date Range: "} field begins at offset {@value #NAME_HEADER_DATE_RANGE_OFFSET}).</p>
     */
    @Test
    @DisplayName("Phase 3b — the name header renders the launched date window verbatim (REPT-START/END-DATE)")
    void nameHeaderRendersSuppliedDateWindow() {
        List<ReportDetailLine> items = List.of(line("TXN0000000000001", 100000000001L, "10.00"));

        List<String> firstRun = render(LARGE_PAGE_SIZE, 1, items, "2023-01-01", "2023-12-31");
        String firstHeader = firstRun.get(0);
        assertThat(isNameHeader(firstHeader)).as("record 0 is the report name header").isTrue();
        assertThat(firstHeader).as("fixed record width (Gate 1/5)").hasSize(RECORD_LENGTH);
        assertThat(firstHeader).as("the human-readable Date Range reflects the launched window")
                .contains("Date Range: 2023-01-01 to 2023-12-31");
        assertThat(firstHeader.substring(NAME_HEADER_DATE_RANGE_OFFSET,
                        NAME_HEADER_DATE_RANGE_OFFSET + DATE_RANGE_FIELD_LENGTH))
                .as("the Date Range field occupies its exact CVTRA07Y columns")
                .isEqualTo("Date Range: 2023-01-01 to 2023-12-31");

        // A second launch with a different window must produce a different header — proving the dates
        // are carried through the (job-parameter) binding, not a static default or a blank field.
        List<String> secondRun = render(LARGE_PAGE_SIZE, 1, items, "2021-02-03", "2021-04-05");
        assertThat(secondRun.get(0))
                .as("a second launch renders its own window, not the first run's dates or a constant")
                .contains("Date Range: 2021-02-03 to 2021-04-05");
    }

    /**
     * Root-cause guard for the CP4 report-contract fix. The rendering test above proves the header
     * echoes whatever window the writer is constructed with; this test proves the writer is
     * <em>constructed</em> from the per-run job parameters (the SQS-launched window) rather than a
     * static property alone. It inspects the {@code @Value} binding on the two date constructor
     * parameters and asserts each prefers {@code jobParameters['startDate']} / {@code ['endDate']}
     * before falling back to the {@code carddemo.batch.report.*-date} property — the identical
     * three-level chain used by {@link TransactionReportProcessor}. A regression to a
     * static-property-only binding (the exact defect this checkpoint fixes) would drop the
     * {@code jobParameters[...]} term and fail here, catching the mismatch without needing a full
     * Spring Batch step context.
     */
    @Test
    @DisplayName("Phase 3b — the date constructor params are @Value-bound to job parameters (property/JCL fallback)")
    void constructorBindsReportWindowFromJobParameters() throws NoSuchMethodException {
        Constructor<TransactionReportItemWriter> ctor = TransactionReportItemWriter.class.getConstructor(
                S3Template.class, int.class, String.class, String.class, String.class, String.class);
        Parameter[] params = ctor.getParameters();

        Value startBinding = params[4].getAnnotation(Value.class);
        Value endBinding = params[5].getAnnotation(Value.class);
        assertThat(startBinding).as("reportStartDate must carry a @Value binding").isNotNull();
        assertThat(endBinding).as("reportEndDate must carry a @Value binding").isNotNull();

        assertThat(startBinding.value())
                .as("start-date binding must prefer the job parameter, then the property fallback")
                .contains("jobParameters['startDate']")
                .contains("carddemo.batch.report.start-date");
        assertThat(endBinding.value())
                .as("end-date binding must prefer the job parameter, then the property fallback")
                .contains("jobParameters['endDate']")
                .contains("carddemo.batch.report.end-date");
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 4 — account control break (1120-WRITE-ACCOUNT-TOTALS on a change of account id)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 4 — a subtotal is emitted on account change; the final subtotal precedes the grand total")
    void accountControlBreakEmitsSubtotalPerAccount() {
        final long accountA = 555000000001L;
        final long accountB = 555000000002L;
        List<ReportDetailLine> accountA_rows = List.of(
                line("TXNA000000000001", accountA, "10.00"),
                line("TXNA000000000002", accountA, "20.50"),
                line("TXNA000000000003", accountA, "5.25"));
        List<ReportDetailLine> accountB_rows = List.of(
                line("TXNB000000000001", accountB, "100.00"),
                line("TXNB000000000002", accountB, "-30.25"));
        List<ReportDetailLine> items = new ArrayList<>();
        items.addAll(accountA_rows);
        items.addAll(accountB_rows);

        // Large page size ⇒ no page break, so there is exactly one account-total line per account.
        List<String> records = render(LARGE_PAGE_SIZE, 5, items);

        List<BigDecimal> accountTotals = totalsWithPrefix(records, "Account Total");
        assertThat(accountTotals).as("one subtotal per account").hasSize(2);
        assertThat(accountTotals.get(0)).as("account A subtotal = Σ(A)").isEqualByComparingTo(sumOf(accountA_rows));
        assertThat(accountTotals.get(1)).as("account B subtotal = Σ(B)").isEqualByComparingTo(sumOf(accountB_rows));
        assertThat(accountTotals.get(0).scale()).as("subtotal scale").isEqualTo(2);
        assertThat(accountTotals.get(1).scale()).as("subtotal scale").isEqualTo(2);

        // The final account's subtotal must be flushed before the grand total.
        int lastAccountTotalIndex = lastIndexMatching(records, "Account Total");
        int grandTotalIndex = firstIndexMatching(records, "Grand Total");
        assertThat(grandTotalIndex).as("a grand total is present").isNotNegative();
        assertThat(lastAccountTotalIndex).as("final account subtotal precedes the grand total")
                .isLessThan(grandTotalIndex);
    }

    @Test
    @DisplayName("Phase 4 — account totals reset per account and sum back to the grand total")
    void accountTotalsResetPerAccountAndReconcileToGrandTotal() {
        List<ReportDetailLine> items = twoAccountsAcrossPages();
        List<String> records = render(SMALL_PAGE_SIZE, 3, items);

        List<BigDecimal> accountTotals = totalsWithPrefix(records, "Account Total");
        assertThat(accountTotals).as("two accounts ⇒ two subtotals").hasSize(2);

        BigDecimal accountTotalSum = accountTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grandTotal = totalsWithPrefix(records, "Grand Total").get(0);
        assertThat(accountTotalSum).as("Σ(account totals) reconciles to the grand total")
                .isEqualByComparingTo(grandTotal);
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 5 — grand total reconciliation (the decimal-fidelity guarantee: grand == Σ details)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 5 — the grand total equals the sum of every detail amount (decimal fidelity)")
    void grandTotalEqualsSumOfAllDetailAmounts() {
        List<ReportDetailLine> items = new ArrayList<>();
        items.add(line("TXN0000000000001", 700000000001L, "1234.56"));
        items.add(line("TXN0000000000002", 700000000001L, "0.44"));
        items.add(line("TXN0000000000003", 700000000002L, "-15.20")); // negative amount exercises the '-' sign
        items.add(line("TXN0000000000004", 700000000002L, "500.00"));
        items.add(line("TXN0000000000005", 700000000003L, "9.99"));

        List<String> records = render(SMALL_PAGE_SIZE, 1, items);

        List<BigDecimal> grandTotals = totalsWithPrefix(records, "Grand Total");
        assertThat(grandTotals).as("exactly one grand total line").hasSize(1);

        BigDecimal grandTotal = grandTotals.get(0);
        assertThat(grandTotal).as("grand total == Σ(all details)").isEqualByComparingTo(sumOf(items));
        assertThat(grandTotal.scale()).as("grand total is scale 2").isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 6 — S3 sink contract (one upload to carddemo-batch-output/tranrept.dat on close())
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 6 — exactly one upload to carddemo-batch-output/tranrept.dat on close()")
    void uploadsOnceToConfiguredBucketAndKeyOnClose() {
        render(SMALL_PAGE_SIZE, 4, twoAccountsAcrossPages());

        verify(s3Template, times(1))
                .upload(eq(OUTPUT_BUCKET), eq(OBJECT_KEY), any(InputStream.class), any(ObjectMetadata.class));
    }

    // ------------------------------------------------------------------------------------------------
    // Robustness — empty run and cross-chunk statefulness
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an empty run (no detail rows) produces no S3 upload")
    void emptyRunProducesNoUpload() {
        TransactionReportItemWriter writer = newWriter(SMALL_PAGE_SIZE);
        writer.open(new ExecutionContext());
        writer.write(new Chunk<ReportDetailLine>()); // an empty chunk must be tolerated
        writer.close();

        verifyNoInteractions(s3Template);
    }

    @Test
    @DisplayName("the rendered report is identical regardless of chunk partitioning (stateful across chunks)")
    void outputIsIdenticalRegardlessOfChunking() {
        List<ReportDetailLine> items = twoAccountsAcrossPages();

        List<String> singleChunk = render(SMALL_PAGE_SIZE, items.size(), items);
        List<String> manyChunks = render(SMALL_PAGE_SIZE, 1, items);

        assertThat(manyChunks).as("chunk partitioning must not affect the rendered report")
                .containsExactlyElementsOf(singleChunk);
    }
}
