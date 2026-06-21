package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.transaction.TransactionAddService;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import com.carddemo.service.shared.TransactionIdAllocator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Isolated, JVM-only unit tests for {@link TransactionAddService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the online
 * transaction-add program {@code app/cbl/COTRN02C.cbl} runs {@code VALIDATE-INPUT-KEY-FIELDS}
 * (account-or-card key edit + cross-reference derivation via {@code READ-CXACAIX-FILE} /
 * {@code READ-CCXREF-FILE}) before {@code VALIDATE-INPUT-DATA-FIELDS}, then auto-generates the
 * transaction id, gates on confirmation, and writes the record. These tests pin behavioral parity
 * with that key-validation cascade (first-error-wins, account-id branch precedence) and the add
 * flow, using Mockito-mocked repositories (no database).</p>
 */
@DisplayName("TransactionAddService - COTRN02C account-or-card key validation, xref derivation, and add")
@ExtendWith(MockitoExtension.class)
class TransactionAddServiceTest {

    private static final String CARD = "4111111111111111";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private TransactionIdAllocator transactionIdAllocator;

    private TransactionAddService service() {
        return new TransactionAddService(transactionRepository, cardCrossReferenceRepository,
                dateValidationService, transactionIdAllocator);
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static CardCrossReference xref(String cardNum, long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(cardNum);
        x.setXrefAcctId(acctId);
        x.setXrefCustId(1L);
        return x;
    }

    /** Builds a request whose <em>data</em> fields are all valid; the key pair and confirm are supplied. */
    private static TransactionAddRequest request(String accountId, String cardNumber, String confirm) {
        return new TransactionAddRequest(
                accountId,
                cardNumber,
                "01",
                "0001",
                "POS",
                "Grocery purchase",
                new BigDecimal("123.45"),
                "2023-05-01",
                "2023-05-02",
                "123456789",
                "ACME Store",
                "Springfield",
                "12345",
                confirm);
    }

    private void stubValidDates() {
        when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(new DateValidationResult(true, 0, 0, "ok", "d", "f"));
    }

    private void stubSaveEcho() {
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000100");
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- Key-validation branch parity (VALIDATE-INPUT-KEY-FIELDS) ---------------------------

    @Test
    @DisplayName("Account-only input derives the card number from the CXACAIX cross-reference")
    void accountOnlyDerivesCardFromXref() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        stubSaveEcho();

        TransactionAddResponse response = service().addTransaction(request("123", null, "Y"));

        assertThat(response.cardNumber()).isEqualTo(CARD);
        assertThat(response.accountId()).isEqualTo("00000000123");
        assertThat(response.transactionId()).isEqualTo("0000000000000100");
        verify(cardCrossReferenceRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("Card-only input derives the account id from the cross-reference")
    void cardOnlyDerivesAccountFromXref() {
        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, 456L)));
        stubValidDates();
        stubSaveEcho();

        TransactionAddResponse response = service().addTransaction(request(null, CARD, "Y"));

        assertThat(response.accountId()).isEqualTo("00000000456");
        assertThat(response.cardNumber()).isEqualTo(CARD);
        verify(cardCrossReferenceRepository, never()).findByXrefAcctId(any());
    }

    @Test
    @DisplayName("When both are supplied the account-id branch wins and re-derives the card (EVALUATE order)")
    void bothPresentAccountBranchWinsAndOverridesCard() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        stubSaveEcho();

        TransactionAddResponse response =
                service().addTransaction(request("123", "9999999999999999", "Y"));

        assertThat(response.cardNumber()).isEqualTo(CARD);
        assertThat(response.accountId()).isEqualTo("00000000123");
        verify(cardCrossReferenceRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("Neither account nor card entered is rejected with the source message")
    void neitherKeyEnteredThrows() {
        assertThatThrownBy(() -> service().addTransaction(request(null, null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account or Card Number must be entered...");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Non-numeric account id throws the exact numeric-edit message")
    void nonNumericAccountThrows() {
        assertThatThrownBy(() -> service().addTransaction(request("12A3", null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID must be Numeric...");
    }

    @Test
    @DisplayName("Non-numeric card number throws the exact numeric-edit message")
    void nonNumericCardThrows() {
        assertThatThrownBy(() -> service().addTransaction(request(null, "4111-1111", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Number must be Numeric...");
    }

    @Test
    @DisplayName("Account id not found in the cross-reference throws the source not-found message")
    void accountNotFoundThrows() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of());
        assertThatThrownBy(() -> service().addTransaction(request("123", null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID NOT found...");
    }

    @Test
    @DisplayName("Card number not found in the cross-reference throws the source not-found message")
    void cardNotFoundThrows() {
        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().addTransaction(request(null, CARD, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Number NOT found...");
    }

    // ---- Add flow: id generation, amount scale, confirmation, duplicate ---------------------

    @Test
    @DisplayName("Successful add generates the next id, normalizes the amount, and echoes all fields")
    void successfulAddPopulatesResponse() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000001");
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionAddResponse response = service().addTransaction(request("123", null, "Y"));

        assertThat(response.transactionId()).isEqualTo("0000000000000001");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(response.categoryCode()).isEqualTo("0001");
        assertThat(response.merchantId()).isEqualTo("123456789");
        assertThat(response.typeCode()).isEqualTo("01");
        assertThat(response.confirm()).isEqualTo("Y");
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("An unconfirmed flag re-prompts after key+data validation pass")
    void unconfirmedReprompts() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        assertThatThrownBy(() -> service().addTransaction(request("123", null, "")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Confirm to add this transaction...");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("An invalid confirmation value is rejected")
    void invalidConfirmRejected() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        assertThatThrownBy(() -> service().addTransaction(request("123", null, "X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid value. Valid values are (Y/N)...");
    }

    @Test
    @DisplayName("A duplicate generated id surfaces as DuplicateRecordException")
    void duplicateIdThrows() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        stubValidDates();
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000100");
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service().addTransaction(request("123", null, "Y")))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage("Tran ID already exist...");
    }

    @Test
    @DisplayName("A missing required data field is rejected after the key resolves")
    void missingDataFieldRejected() {
        when(cardCrossReferenceRepository.findByXrefAcctId(123L)).thenReturn(List.of(xref(CARD, 123L)));
        TransactionAddRequest req = new TransactionAddRequest("123", null, "", "0001", "POS",
                "desc", new BigDecimal("1.00"), "2023-05-01", "2023-05-02", "123456789",
                "ACME", "City", "12345", "Y");
        assertThatThrownBy(() -> service().addTransaction(req))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Type CD can NOT be empty...");
        verify(transactionRepository, never()).save(any());
    }
}
