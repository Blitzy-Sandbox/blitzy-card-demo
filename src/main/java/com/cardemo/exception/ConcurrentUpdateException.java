/*
 * ******************************************************************
 * Program     : ConcurrentUpdateException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Optimistic-concurrency and record-lock outcome of COACTUPC 9700-CHECK-CHANGE-IN-REC.
 * Source      : app/cbl/COACTUPC.cbl:L517-L524 @ 7756d89 - the four outcome
 *               flags and the verbatim screen message literal each carries.
 * Source      : app/cbl/COACTUPC.cbl:L654-L668 @ 7756d89 - the fifth marker
 *               ACUP-CHANGE-ACTION and its ten condition names.
 * Source      : app/cbl/COACTUPC.cbl:L2606-L2615 @ 7756d89 - the EVALUATE TRUE
 *               dispatch that never tests the customer lock flag, so a customer
 *               lock failure falls through WHEN OTHER and is reported to the
 *               user as success; reproduced rather than repaired.
 * Source      : app/cbl/COACTUPC.cbl:L3907-L3915,L3934-L3942 @ 7756d89 - the two
 *               read for update guards, each setting its lock flag only while no
 *               message has already been staged.
 * Source      : app/cbl/COACTUPC.cbl:L4076-L4081,L4095-L4102 @ 7756d89 - the
 *               asymmetric rollback, where both rewrite failures set the same
 *               flag and only the second issues SYNCPOINT ROLLBACK.
 * Source      : app/cbl/COCRDUPC.cbl @ 7756d89 - the mirrored card update change
 *               detection pattern, whose single lock flag is tested at L993.
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.exception;

/**
 * Signals that an account update was abandoned without being applied, because the record could not be locked,
 * had changed since it was shown to the user, or had not yet been confirmed.
 *
 * <p>It is the typed outcome of {@code 9600-WRITE-PROCESSING} and the change detection paragraph it performs,
 * {@code 9700-CHECK-CHANGE-IN-REC}, in the largest program of the frozen corpus. That program signals five
 * distinct abandonment reasons through four condition names on a screen message field plus one value of a
 * change action marker. This class turns those five reasons into five individually representable outcomes so
 * that a caller can tell them apart, which no single generic conflict type could do.
 *
 * <p>It carries no comparison logic, no retry, no merge and no resolution strategy. It is <em>vocabulary</em>.
 * The field-by-field comparison against the snapshot, the staged precedence of the screen messages, and the
 * asymmetric rollback of {@code app/cbl/COACTUPC.cbl:4079-4102} all belong to
 * {@code com.cardemo.service.account.AccountUpdateService}; the choice of HTTP status belongs to the
 * controller. Three concerns, deliberately split.
 *
 * @see CardDemoException
 */
public class ConcurrentUpdateException extends CardDemoException {

    /**
     * Fixed serialization identity, on the contract described on {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The five reasons an account update is abandoned, each mapped to the legacy construct it represents.
     */
    public enum Outcome {

        /**
         * The account record could not be locked for update, so nothing was attempted.
         */
        COULD_NOT_LOCK_ACCOUNT("Could not lock account record for update", 'L'),

        /**
         * The customer record could not be locked for update, so nothing was attempted.
         */
        COULD_NOT_LOCK_CUSTOMER("Could not lock customer record for update", 'C'),

        /**
         * The record changed between the read that populated the screen and the write that would have committed
         * it, so the update was abandoned and the current values are redisplayed.
         */
        DATA_CHANGED_BEFORE_UPDATE("Record changed by some one else. Please review", 'S'),

        /**
         * Both records were locked and the snapshot matched, but a rewrite failed.
         */
        LOCKED_BUT_UPDATE_FAILED("Update of record failed", 'F'),

        /**
         * The edited values were acceptable but the change was not confirmed, so no write was attempted.
         */
        CHANGES_NOT_CONFIRMED("", 'N');

        /**
         * The verbatim legacy screen message literal, reproduced byte for byte from the source.
         */
        private final String legacyMessage;

        /**
         * The {@code ACUP-CHANGE-ACTION} value the legacy dispatch writes for this outcome.
         */
        private final char changeActionCode;

        /**
         * Binds an outcome to the legacy text and marker measured for it.
         *
         * @param legacyMessage the verbatim message literal from {@code app/cbl/COACTUPC.cbl:L517-L524}, or an
         * empty string for the one outcome the source expresses without a literal.
         * @param changeActionCode the {@code ACUP-CHANGE-ACTION} value from the dispatch at
         * {@code app/cbl/COACTUPC.cbl:L2606-L2615}
         */
        Outcome(String legacyMessage, char changeActionCode) {
            this.legacyMessage = legacyMessage;
            this.changeActionCode = changeActionCode;
        }

        /**
         * Returns the verbatim legacy screen message literal for this outcome.
         *
         * @return the literal, never null; <strong>empty</strong> for {@link Outcome#CHANGES_NOT_CONFIRMED},
         * which the source expresses without a message literal.
         */
        public String getLegacyMessage() {
            return legacyMessage;
        }

        /**
         * Returns the {@code ACUP-CHANGE-ACTION} value the legacy dispatch writes for this outcome.
         *
         * @return the single character marker, always one of {@code 'L'}, {@code 'C'}, {@code 'S'}, {@code 'F'}
         * or {@code 'N'}
         */
        public char getChangeActionCode() {
            return changeActionCode;
        }
    }

    /**
     * Which of the five reasons applies, or null when the throw site did not identify one.
     */
    private final Outcome outcome;

    /**
     * The name of the dataset involved, or null when none was stated. A name only - never content.
     */
    private final String affectedRecord;

    /**
     * Creates an exception that reports an abandoned update without naming which of the five outcomes applies.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     */
    public ConcurrentUpdateException(String message) {
        super(message);
        this.outcome = null;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception that reports an abandoned update, preserving the throwable that caused it but
     * without naming which of the five outcomes applies.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
        this.outcome = null;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception for a named outcome, for the business level detections that have no underlying
     * throwable.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param outcome which of the five reasons applies.
     */
    public ConcurrentUpdateException(Outcome outcome, String message) {
        super(message);
        this.outcome = outcome;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception for a named outcome, preserving the throwable that caused it.
     *
     * @param outcome which of the five reasons applies.
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public ConcurrentUpdateException(Outcome outcome, String message, Throwable cause) {
        this(outcome, message, null, cause);
    }

    /**
     * Creates an exception for a named outcome, naming the dataset involved and preserving the throwable that
     * caused it.
     *
     * @param outcome which of the five reasons applies.
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}.
     * @param affectedRecord the name of the dataset involved - the legacy file names are {@code ACCTDAT} and
     * {@code CUSTDAT}, declared at {@code app/cbl/COACTUPC.cbl:L573-L576}.
     * @param cause the underlying throwable, retrievable through {@link Throwable#getCause()}.
     */
    public ConcurrentUpdateException(Outcome outcome, String message, String affectedRecord,
            Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
        this.affectedRecord = affectedRecord == null || affectedRecord.isBlank()
                ? null
                : affectedRecord.strip();
    }

    /**
     * Returns which of the five reasons the update was abandoned for.
     *
     * @return the outcome, or <strong>null</strong> when the instance was created through one of the two
     * message only constructors, which record none.
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns the name of the dataset involved in the failure.
     *
     * @return the dataset name as supplied with surrounding whitespace stripped, typically {@code ACCTDAT} or
     * {@code CUSTDAT}, or <strong>null</strong> when none was stated or when a null or blank value was
     * supplied.
     */
    public String getAffectedRecord() {
        return affectedRecord;
    }
}
