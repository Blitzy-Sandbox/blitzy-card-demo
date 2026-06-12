package com.cardemo.service.card;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that updates a single card record, reproducing the legacy AWS CardDemo online
 * program {@code COCRDUPC} (Card Update; CICS transaction {@code CCUP}, BMS map {@code CCRDUPA}, 1,560
 * lines) on the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x stack.
 *
 * <p>The COBOL program is a pseudo-conversational, multi-step "view&nbsp;&rarr;&nbsp;edit&nbsp;&rarr;
 * confirm&nbsp;&rarr;&nbsp;rewrite" transaction. In the migrated REST architecture that conversational
 * choreography (PF03 exit, PF05 confirm, PF12 cancel, {@code SEND}/{@code RECEIVE MAP}, {@code XCTL}) is a
 * <em>controller</em> concern; the multi-step confirm collapses into a single {@code PUT}. This service
 * therefore performs the server-side core of {@code COCRDUPC} in one call:
 * <strong>validate&nbsp;&rarr;&nbsp;read&nbsp;&rarr;&nbsp;detect-no-change&nbsp;&rarr;&nbsp;apply&nbsp;
 * &rarr;&nbsp;save</strong>, returning the updated {@link CardDto}.</p>
 *
 * <h2>Central parity mapping &mdash; manual snapshot compare &rarr; JPA {@code @Version}</h2>
 * <p>The mainframe program guarded its update with two cooperating mechanisms: a
 * {@code READ &hellip; UPDATE} lock ({@code 9200-WRITE-PROCESSING}) and a manual before/after image
 * comparison ({@code 9300-CHECK-CHANGE-IN-REC}) that re-read the record and rejected the
 * {@code REWRITE} if another task had changed it in the meantime. Both are replaced by the JPA
 * {@code @Version} column on the {@link Card} entity (AAP &sect;0.6.4, &sect;0.7.5): Hibernate emits
 * {@code UPDATE cards &hellip; WHERE card_number = ? AND version = ?}, and a zero-row result raises an
 * {@link ObjectOptimisticLockingFailureException}. This service maps that to
 * {@link ConcurrentModificationException} (HTTP&nbsp;409). The COBOL re-read-and-compare is therefore
 * <strong>not</strong> hand-rolled here; the optimistic-lock column carries the concurrency contract.</p>
 *
 * <h2>Editable surface (COCRDUPC {@code 9200} record build)</h2>
 * <p>Only four fields are user-editable: the embossed <em>name</em>, the active-<em>status</em> flag,
 * and the expiry <em>month</em> and <em>year</em>. The card's <em>CVV</em> ({@code CARD-CVV-CD}) is not
 * on the edit screen and is carried unchanged from the existing record, and the expiry <em>day</em>
 * ({@code EXPDAY}) is likewise carried from the stored record &mdash; the COBOL assembles the new
 * expiration date as {@code STRING(NEW-EXPYEAR '-' NEW-EXPMON '-' NEW-EXPDAY)} where {@code NEW-EXPDAY}
 * originates from the old image (the new-day screen output is commented out at L1122 and the old day is
 * re-displayed at L1123). The owning account id is not modified either: only the read key is used and,
 * in the normal "view-then-update" flow, the supplied account already equals the card's owner.</p>
 *
 * <h2>Validation fidelity &mdash; verbatim messages, first-error-wins (COCRDUPC 1200&rarr;1210..1260)</h2>
 * <p>The six field edits run in a fixed order &mdash; account, card, name, status, expiry month, expiry
 * year &mdash; and the COBOL {@code WS-RETURN-MSG-OFF} 88-level guard means only the <em>first</em>
 * failing edit's message is surfaced ({@code first-error-wins}). Each message below is copied
 * <strong>verbatim</strong> from the COBOL working storage (including the comma-without-following-space
 * and the field-size digit counts in the {@code FILTER} messages) for 100% parity. Validation runs
 * <em>before</em> any database access; on the first failure a {@link ValidationException} (HTTP&nbsp;400)
 * is thrown.</p>
 *
 * <h2>DTO / entity reconciliation</h2>
 * <p>The {@link Card} entity stores the expiration date as a {@link LocalDate}
 * ({@code expiration_date DATE}) and the CVV as an {@link Integer}; the screen contract carried the
 * date as the {@code CARD-EXPIRAION-DATE PIC X(10)} {@code YYYY-MM-DD} text split into year/month/day
 * components. This service therefore composes the new date as
 * {@code LocalDate.of(year, month, carriedDay)} on write, and on read renders the {@link LocalDate}
 * back to its canonical {@code YYYY-MM-DD} text and splits it positionally (year&nbsp;1:4,
 * month&nbsp;6:2, day&nbsp;9:2) &mdash; identical to the sibling {@code CardDetailService}. The owning
 * account ({@code CARD-ACCT-ID PIC 9(11)}, a {@link Long}) is rendered as an 11-digit zero-padded
 * string to preserve the screen field width. The CVV is sensitive and, like the detail screen, is
 * never exposed on the response.</p>
 *
 * <h2>Transaction &amp; concurrency mapping (AAP &sect;0.6.4, &sect;0.7.5)</h2>
 * <p>The sole {@code SYNCPOINT}-bearing write semantics of {@code COCRDUPC} map to Spring declarative
 * transactions: {@link #updateCard(String, String, CardDto)} is {@link Transactional @Transactional},
 * so any unchecked exception rolls back the unit of work. The three COBOL concurrency outcomes map as
 * follows:</p>
 * <ul>
 *   <li>{@code COULD-NOT-LOCK-FOR-UPDATE} ("Could not lock record for update") and
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} ("Record changed by some one else. Please review") both
 *       surface, under JPA optimistic locking, as {@link ObjectOptimisticLockingFailureException}
 *       (Spring's wrapper around {@link OptimisticLockException}) &rarr;
 *       {@link ConcurrentModificationException} (HTTP&nbsp;409).</li>
 *   <li>{@code LOCKED-BUT-UPDATE-FAILED} ("Update of record failed"), a generic persistence failure,
 *       has no optimistic-lock analogue and simply propagates as a {@code DataAccessException} &rarr;
 *       HTTP&nbsp;500 via the central {@code @RestControllerAdvice}.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Migrated 1:1 from the frozen COBOL baseline {@code COCRDUPC.cbl} at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never copied into
 * this repository (AAP &sect;0.7.2). Governing rules: AAP &sect;0.4.1&nbsp;L626, &sect;0.6.4,
 * &sect;0.7.1 (Minimal Change), &sect;0.7.2 (100% parity), &sect;0.7.4 (control flow), &sect;0.7.5
 * ({@code @Version} / {@code @Transactional}).</p>
 *
 * @see CardRepository
 * @see Card
 * @see CardDto
 * @see RecordNotFoundException
 * @see ValidationException
 * @see ConcurrentModificationException
 */
@Service
public class CardUpdateService {

    // ---------------------------------------------------------------------------------------------
    // Verbatim COCRDUPC messages (WS-RETURN-MSG 88-levels / inline literals). Copied EXACTLY, including
    // the comma-without-following-space and the field-size digit counts (11 / 16), for 100% parity.
    // ---------------------------------------------------------------------------------------------

    /** {@code WS-PROMPT-FOR-ACCT} (COCRDUPC L178) &mdash; account not supplied. */
    private static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** Account not-numeric edit literal (COCRDUPC 1210, L745) &mdash; verbatim (comma, no following space). */
    private static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** {@code WS-PROMPT-FOR-CARD} (COCRDUPC L180) &mdash; card not supplied. */
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /** Card not-numeric edit literal (COCRDUPC 1220, L789) &mdash; verbatim (comma, no following space). */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code WS-PROMPT-FOR-NAME} (COCRDUPC L182) &mdash; name not supplied. */
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";

    /** {@code WS-NAME-MUST-BE-ALPHA} (COCRDUPC L184) &mdash; name contains non-alpha/non-space characters. */
    private static final String MSG_NAME_NOT_ALPHA = "Card name can only contain alphabets and spaces";

    /** {@code CARD-STATUS-MUST-BE-YES-NO} (COCRDUPC L196) &mdash; active status not {@code 'Y'}/{@code 'N'}. */
    private static final String MSG_STATUS_NOT_YES_NO = "Card Active Status must be Y or N";

    /** {@code CARD-EXPIRY-MONTH-NOT-VALID} (COCRDUPC L198) &mdash; expiry month outside 1..12. */
    private static final String MSG_EXPIRY_MONTH_INVALID = "Card expiry month must be between 1 and 12";

    /** {@code CARD-EXPIRY-YEAR-NOT-VALID} (COCRDUPC L200) &mdash; expiry year outside 1950..2099. */
    private static final String MSG_EXPIRY_YEAR_INVALID = "Invalid card expiry year";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} (COCRDUPC L204) &mdash; NOTFND on the keyed card read. */
    private static final String MSG_CARDS_NOT_FOUND = "Did not find cards for this search condition";

    /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (COCRDUPC L208) &mdash; optimistic-lock conflict on save. */
    private static final String MSG_CONCURRENT_MODIFICATION = "Record changed by some one else. Please review";

    /**
     * {@code NO-CHANGES-DETECTED} (COCRDUPC L188). The COBOL surfaced this informational text when the
     * submitted values matched the fetched record and performed <em>no</em> write. The confirmed
     * {@link CardDto} carries no message field, so presenting this informational text is a controller /
     * presentation concern; the constant is retained for traceability and so callers/tests can assert
     * the exact wording. It is deliberately <strong>not</strong> thrown &mdash; "no change" is
     * informational, not an error.
     */
    static final String MSG_NO_CHANGE_DETECTED = "No change detected with respect to values fetched.";

    // ---------------------------------------------------------------------------------------------
    // Edit helpers / domain ranges. COBOL "IS NUMERIC" only checks the field is all digits (the 11/16
    // are field sizes, not extra length rules); '*'/SPACES/ZEROS are treated as "not supplied".
    // ---------------------------------------------------------------------------------------------

    /** Matches an all-digits value (COBOL {@code IS NUMERIC} on an unsigned display field). */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Matches an all-zeros value (COBOL {@code &hellip; EQUAL ZEROS} &rarr; treated as not supplied). */
    private static final Pattern ZEROS = Pattern.compile("0+");

    /**
     * Matches a name composed only of ASCII alphabetic characters and spaces. This reproduces the COBOL
     * {@code 1230-EDIT-NAME} edit, which {@code INSPECT &hellip; CONVERTING} the 52-letter set
     * {@code A-Za-z} to spaces and then required {@code FUNCTION LENGTH(FUNCTION TRIM(&hellip;)) = 0}
     * &mdash; i.e. nothing but letters and spaces may remain (COCRDUPC L255-257, L823-828).
     */
    private static final Pattern ALPHA_SPACES = Pattern.compile("[A-Za-z ]+");

    /** Single asterisk sentinel that {@code COCRDUPC}'s {@code RECEIVE-MAP} normalizes to {@code LOW-VALUES}. */
    private static final String ASTERISK_SENTINEL = "*";

    /** Lower (inclusive) bound of {@code VALID-MONTH VALUES 1 THRU 12} (COCRDUPC L95). */
    private static final int MIN_MONTH = 1;

    /** Upper (inclusive) bound of {@code VALID-MONTH VALUES 1 THRU 12} (COCRDUPC L95). */
    private static final int MAX_MONTH = 12;

    /** Lower (inclusive) bound of {@code VALID-YEAR VALUES 1950 THRU 2099} (COCRDUPC L99). */
    private static final int MIN_YEAR = 1950;

    /** Upper (inclusive) bound of {@code VALID-YEAR VALUES 1950 THRU 2099} (COCRDUPC L99). */
    private static final int MAX_YEAR = 2099;

    /** Default day-of-month used when the stored expiration date is absent (the carried "01" guard). */
    private static final int DEFAULT_DAY_OF_MONTH = 1;

    /**
     * Renders the entity {@link LocalDate} expiration to the canonical {@code YYYY-MM-DD} text the COBOL
     * {@code CARD-EXPIRAION-DATE PIC X(10)} field held, so the positional year/month/day split on the
     * response matches the COBOL redefinition exactly.
     */
    private static final DateTimeFormatter EXPIRY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Length of the {@code CARD-EXPIRAION-DATE} field ({@code PIC X(10)}, {@code YYYY-MM-DD}). */
    private static final int EXPIRY_DATE_LENGTH = 10;

    // ---------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // ---------------------------------------------------------------------------------------------

    private final CardRepository cardRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param cardRepository repository for the {@code CARDDAT} VSAM replacement; its inherited
     *                       {@link CardRepository#findById(Object) findById(String)} performs the keyed
     *                       card-number read ({@code COCRDUPC 9200} {@code READ &hellip; UPDATE}) and its
     *                       inherited {@code saveAndFlush(Card)} performs the version-checked
     *                       {@code REWRITE}
     */
    public CardUpdateService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Updates a single card, reproducing the server-side core of {@code COCRDUPC} in one call.
     *
     * <p>Processing order mirrors the COBOL transaction collapsed to a single REST {@code PUT}:</p>
     * <ol>
     *   <li><strong>Edit the inputs</strong> ({@code 1200-EDIT-MAP-INPUTS} &rarr; {@code 1210}..{@code 1260}).
     *       On the first failing edit a {@link ValidationException} is raised <em>before</em> any database
     *       access, with the verbatim COBOL message and first-error-wins precedence.</li>
     *   <li><strong>Read {@code CARDDAT} by card number</strong> ({@code COCRDUPC 9200} {@code READ &hellip;
     *       UPDATE}). A miss raises {@link RecordNotFoundException}.</li>
     *   <li><strong>Detect no change</strong> ({@code NO-CHANGES-DETECTED}, L681). If the submitted
     *       editable values match the fetched record, no write is performed and the current record is
     *       returned (avoids bumping {@code @Version}).</li>
     *   <li><strong>Apply &amp; persist</strong> ({@code 9200} build + {@code REWRITE}) via
     *       {@code saveAndFlush}, with the {@code @Version} conflict translated to
     *       {@link ConcurrentModificationException} (HTTP&nbsp;409).</li>
     * </ol>
     *
     * <p>The account number is validated for format only; it is not a read key and is not compared to the
     * read record's owning account (COCRDUPC parity &mdash; see the class documentation). Only the name,
     * active status, and expiry month/year are editable; the CVV and the expiry day are carried unchanged
     * from the existing record, and the {@code @Version} column and the owning account id are not touched.</p>
     *
     * @param accountId  the account number from the screen ({@code COCRDUPC CC-ACCT-ID}); required and
     *                   validated for format, but not used as a read key or post-read filter
     * @param cardNumber the 16-digit card number from the screen ({@code COCRDUPC CC-CARD-NUM}); the sole
     *                   read key
     * @param request    the user-edited fields (embossed name, active status, expiry month/year)
     * @return the updated single-card {@link CardDto} (or, on a no-change submission, the current record
     *         mapped to a {@link CardDto})
     * @throws ValidationException             if any input edit fails (HTTP&nbsp;400)
     * @throws RecordNotFoundException         if no card exists for the supplied card number (HTTP&nbsp;404)
     * @throws ConcurrentModificationException if the record was changed concurrently, surfacing as a JPA
     *                                         optimistic-lock conflict on save (HTTP&nbsp;409)
     */
    @Transactional
    public CardDto updateCard(String accountId, String cardNumber, CardDto request) {
        // PHASE 1 - Field edits 1210-1260, in COBOL order, first-error-wins (WS-RETURN-MSG-OFF guard).
        // Throws ValidationException (HTTP 400) BEFORE any DB access on the first failing edit.
        validateInputs(accountId, cardNumber, request);

        // The month/year passed their edits (numeric and in range), so these parses are safe and never
        // overflow; reuse the values for both the no-change comparison and the new expiration date.
        int newMonth = Integer.parseInt(request.getExpiryMonth().trim());
        int newYear = Integer.parseInt(request.getExpiryYear().trim());

        // PHASE 2 - Read CARDDAT keyed on the 16-char CARD NUMBER ONLY (COCRDUPC 9200 RIDFLD is the card
        // number; the account-id MOVE is commented out at L1424). The account is validated for format only
        // and is NOT a key or a post-read filter -- adding such a match would change behavior (§0.7.1).
        // VSAM READ ... UPDATE (lock) + REWRITE -> cardRepository.findById + saveAndFlush [COCRDUPC 9200]
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(MSG_CARDS_NOT_FOUND));

        // Capture the "OLD image" editable values for the no-change comparison and the day carry-over.
        String currentName = card.getCardEmbossedName();
        String currentStatus = card.getCardActiveStatus();
        LocalDate currentExpiry = card.getCardExpirationDate();
        // The expiry DAY is carried from the existing record (the new-day screen output is commented out
        // at COCRDUPC L1122). Guard a null/absent stored date by defaulting to the first of the month.
        int carriedDay = (currentExpiry != null) ? currentExpiry.getDayOfMonth() : DEFAULT_DAY_OF_MONTH;

        // PHASE 3 - NO-CHANGES-DETECTED (COCRDUPC L681): the COBOL compares UPPER(new) vs UPPER(old) over
        // the editable CARDDATA block. If nothing changed it performs no write.
        if (isNoChange(request, currentName, currentStatus, currentExpiry, newMonth, newYear)) {
            // NO-CHANGES-DETECTED -> skip save, return current; message is a controller/presentation concern.
            // Skipping the write preserves the COBOL "no REWRITE" behavior and avoids bumping @Version.
            // Not thrown: COBOL treats no-change as informational (see MSG_NO_CHANGE_DETECTED), not an error.
            return toDto(card);
        }

        // PHASE 3b - stale-form detection (COCRDUPC read-before-update / DATA-WAS-CHANGED-BEFORE-UPDATE):
        // the client echoes the version it saw when the card detail was displayed (request.getVersion());
        // if it no longer equals the current committed entity version, another user has since committed a
        // change and this form is stale -> reject with 409 BEFORE applying/writing. This closes the gap
        // server-side @Version alone cannot catch: @Version only detects a change committed AFTER this
        // transaction's read, not a client form loaded before an earlier completed update. Enforced only
        // when the client supplies a version (opt-in); when absent, the JPA @Version on saveAndFlush below
        // remains the safety net (AAP §0.7.5).
        if (request.getVersion() != null && !request.getVersion().equals(card.getVersion())) {
            throw ConcurrentModificationException.forEntity("Card", null);
        }

        // PHASE 4 - Apply ONLY the editable fields onto the managed entity.
        // CVV and expiry DAY are not user-editable -> carried from the existing record [COCRDUPC 9200 build].
        // The owning account id and the @Version column are likewise left untouched (minimal change, §0.7.1).
        card.setCardEmbossedName(request.getEmbossedName());   // CCUP-NEW-CRDNAME, stored as entered (un-uppercased)
        card.setCardActiveStatus(request.getActiveStatus());   // CCUP-NEW-CRDSTCD
        // COBOL: STRING(NEW-EXPYEAR '-' NEW-EXPMON '-' NEW-EXPDAY) INTO CARD-UPDATE-EXPIRAION-DATE
        // [COCRDUPC 9200 L1467-1474]. The entity stores a LocalDate (expiration_date DATE), so the
        // YYYY-MM-DD assembly is expressed directly with the validated month/year and the carried day.
        card.setCardExpirationDate(LocalDate.of(newYear, newMonth, carriedDay));

        // PHASE 5 - Persist with optimistic-lock translation. saveAndFlush forces the version-checked
        // UPDATE to run now (inside this try) rather than being deferred to commit after the method
        // returns -- a plain save() would defer the check and the catch below would never fire.
        // 9300 manual before/after image comparison -> JPA @Version optimistic locking on Card.version.
        try {
            Card saved = cardRepository.saveAndFlush(card);
            return toDto(saved);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            // OptimisticLock conflict (DATA-WAS-CHANGED / COULD-NOT-LOCK) -> ConcurrentModificationException (HTTP 409).
            // A concurrent modify OR delete both surface as an optimistic-lock failure here; the generic
            // LOCKED-BUT-UPDATE-FAILED outcome instead propagates as a DataAccessException -> HTTP 500.
            throw new ConcurrentModificationException(MSG_CONCURRENT_MODIFICATION, ex);
        }
        // CICS PF03 exit / PF05 confirm / PF12 cancel / SEND-RECEIVE MAP / XCTL -> controller concern.
    }

    /**
     * Reproduces the {@code COCRDUPC} input-edit cascade with exact COBOL semantics: the six field edits
     * run in the fixed order account &rarr; card &rarr; name &rarr; status &rarr; expiry month &rarr;
     * expiry year, and the COBOL {@code WS-RETURN-MSG-OFF} guard means only the <em>first</em> failing
     * edit's message is surfaced (<strong>first-error-wins</strong>). Each edit tests "not supplied"
     * (blank / {@code '*'} / all-zeros) before its format rule. A failure raises {@link ValidationException}
     * with the verbatim COBOL message and no read is performed.
     *
     * @param accountId  the account input ({@code CC-ACCT-ID}); required, must be all digits
     * @param cardNumber the card input ({@code CC-CARD-NUM}); required, must be all digits
     * @param request    the edited card payload supplying name, status and expiry month/year
     * @throws ValidationException with the verbatim COBOL message if an edit fails
     */
    private void validateInputs(String accountId, String cardNumber, CardDto request) {
        // Field edits 1210-1260 preserved verbatim; first-error-wins via WS-RETURN-MSG-OFF.

        // 1210-EDIT-ACCOUNT: required, then must be all digits (COBOL IS NUMERIC; the "11" is the field
        // size, not an extra length rule).
        if (isNotSupplied(accountId)) {
            throw new ValidationException(MSG_ACCOUNT_NOT_PROVIDED);
        }
        if (!isAllDigits(accountId)) {
            throw new ValidationException(MSG_ACCOUNT_FILTER_NOT_NUMERIC);
        }

        // 1220-EDIT-CARD: required, then must be all digits (the "16" is the field size, not a length rule).
        if (isNotSupplied(cardNumber)) {
            throw new ValidationException(MSG_CARD_NOT_PROVIDED);
        }
        if (!isAllDigits(cardNumber)) {
            throw new ValidationException(MSG_CARD_FILTER_NOT_NUMERIC);
        }

        // 1230-EDIT-NAME: required, then alphabets and spaces only (INSPECT CONVERTING A-Za-z -> spaces,
        // remaining TRIM length must be 0). A null/blank name is "not supplied".
        String name = request.getEmbossedName();
        if (isNotSupplied(name)) {
            throw new ValidationException(MSG_NAME_NOT_PROVIDED);
        }
        if (!ALPHA_SPACES.matcher(name).matches()) {
            throw new ValidationException(MSG_NAME_NOT_ALPHA);
        }

        // 1240-EDIT-CARDSTATUS: required, then exactly 'Y' or 'N' (FLG-YES-NO-VALID; uppercase only -- a
        // lowercase 'y'/'n' is rejected). Both the not-supplied and invalid cases share the same message.
        String status = request.getActiveStatus();
        if (isNotSupplied(status) || !("Y".equals(status) || "N".equals(status))) {
            throw new ValidationException(MSG_STATUS_NOT_YES_NO);
        }

        // 1250-EDIT-EXPIRY-MON: required, numeric, and 1..12 (VALID-MONTH). "3" and "03" both parse to 3.
        String month = request.getExpiryMonth();
        if (isNotSupplied(month) || !isAllDigits(month) || !inRange(month, MIN_MONTH, MAX_MONTH)) {
            throw new ValidationException(MSG_EXPIRY_MONTH_INVALID);
        }

        // 1260-EDIT-EXPIRY-YEAR: required, numeric, and 1950..2099 (VALID-YEAR).
        String year = request.getExpiryYear();
        if (isNotSupplied(year) || !isAllDigits(year) || !inRange(year, MIN_YEAR, MAX_YEAR)) {
            throw new ValidationException(MSG_EXPIRY_YEAR_INVALID);
        }
    }

    /**
     * Determines whether the submitted editable values match the fetched record, reproducing the COBOL
     * {@code NO-CHANGES-DETECTED} test ({@code UPPER-CASE(CCUP-NEW-CARDDATA) = UPPER-CASE(CCUP-OLD-CARDDATA)},
     * L681). The name is compared case-insensitively after trimming, the status case-insensitively, and the
     * month/year numerically (so {@code "03"} matches a stored month of {@code 3}). The day is carried from
     * the old image rather than edited, so it does not participate in this comparison.
     *
     * @param request       the edited payload (name/status/expiry)
     * @param currentName   the stored embossed name (may be {@code null})
     * @param currentStatus the stored active-status flag (may be {@code null})
     * @param currentExpiry the stored expiration date (may be {@code null})
     * @param newMonth      the validated new expiry month
     * @param newYear       the validated new expiry year
     * @return {@code true} when name, status, month and year all match the stored record
     */
    private boolean isNoChange(CardDto request, String currentName, String currentStatus,
            LocalDate currentExpiry, int newMonth, int newYear) {
        boolean nameSame = currentName != null
                && request.getEmbossedName().trim().equalsIgnoreCase(currentName.trim());
        boolean statusSame = currentStatus != null
                && request.getActiveStatus().equalsIgnoreCase(currentStatus);
        boolean monthSame = currentExpiry != null && newMonth == currentExpiry.getMonthValue();
        boolean yearSame = currentExpiry != null && newYear == currentExpiry.getYear();
        return nameSame && statusSame && monthSame && yearSame;
    }

    /**
     * Maps a {@link Card} entity to the single-card {@link CardDto}, reproducing the fields the
     * {@code COCRDUPC} record build painted on the {@code CCRDUPA} map. Identical in shape to the sibling
     * {@code CardDetailService} mapping: the card number, embossed name and active-status flag map straight
     * across; the owning account ({@code CARD-ACCT-ID PIC 9(11)}, a {@link Long}) is rendered as an 11-digit
     * zero-padded string; and the expiration date &mdash; stored as a {@link LocalDate} &mdash; is rendered
     * to its canonical {@code YYYY-MM-DD} text and split into year/month/day components, and also exposed
     * directly as the DTO's derived {@link LocalDate}. The CVV is sensitive and is intentionally not exposed.
     *
     * @param card the card record to map (never {@code null})
     * @return the populated single-card DTO
     */
    private CardDto toDto(Card card) {
        CardDto dto = new CardDto();

        // CARD-NUM PIC X(16) -> the keyed card number echoed on CCRDUPA.
        dto.setCardNumber(card.getCardNum());

        // CARD-ACCT-ID PIC 9(11) -> 11-digit zero-padded String to match the screen field width. The
        // column is NOT NULL in the schema, so the guard only protects against malformed in-memory data.
        Long owningAccount = card.getCardAcctId();
        if (owningAccount != null) {
            dto.setAccountId(String.format("%011d", owningAccount));
        }

        // CARD-EMBOSSED-NAME PIC X(50) -> CRDNAMEO. (Entity getter getCardEmbossedName(); DTO setEmbossedName().)
        dto.setEmbossedName(card.getCardEmbossedName());

        // CARD-ACTIVE-STATUS PIC X(01) -> CRDSTCDO. (DTO setActiveStatus().)
        dto.setActiveStatus(card.getCardActiveStatus());

        // CARD-EXPIRAION-DATE -> EXPYEARO/EXPMONO/EXPDAYO. The entity stores a LocalDate (expiration_date
        // DATE); render it to the canonical YYYY-MM-DD text and split positionally (year 1:4, month 6:2,
        // day 9:2) exactly as the COBOL FILLER redefinition / sibling CardDetailService do.
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

        // Carry the entity @Version so the redisplayed card holds the optimistic-lock token for any
        // subsequent edit, enabling COCRDUPC-style stale-form detection on the next update (AAP §0.7.5).
        dto.setVersion(card.getVersion());

        // CARD-CVV-CD is sensitive and, like the COCRDUP map, is intentionally not exposed.
        return dto;
    }

    /**
     * Determines whether an input is "not supplied" using {@code COCRDUPC} semantics: the COBOL
     * {@code RECEIVE-MAP} normalizes {@code '*'} or {@code SPACES} to {@code LOW-VALUES}, and the field
     * edits treat {@code LOW-VALUES}, {@code SPACES} and an all-zeros value ({@code &hellip; EQUAL ZEROS})
     * as not supplied.
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
            // SPACES / LOW-VALUES.
            return true;
        }
        if (ASTERISK_SENTINEL.equals(trimmed)) {
            // '*' normalized to LOW-VALUES by the COBOL RECEIVE-MAP.
            return true;
        }
        // ... EQUAL ZEROS -> treated as not supplied.
        return ZEROS.matcher(trimmed).matches();
    }

    /**
     * Determines whether a supplied input is all digits, mirroring the COBOL {@code IS NUMERIC} test on an
     * unsigned display field. The COBOL only checks digit composition (the 11/16 figures in the messages
     * are field sizes, not additional length constraints), so this performs no length check.
     *
     * @param value the input value (already known to be supplied)
     * @return {@code true} when every character (after trim) is a digit; {@code false} otherwise
     */
    private static boolean isAllDigits(String value) {
        return value != null && DIGITS.matcher(value.trim()).matches();
    }

    /**
     * Determines whether an all-digits value parses to an integer within the inclusive {@code [min, max]}
     * window, reproducing the COBOL {@code 88-level} range tests ({@code VALID-MONTH}, {@code VALID-YEAR}).
     * Only invoked after {@link #isAllDigits(String)}; a numeric string too large for {@code int} is treated
     * as outside the window (it cannot be a valid 1..12 month or 1950..2099 year).
     *
     * @param value the all-digit input value
     * @param min   inclusive lower bound
     * @param max   inclusive upper bound
     * @return {@code true} when {@code min <= parsed <= max}; {@code false} otherwise
     */
    private static boolean inRange(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException ex) {
            // A numeric string that overflows int is outside any valid month/year window.
            return false;
        }
    }
}
