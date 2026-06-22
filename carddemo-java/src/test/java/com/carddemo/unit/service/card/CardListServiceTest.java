package com.carddemo.unit.service.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardListResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.card.CardListService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CardListService} (paginated 7-rows/page card browse; behavioral parity with
 * COBOL program COCRDLIC at source commit 27d6c6f). Pure Mockito/JVM test; rationale in DECISION_LOG.md.
 */
@ExtendWith(MockitoExtension.class)
class CardListServiceTest {

  private static final int PAGE_SIZE = 7;
  private static final String VALID_ACCOUNT = "12345678901";
  private static final String VALID_CARD = "1234567890123456";
  private static final String INFO_HINT = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
  private static final String NO_RECORDS = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";
  private static final String NO_MORE = "NO MORE RECORDS TO SHOW";

  @Mock private CardRepository cardRepository;

  @InjectMocks private CardListService cardListService;

  private static Card card(String cardNum, Long acctId, String status) {
    Card card = new Card();
    card.setCardNum(cardNum);
    card.setCardAcctId(acctId);
    card.setCardActiveStatus(status);
    return card;
  }

  private static List<Card> cards(int count) {
    List<Card> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(card(String.format("%016d", i), (long) i, "Y"));
    }
    return list;
  }

  @Test
  @DisplayName("list-all first page with more pages -> 7 rows, page '1', info hint")
  void listAllFirstPageHasNext() {
    Page<Card> page = new PageImpl<>(cards(PAGE_SIZE), PageRequest.of(0, PAGE_SIZE), 10);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(null, null, 0);

    assertThat(response.pageNumber()).isEqualTo("1");
    assertThat(response.cards()).hasSize(PAGE_SIZE);
    assertThat(response.infoMessage()).isEqualTo(INFO_HINT);
    assertThat(response.errorMessage()).isNull();
    assertThat(response.accountIdFilter()).isEmpty();
    assertThat(response.cardNumberFilter()).isEmpty();
    assertThat(response.cards().get(0).selectionFlag()).isEmpty();
    assertThat(response.cards().get(0).accountId()).isEqualTo("00000000001");

    verify(cardRepository).findAll(any(Pageable.class));
    verify(cardRepository, never()).findByCardAcctId(anyLong(), any(Pageable.class));
  }

  @Test
  @DisplayName("list-all final page (items present, no next) -> 'NO MORE RECORDS TO SHOW'")
  void listAllFinalPageNoMoreRecords() {
    Page<Card> page = new PageImpl<>(cards(3), PageRequest.of(1, PAGE_SIZE), 10);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(null, null, 1);

    assertThat(response.pageNumber()).isEqualTo("2");
    assertThat(response.cards()).hasSize(3);
    assertThat(response.infoMessage()).isNull();
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);
  }

  @Test
  @DisplayName("empty first page -> 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'")
  void emptyFirstPageNoRecordsFound() {
    Page<Card> page = new PageImpl<>(Collections.<Card>emptyList(), PageRequest.of(0, PAGE_SIZE), 0);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(null, null, 0);

    assertThat(response.cards()).isEmpty();
    assertThat(response.pageNumber()).isEqualTo("1");
    assertThat(response.errorMessage()).isEqualTo(NO_RECORDS);
    assertThat(response.infoMessage()).isNull();
  }

  @Test
  @DisplayName("empty later page -> 'NO MORE RECORDS TO SHOW'")
  void emptyLaterPageNoMoreRecords() {
    Page<Card> page = new PageImpl<>(Collections.<Card>emptyList(), PageRequest.of(2, PAGE_SIZE), 10);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(null, null, 2);

    assertThat(response.cards()).isEmpty();
    assertThat(response.pageNumber()).isEqualTo("3");
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);
    assertThat(response.infoMessage()).isNull();
  }

  @Test
  @DisplayName("valid account filter -> findByCardAcctId browse, account echoed")
  void accountFilterUsesFindByCardAcctId() {
    List<Card> content = new ArrayList<>();
    content.add(card("1111111111111111", 12345678901L, "Y"));
    Page<Card> page = new PageImpl<>(content, PageRequest.of(0, PAGE_SIZE), 1);
    when(cardRepository.findByCardAcctId(eq(12345678901L), any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(VALID_ACCOUNT, null, 0);

    assertThat(response.cards()).hasSize(1);
    assertThat(response.accountIdFilter()).isEqualTo(VALID_ACCOUNT);
    assertThat(response.cardNumberFilter()).isEmpty();
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);

    verify(cardRepository).findByCardAcctId(eq(12345678901L), any(Pageable.class));
    verify(cardRepository, never()).findAll(any(Pageable.class));
  }

  @Test
  @DisplayName("account filter not 11 digits -> account filter message, no repository call")
  void accountFilterInvalidFormatThrows() {
    assertThatThrownBy(() -> cardListService.getCardList("123", null, 0))
        .isInstanceOf(ValidationException.class)
        .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("valid card filter -> exact-match findById BEFORE paging (Issue 4), single row")
  void cardFilterExactMatchBeforePaging() {
    // Issue 4: the card-number filter is an EXACT match applied before paging (the card number is
    // the unique key), so a matching card is returned regardless of which page it would have fallen
    // on. The earlier implementation paged first and scanned only the current page, dropping matches.
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(card(VALID_CARD, 22L, "N")));

    CardListResponse response = cardListService.getCardList(null, VALID_CARD, 0);

    assertThat(response.cards()).hasSize(1);
    assertThat(response.cards().get(0).cardNumber()).isEqualTo(VALID_CARD);
    assertThat(response.cards().get(0).accountId()).isEqualTo("00000000022");
    assertThat(response.cards().get(0).cardStatus()).isEqualTo("N");
    assertThat(response.cardNumberFilter()).isEqualTo(VALID_CARD);
    assertThat(response.accountIdFilter()).isEmpty();
    assertThat(response.infoMessage()).isNull();
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);

    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).findAll(any(Pageable.class));
    verify(cardRepository, never()).findByCardAcctId(anyLong(), any(Pageable.class));
  }

  @Test
  @DisplayName("card filter on a non-first page -> single match belongs to page 0 only, empty here")
  void cardFilterSingleMatchOnlyOnFirstPage() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(card(VALID_CARD, 22L, "N")));

    CardListResponse response = cardListService.getCardList(null, VALID_CARD, 1);

    assertThat(response.cards()).isEmpty();
    assertThat(response.pageNumber()).isEqualTo("2");
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);
    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).findAll(any(Pageable.class));
  }

  @Test
  @DisplayName("card + account filter -> AND-narrow on owning account (COCRDLIC 9500), single row")
  void cardAndAccountFilterAndNarrowsMatch() {
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(card(VALID_CARD, 12345678901L, "Y")));

    CardListResponse response = cardListService.getCardList(VALID_ACCOUNT, VALID_CARD, 0);

    assertThat(response.cards()).hasSize(1);
    assertThat(response.cards().get(0).cardNumber()).isEqualTo(VALID_CARD);
    assertThat(response.accountIdFilter()).isEqualTo(VALID_ACCOUNT);
    assertThat(response.cardNumberFilter()).isEqualTo(VALID_CARD);
    verify(cardRepository).findById(VALID_CARD);
    verify(cardRepository, never()).findByCardAcctId(anyLong(), any(Pageable.class));
  }

  @Test
  @DisplayName("card + account filter where card belongs to a different account -> AND-narrow excludes it")
  void cardAndAccountFilterMismatchExcluded() {
    // The card exists but is owned by a different account than the supplied account filter; the
    // 9500-FILTER-RECORDS AND-narrowing excludes it, yielding no records.
    when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(card(VALID_CARD, 99999999999L, "Y")));

    CardListResponse response = cardListService.getCardList(VALID_ACCOUNT, VALID_CARD, 0);

    assertThat(response.cards()).isEmpty();
    assertThat(response.errorMessage()).isEqualTo(NO_RECORDS);
    verify(cardRepository).findById(VALID_CARD);
  }

  @Test
  @DisplayName("valid card filter with no matching card -> empty first page message")
  void cardFilterNoMatchEmptyFirstPage() {
    when(cardRepository.findById("9999999999999999")).thenReturn(Optional.empty());

    CardListResponse response = cardListService.getCardList(null, "9999999999999999", 0);

    assertThat(response.cards()).isEmpty();
    assertThat(response.errorMessage()).isEqualTo(NO_RECORDS);
    assertThat(response.infoMessage()).isNull();
    verify(cardRepository).findById("9999999999999999");
    verify(cardRepository, never()).findAll(any(Pageable.class));
  }

  @Test
  @DisplayName("card filter not 16 digits -> card filter message")
  void cardFilterInvalidFormatThrows() {
    assertThatThrownBy(() -> cardListService.getCardList(null, "123", 0))
        .isInstanceOf(ValidationException.class)
        .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("account format error has priority over card format error")
  void accountFormatErrorHasPriorityOverCard() {
    assertThatThrownBy(() -> cardListService.getCardList("123", "456", 0))
        .isInstanceOf(ValidationException.class)
        .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("negative page number rejected with HTTP 400 (Issue 5), no repository call")
  void negativePageRejectedWith400() {
    // Issue 5: an invalid (negative) zero-based page must be rejected, not silently coerced to the
    // first page. The validation precedes any repository access.
    assertThatThrownBy(() -> cardListService.getCardList(null, null, -5))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Page number must be zero or greater");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("offset-overflow page clamped so SQL offset stays within Integer.MAX_VALUE (graceful empty page, not HTTP 500)")
  void hugePageClampedToMaxSafeOffset() {
    // Before the fix, page=400000000 * size 7 = 2_800_000_000 > Integer.MAX_VALUE, so Spring Data
    // raised InvalidDataAccessApiUsageException which surfaced as an unhandled HTTP 500.
    Page<Card> page = new PageImpl<>(Collections.<Card>emptyList(), PageRequest.of(0, PAGE_SIZE), 10);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    CardListResponse response = cardListService.getCardList(null, null, 400_000_000);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(cardRepository).findAll(captor.capture());
    int clampedPage = captor.getValue().getPageNumber();
    assertThat(clampedPage).isEqualTo(Integer.MAX_VALUE / PAGE_SIZE);
    assertThat((long) clampedPage * PAGE_SIZE).isLessThanOrEqualTo(Integer.MAX_VALUE);
    // The service returns a graceful empty page rather than throwing.
    assertThat(response.cards()).isEmpty();
    assertThat(response.errorMessage()).isEqualTo(NO_MORE);
  }

  @Test
  @DisplayName("page size is always 7 and sorted by cardNum ascending (COCRDLIC parity)")
  void pageSizeIsSevenSortedByCardNum() {
    Page<Card> page = new PageImpl<>(cards(PAGE_SIZE), PageRequest.of(0, PAGE_SIZE), 7);
    when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

    cardListService.getCardList(null, null, 0);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(cardRepository).findAll(captor.capture());
    Pageable used = captor.getValue();
    assertThat(used.getPageSize()).isEqualTo(7);
    assertThat(used.getPageNumber()).isEqualTo(0);
    Sort.Order order = used.getSort().getOrderFor("cardNum");
    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
  }
}
