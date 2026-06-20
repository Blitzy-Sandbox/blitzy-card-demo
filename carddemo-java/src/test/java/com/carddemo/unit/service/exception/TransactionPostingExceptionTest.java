package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.TransactionPostingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionPostingException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes the daily-transaction posting validation cascade in
 * {@code app/cbl/CBTRN02C.cbl}, whose {@code WS-VALIDATION-FAIL-REASON} (L181) is set to
 * one of the reject codes {@code 100, 101, 102, 103, 109}. Codes 104-108 are unused in
 * the source and are never represented here (no feature expansion; AAP section
 * 0.8.4).</p>
 */
@DisplayName("TransactionPostingException - CBTRN02C reject codes 100/101/102/103/109")
class TransactionPostingExceptionTest {

    @Test
    @DisplayName("(int, String) constructor auto-builds the message and has no cause")
    void twoArg_autoBuildsMessage() {
        TransactionPostingException ex =
            new TransactionPostingException(100, "INVALID CARD NUMBER FOUND");

        assertThat(ex.getMessage())
            .isEqualTo("Transaction posting rejected (100): INVALID CARD NUMBER FOUND");
        assertThat(ex.getRejectCode()).isEqualTo(100);
        assertThat(ex.getReason()).isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(int, String, Throwable) constructor auto-builds the message and keeps the cause")
    void threeArg_autoBuildsMessageAndKeepsCause() {
        Throwable root = new IllegalStateException("rewrite failed");

        TransactionPostingException ex =
            new TransactionPostingException(109, "ACCOUNT RECORD NOT FOUND", root);

        assertThat(ex.getMessage())
            .isEqualTo("Transaction posting rejected (109): ACCOUNT RECORD NOT FOUND");
        assertThat(ex.getRejectCode()).isEqualTo(109);
        assertThat(ex.getReason()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("auto-built message format holds for all five real reject codes")
    void autoBuiltMessage_holdsForAllFiveRealRejectCodes() {
        assertAutoBuilt(100, "INVALID CARD NUMBER FOUND");
        assertAutoBuilt(101, "ACCOUNT RECORD NOT FOUND");
        assertAutoBuilt(102, "OVERLIMIT TRANSACTION");
        assertAutoBuilt(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertAutoBuilt(109, "ACCOUNT RECORD NOT FOUND");
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        TransactionPostingException ex =
            new TransactionPostingException(101, "ACCOUNT RECORD NOT FOUND");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    private static void assertAutoBuilt(int rejectCode, String reason) {
        TransactionPostingException ex = new TransactionPostingException(rejectCode, reason);

        assertThat(ex.getRejectCode()).isEqualTo(rejectCode);
        assertThat(ex.getReason()).isEqualTo(reason);
        assertThat(ex.getMessage())
            .isEqualTo("Transaction posting rejected (" + rejectCode + "): " + reason);
    }
}
