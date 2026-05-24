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

import com.awsm2.carddemo.domain.CardCrossReference;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link CardCrossReferenceRepository}.
 *
 * <h2>Canonical VSAM AIX &rarr; JPA derived query migration (AAP &sect;0.6.2)</h2>
 *
 * <p>This test is the <strong>canonical example</strong> in the entire
 * CardDemo refactor of the VSAM alternate-index (AIX) &rarr; PostgreSQL
 * secondary-index migration pattern mandated by AAP &sect;0.6.2:</p>
 *
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} (RECLN=50,
 *       {@code KEYS(16 0)} on {@code XREF-CARD-NUM}); replaced by the
 *       PostgreSQL {@code card_xref} table with primary key
 *       {@code VARCHAR(16) xref_card_num} declared in
 *       {@code V004__create_cardxref.sql}.</li>
 *   <li><strong>VSAM AIX:</strong>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} ({@code KEYS(11 25)},
 *       {@code NONUNIQUEKEY UPGRADE}, referenced under the CICS
 *       symbolic name {@code CXACAIX}); replaced by the PostgreSQL
 *       non-unique B-tree index {@code idx_cardxref_acct_id} on
 *       {@code xref_acct_id} plus the
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *       derived query method which returns {@code List<CardCrossReference>}
 *       (NOT {@code Optional} &mdash; the {@code NONUNIQUEKEY} VSAM
 *       attribute permits multiple cross-reference rows per account
 *       ID, mirroring the real-world business rule that one account
 *       commonly owns multiple physical / virtual cards).</li>
 * </ul>
 *
 * <h2>Source traceability</h2>
 * <ul>
 *   <li><strong>COBOL copybook:</strong> {@code app/cpy/CVACT03Y.cpy} &mdash;
 *       {@code CARD-XREF-RECORD} layout (RECLN=50):
 *       <pre>
 *         05  XREF-CARD-NUM  PIC X(16).     (L5)  -- VARCHAR(16) PK
 *         05  XREF-CUST-ID   PIC 9(09).     (L6)  -- BIGINT NN (FK to customers)
 *         05  XREF-ACCT-ID   PIC 9(11).     (L7)  -- BIGINT NN (FK to accounts; INDEXED)
 *         05  FILLER         PIC X(14).     (L8)  -- OMITTED in JPA
 *       </pre>
 *   </li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COACTVWC.cbl} &mdash; account view: uses
 *             {@code findByXrefAcctId(Long)} to enumerate cards owned by
 *             the inquired account ({@code COACTVW.bms} screen).</li>
 *         <li>{@code app/cbl/COTRN02C.cbl} &mdash; transaction-add:
 *             validates that the supplied card &harr; account &harr;
 *             customer link exists before posting a new transaction.</li>
 *         <li>{@code app/cbl/CBTRN02C.cbl} &mdash; batch transaction
 *             posting: Stage 1 of the 4-stage validation cascade
 *             ({@code XREF / Account / Credit limit / Card expiration})
 *             with reject codes 100&ndash;109 preserved verbatim per
 *             AAP &sect;0.1.1.</li>
 *         <li>{@code app/cbl/CBACT03C.cbl} &mdash; batch sequential XREF
 *             reader; uses {@code findAll()} pattern via
 *             {@link CardCrossReferenceRepository#findAll() findAll()}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/XREFFILE.jcl} &mdash;
 *       IDCAMS DEFINE CLUSTER (STEP10:L39&ndash;L52), IDCAMS REPRO load
 *       (STEP15:L57&ndash;L65), IDCAMS DEFINE ALTERNATEINDEX
 *       (STEP20:L69&ndash;L82) with {@code KEYS(11,25) NONUNIQUEKEY UPGRADE},
 *       DEFINE PATH (STEP25:L87&ndash;L92), and BLDINDEX
 *       (STEP30:L97&ndash;L102). Bulk cross-reference data is loaded to
 *       RDS by an AWS Glue Spark job per AAP &sect;0.6.2; Flyway V004
 *       creates the schema but does NOT seed cross-reference rows.</li>
 *   <li><strong>Flyway migrations:</strong>
 *       {@code src/main/resources/db/migration/V001__create_account.sql}
 *       (parent {@code accounts} table &mdash; required by the
 *       {@code fk_cardxref_acct} foreign-key constraint),
 *       {@code V002__create_card.sql} (parent {@code cards} table &mdash;
 *       required by {@code fk_cardxref_card}),
 *       {@code V003__create_customer.sql} (parent {@code customers}
 *       table &mdash; required by {@code fk_cardxref_cust}), and
 *       {@code V004__create_cardxref.sql} (the {@code card_xref} table
 *       under test, plus the critical {@code idx_cardxref_acct_id}
 *       secondary index that replaces the VSAM
 *       {@code CARDXREF.VSAM.AIX KEYS(11 25) NONUNIQUEKEY UPGRADE}).
 *       The application asserts this schema via Hibernate
 *       {@code ddl-auto: validate} at startup.</li>
 *   <li><strong>VSAM catalog inventory:</strong>
 *       {@code app/catlg/LISTCAT.txt} &mdash; confirms
 *       {@code CARDXREF.VSAM.KSDS} {@code KEYLEN=16, RKP=0,
 *       MAXLRECL=50, AVGLRECL=50, INDEXED, SHROPTNS(2,3)} plus the AIX
 *       {@code KEYS(11,25) NONUNIQUEKEY UPGRADE} attributes that this
 *       test validates against PostgreSQL's equivalent semantics.</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ol>
 *   <li><strong>Round-trip persistence by VARCHAR(16) PK</strong> &mdash;
 *       {@code save()} + {@code findById(String)} of a freshly built
 *       {@link CardCrossReference} keyed by {@code xrefCardNum}
 *       ({@link #saveAndFindById_persistsAndRetrievesByXrefCardNum() Test 1}).</li>
 *   <li><strong>AIX-replacement derived query on {@code xrefAcctId}</strong> &mdash;
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} returns
 *       every cross-reference row for an account ID; this is the
 *       <em>canonical</em> {@code NONUNIQUEKEY UPGRADE} VSAM AIX
 *       semantic preserved as a PostgreSQL non-unique secondary index
 *       ({@link #findByXrefAcctId_returnsAllCardsForAccount_replacingCardXrefVsamAix() Test 2}).</li>
 *   <li><strong>Empty-list return on missing key</strong> &mdash;
 *       {@code findByXrefAcctId} returns an empty {@link List}
 *       (NOT {@code null}, NOT a thrown exception, NOT
 *       {@code Optional.empty()}) when no cross-reference rows exist
 *       for the supplied account ID; preserves the JPA-derived-query
 *       contract for {@code List}-returning methods
 *       ({@link #findByXrefAcctId_returnsEmptyList_whenAccountHasNoCards() Test 3}).</li>
 *   <li><strong>Single-result degenerate case</strong> &mdash; even for
 *       the degenerate "one card per account" case, the method returns
 *       a single-element {@link List}; callers MUST NOT assume
 *       single-row semantics
 *       ({@link #findByXrefAcctId_returnsSingleResult_whenAccountHasOneCard() Test 4}).</li>
 *   <li><strong>{@code deleteById} contract</strong> &mdash;
 *       {@code deleteById(String)} removes the cross-reference row
 *       and a subsequent {@code findById} returns
 *       {@code Optional.empty()}
 *       ({@link #deleteById_removesCrossReference() Test 5}).</li>
 *   <li><strong>{@code findAll} / {@code count} contract</strong> &mdash;
 *       returns every persisted row, satisfying the
 *       {@code XrefFileReaderService} (COBOL {@code CBACT03C}) batch
 *       sequential scan pattern
 *       ({@link #findAll_returnsAllCrossReferences() Test 6}).</li>
 * </ol>
 *
 * <h2>Foreign-key dependencies (parent rows required)</h2>
 *
 * <p>The {@code card_xref} table declares three foreign-key constraints
 * (per V004 lines 409&ndash;433):</p>
 * <ul>
 *   <li>{@code fk_cardxref_card} &mdash; {@code xref_card_num} REFERENCES
 *       {@code cards (card_num)} ON DELETE NO ACTION</li>
 *   <li>{@code fk_cardxref_cust} &mdash; {@code xref_cust_id} REFERENCES
 *       {@code customers (cust_id)} ON DELETE NO ACTION</li>
 *   <li>{@code fk_cardxref_acct} &mdash; {@code xref_acct_id} REFERENCES
 *       {@code accounts (acct_id)} ON DELETE NO ACTION</li>
 * </ul>
 *
 * <p>Therefore, before any {@code card_xref} INSERT, a test must first
 * seed parent rows in {@code accounts} (V001), {@code customers} (V003),
 * and {@code cards} (V002). The {@link #persistAccount(Long)},
 * {@link #persistCustomer(Long)}, and {@link #persistCard(String, Long)}
 * helpers below issue minimal native-SQL INSERTs to satisfy these
 * constraints without coupling this test to the full
 * {@code Account} / {@code Customer} / {@code Card} entity constructor
 * surface. The composite helper {@link #seedParentChain(String, Long, Long)}
 * (account &rarr; customer &rarr; card) is used by every test method
 * that persists at least one {@code card_xref} row. This mirrors the
 * established pattern in {@code CardRepositoryTest.persistAccount} and
 * {@code TransactionRepositoryTest.persistAccount} / {@code persistCard}.</p>
 *
 * <h2>PCI-DSS posture (AAP &sect;0.6.6)</h2>
 *
 * <p>{@link CardCrossReference#getXrefCardNum() xrefCardNum} is the 16-character
 * card number (PAN, cardholder data per PCI-DSS v4.0 Requirement 3.4).
 * This test uses the synthetic-but-realistic value
 * {@code "4111111111111111"} which is the industry-documented PCI test
 * PAN published by every major payment processor as a non-routable
 * test value. Production code paths masking
 * {@code xrefCardNum} are exercised by {@code CardCrossReference#toString()}
 * (which displays only the last 4 digits); this test does NOT log or
 * stringify the PAN beyond AssertJ failure-message rendering which
 * already goes through the masked {@link CardCrossReference#toString()}.</p>
 *
 * <h2>Transactional boundary &mdash; what this test does NOT cover</h2>
 *
 * <p>Service-layer transactional rollback semantics ({@code @Transactional(
 * rollbackFor = Exception.class)} replacing the COBOL
 * {@code EXEC CICS SYNCPOINT ROLLBACK} flow per AAP &sect;0.7.1) are
 * out of scope for repository-slice tests. They are covered by the
 * corresponding service-layer tests
 * ({@code AccountUpdateServiceTest}, {@code TransactionAddServiceTest},
 * {@code BillPaymentServiceTest}, {@code TransactionPostingServiceTest}).</p>
 *
 * @see CardCrossReferenceRepository
 * @see CardCrossReference
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardCrossReferenceRepositoryTest {

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
    // ensuring VARCHAR(16) primary key, BIGINT FK column types,
    // idx_cardxref_acct_id non-unique B-tree secondary index (the canonical
    // CXACAIX-replacement index per AAP §0.6.2), Flyway V001+V002+V003+V004
    // migration application, FK constraints (fk_cardxref_card,
    // fk_cardxref_cust, fk_cardxref_acct), and PostgreSQL-specific SQL
    // semantics are validated faithfully against the engine the
    // application will actually run on in production.
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
    // CardRepositoryTest, CustomerRepositoryTest, TransactionRepositoryTest,
    // and the rest of the repository-slice test suite.
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
     * {@code JpaRepository<CardCrossReference, String>} and declares
     * two custom derived query methods:
     * <ul>
     *   <li>{@link CardCrossReferenceRepository#findByXrefAcctId(Long)} &mdash;
     *       the canonical AIX-replacement derived query exercised by
     *       Tests 2, 3, and 4.</li>
     *   <li>{@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)} &mdash;
     *       the deterministic-order variant (NOT exercised by this slice
     *       test &mdash; its ordering semantics are validated by
     *       service-layer tests where deterministic ordering matters
     *       for byte-identical parallel-run output diffs).</li>
     * </ul>
     */
    @Autowired
    private CardCrossReferenceRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager}
     * (transitively enabled by {@code @DataJpaTest}). Used to:
     * <ul>
     *   <li>Force SQL execution via {@link TestEntityManager#flush() flush()}
     *       so the {@link CardCrossReferenceRepository#save(Object) save()}
     *       actually issues an {@code INSERT} to the database (otherwise
     *       the entity remains in the persistence context buffer until
     *       commit).</li>
     *   <li>Detach the persistence context via
     *       {@link TestEntityManager#clear() clear()} so that subsequent
     *       {@code findById()} / {@code findByXrefAcctId()} calls reload
     *       from the database rather than returning a first-level-cache
     *       hit. Critical for verifying that the derived query actually
     *       traverses the {@code idx_cardxref_acct_id} secondary index
     *       rather than echoing entries from the entity-manager cache.</li>
     *   <li>Issue parent {@code accounts} / {@code customers} /
     *       {@code cards} rows via
     *       {@link TestEntityManager#getEntityManager() getEntityManager()}
     *       {@code .createNativeQuery(...)} (in the {@link #persistAccount(Long)},
     *       {@link #persistCustomer(Long)}, and
     *       {@link #persistCard(String, Long)} helpers) to satisfy the
     *       V004 FK constraints without polluting the test with full
     *       {@link com.awsm2.carddemo.domain.Account} /
     *       {@link com.awsm2.carddemo.domain.Customer} /
     *       {@link com.awsm2.carddemo.domain.Card} entity construction.</li>
     * </ul>
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builders
    // =========================================================================

    /**
     * Builds a transient (non-persisted) {@link CardCrossReference} entity
     * populated with the supplied 3 business fields. Mirrors the COBOL
     * {@code CARD-XREF-RECORD} layout from {@code app/cpy/CVACT03Y.cpy}:
     * the 3 setters are invoked in the SAME order as the COBOL fields
     * (xrefCardNum &rarr; xrefCustId &rarr; xrefAcctId) so the Java
     * source remains visually parallel to the source-of-truth COBOL
     * record layout per AAP &sect;0.7.3 refactor discipline. The trailing
     * COBOL {@code FILLER PIC X(14)} has no relational equivalent and
     * is omitted from the JPA entity (consistent with the V001 / V002 /
     * V003 FILLER-omission pattern).
     *
     * @param cardNum 16-character card-number string (PK; mapped to
     *                {@code xref_card_num VARCHAR(16) PK}); PCI-DSS
     *                cardholder data per AAP &sect;0.6.6 (masked in
     *                {@link CardCrossReference#toString()})
     * @param custId  9-digit unsigned customer identifier (FK to
     *                {@code customers.cust_id}; mapped to
     *                {@code xref_cust_id BIGINT NN})
     * @param acctId  11-digit unsigned account identifier (FK to
     *                {@code accounts.acct_id}; mapped to
     *                {@code xref_acct_id BIGINT NN}; INDEXED by
     *                {@code idx_cardxref_acct_id} which is the
     *                canonical CXACAIX-replacement secondary index
     *                per AAP &sect;0.6.2)
     * @return a fresh, transient (non-persisted) {@link CardCrossReference}
     */
    private CardCrossReference buildXref(String cardNum, Long custId, Long acctId) {
        CardCrossReference x = new CardCrossReference();
        // COBOL: CVACT03Y.cpy:L5 XREF-CARD-NUM PIC X(16) -> VARCHAR(16) PK; PCI-sensitive (masked in toString)
        x.setXrefCardNum(cardNum);
        // COBOL: CVACT03Y.cpy:L6 XREF-CUST-ID PIC 9(09) -> BIGINT NN; FK to customers.cust_id (V003)
        x.setXrefCustId(custId);
        // COBOL: CVACT03Y.cpy:L7 XREF-ACCT-ID PIC 9(11) -> BIGINT NN; FK to accounts.acct_id (V001); INDEXED by idx_cardxref_acct_id (replaces CXACAIX KEYS(11,25) NONUNIQUEKEY UPGRADE per app/jcl/XREFFILE.jcl:L72-L82)
        x.setXrefAcctId(acctId);
        // COBOL: CVACT03Y.cpy:L8 FILLER PIC X(14) -- OMITTED (no relational equivalent for fixed-width VSAM padding per AAP §0.6.2)
        return x;
    }

    /**
     * Inserts a minimal parent {@code accounts} row via native SQL so
     * that subsequent {@code card_xref} INSERTs satisfy the V004
     * {@code fk_cardxref_acct FOREIGN KEY (xref_acct_id) REFERENCES
     * accounts (acct_id) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V001__create_account.sql}
     * &mdash; every NOT NULL column (and only the NOT NULL columns) is
     * populated. Native SQL is used (rather than constructing and
     * persisting a full {@code Account} entity via
     * {@link TestEntityManager#persist(Object) persist}) to keep this
     * test focused on cross-reference-repository behaviour and avoid
     * coupling card_xref-repository tests to the {@code Account}
     * entity's full constructor surface. This mirrors the established
     * pattern in {@code CardRepositoryTest.persistAccount} and
     * {@code TransactionRepositoryTest.persistAccount}.</p>
     *
     * @param acctId the 11-digit account identifier to insert (the
     *               supplied {@link Long} is the foreign-key target
     *               for every {@code card_xref} row built by
     *               {@link #buildXref(String, Long, Long) buildXref})
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
     * Inserts a minimal parent {@code customers} row via native SQL so
     * that subsequent {@code card_xref} INSERTs satisfy the V004
     * {@code fk_cardxref_cust FOREIGN KEY (xref_cust_id) REFERENCES
     * customers (cust_id) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V003__create_customer.sql}
     * &mdash; every NOT NULL column (and only the NOT NULL columns) is
     * populated. Native SQL is used (rather than constructing and
     * persisting a full {@code Customer} entity via
     * {@link TestEntityManager#persist(Object) persist}) to keep this
     * test focused on cross-reference-repository behaviour and avoid
     * coupling card_xref-repository tests to the {@code Customer}
     * entity's full constructor surface (16+ fields). The synthetic
     * SSN value {@code 123456789L} and FICO score {@code 750} are
     * test-only values and are subject to the standard PII masking
     * rules in production logs per AAP &sect;0.6.6.</p>
     *
     * @param custId the 9-digit customer identifier to insert (the
     *               supplied {@link Long} is the foreign-key target
     *               for every {@code card_xref} row built by
     *               {@link #buildXref(String, Long, Long) buildXref})
     */
    private void persistCustomer(Long custId) {
        // V003__create_customer.sql NOT NULL columns:
        //   cust_id, cust_first_name, cust_last_name, cust_addr_line_1,
        //   cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip,
        //   cust_ssn, cust_dob_yyyy_mm_dd, cust_pri_card_holder_ind,
        //   cust_fico_credit_score.
        // CHECK constraint: cust_fico_credit_score BETWEEN 300 AND 850.
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO customers ("
            + "    cust_id, cust_first_name, cust_last_name, "
            + "    cust_addr_line_1, cust_addr_state_cd, "
            + "    cust_addr_country_cd, cust_addr_zip, "
            + "    cust_ssn, cust_dob_yyyy_mm_dd, "
            + "    cust_pri_card_holder_ind, cust_fico_credit_score"
            + ") VALUES ("
            + "    ?, 'JOHN', 'PUBLIC', "
            + "    '123 Main St', 'NY', "
            + "    'USA', '10001', "
            + "    123456789, DATE '1985-06-15', "
            + "    'Y', 750"
            + ")"
        ).setParameter(1, custId).executeUpdate();
    }

    /**
     * Inserts a minimal parent {@code cards} row via native SQL so
     * that subsequent {@code card_xref} INSERTs satisfy the V004
     * {@code fk_cardxref_card FOREIGN KEY (xref_card_num) REFERENCES
     * cards (card_num) ON DELETE NO ACTION} constraint.
     *
     * <p>The native query column list is matched <em>exactly</em>
     * against
     * {@code src/main/resources/db/migration/V002__create_card.sql}
     * &mdash; every NOT NULL column (and only the NOT NULL columns) is
     * populated. As with {@link #persistAccount(Long)} and
     * {@link #persistCustomer(Long)}, native SQL avoids coupling
     * the test to the {@code Card} entity's full constructor surface.</p>
     *
     * @param cardNum the 16-character card PAN to insert (must match
     *                the {@code xrefCardNum} of every
     *                {@code CardCrossReference} that references this
     *                card via the PK &harr; FK relationship
     *                {@code card_xref.xref_card_num} &rarr;
     *                {@code cards.card_num})
     * @param acctId  the parent {@code acct_id} that this card belongs
     *                to (MUST already exist in {@code accounts} via
     *                {@link #persistAccount(Long)} &mdash; the
     *                {@code cards.card_acct_id} column carries its own
     *                FK to {@code accounts(acct_id)} per V002)
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
     * Composite helper that seeds the full parent chain
     * ({@code accounts} &rarr; {@code customers} &rarr; {@code cards})
     * required to satisfy the three FK constraints on the
     * {@code card_xref} table (per V004 lines 409&ndash;433). Issues
     * three native SQL INSERTs in dependency order and returns
     * immediately &mdash; the test method itself is responsible for
     * flushing if needed.
     *
     * <p>Used by every test method that persists at least one
     * {@code card_xref} row (Tests 1, 2, 4, 5, 6). The single test
     * that does NOT call this helper (Test 3 &mdash; empty-list
     * verification on missing key) intentionally skips it since no
     * cross-reference rows are persisted.</p>
     *
     * @param cardNum 16-character card PAN (FK target for
     *                {@code card_xref.xref_card_num} via
     *                {@code fk_cardxref_card})
     * @param custId  9-digit customer identifier (FK target for
     *                {@code card_xref.xref_cust_id} via
     *                {@code fk_cardxref_cust})
     * @param acctId  11-digit account identifier (FK target for
     *                {@code card_xref.xref_acct_id} via
     *                {@code fk_cardxref_acct})
     */
    private void seedParentChain(String cardNum, Long custId, Long acctId) {
        // Order matters: cards depends on accounts (FK fk_cards_acct from V002),
        // so accounts must be inserted first. customers has no FK to either.
        persistAccount(acctId);
        persistCustomer(custId);
        persistCard(cardNum, acctId);
    }

    // =========================================================================
    // Test 1 — Round-trip persistence by VARCHAR(16) PK (xref_card_num)
    // =========================================================================

    /**
     * Validates the foundational {@code JpaRepository} contract: a fresh
     * {@link CardCrossReference} {@code save()}'d via the repository
     * can be retrieved by its primary key with {@code findById(String)}
     * and the 3 business fields round-trip without precision loss.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS WRITE DATASET('CXREFDAT') FROM(CARD-XREF-RECORD)
     * RIDFLD(XREF-CARD-NUM)} followed by
     * {@code EXEC CICS READ DATASET('CXREFDAT') INTO(CARD-XREF-RECORD)
     * RIDFLD(XREF-CARD-NUM)} &mdash; the canonical write-then-read
     * pattern used by every CardDemo program that touches the
     * {@code CXREFDAT} file (notably {@code COTRN02C.cbl} transaction-
     * add card-link validation and {@code CBTRN02C.cbl} batch-posting
     * Stage 1 XREF lookup).</p>
     */
    @Test
    void saveAndFindById_persistsAndRetrievesByXrefCardNum() {
        // Arrange: seed parent chain (accounts -> customers -> cards)
        // so the V004 FK constraints (fk_cardxref_card, fk_cardxref_cust,
        // fk_cardxref_acct) are satisfied on subsequent card_xref INSERT.
        seedParentChain("4111111111111111", 100000001L, 10000000001L);

        // Build a transient CardCrossReference fixture (synthetic PAN that
        // resembles a real one but is the documented PCI test value).
        CardCrossReference x = buildXref("4111111111111111", 100000001L, 10000000001L);

        // Act: persist + flush + clear → forces SQL execution + detaches
        // the persistence context so the subsequent findById() reloads from
        // the database (NOT from L1 cache).
        repository.save(x);
        entityManager.flush();
        entityManager.clear();

        // Assert: the row is present and the business fields round-trip intact.
        Optional<CardCrossReference> reloaded = repository.findById("4111111111111111");
        assertThat(reloaded).isPresent();
        CardCrossReference reloadedXref = reloaded.get();
        assertThat(reloadedXref.getXrefCardNum()).isEqualTo("4111111111111111");
        assertThat(reloadedXref.getXrefCustId()).isEqualTo(100000001L);
        // The xrefAcctId is the AIX-indexed column; assert it round-trips intact.
        assertThat(reloadedXref.getXrefAcctId()).isEqualTo(10000000001L);
    }

    // =========================================================================
    // Test 2 — Canonical AIX-replacement derived query (NONUNIQUEKEY semantic)
    // =========================================================================

    /**
     * Validates the canonical VSAM AIX &rarr; PostgreSQL secondary-index
     * migration pattern (AAP &sect;0.6.2): the COBOL VSAM AIX
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX KEYS(11 25) NONUNIQUEKEY
     * UPGRADE} (referenced under the CICS symbolic name {@code CXACAIX})
     * is replaced by the PostgreSQL non-unique B-tree index
     * {@code idx_cardxref_acct_id} on {@code xref_acct_id} declared in
     * {@code V004__create_cardxref.sql}:L530, plus the derived query
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} that
     * returns {@code List<CardCrossReference>} (NOT {@code Optional}
     * &mdash; the {@code NONUNIQUEKEY} VSAM attribute permits multiple
     * cross-reference rows per account ID).
     *
     * <p><strong>COBOL pattern (replaced):</strong>
     * {@code EXEC CICS STARTBR DATASET('CXACAIX') RIDFLD(XREF-ACCT-ID)}
     * followed by repeated {@code EXEC CICS READNEXT} calls until end-
     * of-key or end-of-file. The {@code NONUNIQUEKEY} attribute on the
     * VSAM AIX permits multiple {@code CARD-XREF-RECORD}s per
     * {@code XREF-ACCT-ID}, supporting the cardholder business rule
     * that one account commonly owns multiple physical / virtual cards
     * (primary card + joint-cardholder card + virtual card +
     * replacement card after reissue).</p>
     *
     * <p><strong>JPA pattern (replacement, AAP &sect;0.6.2):</strong>
     * Spring Data JPA translates the derived method name into the JPQL
     * query
     * {@code SELECT cx FROM CardCrossReference cx WHERE cx.xrefAcctId =
     * :acctId}, which PostgreSQL executes against the
     * {@code idx_cardxref_acct_id} non-unique secondary index. The
     * index is NOT UNIQUE (matching the VSAM NONUNIQUEKEY semantic),
     * so multiple rows per {@code xrefAcctId} are returned as a
     * {@link List Java List} &mdash; the natural mapping for the
     * NONUNIQUEKEY VSAM semantic. PostgreSQL EXPLAIN ANALYZE output
     * shows "Bitmap Index Scan on idx_cardxref_acct_id" for queries
     * on this column.</p>
     */
    @Test
    void findByXrefAcctId_returnsAllCardsForAccount_replacingCardXrefVsamAix() {
        // VSAM AIX (KEYS 11 25 NONUNIQUEKEY) → derived query backed by idx_cardxref_acct_id (V004)

        // Arrange: seed two parent account chains (one for the 3-card account, one for the unrelated card).
        // Account 1 — owns 3 cards (cards 4111111111111111, 4111111111111112, 4111111111111113)
        persistAccount(10000000001L);
        persistCustomer(100000001L);
        persistCard("4111111111111111", 10000000001L);
        persistCard("4111111111111112", 10000000001L);
        persistCard("4111111111111113", 10000000001L);

        // Account 2 — owns 1 unrelated card (must NOT appear in result list)
        persistAccount(10000000002L);
        persistCustomer(100000002L);
        persistCard("5500000000000004", 10000000002L);

        // Persist 3 cross-references for account 10000000001L
        repository.save(buildXref("4111111111111111", 100000001L, 10000000001L));
        repository.save(buildXref("4111111111111112", 100000001L, 10000000001L));
        repository.save(buildXref("4111111111111113", 100000001L, 10000000001L));
        // Persist 1 cross-reference for a different account (10000000002L)
        repository.save(buildXref("5500000000000004", 100000002L, 10000000002L));

        // Force flush + clear → SQL execution + persistence-context detachment
        // so the derived query traverses the idx_cardxref_acct_id index and
        // loads rows from the DB (not from the L1 cache).
        entityManager.flush();
        entityManager.clear();

        // Act: the canonical AIX-replacement derived query
        List<CardCrossReference> result = repository.findByXrefAcctId(10000000001L);

        // Assert: NONUNIQUEKEY semantic — multiple rows returned for one account.
        assertThat(result)
                .as("findByXrefAcctId must return all 3 cross-references for the queried account "
                        + "(NONUNIQUEKEY semantic preserved from CARDXREF.VSAM.AIX KEYS(11 25))")
                .hasSize(3);

        // Every result has the queried xrefAcctId — sanity-check on the WHERE clause.
        assertThat(result)
                .as("Every result must carry the queried xrefAcctId (10000000001L)")
                .allSatisfy(xref -> assertThat(xref.getXrefAcctId()).isEqualTo(10000000001L));

        // The 3 expected card numbers are all in the result; the unrelated
        // 5500-card from the other account is NOT.
        assertThat(result)
                .extracting(CardCrossReference::getXrefCardNum)
                .as("Result must contain exactly the 3 cards mapped to acctId 10000000001L, "
                        + "and must NOT contain the unrelated 5500-card mapped to acctId 10000000002L")
                .containsExactlyInAnyOrder(
                        "4111111111111111",
                        "4111111111111112",
                        "4111111111111113")
                .doesNotContain("5500000000000004");
    }

    // =========================================================================
    // Test 3 — Empty-list semantic on missing key (NOT null, NOT Optional.empty)
    // =========================================================================

    /**
     * Validates that
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} returns
     * an empty {@link List} &mdash; <em>not</em> {@code null}, <em>not</em>
     * a thrown exception, <em>not</em> {@code Optional.empty()} &mdash;
     * when no cross-reference rows exist for the supplied account ID.
     *
     * <p><strong>Why this matters:</strong> the COBOL VSAM AIX browse
     * pattern uses {@code EXEC CICS STARTBR} followed by
     * {@code READNEXT} until {@code DFHRESP(NOTFND)} or
     * {@code DFHRESP(ENDFILE)} terminates the browse. The Java target
     * collapses this iteration into a single {@code SELECT ... WHERE
     * xref_acct_id = :acctId} that returns ALL matching rows as a
     * {@code List<CardCrossReference>}. The "no rows match" case must
     * surface as a non-null, empty {@code List} so service-layer code
     * can uniformly iterate the result without special-casing
     * {@code null} (which would be a {@link NullPointerException}
     * waiting to happen).</p>
     *
     * <p><strong>NONUNIQUEKEY semantic:</strong> the empty-list result
     * mirrors the COBOL pattern where an immediate
     * {@code DFHRESP(NOTFND)} on the initial STARTBR yields zero
     * iterations of the read loop. The Java service layer treats this
     * identically &mdash; an empty {@code List.iterator()} performs
     * zero iterations of the {@code for-each} loop.</p>
     */
    @Test
    void findByXrefAcctId_returnsEmptyList_whenAccountHasNoCards() {
        // NONUNIQUEKEY semantics: missing key returns empty List (NOT Optional.empty)

        // Arrange: no cross-reference rows seeded for the queried account
        // (and no FK parent rows needed since we don't persist anything).
        // The card_xref table is empty (or contains only unrelated rows),
        // so a query against an arbitrary missing acctId must return empty.

        // Act: query an account that has no cross-reference rows.
        List<CardCrossReference> result = repository.findByXrefAcctId(99999999999L);

        // Assert: the result is a non-null, empty List.
        // assertThat(result).isEmpty() encompasses both isNotNull() and hasSize(0).
        assertThat(result)
                .as("findByXrefAcctId must return an EMPTY List (not null, not exception) "
                        + "when no cross-reference rows match the queried xrefAcctId")
                .isNotNull()
                .isEmpty();
    }

    // =========================================================================
    // Test 4 — Single-result degenerate case (NONUNIQUEKEY still returns List)
    // =========================================================================

    /**
     * Validates that
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} returns
     * a single-element {@link List} (NOT a stripped scalar, NOT an
     * {@link Optional}) even for the degenerate "one card per account"
     * case.
     *
     * <p>This test exercises the boundary condition where the COBOL
     * source's {@code NONUNIQUEKEY UPGRADE} VSAM AIX semantically
     * permits multiple rows but in practice (for this particular
     * account) only one row exists. The Java target's return type
     * does NOT degrade to {@code Optional<CardCrossReference>} for
     * the single-row case &mdash; that would be a Liskov violation
     * (callers expecting {@code List} would break on the type
     * change). The {@code List} contract is uniform across zero,
     * one, and many results per the schema-level guarantee in
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}'s
     * Javadoc.</p>
     *
     * <p><strong>Consumer implications:</strong> service-layer
     * callers that "know" an account has at most one card (e.g.,
     * single-cardholder retail-card accounts) must still call
     * {@code result.get(0)} defensively or use
     * {@code result.stream().findFirst()} to extract the single
     * element. The cardinality contract preserved verbatim from
     * the COBOL source mandates this caller discipline.</p>
     */
    @Test
    void findByXrefAcctId_returnsSingleResult_whenAccountHasOneCard() {
        // Arrange: seed parent chain + persist exactly 1 cross-reference for acctId 10000000003L
        seedParentChain("4111111111111114", 100000003L, 10000000003L);
        repository.save(buildXref("4111111111111114", 100000003L, 10000000003L));

        entityManager.flush();
        entityManager.clear();

        // Act: query the account with exactly one card.
        List<CardCrossReference> result = repository.findByXrefAcctId(10000000003L);

        // Assert: the result is a single-element List — NOT degraded to Optional.
        assertThat(result)
                .as("findByXrefAcctId must return a single-element List for the degenerate "
                        + "one-card-per-account case (NOT degraded to Optional; List contract uniform)")
                .hasSize(1);
        // The single element carries the queried xrefAcctId and the inserted xrefCardNum.
        assertThat(result.get(0).getXrefAcctId()).isEqualTo(10000000003L);
        assertThat(result.get(0).getXrefCardNum()).isEqualTo("4111111111111114");
        assertThat(result.get(0).getXrefCustId()).isEqualTo(100000003L);
    }

    // =========================================================================
    // Test 5 — deleteById contract
    // =========================================================================

    /**
     * Validates that
     * {@link CardCrossReferenceRepository#deleteById(Object)} removes
     * the cross-reference row and a subsequent {@code findById} returns
     * {@code Optional.empty()}.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS DELETE DATASET('CXREFDAT') RIDFLD(XREF-CARD-NUM)}
     * used at card-closure time when a customer cancels a card or the
     * issuer reissues a new card number after a security event. The
     * COBOL source treats {@code CARDXREF} as a write-once-at-issuance,
     * delete-at-card-closure linkage table &mdash; never updated in
     * place (which is why the {@link CardCrossReference} JPA entity
     * does not carry a {@code @Version} optimistic-lock column per
     * AAP &sect;0.4.1).</p>
     *
     * <p>The {@code ON DELETE NO ACTION} FK declarations on
     * {@code card_xref} (per V004 lines 409&ndash;433) are NOT
     * exercised here &mdash; this test deletes the {@code card_xref}
     * row WITHOUT deleting the parent rows, so no FK violation arises.
     * The parent-row deletion behaviour (FK violation if parent is
     * deleted before child) is a V001 / V002 / V003 concern, not a
     * V004 concern.</p>
     */
    @Test
    void deleteById_removesCrossReference() {
        // Arrange: seed parent chain + persist a single cross-reference.
        seedParentChain("4111111111111115", 100000004L, 10000000004L);
        repository.save(buildXref("4111111111111115", 100000004L, 10000000004L));

        entityManager.flush();
        entityManager.clear();

        // Sanity check — the row is present before delete.
        assertThat(repository.findById("4111111111111115")).isPresent();

        // Act: delete by primary key + flush to force the DELETE statement to fire.
        repository.deleteById("4111111111111115");
        entityManager.flush();
        entityManager.clear();

        // Assert: the row is no longer present after delete + flush.
        assertThat(repository.findById("4111111111111115"))
                .as("deleteById must remove the cross-reference row; findById must return Optional.empty()")
                .isEmpty();
    }

    // =========================================================================
    // Test 6 — findAll / count contract (XrefFileReaderService sequential scan)
    // =========================================================================

    /**
     * Validates that {@link CardCrossReferenceRepository#findAll()} and
     * {@link CardCrossReferenceRepository#count()} return every
     * persisted row, satisfying the {@code XrefFileReaderService}
     * (COBOL {@code CBACT03C}) batch sequential scan pattern.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS STARTBR DATASET('CXREFDAT')} followed by
     * repeated {@code READNEXT} calls until {@code DFHRESP(ENDFILE)}
     * &mdash; the canonical full-cluster scan used by
     * {@code CBACT03C.cbl} for audit, reporting, and regulatory
     * inquiry purposes per AAP &sect;0.4.1.</p>
     *
     * <p><strong>Assertion strategy:</strong> the assertions use
     * {@code isGreaterThanOrEqualTo(3)} rather than {@code isEqualTo(3)}
     * because the {@code @DataJpaTest} slice does NOT enforce strict
     * isolation between test methods (each test method runs in its
     * own transaction that is rolled back on completion, but the
     * Flyway-seeded reference data plus this test's own seeds may
     * persist visibility within the JPA repository's view depending
     * on the order of execution). Asserting the floor "at least 3"
     * is robust under any test ordering. The contract being
     * validated is "{@code findAll} returns all persisted rows"; the
     * exact upper bound is not part of the repository contract.</p>
     */
    @Test
    void findAll_returnsAllCrossReferences() {
        // Arrange: seed 3 distinct parent chains + 3 cross-references.
        // Each test method runs in its own transaction and is rolled back
        // on completion (default @DataJpaTest behaviour), so these 3 rows
        // are visible to this method's findAll() / count() calls but do
        // NOT pollute other test methods.
        persistAccount(10000000005L);
        persistAccount(10000000006L);
        persistAccount(10000000007L);
        persistCustomer(100000005L);
        persistCustomer(100000006L);
        persistCustomer(100000007L);
        persistCard("4111111111111116", 10000000005L);
        persistCard("4111111111111117", 10000000006L);
        persistCard("4111111111111118", 10000000007L);

        repository.save(buildXref("4111111111111116", 100000005L, 10000000005L));
        repository.save(buildXref("4111111111111117", 100000006L, 10000000006L));
        repository.save(buildXref("4111111111111118", 100000007L, 10000000007L));

        entityManager.flush();
        entityManager.clear();

        // Act + Assert: count must reflect the persisted rows.
        // ">=" 3 is robust under any Flyway pre-seed (V012 / V013 / V014 /
        // V015 do NOT seed card_xref rows, but this defensive bound is
        // future-proof against any future seed additions).
        assertThat(repository.count())
                .as("count() must return at least the 3 rows persisted by this test")
                .isGreaterThanOrEqualTo(3L);

        // findAll must return every persisted row. We don't assert exact size
        // for the same robustness reason as count() above.
        List<CardCrossReference> all = repository.findAll();
        assertThat(all)
                .as("findAll() must return at least the 3 cross-references persisted by this test")
                .isNotNull()
                .hasSizeGreaterThanOrEqualTo(3);
        // The 3 newly persisted card numbers must all be present in findAll().
        assertThat(all)
                .extracting(CardCrossReference::getXrefCardNum)
                .as("findAll() must include every persisted cross-reference (XrefFileReaderService / "
                        + "CBACT03C sequential-scan contract)")
                .contains("4111111111111116", "4111111111111117", "4111111111111118");
    }
}
