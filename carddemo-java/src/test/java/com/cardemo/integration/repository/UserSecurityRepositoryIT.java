package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link UserSecurityRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code USRSEC}
 * (the security / user-credential file, key&nbsp;8 / record&nbsp;80).
 *
 * <h2>Legacy source and provisioning</h2>
 * <p>On the mainframe {@code USRSEC} was provisioned by
 * {@code app/jcl/DUSRSECJ.jcl}, whose IDCAMS step defines the cluster
 * ({@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS) KEYS(8,0)
 * RECORDSIZE(80,80) INDEXED)}) and then bulk-loads it with
 * {@code REPRO INFILE(IN) OUTFILE(OUT)} from the inline {@code IEBGENER} user
 * list. Its fixed-length 80-byte record layout is defined by copybook
 * {@code app/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}, primary key
 * {@code SEC-USR-ID PIC X(08)}). The cluster was reached exclusively through CICS
 * file control by two online program families:</p>
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl} &mdash; the sign-on program: a single
 *       <em>keyed</em> read ({@code EXEC CICS READ DATASET(WS-USRSEC-FILE)
 *       RIDFLD(WS-USER-ID)}) followed by {@code EVALUATE WS-RESP-CD}, the
 *       plaintext password compare ({@code IF SEC-USR-PWD = WS-USER-PWD}) and the
 *       role move ({@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}) that routes the
 *       user to the admin or main menu.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl} &mdash; the admin user-list program: a
 *       forward/backward <em>browse</em> ({@code STARTBR} GTEQ positioning on
 *       {@code SEC-USR-ID} + {@code READNEXT}/{@code READPREV}) that fills a
 *       fixed 10-row screen array ({@code 02 USER-REC OCCURS 10 TIMES}), with
 *       PF8 paging forward and PF7 paging backward.</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL
 * {@code user_security} table mapped by {@link UserSecurity}, and every access
 * path is served through the Spring Data {@link UserSecurityRepository}. These
 * tests prove the migration preserves behavior: the keyed sign-on read
 * ({@link UserSecurityRepository#findBySecUsrId(String)}), the keyed 10-rows/page
 * admin browse ({@link UserSecurityRepository#findBySecUsrIdGreaterThanEqual(String, Pageable)}),
 * the {@code SEC-USR-TYPE} {@code 'A'}/{@code 'U'} &rarr; {@link UserType} enum
 * conversion, the 10-row seed count parity, and &mdash; critically &mdash; the
 * single permitted behavioral change of the whole migration: the BCrypt password
 * upgrade.</p>
 *
 * <h2>Behavioral-parity ground truth (the 10 seeded users)</h2>
 * <p>The assertions below pin the <em>exact</em> rows the COBOL sign-on and admin
 * programs would have read, taken from the inline {@code DUSRSECJ.jcl} user list
 * which the Flyway {@code V3__seed_data.sql} migration loads into the throwaway
 * container. {@code count()} must therefore return exactly {@code 10}: five
 * administrators ({@code SEC-USR-TYPE 'A'}) and five regular users
 * ({@code SEC-USR-TYPE 'U'}).</p>
 * <table border="1">
 *   <caption>Seeded {@code USRSEC} rows ({@code user_id} / name / {@code user_type})</caption>
 *   <tr><th>{@code SEC-USR-ID}</th><th>{@code SEC-USR-FNAME}</th>
 *       <th>{@code SEC-USR-LNAME}</th><th>{@code SEC-USR-TYPE}</th></tr>
 *   <tr><td>{@code ADMIN001}</td><td>MARGARET</td><td>GOLD</td><td>{@code A} ({@link UserType#ADMIN})</td></tr>
 *   <tr><td>{@code ADMIN002}</td><td>RUSSELL</td><td>RUSSELL</td><td>{@code A}</td></tr>
 *   <tr><td>{@code ADMIN003}</td><td>RAYMOND</td><td>WHITMORE</td><td>{@code A}</td></tr>
 *   <tr><td>{@code ADMIN004}</td><td>EMMANUEL</td><td>CASGRAIN</td><td>{@code A}</td></tr>
 *   <tr><td>{@code ADMIN005}</td><td>GRANVILLE</td><td>LACHAPELLE</td><td>{@code A}</td></tr>
 *   <tr><td>{@code USER0001}</td><td>LAWRENCE</td><td>THOMAS</td><td>{@code U} ({@link UserType#USER})</td></tr>
 *   <tr><td>{@code USER0002}</td><td>AJITH</td><td>KUMAR</td><td>{@code U}</td></tr>
 *   <tr><td>{@code USER0003}</td><td>LAURITZ</td><td>ALME</td><td>{@code U}</td></tr>
 *   <tr><td>{@code USER0004}</td><td>AVERARDO</td><td>MAZZI</td><td>{@code U}</td></tr>
 *   <tr><td>{@code USER0005}</td><td>LEE</td><td>TING</td><td>{@code U}</td></tr>
 * </table>
 *
 * <h2>Simple {@link String}(8) primary key &mdash; the natural VSAM key</h2>
 * <p>{@code SEC-USR-ID PIC X(08)} is the 8-character cluster key; it becomes the
 * relational primary key {@link UserSecurity#getSecUsrId()} (PostgreSQL
 * {@code VARCHAR(8)}). The repository is therefore typed
 * {@code JpaRepository<UserSecurity, String>} and keyed reads use an 8-character
 * {@link String} literal ({@code findById("ADMIN001")}). The two declared derived
 * finders are the only custom query methods (Minimal Change Clause, AAP
 * &sect;0.7.1); they are exercised here alongside the inherited
 * {@code findById(String)} / {@code count()} / {@code findAll()} operations.</p>
 *
 * <h2>Fixed-width names &mdash; trim-tolerant assertions (AAP &sect;0.7.2)</h2>
 * <p>{@code SEC-USR-FNAME}/{@code SEC-USR-LNAME PIC X(20)} are fixed-width,
 * space-padded alphanumeric on the mainframe and are mapped to {@link String}
 * columns of length&nbsp;20. The authoritative {@code V3__seed_data.sql} inserts
 * trimmed literals into the {@code VARCHAR(20)} columns so PostgreSQL does not
 * re-pad them; but to remain robust against either representation these tests
 * assert against the <em>trimmed</em> value ({@code getSecUsrFname().trim()})
 * rather than relying on exact, non-padded equality. This honors the
 * external-interface-contract rule for fixed-width {@code X(n)} fields without
 * coupling the test to a particular padding strategy.</p>
 *
 * <h2>{@code SEC-USR-TYPE} &rarr; {@link UserType} enum ({@code 'A'}/{@code 'U'})</h2>
 * <p>{@code SEC-USR-TYPE PIC X(01)} ({@code 'A'}&nbsp;=&nbsp;admin,
 * {@code 'U'}&nbsp;=&nbsp;user) is modelled as the type-safe {@link UserType} enum
 * but persisted as the literal single character through the entity's
 * {@code UserTypeConverter}. These tests read the type back through
 * {@link UserSecurity#getSecUsrType()} and assert the enum value, proving the
 * converter round-trips {@code 'A'} &rarr; {@link UserType#ADMIN} and {@code 'U'}
 * &rarr; {@link UserType#USER}; the seed's exact five-admin / five-user split is
 * asserted as a whole-table converter-parity check.</p>
 *
 * <h2>The single permitted behavioral change &mdash; BCrypt (C-003, AAP &sect;0.7.2)</h2>
 * <p>The legacy {@code SEC-USR-PWD PIC X(08)} stored an 8-character
 * <em>plaintext</em> password (every seeded user's original secret was the
 * literal {@code "PASSWORD"}). Constraint C-003 mandates that plaintext credential
 * storage be replaced by a salted <strong>BCrypt</strong> hash &mdash; the
 * <em>only</em> deviation from strict byte-for-byte parity the Minimal Change
 * Clause permits (&sect;0.7.1). {@link #passwordHash_isBcrypt_notPlaintext()} is
 * the in-test evidence that the change is present in the seed: it asserts the
 * stored {@link UserSecurity#getSecUsrPwd()} is a 60-character BCrypt digest with
 * a {@code $2}-family prefix and is <strong>not</strong> the legacy plaintext.
 * BCrypt <em>verification</em> itself is a service concern (the migrated
 * {@code AuthenticationService} replacing the {@code COSGN00C} compare) and is not
 * exercised here; this repository tier asserts only the stored hash shape, never a
 * login.</p>
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
 * rolled back on completion; although every test here is read-only, this keeps
 * isolation uniform with the mutating sibling ITs and leaves the seeded data
 * pristine. {@code USRSEC} carries no {@code @Version} column (per AAP &sect;0.7.5
 * optimistic locking is applied only to {@code Account} and {@code Card}), so no
 * concurrency test is exercised here.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; base package {@code com.cardemo} (decision D-006). Behavior under
 * test is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}. The COBOL/JCL sources ({@code app/jcl/DUSRSECJ.jcl},
 * {@code app/cbl/COSGN00C.cbl}, {@code app/cbl/COUSR00C.cbl}) are read-only
 * reference material and are never copied into this repository.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserType
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("UserSecurityRepository (USRSEC / COSGN00C sign-on, COUSR00C admin browse) — behavioral-parity integration tests")
class UserSecurityRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of {@code USRSEC} rows seeded by {@code V3__seed_data.sql}
     * from the inline {@code DUSRSECJ.jcl} user list: five administrators plus five
     * regular users.
     */
    private static final long SEEDED_USER_ROWS = 10L;

    /** The expected number of seeded administrators ({@code SEC-USR-TYPE 'A'}). */
    private static final long SEEDED_ADMIN_ROWS = 5L;

    /** The expected number of seeded regular users ({@code SEC-USR-TYPE 'U'}). */
    private static final long SEEDED_USER_TYPE_ROWS = 5L;

    /** Primary-key user id of the first seeded administrator ({@code SEC-USR-ID PIC X(08)}). */
    private static final String ADMIN001_ID = "ADMIN001";

    /** Primary-key user id of the first seeded regular user ({@code SEC-USR-ID PIC X(08)}). */
    private static final String USER0001_ID = "USER0001";

    /** An 8-character user id that is intentionally absent from the seed (no such {@code USRSEC} row). */
    private static final String MISSING_ID = "NOPE9999";

    /**
     * The {@code COUSR00C} admin user-list page size: the screen displays a fixed
     * {@code 02 USER-REC OCCURS 10 TIMES} array, i.e. 10 rows per page.
     */
    private static final int ADMIN_PAGE_SIZE = 10;

    /** The fixed length of {@code SEC-USR-ID} ({@code PIC X(08)} &rarr; {@code VARCHAR(8)}). */
    private static final int USER_ID_LENGTH = 8;

    /**
     * The exact character length of a standard BCrypt digest ({@code $2a$}/{@code $2b$}/{@code $2y$}
     * encoding): 60 characters. The seeded {@code password} column stores a hash of this length.
     */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * The common prefix shared by every BCrypt {@code $2}-family encoding
     * ({@code $2a$}, {@code $2b$}, {@code $2y$}). Asserting {@code startsWith("$2")}
     * covers all three variants without pinning a single one.
     */
    private static final String BCRYPT_PREFIX = "$2";

    /**
     * A regular expression matching the full BCrypt digest structure
     * ({@code $2[aby]$} + 2-digit cost factor + {@code $} + 53 base64-ish characters),
     * used to prove the stored hash shape rigorously beyond the prefix check.
     */
    private static final String BCRYPT_PATTERN = "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$";

    /**
     * The legacy plaintext password every seeded user originally held
     * ({@code SEC-USR-PWD PIC X(08)} = {@code "PASSWORD"}). The BCrypt test asserts
     * the stored credential is <strong>not</strong> this value, proving the C-003
     * upgrade is present. This is the <em>only</em> reference to the legacy
     * plaintext, and it is used solely in a negative ({@code isNotEqualTo})
     * assertion &mdash; never to assert a stored equality.
     */
    private static final String LEGACY_PLAINTEXT_PASSWORD = "PASSWORD";

    /** Repository under test &mdash; the relational replacement for the {@code USRSEC} VSAM cluster. */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /**
     * Verifies that primary-key fetches of all 10 seeded user ids return the rows
     * loaded from {@code DUSRSECJ.jcl} &mdash; the simple-{@link String} keyed
     * lookup the migrated sign-on flow performs against the {@code user_security}
     * table (the relational replacement for the keyed {@code USRSEC} read in
     * {@code COSGN00C}).
     *
     * <p>The two anchor rows are asserted explicitly in the assertion helper:
     * {@code ADMIN001} resolves to the administrator MARGARET&nbsp;GOLD with
     * {@link UserType#ADMIN}, and {@code USER0001} resolves to the regular user
     * LAWRENCE&nbsp;THOMAS with {@link UserType#USER}. The remaining eight seeded
     * users are spot-checked through the same helper so that the full five-admin /
     * five-user fixture is exercised. Names ({@code SEC-USR-FNAME}/{@code SEC-USR-LNAME
     * PIC X(20)}) are asserted trim-tolerantly (see the class Javadoc).</p>
     *
     * <p>A whole-table converter-parity check closes the test: filtering
     * {@code findAll()} by {@link UserSecurity#getSecUsrType()} must yield exactly
     * five {@link UserType#ADMIN} rows and five {@link UserType#USER} rows, proving
     * the {@code SEC-USR-TYPE 'A'}/{@code 'U'} &rarr; enum converter round-trips for
     * every seeded record.</p>
     */
    @Test
    @DisplayName("findById(...) returns each seeded user with its UserType enum (ADMIN001->ADMIN/MARGARET GOLD, USER0001->USER/LAWRENCE THOMAS)")
    void findById_returnsSeededUser_withUserTypeEnum() {
        // --- Anchor rows asserted in full (primary key, trimmed names, UserType enum). ---
        assertSeededUser(ADMIN001_ID, "MARGARET", "GOLD", UserType.ADMIN);
        assertSeededUser(USER0001_ID, "LAWRENCE", "THOMAS", UserType.USER);

        // --- Spot checks across the remaining eight seeded users (5 admins 'A' / 5 users 'U'). ---
        assertSeededUser("ADMIN002", "RUSSELL", "RUSSELL", UserType.ADMIN);
        assertSeededUser("ADMIN003", "RAYMOND", "WHITMORE", UserType.ADMIN);
        assertSeededUser("ADMIN004", "EMMANUEL", "CASGRAIN", UserType.ADMIN);
        assertSeededUser("ADMIN005", "GRANVILLE", "LACHAPELLE", UserType.ADMIN);
        assertSeededUser("USER0002", "AJITH", "KUMAR", UserType.USER);
        assertSeededUser("USER0003", "LAURITZ", "ALME", UserType.USER);
        assertSeededUser("USER0004", "AVERARDO", "MAZZI", UserType.USER);
        assertSeededUser("USER0005", "LEE", "TING", UserType.USER);

        // --- Whole-table converter parity: exactly 5 ADMIN ('A') and 5 USER ('U'). ---
        List<UserSecurity> all = userSecurityRepository.findAll();
        assertThat(all)
                .as("the full USRSEC browse must return the 10 seeded users")
                .hasSize((int) SEEDED_USER_ROWS);
        long admins = all.stream().filter(u -> u.getSecUsrType() == UserType.ADMIN).count();
        long users = all.stream().filter(u -> u.getSecUsrType() == UserType.USER).count();
        assertThat(admins)
                .as("SEC-USR-TYPE 'A' -> UserType.ADMIN converter parity: exactly 5 administrators")
                .isEqualTo(SEEDED_ADMIN_ROWS);
        assertThat(users)
                .as("SEC-USR-TYPE 'U' -> UserType.USER converter parity: exactly 5 regular users")
                .isEqualTo(SEEDED_USER_TYPE_ROWS);
    }

    /**
     * Verifies the explicit {@code findBySecUsrId} derived finder &mdash; the
     * relational replacement for the {@code COSGN00C} keyed sign-on read
     * ({@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}).
     *
     * <p>For a seeded id ({@code ADMIN001}) the finder returns a present
     * {@link Optional} that resolves to the <em>same</em> record as the inherited
     * {@link UserSecurityRepository#findById(Object)} &mdash; within a single
     * persistence context the two access paths share the first-level cache and
     * therefore return the same managed instance, which this test asserts via
     * {@code isSameAs}. For an id that is not seeded ({@code NOPE9999}) the finder
     * returns {@link Optional#empty()} rather than throwing &mdash; the JPA
     * equivalent of the COBOL invalid-key ({@code DFHRESP(NOTFND)}) "user not found"
     * branch that {@code COSGN00C} handles without an abend.</p>
     */
    @Test
    @DisplayName("findBySecUsrId(\"ADMIN001\") equals findById result; findBySecUsrId(\"NOPE9999\") is empty")
    void findBySecUsrId_returnsUser() {
        Optional<UserSecurity> viaFindById = userSecurityRepository.findById(ADMIN001_ID);
        Optional<UserSecurity> viaFinder = userSecurityRepository.findBySecUsrId(ADMIN001_ID);

        assertThat(viaFindById)
                .as("findById(\"ADMIN001\") must be present (sanity for the equality assertion)")
                .isPresent();
        assertThat(viaFinder)
                .as("findBySecUsrId(\"ADMIN001\") must return a present Optional (COSGN00C keyed read)")
                .isPresent();

        // The explicit finder and the inherited findById resolve the SAME row. Within one
        // @Transactional persistence context they return the same managed instance (L1 cache),
        // so the finder result "equals" the findById result both by identity and by primary key.
        UserSecurity byFinder = viaFinder.get();
        UserSecurity byId = viaFindById.get();
        assertThat(byFinder)
                .as("findBySecUsrId must return the same managed instance as findById within one tx")
                .isSameAs(byId);
        assertThat(byFinder.getSecUsrId())
                .as("findBySecUsrId result must carry the requested primary key SEC-USR-ID")
                .isEqualTo(ADMIN001_ID);

        // An id that is not seeded yields Optional.empty() (COBOL DFHRESP(NOTFND) branch), not an error.
        assertThat(userSecurityRepository.findBySecUsrId(MISSING_ID))
                .as("findBySecUsrId(\"NOPE9999\") must be empty: a non-seeded id is the NOTFND path")
                .isEmpty();
    }

    /**
     * Verifies the {@code findBySecUsrIdGreaterThanEqual} paginated browse &mdash;
     * the relational replacement for the {@code COUSR00C} admin user-list screen,
     * which positioned the {@code USRSEC} browse with {@code STARTBR} (GTEQ) on
     * {@code SEC-USR-ID} and issued {@code READNEXT} to fill a fixed
     * {@code 02 USER-REC OCCURS 10 TIMES} (10 rows/page) array.
     *
     * <p>Browsing from the lowest seeded key ({@code "ADMIN001"}) with a 10-row
     * page must surface every seeded user, because all 10 ids are lexicographically
     * {@code >= "ADMIN001"} ({@code ADMIN001}&hellip;{@code ADMIN005},
     * {@code USER0001}&hellip;{@code USER0005}). The page is therefore non-empty,
     * reports {@code getTotalElements() == 10}, and &mdash; with an explicit
     * {@link Sort} by {@code secUsrId} so ordering is deterministic &mdash; its
     * content is ascending by id with {@code ADMIN001} first; every row's id is
     * confirmed {@code >= "ADMIN001"}. A second browse positioned at {@code "USER0001"}
     * proves the GTEQ semantics precisely: it returns exactly the five
     * {@code USER000n} rows (ids {@code >= "USER0001"}), demonstrating that the
     * {@code GreaterThanEqual} keyword reproduces {@code STARTBR} positioning rather
     * than returning the whole table. The requested page size (10) is asserted to be
     * honored, and a single page never exceeds it.</p>
     */
    @Test
    @DisplayName("findBySecUsrIdGreaterThanEqual(\"ADMIN001\", PageRequest.of(0,10)) honors the COUSR00C 10-rows/page GTEQ browse (total 10)")
    void findBySecUsrIdGreaterThanEqual_paginates() {
        // Explicit Sort by the key makes ordering deterministic for the assertions below; the
        // method-name predicate (user_id >= ?) reproduces the STARTBR GTEQ positioning of COUSR00C.
        Pageable firstPage = PageRequest.of(0, ADMIN_PAGE_SIZE, Sort.by("secUsrId"));

        Page<UserSecurity> page = userSecurityRepository.findBySecUsrIdGreaterThanEqual(ADMIN001_ID, firstPage);

        // The requested page size is the COUSR00C 10-rows-per-page screen contract.
        assertThat(page.getSize())
                .as("page size must reflect the requested COUSR00C 10-rows-per-page contract")
                .isEqualTo(ADMIN_PAGE_SIZE);

        // Every seeded id is >= "ADMIN001", so the GTEQ browse spans the whole 10-row fixture.
        assertThat(page.getTotalElements())
                .as("all 10 seeded ids are >= \"ADMIN001\", so the GTEQ browse total must be 10")
                .isEqualTo(SEEDED_USER_ROWS);

        // A single page holds at most the page size, and here it holds all 10 (non-empty).
        assertThat(page.getContent())
                .as("the first page of the GTEQ browse must be non-empty and within the page size")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(ADMIN_PAGE_SIZE);

        // Every row's key honors the >= "ADMIN001" lower bound (STARTBR GTEQ semantics).
        assertThat(page.getContent())
                .as("every browsed row's SEC-USR-ID must be >= the \"ADMIN001\" start key")
                .allSatisfy(user -> assertThat(user.getSecUsrId()).isGreaterThanOrEqualTo(ADMIN001_ID));

        // With the explicit Sort, the content is ascending by id and ADMIN001 is the first row.
        assertThat(page.getContent())
                .as("with Sort.by(\"secUsrId\") the browse content must be ascending by user id")
                .isSortedAccordingTo(Comparator.comparing(UserSecurity::getSecUsrId));
        assertThat(page.getContent().get(0).getSecUsrId())
                .as("the first browsed row from \"ADMIN001\" must be ADMIN001 itself")
                .isEqualTo(ADMIN001_ID);

        // Positioning the browse at "USER0001" must return ONLY the five USER000n rows (>= USER0001),
        // proving GreaterThanEqual reproduces STARTBR positioning rather than scanning the whole table.
        Page<UserSecurity> fromUser =
                userSecurityRepository.findBySecUsrIdGreaterThanEqual(USER0001_ID, firstPage);
        assertThat(fromUser.getTotalElements())
                .as("GTEQ browse from \"USER0001\" must return exactly the five USER000n rows")
                .isEqualTo(SEEDED_USER_TYPE_ROWS);
        assertThat(fromUser.getContent())
                .as("every row of the \"USER0001\" GTEQ browse must have id >= \"USER0001\"")
                .isNotEmpty()
                .allSatisfy(user -> assertThat(user.getSecUsrId()).isGreaterThanOrEqualTo(USER0001_ID));
    }

    /**
     * Verifies the migration's single permitted behavioral change (constraint
     * C-003, AAP &sect;0.7.2): the legacy {@code SEC-USR-PWD PIC X(08)} plaintext
     * password is stored as a salted <strong>BCrypt</strong> hash, never as the
     * original plaintext.
     *
     * <p>For the seeded administrator {@code ADMIN001} the stored
     * {@link UserSecurity#getSecUsrPwd()} is asserted to be a well-formed BCrypt
     * digest: exactly 60 characters, a {@code $2}-family prefix
     * ({@code $2a$}/{@code $2b$}/{@code $2y$}), and a match for the full BCrypt
     * structure ({@code $2[aby]$} + cost factor + salt/hash). It is then asserted to
     * be <strong>not</strong> the legacy plaintext {@code "PASSWORD"}. This is the
     * in-test proof that the plaintext {@code USRSEC} credential has been upgraded
     * to BCrypt in the seed. BCrypt verification (matching an entered password to
     * this hash) is a service-layer concern of the migrated {@code AuthenticationService}
     * &mdash; the replacement for the {@code COSGN00C} {@code IF SEC-USR-PWD = WS-USER-PWD}
     * compare &mdash; and is deliberately <strong>not</strong> exercised here; only
     * the stored hash shape is asserted.</p>
     */
    @Test
    @DisplayName("passwordHash is a 60-char BCrypt $2 digest, not the plaintext \"PASSWORD\" (C-003)")
    void passwordHash_isBcrypt_notPlaintext() {
        Optional<UserSecurity> admin = userSecurityRepository.findById(ADMIN001_ID);
        assertThat(admin)
                .as("ADMIN001 must be present so its stored credential can be inspected")
                .isPresent();

        String passwordHash = admin.get().getSecUsrPwd();
        assertThat(passwordHash)
                .as("SEC-USR-PWD must be stored as a non-null, non-blank BCrypt hash (C-003)")
                .isNotNull()
                .isNotBlank()
                // A standard BCrypt digest is exactly 60 characters long.
                .hasSize(BCRYPT_HASH_LENGTH)
                // ... and begins with a $2-family marker ($2a$/$2b$/$2y$).
                .startsWith(BCRYPT_PREFIX)
                // ... matching the full BCrypt structure for a rigorous shape proof.
                .matches(BCRYPT_PATTERN)
                // The decisive C-003 assertion: the stored credential is NOT the legacy plaintext.
                .isNotEqualTo(LEGACY_PLAINTEXT_PASSWORD);
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the 10 users
     * that {@code DUSRSECJ.jcl} / {@code V3__seed_data.sql} provide &mdash; the count
     * equivalent of the {@code COUSR00C} browse over the entire {@code USRSEC}
     * cluster. A raw-JDBC {@code COUNT(*)} cross-check (via the inherited
     * {@code jdbcTemplate}) confirms the JPA count matches the physical
     * {@code user_security} table, bypassing the JPA persistence context.
     */
    @Test
    @DisplayName("count() equals the 10 seeded USRSEC users (cross-checked via raw JDBC)")
    void count_matchesSeededRowCount() {
        assertThat(userSecurityRepository.count())
                .as("USRSEC replacement must contain exactly the 10 seeded users")
                .isEqualTo(SEEDED_USER_ROWS);

        // Cross-check against the physical table using the inherited JdbcTemplate, bypassing JPA.
        Long jdbcCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_security", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) on user_security must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_USER_ROWS);
    }

    /**
     * Asserts that the seeded user with the given {@code SEC-USR-ID} is present and
     * carries the expected name and {@link UserType}.
     *
     * <p>Centralizes the per-user parity checks shared by
     * {@link #findById_returnsSeededUser_withUserTypeEnum()}: a present
     * {@link Optional} from the keyed {@code findById}, exact primary-key parity,
     * and trim-tolerant first/last-name equality (the {@code SEC-USR-FNAME}/
     * {@code SEC-USR-LNAME PIC X(20)} fixed-width fields). The {@code SEC-USR-TYPE}
     * is read back through {@link UserSecurity#getSecUsrType()} and compared to the
     * expected enum, proving the {@code 'A'}/{@code 'U'} converter round-trip for
     * that row.</p>
     *
     * @param userId            the 8-character {@code SEC-USR-ID} primary key to fetch
     * @param expectedFirstName the expected (trimmed) {@code SEC-USR-FNAME}
     * @param expectedLastName  the expected (trimmed) {@code SEC-USR-LNAME}
     * @param expectedType      the expected {@link UserType} ({@link UserType#ADMIN} or {@link UserType#USER})
     */
    private void assertSeededUser(String userId, String expectedFirstName,
            String expectedLastName, UserType expectedType) {
        Optional<UserSecurity> found = userSecurityRepository.findById(userId);
        assertThat(found)
                .as("seeded user %s must be present in the Flyway-seeded USRSEC replacement", userId)
                .isPresent();

        UserSecurity user = found.get();

        // Primary-key parity — SEC-USR-ID PIC X(08) -> String(8).
        assertThat(user.getSecUsrId())
                .as("primary key parity for %s — SEC-USR-ID PIC X(08) -> String(8)", userId)
                .isEqualTo(userId);

        // First name — SEC-USR-FNAME PIC X(20); trim-tolerant (fixed-width X(n) contract, AAP §0.7.2).
        assertThat(user.getSecUsrFname())
                .as("first name must be stored for %s", userId)
                .isNotNull();
        assertThat(user.getSecUsrFname().trim())
                .as("first name parity for %s — SEC-USR-FNAME PIC X(20), trimmed", userId)
                .isEqualTo(expectedFirstName);

        // Last name — SEC-USR-LNAME PIC X(20); trim-tolerant.
        assertThat(user.getSecUsrLname())
                .as("last name must be stored for %s", userId)
                .isNotNull();
        assertThat(user.getSecUsrLname().trim())
                .as("last name parity for %s — SEC-USR-LNAME PIC X(20), trimmed", userId)
                .isEqualTo(expectedLastName);

        // User type — SEC-USR-TYPE PIC X(01) 'A'/'U' -> UserType via UserTypeConverter.
        assertThat(user.getSecUsrType())
                .as("SEC-USR-TYPE enum parity for %s — 'A'/'U' converter round-trip", userId)
                .isEqualTo(expectedType);
    }
}
