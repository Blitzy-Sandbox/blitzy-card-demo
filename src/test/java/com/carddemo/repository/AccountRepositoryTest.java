package com.carddemo.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.carddemo.entity.Account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Data JPA slice test for {@link AccountRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16 database</strong> supplied by Testcontainers
 * through {@link AbstractRepositoryTest}. No mocks and no embedded H2 are used,
 * so the assertions exercise the exact {@code NUMERIC}, {@code DATE} and
 * optimistic-lock semantics that production relies on.
 *
 * <p>{@code AccountRepository} declares no custom query methods &mdash; it is a
 * thin {@code JpaRepository<Account, Long>} persistence port that replaces the
 * legacy VSAM KSDS keyed on {@code ACCT-ID}. These tests therefore verify the
 * three behaviours the migration must guarantee for the account aggregate,
 * whose layout comes from the COBOL copybook {@code app/cpy/CVACT01Y.cpy}
 * ({@code ACCOUNT-RECORD}, fixed record length 300, source commit SHA
 * {@code 27d6c6f}):</p>
 * <ol>
 *   <li><strong>Round-trip persistence</strong> &mdash; every mapped field of
 *       {@link Account} survives a {@code save}/{@code findById} cycle
 *       unchanged.</li>
 *   <li><strong>Decimal fidelity</strong> (AAP&nbsp;&sect;0.8.2) &mdash; the
 *       five signed {@code NUMERIC(12,2)} money columns preserve exact value,
 *       sign and scale, proving no {@code float}/{@code double} truncation of
 *       the COBOL {@code PIC S9(10)V99} amounts.</li>
 *   <li><strong>Optimistic locking</strong> (AAP&nbsp;&sect;0.8.4) &mdash; the
 *       {@link jakarta.persistence.Version @Version} column increments on
 *       update, and a stale (concurrently superseded) write fails with
 *       {@link ObjectOptimisticLockingFailureException}. That reproduces the
 *       COACTUPC read-then-rewrite "record changed by another user" outcome
 *       which the service layer maps to an HTTP&nbsp;409.</li>
 * </ol>
 *
 * <h2>Isolation and identifiers</h2>
 * <p>{@code @DataJpaTest} (inherited from the base class) wraps each test method
 * in a transaction that is rolled back on completion, while the committed
 * Flyway {@code V3__seed_data.sql} rows (50 accounts, ids {@code 1..50}) stay
 * visible. To avoid any primary-key collision with that seed &mdash; the
 * {@code @Id} is application-assigned, so ids are never generated &mdash; every
 * account created here uses a test-owned id at or above
 * {@code 900_000_000L}.</p>
 */
@DisplayName("AccountRepository — PostgreSQL 16 slice test")
class AccountRepositoryTest extends AbstractRepositoryTest {

    /** Test-owned id for the full-field round-trip test (well above the 1..50 seed range). */
    private static final long ROUND_TRIP_ID = 900_000_001L;

    /** Test-owned id for the decimal-fidelity test. */
    private static final long MONEY_ID = 900_000_002L;

    /** Test-owned id for the version-increment test. */
    private static final long VERSION_ID = 900_000_010L;

    /** Test-owned id for the stale-update conflict test. */
    private static final long STALE_ID = 900_000_011L;

    /** Documented Flyway {@code V3} seed account used only for optional secondary checks. */
    private static final long SEEDED_ID = 1L;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Builds a fully-populated, valid {@link Account} with the supplied
     * application-assigned id. Every monetary field is a scale-2
     * {@link BigDecimal} (matching {@code NUMERIC(12,2)}), all three dates are
     * set, and the active status is {@code "Y"}. Individual tests override the
     * specific fields they assert on so the baseline stays DRY.
     *
     * @param id the application-assigned {@code ACCT-ID} primary key (never {@code null})
     * @return a new transient {@code Account} ready to persist
     */
    private Account newAccount(Long id) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("100.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        account.setAcctExpirationDate(LocalDate.of(2027, 1, 14));
        account.setAcctReissueDate(LocalDate.of(2024, 1, 15));
        account.setAcctAddrZip("30301");
        account.setAcctGroupId("GRP0000001");
        return account;
    }

    /**
     * Persists an account with a distinct value in every mapped field and
     * verifies that all of them round-trip unchanged. The persistence context
     * is flushed and cleared before the read so {@code findById} is served from
     * the database rather than the first-level cache, which makes this a true
     * PostgreSQL round-trip. Money fields are compared by value with
     * {@code isEqualByComparingTo} to stay independent of scale representation;
     * dates, strings and the id are compared with {@code isEqualTo}.
     */
    @Test
    @DisplayName("save + findById round-trips every mapped field")
    void saveAndFindById_roundTripsAllFields() {
        Account a = newAccount(ROUND_TRIP_ID);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(new BigDecimal("1234567.89"));
        a.setAcctCreditLimit(new BigDecimal("5000.00"));
        a.setAcctCashCreditLimit(new BigDecimal("2500.50"));
        a.setAcctCurrCycCredit(new BigDecimal("321.00"));
        a.setAcctCurrCycDebit(new BigDecimal("-42.17"));
        a.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        a.setAcctExpirationDate(LocalDate.of(2027, 3, 31));
        a.setAcctReissueDate(LocalDate.of(2024, 6, 30));
        a.setAcctAddrZip("30301-1234");
        a.setAcctGroupId("GOLD000001");

        accountRepository.saveAndFlush(a);
        // Clear so the subsequent read hits PostgreSQL, not the persistence-context cache.
        entityManager.clear();

        Account found = accountRepository.findById(ROUND_TRIP_ID).orElseThrow();

        assertThat(found.getAcctId()).isEqualTo(ROUND_TRIP_ID);
        assertThat(found.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(found.getAcctCurrBal()).isEqualByComparingTo("1234567.89");
        assertThat(found.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(found.getAcctCashCreditLimit()).isEqualByComparingTo("2500.50");
        assertThat(found.getAcctCurrCycCredit()).isEqualByComparingTo("321.00");
        assertThat(found.getAcctCurrCycDebit()).isEqualByComparingTo("-42.17");
        assertThat(found.getAcctOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(found.getAcctExpirationDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(found.getAcctReissueDate()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(found.getAcctAddrZip()).isEqualTo("30301-1234");
        assertThat(found.getAcctGroupId()).isEqualTo("GOLD000001");
        // A freshly inserted row carries optimistic-lock version 0.
        assertThat(found.getVersion()).isEqualTo(0L);
    }

    /**
     * Proves exact decimal fidelity of the five signed {@code NUMERIC(12,2)}
     * money columns (AAP&nbsp;&sect;0.8.2): a negative balance, the maximum
     * value that fits the 10-integer/2-fraction picture, a sub-unit amount, and
     * zero all round-trip by value <em>and</em> retain scale 2. Preserving
     * scale 2 on read is what demonstrates the COBOL packed-decimal amounts were
     * never coerced through {@code float}/{@code double}.
     *
     * <p>As a secondary, deliberately non-brittle check it also confirms two
     * documented {@code V3} seed values for {@code acct_id = 1}
     * ({@code acct_curr_bal = 194.00}, {@code acct_credit_limit = 2020.00}),
     * demonstrating the real Flyway seed is present and readable.</p>
     */
    @Test
    @DisplayName("money fields preserve exact value, sign and scale 2")
    void moneyFields_preserveScaleAndValue() {
        Account a = newAccount(MONEY_ID);
        a.setAcctCurrBal(new BigDecimal("-100.05"));
        a.setAcctCreditLimit(new BigDecimal("99999999.99"));
        a.setAcctCashCreditLimit(new BigDecimal("12345678.90"));
        a.setAcctCurrCycCredit(new BigDecimal("0.01"));
        a.setAcctCurrCycDebit(new BigDecimal("0.00"));

        accountRepository.saveAndFlush(a);
        entityManager.clear();

        Account found = accountRepository.findById(MONEY_ID).orElseThrow();

        // Exact value (sign included), compared independently of representation.
        assertThat(found.getAcctCurrBal()).isEqualByComparingTo("-100.05");
        assertThat(found.getAcctCreditLimit()).isEqualByComparingTo("99999999.99");
        assertThat(found.getAcctCashCreditLimit()).isEqualByComparingTo("12345678.90");
        assertThat(found.getAcctCurrCycCredit()).isEqualByComparingTo("0.01");
        assertThat(found.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");

        // PostgreSQL NUMERIC(12,2) always yields scale 2 on read — no float/double drift.
        assertThat(found.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(found.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(found.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(found.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(found.getAcctCurrCycDebit().scale()).isEqualTo(2);

        // Optional secondary check against the committed Flyway V3 seed (acct_id = 1).
        Account seeded = accountRepository.findById(SEEDED_ID).orElseThrow();
        assertThat(seeded.getAcctCurrBal()).isEqualByComparingTo("194.00");
        assertThat(seeded.getAcctCreditLimit()).isEqualByComparingTo("2020.00");
    }

    /**
     * Verifies the positive optimistic-locking path: Hibernate assigns
     * {@link jakarta.persistence.Version @Version} value {@code 0} on insert and
     * increments it to {@code 1} on the next update. This is the mechanism that
     * later lets a stale write be detected. {@code persistFlushFind} inserts,
     * flushes and re-reads the managed instance in one step so the initial
     * version is observed exactly as the database stored it.
     */
    @Test
    @DisplayName("@Version starts at 0 and increments to 1 on update")
    void save_incrementsVersion() {
        Account persisted = entityManager.persistFlushFind(newAccount(VERSION_ID));

        // Hibernate initializes the @Version column to 0 for a new row.
        assertThat(persisted.getVersion()).isEqualTo(0L);

        persisted.setAcctActiveStatus("N");
        Account saved = accountRepository.saveAndFlush(persisted);

        // The version-checked UPDATE bumps the counter to 1.
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    /**
     * Reproduces the COACTUPC "record changed by another user" conflict and
     * asserts it surfaces as {@link ObjectOptimisticLockingFailureException}
     * (the Spring Data translation of Hibernate's {@code StaleObjectStateException}),
     * which the service layer maps to an HTTP&nbsp;409 (AAP&nbsp;&sect;0.8.4).
     *
     * <p>Sequence: (1) insert the row at version 0 and clear the context;
     * (2) load a copy and immediately detach it &mdash; this {@code stale} copy
     * still carries version 0; (3) load a fresh copy, mutate it and flush so the
     * database row advances to version 1; (4) mutate the stale copy and attempt
     * to save it. Hibernate's version-checked {@code UPDATE ... WHERE version = 0}
     * now matches zero rows, raising the optimistic-lock failure.
     * {@code saveAndFlush} (not {@code save}) is used so the exception is thrown
     * synchronously inside the assertion.</p>
     */
    @Test
    @DisplayName("stale update throws ObjectOptimisticLockingFailureException (409)")
    void staleUpdate_throwsOptimisticLockException() {
        long id = STALE_ID;
        entityManager.persistAndFlush(newAccount(id));
        entityManager.clear();

        // Stale copy: loaded then detached, so it keeps the original version 0.
        Account stale = accountRepository.findById(id).orElseThrow();
        entityManager.detach(stale);
        assertThat(stale.getVersion()).isEqualTo(0L);

        // Fresh copy: mutate and flush so the DB row moves on to version 1.
        Account fresh = accountRepository.findById(id).orElseThrow();
        fresh.setAcctCurrBal(new BigDecimal("500.00"));
        accountRepository.saveAndFlush(fresh);
        entityManager.flush();
        entityManager.clear();

        // Saving the stale (version 0) copy over the version 1 row must fail.
        stale.setAcctCurrBal(new BigDecimal("999.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
