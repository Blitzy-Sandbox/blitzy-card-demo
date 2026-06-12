package com.cardemo.service.card;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

/**
 * Card-detail service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COCRDSLC.cbl}</strong> (CICS transaction {@code CCDL}, BMS map
 * {@code CCRDSLA} / mapset {@code COCRDSL}). The program accepts an account number and a card number,
 * edits both inputs, and performs a <strong>single keyed read of the {@code CARDDAT} VSAM file by card
 * number</strong>, returning the card's detail for display.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COCRDSLC}'s observable business behavior <em>exactly</em> &mdash; the
 * same input-edit order, the same verbatim messages, and the same read semantics. Per the Minimal
 * Change Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology
 * transition. The COBOL is read-only reference material at the frozen baseline commit SHA
 * {@code 27d6c6f} and is never copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed {@code READ} &rarr; JPA {@code findById}.</strong> The
 *       {@code EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM) KEYLENGTH 16}
 *       ({@code 9100-GETCARD-BYACCTCARD}) becomes
 *       {@link CardRepository#findById(Object) cardRepository.findById(cardNumber)}, keyed on the
 *       16-character card number.</li>
 *   <li><strong>{@code FILE STATUS} / {@code DFHRESP} &rarr; typed exceptions.</strong> The
 *       {@code WHEN DFHRESP(NOTFND)} branch ({@code DID-NOT-FIND-ACCTCARD-COMBO}) maps to
 *       {@link RecordNotFoundException} (translated to HTTP&nbsp;404 by the central
 *       {@code @RestControllerAdvice}); the {@code WHEN OTHER} file-error branch is an infrastructure
 *       failure with no JPA analogue beyond a propagating {@code DataAccessException} (HTTP&nbsp;500).</li>
 *   <li><strong>Input edits &rarr; {@link ValidationException}.</strong> The {@code RECEIVE MAP} field
 *       edits ({@code 2210-EDIT-ACCOUNT}, {@code 2220-EDIT-CARD}) and the cross-field check raise a
 *       {@link ValidationException} (HTTP&nbsp;400) carrying the verbatim COBOL message.</li>
 *   <li><strong>CICS presentation &amp; navigation intentionally omitted.</strong> PF-key handling
 *       (only ENTER / PF03-exit are valid), {@code SEND MAP} / {@code RECEIVE MAP}, screen attributes,
 *       COMMAREA plumbing and {@code XCTL} to the caller ({@code COCRDLIC}) or menu ({@code CM00}) are
 *       a controller concern handled by {@code controller/CardController}, never by this service.</li>
 * </ul>
 *
 * <h2>Read key &mdash; card number ONLY (COCRDSLC 9100)</h2>
 * <p>In {@code 9100-GETCARD-BYACCTCARD} the account-id key {@code MOVE} is <em>commented out</em>; the
 * read is keyed solely on the 16-character card number ({@code RIDFLD(WS-CARD-RID-CARDNUM)}). The
 * {@code 9150-GETCARD-BYACCT} (account-path) paragraph exists in the source but is <strong>never
 * performed</strong> &mdash; it is dead code and is deliberately not migrated. Consequently the account
 * number is <strong>validated for format only</strong> (see below); it is not part of the key, and the
 * read record's {@code CARD-ACCT-ID} is never compared back to the supplied account. Adding such a
 * post-read account match would be a behavior change forbidden by the Minimal Change Clause
 * (AAP &sect;0.7.1).</p>
 *
 * <h2>Validation fidelity &mdash; verbatim messages, first-error-wins (COCRDSLC 2200/2210/2220)</h2>
 * <p>Both the account and the card are <strong>required</strong> in {@code COCRDSLC}. The account edit
 * runs first, then the card edit. The COBOL surfaces a single {@code WS-RETURN-MSG} guarded by the
 * {@code WS-RETURN-MSG-OFF} 88-level, so once the account edit has set a message the card edit cannot
 * overwrite it &mdash; <em>first-error-wins</em>. The cross-field rule (both inputs blank) is
 * <em>not</em> guarded and therefore <em>unconditionally overrides</em> any prior message with
 * {@code "No input received"}. The messages here are the verbatim {@code COCRDSLC} texts, including the
 * comma-without-following-space and the field-size digit counts (11 / 16) in the {@code FILTER}
 * messages.</p>
 *
 * <h2>DTO/entity reconciliation</h2>
 * <p>The {@link Card} entity stores the expiration date as a {@link LocalDate}
 * ({@code expiration_date DATE}); this service renders it to the canonical {@code YYYY-MM-DD} form and
 * splits it into the year/month/day components the screen carried ({@code CARD-EXPIRAION-DATE} at
 * positions 1:4 / 6:2 / 9:2, COCRDSLC L84-92), and also exposes the {@link LocalDate} directly on the
 * DTO. The owning account ({@code CARD-ACCT-ID PIC 9(11)}, a {@link Long} on the entity) is rendered as
 * an 11-digit zero-padded string to preserve the screen field width. The card's CVV
 * ({@code CARD-CVV-CD}) is sensitive and, like the COCRDSL detail screen, is deliberately not exposed.</p>
 *
 * <p>This service is a pure boundary-to-persistence translator: it carries no presentation state and no
 * mutating behavior (the program performs only {@code EXEC CICS READ}), so it is annotated
 * {@link Transactional @Transactional(readOnly = true)} at the class level.</p>
 *
 * @see CardRepository
 * @see Card
 * @see CardDto
 * @see RecordNotFoundException
 * @see ValidationException
 */
