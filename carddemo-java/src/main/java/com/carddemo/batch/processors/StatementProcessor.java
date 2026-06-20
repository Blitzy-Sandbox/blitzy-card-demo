package com.carddemo.batch.processors;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that renders a customer account statement in both the
 * plain-text and HTML variants emitted by the original mainframe statement-generation program.
 *
 * <p>Translated (reference only; the COBOL source is not copied &mdash; traceability is preserved
 * via source commit {@code 27d6c6f}) from {@code app/cbl/CBSTM03A.CBL} (statement-generation main),
 * which delegates all file access to {@code app/cbl/CBSTM03B.CBL} (a generic keyed/sequential
 * file-access subroutine). Job context is {@code app/jcl/CREASTMT.JCL}. {@code CBSTM03B} has no
 * Java counterpart: its keyed reads of the cross-reference, customer and account datasets, and its
 * sequential scan of the transaction dataset, are realized through the Spring Data JPA repositories
 * injected here (the {@code CALL 'CBSTM03B'} file service maps to repository finders).</p>
 *
 * <h2>Pipeline role</h2>
 * <p>The upstream reader iterates {@link Account} rows (the statement subjects). For each account
 * this processor resolves the owning customer and the account's card linkage through the
 * cross-reference, aggregates the account's transactions, and produces a {@link StatementResult}
 * carrying both rendered variants. The downstream writer persists the two variants to S3. This
 * component performs <strong>reads only</strong> &mdash; it never persists.</p>
 *
 * <h2>Rendering &mdash; Template Method</h2>
 * <p>A single algorithm assembles an immutable {@link StatementData} holder; two interchangeable
 * {@link StatementRenderer} implementations (a plain-text renderer mirroring the {@code ST-LINE*}
 * 80-column layout, and an HTML renderer mirroring the {@code 5100-WRITE-HTML-HEADER} /
 * {@code 5200-WRITE-HTML-NMADBS} table layout with {@code <p>}-wrapped fields) consume that holder.
 * The shared rendering skeleton lives in {@code AbstractStatementRenderer.render(...)}; each variant
 * supplies the per-section output steps, expressing the Template Method pattern with composable,
 * overridable steps.</p>
 *
 * <h2>Financial fidelity</h2>
 * <p>The current balance and the statement total are {@link BigDecimal} values rendered with the
 * exact COBOL edited-picture semantics ({@code PIC 9(9).99-} and {@code PIC Z(9).99-}); the total is
 * accumulated in {@code BigDecimal} arithmetic at scale 2 with {@link RoundingMode#HALF_EVEN}. No
 * floating-point type is used for any monetary value.</p>
 */
@Component
public class StatementProcessor implements ItemProcessor<Account, StatementProcessor.StatementResult> {

    /**
     * Canonical name of the counter incremented once per statement produced. Matches the
     * application-wide meter name pre-registered by the observability configuration, so the emitted
     * time series is the shared {@code carddemo.batch.records.processed} counter.
     */
    private static final String METRIC_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Monetary scale (number of fractional digits) for all rendered/accumulated amounts. */
    private static final int MONEY_SCALE = 2;

    /** Cross-reference repository (CBSTM03B {@code XREFFILE}); resolves the card/account/customer linkage. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Customer repository (CBSTM03B {@code CUSTFILE}); keyed read of the statement's customer. */
    private final CustomerRepository customerRepository;

    /** Transaction repository (CBSTM03B {@code TRNXFILE}); source of the transaction summary rows. */
    private final TransactionRepository transactionRepository;

    /** Account repository (CBSTM03B {@code ACCTFILE}); keyed re-read of the account for freshness. */
    private final AccountRepository accountRepository;

    /** Micrometer registry used to increment the per-statement processed counter. */
    private final MeterRegistry meterRegistry;

    /** Plain-text statement renderer (80-column {@code ST-LINE*} layout). */
    private final StatementRenderer textRenderer;

    /** HTML statement renderer (table layout with {@code <p>}-wrapped fields). */
    private final StatementRenderer htmlRenderer;

    /**
     * Creates the processor with its collaborators injected by Spring.
     *
     * @param cardCrossReferenceRepository cross-reference repository (must not be {@code null})
     * @param customerRepository           customer repository (must not be {@code null})
     * @param transactionRepository        transaction repository (must not be {@code null})
     * @param accountRepository            account repository (must not be {@code null})
     * @param meterRegistry                Micrometer registry for the processed-records metric
     *                                     (must not be {@code null})
     */
    public StatementProcessor(final CardCrossReferenceRepository cardCrossReferenceRepository,
                              final CustomerRepository customerRepository,
                              final TransactionRepository transactionRepository,
                              final AccountRepository accountRepository,
                              final MeterRegistry meterRegistry) {
        this.cardCrossReferenceRepository =
                Objects.requireNonNull(cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.meterRegistry =
                Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.textRenderer = new PlainTextStatementRenderer();
        this.htmlRenderer = new HtmlStatementRenderer();
    }

    /**
     * Produces the statement for a single account.
     *
     * <p>Control flow mirrors the COBOL mainline ({@code 1000-MAINLINE} driving
     * {@code 1000-XREFFILE-GET-NEXT} &rarr; {@code 2000-CUSTFILE-GET} &rarr; {@code 3000-ACCTFILE-GET}
     * &rarr; {@code 5000-CREATE-STATEMENT} &rarr; {@code 4000-TRNXFILE-GET}) as an explicit method
     * chain:</p>
     * <ol>
     *   <li>Resolve the account's cross-references; when none exist the account has no card linkage
     *       and {@code null} is returned so the item is filtered (the COBOL is cross-reference
     *       driven and emits nothing for an account with no cross-reference).</li>
     *   <li>Resolve the owning customer from the primary cross-reference; a dangling reference is a
     *       fatal data fault surfaced as {@link RecordNotFoundException} (mirroring the COBOL keyed
     *       read failure / abend path).</li>
     *   <li>Re-read the account for freshness, falling back to the supplied instance.</li>
     *   <li>Aggregate the transactions whose card number belongs to the account, ordered by card
     *       number then transaction id (the original {@code TRNXFILE} card+id key order).</li>
     *   <li>Assemble the shared {@link StatementData}, render both variants and increment the
     *       processed-records counter.</li>
     * </ol>
     *
     * @param account the statement subject supplied by the reader; must not be {@code null}
     * @return the rendered {@link StatementResult}, or {@code null} when the account has no card
     *         linkage and must be skipped
     * @throws RecordNotFoundException if a cross-reference points to a customer that does not exist
     */
    @Override
    public StatementResult process(final Account account) {
        Objects.requireNonNull(account, "account must not be null");
        final Long accountId = account.getAcctId();

        final List<CardCrossReference> crossReferences =
                cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (crossReferences == null || crossReferences.isEmpty()) {
            return null;
        }
        final CardCrossReference primaryXref = crossReferences.get(0);

        final Long customerId = primaryXref.getXrefCustId();
        final Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Customer", customerId));

        final Account statementAccount = accountRepository.findById(accountId).orElse(account);
        final BigDecimal currentBalance = statementAccount.getAcctCurrBal();

        final List<Transaction> matchedTransactions = aggregateTransactions(crossReferences);

        final List<TransactionSummaryLine> summaryLines = new ArrayList<>(matchedTransactions.size());
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        for (final Transaction transaction : matchedTransactions) {
            final BigDecimal amount = scaleMoney(transaction.getTranAmt());
            summaryLines.add(new TransactionSummaryLine(
                    transaction.getTranId(), transaction.getTranDesc(), amount));
            total = total.add(amount);
        }
        total = total.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);

        final String customerName = composeCustomerName(customer);
        final StatementData data = new StatementData(
                customerName,
                safe(customer.getCustAddrLine1()),
                safe(customer.getCustAddrLine2()),
                composeAddressLine3(customer),
                accountId,
                currentBalance,
                customer.getCustFicoCreditScore(),
                List.copyOf(summaryLines),
                total);

        final String textStatement = textRenderer.render(data);
        final String htmlStatement = htmlRenderer.render(data);

        meterRegistry.counter(METRIC_RECORDS_PROCESSED).increment();

        return new StatementResult(accountId, customerName, currentBalance, textStatement, htmlStatement);
    }

    /**
     * Collects the transactions belonging to the account's cards. The set of card numbers is taken
     * from the account's cross-references (preserving their order); the matching transactions are
     * fetched with a single {@code IN}-clause query that pushes the card-set filter down to
     * PostgreSQL — replacing the previous per-account {@code findAll()} full-table scan (an
     * O(accounts x transactions) N+1 pattern) — and are then ordered by card number then transaction
     * id to reproduce the original sequential {@code TRNXFILE} (card + transaction-id key) ordering.
     *
     * @param crossReferences the account's cross-reference rows (non-empty)
     * @return the matched transactions in deterministic card-then-id order
     */
    private List<Transaction> aggregateTransactions(final List<CardCrossReference> crossReferences) {
        final Set<String> cardNumbers = new LinkedHashSet<>();
        for (final CardCrossReference crossReference : crossReferences) {
            final String cardNumber = crossReference.getXrefCardNum();
            if (cardNumber != null) {
                cardNumbers.add(cardNumber);
            }
        }
        if (cardNumbers.isEmpty()) {
            return List.of();
        }
        final List<Transaction> matched =
                new ArrayList<>(transactionRepository.findByTranCardNumIn(cardNumbers));
        matched.sort(Comparator.comparing(Transaction::getTranCardNum,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Transaction::getTranId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return matched;
    }

    /**
     * Composes the customer name following the COBOL {@code STRING ... DELIMITED BY ' '} semantics
     * of {@code 5000-CREATE-STATEMENT}: the first, middle and last name each contribute the
     * characters up to their first embedded space, joined by single spaces.
     *
     * @param customer the resolved customer
     * @return the composed display name (trailing blanks removed)
     */
    private static String composeCustomerName(final Customer customer) {
        final String composed = cobolStringToken(customer.getCustFirstName()) + " "
                + cobolStringToken(customer.getCustMiddleName()) + " "
                + cobolStringToken(customer.getCustLastName());
        return composed.stripTrailing();
    }

    /**
     * Composes the third address line following the COBOL {@code STRING ... DELIMITED BY ' '}
     * semantics: address line 3, state code, country code and ZIP each contribute the characters up
     * to their first embedded space, joined by single spaces.
     *
     * @param customer the resolved customer
     * @return the composed address line 3 (trailing blanks removed)
     */
    private static String composeAddressLine3(final Customer customer) {
        final String composed = cobolStringToken(customer.getCustAddrLine3()) + " "
                + cobolStringToken(customer.getCustAddrStateCd()) + " "
                + cobolStringToken(customer.getCustAddrCountryCd()) + " "
                + cobolStringToken(customer.getCustAddrZip());
        return composed.stripTrailing();
    }

    /**
     * Reproduces a COBOL {@code STRING ... DELIMITED BY ' '} sending field: returns the leading run
     * of characters up to (but excluding) the first space, or the whole value when it contains no
     * space. A {@code null} value yields the empty string.
     *
     * @param field the source field
     * @return the characters preceding the first space, or {@code ""} for a {@code null} field
     */
    private static String cobolStringToken(final String field) {
        if (field == null) {
            return "";
        }
        final int spaceIndex = field.indexOf(' ');
        return (spaceIndex < 0) ? field : field.substring(0, spaceIndex);
    }

    /**
     * Normalizes a possibly-{@code null} string to the empty string (mirrors the COBOL behaviour of
     * a space-filled fixed-width field that is moved verbatim).
     *
     * @param value the value to normalize
     * @return {@code value}, or {@code ""} when {@code value} is {@code null}
     */
    private static String safe(final String value) {
        return (value == null) ? "" : value;
    }

    /**
     * Scales a monetary amount to the canonical two-fraction-digit scale using banker's rounding.
     *
     * @param amount the amount to scale; {@code null} is treated as zero
     * @return the amount at scale {@value #MONEY_SCALE}
     */
    private static BigDecimal scaleMoney(final BigDecimal amount) {
        final BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Renders a monetary value as COBOL {@code PIC 9(9).99-}: nine zero-padded integer digits, a
     * decimal point, two fraction digits and a trailing sign character ({@code ' '} for non-negative,
     * {@code '-'} for negative). Field width is always 13 characters.
     *
     * @param value the value to render; {@code null} is treated as zero
     * @return the 13-character edited representation
     */
    private static String formatPic9(final BigDecimal value) {
        return formatCobolNumeric(value, true);
    }

    /**
     * Renders a monetary value as COBOL {@code PIC Z(9).99-}: nine space-suppressed integer digits
     * (leading zeros rendered as blanks), a decimal point, two fraction digits and a trailing sign
     * character. Field width is always 13 characters.
     *
     * @param value the value to render; {@code null} is treated as zero
     * @return the 13-character edited representation
     */
    private static String formatPicZ(final BigDecimal value) {
        return formatCobolNumeric(value, false);
    }

    /**
     * Shared edited-picture formatter for the {@code 9(9).99-} and {@code Z(9).99-} clauses. The
     * magnitude is split into integer and fraction parts via the scaled unscaled value; the integer
     * part is either zero-padded or blank-suppressed to nine positions, and a trailing sign
     * character is appended.
     *
     * @param value      the value to render; {@code null} is treated as zero
     * @param zeroPadded {@code true} for {@code 9(9)} zero padding, {@code false} for {@code Z(9)}
     *                   blank suppression
     * @return the 13-character edited representation
     */
    private static String formatCobolNumeric(final BigDecimal value, final boolean zeroPadded) {
        final BigDecimal scaled = scaleMoney(value);
        final boolean negative = scaled.signum() < 0;
        final BigInteger unscaled = scaled.abs().unscaledValue();
        final BigInteger hundred = BigInteger.valueOf(100);
        String integerDigits = unscaled.divide(hundred).toString();
        final int fraction = unscaled.remainder(hundred).intValue();

        if (integerDigits.length() > 9) {
            integerDigits = integerDigits.substring(integerDigits.length() - 9);
        }
        final String paddedInteger;
        if (zeroPadded) {
            paddedInteger = "0".repeat(9 - integerDigits.length()) + integerDigits;
        } else {
            final String suppressed = "0".equals(integerDigits) ? "" : integerDigits;
            paddedInteger = " ".repeat(9 - suppressed.length()) + suppressed;
        }
        final String fractionDigits = (fraction < 10) ? "0" + fraction : Integer.toString(fraction);
        return paddedInteger + "." + fractionDigits + (negative ? '-' : ' ');
    }

    /**
     * Renders the FICO credit score as the left-justified numeric text the COBOL moves into the
     * {@code ST-FICO-SCORE} alphanumeric field.
     *
     * @param fico the FICO score; {@code null} yields the empty string
     * @return the score as text, or {@code ""} when {@code null}
     */
    private static String formatFico(final Integer fico) {
        return (fico == null) ? "" : Integer.toString(fico);
    }

    /**
     * Truncates or right-pads (with spaces) the supplied value to exactly {@code width} characters,
     * reproducing a COBOL alphanumeric {@code MOVE} into a fixed-width {@code PIC X(width)} field.
     *
     * @param value the value to fit; {@code null} is treated as the empty string
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String fixedField(final String value, final int width) {
        final String safeValue = safe(value);
        if (safeValue.length() >= width) {
            return safeValue.substring(0, width);
        }
        return safeValue + " ".repeat(width - safeValue.length());
    }

    /**
     * Appends a single rendered line followed by a newline to the supplied buffer.
     *
     * @param builder the buffer being assembled
     * @param content the line content
     */
    private static void appendLine(final StringBuilder builder, final String content) {
        builder.append(content).append('\n');
    }

    /**
     * Immutable typed contract returned to the downstream statement writer: the account id, the
     * composed customer name, the current balance, and the fully rendered plain-text and HTML
     * statement variants.
     *
     * @param accountId      the statement account id
     * @param customerName   the composed customer name
     * @param currentBalance the account current balance
     * @param textStatement  the rendered plain-text statement (80-column layout)
     * @param htmlStatement  the rendered HTML statement
     */
    public record StatementResult(Long accountId,
                                  String customerName,
                                  BigDecimal currentBalance,
                                  String textStatement,
                                  String htmlStatement) {
    }

    /**
     * Immutable holder of the resolved statement inputs shared by both renderers (the Template
     * Method's data object).
     *
     * @param customerName   the composed customer name
     * @param addressLine1   the first address line
     * @param addressLine2   the second address line
     * @param addressLine3   the composed third address line (line 3 + state + country + ZIP)
     * @param accountId      the statement account id
     * @param currentBalance the account current balance
     * @param ficoScore      the customer FICO credit score (may be {@code null})
     * @param transactions   the ordered transaction summary lines
     * @param total          the summed transaction amount (scale {@value #MONEY_SCALE})
     */
    public record StatementData(String customerName,
                                String addressLine1,
                                String addressLine2,
                                String addressLine3,
                                Long accountId,
                                BigDecimal currentBalance,
                                Integer ficoScore,
                                List<TransactionSummaryLine> transactions,
                                BigDecimal total) {
    }

    /**
     * Immutable transaction summary line (one row of the statement's Transaction Summary section).
     *
     * @param tranId   the transaction id
     * @param tranDesc the transaction description
     * @param tranAmt  the transaction amount (scale {@value #MONEY_SCALE})
     */
    public record TransactionSummaryLine(String tranId, String tranDesc, BigDecimal tranAmt) {
    }

    /**
     * Strategy abstraction for a statement output variant. Each implementation renders a complete
     * statement document from the shared {@link StatementData}.
     */
    private interface StatementRenderer {

        /**
         * Renders the complete statement document for the supplied data.
         *
         * @param data the resolved statement inputs
         * @return the rendered document
         */
        String render(StatementData data);
    }

    /**
     * Template Method base for the statement renderers. {@link #render(StatementData)} fixes the
     * section ordering (start &rarr; name &rarr; address &rarr; basic details &rarr; transaction
     * header &rarr; transaction rows &rarr; total &rarr; end); subclasses supply the per-section
     * output steps. {@link #appendTotal(StringBuilder, StatementData)} is an optional hook with a
     * default empty implementation (the HTML variant emits no total row).
     */
    private abstract static class AbstractStatementRenderer implements StatementRenderer {

        @Override
        public final String render(final StatementData data) {
            final StringBuilder builder = new StringBuilder(1024);
            appendDocumentStart(builder, data);
            appendName(builder, data);
            appendAddress(builder, data);
            appendBasicDetails(builder, data);
            appendTransactionHeader(builder, data);
            for (final TransactionSummaryLine line : data.transactions()) {
                appendTransactionLine(builder, line);
            }
            appendTotal(builder, data);
            appendDocumentEnd(builder, data);
            return builder.toString();
        }

        /**
         * Emits the document opening (banner / HTML head + heading).
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendDocumentStart(StringBuilder builder, StatementData data);

        /**
         * Emits the customer name.
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendName(StringBuilder builder, StatementData data);

        /**
         * Emits the three address lines (and any surrounding section framing).
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendAddress(StringBuilder builder, StatementData data);

        /**
         * Emits the basic details (account id, current balance, FICO score).
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendBasicDetails(StringBuilder builder, StatementData data);

        /**
         * Emits the transaction summary section header.
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendTransactionHeader(StringBuilder builder, StatementData data);

        /**
         * Emits a single transaction summary line.
         *
         * @param builder the buffer being assembled
         * @param line    the transaction summary line
         */
        protected abstract void appendTransactionLine(StringBuilder builder, TransactionSummaryLine line);

        /**
         * Optional hook that emits the total line. The default implementation emits nothing; the
         * plain-text variant overrides it (the COBOL writes the {@code Total EXP} line only to the
         * text statement, never to the HTML statement).
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected void appendTotal(final StringBuilder builder, final StatementData data) {
            // Optional Template Method hook: by default no total is emitted.
        }

        /**
         * Emits the document closing (banner / HTML footer + closing tags).
         *
         * @param builder the buffer being assembled
         * @param data    the statement data
         */
        protected abstract void appendDocumentEnd(StringBuilder builder, StatementData data);
    }

    /**
     * Plain-text renderer reproducing the 80-column {@code ST-LINE0}..{@code ST-LINE15} layout of
     * {@code CBSTM03A.CBL}. Every emitted line is exactly 80 characters wide.
     */
    private static final class PlainTextStatementRenderer extends AbstractStatementRenderer {

        /** Fixed statement line width (COBOL {@code FD-STMTFILE-REC PIC X(80)}). */
        private static final int LINE_WIDTH = 80;

        /** A full-width rule line ({@code ST-LINE5}/{@code 10}/{@code 12}). */
        private static final String RULE = "-".repeat(LINE_WIDTH);

        @Override
        protected void appendDocumentStart(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31));
        }

        @Override
        protected void appendName(final StringBuilder builder, final StatementData data) {
            appendLine(builder, fixedField(data.customerName(), LINE_WIDTH));
        }

        @Override
        protected void appendAddress(final StringBuilder builder, final StatementData data) {
            appendLine(builder, fixedField(data.addressLine1(), LINE_WIDTH));
            appendLine(builder, fixedField(data.addressLine2(), LINE_WIDTH));
            appendLine(builder, fixedField(data.addressLine3(), LINE_WIDTH));
            appendLine(builder, RULE);
            appendLine(builder, fixedField("", 33) + fixedField("Basic Details", 14) + fixedField("", 33));
            appendLine(builder, RULE);
        }

        @Override
        protected void appendBasicDetails(final StringBuilder builder, final StatementData data) {
            appendLine(builder, fixedField("Account ID", 19) + ":"
                    + fixedField(Objects.toString(data.accountId(), ""), 20) + fixedField("", 40));
            appendLine(builder, fixedField("Current Balance", 19) + ":"
                    + formatPic9(data.currentBalance()) + fixedField("", 47));
            appendLine(builder, fixedField("FICO Score", 19) + ":"
                    + fixedField(formatFico(data.ficoScore()), 20) + fixedField("", 40));
            appendLine(builder, RULE);
        }

        @Override
        protected void appendTransactionHeader(final StringBuilder builder, final StatementData data) {
            appendLine(builder, fixedField("", 30) + "TRANSACTION SUMMARY " + fixedField("", 30));
            appendLine(builder, RULE);
            appendLine(builder, fixedField("Tran ID", 16) + fixedField("Tran Details", 51) + "  Tran Amount");
            appendLine(builder, RULE);
        }

        @Override
        protected void appendTransactionLine(final StringBuilder builder, final TransactionSummaryLine line) {
            appendLine(builder, fixedField(line.tranId(), 16) + " " + fixedField(line.tranDesc(), 49)
                    + "$" + formatPicZ(line.tranAmt()));
        }

        @Override
        protected void appendTotal(final StringBuilder builder, final StatementData data) {
            appendLine(builder, RULE);
            appendLine(builder, "Total EXP:" + fixedField("", 56) + "$" + formatPicZ(data.total()));
        }

        @Override
        protected void appendDocumentEnd(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
        }
    }

    /**
     * HTML renderer reproducing the table layout of {@code 5100-WRITE-HTML-HEADER} /
     * {@code 5200-WRITE-HTML-NMADBS} / {@code 6000-WRITE-TRANS} in {@code CBSTM03A.CBL}, with the
     * dynamic fields wrapped in {@code <p>} elements. It inherits the no-op total hook (the COBOL
     * HTML output carries no total row).
     */
    private static final class HtmlStatementRenderer extends AbstractStatementRenderer {

        /** Background style for the dark heading/footer band rows ({@code HTML-L10}). */
        private static final String TD_HEADING = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

        /** Background style for the issuer band row ({@code HTML-L15}). */
        private static final String TD_ISSUER = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

        /** Background style for the neutral content band rows ({@code HTML-L22-35}). */
        private static final String TD_CONTENT = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

        /** Background style for the centred section-title band rows ({@code HTML-L30-42}). */
        private static final String TD_SECTION = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

        /** Column-header cell for the Tran ID column ({@code HTML-L47}). */
        private static final String TD_HDR_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

        /** Column-header cell for the Tran Details column ({@code HTML-L50}). */
        private static final String TD_HDR_DESC = "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

        /** Column-header cell for the Amount column ({@code HTML-L53}). */
        private static final String TD_HDR_AMT = "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";

        /** Data cell for the Tran ID column ({@code HTML-L58}). */
        private static final String TD_DATA_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

        /** Data cell for the Tran Details column ({@code HTML-L61}). */
        private static final String TD_DATA_DESC = "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

        /** Data cell for the Amount column ({@code HTML-L64}). */
        private static final String TD_DATA_AMT = "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

        @Override
        protected void appendDocumentStart(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<!DOCTYPE html>");
            appendLine(builder, "<html lang=\"en\">");
            appendLine(builder, "<head>");
            appendLine(builder, "<meta charset=\"utf-8\">");
            appendLine(builder, "<title>HTML Table Layout</title>");
            appendLine(builder, "</head>");
            appendLine(builder, "<body style=\"margin:0px;\">");
            appendLine(builder, "<table align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">");
            appendLine(builder, "<tr>");
            appendLine(builder, TD_HEADING);
            appendLine(builder, "<h3>Statement for Account Number: " + Objects.toString(data.accountId(), "") + "</h3>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
            appendLine(builder, "<tr>");
            appendLine(builder, TD_ISSUER);
            appendLine(builder, "<p style=\"font-size:16px\">Bank of XYZ</p>");
            appendLine(builder, "<p>410 Terry Ave N</p>");
            appendLine(builder, "<p>Seattle WA 99999</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
        }

        @Override
        protected void appendName(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<tr>");
            appendLine(builder, TD_CONTENT);
            appendLine(builder, "<p style=\"font-size:16px\">" + data.customerName() + "</p>");
        }

        @Override
        protected void appendAddress(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<p>" + data.addressLine1() + "</p>");
            appendLine(builder, "<p>" + data.addressLine2() + "</p>");
            appendLine(builder, "<p>" + data.addressLine3() + "</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
            appendLine(builder, "<tr>");
            appendLine(builder, TD_SECTION);
            appendLine(builder, "<p style=\"font-size:16px\">Basic Details</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
        }

        @Override
        protected void appendBasicDetails(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<tr>");
            appendLine(builder, TD_CONTENT);
            appendLine(builder, "<p>" + fixedField("Account ID", 19) + ": "
                    + Objects.toString(data.accountId(), "") + "</p>");
            appendLine(builder, "<p>" + fixedField("Current Balance", 19) + ": "
                    + formatPic9(data.currentBalance()) + "</p>");
            appendLine(builder, "<p>" + fixedField("FICO Score", 19) + ": "
                    + formatFico(data.ficoScore()) + "</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
        }

        @Override
        protected void appendTransactionHeader(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<tr>");
            appendLine(builder, TD_SECTION);
            appendLine(builder, "<p style=\"font-size:16px\">Transaction Summary</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
            appendLine(builder, "<tr>");
            appendLine(builder, TD_HDR_ID);
            appendLine(builder, "<p style=\"font-size:16px\">Tran ID</p>");
            appendLine(builder, "</td>");
            appendLine(builder, TD_HDR_DESC);
            appendLine(builder, "<p style=\"font-size:16px\">Tran Details</p>");
            appendLine(builder, "</td>");
            appendLine(builder, TD_HDR_AMT);
            appendLine(builder, "<p style=\"font-size:16px\">Amount</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
        }

        @Override
        protected void appendTransactionLine(final StringBuilder builder, final TransactionSummaryLine line) {
            appendLine(builder, "<tr>");
            appendLine(builder, TD_DATA_ID);
            appendLine(builder, "<p>" + safe(line.tranId()) + "</p>");
            appendLine(builder, "</td>");
            appendLine(builder, TD_DATA_DESC);
            appendLine(builder, "<p>" + safe(line.tranDesc()) + "</p>");
            appendLine(builder, "</td>");
            appendLine(builder, TD_DATA_AMT);
            appendLine(builder, "<p>" + formatPicZ(line.tranAmt()) + "</p>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
        }

        @Override
        protected void appendDocumentEnd(final StringBuilder builder, final StatementData data) {
            appendLine(builder, "<tr>");
            appendLine(builder, TD_HEADING);
            appendLine(builder, "<h3>End of Statement</h3>");
            appendLine(builder, "</td>");
            appendLine(builder, "</tr>");
            appendLine(builder, "</table>");
            appendLine(builder, "</body>");
            appendLine(builder, "</html>");
        }
    }
}
