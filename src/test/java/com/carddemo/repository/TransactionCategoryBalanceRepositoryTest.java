package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.carddemo.entity.Account;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;

/**
 * Spring Data JPA slice test for {@link TransactionCategoryBalanceRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16</strong> database provisioned by Testcontainers through the shared
 * {@link AbstractRepositoryTest} base class (never H2, never a mock). Inheriting the base class
 * supplies all Spring/Testcontainers wiring ({@code @DataJpaTest}, the real Flyway migrations
 * {@code V1}&rarr;{@code V2}&rarr;{@code V3}, {@code ddl-auto: validate}, and a per-test transaction
 * that rolls back), so this class adds no annotations of its own.
 *
 * <p>The entity under test is the Java translation of the COBOL copybook
 * {@code app/cpy/CVTRA01Y.cpy} ({@code TRAN-CAT-BAL-RECORD}, record length 50, source commit SHA
 * {@code 27d6c6f}); its key group {@code TRAN-CAT-KEY} maps to the {@code @Embeddable}
 * {@link TransactionCategoryBalanceId} and the signed money field {@code TRAN-CAT-BAL PIC S9(09)V99}
 * maps to a {@code NUMERIC(11,2)} {@link BigDecimal} column. The suite verifies three behaviours:</p>
 * <ol>
 *   <li>full composite-key retrieval via the inherited
 *       {@link org.springframework.data.repository.CrudRepository#findById(Object) findById}
 *       using a {@link TransactionCategoryBalanceId};</li>
 *   <li>the derived, embedded-id-traversing finder
 *       {@link TransactionCategoryBalanceRepository#findByIdTrancatAcctId(Long)} (property path
 *       {@code id.trancatAcctId}), which reproduces {@code CBACT04C}'s per-account grouping; and</li>
 *   <li>decimal fidelity of {@code tranCatBal} &mdash; sign and scale-2 preservation across a
 *       persist/flush/clear/re-read round trip, including a signed negative and a large positive
 *       value that still fits {@code NUMERIC(11,2)}.</li>
 * </ol>
 *
 * <h2>Foreign-key ordering</h2>
 * <p>The {@code transaction_category_balance.trancat_acct_id} column carries a foreign key to
 * {@code account(acct_id)} (Flyway {@code V1__schema.sql}, constraint {@code fk_tcb_account}).
 * Every balance row therefore requires a pre-existing parent account, so each test first persists
 * and flushes a self-owned {@link Account} (id {@code >= 900000000L}, well clear of the seed range
 * {@code 1..50}) before inserting any balance that references it.</p>
 */
class TransactionCategoryBalanceRepositoryTest extends AbstractRepositoryTest {

    /** Repository under test. */
    @Autowired
    private TransactionCategoryBalanceRepository balanceRepository;

    /** Used to persist the parent {@link Account} rows that satisfy the {@code fk_tcb_account} FK. */
    @Autowired
    private AccountRepository accountRepository;

    /** Provides {@code flush()}/{@code clear()} so reads are genuine round trips to PostgreSQL. */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies a full composite-key lookup: after persisting a parent account and a single balance,
     * {@code findById(TransactionCategoryBalanceId)} returns exactly that row, with the money field
     * round-tripped at scale 2 and every key component intact.
     */
    @Test
    void findById_byCompositeKey_returnsExactRow() {
        accountRepository.saveAndFlush(newAccount(900000500L));
        balanceRepository.saveAndFlush(newBalance(900000500L, "01", 1, new BigDecimal("1234.56")));
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryBalance found = balanceRepository
                .findById(new TransactionCategoryBalanceId(900000500L, "01", 1))
                .orElseThrow();

        assertThat(found.getTranCatBal()).isEqualByComparingTo("1234.56");
        assertThat(found.getTranCatBal().scale()).isEqualTo(2);
        assertThat(found.getId().getTrancatAcctId()).isEqualTo(900000500L);
        assertThat(found.getId().getTrancatTypeCd()).isEqualTo("01");
        assertThat(found.getId().getTrancatCd()).isEqualTo(1);
    }

