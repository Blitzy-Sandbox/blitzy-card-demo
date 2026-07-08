package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.RejectReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link PostingResult}, the discriminated carrier that conveys the outcome of
 * posting a single daily transaction from the posting processor to the writer. Legacy source is
 * referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <p>{@link PostingResult} models the mutually-exclusive branch of the {@code CBTRN02C.cbl} main
 * loop: {@code WS-VALIDATION-FAIL-REASON = 0} yields a <em>posted</em> transaction
 * ({@code 2000-POST-TRANSACTION}); a non-zero reason yields a <em>rejected</em> daily transaction
 * ({@code 2500-WRITE-REJECT-REC}). A validation failure is normal business flow (AAP&nbsp;&sect;0.8.3),
 * not an exception, so both outcomes travel through the same item type and the writer dispatches on
 * {@link PostingResult#isPosted()} / {@link PostingResult#isRejected()}.</p>
 *
 * <p>This suite pins the carrier's contract: the two static factories, the {@code isPosted} /
 * {@code isRejected} discriminant (keyed off {@link RejectReason#VALID}), and the canonical
 * constructor's non-null invariants ({@code sourceTransaction} and {@code rejectReason} required;
 * {@code postedTransaction} legitimately {@code null} for a rejection). No Spring context,
 * database, or network is used, so it is fast and compiles warning-free under {@code -Xlint:all}
 * (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage.</p>
 *
 * @see PostingResult
 * @see RejectReason
 */
@DisplayName("PostingResult — posted/rejected discriminated carrier (CBTRN02C posting branch)")
class PostingResultTest {

    /** A representative source daily-transaction row (only identity matters to this carrier). */
    private static DailyTransaction sourceRow() {
        final DailyTransaction daily = new DailyTransaction();
        daily.setDalytranId("0000000000000001");
        return daily;
    }

    /** A representative posted transaction. */
    private static Transaction postedTx() {
        final Transaction transaction = new Transaction();
        transaction.setTranId("0000000000000001");
        return transaction;
    }

    // =====================================================================
    // 1) posted(...) factory — validation passed
    // =====================================================================

    @Nested
    @DisplayName("posted(source, posted) — the 2000-POST-TRANSACTION branch")
    class PostedFactory {

        @Test
        @DisplayName("marks the result posted with rejectReason VALID and carries both transactions")
        void postedResultIsPosted() {
            final DailyTransaction source = sourceRow();
            final Transaction posted = postedTx();

            final PostingResult result = PostingResult.posted(source, posted);

            assertThat(result.isPosted()).isTrue();
            assertThat(result.isRejected()).isFalse();
            assertThat(result.rejectReason()).isEqualTo(RejectReason.VALID);
            assertThat(result.sourceTransaction()).isSameAs(source);
            assertThat(result.postedTransaction()).isSameAs(posted);
        }

        @Test
        @DisplayName("rejects a null posted transaction")
        void postedRejectsNullPosted() {
            final DailyTransaction source = sourceRow();

            assertThatNullPointerException()
                    .isThrownBy(() -> PostingResult.posted(source, null))
                    .withMessageContaining("posted transaction must not be null");
        }
    }

    // =====================================================================
    // 2) rejected(...) factory — validation failed
    // =====================================================================

    @Nested
    @DisplayName("rejected(source, reason) — the 2500-WRITE-REJECT-REC branch")
    class RejectedFactory {

        @Test
        @DisplayName("marks the result rejected, keeps the source row, and has a null posted transaction")
        void rejectedResultIsRejected() {
            final DailyTransaction source = sourceRow();

            final PostingResult result = PostingResult.rejected(source, RejectReason.OVERLIMIT);

            assertThat(result.isRejected()).isTrue();
            assertThat(result.isPosted()).isFalse();
            assertThat(result.rejectReason()).isEqualTo(RejectReason.OVERLIMIT);
            assertThat(result.sourceTransaction()).isSameAs(source);
            // The reject branch has no posted transaction (COBOL 2500-WRITE-REJECT-REC).
            assertThat(result.postedTransaction()).isNull();
        }

        @Test
        @DisplayName("each non-VALID reason yields a rejected result")
        void everyNonValidReasonIsRejected() {
            final DailyTransaction source = sourceRow();

            for (final RejectReason reason : RejectReason.values()) {
                if (reason == RejectReason.VALID) {
                    continue;
                }
                final PostingResult result = PostingResult.rejected(source, reason);
                assertThat(result.isRejected())
                        .as("reason %s must be a rejection", reason)
                        .isTrue();
                assertThat(result.rejectReason()).isEqualTo(reason);
            }
        }
    }

    // =====================================================================
    // 3) canonical constructor invariants + value semantics
    // =====================================================================

    @Nested
    @DisplayName("canonical constructor invariants and record value semantics")
    class ConstructorInvariants {

        @Test
        @DisplayName("requires a non-null sourceTransaction")
        void requiresNonNullSource() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new PostingResult(null, postedTx(), RejectReason.VALID))
                    .withMessageContaining("sourceTransaction must not be null");
        }

        @Test
        @DisplayName("requires a non-null rejectReason")
        void requiresNonNullRejectReason() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new PostingResult(sourceRow(), postedTx(), null))
                    .withMessageContaining("rejectReason must not be null");
        }

        @Test
        @DisplayName("allows a null postedTransaction (legitimate for a rejected outcome)")
        void allowsNullPostedTransaction() {
            final PostingResult result =
                    new PostingResult(sourceRow(), null, RejectReason.ACCOUNT_NOT_FOUND);

            assertThat(result.postedTransaction()).isNull();
            assertThat(result.isRejected()).isTrue();
        }

        @Test
        @DisplayName("records with equal components are equal and hashCode-consistent")
        void recordEquality() {
            final DailyTransaction source = sourceRow();
            final Transaction posted = postedTx();

            final PostingResult first = PostingResult.posted(source, posted);
            final PostingResult second = new PostingResult(source, posted, RejectReason.VALID);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }
    }
}
