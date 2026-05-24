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
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Account REST controller &mdash; view and update account details.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> this controller replaces
 * two CICS COBOL programs operating on the {@code ACCTDATA} VSAM KSDS
 * cluster (record layout {@code app/cpy/CVACT01Y.cpy} ACCOUNT-RECORD,
 * 300-byte) with optimistic-lock semantics:</p>
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} (CICS transaction id {@code CAVW})
 *       &mdash; Account inquiry, joining {@code ACCTDATA} with
 *       {@code CUSTDATA} and {@code CARDXREF} (via {@code CXACAIX} AIX
 *       on {@code XREF-ACCT-ID}) to render the
 *       {@code app/bms/COACTVW.bms} screen. The Java target uses
 *       {@link AccountViewService#getAccountView(Long)} which composes
 *       these reads.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} (CICS transaction id {@code CAUP})
 *       &mdash; Account maintenance with full validation cascade
 *       (NANPA area code, US state, ZIP-state combination, government
 *       ID, FICO score, opening/expiration dates), driven by the
 *       {@code app/bms/COACTUP.bms} screen. The Java target uses
 *       {@link AccountUpdateService#updateAccount(AccountUpdateDto)}
 *       with JPA {@code @Version} optimistic locking replacing the
 *       COBOL before/after snapshot comparison.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/accounts/{id}} &mdash; returns the
 *       {@link AccountViewDto} for the supplied 11-digit account ID
 *       (replaces CICS {@code COACTVWC} / {@code CAVW}). Available to
 *       any authenticated principal (USER or ADMIN).</li>
 *   <li>{@code PUT /api/accounts/{id}} &mdash; applies validated
 *       updates with optimistic locking (replaces CICS
 *       {@code COACTUPC} / {@code CAUP}). Available to any
 *       authenticated principal (USER or ADMIN) &mdash; the COBOL
 *       source did not restrict the maintenance program to admin
 *       users either; the security gate is upstream at the
 *       sign-on / menu level.</li>
 * </ul>
 *
 * <p><b>Optimistic locking (AAP &sect;0.3.3, &sect;0.6.6):</b> the
 * {@code PUT} endpoint requires the client to echo the {@code version}
 * value returned by the prior {@code GET}. On version mismatch the JPA
 * provider raises {@link org.springframework.dao.OptimisticLockingFailureException}
 * which the {@code GlobalExceptionHandler} translates into HTTP 409
 * Conflict (mirroring the COBOL {@code COACTUPC.cbl} "Account record
 * changed after retrieval" branch).</p>
 *
 * <p><b>Path-variable validation:</b> Jakarta Bean Validation cannot be
 * applied directly to a primitive {@code Long} path variable. The
 * controller checks the {@code id} path variable matches the
 * {@code accountId} field carried in the {@link AccountUpdateDto} body
 * and rejects mismatches with HTTP 400 ({@code @ValidationException})
 * &mdash; this prevents IDOR (Insecure Direct Object Reference)
 * confusion where a client could PUT to {@code /api/accounts/123}
 * with a body claiming {@code accountId=456}.</p>
 *
 * <p><b>Layered architecture compliance:</b> thin Spring MVC fa&ccedil;ade;
 * no business state, no I/O, no repository or AWS-adapter access.
 * Delegates to {@link AccountViewService} and
 * {@link AccountUpdateService} via constructor injection.</p>
 *
 * <p><b>Standardized response envelope (AAP &sect;0.3.4):</b> all
 * responses use {@link ApiResponse#success(Object)}. Failures surface
 * as typed exceptions handled by {@code GlobalExceptionHandler}.</p>
 *
 * <p><b>PCI-DSS logging discipline (AAP &sect;0.6.6, &sect;0.7.2):</b>
 * the controller emits only the account ID and operation type
 * (non-sensitive). Account balances, SSN, customer PII, and other
 * sensitive fields are NEVER logged.</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see AccountViewDto
 * @see AccountUpdateDto
 * @see com.awsm2.carddemo.dto.ApiResponse
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts",
        description = "Account inquiry and maintenance. Replaces CICS "
                + "COACTVWC (Tran-ID CAVW, account view) and COACTUPC "
                + "(Tran-ID CAUP, account update).")
public class AccountController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2
     * structured JSON logging via Logback +
     * logstash-logback-encoder. PCI-DSS discipline (AAP &sect;0.6.6):
     * only the account ID (non-sensitive) and operation type are
     * logged; never balances, SSN, or customer PII.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /**
     * Account inquiry service collaborator &mdash; encapsulates the
     * three-table join ({@code Account} + {@code Customer} +
     * {@code CardCrossReference}) originally implemented as the
     * {@code 9000-READ-ACCT}, {@code 9100-READ-CUST}, and
     * {@code 9200-GETCARDXREF-BYACCT} paragraphs in
     * {@code app/cbl/COACTVWC.cbl}.
     */
    private final AccountViewService accountViewService;

    /**
     * Account update service collaborator &mdash; encapsulates the
     * full validation cascade and {@code @Transactional} dual-write of
     * {@code Account} and {@code Customer} entities. Originally
     * implemented as the {@code 9500-WRITE-PROCESSING} paragraph in
     * {@code app/cbl/COACTUPC.cbl} with {@code SYNCPOINT ROLLBACK} on
     * any validation failure.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param accountViewService   the {@link AccountViewService}
     *                             collaborator; never {@code null}
     * @param accountUpdateService the {@link AccountUpdateService}
     *                             collaborator; never {@code null}
     */
    public AccountController(AccountViewService accountViewService,
                             AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Returns the account view for the supplied 11-digit account ID.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COACTVWC.cbl:PROCESS-ENTER-KEY} which read the
     * {@code ACCOUNT-RECORD} from {@code ACCTDATA} keyed on
     * {@code ACCT-ID}, then joined with {@code CUSTDATA} (keyed on
     * {@code CUST-ID}) and {@code CARDXREF} (via {@code CXACAIX} AIX
     * on {@code XREF-ACCT-ID}) to populate the
     * {@code COACTVWAO} symbolic map fields.</p>
     *
     * <p><b>Authorization:</b>
     * {@link PreAuthorize @PreAuthorize("hasAnyRole('USER','ADMIN')")}.
     * Any authenticated operator can view any account &mdash; this
     * mirrors the COBOL source, which restricts the inquiry endpoint
     * only by sign-on requirement, not by role.</p>
     *
     * @param id the 11-digit account identifier
     *           ({@code ACCT-ID PIC 9(11)} from
     *           {@code app/cpy/CVACT01Y.cpy})
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link AccountViewDto} wrapped in {@link ApiResponse}.
     *         HTTP 404 ({@code RecordNotFoundException}) if no
     *         account exists for the supplied ID
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "View account details by ID",
            description = "Returns the joined account/customer/card-xref view "
                    + "for the supplied 11-digit account ID. Replaces CICS "
                    + "COACTVWC / Tran-ID CAVW (account inquiry)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Account view returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account not found")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<AccountViewDto>> getAccount(
            @PathVariable("id") Long id) {
        // COBOL: COACTVWC / Tran-ID CAVW -- 9000-READ-ACCT +
        //   9100-READ-CUST + 9200-GETCARDXREF-BYACCT (delegates to
        //   AccountViewService which composes the three reads per
        //   AAP §0.4.1).
        LOG.debug("Account view requested for accountId={}", id);
        AccountViewDto view = accountViewService.getAccountView(id);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /**
     * Applies validated updates to the account with optimistic locking.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COACTUPC.cbl:9500-WRITE-PROCESSING} which:</p>
     * <ol>
     *   <li>Re-read the {@code ACCOUNT-RECORD} from {@code ACCTDATA}
     *       under a {@code READ UPDATE} lock.</li>
     *   <li>Compared the read record against the snapshot taken at the
     *       prior screen render. On mismatch &rarr; "Account record
     *       changed after retrieval" branch and
     *       {@code SYNCPOINT ROLLBACK}.</li>
     *   <li>{@code REWRITE} the {@code ACCOUNT-RECORD} with new
     *       values.</li>
     *   <li>{@code READ UPDATE} the {@code CUSTOMER-RECORD} from
     *       {@code CUSTDATA}, compare snapshot, REWRITE.</li>
     *   <li>{@code SYNCPOINT} (commit) on success.</li>
     * </ol>
     *
     * <p>The Java target replaces these steps with a
     * {@code @Transactional} method on
     * {@link AccountUpdateService#updateAccount(AccountUpdateDto)}
     * using JPA {@code @Version} for optimistic locking; the
     * {@code AccountUpdateDto.version()} field carries the version
     * value from the prior {@code GET}, and JPA raises
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * (translated to HTTP 409 by {@code GlobalExceptionHandler}) on
     * mismatch.</p>
     *
     * <p><b>Path vs body ID consistency check:</b> the controller
     * verifies that the {@code id} path variable equals the
     * {@code accountId} field in the request body. Mismatches throw
     * {@link ValidationException} (HTTP 400) to prevent confusion and
     * IDOR-style attacks where a client could PUT to one URL with a
     * body claiming a different account ID.</p>
     *
     * <p><b>Authorization:</b>
     * {@link PreAuthorize @PreAuthorize("hasAnyRole('USER','ADMIN')")}.
     * The COBOL source program {@code COACTUPC} did not gate updates
     * by role &mdash; it was reachable from the main menu &mdash; so
     * the Java target preserves that semantic.</p>
     *
     * @param id      the 11-digit account identifier from the URL path
     * @param request the validated {@link AccountUpdateDto} containing
     *                the {@code accountId} (must equal {@code id}),
     *                {@code version} for optimistic locking, and all
     *                updatable account/customer fields
     * @return {@link ResponseEntity} with HTTP 200 and the updated
     *         {@link AccountUpdateDto} (with the incremented
     *         {@code version}) wrapped in {@link ApiResponse}.
     *         HTTP 409 on optimistic-lock conflict; HTTP 400 on
     *         validation failure; HTTP 404 if the account does not
     *         exist
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Update account details with optimistic locking",
            description = "Applies validated updates to the supplied account "
                    + "with JPA @Version optimistic locking. Replaces CICS "
                    + "COACTUPC / Tran-ID CAUP (account maintenance) including "
                    + "the SYNCPOINT ROLLBACK on snapshot mismatch."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Account updated successfully (returns new version)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (path/body mismatch, invalid field)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Optimistic-lock conflict (account changed since GET)")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<AccountUpdateDto>> updateAccount(
            @PathVariable("id") Long id,
            @Valid @RequestBody AccountUpdateDto request) {
        // COBOL: COACTUPC / Tran-ID CAUP -- 9500-WRITE-PROCESSING +
        //   SYNCPOINT ROLLBACK on snapshot mismatch (delegates to
        //   AccountUpdateService which uses JPA @Version for optimistic
        //   locking per AAP §0.4.1 transformation table).
        //
        // Path/body consistency check — prevents IDOR confusion where
        // a client could PUT /api/accounts/123 with body
        // {"accountId": 456, ...}.
        if (!Objects.equals(id, request.accountId())) {
            LOG.warn("Account update rejected: path id={} != body accountId={}",
                    id, request.accountId());
            throw new ValidationException(
                    "ACCOUNT_ID_MISMATCH",
                    "Path account ID must match request body accountId");
        }
        LOG.debug("Account update requested for accountId={}, version={}",
                id, request.version());
        AccountUpdateDto updated = accountUpdateService.updateAccount(request);
        return ResponseEntity.ok(ApiResponse.success(updated,
                "Account updated successfully"));
    }
}