    /**
     * Verifies the derived query {@link TransactionCategoryBalanceRepository#findByIdTrancatAcctId(Long)}
     * traverses the embedded-id property path {@code id.trancatAcctId} and returns only the rows for
     * the requested account. Three balances are stored for one account and a fourth for a different
     * account; the finder must return exactly the three, all bearing the queried account id.
     */
    @Test
    void findByIdTrancatAcctId_traversesEmbeddedId_returnsAllRowsForAccount() {
        accountRepository.saveAndFlush(newAccount(900000501L));
        accountRepository.saveAndFlush(newAccount(900000502L));
        balanceRepository.saveAllAndFlush(List.of(
                newBalance(900000501L, "01", 1, new BigDecimal("10.00")),
                newBalance(900000501L, "02", 5, new BigDecimal("20.00")),
                newBalance(900000501L, "03", 9, new BigDecimal("30.00")),
                newBalance(900000502L, "01", 1, new BigDecimal("99.00"))));
        entityManager.flush();
        entityManager.clear();

        List<TransactionCategoryBalance> found = balanceRepository.findByIdTrancatAcctId(900000501L);

        assertThat(found).hasSize(3);
        assertThat(found).allMatch(b -> b.getId().getTrancatAcctId().equals(900000501L));
    }

    /**
     * Verifies decimal fidelity of {@code tranCatBal}: a signed negative value and a large positive
     * value that still fits {@code NUMERIC(11,2)} both survive a persist/flush/clear/re-read cycle
     * with their exact value and scale (2), matching the COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}
     * sign-and-scale contract.
     */
    @Test
    void tranCatBal_preservesSignAndScale() {
        accountRepository.saveAndFlush(newAccount(900000503L));

        balanceRepository.saveAndFlush(newBalance(900000503L, "01", 1, new BigDecimal("-9999.99")));
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryBalance negative = balanceRepository
                .findById(new TransactionCategoryBalanceId(900000503L, "01", 1))
                .orElseThrow();
        assertThat(negative.getTranCatBal()).isEqualByComparingTo("-9999.99");
        assertThat(negative.getTranCatBal().scale()).isEqualTo(2);

        balanceRepository.saveAndFlush(newBalance(900000503L, "02", 2, new BigDecimal("9999999.99")));
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryBalance large = balanceRepository
                .findById(new TransactionCategoryBalanceId(900000503L, "02", 2))
                .orElseThrow();
        assertThat(large.getTranCatBal()).isEqualByComparingTo("9999999.99");
        assertThat(large.getTranCatBal().scale()).isEqualTo(2);
    }

    /**
     * Confirms a documented Flyway seed row is visible to repository queries. The committed
     * {@code V3__seed_data.sql} inserts {@code (1, '01', 1, 0.00)} into
     * {@code transaction_category_balance}; because seed data is committed (not part of the test's
     * rolled-back transaction), the row is reachable by composite-key lookup with a scale-2 balance
     * of {@code 0.00}.
     */
    @Test
    void findById_seededRow_isVisibleFromCommittedFlywaySeed() {
        TransactionCategoryBalance seeded = balanceRepository
                .findById(new TransactionCategoryBalanceId(1L, "01", 1))
                .orElseThrow();

        assertThat(seeded.getTranCatBal()).isEqualByComparingTo("0.00");
    }

    /**
     * Builds a valid, self-owned parent {@link Account} for the {@code fk_tcb_account} foreign key.
     * Only {@code acct_id} is {@code NOT NULL} in the schema, but the money fields and a couple of
     * descriptive fields are populated with schema-valid values ({@code NUMERIC(12,2)} amounts and an
     * ISO date) so the row is representative and insert-safe. The optimistic-lock {@code version} is
     * left unset and managed by JPA.
     *
     * @param id the application-assigned account id (use {@code >= 900000000L} to avoid the seed range)
     * @return a transient, insert-ready {@link Account}
     */
    private Account newAccount(Long id) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1000.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        return account;
    }

    /**
     * Builds a transient {@link TransactionCategoryBalance} from its three composite-key components
     * and a balance, via the {@link TransactionCategoryBalanceId} all-args constructor.
     *
     * @param acctId the account id key component (COBOL {@code TRANCAT-ACCT-ID}); must reference an
     *               already-persisted {@link Account}
     * @param typeCd the transaction type-code key component (COBOL {@code TRANCAT-TYPE-CD}, 2 chars)
     * @param catCd  the transaction category-code key component (COBOL {@code TRANCAT-CD})
     * @param bal    the running category balance (COBOL {@code TRAN-CAT-BAL}, scale 2)
     * @return a transient, insert-ready {@link TransactionCategoryBalance}
     */
    private TransactionCategoryBalance newBalance(Long acctId, String typeCd, Integer catCd, BigDecimal bal) {
        TransactionCategoryBalance balance = new TransactionCategoryBalance();
        balance.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        balance.setTranCatBal(bal);
        return balance;
    }
}
