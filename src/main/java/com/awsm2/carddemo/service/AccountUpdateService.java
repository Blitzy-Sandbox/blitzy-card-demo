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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.validation.DateValidationService;
import com.awsm2.carddemo.validation.ValidationLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Account-update service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COACTUPC.cbl} (CICS transaction id
 * {@code CAUP}).
 *
 * <p>This service updates an existing account + customer record pair
 * with optimistic-locking, transactional integrity, and cache
 * eviction. {@code COACTUPC} is the only COBOL program in the source
 * that uses {@code SYNCPOINT ROLLBACK} (AAP &sect;0.1.1) &mdash; the
 * Java target reproduces this single transactional boundary via
 * {@link Transactional &#64;Transactional(rollbackFor = Exception.class)}
 * around the dual write of {@link Account} and {@link Customer}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COACTUPC.cbl} (CICS TRANID
 *       {@code 'CAUP'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COACTUP.bms} (mapset
 *       {@code COACTUP}, map {@code COACTUPA}).</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD} 300 bytes) and
 *       {@code app/cpy/CVCUS01Y.cpy} ({@code CUSTOMER-RECORD} 500
 *       bytes).</li>
 *   <li><b>VSAM clusters:</b> {@code ACCTDAT}, {@code CUSTDAT}
 *       &mdash; replaced by PostgreSQL tables {@code accounts} and
 *       {@code customers}.</li>
 * </ul>
 *
 * <h2>Optimistic locking (AAP &sect;0.7.1)</h2>
 *
 * <p>The COBOL source performs a before/after image comparison after
 * {@code READ UPDATE}: the program reads the record, displays it for
 * editing, then reads it a second time and compares the two snapshots
 * before {@code REWRITE}. Any difference between the two reads (i.e., a
 * concurrent modification by another operator) aborts the update with
 * a "snapshot mismatch" error.</p>
 *
 * <p>The Java target replaces this manual mechanism with JPA
 * {@code @Version} on the {@link Account} entity. The caller supplies
 * the previously-loaded {@code version} value in
 * {@link AccountUpdateDto#version()}; Hibernate issues an
 * {@code UPDATE accounts SET ... WHERE acct_id = ? AND version = ?}
 * and detects a 0-row match as
 * {@link jakarta.persistence.OptimisticLockException}. Spring's
 * persistence exception translation wraps that as
 * {@link OptimisticLockingFailureException}, which
 * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict.</p>
 *
 * <h2>Transactional boundary (AAP &sect;0.7.1)</h2>
 *
 * <p>The {@link Transactional &#64;Transactional(rollbackFor =
 * Exception.class)} annotation declares the entire account + customer
 * update as a single atomic unit. If the customer update fails after
 * the account has been saved, Hibernate rolls back both writes &mdash;
 * preserving the COBOL {@code SYNCPOINT ROLLBACK} contract.</p>
 *
 * <h2>Cache invalidation (AAP &sect;0.7.1)</h2>
 *
 * <p>Per the cache-aside contract, this service invalidates the
 * {@code accountView} cache entry for the affected account ID
 * immediately after the database write commits. The next
 * {@code AccountViewService.viewAccount(...)} call repopulates the
 * cache from the new authoritative database state.</p>
 *
 * <h2>MSK event publication (AAP &sect;0.6.5)</h2>
 *
 * <p>On successful update, this service publishes
 * {@code account.updated} to MSK partitioned by account ID to
 * guarantee per-account ordering. Downstream consumers (audit,
 * fraud detection, reporting) are notified asynchronously.</p>
 *
 * @see com.awsm2.carddemo.repository.AccountRepository
 * @see com.awsm2.carddemo.repository.CustomerRepository
 * @see com.awsm2.carddemo.adapter.CacheService
 * @see com.awsm2.carddemo.adapter.KafkaEventPublisher
 * @see com.awsm2.carddemo.dto.AccountUpdateDto
 */
@Service
public class AccountUpdateService {

