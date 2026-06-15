package com.cardemo.unit.service.card;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardListService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardListService} (migrated from {@code COCRDLIC.cbl}). Fully mocked; verifies
 * pagination page-size=7 parity, 1-based page conversion, optional-filter validation, query selection,
 * and row mapping.
 *
 * <h2>Test strategy</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em> Spring context, no
 * database, no Testcontainers and no I/O. The {@link CardRepository} collaborator is a Mockito
 * {@code @Mock} and the system under test is wired by constructor injection via {@code @InjectMocks}.
 * The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so validation tests stub
 * nothing and assert {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions} to
 * prove the early throw, while each query test stubs <em>exactly one</em> repository method
 * ({@code findAll}, {@code findByCardAcctId} or {@code findById}) &mdash; no {@code lenient()}.</p>
 *
 * <h2>The single most important parity assertion &mdash; page size is EXACTLY seven</h2>
 * <p>The legacy {@code COCRDLIC} screen painted a fixed {@code OCCURS 7 TIMES} row array capped by
 * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} (COCRDLIC L76, L177-178). The make-or-break parity
 * check is therefore the {@link ArgumentCaptor}-based assertion that the {@link Pageable} this service
 * builds has {@code getPageSize() == 7}, with an ascending {@code cardNum} sort
 * ({@code Sort.by("cardNum").ascending()} &larr; the {@code STARTBR(GTEQ)}/{@code READNEXT} browse order).</p>
 *
 * <h2>Verified collaborator contracts (compiled against the actual sources)</h2>
 * <ul>
 *   <li>{@code Card.getCardAcctId()} returns {@link Long}; the entity's CVV is an {@link Integer} and the
 *       expiration date a {@link LocalDate} (NOT {@link String}), so the {@link #card(String, long, String)
 *       factory} sets those typed values.</li>
 *   <li>{@code CardDto.CardListItem} is a {@code public static} nested type whose status accessor is
 *       {@code getActiveStatus()} (NOT {@code getCardStatus()}).</li>
 *   <li>{@code CardDto.getPageNumber()} is asserted only through {@code String.valueOf(...)} so the test
 *       stays agnostic to the DTO's {@code String}-vs-{@code Integer} choice.</li>
 * </ul>
 *
 * <p>Golden values are taken from the first record of {@code app/data/ASCII/carddata.txt}: card number
 * {@code 0500024453765740}, owning account {@code 50}, active status {@code "Y"}. The COBOL is read-only
 * reference at the frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository
 * &mdash; only its observable contract is asserted.</p>
 *
 * @see CardListService
 * @see CardRepository
 * @see CardDto
 * @see CardDto.CardListItem
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService — COCRDLIC paginated card browse (7 rows/page)")
class CardListServiceTest {

    /** The 16-character golden card number (carddata.txt line 1, {@code CARD-NUM}). */
    private static final String GOLDEN_CARD_NUMBER = "0500024453765740";

    /** The 11-digit zero-padded form of the golden owning account ({@code %011d} of 50). */
    private static final String GOLDEN_ACCOUNT_ID = "00000000050";

    /** The owning-account key as the {@link Long} the service parses {@link #GOLDEN_ACCOUNT_ID} into. */
    private static final long GOLDEN_ACCOUNT_KEY = 50L;

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardListService service;

    // ------------------------------------------------------------------------------------------------
    // Test-data factories. The entity contract was verified against model/entity/Card.java: the CVV is an
    // Integer and the expiration date a LocalDate (NOT String), so those typed values are set here.
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds an in-memory {@link Card} fixture for the browse mapping.
     *
     * @param num    the 16-character card number ({@code CARD-NUM}, primary key)
     * @param acctId the owning account id ({@code CARD-ACCT-ID}); mapped to {@link Long}
     * @param status the single-character active-status flag ({@code CARD-ACTIVE-STATUS})
     * @return a populated {@link Card} test fixture
     */
    private static Card card(String num, long acctId, String status) {
        Card c = new Card();
        c.setCardNum(num);
        c.setCardAcctId(acctId);
        c.setCardActiveStatus(status);
        c.setCardEmbossedName("TEST CARDHOLDER");
        c.setCardCvvCd(123);                                // CARD-CVV-CD PIC 9(03) -> Integer (NOT "123")
        c.setCardExpirationDate(LocalDate.of(2024, 5, 10)); // CARD-EXPIRAION-DATE -> LocalDate (NOT "2024-05-10")
        return c;
    }

