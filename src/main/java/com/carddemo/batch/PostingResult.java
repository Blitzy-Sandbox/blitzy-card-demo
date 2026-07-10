package com.carddemo.batch;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.RejectReason;
import java.util.Objects;

/**
 * Immutable, batch-internal transfer object that carries the outcome of posting a single
 * daily transaction from the {@code PostTransactionProcessor} to the
 * {@code PostTransactionItemWriter} within the CardDemo transaction-posting job
 * ({@code PostTransactionJob}).
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This record
 * models the mutually-exclusive branch taken by the main posting loop of the legacy batch
 * program {@code CBTRN02C.cbl}. After each daily-transaction read the program runs
 * {@code 1500-VALIDATE-TRAN} and then, in the {@code PROCEDURE DIVISION} loop, dispatches on the
 * numeric validation result:</p>
 *
 * <pre>
 *   IF WS-VALIDATION-FAIL-REASON = 0
 *       PERFORM 2000-POST-TRANSACTION        -&gt; a posted {@link Transaction} (this.postedTransaction)
 *   ELSE
 *       ADD 1 TO WS-REJECT-COUNT
 *       PERFORM 2500-WRITE-REJECT-REC        -&gt; a rejected daily transaction (this.rejectReason)
 *   END-IF
 * </pre>
 *
 * <p><strong>Why a discriminated carrier.</strong> A Spring Batch chunk step passes exactly one
 * typed item from processor to writer. A validation failure is normal business flow rather than
 * an exception (AAP §0.8.3), and a rejected transaction must be <em>written</em> to the reject
 * file rather than skipped, so both the posted and rejected outcomes have to travel through the
 * same item type. The writer inspects {@link #isPosted()} / {@link #isRejected()} to decide
 * whether to persist {@link #postedTransaction()} (COBOL {@code 2900-WRITE-TRANSACTION-FILE} and
 * the posted counter) or to serialize a fixed-width 430-byte reject record (COBOL
 * {@code 2500-WRITE-REJECT-REC}; the {@code DALYREJS} dataset is {@code RECFM=F,LRECL=430}, i.e.
 * the original 350-byte daily-transaction record followed by the 80-byte
 * {@code WS-VALIDATION-TRAILER} = {@code PIC 9(04)} reason code + {@code PIC X(76)} description).</p>
 *
 * <p><strong>Reject payload source.</strong> {@link #sourceTransaction()} is always present — it
 * is the original {@link DailyTransaction} input row and is required to serialize the 430-byte
 * reject payload; {@link #postedTransaction()} is {@code null} for a rejected outcome. The
 * discriminant is {@link #rejectReason()}: {@link RejectReason#VALID} (code {@code 0}) denotes a
 * posted outcome, and one of {@link RejectReason#INVALID_CARD_NUMBER} (100),
 * {@link RejectReason#ACCOUNT_NOT_FOUND} (101), {@link RejectReason#OVERLIMIT} (102),
 * {@link RejectReason#ACCOUNT_EXPIRED} (103) or {@link RejectReason#ACCOUNT_NOT_FOUND_ON_UPDATE}
 * (109) denotes a rejected outcome.</p>
 *
 * <p><strong>Decimal fidelity (AAP §0.8.2).</strong> This carrier holds no primitive monetary
 * fields; all amounts remain on the referenced entities as {@link java.math.BigDecimal} (scale
 * {@code 2}). No {@code float} or {@code double} is introduced.</p>
 *
 * <p><strong>Immutability.</strong> As a {@code record}, every component is {@code final}, there
 * are no setters, and the compiler-generated accessors, {@code equals}, {@code hashCode} and
 * {@code toString} complete the value-type contract. Instances are safe to share across the batch
 * processor/writer chunk boundary without defensive copying.</p>
 *
 * @param sourceTransaction the original daily-transaction input row; never {@code null}. Needed
 *                          to serialize the 430-byte reject payload (the 350-byte
 *                          {@code DALYTRAN-RECORD} plus the 80-byte reject trailer).
 * @param postedTransaction the transaction built by {@code 2000-POST-TRANSACTION} when validation
 *                          passed; {@code null} for a rejected outcome.
 * @param rejectReason      the validation outcome discriminant; {@link RejectReason#VALID} for a
 *                          posted outcome, otherwise the specific rejection reason. Never
 *                          {@code null}.
 */
