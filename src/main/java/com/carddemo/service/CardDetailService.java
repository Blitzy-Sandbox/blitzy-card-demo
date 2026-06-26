/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service backing the card-detail endpoint {@code GET /api/cards/{cardNum}}.
 *
 * <p>Retrieves a single card by its 16-digit card number, optionally constrained
 * by owning account, and maps it onto a {@link CardDto.Detail} response.</p>
 */
@Service
public class CardDetailService {

    /** Matches a card number consisting of exactly sixteen decimal digits. */
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("\\d{16}");

    /** Lowest valid account identifier (a non-zero eleven-digit number). */
    private static final long MIN_ACCOUNT_ID = 1L;

    /** Highest valid account identifier (eleven nines). */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    private final CardRepository cardRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param cardRepository the repository used to read card records
     */
    public CardDetailService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns the detail view of a single card.
     *
     * @param accountId  the owning account identifier; optional, may be
     *                   {@code null} (or zero, treated as not supplied) when the
     *                   card number alone identifies the card
     * @param cardNumber the sixteen-digit card number
     * @return the card detail mapped onto a {@link CardDto.Detail}
     * @throws ValidationException     when no search criterion is supplied, the
     *                                 card number is missing or not sixteen
     *                                 digits, or the supplied account is not a
     *                                 non-zero eleven-digit number
     * @throws RecordNotFoundException when no card matches the card number, or
     *                                 the matched card belongs to a different
     *                                 account than the one supplied
     */
    @Transactional(readOnly = true)
    public CardDto.Detail getCard(Long accountId, String cardNumber) {
        boolean cardBlank = cardNumber == null || cardNumber.isBlank();
        boolean accountSupplied = accountId != null && accountId != 0L;

        if (cardBlank && !accountSupplied) {
            throw new ValidationException("No input received");
        }
        if (cardBlank) {
            throw new ValidationException("Card number not provided");
        }
        if (!CARD_NUMBER_PATTERN.matcher(cardNumber).matches()) {
            throw new ValidationException("Card number if supplied must be a 16 digit number");
        }
        if (accountSupplied && (accountId < MIN_ACCOUNT_ID || accountId > MAX_ACCOUNT_ID)) {
            throw new ValidationException("Account number must be a non zero 11 digit number");
        }

        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Did not find this account in cards database"));

        if (accountSupplied && !accountId.equals(card.getCardAcctId())) {
            throw new RecordNotFoundException("Did not find cards for this search condition");
        }

        return toDetail(card);
    }

    /**
     * Maps a persisted {@link Card} onto its {@link CardDto.Detail} projection.
     *
     * @param card the card record to map
     * @return the detail projection
     */
    private CardDto.Detail toDetail(Card card) {
        String expirationDate = card.getExpirationDate();
        return new CardDto.Detail(
                formatAccountId(card.getCardAcctId()),
                card.getCardNum(),
                card.getEmbossedName(),
                card.getActiveStatus(),
                extractExpiryMonth(expirationDate),
                extractExpiryYear(expirationDate),
                extractExpiryDay(expirationDate),
                card.getVersion());
    }

    /**
     * Formats an account identifier as an eleven-digit, zero-padded string,
     * mirroring the legacy {@code PIC 9(11)} screen field width.
     *
     * @param cardAcctId the account identifier; may be {@code null}
     * @return the zero-padded identifier, or an empty string when {@code null}
     */
    private static String formatAccountId(Long cardAcctId) {
        if (cardAcctId == null) {
            return "";
        }
        return String.format("%011d", cardAcctId);
    }

    /**
     * Extracts the {@code YYYY} year segment from a {@code YYYY-MM-DD} date.
     *
     * @param expirationDate the ten-character expiration date; may be
     *                       {@code null} or shorter than expected
     * @return the four-character year segment, or an empty string when absent
     */
    private static String extractExpiryYear(String expirationDate) {
        if (expirationDate == null || expirationDate.length() < 4) {
            return "";
        }
        return expirationDate.substring(0, 4);
    }

    /**
     * Extracts the {@code MM} month segment from a {@code YYYY-MM-DD} date.
     *
     * @param expirationDate the ten-character expiration date; may be
     *                       {@code null} or shorter than expected
     * @return the two-character month segment, or an empty string when absent
     */
    private static String extractExpiryMonth(String expirationDate) {
        if (expirationDate == null || expirationDate.length() < 7) {
            return "";
        }
        return expirationDate.substring(5, 7);
    }

    /**
     * Extracts the {@code DD} day segment from a {@code YYYY-MM-DD} date.
     *
     * <p>The legacy {@code COCRDSL} detail map shows only MM/YY; the day is
     * surfaced here from the persisted expiration date so a stateless client can
     * perform a read-modify-write against {@code COCRDUP} (which requires the day)
     * using only API-exposed fields, mirroring the way {@code COCRDUPC} pre-filled
     * the day from the VSAM record.</p>
     *
     * @param expirationDate the ten-character expiration date; may be
     *                       {@code null} or shorter than expected
     * @return the two-character day segment, or an empty string when absent
     */
    private static String extractExpiryDay(String expirationDate) {
        if (expirationDate == null || expirationDate.length() < 10) {
            return "";
        }
        return expirationDate.substring(8, 10);
    }
}
