package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.UserSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Spring Data JPA slice test for {@link UserSecurityRepository}, executed against
 * a <strong>real PostgreSQL&nbsp;16</strong> database provisioned by
 * Testcontainers through {@link AbstractRepositoryTest} — never an in-memory/H2
 * substitute and never a mock.
 *
 * <h2>What is under test</h2>
 * <p>{@link UserSecurityRepository} is the migration replacement for keyed access
 * to the legacy {@code USRSEC} VSAM KSDS dataset (record layout from copybook
 * {@code CSUSR01Y}, 80-byte {@code SEC-USER-DATA} record, base key
 * {@code SEC-USR-ID PIC X(08)}; frozen COBOL reference at source commit SHA
 * {@code 27d6c6f}). The repository declares <em>no</em> custom finder methods:
 * every legacy access is a primary-key or paged operation already inherited from
 * {@code JpaRepository} / {@code PagingAndSortingRepository} /
 * {@code CrudRepository}. This test therefore exercises exactly that inherited
 * surface — {@code findById(String)}, {@code findAll(Pageable)} and
 * {@code save} — over the eight-character {@code String} user id, and pins two
 * behaviours the migration must preserve:</p>
 * <ol>
 *   <li>the {@code findAll(Pageable)} page window of <strong>ten rows per
 *       page</strong> that backs the COBOL user-list screen
 *       ({@code COUSR00C}, transaction {@code CU00}, whose
 *       {@code USER-REC OCCURS 10 TIMES}); and</li>
 *   <li>the {@code secUsrType} {@code 'A'} (admin) / {@code 'U'} (regular)
 *       role discriminator carried verbatim from COBOL
 *       {@code SEC-USR-TYPE PIC X(01)}.</li>
 * </ol>
 *
 * <h2>Password-column width regression guard (C-003 / Decision Log D-002)</h2>
 * <p>The legacy {@code SEC-USR-PWD} field is {@code PIC X(08)} (an 8-character
 * plaintext password). The migration upgrades plaintext passwords to BCrypt
 * hashes — always 60 characters — so the {@code sec_usr_pwd} column is widened to
 * {@code VARCHAR(60)}. Both the seed-read test and the save/re-read round-trip
 * assert the full 60-character hash survives verbatim, guarding against a
 * regression that narrows the column and silently truncates the hash (which would
 * break sign-on).</p>
 *
 * <h2>Fixtures and isolation</h2>
 * <p>{@code AbstractRepositoryTest} runs the real Flyway migrations
 * ({@code V1__schema.sql} &rarr; {@code V2__indexes.sql} &rarr;
 * {@code V3__seed_data.sql}) once at context startup, committing the ten seeded
 * users: {@code ADMIN001}..{@code ADMIN005} ({@code secUsrType='A'}) and
 * {@code USER0001}..{@code USER0005} ({@code secUsrType='U'}), each carrying the
 * documented 60-character seed hash {@link #SEED_BCRYPT_HASH}. Because
 * {@code @DataJpaTest} wraps every test method in a transaction that is rolled
 * back on completion, each test observes exactly those ten committed rows plus
 * only the rows it inserts itself; nothing leaks between tests. This makes the
 * seed-count assertion in {@link #findAll_isPageable_tenPerPage()} deterministic.</p>
 */
class UserSecurityRepositoryTest extends AbstractRepositoryTest {

    /**
     * The exact 60-character BCrypt hash seeded by {@code V3__seed_data.sql} for
     * every user, and reused as the round-trip fixture in
     * {@link #saveAndFindById_roundTrips_bcryptPasswordAndType()}.
     *
     * <p>This is a <strong>test-only fixture, NOT a real or production
     * credential</strong> (&sect;0.8.1). Its purpose is purely to prove that the
     * widened {@code sec_usr_pwd} column ({@code VARCHAR(60)}, C-003 / D-002)
     * stores a complete BCrypt string without truncation. A BCrypt hash always
     * has the shape {@code $2a$<cost>$<22-char-salt><31-char-digest>} and is
     * exactly 60 characters long.</p>
     */
    private static final String SEED_BCRYPT_HASH =
            "$2a$10$IMVnFydbIxu.jlp2dOadduALE5ogKNEOmSyaBpxu1rAAvVNkwcMe2";

    /** The fixed length of a BCrypt hash — the width the password column must hold. */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * The user-list page size ({@code COUSR00C} browses {@code USRSEC} ten
     * records per screen; {@code USER-REC OCCURS 10 TIMES}). The calling service
     * supplies this window via {@code PageRequest.of(page, 10)}.
     */
    private static final int LIST_PAGE_SIZE = 10;

    /** The number of users committed by the {@code V3__seed_data.sql} migration. */
    private static final long SEEDED_USER_COUNT = 10L;

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Builds a transient {@link UserSecurity} for tests that need to insert their
     * own row. The first/last name are short, fixed constants (well within the
     * {@code VARCHAR(20)} columns); the caller supplies the eight-character id,
     * the {@code 'A'}/{@code 'U'} role and the password hash.
     *
     * @param id  the eight-character user id ({@code SEC-USR-ID PIC X(08)})
     * @param type the role discriminator {@code "A"} (admin) or {@code "U"} (regular)
     * @param pwd  the BCrypt password hash to store
     * @return an unsaved {@link UserSecurity} populated with the supplied values
     */
    private UserSecurity newUser(String id, String type, String pwd) {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(id);
        user.setSecUsrFname("TEST");
        user.setSecUsrLname("USER");
        user.setSecUsrPwd(pwd);
        user.setSecUsrType(type);
        return user;
    }

    /**
     * {@code findById(String)} resolves a seeded admin by its primary key and the
     * full 60-character BCrypt hash comes back intact.
     *
     * <p>Mirrors the {@code COSGN00C} sign-on read
     * ({@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)}), which maps to
     * the inherited {@code findById}. Asserting the hash is exactly 60 characters,
     * begins with the BCrypt {@code "$2a$"} prefix and equals the seed value
     * verbatim proves the widened {@code sec_usr_pwd} column (C-003 / D-002) holds
     * a complete hash. A regular user is also read to confirm the {@code 'A'} vs
     * {@code 'U'} discriminator round-trips.</p>
     */
    @Test
    void findById_returnsSeededAdminUser() {
        UserSecurity admin = userSecurityRepository.findById("ADMIN001").orElseThrow();

        assertThat(admin.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(admin.getSecUsrType()).isEqualTo("A");
        assertThat(admin.getSecUsrPwd())
                .hasSize(BCRYPT_HASH_LENGTH)
                .startsWith("$2a$")
                .isEqualTo(SEED_BCRYPT_HASH);

        UserSecurity regular = userSecurityRepository.findById("USER0001").orElseThrow();
        assertThat(regular.getSecUsrType()).isEqualTo("U");
    }

    /**
     * {@code findAll(Pageable)} honours the ten-rows-per-page window that backs
     * the {@code COUSR00C} user-list screen.
     *
     * <p>The test adds no users, so under {@code @DataJpaTest} rollback isolation
     * only the ten committed seed rows are visible. Requesting the first page at
     * size {@link #LIST_PAGE_SIZE} therefore yields all ten users on a single
     * page: {@code totalElements == 10}, at most (here exactly) ten rows in the
     * page content, and {@code totalPages == 1}. This documents the COUSR00C list
     * page size as an executable invariant.</p>
     */
    @Test
    void findAll_isPageable_tenPerPage() {
        Page<UserSecurity> page = userSecurityRepository.findAll(PageRequest.of(0, LIST_PAGE_SIZE));

        assertThat(page.getTotalElements()).isEqualTo(SEEDED_USER_COUNT);
        assertThat(page.getContent())
                .hasSizeLessThanOrEqualTo(LIST_PAGE_SIZE)
                .hasSize((int) SEEDED_USER_COUNT);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    /**
     * {@code save} then {@code findById} round-trips a user, and the full
     * 60-character BCrypt hash is stored and re-read without truncation.
     *
     * <p>The row is persisted with {@code save}, {@link TestEntityManager#flush()
     * flushed} to the database and the persistence context is
     * {@link TestEntityManager#clear() cleared} so the subsequent
     * {@code findById} performs a genuine reload from PostgreSQL rather than
     * returning the still-managed instance. The re-read user must carry the
     * supplied id, the {@code 'U'} role and the BCrypt hash byte-for-byte — the
     * definitive guard that the {@code VARCHAR(60)} column (C-003 / D-002)
     * accommodates a complete hash. The insert is rolled back at test end, leaving
     * only the committed seed rows.</p>
     */
    @Test
    void saveAndFindById_roundTrips_bcryptPasswordAndType() {
        userSecurityRepository.save(newUser("TSTUSR01", "U", SEED_BCRYPT_HASH));
        entityManager.flush();
        entityManager.clear();

        UserSecurity reread = userSecurityRepository.findById("TSTUSR01").orElseThrow();

        assertThat(reread.getSecUsrId()).isEqualTo("TSTUSR01");
        assertThat(reread.getSecUsrType()).isEqualTo("U");
        assertThat(reread.getSecUsrPwd())
                .hasSize(BCRYPT_HASH_LENGTH)
                .isEqualTo(SEED_BCRYPT_HASH);
    }
}
