package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Customer;
import com.cardemo.repository.CustomerRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link CustomerRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code CUSTDAT}.
 *
 * <p>On the mainframe, {@code CUSTDAT} was provisioned by
 * {@code app/jcl/CUSTFILE.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS KEYS(9 0)
 * RECORDSIZE(500 500) INDEXED}); its fixed-length 500-byte record layout was
 * defined by copybook {@code app/cpy/CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD}).
 * It was reached by keyed CICS file control in the online account-view program
 * {@code COACTVWC} (which resolves {@code CUST-ID} from the card cross-reference
 * and {@code READ}s the customer by key) and dumped sequentially by the batch
 * program {@code CBCUS01C} ({@code OPEN INPUT} + {@code READ NEXT} until
 * {@code FILE STATUS '10'} / end-of-file). In the migrated stack the same data
 * lives in the PostgreSQL {@code customer} table mapped by {@link Customer}, and
 * every access path is served through the Spring Data {@link CustomerRepository}.
 * These tests prove that migration preserves behavior: primary-key access
 * (the {@code COACTVWC} keyed read) and full-table count parity (the
 * {@code CBCUS01C} sequential dump).</p>
 *
 * <h2>Behavioral-parity ground truth</h2>
 * <p>The assertions below pin the <em>exact</em> values that {@code COACTVWC}
 * would have displayed for customer&nbsp;1, taken from the canonical ASCII
 * fixture {@code app/data/ASCII/custdata.txt} (the COBOL parity ground truth)
 * which the Flyway {@code V3__seed_data.sql} migration loads into the throwaway
 * container. Customer&nbsp;1 is <em>Immanuel&nbsp;Madeline&nbsp;Kessler</em>. The
 * fixture holds 50 customer records (ids 1&ndash;50), so {@code count()} must
 * return exactly 50.</p>
 *
 * <h2>{@code X(25)} name fields &mdash; trim-tolerant assertions (AAP &sect;0.7.2)</h2>
 * <p>The three name fields originate from {@code CUST-FIRST-NAME},
 * {@code CUST-MIDDLE-NAME} and {@code CUST-LAST-NAME}, each {@code PIC X(25)} &mdash;
 * fixed-width, space-padded alphanumeric on the mainframe. Whether they are stored
 * space-padded depends on the seed: the authoritative {@code V3__seed_data.sql}
 * inserts them into {@code VARCHAR(25)} columns as trimmed literals (e.g.
 * {@code 'Immanuel'}), so PostgreSQL does <strong>not</strong> re-pad them; but to
 * remain robust against either representation these tests assert against the
 * <em>trimmed</em> value ({@code getCustFirstName().trim()}) rather than relying on
 * exact, non-padded equality. This honors the external-interface-contract rule for
 * fixed-width {@code X(n)} fields (AAP &sect;0.7.2) without coupling the test to a
 * particular padding strategy.</p>
 *
 * <h2>No optimistic locking ({@code @Version}) &mdash; unlike {@code Account}/{@code Card}</h2>
 * <p>{@link Customer} carries <strong>no</strong> JPA {@code @Version} column: it is
 * not an optimistic-locking target (AAP &sect;0.7.5). Only {@code Account} (in
 * {@code COACTUPC}) and {@code Card} (in {@code COCRDUPC}) performed the
 * read-before-update snapshot comparison that maps to {@code @Version}. The dual
 * {@code ACCTDAT}+{@code CUSTDAT} update guarded by the single
 * {@code SYNCPOINT ROLLBACK} of {@code COACTUPC} is reproduced at the
 * {@code @Transactional} service boundary, not here, and the customer record
 * participates as an ordinary non-versioned row. Consequently this class contains
 * <strong>no</strong> optimistic-locking test &mdash; the {@code @Version}-parity
 * coverage lives in the sibling {@code AccountRepositoryIT}/{@code CardRepositoryIT}.</p>
 *
 * <h2>FICO credit score &mdash; {@code PIC 9(03)} domain, NOT the conventional FICO band</h2>
 * <p>{@code CUST-FICO-CREDIT-SCORE} is {@code PIC 9(03)}: a three-digit numeric
 * whose true domain is {@code 0}&ndash;{@code 999}. The synthetic
 * {@code custdata.txt} fixture deliberately seeds values <em>outside</em> the
 * conventional 300&ndash;850 FICO band &mdash; customer&nbsp;1's score is
 * {@code 274}, and several other rows are below 300 (for example id&nbsp;2 = 268,
 * id&nbsp;8 = 51, id&nbsp;26 = 1). {@link #findById_ficoScore_isPopulated()}
 * therefore asserts the field is populated (non-null) and pins the
 * <em>exact</em> seeded value ({@value #SEEDED_CUST1_FICO_SCORE}); it intentionally
 * does <strong>not</strong> assert a {@code [300, 850]} range, which would be
 * factually wrong for this fixture and would fail the build. Do not "tighten" this
 * assertion to the conventional band.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the
 * singleton PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} +
 * {@code @ActiveProfiles("test")} context configuration, the
 * {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers. None of those are re-declared
 * here, so this class shares the one cached Spring context and one Flyway migration
 * with its sibling repository ITs. The class is annotated {@link Transactional} so
 * each test method runs in its own transaction that is rolled back on completion;
 * although every test here is read-only, this keeps isolation uniform with the
 * mutating sibling ITs and leaves the seeded data pristine.</p>
 *
 * <h2>Notes on the values asserted (authoritative-source reconciliation)</h2>
 * <ul>
 *   <li>The primary key {@link Customer#getCustId()} is {@link Long} (migrated from
 *       {@code CUST-ID PIC 9(09)} &rarr; {@code BIGINT}); the repository is typed
 *       {@code JpaRepository<Customer, Long>}, so keyed reads use a {@code long}
 *       literal ({@code findById(1L)}).</li>
 *   <li>Raw SQL cross-checks target the table named {@code customer} (singular),
 *       matching {@code @Table(name = "customer")} on {@link Customer} and the
 *       {@code V1__create_schema.sql} DDL.</li>
 *   <li>{@link CustomerRepository} declares <strong>no</strong> custom query methods
 *       (Minimal Change Clause, AAP &sect;0.7.1): {@code CUSTDAT} was reached only by
 *       its primary key or by a full sequential scan, so only the inherited
 *       {@code JpaRepository} operations are exercised here.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; behavior under test is translated from the frozen AWS CardDemo COBOL
 * baseline at commit SHA {@code 27d6c6f}. The COBOL/JCL sources
 * ({@code app/jcl/CUSTFILE.jcl}, {@code app/cbl/CBCUS01C.cbl}) and the ASCII fixture
 * ({@code app/data/ASCII/custdata.txt}) are read-only reference material and are
 * never copied into this repository.</p>
 *
 * @see CustomerRepository
 * @see Customer
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("CustomerRepository (CUSTDAT / COACTVWC, CBCUS01C) — behavioral-parity integration tests")
class CustomerRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of customer rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/custdata.txt} (ids 1&ndash;50).
     */
    private static final long SEEDED_CUSTOMER_ROWS = 50L;

    /**
     * Customer&nbsp;1's seeded FICO credit score ({@code CUST-FICO-CREDIT-SCORE
     * PIC 9(03)}). This is the verbatim value in both {@code custdata.txt} and
     * {@code V3__seed_data.sql}; it is intentionally below the conventional
     * 300&ndash;850 FICO band (see the class Javadoc) and must be pinned exactly.
     */
    private static final int SEEDED_CUST1_FICO_SCORE = 274;

    /** Customer&nbsp;1's seeded first name ({@code CUST-FIRST-NAME}, trimmed). */
    private static final String SEEDED_CUST1_FIRST_NAME = "Immanuel";

    /** Customer&nbsp;1's seeded middle name ({@code CUST-MIDDLE-NAME}, trimmed). */
    private static final String SEEDED_CUST1_MIDDLE_NAME = "Madeline";

    /** Customer&nbsp;1's seeded last name ({@code CUST-LAST-NAME}, trimmed). */
    private static final String SEEDED_CUST1_LAST_NAME = "Kessler";

    /** Repository under test &mdash; the relational replacement for {@code CUSTDAT}. */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Verifies that a primary-key fetch of customer&nbsp;1 returns the row seeded
     * from {@code custdata.txt} &mdash; the keyed read that {@code COACTVWC}
     * performed against {@code CUSTDAT}.
     *
     * <p>The identifier is asserted exactly ({@code 1L}); the three {@code X(25)}
     * name fields are asserted <em>trim-tolerant</em> (see the class Javadoc), so
     * the test passes whether or not the seed stores them space-padded.</p>
     */
    @Test
    @DisplayName("findById(1L) returns the seeded customer (Immanuel Madeline Kessler), trim-tolerant on X(25) names")
    void findById_returnsSeededCustomer() {
        Optional<Customer> found = customerRepository.findById(1L);

        assertThat(found)
                .as("customer 1 must be present in the Flyway-seeded CUSTDAT replacement")
                .isPresent();

        Customer customer = found.get();

        // Primary key parity — CUST-ID PIC 9(09) -> Long (BIGINT).
        assertThat(customer.getCustId()).isEqualTo(1L);

        // Name parity — CUST-*-NAME PIC X(25). Assert against the trimmed value so
        // the test does not depend on whether the X(25) fields are space-padded
        // (AAP §0.7.2 fixed-width contract). Customer 1 = "Immanuel Madeline Kessler".
        assertThat(customer.getCustFirstName().trim()).isEqualTo(SEEDED_CUST1_FIRST_NAME);
        assertThat(customer.getCustMiddleName().trim()).isEqualTo(SEEDED_CUST1_MIDDLE_NAME);
        assertThat(customer.getCustLastName().trim()).isEqualTo(SEEDED_CUST1_LAST_NAME);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the 50 rows
     * that {@code custdata.txt} / {@code V3__seed_data.sql} provide &mdash; the
     * count equivalent of the sequential {@code OPEN INPUT} / {@code READ NEXT} dump
     * that {@code CBCUS01C} performed over {@code CUSTDAT}. A raw-JDBC
     * {@code COUNT(*)} cross-check confirms the JPA count matches the physical table
     * {@code customer} (singular).
     */
    @Test
    @DisplayName("count() equals the 50 seeded rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(customerRepository.count())
                .as("CUSTDAT replacement must contain exactly the 50 seeded fixture rows")
                .isEqualTo(SEEDED_CUSTOMER_ROWS);

        // Cross-check against the physical table (named "customer", singular) using
        // the inherited JdbcTemplate, bypassing the JPA persistence context entirely.
        Long jdbcCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customer", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_CUSTOMER_ROWS);
    }

    /**
     * Verifies that a keyed read for a non-existent customer yields an empty
     * {@link Optional} rather than throwing &mdash; the JPA equivalent of a VSAM
     * "record not found" ({@code FILE STATUS 23}) on a {@code READ} of an absent
     * key, surfaced by {@link Optional#isEmpty()} for the caller to branch on.
     */
    @Test
    @DisplayName("findById(missing) returns Optional.empty()")
    void findById_missingCustomer_returnsEmptyOptional() {
        Optional<Customer> found = customerRepository.findById(999_999L);

        assertThat(found)
                .as("a key that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }

    /**
     * Verifies that customer&nbsp;1's FICO credit score
     * ({@code CUST-FICO-CREDIT-SCORE PIC 9(03)}) is populated and equals the exact
     * seeded value.
     *
     * <p>The assertion pins the verbatim fixture value
     * ({@value #SEEDED_CUST1_FICO_SCORE}) and deliberately does <strong>not</strong>
     * assert the conventional 300&ndash;850 FICO band: the {@code PIC 9(03)} domain
     * is {@code 0}&ndash;{@code 999}, and this synthetic fixture intentionally seeds
     * sub-300 scores (customer&nbsp;1 = 274). Asserting the conventional band would
     * be factually wrong for the parity ground truth and would fail. See the class
     * Javadoc.</p>
     */
    @Test
    @DisplayName("findById(1L) FICO credit score is populated and equals the seeded value (274)")
    void findById_ficoScore_isPopulated() {
        Customer customer = customerRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("seed data missing customer 1"));

        assertThat(customer.getCustFicoCreditScore())
                .as("CUST-FICO-CREDIT-SCORE PIC 9(03) must be seeded (non-null) for customer 1")
                .isNotNull()
                .isEqualTo(SEEDED_CUST1_FICO_SCORE);
    }
}
