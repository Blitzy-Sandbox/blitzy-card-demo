/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.unit.service;

import java.util.List;
import java.util.Optional;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.CardListService;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardListService}, the
 * read-only, paginated card-list service that backs {@code GET /api/cards}.
 *
 * <p>{@code CardListService} is the Java realization of the CICS
 * pseudo-conversational program {@code COCRDLIC} (transaction {@code CCLI}, card
 * list) at source commit {@code 27d6c6f}. The legacy VSAM browse machinery
 * ({@code STARTBR}/{@code READNEXT}/{@code READPREV} over the seven-row
 * {@code WS-SCREEN-ROWS OCCURS 7} display array, with PF7/PF8 paging) becomes
 * stateless Spring Data pagination of fixed page size&nbsp;{@code 7}. The optional
 * account-id and card-id filters reproduce the {@code 9500-FILTER-RECORDS} edit
 * cascade: a supplied-but-malformed filter raises {@link ValidationException}
 * carrying the byte-accurate legacy message, whereas an empty match set is a
 * normal outcome that yields an empty response rather than an exception.</p>
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps no Spring
 * {@code ApplicationContext}; it uses no {@code @SpringBootTest}, {@code MockMvc},
 * Testcontainers, database, or AWS resource. The single collaborator,
 * {@link CardRepository}, is Mockito-mocked and constructor-injected into the
 * service, keeping every test fast, deterministic, and decoupled from JPA.</p>
 *
 * <p><strong>Parity assertions (Gate&nbsp;1 / Gate&nbsp;4).</strong> The two
 * filter-rejection messages are asserted verbatim against the constants compiled
 * into {@code COCRDLIC} ({@code 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT
 * NUMBER'} at line&nbsp;1022 and {@code 'CARD ID FILTER,IF SUPPLIED MUST BE A 16
 * DIGIT NUMBER'} at line&nbsp;1058). The legacy
 * {@code 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'} text (line&nbsp;122) has
 * <em>no</em> field in the compiled {@link CardDto.ListResponse} record &mdash;
 * its components are {@code (pageNumber, accountIdFilter, cardNumberFilter,
 * cards)} only &mdash; so the "no records" outcome is represented by an
 * <em>empty</em> {@code cards()} list with no thrown exception. The compiled
 * contract is authoritative, so these tests assert the empty list rather than a
 * non-existent message accessor.</p>
 *
 * <p><strong>Page indexing.</strong> The compiled service treats the
 * {@code pageNumber} argument as a zero-based index, clamped with
 * {@code Math.max(pageNumber, 0)} and passed straight into
 * {@code PageRequest.of(index, 7)}; {@link CardDto.ListResponse#pageNumber()}
 * echoes that clamped index as a {@link String}. The {@link ArgumentCaptor}
 * tests lock both the size ({@code 7}) and the zero-based index that reach the
 * repository.</p>
 *
 * <p>{@code MockitoExtension} runs in its default strict-stub mode, so the
 * validation tests deliberately register no stubs: each rejection is raised
 * before any repository call, which the {@code verifyNoInteractions} assertions
 * confirm.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService — COCRDLIC (CCLI) paginated card list @ 27d6c6f")
class CardListServiceTest {

    /**
     * Eleven-digit account identifier used across the account-filtered scenarios;
     * its {@link Long#toString()} form {@code "100000001"} is the expected
     * {@code accountIdFilter()} echo in the response.
     */
    private static final long ACCOUNT_ID = 100_000_001L;

    /**
     * Verbatim rejection message for a supplied-but-invalid account filter,
     * mirroring {@code COCRDLIC} paragraph {@code 2210-EDIT-ACCOUNT} (line 1022).
     */
    private static final String ACCOUNT_FILTER_MESSAGE =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Verbatim rejection message for a supplied-but-invalid card filter,
     * mirroring {@code COCRDLIC} paragraph {@code 2220-EDIT-CARD} (line 1058).
     */
    private static final String CARD_FILTER_MESSAGE =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Mocked repository: the service's only collaborator. */
    @Mock
    private CardRepository cardRepository;

    /** System under test, constructor-injected with the mocked repository. */
    @InjectMocks
    private CardListService service;

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Builds a {@link Card} owned by {@link #ACCOUNT_ID} with the supplied number
     * and active-status flag; the remaining fields carry representative, non-null
     * values that the list projection ignores.
     *
     * @param cardNum      the sixteen-character card number (primary key)
     * @param activeStatus the single-character active-status flag
     * @return a fully populated {@link Card}
     */
    private static Card card(String cardNum, String activeStatus) {
        return new Card(cardNum, ACCOUNT_ID, 123, "CARDHOLDER NAME",
                "2025-12-31", activeStatus, 0L);
    }

    // -----------------------------------------------------------------
    // Phase 1 — a page returns up to seven mapped rows
    // -----------------------------------------------------------------

    @Test
    @DisplayName("account filter: returns the full seven-row page mapped to CardSummary")
    void accountFilterPageReturnsSevenCardsWithCorrectMapping() {
        List<Card> cards = List.of(
                card("0000000000000001", "Y"),
                card("0000000000000002", "N"),
                card("0000000000000003", "Y"),
                card("0000000000000004", "N"),
                card("0000000000000005", "Y"),
                card("0000000000000006", "N"),
                card("0000000000000007", "Y"));
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(ACCOUNT_ID, null, 1);

        assertThat(response.cards()).hasSize(7);
        assertThat(response.cards())
                .extracting(CardDto.CardSummary::accountId)
                .containsOnly("100000001");
        assertThat(response.cards())
                .extracting(CardDto.CardSummary::cardNumber)
                .containsExactly(
                        "0000000000000001", "0000000000000002", "0000000000000003",
                        "0000000000000004", "0000000000000005", "0000000000000006",
                        "0000000000000007");
        assertThat(response.cards())
                .extracting(CardDto.CardSummary::cardStatus)
                .containsExactly("Y", "N", "Y", "N", "Y", "N", "Y");
        assertThat(response.accountIdFilter()).isEqualTo("100000001");
        assertThat(response.cardNumberFilter()).isNull();
    }

    // -----------------------------------------------------------------
    // Phase 2 — empty match set is a normal (non-exceptional) outcome
    // -----------------------------------------------------------------

    @Test
    @DisplayName("empty result: returns an empty card list without throwing (legacy 'NO RECORDS FOUND')")
    void emptyResultReturnsEmptyCardsWithoutThrowing() {
        // The compiled ListResponse record exposes no message field; the legacy
        // 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.' outcome is represented by
        // an empty cards() list, never an exception.
        List<Card> none = List.of();
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(none));

        CardDto.ListResponse response =
                assertDoesNotThrow(() -> service.listCards(ACCOUNT_ID, null, 0));

        assertThat(response.cards()).isEmpty();
        assertThat(response.accountIdFilter()).isEqualTo("100000001");
        assertThat(response.pageNumber()).isEqualTo("0");
    }

    // -----------------------------------------------------------------
    // Phase 3 — filter validation raises ValidationException (verbatim text)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("account filter over the eleven-digit maximum throws the verbatim account message")
    void invalidAccountFilterOverMaxThrows() {
        assertThatThrownBy(() -> service.listCards(100_000_000_000L, null, 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage(ACCOUNT_FILTER_MESSAGE);

        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("negative account filter throws the verbatim account message")
    void negativeAccountFilterThrows() {
        assertThatThrownBy(() -> service.listCards(-1L, null, 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage(ACCOUNT_FILTER_MESSAGE);

        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("card filter that is not a sixteen-digit number throws the verbatim card message")
    void invalidCardFilterThrows() {
        assertThatThrownBy(() -> service.listCards(null, "123", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage(CARD_FILTER_MESSAGE);

        verifyNoInteractions(cardRepository);
    }

    // -----------------------------------------------------------------
    // Phase 4 — paging: size 7, zero-based index, page echo
    // -----------------------------------------------------------------

    @Test
    @DisplayName("paging: repository receives a size-7, zero-based PageRequest and the index is echoed")
    void pageableUsesSizeSevenZeroBasedIndex() {
        List<Card> cards = List.of(card("0000000000000001", "Y"));
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(ACCOUNT_ID, null, 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findByCardAcctId(eq(ACCOUNT_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        // P-2: pagination MUST be deterministically ordered by the card-number key
        // (the legacy VSAM browse is strictly card-number keyed), so the PageRequest
        // carries an explicit ascending Sort on cardNum rather than relying on the
        // implicit PK index, which is not guaranteed across query plans.
        Sort sort = pageableCaptor.getValue().getSort();
        assertThat(sort.isSorted()).isTrue();
        assertThat(sort.getOrderFor("cardNum")).isNotNull();
        assertThat(sort.getOrderFor("cardNum").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort).isEqualTo(Sort.by("cardNum").ascending());
        assertThat(response.pageNumber()).isEqualTo("1");
    }

    @Test
    @DisplayName("paging: a negative page number is clamped to the first page (index 0)")
    void negativePageClampsToFirstPage() {
        List<Card> none = List.of();
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(none));

        CardDto.ListResponse response = service.listCards(ACCOUNT_ID, null, -5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findByCardAcctId(eq(ACCOUNT_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        assertThat(response.pageNumber()).isEqualTo("0");
    }

    // -----------------------------------------------------------------
    // Unfiltered browse and "not supplied" filter normalization
    // -----------------------------------------------------------------

    @Test
    @DisplayName("no filter: browses all cards via findAll(Pageable) with a null account echo")
    void noFilterBrowsesAllViaFindAll() {
        List<Card> cards = List.of(
                card("0000000000000001", "Y"),
                card("0000000000000002", "N"));
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(null, null, 0);

        assertThat(response.cards()).hasSize(2);
        assertThat(response.accountIdFilter()).isNull();
        assertThat(response.cardNumberFilter()).isNull();
        assertThat(response.pageNumber()).isEqualTo("0");
    }

    @Test
    @DisplayName("no filter: the unfiltered findAll(Pageable) browse is also ascending-sorted on cardNum (P-2)")
    void unfilteredBrowseIsSortedByCardNumAscending() {
        List<Card> cards = List.of(
                card("0000000000000001", "Y"),
                card("0000000000000002", "N"));
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        service.listCards(null, null, 0);

        // P-2: the same explicitly-ordered PageRequest must flow into the unfiltered
        // findAll(Pageable) browse path, not only the account-filtered finder, so that
        // full-list pagination is deterministically card-number keyed like the VSAM browse.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.by("cardNum").ascending());
    }

    @Test
    @DisplayName("account filter of zero is treated as 'not supplied' and browses all cards")
    void zeroAccountFilterTreatedAsNotSupplied() {
        List<Card> cards = List.of(card("0000000000000001", "Y"));
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(0L, null, 0);

        assertThat(response.accountIdFilter()).isNull();
        assertThat(response.cards()).hasSize(1);
    }

    @Test
    @DisplayName("all-zeros card filter is treated as 'not supplied' (null card echo)")
    void allZerosCardFilterTreatedAsNotSupplied() {
        List<Card> cards = List.of(card("0000000000000001", "Y"));
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(ACCOUNT_ID, "0000000000000000", 0);

        assertThat(response.cardNumberFilter()).isNull();
        assertThat(response.cards()).hasSize(1);
    }

    @Test
    @DisplayName("blank card filter is treated as 'not supplied' (null card echo)")
    void blankCardFilterTreatedAsNotSupplied() {
        List<Card> cards = List.of(card("0000000000000001", "Y"));
        when(cardRepository.findByCardAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards));

        CardDto.ListResponse response = service.listCards(ACCOUNT_ID, "   ", 0);

        assertThat(response.cardNumberFilter()).isNull();
        assertThat(response.cards()).hasSize(1);
    }

    // -----------------------------------------------------------------
    // Specific-card lookup (9500-FILTER-RECORDS logical AND on CARD-NUM)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("specific card filter: resolves the single card by primary key")
    void specificCardFilterReturnsSingleCard() {
        Card found = card("0000000000000005", "Y");
        when(cardRepository.findById("0000000000000005")).thenReturn(Optional.of(found));

        CardDto.ListResponse response = service.listCards(null, "0000000000000005", 0);

        assertThat(response.cards()).hasSize(1);
        CardDto.CardSummary summary = response.cards().get(0);
        assertThat(summary.cardNumber()).isEqualTo("0000000000000005");
        assertThat(summary.accountId()).isEqualTo("100000001");
        assertThat(summary.cardStatus()).isEqualTo("Y");
        assertThat(response.cardNumberFilter()).isEqualTo("0000000000000005");
        assertThat(response.accountIdFilter()).isNull();
    }

    @Test
    @DisplayName("specific card filter: a non-matching account constraint yields an empty list")
    void specificCardFilterAccountMismatchReturnsEmpty() {
        // The card is owned by ACCOUNT_ID (100000001); the supplied account filter differs,
        // so the 9500-FILTER-RECORDS logical AND removes it from the result.
        Card found = card("0000000000000005", "Y");
        when(cardRepository.findById("0000000000000005")).thenReturn(Optional.of(found));

        CardDto.ListResponse response = service.listCards(999_999_999L, "0000000000000005", 0);

        assertThat(response.cards()).isEmpty();
        assertThat(response.accountIdFilter()).isEqualTo("999999999");
        assertThat(response.cardNumberFilter()).isEqualTo("0000000000000005");
    }

    @Test
    @DisplayName("specific card filter: an unknown card number yields an empty list")
    void specificCardFilterNotFoundReturnsEmpty() {
        when(cardRepository.findById("0000000000000099")).thenReturn(Optional.empty());

        CardDto.ListResponse response = service.listCards(null, "0000000000000099", 0);

        assertThat(response.cards()).isEmpty();
        assertThat(response.cardNumberFilter()).isEqualTo("0000000000000099");
    }
}

