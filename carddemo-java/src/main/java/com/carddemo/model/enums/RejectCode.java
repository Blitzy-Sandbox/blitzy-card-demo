package com.carddemo.model.enums;

/**
 * Type-safe enumeration of the daily-transaction rejection reasons produced by
 * the batch posting pipeline.
 *
 * <p>This is the Java equivalent of the COBOL {@code CBTRN02C} working-storage
 * pair {@code WS-VALIDATION-FAIL-REASON} (a {@code PIC 9(04)} numeric reason
 * code) and {@code WS-VALIDATION-FAIL-REASON-DESC} (a {@code PIC X(76)}
 * description text); see source commit {@code 27d6c6f}. Each constant binds one
 * numeric code to the exact, verbatim description literal emitted by the COBOL
 * source so that the external rejection-record contract is preserved
 * byte-for-byte.</p>
 *
 * <p>The complete set present in the source is exactly five codes
 * ({@code 100}, {@code 101}, {@code 102}, {@code 103}, {@code 109}); codes
 * {@code 104}-{@code 108} are absent from the source and are intentionally not
 * represented here. Codes {@code 101} and {@code 109} share the identical
 * description text yet remain distinct reasons: {@code 101} denotes an account
 * not found during lookup, while {@code 109} denotes an account not found during
 * the balance rewrite/update.</p>
 *
 * <p>Consumers: the daily-transaction posting processor and writer use these
 * constants to tag rejection records, and they drive the reason-tagged
 * Micrometer metric {@code carddemo.batch.records.rejected}.</p>
 */
public enum RejectCode {

    /** Card cross-reference lookup failed; the daily-transaction card number has no match. */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /** Account record could not be located during the account lookup read. */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /** Posting the transaction would exceed the account's credit limit. */
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION"),

    /** Transaction was received after the account's expiration date. */
    TRANSACTION_AFTER_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /** Account record could not be located during the balance rewrite/update. */
    ACCOUNT_UPDATE_NOT_FOUND(109, "ACCOUNT RECORD NOT FOUND");

    /** Numeric reject reason, equivalent to {@code WS-VALIDATION-FAIL-REASON}. */
    private final int code;

    /** Verbatim reject description, equivalent to {@code WS-VALIDATION-FAIL-REASON-DESC}. */
    private final String description;

    /**
     * Binds a numeric reject code to its verbatim description literal.
     *
     * @param code        the numeric reject reason
     * @param description the exact COBOL description literal for the reason
     */
    RejectCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the numeric reject reason.
     *
     * @return the numeric reject reason (for example {@code 100} or {@code 109})
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the verbatim COBOL description literal for this reject reason.
     *
     * @return the exact, ALL-CAPS description text emitted by the source
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a numeric reject code to its matching constant.
     *
     * @param code the numeric reject reason (for example {@code 100} or {@code 109})
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
