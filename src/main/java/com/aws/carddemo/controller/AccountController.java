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

import com.aws.carddemo.service.AccountUpdateRequest;
import com.aws.carddemo.service.AccountUpdateResult;
import com.aws.carddemo.service.AccountUpdateService;
import com.aws.carddemo.service.AccountViewResponse;
import com.aws.carddemo.service.AccountViewService;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * REST controller exposing the CardDemo account-view and account-update
 * endpoints — the Java migration of the CICS BMS screens
 * {@code app/bms/COACTVW.bms} (Account View) and {@code app/bms/COACTUP.bms}
 * (Account Update) and their COBOL counterparts {@code app/cbl/COACTVWC.cbl}
 * (TRANID {@code CAVW}) and {@code app/cbl/COACTUPC.cbl}
 * (TRANID {@code CAUP}, 4,236 lines — the largest program in the suite).
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>{@code GET /api/accounts/{accountId}} — returns a JSON-serialised
 * {@link AccountViewJsonResponse} carrying the hydrated account-view payload
 * (account fields from {@code ACCTDAT} PLUS customer fields from
 * {@code CUSTDAT} PLUS the card number from {@code CARDAIX}):
 *
 * <ul>
 *   <li>{@code 200 OK} — account, customer, and cross-reference rows all
 *       present; body's {@code success} field is {@code true} and all view
 *       fields are populated.</li>
 *   <li>{@code 400 BAD_REQUEST} — path variable malformed (not 11 numeric
 *       digits) OR service-layer validation reject
 *       ({@link #MSG_INVALID_ACCOUNT_ID}). Both surface as HTTP 400 because
 *       they correspond to the COBOL {@code FLG-ACCTFILTER-NOT-OK} reject
 *       which fires BEFORE any file I/O.</li>
 *   <li>{@code 401 UNAUTHORIZED} — request lacked authentication (handled
 *       by Spring Security's filter chain BEFORE the controller is
 *       reached).</li>
 *   <li>{@code 404 NOT_FOUND} — any of the three lookups
 *       ({@code CARDAIX}, {@code ACCTDAT}, {@code CUSTDAT}) returned
 *       {@code DFHRESP(NOTFND)}. Mirrors the COBOL {@code NOTFND-CONDITION}
 *       reject paths at {@code COACTVWC.cbl} lines 619 / 706 / 800 which
 *       all surface a distinct "...not found in..." operator message
 *       preserved verbatim in this Java migration.</li>
 *   <li>{@code 500 INTERNAL_SERVER_ERROR} — unexpected service-layer
 *       failure (for example a {@code DataAccessException} when the
 *       {@code ACCTDAT} table is unreachable). The exception detail is
 *       NEVER echoed in the response body — only the canned
 *       {@link #MSG_INTERNAL_ERROR} string is emitted (AAP §0.10.5 applied
 *       to error paths).</li>
 * </ul>
 *
 * <p>{@code PUT /api/accounts/{accountId}} — body is a JSON-serialised
 * {@link AccountUpdateRequest}; response is a JSON-serialised
 * {@link AccountUpdateJsonResponse} carrying the success/failure envelope
 * from {@link AccountUpdateResult}:
 *
 * <ul>
 *   <li>{@code 200 OK} — dual-table update committed (both {@code ACCTDAT}
 *       and {@code CUSTDAT} successfully saved within the
 *       {@code @Transactional} boundary). Body's {@code message} field
 *       carries the verbatim COBOL string
 *       {@code "Changes committed to database"} (mirroring
 *       {@code COACTUPC.cbl} line 474).</li>
 *   <li>{@code 400 BAD_REQUEST} — path variable malformed, path-vs-body
 *       primary-key mismatch (immutable-PK defence per AAP §0.10.4), OR
 *       any of the service's pre-DB validation rejects (active status not
 *       Y/N, credit limit invalid, SSN invalid, phone invalid).</li>
 *   <li>{@code 401 UNAUTHORIZED} — request lacked authentication.</li>
 *   <li>{@code 403 FORBIDDEN} — request was missing the CSRF token on a
 *       state-changing PUT. Spring Security's {@code CsrfFilter} produces
 *       this status BEFORE the controller is reached.</li>
 *   <li>{@code 404 NOT_FOUND} — account-record-not-found or
 *       customer-record-not-found during {@code findById(...)} lookups.
 *       Mirrors COBOL {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} (line 3912)
 *       and {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} (line 3939).</li>
 *   <li>{@code 409 CONFLICT} — JPA optimistic-lock conflict. The
 *       {@code @Version} field on either the loaded account or the loaded
 *       customer did not match the persisted version (a concurrent update
 *       happened between this transaction's lookup and save). Mirrors the
 *       COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag at
 *       {@code COACTUPC.cbl} line 521 and the corresponding
 *       {@code EXEC CICS SYNCPOINT ROLLBACK} block at line 4100.</li>
 *   <li>{@code 415 UNSUPPORTED_MEDIA_TYPE} — request lacked the
 *       {@code Content-Type: application/json} header.</li>
 *   <li>{@code 500 INTERNAL_SERVER_ERROR} — unexpected exception (for
 *       example {@code DataIntegrityViolationException} from a foreign-key
 *       violation, or a generic {@code DataAccessException}). The detail
 *       is sanitised; only {@link #MSG_INTERNAL_ERROR} is returned.</li>
 * </ul>
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code COACTVWC.cbl} — View-Account workflow:
 * <ol>
 *   <li>Operator types an account ID into the {@code ACCTSID} field of
 *       the {@code COACTVW} BMS map (PIC 9(11), MUSTFILL).</li>
 *   <li>The COBOL program validates that the account ID is exactly 11
 *       numeric digits and non-zero ({@code WS-EDIT-ACCT-FLAG}).</li>
 *   <li>It reads {@code CARDAIX} (the card cross-reference index) by
 *       account ID to discover the matching card number and customer ID.</li>
 *   <li>It reads {@code ACCTDAT} by the account ID to hydrate account
 *       fields (status, balances, limits, dates).</li>
 *   <li>It reads {@code CUSTDAT} by the customer ID to hydrate the
 *       customer-side fields shown on the same BMS map (name, SSN, DOB,
 *       FICO).</li>
 *   <li>It writes all hydrated fields back to the {@code COACTVW} BMS
 *       output and returns control to CICS.</li>
 * </ol>
 *
 * <p>{@code COACTUPC.cbl} — Update-Account workflow (4,236 lines, the
 * largest program in the suite):
 * <ol>
 *   <li>Operator views the populated account in the {@code COACTUP} map,
 *       modifies fields, and submits.</li>
 *   <li>The COBOL program revalidates each field (active status Y/N,
 *       credit limit non-negative, SSN PART1, phone area code).</li>
 *   <li>It re-reads {@code ACCTDAT} and {@code CUSTDAT} under UPDATE
 *       intent (acquiring row locks).</li>
 *   <li>It compares the before-image (read at view time) with the
 *       current persisted image — the COBOL
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} optimistic-locking flag
 *       (line 521). If they differ, the update is rejected with the
 *       operator message {@code "Record changed by some one else..."}.</li>
 *   <li>It rewrites {@code ACCTDAT} and {@code CUSTDAT} within a
 *       {@code SYNCPOINT} boundary. On any error,
 *       {@code SYNCPOINT ROLLBACK} (line 4100) unwinds the partial
 *       update.</li>
 * </ol>
 *
 * <p>The Java migration replaces:
 * <ul>
 *   <li>Multi-file {@code EXEC CICS READ} → {@link AccountViewService}
 *       orchestrating three Spring Data JPA {@code findById(...)} calls.</li>
 *   <li>{@code EXEC CICS READ UPDATE} + {@code REWRITE} pair →
 *       {@link AccountUpdateService} with {@code @Transactional}
 *       boundaries and JPA {@code @Version} optimistic locking.</li>
 *   <li>{@code DATA-WAS-CHANGED-BEFORE-UPDATE} explicit before/after-image
 *       comparison → JPA-managed {@code @Version} mismatch raising
 *       {@code OptimisticLockingFailureException}, caught here and mapped
 *       to HTTP 409.</li>
 *   <li>{@code EXEC CICS SYNCPOINT ROLLBACK} → Spring
 *       {@code @Transactional(rollbackFor=Exception.class)} rollback
 *       semantics, with any uncaught {@code RuntimeException} reaching
 *       this controller's exception handler and mapping to HTTP 500.</li>
 * </ul>
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>Service-layer reject-message mirrors.</b> The {@code MSG_*}
 *       constants on {@link AccountViewService} and
 *       {@link AccountUpdateService} are package-private and cannot be
 *       referenced from this controller class which lives in a sibling
 *       package. The literals are duplicated here so the HTTP-status
 *       mapping can dispatch on them deterministically; the sibling test
 *       class {@code AccountControllerTest} mirrors the SAME literals so
 *       drift surfaces loudly (AAP §0.10.10 style consistency).</li>
 *   <li><b>Primary-key immutability.</b> On {@code PUT
 *       /api/accounts/{accountId}}, the path variable is the authoritative
 *       primary-key target. A body that carries a different
 *       {@code accountId} is rejected with HTTP 400 BEFORE the service is
 *       invoked, defending the immutable-PK contract per AAP §0.10.4.</li>
 *   <li><b>CSRF.</b> {@code PUT /api/accounts/{accountId}} is
 *       state-changing so it requires a valid CSRF token under the
 *       production {@code SecurityConfig}. Spring Security's
 *       {@code CsrfFilter} produces HTTP 403 when the token is missing or
 *       invalid; the controller never sees the request in that case.</li>
 *   <li><b>PCI / financial-data logging.</b> The controller deliberately
 *       does not log the {@link AccountUpdateRequest} body (which contains
 *       the customer's SSN) and does not log the
 *       {@link AccountViewResponse} or {@link AccountUpdateResult} bodies.
 *       Per AAP §0.10.5 ("No financial data written to logs at any
 *       level"): the {@code logback-test.xml} turbofilter further masks
 *       any accidental disclosure.</li>
 * </ul>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see AccountViewResponse
 * @see AccountUpdateRequest
 * @see AccountUpdateResult
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    // ------------------------------------------------------------------------
    // Path-variable validation constants
    // ------------------------------------------------------------------------

    /**
     * Width of the account-ID primary key (COBOL {@code PIC 9(11)} from the
     * {@code CVACT01Y.cpy} copybook). The Java migration preserves the
     * exact width to keep the persisted file/table format byte-identical
     * (AAP §0.10.4 immutable boundary).
     */
    static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Rejection message returned to the client when the path variable
     * {@code accountId} does not parse as exactly eleven decimal digits.
     * Mirrors the COBOL {@code FLG-ACCTFILTER-NOT-OK} reject path at
     * {@code COACTVWC.cbl} line 59 — the {@code COACTVW} BMS map declares
     * {@code ACCTSID PIC 9(11) VALIDN=(MUSTFILL)} so the COBOL workflow
     * never reaches the file-I/O step on a malformed input.
     */
    static final String MSG_ACCOUNT_ID_INVALID_FORMAT =
            "Account number must be 11 numeric digits";

    /**
     * Rejection message returned to the client when the path variable
     * and the JSON body's {@code accountId} field disagree. The path
     * variable is authoritative; rejecting a mismatched body defends the
     * primary-key-immutability contract per AAP §0.10.4 (Immutable
     * Boundaries — no client-driven PK mutation possible).
     */
    static final String MSG_ACCOUNT_ID_PATH_BODY_MISMATCH =
            "Account ID in path does not match account ID in body";

    /**
     * Rejection message returned to the client on a JPA
     * {@code OptimisticLockingFailureException}. Mirrors the COBOL
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} reject path
     * ({@code COACTUPC.cbl} line 521) and the operator message
     * {@code "Record changed by some one else..."} preserved on the BMS
     * map output. Maps to HTTP 409 Conflict (REST convention for
     * concurrent-modification rejects).
     */
    static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "Record changed by some one else. Please review";

    /**
     * Generic HTTP 500 message when the service layer throws an
     * unexpected exception. The underlying exception detail is NEVER
     * returned in the HTTP response — preventing accidental disclosure of
     * database error strings, stack traces, SQL fragments, or internal
     * class names (AAP §0.10.5).
     */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    /** HTTP 400 message when the JSON request body cannot be parsed. */
    static final String MSG_MALFORMED_REQUEST = "Malformed JSON request body";

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors (AccountViewService)
    // ------------------------------------------------------------------------

    /**
     * Service-layer reject when the account ID fails the non-zero +
     * 11-digit-numeric validation. Mirrors
     * {@code AccountViewService.MSG_INVALID_ACCOUNT_ID}. Surfaces as HTTP
     * 400 (the COBOL {@code FLG-ACCTFILTER-NOT-OK} reject is a pre-DB
     * validation error the client can fix by adjusting input).
     */
    static final String MSG_INVALID_ACCOUNT_ID =
            "Account Filter must be a non-zero 11 digit numeric value";

    /**
     * Service-layer reject when the {@code CARDAIX} cross-reference lookup
     * returns no rows. Mirrors
     * {@code AccountViewService.MSG_NO_CARD_XREF}. Surfaces as HTTP 404
     * (the cross-reference lookup is the first of three master-file
     * accesses; missing here means the account has no card-side record).
     */
    static final String MSG_NO_CARD_XREF =
            "Did not find this account in the card cross reference file";

    /**
     * Service-layer reject when the {@code ACCTDAT} lookup returns no
     * rows. Mirrors {@code AccountViewService.MSG_ACCOUNT_NOT_FOUND}.
     * Surfaces as HTTP 404 — the canonical "account does not exist"
     * outcome the operator sees from the COBOL workflow.
     */
    static final String MSG_ACCOUNT_NOT_FOUND =
            "Account not found in the account master file";

    /**
     * Service-layer reject when the {@code CUSTDAT} lookup returns no
     * rows after a successful {@code CARDAIX} + {@code ACCTDAT} hydration.
     * Mirrors {@code AccountViewService.MSG_CUSTOMER_NOT_FOUND}. Surfaces
     * as HTTP 404 — even though the account itself was found, the view
     * cannot complete without the customer-side fields.
     */
    static final String MSG_CUSTOMER_NOT_FOUND =
            "Customer not found in the customer master file";

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors (AccountUpdateService)
    // ------------------------------------------------------------------------

    /**
     * Service-layer reject when {@link AccountUpdateRequest#getAccountActiveStatus()}
     * is neither {@code "Y"} nor {@code "N"}. Mirrors
     * {@code AccountUpdateService.MSG_ACCT_STATUS_INVALID}. Surfaces as
     * HTTP 400.
     */
    static final String MSG_ACCT_STATUS_INVALID = "Account Active Status must be Y or N";

    /**
     * Service-layer reject when {@link AccountUpdateRequest#getCreditLimit()}
     * is {@code null} or negative. Mirrors
     * {@code AccountUpdateService.MSG_CREDIT_LIMIT_INVALID}. Surfaces as
     * HTTP 400.
     */
    static final String MSG_CREDIT_LIMIT_INVALID = "Credit Limit is not valid";

    /**
     * Service-layer reject when {@link AccountUpdateRequest#getSsn()}
     * fails the 9-digit regex or its PART1 (first three digits) is in the
     * COBOL exclusion set {@code 0 / 666 / 900–999}. Mirrors
     * {@code AccountUpdateService.MSG_SSN_INVALID}. Surfaces as HTTP 400.
     */
    static final String MSG_SSN_INVALID = "SSN is not valid";

    /**
     * Service-layer reject when {@link AccountUpdateRequest#getPhoneNumber1()}
     * fails the {@code (NNN)NNN-NNNN} regex or its area code equals
     * {@code "000"}. Mirrors {@code AccountUpdateService.MSG_PHONE_INVALID}.
     * Surfaces as HTTP 400.
     */
    static final String MSG_PHONE_INVALID = "Phone number is not valid";

    /**
     * Service-layer reject when {@code accountRepository.findById(...)}
     * returns {@code Optional.empty()} during the update lookup. Mirrors
     * {@code AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT} and the COBOL
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} flag at
     * {@code COACTUPC.cbl} line 3912. Surfaces as HTTP 404.
     */
    static final String MSG_COULD_NOT_LOCK_ACCT =
            "Could not lock account record for update";

    /**
     * Service-layer reject when {@code customerRepository.findById(...)}
     * returns {@code Optional.empty()} during the update lookup. Mirrors
     * {@code AccountUpdateService.MSG_COULD_NOT_LOCK_CUST} and the COBOL
     * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} flag at
     * {@code COACTUPC.cbl} line 3939. Surfaces as HTTP 404.
     */
    static final String MSG_COULD_NOT_LOCK_CUST =
            "Could not lock customer record for update";

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected; @MockBean in slice tests)
    // ------------------------------------------------------------------------

    private final AccountViewService accountViewService;
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructs the controller with constructor-injected service
     * collaborators (the {@link AccountViewService} that orchestrates the
     * three-file view-hydration workflow and the
     * {@link AccountUpdateService} that owns the dual-table update
     * workflow). Constructor injection (rather than field injection) makes
     * the dependencies explicit and enables fully-static
     * {@code @WebMvcTest} slice tests with {@code @MockBean} replacement.
     *
     * @param accountViewService   the view-side service collaborator;
     *                             must not be {@code null}
     * @param accountUpdateService the update-side service collaborator;
     *                             must not be {@code null}
     */
    public AccountController(AccountViewService accountViewService,
                             AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    // ========================================================================
    // GET /api/accounts/{accountId} — view account (COACTVWC / TRANID CAVW)
    // ========================================================================

    /**
     * Retrieves the fully hydrated view of an account, joining
     * {@code ACCTDAT} + {@code CUSTDAT} via {@code CARDAIX}. Mirrors the
     * BMS-driven {@code COACTVW} screen output: the operator sees account
     * fields (status, balances, limits, dates), customer fields (name,
     * SSN, DOB, FICO), and the card number from the cross-reference index
     * on a single screen.
     *
     * <p>The path variable is validated to be exactly 11 numeric digits
     * BEFORE the service is invoked. On a malformed path variable, the
     * controller responds with HTTP 400 carrying
     * {@link #MSG_ACCOUNT_ID_INVALID_FORMAT} and the service is NEVER
     * called — defending the COBOL invariant that the {@code ACCTSID}
     * field is validated by BMS {@code VALIDN=(MUSTFILL)} before any
     * {@code EXEC CICS READ} fires.
     *
     * @param accountId the 11-character account ID path variable
     * @return HTTP 200 with the hydrated view on success; HTTP 400 on a
     *         malformed path variable or service-side validation reject;
     *         HTTP 404 on any of the three master-file-not-found rejects
     */
    @GetMapping(path = "/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountViewJsonResponse> getAccount(@PathVariable("accountId") String accountId) {
        // Step 1 — defensive path-variable validation. The COBOL
        // ACCTSID BMS field declares PIC 9(11) VALIDN=(MUSTFILL), so a
        // malformed input never reaches the file-I/O step. The Java
        // migration enforces the same invariant here before invoking the
        // service. Rejects with HTTP 400 + MSG_ACCOUNT_ID_INVALID_FORMAT
        // so clients can distinguish controller-level path validation
        // from service-level business validation.
        if (!isWellFormedAccountId(accountId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AccountViewJsonResponse.failure(accountId, MSG_ACCOUNT_ID_INVALID_FORMAT));
        }

        // Step 2 — delegate the three-file hydration workflow to the
        // service (CARDAIX cross-reference lookup, ACCTDAT account
        // lookup, CUSTDAT customer lookup). The service returns a
        // success/failure DTO; no exceptions are thrown for business-
        // logic rejects (DataAccessException subclasses propagate to
        // the @ExceptionHandler and map to HTTP 500).
        AccountViewResponse result = accountViewService.getAccount(accountId);

        // Step 3a — success path: HTTP 200 with the hydrated view.
        if (result.isSuccess()) {
            return ResponseEntity.ok(AccountViewJsonResponse.success(result));
        }

        // Step 3b — failure path: dispatch on the reject-message literal
        // to map to the appropriate HTTP status. Per AAP §0.10.4 the
        // message literals are immutable boundary contracts mirrored as
        // constants on this controller; if the service renames one, the
        // test will fail loudly because the controller's mapping no
        // longer matches.
        String msg = result.getMessage();
        HttpStatus status;
        if (MSG_NO_CARD_XREF.equals(msg)
                || MSG_ACCOUNT_NOT_FOUND.equals(msg)
                || MSG_CUSTOMER_NOT_FOUND.equals(msg)) {
            // Any of the three lookups returned no rows. Maps to HTTP
            // 404 NOT_FOUND (REST convention for "the requested resource
            // does not exist"). Mirrors COBOL COACTVWC.cbl
            // DFHRESP(NOTFND) reject paths at lines 619 / 706 / 800.
            status = HttpStatus.NOT_FOUND;
        } else {
            // Any other reject (most commonly MSG_INVALID_ACCOUNT_ID)
            // is a validation error the client can fix. Maps to HTTP
            // 400 BAD_REQUEST.
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(AccountViewJsonResponse.failure(accountId, msg));
    }

    // ========================================================================
    // PUT /api/accounts/{accountId} — update account (COACTUPC / TRANID CAUP)
    // ========================================================================

    /**
     * Updates an existing account record (and its matching customer
     * record) in a single transactional unit. The path variable is the
     * authoritative primary-key target; a request body that carries a
     * different {@code accountId} is rejected with HTTP 400 (defends the
     * immutable-PK contract per AAP §0.10.4).
     *
     * <p>On JPA {@code @Version} mismatch the
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}
     * call throws {@link OptimisticLockingFailureException}; this
     * controller catches it and maps to HTTP 409 Conflict, mirroring the
     * COBOL {@code COACTUPC.cbl} {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
     * (line 521) before/after-image comparison reject path.
     *
     * @param accountId the 11-character account ID path variable
     *                  (authoritative target)
     * @param request   the JSON-serialised {@link AccountUpdateRequest}
     *                  carrying the operator-modified field values
     * @return HTTP 200 on success; HTTP 400 on validation reject
     *         (including malformed path variable and path-vs-body
     *         mismatch); HTTP 404 on account-not-found or
     *         customer-not-found; HTTP 409 on optimistic-lock conflict
     */
    @PutMapping(path = "/{accountId}",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountUpdateJsonResponse> updateAccount(
            @PathVariable("accountId") String accountId,
            @RequestBody AccountUpdateRequest request) {

        // Step 1 — defensive path-variable validation (same idiom as the
        // GET endpoint). A malformed path variable is rejected with HTTP
        // 400 BEFORE any service invocation. The request body may be
        // null when the client sends an empty PUT — we still emit a
        // well-formed JSON failure envelope.
        if (!isWellFormedAccountId(accountId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AccountUpdateJsonResponse.failure(
                            accountId,
                            (request == null) ? null : request.getAccountVersion(),
                            (request == null) ? null : request.getCustomerVersion(),
                            MSG_ACCOUNT_ID_INVALID_FORMAT));
        }

        // Step 2 — immutable-PK defence (AAP §0.10.4). A body that
        // carries a different accountId than the path variable is
        // rejected with HTTP 400 before the service is invoked. This
        // catches client-side bugs (e.g., a frontend that forgets to
        // keep path and body in sync) before they can attempt a
        // primary-key mutation.
        if (request != null
                && request.getAccountId() != null
                && !accountId.equals(request.getAccountId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AccountUpdateJsonResponse.failure(
                            accountId,
                            request.getAccountVersion(),
                            request.getCustomerVersion(),
                            MSG_ACCOUNT_ID_PATH_BODY_MISMATCH));
        }

        // Step 3 — honour the path variable as the authoritative account
        // ID. The mutable setter is the field-binding contract
        // documented on AccountUpdateRequest. This handles the case
        // where the body omits the field (null) — the service still
        // needs to know which account to look up.
        if (request != null) {
            request.setAccountId(accountId);
        }

        try {
            AccountUpdateResult result = accountUpdateService.updateAccount(request);

            Long accountVersion = (request == null) ? null : request.getAccountVersion();
            Long customerVersion = (request == null) ? null : request.getCustomerVersion();

            if (result.isSuccess()) {
                return ResponseEntity.ok(new AccountUpdateJsonResponse(
                        true,
                        accountId,
                        accountVersion,
                        customerVersion,
                        result.getMessage()));
            }

            // Failure path — map the reject message to HTTP status.
            // Validation rejects (active status, credit limit, SSN,
            // phone) map to HTTP 400. Master-file-not-found rejects
            // (COULD-NOT-LOCK-* literals from the service layer) map to
            // HTTP 404. Any other reject text falls through to HTTP 400
            // by default (defensive — no other reject text is expected
            // from the service).
            String msg = result.getMessage();
            HttpStatus status;
            if (MSG_COULD_NOT_LOCK_ACCT.equals(msg) || MSG_COULD_NOT_LOCK_CUST.equals(msg)) {
                status = HttpStatus.NOT_FOUND;
            } else {
                status = HttpStatus.BAD_REQUEST;
            }
            return ResponseEntity.status(status).body(new AccountUpdateJsonResponse(
                    false,
                    accountId,
                    accountVersion,
                    customerVersion,
                    msg));

        } catch (OptimisticLockingFailureException ex) {
            // JPA @Version mismatch — another session updated the row
            // between our load and our save. Preserves the COBOL
            // COACTUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE (line 521)
            // before-image / after-image comparison semantics. Maps to
            // HTTP 409 Conflict per REST convention. The original
            // exception is not echoed in the response (AAP §0.10.5).
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(AccountUpdateJsonResponse.failure(
                            accountId,
                            (request == null) ? null : request.getAccountVersion(),
                            (request == null) ? null : request.getCustomerVersion(),
                            MSG_OPTIMISTIC_LOCK_CONFLICT));
        }
    }

    // ========================================================================
    // @ExceptionHandler — request-shape and unexpected-failure handling
    // ========================================================================

    /**
     * Handles malformed JSON request bodies — Spring's
     * {@link HttpMessageNotReadableException} fires when the body fails
     * JSON deserialisation (truncated JSON, invalid JSON syntax, wrong
     * shape). Returns HTTP 400 with a sanitised message. Declared BEFORE
     * the generic {@link RuntimeException} handler so Spring's
     * most-specific-handler-wins resolution picks it for the JSON-parse
     * case.
     *
     * @param ex the message-conversion exception; the controller does
     *           NOT include any field of this exception in the response
     * @return HTTP 400 with a generic malformed-body message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AccountUpdateJsonResponse> handleMalformedRequestBody(
            HttpMessageNotReadableException ex) {
        // The exception's message may include parts of the offending
        // JSON payload (which could carry account identifiers); we
        // deliberately discard it and return a generic message instead.
        // The `ex` parameter is kept in the signature so Spring's
        // exception resolver matches the most-specific overload, and so
        // production deployments can later add a Logger.debug(ex) call
        // here without changing the method signature.
        if (ex == null) {
            return ResponseEntity.badRequest().body(
                    AccountUpdateJsonResponse.failure(null, null, null, MSG_MALFORMED_REQUEST));
        }
        return ResponseEntity.badRequest().body(
                AccountUpdateJsonResponse.failure(null, null, null, MSG_MALFORMED_REQUEST));
    }

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer. Returns HTTP 500 with a sanitised body — the underlying
     * exception detail must never leak into the HTTP response (AAP
     * §0.10.5 applied to error paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+
     * subtype {@code AuthorizationDeniedException}) is RE-THROWN so
     * Spring Security's {@code ExceptionTranslationFilter} can map it to
     * HTTP 403. {@link OptimisticLockingFailureException} is handled
     * directly in the PUT method (so it never reaches this handler).
     *
     * @param ex the caught exception; not echoed in the response
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorJsonResponse> handleServiceFailure(RuntimeException ex) {
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        // Defensive: dereference `ex` purely to satisfy the
        // unused-parameter lint; we intentionally discard its message
        // and stack trace so they cannot be included in the response.
        // Production code would replace this with a structured Logger
        // invocation that records {ex} for operations review.
        if (ex == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Tests whether a candidate string is a well-formed account ID per
     * the COBOL {@code ACCTSID} BMS field contract: exactly 11
     * characters AND every character is a decimal digit. Used by both
     * the GET and PUT endpoints to reject malformed path variables
     * before the service is invoked.
     *
     * @param candidate the candidate string; {@code null} is treated as
     *                  malformed
     * @return {@code true} iff the candidate is exactly 11 ASCII digits
     */
    private static boolean isWellFormedAccountId(String candidate) {
        if (candidate == null || candidate.length() != ACCOUNT_ID_WIDTH) {
            return false;
        }
        for (int i = 0; i < ACCOUNT_ID_WIDTH; i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Wire-format response records
    // ========================================================================

    /**
     * Wire-format response for {@code GET /api/accounts/{accountId}}.
     * Carries the fully hydrated account-view payload on success or only
     * the failure envelope on a reject path. PCI-sensitive customer
     * fields (SSN, DOB, government-issued ID, full address) are
     * deliberately omitted from this response envelope per AAP §0.10.5;
     * if a future endpoint needs them, a separate
     * {@code GET /api/accounts/{id}/customer-pii} endpoint with stricter
     * authorisation can surface them.
     *
     * @param success           {@code true} on the happy path;
     *                          {@code false} on any reject path
     * @param accountId         11-character account ID echoed from the
     *                          path variable (always populated)
     * @param cardNumber        16-character card number from
     *                          {@code CARDAIX}; null on failure
     * @param activeStatus      1-character {@code ACCT-ACTIVE-STATUS}
     *                          ({@code 'Y'} / {@code 'N'}); null on
     *                          failure
     * @param currentBalance    {@code ACCT-CURR-BAL} at scale 2; null on
     *                          failure
     * @param creditLimit       {@code ACCT-CREDIT-LIMIT} at scale 2; null
     *                          on failure
     * @param cashCreditLimit   {@code ACCT-CASH-CREDIT-LIMIT} at scale
     *                          2; null on failure
     * @param currentCycleCredit {@code ACCT-CURR-CYC-CREDIT} at scale 2;
     *                           null on failure
     * @param currentCycleDebit {@code ACCT-CURR-CYC-DEBIT} at scale 2;
     *                          null on failure
     * @param openDate          {@code ACCT-OPEN-DATE} (ISO
     *                          {@code YYYY-MM-DD}); null on failure
     * @param expirationDate    {@code ACCT-EXPIRAION-DATE} (ISO
     *                          {@code YYYY-MM-DD}); null on failure
     * @param reissueDate       {@code ACCT-REISSUE-DATE} (ISO
     *                          {@code YYYY-MM-DD}); null on failure
     * @param accountGroupId    {@code ACCT-GROUP-ID}; null on failure
     * @param customerId        9-character customer ID from
     *                          {@code CARDAIX}; null on failure
     * @param customerFirstName customer first name; null on failure
     * @param customerMiddleName customer middle name; null on failure
     * @param customerLastName  customer last name; null on failure
     * @param ficoCreditScore   FICO score from {@code CUSTDAT}; null on
     *                          failure
     * @param message           reject message on failure; null on
     *                          success
     */
    public static record AccountViewJsonResponse(
            boolean success,
            String accountId,
            String cardNumber,
            String activeStatus,
            // ---------------------------------------------------------------
            // AAP §0.10.3 — Monetary fields on the HTTP boundary
            //
            // BigDecimal monetary values are serialised to JSON as strings so
            // that the COBOL PIC S9(10)V99 scale-2 contract is preserved
            // verbatim on the wire (e.g., the literal "1250.00" — never
            // 1250 or 1250.0). Numeric JSON serialisation would lose scale
            // for trailing-zero values and would force test code to use
            // Java `double` literals, which the AAP forbids ("No float or
            // double used for any monetary value — BigDecimal exclusively").
            //
            // Annotating each monetary field with @JsonFormat(shape = STRING)
            // is the narrow, declarative Jackson directive for this. The
            // ToString serialiser preserves scale because BigDecimal#toString
            // emits the exact unscaled value with the recorded scale.
            // ---------------------------------------------------------------
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditLimit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cashCreditLimit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentCycleCredit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentCycleDebit,
            String openDate,
            String expirationDate,
            String reissueDate,
            String accountGroupId,
            String customerId,
            String customerFirstName,
            String customerMiddleName,
            String customerLastName,
            Integer ficoCreditScore,
            String message) {

        /**
         * Builds a populated success response from a hydrated
         * {@link AccountViewResponse}.
         *
         * @param r the service response with all account / customer /
         *          cross-reference fields populated
         * @return a success-flagged JSON response carrying every view
         *         field except PCI-sensitive customer attributes (SSN,
         *         DOB, full address) — those are AAP §0.10.5
         *         containment exclusions
         */
        static AccountViewJsonResponse success(AccountViewResponse r) {
            return new AccountViewJsonResponse(
                    true,
                    r.getAccountId(),
                    r.getCardNumber(),
                    r.getActiveStatus(),
                    r.getCurrentBalance(),
                    r.getCreditLimit(),
                    r.getCashCreditLimit(),
                    r.getCurrentCycleCredit(),
                    r.getCurrentCycleDebit(),
                    r.getOpenDate(),
                    r.getExpirationDate(),
                    r.getReissueDate(),
                    r.getGroupId(),
                    r.getCustomerId(),
                    r.getCustomerFirstName(),
                    r.getCustomerMiddleName(),
                    r.getCustomerLastName(),
                    r.getFicoCreditScore(),
                    null);
        }

        /**
         * Builds a failure response carrying only the account-ID echo
         * and the reject message; all other fields are {@code null}.
         *
         * @param accountId the requested account ID (path variable)
         * @param message   the reject message
         * @return a failure-flagged JSON response
         */
        static AccountViewJsonResponse failure(String accountId, String message) {
            return new AccountViewJsonResponse(
                    false,
                    accountId,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null,
                    message);
        }
    }

    /**
     * Wire-format response for {@code PUT /api/accounts/{accountId}}.
     * Carries the success or reject outcome of the account-update
     * operation along with the JPA optimistic-locking version numbers
     * (echoed from the request body).
     *
     * <p>The full account/customer payload is intentionally NOT echoed
     * back in this response — the client just sent it, so re-emitting it
     * carries no additional information and would re-expose the
     * operator-supplied SSN (AAP §0.10.5 PCI containment).
     *
     * @param success         {@code true} on the happy path;
     *                        {@code false} on any reject path
     * @param accountId       11-character account ID echoed from the
     *                        path variable (always populated)
     * @param accountVersion  the JPA optimistic-locking version for the
     *                        account record echoed from the request
     *                        body; null when the request body omitted it
     * @param customerVersion the JPA optimistic-locking version for the
     *                        customer record echoed from the request
     *                        body; null when the request body omitted it
     * @param message         the COBOL-equivalent success or reject
     *                        message
     */
    public static record AccountUpdateJsonResponse(
            boolean success,
            String accountId,
            Long accountVersion,
            Long customerVersion,
            String message) {

        /**
         * Builds a failure response carrying only the path echo, the
         * version numbers, and the reject message.
         *
         * @param accountId       the requested account ID (path
         *                        variable); may be {@code null} when the
         *                        failure occurred during body
         *                        deserialisation
         * @param accountVersion  the version echoed from the request;
         *                        may be {@code null} when the request
         *                        body omitted it
         * @param customerVersion the version echoed from the request;
         *                        may be {@code null} when the request
         *                        body omitted it
         * @param message         the reject message
         * @return a failure-flagged JSON response
         */
        static AccountUpdateJsonResponse failure(String accountId,
                                                 Long accountVersion,
                                                 Long customerVersion,
                                                 String message) {
            return new AccountUpdateJsonResponse(
                    false, accountId, accountVersion, customerVersion, message);
        }
    }

    /**
     * Wire-format response for unexpected service-layer failures.
     * Returned by the {@link #handleServiceFailure(RuntimeException)}
     * exception handler — separated from the per-endpoint response
     * records to keep the JSON shape uniform for client-side error
     * handling regardless of which endpoint the failure originated at.
     *
     * @param success always {@code false}
     * @param message a sanitised generic message
     *                ({@link #MSG_INTERNAL_ERROR})
     */
    public static record ErrorJsonResponse(boolean success, String message) {
    }
}