    /** Class-level SLF4J logger &mdash; structured JSON output. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    /** Audit event name emitted on successful update. */
    private static final String EVENT_ACCT_UPDATED = "account.updated";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CacheService cacheService;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;
    private final ValidationLookupService validationLookupService;
    private final DateValidationService dateValidationService;

    /**
     * Constructor &mdash; Spring supplies the collaborators.
     */
    public AccountUpdateService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CacheService cacheService,
            KafkaEventPublisher kafkaEventPublisher,
            AuditLogService auditLogService,
            ValidationLookupService validationLookupService,
            DateValidationService dateValidationService) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.customerRepository = Objects.requireNonNull(
                customerRepository, "customerRepository");
        this.cacheService = Objects.requireNonNull(
                cacheService, "cacheService");
        this.kafkaEventPublisher = Objects.requireNonNull(
                kafkaEventPublisher, "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(
                auditLogService, "auditLogService");
        this.validationLookupService = Objects.requireNonNull(
                validationLookupService, "validationLookupService");
        this.dateValidationService = Objects.requireNonNull(
                dateValidationService, "dateValidationService");
    }

    /**
     * Apply the supplied edits to the account + customer pair.
     *
     * <p>COBOL paragraph mapping:</p>
     * <table>
     *   <caption>COBOL COACTUPC.cbl &harr;
     *            AccountUpdateService.updateAccount(...)</caption>
     *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
     *   <tr><td>{@code 1000-PROCESS-INPUTS} (Jakarta Bean Validation
     *       at the controller plus state/zip cross-checks here)</td>
     *       <td>{@link #validate(AccountUpdateDto)}</td></tr>
     *   <tr><td>{@code 9000-READ-ACCT}</td>
     *       <td>{@link AccountRepository#findById(Object)}</td></tr>
     *   <tr><td>{@code 9200-READ-CUST}</td>
     *       <td>{@link CustomerRepository#findById(Object)}</td></tr>
     *   <tr><td>Snapshot before/after compare &amp; SYNCPOINT ROLLBACK</td>
     *       <td>JPA {@code @Version} on {@link Account} +
     *       {@link Transactional &#64;Transactional(rollbackFor = Exception.class)}</td></tr>
     *   <tr><td>{@code 9700-UPDATE-ACCT}
     *       (EXEC CICS REWRITE DATASET('ACCTDAT'))</td>
     *       <td>{@link AccountRepository#save(Object)}</td></tr>
     *   <tr><td>{@code 9800-UPDATE-CUST}</td>
     *       <td>{@link CustomerRepository#save(Object)}</td></tr>
     *   <tr><td>Cache eviction (no COBOL analogue)</td>
     *       <td>{@link CacheService#evict(String, String)} on the
     *       {@code accountView} namespace</td></tr>
     *   <tr><td>SEND MAP with confirmation message</td>
     *       <td>Return the persisted DTO with the updated
     *       {@code version} value</td></tr>
     * </table>
     *
     * @param request the validated DTO carrying the desired field
     *                values plus the previously-loaded {@code version};
     *                never {@code null}
     * @return the persisted view; never {@code null}
     * @throws RecordNotFoundException             when the account or
     *                                             customer does not
     *                                             exist (HTTP 404)
     * @throws ValidationException                 when business-level
     *                                             checks fail (state
     *                                             code, zip prefix,
     *                                             date validity)
     * @throws OptimisticLockingFailureException   when the
     *                                             {@code @Version}
     *                                             value does not match
     *                                             (HTTP 409)
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountUpdateDto updateAccount(AccountUpdateDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        Long accountId = request.accountId();
        String cacheKey = String.format("%011d", accountId);

        // ---- COBOL 9000-READ-ACCT -------------------------------------
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCT_NOT_FOUND",
                        "Account not found: " + cacheKey));

        // ---- COBOL 9200-READ-CUST -------------------------------------
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new RecordNotFoundException(
                        "CUST_NOT_FOUND",
                        "Customer not found: " + request.customerId()));

        // ---- Snapshot compare (COBOL before/after image)
        //      replaced by JPA @Version check on Account: set the
        //      caller-supplied version onto the managed entity so
        //      Hibernate's UPDATE includes WHERE version = ?
        // ---------------------------------------------------------------
        if (request.version() == null) {
            throw new ValidationException(
                    "MISSING_VERSION",
                    "Caller must supply the previously-loaded version "
                            + "to prevent lost-update conflicts",
                    List.of(new ValidationException.FieldError(
                            "version", "version is required")));
        }
        account.setVersion(request.version());

        // ---- COBOL 9700-UPDATE-ACCT (REWRITE) -------------------------
        applyAccountEdits(account, request);
        Account saved = accountRepository.save(account);

        // ---- COBOL 9800-UPDATE-CUST -----------------------------------
        applyCustomerEdits(customer, request);
        Customer savedCustomer = customerRepository.save(customer);

        // ---- Cache eviction (cache-aside invalidation) ----------------
        // Failure to evict is non-fatal per CacheService fail-open contract.
        cacheService.evict(AccountViewService.CACHE_NS, cacheKey);

        // ---- MSK account.updated (AAP §0.6.5) -------------------------
        AccountUpdateDto response = toDto(saved, savedCustomer);
        kafkaEventPublisher.publishAccountUpdated(saved.getAcctId(), response);

        // ---- Audit (CloudTrail + OpenSearch) --------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountId", cacheKey);
        payload.put("version", saved.getVersion());
        auditLogService.auditEvent(EVENT_ACCT_UPDATED, "system", payload);

        LOG.info("Account updated accountId={} version={}",
                cacheKey, saved.getVersion());
        return response;
    }

    // -----------------------------------------------------------------
    // Validation -- preserves COBOL 1000-PROCESS-INPUTS semantics
    // (state codes, zip prefix combos, date validity) using the
    // dedicated validation services per AAP §0.7.1.
    // -----------------------------------------------------------------

    /**
     * Cross-field validation that the controller-layer Jakarta Bean
     * Validation cannot perform (e.g., state-code &amp; zip-prefix pair
     * lookups in {@code CSLKPCDY.cpy}, date validity per
     * {@code CSUTLDPY.cpy}/{@code CSUTLDTC.cbl}).
     */
    private void validate(AccountUpdateDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        // State code lookup (CSLKPCDY US-STATE-CODES table)
        if (request.stateCode() != null
                && !validationLookupService.isValidStateCode(request.stateCode())) {
            errors.add(new ValidationException.FieldError(
                    "stateCode", "Invalid US state/territory code"));
        }

        // State + ZIP prefix combination (CSLKPCDY valid-state-zip table)
        if (request.stateCode() != null && request.zipCode() != null
                && !validationLookupService.isValidStateZipCombination(
                        request.stateCode(), request.zipCode())) {
            errors.add(new ValidationException.FieldError(
                    "zipCode",
                    "ZIP prefix does not match supplied state code"));
        }

        // Date validity (CSUTLDPY / CSUTLDTC date validation logic)
        validateDate(errors, request.openDate(), "openDate");
        validateDate(errors, request.expirationDate(), "expirationDate");
        validateDate(errors, request.reissueDate(), "reissueDate");
        validateDate(errors, request.dateOfBirth(), "dateOfBirth");

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "INVALID_INPUT",
                    "Account/customer update validation failed",
                    errors);
        }
    }

    private void validateDate(List<ValidationException.FieldError> errors,
                              LocalDate date, String fieldName) {
        if (date == null) {
            return;
        }
        // DateValidationService validates the string form (the COBOL
        // CSUTLDTC source operates on alphanumeric date strings).
        // Convert the LocalDate back to YYYY-MM-DD for the parity check.
        DateValidationService.DateValidationResult result =
                dateValidationService.validate(date.toString());
        if (!result.isValid()) {
            errors.add(new ValidationException.FieldError(
                    fieldName, result.errorMessage()));
        }
    }

    // -----------------------------------------------------------------
    // Apply incoming DTO edits to the managed JPA entity instances
    // (preserves COBOL field-by-field MOVE semantics)
    // -----------------------------------------------------------------

    private static void applyAccountEdits(Account account, AccountUpdateDto request) {
        // ACTIVE-STATUS, CURR-BAL, CREDIT-LIMIT, CASH-CREDIT-LIMIT,
        // OPEN-DATE, EXPIRATION-DATE, REISSUE-DATE, CURR-CYC-CREDIT,
        // CURR-CYC-DEBIT, ADDR-ZIP, GROUP-ID  -- all from CVACT01Y.cpy
        account.setAcctActiveStatus(request.activeStatus());
        account.setAcctCurrBal(safeBalance(request.currentBalance()));
        account.setAcctCreditLimit(safeBalance(request.creditLimit()));
        account.setAcctCashCreditLimit(safeBalance(request.cashCreditLimit()));
        account.setAcctOpenDate(request.openDate());
        account.setAcctExpirationDate(request.expirationDate());
        account.setAcctReissueDate(request.reissueDate());
        account.setAcctCurrCycCredit(safeBalance(request.currentCycleCredit()));
        account.setAcctCurrCycDebit(safeBalance(request.currentCycleDebit()));
        account.setAcctAddrZip(request.addressZip());
        account.setAcctGroupId(request.accountGroupId());
    }

    private static void applyCustomerEdits(Customer customer, AccountUpdateDto request) {
        customer.setCustFirstName(request.firstName());
        customer.setCustMiddleName(request.middleName());
        customer.setCustLastName(request.lastName());
        customer.setCustSsn(request.customerSsn());
        customer.setCustPhoneNum1(request.phoneNumber1());
        customer.setCustPhoneNum2(request.phoneNumber2());
        customer.setCustAddrLine1(request.addressLine1());
        customer.setCustAddrLine2(request.addressLine2());
        customer.setCustAddrLine3(request.addressLine3());
        customer.setCustAddrStateCd(request.stateCode());
        customer.setCustAddrCountryCd(request.countryCode());
        customer.setCustAddrZip(request.zipCode());
        customer.setCustDobYyyyMmDd(request.dateOfBirth());
        customer.setCustGovtIssuedId(request.governmentIssuedId());
        customer.setCustEftAccountId(request.eftAccountId());
        customer.setCustPriCardHolderInd(request.primaryCardHolderIndicator());
        if (request.ficoCreditScore() != null) {
            customer.setCustFicoCreditScore(request.ficoCreditScore());
        }
    }

    /**
     * Defensive helper: null balance fields are normalised to
     * {@link BigDecimal#ZERO} so the database NOT NULL constraints are
     * not violated. This mirrors the COBOL behaviour where a
     * {@code PIC S9(10)V99} field is always populated with a numeric
     * value (zero by default).
     */
    private static BigDecimal safeBalance(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Build the response DTO from the persisted entities. */
    private static AccountUpdateDto toDto(Account a, Customer c) {
        return new AccountUpdateDto(
                a.getAcctId(),
                a.getAcctActiveStatus(),
                a.getAcctCurrBal(),
                a.getAcctCreditLimit(),
                a.getAcctCashCreditLimit(),
                a.getAcctOpenDate(),
                a.getAcctExpirationDate(),
                a.getAcctReissueDate(),
                a.getAcctCurrCycCredit(),
                a.getAcctCurrCycDebit(),
                a.getAcctAddrZip(),
                a.getAcctGroupId(),
                c.getCustId(),
                c.getCustFirstName(),
                c.getCustMiddleName(),
                c.getCustLastName(),
                c.getCustSsn(),
                c.getCustPhoneNum1(),
                c.getCustPhoneNum2(),
                c.getCustAddrLine1(),
                c.getCustAddrLine2(),
                c.getCustAddrLine3(),
                c.getCustAddrStateCd(),
                c.getCustAddrCountryCd(),
                c.getCustAddrZip(),
                c.getCustDobYyyyMmDd(),
                c.getCustGovtIssuedId(),
                c.getCustEftAccountId(),
                c.getCustPriCardHolderInd(),
                c.getCustFicoCreditScore(),
                a.getVersion());
    }
}
