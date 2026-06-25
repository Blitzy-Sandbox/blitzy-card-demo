/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated, read-only card-list service backing {@code GET /api/cards}.
 *
 * <p>This service is the Java translation of the CICS pseudo-conversational
 * program {@code COCRDLIC} (transaction {@code CCLI}, card list) at source
 * commit {@code 27d6c6f}. The legacy VSAM browse machinery
 * ({@code STARTBR}/{@code READNEXT}/{@code READPREV} over the
 * {@code WS-SCREEN-ROWS OCCURS 7} display array, with PF7/PF8 page navigation)
 * is replaced by stateless Spring Data pagination: a page-number request
 * parameter drives a {@link PageRequest} of fixed size {@value #PAGE_SIZE}, so
 * no server-side browse cursor or conversational state is retained.</p>
 *
 * <p>The optional account-id and card-id filters reproduce the
 * {@code 9500-FILTER-RECORDS} edit cascade: each filter is independent, and when
 * both are supplied a card must satisfy both (logical AND). A filter that is
 * supplied but malformed raises {@link ValidationException} carrying the
 * byte-accurate legacy message text, whereas an empty match set is a normal
 * outcome that yields an empty response rather than an exception.</p>
 *
 * <p>The component is stateless and therefore thread-safe; it holds only the
 * injected repository reference.</p>
 */
@Service
public class CardListService {

    /**
     * Number of card rows returned per page, equal to the legacy
     * {@code WS-MAX-SCREEN-LINES} constant ({@code VALUE 7}) and the
     * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} display array in {@code COCRDLIC}.
     */
    private static final int PAGE_SIZE = 7;

    /**
     * Inclusive maximum value of the {@code CARD-ACCT-ID PIC 9(11)} account key;
     * an eleven-digit unsigned field tops out at 99,999,999,999.
     */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * Message reported for a supplied but invalid account filter, reproduced
     * byte-for-byte from {@code COCRDLIC} paragraph {@code 2210-EDIT-ACCOUNT}.
     */
    private static final String ACCOUNT_FILTER_MESSAGE =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Message reported for a supplied but invalid card filter, reproduced
     * byte-for-byte from {@code COCRDLIC} paragraph {@code 2220-EDIT-CARD}.
     */
    private static final String CARD_FILTER_MESSAGE =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Matches a sixteen-digit card number, mirroring the {@code CARD-NUM PIC X(16)}
     * width and the legacy numeric test applied to the card filter.
     */
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("\\d{16}");

    private final CardRepository cardRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param cardRepository the repository used to read {@link Card} rows
     */
    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns one page of cards, optionally constrained by an account-id filter
     * and/or a specific card-id filter.
     *
     * <p>Behavior mirrors {@code COCRDLIC} at commit {@code 27d6c6f}:</p>
     * <ul>
     *   <li>Filters are validated first ({@code 2210-EDIT-ACCOUNT} then
     *       {@code 2220-EDIT-CARD}); the account check takes precedence. A filter
     *       that is absent ({@code null}, zero, blank, or all zeros) is treated as
     *       "not supplied", while a supplied but malformed filter raises
     *       {@link ValidationException} with the legacy message text.</li>
     *   <li>A supplied card-id filter identifies at most one card (the
     *       {@code CARD-NUM} primary key); when an account-id filter is also
     *       supplied the card must additionally belong to that account, matching
     *       the {@code 9500-FILTER-RECORDS} logical-AND filter.</li>
     *   <li>Otherwise an account-id filter selects that account's cards, and the
     *       absence of any filter browses all cards; both paths are paginated at
     *       {@value #PAGE_SIZE} rows per page.</li>
     *   <li>An empty match set yields a {@link CardDto.ListResponse} with an empty
     *       card list (the legacy "NO RECORDS FOUND FOR THIS SEARCH CONDITION."
     *       outcome), never an exception.</li>
     * </ul>
     *
     * @param accountIdFilter the optional {@code CARD-ACCT-ID} filter; {@code null}
     *                        or zero means "no account filter"
     * @param cardIdFilter    the optional {@code CARD-NUM} filter; {@code null},
     *                        blank, or all zeros means "no card filter"
     * @param pageNumber      the zero-based page index; negative values are clamped
     *                        to the first page
     * @return the requested page mapped to a {@link CardDto.ListResponse}
     * @throws ValidationException if a supplied filter is malformed
     */
    @Transactional(readOnly = true)
    public CardDto.ListResponse listCards(Long accountIdFilter, String cardIdFilter, int pageNumber) {
        boolean accountSupplied = accountIdFilter != null && accountIdFilter != 0L;
        if (accountSupplied && (accountIdFilter < 0L || accountIdFilter > MAX_ACCOUNT_ID)) {
            throw new ValidationException(ACCOUNT_FILTER_MESSAGE);
        }

        String cardFilter = normalizeCardFilter(cardIdFilter);
        int safePage = Math.max(pageNumber, 0);
        Long effectiveAccountId = accountSupplied ? accountIdFilter : null;

        List<Card> cards;
        if (cardFilter != null) {
            cards = resolveSpecificCard(effectiveAccountId, cardFilter, safePage);
        } else {
            Pageable pageable = PageRequest.of(safePage, PAGE_SIZE);
            Page<Card> page = accountSupplied
                    ? cardRepository.findByCardAcctId(accountIdFilter, pageable)
                    : cardRepository.findAll(pageable);
            cards = page.getContent();
        }

        return buildResponse(safePage, effectiveAccountId, cardFilter, cards);
    }

    /**
     * Normalizes and validates the card-id filter.
     *
     * @param cardIdFilter the raw filter value
     * @return the sixteen-digit card number when supplied, or {@code null} when the
     *         filter is absent (null, blank, or all zeros)
     * @throws ValidationException if the filter is supplied but is not a
     *                             sixteen-digit number
     */
    private String normalizeCardFilter(String cardIdFilter) {
        if (cardIdFilter == null) {
            return null;
        }
        String trimmed = cardIdFilter.trim();
        if (trimmed.isEmpty() || isAllZeros(trimmed)) {
            return null;
        }
        if (!CARD_NUMBER_PATTERN.matcher(trimmed).matches()) {
            throw new ValidationException(CARD_FILTER_MESSAGE);
        }
        return trimmed;
    }

    /**
     * Resolves the single card identified by its primary key, applying the
     * optional account constraint and the first-page restriction.
     *
     * @param accountId  the account the card must belong to, or {@code null} for
     *                   no account constraint
     * @param cardNumber the sixteen-digit card number to look up
     * @param safePage   the zero-based page index
     * @return a list holding the matching card, or an empty list when no card
     *         matches or when a page beyond the first is requested
     */
    private List<Card> resolveSpecificCard(Long accountId, String cardNumber, int safePage) {
        if (safePage != 0) {
            return List.of();
        }
        Card card = cardRepository.findById(cardNumber).orElse(null);
        if (card == null) {
            return List.of();
        }
        if (accountId != null && !accountId.equals(card.getCardAcctId())) {
            return List.of();
        }
        return List.of(card);
    }

    /**
     * Builds the list response from the resolved cards and the paging inputs.
     *
     * @param pageNumber       the zero-based page index being returned
     * @param accountIdFilter  the effective account filter, or {@code null}
     * @param cardNumberFilter the effective card filter, or {@code null}
     * @param cards            the cards on the current page
     * @return the populated {@link CardDto.ListResponse}
     */
    private CardDto.ListResponse buildResponse(int pageNumber, Long accountIdFilter,
            String cardNumberFilter, List<Card> cards) {
        List<CardDto.CardSummary> summaries = new ArrayList<>(cards.size());
        for (Card card : cards) {
            summaries.add(toSummary(card));
        }
        String accountFilterText = accountIdFilter == null ? null : accountIdFilter.toString();
        return new CardDto.ListResponse(
                Integer.toString(pageNumber),
                accountFilterText,
                cardNumberFilter,
                summaries);
    }

    /**
     * Maps a {@link Card} entity to a {@link CardDto.CardSummary} list row,
     * reproducing the {@code COCRDLIC} row population ({@code CARD-ACCT-ID} to
     * {@code WS-ROW-ACCTNO}, {@code CARD-NUM} to {@code WS-ROW-CARD-NUM}, and
     * {@code CARD-ACTIVE-STATUS} to {@code WS-ROW-CARD-STATUS}).
     *
     * @param card the source entity
     * @return the mapped summary row
     */
    private CardDto.CardSummary toSummary(Card card) {
        String accountId = card.getCardAcctId() == null ? null : card.getCardAcctId().toString();
        return new CardDto.CardSummary(accountId, card.getCardNum(), card.getActiveStatus());
    }

    /**
     * Reports whether the supplied non-empty text consists solely of the digit
     * zero, matching the legacy not-supplied test on an all-zeros filter.
     *
     * @param value the trimmed, non-empty filter text
     * @return {@code true} if every character is {@code '0'}
     */
    private static boolean isAllZeros(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
