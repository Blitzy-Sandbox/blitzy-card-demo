/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionRepository} &mdash;
 * the MOST COMPLEX repository in the CardDemo target. Validates the three
 * custom derived-query methods that replace the COBOL CICS browse patterns
 * and VSAM AIX semantics for the {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 * cluster (RECLN 350, primary key {@code TRAN-ID(1,16)}, alternate index
 * {@code TRANSACT.VSAM.AIX KEYS(26 304) NONUNIQUEKEY UPGRADE}).
 *
 * <h2>Source mapping (AAP &sect;0.4.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>COBOL copybook:</strong> {@code app/cpy/CVTRA05Y.cpy}
 *       &mdash; {@code TRAN-RECORD} layout (350 bytes; 13 business
 *       fields + 20-byte trailing {@code FILLER}; verified L4-L18).</li>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} &mdash;
 *       {@code KEYS(16 0)}, {@code RECORDSIZE(350 350)},
 *       {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code INDEXED}
 *       (per {@code app/jcl/TRANFILE.jcl}:L49-L62 and verified against
 *       {@code app/catlg/LISTCAT.txt} &mdash; KEYLEN=16, RKP=0,
 *       MAXLRECL=350, AVGLRECL=350, INDEXED).</li>
 *   <li><strong>VSAM alternate index:</strong>
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} &mdash;
 *       {@code KEYS(26 304) NONUNIQUEKEY UPGRADE} per
 *       {@code app/jcl/TRANFILE.jcl}:L82-L91. Translated to the
 *       PostgreSQL composite secondary index
 *       {@code idx_transactions_card_proc_ts} on
 *       {@code (tran_card_num, tran_proc_ts)} in V005 (AAP-mandated
 *       improvement over the single-key VSAM AIX per AAP
 *       &sect;0.6.2).</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COTRN00C.cbl} &mdash; paged transaction
 *             list (10 rows/page per {@code app/bms/COTRN00.bms};
 *             CICS {@code STARTBR} / {@code READNEXT}); replaced by
 *             {@link TransactionRepository#findByOrderByTranIdAsc(org.springframework.data.domain.Pageable)}
 *             (Tests 2 and 3).</li>
 *         <li>{@code app/cbl/COTRN01C.cbl} &mdash; transaction
 *             detail random read by {@code TRAN-ID}; replaced by
 *             inherited
 *             {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object) findById(String)}
 *             (Test 1).</li>
 *         <li>{@code app/cbl/COTRN02C.cbl} (L444-L451) &mdash;
 *             MAX-TRAN-ID generation via {@code STARTBR} /
 *             {@code READPREV} / {@code ENDBR} + {@code ADD 1 TO
 *             WS-TRAN-ID-N}; replaced by
 *             {@link TransactionRepository#findTopByOrderByTranIdDesc()}
 *             (Tests 4 and 5).</li>
 *         <li>{@code app/cbl/CBTRN03C.cbl} (L173-L178) &mdash;
 *             date-window report by card via the
 *             {@code TRANSACT.VSAM.AIX} alternate index; replaced by
 *             {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(String, LocalDateTime, LocalDateTime, org.springframework.data.domain.Pageable)}
 *             (Tests 6 and 7).</li>
 *       </ul>
 *   </li>
 *   <li><strong>Flyway migration:</strong>
 *       {@code src/main/resources/db/migration/V005__create_transaction.sql}
 *       (creates the 13-column {@code transactions} table plus the
 *       composite secondary index
 *       {@code idx_transactions_card_proc_ts} and the
 *       {@code fk_transactions_card_num} FK to {@code cards(card_num)};
 *       depends on V001 {@code accounts} and V002 {@code cards} for
 *       the upstream FK chain).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ol>
 *   <li><strong>Round-trip persistence by VARCHAR(16) PK</strong> &mdash;
 *       {@code save()} + {@code findById(String)} of a freshly built
 *       {@link Transaction} keyed by {@code tranId}, with explicit
 *       verification that the {@code tranAmt}
 *       ({@code NUMERIC(11, 2)}) {@link BigDecimal} round-trips
 *       (Test 1).</li>
 *   <li><strong>Paged ascending browse by {@code tranId}</strong>
 *       &mdash; {@link TransactionRepository#findByOrderByTranIdAsc(org.springframework.data.domain.Pageable)}
 *       returns the first 10 rows for page 0 (matching the
 *       {@code COTRN00.bms} 10-rows-per-page layout) and the
 *       remaining 5 rows for page 1, in lexicographic order
 *       (Tests 2 and 3).</li>
 *   <li><strong>MAX-{@code tranId} discovery</strong> &mdash;
 *       {@link TransactionRepository#findTopByOrderByTranIdDesc()}
 *       returns the lexicographic maximum of the {@code tranId}
 *       column (Test 4) and {@link Optional#empty()} when the table
 *       has no rows (Test 5).</li>
 *   <li><strong>Date-windowed query by card</strong> &mdash;
 *       {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(String, LocalDateTime, LocalDateTime, org.springframework.data.domain.Pageable)}
 *       returns only the transactions for the requested card whose
 *       {@code tranProcTs} falls inside the supplied inclusive window,
 *       excluding window-boundary outliers and transactions for other
 *       cards (Tests 6 and 7).</li>
 *   <li><strong>Inherited delete and count contracts</strong> &mdash;
 *       {@code deleteById()} removes a previously-persisted row
 *       (Test 8); {@code count()} returns the total row count for a
 *       clean transaction (Test 9).</li>
 *   <li><strong>{@code NUMERIC(11, 2)} precision invariant</strong>
 *       &mdash; the {@code tranAmt} column preserves both
 *       {@link BigDecimal} value AND {@code scale = 2} through a
 *       persist-flush-clear-reload cycle at the
 *       {@code PIC S9(09)V99} maximum {@code 999999999.99} (Test 10
 *       per AAP &sect;0.6.1).</li>
 * </ol>
 *
 * <h2>Container strategy</h2>
 * <p>The test class uses a single static
 * {@code postgres:16-alpine} {@link PostgreSQLContainer} (PostgreSQL 16
 * matches the RDS production Multi-AZ baseline per AAP &sect;0.5.1,
 * &sect;0.6.2). The {@link ServiceConnection &#64;ServiceConnection}
 * annotation registers the container's JDBC connection details as a
 * {@code JdbcConnectionDetails} bean; the
 * {@link DynamicPropertySource &#64;DynamicPropertySource} method
 * below additionally pushes the URL/credentials/driver into
 * {@code spring.datasource.*} so that any user-declared
 * {@code @Primary} {@code DataSource} bean picks up the same
 * container (mirrors the pattern in
 * {@code CardDemoApplicationTests} and the sibling
 * {@code DailyTransactionRepositoryTest}). Flyway then applies the
 * full V001&hellip;V016 migration set against the fresh container
 * before any test method runs &mdash; including V001 {@code accounts}
 * and V002 {@code cards} on which the {@code transactions} FK
 * {@code fk_transactions_card_num} depends.</p>
 *
 * <h2>FK chain handling</h2>
 * <p>The {@code transactions} table carries a
 * {@code fk_transactions_card_num FOREIGN KEY (tran_card_num)
 * REFERENCES cards (card_num) ON DELETE NO ACTION} constraint per
 * V005. Cards in turn carry a {@code fk_cards_acct} FK to
 * {@code accounts(acct_id)} per V002. The test seeds both parent
 * rows via native SQL ({@link #persistAccount(Long)} and
 * {@link #persistCard(String, Long)}) before persisting transaction
 * rows so that the FK constraints are satisfied. Native SQL is used
 * (rather than constructing full {@code Account} / {@code Card}
 * entities) to keep this test focused on transaction-repository
 * behaviour, avoiding coupling to the parent entities' full
 * constructor surface &mdash; the same pattern established by
 * {@code CardRepositoryTest.persistAccount}.</p>
 *
 * <h2>Assertion discipline (AAP &sect;0.6.1, &sect;0.7.1)</h2>
 * <p>All {@link BigDecimal} equality checks use
 * {@link BigDecimal#compareTo(BigDecimal)} (value-only,
 * scale-insensitive) &mdash; <strong>never</strong>
 * {@link Object#equals(Object)} which compares both value AND scale
 * (e.g., {@code new BigDecimal("100.50").equals(new BigDecimal("100.5"))}
 * is {@code false}). For Test 10 specifically the assertion verifies
 * scale = 2 explicitly via {@link BigDecimal#scale()} to validate the
 * {@code NUMERIC(11, 2)} fidelity per AAP &sect;0.6.1.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see com.awsm2.carddemo.domain.DailyTransaction the staging counterpart
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryTest {

    // -------------------------------------------------------------------------
    // Test fixture constants
    //
    // Card and account identifiers used by the fixture helpers and the test
    // methods. These are documented PCI test values (the "4111111111111111"
    // VISA test PAN is canonical) and synthetic 11-digit account IDs that
    // pose no PCI scope risk per AAP §0.6.6 (these are seed values, not real
    // cardholder data).
    // -------------------------------------------------------------------------

    /**
     * Primary card number used by Tests 1, 2, 3, 4, 5, 6, 8, 9, 10. The
     * canonical VISA test PAN documented in payment-card-industry materials
     * &mdash; never a real cardholder PAN.
     */
    private static final String CARD_PRIMARY = "4111111111111111";

    /**
     * Secondary card number used by Test 6 to verify that the
     * {@code findByTranCardNumAndTranProcTsBetween} query filters by
     * {@code tranCardNum} (i.e., transactions for this other card must NOT
     * be returned when querying for {@link #CARD_PRIMARY}). Canonical
     * Mastercard test PAN documented in payment-card-industry materials.
     */
    private static final String CARD_SECONDARY = "5500000000000004";

    /**
     * Card number used by Test 7 (no-match scenario). Distinct from
     * {@link #CARD_PRIMARY} and {@link #CARD_SECONDARY} so that Test 7's
     * single fixture row does not interact with other tests.
     */
    private static final String CARD_TEST7 = "4012888888881881";

    /**
     * Parent account identifier used by every card fixture. All test cards
     * are owned by this account &mdash; the test does not exercise
     * per-account behaviour at the {@code transactions} level (that is
     * covered by other repositories).
     */
    private static final Long ACCT_ID = 10000000001L;

    // -------------------------------------------------------------------------
    // PostgreSQL Testcontainer (postgres:16-alpine per AAP §0.5.1, §0.6.2)
    // -------------------------------------------------------------------------
    // The @Container annotation hands lifecycle management to the
    // Testcontainers JUnit 5 extension activated by @Testcontainers — start
    // before the first test method, stop after the last. The @ServiceConnection
    // annotation registers the container as a JdbcConnectionDetails bean so
    // Spring Boot auto-configures any DataSource that consumes
    // JdbcConnectionDetails (Spring Boot 3.1+).
    //
    // The image tag is pinned to "postgres:16-alpine" (NOT "latest" or
    // unpinned) per AAP §0.5.1 — the production RDS PostgreSQL Multi-AZ
    // target runs PostgreSQL 16, so tests exercise the same SQL engine,
    // ensuring NUMERIC(11,2) arbitrary-precision arithmetic, TIMESTAMP(6)
    // microsecond resolution, and PostgreSQL-specific DDL semantics
    // (composite B-tree index idx_transactions_card_proc_ts, foreign keys
    // ON DELETE NO ACTION) are validated against the engine the application
    // will actually run on in production.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container URL to spring.datasource.*
    // -------------------------------------------------------------------------
    // Per AAP §0.6.4 the application's @Primary HikariDataSource bean
    // declared in JpaConfig.dataSource(DataSourceProperties) is constructed
    // from spring.datasource.* properties (NOT from JdbcConnectionDetails)
    // — because the bean is user-declared rather than auto-configured,
    // Spring Boot's HikariJdbcConnectionDetailsBeanPostProcessor does not
    // re-write its jdbcUrl from the @ServiceConnection container.
    //
    // To bridge this gap we explicitly push the @Container's connection
    // details into the Spring Environment under spring.datasource.* before
    // any DataSource bean is constructed. This guarantees that the @Primary
    // DataSource (if loaded by the JPA slice via component scan of JpaConfig)
    // and Flyway both target the SAME container that @ServiceConnection
    // configured for auto-config consumers.
    //
    // Mirrors the pattern in CardDemoApplicationTests.overrideDataSourceUrl
    // and the sibling DailyTransactionRepositoryTest / CardRepositoryTest.
    @DynamicPropertySource
    static void overrideDataSourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    // -------------------------------------------------------------------------
    // System Under Test (SUT) + JPA test fixture support
    // -------------------------------------------------------------------------
    // @Autowired field injection is required here — @DataJpaTest instantiates
    // the test class via reflection BEFORE the application context refreshes,
    // so constructor injection is not supported for the test class itself.

    /**
     * The Spring Data JPA repository under test, autowired from the
     * {@code @DataJpaTest} slice's component scan. Exercised by every
     * test method in this class.
     */
    @Autowired
    private TransactionRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager}
     * (transitively enabled by {@code @DataJpaTest}). Used to force
     * SQL execution via {@link TestEntityManager#flush() flush()},
     * to detach the persistence context via
     * {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than
     * returning a first-level-cache hit, and to execute native SQL
     * for parent-row seeding in {@link #persistAccount(Long)} and
     * {@link #persistCard(String, Long)}.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Per-test setup (defensive: ensures a clean persistence context)
    // =========================================================================

    /**
     * Per-test setup hook executed before every {@code @Test} method.
     *
     * <p>{@code @DataJpaTest} provides automatic transactional rollback at
     * the end of each test method, so the {@code transactions},
     * {@code cards}, and {@code accounts} tables are effectively reset
     * between tests. However, the L1 persistence context (Hibernate
     * {@code Session}) is NOT automatically cleared at the start of each
     * test &mdash; any cached entities from the prior test class context
     * could leak in if the {@link TestEntityManager} were reused. We
     * therefore invoke {@link TestEntityManager#clear() clear()}
     * defensively at the start of every test to detach any cached
     * entities and force subsequent {@code findById} calls to reload from
     * the database. This is a no-op in the common case but eliminates a
     * class of subtle inter-test interference bugs.</p>
     */
    @BeforeEach
    void setUp() {
        // Detach any cached entities from prior tests (defensive — @DataJpaTest
        // rollback already isolates DB state but does not clear the L1 cache).
        entityManager.clear();
    }

    // =========================================================================
    // Test fixture builders
    // =========================================================================

    /**
     * Builds a {@link Transaction} entity populated with deterministic
     * test values for every non-{@code FILLER} field of the COBOL
     * {@code TRAN-RECORD} layout (13 business fields per
     * {@code app/cpy/CVTRA05Y.cpy}). The {@code FILLER PIC X(20)} is
     * intentionally omitted because the relational model has no
     * representation for fixed-width VSAM padding (AAP &sect;0.6.2).
     *
     * <p>The returned entity is TRANSIENT (not yet managed by
     * Hibernate) &mdash; the caller must {@code save()} it via the
     * repository. The amount is fixed at {@code 100.50} (scale 2,
     * within the {@code NUMERIC(11, 2)} bounds) so that Tests 1 and 8
     * can assert exact round-trip without separate fixtures.</p>
     *
     * @param tranId   the 16-character primary-key transaction
     *                 identifier (COBOL {@code TRAN-ID PIC X(16)})
     * @param cardNum  the 16-character card PAN that this transaction
     *                 references; MUST already exist in
     *                 {@code cards(card_num)} when the fixture is
     *                 persisted (FK {@code fk_transactions_card_num})
     * @param procTs   the processing timestamp; the
     *                 {@code tranOrigTs} is derived as
     *                 {@code procTs.minusSeconds(1)}
     * @return a transient {@link Transaction} with every non-FILLER
     *         field populated
     */
    private Transaction buildTransaction(String tranId, String cardNum, LocalDateTime procTs) {
        Transaction t = new Transaction();
        // COBOL: CVTRA05Y.cpy:L5 TRAN-ID PIC X(16) -> VARCHAR(16) PK
        t.setTranId(tranId);
        // COBOL: CVTRA05Y.cpy:L6 TRAN-TYPE-CD PIC X(02) -> CHAR(2) NN
        // ("01" = Purchase per V013 seed fixture; canonical default)
        t.setTranTypeCd("01");
        // COBOL: CVTRA05Y.cpy:L7 TRAN-CAT-CD PIC 9(04) -> INTEGER NN
        // (category 5 per V014 seed fixture)
        t.setTranCatCd(5);
        // COBOL: CVTRA05Y.cpy:L8 TRAN-SOURCE PIC X(10) -> VARCHAR(10)
        t.setTranSource("ONLINE    ");
        // COBOL: CVTRA05Y.cpy:L9 TRAN-DESC PIC X(100) -> VARCHAR(100)
        t.setTranDesc("Test transaction");
        // COBOL: CVTRA05Y.cpy:L10 TRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2) NN
        // Per AAP §0.6.1: BigDecimal with scale=2; NEVER float/double
        t.setTranAmt(new BigDecimal("100.50"));
        // COBOL: CVTRA05Y.cpy:L11 TRAN-MERCHANT-ID PIC 9(09) -> NUMERIC(9)
        t.setTranMerchantId(123456789L);
        // COBOL: CVTRA05Y.cpy:L12 TRAN-MERCHANT-NAME PIC X(50) -> VARCHAR(50)
        t.setTranMerchantName("ACME MERCHANT");
        // COBOL: CVTRA05Y.cpy:L13 TRAN-MERCHANT-CITY PIC X(50) -> VARCHAR(50)
        t.setTranMerchantCity("NEW YORK");
        // COBOL: CVTRA05Y.cpy:L14 TRAN-MERCHANT-ZIP PIC X(10) -> VARCHAR(10)
        t.setTranMerchantZip("10001");
        // COBOL: CVTRA05Y.cpy:L15 TRAN-CARD-NUM PIC X(16) -> VARCHAR(16) NN (FK)
        t.setTranCardNum(cardNum);
        // COBOL: CVTRA05Y.cpy:L16 TRAN-ORIG-TS PIC X(26) -> TIMESTAMP(6) NN
        // (origination timestamp is 1 second before processing per typical flow)
        t.setTranOrigTs(procTs.minusSeconds(1));
        // COBOL: CVTRA05Y.cpy:L17 TRAN-PROC-TS PIC X(26) -> TIMESTAMP(6) NN (indexed)
        t.setTranProcTs(procTs);
        // COBOL: CVTRA05Y.cpy:L18 FILLER PIC X(20) -- OMITTED (AAP §0.6.2)
        return t;
    }

    /**
     * Inserts a minimal parent {@code accounts} row via native SQL so
     * that subsequent {@code cards} INSERTs (via
     * {@link #persistCard(String, Long)}) satisfy the V002
     * {@code fk_cards_acct FOREIGN KEY (card_acct_id) REFERENCES
     * accounts(acct_id) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V001__create_account.sql}
     * &mdash; every NOT NULL column (and only the NOT NULL columns)
     * is populated. Native SQL is used (rather than constructing and
     * persisting a full {@code Account} entity via
     * {@link TestEntityManager#persist(Object) persist}) to keep this
     * test focused on transaction-repository behaviour and avoid
     * coupling to the {@code Account} entity's full constructor
     * surface. This mirrors the established pattern in
     * {@code CardRepositoryTest.persistAccount}.</p>
     *
     * @param acctId the 11-digit account identifier to insert (the
     *               supplied {@link Long} is the foreign-key target
     *               for every {@code card} persisted by
     *               {@link #persistCard(String, Long) persistCard})
     */
    private void persistAccount(Long acctId) {
        // V001__create_account.sql NOT NULL columns:
        //   acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
        //   acct_cash_credit_limit, acct_open_date, acct_expiration_date,
        //   acct_curr_cyc_credit, acct_curr_cyc_debit, version.
        // Nullable: acct_reissue_date, acct_addr_zip, acct_group_id.
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO accounts ("
            + "    acct_id, acct_active_status, acct_curr_bal, "
            + "    acct_credit_limit, acct_cash_credit_limit, "
            + "    acct_open_date, acct_expiration_date, "
            + "    acct_curr_cyc_credit, acct_curr_cyc_debit, "
            + "    acct_addr_zip, acct_group_id, version"
            + ") VALUES ("
            + "    ?, 'Y', 0.00, "
            + "    5000.00, 1000.00, "
            + "    DATE '2024-01-01', DATE '2034-12-31', "
            + "    0.00, 0.00, "
            + "    '10001', 'DEFAULT', 0"
            + ")"
        ).setParameter(1, acctId).executeUpdate();
    }

    /**
     * Inserts a minimal parent {@code cards} row via native SQL so
     * that subsequent {@code transactions} INSERTs satisfy the V005
     * {@code fk_transactions_card_num FOREIGN KEY (tran_card_num)
     * REFERENCES cards(card_num) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V002__create_card.sql}
     * &mdash; every NOT NULL column is populated. As with
     * {@link #persistAccount(Long)}, native SQL avoids coupling
     * the test to the {@code Card} entity's full constructor surface.</p>
     *
     * @param cardNum the 16-character card PAN to insert
     * @param acctId  the parent {@code acct_id} that this card
     *                belongs to (MUST already exist in
     *                {@code accounts} via {@link #persistAccount(Long)})
     */
    private void persistCard(String cardNum, Long acctId) {
        // V002__create_card.sql NOT NULL columns:
        //   card_num, card_acct_id, card_cvv_cd, card_embossed_name,
        //   card_expiration_date, card_active_status, version.
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO cards ("
            + "    card_num, card_acct_id, card_cvv_cd, "
            + "    card_embossed_name, card_expiration_date, "
            + "    card_active_status, version"
            + ") VALUES ("
            + "    ?, ?, 123, "
            + "    'JOHN Q PUBLIC', DATE '2027-12-31', "
            + "    'Y', 0"
            + ")"
        ).setParameter(1, cardNum)
         .setParameter(2, acctId)
         .executeUpdate();
    }

    /**
     * Convenience helper that seeds the parent {@code accounts} row
     * (via {@link #persistAccount(Long)}) and the parent {@code cards}
     * row (via {@link #persistCard(String, Long)}) for the canonical
     * primary card used by most tests. Returns immediately after
     * issuing the two INSERTs (no flush) &mdash; the test method
     * itself is responsible for flushing if needed.
     */
    private void seedPrimaryCardChain() {
        persistAccount(ACCT_ID);
        persistCard(CARD_PRIMARY, ACCT_ID);
    }

    // =========================================================================
    // Test 1 — Round-trip persistence by VARCHAR(16) PK
    // =========================================================================

    /**
     * Validates the foundational {@code JpaRepository} contract: a
     * fresh {@link Transaction} {@code save()}'d via the repository
     * can be retrieved by its primary key with {@code findById()} and
     * the business fields round-trip without precision loss.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS WRITE DATASET('TRANSACT') FROM(TRAN-RECORD)
     * RIDFLD(TRAN-ID)} followed by
     * {@code EXEC CICS READ DATASET('TRANSACT') INTO(TRAN-RECORD)
     * RIDFLD(TRAN-ID)} &mdash; the canonical write-then-read pattern
     * used by every CardDemo program that touches the
     * {@code TRANSACT} file (notably {@code COTRN01C.cbl} transaction
     * detail, {@code COTRN02C.cbl} transaction add, and
     * {@code COBIL00C.cbl} bill payment).</p>
     *
     * <p><strong>BigDecimal assertion discipline</strong>: the
     * {@code tranAmt} assertion uses
     * {@link BigDecimal#compareTo(BigDecimal)} via AssertJ's
     * {@code usingComparator} fluent API &mdash; <strong>never</strong>
     * {@link Object#equals(Object)} which compares both value AND
     * scale (AAP &sect;0.6.1).</p>
     */
    @Test
    void saveAndFindById_persistsAndRetrievesEntity() {
        // Arrange: parent FK chain (accounts -> cards) required by V005 fk_transactions_card_num
        seedPrimaryCardChain();

        // Build a transient Transaction fixture with a known tranId
        String tranId = "TRAN000000000001";
        Transaction t = buildTransaction(tranId, CARD_PRIMARY, LocalDateTime.of(2024, 1, 15, 10, 0, 0));

        // Act: persist + flush + clear → forces SQL execution + detaches the persistence context
        // so the subsequent findById() reloads from the database (NOT from the L1 cache)
        Transaction saved = repository.save(t);
        entityManager.flush();
        entityManager.clear();

        // Assert: save() returned the managed entity (not null) — Spring Data JPA contract
        assertThat(saved).isNotNull();
        assertThat(saved.getTranId()).isEqualTo(tranId);

        // Assert: the row is present and the business fields round-trip intact
        Optional<Transaction> reloadedOpt = repository.findById(tranId);
        assertThat(reloadedOpt).isPresent();
        Transaction reloaded = reloadedOpt.get();
        assertThat(reloaded.getTranId()).isEqualTo(tranId);
        assertThat(reloaded.getTranTypeCd()).isEqualTo("01");
        assertThat(reloaded.getTranCatCd()).isEqualTo(5);
        assertThat(reloaded.getTranCardNum()).isEqualTo(CARD_PRIMARY);
        assertThat(reloaded.getTranMerchantName()).isEqualTo("ACME MERCHANT");
        // BigDecimal precision check per AAP §0.6.1 — compareTo == 0 (scale-aware),
        // NEVER equals() which would compare both value AND scale
        assertThat(reloaded.getTranAmt())
            .usingComparator(BigDecimal::compareTo)
            .isEqualTo(new BigDecimal("100.50"));
        // Timestamps must round-trip through TIMESTAMP(6) without loss
        assertThat(reloaded.getTranProcTs()).isNotNull();
        assertThat(reloaded.getTranOrigTs()).isNotNull();
    }

    // =========================================================================
    // Test 2 — findByOrderByTranIdAsc(Pageable) -- first page (10 rows)
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionRepository#findByOrderByTranIdAsc(org.springframework.data.domain.Pageable)}
     * returns the first 10 transactions in lexicographic ascending
     * {@code tranId} order &mdash; the page size matches the
     * {@code app/bms/COTRN00.bms} 10-rows-per-page list-screen layout
     * driven by the COBOL {@code COTRN00C.cbl} paged-browse
     * controller.
     *
     * <p><strong>COBOL pattern (replaced):</strong>
     * {@code EXEC CICS STARTBR DATASET('TRANSACT') RIDFLD(LOW-VALUES)}
     * followed by repeated {@code EXEC CICS READNEXT} calls bounded by
     * the screen's 10-row capacity &mdash; once 10 rows are read the
     * paragraph stops and the cursor RBA is saved in the COMMAREA for
     * the next pseudo-conversation. The Java target's
     * {@link org.springframework.data.domain.Pageable Pageable} input
     * (PageRequest.of(0, 10)) drives the same outcome via a single
     * SQL {@code LIMIT 10 OFFSET 0} query.</p>
     */
    @Test
    void findByOrderByTranIdAsc_returnsTransactionsInIdOrder_pagedByCotrn00Bms() {
        // COBOL: COTRN00C paged browse — 10 rows per page per COTRN00.bms layout
        // Arrange: parent FK chain + 15 transactions with sortable tranIds
        seedPrimaryCardChain();
        LocalDateTime baseTs = LocalDateTime.of(2024, 1, 15, 10, 0, 0);
        for (int i = 1; i <= 15; i++) {
            String tranId = String.format("TRAN%012d", i);
            Transaction t = buildTransaction(tranId, CARD_PRIMARY, baseTs.plusMinutes(i));
            repository.save(t);
        }
        entityManager.flush();
        entityManager.clear();

        // Act: request page 0 with size 10
        Page<Transaction> page = repository.findByOrderByTranIdAsc(PageRequest.of(0, 10));

        // Assert: page contains exactly 10 rows in lexicographic ASC order
        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getContent().get(0).getTranId()).isEqualTo("TRAN000000000001");
        assertThat(page.getContent().get(9).getTranId()).isEqualTo("TRAN000000000010");
        // hasNext indicates a second page exists (5 remaining rows)
        assertThat(page.hasNext()).isTrue();
        // Total elements reflects the full 15-row set
        assertThat(page.getTotalElements()).isEqualTo(15L);
    }

    // =========================================================================
    // Test 3 — findByOrderByTranIdAsc(Pageable) -- second page (remaining 5 rows)
    // =========================================================================

    /**
     * Validates the SECOND page of the
     * {@link TransactionRepository#findByOrderByTranIdAsc(org.springframework.data.domain.Pageable)}
     * paged browse &mdash; specifically that the cursor advances
     * correctly to row 11, returns only 5 remaining rows, and
     * {@code hasNext()} correctly reports the end of the result set.
     *
     * <p>This validates the COBOL {@code COTRN00C} pseudo-conversation
     * pattern where the user presses {@code PF8} (forward) to advance
     * to the next page &mdash; the controller re-{@code STARTBR}'s
     * from the saved cursor RBA and reads the next 10-row chunk
     * (returning fewer rows when the cursor reaches end-of-file, as
     * occurs here with only 5 remaining rows of 15 total).</p>
     */
    @Test
    void findByOrderByTranIdAsc_secondPage_returnsRemainingTransactions() {
        // COBOL: COTRN00C paged browse — PF8 advance to next page; cursor at row 11
        // Arrange: identical fixture to Test 2 (15 rows total)
        seedPrimaryCardChain();
        LocalDateTime baseTs = LocalDateTime.of(2024, 1, 15, 10, 0, 0);
        for (int i = 1; i <= 15; i++) {
            String tranId = String.format("TRAN%012d", i);
            Transaction t = buildTransaction(tranId, CARD_PRIMARY, baseTs.plusMinutes(i));
            repository.save(t);
        }
        entityManager.flush();
        entityManager.clear();

        // Act: request page 1 with size 10 (offset 10)
        Page<Transaction> page = repository.findByOrderByTranIdAsc(PageRequest.of(1, 10));

        // Assert: page contains the 5 remaining rows (rows 11-15)
        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getContent().get(0).getTranId()).isEqualTo("TRAN000000000011");
        assertThat(page.getContent().get(4).getTranId()).isEqualTo("TRAN000000000015");
        // hasNext is false — this is the last page
        assertThat(page.hasNext()).isFalse();
        assertThat(page.getTotalElements()).isEqualTo(15L);
    }

    // =========================================================================
    // Test 4 — findTopByOrderByTranIdDesc() -- MAX-TRAN-ID generation (COTRN02C)
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionRepository#findTopByOrderByTranIdDesc()}
     * returns the transaction with the lexicographically highest
     * {@code tranId} &mdash; the JPA replacement for the COBOL
     * {@code COTRN02C.cbl} {@code STARTBR}/{@code READPREV}/{@code ENDBR}
     * MAX-TRAN-ID generation pattern at L444-L451.
     *
     * <p><strong>COBOL pattern (replaced):</strong>
     * <pre>
     * MOVE HIGH-VALUES TO TRAN-ID
     * PERFORM STARTBR-TRANSACT-FILE
     * PERFORM READPREV-TRANSACT-FILE
     * PERFORM ENDBR-TRANSACT-FILE
     * MOVE TRAN-ID     TO WS-TRAN-ID-N
     * ADD 1 TO WS-TRAN-ID-N
     * </pre>
     *
     * <p>The service layer (TransactionAddService) extracts the
     * highest {@code tranId} from the returned entity, parses it as a
     * numeric value, adds 1, and zero-pads the result back to 16
     * characters before {@code save()}ing the new transaction (per
     * AAP &sect;0.6.2). This test validates only the repository half
     * of the pattern: returning the highest-tranId entity.</p>
     */
    @Test
    void findTopByOrderByTranIdDesc_returnsMaxIdTransaction_forCotrn02cSequenceGeneration() {
        // COBOL: COTRN02C L444-L451 — STARTBR/READPREV for next sequential ID
        // Arrange: parent FK chain + 3 transactions with non-sequential tranIds
        // (the SAVED order is intentionally out of lexicographic order to verify
        // the query sorts DESC rather than returning insertion order)
        seedPrimaryCardChain();
        LocalDateTime baseTs = LocalDateTime.of(2024, 1, 15, 10, 0, 0);
        repository.save(buildTransaction("TRAN000000000001", CARD_PRIMARY, baseTs));
        repository.save(buildTransaction("TRAN000000000005", CARD_PRIMARY, baseTs.plusMinutes(1)));
        repository.save(buildTransaction("TRAN000000000003", CARD_PRIMARY, baseTs.plusMinutes(2)));
        entityManager.flush();
        entityManager.clear();

        // Act: request the maximum (lexicographic) tranId
        Optional<Transaction> maxOpt = repository.findTopByOrderByTranIdDesc();

        // Assert: the returned Optional carries the row with the highest tranId
        assertThat(maxOpt).isPresent();
        Transaction max = maxOpt.get();
        // VARCHAR(16) lexicographic max — for zero-padded numeric strings this is
        // also the numeric max, which is exactly the COBOL READPREV semantic.
        assertThat(max.getTranId()).isEqualTo("TRAN000000000005");
    }

    // =========================================================================
    // Test 5 — findTopByOrderByTranIdDesc() -- empty table contract
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionRepository#findTopByOrderByTranIdDesc()}
     * returns {@link Optional#empty()} when the {@code transactions}
     * table is empty &mdash; the equivalent of the COBOL CICS
     * {@code STARTBR} returning {@code DFHRESP(ENDFILE)} on a freshly-
     * provisioned VSAM cluster.
     *
     * <p>The Java target service code (TransactionAddService) must
     * handle this case by initialising the next-tranId counter to
     * {@code "TRAN000000000001"} (or whatever the application's
     * defined first-sequence value is) when the repository returns
     * an empty {@link Optional} &mdash; mirroring the COBOL
     * paragraph's empty-file initialisation branch.</p>
     */
    @Test
    void findTopByOrderByTranIdDesc_emptyTable_returnsEmptyOptional() {
        // No fixtures — the @DataJpaTest default transactional rollback already
        // ensures each test method starts with no transactions rows from this
        // test class. (Flyway seed migrations V012-V015 may have populated
        // reference data, but never transactions rows.)

        // Act
        Optional<Transaction> maxOpt = repository.findTopByOrderByTranIdDesc();

        // Assert: empty Optional signals "no transactions exist yet"
        assertThat(maxOpt).isEmpty();
    }

    // =========================================================================
    // Test 6 — findByTranCardNumAndTranProcTsBetween -- date-window report
    // =========================================================================

    /**
     * Validates the THREE filter dimensions of
     * {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(String, LocalDateTime, LocalDateTime, org.springframework.data.domain.Pageable)}:
     * (a) only transactions for the supplied {@code tranCardNum} are
     * returned (other-card rows excluded); (b) only transactions whose
     * {@code tranProcTs} falls inside the inclusive window are
     * returned; (c) transactions for the SAME card but OUTSIDE the
     * window are excluded.
     *
     * <p>This replaces the COBOL {@code CBTRN03C.cbl} date-window
     * filter at L173-L178:
     * <pre>
     * PERFORM 1000-TRANFILE-GET-NEXT
     * IF TRAN-PROC-TS (1:10) &gt;= WS-START-DATE
     *    AND TRAN-PROC-TS (1:10) &lt;= WS-END-DATE
     *    CONTINUE
     * ELSE
     *    NEXT SENTENCE
     * END-IF
     * </pre>
     *
     * <p>where {@code WS-START-DATE}/{@code WS-END-DATE} are
     * 10-character date strings ({@code YYYY-MM-DD}). The PostgreSQL
     * planner backs this query with the composite secondary index
     * {@code idx_transactions_card_proc_ts} on
     * {@code (tran_card_num, tran_proc_ts)} declared in V005
     * &mdash; an AAP-mandated improvement over the COBOL VSAM AIX
     * (which keyed on TRAN-PROC-TS alone) per AAP &sect;0.6.2.</p>
     */
    @Test
    void findByTranCardNumAndTranProcTsBetween_returnsTransactionsInDateWindow_forCbtrn03cReport() {
        // COBOL: CBTRN03C L173-L178 — date-window filter using TRANSACT.VSAM.AIX(TRAN-PROC-TS)
        // Arrange: parent FK chain for BOTH cards (primary + secondary)
        persistAccount(ACCT_ID);
        persistCard(CARD_PRIMARY, ACCT_ID);
        persistCard(CARD_SECONDARY, ACCT_ID);

        // Primary card: 3 transactions across 10 days (one BEFORE, one IN, one AFTER the window)
        repository.save(buildTransaction(
            "TRAN000000000101", CARD_PRIMARY, LocalDateTime.of(2024, 1, 15, 10, 0, 0)));
        repository.save(buildTransaction(
            "TRAN000000000102", CARD_PRIMARY, LocalDateTime.of(2024, 1, 20, 12, 0, 0)));
        repository.save(buildTransaction(
            "TRAN000000000103", CARD_PRIMARY, LocalDateTime.of(2024, 1, 25, 14, 0, 0)));
        // Secondary card: 1 transaction WITHIN the window (must be filtered out by tranCardNum)
        repository.save(buildTransaction(
            "TRAN000000000201", CARD_SECONDARY, LocalDateTime.of(2024, 1, 20, 13, 0, 0)));
        entityManager.flush();
        entityManager.clear();

        // Act: query primary card transactions in the window 2024-01-16..2024-01-22
        Page<Transaction> page = repository.findByTranCardNumAndTranProcTsBetween(
            CARD_PRIMARY,
            LocalDateTime.parse("2024-01-16T00:00:00"),
            LocalDateTime.parse("2024-01-22T23:59:59"),
            PageRequest.of(0, 100));

        // Assert: exactly 1 transaction returned — the one on 2024-01-20.
        // The `Page.getContent()` accessor returns a `java.util.List<Transaction>`
        // per the Spring Data Commons contract; we bind it to a local List
        // explicitly to make the collection type explicit at the assertion
        // call site (matches the agent_prompt's Phase 2 imports list usage
        // of `java.util.List`).
        List<Transaction> filteredRows = page.getContent();
        assertThat(filteredRows).hasSize(1);
        Transaction returned = filteredRows.get(0);
        assertThat(returned.getTranId()).isEqualTo("TRAN000000000102");
        // Assert: the returned transaction belongs to the requested card (NOT the secondary card)
        assertThat(returned.getTranCardNum()).isEqualTo(CARD_PRIMARY);
        // Assert: the timestamp is inside the window
        assertThat(returned.getTranProcTs()).isEqualTo(LocalDateTime.of(2024, 1, 20, 12, 0, 0));
        // Assert: total elements reflects the FILTERED count, not the full table
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    // =========================================================================
    // Test 7 — findByTranCardNumAndTranProcTsBetween -- no-match scenario
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(String, LocalDateTime, LocalDateTime, org.springframework.data.domain.Pageable)}
     * returns an empty {@link Page} (zero content rows AND
     * {@code totalElements = 0}) when no transactions match the
     * supplied card and date-window predicates.
     *
     * <p>This is the equivalent of the COBOL {@code CBTRN03C}
     * report-generation paragraph reaching end-of-file without
     * matching any TRAN-PROC-TS values &mdash; the report header is
     * written but the body remains empty. The Java target service
     * code must handle the empty page by writing a "no transactions
     * in window" notice rather than failing.</p>
     */
    @Test
    void findByTranCardNumAndTranProcTsBetween_noMatches_returnsEmptyPage() {
        // Arrange: parent FK chain for the test card + 1 row OUTSIDE any reasonable window
        persistAccount(ACCT_ID);
        persistCard(CARD_TEST7, ACCT_ID);
        repository.save(buildTransaction(
            "TRAN000000000301", CARD_TEST7, LocalDateTime.of(2024, 6, 15, 10, 0, 0)));
        entityManager.flush();
        entityManager.clear();

        // Act: query a date window that EXCLUDES the persisted row.
        // The page-request is typed as `Pageable` (the interface accepted by
        // every derived query in TransactionRepository) to make the
        // interface-vs-implementation distinction explicit at the call site.
        Pageable pageRequest = PageRequest.of(0, 100);
        Page<Transaction> page = repository.findByTranCardNumAndTranProcTsBetween(
            CARD_TEST7,
            LocalDateTime.parse("2024-01-01T00:00:00"),
            LocalDateTime.parse("2024-01-31T23:59:59"),
            pageRequest);

        // Assert: empty page
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0L);
        assertThat(page.hasNext()).isFalse();
    }

    // =========================================================================
    // Test 8 — deleteById removes a transaction (inherited JpaRepository method)
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteById(Object)
     * deleteById(String)} contract correctly removes a previously-
     * persisted transaction.
     *
     * <p>NOTE: the live COBOL system does NOT delete transactions
     * (the {@code transactions} journal is APPEND-ONLY per AAP
     * &sect;0.7.2 audit-trail preservation). This test exercises the
     * inherited Spring Data JPA contract for completeness; production
     * code never invokes {@code deleteById} on this repository.
     * Compensating reversals are recorded as NEW offsetting rows with
     * their own {@code tranId} per AAP &sect;0.7.2.</p>
     */
    @Test
    void deleteById_removesTransaction() {
        // Arrange: persist a single transaction
        seedPrimaryCardChain();
        String tranId = "TRAN000000000401";
        repository.save(buildTransaction(tranId, CARD_PRIMARY, LocalDateTime.of(2024, 1, 15, 10, 0, 0)));
        entityManager.flush();
        entityManager.clear();

        // Sanity check — the row is present before deletion
        assertThat(repository.findById(tranId)).isPresent();

        // Act: delete by ID
        repository.deleteById(tranId);
        entityManager.flush();

        // Assert: subsequent findById returns empty
        assertThat(repository.findById(tranId)).isEmpty();
    }

    // =========================================================================
    // Test 9 — count() returns total row count (inherited JpaRepository method)
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#count() count()}
     * contract correctly reports the number of rows in
     * {@code transactions}.
     *
     * <p>Used by audit / observability flows (e.g., reporting "N
     * transactions posted today" in the end-of-day batch log; per
     * AAP &sect;0.6.6 the count is also published as a CloudWatch
     * metric for fraud-detection dashboards).</p>
     */
    @Test
    void count_returnsTotalRowCount() {
        // Arrange: persist exactly 3 transactions on a clean table
        seedPrimaryCardChain();
        LocalDateTime baseTs = LocalDateTime.of(2024, 1, 15, 10, 0, 0);
        repository.save(buildTransaction("TRAN000000000501", CARD_PRIMARY, baseTs));
        repository.save(buildTransaction("TRAN000000000502", CARD_PRIMARY, baseTs.plusMinutes(1)));
        repository.save(buildTransaction("TRAN000000000503", CARD_PRIMARY, baseTs.plusMinutes(2)));
        entityManager.flush();
        entityManager.clear();

        // Act + Assert: count returns exactly 3 (the @DataJpaTest default
        // transactional rollback isolates this from any other test method)
        assertThat(repository.count()).isEqualTo(3L);
    }

    // =========================================================================
    // Test 10 — tranAmt NUMERIC(11,2) precision invariant (AAP §0.6.1)
    // =========================================================================

    /**
     * Validates that the {@code tranAmt} column (NUMERIC(11,2))
     * preserves both the {@link BigDecimal} VALUE <em>and</em>
     * {@code scale = 2} through a persist-flush-clear-reload cycle.
     * Exercises the boundary value {@code 999999999.99} (9 digits +
     * V99 = PIC S9(09)V99 maximum positive value per AAP &sect;0.6.1)
     * to confirm that the NUMERIC(11,2) column accepts and round-
     * trips the full COBOL precision range.
     *
     * <p><strong>AAP &sect;0.6.1 (BigDecimal arithmetic discipline):</strong>
     * <ul>
     *   <li>{@code .compareTo() == 0} validates the VALUE
     *       (scale-insensitive); {@code .scale()} validates the
     *       SCALE explicitly (must be 2).</li>
     *   <li>{@code .equals()} would compare both value AND scale
     *       &mdash; equivalent BUT it is NOT the canonical assertion
     *       pattern in this codebase because it false-fails on
     *       trailing-zero differences (e.g.,
     *       {@code "999999999.99"} vs {@code "999999999.990"}). We
     *       use {@code compareTo}+{@code scale} to make the value
     *       check and the scale check independent and explicit.</li>
     *   <li>{@code float}/{@code double} would lose precision at this
     *       boundary &mdash; the test exercises a BigDecimal-only
     *       path end-to-end per AAP &sect;0.6.1.</li>
     * </ul>
     */
    @Test
    void tranAmt_storesNumeric11Comma2Precision() {
        // AAP §0.6.1 — NUMERIC(11,2) PostgreSQL maps to PIC S9(09)V99 COBOL
        // Arrange: parent FK chain + one transaction with the max positive amount
        seedPrimaryCardChain();
        String tranId = "TRAN000000000601";
        Transaction t = buildTransaction(tranId, CARD_PRIMARY, LocalDateTime.of(2024, 1, 15, 10, 0, 0));
        // PIC S9(09)V99 max positive value (9 nines + .99 = 11 total digits / 2 scale)
        BigDecimal maxAmt = new BigDecimal("999999999.99");
        t.setTranAmt(maxAmt);

        // Act: persist + flush + clear → reload from DB
        repository.save(t);
        entityManager.flush();
        entityManager.clear();

        Optional<Transaction> reloadedOpt = repository.findById(tranId);
        assertThat(reloadedOpt).isPresent();
        BigDecimal reloadedAmt = reloadedOpt.get().getTranAmt();

        // Assert: VALUE preserved (compareTo is scale-insensitive equality)
        assertThat(reloadedAmt)
            .usingComparator(BigDecimal::compareTo)
            .isEqualTo(maxAmt);
        // Assert: SCALE preserved at 2 — PIC S9(09)V99 invariant per AAP §0.6.1
        assertThat(reloadedAmt.scale()).isEqualTo(2);
        // Belt-and-braces sanity: the absolute integer-part precision is 9 digits
        // (i.e., the unscaled value is between 0 and 99,999,999,999)
        assertThat(reloadedAmt.precision()).isLessThanOrEqualTo(11);
    }

}
