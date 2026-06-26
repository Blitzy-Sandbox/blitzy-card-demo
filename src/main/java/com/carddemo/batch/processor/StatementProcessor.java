/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.processor;

import com.carddemo.dto.StatementDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that generates a per-card account statement
 * in two presentations — an 80-column plain-text rendering and a 100-column HTML
 * rendering — translating the legacy COBOL statement generator {@code CBSTM03A}
 * (with its file-service subroutine {@code CBSTM03B}, JCL {@code CREASTMT}, and
 * statement copybook {@code COSTM01}) at source commit {@code 27d6c6f}.
 *
 * <p>The COBOL program drives the run from the card cross-reference file: for
 * each card it reads the owning customer (by customer id) and account (by account
 * id), builds the card's transaction table from the {@code TRNX} key path, then
 * formats the statement through paragraphs {@code 5000-CREATE-STATEMENT} and
 * {@code 6000-WRITE-TRANS} while accumulating a running total. The static
 * {@code CALL 'CBSTM03B'} file-service subroutine (DD-routed open/read/read-key/
 * close over {@code TRNXFILE}, {@code XREFFILE}, {@code CUSTFILE}, and
 * {@code ACCTFILE}) is replaced by constructor-injected Spring Data
 * repositories.</p>
 *
 * <p>The processor consumes one {@link CardXref} item per card and returns a
 * {@link StatementBundle} carrying the fully rendered text and HTML payloads plus
 * the {@link BigDecimal} total. Rendering is performed here so the downstream
 * fixed-width writer remains presentation-agnostic; the text payload reproduces
 * the {@code FD-STMTFILE-REC PIC X(80)} stream and the HTML payload reproduces
 * the {@code FD-HTMLFILE-REC PIC X(100)} stream, line for line.</p>
 *
 * <p>Monetary values use {@link BigDecimal} with scale 2 and
 * {@link RoundingMode#HALF_EVEN}; {@code double} and {@code float} are never
 * used. The COBOL numeric edit masks {@code PIC 9(9).99-} (zero-filled balance)
 * and {@code PIC Z(9).99-} (zero-suppressed amounts) are reproduced exactly,
 * including the trailing sign position. The component is stateless and therefore
 * thread-safe.</p>
 */
@Component
public class StatementProcessor implements ItemProcessor<CardXref, StatementProcessor.StatementBundle> {

    /** Fixed record width of the plain-text statement ({@code FD-STMTFILE-REC PIC X(80)}). */
    private static final int TEXT_WIDTH = 80;

    /** Fixed record width of the HTML statement ({@code FD-HTMLFILE-REC PIC X(100)}). */
    private static final int HTML_WIDTH = 100;

    /** Width of the {@code TRAN-ID} / {@code ST-TRANID} field ({@code PIC X(16)}). */
    private static final int TRAN_ID_WIDTH = 16;

    /** Width of the truncated transaction-description field {@code ST-TRANDT} ({@code PIC X(49)}). */
    private static final int TRAN_DESC_WIDTH = 49;

    /** Width of the edited-amount fields {@code ST-TRANAMT} / {@code ST-TOTAL-TRAMT}. */
    private static final int AMOUNT_EDIT_WIDTH = 13;

    /** Width of the account-id display field {@code ST-ACCT-ID} ({@code PIC X(20)}). */
    private static final int ACCT_ID_FIELD_WIDTH = 20;

    /** Width of the FICO display field {@code ST-FICO-SCORE} ({@code PIC X(20)}). */
    private static final int FICO_FIELD_WIDTH = 20;

    /** Number of integer digits in the COBOL edit masks {@code 9(9)} / {@code Z(9)}. */
    private static final int EDIT_INTEGER_DIGITS = 9;

    /** Line separator used to join the fixed-width records into a single payload. */
    private static final String NEWLINE = "\n";

    private static final String BORDER = "-".repeat(TEXT_WIDTH);
    private static final String TEXT_LINE0 = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
    private static final String TEXT_BASIC_DETAILS = " ".repeat(33) + "Basic Details" + " ".repeat(34);
    private static final String TEXT_TRAN_SUMMARY = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);
    private static final String TEXT_COLUMN_HEADER =
            "Tran ID" + " ".repeat(9) + "Tran Details" + " ".repeat(39) + "  Tran Amount";
    private static final String TEXT_LINE15 = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    private static final String LABEL_ACCT = "Account ID" + " ".repeat(9) + ":";
    private static final String LABEL_BAL = "Current Balance" + " ".repeat(4) + ":";
    private static final String LABEL_FICO = "FICO Score" + " ".repeat(9) + ":";
    private static final String LABEL_TOTAL = "Total EXP:";

    private static final String HTML_L01 = "<!DOCTYPE html>";
    private static final String HTML_L02 = "<html lang=\"en\">";
    private static final String HTML_L03 = "<head>";
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";
    private static final String HTML_L06 = "</head>";
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    private static final String HTML_TR_OPEN = "<tr>";
    private static final String HTML_TR_CLOSE = "</tr>";
    private static final String HTML_TD_CLOSE = "</td>";
    private static final String HTML_TD_TITLE = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
    private static final String HTML_TD_BANK = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
    private static final String HTML_BANK_NAME = "<p style=\"font-size:16px\">Bank of XYZ</p>";
    private static final String HTML_BANK_ADDR1 = "<p>410 Terry Ave N</p>";
    private static final String HTML_BANK_ADDR2 = "<p>Seattle WA 99999</p>";
    private static final String HTML_TD_GREY = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
    private static final String HTML_TD_SECTION =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
    private static final String HTML_SECTION_BASIC = "<p style=\"font-size:16px\">Basic Details</p>";
    private static final String HTML_SECTION_SUMMARY = "<p style=\"font-size:16px\">Transaction Summary</p>";
    private static final String HTML_TH_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_TH_ID_LABEL = "<p style=\"font-size:16px\">Tran ID</p>";
    private static final String HTML_TH_DETAILS = "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_TH_DETAILS_LABEL = "<p style=\"font-size:16px\">Tran Details</p>";
    private static final String HTML_TH_AMOUNT = "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String HTML_TH_AMOUNT_LABEL = "<p style=\"font-size:16px\">Amount</p>";
    private static final String HTML_TD_ID = "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_TD_DETAILS = "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_TD_AMOUNT = "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
    private static final String HTML_END = "<h3>End of Statement</h3>";
    private static final String HTML_TABLE_CLOSE = "</table>";
    private static final String HTML_BODY_CLOSE = "</body>";
    private static final String HTML_HTML_CLOSE = "</html>";
    private static final String HTML_ACCT_PREFIX = "<h3>Statement for Account Number: ";
    private static final String HTML_BASIC_ACCT = "<p>Account ID" + " ".repeat(9) + ": ";
    private static final String HTML_BASIC_BAL = "<p>Current Balance" + " ".repeat(4) + ": ";
    private static final String HTML_BASIC_FICO = "<p>FICO Score" + " ".repeat(9) + ": ";

    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    /**
     * Creates the statement processor with its required data-access collaborators.
     * Constructor injection reproduces the COBOL {@code CBSTM03B} file-service
     * subroutine as Spring-managed repository beans.
     *
     * @param transactionRepository repository for posted transactions
     *                              ({@code TRNXFILE} by-card read path)
     * @param customerRepository    repository for customer master records
     *                              ({@code CUSTFILE} keyed read)
     * @param accountRepository     repository for account master records
     *                              ({@code ACCTFILE} keyed read)
     */
    public StatementProcessor(TransactionRepository transactionRepository,
                              CustomerRepository customerRepository,
                              AccountRepository accountRepository) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
    }

    /**
     * Produces the text and HTML statements for a single card.
     *
     * <p>Reproduces the per-card cycle of {@code CBSTM03A 1000-MAINLINE}: resolve
     * the owning customer and account for the cross-reference, read the card's
     * transactions in {@code TRAN-ID} order, render both statement presentations,
     * and accumulate the running total. A missing customer or account reproduces
     * the COBOL abend on a non-{@code '00'} keyed read by raising an
     * {@link IllegalStateException}, which fails the batch step rather than
     * emitting an incomplete statement.</p>
     *
     * @param cardXref the card cross-reference item supplying the card number and
     *                 its owning customer and account identifiers
     * @return the rendered statement bundle for the card; never {@code null}
     */
    @Override
    public StatementBundle process(CardXref cardXref) {
        Objects.requireNonNull(cardXref, "cardXref item must not be null");

        Long custId = cardXref.getXrefCustId();
        if (custId == null) {
            throw new IllegalStateException(
                    "Card cross-reference " + cardXref.getXrefCardNum() + " has no customer id");
        }
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new IllegalStateException("Customer not found for id " + custId));

        Long acctId = cardXref.getXrefAcctId();
        if (acctId == null) {
            throw new IllegalStateException(
                    "Card cross-reference " + cardXref.getXrefCardNum() + " has no account id");
        }
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new IllegalStateException("Account not found for id " + acctId));

        List<Transaction> transactions =
                transactionRepository.findByCardNumOrderByTranIdAsc(cardXref.getXrefCardNum());

        List<StatementDto> lines = new ArrayList<>(transactions.size());
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        for (Transaction transaction : transactions) {
            StatementDto line = toStatementDto(transaction);
            lines.add(line);
            if (line.amount() != null) {
                total = total.add(line.amount());
            }
        }
        total = total.setScale(2, RoundingMode.HALF_EVEN);

        String text = renderText(customer, account, lines, total);
        String html = renderHtml(customer, account, lines, total);
        return new StatementBundle(text, html, total);
    }

    /**
     * Maps a posted {@link Transaction} onto the {@link StatementDto} reporting
     * line defined by the COBOL {@code COSTM01 TRNX-RECORD} layout.
     *
     * <p>The two-character {@code TRAN-TYPE-CD} is surfaced as its legacy code
     * string, the numeric category and merchant identifiers become their digit
     * strings, the monetary amount is normalised to scale 2 with
     * {@link RoundingMode#HALF_EVEN}, and the two 26-character timestamps are
     * preserved verbatim. Trailing pad spaces from fixed-width columns are removed
     * from the free-text and identifier fields.</p>
     *
     * @param transaction the posted transaction to map; must not be {@code null}
     * @return the populated statement reporting line
     */
    public StatementDto toStatementDto(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        String transactionType = transaction.getTranTypeCd();
        String categoryCode =
                transaction.getTranCatCd() == null ? null : String.valueOf(transaction.getTranCatCd());
        String merchantId =
                transaction.getMerchantId() == null ? null : String.valueOf(transaction.getMerchantId());
        BigDecimal amount =
                transaction.getTranAmt() == null ? null : transaction.getTranAmt().setScale(2, RoundingMode.HALF_EVEN);
        return new StatementDto(
                rtrim(transaction.getCardNum()),
                rtrim(transaction.getTranId()),
                transactionType,
                categoryCode,
                rtrim(transaction.getTranSource()),
                rtrim(transaction.getTranDesc()),
                amount,
                merchantId,
                rtrim(transaction.getMerchantName()),
                rtrim(transaction.getMerchantCity()),
                rtrim(transaction.getMerchantZip()),
                transaction.getOrigTs(),
                transaction.getProcTs());
    }

    /**
     * Renders the 80-column plain-text statement, reproducing the write order of
     * COBOL paragraphs {@code 5000-CREATE-STATEMENT}, {@code 6000-WRITE-TRANS},
     * and the trailer of {@code 4000-TRNXFILE-GET}. Every emitted line is exactly
     * {@value #TEXT_WIDTH} characters, matching {@code FD-STMTFILE-REC PIC X(80)}.
     *
     * @param customer the statement owner
     * @param account  the statement account
     * @param lines    the per-transaction reporting lines in {@code TRAN-ID} order
     * @param total    the running total of the card's transaction amounts
     * @return the rendered text statement as newline-joined fixed-width records
     */
    private String renderText(Customer customer, Account account,
                              List<StatementDto> lines, BigDecimal total) {
        List<String> records = new ArrayList<>();
        records.add(TEXT_LINE0);
        records.add(fixed(buildName(customer), 75) + " ".repeat(5));
        records.add(fixed(customer.getAddrLine1(), 50) + " ".repeat(30));
        records.add(fixed(customer.getAddrLine2(), 50) + " ".repeat(30));
        records.add(fixed(buildAddressCityLine(customer), TEXT_WIDTH));
        records.add(BORDER);
        records.add(TEXT_BASIC_DETAILS);
        records.add(BORDER);
        records.add(LABEL_ACCT + fixed(formatAccountId(account.getAcctId()), ACCT_ID_FIELD_WIDTH) + " ".repeat(40));
        records.add(LABEL_BAL + formatZoned(account.getCurrBal()) + " ".repeat(7) + " ".repeat(40));
        records.add(LABEL_FICO + fixed(formatFico(customer.getFicoCreditScore()), FICO_FIELD_WIDTH) + " ".repeat(40));
        records.add(BORDER);
        records.add(TEXT_TRAN_SUMMARY);
        records.add(BORDER);
        records.add(TEXT_COLUMN_HEADER);
        records.add(BORDER);
        for (StatementDto line : lines) {
            records.add(fixed(line.transactionId(), TRAN_ID_WIDTH)
                    + " "
                    + fixed(line.description(), TRAN_DESC_WIDTH)
                    + "$"
                    + formatSuppressed(line.amount()));
        }
        records.add(BORDER);
        records.add(LABEL_TOTAL + " ".repeat(56) + "$" + formatSuppressed(total));
        records.add(TEXT_LINE15);
        return joinFixed(records, TEXT_WIDTH);
    }

    /**
     * Renders the 100-column HTML statement, reproducing the write order of the
     * COBOL HTML paragraphs {@code 5100-WRITE-HTML-HEADER},
     * {@code 5200-WRITE-HTML-NMADBS}, the per-transaction rows of
     * {@code 6000-WRITE-TRANS}, and the closing rows of {@code 4000-TRNXFILE-GET}.
     * Every emitted line is exactly {@value #HTML_WIDTH} characters, matching
     * {@code FD-HTMLFILE-REC PIC X(100)}.
     *
     * @param customer the statement owner
     * @param account  the statement account
     * @param lines    the per-transaction reporting lines in {@code TRAN-ID} order
     * @param total    the running total of the card's transaction amounts
     * @return the rendered HTML statement as newline-joined fixed-width records
     */
    private String renderHtml(Customer customer, Account account,
                              List<StatementDto> lines, BigDecimal total) {
        String accountField = fixed(formatAccountId(account.getAcctId()), ACCT_ID_FIELD_WIDTH);
        List<String> records = new ArrayList<>();
        records.add(HTML_L01);
        records.add(HTML_L02);
        records.add(HTML_L03);
        records.add(HTML_L04);
        records.add(HTML_L05);
        records.add(HTML_L06);
        records.add(HTML_L07);
        records.add(HTML_L08);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_TITLE);
        records.add(HTML_ACCT_PREFIX + accountField + "</h3>");
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_BANK);
        records.add(HTML_BANK_NAME);
        records.add(HTML_BANK_ADDR1);
        records.add(HTML_BANK_ADDR2);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_GREY);
        records.add("<p style=\"font-size:16px\">" + cut(fixed(htmlEscape(buildName(customer)), 50), "  ") + "  </p>");
        records.add("<p>" + cut(fixed(htmlEscape(customer.getAddrLine1()), 50), "  ") + "  </p>");
        records.add("<p>" + cut(fixed(htmlEscape(customer.getAddrLine2()), 50), "  ") + "  </p>");
        records.add("<p>" + cut(fixed(htmlEscape(buildAddressCityLine(customer)), TEXT_WIDTH), "  ") + "  </p>");
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_SECTION);
        records.add(HTML_SECTION_BASIC);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_GREY);
        records.add(HTML_BASIC_ACCT + accountField + "</p>");
        records.add(HTML_BASIC_BAL + formatZoned(account.getCurrBal()) + "</p>");
        records.add(HTML_BASIC_FICO + fixed(formatFico(customer.getFicoCreditScore()), FICO_FIELD_WIDTH) + "</p>");
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_SECTION);
        records.add(HTML_SECTION_SUMMARY);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TR_OPEN);
        records.add(HTML_TH_ID);
        records.add(HTML_TH_ID_LABEL);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TH_DETAILS);
        records.add(HTML_TH_DETAILS_LABEL);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TH_AMOUNT);
        records.add(HTML_TH_AMOUNT_LABEL);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        for (StatementDto line : lines) {
            records.add(HTML_TR_OPEN);
            records.add(HTML_TD_ID);
            records.add("<p>" + fixed(htmlEscape(line.transactionId()), TRAN_ID_WIDTH) + "</p>");
            records.add(HTML_TD_CLOSE);
            records.add(HTML_TD_DETAILS);
            records.add("<p>" + fixed(htmlEscape(line.description()), TRAN_DESC_WIDTH) + "</p>");
            records.add(HTML_TD_CLOSE);
            records.add(HTML_TD_AMOUNT);
            records.add("<p>" + formatSuppressed(line.amount()) + "</p>");
            records.add(HTML_TD_CLOSE);
            records.add(HTML_TR_CLOSE);
        }
        records.add(HTML_TR_OPEN);
        records.add(HTML_TD_TITLE);
        records.add(HTML_END);
        records.add(HTML_TD_CLOSE);
        records.add(HTML_TR_CLOSE);
        records.add(HTML_TABLE_CLOSE);
        records.add(HTML_BODY_CLOSE);
        records.add(HTML_HTML_CLOSE);
        return joinFixed(records, HTML_WIDTH);
    }

    /**
     * Builds the customer name line, reproducing the COBOL {@code STRING ...
     * DELIMITED BY ' '} concatenation of first, middle, and last name in
     * {@code 5000-CREATE-STATEMENT}: each name contributes its text up to the
     * first embedded space, and a single space separates the parts with a
     * trailing space after the last name.
     *
     * @param customer the statement owner
     * @return the assembled name text prior to fixed-width placement
     */
    private static String buildName(Customer customer) {
        return cut(customer.getFirstName(), " ")
                + " " + cut(customer.getMiddleName(), " ")
                + " " + cut(customer.getLastName(), " ")
                + " ";
    }

    /**
     * Builds the third address line, reproducing the COBOL {@code STRING ...
     * DELIMITED BY ' '} concatenation of address line 3, state code, country
     * code, and ZIP in {@code 5000-CREATE-STATEMENT}.
     *
     * @param customer the statement owner
     * @return the assembled city/state/country/ZIP text prior to fixed-width placement
     */
    private static String buildAddressCityLine(Customer customer) {
        return cut(customer.getAddrLine3(), " ")
                + " " + cut(customer.getAddrStateCd(), " ")
                + " " + cut(customer.getAddrCountryCd(), " ")
                + " " + cut(customer.getAddrZip(), " ")
                + " ";
    }

    /**
     * Returns the portion of {@code value} preceding the first occurrence of
     * {@code delimiter}, reproducing the COBOL {@code STRING ... DELIMITED BY}
     * semantics. A {@code null} value yields an empty string; a value that does
     * not contain the delimiter is returned unchanged.
     *
     * @param value     the source text
     * @param delimiter the delimiter to stop at (a single or double space)
     * @return the delimited prefix
     */
    private static String cut(String value, String delimiter) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    /**
     * Left-justifies {@code value} into a fixed-width field, reproducing a COBOL
     * {@code MOVE ... TO PIC X(width)}: shorter values are right-padded with
     * spaces and longer values are truncated. A {@code null} value is treated as
     * an empty field.
     *
     * @param value the value to place
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String fixed(String value, int width) {
        String safe = value == null ? "" : value;
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        StringBuilder builder = new StringBuilder(width);
        builder.append(safe);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /**
     * Replaces the HTML metacharacters {@code & < > " '} in a dynamic value with
     * their character-entity references. The ampersand is replaced first so that
     * the entities introduced for the remaining characters are not re-encoded. A
     * {@code null} value yields an empty string, and a value containing none of
     * the metacharacters is returned unchanged.
     *
     * @param value the dynamic value to encode
     * @return the encoded value, never {@code null}
     */
    private static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&#39;");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Joins records into a single payload, normalising each to exactly
     * {@code width} characters (matching the fixed-length COBOL output record)
     * and terminating every record with a newline.
     *
     * @param records the rendered records in write order
     * @param width   the fixed record width
     * @return the newline-joined fixed-width payload
     */
    private static String joinFixed(List<String> records, int width) {
        StringBuilder builder = new StringBuilder(records.size() * (width + 1));
        for (String record : records) {
            builder.append(fixed(record, width)).append(NEWLINE);
        }
        return builder.toString();
    }

    /**
     * Removes trailing pad spaces from a fixed-width column value, returning
     * {@code null} unchanged.
     *
     * @param value the value to right-trim
     * @return the value without trailing spaces, or {@code null}
     */
    private static String rtrim(String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Formats the account identifier as the COBOL {@code ACCT-ID PIC 9(11)}
     * display value: eleven zero-filled digits. A {@code null} identifier renders
     * as all zeros.
     *
     * @param acctId the account identifier
     * @return the eleven-digit display string
     */
    private static String formatAccountId(Long acctId) {
        long value = acctId == null ? 0L : acctId;
        return String.format("%011d", value);
    }

    /**
     * Formats the FICO score as the COBOL {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}
     * display value: three zero-filled digits. A {@code null} score renders as
     * {@code "000"}.
     *
     * @param fico the FICO score
     * @return the three-digit display string
     */
    private static String formatFico(Integer fico) {
        int value = fico == null ? 0 : fico;
        return String.format("%03d", value);
    }

    /**
     * Applies the COBOL edit mask {@code PIC 9(9).99-}: nine zero-filled integer
     * digits, a decimal point, two fractional digits, and a trailing sign
     * position ({@code '-'} when negative, a space otherwise). The value is first
     * normalised to scale 2 with {@link RoundingMode#HALF_EVEN}.
     *
     * @param amount the monetary value to edit
     * @return the thirteen-character edited string
     */
    private static String formatZoned(BigDecimal amount) {
        return formatEdited(amount, false);
    }

    /**
     * Applies the COBOL edit mask {@code PIC Z(9).99-}: nine zero-suppressed
     * integer digits (leading zeros rendered as spaces), a decimal point, two
     * fractional digits, and a trailing sign position. The value is first
     * normalised to scale 2 with {@link RoundingMode#HALF_EVEN}.
     *
     * @param amount the monetary value to edit
     * @return the thirteen-character edited string
     */
    private static String formatSuppressed(BigDecimal amount) {
        return formatEdited(amount, true);
    }

    /**
     * Shared implementation of the COBOL numeric edit masks {@code 9(9).99-} and
     * {@code Z(9).99-}.
     *
     * @param amount   the monetary value to edit
     * @param suppress {@code true} for zero suppression ({@code Z}), {@code false}
     *                 for zero fill ({@code 9})
     * @return the thirteen-character edited string
     */
    private static String formatEdited(BigDecimal amount, boolean suppress) {
        BigDecimal scaled = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String integerDigits = plain.substring(0, dot);
        String fractionDigits = plain.substring(dot + 1);
        String integerField;
        if (suppress && integerDigits.equals("0")) {
            integerField = " ".repeat(EDIT_INTEGER_DIGITS);
        } else {
            integerField = padDigits(integerDigits, EDIT_INTEGER_DIGITS, suppress ? ' ' : '0');
        }
        return integerField + "." + fractionDigits + (negative ? "-" : " ");
    }

    /**
     * Right-justifies a digit string into a fixed-width integer field. When the
     * value already has more digits than {@code width}, the rightmost
     * {@code width} digits are retained (mirroring COBOL high-order truncation on
     * a {@code MOVE} into a shorter numeric edit field).
     *
     * @param digits the digit string (no sign)
     * @param width  the integer-field width
     * @param pad    the leading pad character ({@code '0'} or {@code ' '})
     * @return a string of exactly {@code width} characters
     */
    private static String padDigits(String digits, int width, char pad) {
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        StringBuilder builder = new StringBuilder(width);
        for (int i = digits.length(); i < width; i++) {
            builder.append(pad);
        }
        builder.append(digits);
        return builder.toString();
    }

    /**
     * Immutable carrier for the dual statement renderings produced for a single
     * card, returned by {@link StatementProcessor#process(CardXref)} for the
     * downstream writer(s).
     *
     * <p>{@code text} is the fully rendered 80-column plain-text statement
     * ({@code FD-STMTFILE-REC PIC X(80)}); {@code html} is the fully rendered
     * 100-column HTML statement ({@code FD-HTMLFILE-REC PIC X(100)}); both are
     * newline-joined fixed-width records. {@code total} is the running total of
     * the card's transaction amounts as a scale-2 {@link BigDecimal}.</p>
     *
     * @param text  the rendered text statement payload
     * @param html  the rendered HTML statement payload
     * @param total the card's transaction total (scale 2)
     */
    public record StatementBundle(String text, String html, BigDecimal total) {
    }
}
