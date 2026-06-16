package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardUpdateRequest;
import com.carddemo.model.dto.CardUpdateResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.shared.DateValidationService;
import jakarta.persistence.OptimisticLockException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link com.carddemo.service.card.CardUpdateService} (optimistic-locked card update;
 * behavioral parity with COBOL program COCRDUPC at source commit 27d6c6f). Pure Mockito/JVM test;
 * rationale in DECISION_LOG.md.
 */
@ExtendWith(MockitoExtension.class)
class CardUpdateServiceTest {

  private static final String VALID_ACCOUNT = "12345678901";
  private static final String VALID_CARD = "1234567890123456";
  private static final String CONCURRENCY_MSG = "Record changed by some one else. Please review";

  @Mock private CardRepository cardRepository;

  @Mock private DateValidationService dateValidationService;

  @InjectMocks private com.carddemo.service.card.CardUpdateService cardUpdateService;

  private static Card existingCard() {
    Card card = new Card();
    card.setCardNum(VALID_CARD);
    card.setCardAcctId(12345678901L);
    card.setCardEmbossedName("JOHN DOE");
    card.setCardActiveStatus("Y");
    card.setCardExpiraionDate("2020-05-15");
    card.setVersion(0L);
    return card;
  }

  private static CardUpdateRequest request(String name, String status, String month, String year, String day) {
    // version 0L matches the existingCard() fixture, so the optimistic-concurrency
    // guard is transparent for these behavior tests; the stale-version case is
    // exercised separately by staleClientVersionRejected().
    return new CardUpdateRequest(0L, VALID_ACCOUNT, VALID_CARD, name, status, month, year, day);
  }

  private static DateValidationService.DateValidationResult acceptable() {
    return new DateValidationService.DateValidationResult(true, 0, 0, "Date is valid", "", "");
  }

  private static DateValidationService.DateValidationResult notAcceptable() {
    return new DateValidationService.DateValidationResult(false, 3, 2508, "Datevalue error", "", "");
  }

  // ----- search-key validation (before any repository read) -----

  @Test
  @DisplayName("both keys blank -> 'No input received'")
  void bothKeysBlank() {
    CardUpdateRequest req = new CardUpdateRequest(0L, "", "", "JANE DOE", "Y", "06", "2025", "15");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("No input received");
    verifyNoInteractions(cardRepository, dateValidationService);
  }

  @Test
  @DisplayName("account blank, card present -> 'Account number not provided'")
  void accountBlank() {
    CardUpdateRequest req = new CardUpdateRequest(0L, "", VALID_CARD, "JANE DOE", "N", "06", "2025", "15");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Account number not provided");
    verifyNoInteractions(cardRepository, dateValidationService);
  }

