package com.carddemo.service;

import java.util.regex.Pattern;
import io.micrometer.observation.annotation.Observed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.CardViewResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Application service for the single Credit-Card View use case (online
 * transaction {@code CCDL}).
 *
 * <p>This {@code @Service} is the idiomatic Spring translation of the legacy
 * COBOL/CICS program {@code COCRDSLC} ({@code app/cbl/COCRDSLC.cbl}, 887&nbsp;LOC,
 * frozen reference SHA {@code 27d6c6f}), whose function header reads
 * <em>"Accept and process credit card detail request"</em>. It returns the
 * detail of exactly one card, located by its card number.</p>
 *
 * <p><strong>Control-flow provenance.</strong> The public
 * {@link #viewCard(String)} method preserves the ordering and semantics of the
 * COBOL paragraphs that drive the "view a single card" path:</p>
 * <ul>
 *   <li>{@code 2220-EDIT-CARD} &mdash; the input edit that rejects a card number
 *       that is not supplied (blank / spaces / zeros) or that is not a 16-digit
 *       number. Both COBOL rejection branches raise the same user-facing message
 *       ({@code 88 SEARCHED-CARD-NOT-NUMERIC}), so they collapse into a single
 *       {@link ValidationException} carrying {@link #MSG_CARD_INVALID}.</li>
 *   <li>{@code 9000-READ-DATA} &rarr; {@code 9100-GETCARD-BYACCTCARD} &mdash; the
 *       keyed read of the {@code CARDDAT} file by card number
 *       ({@code EXEC CICS READ FILE('CARDDAT') RIDFLD(card-number)}). The COBOL
 *       {@code EVALUATE WS-RESP-CD} maps {@code DFHRESP(NORMAL)} to a successful
 *       projection and {@code DFHRESP(NOTFND)} to the "not found" message. Here
 *       the keyed read is {@link CardRepository#findById(Object) findById}, and
 *       the {@code NOTFND} branch becomes a {@link ResourceNotFoundException}
 *       carrying {@link #MSG_CARD_NOT_FOUND}. The end-of-file / other-response
 *       branches have no single-view analogue and are handled uniformly by the
 *       persistence layer.</li>
 * </ul>
 *
 * <p><strong>Security invariants.</strong> This service enforces the migration's
 * card-data protection rules (AAP &sect;0.3.2):</p>
 * <ul>
 *   <li><strong>PAN masking</strong> &mdash; the Primary Account Number returned
 *       to callers is masked so that only the trailing
 *       {@value #VISIBLE_PAN_DIGITS} characters remain visible. Masking is
 *       applied here via {@link #maskPan(String)} and again, idempotently, by the
 *       {@link CardViewResponse} canonical constructor (defense in depth); the
 *       two algorithms are intentionally identical so the double application is a
 *       no-op on an already-masked value.</li>
 *   <li><strong>No CVV</strong> &mdash; the card verification value
 *       ({@code CARD-CVV-CD}) is a secret that never appears on the
 *       {@code COCRDSL} screen and must never be emitted by the API. Although
 *       {@link Card#getCardCvvCd()} exists on the entity, this service never
 *       reads it, and {@link CardViewResponse} has no field for it.</li>
 *   <li><strong>No secret logging</strong> &mdash; the logger emits only masked
 *       card numbers and coarse diagnostics; the full PAN and the CVV are never
 *       logged.</li>
 * </ul>
 *
 * <p>The service is read-only and stateless. Its single dependency
 * ({@link CardRepository}) is supplied through constructor injection, which also
 * allows the class to be unit-tested with a mock repository without bootstrapping
 * a Spring application context.</p>
 */
@Service
public class CardViewService {

    /**
     * User-facing message emitted when the supplied card number fails the input
     * edit (not supplied, or not a 16-digit number).
     *
     * <p>Transcribed verbatim from the {@code 88 SEARCHED-CARD-NOT-NUMERIC}
     * condition on {@code WS-RETURN-MSG} in {@code COCRDSLC} (SHA {@code 27d6c6f}).
     * Preserving the exact text maintains external message parity (Gate&nbsp;5).</p>
     */
    public static final String MSG_CARD_INVALID =
            "Card number if supplied must be a 16 digit number";

    /**
     * User-facing message emitted when no card exists for the supplied card
     * number (the migrated {@code DFHRESP(NOTFND)} branch of the keyed read).
     *
     * <p>Transcribed verbatim from the {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}
     * condition on {@code WS-RETURN-MSG} in {@code COCRDSLC} (SHA {@code 27d6c6f}),
     * which the AAP designates as the not-found message for the single-card view.</p>
     */
    public static final String MSG_CARD_NOT_FOUND =
            "Did not find this account in cards database";

    /** Number of trailing card-number characters that remain visible after masking. */
    private static final int VISIBLE_PAN_DIGITS = 4;

    /** Character substituted for each concealed card-number character. */
    private static final char MASK_CHARACTER = '*';

    /**
     * Precompiled matcher for a valid card number: exactly 16 decimal digits.
     *
     * <p>Reproduces the {@code COCRDSLC} edit {@code 2220-EDIT-CARD}, whose input
     * field {@code CC-CARD-NUM} is a fixed 16-character picture that must be
     * numeric. Compiling once and reusing avoids per-request regex compilation.</p>
     */
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("\\d{16}");

    /** SLF4J logger; emits only masked card numbers and coarse, non-sensitive diagnostics. */
    private static final Logger log = LoggerFactory.getLogger(CardViewService.class);

    /**
     * Repository providing keyed access to the migrated {@code CARDDATA} store.
     * Final and injected via the constructor to guarantee immutability and
     * testability.
     */
    private final CardRepository cardRepository;

    /**
     * Creates the service with its collaborating repository.
     *
     * <p>Constructor injection is used deliberately (no field injection) so the
     * service can be instantiated directly in unit tests with a mock
     * {@link CardRepository}.</p>
     *
     * @param cardRepository the card repository (migrated {@code CARDDAT} access);
     *                       must not be {@code null}
     */
    public CardViewService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns the detail of a single card identified by its card number.
     *
     * <p>Behaviour, mirroring {@code COCRDSLC}:</p>
     * <ol>
     *   <li><strong>Input edit</strong> ({@code 2220-EDIT-CARD}): the card number
     *       must be supplied and be exactly 16 digits; otherwise a
     *       {@link ValidationException} with {@link #MSG_CARD_INVALID} is thrown
     *       (HTTP&nbsp;400).</li>
     *   <li><strong>Keyed read</strong> ({@code 9100-GETCARD-BYACCTCARD}): the
     *       card is looked up by its number. When no row exists (the migrated
     *       {@code DFHRESP(NOTFND)} branch), a {@link ResourceNotFoundException}
     *       with {@link #MSG_CARD_NOT_FOUND} is thrown (HTTP&nbsp;404).</li>
     *   <li><strong>Projection</strong>: on success the card is projected onto an
     *       immutable {@link CardViewResponse}. The PAN is masked and the CVV is
     *       never read, satisfying the card-data protection rules.</li>
     * </ol>
     *
     * <p>The method is transactional and read-only, so no write lock is taken and
     * the persistence provider may apply read optimizations.</p>
     *
     * @param cardNumber the 16-digit card number to view; must be non-blank and
     *                   exactly 16 digits
     * @return the masked, CVV-free {@link CardViewResponse} for the requested card
     * @throws ValidationException       if {@code cardNumber} is {@code null},
     *                                   blank, or not exactly 16 digits
     * @throws ResourceNotFoundException if no card exists for {@code cardNumber}
     */
    @Transactional(readOnly = true)
    @Observed(name = "carddemo.service", contextualName = "card-view")
    public CardViewResponse viewCard(String cardNumber) {
        // 2220-EDIT-CARD: "Not supplied" branch (CC-CARD-NUM blank / spaces / zeros).
        if (cardNumber == null || cardNumber.isBlank()) {
            log.debug("Rejected card view request: card number was not supplied");
            throw new ValidationException(MSG_CARD_INVALID);
        }
        // 2220-EDIT-CARD: "Not numeric / not 16 characters" branch. The raw value
        // is intentionally NOT logged, so that no unvalidated caller input leaks.
        if (!CARD_NUMBER_PATTERN.matcher(cardNumber).matches()) {
            log.debug("Rejected card view request: card number failed 16-digit format edit");
            throw new ValidationException(MSG_CARD_INVALID);
        }

        // 9000-READ-DATA -> 9100-GETCARD-BYACCTCARD: keyed read of CARDDAT by
        // card number. Optional.orElseThrow reproduces the DFHRESP(NOTFND) branch.
        log.debug("Reading card detail for PAN {}", maskPan(cardNumber));
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_CARD_NOT_FOUND));

        // Successful projection. CVV (Card#getCardCvvCd) is intentionally not read.
        log.debug("Resolved card detail for PAN {}", maskPan(card.getCardNum()));
        return new CardViewResponse(
                String.valueOf(card.getCardAcctId()),
                maskPan(card.getCardNum()),
                card.getCardEmbossedName(),
                card.getCardActiveStatus(),
                card.getCardExpirationDate(),
                card.getVersion());
    }

    /**
     * Masks a card number so that only its last {@value #VISIBLE_PAN_DIGITS}
     * characters remain visible, preserving the original width.
     *
     * <p>The algorithm is deliberately identical to the one enforced by the
     * {@link CardViewResponse} canonical constructor, so masking an
     * already-masked value is a no-op (idempotent). The full PAN is never
     * exposed:</p>
     * <ul>
     *   <li>a {@code null} input yields {@code null};</li>
     *   <li>an input of length &le; {@value #VISIBLE_PAN_DIGITS} is masked in its
     *       entirety, so a short value can never be revealed in full;</li>
     *   <li>otherwise every character except the trailing
     *       {@value #VISIBLE_PAN_DIGITS} is replaced with the mask character.</li>
     * </ul>
     *
     * @param pan the card number (PAN) to mask; may be {@code null}
     * @return the masked card number, or {@code null} when {@code pan} is
     *         {@code null}
     */
    private String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        int length = pan.length();
        if (length <= VISIBLE_PAN_DIGITS) {
            return String.valueOf(MASK_CHARACTER).repeat(length);
        }
        int maskedLength = length - VISIBLE_PAN_DIGITS;
        return String.valueOf(MASK_CHARACTER).repeat(maskedLength)
                + pan.substring(maskedLength);
    }
}
