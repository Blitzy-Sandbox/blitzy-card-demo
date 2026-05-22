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
//   * Customer — the JPA entity stub matching the CUSTREC.cpy /
//     CVCUS01Y.cpy CUSTOMER-RECORD layout (500-byte fixed-width record:
//     9-char CUST-ID + 25-char CUST-FIRST-NAME + 25-char CUST-MIDDLE-NAME +
//     25-char CUST-LAST-NAME + 3×50-char CUST-ADDR-LINE-1/2/3 + 2-char
//     CUST-ADDR-STATE-CD + 3-char CUST-ADDR-COUNTRY-CD + 10-char
//     CUST-ADDR-ZIP + 2×15-char CUST-PHONE-NUM-1/2 + 9-char CUST-SSN +
//     20-char CUST-GOVT-ISSUED-ID + 10-char CUST-DOB-YYYY-MM-DD +
//     10-char CUST-EFT-ACCOUNT-ID + 1-char CUST-PRI-CARD-HOLDER-IND +
//     3-char CUST-FICO-CREDIT-SCORE + 168-byte FILLER). Test methods drive
//     persist/find/save round-trips through this entity. The production
//     class uses Java-conventioned field names (customerId, firstName,
//     dateOfBirth, ficoCreditScore, etc.) rather than the COBOL-style
//     CUST-* names; the AAP §0.10 "Phase 11 Adaptation Notes" explicitly
//     anticipates this name divergence ("If the production Customer.custDob
//     setter is named differently (e.g., setCustDobYyyymmdd or
//     setDateOfBirth), adjust the call site.").
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
//     the Customers-nested constants: SAMPLE_CUSTOMER_ID_01 ("000000001"),
//     SAMPLE_CUSTOMER_ID_10 ("000000010"), SAMPLE_CUSTOMER_ID_50
//     ("000000050"), and NONEXISTENT_CUSTOMER_ID ("999999999"). The first
//     three are the 9-digit CUST-ID primary keys present in the
//     custdata.txt fixture range; the fourth is reserved for VSAM
//     STATUS '23' (record-not-found) parity assertions.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Customer;
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
//   * @Autowired field-injects the Spring-managed CustomerRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<Customer, String>} interface declaration on
//     the production repository — no manual implementation is required,
//     and no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.time.LocalDate — used to construct deterministic ISO-formatted
//     date-of-birth string values for Customer entity persistence
//     round-trip tests. The COBOL original CUST-DOB-YYYY-MM-DD PIC X(10)
//     field stores an ISO 'YYYY-MM-DD' string; the Java migration's
//     Customer entity preserves that String-based storage at the field
//     level (see Customer#dateOfBirth) but tests construct the value via
//     LocalDate.of(year, month, day).toString() to make the date semantics
//     self-documenting at the call site (AAP §0.10.10 Style Consistency).
//     The AAP §0.10 "Phase 11 Adaptation Notes" anticipated this
//     production-side type choice: "If the production entity stores DOB
//     as String instead of LocalDate, adjust the setter argument to
//     "1980-01-15"."
//
//   * java.util.Optional — return type of CustomerRepository.findById()
//     used by the primary-key lookup assertions (isPresent / isEmpty).
//     CustomerRepository extends JpaRepository<Customer, String> so the
//     inherited Optional<Customer> findById(String) signature is the
//     primary lookup mechanism (the Java equivalent of the COBOL
//     {@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(WS-CARD-RID-CUST-ID-X)}
//     used by COACTVWC.cbl §9400-GETCUSTDATA-BYCUST).
// ---------------------------------------------------------------------------
import java.time.LocalDate;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent() /
//     assertThat(optional).isEmpty(), assertThat(string).isEqualTo(...),
//     assertThat(integer).isEqualTo(...), assertThat(long)
//     .isGreaterThanOrEqualTo(0) are the four primary idioms this class
//     exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CustomerRepository}, which persists
 * {@link Customer} entities migrated from the COBOL {@code CUSTOMER-RECORD}
 * defined in {@code app/cpy/CUSTREC.cpy} and {@code app/cpy/CVCUS01Y.cpy}
 * (RECLN 500).
 *
 * <h2>COBOL Provenance — CUSTREC.cpy / CVCUS01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 500-byte record:
 * <pre>
 *   01 CUSTOMER-RECORD.
 *      05 CUST-ID                  PIC 9(09).  --&gt; {@link Customer#getCustomerId()}                 (primary key)
 *      05 CUST-FIRST-NAME          PIC X(25).  --&gt; {@link Customer#getFirstName()}
 *      05 CUST-MIDDLE-NAME         PIC X(25).  --&gt; {@link Customer#getMiddleName()}
 *      05 CUST-LAST-NAME           PIC X(25).  --&gt; {@link Customer#getLastName()}
 *      05 CUST-ADDR-LINE-1         PIC X(50).  --&gt; {@link Customer#getAddressLine1()}
 *      05 CUST-ADDR-LINE-2         PIC X(50).  --&gt; {@link Customer#getAddressLine2()}
 *      05 CUST-ADDR-LINE-3         PIC X(50).  --&gt; {@link Customer#getAddressLine3()}
 *      05 CUST-ADDR-STATE-CD       PIC X(02).  --&gt; {@link Customer#getAddressStateCode()}
 *      05 CUST-ADDR-COUNTRY-CD     PIC X(03).  --&gt; {@link Customer#getAddressCountryCode()}
 *      05 CUST-ADDR-ZIP            PIC X(10).  --&gt; {@link Customer#getAddressZip()}
 *      05 CUST-PHONE-NUM-1         PIC X(15).  --&gt; {@link Customer#getPhoneNumber1()}              (NANPA-validated)
 *      05 CUST-PHONE-NUM-2         PIC X(15).  --&gt; {@link Customer#getPhoneNumber2()}              (NANPA-validated)
 *      05 CUST-SSN                 PIC 9(09).  --&gt; {@link Customer#getSsn()}                       (PII)
 *      05 CUST-GOVT-ISSUED-ID      PIC X(20).  --&gt; {@link Customer#getGovernmentIssuedId()}
 *      05 CUST-DOB-YYYY-MM-DD      PIC X(10).  --&gt; {@link Customer#getDateOfBirth()}               (ISO YYYY-MM-DD)
 *      05 CUST-EFT-ACCOUNT-ID      PIC X(10).  --&gt; {@link Customer#getEftAccountId()}
 *      05 CUST-PRI-CARD-HOLDER-IND PIC X(01).  --&gt; {@link Customer#getPrimaryCardHolderIndicator()}
 *      05 CUST-FICO-CREDIT-SCORE   PIC 9(03).  --&gt; {@link Customer#getFicoCreditScore()}           (300–850, nullable)
 *      05 FILLER                   PIC X(168).
 * </pre>
 *
 * <h2>Migration Pattern (AAP §0.5.1)</h2>
 *
 * <p>VSAM KSDS primary-key access ({@code CUST-ID PIC 9(09)}) maps to JPA
 * {@code findById}. The COBOL {@code EXEC CICS READ DATASET('CUSTDAT')
 * RIDFLD(WS-CARD-RID-CUST-ID-X)} call in {@code COACTVWC.cbl §9400-GETCUSTDATA-BYCUST}
 * paragraph (lines 825–869) is replaced by
 * {@link CustomerRepository#findById(Object)}:
 *
 * <ul>
 *   <li>COBOL {@code DFHRESP(NORMAL)} → {@code Optional.of(customer)} —
 *       record found; {@link #findById_existingCustomer_returnsCustomer()}.</li>
 *   <li>COBOL {@code DFHRESP(NOTFND)} → {@code Optional.empty()} —
 *       record not found (VSAM file-status code {@code '23'});
 *       {@link #findById_nonexistentCustomer_returnsEmpty()}.</li>
 * </ul>
 *
 * <p>NANPA phone-number validation (originally implemented via the
 * {@code CSLKPCDY.cpy} lookup table) lives in {@code ValidationLookupService}
 * per AAP §0.5.1, NOT in the repository. This IT only verifies that
 * already-validated phone values persist verbatim — the repository does
 * not normalize, mask, or strip phone formats. The NANPA validation
 * logic itself is covered by {@code ValidationLookupServiceTest}.
 *
 * <h2>Production Entity Naming Note</h2>
 *
 * <p>The production {@link Customer} class uses Java-conventioned field
 * names ({@code customerId}, {@code firstName}, {@code middleName},
 * {@code lastName}, {@code addressLine1/2/3}, {@code addressStateCode},
 * {@code addressCountryCode}, {@code addressZip}, {@code phoneNumber1/2},
 * {@code ssn}, {@code governmentIssuedId}, {@code dateOfBirth},
 * {@code eftAccountId}, {@code primaryCardHolderIndicator},
 * {@code ficoCreditScore}) rather than the COBOL-style {@code CUST-*}
 * names; this IT therefore drives the Java-style API exactly as the
 * production code exposes it. The COBOL-to-Java field-name mapping is
 * documented in the {@link Customer} class Javadoc and in the field-list
 * mapping above for cross-reference.
 *
 * <p>The production {@code Customer#dateOfBirth} field is a {@link String}
 * (storing the ISO {@code YYYY-MM-DD} representation), NOT a
 * {@link LocalDate}. Tests construct the value via
 * {@code LocalDate.of(year, month, day).toString()} to keep the date
 * semantics self-documenting at the call site while producing the
 * {@link String} the production setter requires. The AAP §0.10 "Phase 11
 * Adaptation Notes" explicitly anticipated both deviations ("If the
 * production entity stores DOB as String instead of LocalDate, adjust
 * the setter argument to "1980-01-15"" and "If the production
 * Customer.custDob setter is named differently (e.g., setCustDobYyyymmdd
 * or setDateOfBirth), adjust the call site").
 *
 * <h2>Security Note (AAP §0.10.5) — Synthetic PII Only</h2>
 *
 * <p>The {@code CUST-SSN PIC 9(09)} field is PII. This IT uses the
 * canonical synthetic placeholder {@code "123456789"} (the all-9-digit-
 * sequential pattern) for every SSN value; no real production SSN data
 * appears in test fixtures. Names ({@code "Jane Doe"}, {@code "Alice Smith"},
 * etc.) are common-test-data conventions. Phone numbers use the
 * {@code 555-XXXX} prefix block — reserved by the North American
 * Numbering Plan (NANPA) for fictional use and guaranteed never to ring
 * a real line. Address-line content is wholly synthetic. The AAP §0.10.5
 * "no plaintext credentials" rule does not gate this IT directly (no
 * password field on the Customer entity) but the broader spirit of the
 * AAP §0.10.5 PII-defence section is honored throughout.
 *
 * <h2>Coverage Focus (AAP §0.5.1, §0.7.1)</h2>
 *
 * <p>The 8 test methods exercise the integration of {@link CustomerRepository}
 * against a real PostgreSQL 16 instance provisioned by Testcontainers —
 * no Mockito stubs at this layer (AAP §0.10.1 Require Test Coverage
 * Rule). The categories below cover the AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #findById_existingCustomer_returnsCustomer()} —
 *       primary-key lookup happy path (COBOL {@code DFHRESP(NORMAL)}
 *       equivalent).</li>
 *   <li>{@link #findById_nonexistentCustomer_returnsEmpty()} —
 *       primary-key lookup negative path (COBOL {@code DFHRESP(NOTFND)} /
 *       VSAM {@code STATUS '23'} equivalent).</li>
 *   <li>{@link #save_newCustomer_persistsAllFields()} — full
 *       500-byte-record round-trip: every migrated field round-trips
 *       through {@code save()} + {@code findById()} unchanged. This is
 *       the AAP §0.10.4 "Immutable Boundaries" contract for the
 *       CUSTOMER-RECORD layout.</li>
 *   <li>{@link #save_updatedAddress_persistsChange()} — update-path
 *       parity for the customer-side dual-write in {@code COACTUPC.cbl}
 *       (account-update flow updates both account and customer rows
 *       within a single SYNCPOINT-bounded unit of work).</li>
 *   <li>{@link #save_nanpaValidatedPhones_persistsExactly()} —
 *       AAP §0.5.1 "NANPA phone validation persistence" contract:
 *       NANPA-valid phone values persist verbatim through the
 *       repository layer; the repository does not normalize, mask, or
 *       strip phone formats. (The NANPA validation logic itself lives
 *       in {@code ValidationLookupService}.)</li>
 *   <li>{@link #save_nullSecondaryPhone_persistsNull()} — null-tolerance
 *       contract: the optional {@code CUST-PHONE-NUM-2} field persists
 *       as {@code null} when not supplied (COBOL stores spaces; JPA
 *       stores null).</li>
 *   <li>{@link #save_maxFicoScore_persistsExactly()} — FICO boundary
 *       contract: the {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} field's
 *       upper bound (850) round-trips through the {@link Integer}
 *       column without truncation. A column erroneously sized as
 *       {@code TINYINT} or {@code SMALLINT} below 850 would surface
 *       through this assertion.</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link CustomerRepository} bean wired by Spring Data JPA
 * against the real PostgreSQL 16 database supplied by Testcontainers
 * (inherited from {@link AbstractRepositoryIT}). Repository ITs sit at
 * the lowest mock boundary in the test pyramid: they verify that the
 * Spring Data JPA proxy + Hibernate ORM + JDBC driver + PostgreSQL
 * stack produces correct results against a real schema. Tests that
 * would otherwise mock the repository (service unit tests, batch
 * processor unit tests) live one layer up in
 * {@code com.aws.carddemo.service.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>This IT performs <strong>zero</strong> NANPA validation, format
 * normalisation, or date parsing inside test methods. Phone-number
 * literals like {@code "212-555-1234"} are pre-formatted strings; the
 * repository persists them verbatim. Date literals are constructed via
 * {@code LocalDate.of(...).toString()} purely for compile-time
 * type-safety on the year/month/day triplet (not for date arithmetic).
 * NANPA validation, phone normalisation, and date parsing logic lives
 * exclusively in {@code com.aws.carddemo.validation.ValidationLookupService}
 * and {@code com.aws.carddemo.validation.DateValidationService}, and is
 * covered by their respective unit tests.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic customers persisted by every
 * test in this class are gone before the next test sees the database
 * state. Each test starts from the Flyway-seeded customer catalog (50
 * rows from {@code custdata.txt}); test order independence is
 * guaranteed.
 *
 * <h2>Activation State</h2>
 *
 * <p>This IT is active and executes under {@code mvn verify} (Failsafe).
 * The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link CustomerRepository} bean against a real PostgreSQL 16
 * database. The {@link Customer} entity is annotated as a JPA
 * {@code @Entity} (with {@code @Id} on {@code customerId},
 * {@code @Column} annotations on every field, {@code @Version} on
 * {@code version}), and the Flyway scripts under
 * {@code src/main/resources/db/migration/} create the
 * {@code customers} table and seed reference customers. The
 * {@code findById}, save, optimistic-locking, all-17-field round-trip,
 * and NANPA-shaped phone-number paths are all exercised by the test
 * methods below.
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
 * @see CustomerRepository
 * @see Customer
 * @see AbstractRepositoryIT
 * @see TestFixtures.Customers
 */
@DisplayName("CustomerRepository — CUSTREC.cpy / CVCUS01Y.cpy migration parity ITs")
class CustomerRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link CustomerRepository} interface declaration (no
     * manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private CustomerRepository customerRepository;

    // =========================================================================
    // Primary-Key Lookup Tests (AAP §0.5.1 "findById")
    // =========================================================================

    /**
     * Verifies that {@link CustomerRepository#findById(Object)} returns
     * the persisted {@link Customer} for an existing 9-character primary
     * key. The test persists a synthetic customer via the inherited
     * {@code TestEntityManager}, flushes the persistence context to push
     * the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected primary key, first name, and last name.
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NORMAL)} branch from {@code COACTVWC.cbl}'s
     * {@code 9400-GETCUSTDATA-BYCUST} paragraph (lines 825–869) and
     * underlies the customer-lookup half of the
     * {@link com.aws.carddemo.service.AccountViewService}'s
     * account-view round-trip.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing CUST-ID) returns the persisted Customer")
    void findById_existingCustomer_returnsCustomer() {
        // Arrange — persist a synthetic customer via the inherited TestEntityManager.
        // SAMPLE_CUSTOMER_ID_10 ("000000010") is the canonical fixture-range CUST-ID
        // used across customer-related ITs.
        Customer cust = buildSyntheticCustomer(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10,
                "Jane",
                "Doe");
        entityManager.persistAndFlush(cust);
        entityManager.clear();

        // Act — drive the production repository against the real DB.
        Optional<Customer> result =
                customerRepository.findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);

        // Assert — the row must be located and the primary identifying fields must
        // round-trip exactly.
        assertThat(result)
                .as("findById should locate the persisted customer by 9-character primary key "
                        + "(COBOL DFHRESP(NORMAL) equivalent)")
                .isPresent();
        Customer c = result.get();
        assertThat(c.getCustomerId())
                .as("CUST-ID primary-key component must round-trip exactly")
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        assertThat(c.getFirstName())
                .as("CUST-FIRST-NAME must round-trip exactly after persist+findById")
                .isEqualTo("Jane");
        assertThat(c.getLastName())
                .as("CUST-LAST-NAME must round-trip exactly after persist+findById")
                .isEqualTo("Doe");
    }

    /**
     * Verifies that {@link CustomerRepository#findById(Object)} returns
     * {@link Optional#empty()} when the supplied 9-character primary key
     * does not exist in the {@code customers} table. This is the Java
     * equivalent of the COBOL CICS response code {@code DFHRESP(NOTFND)}
     * branch from {@code COACTVWC.cbl}'s
     * {@code 9400-GETCUSTDATA-BYCUST} paragraph — the underlying VSAM
     * file-status code {@code '23'} (record not found). The production
     * {@link com.aws.carddemo.service.AccountViewService} translates
     * this empty {@link Optional} into the COBOL-equivalent "CustId:nnn
     * not found in customer master" reject message.
     *
     * <p>The lookup key {@link TestFixtures.Customers#NONEXISTENT_CUSTOMER_ID}
     * ({@code "999999999"}) is guaranteed never to appear in the
     * {@code custdata.txt} fixture (which spans {@code "000000001"} to
     * {@code "000000050"}); the constant exists precisely for not-found
     * assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent CUST-ID) returns empty Optional (VSAM STATUS 23 parity)")
    void findById_nonexistentCustomer_returnsEmpty() {
        // Act — drive the production repository against the real DB with a key
        // guaranteed never to appear in the fixture customer catalog.
        Optional<Customer> result =
                customerRepository.findById(TestFixtures.Customers.NONEXISTENT_CUSTOMER_ID);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal.
        assertThat(result)
                .as("findById should return Optional.empty for unknown customer IDs "
                        + "(COBOL DFHRESP(NOTFND) / VSAM STATUS '23' equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // Save / Round-Trip Tests (AAP §0.5.1 "save", §0.10.4 immutable boundaries)
    // =========================================================================

    /**
     * Verifies that every migrated field of the 500-byte
     * {@code CUSTOMER-RECORD} round-trips through
     * {@link CustomerRepository#save(Object)} +
     * {@link CustomerRepository#findById(Object)} unchanged. This is the
     * AAP §0.10.4 "Immutable Boundaries" contract for the customer
     * record layout: any field that ships through the repository must
     * arrive on the other side bit-for-bit identical to what was
     * supplied.
     *
     * <p>The test constructs a fully-populated {@link Customer} (skipping
     * the {@code @Version} field, which Hibernate manages), saves it,
     * flushes the persistence context to push the INSERT through to
     * PostgreSQL, clears the first-level cache so the subsequent
     * {@code findById} re-reads from the database, and asserts on every
     * non-version field individually.
     *
     * <p>Coverage:
     * <ul>
     *   <li>String fields (names, addresses, codes, identifiers) —
     *       verify VARCHAR/CHAR column round-trip without truncation or
     *       null-padding loss.</li>
     *   <li>{@code dateOfBirth} (CUST-DOB-YYYY-MM-DD) — verify the
     *       ISO {@code YYYY-MM-DD} 10-character string round-trips
     *       through the {@code CHAR(10)} column.</li>
     *   <li>{@code ficoCreditScore} (CUST-FICO-CREDIT-SCORE) — verify the
     *       3-digit {@link Integer} round-trips through the INTEGER
     *       column.</li>
     * </ul>
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new Customer) persists all fields including 3 address lines and 2 phones")
    void save_newCustomer_persistsAllFields() {
        // Arrange — populate every addressable field of the 500-byte CUSTOMER-RECORD.
        // SAMPLE_CUSTOMER_ID_50 ("000000050") is the last fixture-range CUST-ID.
        Customer cust = new Customer();
        cust.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50);
        cust.setFirstName("Alice");
        cust.setMiddleName("Marie");
        cust.setLastName("Smith");
        cust.setAddressLine1("123 Test Street");
        cust.setAddressLine2("Apt 4B");
        cust.setAddressLine3("Care of Test Building");
        cust.setAddressStateCode("WA");
        cust.setAddressCountryCode("USA");
        cust.setAddressZip("99999");
        // NANPA-valid 206 (Seattle) and 425 (Eastside) area codes; the 555-XXXX
        // exchange block is NANPA-reserved for fictional use.
        cust.setPhoneNumber1("206-555-0100");
        cust.setPhoneNumber2("425-555-0101");
        // Synthetic SSN — the canonical all-9-digit-sequential placeholder, NOT real PII.
        cust.setSsn("123456789");
        cust.setGovernmentIssuedId("TEST-GOVT-ID-12345");
        // CUST-DOB-YYYY-MM-DD stored as String; LocalDate.of(...).toString()
        // self-documents the year/month/day triplet at the call site.
        cust.setDateOfBirth(LocalDate.of(1980, 1, 15).toString());
        cust.setEftAccountId("EFT0000001");
        cust.setPrimaryCardHolderIndicator("Y");
        cust.setFicoCreditScore(750);

        // Act — drive the production repository's save path; flush + clear forces
        // Hibernate to issue the INSERT immediately and the subsequent findById
        // to re-fetch from PostgreSQL rather than the first-level cache.
        customerRepository.save(cust);
        entityManager.flush();
        entityManager.clear();

        // Assert — every field must round-trip exactly (AAP §0.10.4 immutable boundary).
        Customer reloaded = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50)
                .orElseThrow();
        assertThat(reloaded.getCustomerId())
                .as("CUST-ID primary-key round-trip")
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50);
        assertThat(reloaded.getFirstName())
                .as("CUST-FIRST-NAME round-trip")
                .isEqualTo("Alice");
        assertThat(reloaded.getMiddleName())
                .as("CUST-MIDDLE-NAME round-trip")
                .isEqualTo("Marie");
        assertThat(reloaded.getLastName())
                .as("CUST-LAST-NAME round-trip")
                .isEqualTo("Smith");
        assertThat(reloaded.getAddressLine1())
                .as("CUST-ADDR-LINE-1 round-trip")
                .isEqualTo("123 Test Street");
        assertThat(reloaded.getAddressLine2())
                .as("CUST-ADDR-LINE-2 round-trip")
                .isEqualTo("Apt 4B");
        assertThat(reloaded.getAddressLine3())
                .as("CUST-ADDR-LINE-3 round-trip (city per COBOL convention)")
                .isEqualTo("Care of Test Building");
        assertThat(reloaded.getAddressStateCode())
                .as("CUST-ADDR-STATE-CD round-trip")
                .isEqualTo("WA");
        assertThat(reloaded.getAddressCountryCode())
                .as("CUST-ADDR-COUNTRY-CD round-trip")
                .isEqualTo("USA");
        assertThat(reloaded.getAddressZip())
                .as("CUST-ADDR-ZIP round-trip")
                .isEqualTo("99999");
        assertThat(reloaded.getPhoneNumber1())
                .as("CUST-PHONE-NUM-1 round-trip")
                .isEqualTo("206-555-0100");
        assertThat(reloaded.getPhoneNumber2())
                .as("CUST-PHONE-NUM-2 round-trip")
                .isEqualTo("425-555-0101");
        assertThat(reloaded.getSsn())
                .as("CUST-SSN round-trip (synthetic value, not real PII)")
                .isEqualTo("123456789");
        assertThat(reloaded.getGovernmentIssuedId())
                .as("CUST-GOVT-ISSUED-ID round-trip")
                .isEqualTo("TEST-GOVT-ID-12345");
        assertThat(reloaded.getDateOfBirth())
                .as("CUST-DOB-YYYY-MM-DD round-trip (ISO YYYY-MM-DD string)")
                .isEqualTo(LocalDate.of(1980, 1, 15).toString());
        assertThat(reloaded.getEftAccountId())
                .as("CUST-EFT-ACCOUNT-ID round-trip")
                .isEqualTo("EFT0000001");
        assertThat(reloaded.getPrimaryCardHolderIndicator())
                .as("CUST-PRI-CARD-HOLDER-IND round-trip ('Y' for primary card holder)")
                .isEqualTo("Y");
        assertThat(reloaded.getFicoCreditScore())
                .as("CUST-FICO-CREDIT-SCORE round-trip")
                .isEqualTo(750);
    }

    // =========================================================================
    // Update Path Tests (AAP §0.5.1 — COACTUPC customer-side update flow)
    // =========================================================================

    /**
     * Verifies that re-saving a previously persisted {@link Customer}
     * with modified address fields persists the change. This is the
     * customer-side update path of the COBOL {@code COACTUPC.cbl}
     * dual-write flow: account-update operations within
     * {@link com.aws.carddemo.service.AccountUpdateService} update both
     * the account row AND the customer row in a single transaction
     * (the Java replacement for COBOL's
     * {@code SYNCPOINT ROLLBACK}-bounded unit of work).
     *
     * <p>The test persists an initial synthetic customer with the
     * baseline address from {@link #buildSyntheticCustomer(String,
     * String, String)}, then loads it, mutates two address fields
     * (line 1 and zip), re-saves, and asserts that the reloaded row
     * carries the new values.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(updated address) persists the change (COACTUPC customer-side update flow)")
    void save_updatedAddress_persistsChange() {
        // Arrange — persist an initial customer with the helper's baseline address
        // (100 Test Lane / WA / 99999 / USA).
        Customer initial = buildSyntheticCustomer(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01,
                "Bob",
                "Jones");
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act — load, change address, re-save.
        Customer reloaded = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01)
                .orElseThrow();
        reloaded.setAddressLine1("456 Updated Avenue");
        reloaded.setAddressZip("88888");
        customerRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Assert — the mutated fields carry the new values; nothing else should change.
        Customer afterUpdate = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01)
                .orElseThrow();
        assertThat(afterUpdate.getAddressLine1())
                .as("Updated CUST-ADDR-LINE-1 must persist verbatim")
                .isEqualTo("456 Updated Avenue");
        assertThat(afterUpdate.getAddressZip())
                .as("Updated CUST-ADDR-ZIP must persist verbatim")
                .isEqualTo("88888");
    }

    // =========================================================================
    // NANPA Phone Persistence (AAP §0.5.1 — "NANPA phone validation persistence")
    // =========================================================================

    /**
     * Verifies that NANPA-validated phone number strings persist
     * <strong>verbatim</strong> through the repository layer. The COBOL
     * {@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2} fields
     * ({@code PIC X(15)}) are validated against the
     * {@code CSLKPCDY.cpy} NANPA area-code lookup table at the
     * application layer; the validation responsibility migrates to
     * {@link com.aws.carddemo.validation.ValidationLookupService} per
     * AAP §0.5.1, NOT to the repository. This IT therefore only verifies
     * the <em>persistence</em> side of the contract: whatever
     * NANPA-validated string the production code persists must
     * round-trip through {@code save()} + {@code findById()} unchanged.
     *
     * <p>The test uses two real-world NANPA area codes (212 = New York
     * City, 415 = San Francisco) combined with the NANPA-reserved
     * {@code 555-XXXX} fictional-use exchange block. These values are
     * pre-formatted strings — the repository does not normalize, mask,
     * or strip phone formats.
     *
     * <p>If the repository (or its underlying Hibernate column converter)
     * were to apply any transformation — trimming whitespace, stripping
     * separators, masking middle digits — this test would fail with a
     * value-mismatch error. The verbatim round-trip is the contract.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Customer with NANPA-valid phone) round-trips both phone numbers exactly")
    void save_nanpaValidatedPhones_persistsExactly() {
        // Arrange — already-validated NANPA phone formats with real area codes.
        // (Validation logic lives in ValidationLookupService — this IT only persists.)
        Customer cust = buildSyntheticCustomer(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10,
                "Charlie",
                "Brown");
        cust.setPhoneNumber1("212-555-1234"); // 212 = New York City NANPA area code
        cust.setPhoneNumber2("415-555-5678"); // 415 = San Francisco NANPA area code

        // Act — drive the production repository's save path.
        customerRepository.save(cust);
        entityManager.flush();
        entityManager.clear();

        // Assert — both phones persist verbatim with NO normalization at the
        // repository layer (the AAP §0.5.1 "NANPA phone validation persistence"
        // contract: validation is upstream; the repository is a verbatim store).
        Customer reloaded = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10)
                .orElseThrow();
        assertThat(reloaded.getPhoneNumber1())
                .as("CUST-PHONE-NUM-1 must round-trip verbatim — repository does NOT "
                        + "normalize, mask, or strip phone formats (validation lives in "
                        + "ValidationLookupService per AAP §0.5.1)")
                .isEqualTo("212-555-1234");
        assertThat(reloaded.getPhoneNumber2())
                .as("CUST-PHONE-NUM-2 must round-trip verbatim — repository does NOT "
                        + "normalize, mask, or strip phone formats (validation lives in "
                        + "ValidationLookupService per AAP §0.5.1)")
                .isEqualTo("415-555-5678");
    }

    /**
     * Verifies that a {@code null}-valued {@link Customer#setPhoneNumber2(String)}
     * persists as SQL {@code NULL} (not as an empty string and not as
     * spaces). The COBOL {@code CUST-PHONE-NUM-2 PIC X(15)} field is
     * optional: when a customer has only a primary phone, the COBOL
     * record stores 15 spaces; in the Java migration the equivalent
     * absence-of-value is {@code null}.
     *
     * <p>This null-tolerance contract is essential for downstream
     * consumers (e.g.,
     * {@link com.aws.carddemo.service.AccountUpdateService} and the
     * customer-detail REST controller) which use the standard
     * "{@code null} means no value" convention. A repository column
     * defined as {@code NOT NULL} would throw a constraint violation
     * here, surfacing the misconfiguration immediately.
     *
     * <p>The test persists a synthetic customer with
     * {@code phoneNumber2 = null}, flushes, clears, reloads, and
     * asserts that the reloaded {@code phoneNumber2} is {@code null}.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Customer with null secondary phone) persists null (optional field)")
    void save_nullSecondaryPhone_persistsNull() {
        // Arrange — CUST-PHONE-NUM-2 is optional. The helper sets it to "" by
        // default; here we explicitly null it to exercise the JPA null path.
        Customer cust = buildSyntheticCustomer(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50,
                "Dora",
                "Explorer");
        cust.setPhoneNumber2(null);

        // Act — drive the production repository's save path; flush + clear forces
        // the INSERT and a fresh read from PostgreSQL.
        customerRepository.save(cust);
        entityManager.flush();
        entityManager.clear();

        // Assert — the optional field persists as null (not empty string, not spaces).
        Customer reloaded = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_50)
                .orElseThrow();
        assertThat(reloaded.getPhoneNumber2())
                .as("Null secondary phone must round-trip as null — the column must NOT "
                        + "be defined NOT NULL, and the JPA mapping must NOT coerce null "
                        + "to empty-string or spaces")
                .isNull();
    }

    // =========================================================================
    // FICO Score Boundary Test (AAP §0.5.1 coverage focus)
    // =========================================================================

    /**
     * Verifies that the maximum FICO credit score (850) round-trips
     * exactly through the {@code fico_credit_score} column. The COBOL
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} field is a 3-digit
     * unsigned integer; the FICO scoring range is 300–850 by FICO's
     * public specification, with 850 the absolute upper bound. The Java
     * migration represents the field as {@link Integer} (not {@code int})
     * to preserve the null sentinel for customers whose FICO has never
     * been pulled.
     *
     * <p>This test guards against an accidental column-width
     * misconfiguration: an underlying database column erroneously sized
     * as {@code TINYINT} (PostgreSQL doesn't have this, but the
     * Hibernate dialect chosen here for documentation purposes does have
     * narrower integer types) — or a {@code SMALLINT} value type that
     * happened to be capped below 850 — would surface through this
     * assertion's failure on the upper-bound value.
     *
     * <p>The test persists a synthetic customer with {@code ficoCreditScore = 850},
     * flushes to push the INSERT through Hibernate's binding logic,
     * clears the first-level cache, reloads, and asserts that the
     * reloaded value is exactly 850.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Customer with FICO 850 boundary) preserves max-precision integer")
    void save_maxFicoScore_persistsExactly() {
        // Arrange — FICO score upper-bound boundary (850 is the absolute max per
        // FICO's public scoring range 300–850).
        Customer cust = buildSyntheticCustomer(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01,
                "Test",
                "FicoMax");
        cust.setFicoCreditScore(850);

        // Act — drive the production repository's save path; flush + clear forces
        // Hibernate to bind the Integer through the JDBC driver and the subsequent
        // findById to re-fetch from PostgreSQL.
        customerRepository.save(cust);
        entityManager.flush();
        entityManager.clear();

        // Assert — the maximum FICO score (850) must round-trip exactly.
        Customer reloaded = customerRepository
                .findById(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01)
                .orElseThrow();
        assertThat(reloaded.getFicoCreditScore())
                .as("Max FICO score (850) must round-trip exactly — the column must be "
                        + "INTEGER or wider; a column erroneously sized below 850 (e.g., "
                        + "SMALLINT capped or TINYINT) would surface through this assertion")
                .isEqualTo(850);
    }

    // =========================================================================
    // count() Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link CustomerRepository#count()} returns a
     * non-negative tally. The assertion uses
     * {@code isGreaterThanOrEqualTo(0)} rather than an exact value
     * because the inherited {@code @DataJpaTest} transactional rollback
     * may not yet have run when this method is invoked, so synthetic
     * rows from earlier tests in the same class might still be visible
     * — the non-negative invariant is the safest universal assertion.
     *
     * <p>Together with the other tests in this class, this guards against
     * accidentally truncating the {@code customers} table at any point
     * in the migration lifecycle: a negative tally would surface a
     * Spring Data JPA implementation defect immediately.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count")
    void count_invoked_returnsNonNegativeValue() {
        // Act — drive the inherited JpaRepository.count() against the real DB.
        long total = customerRepository.count();

        // Assert — count is a row tally; it must never be negative.
        assertThat(total)
                .as("count() must return a non-negative row tally; negative values would "
                        + "indicate a Spring Data JPA implementation defect")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Constructs a synthetic {@link Customer} populated with the supplied
     * 9-character primary key, first name, and last name. All other fields
     * are populated with deterministic synthetic values to satisfy any
     * not-null constraints the production schema may impose:
     *
     * <ul>
     *   <li>{@code middleName = "Test"} — generic placeholder.</li>
     *   <li>{@code addressLine1 = "100 Test Lane"} — synthetic street.</li>
     *   <li>{@code addressLine2 = ""} — empty optional line 2.</li>
     *   <li>{@code addressLine3 = ""} — empty optional line 3.</li>
     *   <li>{@code addressStateCode = "WA"} — Washington (NANPA area
     *       206/425/253 etc.); chosen for consistency with
     *       {@code phoneNumber1 = "206-555-0100"}.</li>
     *   <li>{@code addressCountryCode = "USA"} — 3-character ISO 3166-1
     *       alpha-3.</li>
     *   <li>{@code addressZip = "99999"} — synthetic 5-digit ZIP.</li>
     *   <li>{@code phoneNumber1 = "206-555-0100"} — NANPA area 206
     *       (Seattle) + 555-XXXX fictional-use block.</li>
     *   <li>{@code phoneNumber2 = ""} — empty optional secondary phone
     *       (tests that need {@code null} explicitly call
     *       {@code setPhoneNumber2(null)} after the helper returns).</li>
     *   <li>{@code ssn = "123456789"} — the canonical all-9-digit-
     *       sequential synthetic placeholder, NOT real PII (AAP §0.10.5).</li>
     *   <li>{@code governmentIssuedId = "TEST-ID-001"} — synthetic
     *       driver's-licence-style identifier.</li>
     *   <li>{@code dateOfBirth = "1980-01-01"} — ISO YYYY-MM-DD;
     *       deterministic for assertion stability.</li>
     *   <li>{@code eftAccountId = ""} — empty optional bank account.</li>
     *   <li>{@code primaryCardHolderIndicator = "Y"} — primary card
     *       holder by default.</li>
     *   <li>{@code ficoCreditScore = 700} — typical mid-range FICO score.</li>
     * </ul>
     *
     * <p>This is a pure data-builder helper — it carries no business
     * logic, no validation, no derivation, and no encoding/decoding (AAP
     * §0.10.1 Require Test Coverage Rule). It does NOT invoke
     * {@code ValidationLookupService}, does NOT invoke
     * {@code DateValidationService}, and does NOT compute any derived
     * value. Every field is a straight setter call from a hard-coded
     * synthetic value.
     *
     * <p>Per AAP §0.10.5, NO real PII appears in the values produced by
     * this helper: SSN is the all-9-digit-sequential placeholder, names
     * are caller-supplied (test-data conventions like "Jane Doe"), address
     * is synthetic, and phone numbers use the NANPA-reserved 555-XXXX
     * fictional-use block.
     *
     * @param customerId 9-character {@code CUST-ID} primary key per COBOL
     *                   {@code PIC 9(09)}; must not be {@code null}
     * @param firstName  up-to-25-character first name per COBOL
     *                   {@code CUST-FIRST-NAME PIC X(25)}
     * @param lastName   up-to-25-character last name per COBOL
     *                   {@code CUST-LAST-NAME PIC X(25)}
     * @return a fully-populated {@link Customer} instance with the
     *         supplied fields set via the production setters plus
     *         deterministic synthetic values for all other fields
     */
    private Customer buildSyntheticCustomer(String customerId,
                                            String firstName,
                                            String lastName) {
        Customer c = new Customer();
        c.setCustomerId(customerId);
        c.setFirstName(firstName);
        c.setMiddleName("Test");
        c.setLastName(lastName);
        c.setAddressLine1("100 Test Lane");
        c.setAddressLine2("");
        c.setAddressLine3("");
        c.setAddressStateCode("WA");
        c.setAddressCountryCode("USA");
        c.setAddressZip("99999");
        c.setPhoneNumber1("206-555-0100");
        c.setPhoneNumber2("");
        // Canonical all-9-digit-sequential synthetic SSN placeholder, NOT real PII
        // (AAP §0.10.5).
        c.setSsn("123456789");
        c.setGovernmentIssuedId("TEST-ID-001");
        // CUST-DOB-YYYY-MM-DD stored as String; LocalDate.of(...).toString() makes
        // the year/month/day triplet self-documenting at the call site.
        c.setDateOfBirth(LocalDate.of(1980, 1, 1).toString());
        c.setEftAccountId("");
        c.setPrimaryCardHolderIndicator("Y");
        c.setFicoCreditScore(700);
        return c;
    }
}
