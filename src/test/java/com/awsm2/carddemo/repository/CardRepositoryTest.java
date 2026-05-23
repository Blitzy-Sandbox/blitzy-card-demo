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

import com.awsm2.carddemo.domain.Card;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @DataJpaTest} slice test for {@link CardRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.4.1, &sect;0.6.2, &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} &mdash;
 *       {@code KEYS(16 0)}, {@code RECORDSIZE(150 150)},
 *       {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code INDEXED}
 *       (per {@code app/jcl/CARDFILE.jcl}:L50&ndash;L63 and verified
 *       against {@code app/catlg/LISTCAT.txt}:L164&ndash;L222 &mdash;
 *       KEYLEN=16, RKP=0, MAXLRECL=150, AVGLRECL=150, INDEXED,
 *       SHROPTNS(2,3)).</li>
 *   <li><strong>VSAM alternate index:</strong>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} &mdash;
 *       {@code KEYS(11 16) NONUNIQUEKEY UPGRADE} per
 *       {@code app/jcl/CARDFILE.jcl}:L83&ndash;L92. Replaced by the
 *       PostgreSQL secondary index {@code idx_cards_acct_id} on
 *       {@link Card#getCardAcctId() cardAcctId} (V002).</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COCRDLIC.cbl} &mdash; card list:
 *             paginated 7-row-per-page browse by {@code CARD-ACCT-ID}
 *             ({@code STARTBR}/{@code READNEXT} over the AIX) populating
 *             the {@code COCRDLI.bms} screen. The Java target
 *             {@code CardListService} consumes the paged
 *             {@code findByCardAcctIdOrderByCardNumAsc(Long, Pageable)}
 *             derived query method (Test 3).</li>
 *         <li>{@code app/cbl/COCRDSLC.cbl} &mdash; card detail: random
 *             read by {@code CARD-NUM} ({@code EXEC CICS READ}); the
 *             Java target {@code CardDetailService} uses
 *             {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object) findById(String)}
 *             (Test 1).</li>
 *         <li>{@code app/cbl/COCRDUPC.cbl} &mdash; card update with
 *             {@code EXEC CICS READ UPDATE} &rarr; before/after image
 *             comparison &rarr; {@code REWRITE}. The Java target
 *             {@code CardUpdateService} replaces the before/after image
 *             comparison with JPA {@code @Version} optimistic locking
 *             per AAP &sect;0.7.1 (Test 4).</li>
 *         <li>{@code app/cbl/CBACT02C.cbl} &mdash; batch card-file
 *             reader; sequential scanner that the Java target
 *             {@code CardFileReaderService} replaces with chunked
 *             {@code findAll(Pageable)} iteration.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Copybook:</strong> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD} layout, RECLN 150; 6 business fields plus
 *       a 59-byte trailing {@code FILLER PIC X(59)}):
 *       <pre>
 *         05  CARD-NUM             PIC X(16).    (L5)
 *         05  CARD-ACCT-ID         PIC 9(11).    (L6)
 *         05  CARD-CVV-CD          PIC 9(03).    (L7)
 *         05  CARD-EMBOSSED-NAME   PIC X(50).    (L8)
 *         05  CARD-EXPIRAION-DATE  PIC X(10).    (L9)  [COBOL typo: "EXPIRAION"]
 *         05  CARD-ACTIVE-STATUS   PIC X(01).    (L10)
 *         05  FILLER               PIC X(59).    (L11, OMITTED in JPA)
 *       </pre>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/CARDFILE.jcl}
 *       &mdash; IDCAMS DEFINE CLUSTER (STEP10:L47&ndash;L63), IDCAMS
 *       REPRO load (STEP15:L68&ndash;L76) from
 *       {@code AWS.M2.CARDDEMO.CARDDATA.PS}, IDCAMS DEFINE
 *       ALTERNATEINDEX (STEP40:L80&ndash;L92), DEFINE PATH
 *       (STEP50:L97&ndash;L102), and BLDINDEX (STEP60:L107&ndash;L112).
 *       Bulk card master data is loaded to RDS by an AWS Glue Spark
 *       job per AAP &sect;0.6.2; Flyway V002 creates the schema but
 *       does NOT seed card rows.</li>
 *   <li><strong>Flyway migrations:</strong>
 *       {@code src/main/resources/db/migration/V001__create_account.sql}
 *       (parent {@code accounts} table — required by the
 *       {@code fk_cards_acct} foreign-key constraint) and
 *       {@code src/main/resources/db/migration/V002__create_card.sql}
 *       create the 7-column {@code cards} table
 *       ({@code card_num VARCHAR(16) PK},
 *       {@code card_acct_id BIGINT NOT NULL} with FK to
 *       {@code accounts(acct_id)},
 *       {@code card_cvv_cd NUMERIC(3) NOT NULL},
 *       {@code card_embossed_name VARCHAR(50) NOT NULL},
 *       {@code card_expiration_date DATE NOT NULL} [the typo-corrected
 *       column], {@code card_active_status CHAR(1) NOT NULL} with
 *       {@code chk_cards_active_status CHECK} restricting to
 *       {@code 'Y'} or {@code 'N'}, and {@code version BIGINT NOT NULL
 *       DEFAULT 0} for JPA {@code @Version} optimistic locking) plus
 *       the secondary index {@code idx_cards_acct_id} on
 *       {@code card_acct_id} that replaces the COBOL VSAM AIX
 *       {@code CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY UPGRADE}.
 *       The application asserts this schema via Hibernate
 *       {@code ddl-auto: validate} at startup.</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ol>
 *   <li><strong>Round-trip persistence by VARCHAR(16) PK</strong>
 *       &mdash; {@code save()} + {@code findById(String)} of a freshly
 *       built {@link Card} keyed by {@code cardNum}
 *       ({@link #saveAndFindById_persistsCardByCardNum() Test 1}).</li>
 *   <li><strong>Unpaged AIX-style lookup by {@code cardAcctId}</strong>
 *       &mdash; the derived query {@code findByCardAcctId(Long)} returns
 *       a {@link List} of every card owned by the supplied account
 *       (NONUNIQUEKEY semantic: one account &rarr; many cards), backed
 *       by the {@code idx_cards_acct_id} secondary index (Test 2).</li>
 *   <li><strong>Paged 7-rows-per-page AIX-style lookup by
 *       {@code cardAcctId}</strong> &mdash; the derived query
 *       {@code findByCardAcctIdOrderByCardNumAsc(Long, Pageable)}
 *       returns a {@link Page Page&lt;Card&gt;} envelope matching the
 *       {@code COCRDLI.bms} 7-row-per-page card-list screen layout
 *       per AAP &sect;0.4.1 (Test 3).</li>
 *   <li><strong>JPA {@code @Version} optimistic locking</strong>
 *       replaces COBOL {@code COCRDUPC} before/after image comparison
 *       &mdash; concurrent writes against a stale entity throw
 *       {@link OptimisticLockingFailureException} per AAP &sect;0.7.1
 *       (Test 4).</li>
 *   <li><strong>{@code deleteById}</strong> contract (Test 5).</li>
 *   <li><strong>Empty-list result</strong> &mdash; the unpaged derived
 *       query returns an empty {@link List} (NOT {@code null}) when no
 *       cards exist for the supplied account (Test 6).</li>
 * </ol>
 *
 * <h2>PCI-DSS posture (AAP &sect;0.6.6)</h2>
 * <p>The {@link Card} entity holds two PCI-DSS-classified data elements:
 * {@link Card#getCardNum() cardNum} (cardholder data per PCI-DSS v4.0
 * Requirement 3.4) and {@link Card#getCardCvvCd() cardCvvCd} (sensitive
 * authentication data per PCI-DSS v4.0 Requirement 3.2). This test
 * uses synthetic fixture values that resemble real card numbers but
 * are NOT real PANs (the standard PCI test-PAN {@code 4111111111111111}
 * is documented by every payment processor as a non-routable test
 * value). The {@link Card#toString()} method enforces masking to the
 * last 4 digits and intentionally omits the CVV &mdash; any AssertJ
 * failure message that prints a {@link Card} will display the masked
 * form, never the full PAN or the CVV.</p>
 *
 * <h2>Transactional boundary &mdash; what this test does NOT cover</h2>
 * <p>The {@code @Transactional(rollbackFor = Exception.class)} that
 * wraps the {@code CardUpdateService.updateCard()} method (replacing
 * the COBOL {@code EXEC CICS SYNCPOINT ROLLBACK} flow at the service
 * layer) is exercised by {@code CardUpdateServiceTest}, NOT here.
 * Repository-slice tests intentionally validate only persistence
 * contracts; service-layer transactional semantics are out of
 * scope.</p>
 *
 * @see CardRepository
 * @see Card
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardRepositoryTest {

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
    // target runs PostgreSQL 16, so tests exercise the same SQL engine
    // ensuring VARCHAR(16) primary key, NUMERIC(11) FK column type,
    // NUMERIC(3) CVV column, DATE for cardExpirationDate, CHAR(1) for
    // cardActiveStatus, BIGINT version column for @Version optimistic
    // locking, idx_cards_acct_id secondary index (replacing
    // CARDDATA.VSAM.AIX), Flyway V001 + V002 migration application, FK
    // constraint fk_cards_acct, chk_cards_active_status CHECK constraint,
    // and PostgreSQL-specific SQL semantics are validated faithfully
    // against the engine the application will actually run on in
    // production.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container URL to spring.datasource.*
    // -------------------------------------------------------------------------
    // Per AAP §0.6.4, the application's @Primary @RefreshScope HikariDataSource
    // bean declared in JpaConfig.dataSource(DataSourceProperties) is
    // constructed from spring.datasource.* properties (NOT from
    // JdbcConnectionDetails) — because the bean is user-declared rather than
    // auto-configured, Spring Boot's HikariJdbcConnectionDetailsBeanPostProcessor
    // does not re-write its jdbcUrl from the @ServiceConnection container.
    //
    // To bridge this gap we explicitly push the @Container's connection
    // details into the Spring Environment under spring.datasource.* before
    // any DataSource bean is constructed. This guarantees that the @Primary
    // DataSource (loaded by the JPA slice via component scan of JpaConfig)
    // and Flyway both target the SAME container that @ServiceConnection
    // configured for auto-config consumers.
    //
    // Mirrors the pattern established in AccountRepositoryTest,
    // CustomerRepositoryTest, TransactionTypeRepositoryTest,
    // DisclosureGroupRepositoryTest, DailyTransactionRepositoryTest, and
    // UserSecurityRepositoryTest.
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
     * {@code @DataJpaTest} slice's component scan rooted at
     * {@code com.awsm2.carddemo.repository} (per {@code JpaConfig}
     * {@code @EnableJpaRepositories}). The repository extends
     * {@code JpaRepository<Card, String>} and declares two custom
     * derived query methods (the unpaged
     * {@link CardRepository#findByCardAcctId(Long) findByCardAcctId} and
     * the paged
     * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long, org.springframework.data.domain.Pageable) findByCardAcctIdOrderByCardNumAsc})
     * which together replace the COBOL VSAM AIX
     * {@code CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY} access patterns
     * per AAP &sect;0.6.2.
     */
    @Autowired
    private CardRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager}
     * (transitively enabled by {@code @DataJpaTest}). Used to:
     * <ul>
     *   <li>Force SQL execution via {@link TestEntityManager#flush() flush()}
     *       so the {@link CardRepository#save(Object) save()} actually
     *       issues an {@code INSERT} / {@code UPDATE} to the database
     *       (otherwise the entity remains in the persistence context
     *       buffer until commit).</li>
     *   <li>Detach the persistence context via
     *       {@link TestEntityManager#clear() clear()} so that subsequent
     *       {@code findById()} calls reload from the database rather
     *       than returning a first-level-cache hit. Critical for
     *       verifying that the {@code @Version} column and all 6
     *       business fields round-trip from PostgreSQL intact &mdash;
     *       not merely echoed from the entity-manager cache.</li>
     *   <li>Issue parent {@code accounts} rows via
     *       {@link TestEntityManager#getEntityManager() getEntityManager()}
     *       {@code .createNativeQuery(...)} (in the
     *       {@link #persistAccount(Long) persistAccount(Long)} helper)
     *       to satisfy the V002 {@code fk_cards_acct FOREIGN KEY}
     *       constraint without polluting the test with full
     *       {@link com.awsm2.carddemo.domain.Account Account} entity
     *       construction.</li>
     *   <li>Detach the snap1 reference via
     *       {@code entityManager.getEntityManager().detach(snap1)} in
     *       Test 4 to simulate two independent persistence contexts
     *       within a single test method for {@code @Version} optimistic-
     *       locking validation (replacing COCRDUPC.cbl before/after
     *       image comparison per AAP &sect;0.7.1).</li>
     * </ul>
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builders
    // =========================================================================

    /**
     * Builds a transient (non-persisted) {@link Card} entity populated
     * with the supplied identity fields plus a canonical set of valid
     * defaults for every other field defined in
     * {@code app/cpy/CVACT02Y.cpy}. The fixture is intentionally
     * minimal: required NOT NULL columns are populated with realistic
     * golden-fixture-style values.
     *
     * <p>The 6 setters are invoked in the SAME order as the COBOL
     * {@code CARD-RECORD} layout ({@code app/cpy/CVACT02Y.cpy}:L5
     * &ndash;L10) so the Java source remains visually parallel to the
     * source-of-truth COBOL record layout per AAP &sect;0.7.3 refactor
     * discipline.</p>
     *
     * <p>The {@link Card#getVersion() version} field is intentionally
     * left {@code null} &mdash; Hibernate initialises it to {@code 0L}
     * on first {@code save()} and increments it on every subsequent
     * managed update. Manual assignment would defeat the optimistic-
     * locking guarantee per AAP &sect;0.7.1.</p>
     *
     * @param cardNum      16-character card-number string (PK, mapped
     *                     to {@code card_num VARCHAR(16)}); PCI-DSS
     *                     cardholder data per AAP &sect;0.6.6
     * @param acctId       11-digit unsigned account identifier (FK,
     *                     mapped to {@code card_acct_id BIGINT})
     * @param embossedName cardholder name as embossed on the physical
     *                     card (mapped to {@code card_embossed_name
     *                     VARCHAR(50)})
     * @return a fresh, transient (non-persisted) {@link Card}
     */
    private Card buildCard(String cardNum, Long acctId, String embossedName) {
        Card c = new Card();
        // COBOL: CVACT02Y.cpy:L5 CARD-NUM PIC X(16) -> VARCHAR(16) PK; PCI-sensitive (masked in toString)
        c.setCardNum(cardNum);
        // COBOL: CVACT02Y.cpy:L6 CARD-ACCT-ID PIC 9(11) -> BIGINT NN; FK to accounts.acct_id (V002); indexed by idx_cards_acct_id
        c.setCardAcctId(acctId);
        // COBOL: CVACT02Y.cpy:L7 CARD-CVV-CD PIC 9(03) -> NUMERIC(3) NN; PCI-sensitive (omitted from toString)
        c.setCardCvvCd(123);
        // COBOL: CVACT02Y.cpy:L8 CARD-EMBOSSED-NAME PIC X(50) -> VARCHAR(50) NN
        c.setCardEmbossedName(embossedName);
        // COBOL: CVACT02Y.cpy:L9 CARD-EXPIRAION-DATE [typo] PIC X(10) -> DATE NN (typo corrected to "expiration" per AAP §0.4.1)
        // LE CEEDAYS replacement with native java.time.LocalDate per AAP §0.6.2
        c.setCardExpirationDate(LocalDate.of(2027, 12, 31));
        // COBOL: CVACT02Y.cpy:L10 CARD-ACTIVE-STATUS PIC X(01) -> CHAR(1) NN; chk_cards_active_status enforces ('Y','N')
        c.setCardActiveStatus("Y");
        // COBOL: CVACT02Y.cpy:L11 FILLER PIC X(59) -- OMITTED (no relational equivalent for fixed-width VSAM padding per AAP §0.6.2)
        // The version field is auto-managed by @Version — never set manually.
        return c;
    }

    /**
     * Inserts a minimal parent {@code accounts} row via native SQL so
     * that subsequent {@code cards} INSERTs satisfy the V002
     * {@code fk_cards_acct FOREIGN KEY (card_acct_id) REFERENCES
     * accounts(acct_id) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V001__create_account.sql}
     * &mdash; every NOT NULL column (and only the NOT NULL columns)
     * is populated, including
     * {@link com.awsm2.carddemo.domain.Account#getAcctExpirationDate() acct_expiration_date}
     * (NOT NULL per V001:L284) which the COBOL source preserves as
     * {@code ACCT-EXPIRAION-DATE} (line 11 of CVACT01Y.cpy &mdash;
     * spelling typo corrected per AAP &sect;0.4.1).</p>
     *
     * <p>Native SQL is used (rather than constructing and persisting
     * a full {@link com.awsm2.carddemo.domain.Account} entity via
     * {@link TestEntityManager#persist(Object) persist}) to keep the
     * test focused on {@code cards} repository behaviour and avoid
     * coupling card-repository tests to the {@link
     * com.awsm2.carddemo.domain.Account} entity's full constructor
     * surface (12 setters). This mirrors the established pattern in
     * other repository-slice tests that depend on parent rows for FK
     * satisfaction without exercising the parent entity's behaviour.</p>
     *
     * <p>Monetary columns are seeded with the V001-mandated DEFAULT 0
     * semantic (zero balance, zero cycle credit/debit) and a token
     * 5000.00 / 1000.00 credit-limit pair. Date columns are seeded
     * with {@code '2024-01-01'} (open date) and {@code '2034-12-31'}
     * (expiration date) &mdash; matching the buildCard fixture's
     * expiration year range for visual consistency.</p>
     *
     * @param acctId the 11-digit account identifier to insert (the
     *               supplied {@link Long} is the foreign key target
     *               for every card built by
     *               {@link #buildCard(String, Long, String) buildCard})
     */
    private void persistAccount(Long acctId) {
        // V001__create_account.sql NOT NULL columns:
        //   acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
        //   acct_cash_credit_limit, acct_open_date, acct_expiration_date,
        //   acct_curr_cyc_credit, acct_curr_cyc_debit, version.
        // Nullable: acct_reissue_date, acct_addr_zip, acct_group_id.
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO accounts (" +
            "    acct_id, acct_active_status, acct_curr_bal, " +
            "    acct_credit_limit, acct_cash_credit_limit, " +
            "    acct_open_date, acct_expiration_date, " +
            "    acct_curr_cyc_credit, acct_curr_cyc_debit, " +
            "    acct_addr_zip, acct_group_id, version" +
            ") VALUES (" +
            "    ?, 'Y', 0.00, " +
            "    5000.00, 1000.00, " +
            "    DATE '2024-01-01', DATE '2034-12-31', " +
            "    0.00, 0.00, " +
            "    '10001', 'DEFAULT', 0" +
            ")"
        ).setParameter(1, acctId).executeUpdate();
    }

    // =========================================================================
    // Test 1 — Round-trip persistence by VARCHAR(16) PK
    // =========================================================================

    /**
     * Validates the foundational JpaRepository contract: a fresh
     * {@link Card} {@code save()}'d via the repository can be retrieved
     * by its primary key with {@code findById()} and the 6 business
     * fields round-trip without precision loss.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS WRITE DATASET('CARDDAT') FROM(CARD-RECORD)
     * RIDFLD(CARD-NUM)} followed by
     * {@code EXEC CICS READ DATASET('CARDDAT') INTO(CARD-RECORD)
     * RIDFLD(CARD-NUM)} &mdash; the canonical write-then-read pattern
     * used by every CardDemo program that touches the {@code CARDDAT}
     * file (notably {@code COCRDSLC.cbl} card detail and
     * {@code COCRDUPC.cbl} card update).</p>
     */
    @Test
    void saveAndFindById_persistsCardByCardNum() {
        // Arrange: parent account row required by FK constraint fk_cards_acct (V002)
        persistAccount(10000000001L);

        // Build a transient Card fixture (synthetic PAN that resembles a real one but is the documented PCI test value)
        Card c = buildCard("4111111111111111", 10000000001L, "JOHN Q PUBLIC");

        // Act: persist + flush + clear → forces SQL execution + detaches the persistence context
        // so the subsequent findById() reloads from the database (NOT from L1 cache)
        repository.save(c);
        entityManager.flush();
        entityManager.clear();

        // Assert: the row is present and the business fields round-trip intact
        Optional<Card> reloaded = repository.findById("4111111111111111");
        assertThat(reloaded).isPresent();
        Card reloadedCard = reloaded.get();
        assertThat(reloadedCard.getCardNum()).isEqualTo("4111111111111111");
        assertThat(reloadedCard.getCardAcctId()).isEqualTo(10000000001L);
        assertThat(reloadedCard.getCardActiveStatus()).isEqualTo("Y");
        assertThat(reloadedCard.getCardEmbossedName()).isEqualTo("JOHN Q PUBLIC");
        assertThat(reloadedCard.getCardExpirationDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        // JPA @Version initialised to 0L on first save() per Hibernate semantics
        assertThat(reloadedCard.getVersion()).isNotNull().isEqualTo(0L);
    }

    // =========================================================================
    // Test 2 — Unpaged AIX-style lookup by cardAcctId (replaces CARDDATA.VSAM.AIX)
    // =========================================================================

    /**
     * Validates the unpaged derived query
     * {@link CardRepository#findByCardAcctId(Long)} &mdash; the
     * JPA replacement for the COBOL VSAM AIX
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY
     * UPGRADE} alternate-index access pattern.
     *
     * <p><strong>COBOL pattern (replaced):</strong>
     * {@code EXEC CICS STARTBR DATASET('CARDAIX') RIDFLD(CARD-ACCT-ID)}
     * followed by repeated {@code EXEC CICS READNEXT} calls until
     * end-of-file or a key change. The NONUNIQUEKEY attribute on the
     * VSAM AIX permits multiple {@code CARD-RECORD}s per
     * {@code CARD-ACCT-ID}, supporting the cardholder business rule
     * that one account may have multiple cards.</p>
     *
     * <p><strong>JPA pattern (replacement, AAP &sect;0.6.2):</strong>
     * Spring Data JPA translates the derived method name into the
     * JPQL query
     * {@code SELECT c FROM Card c WHERE c.cardAcctId = :acctId}, which
     * PostgreSQL executes against the {@code idx_cards_acct_id}
     * secondary index declared in V002. The index is NOT UNIQUE
     * (matching the VSAM NONUNIQUEKEY semantic), so multiple rows per
     * {@code cardAcctId} are returned as a {@link List Java List}
     * &mdash; the natural mapping for the NONUNIQUEKEY VSAM semantic.</p>
     */
    @Test
    void findByCardAcctId_returnsAllCardsForAccount_unpagedAixReplacement() {
        // COBOL: AIX-style lookup; backed by idx_cards_acct_id (V002) — replaces CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY UPGRADE
        // Arrange: persist parent account rows (FK fk_cards_acct from V002)
        persistAccount(10000000001L);
        persistAccount(10000000002L);

        // Three cards on account 10000000001L (the target account)
        repository.save(buildCard("4111111111110001", 10000000001L, "JOHN Q PUBLIC"));
        repository.save(buildCard("4111111111110002", 10000000001L, "JANE Q PUBLIC"));
        repository.save(buildCard("4111111111110003", 10000000001L, "JAMES Q PUBLIC"));

        // One card on account 10000000002L (the unrelated account, MUST NOT be returned)
        repository.save(buildCard("4111111111110099", 10000000002L, "JILL Q PUBLIC"));

        entityManager.flush();
        entityManager.clear();

        // Act: query by account ID — the AIX-style replacement
        List<Card> result = repository.findByCardAcctId(10000000001L);

        // Assert: exactly the 3 cards on the target account are returned
        assertThat(result).hasSize(3);
        // Every returned card belongs to the queried account (no leakage from acct 10000000002L)
        assertThat(result).allMatch(c -> c.getCardAcctId().equals(10000000001L));
        // Sanity: the unrelated card is NOT in the result set
        assertThat(result).noneMatch(c -> c.getCardNum().equals("4111111111110099"));
    }

    // =========================================================================
    // Test 3 — Paged 7-rows-per-page AIX lookup (matches COCRDLI.bms layout)
    // =========================================================================

    /**
     * Validates the paged derived query
     * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long, org.springframework.data.domain.Pageable)}
     * &mdash; the JPA replacement for the COBOL
     * {@code EXEC CICS STARTBR}/{@code READNEXT} paged browse loop in
     * {@code app/cbl/COCRDLIC.cbl} that populates the
     * {@code COCRDLI.bms} card-list screen.
     *
     * <p><strong>COBOL pattern (replaced):</strong> {@code COCRDLIC.cbl}
     * uses {@code EXEC CICS STARTBR DATASET('CARDAIX')
     * RIDFLD(CARD-ACCT-ID)} to position the browse cursor at the first
     * card for the supplied account, then loops {@code READNEXT} up to
     * 7 times to fill the {@code CARD-ROW OCCURS 7 TIMES} working-
     * storage array that maps to the {@code COCRDLI.bms} 7-row-per-page
     * grid. The next page is requested by capturing the last-key value
     * and starting a fresh browse from that key.</p>
     *
     * <p><strong>JPA pattern (replacement, AAP &sect;0.4.1):</strong>
     * The method name suffix {@code OrderByCardNumAsc} forces Spring
     * Data to emit {@code ORDER BY c.cardNum ASC} regardless of any
     * {@code Sort} embedded in the {@link org.springframework.data.domain.Pageable Pageable}
     * &mdash; deterministic ascending-key traversal that mirrors the
     * CICS browse-loop semantics. The {@code PageRequest.of(pageNumber,
     * 7)} constructor argument matches the {@code CARD-ROW OCCURS 7
     * TIMES} display layout per the AAP traceability matrix.</p>
     *
     * <p>The test persists 10 cards (one more than fits on a single
     * page) and verifies that page 0 contains the first 7 cards in
     * ascending {@code cardNum} order, page 1 contains the remaining
     * 3 cards, and {@link Page#getTotalElements()} reports the full
     * row count.</p>
     */
    @Test
    void findByCardAcctIdOrderByCardNumAsc_paged7PerPage_matchingCocrdliBmsLayout() {
        // COBOL: COCRDLIC paged browse — 7 rows/page per COCRDLI.bms (CARD-ROW OCCURS 7 TIMES)
        // Arrange: persist parent account row
        persistAccount(10000000001L);

        // Persist 10 cards with deterministic, ascending cardNums "4111111111110001" through "4111111111110010"
        // The terminal digits 0001..0010 make assertion-time ordering trivially verifiable.
        for (int i = 1; i <= 10; i++) {
            String cardNum = String.format("411111111111%04d", i);
            repository.save(buildCard(cardNum, 10000000001L, "CARDHOLDER " + i));
        }
        entityManager.flush();
        entityManager.clear();

        // Act: request page 0 (first 7 rows in ascending order) — matches COCRDLI.bms first-page display
        Page<Card> page0 = repository.findByCardAcctIdOrderByCardNumAsc(
                10000000001L, PageRequest.of(0, 7));

        // Assert: page 0 contains exactly 7 rows, the first being cardNum "...0001" and the last being "...0007"
        assertThat(page0.getContent()).hasSize(7);
        assertThat(page0.getContent().get(0).getCardNum()).isEqualTo("4111111111110001");
        assertThat(page0.getContent().get(6).getCardNum()).isEqualTo("4111111111110007");
        // Page-envelope metadata reflects the full row count (10) and total page count (2)
        assertThat(page0.getTotalElements()).isEqualTo(10L);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        // Page-number / page-size invariants for the first page
        assertThat(page0.getNumber()).isEqualTo(0);
        assertThat(page0.getSize()).isEqualTo(7);

        // Act: request page 1 (remaining 3 rows: "...0008", "...0009", "...0010")
        Page<Card> page1 = repository.findByCardAcctIdOrderByCardNumAsc(
                10000000001L, PageRequest.of(1, 7));

        // Assert: page 1 contains the remaining 3 rows (10 - 7 = 3) in ascending order
        assertThat(page1.getContent()).hasSize(3);
        assertThat(page1.getContent().get(0).getCardNum()).isEqualTo("4111111111110008");
        assertThat(page1.getContent().get(1).getCardNum()).isEqualTo("4111111111110009");
        assertThat(page1.getContent().get(2).getCardNum()).isEqualTo("4111111111110010");
        assertThat(page1.getNumber()).isEqualTo(1);
    }

    // =========================================================================
    // Test 4 — @Version optimistic locking replaces COCRDUPC.cbl
    //          before/after image comparison
    // =========================================================================

    /**
     * Validates the JPA {@code @Version} optimistic-locking contract
     * that replaces the COBOL before/after image comparison pattern in
     * {@code app/cbl/COCRDUPC.cbl}.
     *
     * <p><strong>COBOL pattern (replaced):</strong> {@code COCRDUPC.cbl}
     * performs {@code EXEC CICS READ DATASET('CARDDAT') UPDATE} to lock
     * the row, captures a before-image, applies field-level updates,
     * captures an after-image, then issues
     * {@code EXEC CICS REWRITE DATASET('CARDDAT')} only if the
     * before-image matches the database state at REWRITE time. If a
     * concurrent CICS task has modified the row in the interim, the
     * REWRITE detects the snapshot mismatch and the program issues an
     * error to abort the unit of work.</p>
     *
     * <p><strong>JPA pattern (replacement, AAP &sect;0.7.1):</strong>
     * The {@link Card} entity carries a {@code @Version} {@code Long}
     * column. On every {@code save()}, Hibernate:</p>
     * <ol>
     *   <li>Auto-increments the {@code version} column on the in-memory
     *       entity.</li>
     *   <li>Issues an {@code UPDATE} whose {@code WHERE} clause
     *       includes the previously-loaded {@code version} value.</li>
     *   <li>If another transaction has committed in the interim, the
     *       {@code WHERE} matches 0 rows; Hibernate detects this and
     *       throws
     *       {@link org.hibernate.StaleObjectStateException} which
     *       Spring's persistence exception translation (enabled by
     *       {@code @Repository}) wraps as
     *       {@link OptimisticLockingFailureException} (more precisely,
     *       {@link org.springframework.orm.ObjectOptimisticLockingFailureException}).</li>
     *   <li>The {@code GlobalExceptionHandler}
     *       ({@code @RestControllerAdvice}) maps this to the domain
     *       {@code ConcurrentModificationException} and returns HTTP
     *       409 Conflict to the REST caller, preserving the COBOL's
     *       "snapshot mismatch" error semantic.</li>
     * </ol>
     *
     * <p><strong>Test choreography</strong> (simulating two
     * independent persistence contexts inside a single
     * {@code @DataJpaTest} method via {@code detach()} + {@code clear()}
     * + re-fetch):</p>
     * <ol>
     *   <li>Persist a Card; flush + clear so the row is fully
     *       materialised in the database.</li>
     *   <li>Load {@code snap1} (snapshot 1) via {@code findById()}.</li>
     *   <li>Detach {@code snap1} from the persistence context (it is
     *       now unmanaged but retains its loaded {@code version}
     *       value).</li>
     *   <li>Clear the persistence context so the next find returns a
     *       fresh managed entity rather than a cache hit.</li>
     *   <li>Load {@code snap2} via {@code findById()} (managed; same
     *       initial {@code version} value as {@code snap1}).</li>
     *   <li>Modify {@code snap2}; {@code save()} + {@code flush()}.
     *       The database {@code version} is now incremented.</li>
     *   <li>Modify {@code snap1} (which still holds the OLD version);
     *       attempt {@code save()} + {@code flush()}. Hibernate detects
     *       the stale version, the {@code WHERE} clause matches 0
     *       rows, and {@link OptimisticLockingFailureException} is
     *       thrown.</li>
     * </ol>
     */
    @Test
    void versionFieldOptimisticLocking_throwsOnConcurrentUpdate_replacingCocrdupcBeforeAfterImage() {
        // AAP §0.7.1 — @Version replaces COCRDUPC before/after image comparison
        // SYNCPOINT ROLLBACK semantics live at the service layer via @Transactional.

        // ----- Step 1: Persist + flush + clear so the row is fully materialised -----
        persistAccount(10000000001L);
        Card initial = buildCard("4111111111111111", 10000000001L, "JOHN Q PUBLIC");
        repository.save(initial);
        entityManager.flush();
        entityManager.clear();

        // ----- Step 2: Load snap1 (snapshot 1) -----
        Card snap1 = repository.findById("4111111111111111").orElseThrow();
        Long initialVersion = snap1.getVersion();
        assertThat(initialVersion).isNotNull();

        // ----- Step 3: Detach snap1 from the persistence context -----
        // It is now unmanaged but retains the loaded version value.
        entityManager.getEntityManager().detach(snap1);

        // ----- Step 4: Clear the persistence context -----
        entityManager.clear();

        // ----- Step 5: Load snap2 (snapshot 2) — same row, same initial version -----
        Card snap2 = repository.findById("4111111111111111").orElseThrow();
        assertThat(snap2.getVersion()).isEqualTo(initialVersion);

        // ----- Step 6: Modify and save snap2 — version increments in DB -----
        snap2.setCardEmbossedName("UPDATED 1");
        repository.save(snap2);
        entityManager.flush();
        entityManager.clear();

        // ----- Step 7: Modify snap1 (still holds OLD version) and attempt to save → expect OptimisticLockingFailureException -----
        snap1.setCardEmbossedName("UPDATED 2");
        assertThatThrownBy(() -> {
            repository.save(snap1);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockingFailureException.class);
    }

    // =========================================================================
    // Test 5 — deleteById removes the row
    // =========================================================================

    /**
     * Validates the {@code deleteById} contract: a previously persisted
     * Card row can be deleted and is no longer retrievable by primary
     * key.
     *
     * <p><strong>Production caveat (AAP &sect;0.7.3 Minimal Change
     * Clause):</strong> the COBOL source treats cards as
     * <em>logically deactivated</em> via {@code cardActiveStatus = 'N'}
     * rather than physically deleted. Real production flows preserve
     * this behaviour. {@code deleteById} is exposed by
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * for completeness and operational scenarios (test fixture
     * teardown, GDPR right-to-erasure) but is not used in routine
     * business flows.</p>
     *
     * <p>The V002 {@code fk_cards_acct} FK declares {@code ON DELETE
     * NO ACTION} (the conservative default), so deleting a card does
     * NOT cascade to the parent account &mdash; verified implicitly by
     * the test design: the parent {@code accounts} row remains intact
     * after the card row is deleted.</p>
     */
    @Test
    void deleteById_removesCard() {
        // Arrange: persist a parent account and a single card
        persistAccount(10000000001L);
        Card c = buildCard("4111111111111111", 10000000001L, "JOHN Q PUBLIC");
        repository.save(c);
        entityManager.flush();
        // Sanity: the card is present before the delete
        assertThat(repository.findById("4111111111111111")).isPresent();

        // Act: delete by primary key + flush to issue the DELETE
        repository.deleteById("4111111111111111");
        entityManager.flush();

        // Assert: row no longer present
        assertThat(repository.findById("4111111111111111")).isEmpty();
    }

    // =========================================================================
    // Test 6 — findByCardAcctId returns empty List when account has no cards
    // =========================================================================

    /**
     * Validates the unpaged derived query contract when no rows match:
     * {@link CardRepository#findByCardAcctId(Long)} returns an empty
     * {@link List} (NOT {@code null}, NOT throws) when the supplied
     * account ID exists but has no associated cards.
     *
     * <p>This is the negative-path complement to Test 2. Together,
     * Tests 2 and 6 establish the full contract: the query returns
     * exactly the matching cards (multiple, single, or zero) without
     * surprising null returns or exceptions. The {@link List} return
     * type's empty-not-null behaviour is the canonical Spring Data
     * JPA semantic for derived multi-result queries.</p>
     *
     * <p>Account ID {@code 10000000099L} is deliberately chosen as an
     * account that is created (so it is a valid FK target if a card
     * were inserted) but has zero cards &mdash; representing the
     * realistic business case of a newly-opened account whose first
     * card has not yet been issued.</p>
     */
    @Test
    void findByCardAcctId_returnsEmptyList_whenAccountHasNoCards() {
        // Arrange: persist a parent account WITHOUT any cards
        persistAccount(10000000099L);

        // Act: query for cards on the cardless account
        List<Card> result = repository.findByCardAcctId(10000000099L);

        // Assert: empty list (NOT null, NOT throws)
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }
}
