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

import java.util.Optional;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.CardDetailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardDetailService}, the
 * read-only keyed card lookup (the Java realization of the COBOL {@code COCRDSLC}
 * detail program @ {@code 27d6c6f}).
 *
 * <p>The tests lock the input-validation cascade (no input, card-number missing,
 * card-number format, account-number range), the keyed not-found and
 * account/card mismatch messages, and the expiry-segment projection of the
 * returned {@link CardDto.Detail}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService - COCRDSLC keyed card lookup @ 27d6c6f")
class CardDetailServiceTest {

    private static final String CARD_NUMBER = "1234567890123456";

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardDetailService service;

    private Card card() {
        Card card = new Card();
        card.setCardNum(CARD_NUMBER);
        card.setCardAcctId(123L);
        card.setEmbossedName("John Public");
        card.setActiveStatus("Y");
        card.setExpirationDate("2025-12-31");
        return card;
    }

    @Test
    @DisplayName("getCard: no card and no account raises 'No input received'")
    void getCardNoInputRaisesNoInput() {
        assertThatThrownBy(() -> service.getCard(null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    @Test
    @DisplayName("getCard: blank card with an account supplied raises 'Card number not provided'")
    void getCardBlankCardRaisesCardNotProvided() {
        assertThatThrownBy(() -> service.getCard(123L, "  "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");
    }

    @Test
    @DisplayName("getCard: malformed card number raises the 16-digit format message")
    void getCardBadFormatRaisesFormatMessage() {
        assertThatThrownBy(() -> service.getCard(null, "12345"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number if supplied must be a 16 digit number");
    }

    @Test
    @DisplayName("getCard: out-of-range account number raises the account-number message")
    void getCardAccountOutOfRangeRaisesAccountMessage() {
        assertThatThrownBy(() -> service.getCard(100_000_000_000L, CARD_NUMBER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");
    }

    @Test
    @DisplayName("getCard: unknown card number raises the cards-database not-found message")
    void getCardUnknownCardRaisesNotFound() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCard(null, CARD_NUMBER))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in cards database");
    }

    @Test
    @DisplayName("getCard: account that does not own the card raises the search-condition message")
    void getCardAccountMismatchRaisesSearchCondition() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card()));

        assertThatThrownBy(() -> service.getCard(999L, CARD_NUMBER))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    @Test
    @DisplayName("getCard: resolved card projects account id and expiry segments onto the detail")
    void getCardResolvesDetail() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card()));

        CardDto.Detail detail = service.getCard(123L, CARD_NUMBER);

        assertThat(detail).isNotNull();
        assertThat(detail.accountId()).isEqualTo("00000000123");
        assertThat(detail.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(detail.cardholderName()).isEqualTo("John Public");
        assertThat(detail.cardStatus()).isEqualTo("Y");
        assertThat(detail.expiryMonth()).isEqualTo("12");
        assertThat(detail.expiryYear()).isEqualTo("2025");
    }
}
