package com.cardemo.unit.service;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardUpdateService;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardUpdateService} — migrated from COCRDUPC.cbl (1,560 lines).
 *
 * <p>Tests cover optimistic concurrency via JPA {@code @Version}, field-by-field validation
 * in COCRDUPC order (acctId→cardNum→embossedName→activeStatus→expiryMonth→expiryYear),
 * UPPER-CASE before/after image comparison for change detection, immutable key field
 * enforcement (cardNum and acctId never modified), and all error handling paths.</p>
 *
 * <p>Pure Mockito unit tests — NO Spring context loading.</p>
 *
 * @see CardUpdateService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService — COCRDUPC.cbl card update unit tests")
class CardUpdateServiceTest {

    /* ---------- Constants matching valid CARD-RECORD values (CVACT02Y.cpy 150-byte layout) ---------- */

    private static final String VALID_CARD_NUM = "4111111111111111";
    private static final String VALID_ACCT_ID = "00000000001";
    private static final String VALID_CVV = "123";
    private static final String VALID_NAME = "JOHN DOE";
    private static final LocalDate VALID_EXP_DATE = LocalDate.of(2025, 12, 1);
    private static final String VALID_STATUS = "Y";

    /* ---------- Mocked dependencies ---------- */

    @Mock
    private CardRepository cardRepository;

    @Mock
    private AccountRepository accountRepository;

    /* ---------- Service under test ---------- */

    @InjectMocks
    private CardUpdateService cardUpdateService;

    /* ---------- Test fixtures ---------- */

    private Card testCard;
    private CardDto validDto;

    /**
     * Initialise reusable test fixtures before each test method.
     * <p>The card entity mirrors a valid CARD-RECORD as defined in CVACT02Y.cpy, and
     * the DTO mirrors the BMS symbolic map fields from COCRDUP.CPY.</p>
     */
    @BeforeEach
    void setUp() {
        // Construct a valid Card entity (database record)
        testCard = new Card();
        testCard.setCardNum(VALID_CARD_NUM);
        testCard.setCardAcctId(VALID_ACCT_ID);
        testCard.setCardCvvCd(VALID_CVV);
        testCard.setCardEmbossedName(VALID_NAME);
        testCard.setCardExpDate(VALID_EXP_DATE);
        testCard.setCardActiveStatus(VALID_STATUS);
        testCard.setVersion(1);

        // Construct a valid CardDto (update request matching the entity exactly)
        validDto = new CardDto();
        validDto.setCardNum(VALID_CARD_NUM);
        validDto.setCardAcctId(VALID_ACCT_ID);
        validDto.setCardCvvCd(VALID_CVV);
        validDto.setCardEmbossedName(VALID_NAME);
        validDto.setCardExpDate(VALID_EXP_DATE);
        validDto.setCardActiveStatus(VALID_STATUS);
    }

    // ============================================================================================
    // GET CARD FOR UPDATE (paragraphs 9000-READ-CARD)
    // ============================================================================================

