package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.dto.TransactionViewResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast Mockito unit tests for {@link TransactionViewService} — the Java 25 /
 * Spring Boot translation of the CICS/COBOL transaction-view program
 * {@code COTRN01C} (online transaction {@code CT01}, "Txn View"). The COBOL source
 * is referenced — never copied — at commit SHA {@code 27d6c6f}
 * (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}).
 *
 * <p>The single collaborator ({@link TransactionRepository}) is supplied as a
 * Mockito mock and injected via the service's only constructor, so this suite
 * loads <em>no</em> Spring context and touches no database, Testcontainers,
 * Docker, or live AWS. Every branch of {@link TransactionViewService#viewTransaction(String)}
 * is exercised, feeding the JaCoCo line-coverage gate (Gate&nbsp;8) and compiling
 * cleanly under {@code -Xlint:all} (Gate&nbsp;2).</p>
 *
 * <h2>Behavioural parity assertions (verbatim to {@code COTRN01C})</h2>
 * <ul>
 *   <li><b>{@code PROCESS-ENTER-KEY}</b> (source L144–L192) — an empty transaction
 *       id ({@code TRNIDINI = SPACES OR LOW-VALUES}) yields the exact operator
 *       message <em>"Tran ID can NOT be empty..."</em> (source L149). Here that
 *       maps to a {@link ValidationException} raised <em>before</em> any repository
 *       access, so the repository is asserted to be untouched.</li>
 *   <li><b>{@code READ-TRANSACT-FILE}</b> {@code DFHRESP(NOTFND)} branch
 *       (source L285) — a missing record yields the exact message
 *       <em>"Transaction ID NOT found..."</em>, mapped to a
 *       {@link ResourceNotFoundException}.</li>
 *   <li><b>Field projection</b> (source L176–L192) — the record fields moved to the
 *       {@code COTRN1A} map are projected onto {@link TransactionViewResponse} in
 *       the same order, with the PAN masked, the amount at scale&nbsp;2, and the
 *       26-character timestamps preserved verbatim.</li>
 * </ul>
 *
 * <p>Monetary values are asserted only through {@link BigDecimal} — via
 * {@code scale()} and {@code compareTo} (AssertJ {@code isEqualByComparingTo}) —
 * and every {@code BigDecimal} literal is constructed from a {@code String}; no
 * {@code float}/{@code double} appears anywhere, honouring the migration's
 * decimal-fidelity constraint (AAP&nbsp;§0.8.2).</p>
 *
 * @see TransactionViewService
 * @see TransactionViewResponse
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionViewService — COBOL COTRN01C 'Txn View' (CT01) parity (SHA 27d6c6f)")
class TransactionViewServiceTest {

    /**
     * Verbatim empty-id message transcribed from {@code COTRN01C} paragraph
     * {@code PROCESS-ENTER-KEY} (source L149).
     */
    private static final String MSG_TRANID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Verbatim not-found message transcribed from {@code COTRN01C} paragraph
     * {@code READ-TRANSACT-FILE} {@code DFHRESP(NOTFND)} branch (source L285).
     */
    private static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    /** {@code TRAN-ID} key used for the happy-path fixture ({@code PIC X(16)}). */
    private static final String TRAN_ID = "0000000000000001";

    /** Raw, unmasked card number stored on the entity ({@code TRAN-CARD-NUM}, {@code X(16)}). */
    private static final String FULL_PAN = "1234567890123456";

    /** Expected masked card number: only the last four characters remain visible. */
    private static final String MASKED_PAN = "************3456";

    /** 26-character {@code TRAN-ORIG-TS} fixture ({@code yyyy-mm-dd-hh.mm.ss.ffffff}). */
    private static final String ORIG_TS = "2024-01-15-12.30.45.123456";

    /** 26-character {@code TRAN-PROC-TS} fixture ({@code yyyy-mm-dd-hh.mm.ss.ffffff}). */
    private static final String PROC_TS = "2024-01-15-12.30.46.654321";

    /** Mocked repository standing in for keyed access to the migrated {@code TRANSACT} KSDS. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Class under test, with {@link #transactionRepository} injected via its constructor. */
    @InjectMocks
    private TransactionViewService service;

    /**
     * Builds a fully-populated {@link Transaction} fixture mirroring the
     * {@code CVTRA05Y} {@code TRAN-RECORD} layout, with a full (unmasked) card
     * number, a scale-2 amount, and two 26-character timestamps.
     *
     * @return a populated {@link Transaction} ready for the happy-path assertions
     */
    private static Transaction sampleTransaction() {
        Transaction t = new Transaction();
        t.setTranId(TRAN_ID);
        t.setTranCardNum(FULL_PAN);
        t.setTranTypeCd("DB");
        t.setTranCatCd(5);
        t.setTranSource("POS");
        t.setTranDesc("GROCERY STORE PURCHASE");
        t.setTranAmt(new BigDecimal("1234.56"));
        t.setTranOrigTs(ORIG_TS);
        t.setTranProcTs(PROC_TS);
        t.setTranMerchantId(42L);
        t.setTranMerchantName("ACME GROCERS");
        t.setTranMerchantCity("SPRINGFIELD");
        t.setTranMerchantZip("62704");
        return t;
    }

    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY — empty/blank/null id edit (source L144–L156)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null/empty/blank id -> ValidationException with the verbatim message; repository untouched")
    void viewTransaction_emptyId_throwsValidation() {
        assertThatThrownBy(() -> service.viewTransaction(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_TRANID_EMPTY);

        assertThatThrownBy(() -> service.viewTransaction(""))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_TRANID_EMPTY);

        assertThatThrownBy(() -> service.viewTransaction("   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_TRANID_EMPTY);

        // The empty-id edit short-circuits before READ-TRANSACT-FILE runs.
        verifyNoInteractions(transactionRepository);
    }

    // ------------------------------------------------------------------
    // READ-TRANSACT-FILE — DFHRESP(NOTFND) branch (source L282–L288)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown id -> ResourceNotFoundException with the verbatim not-found message")
    void viewTransaction_notFound_throwsResourceNotFound() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewTransaction(TRAN_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_TRAN_NOT_FOUND);

        verify(transactionRepository).findById(TRAN_ID);
    }

    // ------------------------------------------------------------------
    // Field projection — PAN masking (source L177)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success masks the card number to its last four characters, never emitting the full PAN")
    void viewTransaction_success_masksPan() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));

        TransactionViewResponse response = service.viewTransaction(TRAN_ID);

        assertThat(response.cardNumber())
                .isEqualTo(MASKED_PAN)
                .isNotEqualTo(FULL_PAN)
                .startsWith("*")
                .endsWith("3456")
                .doesNotContain("123456789012");
    }

    // ------------------------------------------------------------------
    // Field projection — decimal fidelity (source L175, TRAN-AMT)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success returns the amount as a scale-2 BigDecimal (compareTo); normalises a scale-1 input")
    void viewTransaction_success_amountScaleTwo() {
        Transaction transaction = sampleTransaction();
        // A scale-1 source amount must be normalised to scale 2 on the response.
        transaction.setTranAmt(new BigDecimal("50.5"));
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(transaction));

        TransactionViewResponse response = service.viewTransaction(TRAN_ID);

        assertThat(response.amount().scale()).isEqualTo(2);
        // compareTo-based equality (scale-insensitive) both ways proves value fidelity.
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("50.50"));
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("50.5"));
    }

    // ------------------------------------------------------------------
    // Field projection — timestamp preservation (source L184–L185)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success preserves both 26-character timestamps verbatim")
    void viewTransaction_success_timestampLength26() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));

        TransactionViewResponse response = service.viewTransaction(TRAN_ID);

        assertThat(response.originalTimestamp()).hasSize(26).isEqualTo(ORIG_TS);
        assertThat(response.processedTimestamp()).hasSize(26).isEqualTo(PROC_TS);
    }

    // ------------------------------------------------------------------
    // Field projection — remaining scalar/identifier fields (source L172–L189)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success maps type/category/source/description and all merchant fields from the entity")
    void viewTransaction_success_mapsFields() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));

        TransactionViewResponse response = service.viewTransaction(TRAN_ID);

        assertThat(response.transactionId()).isEqualTo(TRAN_ID);
        assertThat(response.typeCode()).isEqualTo("DB");
        // TRAN-CAT-CD PIC 9(04) rendered zero-padded to four digits.
        assertThat(response.categoryCode()).isEqualTo("0005");
        assertThat(response.source()).isEqualTo("POS");
        assertThat(response.description()).isEqualTo("GROCERY STORE PURCHASE");
        // TRAN-MERCHANT-ID PIC 9(09) rendered zero-padded to nine digits.
        assertThat(response.merchantId()).isEqualTo("000000042");
        assertThat(response.merchantName()).isEqualTo("ACME GROCERS");
        assertThat(response.merchantCity()).isEqualTo("SPRINGFIELD");
        assertThat(response.merchantZip()).isEqualTo("62704");
    }
}
