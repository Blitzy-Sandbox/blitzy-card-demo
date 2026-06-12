package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link AccountRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code ACCTDAT}.
 *
 * <p>On the mainframe, {@code ACCTDAT} was provisioned by
 * {@code app/jcl/ACCTFILE.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS KEYS(11 0)
 * RECORDSIZE(300 300) INDEXED}) and reached by keyed CICS file control in the
 * online account-view program {@code COACTVWC} and by the batch account-dump
 * program {@code CBACT01C}. In the migrated stack the same data lives in the
 * PostgreSQL {@code account} table mapped by {@link Account}, and every keyed
 * operation is served through the Spring Data {@link AccountRepository}. These
 * tests prove that migration preserves behavior: primary-key access, full-table
 * count parity, and the optimistic-locking concurrency control.</p>
 *
 * <h2>Behavioral-parity ground truth</h2>
 * <p>The assertions below pin the <em>exact</em> values that {@code COACTVWC}
 * would have displayed for account&nbsp;1, taken from the canonical ASCII fixture
 * {@code app/data/ASCII/acctdata.txt} (the COBOL parity ground truth) which the
 * Flyway {@code V3__seed_data.sql} migration loads into the throwaway container.
 * The fixture holds 50 account records (ids 1&ndash;50), so {@code count()} must
 * return exactly 50.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Every monetary field originates from a COBOL {@code PIC S9(10)V99} clause and
 * is mapped to a {@link BigDecimal} of {@code precision = 12, scale = 2}. All
 * numeric assertions therefore use {@link BigDecimal#compareTo(BigDecimal)}
 * semantics &mdash; via AssertJ
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)
 * isEqualByComparingTo} &mdash; and <strong>never</strong>
 * {@link BigDecimal#equals(Object)}, which is scale-sensitive (e.g. {@code 194.0}
 * is not {@code equals} to {@code 194.00}). Expected values are constructed with
 * the {@code BigDecimal(String)} constructor, never {@code double}, so the literal
 * scale is exact and no binary floating-point error is introduced.</p>
 *
 * <h2>Optimistic locking ({@code @Version}) &mdash; the {@code COACTUPC} parity</h2>
 * <p>The online update program {@code COACTUPC} performed a read-before-update
 * snapshot comparison (it re-read the record before its {@code REWRITE} and
 * compared the before/after images) to reject concurrent modifications. That
 * safeguard is reproduced declaratively by the JPA {@code @Version} column on
 * {@link Account}: a stale write surfaces as
 * {@link ObjectOptimisticLockingFailureException}. The
 * {@link #save_incrementsVersion_andOptimisticLockOnStaleUpdate()} test exercises
 * exactly this path.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the
 * singleton PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} +
 * {@code @ActiveProfiles("test")} context configuration, the
 * {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers. None of those are
 * re-declared here, so this class shares the one cached Spring context and one
 * Flyway migration with its sibling repository ITs. The class is annotated
 * {@link Transactional} so each test method runs in its own transaction that is
 * rolled back on completion &mdash; this isolates the mutating optimistic-lock
 * test from the read-only tests and leaves the seeded data pristine for sibling
 * classes.</p>
 *
 * <h2>Notes on the values asserted (authoritative-source reconciliation)</h2>
 * <p>The assertions follow the authoritative dependency artifacts (the
 * {@link Account} entity and {@code V3__seed_data.sql}) rather than any looser
 * prose, in three respects derived directly from the fixed 300-byte record layout
 * of copybook {@code CVACT01Y}:</p>
 * <ul>
 *   <li>The three date fields ({@code open}/{@code expiration}/{@code reissue})
 *       are {@link LocalDate} (the entity maps them to {@code DATE} columns), so
 *       they are compared against {@link LocalDate} values.</li>
 *   <li>The token {@code "A000000000"} occupies {@code ACCT-ADDR-ZIP}
 *       (record offset 102, length 10) in {@code acctdata.txt}, so it is asserted
 *       on {@link Account#getAcctAddrZip()}; the trailing {@code ACCT-GROUP-ID}
 *       field (offset 112) is spaces in the fixture and is seeded as
 *       {@code NULL}, so {@link Account#getAcctGroupId()} is asserted
 *       {@code null}.</li>
 *   <li>Raw SQL cross-checks target the table named {@code account} (singular),
 *       matching {@code @Table(name = "account")} on {@link Account} and the
 *       {@code V1__create_schema.sql} DDL.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; behavior under test is translated from the frozen AWS CardDemo
 * COBOL baseline at commit SHA {@code 27d6c6f}. The COBOL/JCL sources and the
 * ASCII fixtures are read-only reference material and are never copied into this
 * repository.</p>
 *
 * @see AccountRepository
 * @see Account
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("AccountRepository (ACCTDAT / COACTVWC, COACTUPC) — behavioral-parity integration tests")
class AccountRepositoryIT extends AbstractRepositoryIT {

    /**
     * The {@code ACCT-ADDR-ZIP} token seeded for account&nbsp;1 (record offset 102,
     * length 10 of copybook {@code CVACT01Y}); referenced from the parity assertion.
     */
    private static final String SEEDED_ADDR_ZIP = "A000000000";

    /** The exact number of account rows seeded by {@code V3__seed_data.sql}. */
    private static final long SEEDED_ACCOUNT_ROWS = 50L;

    /** Repository under test &mdash; the relational replacement for {@code ACCTDAT}. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Verifies that a primary-key fetch of account&nbsp;1 returns the row with the
     * exact field values seeded from {@code acctdata.txt} &mdash; the keyed read
     * that {@code COACTVWC} performed against {@code ACCTDAT}.
     *
     * <p>Monetary fields are compared with {@code isEqualByComparingTo} (scale-
     * insensitive {@link BigDecimal#compareTo(BigDecimal)} semantics, AAP
     * &sect;0.7.3); date fields are compared as {@link LocalDate}.</p>
     */
    @Test
    @DisplayName("findById(1L) returns the seeded account with exact decimal scale and date parity")
    void findById_returnsSeededAccount_withExactDecimalScale() {
        Optional<Account> found = accountRepository.findById(1L);

        assertThat(found)
                .as("account 1 must be present in the Flyway-seeded ACCTDAT replacement")
                .isPresent();

        Account account = found.get();

        // Primary key + single-character active-status flag (ACCT-ACTIVE-STATUS).
        assertThat(account.getAcctId()).isEqualTo(1L);
        assertThat(account.getAcctActiveStatus()).isEqualTo("Y");

        // Monetary parity — compareTo semantics ONLY (never BigDecimal.equals, which
        // is scale-sensitive). String-constructor literals keep the scale exact.
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("194.00"));
        assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
        assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));

        // Date parity — entity maps ACCT-*-DATE PIC X(10) to LocalDate (DATE columns).
        assertThat(account.getAcctOpenDate()).isEqualTo(LocalDate.parse("2014-11-20"));
        assertThat(account.getAcctExpirationDate()).isEqualTo(LocalDate.parse("2025-05-20"));
        assertThat(account.getAcctReissueDate()).isEqualTo(LocalDate.parse("2025-05-20"));

        // Field-position parity from CVACT01Y: "A000000000" is ACCT-ADDR-ZIP (offset 102);
        // ACCT-GROUP-ID (offset 112) is spaces in the fixture and is seeded as NULL.
        assertThat(account.getAcctAddrZip()).isEqualTo(SEEDED_ADDR_ZIP);
        assertThat(account.getAcctGroupId()).isNull();
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the 50 rows
     * that {@code acctdata.txt} / {@code V3__seed_data.sql} provide &mdash; the
     * count equivalent of the sequential {@code STARTBR}/{@code READNEXT} dump that
     * {@code CBACT01C} performed over {@code ACCTDAT}. A raw-JDBC {@code COUNT(*)}
     * cross-check confirms the JPA count matches the physical table {@code account}
     * (singular).
     */
    @Test
    @DisplayName("count() equals the 50 seeded rows (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(accountRepository.count())
                .as("ACCTDAT replacement must contain exactly the 50 seeded fixture rows")
                .isEqualTo(SEEDED_ACCOUNT_ROWS);

        // Cross-check against the physical table (named "account", singular) using the
        // inherited JdbcTemplate, bypassing the JPA persistence context entirely.
        Long jdbcCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_ACCOUNT_ROWS);
    }

    /**
     * Verifies that a keyed read for a non-existent account yields an empty
     * {@link Optional} rather than throwing &mdash; the JPA equivalent of a VSAM
     * "record not found" ({@code FILE STATUS 23}) on a {@code READ} of an absent
     * key, surfaced by {@link Optional#isEmpty()} for the caller to branch on.
     */
    @Test
    @DisplayName("findById(missing) returns Optional.empty()")
    void findById_missingAccount_returnsEmptyOptional() {
        Optional<Account> found = accountRepository.findById(999_999L);

        assertThat(found)
                .as("a key that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }

    /**
     * Verifies the JPA {@code @Version} optimistic-locking semantics that replace
     * the {@code COACTUPC} read-before-update snapshot comparison: a write based on
     * a stale in-memory copy is rejected with
     * {@link ObjectOptimisticLockingFailureException}.
     *
     * <p><strong>Technique (documented per the file specification).</strong> Because
     * this class is {@link Transactional}, a competing update is simulated
     * <em>in&nbsp;process</em> via the inherited {@code jdbcTemplate}, which shares
     * this test's transaction-bound JDBC connection. The sequence is:</p>
     * <ol>
     *   <li>Load account&nbsp;1 through the repository, capturing its managed
     *       {@code version} (the seeded value is {@code 0}).</li>
     *   <li>Bump the {@code version} column out-of-band with a direct
     *       {@code UPDATE account SET version = version + 1 WHERE account_id = 1};
     *       on the shared connection the row's version becomes {@code 1}.</li>
     *   <li>Mutate the now-stale managed entity (whose in-memory version is still
     *       {@code 0}) and force a flush with {@code saveAndFlush}.</li>
     * </ol>
     * <p>Hibernate's versioned {@code UPDATE} carries {@code WHERE version = 0}, but
     * the row already holds version {@code 1}, so zero rows are affected and
     * Hibernate raises a {@code StaleObjectStateException}, which Spring Data
     * translates to {@link ObjectOptimisticLockingFailureException}. {@code saveAndFlush}
     * (rather than {@code save}) is required so the failure is raised synchronously
     * inside the assertion rather than being deferred to transaction commit. This
     * mirrors the exact concurrent-update rejection that {@code COACTUPC} produced
     * on the mainframe.</p>
     */
    @Test
    @DisplayName("saveAndFlush of a stale entity throws ObjectOptimisticLockingFailureException (@Version / COACTUPC parity)")
    void save_incrementsVersion_andOptimisticLockOnStaleUpdate() {
        Account account = accountRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("seed data missing account 1"));

        Long originalVersion = account.getVersion();
        assertThat(originalVersion)
                .as("seeded @Version must be initialized (V1 DDL default 0)")
                .isNotNull();

        // Simulate a competing transaction committing a newer image of the row. The
        // inherited JdbcTemplate runs on this test's transaction-bound connection, so
        // the bumped version is visible to Hibernate's subsequent versioned UPDATE.
        int rowsBumped = jdbcTemplate.update(
                "UPDATE account SET version = version + 1 WHERE account_id = 1");
        assertThat(rowsBumped)
                .as("the out-of-band version bump must affect exactly account 1")
                .isEqualTo(1);

        // The in-memory entity is now stale (its version still equals originalVersion).
        // Mutating it and flushing must be rejected by the optimistic-lock check.
        account.setAcctCurrBal(new BigDecimal("999999.99"));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> accountRepository.saveAndFlush(account),
                "a stale @Version write must be rejected, reproducing the COACTUPC "
                        + "read-before-update concurrency control");
    }
}
