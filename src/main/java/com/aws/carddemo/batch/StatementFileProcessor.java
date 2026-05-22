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

import java.util.List;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBSTM03B.cbl} (230
 * lines) — the statement-file orchestrator.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.cbl} orchestrates the per-customer
 * statement generation: open the customer/transaction inputs, drive
 * the {@link StatementProcessor} for each customer, write the
 * dual-output streams (plain-text + HTML), and close the outputs.
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>This class delegates statement formatting to {@link StatementProcessor}
 * and adds the file-orchestration semantics: process one customer's
 * statements via {@link #process(Customer, List)}, returning a
 * {@link StatementBundle} carrying both the text and HTML outputs.
 *
 * @see com.aws.carddemo.batch.StatementFileProcessorTest
 */
public class StatementFileProcessor {

    private final StatementProcessor delegate;

    /**
     * Constructs a new file processor backed by the given
     * {@link StatementProcessor}.
     *
     * @param delegate the statement formatter; must not be null
     */
    public StatementFileProcessor(StatementProcessor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Convenience constructor that wires a fresh {@link StatementProcessor}
     * — used by Spring when no explicit bean is provided.
     */
    public StatementFileProcessor() {
        this(new StatementProcessor());
    }

    /**
     * Process one customer's statements — null-customer returns null
     * (EOF/skip semantics); a non-null customer with its transactions
     * returns a {@link StatementBundle} carrying both the text and
     * HTML outputs.
     *
     * @param customer     the customer to bill; null for EOF
     * @param transactions the transactions in the billing period; must not be null
     *                     when {@code customer} is non-null
     * @return a {@link StatementBundle}, or {@code null} if {@code customer} is null
     */
    public StatementBundle process(Customer customer, List<Transaction> transactions) {
        if (customer == null) {
            return null;
        }
        Objects.requireNonNull(transactions, "transactions must not be null");
        String text = delegate.buildTextStatement(customer, transactions);
        String html = delegate.buildHtmlStatement(customer, transactions);
        return new StatementBundle(customer.getCustomerId(), text, html);
    }

    /**
     * Immutable value object carrying both output formats of one
     * customer's statement.
     */
    public static final class StatementBundle {
        private final String customerId;
        private final String text;
        private final String html;

        /** Constructs a new bundle. */
        public StatementBundle(String customerId, String text, String html) {
            this.customerId = customerId;
            this.text = Objects.requireNonNull(text, "text must not be null");
            this.html = Objects.requireNonNull(html, "html must not be null");
        }

        /** @return the customer ID (may be null if customer had no ID) */
        public String getCustomerId() { return customerId; }

        /** @return the plain-text statement */
        public String getText() { return text; }

        /** @return the HTML statement */
        public String getHtml() { return html; }
    }
}
