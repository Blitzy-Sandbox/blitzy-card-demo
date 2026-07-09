package com.carddemo.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.RejectReason;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure JUnit&nbsp;5 unit test for {@link PostingResult} — the discriminated value object that
 * conveys the outcome of posting a single daily transaction from {@code PostTransactionProcessor}
 * to {@code PostTransactionItemWriter} — together with its reject-reason status enum
 * {@link RejectReason}. The legacy COBOL source is referenced read-only at commit
 * SHA&nbsp;{@code 27d6c6f}.
 *
 * <p><strong>COBOL lineage.</strong> {@link PostingResult} models the mutually-exclusive branch
 * of the {@code CBTRN02C.cbl} main posting loop: after {@code 1500-VALIDATE-TRAN} sets
 * {@code WS-VALIDATION-FAIL-REASON}, the loop dispatches {@code 2000-POST-TRANSACTION} for a
 * <em>posted</em> outcome (reason {@code 0}) and {@code 2500-WRITE-REJECT-REC} for a
 * <em>rejected</em> outcome (any non-zero reason). A validation failure is normal business flow
 * (AAP&nbsp;&sect;0.8.3), never an exception, so both outcomes travel through the single item type
 * and the writer dispatches on {@link PostingResult#isPosted()} / {@link PostingResult#isRejected()}.</p>
 *
 * <p><strong>What this suite locks.</strong></p>
 * <ul>
 *   <li>the {@link PostingResult#posted(DailyTransaction, Transaction) posted} and
 *       {@link PostingResult#rejected(DailyTransaction, RejectReason) rejected} factory contracts,
 *       including the {@code isPosted}/{@code isRejected} discriminant (keyed off
 *       {@link RejectReason#VALID});</li>
 *   <li>the canonical constructor's non-null invariants ({@code sourceTransaction} and
 *       {@code rejectReason} required; {@code postedTransaction} legitimately {@code null} for a
 *       rejection);</li>
 *   <li>the {@link RejectReason} numeric code mapping and description literals, which must remain
 *       byte/semantics-faithful to the CBTRN02C {@code 1500-VALIDATE-TRAN} outcomes because the
 *       writer later formats {@link RejectReason#getCode() getCode()} as {@code %04d} into the
 *       80-byte reject trailer ({@code PIC 9(04)} code + {@code PIC X(76)} description).</li>
 * </ul>
 *
 * <p>No Spring context, database, or network is used, so the suite runs in milliseconds under
 * Surefire and compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2), contributing to
 * Gate&nbsp;8 coverage by exercising both factory branches and every enum constant. Every
 * assertion uses the JUnit&nbsp;5 {@link org.junit.jupiter.api.Assertions} API.</p>
 *
 * @see PostingResult
 * @see RejectReason
 */
@DisplayName("PostingResult factory + RejectReason mapping (CBTRN02C posting branch)")
class PostingResultTest {

    /** Representative card number shared by the helper fixtures (identity is what matters here). */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Representative transaction identifier shared by the helper fixtures. */
    private static final String TRAN_ID = "0000000000000001";

    /**
     * Builds a minimally-populated {@link DailyTransaction} input row. Only the identity of the
     * returned instance matters to {@link PostingResult}; the id and card number are set so the
     * fixture resembles a real {@code DALYTRAN-RECORD} without pulling in monetary fields.
     *
     * @return a fresh, distinct daily-transaction fixture
     */
    private static DailyTransaction newDailyTransaction() {
        final DailyTransaction daily = new DailyTransaction();
        daily.setDalytranId(TRAN_ID);
        daily.setDalytranCardNum(CARD_NUMBER);
        return daily;
    }

    /**
     * Builds a minimally-populated posted {@link Transaction}. Only the identity of the returned
     * instance matters to {@link PostingResult}.
     *
     * @return a fresh, distinct posted-transaction fixture
     */
    private static Transaction newTransaction() {
        final Transaction transaction = new Transaction();
        transaction.setTranId(TRAN_ID);
        transaction.setTranCardNum(CARD_NUMBER);
        return transaction;
    }

    // =====================================================================
    // Phase 2 — posted(...) factory contract (COBOL 2000-POST-TRANSACTION)
    // =====================================================================

    @Nested
    @DisplayName("posted(source, posted) — the 2000-POST-TRANSACTION branch")
    class PostedFactory {

        @Test
        @DisplayName("marks the result posted, carries both transactions, and sets rejectReason=VALID")
        void postedResultHonoursContract() {
            final DailyTransaction source = newDailyTransaction();
            final Transaction posted = newTransaction();

            final PostingResult result = PostingResult.posted(source, posted);

            // Discriminant: a posted result is posted and not rejected.
            assertTrue(result.isPosted(), "posted() must yield isPosted()==true");
            assertFalse(result.isRejected(), "posted() must yield isRejected()==false");

            // Both transactions are carried through by identity (no defensive copy).
            assertSame(source, result.sourceTransaction(),
                    "sourceTransaction() must return the same DailyTransaction instance");
            assertSame(posted, result.postedTransaction(),
                    "postedTransaction() must return the same Transaction instance");

            // Production contract: the posted discriminant is RejectReason.VALID (not null).
            assertEquals(RejectReason.VALID, result.rejectReason(),
                    "posted() must set rejectReason() to RejectReason.VALID");
            assertSame(RejectReason.VALID, result.rejectReason(),
                    "the VALID discriminant is the singleton enum constant");
        }
    }

    // =====================================================================
    // Phase 3 — rejected(...) factory contract (COBOL 2500-WRITE-REJECT-REC)
    // =====================================================================

    @Nested
    @DisplayName("rejected(source, reason) — the 2500-WRITE-REJECT-REC branch")
    class RejectedFactory {

        @Test
        @DisplayName("marks the result rejected, keeps the source row, and has a null posted transaction")
        void rejectedResultHonoursContract() {
            final DailyTransaction source = newDailyTransaction();

            final PostingResult result = PostingResult.rejected(source, RejectReason.OVERLIMIT);

            assertTrue(result.isRejected(), "rejected() must yield isRejected()==true");
            assertFalse(result.isPosted(), "rejected() must yield isPosted()==false");
            assertEquals(RejectReason.OVERLIMIT, result.rejectReason(),
                    "rejected() must surface the supplied reason");
            assertSame(source, result.sourceTransaction(),
                    "the source row backs the 430-byte reject payload and must be retained by identity");
            // The reject branch never carries a posted transaction (COBOL 2500-WRITE-REJECT-REC).
            assertNull(result.postedTransaction(),
                    "a rejected result must have a null postedTransaction");
        }

        @ParameterizedTest(name = "{0} round-trips as a rejection")
        @EnumSource(value = RejectReason.class, names = "VALID", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("each non-VALID reason round-trips through rejected(...)")
        void everyNonValidReasonRoundTrips(final RejectReason reason) {
            final DailyTransaction source = newDailyTransaction();

            final PostingResult result = PostingResult.rejected(source, reason);

            assertTrue(result.isRejected(),
                    "a non-VALID reason must produce a rejected result");
            assertFalse(result.isPosted(),
                    "a non-VALID reason must not produce a posted result");
            assertEquals(reason, result.rejectReason(),
                    "the supplied reason must round-trip unchanged");
            assertSame(source, result.sourceTransaction(),
                    "the source row must be retained by identity");
            assertNull(result.postedTransaction(),
                    "a rejected result must have a null postedTransaction");
        }
    }

    // =====================================================================
    // Phase 4 — RejectReason code mapping (CBTRN02C parity — CRITICAL)
    // =====================================================================

    @Nested
    @DisplayName("RejectReason numeric codes and descriptions match CBTRN02C 1500-VALIDATE-TRAN")
    class RejectReasonCodeMapping {

        @Test
        @DisplayName("getCode() equals the exact COBOL WS-VALIDATION-FAIL-REASON value for every constant")
        void codesMatchCobol() {
            assertEquals(0, RejectReason.VALID.getCode(),
                    "VALID must be code 0 (WS-VALIDATION-FAIL-REASON = 0)");
            assertEquals(100, RejectReason.INVALID_CARD_NUMBER.getCode(),
                    "INVALID_CARD_NUMBER must be code 100 (1500-A-LOOKUP-XREF)");
            assertEquals(101, RejectReason.ACCOUNT_NOT_FOUND.getCode(),
                    "ACCOUNT_NOT_FOUND must be code 101 (1500-B-LOOKUP-ACCT)");
            assertEquals(102, RejectReason.OVERLIMIT.getCode(),
                    "OVERLIMIT must be code 102 (credit-limit check)");
            assertEquals(103, RejectReason.ACCOUNT_EXPIRED.getCode(),
                    "ACCOUNT_EXPIRED must be code 103 (expiration check)");
            assertEquals(109, RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode(),
                    "ACCOUNT_NOT_FOUND_ON_UPDATE must be code 109 (2800-UPDATE-ACCOUNT-REC rewrite)");
        }

        @Test
        @DisplayName("getDescription() matches the CBTRN02C WS-VALIDATION-FAIL-REASON-DESC literals verbatim")
        void descriptionsMatchCobol() {
            // VALID renders as SPACES in COBOL; the enum models that as an empty description.
            assertEquals("", RejectReason.VALID.getDescription(),
                    "VALID description must be empty (COBOL SPACES)");
            assertEquals("INVALID CARD NUMBER FOUND",
                    RejectReason.INVALID_CARD_NUMBER.getDescription());
            assertEquals("ACCOUNT RECORD NOT FOUND",
                    RejectReason.ACCOUNT_NOT_FOUND.getDescription());
            assertEquals("OVERLIMIT TRANSACTION",
                    RejectReason.OVERLIMIT.getDescription());
            assertEquals("TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
                    RejectReason.ACCOUNT_EXPIRED.getDescription());
            // Reason 109 shares the "ACCOUNT RECORD NOT FOUND" literal with 101 in CBTRN02C.
            assertEquals("ACCOUNT RECORD NOT FOUND",
                    RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription());
        }

        @Test
        @DisplayName("the enum exposes exactly the six migrated CBTRN02C outcomes")
        void enumConstantCountIsLocked() {
            // Guards against silent addition/removal of a reason, which would drift from CBTRN02C.
            assertEquals(6, RejectReason.values().length,
                    "RejectReason must model exactly the six CBTRN02C validation outcomes");
        }

        @Test
        @DisplayName("fromCode(int) resolves each known code and is empty for an unknown code")
        void fromCodeResolvesKnownAndUnknownCodes() {
            assertEquals(Optional.of(RejectReason.VALID), RejectReason.fromCode(0));
            assertEquals(Optional.of(RejectReason.INVALID_CARD_NUMBER), RejectReason.fromCode(100));
            assertEquals(Optional.of(RejectReason.ACCOUNT_NOT_FOUND), RejectReason.fromCode(101));
            assertEquals(Optional.of(RejectReason.OVERLIMIT), RejectReason.fromCode(102));
            assertEquals(Optional.of(RejectReason.ACCOUNT_EXPIRED), RejectReason.fromCode(103));
            assertEquals(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE),
                    RejectReason.fromCode(109));

            // A code that never appears in CBTRN02C resolves to no reason.
            assertTrue(RejectReason.fromCode(999).isEmpty(),
                    "an unknown reject code must resolve to Optional.empty()");
        }

        @ParameterizedTest(name = "fromCode(getCode()) is the inverse for {0}")
        @EnumSource(RejectReason.class)
        @DisplayName("fromCode(getCode()) round-trips to the originating constant for every reason")
        void fromCodeIsInverseOfGetCode(final RejectReason reason) {
            assertEquals(Optional.of(reason), RejectReason.fromCode(reason.getCode()),
                    "fromCode must be the exact inverse of getCode for every constant");
        }
    }

    // =====================================================================
    // Phase 5 — null-safety invariants + record value semantics
    // =====================================================================

    @Nested
    @DisplayName("canonical constructor null-safety invariants and record value semantics")
    class NullSafetyAndValueSemantics {

        @Test
        @DisplayName("posted(...) rejects a null posted transaction with a descriptive NPE")
        void postedRejectsNullPosted() {
            final DailyTransaction source = newDailyTransaction();

            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> PostingResult.posted(source, null));
            assertTrue(ex.getMessage().contains("posted transaction must not be null"),
                    "the NPE message must identify the null posted transaction");
        }

        @Test
        @DisplayName("posted(...) rejects a null source transaction with a descriptive NPE")
        void postedRejectsNullSource() {
            final Transaction posted = newTransaction();

            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> PostingResult.posted(null, posted));
            assertTrue(ex.getMessage().contains("sourceTransaction must not be null"),
                    "the NPE message must identify the null source transaction");
        }

        @Test
        @DisplayName("rejected(...) rejects a null reason with a descriptive NPE")
        void rejectedRejectsNullReason() {
            final DailyTransaction source = newDailyTransaction();

            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> PostingResult.rejected(source, null));
            assertTrue(ex.getMessage().contains("rejectReason must not be null"),
                    "the NPE message must identify the null reject reason");
        }

        @Test
        @DisplayName("rejected(...) rejects a null source transaction with a descriptive NPE")
        void rejectedRejectsNullSource() {
            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> PostingResult.rejected(null, RejectReason.ACCOUNT_NOT_FOUND));
            assertTrue(ex.getMessage().contains("sourceTransaction must not be null"),
                    "the NPE message must identify the null source transaction");
        }

        @Test
        @DisplayName("the canonical constructor requires a non-null sourceTransaction")
        void canonicalConstructorRequiresSource() {
            final Transaction posted = newTransaction();

            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new PostingResult(null, posted, RejectReason.VALID));
            assertTrue(ex.getMessage().contains("sourceTransaction must not be null"),
                    "the canonical constructor must null-check sourceTransaction");
        }

        @Test
        @DisplayName("the canonical constructor requires a non-null rejectReason")
        void canonicalConstructorRequiresRejectReason() {
            final DailyTransaction source = newDailyTransaction();
            final Transaction posted = newTransaction();

            final NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new PostingResult(source, posted, null));
            assertTrue(ex.getMessage().contains("rejectReason must not be null"),
                    "the canonical constructor must null-check rejectReason");
        }

        @Test
        @DisplayName("the canonical constructor permits a null postedTransaction (rejected outcome)")
        void canonicalConstructorAllowsNullPosted() {
            final DailyTransaction source = newDailyTransaction();

            final PostingResult result =
                    new PostingResult(source, null, RejectReason.ACCOUNT_NOT_FOUND);

            assertNull(result.postedTransaction(),
                    "postedTransaction is legitimately null for a rejected outcome");
            assertTrue(result.isRejected(), "a non-VALID reason yields a rejected result");
            assertEquals(RejectReason.ACCOUNT_NOT_FOUND, result.rejectReason());
        }

        @Test
        @DisplayName("records with equal components are equal and hashCode-consistent")
        void recordValueSemantics() {
            final DailyTransaction source = newDailyTransaction();
            final Transaction posted = newTransaction();

            final PostingResult viaFactory = PostingResult.posted(source, posted);
            final PostingResult viaConstructor =
                    new PostingResult(source, posted, RejectReason.VALID);

            assertEquals(viaFactory, viaConstructor,
                    "records with equal components must be equal");
            assertEquals(viaFactory.hashCode(), viaConstructor.hashCode(),
                    "equal records must share a hashCode");
        }
    }
}
