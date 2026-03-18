package com.cardemo.service.card;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service providing paginated card browsing — migrated from COCRDLIC.cbl (1,459 lines).
 *
 * <p>Preserves the COBOL card list browse semantics including:
 * <ul>
 *   <li>Exactly 7 rows per page (WS-MAX-SCREEN-LINES = 7)</li>
 *   <li>Card number ascending sort (STARTBR GTEQ + READNEXT sequential order)</li>
 *   <li>Account ID and card number exact-match filtering (9500-FILTER-RECORDS AND logic)</li>
 *   <li>CXACAIX alternate index resolution for account-scoped card lookup</li>
 *   <li>Admin (all cards) and user (account-scoped) view modes</li>
 * </ul>
 *
 * <p>COBOL Paragraph → Java Method traceability:
 * <pre>
 *   9000-READ-FORWARD       → listCards() with ascending sort
 *   9100-READ-BACKWARDS     → listCards() with previous page number
 *   9500-FILTER-RECORDS     → filter parameters in listCards()
 *   1000-PROCESS-INPUTS     → input validation methods
 *   2210-EDIT-ACCOUNT       → validateAccountIdFilter()
 *   2220-EDIT-CARD          → validateCardNumFilter()
 * </pre>
 *
 * @see com.cardemo.repository.CardRepository
 * @see com.cardemo.repository.CardCrossReferenceRepository
 */
@Service
@Transactional(readOnly = true)
public class CardListService {

    private static final Logger logger = LoggerFactory.getLogger(CardListService.class);

    /**
     * Page size matching COBOL WS-MAX-SCREEN-LINES = 7 (COCRDLIC.cbl line ~178).
     * This constant preserves 100% behavioral parity with the COBOL card list browse
     * screen which displays exactly 7 card rows per page.
     */
    private static final int PAGE_SIZE = 7;

    /** Maximum account ID length — maps COBOL PIC 9(11) */
    private static final int MAX_ACCT_ID_LENGTH = 11;

    /** Maximum card number length — maps COBOL PIC X(16) */
    private static final int MAX_CARD_NUM_LENGTH = 16;

    /** Minimum digits to show when masking PII values for logging */
    private static final int MASK_VISIBLE_DIGITS = 4;

    private final CardRepository cardRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Constructs CardListService with required repository dependencies.
     *
     * @param cardRepository JPA repository for CARDDAT VSAM dataset access
     * @param cardCrossReferenceRepository JPA repository for CARDXREF VSAM + CXACAIX access
     */
    public CardListService(CardRepository cardRepository,
                           CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.cardRepository = cardRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Paginated card browse with optional account ID and card number filters.
     *
     * <p>Maps COCRDLIC.cbl paragraphs:
     * <ul>
     *   <li>9000-READ-FORWARD — STARTBR GTEQ + READNEXT loop → Spring Data Page</li>
     *   <li>9500-FILTER-RECORDS — account/card exact-match filtering (AND logic)</li>
     *   <li>2210-EDIT-ACCOUNT — 11-digit numeric account filter validation</li>
     *   <li>2220-EDIT-CARD — 16-digit numeric card number filter validation</li>
     * </ul>
     *
     * <p>Admin view (no filters) returns all cards sorted by card number ascending.
     * Filtered view narrows results by exact account ID and/or exact card number match.
     * Both filters can be combined with AND logic, faithfully matching COBOL 9500-FILTER-RECORDS
     * which excludes a record if either filter fails independently.
     *
     * @param page zero-based page number (must be &gt;= 0)
     * @param acctIdFilter optional 11-digit numeric account ID filter; null or blank means no filter
     * @param cardNumFilter optional 16-digit numeric card number filter; null or blank means no filter
     * @return paginated CardDto results with page size 7
     * @throws IllegalArgumentException if page is negative, acctIdFilter is non-numeric,
     *         or cardNumFilter is non-numeric
     */
    public Page<CardDto> listCards(int page, String acctIdFilter, String cardNumFilter) {
        validatePageNumber(page);
        validateAccountIdFilter(acctIdFilter);
        validateCardNumFilter(cardNumFilter);

        logger.info("Listing cards page {} with acctFilter={} cardFilter={}",
                page,
                maskAccountId(acctIdFilter),
                maskCardNumber(cardNumFilter));

        boolean hasAcctFilter = isNotBlank(acctIdFilter);
        boolean hasCardFilter = isNotBlank(cardNumFilter);

        Page<CardDto> result;

        if (!hasAcctFilter && !hasCardFilter) {
            // Admin view — all cards with native database pagination.
            // Maps COCRDLIC 9000-READ-FORWARD with no filter flags set.
            Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                    Sort.by("cardNum").ascending());
            Page<Card> cardPage = cardRepository.findAll(pageable);
            result = cardPage.map(this::toCardDto);
        } else {
            // Filtered view — fetch candidate records, apply filters, paginate manually.
            // Maps COCRDLIC 9500-FILTER-RECORDS exact-match AND logic:
            //   IF FLG-ACCTFILTER-ISVALID AND CARD-ACCT-ID != CC-ACCT-ID → exclude
            //   IF FLG-CARDFILTER-ISVALID AND CARD-NUM != CC-CARD-NUM   → exclude
            result = executeFilteredQuery(page, acctIdFilter, cardNumFilter,
                    hasAcctFilter, hasCardFilter);
        }

        logger.debug("Retrieved {} cards on page {} (total: {})",
                result.getNumberOfElements(), page, result.getTotalElements());

        return result;
    }

