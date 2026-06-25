package com.carddemo.enums;

/**
 * Reject-reason vocabulary for the daily-transaction posting validation cascade.
 *
 * <p>Translated from COBOL program {@code CBTRN02C} (AWS CardDemo) at source
 * commit {@code 27d6c6f}. Each constant carries the numeric reason code and the
 * verbatim description text that the COBOL program moves into its validation
 * trailer {@code 01 WS-VALIDATION-TRAILER}:</p>
 *
 * <pre>
 * 05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 * 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * </pre>
 *
 * <p>The trailer is 80 bytes wide: a 4-digit reason code followed by a
 * 76-character description. This enum stores the raw {@code int} code (see
 * {@link #getCode()}) and the unpadded description text (see
 * {@link #getDescription()}). Rendering the code as the zero-padded
 * {@code PIC 9(04)} field (for example {@code 100} renders as {@code "0100"} and
 * {@code 0} renders as {@code "0000"}) and space-padding the description to the
 * {@code PIC X(76)} width are the responsibility of the rejects writer/processor
 * ({@code com.carddemo.batch.processor.TransactionPostingProcessor} and the
 * rejects {@code ItemWriter}), not of this enum. {@link #getFormattedCode()}
 * supplies the zero-padded code as a convenience.</p>
 *
 * <p>The codes and the description text are byte-equivalence critical
 * (Validation Gates 1 and 4) and match the COBOL baseline exactly. Codes
 * {@code 101} and {@code 109} are distinct conditions that share the same
 * description text.</p>
 */
public enum RejectReasonCode {

    /**
     * No validation failure; the transaction is valid.
     * COBOL: {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} with
     * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} (CBTRN02C L208-209).
     */
    NONE(0, ""),

    /**
     * Card cross-reference lookup did not find the card number.
     * COBOL: {@code 1500-A-LOOKUP-XREF} INVALID KEY (CBTRN02C L385-387).
     */
    CARD_NOT_FOUND(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Account master lookup did not find the account record.
     * COBOL: {@code 1500-B-LOOKUP-ACCT} INVALID KEY (CBTRN02C L397-399).
     */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Transaction would exceed the available credit limit.
     * COBOL: credit-limit check (CBTRN02C L410-412).
     */
    OVER_CREDIT_LIMIT(102, "OVERLIMIT TRANSACTION"),

    /**
     * Transaction was received after the account expiration date.
     * COBOL: expiration check (CBTRN02C L417-419).
     */
    ACCOUNT_EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /**
     * Account record was absent during the post-update account rewrite.
     * COBOL: {@code 2800-UPDATE-ACCOUNT-REC} REWRITE INVALID KEY
     * (CBTRN02C L556-558). Distinct condition from {@link #ACCOUNT_NOT_FOUND}.
     */
    ACCOUNT_NOT_FOUND_ON_UPDATE(109, "ACCOUNT RECORD NOT FOUND");

    private final int code;
    private final String description;

    private RejectReasonCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the raw numeric reason code (the {@code PIC 9(04)} value).
     *
     * @return the reason code as an {@code int}
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the verbatim COBOL description text, unpadded.
     *
     * @return the reason description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the reason code rendered as the zero-padded four-digit
     * {@code PIC 9(04)} field (for example {@code "0100"} for code {@code 100}
     * and {@code "0000"} for {@link #NONE}).
     *
     * @return the zero-padded four-digit code
     */
    public String getFormattedCode() {
        return String.format("%04d", code);
    }

    /**
     * Resolves the constant whose {@link #getCode()} equals the given code.
     *
     * @param code the numeric reason code to resolve; {@code 0} resolves to
     *             {@link #NONE}
     * @return the matching {@code RejectReasonCode}
     * @throws IllegalArgumentException if no constant carries the given code
     */
    public static RejectReasonCode fromCode(int code) {
        for (RejectReasonCode reason : values()) {
            if (reason.code == code) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown reject reason code: " + code);
    }
}
