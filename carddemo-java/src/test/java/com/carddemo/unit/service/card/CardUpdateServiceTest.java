package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardUpdateRequest;
import com.carddemo.model.dto.CardUpdateResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.card.CardUpdateService;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link CardUpdateService} (COBOL {@code COCRDUPC} parity): search-key edits,
 * the no-changes short circuit, the ordered field edits, the JPA {@code @Version} optimistic
 * lock translation to {@link ConcurrencyException}, and the success response.
 */
@ExtendWith(MockitoExtension.class)
class CardUpdateServiceTest {

    private static final String ACCT = "12345678901";
    private static final String CARD = "4111111111111111";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private DateValidationService dateValidationService;

    private CardUpdateService service() {
        return new CardUpdateService(cardRepository, dateValidationService);
    }

    private static CardUpdateRequest req(String name, String status, String month, String year) {
        return new CardUpdateRequest(ACCT, CARD, name, status, month, year, "", 0L);
    }

    private static Card existing(String name, String status, String expiry) {
        Card c = new Card();
        c.setCardNum(CARD);
        c.setCardAcctId(12345678901L);
        c.setCardEmbossedName(name);
        c.setCardActiveStatus(status);
        c.setCardExpiraionDate(expiry);
        c.setVersion(2L);
        return c;
    }

    private static DateValidationResult acceptable() {
        return new DateValidationResult(true, 0, 0, "OK", "", "YYYY-MM-DD");
    }

    private static DateValidationResult rejected() {
        return new DateValidationResult(false, 12, 9999, "BAD", "", "YYYY-MM-DD");
    }

    @Test
    void bothKeysBlankThrowsNoInput() {
        CardUpdateRequest request = new CardUpdateRequest("", "", "N", "Y", "8", "2030", "", 0L);
        assertThatThrownBy(() -> service().updateCard(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    @Test
    void invalidAccountKeyThrowsAccountFilter() {
        CardUpdateRequest request = new CardUpdateRequest("123", CARD, "N", "Y", "8", "2030", "", 0L);
        assertThatThrownBy(() -> service().updateCard(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    }

    @Test
    void cardNotFoundThrowsRecordNotFound() {
        when(cardRepository.findById(CARD)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "N", "8", "2030")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    @Test
    void noChangesReturnsNoChangeMessageWithoutSaving() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));

        CardUpdateResponse response = service().updateCard(req("OLD NAME", "Y", "8", "2025"));

        assertThat(response.infoMessage()).isEqualTo("No change detected with respect to values fetched.");
        assertThat(response.errorMessage()).isNull();
        verify(cardRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankNameThrowsNameNotProvided() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        assertThatThrownBy(() -> service().updateCard(req("", "N", "8", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name not provided");
    }

    @Test
    void nonAlphaNameThrowsAlphaMessage() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        assertThatThrownBy(() -> service().updateCard(req("NAME123", "N", "8", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name can only contain alphabets and spaces");
    }

    @Test
    void invalidStatusThrowsStatusMessage() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "X", "8", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Active Status must be Y or N");
    }

    @Test
    void outOfRangeMonthThrowsMonthMessage() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "N", "13", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
    }

    @Test
    void outOfRangeYearThrowsYearMessage() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "N", "8", "1900")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid card expiry year");
    }

    @Test
    void invalidComposedDateThrowsDateMessage() {
        when(cardRepository.findById(CARD))
                .thenReturn(Optional.of(existing("OLD NAME", "Y", "2025-08-01")));
        when(dateValidationService.validateDate(anyString(), anyString())).thenReturn(rejected());

        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "N", "8", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry date is not a valid date");
    }

    @Test
    void successCommitsAndReturnsFormattedResponse() {
        Card entity = existing("OLD NAME", "Y", "2020-05-10");
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(entity));
        when(dateValidationService.validateDate(anyString(), anyString())).thenReturn(acceptable());

        CardUpdateResponse response = service().updateCard(req("NEW NAME", "N", "8", "2030"));

        assertThat(response.nameOnCard()).isEqualTo("NEW NAME");
        assertThat(response.cardStatus()).isEqualTo("N");
        assertThat(response.expirationMonth()).isEqualTo("08");
        assertThat(response.expirationYear()).isEqualTo("2030");
        assertThat(response.expirationDay()).isEqualTo("10");
        assertThat(response.infoMessage()).isEqualTo("Changes committed to database");
        // entity carries the new expiry retaining the non-display day component.
        assertThat(entity.getCardExpiraionDate()).isEqualTo("2030-08-10");
        verify(cardRepository).saveAndFlush(entity);
    }

    @Test
    void optimisticLockFailureTranslatedToConcurrencyException() {
        Card entity = existing("OLD NAME", "Y", "2020-05-10");
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(entity));
        when(dateValidationService.validateDate(anyString(), anyString())).thenReturn(acceptable());
        when(cardRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Card.class, CARD));

        assertThatThrownBy(() -> service().updateCard(req("NEW NAME", "N", "8", "2030")))
                .isInstanceOf(ConcurrencyException.class)
                .hasMessage("Record changed by some one else. Please review");
    }
}
