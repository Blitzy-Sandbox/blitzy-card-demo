package com.cardemo.controller;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the three AWS CardDemo CICS BMS 3270 <strong>transaction screens</strong>:
 * <strong>Transaction List</strong>, <strong>Transaction View (detail)</strong> and
 * <strong>Transaction Add</strong>. It exposes the three routes under
 * <strong>{@code /api/transactions/*}</strong> (list, detail, add) and is a thin adapter over
 * {@link TransactionListService}, {@link TransactionDetailService} and {@link TransactionAddService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L649: <em>{@code controller/TransactionController.java}
 * CREATE &larr; {@code app/bms/COTRN00.bms}, {@code COTRN01.bms}, {@code COTRN02.bms} &mdash;
 * "GET/POST /api/transactions/*"</em>) and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST contract
 * translation). It preserves features <strong>F-010</strong> (Transaction List),
 * <strong>F-011</strong> (Transaction View) and <strong>F-012</strong> (Transaction Add) without
 * expansion (Minimal Change Clause, AAP&nbsp;&sect;0.7.1).</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COTRN00.bms}</strong> &mdash; the Transaction List mapset
 *       ({@code COTRN00} / map {@code COTRN0A}), driven by CICS program {@code COTRN00C},
 *       transaction <strong>{@code CT00}</strong>. The browse painted a fixed row array of
 *       {@code TRNID01}&hellip;{@code TRNID10} &mdash; <strong>exactly ten rows per page</strong>
 *       &mdash; optionally positioned by the {@code TRNIDIN} starting-id filter.</li>
 *   <li><strong>{@code app/bms/COTRN01.bms}</strong> &mdash; the Transaction View mapset
 *       ({@code COTRN01} / map {@code COTRN1A}), driven by CICS program {@code COTRN01C},
 *       transaction <strong>{@code CT01}</strong>. A single keyed read of the {@code TRANSACT}
 *       VSAM KSDS by the sixteen-character transaction id.</li>
 *   <li><strong>{@code app/bms/COTRN02.bms}</strong> &mdash; the Transaction Add mapset
 *       ({@code COTRN02} / map {@code COTRN2A}), driven by CICS program {@code COTRN02C},
 *       transaction <strong>{@code CT02}</strong>. A two-step pseudo-conversational add that
 *       auto-generates the next transaction id (browse-to-end of {@code TRANSACT} + increment) and,
 *       on the PF4 confirm, writes the new record.</li>
 * </ul>
 * <p>Their symbolic maps live under {@code app/cpy-bms/} and were migrated into
 * {@link TransactionDto} (the {@code COPY CSSETATY} field contract) and its nested
 * {@link TransactionDto.TransactionListItem} browse row. This controller never re-declares those
 * structures and never copies COBOL/BMS text &mdash; only the screen <em>behavior</em> is reproduced,
 * by delegation to the three transaction services.</p>
 *
 * <h2>Thin-adapter contract (AAP &sect;0.3.3)</h2>
 * <p>This controller contains <strong>no business logic and no data access</strong>. The
 * start-key-filtered paginated browse, the single keyed read, the auto-id generation
 * (browse-to-end + increment) and the fail-fast validation cascade with the confirm/duplicate
 * handling all live in the services. Each handler is a pure delegation that adds <strong>zero</strong>
 * logic.</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND MAP} / {@code RECEIVE MAP} &rarr; request/response DTO.</strong> The 3270
 *       map paint and field harvest collapse into Jackson (de)serialization of {@link TransactionDto}
 *       (and, for the list, its nested {@link TransactionDto.TransactionListItem} rows). Screen chrome,
 *       message lines and BMS control bytes have no REST analogue and are not modeled
 *       (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID(...) COMMAREA} &rarr; stateless REST.</strong> The CICS
 *       pseudo-conversational hand-off (the {@code COCOM01Y} COMMAREA carrying state across turns) is
 *       replaced by stateless HTTP; this controller holds no conversational state
 *       (AAP&nbsp;&sect;0.1.2).</li>
 *   <li><strong>PF07 (page-up) / PF08 (page-down) &rarr; {@code page&plusmn;1} query parameter.</strong>
 *       The {@code COTRN00C} backward/forward browse keys map to the client re-calling
 *       {@code GET /api/transactions?page=N-1} / {@code ?page=N+1}; paging is stateless navigation, not
 *       server-held cursor state. The page size is fixed at <strong>ten rows</strong>
 *       ({@link TransactionDto#ROWS_PER_PAGE}) and {@code page} is <strong>one-based</strong>.</li>
 *   <li><strong>Row-selection {@code 'S'} and the two-step PF4 confirm &rarr; distinct client-driven
 *       REST calls.</strong> Selecting a list row to view ({@code COTRN00C} {@code XCTL} to
 *       {@code COTRN01C}) becomes the client calling the detail endpoint; the {@code COTRN02C} two-step
 *       PF4-confirm add collapses into a single {@code POST} whose {@code confirm} field drives
 *       commit-vs-preview &mdash; all interpreted by the services, never here (AAP&nbsp;&sect;0.1.2).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the services (AAP &sect;0.7.2)</h2>
 * <p>The services are the authoritative source for validation <em>order</em> and verbatim COBOL
 * message text ({@code TransactionAddService}'s fail-fast cascade and {@code TransactionDetailService}'s
 * blank-id message in particular). Request bodies are therefore bound as a plain {@code @RequestBody}
 * <strong>without</strong> {@code @Valid}: applying bean validation here would pre-empt those ordered
 * messages with a generic {@code MethodArgumentNotValidException} and break message parity (consistent
 * with the other CardDemo controllers, e.g. {@code AccountController}). The {@link TransactionDto}
 * Jakarta constraints remain the documented field contract (the {@code COPY CSSETATY} translation), but
 * the service is the runtime source of truth.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. The services throw and this controller
 * lets propagate the typed exceptions translated by the centralized {@code @RestControllerAdvice} in
 * {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (the verbatim COBOL edit messages &mdash; blank/non-numeric tran id, invalid
 *       row selection, or any failed add-field edit).</li>
 *   <li>{@code com.cardemo.exception.RecordNotFoundException} &rarr; HTTP&nbsp;<strong>404 Not
 *       Found</strong> ("Transaction ID NOT found..." on the detail keyed read).</li>
 *   <li>{@code com.cardemo.exception.DuplicateRecordException} &rarr; HTTP&nbsp;<strong>409
 *       Conflict</strong> when the add's generated transaction id collides with an existing row.</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Transaction amounts on {@link TransactionDto} (and its {@link TransactionDto.TransactionListItem}
 * rows) are {@link java.math.BigDecimal} of scale 2, SIGNED &mdash; <strong>never</strong>
 * {@code float} or {@code double}. This controller passes the DTO through untouched and performs no
 * arithmetic, so decimal precision is preserved end to end.</p>
 *
 * <h2>Security</h2>
 * <p>All {@code /api/transactions/*} endpoints require authentication; access is governed by the Spring
 * Security configuration in {@code config/SecurityConfig}. No security infrastructure and no
 * method-security annotations are introduced in this thin adapter.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied into
 * this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see TransactionListService
 * @see TransactionDetailService
 * @see TransactionAddService
 * @see TransactionDto
 * @see TransactionDto.TransactionListItem
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    /** Service owning the paginated transaction browse &mdash; ten rows/page ({@code COTRN00C}). */
    private final TransactionListService transactionListService;

    /** Service owning the single keyed transaction read ({@code COTRN01C}). */
    private final TransactionDetailService transactionDetailService;

    /**
     * Service owning the {@code @Transactional} transaction add ({@code COTRN02C}): the fail-fast
     * validation cascade, the auto-generated transaction id (browse-to-end + increment) and the
     * duplicate-key check.
     */
    private final TransactionAddService transactionAddService;

    /**
     * Constructs the controller with the three transaction services injected by Spring (constructor
     * injection; all fields are {@code final}; no field {@code @Autowired}).
     *
     * @param transactionListService   the paginated transaction-list browse service ({@code COTRN00C})
     * @param transactionDetailService the single-transaction detail/view service ({@code COTRN01C})
     * @param transactionAddService    the transactional transaction-add service ({@code COTRN02C})
     */
    public TransactionController(final TransactionListService transactionListService,
                                 final TransactionDetailService transactionDetailService,
                                 final TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Returns one page of the transaction-list browse, reproducing {@code COTRN00C}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/transactions}.</p>
     *
     * <p>The optional {@code transactionId} query parameter is the browse <em>start-key filter</em> the
     * operator typed into the {@code TRNIDIN} field; when omitted the browse starts at the beginning of
     * the file. The {@code page} query parameter is the <strong>one-based</strong> page index (default
     * {@code 1}); the service caps each page at <strong>ten rows</strong>
     * ({@link TransactionDto#ROWS_PER_PAGE}) and normalizes out-of-range page numbers. The controller
     * assembles these query parameters into a {@link TransactionDto} request &mdash; the COBOL
     * screen-field&nbsp;&rarr;&nbsp;COMMAREA mapping &mdash; and performs no page arithmetic itself; the
     * service converts the one-based page indicator to its zero-based browse index.</p>
     *
     * @param transactionId optional starting-id filter ({@code COTRN00C TRNIDIN}); when supplied it must
     *                      be all digits or the service raises a 400
     * @param page          the one-based page index to return (default {@code 1}, ten rows/page)
     * @return {@code 200 OK} carrying a {@link TransactionDto} whose {@code transactions} list holds up
     *         to ten {@link TransactionDto.TransactionListItem} rows for the requested {@code pageNumber}
     *         (possibly empty when the browse yields no records)
     * @throws com.cardemo.exception.ValidationException if the supplied filter is not numeric (rendered
     *         as HTTP&nbsp;400 by {@code config/WebConfig}); propagated, not caught
     */
    // COBOL substitution: COTRN00C SEND MAP('COTRN0A') painted a fixed TRNID01..TRNID10 row array; its
    // STARTBR(GTEQ)/READNEXT/READPREV browse + PF07 (page-up) / PF08 (page-down) keys map to a stateless
    // GET with a one-based `page` query param at 10 rows/page -- the client requests page-1 / page+1
    // (AAP §0.1.2). RECEIVE MAP('COTRN0A') field harvest (TRNIDIN start-key + PAGENUM) -> the query
    // params bound below; RETURN TRANSID('CT00') COMMAREA -> stateless REST (no conversational state).
    @GetMapping
    public ResponseEntity<TransactionDto> listTransactions(
            @RequestParam(required = false) final String transactionId,
            @RequestParam(defaultValue = "1") final int page) {
        // Query params -> TransactionDto request: the COBOL screen-field -> COMMAREA mapping. The 1-based
        // `page` is conveyed verbatim as the PAGENUM (PIC X(8)) character indicator -- the service's
        // resolvePageIndex parses it and converts the 1-based page to its 0-based browse index, so the
        // controller adds no page arithmetic. A null filter maps to the LOW-VALUES start key in-service.
        final TransactionDto request = new TransactionDto();
        request.setTransactionIdFilter(transactionId);
        request.setPageNumber(Integer.toString(page));
        // Pure delegation: TransactionListService owns the 10-rows/page cap, the ascending tran-id browse
        // order and the start-key filter edit (non-numeric filter -> ValidationException -> 400).
        return ResponseEntity.ok(transactionListService.listTransactions(request));
    }

    /**
     * Returns the detail of a single transaction, reproducing {@code COTRN01C}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/transactions/{id}}.</p>
     *
     * <p>The transaction id is the authoritative read key (path variable). It is passed through
     * unchanged to {@link TransactionDetailService#getTransaction(String)}, which performs the
     * {@code PROCESS-ENTER-KEY} blank edit (raising the verbatim "Tran ID can NOT be empty..." message)
     * and the {@code READ-TRANSACT-FILE} keyed read in the exact COBOL order. The controller performs no
     * validation of its own so a malformed id reaches the service and surfaces the verbatim message
     * rather than a generic framework error (parity, AAP&nbsp;&sect;0.7.2).</p>
     *
     * @param id the transaction id to read ({@code COTRN01C TRNIDIN}); the sole read key
     * @return {@code 200 OK} carrying the populated single-transaction {@link TransactionDto}
     * @throws com.cardemo.exception.ValidationException     if the transaction id is blank
     *         (HTTP&nbsp;400); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no transaction exists for the id
     *         (HTTP&nbsp;404 / FILE STATUS {@code '23'}); propagated, not caught
     */
    // COBOL substitution: COTRN01C EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID) ->
    // TransactionDetailService.getTransaction keyed on the 16-char transaction id (the path variable).
    // RECEIVE MAP('COTRN1A') field harvest -> the {id} path binding; SEND MAP('COTRN1A') ->
    // TransactionDto JSON; RETURN TRANSID('CT01') COMMAREA -> stateless GET. A blank id surfaces the
    // verbatim ValidationException (400); a missing record surfaces RecordNotFoundException (404).
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDto> getTransaction(@PathVariable("id") final String id) {
        return ResponseEntity.ok(transactionDetailService.getTransaction(id));
    }

    /**
     * Adds a new transaction, reproducing the server-side core of {@code COTRN02C}.
     *
     * <p><strong>Endpoint:</strong> {@code POST /api/transactions}.</p>
     *
     * <p>The pseudo-conversational "fill the form&nbsp;&rarr;&nbsp;PF4-confirm&nbsp;&rarr;&nbsp;write"
     * choreography collapses into a single {@code POST}. The new-transaction fields travel in the
     * request body, which also carries the single-character {@code confirm} flag that drives the
     * {@code COTRN02C} confirm flow &mdash; the service is authoritative for preview-vs-commit semantics.
     * The body is bound as {@code @RequestBody} <strong>without</strong> {@code @Valid}: the service owns
     * the ordered fail-fast verbatim COBOL edit cascade, so applying bean validation here would pre-empt
     * those messages and break message parity (AAP&nbsp;&sect;0.7.2, consistent with the other CardDemo
     * controllers). The transaction id is <em>auto-generated</em> by the service (browse-to-end of
     * {@code TRANSACT} + increment) and returned on the response DTO; the single {@code WRITE} is one
     * {@code @Transactional} unit, so the insert commits or rolls back atomically.</p>
     *
     * <p>HTTP&nbsp;<strong>201 Created</strong> is returned on a committed add: a new transaction
     * resource (identified by the auto-generated id) is created from the client's perspective.</p>
     *
     * @param request the new-transaction payload bound from the JSON body &mdash; carries the
     *                {@code confirm} (Y/N) flag that drives the COTRN02C confirm flow; never {@code null}
     * @return {@code 201 Created} carrying the created {@link TransactionDto} with the auto-generated
     *         {@link TransactionDto#getTransactionId() transactionId}
     * @throws com.cardemo.exception.ValidationException      if any input edit in the fail-fast cascade
     *         fails (HTTP&nbsp;400, verbatim COBOL message); propagated, not caught
     * @throws com.cardemo.exception.DuplicateRecordException if the generated transaction id collides
     *         with an existing row (HTTP&nbsp;409); propagated, not caught
     */
    // COBOL substitution: COTRN02C two-step PF4-confirm add -> a single POST. RECEIVE MAP('COTRN2A')
    // field harvest -> @RequestBody TransactionDto; the multi-turn confirm screen collapses into the
    // single `confirm` (Y/N) field on the body, interpreted by the service (preview vs commit). SEND
    // MAP('COTRN2A') -> TransactionDto JSON; RETURN TRANSID('CT02') COMMAREA -> stateless POST. Bound
    // WITHOUT @Valid so the service's ordered fail-fast cascade produces the verbatim messages (parity,
    // AAP §0.7.2). Pure delegation: the auto-id generation (browse-to-end + increment) and the single
    // @Transactional WRITE live in TransactionAddService; a duplicate id -> DuplicateRecordException (409).
    @PostMapping
    public ResponseEntity<TransactionDto> addTransaction(@RequestBody final TransactionDto request) {
        final TransactionDto created = transactionAddService.addTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
