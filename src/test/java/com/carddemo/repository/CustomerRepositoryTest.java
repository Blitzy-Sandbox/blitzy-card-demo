package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.carddemo.entity.Customer;

/**
 * Spring Data JPA slice test for {@link CustomerRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16 database</strong> provisioned by Testcontainers
 * through {@link AbstractRepositoryTest}. It verifies that the inherited
 * {@link org.springframework.data.jpa.repository.JpaRepository JpaRepository}
 * operations correctly persist and retrieve the {@link Customer} aggregate migrated
 * from COBOL copybook {@code app/cpy/CVCUS01Y.cpy} ({@code CUSTOMER-RECORD}, fixed
 * record length 500 bytes, source SHA {@code 27d6c6f} — read-only reference, not
 * copied into the target).
 *
 * <h2>Why a real database (not H2, not a mock)</h2>
 * <p>{@code Customer} carries no {@code COMP-3}/money fields and no optimistic-lock
 * {@code @Version}, but it does exercise three PostgreSQL column types whose fidelity
 * an embedded database cannot be trusted to reproduce: {@code cust_ssn} as
 * {@code VARCHAR(9)} ({@code CUST-SSN PIC 9(09)} &rarr; {@link String}), which must
 * preserve leading zeros verbatim (the {@code BIGINT} storage the migration replaced
 * silently dropped them &mdash; F-SSN-BIGINT); {@code cust_fico_credit_score} as
 * {@code INTEGER} ({@code CUST-FICO-CREDIT-SCORE PIC 9(03)} &rarr; {@link Integer});
 * and {@code cust_dob_yyyy_mm_dd} as {@code DATE}
 * ({@code CUST-DOB-YYYY-MM-DD} &rarr; {@link LocalDate}). Running against the same
 * engine used in production — with the real Flyway migrations
 * ({@code V1__schema.sql} &rarr; {@code V2__indexes.sql} &rarr; {@code V3__seed_data.sql})
 * and Hibernate {@code ddl-auto: validate} — proves those types round-trip
 * faithfully and that the entity mapping matches the migrated schema.</p>
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>{@link #saveAndFindById_roundTripsAllFields()} — a {@code save}/{@code findById}
 *       round-trip that asserts every mapped field, including the leading-zero
 *       {@link String} SSN, and the {@link Integer} and {@link LocalDate} columns.</li>
 *   <li>{@link #existsAndDeleteById_behaveCorrectly()} — {@code existsById} then
 *       {@code deleteById} lifecycle.</li>
 *   <li>{@link #findById_returnsSeededCustomer()} — a light smoke check that the
 *       committed Flyway V3 seed (50 customers, {@code cust_id} 1..50) is present and
 *       mapped.</li>
 * </ul>
 *
 * <h2>Test isolation</h2>
 * <p>Each {@code @Test} runs inside the {@code @DataJpaTest} transaction that is
 * rolled back at completion, so the inserts here never leak between methods. The
 * committed V3 seed occupies {@code cust_id} 1..50; the mutating tests therefore use
 * <em>test-owned</em> identifiers {@code >= 900000000L} so they can never collide
 * with a seeded primary key. No custom finder is invented — the repository exposes
 * only the inherited keyed-access contract (scope guard, Gate 7).</p>
 */
class CustomerRepositoryTest extends AbstractRepositoryTest {

    // --- Test-owned identifiers (>= 900000000L to avoid the 50 seeded customers) ---

    /** Primary key used by the full-field round-trip test. */
    private static final long ROUND_TRIP_ID = 900_000_200L;

    /** Primary key used by the exists/delete lifecycle test. */
    private static final long EXISTS_DELETE_ID = 900_000_201L;

    /** A known seeded primary key (Flyway V3 loads {@code cust_id} 1..50). */
    private static final long SEEDED_CUSTOMER_ID = 1L;

