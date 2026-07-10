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
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.dto.AccountUpdateResponse;
import com.carddemo.dto.AccountViewResponse;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * REST controller for the account <em>view</em> and <em>update</em> use cases &mdash; the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5 replacement for two legacy CICS/BMS 3270 programs
 * (frozen COBOL source referenced read-only at commit SHA {@code 27d6c6f}; never copied into
 * this repository):
 *
 * <ul>
 *   <li><strong>{@code COACTVWC}</strong> ({@code app/cbl/COACTVWC.cbl}, transaction
 *       {@code CAVW}) &mdash; the <em>Account View</em> panel ({@code app/cpy-bms/COACTVW.CPY}).
 *       The mainframe program reads the {@code ACCOUNT} and {@code CUSTOMER} records by account
 *       id and renders a single, denormalized account/customer view. That pseudo-conversational
 *       screen becomes a stateless {@code GET /api/accounts/{accountId}} returning an
 *       {@link AccountViewResponse}; all read logic lives in {@link AccountViewService}.</li>
 *   <li><strong>{@code COACTUPC}</strong> ({@code app/cbl/COACTUPC.cbl}, transaction
 *       {@code CAUP} &mdash; at 4,236&nbsp;LOC the largest legacy program;
 *       {@code app/cpy-bms/COACTUP.CPY}) &mdash; the read-then-rewrite <em>Account Update</em>
 *       with an on-screen confirmation step, an optimistic-lock check ("Record changed by some
 *       one else. Please review") and a {@code SYNCPOINT ROLLBACK} on failure. It is mapped to
 *       {@code PUT /api/accounts/{accountId}}; the {@code SYNCPOINT ROLLBACK} is reproduced by
 *       {@link AccountUpdateService}'s {@code @Transactional(rollbackFor = ...)} boundary and the
 *       "record changed" check by JPA {@code @Version} optimistic locking (AAP &sect;0.8.4). A
 *       version conflict surfaces as HTTP&nbsp;409; all persistence and lock logic lives in
 *       {@link AccountUpdateService}.</li>
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
 * <h2>Stateless optimistic concurrency</h2>
 * <p>The legacy CICS {@code COMMAREA} that carried pseudo-conversational state between the view
 * and the confirmed update is replaced by a stateless round-trip: {@link AccountViewResponse}
 * echoes the JPA {@code @Version} token, the client sends it back in
 * {@link AccountUpdateRequest#version()}, and a stale token is reported by the service as an
 * optimistic-lock conflict (HTTP&nbsp;409). No server-side conversational session is retained.</p>
 *
 * <h2>Decimal &amp; date fidelity (AAP &sect;0.8.2)</h2>
 * <p>This controller never introduces {@code float}/{@code double} and performs no arithmetic:
 * every monetary value is a scale-2 {@link java.math.BigDecimal} and every date a
 * {@link java.time.LocalDate} on the DTO layer, whose canonical constructors enforce those
 * invariants. The controller merely binds and returns the DTOs, preserving the {@code COACTVW}
 * field layout byte-for-byte (contract preservation, Gates&nbsp;1/5).</p>
 *
 * <h2>Security</h2>
 * <p>Both endpoints require an authenticated caller. The Account View (CAVW) and Account Update
 * (CAUP) transactions are reachable by <em>any</em> role (a regular user navigates to them from
 * the main menu), so there is deliberately no {@code @PreAuthorize} admin gate here &mdash;
 * authentication is enforced by the security filter chain in the {@code config} package.</p>
 *
 * <h2>Logging (Observability &sect;0.7.1)</h2>
 * <p>View and update requests are logged at {@code INFO} carrying only the non-sensitive
 * {@code accountId}. The response body is <strong>never</strong> logged because
 * {@link AccountViewResponse} can carry the customer's (masked) SSN and other PII. Every log line
 * carries the MDC {@code correlationId} established by the observability correlation-id filter.</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor injection; its two
 * service collaborators are {@code final}. Design rationale is recorded in
 * {@code docs/decision-log.md} and the COBOL-paragraph mapping in
 * {@code docs/traceability-matrix.md} (Explainability rule), not in code comments.</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/accounts")
@Validated
public class AccountController {

    /** Structured logger; never emits PII (SSN, names, addresses). Carries the MDC {@code correlationId}. */
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** Account View service (transaction {@code CAVW}); flattened account + customer lookup. */
    private final AccountViewService accountViewService;

    /** Account Update service (transaction {@code CAUP}); read-then-rewrite with optimistic locking. */
    private final AccountUpdateService accountUpdateService;

    /**
     * Creates the controller with its two service collaborators.
     *
     * <p>Constructor injection is used exclusively (no field injection) so the collaborators are
     * {@code final} and the controller can be instantiated directly &mdash; with mock services
     * &mdash; in a standalone MockMvc unit test.</p>
     *
     * @param accountViewService   the Account View service ({@code CAVW}); must not be {@code null}
     * @param accountUpdateService the Account Update service ({@code CAUP}); must not be {@code null}
     */
    public AccountController(final AccountViewService accountViewService,
                             final AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Returns the flattened account + customer view for a single account &mdash; the migration of
     * the {@code COACTVWC} (CAVW) Account View panel.
     *
     * <p>The path variable is the account id ({@code ACCT-ID PIC 9(11)}, an eleven-digit numeric
     * key accepted here as a {@link Long}); it must be present. When no account exists for the id
     * (or the account has no cross-reference / customer), {@link AccountViewService} raises a
     * {@code ResourceNotFoundException} (HTTP&nbsp;404). The returned {@link AccountViewResponse}
     * carries scale-2 {@code BigDecimal} money, ISO {@link java.time.LocalDate} dates, the
     * optimistic-lock {@code version}, and a masked SSN. No exception is caught here &mdash;
     * {@link GlobalExceptionHandler} maps them all.</p>
     *
     * @param accountId the account id to view ({@code ACCT-ID} / screen {@code ACCTSID});
     *                  required, eleven-digit numeric key
     * @return {@code 200 OK} with the flattened {@link AccountViewResponse} for the account
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountViewResponse> viewAccount(
            @PathVariable @NotNull final Long accountId) {

        // Log only the non-sensitive account id; never the response body (it may carry masked PII).
        log.info("Account view request (CAVW): accountId={}", accountId);

        final AccountViewResponse response = accountViewService.viewAccount(accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates an existing account and its customer &mdash; the migration of the {@code COACTUPC}
     * (CAUP) read-then-rewrite update with optimistic-concurrency detection.
     *
     * <p>The path variable identifies the account to update; the request body carries the full set
     * of editable account and customer attributes plus the optimistic-lock {@code version} the
     * caller last observed (replacing the CICS {@code COMMAREA}). {@code PUT} is chosen for the
     * full-record replace semantics of CAUP, whose legacy screen rewrites the whole
     * account/customer record. {@link AccountUpdateService} applies the changes inside a
     * {@code @Transactional(rollbackFor = ...)} boundary (reproducing {@code SYNCPOINT ROLLBACK})
     * and re-reads under {@code @Version} optimistic locking: a stale version surfaces as
     * {@code OptimisticLockConflictException} (HTTP&nbsp;409, "Record changed by some one else.
     * Please review"), a missing account as {@code ResourceNotFoundException} (HTTP&nbsp;404), and
     * a malformed field as {@code ValidationException} (HTTP&nbsp;400). Bean-validation failures on
     * the body ({@code @Valid}) &mdash; for example money with more than two fraction digits or a
     * future date of birth &mdash; are reported as HTTP&nbsp;400 with per-field detail. No
     * exception is caught here &mdash; {@link GlobalExceptionHandler} maps them all.</p>
     *
     * @param accountId the account id identifying the record to update ({@code ACCT-ID});
     *                  eleven-digit numeric key
     * @param request   the validated update payload and echoed optimistic-lock version; must not
     *                  be {@code null}
     * @return {@code 200 OK} with an {@link AccountUpdateResponse} carrying the refreshed account
     *         snapshot, the success confirmation, and the new optimistic-lock version
     */
    @PutMapping("/{accountId}")
    public ResponseEntity<AccountUpdateResponse> updateAccount(
            @PathVariable final Long accountId,
            @Valid @RequestBody final AccountUpdateRequest request) {

        // Log only the non-sensitive account id; never the request or response body (they carry PII).
        log.info("Account update request (CAUP): accountId={}", accountId);

        final AccountUpdateResponse response = accountUpdateService.updateAccount(accountId, request);
        return ResponseEntity.ok(response);
    }
}