    /**
     * Builds {@code n} sequential card fixtures with 16-digit zero-padded numbers, all on account
     * {@code 50} with active status {@code "Y"} &mdash; used to fill a browse page.
     *
     * @param n the number of cards to build (at most {@link CardDto#ROWS_PER_PAGE} for a single page)
     * @return a mutable list of {@code n} card fixtures in ascending card-number order
     */
    private static List<Card> nCards(int n) {
        List<Card> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(card(String.format("%016d", i), GOLDEN_ACCOUNT_KEY, "Y")); // 16-digit card numbers
        }
        return list;
    }

    // ================================================================================================
    // Phase 2 — Pagination parity: page size = 7, ascending cardNum sort, 1-based -> 0-based conversion.
    // ================================================================================================

    @Test
    @DisplayName("builds Pageable size=7, sort cardNum ASC, page 1 -> index 0 [HARD PARITY: OCCURS 7]")
    void listCards_buildsPageableSize7_sortCardNumAsc_page1IsIndex0() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listCards(null, null, 1);

        verify(cardRepository).findAll(captor.capture());
        Pageable p = captor.getValue();
        assertThat(p.getPageSize()).isEqualTo(7);                 // HARD PARITY (WS-MAX-SCREEN-LINES / OCCURS 7)
        assertThat(p.getPageNumber()).isEqualTo(0);               // 1-based -> 0-based
        Sort.Order order = p.getSort().getOrderFor("cardNum");    // STARTBR(GTEQ)/READNEXT -> cardNum ASC
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("page 2 uses zero-based index 1 (page size still 7)")
    void listCards_page2_usesZeroBasedIndex1() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listCards(null, null, 2);

        verify(cardRepository).findAll(captor.capture());
        Pageable p = captor.getValue();
        assertThat(p.getPageNumber()).isEqualTo(1);               // 2 (1-based) -> 1 (0-based)
        assertThat(p.getPageSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("page 0 (below 1) is normalized to index 0")
    void listCards_pageBelow1_normalizedToIndex0() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listCards(null, null, 0);

        verify(cardRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0); // 0 normalized to 1 -> index 0
    }

    @Test
    @DisplayName("negative page (-5) is normalized to index 0")
    void listCards_pageNegative_normalizedToIndex0() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listCards(null, null, -5);

        verify(cardRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0); // -5 normalized to 1 -> index 0
    }

    // ================================================================================================
    // Phase 3 — No-filter browse: page content size and per-row mapping.
    // ================================================================================================

    @Test
    @DisplayName("no filter -> 7 mapped rows on page 1 (selection blank, %011d account, status, page=1)")
    void listCards_noFilter_returns7MappedRows_onPage1() {
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(nCards(7)));

        CardDto result = service.listCards(null, null, 1);

        assertThat(result.getCards()).hasSize(7);
        CardDto.CardListItem first = result.getCards().get(0);
        assertThat(first.getCardNumber()).isEqualTo("0000000000000001");
        assertThat(first.getAccountId()).isEqualTo("00000000050");          // %011d of 50L
        assertThat(first.getActiveStatus()).isEqualTo("Y");                 // CardListItem getter is getActiveStatus()
        assertThat(first.getSelectionFlag()).isNullOrEmpty();               // service sets "" (tolerate null)
        assertThat(String.valueOf(result.getPageNumber())).isEqualTo("1");  // type-tolerant (String OR Integer)
    }

    @Test
    @DisplayName("no filter, page 2 -> 3 rows mapped, page number stamped 2")
    void listCards_noFilter_page2_returns3Rows() {
        // The mock returns whatever page is stubbed; "3 on page 2" is simulated independently (the mock does
        // not honor the page index). The service maps the returned content and stamps the 1-based page number.
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(nCards(3)));

        CardDto result = service.listCards(null, null, 2);

        assertThat(result.getCards()).hasSize(3);
        assertThat(String.valueOf(result.getPageNumber())).isEqualTo("2");
    }

    // ================================================================================================
    // Phase 4 — Filtered query selection: account-only (findByCardAcctId), card-only / both (findById).
    // ================================================================================================

    @Test
    @DisplayName("account filter only -> findByCardAcctId(50L, pageable size=7); 1 row mapped")
    void listCards_accountFilterOnly_usesFindByCardAcctId_size7() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(cardRepository.findByCardAcctId(eq(GOLDEN_ACCOUNT_KEY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card(GOLDEN_CARD_NUMBER, GOLDEN_ACCOUNT_KEY, "Y"))));

        CardDto result = service.listCards(GOLDEN_ACCOUNT_ID, null, 1); // "00000000050" -> Long 50

        verify(cardRepository).findByCardAcctId(eq(GOLDEN_ACCOUNT_KEY), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(7);
        assertThat(result.getCards()).hasSize(1);
        assertThat(result.getCards().get(0).getAccountId()).isEqualTo("00000000050");
    }

    @Test
    @DisplayName("card filter only, present -> findById single item on page 1")
    void listCards_cardFilterOnly_present_returnsSingleItem_page1() {
        when(cardRepository.findById(GOLDEN_CARD_NUMBER))
                .thenReturn(Optional.of(card(GOLDEN_CARD_NUMBER, GOLDEN_ACCOUNT_KEY, "Y")));

        CardDto result = service.listCards(null, GOLDEN_CARD_NUMBER, 1);

        assertThat(result.getCards()).hasSize(1);
        assertThat(result.getCards().get(0).getCardNumber()).isEqualTo(GOLDEN_CARD_NUMBER);
    }

    @Test
    @DisplayName("card filter only, page 2 -> empty (unique-key match belongs to page 1 only)")
    void listCards_cardFilterOnly_page2_returnsEmpty() {
        // No stub: the service's zeroBasedPage==0 guard means findById is not even probed on page 2, and a
        // unique-key card filter yields at most one record, which belongs to page 1. Result is empty.
        CardDto result = service.listCards(null, GOLDEN_CARD_NUMBER, 2);

        assertThat(result.getCards()).isEmpty();
    }

    @Test
    @DisplayName("both filters, account matches the card's owner -> single item")
    void listCards_bothFilters_accountMatches_returnsSingleItem() {
        when(cardRepository.findById(GOLDEN_CARD_NUMBER))
                .thenReturn(Optional.of(card(GOLDEN_CARD_NUMBER, GOLDEN_ACCOUNT_KEY, "Y")));

        CardDto result = service.listCards(GOLDEN_ACCOUNT_ID, GOLDEN_CARD_NUMBER, 1);

        assertThat(result.getCards()).hasSize(1);
        assertThat(result.getCards().get(0).getCardNumber()).isEqualTo(GOLDEN_CARD_NUMBER);
    }

    @Test
    @DisplayName("both filters, account does NOT match the card's owner -> empty (filters ANDed)")
    void listCards_bothFilters_accountMismatch_returnsEmpty() {
        // Card belongs to account 999, but the caller filters on account 50 -> the AND of the two filters
        // excludes the record (9500-FILTER-RECORDS parity).
        when(cardRepository.findById(GOLDEN_CARD_NUMBER))
                .thenReturn(Optional.of(card(GOLDEN_CARD_NUMBER, 999L, "Y")));

        CardDto result = service.listCards(GOLDEN_ACCOUNT_ID, GOLDEN_CARD_NUMBER, 1);

        assertThat(result.getCards()).isEmpty();
    }

    // ================================================================================================
    // Phase 5 — Empty result, verbatim validation messages (first-error-wins), and optional filters.
    // ================================================================================================

    @Test
    @DisplayName("empty first page -> empty cards, no exception (NO RECORDS is a controller concern)")
    void listCards_emptyFirstPage_returnsEmptyCards_noException() {
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        CardDto result = service.listCards(null, null, 1);

        assertThat(result.getCards()).isEmpty();
    }

    @Test
    @DisplayName("account filter non-numeric -> ValidationException (verbatim 11-digit); no DB access")
    void listCards_accountFilterNonNumeric_throwsValidation() {
        assertThatThrownBy(() -> service.listCards("12A45678901", null, 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("card filter non-numeric -> ValidationException (verbatim 16-digit); no DB access")
    void listCards_cardFilterNonNumeric_throwsValidation() {
        assertThatThrownBy(() -> service.listCards(null, "12A4567890123456", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("both filters non-numeric -> account error wins (account-then-card precedence); no DB access")
    void listCards_bothFiltersNonNumeric_accountErrorWins() {
        assertThatThrownBy(() -> service.listCards("BADACCOUNT1", "BADCARD0000000001", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("blank filters (spaces / empty) -> no-filter findAll path, no exception")
    void listCards_blankFilters_noException() {
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        CardDto result = service.listCards("   ", "", 1); // blank => not supplied (NOT an error)

        assertThat(result.getCards()).isEmpty();
        verify(cardRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("'*' sentinel account filter treated as not supplied -> findAll path (findByCardAcctId never)")
    void listCards_allZerosOrStarFilter_treatedAsNotSupplied() {
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        CardDto result = service.listCards("*", null, 1); // '*' normalized to LOW-VALUES => filter inactive

        assertThat(result.getCards()).isEmpty();
        verify(cardRepository).findAll(any(Pageable.class));
        verify(cardRepository, never()).findByCardAcctId(any(), any(Pageable.class));
    }
}
