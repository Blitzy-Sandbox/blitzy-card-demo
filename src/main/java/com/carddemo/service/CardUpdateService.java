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
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

import jakarta.persistence.OptimisticLockException;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service backing the card-update flow ({@code PUT /api/cards/{cardNum}}),
 * translating the COBOL transaction program {@code COCRDUPC} (CCUP) from copybook
 * source commit {@code 27d6c6f}.
 *
 * <p>The single public operation, {@link #updateCard(Long, String, CardDto.UpdateRequest)},
 * runs the card-field edit cascade ({@code 1210}-account, {@code 1220}-card,
 * {@code 1230}-name, {@code 1240}-card-status, {@code 1250}-expiry-month,
 * {@code 1260}-expiry-year) as a sequence of private guarded checks that preserve
 * the field evaluation order and byte-exact operator messages. The full assembled
 * expiry date is delegated to {@link DateValidationService} for calendar
 * validation.</p>
 *
 * <p>The update is committed atomically within a single {@code @Transactional}
 * boundary; an optimistic-locking conflict on the {@link Card} {@code @Version}
 * attribute is surfaced as {@link ConcurrentUpdateException}.</p>
 *
 * <p>Design rationale (the {@code @Version} translation of the
 * {@code 9300-CHECK-CHANGE-IN-REC} re-read-and-compare guard and the
 * {@code @Transactional}/{@code SYNCPOINT} boundary) is recorded in
 * {@code DECISION_LOG.md} (D-007, D-008); COBOL paragraph&#8594;method mappings
 * are in {@code TRACEABILITY_MATRIX.md}.</p>
 */
@Service
public class CardUpdateService {

    private static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";
    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";
    private static final String MSG_NAME_ALPHA = "Card name can only contain alphabets and spaces";
    private static final String MSG_STATUS_YES_NO = "Card Active Status must be Y or N";
    private static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";
    private static final String MSG_ACCOUNT_ELEVEN_DIGITS =
            "Account number must be a non zero 11 digit number";
    private static final String MSG_CARD_SIXTEEN_DIGITS =
            "Card number if supplied must be a 16 digit number";
    private static final String MSG_NO_CHANGE = "No change detected with respect to values fetched.";
    private static final String MSG_NOT_FOUND_CARD = "Did not find this account in cards database";
    private static final String MSG_NOT_FOUND_COMBO = "Did not find cards for this search condition";

    private static final String FIELD_ACCOUNT_ID = "accountId";
    private static final String FIELD_CARD_NUMBER = "cardNumber";
    private static final String FIELD_CARD_NAME = "cardholderName";
    private static final String FIELD_CARD_STATUS = "cardStatus";
    private static final String FIELD_EXPIRY_MONTH = "expiryMonth";
    private static final String FIELD_EXPIRY_YEAR = "expiryYear";
    private static final String LABEL_EXPIRY_DATE = "Card expiry date";

    private static final int ACCOUNT_LENGTH = 11;
    private static final int CARD_LENGTH = 16;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;
    private static final int MIN_YEAR = 1950;
    private static final int MAX_YEAR = 2099;
    private static final int MAX_NUMERIC_DIGITS = 9;

    private static final int EXPIRY_YEAR_BEGIN = 0;
    private static final int EXPIRY_YEAR_END = 4;
    private static final int EXPIRY_MONTH_BEGIN = 5;
    private static final int EXPIRY_MONTH_END = 7;
    private static final int EXPIRY_DATE_LENGTH = 10;
    private static final int EXPIRY_YEAR_WIDTH = 4;
    private static final int EXPIRY_SEGMENT_WIDTH = 2;

    private final CardRepository cardRepository;
    private final DateValidationService dateValidationService;

    /**
     * Creates the service with its collaborating beans.
     *
     * @param cardRepository        repository providing keyed card access and
     *                              the version-checked persistence used for the
     *                              update
     * @param dateValidationService shared calendar-validation service used to
     *                              validate the assembled card expiry date
     */
    public CardUpdateService(CardRepository cardRepository,
                             DateValidationService dateValidationService) {
        this.cardRepository = cardRepository;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Validates and applies an update to a single card, committing the change
     * atomically.
     *
     * <p>The request fields are validated in order; the assembled expiry date is
     * validated through {@link DateValidationService}; the target card is loaded
     * by its number and, when an account identifier is supplied, verified to
     * belong to that account; the validated values are applied; and the record
     * is persisted under optimistic locking. A concurrent modification is
     * surfaced as a {@link ConcurrentUpdateException}.</p>
     *
     * @param accountId  the owning account identifier used to confirm the card
     *                   belongs to the requested account; may be {@code null} to
     *                   skip the ownership check
     * @param cardNumber the sixteen-character card number identifying the record
     *                   to update
     * @param request    the requested new card values
     * @return the refreshed card detail after a successful update
     * @throws ValidationException        if any input field fails validation
     *                                    (HTTP 400)
     * @throws RecordNotFoundException    if no card matches the supplied number,
     *                                    or it is not owned by the supplied
     *                                    account (HTTP 404)
     * @throws ConcurrentUpdateException  if the record was changed concurrently
     *                                    (HTTP 409)
     */
    @Transactional(rollbackFor = Exception.class)
    public CardDto.Detail updateCard(Long accountId, String cardNumber, CardDto.UpdateRequest request) {
        validateInputs(request);
        validateExpiryDate(request);
        Card card = loadCard(accountId, cardNumber);
        ensureChangeDetected(card, request);
        applyChanges(card, request);
        Card saved = persist(card);
        return toDetail(saved);
    }

    private void validateInputs(CardDto.UpdateRequest request) {
        String account = (request == null) ? null : request.accountId();
        String card = (request == null) ? null : request.cardNumber();
        boolean accountBlank = isBlankOrZeros(account);
        boolean cardBlank = isBlankOrZeros(card);

        Map<String, String> keyErrors = new LinkedHashMap<>();
        if (accountBlank) {
            keyErrors.put(FIELD_ACCOUNT_ID, MSG_ACCOUNT_NOT_PROVIDED);
        } else if (!isValidAccountFormat(account)) {
            keyErrors.put(FIELD_ACCOUNT_ID, MSG_ACCOUNT_ELEVEN_DIGITS);
        }
        if (cardBlank) {
            keyErrors.put(FIELD_CARD_NUMBER, MSG_CARD_NOT_PROVIDED);
        } else if (!isValidCardFormat(card)) {
            keyErrors.put(FIELD_CARD_NUMBER, MSG_CARD_SIXTEEN_DIGITS);
        }

        if (accountBlank && cardBlank) {
            throw new ValidationException(MSG_NO_INPUT, keyErrors);
        }
        if (!keyErrors.isEmpty()) {
            throw new ValidationException(firstMessage(keyErrors), keyErrors);
        }

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        validateName(request, fieldErrors);
        validateStatus(request, fieldErrors);
        validateExpiryMonth(request, fieldErrors);
        validateExpiryYear(request, fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(firstMessage(fieldErrors), fieldErrors);
        }
    }

    private static void validateName(CardDto.UpdateRequest request, Map<String, String> errors) {
        String name = request.cardholderName();
        if (isBlank(name)) {
            errors.put(FIELD_CARD_NAME, MSG_NAME_NOT_PROVIDED);
            return;
        }
        if (!isAlphaSpace(name)) {
            errors.put(FIELD_CARD_NAME, MSG_NAME_ALPHA);
        }
    }

    private static void validateStatus(CardDto.UpdateRequest request, Map<String, String> errors) {
        if (!isYesNo(request.cardStatus())) {
            errors.put(FIELD_CARD_STATUS, MSG_STATUS_YES_NO);
        }
    }

    private static void validateExpiryMonth(CardDto.UpdateRequest request, Map<String, String> errors) {
        if (!isNumericInRange(request.expiryMonth(), MIN_MONTH, MAX_MONTH)) {
            errors.put(FIELD_EXPIRY_MONTH, MSG_EXPIRY_MONTH);
        }
    }

    private static void validateExpiryYear(CardDto.UpdateRequest request, Map<String, String> errors) {
        if (!isNumericInRange(request.expiryYear(), MIN_YEAR, MAX_YEAR)) {
            errors.put(FIELD_EXPIRY_YEAR, MSG_EXPIRY_YEAR);
        }
    }

    private void validateExpiryDate(CardDto.UpdateRequest request) {
        dateValidationService.validateDateParts(
                trimToEmpty(request.expiryYear()),
                trimToEmpty(request.expiryMonth()),
                trimToEmpty(request.expiryDay()),
                LABEL_EXPIRY_DATE);
    }

    private Card loadCard(Long accountId, String cardNumber) {
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(MSG_NOT_FOUND_CARD));
        if (accountId != null && !accountId.equals(card.getCardAcctId())) {
            throw new RecordNotFoundException(MSG_NOT_FOUND_COMBO);
        }
        return card;
    }

    private static void ensureChangeDetected(Card card, CardDto.UpdateRequest request) {
        boolean nameSame = equalsIgnoreCaseTrimmed(request.cardholderName(), card.getEmbossedName());
        boolean statusSame = equalsIgnoreCaseTrimmed(request.cardStatus(), card.getActiveStatus());
        boolean expirySame = assembleExpiry(request).equals(trimToEmpty(card.getExpirationDate()));
        if (nameSame && statusSame && expirySame) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put(FIELD_CARD_NUMBER, MSG_NO_CHANGE);
            throw new ValidationException(MSG_NO_CHANGE, errors);
        }
    }

    private static void applyChanges(Card card, CardDto.UpdateRequest request) {
        card.setEmbossedName(request.cardholderName());
        card.setActiveStatus(request.cardStatus());
        card.setExpirationDate(assembleExpiry(request));
    }

    private Card persist(Card card) {
        try {
            return cardRepository.saveAndFlush(card);
        } catch (OptimisticLockingFailureException | OptimisticLockException ex) {
            throw new ConcurrentUpdateException(ex);
        }
    }

    private static CardDto.Detail toDetail(Card card) {
        String expirationDate = trimToEmpty(card.getExpirationDate());
        String expiryYear = "";
        String expiryMonth = "";
        if (expirationDate.length() >= EXPIRY_DATE_LENGTH) {
            expiryYear = expirationDate.substring(EXPIRY_YEAR_BEGIN, EXPIRY_YEAR_END);
            expiryMonth = expirationDate.substring(EXPIRY_MONTH_BEGIN, EXPIRY_MONTH_END);
        }
        String accountId = (card.getCardAcctId() == null)
                ? null
                : formatAccountId(card.getCardAcctId());
        return new CardDto.Detail(
                accountId,
                card.getCardNum(),
                card.getEmbossedName(),
                card.getActiveStatus(),
                expiryMonth,
                expiryYear);
    }

    private static String assembleExpiry(CardDto.UpdateRequest request) {
        return padNumeric(request.expiryYear(), EXPIRY_YEAR_WIDTH) + "-"
                + padNumeric(request.expiryMonth(), EXPIRY_SEGMENT_WIDTH) + "-"
                + padNumeric(request.expiryDay(), EXPIRY_SEGMENT_WIDTH);
    }

    private static String formatAccountId(long accountId) {
        String digits = Long.toString(accountId);
        if (digits.length() >= ACCOUNT_LENGTH) {
            return digits;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = digits.length(); i < ACCOUNT_LENGTH; i++) {
            builder.append('0');
        }
        return builder.append(digits).toString();
    }

    private static String padNumeric(String value, int width) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isEmpty() || !isAllDigits(trimmed) || trimmed.length() >= width) {
            return trimmed;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = trimmed.length(); i < width; i++) {
            builder.append('0');
        }
        return builder.append(trimmed).toString();
    }

    private static String firstMessage(Map<String, String> errors) {
        return errors.values().iterator().next();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isBlankOrZeros(String value) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidAccountFormat(String value) {
        String trimmed = value.trim();
        return trimmed.length() == ACCOUNT_LENGTH && isAllDigits(trimmed);
    }

    private static boolean isValidCardFormat(String value) {
        String trimmed = value.trim();
        return trimmed.length() == CARD_LENGTH && isAllDigits(trimmed);
    }

    private static boolean isAlphaSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean alpha = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            if (!alpha && ch != ' ') {
                return false;
            }
        }
        return true;
    }

    private static boolean isYesNo(String value) {
        return "Y".equals(value) || "N".equals(value);
    }

    private static boolean isNumericInRange(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NUMERIC_DIGITS || !isAllDigits(trimmed)) {
            return false;
        }
        int parsed = Integer.parseInt(trimmed);
        return parsed >= min && parsed <= max;
    }

    private static boolean equalsIgnoreCaseTrimmed(String left, String right) {
        return trimToEmpty(left).equalsIgnoreCase(trimToEmpty(right));
    }

    private static String trimToEmpty(String value) {
        return (value == null) ? "" : value.trim();
    }
}
