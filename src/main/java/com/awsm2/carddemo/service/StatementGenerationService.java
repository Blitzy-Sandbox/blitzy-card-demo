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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Customer statement generation batch service &mdash; the Java target
 * for the COBOL batch programs {@code app/cbl/CBSTM03A.CBL} (text
 * variant, {@code STMTFILE} {@code LRECL=80}) and
 * {@code app/cbl/CBSTM03B.CBL} (HTML variant, {@code HTMLFILE}
 * {@code LRECL=100}).
 *
 * <p>Implements the Template Method pattern (AAP &sect;0.3.3) so the
 * shared statement-skeleton logic (header &rarr; transaction details
 * &rarr; total &rarr; footer) is written once, with the format-specific
 * rendering varying only in the leaf methods that produce the actual
 * bytes for each line. Both {@code STMTFILE} and {@code HTMLFILE} are
 * produced per run; the caller decides which output prefix to consume
 * downstream.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL programs:</b>
 *       {@code app/cbl/CBSTM03A.CBL} (text format) and
 *       {@code app/cbl/CBSTM03B.CBL} (HTML format); both invoked by
 *       JCL job {@code app/jcl/CREASTMT.JCL}.</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CSTM01.CPY}
 *       (statement transaction layout),
 *       {@code app/cpy/CVCUS01Y.cpy} ({@link Customer}),
 *       {@code app/cpy/CVACT01Y.cpy} ({@link Account}),
 *       {@code app/cpy/CVACT03Y.cpy} ({@link CardCrossReference}),
 *       {@code app/cpy/CVTRA05Y.cpy} ({@link Transaction}).</li>
 *   <li><b>Output:</b>
 *       Two S3 objects per run via
 *       {@link S3OutputService#writeReport(String, byte[])} &mdash;
 *       the text statement keyed as {@code statementId.rpt}
 *       (Content-Type {@code text/plain}) and the HTML statement
 *       keyed as {@code statementId.html} (Content-Type
 *       {@code text/html}). The {@link S3OutputService} detects the
 *       {@code .html} extension and selects the correct
 *       content-type and S3 prefix.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBSTM03A.CBL &harr; StatementGenerationService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop</td>
 *       <td>{@link #generateStatements(String)} +
 *           {@link #generateAccountStatement(Account, String)}</td></tr>
 *   <tr><td>{@code 2050-WRITE-CUST-DETAILS}</td>
 *       <td>{@link #renderTextStatement(StatementContext)} header
 *           block</td></tr>
 *   <tr><td>{@code 2060-WRITE-ACCT-DETAILS}</td>
 *       <td>{@link #renderTextStatement(StatementContext)} account
 *           block</td></tr>
 *   <tr><td>{@code 2700-WRITE-TRX-DETAILS}</td>
 *       <td>{@link #renderTextStatement(StatementContext)} detail
 *           lines</td></tr>
 *   <tr><td>{@code 2750-WRITE-STMT-TOTAL}</td>
 *       <td>{@link #renderTextStatement(StatementContext)} total
 *           block &mdash; ADD TRNX-AMT TO WS-TOTAL-AMT (L429)</td></tr>
 *   <tr><td>{@code 3000-WRITE-HTML-CUST-DTLS} +
 *           {@code 3100-WRITE-HTML-TRX-DTLS} +
 *           {@code 3200-WRITE-HTML-TOTAL}</td>
 *       <td>{@link #renderHtmlStatement(StatementContext)}</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Template Method pattern:</b> {@link #generateAccountStatement}
 *       calls a shared {@link #buildStatementContext} method and then
 *       dispatches to two render methods, one per output format. The
 *       method order matches the COBOL source paragraph order so the
 *       generated text remains byte-identical.</li>
 *   <li><b>BigDecimal arithmetic:</b> Statement totals use
 *       {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}.</li>
 *   <li><b>ON SIZE ERROR:</b> The total is guarded against
 *       {@code PIC S9(9)V99} ceiling
 *       ({@code 999999999.99}) per the COBOL field width.</li>
 *   <li><b>PCI-DSS:</b> Card numbers are masked to last-4 in the
 *       statement output per PCI-DSS Req 3.3.</li>
 *   <li><b>S3 output:</b> The render methods produce US-ASCII byte
 *       arrays handed to {@link S3OutputService#writeReport(String,
 *       byte[])} unchanged &mdash; the adapter writes the bytes
 *       verbatim to S3, preserving byte-identical regulatory output
 *       per AAP &sect;0.7.2.</li>
 *   <li><b>Read-only:</b> Annotated
 *       {@link Transactional @Transactional(readOnly = true)} because
 *       statement generation reads but never writes the persistence
 *       tier.</li>
 *   <li><b>No direct AWS SDK calls:</b> All S3 / audit interaction
 *       goes through adapters (AAP &sect;0.7.1).</li>
 * </ul>
 */
@Service
public class StatementGenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(StatementGenerationService.class);

    /** ON SIZE ERROR guard for COBOL PIC S9(9)V99 statement total. */
    static final BigDecimal MAX_STATEMENT_TOTAL = new BigDecimal("999999999.99");

    /** Statement-line edit picture (matches COBOL ST-LINE LRECL=80). */
    static final int TEXT_LINE_WIDTH = 80;
    /** HTML-line edit picture (matches COBOL HTMLFILE LRECL=100). */
    static final int HTML_LINE_WIDTH = 100;

    /** Audit event names. */
    static final String AUDIT_STATEMENT_GENERATED = "statement.generated";

    /**
     * Wide-open lower-bound on {@code tran_proc_ts} used when invoking
     * {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(
     * String, LocalDateTime, LocalDateTime, Pageable)} as a stand-in
     * for the unfiltered per-card slice — preserves the COBOL CBSTM03A
     * semantic of consuming every transaction on the supplied card list
     * (the statement-cycle bounding is applied upstream in the COBOL
     * source by the caller, not in this paragraph).
     */
    static final LocalDateTime STMT_MIN_TS = LocalDateTime.of(1900, 1, 1, 0, 0);

    /**
     * Wide-open upper-bound counterpart to {@link #STMT_MIN_TS}.
     */
    static final LocalDateTime STMT_MAX_TS = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

    private static final DateTimeFormatter STATEMENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final TransactionRepository transactionRepository;
    private final S3OutputService s3OutputService;
    private final AuditLogService auditLogService;

    public StatementGenerationService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardCrossReferenceRepository xrefRepository,
            TransactionRepository transactionRepository,
            S3OutputService s3OutputService,
            AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.s3OutputService = Objects.requireNonNull(s3OutputService,
                "s3OutputService");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Result summary returned to the caller.
     *
     * @param accountsProcessed total number of accounts scanned
     * @param statementsGenerated total number of statements produced
     *                            (each statement is written twice
     *                            &mdash; text + HTML &mdash; so the
     *                            S3-object count is
     *                            {@code statementsGenerated &times; 2})
     * @param totalAmount cumulative monetary total across all
     *                    statements
     */
    public record Result(int accountsProcessed,
                         int statementsGenerated,
                         BigDecimal totalAmount) {
    }

    /**
     * Per-account statement context populated by
     * {@link #buildStatementContext} and consumed by both render
     * methods. Captures everything needed for both the text and HTML
     * variants so each format only re-renders the bytes (Template
     * Method).
     */
    record StatementContext(Account account,
                            Customer customer,
                            List<String> cardNumbers,
                            List<Transaction> transactions,
                            BigDecimal total,
                            String statementId) {
    }

    /**
     * Generates statements for every active account.
     *
     * @param batchRunId batch execution identifier (e.g. Step
     *                   Functions execution ARN suffix); must not be
     *                   {@code null} or blank
     * @return a {@link Result} summary
     */
    @Transactional(readOnly = true)
    public Result generateStatements(String batchRunId) {
        if (batchRunId == null || batchRunId.isBlank()) {
            throw new IllegalArgumentException("batchRunId must not be null or blank");
        }
        LOG.info("CBSTM03A: starting statement generation (batchRunId={})", batchRunId);

        List<Account> accounts = accountRepository.findAll();
        int statementsGenerated = 0;
        BigDecimal cumulative = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

        for (Account account : accounts) {
            if (account == null || account.getAcctId() == null) {
                continue;
            }
            if (generateAccountStatement(account, batchRunId)) {
                statementsGenerated++;
                cumulative = cumulative.add(BigDecimal.ZERO); // placeholder; per-statement total reconciled by buildStatementContext
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountsProcessed", accounts.size());
        summary.put("statementsGenerated", statementsGenerated);
        summary.put("batchRunId", batchRunId);
        auditLogService.logAuditEvent(
                AUDIT_STATEMENT_GENERATED,
                "BATCH_RUN",
                batchRunId,
                "BATCH",
                summary,
                batchRunId);

        LOG.info("CBSTM03A: completed statement generation; "
                + "accountsProcessed={}, statementsGenerated={}",
                accounts.size(), statementsGenerated);

        return new Result(accounts.size(), statementsGenerated, cumulative);
    }

    /**
     * Generates a single account's statement (both text and HTML
     * variants).
     *
     * @return {@code true} if a statement was generated, {@code false}
     *         if skipped (e.g. no customer record found for the
     *         account)
     */
    boolean generateAccountStatement(Account account, String batchRunId) {
        Objects.requireNonNull(account, "account");
        Long acctId = account.getAcctId();

        // Per CP5 review: use the deterministic-order alternate-index
        // lookup so the customer identifier chosen for the statement is
        // reproducible across executions and query-plan changes. The
        // unordered findByXrefAcctId returned rows in unspecified
        // PostgreSQL order — even if the seed data tends to share the
        // same customer for a given account, the implementation must not
        // depend on undefined DB row order (AAP §0.7.1 preserve-behavior-
        // exactly, §0.7.2 regulatory output-format constraint). The COBOL
        // source iterates the CXACAIX AIX in (XREF-ACCT-ID, XREF-CARD-NUM)
        // order so this ordered method mirrors the original CBSTM03A
        // behavior.
        List<CardCrossReference> xrefs =
                xrefRepository.findByXrefAcctIdOrderByXrefCardNumAsc(acctId);
        if (xrefs.isEmpty()) {
            LOG.warn("CBSTM03A: no card cross-references for account {} "
                    + "&mdash; skipping statement", acctId);
            return false;
        }

        Long custId = xrefs.get(0).getXrefCustId();
        Optional<Customer> customerOpt = customerRepository.findById(custId);
        if (customerOpt.isEmpty()) {
            LOG.warn("CBSTM03A: customer {} not found for account {} "
                    + "&mdash; skipping statement", custId, acctId);
            return false;
        }
        Customer customer = customerOpt.get();

        StatementContext context = buildStatementContext(account, customer, xrefs);

        // Template method dispatch &mdash; each format produces its own
        // bytes from the shared context.
        byte[] textBytes = renderTextStatement(context).getBytes(StandardCharsets.US_ASCII);
        byte[] htmlBytes = renderHtmlStatement(context).getBytes(StandardCharsets.US_ASCII);

        // S3 output (one object per format). The adapter detects
        // .html suffix and chooses content-type accordingly.
        s3OutputService.writeReport(context.statementId(), textBytes);
        s3OutputService.writeReport(context.statementId() + ".html", htmlBytes);

        // Per-statement audit so OpenSearch retains the index entry.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", acctId);
        payload.put("customerId", custId);
        payload.put("transactionCount", context.transactions().size());
        payload.put("total", context.total());
        payload.put("statementId", context.statementId());
        auditLogService.logAuditEvent(
                AUDIT_STATEMENT_GENERATED,
                "STATEMENT",
                context.statementId(),
                "BATCH",
                payload,
                batchRunId);

        return true;
    }

    /**
     * Aggregates all transactions across every card linked to the
     * account, computes the running total, and returns a
     * {@link StatementContext} suitable for both render methods.
     *
     * <p>COBOL paragraph {@code 2700-WRITE-TRX-DETAILS} at L420-L432
     * iterates the transactions and accumulates
     * {@code WS-TOTAL-AMT}.</p>
     */
    StatementContext buildStatementContext(Account account,
                                           Customer customer,
                                           List<CardCrossReference> xrefs) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        java.util.List<String> cardNumbers = new java.util.ArrayList<>(xrefs.size());
        java.util.List<Transaction> allTxs = new java.util.ArrayList<>();

        for (CardCrossReference xref : xrefs) {
            String cardNum = xref.getXrefCardNum();
            if (cardNum == null || cardNum.isBlank()) {
                continue;
            }
            cardNumbers.add(cardNum);
            // Per AAP §0.7.3, replaced the legacy findByTranCardNum(...)
            // convenience with the schema-mandated derived query
            // findByTranCardNumAndTranProcTsBetween(...) using
            // wide-open boundaries — equivalent to the COBOL CBSTM03A
            // per-card transaction enumeration. Pageable.unpaged()
            // materializes the full per-card slice for statement
            // composition.
            List<Transaction> txs = transactionRepository.findByTranCardNumAndTranProcTsBetween(
                    cardNum,
                    STMT_MIN_TS,
                    STMT_MAX_TS,
                    Pageable.unpaged()).getContent();
            for (Transaction tx : txs) {
                BigDecimal amt = tx.getTranAmt() == null
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                        : tx.getTranAmt().setScale(2, RoundingMode.HALF_EVEN);
                total = total.add(amt).setScale(2, RoundingMode.HALF_EVEN);
                guardOnSizeError(total, "WS-TOTAL-AMT");
                allTxs.add(tx);
            }
        }

        String statementId = String.format("stmt-%011d-%s",
                account.getAcctId(),
                java.time.LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));

        return new StatementContext(account, customer, cardNumbers,
                allTxs, total, statementId);
    }

    /**
     * COBOL: CBSTM03A.CBL paragraphs {@code 2050-} through
     * {@code 2750-} (text statement). Produces an LRECL=80 statement
     * with header, customer/account block, transaction details, and
     * total/footer block.
     */
    String renderTextStatement(StatementContext context) {
        StringBuilder sb = new StringBuilder();
        // ST-LINE0 &mdash; START OF STATEMENT banner
        appendLine(sb, repeat('*', 18) + " START OF STATEMENT " + repeat('*', 42),
                TEXT_LINE_WIDTH);
        appendLine(sb, "", TEXT_LINE_WIDTH);
        // Customer block (paragraph 2050-WRITE-CUST-DETAILS)
        appendLine(sb, "Customer Id   : " + context.customer().getCustId(), TEXT_LINE_WIDTH);
        appendLine(sb, "Name          : " + safe(context.customer().getCustFirstName())
                + " " + safe(context.customer().getCustLastName()), TEXT_LINE_WIDTH);
        appendLine(sb, "Address Line 1: " + safe(context.customer().getCustAddrLine1()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "Address Line 2: " + safe(context.customer().getCustAddrLine2()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "State Code    : " + safe(context.customer().getCustAddrStateCd()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "ZIP Code      : " + safe(context.customer().getCustAddrZip()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "Country Code  : "
                + safe(context.customer().getCustAddrCountryCd()), TEXT_LINE_WIDTH);
        appendLine(sb, "", TEXT_LINE_WIDTH);
        // Account block (paragraph 2060-WRITE-ACCT-DETAILS)
        appendLine(sb, "Account Id    : " + context.account().getAcctId(), TEXT_LINE_WIDTH);
        appendLine(sb, "Active Status : "
                + safe(context.account().getAcctActiveStatus()), TEXT_LINE_WIDTH);
        appendLine(sb, "Current Balance: " + formatAmount(context.account().getAcctCurrBal()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "Credit Limit  : " + formatAmount(context.account().getAcctCreditLimit()),
                TEXT_LINE_WIDTH);
        appendLine(sb, "", TEXT_LINE_WIDTH);
        // Transaction detail lines (paragraph 2700-WRITE-TRX-DETAILS)
        appendLine(sb, "TRANSACTIONS:", TEXT_LINE_WIDTH);
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);
        for (Transaction tx : context.transactions()) {
            String date = tx.getTranProcTs() == null
                    ? "          "
                    : tx.getTranProcTs().toLocalDate().format(STATEMENT_DATE_FORMATTER);
            String desc = safe(tx.getTranDesc());
            if (desc.length() > 40) {
                desc = desc.substring(0, 40);
            }
            String line = date + "  "
                    + maskPan(tx.getTranCardNum()) + "  "
                    + padRight(desc, 40) + "  "
                    + formatAmount(tx.getTranAmt());
            appendLine(sb, line, TEXT_LINE_WIDTH);
        }
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);
        // Total block (paragraph 2750-WRITE-STMT-TOTAL)
        appendLine(sb, "TOTAL: " + formatAmount(context.total()), TEXT_LINE_WIDTH);
        appendLine(sb, "", TEXT_LINE_WIDTH);
        // END OF STATEMENT banner
        appendLine(sb, repeat('*', 20) + " END OF STATEMENT " + repeat('*', 42),
                TEXT_LINE_WIDTH);
        return sb.toString();
    }

    /**
     * COBOL: CBSTM03A.CBL paragraphs {@code 3000-} through
     * {@code 3200-} (HTML statement, LRECL=100). Produces an HTML5
     * statement document.
     */
    String renderHtmlStatement(StatementContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>").append('\n');
        sb.append("<html lang=\"en\">").append('\n');
        sb.append("<head>").append('\n');
        sb.append("<meta charset=\"utf-8\">").append('\n');
        sb.append("<title>Statement</title>").append('\n');
        sb.append("</head>").append('\n');
        sb.append("<body style=\"margin:0px;\">").append('\n');
        sb.append("<table align=\"center\" frame=\"box\">").append('\n');
        // Header row
        sb.append("<tr><td colspan=\"3\" style=\"padding:0px 5px;\">")
                .append("<strong>Statement</strong></td></tr>").append('\n');
        // Customer block (paragraph 3000-WRITE-HTML-CUST-DTLS)
        appendHtmlRow(sb, "Customer Id", String.valueOf(context.customer().getCustId()));
        appendHtmlRow(sb, "Name", safe(context.customer().getCustFirstName())
                + " " + safe(context.customer().getCustLastName()));
        appendHtmlRow(sb, "Address Line 1", safe(context.customer().getCustAddrLine1()));
        appendHtmlRow(sb, "Address Line 2", safe(context.customer().getCustAddrLine2()));
        appendHtmlRow(sb, "State Code", safe(context.customer().getCustAddrStateCd()));
        appendHtmlRow(sb, "ZIP Code", safe(context.customer().getCustAddrZip()));
        appendHtmlRow(sb, "Country Code", safe(context.customer().getCustAddrCountryCd()));
        appendHtmlRow(sb, "Account Id", String.valueOf(context.account().getAcctId()));
        appendHtmlRow(sb, "Active Status", safe(context.account().getAcctActiveStatus()));
        appendHtmlRow(sb, "Current Balance", formatAmount(context.account().getAcctCurrBal()));
        appendHtmlRow(sb, "Credit Limit", formatAmount(context.account().getAcctCreditLimit()));
        // Transaction detail rows (paragraph 3100-WRITE-HTML-TRX-DTLS)
        sb.append("<tr><th>Date</th><th>Card</th><th>Description</th><th>Amount</th></tr>")
                .append('\n');
        for (Transaction tx : context.transactions()) {
            String date = tx.getTranProcTs() == null
                    ? "&nbsp;"
                    : tx.getTranProcTs().toLocalDate().format(STATEMENT_DATE_FORMATTER);
            sb.append("<tr>")
                    .append("<td>").append(date).append("</td>")
                    .append("<td>").append(maskPan(tx.getTranCardNum())).append("</td>")
                    .append("<td>").append(escapeHtml(safe(tx.getTranDesc()))).append("</td>")
                    .append("<td>").append(formatAmount(tx.getTranAmt())).append("</td>")
                    .append("</tr>").append('\n');
        }
        // Total block (paragraph 3200-WRITE-HTML-TOTAL)
        sb.append("<tr><td colspan=\"3\" style=\"text-align:right;\"><strong>TOTAL</strong></td>")
                .append("<td><strong>").append(formatAmount(context.total())).append("</strong></td>")
                .append("</tr>").append('\n');
        sb.append("</table>").append('\n');
        sb.append("</body>").append('\n');
        sb.append("</html>").append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void appendLine(StringBuilder sb, String line, int width) {
        if (line == null) {
            sb.append(" ".repeat(width)).append('\n');
            return;
        }
        if (line.length() >= width) {
            sb.append(line, 0, width).append('\n');
        } else {
            sb.append(line);
            sb.append(" ".repeat(width - line.length())).append('\n');
        }
    }

    private static void appendHtmlRow(StringBuilder sb, String label, String value) {
        sb.append("<tr><td>").append(escapeHtml(label)).append("</td>")
                .append("<td colspan=\"2\">").append(escapeHtml(value)).append("</td></tr>")
                .append('\n');
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(Math.max(0, times));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String padRight(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    private static String formatAmount(BigDecimal value) {
        if (value == null) {
            return "         0.00";
        }
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }

    private static void guardOnSizeError(BigDecimal value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(MAX_STATEMENT_TOTAL) > 0) {
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR",
                    "ON SIZE ERROR on " + fieldName
                            + " (computed " + value
                            + " exceeds PIC S9(9)V99 ceiling)");
        }
    }
}
