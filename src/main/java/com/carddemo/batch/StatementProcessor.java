package com.carddemo.batch;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.service.StatementFileService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Chunk-step {@link ItemProcessor} for the CardDemo statement-generation stage &mdash; processor
 * <strong>#4 of 5</strong> in the batch pipeline, wired into {@code StatementJob} (JCL
 * {@code CREASTMT}).
 *
 * <p><strong>COBOL lineage (REFERENCE-only, source commit SHA {@code 27d6c6f}).</strong> This
 * processor is the Java translation of the per-card statement assembly performed by the legacy
 * batch program {@code app/cbl/CBSTM03A.CBL}. For every card cross-reference row produced by the
 * driving reader, {@code CBSTM03A} runs {@code 2000-CUSTFILE-GET} / {@code 3000-ACCTFILE-GET} to
 * join the owning customer and account, then {@code 5000-CREATE-STATEMENT} (with
 * {@code 6000-WRITE-TRANS} per transaction) to assemble one statement in <em>two</em> parallel
 * output formats &mdash; the plain-text {@code STMT-FILE} ({@code FD-STMTFILE-REC PIC X(80)}) and
 * the {@code HTML-FILE} ({@code FD-HTMLFILE-REC PIC X(100)}). This processor reproduces
 * {@code 5000-CREATE-STATEMENT} exactly: it maps one {@link CardXref} to one assembled
 * {@link StatementDocument} that carries both renderings.</p>
 *
 * <h2>CALL &rarr; bean injection (AAP &sect;0.4.3)</h2>
 * <p>In the mainframe program the file reads are delegated to the subprogram {@code CBSTM03B} via
 * {@code CALL 'CBSTM03B' USING WS-M03B-AREA} (three call sites in {@code CBSTM03A} &mdash; customer,
 * account and transaction reads). Per the AAP's canonical CALL&rarr;bean mandate, that static COBOL
 * linkage is replaced by a <strong>constructor-injected {@link StatementFileService}</strong> bean;
 * this processor performs <em>no</em> direct repository access and reaches every record exclusively
 * through that collaborator. This is the reference example for the CALL&rarr;bean transformation
 * recorded in {@code docs/traceability-matrix.md}.</p>
 *
 * <h2>Rendering happens here, not in the writer</h2>
 * <p>Both statement formats are rendered in this processor so that the downstream
 * {@code StatementItemWriter} stays purely I/O: the writer only pads/truncates each pre-rendered
 * line to its fixed record length ({@value #TEXT_RECORD_LENGTH} for text,
 * {@value #HTML_RECORD_LENGTH} for HTML) and ships the two aggregate files to versioned S3 objects.
 * The text statement is delimited by a {@code START OF STATEMENT} header banner and an
 * {@code END OF STATEMENT} trailer banner and reproduces the {@code COSTM01} / {@code STATEMENT-LINES}
 * field layout and labels; the HTML statement reproduces the {@code HTML-LINES}
 * {@code <!DOCTYPE html>}&hellip;{@code </html>} table structure.</p>
 *
 * <h2>Missing-parent semantics (return {@code null} to filter)</h2>
 * <p>A cross-reference row whose account or customer cannot be resolved yields <em>no</em> statement:
 * {@link #process(CardXref)} returns {@code null}, which is the idiomatic Spring Batch signal to
 * filter the item out of the chunk. This is the migration analogue of {@code CBSTM03A} not emitting a
 * statement for an unresolved card (the legacy program abends on a missing parent; the refined
 * batch-friendly behaviour of skipping the card is recorded in {@code docs/decision-log.md}). The
 * card master record is looked up for completeness but is <em>not</em> required: {@code CBSTM03A}
 * never reads a card file and the statement header carries no card-specific field, so a missing card
 * never suppresses a statement. Consistent with AAP &sect;0.8.3, {@code null} is only ever a
 * <em>filter</em> signal &mdash; it is never used to mask an error; infrastructure faults raised by
 * the injected service propagate unchanged.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.8.2)</h2>
 * <p>Every monetary value rendered onto a statement originates from
 * {@link StatementTransactionDto#amount()} as a scale-{@value #AMOUNT_SCALE} {@link BigDecimal}, and
 * the running total is accumulated with {@link BigDecimal} arithmetic. All numeric editing
 * (reproducing the COBOL {@code PIC 9(9).99-} balance edit and {@code PIC Z(9).99-} amount edit) is
 * performed on {@link BigDecimal}/{@link BigInteger} and formatted directly to {@link String};
 * {@code float}/{@code double} are never used for money anywhere in this class.</p>
 *
 * <h2>Thread-safety and scope</h2>
 * <p>The bean is a stateless singleton {@link Component}: it holds only its final
 * {@link StatementFileService} reference and computes each statement from method-local state, so a
 * single instance is safe to share across concurrent batch worker threads. It is deliberately not
 * {@code @StepScope} &mdash; unlike the reader and writer it needs no per-step state or job
 * parameters.</p>
 *
 * @see StatementDocument
 * @see StatementFileService
 * @see StatementItemWriter
 */
@Component
public class StatementProcessor implements ItemProcessor<CardXref, StatementDocument> {

    /**
     * Fixed record length of the plain-text statement file (COBOL {@code FD-STMTFILE-REC PIC X(80)},
     * {@code CREASTMT.JCL} {@code STMTFILE DCB=(LRECL=80)}). Each rendered text line is bounded to
     * this width; the writer pads/truncates to the same length.
     */
    static final int TEXT_RECORD_LENGTH = 80;

    /**
     * Fixed record length of the HTML statement file (COBOL {@code FD-HTMLFILE-REC PIC X(100)},
     * {@code CREASTMT.JCL} {@code HTMLFILE DCB=(LRECL=100)}). Each rendered HTML line is bounded to
     * this width, matching the COBOL record that truncates any longer output.
     */
    static final int HTML_RECORD_LENGTH = 100;

    /** Decimal scale of every monetary value, matching the {@code V99} fraction of the COBOL picture. */
    private static final int AMOUNT_SCALE = 2;

    /** Integer-digit count of the COBOL zoned money edits {@code PIC 9(9).99-} / {@code PIC Z(9).99-}. */
    private static final int ZONED_INTEGER_DIGITS = 9;

    /** Display width of {@code ACCT-ID PIC 9(11)} when moved to a statement field. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** Display width of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} when moved to a statement field. */
    private static final int FICO_SCORE_DIGITS = 3;

    /** Width of the {@code ST-LINE7/8/9} label fields (COBOL {@code PIC X(20)}). */
    private static final int LABEL_WIDTH = 20;

    /** Width of the value fields {@code ST-ACCT-ID} / {@code ST-FICO-SCORE} (COBOL {@code PIC X(20)}). */
    private static final int VALUE_WIDTH = 20;

    /** Width of {@code ST-NAME} (COBOL {@code PIC X(75)}). */
    private static final int NAME_WIDTH = 75;

    /** Width of {@code ST-ADD1} / {@code ST-ADD2} (COBOL {@code PIC X(50)}). */
    private static final int ADDRESS_WIDTH = 50;

    /** Width of {@code ST-ADD3} (COBOL {@code PIC X(80)}). */
    private static final int CITY_LINE_WIDTH = 80;

    /** Width of {@code ST-TRANID} (COBOL {@code PIC X(16)}). */
    private static final int TRAN_ID_WIDTH = 16;

    /** Width of {@code ST-TRANDT} (COBOL {@code PIC X(49)}). */
    private static final int TRAN_DESC_WIDTH = 49;

    /** Width of {@code L23-NAME}, the HTML name projection (COBOL {@code PIC X(50)}). */
    private static final int HTML_NAME_WIDTH = 50;

    /** The {@code ST-LINE7} label, built as {@code "Account ID"} padded to 19 plus a trailing colon. */
    private static final String LABEL_ACCOUNT_ID = fixed("Account ID", LABEL_WIDTH - 1) + ":";

    /** The {@code ST-LINE8} label, {@code "Current Balance"} padded to 19 plus a trailing colon. */
    private static final String LABEL_CURRENT_BALANCE = fixed("Current Balance", LABEL_WIDTH - 1) + ":";

    /** The {@code ST-LINE9} label, {@code "FICO Score"} padded to 19 plus a trailing colon. */
    private static final String LABEL_FICO_SCORE = fixed("FICO Score", LABEL_WIDTH - 1) + ":";

    /** SLF4J logger; structured JSON with the MDC {@code correlationId} is applied by logback. */
    private static final Logger log = LoggerFactory.getLogger(StatementProcessor.class);

    /**
     * File-access collaborator replacing the legacy {@code CALL 'CBSTM03B'} linkage (AAP &sect;0.4.3);
     * the sole source of customer, account, card and transaction data for this processor.
     */
    private final StatementFileService statementFileService;

    /**
     * Creates the processor with its single, constructor-injected collaborator.
     *
     * <p>Constructor injection keeps the {@link StatementFileService} reference {@code final} and
     * mandatory, directly realizing the AAP &sect;0.4.3 mandate that {@code CBSTM03A}'s
     * {@code CALL 'CBSTM03B'} become a Spring bean dependency.</p>
     *
     * @param statementFileService the statement file-access service (the {@code CBSTM03B} replacement);
     *                             must not be {@code null}
     */
    public StatementProcessor(StatementFileService statementFileService) {
        this.statementFileService = Objects.requireNonNull(
                statementFileService, "statementFileService must not be null");
    }

    /**
     * Assembles one statement from a single card cross-reference row, reproducing
     * {@code CBSTM03A 5000-CREATE-STATEMENT}.
     *
     * <p>Processing steps, in order:</p>
     * <ol>
     *   <li>Resolve the driving keys from the cross-reference row: card number
     *       ({@code XREF-CARD-NUM}), account id ({@code XREF-ACCT-ID}) and customer id
     *       ({@code XREF-CUST-ID}).</li>
     *   <li>If the account or customer key is absent, emit no statement (return {@code null}); this
     *       also guards the repository lookup, whose {@code findById} rejects a {@code null} key.</li>
     *   <li>Via the injected {@link StatementFileService} (the {@code CBSTM03B} replacement) fetch the
     *       {@code Account} ({@code 3000-ACCTFILE-GET}) and {@code Customer}
     *       ({@code 2000-CUSTFILE-GET}); if either is missing, emit no statement (return
     *       {@code null}).</li>
     *   <li>Fetch the {@code Card} for the header (optional &mdash; a missing card does not suppress
     *       the statement) and build the per-transaction statement lines
     *       ({@link StatementFileService#buildStatementLines(String)}).</li>
     *   <li>Render both the plain-text and HTML statements and return a {@link StatementDocument}
     *       carrying the driving row, the joined entities, the structured lines and both
     *       renderings.</li>
     * </ol>
     *
     * @param xref the driving card cross-reference row; guaranteed non-{@code null} by the Spring
     *             Batch chunk contract
     * @return the fully-assembled {@link StatementDocument}, or {@code null} to filter this card out
     *         of the run (unresolved account/customer key, or a missing account or customer record)
     */
    @Override
    public StatementDocument process(CardXref xref) {
        final String cardNumber = xref.getXrefCardNum();
        final Long accountId = xref.getXrefAcctId();
        final Long customerId = xref.getXrefCustId();

        // An XREF row that cannot resolve its account or customer key produces no statement. Guard
        // before the repository call, whose findById rejects a null identifier.
        if (accountId == null || customerId == null) {
            log.debug("Skipping statement for card {}: unresolved key (acctId={}, custId={})",
                    cardNumber, accountId, customerId);
            return null;
        }

        // CALL 'CBSTM03B' USING WS-M03B-AREA -> injected StatementFileService (AAP 0.4.3).
        final Account account = statementFileService.getAccount(accountId).orElse(null);
        final Customer customer = statementFileService.getCustomer(customerId).orElse(null);
        if (account == null || customer == null) {
            log.debug("Skipping statement for card {}: missing parent (accountPresent={}, "
                            + "customerPresent={})", cardNumber, account != null, customer != null);
            return null;
        }

        // The card master is looked up for completeness only; CBSTM03A reads no card file and the
        // statement header uses no card field, so a missing card never suppresses a statement.
        final Card card = statementFileService.getCard(cardNumber).orElse(null);

        // 4000-TRNXFILE-GET: the per-card transaction lines assembled through CBSTM03B.
        List<StatementTransactionDto> lines = statementFileService.buildStatementLines(cardNumber);
        if (lines == null) {
            lines = List.of();
        }

        final List<String> textLines = renderTextStatement(account, customer, lines);
        final List<String> htmlLines = renderHtmlStatement(account, customer, lines);

        log.debug("Assembled statement for card {} with {} transaction line(s)",
                cardNumber, lines.size());
        return new StatementDocument(xref, account, customer, card, lines, textLines, htmlLines);
    }

    /**
     * Renders the plain-text statement, reproducing the {@code STATEMENT-LINES} layout written by
     * {@code CBSTM03A} ({@code 5000-CREATE-STATEMENT}, {@code 6000-WRITE-TRANS} and the
     * {@code 4000-TRNXFILE-GET} trailer).
     *
     * <p>The line sequence and fixed-width fields mirror the COBOL {@code ST-LINE0} &hellip;
     * {@code ST-LINE15} records: a {@code START OF STATEMENT} banner, the customer name and address
     * block, the {@code Basic Details} block (account id, current balance, FICO score), a
     * {@code TRANSACTION SUMMARY} column header, one detail line per transaction, the running expense
     * total, and an {@code END OF STATEMENT} banner. Every line is built to exactly
     * {@value #TEXT_RECORD_LENGTH} characters and defensively bounded to that width.</p>
     *
     * @param account  the joined account ({@code ACCT-ID}, {@code ACCT-CURR-BAL}); never {@code null}
     * @param customer the joined customer (name, address, FICO score); never {@code null}
     * @param lines    the per-transaction statement lines in source order; never {@code null}
     * @return the ordered, immutable-safe list of rendered text lines (never {@code null})
     */
    private List<String> renderTextStatement(
            Account account, Customer customer, List<StatementTransactionDto> lines) {
        final List<String> text = new ArrayList<>();
        final String rule = "-".repeat(TEXT_RECORD_LENGTH);

        // ST-LINE0 — START OF STATEMENT banner (31 '*' + text + 31 '*').
        text.add(bound("*".repeat(31) + "START OF STATEMENT" + "*".repeat(31), TEXT_RECORD_LENGTH));

        // ST-LINE1 — customer name (STRING first/middle/last DELIMITED BY ' ').
        text.add(bound(fixed(buildCustomerName(customer), NAME_WIDTH) + " ".repeat(5),
                TEXT_RECORD_LENGTH));

        // ST-LINE2 / ST-LINE3 — address lines 1 and 2 (direct MOVE, space-padded).
        text.add(bound(fixed(customer.getCustAddrLine1(), ADDRESS_WIDTH) + " ".repeat(30),
                TEXT_RECORD_LENGTH));
        text.add(bound(fixed(customer.getCustAddrLine2(), ADDRESS_WIDTH) + " ".repeat(30),
                TEXT_RECORD_LENGTH));

        // ST-LINE4 — city/state/country/zip (STRING DELIMITED BY ' ').
        text.add(bound(fixed(buildCityStateZip(customer), CITY_LINE_WIDTH), TEXT_RECORD_LENGTH));

        // ST-LINE5 / ST-LINE6 / ST-LINE5 — Basic Details banner.
        text.add(rule);
        text.add(bound(" ".repeat(33) + fixed("Basic Details", 14) + " ".repeat(33),
                TEXT_RECORD_LENGTH));
        text.add(rule);

        // ST-LINE7 / ST-LINE8 / ST-LINE9 — account id, current balance, FICO score.
        text.add(bound(LABEL_ACCOUNT_ID
                + fixed(digits(account.getAcctId(), ACCOUNT_ID_DIGITS), VALUE_WIDTH)
                + " ".repeat(40), TEXT_RECORD_LENGTH));
        text.add(bound(LABEL_CURRENT_BALANCE
                + formatSignedZoned(account.getAcctCurrBal())
                + " ".repeat(47), TEXT_RECORD_LENGTH));
        text.add(bound(LABEL_FICO_SCORE
                + fixed(digits(toLong(customer.getCustFicoCreditScore()), FICO_SCORE_DIGITS), VALUE_WIDTH)
                + " ".repeat(40), TEXT_RECORD_LENGTH));

        // ST-LINE10 / ST-LINE11 / ST-LINE12 — Transaction Summary banner and column header.
        text.add(rule);
        text.add(bound(" ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30), TEXT_RECORD_LENGTH));
        text.add(rule);
        text.add(bound(fixed("Tran ID", TRAN_ID_WIDTH) + fixed("Tran Details", 51) + "  Tran Amount",
                TEXT_RECORD_LENGTH));
        text.add(rule);

        // ST-LINE14 — one detail line per transaction (6000-WRITE-TRANS), accumulating the total.
        BigDecimal total = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        for (StatementTransactionDto line : lines) {
            total = total.add(scaled(line.amount()));
            text.add(bound(fixed(line.transactionId(), TRAN_ID_WIDTH)
                    + " "
                    + fixed(line.description(), TRAN_DESC_WIDTH)
                    + "$"
                    + formatSuppressedZoned(line.amount()), TEXT_RECORD_LENGTH));
        }

        // ST-LINE12 / ST-LINE14A / ST-LINE15 — total expense and END OF STATEMENT banner.
        text.add(rule);
        text.add(bound("Total EXP:" + " ".repeat(56) + "$" + formatSuppressedZoned(total),
                TEXT_RECORD_LENGTH));
        text.add(bound("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32), TEXT_RECORD_LENGTH));

        return text;
    }

    /**
     * Renders the HTML statement, reproducing the {@code HTML-LINES} table written by
     * {@code CBSTM03A} ({@code 5100-WRITE-HTML-HEADER}, {@code 5200-WRITE-HTML-NMADBS},
     * {@code 6000-WRITE-TRANS} and the {@code 4000-TRNXFILE-GET} trailer).
     *
     * <p>The emitted document opens with the fixed {@code <!DOCTYPE html>}&hellip;{@code <table>}
     * header, a bank-details banner, the customer name/address block, the {@code Basic Details}
     * block, the {@code Transaction Summary} column header, one {@code <tr>} per transaction, an
     * {@code End of Statement} banner and the closing tags. Every line is defensively bounded to
     * {@value #HTML_RECORD_LENGTH} characters, matching the COBOL {@code FD-HTMLFILE-REC PIC X(100)}
     * record that truncates any longer output. The HTML statement carries no expense total (matching
     * {@code CBSTM03A}).</p>
     *
     * @param account  the joined account ({@code ACCT-ID}, {@code ACCT-CURR-BAL}); never {@code null}
     * @param customer the joined customer (name, address, FICO score); never {@code null}
     * @param lines    the per-transaction statement lines in source order; never {@code null}
     * @return the ordered, immutable-safe list of rendered HTML lines (never {@code null})
     */
    private List<String> renderHtmlStatement(
            Account account, Customer customer, List<StatementTransactionDto> lines) {
        final List<String> html = new ArrayList<>();

        // 5100-WRITE-HTML-HEADER — document head, opening table and account-number banner.
        html.add("<!DOCTYPE html>");
        html.add("<html lang=\"en\">");
        html.add("<head>");
        html.add("<meta charset=\"utf-8\">");
        html.add("<title>HTML Table Layout</title>");
        html.add("</head>");
        html.add("<body style=\"margin:0px;\">");
        html.add("<table  align=\"center\" frame=\"box\" "
                + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">");
        html.add("<tr>");
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");
        html.add(bound("<h3>Statement for Account Number: "
                + fixed(digits(account.getAcctId(), ACCOUNT_ID_DIGITS), VALUE_WIDTH)
                + "</h3>", HTML_RECORD_LENGTH));
        html.add("</td>");
        html.add("</tr>");
        html.add("<tr>");
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">");
        html.add("<p style=\"font-size:16px\">Bank of XYZ</p>");
        html.add("<p>410 Terry Ave N</p>");
        html.add("<p>Seattle WA 99999</p>");
        html.add("</td>");
        html.add("</tr>");
        html.add("<tr>");
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");

        // 5200-WRITE-HTML-NMADBS — customer name and address block.
        html.add(bound("<p style=\"font-size:16px\">"
                + truncate(buildCustomerName(customer), HTML_NAME_WIDTH) + "</p>", HTML_RECORD_LENGTH));
        html.add(bound("<p>" + stripField(customer.getCustAddrLine1()) + "</p>", HTML_RECORD_LENGTH));
        html.add(bound("<p>" + stripField(customer.getCustAddrLine2()) + "</p>", HTML_RECORD_LENGTH));
        html.add(bound("<p>" + stripField(buildCityStateZip(customer)) + "</p>", HTML_RECORD_LENGTH));
        html.add("</td>");
        html.add("</tr>");

        // 5200 (continued) — Basic Details header and body.
        html.add("<tr>");
        html.add("<td colspan=\"3\" "
                + "style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">");
        html.add("<p style=\"font-size:16px\">Basic Details</p>");
        html.add("</td>");
        html.add("</tr>");
        html.add("<tr>");
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");
        html.add(bound("<p>" + LABEL_ACCOUNT_ID + " "
                + fixed(digits(account.getAcctId(), ACCOUNT_ID_DIGITS), VALUE_WIDTH)
                + "</p>", HTML_RECORD_LENGTH));
        html.add(bound("<p>" + LABEL_CURRENT_BALANCE + " "
                + formatSignedZoned(account.getAcctCurrBal()) + "</p>", HTML_RECORD_LENGTH));
        html.add(bound("<p>" + LABEL_FICO_SCORE + " "
                + fixed(digits(toLong(customer.getCustFicoCreditScore()), FICO_SCORE_DIGITS), VALUE_WIDTH)
                + "</p>", HTML_RECORD_LENGTH));
        html.add("</td>");
        html.add("</tr>");

        // 5200 (continued) — Transaction Summary header and column titles.
        html.add("<tr>");
        html.add("<td colspan=\"3\" "
                + "style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">");
        html.add("<p style=\"font-size:16px\">Transaction Summary</p>");
        html.add("</td>");
        html.add("</tr>");
        html.add("<tr>");
        html.add("<td style=\"width:25%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:left;\">");
        html.add("<p style=\"font-size:16px\">Tran ID</p>");
        html.add("</td>");
        html.add("<td style=\"width:55%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:left;\">");
        html.add("<p style=\"font-size:16px\">Tran Details</p>");
        html.add("</td>");
        html.add("<td style=\"width:20%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:right;\">");
        html.add("<p style=\"font-size:16px\">Amount</p>");
        html.add("</td>");
        html.add("</tr>");

        // 6000-WRITE-TRANS — one HTML row per transaction.
        for (StatementTransactionDto line : lines) {
            html.add("<tr>");
            html.add("<td style=\"width:25%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">");
            html.add(bound("<p>" + fixed(line.transactionId(), TRAN_ID_WIDTH) + "</p>",
                    HTML_RECORD_LENGTH));
            html.add("</td>");
            html.add("<td style=\"width:55%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">");
            html.add(bound("<p>" + stripField(truncate(line.description(), TRAN_DESC_WIDTH)) + "</p>",
                    HTML_RECORD_LENGTH));
            html.add("</td>");
            html.add("<td style=\"width:20%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:right;\">");
            html.add(bound("<p>" + formatSuppressedZoned(line.amount()) + "</p>", HTML_RECORD_LENGTH));
            html.add("</td>");
            html.add("</tr>");
        }

        // 4000-TRNXFILE-GET (HTML trailer) — End of Statement banner and closing tags.
        html.add("<tr>");
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");
        html.add("<h3>End of Statement</h3>");
        html.add("</td>");
        html.add("</tr>");
        html.add("</table>");
        html.add("</body>");
        html.add("</html>");

        return html;
    }

    /**
     * Builds the customer's display name, reproducing the COBOL
     * {@code STRING CUST-FIRST-NAME/CUST-MIDDLE-NAME/CUST-LAST-NAME DELIMITED BY ' '} that populates
     * {@code ST-NAME}. Each name field contributes only the characters up to its first embedded space
     * (the {@code DELIMITED BY ' '} semantics), and empty parts are skipped so the result is a
     * single-spaced {@code "First Middle Last"} string with no leading, trailing or doubled spaces.
     *
     * @param customer the customer whose name is assembled (never {@code null})
     * @return the assembled display name (possibly empty, never {@code null})
     */
    private static String buildCustomerName(Customer customer) {
        final StringBuilder name = new StringBuilder();
        appendToken(name, customer.getCustFirstName());
        appendToken(name, customer.getCustMiddleName());
        appendToken(name, customer.getCustLastName());
        return name.toString();
    }

    /**
     * Builds the city/state/country/zip line, reproducing the COBOL
     * {@code STRING CUST-ADDR-LINE-3/STATE-CD/COUNTRY-CD/ZIP DELIMITED BY ' '} that populates
     * {@code ST-ADD3}. Each field contributes only the characters up to its first embedded space,
     * matching the legacy verb exactly.
     *
     * @param customer the customer whose address tail is assembled (never {@code null})
     * @return the assembled address line (possibly empty, never {@code null})
     */
    private static String buildCityStateZip(Customer customer) {
        final StringBuilder line = new StringBuilder();
        appendToken(line, customer.getCustAddrLine3());
        appendToken(line, customer.getCustAddrStateCd());
        appendToken(line, customer.getCustAddrCountryCd());
        appendToken(line, customer.getCustAddrZip());
        return line.toString();
    }

    /**
     * Appends the {@code DELIMITED BY ' '} token of {@code field} to {@code target}, separated by a
     * single space when {@code target} already holds content. Empty tokens are skipped so no doubled
     * or trailing spaces are produced.
     *
     * @param target the buffer being assembled
     * @param field  the source field (may be {@code null})
     */
    private static void appendToken(StringBuilder target, String field) {
        final String token = firstToken(field);
        if (token.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append(' ');
        }
        target.append(token);
    }

    /**
     * Returns the leading token of a field under COBOL {@code STRING ... DELIMITED BY ' '} semantics:
     * the characters up to the first space. Fixed-width padding is stripped first so a space-padded
     * COBOL field yields its populated value, and a {@code null} or blank field yields the empty
     * string.
     *
     * @param field the source field (may be {@code null})
     * @return the leading space-delimited token (never {@code null})
     */
    private static String firstToken(String field) {
        if (field == null) {
            return "";
        }
        final String trimmed = field.strip();
        final int space = trimmed.indexOf(' ');
        return (space < 0) ? trimmed : trimmed.substring(0, space);
    }

    /**
     * Reproduces a fixed-width COBOL alphanumeric field ({@code PIC X(width)}): the value is
     * space-padded on the right to exactly {@code width} characters, or truncated when longer. A
     * {@code null} value renders as all spaces, as an uninitialized {@code PIC X} field would.
     *
     * @param value the source value (may be {@code null})
     * @param width the exact target width in characters
     * @return a string of exactly {@code width} characters
     */
    private static String fixed(String value, int width) {
        final String source = (value == null) ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Defensively bounds a rendered line to a fixed record length. Every text line is built to
     * exactly {@value #TEXT_RECORD_LENGTH} characters and every HTML line to at most
     * {@value #HTML_RECORD_LENGTH}, so this only ever acts as a safety net that mirrors the COBOL
     * {@code FD} record truncation ({@code FD-*-REC PIC X(n)}), guaranteeing the writer never has to
     * truncate.
     *
     * @param value     the rendered line (never {@code null})
     * @param maxLength the maximum record length
     * @return {@code value}, truncated to {@code maxLength} when longer
     */
    private static String bound(String value, int maxLength) {
        return (value.length() <= maxLength) ? value : value.substring(0, maxLength);
    }

    /**
     * Truncates a possibly-{@code null} value to at most {@code maxLength} characters without padding,
     * reproducing a COBOL {@code MOVE} into a shorter {@code PIC X} receiving field. A {@code null}
     * value becomes the empty string.
     *
     * @param value     the source value (may be {@code null})
     * @param maxLength the maximum length
     * @return the value bounded to {@code maxLength} (never {@code null})
     */
    private static String truncate(String value, int maxLength) {
        final String source = (value == null) ? "" : value;
        return (source.length() <= maxLength) ? source : source.substring(0, maxLength);
    }

    /**
     * Strips fixed-width padding from a field, returning the empty string for {@code null}. Used for
     * the HTML projections where the COBOL {@code STRING ... DELIMITED BY '  '} trims trailing
     * padding before embedding the value in a {@code <p>} element.
     *
     * @param value the source value (may be {@code null})
     * @return the stripped value (never {@code null})
     */
    private static String stripField(String value) {
        return (value == null) ? "" : value.strip();
    }

    /**
     * Renders an unsigned COBOL numeric field in {@code DISPLAY} form: the absolute value,
     * zero-padded to exactly {@code width} digits (low-order digits retained on overflow, matching
     * COBOL numeric truncation). A {@code null} value renders as all zeros, as an unpopulated numeric
     * field would.
     *
     * @param value the numeric value (may be {@code null})
     * @param width the exact digit count
     * @return a string of exactly {@code width} decimal digits
     */
    private static String digits(Long value, int width) {
        final long magnitude = (value == null) ? 0L : Math.abs(value);
        final String rendered = Long.toString(magnitude);
        if (rendered.length() > width) {
            return rendered.substring(rendered.length() - width);
        }
        return "0".repeat(width - rendered.length()) + rendered;
    }

    /**
     * Widens a nullable {@link Integer} to a nullable {@link Long}, preserving {@code null}. Bridges
     * the {@code Integer} FICO score to {@link #digits(Long, int)}.
     *
     * @param value the source value (may be {@code null})
     * @return the widened value, or {@code null}
     */
    private static Long toLong(Integer value) {
        return (value == null) ? null : value.longValue();
    }

    /**
     * Normalizes a monetary value to scale {@value #AMOUNT_SCALE} using
     * {@link RoundingMode#HALF_UP}, mapping {@code null} to {@code 0.00}. Keeps every amount on
     * {@link BigDecimal} (never {@code double}/{@code float}) per AAP &sect;0.8.2.
     *
     * @param value the amount (may be {@code null})
     * @return a non-{@code null} {@link BigDecimal} of scale {@value #AMOUNT_SCALE}
     */
    private static BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Formats a monetary value in the COBOL {@code PIC 9(9).99-} edit used by {@code ST-CURR-BAL}:
     * {@value #ZONED_INTEGER_DIGITS} zero-filled integer digits, a decimal point, two fraction
     * digits, and a trailing sign position ({@code '-'} when negative, a space otherwise). The
     * result is always 13 characters.
     *
     * @param amount the amount (may be {@code null}, treated as {@code 0.00})
     * @return the 13-character edited value
     */
    private static String formatSignedZoned(BigDecimal amount) {
        return formatZoned(amount, true);
    }

    /**
     * Formats a monetary value in the COBOL {@code PIC Z(9).99-} edit used by {@code ST-TRANAMT} and
     * {@code ST-TOTAL-TRAMT}: {@value #ZONED_INTEGER_DIGITS} integer digits with leading-zero
     * suppression (spaces), a decimal point, two fraction digits, and a trailing sign position
     * ({@code '-'} when negative, a space otherwise). The result is always 13 characters.
     *
     * @param amount the amount (may be {@code null}, treated as {@code 0.00})
     * @return the 13-character edited value
     */
    private static String formatSuppressedZoned(BigDecimal amount) {
        return formatZoned(amount, false);
    }

    /**
     * Shared implementation of the two COBOL money edits ({@code PIC 9(9).99-} and
     * {@code PIC Z(9).99-}). The value is normalized to scale {@value #AMOUNT_SCALE}, split into
     * integer and fraction using {@link BigInteger} cent arithmetic (no {@code double}/{@code float}
     * per AAP &sect;0.8.2), and edited into {@value #ZONED_INTEGER_DIGITS} integer digits plus
     * {@value #AMOUNT_SCALE} fraction digits and a trailing sign.
     *
     * @param amount     the amount (may be {@code null}, treated as {@code 0.00})
     * @param zeroFilled {@code true} for {@code 9(9)} zero fill; {@code false} for {@code Z(9)}
     *                   leading-zero suppression (spaces)
     * @return the edited value: {@value #ZONED_INTEGER_DIGITS} integer positions, {@code '.'},
     *         {@value #AMOUNT_SCALE} fraction digits, and one sign position (13 characters)
     */
    private static String formatZoned(BigDecimal amount, boolean zeroFilled) {
        final BigDecimal value = scaled(amount);
        final boolean negative = value.signum() < 0;
        final BigInteger cents = value.abs().movePointRight(AMOUNT_SCALE).toBigInteger();
        final BigInteger hundred = BigInteger.valueOf(100);
        final BigInteger integerPart = cents.divide(hundred);
        final BigInteger fractionPart = cents.remainder(hundred);

        String integerDigits = (!zeroFilled && integerPart.signum() == 0)
                ? ""
                : integerPart.toString();
        if (integerDigits.length() > ZONED_INTEGER_DIGITS) {
            integerDigits = integerDigits.substring(integerDigits.length() - ZONED_INTEGER_DIGITS);
        } else {
            final int padWidth = ZONED_INTEGER_DIGITS - integerDigits.length();
            integerDigits = (zeroFilled ? "0" : " ").repeat(padWidth) + integerDigits;
        }

        final StringBuilder fraction = new StringBuilder(fractionPart.toString());
        while (fraction.length() < AMOUNT_SCALE) {
            fraction.insert(0, '0');
        }

        return integerDigits + "." + fraction + (negative ? "-" : " ");
    }
}
