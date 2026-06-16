package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import com.carddemo.service.transaction.TransactionAddService;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Unit tests for {@link TransactionAddService} (re-platform of COBOL COTRN02C; source commit 27d6c6f). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService - COTRN02C transaction add (auto-ID factory, validation cascade, confirm gate)")
class TransactionAddServiceTest {

    private static final String EXISTING_MAX_ID = "0000000000000041";
    private static final String NEXT_ID = "0000000000000042";
    private static final String FIRST_ID = "0000000000000001";
    private static final String DEFAULT_ORIGIN_DATE = "2024-01-15";
    private static final String DEFAULT_PROCESS_DATE = "2024-01-16";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private TransactionAddService service;

    // =============================================================================================
    // Phase 2 - HEADLINE auto-ID factory (COTRN02C browse-to-end + increment)
    // =============================================================================================

    @Test
    @DisplayName("Auto-ID: highest existing id is incremented and zero-padded to sixteen digits")
    void autoIdIncrementsHighestAndZeroPads() {
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(validRequest());

        assertThat(response.transactionId()).isEqualTo(NEXT_ID);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTranId()).isEqualTo(NEXT_ID);
    }

    @Test
    @DisplayName("Auto-ID: empty transaction store seeds the first id at 0000000000000001")
    void autoIdEmptyRepoStartsAtOne() {
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(null);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(validRequest());

        assertThat(response.transactionId()).isEqualTo(FIRST_ID);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTranId()).isEqualTo(FIRST_ID);
    }

    @Test
    @DisplayName("Auto-ID: store is browsed to its end (findMaxTranId) before the record is written (save)")
    void autoIdBrowsesBeforeWriting() {
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        service.addTransaction(validRequest());

        InOrder inOrder = inOrder(transactionRepository);
        inOrder.verify(transactionRepository).findMaxTranId();
        inOrder.verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Auto-ID: request contract exposes no client-supplied transaction id (id is always generated)")
    void requestContractHasNoClientSuppliedTransactionId() {
        List<String> componentNames = Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).hasSize(14);
        assertThat(componentNames)
                .noneMatch(name -> name.equalsIgnoreCase("tranId")
                        || name.equalsIgnoreCase("transactionId"));
    }

    // =============================================================================================
    // Phase 3 - Persist field mapping and BigDecimal (scale 2, HALF_EVEN, negatives allowed)
    // =============================================================================================

    @Test
    @DisplayName("Persist: maps every field including the card number, and echoes the account id in the response")
    void persistMapsFieldsIncludingCardNumber() {
        TransactionAddRequest request = validRequest();
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(request);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getTranId()).isEqualTo(NEXT_ID);
        assertThat(saved.getTranCardNum()).isEqualTo(request.cardNumber());
        assertThat(saved.getTranTypeCd()).isEqualTo(request.typeCode());
        assertThat(saved.getTranCatCd()).isEqualTo(1);
        assertThat(saved.getTranSource()).isEqualTo(request.source());
        assertThat(saved.getTranDesc()).isEqualTo(request.description());
        assertThat(saved.getTranMerchantId()).isEqualTo(123456789L);
        assertThat(saved.getTranMerchantName()).isEqualTo(request.merchantName());
        assertThat(saved.getTranMerchantCity()).isEqualTo(request.merchantCity());
        assertThat(saved.getTranMerchantZip()).isEqualTo(request.merchantZip());
        assertThat(saved.getTranOrigTs()).isEqualTo(request.originDate());
        assertThat(saved.getTranProcTs()).isEqualTo(request.processDate());
        assertThat(saved.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(saved.getTranAmt().scale()).isEqualTo(2);

        assertThat(response.transactionId()).isEqualTo(NEXT_ID);
        assertThat(response.accountId()).isEqualTo(request.accountId());
        assertThat(response.cardNumber()).isEqualTo(request.cardNumber());
        assertThat(response.typeCode()).isEqualTo(request.typeCode());
        assertThat(response.categoryCode()).isEqualTo("0001");
        assertThat(response.source()).isEqualTo(request.source());
        assertThat(response.description()).isEqualTo(request.description());
        assertThat(response.originDate()).isEqualTo(request.originDate());
        assertThat(response.processDate()).isEqualTo(request.processDate());
        assertThat(response.merchantId()).isEqualTo("123456789");
        assertThat(response.merchantName()).isEqualTo(request.merchantName());
        assertThat(response.merchantCity()).isEqualTo(request.merchantCity());
        assertThat(response.merchantZip()).isEqualTo(request.merchantZip());
        assertThat(response.confirm()).isEqualTo(request.confirm());
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.amount().scale()).isEqualTo(2);
        assertThat(response.errorMessage()).isNull();
    }

    @ParameterizedTest(name = "amount {0} -> {1} (HALF_EVEN, scale 2)")
    @MethodSource("halfEvenAmountCases")
    @DisplayName("Amount: normalized to scale 2 using HALF_EVEN rounding for both persistence and response")
    void amountNormalizedHalfEven(String input, String expected) {
        TransactionAddRequest request = Req.valid().amount(new BigDecimal(input)).build();
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(request);

        BigDecimal expectedAmount = new BigDecimal(expected);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTranAmt()).isEqualByComparingTo(expectedAmount);
        assertThat(captor.getValue().getTranAmt().scale()).isEqualTo(2);
        assertThat(response.amount()).isEqualByComparingTo(expectedAmount);
        assertThat(response.amount().scale()).isEqualTo(2);
    }

    private static Stream<Arguments> halfEvenAmountCases() {
        return Stream.of(
                Arguments.of("100.5", "100.50"),
                Arguments.of("100.005", "100.00"),
                Arguments.of("100.015", "100.02"),
                Arguments.of("100.004", "100.00"),
                Arguments.of("100.006", "100.01"));
    }

    @Test
    @DisplayName("Amount: a negative amount is accepted and persisted at scale 2 (TRAN-AMT PIC S9(9)V99)")
    void negativeAmountIsAccepted() {
        TransactionAddRequest request = Req.valid().amount(new BigDecimal("-50.00")).build();
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(request);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTranAmt()).isEqualByComparingTo(new BigDecimal("-50.00"));
        assertThat(captor.getValue().getTranAmt().scale()).isEqualTo(2);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("-50.00"));
        assertThat(response.amount().scale()).isEqualTo(2);
    }

    // =============================================================================================
    // Phase 4 - Validation cascade (first-error-wins; nothing is ever persisted on failure)
    // =============================================================================================

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("emptyFieldCases")
    @DisplayName("Validation: an empty mandatory field is rejected with its exact message and nothing is persisted")
    void emptyMandatoryFieldRejected(TransactionAddRequest request, String expectedMessage) {
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    private static Stream<Arguments> emptyFieldCases() {
        return Stream.of(
                Arguments.of(Req.valid().typeCode(null).build(), "Type CD can NOT be empty..."),
                Arguments.of(Req.valid().typeCode("   ").build(), "Type CD can NOT be empty..."),
                Arguments.of(Req.valid().categoryCode(null).build(), "Category CD can NOT be empty..."),
                Arguments.of(Req.valid().source(null).build(), "Source can NOT be empty..."),
                Arguments.of(Req.valid().source("").build(), "Source can NOT be empty..."),
                Arguments.of(Req.valid().description(null).build(), "Description can NOT be empty..."),
                Arguments.of(Req.valid().amount(null).build(), "Amount can NOT be empty..."),
                Arguments.of(Req.valid().originDate(null).build(), "Orig Date can NOT be empty..."),
                Arguments.of(Req.valid().processDate(null).build(), "Proc Date can NOT be empty..."),
                Arguments.of(Req.valid().merchantId(null).build(), "Merchant ID can NOT be empty..."),
                Arguments.of(Req.valid().merchantName(null).build(), "Merchant Name can NOT be empty..."),
                Arguments.of(Req.valid().merchantCity(null).build(), "Merchant City can NOT be empty..."),
                Arguments.of(Req.valid().merchantZip(null).build(), "Merchant Zip can NOT be empty..."),
                Arguments.of(Req.valid().merchantZip("   ").build(), "Merchant Zip can NOT be empty..."));
    }

    @Test
    @DisplayName("Validation: a non-numeric type code is rejected before any date or repository interaction")
    void typeCodeNonNumericRejected() {
        TransactionAddRequest request = Req.valid().typeCode("AB").build();
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Type CD must be Numeric...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Validation: a non-numeric category code is rejected before any date or repository interaction")
    void categoryCodeNonNumericRejected() {
        TransactionAddRequest request = Req.valid().categoryCode("AB").build();
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Category CD must be Numeric...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @ParameterizedTest(name = "originDate=[{0}]")
    @ValueSource(strings = {"01/15/2024", "2024/01/15", "2024-01/15", "2024-AB-15", "2024-01-AB", "2024-1-15",
            "2024-01-1", "20240115"})
    @DisplayName("Validation: an origin date not in YYYY-MM-DD positional format is rejected (no trailing dots)")
    void originDateBadFormatRejected(String badOriginDate) {
        TransactionAddRequest request = Req.valid().originDate(badOriginDate).build();
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Orig Date should be in format YYYY-MM-DD");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @ParameterizedTest(name = "processDate=[{0}]")
    @ValueSource(strings = {"2024/01/16", "01-16-2024", "2024-01-1", "2024-01-160"})
    @DisplayName("Validation: a process date not in YYYY-MM-DD positional format is rejected (no trailing dots)")
    void processDateBadFormatRejected(String badProcessDate) {
        TransactionAddRequest request = Req.valid().processDate(badProcessDate).build();
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Proc Date should be in format YYYY-MM-DD");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Validation: a well-formatted but invalid origin date is rejected via DateValidationService")
    void originDateInvalidRejected() {
        TransactionAddRequest request = Req.valid().originDate("2024-13-40").build();
        when(dateValidationService.validateDate(eq("2024-13-40"), anyString()))
                .thenReturn(notAcceptable("2024-13-40"));

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Orig Date - Not a valid date...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Validation: a well-formatted but invalid process date is rejected after a valid origin date")
    void processDateInvalidRejected() {
        TransactionAddRequest request = Req.valid().processDate("2024-02-30").build();
        when(dateValidationService.validateDate(eq(DEFAULT_ORIGIN_DATE), anyString()))
                .thenReturn(acceptable(DEFAULT_ORIGIN_DATE));
        when(dateValidationService.validateDate(eq("2024-02-30"), anyString()))
                .thenReturn(notAcceptable("2024-02-30"));

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Proc Date - Not a valid date...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Validation: a non-numeric merchant id is rejected last, after both dates pass validation")
    void merchantIdNonNumericRejected() {
        TransactionAddRequest request = Req.valid().merchantId("12A456789").build();
        stubValidDates();

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Merchant ID must be Numeric...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Validation: with multiple invalid fields, the first cascade check (type code) wins")
    void firstErrorWinsAcrossCascade() {
        TransactionAddRequest request = Req.valid().typeCode(null).source(null).build();
        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Type CD can NOT be empty...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // =============================================================================================
    // Phase 5 - Confirmation gate and duplicate-key mapping
    // =============================================================================================

    @ParameterizedTest(name = "confirm=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   ", "N", "n"})
    @DisplayName("Confirm gate: absent/blank/N/n requires confirmation and persists nothing")
    void confirmAbsentOrNegativeRequiresConfirmation(String confirm) {
        TransactionAddRequest request = Req.valid().confirm(confirm).build();
        stubValidDates();

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Confirm to add this transaction...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Confirm gate: an invalid confirm value reports the (Y/N) message and persists nothing")
    void confirmInvalidValueRejected() {
        TransactionAddRequest request = Req.valid().confirm("X").build();
        stubValidDates();

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid value. Valid values are (Y/N)...");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Confirm gate: a lowercase 'y' is accepted and the transaction proceeds to persistence")
    void confirmLowercaseYProceeds() {
        TransactionAddRequest request = Req.valid().confirm("y").build();
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        stubSaveEchoesArgument();

        TransactionAddResponse response = service.addTransaction(request);

        assertThat(response.transactionId()).isEqualTo(NEXT_ID);
        assertThat(response.confirm()).isEqualTo("y");
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Duplicate: a DataIntegrityViolationException on save is mapped to DuplicateRecordException")
    void duplicateKeyOnSaveMapsToDuplicateRecordException() {
        TransactionAddRequest request = validRequest();
        stubValidDates();
        when(transactionRepository.findMaxTranId()).thenReturn(EXISTING_MAX_ID);
        DataIntegrityViolationException cause = new DataIntegrityViolationException("duplicate key");
        when(transactionRepository.save(any(Transaction.class))).thenThrow(cause);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage("Tran ID already exist...")
                .hasCause(cause);
    }

    // =============================================================================================
    // Phase 1 / Phase 6 - Fixtures, stubbing helpers, and a fully-valid request builder
    // =============================================================================================

    private static TransactionAddRequest validRequest() {
        return Req.valid().build();
    }

    /** Stubs both default request dates as calendar-valid (reaches persistence on the happy path). */
    private void stubValidDates() {
        when(dateValidationService.validateDate(eq(DEFAULT_ORIGIN_DATE), anyString()))
                .thenReturn(acceptable(DEFAULT_ORIGIN_DATE));
        when(dateValidationService.validateDate(eq(DEFAULT_PROCESS_DATE), anyString()))
                .thenReturn(acceptable(DEFAULT_PROCESS_DATE));
    }

    /** Makes {@code save} echo its argument so the response observes the generated identifier. */
    private void stubSaveEchoesArgument() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(returnsFirstArg());
    }

    private static DateValidationResult acceptable(String date) {
        return new DateValidationResult(true, 0, 0, "Date is valid", date, "YYYY-MM-DD");
    }

    private static DateValidationResult notAcceptable(String date) {
        return new DateValidationResult(false, 3, 2508, "Datevalue error", date, "YYYY-MM-DD");
    }

    /**
     * Mutable builder for a {@link TransactionAddRequest} pre-populated with a fully valid payload so
     * that each test varies exactly one field while every other field remains acceptable.
     */
    private static final class Req {

        private String accountId = "00000000001";
        private String cardNumber = "1234567890123456";
        private String typeCode = "01";
        private String categoryCode = "0001";
        private String source = "POS";
        private String description = "GROCERIES";
        private BigDecimal amount = new BigDecimal("100.00");
        private String originDate = DEFAULT_ORIGIN_DATE;
        private String processDate = DEFAULT_PROCESS_DATE;
        private String merchantId = "123456789";
        private String merchantName = "ACME";
        private String merchantCity = "DALLAS";
        private String merchantZip = "75001";
        private String confirm = "Y";

        private static Req valid() {
            return new Req();
        }

        private Req typeCode(String value) {
            this.typeCode = value;
            return this;
        }

        private Req categoryCode(String value) {
            this.categoryCode = value;
            return this;
        }

        private Req source(String value) {
            this.source = value;
            return this;
        }

        private Req description(String value) {
            this.description = value;
            return this;
        }

        private Req amount(BigDecimal value) {
            this.amount = value;
            return this;
        }

        private Req originDate(String value) {
            this.originDate = value;
            return this;
        }

        private Req processDate(String value) {
            this.processDate = value;
            return this;
        }

        private Req merchantId(String value) {
            this.merchantId = value;
            return this;
        }

        private Req merchantName(String value) {
            this.merchantName = value;
            return this;
        }

        private Req merchantCity(String value) {
            this.merchantCity = value;
            return this;
        }

        private Req merchantZip(String value) {
            this.merchantZip = value;
            return this;
        }

        private Req confirm(String value) {
            this.confirm = value;
            return this;
        }

        private TransactionAddRequest build() {
            return new TransactionAddRequest(accountId, cardNumber, typeCode, categoryCode,
                    source, description, amount, originDate, processDate, merchantId,
                    merchantName, merchantCity, merchantZip, confirm);
        }
    }
}
