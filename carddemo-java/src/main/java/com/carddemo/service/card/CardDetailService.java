package com.carddemo.service.card;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.CardDetailResponse;
import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Card detail inquiry service &mdash; the Java translation of the COBOL online program
 * {@code COCRDSLC} (card detail / single keyed read), source commit {@code 27d6c6f}.
 *
 * <p>Mirrors the program's input edits ({@code 2200-EDIT-MAP-INPUTS} delegating to
 * {@code 2210-EDIT-ACCOUNT} and {@code 2220-EDIT-CARD}) and its keyed read
 * ({@code 9000-READ-DATA} -&gt; {@code 9100-GETCARD-BYACCTCARD}, which reads the CARD
 * file by card number). The stateless REST contract replaces the BMS screen: validation
 * failures surface as {@link ValidationException} and a missing card as
 * {@link RecordNotFoundException}; a successful lookup returns a populated
 * {@link CardDetailResponse}.</p>
 */
@Service
public class CardDetailService {

    private static final Logger LOG = LoggerFactory.getLogger(CardDetailService.class);

    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_ACCT_FILTER = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";
    private static final String MSG_CARD_FILTER = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String MSG_NOT_FOUND = "Did not find cards for this search condition";

    private static final String WILDCARD = "*";

    private final CardRepository cardRepository;

    public CardDetailService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Retrieves the detail of a single card. Both the account filter and the card filter
     * are validated (mirroring {@code COCRDSLC}); the record itself is fetched by card
     * number, which is the program's read key.
     *
     * @param accountId  the 11-digit account filter (validated, must be a non-zero number)
     * @param cardNumber the 16-digit card number (the read key)
     * @return the populated {@link CardDetailResponse}
     * @throws ValidationException     when an input edit fails (exact COBOL messages preserved)
     * @throws RecordNotFoundException when no card exists for the supplied card number
     */
    public CardDetailResponse getCardDetail(String accountId, String cardNumber) {
        String acct = normalize(accountId);
        String card = normalize(cardNumber);

        boolean acctBlank = acct.isEmpty() || isAllZeros(acct);
        boolean cardBlank = card.isEmpty() || isAllZeros(card);

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

        Card cardRecord = cardRepository.findById(card)
                .orElseThrow(() -> new RecordNotFoundException(MSG_NOT_FOUND));

        CardDetailResponse response = new CardDetailResponse(
                formatAccountId(cardRecord.getCardAcctId()),
                cardRecord.getCardNum(),
                cardRecord.getCardEmbossedName(),
                cardRecord.getCardActiveStatus(),
                expiryMonth(cardRecord.getCardExpiraionDate()),
                expiryYear(cardRecord.getCardExpiraionDate()),
                null,
                null);

        LOG.info("Card detail retrieved for account {}", response.accountId());
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

    private static String expiryYear(String expiryDate) {
        return (expiryDate != null && expiryDate.length() >= 4) ? expiryDate.substring(0, 4) : "";
    }

    private static String expiryMonth(String expiryDate) {
        return (expiryDate != null && expiryDate.length() >= 7) ? expiryDate.substring(5, 7) : "";
    }
}
