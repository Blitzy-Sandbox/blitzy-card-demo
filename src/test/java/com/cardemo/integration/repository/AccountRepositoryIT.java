package com.cardemo.integration.repository;

// Standard library imports
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Internal project imports
import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;

// JUnit 5 annotations
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Spring Framework dependency injection
import org.springframework.beans.factory.annotation.Autowired;

// Spring Boot JPA test slice
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

// Spring ORM optimistic locking exception
import org.springframework.orm.ObjectOptimisticLockingFailureException;

// Spring Test context
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Testcontainers — use the 2.x non-deprecated package path
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// AssertJ fluent assertions
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link AccountRepository} verifying all CRUD and custom query
 * operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>Validates Account entity mapping from COBOL ACCTDAT VSAM KSDS dataset
 * (ACCTFILE.jcl KEYS(11 0), CVACT01Y.cpy 300-byte record layout) including:
 * <ul>
 *   <li>Keyed read by 11-character account ID preserving leading zeros</li>
 *   <li>BigDecimal precision for all COMP-3 PIC S9(10)V99 monetary fields</li>
 *   <li>JPA @Version optimistic locking (COACTUPC.cbl SYNCPOINT ROLLBACK semantics)</li>
 *   <li>Custom derived query methods for active status and partial key matching</li>
 * </ul>
 *
 * <p>Seed data: 50 account records loaded via Flyway V3 migration from acctdata.txt.
 * All BigDecimal assertions use {@code compareTo()} per AAP §0.8.2 zero floating-point rule.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class AccountRepositoryIT {

    /**
     * PostgreSQL 16-alpine container managed by Testcontainers.
     * Provides a real RDBMS for NUMERIC(12,2) precision and @Version verification
     * instead of H2 in-memory (which can mask precision and locking issues).
     */
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("carddemo")
            .withUsername("test")
            .withPassword("test");

    /**
     * Injects Testcontainers PostgreSQL connection properties into Spring context,
     * overriding any values from application-test.yml to ensure the @Container-managed
     * database is used (not the Testcontainers JDBC URL driver from the profile).
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Disable Hikari auto-commit so Spring's JpaTransactionManager can manage
        // transactions and roll back after each @DataJpaTest method cleanly
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
        // Align Hibernate with Hikari: tell Hibernate the pool disables autocommit
        // (base application.yml sets provider_disables_autocommit=true but Hikari
        // defaults to auto-commit=true — the mismatch causes rollback failures)
        registry.add("spring.jpa.properties.hibernate.connection.provider_disables_autocommit", () -> "true");
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ========================================================================
    // Test 1: findById — existing seeded account (VSAM KSDS keyed read)
    // ========================================================================

    @Test
    @DisplayName("findById returns existing seeded account with correct field values from acctdata.txt (VSAM KSDS keyed read)")
    void testFindById_ExistingAccount() {
        // Clear persistence context to force a true database round-trip
        entityManager.clear();

        // Action: keyed read by 11-char account ID (ACCTFILE.jcl KEYS(11 0))
        Optional<Account> result = accountRepository.findById("00000000001");

        // Assert: Optional is present
        assertThat(result.isPresent()).isTrue();
        Account account = result.get();

        // String fields — ACCT-ID PIC 9(11), ACCT-ACTIVE-STATUS PIC X(01)
        assertThat(account.getAcctId()).isEqualTo("00000000001");
        assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(account.getAcctAddrZip()).isEqualTo("A000000000");
        assertThat(account.getAcctGroupId()).isEqualTo("A000000000");

        // BigDecimal fields — compareTo() ONLY, NEVER equals() (AAP §0.8.2)
        // Seed data account #1: curr_bal=194.00, credit_limit=2020.00, cash_credit_limit=1020.00
        assertThat(account.getAcctCurrBal()).isNotNull();
        assertThat(account.getAcctCurrBal().compareTo(new BigDecimal("194.00"))).isZero();

        assertThat(account.getAcctCreditLimit()).isNotNull();
        assertThat(account.getAcctCreditLimit().compareTo(new BigDecimal("2020.00"))).isZero();

        assertThat(account.getAcctCashCreditLimit()).isNotNull();
        assertThat(account.getAcctCashCreditLimit().compareTo(new BigDecimal("1020.00"))).isZero();

        assertThat(account.getAcctCurrCycCredit()).isNotNull();
        assertThat(account.getAcctCurrCycCredit().compareTo(new BigDecimal("0.00"))).isZero();

        assertThat(account.getAcctCurrCycDebit()).isNotNull();
        assertThat(account.getAcctCurrCycDebit().compareTo(new BigDecimal("0.00"))).isZero();

        // Date fields — ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE
        assertThat(account.getAcctOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(account.getAcctExpDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getAcctReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));

        // Version from seed data (version INTEGER DEFAULT 0)
        assertThat(account.getVersion()).isEqualTo(0);
    }

    // ========================================================================
    // Test 2: findById — non-existent account
    // ========================================================================

    @Test
    @DisplayName("findById returns empty Optional for non-existent account ID")
    void testFindById_NonExistentAccount() {
        Optional<Account> result = accountRepository.findById("99999999999");

        // Assert: Optional is empty (VSAM INVALID KEY equivalent)
        assertThat(result.isEmpty()).isTrue();
    }

    // ========================================================================
    // Test 3: save and retrieve — full round-trip with all field types
    // ========================================================================

    @Test
    @DisplayName("save and retrieve preserves all field values including BigDecimal precision, dates, and initial @Version")
    void testSaveAndRetrieve() {
        // Setup: use no-arg constructor + all setters (covers all Account setter members)
        Account account = new Account();
        account.setAcctId("99999900001");
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1500.50"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("2500.75"));
        account.setAcctOpenDate(LocalDate.of(2023, 1, 15));
        account.setAcctExpDate(LocalDate.of(2028, 6, 30));
        account.setAcctReissueDate(LocalDate.of(2026, 3, 10));
        account.setAcctCurrCycCredit(new BigDecimal("250.25"));
        account.setAcctCurrCycDebit(new BigDecimal("100.50"));
        account.setAcctAddrZip("90210");
        account.setAcctGroupId("GRPA000001");
        account.setVersion(null); // JPA manages version; verify setter accessibility

        // Action: save → flush → clear → findById (true database round-trip)
        accountRepository.save(account);
        entityManager.flush();
        entityManager.clear();

        Optional<Account> result = accountRepository.findById("99999900001");
        assertThat(result).isPresent();

        Account loaded = result.get();

        // String fields round-trip
        assertThat(loaded.getAcctId()).isEqualTo("99999900001");
        assertThat(loaded.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(loaded.getAcctAddrZip()).isEqualTo("90210");
        assertThat(loaded.getAcctGroupId()).isEqualTo("GRPA000001");

        // BigDecimal precision — compareTo() ONLY (AAP §0.8.2)
        assertThat(loaded.getAcctCurrBal().compareTo(new BigDecimal("1500.50"))).isZero();
        assertThat(loaded.getAcctCreditLimit().compareTo(new BigDecimal("5000.00"))).isZero();
        assertThat(loaded.getAcctCashCreditLimit().compareTo(new BigDecimal("2500.75"))).isZero();
        assertThat(loaded.getAcctCurrCycCredit().compareTo(new BigDecimal("250.25"))).isZero();
        assertThat(loaded.getAcctCurrCycDebit().compareTo(new BigDecimal("100.50"))).isZero();

        // Date fields round-trip
        assertThat(loaded.getAcctOpenDate()).isEqualTo(LocalDate.of(2023, 1, 15));
        assertThat(loaded.getAcctExpDate()).isEqualTo(LocalDate.of(2028, 6, 30));
        assertThat(loaded.getAcctReissueDate()).isEqualTo(LocalDate.of(2026, 3, 10));

        // Version: JPA @Version starts at 0 for newly persisted entities
        assertThat(loaded.getVersion()).isEqualTo(0);
    }

    // ========================================================================
    // Test 4: findByAcctActiveStatus — custom derived query
    // ========================================================================

    @Test
    @DisplayName("findByAcctActiveStatus returns all 50 active accounts from seed data")
    void testFindByAcctActiveStatus() {
        entityManager.clear();

        // Action: custom derived query on active_status column
        List<Account> activeAccounts = accountRepository.findByAcctActiveStatus("Y");

        // Assert: non-empty list
        assertThat(activeAccounts.isEmpty()).isFalse();

        // Assert: all 50 seed accounts have 'Y' active status (from acctdata.txt)
        assertThat(activeAccounts.size()).isEqualTo(50);

        // Assert: every returned account has active status 'Y' (uses List.stream())
        activeAccounts.stream()
                .forEach(a -> assertThat(a.getAcctActiveStatus()).isEqualTo("Y"));

        // Cross-validate with findAll() — all accounts in seed data are active
        List<Account> allAccounts = accountRepository.findAll();
        assertThat(allAccounts.size()).isEqualTo(activeAccounts.size());
    }

    // ========================================================================
    // Test 5: findByAcctIdStartingWith — partial key match (COBOL STARTBR)
    // ========================================================================

    @Test
    @DisplayName("findByAcctIdStartingWith returns accounts matching prefix (COBOL VSAM STARTBR GTEQ pattern)")
    void testFindByAcctIdStartingWith() {
        entityManager.clear();

        // Action: partial key match — prefix "0000000000" matches IDs 00000000001-00000000009
        List<Account> result = accountRepository.findByAcctIdStartingWith("0000000000");

        // Assert: 9 accounts match (IDs ending in 1-9 with the 10-digit prefix)
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.size()).isEqualTo(9);

        // Assert: all returned account IDs start with the prefix (uses List.stream())
        result.stream()
                .forEach(a -> assertThat(a.getAcctId()).startsWith("0000000000"));
    }

    // ========================================================================
    // Test 6: BigDecimal precision — zero floating-point substitution
    // ========================================================================

    @Test
    @DisplayName("BigDecimal precision preserved for max PIC S9(10)V99 boundary value — zero floating-point substitution (AAP §0.8.2)")
    void testBigDecimalPrecision() {
        // Setup: maximum monetary value for NUMERIC(12,2) — PIC S9(10)V99
        // 10 integer digits + 2 decimal digits = 99999999.99 fits in NUMERIC(12,2)
        BigDecimal maxValue = new BigDecimal("99999999.99");

        Account account = new Account(
                "99999900002", "Y",
                maxValue, maxValue, maxValue,
                LocalDate.now(), LocalDate.of(2030, 12, 31), LocalDate.now(),
                maxValue, maxValue,
                "99999", "GRPMAX0001"
        );

        // Action: save and retrieve via database round-trip
        accountRepository.save(account);
        entityManager.flush();
        entityManager.clear();

        Account loaded = accountRepository.findById("99999900002").get();

        // CRITICAL: All 5 monetary fields must preserve exact BigDecimal values
        // Use compareTo() == 0, NEVER equals() — BigDecimal.equals is scale-sensitive
        // (e.g., new BigDecimal("1.0").equals(new BigDecimal("1.00")) returns false)
        assertThat(loaded.getAcctCurrBal().compareTo(maxValue)).isZero();
        assertThat(loaded.getAcctCreditLimit().compareTo(maxValue)).isZero();
        assertThat(loaded.getAcctCashCreditLimit().compareTo(maxValue)).isZero();
        assertThat(loaded.getAcctCurrCycCredit().compareTo(maxValue)).isZero();
        assertThat(loaded.getAcctCurrCycDebit().compareTo(maxValue)).isZero();

        // Verify scale preservation: NUMERIC(12,2) → BigDecimal scale == 2
        assertThat(loaded.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCurrCycDebit().scale()).isEqualTo(2);
    }

    // ========================================================================
    // Test 7: Optimistic locking — @Version for COACTUPC.cbl SYNCPOINT
    // ========================================================================

    @Test
    @DisplayName("@Version optimistic locking prevents concurrent modification (AAP §0.8.4 COACTUPC SYNCPOINT ROLLBACK semantics)")
    void testOptimisticLocking() {
        // Setup: create and persist a new account
        Account account = new Account(
                "99999900003", "Y",
                new BigDecimal("500.00"), new BigDecimal("5000.00"), new BigDecimal("2500.00"),
                LocalDate.of(2023, 6, 1), LocalDate.of(2028, 6, 1), LocalDate.of(2026, 6, 1),
                new BigDecimal("0.00"), new BigDecimal("0.00"),
                "12345", "GRPLCK0001"
        );
        entityManager.persistAndFlush(account);
        entityManager.clear();

        // Load entity and verify initial version
        Account firstLoad = accountRepository.findById("99999900003").get();
        assertThat(firstLoad.getVersion()).isEqualTo(0);

        // Detach first copy to simulate stale read (User A reads record)
        entityManager.detach(firstLoad);

        // Load fresh managed copy (User B reads same record)
        Account secondLoad = accountRepository.findById("99999900003").get();

        // User B saves first — version increments from 0 → 1
        secondLoad.setAcctCurrBal(new BigDecimal("888.88"));
        accountRepository.saveAndFlush(secondLoad);
        entityManager.clear();

        // User A tries to save stale copy (version=0, but DB now has version=1)
        firstLoad.setAcctCurrBal(new BigDecimal("111.11"));

        // Assert: stale save throws optimistic locking exception
        // Spring ORM wraps Hibernate's StaleObjectStateException
        assertThatThrownBy(() -> accountRepository.saveAndFlush(firstLoad))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ========================================================================
    // Test 8: Version increment tracking across multiple saves
    // ========================================================================

    @Test
    @DisplayName("JPA @Version field increments on each successive save operation (0 → 1 → 2)")
    void testSaveUpdatesVersion() {
        // Setup: create and persist
        Account account = new Account(
                "99999900004", "Y",
                new BigDecimal("1000.00"), new BigDecimal("10000.00"), new BigDecimal("5000.00"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2029, 12, 31), LocalDate.of(2027, 6, 15),
                new BigDecimal("0.00"), new BigDecimal("0.00"),
                "54321", "GRPVER0001"
        );

        accountRepository.save(account);
        entityManager.flush();
        entityManager.clear();

        // Verify initial version == 0
        Account afterCreate = accountRepository.findById("99999900004").get();
        assertThat(afterCreate.getVersion()).isEqualTo(0);

        // First update → version should increment to 1
        afterCreate.setAcctCurrBal(new BigDecimal("1100.00"));
        accountRepository.save(afterCreate);
        entityManager.flush();
        entityManager.clear();

        Account afterFirstUpdate = accountRepository.findById("99999900004").get();
        assertThat(afterFirstUpdate.getVersion()).isEqualTo(1);

        // Second update → version should increment to 2
        afterFirstUpdate.setAcctCurrBal(new BigDecimal("1200.00"));
        accountRepository.save(afterFirstUpdate);
        entityManager.flush();
        entityManager.clear();

        Account afterSecondUpdate = accountRepository.findById("99999900004").get();
        assertThat(afterSecondUpdate.getVersion()).isEqualTo(2);
    }
}
