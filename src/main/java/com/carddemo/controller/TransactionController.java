package com.carddemo.controller;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionAddResponse;
import com.carddemo.dto.TransactionListResponse;
import com.carddemo.dto.TransactionViewResponse;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * REST controller for the transaction list, view and add use cases &mdash; the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5 replacement for three legacy CICS/BMS 3270
 * programs (frozen COBOL source referenced read-only at commit SHA {@code 27d6c6f};
 * never copied into this repository):
 *
 * <ul>
 *   <li><strong>{@code COTRN00C}</strong> ({@code app/cbl/COTRN00C.cbl}, transaction
 *       {@code CT00}) &mdash; the paginated <em>Transaction List</em> screen. The mainframe
 *       map {@code COTRN00} renders a fixed <strong>ten rows per page</strong> (the
 *       {@code PROCESS-PAGE-FORWARD} loop stops at {@code WS-IDX >= 11}) with an optional
 *       transaction-id and/or card-number search filter, a {@code SEL} row-select field to
 *       drill into a transaction, and PF7 (backward) / PF8 (forward) scroll keys. That
 *       pseudo-conversational, {@code COMMAREA}-carried paging state
 *       ({@code CDEMO-CT00-PAGE-NUM} / {@code NEXT-PAGE-FLG}) is re-expressed here as a
 *       <strong>stateless</strong> {@code GET /api/transactions}: PF7/PF8 become a
 *       {@code page} query parameter, the filters become query parameters, and the
 *       {@code SEL} affordance becomes the {@code /{transactionId}} view path below.</li>
 *   <li><strong>{@code COTRN01C}</strong> ({@code app/cbl/COTRN01C.cbl}, transaction
 *       {@code CT01}) &mdash; the single-transaction <em>detail view</em>, mapped to
 *       {@code GET /api/transactions/{transactionId}}.</li>
 *   <li><strong>{@code COTRN02C}</strong> ({@code app/cbl/COTRN02C.cbl}, transaction
 *       {@code CT02}) &mdash; the <em>transaction add</em> with a {@code Y}/{@code N}
 *       confirm gate ({@code EVALUATE CONFIRMI}) and a server-generated transaction id
 *       (the {@code ADD-TRANSACTION} {@code READPREV} + {@code ADD 1} auto-ID), mapped to
 *       {@code POST /api/transactions}. The pseudo-conversational confirm state becomes the
 *       stateless {@link TransactionAddRequest#confirm() confirm} flag on the request
 *       body.</li>
 * </ul>
 *
 * <h2>Thin controller by design</h2>
 * <p>This class performs no business logic: it binds and validates the HTTP request,
 * delegates to the appropriate {@code @Service}, and returns the produced DTO. It
 * deliberately does <strong>not</strong> catch exceptions &mdash; every domain failure
 * ({@code ValidationException}&nbsp;&rarr;&nbsp;400,
 * {@code ResourceNotFoundException}&nbsp;&rarr;&nbsp;404) and every request-binding failure
 * ({@code MethodArgumentNotValidException} /
 * {@code ConstraintViolationException}&nbsp;&rarr;&nbsp;400) is translated to the shared
 * {@code ErrorResponse} JSON body by {@link GlobalExceptionHandler}.</p>
 *
 * <h2>Page-index bridge</h2>
 * <p>{@link TransactionListService#listTransactions} follows the Spring&nbsp;Data convention
 * of a <em>zero-based</em> page index, whereas this REST endpoint is <em>one-based</em> (the
 * first page is {@code 1}, mirroring the legacy {@code PAGENUM} indicator and the one-based
 * {@link com.carddemo.dto.PageResponse#pageNumber()} convention). The one-based value is
 * therefore converted to zero-based ({@code page - 1}) before delegation, so {@code page=1}
 * returns the first page rather than the second. The service fixes the page size at ten rows;
 * the controller never sets it.</p>
 *
 * <h2>Add semantics (confirm-before-commit)</h2>
 * <p>{@code POST /api/transactions} reproduces the legacy confirm gate statelessly. When the
 * request's {@link TransactionAddRequest#confirm() confirm} flag is {@code true} the service
 * validates, allocates the next transaction id and persists, and the endpoint responds
 * {@code 201 Created} with a {@code Location} header pointing at the new resource. When the
 * flag is absent or {@code false} the service returns a non-persisted preview carrying the
 * legacy <em>"Confirm to add this transaction..."</em> prompt, and the endpoint responds
 * {@code 200 OK}. The transaction id is <strong>always</strong> server-generated; the request
 * never supplies one.</p>
 *
 * <h2>Security &amp; PAN protection</h2>
 * <p>All endpoints require an authenticated caller; transaction screens are reachable by
 * <em>any</em> role (regular or admin), so there is no {@code @PreAuthorize} admin gate here
 * &mdash; authentication is enforced by the security filter chain in the {@code config}
 * package (its blanket {@code anyRequest().authenticated()} rule covers
 * {@code /api/transactions/**}). Card numbers are Primary Account Numbers (PANs): the
 * producing services mask them to the last four digits, so every {@link TransactionViewResponse}
 * / {@code TransactionListItem} returned already carries a masked value and this controller
 * never unmasks one. Diagnostic logs never contain a full PAN &mdash; the card-number search
 * filter is logged only as a supplied/absent flag and the add request's card number is masked
 * via {@link #maskPan(String)} before logging. Every log line carries the MDC
 * {@code correlationId} established by the observability correlation-id filter.</p>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Monetary amounts flow through as {@link java.math.BigDecimal} at scale&nbsp;2 (matching
 * the COBOL {@code TRAN-AMT PIC S9(09)V99}); this controller performs no arithmetic and never
 * uses {@code float}/{@code double}. Timestamps remain 26-character strings so the exact
 * on-file format survives the round-trip unchanged (interface-contract parity, Gates&nbsp;1/5).</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor injection; its
 * three service collaborators are {@code final}. Design rationale is recorded in
 * {@code docs/decision-log.md} and the COBOL-paragraph mapping in
 * {@code docs/traceability-matrix.md} (Explainability rule), not in code comments.</p>
 *
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/transactions")
@Validated
public class TransactionController {

    /** Structured logger; never emits a full card number (PAN). Carries the MDC {@code correlationId}. */
    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    /** Base request path, reused when building the {@code Location} header for a created transaction. */
    private static final String RESOURCE_PATH = "/api/transactions";

    /** Number of trailing PAN digits left visible when masking a card number for logging. */
    private static final int PAN_VISIBLE_DIGITS = 4;

    /** Transaction List service (transaction {@code CT00}); fixes the page size at ten rows. */
    private final TransactionListService transactionListService;

    /** Transaction View service (transaction {@code CT01}); single-transaction detail lookup. */
    private final TransactionViewService transactionViewService;

    /** Transaction Add service (transaction {@code CT02}); confirm gate + auto-ID + persist. */
    private final TransactionAddService transactionAddService;

    /**
     * Creates the controller with its three service collaborators.
     *
     * <p>Constructor injection is used exclusively (no field injection) so the collaborators
     * are {@code final} and the controller can be instantiated directly &mdash; with mock
     * services &mdash; in a standalone MockMvc unit test.</p>
     *
     * @param transactionListService the Transaction List service ({@code CT00}); must not be {@code null}
     * @param transactionViewService the Transaction View service ({@code CT01}); must not be {@code null}
     * @param transactionAddService  the Transaction Add service ({@code CT02}); must not be {@code null}
     */
    public TransactionController(final TransactionListService transactionListService,
                                 final TransactionViewService transactionViewService,
                                 final TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionViewService = transactionViewService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Lists transactions a page at a time, optionally narrowed by transaction id and/or card
     * number &mdash; the stateless migration of the {@code COTRN00C} (CT00) browse.
     *
     * <p>The legacy Transaction List screen shows a fixed ten rows per page and scrolls with
     * PF7 (backward) / PF8 (forward). Those keys are replaced by an explicit, one-based
     * {@code page} query parameter; the optional {@code transactionId} and {@code cardNumber}
     * filters replace the on-screen search fields. A non-blank, non-numeric transaction-id
     * filter is rejected by {@link TransactionListService} with the verbatim legacy message
     * (HTTP&nbsp;400). The one-based {@code page} is bridged to the zero-based Spring&nbsp;Data
     * index the service expects ({@code page - 1}), so {@code page=1} returns the first page.</p>
     *
     * @param transactionId optional transaction-id start filter ({@code TRNIDIN}); {@code null}
     *                      or blank means "no filter"; at most sixteen characters; echoed back
     *                      verbatim in the response
     * @param cardNumber    optional card-number (PAN) filter used to scope the browse to a single
     *                      card; {@code null} or blank means "no card filter"; at most sixteen
     *                      characters; never logged
     * @param page          the one-based page index to retrieve; defaults to {@code 1} and must be
     *                      at least {@code 1}
     * @return {@code 200 OK} with a {@link TransactionListResponse} echoing the transaction-id
     *         filter and carrying the requested ten-row page with pagination metadata
     */
    @GetMapping
    public ResponseEntity<TransactionListResponse> listTransactions(
            @RequestParam(required = false) @Size(max = 16) final String transactionId,
            @RequestParam(required = false) @Size(max = 16) final String cardNumber,
            @RequestParam(defaultValue = "1") @Min(1) final int page) {

        // Never log the raw card-number filter (it may be a full PAN): record only whether a
        // filter was supplied, alongside the non-sensitive transaction-id filter and page index.
        log.info("Transaction list request (CT00): transactionIdFilter={}, cardFilterSupplied={}, page={}",
                transactionId, isSupplied(cardNumber), page);

        // Bridge the one-based REST page (PAGENUM / PF7-PF8) to the zero-based Spring Data index
        // expected by TransactionListService, so page=1 yields the first page.
        final TransactionListResponse response =
                transactionListService.listTransactions(transactionId, cardNumber, page - 1);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the detail of a single transaction identified by its transaction id &mdash; the
     * migration of the {@code COTRN01C} (CT01) single-transaction view.
     *
     * <p>The path variable reproduces the {@code COTRN01} {@code TRNIDIN} search key
     * ({@code TRAN-ID PIC X(16)}): it must be present and at most sixteen characters. An empty
     * id is rejected by {@link TransactionViewService} as HTTP&nbsp;400, and when no transaction
     * exists for the id the service raises a {@code ResourceNotFoundException} (HTTP&nbsp;404).
     * The returned {@link TransactionViewResponse} carries a masked PAN, the amount at scale&nbsp;2
     * and the 26-character timestamps preserved verbatim.</p>
     *
     * @param transactionId the transaction id to view ({@code TRAN-ID}); required, at most sixteen
     *                      characters
     * @return {@code 200 OK} with the {@link TransactionViewResponse} detail for the transaction
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionViewResponse> viewTransaction(
            @PathVariable @NotBlank @Size(max = 16) final String transactionId) {

        log.info("Transaction view request (CT01): transactionId={}", transactionId);

        final TransactionViewResponse response = transactionViewService.viewTransaction(transactionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Adds a new transaction &mdash; the migration of the {@code COTRN02C} (CT02) add flow with
     * its {@code Y}/{@code N} confirm gate and server-generated transaction id.
     *
     * <p>The request body carries the transaction fields (account id or card number, type,
     * category, source, description, amount, dates and merchant details) plus the
     * confirm-before-commit flag; it never carries a transaction id (the id is auto-generated).
     * {@link TransactionAddService} validates every field in the legacy {@code COTRN02C} order
     * &mdash; a malformed field surfaces as {@code ValidationException} (HTTP&nbsp;400) and a
     * missing account cross-reference as {@code ResourceNotFoundException} (HTTP&nbsp;404).
     * Bean-validation failures on the body ({@code @Valid}, for example a blank required field or
     * an amount with three decimal places) are reported as HTTP&nbsp;400 with per-field detail.
     * No exception is caught here &mdash; {@link GlobalExceptionHandler} maps them all.</p>
     *
     * <p><strong>Status semantics.</strong> When {@code confirm} is {@code true} the service
     * commits and this method returns {@code 201 Created} with a {@code Location} header pointing
     * at {@code /api/transactions/{transactionId}} for the newly assigned id. When {@code confirm}
     * is absent or {@code false} the service returns a non-persisted preview and this method
     * returns {@code 200 OK}. The confirm flag is inspected with a null-safe
     * {@code Boolean.TRUE.equals(...)} test.</p>
     *
     * @param request the validated transaction-add payload; must not be {@code null}
     * @return {@code 201 Created} (with a {@code Location} header and the confirmation carrying the
     *         auto-generated id) when {@code confirm} is {@code true}; otherwise {@code 200 OK}
     *         with the confirm-prompt preview
     */
    @PostMapping
    public ResponseEntity<TransactionAddResponse> addTransaction(
            @Valid @RequestBody final TransactionAddRequest request) {

        // Null-safe confirm discriminator: TRUE commits (201); null/false previews (200).
        final boolean committed = Boolean.TRUE.equals(request.confirm());

        // Log the (non-sensitive) account id and the masked card only; never the full PAN.
        log.info("Transaction add request (CT02): accountId={}, card={}, confirmed={}",
                request.accountId(), maskPan(request.cardNumber()), committed);

        final TransactionAddResponse response = transactionAddService.addTransaction(request);

        if (committed) {
            // A committed add always carries a server-generated id; build the Location header for it.
            final URI location = UriComponentsBuilder.fromPath(RESOURCE_PATH + "/{transactionId}")
                    .buildAndExpand(response.transactionId())
                    .toUri();
            return ResponseEntity.created(location).body(response);
        }
        return ResponseEntity.ok(response);
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
}
