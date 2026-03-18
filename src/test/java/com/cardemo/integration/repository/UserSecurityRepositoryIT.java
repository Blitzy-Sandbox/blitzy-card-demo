package com.cardemo.integration.repository;

// Standard library imports
import java.util.List;
import java.util.Optional;

// Internal project imports — from depends_on_files only
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

// JUnit 5 annotations
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Spring Framework dependency injection
import org.springframework.beans.factory.annotation.Autowired;

// Spring Boot JPA test slice
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

// Spring Test context
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Testcontainers — 2.x package path
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// AssertJ fluent assertions
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link UserSecurityRepository} verifying all CRUD and
 * custom query operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>Validates UserSecurity entity mapping from COBOL USRSEC VSAM KSDS dataset
 * (DUSRSECJ.jcl KEYS(8,0), CSUSR01Y.cpy 80-byte record layout) including:</p>
 * <ul>
 *   <li>Authentication lookup by user ID ({@code findBySecUsrId}) mapping
 *       COBOL {@code READ USRSEC KEY IS SEC-USR-ID} from COSGN00C.cbl</li>
 *   <li>BCrypt password hash storage — upgraded from COBOL plaintext
 *       (PIC X(08)) per AAP §0.8.1 security improvement</li>
 *   <li>UserType enum persistence via {@code UserTypeConverter} mapping
 *       COBOL {@code SEC-USR-TYPE PIC X(01)} — 'A' (ADMIN) / 'U' (USER)</li>
 *   <li>Full CRUD lifecycle: save (COUSR01C), update (COUSR02C), delete (COUSR03C)</li>
 *   <li>Seed data verification: 10 users (5 admins + 5 regular users) from
 *       DUSRSECJ.jcl inline data loaded via Flyway V3 migration</li>
 * </ul>
 *
 * <p>Seed data from DUSRSECJ.jcl (80-byte records, CSUSR01Y.cpy layout):</p>
 * <pre>
 *   ADMIN001–005: Margaret Gold, Russell Russell, Raymond Whitmore,
 *                 Emmanuel Casgrain, Granville Lachapelle (type 'A')
 *   USER0001–0005: Lawrence Thomas, Ajith Kumar, Lauritz Alme,
 *                  Averardo Mazzi, Lee Ting (type 'U')
 *   Passwords: PASSWORDA (admins) / PASSWORDU (users) → BCrypt hashed in V3__seed_data.sql
 * </pre>
 *
 * <p>All BCrypt hash assertions verify:
 *   prefix ($2a$ or $2b$), length (60 characters), and non-plaintext storage.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class UserSecurityRepositoryIT {

    /**
     * PostgreSQL 16-alpine container managed by Testcontainers.
     * Provides a real RDBMS with CHAR(1) column semantics for user type
     * CHECK constraint verification and VARCHAR(60) for BCrypt hashes,
     * instead of H2 in-memory (which can mask column type issues).
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
        registry.add("spring.jpa.properties.hibernate.connection.provider_disables_autocommit",
                () -> "true");
    }

    @Autowired
    private UserSecurityRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // BCrypt hash constants for test assertions
    // =========================================================================

    /**
     * BCrypt hash prefix pattern — standard BCrypt output starts with "$2a$" or "$2b$".
     * Used in assertions to verify that password storage has been upgraded from
     * COBOL plaintext (PIC X(08)) to BCrypt per AAP §0.8.1.
     */
    private static final String BCRYPT_PREFIX_2A = "$2a$";
    private static final String BCRYPT_PREFIX_2B = "$2b$";

    /**
     * Standard BCrypt hash output length (60 characters).
     * Format: $2a$10$22-char-salt + 31-char-hash = 60 total characters.
     */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * Pre-computed BCrypt hash for test user creation.
     * This is a valid BCrypt hash of the string "TESTPASSWORD" with cost factor 10.
     * Generated via: BCryptPasswordEncoder(10).encode("TESTPASSWORD")
     *
     * Using a well-formed hash ensures that save operations succeed and
     * round-trip verification accurately tests hash preservation.
     */
    private static final String TEST_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    // =========================================================================
    // Test Methods
    // =========================================================================

    /**
     * Verifies {@code findBySecUsrId} retrieves an admin user from seed data.
     *
     * <p>Maps COBOL: {@code READ USRSEC KEY IS SEC-USR-ID} from COSGN00C.cbl.
     * The VSAM primary key (KEYS(8,0)) maps to the {@code usr_id} VARCHAR(8) PK.</p>
     *
     * <p>Seed data reference (DUSRSECJ.jcl line 35):
     *   {@code ADMIN001MARGARET            GOLD                PASSWORDA}</p>
     *
     * <p>CRITICAL: Verifies BCrypt hash format — password must NOT be plaintext
     * "PASSWORDA" (COBOL original). AAP §0.8.1 mandates BCrypt upgrade.</p>
     */
    @Test
    @DisplayName("findBySecUsrId — retrieves ADMIN001 with correct fields and BCrypt hash")
    void testFindBySecUsrId_AdminUser() {
        // Action: Authentication lookup by primary key — same pattern as COSGN00C.cbl
        Optional<UserSecurity> result = repository.findBySecUsrId("ADMIN001");

        // Assertions: Record present
        assertThat(result).isPresent();
        UserSecurity admin = result.get();

        // Primary key: SEC-USR-ID PIC X(08) — 8 characters, no padding expected
        assertThat(admin.getSecUsrId()).isEqualTo("ADMIN001");

        // First name: SEC-USR-FNAME PIC X(20) — MARGARET from seed data (uppercase)
        assertThat(admin.getSecUsrFname()).isEqualTo("MARGARET");

        // Last name: SEC-USR-LNAME PIC X(20) — GOLD from seed data (uppercase)
        assertThat(admin.getSecUsrLname()).isEqualTo("GOLD");

        // User type: SEC-USR-TYPE PIC X(01) — 'A' → UserType.ADMIN
        assertThat(admin.getSecUsrType()).isEqualTo(UserType.ADMIN);

        // BCrypt password hash: upgraded from plaintext PIC X(08) per AAP §0.8.1
        String passwordHash = admin.getSecUsrPwd();
        assertThat(passwordHash).isNotNull();
        assertThat(passwordHash).startsWith(BCRYPT_PREFIX_2A);
        assertThat(passwordHash).hasSize(BCRYPT_HASH_LENGTH);
    }

    /**
     * Verifies {@code findBySecUsrId} retrieves a regular user from seed data.
     *
     * <p>Seed data reference (DUSRSECJ.jcl line 40):
     *   {@code USER0001LAWRENCE            THOMAS              PASSWORDU}</p>
     *
     * <p>Validates UserType.USER mapping from SEC-USR-TYPE PIC X(01) = 'U'.</p>
     */
    @Test
    @DisplayName("findBySecUsrId — retrieves USER0001 with USER type and BCrypt hash")
    void testFindBySecUsrId_RegularUser() {
        // Action: Authentication lookup for regular user
        Optional<UserSecurity> result = repository.findBySecUsrId("USER0001");

        // Assertions: Record present
        assertThat(result).isPresent();
        UserSecurity user = result.get();

        // Primary key
        assertThat(user.getSecUsrId()).isEqualTo("USER0001");

        // First name from seed data (uppercase per COBOL convention)
        assertThat(user.getSecUsrFname()).isEqualTo("LAWRENCE");

        // Last name from seed data
        assertThat(user.getSecUsrLname()).isEqualTo("THOMAS");

        // User type: SEC-USR-TYPE PIC X(01) — 'U' → UserType.USER
        assertThat(user.getSecUsrType()).isEqualTo(UserType.USER);

        // BCrypt password hash verification
        String passwordHash = user.getSecUsrPwd();
        assertThat(passwordHash).isNotNull();
        assertThat(passwordHash.startsWith(BCRYPT_PREFIX_2A)
                || passwordHash.startsWith(BCRYPT_PREFIX_2B)).isTrue();
        assertThat(passwordHash).hasSize(BCRYPT_HASH_LENGTH);
    }

    /**
     * Verifies {@code findBySecUsrId} returns empty for a non-existent user ID.
     *
     * <p>Maps COBOL: {@code FILE STATUS '23'} (record not found) on
     * {@code READ USRSEC KEY IS SEC-USR-ID} when the key does not exist
     * in the VSAM KSDS.</p>
     */
    @Test
    @DisplayName("findBySecUsrId — returns empty Optional for non-existent user")
    void testFindBySecUsrId_NonExistent() {
        // Action: Lookup with a user ID that does not exist in seed data
        Optional<UserSecurity> result = repository.findBySecUsrId("NOBODY99");

        // Assertion: Empty optional — maps FILE STATUS '23' (INVALID KEY)
        assertThat(result).isEmpty();
    }

    /**
     * Verifies {@code save} persists a new UserSecurity record and all fields
     * survive a full database round-trip (flush + clear + re-read).
     *
     * <p>Maps COBOL: {@code WRITE USRSEC} from COUSR01C.cbl (user creation).
     * The PIC X(08) primary key is a user-supplied identifier, not auto-generated.</p>
     *
     * <p>CRITICAL: BCrypt hash must be preserved exactly through save and re-read —
     * no truncation, no modification. The VARCHAR(60) column must store the full
     * 60-character BCrypt output without loss.</p>
     */
    @Test
    @DisplayName("save — persists new user with BCrypt hash and round-trip verification")
    void testSave_NewUser() {
        // Setup: Create a new UserSecurity entity via all-argument constructor
        // SEC-USR-ID PIC X(08): "NEWUSR01" — 8 characters exactly
        UserSecurity newUser = new UserSecurity(
                "NEWUSR01",       // secUsrId — PK, PIC X(08)
                "TestFirst",      // secUsrFname — PIC X(20)
                "TestLast",       // secUsrLname — PIC X(20)
                TEST_BCRYPT_HASH, // secUsrPwd — BCrypt hash (60 chars)
                UserType.USER     // secUsrType — PIC X(01) → 'U'
        );

        // Action: Save, flush to DB, clear persistence context for true round-trip
        repository.save(newUser);
        entityManager.flush();
        entityManager.clear();

        // Re-read from database using inherited JpaRepository.findById
        Optional<UserSecurity> reloaded = repository.findById("NEWUSR01");

        // Assertions: Round-trip preserves all fields
        assertThat(reloaded).isPresent();
        UserSecurity saved = reloaded.get();

        assertThat(saved.getSecUsrId()).isEqualTo("NEWUSR01");
        assertThat(saved.getSecUsrFname()).isEqualTo("TestFirst");
        assertThat(saved.getSecUsrLname()).isEqualTo("TestLast");
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.USER);

        // BCrypt hash preserved exactly — no truncation by VARCHAR(60)
        assertThat(saved.getSecUsrPwd()).isEqualTo(TEST_BCRYPT_HASH);
        assertThat(saved.getSecUsrPwd()).startsWith(BCRYPT_PREFIX_2A);
        assertThat(saved.getSecUsrPwd()).hasSize(BCRYPT_HASH_LENGTH);
    }

    /**
     * Verifies {@code delete} removes a user record from the database.
     *
     * <p>Maps COBOL: {@code DELETE USRSEC} from COUSR03C.cbl (user deletion
     * with confirmation). After deletion, a {@code READ USRSEC KEY IS SEC-USR-ID}
     * must return FILE STATUS '23' (record not found).</p>
     */
    @Test
    @DisplayName("delete — removes user and verifies not found after deletion")
    void testDelete_User() {
        // Setup: Create a new user specifically for deletion testing
        // Using no-arg constructor + setters to verify JPA entity contract
        // (mirrors COBOL MOVE statements to SEC-USER-DATA fields before WRITE)
        UserSecurity deleteTarget = new UserSecurity();
        deleteTarget.setSecUsrId("DELUSR01");
        deleteTarget.setSecUsrFname("DeleteMe");
        deleteTarget.setSecUsrLname("TestUser");
        deleteTarget.setSecUsrPwd(TEST_BCRYPT_HASH);
        deleteTarget.setSecUsrType(UserType.USER);
        repository.save(deleteTarget);
        entityManager.flush();
        entityManager.clear();

        // Verify the user exists before deletion
        Optional<UserSecurity> beforeDelete = repository.findBySecUsrId("DELUSR01");
        assertThat(beforeDelete).isPresent();

        // Action: Delete the user record
        repository.delete(beforeDelete.get());
        entityManager.flush();
        entityManager.clear();

        // Assertion: User no longer exists — maps FILE STATUS '23' after DELETE
        Optional<UserSecurity> afterDelete = repository.findBySecUsrId("DELUSR01");
        assertThat(afterDelete).isEmpty();
    }

    /**
     * CRITICAL test — AAP §0.8.1: Plaintext password upgraded to BCrypt.
     *
     * <p>Verifies that the seed data admin user's password field contains a valid
     * BCrypt hash, NOT the original COBOL plaintext "PASSWORDA". This is the
     * single most important security verification in the user security domain.</p>
     *
     * <p>COBOL original (DUSRSECJ.jcl): {@code SEC-USR-PWD PIC X(08)} stored
     * plaintext "PASSWORD" (8 bytes). V3__seed_data.sql replaces this with
     * BCrypt hash of "PASSWORDA" — 60 characters, $2a$10$ prefix.</p>
     *
     * <p>BCrypt structure validation:
     *   $2a$ — algorithm identifier (BCrypt, revision a)
     *   10$  — cost factor (2^10 = 1024 iterations)
     *   22 chars — Base64-encoded 128-bit salt
     *   31 chars — Base64-encoded 184-bit hash
     *   Total: 60 characters exactly</p>
     */
    @Test
    @DisplayName("BCrypt password hash storage — verifies non-plaintext, valid BCrypt format")
    void testBCryptPasswordHashStorage() {
        // Action: Retrieve seed data admin user for password hash inspection
        Optional<UserSecurity> result = repository.findBySecUsrId("ADMIN001");
        assertThat(result).isPresent();

        String passwordHash = result.get().getSecUsrPwd();

        // Assertion 1: Password hash is NOT plaintext "PASSWORD" (original COBOL value)
        assertThat(passwordHash).isNotEqualTo("PASSWORD");
        // Also verify it's not "PASSWORDA" (the full plaintext + type combo from seed data)
        assertThat(passwordHash).isNotEqualTo("PASSWORDA");

        // Assertion 2: Password hash starts with BCrypt prefix "$2a$" or "$2b$"
        assertThat(passwordHash.startsWith(BCRYPT_PREFIX_2A)
                || passwordHash.startsWith(BCRYPT_PREFIX_2B))
                .as("Password hash must start with BCrypt prefix $2a$ or $2b$")
                .isTrue();

        // Assertion 3: Password hash is exactly 60 characters (standard BCrypt output)
        assertThat(passwordHash).hasSize(BCRYPT_HASH_LENGTH);

        // Assertion 4: BCrypt structure — contains cost factor after prefix
        // Pattern: $2a$NN$ where NN is 2-digit cost factor (typically 10-12)
        assertThat(passwordHash).matches("^\\$2[ab]\\$\\d{2}\\$.{53}$");

        // Assertion 5: Verify regular user also has BCrypt hash (not just admins)
        Optional<UserSecurity> regularUser = repository.findBySecUsrId("USER0001");
        assertThat(regularUser).isPresent();
        String regularHash = regularUser.get().getSecUsrPwd();
        assertThat(regularHash).isNotEqualTo("PASSWORD");
        assertThat(regularHash).isNotEqualTo("PASSWORDU");
        assertThat(regularHash.startsWith(BCRYPT_PREFIX_2A)
                || regularHash.startsWith(BCRYPT_PREFIX_2B)).isTrue();
        assertThat(regularHash).hasSize(BCRYPT_HASH_LENGTH);
    }

    /**
     * Verifies {@code findAll} returns all 10 seed data users with correct
     * type distribution: 5 ADMIN + 5 USER.
     *
     * <p>Maps COBOL: Full sequential read of USRSEC VSAM KSDS dataset.
     * Seed data from DUSRSECJ.jcl inline records (lines 35-44).</p>
     *
     * <p>Record count breakdown:
     *   ADMIN001-005 → 5 records with SEC-USR-TYPE = 'A' (UserType.ADMIN)
     *   USER0001-0005 → 5 records with SEC-USR-TYPE = 'U' (UserType.USER)</p>
     */
    @Test
    @DisplayName("findAll — returns 10 seed data users: 5 ADMIN + 5 USER")
    void testFindAll_SeedDataUserCount() {
        // Action: Retrieve all user security records
        List<UserSecurity> allUsers = repository.findAll();

        // Assertion 1: Exactly 10 users from DUSRSECJ.jcl seed data
        assertThat(allUsers).hasSize(10);

        // Assertion 2: 5 users have type ADMIN
        long adminCount = allUsers.stream()
                .filter(u -> u.getSecUsrType() == UserType.ADMIN)
                .count();
        assertThat(adminCount).isEqualTo(5);

        // Assertion 3: 5 users have type USER
        long userCount = allUsers.stream()
                .filter(u -> u.getSecUsrType() == UserType.USER)
                .count();
        assertThat(userCount).isEqualTo(5);

        // Assertion 4: Also verify via repository.count() — maps COBOL file record count
        assertThat(repository.count()).isEqualTo(10);
    }

    /**
     * Verifies that an existing user record can be updated and the changes
     * persist through a full database round-trip.
     *
     * <p>Maps COBOL: {@code REWRITE USRSEC} from COUSR02C.cbl (user update).
     * The COBOL program reads the record ({@code READ USRSEC}), modifies fields
     * in working storage, then issues {@code REWRITE USRSEC} to persist changes.</p>
     *
     * <p>This test modifies the first name of a seed data user and verifies
     * the change survives flush + clear + re-read.</p>
     */
    @Test
    @DisplayName("updateUser — modifies first name and verifies persistence")
    void testUpdateUser() {
        // Setup: Find existing seed data user ADMIN002 (RUSSELL RUSSELL)
        Optional<UserSecurity> found = repository.findBySecUsrId("ADMIN002");
        assertThat(found).isPresent();

        UserSecurity user = found.get();

        // Verify original name from DUSRSECJ.jcl seed data
        assertThat(user.getSecUsrFname()).isEqualTo("RUSSELL");

        // Action: Modify the first name — maps COBOL MOVE "UPDATED" TO SEC-USR-FNAME
        user.setSecUsrFname("UPDATED");
        repository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Re-read from database to verify persistence
        Optional<UserSecurity> reloaded = repository.findBySecUsrId("ADMIN002");
        assertThat(reloaded).isPresent();

        // Assertion: Updated name reflected after round-trip
        UserSecurity updated = reloaded.get();
        assertThat(updated.getSecUsrFname()).isEqualTo("UPDATED");

        // Verify other fields unchanged — REWRITE preserves unmodified fields
        assertThat(updated.getSecUsrLname()).isEqualTo("RUSSELL");
        assertThat(updated.getSecUsrType()).isEqualTo(UserType.ADMIN);
        assertThat(updated.getSecUsrPwd()).startsWith(BCRYPT_PREFIX_2A);
    }
}
