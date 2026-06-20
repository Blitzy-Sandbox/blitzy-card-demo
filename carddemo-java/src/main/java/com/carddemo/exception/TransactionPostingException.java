package com.carddemo.exception;

/**
 * Daily-transaction posting rejection raised by the batch posting pipeline.
 *
 * <p>Java equivalent of the rejection trailer produced by the mainframe
 * daily-transaction posting program {@code app/cbl/CBTRN02C.cbl} (source commit
 * {@code 27d6c6f}). In the COBOL source the validation/posting cascade set
 * {@code WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}, a four-digit reject code)
 * and {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}, the reason text),
 * then wrote a reject record via the {@code 2500-WRITE-REJECT-REC} paragraph. This
 * exception carries that same {@code (code, reason)} pair so the posting processor
 * and reject writer can tag the rejected record and drive the reason-tagged metric
 * {@code carddemo.batch.records.rejected}.</p>
 *
 * <p>The closed, verified set of reject reasons emitted by the source program is:</p>
 * <ul>
 *   <li>{@code 100} &mdash; {@code INVALID CARD NUMBER FOUND} (cross-reference read
 *       {@code INVALID KEY}).</li>
 *   <li>{@code 101} &mdash; {@code ACCOUNT RECORD NOT FOUND} (account read
 *       {@code INVALID KEY} during lookup).</li>
 *   <li>{@code 102} &mdash; {@code OVERLIMIT TRANSACTION} (the computed balance
 *       exceeds the account credit limit).</li>
 *   <li>{@code 103} &mdash; {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}
 *       (the transaction date is past the account expiration date).</li>
 *   <li>{@code 109} &mdash; {@code ACCOUNT RECORD NOT FOUND} (account
 *       {@code REWRITE INVALID KEY} during the balance update/posting step).</li>
 * </ul>
 *
 * <p>Reject codes {@code 104}&ndash;{@code 108} are an intentional gap in the source
 * program and are not represented anywhere in the migration. Because code
 * {@code 109} arises during the posting-time {@code REWRITE} (not during the earlier
 * validation paragraphs), this exception extends {@link CardDemoException} directly
 * rather than a validation-specific subtype, so it models the whole posting-reject
 * family at the base level. The decision rationale is recorded in
 * {@code DECISION_LOG.md}; this class deliberately carries no enum or constant
 * mapping &mdash; the numeric code and reason text are supplied by the caller (the
 * code/description mapping lives in {@code com.carddemo.model.enums.RejectCode}).</p>
 */
public class TransactionPostingException extends CardDemoException {

    /**
     * Serialization version identifier. Required because the exception hierarchy is
     * {@link java.io.Serializable} (via {@link RuntimeException}) and the build runs
     * under {@code -Xlint:all -Werror}, which flags a missing {@code serialVersionUID}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Numeric reject reason, equivalent to the COBOL {@code WS-VALIDATION-FAIL-REASON}
     * ({@code PIC 9(04)}) field &mdash; for example {@code 100} or {@code 109}. Stored as a
     * primitive {@code int}, which is serializable and therefore needs no {@code transient}
     * modifier.
     */
    private final int rejectCode;

    /**
     * Reject description text, equivalent to the COBOL
     * {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}) field &mdash; for example
     * {@code "OVERLIMIT TRANSACTION"}.
     */
    private final String reason;

    /**
     * Creates a posting rejection with the given reject code and reason text. The
     * detail message is built automatically from the supplied values in the form
     * {@code "Transaction posting rejected (<code>): <reason>"}.
     *
     * @param rejectCode the numeric reject reason (for example {@code 100} or {@code 109})
     * @param reason     the reject description text (for example {@code "OVERLIMIT TRANSACTION"})
     */
    public TransactionPostingException(int rejectCode, String reason) {
        super("Transaction posting rejected (" + rejectCode + "): " + reason);
        this.rejectCode = rejectCode;
        this.reason = reason;
    }

    /**
     * Creates a posting rejection with the given reject code, reason text, and
     * underlying cause. The detail message is built automatically from the supplied
     * values in the form {@code "Transaction posting rejected (<code>): <reason>"}.
     *
     * @param rejectCode the numeric reject reason (for example {@code 100} or {@code 109})
     * @param reason     the reject description text (for example {@code "OVERLIMIT TRANSACTION"})
     * @param cause      the underlying cause (for example an I/O failure during the reject write)
     */
    public TransactionPostingException(int rejectCode, String reason, Throwable cause) {
        super("Transaction posting rejected (" + rejectCode + "): " + reason, cause);
        this.rejectCode = rejectCode;
        this.reason = reason;
    }

    /**
     * Returns the numeric reject reason carried by this rejection.
     *
     * @return the reject code (for example {@code 100} or {@code 109})
     */
    public int getRejectCode() {
        return rejectCode;
    }

    /**
     * Returns the reject description text carried by this rejection.
     *
     * @return the reason text (for example {@code "OVERLIMIT TRANSACTION"})
     */
    public String getReason() {
        return reason;
    }
}
