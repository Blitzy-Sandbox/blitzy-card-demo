/*
 * CardDetailService.java
 *
 * Spring @Service for credit card detail lookup — migrated from COCRDSLC.cbl (887 lines).
 *
 * This service provides single-card keyed reads by card number (primary key)
 * and account-based card lookups (alternate index), mapping the COBOL EXEC CICS
 * READ operations on the CARDDAT VSAM KSDS dataset and the CARDAIX alternate
 * index path to JPA repository calls.
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * ┌───────────────────────────────┬──────────────────────────────────┬──────────────────────────────────────┐
 * │ COBOL Paragraph               │ Java Method                      │ Description                          │
 * ├───────────────────────────────┼──────────────────────────────────┼──────────────────────────────────────┤
 * │ 9100-GETCARD-BYACCTCARD       │ getCardDetail(String)            │ READ CARDDAT by primary key (16-char │
 * │ (COCRDSLC.cbl lines 736-777)  │                                  │ card number via RIDFLD)              │
 * ├───────────────────────────────┼──────────────────────────────────┼──────────────────────────────────────┤
 * │ 9150-GETCARD-BYACCT           │ getCardsByAccountId(String)      │ READ CARDAIX by alternate index      │
 * │ (COCRDSLC.cbl lines 779-812)  │                                  │ (11-digit account ID)                │
 * └───────────────────────────────┴──────────────────────────────────┴──────────────────────────────────────┘
 *
 * Error Handling:
 * - DFHRESP(NOTFND) / FILE STATUS 23 → RecordNotFoundException
 * - DFHRESP(OTHER)                    → logged as error (maps "Error reading Card Data File")
 *
 * Observability (AAP §0.7.1):
 * - SLF4J structured logging with correlation ID propagation via Logback MDC
 * - Card numbers are NEVER logged in full — only last 4 digits shown
 * - Method entry/exit logging at INFO level; not-found at WARN level
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.card;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for retrieving credit card detail records.
 *
 * <p>Migrated from COBOL program {@code COCRDSLC.cbl} (887 lines), which provides the
 * "Credit Card Detail" screen (BMS mapset COCRDSL, map CCRDSLA) in the CardDemo
 * application. In the COBOL system, this program handles CICS pseudo-conversational
 * terminal I/O with READ operations against CARDDAT (primary key) and CARDAIX
 * (alternate index by account ID).</p>
 *
 * <p>In the Java migration, the CICS terminal I/O and pseudo-conversational state
 * machine are replaced by stateless service methods invoked from REST controllers.
 * The two data access patterns are preserved:</p>
 * <ol>
 *   <li><strong>Primary key lookup</strong> ({@link #getCardDetail(String)}) — maps
 *       paragraph {@code 9100-GETCARD-BYACCTCARD} which performs
 *       {@code EXEC CICS READ FILE(CARDDAT) RIDFLD(WS-CARD-RID-CARDNUM)}</li>
 *   <li><strong>Alternate index lookup</strong> ({@link #getCardsByAccountId(String)}) —
 *       maps paragraph {@code 9150-GETCARD-BYACCT} which performs
 *       {@code EXEC CICS READ FILE(CARDAIX) RIDFLD(WS-CARD-RID-ACCT-ID)}</li>
 * </ol>
 *
 * <p>All operations are read-only; the class-level {@code @Transactional(readOnly = true)}
 * annotation ensures proper transaction boundaries and allows Hibernate to apply
 * read-only optimizations (no dirty checking, flush-mode NEVER).</p>
 *
 * <h3>Security</h3>
 * <p>Card numbers are treated as sensitive data (PCI DSS). All log statements mask the
 * card number to show only the last 4 digits. Full card numbers are never written to
 * log output.</p>
 *
 * @see com.cardemo.repository.CardRepository
 * @see com.cardemo.model.entity.Card
 * @see com.cardemo.model.dto.CardDto
 * @see com.cardemo.exception.RecordNotFoundException
 */
