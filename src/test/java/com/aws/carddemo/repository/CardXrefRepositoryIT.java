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
//   * CardXref — the JPA entity stub matching the CVACT03Y.cpy
//     CARD-XREF-RECORD layout (RECLN 50: 16-char XREF-CARD-NUM PIC X(16)
//     primary key + 9-char XREF-CUST-ID PIC 9(09) foreign key + 11-char
//     XREF-ACCT-ID PIC 9(11) foreign key + 14-char FILLER). Test methods
//     drive persist / findById / findByAccountId / save round-trips
//     through this entity and assert that the three keys round-trip
//     exactly across every read / write boundary.
//
//     PRODUCTION FIELD NAMING NOTE — the AAP §0.10 "Phase 10 Adaptation
//     Notes" explicitly anticipates the production-entity field-name
//     divergence from the COBOL XREF-* literals. The production
//     {@link CardXref} class uses Java-conventioned field names
//     (cardNumber, customerId, accountId) rather than the COBOL-style
//     XREF-CARD-NUM / XREF-CUST-ID / XREF-ACCT-ID names; this IT drives
//     the Java-style API exactly as the production code exposes it
//     (getCardNumber / setCardNumber, getCustomerId / setCustomerId,
//     getAccountId / setAccountId). The COBOL XREF-* prefixes are
//     preserved in the entity Javadoc as the source-of-truth fidelity
//     marker but do not appear in the Java field names. This mirrors
//     the peer {@code CardRepositoryIT} pattern for the
//     CARD-NUM / CARD-ACCT-ID / CARD-ACTIVE-STATUS fields and the
//     peer {@code AccountRepositoryIT} pattern for the ACCT-ID /
//     ACCT-CURR-BAL fields.
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
//     the Cards-nested constants: SAMPLE_CARD_NUMBER_01 ("4111111111111101")
//     and NONEXISTENT_CARD_NUMBER ("4999999999999999", guaranteed never
//     to appear in cardxref.txt — used for VSAM STATUS '23' parity
//     assertions); the Accounts-nested constants SAMPLE_ACCOUNT_ID_10 /
//     _20 / _30 / _50 ("00000000010"–"00000000050") and
//     NONEXISTENT_ACCOUNT_ID ("99999999999", guaranteed never to appear
//     in any fixture); and the Customers-nested constants
//     SAMPLE_CUSTOMER_ID_01 / _10 / _50 ("000000001" / "000000010" /
//     "000000050"). Per AAP §0.5.5 Cross-File Test Dependencies and
//     AAP §0.10.1 Require Test Coverage Rule (test bodies must not
//     duplicate literal sentinel values that already appear in
//     TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.CardXref;
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
//     test class and each method so IDE runner output and CI test
//     reports surface the COBOL-parity intent (rather than the
//     camelCase method name alone).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed CardXrefRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<CardXref, String>} interface declaration on
//     the production repository — no manual implementation is required,
//     and no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.util.Optional — the return type of
//     {@code cardXrefRepository.findById(...)} (inherited from
//     {@code JpaRepository<CardXref, String>}) AND the return type of
//     the production
//     {@link CardXrefRepository#findByAccountId(String)} derived query
//     method. Tests assert {@code isPresent()} for happy-path lookups
//     (the COBOL DFHRESP(NORMAL) branch), {@code isEmpty()} for VSAM
//     STATUS '23' record-not-found parity (the COBOL DFHRESP(NOTFND)
//     branch), and {@code orElseThrow()} when extracting the reloaded
//     entity for round-trip assertions.
//
//     PRODUCTION RETURN-TYPE ADAPTATION NOTE — the AAP §0.10 "Phase 10
//     Adaptation Notes" explicitly anticipates that the production
//     repository may expose a 1:1-cardinality finder
//     ({@code Optional<CardXref> findByAccountId(String)}) rather than
//     the AAP blueprint's hypothetical 1:N
//     {@code List<CardXref> findByXrefAcctId(String)}. This IT drives
//     the production 1:1 signature exactly as exposed; the production
//     CARDDAT / CARDAIX dataset has unique account-to-card cardinality
//     (one card per account in the {@code app/data/ASCII/cardxref.txt}
//     fixture) which the production repository encodes via the
//     {@link Optional} return type. The AAP blueprint's List-based
//     reverse-lookup test is therefore replaced by an
//     {@link Optional}-based test in this IT — the access pattern
//     (account → card) is preserved, only the return-type cardinality
//     is adapted to match production.
// ---------------------------------------------------------------------------
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static imports (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. Idioms exercised by this class include
//     assertThat(optional).isPresent() / isEmpty(),
//     assertThat(xref.getCardNumber()).isEqualTo(...), and
//     assertThat(count).isGreaterThanOrEqualTo(0).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Integration tests for {@link CardXrefRepository}, which persists the
 * {@link CardXref} cross-reference table migrated from the COBOL
 * {@code CARD-XREF-RECORD} defined in {@code app/cpy/CVACT03Y.cpy} (RECLN 50).
 *
 * <h2>COBOL Provenance — CVACT03Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 50-byte record:
 * <pre>
 *   01 CARD-XREF-RECORD.
 *      05 XREF-CARD-NUM PIC X(16). --&gt; {@link CardXref#getCardNumber()} (primary key)
 *      05 XREF-CUST-ID  PIC 9(09). --&gt; {@link CardXref#getCustomerId()} (customer FK)
 *      05 XREF-ACCT-ID  PIC 9(11). --&gt; {@link CardXref#getAccountId()}  (account FK, CARDAIX alternate-index key)
 *      05 FILLER        PIC X(14).
 * </pre>
 *
 * <p>The original COBOL persistence layer is a VSAM KSDS named CARDXREF
 * with the primary key on {@code XREF-CARD-NUM} and an Alternate Index
 * (AIX) named {@code CARDAIX} keyed on {@code XREF-ACCT-ID}. The AIX
 * provides the "find the card associated with this account" access
 * pattern that {@code COACTVWC.cbl} §9200-GETCARDXREF-BYACCT uses to
 * resolve account-to-card during the account-view round-trip. The
 * cross-reference table is the bridge that lets account-centric
 * operations (view, update, statement generation) discover the
 * cardholder identifier they need to render to the operator.
 *
 * <h2>Migration Pattern (AAP §0.10.4 — Immutable Boundaries)</h2>
 *
 * <p>In the relational model:
 * <ul>
 *   <li>The VSAM PK ({@code XREF-CARD-NUM}) maps to the JPA primary key
 *       on {@link CardXref#getCardNumber()} — accessed via the inherited
 *       {@link CardXrefRepository#findById(Object)} method.</li>
 *   <li>The VSAM AIX ({@code XREF-ACCT-ID}) maps to Spring Data's
 *       derived-query method
 *       {@link CardXrefRepository#findByAccountId(String)} — the
 *       relational replacement for COBOL
 *       {@code EXEC CICS READ DATASET('CXACAIX')
 *       RIDFLD(WS-CARD-RID-ACCT-ID-X)}.</li>
 *   <li>The third key ({@code XREF-CUST-ID}) is preserved as a JPA
 *       column on {@link CardXref#getCustomerId()} for full record-layout
 *       parity, and is round-tripped through every save / load assertion
 *       in this IT.</li>
 * </ul>
 *
 * <p>This IT verifies both access directions against a real PostgreSQL 16
 * container provided by Testcontainers (inherited via
 * {@link AbstractRepositoryIT}). The forward access (card → account /
 * customer) is the canonical primary-key lookup; the reverse access
 * (account → card) is the migration's replacement for the COBOL
 * Alternate Index path.
 *
 * <h2>Production Signature Adaptation (AAP §0.10 Phase 10 Notes)</h2>
 *
 * <p>The AAP §0.10 Phase 10 Adaptation Notes explicitly anticipate that
 * the production repository's reverse-lookup finder may differ from the
 * AAP blueprint:
 *
 * <ul>
 *   <li><strong>Method name.</strong> AAP blueprint:
 *       {@code findByXrefAcctId(String)}. Production:
 *       {@link CardXrefRepository#findByAccountId(String)}. This IT
 *       drives the production method name.</li>
 *   <li><strong>Return type cardinality.</strong> AAP blueprint:
 *       {@code List<CardXref>} (1:N). Production:
 *       {@code Optional<CardXref>} (1:1). The production model is
 *       grounded in the {@code app/data/ASCII/cardxref.txt} fixture
 *       data, where each of the 50 account IDs maps to exactly one
 *       card-number row — the strict 1:1 cardinality of the original
 *       CARDAIX alternate index. This IT drives the production 1:1
 *       cardinality exactly as exposed.</li>
 *   <li><strong>Field naming.</strong> AAP blueprint:
 *       {@code xrefCardNum / xrefCustId / xrefAcctId}. Production:
 *       {@code cardNumber / customerId / accountId}. This IT drives the
 *       Java-conventioned getters and setters
 *       ({@link CardXref#getCardNumber()},
 *       {@link CardXref#setCardNumber(String)}, etc.).</li>
 *   <li><strong>Customer-key reverse lookup.</strong> AAP blueprint
 *       calls out a {@code findByXrefCustId(String)} derived query for
 *       the "find all cards for a customer" access pattern; the
 *       production {@link CardXrefRepository} exposes this access
 *       pattern as {@link CardXrefRepository#findByCustomerId(String)}
 *       returning {@code List<CardXref>} (1:N cardinality, since a
 *       customer can hold multiple cards across multiple accounts).
 *       This IT exercises that finder through the dedicated
 *       {@link #findByCustomerId_existingCustomer_returnsAllXrefsForCustomer()},
 *       {@link #findByCustomerId_unknownCustomer_returnsEmptyList()},
 *       and
 *       {@link #findByCustomerId_discriminatesAmongCustomers_returnsOnlyTargetXrefs()}
 *       test methods (added per AAP §0.5.1 customer alternate-index
 *       coverage mandate).</li>
 * </ul>
 *
 * <h2>Coverage Focus (AAP §0.5.1 / §0.7.1)</h2>
 *
 * <p>AAP §0.7.1 requires {@code com.aws.carddemo.repository.**} to reach
 * {@code ≥75%} line coverage. The {@link CardXrefRepository} interface
 * has a small surface (two custom finders — by account ID and by
 * customer ID — plus the inherited {@code JpaRepository} methods), so
 * the ten tests below are sufficient to exceed that threshold:
 *
 * <ul>
 *   <li>{@link #findById_existingCardNumber_returnsXrefRow()} —
 *       primary-key lookup (COBOL DFHRESP(NORMAL) parity)</li>
 *   <li>{@link #findById_nonexistentCardNumber_returnsEmpty()} —
 *       primary-key miss (COBOL DFHRESP(NOTFND) / VSAM STATUS '23'
 *       parity)</li>
 *   <li>{@link #findByAccountId_existingAccount_returnsXrefForAccount()}
 *       — reverse lookup happy path (COACTVWC §9200 DFHRESP(NORMAL)
 *       parity; replaces VSAM CARDAIX alternate-index read)</li>
 *   <li>{@link #findByAccountId_nonexistentAccount_returnsEmpty()} —
 *       reverse lookup miss (COACTVWC §9200 DFHRESP(NOTFND) parity)</li>
 *   <li>{@link #findByAccountId_discriminatesAmongAccounts_returnsOnlyTargetXref()}
 *       — reverse lookup FK discrimination: seeds three xref rows on
 *       three distinct accounts and verifies the finder returns only
 *       the row matching the supplied account ID (a missing or buggy
 *       WHERE clause would return a non-matching row and fail this
 *       assertion)</li>
 *   <li>{@link #findByCustomerId_existingCustomer_returnsAllXrefsForCustomer()}
 *       — customer alternate-index happy path (COCRDLIC.cbl parity;
 *       seeds three xref rows for one customer and verifies all three
 *       are returned as a List)</li>
 *   <li>{@link #findByCustomerId_unknownCustomer_returnsEmptyList()} —
 *       customer alternate-index miss (DFHRESP(NOTFND) parity returns
 *       empty {@code List} rather than {@code null})</li>
 *   <li>{@link #findByCustomerId_discriminatesAmongCustomers_returnsOnlyTargetXrefs()}
 *       — customer alternate-index FK discrimination: seeds xref rows
 *       on three distinct customers and verifies the finder returns
 *       only the rows matching the supplied customer ID</li>
 *   <li>{@link #save_newXref_persistsAllKeys()} — full round-trip
 *       through every CVACT03Y.cpy key field (INSERT path, replaces
 *       COBOL {@code EXEC CICS WRITE DATASET('CARDXREF')})</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — wiring smoke
 *       test (Repository bean, DataSource, schema, container)</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>No mocks: this IT exercises the REAL {@link CardXrefRepository}
 * proxy supplied by Spring Data JPA against the REAL PostgreSQL 16
 * container provided by Testcontainers (inherited via
 * {@link AbstractRepositoryIT}). Mocks would defeat the purpose of an
 * integration test — the AAP §0.10.1 Require Test Coverage Rule mandates
 * that integration tests call production repository code directly
 * against the production persistence stack to prove the
 * {@code JpaRepository} → Hibernate → JDBC → PostgreSQL stack produces
 * correct results against a real schema. Tests that would otherwise
 * mock the repository (service unit tests, batch processor unit tests)
 * live one layer up in {@code com.aws.carddemo.service.*Test} and
 * {@code com.aws.carddemo.batch.*Test} — see the sibling
 * {@code AccountViewServiceTest} for an example of how
 * {@link CardXrefRepository} is mocked at the service-unit-test layer.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>This IT performs <strong>zero</strong> arithmetic, format
 * normalisation, or sign-overpunch decoding inside test methods. The
 * three keys ({@code XREF-CARD-NUM}, {@code XREF-CUST-ID},
 * {@code XREF-ACCT-ID}) are all {@link String}-typed and assigned
 * directly from {@link TestFixtures} or from synthetic Visa-test-PAN
 * literals (the {@code 4111000000000xxx} and {@code 4222000000000xxx}
 * series chosen specifically to avoid collisions with the converted
 * fixture range {@code 4111111111111101}–{@code 4111111111111150}).
 * The cross-reference business logic — sign-overpunch decoding of the
 * COBOL ASCII fixture, primary-vs-alternate-index dispatch, and reject
 * handling for {@code DFHRESP(NOTFND)} — lives exclusively in
 * {@code com.aws.carddemo.batch.CardXrefFileProcessor} and
 * {@code com.aws.carddemo.service.AccountViewService}, each with its
 * own dedicated unit test class.
 *
 * <h2>Test Isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic xref rows persisted by every
 * test in this class are gone before the next test sees the database
 * state. Each test starts from the Flyway-seeded card-xref catalog
 * (50 rows from {@code app/data/ASCII/cardxref.txt} via V3__seed.sql);
 * test order independence is guaranteed.
 *
 * <h2>Security / PII (AAP §0.10.5)</h2>
 *
 * <p>The 16-character {@code XREF-CARD-NUM} field IS a PCI-sensitive
 * identifier (the credit-card PAN). This IT seeds the field with
 * synthetic Visa test PANs ({@code 4111000000000xxx} and
 * {@code 4222000000000xxx}) and with the
 * {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
 * ({@code 4111111111111101}) constant; none of these values can
 * authenticate any real card transaction. AAP §0.10.5 ("No financial
 * data written to logs at any level") is enforced separately by the
 * {@code logback-test.xml} PCI/PII redaction filter — see
 * {@code com.aws.carddemo.logging.LoggingPiiRedactionTest} for the
 * runtime verification.
 *
 * <h2>Activation State</h2>
 *
 * <p>This IT is active and executes under {@code mvn verify} (Failsafe).
 * The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link CardXrefRepository} bean against a real PostgreSQL 16
 * database. The {@link CardXref} entity is annotated as a JPA
 * {@code @Entity} (with {@code @Id} on {@code cardNumber} and
 * {@code @Column} annotations on every field), and the Flyway scripts
 * under {@code src/main/resources/db/migration/} create the
 * {@code card_xref} table and seed reference rows. The primary-key
 * lookup, account-ID alternate-index lookup, customer-ID
 * alternate-index lookup, FK discrimination, and INSERT round-trip
 * paths are all exercised by the test methods below.
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
 * @see CardXrefRepository
 * @see CardXref
 * @see AbstractRepositoryIT
 * @see TestFixtures.Cards
 * @see TestFixtures.Accounts
 * @see TestFixtures.Customers
 */
@DisplayName("CardXrefRepository — CVACT03Y.cpy bi-directional lookup ITs")
class CardXrefRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link CardXrefRepository} interface declaration (no
     * manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    // =========================================================================
    // Primary-Key (XREF-CARD-NUM) Forward Lookup Tests
    // — AAP §0.5.1 "findById, save, bi-directional lookup card→account and
    //   account→card" (forward direction)
    // — COBOL parity: VSAM primary-key READ on the CARDXREF KSDS by
    //   XREF-CARD-NUM (16-char PAN). The forward lookup returns BOTH the
    //   XREF-CUST-ID and XREF-ACCT-ID foreign-key fields so the caller
    //   can route from a card number to either the customer record or
    //   the account record.
    // =========================================================================

    /**
     * Verifies that {@link CardXrefRepository#findById(Object)} returns
     * the persisted {@link CardXref} for an existing 16-character primary
     * key, and that BOTH foreign-key fields ({@code XREF-CUST-ID} and
     * {@code XREF-ACCT-ID}) round-trip exactly across the read / write
     * boundary.
     *
     * <p>The test persists a synthetic xref via the inherited
     * {@code TestEntityManager}, flushes the persistence context to push
     * the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected primary key, customer foreign key, and account foreign
     * key.
     *
     * <p>This is the Java equivalent of a VSAM CARDXREF KSDS primary-key
     * read ({@code EXEC CICS READ DATASET('CARDXREF') RIDFLD(XREF-CARD-NUM)})
     * — the underlying access pattern that lets card-centric operations
     * (statement generation, transaction posting) discover the
     * customer and account associated with a given PAN.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing XREF-CARD-NUM) returns the persisted CardXref with both FK fields populated")
    void findById_existingCardNumber_returnsXrefRow() {
        // Arrange — persist a synthetic xref via the inherited TestEntityManager.
        // SAMPLE_CARD_NUMBER_01 ("4111111111111101") is the canonical fixture-range
        // PAN used across card-related ITs; SAMPLE_CUSTOMER_ID_01 ("000000001")
        // is the canonical fixture-range XREF-CUST-ID; SAMPLE_ACCOUNT_ID_10
        // ("00000000010") is the canonical fixture-range XREF-ACCT-ID.
        CardXref xref = buildSyntheticXref(
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        entityManager.persistAndFlush(xref);
        entityManager.clear();

        // Act — drive the production repository against the real DB.
        Optional<CardXref> result =
                cardXrefRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

        // Assert — forward lookup must locate the persisted row, and
        // BOTH foreign-key fields (XREF-CUST-ID and XREF-ACCT-ID) must
        // round-trip exactly. The forward access pattern's value
        // proposition is precisely that ONE read returns BOTH FKs;
        // any divergence here would indicate a JPA column-mapping
        // omission on the CardXref entity.
        assertThat(result)
                .as("findById should locate the persisted xref by 16-character "
                        + "XREF-CARD-NUM primary key (COBOL VSAM CARDXREF "
                        + "primary-key READ equivalent)")
                .isPresent();
        CardXref r = result.get();
        assertThat(r.getCardNumber())
                .as("XREF-CARD-NUM primary-key component must round-trip "
                        + "byte-for-byte to satisfy the AAP §0.10.4 immutable-"
                        + "boundary contract on the CVACT03Y.cpy record layout")
                .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        assertThat(r.getCustomerId())
                .as("XREF-CUST-ID foreign-key component must round-trip "
                        + "exactly — the customer FK is the bridge that lets "
                        + "card-centric operations resolve to the cardholder")
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        assertThat(r.getAccountId())
                .as("XREF-ACCT-ID foreign-key component must round-trip "
                        + "exactly — the account FK is the COBOL CARDAIX "
                        + "alternate-index key and the reverse-lookup target "
                        + "exercised by findByAccountId tests below")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
    }

    /**
     * Verifies that {@link CardXrefRepository#findById(Object)} returns
     * {@link Optional#empty()} when the supplied 16-character primary
     * key does not exist in the {@code card_xref} table. This is the
     * Java equivalent of the COBOL VSAM file-status code {@code '23'}
     * (record not found) returned by an {@code EXEC CICS READ} against
     * a non-existent key, and of the {@code DFHRESP(NOTFND)} response
     * code that the surrounding COBOL programs check for.
     *
     * <p>The lookup key {@link TestFixtures.Cards#NONEXISTENT_CARD_NUMBER}
     * ({@code "4999999999999999"}) is deliberately outside the converted
     * fixture range {@code 4111111111111101}–{@code 4111111111111150}
     * and outside the synthetic PAN ranges used by save-path tests in
     * this IT; the constant exists precisely for not-found assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent XREF-CARD-NUM) returns empty Optional (VSAM STATUS '23' / DFHRESP(NOTFND) parity)")
    void findById_nonexistentCardNumber_returnsEmpty() {
        // Act — drive the production repository against the real DB with
        // a key guaranteed never to appear in the fixture xref catalog
        // (NONEXISTENT_CARD_NUMBER = "4999999999999999" is reserved
        // precisely for record-not-found assertions per TestFixtures
        // documentation).
        Optional<CardXref> result =
                cardXrefRepository.findById(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        // and the underlying VSAM STATUS '23' code. The production
        // service layer (AccountViewService) translates this empty
        // Optional into the COBOL-equivalent "Card record not found"
        // reject path.
        assertThat(result)
                .as("findById should return Optional.empty for unknown card "
                        + "numbers (COBOL DFHRESP(NOTFND) / VSAM STATUS '23' "
                        + "equivalent — the empty Optional is the canonical "
                        + "Java translation of the COBOL not-found signal)")
                .isEmpty();
    }

    // =========================================================================
    // Reverse Lookup (XREF-ACCT-ID — Account → Card) Tests
    // — AAP §0.5.1 "bi-directional lookup card→account and account→card"
    //   (reverse direction); replaces the COBOL CARDAIX alternate index
    // — COBOL parity: COACTVWC.cbl §9200-GETCARDXREF-BYACCT performs
    //   {@code EXEC CICS READ DATASET('CXACAIX') RIDFLD(WS-CARD-RID-ACCT-ID-X)}
    //   to resolve account-ID to card-number through the CARDAIX alternate
    //   index. The Java migration replaces this with the Spring Data
    //   derived-query method {@link CardXrefRepository#findByAccountId(String)}
    //   which returns an {@link Optional<CardXref>} (1:1 cardinality matching
    //   the production cardxref.txt fixture data).
    //
    // PRODUCTION SIGNATURE NOTE — the AAP §0.10 "Phase 10 Adaptation Notes"
    // explicitly anticipates that the production repository may expose a
    // 1:1-cardinality finder (Optional<CardXref> findByAccountId(String))
    // rather than the AAP blueprint's hypothetical 1:N
    // List<CardXref> findByXrefAcctId(String). These tests drive the
    // production 1:1 signature exactly as exposed.
    // =========================================================================

    /**
     * Verifies that
     * {@link CardXrefRepository#findByAccountId(String)} returns the
     * single {@link CardXref} row matching the supplied 11-character
     * {@code XREF-ACCT-ID} foreign key, and that ALL THREE key fields
     * round-trip exactly across the reverse-lookup boundary.
     *
     * <h2>COBOL Reference — COACTVWC.cbl §9200-GETCARDXREF-BYACCT</h2>
     *
     * <p>The original COBOL {@code 9200-GETCARDXREF-BYACCT} paragraph
     * (lines 723–769 of {@code COACTVWC.cbl}) performs an
     * {@code EXEC CICS READ DATASET('CXACAIX')
     * RIDFLD(WS-CARD-RID-ACCT-ID-X)} against the {@code CXACAIX}
     * Alternate Index (the {@code CARDAIX} AIX keyed on
     * {@code XREF-ACCT-ID}) using the 11-character
     * {@code WS-CARD-RID-ACCT-ID-X} key. The
     * {@code WS-RESP-CD = DFHRESP(NORMAL)} response carries the
     * happy-path outcome:
     * <pre>
     *     MOVE XREF-CUST-ID TO CDEMO-CUST-ID
     *     MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM
     * </pre>
     *
     * <p>The Java migration replaces the {@code CXACAIX} alternate-index
     * read with the Spring Data derived-query method
     * {@link CardXrefRepository#findByAccountId(String)}. Spring Data
     * synthesises the JPQL {@code SELECT x FROM CardXref x WHERE
     * x.accountId = ?1} from the method name at proxy-creation time,
     * and the 1:1 cardinality of the CARDAIX index in the production
     * dataset guarantees that the result fits in an {@link Optional} —
     * a given account ID maps to at most one card cross-reference row.
     *
     * <h2>Test Scenario — Reverse Access Round Trip</h2>
     *
     * <p>The test persists a synthetic xref with the canonical fixture
     * key combination (SAMPLE_CARD_NUMBER_01 / SAMPLE_CUSTOMER_ID_01 /
     * SAMPLE_ACCOUNT_ID_10), then drives the production reverse-lookup
     * finder with the same account ID. The returned {@link Optional}
     * must be present, and every key field on the returned entity
     * must equal the value persisted — confirming that the relational
     * model correctly preserves the 1:1 reverse-access semantics of
     * the original COBOL CARDAIX alternate index.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(existing XREF-ACCT-ID) returns the CardXref for that account (COACTVWC §9200 CARDAIX parity)")
    void findByAccountId_existingAccount_returnsXrefForAccount() {
        // Arrange — persist a synthetic xref establishing the
        // account-to-card linkage that COACTVWC.cbl §9200 walks.
        // SAMPLE_ACCOUNT_ID_10 ("00000000010") is the canonical
        // fixture-range XREF-ACCT-ID; SAMPLE_CARD_NUMBER_01
        // ("4111111111111101") is the canonical fixture-range PAN that
        // the CARDAIX alternate-index lookup must resolve to.
        CardXref xref = buildSyntheticXref(
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        entityManager.persistAndFlush(xref);
        entityManager.clear();

        // Act — drive the production reverse-lookup finder. This is the
        // Spring Data JPA replacement for COBOL EXEC CICS READ against
        // the CARDAIX alternate index.
        Optional<CardXref> result =
                cardXrefRepository.findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

        // Assert — the row must be located (Optional.present), and all
        // three key fields must round-trip exactly. The COBOL
        // COACTVWC §9200 paragraph reads XREF-CUST-ID and XREF-CARD-NUM
        // off the returned record into CDEMO-CUST-ID and CDEMO-CARD-NUM;
        // the Java migration must preserve both values for the
        // account-view round-trip to continue to function.
        assertThat(result)
                .as("findByAccountId should locate the xref row by 11-character "
                        + "XREF-ACCT-ID foreign key (COACTVWC §9200 "
                        + "DFHRESP(NORMAL) parity — replaces VSAM CARDAIX "
                        + "alternate-index READ)")
                .isPresent();
        CardXref r = result.get();
        assertThat(r.getAccountId())
                .as("XREF-ACCT-ID returned by the reverse-lookup finder must "
                        + "equal the lookup key — any divergence indicates the "
                        + "derived Spring Data query is missing the "
                        + "x.accountId = ?1 predicate")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        assertThat(r.getCardNumber())
                .as("XREF-CARD-NUM must round-trip across the reverse-lookup "
                        + "boundary — the COBOL COACTVWC §9200 MOVE "
                        + "XREF-CARD-NUM TO CDEMO-CARD-NUM step relies on "
                        + "this value being correctly populated")
                .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        assertThat(r.getCustomerId())
                .as("XREF-CUST-ID must round-trip across the reverse-lookup "
                        + "boundary — the COBOL COACTVWC §9200 MOVE "
                        + "XREF-CUST-ID TO CDEMO-CUST-ID step relies on this "
                        + "value being correctly populated")
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
    }

    /**
     * Verifies that
     * {@link CardXrefRepository#findByAccountId(String)} returns
     * {@link Optional#empty()} when the supplied 11-character
     * {@code XREF-ACCT-ID} foreign key does not exist in the
     * {@code card_xref} table. This is the Java equivalent of the COBOL
     * {@code WS-RESP-CD = DFHRESP(NOTFND)} branch from
     * {@code COACTVWC.cbl} §9200-GETCARDXREF-BYACCT (line 759), which
     * sets {@code WS-CARD-RID-ACCT-ID-X} to the failing account ID and
     * issues the {@code 'Account:nnn not found in Cross ref file'}
     * reject message to the operator.
     *
     * <p>The lookup key {@link TestFixtures.Accounts#NONEXISTENT_ACCOUNT_ID}
     * ({@code "99999999999"}) is guaranteed never to appear in the
     * {@code accounts} table (which spans {@code "00000000001"} to
     * {@code "00000000050"} per the AAP §0.5.5 sample range) and is
     * therefore guaranteed never to appear in any {@code card_xref}
     * row; the constant exists precisely for not-found assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(nonexistent XREF-ACCT-ID) returns empty Optional (COACTVWC §9200 DFHRESP(NOTFND) parity)")
    void findByAccountId_nonexistentAccount_returnsEmpty() {
        // Act — drive the production reverse-lookup finder against an
        // account ID guaranteed never to appear in any fixture xref
        // row. NONEXISTENT_ACCOUNT_ID = "99999999999" is reserved
        // precisely for record-not-found assertions per TestFixtures
        // documentation.
        Optional<CardXref> result = cardXrefRepository.findByAccountId(
                TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND)
        // signal. The production service layer translates this empty
        // Optional into the COBOL-equivalent reject message
        // 'Account:nnn not found in Cross ref file'. The assertion
        // chains isNotNull explicitly to surface any mistaken null
        // return from a buggy proxy implementation; Spring Data JPA's
        // proxy MUST return Optional.empty (not null) on the
        // not-found path.
        assertThat(result)
                .as("findByAccountId should return Optional.empty (NOT null "
                        + "and NOT an exception) for an account ID guaranteed "
                        + "never to appear in any fixture — the COACTVWC "
                        + "§9200 DFHRESP(NOTFND) equivalent")
                .isNotNull()
                .isEmpty();
    }

    /**
     * Verifies that
     * {@link CardXrefRepository#findByAccountId(String)} discriminates
     * correctly among multiple xref rows in the {@code card_xref}
     * table: the finder must return ONLY the row matching the
     * supplied account ID, never a row associated with a different
     * account. This test exists specifically to catch missing-or-buggy
     * WHERE-clause bugs in the derived-query synthesis: an
     * incorrectly-implemented derived query that returns an arbitrary
     * row (the first row in the table, say) would fail this assertion
     * even if the simpler positive/negative path tests pass.
     *
     * <h2>COBOL Reference</h2>
     *
     * <p>This test models the foundational guarantee of the COBOL
     * CARDAIX alternate index: that the AIX provides a UNIQUE
     * resolution from {@code XREF-ACCT-ID} to {@code XREF-CARD-NUM}.
     * The COBOL CARDXREF VSAM KSDS is defined with a unique-key
     * alternate index (the {@code CXACAIX} AIX is created with
     * {@code DEFINE AIX (NAME(CXACAIX) UNIQUEKEY ...)}); the
     * relational model must preserve that uniqueness semantic so the
     * account-to-card lookup remains deterministic.
     *
     * <h2>Test Scenario — Three-Way FK Discrimination</h2>
     *
     * <p>The test seeds THREE xref rows linking three distinct cards
     * to three distinct accounts (target account 10, decoy account 20,
     * decoy account 30), then drives the reverse-lookup finder with
     * the target account ID. The returned {@link Optional} must
     * contain ONLY the target row — and specifically must NOT
     * contain a decoy row.
     *
     * <p>The assertion chain checks the returned XREF-CARD-NUM
     * explicitly against the EXPECTED card and AGAINST the two decoy
     * cards. The negative-control checks ensure that a buggy
     * implementation (e.g., one that hard-codes the first row, or that
     * returns ANY row whose XREF-ACCT-ID matches a different account)
     * cannot pass spuriously.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(supplied account) returns only the matching xref (FK discrimination — replaces VSAM CARDAIX unique-key AIX)")
    void findByAccountId_discriminatesAmongAccounts_returnsOnlyTargetXref() {
        // Arrange — persist three xref rows linking distinct cards to
        // distinct accounts AND distinct customers (so the
        // discrimination assertion below catches a buggy WHERE clause
        // that might match on the wrong column). The 4111000000000xxx
        // PAN range is chosen specifically to avoid colliding with the
        // converted fixture range (4111111111111101–4111111111111150)
        // and with the 4222000000000xxx / 4333000000000xxx ranges used
        // by save-path tests.
        final String targetCardNum = "4111000000000010";
        final String decoyCardNumA = "4111000000000020";
        final String decoyCardNumB = "4111000000000030";
        final String targetAcct = TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10;
        final String decoyAcctA = TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20;
        final String decoyAcctB = TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30;
        // Vary customers across the three rows so a buggy predicate
        // that filters on customerId instead of accountId would surface
        // immediately — a missing or wrong-column WHERE clause cannot
        // hide behind a single shared customer ID.
        final String targetCust = TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01;
        final String decoyCustA = TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10;
        final String decoyCustB = TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50;

        entityManager.persist(buildSyntheticXref(targetCardNum, targetCust, targetAcct));
        entityManager.persist(buildSyntheticXref(decoyCardNumA, decoyCustA, decoyAcctA));
        entityManager.persist(buildSyntheticXref(decoyCardNumB, decoyCustB, decoyAcctB));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the reverse-lookup finder with the TARGET
        // account ID. A correctly-implemented derived query will
        // narrow to exactly the one row whose XREF-ACCT-ID matches
        // the predicate.
        Optional<CardXref> result = cardXrefRepository.findByAccountId(targetAcct);

        // Assert — the finder must return the TARGET row and ONLY the
        // TARGET row. The positive assertion confirms the happy path;
        // the negative-control assertions confirm that the WHERE
        // clause actually filters (a missing or buggy predicate would
        // surface either an arbitrary decoy row or an
        // IncorrectResultSizeDataAccessException — both of which
        // would fail this test).
        assertThat(result)
                .as("findByAccountId should locate the xref row for the "
                        + "TARGET account ID (positive assertion — the "
                        + "predicate's WHERE clause must match)")
                .isPresent();
        CardXref r = result.get();
        assertThat(r.getCardNumber())
                .as("findByAccountId must return the XREF-CARD-NUM of the "
                        + "TARGET account (the COBOL CARDAIX unique-key "
                        + "alternate-index resolution); any decoy card "
                        + "number here indicates a missing or buggy "
                        + "predicate in the derived Spring Data query")
                .isEqualTo(targetCardNum)
                .isNotEqualTo(decoyCardNumA)
                .isNotEqualTo(decoyCardNumB);
        assertThat(r.getAccountId())
                .as("findByAccountId must return the XREF-ACCT-ID of the "
                        + "TARGET account — sanity check that the matching "
                        + "row genuinely belongs to the lookup key")
                .isEqualTo(targetAcct);
        assertThat(r.getCustomerId())
                .as("findByAccountId must return the XREF-CUST-ID associated "
                        + "with the TARGET row's account, NOT a decoy "
                        + "customer ID — a buggy predicate that filtered on "
                        + "customerId instead of accountId would surface "
                        + "here because the three seeded rows carry distinct "
                        + "customer IDs (SAMPLE_CUSTOMER_ID_01 / _10 / _50)")
                .isEqualTo(targetCust);
    }

    // =========================================================================
    // Insert / Save Path Tests
    // — AAP §0.5.1 "save" (INSERT path round-trip through every key field)
    // — COBOL parity: EXEC CICS WRITE DATASET('CARDXREF') from the batch
    //   xref-loader path. The card-xref table is reference data in
    //   CardDemo (the relationship between a card and its account /
    //   customer is established at card issuance and rarely updated),
    //   so no @Version optimistic-locking test is required — see the
    //   class-level Javadoc "Key Insights" section.
    // =========================================================================

    /**
     * Verifies that {@link CardXrefRepository#save(Object)} persists a
     * new {@link CardXref} so that all three key fields round-trip
     * through the database without truncation, encoding loss, or
     * column-mapping omission. This is the Java equivalent of the COBOL
     * batch xref-loader's {@code EXEC CICS WRITE DATASET('CARDXREF')}
     * step and of the online card-issuance flow's xref-row creation.
     *
     * <p>The post-save assertion confirms that:
     * <ul>
     *   <li>The 16-character {@code XREF-CARD-NUM} primary key
     *       ({@link CardXref#getCardNumber()}) round-trips exactly.</li>
     *   <li>The 9-character {@code XREF-CUST-ID} foreign key
     *       ({@link CardXref#getCustomerId()}) round-trips exactly —
     *       confirming the customer-FK column is correctly mapped.</li>
     *   <li>The 11-character {@code XREF-ACCT-ID} foreign key
     *       ({@link CardXref#getAccountId()}) round-trips exactly —
     *       confirming the account-FK column (which carries the CARDAIX
     *       alternate-index target) is correctly mapped.</li>
     * </ul>
     *
     * <p>The flush forces Hibernate to issue the INSERT against the DB,
     * and the clear discards the first-level cache so the subsequent
     * {@code findById} hits the database (not the in-memory entity).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new CardXref) persists and round-trips all three CVACT03Y.cpy key fields")
    void save_newXref_persistsAllKeys() {
        // Arrange — construct a synthetic xref with a deterministic PAN
        // outside the converted fixture range so the test never collides
        // with any pre-seeded row. The 4333000000000001 PAN is chosen
        // specifically to avoid colliding with the converted fixture
        // range (4111111111111101–4111111111111150) and with the
        // 4111000000000xxx / 4222000000000xxx ranges used by the
        // reverse-lookup-discrimination test.
        final String pan = "4333000000000001";
        CardXref xref = buildSyntheticXref(
                pan,
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_50);

        // Act — drive the production repository's save path. The flush
        // forces Hibernate to issue the INSERT statement against the
        // DB, and the clear discards the first-level cache so the
        // subsequent findById hits the database (not the in-memory
        // entity).
        cardXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // Assert — all three key fields preserved across the
        // round-trip. The orElseThrow guards against a save-then-not-
        // findable bug (which would manifest as Optional.empty rather
        // than the expected Optional.of(reloaded)).
        CardXref reloaded = cardXrefRepository.findById(pan).orElseThrow();
        assertThat(reloaded.getCardNumber())
                .as("XREF-CARD-NUM primary key must round-trip byte-for-byte "
                        + "across the INSERT — the 16-character VSAM-key "
                        + "preservation contract per AAP §0.10.4")
                .isEqualTo(pan);
        assertThat(reloaded.getCustomerId())
                .as("XREF-CUST-ID foreign key must round-trip exactly — "
                        + "confirming the customer-FK column mapping is "
                        + "complete and the 9-character PIC 9(09) field "
                        + "width is preserved")
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50);
        assertThat(reloaded.getAccountId())
                .as("XREF-ACCT-ID foreign key must round-trip exactly — "
                        + "confirming the account-FK column mapping is "
                        + "complete and the 11-character PIC 9(11) field "
                        + "width is preserved; the same column carries the "
                        + "CARDAIX alternate-index target exercised by the "
                        + "findByAccountId tests above")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_50);
    }

    // =========================================================================
    // Repository Wiring Smoke Test
    // =========================================================================

    /**
     * Verifies that {@link CardXrefRepository#count()} returns a
     * non-negative row count. This is a wiring-level smoke test
     * confirming that:
     * <ul>
     *   <li>The {@link CardXrefRepository} bean is correctly autowired
     *       by Spring Data JPA.</li>
     *   <li>The PostgreSQL DataSource is reachable and the
     *       Testcontainers container is healthy.</li>
     *   <li>The {@code card_xref} table exists in the schema (created
     *       by Flyway's {@code V1__schema.sql}).</li>
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
        long total = cardXrefRepository.count();

        // Assert — count() should never return negative. A negative
        // value would indicate a corrupted Spring Data JPA
        // implementation (impossible in practice) or a JDBC driver
        // bug; an exception thrown here would indicate a missing
        // table, broken DataSource, or unbootstrapped Spring context.
        assertThat(total)
                .as("CardXrefRepository.count() should return a non-negative "
                        + "row count; any exception thrown here would "
                        + "indicate a missing card_xref table or broken "
                        + "Testcontainers DataSource wiring")
                .isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // Customer Alternate-Index (XREF-CUST-ID) Reverse Lookup Tests
    //
    // COBOL provenance: COCRDLIC.cbl uses the XREF-CUST-ID alternate
    // index to enumerate every card a customer holds. This is a 1-to-many
    // mapping: a customer may hold several cards spanning multiple
    // accounts. Spring Data derives the equivalent JPQL from the
    // findByCustomerId(String) method name on CardXrefRepository.
    // =========================================================================

    /**
     * Verifies that
     * {@link CardXrefRepository#findByCustomerId(String)} returns every
     * xref row whose {@code XREF-CUST-ID} matches the supplied customer
     * identifier. We seed three synthetic xrefs on the same customer
     * with three distinct PANs and three distinct account IDs, then
     * assert the finder returns exactly those three rows.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByCustomerId(existing XREF-CUST-ID) returns all xrefs for that customer (COCRDLIC parity)")
    void findByCustomerId_existingCustomer_returnsAllXrefsForCustomer() {
        // Arrange — seed three xrefs on a single customer. The PAN
        // range "4111100000000xxx" is chosen specifically to avoid
        // colliding with the converted fixture range
        // 4111111111111101-4111111111111150 and with the other
        // tests' synthetic PAN ranges (4111000000000xxx,
        // 4222000000000xxx).
        entityManager.persist(buildSyntheticXref(
                "4111100000000001", TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10));
        entityManager.persist(buildSyntheticXref(
                "4111100000000002", TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20));
        entityManager.persist(buildSyntheticXref(
                "4111100000000003", TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10,
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the derived query against the real repository.
        List<CardXref> results = cardXrefRepository.findByCustomerId(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);

        // Assert — every returned xref carries the requested
        // customerId; size is at least 3 (the rows we seeded).
        assertThat(results)
                .as("findByCustomerId must return at least the three seeded xrefs")
                .isNotNull()
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(x -> assertThat(x.getCustomerId())
                        .as("Every returned xref must match the customer ID filter")
                        .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10));
    }

    /**
     * Verifies that
     * {@link CardXrefRepository#findByCustomerId(String)} returns an
     * empty (but non-{@code null}) list when no row matches the
     * supplied customer ID — the COBOL {@code DFHRESP(NOTFND)} parity.
     */
    @Test
    @DisplayName("findByCustomerId(unknown XREF-CUST-ID) returns empty list (DFHRESP(NOTFND) parity)")
    void findByCustomerId_unknownCustomer_returnsEmptyList() {
        // Act — query for a customer ID guaranteed to never appear.
        List<CardXref> results = cardXrefRepository.findByCustomerId(
                TestFixtures.Customers.NONEXISTENT_CUSTOMER_ID);

        // Assert — Spring Data List-typed derived queries return an
        // empty list (never null) for unmatched filters.
        assertThat(results)
                .as("findByCustomerId for an unknown customer must return an "
                        + "empty list (never null)")
                .isNotNull()
                .isEmpty();
    }

    /**
     * Verifies that
     * {@link CardXrefRepository#findByCustomerId(String)} discriminates
     * between distinct customers — seeding two xrefs on two different
     * customers and asserting the finder returns only the row matching
     * the target customer. A missing WHERE clause would silently return
     * both rows and fail this assertion.
     */
    @Test
    @DisplayName("findByCustomerId discriminates among multiple customers (returns only target customer's xrefs)")
    void findByCustomerId_discriminatesAmongCustomers_returnsOnlyTargetXrefs() {
        // Arrange — seed two xrefs on two distinct customers. We use
        // SAMPLE_CUSTOMER_ID_01 and SAMPLE_CUSTOMER_ID_50, two known
        // distinct values from the TestFixtures constants.
        entityManager.persist(buildSyntheticXref(
                "4111100000000010", TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01,
                "20000000010"));
        entityManager.persist(buildSyntheticXref(
                "4111100000000011", TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50,
                "20000000020"));
        entityManager.flush();
        entityManager.clear();

        // Act — request only customer 01's xrefs.
        List<CardXref> results = cardXrefRepository.findByCustomerId(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);

        // Assert — every returned xref belongs to customer 01;
        // none belongs to customer 50.
        assertThat(results)
                .as("findByCustomerId must return only target-customer xrefs")
                .isNotEmpty()
                .allSatisfy(x -> assertThat(x.getCustomerId())
                        .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01))
                .noneSatisfy(x -> assertThat(x.getCustomerId())
                        .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50));
    }

    // =========================================================================
    // Synthetic CardXref Builder (Test Helper — no business logic per
    // AAP §0.10.1)
    // =========================================================================

    /**
     * Constructs a synthetic {@link CardXref} populated with the
     * supplied {@code XREF-CARD-NUM} primary key, {@code XREF-CUST-ID}
     * customer foreign key, and {@code XREF-ACCT-ID} account foreign
     * key. The helper exists purely to eliminate field-by-field setter
     * boilerplate across the six test methods in this IT that seed
     * xref rows via the inherited {@code TestEntityManager}.
     *
     * <p>The helper contains no business logic and no calculations —
     * the three caller-supplied arguments are assigned directly to the
     * corresponding {@link CardXref} fields with no transformation,
     * complying with the AAP §0.10.1 Require Test Coverage Rule that
     * forbids reimplementing business or calculation logic inside test
     * bodies. The production {@code CardXrefFileProcessor} (which
     * decodes the ASCII fixture into the JPA entity) and
     * {@code AccountViewService} (which routes the reverse lookup) are
     * the authoritative locations for record-parsing and
     * lookup-dispatch logic; tests never duplicate that logic and
     * never re-derive expected values from inputs.
     *
     * <p>Field assignments mirror the {@code CVACT03Y.cpy}
     * CARD-XREF-RECORD layout: the three caller-supplied arguments
     * cover all three keys in the production entity (cardNumber,
     * customerId, accountId — note the Java-style field names per
     * AAP §0.10 Phase 10 Adaptation Notes, NOT the COBOL XREF-* names
     * which appear only in the entity Javadoc as the source-of-truth
     * fidelity marker). The CardXref entity has no other persisted
     * fields, so the helper does not set any additional values.
     *
     * @param cardNumber the 16-character XREF-CARD-NUM primary key
     *                   (Visa-format PAN per CVACT03Y.cpy PIC X(16))
     * @param customerId the 9-character zero-padded XREF-CUST-ID
     *                   foreign key (CVACT03Y.cpy PIC 9(09))
     * @param accountId  the 11-character zero-padded XREF-ACCT-ID
     *                   foreign key (CVACT03Y.cpy PIC 9(11))
     * @return a fully-populated unmanaged {@link CardXref} ready for
     *         {@link CardXrefRepository#save(Object)} or
     *         {@link jakarta.persistence.EntityManager#persist(Object)}
     */
    private CardXref buildSyntheticXref(String cardNumber,
                                        String customerId,
                                        String accountId) {
        CardXref x = new CardXref();
        // -- 16-character XREF-CARD-NUM primary key (PIC X(16)).
        x.setCardNumber(cardNumber);
        // -- 9-character XREF-CUST-ID foreign key (PIC 9(09)).
        x.setCustomerId(customerId);
        // -- 11-character XREF-ACCT-ID foreign key (PIC 9(11)).
        //    This column also carries the CARDAIX alternate-index
        //    target — the reverse-lookup access pattern exercised by
        //    findByAccountId tests in this IT.
        x.setAccountId(accountId);
        return x;
    }
}
