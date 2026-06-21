package com.carddemo.service.card;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardListResponse;
import com.carddemo.model.dto.CardListResponse.CardListItem;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.shared.PaginationSupport;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Card list (browse) service &mdash; the Java translation of the COBOL online program
 * {@code COCRDLIC} (paginated card browse), source commit {@code 27d6c6f}.
 *
 * <p>Reproduces the program's optional account/card filters ({@code 2210-EDIT-ACCOUNT},
 * {@code 2220-EDIT-CARD}), its {@code WS-MAX-SCREEN-LINES = 7} page size, the
 * {@code 9500-FILTER-RECORDS} AND-narrowing, and its informational/empty-result
 * messages. The CICS STARTBR/READNEXT browse is expressed with Spring Data
 * {@link Pageable} paging sorted by card number ascending.</p>
 */
@Service
public class CardListService {

    private static final Logger LOG = LoggerFactory.getLogger(CardListService.class);

    private static final int ROWS_PER_PAGE = 7;
    private static final String CARD_NUMBER_PROPERTY = "cardNum";
    private static final String WILDCARD = "*";

    private static final String MSG_ACCT_FILTER = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String MSG_CARD_FILTER = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String MSG_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";
    private static final String MSG_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    private final CardRepository cardRepository;

    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Browses the card file with optional filters. Both filters are optional; when both
     * are blank every card is listed (mirroring {@code COCRDLIC}). Each page holds at most
     * {@value #ROWS_PER_PAGE} rows ordered by card number ascending.
     *
     * @param accountIdFilter  optional 11-digit account filter (blank lists all)
     * @param cardNumberFilter optional 16-digit card-number filter (blank lists all)
     * @param pageNumber       zero-based page index
     * @return the populated {@link CardListResponse}
     * @throws ValidationException when a supplied filter is not the required numeric format
     */
    public CardListResponse getCardList(String accountIdFilter, String cardNumberFilter, int pageNumber) {
        String acct = normalize(accountIdFilter);
        String card = normalize(cardNumberFilter);

        boolean acctBlank = acct.isEmpty() || isAllZeros(acct);
        boolean cardBlank = card.isEmpty() || isAllZeros(card);

        boolean acctFilterValid = false;
        if (!acctBlank) {
            if (!acct.matches("\\d{11}")) {
                throw new ValidationException(MSG_ACCT_FILTER);
            }
            acctFilterValid = true;
        }

        boolean cardFilterValid = false;
        if (!cardBlank) {
            if (!card.matches("\\d{16}")) {
                throw new ValidationException(MSG_CARD_FILTER);
            }
            cardFilterValid = true;
        }

        // Clamp the zero-based page so the resulting SQL offset (page * ROWS_PER_PAGE) cannot
        // exceed Integer.MAX_VALUE. clampPageToMaxOffset also floors negatives at 0, so an absurd
        // page number now yields a graceful empty page (HTTP 200) instead of an offset-overflow
        // InvalidDataAccessApiUsageException surfacing as HTTP 500.
        int page = PaginationSupport.clampPageToMaxOffset(pageNumber, ROWS_PER_PAGE);
        Pageable pageable = PageRequest.of(page, ROWS_PER_PAGE,
                Sort.by(CARD_NUMBER_PROPERTY).ascending());

        Page<Card> result = acctFilterValid
                ? cardRepository.findByCardAcctId(Long.parseLong(acct), pageable)
                : cardRepository.findAll(pageable);

        List<CardListItem> items = new ArrayList<>();
        for (Card record : result.getContent()) {
            if (cardFilterValid && !card.equals(record.getCardNum())) {
                continue;
            }
            items.add(new CardListItem(
                    "",
                    formatAccountId(record.getCardAcctId()),
                    record.getCardNum(),
                    record.getCardActiveStatus()));
        }

        boolean hasNext = result.hasNext() && !cardFilterValid;

        String infoMessage = null;
        String errorMessage = null;
        if (items.isEmpty()) {
            errorMessage = (page == 0) ? MSG_NO_RECORDS_FOUND : MSG_NO_MORE_RECORDS;
        } else if (hasNext) {
            infoMessage = MSG_INFORM_REC_ACTIONS;
        } else {
            errorMessage = MSG_NO_MORE_RECORDS;
        }

        CardListResponse response = new CardListResponse(
                String.valueOf(result.getNumber() + 1),
                acctFilterValid ? acct : "",
                cardFilterValid ? card : "",
                items,
                infoMessage,
                errorMessage);

        LOG.info("Card list page {} returned {} row(s)", response.pageNumber(), items.size());
        return response;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return WILDCARD.equals(trimmed) ? "" : trimmed;
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

    private static String formatAccountId(Long accountId) {
        return accountId == null ? "" : String.format("%011d", accountId);
    }
}