@Service
@Transactional(readOnly = true)
public class CardDetailService {

    /**
     * SLF4J logger for structured logging with correlation ID propagation.
     * Observability per AAP §0.7.1 — all log entries participate in the
     * Logback MDC context which includes traceId, spanId, and correlationId.
     */
    private static final Logger logger = LoggerFactory.getLogger(CardDetailService.class);

    /**
     * Maximum length for a card number (CARD-NUM PIC X(16) from CVACT02Y.cpy).
     * Used for input validation matching COBOL WORKING-STORAGE field width.
     */
    private static final int CARD_NUM_MAX_LENGTH = 16;

    /**
     * Maximum length for an account ID (CARD-ACCT-ID PIC 9(11) from CVACT02Y.cpy).
     * Used for input validation matching COBOL WORKING-STORAGE field width.
     */
    private static final int ACCT_ID_MAX_LENGTH = 11;

    /**
     * Spring Data JPA repository for the CARDDAT VSAM KSDS dataset.
     * Injected via single-constructor auto-injection (no {@code @Autowired} needed).
     *
     * <p>Provides:</p>
     * <ul>
     *   <li>{@code findById(String)} — primary key lookup by 16-char card number</li>
     *   <li>{@code findByCardAcctId(String)} — alternate index lookup by 11-digit account ID</li>
     * </ul>
     */
    private final CardRepository cardRepository;

