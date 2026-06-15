package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.TransactionCategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link TransactionCategoryRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational replacement
 * of the legacy AWS CardDemo VSAM KSDS dataset {@code TRANCATG}.
 *
 * <p>On the mainframe, {@code TRANCATG} was provisioned by {@code app/jcl/TRANCATG.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS KEYS(6 0) RECORDSIZE(60 60)
 * INDEXED}); its fixed-length 60-byte record layout was defined by copybook
 * {@code app/cpy/CVTRA04Y.cpy} ({@code 01 TRAN-CAT-RECORD}: {@code TRAN-CAT-KEY} =
 * {@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)}, then
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)} and a trailing {@code FILLER PIC X(04)} =
 * {@code 2 + 4 + 50 + 4 = 60}). It is small, static <em>reference&nbsp;/&nbsp;lookup</em>
 * data &mdash; a {@code (transaction-type code, transaction-category code)} pair mapped to a
 * human-readable description &mdash; that was consulted, never written, by the batch programs:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting): reads the cluster while
 *       validating each incoming transaction's type/category combination before the row is
 *       posted to the ledger (the validation cascade).</li>
 *   <li>{@code CBTRN03C} (transaction-detail report): paragraph {@code 1500-C-LOOKUP-TRANCATG}
 *       performs a keyed {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} on the six-byte
 *       composite key ({@code RECORD KEY IS FD-TRAN-CAT-KEY}) to resolve the stored
 *       type/category combination into its {@code TRAN-CAT-TYPE-DESC} for the printed report;
 *       an {@code INVALID KEY} sets {@code IO-STATUS 23} (record-not-found).</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL {@code transaction_category}
 * table mapped by {@link TransactionCategory}, and every access path is served through the
 * Spring Data {@link TransactionCategoryRepository}. These tests prove that migration preserves
 * behavior: composite-key access (the {@code CBTRN03C}/{@code CBTRN02C} keyed lookup),
 * reference-table count parity, and the "record not found" path.</p>
 *
 * <h2>The single subtlety &mdash; a composite {@code @EmbeddedId} key (NOT a scalar key)</h2>
 * <p>This is what distinguishes {@code TRANCATG} from its sibling {@code TRANTYPE}. The legacy
 * KSDS key was the six-byte concatenation of the two {@code TRAN-CAT-KEY} sub-fields
 * ({@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)}). That physical concatenated
 * key is migrated to an explicit, typed composite key &mdash; the {@code @Embeddable}
 * {@link TransactionCategoryId} &mdash; so the entity carries
 * {@code @EmbeddedId TransactionCategoryId id} and the repository is typed
 * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}. Consequently
 * {@link org.springframework.data.repository.CrudRepository#findById(Object) findById(...)}
 * takes a <strong>fully-populated</strong> {@link TransactionCategoryId} (both the type code and
 * the category code must be set), exactly as the legacy keyed read required the complete six-byte
 * key. This deliberately contrasts with {@code TransactionTypeRepository}, whose
 * {@code TransactionType} (dataset {@code TRANTYPE}, copybook {@code CVTRA03Y.cpy}) keys on the
 * type code <em>alone</em> via a plain {@code @Id String}.</p>
 * <p>The key components are constructed via the all-args constructor in their original COBOL
 * field order &mdash; {@code new TransactionCategoryId(typeCode, catCode)} &mdash; where
 * {@code typeCode} is the {@code TRAN-TYPE-CD PIC X(02)} {@link String} and {@code catCode} is the
 * {@code TRAN-CAT-CD PIC 9(04)} {@link Integer}. The order is type-code <em>then</em> category-code
 * and must not be transposed.</p>
 *
 * <h2>Behavioral-parity ground truth (the 18 category rows)</h2>
 * <p>The assertions below pin the <em>exact</em> rows that the COBOL programs would have resolved,
 * taken from the canonical ASCII fixture {@code app/data/ASCII/trancatg.txt} (the parity ground
 * truth, 18 fixed-width 60-byte records) which the Flyway {@code V3__seed_data.sql} migration loads
 * into the throwaway container. The fixture holds exactly eighteen records, so {@code count()} must
 * return {@code 18}. A representative cross-section of the composite keys is asserted: the first
 * row {@code (01, 1)}, the last category of type {@code 01} &mdash; {@code (01, 5)}, the first row
 * of type {@code 02} &mdash; {@code (02, 1)}, and the final fixture row {@code (07, 1)}.</p>
 *
 * <h2>{@code X(50)} description &mdash; trim-tolerant assertions (AAP &sect;0.7.2)</h2>
 * <p>{@code TRAN-CAT-TYPE-DESC} is {@code PIC X(50)} &mdash; fixed-width, space-padded alphanumeric
 * on the mainframe. The authoritative {@code V3__seed_data.sql} inserts the descriptions into the
 * {@code category_description VARCHAR(50)} column as trimmed literals (e.g.
 * {@code 'Regular Sales Draft'}), so PostgreSQL does <strong>not</strong> re-pad them; but to remain
 * robust against either representation these tests assert against the <em>trimmed</em> value
 * ({@code getTranCatTypeDesc().trim()}) rather than relying on exact, non-padded equality. This
 * honors the external-interface-contract rule for fixed-width {@code X(n)} fields (AAP &sect;0.7.2)
 * without coupling the test to a particular padding strategy.</p>
 *
 * <h2>No optimistic locking ({@code @Version}); no custom query methods</h2>
 * <p>{@link TransactionCategory} carries <strong>no</strong> JPA {@code @Version} column: per AAP
 * &sect;0.7.5 optimistic locking is applied only to {@code Account} (in {@code COACTUPC}) and
 * {@code Card} (in {@code COCRDUPC}); {@code TRANCATG} is static reference data that is read but
 * never rewritten through a read-update snapshot comparison. {@link TransactionCategoryRepository}
 * likewise declares <strong>no</strong> custom query methods (Minimal Change Clause, AAP
 * &sect;0.7.1): {@code TRANCATG} was reached only by a single keyed read or a full browse, so only
 * the inherited {@code JpaRepository} operations
 * ({@code findById(TransactionCategoryId)} / {@code count()}) are exercised here.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the singleton
 * PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")}
 * context configuration, the {@code @DynamicPropertySource} datasource wiring, and the
 * {@code protected} {@code jdbcTemplate}/{@code entityManager} helpers. None of those are
 * re-declared here, so this class shares the one cached Spring context and one Flyway migration
 * with its sibling repository ITs. The class is annotated {@link Transactional} so each test
 * method runs in its own transaction that is rolled back on completion; although every test here is
 * read-only, this keeps isolation uniform with the mutating sibling ITs and leaves the seeded data
 * pristine.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source equivalent;
 * behavior under test is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}. The COBOL/JCL sources ({@code app/jcl/TRANCATG.jcl},
 * {@code app/cbl/CBTRN03C.cbl}, {@code app/cbl/CBTRN02C.cbl}) and the ASCII fixture
 * ({@code app/data/ASCII/trancatg.txt}) are read-only reference material and are never copied into
 * this repository.</p>
 *
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("TransactionCategoryRepository (TRANCATG / CBTRN02C, CBTRN03C) — composite-key parity integration tests")
class TransactionCategoryRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of transaction-category rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/trancatg.txt} (18 fixed-width 60-byte records).
     */
    private static final long SEEDED_CATEGORY_ROWS = 18L;

    /** {@code TRAN-TYPE-CD} of the first fixture row and of the {@code (01, 1)} category. */
    private static final String TYPE_PURCHASE = "01";

    /** {@code TRAN-TYPE-CD} of the {@code (02, 1)} category (the first type-{@code 02} row). */
    private static final String TYPE_PAYMENT = "02";

    /** {@code TRAN-TYPE-CD} of the final fixture row {@code (07, 1)}. */
    private static final String TYPE_ADJUSTMENT = "07";

    /** {@code TRAN-CAT-CD} of "Regular Sales Draft" ({@code (01, 1)}). */
    private static final int CAT_REGULAR_SALES = 1;

    /** {@code TRAN-CAT-CD} of "Interest Amount" ({@code (01, 5)}). */
    private static final int CAT_INTEREST = 5;

    /** Trimmed {@code TRAN-CAT-TYPE-DESC} for composite key {@code (01, 1)}. */
    private static final String DESC_REGULAR_SALES = "Regular Sales Draft";

    /** Trimmed {@code TRAN-CAT-TYPE-DESC} for composite key {@code (01, 5)}. */
    private static final String DESC_INTEREST = "Interest Amount";

    /** Trimmed {@code TRAN-CAT-TYPE-DESC} for composite key {@code (02, 1)}. */
    private static final String DESC_CASH_PAYMENT = "Cash payment";

    /** Trimmed {@code TRAN-CAT-TYPE-DESC} for composite key {@code (07, 1)}. */
    private static final String DESC_SALES_DRAFT_CREDIT_ADJ = "Sales draft credit adjustment";

    /** A composite key (type {@code 99}, category {@code 999}) intentionally absent from the seed. */
    private static final String MISSING_TYPE = "99";

    /** Category component of the intentionally-absent composite key. */
    private static final int MISSING_CAT = 999;

    /** Repository under test &mdash; the relational replacement for {@code TRANCATG}. */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Verifies that composite-key fetches of seeded categories return the rows loaded from
     * {@code trancatg.txt} &mdash; the keyed lookup that {@code CBTRN03C}
     * ({@code 1500-C-LOOKUP-TRANCATG}) and the {@code CBTRN02C} posting validation performed
     * against {@code TRANCATG} on the six-byte {@code TRAN-CAT-KEY}.
     *
     * <p>A representative cross-section of the 18 fixture rows is asserted, each constructed with
     * the all-args {@link TransactionCategoryId#TransactionCategoryId(String, Integer)} constructor
     * in COBOL field order ({@code typeCode}, then {@code catCode}):</p>
     * <ul>
     *   <li>{@code (01, 1)} &rarr; "Regular Sales Draft" (first fixture row);</li>
     *   <li>{@code (01, 5)} &rarr; "Interest Amount" (last category of type {@code 01});</li>
     *   <li>{@code (02, 1)} &rarr; "Cash payment" (first type-{@code 02} row);</li>
     *   <li>{@code (07, 1)} &rarr; "Sales draft credit adjustment" (final fixture row).</li>
     * </ul>
     * <p>For each, the returned entity must be present, its {@code @EmbeddedId} must round-trip back
     * to the requested composite key (proving value-based key equality the JPA provider relies on),
     * and the {@code X(50)} description is asserted <em>trim-tolerant</em> (see the class Javadoc),
     * so the test passes whether or not the seed stores it space-padded.</p>
     */
    @Test
    @DisplayName("findById((01,1)/(01,5)/(02,1)/(07,1)) returns the seeded categories, trim-tolerant on X(50) desc")
    void findById_byCompositeKey_returnsSeededCategory() {
        // (01, 1) -> "Regular Sales Draft" (first row of trancatg.txt).
        assertSeededCategory(TYPE_PURCHASE, CAT_REGULAR_SALES, DESC_REGULAR_SALES);
        // (01, 5) -> "Interest Amount" (last category of type 01).
        assertSeededCategory(TYPE_PURCHASE, CAT_INTEREST, DESC_INTEREST);
        // (02, 1) -> "Cash payment" (first type-02 row).
        assertSeededCategory(TYPE_PAYMENT, CAT_REGULAR_SALES, DESC_CASH_PAYMENT);
        // (07, 1) -> "Sales draft credit adjustment" (final fixture row).
        assertSeededCategory(TYPE_ADJUSTMENT, CAT_REGULAR_SALES, DESC_SALES_DRAFT_CREDIT_ADJ);
    }

    /**
     * Asserts that the composite key {@code (typeCode, catCode)} resolves, via
     * {@code findById(TransactionCategoryId)}, to a present category whose embedded id round-trips
     * to the requested key and whose trimmed description equals {@code expectedDescription}.
     *
     * <p>Centralizing the per-row assertion keeps {@link #findById_byCompositeKey_returnsSeededCategory()}
     * declarative and ensures every asserted row exercises the identical contract: composite-key
     * presence, {@code @EmbeddedId} value round-trip ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}),
     * and the trim-tolerant {@code X(50)} description rule (AAP &sect;0.7.2).</p>
     *
     * @param typeCode            the {@code TRAN-TYPE-CD} component ({@code PIC X(02)})
     * @param catCode             the {@code TRAN-CAT-CD} component ({@code PIC 9(04)})
     * @param expectedDescription the trimmed {@code TRAN-CAT-TYPE-DESC} the row must carry
     */
    private void assertSeededCategory(String typeCode, int catCode, String expectedDescription) {
        // Construct the six-byte composite key in COBOL field order: type code, then category code.
        TransactionCategoryId key = new TransactionCategoryId(typeCode, catCode);

        Optional<TransactionCategory> found = transactionCategoryRepository.findById(key);
        assertThat(found)
                .as("composite key (%s, %d) must be present in the Flyway-seeded TRANCATG replacement",
                        typeCode, catCode)
                .isPresent();

        TransactionCategory category = found.get();

        // Composite-key parity: the @EmbeddedId must round-trip by value to the requested key
        // (proves TransactionCategoryId.equals/hashCode cover both components — the contract JPA
        // relies on to resolve the keyed read, mirroring the legacy six-byte TRAN-CAT-KEY).
        assertThat(category.getId())
                .as("the @EmbeddedId must round-trip to the requested composite key (%s, %d)",
                        typeCode, catCode)
                .isEqualTo(key);
        assertThat(category.getId().getTypeCode())
                .as("TRAN-TYPE-CD parity for composite key (%s, %d)", typeCode, catCode)
                .isEqualTo(typeCode);
        assertThat(category.getId().getCatCode())
                .as("TRAN-CAT-CD parity for composite key (%s, %d)", typeCode, catCode)
                .isEqualTo(catCode);

        // Description parity — TRAN-CAT-TYPE-DESC PIC X(50). Assert against the trimmed value so the
        // test does not depend on whether the X(50) field is space-padded (AAP §0.7.2).
        assertThat(category.getTranCatTypeDesc())
                .as("description for composite key (%s, %d) must not be null", typeCode, catCode)
                .isNotNull();
        assertThat(category.getTranCatTypeDesc().trim())
                .as("description for composite key (%s, %d) must be \"%s\"",
                        typeCode, catCode, expectedDescription)
                .isEqualTo(expectedDescription);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the eighteen rows that
     * {@code trancatg.txt} / {@code V3__seed_data.sql} provide &mdash; the count equivalent of a full
     * browse of the small {@code TRANCATG} reference cluster. A raw-JDBC {@code COUNT(*)} cross-check
     * confirms the JPA count matches the physical table {@code transaction_category} (singular).
     */
    @Test
    @DisplayName("count() equals the 18 seeded rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(transactionCategoryRepository.count())
                .as("TRANCATG replacement must contain exactly the 18 seeded fixture rows")
                .isEqualTo(SEEDED_CATEGORY_ROWS);

        // Cross-check against the physical table (named "transaction_category", singular) using the
        // inherited JdbcTemplate, bypassing the JPA persistence context. NOTE: the table is singular
        // per V1__create_schema.sql and the entity's @Table(name = "transaction_category").
        Long jdbcCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaction_category", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) on transaction_category must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_CATEGORY_ROWS);
    }

    /**
     * Verifies that a keyed read for a non-existent composite key yields an empty {@link Optional}
     * rather than throwing &mdash; the JPA equivalent of a VSAM "record not found"
     * ({@code FILE STATUS 23}) on a {@code READ} of an absent key, which is the COBOL
     * "INVALID TRAN CATG KEY" path that the {@code CBTRN02C} validation cascade and the
     * {@code CBTRN03C} {@code 1500-C-LOOKUP-TRANCATG} branch detect via {@link Optional#isEmpty()}.
     *
     * <p>The probe key {@code (99, 999)} is well-formed (both components set, as the legacy keyed
     * read required a complete six-byte key) but is intentionally absent from the seed.</p>
     */
    @Test
    @DisplayName("findById((99, 999)) returns Optional.empty() (no such composite key)")
    void findById_missingCompositeKey_returnsEmpty() {
        Optional<TransactionCategory> found =
                transactionCategoryRepository.findById(new TransactionCategoryId(MISSING_TYPE, MISSING_CAT));

        assertThat(found)
                .as("a composite key that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }

    /**
     * Guards the {@code @EmbeddedId} value-equality contract that JPA depends on to resolve
     * {@code findById(...)} for the composite key.
     *
     * <p>Hibernate looks up an {@code @EmbeddedId} entity by hashing/comparing the supplied key
     * instance against managed/persisted identifiers, so {@link TransactionCategoryId} must define
     * {@code equals}/{@code hashCode} <em>by value</em> across <strong>both</strong> components
     * ({@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}). This test pins that contract directly &mdash;
     * if it ever regressed (for example to identity equality), the keyed-read tests above would fail
     * mysteriously, so asserting it here localizes the cause.</p>
     *
     * <p>Two independently-constructed {@code (01, 1)} keys must be {@code equals} and share a
     * {@code hashCode}; keys differing in <em>either</em> component &mdash; {@code (01, 2)} (differs
     * in category) and {@code (02, 1)} (differs in type) &mdash; must <em>not</em> be equal,
     * confirming both fields participate in equality.</p>
     */
    @Test
    @DisplayName("TransactionCategoryId equals()/hashCode() are value-based across both key components")
    void embeddedId_equalsAndHashCode_behaveByValue() {
        TransactionCategoryId a = new TransactionCategoryId(TYPE_PURCHASE, CAT_REGULAR_SALES); // (01, 1)
        TransactionCategoryId b = new TransactionCategoryId(TYPE_PURCHASE, CAT_REGULAR_SALES); // (01, 1)

        assertThat(a)
                .as("two TransactionCategoryId(\"01\", 1) instances must be value-equal")
                .isEqualTo(b);
        assertThat(a.hashCode())
                .as("value-equal composite keys must share a hashCode (required by the @EmbeddedId contract)")
                .isEqualTo(b.hashCode());

        // Differing in the category component alone must break equality (catCode participates).
        assertThat(a)
                .as("keys differing only in TRAN-CAT-CD must not be equal")
                .isNotEqualTo(new TransactionCategoryId(TYPE_PURCHASE, CAT_REGULAR_SALES + 1)); // (01, 2)

        // Differing in the type component alone must break equality (typeCode participates).
        assertThat(a)
                .as("keys differing only in TRAN-TYPE-CD must not be equal")
                .isNotEqualTo(new TransactionCategoryId(TYPE_PAYMENT, CAT_REGULAR_SALES)); // (02, 1)
    }
}
