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

import com.awsm2.carddemo.domain.Customer;
import java.time.LocalDate;
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
 * {@code @DataJpaTest} slice test for {@link CustomerRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.6.6, PCI-DSS)</h2>
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} &mdash;
 *       {@code KEYS(9 0)}, {@code RECORDSIZE(500 500)},
 *       {@code SHAREOPTIONS(2 3)}, {@code INDEXED}, {@code CYLINDERS(1 5)}
 *       (per {@code app/jcl/CUSTFILE.jcl}:L46&ndash;L59 and verified
 *       against {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COACTVWC.cbl} &mdash; account view: joins
 *             customers onto accounts via {@code card_xref} to render
 *             the account-inquiry screen.</li>
 *         <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update:
 *             updates customer demographic columns alongside account
 *             columns within a single transactional boundary.</li>
 *         <li>{@code app/cbl/CBCUS01C.cbl} &mdash; batch sequential
 *             scanner that emits every row in the customers table for
 *             audit / reporting purposes.</li>
 *         <li>{@code app/cbl/CBSTM03A.CBL} / {@code CBSTM03B.CBL} &mdash;
 *             statement generation; prints the customer name and
 *             mailing address as the statement header.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Copybooks:</strong> {@code app/cpy/CVCUS01Y.cpy} and
 *       {@code app/cpy/CUSTREC.cpy} &mdash; both define IDENTICAL
 *       500-byte {@code CUSTOMER-RECORD} layouts (18 business fields
 *       plus a 168-byte trailing {@code FILLER}). The only textual
 *       difference is the DOB field name
 *       ({@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y.cpy} vs
 *       {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC.cpy}); both refer
 *       to the same 10-byte position in the record. V003 and the
 *       {@link Customer} entity adopt the cleaner {@code CVCUS01Y}
 *       naming.</li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/CUSTFILE.jcl}
 *       &mdash; IDCAMS DEFINE CLUSTER (STEP10) + IDCAMS REPRO load
 *       (STEP15) from {@code CUSTDATA.PS}. Bulk customer master data is
 *       loaded to RDS by an AWS Glue Spark job per AAP &sect;0.6.2;
 *       Flyway does NOT seed customer rows (unlike disclosure_group,
 *       transaction_type, transaction_category, user_security which DO
 *       have seed migrations).</li>
 *   <li><strong>Migration:</strong>
 *       {@code src/main/resources/db/migration/V003__create_customer.sql}
 *       (18 columns; cust_id BIGINT PK; cust_ssn BIGINT NN [PII];
 *       cust_dob_yyyy_mm_dd DATE NN [PII]; cust_pri_card_holder_ind
 *       CHAR(1) NN; cust_fico_credit_score INTEGER NN with CHECK
 *       constraint BETWEEN 300 AND 850; CHAR(2) state, CHAR(3) country,
 *       VARCHAR for names/addresses/zip/phones/govt-id/eft).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>Round-trip persistence by BIGINT PK:</strong>
 *       {@code save()} + {@code findById()} of a freshly built
 *       {@link Customer} (Test 1).</li>
 *   <li><strong>PII fields persist correctly:</strong> SSN
 *       ({@code Long}), govt-issued ID ({@code String}), and DOB
 *       ({@link LocalDate}) round-trip through the BIGINT / VARCHAR(20)
 *       / DATE columns intact &mdash; encryption-at-rest is delegated
 *       to RDS KMS CMK and is transparent to the application
 *       (Test 2).</li>
 *   <li><strong>{@code Customer.toString()} masks SSN per AAP
 *       &sect;0.6.6:</strong> the raw 9-digit SSN value never appears
 *       in the string output; the masked form
 *       {@code "***-**-NNNN"} (last 4 digits only) is emitted instead
 *       (Test 3).</li>
 *   <li><strong>{@code Customer.toString()} omits phone numbers and
 *       full DOB per AAP &sect;0.6.6:</strong> stray
 *       {@code log.info(customer)} cannot leak phone or DOB into
 *       CloudWatch Logs (Test 4).</li>
 *   <li><strong>Nullable columns persist as NULL:</strong> middle name,
 *       address lines 2/3, phone numbers, govt-issued ID, and EFT
 *       account ID accept and round-trip {@code null} values per V003
 *       schema (Test 5).</li>
 *   <li><strong>Fixed-length CHAR(2)/CHAR(3)/CHAR(1) columns:</strong>
 *       state code, country code, and primary-cardholder indicator
 *       round-trip without Hibernate auto-padding artifacts (Test 6).</li>
 *   <li><strong>NUMERIC(3) FICO score:</strong> Integer 750 round-trips
 *       and the boundary values 300 / 850 are accepted by the V003
 *       CHECK constraint (Test 7).</li>
 *   <li><strong>{@code deleteById} contract:</strong> a previously
 *       persisted customer row can be deleted and is no longer
 *       findable (Test 8).</li>
 *   <li><strong>{@code count()} returns the row cardinality after
 *       multi-row save:</strong> three persisted rows yield a count of
 *       3 (Test 9).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>PII-searching custom finders.</strong> Per AAP
 *       &sect;0.6.6, the {@link CustomerRepository} interface declares
 *       <em>no</em> {@code findBySsn(...)}, {@code findByDobYyyyMmDd(...)},
 *       {@code findByCustFirstNameAndCustLastName(...)}, or any other
 *       PII-keyed lookup. Such methods would leak sensitive data into
 *       SQL query strings, query plans, slow-query logs, and RDS
 *       Performance Insights captures &mdash; all of which are
 *       common PCI-DSS audit findings. This test deliberately exercises
 *       only the inherited {@link
 *       org.springframework.data.jpa.repository.JpaRepository}
 *       primary-key access patterns to prove the security-by-design
 *       posture is preserved.</li>
 *   <li><strong>Encryption at rest.</strong> RDS KMS CMK encryption is
 *       a storage-layer concern transparent to JDBC; this test cannot
 *       observe it. Per AAP &sect;0.6.6 the schema-layer DDL does NOT
 *       apply column-level {@code pgcrypto} encryption.</li>
 *   <li><strong>Logback masking patterns.</strong> Defense-in-depth
 *       masking via {@code logback-spring.xml} regex patterns is a
 *       cross-cutting logging concern unit-tested separately. This
 *       test exercises only the entity-layer masking implemented in
 *       {@link Customer#toString()}.</li>
 *   <li><strong>Optimistic locking.</strong> {@link Customer} has no
 *       {@code @Version} column &mdash; customer demographic updates
 *       in {@code COACTUPC} flow under the {@code Account} entity's
 *       {@code @Version} check (per the Customer entity Javadoc).
 *       There is no read-modify-write contention on the Customer
 *       table to guard against at the repository layer.</li>
 *   <li><strong>FICO range CHECK violation behavior.</strong> V003
 *       declares {@code CHECK (cust_fico_credit_score BETWEEN 300 AND
 *       850)}. This test asserts that valid values 300, 750, 850 are
 *       accepted; out-of-range constraint-violation behaviour is a
 *       schema-validation concern outside the scope of this repository
 *       slice test.</li>
 * </ul>
 *
 * <h2>Container strategy</h2>
 * <p>The test class uses a single static {@code postgres:16-alpine}
 * Testcontainer (PostgreSQL 16 to match the production RDS Multi-AZ
 * baseline per AAP &sect;0.5.1, &sect;0.6.2). The
 * {@link ServiceConnection &#64;ServiceConnection} annotation registers
 * the container's JDBC connection details as a
 * {@code JdbcConnectionDetails} bean; the
 * {@link DynamicPropertySource &#64;DynamicPropertySource} method below
 * additionally pushes the URL / credentials / driver into
 * {@code spring.datasource.*} so that the user-declared
 * {@code @Primary @RefreshScope} {@code HikariDataSource} bean in
 * {@code JpaConfig} (which is constructed from
 * {@code spring.datasource.*} properties rather than from
 * {@code JdbcConnectionDetails}) picks up the SAME container that
 * {@code @ServiceConnection} configures for the auto-config consumers.
 * Flyway then applies the full V001&hellip;V015 migration set against
 * the fresh container before any test method runs, so the
 * {@code customers} table (V003) is ready for use.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a
 * {@code @Transactional} boundary that rolls back at the end of the
 * test. Any rows persisted by an individual test (Tests 1, 2, 5, 6, 7,
 * 8, 9) are rolled back at the end of that test and invisible to the
 * others. This per-test isolation is what allows Test 9's
 * {@code count() == 3} assertion to hold deterministically &mdash; no
 * customer rows from prior tests survive into Test 9, and V003 does
 * not seed customer master data (per AAP &sect;0.6.2, customer data is
 * loaded by Glue Spark jobs in production, not by Flyway).</p>
 *
 * <h2>Note on absent custom queries</h2>
 * <p>{@link CustomerRepository} has an empty interface body &mdash; it
 * extends {@code JpaRepository<Customer, Long>} and declares no custom
 * derived queries, no {@code @Query} annotations, and no native SQL
 * methods. All consumer access patterns are satisfied by inherited
 * {@link org.springframework.data.jpa.repository.JpaRepository}
 * methods. This is a security-by-design choice per AAP &sect;0.6.6:
 * any future contributor adding a PII-searching method to this
 * repository would violate the PCI-DSS audit posture and must obtain
 * an explicit security review before merging.</p>
 *
 * @see CustomerRepository
 * @see Customer
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryTest {

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
    // ensuring BIGINT primary key, BIGINT cust_ssn, INTEGER FICO score,
    // CHAR(2) state / CHAR(3) country / CHAR(1) indicator semantics,
    // DATE column for DOB, V003 CHECK constraint enforcement, Flyway V003
    // migration application, and PostgreSQL-specific SQL semantics are
    // validated faithfully against the engine the application will
    // actually run on in production.
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
    // Mirrors the pattern in TransactionTypeRepositoryTest,
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
     * {@code @EnableJpaRepositories}). The repository has an empty
     * interface body &mdash; this field exercises only inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * methods.
     */
    @Autowired
    private CustomerRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to force SQL execution via
     * {@link TestEntityManager#flush() flush()} and to detach the persistence
     * context via {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than returning
     * a first-level-cache hit. This is critical for verifying that PII fields
     * (SSN, govt-issued ID, DOB) round-trip from PostgreSQL BIGINT / VARCHAR /
     * DATE columns intact and not merely echoed from the entity-manager cache.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builder
    // =========================================================================

    /**
     * Builds a transient (non-persisted) {@link Customer} entity populated
     * with the supplied identity fields plus a canonical set of valid
     * defaults for every other field defined in
     * {@code app/cpy/CVCUS01Y.cpy}. The fixture is intentionally minimal:
     * required fields are populated with realistic golden-fixture-style
     * values; nullable fields ({@code custMiddleName},
     * {@code custAddrLine2}, {@code custAddrLine3},
     * {@code custPhoneNum1}, {@code custPhoneNum2},
     * {@code custEftAccountId}) default to {@code null} so that Test 5
     * can validate the V003 nullable-column contract by simply re-using
     * this builder.
     *
     * <p>The 18 setters are invoked in the SAME order as the COBOL
     * {@code CUSTOMER-RECORD} layout
     * ({@code app/cpy/CVCUS01Y.cpy}:L5&ndash;L22) so that the Java
     * source remains visually parallel to the source-of-truth COBOL
     * record layout per AAP &sect;0.7.3 refactor discipline.</p>
     *
     * @param custId    9-digit unsigned numeric customer identifier (PK,
     *                  mapped to {@code cust_id BIGINT})
     * @param firstName customer first name (max 25 chars; required;
     *                  mapped to {@code cust_first_name VARCHAR(25)})
     * @param lastName  customer last name (max 25 chars; required;
     *                  mapped to {@code cust_last_name VARCHAR(25)})
     * @return a fresh, transient (non-persisted) {@link Customer}
     */
    private Customer buildCustomer(Long custId, String firstName, String lastName) {
        Customer c = new Customer();
        // COBOL: CVCUS01Y.cpy:L5 CUST-ID PIC 9(09) -> BIGINT PK
        c.setCustId(custId);
        // COBOL: CVCUS01Y.cpy:L6 CUST-FIRST-NAME PIC X(25) -> VARCHAR(25) NN
        c.setCustFirstName(firstName);
        // COBOL: CVCUS01Y.cpy:L7 CUST-MIDDLE-NAME PIC X(25) -> nullable
        c.setCustMiddleName(null);
        // COBOL: CVCUS01Y.cpy:L8 CUST-LAST-NAME PIC X(25) -> VARCHAR(25) NN
        c.setCustLastName(lastName);
        // COBOL: CVCUS01Y.cpy:L9 CUST-ADDR-LINE-1 PIC X(50) -> VARCHAR(50) NN
        c.setCustAddrLine1("123 Main St");
        // COBOL: CVCUS01Y.cpy:L10 CUST-ADDR-LINE-2 PIC X(50) -> nullable
        c.setCustAddrLine2(null);
        // COBOL: CVCUS01Y.cpy:L11 CUST-ADDR-LINE-3 PIC X(50) -> nullable
        c.setCustAddrLine3(null);
        // COBOL: CVCUS01Y.cpy:L12 CUST-ADDR-STATE-CD PIC X(02) -> CHAR(2) NN
        c.setCustAddrStateCd("NY");
        // COBOL: CVCUS01Y.cpy:L13 CUST-ADDR-COUNTRY-CD PIC X(03) -> CHAR(3) NN
        c.setCustAddrCountryCd("USA");
        // COBOL: CVCUS01Y.cpy:L14 CUST-ADDR-ZIP PIC X(10) -> VARCHAR(10) NN
        c.setCustAddrZip("10001");
        // COBOL: CVCUS01Y.cpy:L15 CUST-PHONE-NUM-1 PIC X(15) -> nullable (PII)
        c.setCustPhoneNum1(null);
        // COBOL: CVCUS01Y.cpy:L16 CUST-PHONE-NUM-2 PIC X(15) -> nullable (PII)
        c.setCustPhoneNum2(null);
        // COBOL: CVCUS01Y.cpy:L17 CUST-SSN PIC 9(09) -> BIGINT NN (PII; masked in toString)
        c.setCustSsn(123456789L);
        // COBOL: CVCUS01Y.cpy:L18 CUST-GOVT-ISSUED-ID PIC X(20) -> nullable (PII; masked)
        c.setCustGovtIssuedId("DL123456");
        // COBOL: CVCUS01Y.cpy:L19 CUST-DOB-YYYY-MM-DD PIC X(10) -> DATE NN (PII; omitted in toString)
        c.setCustDobYyyyMmDd(LocalDate.of(1985, 6, 15));
        // COBOL: CVCUS01Y.cpy:L20 CUST-EFT-ACCOUNT-ID PIC X(10) -> nullable
        c.setCustEftAccountId(null);
        // COBOL: CVCUS01Y.cpy:L21 CUST-PRI-CARD-HOLDER-IND PIC X(01) -> CHAR(1) NN
        c.setCustPriCardHolderInd("Y");
        // COBOL: CVCUS01Y.cpy:L22 CUST-FICO-CREDIT-SCORE PIC 9(03) -> INTEGER NN (CHECK 300..850)
        c.setCustFicoCreditScore(750);
        return c;
    }

    // =========================================================================
    // Test 1 — save + findById round-trips a Customer by its CUST-ID
    // =========================================================================

    /**
     * Validates that {@link CustomerRepository} can persist a freshly
     * built {@link Customer} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * 9-digit {@code cust_id} primary key via
     * {@code JpaRepository.findById(Long)}.
     *
     * <p>Replaces the COBOL pattern of
     * {@code EXEC CICS WRITE DATASET('CUSTDATA') FROM(CUSTOMER-RECORD)
     * RIDFLD(CUST-ID)} followed by an
     * {@code EXEC CICS READ DATASET('CUSTDATA') INTO(CUSTOMER-RECORD)
     * RIDFLD(CUST-ID)} positioned re-read &mdash; the relational
     * equivalent exercises Hibernate INSERT, SQL flush,
     * persistence-context clear, and SELECT BY PK.</p>
     */
    @Test
    void saveAndFindById_persistsCustomerByCustId() {
        Customer input = buildCustomer(100000001L, "Jane", "Doe");

        repository.save(input);
        // Force the pending INSERT to the DB and detach the entity so the
        // subsequent findById() returns a freshly hydrated entity (rather
        // than the same instance from the first-level cache).
        entityManager.flush();
        entityManager.clear();

        Customer loaded = repository.findById(100000001L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000001 to be present "
                                + "after save + flush + clear cycle"));

        // Identity assertion — verify primary-key round-trip
        assertThat(loaded.getCustId())
                .as("cust_id (BIGINT PK) must round-trip the COBOL "
                        + "CUST-ID PIC 9(09) value unchanged")
                .isEqualTo(100000001L);
        // Display-name assertions — VARCHAR(25) columns
        assertThat(loaded.getCustFirstName())
                .as("cust_first_name (VARCHAR(25)) must round-trip the COBOL "
                        + "CUST-FIRST-NAME PIC X(25) value unchanged")
                .isEqualTo("Jane");
        assertThat(loaded.getCustLastName())
                .as("cust_last_name (VARCHAR(25)) must round-trip the COBOL "
                        + "CUST-LAST-NAME PIC X(25) value unchanged")
                .isEqualTo("Doe");
    }

    // =========================================================================
    // Test 2 — PII fields persist and reload correctly (AAP §0.6.6)
    // =========================================================================

    /**
     * Validates that the PII columns ({@code cust_ssn},
     * {@code cust_govt_issued_id}, {@code cust_dob_yyyy_mm_dd})
     * round-trip through PostgreSQL BIGINT / VARCHAR(20) / DATE without
     * loss or transformation.
     *
     * <p>Encryption at rest via RDS KMS CMK is delegated to the storage
     * layer per AAP &sect;0.6.6 and is TRANSPARENT to the application:
     * JDBC reads back the same values it wrote. This test verifies that
     * application-layer logic (in particular the {@link LocalDate}
     * conversion replacing LE {@code CEEDAYS} per AAP &sect;0.6.1) does
     * not alter the values during round-trip.</p>
     *
     * <p><b>PII context:</b> the SSN {@code 123456789L} is a
     * deliberately non-realistic test value (no real person should
     * have SSN 123-45-6789); the govt-issued ID
     * {@code "DL123456"} mimics a driver's license number; the DOB
     * {@code 1985-06-15} is an arbitrary adult-aged date.</p>
     */
    @Test
    void piiFields_persistAndReloadCorrectly() {
        // AAP §0.6.6 — PII persists at rest; encryption via RDS KMS CMK (transparent to application)
        Customer input = buildCustomer(100000002L, "John", "Smith");
        // buildCustomer already sets custSsn = 123456789L,
        // custGovtIssuedId = "DL123456", custDobYyyyMmDd = 1985-06-15.

        repository.save(input);
        entityManager.flush();
        entityManager.clear();

        Customer loaded = repository.findById(100000002L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000002 to be present "
                                + "after save + flush + clear cycle"));

        // CUST-SSN PIC 9(09) -> BIGINT — 9-digit unsigned SSN must round-trip
        assertThat(loaded.getCustSsn())
                .as("cust_ssn (BIGINT) must round-trip the COBOL CUST-SSN "
                        + "PIC 9(09) value unchanged — encryption at rest via "
                        + "RDS KMS CMK is transparent to the application "
                        + "(AAP §0.6.6)")
                .isEqualTo(123456789L);

        // CUST-GOVT-ISSUED-ID PIC X(20) -> VARCHAR(20) — driver's license, passport, state ID
        assertThat(loaded.getCustGovtIssuedId())
                .as("cust_govt_issued_id (VARCHAR(20)) must round-trip the "
                        + "COBOL CUST-GOVT-ISSUED-ID PIC X(20) value unchanged "
                        + "— sensitive PII (AAP §0.6.6) preserved at rest")
                .isEqualTo("DL123456");

        // CUST-DOB-YYYY-MM-DD PIC X(10) -> DATE — native LocalDate per AAP §0.6.1
        // (replaces LE CEEDAYS-based date handling with native java.time)
        assertThat(loaded.getCustDobYyyyMmDd())
                .as("cust_dob_yyyy_mm_dd (DATE) must round-trip the COBOL "
                        + "CUST-DOB-YYYY-MM-DD PIC X(10) value as a LocalDate "
                        + "per AAP §0.6.1 (LE CEEDAYS replacement with native "
                        + "java.time) — sensitive PII")
                .isEqualTo(LocalDate.of(1985, 6, 15));
    }

    // =========================================================================
    // Test 3 — Customer.toString() masks SSN to last 4 digits (AAP §0.6.6)
    // =========================================================================

    /**
     * Validates that {@link Customer#toString()} MASKS the SSN to its
     * last 4 digits in the canonical {@code "***-**-NNNN"} format,
     * NEVER emitting the full 9-digit raw value.
     *
     * <p>This is a defense-in-depth control per AAP &sect;0.6.6 PCI-DSS:
     * even if Logback redaction patterns in
     * {@code src/main/resources/logback-spring.xml} fail (e.g.,
     * misconfiguration, log appender bypassing the encoder), this
     * entity-layer masking still ensures the raw SSN cannot leak via a
     * stray {@code log.info(customer)} or implicit string concatenation
     * into CloudWatch Logs or OpenSearch.</p>
     *
     * <p>The test deliberately uses a non-persisting code path
     * (build-and-toString) because the masking is an entity-layer
     * concern that does not require database round-trip.</p>
     */
    @Test
    void toString_masksSsnNotFull9Digits_perAap_0_6_6() {
        // AAP §0.6.6 — toString masks SSN per PCI-DSS / PII protection
        Customer customer = buildCustomer(100000003L, "Alice", "Wilson");
        // buildCustomer sets custSsn = 123456789L

        String toString = customer.toString();

        // Negative assertion: the raw 9-digit SSN must NOT appear anywhere
        // in the string output. This is the primary PII-leak guard — if
        // this assertion fails, the entity is leaking raw PII into the
        // string output and PCI-DSS compliance is broken.
        assertThat(toString)
                .as("Customer.toString() MUST NOT contain the raw 9-digit "
                        + "SSN value '123456789' anywhere in its output "
                        + "(AAP §0.6.6 PCI-DSS — defense-in-depth against "
                        + "stray log.info(customer) calls). Actual toString: "
                        + toString)
                .doesNotContain("123456789");

        // Positive assertion: the masked form must appear so that the
        // toString output remains informative for debugging without
        // leaking sensitive data. Per Customer.maskSsn():
        //   String.format("%09d", 123456789L) = "123456789"
        //   "***-**-" + "123456789".substring(5) = "***-**-6789"
        assertThat(toString)
                .as("Customer.toString() MUST contain the masked SSN form "
                        + "'***-**-6789' (last 4 digits only) per the "
                        + "canonical AAP §0.6.6 SSN masking convention")
                .contains("***-**-6789");
    }

    // =========================================================================
    // Test 4 — Customer.toString() omits phone numbers and full DOB (AAP §0.6.6)
    // =========================================================================

    /**
     * Validates that {@link Customer#toString()} OMITS phone numbers and
     * the full date of birth entirely from its string output, per AAP
     * &sect;0.6.6 PCI-DSS.
     *
     * <p>Phone numbers are direct contact channels and DOB combined with
     * name + ZIP triangulates identity (a known re-identification vector
     * per HIPAA and many state privacy laws). Both are therefore
     * OMITTED from the {@code toString()} output entirely rather than
     * being masked &mdash; even a masked DOB ({@code "1985-**-**"})
     * combined with other identifying attributes already in the output
     * (first name, last name, state, ZIP, FICO score) could allow an
     * attacker reading log output to narrow down the customer's
     * identity. Total omission is the most conservative posture and
     * aligns with the Customer entity's Javadoc documentation of the
     * masking policy.</p>
     */
    @Test
    void toString_omitsPhoneNumbersAndFullDob() {
        // AAP §0.6.6 — toString OMITS phone numbers and full DOB
        Customer customer = buildCustomer(100000004L, "Bob", "Brown");
        // Override the nullable phone fields with concrete values so we
        // can verify they would be visible if the toString were
        // misimplemented to include them.
        customer.setCustPhoneNum1("1234567890");
        customer.setCustPhoneNum2("0987654321");
        // buildCustomer already set custDobYyyyMmDd = LocalDate.of(1985, 6, 15)

        String toString = customer.toString();

        // Negative assertion: the primary phone number must NOT appear
        // in the string output.
        assertThat(toString)
                .as("Customer.toString() MUST NOT contain the primary "
                        + "phone number '1234567890' (AAP §0.6.6 PCI-DSS "
                        + "— direct contact channels are PII). Actual "
                        + "toString: " + toString)
                .doesNotContain("1234567890");

        // Negative assertion: the secondary phone number must NOT appear
        // in the string output either.
        assertThat(toString)
                .as("Customer.toString() MUST NOT contain the secondary "
                        + "phone number '0987654321' (AAP §0.6.6 PCI-DSS "
                        + "— direct contact channels are PII). Actual "
                        + "toString: " + toString)
                .doesNotContain("0987654321");

        // Negative assertion: the full date of birth must NOT appear in
        // the string output in any common ISO-8601 or numeric form. The
        // LocalDate.toString() form is "1985-06-15"; we also guard
        // against potential reformatting variants.
        assertThat(toString)
                .as("Customer.toString() MUST NOT contain the full DOB "
                        + "'1985-06-15' (AAP §0.6.6 PCI-DSS — DOB + name "
                        + "+ ZIP triangulates identity). Actual toString: "
                        + toString)
                .doesNotContain("1985-06-15");

        // Defense-in-depth: also guard against the COBOL CUSTREC.cpy
        // 'YYYYMMDD' variant in case any code path inadvertently
        // formats the DOB without hyphens.
        assertThat(toString)
                .as("Customer.toString() MUST NOT contain the DOB in the "
                        + "COBOL CUSTREC.cpy 'YYYYMMDD' form '19850615' "
                        + "either — full DOB omission applies to ALL "
                        + "format variants")
                .doesNotContain("19850615");
    }

    // =========================================================================
    // Test 5 — nullable fields persist with NULL values (V003 contract)
    // =========================================================================

    /**
     * Validates that the V003 nullable columns ({@code cust_middle_name},
     * {@code cust_addr_line_2}, {@code cust_addr_line_3},
     * {@code cust_phone_num_1}, {@code cust_phone_num_2},
     * {@code cust_govt_issued_id}, {@code cust_eft_account_id}) accept
     * {@code null} values and round-trip them faithfully.
     *
     * <p>The COBOL source represents "absent" values as all-spaces in a
     * fixed-width COBOL field; the application layer translates this to
     * PostgreSQL {@code NULL} on ingest, and the entity must accept
     * {@code NULL} on update. This test exercises both the V003 column
     * nullability declaration and the {@link Customer} entity's
     * accessor contract.</p>
     */
    @Test
    void nullableFields_persistsWithNullValues() {
        // V003 — nullable columns per CVCUS01Y record layout
        Customer input = buildCustomer(100000005L, "Carol", "Davis");
        // buildCustomer already sets all nullable fields to null except
        // custGovtIssuedId (which it sets to "DL123456"). Override that
        // here so we exercise all 7 nullable columns at once.
        input.setCustGovtIssuedId(null);

        repository.save(input);
        entityManager.flush();
        entityManager.clear();

        Customer loaded = repository.findById(100000005L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000005 to be present "
                                + "after save + flush + clear cycle"));

        // Verify all 7 nullable columns round-trip as null per V003 schema:
        assertThat(loaded.getCustMiddleName())
                .as("cust_middle_name (VARCHAR(25), nullable) must "
                        + "round-trip null — V003 marks this column nullable "
                        + "for customers with no middle name")
                .isNull();
        assertThat(loaded.getCustAddrLine2())
                .as("cust_addr_line_2 (VARCHAR(50), nullable) must "
                        + "round-trip null — single-family residences "
                        + "without apartment numbers leave this empty")
                .isNull();
        assertThat(loaded.getCustAddrLine3())
                .as("cust_addr_line_3 (VARCHAR(50), nullable) must "
                        + "round-trip null — additional locality lines "
                        + "are typically only used for foreign addresses")
                .isNull();
        assertThat(loaded.getCustPhoneNum1())
                .as("cust_phone_num_1 (VARCHAR(15), nullable) must "
                        + "round-trip null — primary phone is not required")
                .isNull();
        assertThat(loaded.getCustPhoneNum2())
                .as("cust_phone_num_2 (VARCHAR(15), nullable) must "
                        + "round-trip null — secondary phone is rarely used")
                .isNull();
        assertThat(loaded.getCustGovtIssuedId())
                .as("cust_govt_issued_id (VARCHAR(20), nullable) must "
                        + "round-trip null — not every customer has a "
                        + "government-issued ID on file (PII)")
                .isNull();
        assertThat(loaded.getCustEftAccountId())
                .as("cust_eft_account_id (VARCHAR(10), nullable) must "
                        + "round-trip null — not every customer pays via EFT")
                .isNull();
    }

    // =========================================================================
    // Test 6 — fixed-length CHAR(2)/CHAR(3)/CHAR(1) columns
    // =========================================================================

    /**
     * Validates that the V003 fixed-length CHAR columns
     * ({@code cust_addr_state_cd CHAR(2)},
     * {@code cust_addr_country_cd CHAR(3)},
     * {@code cust_pri_card_holder_ind CHAR(1)}) accept and round-trip
     * the exact values written. PostgreSQL CHAR(n) columns space-pad
     * on storage; Hibernate (via the {@code SqlTypes.CHAR} type code
     * declared on the {@link Customer} entity) compensates so that the
     * Java-side value remains unpadded for direct equality comparison.
     */
    @Test
    void fixedLengthCharFields_acceptCorrectLengths() {
        // CHAR(2)/CHAR(3)/CHAR(1) per CVCUS01Y fixed-length fields
        Customer input = buildCustomer(100000006L, "Dave", "Evans");
        // buildCustomer sets:
        //   custAddrStateCd  = "NY"   (CHAR(2))
        //   custAddrCountryCd = "USA" (CHAR(3))
        //   custPriCardHolderInd = "Y" (CHAR(1))

        repository.save(input);
        entityManager.flush();
        entityManager.clear();

        Customer loaded = repository.findById(100000006L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000006 to be present "
                                + "after save + flush + clear cycle"));

        // CUST-ADDR-STATE-CD PIC X(02) -> CHAR(2)
        // Hibernate's SqlTypes.CHAR mapping plus columnDefinition="CHAR(2)"
        // on the entity ensures the Java string is returned without
        // PostgreSQL's storage-layer space-padding (CHAR(n) columns
        // space-pad on the wire but Hibernate strips on read).
        assertThat(loaded.getCustAddrStateCd())
                .as("cust_addr_state_cd (CHAR(2)) must round-trip the "
                        + "exact 2-character US state code 'NY' without "
                        + "trailing padding — per CVCUS01Y CUST-ADDR-STATE-CD "
                        + "PIC X(02)")
                .isEqualTo("NY");
        // CUST-ADDR-COUNTRY-CD PIC X(03) -> CHAR(3)
        assertThat(loaded.getCustAddrCountryCd())
                .as("cust_addr_country_cd (CHAR(3)) must round-trip the "
                        + "exact 3-character ISO country code 'USA' without "
                        + "trailing padding — per CVCUS01Y CUST-ADDR-COUNTRY-CD "
                        + "PIC X(03)")
                .isEqualTo("USA");
        // CUST-PRI-CARD-HOLDER-IND PIC X(01) -> CHAR(1)
        assertThat(loaded.getCustPriCardHolderInd())
                .as("cust_pri_card_holder_ind (CHAR(1)) must round-trip the "
                        + "exact 1-character indicator 'Y' (primary cardholder) "
                        + "— per CVCUS01Y CUST-PRI-CARD-HOLDER-IND PIC X(01)")
                .isEqualTo("Y");
    }

    // =========================================================================
    // Test 7 — FICO credit score 3-digit numeric (V003 CHECK 300..850)
    // =========================================================================

    /**
     * Validates that the {@code cust_fico_credit_score INTEGER NN} column
     * (V003) round-trips a representative mid-range FICO score (750)
     * and accepts the boundary values 300 (the minimum permitted by
     * the V003 {@code CHECK (cust_fico_credit_score BETWEEN 300 AND 850)}
     * constraint) and 850 (the maximum).
     *
     * <p>Although the COBOL source ({@code CVCUS01Y.cpy}:L22) declares
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} which accepts the
     * broader 000-999 range, the Java target enforces the
     * real-world canonical FICO range 300..850 at the schema layer for
     * defense-in-depth (per AAP &sect;0.7.1) &mdash; out-of-range
     * scores would corrupt downstream credit-limit calculations
     * ({@code InterestCalculationService}) and disclosure-group
     * lookups ({@code DisclosureGroupRepository}). This test asserts
     * the V003 constraint accepts 300, 750, and 850 without rejection.</p>
     */
    @Test
    void ficoCreditScore_3DigitNumeric() {
        // Representative mid-range score
        Customer mid = buildCustomer(100000007L, "Eve", "Foster");
        mid.setCustFicoCreditScore(750);

        // Lower-boundary score — minimum value accepted by V003 CHECK constraint
        Customer low = buildCustomer(100000008L, "Frank", "Green");
        low.setCustFicoCreditScore(300);

        // Upper-boundary score — maximum value accepted by V003 CHECK constraint
        Customer high = buildCustomer(100000009L, "Grace", "Harris");
        high.setCustFicoCreditScore(850);

        repository.save(mid);
        repository.save(low);
        repository.save(high);
        entityManager.flush();
        entityManager.clear();

        // Mid-range 750
        Customer loadedMid = repository.findById(100000007L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000007 (FICO 750) "
                                + "to be present after save + flush + clear cycle"));
        assertThat(loadedMid.getCustFicoCreditScore())
                .as("cust_fico_credit_score (INTEGER NN) must round-trip "
                        + "mid-range value 750 unchanged — per CVCUS01Y "
                        + "CUST-FICO-CREDIT-SCORE PIC 9(03)")
                .isEqualTo(750);

        // Lower boundary 300 — minimum FICO score per V003 CHECK constraint
        Customer loadedLow = repository.findById(100000008L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000008 (FICO 300) "
                                + "to be present after save + flush + clear cycle"));
        assertThat(loadedLow.getCustFicoCreditScore())
                .as("cust_fico_credit_score must accept boundary value 300 "
                        + "— V003 declares CHECK (cust_fico_credit_score "
                        + "BETWEEN 300 AND 850); 300 is the inclusive minimum")
                .isEqualTo(300);

        // Upper boundary 850 — maximum FICO score per V003 CHECK constraint
        Customer loadedHigh = repository.findById(100000009L)
                .orElseThrow(() -> new AssertionError(
                        "Expected Customer with custId 100000009 (FICO 850) "
                                + "to be present after save + flush + clear cycle"));
        assertThat(loadedHigh.getCustFicoCreditScore())
                .as("cust_fico_credit_score must accept boundary value 850 "
                        + "— V003 declares CHECK (cust_fico_credit_score "
                        + "BETWEEN 300 AND 850); 850 is the inclusive maximum")
                .isEqualTo(850);
    }

    // =========================================================================
    // Test 8 — deleteById removes the customer row
    // =========================================================================

    /**
     * Validates the {@code deleteById} contract for
     * {@link CustomerRepository}: a previously persisted customer can
     * be deleted by primary key, and a subsequent
     * {@code findById(sameId)} returns {@link java.util.Optional#empty()}.
     *
     * <p>Replaces the COBOL pattern of
     * {@code EXEC CICS DELETE DATASET('CUSTDATA') RIDFLD(CUST-ID)} on
     * the VSAM KSDS. Note that production CardDemo retains customer
     * records indefinitely for regulatory audit purposes (per the
     * {@link CustomerRepository} Javadoc); this {@code deleteById}
     * capability is exposed for operational cleanup and test-fixture
     * teardown rather than for routine business operations.</p>
     */
    @Test
    void deleteById_removesCustomer() {
        // First persist a fresh customer so we have a known row to delete.
        Customer input = buildCustomer(100000010L, "Henry", "Iverson");
        repository.save(input);
        entityManager.flush();
        entityManager.clear();
        // Sanity check — confirm the row is present before delete so the
        // assertion below cannot pass trivially for a row that never
        // existed.
        assertThat(repository.findById(100000010L))
                .as("Pre-delete sanity check — Customer 100000010 must be "
                        + "present after the save+flush+clear cycle")
                .isPresent();

        // Now delete by primary key.
        repository.deleteById(100000010L);
        // Force the DELETE SQL to the DB so the subsequent findById()
        // executes a SELECT against a row that has actually been removed.
        entityManager.flush();
        entityManager.clear();

        // Post-delete assertion — Optional.empty() per the JpaRepository
        // contract, NOT a thrown exception (which would replace COBOL
        // FILE STATUS 23 NOTFND -> RecordNotFoundException at the
        // service layer per the CustomerRepository Javadoc).
        assertThat(repository.findById(100000010L))
                .as("findById(100000010L) must return Optional.empty() after "
                        + "deleteById — replaces the COBOL CICS DELETE "
                        + "DATASET('CUSTDATA') RIDFLD(CUST-ID) pattern")
                .isEmpty();
    }

    // =========================================================================
    // Test 9 — count() returns the row cardinality after multi-row save
    // =========================================================================

    /**
     * Validates that {@link CustomerRepository#count()} returns the
     * exact row count after a multi-row save sequence.
     *
     * <p>V003 does NOT seed customer master data (per AAP &sect;0.6.2,
     * bulk fact data is loaded by AWS Glue Spark jobs in production
     * and by per-test fixtures in tests), so each
     * {@code @DataJpaTest} method begins with zero rows in the
     * {@code customers} table. After persisting 3 rows the count must
     * be exactly 3. This contract is what allows downstream services
     * (operational dashboards, Spring Batch progress reporting) to
     * report accurate row counts.</p>
     *
     * <p>Replaces the COBOL pattern of
     * {@code EXEC CICS STARTBR DATASET('CUSTDATA') ... READNEXT}
     * counting iterations &mdash; the relational equivalent is the
     * inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#count()
     * count()} method.</p>
     */
    @Test
    void count_returnsRowCount() {
        // V003 does NOT seed customer rows (per AAP §0.6.2) so the count
        // before our saves should be zero. We snapshot the baseline rather
        // than asserting exactly 0 to remain robust against any future
        // baseline change (e.g., if a downstream migration adds a seed).
        long baselineCount = repository.count();

        // Persist three independent customer rows with distinct PKs.
        repository.save(buildCustomer(100000011L, "Ivan", "Johnson"));
        repository.save(buildCustomer(100000012L, "Julia", "Kim"));
        repository.save(buildCustomer(100000013L, "Karl", "Lopez"));
        // Force flush so the count() SQL sees the three INSERTs.
        entityManager.flush();
        entityManager.clear();

        long actualCount = repository.count();

        // Assert exact delta of 3 from baseline. Equivalent to the COBOL
        // pattern where CBCUS01C counts records via WS-ITERATIONS = 0,
        // STARTBR, READNEXT until EOF, ADD 1 TO WS-ITERATIONS.
        assertThat(actualCount)
                .as("After persisting 3 customers, count() must equal "
                        + "baseline (%d) + 3 = %d — replaces the COBOL CBCUS01C "
                        + "STARTBR + READNEXT counting pattern with the "
                        + "inherited JpaRepository.count() method",
                        baselineCount, baselineCount + 3L)
                .isEqualTo(baselineCount + 3L);
    }
}
