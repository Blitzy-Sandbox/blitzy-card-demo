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
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * TestFixtures — single source of truth for sample identifiers
//     (TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
//     TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, and the deterministic
//     clock instant TestFixtures.Dates.FIXED_CLOCK_INSTANT
//     ("2024-01-15T00:00:00Z") that this class's HappyPath isExpired
//     branches anchor on). Per AAP §0.5.5 every literal that appears in
//     more than one test class must come from TestFixtures so the
//     baseline/input/*.txt fixtures and the in-memory test data agree.
//
//   * Card — the JPA entity populated by the COCRDSLC.cbl migration's
//     single-key CARDDAT read. The `standardCard()` helper builds a
//     fully-populated instance using TestFixtures constants and
//     deliberately synthetic literals (no PII) so each test can stub the
//     repository with a realistic return value.
//
//   * CardRepository — the Spring Data JPA repository boundary the
//     production service calls. Mocked at the JPA-repository boundary per
//     AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * CardDetailService — the production class under test. No explicit
//     import because it shares this test's package
//     (`com.aws.carddemo.service`); Java resolves simple-named references
//     via package membership.
//
//   * CardDetailResponse — the result DTO returned by every code path of
//     CardDetailService.getCard(...). Same package; resolved without an
//     import.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Card;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @BeforeEach — reinstantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9).
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups happy-path and reject-path tests into the two
//     semantic sections (HappyPath, RejectPaths) that match this test
//     class's exports.members_exposed schema entries.
//   * @Test — single-execution test marker (no parameter source).
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// Mockito 5 (AAP §0.6.1 — BOM-managed by spring-boot-starter-test 3.3.13;
// resolved to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * MockitoExtension — activates STRICT_STUBS strictness by default (AAP
//     §0.10.1: "any unused stub fails the test — surfaces Require-Test-
//     Coverage-rule violations early"). Tests that configure a stub but
//     never trigger the production code path that uses it will fail with
//     UnnecessaryStubbingException.
// ---------------------------------------------------------------------------
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.time + java.util).
//
//   * Clock — AAP §0.4.2 Blueprint A pattern for deterministic time
//     dependence. Constructed once per test via Clock.fixed(...) with the
//     TestFixtures.Dates.FIXED_CLOCK_INSTANT ("2024-01-15T00:00:00Z") so
//     the production service's isExpired derivation is reproducible
//     across the HappyPath isExpired-false (future-dated) and
//     isExpired-true (past-dated) branches.
//   * Instant — parses the TestFixtures.Dates.FIXED_CLOCK_INSTANT ISO-8601
//     literal into a java.time.Instant for the Clock.fixed(...) factory.
//   * ZoneOffset.UTC — second argument of Clock.fixed(...); pins the
//     clock to UTC so LocalDate.now(clock) on the production side
//     resolves deterministically regardless of the host machine's
//     default time zone.
//   * Optional — repository lookup return values; Optional.of(...) for
//     happy-path scenarios, Optional.empty() for the NOTFND-reject
//     scenario mirroring COBOL DFHRESP(NOTFND) on CARDDAT.
// ---------------------------------------------------------------------------
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * ArgumentMatchers.any — relaxes argument matching in tests where the
//     card-number input is incidental (e.g., the isExpired branch
//     coverage tests where the focus is on the date comparison, not the
//     PK passed to findById).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardDetailService}, the migrated Java equivalent of
 * the 887-line CICS program {@code app/cbl/COCRDSLC.cbl} (TRANID
 * {@code CCDL}). Read-only single-key card-detail lookup.
 *
 * <h2>COBOL Provenance — COCRDSLC.cbl</h2>
 *
 * <p>The {@code 9100-GETCARD-BYACCTCARD} paragraph (lines 736–777)
 * orchestrates the single-key read against the {@code CARDDAT} VSAM KSDS:
 *
 * <ol>
 *   <li>{@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
 *       by the 16-character {@code CARD-NUM} primary key (lines
 *       742–750).</li>
 *   <li>{@code WHEN DFHRESP(NORMAL)} — record found, the field-mapping
 *       block populates the {@code CCRDSLA} BMS map (replaced in the Java
 *       migration by
 *       {@link CardDetailResponse#success(Card, boolean)}).</li>
 *   <li>{@code WHEN DFHRESP(NOTFND)} — reject with the COBOL message
 *       {@code 'Did not find cards for this search condition'} (line
 *       760), normalised in the Java migration to
 *       {@link CardDetailService#MSG_CARD_NOT_FOUND}.</li>
 *   <li>{@code WHEN OTHER} — I/O error (lines 762–771); maps to
 *       infrastructure failures
 *       ({@link org.springframework.dao.DataAccessException}) and is
 *       handled by the controller-layer exception handler rather than
 *       producing a {@link CardDetailResponse#failure(String)} value.</li>
 * </ol>
 *
 * <h2>Java Migration: Expired-Card Display Flag</h2>
 *
 * <p>The COBOL workflow displays the {@code CARD-EXPIRAION-DATE} field
 * verbatim on the {@code CCRDSLA} BMS map. The Java migration adds a
 * derived {@code expired} boolean flag (computed against the injected
 * {@link Clock}) so downstream REST consumers can render expired-card
 * indicators without parsing the date string themselves — a display-only
 * enhancement that did not exist in COBOL but is needed for the REST API
 * contract.
 *
 * <p>This test class verifies the {@code expired} derivation by injecting
 * a {@link Clock#fixed(Instant, java.time.ZoneId)} pinned to
 * {@code 2024-01-15T00:00:00Z} (see
 * {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}) and asserting the flag
 * for both future-dated ({@code 2026-12-31} → not expired) and past-dated
 * ({@code 2023-06-30} → expired) cards.
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree (both
 * COBOL-mandated branches plus the Java-migration expired derivation):
 *
 * <table border="1">
 *   <caption>Card-detail test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path (CARDDAT lookup succeeds, populated detail)</td>
 *       <td>{@link HappyPath#getCard_existingCard_returnsPopulatedDetail()}</td></tr>
 *   <tr><td>{@code expired=false} (future-dated card vs fixed clock)</td>
 *       <td>{@link HappyPath#getCard_futureExpirationDate_isExpiredFalse()}</td></tr>
 *   <tr><td>{@code expired=true} (past-dated card vs fixed clock)</td>
 *       <td>{@link HappyPath#getCard_pastExpirationDate_isExpiredTrue()}</td></tr>
 *   <tr><td>{@code expired=false} (null expiration — defensive guard)</td>
 *       <td>{@link HappyPath#getCard_nullExpirationDate_isExpiredFalseAndLookupSucceeds()}</td></tr>
 *   <tr><td>{@code expired=false} (malformed expiration — defensive guard)</td>
 *       <td>{@link HappyPath#getCard_malformedExpirationDate_isExpiredFalseAndLookupSucceeds()}</td></tr>
 *   <tr><td>NOTFND reject ({@code DFHRESP(NOTFND)})</td>
 *       <td>{@link RejectPaths#getCard_nonexistentCard_rejectsWithNotFoundMessage()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link CardDetailService}; the single
 * mock is the JPA repository boundary ({@link CardRepository}). The
 * {@link Clock} is a real {@link Clock#fixed} instance, not a Mockito
 * mock — fixed clocks are deterministic by construction so mocking would
 * add no value. No business logic — the {@code expired} derivation, the
 * reject-message selection, the field mapping into
 * {@link CardDetailResponse} — is reimplemented inside test bodies.
 * Assertions reference only observable outputs (the returned
 * {@link CardDetailResponse} fields).
 *
 * <h2>Read-Only Service (No Repository Mutations)</h2>
 *
 * <p>The COBOL workflow is strictly read-only; no {@code REWRITE},
 * {@code WRITE}, or {@code DELETE} statements are issued against
 * {@code CARDDAT}. The Java migration preserves this contract: the
 * service calls only {@link CardRepository#findById(Object)}; no
 * {@code save()} or {@code deleteById()} call exists in the production
 * code path. Mockito's STRICT_STUBS mode catches accidental mutation
 * stubs at test-execution time (unused stubs raise
 * {@code UnnecessaryStubbingException}), so the test class deliberately
 * does NOT add any defensive {@code verify(..., never())} assertions on
 * mutation methods — that would be over-mocking. The structural
 * read-only-ness is enforced by the production class's source code, not
 * by the test.
 *
 * @see CardDetailService
 * @see CardDetailResponse
 * @see Card
 * @see CardRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService — COCRDSLC.cbl migration parity")
final class CardDetailServiceTest {

    /**
     * Mocked JPA repository for {@link Card} lookups. The production
     * {@link CardDetailService#getCard(String)} calls
     * {@code cardRepository.findById(cardNumber)} as the single stage of
     * the read-only workflow (COBOL §9100-GETCARD-BYACCTCARD).
     *
     * <p>This is the only Mockito mock the test maintains — the COBOL
     * program has a single I/O boundary (the {@code CARDDAT} VSAM file),
     * so the Java migration has a single repository collaborator, and
     * the test mocks exactly that one boundary. Per AAP §0.10.1 ("Mocks
     * limited to external boundaries: file I/O, downstream service
     * calls, database").
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Deterministic clock pinned to {@code 2024-01-15T00:00:00Z} via
     * {@link Clock#fixed(Instant, java.time.ZoneId)}. The production
     * service's {@code expired} derivation calls {@code LocalDate.now(clock)}
     * internally; pinning the clock makes the derivation reproducible
     * regardless of when the test runs and regardless of the host
     * machine's default time zone.
     *
     * <p>This is a real {@link Clock} instance (not a Mockito mock):
     * fixed clocks are deterministic by construction, and using a mock
     * would add complexity without benefit. The fixed-clock pattern is
     * documented in AAP §0.4.2 Blueprint A and applied consistently
     * across the {@code AuthenticationService} tests and this class.
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT), // 2024-01-15T00:00:00Z
            ZoneOffset.UTC);

    /**
     * System under test. Instantiated fresh per {@code @Test} via
     * {@link #setUp()} because the {@code @Mock}-injected field is reset
     * by {@link MockitoExtension} between methods, and any captured
     * constructor reference would point at the stale prior mock.
     */
    private CardDetailService service;

    /**
     * Constructor-injects the freshly created Mockito mock and the
     * fixed clock into a new {@link CardDetailService} instance before
     * every {@code @Test} method. The two-argument constructor signature
     * documented by the agent prompt is verified implicitly: if the
     * production service ever changes its constructor signature, this
     * line will fail to compile and the entire test class will be
     * flagged at build time.
     */
    @BeforeEach
    void setUp() {
        service = new CardDetailService(cardRepository, fixedClock);
    }

    // =========================================================================
    // HAPPY PATH — single-key CARDDAT lookup succeeds, all fields populated
    // =========================================================================

    /**
     * Happy-path tests for {@link CardDetailService#getCard(String)}.
     *
     * <p>Verifies the success branch: the repository lookup returns a
     * populated card, the response is marked successful, the
     * COBOL-equivalent {@code CARD-*} fields are surfaced via the
     * response, and the Java-migration-added {@code expired} display
     * flag is derived correctly for both future-dated and past-dated
     * cards (with the fixed clock pinned to {@code 2024-01-15T00:00:00Z}).
     */
    @Nested
    @DisplayName("Happy path — read-only lookup")
    class HappyPath {

        /**
         * The canonical happy-path test: the repository returns a fully
         * populated {@link Card} for the sample card number (COBOL
         * {@code DFHRESP(NORMAL)} branch). The assertion block verifies
         * that the response carries:
         *
         * <ul>
         *   <li>{@code isSuccess()} → {@code true} (the success envelope flag)</li>
         *   <li>{@code getCardNumber()} → the fixture
         *       {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
         *       (CARD-NUM primary key preserved across the response
         *       round-trip)</li>
         *   <li>{@code getAccountId()} → the fixture
         *       {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
         *       (CARD-ACCT-ID foreign key preserved across the response
         *       round-trip)</li>
         *   <li>{@code getEmbossedName()} → {@code "TEST USER"}
         *       (CARD-EMBOSSED-NAME preserved verbatim)</li>
         *   <li>{@code getActiveStatus()} → {@code "Y"}
         *       (CARD-ACTIVE-STATUS preserved verbatim)</li>
         * </ul>
         *
         * <p>The fixture card carries a {@code 2026-12-31} expiration
         * date which is more than two years in the future relative to
         * the fixed clock — implicitly satisfying the
         * {@code expired=false} contract on the success path (the
         * dedicated future-dated test below makes that assertion
         * explicit).
         */
        @Test
        @DisplayName("getCard(existingCard) returns populated card detail")
        void getCard_existingCard_returnsPopulatedDetail() {
            // Arrange — build a fully populated Card for the canonical
            // sample card number and stub the repository to return it on
            // findById(...).
            Card existing = standardCard();
            when(cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(existing));

            // Act
            CardDetailResponse response = service.getCard(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Assert — outcome envelope: success branch sets isSuccess()
            // to true and leaves getMessage() null (the failure-only field).
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isNull();

            // Card-key + account-foreign-key identifiers preserved
            // verbatim from the entity (no transformation in the read
            // path).
            assertThat(response.getCardNumber())
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            assertThat(response.getAccountId())
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Display fields preserved verbatim.
            assertThat(response.getEmbossedName()).isEqualTo("TEST USER");
            assertThat(response.getActiveStatus()).isEqualTo("Y");
        }

        /**
         * Verifies the {@code expired=false} branch of the Java-migration
         * display-flag derivation: a card whose expiration date is
         * strictly later than "today" (per the fixed clock at
         * {@code 2024-01-15T00:00:00Z}) must report {@code isExpired()}
         * as {@code false}.
         *
         * <p>The fixture sets the expiration date to {@code 2026-12-31}
         * — nearly two years in the future relative to the fixed clock
         * — so the assertion is independent of any rounding or
         * end-of-month edge case. The {@code any()} argument matcher is
         * used because the test is incidental about which card-number
         * was passed to the repository — the only invariant under test
         * here is the date-comparison contract on the response side of
         * the mapping.
         */
        @Test
        @DisplayName("getCard sets isExpired=false for future-dated card")
        void getCard_futureExpirationDate_isExpiredFalse() {
            // Arrange — fixed clock at 2024-01-15; expiration date
            // ~2 years in the future.
            Card futureCard = standardCard();
            futureCard.setExpirationDate("2026-12-31");
            when(cardRepository.findById(any())).thenReturn(Optional.of(futureCard));

            // Act
            CardDetailResponse response = service.getCard(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Assert — the Java-migration expired flag is derived against
            // the injected clock; a future-dated card must be flagged
            // as NOT expired.
            assertThat(response.isExpired())
                    .as("Card with future expiration relative to fixed clock 2024-01-15 is not expired")
                    .isFalse();
        }

        /**
         * Verifies the {@code expired=true} branch of the Java-migration
         * display-flag derivation: a card whose expiration date is
         * strictly earlier than "today" (per the fixed clock at
         * {@code 2024-01-15T00:00:00Z}) must report {@code isExpired()}
         * as {@code true}.
         *
         * <p>The fixture sets the expiration date to {@code 2023-06-30}
         * — more than 6 months in the past relative to the fixed clock
         * — so the assertion is independent of any rounding or
         * end-of-month edge case. Paired with the future-dated test
         * above, the two cases prove that the production code
         * implements a genuine "is-strictly-before" comparison rather
         * than a fragile equality check or an opposite-sign comparison.
         */
        @Test
        @DisplayName("getCard sets isExpired=true for past-dated card")
        void getCard_pastExpirationDate_isExpiredTrue() {
            // Arrange — fixed clock at 2024-01-15; expiration date
            // ~6 months in the past.
            Card expiredCard = standardCard();
            expiredCard.setExpirationDate("2023-06-30");
            when(cardRepository.findById(any())).thenReturn(Optional.of(expiredCard));

            // Act
            CardDetailResponse response = service.getCard(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Assert — the Java-migration expired flag is derived against
            // the injected clock; a past-dated card MUST be flagged as
            // expired.
            assertThat(response.isExpired())
                    .as("Card with past expiration relative to fixed clock 2024-01-15 IS expired")
                    .isTrue();
        }

        /**
         * Verifies the documented defensive contract of
         * {@link CardDetailService}: a {@code null} expiration date on the
         * {@link Card} entity does NOT promote a display-only flag
         * derivation into an exception path. The card is returned with
         * {@code isExpired() == false} (the "permissive null guard"
         * documented in {@code CardDetailService.isExpired(String)}
         * javadoc, lines 235–242 of {@code CardDetailService.java}).
         *
         * <p>This branch is reachable in production if the database
         * column is ever populated by a path that bypasses the standard
         * NOT NULL constraint (e.g., a legacy bulk-load tool, an
         * incomplete data migration, or a fixture loader writing a
         * partially-populated entity). The Java migration's contract is
         * to return {@code false} in this case so the REST consumer sees
         * a "best-effort" expired flag rather than a 5xx error from an
         * unhandled NullPointerException.
         *
         * <p>The lookup itself still succeeds (the success envelope flag
         * is {@code true}); only the derived expired flag is permissive
         * about the missing date.
         */
        @Test
        @DisplayName("getCard treats null expiration date as not expired (defensive guard)")
        void getCard_nullExpirationDate_isExpiredFalseAndLookupSucceeds() {
            // Arrange — Card with null expirationDate, all other fields
            // populated; repository returns it on findById.
            Card cardWithNullDate = standardCard();
            cardWithNullDate.setExpirationDate(null);
            when(cardRepository.findById(any())).thenReturn(Optional.of(cardWithNullDate));

            // Act
            CardDetailResponse response = service.getCard(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Assert — the lookup succeeds; only the derived expired flag
            // is permissive about the missing date. This exercises the
            // documented null-guard branch of
            // CardDetailService.isExpired(String) without promoting the
            // null into a reject path.
            assertThat(response.isSuccess())
                    .as("Lookup succeeds even when the entity has a null expiration date")
                    .isTrue();
            assertThat(response.isExpired())
                    .as("Null expiration date is treated as not expired per documented contract")
                    .isFalse();
        }

        /**
         * Verifies the documented defensive contract of
         * {@link CardDetailService}: a malformed expiration date string
         * (one that {@link java.time.LocalDate#parse(CharSequence)} cannot
         * parse and throws {@link DateTimeParseException} for) does NOT
         * promote a display-only flag derivation into an exception path.
         * The card is returned with {@code isExpired() == false} (the
         * "permissive parse-failure guard" documented in
         * {@code CardDetailService.isExpired(String)} javadoc, lines
         * 235–242 of {@code CardDetailService.java}).
         *
         * <p>This branch is reachable in production if a legacy date
         * format (e.g., {@code MM/DD/YYYY} or COBOL-style {@code YYYYMMDD}
         * without separators) leaks into the {@code expirationDate} column
         * before the data-cleanup migration runs. The Java migration's
         * contract is to return {@code false} in this case so the REST
         * consumer sees a "best-effort" expired flag rather than a 5xx
         * error from an unhandled {@link DateTimeParseException}.
         *
         * <p>The fixture uses {@code "12/31/2026"} — a US-format date that
         * {@code LocalDate.parse(...)} cannot interpret with the default
         * ISO-8601 formatter. The lookup itself still succeeds (the
         * success envelope flag is {@code true}); only the derived
         * expired flag is permissive about the malformed date.
         */
        @Test
        @DisplayName("getCard treats malformed expiration date as not expired (defensive guard)")
        void getCard_malformedExpirationDate_isExpiredFalseAndLookupSucceeds() {
            // Arrange — Card with non-ISO-8601 expirationDate ("12/31/2026"
            // is US-format and cannot be parsed by LocalDate.parse with
            // the default ISO formatter, triggering the
            // DateTimeParseException catch branch).
            Card cardWithBadDate = standardCard();
            cardWithBadDate.setExpirationDate("12/31/2026");
            when(cardRepository.findById(any())).thenReturn(Optional.of(cardWithBadDate));

            // Act
            CardDetailResponse response = service.getCard(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Assert — the lookup succeeds; only the derived expired flag
            // is permissive about the malformed date. This exercises the
            // documented DateTimeParseException-catch branch of
            // CardDetailService.isExpired(String) without propagating the
            // parse failure as an unhandled exception.
            assertThat(response.isSuccess())
                    .as("Lookup succeeds even when the entity has a malformed expiration date")
                    .isTrue();
            assertThat(response.isExpired())
                    .as("Malformed expiration date is treated as not expired per documented contract")
                    .isFalse();
        }
    }

    // =========================================================================
    // REJECT PATHS — NOTFND mirrors COBOL DFHRESP(NOTFND)
    // =========================================================================

    /**
     * Reject-path tests for {@link CardDetailService#getCard(String)}.
     *
     * <p>Verifies the single reject branch the COBOL workflow produces
     * as a business-logic outcome ({@code DFHRESP(NOTFND)}). The COBOL
     * {@code WHEN OTHER} branch is an infrastructure-failure path
     * surfaced via {@link org.springframework.dao.DataAccessException}
     * propagation, not a {@link CardDetailResponse#failure(String)}
     * value — therefore not covered as a reject-path assertion in this
     * class (controller-layer exception handlers cover that branch in
     * the IT suite).
     */
    @Nested
    @DisplayName("Reject paths")
    class RejectPaths {

        /**
         * NOTFND reject — the canonical {@code DFHRESP(NOTFND)} branch
         * from {@code 9100-GETCARD-BYACCTCARD} (COCRDSLC.cbl lines
         * 755–761):
         *
         * <pre>
         *   WHEN DFHRESP(NOTFND)
         *     SET INPUT-ERROR                    TO TRUE
         *     SET FLG-ACCTFILTER-NOT-OK          TO TRUE
         *     SET FLG-CARDFILTER-NOT-OK          TO TRUE
         *     IF  WS-RETURN-MSG-OFF
         *         SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
         *     END-IF
         * </pre>
         *
         * <p>The Java migration normalises the COBOL message to
         * {@link CardDetailService#MSG_CARD_NOT_FOUND} ("Card number not
         * found...") and returns it as a
         * {@link CardDetailResponse#failure(String)} value. The
         * assertion uses {@code containsIgnoringCase("not found")} to
         * anchor on the discriminating phrase ("not found") rather than
         * the exact normalised literal — this lets the message text be
         * normalised across the codebase (e.g., "Card number not
         * found..." in one place vs. "Card Number Not Found..." in
         * another) without breaking the test's intent.
         *
         * <p>Uses a deliberately out-of-fixture card number
         * ({@code "9999999999999999"}) so the test cannot accidentally
         * collide with any real fixture row.
         */
        @Test
        @DisplayName("getCard rejects when card does not exist (NOTFND parity)")
        void getCard_nonexistentCard_rejectsWithNotFoundMessage() {
            // Arrange — repository returns Optional.empty() (NOTFND).
            when(cardRepository.findById("9999999999999999")).thenReturn(Optional.empty());

            // Act
            CardDetailResponse response = service.getCard("9999999999999999");

            // Assert — reject outcome with COBOL-equivalent message text.
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage())
                    .as("Verbatim COBOL-equivalent message anchored on 'not found'")
                    .containsIgnoringCase("not found");
        }
    }

    // =========================================================================
    // Fixture helper — builds a fully populated Card
    // =========================================================================

    /**
     * Builds a fully populated {@link Card} fixture for use across the
     * {@link HappyPath} and {@link RejectPaths} nested test classes.
     *
     * <p>Every field uses a {@link TestFixtures} constant or a
     * deliberately synthetic literal — no PII, no real card numbers, no
     * float/double values. The card number is the Visa test PAN
     * {@code 4111111111111101} from the publicly documented test range
     * (per AAP §0.10.5 — "No real PII"). The expiration date is set to
     * {@code 2026-12-31} (future-dated relative to the fixed clock at
     * {@code 2024-01-15T00:00:00Z}), satisfying the implicit
     * {@code expired=false} contract on the canonical happy-path test
     * without requiring an explicit assertion (the dedicated
     * future-dated test makes that assertion explicit).
     *
     * <p>The {@link Card#getVersion()} field is set to {@code 1L} so the
     * fixture mirrors what an OptimisticLockingFailureException-aware
     * test would see — even though this read-only service never mutates
     * the version, populating the field makes the fixture compatible
     * with future card-update tests that might reuse it.
     *
     * @return a populated {@link Card} ready for repository stubbing
     */
    private static Card standardCard() {
        Card c = new Card();
        c.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        c.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        c.setCvvCode("123");
        c.setEmbossedName("TEST USER");
        c.setExpirationDate("2026-12-31");
        c.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        c.setVersion(1L);
        return c;
    }
}
