package com.cardemo.integration.repository;

import com.cardemo.model.entity.Customer;
import com.cardemo.repository.CustomerRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link CustomerRepository} verifying standard JPA CRUD
 * operations against a real PostgreSQL 16 instance via Testcontainers.
 *
 * <p>Validates the {@link Customer} entity mapping from the COBOL CUSTDAT VSAM
 * KSDS dataset — the largest record in the CardDemo system at 500 bytes
 * (CVCUS01Y.cpy / CUSTREC.cpy). This test ensures that all 18 mapped fields
 * (excluding the 168-byte FILLER) round-trip correctly through JPA/Hibernate
 * to PostgreSQL and back.</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS)
 *     KEYS(9 0)
 *     RECORDSIZE(500 500)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVCUS01Y.cpy — 500 bytes)</h3>
 * <pre>
 * 01  CUSTOMER-RECORD.
 *     05  CUST-ID                    PIC 9(09).    — PK (cust_id VARCHAR(9))
 *     05  CUST-FIRST-NAME            PIC X(25).    — first_name VARCHAR(25)
 *     05  CUST-MIDDLE-NAME           PIC X(25).    — middle_name VARCHAR(25)
 *     05  CUST-LAST-NAME             PIC X(25).    — last_name VARCHAR(25)
 *     05  CUST-ADDR-LINE-1           PIC X(50).    — addr_line_1 VARCHAR(50)
 *     05  CUST-ADDR-LINE-2           PIC X(50).    — addr_line_2 VARCHAR(50)
 *     05  CUST-ADDR-LINE-3           PIC X(50).    — addr_line_3 VARCHAR(50)
 *     05  CUST-ADDR-STATE-CD         PIC X(02).    — addr_state_cd CHAR(2)
 *     05  CUST-ADDR-COUNTRY-CD       PIC X(03).    — addr_country_cd CHAR(3)
 *     05  CUST-ADDR-ZIP              PIC X(10).    — addr_zip VARCHAR(10)
 *     05  CUST-PHONE-NUM-1           PIC X(15).    — phone_num_1 VARCHAR(15)
 *     05  CUST-PHONE-NUM-2           PIC X(15).    — phone_num_2 VARCHAR(15)
 *     05  CUST-SSN                   PIC 9(09).    — ssn VARCHAR(9)
 *     05  CUST-GOVT-ISSUED-ID        PIC X(20).    — govt_issued_id VARCHAR(20)
 *     05  CUST-DOB-YYYY-MM-DD        PIC X(10).    — dob DATE
 *     05  CUST-EFT-ACCOUNT-ID        PIC X(10).    — eft_account_id VARCHAR(10)
 *     05  CUST-PRI-CARD-HOLDER-IND   PIC X(01).    — pri_card_holder_ind CHAR(1)
 *     05  CUST-FICO-CREDIT-SCORE     PIC 9(03).    — fico_credit_score SMALLINT
 *     05  FILLER                     PIC X(168).   — not mapped
 * </pre>
 *
 * <h3>Seed Data (custdata.txt — 50 records)</h3>
 * <p>First customer: ID=000000001, Immanuel Madeline Kessler, 618 Deshaun Route,
 * NC, USA, SSN=020973888, DOB=1961-06-08, FICO=274</p>
 *
 * <p>All tests run against a real PostgreSQL 16 instance via Testcontainers with
 * Flyway migrations provisioning the schema (V1__create_schema.sql) and seed data
 * (V3__seed_data.sql — 50 customer records parsed from
 * {@code app/data/ASCII/custdata.txt}).</p>
 *
 * <p>COBOL source reference: {@code app/jcl/CUSTFILE.jcl}, {@code app/cpy/CVCUS01Y.cpy},
 * {@code app/cpy/CUSTREC.cpy}, and {@code app/data/ASCII/custdata.txt} from commit
 * {@code 27d6c6f}.</p>
 *
 * @see Customer
 * @see CustomerRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("CustomerRepository Integration Tests — CUSTDAT VSAM KSDS (500-byte record)")
