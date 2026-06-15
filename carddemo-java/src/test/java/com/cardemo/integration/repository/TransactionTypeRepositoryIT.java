package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.TransactionType;
import com.cardemo.repository.TransactionTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link TransactionTypeRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code TRANTYPE}.
 *
 * <p>On the mainframe, {@code TRANTYPE} was provisioned by
 * {@code app/jcl/TRANTYPE.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS KEYS(2 0)
 * RECORDSIZE(60 60) INDEXED}); its fixed-length 60-byte record layout was defined
 * by copybook {@code app/cpy/CVTRA03Y.cpy} ({@code 01 TRAN-TYPE-RECORD}:
 * {@code TRAN-TYPE PIC X(02)} + {@code TRAN-TYPE-DESC PIC X(50)} +
 * {@code FILLER PIC X(08)}). It is small, static <em>reference&nbsp;/&nbsp;lookup</em>
 * data &mdash; a two-character transaction-type code mapped to a human-readable
 * description &mdash; that was consulted, never written, by the batch programs:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting): reads the
 *       cluster while validating each incoming transaction's type code before the
 *       row is posted to the ledger (the validation cascade).</li>
 *   <li>{@code CBTRN03C} (transaction-detail report): paragraph
 *       {@code 1500-B-LOOKUP-TRANTYPE} performs a keyed {@code READ TRANTYPE-FILE}
 *       on the two-byte code to resolve the stored type code into its
 *       {@code TRAN-TYPE-DESC} for the printed report.</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL
 * {@code transaction_type} table mapped by {@link TransactionType}, and every
 * access path is served through the Spring Data {@link TransactionTypeRepository}.
 * These tests prove that migration preserves behavior: primary-key access (the
 * {@code CBTRN03C}/{@code CBTRN02C} keyed lookup), full-table count parity, and the
 * complete set of seeded type codes.</p>
 *
 * <h2>Behavioral-parity ground truth (the 7 type codes)</h2>
 * <p>The assertions below pin the <em>exact</em> rows that the COBOL programs would
 * have resolved, taken from the canonical ASCII fixture
 * {@code app/data/ASCII/trantype.txt} (the parity ground truth) which the Flyway
 * {@code V3__seed_data.sql} migration loads into the throwaway container. The
 * fixture holds exactly seven records, so {@code count()} must return {@code 7} and
 * the full set of codes is
 * <code>{01, 02, 03, 04, 05, 06, 07}</code>:</p>
 * <table border="1">
 *   <caption>{@code trantype.txt} seeded rows</caption>
 *   <tr><th>{@code type_code}</th><th>{@code type_description}</th></tr>
 *   <tr><td>01</td><td>Purchase</td></tr>
 *   <tr><td>02</td><td>Payment</td></tr>
 *   <tr><td>03</td><td>Credit</td></tr>
 *   <tr><td>04</td><td>Authorization</td></tr>
 *   <tr><td>05</td><td>Refund</td></tr>
 *   <tr><td>06</td><td>Reversal</td></tr>
 *   <tr><td>07</td><td>Adjustment</td></tr>
 * </table>
 *
 * <h2>Simple {@link String} primary key &mdash; NOT a composite key</h2>
 * <p>This is the entity's single subtlety. {@code TRAN-TYPE PIC X(02)} is a fixed
 * two-character (alphanumeric) code that becomes a <strong>plain single-column
 * primary key</strong> ({@link TransactionType#getTranType()}, PostgreSQL
 * {@code VARCHAR(2)}), so the repository is typed
 * {@code JpaRepository<TransactionType, String>} and keyed reads use a two-character
 * {@link String} literal ({@code findById("01")}). It deliberately contrasts with
 * the sibling {@code TransactionCategory} (dataset {@code TRANCATG}, copybook
 * {@code CVTRA04Y.cpy}), which keys on the type code <em>plus</em> a four-digit
 * category code and therefore uses an {@code @EmbeddedId} composite key. These
 * tests therefore exercise {@code findById(String)} with a simple key.</p>
 *
 * <h2>{@code X(50)} description &mdash; trim-tolerant assertions (AAP &sect;0.7.2)</h2>
 * <p>{@code TRAN-TYPE-DESC} is {@code PIC X(50)} &mdash; fixed-width, space-padded
 * alphanumeric on the mainframe. The authoritative {@code V3__seed_data.sql} inserts
 * the descriptions into the {@code VARCHAR(50)} column as trimmed literals (e.g.
 * {@code 'Purchase'}), so PostgreSQL does <strong>not</strong> re-pad them; but to
 * remain robust against either representation these tests assert against the
 * <em>trimmed</em> value ({@code getTranTypeDesc().trim()}) rather than relying on
 * exact, non-padded equality. This honors the external-interface-contract rule for
 * fixed-width {@code X(n)} fields (AAP &sect;0.7.2) without coupling the test to a
 * particular padding strategy.</p>
 *
 * <h2>No optimistic locking ({@code @Version}); no custom query methods</h2>
 * <p>{@link TransactionType} carries <strong>no</strong> JPA {@code @Version}
 * column: per AAP &sect;0.7.5 optimistic locking is applied only to {@code Account}
 * (in {@code COACTUPC}) and {@code Card} (in {@code COCRDUPC}); {@code TRANTYPE} is
 * static reference data that is read but never rewritten through a read-update
 * snapshot comparison. {@link TransactionTypeRepository} likewise declares
 * <strong>no</strong> custom query methods (Minimal Change Clause, AAP
 * &sect;0.7.1): {@code TRANTYPE} was reached only by a single keyed read or a full
 * browse, so only the inherited {@code JpaRepository} operations
 * ({@code findById(String)} / {@code findAll()} / {@code count()}) are exercised
 * here.</p>
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
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; behavior under test is translated from the frozen AWS CardDemo COBOL
 * baseline at commit SHA {@code 27d6c6f}. The COBOL/JCL sources
 * ({@code app/jcl/TRANTYPE.jcl}, {@code app/cbl/CBTRN03C.cbl},
 * {@code app/cbl/CBTRN02C.cbl}) and the ASCII fixture
 * ({@code app/data/ASCII/trantype.txt}) are read-only reference material and are
 * never copied into this repository.</p>
 *
 * @see TransactionTypeRepository
 * @see TransactionType
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("TransactionTypeRepository (TRANTYPE / CBTRN02C, CBTRN03C) — behavioral-parity integration tests")
class TransactionTypeRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of transaction-type rows seeded by {@code V3__seed_data.sql}
     * from {@code app/data/ASCII/trantype.txt} (codes {@code 01}&ndash;{@code 07}).
     */
    private static final long SEEDED_TYPE_ROWS = 7L;

    /** Seeded type code for "Purchase" ({@code TRAN-TYPE} of the first fixture row). */
    private static final String PURCHASE_CODE = "01";

    /** Seeded description for type code {@code 01} ({@code TRAN-TYPE-DESC}, trimmed). */
    private static final String PURCHASE_DESC = "Purchase";

    /** Seeded type code for "Adjustment" ({@code TRAN-TYPE} of the last fixture row). */
    private static final String ADJUSTMENT_CODE = "07";

    /** Seeded description for type code {@code 07} ({@code TRAN-TYPE-DESC}, trimmed). */
    private static final String ADJUSTMENT_DESC = "Adjustment";

    /** A two-byte code that is intentionally absent from the seed (no such row). */
    private static final String MISSING_CODE = "99";

    /**
     * The complete set of two-character type codes seeded from
     * {@code trantype.txt}. The full browse ({@code findAll()}) must yield exactly
     * these identifiers, irrespective of row order.
     */
    private static final Set<String> EXPECTED_TYPE_CODES =
            Set.of("01", "02", "03", "04", "05", "06", "07");

    /** Repository under test &mdash; the relational replacement for {@code TRANTYPE}. */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /**
     * Verifies that primary-key fetches of the seeded type codes return the rows
     * loaded from {@code trantype.txt} &mdash; the keyed lookup that
     * {@code CBTRN03C} ({@code 1500-B-LOOKUP-TRANTYPE}) and the {@code CBTRN02C}
     * validation cascade performed against {@code TRANTYPE}.
     *
     * <p>Both {@code findById("01")} (Purchase) and {@code findById("07")}
     * (Adjustment) must be present; the primary key is asserted exactly while the
     * {@code X(50)} description is asserted <em>trim-tolerant</em> (see the class
     * Javadoc), so the test passes whether or not the seed stores it space-padded.</p>
     */
    @Test
    @DisplayName("findById(\"01\")/(\"07\") return the seeded types (Purchase/Adjustment), trim-tolerant on X(50) desc")
    void findById_returnsSeededType() {
        // Type code 01 -> "Purchase" (first row of trantype.txt).
        Optional<TransactionType> purchase = transactionTypeRepository.findById(PURCHASE_CODE);
        assertThat(purchase)
                .as("type code 01 (Purchase) must be present in the Flyway-seeded TRANTYPE replacement")
                .isPresent();
        TransactionType purchaseType = purchase.get();
        assertThat(purchaseType.getTranType())
                .as("primary key parity — TRAN-TYPE PIC X(02) -> String(2)")
                .isEqualTo(PURCHASE_CODE);
        // Description parity — TRAN-TYPE-DESC PIC X(50). Assert against the trimmed
        // value so the test does not depend on whether the X(50) field is
        // space-padded (AAP §0.7.2 fixed-width contract).
        assertThat(purchaseType.getTranTypeDesc().trim())
                .as("type code 01 description must be \"Purchase\"")
                .isEqualTo(PURCHASE_DESC);

        // Type code 07 -> "Adjustment" (last row of trantype.txt).
        Optional<TransactionType> adjustment = transactionTypeRepository.findById(ADJUSTMENT_CODE);
        assertThat(adjustment)
                .as("type code 07 (Adjustment) must be present in the Flyway-seeded TRANTYPE replacement")
                .isPresent();
        TransactionType adjustmentType = adjustment.get();
        assertThat(adjustmentType.getTranType())
                .as("primary key parity — TRAN-TYPE PIC X(02) -> String(2)")
                .isEqualTo(ADJUSTMENT_CODE);
        assertThat(adjustmentType.getTranTypeDesc().trim())
                .as("type code 07 description must be \"Adjustment\"")
                .isEqualTo(ADJUSTMENT_DESC);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the seven
     * rows that {@code trantype.txt} / {@code V3__seed_data.sql} provide &mdash; the
     * count equivalent of a full browse of the small {@code TRANTYPE} reference
     * cluster. A raw-JDBC {@code COUNT(*)} cross-check confirms the JPA count matches
     * the physical table {@code transaction_type} (singular).
     */
    @Test
    @DisplayName("count() equals the 7 seeded rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(transactionTypeRepository.count())
                .as("TRANTYPE replacement must contain exactly the 7 seeded fixture rows")
                .isEqualTo(SEEDED_TYPE_ROWS);

        // Cross-check against the physical table (named "transaction_type", singular)
        // using the inherited JdbcTemplate, bypassing the JPA persistence context.
        Long jdbcCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaction_type", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_TYPE_ROWS);
    }

    /**
     * Verifies that a full browse ({@code findAll()}) yields exactly the seven
     * seeded type codes &mdash; the sequential read of the whole (small) reference
     * cluster &mdash; collecting the primary keys into a {@link Set} and asserting
     * they match {@link #EXPECTED_TYPE_CODES} exactly, irrespective of row order.
     */
    @Test
    @DisplayName("findAll() contains exactly the seven type codes {01..07} (order-independent)")
    void findAll_containsAllSevenCodes() {
        List<TransactionType> all = transactionTypeRepository.findAll();

        Set<String> codes = all.stream()
                .map(TransactionType::getTranType)
                .collect(Collectors.toSet());

        assertThat(codes)
                .as("the full TRANTYPE browse must return exactly the seven seeded codes {01..07}")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_TYPE_CODES);
    }

    /**
     * Verifies that a keyed read for a non-existent type code yields an empty
     * {@link Optional} rather than throwing &mdash; the JPA equivalent of a VSAM
     * "record not found" ({@code FILE STATUS 23}) on a {@code READ} of an absent
     * key, which is the COBOL "INVALID TRANSACTION TYPE" path that the
     * {@code CBTRN02C} validation cascade and {@code CBTRN03C} lookup branch on via
     * {@link Optional#isEmpty()}.
     */
    @Test
    @DisplayName("findById(\"99\") returns Optional.empty() (no such type code)")
    void findById_missingType_returnsEmpty() {
        Optional<TransactionType> found = transactionTypeRepository.findById(MISSING_CODE);

        assertThat(found)
                .as("a type code that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }
}
