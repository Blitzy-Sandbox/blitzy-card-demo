package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Data JPA slice test for {@link CardXrefRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16 database</strong> provisioned by Testcontainers through
 * {@link AbstractRepositoryTest} (never H2, never a mock).
 *
 * <p>{@link CardXref} is the field-by-field translation of the COBOL copybook
 * {@code app/cpy/CVACT03Y.cpy} ({@code CARD-XREF-RECORD}, record length {@code 50}, source
 * commit SHA {@code 27d6c6f}): {@code XREF-CARD-NUM PIC X(16)} &rarr; the {@code String} primary
 * key, {@code XREF-CUST-ID PIC 9(09)} and {@code XREF-ACCT-ID PIC 9(11)} &rarr; the {@code Long}
 * scalar reference fields. The legacy VSAM dataset exposed two access paths and this test verifies
 * that both survive the migration:</p>
 * <ul>
 *   <li><strong>Base KSDS key</strong> over {@code XREF-CARD-NUM} &rarr; the inherited
 *       {@link org.springframework.data.repository.CrudRepository#findById(Object) findById(String)}
 *       primary-key lookup (exercised by {@link #saveAndFindById_roundTrips()} and
 *       {@link #findById_returnsSeededXref()}).</li>
 *   <li><strong>Non-unique alternate index</strong> {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX}
 *       ({@code KEYS(11,25) NONUNIQUEKEY} over {@code XREF-ACCT-ID}) &rarr; the derived query
 *       {@link CardXrefRepository#findByXrefAcctId(Long)}, which returns a {@link List} because a
 *       single account may map to many cards (exercised by
 *       {@link #findByXrefAcctId_returnsAllRowsForAccount_nonUnique()}).</li>
 * </ul>
 *
 * <h2>Foreign-key ordering (critical)</h2>
 * <p>The Flyway migration {@code db/migration/V1__schema.sql} declares two foreign keys on
 * {@code card_xref}: {@code xref_acct_id} &rarr; {@code account(acct_id)} and {@code xref_cust_id}
 * &rarr; {@code customer(cust_id)}. PostgreSQL checks these constraints at statement-execution time,
 * so every test that inserts a {@code CardXref} first persists (and flushes) the parent
 * {@link Customer} and {@link Account} rows it references; otherwise the insert fails with a
 * {@code DataIntegrityViolationException}. The insert order is therefore always
 * <em>Customer + Account &rarr; CardXref</em>.</p>
 *
 * <h2>Isolation</h2>
 * <p>Each {@code @Test} runs inside the transaction opened by {@code @DataJpaTest} and is rolled
 * back on completion, so the test-owned rows created here (identifiers {@code >= 900000000}) never
 * pollute the committed Flyway seed and never collide with the small seed identifiers. The
 * seed-backed assertion reads a committed row only.</p>
 */
class CardXrefRepositoryTest extends AbstractRepositoryTest {

    /** Repository under test — the card&nbsp;&harr;&nbsp;account&nbsp;&harr;&nbsp;customer cross-reference port. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Parent-aggregate repository used to satisfy the {@code xref_acct_id} foreign key. */
    @Autowired
    private AccountRepository accountRepository;

    /** Parent-aggregate repository used to satisfy the {@code xref_cust_id} foreign key. */
    @Autowired
    private CustomerRepository customerRepository;

    /** JPA test helper used to detach persisted state so reads are served fresh from the database. */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies that the derived query {@link CardXrefRepository#findByXrefAcctId(Long)} reproduces
     * the legacy <strong>non-unique</strong> account alternate index: all cross-reference rows that
     * share an account id are returned as a {@link List}, and an account with no rows yields an
     * empty list (never {@code null}).
     */
    @Test
    void findByXrefAcctId_returnsAllRowsForAccount_nonUnique() {
        // Parents first (FK ordering): persist and flush Customer + Account before any CardXref.
        customerRepository.saveAndFlush(newCustomer(900000300L));
        accountRepository.saveAndFlush(newAccount(900000300L));

        // Three distinct cards all pointing at the same account -> exercises the NON-UNIQUE index.
        cardXrefRepository.saveAllAndFlush(List.of(
                newXref("9000000000030001", 900000300L, 900000300L),
                newXref("9000000000030002", 900000300L, 900000300L),
                newXref("9000000000030003", 900000300L, 900000300L)));

        // Detach everything so findByXrefAcctId reads from PostgreSQL rather than the 1st-level cache.
        entityManager.clear();

        List<CardXref> found = cardXrefRepository.findByXrefAcctId(900000300L);

        assertThat(found).hasSize(3);
        assertThat(found).allMatch(xref -> xref.getXrefAcctId().equals(900000300L));

        // A non-existent account resolves to an empty list, confirming the finder is selective.
        assertThat(cardXrefRepository.findByXrefAcctId(900000999L)).isEmpty();
    }

    /**
     * Verifies the {@code save} / {@code findById} round-trip over the natural {@code String}
     * primary key ({@code XREF-CARD-NUM}), confirming the two scalar {@code Long} fields
     * ({@code XREF-CUST-ID}, {@code XREF-ACCT-ID}) persist and reload unchanged.
     */
    @Test
    void saveAndFindById_roundTrips() {
        customerRepository.saveAndFlush(newCustomer(900000301L));
        accountRepository.saveAndFlush(newAccount(900000301L));

        cardXrefRepository.saveAndFlush(newXref("9000000000031001", 900000301L, 900000301L));
        entityManager.clear();

        CardXref found = cardXrefRepository.findById("9000000000031001").orElseThrow();

        assertThat(found.getXrefCustId()).isEqualTo(900000301L);
        assertThat(found.getXrefAcctId()).isEqualTo(900000301L);
    }

    /**
     * Verifies the committed Flyway seed ({@code V3__seed_data.sql}) is visible to a
     * primary-key lookup: card number {@code 0500024453765740} maps to account {@code 50} and
     * customer {@code 50}. This exercises {@code findById} against pre-existing, read-only data
     * without provisioning parents.
     */
    @Test
    void findById_returnsSeededXref() {
        assertThat(cardXrefRepository.findById("0500024453765740"))
                .as("seeded card_xref row for card 0500024453765740")
                .isPresent()
                .hasValueSatisfying(xref -> {
                    assertThat(xref.getXrefAcctId()).isEqualTo(50L);
                    assertThat(xref.getXrefCustId()).isEqualTo(50L);
                });
    }

    /**
     * Builds a transient {@link CardXref} with the supplied natural key and scalar references,
     * keeping the test bodies DRY.
     *
     * @param cardNum the 16-character card number (natural {@code @Id})
     * @param custId  the referenced customer id (maps to {@code XREF-CUST-ID})
     * @param acctId  the referenced account id (maps to {@code XREF-ACCT-ID})
     * @return a new, unmanaged {@link CardXref} instance
     */
    private CardXref newXref(String cardNum, Long custId, Long acctId) {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(cardNum);
        xref.setXrefCustId(custId);
        xref.setXrefAcctId(acctId);
        return xref;
    }

    /**
     * Builds a minimal parent {@link Account} carrying only its application-assigned primary key —
     * the sole {@code NOT NULL} column on the {@code account} table — sufficient to satisfy the
     * {@code fk_xref_account} foreign key.
     *
     * @param acctId the application-assigned account id
     * @return a new, unmanaged {@link Account} instance
     */
    private Account newAccount(Long acctId) {
        Account account = new Account();
        account.setAcctId(acctId);
        return account;
    }

    /**
     * Builds a minimal parent {@link Customer} carrying only its application-assigned primary key —
     * the sole {@code NOT NULL} column on the {@code customer} table — sufficient to satisfy the
     * {@code fk_xref_customer} foreign key.
     *
     * @param custId the application-assigned customer id
     * @return a new, unmanaged {@link Customer} instance
     */
    private Customer newCustomer(Long custId) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        return customer;
    }
}
