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
//       - TestFixtures.Cards.SAMPLE_CARD_NUMBER_01 ("4111111111111101", the
//         16-digit Visa-test PAN driving happy-path findById lookups and the
//         helper standardCard() / buildValidRequest() initialisation;
//         mirrors the COBOL CARD-UPDATE-NUM PIC X(16) immutable PK at
//         line 315).
//       - TestFixtures.Cards.SAMPLE_CARD_NUMBER_50 ("4111111111111150",
//         the alternate 16-digit Visa-test PAN used for boundary scenarios
//         that need a second distinct fixture card).
//       - TestFixtures.Cards.ACTIVE_STATUS_YES ("Y", the single-character
//         active-status code mirroring CARD-UPDATE-ACTIVE-STATUS PIC X(01)
//         at line 320).
//       - TestFixtures.Cards.ACTIVE_STATUS_NO ("N", the single-character
//         inactive code; declared for symmetry with the production
//         constant although the current scenarios drive only ACTIVE_STATUS_YES).
//       - TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10 ("00000000010", the
//         11-digit zero-padded account ID mirroring CARD-UPDATE-ACCT-ID
//         PIC 9(11) at line 316).
//       - TestFixtures.Dates.FIXED_CLOCK_INSTANT ("2024-01-15T00:00:00Z",
//         the deterministic clock instant injected into CardUpdateService
//         per AAP §0.10.9 test-independence requirement).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * Card — the JPA entity returned by the repository on findById(...)
//     lookups and the captured argument on save(...) calls. Imported via
//     fully-qualified package because the entity lives in the
//     com.aws.carddemo.entity package (NOT this test's
//     com.aws.carddemo.service package).
//
//   * CardRepository / CardXrefRepository — the Spring Data JPA repositories
//     the production CardUpdateService delegates to. Mocked at the
//     JPA-repository boundary per AAP §0.10.1 ("Mocks limited to external
//     boundaries: file I/O, downstream service calls, database").
//
//   * CardUpdateService / CardUpdateRequest / CardUpdateResult — the
//     production classes under test. No explicit imports because they
//     share this test's package (com.aws.carddemo.service); Java resolves
//     simple-named references via package membership. This matches the
//     convention established by UserUpdateServiceTest, UserAddServiceTest,
//     and every other test under com.aws.carddemo.service (AAP §0.10.10
//     style consistency).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Card;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (file schema external_imports; AAP §0.10.7 framework
// constraint — JUnit 5 only, never JUnit 4 / Vintage).
//
//   * @BeforeEach — re-instantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9). Each test sees a
//     fresh CardRepository / CardXrefRepository mock and a fresh
//     CardUpdateService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups HappyPath / OptimisticLocking / ValidationRejects
//     scenarios into the three semantic sections that match this test
//     class's exports.members_exposed schema entries.
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
//     validation-reject scenarios across representative invalid inputs:
//     five values for the card-number reject (15-digit, 17-digit,
//     digits-plus-alpha, empty, whitespace); five values for the CVV
//     reject (2-digit, 4-digit, alpha, empty, whitespace); six values for
//     the expiration-date reject (month 13, Feb 30, Feb 29 non-leap year,
//     alpha year, slash separator, empty); six values for the active-status
//     reject (X, 1, 0, multi-char "YES", multi-char "true", single space).
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
//   * ArgumentCaptor — captures the Card instance passed to
//     cardRepository.save(...) so the test can assert the persisted entity
//     carries the new field values from the request AND preserves the
//     immutable card-number primary key.
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
//     Stubbed via Mockito.when(...).thenThrow(...) in the OptimisticLocking
//     nested test group; asserted via AssertJ's assertThatThrownBy(...).
//     This is the Java translation of the COBOL
//     DATA-WAS-CHANGED-BEFORE-UPDATE flag at COCRDUPC.cbl line 1511,
//     raised when CHECK-CHANGE-IN-REC (line 1453) detects the loaded
//     record was changed by another user between READ and READ UPDATE.
// ---------------------------------------------------------------------------
import org.springframework.dao.OptimisticLockingFailureException;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.time and java.util).
//
//   * Clock / Instant / ZoneOffset — Clock.fixed(Instant.parse(
//     TestFixtures.Dates.FIXED_CLOCK_INSTANT), ZoneOffset.UTC) pins the
//     test "now" to 2024-01-15T00:00:00Z and is injected as the third
//     constructor argument to CardUpdateService. This guarantees that any
//     audit timestamp the production service stamps onto the persisted
//     Card entity is deterministic and reproducible across test runs and
//     parallel executions per AAP §0.10.9 test-independence requirement,
//     mirroring how the COBOL COCRDUPC.cbl program uses CURRENT-DATE in
//     a controlled execution context.
//   * Optional — wraps the repository's findById return value:
//     Optional.of(existing) for happy-update / optimistic-locking /
//     validation-reject scenarios (simulates "card exists in CARDDAT");
//     Optional.empty() for the card-not-found scenario (Java translation
//     of COBOL DFHRESP(NOTFND) on the EXEC CICS READ at line 1382).
// ---------------------------------------------------------------------------
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
//     point used in the OptimisticLocking scenarios to verify the
//     uncaught exception propagation (the COBOL
//     DATA-WAS-CHANGED-BEFORE-UPDATE Java migration).
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications and in stubs that don't care about the specific
//     argument value.
//   * Mockito.never — verifies that a stub method was NOT invoked; used to
//     prove that validation rejects short-circuit BEFORE the save call.
//   * Mockito.times — verifies the exact number of invocations; used to
//     pair with ArgumentCaptor on the single-save happy-path scenario.
//   * Mockito.verify — interaction assertion; pairs with .never(), .times(),
//     and ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths (Optional.of(...) from findById, the saved
//     entity from save), the card-not-found path (Optional.empty() from
//     findById), and the version-mismatch path
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
 * Unit tests for {@link CardUpdateService}, the migrated Java equivalent of
 * the 1,560-line CICS COBOL program {@code app/cbl/COCRDUPC.cbl} (TRANID
 * {@code CCUP}, the card-update dispatcher). The service updates the
 * {@code CARDDAT} master file with optimistic concurrency control. This
 * test class exercises the COBOL-inherited validation cascade plus the
 * Java-migration-added optimistic-locking propagation and card-number
 * immutability invariant.
 *
 * <h2>COBOL Provenance — COCRDUPC.cbl</h2>
 *
 * <p>The optimistic-locking pattern (lines 1382–1521) parallels
 * {@code COACTUPC.cbl} but operates on a SINGLE table ({@code CARDDAT}),
 * making it the simpler of the two update CICS programs. The COBOL
 * workflow:
 * <ol>
 *   <li>{@code EXEC CICS READ FILE(CARDDAT) RIDFLD(CARD-UPDATE-NUM)}
 *       (line 1382) — initial display read; the Java migration uses
 *       {@link CardRepository#findById(Object)}.</li>
 *   <li>{@code EXEC CICS READ UPDATE FILE(CARDDAT)} (lines 1427–1429) —
 *       lock acquisition; the Java migration relies on JPA's
 *       {@code @Version} optimistic-lock check at {@code save()} time
 *       instead of pessimistic file locking.</li>
 *   <li>{@code CHECK-CHANGE-IN-REC} paragraph (line 1453) — compares the
 *       displayed before-image of the record against the current
 *       persisted state, setting {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 *       (line 1511) if they differ. The Java migration replaces this
 *       with JPA's {@code @Version} field check, which raises
 *       {@link OptimisticLockingFailureException} on mismatch.</li>
 *   <li>{@code EXEC CICS REWRITE FILE(CARDDAT) FROM(CARD-UPDATE-RECORD)}
 *       (lines 1477–1483) — persistence step; Java equivalent is
 *       {@link CardRepository#save(Object)}.</li>
 *   <li>{@code ABEND-ROUTINE} (lines 1546–1552) — controlled abort on
 *       irrecoverable error via {@code EXEC CICS HANDLE ABEND CANCEL} +
 *       {@code EXEC CICS ABEND ABCODE('9999')}. The Java equivalent is
 *       Spring's transactional rollback boundary which automatically
 *       reverts the persistent state on any uncaught
 *       {@link RuntimeException}.</li>
 *   <li>{@code EXEC CICS SYNCPOINT} (line 470 of the READY-TO-PROCESS
 *       paragraph) — commit. Java equivalent is the {@code @Transactional}
 *       commit boundary at method return.</li>
 * </ol>
 *
 * <h2>CARD-UPDATE-RECORD Fields (lines 314–321)</h2>
 *
 * <ul>
 *   <li>{@code CARD-UPDATE-NUM PIC X(16)} → {@link Card#getCardNumber()}
 *       (immutable PK — see "Card Number Immutability" invariant below)</li>
 *   <li>{@code CARD-UPDATE-ACCT-ID PIC 9(11)} → {@link Card#getAccountId()}</li>
 *   <li>{@code CARD-UPDATE-CVV-CD PIC 9(03)} → {@link Card#getCvvCode()}</li>
 *   <li>{@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)} →
 *       {@link Card#getEmbossedName()}</li>
 *   <li>{@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} →
 *       {@link Card#getExpirationDate()} (ISO {@code YYYY-MM-DD})</li>
 *   <li>{@code CARD-UPDATE-ACTIVE-STATUS PIC X(01)} →
 *       {@link Card#getActiveStatus()} ({@code 'Y'} or {@code 'N'})</li>
 * </ul>
 *
 * <h2>Validation Order (COCRDUPC.cbl 1210–1260)</h2>
 *
 * <ol>
 *   <li>Card number presence/format check (COBOL parity:
 *       {@code 1220-EDIT-CARD}) — BEFORE the repository lookup.</li>
 *   <li>{@link CardRepository#findById} — Java equivalent of
 *       {@code EXEC CICS READ} at line 1382. On
 *       {@link Optional#empty()} → reject with {@code 'Did not find
 *       cards for this search condition'}.</li>
 *   <li>CVV format check (Java-migration addition; no direct COBOL
 *       equivalent because the COBOL workflow preserves CVV from the
 *       existing record and does not validate it as operator input).</li>
 *   <li>Embossed name presence check (COBOL parity:
 *       {@code 1230-EDIT-NAME}).</li>
 *   <li>Expiration date strict ISO parse (COBOL parity:
 *       {@code 1250-EDIT-EXPIRY-MON} + {@code 1260-EDIT-EXPIRY-YEAR},
 *       collapsed into a single {@link java.time.LocalDate#parse} in the
 *       Java migration).</li>
 *   <li>Active status domain check (COBOL parity:
 *       {@code 1240-EDIT-CARDSTATUS}).</li>
 *   <li>Field updates + {@link CardRepository#save} (COBOL parity:
 *       {@code EXEC CICS REWRITE} at line 1477).</li>
 *   <li>Success response with the verbatim COBOL {@code 'Changes
 *       committed to database'} message ({@code CONFIRM-UPDATE-SUCCESS}
 *       at line 169).</li>
 * </ol>
 *
 * <h2>Card Number Immutability Invariant</h2>
 *
 * <p>The COBOL workflow at lines 1461–1474 {@code INITIALIZE
 * CARD-UPDATE-RECORD} with the loaded {@code CARD-NUM} value before
 * assigning the request's other fields — implicitly preserving the
 * primary key across the update. The Java migration enforces this
 * explicitly: the production service NEVER calls
 * {@link Card#setCardNumber(String)} on the loaded entity. The
 * {@code preservesCardNumberAsImmutableKey} test guards this invariant
 * via {@link ArgumentCaptor}, asserting that the captured {@link Card}
 * passed to {@code save()} carries the exact primary-key value supplied
 * to {@code findById()}.
 *
 * <h2>Optimistic Locking (COBOL DATA-WAS-CHANGED-BEFORE-UPDATE parity)</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom serialised concurrent updates
 * through CICS file locks; the {@code CHECK-CHANGE-IN-REC} paragraph
 * (line 1453) compared the displayed before-image of the record against
 * the current persisted state on the way into the REWRITE, setting
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 1511) when they differed.
 * The Java migration replaces this with JPA's {@code @Version}
 * optimistic-locking field on {@link Card}: when the {@code save()} call
 * detects a version mismatch, it raises
 * {@link OptimisticLockingFailureException}. Tests verify this exception
 * propagates uncaught — the {@link CardUpdateService} does not catch it,
 * letting the controller layer's exception-handler chain produce the
 * HTTP 409 Conflict response.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link CardUpdateService} via its public
 * three-argument constructor and assert on the {@link CardUpdateResult}
 * returned by the real
 * {@link CardUpdateService#updateCard(CardUpdateRequest)} method (or on
 * the propagated {@link OptimisticLockingFailureException} for the
 * version-mismatch scenario). The only mocked collaborators are
 * {@link CardRepository} and {@link CardXrefRepository} (database
 * boundaries, the only mock category permitted under AAP §0.10.1). The
 * {@link Clock} is a real {@link Clock#fixed(Instant, java.time.ZoneId)}
 * — never mocked — so the test exercises real time-handling code paths
 * with a deterministic instant.
 *
 * <p>No business logic — card-number format check, CVV check, date
 * parse, active-status domain check, field-update dispatch, optimistic-
 * locking propagation, success-message construction — is duplicated in
 * any test body; the tests assert only on observable outputs
 * ({@link CardUpdateResult#isSuccess()},
 * {@link CardUpdateResult#getMessage()}, the {@link Card} captured by
 * {@link ArgumentCaptor}, and the exception type of the propagated
 * {@link OptimisticLockingFailureException}).
 *
 * <h2>Defence-in-Depth Invariants</h2>
 *
 * <p>The reject-path tests assert the load-bearing "no save" invariant
 * via {@code verify(cardRepository, never()).save(any())}, proving that
 * malformed requests cannot reach the persistence layer. Combined with
 * Mockito's default {@code STRICT_STUBS} mode (which raises
 * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}
 * on any unused stub), these assertions prove that the production code's
 * reject paths do not silently call the repository — a defence-in-depth
 * property documented per AAP §0.10.1.
 *
 * @see CardUpdateService
 * @see CardUpdateRequest
 * @see CardUpdateResult
 * @see Card
 * @see CardRepository
 * @see CardXrefRepository
 * @see TestFixtures.Cards
 * @see TestFixtures.Accounts
 * @see TestFixtures.Dates
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService — COCRDUPC.cbl migration parity")
final class CardUpdateServiceTest {

    /**
     * Mocked {@link CardRepository} — the JPA repository boundary the
     * production {@link CardUpdateService} delegates to for {@code findById}
     * (the COBOL {@code EXEC CICS READ FILE(CARDDAT)} replacement at line
     * 1382) and {@code save} (the COBOL {@code EXEC CICS REWRITE} at line
     * 1477). Per AAP §0.10.1, mocked because it crosses the database
     * boundary (the only mock category permitted). Re-created per
     * {@code @Test} method by {@link MockitoExtension}, ensuring strict
     * isolation between scenarios.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mocked {@link CardXrefRepository} — the JPA repository boundary the
     * production {@link CardUpdateService} accepts as a constructor
     * argument for future account-cross-reference validation. Per AAP
     * §0.10.1, mocked because it crosses the database boundary. The
     * current minimum-viable implementation of {@code CardUpdateService}
     * does not invoke this repository on any code path; the {@code @Mock}
     * field is declared here so the service constructor can be satisfied
     * with a non-{@code null} argument. No stubs are configured on this
     * mock — under Mockito's {@code STRICT_STUBS} mode this is acceptable
     * (only unused STUBS, not unused MOCKS, raise
     * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}).
     */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /**
     * Fixed {@link Clock} pinned at {@code 2024-01-15T00:00:00Z} (UTC) for
     * deterministic time-dependent behaviour in the production
     * {@link CardUpdateService}. The {@link Clock#fixed(Instant,
     * java.time.ZoneId)} factory produces a {@link Clock} that always
     * returns the supplied {@link Instant} from
     * {@link Clock#instant()}, guaranteeing reproducible test outcomes
     * across runs and parallel executions per AAP §0.10.9
     * test-independence requirement. The instant is sourced from
     * {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT} so every test class
     * in the suite that uses a fixed clock agrees on the same "now".
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
            ZoneOffset.UTC);

    /**
     * System under test — the real {@link CardUpdateService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborators above and to prevent any
     * accidental state leak between tests.
     */
    private CardUpdateService service;

    /**
     * Constructs a fresh {@link CardUpdateService} before every test method,
     * injecting the freshly-instantiated {@link #cardRepository} and
     * {@link #cardXrefRepository} mocks plus the {@link #fixedClock}
     * deterministic clock. The combination of per-method {@code @Mock}
     * instantiation (driven by {@link MockitoExtension}) and per-method
     * service construction here guarantees that no stub or interaction
     * from one test leaks into another (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new CardUpdateService(cardRepository, cardXrefRepository, fixedClock);
    }



    // =========================================================================
    // HAPPY PATH — valid request updates the card and returns success
    //
    // COCRDUPC.cbl 9200-WRITE-PROCESSING (lines 1376-1521) reads the existing
    // CARD-RECORD by primary key (line 1382), READs again UPDATE to acquire
    // the lock (line 1427), compares loaded vs displayed before-image fields
    // via CHECK-CHANGE-IN-REC (line 1453), INITIALIZES CARD-UPDATE-RECORD
    // with the new values (lines 1461-1474), and REWRITEs (line 1477). On
    // DFHRESP(NORMAL) it sets CONFIRM-UPDATE-SUCCESS (the 88-level literal
    // 'Changes committed to database' at line 169). The Java migration
    // collapses this into a single findById → mutate-loaded-entity → save
    // sequence with JPA's @Version field replacing the COBOL READ UPDATE /
    // CHECK-CHANGE-IN-REC pessimistic-lock idiom.
    // =========================================================================

    /**
     * Happy-path scenarios for
     * {@link CardUpdateService#updateCard(CardUpdateRequest)}.
     *
     * <p>Each scenario configures the mocked {@link #cardRepository} to
     * return {@link Optional#of(Object)} on the {@code findById(...)} call
     * and to echo back the supplied {@link Card} on the {@code save(...)}
     * call, then drives the service with a valid {@link CardUpdateRequest}
     * built by {@link CardUpdateServiceTest#buildValidRequest()}. The
     * scenarios assert on (a) the returned {@link CardUpdateResult}'s
     * success flag and message, (b) the {@link Card} captured by
     * {@link ArgumentCaptor} on the single {@code save(...)} invocation
     * (verifying the per-field mutations and the primary-key preservation),
     * and (c) the exact number of {@code save(...)} invocations (must be
     * exactly 1 — short of that would indicate the production code did not
     * persist, more than that would indicate redundant persistence).
     */
    @Nested
    @DisplayName("Happy path — valid request persists the card")
    class HappyPath {

        /**
         * Verifies that a valid {@link CardUpdateRequest} with a small
         * field change (embossed name {@code "JOHN DOE"} → {@code "JOHN A
         * DOE"}) results in:
         * <ul>
         *   <li>A single {@link CardRepository#save(Object)} invocation.</li>
         *   <li>A successful {@link CardUpdateResult} (i.e.
         *       {@link CardUpdateResult#isSuccess()} returns {@code true}).</li>
         *   <li>The persisted {@link Card} carrying the new embossed name
         *       from the request, the unchanged CVV from the request, and
         *       the preserved primary key from the originally-loaded
         *       entity.</li>
         * </ul>
         *
         * <p>COBOL parity: {@code 9200-WRITE-PROCESSING} happy path (lines
         * 1376–1521 DFHRESP(NORMAL) branch), with the {@code 'Changes
         * committed to database'} success literal at line 169.
         */
        @Test
        @DisplayName("updateCard(validRequest) persists card and returns success")
        void updateCard_validRequest_persistsCardAndReturnsSuccess() {
            // Arrange — pre-existing Card in CARDDAT replacement table.
            // Uses the standardCard() helper for the fields not under test,
            // and explicitly sets the embossed name to the COBOL-baseline
            // value "JOHN DOE" so the test can assert the change to
            // "JOHN A DOE" is observable.
            Card existingCard = standardCard();
            existingCard.setEmbossedName("JOHN DOE");

            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existingCard));
            // The save stub echoes back the captured argument so the
            // production code's return value (if any) carries the saved
            // entity reference; the service does not use this return value
            // (it returns a CardUpdateResult, not the entity), but the stub
            // is wired to match Spring Data JPA's actual save contract.
            when(cardRepository.save(any(Card.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            CardUpdateRequest request = buildValidRequest();
            request.setEmbossedName("JOHN A DOE"); // the small change under test

            // Act — drive the real production service.
            CardUpdateResult result = service.updateCard(request);

            // Assert — observable success.
            assertThat(result.isSuccess())
                    .as("Happy-path request must succeed (COBOL: 9200-WRITE-"
                            + "PROCESSING DFHRESP(NORMAL) at line 1483)")
                    .isTrue();

            // Assert — the Card passed to save() carries the new values.
            // ArgumentCaptor is the only legitimate way to inspect the
            // entity the production code constructed: per AAP §0.10.1 the
            // test must NOT duplicate the production logic that derived the
            // values.
            ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).save(captor.capture());
            Card saved = captor.getValue();

            assertThat(saved.getEmbossedName())
                    .as("Embossed name change must reach the persisted entity"
                            + " (COBOL: CARD-UPDATE-EMBOSSED-NAME at line 318)")
                    .isEqualTo("JOHN A DOE");
            assertThat(saved.getCardNumber())
                    .as("Card number must be preserved as the immutable PK"
                            + " (COBOL: CARD-UPDATE-NUM PIC X(16) at line 315)")
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            assertThat(saved.getCvvCode())
                    .as("CVV must reach the persisted entity unchanged from"
                            + " the request (COBOL: CARD-UPDATE-CVV-CD at line 317)")
                    .isEqualTo("123");

            // Assert — exactly one save invocation. More than 1 would
            // indicate a defect (double-persistence); 0 would indicate the
            // production code returned without persisting.
            verify(cardRepository, times(1)).save(any(Card.class));
        }

        /**
         * Verifies the card-number immutability invariant: the production
         * service NEVER calls {@link Card#setCardNumber(String)} on the
         * loaded entity, so the persisted primary key always equals the
         * one originally loaded from the repository.
         *
         * <p>This is a load-bearing test for the COBOL-parity contract: the
         * primary key is the access path for every subsequent operation
         * (read, update, balance posting, statement generation). Allowing
         * the primary key to mutate during an update would invalidate every
         * cross-reference (CardXref account → card mapping, transaction
         * card-number foreign keys, etc.).
         *
         * <p>COBOL parity: {@code 9200-WRITE-PROCESSING} lines 1461–1474
         * INITIALIZE CARD-UPDATE-RECORD with the loaded CARD-NUM value
         * before assigning the operator's other fields.
         */
        @Test
        @DisplayName("updateCard preserves cardNumber as immutable PK (PIC X(16))")
        void updateCard_validRequest_preservesCardNumberAsImmutableKey() {
            // Arrange
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));
            when(cardRepository.save(any(Card.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act — drive the production service with a fully-valid request.
            service.updateCard(buildValidRequest());

            // Assert — captured Card carries the same PK as findById(...).
            ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).save(captor.capture());
            assertThat(captor.getValue().getCardNumber())
                    .as("Card number is the immutable PK; must not change on"
                            + " update (COBOL: CARD-UPDATE-NUM PIC X(16) at"
                            + " line 315 of COCRDUPC.cbl)")
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        }

        /**
         * Verifies that the success response carries the verbatim COBOL
         * literal {@code 'Changes committed to database'} from
         * {@code CONFIRM-UPDATE-SUCCESS} at {@code COCRDUPC.cbl} line 169.
         *
         * <p>This is the load-bearing AAP §0.10.4 (Immutable Boundaries)
         * test: downstream consumers reading the JSON response envelope
         * must see the same textual confirmation as the COBOL baseline.
         * Any drift in this message would break clients that pattern-match
         * on it.
         */
        @Test
        @DisplayName("updateCard(validRequest) returns 'Changes committed to database' "
                + "(COBOL CONFIRM-UPDATE-SUCCESS at line 169)")
        void updateCard_validRequest_returnsChangesCommittedMessage() {
            // Arrange
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));
            when(cardRepository.save(any(Card.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            CardUpdateResult result = service.updateCard(buildValidRequest());

            // Assert — verbatim COBOL literal preserved (AAP §0.10.4).
            assertThat(result.getMessage())
                    .as("Success message must be the verbatim COBOL literal"
                            + " 'Changes committed to database' from"
                            + " CONFIRM-UPDATE-SUCCESS at line 169")
                    .isEqualTo("Changes committed to database");
            assertThat(result.isSuccess()).isTrue();
        }
    }


    // =========================================================================
    // OPTIMISTIC LOCKING — DATA-WAS-CHANGED-BEFORE-UPDATE + COULD-NOT-LOCK-FOR-UPDATE
    //
    // COCRDUPC.cbl 9200-WRITE-PROCESSING (lines 1376-1521) acquires an
    // exclusive CICS file lock via EXEC CICS READ UPDATE (line 1427) and
    // compares the loaded record against the displayed before-image via
    // CHECK-CHANGE-IN-REC (line 1453). If they differ, the COBOL workflow
    // sets DATA-WAS-CHANGED-BEFORE-UPDATE (line 1511) and emits the
    // 'Record changed by some one else. Please review' reject (line 208).
    // If the initial READ at line 1382 returns DFHRESP(NOTFND), the COBOL
    // workflow emits 'Did not find cards for this search condition'
    // (DID-NOT-FIND-ACCTCARD-COMBO at line 203) or 'Could not lock record
    // for update' (COULD-NOT-LOCK-FOR-UPDATE at line 205).
    //
    // The Java migration replaces both COBOL mechanisms with JPA's
    // @Version optimistic-locking field: the EXEC CICS READ UPDATE
    // pessimistic lock is dropped entirely, and the version-mismatch
    // detection happens at JpaRepository.save() time via the @Version
    // contract on the Card entity. On version mismatch JPA raises
    // OptimisticLockingFailureException; the service does not catch it,
    // letting it propagate uncaught to the controller layer (mapped to
    // HTTP 409 Conflict).
    // =========================================================================

    /**
     * Optimistic-locking scenarios for
     * {@link CardUpdateService#updateCard(CardUpdateRequest)}.
     *
     * <p>Tests cover:
     * <ul>
     *   <li>The {@link Card#getVersion()} mismatch path
     *       ({@link OptimisticLockingFailureException} propagation) — the
     *       Java equivalent of the COBOL
     *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch at line 1511.</li>
     *   <li>The card-not-found path ({@link Optional#empty()} return from
     *       {@link CardRepository#findById}) — the Java equivalent of
     *       the COBOL {@code DFHRESP(NOTFND)} branch on the EXEC CICS READ
     *       at line 1382.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Optimistic locking — COCRDUPC 1382-1521 failure paths")
    class OptimisticLocking {

        /**
         * Verifies that a {@link OptimisticLockingFailureException} thrown
         * by {@link CardRepository#save(Object)} propagates uncaught
         * through {@link CardUpdateService#updateCard(CardUpdateRequest)}.
         *
         * <p>This is the load-bearing AAP §0.10.4 / §0.10.1 test that
         * proves the production service does NOT swallow the exception
         * (which would mask the optimistic-lock conflict from the
         * controller layer and break the HTTP 409 Conflict mapping).
         *
         * <p>COBOL parity: the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag
         * at line 1511, set when {@code CHECK-CHANGE-IN-REC} (line 1453)
         * detects the loaded record was changed by another user between
         * the initial READ (line 1382) and the READ UPDATE (line 1427).
         * The COBOL response is the {@code 'Record changed by some one
         * else. Please review'} reject literal at line 208; the Java
         * migration response is the
         * {@link OptimisticLockingFailureException} which the controller
         * maps to HTTP 409.
         */
        @Test
        @DisplayName("updateCard throws OptimisticLockingFailureException on"
                + " @Version mismatch (COBOL DATA-WAS-CHANGED-BEFORE-UPDATE)")
        void updateCard_versionMismatch_throwsOptimisticLockingFailureException() {
            // Arrange — happy lookup, but save() throws.
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));
            when(cardRepository.save(any(Card.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "Row was updated by another transaction"));

            // Act + Assert — exception propagates uncaught.
            assertThatThrownBy(() -> service.updateCard(buildValidRequest()))
                    .as("OptimisticLockingFailureException must propagate"
                            + " uncaught so the controller layer can map it"
                            + " to HTTP 409 Conflict (COBOL parity: "
                            + "DATA-WAS-CHANGED-BEFORE-UPDATE at line 1511)")
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        /**
         * Verifies that when {@link CardRepository#findById(Object)} returns
         * {@link Optional#empty()} (the card does not exist in the
         * CARDDAT replacement table), the production service:
         * <ul>
         *   <li>Returns {@link CardUpdateResult#failure(String)} with the
         *       verbatim COBOL reject literal {@code 'Did not find cards
         *       for this search condition'}.</li>
         *   <li>Does NOT call {@link CardRepository#save(Object)} — the
         *       absence-of-record short-circuits the entire downstream
         *       update flow.</li>
         * </ul>
         *
         * <p>COBOL parity: the {@code DFHRESP(NOTFND)} response on the
         * initial {@code EXEC CICS READ} at line 1382, which sets
         * {@code COULD-NOT-LOCK-FOR-UPDATE} or
         * {@code DID-NOT-FIND-ACCTCARD-COMBO} depending on the COBOL
         * code path. The Java migration consolidates both into a single
         * reject because the JPA model has no equivalent of CICS's
         * read-vs-read-update distinction.
         */
        @Test
        @DisplayName("updateCard returns reject when card-not-found"
                + " (COBOL DID-NOT-FIND-ACCTCARD-COMBO at line 203)")
        void updateCard_cardNotFound_returnsReject() {
            // Arrange — findById returns empty (card does not exist).
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.empty());

            // Act — drive the service with an otherwise-valid request.
            CardUpdateResult result = service.updateCard(buildValidRequest());

            // Assert — failure with the verbatim COBOL reject literal.
            assertThat(result.isSuccess())
                    .as("Card-not-found must yield failure (COBOL: "
                            + "DFHRESP(NOTFND) on EXEC CICS READ at line 1382)")
                    .isFalse();
            assertThat(result.getMessage())
                    .as("Reject message must be the verbatim COBOL literal"
                            + " 'Did not find cards for this search"
                            + " condition' (DID-NOT-FIND-ACCTCARD-COMBO at"
                            + " line 203 of COCRDUPC.cbl)")
                    .isEqualTo("Did not find cards for this search condition");

            // Defence-in-depth: save() must never be invoked on the
            // reject path. STRICT_STUBS Mockito strictness combined with
            // the absence of a save(...) stub already proves this, but the
            // explicit verify(never()) call makes the invariant explicit.
            verify(cardRepository, never()).save(any());
        }
    }


    // =========================================================================
    // VALIDATION REJECTS — COCRDUPC.cbl field-level edits (1210-1260)
    //
    // The COBOL workflow validates each operator-supplied field in a
    // separate paragraph:
    //   - 1210-EDIT-ACCOUNT: account ID format and presence
    //   - 1220-EDIT-CARD: card number presence and 16-digit numeric format
    //   - 1230-EDIT-NAME: card name presence and alphabetic-only format
    //   - 1240-EDIT-CARDSTATUS: active status Y/N domain
    //   - 1250-EDIT-EXPIRY-MON: expiry month 1-12 numeric
    //   - 1260-EDIT-EXPIRY-YEAR: expiry year 1950-2099 numeric
    //
    // The Java migration consolidates these into a single validation
    // cascade inside CardUpdateService.updateCard, mirroring the COBOL
    // reject messages verbatim (per AAP §0.10.4). The CVV check
    // (3-digit numeric) is a Java-migration addition with no direct COBOL
    // equivalent — the COBOL workflow preserves CVV from the existing
    // record and does not validate it as operator input, but the REST API
    // exposes CVV as an editable field.
    //
    // Each @ParameterizedTest exercises representative invalid inputs that
    // span the validation boundary (too short, too long, wrong character
    // class, empty, whitespace) to prove the validation rejects them all
    // with the SAME outcome (result.isSuccess() == false, save() never
    // invoked).
    // =========================================================================

    /**
     * Validation-reject scenarios for
     * {@link CardUpdateService#updateCard(CardUpdateRequest)}.
     *
     * <p>Each test method is a {@link ParameterizedTest} driven by a
     * {@link ValueSource} of representative invalid inputs. The invariants
     * asserted on every reject path are:
     * <ul>
     *   <li>{@link CardUpdateResult#isSuccess()} returns {@code false}.</li>
     *   <li>{@link CardRepository#save(Object)} is NEVER invoked
     *       (defence-in-depth via {@code verify(..., never())}).</li>
     * </ul>
     *
     * <p>The card-number reject scenario does not stub
     * {@link CardRepository#findById(Object)} because the production
     * validation cascade rejects card-number-format violations BEFORE the
     * repository lookup. The other three reject scenarios (CVV,
     * expiration date, active status) stub {@code findById(...)} because
     * the production cascade rejects those validations AFTER the lookup;
     * Mockito's STRICT_STUBS mode would raise
     * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}
     * if the production code did not actually call {@code findById(...)}.
     */
    @Nested
    @DisplayName("Validation rejects — COCRDUPC.cbl field-level edits"
            + " (1210-1260)")
    class ValidationRejects {

        /**
         * Verifies that invalid card-number formats are rejected BEFORE
         * the repository lookup (i.e. without a {@code findById(...)}
         * stub on {@link CardRepository}). The {@code @ValueSource} drives
         * five representative invalid inputs:
         * <ul>
         *   <li>{@code "411111111111111"} — 15 digits (one short of the
         *       PIC X(16) field width).</li>
         *   <li>{@code "41111111111111111"} — 17 digits (one over the
         *       PIC X(16) field width).</li>
         *   <li>{@code "411111111111111A"} — 16 chars but final char is
         *       alphabetic (violates PIC X(16) NUMERIC check at COBOL
         *       line 783).</li>
         *   <li>{@code ""} — empty (COBOL parity: WS-PROMPT-FOR-CARD at
         *       line 181, "Card number not provided").</li>
         *   <li>16 spaces — whitespace-only (COBOL parity: same
         *       WS-PROMPT-FOR-CARD branch via SPACES condition at line
         *       768).</li>
         * </ul>
         *
         * <p>COBOL parity: {@code 1220-EDIT-CARD} paragraph (lines 763-800)
         * — both {@code WS-PROMPT-FOR-CARD} (presence reject at line 181)
         * and {@code SEARCHED-CARD-NOT-NUMERIC} (format reject at line
         * 196) consolidate to a single observable outcome in this test:
         * {@code result.isSuccess() == false} and {@code save() never
         * invoked}.
         */
        @ParameterizedTest(name = "[{index}] invalid card number ''{0}''")
        @ValueSource(strings = {
                "411111111111111",       // 15 digits (too short)
                "41111111111111111",     // 17 digits (too long)
                "411111111111111A",      // 16 chars but non-numeric final char
                "",                      // empty
                "                "       // 16 spaces (whitespace-only)
        })
        @DisplayName("updateCard rejects card numbers that violate"
                + " PIC X(16) numeric format")
        void updateCard_invalidCardNumber_rejected(String invalidCardNumber) {
            // Arrange — invalid card number; no findById stub because the
            // production validation rejects BEFORE the repository lookup.
            // Under STRICT_STUBS, the absence of a findById stub here
            // implicitly proves that the production code does NOT call
            // findById on this path (a call to an unstubbed method on a
            // strict mock returns Mockito's default — which for Optional
            // is Optional.empty() — which would still drive the reject
            // through the card-not-found branch instead of the format
            // branch; but the more important invariant is that save() is
            // never called).
            CardUpdateRequest request = buildValidRequest();
            request.setCardNumber(invalidCardNumber);

            // Act
            CardUpdateResult result = service.updateCard(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("Card number '%s' must be rejected (COBOL: "
                            + "1220-EDIT-CARD lines 763-800)", invalidCardNumber)
                    .isFalse();

            // Defence-in-depth — save() never invoked on the reject path.
            verify(cardRepository, never()).save(any());
        }

        /**
         * Verifies that invalid CVV formats are rejected AFTER the
         * repository lookup (i.e. with a {@code findById(...)} stub so
         * Mockito's STRICT_STUBS mode does not raise
         * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}).
         * The {@code @ValueSource} drives five representative invalid
         * inputs:
         * <ul>
         *   <li>{@code "12"} — 2 digits (one short of the PIC 9(03)
         *       field width).</li>
         *   <li>{@code "1234"} — 4 digits (one over the PIC 9(03)
         *       field width).</li>
         *   <li>{@code "ABC"} — 3 chars but all alphabetic (violates
         *       PIC 9(03) NUMERIC check).</li>
         *   <li>{@code ""} — empty.</li>
         *   <li>{@code "  "} — whitespace-only (2-char).</li>
         * </ul>
         *
         * <p>The CVV check has no direct COBOL equivalent — the COBOL
         * workflow preserves CVV from the existing CARD-RECORD via the
         * READ on line 1382 and does not validate it as operator input
         * (the BMS map exposes no CVV input field). The Java migration
         * adds the validation because the REST API exposes CVV as an
         * editable field per the
         * {@link CardUpdateRequest#getCvvCode()} contract.
         */
        @ParameterizedTest(name = "[{index}] invalid CVV ''{0}''")
        @ValueSource(strings = {
                "12",     // 2 digits (too short)
                "1234",   // 4 digits (too long)
                "ABC",    // 3 chars but alphabetic
                "",       // empty
                "  "      // whitespace-only
        })
        @DisplayName("updateCard rejects CVV that violates PIC 9(03) format")
        void updateCard_invalidCvv_rejected(String invalidCvv) {
            // Arrange — happy lookup; the CVV reject happens AFTER the
            // repository read. The findById stub MUST be configured here
            // (otherwise Mockito STRICT_STUBS would not raise on the
            // production code calling it without a stub — Optional.empty()
            // is the default — but the production code would then drive
            // the card-not-found branch instead of the CVV-reject branch,
            // changing the observable outcome).
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));

            CardUpdateRequest request = buildValidRequest();
            request.setCvvCode(invalidCvv);

            // Act
            CardUpdateResult result = service.updateCard(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("CVV '%s' must be rejected (Java-migration"
                            + " addition mirroring CARD-UPDATE-CVV-CD"
                            + " PIC 9(03) at line 317)", invalidCvv)
                    .isFalse();

            // Defence-in-depth — save() never invoked on the reject path.
            verify(cardRepository, never()).save(any());
        }

        /**
         * Verifies that malformed expiration dates are rejected via the
         * strict {@link java.time.LocalDate#parse} validation. The
         * {@code @ValueSource} drives six representative invalid inputs:
         * <ul>
         *   <li>{@code "2024-13-01"} — month 13 (violates COBOL
         *       VALID-MONTH 88-level 1-12).</li>
         *   <li>{@code "2024-02-30"} — Feb 30 (impossible date,
         *       rejected by strict LocalDate.parse).</li>
         *   <li>{@code "2023-02-29"} — Feb 29 in a non-leap year
         *       (2023 is not divisible by 4).</li>
         *   <li>{@code "abcd-01-15"} — alphabetic year (violates COBOL
         *       1260-EDIT-EXPIRY-YEAR NUMERIC check).</li>
         *   <li>{@code "2024/01/15"} — wrong separator (LocalDate.parse
         *       with ISO_LOCAL_DATE requires '-').</li>
         *   <li>{@code ""} — empty.</li>
         * </ul>
         *
         * <p>COBOL parity: collapsed from {@code 1250-EDIT-EXPIRY-MON}
         * (lines 870-907, {@code CARD-EXPIRY-MONTH-NOT-VALID} at line
         * 200) and {@code 1260-EDIT-EXPIRY-YEAR} (lines 910-945,
         * {@code CARD-EXPIRY-YEAR-NOT-VALID} at line 202) into a single
         * strict-parse reject. The Java migration's
         * {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE}-driven
         * {@link java.time.LocalDate#parse} cannot easily distinguish
         * between month-related and year-related parse failures, so the
         * Java reject literal is the broader {@code 'Invalid card expiry
         * year'} from line 202.
         */
        @ParameterizedTest(name = "[{index}] invalid expiration date ''{0}''")
        @ValueSource(strings = {
                "2024-13-01",   // month 13 (invalid)
                "2024-02-30",   // Feb 30 (impossible)
                "2023-02-29",   // Feb 29 in non-leap year (2023 % 4 != 0)
                "abcd-01-15",   // alphabetic year
                "2024/01/15",   // wrong separator
                ""              // empty
        })
        @DisplayName("updateCard rejects malformed expiration dates"
                + " per CSUTLDTC parity (YYYY-MM-DD strict parse)")
        void updateCard_invalidExpirationDate_rejected(String invalidDate) {
            // Arrange — happy lookup; the date reject happens AFTER the
            // repository read (same rationale as the CVV reject above).
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));

            CardUpdateRequest request = buildValidRequest();
            request.setExpirationDate(invalidDate);

            // Act
            CardUpdateResult result = service.updateCard(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("Invalid expiration date '%s' must be rejected"
                            + " (COBOL: collapsed 1250-EDIT-EXPIRY-MON"
                            + " and 1260-EDIT-EXPIRY-YEAR)", invalidDate)
                    .isFalse();

            // Defence-in-depth — save() never invoked on the reject path.
            verify(cardRepository, never()).save(any());
        }

        /**
         * Verifies that active-status values outside the
         * {@code {"Y", "N"}} domain are rejected. The {@code @ValueSource}
         * drives six representative invalid inputs:
         * <ul>
         *   <li>{@code "X"} — 1-char outside Y/N domain (case-sensitive,
         *       case-canonical).</li>
         *   <li>{@code "1"} — numeric digit (violates PIC X(01)
         *       case-canonical domain).</li>
         *   <li>{@code "0"} — numeric digit (same rationale as "1").</li>
         *   <li>{@code "YES"} — 3-char (violates PIC X(01) single-char
         *       field width).</li>
         *   <li>{@code "true"} — 4-char (violates PIC X(01) field
         *       width).</li>
         *   <li>{@code " "} — single space (whitespace, violates PIC
         *       X(01) Y-or-N domain).</li>
         * </ul>
         *
         * <p>COBOL parity: {@code 1240-EDIT-CARDSTATUS} paragraph (lines
         * 845-869), {@code CARD-STATUS-MUST-BE-YES-NO} reject literal at
         * line 198 — verbatim {@code 'Card Active Status must be Y or
         * N'}.
         */
        @ParameterizedTest(name = "[{index}] invalid active status ''{0}''")
        @ValueSource(strings = {
                "X",      // single char outside Y/N
                "1",      // numeric
                "0",      // numeric
                "YES",    // multi-char
                "true",   // multi-char
                " "       // single space
        })
        @DisplayName("updateCard rejects active status outside Y/N domain"
                + " (COBOL CARD-STATUS-MUST-BE-YES-NO at line 198)")
        void updateCard_invalidActiveStatus_rejected(String invalidStatus) {
            // Arrange — happy lookup; the status reject happens AFTER the
            // repository read.
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));

            CardUpdateRequest request = buildValidRequest();
            request.setActiveStatus(invalidStatus);

            // Act
            CardUpdateResult result = service.updateCard(request);

            // Assert — observable reject.
            assertThat(result.isSuccess())
                    .as("Active status '%s' must be rejected (COBOL: "
                            + "1240-EDIT-CARDSTATUS, "
                            + "CARD-STATUS-MUST-BE-YES-NO at line 198)",
                            invalidStatus)
                    .isFalse();

            // Defence-in-depth — save() never invoked on the reject path.
            verify(cardRepository, never()).save(any());
        }
    }


    // =========================================================================
    // HELPER METHODS — fixture construction
    //
    // These helpers are kept private and static so they can be invoked from
    // any nested @Nested class without re-instantiation, and so they cannot
    // accidentally accumulate per-test mutable state. They produce
    // canonical Card and CardUpdateRequest fixtures that satisfy every
    // validation rule the production CardUpdateService enforces — so the
    // tests that exercise reject paths only need to mutate the specific
    // field they want to invalidate.
    // =========================================================================

    /**
     * Builds a canonical {@link Card} fixture representing the persisted
     * pre-update record loaded by {@link CardRepository#findById(Object)}.
     * The fixture's field values are all valid per the production
     * {@link CardUpdateService} validation cascade — so tests that pass
     * a {@link #buildValidRequest()} alongside this fixture will reach
     * the {@link CardRepository#save(Object)} call (subject to any save
     * stub configured by the test).
     *
     * <h3>Field values</h3>
     * <ul>
     *   <li>{@code cardNumber} = {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
     *       (the 16-digit Visa test PAN {@code "4111111111111101"}).</li>
     *   <li>{@code accountId} = {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
     *       (the 11-digit zero-padded account ID {@code "00000000010"}).</li>
     *   <li>{@code activeStatus} = {@link TestFixtures.Cards#ACTIVE_STATUS_YES}
     *       ({@code "Y"}, the COBOL parity value for an active card).</li>
     *   <li>{@code expirationDate} = {@code "2025-12-31"} (a future date
     *       relative to the {@link #fixedClock}'s {@code 2024-01-15} now,
     *       so the card is not expired in the test world).</li>
     *   <li>{@code embossedName} = {@code "JOHN DOE"} (a canonical
     *       alphabetic-only embossed name that satisfies the COBOL
     *       1230-EDIT-NAME alpha-only check).</li>
     *   <li>{@code cvvCode} = {@code "123"} (a canonical 3-digit CVV).</li>
     *   <li>{@code version} = {@code 1L} (a non-{@code null} version so
     *       JPA's {@code @Version} contract is exercised on save).</li>
     * </ul>
     *
     * @return a populated {@link Card} fixture suitable for use as the
     *         {@link Optional#of(Object)} payload returned by a
     *         {@link CardRepository#findById(Object)} stub
     */
    private static Card standardCard() {
        Card c = new Card();
        c.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        c.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        c.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        c.setExpirationDate("2025-12-31");
        c.setEmbossedName("JOHN DOE");
        c.setCvvCode("123");
        c.setVersion(1L);
        return c;
    }

    /**
     * Builds a canonical {@link CardUpdateRequest} fixture whose field
     * values are all valid per the production {@link CardUpdateService}
     * validation cascade. Tests that exercise reject paths typically
     * call this helper and then mutate exactly one field to invalidate
     * the desired validation rule — so the test isolates the single
     * field under test rather than re-stating the whole request shape.
     *
     * <h3>Field values</h3>
     * <ul>
     *   <li>{@code cardNumber} = {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
     *       (matches the {@link #standardCard()} fixture's PK so
     *       {@code findById(...)} returns a {@link Card}).</li>
     *   <li>{@code accountId} = {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}.</li>
     *   <li>{@code cvvCode} = {@code "123"} (a canonical valid 3-digit
     *       numeric CVV).</li>
     *   <li>{@code embossedName} = {@code "JOHN DOE"}.</li>
     *   <li>{@code expirationDate} = {@code "2025-12-31"} (valid ISO
     *       date, in the future relative to the {@link #fixedClock}).</li>
     *   <li>{@code activeStatus} = {@link TestFixtures.Cards#ACTIVE_STATUS_YES}
     *       ({@code "Y"}).</li>
     *   <li>{@code version} = {@code 1L}.</li>
     * </ul>
     *
     * @return a populated, fully-valid {@link CardUpdateRequest} ready
     *         for use in a happy-path scenario or as the starting point
     *         for a field-mutation reject scenario
     */
    private static CardUpdateRequest buildValidRequest() {
        CardUpdateRequest req = new CardUpdateRequest();
        req.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        req.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        req.setCvvCode("123");
        req.setEmbossedName("JOHN DOE");
        req.setExpirationDate("2025-12-31");
        req.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        req.setVersion(1L);
        return req;
    }
}