    /**
     * Constructs a new {@code CardDetailService} with the required repository dependency.
     *
     * <p>Spring's single-constructor auto-injection eliminates the need for
     * {@code @Autowired}. The {@code CardRepository} is resolved from the
     * Spring application context at startup.</p>
     *
     * @param cardRepository the JPA repository for card data access; must not be {@code null}
     */
    public CardDetailService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Retrieves a single card record by its card number (primary key).
     *
     * <p><strong>COBOL Traceability:</strong> Maps paragraph {@code 9100-GETCARD-BYACCTCARD}
     * (COCRDSLC.cbl lines 736-777) which performs:</p>
     * <pre>
     * EXEC CICS READ
     *      FILE      (LIT-CARDFILENAME)         — 'CARDDAT '
     *      RIDFLD    (WS-CARD-RID-CARDNUM)       — 16-byte card number
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-CARDNUM)
     *      INTO      (CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>Error handling maps the COBOL EVALUATE block:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → successful return of {@link CardDto}</li>
     *   <li>{@code DFHRESP(NOTFND)} → {@link RecordNotFoundException} thrown
     *       (maps COBOL message "Did not find cards for this search condition")</li>
     * </ul>
     *
     * <p><strong>Input Validation:</strong> Matches COBOL paragraph 2220-EDIT-CARD
     * which validates: not blank, numeric, max 16 characters. Non-numeric and
     * blank values are rejected with descriptive messages matching COBOL 88-level
     * condition names.</p>
     *
     * @param cardNum the 16-character card number to look up; must not be {@code null}
     *                or blank, must be numeric, and must not exceed 16 characters
     * @return a {@link CardDto} containing the card detail fields
     * @throws IllegalArgumentException  if {@code cardNum} is null, blank, non-numeric,
     *                                    or exceeds 16 characters
     * @throws RecordNotFoundException    if no card record exists for the given card number
     *                                    (maps FILE STATUS 23 / DFHRESP(NOTFND))
     */
    public CardDto getCardDetail(String cardNum) {
        logger.info("Retrieving card detail for card number ending in {}",
                maskCardNumber(cardNum));

        validateCardNumber(cardNum);

        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    // Maps COBOL: DFHRESP(NOTFND) → SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
                    // Message: "Did not find cards for this search condition"
                    logger.warn("Card not found for card number ending in {}",
                            maskCardNumber(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        CardDto result = toCardDto(card);
        logger.info("Successfully retrieved card detail for card number ending in {}",
                maskCardNumber(cardNum));
        return result;
    }

    /**
     * Retrieves all card records associated with the specified account ID.
     *
     * <p><strong>COBOL Traceability:</strong> Maps paragraph {@code 9150-GETCARD-BYACCT}
     * (COCRDSLC.cbl lines 779-812) which performs:</p>
     * <pre>
     * EXEC CICS READ
     *      FILE      (LIT-CARDFILENAME-ACCT-PATH)  — 'CARDAIX '
     *      RIDFLD    (WS-CARD-RID-ACCT-ID)          — 11-byte account ID
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID)
     *      INTO      (CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>The COBOL alternate index (CARDAIX, KEYS 11 16, NONUNIQUEKEY) returns the
     * first matching card for the account. In the Java migration, we return <em>all</em>
     * cards for the account via the JPA derived query {@code findByCardAcctId()},
     * which maps the NONUNIQUEKEY semantics more completely.</p>
     *
     * <p>Error handling maps the COBOL EVALUATE block:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → successful return of card list</li>
     *   <li>{@code DFHRESP(NOTFND)} → {@link RecordNotFoundException} thrown
     *       (maps COBOL message "Did not find this account in cards database")</li>
     * </ul>
     *
     * @param acctId the 11-character account identifier; must not be {@code null}
     *               or blank, must be numeric, and must not exceed 11 characters
     * @return a non-empty {@link List} of {@link CardDto} instances for the account
     * @throws IllegalArgumentException  if {@code acctId} is null, blank, non-numeric,
     *                                    or exceeds 11 characters
     * @throws RecordNotFoundException    if no card records exist for the given account ID
     *                                    (maps FILE STATUS 23 / DFHRESP(NOTFND))
     */
    public List<CardDto> getCardsByAccountId(String acctId) {
        logger.info("Retrieving cards for account ID {}", acctId);

        validateAccountId(acctId);

        List<Card> cards = cardRepository.findByCardAcctId(acctId);

        if (cards.isEmpty()) {
            // Maps COBOL: DFHRESP(NOTFND) → SET DID-NOT-FIND-ACCT-IN-CARDXREF TO TRUE
            // Message: "Did not find this account in cards database"
            logger.warn("No cards found for account ID {}", acctId);
            throw new RecordNotFoundException("Cards for account", acctId);
        }

        List<CardDto> result = cards.stream()
                .map(this::toCardDto)
                .toList();

        logger.info("Retrieved {} card(s) for account ID {}", result.size(), acctId);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private Helpers
    // -----------------------------------------------------------------------

    /**
     * Converts a {@link Card} JPA entity to a {@link CardDto} API response object.
     *
     * <p>Maps all 6 business fields from the COBOL CARD-RECORD layout (CVACT02Y.cpy):</p>
     * <ul>
     *   <li>CARD-NUM PIC X(16) → {@code cardNum}</li>
     *   <li>CARD-ACCT-ID PIC 9(11) → {@code cardAcctId}</li>
     *   <li>CARD-CVV-CD PIC 9(03) → {@code cardCvvCd}</li>
     *   <li>CARD-EMBOSSED-NAME PIC X(50) → {@code cardEmbossedName}</li>
     *   <li>CARD-EXPIRAION-DATE PIC X(10) → {@code cardExpDate} (LocalDate)</li>
     *   <li>CARD-ACTIVE-STATUS PIC X(01) → {@code cardActiveStatus}</li>
     * </ul>
     *
     * <p>This helper corresponds to the COBOL pattern in paragraph
     * {@code 1200-SETUP-SCREEN-VARS} (COCRDSLC.cbl lines 457-500) where
     * CARD-RECORD fields are moved to BMS screen output fields.</p>
     *
     * @param card the Card entity to convert; must not be {@code null}
     * @return a fully populated {@link CardDto} instance
     */
    private CardDto toCardDto(Card card) {
        CardDto dto = new CardDto();
        dto.setCardNum(card.getCardNum());
        dto.setCardAcctId(card.getCardAcctId());
        dto.setCardCvvCd(card.getCardCvvCd());
        dto.setCardEmbossedName(card.getCardEmbossedName());
        dto.setCardExpDate(card.getCardExpDate());
        dto.setCardActiveStatus(card.getCardActiveStatus());
        return dto;
    }

    /**
     * Validates the card number input parameter.
     *
     * <p>Maps COBOL paragraph {@code 2220-EDIT-CARD} (COCRDSLC.cbl lines 675-724)
     * which validates:</p>
     * <ul>
     *   <li>Card number is not blank (88 FLG-CARDFILTER-BLANK)</li>
     *   <li>Card number is numeric (IF CC-CARD-NUM IS NOT NUMERIC)</li>
     *   <li>Card number is max 16 characters (PIC X(16) width constraint)</li>
     * </ul>
     *
     * <p>COBOL error messages mapped:</p>
     * <ul>
     *   <li>"Card number not provided" → blank input</li>
     *   <li>"Card number if supplied must be a 16 digit number" → non-numeric or wrong length</li>
     * </ul>
     *
     * @param cardNum the card number to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateCardNumber(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            throw new IllegalArgumentException("Card number not provided");
        }
        if (cardNum.length() > CARD_NUM_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Card number must not exceed " + CARD_NUM_MAX_LENGTH + " characters");
        }
        for (int i = 0; i < cardNum.length(); i++) {
            if (!Character.isDigit(cardNum.charAt(i))) {
                throw new IllegalArgumentException(
                        "Card number if supplied must be a 16 digit number");
            }
        }
    }

    /**
     * Validates the account ID input parameter.
     *
     * <p>Maps COBOL paragraph {@code 2210-EDIT-ACCT} (COCRDSLC.cbl lines 628-674)
     * which validates:</p>
     * <ul>
     *   <li>Account ID is not blank (88 FLG-ACCTFILTER-BLANK)</li>
     *   <li>Account ID is numeric (IF CC-ACCT-ID IS NOT NUMERIC)</li>
     *   <li>Account ID is non-zero (IF CC-ACCT-ID-N = ZEROES)</li>
     *   <li>Account ID is max 11 characters (PIC 9(11) width constraint)</li>
     * </ul>
     *
     * <p>COBOL error messages mapped:</p>
     * <ul>
     *   <li>"Account number not provided" → blank input</li>
     *   <li>"Account number must be a non zero 11 digit number" → non-numeric or zero</li>
     * </ul>
     *
     * @param acctId the account ID to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateAccountId(String acctId) {
        if (acctId == null || acctId.isBlank()) {
            throw new IllegalArgumentException("Account number not provided");
        }
        if (acctId.length() > ACCT_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Account ID must not exceed " + ACCT_ID_MAX_LENGTH + " characters");
        }
        boolean allZeros = true;
        for (int i = 0; i < acctId.length(); i++) {
            char ch = acctId.charAt(i);
            if (!Character.isDigit(ch)) {
                throw new IllegalArgumentException(
                        "Account number must be a non zero 11 digit number");
            }
            if (ch != '0') {
                allZeros = false;
            }
        }
        if (allZeros) {
            throw new IllegalArgumentException(
                    "Account number must be a non zero 11 digit number");
        }
    }

    /**
     * Masks a card number for safe logging, showing only the last 4 digits.
     *
     * <p>PCI DSS compliance requires that card numbers never appear in full
     * in log files. This method returns a masked representation such as
     * {@code "****1234"} for a card number ending in "1234".</p>
     *
     * @param cardNum the card number to mask; may be {@code null}
     * @return a masked representation showing only the last 4 digits,
     *         or {@code "****"} if the input is null or too short
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() <= 4) {
            return "****";
        }
        return "****" + cardNum.substring(cardNum.length() - 4);
    }
}