    /**
     * Account-scoped card browse — user view when CDEMO-USER-TYPE = 'U'.
     *
     * <p>Resolves cards for a given account using the CXACAIX alternate index pattern
     * from COCRDLIC.cbl. The cross-reference table maps account IDs to card numbers,
     * then the card repository fetches the actual card records for that account.
     *
     * <p>Returns an empty page when no cards are found for the account, matching the
     * COBOL behavior of displaying an empty screen (WS-NO-RECORDS-FOUND flag).
     * However, if cross-references exist but no corresponding card records are found,
     * this indicates a data integrity issue (orphaned CXACAIX entries) and throws
     * RecordNotFoundException (maps to COBOL FILE STATUS 23 / RESP(NOTFND)).
     *
     * @param acctId 11-digit account ID (required, non-blank)
     * @param page zero-based page number (must be &gt;= 0)
     * @return paginated CardDto results with page size 7
     * @throws IllegalArgumentException if acctId is null or blank, or page is negative
     * @throws RecordNotFoundException if cross-references exist but no cards found (data integrity)
     */
    public Page<CardDto> listCardsByAccount(String acctId, int page) {
        if (acctId == null || acctId.isBlank()) {
            throw new IllegalArgumentException(
                    "Account ID is required for account-scoped card listing");
        }
        validatePageNumber(page);

        logger.info("Listing cards for account {} page {}",
                maskAccountId(acctId), page);

        // Step 1: Resolve cross-references — which cards belong to this account.
        // Maps CXACAIX alternate index browse pattern from COCRDLIC.cbl.
        // The COBOL program uses the CARDAIX alternate index (defined in XREFFILE.jcl)
        // to discover card numbers associated with an account before reading cards.
        List<CardCrossReference> crossRefs =
                cardCrossReferenceRepository.findByXrefAcctId(acctId);

        logger.debug("Found {} cross-references for account {}",
                crossRefs.size(), maskAccountId(acctId));

        // Extract cross-reference card numbers for data integrity verification.
        // Each CardCrossReference links an account ID (getXrefAcctId) to a card
        // number (getXrefCardNum), preserving the CXACAIX alternate index semantics.
        List<String> crossRefCardNumbers = crossRefs.stream()
                .map(CardCrossReference::getXrefCardNum)
                .collect(Collectors.toList());

        if (!crossRefs.isEmpty()) {
            logger.debug("Cross-reference resolved {} card(s) for account {}",
                    crossRefCardNumbers.size(), maskAccountId(acctId));
        }

        // Step 2: Fetch actual card records for this account.
        // Wrap in ArrayList to ensure mutability for downstream sort operation.
        List<Card> cards = new ArrayList<>(
                cardRepository.findByCardAcctId(acctId));

        // Data integrity check: cross-references exist but no card records found.
        // This maps to the COBOL RESP(NOTFND) error path where the card file
        // browse fails after the CXACAIX index indicated records should exist.
        if (!crossRefs.isEmpty() && cards.isEmpty()) {
            logger.warn("Data integrity issue: {} cross-references exist for account {} "
                            + "but no card records found — orphaned CXACAIX entries detected",
                    crossRefs.size(), maskAccountId(acctId));
            throw new RecordNotFoundException("Card", acctId);
        }

        // Step 3: Sort by card number ascending (COBOL STARTBR GTEQ sequential order)
        // and paginate manually with PAGE_SIZE = 7.
        cards.sort((a, b) -> {
            String numA = a.getCardNum() != null ? a.getCardNum() : "";
            String numB = b.getCardNum() != null ? b.getCardNum() : "";
            return numA.compareTo(numB);
        });

        Page<CardDto> result = paginateCards(cards, page);

        logger.debug("Retrieved {} cards on page {} for account {} (total: {})",
                result.getNumberOfElements(), page,
                maskAccountId(acctId), result.getTotalElements());

        return result;
    }

