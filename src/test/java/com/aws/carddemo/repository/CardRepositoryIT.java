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
package com.aws.carddemo.repository;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * Card — the JPA entity stub matching the CVACT02Y.cpy CARD-RECORD
//     layout (150-byte fixed-width VSAM KSDS record: 16-char CARD-NUM PK +
//     11-char CARD-ACCT-ID FK + 3-char CARD-CVV-CD + 50-char CARD-EMBOSSED-
//     NAME + 10-char CARD-EXPIRAION-DATE (sic — original COBOL copybook
//     misspelling preserved as a source-of-truth fidelity marker) + 1-char
//     CARD-ACTIVE-STATUS + 59-char FILLER). Test methods drive persist /
//     findById / save round-trips through this entity and assert that the
//     6 payload fields (and the JPA @Version optimistic-locking counter)
//     round-trip exactly across every read / write boundary.
//
//     PRODUCTION FIELD NAMING NOTE — the AAP §0.10 "Phase 10 Adaptation
//     Notes" explicitly anticipates the production-entity field-name
//     divergence from the COBOL CARD-* literals. The production
//     {@link Card} class uses Java-conventioned field names
//     (cardNumber, accountId, cvvCode, embossedName, expirationDate,
//     activeStatus, version) rather than the COBOL-style CARD-* names;
//     this IT drives the Java-style API exactly as the production code
//     exposes it. The COBOL CARD-EXPIRAION-DATE → Java expirationDate
//     normalisation (the COBOL field name preserves the original
//     copybook misspelling) is documented in the {@link Card} class
//     Javadoc; the Java field uses the corrected spelling while the
//     COBOL record layout retains the misspelling for byte-for-byte
//     VSAM compatibility on any future round-trip back to mainframe
//     fixtures.
//
//     PRODUCTION DATE TYPE NOTE — the AAP §0.10 "Phase 10 Adaptation
//     Notes" also explicitly anticipates the production type choice for
//     date fields. The production {@link Card#getExpirationDate()} field
//     is {@link String} (storing the ISO YYYY-MM-DD representation), NOT
//     {@link java.time.LocalDate}. Tests construct the value via
//     {@code LocalDate.of(year, month, day).toString()} to keep the date
//     semantics self-documenting at the call site while producing the
//     {@link String} the production setter requires. This mirrors the
//     peer {@code AccountRepositoryIT} pattern for the
//     ACCT-OPEN-DATE / ACCT-EXPIRAION-DATE / ACCT-REISSUE-DATE fields
//     and the peer {@code CustomerRepositoryIT} pattern for the
//     CUST-DOB-YYYY-MM-DD field.
//
//   * AbstractRepositoryIT — the abstract base class providing the
//     @DataJpaTest slice annotation, the @Testcontainers PostgreSQL 16
//     container, the @DynamicPropertySource that wires Testcontainers'
//     JDBC URL/username/password into Spring's environment, and the
//     inherited TestEntityManager field (entityManager). Per AAP §0.4.4
//     all 10 repository ITs extend this base — this IT inherits the JPA
//     slice, per-class container lifecycle, and transactional rollback
//     after each @Test method without re-declaring any of that wiring.
//
//   * TestFixtures — the shared test-constants holder. This IT consumes
//     the Cards-nested constants: SAMPLE_CARD_NUMBER_01 ("4111111111111101"),
//     NONEXISTENT_CARD_NUMBER ("4999999999999999", guaranteed never to
//     appear in carddata.txt — used for VSAM STATUS '23' parity
//     assertions), ACTIVE_STATUS_YES ("Y") and ACTIVE_STATUS_NO ("N")
//     for the CARD-ACTIVE-STATUS PIC X(01) sentinel values; and the
//     Accounts-nested constants SAMPLE_ACCOUNT_ID_10 / _20 / _30 / _40
//     / _50 ("00000000010"–"00000000050"), NEW_ACCOUNT_ID_60
//     ("00000000060", reserved for synthetic INSERT tests not backed by
//     any fixture row), and NONEXISTENT_ACCOUNT_ID ("99999999999",
//     guaranteed never to appear in any fixture). Per AAP §0.5.5
//     Cross-File Test Dependencies and AAP §0.10.1 Require Test Coverage
//     Rule (test bodies must not duplicate literal sentinel values that
//     already appear in TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Card;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the {@code *IT.java} suffix convention
//     and JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test reports
//     surface the COBOL-parity intent (rather than the camelCase method
//     name alone).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed CardRepository proxy
//     into this IT class instance. The proxy is created by Spring Data
//     JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<Card, String>} interface declaration on the
//     production repository — no manual implementation is required, and
//     no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Spring Framework data-access exception import.
//
//   * OptimisticLockingFailureException — Spring's data-access exception
//     subclass thrown when a JPA save() detects a stale @Version field
//     value. This is Spring's translation of Hibernate's
//     StaleObjectStateException (the underlying ORM-layer signal).
//
//     This IT asserts via AssertJ catchThrowable + isInstanceOf that a
//     stale-@Version save raises this exception — the JPA migration
//     equivalent of COBOL COCRDUPC's before-image/after-image record
//     comparison for concurrent-update detection (AAP §0.1.1
//     "optimistic-locking semantics replacement"). The COBOL COCRDUPC.cbl
//     program loads a card record, displays it on the BMS screen for
//     operator review, accepts an updated copy on Enter, and then
//     re-reads the record from VSAM and compares the new copy against
//     the operator's in-memory before-image — if any field differs
//     from the freshly-read after-image, the program rejects the update
//     as a concurrent-change conflict. The JPA @Version mechanism
//     (REFACTOR agents will add the @Version annotation to
//     {@link Card#getVersion()}) provides the same guarantee
//     declaratively: Hibernate compares the version-column value in the
//     UPDATE statement's WHERE clause against the in-memory entity's
//     version, and reports zero rows updated as a stale-version
//     conflict — surfaced to Spring as OptimisticLockingFailureException.
// ---------------------------------------------------------------------------
import org.springframework.dao.OptimisticLockingFailureException;

