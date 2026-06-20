package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardListResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.card.CardListService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link CardListService} (COBOL {@code COCRDLIC} parity): optional account/card
 * filters, the seven-rows-per-page browse, AND-narrowing card filter, and the
 * informational/empty-result messages. A real {@link PageImpl} drives faithful
 * {@code hasNext()} behavior.
 */
@ExtendWith(MockitoExtension.class)
class CardListServiceTest {

    private static final String ACCT = "12345678901";
    private static final String CARD = "4111111111111111";

    @Mock
    private CardRepository cardRepository;

    private CardListService service() {
        return new CardListService(cardRepository);
    }

    private static Card card(String num, long acctId, String status) {
        Card c = new Card();
        c.setCardNum(num);
        c.setCardAcctId(acctId);
        c.setCardActiveStatus(status);
        return c;
    }

    @Test
    void invalidAccountFilterThrows() {
        assertThatThrownBy(() -> service().getCardList("123", "", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    }

    @Test
    void invalidCardFilterThrows() {
        assertThatThrownBy(() -> service().getCardList("", "4111", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    }

    @Test
    void noFiltersFirstPageWithMoreRecordsReturnsInfoMessageAndSevenRows() {
        List<Card> content = List.of(
                card("4000000000000001", 11111111111L, "Y"),
                card("4000000000000002", 11111111111L, "Y"),
                card("4000000000000003", 11111111111L, "N"),
                card("4000000000000004", 11111111111L, "Y"),
                card("4000000000000005", 11111111111L, "Y"),
                card("4000000000000006", 11111111111L, "Y"),
                card("4000000000000007", 11111111111L, "Y"));
        // total 20 => hasNext() true on page 0 with size 7.
        Pageable pageable = PageRequest.of(0, 7);
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 20));

        CardListResponse response = service().getCardList(null, null, 0);

        assertThat(response.cards()).hasSize(7);
        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.infoMessage()).isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        assertThat(response.errorMessage()).isNull();
        assertThat(response.accountIdFilter()).isEmpty();
        assertThat(response.cardNumberFilter()).isEmpty();
    }

    @Test
    void noFiltersLastPageReturnsNoMoreRecords() {
        List<Card> content = List.of(card("4000000000000008", 11111111111L, "Y"));
        Pageable pageable = PageRequest.of(0, 7);
        // total == content size => hasNext() false.
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 1));

        CardListResponse response = service().getCardList("", "", 0);

        assertThat(response.cards()).hasSize(1);
        assertThat(response.infoMessage()).isNull();
        assertThat(response.errorMessage()).isEqualTo("NO MORE RECORDS TO SHOW");
    }

    @Test
    void emptyFirstPageReturnsNoRecordsFound() {
        Pageable pageable = PageRequest.of(0, 7);
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        CardListResponse response = service().getCardList("", "", 0);

        assertThat(response.cards()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
    }

    @Test
    void emptyLaterPageReturnsNoMoreRecords() {
        Pageable pageable = PageRequest.of(2, 7);
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 5));

        CardListResponse response = service().getCardList("", "", 2);

        assertThat(response.cards()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo("NO MORE RECORDS TO SHOW");
    }

    @Test
    void accountFilterUsesFindByCardAcctId() {
        List<Card> content = List.of(card("4000000000000009", 12345678901L, "Y"));
        Pageable pageable = PageRequest.of(0, 7);
        when(cardRepository.findByCardAcctId(eq(12345678901L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 1));

        CardListResponse response = service().getCardList(ACCT, "", 0);

        assertThat(response.accountIdFilter()).isEqualTo(ACCT);
        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).accountId()).isEqualTo("12345678901");
    }

    @Test
    void cardFilterNarrowsInMemoryAndForcesNoMoreRecords() {
        List<Card> content = List.of(
                card(CARD, 11111111111L, "Y"),
                card("4999999999999999", 11111111111L, "N"));
        Pageable pageable = PageRequest.of(0, 7);
        // total 20 => page.hasNext() true, but cardFilterValid forces hasNext=false.
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 20));

        CardListResponse response = service().getCardList("", CARD, 0);

        assertThat(response.cardNumberFilter()).isEqualTo(CARD);
        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).cardNumber()).isEqualTo(CARD);
        assertThat(response.errorMessage()).isEqualTo("NO MORE RECORDS TO SHOW");
        assertThat(response.infoMessage()).isNull();
    }

    @Test
    void negativePageNumberClampedToZero() {
        Pageable pageable = PageRequest.of(0, 7);
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        CardListResponse response = service().getCardList("", "", -5);

        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.errorMessage()).isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
    }
}
