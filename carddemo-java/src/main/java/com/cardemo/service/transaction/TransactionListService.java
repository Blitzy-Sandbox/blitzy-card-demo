package com.cardemo.service.transaction;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionDto.TransactionListItem;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-list service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COTRN00C.cbl}</strong> (CICS transaction {@code CT00}, BMS map
 * {@code COTRN0A} / mapset {@code COTRN00}). The program performs a <strong>paginated forward/backward
 * browse of the {@code TRANSACT} VSAM KSDS, exactly ten rows per page</strong>, optionally positioned by
 * a starting transaction-id filter, painting each page of rows onto the 3270 list screen.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COTRN00C}'s observable browse behavior <em>exactly</em> &mdash; the
 * same ten-rows-per-page size, the same ascending transaction-id browse order, the same
 * starting-id filter semantics and the same verbatim edit/boundary messages. Per the Minimal Change
 * Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology transition. The
 * COBOL is read-only reference material at the frozen baseline commit SHA {@code 27d6c6f} and is never
 * copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>The single most important parity constant &mdash; page size is EXACTLY ten</h2>
 * <p>The legacy program filled a page with {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10}
 * and {@code PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF} ({@code COTRN00C} L290, L297), painting the ten
 * symbolic rows {@code TRNID01}&hellip;{@code TRNID10}. That contract is encoded once as
 * {@link TransactionDto#ROWS_PER_PAGE} (ten) and supplied to every query through the
 * {@link org.springframework.data.domain.Pageable} this service builds. No other page size is ever
 * used.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM {@code STARTBR(GTEQ)}/{@code READNEXT}/{@code READPREV} keyset browse &rarr; Spring
 *       Data offset pagination.</strong> {@code PROCESS-PAGE-FORWARD}/{@code PROCESS-PAGE-BACKWARD}
 *       positioned the {@code TRANSACT} browse with {@code EXEC CICS STARTBR RIDFLD(TRAN-ID)}
 *       (greater-than-or-equal) and looped {@code READNEXT} to accumulate ten rows in ascending key
 *       order. That becomes {@link TransactionRepository#findByTranIdGreaterThanEqual(String,
 *       org.springframework.data.domain.Pageable)} with
 *       {@code PageRequest.of(pageIndex, 10, Sort.by("tranId").ascending())}. The page index is 0-based
 *       (COBOL screen page&nbsp;1 = index&nbsp;0).</li>
 *   <li><strong>{@code LOW-VALUES} start key &rarr; {@code "0000000000000000"}.</strong> A blank
 *       starting-id filter maps to {@code MOVE LOW-VALUES TO TRAN-ID} ({@code COTRN00C} L206-207);
 *       because {@code TRAN-ID PIC X(16)} stores a 16-character zero-padded numeric key, the lowest
 *       possible key (the {@code LOW-VALUES} analog) is sixteen ASCII zeros.</li>
 *   <li><strong>{@code TRAN-ORIG-TS} (PIC X(26) text) &rarr; {@link LocalDateTime} formatted
 *       {@code MM/dd/yy}.</strong> {@code POPULATE-TRAN-DATA} derived the {@code MM/DD/YY} row date by
 *       reference-modifying the 26-character timestamp text ({@code COTRN00C} L383-388). The migrated
 *       {@link Transaction} entity stores that timestamp as a {@link LocalDateTime}, so the same value is
 *       rendered with a {@code MM/dd/yy} {@link DateTimeFormatter}; the painted string is identical.</li>
 *   <li><strong>{@code MOVE TRAN-DESC TO TDESC0n} truncation.</strong> The 100-character
 *       {@code TRAN-DESC} is moved into the 26-character row-description field, which truncates to the
 *       leftmost 26 characters &mdash; reproduced here explicitly.</li>
 *   <li><strong>{@code NEXT-PAGE-FLG} (the extra {@code READNEXT} probe) &rarr; {@link Page#hasNext()}.</strong>
 *       After filling a page, {@code PROCESS-PAGE-FORWARD} issued one more {@code READNEXT} purely to
 *       learn whether a further record exists ({@code COTRN00C} L308-313), setting
 *       {@code NEXT-PAGE-YES}/{@code NEXT-PAGE-NO} to drive PF8. Spring Data's {@link Page#hasNext()} is
 *       the exact analog; it is exposed through {@link #hasNextPage(TransactionDto)} /
 *       {@link #hasPreviousPage(TransactionDto)} so the controller can enable the forward/backward action
 *       and surface the boundary messages.</li>
 *   <li><strong>CICS presentation &amp; navigation intentionally omitted.</strong> PF-key handling
 *       (PF03 exit to {@code COMEN01C}, PF07 page-up, PF08 page-down), {@code SEND MAP}/{@code RECEIVE
 *       MAP}, screen attributes, COMMAREA plumbing and the {@code XCTL} to the detail program
 *       {@code COTRN01C} on a row {@code 'S'} selection are a controller concern handled by
 *       {@code controller/TransactionController}, never by this service.</li>
 * </ul>
 *
 * <h2>Boundary messages, the "no message field" limitation, and selection handling</h2>
 * <p>{@code COTRN00C} surfaced several pseudo-conversational re-display notes by moving a literal into
 * {@code WS-MESSAGE} and re-sending the screen (the browse simply had no more rows). The confirmed
 * {@link TransactionDto} carries <strong>no</strong> message or next-page field, so &mdash; faithfully to
 * the COBOL, which treats these as informational states rather than errors &mdash; this service does
 * <em>not</em> throw for them: when navigation runs past the end (or before the beginning) it returns a
 * {@link TransactionDto} with an empty {@code transactions} list and leaves the page number unchanged.
 * The six verbatim browse/boundary strings are published as {@code public static final String} constants
 * ({@link #MSG_ALREADY_AT_TOP}, {@link #MSG_ALREADY_AT_BOTTOM}, {@link #MSG_AT_TOP},
 * {@link #MSG_REACHED_BOTTOM}, {@link #MSG_REACHED_TOP}, {@link #MSG_UNABLE_TO_LOOKUP}) so the controller
 * can render them and tests can assert exact parity even though the transport (HTTP response vs. 3270
 * re-display) differs.</p>
 * <p>Only two conditions are genuine input-validation failures and therefore throw
 * {@link ValidationException} (HTTP&nbsp;400) with the verbatim COBOL message: a supplied but
 * non-numeric starting-id filter ({@link #MSG_TRAN_ID_NOT_NUMERIC}, {@code COTRN00C} L211-215) and a row
 * selection flag that is present but not {@code 'S'}/{@code 's'} ({@link #MSG_INVALID_SELECTION},
 * {@code COTRN00C} L196-200). A valid {@code 'S'} selection drives the {@code XCTL} to {@code COTRN01C}
 * in the legacy program; in REST that navigation is a separate detail-endpoint call made by the
 * controller/client, so this single-responsibility list service never invokes the detail service.</p>
 *
 * <p>The program performs only browse operations ({@code STARTBR}/{@code READNEXT}/{@code READPREV}/
 * {@code ENDBR}), so this service is annotated {@link Transactional @Transactional(readOnly = true)} at
 * the class level and carries no mutating behavior and no mutable instance state.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see TransactionDto
 * @see TransactionDto.TransactionListItem
 * @see ValidationException
 */
@Service
@Transactional(readOnly = true)
public class TransactionListService {

    // -----------------------------------------------------------------------------------------------
    // Browse contract constants.
    // -----------------------------------------------------------------------------------------------

    /**
     * The fixed browse page size &mdash; <strong>exactly ten rows</strong>.
     *
     * <p>Bound to {@link TransactionDto#ROWS_PER_PAGE} (the single source of truth for the
     * ten-rows-per-page contract) so the value cannot silently drift. It is the Java mapping of the
     * COBOL page-fill loop bound {@code PERFORM ... UNTIL WS-IDX > 10} / {@code UNTIL WS-IDX >= 11}
     * ({@code COTRN00C} L290, L297), which capped the {@code TRNID01..TRNID10} row array. This page size
     * is non-negotiable for parity.</p>
     */
    // PERFORM UNTIL WS-IDX > 10 -> fixed page size of 10 rows [COTRN00C L290/L297]
    private static final int PAGE_SIZE = TransactionDto.ROWS_PER_PAGE;

    /**
     * The {@link Transaction} entity property the browse is ordered by.
     *
     * <p>The COBOL {@code STARTBR ... } positioned the browse on the {@code TRANSACT} cluster key, the
     * sixteen-character transaction id, and {@code READNEXT} then walked the file in ascending key order.
     * The entity property {@code tranId} (column {@code transaction_id}) is that key, so
     * {@code Sort.by("tranId").ascending()} reproduces the {@code STARTBR}/{@code READNEXT} traversal
     * order exactly.</p>
     */
    // STARTBR/READNEXT on the 16-char TRAN-ID cluster key -> Sort by Transaction.tranId ASC [COTRN00C 593-634]
    private static final String SORT_PROPERTY = "tranId";

    /**
     * The lowest possible {@code TRAN-ID} key &mdash; sixteen ASCII zeros.
     *
     * <p>COBOL substitution for {@code MOVE LOW-VALUES TO TRAN-ID} ({@code COTRN00C} L207): a blank
     * starting-id filter positions the browse at the very beginning of the file. Because
     * {@code TRAN-ID PIC X(16)} holds a 16-character zero-padded numeric key, the lowest key is sixteen
     * zeros.</p>
     */
    // MOVE LOW-VALUES TO TRAN-ID -> lowest 16-char numeric key [COTRN00C L207]
    private static final String LOWEST_TRAN_ID = "0000000000000000";

    /**
     * The display fallback for a missing original timestamp &mdash; the COBOL {@code WS-TRAN-DATE}
     * initial value {@code '00/00/00'} ({@code COTRN00C} L57). Used when {@code TRAN-ORIG-TS} is null so
     * a missing value never throws on this display-only field.
     */
    // WS-TRAN-DATE PIC X(08) VALUE '00/00/00' -> null-timestamp fallback [COTRN00C L57]
    private static final String DEFAULT_TRAN_DATE = "00/00/00";

    /**
     * The list-row description width &mdash; {@code TDESC0n PIC X(26)}. The 100-character
     * {@code TRAN-DESC} is truncated to this width when painted onto a browse row.
     */
    // TDESC0n PIC X(26) -> row description display width (TRAN-DESC X(100) truncates to 26)
    private static final int TRAN_DESC_DISPLAY_LENGTH = 26;

    /**
     * The monetary scale for every amount &mdash; two fractional digits ({@code TRAN-AMT PIC S9(09)V99},
     * AAP &sect;0.7.3). Amounts are {@link BigDecimal} only; never {@code float}/{@code double}.
     */
    // TRAN-AMT PIC S9(09)V99 -> BigDecimal scale 2 (AAP §0.7.3)
    private static final int AMOUNT_SCALE = 2;

    /**
     * Formatter that renders the row date as {@code MM/dd/yy}.
     *
     * <p>COBOL substitution for {@code POPULATE-TRAN-DATA} ({@code COTRN00C} L383-388), which built
     * {@code MM/DD/YY} from the {@code TRAN-ORIG-TS} text (with {@code YY} taken from the last two digits
     * of the four-digit year via {@code WS-TIMESTAMP-DT-YYYY(3:2)}). The {@code yy} pattern emits exactly
     * those last two year digits, matching the legacy rendering.</p>
     */
    // POPULATE-TRAN-DATA MM/DD/YY (YY = year(3:2)) -> DateTimeFormatter "MM/dd/yy" [COTRN00C L383-388]
    private static final DateTimeFormatter TRAN_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy");

    /** Matches an all-digits value (COBOL {@code IS NUMERIC} on an unsigned display field). */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // -----------------------------------------------------------------------------------------------
    // Verbatim COTRN00C messages. Copied EXACTLY (including the trailing "..." ellipses and, for the
    // numeric edit, the single space before the ellipsis) for 100% message parity (AAP §0.7.2).
    // -----------------------------------------------------------------------------------------------

    /** PF7 already at the first page ({@code PROCESS-PAGE-BACKWARD} guard, {@code COTRN00C} L248). */
    public static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** PF8 already at the last page ({@code PROCESS-PAGE-FORWARD} guard, {@code COTRN00C} L270). */
    public static final String MSG_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /** {@code STARTBR} returned {@code NOTFND} &mdash; no record at/after the start key ({@code COTRN00C} L608). */
    public static final String MSG_AT_TOP = "You are at the top of the page...";

    /** {@code READNEXT} returned {@code ENDFILE} &mdash; end of file reached paging forward ({@code COTRN00C} L642). */
    public static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** {@code READPREV} returned {@code ENDFILE} &mdash; start of file reached paging backward ({@code COTRN00C} L676). */
    public static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    /** Any other browse {@code RESP} &mdash; unexpected file-control failure ({@code COTRN00C} L615/L649/L683). */
    public static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /** Row selection flag present but not {@code 'S'}/{@code 's'} ({@code COTRN00C} L196-200) &mdash; thrown. */
    public static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Supplied starting-id filter is not numeric ({@code COTRN00C} L213-215) &mdash; thrown.
     *
     * <p><strong>Exact literal</strong>, including the single space before the three-dot ellipsis, as in
     * the COBOL {@code MOVE 'Tran ID must be Numeric ...' TO WS-MESSAGE}.</p>
     */
    public static final String MSG_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param transactionRepository repository for the {@code TRANSACT} VSAM replacement; supplies the
     *                              greater-than-or-equal paginated browse
     *                              ({@link TransactionRepository#findByTranIdGreaterThanEqual(String,
     *                              org.springframework.data.domain.Pageable)}) that reproduces the
     *                              {@code COTRN00C} {@code STARTBR}/{@code READNEXT} loop
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns one page of the transaction-list browse, reproducing {@code COTRN00C} end to end.
     *
     * <p>Processing order mirrors the COBOL dispatch:</p>
     * <ol>
     *   <li><strong>Edit any row selection</strong> ({@code PROCESS-ENTER-KEY} selection block,
     *       {@code COTRN00C} L183-204). The COBOL captured the first non-blank {@code SELxxxxI} flag and,
     *       if it was not {@code 'S'}/{@code 's'}, raised the verbatim invalid-selection edit. A valid
     *       {@code 'S'} selection drives the {@code XCTL} to the detail program {@code COTRN01C}; in REST
     *       that navigation is a separate detail-endpoint call made by the controller/client, so this
     *       method does not perform it (single responsibility, matching the COBOL program boundary).</li>
     *   <li><strong>Resolve the start key and page index, then run the browse</strong>
     *       ({@code PROCESS-PAGE-FORWARD}/{@code STARTBR}/{@code READNEXT}). A supplied but non-numeric
     *       starting-id filter raises a {@link ValidationException} <em>before</em> any database access;
     *       a blank filter starts the browse from the beginning of the file.</li>
     *   <li><strong>Map the page rows</strong> to the response {@link TransactionDto} (with its nested
     *       {@link TransactionListItem} rows, {@code POPULATE-TRAN-DATA}), echo the starting-id filter and
     *       stamp the one-based page number.</li>
     * </ol>
     *
     * <p>Forward/backward paging (PF8/PF7) is a controller concern: the controller requests
     * {@code page + 1} / {@code page - 1} and uses {@link #hasNextPage(TransactionDto)} /
     * {@link #hasPreviousPage(TransactionDto)} to enable the action and surface the boundary messages. An
     * empty page (navigation past the end/beginning, or no records at/after the start key) is returned as
     * a {@link TransactionDto} with an empty {@code transactions} list and the page number unchanged
     * &mdash; this method does <em>not</em> throw for it, because the COBOL treats it as an informational
     * re-display rather than an error.</p>
     *
     * @param request the inbound list request carrying the optional starting-id filter
     *                ({@code transactionIdFilter}, COBOL {@code TRNIDIN}), the one-based page indicator
     *                ({@code pageNumber}, COBOL {@code PAGENUM}) and any per-row selection flags; must not
     *                be {@code null}
     * @return the populated {@link TransactionDto} whose {@code transactions} list holds up to
     *         {@link #PAGE_SIZE} rows for the requested page (possibly empty), with the starting-id filter
     *         echoed and the eight-character one-based {@code pageNumber} set
     * @throws ValidationException if a row selection flag is present but not {@code 'S'}/{@code 's'}
     *                             ({@link #MSG_INVALID_SELECTION}), or the starting-id filter is supplied
     *                             but not numeric ({@link #MSG_TRAN_ID_NOT_NUMERIC}) &mdash; both
     *                             HTTP&nbsp;400 with the verbatim COBOL message
     */
    public TransactionDto listTransactions(TransactionDto request) {
        // PROCESS-ENTER-KEY selection edit (COTRN00C L183-204): a non-blank row selection flag that is
        // not 'S'/'s' is a genuine input error; a valid 'S'/'s' selection drives XCTL to COTRN01C
        // (detail), which is the controller/client's concern -- never this list service.
        validateSelection(request);

        // Resolve the TRNIDIN start key and the PAGENUM page index, then run the paginated browse.
        Page<Transaction> page = browsePage(request);

        // POPULATE-TRAN-DATA (COTRN00C L381+): map up to PAGE_SIZE (10) browsed rows onto the DTO.
        List<TransactionListItem> items = new ArrayList<>(PAGE_SIZE);
        for (Transaction transaction : page.getContent()) {
            items.add(toListItem(transaction));
        }

        TransactionDto response = new TransactionDto();
        response.setTransactions(items);

        // Filter echo: COTRN00C re-sends the list screen carrying the browse context. The stateless REST
        // contract echoes the applied TRNIDIN filter so the client can correlate the page with its filter.
        response.setTransactionIdFilter(request.getTransactionIdFilter());

        // COBOL substitution: MOVE CDEMO-CT00-PAGE-NUM (PIC 9(08)) TO PAGENUMI (PIC X(08)) rendered the
        // 1-based page as an 8-digit zero-padded string (COTRN00C L324/L373); reproduce that exact width.
        // page.getNumber() is the 0-based index of the returned page, so +1 yields the 1-based page.
        response.setPageNumber(String.format("%08d", page.getNumber() + 1));

        return response;
    }

    /**
     * Returns whether a page exists after the requested page &mdash; the parity equivalent of the COBOL
     * {@code NEXT-PAGE-FLG}.
     *
     * <p>COBOL substitution: after filling a page, {@code PROCESS-PAGE-FORWARD} issued one extra
     * {@code READNEXT} purely to learn whether a further record exists ({@code COTRN00C} L308-313),
     * setting {@code NEXT-PAGE-YES}/{@code NEXT-PAGE-NO} to drive PF8 enablement and the
     * {@link #MSG_ALREADY_AT_BOTTOM} guard. Spring Data's {@link Page#hasNext()} is the exact analog.
     * Because the confirmed {@link TransactionDto} carries no next-page field, this is exposed as a
     * service method the controller uses to enable the forward (PF8) action and to decide when to surface
     * {@link #MSG_ALREADY_AT_BOTTOM}.</p>
     *
     * @param request the same list request used for {@link #listTransactions(TransactionDto)} (start-key
     *                filter and one-based page indicator); must not be {@code null}
     * @return {@code true} when a page exists after the requested page; {@code false} otherwise
     * @throws ValidationException if the starting-id filter is supplied but not numeric
     *                             ({@link #MSG_TRAN_ID_NOT_NUMERIC})
     */
    public boolean hasNextPage(TransactionDto request) {
        return browsePage(request).hasNext();
    }

    /**
     * Returns whether a page exists before the requested page &mdash; the parity equivalent of the COBOL
     * {@code PROCESS-PF7-KEY} top-of-file guard.
     *
     * <p>COBOL substitution: {@code PROCESS-PF7-KEY} only paged backward when
     * {@code CDEMO-CT00-PAGE-NUM > 1}; otherwise it re-displayed {@link #MSG_ALREADY_AT_TOP}
     * ({@code COTRN00C} L245-251). {@link Page#hasPrevious()} (false on page index 0) is the analog the
     * controller uses to enable the backward (PF7) action and to decide when to surface
     * {@link #MSG_ALREADY_AT_TOP}.</p>
     *
     * @param request the same list request used for {@link #listTransactions(TransactionDto)}; must not be
     *                {@code null}
     * @return {@code true} when a page exists before the requested page; {@code false} otherwise
     * @throws ValidationException if the starting-id filter is supplied but not numeric
     *                             ({@link #MSG_TRAN_ID_NOT_NUMERIC})
     */
    public boolean hasPreviousPage(TransactionDto request) {
        return browsePage(request).hasPrevious();
    }

    /**
     * Builds the {@link PageRequest} from the request's start-key filter and page indicator and runs the
     * greater-than-or-equal browse, shared by {@link #listTransactions(TransactionDto)},
     * {@link #hasNextPage(TransactionDto)} and {@link #hasPreviousPage(TransactionDto)}.
     *
     * @param request the inbound list request
     * @return the requested page of transactions with id &ge; the resolved start key (never {@code null})
     * @throws ValidationException if the starting-id filter is supplied but not numeric
     */
    private Page<Transaction> browsePage(TransactionDto request) {
        // TRNIDIN start-key filter (COTRN00C L206-219).
        String startId = resolveStartKey(request.getTransactionIdFilter());

        // PAGENUM 1-based screen page (COBOL CDEMO-CT00-PAGE-NUM) -> Spring Data 0-based page index.
        int pageIndex = resolvePageIndex(request.getPageNumber());

        // COBOL substitution: VSAM STARTBR(GTEQ)/READNEXT/READPREV keyset browse, 10 rows/page
        //   (PERFORM UNTIL WS-IDX > 10) -> Spring Data offset pagination. PageRequest index is 0-based;
        //   Sort by the 16-char tranId key ascending reproduces the ascending-key STARTBR/READNEXT walk.
        return transactionRepository.findByTranIdGreaterThanEqual(
                startId, PageRequest.of(pageIndex, PAGE_SIZE, Sort.by(SORT_PROPERTY).ascending()));
    }

    /**
     * Edits any per-row selection flag, reproducing the {@code PROCESS-ENTER-KEY} selection block
     * ({@code COTRN00C} L148-204).
     *
     * <p>The COBOL {@code EVALUATE TRUE} captured the <em>first</em> non-blank {@code SELxxxxI} flag (rows
     * one through ten, in order); that first-match ordering and the no-fall-through semantics are
     * preserved here. A captured flag of {@code 'S'}/{@code 's'} selects the row for the detail program
     * (controller concern &mdash; not performed here); any other non-blank value raises the verbatim
     * {@link #MSG_INVALID_SELECTION} edit. A {@code null} or all-blank flag is "no selection".</p>
     *
     * @param request the inbound list request; its {@code transactions} rows may carry selection flags
     * @throws ValidationException if the first non-blank selection flag is not {@code 'S'}/{@code 's'}
     */
    private void validateSelection(TransactionDto request) {
        List<TransactionListItem> rows = request.getTransactions();
        if (rows == null) {
            return;
        }
        // EVALUATE TRUE (L148-182): the first non-blank SELxxxxI flag wins; no fall-through.
        for (TransactionListItem row : rows) {
            if (row == null) {
                continue;
            }
            String flag = row.getSelectionFlag();
            if (flag == null) {
                continue;
            }
            String trimmed = flag.trim();
            if (trimmed.isEmpty()) {
                // SPACES / LOW-VALUES -> no selection on this row.
                continue;
            }
            // EVALUATE CDEMO-CT00-TRN-SEL-FLG (L185-203): 'S'/'s' -> select (controller navigates to the
            // detail program COTRN01C); any other value -> the verbatim invalid-selection edit.
            if (!"S".equals(trimmed) && !"s".equals(trimmed)) {
                throw new ValidationException(MSG_INVALID_SELECTION);
            }
            // First non-blank selection handled; the COBOL evaluated only the first match.
            return;
        }
    }

    /**
     * Resolves the browse start key from the optional starting-id filter, reproducing the
     * {@code PROCESS-ENTER-KEY} positioning logic ({@code COTRN00C} L206-219).
     *
     * @param filter the raw {@code TRNIDIN} filter value (may be {@code null}/blank)
     * @return the 16-character start key to browse from
     * @throws ValidationException if the filter is supplied but not numeric ({@link #MSG_TRAN_ID_NOT_NUMERIC})
     */
    private static String resolveStartKey(String filter) {
        // COBOL substitution: IF TRNIDINI = SPACES OR LOW-VALUES -> MOVE LOW-VALUES TO TRAN-ID (L206-207).
        //   The lowest 16-char numeric key (the LOW-VALUES analog) is sixteen ASCII zeros.
        if (filter == null || filter.trim().isEmpty()) {
            return LOWEST_TRAN_ID;
        }
        String trimmed = filter.trim();
        if (isAllDigits(trimmed)) {
            // IF TRNIDINI IS NUMERIC -> MOVE TRNIDINI TO TRAN-ID (L209-210). Left zero-pad to the 16-char
            // key width so the value aligns with the stored keys. The DTO @Size(max = 16) bounds the
            // input width, so the all-digit value is always within long range.
            return String.format("%016d", Long.parseLong(trimmed));
        }
        // ELSE -> 'Tran ID must be Numeric ...' (L211-215): a genuine input-validation failure.
        throw new ValidationException(MSG_TRAN_ID_NOT_NUMERIC);
    }

    /**
     * Resolves the Spring Data 0-based page index from the one-based {@code PAGENUM} indicator.
     *
     * <p>COBOL substitution: {@code CDEMO-CT00-PAGE-NUM} is a one-based page counter ({@code PIC 9(08)});
     * {@code PROCESS-ENTER-KEY} reset it to zero then {@code PROCESS-PAGE-FORWARD} advanced it to one
     * ({@code COTRN00C} L224-225). A blank, absent or non-numeric indicator therefore means "first page"
     * &rarr; index zero; any value at or below one is also the first page.</p>
     *
     * @param pageNumber the raw {@code PAGENUM} value (may be {@code null}/blank)
     * @return the 0-based page index (never negative)
     */
    private static int resolvePageIndex(String pageNumber) {
        if (pageNumber == null || pageNumber.trim().isEmpty()) {
            return 0;
        }
        String trimmed = pageNumber.trim();
        if (!isAllDigits(trimmed)) {
            // Defensive: a non-numeric page indicator is treated as the first page (index 0).
            return 0;
        }
        long oneBasedPage = Long.parseLong(trimmed);
        if (oneBasedPage <= 1L) {
            return 0;
        }
        // 1-based page N -> 0-based index N-1. PAGENUM is PIC X(8), so the value stays within int range.
        return (int) (oneBasedPage - 1L);
    }

    /**
     * Maps one browsed {@link Transaction} record to a {@link TransactionListItem}, reproducing the row
     * {@code MOVE}s of {@code POPULATE-TRAN-DATA} ({@code COTRN00C} L381-445).
     *
     * @param transaction the browsed transaction record (never {@code null})
     * @return the populated browse row
     */
    private static TransactionListItem toListItem(Transaction transaction) {
        TransactionListItem item = new TransactionListItem();

        // SEL000n PIC X(1) is an empty input field the operator types 'S' into -> blank on the list output
        // (COTRN00C never writes a value into it when painting a page).
        item.setSelectionFlag("");

        // TRNID0n PIC X(16) <- TRAN-ID [COTRN00C L392 etc.].
        item.setTransactionId(transaction.getTranId());

        // TDATE0n PIC X(8) <- MM/DD/YY derived from TRAN-ORIG-TS.
        item.setDate(formatTranDate(transaction.getTranOrigTs()));

        // TDESC0n PIC X(26) <- TRAN-DESC truncated to the 26-char row width.
        item.setDescription(truncateDescription(transaction.getTranDesc()));

        // TAMT00n <- TRAN-AMT as a signed BigDecimal of scale 2 (the +99999999.99 edited form is purely
        // presentational; the DTO carries the raw signed value).
        item.setAmount(scaleAmount(transaction.getTranAmt()));

        return item;
    }

    /**
     * Renders the row date as {@code MM/DD/YY}, reproducing {@code POPULATE-TRAN-DATA} ({@code COTRN00C}
     * L383-388).
     *
     * <p>COBOL substitution: the legacy program reference-modified the 26-character {@code TRAN-ORIG-TS}
     * text ({@code YY = WS-TIMESTAMP-DT-YYYY(3:2)}, i.e. the last two year digits). The migrated
     * {@link Transaction} entity stores {@code TRAN-ORIG-TS} as a {@link LocalDateTime}, so the same value
     * is rendered with the {@code MM/dd/yy} formatter; the painted string is identical. A {@code null}
     * timestamp falls back to {@link #DEFAULT_TRAN_DATE} ({@code '00/00/00'}, the {@code WS-TRAN-DATE}
     * initial value) so a missing value never throws on this display-only field.</p>
     *
     * @param origTs the original transaction timestamp (may be {@code null})
     * @return the {@code MM/DD/YY} date string, or {@code "00/00/00"} when the timestamp is {@code null}
     */
    private static String formatTranDate(LocalDateTime origTs) {
        if (origTs == null) {
            return DEFAULT_TRAN_DATE;
        }
        return origTs.format(TRAN_DATE_FORMAT);
    }

    /**
     * Truncates the transaction description to the 26-character row width, reproducing
     * {@code MOVE TRAN-DESC TO TDESC0n} ({@code COTRN00C} L395 etc.).
     *
     * <p>COBOL substitution: a COBOL alphanumeric {@code MOVE} of {@code TRAN-DESC PIC X(100)} into the
     * {@code TDESC0n PIC X(26)} screen field truncates to the leftmost 26 characters. A {@code null}
     * description maps to an empty string (COBOL would render spaces).</p>
     *
     * @param description the full transaction description (may be {@code null})
     * @return the description truncated to at most {@link #TRAN_DESC_DISPLAY_LENGTH} characters
     */
    private static String truncateDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.substring(0, Math.min(TRAN_DESC_DISPLAY_LENGTH, description.length()));
    }

    /**
     * Normalizes the row amount to scale 2, reproducing the decimal-fidelity rule (AAP &sect;0.7.3).
     *
     * <p>Monetary values are {@link BigDecimal} of scale 2 &mdash; never {@code float}/{@code double} &mdash;
     * and comparisons (elsewhere) use {@link BigDecimal#compareTo(BigDecimal)}, never the scale-sensitive
     * {@link BigDecimal#equals(Object)}. The persisted {@code TRAN-AMT} is already {@code NUMERIC(11,2)};
     * this defensively normalizes the scale to two using {@link RoundingMode#HALF_EVEN} (banker's
     * rounding) without altering the value.</p>
     *
     * @param amount the raw transaction amount (may be {@code null})
     * @return the amount at scale 2, or {@code null} when the input is {@code null}
     */
    private static BigDecimal scaleAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Determines whether a value is all digits, mirroring the COBOL {@code IS NUMERIC} test on an unsigned
     * display field. Only digit composition is checked (no length constraint).
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-{@code null} and every character is a digit
     */
    private static boolean isAllDigits(String value) {
        return value != null && DIGITS.matcher(value).matches();
    }
}

