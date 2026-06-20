package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardDetailResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.card.CardDetailService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CardDetailService} (single keyed card read; behavioral parity with COBOL
 * program COCRDSLC at source commit 27d6c6f). Pure Mockito/JVM test; rationale in DECISION_LOG.md.
 */
@ExtendWith(MockitoExtension.class)
class CardDetailServiceTest {

  private static final String VALID_ACCOUNT = "12345678901";
  private static final String VALID_CARD = "1234567890123456";

  @Mock private CardRepository cardRepository;

  @InjectMocks private CardDetailService cardDetailService;

  private static Card card(String cardNum, Long acctId, String name, String status, String expiry) {
    Card card = new Card();
    card.setCardNum(cardNum);
    card.setCardAcctId(acctId);
    card.setCardEmbossedName(name);
    card.setCardActiveStatus(status);
    card.setCardExpiraionDate(expiry);
    return card;
  }

  @Test
  @DisplayName("both filters blank -> 'No input received', no repository read")
  void bothBlankThrowsNoInput() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail(null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessage("No input received");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("wildcard '*' both filters treated as blank -> 'No input received'")
  void wildcardTreatedAsBlank() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail("*", "*"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("No input received");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("account blank but card supplied -> 'Account number not provided'")
  void accountBlankThrows() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail("", VALID_CARD))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Account number not provided");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("all-zero account treated as blank -> 'Account number not provided'")
  void allZeroAccountTreatedAsBlank() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail("00000000000", VALID_CARD))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Account number not provided");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("account not 11 digits -> account filter message")
  void accountWrongLengthThrows() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail("123", VALID_CARD))
        .isInstanceOf(ValidationException.class)
        .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("card blank but account valid -> 'Card number not provided'")
  void cardBlankThrows() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail(VALID_ACCOUNT, ""))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Card number not provided");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("card not 16 digits -> card filter message")
  void cardWrongLengthThrows() {
    assertThatThrownBy(() -> cardDetailService.getCardDetail(VALID_ACCOUNT, "123"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("valid keys but no record -> RecordNotFoundException (FILE STATUS 23)")
  void notFoundThrows() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> cardDetailService.getCardDetail(VALID_ACCOUNT, VALID_CARD))
        .isInstanceOf(RecordNotFoundException.class)
        .hasMessage("Did not find cards for this search condition");

    verify(cardRepository).findById(VALID_CARD);
  }

  @Test
  @DisplayName("valid keys, record found -> populated response, %011d account, month/year split")
  void foundReturnsPopulatedResponse() {
    Card entity = card(VALID_CARD, 1L, "JOHN Q PUBLIC", "Y", "2024-06-15");
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(entity));

    CardDetailResponse response = cardDetailService.getCardDetail(VALID_ACCOUNT, VALID_CARD);

    assertThat(response.accountId()).isEqualTo("00000000001");
    assertThat(response.cardNumber()).isEqualTo(VALID_CARD);
    assertThat(response.nameOnCard()).isEqualTo("JOHN Q PUBLIC");
    assertThat(response.cardStatus()).isEqualTo("Y");
    assertThat(response.expirationMonth()).isEqualTo("06");
    assertThat(response.expirationYear()).isEqualTo("2024");
    assertThat(response.infoMessage()).isNull();
    assertThat(response.errorMessage()).isNull();

    verify(cardRepository).findById(VALID_CARD);
  }
}
