package com.cardemo.service.card;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.exception.ValidationException.FieldError;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for card update operations — migrated from COCRDUPC.cbl (1,560 lines).
 *
 * <p>Implements optimistic concurrency via JPA {@code @Version} on the {@link Card} entity,
 * replacing the COBOL CICS READ UPDATE + snapshot comparison pattern (paragraph
 * 9300-CHECK-CHANGE-IN-REC). Handles card status, expiration date, CVV, and embossed
 * name updates with field-by-field validation that collects all errors before returning
 * (matching COBOL behavior of marking all invalid fields rather than short-circuiting).</p>
 *
 * <p>Key COBOL paragraph mappings:</p>
 * <ul>
 *   <li>9000-READ-DATA / 9100-GETCARD-BYACCTCARD → {@link #getCardForUpdate(String)}</li>
 *   <li>1200-EDIT-MAP-INPUTS / 1230–1260 → {@link #validateFields(CardDto)}</li>
 *   <li>1200-CHECK-FOR-CHANGES → {@link #hasChanges(Card, CardDto)}</li>
 *   <li>9200-WRITE-PROCESSING → {@link #updateCard(String, CardDto)} save step</li>
 *   <li>9300-CHECK-CHANGE-IN-REC → JPA {@code @Version} optimistic locking</li>
 * </ul>
 *
 * @see Card
 * @see CardRepository
 */
@Service
public class CardUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(CardUpdateService.class);

    /** Maximum length for account ID field — PIC 9(11) from CVACT02Y.cpy. */
    private static final int ACCT_ID_MAX_LENGTH = 11;

    /** Maximum length for card number field — PIC X(16) from CVACT02Y.cpy. */
    private static final int CARD_NUM_MAX_LENGTH = 16;

    /** Regex pattern for numeric-only validation — matches COBOL IS NUMERIC check. */
    private static final String NUMERIC_PATTERN = "^\\d+$";

    /**
     * Regex pattern for alphabets and spaces only — matches COBOL 1230-EDIT-NAME
     * INSPECT CONVERTING check (LIT-ALL-ALPHA-FROM → LIT-ALL-SPACES-TO).
     */
    private static final String ALPHA_SPACE_PATTERN = "^[A-Za-z ]+$";

    /** Minimum valid expiration year per COBOL 1260-EDIT-EXPIRY-YEAR. */
    private static final int MIN_EXPIRY_YEAR = 1950;

    /** Maximum valid expiration year per COBOL 1260-EDIT-EXPIRY-YEAR. */
    private static final int MAX_EXPIRY_YEAR = 2099;

    /** Minimum valid expiration month per COBOL 1250-EDIT-EXPIRY-MON. */
    private static final int MIN_EXPIRY_MONTH = 1;

    /** Maximum valid expiration month per COBOL 1250-EDIT-EXPIRY-MON. */
    private static final int MAX_EXPIRY_MONTH = 12;

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;

    /**
     * Constructs a CardUpdateService with required repository dependencies.
     *
     * @param cardRepository    repository for Card entity CRUD operations
     * @param accountRepository repository for Account entity lookups (9100-READ-ACCT)
     */
    public CardUpdateService(CardRepository cardRepository, AccountRepository accountRepository) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Retrieves a card record for editing — maps COBOL paragraph 9000-READ-DATA /
     * 9100-GETCARD-BYACCTCARD.
     *
     * <p>Performs a read-only transactional lookup of the card by its primary key (card number).
     * Maps to {@code EXEC CICS READ FILE(CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)}.</p>
     *
     * @param cardNum the 16-digit card number (primary key)
     * @return {@link CardDto} populated with current card data including version for
     *         optimistic locking on subsequent update
     * @throws RecordNotFoundException if no card exists with the given number (FILE STATUS 23)
     */
    @Transactional(readOnly = true)
    public CardDto getCardForUpdate(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            throw new RecordNotFoundException("Card", cardNum);
        }

        logger.info("Retrieving card ending in {} for update", maskCardNumber(cardNum));

        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    logger.warn("Card not found: ending in {}", maskCardNumber(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        // Verify associated account still exists (informational integrity check)
        if (card.getCardAcctId() != null && !card.getCardAcctId().isBlank()) {
            boolean acctExists = accountRepository.existsById(card.getCardAcctId());
            if (!acctExists) {
                logger.warn("Card's associated account ending in {} not found",
                        maskAccountId(card.getCardAcctId()));
            }
        }

        logger.debug("Retrieved card ending in {} with version {}",
                maskCardNumber(card.getCardNum()), card.getVersion());
        return toCardDto(card);
    }

    /**
     * Validates and saves card updates — maps COBOL paragraphs 1100-VALIDATE-CARD-DATA,
     * 1200-CHECK-FOR-CHANGES, 9200-WRITE-PROCESSING, and 9300-CHECK-CHANGE-IN-REC.
     *
     * <p>Processing steps (matching COBOL state machine flow):</p>
     * <ol>
     *   <li>Fetch current card record (9100-GETCARD-BYACCTCARD)</li>
     *   <li>Field-by-field validation collecting ALL errors (1100-VALIDATE-CARD-DATA)</li>
     *   <li>Verify associated account exists (9100-READ-ACCT)</li>
     *   <li>Change detection with upper-case comparison (1200-CHECK-FOR-CHANGES)</li>
     *   <li>Apply field changes — cardNum and cardAcctId are NEVER modified (key fields)</li>
     *   <li>Save with JPA @Version optimistic locking (9200-WRITE-CARD / 9300-CHECK-CHANGE-IN-REC)</li>
     * </ol>
     *
     * @param cardNum       the card number identifying the card to update
     * @param updateRequest DTO containing the updated field values
     * @return {@link CardDto} with the saved card data
     * @throws RecordNotFoundException          if card or associated account not found
     * @throws ValidationException              if one or more field validations fail
     * @throws ConcurrentModificationException  if another user modified the record concurrently
     */
    @Transactional
    public CardDto updateCard(String cardNum, CardDto updateRequest) {
        logger.info("Updating card ending in {}", maskCardNumber(cardNum));

        // Step 1: Fetch current record (maps 9000-READ-DATA / 9100-GETCARD-BYACCTCARD)
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    logger.warn("Card not found for update: ending in {}", maskCardNumber(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        // Step 2: Field-by-field validation (maps 1100-VALIDATE-CARD-DATA paragraphs 1210–1260)
        // Collects ALL errors before throwing — matches COBOL behavior of marking all bad fields
        validateFields(updateRequest);

        // Step 3: Verify associated account exists (maps 9100-READ-ACCT)
        verifyAccountExists(updateRequest.getCardAcctId());

        // Step 4: Change detection (maps 1200-CHECK-FOR-CHANGES with FUNCTION UPPER-CASE)
        if (!hasChanges(card, updateRequest)) {
            logger.info("No changes detected for card ending in {}", maskCardNumber(cardNum));
            return toCardDto(card);
        }

        // Step 5: Apply changes — cardNum and cardAcctId are NEVER updated (key fields per COBOL)
        card.setCardCvvCd(updateRequest.getCardCvvCd());
        card.setCardEmbossedName(updateRequest.getCardEmbossedName());
        card.setCardExpDate(updateRequest.getCardExpDate());
        card.setCardActiveStatus(updateRequest.getCardActiveStatus());

        // Step 6: Save with optimistic locking (maps 9200-WRITE-CARD / 9300-CHECK-CHANGE-IN-REC)
        // JPA @Version on Card entity triggers OptimisticLockException on version mismatch
        try {
            Card savedCard = cardRepository.save(card);
            logger.info("Successfully updated card ending in {}", maskCardNumber(cardNum));
            return toCardDto(savedCard);
        } catch (OptimisticLockException ex) {
            // Direct JPA optimistic lock — preserve cause chain for diagnostics
            logger.warn("Concurrent modification detected for card ending in {}",
                    maskCardNumber(cardNum));
            throw new ConcurrentModificationException(
                    "Card record was modified by another user", ex);
        } catch (RuntimeException ex) {
            // Spring Data wraps JPA exceptions — check cause chain for optimistic lock
            if (isOptimisticLockFailure(ex)) {
                logger.warn("Concurrent modification detected for card ending in {}",
                        maskCardNumber(cardNum));
                throw new ConcurrentModificationException("Card", cardNum);
            }
            throw ex;
        }
    }

    /**
     * Verifies that the account associated with the card exists in the database.
     * Maps COBOL paragraph 9100-READ-ACCT which reads ACCTDAT to confirm the
     * account referenced by the card is valid.
     *
     * @param acctId the account ID to verify
     * @throws RecordNotFoundException if the account does not exist
     */
    private void verifyAccountExists(String acctId) {
        if (acctId == null || acctId.isBlank()) {
            return;
        }
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.warn("Account ending in {} not found for card update",
                            maskAccountId(acctId));
                    return new RecordNotFoundException("Account", acctId);
                });
        logger.debug("Verified account ending in {} exists for card update",
                maskAccountId(account.getAcctId()));
    }

    /**
     * Performs field-by-field validation of the card update request, collecting ALL errors
     * before throwing. This matches COBOL paragraph 1200-EDIT-MAP-INPUTS which validates
     * every field and sets individual error flags rather than short-circuiting on the first error.
     *
     * @param dto the card update request to validate
     * @throws ValidationException if one or more fields fail validation
     */
    private void validateFields(CardDto dto) {
        List<FieldError> errors = new ArrayList<>();

        // Account ID validation (COBOL 1210-EDIT-ACCOUNT, lines ~721–755)
        validateAccountId(dto.getCardAcctId(), errors);

        // Card Number validation (COBOL 1220-EDIT-CARD, lines ~762–799)
        validateCardNumber(dto.getCardNum(), errors);

        // Embossed Name validation (COBOL 1230-EDIT-NAME, lines ~806–839)
        validateEmbossedName(dto.getCardEmbossedName(), errors);

        // Active Status validation (COBOL 1240-EDIT-CARDSTATUS, lines ~845–872)
        validateActiveStatus(dto.getCardActiveStatus(), errors);

        // Expiry Date validation (COBOL 1250/1260-EDIT-EXPIRY-MON/YEAR, lines ~877–943)
        validateExpiryDate(dto.getCardExpDate(), errors);

        if (!errors.isEmpty()) {
            logger.warn("Validation failed for card update: {} error(s)", errors.size());
            throw new ValidationException(errors);
        }
    }

    /**
     * Validates the account ID field — maps COBOL 1210-EDIT-ACCOUNT.
     * Account ID must be non-blank, numeric, and at most 11 digits.
     */
    private void validateAccountId(String acctId, List<FieldError> errors) {
        if (acctId == null || acctId.isBlank()) {
            errors.add(new FieldError("acctId", acctId, "Account number cannot be blank"));
            return;
        }
        if (!acctId.matches(NUMERIC_PATTERN)) {
            errors.add(new FieldError("acctId", maskAccountId(acctId),
                    "Account number must be numeric"));
            return;
        }
        if (acctId.length() > ACCT_ID_MAX_LENGTH) {
            errors.add(new FieldError("acctId", maskAccountId(acctId),
                    "Account number must not exceed " + ACCT_ID_MAX_LENGTH + " digits"));
        }
    }

    /**
     * Validates the card number field — maps COBOL 1220-EDIT-CARD.
     * Card number must be non-blank, numeric, and at most 16 digits.
     */
    private void validateCardNumber(String cardNum, List<FieldError> errors) {
        if (cardNum == null || cardNum.isBlank()) {
            errors.add(new FieldError("cardNum", cardNum, "Card number cannot be blank"));
            return;
        }
        if (!cardNum.matches(NUMERIC_PATTERN)) {
            errors.add(new FieldError("cardNum", maskCardNumber(cardNum),
                    "Card number must be numeric"));
            return;
        }
        if (cardNum.length() > CARD_NUM_MAX_LENGTH) {
            errors.add(new FieldError("cardNum", maskCardNumber(cardNum),
                    "Card number must not exceed " + CARD_NUM_MAX_LENGTH + " digits"));
        }
    }

    /**
     * Validates the embossed name field — maps COBOL 1230-EDIT-NAME.
     * Name must contain only alphabets [A-Za-z] and spaces. The COBOL uses
     * INSPECT CONVERTING to replace all alpha chars with spaces, then checks
     * if the trimmed result is empty (meaning all chars were alpha/space).
     */
    private void validateEmbossedName(String name, List<FieldError> errors) {
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("embossedName", name,
                    "Name must contain only letters and spaces"));
            return;
        }
        if (!name.matches(ALPHA_SPACE_PATTERN)) {
            errors.add(new FieldError("embossedName", name,
                    "Name must contain only letters and spaces"));
        }
    }

    /**
     * Validates the active status field — maps COBOL 1240-EDIT-CARDSTATUS.
     * Status must be exactly "Y" or "N" (case-insensitive comparison per COBOL).
     */
    private void validateActiveStatus(String status, List<FieldError> errors) {
        if (status == null || status.isBlank()) {
            errors.add(new FieldError("activeStatus", status,
                    "Card status must be Y or N"));
            return;
        }
        String upperStatus = status.toUpperCase();
        if (!"Y".equals(upperStatus) && !"N".equals(upperStatus)) {
            errors.add(new FieldError("activeStatus", status,
                    "Card status must be Y or N"));
        }
    }

    /**
     * Validates the expiration date field — maps COBOL 1250-EDIT-EXPIRY-MON and
     * 1260-EDIT-EXPIRY-YEAR. Month must be 1–12, year must be 1950–2099.
     * Day is always defaulted to 1 per COBOL convention (not user-editable).
     */
    private void validateExpiryDate(LocalDate expDate, List<FieldError> errors) {
        if (expDate == null) {
            errors.add(new FieldError("expMonth", null,
                    "Expiration month must be between 1 and 12"));
            errors.add(new FieldError("expYear", null,
                    "Year must be between 1950 and 2099"));
            return;
        }
        int month = expDate.getMonthValue();
        int year = expDate.getYear();
        if (month < MIN_EXPIRY_MONTH || month > MAX_EXPIRY_MONTH) {
            errors.add(new FieldError("expMonth", String.valueOf(month),
                    "Expiration month must be between 1 and 12"));
        }
        if (year < MIN_EXPIRY_YEAR || year > MAX_EXPIRY_YEAR) {
            errors.add(new FieldError("expYear", String.valueOf(year),
                    "Year must be between 1950 and 2099"));
        }
    }

    /**
     * Detects whether any updatable fields have changed between the current database
     * record and the update request. Uses UPPER-CASE comparison for string fields,
     * matching COBOL paragraph 1200-CHECK-FOR-CHANGES: {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)
     * EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}.
     *
     * @param currentCard   the current card entity from the database
     * @param updateRequest the incoming update DTO
     * @return {@code true} if any field differs, {@code false} if all fields are identical
     */
    private boolean hasChanges(Card currentCard, CardDto updateRequest) {
        boolean cvvMatch = safeUpperEquals(
                currentCard.getCardCvvCd(), updateRequest.getCardCvvCd());
        boolean nameMatch = safeUpperEquals(
                currentCard.getCardEmbossedName(), updateRequest.getCardEmbossedName());
        boolean statusMatch = safeUpperEquals(
                currentCard.getCardActiveStatus(), updateRequest.getCardActiveStatus());
        boolean dateMatch = safeLocalDateEquals(
                currentCard.getCardExpDate(), updateRequest.getCardExpDate());

        return !(cvvMatch && nameMatch && statusMatch && dateMatch);
    }

    /**
     * Null-safe upper-case string comparison — mirrors COBOL FUNCTION UPPER-CASE behavior.
     */
    private boolean safeUpperEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toUpperCase().equals(b.toUpperCase());
    }

    /**
     * Null-safe LocalDate comparison for expiration date matching.
     */
    private boolean safeLocalDateEquals(LocalDate a, LocalDate b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    /**
     * Checks the exception cause chain for optimistic locking failures.
     * Spring Data JPA wraps JPA {@link OptimisticLockException} in its own exception hierarchy
     * (ObjectOptimisticLockingFailureException). This method traverses the cause chain to
     * detect either the JPA exception or Spring's wrapper.
     */
    private boolean isOptimisticLockFailure(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
            String className = cause.getClass().getSimpleName();
            if (className.contains("OptimisticLocking")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Converts a Card entity to a CardDto for API responses.
     * Maps all six fields: cardNum, cardAcctId, cardEmbossedName, cardExpDate,
     * cardActiveStatus, cardCvvCd.
     *
     * @param card the Card entity to convert
     * @return populated CardDto
     */
    private CardDto toCardDto(Card card) {
        CardDto dto = new CardDto();
        dto.setCardNum(card.getCardNum());
        dto.setCardAcctId(card.getCardAcctId());
        dto.setCardEmbossedName(card.getCardEmbossedName());
        dto.setCardExpDate(card.getCardExpDate());
        dto.setCardActiveStatus(card.getCardActiveStatus());
        dto.setCardCvvCd(card.getCardCvvCd());
        return dto;
    }

    /**
     * Masks a card number for safe logging — shows only last 4 digits.
     * Prevents PII exposure in log files per security requirements.
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() <= 4) {
            return "****";
        }
        return "****" + cardNum.substring(cardNum.length() - 4);
    }

    /**
     * Masks an account ID for safe logging — shows only last 4 digits.
     * Prevents PII exposure in log files per security requirements.
     */
    private String maskAccountId(String acctId) {
        if (acctId == null || acctId.length() <= 4) {
            return "****";
        }
        return "****" + acctId.substring(acctId.length() - 4);
    }
}
