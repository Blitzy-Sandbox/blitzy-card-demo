package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardDetailResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.card.CardDetailService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CardDetailService} (COBOL {@code COCRDSLC} parity). Verifies the
 * ordered input edits ({@code 2210-EDIT-ACCOUNT}/{@code 2220-EDIT-CARD}), the keyed read by
 * card number ({@code 9100-GETCARD-BYACCTCARD}), and the populated success response.
 */
@ExtendWith(MockitoExtension.class)
class CardDetailServiceTest {

    private static final String ACCT = "12345678901";
    private static final String CARD = "4111111111111111";

    @Mock
    private CardRepository cardRepository;

    private CardDetailService service() {
        return new CardDetailService(cardRepository);
    }

    @Test
    void bothBlankThrowsNoInput() {
        assertThatThrownBy(() -> service().getCardDetail("", ""))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    @Test
    void wildcardAndAllZerosTreatedAsBlank() {
        // "*" normalizes to empty and "000..." is all-zeros => both treated blank => No input.
        assertThatThrownBy(() -> service().getCardDetail("*", "0000000000000000"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    @Test
    void accountBlankButCardPresentThrowsAccountNotProvided() {
        assertThatThrownBy(() -> service().getCardDetail("", CARD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");
    }

    @Test
    void accountNotElevenDigitsThrowsAccountFilter() {
        assertThatThrownBy(() -> service().getCardDetail("123", CARD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    }

    @Test
    void cardBlankButAccountValidThrowsCardNotProvided() {
        assertThatThrownBy(() -> service().getCardDetail(ACCT, ""))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");
    }

    @Test
    void cardNotSixteenDigitsThrowsCardFilter() {
        assertThatThrownBy(() -> service().getCardDetail(ACCT, "4111"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    }

    @Test
    void cardNotFoundThrowsRecordNotFound() {
        when(cardRepository.findById(CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getCardDetail(ACCT, CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    @Test
    void successPopulatesResponseWithFormattedFields() {
        Card card = new Card();
        card.setCardNum(CARD);
        card.setCardAcctId(12345678901L);
        card.setCardEmbossedName("JOHN Q PUBLIC");
        card.setCardActiveStatus("Y");
        card.setCardExpiraionDate("2026-08-15");
        card.setVersion(3L);
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(card));

        CardDetailResponse response = service().getCardDetail(ACCT, CARD);

        assertThat(response.accountId()).isEqualTo("12345678901");
        assertThat(response.cardNumber()).isEqualTo(CARD);
        assertThat(response.nameOnCard()).isEqualTo("JOHN Q PUBLIC");
        assertThat(response.cardStatus()).isEqualTo("Y");
        assertThat(response.expirationMonth()).isEqualTo("08");
        assertThat(response.expirationYear()).isEqualTo("2026");
        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void successWithNullAccountIdAndShortExpiryYieldsBlankDerivedFields() {
        Card card = new Card();
        card.setCardNum(CARD);
        card.setCardAcctId(null);
        card.setCardEmbossedName("NAME");
        card.setCardActiveStatus("N");
        card.setCardExpiraionDate("20");
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(card));

        CardDetailResponse response = service().getCardDetail(ACCT, CARD);

        assertThat(response.accountId()).isEmpty();
        assertThat(response.expirationMonth()).isEmpty();
        assertThat(response.expirationYear()).isEmpty();
    }
}
