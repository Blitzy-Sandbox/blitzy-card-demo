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
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Account-update service — the Java migration of the 4,236-line CICS COBOL
 * program {@code app/cbl/COACTUPC.cbl} (TRANID {@code CAUP}, the
 * account-update dispatcher and the largest CICS program in the CardDemo
 * migration). Performs a dual-table coordinated update — the {@code ACCTDAT}
 * VSAM KSDS replacement ({@link Account}) and the {@code CUSTDAT} VSAM KSDS
 * replacement ({@link Customer}) — after enforcing the field-level validation
 * cascade inherited from the COBOL baseline and JPA optimistic-locking
 * semantics that replace the COBOL READ UPDATE / CHECK-CHANGE-IN-REC
 * pessimistic-lock idiom.
 *
 * <h2>COBOL Provenance — COACTUPC.cbl</h2>
 *
 * <p>The COBOL workflow combines the {@code 1000-PROCESS-INPUTS} validation
 * cascade (paragraphs for account-status edit, credit-limit edit, SSN edit,
 * phone-number edit, etc.) with the {@code 9600-WRITE-PROCESSING} dual READ
 * UPDATE / dual REWRITE flow (lines 3888–4105) protected by an
 * {@code EXEC CICS SYNCPOINT ROLLBACK} (line 4100). The Java migration
 * preserves the validation cascade verbatim (matching reject messages per
 * AAP §0.10.4 Immutable Boundaries) and replaces the COBOL transaction
 * semantics with a single Spring {@code @Transactional} method:
 *
 * <ol>
 *   <li>Validate the account active status (COBOL parity:
 *       {@code ACCT-STATUS-MUST-BE-YES-NO} 88-level at line 503).</li>
 *   <li>Validate the credit limit (COBOL parity:
 *       {@code CRED-LIMIT-IS-NOT-VALID} 88-level at line 507).</li>
 *   <li>Validate the SSN's first part (COBOL parity:
 *       {@code INVALID-SSN-PART1} 88-level at line 121 — values
 *       {@code 0}, {@code 666}, and {@code 900 THRU 999}).</li>
 *   <li>Validate the primary phone number's area code (COBOL parity:
 *       {@code INVALID-PHONE-NUMA} 88-level at line 102 — value
 *       {@code "000"}).</li>
 *   <li>{@link AccountRepository#findById(Object)} on the supplied
 *       account ID. {@link Optional#empty()} → reject with
 *       {@link #MSG_COULD_NOT_LOCK_ACCT}, mirroring COBOL
 *       {@code DFHRESP(NOTFND)} on
 *       {@code EXEC CICS READ FILE('ACCTDAT') UPDATE} at line 3897
 *       (which sets {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}).</li>
 *   <li>{@link CustomerRepository#findById(Object)} on the supplied
 *       customer ID. {@link Optional#empty()} → reject with
 *       {@link #MSG_COULD_NOT_LOCK_CUST}, mirroring COBOL
 *       {@code DFHRESP(NOTFND)} on
 *       {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} at line 3925
 *       (which sets {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}).</li>
 *   <li>Apply the request's field values to the loaded {@link Account}
 *       and {@link Customer} entities. The primary keys
 *       ({@link Account#getAccountId()} and
 *       {@link Customer#getCustomerId()}) are NEVER mutated — Java
 *       migration invariant.</li>
 *   <li>{@link AccountRepository#save(Object)} — Java equivalent of
 *       {@code EXEC CICS REWRITE FILE('ACCTDAT')} at line 4078. On JPA
 *       {@code @Version} mismatch this raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       which propagates uncaught (COBOL parity:
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag at line 521; the
 *       COBOL workflow sets it from
 *       {@code 9700-CHECK-CHANGE-IN-REC} at lines 4143/4189).</li>
 *   <li>{@link CustomerRepository#save(Object)} — Java equivalent of
 *       {@code EXEC CICS REWRITE FILE('CUSTDAT')} at line 4093. On JPA
 *       {@code @Version} mismatch this raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}.
 *       The Spring {@code @Transactional(rollbackFor = Exception.class)}
 *       boundary on this method rolls back the prior account save
 *       atomically — the Java equivalent of COBOL
 *       {@code EXEC CICS SYNCPOINT ROLLBACK} at line 4100.</li>
 *   <li>Build the success response carrying the verbatim COBOL
 *       {@code 'Changes committed to database'} literal (COBOL parity:
 *       {@code CONFIRM-UPDATE-SUCCESS} 88-level at line 474).</li>
 * </ol>
 *
 * <h2>Java Migration: Optimistic Locking via JPA @Version</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom (lines 3897, 3925, 4078, 4093)
 * serialised concurrent updates through CICS file locks: each READ UPDATE
 * acquired an exclusive lock on the record that persisted until the
 * corresponding REWRITE released it. The {@code 9700-CHECK-CHANGE-IN-REC}
 * paragraph (lines 4131–4189) additionally compared the operator-displayed
 * before-image of each record against the current persisted state on the
 * way into the REWRITE, setting {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 * (line 521) when the comparison detected an intervening update. The Java
 * migration replaces both mechanisms with JPA's {@code @Version}
 * optimistic-locking field on {@link Account} and {@link Customer}: when
 * {@code save()} detects a version mismatch (another transaction
 * incremented the persisted version between this transaction's lookup and
 * save), JPA raises
 * {@link org.springframework.dao.OptimisticLockingFailureException}. The
 * service does not catch the exception; it propagates uncaught to the
 * controller layer's exception-handler chain (mapped to HTTP 409 Conflict).
 * The observable contract (concurrent updates are detected and one of them
 * fails cleanly) is preserved per AAP §0.10.4 ("External interfaces
 * consumed by downstream systems MUST NOT change").
 *
 * <h2>Java Migration: SYNCPOINT ROLLBACK via @Transactional</h2>
 *
 * <p>Unlike {@code COCRDUPC.cbl} (single-table update), {@code COACTUPC.cbl}
 * updates both ACCTDAT and CUSTDAT and uses {@code EXEC CICS SYNCPOINT
 * ROLLBACK} (line 4100) to undo the account REWRITE if the customer
 * REWRITE fails — a textbook example of an XA-style multi-resource
 * transaction in CICS. The Java migration declares
 * {@code @Transactional(rollbackFor = Exception.class)} on
 * {@link #updateAccount(AccountUpdateRequest)} so that any uncaught
 * exception (including
 * {@link org.springframework.dao.OptimisticLockingFailureException} and
 * other {@link org.springframework.dao.DataAccessException} subclasses)
 * triggers Spring's transaction manager to roll back BOTH the account save
 * and the customer save atomically. The observable contract is preserved:
 * either both writes succeed, or neither persists.
 *
 * <p>NOTE: The {@code @Transactional} annotation reference is documented
 * here in the Javadoc but is NOT applied as a Java annotation in the
 * stub class body because subsequent migration agents (REFACTOR flavor)
 * own the Spring application context wiring. The transactional semantics
 * are emulated implicitly at the unit-test level by asserting that
 * exceptions propagate uncaught — which is the precondition Spring needs
 * to perform the rollback. The annotation will be added when the
 * {@code @Service}, {@code @Repository}, and Spring Boot bootstrap classes
 * are wired up by later migration agents.
 *
 * <h2>Java Migration: Primary-Key Immutability</h2>
 *
 * <p>The COBOL workflow at lines 4061–4076 INITIALIZES
 * {@code ACCT-UPDATE-RECORD} with the operator's modified field values
 * BUT carries over the {@code ACCT-UPDATE-ID} primary key from the loaded
 * record. The Java migration enforces this explicitly: the service NEVER
 * calls {@link Account#setAccountId(String)} or
 * {@link Customer#setCustomerId(String)} on the loaded entities, so the
 * primary keys are preserved by construction. This invariant is the
 * load-bearing AAP §0.10.4 contract — the primary key is the access path
 * for every subsequent operation (read, posting, statement generation),
 * and allowing it to mutate during an update would invalidate every
 * cross-reference.
 *
 * <h2>Validation Order</h2>
 *
 * <p>The service performs validations in the following order to match the
 * test-suite invariants documented by
 * {@code AccountUpdateServiceTest.ValidationRejects}:
 * <ol>
 *   <li><b>Account active status domain check</b> — must be {@code "Y"}
 *       or {@code "N"}. Rejects via {@link #MSG_ACCT_STATUS_INVALID}.</li>
 *   <li><b>Credit limit non-negative check</b> — must be non-{@code null}
 *       and ≥ {@link BigDecimal#ZERO}. Rejects via
 *       {@link #MSG_CREDIT_LIMIT_INVALID}.</li>
 *   <li><b>SSN format check</b> — 9 numeric digits; first 3 (PART1) must
 *       not be {@code "000"}, {@code "666"}, or in {@code "900"}–{@code "999"}.
 *       Rejects via {@link #MSG_SSN_INVALID}.</li>
 *   <li><b>Phone area code check</b> — first 3 digits of the area-code
 *       group must not be {@code "000"}. Rejects via
 *       {@link #MSG_PHONE_INVALID}.</li>
 *   <li><b>Account repository lookup</b> via
 *       {@link AccountRepository#findById}. On {@link Optional#empty()}
 *       → reject with {@link #MSG_COULD_NOT_LOCK_ACCT}.</li>
 *   <li><b>Customer repository lookup</b> via
 *       {@link CustomerRepository#findById}. On {@link Optional#empty()}
 *       → reject with {@link #MSG_COULD_NOT_LOCK_CUST}.</li>
 *   <li><b>Field updates</b> on the loaded entities (all mutable fields
 *       except the primary keys).</li>
 *   <li><b>Account save</b>, then <b>Customer save</b>. On JPA
 *       {@code @Version} mismatch this raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       which propagates uncaught (so Spring rolls back the transaction).</li>
 *   <li><b>Success response</b> — builds the
 *       {@code 'Changes committed to database'} confirmation.</li>
 * </ol>
 *
 * <p>Each validation short-circuits and returns immediately on failure;
 * the repository's {@code save()} methods are never invoked on any of the
 * validation-reject paths or the account-not-found / customer-not-found
 * paths (defence-in-depth verified by the corresponding test suite via
 * {@code verify(repository, never())} assertions).
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts the
 * repository and clock collaborators directly so unit tests can wire
 * Mockito mocks + a {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
 * deterministic clock without a Spring context — matching the convention
 * established by {@link CardUpdateService}, {@link AuthenticationService},
 * and the rest of the service package.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE account-update dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #updateAccount(AccountUpdateRequest)} entry point). The
 * corresponding {@code AccountUpdateServiceTest} exercises every branch
 * via real method calls with mocked {@link AccountRepository} and
 * {@link CustomerRepository} at the database boundary and a fixed
 * {@link Clock} for deterministic time-dependent behaviour. No business
 * logic (active-status domain check, credit-limit sign check, SSN PART1
 * check, phone area-code check, optimistic-locking propagation) is
 * duplicated inside the test.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable service implementation</strong>
 * created to satisfy {@code AccountUpdateServiceTest} compilation and the
 * AAP §0.10.1 Require Test Coverage rule. Subsequent migration agents
 * (REFACTOR flavor) will add the {@code @Service} stereotype, JPA
 * {@code @Transactional(rollbackFor = Exception.class)} demarcation,
 * structured logging hooks, the {@link CardXrefRepository}-driven account-
 * card cross-reference validation, and the REST controller layer that
 * drives this service when the full Spring application context is wired
 * up. The {@link CardXrefRepository} field is accepted by the constructor
 * today (and held as a final field) so future migration agents can add
 * the cross-reference validation without a constructor-signature change.
 *
 * @see AccountUpdateRequest
 * @see AccountUpdateResult
 * @see Account
 * @see Customer
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardXrefRepository
 */
public class AccountUpdateService {

    // ---------------------------------------------------------------------
    // Reject messages — verbatim COBOL literals preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see the same textual reason as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when {@link AccountUpdateRequest#getAccountActiveStatus()}
     * is neither {@code "Y"} nor {@code "N"} — verbatim COBOL literal from
     * {@code COACTUPC.cbl} {@code ACCT-STATUS-MUST-BE-YES-NO} 88-level at
     * line 503: {@code 'Account Active Status must be Y or N'}.
     */
    static final String MSG_ACCT_STATUS_INVALID = "Account Active Status must be Y or N";

    /**
     * Reject message returned when {@link AccountUpdateRequest#getCreditLimit()}
     * is {@code null} or negative — verbatim COBOL literal from
     * {@code COACTUPC.cbl} {@code CRED-LIMIT-IS-NOT-VALID} 88-level at
     * line 507: {@code 'Credit Limit is not valid'}.
     */
    static final String MSG_CREDIT_LIMIT_INVALID = "Credit Limit is not valid";

    /**
     * Reject message returned when {@link AccountUpdateRequest#getSsn()}
     * does not match the 9-digit numeric format or its first three digits
     * (SSA-administered SSN PART1) are {@code "000"}, {@code "666"}, or
     * in the range {@code "900"}–{@code "999"} (the unassigned ranges).
     * Mirrors the COBOL {@code INVALID-SSN-PART1} 88-level at line 121
     * ({@code VALUES 0, 666, 900 THRU 999}) — Java-migration message
     * preserved as plain English because the COBOL workflow does not
     * surface a single dedicated reject literal for SSN-PART1.
     */
    static final String MSG_SSN_INVALID = "SSN is not valid";

    /**
     * Reject message returned when {@link AccountUpdateRequest#getPhoneNumber1()}
     * has an invalid area code (first three digits inside the parentheses
     * equal {@code "000"}). Mirrors the COBOL
     * {@code WS-EDIT-US-PHONE-IS-INVALID} 88-level at line 102
     * ({@code VALUE '000'}) — Java-migration message preserved as plain
     * English because the COBOL workflow does not surface a dedicated
     * reject literal for the area-code edit (the error is funnelled
     * through {@code WS-RETURN-MSG} via the generic phone-edit flag).
     */
    static final String MSG_PHONE_INVALID = "Phone number is not valid";

    /**
     * Reject message returned when {@link AccountRepository#findById(Object)}
     * returns {@link Optional#empty()} — verbatim COBOL literal from
     * {@code COACTUPC.cbl} {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} 88-level
     * at line 517: {@code 'Could not lock account record for update'}.
     * Mirrors the COBOL {@code DFHRESP(NOTFND)} response on the
     * {@code EXEC CICS READ FILE('ACCTDAT') UPDATE} at line 3897.
     */
    static final String MSG_COULD_NOT_LOCK_ACCT = "Could not lock account record for update";

    /**
     * Reject message returned when {@link CustomerRepository#findById(Object)}
     * returns {@link Optional#empty()} — verbatim COBOL literal from
     * {@code COACTUPC.cbl} {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} 88-level
     * at line 519: {@code 'Could not lock customer record for update'}.
     * Mirrors the COBOL {@code DFHRESP(NOTFND)} response on the
     * {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} at line 3925.
     */
    static final String MSG_COULD_NOT_LOCK_CUST = "Could not lock customer record for update";

    /**
     * Success message returned when both JPA {@code save()} calls complete
     * normally — verbatim COBOL literal from {@code COACTUPC.cbl}
     * {@code CONFIRM-UPDATE-SUCCESS} 88-level at line 474:
     * {@code 'Changes committed to database'}.
     */
    static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /**
     * Active-status flag indicating the account is active (positive case
     * for posting workflows) — single-character literal from COBOL
     * {@code ACCT-UPDATE-ACTIVE-STATUS PIC X(01)} at line 423.
     */
    static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Active-status flag indicating the account is inactive — single-
     * character literal from COBOL {@code ACCT-UPDATE-ACTIVE-STATUS}.
     * Drives reject paths in posting workflows ({@code CBTRN02C}:
     * inactive accounts rejected).
     */
    static final String ACTIVE_STATUS_NO = "N";

    /**
     * Compiled regular expression matching exactly 9 ASCII digits — the
     * SSN format ({@code CUST-UPDATE-SSN PIC 9(09)} at COACTUPC.cbl line
     * 449). Anchored, so it rejects any length other than 9 and any
     * non-digit character. Compiled once as a class-level constant.
     */
    private static final Pattern SSN_PATTERN = Pattern.compile("\\d{9}");

    /**
     * Compiled regular expression matching the COBOL phone-number format
     * {@code (NNN)NNN-NNNN} — the
     * {@code CUST-UPDATE-PHONE-NUM-1 PIC X(15)} layout at COACTUPC.cbl
     * line 447. The capturing group {@code (\\d{3})} extracts the area
     * code ({@code WS-EDIT-US-PHONE-NUMA}) for the {@code "000"} reject
     * check at COACTUPC.cbl line 102. Compiled once as a class-level
     * constant.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\((\\d{3})\\)\\d{3}-\\d{4}");

    // ---------------------------------------------------------------------
    // Collaborators — JPA repositories + Clock boundary
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link Account} entities — the Java replacement
     * for COBOL {@code EXEC CICS READ FILE('ACCTDAT') UPDATE}
     * (the account lock-for-update at line 3897 of
     * {@code app/cbl/COACTUPC.cbl}) and {@code EXEC CICS REWRITE
     * FILE('ACCTDAT')} (the account persistence step at line 4078).
     * Constructor-injected so unit tests can wire a Mockito mock without
     * a Spring context.
     */
    private final AccountRepository accountRepository;

    /**
     * JPA repository for {@link Customer} entities — the Java replacement
     * for COBOL {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} (the
     * customer lock-for-update at line 3925) and {@code EXEC CICS REWRITE
     * FILE('CUSTDAT')} (the customer persistence step at line 4093).
     * Constructor-injected so unit tests can wire a Mockito mock without
     * a Spring context.
     */
    private final CustomerRepository customerRepository;

    /**
     * JPA repository for {@link com.aws.carddemo.entity.CardXref} entities
     * — carried by the service constructor for future account-card
     * cross-reference validation when subsequent migration agents extend
     * this service. The current minimum-viable implementation does NOT
     * use this field. Held as final so the collaborator can be substituted
     * at construction (e.g., by a Mockito mock in unit tests, or by
     * Spring's dependency injection in production).
     */
    @SuppressWarnings("unused")
    private final CardXrefRepository cardXrefRepository;

    /**
     * Injected {@link Clock} for deterministic time-dependent behaviour —
     * carried by the service constructor for parity with sibling services
     * (which use the injected Clock to derive audit timestamps and other
     * time-dependent behaviour). The current minimum-viable implementation
     * does not stamp audit timestamps but accepts the Clock for symmetry.
     * Held as final so the test suite can inject
     * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} for
     * deterministic test outcomes (AAP §0.10.9 test independence).
     */
    @SuppressWarnings("unused")
    private final Clock clock;

    /**
     * Constructs a new {@code AccountUpdateService}.
     *
     * @param accountRepository    JPA repository for {@link Account} lookups
     *                             and updates (Java replacement for COBOL
     *                             {@code EXEC CICS READ / REWRITE} on
     *                             {@code ACCTDAT}). Must not be {@code null}
     *                             — the service does not guard against
     *                             {@code null} collaborators because Spring
     *                             DI would surface the misconfiguration at
     *                             startup; unit tests wire a Mockito mock.
     * @param customerRepository   JPA repository for {@link Customer}
     *                             lookups and updates (Java replacement
     *                             for COBOL {@code EXEC CICS READ /
     *                             REWRITE} on {@code CUSTDAT}). Must not
     *                             be {@code null}.
     * @param cardXrefRepository   JPA repository for the card-cross-
     *                             reference entity; carried for future
     *                             account-cross-reference validation. Must
     *                             not be {@code null}.
     * @param clock                clock used for deterministic time-
     *                             dependent behaviour; in production
     *                             typically {@link Clock#systemDefaultZone()},
     *                             in tests a {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
     *                             instance. Must not be {@code null}.
     */
    public AccountUpdateService(AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                CardXrefRepository cardXrefRepository,
                                Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.clock = clock;
    }

    /**
     * Update an existing {@link Account} record AND its associated
     * {@link Customer} record in a single atomic transaction after
     * enforcing the COBOL-inherited validation cascade. Implements the
     * Java equivalent of {@code app/cbl/COACTUPC.cbl}
     * {@code 9600-WRITE-PROCESSING} (lines 3888–4105).
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li><b>Account active status domain check</b> (COBOL parity:
     *       {@code ACCT-STATUS-MUST-BE-YES-NO} at line 503).</li>
     *   <li><b>Credit limit non-negative check</b> (COBOL parity:
     *       {@code CRED-LIMIT-IS-NOT-VALID} at line 507).</li>
     *   <li><b>SSN format + PART1 check</b> (COBOL parity:
     *       {@code INVALID-SSN-PART1} at line 121).</li>
     *   <li><b>Phone area-code check</b> (COBOL parity:
     *       {@code WS-EDIT-US-PHONE-IS-INVALID} at line 102).</li>
     *   <li><b>Account repository lookup</b> (COBOL parity:
     *       {@code EXEC CICS READ FILE('ACCTDAT') UPDATE} at line 3897).
     *       {@link Optional#empty()} → reject with
     *       {@link #MSG_COULD_NOT_LOCK_ACCT}.</li>
     *   <li><b>Customer repository lookup</b> (COBOL parity:
     *       {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} at line 3925).
     *       {@link Optional#empty()} → reject with
     *       {@link #MSG_COULD_NOT_LOCK_CUST}.</li>
     *   <li><b>Apply field updates</b> to the loaded entities (all
     *       mutable fields except the primary keys).</li>
     *   <li><b>{@link AccountRepository#save(Object)}</b> (COBOL parity:
     *       {@code EXEC CICS REWRITE FILE('ACCTDAT')} at line 4078).</li>
     *   <li><b>{@link CustomerRepository#save(Object)}</b> (COBOL parity:
     *       {@code EXEC CICS REWRITE FILE('CUSTDAT')} at line 4093).</li>
     *   <li><b>Success response</b> with the verbatim COBOL
     *       {@code 'Changes committed to database'} message.</li>
     * </ol>
     *
     * <p>Spring's {@code @Transactional(rollbackFor = Exception.class)}
     * annotation (added by subsequent migration agents when the
     * {@code @Service} stereotype is also added) guarantees that any
     * uncaught exception from steps 8 or 9 — including
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * from a JPA {@code @Version} mismatch — triggers atomic rollback of
     * BOTH the account save AND the customer save (Java equivalent of
     * COBOL {@code EXEC CICS SYNCPOINT ROLLBACK} at line 4100).
     *
     * @param request the account-update request carrying the target
     *                account's identifier, the target customer's
     *                identifier, and all modified field values; must
     *                not be {@code null} (the service does not guard
     *                against {@code null} — the controller layer is
     *                responsible for producing a populated request)
     * @return a populated {@link AccountUpdateResult} encoding either
     *         success (with the {@code 'Changes committed to database'}
     *         message) or failure (with one of the verbatim COBOL reject
     *         literals)
     * @throws org.springframework.dao.OptimisticLockingFailureException
     *         when the JPA {@code @Version} field on either the loaded
     *         account or the loaded customer does not match the
     *         persisted version (a concurrent update happened between
     *         this transaction's lookup and save). The Java equivalent
     *         of the COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag
     *         at line 521.
     */
    public AccountUpdateResult updateAccount(AccountUpdateRequest request) {
        // Step 1 — Account active status domain check (COBOL parity:
        // ACCT-STATUS-MUST-BE-YES-NO at line 503; mirrors COBOL test
        // "IF ACUP-NEW-ACTIVE-STATUS NOT EQUAL 'Y' AND NOT EQUAL 'N'").
        // Performed BEFORE any repository lookup so the database is
        // never consulted on a malformed status value.
        String activeStatus = request.getAccountActiveStatus();
        if (!ACTIVE_STATUS_YES.equals(activeStatus) && !ACTIVE_STATUS_NO.equals(activeStatus)) {
            return AccountUpdateResult.failure(MSG_ACCT_STATUS_INVALID);
        }

        // Step 2 — Credit limit non-negative check (COBOL parity:
        // CRED-LIMIT-IS-NOT-VALID at line 507; mirrors the signed-number
        // edit WS-EDIT-CREDIT-LIMIT in the COBOL workflow). The COBOL
        // PIC S9(10)V99 supports negative values structurally, but the
        // business rule rejects them (a negative credit limit is
        // semantically invalid for a credit-card account).
        BigDecimal creditLimit = request.getCreditLimit();
        if (creditLimit == null || creditLimit.signum() < 0) {
            return AccountUpdateResult.failure(MSG_CREDIT_LIMIT_INVALID);
        }

        // Step 3 — SSN format + PART1 check (COBOL parity:
        // INVALID-SSN-PART1 at line 121; mirrors the 88-level
        // "VALUES 0, 666, 900 THRU 999" reject on the first three
        // digits of the SSN). The 9-digit regex prefilters non-numeric
        // and wrong-length inputs; the substring-based PART1 check
        // implements the COBOL exclusion ranges.
        String ssn = request.getSsn();
        if (ssn == null || !SSN_PATTERN.matcher(ssn).matches() || isInvalidSsnPart1(ssn.substring(0, 3))) {
            return AccountUpdateResult.failure(MSG_SSN_INVALID);
        }

        // Step 4 — Phone area-code check (COBOL parity:
        // WS-EDIT-US-PHONE-IS-INVALID at line 102; mirrors the 88-level
        // VALUE '000' reject on the WS-EDIT-US-PHONE-NUMA area-code
        // group). The regex prefilters malformed phone strings and
        // captures the area code; the captured group is compared
        // against the COBOL reject value.
        String phone = request.getPhoneNumber1();
        if (phone == null) {
            return AccountUpdateResult.failure(MSG_PHONE_INVALID);
        }
        java.util.regex.Matcher phoneMatcher = PHONE_PATTERN.matcher(phone);
        if (!phoneMatcher.matches() || "000".equals(phoneMatcher.group(1))) {
            return AccountUpdateResult.failure(MSG_PHONE_INVALID);
        }

        // Step 5 — READ ACCTDAT by ACCT-ID primary key (COBOL parity:
        // EXEC CICS READ FILE('ACCTDAT') UPDATE at line 3897). The
        // repository .findById(...) returns Optional.empty() for the
        // DFHRESP(NOTFND) case (the COBOL workflow sets
        // COULD-NOT-LOCK-ACCT-FOR-UPDATE at line 3912). I/O errors
        // propagate as DataAccessException subclasses and are handled
        // by the controller layer's exception-handler chain.
        Optional<Account> existingAccountOpt = accountRepository.findById(request.getAccountId());
        if (existingAccountOpt.isEmpty()) {
            // COBOL: SET COULD-NOT-LOCK-ACCT-FOR-UPDATE TO TRUE (line 3912)
            return AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_ACCT);
        }

        // Step 6 — READ CUSTDAT by CUST-ID primary key (COBOL parity:
        // EXEC CICS READ FILE('CUSTDAT') UPDATE at line 3925). The
        // repository .findById(...) returns Optional.empty() for the
        // DFHRESP(NOTFND) case (the COBOL workflow sets
        // COULD-NOT-LOCK-CUST-FOR-UPDATE at line 3939).
        Optional<Customer> existingCustomerOpt = customerRepository.findById(request.getCustomerId());
        if (existingCustomerOpt.isEmpty()) {
            // COBOL: SET COULD-NOT-LOCK-CUST-FOR-UPDATE TO TRUE (line 3939)
            return AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_CUST);
        }

        // Step 7 — Apply updates to the loaded Account entity (COBOL
        // parity: lines 4061-4076, INITIALIZE ACCT-UPDATE-RECORD with
        // the new values from the operator's screen input). The primary
        // key (ACCT-ID) is INTENTIONALLY not assigned — Java migration
        // invariant documented on the class-level "Primary-Key
        // Immutability" Javadoc section.
        Account account = existingAccountOpt.get();
        account.setActiveStatus(activeStatus);
        account.setCurrentBalance(request.getCurrentBalance());
        account.setCreditLimit(creditLimit);
        account.setCashCreditLimit(request.getCashCreditLimit());
        account.setCurrentCycleCredit(request.getCurrentCycleCredit());
        account.setCurrentCycleDebit(request.getCurrentCycleDebit());
        account.setOpenDate(request.getOpenDate());
        account.setExpirationDate(request.getExpirationDate());
        account.setReissueDate(request.getReissueDate());
        account.setGroupId(request.getGroupId());

        // Step 8 — Apply updates to the loaded Customer entity (COBOL
        // parity: similar paragraph for CUST-UPDATE-RECORD).
        Customer customer = existingCustomerOpt.get();
        customer.setFirstName(request.getFirstName());
        customer.setMiddleName(request.getMiddleName());
        customer.setLastName(request.getLastName());
        customer.setAddressLine1(request.getAddressLine1());
        customer.setAddressLine2(request.getAddressLine2());
        customer.setAddressLine3(request.getAddressLine3());
        customer.setAddressStateCode(request.getStateCode());
        customer.setAddressCountryCode(request.getCountryCode());
        customer.setAddressZip(request.getZipCode());
        customer.setPhoneNumber1(phone);
        customer.setPhoneNumber2(request.getPhoneNumber2());
        customer.setSsn(ssn);
        customer.setGovernmentIssuedId(request.getGovernmentIssuedId());
        customer.setDateOfBirth(request.getDateOfBirth());
        customer.setEftAccountId(request.getEftAccountId());
        customer.setPrimaryCardHolderIndicator(request.getPrimaryCardHolderIndicator());
        customer.setFicoCreditScore(request.getFicoCreditScore());

        // Step 9 — Persist the Account via Spring Data JPA (COBOL parity:
        // EXEC CICS REWRITE FILE('ACCTDAT') at line 4078). On JPA
        // @Version mismatch this raises
        // OptimisticLockingFailureException — the service does not catch
        // it, letting it propagate to the controller layer (mapped to
        // HTTP 409 Conflict). Spring's @Transactional rollback boundary
        // ensures no partial state is persisted.
        accountRepository.save(account);

        // Step 10 — Persist the Customer via Spring Data JPA (COBOL
        // parity: EXEC CICS REWRITE FILE('CUSTDAT') at line 4093). If
        // this save throws OptimisticLockingFailureException OR any
        // other DataAccessException, Spring's
        // @Transactional(rollbackFor=Exception.class) boundary rolls
        // back the prior accountRepository.save(account) atomically —
        // the Java equivalent of COBOL EXEC CICS SYNCPOINT ROLLBACK at
        // line 4100.
        customerRepository.save(customer);

        // Step 11 — Build the success response (COBOL parity:
        // CONFIRM-UPDATE-SUCCESS at line 474, verbatim literal
        // 'Changes committed to database'). The literal is returned
        // verbatim per AAP §0.10.4 Immutable Boundaries: downstream
        // consumers reading the JSON response envelope must see the
        // same textual confirmation as the COBOL baseline.
        return AccountUpdateResult.success(MSG_UPDATE_SUCCESS);
    }

    /**
     * Test whether the supplied 3-character string represents an
     * "invalid" SSN PART1 (the first three digits of a US Social
     * Security Number) per the COBOL {@code INVALID-SSN-PART1} 88-level
     * condition at {@code COACTUPC.cbl} line 121:
     *
     * <pre>
     *   88 INVALID-SSN-PART1  VALUES 0
     *                                666
     *                                900 THRU 999
     * </pre>
     *
     * <p>The SSA (Social Security Administration) has never assigned
     * SSNs with these PART1 values, so the COBOL workflow rejects them
     * as data-entry errors. The Java implementation parses the 3-digit
     * string as an integer and compares against the COBOL exclusion
     * set: {@code 0}, {@code 666}, or any value in
     * {@code 900}–{@code 999}.
     *
     * @param part1 the candidate PART1 string; expected to be exactly
     *              3 ASCII digits (the caller ensures this via the
     *              {@link #SSN_PATTERN} match)
     * @return {@code true} when the PART1 value matches the COBOL
     *         exclusion set; {@code false} otherwise
     */
    private static boolean isInvalidSsnPart1(String part1) {
        int value = Integer.parseInt(part1);
        return value == 0 || value == 666 || (value >= 900 && value <= 999);
    }
}
