package com.carddemo.service.card;

import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardUpdateRequest;
import com.carddemo.model.dto.CardUpdateResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.shared.DateValidationService;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card update (maintenance) service &mdash; the Java translation of the COBOL online program
 * {@code COCRDUPC} (card update with confirm flow and optimistic concurrency), source commit
 * {@code 27d6c6f}. Translated by behavior, never copied.
 *
 * <p>Mapping of the COBOL paragraphs that drive this service:
 * {@code 1210-EDIT-ACCOUNT}/{@code 1220-EDIT-CARD} (search-key edits),
 * {@code 1200-EDIT-MAP-INPUTS} no-changes detection (UPPER-CASE compare of the CARDDATA group),
 * {@code 1230-EDIT-NAME}/{@code 1240-EDIT-CARDSTATUS}/{@code 1250-EDIT-EXPIRY-MON}/
 * {@code 1260-EDIT-EXPIRY-YEAR} (field edits, performed as an ordered chain),
 * {@code 9100-GETCARD-BYACCTCARD} (keyed read by card number),
 * {@code 9200-WRITE-PROCESSING} + {@code 9300-CHECK-CHANGE-IN-REC} (the READ-UPDATE/REWRITE with
 * the before/after image compare, expressed here as JPA optimistic locking via {@code @Version}).
 * The expiry day is a non-display field the original program does not allow the user to change
 * ({@code 3200-SETUP-SCREEN-VARS}); the existing day is therefore retained on update.</p>
 */
