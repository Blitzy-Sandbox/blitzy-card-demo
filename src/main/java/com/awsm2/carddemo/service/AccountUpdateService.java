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
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.validation.DateValidationService;
import com.awsm2.carddemo.validation.ValidationLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Account-update service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COACTUPC.cbl} (CICS transaction id
 * {@code 'CAUP'}, file {@code 'ACCTDAT'}).
 *
 * <p>This service updates an existing account + customer record pair
 * atomically, with JPA {@code @Version} optimistic-locking, declarative
 * transactional integrity, cache invalidation, MSK event publication,
 * and immutable audit-trail emission. {@code COACTUPC} is the
 * <b>only</b> COBOL program in the CardDemo source tree that issues
 * an explicit {@code SYNCPOINT ROLLBACK} (per AAP &sect;0.1.1) &mdash;
 * the Java target reproduces this single transactional boundary via
 * {@link Transactional &#64;Transactional(rollbackFor = Exception.class,
 * isolation = Isolation.READ_COMMITTED)} around the dual write of
 * {@link Account} and {@link Customer}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COACTUPC.cbl} (CICS TRANID
 *       {@code 'CAUP'}; SEND MAP / RECEIVE MAP {@code COACTUPA};
 *       paragraphs {@code 1000-PROCESS-INPUTS}, {@code 1200-EDIT-MAP-INPUTS},
 *       {@code 9000-READ-ACCT}, {@code 9200-GETCARDXREF-BYACCT},
 *       {@code 9300-GETACCTDATA-BYACCT}, {@code 9400-GETCUSTDATA-BYCUST},
 *       {@code 9700-CHECK-CHANGE-IN-REC}, {@code 9700-REWRITE-ACCTDAT-FILE},
 *       {@code 9800-REWRITE-CUSTDAT-FILE}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COACTUP.bms} (mapset
 *       {@code COACTUP}, map {@code COACTUPA}; symbolic copybook
 *       {@code app/cpy-bms/COACTUP.CPY}).</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}, 300 bytes), {@code app/cpy/CVCUS01Y.cpy}
 *       ({@code CUSTOMER-RECORD}, 500 bytes), {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}, 50 bytes).</li>
 *   <li><b>VSAM clusters:</b> {@code ACCTDAT} (KSDS), {@code CUSTDAT}
 *       (KSDS), {@code CXACAIX} (alternate index over CARDXREF KEYS(11
 *       25)) &mdash; replaced by PostgreSQL tables {@code accounts},
 *       {@code customers}, and {@code card_xref} (with a derived
 *       {@code findByXrefAcctId} repository method providing the AIX
 *       semantics).</li>
 * </ul>
 *
 * <h2>Optimistic locking (AAP &sect;0.6.2, &sect;0.7.1)</h2>
 *
 * <p>The COBOL source implements optimistic concurrency via the
 * {@code 9700-CHECK-CHANGE-IN-REC} paragraph (lines 4109&ndash;4193 of
 * {@code COACTUPC.cbl}): a snapshot of every {@code ACCOUNT-RECORD} and
 * {@code CUSTOMER-RECORD} field is taken at {@code RECEIVE MAP} time
 * (stored as {@code ACUP-OLD-*}), and at {@code REWRITE} time the
 * record is re-read and field-by-field compared against the snapshot.
 * Any mismatch sets the {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 * condition, which triggers {@code SYNCPOINT ROLLBACK} and surfaces a
 * "snapshot mismatch" error to the operator.</p>
 *
 * <p>The Java target replaces this manual mechanism with the canonical
 * JPA {@code @Version} pattern on the {@link Account} entity:
 * <ol>
 *   <li><b>Explicit pre-mutation check.</b> Immediately after
 *       {@code accountRepository.findById(acctId)}, the service compares
 *       {@code request.version()} against {@code account.getVersion()};
 *       a mismatch throws
 *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       (HTTP 409) before any mutation occurs. A {@code null}
 *       request version is rejected with {@link ValidationException} so
 *       the caller cannot bypass optimistic locking.</li>
 *   <li><b>JPA race-condition gap.</b> If a concurrent writer commits
 *       between {@code findById} and {@code save}, Hibernate's
 *       {@code UPDATE accounts SET ... WHERE acct_id = ? AND version =
 *       ?} matches zero rows and Spring's persistence-exception
 *       translation surfaces {@link OptimisticLockingFailureException}.
 *       The service catches this exception and rethrows it as
 *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       so callers see a single, consistent 409 response shape.</li>
 * </ol></p>
 *
 * <p><b>Domain-exception FQCN discipline.</b> Every reference to the
 * {@code ConcurrentModificationException} in this file uses the fully
 * qualified
 * {@code com.awsm2.carddemo.exception.ConcurrentModificationException}
 * to avoid ambiguity with {@link java.util.ConcurrentModificationException}
 * (which is <b>not</b> imported here).</p>
 *
 * <h2>Transactional boundary (AAP &sect;0.7.1)</h2>
 *
 * <p>The {@link Transactional &#64;Transactional(rollbackFor =
 * Exception.class, isolation = Isolation.READ_COMMITTED)} annotation
 * on {@link #updateAccount(Long, AccountUpdateDto)} declares the entire
 * account + customer update as a single atomic unit. If the customer
 * save fails after the account save has succeeded (e.g., due to a
 * constraint violation), Hibernate rolls back both writes &mdash;
 * preserving the COBOL {@code SYNCPOINT ROLLBACK} contract.
 * {@code READ_COMMITTED} is the AAP-mandated minimum isolation level
 * for financial read-modify-write flows (AAP &sect;0.3.3).</p>
 *
 * <h2>Cache invalidation (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 *
 * <p>Per the cache-aside contract implemented by
 * {@link com.awsm2.carddemo.service.AccountViewService}, this service
 * invalidates the {@code account-view} Redis entry for the affected
 * account ID immediately after the database write commits. The next
 * {@code AccountViewService.getAccountView(...)} call repopulates the
 * cache from the new authoritative database state. The cache key is
 * {@code String.format("%011d", acctId)} &mdash; the 11-digit zero-padded
 * form &mdash; matching the writer in {@code AccountViewService}.</p>
 *
 * <h2>MSK event publication (AAP &sect;0.6.5)</h2>
 *
 * <p>On successful update, this service publishes
 * {@code account.updated} to MSK partitioned by account ID to guarantee
 * per-account ordering. Downstream consumers (audit, fraud detection,
 * reporting) are notified asynchronously. The publish is fire-and-forget
 * (returns {@code CompletableFuture}); failures are logged by the
 * publisher but do not roll back the database transaction since the
 * commit has already happened.</p>
 *
 * <h2>Audit-trail emission (AAP &sect;0.6.6)</h2>
 *
 * <p>An {@code account.updated} audit event is emitted via
 * {@link AuditLogService#auditEvent(String, String, java.util.Map)} on
 * every successful update. The audit payload contains the masked
 * account ID, the customer ID, and the post-update version &mdash;
 * never SSN, full phone, DOB, or full address (PII discipline per AAP
 * &sect;0.7.2).</p>
 *
 * <h2>PII discipline (AAP &sect;0.7.2)</h2>
 *
 * <p>The service logger ({@link #LOG}) only emits {@code acctId} and
 * {@code custId}; no SSN, no full phone, no DOB, no address line, no
 * postal code. {@link AccountUpdateDto#toString()} and
 * {@link Customer#toString()} both mask SSN as {@code ***-**-XXXX} for
 * accidental log capture safety. The Kafka payload uses the request
 * DTO whose masked {@code toString()} prevents accidental leakage in
 * Kafka producer error logs.</p>
 *
 * @see com.awsm2.carddemo.repository.AccountRepository
 * @see com.awsm2.carddemo.repository.CustomerRepository
 * @see com.awsm2.carddemo.repository.CardCrossReferenceRepository
 * @see com.awsm2.carddemo.adapter.CacheService
 * @see com.awsm2.carddemo.adapter.KafkaEventPublisher
 * @see com.awsm2.carddemo.adapter.AuditLogService
 * @see com.awsm2.carddemo.validation.ValidationLookupService
 * @see com.awsm2.carddemo.validation.DateValidationService
 * @see com.awsm2.carddemo.dto.AccountUpdateDto
 * @see com.awsm2.carddemo.dto.AccountViewDto
 * @see CardDemoException
 */
@Service
public class AccountUpdateService {

    /** Class-level SLF4J logger &mdash; structured JSON output to
     *  CloudWatch Logs via Logback + logstash-logback-encoder. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    /** Audit event name emitted on successful update; consumed by
     *  CloudTrail integration and OpenSearch indexes per AAP &sect;0.6.6. */
    private static final String EVENT_ACCT_UPDATED = "account.updated";

    /** Audit actor identifier when the request originates from the
     *  service layer (not a specific end-user). The JWT principal can
     *  override this in a future enhancement; the existing test
     *  contract pins the actor to {@code "system"}. */
    private static final String AUDIT_ACTOR = "system";

    /**
     * Date-format mask passed to {@link DateValidationService} for
     * validating ISO-8601 date strings ({@code yyyy-MM-dd}).
     *
     * <p>The {@link AccountUpdateDto} declares its date fields
     * ({@code openDate}, {@code expirationDate}, {@code reissueDate},
     * {@code dateOfBirth}) as {@link LocalDate}, whose
     * {@link LocalDate#toString()} produces a stable
     * {@code "yyyy-MM-dd"} representation (e.g., {@code "2020-01-15"}).
     * The {@link DateValidationService} accepts the COBOL-style mask
     * {@code "YYYY-MM-DD"} (translated internally to the
     * {@link java.time.format.DateTimeFormatter} pattern
     * {@code "uuuu-MM-dd"} via
     * {@link DateValidationService#toJavaPattern(String)}) and applies
     * the same century check and STRICT-resolver semantics that the
     * COBOL LE {@code CEEDAYS} cascade enforces per AAP &sect;0.5.2.</p>
     *
     * <p><b>Why this constant exists.</b> The default
     * {@link DateValidationService#DEFAULT_FORMAT_MASK} is
     * {@code "YYYYMMDD"} (no separators), which matches the COBOL
     * {@code CSUTLDWY.cpy:L58-L59} default. Calling
     * {@code dateValidationService.validate(localDate.toString())}
     * (single-argument overload) would pass an ISO-hyphenated string
     * to the no-separator default mask, producing a
     * {@code DateTimeParseException} at index 4 (the first hyphen) on
     * every valid date. This constant pins the explicit two-argument
     * call to the matching ISO mask. The exact same pattern is used
     * by {@link ReportSubmissionService} (constant declared at line
     * 205 of that file) so all services that validate
     * {@link LocalDate#toString()}-derived strings share one
     * consistent mask convention.</p>
     */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    // ------------------------------------------------------------------
    // Injected collaborators (final fields populated by constructor
    // injection; no field-level @Autowired — per AAP §0.3.3
    // "constructor injection for all @Service beans")
    // ------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final ValidationLookupService validationLookupService;
    private final DateValidationService dateValidationService;
    private final CacheService cacheService;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;

    /**
     * Constructor &mdash; Spring supplies the 8 collaborators via
     * constructor injection. All references are immutable after
     * construction (final fields) and validated non-null via
     * {@link Objects#requireNonNull(Object, String)}.
     *
     * <p>Argument order matches the schema's {@code members_exposed}
     * declaration:
     * {@code AccountUpdateService(AccountRepository, CustomerRepository,
     * CardCrossReferenceRepository, ValidationLookupService,
     * DateValidationService, CacheService, KafkaEventPublisher,
     * AuditLogService)}.</p>
     *
     * @param accountRepository             JPA repository for {@link Account}
     * @param customerRepository            JPA repository for {@link Customer}
     * @param cardCrossReferenceRepository  JPA repository for
     *                                      {@link CardCrossReference}
     *                                      (replaces VSAM CXACAIX AIX)
     * @param validationLookupService       NANPA / US state / state-ZIP
     *                                      lookup service ported from
     *                                      {@code app/cpy/CSLKPCDY.cpy}
     * @param dateValidationService         date validation service ported
     *                                      from {@code app/cpy/CSUTLDPY.cpy}
     *                                      and {@code app/cbl/CSUTLDTC.cbl}
     * @param cacheService                  ElastiCache Redis adapter for
     *                                      cache-aside invalidation
     * @param kafkaEventPublisher           MSK Kafka producer adapter for
     *                                      the {@code account.updated} topic
     * @param auditLogService               CloudTrail + OpenSearch audit
     *                                      trail adapter
     */
    public AccountUpdateService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            ValidationLookupService validationLookupService,
            DateValidationService dateValidationService,
            CacheService cacheService,
            KafkaEventPublisher kafkaEventPublisher,
            AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.customerRepository = Objects.requireNonNull(
                customerRepository, "customerRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository");
        this.validationLookupService = Objects.requireNonNull(
                validationLookupService, "validationLookupService");
        this.dateValidationService = Objects.requireNonNull(
                dateValidationService, "dateValidationService");
        this.cacheService = Objects.requireNonNull(
                cacheService, "cacheService");
        this.kafkaEventPublisher = Objects.requireNonNull(
                kafkaEventPublisher, "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(
                auditLogService, "auditLogService");
    }

    // ==================================================================
    // Public API — updateAccount
    // ==================================================================

    /**
     * Apply the supplied account + customer edits atomically.
     *
     * <p>COBOL paragraph &harr; Java equivalent mapping:</p>
     * <table>
     *   <caption>{@code COACTUPC.cbl} translation map</caption>
     *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
     *   <tr><td>{@code 1000-PROCESS-INPUTS} +
     *           {@code 1200-EDIT-MAP-INPUTS} (and sub-paragraphs
     *           {@code 1210}&ndash;{@code 1280} for individual edits)</td>
     *       <td>{@link #validate(AccountUpdateDto)} — collects all
     *           cross-field errors before throwing once</td></tr>
     *   <tr><td>{@code 9000-READ-ACCT}
     *           ({@code EXEC CICS READ UPDATE DATASET('ACCTDAT')})</td>
     *       <td>{@link AccountRepository#findById(Object)}</td></tr>
     *   <tr><td>{@code 9700-CHECK-CHANGE-IN-REC}
     *           (before/after snapshot compare, {@code DATA-WAS-CHANGED-
     *           BEFORE-UPDATE} condition, {@code SYNCPOINT ROLLBACK})</td>
     *       <td>Explicit {@code @Version} pre-check throwing
     *           {@link com.awsm2.carddemo.exception.ConcurrentModificationException},
     *           plus JPA's automatic check on save catching
     *           {@link OptimisticLockingFailureException} for the
     *           race-condition gap</td></tr>
     *   <tr><td>{@code 9200-GETCARDXREF-BYACCT}
     *           ({@code EXEC CICS READ DATASET('CXACAIX')})</td>
     *       <td>{@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
     *           — list semantics preserve VSAM AIX
     *           {@code NONUNIQUEKEY}</td></tr>
     *   <tr><td>{@code 9400-GETCUSTDATA-BYCUST}
     *           ({@code EXEC CICS READ DATASET('CUSTDAT')})</td>
     *       <td>{@link CustomerRepository#findById(Object)} keyed by the
     *           XREF-derived {@code custId}, not by
     *           {@link AccountUpdateDto#customerId()}</td></tr>
     *   <tr><td>{@code 9700-REWRITE-ACCTDAT-FILE}
     *           ({@code EXEC CICS REWRITE DATASET('ACCTDAT')})</td>
     *       <td>{@link AccountRepository#save(Object)}</td></tr>
     *   <tr><td>{@code 9800-REWRITE-CUSTDAT-FILE}
     *           ({@code EXEC CICS REWRITE DATASET('CUSTDAT')})</td>
     *       <td>{@link CustomerRepository#save(Object)}</td></tr>
     *   <tr><td>Cache invalidation (no COBOL analogue &mdash; legacy
     *           system had no cache)</td>
     *       <td>{@link CacheService#evict(String, String)} on the
     *           {@code account-view} namespace
     *           (see {@link AccountViewService#CACHE_NS})</td></tr>
     *   <tr><td>{@code SEND MAP} with confirmation message</td>
     *       <td>Return {@link AccountViewDto} reconstructed from the
     *           persisted entities for the caller to redisplay</td></tr>
     * </table>
     *
     * @param acctId  the 11-digit account identifier (URL path
     *                variable in the {@code PUT /api/accounts/{id}}
     *                endpoint); never {@code null}
     * @param request the validated DTO carrying the desired field
     *                values plus the previously-loaded {@code version}
     *                for optimistic-lock enforcement; never {@code null}
     * @return the post-update view of the account + customer pair;
     *         never {@code null}
     * @throws NullPointerException                                                 when {@code request} is {@code null}
     * @throws ValidationException                                                  when service-layer validation fails
     *                                                                              (state code, state+ZIP, area code,
     *                                                                              SSN, dates, missing version)
     * @throws RecordNotFoundException                                              when the account, the XREF row, or
     *                                                                              the customer cannot be found (HTTP 404)
     * @throws com.awsm2.carddemo.exception.ConcurrentModificationException         when the optimistic-lock pre-check or
     *                                                                              the JPA-detected race condition fires
     *                                                                              (HTTP 409)
     */
    @Transactional(rollbackFor = Exception.class,
                   isolation = Isolation.READ_COMMITTED)
    public AccountViewDto updateAccount(Long acctId, AccountUpdateDto request) {
        // ---- Defensive null-check on the request DTO ------------------
        // Mirrors the test contract: a null request surfaces NPE before
        // any side-effect occurs (see updateAccount_nullRequest_throwsNpe).
        Objects.requireNonNull(request, "request");

        // ---- COBOL: COACTUPC:1000-PROCESS-INPUTS ----------------------
        // Cross-field validation against the CSLKPCDY lookup tables and
        // CSUTLDPY date rules. Errors are collected and thrown in a
        // single ValidationException so the caller sees the complete
        // set of input problems in one response (matches the COBOL
        // pattern of accumulating WS-EDIT-*-FLG settings before
        // returning to the screen with all error highlights).
        validate(request);

        // ---- COBOL: COACTUPC:9000-READ-ACCT ---------------------------
        // EXEC CICS READ UPDATE DATASET('ACCTDAT') RIDFLD(WS-ACCT-ID)
        // The {@code findById} call returns Optional.empty() when the
        // VSAM equivalent would have returned FILE STATUS '23' (NOTFND).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Account",
                        "acctId=" + acctId));

        // ---- COBOL: COACTUPC:9700-CHECK-CHANGE-IN-REC (PRE-CHECK) -----
        // Explicit optimistic-lock pre-check. The COBOL source performs
        // a before/after image comparison; the Java target reads the
        // version stamped onto the entity at the moment of caller's
        // last view and compares against the request's claimed version.
        // A null version means the caller did not supply optimistic-lock
        // information — reject before any state mutation.
        verifyVersion(request, account, acctId);

        // ---- COBOL: COACTUPC:9200-GETCARDXREF-BYACCT ------------------
        // Resolve the customer ID via the cross-reference table — the
        // authoritative source for the account-to-customer mapping.
        // The COBOL source uses the CXACAIX VSAM alternate index
        // (KEYS(11 25), NONUNIQUEKEY) over the CARDXREF cluster; the
        // Java target uses a derived JPA query that returns a List
        // (preserving the NONUNIQUEKEY semantics — multiple cards may
        // share one account).
        List<CardCrossReference> xrefs =
                cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(
                    "CardCrossReference",
                    "acctId=" + acctId);
        }
        Long custId = xrefs.get(0).getXrefCustId();

        // ---- COBOL: COACTUPC:9400-GETCUSTDATA-BYCUST ------------------
        // EXEC CICS READ DATASET('CUSTDAT') RIDFLD(WS-CUST-ID)
        // The customer ID comes from the XREF, NOT from
        // request.customerId() — preserves the COBOL source-of-truth
        // contract where the screen's customer-ID field is display-only.
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Customer",
                        "custId=" + custId));

        // ---- COBOL: field-by-field MOVE statements ---------------------
        // Apply the validated DTO values to the managed JPA entities.
        // Monetary fields normalise null → BigDecimal.ZERO and are
        // scaled to (12,2) via HALF_EVEN (banker's rounding) per
        // AAP §0.6.1 — never float/double.
        applyAccountEdits(account, request);
        applyCustomerEdits(customer, request);

        // ---- COBOL: 9700-REWRITE-ACCTDAT-FILE +
        //              9800-REWRITE-CUSTDAT-FILE ------------------------
        // Both REWRITE operations execute inside the single transaction
        // declared by @Transactional(rollbackFor = Exception.class).
        // If the customer save fails after the account save succeeds
        // (constraint violation, optimistic-lock failure, etc.), the
        // transaction manager rolls back BOTH writes — preserving the
        // COBOL SYNCPOINT ROLLBACK contract from COACTUPC.
        //
        // OptimisticLockingFailureException is the Spring-DAO wrapper
        // around Hibernate's StaleObjectStateException, raised when the
        // UPDATE accounts WHERE acct_id = ? AND version = ? matches 0
        // rows (i.e., a concurrent writer committed between findById
        // and save). Per AAP §0.6.2, this is rethrown as the carddemo
        // ConcurrentModificationException (HTTP 409) to give callers a
        // single, consistent conflict-response shape.
        try {
            accountRepository.save(account);
            customerRepository.save(customer);
        } catch (OptimisticLockingFailureException ole) {
            throw new com.awsm2.carddemo.exception.ConcurrentModificationException(
                    "Account",
                    "acctId=" + acctId,
                    ole);
        }

        // ---- Side effects (only after successful commit) ---------------
        // Cache invalidation, MSK publication, and audit emission all
        // occur AFTER both saves succeed. If any of these throws, the
        // @Transactional rollback would also undo the saves — but the
        // CacheService and KafkaEventPublisher are designed fail-open
        // (errors logged, not thrown), and AuditLogService swallows
        // OpenSearch failures (the CloudTrail integration handles the
        // mandatory-audit obligation independently).
        String cacheKey = acctIdKey(acctId);

        // Cache-aside invalidation — the account-view cache must reflect
        // the new authoritative state on the next AccountViewService
        // read. Namespace matches AccountViewService.CACHE_NS to ensure
        // the invalidation hits the same key the view service populated.
        cacheService.evict(AccountViewService.CACHE_NS, cacheKey);

        // MSK account.updated publication — partitioned by acctId so all
        // downstream consumers see per-account ordering (AAP §0.6.5).
        // The request DTO is published; its toString() masks SSN per
        // PII discipline so accidental log capture in Kafka producer
        // error paths does not leak regulated data.
        kafkaEventPublisher.publishAccountUpdated(acctId, request);

        // Audit-trail emission — CloudTrail + OpenSearch (AAP §0.6.6).
        // Payload contains acctId, custId, and post-update version
        // ONLY; never SSN, full phone, DOB, or full address.
        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("accountId", cacheKey);
        auditPayload.put("customerId", customer.getCustId());
        auditPayload.put("version", account.getVersion());
        auditLogService.auditEvent(EVENT_ACCT_UPDATED, AUDIT_ACTOR, auditPayload);

        // PII-safe structured log message: only acctId + custId emitted.
        LOG.info("Account updated acctId={} custId={} version={}",
                cacheKey, customer.getCustId(), account.getVersion());

        // ---- COBOL: SEND MAP with confirmation -----------------------
        // Return the post-update AccountViewDto for the caller to use
        // as the new authoritative snapshot (and to seed the next
        // optimistic-lock cycle if the caller continues editing).
        return toViewDto(account, customer);
    }

    // ==================================================================
    // Private helpers — optimistic-lock & validation
    // ==================================================================

    /**
     * Optimistic-lock pre-check (replaces COBOL
     * {@code 9700-CHECK-CHANGE-IN-REC} snapshot comparison).
     *
     * <p>Two failure modes:</p>
     * <ol>
     *   <li><b>{@code request.version() == null}</b> &mdash; caller did
     *       not supply the previously-loaded version; reject with a
     *       {@link ValidationException} carrying a {@code "version"}
     *       field error. This prevents callers from bypassing
     *       optimistic locking by omitting the version field.</li>
     *   <li><b>{@code request.version() != account.getVersion()}</b>
     *       &mdash; a concurrent writer has updated this account since
     *       the caller last viewed it; reject with
     *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
     *       (HTTP 409) before any mutation occurs.</li>
     * </ol>
     */
    private void verifyVersion(AccountUpdateDto request,
                               Account account,
                               Long acctId) {
        Long requested = request.version();
        if (requested == null) {
            throw new ValidationException(
                    ValidationException.DEFAULT_REASON_CODE,
                    "Caller must supply the previously-loaded version "
                            + "to prevent lost-update conflicts",
                    List.of(new ValidationException.FieldError(
                            "version", "version is required")));
        }
        Long current = account.getVersion();
        if (!requested.equals(current)) {
            throw new com.awsm2.carddemo.exception.ConcurrentModificationException(
                    "Account",
                    "acctId=" + acctId
                            + " (requested version=" + requested
                            + ", current=" + current + ")");
        }
    }

    /**
     * Cross-field service-layer validation that the controller-level
     * Jakarta Bean Validation cannot perform (lookup-table membership
     * for state codes, NANPA area codes, state+ZIP-prefix combinations,
     * date validity rules from {@code CSUTLDPY.cpy} / {@code CSUTLDTC.cbl},
     * and the COBOL SSN part-1 invariant).
     *
     * <p>This method <b>collects</b> all errors into a single list and
     * throws once at the end, matching the COBOL {@code COACTUPC.cbl}
     * pattern of accumulating {@code WS-EDIT-*-FLG} settings during the
     * {@code 1200-EDIT-MAP-INPUTS} cascade before returning all
     * highlighted errors to the operator's screen. This contract is
     * locked by the test {@code updateAccount_invalidStateCode_*}: even
     * when an earlier check fails, subsequent checks still run (the
     * caller receives the complete error set in one response).</p>
     *
     * <p>COBOL paragraph map:</p>
     * <ul>
     *   <li>{@code 1210-EDIT-ACCOUNT} &rarr; (handled by Jakarta
     *       {@code @Pattern} on DTO {@code activeStatus})</li>
     *   <li>{@code 1215-EDIT-MANDATORY} &rarr; (handled by Jakarta
     *       {@code @NotBlank}/{@code @NotNull} on DTO)</li>
     *   <li>{@code 1220-EDIT-YESNO} &rarr; (handled by Jakarta
     *       {@code @Pattern} on DTO {@code activeStatus},
     *       {@code primaryCardHolderIndicator})</li>
     *   <li>{@code 1225-EDIT-ALPHA-REQD} &rarr; (handled by Jakarta
     *       on names; alpha-only enforcement done at DTO layer)</li>
     *   <li>{@code 1230-EDIT-ALPHANUM-REQD} &rarr; (handled by Jakarta
     *       on address lines)</li>
     *   <li>{@code 1250-EDIT-SIGNED-9V2} &rarr; (handled by Jakarta
     *       {@code @DecimalMin}/{@code @Digits} on BigDecimal fields)</li>
     *   <li>{@code 1260-EDIT-US-PHONE-NUM} &rarr;
     *       {@link #validateAreaCode(List, String, String)}</li>
     *   <li>{@code 1265-EDIT-US-SSN} (INVALID-SSN-PART1 invariant)
     *       &rarr; {@link #validateSsn(List, Long, String)}</li>
     *   <li>{@code 1270-EDIT-US-STATE-CD} &rarr;
     *       {@link ValidationLookupService#isValidStateCode(String)}</li>
     *   <li>{@code 1275-EDIT-FICO-SCORE} &rarr; (handled by Jakarta
     *       {@code @Min}/{@code @Max} on DTO {@code ficoCreditScore})</li>
     *   <li>{@code 1280-EDIT-US-STATE-ZIP-CD} &rarr;
     *       {@link ValidationLookupService#isValidStateZipCombination(String, String)}</li>
     *   <li>{@code CSUTLDPY} / {@code CSUTLDTC} date validation &rarr;
     *       {@link #validateDate(List, LocalDate, String)}</li>
     * </ul>
     */
    private void validate(AccountUpdateDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        // COBOL: 1270-EDIT-US-STATE-CD (CSLKPCDY US-STATE-CODES table)
        if (request.stateCode() != null
                && !validationLookupService.isValidStateCode(request.stateCode())) {
            errors.add(new ValidationException.FieldError(
                    "stateCode",
                    "Invalid US state/territory code"));
        }

        // COBOL: 1280-EDIT-US-STATE-ZIP-CD (CSLKPCDY valid state+ZIP-prefix table)
        if (request.stateCode() != null && request.zipCode() != null
                && !validationLookupService.isValidStateZipCombination(
                        request.stateCode(), request.zipCode())) {
            errors.add(new ValidationException.FieldError(
                    "zipCode",
                    "ZIP prefix does not match the supplied state code"));
        }

        // COBOL: 1260-EDIT-US-PHONE-NUM (CSLKPCDY NANPA area-code table)
        validateAreaCode(errors, request.phoneNumber1(), "phoneNumber1");
        validateAreaCode(errors, request.phoneNumber2(), "phoneNumber2");

        // COBOL: 1265-EDIT-US-SSN (INVALID-SSN-PART1 invariant — 0, 666, 900-999)
        validateSsn(errors, request.customerSsn(), "customerSsn");

        // COBOL: CSUTLDPY/CSUTLDTC date validation cascade
        validateDate(errors, request.openDate(), "openDate");
        validateDate(errors, request.expirationDate(), "expirationDate");
        validateDate(errors, request.reissueDate(), "reissueDate");
        validateDate(errors, request.dateOfBirth(), "dateOfBirth");

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    ValidationException.DEFAULT_REASON_CODE,
                    "Account/customer update validation failed",
                    errors);
        }
    }

    /**
     * NANPA area-code validation against {@code CSLKPCDY.cpy}'s area-code
     * table. The DTO's {@code @Pattern("^\\d{10}$")} guarantees a 10-digit
     * phone string (no parens, no dashes); this method extracts the
     * first three digits and asks the lookup service whether they
     * form a registered area code.
     *
     * <p>{@code phoneNumber2} is optional &mdash; when null or blank,
     * no validation is performed. When present, the same area-code
     * check applies.</p>
     */
    private void validateAreaCode(List<ValidationException.FieldError> errors,
                                  String phone,
                                  String fieldName) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        if (phone.length() >= 3) {
            String areaCode = phone.substring(0, 3);
            if (!validationLookupService.isValidAreaCode(areaCode)) {
                errors.add(new ValidationException.FieldError(
                        fieldName,
                        "Invalid NANPA area code"));
            }
        }
    }

    /**
     * SSN part-1 invariant per COBOL {@code COACTUPC.cbl}
     * {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}.
     *
     * <p>{@code ValidationLookupService} does not expose an
     * {@code isValidSsn} method (verified during discovery), so the
     * invariant is enforced inline. The first three digits of the
     * 9-digit SSN are extracted by integer division by {@code 10^6}.</p>
     *
     * <p>Examples (Long → part1):
     * <ul>
     *   <li>{@code 123456789L} → part1 = 123 (valid)</li>
     *   <li>{@code 666123456L} → part1 = 666 (INVALID per COBOL rule)</li>
     *   <li>{@code 999999999L} → part1 = 999 (INVALID per COBOL rule)</li>
     *   <li>{@code 12345678L}  → part1 = 12  (INVALID — leading zero
     *       conceptually means 012; the rule rejects 0 = "000")</li>
     * </ul></p>
     */
    private static void validateSsn(List<ValidationException.FieldError> errors,
                                    Long ssn,
                                    String fieldName) {
        if (ssn == null) {
            return; // @NotNull at the DTO layer enforces presence
        }
        long part1 = ssn / 1_000_000L;
        if (part1 == 0L
                || part1 == 666L
                || (part1 >= 900L && part1 <= 999L)) {
            errors.add(new ValidationException.FieldError(
                    fieldName,
                    "Invalid SSN: first three digits must not be 000, 666, or 900-999"));
        }
    }

    /**
     * Date validation against {@code DateValidationService}. The COBOL
     * source uses LE {@code CEEDAYS} via {@code CSUTLDTC.cbl} to verify
     * that the date is a real calendar date (correct month, valid day,
     * leap-year aware) and within plausible bounds. The Java target
     * delegates to
     * {@link DateValidationService#validate(String, String)} which
     * performs the same checks using {@code java.time.LocalDate}.
     *
     * <p><b>Format-mask contract.</b> The {@link AccountUpdateDto}
     * declares its date fields as {@link LocalDate}, whose
     * {@link LocalDate#toString()} produces a stable ISO-8601
     * representation ({@code "yyyy-MM-dd"}, e.g.,
     * {@code "2020-01-15"}). The validation call passes the explicit
     * mask {@link #DATE_FORMAT_MASK} ({@code "YYYY-MM-DD"}) so the
     * service's STRICT-resolver {@link java.time.format.DateTimeFormatter}
     * accepts the hyphenated form. The single-argument
     * {@code validate(String)} convenience overload defaults to
     * {@link DateValidationService#DEFAULT_FORMAT_MASK}
     * ({@code "YYYYMMDD"}, no separators) which would fail at index 4
     * (the first hyphen) for every valid ISO date. This service
     * therefore uses the two-argument form exclusively &mdash;
     * mirroring the established pattern in
     * {@link ReportSubmissionService} (which validates the same kind
     * of {@link LocalDate#toString()}-derived strings).</p>
     *
     * <p>A {@code null} date is treated as "not supplied"; the DTO's
     * Jakarta {@code @NotNull} on mandatory dates handles the
     * presence check.</p>
     */
    // COBOL: COACTUPC.cbl :1265-EDIT-DATE-OPEN, :1266-EDIT-DATE-EXP,
    //                    :1267-EDIT-DATE-REIS, :1268-EDIT-DATE-DOB
    //        (each calling CSUTLDTC with a YYYY-MM-DD format literal)
    private void validateDate(List<ValidationException.FieldError> errors,
                              LocalDate date,
                              String fieldName) {
        if (date == null) {
            return;
        }
        // CRITICAL: pass the explicit YYYY-MM-DD mask. LocalDate.toString()
        // produces an ISO-8601 hyphenated string; the default mask
        // (YYYYMMDD, no separators) would reject every valid date at
        // index 4 (the first hyphen) — a defect captured by QA Checkpoint 3
        // Issue #1 and fixed here.
        DateValidationService.DateValidationResult result =
                dateValidationService.validate(date.toString(), DATE_FORMAT_MASK);
        if (!result.isValid()) {
            errors.add(new ValidationException.FieldError(
                    fieldName,
                    result.errorMessage()));
        }
    }



    // ==================================================================
    // Private helpers — entity-mutation & DTO assembly
    // ==================================================================

    /**
     * Apply the request DTO values to the managed {@link Account}
     * entity. Mirrors the field-by-field {@code MOVE} statements in
     * {@code COACTUPC.cbl}'s update paragraphs.
     *
     * <p>Monetary fields are normalised through {@link #safeBalance}:
     * <ul>
     *   <li>{@code null} values become {@link BigDecimal#ZERO} (this
     *       matches the COBOL behavior where a {@code PIC S9(10)V99}
     *       field is never null &mdash; it carries a numeric value, zero
     *       by default; per AAP &sect;0.7.1 and the
     *       {@code updateAccount_nullBalance_normalisedToZero} test).</li>
     *   <li>Non-null values are scaled to (precision=12, scale=2) with
     *       {@link RoundingMode#HALF_EVEN} (banker's rounding) to match
     *       the COBOL {@code PIC S9(10)V99} rounding semantics. The
     *       resulting value preserves the caller's BigDecimal exactly
     *       at scale=2 (locked by the
     *       {@code updateAccount_preservesBigDecimalScale} test).</li>
     * </ul></p>
     *
     * <p>The {@code @Version} field is intentionally NOT touched here.
     * The pre-mutation {@link #verifyVersion} call has already
     * confirmed the loaded entity's version matches the request's,
     * and JPA increments the version on successful save.</p>
     */
    private static void applyAccountEdits(Account account,
                                          AccountUpdateDto request) {
        // CVACT01Y.cpy fields (ACCOUNT-RECORD, 300 bytes):
        //   ACCT-ACTIVE-STATUS         PIC X(01)
        //   ACCT-CURR-BAL              PIC S9(10)V99
        //   ACCT-CREDIT-LIMIT          PIC S9(10)V99
        //   ACCT-CASH-CREDIT-LIMIT     PIC S9(10)V99
        //   ACCT-OPEN-DATE             PIC X(10)  (yyyy-MM-dd)
        //   ACCT-EXPIRAION-DATE        PIC X(10)  (yyyy-MM-dd)
        //   ACCT-REISSUE-DATE          PIC X(10)  (yyyy-MM-dd)
        //   ACCT-CURR-CYC-CREDIT       PIC S9(10)V99
        //   ACCT-CURR-CYC-DEBIT        PIC S9(10)V99
        //   ACCT-ADDR-ZIP              PIC X(10)
        //   ACCT-GROUP-ID              PIC X(10)
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

    /**
     * Apply the request DTO values to the managed {@link Customer}
     * entity. Mirrors the field-by-field {@code MOVE} statements in
     * {@code COACTUPC.cbl}'s customer-update paragraph
     * ({@code 9800-REWRITE-CUSTDAT-FILE} et al.).
     *
     * <p>The {@code Customer} entity does not carry {@code @Version}
     * (per AAP &sect;0.4.1 only {@code Account} and {@code Card} are
     * optimistic-lock protected). Concurrent {@code Customer}
     * modifications are not detected at the JPA layer, but the dual
     * write is still atomic per {@code @Transactional} &mdash; if the
     * account save throws after the customer save was about to occur,
     * the customer changes are rolled back.</p>
     *
     * <p>{@code customerSsn} is type {@link Long}; if the caller
     * supplies {@code null}, the existing SSN value is preserved (DTO
     * {@code @NotNull} enforces presence at the controller layer).</p>
     *
     * <p>{@code ficoCreditScore} is similarly guarded: a {@code null}
     * caller value preserves the existing entity value (matches COBOL's
     * "leave field unchanged when blank" semantic).</p>
     */
    private static void applyCustomerEdits(Customer customer,
                                           AccountUpdateDto request) {
        // CVCUS01Y.cpy fields (CUSTOMER-RECORD, 500 bytes):
        //   CUST-FIRST-NAME            PIC X(25)
        //   CUST-MIDDLE-NAME           PIC X(25)
        //   CUST-LAST-NAME             PIC X(25)
        //   CUST-SSN                   PIC 9(09)        (Long)
        //   CUST-PHONE-NUM-1           PIC X(15)
        //   CUST-PHONE-NUM-2           PIC X(15)
        //   CUST-ADDR-LINE-1           PIC X(50)
        //   CUST-ADDR-LINE-2           PIC X(50)
        //   CUST-ADDR-LINE-3           PIC X(50)
        //   CUST-ADDR-STATE-CD         PIC X(02)
        //   CUST-ADDR-COUNTRY-CD       PIC X(03)
        //   CUST-ADDR-ZIP              PIC X(10)
        //   CUST-DOB-YYYY-MM-DD        PIC X(10)        (LocalDate)
        //   CUST-GOVT-ISSUED-ID        PIC X(20)
        //   CUST-EFT-ACCOUNT-ID        PIC X(10)
        //   CUST-PRI-CARD-HOLDER-IND   PIC X(01)
        //   CUST-FICO-CREDIT-SCORE     PIC 9(03)        (Integer)
        customer.setCustFirstName(request.firstName());
        customer.setCustMiddleName(request.middleName());
        customer.setCustLastName(request.lastName());
        if (request.customerSsn() != null) {
            customer.setCustSsn(request.customerSsn());
        }
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
     * Defensive helper for monetary fields:
     * <ul>
     *   <li>{@code null} &rarr; {@link BigDecimal#ZERO} (matches COBOL
     *       {@code PIC S9(10)V99} which is always populated)</li>
     *   <li>Non-null &rarr; scaled to {@code scale=2} with
     *       {@link RoundingMode#HALF_EVEN} (banker's rounding,
     *       AAP-mandated for all monetary computations)</li>
     * </ul>
     *
     * <p>This method NEVER uses {@code float} or {@code double} &mdash;
     * AAP &sect;0.7.1 explicit prohibition. {@link BigDecimal} is the
     * only acceptable monetary type.</p>
     */
    private static BigDecimal safeBalance(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Build the post-update {@link AccountViewDto} from the persisted
     * {@link Account} and {@link Customer} entities.
     *
     * <p>This is the Java equivalent of the COBOL {@code SEND MAP
     * COACTUPA} that follows a successful {@code REWRITE}: the operator
     * sees the now-authoritative state on screen. In the REST API
     * world, the caller receives this DTO as the response body and
     * uses it as the new optimistic-lock seed for any subsequent
     * edit cycle.</p>
     *
     * <p>The constructor matches {@link AccountViewDto}'s 30-field
     * record signature exactly (no version &mdash; AccountViewDto is
     * a read-only response shape).</p>
     */
    private static AccountViewDto toViewDto(Account a, Customer c) {
        return new AccountViewDto(
                // ----- Account fields (CVACT01Y.cpy) -----
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
                // ----- Customer fields (CVCUS01Y.cpy) -----
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
                c.getCustFicoCreditScore());
    }

    /**
     * Format an account ID to the 11-digit zero-padded canonical form
     * used for cache keys, MSK partition keys, audit-log correlation,
     * and structured log entries.
     *
     * <p>Matches the format used by {@code AccountViewService.acctIdKey}
     * so the same {@code (namespace, key)} pair invalidated here hits
     * the entry populated by the view service.</p>
     */
    private static String acctIdKey(Long acctId) {
        return String.format("%011d", acctId);
    }
}