    // =========================================================================
    // Private helper methods
    // =========================================================================

    /**
     * Executes a filtered card query with manual pagination.
     *
     * <p>Fetches candidate card records based on the active filters, applies
     * 9500-FILTER-RECORDS AND logic, sorts by card number ascending, and
     * constructs a manually paginated result matching PAGE_SIZE = 7.
     *
     * @param page zero-based page number
     * @param acctIdFilter account ID filter (may be null/blank)
     * @param cardNumFilter card number filter (may be null/blank)
     * @param hasAcctFilter whether account filter is active
     * @param hasCardFilter whether card number filter is active
     * @return paginated CardDto results
     */
    private Page<CardDto> executeFilteredQuery(int page, String acctIdFilter,
                                               String cardNumFilter,
                                               boolean hasAcctFilter,
                                               boolean hasCardFilter) {
        List<Card> candidates;

        if (hasAcctFilter) {
            // Fetch cards for the specified account.
            // Maps COCRDLIC 9500-FILTER-RECORDS: FLG-ACCTFILTER-ISVALID check
            // where CARD-ACCT-ID must equal CC-ACCT-ID (exact match).
            // Wrap in ArrayList to ensure mutability for downstream sort operation.
            candidates = new ArrayList<>(
                    cardRepository.findByCardAcctId(acctIdFilter.trim()));
        } else {
            // No account filter — load all cards for card number filtering.
            // Maps COCRDLIC sequential READNEXT browse through entire CARD-FILE.
            Pageable allCards = PageRequest.of(0, Integer.MAX_VALUE,
                    Sort.by("cardNum").ascending());
            candidates = new ArrayList<>(
                    cardRepository.findAll(allCards).getContent());
        }

        // Apply card number exact-match filter (AND logic with account filter).
        // Maps COCRDLIC 9500-FILTER-RECORDS: FLG-CARDFILTER-ISVALID check
        // where CARD-NUM must equal CC-CARD-NUM-N (exact match).
        if (hasCardFilter) {
            String trimmedCardFilter = cardNumFilter.trim();
            candidates = candidates.stream()
                    .filter(card -> trimmedCardFilter.equals(card.getCardNum()))
                    .collect(Collectors.toList());
        }

        // Sort by card number ascending (COBOL STARTBR GTEQ + READNEXT order)
        candidates.sort((a, b) -> {
            String numA = a.getCardNum() != null ? a.getCardNum() : "";
            String numB = b.getCardNum() != null ? b.getCardNum() : "";
            return numA.compareTo(numB);
        });

        return paginateCards(candidates, page);
    }

    /**
     * Manually paginates a list of Card entities into a Page of CardDto.
     *
     * <p>Applies the PAGE_SIZE = 7 constraint matching COBOL WS-MAX-SCREEN-LINES,
     * converting each Card entity to a CardDto via {@link #toCardDto(Card)}.
     * Returns an empty page when the start index exceeds the list size, matching
     * COBOL behavior of showing an empty screen when no records remain.
     *
     * @param cards sorted list of Card entities to paginate
     * @param page zero-based page number
     * @return paginated CardDto results with page size 7
     */
    private Page<CardDto> paginateCards(List<Card> cards, int page) {
        int totalElements = cards.size();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, totalElements);

