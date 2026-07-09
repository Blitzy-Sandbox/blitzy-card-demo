package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardListResponse;
import com.carddemo.dto.PageResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Pure, fast Mockito unit tests for {@link CardListService}, the online
 * <em>Card List</em> (CCLI) service migrated from the COBOL program
 * {@code COCRDLIC} ({@code app/cbl/COCRDLIC.cbl}, frozen reference SHA
 * {@code 27d6c6f} — read-only, not copied into this repository).
 *
 * <p>The single collaborator {@link CardRepository} is supplied as a Mockito
 * mock and the service is instantiated through {@link InjectMocks constructor
 * injection}, so the suite loads no Spring context and touches no database,
 * Testcontainers, Docker, or live AWS. Every branch of the public entry point
 * {@link CardListService#listCards(Long, String, int)} is exercised, feeding the
 * JaCoCo line-coverage gate (Gate&nbsp;8) and compiling warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2).</p>
 *
 * <h2>Behavioural parity assertions (COCRDLIC @ SHA 27d6c6f)</h2>
 * <ul>
 *   <li><b>Seven rows per page.</b> {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP
 *       VALUE 7} is reproduced by {@link CardListService#PAGE_SIZE}; the
 *       {@link Pageable} handed to the repository is captured and asserted to
 *       carry a page size of seven, ordered by the base {@code CARD-NUM} key.</li>
 *   <li><b>Most-selective access path</b> ({@code 9500-FILTER-RECORDS}). A card
 *       filter positions to a single card ({@code findById}); an account filter
 *       reads the {@code CARD-ACCT-ID} alternate index ({@code findByCardAcctId});
 *       no filter browses the whole cluster ({@code findAll}).</li>
 *   <li><b>Filter edits in source order</b> ({@code 2200-EDIT-INPUTS}:
 *       {@code 2210-EDIT-ACCOUNT} then {@code 2220-EDIT-CARD}). The two operator
 *       messages are asserted verbatim so the external contract (Gate&nbsp;5) is
 *       preserved byte-for-byte.</li>
 *   <li><b>PAN masking (security).</b> Every {@link CardListItem#cardNumber()}
 *       exposes only its last four digits; a full Primary Account Number is never
 *       surfaced in a list row.</li>
 *   <li><b>One-based pagination metadata.</b> {@link PageResponse} exposes the
 *       one-based page number (legacy {@code PAGENO}) and the PF7/PF8
 *       {@code hasPrevious}/{@code hasNext} scroll semantics.</li>
 * </ul>
 *
 * <p>Only {@link String} and {@code long}/{@link Long} identifier values are
 * used; no {@code float}/{@code double} appears anywhere, consistent with the
 * migration's decimal-fidelity constraints.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService — COBOL COCRDLIC (CCLI) card list, 7 rows/page (SHA 27d6c6f)")
class CardListServiceTest {

    /**
     * Verbatim operator message emitted by {@code COCRDLIC} paragraph
     * {@code 2210-EDIT-ACCOUNT} (source line 1022) when a supplied account filter
     * is not a valid eleven-digit number.
     */
    private static final String MSG_ACCT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Verbatim operator message emitted by {@code COCRDLIC} paragraph
     * {@code 2220-EDIT-CARD} (source line 1058) when a supplied card filter is not
     * a valid sixteen-digit number.
     */
    private static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** A valid owning-account filter: exactly eleven digits ({@code CARD-ACCT-ID PIC 9(11)}). */
    private static final long VALID_ACCOUNT_FILTER = 12_345_678_901L;

    /** An invalid account filter: twelve digits, one more than the eleven-nine maximum. */
    private static final long OVERLONG_ACCOUNT_FILTER = 100_000_000_000L;

    /** A valid card-number filter: exactly sixteen digits ({@code CARD-NUM PIC X(16)}). */
    private static final String VALID_CARD_FILTER = "1234567890123456";

    /** The mocked repository collaborator (keyed and alternate-index card access). */
    @Mock
    private CardRepository cardRepository;

    /** Service under test, with {@link #cardRepository} constructor-injected by Mockito. */
    @InjectMocks
    private CardListService service;

    /**
     * Builds a {@link Card} fixture carrying only the fields the service reads
     * when projecting a {@link CardListItem} row.
     *
     * @param cardNum the card number ({@code CARD-NUM}, the natural key / PAN)
     * @param acctId  the owning account id ({@code CARD-ACCT-ID})
     * @param status  the active-status flag ({@code CARD-ACTIVE-STATUS})
     * @return a populated, transient {@link Card}
     */
    private static Card card(String cardNum, Long acctId, String status) {
        Card c = new Card();
        c.setCardNum(cardNum);
        c.setCardAcctId(acctId);
        c.setCardActiveStatus(status);
        return c;
    }

    /**
     * Wraps a list of cards in a Spring Data {@link Page} for the first page of a
     * seven-row browse, mirroring the {@code PageRequest.of(0, PAGE_SIZE)} the
     * service builds. The declared {@code total} may exceed the content size to
     * model a multi-page result.
     *
     * @param content the cards on the (first) page
     * @param total   the total number of matching cards across all pages
     * @return a {@link Page} of {@link Card} for page index zero, size seven
     */
    private static Page<Card> pageOf(List<Card> content, long total) {
        return new PageImpl<>(content, PageRequest.of(0, CardListService.PAGE_SIZE), total);
    }

    /**
     * Generates {@code count} distinct sixteen-digit card fixtures for
     * page-metadata assertions.
     *
     * @param count the number of cards to generate
     * @return a mutable list of {@code count} distinct {@link Card} rows
     */
    private static List<Card> cards(int count) {
        List<Card> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(card(String.format("%016d", i), VALID_ACCOUNT_FILTER, "Y"));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Access-path selection — COCRDLIC 9500-FILTER-RECORDS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A valid account filter reads the CARD-ACCT-ID alternate index (findByCardAcctId), seven rows per page")
    void listCards_withAccountFilter_usesFindByCardAcctId() {
        when(cardRepository.findByCardAcctId(eq(VALID_ACCOUNT_FILTER), any(Pageable.class)))
                .thenReturn(pageOf(List.of(card("1111222233334444", VALID_ACCOUNT_FILTER, "Y")), 1));

        CardListResponse response = service.listCards(VALID_ACCOUNT_FILTER, null, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findByCardAcctId(eq(VALID_ACCOUNT_FILTER), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(7);
        assertThat(response.page().pageSize()).isEqualTo(7);
        assertThat(response.accountIdFilter()).isEqualTo(String.valueOf(VALID_ACCOUNT_FILTER));
    }

    @Test
    @DisplayName("No filter browses the whole CARDDATA cluster (findAll)")
    void listCards_noFilter_usesFindAll() {
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(List.of(card("1111222233334444", 55L, "Y")), 1));

        CardListResponse response = service.listCards(null, null, 0);

        verify(cardRepository).findAll(any(Pageable.class));
        assertThat(response.page().content()).hasSize(1);
    }

    @Test
    @DisplayName("A valid card filter positions to a single card via the base key (findById)")
    void listCards_withCardFilter_usesFindById() {
        when(cardRepository.findById(VALID_CARD_FILTER))
                .thenReturn(Optional.of(card(VALID_CARD_FILTER, 55L, "Y")));

        CardListResponse response = service.listCards(null, VALID_CARD_FILTER, 0);

        verify(cardRepository).findById(VALID_CARD_FILTER);
        // D-023 (review finding M2): the echoed card-number filter is masked to its
        // last four digits so a full PAN is never externally observable, even when
        // the caller filtered by a complete sixteen-digit PAN.
        assertThat(response.cardNumberFilter()).isEqualTo("************3456");
        assertThat(response.page().content()).hasSize(1);
        assertThat(response.page().content().get(0).cardNumber()).isEqualTo("************3456");
    }

    @Test
    @DisplayName("The Pageable handed to the repository honours PAGE_SIZE = 7, ordered by cardNum ascending")
    void listCards_pageSizeIsSeven() {
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(List.of(), 0));

        service.listCards(null, null, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(captor.capture());
        Pageable requested = captor.getValue();
        assertThat(requested.getPageSize()).isEqualTo(7);
        assertThat(CardListService.PAGE_SIZE).isEqualTo(7);

        Sort.Order order = requested.getSort().getOrderFor("cardNum");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // ------------------------------------------------------------------
    // Filter edits — COCRDLIC 2200-EDIT-INPUTS (account edited before card)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An account filter beyond eleven digits is rejected with the verbatim COCRDLIC message and no repository call")
    void listCards_invalidAccountFilter_throwsValidation() {
        assertThatThrownBy(() -> service.listCards(OVERLONG_ACCOUNT_FILTER, null, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_FILTER_INVALID);

        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A card filter that is not sixteen digits is rejected with the verbatim COCRDLIC message and no repository call")
    void listCards_invalidCardFilter_throwsValidation() {
        assertThatThrownBy(() -> service.listCards(null, "123", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CARD_FILTER_INVALID);

        verifyNoInteractions(cardRepository);
    }

    // ------------------------------------------------------------------
    // Security — PAN masking (last four digits only) on every list row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Every returned card number is masked to its last four digits and never exposes the full PAN")
    void listCards_masksPan() {
        String pan1 = "1111222233334444";
        String pan2 = "9876543210987654";
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(List.of(card(pan1, 55L, "Y"), card(pan2, 66L, "N")), 2));

        CardListResponse response = service.listCards(null, null, 0);

        List<CardListItem> items = response.page().content();
        assertThat(items).hasSize(2);
        for (CardListItem item : items) {
            assertThat(item.cardNumber()).matches("\\*{12}\\d{4}");
        }
        assertThat(items.get(0).cardNumber()).isEqualTo("************4444").isNotEqualTo(pan1);
        assertThat(items.get(1).cardNumber()).isEqualTo("************7654").isNotEqualTo(pan2);
    }

    // ------------------------------------------------------------------
    // Pagination metadata — one-based PAGENO plus PF7/PF8 scroll semantics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Page metadata is computed one-based for a multi-page result (20 elements, page 1 of 3)")
    void listCards_mapsPageMetadata() {
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(cards(CardListService.PAGE_SIZE), 20));

        CardListResponse response = service.listCards(null, null, 0);

        PageResponse<CardListItem> page = response.page();
        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(7);
        assertThat(page.totalElements()).isEqualTo(20L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
        assertThat(page.content()).hasSize(7);
    }
}
