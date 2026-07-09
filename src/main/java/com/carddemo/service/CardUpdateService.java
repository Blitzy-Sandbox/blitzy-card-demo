package com.carddemo.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.CardUpdateResponse;
import com.carddemo.dto.CardViewResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Application service implementing the Card Update transaction (online
 * transaction {@code CCUP}), the Java&nbsp;25 / Spring&nbsp;Boot translation of
 * the legacy CICS program {@code COCRDUPC} ({@code app/cbl/COCRDUPC.cbl},
 * 1&nbsp;560&nbsp;LOC, frozen source commit SHA {@code 27d6c6f}).
 *
 * <p>{@code COCRDUPC} implements a classic mainframe <em>read-then-rewrite</em>
 * with explicit optimistic-concurrency detection: the program reads a card,
 * presents it for editing, and on save re-reads the record under an update lock
 * and compares the freshly read fields against the snapshot the user was shown
 * ({@code 9300-CHECK-CHANGE-IN-REC}). If they differ, the 88-level
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} is raised and the rewrite is abandoned
 * with the message <em>"Record changed by some one else. Please review"</em>.
 * This service preserves that behaviour using JPA {@code @Version} optimistic
 * locking on {@link Card}, surfaced as an
 * {@link OptimisticLockConflictException} (HTTP&nbsp;409).</p>
 *
 * <h2>Control-flow parity (AAP G3)</h2>
 * <p>The single public method {@link #updateCard(String, CardUpdateRequest)}
 * preserves the ordered edit sequence of {@code 1200-EDIT-MAP-INPUTS}:</p>
 * <ol>
 *   <li>search-key edit of the card number ({@code 1220-EDIT-CARD}) &mdash;
 *       performed before the record is fetched;</li>
 *   <li>keyed read of the card ({@code 9000-READ-DATA};
 *       {@code DFHRESP(NOTFND)} &rarr; {@link ResourceNotFoundException});</li>
 *   <li>optimistic-lock precheck against the version the caller last observed
 *       ({@code 9300-CHECK-CHANGE-IN-REC});</li>
 *   <li>change-field edits in copybook order &mdash; active status
 *       ({@code 1240-EDIT-CARDSTATUS}) then expiry
 *       ({@code 1250-EDIT-EXPIRY-MON} / {@code 1260-EDIT-EXPIRY-YEAR});</li>
 *   <li>rewrite of the mutable fields ({@code 9200-WRITE-PROCESSING}
 *       {@code EXEC CICS REWRITE}), with a persistence-layer optimistic-lock
 *       failure mapped to the same 409 conflict.</li>
 * </ol>
 *
 * <h2>Editable-field scope</h2>
 * <p>The legacy {@code CARD-UPDATE-RECORD} carries card number, account id, CVV,
 * embossed name, expiration date and active status. The REST contract
 * ({@link CardUpdateRequest}) deliberately narrows the mutable set to
 * <strong>embossed name, active status and expiration date</strong>: the card
 * number and account id are identity/ownership fields that are never rewritten,
 * and the card verification value ({@code CARD-CVV-CD}) is excluded from the
 * contract for security &mdash; it is neither accepted on input nor emitted on
 * output.</p>
 *
 * <h2>Security</h2>
 * <p>The response projection ({@link CardViewResponse}) masks the Primary
 * Account Number to its last four characters and carries no CVV, so neither
 * secret can leak through this service. No card number or CVV is ever written to
 * a log line; structured logs are correlated through the MDC
 * {@code correlationId} established by the request filter.</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor
 * injection. Cards carry no monetary values, so no {@code BigDecimal} (and, per
 * policy, no floating-point) arithmetic is involved.</p>
 *
 * @see CardRepository
 * @see Card
 * @see CardUpdateRequest
 * @see CardUpdateResponse
 * @see OptimisticLockConflictException
 */
@Service
public class CardUpdateService {

    private static final Logger log = LoggerFactory.getLogger(CardUpdateService.class);

    /**
     * Success confirmation returned after a committed rewrite.
     *
     * <p>Verbatim transcription of the {@code CONFIRM-UPDATE-SUCCESS} 88-level
     * literal in {@code COCRDUPC} (line&nbsp;168, SHA&nbsp;{@code 27d6c6f}):
     * <em>"Changes committed to database"</em>. Using the exact legacy text
     * preserves the interface contract of the migrated transaction rather than
     * the generic fallback {@code "Card updated successfully"}.</p>
     */
    static final String MSG_SUCCESS = "Changes committed to database";

    /**
     * Not-found message for a card that cannot be located by its number.
     *
     * <p>Verbatim transcription of the {@code DID-NOT-FIND-ACCT-IN-CARDXREF}
     * 88-level literal in {@code COCRDUPC} (line&nbsp;202): <em>"Did not find
     * this account in cards database"</em>.</p>
     */
    static final String MSG_CARD_NOT_FOUND = "Did not find this account in cards database";

    /**
     * Invalid card-number message.
     *
     * <p>Verbatim transcription of the {@code SEARCHED-CARD-NOT-NUMERIC}
     * 88-level literal in {@code COCRDUPC} (line&nbsp;194): <em>"Card number if
     * supplied must be a 16 digit number"</em>.</p>
     */
    static final String MSG_CARD_NUMBER = "Card number if supplied must be a 16 digit number";

    /**
     * Invalid active-status message.
     *
     * <p>Verbatim transcription of the {@code CARD-STATUS-MUST-BE-YES-NO}
     * 88-level literal in {@code COCRDUPC} (line&nbsp;196): <em>"Card Active
     * Status must be Y or N"</em>.</p>
     */
    static final String MSG_ACTIVE_STATUS = "Card Active Status must be Y or N";

    /**
     * Invalid expiry-year message.
     *
     * <p>Verbatim transcription of the {@code CARD-EXPIRY-YEAR-NOT-VALID}
     * 88-level literal in {@code COCRDUPC} (line&nbsp;200): <em>"Invalid card
     * expiry year"</em>.</p>
     */
    static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    /**
     * Invalid expiry-month message.
     *
     * <p>Verbatim transcription of the {@code CARD-EXPIRY-MONTH-NOT-VALID}
     * 88-level literal in {@code COCRDUPC} (line&nbsp;198): <em>"Card expiry
     * month must be between 1 and 12"</em>. Retained for traceability; the
     * month is structurally constrained to 1&ndash;12 by {@link LocalDate}, so
     * this text is not raised at runtime.</p>
     */
    static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";

    /**
     * Invalid account-number message.
     *
     * <p>Verbatim transcription of the {@code SEARCHED-ACCT-ZEROES} /
     * {@code SEARCHED-ACCT-NOT-NUMERIC} 88-level literal in {@code COCRDUPC}
     * (lines&nbsp;190/192): <em>"Account number must be a non zero 11 digit
     * number"</em>. Retained for traceability; the account id is an
     * identity/ownership field that this REST update never mutates or
     * re-validates.</p>
     */
    static final String MSG_ACCOUNT = "Account number must be a non zero 11 digit number";

    /**
     * Aggregate summary used as the top-level detail message when one or more
     * change-field edits fail. Individual field failures are carried in the
     * {@link ValidationException#getFieldErrors() field-error map}; the legacy
     * 3270 screen surfaced a single message at a time, whereas the REST boundary
     * reports every failing field together.
     */
    static final String MSG_VALIDATION_SUMMARY = "Card update failed validation.";

    /** Inclusive lower bound for a valid expiry year ({@code COCRDUPC} {@code VALID-YEAR} 1950 THRU 2099). */
    private static final int MIN_EXPIRY_YEAR = 1950;

    /** Inclusive upper bound for a valid expiry year ({@code COCRDUPC} {@code VALID-YEAR} 1950 THRU 2099). */
    private static final int MAX_EXPIRY_YEAR = 2099;

    /** Exact 16-digit card-number pattern ({@code CARD-NUM PIC X(16)}, numeric edit). */
    private static final String CARD_NUMBER_PATTERN = "\\d{16}";

    /**
     * The card repository (migrated VSAM {@code CARDDATA} keyed access). Never
     * {@code null}; supplied by the container through constructor injection.
     */
    private final CardRepository cardRepository;

    /**
     * Creates the service with its collaborating repository.
     *
     * @param cardRepository the Spring Data repository for the {@link Card}
     *                       aggregate; must not be {@code null}
     */
    public CardUpdateService(final CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Updates the mutable attributes of an existing card, preserving the
     * read-then-rewrite optimistic-concurrency semantics of {@code COCRDUPC}
     * (transaction {@code CCUP}).
     *
     * <p>Only the embossed name, active status and expiration date are applied;
     * the card number, account id and CVV are never modified. The method runs in
     * a single transaction that rolls back on any exception, mirroring the
     * legacy {@code EXEC CICS SYNCPOINT ROLLBACK} boundary.</p>
     *
     * @param cardNumber the 16-digit card number identifying the record to
     *                   update ({@code CARD-NUM}); validated before the read
     * @param request    the requested changes and the optimistic-lock version
     *                   the caller last observed; must not be {@code null}
     * @return a {@link CardUpdateResponse} carrying the PAN-masked, CVV-free
     *         card snapshot, the success confirmation ({@link #MSG_SUCCESS}) and
     *         the new optimistic-lock version
     * @throws ValidationException            if the card number is not a 16-digit
     *                                        value, or a change-field edit fails
     *                                        (HTTP&nbsp;400)
     * @throws ResourceNotFoundException      if no card exists for the supplied
     *                                        number (HTTP&nbsp;404)
     * @throws OptimisticLockConflictException if the card was changed by another
     *                                        actor between read and write
     *                                        (HTTP&nbsp;409)
     */
    @Transactional(rollbackFor = Exception.class)
    public CardUpdateResponse updateCard(final String cardNumber, final CardUpdateRequest request) {
        log.info("Processing card update request (transaction CCUP)");

        // Input-contract guard: a null request body is a broken contract, surfaced
        // as a typed HTTP-400 validation failure rather than an unhandled
        // NullPointerException / HTTP 500 on the request.version() dereference below.
        if (request == null) {
            throw new ValidationException(MSG_VALIDATION_SUMMARY);
        }

        // --- 1220-EDIT-CARD: search-key edit performed before the record read.
        // The card number must be exactly 16 digits (CARD-NUM PIC X(16), numeric).
        final String key = (cardNumber == null) ? null : cardNumber.strip();
        if (key == null || !key.matches(CARD_NUMBER_PATTERN)) {
            throw new ValidationException(MSG_CARD_NUMBER);
        }

        // --- 9000-READ-DATA: keyed read; DFHRESP(NOTFND) -> 404 not found.
        final Card card = cardRepository.findById(key)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_CARD_NOT_FOUND));

        // --- 9300-CHECK-CHANGE-IN-REC: optimistic-lock precheck. If the caller
        // echoed a version that no longer matches the persisted row, the record
        // was changed after it was read (DATA-WAS-CHANGED-BEFORE-UPDATE).
        if (request.version() != null && !request.version().equals(card.getVersion())) {
            log.warn("Optimistic-lock precheck failed on card update (expected v{}, current v{})",
                    request.version(), card.getVersion());
            throw new OptimisticLockConflictException();
        }

        // --- 1240/1250/1260 change-field edits, accumulated in copybook order.
        final Map<String, String> fieldErrors = new LinkedHashMap<>();
        validateActiveStatus(request.activeStatus(), fieldErrors);
        validateExpirationDate(request.expirationDate(), fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(MSG_VALIDATION_SUMMARY, fieldErrors);
        }

        // --- 9200-WRITE-PROCESSING: apply only the mutable fields. CVV and the
        // account id are intentionally left untouched (identity/secret fields).
        card.setCardEmbossedName(request.embossedName());
        card.setCardActiveStatus(request.activeStatus());
        card.setCardExpirationDate(request.expirationDate());

        // --- EXEC CICS REWRITE. A concurrent modification detected by the
        // persistence provider (Spring wraps Hibernate's optimistic-lock failure
        // as ObjectOptimisticLockingFailureException, a subtype of
        // OptimisticLockingFailureException) maps to the same 409 conflict.
        try {
            cardRepository.saveAndFlush(card);
        } catch (final OptimisticLockingFailureException ex) {
            log.warn("Optimistic-lock conflict on card rewrite: {}", ex.getClass().getSimpleName());
            throw new OptimisticLockConflictException(ex);
        }

        log.info("Card update committed (transaction CCUP)");

        // --- CONFIRM-UPDATE-SUCCESS: repaint the (masked) card with the success
        // confirmation and the new optimistic-lock version.
        final CardViewResponse view = toView(card);
        return new CardUpdateResponse(view, MSG_SUCCESS, card.getVersion());
    }

    /**
     * Validates the active-status change field, mirroring
     * {@code 1240-EDIT-CARDSTATUS}: the value must be present and exactly
     * {@code "Y"} or {@code "N"} (case-sensitive, as the COBOL
     * {@code FLG-YES-NO-VALID} condition tests literal {@code 'Y'}/{@code 'N'}).
     * A blank or otherwise invalid value records {@link #MSG_ACTIVE_STATUS}
     * against the {@code activeStatus} field.
     *
     * @param activeStatus the requested active-status value; may be {@code null}
     * @param fieldErrors  the accumulator to which a failure is added, preserving
     *                     copybook edit order; must not be {@code null}
     */
    private static void validateActiveStatus(final String activeStatus, final Map<String, String> fieldErrors) {
        if (activeStatus == null || activeStatus.isBlank()
                || !("Y".equals(activeStatus) || "N".equals(activeStatus))) {
            fieldErrors.put("activeStatus", MSG_ACTIVE_STATUS);
        }
    }

    /**
     * Validates the expiration-date change field, mirroring the expiry edits of
     * {@code 1250-EDIT-EXPIRY-MON} / {@code 1260-EDIT-EXPIRY-YEAR}.
     *
     * <p>The month is structurally constrained to 1&ndash;12 by {@link LocalDate}
     * (so {@code COCRDUPC}'s {@code VALID-MONTH} 1&nbsp;THRU&nbsp;12 can never be
     * violated by a parsed date). The year range is enforced explicitly to
     * preserve {@code VALID-YEAR} (1950&nbsp;THRU&nbsp;2099): a supplied date
     * whose year falls outside that range records {@link #MSG_EXPIRY_YEAR}. A
     * {@code null} date is left to the field-level request contract and is not
     * re-validated here.</p>
     *
     * @param expirationDate the requested expiration date; may be {@code null}
     * @param fieldErrors    the accumulator to which a failure is added,
     *                       preserving copybook edit order; must not be
     *                       {@code null}
     */
    private static void validateExpirationDate(final LocalDate expirationDate,
            final Map<String, String> fieldErrors) {
        if (expirationDate == null) {
            return;
        }
        final int year = expirationDate.getYear();
        if (year < MIN_EXPIRY_YEAR || year > MAX_EXPIRY_YEAR) {
            fieldErrors.put("expirationDate", MSG_EXPIRY_YEAR);
        }
    }

    /**
     * Projects a persisted {@link Card} onto the read-only
     * {@link CardViewResponse} returned to the caller.
     *
     * <p>The full card number is handed to {@link CardViewResponse}, whose
     * canonical constructor masks it to the last four characters; the CVV is
     * never read, so it cannot appear in the projection. The account id
     * ({@code CARD-ACCT-ID PIC 9(11)}) is rendered with
     * {@link String#valueOf(Object)} &mdash; unpadded, matching the card-view read
     * path ({@code CardViewService}) so a card-update round-trip returns an
     * account-id string identical to the subsequent {@code GET}.</p>
     *
     * @param card the persisted card (post-rewrite); must not be {@code null}
     * @return a PAN-masked, CVV-free view of the card
     */
    private static CardViewResponse toView(final Card card) {
        return new CardViewResponse(
                String.valueOf(card.getCardAcctId()),
                card.getCardNum(),
                card.getCardEmbossedName(),
                card.getCardActiveStatus(),
                card.getCardExpirationDate(),
                card.getVersion());
    }

}