    // --- Fixed, obviously-fabricated field values (single source of truth) ---
    // Each value is within its column's VARCHAR(n) limit so no length constraint is
    // violated. They are declared once and reused by both newCustomer(...) and the
    // round-trip assertions, so persisted state and expected state can never drift.

    private static final String FIRST_NAME = "Jonathan";
    private static final String MIDDLE_NAME = "Robert";
    private static final String LAST_NAME = "Fitzgerald";
    private static final String ADDR_LINE_1 = "100 Congress Avenue";
    private static final String ADDR_LINE_2 = "Suite 2200";
    private static final String ADDR_LINE_3 = "Austin Central";
    private static final String STATE_CD = "TX";
    private static final String COUNTRY_CD = "USA";
    private static final String ZIP = "73301";
    private static final String PHONE_1 = "(512)555-0100";
    private static final String PHONE_2 = "(512)555-0199";
    private static final String SSN = "023456789";
    private static final String GOVT_ID = "TX-DL-88990011";
    private static final LocalDate DOB = LocalDate.of(1985, 6, 15);
    private static final String EFT_ACCOUNT_ID = "0012345678";
    private static final String PRI_CARD_HOLDER_IND = "Y";
    private static final Integer FICO = 750;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Persists a fully-populated {@link Customer} and reads it back by primary key,
     * asserting that <em>every</em> mapped field survives the database round-trip
     * unchanged. Clearing the persistence context between the write and the read
     * forces a genuine {@code SELECT} (rather than returning the still-managed
     * first-level-cache instance), so the assertions validate the PostgreSQL column
     * mappings and not merely the in-memory object.
     *
     * <p>The non-{@code varchar} columns and the leading-zero SSN are the point of the
     * test: {@code custSsn} exercises {@code VARCHAR(9)} ({@link String}) and must keep
     * its leading zero, {@code custFicoCreditScore} exercises {@code INTEGER}
     * ({@link Integer}), and {@code custDobYyyyMmDd} exercises {@code DATE}
     * ({@link LocalDate}).</p>
     */
    @Test
    @DisplayName("save + findById round-trips every field (leading-zero String SSN, Integer FICO, LocalDate DOB)")
    void saveAndFindById_roundTripsAllFields() {
        Customer c = newCustomer(ROUND_TRIP_ID);

        customerRepository.saveAndFlush(c);
        entityManager.clear();

        Customer found = customerRepository.findById(ROUND_TRIP_ID).orElseThrow();

        assertThat(found.getCustId()).isEqualTo(ROUND_TRIP_ID);
        assertThat(found.getCustFirstName()).isEqualTo(FIRST_NAME);
        assertThat(found.getCustMiddleName()).isEqualTo(MIDDLE_NAME);
        assertThat(found.getCustLastName()).isEqualTo(LAST_NAME);
        assertThat(found.getCustAddrLine1()).isEqualTo(ADDR_LINE_1);
        assertThat(found.getCustAddrLine2()).isEqualTo(ADDR_LINE_2);
        assertThat(found.getCustAddrLine3()).isEqualTo(ADDR_LINE_3);
        assertThat(found.getCustAddrStateCd()).isEqualTo(STATE_CD);
        assertThat(found.getCustAddrCountryCd()).isEqualTo(COUNTRY_CD);
        assertThat(found.getCustAddrZip()).isEqualTo(ZIP);
        assertThat(found.getCustPhoneNum1()).isEqualTo(PHONE_1);
        assertThat(found.getCustPhoneNum2()).isEqualTo(PHONE_2);
        // VARCHAR(9) round-trip: the nine-digit SSN is preserved verbatim as a
        // String, including its leading zero (the BIGINT storage the migration
        // replaced silently dropped leading zeros -- F-SSN-BIGINT).
        assertThat(found.getCustSsn()).isEqualTo(SSN);
        assertThat(found.getCustGovtIssuedId()).isEqualTo(GOVT_ID);
        // DATE round-trip: date of birth preserved as LocalDate.
        assertThat(found.getCustDobYyyyMmDd()).isEqualTo(DOB);
        assertThat(found.getCustEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
        assertThat(found.getCustPriCardHolderInd()).isEqualTo(PRI_CARD_HOLDER_IND);
        // INTEGER round-trip: FICO score preserved as Integer.
        assertThat(found.getCustFicoCreditScore()).isEqualTo(FICO);
    }

    /**
     * Verifies the existence and deletion lifecycle: after a customer is persisted,
     * {@code existsById} reports {@code true}; after {@code deleteById} is flushed,
     * {@code findById} returns an empty {@link java.util.Optional}. The persistence
     * context is cleared between operations so each assertion reflects the database
     * state rather than a cached managed instance.
     */
    @Test
    @DisplayName("existsById is true after save; findById is empty after deleteById")
    void existsAndDeleteById_behaveCorrectly() {
        customerRepository.saveAndFlush(newCustomer(EXISTS_DELETE_ID));
        entityManager.clear();

        assertThat(customerRepository.existsById(EXISTS_DELETE_ID)).isTrue();

        customerRepository.deleteById(EXISTS_DELETE_ID);
        entityManager.flush();
        entityManager.clear();

        assertThat(customerRepository.findById(EXISTS_DELETE_ID)).isEmpty();
    }

    /**
     * Light smoke check that the committed Flyway {@code V3__seed_data.sql} seed is
     * present and mapped: the first seeded customer ({@code cust_id = 1}) must be
     * retrievable, expose its identifier, and carry a non-null last name. Exact
     * seeded personal values are intentionally not pinned here — that is the
     * responsibility of the seed/migration tests — so the assertions stay minimal.
     */
    @Test
    @DisplayName("findById returns a seeded customer (Flyway V3 seed, cust_id=1)")
    void findById_returnsSeededCustomer() {
        assertThat(customerRepository.findById(SEEDED_CUSTOMER_ID))
                .as("Flyway V3 seed must load customer with cust_id=%d", SEEDED_CUSTOMER_ID)
                .isPresent()
                .get()
                .satisfies(seeded -> {
                    assertThat(seeded.getCustId()).isEqualTo(SEEDED_CUSTOMER_ID);
                    assertThat(seeded.getCustLastName()).isNotNull();
                });
    }

    /**
     * Builds a fully-populated, transient {@link Customer} whose field values are the
     * fixed constants declared above (each within its column's length limit). The
     * only per-call variation is the primary key, so the same distinctive payload can
     * be reused across tests while distinct identifiers prevent primary-key
     * collisions with the seed or between tests.
     *
     * @param id the application-assigned natural primary key ({@code CUST-ID})
     * @return a new, unmanaged {@code Customer} ready to persist
     */
    private Customer newCustomer(Long id) {
        Customer c = new Customer();
        c.setCustId(id);
        c.setCustFirstName(FIRST_NAME);
        c.setCustMiddleName(MIDDLE_NAME);
        c.setCustLastName(LAST_NAME);
        c.setCustAddrLine1(ADDR_LINE_1);
        c.setCustAddrLine2(ADDR_LINE_2);
        c.setCustAddrLine3(ADDR_LINE_3);
        c.setCustAddrStateCd(STATE_CD);
        c.setCustAddrCountryCd(COUNTRY_CD);
        c.setCustAddrZip(ZIP);
        c.setCustPhoneNum1(PHONE_1);
        c.setCustPhoneNum2(PHONE_2);
        c.setCustSsn(SSN);
        c.setCustGovtIssuedId(GOVT_ID);
        c.setCustDobYyyyMmDd(DOB);
        c.setCustEftAccountId(EFT_ACCOUNT_ID);
        c.setCustPriCardHolderInd(PRI_CARD_HOLDER_IND);
        c.setCustFicoCreditScore(FICO);
        return c;
    }
}