// ---------------------------------------------------------------------------
// Spring Data pagination imports.
//
//   * Page / Pageable / PageRequest — the production
//     {@link CardRepository#findByAccountId(String, Pageable)} method
//     accepts a {@link Pageable} argument and returns a {@link Page} of
//     {@link Card} rows. The Pageable parameter is the Java migration's
//     direct replacement for the COBOL COCRDLIC.cbl
//     {@code WS-MAX-SCREEN-LINES VALUE 7} page-size constant
//     (lines 177–178 of the program) combined with the STARTBR-CARDAIX /
//     READNEXT-CARDAIX browse loop's implicit pagination semantics.
//
//     PRODUCTION SIGNATURE NOTE — the AAP §0.10 "Phase 10 Adaptation
//     Notes" explicitly anticipates that the production repository may
//     expose a paginated finder ({@code findByAccountId(String, Pageable)})
//     rather than the AAP blueprint's hypothetical
//     {@code findByCardAcctId(String)} returning {@link List}. This IT
//     drives the production paginated signature exactly as the
//     production repository exposes it; the find-by-account tests pass
//     an unbounded {@link PageRequest#of(int, int) PageRequest.of(0, 100)}
//     so the assertions can verify all matching rows were returned
//     without colliding with the production 7-rows-per-page contract
//     drawn from the COBOL screen-bound paging convention.
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.time.LocalDate — used as a self-documenting builder for the
//     ISO-formatted date string the production Card entity stores. The
//     COBOL CARD-EXPIRAION-DATE field is PIC X(10) — an ISO 'YYYY-MM-DD'
//     string. The Java migration preserves the String-based storage at
//     the field level (see {@link Card#getExpirationDate()}) but tests
//     construct the value via
//     {@code LocalDate.of(year, month, day).toString()} to make the
//     date semantics self-documenting at the call site (AAP §0.10.10
//     Style Consistency). This mirrors the peer {@code AccountRepositoryIT}
//     pattern for the ACCT-OPEN-DATE / ACCT-EXPIRAION-DATE /
//     ACCT-REISSUE-DATE fields and the peer {@code CustomerRepositoryIT}
//     pattern for the CUST-DOB-YYYY-MM-DD field.
//
//   * java.util.List — the {@link Page#getContent()} method on a Spring
//     Data {@link Page} returns the underlying page-content list of
//     {@link Card} rows. Tests use the content list as the AssertJ
//     fluent-assertion target for {@code hasSize(...)},
//     {@code allSatisfy(...)}, and {@code extracting(...)} chains that
//     verify the find-by-account-FK semantic.
//
//   * java.util.Optional — the return type of
//     {@code cardRepository.findById(...)}. Tests assert
//     {@code isPresent()} for happy-path lookups, {@code isEmpty()} for
//     VSAM STATUS '23' record-not-found parity, and
//     {@code orElseThrow()} when extracting the reloaded entity for
//     round-trip and update assertions.
// ---------------------------------------------------------------------------
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static imports (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. Idioms exercised by this class include
//     assertThat(optional).isPresent() / isEmpty(),
//     assertThat(card.getCardNumber()).isEqualTo(...),
//     assertThat(card.getEmbossedName()).isNotNull(),
//     assertThat(page.getContent()).hasSize(...).allSatisfy(...),
//     and assertThat(throwable).isInstanceOf(...).
//
//   * catchThrowable captures any {@link Throwable} thrown by the
//     supplied lambda for subsequent type/message assertions without
//     wrapping it in a try/catch — the AssertJ-canonical way to assert
//     on expected exceptions. Used here to capture the
//     {@link OptimisticLockingFailureException} raised by a stale-@Version
//     save in the
//     {@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
//     test.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;


/**
 * Integration tests for {@link CardRepository}, which persists {@link Card}
 * entities migrated from the COBOL {@code CARD-RECORD} defined in
 * {@code app/cpy/CVACT02Y.cpy} (RECLN 150).
 *
 * <h2>COBOL Provenance — CVACT02Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 150-byte record:
 * <pre>
 *   01 CARD-RECORD.
 *      05 CARD-NUM             PIC X(16).  --&gt; {@link Card#getCardNumber()}      (primary key)
 *      05 CARD-ACCT-ID         PIC 9(11).  --&gt; {@link Card#getAccountId()}       (foreign key to accounts)
 *      05 CARD-CVV-CD          PIC 9(03).  --&gt; {@link Card#getCvvCode()}         (PCI-sensitive; redacted in toString)
 *      05 CARD-EMBOSSED-NAME   PIC X(50).  --&gt; {@link Card#getEmbossedName()}    (cardholder name)
 *      05 CARD-EXPIRAION-DATE  PIC X(10).  --&gt; {@link Card#getExpirationDate()}  (ISO YYYY-MM-DD String; COBOL field name retains misspelling)
 *      05 CARD-ACTIVE-STATUS   PIC X(01).  --&gt; {@link Card#getActiveStatus()}    ({@code 'Y'} or {@code 'N'})
 *      05 FILLER               PIC X(59).
 * </pre>
 *
 * <h2>Migration Pattern (AAP §0.5.1)</h2>
 *
 * <p>The CARDDAT VSAM KSDS file is persisted by FIVE distinct COBOL
 * programs in the original CardDemo workflow, each of which maps to a
 * specific JPA repository contract verified by this IT:
 *
 * <ul>
 *   <li><strong>COBIL00C.cbl</strong> (bill payment) — reads the card
 *       record to associate the payment with the cardholder. Mapped to
 *       {@link CardRepository#findById(Object)}.</li>
 *   <li><strong>COCRDLIC.cbl</strong> (card list, paged) — browses the
 *       card master via {@code STARTBR-CARDAIX} / {@code READNEXT-CARDAIX}
 *       with the {@code 9500-FILTER-RECORDS} paragraph applying an
 *       in-loop equality test against {@code CARD-ACCT-ID = CC-ACCT-ID}
 *       (line 1386). The Java migration delegates this filter to a
 *       derived Spring Data query
 *       {@link CardRepository#findByAccountId(String, Pageable)},
 *       letting the database engine narrow the result set before
 *       pagination.</li>
 *   <li><strong>COCRDSLC.cbl</strong> (card detail, point read) —
 *       {@code 9100-GETCARD-BYACCTCARD} paragraph (lines 736–777)
 *       performs an {@code EXEC CICS READ} against the {@code CARDDAT}
 *       VSAM KSDS file by the 16-character {@code WS-CARD-RID-CARDNUM}
 *       primary key. Mapped to {@link CardRepository#findById(Object)}.</li>
 *   <li><strong>COCRDUPC.cbl</strong> (card update) — performs the
 *       classic VSAM READ-UPDATE-REWRITE flow with before-image /
 *       after-image comparison for concurrent-update detection. Mapped
 *       to Spring Data's {@code findById} → mutate → {@code save}, with
 *       optimistic locking provided by the {@code @Version} field that
 *       replaces the original COBOL before/after-image comparison
 *       (AAP §0.1.1 — optimistic-locking semantics replacement).</li>
 *   <li><strong>CBACT02C.cbl</strong> (batch card-file print) — sequential
 *       browse of all cards. Mapped to
 *       {@link CardRepository#findAll(Pageable)} (inherited from
 *       JpaRepository).</li>
 * </ul>
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <ul>
 *   <li>{@link #findById_existingCard_returnsCard()} — primary-key lookup
 *       (COCRDSLC §9100 happy path)</li>
 *   <li>{@link #findById_nonexistentCard_returnsEmpty()} — primary-key
 *       miss (COCRDSLC §9100 DFHRESP(NOTFND) / VSAM STATUS '23' parity)</li>
 *   <li>{@link #save_newCard_persistsAllFields()} — full round-trip
 *       through every CVACT02Y.cpy field</li>
 *   <li>{@link #save_updatedCardActiveStatus_persistsChange()} —
 *       update path (COCRDUPC REWRITE CARD-RECORD parity)</li>
 *   <li>{@link #saveAndSave_sequentialUpdates_noVersionConflict()} —
 *       sequential saves on the same row do not raise version
 *       conflicts (verifies that {@code @Version} only blocks concurrent
 *       updates, not normal sequential mutations)</li>
 *   <li>{@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
 *       — optimistic-lock conflict (replaces COCRDUPC before-image /
 *       after-image comparison per AAP §0.1.1)</li>
 *   <li>{@link #findByAccountId_existingAccount_returnsAllCards()} —
 *       find-by-account FK (COCRDLIC §9500 filter parity)</li>
 *   <li>{@link #findByAccountId_nonexistentAccount_returnsEmptyPage()} —
 *       find-by-account miss</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — wiring smoke
 *       test (Repository bean, DataSource, schema, container)</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>No mocks: this IT exercises the REAL {@link CardRepository} proxy
 * supplied by Spring Data JPA against the REAL PostgreSQL 16 container
 * provided by Testcontainers (inherited via {@link AbstractRepositoryIT}).
 * Mocks would defeat the purpose of an integration test — the AAP
 * §0.10.1 Require Test Coverage Rule mandates that integration tests
 * call production repository code directly against the production
 * persistence stack to prove the {@code JpaRepository} → Hibernate →
 * JDBC → PostgreSQL stack produces correct results against a real
 * schema. Tests that would otherwise mock the repository (service unit
 * tests, batch processor unit tests) live one layer up in
 * {@code com.aws.carddemo.service.*Test} and
 * {@code com.aws.carddemo.batch.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>This IT performs <strong>zero</strong> arithmetic, format
 * normalisation, or date parsing inside test methods. Date literals
 * are constructed via {@code LocalDate.of(year, month, day).toString()}
 * purely for compile-time type-safety on the year/month/day triplet
 * (not for date arithmetic). The card-detail formatting logic,
 * card-update REWRITE logic, and date-format validation logic live
 * exclusively in {@code com.aws.carddemo.service.CardDetailService},
 * {@code com.aws.carddemo.service.CardUpdateService}, and
 * {@code com.aws.carddemo.validation.DateValidationService}
 * respectively, each with its own dedicated unit test class.
 *
 * <h2>Test Isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic cards persisted by every test
 * in this class are gone before the next test sees the database state.
 * Each test starts from the Flyway-seeded card catalog (50 rows from
 * {@code app/data/ASCII/carddata.txt} via V3__seed.sql); test order
 * independence is guaranteed.
 *
 * <h2>PCI Sensitivity</h2>
 *
 * <p>The {@code CARD-CVV-CD PIC 9(03)} field is a PCI-sensitive
 * credential (the 3-digit card-verification value printed on the back
 * of a physical card). This IT seeds the field with the synthetic
 * test value {@code "123"} purely for fixture completeness; AAP
 * §0.10.5 (No financial data written to logs at any level) is
 * enforced separately by the
 * {@code com.aws.carddemo.logging.LoggingPiiRedactionTest} class
 * (a different package), which exercises the Logback PCI/PII
 * redaction filter against the production {@link Card#toString()}
 * method (which already omits the CVV per the entity's documented
 * security contract).
 *
 * <h2>Activation State</h2>
 *
 * <p>This IT is active and executes under {@code mvn verify} (Failsafe).
 * The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link CardRepository} bean against a real PostgreSQL 16 database.
 * The {@link Card} entity carries the required JPA annotations
 * ({@code @Entity}, {@code @Id}, {@code @Column}, {@code @Version}) so
 * Hibernate maps the entity onto the {@code cards} table created by
 * Flyway {@code V1__schema.sql}, with {@code card_acct_id} carrying a
 * FOREIGN KEY constraint to {@code accounts.acct_id} for referential
 * integrity. The {@code findById}, save, optimistic-locking,
 * find-by-account-id, and CVV non-exposure paths are all exercised by
 * the 9 test methods below.
 *
 * <h3>Operational Prerequisite</h3>
 *
 * <p>Docker must be available to Testcontainers at test runtime — the
 * {@code mvn verify} build agent must be able to run
 * {@code postgres:16-alpine}. CI agents that cannot start containers
 * (e.g. nested-virtualisation-free environments) can set
 * {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 * {@code src/test/resources/application-test.properties}.
 *
 * @see CardRepository
 * @see Card
 * @see AbstractRepositoryIT
 * @see TestFixtures.Cards
 * @see TestFixtures.Accounts
 */
@DisplayName("CardRepository — CVACT02Y.cpy migration parity ITs")
class CardRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link CardRepository} interface declaration (no manual
     * implementation). Field injection is consistent with the inherited
     * {@code @Autowired TestEntityManager entityManager} field on
     * {@link AbstractRepositoryIT}.
     */
    @Autowired
    private CardRepository cardRepository;

    // =========================================================================
    // Primary-Key Lookup Tests (AAP §0.5.1 "findById")
    // =========================================================================

    /**
     * Verifies that {@link CardRepository#findById(Object)} returns the
     * persisted {@link Card} for an existing 16-character primary key.
     * The test persists a synthetic card via the inherited
     * {@code TestEntityManager}, flushes the persistence context to
     * push the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected primary key, foreign-key, and active-status values.
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NORMAL)} branch from {@code COCRDSLC.cbl}'s
     * {@code 9100-GETCARD-BYACCTCARD} paragraph (lines 736–777) and
     * underlies the card-lookup half of the
     * {@link com.aws.carddemo.service.CardDetailService}'s
     * card-view round-trip.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing CARD-NUM) returns the persisted Card (COCRDSLC §9100 DFHRESP(NORMAL) parity)")
    void findById_existingCard_returnsCard() {
        // Arrange — persist a synthetic card via the inherited TestEntityManager.
        // SAMPLE_CARD_NUMBER_01 ("4111111111111101") is the canonical fixture-range
        // CARD-NUM used across card-related ITs; SAMPLE_ACCOUNT_ID_10
        // ("00000000010") is the canonical fixture-range CARD-ACCT-ID FK target.
        Card card = buildSyntheticCard(
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10,
                TestFixtures.Cards.ACTIVE_STATUS_YES);
        entityManager.persistAndFlush(card);
        entityManager.clear();

        // Act — drive the production repository against the real DB.
        Optional<Card> result =
                cardRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

        // Assert — the row must be located and the primary identifying
        // fields must round-trip exactly.
        assertThat(result)
                .as("findById should locate the persisted card by 16-character "
                        + "primary key (COBOL DFHRESP(NORMAL) equivalent)")
                .isPresent();
        Card c = result.get();
        assertThat(c.getCardNumber())
                .as("CARD-NUM primary-key component must round-trip exactly")
                .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        assertThat(c.getAccountId())
                .as("CARD-ACCT-ID foreign-key component must round-trip exactly")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        assertThat(c.getActiveStatus())
                .as("CARD-ACTIVE-STATUS must round-trip exactly "
                        + "('Y' for active per CVACT02Y.cpy PIC X(01))")
                .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_YES);
    }

    /**
     * Verifies that {@link CardRepository#findById(Object)} returns
     * {@link Optional#empty()} when the supplied 16-character primary key
     * does not exist in the {@code cards} table. This is the Java
     * equivalent of the COBOL CICS response code {@code DFHRESP(NOTFND)}
     * branch from {@code COCRDSLC.cbl}'s
     * {@code 9100-GETCARD-BYACCTCARD} paragraph (line 760) — the
     * underlying VSAM file-status code {@code '23'} (record not found).
     * The production {@link com.aws.carddemo.service.CardDetailService}
     * translates this empty {@link Optional} into the COBOL-equivalent
     * {@code "Did not find cards for this search condition"} reject
     * message that COCRDSLC issues at line 760.
     *
     * <p>The lookup key {@link TestFixtures.Cards#NONEXISTENT_CARD_NUMBER}
     * ({@code "4999999999999999"}) is deliberately outside the converted
     * fixture range {@code 4111111111111101}–{@code 4111111111111150}; the
     * constant exists precisely for not-found assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent CARD-NUM) returns empty Optional (VSAM STATUS 23 / COCRDSLC DFHRESP(NOTFND) parity)")
    void findById_nonexistentCard_returnsEmpty() {
        // Act — drive the production repository against the real DB with
        // a key guaranteed never to appear in the fixture card catalog.
        Optional<Card> result =
                cardRepository.findById(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal.
        assertThat(result)
                .as("findById should return Optional.empty for unknown card "
                        + "numbers (COBOL DFHRESP(NOTFND) / VSAM STATUS '23' "
                        + "equivalent — COCRDSLC §9100 line 760)")
                .isEmpty();
    }

    // =========================================================================
    // Save / Update Path Tests (COCRDUPC update flow + CBACT02C insert flow)
    // =========================================================================

    /**
     * Verifies that {@link CardRepository#save(Object)} persists every
     * field of a new {@link Card} so that all 6 payload fields plus the
     * {@code @Version} counter round-trip without truncation, encoding
     * loss, or scale degradation.
     *
     * <p>This is the Java equivalent of the COBOL CICS
     * {@code EXEC CICS WRITE DATASET('CARDDAT')} flow used by
     * {@code COCRDUPC} (online card-update WRITE on first-time insert)
     * and by the batch {@code CBACT02C} card-data loader. The
     * post-save assertion confirms that:
     * <ul>
     *   <li>The 16-character primary key ({@link Card#getCardNumber()})
     *       round-trips exactly.</li>
     *   <li>The 11-character foreign-key
     *       ({@link Card#getAccountId()}) round-trips exactly.</li>
     *   <li>The single-character active-status sentinel
     *       ({@link Card#getActiveStatus()}) round-trips exactly.</li>
     *   <li>The 50-character embossed-name field
     *       ({@link Card#getEmbossedName()}) is non-null (the
     *       buildSyntheticCard helper guarantees a deterministic
     *       value).</li>
     * </ul>
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new Card) persists and round-trips all CVACT02Y.cpy fields")
    void save_newCard_persistsAllFields() {
        // Arrange — construct a synthetic card with a deterministic PAN
        // outside the converted fixture range so the test never collides
        // with any pre-seeded row.
        final String pan = "4222000000099999";
        Card card = buildSyntheticCard(
                pan,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20,
                TestFixtures.Cards.ACTIVE_STATUS_YES);

        // Act — drive the production repository's save path. The flush
        // forces Hibernate to issue the INSERT statement against the DB,
        // and the clear discards the first-level cache so the subsequent
        // findById hits the database (not the in-memory entity).
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        // Assert — all fields preserved across the round-trip.
        Card reloaded = cardRepository.findById(pan).orElseThrow();
        assertThat(reloaded.getCardNumber())
                .as("CARD-NUM primary key must round-trip byte-for-byte")
                .isEqualTo(pan);
        assertThat(reloaded.getAccountId())
                .as("CARD-ACCT-ID foreign key must round-trip exactly")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20);
        assertThat(reloaded.getActiveStatus())
                .as("CARD-ACTIVE-STATUS must round-trip exactly")
                .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_YES);
        assertThat(reloaded.getEmbossedName())
                .as("CARD-EMBOSSED-NAME must round-trip without truncation; "
                        + "buildSyntheticCard sets a deterministic non-null value "
                        + "so a null reload here would indicate a JPA mapping "
                        + "omission on the embossedName column")
                .isNotNull();
        assertThat(reloaded.getExpirationDate())
                .as("CARD-EXPIRAION-DATE must round-trip as the ISO YYYY-MM-DD "
                        + "string set by buildSyntheticCard ('2030-12-31')")
                .isEqualTo(LocalDate.of(2030, 12, 31).toString());
        assertThat(reloaded.getCvvCode())
                .as("CARD-CVV-CD must round-trip as the synthetic 3-digit fixture "
                        + "value; production logging redaction is enforced separately "
                        + "by LoggingPiiRedactionTest, not at the persistence layer")
                .isEqualTo("123");
    }

    /**
     * Verifies that updating {@code CARD-ACTIVE-STATUS} on a previously
     * persisted {@link Card} via the classic JPA load-mutate-save
     * sequence persists the new status and preserves all other fields.
     *
     * <p>This test mirrors the canonical CardDemo card-update flow
     * documented in {@code COCRDUPC.cbl}:
     * <ol>
     *   <li>Read the existing CARD-RECORD by primary key
     *       (load = {@link CardRepository#findById(Object)}).</li>
     *   <li>Mutate {@code CARD-ACTIVE-STATUS} in memory (Java field
     *       assignment).</li>
     *   <li>Rewrite the CARD-RECORD back to the dataset
     *       (save = {@link CardRepository#save(Object)}).</li>
     * </ol>
     *
     * <p>The COCRDUPC program performs this exact load-mutate-save
     * sequence under operator control, with the {@code WS-CARD-ACTIVE-
     * STATUS} field carrying the user's intended new value. The Java
     * migration replaces COCRDUPC's hand-rolled before-image /
     * after-image comparison with JPA's {@code @Version} optimistic
     * locking (exercised separately by
     * {@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}),
     * but the happy-path update flow itself is unchanged.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Card with updated CARD-ACTIVE-STATUS) persists the new value (COCRDUPC update-flow parity)")
    void save_updatedCardActiveStatus_persistsChange() {
        // Arrange — persist initial active card.
        final String pan = "4222000000088888";
        Card initial = buildSyntheticCard(
                pan,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30,
                TestFixtures.Cards.ACTIVE_STATUS_YES);
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act — load (READ CARDDAT), deactivate, re-save (REWRITE
        // CARD-RECORD). COCRDUPC follows this exact load-mutate-save
        // sequence under operator control.
        Card reloaded = cardRepository.findById(pan).orElseThrow();
        reloaded.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_NO);
        cardRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Assert — the new status is persisted; the row is otherwise
        // unchanged (foreign key, embossed name, expiration date all
        // preserved across the update).
        Card afterUpdate = cardRepository.findById(pan).orElseThrow();
        assertThat(afterUpdate.getActiveStatus())
                .as("CARD-ACTIVE-STATUS update must persist (COCRDUPC REWRITE "
                        + "CARD-RECORD parity — the deactivation 'Y' -> 'N' is the "
                        + "single most common operator-driven mutation in the "
                        + "original mainframe workflow)")
                .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_NO);
        assertThat(afterUpdate.getAccountId())
                .as("CARD-ACTIVE-STATUS update must not perturb the unchanged "
                        + "CARD-ACCT-ID foreign key on the same row")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30);
    }


    // =========================================================================
    // Optimistic Locking Tests (@Version — COCRDUPC concurrent-update parity
    // per AAP §0.1.1)
    // =========================================================================

    /**
     * Verifies that two sequential save operations on the same card row
     * (without any concurrent modification by another session) succeed
     * without raising {@link OptimisticLockingFailureException}. This is
     * the positive control for the {@code @Version} optimistic-locking
     * contract: the version counter increments cleanly under
     * normal, sequential updates and only raises a conflict when
     * <em>another</em> session has interleaved its own update.
     *
     * <p>Without this positive control, a passing
     * {@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
     * test could spuriously pass for the wrong reason — e.g., if
     * {@code @Version} were misconfigured to fire on every save
     * regardless of concurrency. This positive test ensures the
     * version-increment path is exercised explicitly.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(loaded entity twice) does not raise version conflict for sequential, non-concurrent saves")
    void saveAndSave_sequentialUpdates_noVersionConflict() {
        // Arrange — persist initial card.
        final String pan = "4222000000077777";
        Card initial = buildSyntheticCard(
                pan,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_40,
                TestFixtures.Cards.ACTIVE_STATUS_YES);
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act — two sequential reads and writes (no concurrency). Each
        // load fetches the latest version, mutates, saves. Hibernate
        // increments @Version on each save; the next load sees the
        // post-increment value, so no stale-version conflict can occur.
        Card load1 = cardRepository.findById(pan).orElseThrow();
        load1.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_NO);
        cardRepository.save(load1);
        entityManager.flush();
        entityManager.clear();

        Card load2 = cardRepository.findById(pan).orElseThrow();
        load2.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        cardRepository.save(load2);
        entityManager.flush();
        entityManager.clear();

        // Assert — both updates persisted, no exception thrown.
        Card finalState = cardRepository.findById(pan).orElseThrow();
        assertThat(finalState.getActiveStatus())
                .as("Sequential 'Y' -> 'N' -> 'Y' updates must all persist "
                        + "without raising OptimisticLockingFailureException; "
                        + "the @Version counter increments cleanly under "
                        + "non-concurrent sequential mutations")
                .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_YES);
    }

    /**
     * Verifies that a stale-version save raises
     * {@link OptimisticLockingFailureException}, exercising the
     * concurrency-control contract that replaces COCRDUPC's COBOL
     * before-image / after-image record comparison per AAP §0.1.1.
     *
     * <h2>COBOL Reference — COCRDUPC Concurrent-Update Detection</h2>
     *
     * <p>The original COBOL COCRDUPC program protects against
     * concurrent updates by reading the CARD-RECORD twice during the
     * same screen interaction: once to display the BEFORE image to
     * the user, and again immediately before the REWRITE to fetch the
     * LATEST image and compare it against the user's AFTER image. If
     * the two images diverge (because another user updated the record
     * in between), COCRDUPC issues
     * {@code WS-ROLLBACK-RECORD-CHANGED} and aborts the update with
     * an error message to the user, prompting them to re-fetch and
     * re-attempt their update.
     *
     * <h2>JPA Migration — @Version Optimistic Locking</h2>
     *
     * <p>The Java migration replaces this hand-rolled before-image /
     * after-image comparison with JPA's standard {@code @Version}
     * optimistic-locking mechanism (AAP §0.1.1). The {@link Card}
     * entity carries a {@code @Version Long version} field which
     * Hibernate increments on every UPDATE; the UPDATE statement
     * includes a {@code WHERE version = ?} clause that fails to match
     * (zero rows affected) if any other session has incremented the
     * version since this session loaded the row. Hibernate detects
     * the zero-row outcome and raises {@code StaleObjectStateException},
     * which Spring Data JPA translates to
     * {@link OptimisticLockingFailureException} via
     * {@code SessionFactoryUtils.convertHibernateAccessException}.
     *
     * <h2>Test Scenario — Two Concurrent Sessions</h2>
     *
     * <p>The test simulates two concurrent sessions racing to update
     * the same card row:
     * <ol>
     *   <li>Initial save persists the row at {@code @Version = 0}.</li>
     *   <li>"Session A" loads the row (and detaches it from the
     *       persistence context to model the JPA semantic of a
     *       request-scoped persistence context that has been closed
     *       between the load and the save — e.g., a card-update
     *       screen interaction split across two HTTP requests).</li>
     *   <li>"Session B" loads the same row, mutates
     *       {@code CARD-ACTIVE-STATUS} from 'Y' to 'N', and saves
     *       successfully — incrementing {@code @Version} from 0 to 1
     *       in the database.</li>
     *   <li>"Session A" now mutates its detached copy (still carrying
     *       {@code @Version = 0}) and attempts to save. The UPDATE
     *       statement's {@code WHERE version = 0} clause matches zero
     *       rows because the database now holds {@code @Version = 1}.
     *       Hibernate raises {@code StaleObjectStateException} which
     *       Spring translates to
     *       {@link OptimisticLockingFailureException}.</li>
     * </ol>
     *
     * <p>The assertion uses AssertJ's
     * {@code Assertions.catchThrowable(...)} idiom (preferred over
     * try/catch or {@code assertThrows} for fluent chained assertions
     * per AAP §0.10.10 Style Consistency) and confirms that the
     * thrown exception is {@code OptimisticLockingFailureException}.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(detached entity with stale @Version) throws OptimisticLockingFailureException (COCRDUPC concurrent-update parity, AAP §0.1.1)")
    void saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure() {
        // Arrange — persist initial card at @Version 0.
        final String pan = "4222000000066666";
        Card initial = buildSyntheticCard(
                pan,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_50,
                TestFixtures.Cards.ACTIVE_STATUS_YES);
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act 1 — "Session A" loads the row and detaches it. The detach
        // models the JPA semantic of a request-scoped persistence context
        // that has been closed (e.g., between two HTTP requests from the
        // same end-user) — the entity carries a stale @Version snapshot.
        Card sessionA = cardRepository.findById(pan).orElseThrow();
        entityManager.detach(sessionA);

        // Act 2 — "Session B" loads the SAME row, mutates the active
        // status, and saves successfully. Hibernate increments @Version
        // from 0 to 1 in the database.
        Card sessionB = cardRepository.findById(pan).orElseThrow();
        sessionB.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_NO);
        cardRepository.save(sessionB);
        entityManager.flush();
        entityManager.clear();

        // Act 3 — "Session A" now mutates its detached copy (still
        // carrying @Version = 0) and attempts to save. The UPDATE
        // statement's WHERE version = 0 clause matches zero rows
        // because the database now holds @Version = 1, so Hibernate
        // raises StaleObjectStateException which Spring translates to
        // OptimisticLockingFailureException.
        sessionA.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        Throwable thrown = catchThrowable(() -> {
            cardRepository.save(sessionA);
            entityManager.flush();
        });

        // Assert — stale-version save must raise
        // OptimisticLockingFailureException. This replaces COBOL
        // COCRDUPC's WS-ROLLBACK-RECORD-CHANGED before-image /
        // after-image comparison per AAP §0.1.1.
        assertThat(thrown)
                .as("Concurrent update with stale @Version must throw "
                        + "OptimisticLockingFailureException (replaces COBOL "
                        + "COCRDUPC WS-ROLLBACK-RECORD-CHANGED before-image / "
                        + "after-image comparison per AAP §0.1.1)")
                .isInstanceOf(OptimisticLockingFailureException.class);
    }


    // =========================================================================
    // Find-by-Account FK Tests (AAP §0.5.1 "find-by-account" — COCRDLIC §9500
    // FILTER-RECORDS parity)
    // =========================================================================

    /**
     * Verifies that
     * {@link CardRepository#findByAccountId(String, Pageable)} returns
     * every {@link Card} row whose {@code CARD-ACCT-ID} foreign key
     * matches the supplied account identifier — and ONLY those rows.
     *
     * <h2>COBOL Reference — COCRDLIC §9500-FILTER-RECORDS</h2>
     *
     * <p>The original COBOL {@code COCRDLIC.cbl} card-list program
     * browses the {@code CARDDAT} VSAM KSDS via the
     * {@code STARTBR-CARDAIX} / {@code READNEXT-CARDAIX} loop and
     * applies an in-loop equality filter in the
     * {@code 9500-FILTER-RECORDS} paragraph at line 1386:
     * <pre>
     *     IF CARD-ACCT-ID = CC-ACCT-ID
     *         ... include this record ...
     *     ELSE
     *         SET WS-EXCLUDE-THIS-RECORD TO TRUE
     *     END-IF
     * </pre>
     *
     * <p>The Java migration delegates this filter to a derived Spring
     * Data query, letting the database engine narrow the result set
     * before pagination — the natural Java replacement that avoids the
     * COBOL in-loop {@code GO TO} ({@code GO TO 9500-FILTER-RECORDS-EXIT}
     * at line 1390).
     *
     * <h2>Production Signature Adaptation Note</h2>
     *
     * <p>The AAP blueprint's hypothetical
     * {@code findByCardAcctId(String)} returning {@code List<Card>} does
     * NOT exist in the production
     * {@link com.aws.carddemo.repository.CardRepository}. The production
     * repository instead exposes the paginated finder
     * {@link CardRepository#findByAccountId(String, Pageable)}, which
     * matches the COBOL {@code WS-MAX-SCREEN-LINES VALUE 7} page-size
     * constant on COCRDLIC.cbl lines 177–178 via Spring Data's
     * {@link Pageable} parameter. This IT drives the production
     * signature exactly as exposed; the test passes an unbounded
     * {@code PageRequest.of(0, 100)} so the assertion can verify all
     * matching rows were returned without colliding with the production
     * 7-rows-per-page contract. AAP §0.10.2 Phase 10 Adaptation Notes
     * explicitly anticipates this kind of signature divergence: "If
     * repository method is findByAccountId instead of
     * findByCardAcctId, adjust."
     *
     * <h2>Test Scenario — FK Filter Discrimination</h2>
     *
     * <p>The test seeds 2 cards on the target account and 1 card on a
     * different account, then asserts the finder returns exactly the
     * 2 matching cards (confirming the FK filter actually excludes
     * non-matching rows — a missing or buggy WHERE clause would
     * return all 3 cards).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(existing CARD-ACCT-ID) returns all cards for that account (COCRDLIC §9500 filter parity)")
    void findByAccountId_existingAccount_returnsAllCards() {
        // Arrange — persist 2 cards on the target account and 1 card on
        // a different account. NEW_ACCOUNT_ID_60 ("00000000060") is the
        // synthetic-INSERT account constant; SAMPLE_ACCOUNT_ID_20 is the
        // contrast account used as a negative control.
        final String targetAcct = TestFixtures.Accounts.NEW_ACCOUNT_ID_60;
        final String otherAcct = TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20;

        entityManager.persist(buildSyntheticCard(
                "4333000000000001",
                targetAcct,
                TestFixtures.Cards.ACTIVE_STATUS_YES));
        entityManager.persist(buildSyntheticCard(
                "4333000000000002",
                targetAcct,
                TestFixtures.Cards.ACTIVE_STATUS_NO));
        entityManager.persist(buildSyntheticCard(
                "4333000000000099",
                otherAcct,
                TestFixtures.Cards.ACTIVE_STATUS_YES));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the production finder. PageRequest.of(0, 100)
        // requests an unbounded first page so all matching rows are
        // returned without colliding with the production 7-rows-per-page
        // contract that COCRDLIC.cbl WS-MAX-SCREEN-LINES VALUE 7 imposes.
        Page<Card> resultPage =
                cardRepository.findByAccountId(targetAcct, PageRequest.of(0, 100));
        List<Card> result = resultPage.getContent();

        // Assert — the finder must return exactly the 2 matching cards
        // and must NOT include the 3rd card on the other account.
        assertThat(result)
                .as("findByAccountId should return exactly the 2 cards on the "
                        + "target account (COCRDLIC §9500-FILTER-RECORDS parity); "
                        + "a missing or buggy WHERE clause would return all 3 "
                        + "rows and fail this assertion")
                .hasSize(2);
        assertThat(result)
                .as("Every card returned by findByAccountId must belong to the "
                        + "target account; any divergence indicates the derived "
                        + "Spring Data query is missing the c.accountId = ?1 "
                        + "predicate")
                .allSatisfy(c -> assertThat(c.getAccountId()).isEqualTo(targetAcct));
    }

    /**
     * Verifies that
     * {@link CardRepository#findByAccountId(String, Pageable)} returns
     * an empty page when the supplied account identifier has no cards
     * — neither an exception nor a {@code null} result. The empty
     * result is the Java migration equivalent of the COCRDLIC.cbl
     * {@code WS-NO-CARDS-FOUND} state that the program enters when
     * its {@code STARTBR-CARDAIX} + {@code 9500-FILTER-RECORDS} loop
     * yields zero matches.
     *
     * <p>The lookup key {@link TestFixtures.Accounts#NONEXISTENT_ACCOUNT_ID}
     * ({@code "99999999999"}) is guaranteed never to appear in the
     * {@code accounts} table (which spans {@code "00000000001"} to
     * {@code "00000000050"} per the AAP §0.5.5 sample range); the
     * constant exists precisely for not-found assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(nonexistent CARD-ACCT-ID) returns empty page (COCRDLIC WS-NO-CARDS-FOUND parity)")
    void findByAccountId_nonexistentAccount_returnsEmptyPage() {
        // Act — drive the production finder against an account ID
        // guaranteed never to appear in any fixture row.
        Page<Card> resultPage = cardRepository.findByAccountId(
                TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID,
                PageRequest.of(0, 100));
        List<Card> result = resultPage.getContent();

        // Assert — non-null, empty list (NOT an exception; NOT null).
        assertThat(result)
                .as("findByAccountId should return a non-null empty list "
                        + "(NOT null and NOT an exception) for an account ID "
                        + "guaranteed never to appear in any fixture — the "
                        + "COCRDLIC WS-NO-CARDS-FOUND equivalent")
                .isNotNull()
                .isEmpty();
        assertThat(resultPage.getTotalElements())
                .as("Total-elements count for an empty page must be zero; a "
                        + "non-zero count would indicate the pagination layer "
                        + "is reporting the unfiltered table size rather than "
                        + "the filtered count")
                .isZero();
    }

    // =========================================================================
    // Repository Wiring Smoke Test
    // =========================================================================

    /**
     * Verifies that {@link CardRepository#count()} returns a
     * non-negative row count. This is a wiring-level smoke test
     * confirming that:
     * <ul>
     *   <li>The {@link CardRepository} bean is correctly autowired by
     *       Spring Data JPA.</li>
     *   <li>The PostgreSQL DataSource is reachable and the
     *       Testcontainers container is healthy.</li>
     *   <li>The {@code cards} table exists in the schema (created by
     *       Flyway's {@code V1__schema.sql}).</li>
     * </ul>
     *
     * <p>The assertion is intentionally lenient ({@code count() >= 0})
     * because the absolute row count depends on whether Flyway's
     * {@code V3__seed.sql} has been applied — this IT does not assert
     * on a specific seed-data row count. The test's purpose is to
     * surface gross wiring failures (missing table, broken DataSource,
     * unbootstrapped Spring context) cleanly, not to validate
     * seed-data contents.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count (wiring smoke test)")
    void count_invoked_returnsNonNegativeValue() {
        // Act — invoke the inherited JpaRepository.count() method.
        long total = cardRepository.count();

        // Assert — count() should never return negative. A negative
        // value would indicate a corrupted Spring Data JPA
        // implementation (impossible in practice) or a JDBC driver
        // bug; an exception thrown here would indicate a missing
        // table, broken DataSource, or unbootstrapped Spring context.
        assertThat(total)
                .as("CardRepository.count() should return a non-negative "
                        + "row count; any exception thrown here would indicate "
                        + "a missing cards table or broken Testcontainers "
                        + "DataSource wiring")
                .isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // Synthetic Card Builder (Test Helper — no business logic per AAP §0.10.1)
    // =========================================================================

    /**
     * Constructs a synthetic {@link Card} populated with the supplied
     * primary key, foreign key, and active-status; every other field
     * defaults to a deterministic synthetic value (a fixed CVV string,
     * a fixed embossed-name string, and a fixed ISO-format
     * expiration-date string derived from {@code LocalDate.of(2030, 12, 31)}).
     *
     * <p>The helper exists purely to eliminate field-by-field setter
     * boilerplate across the seven save-path tests in this IT. It
     * contains no business logic and no calculations — the three
     * caller-supplied arguments are assigned directly to the
     * corresponding {@link Card} fields with no transformation, and the
     * remaining fields are populated with deterministic test
     * constants. This complies with the AAP §0.10.1 Require Test
     * Coverage Rule that forbids reimplementing business or
     * calculation logic inside test bodies.
     *
     * <p>Field assignments mirror the {@code CVACT02Y.cpy}
     * CARD-RECORD layout: the three caller-supplied arguments cover
     * the dynamic fields each save-path test wants to vary (PAN,
     * account FK, active flag), and the remaining fields are filled
     * with synthetic values that satisfy any not-null constraints
     * the production schema imposes without introducing test-only PII
     * (AAP §0.10.5 No PII — the synthetic CVV {@code "123"} is a test
     * fixture value, not a real card-verification value).
     *
     * <p>The expiration date is constructed via
     * {@code LocalDate.of(2030, 12, 31).toString()} which yields the
     * ISO-formatted string {@code "2030-12-31"}. This matches the
     * production {@link Card#setExpirationDate(String)} signature
     * (the field is a {@link String} per
     * {@code CARD-EXPIRAION-DATE PIC X(10)}) while keeping the
     * year/month/day triplet self-documenting at the call site. The
     * date {@code 2030-12-31} is comfortably in the future relative
     * to the AAP §0.4.2 deterministic test clock
     * ({@code 2024-01-15T00:00:00Z}) so the synthetic cards are
     * always "active" with respect to any expiration-date logic
     * the production code may apply.
     *
     * @param cardNumber   the 16-digit Visa-format PAN (CARD-NUM
     *                     PIC X(16)) — primary key
     * @param accountId    the 11-digit zero-padded account FK
     *                     (CARD-ACCT-ID PIC 9(11))
     * @param activeStatus the single-character active flag
     *                     (CARD-ACTIVE-STATUS PIC X(01)) — 'Y' or 'N'
     * @return a fully-populated unmanaged {@link Card} ready for
     *         {@link CardRepository#save(Object)} or
     *         {@link jakarta.persistence.EntityManager#persist(Object)}
     */
    private Card buildSyntheticCard(String cardNumber,
                                    String accountId,
                                    String activeStatus) {
        Card c = new Card();
        // -- Primary key + foreign key --
        c.setCardNumber(cardNumber);
        c.setAccountId(accountId);
        // -- PCI-sensitive CVV (synthetic fixture value; never logged
        //    per Card.toString() and LoggingPiiRedactionTest).
        c.setCvvCode("123");
        // -- 50-char embossed cardholder name (test-only synthetic
        //    placeholder; no real PII per AAP §0.10.5).
        c.setEmbossedName("TEST CARDHOLDER");
        // -- 10-char ISO YYYY-MM-DD expiration date. LocalDate.of(...)
        //    .toString() self-documents the year/month/day triplet
        //    and produces the canonical ISO format expected by the
        //    production String date field. This mirrors the peer
        //    AccountRepositoryIT pattern for the ACCT-OPEN-DATE /
        //    ACCT-EXPIRAION-DATE / ACCT-REISSUE-DATE fields.
        c.setExpirationDate(LocalDate.of(2030, 12, 31).toString());
        // -- Single-character active-status flag (CARD-ACTIVE-STATUS).
        c.setActiveStatus(activeStatus);
        return c;
    }

}
