package com.carddemo.service;

import java.util.List;
import io.micrometer.observation.annotation.Observed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardListResponse;
import com.carddemo.dto.PageResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Application service backing the <em>Credit Card List</em> screen (online
 * transaction {@code CCLI}). It is the idiomatic Spring translation of the COBOL
 * program {@code COCRDLIC} ({@code app/cbl/COCRDLIC.cbl}, 1&nbsp;459&nbsp;LOC)
 * captured at the frozen source commit SHA {@code 27d6c6f}.
 *
 * <p>{@code COCRDLIC} drives the BMS mapset {@code COCRDLI}, presenting the
 * {@code CARDDATA} VSAM cluster as a seven-row-per-page browse that may be
 * narrowed by an optional account-id filter and/or an optional card-number
 * filter. This service reproduces that behaviour as a stateless, read-only query
 * whose result is carried by {@link CardListResponse}; the CICS
 * pseudo-conversational paging state ({@code WS-CA-SCREEN-NUM},
 * {@code CA-NEXT-PAGE-EXISTS}) is externalised into the pagination metadata of
 * {@link PageResponse} rather than retained server-side.</p>
 *
 * <h2>Behavioural parity with COCRDLIC</h2>
 * <ul>
 *   <li><strong>Seven rows per page.</strong> {@code WS-MAX-SCREEN-LINES}
 *       ({@code PIC S9(4) COMP VALUE 7}) is reproduced by {@link #PAGE_SIZE},
 *       sourced from {@link CardListResponse#PAGE_SIZE} so the producing service,
 *       the response DTO and their tests share a single source of truth.</li>
 *   <li><strong>Key-ordered browse.</strong> Rows are read in ascending
 *       {@code CARD-NUM} order (the base KSDS key), reproduced with
 *       {@code Sort.by("cardNum")} so pagination is deterministic and matches the
 *       legacy sequential/keyset browse.</li>
 *   <li><strong>Filter edits, evaluated in source order.</strong> The COBOL
 *       driver {@code 2200-EDIT-INPUTS} performs {@code 2210-EDIT-ACCOUNT}
 *       <em>before</em> {@code 2220-EDIT-CARD}. The card message in
 *       {@code 2220-EDIT-CARD} is guarded by {@code IF WS-ERROR-MSG-OFF}, so a
 *       message already set by the account edit is never overwritten; when
 *       <em>both</em> filters are invalid the <em>account</em> message is the one
 *       reported. This service preserves that order and that precedence by
 *       validating the account filter first and failing fast
 *       ({@link #validate(Long, String)}).</li>
 *   <li><strong>Most-selective access path.</strong> When a card-number filter is
 *       supplied {@code COCRDLIC} positions directly to that card; the migration
 *       resolves it to a single-key lookup ({@code findById}) wrapped as a page.
 *       An account filter uses the {@code CARD-ACCT-ID} alternate index
 *       ({@code findByCardAcctId}); with no filter the whole cluster is browsed
 *       ({@code findAll}) — see {@link #applyFilters(Long, String, Pageable)}.</li>
 * </ul>
 *
 * <h2>Filter validation rules (verbatim messages)</h2>
 * <ul>
 *   <li>Account filter, when supplied, must be a positive number of at most
 *       eleven digits ({@code CARD-ACCT-ID PIC 9(11)}); otherwise
 *       {@link #MSG_ACCT_FILTER_INVALID} is raised.</li>
 *   <li>Card filter, when supplied, must be exactly sixteen digits
 *       ({@code CARD-NUM PIC X(16)} tested with {@code IS NUMERIC}); otherwise
 *       {@link #MSG_CARD_FILTER_INVALID} is raised.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>Card numbers are Primary Account Numbers (PANs). Every card number placed in
 * a {@link CardListItem} is masked to its last four digits by {@link #maskPan}
 * (and again, idempotently, by {@link CardListItem}); the full PAN is never
 * emitted in a list row and never written to the log. Diagnostic logging records
 * only whether a card filter was supplied, never its value.</p>
 *
 * <p>The service is stateless and its single collaborator ({@link CardRepository})
 * is supplied by constructor injection, so instances are immutable and safe for
 * concurrent use. The public entry point runs in a read-only transaction. Design
 * rationale is recorded in {@code docs/decision-log.md}; the paragraph-level
 * mapping is recorded in {@code docs/traceability-matrix.md}.</p>
 */
@Service
public class CardListService {

    /** SLF4J logger; never used to emit a full card number (PAN). */
    private static final Logger log = LoggerFactory.getLogger(CardListService.class);

    /**
     * Error message raised when a supplied account-id filter is not a valid
     * eleven-digit number. Transcribed verbatim from {@code COCRDLIC}
     * {@code 2210-EDIT-ACCOUNT} (SHA {@code 27d6c6f}).
     */
    public static final String MSG_ACCT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Error message raised when a supplied card-number filter is not a valid
     * sixteen-digit number. Transcribed verbatim from {@code COCRDLIC}
     * {@code 2220-EDIT-CARD} (SHA {@code 27d6c6f}).
     */
    public static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Number of card rows returned per page — the migration of
     * {@code WS-MAX-SCREEN-LINES VALUE 7}. Sourced from
     * {@link CardListResponse#PAGE_SIZE} (value {@code 7}) to keep a single source
     * of truth across the service, the response DTO and their tests.
     */
    public static final int PAGE_SIZE = CardListResponse.PAGE_SIZE;

    /**
     * Largest value representable by {@code CARD-ACCT-ID PIC 9(11)} — eleven nines.
     * A supplied account filter greater than this (or non-positive) is rejected.
     */
    private static final long MAX_ACCT_ID = 99_999_999_999L;

    /**
     * Regular expression matching exactly sixteen ASCII digits, reproducing the
     * COBOL {@code IS NUMERIC} test on the fixed sixteen-character
     * {@code CARD-NUM} filter field.
     */
    private static final String CARD_FILTER_PATTERN = "[0-9]{16}";

    /** Number of trailing PAN digits left visible when masking a card number. */
    private static final int PAN_VISIBLE_DIGITS = 4;

    /** Entity property used to order the browse (the base {@code CARD-NUM} key). */
    private static final String SORT_PROPERTY = "cardNum";

    /** Repository providing keyed and alternate-index access to {@link Card} rows. */
    private final CardRepository cardRepository;

    /**
     * Creates the service with its required {@link CardRepository} collaborator.
     *
     * @param cardRepository the card repository; injected by the container and
     *                       never {@code null}
     */
    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Lists credit cards a page at a time, optionally narrowed by account id
     * and/or card number, reproducing the {@code COCRDLIC} (CCLI) browse.
     *
     * <p>Processing mirrors the COBOL control flow: the input filters are edited
     * first ({@code 2200-EDIT-INPUTS}: account then card), then the most selective
     * access path is chosen ({@code 9500-FILTER-RECORDS}), then a fixed seven-row
     * window is materialised in ascending {@code CARD-NUM} order. Each retained
     * {@code CARD-RECORD} becomes a {@link CardListItem} whose card number is
     * masked to its last four digits.</p>
     *
     * @param accountIdFilter optional owning-account filter
     *                        ({@code CARD-ACCT-ID PIC 9(11)}); {@code null} means
     *                        "no account filter". When supplied it must be a
     *                        positive number of at most eleven digits
     * @param cardNumberFilter optional card-number filter
     *                         ({@code CARD-NUM PIC X(16)}); {@code null} or blank
     *                         means "no card filter". When supplied it must be
     *                         exactly sixteen digits
     * @param page             zero-based index of the requested page (Spring Data
     *                         convention); surfaced one-based in the response to
     *                         mirror the legacy {@code PAGENO} display
     * @return a {@link CardListResponse} echoing the applied filters and carrying
     *         the requested page of masked {@link CardListItem} rows with
     *         pagination metadata
     * @throws ValidationException if a supplied account filter is not a valid
     *                             eleven-digit number ({@link #MSG_ACCT_FILTER_INVALID})
     *                             or a supplied card filter is not a valid
     *                             sixteen-digit number ({@link #MSG_CARD_FILTER_INVALID});
     *                             HTTP {@code 400 Bad Request}
     */
    @Transactional(readOnly = true)
    @Observed(name = "carddemo.service", contextualName = "card-list")
    public CardListResponse listCards(Long accountIdFilter, String cardNumberFilter, int page) {
        // Never log the raw card-number filter (it may be a full PAN); record only
        // whether a filter was supplied, alongside the non-sensitive account id and page.
        log.debug("Card list request: accountIdFilter={}, cardFilterSupplied={}, page={}",
                accountIdFilter, isSupplied(cardNumberFilter), page);

        // Input edits — COCRDLIC 2200-EDIT-INPUTS: 2210-EDIT-ACCOUNT then 2220-EDIT-CARD.
        validate(accountIdFilter, cardNumberFilter);

        // Seven-row browse window ordered by the base CARD-NUM key (ascending KSDS/AIX browse).
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(SORT_PROPERTY));

        // Record filtering — COCRDLIC 9500-FILTER-RECORDS: choose the most selective access path.
        Page<Card> cards = applyFilters(accountIdFilter, cardNumberFilter, pageable);

        // Map each retained CARD-RECORD row to a masked list item, then attach pagination metadata.
        Page<CardListItem> items = cards.map(this::toItem);
        PageResponse<CardListItem> pageResponse = PageResponse.of(
                items.getContent(),
                items.getNumber() + 1, // Spring Data is zero-based; PageResponse/PAGENO is one-based.
                PAGE_SIZE,
                items.getTotalElements());

        log.debug("Card list result: page {} of {}, {} row(s) on page, {} total card(s)",
                pageResponse.pageNumber(), pageResponse.totalPages(),
                pageResponse.content().size(), pageResponse.totalElements());

        // Echo the submitted filters (COCRDLI ACCTSID/CARDSID). The account filter is converted
        // from Long to String to match the CardListResponse contract. The card-number filter is
        // passed as entered but masked to its last four digits by the CardListResponse canonical
        // constructor (D-023), so a caller filtering by a full PAN never sees it echoed back.
        String echoedAccountFilter = (accountIdFilter == null) ? null : String.valueOf(accountIdFilter);
        return new CardListResponse(echoedAccountFilter, cardNumberFilter, pageResponse);
    }

    /**
     * Edits the optional search filters, reproducing {@code COCRDLIC}
     * {@code 2200-EDIT-INPUTS}. The account filter ({@code 2210-EDIT-ACCOUNT}) is
     * checked <em>before</em> the card filter ({@code 2220-EDIT-CARD}) so that,
     * when both are invalid, the account message is the one raised — matching the
     * legacy {@code IF WS-ERROR-MSG-OFF} guard on the card edit, under which the
     * earlier account message is never overwritten.
     *
     * <p>A {@code null} account filter and a {@code null}/blank card filter both
     * mean "not supplied" (the COBOL blank test on spaces / low-values / zeros)
     * and pass without error.</p>
     *
     * @param accountIdFilter  the optional account filter; when non-{@code null}
     *                         it must be a positive number of at most eleven
     *                         digits ({@code CARD-ACCT-ID PIC 9(11)})
     * @param cardNumberFilter the optional card filter; when supplied it must be
     *                         exactly sixteen digits ({@code CARD-NUM PIC X(16)})
     * @throws ValidationException {@link #MSG_ACCT_FILTER_INVALID} for a bad
     *                             account filter, or {@link #MSG_CARD_FILTER_INVALID}
     *                             for a bad card filter
     */
    private void validate(Long accountIdFilter, String cardNumberFilter) {
        // 2210-EDIT-ACCOUNT (evaluated first): positive, at most eleven digits.
        if (accountIdFilter != null && (accountIdFilter <= 0L || accountIdFilter > MAX_ACCT_ID)) {
            throw new ValidationException(MSG_ACCT_FILTER_INVALID);
        }
        // 2220-EDIT-CARD (evaluated second): exactly sixteen digits when supplied.
        if (isSupplied(cardNumberFilter) && !cardNumberFilter.strip().matches(CARD_FILTER_PATTERN)) {
            throw new ValidationException(MSG_CARD_FILTER_INVALID);
        }
    }

    /**
     * Selects the retained set of cards for the requested window, reproducing the
     * record-filtering step {@code COCRDLIC} {@code 9500-FILTER-RECORDS} by
     * choosing the most selective access path over the {@code CARDDATA} cluster.
     *
     * <p>The filters have already been validated by
     * {@link #validate(Long, String)}, so any supplied card filter is a
     * well-formed sixteen-digit key here.</p>
     *
     * <ol>
     *   <li>A supplied card-number filter positions directly to a single card via
     *       the base KSDS key ({@code findById}); the at-most-one hit is wrapped
     *       as a {@link Page} so the caller maps every path uniformly.</li>
     *   <li>Otherwise a supplied account filter reads through the non-unique
     *       {@code CARD-ACCT-ID} alternate index
     *       ({@link CardRepository#findByCardAcctId(Long, Pageable)}).</li>
     *   <li>Otherwise the whole cluster is browsed
     *       ({@link CardRepository#findAll(Pageable)}).</li>
     * </ol>
     *
     * @param accountIdFilter  the optional account filter (may be {@code null})
     * @param cardNumberFilter the optional card filter (may be {@code null}/blank)
     * @param pageable         the seven-row, card-number-ordered page request
     * @return the page of matching {@link Card} rows; never {@code null} (empty
     *         when nothing matches the requested window)
     */
    private Page<Card> applyFilters(Long accountIdFilter, String cardNumberFilter, Pageable pageable) {
        if (isSupplied(cardNumberFilter)) {
            String cardNum = cardNumberFilter.strip();
            Card found = cardRepository.findById(cardNum).orElse(null);
            List<Card> content = (found == null) ? List.of() : List.of(found);
            return new PageImpl<>(content, pageable, content.size());
        }
        if (accountIdFilter != null) {
            return cardRepository.findByCardAcctId(accountIdFilter, pageable);
        }
        return cardRepository.findAll(pageable);
    }

    /**
     * Maps one persistent {@link Card} to a masked {@link CardListItem} row,
     * reproducing the per-row population of the {@code COCRDLI} symbolic map
     * ({@code ACCTNOn}/{@code CRDNUMn}/{@code CRDSTSn}).
     *
     * <p>The owning account id ({@code CARD-ACCT-ID PIC 9(11)}) is rendered as
     * text to preserve fixed-width identity; the (nullable) column is guarded so a
     * missing value surfaces as {@code null} rather than the literal string
     * {@code "null"}. The card number is masked to its last four digits before it
     * leaves the service.</p>
     *
     * @param card the source card entity; never {@code null}
     * @return the masked, immutable list-row DTO
     */
    private CardListItem toItem(Card card) {
        String accountId = (card.getCardAcctId() == null) ? null : String.valueOf(card.getCardAcctId());
        return new CardListItem(accountId, maskPan(card.getCardNum()), card.getCardActiveStatus());
    }

    /**
     * Masks a card number (PAN) so that only its last four digits remain visible,
     * replacing every earlier character with {@code '*'}. Fixed-width padding is
     * stripped first. The method is null-safe and short-safe: {@code null} is
     * returned unchanged, and a value of four or fewer characters is returned
     * unchanged (there is nothing further to conceal, and no digits are invented).
     * Masking is idempotent, so an already-masked value passes through unchanged.
     *
     * @param pan the card number to mask; may be {@code null}
     * @return the masked card number (for example {@code ************3456}), or the
     *         original value when there is nothing to mask
     */
    private String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        String normalized = pan.strip();
        int length = normalized.length();
        if (length <= PAN_VISIBLE_DIGITS) {
            return normalized;
        }
        return "*".repeat(length - PAN_VISIBLE_DIGITS)
                + normalized.substring(length - PAN_VISIBLE_DIGITS);
    }

    /**
     * Determines whether an optional text filter was supplied, treating
     * {@code null} and blank (whitespace-only) values as "not supplied" — the
     * migration of the COBOL blank test on {@code SPACES} / {@code LOW-VALUES}.
     *
     * @param filter the candidate filter value; may be {@code null}
     * @return {@code true} if the filter carries non-whitespace text
     */
    private static boolean isSupplied(String filter) {
        return filter != null && !filter.isBlank();
    }
}
