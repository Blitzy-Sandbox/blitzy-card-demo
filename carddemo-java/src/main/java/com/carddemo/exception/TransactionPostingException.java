package com.carddemo.exception;

/**
 * Daily-transaction posting rejection. Models the validation cascade of
 * {@code app/cbl/CBTRN02C.cbl}, which set {@code WS-VALIDATION-FAIL-REASON} (PIC 9(04))
 * and {@code WS-VALIDATION-FAIL-REASON-DESC} (PIC X(76)) and wrote a reject trailer via
 * {@code 2500-WRITE-REJECT-REC}. Source commit {@code 27d6c6f}. Known reject codes:
 * 100 INVALID CARD NUMBER FOUND, 101 ACCOUNT RECORD NOT FOUND, 102 OVERLIMIT TRANSACTION,
 * 103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION, 109 ACCOUNT RECORD NOT FOUND (on the
 * account REWRITE during posting). Codes 104-108 are unused in the source.
 *
 * <p>Extends {@link CardDemoException} directly rather than a validation-only subtype:
 * although codes 100-103 originate in the validation paragraphs, code 109 is a
 * posting-time {@code REWRITE INVALID KEY} failure ({@code 2800-UPDATE-ACCOUNT-REC}),
 * so this type models the whole posting-reject family at the base level.</p>
 *
 * <p>The numeric {@code rejectCode} and {@code reason} text are supplied by the caller
 * (the posting processor / {@code FileStatusMapper}); the human-readable exception
 * message is composed from them. This class intentionally holds no catalog of reject
 * codes - that mapping is the responsibility of {@code com.carddemo.model.enums.RejectCode}.</p>
 */
public class TransactionPostingException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /** Numeric reject code mirroring COBOL {@code WS-VALIDATION-FAIL-REASON} (e.g. 100). */
    private final int rejectCode;

    /** Reject reason text mirroring COBOL {@code WS-VALIDATION-FAIL-REASON-DESC} (e.g. "OVERLIMIT TRANSACTION"). */
    private final String reason;

    /**
     * Creates a posting rejection with the given numeric reject code and reason text.
     *
     * @param rejectCode the numeric reject code (e.g. 100)
     * @param reason     the reject reason description (e.g. "OVERLIMIT TRANSACTION")
     */
    public TransactionPostingException(int rejectCode, String reason) {
        super("Transaction posting rejected (" + rejectCode + "): " + reason);
        this.rejectCode = rejectCode;
        this.reason = reason;
    }

    /**
     * Creates a posting rejection with the given numeric reject code, reason text, and
     * underlying cause.
     *
     * @param rejectCode the numeric reject code (e.g. 109)
     * @param reason     the reject reason description (e.g. "ACCOUNT RECORD NOT FOUND")
     * @param cause      the underlying cause of this rejection
     */
    public TransactionPostingException(int rejectCode, String reason, Throwable cause) {
        super("Transaction posting rejected (" + rejectCode + "): " + reason, cause);
        this.rejectCode = rejectCode;
        this.reason = reason;
    }

    /**
     * Returns the numeric reject code (mirrors COBOL {@code WS-VALIDATION-FAIL-REASON}).
     *
     * @return the numeric reject code
     */
    public int getRejectCode() {
        return rejectCode;
    }

    /**
     * Returns the reject reason text (mirrors COBOL {@code WS-VALIDATION-FAIL-REASON-DESC}).
     *
     * @return the reject reason description
     */
    public String getReason() {
        return reason;
    }
}
