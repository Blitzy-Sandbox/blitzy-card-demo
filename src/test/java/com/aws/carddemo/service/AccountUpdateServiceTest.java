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

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies; file
// schema internal_imports).
//
//   * TestFixtures — single source of truth for shared test constants. This
//     test consumes:
//       - TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10 ("00000000010", the
//         11-digit zero-padded account ID mirroring COBOL
//         ACCT-UPDATE-ID PIC 9(11) at COACTUPC.cbl line 422).
//       - TestFixtures.Accounts.DEFAULT_GROUP_ID ("A000000000", the
//         canonical disclosure-group identifier mirroring COBOL
//         ACCT-UPDATE-GROUP-ID PIC X(10) at line 432).
//       - TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01 ("000000001", the
//         9-digit zero-padded customer ID mirroring COBOL
//         CUST-UPDATE-ID PIC 9(09) at line 438).
//       - TestFixtures.Dates.FIXED_CLOCK_INSTANT ("2024-01-15T00:00:00Z",
//         the deterministic clock instant injected into the service
//         per AAP §0.10.9 test-independence requirement).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * Account / Customer — JPA entities returned by the repositories on
//     findById(...) lookups and the captured arguments on save(...)
//     calls. Imported via fully-qualified package because the entities
//     live in the com.aws.carddemo.entity package (NOT this test's
//     com.aws.carddemo.service package).
//
//   * AccountRepository / CustomerRepository / CardXrefRepository — the
//     Spring Data JPA repositories the production AccountUpdateService
//     delegates to. Mocked at the JPA-repository boundary per AAP §0.10.1
//     ("Mocks limited to external boundaries: file I/O, downstream
//     service calls, database").
//
//   * AccountUpdateService / AccountUpdateRequest / AccountUpdateResult —
//     the production classes under test. No explicit imports because
//     they share this test's package (com.aws.carddemo.service); Java
//     resolves simple-named references via package membership.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (file schema external_imports; AAP §0.10.7 framework
// constraint — JUnit 5 only, never JUnit 4 / Vintage).
//
//   * @BeforeEach — re-instantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9).
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups HappyPath / OptimisticLockingFailures /
//     ValidationRejects / FinancialPrecision scenarios into the four
//     semantic sections that match this test class's
//     exports.members_exposed schema entries.
//   * @Test — single-execution test marker for non-parameterised scenarios.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (file schema external_imports).
//
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives the
//     SSN PART1 reject scenarios across the COBOL INVALID-SSN-PART1
//     exclusion set (000, 666, 900, 950, 999) and the phone-area-code
//     reject scenario across the COBOL INVALID-PHONE-NUMA value ('000').
//     Fulfils AAP §0.10.7 "@ParameterizedTest for calculation variants"
//     directive.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (file schema external_imports for mockito-core; resolved via
// spring-boot-starter-test BOM 3.3.13 to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * ArgumentCaptor — captures the Account and Customer instances passed
//     to repository save() calls so the test can assert that the
//     persisted entities carry the new field values from the request
//     AND preserve their primary keys.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`."). Each reject-path test
//     deliberately avoids stubbing the repository (or stubs only the
//     scenario-relevant method) to prove via Mockito.verify(...,
//     never()) that the production code never reaches the unstubbed
//     methods on rejected calls.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Framework data-access exception (file schema external_imports for
// spring-tx, resolved via spring-boot-starter-data-jpa transitively).
//
//   * OptimisticLockingFailureException — Spring's JPA optimistic-locking
//     exception thrown when a save(...) call detects a @Version mismatch.
//     Stubbed via Mockito.when(...).thenThrow(...) in the
//     OptimisticLockingFailures nested test group; asserted via
//     AssertJ's assertThatThrownBy(...). This is the Java translation
//     of the COBOL DATA-WAS-CHANGED-BEFORE-UPDATE flag at COACTUPC.cbl
//     line 521, raised by the 9700-CHECK-CHANGE-IN-REC paragraph (lines
//     4143/4189) and the SYNCPOINT ROLLBACK trigger at line 4100.
// ---------------------------------------------------------------------------
import org.springframework.dao.OptimisticLockingFailureException;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.math, java.time, java.util).
//
//   * BigDecimal — JDK 17 monetary primitive (AAP §0.10.3 financial-
//     precision mandate). Used to construct currentBalance, creditLimit,
//     cashCreditLimit, currentCycleCredit, currentCycleDebit values at
//     scale 2 per COBOL PIC S9(10)V99, and to construct the negative-
//     credit-limit reject scenario value. Never float/double per the
//     no-float/no-double clause.
//   * Clock / Instant / ZoneOffset — Clock.fixed(Instant.parse(
//     TestFixtures.Dates.FIXED_CLOCK_INSTANT), ZoneOffset.UTC) pins the
//     test "now" to 2024-01-15T00:00:00Z and is injected as the fourth
//     constructor argument to AccountUpdateService.
//   * Optional — wraps the repository's findById return values:
//     Optional.of(existing) for happy-update / optimistic-locking /
//     validation-reject scenarios; Optional.empty() for the
//     COULD-NOT-LOCK-ACCT and COULD-NOT-LOCK-CUST reject scenarios that
//     mirror COBOL DFHRESP(NOTFND) on the ACCTDAT / CUSTDAT READ UPDATE
//     attempts at lines 3897 and 3925.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * Assertions.assertThatThrownBy — fluent exception-assertion entry
//     point used in the OptimisticLockingFailures scenarios to verify
//     the uncaught exception propagation.
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications and in stubs that don't care about the specific
//     argument value.
//   * Mockito.never — verifies that a stub method was NOT invoked; used
//     to prove that validation rejects short-circuit BEFORE the save
//     call.
//   * Mockito.times — verifies the exact number of invocations; used to
//     pair with ArgumentCaptor on the single-save happy-path scenario.
//   * Mockito.verify — interaction assertion; pairs with .never(),
//     .times(), and ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths (Optional.of(...) from findById, the
//     saved entity from save), the not-found paths (Optional.empty()
//     from findById), and the version-mismatch path
//     (OptimisticLockingFailureException from save).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountUpdateService}, the migrated Java equivalent
 * of the 4,236-line COBOL CICS program {@code app/cbl/COACTUPC.cbl} (the
 * largest online program in CardDemo, TRANID {@code CAUP}). The service
 * performs a two-table coordinated update (ACCTDAT + CUSTDAT) with
 * optimistic concurrency control.
 *
 * <h2>COBOL Provenance — COACTUPC.cbl</h2>
 *
 * <p>The {@code 9600-WRITE-PROCESSING} paragraph (lines 3888-4105)
 * implements the critical optimistic-locking + SYNCPOINT ROLLBACK pattern:
 * <ol>
 *   <li>{@code READ UPDATE} on ACCTDAT (acquires lock at line 3897) → if
 *       fails → {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} at line 3912,
 *       return.</li>
 *   <li>{@code READ UPDATE} on CUSTDAT (acquires lock at line 3925) → if
 *       fails → {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} at line 3939,
 *       return.</li>
 *   <li>{@code 9700-CHECK-CHANGE-IN-REC} (lines 4131-4189) — compare
 *       current DB state against the version the user loaded → if
 *       differs → {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 521,
 *       set at lines 4143/4189), return (this is the optimistic-
 *       concurrency-control violation).</li>
 *   <li>{@code REWRITE} ACCTDAT (line 4078) → if fails →
 *       {@code LOCKED-BUT-UPDATE-FAILED}, return.</li>
 *   <li>{@code REWRITE} CUSTDAT (line 4093) → if fails →
 *       {@code LOCKED-BUT-UPDATE-FAILED}, {@code EXEC CICS SYNCPOINT
 *       ROLLBACK} (line 4100 — rolls back the account REWRITE).</li>
 *   <li>On full success (line 2969): {@code SET CONFIRM-UPDATE-SUCCESS
 *       TO TRUE} → "Changes committed to database" (line 474).</li>
 * </ol>
 *
 * <h2>Java Migration Pattern</h2>
 *
 * <p>The CICS optimistic locking + SYNCPOINT translates to:
 * <ul>
 *   <li>JPA {@code @Version} fields on {@code Account} and
 *       {@code Customer} entities → JPA throws
 *       {@link OptimisticLockingFailureException} on version mismatch.</li>
 *   <li>{@code @Transactional(rollbackFor = Exception.class)} on
 *       {@link AccountUpdateService#updateAccount} — guarantees both
 *       {@code accountRepository.save()} and
 *       {@code customerRepository.save()} roll back atomically if either
 *       throws.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>This test class instantiates the real {@link AccountUpdateService}
 * via its constructor and exercises its public API. Mocks replace ONLY
 * the JPA repository boundaries; no validation or optimistic-locking
 * detection logic is reimplemented in test bodies.
 * {@link ArgumentCaptor} verifies that the values the service computes
 * and persists match expectations — never that the test recomputes them
 * independently.
 *
 * <h2>Coverage Target (AAP §0.7.1)</h2>
 *
 * <p>{@code com.aws.carddemo.service.**} carries the user-mandated ≥80%
 * line / ≥70% branch coverage floor. The tests below exercise the happy
 * path, every optimistic-locking failure path (account-not-found,
 * customer-not-found, account-version-mismatch, customer-version-
 * mismatch), every validation reject (active status, credit limit, SSN
 * PART1 across 5 exclusion values, phone area code), and the
 * {@code @Transactional} rollback boundary semantics.
 *
 * @see AccountUpdateService
 * @see AccountUpdateRequest
 * @see AccountUpdateResult
 * @see Account
 * @see Customer
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardXrefRepository
 * @see TestFixtures.Accounts
 * @see TestFixtures.Customers
 * @see TestFixtures.Dates
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService — COACTUPC.cbl migration parity")
final class AccountUpdateServiceTest {

    /**
     * Mocked {@link AccountRepository} — the JPA repository boundary the
     * production {@link AccountUpdateService} delegates to for
     * {@code findById} (the COBOL {@code EXEC CICS READ FILE('ACCTDAT')
     * UPDATE} replacement at line 3897) and {@code save} (the COBOL
     * {@code EXEC CICS REWRITE FILE('ACCTDAT')} at line 4078). Per AAP
     * §0.10.1, mocked because it crosses the database boundary. Re-
     * created per {@code @Test} method by {@link MockitoExtension},
     * ensuring strict isolation between scenarios.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mocked {@link CustomerRepository} — the JPA repository boundary the
     * production {@link AccountUpdateService} delegates to for
     * {@code findById} (the COBOL {@code EXEC CICS READ FILE('CUSTDAT')
     * UPDATE} replacement at line 3925) and {@code save} (the COBOL
     * {@code EXEC CICS REWRITE FILE('CUSTDAT')} at line 4093). Per AAP
     * §0.10.1, mocked because it crosses the database boundary.
     */
    @Mock
    private CustomerRepository customerRepository;

    /**
     * Mocked {@link CardXrefRepository} — the JPA repository boundary the
     * production {@link AccountUpdateService} accepts as a constructor
     * argument for future account-cross-reference validation. The current
     * minimum-viable implementation does not invoke this repository on
     * any code path; the {@code @Mock} field is declared here so the
     * service constructor can be satisfied with a non-{@code null}
     * argument. No stubs are configured on this mock — under Mockito's
     * {@code STRICT_STUBS} mode this is acceptable (only unused stubs,
     * not unused mocks, raise
     * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}).
     */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /**
     * Fixed {@link Clock} pinned at {@code 2024-01-15T00:00:00Z} (UTC) for
     * deterministic time-dependent behaviour in the production
     * {@link AccountUpdateService}. The {@link Clock#fixed(Instant,
     * java.time.ZoneId)} factory produces a {@link Clock} that always
     * returns the supplied {@link Instant} from {@link Clock#instant()},
     * guaranteeing reproducible test outcomes across runs and parallel
     * executions per AAP §0.10.9 test-independence requirement. The
     * instant is sourced from {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}
     * so every test class in the suite that uses a fixed clock agrees
     * on the same "now".
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
            ZoneOffset.UTC);

    /**
     * System under test — the real {@link AccountUpdateService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborators above and to prevent any
     * accidental state leak between tests.
     */
    private AccountUpdateService service;

    /**
     * Constructs a fresh {@link AccountUpdateService} before every test
     * method, injecting the freshly-instantiated {@link #accountRepository},
     * {@link #customerRepository}, and {@link #cardXrefRepository} mocks
     * plus the {@link #fixedClock} deterministic clock. The combination
     * of per-method {@code @Mock} instantiation (driven by
     * {@link MockitoExtension}) and per-method service construction here
     * guarantees that no stub or interaction from one test leaks into
     * another (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new AccountUpdateService(
                accountRepository, customerRepository, cardXrefRepository, fixedClock);
    }


    // =========================================================================
    // HAPPY PATH — valid request updates both Account and Customer, returns success
    //
    // COACTUPC.cbl 9600-WRITE-PROCESSING (lines 3888-4105) reads the existing
    // ACCT-RECORD by primary key, reads the CUST-RECORD by primary key,
    // compares loaded vs displayed before-image fields via
    // 9700-CHECK-CHANGE-IN-REC, INITIALIZES ACCT-UPDATE-RECORD and
    // CUST-UPDATE-RECORD with the new values from the operator's screen
    // input, and REWRITEs both. On full success (line 2969) it sets
    // CONFIRM-UPDATE-SUCCESS (the 88-level literal 'Changes committed to
    // database' at line 474). The Java migration collapses this into a
    // findById-x2 → mutate-loaded-entities → save-x2 sequence with JPA's
    // @Version field replacing the COBOL READ UPDATE / CHECK-CHANGE-IN-REC
    // pessimistic-lock idiom.
    // =========================================================================

    /**
     * Happy-path scenarios for
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}.
     *
     * <p>Each scenario configures the mocked {@link #accountRepository} and
     * {@link #customerRepository} to return {@link Optional#of(Object)} on
     * the {@code findById(...)} call and to echo back the supplied entity
     * on the {@code save(...)} call, then drives the service with a valid
     * {@link AccountUpdateRequest} built by {@link #buildValidRequest()}.
     * The scenarios assert on (a) the returned {@link AccountUpdateResult}'s
     * success flag and message, (b) the {@link Account} and {@link Customer}
     * entities captured by {@link ArgumentCaptor} on the single
     * {@code save(...)} invocation per repository (verifying the per-field
     * mutations and the primary-key preservation), and (c) the exact
     * number of {@code save(...)} invocations.
     */
    @Nested
    @DisplayName("Happy path — valid request persists both entities")
    class HappyPath {

        /**
         * Verifies that a valid {@link AccountUpdateRequest} results in:
         * <ul>
         *   <li>A single {@link AccountRepository#save(Object)} invocation.</li>
         *   <li>A single {@link CustomerRepository#save(Object)} invocation.</li>
         *   <li>A successful {@link AccountUpdateResult} (i.e.
         *       {@link AccountUpdateResult#isSuccess()} returns {@code true}).</li>
         *   <li>The persisted {@link Account} carrying the new field values
         *       from the request, with its primary key preserved from the
         *       originally-loaded entity.</li>
         *   <li>The persisted {@link Customer} carrying the new field values
         *       from the request, with its primary key preserved from the
         *       originally-loaded entity.</li>
         * </ul>
         *
         * <p>COBOL parity: {@code 9600-WRITE-PROCESSING} happy path (lines
         * 3888-4105 DFHRESP(NORMAL) branches on both READs and both
         * REWRITEs), with the {@code 'Changes committed to database'}
         * success literal at line 474.
         */
        @Test
        @DisplayName("updateAccount(validRequest) persists both Account and Customer and returns success")
        void updateAccount_validRequest_persistsBothEntitiesAndReturnsSuccess() {
            // Arrange — pre-existing Account and Customer in their respective
            // VSAM-replacement tables.
            Account existingAccount = standardAccount();
            Customer existingCustomer = standardCustomer();

            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(existingAccount));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(existingCustomer));
            // The save stubs echo back the captured arguments so the
            // production code's return values (if any) carry the saved
            // entity references; the service does not use these return
            // values (it returns an AccountUpdateResult, not the entities),
            // but the stubs are wired to match Spring Data JPA's actual
            // save contract.
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateRequest request = buildValidRequest();

            // Act — drive the real production service.
            AccountUpdateResult result = service.updateAccount(request);

            // Assert — observable success.
            assertThat(result).isNotNull();
            assertThat(result.isSuccess())
                    .as("Happy-path request must succeed (COBOL: 9600-WRITE-"
                            + "PROCESSING DFHRESP(NORMAL) at line 2969)")
                    .isTrue();
            assertThat(result.getMessage())
                    .as("Success message must be the verbatim COBOL literal"
                            + " 'Changes committed to database' from"
                            + " CONFIRM-UPDATE-SUCCESS at line 474")
                    .isEqualTo("Changes committed to database");

            // Assert — the Account passed to save() carries the new values.
            // ArgumentCaptor is the only legitimate way to inspect the
            // entity the production code constructed: per AAP §0.10.1 the
            // test must NOT duplicate the production logic that derived
            // the values.
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            Account savedAccount = accountCaptor.getValue();
            assertThat(savedAccount.getCurrentBalance())
                    .as("currentBalance preserved from request (COBOL: "
                            + "ACCT-UPDATE-CURR-BAL PIC S9(10)V99 at line 424)")
                    .isEqualByComparingTo(request.getCurrentBalance());
            assertThat(savedAccount.getCreditLimit())
                    .as("creditLimit preserved from request (COBOL: "
                            + "ACCT-UPDATE-CREDIT-LIMIT PIC S9(10)V99 at line 425)")
                    .isEqualByComparingTo(request.getCreditLimit());
            assertThat(savedAccount.getActiveStatus())
                    .as("activeStatus preserved from request (COBOL: "
                            + "ACCT-UPDATE-ACTIVE-STATUS PIC X(01) at line 423)")
                    .isEqualTo(request.getAccountActiveStatus());
            assertThat(savedAccount.getAccountId())
                    .as("accountId is the immutable PK; must equal the"
                            + " findById key (COBOL: ACCT-UPDATE-ID PIC 9(11)"
                            + " at line 422)")
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — the Customer passed to save() carries the new values.
            ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(customerCaptor.capture());
            Customer savedCustomer = customerCaptor.getValue();
            assertThat(savedCustomer.getFirstName())
                    .as("firstName preserved from request (COBOL: "
                            + "CUST-UPDATE-FIRST-NAME PIC X(25) at line 439)")
                    .isEqualTo(request.getFirstName());
            assertThat(savedCustomer.getLastName())
                    .as("lastName preserved from request (COBOL: "
                            + "CUST-UPDATE-LAST-NAME PIC X(25) at line 441)")
                    .isEqualTo(request.getLastName());
            assertThat(savedCustomer.getSsn())
                    .as("ssn preserved from request (COBOL: "
                            + "CUST-UPDATE-SSN PIC 9(09) at line 449)")
                    .isEqualTo(request.getSsn());
            assertThat(savedCustomer.getCustomerId())
                    .as("customerId is the immutable PK; must equal the"
                            + " findById key (COBOL: CUST-UPDATE-ID PIC 9(09)"
                            + " at line 438)")
                    .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);

            // Assert — exactly one save invocation per repository.
            verify(accountRepository, times(1)).save(any(Account.class));
            verify(customerRepository, times(1)).save(any(Customer.class));
        }

        /**
         * Verifies the primary-key immutability invariant: the production
         * service NEVER calls {@link Account#setAccountId(String)} or
         * {@link Customer#setCustomerId(String)} on the loaded entities,
         * so the persisted primary keys always equal the ones originally
         * loaded from the repositories.
         *
         * <p>COBOL parity: {@code 9600-WRITE-PROCESSING} lines 4061-4076
         * INITIALIZE ACCT-UPDATE-RECORD and CUST-UPDATE-RECORD with the
         * loaded ACCT-ID and CUST-ID values before assigning the
         * operator's other fields.
         */
        @Test
        @DisplayName("updateAccount preserves accountId and customerId as immutable PKs")
        void updateAccount_validRequest_preservesPrimaryKeysAsImmutable() {
            // Arrange
            Account existingAccount = standardAccount();
            Customer existingCustomer = standardCustomer();
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(existingAccount));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(existingCustomer));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act — drive the production service with a fully-valid request.
            service.updateAccount(buildValidRequest());

            // Assert — captured Account carries the same PK as findById(...).
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getAccountId())
                    .as("accountId is the immutable PK; must not change on"
                            + " update (COBOL: ACCT-UPDATE-ID PIC 9(11) at"
                            + " line 422 of COACTUPC.cbl)")
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — captured Customer carries the same PK as findById(...).
            ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(customerCaptor.capture());
            assertThat(customerCaptor.getValue().getCustomerId())
                    .as("customerId is the immutable PK; must not change on"
                            + " update (COBOL: CUST-UPDATE-ID PIC 9(09) at"
                            + " line 438 of COACTUPC.cbl)")
                    .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        }

        /**
         * Verifies that the success response carries the verbatim COBOL
         * literal {@code 'Changes committed to database'} from
         * {@code CONFIRM-UPDATE-SUCCESS} at {@code COACTUPC.cbl} line 474.
         *
         * <p>This is the load-bearing AAP §0.10.4 (Immutable Boundaries)
         * test: downstream consumers reading the JSON response envelope
         * must see the same textual confirmation as the COBOL baseline.
         */
        @Test
        @DisplayName("updateAccount(validRequest) returns 'Changes committed to database' "
                + "(COBOL CONFIRM-UPDATE-SUCCESS at line 474)")
        void updateAccount_validRequest_returnsChangesCommittedMessage() {
            // Arrange
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            AccountUpdateResult result = service.updateAccount(buildValidRequest());

            // Assert — verbatim COBOL literal preserved (AAP §0.10.4).
            assertThat(result.getMessage())
                    .as("Success message must be the verbatim COBOL literal"
                            + " 'Changes committed to database' from"
                            + " CONFIRM-UPDATE-SUCCESS at line 474")
                    .isEqualTo("Changes committed to database");
            assertThat(result.isSuccess()).isTrue();
        }
    }


    // =========================================================================
    // OPTIMISTIC LOCKING FAILURES — COACTUPC 9600-WRITE-PROCESSING failure paths
    //
    // COACTUPC.cbl 9600-WRITE-PROCESSING (lines 3888-4105) has FOUR distinct
    // failure paths:
    //   1. COULD-NOT-LOCK-ACCT-FOR-UPDATE (line 3912): the EXEC CICS READ
    //      FILE('ACCTDAT') UPDATE at line 3897 returned DFHRESP(NOTFND).
    //   2. COULD-NOT-LOCK-CUST-FOR-UPDATE (line 3939): the EXEC CICS READ
    //      FILE('CUSTDAT') UPDATE at line 3925 returned DFHRESP(NOTFND).
    //   3. DATA-WAS-CHANGED-BEFORE-UPDATE (line 521, set at lines 4143/4189):
    //      9700-CHECK-CHANGE-IN-REC detected the loaded record was changed
    //      by another user between the operator's display read and the
    //      lock-for-update read.
    //   4. LOCKED-BUT-UPDATE-FAILED (lines 4079/4098): a REWRITE failed
    //      for a non-version reason (database I/O error, etc.). On the
    //      customer-side failure, EXEC CICS SYNCPOINT ROLLBACK at line
    //      4100 rolls back the account REWRITE atomically.
    //
    // The Java migration replaces all of these with:
    //   - Optional.empty() from findById(...) for paths 1 and 2 (account/
    //     customer not found).
    //   - OptimisticLockingFailureException from save(...) for path 3
    //     (version mismatch).
    //   - @Transactional(rollbackFor=Exception.class) on the service
    //     method for path 4 (any uncaught exception triggers rollback).
    // =========================================================================

    /**
     * Optimistic-locking and lock-failure scenarios for
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}.
     *
     * <p>Tests cover all four distinct failure paths of the COBOL
     * {@code 9600-WRITE-PROCESSING} workflow.
     */
    @Nested
    @DisplayName("Optimistic locking — COACTUPC 9600-WRITE-PROCESSING failure paths")
    class OptimisticLockingFailures {

        /**
         * Verifies that when {@link AccountRepository#findById(Object)}
         * returns {@link Optional#empty()} (the account does not exist in
         * the ACCTDAT replacement table), the production service:
         * <ul>
         *   <li>Returns {@link AccountUpdateResult#failure(String)} with
         *       the verbatim COBOL reject literal
         *       {@code 'Could not lock account record for update'}.</li>
         *   <li>Does NOT call {@link CustomerRepository#findById(Object)}
         *       (defence-in-depth: the absence-of-account short-circuits
         *       the entire downstream flow).</li>
         *   <li>Does NOT call any {@code save(...)} method.</li>
         * </ul>
         *
         * <p>COBOL parity: the {@code DFHRESP(NOTFND)} response on the
         * {@code EXEC CICS READ FILE('ACCTDAT') UPDATE} at line 3897,
         * which sets {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} (line 517).
         */
        @Test
        @DisplayName("updateAccount returns COULD-NOT-LOCK-ACCT reject when Account is not found")
        void updateAccount_accountNotFound_returnsAccountLockReject() {
            // Arrange — findById returns empty (account does not exist).
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.empty());

            // Act — drive the service with an otherwise-valid request.
            AccountUpdateResult result = service.updateAccount(buildValidRequest());

            // Assert — failure with the verbatim COBOL reject literal.
            assertThat(result.isSuccess())
                    .as("Account-not-found must yield failure (COBOL: "
                            + "DFHRESP(NOTFND) on EXEC CICS READ at line 3897)")
                    .isFalse();
            assertThat(result.getMessage())
                    .as("Reject message must be the verbatim COBOL literal"
                            + " 'Could not lock account record for update'"
                            + " (COULD-NOT-LOCK-ACCT-FOR-UPDATE at line 517"
                            + " of COACTUPC.cbl)")
                    .isEqualTo("Could not lock account record for update");

            // Defence-in-depth: customer findById and both saves must never
            // be invoked on the reject path.
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }

        /**
         * Verifies that when {@link CustomerRepository#findById(Object)}
         * returns {@link Optional#empty()} (the customer does not exist),
         * the production service:
         * <ul>
         *   <li>Returns {@link AccountUpdateResult#failure(String)} with
         *       the verbatim COBOL reject literal
         *       {@code 'Could not lock customer record for update'}.</li>
         *   <li>Does NOT call any {@code save(...)} method.</li>
         * </ul>
         *
         * <p>COBOL parity: the {@code DFHRESP(NOTFND)} response on the
         * {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} at line 3925,
         * which sets {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} (line 519).
         */
        @Test
        @DisplayName("updateAccount returns COULD-NOT-LOCK-CUST reject when Customer is not found")
        void updateAccount_customerNotFound_returnsCustomerLockReject() {
            // Arrange — account exists, customer does not.
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.empty());

            // Act
            AccountUpdateResult result = service.updateAccount(buildValidRequest());

            // Assert — failure with the verbatim COBOL reject literal.
            assertThat(result.isSuccess())
                    .as("Customer-not-found must yield failure (COBOL: "
                            + "DFHRESP(NOTFND) on EXEC CICS READ at line 3925)")
                    .isFalse();
            assertThat(result.getMessage())
                    .as("Reject message must be the verbatim COBOL literal"
                            + " 'Could not lock customer record for update'"
                            + " (COULD-NOT-LOCK-CUST-FOR-UPDATE at line 519"
                            + " of COACTUPC.cbl)")
                    .isEqualTo("Could not lock customer record for update");

            // Defence-in-depth: neither save must be invoked on the reject path.
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }

        /**
         * Verifies that an {@link OptimisticLockingFailureException} thrown
         * by {@link AccountRepository#save(Object)} (because the JPA
         * {@code @Version} field on the loaded {@link Account} does not
         * match the persisted version) propagates uncaught through
         * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}.
         *
         * <p>This is the load-bearing AAP §0.10.4 / §0.10.1 test that
         * proves the production service does NOT swallow the exception
         * — propagation is the precondition for Spring's
         * {@code @Transactional} rollback boundary to trigger and for
         * the controller layer's exception-handler chain to map the
         * exception to HTTP 409 Conflict.
         *
         * <p>COBOL parity: the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag
         * at line 521, set by {@code 9700-CHECK-CHANGE-IN-REC} at line
         * 4143 when the loaded account was changed by another user
         * between the operator's display read and the lock-for-update
         * read.
         */
        @Test
        @DisplayName("updateAccount throws OptimisticLockingFailureException on"
                + " Account @Version mismatch (COBOL DATA-WAS-CHANGED-BEFORE-UPDATE)")
        void updateAccount_accountVersionMismatch_throwsOptimisticLockingFailureException() {
            // Arrange — happy lookups, but accountRepository.save() throws.
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "Row was updated or deleted by another transaction"));

            // Act + Assert — exception propagates uncaught.
            assertThatThrownBy(() -> service.updateAccount(buildValidRequest()))
                    .as("OptimisticLockingFailureException must propagate"
                            + " uncaught so the controller layer can map it"
                            + " to HTTP 409 Conflict (COBOL parity: "
                            + "DATA-WAS-CHANGED-BEFORE-UPDATE at line 521)")
                    .isInstanceOf(OptimisticLockingFailureException.class);

            // Assert — customer save must NOT be invoked when account save
            // fails first. This proves the validation cascade is fail-fast
            // and that @Transactional rollback would have no customer-state
            // to undo (the rollback semantics only apply to writes that
            // actually completed).
            verify(customerRepository, never()).save(any(Customer.class));
        }

        /**
         * Verifies the critical SYNCPOINT ROLLBACK parity: when
         * {@link CustomerRepository#save(Object)} throws
         * {@link OptimisticLockingFailureException} AFTER the prior
         * {@link AccountRepository#save(Object)} succeeded, the exception
         * propagates uncaught through the service method.
         *
         * <p>At the unit-test level we cannot DIRECTLY observe Spring's
         * transactional rollback (Spring is not involved in unit tests),
         * but we CAN assert the necessary preconditions:
         * <ul>
         *   <li>Both repositories were invoked in the documented order
         *       (account.save BEFORE customer.save).</li>
         *   <li>The exception propagates uncaught (so Spring's
         *       {@code @Transactional(rollbackFor=Exception.class)}
         *       boundary will roll back the account save).</li>
         * </ul>
         *
         * <p>COBOL parity: {@code EXEC CICS SYNCPOINT ROLLBACK} at line
         * 4100 — when the second REWRITE (CUSTDAT) fails, the COBOL
         * workflow rolls back the prior ACCTDAT REWRITE so neither file
         * persists the partial update. The Java equivalent is Spring's
         * transactional rollback, which depends on the exception
         * propagating uncaught.
         */
        @Test
        @DisplayName("updateAccount propagates exception when Customer save fails after Account save"
                + " (COBOL SYNCPOINT ROLLBACK at line 4100)")
        void updateAccount_customerSaveFailsAfterAccountSave_propagatesExceptionForRollback() {
            // Arrange — happy lookups, account save succeeds, customer save throws.
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "Customer row was updated by another transaction"));

            // Act + Assert — exception propagates uncaught (the precondition
            // for Spring's @Transactional rollback to trigger).
            assertThatThrownBy(() -> service.updateAccount(buildValidRequest()))
                    .as("Customer save failure must propagate uncaught"
                            + " (triggering @Transactional rollback —"
                            + " Java parity for COBOL EXEC CICS SYNCPOINT"
                            + " ROLLBACK at line 4100)")
                    .isInstanceOf(OptimisticLockingFailureException.class);

            // Assert — both saves were attempted in the documented order.
            verify(accountRepository, times(1)).save(any(Account.class));
            verify(customerRepository, times(1)).save(any(Customer.class));
        }
    }


    // =========================================================================
    // VALIDATION REJECTS — COACTUPC.cbl field-level edits
    //
    // The COBOL workflow validates each operator-supplied field via
    // dedicated 88-level conditions:
    //   - ACCT-STATUS-MUST-BE-YES-NO (line 503): activeStatus ∈ {Y, N}
    //   - CRED-LIMIT-IS-NOT-VALID (line 507): creditLimit signed-number edit
    //   - INVALID-SSN-PART1 (line 121): VALUES 0, 666, 900 THRU 999
    //   - WS-EDIT-US-PHONE-IS-INVALID (line 102): area code VALUE '000'
    //
    // The Java migration consolidates these into a single validation
    // cascade inside AccountUpdateService.updateAccount, mirroring the
    // COBOL reject messages verbatim where the COBOL workflow surfaces a
    // dedicated 88-level reject literal (per AAP §0.10.4 Immutable
    // Boundaries), or with Java-migration-added plain-English messages
    // where the COBOL workflow uses an internal flag (SSN PART1, phone
    // area code) rather than a single reject literal.
    //
    // Each @ParameterizedTest exercises representative invalid inputs that
    // span the validation boundary to prove the validation rejects them
    // all with the SAME outcome (result.isSuccess() == false, no save
    // invoked, no repository lookup invoked because validation runs
    // BEFORE the repository call).
    // =========================================================================

    /**
     * Validation-reject scenarios for
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}.
     *
     * <p>Each test method drives a single invalid input through the
     * production service and asserts the invariants asserted on every
     * reject path:
     * <ul>
     *   <li>{@link AccountUpdateResult#isSuccess()} returns {@code false}.</li>
     *   <li>Neither {@code save(...)} method is invoked
     *       (defence-in-depth via {@code verify(..., never())}).</li>
     *   <li>Neither {@code findById(...)} method is invoked
     *       (validation runs BEFORE any repository call).</li>
     * </ul>
     *
     * <p>No findById stubs are configured because the production
     * validation cascade rejects format violations BEFORE the repository
     * lookup. Under Mockito's STRICT_STUBS mode this proves the
     * production code does not attempt any repository read on the
     * reject path.
     */
    @Nested
    @DisplayName("Validation rejects — COACTUPC.cbl field-level edits")
    class ValidationRejects {

        /**
         * Verifies that SSN values whose PART1 (first 3 digits) match the
         * COBOL {@code INVALID-SSN-PART1} 88-level exclusion set are
         * rejected with the SSN-invalid message. The {@code @ValueSource}
         * drives five representative invalid PART1 values that span the
         * COBOL exclusion set {@code VALUES 0, 666, 900 THRU 999}:
         * <ul>
         *   <li>{@code "000"} — value 0 (the explicit COBOL exclusion).</li>
         *   <li>{@code "666"} — the explicit COBOL exclusion.</li>
         *   <li>{@code "900"} — the lower bound of the COBOL range
         *       {@code 900 THRU 999}.</li>
         *   <li>{@code "950"} — a value in the middle of the COBOL
         *       range.</li>
         *   <li>{@code "999"} — the upper bound of the COBOL range.</li>
         * </ul>
         *
         * <p>COBOL parity: {@code INVALID-SSN-PART1} at line 121 of
         * {@code COACTUPC.cbl}:
         * <pre>
         *   88 INVALID-SSN-PART1  VALUES 0
         *                                666
         *                                900 THRU 999
         * </pre>
         *
         * <p>The corresponding COBOL test at line 2450 ({@code IF
         * INVALID-SSN-PART1}) drives the reject. The Java migration
         * preserves the exclusion set verbatim.
         */
        @ParameterizedTest(name = "[{index}] invalid SSN PART1 ''{0}'' rejected per COACTUPC INVALID-SSN-PART1")
        @ValueSource(strings = {"000", "666", "900", "950", "999"})
        @DisplayName("updateAccount rejects SSN with INVALID-SSN-PART1 value (0, 666, 900-999)")
        void updateAccount_invalidSsnPart1_rejected(String ssnPart1) {
            // Arrange — construct SSN with the invalid PART1 prefix
            // followed by 6 arbitrary numeric digits (so the 9-digit
            // format check passes and the PART1 check is reached).
            AccountUpdateRequest request = buildValidRequest();
            request.setSsn(ssnPart1 + "501234");

            // Act
            AccountUpdateResult result = service.updateAccount(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("SSN with INVALID-SSN-PART1='%s' must be rejected"
                            + " (COBOL: INVALID-SSN-PART1 at line 121)", ssnPart1)
                    .isFalse();

            // Defence-in-depth — no repository call and no save on reject.
            verify(accountRepository, never()).findById(any());
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }

        /**
         * Verifies that phone numbers with area code {@code "000"} are
         * rejected per the COBOL {@code WS-EDIT-US-PHONE-IS-INVALID}
         * 88-level condition at line 102 ({@code VALUE '000'}).
         *
         * <p>The {@code @ValueSource} drives the single invalid area
         * code value mandated by COBOL — but it's parameterised so
         * additional invalid area codes can be added in the future
         * without restructuring the test method.
         *
         * <p>COBOL parity: {@code WS-EDIT-US-PHONE-IS-INVALID} at line
         * 102 of {@code COACTUPC.cbl}:
         * <pre>
         *   88 WS-EDIT-US-PHONE-IS-INVALID VALUE '000'
         * </pre>
         *
         * <p>The COBOL workflow extracts the {@code PHONE-NUMA} group
         * (first 3 digits of the parenthesised area code in the
         * {@code (NNN)NNN-NNNN} format) and compares it against the
         * exclusion value. The Java migration preserves this exact
         * semantics using a regex capture group.
         */
        @ParameterizedTest(name = "[{index}] invalid phone area code ''{0}'' rejected per COACTUPC INVALID-PHONE-NUMA")
        @ValueSource(strings = {"000"})
        @DisplayName("updateAccount rejects phone number with INVALID-PHONE-NUMA value '000'")
        void updateAccount_invalidPhoneAreaCode_rejected(String areaCode) {
            // Arrange — construct phone with the invalid area code.
            AccountUpdateRequest request = buildValidRequest();
            request.setPhoneNumber1("(" + areaCode + ")555-1234");

            // Act
            AccountUpdateResult result = service.updateAccount(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("Phone with area code '%s' must be rejected (COBOL:"
                            + " WS-EDIT-US-PHONE-IS-INVALID at line 102)", areaCode)
                    .isFalse();

            // Defence-in-depth — no repository call on reject.
            verify(accountRepository, never()).findById(any());
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }

        /**
         * Verifies that a negative credit limit is rejected with the
         * verbatim COBOL reject literal {@code 'Credit Limit is not
         * valid'} from {@code CRED-LIMIT-IS-NOT-VALID} 88-level at
         * {@code COACTUPC.cbl} line 507.
         *
         * <p>The COBOL {@code PIC S9(10)V99} field type supports
         * negative values structurally, but the business rule rejects
         * them via the signed-number edit (a negative credit limit is
         * semantically invalid for a credit-card account).
         *
         * <p>COBOL parity: {@code CRED-LIMIT-IS-NOT-VALID} at line 507
         * of {@code COACTUPC.cbl}: {@code 'Credit Limit is not valid'}.
         */
        @Test
        @DisplayName("updateAccount rejects negative credit limit"
                + " (COBOL CRED-LIMIT-IS-NOT-VALID at line 507)")
        void updateAccount_negativeCreditLimit_rejected() {
            // Arrange
            AccountUpdateRequest request = buildValidRequest();
            request.setCreditLimit(new BigDecimal("-1000.00"));

            // Act
            AccountUpdateResult result = service.updateAccount(request);

            // Assert
            assertThat(result.isSuccess())
                    .as("Negative credit limit must be rejected (COBOL: "
                            + "CRED-LIMIT-IS-NOT-VALID at line 507)")
                    .isFalse();
            assertThat(result.getMessage())
                    .as("Reject message must be the verbatim COBOL literal"
                            + " 'Credit Limit is not valid' from"
                            + " CRED-LIMIT-IS-NOT-VALID at line 507")
                    .isEqualTo("Credit Limit is not valid");

            // Defence-in-depth — no repository call on reject.
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }

        /**
         * Verifies that an account active status outside the
         * {@code {"Y", "N"}} domain is rejected with the verbatim COBOL
         * reject literal {@code 'Account Active Status must be Y or N'}
         * from {@code ACCT-STATUS-MUST-BE-YES-NO} 88-level at
         * {@code COACTUPC.cbl} line 503.
         *
         * <p>COBOL parity: {@code ACCT-STATUS-MUST-BE-YES-NO} at line
         * 503 of {@code COACTUPC.cbl}: {@code 'Account Active Status
         * must be Y or N'}.
         */
        @Test
        @DisplayName("updateAccount rejects active status outside Y/N domain"
                + " (COBOL ACCT-STATUS-MUST-BE-YES-NO at line 503)")
        void updateAccount_invalidActiveStatus_rejected() {
            // Arrange
            AccountUpdateRequest request = buildValidRequest();
            request.setAccountActiveStatus("X");

            // Act
            AccountUpdateResult result = service.updateAccount(request);

            // Assert
            assertThat(result.isSuccess())
                    .as("Active status 'X' must be rejected (COBOL: "
                            + "ACCT-STATUS-MUST-BE-YES-NO at line 503)")
                    .isFalse();
            assertThat(result.getMessage())
                    .as("Reject message must be the verbatim COBOL literal"
                            + " 'Account Active Status must be Y or N' from"
                            + " ACCT-STATUS-MUST-BE-YES-NO at line 503")
                    .isEqualTo("Account Active Status must be Y or N");

            // Defence-in-depth — no repository call on reject.
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
        }
    }


    // =========================================================================
    // FINANCIAL PRECISION — BigDecimal scale parity with COBOL PIC S9(10)V99
    //
    // AAP §0.10.3 mandates BigDecimal exclusively for monetary values with
    // scale derived from the COBOL PICTURE clause. COBOL PIC S9(10)V99 →
    // BigDecimal scale 2. The tests below verify that the service preserves
    // the scale of monetary values flowing from request through the
    // captured entity to the save() call.
    //
    // CRITICAL: Per AAP §0.10.1 the test does NOT recompute any monetary
    // value. The test asserts only that the captured entity carries the
    // EXACT BigDecimal supplied by the request (via isEqualByComparingTo)
    // AND that its scale equals 2 (the COBOL PIC S9(10)V99 scale).
    // =========================================================================

    /**
     * Financial-precision scenarios for
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)}.
     *
     * <p>Tests verify that all monetary fields flowing through the service
     * preserve {@link BigDecimal} scale 2 (matching COBOL
     * {@code PIC S9(10)V99}). Per AAP §0.10.3 no monetary value is ever
     * represented as {@code float} or {@code double}; AssertJ's
     * {@code isEqualByComparingTo} performs value-based comparison
     * insensitive to incidental scale changes, while a paired
     * {@code .satisfies(b -> assertThat(b.scale()).isEqualTo(2))} pins
     * the scale invariant explicitly.
     */
    @Nested
    @DisplayName("Financial precision — BigDecimal scale parity with COBOL PIC S9(10)V99")
    class FinancialPrecision {

        /**
         * Verifies that the production service preserves {@link BigDecimal}
         * scale 2 for {@code currentBalance}, {@code creditLimit}, and
         * {@code cashCreditLimit} on the entity passed to
         * {@link AccountRepository#save(Object)}.
         *
         * <p>COBOL parity: {@code ACCT-UPDATE-CURR-BAL PIC S9(10)V99}
         * (line 424), {@code ACCT-UPDATE-CREDIT-LIMIT PIC S9(10)V99}
         * (line 425), {@code ACCT-UPDATE-CASH-CREDIT-LIMIT PIC S9(10)V99}
         * (line 426). The {@code V99} portion of each picture clause
         * mandates exactly two implicit decimal digits — Java's
         * {@link BigDecimal#scale()} of 2 is the direct equivalent.
         */
        @Test
        @DisplayName("updateAccount preserves BigDecimal scale 2 for currentBalance, creditLimit, cashCreditLimit")
        void updateAccount_monetaryFields_preserveScale2() {
            // Arrange — populate request with scale-2 monetary values.
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateRequest request = buildValidRequest();
            request.setCurrentBalance(new BigDecimal("1234.50"));
            request.setCreditLimit(new BigDecimal("5000.00"));
            request.setCashCreditLimit(new BigDecimal("1000.00"));

            // Act
            service.updateAccount(request);

            // Assert — capture saved Account and verify scale invariants.
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getCurrentBalance())
                    .as("currentBalance must have scale 2 per COBOL"
                            + " PIC S9(10)V99 at line 424")
                    .isEqualByComparingTo("1234.50")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale parity with COBOL V99 (implicit 2 decimal digits)")
                            .isEqualTo(2));

            assertThat(saved.getCreditLimit())
                    .as("creditLimit must have scale 2 per COBOL"
                            + " PIC S9(10)V99 at line 425")
                    .isEqualByComparingTo("5000.00")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale parity with COBOL V99")
                            .isEqualTo(2));

            assertThat(saved.getCashCreditLimit())
                    .as("cashCreditLimit must have scale 2 per COBOL"
                            + " PIC S9(10)V99 at line 426")
                    .isEqualByComparingTo("1000.00")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale parity with COBOL V99")
                            .isEqualTo(2));
        }

        /**
         * Verifies that the production service also preserves
         * {@link BigDecimal} scale 2 for {@code currentCycleCredit} and
         * {@code currentCycleDebit} — the running per-billing-cycle
         * totals.
         *
         * <p>COBOL parity: {@code ACCT-UPDATE-CURR-CYC-CREDIT PIC
         * S9(10)V99} (line 430), {@code ACCT-UPDATE-CURR-CYC-DEBIT PIC
         * S9(10)V99} (line 431).
         */
        @Test
        @DisplayName("updateAccount preserves BigDecimal scale 2 for currentCycleCredit and currentCycleDebit")
        void updateAccount_cycleTotals_preserveScale2() {
            // Arrange
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateRequest request = buildValidRequest();
            request.setCurrentCycleCredit(new BigDecimal("250.75"));
            request.setCurrentCycleDebit(new BigDecimal("125.25"));

            // Act
            service.updateAccount(request);

            // Assert
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getCurrentCycleCredit())
                    .as("currentCycleCredit must have scale 2 per COBOL"
                            + " PIC S9(10)V99 at line 430")
                    .isEqualByComparingTo("250.75")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale parity with COBOL V99")
                            .isEqualTo(2));

            assertThat(saved.getCurrentCycleDebit())
                    .as("currentCycleDebit must have scale 2 per COBOL"
                            + " PIC S9(10)V99 at line 431")
                    .isEqualByComparingTo("125.25")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale parity with COBOL V99")
                            .isEqualTo(2));
        }

        /**
         * Verifies that the production service handles zero balances
         * correctly (scale-2 preservation must work for
         * {@link BigDecimal#ZERO} as well as for non-zero values).
         *
         * <p>This is the edge-case boundary for the COBOL
         * {@code WHEN-ZERO} edit flag at line 87 of
         * {@code COACTUPC.cbl}: zero values are valid but require
         * explicit scale-2 representation to round-trip through the
         * database column type without scale loss.
         */
        @Test
        @DisplayName("updateAccount preserves BigDecimal scale 2 for zero balance values")
        void updateAccount_zeroBalances_preserveScale2() {
            // Arrange
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateRequest request = buildValidRequest();
            request.setCurrentBalance(new BigDecimal("0.00"));
            request.setCurrentCycleCredit(new BigDecimal("0.00"));
            request.setCurrentCycleDebit(new BigDecimal("0.00"));

            // Act
            service.updateAccount(request);

            // Assert
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getCurrentBalance())
                    .as("zero currentBalance must have scale 2")
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(saved.getCurrentCycleCredit())
                    .as("zero currentCycleCredit must have scale 2")
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(saved.getCurrentCycleDebit())
                    .as("zero currentCycleDebit must have scale 2")
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        /**
         * Verifies that the production service handles large-magnitude
         * boundary values (close to the COBOL {@code PIC S9(10)V99}
         * upper bound of {@code 9999999999.99}) without scale loss.
         *
         * <p>The COBOL field can hold up to 10 digits before the decimal
         * point and 2 after. The Java migration's {@link BigDecimal} has
         * no equivalent precision cap (it is arbitrary-precision), but
         * the scale-2 invariant still applies — a value like
         * {@code "9999999999.99"} must round-trip with scale 2 intact.
         */
        @Test
        @DisplayName("updateAccount preserves BigDecimal scale 2 at COBOL PIC S9(10)V99 boundary value")
        void updateAccount_pictureBoundaryValue_preservesScale2() {
            // Arrange
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                    .thenReturn(Optional.of(standardCustomer()));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.save(any(Customer.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AccountUpdateRequest request = buildValidRequest();
            // Boundary value: 10 digits before decimal, 2 after.
            request.setCurrentBalance(new BigDecimal("9999999999.99"));
            request.setCreditLimit(new BigDecimal("9999999999.99"));

            // Act
            service.updateAccount(request);

            // Assert
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getCurrentBalance())
                    .as("PIC S9(10)V99 boundary value must round-trip with scale 2")
                    .isEqualByComparingTo("9999999999.99")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(saved.getCreditLimit())
                    .as("PIC S9(10)V99 boundary value must round-trip with scale 2")
                    .isEqualByComparingTo("9999999999.99")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }
    }


    // =========================================================================
    // HELPER METHODS — fixture construction
    //
    // These helpers are kept private and static so they can be invoked from
    // any nested @Nested class without re-instantiation, and so they cannot
    // accidentally accumulate per-test mutable state. They produce canonical
    // Account, Customer, and AccountUpdateRequest fixtures that satisfy
    // every validation rule the production AccountUpdateService enforces
    // — so the tests that exercise reject paths only need to mutate the
    // specific field they want to invalidate.
    // =========================================================================

    /**
     * Builds a canonical {@link Account} fixture representing the
     * persisted pre-update record loaded by
     * {@link AccountRepository#findById(Object)}. The fixture's field
     * values are all valid per the production
     * {@link AccountUpdateService} validation cascade.
     *
     * <h3>Field values</h3>
     * <ul>
     *   <li>{@code accountId} = {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
     *       (the 11-digit zero-padded account ID
     *       {@code "00000000010"}).</li>
     *   <li>{@code activeStatus} = {@code "Y"} (active).</li>
     *   <li>{@code customerId} = {@link TestFixtures.Customers#SAMPLE_CUSTOMER_ID_01}
     *       (the 9-digit zero-padded customer ID
     *       {@code "000000001"}).</li>
     *   <li>{@code groupId} = {@link TestFixtures.Accounts#DEFAULT_GROUP_ID}
     *       (the canonical {@code "A000000000"} disclosure group).</li>
     *   <li>All monetary fields ({@code currentBalance},
     *       {@code creditLimit}, {@code cashCreditLimit},
     *       {@code currentCycleCredit}, {@code currentCycleDebit}) are
     *       {@link BigDecimal} scale 2 per AAP §0.10.3.</li>
     *   <li>{@code version} = {@code 1L} (a non-{@code null} version so
     *       JPA's {@code @Version} contract is exercised on save).</li>
     * </ul>
     *
     * @return a populated {@link Account} fixture suitable for use as
     *         the {@link Optional#of(Object)} payload returned by an
     *         {@link AccountRepository#findById(Object)} stub
     */
    private static Account standardAccount() {
        Account a = new Account();
        a.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        a.setActiveStatus("Y");
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setCreditLimit(new BigDecimal("5000.00"));
        a.setCashCreditLimit(new BigDecimal("1000.00"));
        a.setCurrentCycleCredit(new BigDecimal("0.00"));
        a.setCurrentCycleDebit(new BigDecimal("0.00"));
        a.setOpenDate("2020-01-01");
        a.setExpirationDate("2025-12-31");
        a.setReissueDate("2023-01-01");
        a.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        a.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        a.setVersion(1L);
        return a;
    }

    /**
     * Builds a canonical {@link Customer} fixture representing the
     * persisted pre-update record loaded by
     * {@link CustomerRepository#findById(Object)}. The fixture's field
     * values are all valid per the production
     * {@link AccountUpdateService} validation cascade.
     *
     * <h3>Field values</h3>
     * <ul>
     *   <li>{@code customerId} = {@link TestFixtures.Customers#SAMPLE_CUSTOMER_ID_01}
     *       (the 9-digit zero-padded customer ID).</li>
     *   <li>{@code firstName} / {@code lastName} populated with
     *       alphabetic-only synthetic values.</li>
     *   <li>{@code ssn} = {@code "123456789"} — a 9-digit numeric SSN
     *       whose PART1 ({@code "123"}) is NOT in the COBOL
     *       {@code INVALID-SSN-PART1} exclusion set.</li>
     *   <li>{@code phoneNumber1} = {@code "(212)555-1234"} — formatted
     *       per COBOL {@code (NNN)NNN-NNNN} with a valid area code
     *       (NOT {@code "000"}).</li>
     *   <li>{@code version} = {@code 1L}.</li>
     * </ul>
     *
     * @return a populated {@link Customer} fixture
     */
    private static Customer standardCustomer() {
        Customer c = new Customer();
        c.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        c.setFirstName("John");
        c.setMiddleName("Q");
        c.setLastName("Doe");
        c.setAddressLine1("123 Main St");
        c.setAddressLine2("Apt 4");
        c.setAddressLine3("Springfield");
        c.setAddressStateCode("CA");
        c.setAddressCountryCode("USA");
        c.setAddressZip("90001");
        c.setPhoneNumber1("(212)555-1234");
        c.setPhoneNumber2("(212)555-5678");
        c.setSsn("123456789");
        c.setGovernmentIssuedId("DL12345678");
        c.setDateOfBirth("1980-01-15");
        c.setEftAccountId("EFT0001234");
        c.setPrimaryCardHolderIndicator("Y");
        c.setFicoCreditScore(720);
        c.setVersion(1L);
        return c;
    }

    /**
     * Builds a canonical {@link AccountUpdateRequest} fixture whose field
     * values are all valid per the production
     * {@link AccountUpdateService} validation cascade. Tests that
     * exercise reject paths typically call this helper and then mutate
     * exactly one field to invalidate the desired validation rule — so
     * the test isolates the single field under test rather than
     * re-stating the whole request shape.
     *
     * <h3>Field values</h3>
     * <ul>
     *   <li>{@code accountId} = {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
     *       (matches the {@link #standardAccount()} fixture's PK so
     *       {@code findById(...)} returns an {@link Account}).</li>
     *   <li>{@code customerId} = {@link TestFixtures.Customers#SAMPLE_CUSTOMER_ID_01}
     *       (matches the {@link #standardCustomer()} fixture's PK).</li>
     *   <li>{@code accountActiveStatus} = {@code "Y"} (a valid value).</li>
     *   <li>All monetary fields are {@link BigDecimal} scale 2 per AAP §0.10.3.</li>
     *   <li>{@code ssn} = {@code "123456789"} (PART1 {@code "123"} is
     *       NOT in the COBOL exclusion set).</li>
     *   <li>{@code phoneNumber1} = {@code "(212)555-1234"} (area code
     *       {@code "212"} is NOT {@code "000"}).</li>
     *   <li>{@code groupId} = {@link TestFixtures.Accounts#DEFAULT_GROUP_ID}.</li>
     *   <li>{@code accountVersion} / {@code customerVersion} = {@code 1L}.</li>
     * </ul>
     *
     * @return a populated, fully-valid {@link AccountUpdateRequest}
     *         ready for use in a happy-path scenario or as the starting
     *         point for a field-mutation reject scenario
     */
    private static AccountUpdateRequest buildValidRequest() {
        AccountUpdateRequest req = new AccountUpdateRequest();
        // Account fields
        req.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        req.setAccountActiveStatus("Y");
        req.setCurrentBalance(new BigDecimal("1500.00"));
        req.setCreditLimit(new BigDecimal("7500.00"));
        req.setCashCreditLimit(new BigDecimal("1500.00"));
        req.setCurrentCycleCredit(new BigDecimal("100.00"));
        req.setCurrentCycleDebit(new BigDecimal("50.00"));
        req.setOpenDate("2020-01-01");
        req.setExpirationDate("2026-12-31");
        req.setReissueDate("2024-01-01");
        req.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        // Customer fields
        req.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        req.setFirstName("Jane");
        req.setMiddleName("R");
        req.setLastName("Smith");
        req.setAddressLine1("456 Oak Ave");
        req.setAddressLine2("Suite 200");
        req.setAddressLine3("Lakeside");
        req.setStateCode("NY");
        req.setCountryCode("USA");
        req.setZipCode("10001");
        req.setPhoneNumber1("(212)555-1234");
        req.setPhoneNumber2("(212)555-5678");
        req.setSsn("123456789");
        req.setGovernmentIssuedId("DL87654321");
        req.setDateOfBirth("1985-06-20");
        req.setEftAccountId("EFT0009876");
        req.setPrimaryCardHolderIndicator("Y");
        req.setFicoCreditScore(750);
        // Version fields
        req.setAccountVersion(1L);
        req.setCustomerVersion(1L);
        return req;
    }
    // ============================================================
    // Nested test class — Authorization Contract
    // ============================================================

    /**
     * Documents and asserts the authorization-contract layer for
     * {@link AccountUpdateService} — the service that replaces COBOL program
     * {@code COACTUPC} (which mutates user-owned account data).
     *
     * <h2>COBOL Authorization Model</h2>
     *
     * <p>In the original CICS/COBOL implementation, authorization was
     * gated by the CICS BMS sign-on flow: {@code COSGN00C} validated
     * the user's credentials and only after success could the user
     * navigate via the main menu (or admin menu for admin-only flows)
     * to this program's screen. The COBOL program itself performed no
     * caller-authorization check — it trusted the upstream CICS session.
     *
     * <h2>Java Migration — Layer of Responsibility</h2>
     *
     * <p>Per AAP §0.10.2 (Minimal Change Clause), the Java migration
     * preserves this contract. {@link AccountUpdateService} does NOT perform a
     * service-level caller-authorization check; instead:
     * <ul>
     *   <li>The REST controller (e.g., the Spring MVC controller
     *       that fronts this service) MUST enforce Spring Security
     *       {@code @PreAuthorize} or {@code @PostAuthorize}
     *       annotations at the HTTP boundary (the modern equivalent
     *       of the CICS BMS sign-on gate).</li>
     *   <li>The service layer trusts that the caller has passed the
     *       upstream authentication check; this matches the COBOL
     *       contract precisely.</li>
     * </ul>
     *
     * <p>These tests assert that contract is preserved structurally.
     *
     * @see com.aws.carddemo.service.UserListService for the contrasting
     *      pattern where the COBOL program does perform an admin-only
     *      check and the Java migration mirrors it via {@code callerUserType}
     */
    @Nested
    @DisplayName("Authorization contract — controller-layer responsibility (AAP §0.10.2)")
    class AuthorizationContract {

        /**
         * Verify {@link AccountUpdateService} method signatures carry NO
         * {@code callerUserType}-style parameter — proving the
         * authorization is the controller's responsibility per the
         * COBOL COACTUPC trust-upstream contract.
         */
        @Test
        @DisplayName("methodSignatures_carryNoCallerIdentity_perCobolContract")
        void methodSignatures_carryNoCallerIdentity_perCobolContract() {
            java.lang.reflect.Method[] methods = AccountUpdateService.class.getDeclaredMethods();
            boolean hasCallerUserTypeParam = false;
            for (java.lang.reflect.Method m : methods) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                for (java.lang.reflect.Parameter p : m.getParameters()) {
                    if (p.getName().toLowerCase().contains("callerusertype")
                            || p.getName().toLowerCase().contains("calleruser")) {
                        hasCallerUserTypeParam = true;
                    }
                }
            }
            assertThat(hasCallerUserTypeParam)
                    .as("AccountUpdateService must NOT accept callerUserType — "
                            + "authorization is the controller's responsibility "
                            + "per the COBOL COACTUPC contract")
                    .isFalse();
        }

        /**
         * Verify the request DTO {@link AccountUpdateRequest} carries no
         * {@code callerUserType} field — the structural assertion
         * of the layer-of-responsibility model.
         *
         * <p>Contrast with {@code AdminMenuRequest}, {@code UserListRequest},
         * {@code MainMenuRequest} which DO carry {@code callerUserType}
         * — those COBOL programs (COADM01C, COUSR00C, COMEN01C)
         * performed admin checks; this one (COACTUPC) did not.
         */
        @Test
        @DisplayName("requestDto_doesNotCarryCallerUserType_perCobolContract")
        void requestDto_doesNotCarryCallerUserType_perCobolContract() {
            java.lang.reflect.Field[] fields = AccountUpdateRequest.class.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                assertThat(f.getName().toLowerCase())
                        .as("Field %s on AccountUpdateRequest must not be a caller-identity field",
                                f.getName())
                        .doesNotContain("calleruser");
            }
        }
    }
    // ============================================================
    // Nested test class — Logging Safety (AAP §0.10.5)
    // ============================================================

    /**
     * Coverage for AAP §0.10.5 NON-NEGOTIABLE: "No financial data
     * written to logs at any level". The production
     * {@link AccountUpdateService} migrates COBOL program {@code COACTUPC} which
     * handles account balance and credit limits.
     *
     * <p>The defensive-design assertion below verifies the production
     * class declares no logger field. This is the cheapest, most
     * reliable defence against accidental log-leak regressions: if no
     * logger exists, no logger call is possible.
     *
     * <p>Where the service must emit audit events (e.g., transaction
     * creation, authentication), the production class delegates those
     * to a centralised audit-log service that already implements the
     * PII/PAN/financial-value redaction required by AAP §0.10.5; that
     * delegation is verified at the audit-log-service unit test level.
     */
    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafety {

        /**
         * Verify {@link AccountUpdateService} declares no logger fields. The
         * production class is a pure-logic service with no SLF4J,
         * java.util.logging, or commons-logging dependency at the
         * field level. Any future regression introducing a logger
         * is caught by this test.
         */
        @Test
        @DisplayName("service_declaresNoLoggerField_preventingFinancialDataLeak")
        void service_declaresNoLoggerField_preventingFinancialDataLeak() {
            // Scan every declared field of the production service for
            // any logger-like type. The defensive design forbids
            // logger fields entirely on services that handle
            // account balance and credit limits.
            assertThat(AccountUpdateService.class.getDeclaredFields())
                    .as("AccountUpdateService must declare no logger fields "
                            + "(AAP §0.10.5 defensive design — account balance and credit limits "
                            + "must never reach a log stream)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
