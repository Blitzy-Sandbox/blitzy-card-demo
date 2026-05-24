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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.service.AccountUpdateService;
import com.awsm2.carddemo.service.AccountViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Account REST controller &mdash; exposes the account view and account
 * update endpoints.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1, &sect;0.7.3):</b> this
 * controller replaces two CICS COBOL programs operating on the
 * {@code ACCTDAT} VSAM KSDS cluster (record layout
 * {@code app/cpy/CVACT01Y.cpy} {@code ACCOUNT-RECORD}, 300 bytes) with
 * optimistic-lock semantics on the update path:</p>
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} (CICS transaction id {@code CAVW})
 *       &mdash; Account inquiry, joining {@code ACCTDAT} with
 *       {@code CUSTDAT} and {@code CARDXREF} (via {@code CXACAIX} AIX
 *       on {@code XREF-ACCT-ID}) to render the
 *       {@code app/bms/COACTVW.bms} screen (symbolic-map
 *       {@code app/cpy-bms/COACTVW.CPY}). The Java target uses
 *       {@link AccountViewService#getAccountView(Long)} which composes
 *       these three reads with cache-aside + JPA semantics.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} (CICS transaction id {@code CAUP})
 *       &mdash; Account maintenance with full validation cascade and
 *       {@code SYNCPOINT ROLLBACK} on the dual write of
 *       {@code ACCTDAT} + {@code CUSTDAT} (the <b>only</b> explicit
 *       {@code SYNCPOINT ROLLBACK} in the entire CardDemo source per
 *       AAP &sect;0.1.1). Driven by the {@code app/bms/COACTUP.bms}
 *       screen (symbolic-map {@code app/cpy-bms/COACTUP.CPY}). The Java
 *       target uses
 *       {@link AccountUpdateService#updateAccount(Long, AccountUpdateDto)}
 *       with JPA {@code @Version} optimistic locking replacing the
 *       COBOL before/after snapshot comparison in paragraph
 *       {@code 9700-CHECK-CHANGE-IN-REC}, plus
 *       {@code @Transactional(rollbackFor = Exception.class,
 *       isolation = Isolation.READ_COMMITTED)} replacing the COBOL
 *       SYNCPOINT/SYNCPOINT ROLLBACK boundary.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/accounts/{id}} &mdash; returns the
 *       {@link AccountViewDto} for the supplied 11-digit account ID
 *       (replaces CICS {@code COACTVWC} / Tran-ID {@code CAVW}).
 *       Available to any authenticated principal with the {@code USER}
 *       or {@code ADMIN} role.</li>
 *   <li>{@code PUT /api/accounts/{id}} &mdash; applies validated
 *       updates with optimistic locking (replaces CICS
 *       {@code COACTUPC} / Tran-ID {@code CAUP}). Available to any
 *       authenticated principal with the {@code USER} or {@code ADMIN}
 *       role &mdash; the COBOL source did not restrict the maintenance
 *       program to admin users either; the security gate is upstream
 *       at the sign-on / menu level per AAP &sect;0.3.4.</li>
 * </ul>
 *
 * <p><b>Optimistic locking (AAP &sect;0.6.2, &sect;0.6.6):</b> the
 * {@code PUT} endpoint requires the client to echo the {@code version}
 * value returned by the prior {@code GET}. On version mismatch the
 * service layer raises
 * {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 * which the {@code GlobalExceptionHandler} translates into HTTP 409
 * Conflict (mirroring the COBOL {@code COACTUPC.cbl}
 * "Account record changed after retrieval" branch from paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC}). JPA-level race conditions between
 * {@code findById} and {@code save} surface as
 * {@link org.springframework.dao.OptimisticLockingFailureException}
 * which the service catches and rethrows as the same
 * {@code ConcurrentModificationException} so callers see one
 * consistent 409 response shape.</p>
 *
 * <p><b>Transactional integrity (AAP &sect;0.7.1):</b> the
 * {@link AccountUpdateService} service method is annotated with
 * {@code @Transactional(rollbackFor = Exception.class, isolation =
 * Isolation.READ_COMMITTED)} preserving the COBOL {@code SYNCPOINT
 * ROLLBACK} contract from {@code COACTUPC.cbl}. The controller has no
 * transactional semantics &mdash; that responsibility lives in the
 * service layer per the layered architecture mandate.</p>
 *
 * <p><b>Bean Validation (AAP &sect;0.3.4):</b> the class-level
 * {@link Validated @Validated} annotation activates Jakarta Bean
 * Validation on {@code @PathVariable} arguments &mdash; without it,
 * the {@link NotNull @NotNull} and {@link Min @Min} annotations on the
 * {@code id} path parameter would be ignored. Cascading validation
 * into the {@code @RequestBody AccountUpdateDto} is triggered
 * separately by the parameter-level {@link Valid @Valid} annotation,
 * which fires the DTO's full record-level Jakarta constraint set (27
 * components including the mandatory {@code version} field per AAP
 * &sect;0.6.2 optimistic locking). Constraint violations bubble up as
 * {@code jakarta.validation.ConstraintViolationException} (for the
 * path variable) or
 * {@code org.springframework.web.bind.MethodArgumentNotValidException}
 * (for the request body) and are translated by
 * {@code GlobalExceptionHandler} into the standardized
 * {@link ApiResponse} envelope with HTTP 400 status.</p>
 *
 * <p><b>Path-vs-body ID consistency check:</b> the {@code PUT} endpoint
 * verifies that the {@code id} path variable equals the
 * {@code accountId} field carried in the {@link AccountUpdateDto}
 * body. Mismatches are rejected with HTTP 400 ({@link ValidationException}
 * &rarr; {@code GlobalExceptionHandler}) &mdash; this prevents IDOR
 * (Insecure Direct Object Reference) confusion where a client could
 * {@code PUT /api/accounts/123} with a body claiming
 * {@code "accountId": 456}. The service layer always uses the
 * URL-path {@code id} as the authoritative key for the lookup; the
 * controller's consistency check is defense-in-depth per AAP
 * &sect;0.7.2 PCI-DSS layering.</p>
 *
 * <p><b>Layered architecture compliance (AAP &sect;0.3.3, &sect;0.7.1):</b>
 * thin Spring MVC fa&ccedil;ade; no business state, no I/O, no
 * repository or AWS-adapter access. Delegates to
 * {@link AccountViewService} and {@link AccountUpdateService} via
 * constructor injection. The controller never inlines AWS SDK calls
 * &mdash; every AWS-side interaction lives in
 * {@code com.awsm2.carddemo.adapter} per the adapter-pattern rule
 * (AAP &sect;0.3.3).</p>
 *
 * <p><b>Standardized response envelope (AAP &sect;0.3.4):</b> all
 * responses use {@link ApiResponse#success(Object)} (GET) or
 * {@link ApiResponse#success(Object, String)} (PUT, with the message
 * {@code "Account updated successfully"}). Failures surface as typed
 * exceptions handled by {@code GlobalExceptionHandler} which emits
 * symmetric {@code ApiResponse.error(...)} envelopes with the
 * appropriate HTTP status (400, 401, 403, 404, 409, 500).</p>
 *
 * <p><b>PCI-DSS / PII logging discipline (AAP &sect;0.6.6,
 * &sect;0.7.2):</b> the controller emits only the {@code accountId}
 * (non-sensitive 11-digit numeric key) and the operation type. Account
 * balances, SSN, customer name, address, phone, DOB, and other
 * regulated PII fields are <b>never</b> logged from this controller.
 * The {@link AccountUpdateDto#toString()} and
 * {@link AccountViewDto#toString()} both mask the SSN as
 * {@code ***-**-XXXX} so even an accidental
 * {@code LOG.error("DTO state: {}", dto)} call would not leak the
 * regulated value. Structured JSON logs flow through Logback +
 * logstash-logback-encoder to CloudWatch Logs and OpenSearch per AAP
 * &sect;0.6.6 observability architecture.</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see AccountViewDto
 * @see AccountUpdateDto
 * @see ApiResponse
 * @see com.awsm2.carddemo.exception.GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Account",
        description = "Account view and update. Replaces CICS "
                + "COACTVWC (Tran-ID CAVW, account view) and COACTUPC "
                + "(Tran-ID CAUP, account update with SYNCPOINT ROLLBACK).")
@Validated
public class AccountController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2
     * structured JSON logging via Logback + logstash-logback-encoder
     * routes log events through to CloudWatch Logs / OpenSearch.
     *
     * <p><b>PCI-DSS discipline (AAP &sect;0.6.6):</b> only the account
     * ID (non-sensitive 11-digit numeric key) and operation type are
     * logged from this controller; never balances, SSN, customer name,
     * address, phone, DOB, or any other regulated PII field.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /**
     * Account-view service collaborator &mdash; encapsulates the
     * three-table join ({@code Account} + {@code Customer} +
     * {@code CardCrossReference}) originally implemented as the
     * {@code 9000-READ-ACCT}, {@code 9100-READ-CUST}, and
     * {@code 9200-GETCARDXREF-BYACCT} paragraphs in
     * {@code app/cbl/COACTVWC.cbl}. Constructor-injected and held
     * {@code final} for immutability and Spring proxy compatibility.
     */
    private final AccountViewService accountViewService;

    /**
     * Account-update service collaborator &mdash; encapsulates the
     * full validation cascade and {@code @Transactional} dual-write of
     * {@code Account} and {@code Customer} entities. Originally
     * implemented as the {@code 9500-WRITE-PROCESSING} paragraph in
     * {@code app/cbl/COACTUPC.cbl} with {@code SYNCPOINT ROLLBACK} on
     * any validation failure or optimistic-lock conflict.
     * Constructor-injected and held {@code final} for immutability and
     * Spring proxy compatibility.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Primary constructor used by Spring's dependency-injection
     * container. Per AAP &sect;0.3.3 (Layered Architecture) and
     * &sect;0.7.1 (Refactor-Specific Rules), constructor injection is
     * the only permitted injection style &mdash; no
     * {@code @Autowired} field injection, no setter injection. Both
     * collaborators must be non-null; misconfiguration would surface
     * as a Spring {@code BeanCreationException} at context startup,
     * not at first invocation.
     *
     * @param accountViewService   the {@link AccountViewService}
     *                             collaborator that delivers the
     *                             read-only account view per
     *                             {@code GET /api/accounts/{id}};
     *                             never {@code null}
     * @param accountUpdateService the {@link AccountUpdateService}
     *                             collaborator that performs the
     *                             read-modify-write per
     *                             {@code PUT /api/accounts/{id}};
     *                             never {@code null}
     */
    public AccountController(AccountViewService accountViewService,
                             AccountUpdateService accountUpdateService) {
        this.accountViewService = Objects.requireNonNull(
                accountViewService, "accountViewService");
        this.accountUpdateService = Objects.requireNonNull(
                accountUpdateService, "accountUpdateService");
    }

    // =========================================================================
    // GET /api/accounts/{id}
    // Replaces CICS COACTVWC (Tran-ID CAVW, account view)
    // =========================================================================

    /**
     * Returns the joined account/customer/card-xref view for the
     * supplied 11-digit account ID.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COACTVWC.cbl:PROCESS-ENTER-KEY} which:</p>
     * <ol>
     *   <li>Read the {@code ACCOUNT-RECORD} from {@code ACCTDAT}
     *       keyed on {@code ACCT-ID} (PIC 9(11)).</li>
     *   <li>Joined with {@code CUSTDAT} keyed on {@code CUST-ID}.</li>
     *   <li>Joined with {@code CARDXREF} via the {@code CXACAIX} AIX
     *       on {@code XREF-ACCT-ID}.</li>
     *   <li>Populated the {@code COACTVWAO} symbolic-map fields and
     *       issued {@code SEND MAP} to render the 3270 screen.</li>
     * </ol>
     *
     * <p>The Java target performs the equivalent join via
     * {@link AccountViewService#getAccountView(Long)} which executes
     * a cache-aside lookup followed by three JPA reads (XREF first,
     * then ACCT, then CUST) preserving the COBOL paragraph order so
     * that any {@code RecordNotFoundException} reports the missing
     * dataset matching the COBOL error message.</p>
     *
     * <p><b>Authorization:</b> {@code @PreAuthorize("hasAnyRole('USER',
     * 'ADMIN')")}. Any authenticated operator can view any account
     * &mdash; this mirrors the COBOL source, which restricts the
     * inquiry endpoint only by sign-on requirement, not by role. An
     * unauthenticated request yields HTTP 401; a request without the
     * required role yields HTTP 403 (both translated by
     * {@code GlobalExceptionHandler}).</p>
     *
     * <p><b>Path-variable validation:</b>
     * {@code @NotNull @Min(value = 1L)} on the {@code id} parameter
     * enforces a positive 11-digit account number matching the COBOL
     * {@code ACCT-ID PIC 9(11)} semantics from
     * {@code app/cpy/CVACT01Y.cpy}. A non-positive ID is rejected with
     * HTTP 400 before the service is invoked. The validation requires
     * the class-level {@link Validated @Validated} annotation to be
     * active for path-variable constraints to fire.</p>
     *
     * @param id the 11-digit account identifier
     *           ({@code ACCT-ID PIC 9(11)} from
     *           {@code app/cpy/CVACT01Y.cpy}); must be non-null and
     *           strictly positive
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link AccountViewDto} wrapped in
     *         {@link ApiResponse#success(Object)};
     *         HTTP 400 if {@code id} fails Bean Validation;
     *         HTTP 401 if unauthenticated;
     *         HTTP 403 if authenticated but lacking USER/ADMIN role;
     *         HTTP 404 if no account / customer / xref record exists
     *         for the supplied ID (any of three
     *         {@code RecordNotFoundException} variants from the
     *         service layer)
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get account details by ID",
            description = "Returns account, customer, and card cross-reference "
                    + "data joined via the CXACAIX alternate index. Replaces "
                    + "CICS COACTVWC (Account View / Tran-ID CAVW)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Account view returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid account ID format (non-positive or null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthenticated (JWT missing or invalid)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden (authenticated principal lacks USER or ADMIN role)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account not found")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<AccountViewDto>> getAccount(
            @PathVariable("id")
            @NotNull(message = "Account ID is required")
            @Min(value = 1L, message = "Account ID must be positive")
            @Parameter(
                    description = "11-digit account ID (COBOL ACCT-ID PIC 9(11) "
                            + "from app/cpy/CVACT01Y.cpy)",
                    example = "10000000001",
                    required = true)
            Long id) {
        // COBOL: COACTVWC / Tran-ID CAVW
        //   PROCESS-ENTER-KEY → 9000-READ-ACCT → READ-CXACAIX
        //                    → READ-ACCTDAT  → READ-CUSTDAT
        // The service composes the three reads with the COBOL
        // paragraph ordering preserved so that the RecordNotFoundException
        // message identifies the specific missing dataset (XREF / ACCT /
        // CUST). PII discipline: log only the account ID — never balance,
        // SSN, name, address, or DOB (AAP §0.6.6 / §0.7.2).
        LOG.debug("Account view requested for accountId={}", id);
        AccountViewDto view = accountViewService.getAccountView(id);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    // =========================================================================
    // PUT /api/accounts/{id}
    // Replaces CICS COACTUPC (Tran-ID CAUP, account update w/ SYNCPOINT)
    // =========================================================================

    /**
     * Applies validated updates to the account with JPA
     * {@code @Version} optimistic locking.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COACTUPC.cbl:9500-WRITE-PROCESSING} which:</p>
     * <ol>
     *   <li>{@code RECEIVE MAP COACTUPA} from
     *       {@code app/bms/COACTUP.bms} (input fields and snapshot of
     *       before-image values).</li>
     *   <li>{@code 1200-EDIT-MAP-INPUTS} (validation cascade including
     *       SSN format, US state, ZIP-prefix combinations, NANPA area
     *       codes, dates).</li>
     *   <li>{@code READ UPDATE ACCTDAT} (lock the account record).</li>
     *   <li>{@code 9700-CHECK-CHANGE-IN-REC} (compare before/after
     *       images of every ACCT-* and CUST-* field; on mismatch raise
     *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and issue
     *       {@code SYNCPOINT ROLLBACK}).</li>
     *   <li>{@code REWRITE ACCTDAT} with new values.</li>
     *   <li>{@code READ UPDATE CUSTDAT}, before/after compare, then
     *       {@code REWRITE CUSTDAT}.</li>
     *   <li>{@code SYNCPOINT} (commit) on success.</li>
     * </ol>
     *
     * <p>The Java target replaces these steps with:</p>
     * <ul>
     *   <li>Jakarta Bean Validation cascade via
     *       {@link Valid @Valid} on {@link AccountUpdateDto} (record-level
     *       constraints fire automatically &mdash; 27 components).</li>
     *   <li>{@link AccountUpdateService#updateAccount(Long,
     *       AccountUpdateDto)} performs the service-layer cross-field
     *       validation (SSN part-1, state-code lookup, state-ZIP
     *       combination, NANPA area-code lookup, date semantic
     *       validity).</li>
     *   <li>JPA {@code @Version} on the {@code Account} entity for
     *       optimistic locking; the {@link AccountUpdateDto#version()}
     *       field carries the version value from the prior GET, and
     *       mismatch raises
     *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
     *       (HTTP 409).</li>
     *   <li>{@code @Transactional(rollbackFor = Exception.class,
     *       isolation = Isolation.READ_COMMITTED)} on the service
     *       method declares the dual Account+Customer write as a
     *       single atomic unit, replacing the COBOL
     *       {@code SYNCPOINT ROLLBACK} contract.</li>
     * </ul>
     *
     * <p><b>Path-vs-body consistency check:</b> the controller verifies
     * that the {@code id} path variable equals the {@code accountId}
     * field in the request body. Mismatches throw
     * {@link ValidationException} (HTTP 400) to prevent IDOR-style
     * confusion where a client could
     * {@code PUT /api/accounts/123} with a body claiming
     * {@code "accountId": 456}. This is defense-in-depth: the service
     * layer always uses the URL-path {@code id} as the authoritative
     * lookup key (not {@code request.accountId()}), so the consistency
     * check exists to surface intent-mismatch errors early and clearly
     * (AAP &sect;0.7.2 PCI-DSS layering).</p>
     *
     * <p><b>Authorization:</b> {@code @PreAuthorize("hasAnyRole('USER',
     * 'ADMIN')")}. The COBOL source program {@code COACTUPC} did not
     * gate updates by role &mdash; it was reachable from the main menu
     * accessible to any authenticated operator &mdash; so the Java
     * target preserves that semantic (AAP &sect;0.7.1 Minimal Change
     * Clause).</p>
     *
     * <p><b>Path-variable validation:</b>
     * {@code @NotNull @Min(value = 1L)} enforces a positive 11-digit
     * account number matching the COBOL {@code ACCT-ID PIC 9(11)}
     * semantics. Failures emit HTTP 400 via
     * {@code GlobalExceptionHandler}.</p>
     *
     * <p><b>Service-layer exceptions translated by
     * {@code GlobalExceptionHandler}:</b></p>
     * <ul>
     *   <li>{@link ValidationException} &rarr; HTTP 400
     *       (with {@code fieldErrors[]})</li>
     *   <li>{@code RecordNotFoundException} &rarr; HTTP 404</li>
     *   <li>{@code ConcurrentModificationException} &rarr; HTTP 409
     *       (the FQN from {@code com.awsm2.carddemo.exception} &mdash;
     *       distinct from {@link java.util.ConcurrentModificationException})</li>
     *   <li>{@code OnSizeErrorException} &rarr; HTTP 500
     *       (replicates COBOL {@code ON SIZE ERROR} arithmetic
     *       overflow on any monetary computation; AAP &sect;0.7.1)</li>
     * </ul>
     *
     * @param id      the 11-digit account identifier from the URL
     *                path; must be non-null and strictly positive
     * @param request the validated {@link AccountUpdateDto} containing
     *                the {@code accountId} (must equal {@code id}),
     *                the {@code version} for optimistic locking, and
     *                all updatable account/customer fields. The DTO
     *                masks its {@code customerSsn} in {@code toString()}
     *                for log/audit safety per AAP &sect;0.6.6
     * @return {@link ResponseEntity} with HTTP 200 and the updated
     *         {@link AccountViewDto} (carrying the post-update
     *         authoritative state) wrapped in
     *         {@link ApiResponse#success(Object, String)};
     *         HTTP 400 on Bean Validation or path/body mismatch;
     *         HTTP 401 if unauthenticated;
     *         HTTP 403 if lacking USER/ADMIN role;
     *         HTTP 404 if the account does not exist;
     *         HTTP 409 on optimistic-lock conflict;
     *         HTTP 500 on arithmetic overflow (OnSizeErrorException)
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Update account details with optimistic locking",
            description = "Updates account and associated customer fields "
                    + "atomically. Uses JPA @Version optimistic locking; "
                    + "version mismatch returns HTTP 409 Conflict. Replaces "
                    + "CICS COACTUPC (Account Update / Tran-ID CAUP) including "
                    + "the SYNCPOINT/SYNCPOINT ROLLBACK boundary on snapshot "
                    + "mismatch — the only explicit SYNCPOINT ROLLBACK in the "
                    + "entire CardDemo source per AAP §0.1.1."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Account updated successfully (response carries new version)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed (SSN, state, ZIP, dates, amounts, "
                            + "missing version, or path/body accountId mismatch)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthenticated (JWT missing or invalid)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden (authenticated principal lacks USER or ADMIN role)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Concurrent modification — optimistic-lock version mismatch"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "On size error — arithmetic overflow (COBOL ON SIZE ERROR)")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<AccountViewDto>> updateAccount(
            @PathVariable("id")
            @NotNull(message = "Account ID is required")
            @Min(value = 1L, message = "Account ID must be positive")
            @Parameter(
                    description = "11-digit account ID (COBOL ACCT-ID PIC 9(11) "
                            + "from app/cpy/CVACT01Y.cpy)",
                    example = "10000000001",
                    required = true)
            Long id,
            @Valid @RequestBody AccountUpdateDto request) {
        // COBOL: COACTUPC / Tran-ID CAUP
        //   9500-WRITE-PROCESSING + 9700-CHECK-CHANGE-IN-REC
        //   + SYNCPOINT ROLLBACK on snapshot mismatch.
        // The service uses JPA @Version for optimistic locking and
        // @Transactional(rollbackFor = Exception.class) to wrap the
        // dual Account+Customer write — replacing CICS SYNCPOINT /
        // SYNCPOINT ROLLBACK (the ONLY explicit rollback in source
        // per AAP §0.1.1) — with declarative transactional integrity
        // per AAP §0.4.1 transformation table.

        // ---- Path/body consistency check (defense-in-depth) ----
        // Prevents IDOR confusion where a client could
        // PUT /api/accounts/123 with body {"accountId": 456, ...}.
        // The service uses the URL-path id (not request.accountId())
        // as the authoritative lookup key — so this check exists to
        // surface intent-mismatch errors early and clearly, matching
        // the COBOL pattern where the SCREEN ACCT-ID and the
        // WORKING-STORAGE ACCT-ID must be identical (paragraph
        // 1200-EDIT-MAP-INPUTS in COACTUPC.cbl).
        if (!Objects.equals(id, request.accountId())) {
            // PII-safe log: only the two numeric account IDs — never
            // SSN, name, address, or other PII from the request body.
            LOG.warn("Account update rejected: path id={} != body accountId={}",
                    id, request.accountId());
            throw new ValidationException(
                    "ACCOUNT_ID_MISMATCH",
                    "Path account ID must match request body accountId");
        }

        // PII-safe log: only accountId and version (non-sensitive
        // optimistic-lock seed) — never SSN, name, address, or any
        // other PII field from the request.toString() (which would
        // mask the SSN anyway via AccountUpdateDto's overridden
        // toString() per AAP §0.6.6, but we don't even reference
        // toString() here as a defensive layering measure).
        LOG.info("Account update requested for accountId={} version={}",
                id, request.version());

        // Service contract: updateAccount(Long acctId, AccountUpdateDto request)
        // returns AccountViewDto representing the post-update authoritative
        // view of the account + customer pair. The acctId comes from the
        // URL path variable (already validated for IDOR via the
        // consistency check above) and is the source of truth for the
        // lookup, NOT request.accountId().
        AccountViewDto updated = accountUpdateService.updateAccount(id, request);

        LOG.info("Account update successful for accountId={}", id);

        return ResponseEntity.ok(
                ApiResponse.success(updated, "Account updated successfully"));
    }
}