@Service
public class CardUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_ACCT_FILTER = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";
    private static final String MSG_CARD_FILTER = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";
    private static final String MSG_NAME_ALPHA = "Card name can only contain alphabets and spaces";
    private static final String MSG_STATUS_YN = "Card Active Status must be Y or N";
    private static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";
    private static final String MSG_EXPIRY_DATE = "Card expiry date is not a valid date";
    private static final String MSG_NO_CHANGES = "No change detected with respect to values fetched.";
    private static final String MSG_NOT_FOUND = "Did not find cards for this search condition";
    private static final String MSG_CONCURRENCY = "Record changed by some one else. Please review";
    private static final String MSG_SUCCESS = "Changes committed to database";

    private static final String DATE_FORMAT = "YYYY-MM-DD";
    private static final String ENTITY_NAME = "Card";
    private static final String WILDCARD = "*";
    private static final String NAME_ALLOWED_PATTERN = "[A-Za-z ]+";
    private static final int YEAR_MIN = 1950;
    private static final int YEAR_MAX = 2099;
    private static final int MONTH_MIN = 1;
    private static final int MONTH_MAX = 12;
    private static final int DEFAULT_DAY = 1;

    private final CardRepository cardRepository;
    private final DateValidationService dateValidationService;

    public CardUpdateService(CardRepository cardRepository, DateValidationService dateValidationService) {
        this.cardRepository = cardRepository;
        this.dateValidationService = dateValidationService;
    }

    @Transactional
    public CardUpdateResponse updateCard(CardUpdateRequest request) {
        String acct = normalize(request.accountId());
        String card = normalize(request.cardNumber());
        editSearchKeys(acct, card);

        Card entity = cardRepository.findById(card)
                .orElseThrow(() -> new RecordNotFoundException(MSG_NOT_FOUND));

        String oldExpiry = trimToEmpty(entity.getCardExpiraionDate());
        String oldName = trimToEmpty(entity.getCardEmbossedName());
        String oldStatus = trimToEmpty(entity.getCardActiveStatus());
        String oldYear = expiryYear(oldExpiry);
        String oldMonth = expiryMonth(oldExpiry);
        String oldDay = expiryDay(oldExpiry);

        String newName = normalize(request.nameOnCard());
        String newStatus = normalize(request.cardStatus());
        String newMonth = normalize(request.expirationMonth());
        String newYear = normalize(request.expirationYear());

        if (isUnchanged(newName, newStatus, newMonth, newYear, oldName, oldStatus, oldMonth, oldYear)) {
            return new CardUpdateResponse(acct, card, oldName, oldStatus,
                    oldMonth, oldYear, oldDay, entity.getVersion(), MSG_NO_CHANGES, null);
        }

        editName(newName);
        editCardStatus(newStatus);
        editExpiryMonth(newMonth);
        editExpiryYear(newYear);

        int yearValue = Integer.parseInt(newYear);
        int monthValue = Integer.parseInt(newMonth);
        int dayValue = parseOrDefault(oldDay, DEFAULT_DAY);
        String newExpiry = String.format("%04d-%02d-%02d", yearValue, monthValue, dayValue);

        if (!dateValidationService.validateDate(newExpiry, DATE_FORMAT).isAcceptable()) {
            throw new ValidationException(MSG_EXPIRY_DATE);
        }

        entity.setCardEmbossedName(newName);
        entity.setCardActiveStatus(newStatus);
        entity.setCardExpiraionDate(newExpiry);

        try {
            cardRepository.saveAndFlush(entity);
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyException(MSG_CONCURRENCY, ENTITY_NAME, ex);
        }

        LOG.info("Card update committed for account {}", acct);
        return new CardUpdateResponse(acct, card, newName, newStatus,
                String.format("%02d", monthValue), String.format("%04d", yearValue),
                String.format("%02d", dayValue), entity.getVersion(), MSG_SUCCESS, null);
    }

    private void editSearchKeys(String acct, String card) {
        boolean acctBlank = isBlankOrZeros(acct);
        boolean cardBlank = isBlankOrZeros(card);
        if (acctBlank && cardBlank) {
            throw new ValidationException(MSG_NO_INPUT);
        }
        if (acctBlank) {
            throw new ValidationException(MSG_ACCT_NOT_PROVIDED);
        }
        if (!acct.matches("\\d{11}")) {
            throw new ValidationException(MSG_ACCT_FILTER);
        }
        if (cardBlank) {
            throw new ValidationException(MSG_CARD_NOT_PROVIDED);
        }
        if (!card.matches("\\d{16}")) {
            throw new ValidationException(MSG_CARD_FILTER);
        }
    }

    private void editName(String name) {
        if (isBlankOrZeros(name)) {
            throw new ValidationException(MSG_NAME_NOT_PROVIDED);
        }
        if (!name.matches(NAME_ALLOWED_PATTERN)) {
            throw new ValidationException(MSG_NAME_ALPHA);
        }
    }

    private void editCardStatus(String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw new ValidationException(MSG_STATUS_YN);
        }
    }

    private void editExpiryMonth(String month) {
        Integer value = parseOrNull(month);
        if (value == null || value < MONTH_MIN || value > MONTH_MAX) {
            throw new ValidationException(MSG_EXPIRY_MONTH);
        }
    }

    private void editExpiryYear(String year) {
        Integer value = parseOrNull(year);
        if (value == null || value < YEAR_MIN || value > YEAR_MAX) {
            throw new ValidationException(MSG_EXPIRY_YEAR);
        }
    }

    private static boolean isUnchanged(String newName, String newStatus, String newMonth, String newYear,
                                       String oldName, String oldStatus, String oldMonth, String oldYear) {
        return newName.equalsIgnoreCase(oldName)
                && newStatus.equalsIgnoreCase(oldStatus)
                && numericEquals(newMonth, oldMonth)
                && numericEquals(newYear, oldYear);
    }

    private static boolean numericEquals(String left, String right) {
        Integer leftValue = parseOrNull(left);
        Integer rightValue = parseOrNull(right);
        return leftValue != null && rightValue != null && leftValue.intValue() == rightValue.intValue();
    }

    private static Integer parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !trimmed.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int parseOrDefault(String value, int fallback) {
        Integer parsed = parseOrNull(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return WILDCARD.equals(trimmed) ? "" : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlankOrZeros(String value) {
        return value.isEmpty() || isAllZeros(value);
    }

    private static boolean isAllZeros(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static String expiryYear(String expiryDate) {
        return expiryDate.length() >= 4 ? expiryDate.substring(0, 4) : "";
    }

    private static String expiryMonth(String expiryDate) {
        return expiryDate.length() >= 7 ? expiryDate.substring(5, 7) : "";
    }

    private static String expiryDay(String expiryDate) {
        return expiryDate.length() >= 10 ? expiryDate.substring(8, 10) : "";
    }
}
