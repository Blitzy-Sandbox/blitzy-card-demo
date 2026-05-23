/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.controller;

import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.service.TransactionAddRequest;
import com.aws.carddemo.service.TransactionAddResult;
import com.aws.carddemo.service.TransactionAddService;
import com.aws.carddemo.service.TransactionDetailResponse;
import com.aws.carddemo.service.TransactionDetailService;
import com.aws.carddemo.service.TransactionListRequest;
import com.aws.carddemo.service.TransactionListResponse;
import com.aws.carddemo.service.TransactionListService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller exposing the CardDemo transaction endpoints — the Java
 * migration of the THREE CICS BMS screens and THREE COBOL programs that
 * collectively own the transaction list / view / add lifecycle:
 *
 * <ul>
 *   <li>{@code app/bms/COTRN00.bms} + {@code app/cbl/COTRN00C.cbl}
 *       (TRANID {@code CT00}, 699 lines) — transaction list (10 rows/page,
 *       with optional account-ID / card-number filters)</li>
 *   <li>{@code app/bms/COTRN01.bms} + {@code app/cbl/COTRN01C.cbl}
 *       (TRANID {@code CT01}, 330 lines) — transaction detail (read-only,
 *       16-character {@code TRAN-ID} primary-key lookup)</li>
 *   <li>{@code app/bms/COTRN02.bms} + {@code app/cbl/COTRN02C.cbl}
 *       (TRANID {@code CT02}, 783 lines) — transaction add (auto-generated
 *       {@code TRAN-ID} via {@code STARTBR / READPREV + 1} parity, 11
 *       empty-field validations preserved verbatim)</li>
 * </ul>
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>All endpoints require authentication; unauthenticated callers receive
 * HTTP 401. The endpoints are not admin-restricted — any authenticated user
 * may list / view / add transactions, mirroring the COBOL workflow where
 * regular operators (USR-TYPE = "U") had access to the {@code CT00 / CT01
 * / CT02} TRANIDs. State-changing requests (the POST) require a CSRF token;
 * missing tokens produce HTTP 403 from Spring Security's filter chain.
 *
 * <ul>
 *   <li>{@code GET /api/transactions} — list transactions (10 rows/page);
 *       optional query parameters {@code page} (zero-based, default 0),
 *       {@code accountId} (11-character filter), and {@code cardNumber}
 *       (16-character filter). Returns a {@link TransactionListJsonResponse}
 *       carrying a list of {@link TransactionSummary} rows plus the page
 *       navigation flags.</li>
 *   <li>{@code GET /api/transactions/{id}} — view a single transaction by
 *       its 16-character {@code TRAN-ID} primary key. Returns a
 *       {@link TransactionDetailJsonResponse} on success (HTTP 200) or
 *       HTTP 404 when the service reports
 *       {@code "Transaction ID NOT found..."} (preserves COBOL
 *       {@code COTRN01C} {@code DFHRESP(NOTFND)} semantics).</li>
 *   <li>{@code POST /api/transactions} — add a new transaction. The
 *       request body is a JSON-serialised {@link TransactionAddRequest}
 *       (the service generates the new 16-character {@code TRAN-ID} via
 *       {@code findTopByOrderByTransactionIdDesc + 1}). On success returns
 *       HTTP 201 Created with a {@link TransactionAddJsonResponse} carrying
 *       the success message embedding the new ID. On validation failure
 *       returns HTTP 400 with the reject message verbatim from the service
 *       (preserves COBOL {@code COTRN02C} reject-message bytes per AAP
 *       §0.10.4).</li>
 * </ul>
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>Financial precision (AAP §0.10.3).</b> The {@code amount} field
 *       is exposed as a {@link BigDecimal} at scale 2 on the wire (matching
 *       the COBOL {@code TRAN-AMT PIC S9(09)V99} field). The controller
 *       never converts to or from {@code float} / {@code double}; the
 *       service preserves the entity's BigDecimal scale verbatim.</li>
 *   <li><b>PCI containment.</b> The {@link Transaction} entity exposes a
 *       full 16-character card number via {@link Transaction#getCardNumber()}.
 *       The controller's projection records preserve the COBOL baseline
 *       byte-for-byte (AAP §0.10.4 — immutable boundaries) so the JSON
 *       response carries the same card-number bytes as the original BMS
 *       map output. A future migration step that introduces PAN masking
 *       at the wire boundary would attach a Jackson serialiser at the
 *       record-field level rather than mutating the entity itself.</li>
 *   <li><b>Service-layer reject-message mirrors.</b> The service constants
 *       are package-private and cannot be referenced from this controller's
 *       sibling package. The relevant literals are duplicated here so the
 *       HTTP-status mapping can dispatch on them. The matching test
 *       {@code TransactionControllerTest} asserts every mapping against the
 *       same literals so drift surfaces loudly (AAP §0.10.10 style
 *       consistency).</li>
 * </ul>
 *
 * @see TransactionListService
 * @see TransactionDetailService
 * @see TransactionAddService
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The Mxx_* constants on TransactionDetailService and TransactionAddService
    // are package-private (no modifier on the `static final String`
    // declarations), so they cannot be referenced from a sibling package. The
    // controller duplicates the literals here so the HTTP-status mapping can
    // dispatch on them. TransactionControllerTest asserts every mapped status
    // against the SAME literals — drift will therefore fail the controller
    // test and the service test together, surfacing the issue loudly.
    //
    // Source:
    //   com.aws.carddemo.service.TransactionDetailService.MSG_TRANSACTION_NOT_FOUND
    //   com.aws.carddemo.service.TransactionAddService.MSG_TRANSACTION_ADD_SUCCESS_FORMAT
    // ------------------------------------------------------------------------

    /** TransactionDetailService reject when the transaction is absent; HTTP 404. */
    static final String MSG_TRANSACTION_NOT_FOUND = "Transaction ID NOT found...";

    /** HTTP 500 message for unexpected service-layer exceptions. Never echoes the cause. */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // COBOL parity constants
    // ------------------------------------------------------------------------

    /**
     * Fixed page size for the list endpoint — matches the COBOL
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10}
     * loop bound in {@code COTRN00C.cbl} (the implicit 10-row table
     * {@code TRAN-REC OCCURS 10 TIMES} on the {@code COTRN0AO} BMS map).
     * Preserved in the Java migration via {@link TransactionListService#PAGE_SIZE}.
     */
    static final int PAGE_SIZE = 10;

    /**
     * Expected character width of the {@code TRAN-ID} path-variable from
     * the {@code TRAN-ID PIC X(16)} field in {@code app/cpy/CVTRA05Y.cpy}.
     * Used in the path-variable validation that rejects malformed IDs with
     * HTTP 400 — preserving the COBOL implicit "16-byte key" contract.
     */
    static final int TRAN_ID_WIDTH = 16;

    // ------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------

    private final TransactionListService transactionListService;
    private final TransactionDetailService transactionDetailService;
    private final TransactionAddService transactionAddService;

    /**
     * Constructs the controller with constructor-injected service collaborators.
     *
     * @param transactionListService   the transaction-list service (TRANID {@code CT00})
     * @param transactionDetailService the transaction-detail service (TRANID {@code CT01})
     * @param transactionAddService    the transaction-add service (TRANID {@code CT02})
     */
    public TransactionController(TransactionListService transactionListService,
                                 TransactionDetailService transactionDetailService,
                                 TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    // ========================================================================
    // GET /api/transactions — list transactions (COTRN00C / TRANID CT00)
    // ========================================================================

    /**
     * Lists transactions, 10 per page. Optional account-ID and card-number
     * filters are supported as URL query parameters; account-ID wins if
     * both are populated (mirrors {@link TransactionListService}'s
     * dispatcher contract).
     *
     * @param page             zero-based page index (default {@code 0})
     * @param accountIdFilter  optional 11-character account-ID filter;
     *                         {@code null} or empty means no account filter
     * @param cardNumberFilter optional 16-character card-number filter;
     *                         {@code null} or empty means no card filter
     * @return HTTP 200 with a {@link TransactionListJsonResponse} carrying
     *         the page of {@link TransactionSummary} rows
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionListJsonResponse> listTransactions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "accountId", required = false) String accountIdFilter,
            @RequestParam(value = "cardNumber", required = false) String cardNumberFilter) {

        TransactionListRequest req = new TransactionListRequest();
        req.setPage(page);
        req.setAccountIdFilter(accountIdFilter);
        req.setCardNumberFilter(cardNumberFilter);

        TransactionListResponse result = transactionListService.listTransactions(req);

        // Project each Transaction entity into a TransactionSummary record so
        // the wire-format response is governed by the controller's projection,
        // not the entity. The COBOL workflow renders a fixed-width 10-row
        // OCCURS table on the COTRN0AO BMS map — the Java migration preserves
        // the per-row field set (TRAN-ID, TRAN-CARD-NUM, TRAN-AMT, TRAN-TYPE,
        // TRAN-CAT-CD, TRAN-DESC, originTimestamp) on each summary.
        List<TransactionSummary> content = result.getTransactions().stream()
                .map(TransactionController::toSummary)
                .collect(Collectors.toUnmodifiableList());

        return ResponseEntity.ok(new TransactionListJsonResponse(
                true,
                content,
                result.getCurrentPage(),
                PAGE_SIZE,
                result.isHasNext(),
                result.isHasPrevious(),
                content.size(),
                null));
    }

    // ========================================================================
    // GET /api/transactions/{id} — view single transaction (COTRN01C / CT01)
    // ========================================================================

    /**
     * Look up a single transaction by its 16-character {@code TRAN-ID}
     * primary key. The path variable is validated for exact 16-character
     * width (preserves the COBOL implicit "16-byte key" contract); a
     * malformed ID produces HTTP 400 without invoking the service.
     *
     * @param transactionId the 16-character {@code TRAN-ID} path variable
     * @return HTTP 200 with the populated {@link TransactionDetailJsonResponse}
     *         on success; HTTP 400 when the path variable is malformed;
     *         HTTP 404 when the service reports
     *         {@link #MSG_TRANSACTION_NOT_FOUND}
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionDetailJsonResponse> getTransaction(
            @PathVariable("id") String transactionId) {

        // Defensive path-variable validation — reject malformed IDs at the
        // boundary so the service does not receive obviously bad inputs.
        // Mirrors the COBOL implicit contract that TRAN-ID is exactly 16
        // characters and numeric (16-digit zero-padded numeric strings for
        // daily transactions; 16-character zero-padded numeric strings for
        // interest transactions generated by CBACT04C).
        if (!isWellFormedTransactionId(transactionId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(TransactionDetailJsonResponse.failure(transactionId,
                            "Transaction ID must be 16 numeric digits"));
        }

        TransactionDetailResponse result = transactionDetailService.getTransaction(transactionId);

        if (result.isSuccess()) {
            return ResponseEntity.ok(TransactionDetailJsonResponse.success(result));
        }

        // Failure path — map the reject message to HTTP status. The only
        // service-level reject path is the NOTFND branch which maps to
        // HTTP 404 Not Found (preserving COBOL COTRN01C DFHRESP(NOTFND)).
        // Any other reject text falls through to HTTP 400 by default
        // (defensive — no other reject text is expected from the service).
        String msg = result.getMessage();
        HttpStatus status = MSG_TRANSACTION_NOT_FOUND.equals(msg)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(TransactionDetailJsonResponse.failure(transactionId, msg));
    }

    // ========================================================================
    // POST /api/transactions — add a new transaction (COTRN02C / CT02)
    // ========================================================================

    /**
     * Adds a new transaction record. The service generates the new
     * 16-character {@code TRAN-ID} via {@code findTopByOrderByTransactionIdDesc
     * + 1} and embeds it in the success-confirmation message
     * ({@code "Transaction added successfully. Your Transaction ID is XXX."}).
     *
     * @param request the JSON-serialised {@link TransactionAddRequest}
     * @return HTTP 201 with the success message on a happy path;
     *         HTTP 400 with the COBOL-equivalent reject message on any
     *         validation failure (the service's 11-empty-field cascade plus
     *         numeric / format / semantic-date validations)
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionAddJsonResponse> addTransaction(
            @RequestBody TransactionAddRequest request) {

        TransactionAddResult result = transactionAddService.addTransaction(request);

        String requestAccountId = (request == null) ? null : request.getAccountId();
        // -----------------------------------------------------------------
        // AAP §0.10.5 — PCI/PAN exposure mitigation.
        //
        // The add-transaction response echoes the request's card number to
        // let the client confirm the persisted record. The OnlineTransaction
        // E2E test (step5_addTransaction_validRequest_returns201AndAssignsTransactionId)
        // explicitly asserts the response MUST NOT expose any unmasked
        // 16-digit Visa PAN from the seeded range
        // 4111111111111101-4111111111111150 — i.e., the card number echoed
        // back must be masked at the HTTP boundary even though the database
        // and service-layer DTOs retain the full PAN for downstream
        // processing (the COBOL CICS COMMAREA carries the unmasked PAN
        // between programs, and AAP §0.10.4 preserves that for the
        // record-layout-immutable contract).
        //
        // The masking applies last-4-only — replacing the first 12 of a
        // 16-digit PAN with asterisks ("************1234"). The
        // {@link #maskPanLastFour(String)} helper preserves non-PAN
        // strings (null, blank, non-16-digit values) verbatim so the
        // {@link TransactionAddRequest#getCardNumber()} validation reject
        // paths (which surface the input value in error messages) still
        // function and the COBOL "Card Number must be a 16-digit number"
        // reject is reachable unchanged.
        // -----------------------------------------------------------------
        String requestCardNumber = (request == null) ? null : request.getCardNumber();
        String maskedCardNumber = maskPanLastFour(requestCardNumber);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new TransactionAddJsonResponse(
                            true,
                            requestAccountId,
                            maskedCardNumber,
                            result.getMessage()));
        }

        // Failure path — every TransactionAddService reject is a validation
        // failure mapping to HTTP 400. The reject reason text comes from the
        // service verbatim per AAP §0.10.4 (immutable boundaries). The
        // card-number echo is masked the same way as the happy-path branch
        // so a rejected POST cannot leak the unmasked PAN that the caller
        // submitted.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new TransactionAddJsonResponse(
                        false,
                        requestAccountId,
                        maskedCardNumber,
                        result.getMessage()));
    }

    /**
     * Returns the last-four-digit masked form of a 16-character all-digit
     * card number — the canonical PCI / AAP §0.10.5 PAN-masking shape used
     * by the add-transaction response.
     *
     * <p>For an input PAN {@code 4111111111111101} the returned string is
     * {@code ************1101} (12 asterisks followed by the last 4
     * digits). Inputs that do NOT match the 16-digit all-numeric shape
     * (including {@code null}, blank, partially-digit, and lengths other
     * than 16) are returned verbatim — this preserves the existing
     * controller-test reject-path assertions where the request-validation
     * surface echoes the malformed input back to the client with the
     * COBOL-equivalent reject message ("Card Number must be a 16-digit
     * number..."), and it preserves the {@code null} case where the
     * request body omitted the field entirely.
     *
     * <p>The method is package-private (no access modifier) so the
     * controller-test suite can exercise the masking logic directly via
     * {@code TransactionController.maskPanLastFour(...)} without requiring
     * a full HTTP round-trip — matching the
     * {@code TransactionPostingProcessor}'s package-private helper
     * convention used elsewhere in the migrated codebase.
     *
     * @param pan the input card number; may be {@code null} or any
     *            length / character composition
     * @return the masked form when the input is a 16-character all-digit
     *         PAN; the original input otherwise (including {@code null})
     */
    static String maskPanLastFour(String pan) {
        if (pan == null || pan.length() != 16) {
            return pan;
        }
        for (int i = 0; i < pan.length(); i++) {
            if (!Character.isDigit(pan.charAt(i))) {
                return pan;
            }
        }
        return "************" + pan.substring(12);
    }

    // ========================================================================
    // @ExceptionHandler — unexpected service failures
    // ========================================================================

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer. Returns HTTP 500 with a sanitised body — the underlying
     * exception detail must never leak into the HTTP response (AAP §0.10.5
     * applied to error paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+
     * subtype {@code AuthorizationDeniedException}) is RE-THROWN so Spring
     * Security's {@code ExceptionTranslationFilter} can map it to HTTP 403.
     *
     * @param ex the caught exception; not echoed in the response
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorJsonResponse> handleServiceFailure(RuntimeException ex) {
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
    }

    // ========================================================================
    // Private mapping helpers
    // ========================================================================

    /**
     * Validates that a candidate transaction identifier matches the COBOL
     * {@code TRAN-ID PIC X(16)} contract: exactly 16 ASCII digits.
     *
     * @param transactionId the candidate identifier; may be {@code null}
     * @return {@code true} when the identifier is non-null, exactly 16
     *         characters, and contains only ASCII digits 0-9; {@code false}
     *         otherwise
     */
    private static boolean isWellFormedTransactionId(String transactionId) {
        if (transactionId == null || transactionId.length() != TRAN_ID_WIDTH) {
            return false;
        }
        for (int i = 0; i < transactionId.length(); i++) {
            char c = transactionId.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Projects a {@link Transaction} entity into a {@link TransactionSummary}
     * record for the list endpoint's wire-format response. The projection
     * carries the seven fields that the COBOL workflow renders on each row
     * of the {@code COTRN0AO} BMS map (the {@code TRAN-REC OCCURS 10 TIMES}
     * table fields):
     * <ul>
     *   <li>{@code TRAN-ID} → {@code transactionId}</li>
     *   <li>{@code TRAN-CARD-NUM} → {@code cardNumber}</li>
     *   <li>{@code TRAN-AMT} → {@code amount} (BigDecimal scale 2)</li>
     *   <li>{@code TRAN-TYPE-CD} → {@code transactionType}</li>
     *   <li>{@code TRAN-CAT-CD} → {@code transactionCategoryCode}</li>
     *   <li>{@code TRAN-DESC} → {@code description}</li>
     *   <li>{@code TRAN-ORIG-TS} → {@code originTimestamp}</li>
     * </ul>
     *
     * @param transaction the hydrated {@link Transaction} entity; never {@code null}
     * @return a fresh {@link TransactionSummary} carrying the per-row fields
     */
    private static TransactionSummary toSummary(Transaction transaction) {
        // AAP §0.10.5 — PAN masking on the HTTP boundary. The Transaction
        // entity carries the unmasked TRAN-CARD-NUM for downstream COBOL
        // CICS COMMAREA semantics (preserved per AAP §0.10.4 immutable
        // boundaries), but the HTTP wire format must mask the value to
        // last-4-only form. See {@link #maskPanLastFour(String)} for the
        // mask shape contract (16-digit all-numeric → "************" +
        // last 4; non-PAN values pass through verbatim).
        return new TransactionSummary(
                transaction.getTransactionId(),
                maskPanLastFour(transaction.getCardNumber()),
                transaction.getAmount(),
                transaction.getTransactionTypeCode(),
                transaction.getTransactionCategoryCode(),
                transaction.getDescription(),
                transaction.getOriginTimestamp());
    }

    // ========================================================================
    // Response DTOs — inner records governing the wire-format contract
    // ========================================================================

    /**
     * Per-row projection of a {@link Transaction} entity for the list
     * endpoint. Carries the seven fields rendered on each {@code TRAN-REC
     * OCCURS 10 TIMES} row of the {@code COTRN0AO} BMS map.
     *
     * @param transactionId           16-character {@code TRAN-ID}
     * @param cardNumber              16-character {@code TRAN-CARD-NUM}
     * @param amount                  {@code TRAN-AMT} as BigDecimal scale 2
     * @param transactionType         2-character {@code TRAN-TYPE-CD}:
     *                                {@code "01"}, {@code "02"}, {@code "03"}, etc.
     * @param transactionCategoryCode 4-character {@code TRAN-CAT-CD}: {@code "0001"} etc.
     * @param description             100-character {@code TRAN-DESC}
     * @param originTimestamp         26-character {@code TRAN-ORIG-TS}
     */
    public static record TransactionSummary(
            String transactionId,
            String cardNumber,
            // ---------------------------------------------------------------
            // AAP §0.10.3 — Monetary fields on the HTTP boundary
            //
            // The TRAN-AMT field carries scale-2 BigDecimal values that must
            // be transmitted verbatim — never coerced through Java
            // float/double, never truncated to lower scale on the wire.
            // Jackson serialises {@link BigDecimal} as a JSON number by
            // default, preserving the scale set by the source field
            // (i.e., a {@code BigDecimal} carrying {@code "100.50"} emits
            // the JSON literal {@code 100.50}, not the scientific-notation
            // {@code 1.005E2} or the trimmed {@code 100.5}). Combined with
            // the {@link com.aws.carddemo.config.JacksonConfig} customizer
            // — which enables
            // {@link com.fasterxml.jackson.databind.DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}
            // and an exact-mode
            // {@link com.fasterxml.jackson.databind.node.JsonNodeFactory#withExactBigDecimals(boolean)
            // JsonNodeFactory} on the auto-configured {@code ObjectMapper}
            // — round-trip clients see the verbatim scale-2 value at every
            // boundary (entity ↔ DTO ↔ JSON wire ↔ test JsonNode tree).
            // The {@code @JsonFormat(shape = STRING)} annotation that
            // previously routed the BigDecimal through {@code toString} is
            // intentionally absent: the COBOL {@code PIC S9(09)V99}
            // contract is honoured at the numeric level, not via string
            // coercion. The E2E parity assertion
            // ({@code OnlineTransactionE2ETest.step4_*.amount.isNumber()})
            // verifies the wire format is a JSON number.
            // ---------------------------------------------------------------
            BigDecimal amount,
            String transactionType,
            String transactionCategoryCode,
            String description,
            String originTimestamp) {
    }

    /**
     * Wire-format response for {@code GET /api/transactions}. Carries the
     * paged list of {@link TransactionSummary} rows plus the page navigation
     * flags from the underlying {@link TransactionListResponse}.
     *
     * @param success       always {@code true} on the happy path (the
     *                      transaction-list service has no reject path)
     * @param content       the page of summaries (empty list for empty result)
     * @param pageNumber    the zero-based page index from the service response
     * @param pageSize      the page size (always {@link #PAGE_SIZE} = 10)
     * @param hasNext       {@code true} when there is at least one record
     *                      beyond the end of the current page
     * @param hasPrevious   {@code true} when there is at least one record
     *                      before the start of the current page
     * @param totalElements the count of summaries on the current page (used
     *                      by clients to render "showing X of Y" labels;
     *                      {@code 0} for empty result)
     * @param message       reserved for future failure paths; {@code null}
     *                      on success
     */
    public static record TransactionListJsonResponse(
            boolean success,
            List<TransactionSummary> content,
            int pageNumber,
            int pageSize,
            boolean hasNext,
            boolean hasPrevious,
            long totalElements,
            String message) {
    }

    /**
     * Wire-format response for {@code GET /api/transactions/{id}}. Carries
     * the full transaction-detail field set on success or only the failure
     * envelope on the NOTFND reject path.
     *
     * @param success                 {@code true} on the happy path; {@code false}
     *                                on the NOTFND reject or malformed-ID reject
     * @param transactionId           the 16-character {@code TRAN-ID} echoed
     *                                from the path variable; populated even on
     *                                the failure branch so clients can correlate
     * @param accountId               reserved for the cross-reference-resolved
     *                                account ID; the COBOL workflow surfaces this
     *                                via the {@code CXACAIX} alternate-index walk
     *                                — null on the failure path
     * @param cardNumber              16-character {@code TRAN-CARD-NUM};
     *                                null on the failure path
     * @param amount                  {@code TRAN-AMT} BigDecimal scale 2;
     *                                null on the failure path
     * @param transactionType         2-character {@code TRAN-TYPE-CD};
     *                                null on the failure path
     * @param transactionCategoryCode 4-character {@code TRAN-CAT-CD};
     *                                null on the failure path
     * @param source                  10-character {@code TRAN-SOURCE};
     *                                null on the failure path
     * @param description             100-character {@code TRAN-DESC};
     *                                null on the failure path
     * @param merchantId              9-digit {@code TRAN-MERCHANT-ID};
     *                                null on the failure path
     * @param merchantName            50-character {@code TRAN-MERCHANT-NAME};
     *                                null on the failure path
     * @param merchantCity            50-character {@code TRAN-MERCHANT-CITY};
     *                                null on the failure path
     * @param merchantZip             10-character {@code TRAN-MERCHANT-ZIP};
     *                                null on the failure path
     * @param originTimestamp         26-character {@code TRAN-ORIG-TS};
     *                                null on the failure path
     * @param processTimestamp        26-character {@code TRAN-PROC-TS};
     *                                null on the failure path
     * @param message                 reject message on failure; null on success
     */
    public static record TransactionDetailJsonResponse(
            boolean success,
            String transactionId,
            String accountId,
            String cardNumber,
            // ---------------------------------------------------------------
            // AAP §0.10.3 — Monetary fields on the HTTP boundary
            //
            // Same contract as TransactionSummary#amount above: emit
            // TRAN-AMT as a JSON number (NOT a JSON string) so the wire
            // preserves the COBOL PIC S9(09)V99 scale-2 contract verbatim.
            // The {@link com.aws.carddemo.config.JacksonConfig} customizer
            // pins the auto-configured {@code ObjectMapper}'s
            // {@code JsonNodeFactory} to exact-BigDecimals mode so the
            // E2E test's {@code body.get("amount").asText()} parses back
            // to {@code BigDecimal} with scale=2 — required by
            // {@code OnlineTransactionE2ETest.step4_*}'s
            // {@code amount.isNumber()} +
            // {@code new BigDecimal(amt.asText()).scale() == 2} assertion
            // pair.
            // ---------------------------------------------------------------
            BigDecimal amount,
            String transactionType,
            String transactionCategoryCode,
            String source,
            String description,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String originTimestamp,
            String processTimestamp,
            String message) {

        /**
         * Builds a populated success response from a hydrated
         * {@link TransactionDetailResponse}. The {@code accountId} field is
         * left {@code null} because the underlying service does not surface
         * the cross-reference-resolved account ID — a subsequent migration
         * step would populate it via a {@code CardXrefRepository} lookup.
         *
         * @param r the service response with all transaction fields populated
         * @return a success-flagged JSON response with all fields hydrated
         */
        static TransactionDetailJsonResponse success(TransactionDetailResponse r) {
            // AAP §0.10.5 — PAN masking on the HTTP boundary. The
            // TransactionDetailResponse carries the unmasked
            // TRAN-CARD-NUM for downstream COBOL CICS COMMAREA semantics
            // (preserved per AAP §0.10.4 immutable boundaries), but the
            // HTTP wire format must mask the value to last-4-only form.
            // See {@link TransactionController#maskPanLastFour(String)}
            // for the mask shape contract.
            return new TransactionDetailJsonResponse(
                    true,
                    r.getTransactionId(),
                    null, // accountId — not surfaced by TransactionDetailService
                    maskPanLastFour(r.getCardNumber()),
                    r.getAmount(),
                    r.getTransactionTypeCode(),
                    r.getTransactionCategoryCode(),
                    r.getSource(),
                    r.getDescription(),
                    r.getMerchantId(),
                    r.getMerchantName(),
                    r.getMerchantCity(),
                    r.getMerchantZip(),
                    r.getOriginTimestamp(),
                    r.getProcessTimestamp(),
                    null);
        }

        /**
         * Builds a failure response carrying only the transaction-ID echo
         * and the reject message; all other fields are {@code null}.
         *
         * @param transactionId the requested transaction ID (path variable)
         * @param message       the reject message
         * @return a failure-flagged JSON response
         */
        static TransactionDetailJsonResponse failure(String transactionId, String message) {
            return new TransactionDetailJsonResponse(
                    false,
                    transactionId,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    message);
        }
    }

    /**
     * Wire-format response for {@code POST /api/transactions}. Reflects the
     * account ID and card number from the request (so the client can confirm
     * the persisted entity) plus the service's success or reject message
     * (which embeds the auto-generated {@code TRAN-ID} on the happy path).
     *
     * @param success    {@code true} on the happy path; {@code false} on any reject
     * @param accountId  the account ID echoed from the request body
     * @param cardNumber the card number echoed from the request body
     * @param message    the service's success or reject message
     */
    public static record TransactionAddJsonResponse(
            boolean success,
            String accountId,
            String cardNumber,
            String message) {
    }

    /**
     * Wire-format response for unexpected service-layer failures. Returned by
     * the {@link #handleServiceFailure(RuntimeException)} exception handler.
     *
     * @param success always {@code false}
     * @param message a sanitised generic message ({@link #MSG_INTERNAL_ERROR})
     */
    public static record ErrorJsonResponse(boolean success, String message) {
    }
}