    @Test
    @DisplayName("getCardForUpdate — success: returns CardDto with all 6 fields populated (9000-READ-CARD)")
    void testGetCardForUpdate_success() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.existsById(VALID_ACCT_ID)).thenReturn(true);

        CardDto result = cardUpdateService.getCardForUpdate(VALID_CARD_NUM);

        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(VALID_CARD_NUM);
        assertThat(result.getCardAcctId()).isEqualTo(VALID_ACCT_ID);
        assertThat(result.getCardCvvCd()).isEqualTo(VALID_CVV);
        assertThat(result.getCardEmbossedName()).isEqualTo(VALID_NAME);
        assertThat(result.getCardExpDate()).isEqualTo(VALID_EXP_DATE);
        assertThat(result.getCardActiveStatus()).isEqualTo(VALID_STATUS);
    }

    @Test
    @DisplayName("getCardForUpdate — not found: throws RecordNotFoundException (FILE STATUS 23 / DFHRESP(NOTFND))")
    void testGetCardForUpdate_notFound_throwsRecordNotFound() {
        when(cardRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardUpdateService.getCardForUpdate("9999999999999999"))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // ============================================================================================
    // FIELD VALIDATION (COCRDUPC order: acctId → cardNum → embossedName → activeStatus → expiry)
    // Maps COBOL paragraph 1100-VALIDATE-CARD-DATA accumulated error pattern.
    // ============================================================================================

    @Test
    @DisplayName("updateCard — blank acctId throws ValidationException (1210-EDIT-ACCOUNT)")
    void testUpdateCard_blankAcctId_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardAcctId("   ");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).isNotEmpty();
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "acctId".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — non-numeric acctId throws ValidationException (1210-EDIT-ACCOUNT)")
    void testUpdateCard_nonNumericAcctId_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardAcctId("ABC12345678");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "acctId".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — blank cardNum throws ValidationException (1220-EDIT-CARD)")
    void testUpdateCard_blankCardNum_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardNum("");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "cardNum".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — non-numeric cardNum throws ValidationException (1220-EDIT-CARD)")
    void testUpdateCard_nonNumericCardNum_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardNum("ABCD1234EFGH5678");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "cardNum".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — invalid embossedName (non-alpha/space chars) throws ValidationException (1230-EDIT-NAME)")
    void testUpdateCard_invalidEmbossedName_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardEmbossedName("JOHN DOE 123!");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "embossedName".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — invalid activeStatus (not Y/N) throws ValidationException (1240-EDIT-CARDSTATUS)")
    void testUpdateCard_invalidActiveStatus_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        dto.setCardActiveStatus("X");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "activeStatus".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — invalid expiry month (null expDate → month error) throws ValidationException (1250-EDIT-EXPIRY-MON)")
    void testUpdateCard_invalidExpiryMonth_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        // Null expDate triggers both month and year errors; we assert the month error is present.
        // Java LocalDate enforces 1-12 for months so a null date is the canonical trigger.
        dto.setCardExpDate(null);

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "expMonth".equals(e.fieldName()));
                });
    }

    @Test
    @DisplayName("updateCard — invalid expiry year (>2099) throws ValidationException (1260-EDIT-EXPIRY-YEAR)")
    void testUpdateCard_invalidExpiryYear_throwsValidation() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));

        CardDto dto = createValidDto();
        // Year 2100 exceeds the COBOL-parity upper bound of 2099
        dto.setCardExpDate(LocalDate.of(2100, 6, 1));

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(
                            e -> "expYear".equals(e.fieldName()));
                });
    }

    // ============================================================================================
    // CHANGE DETECTION (COBOL UPPER-CASE before/after image comparison — paragraph 1200-CHECK-FOR-CHANGES)
    // ============================================================================================

    @Test
    @DisplayName("updateCard — no changes detected: returns unchanged DTO, save NOT called (1200-CHECK-FOR-CHANGES)")
    void testUpdateCard_noChanges_returnsUnchanged() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));

        // validDto has identical values to testCard — no change should be detected
        CardDto result = cardUpdateService.updateCard(VALID_CARD_NUM, validDto);

        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(VALID_CARD_NUM);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard — with changes: saves and returns updated DTO (9200-WRITE-PROCESSING)")
    void testUpdateCard_withChanges_savesAndReturnsUpdated() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto dto = createValidDto();
        dto.setCardEmbossedName("JANE SMITH");

        CardDto result = cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        assertThat(result).isNotNull();
        assertThat(result.getCardEmbossedName()).isEqualTo("JANE SMITH");
        verify(cardRepository).save(any(Card.class));
    }

    // ============================================================================================
    // OPTIMISTIC CONCURRENCY (paragraph 9300-CHECK-CHANGE-IN-REC — @Version mismatch)
    // ============================================================================================

    @Test
    @DisplayName("updateCard — optimistic lock conflict: throws ConcurrentModificationException (9300-CHECK-CHANGE-IN-REC)")
    void testUpdateCard_optimisticLockConflict_throwsConcurrentModification() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenThrow(new OptimisticLockException("version mismatch"));

        CardDto dto = createValidDto();
        dto.setCardEmbossedName("CHANGED NAME");

        assertThatThrownBy(() -> cardUpdateService.updateCard(VALID_CARD_NUM, dto))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    // ============================================================================================
    // IMMUTABLE FIELDS (cardNum and acctId are never modified during COCRDUPC update)
    // ============================================================================================

    @Test
    @DisplayName("updateCard — cardNum is never modified during update (immutable primary key)")
    void testUpdateCard_cardNumNeverModified() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto dto = createValidDto();
        dto.setCardEmbossedName("MODIFIED NAME"); // Ensure a change so save is triggered

        cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        // Verify saved card retains the original cardNum
        verify(cardRepository).save(argThat(card ->
                VALID_CARD_NUM.equals(card.getCardNum())));
    }

    @Test
    @DisplayName("updateCard — acctId is never modified during update (immutable foreign key)")
    void testUpdateCard_acctIdNeverModified() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto dto = createValidDto();
        dto.setCardEmbossedName("MODIFIED NAME"); // Ensure a change triggers save

        cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        // Verify saved card retains the original acctId
        verify(cardRepository).save(argThat(card ->
                VALID_ACCT_ID.equals(card.getCardAcctId())));
    }

    // ============================================================================================
    // SUCCESSFUL UPDATE (all mutable fields mapped correctly)
    // ============================================================================================

    @Test
    @DisplayName("updateCard — valid update maps all mutable fields (cvvCd, embossedName, expDate, activeStatus)")
    void testUpdateCard_validUpdate_allFieldsMapped() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto dto = createValidDto();
        dto.setCardCvvCd("456");
        dto.setCardEmbossedName("UPDATED NAME");
        dto.setCardExpDate(LocalDate.of(2026, 6, 1));
        dto.setCardActiveStatus("N");

        CardDto result = cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        assertThat(result).isNotNull();
        assertThat(result.getCardCvvCd()).isEqualTo("456");
        assertThat(result.getCardEmbossedName()).isEqualTo("UPDATED NAME");
        assertThat(result.getCardExpDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.getCardActiveStatus()).isEqualTo("N");
        // Immutable key fields preserved
        assertThat(result.getCardNum()).isEqualTo(VALID_CARD_NUM);
        assertThat(result.getCardAcctId()).isEqualTo(VALID_ACCT_ID);
    }

    @Test
    @DisplayName("updateCard — embossed name UPPER-CASE comparison: same name in different case triggers no change (FUNCTION UPPER-CASE)")
    void testUpdateCard_embossedNameUpperCase() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));

        // testCard has "JOHN DOE"; DTO has "john doe" (same when uppercased)
        CardDto dto = createValidDto();
        dto.setCardEmbossedName("john doe");

        CardDto result = cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        // UPPER-CASE comparison detects no change — save should NOT be called
        assertThat(result).isNotNull();
        verify(cardRepository, never()).save(any(Card.class));
    }

    // ============================================================================================
    // VERIFICATION (explicit Mockito verify assertions)
    // ============================================================================================

    @Test
    @DisplayName("updateCard — verify save() called exactly once when changes detected")
    void testUpdateCard_verifySaveCalled() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto dto = createValidDto();
        dto.setCardActiveStatus("N"); // Change active status to trigger save

        cardUpdateService.updateCard(VALID_CARD_NUM, dto);

        verify(cardRepository, times(1)).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard — verify save() NOT called when no changes detected")
    void testUpdateCard_verifySaveNotCalled_noChanges() {
        when(cardRepository.findById(VALID_CARD_NUM)).thenReturn(Optional.of(testCard));
        when(accountRepository.findById(VALID_ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));

        // DTO matches testCard exactly — no changes
        cardUpdateService.updateCard(VALID_CARD_NUM, validDto);

        verify(cardRepository, never()).save(any(Card.class));
    }

    // ============================================================================================
    // HELPER METHODS
    // ============================================================================================

    /**
     * Creates a fresh valid CardDto with all fields populated to match the test entity.
     * Each test can mutate individual fields to test specific validation or change detection scenarios.
     */
    private CardDto createValidDto() {
        CardDto dto = new CardDto();
        dto.setCardNum(VALID_CARD_NUM);
        dto.setCardAcctId(VALID_ACCT_ID);
        dto.setCardCvvCd(VALID_CVV);
        dto.setCardEmbossedName(VALID_NAME);
        dto.setCardExpDate(VALID_EXP_DATE);
        dto.setCardActiveStatus(VALID_STATUS);
        return dto;
    }
}
