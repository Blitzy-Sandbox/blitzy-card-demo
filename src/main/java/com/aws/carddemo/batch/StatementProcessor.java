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
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBSTM03A.cbl} (924
 * lines) — the customer statement formatter.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.cbl} aggregates transactions by customer
 * for a billing period and emits both a plain-text statement and an
 * HTML statement to the output streams. Each statement carries:
 * <ul>
 *   <li>Customer header (name, address, account ID).</li>
 *   <li>Itemised transaction list for the billing period.</li>
 *   <li>Statement total (sum of transaction amounts).</li>
 *   <li>Footer (regulatory disclosures).</li>
 * </ul>
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>This class exposes pure-function seams for the unit tests:
 * <ul>
 *   <li>{@link #buildTextStatement(Customer, List)} — produces the
 *       plain-text statement string.</li>
 *   <li>{@link #buildHtmlStatement(Customer, List)} — produces the
 *       HTML statement string with proper escaping.</li>
 *   <li>{@link #computeTotal(List)} — sums transaction amounts at
 *       scale 2 HALF_EVEN.</li>
 * </ul>
 *
 * @see com.aws.carddemo.batch.StatementProcessorTest
 */
public class StatementProcessor {

    /** Constructs a new processor. */
    public StatementProcessor() {
        // No collaborators.
    }

    /**
     * Build the plain-text statement for one customer.
     *
     * @param customer     the customer; must not be null
     * @param transactions the transactions in the billing period; must not be null (may be empty)
     * @return the statement as a multi-line plain-text string
     */
    public String buildTextStatement(Customer customer, List<Transaction> transactions) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(transactions, "transactions must not be null");

        StringBuilder sb = new StringBuilder(2048);
        sb.append("CUSTOMER STATEMENT\n");
        sb.append("==================\n");
        sb.append("Customer ID: ").append(nullSafe(customer.getCustomerId())).append('\n');
        sb.append("Name       : ").append(nullSafe(customer.getFirstName())).append(' ')
                .append(nullSafe(customer.getLastName())).append('\n');
        sb.append("Address    : ").append(nullSafe(customer.getAddressLine1())).append('\n');
        if (customer.getAddressLine2() != null && !customer.getAddressLine2().isEmpty()) {
            sb.append("             ").append(customer.getAddressLine2()).append('\n');
        }
        sb.append("             ").append(nullSafe(customer.getAddressStateCode()))
                .append(' ').append(nullSafe(customer.getAddressZip())).append('\n');
        sb.append("\nTransactions:\n");
        sb.append("-----------------------------------------------\n");

        for (Transaction t : transactions) {
            sb.append(formatTransactionLine(t)).append('\n');
        }

        sb.append("-----------------------------------------------\n");
        BigDecimal total = computeTotal(transactions);
        sb.append("Total: ").append(total.toPlainString()).append('\n');
        return sb.toString();
    }

    /**
     * Build the HTML statement for one customer with proper escaping
     * of customer-supplied fields (defence against an attacker
     * controlling the customer name/address).
     *
     * @param customer     the customer; must not be null
     * @param transactions the transactions; must not be null (may be empty)
     * @return the HTML statement
     */
    public String buildHtmlStatement(Customer customer, List<Transaction> transactions) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(transactions, "transactions must not be null");

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<html><body>\n");
        sb.append("<h1>Customer Statement</h1>\n");
        sb.append("<p><strong>Customer ID:</strong> ").append(escapeHtml(customer.getCustomerId())).append("</p>\n");
        sb.append("<p><strong>Name:</strong> ").append(escapeHtml(customer.getFirstName()))
                .append(' ').append(escapeHtml(customer.getLastName())).append("</p>\n");
        sb.append("<p><strong>Address:</strong> ").append(escapeHtml(customer.getAddressLine1())).append("</p>\n");

        sb.append("<table border=\"1\">\n");
        sb.append("<tr><th>TXN-ID</th><th>TYPE</th><th>CAT</th><th>AMOUNT</th><th>DATE</th></tr>\n");
        for (Transaction t : transactions) {
            sb.append("<tr>")
                    .append("<td>").append(escapeHtml(t.getTransactionId())).append("</td>")
                    .append("<td>").append(escapeHtml(t.getTransactionTypeCode())).append("</td>")
                    .append("<td>").append(escapeHtml(t.getTransactionCategoryCode())).append("</td>")
                    .append("<td>").append(formatAmount(t.getAmount())).append("</td>")
                    .append("<td>").append(escapeHtml(extractDate(t.getOriginTimestamp()))).append("</td>")
                    .append("</tr>\n");
        }
        sb.append("</table>\n");

        BigDecimal total = computeTotal(transactions);
        sb.append("<p><strong>Total:</strong> ").append(total.toPlainString()).append("</p>\n");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Compute the sum of transaction amounts at scale 2 HALF_EVEN.
     *
     * @param transactions the list to sum; must not be null
     * @return the total at scale 2; zero for an empty list
     */
    public BigDecimal computeTotal(List<Transaction> transactions) {
        Objects.requireNonNull(transactions, "transactions must not be null");
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        for (Transaction t : transactions) {
            if (t.getAmount() != null) {
                total = total.add(t.getAmount()).setScale(2, RoundingMode.HALF_EVEN);
            }
        }
        return total;
    }

    /**
     * Format one transaction as a single-line plain-text record.
     *
     * @param t the transaction; must not be null
     * @return the formatted line (no trailing newline)
     */
    public String formatTransactionLine(Transaction t) {
        Objects.requireNonNull(t, "transaction must not be null");
        return nullSafe(t.getTransactionId()) + " "
                + nullSafe(t.getTransactionTypeCode()) + " "
                + nullSafe(t.getTransactionCategoryCode()) + " "
                + formatAmount(t.getAmount()) + " "
                + extractDate(t.getOriginTimestamp());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String extractDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return "";
        }
        return timestamp.substring(0, 10);
    }

    /**
     * HTML-escape a string for safe inclusion in an HTML attribute or
     * text node. Null returns the empty string.
     *
     * <p>Escapes {@code &}, {@code <}, {@code >}, {@code "}, {@code '}.
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
