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
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.DateValidationService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardUpdateService}, the
 * transactional card-update operation (the Java realization of the COBOL
 * {@code COCRDUPC} update program @ {@code 27d6c6f}).
 *
 * <p>The tests lock the field-edit cascade order ({@code 1210}-account through
 * {@code 1260}-expiry-year), the delegation of the assembled expiry date to
 * {@link DateValidationService}, the keyed load with account-ownership check,
 * the no-change guard, the happy-path persistence, and the translation of an
 * optimistic-locking conflict (the {@code 9300-CHECK-CHANGE-IN-REC} replacement)
 * into a {@link ConcurrentUpdateException}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService - COCRDUPC transactional card update @ 27d6c6f")
class CardUpdateServiceTest {

    private static final String ACCOUNT_ID = "00000000123";
    private static final Long ACCOUNT_ID_LONG = 123L;
    private static final String CARD_NUMBER = "1234567890123456";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private CardUpdateService service;

    private CardDto.UpdateRequest validRequest() {
        return new CardDto.UpdateRequest(ACCOUNT_ID, CARD_NUMBER, "John Public", "Y", "12", "2025", "31");
    }

    private Card existingCard(String name, String status, String expiry) {
        Card card = new Card();
        card.setCardNum(CARD_NUMBER);
        card.setCardAcctId(ACCOUNT_ID_LONG);
        card.setEmbossedName(name);
        card.setActiveStatus(status);
        card.setExpirationDate(expiry);
        return card;
    }

    @Test
    @DisplayName("updateCard: blank account and card keys raise 'No input received'")
    void updateCardNoInputRaisesNoInput() {
        CardDto.UpdateRequest request =
                new CardDto.UpdateRequest("", "", "John Public", "Y", "12", "2025", "31");

        assertThatThrownBy(() -> service.updateCard(null, null, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    @Test
    @DisplayName("updateCard: a non 11-digit account raises the account-format message")
    void updateCardInvalidAccountFormatRaises() {
        CardDto.UpdateRequest request =
                new CardDto.UpdateRequest("123", CARD_NUMBER, "John Public", "Y", "12", "2025", "31");

        assertThatThrownBy(() -> service.updateCard(null, CARD_NUMBER, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");
    }

    @Test
    @DisplayName("updateCard: an out-of-range expiry month is reported before the expiry year")
    void updateCardInvalidExpiryMonthRaises() {
        CardDto.UpdateRequest request =
                new CardDto.UpdateRequest(ACCOUNT_ID, CARD_NUMBER, "John Public", "Y", "13", "2025", "31");

        assertThatThrownBy(() -> service.updateCard(null, CARD_NUMBER, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
    }

    @Test
    @DisplayName("updateCard: an invalid assembled expiry date from DateValidationService propagates")
    void updateCardInvalidExpiryDatePropagates() {
        when(dateValidationService.validateDateParts(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ValidationException("Card expiry date is not a valid date"));

        assertThatThrownBy(() -> service.updateCard(null, CARD_NUMBER, validRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry date is not a valid date");
    }

    @Test
    @DisplayName("updateCard: an unknown card number raises the cards-database not-found message")
    void updateCardUnknownCardRaisesNotFound() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCard(null, CARD_NUMBER, validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in cards database");
    }

    @Test
    @DisplayName("updateCard: a card not owned by the supplied account raises the search-condition message")
    void updateCardAccountMismatchRaisesSearchCondition() {
        Card card = existingCard("Old Name", "N", "2020-01-01");
        card.setCardAcctId(999L);
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID_LONG, CARD_NUMBER, validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    @Test
    @DisplayName("updateCard: identical submitted values raise the no-change guard")
    void updateCardNoChangeRaises() {
        when(cardRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(existingCard("John Public", "Y", "2025-12-31")));

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID_LONG, CARD_NUMBER, validRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No change detected with respect to values fetched.");
    }

    @Test
    @DisplayName("updateCard: a valid change is applied, flushed, and projected onto the detail")
    void updateCardHappyPathPersistsAndProjects() {
        Card card = existingCard("Old Name", "N", "2020-01-01");
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));
        when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto.Detail detail = service.updateCard(ACCOUNT_ID_LONG, CARD_NUMBER, validRequest());

        assertThat(detail.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(detail.cardholderName()).isEqualTo("John Public");
        assertThat(detail.cardStatus()).isEqualTo("Y");
        assertThat(detail.expiryMonth()).isEqualTo("12");
        assertThat(detail.expiryYear()).isEqualTo("2025");
        verify(cardRepository).saveAndFlush(card);
    }

    @Test
    @DisplayName("updateCard: an optimistic-locking conflict is translated to ConcurrentUpdateException")
    void updateCardOptimisticLockMapsToConcurrentUpdate() {
        Card card = existingCard("Old Name", "N", "2020-01-01");
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));
        when(cardRepository.saveAndFlush(any(Card.class)))
                .thenThrow(new OptimisticLockingFailureException("version conflict"));

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID_LONG, CARD_NUMBER, validRequest()))
                .isInstanceOf(ConcurrentUpdateException.class);
    }
}
