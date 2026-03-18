/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;

/**
 * Spring MVC REST controller providing account view and update operations,
 * replacing CICS BMS screens COACTVW (account view) and COACTUP (account update)
 * from the CardDemo mainframe application.
 *
 * <p>This controller is a thin delegation layer — all business logic, validation,
 * and transaction management resides in the service classes. The controller is
 * responsible only for HTTP binding, request logging, and response wrapping.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>BMS Screen to REST endpoint mapping</caption>
 *   <tr><th>BMS Screen</th><th>COBOL Program</th><th>REST Endpoint</th><th>HTTP Method</th></tr>
 *   <tr><td>COACTVW (Account View)</td><td>COACTVWC.cbl (941 lines)</td>
 *       <td>/api/accounts/{id}</td><td>GET</td></tr>
 *   <tr><td>COACTUP (Account Update)</td><td>COACTUPC.cbl (4,236 lines)</td>
 *       <td>/api/accounts/{id}</td><td>PUT</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Successful account view or update</li>
 *   <li><strong>400 Bad Request</strong> — Validation failures (SSN, FICO, NANPA,
 *       date, state/ZIP errors) — handled by ControllerAdvice</li>
 *   <li><strong>404 Not Found</strong> — Account, customer, or cross-reference
 *       not found — handled by ControllerAdvice</li>
 *   <li><strong>409 Conflict</strong> — Optimistic locking failure during update
 *       (maps COBOL LOCKED-BUT-UPDATE-FAILED) — handled by ControllerAdvice</li>
 * </ul>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>No exception handling in controller — all exceptions propagate to the global
 *       {@code @ControllerAdvice} handler for consistent error response formatting.</li>
 *   <li>No business logic in controller — all 25+ field validations, cross-reference
 *       resolution, and dual-dataset atomic updates are delegated to services.</li>
 *   <li>All monetary amounts are {@link java.math.BigDecimal} — zero float/double
 *       substitution per AAP §0.8.2 decimal precision rules.</li>
 *   <li>Structured logging with SLF4J supports correlation ID propagation via MDC
 *       per AAP §0.7.1 observability requirements.</li>
 * </ul>
 *
 * <p>Source traceability: COACTVWC.cbl + COACTUPC.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see AccountDto
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs account view and update operations at INFO level for audit trail
     * and observability, supporting the AAP §0.7.1 requirement for structured
     * logging with correlation IDs propagated via MDC.
     */
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    /**
     * Account view service implementing multi-dataset read chain.
     * Migrated from COBOL program COACTVWC.cbl (941 lines).
     * Performs: CardCrossReference (CXACAIX) → Account (ACCTDAT) → Customer (CUSTDAT).
     */
    private final AccountViewService accountViewService;

    /**
     * Account update service implementing dual-dataset atomic update with SYNCPOINT semantics.
     * Migrated from COBOL program COACTUPC.cbl (4,236 lines — most complex program).
     * Performs: 25+ field validations → Account + Customer atomic update with
     * {@code @Transactional(rollbackFor = Exception.class)} and JPA {@code @Version}
     * optimistic locking.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructs a new AccountController with the required service dependencies.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency injection
     * pattern. When a class has a single constructor, Spring automatically uses it for
     * autowiring without requiring the {@code @Autowired} annotation.</p>
     *
     * @param accountViewService   service for account view operations (COACTVWC.cbl)
     * @param accountUpdateService service for account update operations (COACTUPC.cbl)
     */
    public AccountController(AccountViewService accountViewService,
                             AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Retrieves a comprehensive account view by account ID.
     *
     * <p>Maps COBOL program COACTVWC.cbl (941 lines, CICS transaction CA00) which
     * performs a multi-dataset read chain:</p>
     * <ol>
     *   <li><strong>Cross-Reference Lookup (9200-GETCARDXREF-BYACCT):</strong>
     *       Reads CXACAIX alternate index by account ID to obtain customer ID</li>
     *   <li><strong>Account Data Read (9300-GETACCTDATA-BYACCT):</strong>
     *       Reads ACCTDAT primary key by account ID for financial data</li>
     *   <li><strong>Customer Data Read (9400-GETCUSTDATA-BYCUST):</strong>
     *       Reads CUSTDAT by customer ID for personal/contact information</li>
     * </ol>
     *
     * <p>The returned {@link AccountDto} contains 27+ fields including 5 BigDecimal
     * monetary fields (acctCurrBal, acctCreditLimit, acctCashCreditLimit,
     * acctCurrCycCredit, acctCurrCycDebit) and 4 LocalDate date fields.</p>
     *
     * <p>This is a read-only operation — no {@code @Transactional} annotation is
     * needed at the controller level because the service uses
     * {@code @Transactional(readOnly = true)} internally.</p>
     *
     * @param id the account identifier (11-digit numeric string, maps COBOL PIC 9(11))
     * @return HTTP 200 with {@link AccountDto} containing combined account and customer data
     * @throws com.cardemo.exception.RecordNotFoundException if account, customer, or
     *         cross-reference record is not found (mapped to HTTP 404 by ControllerAdvice)
     * @throws IllegalArgumentException if the account ID is invalid (null, blank,
     *         non-numeric, all zeros, or wrong length)
     */
    @GetMapping("/{id}")
    public ResponseEntity<AccountDto> getAccount(@PathVariable String id) {
        logger.info("Viewing account {}", id);
        AccountDto result = accountViewService.getAccountView(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Updates an account with the provided data, performing a dual-dataset atomic update.
     *
     * <p>Maps COBOL program COACTUPC.cbl (4,236 lines — most complex program in the
     * CardDemo application, CICS transaction CA01) which performs:</p>
     * <ul>
     *   <li><strong>Field Validation (1200-EDIT-MAP-INPUTS):</strong> 25+ field
     *       validations including SSN (3-part with 000/666/900-999 invalid ranges),
     *       FICO score (300-850), NANPA area codes, US state/ZIP cross-validation,
     *       and date validation via LE CEEDAYS equivalent</li>
     *   <li><strong>Dual-Dataset Update (9600-WRITE-PROCESSING):</strong> Account and
     *       Customer records are updated atomically within a single database transaction,
     *       mapping the COBOL EXEC CICS SYNCPOINT ROLLBACK pattern (AAP §0.8.4)</li>
     *   <li><strong>Optimistic Concurrency (9700-CHECK-CHANGE-IN-REC):</strong> JPA
     *       {@code @Version} annotation on the Account entity detects concurrent
     *       modifications, mapping the COBOL DATA-WAS-CHANGED-BEFORE-UPDATE check</li>
     * </ul>
     *
     * <p>The {@code @Valid} annotation triggers Jakarta Bean Validation on the
     * {@link AccountDto} request body, enforcing {@code @Size} constraints on all
     * string fields matching COBOL PIC clause lengths. This replaces the COBOL
     * CSSETATY.cpy BMS field attribute setting with declarative validation.</p>
     *
     * <p>All monetary amounts in the DTO use {@link java.math.BigDecimal} with zero
     * float/double substitution per AAP §0.8.2 decimal precision rules.</p>
     *
     * @param id         the account identifier (11-digit numeric string, maps COBOL PIC 9(11))
     * @param accountDto the updated account data (validated via {@code @Valid})
     * @return HTTP 200 with {@link AccountDto} containing the persisted updated values
     * @throws com.cardemo.exception.RecordNotFoundException if account, customer, or
     *         cross-reference not found (mapped to HTTP 404 by ControllerAdvice)
     * @throws com.cardemo.exception.ConcurrentModificationException if optimistic lock
     *         conflict detected (mapped to HTTP 409 by ControllerAdvice)
     * @throws com.cardemo.exception.ValidationException if field validation fails
     *         (mapped to HTTP 400 by ControllerAdvice)
     */
    @PutMapping("/{id}")
    public ResponseEntity<AccountDto> updateAccount(@PathVariable String id,
                                                    @Valid @RequestBody AccountDto accountDto) {
        logger.info("Updating account {}", id);
        AccountDto result = accountUpdateService.updateAccount(id, accountDto);
        return ResponseEntity.ok(result);
    }
}
