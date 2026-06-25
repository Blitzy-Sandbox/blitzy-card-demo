/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.exception;

/**
 * Signals that a syntactically valid request violates a domain business rule.
 *
 * <p>Representative violations include the transaction-posting cascade
 * rejections (for example an over-limit transaction or a transaction received
 * after account expiration) and bill-payment rules (for example an account
 * with no outstanding balance to pay). The detail message is always supplied
 * by the caller so that each rule preserves its own description verbatim, and
 * an optional {@link #getRuleCode() rule code} can carry the originating
 * reason code for traceability.
 *
 * <p>The exception extends {@link java.lang.RuntimeException} directly and
 * carries no dependency on any persistence, web, or application package. The
 * {@code ruleCode} context field is nullable and is stored as a
 * {@link String} so that instances remain fully serializable.
 */
public class BusinessRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Code identifying the violated business rule (for example the
     * transaction-posting reason code {@code "102"} or {@code "103"}, or a
     * symbolic rule identifier); {@code null} when not specified.
     */
    private final String ruleCode;

    /**
     * Creates an exception with the supplied detail message and no rule code.
     *
     * @param message the detail message describing the violated rule
     */
    public BusinessRuleException(String message) {
        super(message);
        this.ruleCode = null;
    }

    /**
     * Creates an exception with the supplied detail message and cause and no
     * rule code.
     *
     * @param message the detail message describing the violated rule
     * @param cause   the underlying cause, which may be {@code null}
     */
    public BusinessRuleException(String message, Throwable cause) {
        super(message, cause);
        this.ruleCode = null;
    }

    /**
     * Creates an exception that records the violated rule code alongside the
     * detail message.
     *
     * @param ruleCode the code identifying the violated business rule (for
     *                 example {@code "102"} or {@code "103"}); may be
     *                 {@code null}
     * @param message  the detail message describing the violated rule
     */
    public BusinessRuleException(String ruleCode, String message) {
        super(message);
        this.ruleCode = ruleCode;
    }

    /**
     * Returns the code identifying the violated business rule, if known.
     *
     * @return the rule code, or {@code null} when not specified
     */
    public String getRuleCode() {
        return ruleCode;
    }
}
