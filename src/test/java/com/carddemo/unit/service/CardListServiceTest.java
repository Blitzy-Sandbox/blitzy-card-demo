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
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardListService}, the paged
 * card browse (the Java realization of the COBOL {@code COCRDLIC} list program @
 * {@code 27d6c6f}, whose PF7/PF8 paging becomes page-number parameters).
 *
 * <p>The tests lock the bounded {@code PAGE_SIZE} of seven rows, the account- and
 * card-filter validation messages, the routing between the account-scoped finder
 * and the unscoped finder, and the single-card resolution taken when a 16-digit
 * card filter is supplied.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService - COCRDLIC paged card list (PAGE_SIZE = 7) @ 27d6c6f")
class CardListServiceTest {

    private static final String CARD_NUMBER = "1234567890123456";

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardListService service;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private Card card(long acctId, String cardNum, String status) {
        Card card = new Card();
        card.setCardAcctId(acctId);
        card.setCardNum(cardNum);
        card.setActiveStatus(status);
        return card;
    }

    @Test
    @DisplayName("listCards: no filters reads the unscoped page bounded to seven rows")
    void listCardsNoFilterUsesUnscopedPageOfSeven() {
        Page<Card> page = new PageImpl<>(List.of(
                card(111L, CARD_NUMBER, "Y"),
                card(222L, "6543210987654321", "N")));
        when(cardRepository.findAll(pageableCaptor.capture())).thenReturn(page);

        CardDto.ListResponse response = service.listCards(null, null, 0);

        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.cards()).hasSize(2);
        assertThat(response.cards().get(0).cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(response.cards().get(0).cardStatus()).isEqualTo("Y");
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("listCards: an account filter routes to the account-scoped finder")
    void listCardsWithAccountUsesScopedFinder() {
        Page<Card> page = new PageImpl<>(List.of(card(123L, CARD_NUMBER, "Y")));
        when(cardRepository.findByCardAcctId(eq(123L), pageableCaptor.capture())).thenReturn(page);

        CardDto.ListResponse response = service.listCards(123L, null, 0);

        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
        verify(cardRepository).findByCardAcctId(eq(123L), any(Pageable.class));
    }

    @Test
    @DisplayName("listCards: an out-of-range account filter raises the account-filter message")
    void listCardsInvalidAccountFilterRaises() {
        assertThatThrownBy(() -> service.listCards(100_000_000_000L, null, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    }

    @Test
    @DisplayName("listCards: a malformed card filter raises the card-filter message")
    void listCardsInvalidCardFilterRaises() {
        assertThatThrownBy(() -> service.listCards(null, "12345", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    }

    @Test
    @DisplayName("listCards: a 16-digit card filter resolves the single matching card")
    void listCardsWithCardFilterResolvesSingleCard() {
        when(cardRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(card(123L, CARD_NUMBER, "Y")));

        CardDto.ListResponse response = service.listCards(null, CARD_NUMBER, 0);

        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).cardNumber()).isEqualTo(CARD_NUMBER);
    }
}