public record PostingResult(
        DailyTransaction sourceTransaction,
        Transaction postedTransaction,
        RejectReason rejectReason) {

    /**
     * Canonical constructor enforcing the carrier's non-null invariants.
     *
     * <p>{@code sourceTransaction} and {@code rejectReason} are always required: the source row
     * backs the reject-record payload and the reason is the outcome discriminant. In contrast
     * {@code postedTransaction} is intentionally <em>not</em> null-checked because it is
     * legitimately {@code null} for a rejected outcome (COBOL {@code 2500-WRITE-REJECT-REC}).</p>
     *
     * @throws NullPointerException if {@code sourceTransaction} or {@code rejectReason} is
     *                              {@code null}.
     */
    public PostingResult {
        Objects.requireNonNull(sourceTransaction, "sourceTransaction must not be null");
        Objects.requireNonNull(rejectReason, "rejectReason must not be null");
    }

    /**
     * Creates a <em>posted</em> result for a transaction that passed validation.
     *
     * <p>Represents the COBOL {@code 2000-POST-TRANSACTION} branch (taken when
     * {@code WS-VALIDATION-FAIL-REASON = 0}); the {@link #rejectReason()} discriminant is set to
     * {@link RejectReason#VALID} so the writer persists {@code posted} via
     * {@code 2900-WRITE-TRANSACTION-FILE} and increments the posted counter.</p>
     *
     * @param source the original daily-transaction input row; must not be {@code null}.
     * @param posted the built posted transaction; must not be {@code null}.
     * @return a posted {@code PostingResult} for which {@link #isPosted()} is {@code true}.
     * @throws NullPointerException if {@code source} or {@code posted} is {@code null}.
     */
    public static PostingResult posted(DailyTransaction source, Transaction posted) {
        return new PostingResult(
                source,
                Objects.requireNonNull(posted, "posted transaction must not be null"),
                RejectReason.VALID);
    }

    /**
     * Creates a <em>rejected</em> result for a transaction that failed validation.
     *
     * <p>Represents the COBOL {@code 2500-WRITE-REJECT-REC} branch (taken when
     * {@code WS-VALIDATION-FAIL-REASON} is non-zero); {@link #postedTransaction()} is {@code null}
     * and the writer emits the fixed-width 430-byte reject record and increments the rejected
     * counter.</p>
     *
     * @param source the original daily-transaction input row; must not be {@code null}.
     * @param reason the non-{@link RejectReason#VALID} rejection reason; must not be {@code null}.
     * @return a rejected {@code PostingResult} for which {@link #isRejected()} is {@code true}.
     * @throws NullPointerException if {@code source} or {@code reason} is {@code null}.
     */
    public static PostingResult rejected(DailyTransaction source, RejectReason reason) {
        return new PostingResult(source, null, reason);
    }

    /**
     * Indicates whether this result represents a posted transaction (validation passed).
     *
     * @return {@code true} when {@link #rejectReason()} is {@link RejectReason#VALID}
     *         (COBOL {@code WS-VALIDATION-FAIL-REASON = 0}); {@code false} otherwise.
     */
    public boolean isPosted() {
        return rejectReason == RejectReason.VALID;
    }

    /**
     * Indicates whether this result represents a rejected transaction (validation failed).
     *
     * @return {@code true} when the transaction was rejected; the logical negation of
     *         {@link #isPosted()}.
     */
    public boolean isRejected() {
        return !isPosted();
    }
}