@Service
@Transactional(readOnly = true)
public class CardDetailService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COCRDSLC messages (WS-RETURN-MSG 88-levels / inline literals). Copied EXACTLY, including
    // the comma-without-following-space and the field-size digit counts, for 100% message parity.
    // -----------------------------------------------------------------------------------------------

    /** {@code WS-PROMPT-FOR-ACCT} (COCRDSLC L139) &mdash; account not supplied. */
    private static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** Account not-numeric edit literal (COCRDSLC 2210, L670) &mdash; verbatim (comma, no following space). */
    private static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** {@code WS-PROMPT-FOR-CARD} (COCRDSLC L141) &mdash; card not supplied. */
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /** Card not-numeric edit literal (COCRDSLC 2220, L711) &mdash; verbatim (comma, no following space). */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED} (COCRDSLC L143) &mdash; unconditional both-blank override. */
    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} (COCRDSLC L154) &mdash; NOTFND on the keyed card read. */
    private static final String MSG_CARDS_NOT_FOUND = "Did not find cards for this search condition";

    // -----------------------------------------------------------------------------------------------
    // Edit helpers. COBOL "IS NOT NUMERIC" only checks the field is all digits (the 11/16 are the field
    // sizes, not extra length rules), and '*'/SPACES/ZEROS are treated as "not supplied" (LOW-VALUES).
    // -----------------------------------------------------------------------------------------------

    /** Matches an all-digits value (COBOL {@code IS NUMERIC} on an unsigned display field). */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Matches an all-zeros value (COBOL {@code CC-*-N EQUAL ZEROS} &rarr; treated as not supplied). */
    private static final Pattern ZEROS = Pattern.compile("0+");

    /** Single asterisk sentinel that {@code COCRDSLC} 2200 normalizes to {@code LOW-VALUES}. */
    private static final String ASTERISK_SENTINEL = "*";

    /**
     * Renders the entity {@link LocalDate} expiration to the canonical {@code YYYY-MM-DD} text the COBOL
     * {@code CARD-EXPIRAION-DATE PIC X(10)} field held, so the positional year/month/day split matches
     * the COBOL redefinition exactly.
     */
    private static final DateTimeFormatter EXPIRY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Length of the {@code CARD-EXPIRAION-DATE} field ({@code PIC X(10)}, {@code YYYY-MM-DD}). */
    private static final int EXPIRY_DATE_LENGTH = 10;

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    private final CardRepository cardRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param cardRepository repository for the {@code CARDDAT} VSAM replacement; its inherited
     *                       {@link CardRepository#findById(Object) findById(String)} performs the keyed
     *                       card-number read that {@code COCRDSLC} executed via CICS file control
     */
    public CardDetailService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns the detail of a single card, reproducing {@code COCRDSLC} end to end.
     *
     * <p>Processing order mirrors the COBOL {@code 0000-MAIN} dispatch:</p>
     * <ol>
     *   <li><strong>Edit the inputs</strong> ({@code 2200-EDIT-MAP-INPUTS} &rarr;
     *       {@code 2210-EDIT-ACCOUNT}, {@code 2220-EDIT-CARD}, cross-field). On any failure a
     *       {@link ValidationException} is raised <em>before</em> any database access &mdash; the same
     *       fields are flagged, with the verbatim first-error-wins / both-blank-override message.</li>
     *   <li><strong>Read {@code CARDDAT} by card number</strong> ({@code 9000-READ-DATA} &rarr;
     *       {@code 9100-GETCARD-BYACCTCARD}). A miss raises {@link RecordNotFoundException}; a hit is
     *       mapped to the response DTO.</li>
     * </ol>
     *
     * <p>The account number is validated for format only; it is not a read key and is not compared to
     * the read record's owning account (COCRDSLC parity &mdash; see the class documentation).</p>
     *
     * @param accountId  the account number from the request ({@code COCRDSLC CC-ACCT-ID}); required and
     *                   validated for format, but not used as a read key
     * @param cardNumber the 16-digit card number from the request ({@code COCRDSLC CC-CARD-NUM}); the
     *                   sole read key
     * @return the populated single-card {@link CardDto} for the located card
     * @throws ValidationException     if the account or card input fails its edit (HTTP&nbsp;400)
     * @throws RecordNotFoundException if no card exists for the supplied card number (HTTP&nbsp;404)
     */
    public CardDto getCardDetail(String accountId, String cardNumber) {
        // PHASE 1 - Input edits preserved verbatim from COCRDSLC 2210/2220; first-error-wins via
        // WS-RETURN-MSG-OFF, plus the unconditional both-blank override. Throws before any DB access.
        validateInputs(accountId, cardNumber);

        // PHASE 2 - Read CARDDAT keyed on the 16-char CARD NUMBER ONLY (the account-id key MOVE is
        // commented out in 9100, and 9150-GETCARD-BYACCT is dead). The account is validated for format
        // only and is NOT a key or a post-read filter (adding such a match would change behavior, §0.7.1).
        // VSAM KSDS keyed READ FILE('CARDDAT') RIDFLD(card-number, KEYLENGTH 16) -> cardRepository.findById(cardNumber) [COCRDSLC 9100]
        Optional<Card> result = cardRepository.findById(cardNumber);

        // COBOL FILE STATUS NOTFND -> RecordNotFoundException (HTTP 404) [COCRDSLC 9100 DFHRESP(NOTFND)].
        // The verbatim DID-NOT-FIND-ACCTCARD-COMBO message (L154) preserves the user-visible text.
        // The WHEN OTHER file-error branch is an infrastructure failure with no JPA findById analogue
        // (the realistic outcomes are present/empty); any underlying DataAccessException simply
        // propagates to the central @RestControllerAdvice (HTTP 500), so no explicit handling is needed.
        Card card = result.orElseThrow(() -> new RecordNotFoundException(MSG_CARDS_NOT_FOUND));

        // PHASE 3 - NORMAL path (COBOL sets FOUND-CARDS-FOR-ACCOUNT): map the record to the DTO.
        return toDto(card);
    }

    /**
     * Reproduces the {@code COCRDSLC} input-edit cascade ({@code 2200-EDIT-MAP-INPUTS} and its
     * {@code 2210-EDIT-ACCOUNT} / {@code 2220-EDIT-CARD} helpers) with exact COBOL semantics:
     * <ul>
     *   <li>the account edit runs first and the card edit second;</li>
     *   <li>each edit checks "not supplied" (blank / {@code '*'} / all-zeros) before "not numeric";</li>
     *   <li><strong>first-error-wins</strong>: an account message, if any, beats a card message (the
     *       COBOL {@code WS-RETURN-MSG-OFF} guard); and</li>
     *   <li>the cross-field rule <strong>unconditionally overrides</strong> any prior message with
     *       {@code "No input received"} when <em>both</em> inputs are not supplied (COCRDSLC L637-640).</li>
     * </ul>
     * When a message results, a {@link ValidationException} is thrown and no read is performed.
     *
     * @param accountId  the account input ({@code CC-ACCT-ID})
     * @param cardNumber the card input ({@code CC-CARD-NUM})
     * @throws ValidationException with the verbatim COBOL message if an edit fails
     */
    private void validateInputs(String accountId, String cardNumber) {
        boolean accountNotSupplied = isNotSupplied(accountId);
        boolean cardNotSupplied = isNotSupplied(cardNumber);

        // 2210-EDIT-ACCOUNT - "not supplied" is tested before "not numeric"; account runs FIRST.
        String accountError = null;
        if (accountNotSupplied) {
            accountError = MSG_ACCOUNT_NOT_PROVIDED;
        } else if (!isAllDigits(accountId)) {
            accountError = MSG_ACCOUNT_FILTER_NOT_NUMERIC;
        }

        // 2220-EDIT-CARD - same precedence; card runs SECOND.
        String cardError = null;
        if (cardNotSupplied) {
            cardError = MSG_CARD_NOT_PROVIDED;
        } else if (!isAllDigits(cardNumber)) {
            cardError = MSG_CARD_FILTER_NOT_NUMERIC;
        }

        // First-error-wins (WS-RETURN-MSG-OFF guard): the account message, if present, takes precedence
        // over the card message exactly as the COBOL guard prevents the card edit from overwriting it.
        String errorMsg = (accountError != null) ? accountError : cardError;

        // Cross-field UNCONDITIONAL override (COCRDSLC 2200, L637-640): when BOTH inputs are not
        // supplied, "No input received" replaces whatever message the field edits produced.
        if (accountNotSupplied && cardNotSupplied) {
            errorMsg = MSG_NO_INPUT_RECEIVED;
        }

        if (errorMsg != null) {
            throw new ValidationException(errorMsg);
        }
    }

    /**
     * Determines whether an input is "not supplied" using {@code COCRDSLC} semantics: the COBOL 2200
     * paragraph normalizes {@code '*'} or {@code SPACES} to {@code LOW-VALUES}, and the field edits treat
     * {@code LOW-VALUES}, {@code SPACES} and an all-zeros numeric value ({@code CC-*-N EQUAL ZEROS}) as
     * not supplied.
     *
     * @param value the raw input value
     * @return {@code true} when the value is {@code null}, blank (after trim), the lone {@code '*'}
     *         sentinel, or all zeros; {@code false} otherwise
     */
    private static boolean isNotSupplied(String value) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            // SPACES / LOW-VALUES (COCRDSLC 2200 L616/623 and the 2210/2220 LOW-VALUES/SPACES tests).
            return true;
        }
        if (ASTERISK_SENTINEL.equals(trimmed)) {
            // '*' normalized to LOW-VALUES (COCRDSLC 2200 L615/622).
            return true;
        }
        // CC-ACCT-ID-N / CC-CARD-NUM-N EQUAL ZEROS (COCRDSLC L653/693).
        return ZEROS.matcher(trimmed).matches();
    }

    /**
     * Determines whether a supplied input is all digits, mirroring the COBOL {@code IS NUMERIC} test on
     * an unsigned display field. The COBOL only checks digit composition (the 11/16 figures in the
     * messages are field sizes, not additional length constraints), so this performs no length check.
     *
     * @param value the input value (already known to be supplied)
     * @return {@code true} when every character (after trim) is a digit; {@code false} otherwise
     */
    private static boolean isAllDigits(String value) {
        return value != null && DIGITS.matcher(value.trim()).matches();
    }

    /**
     * Maps a located {@link Card} entity to the single-card {@link CardDto}, reproducing the fields the
     * {@code COCRDSLC} {@code 1200-SETUP-SCREEN-VARS} paragraph painted on the {@code CCRDSLA} map.
     *
     * <p>The card number, embossed name and active-status flag map straight across. The owning account
     * ({@code CARD-ACCT-ID PIC 9(11)}, a {@link Long}) is rendered as an 11-digit zero-padded string to
     * match the screen field width. The expiration date &mdash; stored on the entity as a
     * {@link LocalDate} &mdash; is rendered to its canonical {@code YYYY-MM-DD} text and split into the
     * year / month / day components ({@code CARD-EXPIRAION-DATE} positions 1:4 / 6:2 / 9:2, COCRDSLC
     * L84-92), and is also exposed directly as the DTO's derived {@link LocalDate}.</p>
     *
     * @param card the located card record (never {@code null})
     * @return the populated single-card DTO
     */
    private CardDto toDto(Card card) {
        CardDto dto = new CardDto();

        // CARD-NUM PIC X(16) -> the keyed card number echoed on CARDSIDO [COCRDSLC 1200].
        dto.setCardNumber(card.getCardNum());

        // CARD-ACCT-ID PIC 9(11) -> 11-digit zero-padded String to match the screen field width.
        // The DTO field is @Size(max=11)/@Pattern \d{1,11}, so an 11-digit string is valid. account_id
        // is NOT NULL in the schema, so the guard only protects against malformed in-memory test data.
        Long owningAccount = card.getCardAcctId();
        if (owningAccount != null) {
            dto.setAccountId(String.format("%011d", owningAccount));
        }

        // CARD-EMBOSSED-NAME PIC X(50) -> CRDNAMEO [COCRDSLC 1200 L475]. (Entity getter is
        // getCardEmbossedName(); DTO setter is setEmbossedName().)
        dto.setEmbossedName(card.getCardEmbossedName());

        // CARD-ACTIVE-STATUS PIC X(01) -> CRDSTCDO [COCRDSLC 1200 L484]. (DTO setter is setActiveStatus().)
        dto.setActiveStatus(card.getCardActiveStatus());

        // CARD-EXPIRAION-DATE -> EXPYEARO/EXPMONO (+ day component) [COCRDSLC 1200 L477-482].
        // The entity stores this as a LocalDate (expiration_date DATE); render it to the canonical
        // YYYY-MM-DD text the COBOL CARD-EXPIRAION-DATE-X field held, then split it positionally
        // (year 1:4, month 6:2, day 9:2) exactly as the COBOL FILLER redefinition did (L84-92).
        LocalDate expiry = card.getCardExpirationDate();
        if (expiry != null) {
            String expiryText = expiry.format(EXPIRY_DATE_FORMATTER);
            if (expiryText.length() >= EXPIRY_DATE_LENGTH) {
                dto.setExpiryYear(expiryText.substring(0, 4));   // CARD-EXPIRY-YEAR  (positions 1:4)
                dto.setExpiryMonth(expiryText.substring(5, 7));  // CARD-EXPIRY-MONTH (positions 6:2)
                dto.setExpiryDay(expiryText.substring(8, 10));   // CARD-EXPIRY-DAY   (positions 9:2)
            }
            // Optional derived LocalDate, set directly (the entity already supplies a LocalDate).
            dto.setExpirationDate(expiry);
        }

        // CARD-CVV-CD is sensitive and, like the COCRDSL detail map, is intentionally not exposed.
        // CICS SEND/RECEIVE MAP & XCTL navigation intentionally omitted -> handled by CardController.
        return dto;
    }
}

