package com.cardemo.controller;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the two AWS CardDemo CICS BMS 3270 <strong>account screens</strong>:
 * <strong>Account View</strong> and <strong>Account Update</strong>. It exposes the two routes under
 * <strong>{@code /api/accounts/{id}}</strong> (view, update) and is a thin adapter over
 * {@link AccountViewService} and {@link AccountUpdateService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L647: <em>{@code controller/AccountController.java} CREATE &larr;
 * {@code app/bms/COACTVW.bms}, {@code app/bms/COACTUP.bms} &mdash; "GET/PUT /api/accounts/{id}"</em>)
 * and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST contract translation). It preserves features
 * <strong>F-004</strong> (Account View) and <strong>F-005</strong> (Account Update) without
 * expansion (Minimal Change Clause, AAP&nbsp;&sect;0.7.1).</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COACTVW.bms}</strong> &mdash; the Account View mapset
 *       ({@code COACTVW} / map {@code CACTVWA}), driven by CICS program {@code COACTVWC},
 *       transaction <strong>{@code CAVW}</strong>. A read-only inquiry that joined the
 *       {@code ACCTDAT} account record with the related {@code CUSTDAT} customer record
 *       (resolved through the {@code CXACAIX} cross-reference).</li>
 *   <li><strong>{@code app/bms/COACTUP.bms}</strong> &mdash; the Account Update mapset
 *       ({@code COACTUP} / map {@code CACTUPA}), driven by CICS program {@code COACTUPC},
 *       transaction <strong>{@code CAUP}</strong>. At 4,236 lines {@code COACTUPC} is the single
 *       most complex program in the estate and the <strong>sole {@code SYNCPOINT ROLLBACK}</strong>
 *       site: it performs a dual {@code REWRITE} of the {@code ACCTDAT} (account) and
 *       {@code CUSTDAT} (customer) records that must commit together or roll back together, guarded
 *       by optimistic concurrency (AAP&nbsp;&sect;0.6.4).</li>
 * </ul>
 * <p>Their symbolic maps ({@code app/cpy-bms/COACTVW.CPY} / {@code app/cpy-bms/COACTUP.CPY}) were
 * migrated into {@link AccountDto} (the {@code COPY CSSETATY} field contract). This controller never
 * re-declares those structures and never copies COBOL/BMS text &mdash; only the screen
 * <em>behavior</em> is reproduced, by delegation to the two account services.</p>
 *
 * <h2>Key insight &mdash; one account+customer record handled by the services</h2>
 * <p>Both screens describe the same joined account+customer record. The multi-dataset read chain
 * ({@code CXACAIX}&nbsp;&rarr;&nbsp;{@code ACCTDAT}&nbsp;&rarr;&nbsp;{@code CUSTDAT}), the account-id
 * edits with verbatim ordered messages, the {@code @Version} optimistic-lock check, and the
 * {@code @Transactional} dual-record write all live in {@link AccountViewService} /
 * {@link AccountUpdateService}. This controller adds <strong>zero</strong> logic; each handler is a
 * pure delegation that returns {@code 200 OK} with the service's {@link AccountDto} (AAP&nbsp;&sect;0.3.3).</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND MAP} / {@code RECEIVE MAP} &rarr; request/response DTO.</strong> The 3270
 *       map paint ({@code SEND MAP('CACTVWA')} / {@code SEND MAP('CACTUPA')}) and field harvest
 *       ({@code RECEIVE MAP}) collapse into Jackson (de)serialization of {@link AccountDto}. Screen
 *       chrome, message lines and BMS control bytes have no REST analogue and are not modeled
 *       (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID('CAVW' / 'CAUP') COMMAREA} &rarr; stateless REST.</strong> The
 *       CICS pseudo-conversational hand-off (the {@code COCOM01Y} COMMAREA carrying state across
 *       turns) is replaced by stateless HTTP; this controller holds no conversational state
 *       (AAP&nbsp;&sect;0.1.2).</li>
 *   <li><strong>Two-step PF05-confirm update &rarr; a single version-carrying {@code PUT}.</strong>
 *       {@code COACTUPC} was pseudo-conversational with a two-step confirm state machine: it first
 *       displayed the record, then on PF05 ("confirm") performed the dual {@code REWRITE}. In REST
 *       this collapses into one {@code PUT}; the optimistic-lock {@code version} the user saw at view
 *       time travels inside {@link AccountDto} so a stale form is detected exactly as the COBOL
 *       before/after image compare did (AAP&nbsp;&sect;0.1.2, &sect;0.6.4).</li>
 *   <li><strong>AID keys &rarr; distinct client-driven REST calls.</strong> The view/update screens'
 *       PF03 (return), PF05 (save) and PF12 (cancel) are client-navigation concerns: the client simply
 *       issues the view or update endpoint (or navigates away) for the chosen account. They are not
 *       endpoints on this controller (AAP&nbsp;&sect;0.1.2).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the services (AAP &sect;0.7.2)</h2>
 * <p>The services are the authoritative source for validation <em>order</em> and verbatim COBOL
 * message text ({@code COACTVWC}/{@code COACTUPC} emit specific ordered messages such as
 * "Account ID must be a 11 digit Non-Zero Number ..."). The update body is therefore bound as a plain
 * {@code @RequestBody} <strong>without</strong> {@code @Valid}: applying bean validation here would
 * pre-empt those ordered messages with a generic {@code MethodArgumentNotValidException} and break
 * message parity (consistent with the other CardDemo controllers). The {@link AccountDto} Jakarta
 * constraints remain the documented field contract (the {@code COPY CSSETATY} translation), but the
 * service is the runtime source of truth. The path {@code {id}} is passed straight through as the
 * authoritative account key.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. The services throw and this
 * controller lets propagate the typed exceptions translated by the centralized
 * {@code @RestControllerAdvice} in {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (the verbatim COBOL edit messages).</li>
 *   <li>{@code com.cardemo.exception.RecordNotFoundException} &rarr; HTTP&nbsp;<strong>404 Not
 *       Found</strong> (cross-reference, account or customer record absent).</li>
 *   <li>{@code com.cardemo.exception.ConcurrentModificationException} &rarr;
 *       HTTP&nbsp;<strong>409 Conflict</strong> on a {@code @Version} optimistic-lock conflict during
 *       update. This is deliberately the {@code com.cardemo.exception} type, <strong>never</strong>
 *       {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All {@code /api/accounts/*} endpoints require authentication; access is governed by the Spring
 * Security configuration in {@code config/SecurityConfig}. No security infrastructure and no
 * method-security annotations are introduced in this thin adapter.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied
 * into this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see AccountDto
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    /** Service owning the read-only multi-dataset account inquiry ({@code COACTVWC}). */
    private final AccountViewService accountViewService;

    /**
     * Service owning the {@code @Transactional} dual-record update with {@code @Version}
     * optimistic locking ({@code COACTUPC}, the sole {@code SYNCPOINT ROLLBACK} site).
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructs the controller with the two account services injected by Spring (constructor
     * injection; both fields are {@code final}; no field {@code @Autowired}).
     *
     * @param accountViewService   the read-only account-view service ({@code COACTVWC})
     * @param accountUpdateService the transactional account-update service ({@code COACTUPC})
     */
    public AccountController(final AccountViewService accountViewService,
                            final AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Returns the joined account + customer view for a single account, reproducing {@code COACTVWC}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/accounts/{id}}.</p>
     *
     * <p>The account id is the authoritative key (path variable). It is passed through unchanged to
     * {@link AccountViewService#viewAccount(String)}, which performs the {@code 2210-EDIT-ACCOUNT}
     * input edit (with the verbatim COBOL rejection messages) and the three-step keyed read
     * ({@code CXACAIX}&nbsp;&rarr;&nbsp;{@code ACCTDAT}&nbsp;&rarr;&nbsp;{@code CUSTDAT}) in the exact
     * COBOL order. The controller performs no validation of its own so a malformed id reaches the
     * service and surfaces the verbatim message rather than a generic framework error
     * (parity, AAP&nbsp;&sect;0.7.2).</p>
     *
     * @param id the account id to view ({@code COACTVWC ACCTSID}); the authoritative read key
     * @return {@code 200 OK} carrying the fully-populated account+customer {@link AccountDto}
     * @throws com.cardemo.exception.ValidationException     if the account id is blank, non-numeric or
     *         zero (rendered as HTTP&nbsp;400 by {@code config/WebConfig}); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no cross-reference, account or customer
     *         record exists for the id (HTTP&nbsp;404); propagated, not caught
     */
    // COBOL substitution: COACTVWC RECEIVE MAP('CACTVWA') field harvest -> the {id} path binding;
    // SEND MAP('CACTVWA') -> AccountDto JSON; RETURN TRANSID('CAVW') COMMAREA -> stateless GET.
    // Pure delegation: AccountViewService owns the 2210-EDIT-ACCOUNT edit and the ordered
    // CXACAIX -> ACCTDAT -> CUSTDAT keyed reads; absence surfaces as RecordNotFoundException (404).
    @GetMapping("/{id}")
    public ResponseEntity<AccountDto> getAccount(@PathVariable("id") final String id) {
        return ResponseEntity.ok(accountViewService.viewAccount(id));
    }

    /**
     * Updates an account and its associated customer, reproducing the server-side core of
     * {@code COACTUPC}.
     *
     * <p><strong>Endpoint:</strong> {@code PUT /api/accounts/{id}}.</p>
     *
     * <p>The pseudo-conversational "display&nbsp;&rarr;&nbsp;edit&nbsp;&rarr;&nbsp;PF05-confirm&nbsp;
     * &rarr;&nbsp;dual&nbsp;REWRITE" choreography collapses into a single {@code PUT}. The account id is
     * the authoritative key (path variable); the edited account and customer fields travel in the
     * request body, which also carries the optimistic-lock {@code version} the client saw at view time.
     * The body is bound as {@code @RequestBody} <strong>without</strong> {@code @Valid}: the service
     * owns the ordered verbatim COBOL edit cascade, so applying bean validation here would pre-empt
     * those messages and break parity (AAP&nbsp;&sect;0.7.2). Both the {@code ACCTDAT} and
     * {@code CUSTDAT} writes share one transaction ({@code @Transactional}, the sole
     * {@code SYNCPOINT ROLLBACK} translation), so they commit together or roll back together.</p>
     *
     * @param id      the account id to update ({@code COACTUPC ACCTSID}); the authoritative key
     * @param request the edited account+customer payload &mdash; carries the optimistic-lock
     *                {@code version} for the {@code @Version} conflict check
     * @return {@code 200 OK} carrying the committed account+customer {@link AccountDto}
     * @throws com.cardemo.exception.ValidationException             if the key or any field fails its
     *         edit (HTTP&nbsp;400); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException         if the account or customer does not
     *         exist (HTTP&nbsp;404); propagated, not caught
     * @throws com.cardemo.exception.ConcurrentModificationException if the account was changed
     *         concurrently (JPA {@code @Version} optimistic-lock conflict, HTTP&nbsp;409); propagated,
     *         not caught &mdash; this is the {@code com.cardemo.exception} type, never
     *         {@link java.util.ConcurrentModificationException}
     */
    // COBOL substitution: COACTUPC two-step PF05-confirm update (the manual before/after image
    // compare of 9700-CHECK-CHANGE-IN-REC + dual ACCTDAT+CUSTDAT REWRITE guarded by SYNCPOINT ROLLBACK)
    // -> a single PUT carrying the optimistic-lock version inside AccountDto. RECEIVE MAP('CACTUPA')
    // field harvest -> @RequestBody binding; SEND MAP('CACTUPA') -> AccountDto JSON; RETURN
    // TRANSID('CAUP') COMMAREA -> stateless PUT. Pure delegation: AccountUpdateService owns the
    // validation cascade, the @Transactional dual write and the @Version check (stale version ->
    // com.cardemo.exception.ConcurrentModificationException -> 409, NOT the java.util type).
    @PutMapping("/{id}")
    public ResponseEntity<AccountDto> updateAccount(@PathVariable("id") final String id,
                                                    @RequestBody final AccountDto request) {
        return ResponseEntity.ok(accountUpdateService.updateAccount(id, request));
    }
}
