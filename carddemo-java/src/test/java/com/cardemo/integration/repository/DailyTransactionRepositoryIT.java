package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link DailyTransactionRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo <strong>sequential PS staging dataset</strong>
 * {@code DALYTRAN} ({@code AWS.M2.CARDDEMO.DALYTRAN.PS}).
 *
 * <h2>Why this dataset is special &mdash; the only non-KSDS source in the inventory</h2>
 * <p>{@code DALYTRAN} is the <em>single</em> data entity in the whole migration that is backed by a
 * flat <strong>physical-sequential (QSAM/PS) file rather than a keyed VSAM&nbsp;KSDS cluster</strong>
 * (AAP &sect;0.6.2, &sect;0.6.5). Its fixed-length 350-byte record layout is defined by copybook
 * {@code app/cpy/CVTRA06Y.cpy} ({@code 01 DALYTRAN-RECORD}, a near-clone of the {@code TRANSACT}
 * layout). On the mainframe it is the daily, <em>pre-posting</em> transaction feed and is the input to
 * the {@code POSTTRAN} batch job ({@code app/jcl/POSTTRAN.jcl}); two batch programs reach it, both
 * declaring {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS SEQUENTIAL}:</p>
 * <ul>
 *   <li>{@code CBTRN01C} &mdash; the daily-transaction reader/validator: opens {@code DALYTRAN-FILE}
 *       {@code INPUT} and reads it front-to-back ({@code READ DALYTRAN-FILE} until the {@code AT END}
 *       file-status {@code '10'}).</li>
 *   <li>{@code CBTRN02C} &mdash; the {@code POSTTRAN} posting step: reads the same file sequentially
 *       ({@code 1000-DALYTRAN-GET-NEXT}) and {@code 2000-POST-TRANSACTION} posts each accepted row
 *       into the permanent {@code TRANSACT} ledger (rejects routed to the daily-reject sink).</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL {@code daily_transactions} table
 * mapped by {@link DailyTransaction}, and every access path is served through the Spring Data
 * {@link DailyTransactionRepository}. These tests prove the migration preserves behavior:
 * simple-{@link String} primary-key access (the row the posting pipeline resolves), the 300-row
 * staging-feed count parity, the 16-character zero-padded id parity, and the {@code BigDecimal}
 * monetary fidelity that interest/statement/report processing depends on downstream.</p>
 *
 * <h2>Behavioral-parity ground truth (the 300 seeded rows)</h2>
 * <p>The assertions below pin the <em>exact</em> rows the COBOL posting pipeline would have read,
 * taken from the canonical ASCII fixture {@code app/data/ASCII/dailytran.txt} (the parity ground
 * truth, 300&nbsp;&times;&nbsp;350-byte records) which the Flyway {@code V3__seed_data.sql} migration
 * loads into the throwaway container. {@code count()} must therefore return {@code 300}. Two pinned
 * rows exercise the two distinct {@code DALYTRAN-SOURCE} provenance tokens:</p>
 * <table border="1">
 *   <caption>Pinned {@code dailytran.txt} rows (overpunch-decoded amounts)</caption>
 *   <tr><th>{@code transaction_id}</th><th>{@code type_code}</th><th>{@code category_code}</th>
 *       <th>{@code source}</th><th>{@code amount} (S9(09)V99)</th></tr>
 *   <tr><td>{@code 0000000000683580}</td><td>{@code 01}</td><td>{@code 1}</td>
 *       <td>{@code POS TERM}</td><td>{@code 504.77} (overpunch {@code 0000005047G})</td></tr>
 *   <tr><td>{@code 0000000001774260}</td><td>{@code 03}</td><td>{@code 1}</td>
 *       <td>{@code OPERATOR}</td><td>{@code -919.00} (overpunch {@code 0000009190}}{@code )}</td></tr>
 * </table>
 *
 * <h2>Simple {@link String}(16) primary key &mdash; no VSAM key, no composite key</h2>
 * <p>A QSAM/sequential file has <strong>no record key</strong>; {@code DALYTRAN}'s natural record
 * identifier is {@code DALYTRAN-ID PIC X(16)}, the 16-character transaction id, which becomes the
 * relational primary key {@link DailyTransaction#getDalytranId()} (PostgreSQL {@code VARCHAR(16)}).
 * The repository is therefore typed {@code JpaRepository<DailyTransaction, String>} and keyed reads use
 * a 16-character {@link String} literal ({@code findById("0000000000683580")}). Because the legacy
 * access is a forward sequential browse and a sequential load, {@link DailyTransactionRepository}
 * declares <strong>no</strong> custom query methods (Minimal Change Clause, AAP &sect;0.7.1); only the
 * inherited {@code JpaRepository} operations ({@code findById(String)} / {@code findAll()} /
 * {@code count()}) are exercised here.</p>
 *
 * <h2>Decimal fidelity &mdash; {@code BigDecimal} via {@code compareTo}, never {@code equals} (AAP
 * &sect;0.7.3)</h2>
 * <p>{@code DALYTRAN-AMT PIC S9(09)V99} (nine integer + two fractional digits, signed) maps to a
 * {@link BigDecimal} {@code NUMERIC(11,2)} {@code amount} column &mdash; <strong>never</strong>
 * {@code float}/{@code double}. Because {@link BigDecimal#equals(Object)} is scale-sensitive (for
 * example {@code 504.77} is not {@code equals} to {@code 504.770}), every amount assertion uses AssertJ
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)
 * isEqualByComparingTo} (which delegates to {@link BigDecimal#compareTo(BigDecimal)}), and every
 * expected literal is built with the {@link BigDecimal#BigDecimal(String) String constructor}
 * ({@code new BigDecimal("504.77")}), never from a {@code double}. The persisted scale (exactly
 * {@code 2}, from {@code NUMERIC(11,2)}) is additionally asserted. The inherited
 * {@link AbstractRepositoryIT#assertAmountEquals(BigDecimal, BigDecimal) assertAmountEquals} helper
 * applies the identical rule.</p>
 *
 * <h2>{@code DALYTRAN-SOURCE} kept as a raw {@link String} &mdash; trim-tolerant assertions (AAP
 * &sect;0.7.2)</h2>
 * <p>{@code DALYTRAN-SOURCE PIC X(10)} is fixed-width, space-padded alphanumeric on the mainframe and
 * is mapped to a raw {@link String} of length&nbsp;10 (<strong>not</strong> the
 * {@code model.enums.TransactionSource} enum) to preserve exact byte parity at the persistence
 * boundary; any enum interpretation is a service-layer concern. The authoritative
 * {@code V3__seed_data.sql} inserts the source as trimmed literals (e.g. {@code 'POS TERM'},
 * {@code 'OPERATOR'}) into the {@code VARCHAR(10)} column, so PostgreSQL does <strong>not</strong>
 * re-pad them; but to remain robust against either representation these tests assert against the
 * <em>trimmed</em> value ({@code getDalytranSource().trim()}) rather than relying on exact, non-padded
 * equality. This honors the external-interface-contract rule for fixed-width {@code X(n)} fields without
 * coupling the test to a particular padding strategy.</p>
 *
 * <h2>No optimistic locking ({@code @Version})</h2>
 * <p>Per AAP &sect;0.7.5 optimistic locking is applied only to {@code Account} (in {@code COACTUPC})
 * and {@code Card} (in {@code COCRDUPC}); a daily-transaction row is inserted by the feed and read once
 * by the posting job, never concurrently rewritten through a read-update snapshot comparison, so
 * {@link DailyTransaction} carries no {@code @Version} column and no concurrency test is exercised here.
 * The transactional boundary that posts a daily row into {@code transactions} lives at the
 * {@code @Transactional} batch-step boundary, not at this staging repository.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the singleton
 * PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")} context
 * configuration, the {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers. None of those are re-declared here, so this class
 * shares the one cached Spring context and one Flyway migration with its sibling repository ITs. The
 * class is annotated {@link Transactional} so each test method runs in its own transaction that is
 * rolled back on completion; although every test here is read-only, this keeps isolation uniform with
 * the mutating sibling ITs and leaves the seeded data pristine.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source equivalent; base
 * package {@code com.cardemo} (decision D-006). Behavior under test is translated from the frozen AWS
 * CardDemo COBOL baseline at commit SHA {@code 27d6c6f}. The COBOL sources
 * ({@code app/cbl/CBTRN01C.cbl}, {@code app/cbl/CBTRN02C.cbl}) and the ASCII fixture
 * ({@code app/data/ASCII/dailytran.txt}) are read-only reference material and are never copied into
 * this repository.</p>
 *
 * @see DailyTransactionRepository
 * @see DailyTransaction
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("DailyTransactionRepository (DALYTRAN PS staging / CBTRN01C, CBTRN02C) — behavioral-parity integration tests")
class DailyTransactionRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of daily-transaction rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/dailytran.txt} (300 &times; 350-byte staging records) &mdash; the daily
     * posting input that {@code CBTRN01C}/{@code CBTRN02C} consumed.
     */
    private static final long SEEDED_DALYTRAN_ROWS = 300L;

    /**
     * Pinned {@code DALYTRAN-ID} of the {@code POS TERM} ground-truth row (first fixture row). A
     * 16-character zero-padded id ({@code PIC X(16)}).
     */
    private static final String POS_TERM_ID = "0000000000683580";

    /**
     * Pinned {@code DALYTRAN-ID} of the {@code OPERATOR} ground-truth row. A 16-character zero-padded
     * id ({@code PIC X(16)}).
     */
    private static final String OPERATOR_ID = "0000000001774260";

    /** A 16-character id that is intentionally absent from the seed (no such staging row). */
    private static final String MISSING_ID = "9999999999999999";

    /** Seeded {@code DALYTRAN-TYPE-CD} ({@code PIC X(02)}) of the {@code POS TERM} row. */
    private static final String POS_TERM_TYPE_CD = "01";

    /** Seeded {@code DALYTRAN-CAT-CD} ({@code PIC 9(04)}) of the {@code POS TERM} row. */
    private static final int POS_TERM_CAT_CD = 1;

    /** Seeded {@code DALYTRAN-SOURCE} ({@code PIC X(10)}, trimmed) of the {@code POS TERM} row. */
    private static final String POS_TERM_SOURCE = "POS TERM";

    /** Seeded {@code DALYTRAN-SOURCE} ({@code PIC X(10)}, trimmed) of the {@code OPERATOR} row. */
    private static final String OPERATOR_SOURCE = "OPERATOR";

    /**
     * Seeded {@code DALYTRAN-AMT} of the {@code POS TERM} row: {@code +504.77} (overpunch
     * {@code 0000005047G} &rarr; trailing {@code G} = positive&nbsp;7). Built with the
     * {@link BigDecimal} String constructor (never a {@code double}); compared via
     * {@code isEqualByComparingTo} only (AAP &sect;0.7.3).
     */
    private static final BigDecimal POS_TERM_AMT = new BigDecimal("504.77");

    /**
     * Seeded {@code DALYTRAN-AMT} of the {@code OPERATOR} row: {@code -919.00} (overpunch
     * {@code 0000009190}} &rarr; trailing {@code }} = negative&nbsp;0). Built with the
     * {@link BigDecimal} String constructor (never a {@code double}); compared via
     * {@code isEqualByComparingTo} only (AAP &sect;0.7.3).
     */
    private static final BigDecimal OPERATOR_AMT = new BigDecimal("-919.00");

    /**
     * The exact scale of the {@code amount} column ({@code DALYTRAN-AMT PIC S9(09)V99} &rarr;
     * {@code NUMERIC(11,2)}): two fractional digits.
     */
    private static final int EXPECTED_AMOUNT_SCALE = 2;

    /**
     * The fixed length of {@code DALYTRAN-ID} ({@code PIC X(16)} &rarr; {@code VARCHAR(16)}): the
     * 16-character zero-padded staging key.
     */
    private static final int DALYTRAN_ID_LENGTH = 16;

    /** Repository under test &mdash; the relational replacement for the {@code DALYTRAN} PS file. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * Verifies that primary-key fetches of the two pinned seeded ids return the rows loaded from
     * {@code dailytran.txt} &mdash; the simple-{@link String} keyed lookup the migrated posting
     * pipeline performs against the {@code daily_transactions} staging table (the relational
     * replacement for the keyless {@code DALYTRAN} PS file).
     *
     * <p>Row {@code 0000000000683580} (the {@code POS TERM} provenance) is asserted in full: its
     * type code is {@code "01"}, its category code is {@code 1}, its {@code source} is {@code "POS TERM"}
     * (trim-tolerant, see the class Javadoc), and its {@code DALYTRAN-AMT} is the positive
     * {@code 504.77} &mdash; non-null, persisted at scale&nbsp;2, and compared with
     * {@code isEqualByComparingTo} (never {@link BigDecimal#equals(Object)}). Row
     * {@code 0000000001774260} (the {@code OPERATOR} provenance) additionally proves the negative
     * amount {@code -919.00} round-trips with full sign and scale fidelity.</p>
     */
    @Test
    @DisplayName("findById(\"...683580\")/(\"...774260\") return the seeded rows (POS TERM/OPERATOR), BigDecimal compareTo not equals")
    void findById_returnsSeededDailyTransaction() {
        // --- Row 1: DALYTRAN-ID 0000000000683580 -> POS TERM (first row of dailytran.txt). ---
        Optional<DailyTransaction> posTerm = dailyTransactionRepository.findById(POS_TERM_ID);
        assertThat(posTerm)
                .as("DALYTRAN-ID %s (POS TERM) must be present in the Flyway-seeded DALYTRAN replacement",
                        POS_TERM_ID)
                .isPresent();
        DailyTransaction posTxn = posTerm.get();

        // Primary-key parity — DALYTRAN-ID PIC X(16) -> String(16).
        assertThat(posTxn.getDalytranId())
                .as("primary key parity — DALYTRAN-ID PIC X(16) -> String(16)")
                .isEqualTo(POS_TERM_ID);
        // Type code parity — DALYTRAN-TYPE-CD PIC X(02).
        assertThat(posTxn.getDalytranTypeCd())
                .as("type code parity — DALYTRAN-TYPE-CD PIC X(02)")
                .isEqualTo(POS_TERM_TYPE_CD);
        // Category code parity — DALYTRAN-CAT-CD PIC 9(04) -> Integer.
        assertThat(posTxn.getDalytranCatCd())
                .as("category code parity — DALYTRAN-CAT-CD PIC 9(04) -> Integer")
                .isEqualTo(POS_TERM_CAT_CD);
        // Source parity — DALYTRAN-SOURCE PIC X(10). Assert against the trimmed value so the test does
        // not depend on whether the X(10) field is space-padded (AAP §0.7.2 fixed-width contract).
        assertThat(posTxn.getDalytranSource())
                .as("source must be stored (raw String, not the TransactionSource enum)")
                .isNotNull();
        assertThat(posTxn.getDalytranSource().trim())
                .as("source token must be \"POS TERM\" (DALYTRAN-SOURCE PIC X(10), trimmed)")
                .isEqualTo(POS_TERM_SOURCE);

        // Monetary parity — DALYTRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2). compareTo semantics ONLY
        // (never BigDecimal.equals, which is scale-sensitive), per AAP §0.7.3.
        BigDecimal posAmt = posTxn.getDalytranAmt();
        assertThat(posAmt)
                .as("DALYTRAN-AMT must be stored (non-null monetary amount)")
                .isNotNull();
        assertThat(posAmt.scale())
                .as("DALYTRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2): scale must be exactly 2")
                .isEqualTo(EXPECTED_AMOUNT_SCALE);
        assertThat(posAmt)
                .as("POS TERM amount must be 504.77 (compareTo, not equals)")
                .isEqualByComparingTo(POS_TERM_AMT);

        // --- Row 2: DALYTRAN-ID 0000000001774260 -> OPERATOR (proves negative-amount fidelity). ---
        Optional<DailyTransaction> operator = dailyTransactionRepository.findById(OPERATOR_ID);
        assertThat(operator)
                .as("DALYTRAN-ID %s (OPERATOR) must be present in the Flyway-seeded DALYTRAN replacement",
                        OPERATOR_ID)
                .isPresent();
        DailyTransaction opTxn = operator.get();

        assertThat(opTxn.getDalytranId())
                .as("primary key parity — DALYTRAN-ID PIC X(16) -> String(16)")
                .isEqualTo(OPERATOR_ID);
        assertThat(opTxn.getDalytranSource())
                .as("source must be stored (raw String, not the TransactionSource enum)")
                .isNotNull();
        assertThat(opTxn.getDalytranSource().trim())
                .as("source token must be \"OPERATOR\" (DALYTRAN-SOURCE PIC X(10), trimmed)")
                .isEqualTo(OPERATOR_SOURCE);

        BigDecimal opAmt = opTxn.getDalytranAmt();
        assertThat(opAmt)
                .as("DALYTRAN-AMT must be stored (non-null monetary amount)")
                .isNotNull();
        assertThat(opAmt.scale())
                .as("DALYTRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2): scale must be exactly 2")
                .isEqualTo(EXPECTED_AMOUNT_SCALE);
        assertThat(opAmt)
                .as("OPERATOR amount must be -919.00 with full sign fidelity (compareTo, not equals)")
                .isEqualByComparingTo(OPERATOR_AMT);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the 300 rows that
     * {@code dailytran.txt} / {@code V3__seed_data.sql} provide &mdash; the count equivalent of the
     * front-to-back sequential browse of the {@code DALYTRAN} staging feed performed by the
     * {@code CBTRN01C} reader and the {@code CBTRN02C} posting step. A raw-JDBC {@code COUNT(*)}
     * cross-check (via the inherited {@code jdbcTemplate}) confirms the JPA count matches the physical
     * {@code daily_transactions} table, bypassing the JPA persistence context.
     */
    @Test
    @DisplayName("count() equals the 300 seeded staging rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(dailyTransactionRepository.count())
                .as("DALYTRAN replacement must contain exactly the 300 seeded daily-posting rows")
                .isEqualTo(SEEDED_DALYTRAN_ROWS);

        // Cross-check against the physical table using the inherited JdbcTemplate, bypassing JPA.
        Long jdbcCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_transactions", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) on daily_transactions must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_DALYTRAN_ROWS);
    }

    /**
     * Verifies that a keyed read for a non-existent {@code DALYTRAN-ID} yields an empty
     * {@link Optional} rather than throwing &mdash; the JPA equivalent of the sequential reader
     * reaching {@code AT END} (COBOL file-status {@code '10'}) without matching a row, i.e. the
     * "record not found" path. The posting pipeline branches on {@link Optional#isEmpty()} rather than
     * an exception, preserving the COBOL control flow.
     */
    @Test
    @DisplayName("findById(\"9999999999999999\") returns Optional.empty() (no such staging row)")
    void findById_missing_returnsEmpty() {
        Optional<DailyTransaction> found = dailyTransactionRepository.findById(MISSING_ID);

        assertThat(found)
                .as("a DALYTRAN-ID that is not seeded must produce an empty Optional, not an error")
                .isEmpty();
    }

    /**
     * Verifies the 16-character zero-padded key parity across the whole staging feed: a full browse
     * ({@code findAll()}, the sequential read of the entire {@code DALYTRAN} file) must return a
     * non-empty set in which <em>every</em> {@link DailyTransaction#getDalytranId()} is exactly
     * 16&nbsp;characters &mdash; the {@code DALYTRAN-ID PIC X(16)} external-format contract preserved as
     * {@code VARCHAR(16)} (AAP &sect;0.7.2). This guards against any seed or mapping defect that would
     * truncate or pad the id away from its fixed legacy width.
     */
    @Test
    @DisplayName("findAll() — every DALYTRAN-ID is the 16-char PIC X(16) key (zero-padded parity)")
    void findAll_idsAreSixteenChars() {
        List<DailyTransaction> all = dailyTransactionRepository.findAll();

        assertThat(all)
                .as("the full DALYTRAN browse must return the seeded staging rows")
                .isNotEmpty()
                .allSatisfy(txn -> assertThat(txn.getDalytranId())
                        .as("DALYTRAN-ID parity — PIC X(16) -> String(16): id must be 16 characters")
                        .hasSize(DALYTRAN_ID_LENGTH));
    }
}
