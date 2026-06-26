/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.integration;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link AccountRepository} against a real PostgreSQL&nbsp;16
 * Testcontainer seeded by Flyway {@code V1}/{@code V2}/{@code V3}.
 *
 * <p>This suite is the JPA-era replacement for the legacy VSAM {@code ACCTFILE}
 * KSDS keyed access. The account master is defined by COBOL copybook
 * {@code app/cpy/CVACT01Y.cpy} ({@code ACCOUNT-RECORD}, record length&nbsp;300) and
 * its VSAM cluster is provisioned by {@code app/jcl/ACCTFILE.jcl}
 * ({@code KEYS(11 0) RECORDSIZE(300 300)}) at source commit {@code 27d6c6f}. The
 * three behaviours the COBOL online programs relied upon are each asserted here:</p>
 * <ul>
 *   <li><strong>Keyed READ</strong> — {@code COACTVWC} reads the account by key
 *       ({@code 9300-GETACCTDATA-BYACCT}, {@code RIDFLD} = the 11-digit account id).
 *       Reproduced by {@link AccountRepository#findById(Object)} (present / absent
 *       paths, the latter being the VSAM {@code FILE STATUS 23} not-found case).</li>
 *   <li><strong>REWRITE under a re-read-and-compare guard</strong> — {@code COACTUPC}
 *       rewrites the account ({@code REWRITE FILE(LIT-ACCTFILENAME)}) only after the
 *       {@code 9300-CHECK-CHANGE-IN-REC} guard confirms the record was not changed by
 *       another user (the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag and the
 *       "Record changed by some one else" message). Reproduced by JPA
 *       {@code @Version} optimistic locking on {@link Account#getVersion()}.</li>
 *   <li><strong>Decimal exactness</strong> — the five {@code COMP-3}
 *       {@code PIC S9(10)V99} money fields ({@code curr_bal}, {@code credit_limit},
 *       {@code cash_credit_limit}, {@code curr_cyc_credit}, {@code curr_cyc_debit})
 *       map to {@code NUMERIC(12,2)} and are asserted as {@link BigDecimal} at scale&nbsp;2
 *       using {@code compareTo} (never {@code equals}, which is scale-sensitive).</li>
 * </ul>
 *
 * <p>The suite extends {@link AbstractIntegrationIT}, so it inherits the shared
 * PostgreSQL&nbsp;16 and LocalStack containers, the {@code test} profile
 * ({@code ddl-auto=validate}, Flyway enabled, no H2), and the runtime-injected
 * datasource coordinates (no hardcoded port, no real secret). There is no mocking
 * and no in-memory database: every assertion exercises the migrated schema and its
 * deterministic seed.</p>
 *
 * <h2>Data isolation</h2>
 * <p>Flyway&nbsp;V3 seeds exactly <strong>50</strong> account rows
 * ({@code acct_id} 1..50, each at {@code version} 0). The mutating read/update and
 * decimal tests are annotated {@link Transactional} so Spring rolls their changes
 * back, leaving the shared seed untouched. The optimistic-locking test
 * <em>cannot</em> be {@code @Transactional} (it needs two genuinely detached copies
 * read in independent transactions), so it operates on a dedicated, out-of-seed
 * account id and removes that row in {@link #removeDedicatedOptimisticLockRow()};
 * JUnit&nbsp;5 runs the methods sequentially, so the {@code count() == 50}
 * invariant holds at the start of every test.</p>
 */
@DisplayName("AccountRepository IT — PostgreSQL 16 + Flyway seed (VSAM ACCTFILE parity)")
class AccountRepositoryIT extends AbstractIntegrationIT {

    /**
     * Number of account rows Flyway {@code V3} seeds from
     * {@code app/data/ASCII/acctdata.txt} ({@code CVACT01Y}, 50 records).
     */
    private static final int SEEDED_ACCOUNT_COUNT = 50;

    /** First seeded account id (overpunch record {@code 00000000001} → {@code acct_id} 1). */
    private static final long SEEDED_ACCOUNT_ID = 1L;

    /** Seeded {@code curr_bal} for {@link #SEEDED_ACCOUNT_ID} (overpunch-decoded from the fixture). */
    private static final BigDecimal SEEDED_ACCOUNT_CURR_BAL = new BigDecimal("194.00");

    /** Seeded {@code credit_limit} for {@link #SEEDED_ACCOUNT_ID} (overpunch-decoded from the fixture). */
    private static final BigDecimal SEEDED_ACCOUNT_CREDIT_LIMIT = new BigDecimal("2020.00");

    /** Account id guaranteed absent from the seed — exercises the not-found ({@code FILE STATUS 23}) path. */
    private static final long ABSENT_ACCOUNT_ID = 999_999_999L;

    /** Out-of-seed id for the "persist a new account" test (rolled back; 11-digit, within {@code PIC 9(11)}). */
    private static final long NEW_ACCOUNT_ID = 70_000_000_001L;

    /** Out-of-seed id for the decimal-precision test (rolled back; 11-digit, within {@code PIC 9(11)}). */
    private static final long DECIMAL_ACCOUNT_ID = 70_000_000_002L;

    /**
     * Out-of-seed id for the optimistic-locking test. That test is not
     * {@code @Transactional} (so it commits), hence this dedicated row is deleted in
     * {@link #removeDedicatedOptimisticLockRow()} to keep the seed deterministic.
     */
    private static final long OPTIMISTIC_LOCK_ACCOUNT_ID = 70_000_000_003L;

    /** Canonical monetary scale for every {@code NUMERIC(12,2)} account money column. */
    private static final int MONEY_SCALE = 2;

    /** Repository under test (keyed-finder + persistence facade over the {@code accounts} table). */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Transaction-scoped persistence context used only to {@code clear()} managed
     * state between a write and a re-read, so the subsequent {@code findById} is a
     * genuine SELECT against the database rather than a first-level-cache hit.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Removes the dedicated row the (non-transactional) optimistic-locking test
     * commits, restoring the {@code count() == 50} seed invariant for the next test.
     * The transactional tests roll back and never create this row, so the guard
     * simply skips for them.
     */
    @AfterEach
    void removeDedicatedOptimisticLockRow() {
        if (accountRepository.existsById(OPTIMISTIC_LOCK_ACCOUNT_ID)) {
            accountRepository.deleteById(OPTIMISTIC_LOCK_ACCOUNT_ID);
        }
    }

    // =====================================================================================
    // Phase 1 — Read finders (replace the VSAM keyed READ used by COACTVWC / COACTUPC).
    // =====================================================================================

    @Test
    @DisplayName("findById(seeded id) returns the account (VSAM READ KEY) with scale-2 money")
    void findByIdReturnsSeededAccount() {
        Account account = accountRepository.findById(SEEDED_ACCOUNT_ID).orElseThrow();

        assertThat(account.getAcctId()).isEqualTo(SEEDED_ACCOUNT_ID);
        // A NUMERIC(12,2) column always materialises a BigDecimal at scale 2.
        assertThat(account.getCurrBal().scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("findById(seeded id) decodes the fixture money fields byte-for-byte (overpunch parity)")
    void findByIdReturnsSeededMonetaryValues() {
        Account account = accountRepository.findById(SEEDED_ACCOUNT_ID).orElseThrow();

        // compareTo (not equals) so the assertion is scale-insensitive yet value-exact.
        assertThat(account.getCurrBal()).isEqualByComparingTo(SEEDED_ACCOUNT_CURR_BAL);
        assertThat(account.getCreditLimit()).isEqualByComparingTo(SEEDED_ACCOUNT_CREDIT_LIMIT);
    }

    @Test
    @DisplayName("findById(absent id) is empty (VSAM FILE STATUS 23 / record-not-found path)")
    void findByIdOfMissingAccountIsEmpty() {
        assertThat(accountRepository.findById(ABSENT_ACCOUNT_ID)).isEmpty();
    }

    @Test
    @DisplayName("count() equals the 50 rows seeded by Flyway V3")
    void countReturnsSeededRowCount() {
        assertThat(accountRepository.count()).isEqualTo(SEEDED_ACCOUNT_COUNT);
    }

    @Test
    @DisplayName("findAll() returns all 50 seeded rows (sequential master read parity)")
    void findAllReturnsSeededRows() {
        assertThat(accountRepository.findAll()).hasSize(SEEDED_ACCOUNT_COUNT);
    }

    // =====================================================================================
    // Phase 2 — Persist / update (replace the VSAM WRITE / REWRITE used by COACTUPC).
    // Each test is @Transactional so Spring rolls the mutation back and the seed is intact.
    // =====================================================================================

    @Test
    @Transactional
    @DisplayName("save(new account) inserts and round-trips all five money fields at scale 2")
    void saveNewAccountRoundTripsAllMoneyFields() {
        BigDecimal currBal = new BigDecimal("123.45");
        BigDecimal creditLimit = new BigDecimal("5000.00");
        BigDecimal cashCreditLimit = new BigDecimal("2500.00");
        BigDecimal currCycCredit = new BigDecimal("10.00");
        BigDecimal currCycDebit = new BigDecimal("20.00");

        // version == null marks the entity new, so Spring Data issues an INSERT (persist),
        // not a merge, even though the id is assigned (there is no @GeneratedValue).
        Account fresh = new Account(NEW_ACCOUNT_ID, "Y",
                currBal, creditLimit, cashCreditLimit,
                "2020-01-01", "2030-01-01", "2030-01-01",
                currCycCredit, currCycDebit, "9999999999", "TESTGRP001", null);

        accountRepository.saveAndFlush(fresh);
        // Detach everything so the reload is a real SELECT, not a cache hit.
        entityManager.clear();

        Account reloaded = accountRepository.findById(NEW_ACCOUNT_ID).orElseThrow();
        assertThat(reloaded.getAcctId()).isEqualTo(NEW_ACCOUNT_ID);

        // Money round-trips exactly: value via compareTo, fidelity via scale == 2.
        assertThat(reloaded.getCurrBal()).isEqualByComparingTo(currBal);
        assertThat(reloaded.getCreditLimit()).isEqualByComparingTo(creditLimit);
        assertThat(reloaded.getCashCreditLimit()).isEqualByComparingTo(cashCreditLimit);
        assertThat(reloaded.getCurrCycCredit()).isEqualByComparingTo(currCycCredit);
        assertThat(reloaded.getCurrCycDebit()).isEqualByComparingTo(currCycDebit);

        assertThat(reloaded.getCurrBal().scale()).isEqualTo(MONEY_SCALE);
        assertThat(reloaded.getCreditLimit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(reloaded.getCashCreditLimit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(reloaded.getCurrCycCredit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(reloaded.getCurrCycDebit().scale()).isEqualTo(MONEY_SCALE);

        // A freshly inserted @Version row starts at 0.
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    @Transactional
    @DisplayName("save(updated balance) rewrites the row (REWRITE) and advances the @Version counter")
    void updateBalanceIncrementsVersion() {
        Account account = accountRepository.findById(SEEDED_ACCOUNT_ID).orElseThrow();
        long originalVersion = account.getVersion();

        // COBOL COMPUTE/ROUNDED parity: add at scale 2 with HALF_EVEN, never double/float.
        BigDecimal increasedBalance = account.getCurrBal()
                .add(new BigDecimal("100.00"))
                .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        account.setCurrBal(increasedBalance);

        accountRepository.saveAndFlush(account);
        entityManager.clear();

        Account reloaded = accountRepository.findById(SEEDED_ACCOUNT_ID).orElseThrow();
        assertThat(reloaded.getCurrBal()).isEqualByComparingTo(increasedBalance);
        assertThat(reloaded.getCurrBal().scale()).isEqualTo(MONEY_SCALE);
        // One successful REWRITE → exactly one version increment.
        assertThat(reloaded.getVersion()).isEqualTo(originalVersion + 1);
    }

    // =====================================================================================
    // Phase 3 — Optimistic locking (COACTUPC 9300-CHECK-CHANGE-IN-REC re-read-and-compare).
    //
    // This test is NOT @Transactional. Each repository call runs in its own transaction, so
    // the two findById results are detached copies (both at version 0). Saving copy A
    // advances the persisted version to 1; saving the stale copy B then fails the version
    // check, matching the COBOL guard that rejected an update to a record another user had
    // already changed. The dedicated row is removed in @AfterEach so the shared seed stays
    // at 50 rows.
    // =====================================================================================

    @Test
    @DisplayName("save(stale copy) throws ObjectOptimisticLockingFailureException (9300-CHECK-CHANGE-IN-REC)")
    void staleUpdateThrowsOptimisticLockingFailure() {
        // Arrange: a committed, dedicated row (version starts at 0).
        Account seed = new Account(OPTIMISTIC_LOCK_ACCOUNT_ID, "Y",
                new BigDecimal("100.00"), new BigDecimal("5000.00"), new BigDecimal("2500.00"),
                "2020-01-01", "2030-01-01", "2030-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "9999999999", "OPTLOCKGRP", null);
        accountRepository.saveAndFlush(seed);

        // Two independent reads → two detached copies, each still at version 0.
        Account copyA = accountRepository.findById(OPTIMISTIC_LOCK_ACCOUNT_ID).orElseThrow();
        Account copyB = accountRepository.findById(OPTIMISTIC_LOCK_ACCOUNT_ID).orElseThrow();

        // First writer wins: the persisted version advances 0 → 1.
        copyA.setCurrBal(new BigDecimal("500.00"));
        accountRepository.saveAndFlush(copyA);

        // Second writer holds the stale version (0) → re-read-and-compare guard rejects it.
        copyB.setCurrBal(new BigDecimal("600.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(copyB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // =====================================================================================
    // Phase 4 — Decimal precision fidelity (COMP-3 PIC S9(10)V99 → NUMERIC(12,2)).
    // =====================================================================================

    @Test
    @Transactional
    @DisplayName("money persists at scale 2 with no precision loss and compares scale-insensitively")
    void decimalValuePersistsAtScaleTwoWithoutPrecisionLoss() {
        BigDecimal creditLimit = new BigDecimal("12000.00");
        Account fresh = new Account(DECIMAL_ACCOUNT_ID, "Y",
                new BigDecimal("0.00"), creditLimit, new BigDecimal("0.00"),
                "2020-01-01", "2030-01-01", "2030-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "9999999999", "TESTGRP001", null);

        accountRepository.saveAndFlush(fresh);
        entityManager.clear();

        Account reloaded = accountRepository.findById(DECIMAL_ACCOUNT_ID).orElseThrow();

        // Stored exactly at NUMERIC(12,2): scale is 2 and the value is preserved.
        assertThat(reloaded.getCreditLimit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(reloaded.getCreditLimit()).isEqualByComparingTo(creditLimit);

        // A scale-0 representation of the same amount still compares equal via compareTo,
        // proving money assertions must use compareTo and never the scale-sensitive equals.
        assertThat(reloaded.getCreditLimit().compareTo(new BigDecimal("12000"))).isZero();
        assertThat(reloaded.getCreditLimit()).isEqualByComparingTo(new BigDecimal("12000"));
    }
}