public class CustomerRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for CUSTDATA.VSAM.KSDS
    // KEYS(9 0) RECORDSIZE(500 500) INDEXED
    // -----------------------------------------------------------------------

    @Container
    static PostgreSQLContainer postgresContainer =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /**
     * Injects Testcontainers PostgreSQL connection properties into the Spring
     * Environment, overriding the static {@code jdbc:tc:} URL from
     * {@code application-test.yml} with the dynamically allocated container URL.
     *
     * <p>This ensures Flyway migrations run against the real PostgreSQL container
     * and Hibernate validates entity mappings against the Flyway-created schema.</p>
     *
     * @param registry the dynamic property registry for runtime property injection
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Disable autoCommit so @DataJpaTest @Transactional rollback works correctly
        // with PostgreSQL — HikariCP defaults to autoCommit=true which prevents rollback
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
    }

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById for existing customer (first seed record)
    // Verifies keyed read — equivalent to COBOL READ CUSTDAT KEY("000000001")
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("000000001")} returns the first customer
     * seeded from {@code custdata.txt} via Flyway V3 migration.
     *
     * <p>Maps COBOL keyed read: {@code READ CUSTDAT-FILE INTO CUSTOMER-RECORD
     * KEY IS '000000001'}. The PK is CUST-ID PIC 9(09) at KEYS(9,0).</p>
     *
     * <p>Assertions verify critical field mappings:</p>
     * <ul>
     *   <li>custId preserves leading zeros (PIC 9(09) → String)</li>
     *   <li>Name fields are trimmed from PIC X(25)</li>
     *   <li>State code is 2-char (PIC X(02))</li>
     *   <li>SSN is a 9-digit String (PIC 9(09), not numeric type)</li>
     *   <li>FICO score is Short (PIC 9(03))</li>
     *   <li>DOB is a valid LocalDate (PIC X(10) date string)</li>
     * </ul>
     */
    @Test
    @DisplayName("findById('000000001') returns first customer — Immanuel Kessler")
    void testFindById_ExistingCustomer() {
        // Act — equivalent to COBOL READ CUSTDAT with KEY='000000001'
        Optional<Customer> result = customerRepository.findById("000000001");

        // Assert — verify presence
        assertThat(result)
                .as("Customer '000000001' (Immanuel Kessler) should be present in seed data")
                .isPresent();

        Customer customer = result.get();

        // Verify primary key — PIC 9(09) with leading zeros preserved
        assertThat(customer.getCustId())
                .as("Customer ID should be '000000001' with leading zeros preserved (PIC 9(09))")
                .isEqualTo("000000001")
                .hasSize(9);

        // Verify name fields — trimmed from PIC X(25)
        assertThat(customer.getCustFirstName())
                .as("First name should be 'Immanuel' (trimmed from PIC X(25))")
                .isEqualTo("Immanuel");

        assertThat(customer.getCustLastName())
                .as("Last name should be 'Kessler' (trimmed from PIC X(25))")
                .isEqualTo("Kessler");

        // Verify state code — PIC X(02)
        assertThat(customer.getCustAddrStateCd())
                .as("State code should be a 2-character code (PIC X(02))")
                .isNotNull()
                .satisfies(stateCd -> assertThat(stateCd.trim()).hasSize(2));

        // Verify SSN — PIC 9(09), privacy-sensitive, stored as String
        assertThat(customer.getCustSsn())
                .as("SSN should be a 9-digit string (PIC 9(09) — String not numeric)")
                .isNotNull()
                .matches("\\d{9}");

        // Verify FICO credit score — PIC 9(03), Short type
        assertThat(customer.getCustFicoCreditScore())
                .as("FICO credit score should be a non-null Short value (PIC 9(03))")
                .isNotNull();
        assertThat(customer.getCustFicoCreditScore().intValue())
                .as("FICO score should be within valid range")
                .isGreaterThanOrEqualTo(0)
                .isLessThanOrEqualTo(999);

        // Verify DOB — PIC X(10) date string → LocalDate
        assertThat(customer.getCustDob())
                .as("Date of birth should be a valid LocalDate (PIC X(10) → LocalDate)")
                .isNotNull()
                .isInstanceOf(LocalDate.class);
    }

    // -----------------------------------------------------------------------
    // Test 2: findById for non-existent customer
    // Verifies empty result — equivalent to COBOL READ with FILE STATUS '23'
    // (INVALID KEY / record not found)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("999999999")} returns an empty {@code Optional}
     * for a non-existent customer ID.
     *
     * <p>Maps COBOL FILE STATUS '23' (INVALID KEY — record not found) for the
     * CUSTDAT VSAM KSDS dataset when the requested key does not exist.</p>
     */
    @Test
    @DisplayName("findById('999999999') returns empty Optional for non-existent customer")
    void testFindById_NonExistent() {
        // Act — attempt to read a customer ID that does not exist in seed data
        Optional<Customer> result = customerRepository.findById("999999999");

        // Assert — result should be empty (FILE STATUS '23' equivalent)
        assertThat(result)
                .as("Customer '999999999' does not exist — findById should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 3: Save and retrieve a new customer
    // Verifies create operation — equivalent to COBOL WRITE CUSTDAT
    // Validates all 18 fields round-trip through JPA → PostgreSQL → JPA
    // -----------------------------------------------------------------------

    /**
     * Verifies that a new Customer with all 18+ fields populated can be saved
     * and retrieved with all fields intact. Uses flush/clear to force a true
     * database round-trip (bypasses JPA first-level cache).
     *
     * <p>Maps COBOL WRITE: {@code WRITE CUSTOMER-RECORD FROM WS-CUSTOMER-RECORD}.
     * All fields from the 500-byte record layout (CVCUS01Y.cpy) must survive
     * the JPA → PostgreSQL → JPA round-trip with exact values preserved.</p>
     */
    @Test
    @DisplayName("save() and findById() round-trip all 18 fields of 500-byte customer record")
    void testSaveAndRetrieve() {
        // Arrange — create a new customer with all 18 mapped fields populated
        // Uses no-args constructor + setters to exercise all setter methods
        Customer newCustomer = new Customer();
        newCustomer.setCustId("900000001");
        newCustomer.setCustFirstName("TestFirst");
        newCustomer.setCustMiddleName("TestMiddle");
        newCustomer.setCustLastName("TestLast");
        newCustomer.setCustAddrLine1("123 Test Street");
        newCustomer.setCustAddrLine2("Suite 100");
        newCustomer.setCustAddrLine3("Testville");
        newCustomer.setCustAddrStateCd("TX");
        newCustomer.setCustAddrCountryCd("USA");
        newCustomer.setCustAddrZip("75001");
        newCustomer.setCustPhoneNum1("(214)555-0100");
        newCustomer.setCustPhoneNum2("(972)555-0200");
        newCustomer.setCustSsn("123456789");
        newCustomer.setCustGovtIssuedId("DL12345678");
        newCustomer.setCustDob(LocalDate.of(1985, 7, 15));
        newCustomer.setCustEftAccountId("9876543210");
        newCustomer.setCustPriCardHolderInd("Y");
        newCustomer.setCustFicoCreditScore((short) 720);

        // Act — save, flush to PostgreSQL, clear JPA cache, retrieve from database
        customerRepository.save(newCustomer);
        entityManager.flush();
        entityManager.clear();

        Optional<Customer> result = customerRepository.findById("900000001");

        // Assert — verify the customer was persisted and all fields survived round-trip
        assertThat(result)
                .as("Saved customer '900000001' should be retrievable after flush/clear")
                .isPresent();

        Customer retrieved = result.get();

        // Verify every field individually for 500-byte record mapping accuracy
        assertThat(retrieved.getCustId())
                .as("custId round-trip")
                .isEqualTo("900000001");
        assertThat(retrieved.getCustFirstName())
                .as("custFirstName round-trip")
                .isEqualTo("TestFirst");
        assertThat(retrieved.getCustMiddleName())
                .as("custMiddleName round-trip")
                .isEqualTo("TestMiddle");
        assertThat(retrieved.getCustLastName())
                .as("custLastName round-trip")
                .isEqualTo("TestLast");
        assertThat(retrieved.getCustAddrLine1())
                .as("custAddrLine1 round-trip")
                .isEqualTo("123 Test Street");
        assertThat(retrieved.getCustAddrLine2())
                .as("custAddrLine2 round-trip")
                .isEqualTo("Suite 100");
        assertThat(retrieved.getCustAddrLine3())
                .as("custAddrLine3 round-trip")
                .isEqualTo("Testville");
        assertThat(retrieved.getCustAddrStateCd())
                .as("custAddrStateCd round-trip (CHAR(2))")
                .isNotNull();
        assertThat(retrieved.getCustAddrStateCd().trim())
                .as("custAddrStateCd trimmed value")
                .isEqualTo("TX");
        assertThat(retrieved.getCustAddrCountryCd())
                .as("custAddrCountryCd round-trip (CHAR(3))")
                .isNotNull();
        assertThat(retrieved.getCustAddrCountryCd().trim())
                .as("custAddrCountryCd trimmed value")
                .isEqualTo("USA");
        assertThat(retrieved.getCustAddrZip())
                .as("custAddrZip round-trip")
                .isEqualTo("75001");
        assertThat(retrieved.getCustPhoneNum1())
                .as("custPhoneNum1 round-trip")
                .isEqualTo("(214)555-0100");
        assertThat(retrieved.getCustPhoneNum2())
                .as("custPhoneNum2 round-trip")
                .isEqualTo("(972)555-0200");
        assertThat(retrieved.getCustSsn())
                .as("custSsn round-trip (9-char String)")
                .isEqualTo("123456789");
        assertThat(retrieved.getCustGovtIssuedId())
                .as("custGovtIssuedId round-trip")
                .isEqualTo("DL12345678");
        assertThat(retrieved.getCustDob())
                .as("custDob round-trip (LocalDate)")
                .isEqualTo(LocalDate.of(1985, 7, 15));
        assertThat(retrieved.getCustEftAccountId())
                .as("custEftAccountId round-trip")
                .isEqualTo("9876543210");
        assertThat(retrieved.getCustPriCardHolderInd())
                .as("custPriCardHolderInd round-trip (CHAR(1))")
                .isNotNull();
        assertThat(retrieved.getCustPriCardHolderInd().trim())
                .as("custPriCardHolderInd trimmed value")
                .isEqualTo("Y");
        assertThat(retrieved.getCustFicoCreditScore())
                .as("custFicoCreditScore round-trip (Short)")
                .isEqualTo((short) 720);
    }

    // -----------------------------------------------------------------------
    // Test 4: Update existing customer
    // Verifies rewrite operation — equivalent to COBOL REWRITE CUSTDAT
    // -----------------------------------------------------------------------

    /**
     * Verifies that modifying an existing customer's address fields and saving
     * persists the changes correctly through a true database round-trip.
     *
     * <p>Maps COBOL REWRITE: {@code REWRITE CUSTOMER-RECORD FROM WS-CUSTOMER-RECORD}.
     * The test modifies address fields (CUST-ADDR-LINE-1, CUST-ADDR-LINE-2,
     * CUST-ADDR-STATE-CD, CUST-ADDR-ZIP) and verifies the updated values
     * survive flush/clear/findById.</p>
     */
    @Test
    @DisplayName("save() updates existing customer address fields")
    void testUpdateCustomer() {
        // Arrange — find existing customer from seed data
        Optional<Customer> existing = customerRepository.findById("000000001");
        assertThat(existing)
                .as("Customer '000000001' must exist in seed data for update test")
                .isPresent();

        Customer customer = existing.get();

        // Record original values for comparison
        String originalFirstName = customer.getCustFirstName();

        // Modify address fields — simulating a customer address change
        customer.setCustAddrLine1("999 Updated Boulevard");
        customer.setCustAddrLine2("Floor 42");
        customer.setCustAddrLine3("Updated City");
        customer.setCustAddrStateCd("CA");
        customer.setCustAddrZip("90210");

        // Act — save updated customer, flush to database, clear JPA cache
        customerRepository.save(customer);
        entityManager.flush();
        entityManager.clear();

        // Retrieve the customer again from the database
        Optional<Customer> updated = customerRepository.findById("000000001");

        // Assert — verify updated fields reflect changes
        assertThat(updated)
                .as("Updated customer '000000001' should still exist")
                .isPresent();

        Customer updatedCustomer = updated.get();

        // Verify updated fields
        assertThat(updatedCustomer.getCustAddrLine1())
                .as("Address line 1 should be updated")
                .isEqualTo("999 Updated Boulevard");
        assertThat(updatedCustomer.getCustAddrLine2())
                .as("Address line 2 should be updated")
                .isEqualTo("Floor 42");
        assertThat(updatedCustomer.getCustAddrLine3())
                .as("Address line 3 should be updated")
                .isEqualTo("Updated City");
        assertThat(updatedCustomer.getCustAddrStateCd())
                .as("State code should be updated (CHAR(2))")
                .isNotNull();
        assertThat(updatedCustomer.getCustAddrStateCd().trim())
                .as("State code trimmed value should be updated")
                .isEqualTo("CA");
        assertThat(updatedCustomer.getCustAddrZip())
                .as("ZIP code should be updated")
                .isEqualTo("90210");

        // Verify non-updated fields remain unchanged
        assertThat(updatedCustomer.getCustFirstName())
                .as("First name should not have changed during address update")
                .isEqualTo(originalFirstName);
        assertThat(updatedCustomer.getCustId())
                .as("Customer ID should not change during update")
                .isEqualTo("000000001");
    }

    // -----------------------------------------------------------------------
    // Test 5: Delete a customer
    // Verifies delete operation — equivalent to COBOL DELETE CUSTDAT
    // -----------------------------------------------------------------------

    /**
     * Verifies that a customer can be created, saved, and then deleted with
     * the deletion persisted to the database.
     *
     * <p>Maps COBOL DELETE: {@code DELETE CUSTDAT-FILE RECORD}. The test creates
     * a new customer (to avoid affecting seed data assertions in other tests),
     * saves it, confirms it exists, deletes it, and verifies it is no longer
     * retrievable.</p>
     */
    @Test
    @DisplayName("delete() removes customer and findById returns empty")
    void testDeleteCustomer() {
        // Arrange — create a new customer specifically for deletion testing
        Customer toDelete = new Customer();
        toDelete.setCustId("800000001");
        toDelete.setCustFirstName("DeleteMe");
        toDelete.setCustMiddleName("Test");
        toDelete.setCustLastName("Customer");
        toDelete.setCustAddrLine1("1 Delete Lane");
        toDelete.setCustAddrStateCd("NY");
        toDelete.setCustAddrCountryCd("USA");
        toDelete.setCustAddrZip("10001");
        toDelete.setCustSsn("999888777");
        toDelete.setCustDob(LocalDate.of(1990, 1, 1));
        toDelete.setCustFicoCreditScore((short) 650);
        toDelete.setCustPriCardHolderInd("N");

        // Save and verify the customer exists
        customerRepository.save(toDelete);
        entityManager.flush();
        entityManager.clear();

        assertThat(customerRepository.findById("800000001"))
                .as("Customer '800000001' should exist after save")
                .isPresent();

        // Act — delete the customer
        Customer managed = customerRepository.findById("800000001").get();
        customerRepository.delete(managed);
        entityManager.flush();
        entityManager.clear();

        // Assert — customer should no longer exist (FILE STATUS '23' equivalent)
        Optional<Customer> afterDelete = customerRepository.findById("800000001");
        assertThat(afterDelete)
                .as("Customer '800000001' should not exist after delete")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 6: findAll returns all 50 seed records
    // Verifies sequential browse — equivalent to COBOL BROWSE CUSTDAT
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findAll()} returns exactly 50 customer records
     * matching the seed data from {@code custdata.txt}.
     *
     * <p>Maps COBOL sequential read (CBCUS01C.cbl batch customer file reader).
     * Each record must have a valid 9-character customer ID preserving
     * leading zeros, matching the COBOL PIC 9(09) layout.</p>
     */
    @Test
    @DisplayName("findAll() returns exactly 50 seeded customer records from custdata.txt")
    void testFindAll() {
        // Act — equivalent to COBOL sequential browse of CUSTDAT file
        List<Customer> allCustomers = customerRepository.findAll();

        // Assert — verify count matches 50 seed records from custdata.txt
        assertThat(allCustomers)
                .as("findAll() should return exactly 50 customer records from custdata.txt")
                .hasSize(50);

        // Verify every record has a valid 9-character customer ID
        // PIC 9(09) requires leading zeros — stored as String, not numeric
        assertThat(allCustomers)
                .as("Every customer record must have a 9-character custId")
                .allSatisfy(customer -> {
                    assertThat(customer.getCustId())
                            .as("custId should be exactly 9 characters (PIC 9(09))")
                            .isNotNull()
                            .hasSize(9);
                    assertThat(customer.getCustId())
                            .as("custId should contain only digits (PIC 9(09))")
                            .matches("\\d{9}");
                });

        // Verify count() method as well
        long count = customerRepository.count();
        assertThat(count)
                .as("count() should return 50 matching seed data record count")
                .isEqualTo(50L);
    }

    // -----------------------------------------------------------------------
    // Test 7: Complete 500-byte record mapping accuracy
    // CRITICAL — validates all 18 fields from CVCUS01Y.cpy are properly mapped
    // -----------------------------------------------------------------------

    /**
     * Validates the complete 500-byte COBOL record mapping accuracy by verifying
     * ALL 18 mapped fields of the first customer record (custId "000000001").
     *
     * <p>This is the most critical test in the suite — it ensures that the JPA
     * entity mapping from the 500-byte CVCUS01Y.cpy record layout to the
     * PostgreSQL {@code customers} table preserves every field with correct
     * type, length, and content.</p>
     *
     * <h4>Field-by-field validation:</h4>
     * <pre>
     * Field                    COBOL PIC      Java Type   Max Len  Constraint
     * ─────────────────────────────────────────────────────────────────────────
     * custId                   PIC 9(09)      String      9        PK, leading zeros
     * custFirstName            PIC X(25)      String      25       NOT NULL
     * custMiddleName           PIC X(25)      String      25
     * custLastName             PIC X(25)      String      25       NOT NULL
     * custAddrLine1            PIC X(50)      String      50
     * custAddrLine2            PIC X(50)      String      50
     * custAddrLine3            PIC X(50)      String      50
     * custAddrStateCd          PIC X(02)      String      2        CHAR(2)
     * custAddrCountryCd        PIC X(03)      String      3        CHAR(3)
     * custAddrZip              PIC X(10)      String      10
     * custPhoneNum1            PIC X(15)      String      15
     * custPhoneNum2            PIC X(15)      String      15
     * custSsn                  PIC 9(09)      String      9        Sensitive PII
     * custGovtIssuedId         PIC X(20)      String      20
     * custDob                  PIC X(10)      LocalDate   —        DATE type
     * custEftAccountId         PIC X(10)      String      10
     * custPriCardHolderInd     PIC X(01)      String      1        CHAR(1), Y/N
     * custFicoCreditScore      PIC 9(03)      Short       —        SMALLINT 0-999
     * </pre>
     *
     * <p>Seed data for customer '000000001' from V3__seed_data.sql:</p>
     * <pre>
     * cust_id='000000001', first_name='Immanuel', middle_name='Madeline',
     * last_name='Kessler', addr_line_1='618 Deshaun Route', addr_line_2='Apt. 802',
     * addr_line_3='Altenwerthshire', state_cd='NC', country_cd='USA', zip='27546',
     * phone_1='(908)119-8310', phone_2='(445)693-8684', ssn='020973888',
     * govt_id='00000000000049368437', dob='1961-06-08', eft='0053581756',
     * pri_card='Y', fico=688
     * </pre>
     */
    @Test
    @DisplayName("500-byte record mapping — all 18 fields verified for first customer")
    void testRecordMappingAccuracy_500ByteRecord() {
        // Act — retrieve first customer from seed data
        Optional<Customer> result = customerRepository.findById("000000001");

        assertThat(result)
                .as("Customer '000000001' should be present for 500-byte mapping test")
                .isPresent();

        Customer customer = result.get();

        // ---- Field 1: custId — PIC 9(09), 9 chars, leading zeros ----
        assertThat(customer.getCustId())
                .as("custId: PIC 9(09), 9 chars, leading zeros preserved")
                .isNotNull()
                .isEqualTo("000000001")
                .hasSize(9);

        // ---- Field 2: custFirstName — PIC X(25), max 25 chars ----
        assertThat(customer.getCustFirstName())
                .as("custFirstName: PIC X(25), max 25 chars, value='Immanuel'")
                .isNotNull()
                .isEqualTo("Immanuel")
                .hasSizeLessThanOrEqualTo(25);

        // ---- Field 3: custMiddleName — PIC X(25), max 25 chars ----
        assertThat(customer.getCustMiddleName())
                .as("custMiddleName: PIC X(25), max 25 chars, value='Madeline'")
                .isNotNull()
                .isEqualTo("Madeline")
                .hasSizeLessThanOrEqualTo(25);

        // ---- Field 4: custLastName — PIC X(25), max 25 chars ----
        assertThat(customer.getCustLastName())
                .as("custLastName: PIC X(25), max 25 chars, value='Kessler'")
                .isNotNull()
                .isEqualTo("Kessler")
                .hasSizeLessThanOrEqualTo(25);

        // ---- Field 5: custAddrLine1 — PIC X(50), max 50 chars ----
        assertThat(customer.getCustAddrLine1())
                .as("custAddrLine1: PIC X(50), max 50 chars")
                .isNotNull()
                .isEqualTo("618 Deshaun Route")
                .hasSizeLessThanOrEqualTo(50);

        // ---- Field 6: custAddrLine2 — PIC X(50), max 50 chars ----
        assertThat(customer.getCustAddrLine2())
                .as("custAddrLine2: PIC X(50), max 50 chars")
                .isNotNull()
                .isEqualTo("Apt. 802")
                .hasSizeLessThanOrEqualTo(50);

        // ---- Field 7: custAddrLine3 — PIC X(50), max 50 chars ----
        assertThat(customer.getCustAddrLine3())
                .as("custAddrLine3: PIC X(50), max 50 chars")
                .isNotNull()
                .isEqualTo("Altenwerthshire")
                .hasSizeLessThanOrEqualTo(50);

        // ---- Field 8: custAddrStateCd — PIC X(02), CHAR(2) ----
        assertThat(customer.getCustAddrStateCd())
                .as("custAddrStateCd: PIC X(02), CHAR(2)")
                .isNotNull();
        assertThat(customer.getCustAddrStateCd().trim())
                .as("custAddrStateCd trimmed: should be 'NC' (2 chars)")
                .isEqualTo("NC")
                .hasSize(2);

        // ---- Field 9: custAddrCountryCd — PIC X(03), CHAR(3) ----
        assertThat(customer.getCustAddrCountryCd())
                .as("custAddrCountryCd: PIC X(03), CHAR(3)")
                .isNotNull();
        assertThat(customer.getCustAddrCountryCd().trim())
                .as("custAddrCountryCd trimmed: should be 'USA' (3 chars)")
                .isEqualTo("USA")
                .hasSize(3);

        // ---- Field 10: custAddrZip — PIC X(10), max 10 chars ----
        assertThat(customer.getCustAddrZip())
                .as("custAddrZip: PIC X(10), max 10 chars")
                .isNotNull()
                .isEqualTo("27546")
                .hasSizeLessThanOrEqualTo(10);

        // ---- Field 11: custPhoneNum1 — PIC X(15), max 15 chars ----
        assertThat(customer.getCustPhoneNum1())
                .as("custPhoneNum1: PIC X(15), max 15 chars")
                .isNotNull()
                .isEqualTo("(908)119-8310")
                .hasSizeLessThanOrEqualTo(15);

        // ---- Field 12: custPhoneNum2 — PIC X(15), max 15 chars ----
        assertThat(customer.getCustPhoneNum2())
                .as("custPhoneNum2: PIC X(15), max 15 chars")
                .isNotNull()
                .isEqualTo("(445)693-8684")
                .hasSizeLessThanOrEqualTo(15);

        // ---- Field 13: custSsn — PIC 9(09), 9-char String (sensitive PII) ----
        assertThat(customer.getCustSsn())
                .as("custSsn: PIC 9(09), 9-char String (SSN as String, not numeric)")
                .isNotNull()
                .isEqualTo("020973888")
                .hasSize(9)
                .matches("\\d{9}");

        // ---- Field 14: custGovtIssuedId — PIC X(20), max 20 chars ----
        assertThat(customer.getCustGovtIssuedId())
                .as("custGovtIssuedId: PIC X(20), max 20 chars")
                .isNotNull()
                .isEqualTo("00000000000049368437")
                .hasSizeLessThanOrEqualTo(20);

        // ---- Field 15: custDob — PIC X(10), LocalDate ----
        assertThat(customer.getCustDob())
                .as("custDob: PIC X(10) date → LocalDate, value=1961-06-08")
                .isNotNull()
                .isEqualTo(LocalDate.of(1961, 6, 8));

        // ---- Field 16: custEftAccountId — PIC X(10), max 10 chars ----
        assertThat(customer.getCustEftAccountId())
                .as("custEftAccountId: PIC X(10), max 10 chars")
                .isNotNull()
                .isEqualTo("0053581756")
                .hasSizeLessThanOrEqualTo(10);

        // ---- Field 17: custPriCardHolderInd — PIC X(01), CHAR(1) ----
        assertThat(customer.getCustPriCardHolderInd())
                .as("custPriCardHolderInd: PIC X(01), CHAR(1)")
                .isNotNull();
        assertThat(customer.getCustPriCardHolderInd().trim())
                .as("custPriCardHolderInd trimmed: should be 'Y' (1 char)")
                .isEqualTo("Y")
                .hasSize(1);

        // ---- Field 18: custFicoCreditScore — PIC 9(03), Short 0-999 ----
        assertThat(customer.getCustFicoCreditScore())
                .as("custFicoCreditScore: PIC 9(03), Short (SMALLINT), value=688")
                .isNotNull()
                .isEqualTo((short) 688);
        assertThat(customer.getCustFicoCreditScore().intValue())
                .as("custFicoCreditScore should be within valid range 0-999")
                .isGreaterThanOrEqualTo(0)
                .isLessThanOrEqualTo(999);
    }
}
