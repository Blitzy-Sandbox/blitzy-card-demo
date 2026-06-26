package com.carddemo.controller;

import com.carddemo.dto.TransactionDto;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionDetailService;
import com.carddemo.service.TransactionListService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless REST surface for the transaction screens of the AWS CardDemo
 * application, rooted at {@code /api/transactions}.
 *
 * <p>This controller is the HTTP entry point for the three CICS
 * pseudo-conversational transaction programs translated at source commit
 * {@code 27d6c6f}:</p>
 *
 * <ul>
 *   <li>{@code GET /api/transactions} &larr; {@code COTRN00C} (transaction
 *       {@code CT00}, transaction list), delegating to
 *       {@link TransactionListService}. The legacy PF7/PF8 browse navigation is
 *       exposed as a single zero-based {@code page} request parameter, and the
 *       {@code TRNIDIN} screen filter is exposed as the optional
 *       {@code transactionId} query parameter; the page size is fixed inside the
 *       service.</li>
 *   <li>{@code GET /api/transactions/{id}} &larr; {@code COTRN01C} (transaction
 *       {@code CT01}, transaction detail), delegating to
 *       {@link TransactionDetailService}.</li>
 *   <li>{@code POST /api/transactions} &larr; {@code COTRN02C} (transaction
 *       {@code CT02}, transaction add), delegating to
 *       {@link TransactionAddService}, whose auto-identifier generation and
 *       confirmation handling reproduce the legacy {@code ADD-TRANSACTION}
 *       flow.</li>
 * </ul>
 *
 * <p>The controller is a thin HTTP adapter: it carries no business logic and no
 * data access. Request validation is declarative ({@link Min} on the page
 * parameter, enforced by the class-level {@link Validated}, and {@link Valid} on
 * the add body); domain errors raised by the services are translated to HTTP
 * status codes by the centralized {@code GlobalExceptionHandler}. All routes
 * require an authenticated caller, enforced by the application security
 * configuration.</p>
 */
@RestController
@RequestMapping("/api/transactions")
@Validated
public class TransactionController {

    private final TransactionListService transactionListService;
    private final TransactionDetailService transactionDetailService;
    private final TransactionAddService transactionAddService;

    /**
     * Creates the controller with its three collaborating services.
     *
     * @param transactionListService   service backing the paginated
     *                                  transaction-list endpoint
     * @param transactionDetailService service backing the transaction-detail
     *                                  endpoint
     * @param transactionAddService    service backing the transaction-add
     *                                  endpoint
     */
    public TransactionController(TransactionListService transactionListService,
                                 TransactionDetailService transactionDetailService,
                                 TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Returns one page of transactions, optionally filtered ({@code COTRN00C} /
     * {@code CT00}).
     *
     * @param transactionId optional transaction-id filter ({@code TRNIDIN});
     *                      {@code null} means no filter
     * @param page          the zero-based page index; must not be negative
     * @return {@code 200 OK} with the requested page of transaction summaries
     */
    @GetMapping
    public ResponseEntity<TransactionDto.ListResponse> listTransactions(
            @RequestParam(name = "transactionId", required = false) String transactionId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(transactionListService.listTransactions(transactionId, page));
    }

    /**
     * Returns the detail view of a single transaction ({@code COTRN01C} /
     * {@code CT01}).
     *
     * @param id the transaction identifier from the request path
     * @return {@code 200 OK} with the transaction detail
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDto.Detail> getTransaction(@PathVariable("id") String id) {
        return ResponseEntity.ok(transactionDetailService.getTransaction(id));
    }

    /**
     * Validates and adds a new transaction ({@code COTRN02C} / {@code CT02}).
     *
     * @param request the validated add body carrying the new transaction values
     * @return {@code 201 Created} with the persisted transaction detail
     */
    @PostMapping
    public ResponseEntity<TransactionDto.Detail> addTransaction(
            @Valid @RequestBody TransactionDto.AddRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionAddService.addTransaction(request));
    }
}
