package com.cardemo.batch.processors;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Statement generation processor — translates CBSTM03A.CBL (924 lines) and
 * CBSTM03B.CBL (230 lines, file-service subroutine) to Java/Spring Batch.
 *
 * <p>Generates dual-format (text + HTML) account statements by joining card
 * cross-reference, customer, account, and transaction data. Each
 * {@link CardCrossReference} input record drives one complete statement cycle.
 *
 * <h3>COBOL Paragraph → Java Method Mapping:</h3>
 * <ul>
 *   <li>{@code 1000-MAINLINE}       → {@link #process(CardCrossReference)}</li>
 *   <li>{@code 2000-CUSTFILE-GET}   → {@code customerRepository.findById()}</li>
 *   <li>{@code 3000-ACCTFILE-GET}   → {@code accountRepository.findById()}</li>
 *   <li>{@code 4000-TRNXFILE-GET}   → {@code transactionRepository.findByTranCardNum()} +
 *       running total via {@link BigDecimal}</li>
 *   <li>{@code 5000-CREATE-STATEMENT} → {@link #generateTextStatement}</li>
 *   <li>{@code 5100-WRITE-HTML-HEADER} + {@code 5200-WRITE-HTML-NMADBS}
 *       → {@link #generateHtmlStatement}</li>
 *   <li>{@code 6000-WRITE-TRANS}    → inline in both generate methods</li>
 *   <li>{@code CBSTM03B} subroutine → entirely replaced by JPA repository calls</li>
 * </ul>
 *
 * <h3>Key COBOL Transformations:</h3>
 * <ul>
 *   <li>WS-TRNX-TABLE (51 cards × 10 transactions 2-D array) → JPA query results</li>
 *   <li>ALTER / GO TO control flow → structured Java methods</li>
 *   <li>WS-TOTAL-AMT PIC S9(9)V99 COMP-3 → {@link BigDecimal} with
 *       {@link RoundingMode#HALF_EVEN}</li>
 *   <li>PSA/TCB/TIOT mainframe control-block inspection → removed (irrelevant)</li>
 *   <li>STMT-FILE (LRECL=80) → text content string</li>
 *   <li>HTML-FILE (LRECL=100) → HTML content string</li>
 * </ul>
 */
@Component
public class StatementProcessor
        implements ItemProcessor<CardCrossReference, StatementWriter.StatementOutput> {

    private static final Logger log = LoggerFactory.getLogger(StatementProcessor.class);

    // -----------------------------------------------------------------------
    // Text statement constants – mirror COBOL STATEMENT-LINES (LRECL = 80)
    // -----------------------------------------------------------------------

    /** ST-LINE0: {@code *…START OF STATEMENT…*} (31 + 18 + 31 = 80 chars). */
    private static final String START_BANNER =
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /** ST-LINE15: {@code *…END OF STATEMENT…*} (32 + 16 + 32 = 80 chars). */
    private static final String END_BANNER =
            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /** ST-LINE5 / ST-LINE10 / ST-LINE12: full-width separator. */
    private static final String SEPARATOR = "-".repeat(80);

    /** Column header widths matching ST-LINE13 layout. */
    private static final int COL_TRAN_ID = 16;
    private static final int COL_TRAN_DESC = 51;
    private static final int COL_TRAN_AMT = 13;

    /** Maximum number of decimal digits for monetary amounts (PIC S9(9)V99). */
    private static final int AMOUNT_SCALE = 2;

    // -----------------------------------------------------------------------
    // Dependencies – replace CBSTM03B file-service subroutine operations
    // -----------------------------------------------------------------------

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Constructor-injected repositories replacing CBSTM03B subroutine.
     *
     * <p>CBSTM03B abstracted all file I/O through a single CALL interface
     * with operation codes: 'O' (open), 'C' (close), 'R' (sequential read),
     * 'K' (keyed read), 'W' (write), 'Z' (close all).
     * JPA repositories provide type-safe, transactional data access instead.
     *
     * @param customerRepository    replaces CBSTM03B 'K' on CUSTFILE
     * @param accountRepository     replaces CBSTM03B 'K' on ACCTFILE
     * @param transactionRepository replaces WS-TRNX-TABLE in-memory buffering
     */
    public StatementProcessor(CustomerRepository customerRepository,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // -----------------------------------------------------------------------
    // ItemProcessor contract
    // -----------------------------------------------------------------------

    /**
     * Core processing method — maps CBSTM03A 1000-MAINLINE.
     *
     * <p>For each {@link CardCrossReference} record (sequential XREF read):
     * <ol>
     *   <li>Look up customer (← 2000-CUSTFILE-GET via CBSTM03B 'K' on CUSTFILE)</li>
     *   <li>Look up account  (← 3000-ACCTFILE-GET via CBSTM03B 'K' on ACCTFILE)</li>
     *   <li>Retrieve transactions (← 4000-TRNXFILE-GET from WS-TRNX-TABLE)</li>
     *   <li>Generate dual-format statement (← 5000-CREATE-STATEMENT + 6000-WRITE-TRANS)</li>
     * </ol>
     *
     * @param item CardCrossReference record with card → customer → account linkage
     * @return {@link StatementWriter.StatementOutput} with card number, text and HTML,
     *         or {@code null} to skip
     * @throws Exception on unexpected processing failure
     */
    @Override
    public StatementWriter.StatementOutput process(CardCrossReference item) throws Exception {
        log.info("Processing statement for card: {}, customer: {}, account: {}",
                item.getXrefCardNum(), item.getXrefCustId(), item.getXrefAcctId());

        // ---------------------------------------------------------------
        // 2000-CUSTFILE-GET: Customer lookup via CBSTM03B 'K' on CUSTFILE
        //   COBOL: MOVE 'CUSTFILE' TO WS-M03B-DD
        //          MOVE 'K'        TO WS-M03B-ESSION
        //          MOVE XREF-CUST-ID TO WS-M03B-KEY
        //          CALL 'CBSTM03B' USING WS-M03B-AREA
        // ---------------------------------------------------------------
        Optional<Customer> customerOpt = customerRepository.findById(item.getXrefCustId());
        if (customerOpt.isEmpty()) {
            log.warn("Customer not found for ID: {}, skipping statement for card: {}",
                    item.getXrefCustId(), item.getXrefCardNum());
            return null;
        }
        Customer customer = customerOpt.get();
        log.debug("Customer retrieved: {} {} (ID: {})",
                customer.getCustFirstName(), customer.getCustLastName(),
                item.getXrefCustId());

        // ---------------------------------------------------------------
        // 3000-ACCTFILE-GET: Account lookup via CBSTM03B 'K' on ACCTFILE
        //   COBOL: MOVE 'ACCTFILE' TO WS-M03B-DD
        //          MOVE 'K'        TO WS-M03B-ESSION
        //          MOVE XREF-ACCT-ID TO WS-M03B-KEY
        //          CALL 'CBSTM03B' USING WS-M03B-AREA
        // ---------------------------------------------------------------
        Optional<Account> accountOpt = accountRepository.findById(item.getXrefAcctId());
        if (!accountOpt.isPresent()) {
            log.warn("Account not found for ID: {}, skipping statement for card: {}",
                    item.getXrefAcctId(), item.getXrefCardNum());
            return null;
        }
        Account account = accountOpt.orElse(null);
        if (account == null) {
            log.error("Account resolved to null unexpectedly for ID: {}", item.getXrefAcctId());
            return null;
        }
        log.debug("Account retrieved: {}, balance: {}",
                account.getAcctId(),
                account.getAcctCurrBal() != null ? account.getAcctCurrBal().toString() : "N/A");

        // ---------------------------------------------------------------
        // 4000-TRNXFILE-GET: Transaction retrieval
        //   Replaces COBOL WS-TRNX-TABLE (51 cards × 10 transactions)
        //   in-memory 2-D array with a direct JPA query.
        //   COBOL: PERFORM VARYING CR-JMP FROM 1 BY 1
        //            UNTIL CR-JMP > CR-CNT
        //            OR (WS-CARD-NUM(CR-JMP) > XREF-CARD-NUM)
        //              IF XREF-CARD-NUM = WS-CARD-NUM(CR-JMP)
        //                PERFORM 6000-WRITE-TRANS
        //                ADD TRNX-AMT TO WS-TOTAL-AMT
        //
        // Behavioral parity note: The COBOL CBSTM03A uses sequential READNEXT
        // through a physical TRNXFILE bounded by the sorted file size. The JPA
        // query here is unbounded (no date range filter). This preserves COBOL
        // semantics where all transactions for a card are included in the
        // statement. For production environments with large transaction histories,
        // consider adding a statement-period date range filter to bound the query.
        // ---------------------------------------------------------------
        List<Transaction> transactions =
                transactionRepository.findByTranCardNum(item.getXrefCardNum());
        if (transactions.isEmpty()) {
            log.info("No transactions found for card: {}, generating empty statement",
                    item.getXrefCardNum());
        }
        log.info("Retrieved {} transaction(s) for card: {}",
                transactions.size(), item.getXrefCardNum());

        // ---------------------------------------------------------------
        // 5000-CREATE-STATEMENT + 6000-WRITE-TRANS: Dual-format generation
        // ---------------------------------------------------------------
        String textContent = generateTextStatement(customer, account, transactions);
        String htmlContent = generateHtmlStatement(customer, account, transactions);

        log.info("Statement generated for account: {} ({} transactions processed)",
                account.getAcctId(), transactions.size());

        return new StatementWriter.StatementOutput(item.getXrefCardNum(), textContent, htmlContent);
    }

    // -----------------------------------------------------------------------
    // Text statement generation — maps 5000-CREATE-STATEMENT + 6000-WRITE-TRANS
    // Output mirrors STMT-FILE sequential writes (LRECL = 80)
    // -----------------------------------------------------------------------

    /**
     * Generates the plain-text statement matching the COBOL STATEMENT-LINES
     * layout written to STMT-FILE.
     *
     * <p>Line sequence exactly mirrors the COBOL WRITE sequence in
     * 5000-CREATE-STATEMENT (lines 458-504) and 4000-TRNXFILE-GET (lines
     * 416-456).
     *
     * @param customer     customer entity with name and address fields
     * @param account      account entity with ID and current balance
     * @param transactions list of transactions for this card
     * @return complete text statement as a single string
     */
    private String generateTextStatement(Customer customer,
                                         Account account,
                                         List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder(2048);

        // ST-LINE0: Start banner
        sb.append(START_BANNER).append('\n');

        // ST-LINE1: Customer name (75 chars) — maps COBOL STRING … INTO ST-NAME
        String customerName = buildCustomerName(customer);
        sb.append(padRight(customerName, 75)).append("     \n");

        // ST-LINE2: Address line 1 (50 chars + 30 filler)
        sb.append(padRight(nullSafe(customer.getCustAddrLine1()), 50))
                .append("                              \n");

        // ST-LINE3: Address line 2 (50 chars + 30 filler)
        sb.append(padRight(nullSafe(customer.getCustAddrLine2()), 50))
                .append("                              \n");

        // ST-LINE4: Address line 3 + state + country + zip (80 chars)
        String addressLine3 = buildAddressLine3(customer);
        sb.append(padRight(addressLine3, 80)).append('\n');

        // ST-LINE5: Separator
        sb.append(SEPARATOR).append('\n');

        // ST-LINE6: "Basic Details" centered (33 spaces + 14 chars + 33 spaces)
        sb.append("                                 Basic Details                                 \n");

        // ST-LINE5 again: Separator (COBOL writes ST-LINE5 twice)
        sb.append(SEPARATOR).append('\n');

        // ST-LINE7: Account ID (PIC X(20) label + PIC X(20) value + 40 filler)
        sb.append("Account ID         :")
                .append(padRight(nullSafe(account.getAcctId()), 20))
                .append("                                        \n");

        // ST-LINE8: Current Balance (PIC X(20) label + PIC 9(9).99- value + 47 filler)
        sb.append("Current Balance    :")
                .append(formatBalanceText(account.getAcctCurrBal()))
                .append('\n');

        // ST-LINE9: FICO Score (PIC X(20) label + PIC X(20) value + 40 filler)
        sb.append("FICO Score         :")
                .append(padRight(formatFicoScore(customer.getCustFicoCreditScore()), 20))
                .append("                                        \n");

        // ST-LINE10: Separator
        sb.append(SEPARATOR).append('\n');

        // ST-LINE11: "TRANSACTION SUMMARY" centered (30 + 20 + 30)
        sb.append("                              TRANSACTION SUMMARY                              \n");

        // ST-LINE12: Separator
        sb.append(SEPARATOR).append('\n');

        // ST-LINE13: Column headers (16 + 51 + 13 = 80)
        sb.append(padRight("Tran ID", COL_TRAN_ID))
                .append(padRight("Tran Details", COL_TRAN_DESC))
                .append(padLeft("Tran Amount", COL_TRAN_AMT))
                .append('\n');

        // ST-LINE12: Separator (COBOL writes ST-LINE12 again after headers)
        sb.append(SEPARATOR).append('\n');

        // 6000-WRITE-TRANS: Per-transaction detail lines
        // COBOL: MOVE TRNX-ID TO ST-TRANID (16)
        //        MOVE TRNX-DESC TO ST-TRANDT (49)
        //        MOVE TRNX-AMT TO ST-TRANAMT PIC Z(9).99-
        //        ADD TRNX-AMT TO WS-TOTAL-AMT
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (Transaction tran : transactions) {
            BigDecimal amt = tran.getTranAmt() != null ? tran.getTranAmt() : BigDecimal.ZERO;
            totalAmt = totalAmt.add(amt);

            // ST-LINE14: tranId(16) + ' ' + tranDesc(49) + '$' + amount Z(9).99-
            sb.append(padRight(nullSafe(tran.getTranId()), COL_TRAN_ID))
                    .append(' ')
                    .append(padRight(truncate(nullSafe(tran.getTranDesc()), 49), 49))
                    .append('$')
                    .append(formatAmountZSuppressed(amt))
                    .append('\n');
        }

        // After all transactions — COBOL lines 433-437
        // ST-LINE12: Separator
        sb.append(SEPARATOR).append('\n');

        // ST-LINE14A: Total line (10 + 56 spaces + '$' + Z(9).99-)
        sb.append("Total EXP:")
                .append(" ".repeat(56))
                .append('$')
                .append(formatAmountZSuppressed(totalAmt))
                .append('\n');

        // ST-LINE15: End banner
        sb.append(END_BANNER).append('\n');

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // HTML statement generation — maps 5100-WRITE-HTML-HEADER +
    //   5200-WRITE-HTML-NMADBS + 6000-WRITE-TRANS HTML sections
    // Output mirrors HTML-FILE sequential writes (LRECL = 100)
    // -----------------------------------------------------------------------

    /**
     * Generates the HTML statement matching the COBOL HTML-LINES 88-level
     * template values written to HTML-FILE.
     *
     * <p>The HTML structure follows CBSTM03A exactly:
     * <ul>
     *   <li>5100-WRITE-HTML-HEADER: DOCTYPE through bank info row</li>
     *   <li>5200-WRITE-HTML-NMADBS: customer name/address, basic details,
     *       transaction summary header with column headers</li>
     *   <li>6000-WRITE-TRANS: per-transaction rows</li>
     *   <li>Footer: End of Statement, closing tags</li>
     * </ul>
     *
     * @param customer     customer entity with name and address fields
     * @param account      account entity with ID and current balance
     * @param transactions list of transactions for this card
     * @return complete HTML document as a single string
     */
    private String generateHtmlStatement(Customer customer,
                                         Account account,
                                         List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder(4096);

        // ---------------------------------------------------------------
        // 5100-WRITE-HTML-HEADER: HTML-L01 through HTML-L22-35
        // ---------------------------------------------------------------

        // HTML-L01 through HTML-L08: Document structure
        sb.append("<!DOCTYPE html>\n");                                      // HTML-L01
        sb.append("<html lang=\"en\">\n");                                   // HTML-L02
        sb.append("<head>\n");                                               // HTML-L03
        sb.append("<meta charset=\"utf-8\">\n");                             // HTML-L04
        sb.append("<title>HTML Table Layout</title>\n");                     // HTML-L05
        sb.append("</head>\n");                                              // HTML-L06
        sb.append("<body style=\"margin:0px;\">\n");                         // HTML-L07
        sb.append("<table  align=\"center\" frame=\"box\"")                  // HTML-L08
                .append(" style=\"width:70%; font:12px Segoe UI,sans-serif;\">\n");

        // Account header — dark blue (#1d1d96b3) background
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L10
                .append("background-color:#1d1d96b3;\">\n");
        // HTML-L11: Account number heading (dynamic)
        sb.append("<h3>Statement for Account Number: ")
                .append(escapeHtml(nullSafe(account.getAcctId())))
                .append("</h3>\n");
        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Bank info — orange (#FFAF33) background
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L15
                .append("background-color:#FFAF33;\">\n");
        sb.append("<p style=\"font-size:16px\">Bank of XYZ</p>\n");         // HTML-L16
        sb.append("<p>410 Terry Ave N</p>\n");                               // HTML-L17
        sb.append("<p>Seattle WA 99999</p>\n");                              // HTML-L18
        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Customer name/address — gray (#f2f2f2) background
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L22-35
                .append("background-color:#f2f2f2;\">\n");

        // ---------------------------------------------------------------
        // 5200-WRITE-HTML-NMADBS: Name, address, basic details, tran summary
        // ---------------------------------------------------------------

        // Customer name (HTML-L23 with dynamic L23-NAME)
        String customerName = buildCustomerName(customer);
        sb.append("<p style=\"font-size:16px\">")
                .append(escapeHtml(customerName))
                .append("</p>\n");

        // Address lines (HTML-ADDR-LN dynamic formatting)
        sb.append("<p>").append(escapeHtml(nullSafe(customer.getCustAddrLine1()).trim()))
                .append("</p>\n");
        sb.append("<p>").append(escapeHtml(nullSafe(customer.getCustAddrLine2()).trim()))
                .append("</p>\n");
        sb.append("<p>").append(escapeHtml(buildAddressLine3(customer)))
                .append("</p>\n");

        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Basic Details header — teal (#33FFD1) background, centered
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L30-42
                .append("background-color:#33FFD1; text-align:center;\">\n");
        sb.append("<p style=\"font-size:16px\">Basic Details</p>\n");        // HTML-L31
        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Basic details rows — gray (#f2f2f2) background
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L22-35
                .append("background-color:#f2f2f2;\">\n");

        // Account ID, Current Balance, FICO Score (HTML-BSIC-LN dynamic)
        sb.append("<p>Account ID         : ")
                .append(escapeHtml(nullSafe(account.getAcctId())))
                .append("</p>\n");
        sb.append("<p>Current Balance    : ")
                .append(formatBalanceHtml(account.getAcctCurrBal()))
                .append("</p>\n");
        sb.append("<p>FICO Score         : ")
                .append(escapeHtml(formatFicoScore(customer.getCustFicoCreditScore())))
                .append("</p>\n");

        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Transaction Summary header — teal (#33FFD1) background, centered
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L30-42
                .append("background-color:#33FFD1; text-align:center;\">\n");
        sb.append("<p style=\"font-size:16px\">Transaction Summary</p>\n");  // HTML-L43
        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Column headers — green (#33FF5E) background
        sb.append("<tr>\n");                                                 // HTML-LTRS
        // Tran ID column (25%, left-aligned) — HTML-L47 + HTML-L48
        sb.append("<td style=\"width:25%; padding:0px 5px; ")
                .append("background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran ID</p>\n");
        sb.append("</td>\n");
        // Tran Details column (55%, left-aligned) — HTML-L50 + HTML-L51
        sb.append("<td style=\"width:55%; padding:0px 5px; ")
                .append("background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran Details</p>\n");
        sb.append("</td>\n");
        // Amount column (20%, right-aligned) — HTML-L53 + HTML-L54
        sb.append("<td style=\"width:20%; padding:0px 5px; ")
                .append("background-color:#33FF5E; text-align:right;\">\n");
        sb.append("<p style=\"font-size:16px\">Amount</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");                                                // HTML-LTRE

        // ---------------------------------------------------------------
        // 6000-WRITE-TRANS: Per-transaction rows (HTML section)
        // Each transaction row uses HTML-L58/L61/L64 column styles
        // ---------------------------------------------------------------
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (Transaction tran : transactions) {
            BigDecimal amt = tran.getTranAmt() != null ? tran.getTranAmt() : BigDecimal.ZERO;
            totalAmt = totalAmt.add(amt);

            sb.append("<tr>\n");                                             // HTML-LTRS
            // Tran ID cell — HTML-L58 (25%, gray, left)
            sb.append("<td style=\"width:25%; padding:0px 5px; ")
                    .append("background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>").append(escapeHtml(nullSafe(tran.getTranId())))
                    .append("</p>\n");
            sb.append("</td>\n");
            // Tran Details cell — HTML-L61 (55%, gray, left)
            sb.append("<td style=\"width:55%; padding:0px 5px; ")
                    .append("background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>").append(escapeHtml(nullSafe(tran.getTranDesc())))
                    .append("</p>\n");
            sb.append("</td>\n");
            // Amount cell — HTML-L64 (20%, gray, right)
            sb.append("<td style=\"width:20%; padding:0px 5px; ")
                    .append("background-color:#f2f2f2; text-align:right;\">\n");
            sb.append("<p>").append(formatAmountHtml(amt))
                    .append("</p>\n");
            sb.append("</td>\n");
            sb.append("</tr>\n");                                            // HTML-LTRE
        }

        // ---------------------------------------------------------------
        // Footer — after all transactions (end of 4000-TRNXFILE-GET)
        // Dark blue (#1d1d96b3) background
        // ---------------------------------------------------------------
        sb.append("<tr>\n");                                                 // HTML-LTRS
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;")              // HTML-L10
                .append("background-color:#1d1d96b3;\">\n");
        sb.append("<h3>End of Statement</h3>\n");                            // HTML-L75
        sb.append("</td>\n");                                                // HTML-LTDE
        sb.append("</tr>\n");                                                // HTML-LTRE

        // Closing tags
        sb.append("</table>\n");                                             // HTML-L78
        sb.append("</body>\n");                                              // HTML-L79
        sb.append("</html>\n");                                              // HTML-L80

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Formatting helpers — preserve COBOL PIC clause semantics with BigDecimal
    // -----------------------------------------------------------------------

    /**
     * Builds the customer full name from first, middle, and last name fields.
     *
     * <p>Maps COBOL STRING:
     * <pre>
     * STRING CUST-FIRST-NAME DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-MIDDLE-NAME DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-LAST-NAME DELIMITED BY ' '
     *        INTO ST-NAME
     * </pre>
     *
     * <p>COBOL DELIMITED BY ' ' trims at the first space character, so
     * Java {@link String#trim()} followed by space-delimited join replicates
     * the same behavior for typical name fields.
     *
     * @param customer the customer entity
     * @return formatted full name string
     */
    private String buildCustomerName(Customer customer) {
        StringBuilder name = new StringBuilder(75);
        String first = nullSafe(customer.getCustFirstName()).trim();
        String middle = nullSafe(customer.getCustMiddleName()).trim();
        String last = nullSafe(customer.getCustLastName()).trim();

        if (!first.isEmpty()) {
            name.append(first);
        }
        if (!middle.isEmpty()) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(middle);
        }
        if (!last.isEmpty()) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(last);
        }
        return name.toString();
    }

    /**
     * Builds the third address line from address line 3, state, country, and ZIP.
     *
     * <p>Maps COBOL STRING:
     * <pre>
     * STRING CUST-ADDR-LINE-3 DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-STATE-CD DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-COUNTRY-CD DELIMITED BY ' '
     *        ' ' DELIMITED BY SIZE
     *        CUST-ADDR-ZIP DELIMITED BY ' '
     *        INTO ST-ADD3
     * </pre>
     *
     * @param customer the customer entity
     * @return formatted address line
     */
    private String buildAddressLine3(Customer customer) {
        StringBuilder addr = new StringBuilder(80);
        String line3 = nullSafe(customer.getCustAddrLine3()).trim();
        String state = nullSafe(customer.getCustAddrStateCd()).trim();
        String country = nullSafe(customer.getCustAddrCountryCd()).trim();
        String zip = nullSafe(customer.getCustAddrZip()).trim();

        if (!line3.isEmpty()) {
            addr.append(line3);
        }
        if (!state.isEmpty()) {
            if (!addr.isEmpty()) {
                addr.append(' ');
            }
            addr.append(state);
        }
        if (!country.isEmpty()) {
            if (!addr.isEmpty()) {
                addr.append(' ');
            }
            addr.append(country);
        }
        if (!zip.isEmpty()) {
            if (!addr.isEmpty()) {
                addr.append(' ');
            }
            addr.append(zip);
        }
        return addr.toString();
    }

    /**
     * Formats a monetary amount for text statement output, matching
     * COBOL {@code PIC Z(9).99-} (zero-suppressed, trailing sign).
     *
     * <p>Z(9) = 9 digit positions with leading zero suppression (spaces).
     * .99 = decimal point and 2 decimal digits.
     * {@code -} = trailing minus for negative, trailing space for positive.
     * Total width: 13 characters.
     *
     * <p>Uses {@link BigDecimal} exclusively per AAP §0.8.2 — zero
     * float/double substitution. Scale set to 2 with
     * {@link RoundingMode#HALF_EVEN} (banker's rounding, matching COBOL default).
     *
     * @param amount the monetary amount (may be null, treated as zero)
     * @return 13-character formatted string matching PIC Z(9).99-
     */
    private String formatAmountZSuppressed(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();

        // Split into integer and fractional parts
        String plain = abs.toPlainString();
        int dotIdx = plain.indexOf('.');
        String intPart;
        String decPart;
        if (dotIdx >= 0) {
            intPart = plain.substring(0, dotIdx);
            decPart = plain.substring(dotIdx + 1);
        } else {
            intPart = plain;
            decPart = "00";
        }
        // Ensure exactly 2 decimal digits
        if (decPart.length() < 2) {
            decPart = decPart + "0".repeat(2 - decPart.length());
        } else if (decPart.length() > 2) {
            decPart = decPart.substring(0, 2);
        }

        // Z(9) = right-align integer part in 9 character positions, leading spaces
        String formattedInt = String.format("%9s", intPart);
        // Trailing sign: '-' for negative, ' ' for positive
        char sign = negative ? '-' : ' ';

        return formattedInt + "." + decPart + sign;
    }

    /**
     * Formats the current balance for text statement output, matching
     * COBOL {@code PIC 9(9).99-} (leading zeros preserved).
     *
     * <p>9(9) = 9 digit positions with leading zeros displayed.
     * .99 = decimal point and 2 decimal digits.
     * {@code -} = trailing minus for negative, trailing space for positive.
     *
     * @param balance the account current balance (may be null)
     * @return formatted balance string with trailing filler
     */
    private String formatBalanceText(BigDecimal balance) {
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
        BigDecimal scaled = balance.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();

        String plain = abs.toPlainString();
        int dotIdx = plain.indexOf('.');
        String intPart;
        String decPart;
        if (dotIdx >= 0) {
            intPart = plain.substring(0, dotIdx);
            decPart = plain.substring(dotIdx + 1);
        } else {
            intPart = plain;
            decPart = "00";
        }
        if (decPart.length() < 2) {
            decPart = decPart + "0".repeat(2 - decPart.length());
        } else if (decPart.length() > 2) {
            decPart = decPart.substring(0, 2);
        }

        // 9(9) = right-align with leading zeros in 9 positions
        String paddedInt = "0".repeat(Math.max(0, 9 - intPart.length())) + intPart;
        if (paddedInt.length() > 9) {
            paddedInt = paddedInt.substring(paddedInt.length() - 9);
        }
        char sign = negative ? '-' : ' ';

        // PIC 9(9).99- + 7 filler spaces + 40 filler spaces = fills 80 chars
        return paddedInt + "." + decPart + sign
                + "       " + "                                        \n";
    }

    /**
     * Formats a monetary amount for HTML output — plain decimal with 2 decimal places.
     *
     * <p>Uses {@link BigDecimal#toPlainString()} to avoid scientific notation.
     *
     * @param amount the monetary amount (may be null)
     * @return formatted amount string
     */
    private String formatAmountHtml(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Formats the account balance for HTML output — plain decimal string matching
     * the COBOL ST-CURR-BAL formatting used in HTML-BSIC-LN.
     *
     * @param balance the account current balance (may be null)
     * @return formatted balance string
     */
    private String formatBalanceHtml(BigDecimal balance) {
        if (balance == null) {
            return "0.00";
        }
        return balance.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Formats the FICO credit score for display.
     *
     * <p>Maps COBOL {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE}
     * where ST-FICO-SCORE is PIC X(20).
     *
     * @param ficoScore the FICO score (may be null)
     * @return string representation of the score, or empty string
     */
    private String formatFicoScore(Short ficoScore) {
        if (ficoScore == null) {
            return "";
        }
        return ficoScore.toString();
    }

    // -----------------------------------------------------------------------
    // String utility helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the input string if non-null, or an empty string otherwise.
     *
     * @param value nullable string
     * @return the original value or empty string
     */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /**
     * Right-pads a string to the specified length with spaces, or truncates
     * if the string exceeds the target length.
     *
     * @param text   the source string
     * @param length target length
     * @return padded or truncated string
     */
    private String padRight(String text, int length) {
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return text + " ".repeat(length - text.length());
    }

    /**
     * Left-pads a string to the specified length with spaces, or truncates
     * from the left if the string exceeds the target length.
     *
     * @param text   the source string
     * @param length target length
     * @return padded or truncated string
     */
    private String padLeft(String text, int length) {
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return " ".repeat(length - text.length()) + text;
    }

    /**
     * Truncates a string to the specified maximum length.
     *
     * @param text      the source string
     * @param maxLength maximum allowed length
     * @return the original string or truncated version
     */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    /**
     * Escapes HTML special characters to prevent XSS and ensure valid markup.
     *
     * @param text the raw text
     * @return HTML-safe escaped text
     */
    private String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
