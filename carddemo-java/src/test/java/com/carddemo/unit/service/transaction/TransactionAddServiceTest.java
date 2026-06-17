package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.transaction.TransactionAddService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TransactionAddService} focused on the CP3 CRITICAL finding: the
 * {@code COTRN02C} account/card key-field validation and cross-reference linkage that the
 * service previously omitted.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the
 * tests assert the bindings of {@code COTRN02C.VALIDATE-INPUT-KEY-FIELDS} +
 * {@code READ-CXACAIX-FILE} / {@code READ-CCXREF-FILE}:
 * <ul>
 *   <li>key validation runs <em>before</em> data-field validation;</li>
 *   <li>account-id-first precedence resolves the authoritative card number from the
 *       cross-reference and persists it (never an arbitrary client-supplied card);</li>
 *   <li>a card-only request adopts the cross-reference account id;</li>
 *   <li>missing / non-numeric / unresolvable keys raise the exact COBOL messages; and</li>
 *   <li>the posted amount increments {@code carddemo.transaction.amount.total}.</li>
 * </ul>
 * The repositories are mocked; {@link DateValidationService} is used for real (it is
 * dependency-free and the dates supplied are valid); the {@code MeterRegistry} is a real
 * {@link SimpleMeterRegistry}.</p>
 */
@DisplayName("TransactionAddService - COTRN02C key validation, cross-reference linkage, amount metric")
class TransactionAddServiceTest {

    private TransactionRepository transactionRepository;
    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private SimpleMeterRegistry registry;
    private TransactionAddService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        registry = new SimpleMeterRegistry();
        service = new TransactionAddService(transactionRepository, cardCrossReferenceRepository,
                new DateValidationService(), registry);
    }

    @Test
    @DisplayName("account-id-first: resolves and persists the cross-reference card, overriding the supplied card")
    void accountIdFirst_resolvesAndPersistsCrossReferenceCard() {
        // Account id supplied alongside a *different* card number; account-id-first precedence must
        // resolve the authoritative card from the cross-reference and persist that one.
        when(cardCrossReferenceRepository.findByXrefAcctId(100L))
                .thenReturn(List.of(xref("1111222233334444", 7L, 100L)));
        when(transactionRepository.findMaxTranId()).thenReturn(null);
        when(transactionRepository.insertNew(any(Transaction.class))).then(returnsFirstArg());

        TransactionAddResponse response =
                service.addTransaction(request("100", "9999888877776666", "Y"));

        assertThat(response.errorMessage()).isNull();
        assertThat(response.accountId()).isEqualTo("100");
        assertThat(response.cardNumber()).isEqualTo("1111222233334444");
        assertThat(response.transactionId()).isEqualTo("0000000000000001");

        // The persisted transaction carries the resolved card, not the client-supplied "9999...".
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).insertNew(saved.capture());
        assertThat(saved.getValue().getTranCardNum()).isEqualTo("1111222233334444");

        // The posted amount increments the running-total metric.
        assertThat(registry.counter(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).count())
                .isCloseTo(12.34, within(1e-9));
    }

    @Test
    @DisplayName("card-only: adopts the cross-reference account id and persists the supplied card")
    void cardOnly_adoptsCrossReferenceAccountId() {
        when(cardCrossReferenceRepository.findById("1111222233334444"))
                .thenReturn(Optional.of(xref("1111222233334444", 7L, 200L)));
        when(transactionRepository.findMaxTranId()).thenReturn("0000000000000005");
        when(transactionRepository.insertNew(any(Transaction.class))).then(returnsFirstArg());

        TransactionAddResponse response =
                service.addTransaction(request(null, "1111222233334444", "Y"));

        assertThat(response.errorMessage()).isNull();
        assertThat(response.accountId()).isEqualTo("200");
        assertThat(response.cardNumber()).isEqualTo("1111222233334444");
        assertThat(response.transactionId()).isEqualTo("0000000000000006");
    }

    @Test
    @DisplayName("neither key supplied: 'Account or Card Number must be entered...' and nothing persisted")
    void neitherKey_raisesKeyRequired() {
        assertThatThrownBy(() -> service.addTransaction(request(null, null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account or Card Number must be entered...");
        verify(transactionRepository, never()).insertNew(any());
    }

    @Test
    @DisplayName("key validation runs BEFORE data-field validation")
    void keyValidationPrecedesDataValidation() {
        // Both the key and a data field (type code) are invalid; the COBOL ordering requires the
        // key error to win because VALIDATE-INPUT-KEY-FIELDS runs before VALIDATE-INPUT-DATA-FIELDS.
        TransactionAddRequest bothInvalid = new TransactionAddRequest(
                null, null, "", "0001", "POS", "GROCERIES", new BigDecimal("12.34"),
                "2024-01-15", "2024-01-16", "000000123", "ACME", "NYC", "10001", "Y");

        assertThatThrownBy(() -> service.addTransaction(bothInvalid))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account or Card Number must be entered...");
    }

    @Test
    @DisplayName("account id with no cross-reference: 'Account ID NOT found...'")
    void accountIdNotFound_raisesRecordNotFound() {
        when(cardCrossReferenceRepository.findByXrefAcctId(999L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addTransaction(request("999", null, "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...");
        verify(transactionRepository, never()).insertNew(any());
    }

    @Test
    @DisplayName("card number with no cross-reference: 'Card Number NOT found...'")
    void cardNumberNotFound_raisesRecordNotFound() {
        when(cardCrossReferenceRepository.findById("1111222233334444"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTransaction(request(null, "1111222233334444", "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Card Number NOT found...");
        verify(transactionRepository, never()).insertNew(any());
    }

    @Test
    @DisplayName("non-numeric account id: 'Account ID must be Numeric...'")
    void nonNumericAccountId_raisesValidation() {
        assertThatThrownBy(() -> service.addTransaction(request("ABC", null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID must be Numeric...");
        verify(transactionRepository, never()).insertNew(any());
    }

    // ---------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------

    private static TransactionAddRequest request(String accountId, String cardNumber,
            String confirm) {
        return new TransactionAddRequest(
                accountId, cardNumber, "01", "0001", "POS", "GROCERIES",
                new BigDecimal("12.34"), "2024-01-15", "2024-01-16",
                "000000123", "ACME", "NYC", "10001", confirm);
    }

    private static CardCrossReference xref(String cardNumber, Long custId, Long acctId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefCustId(custId);
        xref.setXrefAcctId(acctId);
        return xref;
    }
}
