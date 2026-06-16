package com.carddemo.batch.processors;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that renders one customer account statement in two
 * variants &mdash; plain text and HTML &mdash; reproducing the per-account statement logic of the
 * mainframe statement-generation main program {@code app/cbl/CBSTM03A.CBL} and its file-access
 * subroutine {@code app/cbl/CBSTM03B.CBL} (source commit {@code 27d6c6f}); job context is
 * {@code app/jcl/CREASTMT.JCL}, which produces a text statement ({@code STMTFILE}, fixed record
 * {@code PIC X(80)}) and an HTML statement ({@code HTMLFILE}, fixed record {@code PIC X(100)}).
 *
 * <p><b>File service &rarr; repositories.</b> {@code CBSTM03B} is a generic file handler invoked
 * by {@code CBSTM03A} as {@code CALL 'CBSTM03B' USING LK-M03B-AREA} with an operation code
 * (open / close / sequential-read / keyed-read) against {@code XREFFILE}, {@code CUSTFILE},
 * {@code ACCTFILE}, and {@code TRNXFILE}. There is no separate Java class for it: its keyed and
 * sequential reads map directly onto the Spring Data JPA repositories injected here. The COBOL
 * keyed reads {@code 1000-XREFFILE-GET-NEXT}, {@code 2000-CUSTFILE-GET}, and
 * {@code 3000-ACCTFILE-GET} become {@link CardCrossReferenceRepository#findByXrefAcctId(Long)},
 * {@link CustomerRepository#findById(Object)}, and {@link AccountRepository#findById(Object)}
 * respectively, and the transaction grouping of {@code 8500-READTRNX-READ} / {@code 4000-TRNXFILE-GET}
 * becomes an aggregation over {@link TransactionRepository}.</p>
 *
 * <p><b>Driver inversion.</b> The COBOL drives statement generation by sequentially reading the
 * cross-reference file (one statement per card cross-reference). In this migration the upstream
 * reader streams {@link Account} rows (the statement subjects); this processor resolves the
 * customer and card linkage from the cross-reference, aggregates the account's transactions, and
 * renders both variants. An account with no cross-reference has no card linkage and is skipped by
 * returning {@code null} (Spring Batch filters the item), matching the COBOL behaviour of never
 * emitting a statement for a card that is absent from the cross-reference file. A cross-reference
 * that points at a missing customer is a fatal data error: it raises
 * {@link RecordNotFoundException}, mirroring the COBOL keyed-read failure path
 * ({@code 9999-ABEND-PROGRAM} on a non-zero {@code CUSTFILE} return code).</p>
 *
 * <p><b>Template Method.</b> The processor assembles a single immutable {@link StatementData}
 * value and then runs two interchangeable {@link StatementRenderer} steps over it: a
 * {@link TextStatementRenderer} that reproduces the {@code ST-LINE0}..{@code ST-LINE15}
 * 80-column layout and an {@link HtmlStatementRenderer} that reproduces the
 * {@code 5100-WRITE-HTML-HEADER} / {@code 5200-WRITE-HTML-NMADBS} 100-column HTML-table layout
 * (fields wrapped in {@code <p>} tags). Both renderers consume the same data, so the two variants
 * always carry identical content.</p>
 *
 * <p><b>Decimal fidelity.</b> The current balance, every transaction amount, and the statement
 * total are {@link BigDecimal} at scale&nbsp;2; the total is the {@code BigDecimal} sum of the
 * account's transaction amounts (no floating point), reproducing the COBOL {@code COMP-3}
 * accumulation {@code ADD TRNX-AMT TO WS-TOTAL-AMT}. Numeric values are formatted for display
 * without precision loss using the COBOL edited-picture rules ({@code PIC 9(9).99-} for the
 * current balance, {@code PIC Z(9).99-} for transaction amounts and the total).</p>
 *
 * <p><b>Side-effect free.</b> This component performs reads only and never persists; writing the
 * two rendered variants to S3 is the responsibility of the downstream statement writer. Each
 * statement actually produced increments the {@code carddemo.batch.records.processed} counter;
 * a skipped account does not.</p>
 */
@Component
public class StatementProcessor implements ItemProcessor<Account, StatementProcessor.StatementResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatementProcessor.class);

    /** Fixed record width of the COBOL text statement ({@code STMTFILE}, {@code PIC X(80)}). */
    private static final int TEXT_WIDTH = 80;

    /** Fixed record width of the COBOL HTML statement ({@code HTMLFILE}, {@code PIC X(100)}). */
    private static final int HTML_WIDTH = 100;

    /** Money scale shared by balances, amounts, and the statement total (COBOL {@code V99}). */
    private static final int MONEY_SCALE = 2;

    // -- Text-layout labels (each rendered into a 20-column field, COBOL ST-LINE7/8/9) -----------
    private static final String LABEL_ACCT_TEXT = "Account ID" + " ".repeat(9) + ":";
    private static final String LABEL_BAL_TEXT = "Current Balance" + " ".repeat(4) + ":";
    private static final String LABEL_FICO_TEXT = "FICO Score" + " ".repeat(9) + ":";

    // -- HTML-layout labels (COBOL 5200-WRITE-HTML-NMADBS basic-details block) --------------------
    private static final String LABEL_ACCT_HTML = "Account ID" + " ".repeat(9) + ": ";
    private static final String LABEL_BAL_HTML = "Current Balance" + " ".repeat(4) + ": ";
    private static final String LABEL_FICO_HTML = "FICO Score" + " ".repeat(9) + ": ";

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final MeterRegistry meterRegistry;

    private final StatementRenderer textRenderer;
    private final StatementRenderer htmlRenderer;

    /**
     * Creates the processor with its repository collaborators (the {@code CBSTM03B} file-service
     * equivalents), the meter registry, and the two statement renderers.
     *
     * @param cardCrossReferenceRepository resolves the card/account/customer linkage
     *                                     ({@code XREFFILE} keyed read by account id)
     * @param customerRepository           resolves the statement customer ({@code CUSTFILE} keyed read)
     * @param transactionRepository        source of the account's transactions for the summary section
     * @param accountRepository            re-reads the account record ({@code ACCTFILE} keyed read)
     * @param meterRegistry                Micrometer registry for the processed-records counter
     */
    public StatementProcessor(CardCrossReferenceRepository cardCrossReferenceRepository,
                              CustomerRepository customerRepository,
                              TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              MeterRegistry meterRegistry) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.meterRegistry = meterRegistry;
        this.textRenderer = new TextStatementRenderer();
        this.htmlRenderer = new HtmlStatementRenderer();
    }

    /**
     * Resolves the inputs for one account's statement, renders both variants, and returns the
     * combined {@link StatementResult}. Returns {@code null} (a Spring Batch filter) when the
     * account has no card cross-reference, so no statement is produced for an unlinked account.
     *
     * @param account the statement subject supplied by the upstream account reader
     * @return the rendered statement, or {@code null} to skip an account with no cross-reference
     * @throws RecordNotFoundException if the cross-reference points at a customer that does not exist
     */
    @Override
    public StatementResult process(Account account) {
        // 1000-XREFFILE-GET-NEXT: resolve the card/customer linkage keyed by account id.
        List<CardCrossReference> crossReferences =
                cardCrossReferenceRepository.findByXrefAcctId(account.getAcctId());
        if (crossReferences == null || crossReferences.isEmpty()) {
            LOGGER.info("No card cross-reference for account {}; skipping statement (no card linkage)",
                    account.getAcctId());
            return null;
        }

        // 2000-CUSTFILE-GET: keyed customer read; a missing customer is a fatal data error.
        CardCrossReference primary = crossReferences.get(0);
        Long customerId = primary.getXrefCustId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Customer", customerId));

        // 3000-ACCTFILE-GET: keyed account read; fall back to the supplied row if absent.
        Account statementAccount = accountRepository.findById(primary.getXrefAcctId())
                .orElse(account);

        // 8500-READTRNX-READ / 4000-TRNXFILE-GET: gather the transactions for the resolved cards.
        // Fetch only the rows for the resolved card numbers through the idx_tran_card_num
        // alternate index (ordered by card number then id at the database) instead of scanning
        // the entire TRANSACT table in memory once per account.
        Set<String> cardNumbers = crossReferences.stream()
                .map(CardCrossReference::getXrefCardNum)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<TransactionSummaryLine> summaryLines = cardNumbers.isEmpty()
                ? List.of()
                : transactionRepository
                        .findByTranCardNumInOrderByTranCardNumAscTranIdAsc(cardNumbers).stream()
                        .map(StatementProcessor::toSummaryLine)
                        .toList();

        BigDecimal total = summaryLines.stream()
                .map(TransactionSummaryLine::tranAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);

        StatementData data = new StatementData(
                statementAccount.getAcctId(),
                composeName(customer),
                customer.getCustAddrLine1(),
                customer.getCustAddrLine2(),
                composeAddress3(customer),
                scaleMoney(statementAccount.getAcctCurrBal()),
                customer.getCustFicoCreditScore(),
                summaryLines,
                total);

        StatementResult result = renderStatement(data);
        MetricsConfig.recordsProcessed(meterRegistry).increment();
        return result;
    }

    /**
     * Template method: runs the text renderer and the HTML renderer over the shared
     * {@link StatementData} and assembles the combined {@link StatementResult}.
     *
     * @param data the resolved statement inputs
     * @return both rendered variants plus the summary header fields
     */
    private StatementResult renderStatement(StatementData data) {
        String textStatement = textRenderer.render(data);
        String htmlStatement = htmlRenderer.render(data);
        String customerName = (data.customerName() == null) ? null : data.customerName().strip();
        return new StatementResult(
                data.accountId(),
                customerName,
                data.currentBalance(),
                textStatement,
                htmlStatement);
    }

    /**
     * Maps a transaction entity to its summary-line view, normalising the amount to the money
     * scale so the renderers and the total share an identical representation.
     *
     * @param transaction the source transaction
     * @return the summary line for the statement transaction section
     */
    private static TransactionSummaryLine toSummaryLine(Transaction transaction) {
        return new TransactionSummaryLine(
                transaction.getTranId(),
                transaction.getTranDesc(),
                scaleMoney(transaction.getTranAmt()));
    }

    /**
     * Composes the customer name exactly as COBOL {@code 5000-CREATE-STATEMENT} does:
     * {@code STRING CUST-FIRST-NAME DELIMITED BY ' ' ' ' CUST-MIDDLE-NAME DELIMITED BY ' ' ' '
     * CUST-LAST-NAME DELIMITED BY ' ' ' '}. Each component contributes the text up to its first
     * embedded space, the components are joined with single spaces, and a trailing space follows
     * the last component (the final {@code DELIMITED BY SIZE} literal).
     *
     * @param customer the resolved customer
     * @return the composed {@code ST-NAME} value (single-space separated, one trailing space)
     */
    private static String composeName(Customer customer) {
        String first = tokenBeforeFirstSpace(customer.getCustFirstName());
        String middle = tokenBeforeFirstSpace(customer.getCustMiddleName());
        String last = tokenBeforeFirstSpace(customer.getCustLastName());
        return first + " " + middle + " " + last + " ";
    }

    /**
     * Composes the third address line exactly as COBOL {@code 5000-CREATE-STATEMENT} does:
     * {@code STRING CUST-ADDR-LINE-3 DELIMITED BY ' ' ' ' CUST-ADDR-STATE-CD ... CUST-ADDR-COUNTRY-CD
     * ... CUST-ADDR-ZIP ... INTO ST-ADD3}. Each component contributes the text up to its first
     * embedded space and the components are joined with single spaces with one trailing space.
     *
     * @param customer the resolved customer
     * @return the composed {@code ST-ADD3} value
     */
    private static String composeAddress3(Customer customer) {
        String line3 = tokenBeforeFirstSpace(customer.getCustAddrLine3());
        String state = tokenBeforeFirstSpace(customer.getCustAddrStateCd());
        String country = tokenBeforeFirstSpace(customer.getCustAddrCountryCd());
        String zip = tokenBeforeFirstSpace(customer.getCustAddrZip());
        return line3 + " " + state + " " + country + " " + zip + " ";
    }

    /**
     * Normalises a monetary value to the statement money scale, treating {@code null} as zero.
     * Uses {@link RoundingMode#HALF_EVEN} (banker's rounding), the COBOL {@code COMP-3} default.
     *
     * @param value the source amount, possibly {@code null}
     * @return the value at scale {@link #MONEY_SCALE}, never {@code null}
     */
    private static BigDecimal scaleMoney(BigDecimal value) {
        BigDecimal source = (value == null) ? BigDecimal.ZERO : value;
        return source.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Returns the prefix of the supplied value up to (but excluding) its first space, reproducing
     * COBOL {@code STRING ... DELIMITED BY ' '} on a space-padded sending field. A {@code null}
     * value yields an empty string.
     *
     * @param value the source field
     * @return the text before the first space, or the whole value when it contains no space
     */
    private static String tokenBeforeFirstSpace(String value) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf(' ');
        return (index < 0) ? value : value.substring(0, index);
    }

    /**
     * Extracts the visible content of a value for an HTML {@code <p>} field, reproducing COBOL
     * {@code STRING ... DELIMITED BY '  '} (two spaces) on a space-padded sending field: the text
     * up to the first double space, with any single trailing space removed.
     *
     * @param value the source field
     * @return the content up to the first double space, right-trimmed
     */
    private static String htmlText(String value) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf("  ");
        String content = (index < 0) ? value : value.substring(0, index);
        return content.stripTrailing();
    }

    /**
     * Right-pads (with spaces) or truncates a value to an exact width, reproducing the fixed-width
     * placement of a COBOL alphanumeric field. A {@code null} value is treated as empty.
     *
     * @param value the source text
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String padRight(String value, int width) {
        String source = (value == null) ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Renders a {@code Long} account id as the COBOL {@code PIC 9(11)} display value
     * (zero-filled to 11 digits), as moved into {@code ST-ACCT-ID} / {@code L11-ACCT}.
     *
     * @param accountId the account id, possibly {@code null}
     * @return the 11-digit zero-filled account id
     */
    private static String formatAccountId(Long accountId) {
        long value = (accountId == null || accountId < 0L) ? 0L : accountId;
        return String.format("%011d", value);
    }

    /**
     * Renders a FICO score as the COBOL {@code PIC 9(3)} display value (zero-filled to 3 digits),
     * as moved into {@code ST-FICO-SCORE}.
     *
     * @param fico the FICO score, possibly {@code null}
     * @return the 3-digit zero-filled score
     */
    private static String formatFico(Integer fico) {
        int value = (fico == null || fico < 0) ? 0 : fico;
        return String.format("%03d", value);
    }

    /**
     * Formats a monetary value as the COBOL edited picture {@code PIC 9(9).99-}: nine zero-filled
     * integer digits, a decimal point, two fraction digits, and a trailing sign ({@code '-'} for
     * negative, space otherwise). The result is always 13 characters.
     *
     * @param amount the amount to format
     * @return the {@code PIC 9(9).99-} rendering (13 characters)
     */
    private static String formatCurrencySigned(BigDecimal amount) {
        String[] parts = moneyParts(amount);
        return parts[0] + "." + parts[1] + parts[2];
    }

    /**
     * Formats a monetary value as the COBOL edited picture {@code PIC Z(9).99-}: nine integer
     * digits with leading zeros suppressed to spaces, a decimal point, two fraction digits, and a
     * trailing sign ({@code '-'} for negative, space otherwise). The result is always 13
     * characters.
     *
     * @param amount the amount to format
     * @return the {@code PIC Z(9).99-} rendering (13 characters)
     */
    private static String formatCurrencySuppressed(BigDecimal amount) {
        String[] parts = moneyParts(amount);
        return suppressLeadingZeros(parts[0]) + "." + parts[1] + parts[2];
    }

    /**
     * Splits a monetary value into the nine-digit zero-filled integer part, the two-digit fraction
     * part, and the trailing sign character, applying {@link RoundingMode#HALF_EVEN}. An integer
     * part wider than nine digits is truncated on the high order, matching COBOL picture overflow.
     *
     * @param amount the amount, possibly {@code null} (treated as zero)
     * @return a three-element array: {@code [integerPart(9), fractionPart(2), sign(1)]}
     */
    private static String[] moneyParts(BigDecimal amount) {
        BigDecimal value = scaleMoney(amount);
        char sign = (value.signum() < 0) ? '-' : ' ';
        String digits = value.abs().movePointRight(MONEY_SCALE).toBigInteger().toString();
        if (digits.length() < 11) {
            digits = "0".repeat(11 - digits.length()) + digits;
        } else if (digits.length() > 11) {
            digits = digits.substring(digits.length() - 11);
        }
        String integerPart = digits.substring(0, 9);
        String fractionPart = digits.substring(9);
        return new String[] {integerPart, fractionPart, String.valueOf(sign)};
    }

    /**
     * Replaces the leading zeros of a digit string with spaces, reproducing COBOL {@code Z} zero
     * suppression; an all-zero value becomes all spaces.
     *
     * @param digits the zero-filled digit string
     * @return the string with leading zeros converted to spaces
     */
    private static String suppressLeadingZeros(String digits) {
        StringBuilder builder = new StringBuilder(digits);
        for (int i = 0; i < builder.length() && builder.charAt(i) == '0'; i++) {
            builder.setCharAt(i, ' ');
        }
        return builder.toString();
    }

    /**
     * Appends one fixed-width statement record: the content padded or truncated to {@code width}
     * columns followed by a line separator (the modern materialisation of a fixed-block record).
     *
     * @param builder the accumulating output buffer
     * @param content the record content
     * @param width   the fixed record width
     */
    private static void appendLine(StringBuilder builder, String content, int width) {
        builder.append(padRight(content, width)).append('\n');
    }

    /**
     * Combined output of one rendered statement: the summary header fields plus both rendered
     * variants. The downstream statement writer persists {@link #textStatement()} and
     * {@link #htmlStatement()} to S3.
     *
     * @param accountId      the statement account id
     * @param customerName   the composed customer name (trimmed)
     * @param currentBalance the account current balance at scale 2
     * @param textStatement  the complete plain-text statement (80-column records)
     * @param htmlStatement  the complete HTML statement (100-column records)
     */
    public record StatementResult(Long accountId,
                                  String customerName,
                                  BigDecimal currentBalance,
                                  String textStatement,
                                  String htmlStatement) {
    }

    /**
     * Immutable, renderer-agnostic data for one statement &mdash; the shared input both renderers
     * consume in the Template Method. The transaction list is defensively copied.
     *
     * @param accountId      the account id ({@code ST-ACCT-ID} / {@code L11-ACCT})
     * @param customerName   the composed {@code ST-NAME}
     * @param addressLine1   the first address line ({@code ST-ADD1})
     * @param addressLine2   the second address line ({@code ST-ADD2})
     * @param addressLine3   the composed third address line ({@code ST-ADD3})
     * @param currentBalance the current balance ({@code ST-CURR-BAL}) at scale 2
     * @param ficoScore      the FICO score ({@code ST-FICO-SCORE})
     * @param transactions   the transaction summary lines in display order
     * @param total          the {@code BigDecimal} sum of the transaction amounts at scale 2
     */
    record StatementData(Long accountId,
                         String customerName,
                         String addressLine1,
                         String addressLine2,
                         String addressLine3,
                         BigDecimal currentBalance,
                         Integer ficoScore,
                         List<TransactionSummaryLine> transactions,
                         BigDecimal total) {

        StatementData {
            transactions = (transactions == null) ? List.of() : List.copyOf(transactions);
        }
    }

    /**
     * One transaction row of the statement transaction-summary section.
     *
     * @param tranId   the transaction id ({@code ST-TRANID})
     * @param tranDesc the transaction description ({@code ST-TRANDT})
     * @param tranAmt  the transaction amount ({@code ST-TRANAMT}) at scale 2
     */
    record TransactionSummaryLine(String tranId, String tranDesc, BigDecimal tranAmt) {
    }

    /**
     * Strategy for rendering a {@link StatementData} into one output variant. The two
     * implementations are the injectable output steps of the Template Method.
     */
    interface StatementRenderer {

        /**
         * Renders the supplied statement data into a single variant string.
         *
         * @param data the resolved statement inputs
         * @return the rendered statement
         */
        String render(StatementData data);
    }

    /**
     * Plain-text renderer reproducing the COBOL {@code ST-LINE0}..{@code ST-LINE15} 80-column
     * layout written to {@code STMTFILE}.
     */
    private static final class TextStatementRenderer implements StatementRenderer {

        @Override
        public String render(StatementData data) {
            StringBuilder builder = new StringBuilder();
            String dashes = "-".repeat(TEXT_WIDTH);

            // ST-LINE0: start-of-statement banner.
            appendLine(builder, "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31), TEXT_WIDTH);
            // ST-LINE1..ST-LINE4: name and address block.
            appendLine(builder, data.customerName(), TEXT_WIDTH);
            appendLine(builder, data.addressLine1(), TEXT_WIDTH);
            appendLine(builder, data.addressLine2(), TEXT_WIDTH);
            appendLine(builder, data.addressLine3(), TEXT_WIDTH);
            // ST-LINE5 / ST-LINE6 / ST-LINE5: basic-details banner.
            appendLine(builder, dashes, TEXT_WIDTH);
            appendLine(builder, " ".repeat(33) + "Basic Details " + " ".repeat(33), TEXT_WIDTH);
            appendLine(builder, dashes, TEXT_WIDTH);
            // ST-LINE7/8/9: account id, current balance, FICO score.
            appendLine(builder, LABEL_ACCT_TEXT + padRight(formatAccountId(data.accountId()), 20), TEXT_WIDTH);
            appendLine(builder, LABEL_BAL_TEXT + formatCurrencySigned(data.currentBalance()), TEXT_WIDTH);
            appendLine(builder, LABEL_FICO_TEXT + padRight(formatFico(data.ficoScore()), 20), TEXT_WIDTH);
            // ST-LINE10 / ST-LINE11 / ST-LINE12: transaction-summary banner and column headers.
            appendLine(builder, dashes, TEXT_WIDTH);
            appendLine(builder, " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30), TEXT_WIDTH);
            appendLine(builder, dashes, TEXT_WIDTH);
            appendLine(builder, padRight("Tran ID", 16) + padRight("Tran Details", 51) + "  Tran Amount", TEXT_WIDTH);
            appendLine(builder, dashes, TEXT_WIDTH);
            // ST-LINE14: one row per transaction.
            for (TransactionSummaryLine line : data.transactions()) {
                appendLine(builder, padRight(line.tranId(), 16) + " " + padRight(line.tranDesc(), 49)
                        + "$" + formatCurrencySuppressed(line.tranAmt()), TEXT_WIDTH);
            }
            // ST-LINE12 / ST-LINE14A / ST-LINE15: total and end-of-statement banner.
            appendLine(builder, dashes, TEXT_WIDTH);
            appendLine(builder, "Total EXP:" + " ".repeat(56) + "$" + formatCurrencySuppressed(data.total()), TEXT_WIDTH);
            appendLine(builder, "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32), TEXT_WIDTH);
            return builder.toString();
        }
    }

    /**
     * HTML renderer reproducing the COBOL {@code 5100-WRITE-HTML-HEADER} /
     * {@code 5200-WRITE-HTML-NMADBS} / {@code 6000-WRITE-TRANS} 100-column HTML-table layout
     * written to {@code HTMLFILE}. The bank header block is a fixed literal in the source.
     */
    private static final class HtmlStatementRenderer implements StatementRenderer {

        private static final String DOCTYPE = "<!DOCTYPE html>";
        private static final String HTML_OPEN = "<html lang=\"en\">";
        private static final String HEAD_OPEN = "<head>";
        private static final String META = "<meta charset=\"utf-8\">";
        private static final String TITLE = "<title>HTML Table Layout</title>";
        private static final String HEAD_CLOSE = "</head>";
        private static final String BODY_OPEN = "<body style=\"margin:0px;\">";
        private static final String TABLE_OPEN =
                "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
        private static final String TR_OPEN = "<tr>";
        private static final String TR_CLOSE = "</tr>";
        private static final String TD_CLOSE = "</td>";
        private static final String TD_NAVY =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
        private static final String TD_ORANGE =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
        private static final String TD_GREY =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
        private static final String TD_TEAL_CENTER =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
        private static final String TD_HDR_ID =
                "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
        private static final String TD_HDR_DETAILS =
                "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
        private static final String TD_HDR_AMOUNT =
                "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
        private static final String TD_ROW_ID =
                "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
        private static final String TD_ROW_DETAILS =
                "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
        private static final String TD_ROW_AMOUNT =
                "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
        private static final String BANK_NAME = "<p style=\"font-size:16px\">Bank of XYZ</p>";
        private static final String BANK_ADDR1 = "<p>410 Terry Ave N</p>";
        private static final String BANK_ADDR2 = "<p>Seattle WA 99999</p>";
        private static final String BASIC_DETAILS = "<p style=\"font-size:16px\">Basic Details</p>";
        private static final String TRANS_SUMMARY = "<p style=\"font-size:16px\">Transaction Summary</p>";
        private static final String HDR_TRAN_ID = "<p style=\"font-size:16px\">Tran ID</p>";
        private static final String HDR_TRAN_DETAILS = "<p style=\"font-size:16px\">Tran Details</p>";
        private static final String HDR_AMOUNT = "<p style=\"font-size:16px\">Amount</p>";
        private static final String END_OF_STATEMENT = "<h3>End of Statement</h3>";
        private static final String TABLE_CLOSE = "</table>";
        private static final String BODY_CLOSE = "</body>";
        private static final String HTML_CLOSE = "</html>";

        @Override
        public String render(StatementData data) {
            StringBuilder builder = new StringBuilder();
            String accountId = padRight(formatAccountId(data.accountId()), 20);

            // 5100-WRITE-HTML-HEADER: document head and bank header rows.
            appendLine(builder, DOCTYPE, HTML_WIDTH);
            appendLine(builder, HTML_OPEN, HTML_WIDTH);
            appendLine(builder, HEAD_OPEN, HTML_WIDTH);
            appendLine(builder, META, HTML_WIDTH);
            appendLine(builder, TITLE, HTML_WIDTH);
            appendLine(builder, HEAD_CLOSE, HTML_WIDTH);
            appendLine(builder, BODY_OPEN, HTML_WIDTH);
            appendLine(builder, TABLE_OPEN, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_NAVY, HTML_WIDTH);
            appendLine(builder, "<h3>Statement for Account Number: " + accountId + "</h3>", HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_ORANGE, HTML_WIDTH);
            appendLine(builder, BANK_NAME, HTML_WIDTH);
            appendLine(builder, BANK_ADDR1, HTML_WIDTH);
            appendLine(builder, BANK_ADDR2, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_GREY, HTML_WIDTH);

            // 5200-WRITE-HTML-NMADBS: name, address, and basic-details paragraphs.
            appendLine(builder, "<p style=\"font-size:16px\">" + htmlText(data.customerName()) + "  </p>", HTML_WIDTH);
            appendLine(builder, "<p>" + htmlText(data.addressLine1()) + "  </p>", HTML_WIDTH);
            appendLine(builder, "<p>" + htmlText(data.addressLine2()) + "  </p>", HTML_WIDTH);
            appendLine(builder, "<p>" + htmlText(data.addressLine3()) + "  </p>", HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_TEAL_CENTER, HTML_WIDTH);
            appendLine(builder, BASIC_DETAILS, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_GREY, HTML_WIDTH);
            appendLine(builder, "<p>" + LABEL_ACCT_HTML + accountId + "</p>", HTML_WIDTH);
            appendLine(builder, "<p>" + LABEL_BAL_HTML + formatCurrencySigned(data.currentBalance()) + "</p>", HTML_WIDTH);
            appendLine(builder, "<p>" + LABEL_FICO_HTML + padRight(formatFico(data.ficoScore()), 20) + "</p>", HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);

            // Transaction-summary banner and column headers.
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_TEAL_CENTER, HTML_WIDTH);
            appendLine(builder, TRANS_SUMMARY, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_HDR_ID, HTML_WIDTH);
            appendLine(builder, HDR_TRAN_ID, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TD_HDR_DETAILS, HTML_WIDTH);
            appendLine(builder, HDR_TRAN_DETAILS, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TD_HDR_AMOUNT, HTML_WIDTH);
            appendLine(builder, HDR_AMOUNT, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);

            // 6000-WRITE-TRANS: one row group per transaction.
            for (TransactionSummaryLine line : data.transactions()) {
                appendLine(builder, TR_OPEN, HTML_WIDTH);
                appendLine(builder, TD_ROW_ID, HTML_WIDTH);
                appendLine(builder, "<p>" + padRight(line.tranId(), 16) + "</p>", HTML_WIDTH);
                appendLine(builder, TD_CLOSE, HTML_WIDTH);
                appendLine(builder, TD_ROW_DETAILS, HTML_WIDTH);
                appendLine(builder, "<p>" + padRight(line.tranDesc(), 49) + "</p>", HTML_WIDTH);
                appendLine(builder, TD_CLOSE, HTML_WIDTH);
                appendLine(builder, TD_ROW_AMOUNT, HTML_WIDTH);
                appendLine(builder, "<p>" + formatCurrencySuppressed(line.tranAmt()) + "</p>", HTML_WIDTH);
                appendLine(builder, TD_CLOSE, HTML_WIDTH);
                appendLine(builder, TR_CLOSE, HTML_WIDTH);
            }

            // 4000-TRNXFILE-GET: end-of-statement footer and document close.
            appendLine(builder, TR_OPEN, HTML_WIDTH);
            appendLine(builder, TD_NAVY, HTML_WIDTH);
            appendLine(builder, END_OF_STATEMENT, HTML_WIDTH);
            appendLine(builder, TD_CLOSE, HTML_WIDTH);
            appendLine(builder, TR_CLOSE, HTML_WIDTH);
            appendLine(builder, TABLE_CLOSE, HTML_WIDTH);
            appendLine(builder, BODY_CLOSE, HTML_WIDTH);
            appendLine(builder, HTML_CLOSE, HTML_WIDTH);
            return builder.toString();
        }
    }
}
