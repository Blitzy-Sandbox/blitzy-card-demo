package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link TransactionCategoryBalanceRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational replacement of the
 * legacy AWS CardDemo VSAM KSDS dataset {@code TCATBALF}.
 *
 * <p>On the mainframe, {@code TCATBALF} was provisioned by {@code app/jcl/TCATBALF.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS KEYS(17 0) RECORDSIZE(50 50)
 * INDEXED}); its fixed-length 50-byte record layout was defined by copybook
 * {@code app/cpy/CVTRA01Y.cpy} ({@code 01 TRAN-CAT-BAL-RECORD}: the 17-byte {@code TRAN-CAT-KEY}
 * group &mdash; {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)} +
 * {@code TRANCAT-CD PIC 9(04)} &mdash; followed by {@code TRAN-CAT-BAL PIC S9(09)V99} and a trailing
 * {@code FILLER PIC X(22)} = {@code 11 + 2 + 4 + 11 + 22 = 50}). It holds a <em>per-account,
 * per-type, per-category running balance</em> &mdash; one balance for each distinct
 * {@code (account, transaction-type, transaction-category)} combination &mdash; sitting at the
 * intersection of the daily-posting and interest paths:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting): paragraph
 *       {@code 2700-UPDATE-TCATBAL} <em>maintains</em> the balance. It assembles the full three-part
 *       key ({@code FD-TRANCAT-ACCT-ID} + {@code FD-TRANCAT-TYPE-CD} + {@code FD-TRANCAT-CD}) and
 *       issues a keyed {@code READ TCATBAL-FILE}, then either inserts a new row when the key is absent
 *       ({@code 2700-A-CREATE-TCATBAL-REC} &rarr; {@code WRITE} on FILE STATUS {@code '23'}) or
 *       updates the existing row ({@code 2700-B-UPDATE-TCATBAL-REC} &rarr; {@code REWRITE} on FILE
 *       STATUS {@code '00'}).</li>
 *   <li>{@code CBACT04C} (interest calculation): paragraph {@code 1000-TCATBALF-GET-NEXT}
 *       <em>reads</em> the balances. With the file opened {@code ACCESS MODE IS SEQUENTIAL} it
 *       performs a plain {@code READ TCATBAL-FILE} (next record) and walks the whole cluster to
 *       end-of-file; it does no {@code START} and no key positioning.</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL {@code transaction_category_balance}
 * table mapped by {@link TransactionCategoryBalance}, and every access path is served through the
 * Spring Data {@link TransactionCategoryBalanceRepository}. These tests prove the migration preserves
 * behavior: three-field composite-key access (the {@code CBTRN02C 2700-UPDATE-TCATBAL} keyed read),
 * penny-exact {@code BigDecimal} balance fidelity, count parity, and the "record not found" path.</p>
 *
 * <h2>The defining subtlety &mdash; a THREE-field composite {@code @EmbeddedId} key</h2>
 * <p>What distinguishes {@code TCATBALF} from a scalar-keyed reference table is its
 * <strong>three-part</strong> key. The legacy 17-byte KSDS key was the concatenation of the three
 * {@code TRAN-CAT-KEY} sub-fields ({@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD
 * PIC X(02)} + {@code TRANCAT-CD PIC 9(04)}). That physical concatenated key is migrated to an
 * explicit, typed composite key &mdash; the {@code @Embeddable} {@link TransactionCategoryBalanceId}
 * &mdash; so the entity carries {@code @EmbeddedId TransactionCategoryBalanceId id} and the repository
 * is typed {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}.
 * Consequently {@link org.springframework.data.repository.CrudRepository#findById(Object) findById}
 * takes a <strong>fully-populated</strong> {@link TransactionCategoryBalanceId} (account, type, and
 * category all set), exactly as the legacy keyed read required the complete 17-byte key.</p>
 * <p>The key components are constructed via the all-args constructor in their original COBOL field
 * order &mdash; {@code new TransactionCategoryBalanceId(acctId, typeCode, catCode)} &mdash; where
 * {@code acctId} is the {@code TRANCAT-ACCT-ID PIC 9(11)} {@link Long} (eleven digits exceed the
 * {@link Integer} range), {@code typeCode} is the {@code TRANCAT-TYPE-CD PIC X(02)} {@link String},
 * and {@code catCode} is the {@code TRANCAT-CD PIC 9(04)} {@link Integer}. The order is account, then
 * type, then category and must <strong>not</strong> be transposed &mdash; transposing it would
 * silently break every {@code findById} lookup.</p>
 *
 * <h2>Decimal fidelity &mdash; {@code BigDecimal} via {@code compareTo}, never {@code equals} (AAP
 * &sect;0.7.3)</h2>
 * <p>{@code TRAN-CAT-BAL PIC S9(09)V99} is the signed running balance (nine integer digits plus two
 * fractional digits), mapped to a {@link BigDecimal} {@code NUMERIC(11,2)} balance column &mdash;
 * <strong>never</strong> {@code float}/{@code double}. Because {@link BigDecimal#equals(Object)} is
 * scale-sensitive ({@code 0.00} is not {@code equals} to {@code 0.0}) whereas the COBOL semantics are
 * purely numeric, every balance assertion here uses AssertJ's
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)
 * isEqualByComparingTo} (which delegates to {@link BigDecimal#compareTo(BigDecimal)}) and the expected
 * values are built from {@link String} literals ({@code new BigDecimal("0.00")}), never from a
 * {@code double}. The inherited {@code assertAmountEquals} helper applies the identical rule.</p>
 *
 * <h2>Behavioral-parity ground truth (the 50 balance rows, pre-posting state)</h2>
 * <p>The assertions below pin the <em>exact</em> rows the COBOL batch would have read before any
 * accrual, taken from the canonical ASCII fixture {@code app/data/ASCII/tcatbal.txt} (the parity
 * ground truth, 50 fixed-width 50-byte records) which the Flyway {@code V3__seed_data.sql} migration
 * loads into the throwaway container. Every fixture record carries a zero balance: the low-order
 * byte of the {@code TRAN-CAT-BAL} field uses COBOL zoned-overpunch encoding for {@code +0}, so the
 * field reads as {@code +000000000.00} and each composite key resolves to {@code 0.00} (NUMERIC scale
 * preserved) &mdash; precisely the pre-posting state {@code CBACT04C}
 * reads before computing interest. The fixture holds exactly fifty records, so {@code count()} must
 * return {@code 50}. Two representative keys are asserted: {@code (1, "01", 1)} (the first fixture
 * row) and {@code (2, "01", 1)} (the second).</p>
 *
 * <h2>No optimistic locking ({@code @Version}); no custom query methods</h2>
 * <p>{@link TransactionCategoryBalance} carries <strong>no</strong> JPA {@code @Version} column: per
 * AAP &sect;0.7.5 optimistic locking is applied only to {@code Account} (in {@code COACTUPC}) and
 * {@code Card} (in {@code COCRDUPC}); the category-balance row is maintained by batch posting
 * ({@code CBTRN02C}), not by an online read-update snapshot comparison.
 * {@link TransactionCategoryBalanceRepository} likewise declares <strong>no</strong> custom query
 * methods (Minimal Change Clause, AAP &sect;0.7.1): {@code TCATBALF} was reached only by a full-key
 * read/write or a full sequential browse, so only the inherited {@code JpaRepository} operations
 * ({@code findById(TransactionCategoryBalanceId)} / {@code count()}) are exercised here. In
 * particular, <strong>no</strong> account-only finder ({@code findByIdAcctId}) is invented &mdash; no
 * source program ranges {@code TCATBALF} by account alone.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the singleton
 * PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")} context
 * configuration, the {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers. None of those are re-declared here, so this
 * class shares the one cached Spring context and one Flyway migration with its sibling repository ITs.
 * The class is annotated {@link Transactional} so each test method runs in its own transaction that is
 * rolled back on completion; although every test here is read-only, this keeps isolation uniform with
 * the mutating sibling ITs and leaves the seeded data pristine.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source equivalent; behavior
 * under test is translated from the frozen AWS CardDemo COBOL baseline at commit SHA {@code 27d6c6f}.
 * The COBOL/JCL sources ({@code app/jcl/TCATBALF.jcl}, {@code app/cbl/CBTRN02C.cbl},
 * {@code app/cbl/CBACT04C.cbl}) and the ASCII fixture ({@code app/data/ASCII/tcatbal.txt}) are
 * read-only reference material and are never copied into this repository.</p>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("TransactionCategoryBalanceRepository (TCATBALF / CBTRN02C, CBACT04C) — 3-field composite-key parity integration tests")
class TransactionCategoryBalanceRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of transaction-category-balance rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/tcatbal.txt} (50 fixed-width 50-byte records).
     */
    private static final long SEEDED_BALANCE_ROWS = 50L;

    /** Account id of the first seeded fixture row ({@code TRANCAT-ACCT-ID} of record 1). */
    private static final Long ACCT_FIRST = 1L;

    /** Account id of the second seeded fixture row ({@code TRANCAT-ACCT-ID} of record 2). */
    private static final Long ACCT_SECOND = 2L;

    /** {@code TRANCAT-TYPE-CD PIC X(02)} carried by every fixture row. */
    private static final String SEED_TYPE_CD = "01";

    /** {@code TRANCAT-CD PIC 9(04)} carried by every fixture row. */
    private static final int SEED_CAT_CD = 1;

    /**
     * The pre-posting balance every fixture row carries ({@code TRAN-CAT-BAL = +000000000.00}).
     *
     * <p>Built from a {@link String} literal (never a {@code double}) so the scale is exactly 2,
     * matching the {@code NUMERIC(11,2)} column; compared with {@code isEqualByComparingTo} so the
     * assertion is scale-insensitive per AAP &sect;0.7.3.</p>
     */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /** Account component of a composite key intentionally absent from the seed. */
    private static final Long MISSING_ACCT = 999_999L;

    /** Type component of the intentionally-absent composite key. */
    private static final String MISSING_TYPE = "99";

    /** Category component of the intentionally-absent composite key. */
    private static final int MISSING_CAT = 999;

    /** Repository under test &mdash; the relational replacement for {@code TCATBALF}. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Verifies that three-field composite-key fetches of seeded balances return the rows loaded from
     * {@code tcatbal.txt} &mdash; the keyed lookup that {@code CBTRN02C} ({@code 2700-UPDATE-TCATBAL})
     * performed against {@code TCATBALF} on the 17-byte {@code TRAN-CAT-KEY} and that {@code CBACT04C}
     * reads before accruing interest.
     *
     * <p>Two representative keys are asserted, each constructed with the all-args
     * {@link TransactionCategoryBalanceId#TransactionCategoryBalanceId(Long, String, Integer)}
     * constructor in COBOL field order ({@code acctId}, then {@code typeCode}, then {@code catCode}):
     * {@code (1, "01", 1)} (the first fixture row) and {@code (2, "01", 1)} (the second). For each,
     * the returned entity must be present, its {@code @EmbeddedId} must round-trip back to the
     * requested composite key (proving the value-based key equality the JPA provider relies on), and
     * its {@code TRAN-CAT-BAL} must equal {@code 0.00} compared via {@code isEqualByComparingTo}
     * (scale-insensitive, AAP &sect;0.7.3) &mdash; never {@link BigDecimal#equals(Object)}.</p>
     */
    @Test
    @DisplayName("findById((1,\"01\",1)/(2,\"01\",1)) returns the seeded 0.00 balances (BigDecimal compareTo, not equals)")
    void findById_byCompositeKey_returnsSeededBalance_withDecimalScale() {
        // (1, "01", 1) -> 0.00 (first row of tcatbal.txt).
        assertSeededZeroBalance(ACCT_FIRST);
        // (2, "01", 1) -> 0.00 (second row of tcatbal.txt).
        assertSeededZeroBalance(ACCT_SECOND);
    }

    /**
     * Asserts that the three-field composite key {@code (acctId, "01", 1)} resolves, via
     * {@code findById(TransactionCategoryBalanceId)}, to a present balance row whose embedded id
     * round-trips to the requested key and whose {@code TRAN-CAT-BAL} equals {@code 0.00}.
     *
     * <p>Centralizing the per-row assertion keeps
     * {@link #findById_byCompositeKey_returnsSeededBalance_withDecimalScale()} declarative and ensures
     * every asserted row exercises the identical contract: composite-key presence,
     * {@code @EmbeddedId} value round-trip across all three components ({@code TRANCAT-ACCT-ID} +
     * {@code TRANCAT-TYPE-CD} + {@code TRANCAT-CD}), and the scale-insensitive {@code BigDecimal}
     * balance comparison (AAP &sect;0.7.3).</p>
     *
     * @param acctId the {@code TRANCAT-ACCT-ID} component ({@code PIC 9(11)}) to look up; combined with
     *               the fixed seed type {@code "01"} and category {@code 1}
     */
    private void assertSeededZeroBalance(Long acctId) {
        // Construct the 17-byte composite key in COBOL field order: account id, type code, category code.
        TransactionCategoryBalanceId key =
                new TransactionCategoryBalanceId(acctId, SEED_TYPE_CD, SEED_CAT_CD);

        Optional<TransactionCategoryBalance> found = transactionCategoryBalanceRepository.findById(key);
        assertThat(found)
                .as("composite key (%d, %s, %d) must be present in the Flyway-seeded TCATBALF replacement",
                        acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isPresent();

        TransactionCategoryBalance balance = found.get();

        // Composite-key parity: the @EmbeddedId must round-trip by value to the requested key (proves
        // TransactionCategoryBalanceId.equals/hashCode cover all three components — the contract JPA
        // relies on to resolve the keyed read, mirroring the legacy 17-byte TRAN-CAT-KEY).
        assertThat(balance.getId())
                .as("the @EmbeddedId must round-trip to the requested composite key (%d, %s, %d)",
                        acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isEqualTo(key);
        assertThat(balance.getId().getAcctId())
                .as("TRANCAT-ACCT-ID parity for composite key (%d, %s, %d)", acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isEqualTo(acctId);
        assertThat(balance.getId().getTypeCode())
                .as("TRANCAT-TYPE-CD parity for composite key (%d, %s, %d)", acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isEqualTo(SEED_TYPE_CD);
        assertThat(balance.getId().getCatCode())
                .as("TRANCAT-CD parity for composite key (%d, %s, %d)", acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isEqualTo(SEED_CAT_CD);

        // Decimal fidelity — TRAN-CAT-BAL PIC S9(09)V99 -> NUMERIC(11,2). Compare by NUMERIC VALUE via
        // isEqualByComparingTo (BigDecimal.compareTo), never equals (scale-sensitive), per AAP §0.7.3.
        assertThat(balance.getTranCatBal())
                .as("balance for composite key (%d, %s, %d) must not be null", acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isNotNull();
        assertThat(balance.getTranCatBal())
                .as("balance for composite key (%d, %s, %d) must be 0.00 (pre-posting), compared scale-insensitively",
                        acctId, SEED_TYPE_CD, SEED_CAT_CD)
                .isEqualByComparingTo(ZERO_BALANCE);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the fifty rows that
     * {@code tcatbal.txt} / {@code V3__seed_data.sql} provide &mdash; the count equivalent of the full
     * sequential browse {@code CBACT04C} ({@code 1000-TCATBALF-GET-NEXT}) performs over the
     * {@code TCATBALF} cluster. A raw-JDBC {@code COUNT(*)} cross-check confirms the JPA count matches
     * the physical table {@code transaction_category_balance} (singular, per
     * {@code V1__create_schema.sql} and the entity's {@code @Table}).
     */
    @Test
    @DisplayName("count() equals the 50 seeded rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(transactionCategoryBalanceRepository.count())
                .as("TCATBALF replacement must contain exactly the 50 seeded fixture rows")
                .isEqualTo(SEEDED_BALANCE_ROWS);

        // Cross-check against the physical table (named "transaction_category_balance", singular) using
        // the inherited JdbcTemplate, bypassing the JPA persistence context.
        Long jdbcCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_category_balance", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) on transaction_category_balance must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_BALANCE_ROWS);
    }

    /**
     * Verifies that a keyed read for a non-existent composite key yields an empty {@link Optional}
     * rather than throwing &mdash; the JPA equivalent of a VSAM "record not found"
     * ({@code FILE STATUS '23'}) on a {@code READ} of an absent key. This is exactly the
     * {@code CBTRN02C 2700-UPDATE-TCATBAL} branch that, on {@code '23'}, creates a fresh balance row
     * ({@code 2700-A-CREATE-TCATBAL-REC}); the service layer detects the absence via
     * {@link Optional#isEmpty()}.
     *
     * <p>The probe key {@code (999999, "99", 999)} is well-formed (all three components set, as the
     * legacy keyed read required a complete 17-byte key) but is intentionally absent from the seed.</p>
     */
    @Test
    @DisplayName("findById((999999,\"99\",999)) returns Optional.empty() (no such composite key)")
    void findById_missingCompositeKey_returnsEmpty() {
        Optional<TransactionCategoryBalance> found = transactionCategoryBalanceRepository.findById(
                new TransactionCategoryBalanceId(MISSING_ACCT, MISSING_TYPE, MISSING_CAT));

        assertThat(found)
                .as("a composite key that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }

    /**
     * Guards the {@code @EmbeddedId} value-equality contract that JPA depends on to resolve
     * {@code findById(...)} for the three-field composite key.
     *
     * <p>Hibernate looks up an {@code @EmbeddedId} entity by hashing/comparing the supplied key
     * instance against managed/persisted identifiers, so {@link TransactionCategoryBalanceId} must
     * define {@code equals}/{@code hashCode} <em>by value</em> across <strong>all three</strong>
     * components ({@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD}). This test
     * pins that contract directly &mdash; if it ever regressed (for example to identity equality), the
     * keyed-read tests above would fail mysteriously, so asserting it here localizes the cause.</p>
     *
     * <p>Two independently-constructed {@code (1, "01", 1)} keys must be {@code equals} and share a
     * {@code hashCode}; keys differing in <em>any single</em> component &mdash; account, type, or
     * category &mdash; must <em>not</em> be equal, confirming all three fields participate in
     * equality and that the {@code (Long, String, Integer)} constructor order is honored.</p>
     */
    @Test
    @DisplayName("TransactionCategoryBalanceId equals()/hashCode() are value-based across all three key components")
    void embeddedId_equalsAndHashCode_byValue() {
        TransactionCategoryBalanceId a =
                new TransactionCategoryBalanceId(ACCT_FIRST, SEED_TYPE_CD, SEED_CAT_CD); // (1, "01", 1)
        TransactionCategoryBalanceId b =
                new TransactionCategoryBalanceId(ACCT_FIRST, SEED_TYPE_CD, SEED_CAT_CD); // (1, "01", 1)

        assertThat(a)
                .as("two TransactionCategoryBalanceId(1L, \"01\", 1) instances must be value-equal")
                .isEqualTo(b);
        assertThat(a.hashCode())
                .as("value-equal composite keys must share a hashCode (required by the @EmbeddedId contract)")
                .isEqualTo(b.hashCode());

        // Differing in the account component alone must break equality (acctId participates).
        assertThat(a)
                .as("keys differing only in TRANCAT-ACCT-ID must not be equal")
                .isNotEqualTo(new TransactionCategoryBalanceId(ACCT_SECOND, SEED_TYPE_CD, SEED_CAT_CD)); // (2, "01", 1)

        // Differing in the type component alone must break equality (typeCode participates).
        assertThat(a)
                .as("keys differing only in TRANCAT-TYPE-CD must not be equal")
                .isNotEqualTo(new TransactionCategoryBalanceId(ACCT_FIRST, MISSING_TYPE, SEED_CAT_CD)); // (1, "99", 1)

        // Differing in the category component alone must break equality (catCode participates).
        assertThat(a)
                .as("keys differing only in TRANCAT-CD must not be equal")
                .isNotEqualTo(new TransactionCategoryBalanceId(ACCT_FIRST, SEED_TYPE_CD, SEED_CAT_CD + 1)); // (1, "01", 2)
    }
}