  @Test
  @DisplayName("account not 11 digits -> account filter message")
  void accountWrongLength() {
    CardUpdateRequest req = new CardUpdateRequest(0L, "123", VALID_CARD, "JANE DOE", "N", "06", "2025", "15");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository, dateValidationService);
  }

  @Test
  @DisplayName("card blank -> 'Card number not provided'")
  void cardBlank() {
    CardUpdateRequest req = new CardUpdateRequest(0L, VALID_ACCOUNT, "", "JANE DOE", "N", "06", "2025", "15");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card number not provided");
    verifyNoInteractions(cardRepository, dateValidationService);
  }

  @Test
  @DisplayName("card not 16 digits -> card filter message")
  void cardWrongLength() {
    CardUpdateRequest req = new CardUpdateRequest(0L, VALID_ACCOUNT, "123", "JANE DOE", "N", "06", "2025", "15");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository, dateValidationService);
  }

  // ----- record lookup -----

  @Test
  @DisplayName("valid keys but no record -> RecordNotFoundException, no save")
  void recordNotFound() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.empty());
    CardUpdateRequest req = request("JANE DOE", "N", "06", "2025", "15");

    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(RecordNotFoundException.class)
        .hasMessage("Did not find cards for this search condition");

    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  // ----- no-change early return (no save, no date validation) -----

  @Test
  @DisplayName("no changes (case-insensitive name/status, numeric month/year) -> early return, no save")
  void noChangesDetected() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("john doe", "y", "5", "2020", "99");

    CardUpdateResponse response = cardUpdateService.updateCard(req);

    assertThat(response.infoMessage()).isEqualTo("No change detected with respect to values fetched.");
    assertThat(response.errorMessage()).isNull();
    assertThat(response.accountId()).isEqualTo(VALID_ACCOUNT);
    assertThat(response.cardNumber()).isEqualTo(VALID_CARD);
    assertThat(response.nameOnCard()).isEqualTo("JOHN DOE");
    assertThat(response.cardStatus()).isEqualTo("Y");
    assertThat(response.expirationMonth()).isEqualTo("05");
    assertThat(response.expirationYear()).isEqualTo("2020");
    assertThat(response.expirationDay()).isEqualTo("15");

    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  // ----- happy path -----

  @Test
  @DisplayName("valid changes -> persists new values, existing expiry day preserved, success message")
  void happyPathUpdate() {
    Card entity = existingCard();
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(entity));
    when(dateValidationService.validateDate(anyString(), eq("YYYY-MM-DD"))).thenReturn(acceptable());

    CardUpdateRequest req = request("JANE ROE", "N", "06", "2025", "01");

    CardUpdateResponse response = cardUpdateService.updateCard(req);

    assertThat(response.accountId()).isEqualTo(VALID_ACCOUNT);
    assertThat(response.cardNumber()).isEqualTo(VALID_CARD);
    assertThat(response.nameOnCard()).isEqualTo("JANE ROE");
    assertThat(response.cardStatus()).isEqualTo("N");
    assertThat(response.expirationMonth()).isEqualTo("06");
    assertThat(response.expirationYear()).isEqualTo("2025");
    assertThat(response.expirationDay()).isEqualTo("15");
    assertThat(response.infoMessage()).isEqualTo("Changes committed to database");
    assertThat(response.errorMessage()).isNull();

    ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
    verify(dateValidationService).validateDate(dateCaptor.capture(), eq("YYYY-MM-DD"));
    assertThat(dateCaptor.getValue()).isEqualTo("2025-06-15");

    ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
    verify(cardRepository).saveAndFlush(cardCaptor.capture());
    Card saved = cardCaptor.getValue();
    assertThat(saved.getCardEmbossedName()).isEqualTo("JANE ROE");
    assertThat(saved.getCardActiveStatus()).isEqualTo("N");
    assertThat(saved.getCardExpiraionDate()).isEqualTo("2025-06-15");

    verify(cardRepository).findById(VALID_CARD);
  }

  // ----- optimistic locking (both exception types -> ConcurrencyException 'Card') -----

  @Test
  @DisplayName("Spring ObjectOptimisticLockingFailureException on save -> ConcurrencyException('Card')")
  void optimisticLockSpringException() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    when(dateValidationService.validateDate(anyString(), eq("YYYY-MM-DD"))).thenReturn(acceptable());
    ObjectOptimisticLockingFailureException lockEx =
        new ObjectOptimisticLockingFailureException(Card.class, VALID_CARD);
    when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(lockEx);

    CardUpdateRequest req = request("JANE ROE", "N", "06", "2025", "01");

    ConcurrencyException thrown =
        assertThrows(ConcurrencyException.class, () -> cardUpdateService.updateCard(req));
    assertThat(thrown.getMessage()).isEqualTo(CONCURRENCY_MSG);
    assertThat(thrown.getEntity()).isEqualTo("Card");
    assertThat(thrown.getCause()).isSameAs(lockEx);
  }

  @Test
  @DisplayName("jakarta OptimisticLockException on save -> ConcurrencyException('Card')")
  void optimisticLockJakartaException() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    when(dateValidationService.validateDate(anyString(), eq("YYYY-MM-DD"))).thenReturn(acceptable());
    OptimisticLockException lockEx = new OptimisticLockException("version conflict");
    when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(lockEx);

    CardUpdateRequest req = request("JANE ROE", "N", "06", "2025", "01");

    ConcurrencyException thrown =
        assertThrows(ConcurrencyException.class, () -> cardUpdateService.updateCard(req));
    assertThat(thrown.getMessage()).isEqualTo(CONCURRENCY_MSG);
    assertThat(thrown.getEntity()).isEqualTo("Card");
    assertThat(thrown.getCause()).isSameAs(lockEx);
  }

  // ----- client-supplied stale version (before/after-image check; COCRDUPC parity) -----

  @Test
  @DisplayName("stale client version (echoed != persisted) -> ConcurrencyException('Card'), no save, no date validation")
  void staleClientVersionRejected() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    // Persisted card is version 0L; the client echoes a stale 5L, modelling an edit
    // made against an out-of-date read. The guard must reject before any mutation.
    CardUpdateRequest req =
        new CardUpdateRequest(5L, VALID_ACCOUNT, VALID_CARD, "JANE ROE", "N", "06", "2025", "01");

    ConcurrencyException thrown =
        assertThrows(ConcurrencyException.class, () -> cardUpdateService.updateCard(req));
    assertThat(thrown.getMessage()).isEqualTo(CONCURRENCY_MSG);
    assertThat(thrown.getEntity()).isEqualTo("Card");
    assertThat(thrown.getCause()).isNull();

    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  // ----- ordered field validation (first-error-wins; reached only when record changed) -----

  @Test
  @DisplayName("blank card name -> 'Card name not provided'")
  void nameBlank() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("", "N", "06", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card name not provided");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("card name with digits -> 'Card name can only contain alphabets and spaces'")
  void nameNonAlpha() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE2 ROE", "N", "06", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card name can only contain alphabets and spaces");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("invalid card status -> 'Card Active Status must be Y or N'")
  void statusInvalid() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE ROE", "X", "06", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card Active Status must be Y or N");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("lowercase 'y' card status rejected at edit stage (no implicit uppercasing)")
  void statusLowercaseRejected() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE ROE", "y", "06", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card Active Status must be Y or N");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("expiry month 13 -> month range message")
  void monthOutOfRange() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE ROE", "N", "13", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card expiry month must be between 1 and 12");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("non-numeric expiry month -> month range message")
  void monthNonNumeric() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE ROE", "N", "AB", "2025", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card expiry month must be between 1 and 12");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  @Test
  @DisplayName("expiry year below 1950 -> 'Invalid card expiry year'")
  void yearOutOfRange() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(existingCard()));
    CardUpdateRequest req = request("JANE ROE", "N", "06", "1949", "01");
    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Invalid card expiry year");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
    verifyNoInteractions(dateValidationService);
  }

  // ----- assembled-date calendar validation (existing day reused) -----

  @Test
  @DisplayName("calendar-invalid assembled date -> 'Card expiry date is not a valid date', no save")
  void invalidAssembledDate() {
    Card entity = existingCard();
    entity.setCardExpiraionDate("2020-01-31");
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(entity));
    when(dateValidationService.validateDate(anyString(), eq("YYYY-MM-DD"))).thenReturn(notAcceptable());

    CardUpdateRequest req = request("JANE ROE", "N", "02", "2025", "01");

    assertThatThrownBy(() -> cardUpdateService.updateCard(req))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card expiry date is not a valid date");

    ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
    verify(dateValidationService).validateDate(dateCaptor.capture(), eq("YYYY-MM-DD"));
    assertThat(dateCaptor.getValue()).isEqualTo("2025-02-31");
    verify(cardRepository, never()).saveAndFlush(any(Card.class));
  }
}