        List<CardDto> pageContent;
        if (start >= totalElements) {
            // Beyond available records — return empty page.
            // Maps COCRDLIC 9000-READ-FORWARD ENDFILE condition when
            // WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0.
            pageContent = List.of();
        } else {
            pageContent = cards.subList(start, end).stream()
                    .map(this::toCardDto)
                    .collect(Collectors.toList());
        }

        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by("cardNum").ascending());
        return new PageImpl<>(pageContent, pageable, totalElements);
    }

    /**
     * Converts a Card entity to CardDto.
     *
     * <p>Maps all 6 fields from the COBOL CARD-RECORD (CVACT02Y.cpy, 150 bytes):
     * <pre>
     *   CARD-NUM             PIC X(16)  → cardNum
     *   CARD-ACCT-ID         PIC 9(11)  → cardAcctId
     *   CARD-EMBOSSED-NAME   PIC X(50)  → cardEmbossedName
     *   CARD-EXPIRAION-DATE  PIC X(10)  → cardExpDate (LocalDate)
     *   CARD-ACTIVE-STATUS   PIC X(01)  → cardActiveStatus
     *   CARD-CVV-CD          PIC 9(03)  → cardCvvCd
     * </pre>
     *
     * @param card the Card entity to convert
     * @return populated CardDto with all 6 fields mapped
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

    // =========================================================================
    // Input validation — maps COCRDLIC.cbl 2200-EDIT-INPUTS
    // =========================================================================

    /**
     * Validates page number is non-negative.
     *
     * @param page the page number to validate
     * @throws IllegalArgumentException if page is negative
     */
    private void validatePageNumber(int page) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "Page number must be >= 0, got: " + page);
        }
    }

    /**
     * Validates account ID filter if supplied.
     *
     * <p>Maps COCRDLIC.cbl 2210-EDIT-ACCOUNT (lines 1003-1034):
     * "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"
     * Blank/null values are accepted (no filter applied). Non-numeric or
     * over-length values are rejected with IllegalArgumentException.
     *
     * @param acctIdFilter account ID filter to validate (may be null/blank)
     * @throws IllegalArgumentException if non-blank filter is non-numeric or exceeds 11 digits
     */
    private void validateAccountIdFilter(String acctIdFilter) {
        if (acctIdFilter == null || acctIdFilter.isBlank()) {
            return;
        }
        String trimmed = acctIdFilter.trim();
        if (trimmed.length() > MAX_ACCT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Account filter, if supplied, must be at most "
                            + MAX_ACCT_ID_LENGTH + " digits");
        }
        if (!trimmed.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "Account filter, if supplied, must be numeric");
        }
    }

    /**
     * Validates card number filter if supplied.
     *
     * <p>Maps COCRDLIC.cbl 2220-EDIT-CARD (lines 1036-1071):
     * "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"
     * Blank/null values are accepted (no filter applied). Non-numeric or
     * over-length values are rejected with IllegalArgumentException.
     *
     * @param cardNumFilter card number filter to validate (may be null/blank)
     * @throws IllegalArgumentException if non-blank filter is non-numeric or exceeds 16 digits
     */
    private void validateCardNumFilter(String cardNumFilter) {
        if (cardNumFilter == null || cardNumFilter.isBlank()) {
            return;
        }
        String trimmed = cardNumFilter.trim();
        if (trimmed.length() > MAX_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "Card ID filter, if supplied, must be at most "
                            + MAX_CARD_NUM_LENGTH + " digits");
        }
        if (!trimmed.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "Card ID filter, if supplied, must be numeric");
        }
    }

    // =========================================================================
    // PII masking — observability (AAP §0.7.1) requires NO full card/acct IDs in logs
    // =========================================================================

    /**
     * Masks an account ID for safe logging, showing only the last 4 digits.
     * Returns "none" for null or blank values.
     *
     * @param acctId the account ID to mask
     * @return masked account ID string (e.g., "***7890")
     */
    private String maskAccountId(String acctId) {
        if (acctId == null || acctId.isBlank()) {
            return "none";
        }
        String trimmed = acctId.trim();
        if (trimmed.length() <= MASK_VISIBLE_DIGITS) {
            return "***" + trimmed;
        }
        return "***" + trimmed.substring(trimmed.length() - MASK_VISIBLE_DIGITS);
    }

    /**
     * Masks a card number for safe logging, showing only the last 4 digits.
     * Returns "none" for null or blank values.
     *
     * @param cardNum the card number to mask
     * @return masked card number string (e.g., "***5678")
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            return "none";
        }
        String trimmed = cardNum.trim();
        if (trimmed.length() <= MASK_VISIBLE_DIGITS) {
            return "***" + trimmed;
        }
        return "***" + trimmed.substring(trimmed.length() - MASK_VISIBLE_DIGITS);
    }

    /**
     * Checks if a string is non-null and non-blank.
     *
     * @param value the string to check
     * @return true if the string has meaningful content
     */
    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
