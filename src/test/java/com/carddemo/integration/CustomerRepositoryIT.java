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
package com.carddemo.integration;

import com.carddemo.entity.Customer;
import com.carddemo.repository.CustomerRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration test for {@link CustomerRepository} executed against a <em>real</em>
 * PostgreSQL&nbsp;16 Testcontainer (Flyway-seeded), inherited from
 * {@link AbstractIntegrationIT}. No mock, no H2, no hardcoded port — the suite must
 * exercise the production persistence contract so the VSAM-fidelity guarantees behind
 * Validation Gates&nbsp;1, 4 and 5 are real.
 *
 * <h2>What legacy artifact this verifies</h2>
 * The {@code customers} table and {@link Customer} entity replace the VSAM
 * {@code CUSTFILE} KSDS defined by {@code app/jcl/CUSTFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(9 0) RECORDSIZE(500 500) INDEXED}) and described by
 * copybooks {@code app/cpy/CVCUS01Y.cpy} / {@code app/cpy/CUSTREC.cpy}
 * ({@code CUSTOMER-RECORD}, RECLN&nbsp;500) at source commit SHA {@code 27d6c6f}. The
 * legacy keyed access paths map to inherited {@code JpaRepository} operations:
 * <ul>
 *   <li>keyed {@code READ} ({@code COACTVWC} account-view join, {@code COACTUPC}
 *       read-before-update) &rarr; {@link CustomerRepository#findById(Object)};</li>
 *   <li>keyed {@code REWRITE} ({@code COACTUPC} dual-record ACCOUNT+CUSTOMER update)
 *       &rarr; {@code save}/{@code saveAndFlush};</li>
 *   <li>sequential master read in key order ({@code app/cbl/CBCUS01C.cbl} driven by
 *       {@code app/jcl/CUSTFILE.jcl}) &rarr; {@code findAll(Sort.by("custId"))}.</li>
 * </ul>
 *
 * <h2>Why the SSN assertions matter</h2>
 * COBOL {@code CUST-SSN PIC 9(09)} is migrated to a fixed-width {@code CHAR(9)}
 * {@link String} (never a numeric type). Several seeded SSNs begin with {@code 0}
 * (for example customer&nbsp;1 = {@code 020973888}); a numeric mapping would silently
 * drop those leading zeros and corrupt the value. These tests are the regression guard
 * that the leading zero — and the full nine-character width — survives every round-trip.
 *
 * <h2>Optimistic-locking parity</h2>
 * The {@link Customer} {@code @Version} column reproduces the COBOL re-read-and-compare
 * guard paragraph {@code 9300-CHECK-CHANGE-IN-REC} used by {@code COACTUPC} /
 * {@code COCRDUPC}. {@code customers} is the second half of the {@code COACTUPC}
 * dual-record atomic update, so the optimistic-lock test below proves the CUSTOMER half
 * of that concurrency guard: a stale write fails with
 * {@link ObjectOptimisticLockingFailureException} — the exact "record changed by another
 * user" outcome the mainframe produced.
 *
 * <h2>Data isolation</h2>
 * Flyway&nbsp;V3 seeds exactly fifty customer rows ({@code cust_id} 1..50, each at
 * {@code version} 0). Mutating tests keep that seed deterministic in one of two ways,
 * per the {@link AbstractIntegrationIT} contract:
 * <ul>
 *   <li>tests that only need first-level-cache semantics are {@link Transactional} so
 *       Spring rolls their transaction back (for example
 *       {@link #update_seededCustomerField_incrementsVersion()});</li>
 *   <li>tests that must observe <em>real</em> cross-transaction behaviour — a true
 *       reload from the database, two detached copies for the optimistic-lock race, or a
 *       committed row read back through raw JDBC — are deliberately <strong>not</strong>
 *       transactional and instead operate on dedicated {@code cust_id}s far outside the
 *       seeded range ({@code 9_000_000_00x}), cleaning them up in a {@code finally} block
 *       so the table always returns to its fifty-row baseline.</li>
 * </ul>
 */
@DisplayName("CustomerRepository IT — PostgreSQL 16 + Flyway (CUSTFILE/CVCUS01Y parity)")
class CustomerRepositoryIT extends AbstractIntegrationIT {

    /** Flyway V3 seeds exactly this many customer rows from {@code app/data/ASCII/custdata.txt}. */
    private static final long SEEDED_CUSTOMER_COUNT = 50L;

    /** First seeded customer ({@code cust_id} 1); its SSN intentionally carries a leading zero. */
    private static final Long SEEDED_CUST_ID = 1L;
    private static final String SEEDED_CUST_ID_SSN = "020973888";
    private static final int SEEDED_CUST_ID_FICO = 274;

    /** Last seeded customer ({@code cust_id} 50) — used to assert key-ordered traversal. */
    private static final Long LAST_SEEDED_CUST_ID = 50L;

    /** An id guaranteed never to be seeded, for the not-found path. */
    private static final Long NON_EXISTENT_CUST_ID = 999_999_999L;

    /**
     * Dedicated {@code cust_id}s, well outside the seeded {@code 1..50} range, used by the
     * non-transactional tests that commit and then clean up after themselves so the shared
     * fifty-row seed is never disturbed.
     */
    private static final Long NEW_ROUNDTRIP_CUST_ID = 9_000_000_001L;
    private static final Long OPTIMISTIC_LOCK_CUST_ID = 9_000_000_002L;
    private static final Long NEW_SSN_FIDELITY_CUST_ID = 9_000_000_003L;

    @Autowired
    private CustomerRepository customerRepository;

    // =====================================================================================
    // Phase 1 — Read finders (VSAM keyed READ + sequential master read parity)
    // =====================================================================================

    @Test
    @DisplayName("findById(seeded): present; SSN is a 9-char String with its leading zero intact and FICO populated")
    void findById_seededCustomerOne_isPresentWithNineCharLeadingZeroSsnAndFico() {
        Customer customer = customerRepository.findById(SEEDED_CUST_ID).orElseThrow();

        // SSN must be the fixed-width nine-character string CHAR(9) with the leading zero
        // preserved — proof that CUST-SSN PIC 9(09) was NOT migrated to a numeric type.
        assertThat(customer.getSsn())
                .as("seeded SSN must keep all nine characters including the leading zero")
                .isEqualTo(SEEDED_CUST_ID_SSN)
                .hasSize(9)
                .startsWith("0");

        // FICO (CUST-FICO-CREDIT-SCORE PIC 9(03)) is a populated numeric.
        assertThat(customer.getFicoCreditScore())
                .as("seeded FICO score must be populated")
                .isNotNull()
                .isEqualTo(SEEDED_CUST_ID_FICO);

        // The primary key round-trips as the expected Long.
        assertThat(customer.getCustId()).isEqualTo(SEEDED_CUST_ID);
    }

    @Test
    @DisplayName("findById(non-existent): returns Optional.empty (FILE STATUS '23' / record-not-found parity)")
    void findById_nonExistentId_returnsEmpty() {
        assertThat(customerRepository.findById(NON_EXISTENT_CUST_ID))
                .as("an unseeded customer id must resolve to an empty Optional")
                .isEmpty();
    }

    @Test
    @DisplayName("count(): reports the fifty Flyway-seeded customer rows")
    void count_reportsFiftySeededCustomers() {
        assertThat(customerRepository.count())
                .as("Flyway V3 seeds exactly fifty customers")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);
    }

    @Test
    @DisplayName("findAll(Sort by custId): returns all fifty rows in ascending key order (CBCUS01C sequential read)")
    void findAll_returnsAllFiftyCustomersInKeyOrder() {
        List<Customer> all = customerRepository.findAll(Sort.by("custId"));

        assertThat(all)
                .as("findAll must return every seeded customer")
                .hasSize((int) SEEDED_CUSTOMER_COUNT);

        // CBCUS01C reads the KSDS sequentially in ascending CUST-ID order; findAll(Sort) is
        // the faithful equivalent, so the first/last keys must bracket the seeded range.
        assertThat(all.get(0).getCustId())
                .as("first row in key order is cust_id 1")
                .isEqualTo(SEEDED_CUST_ID);
        assertThat(all.get(all.size() - 1).getCustId())
                .as("last row in key order is cust_id 50")
                .isEqualTo(LAST_SEEDED_CUST_ID);
    }

    // =====================================================================================
    // Phase 2 — Persist / update (VSAM WRITE + REWRITE parity)
    // =====================================================================================

    /**
     * Saving a brand-new customer must persist it, assign the initial {@code @Version} of
     * {@code 0}, and — when reloaded in a fresh transaction (a genuine {@code SELECT}, not a
     * first-level-cache hit) — return every field exactly as written, including a
     * nine-character SSN.
     *
     * <p>This test is intentionally <strong>not</strong> {@link Transactional}: a real
     * cross-transaction reload is required to prove the database actually stored and returned
     * the value. It uses a dedicated {@code cust_id} outside the seeded range and removes it in
     * a {@code finally} block so the fifty-row seed baseline is preserved.
     */
    @Test
    @DisplayName("save(new customer): persists, assigns @Version 0, and reloads with the SSN round-tripping exactly")
    void save_newCustomer_reloadRoundTripsAndAssignsInitialVersionZero() {
        final Long custId = NEW_ROUNDTRIP_CUST_ID;
        // Idempotent pre-clean in case a previous interrupted run left the row behind.
        customerRepository.findById(custId).ifPresent(customerRepository::delete);
        try {
            Customer saved = customerRepository.saveAndFlush(newCustomer(custId, "000123456", 640));

            // A freshly INSERTed @Version row starts at 0.
            assertThat(saved.getVersion())
                    .as("a newly persisted optimistic-lock version starts at 0")
                    .isZero();

            // Reload in a new transaction → a real database read, not the persistence-context cache.
            Customer reloaded = customerRepository.findById(custId).orElseThrow();
            assertThat(reloaded.getSsn())
                    .as("the nine-character SSN must round-trip exactly through a real reload")
                    .isEqualTo("000123456")
                    .hasSize(9);
            assertThat(reloaded.getFicoCreditScore()).isEqualTo(640);
            assertThat(reloaded.getFirstName()).isEqualTo("Roundtrip");
            assertThat(reloaded.getCustId()).isEqualTo(custId);
        } finally {
            customerRepository.findById(custId).ifPresent(customerRepository::delete);
        }
    }

    /**
     * Updating a managed customer and flushing must advance the optimistic-lock
     * {@code @Version}. Run inside a test-managed transaction so Spring rolls the change back
     * and the seeded row is left untouched for other tests.
     */
    @Test
    @Transactional
    @DisplayName("update(existing customer): saving a changed field increments @Version")
    void update_seededCustomerField_incrementsVersion() {
        Customer customer = customerRepository.findById(6L).orElseThrow();
        Long originalVersion = customer.getVersion();
        assertThat(originalVersion).as("seeded customers start at version 0").isNotNull();

        // Mutate a non-key field (CUST-FICO-CREDIT-SCORE) and flush the REWRITE.
        Integer currentFico = customer.getFicoCreditScore();
        customer.setFicoCreditScore(currentFico == null ? 700 : currentFico + 1);
        Customer saved = customerRepository.saveAndFlush(customer);

        assertThat(saved.getVersion())
                .as("a successful update must advance the @Version by exactly one")
                .isGreaterThan(originalVersion)
                .isEqualTo(originalVersion + 1);
    }

    // =====================================================================================
    // Phase 3 — Optimistic locking (COBOL 9300-CHECK-CHANGE-IN-REC parity)
    // =====================================================================================

    /**
     * Proves the CUSTOMER half of the {@code COACTUPC} dual-record concurrency guard: two
     * users read the same row, the first commits (advancing the {@code @Version}), and the
     * second's stale write fails with {@link ObjectOptimisticLockingFailureException} — the
     * exact "record changed by another user" outcome of the COBOL re-read-and-compare
     * paragraph {@code 9300-CHECK-CHANGE-IN-REC}.
     *
     * <p>This test is intentionally <strong>not</strong> {@link Transactional}: each
     * repository call must run in its own transaction so the two reads return distinct
     * <em>detached</em> copies that observe the same starting version. It uses a dedicated
     * {@code cust_id} and cleans it up in a {@code finally} block.
     */
    @Test
    @DisplayName("optimistic locking: a stale concurrent update throws ObjectOptimisticLockingFailureException")
    void optimisticLocking_staleConcurrentUpdate_throwsObjectOptimisticLockingFailure() {
        final Long custId = OPTIMISTIC_LOCK_CUST_ID;
        // Establish a known-clean dedicated row (idempotent).
        customerRepository.findById(custId).ifPresent(customerRepository::delete);
        customerRepository.saveAndFlush(newCustomer(custId, "000654321", 700));
        try {
            // Two independent reads model two concurrent users. Because this method is not
            // @Transactional, each call runs in its own transaction and returns a DETACHED
            // copy, so both observe the same starting @Version.
            Customer userA = customerRepository.findById(custId).orElseThrow();
            Customer userB = customerRepository.findById(custId).orElseThrow();
            assertThat(userA.getVersion())
                    .as("both reads must start from the same version")
                    .isEqualTo(userB.getVersion());

            // User A commits first: the row's @Version advances.
            userA.setFicoCreditScore(710);
            customerRepository.saveAndFlush(userA);

            // User B writes with the now-stale @Version: the UPDATE ... WHERE version = <old>
            // matches zero rows, so Spring Data surfaces an optimistic-locking failure.
            userB.setFicoCreditScore(720);
            assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                    .as("the stale second write must fail the optimistic-lock check")
                    .isThrownBy(() -> customerRepository.saveAndFlush(userB));
        } finally {
            customerRepository.findById(custId).ifPresent(customerRepository::delete);
        }
    }

    // =====================================================================================
    // Phase 4 — SSN / String fidelity (leading-zero regression guard)
    // =====================================================================================

    /**
     * Reads several seeded customers whose SSNs begin with {@code 0} and asserts each is read
     * back as the full nine-character string with its leading zero intact — a regression guard
     * against any future numeric mapping of {@code CUST-SSN PIC 9(09)} on the read path.
     */
    @Test
    @DisplayName("SSN fidelity (read): seeded SSNs beginning with 0 are read back as full 9-char strings")
    void ssn_seededLeadingZeroCustomers_preservedExactlyOnRead() {
        assertSeededSsn(1L, "020973888");
        assertSeededSsn(15L, "033922034");
        assertSeededSsn(24L, "017590544");
        assertSeededSsn(29L, "015027332");
    }

    /**
     * Stores a brand-new customer whose SSN begins with {@code 0} and asserts it is read back
     * with the leading zero preserved through <em>both</em> the ORM read path (a real reload)
     * and a raw JDBC read of the {@code CHAR(9)} column — independent proof that the database
     * persists the value verbatim and that Hibernate never coerces it to a number.
     *
     * <p>Not {@link Transactional}: the row is committed so the raw JDBC read observes it, then
     * removed in a {@code finally} block.
     */
    @Test
    @DisplayName("SSN fidelity (write+read): a new SSN beginning with 0 round-trips exactly via ORM and raw JDBC")
    void ssn_newCustomerLeadingZero_storedAndReadBackPreserved() {
        final Long custId = NEW_SSN_FIDELITY_CUST_ID;
        final String ssn = "000123456";
        customerRepository.findById(custId).ifPresent(customerRepository::delete);
        try {
            customerRepository.saveAndFlush(newCustomer(custId, ssn, 705));

            // ORM read path — a genuine reload returns the leading-zero SSN intact.
            Customer reloaded = customerRepository.findById(custId).orElseThrow();
            assertThat(reloaded.getSsn())
                    .as("ORM must read the SSN back with its leading zero and full width")
                    .isEqualTo(ssn)
                    .hasSize(9)
                    .startsWith("0");

            // Raw JDBC read path — proves the CHAR(9) column stored the value verbatim,
            // independently of Hibernate's mapping.
            String rawSsn = jdbcTemplate.queryForObject(
                    "SELECT ssn FROM customers WHERE cust_id = ?", String.class, custId);
            assertThat(rawSsn)
                    .as("the CHAR(9) column must persist the SSN verbatim, leading zero included")
                    .isEqualTo(ssn);
        } finally {
            customerRepository.findById(custId).ifPresent(customerRepository::delete);
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /**
     * Reads a seeded customer by id and asserts its SSN is the expected nine-character string
     * with a leading zero.
     *
     * @param custId      the seeded customer id
     * @param expectedSsn the expected nine-character SSN (leading zero included)
     */
    private void assertSeededSsn(Long custId, String expectedSsn) {
        Customer customer = customerRepository.findById(custId).orElseThrow();
        assertThat(customer.getSsn())
                .as("seeded customer %d SSN must keep its leading zero and full nine characters", custId)
                .isEqualTo(expectedSsn)
                .hasSize(9)
                .startsWith("0");
    }

    /**
     * Builds a transient {@link Customer} with the given key, SSN and FICO score plus sensible,
     * contract-faithful defaults for the remaining fields. The {@code version} is intentionally
     * left {@code null} so Spring Data treats the entity as new and issues an {@code INSERT}
     * (rather than a {@code merge}), letting Hibernate assign the initial {@code @Version} of 0.
     *
     * @param custId the primary key ({@code CUST-ID})
     * @param ssn    the nine-character SSN ({@code CUST-SSN}); leading zeros are significant
     * @param fico   the FICO credit score ({@code CUST-FICO-CREDIT-SCORE})
     * @return a new, un-persisted {@link Customer}
     */
    private static Customer newCustomer(Long custId, String ssn, Integer fico) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setFirstName("Roundtrip");
        customer.setMiddleName("Q");
        customer.setLastName("Tester");
        customer.setAddrLine1("1 Integration Way");
        customer.setAddrLine2("Suite IT");
        customer.setAddrLine3("Testville");
        customer.setAddrStateCd("NC");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("12546");
        customer.setPhoneNum1("(908)119-8310");
        customer.setPhoneNum2("(373)693-8684");
        customer.setSsn(ssn);
        customer.setGovtIssuedId("00000000000049368437");
        customer.setDob("1990-01-01");
        customer.setEftAccountId("0053581756");
        customer.setPriCardHolderInd("Y");
        customer.setFicoCreditScore(fico);
        // version left null on purpose — see Javadoc above.
        return customer;
    }
}
