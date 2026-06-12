package com.cardemo.service.card;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card-list service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COCRDLIC.cbl}</strong> (CICS transaction {@code CCLI}, BMS map
 * {@code CCRDLIA} / mapset {@code COCRDLI}). The program performs a <strong>paginated forward/backward
 * browse of the {@code CARDDAT} VSAM file, exactly seven rows per page</strong>, optionally narrowed by
 * an account-number filter and/or a card-number filter, painting each page of rows onto the 3270 list
 * screen.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COCRDLIC}'s observable browse behavior <em>exactly</em> &mdash; the
 * same page size, the same ascending card-number browse order, the same optional-filter semantics and
 * the same verbatim edit messages. Per the Minimal Change Clause (AAP &sect;0.7.1) nothing is added,
 * enhanced or optimized beyond the technology transition. The COBOL is read-only reference material at
 * the frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its behavior is reproduced.</p>
 *
 * <h2>The single most important parity constant &mdash; page size is EXACTLY seven</h2>
 * <p>The legacy screen painted a fixed {@code OCCURS 7 TIMES} row array; the working-storage constant
 * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} caps the browse at seven rows per page
 * ({@code COCRDLIC} L177-178, L255, L76). That contract is encoded once as {@link CardDto#ROWS_PER_PAGE}
 * (seven) and supplied to every query through the {@link Pageable} this service builds. No other page
 * size is ever used.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM browse {@code STARTBR(GTEQ)}+{@code READNEXT} loop &rarr; Spring Data pagination.</strong>
 *       The {@code 9000-READ-FORWARD} paragraph issued {@code EXEC CICS STARTBR DATASET('CARDDAT')
 *       RIDFLD(card-number) GTEQ} then looped {@code READNEXT} accumulating seven rows in ascending
 *       card-number order. That becomes
 *       {@code PageRequest.of(pageNumber - 1, 7, Sort.by("cardNum").ascending())} handed to a Spring
 *       Data repository query. The {@code 9100-READ-BACKWARDS} {@code READPREV} path (PF7 page-up)
 *       becomes a {@code page - 1} request issued by the controller, not by this service.</li>
 *   <li><strong>Record filtering ({@code 9500-FILTER-RECORDS}) &rarr; repository method selection.</strong>
 *       The COBOL excluded a browsed record when an active account filter did not equal
 *       {@code CARD-ACCT-ID}, or an active card filter did not equal {@code CARD-NUM} (the two filters
 *       are ANDed). Because the card number is the unique primary key, the most faithful translation
 *       chooses among {@link CardRepository#findById(Object) findById} (card filter present, &le;1 row),
 *       {@link CardRepository#findByCardAcctId(Long, Pageable) findByCardAcctId} (account filter only)
 *       and {@link CardRepository#findAll(Pageable) findAll} (no filter).</li>
 *   <li><strong>Input edits ({@code 2210}/{@code 2220}) &rarr; {@link ValidationException}.</strong>
 *       A supplied-but-non-numeric account or card filter raises a {@link ValidationException}
 *       (HTTP&nbsp;400) carrying the verbatim COBOL message. Unlike the card-detail program, blank
 *       filters are <em>not</em> an error here &mdash; they simply mean "no filtering".</li>
 *   <li><strong>{@code CA-NEXT-PAGE-EXISTS} (the extra {@code READNEXT} probe) &rarr;
 *       {@code Page.hasNext()}.</strong> After accumulating the seventh row the COBOL issued one more
 *       {@code READNEXT} purely to learn whether a next page exists, driving PF8 enablement. The Spring
 *       Data {@link Page#hasNext()} flag is the exact analog; PF8/next-page enablement is derived by the
 *       controller (which simply requests the next page), so this service returns the rows and the
 *       page number without a dedicated "has next" field.</li>
 *   <li><strong>CICS presentation &amp; navigation intentionally omitted.</strong> PF-key handling
 *       (PF03 exit, PF07 page-up, PF08 page-down), {@code SEND MAP}/{@code RECEIVE MAP}, screen
 *       attributes, COMMAREA plumbing and {@code XCTL} to the detail ({@code COCRDSLC}) or update
 *       ({@code COCRDUPC}) programs on a row {@code 'S'}/{@code 'U'} selection are a controller concern
 *       handled by {@code controller/CardController}, never by this service.</li>
 * </ul>
 *
 * <h2>Selection-array edit (2250) and the "no records" message are controller concerns</h2>
 * <p>The COBOL {@code 2250-EDIT-ARRAY} paragraph validated the per-row action codes the operator typed
 * on the displayed list ({@code 'S'} view / {@code 'U'} update), surfacing
 * {@code "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE"} for more than one selection and
 * {@code "INVALID ACTION CODE"} for an unrecognised code. In REST those row selections become separate
 * detail/update endpoint calls, so that parsing is the controller's responsibility and is deliberately
 * not implemented here. Likewise, when the first page comes back empty the COBOL set
 * {@code WS-NO-RECORDS-FOUND} ({@value #NO_RECORDS_FOUND_MESSAGE}); this service returns a
 * {@link CardDto} with an empty {@code cards} list (it does <em>not</em> throw, because the COBOL
 * treats this as an informational state, not an error) and the controller surfaces the message via
 * {@link #NO_RECORDS_FOUND_MESSAGE}.</p>
 *
 * <p>The program performs only browse operations ({@code STARTBR}/{@code READNEXT}/{@code READPREV}/
 * {@code ENDBR}), so this service is annotated {@link Transactional @Transactional(readOnly = true)} at
 * the class level and carries no mutating behavior.</p>
 *
 * @see CardRepository
 * @see Card
 * @see CardDto
 * @see CardDto.CardListItem
 * @see ValidationException
 */
@Service
@Transactional(readOnly = true)
public class CardListService {

    // -----------------------------------------------------------------------------------------------
    // Browse contract constants.
    // -----------------------------------------------------------------------------------------------

    /**
     * The fixed browse page size &mdash; <strong>exactly seven rows</strong>.
     *
     * <p>Bound to {@link CardDto#ROWS_PER_PAGE} (the single source of truth for the seven-rows-per-page
     * contract) so the value cannot silently drift. It is the Java mapping of the COBOL working-storage
     * constant {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} ({@code COCRDLIC} L177-178), which
     * capped the {@code OCCURS 7 TIMES} row array. This page size is non-negotiable for parity.</p>
     */
    // WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 -> fixed page size of 7 rows [COCRDLIC L177]
    private static final int PAGE_SIZE = CardDto.ROWS_PER_PAGE;

    /**
     * The {@link Card} entity property the browse is ordered by.
     *
     * <p>The COBOL {@code STARTBR ... GTEQ} positioned the browse on the {@code CARDDAT} cluster key,
     * the sixteen-character card number, and {@code READNEXT} then walked the file in ascending key
     * order. The entity property {@code cardNum} (column {@code card_number}) is that key, so a
     * {@code Sort.by("cardNum").ascending()} reproduces the {@code STARTBR(GTEQ)}+{@code READNEXT}
     * traversal order exactly.</p>
     */
    // STARTBR ... GTEQ on the 16-char card-number cluster key -> Sort by Card.cardNum ASC [COCRDLIC 9000]
    private static final String SORT_PROPERTY = "cardNum";

    // -----------------------------------------------------------------------------------------------
    // Verbatim COCRDLIC messages. Copied EXACTLY, including the comma-without-following-space and the
    // field-size digit counts (11 / 16), for 100% message parity (AAP §0.7.2).
    // -----------------------------------------------------------------------------------------------

    /** Account not-numeric edit literal ({@code COCRDLIC} 2210, L1022) &mdash; verbatim (comma, no following space). */
    private static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Card not-numeric edit literal ({@code COCRDLIC} 2220, L1058) &mdash; verbatim (comma, no following space). */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The verbatim {@code WS-NO-RECORDS-FOUND} text ({@code COCRDLIC} L122), exposed so the controller
     * can surface it when the first page of a search comes back empty.
     *
     * <p>This is a <em>presentation</em> string: the confirmed {@link CardDto} carries no message field,
     * and the COBOL treated the empty first page as an informational state rather than an error, so this
     * service never throws for it. It is published here (rather than buried in a comment) precisely so
     * {@code controller/CardController} can render it verbatim when {@link CardDto#getCards()} is empty on
     * page one.</p>
     */
    // NO RECORDS FOUND FOR THIS SEARCH CONDITION. -> empty cards list; message surfaced by controller [COCRDLIC L122]
    public static final String NO_RECORDS_FOUND_MESSAGE = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    // -----------------------------------------------------------------------------------------------
    // Filter-normalization helpers. The COBOL 2210/2220 edits treat LOW-VALUES, SPACES, an all-zeros
    // numeric value and the '*' sentinel as "not supplied" (filter inactive), and the "IS NUMERIC" test
    // only checks digit composition (the 11/16 in the messages are field sizes, not extra length rules).
    // -----------------------------------------------------------------------------------------------

    /** Matches an all-digits value (COBOL {@code IS NUMERIC} on an unsigned display field). */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Matches an all-zeros value ({@code CC-*-N EQUAL ZEROS} &rarr; treated as not supplied). */
    private static final Pattern ZEROS = Pattern.compile("0+");

    /** Single asterisk sentinel that {@code COCRDLIC} normalizes to {@code LOW-VALUES} (filter inactive). */
    private static final String ASTERISK_SENTINEL = "*";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    private final CardRepository cardRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param cardRepository repository for the {@code CARDDAT} VSAM replacement; supplies the
     *                       account-filtered paginated browse
     *                       ({@link CardRepository#findByCardAcctId(Long, Pageable)}), the keyed
     *                       single-card lookup ({@link CardRepository#findById(Object)} for the
     *                       unique-key card filter) and the unfiltered paginated browse
     *                       ({@link CardRepository#findAll(Pageable)}) that together reproduce the
     *                       {@code COCRDLIC} {@code STARTBR}/{@code READNEXT} loop
     */
    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns one page of the card-list browse, reproducing {@code COCRDLIC} end to end.
     *
     * <p>Processing order mirrors the COBOL dispatch:</p>
     * <ol>
     *   <li><strong>Edit the optional filters</strong> ({@code 2210-EDIT-ACCOUNT}, then
     *       {@code 2220-EDIT-CARD}). A supplied-but-non-numeric filter raises a
     *       {@link ValidationException} <em>before</em> any database access; a blank filter is simply
     *       inactive (no error). The account edit runs first, so an account message takes precedence over
     *       a card message (the COBOL {@code WS-ERROR-MSG-OFF} guard &mdash; first-error-wins).</li>
     *   <li><strong>Select and run the browse query</strong> ({@code 9000-READ-FORWARD} +
     *       {@code 9500-FILTER-RECORDS}), choosing the repository method that faithfully reproduces the
     *       filtered {@code STARTBR}/{@code READNEXT} loop for the active filter combination, capped at
     *       {@link #PAGE_SIZE} (seven) rows in ascending card-number order.</li>
     *   <li><strong>Map the page rows</strong> to the response {@link CardDto} (with its nested
     *       {@link CardDto.CardListItem} rows) and stamp the one-based page number.</li>
     * </ol>
     *
     * <p>Forward/backward paging (PF8/PF7) is a controller concern: the controller requests
     * {@code pageNumber + 1} / {@code pageNumber - 1}; this method simply returns the requested page. An
     * empty first page is returned as a {@link CardDto} with an empty {@code cards} list (no exception) so
     * the controller can surface {@link #NO_RECORDS_FOUND_MESSAGE}.</p>
     *
     * @param accountIdFilter the optional account-number filter ({@code COCRDLIC CC-ACCT-ID}); when blank
     *                        (null, spaces, all zeros or the {@code '*'} sentinel) the browse is not
     *                        narrowed by account; when supplied it must be all digits
     * @param cardNumberFilter the optional card-number filter ({@code COCRDLIC CC-CARD-NUM}); when blank
     *                         the browse is not narrowed by card; when supplied it must be all digits and,
     *                         being the unique key, matches at most one card
     * @param pageNumber      the <strong>one-based</strong> page index to return ({@code COCRDLIC
     *                        WS-CA-SCREEN-NUM}, first page = 1); any value below one is normalized to one
     * @return the populated {@link CardDto} whose {@code cards} list holds up to {@link #PAGE_SIZE} rows
     *         for the requested page (possibly empty), with the one-based {@code pageNumber} set
     * @throws ValidationException if the account filter, or then the card filter, is supplied but not
     *                             all digits (HTTP&nbsp;400), with the verbatim COBOL message
     */
    public CardDto listCards(String accountIdFilter, String cardNumberFilter, int pageNumber) {
        // Page numbering convention: pageNumber is 1-based (COBOL WS-CA-SCREEN-NUM, first page = 1).
        // Normalize < 1 to 1, then convert to Spring Data's 0-based page index.
        int normalizedPageNumber = Math.max(pageNumber, 1);
        int zeroBasedPage = normalizedPageNumber - 1;

        // VSAM browse STARTBR(GTEQ)+READNEXT loop, 7 rows (WS-MAX-SCREEN-LINES)
        // -> PageRequest.of(page-1, 7, Sort by cardNum ASC) [COCRDLIC 9000-READ-FORWARD].
        // READPREV backward paging -> page-1 at controller [COCRDLIC 9100-READ-BACKWARDS].
        Pageable pageable = PageRequest.of(zeroBasedPage, PAGE_SIZE, Sort.by(SORT_PROPERTY).ascending());

        // 2210/2220: normalize the optional filters (blank/'*'/all-zeros -> inactive) and edit them.
        // The account edit runs FIRST so its message wins over the card message (WS-ERROR-MSG-OFF guard).
        String accountFilter = normalizeFilter(accountIdFilter);
        String cardFilter = normalizeFilter(cardNumberFilter);

        // 2210-EDIT-ACCOUNT: a supplied account filter must be all digits; parse it to its Long key value.
        Long accountKey = null;
        if (accountFilter != null) {
            accountKey = parseAccountFilter(accountFilter);
        }

        // 2220-EDIT-CARD: a supplied card filter must be all digits (account error already took precedence).
        if (cardFilter != null && !isAllDigits(cardFilter)) {
            throw new ValidationException(MSG_CARD_FILTER_NOT_NUMERIC);
        }

        // 9000-READ-FORWARD + 9500-FILTER-RECORDS (acct AND card filters)
        // -> findById / findByCardAcctId / findAll selection.
        List<Card> rows = selectRows(accountKey, cardFilter, zeroBasedPage, pageable);

        // Map the (<= 7) browsed rows onto the DTO and stamp the 1-based page number.
        return buildDto(rows, normalizedPageNumber);
    }

    /**
     * Selects the page of rows for the active filter combination, reproducing {@code 9500-FILTER-RECORDS}
     * (the account and card filters are ANDed) with the most faithful repository call available.
     *
     * <p>Because the card number is the unique primary key, any active card filter matches at most one
     * record, so the card-filter branches use {@link CardRepository#findById(Object)} and yield their
     * single row on the first page only (a unique key has no further records to browse on later pages,
     * matching the COBOL behavior where the forward browse would find nothing more). The account-only and
     * unfiltered branches use the paginated repository queries and honor the {@link Pageable}'s seven-row
     * page size and ascending card-number sort.</p>
     *
     * <p>For the paginated branches, {@code Page.hasNext()} is the parity equivalent of the COBOL
     * {@code CA-NEXT-PAGE-EXISTS} flag (the extra {@code READNEXT} probe after the seventh row,
     * {@code COCRDLIC} 9000); next-page (PF8) enablement is derived by the controller, so it is documented
     * here rather than carried on the DTO.</p>
     *
     * @param accountKey   the parsed account-filter key, or {@code null} when no account filter is active
     * @param cardFilter   the normalized card-filter value, or {@code null} when no card filter is active
     * @param zeroBasedPage the zero-based page index requested
     * @param pageable     the paging/sorting specification (seven rows, card-number ascending)
     * @return the rows for the requested page (at most {@link #PAGE_SIZE}), never {@code null}
     */
    private List<Card> selectRows(Long accountKey, String cardFilter, int zeroBasedPage, Pageable pageable) {
        boolean accountActive = accountKey != null;
        boolean cardActive = cardFilter != null;

        if (cardActive) {
            // Card filter active (alone or ANDed with an account filter): the unique-key lookup yields
            // at most one record, which belongs on the first page only. CA-NEXT-PAGE-EXISTS -> false
            // (a unique key has no further records to browse). [COCRDLIC 9500-FILTER-RECORDS]
            List<Card> rows = new ArrayList<>();
            if (zeroBasedPage == 0) {
                Optional<Card> found = cardRepository.findById(cardFilter);
                if (found.isPresent()) {
                    Card card = found.get();
                    // 9500: when an account filter is ALSO active, both must match (filters are ANDed);
                    // with only a card filter, a present record always qualifies.
                    if (!accountActive || accountKey.equals(card.getCardAcctId())) {
                        rows.add(card);
                    }
                }
            }
            // zeroBasedPage > 0 with a unique-key card filter -> no further records -> empty page.
            return rows;
        }

        if (accountActive) {
            // Account filter only -> CARDAIX alternate-index paginated lookup. CA-NEXT-PAGE-EXISTS
            // (extra READNEXT probe) -> page.hasNext() [COCRDLIC 9000], derived by the controller.
            Page<Card> page = cardRepository.findByCardAcctId(accountKey, pageable);
            return page.getContent();
        }

        // No filter -> full-file paginated browse. CA-NEXT-PAGE-EXISTS -> page.hasNext() [COCRDLIC 9000].
        Page<Card> page = cardRepository.findAll(pageable);
        return page.getContent();
    }

    /**
     * Builds the response {@link CardDto} from the page rows, reproducing the row mapping the COBOL
     * {@code 9000-READ-FORWARD} loop performed when populating the {@code OCCURS 7 TIMES} screen array.
     *
     * @param rows               the browsed rows (at most {@link #PAGE_SIZE}), in browse order
     * @param normalizedPageNumber the one-based page number to stamp on the DTO
     * @return the populated {@link CardDto}
     */
    private CardDto buildDto(List<Card> rows, int normalizedPageNumber) {
        CardDto dto = new CardDto();

        List<CardDto.CardListItem> items = new ArrayList<>();
        for (Card card : rows) {
            items.add(toItem(card));
        }
        dto.setCards(items);

        // PAGENO PIC X(3) -> the DTO page number is a String; set the 1-based value as text.
        dto.setPageNumber(String.valueOf(normalizedPageNumber));

        // CICS XCTL row-select navigation (S/U) & 2250 selection edits -> controller concern.
        // An empty `items` on page 1 is the WS-NO-RECORDS-FOUND state -> the controller surfaces
        // NO_RECORDS_FOUND_MESSAGE; this service does not throw for it.
        return dto;
    }

    /**
     * Maps one browsed {@link Card} record to a {@link CardDto.CardListItem}, reproducing the COBOL row
     * {@code MOVE}s in {@code 9000-READ-FORWARD} (L1167-1175).
     *
     * <ul>
     *   <li>{@code WS-EDIT-SELECT} (the per-row {@code CRDSEL} select field) is an input field, blank on
     *       display, so the selection flag is set to the empty string.</li>
     *   <li>{@code CARD-ACCT-ID PIC 9(11)} (a {@link Long} on the entity) is rendered as an 11-digit
     *       zero-padded string to match the screen field width ({@code WS-ROW-ACCTNO PIC X(11)}).</li>
     *   <li>{@code CARD-NUM PIC X(16)} maps straight across to the row card number
     *       ({@code WS-ROW-CARD-NUM PIC X(16)}).</li>
     *   <li>{@code CARD-ACTIVE-STATUS PIC X(01)} maps straight across to the row status
     *       ({@code WS-ROW-CARD-STATUS PIC X(1)}); the DTO setter is {@code setActiveStatus}.</li>
     * </ul>
     *
     * @param card the browsed card record (never {@code null})
     * @return the populated browse row
     */
    private CardDto.CardListItem toItem(Card card) {
        CardDto.CardListItem item = new CardDto.CardListItem();

        // WS-EDIT-SELECT (CRDSEL{n}) is a blank input field on display [COCRDLIC L972-978].
        item.setSelectionFlag("");

        // CARD-ACCT-ID PIC 9(11) -> WS-ROW-ACCTNO PIC X(11): 11-digit zero-padded String to match the
        // screen field width. account_id is NOT NULL in the schema, so the guard only protects against
        // malformed in-memory test data.
        Long owningAccount = card.getCardAcctId();
        item.setAccountId(owningAccount == null ? null : String.format("%011d", owningAccount));

        // CARD-NUM PIC X(16) -> WS-ROW-CARD-NUM PIC X(16).
        item.setCardNumber(card.getCardNum());

        // CARD-ACTIVE-STATUS PIC X(01) -> WS-ROW-CARD-STATUS PIC X(1) (DTO setter is setActiveStatus()).
        item.setActiveStatus(card.getCardActiveStatus());

        return item;
    }

    /**
     * Normalizes a raw filter input to its active value, or {@code null} when the filter is "not supplied"
     * per {@code COCRDLIC} 2210/2220 semantics: the COBOL treats {@code LOW-VALUES}, {@code SPACES}, an
     * all-zeros numeric value and the {@code '*'} sentinel as an inactive filter. Unlike the card-detail
     * program, an inactive filter here is <strong>not</strong> an error &mdash; it simply means "no
     * filtering".
     *
     * @param value the raw filter input
     * @return the trimmed filter value when active, or {@code null} when the filter is not supplied
     */
    private static String normalizeFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            // SPACES / LOW-VALUES -> filter inactive.
            return null;
        }
        if (ASTERISK_SENTINEL.equals(trimmed)) {
            // '*' normalized to LOW-VALUES -> filter inactive.
            return null;
        }
        if (ZEROS.matcher(trimmed).matches()) {
            // CC-*-N EQUAL ZEROS -> filter inactive.
            return null;
        }
        return trimmed;
    }

    /**
     * Edits and parses a supplied account filter ({@code 2210-EDIT-ACCOUNT}): a supplied account filter
     * must be all digits, otherwise the verbatim {@link #MSG_ACCOUNT_FILTER_NOT_NUMERIC} is raised. The
     * COBOL only checks digit composition (the {@code 11} in the message is the field size, not an extra
     * length rule); the controller's {@code @Pattern(\d{1,11})} already bounds production input to eleven
     * digits, so the overflow guard below is a defensive fallback for direct (e.g. test) invocations.
     *
     * @param accountFilter the normalized (supplied, non-blank) account filter
     * @return the account key as a {@link Long}
     * @throws ValidationException if the filter is not all digits, or is an all-digit value too large to
     *                             be a valid eleven-digit account number
     */
    private Long parseAccountFilter(String accountFilter) {
        if (!isAllDigits(accountFilter)) {
            throw new ValidationException(MSG_ACCOUNT_FILTER_NOT_NUMERIC);
        }
        try {
            return Long.valueOf(accountFilter);
        } catch (NumberFormatException ex) {
            // All-digit but beyond Long range: cannot be a valid 11-digit account number. Defensive only;
            // the controller @Pattern(\d{1,11}) bounds production input to 11 digits (always Long-safe).
            throw new ValidationException(MSG_ACCOUNT_FILTER_NOT_NUMERIC, ex);
        }
    }

    /**
     * Determines whether a supplied filter is all digits, mirroring the COBOL {@code IS NUMERIC} test on
     * an unsigned display field. The COBOL only checks digit composition (the 11/16 figures in the
     * messages are field sizes, not additional length constraints), so this performs no length check.
     *
     * @param value the filter value (already normalized to a supplied, non-blank value)
     * @return {@code true} when every character is a digit; {@code false} otherwise
     */
    private static boolean isAllDigits(String value) {
        return value != null && DIGITS.matcher(value).matches();
    }

}
