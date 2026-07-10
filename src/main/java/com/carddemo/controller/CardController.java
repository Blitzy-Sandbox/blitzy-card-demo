package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.dto.CardListResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.CardUpdateResponse;
import com.carddemo.dto.CardViewResponse;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.CardViewService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * REST controller for the credit-card list, view and update use cases &mdash; the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5 replacement for three legacy CICS/BMS 3270
 * programs (frozen COBOL source referenced read-only at commit SHA {@code 27d6c6f};
 * never copied into this repository):
 *
 * <ul>
 *   <li><strong>{@code COCRDLIC}</strong> ({@code app/cbl/COCRDLIC.cbl}, transaction
 *       {@code CCLI}) &mdash; the paginated <em>Card List</em> screen. The mainframe map
 *       {@code COCRDLI} renders a fixed <strong>seven rows per page</strong>
 *       ({@code OCCURS 7 TIMES} / {@code WS-MAX-SCREEN-LINES}) with an optional account-id
 *       and/or card-number search filter and PF7 (backward) / PF8 (forward) scroll keys.
 *       That pseudo-conversational, {@code COMMAREA}-carried paging state is re-expressed
 *       here as a <strong>stateless</strong> {@code GET /api/cards}: the PF7/PF8 keys become
 *       a {@code page} query parameter and the filters become query parameters.</li>
 *   <li><strong>{@code COCRDSLC}</strong> ({@code app/cbl/COCRDSLC.cbl}, transaction
 *       {@code CCDL}) &mdash; the single-card <em>detail view</em>, mapped to
 *       {@code GET /api/cards/{cardNumber}}.</li>
 *   <li><strong>{@code COCRDUPC}</strong> ({@code app/cbl/COCRDUPC.cbl}, transaction
 *       {@code CCUP}) &mdash; the read-then-rewrite <em>card update</em> with
 *       optimistic-concurrency detection, mapped to {@code PUT /api/cards/{cardNumber}}.
 *       A version conflict surfaces as HTTP&nbsp;409 (&quot;record changed by someone
 *       else&quot;); all persistence and lock logic lives in {@link CardUpdateService}.</li>
 * </ul>
 *
 * <h2>Thin controller by design</h2>
 * <p>This class performs no business logic: it binds and validates the HTTP request, delegates
 * to the appropriate {@code @Service}, and returns the produced DTO with a {@code 200 OK}
 * status. It deliberately does <strong>not</strong> catch exceptions &mdash; every domain
 * failure ({@code ValidationException}&nbsp;&rarr;&nbsp;400,
 * {@code ResourceNotFoundException}&nbsp;&rarr;&nbsp;404,
 * {@code OptimisticLockConflictException}&nbsp;&rarr;&nbsp;409) and every request-binding
 * failure ({@code MethodArgumentNotValidException} /
 * {@code ConstraintViolationException}&nbsp;&rarr;&nbsp;400) is translated to the shared
 * {@code ErrorResponse} JSON body by {@link GlobalExceptionHandler}.</p>
 *
 * <h2>Security &amp; PAN protection</h2>
 * <p>All endpoints require an authenticated caller; card screens are reachable by
 * <em>any</em> role (regular or admin), so there is no {@code @PreAuthorize} admin gate here
 * &mdash; authentication is enforced by the security filter chain in the {@code config}
 * package. Card numbers are Primary Account Numbers (PANs): the producing services mask them
 * to the last four digits, so every {@link CardViewResponse} / {@code CardListItem} returned
 * already carries a masked value and this controller never unmasks one. Diagnostic logs never
 * contain a full PAN &mdash; the card-number search filter is logged only as a supplied/absent
 * flag and path/body card numbers are masked via {@link #maskPan(String)} before logging.
 * Every log line carries the MDC {@code correlationId} established by the observability
 * correlation-id filter.</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor injection; its
 * three service collaborators are {@code final}. Design rationale is recorded in
 * {@code docs/decision-log.md} and the COBOL-paragraph mapping in
 * {@code docs/traceability-matrix.md} (Explainability rule), not in code comments.</p>
 *
 * @see CardListService
 * @see CardViewService
 * @see CardUpdateService
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/cards")
@Validated
public class CardController {

    /** Structured logger; never emits a full card number (PAN). Carries the MDC {@code correlationId}. */
    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    /** Number of trailing PAN digits left visible when masking a card number for logging. */
    private static final int PAN_VISIBLE_DIGITS = 4;

    /** Card List service (transaction {@code CCLI}); fixes the page size at seven rows. */
    private final CardListService cardListService;

    /** Card View service (transaction {@code CCDL}); single-card detail lookup. */
    private final CardViewService cardViewService;

    /** Card Update service (transaction {@code CCUP}); read-then-rewrite with optimistic locking. */
    private final CardUpdateService cardUpdateService;

    /**
     * Creates the controller with its three service collaborators.
     *
     * <p>Constructor injection is used exclusively (no field injection) so the collaborators
     * are {@code final} and the controller can be instantiated directly &mdash; with mock
     * services &mdash; in a standalone MockMvc unit test.</p>
     *
     * @param cardListService   the Card List service ({@code CCLI}); must not be {@code null}
     * @param cardViewService   the Card View service ({@code CCDL}); must not be {@code null}
     * @param cardUpdateService the Card Update service ({@code CCUP}); must not be {@code null}
     */
    public CardController(final CardListService cardListService,
                          final CardViewService cardViewService,
                          final CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardViewService = cardViewService;
        this.cardUpdateService = cardUpdateService;
    }

    /**
     * Lists credit cards a page at a time, optionally narrowed by owning account id and/or
     * card number &mdash; the stateless migration of the {@code COCRDLIC} (CCLI) browse.
     *
     * <p>The legacy Card List screen shows a fixed seven rows per page and scrolls with PF7
     * (backward) / PF8 (forward). Those keys are replaced by an explicit, one-based
     * {@code page} query parameter (the first page is {@code 1}, mirroring the legacy
     * {@code PAGENO} indicator and the one-based {@link CardListResponse#page()} convention).
     * The optional {@code accountId} and {@code cardNumber} filters replace the on-screen
     * {@code ACCTSID}/{@code CARDSID} search fields; both filters, when malformed, are rejected
     * by {@link CardListService} with the verbatim legacy messages (HTTP&nbsp;400).</p>
     *
     * <p><strong>Page-index bridge.</strong> {@link CardListService#listCards} follows the
     * Spring Data convention of a <em>zero-based</em> page index, whereas this REST endpoint is
     * <em>one-based</em>. The one-based value is therefore converted to zero-based
     * ({@code page - 1}) before delegation, so {@code page=1} returns the first page rather than
     * the second. The service fixes the page size at seven
     * ({@link CardListResponse#PAGE_SIZE}); the controller never sets it.</p>
     *
     * @param accountId  optional owning-account filter ({@code CARD-ACCT-ID} / screen
     *                   {@code ACCTSID}); {@code null} means "no account filter"
     * @param cardNumber optional card-number filter ({@code CARD-NUM} / screen {@code CARDSID});
     *                   {@code null} or blank means "no card filter"; at most sixteen characters
     * @param page       the one-based page index to retrieve; defaults to {@code 1} and must be
     *                   between {@code 1} and {@code 1000000} inclusive. The upper bound is an
     *                   input-robustness guard: without it, the zero-based offset computed
     *                   downstream ({@code (page - 1) * }{@link CardListResponse#PAGE_SIZE}) can
     *                   exceed {@link Integer#MAX_VALUE} for absurdly large pages, which Spring
     *                   Data rejects with an {@code InvalidDataAccessApiUsageException}. Bounding
     *                   the parameter turns that into a clean {@code 400 VALIDATION_ERROR} instead
     *                   of a {@code 500}. One million pages addresses seven million rows, far
     *                   beyond any CardDemo data set.
     * @return {@code 200 OK} with a {@link CardListResponse} echoing the applied filters and
     *         carrying the requested page of masked card rows with pagination metadata
     */
    @GetMapping
    public ResponseEntity<CardListResponse> listCards(
            @RequestParam(required = false) final Long accountId,
            @RequestParam(required = false) @Size(max = 16) final String cardNumber,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) final int page) {

        // Never log the raw card-number filter (it may be a full PAN): record only whether a
        // filter was supplied, alongside the non-sensitive account id and one-based page index.
        log.info("Card list request (CCLI): accountId={}, cardFilterSupplied={}, page={}",
                accountId, isSupplied(cardNumber), page);

        // Bridge the one-based REST page (PAGENO / PF7-PF8) to the zero-based Spring Data index
        // expected by CardListService, so page=1 yields the first page.
        final CardListResponse response = cardListService.listCards(accountId, cardNumber, page - 1);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the detail of a single card identified by its card number &mdash; the migration of
     * the {@code COCRDSLC} (CCDL) single-card view.
     *
     * <p>The path variable reproduces the {@code COCRDSL} {@code CARDSID} search key: it must be
     * present and be one-to-sixteen digits ({@code CARD-NUM PIC X(16)}); a non-numeric or
     * oversized value is rejected as HTTP&nbsp;400 before the service is invoked. When no card
     * exists for the number, {@link CardViewService} raises a {@code ResourceNotFoundException}
     * (HTTP&nbsp;404). The returned {@link CardViewResponse} carries a masked PAN and no CVV.</p>
     *
     * @param cardNumber the card number to view ({@code CARD-NUM} / screen {@code CARDSID});
     *                   required, at most sixteen characters, digits only
     * @return {@code 200 OK} with the masked, CVV-free {@link CardViewResponse} for the card
     */
    @GetMapping("/{cardNumber}")
    public ResponseEntity<CardViewResponse> viewCard(
            @PathVariable @NotBlank @Size(max = 16) @Pattern(regexp = "\\d{1,16}") final String cardNumber) {

        log.info("Card view request (CCDL): card={}", maskPan(cardNumber));

        final CardViewResponse response = cardViewService.viewCard(cardNumber);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates the mutable attributes of an existing card &mdash; the migration of the
     * {@code COCRDUPC} (CCUP) read-then-rewrite update with optimistic-concurrency detection.
     *
     * <p>The path variable identifies the card to update; the request body carries the requested
     * changes (embossed name, active status, expiration date) plus the optimistic-lock
     * {@code version} the caller last observed. {@link CardUpdateService} applies the mutable
     * fields, re-reads under lock and rewrites; a stale version surfaces as
     * {@code OptimisticLockConflictException} (HTTP&nbsp;409, &quot;Record changed by some one
     * else&quot;), a malformed field as {@code ValidationException} (HTTP&nbsp;400), and a
     * missing card as {@code ResourceNotFoundException} (HTTP&nbsp;404). Bean-validation failures
     * on the body ({@code @Valid}) are reported as HTTP&nbsp;400 with per-field detail. No
     * exception is caught here &mdash; {@link GlobalExceptionHandler} maps them all.</p>
     *
     * @param cardNumber the card number identifying the record to update ({@code CARD-NUM});
     *                   required, at most sixteen characters
     * @param request    the validated update payload and echoed optimistic-lock version; must not
     *                   be {@code null}
     * @return {@code 200 OK} with a {@link CardUpdateResponse} carrying the masked, CVV-free card
     *         snapshot, the success confirmation, and the new optimistic-lock version
     */
    @PutMapping("/{cardNumber}")
    public ResponseEntity<CardUpdateResponse> updateCard(
            @PathVariable @NotBlank @Size(max = 16) final String cardNumber,
            @Valid @RequestBody final CardUpdateRequest request) {

        // Log the masked card and the (non-sensitive) account id; never the full PAN or CVV.
        log.info("Card update request (CCUP): card={}, accountId={}",
                maskPan(cardNumber), (request == null) ? null : request.accountId());

        final CardUpdateResponse response = cardUpdateService.updateCard(cardNumber, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Masks a card number (PAN) for logging so that only its last {@value #PAN_VISIBLE_DIGITS}
     * digits remain visible, replacing every earlier character with {@code '*'}. Fixed-width
     * padding is stripped first. The method is null-safe and short-safe: {@code null} is returned
     * unchanged and a value of {@value #PAN_VISIBLE_DIGITS} or fewer characters is returned
     * unchanged (there is nothing further to conceal, and no digits are invented). Masking is
     * idempotent, so an already-masked value passes through unchanged. It mirrors the masking the
     * producing services apply to response payloads, ensuring a full PAN is never written to a
     * log line at any layer.
     *
     * @param pan the card number to mask for logging; may be {@code null}
     * @return the masked card number (for example {@code ************3456}), or the original value
     *         when there is nothing to mask
     */
    private static String maskPan(final String pan) {
        if (pan == null) {
            return null;
        }
        final String normalized = pan.strip();
        final int length = normalized.length();
        if (length <= PAN_VISIBLE_DIGITS) {
            return normalized;
        }
        return "*".repeat(length - PAN_VISIBLE_DIGITS)
                + normalized.substring(length - PAN_VISIBLE_DIGITS);
    }

    /**
     * Determines whether an optional text filter was supplied, treating {@code null} and blank
     * (whitespace-only) values as "not supplied" &mdash; the migration of the COBOL blank test on
     * {@code SPACES} / {@code LOW-VALUES}. Used to log the presence of the card-number filter
     * without ever logging its (potentially full-PAN) value.
     *
     * @param filter the candidate filter value; may be {@code null}
     * @return {@code true} if the filter carries non-whitespace text
     */
    private static boolean isSupplied(final String filter) {
        return filter != null && !filter.isBlank();
    }
}
