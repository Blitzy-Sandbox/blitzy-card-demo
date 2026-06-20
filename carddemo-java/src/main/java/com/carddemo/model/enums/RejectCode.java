package com.carddemo.model.enums;

/**
 * Daily-transaction rejection reasons.
 *
 * <p>Java equivalent of the {@code CBTRN02C} working-storage fields
 * {@code WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}) and
 * {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}) from the
 * mainframe daily-transaction posting program (source commit {@code 27d6c6f}).
 *
 * <p>Each constant pairs the numeric reject reason with the exact COBOL
 * description literal it carries. The set is the closed, verified collection of
 * five reasons emitted by the source program's validation paragraphs
 * ({@code 100}, {@code 101}, {@code 102}, {@code 103}, {@code 109}); no other
 * reason codes exist in the source.
 *
 * <p>Consumed by the daily-transaction posting processor and writer to tag
 * rejection records and to drive the reason-tagged metric
 * {@code carddemo.batch.records.rejected}.
 *
 * <p>Note: {@link #ACCOUNT_NOT_FOUND} ({@code 101}) and
 * {@link #ACCOUNT_UPDATE_NOT_FOUND} ({@code 109}) carry the same description
 * text but are distinct reasons — {@code 101} signals an account missing during
 * lookup, while {@code 109} signals an account missing during the balance
 * rewrite/update.
 */
public enum RejectCode {

    /** Reject reason {@code 100}: the card number on the transaction was not found. */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /** Reject reason {@code 101}: the account record was not found during lookup. */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /** Reject reason {@code 102}: the transaction would exceed the account credit limit. */
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION"),

    /** Reject reason {@code 103}: the transaction was received after account expiration. */
    TRANSACTION_AFTER_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /** Reject reason {@code 109}: the account record was not found during the balance rewrite/update. */
    ACCOUNT_UPDATE_NOT_FOUND(109, "ACCOUNT RECORD NOT FOUND");

    /** Numeric reject reason, equivalent to {@code WS-VALIDATION-FAIL-REASON}. */
    private final int code;

    /** Reject description, equivalent to {@code WS-VALIDATION-FAIL-REASON-DESC}. */
    private final String description;

    RejectCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the numeric reject reason.
     *
     * @return the numeric reject code (for example, {@code 100} or {@code 109})
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the reject description as the exact source literal.
     *
     * @return the description text carried by this reject reason
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a {@code RejectCode} from its numeric reject reason.
     *
     * @param code the numeric reject reason (for example, {@code 100} or {@code 109})
     * @return the matching constant, or {@code null} if no constant has that code
     */
    public static RejectCode fromCode(int code) {
        for (RejectCode reason : values()) {
            if (reason.code == code) {
                return reason;
            }
        }
        return null;
    }
}
