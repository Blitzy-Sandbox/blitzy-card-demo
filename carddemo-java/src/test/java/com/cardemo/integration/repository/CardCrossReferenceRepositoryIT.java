package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link CardCrossReferenceRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code CARDXREF}
 * <em>together with</em> its alternate index {@code CXACAIX} and PATH.
 *
 * <p>On the mainframe, {@code CARDXREF} and its alternate index were provisioned by
 * {@code app/jcl/XREFFILE.jcl}: the base cluster
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS KEYS(16 0)
 * RECORDSIZE(50 50) INDEXED}), then the NON-UNIQUE alternate index
 * ({@code DEFINE ALTERNATEINDEX ... AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX KEYS(11,25)
 * NONUNIQUEKEY UPGRADE}, STEP20) plus its {@code DEFINE PATH} (STEP25) and
 * {@code BLDINDEX} (STEP30). The fixed-length 50-byte record layout was defined by
 * copybook {@code app/cpy/CVACT03Y.cpy} ({@code 01 CARD-XREF-RECORD}:
 * {@code XREF-CARD-NUM PIC X(16)} @&nbsp;0, {@code XREF-CUST-ID PIC 9(09)} @&nbsp;16,
 * {@code XREF-ACCT-ID PIC 9(11)} @&nbsp;25, {@code FILLER PIC X(14)}). In the migrated
 * stack the same data lives in the PostgreSQL {@code card_xref} table mapped by
 * {@link CardCrossReference}, and every access path the COBOL programs used is served
 * through the Spring Data {@link CardCrossReferenceRepository}.</p>
 *
 * <h2>What this class proves &mdash; the two preserved access paths</h2>
 * <p>The cross-reference is the small but operationally critical <em>junction</em>
 * record linking a card to both its owning account and its owning customer
 * ({@code card }&rarr;{@code  (account, customer)}). Two physical VSAM access paths
 * existed and both must be preserved by the relational mapping; these tests exercise
 * each one against the canonical seed:</p>
 * <ul>
 *   <li><strong>Primary key &mdash; {@code XREF-CARD-NUM} (16-byte cluster key).</strong>
 *       The {@code READ DATASET('CARDXREF') RIDFLD(XREF-CARD-NUM)} keyed read (e.g. the
 *       {@code COTRN02C} card-key read) maps to the inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object)
 *       findById(String)}; covered by {@link #findById_returnsSeededXref()} and the
 *       secondary spot-check inside {@link #count_matchesSeededRowCount()}.</li>
 *   <li><strong>Alternate index &mdash; {@code CXACAIX} (account id, NON-UNIQUE).</strong>
 *       The {@code KEYS(11,25)} alternate key on {@code XREF-ACCT-ID} served the
 *       account&nbsp;&rarr;&nbsp;card(s) read path. Because it is {@code NONUNIQUEKEY},
 *       one account may own several cards, so the lookup returns <em>zero, one or
 *       many</em> rows &mdash; never a scalar. AAP &sect;0.4.2 maps it onto the derived
 *       query {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}, the central
 *       account&rarr;card(s) resolution used online by {@code COACTVWC}
 *       ({@code 9200-GETCARDXREF-BYACCT}, which reads {@code CARD-XREF-RECORD} through
 *       the {@code 'CXACAIX'} path), {@code COCRDLIC} (account-filtered card list) and
 *       {@code COTRN02C} ({@code READ-CXACAIX-FILE RIDFLD(XREF-ACCT-ID)}), and in batch
 *       by {@code CBACT03C}/{@code CBTRN02C}/{@code CBSTM03A}. It is exercised by
 *       {@link #findByXrefAcctId_returnsXrefsForAccount()} (the
 *       account&rarr;card(s) pivot) and {@link #findByXrefAcctId_unknownAccount_returnsEmptyList()}
 *       (the empty-result contract). This is the single most important contract on the
 *       repository, so it is the primary focus of this suite.</li>
 * </ul>
 *
 * <h2>Behavioral-parity ground truth</h2>
 * <p>The assertions below pin the <em>exact</em> values seeded from the canonical ASCII
 * fixture {@code app/data/ASCII/cardxref.txt} (the COBOL parity ground truth) which the
 * Flyway {@code V3__seed_data.sql} migration loads into the throwaway container. That
 * fixture holds {@value #SEEDED_XREF_ROWS} cross-reference rows (36-byte records:
 * 16-byte card number + 9-digit customer id + 11-digit account id; the trailing
 * {@code FILLER PIC X(14)} is absent from the fixture and unmapped in the entity), so
 * {@code count()} must return exactly {@value #SEEDED_XREF_ROWS}. The anchor row is card
 * {@value #ANCHOR_CARD_NUMBER} &rarr; account&nbsp;{@code 50} / customer&nbsp;{@code 50}
 * (cardxref.txt row&nbsp;1); the secondary spot-check uses card
 * {@value #SECONDARY_CARD_NUMBER} &rarr; account&nbsp;{@code 27} / customer&nbsp;{@code 27}
 * (row&nbsp;2). In this fixture the account&rarr;card relationship happens to be 1:1, so
 * {@code findByXrefAcctId(50L)} returns a single row; the alternate-index assertions
 * therefore verify {@code >= 1} result and anchor-card membership rather than hard-coding
 * a count, keeping the test robust to a future many-cards-per-account seed (the NON-UNIQUE
 * index legitimately permits it).</p>
 *
 * <h2>String primary key &mdash; {@code XREF-CARD-NUM PIC X(16)}</h2>
 * <p>The repository is typed {@code JpaRepository<CardCrossReference, String>}: the
 * identifier is a {@link String}, not a numeric type, because {@code XREF-CARD-NUM} is the
 * 16-character Primary Account Number (PAN). It is kept as a {@code String} (PostgreSQL
 * {@code VARCHAR(16)}) so that any leading characters are preserved exactly and the value
 * matches the keyed VSAM access and {@code Card.cardNum}; keyed reads therefore use the
 * 16-character {@code String} literal {@value #ANCHOR_CARD_NUMBER}, never a numeric key.
 * By contrast the {@code CXACAIX} alternate key {@code XREF-ACCT-ID PIC 9(11)} is a
 * {@link Long} ({@code BIGINT}, since eleven digits exceed {@code Integer}'s
 * ~2.1-billion ceiling) and surfaces through {@code findByXrefAcctId(Long)}.</p>
 *
 * <h2>No optimistic locking ({@code @Version}) &mdash; unlike {@code Account}/{@code Card}</h2>
 * <p>{@link CardCrossReference} carries <strong>no</strong> JPA {@code @Version} column: per
 * AAP &sect;0.7.5 optimistic locking is applied <strong>only</strong> to {@code Account}
 * (in {@code COACTUPC}) and {@code Card} (in {@code COCRDUPC}). The cross-reference record
 * is never updated in place through a read-update snapshot comparison, so this class
 * contains <strong>no</strong> optimistic-locking test &mdash; that coverage lives in the
 * sibling {@code AccountRepositoryIT}/{@code CardRepositoryIT}.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the singleton
 * PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")}
 * context configuration, the {@code @DynamicPropertySource} datasource wiring, and the
 * {@code protected} {@code jdbcTemplate}/{@code entityManager} helpers. None of those are
 * re-declared here, so this class shares the one cached Spring context and one Flyway
 * migration with its sibling repository ITs (the performance keystone of the suite). The
 * class is annotated {@link Transactional} so each test method runs in its own transaction
 * that is rolled back on completion; although every test here is read-only, this keeps
 * isolation uniform with the mutating sibling ITs and leaves the seeded data pristine.</p>
 *
 * <h2>Notes on the values asserted (authoritative-source reconciliation)</h2>
 * <ul>
 *   <li>Raw SQL cross-checks target the table named {@code card_xref}, matching
 *       {@code @Table(name = "card_xref")} on {@link CardCrossReference} and the
 *       {@code V1__create_schema.sql} DDL ({@code card_number VARCHAR(16)} primary key,
 *       {@code customer_id BIGINT}, {@code account_id BIGINT}).</li>
 *   <li>The {@code findByXrefAcctId} lookup is index-served by the non-unique B-tree index
 *       {@code idx_card_xref_account_id} created in {@code V2__create_indexes.sql} &mdash;
 *       the relational realization of {@code CXACAIX}; the index is a performance concern of
 *       the schema and does not change the contract under test.</li>
 *   <li>{@link CardCrossReferenceRepository} declares exactly <strong>one</strong> custom
 *       query method (Minimal Change Clause, AAP &sect;0.7.1): {@code findByXrefAcctId};
 *       every primary-key operation is inherited from {@code JpaRepository}. This suite
 *       therefore exercises that single derived query plus the inherited
 *       {@code findById}/{@code count} operations &mdash; nothing more.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; behavior under test is translated from the frozen AWS CardDemo COBOL baseline
 * at commit SHA {@code 27d6c6f}. The COBOL/JCL sources ({@code app/jcl/XREFFILE.jcl},
 * {@code app/cbl/COACTVWC.cbl}, {@code app/cbl/CBACT03C.cbl}) and the ASCII fixture
 * ({@code app/data/ASCII/cardxref.txt}) are read-only reference material and are never
 * copied into this repository.</p>
 *
 * @see CardCrossReferenceRepository
 * @see CardCrossReference
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("CardCrossReferenceRepository (CARDXREF + CXACAIX / COACTVWC, COCRDLIC, COTRN02C, CBACT03C) — behavioral-parity integration tests")
class CardCrossReferenceRepositoryIT extends AbstractRepositoryIT {

    /**
     * The anchor card number ({@code XREF-CARD-NUM PIC X(16)}) &mdash; row&nbsp;1 of
     * {@code app/data/ASCII/cardxref.txt}. Kept as a 16-character {@link String} because
     * the migrated primary key is {@code VARCHAR(16)} (the PAN), not a numeric type.
     */
    private static final String ANCHOR_CARD_NUMBER = "0500024453765740";

    /**
     * The owning account id of {@link #ANCHOR_CARD_NUMBER} ({@code XREF-ACCT-ID PIC 9(11)})
     * &mdash; the {@code CXACAIX} alternate-index key value for the anchor row.
     */
    private static final Long ANCHOR_ACCOUNT_ID = 50L;

    /** The owning customer id of {@link #ANCHOR_CARD_NUMBER} ({@code XREF-CUST-ID PIC 9(09)}). */
    private static final Long ANCHOR_CUSTOMER_ID = 50L;

    /**
     * Secondary anchor card number &mdash; row&nbsp;2 of {@code cardxref.txt} &mdash; used by
     * the spot-check in {@link #count_matchesSeededRowCount()} to confirm a second, distinct
     * account/customer pair round-trips correctly (not just a single-row coincidence).
     */
    private static final String SECONDARY_CARD_NUMBER = "0683586198171516";

    /** The owning account id of {@link #SECONDARY_CARD_NUMBER}. */
    private static final Long SECONDARY_ACCOUNT_ID = 27L;

    /** The owning customer id of {@link #SECONDARY_CARD_NUMBER}. */
    private static final Long SECONDARY_CUSTOMER_ID = 27L;

    /**
     * An account id guaranteed absent from the {@value #SEEDED_XREF_ROWS}-row fixture, used to
     * assert the empty-result contract of the {@code CXACAIX} lookup.
     */
    private static final Long UNKNOWN_ACCOUNT_ID = 999_999L;

    /**
     * The exact number of cross-reference rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/cardxref.txt}.
     */
    private static final long SEEDED_XREF_ROWS = 50L;

    /** Repository under test &mdash; the relational replacement for {@code CARDXREF} + {@code CXACAIX}. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Verifies that a primary-key fetch of the anchor card returns the cross-reference row
     * seeded from {@code cardxref.txt} &mdash; the keyed
     * {@code READ DATASET('CARDXREF') RIDFLD(XREF-CARD-NUM)} that the COBOL programs performed
     * against the base cluster.
     *
     * <p>Asserts the primary key ({@code XREF-CARD-NUM}), the owning account
     * ({@code XREF-ACCT-ID}, the {@code CXACAIX} key) and the owning customer
     * ({@code XREF-CUST-ID}) all equal the exact seeded values for cardxref.txt row&nbsp;1.</p>
     */
    @Test
    @DisplayName("findById(\"0500024453765740\") returns the seeded cross-reference (XREF-CARD-NUM keyed read)")
    void findById_returnsSeededXref() {
        Optional<CardCrossReference> found = cardCrossReferenceRepository.findById(ANCHOR_CARD_NUMBER);

        assertThat(found)
                .as("card cross-reference %s must be present in the Flyway-seeded CARDXREF replacement",
                        ANCHOR_CARD_NUMBER)
                .isPresent();

        CardCrossReference xref = found.get();

        // Primary-key parity — XREF-CARD-NUM PIC X(16) -> String(16) VARCHAR(16) @Id.
        assertThat(xref.getXrefCardNum())
                .as("primary-key card number must round-trip exactly (16-char PAN, no numeric coercion)")
                .isEqualTo(ANCHOR_CARD_NUMBER);

        // Owning-account parity — XREF-ACCT-ID PIC 9(11) -> Long (BIGINT); this is the CXACAIX key.
        assertThat(xref.getXrefAcctId())
                .as("owning account id (CXACAIX key) for card %s must be %d",
                        ANCHOR_CARD_NUMBER, ANCHOR_ACCOUNT_ID)
                .isEqualTo(ANCHOR_ACCOUNT_ID);

        // Owning-customer parity — XREF-CUST-ID PIC 9(09) -> Long (BIGINT).
        assertThat(xref.getXrefCustId())
                .as("owning customer id for card %s must be %d", ANCHOR_CARD_NUMBER, ANCHOR_CUSTOMER_ID)
                .isEqualTo(ANCHOR_CUSTOMER_ID);
    }

    /**
     * Verifies the {@code CXACAIX} alternate-index access pattern: looking up cross-references
     * by owning account id returns the account's card(s).
     *
     * <p>This is the JPA realization of the VSAM NON-UNIQUE alternate index
     * {@code CXACAIX KEYS(11,25)} on {@code XREF-ACCT-ID} &mdash; the account&rarr;card(s)
     * pivot that {@code COACTVWC} ({@code 9200-GETCARDXREF-BYACCT}), {@code COCRDLIC} and
     * {@code COTRN02C} ({@code READ-CXACAIX-FILE}) relied on. Because the index is NON-UNIQUE
     * the result is a {@link List}: the test asserts it is non-empty, that <em>every</em>
     * returned row belongs to the requested account, and that it includes the anchor card. The
     * fixture is 1:1 here, so exactly one row is returned; membership/containment is asserted
     * rather than a hard-coded count so the test stays valid if a future seed maps several cards
     * to one account (which the NON-UNIQUE index permits).</p>
     */
    @Test
    @DisplayName("findByXrefAcctId(50L) returns the account's cross-reference(s) (CXACAIX alternate-index lookup)")
    void findByXrefAcctId_returnsXrefsForAccount() {
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(ANCHOR_ACCOUNT_ID);

        assertThat(xrefs)
                .as("the CXACAIX lookup for account %d must return at least one cross-reference",
                        ANCHOR_ACCOUNT_ID)
                .isNotNull()
                .isNotEmpty();

        // Every row returned by the alternate-index lookup must belong to the requested account
        // (the WHERE account_id = ? clause that Spring Data derives from the method name).
        assertThat(xrefs)
                .as("every cross-reference returned by findByXrefAcctId(%d) must belong to that account",
                        ANCHOR_ACCOUNT_ID)
                .allSatisfy(xref -> assertThat(xref.getXrefAcctId()).isEqualTo(ANCHOR_ACCOUNT_ID));

        // The account->card(s) resolution must include the anchor card 0500024453765740.
        assertThat(xrefs)
                .as("the account->card(s) resolution for account %d must include the anchor card %s",
                        ANCHOR_ACCOUNT_ID, ANCHOR_CARD_NUMBER)
                .extracting(CardCrossReference::getXrefCardNum)
                .contains(ANCHOR_CARD_NUMBER);
    }

    /**
     * Verifies the empty-result contract of the {@code CXACAIX} lookup: an account that owns no
     * cards yields an empty &mdash; but non-{@code null} &mdash; {@link List}, never an error.
     *
     * <p>This mirrors a VSAM alternate-index browse that finds no matching base records
     * ({@code STARTBR}/{@code READNEXT} reaching end-of-file with no hit). Spring Data returns an
     * empty collection (never {@code null}) for a {@code List}-returning derived query, which the
     * online and batch consumers branch on to detect "account has no cards".</p>
     */
    @Test
    @DisplayName("findByXrefAcctId(999999L) returns an empty (non-null) list")
    void findByXrefAcctId_unknownAccount_returnsEmptyList() {
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(UNKNOWN_ACCOUNT_ID);

        assertThat(xrefs)
                .as("an account (%d) with no cross-references must yield an empty list, never null and never an error",
                        UNKNOWN_ACCOUNT_ID)
                .isNotNull()
                .isEmpty();
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the
     * {@value #SEEDED_XREF_ROWS} rows that {@code cardxref.txt} / {@code V3__seed_data.sql}
     * provide &mdash; the count equivalent of the sequential {@code CARDXREF} dump that
     * {@code CBACT03C} performed ({@code STARTBR}/{@code READNEXT} until end-of-file). A raw-JDBC
     * {@code COUNT(*)} cross-check confirms the JPA count matches the physical table
     * {@code card_xref}, bypassing the persistence context entirely.
     *
     * <p>A secondary keyed spot-check (cardxref.txt row&nbsp;2, card
     * {@value #SECONDARY_CARD_NUMBER} &rarr; account&nbsp;{@code 27} / customer&nbsp;{@code 27})
     * confirms a second, distinct account/customer pair round-trips correctly, so the parity is
     * not a single-row coincidence.</p>
     */
    @Test
    @DisplayName("count() equals the 50 seeded rows (raw-JDBC cross-checked); spot-check 0683586198171516 -> acct/cust 27")
    void count_matchesSeededRowCount() {
        assertThat(cardCrossReferenceRepository.count())
                .as("CARDXREF replacement must contain exactly the %d seeded fixture rows", SEEDED_XREF_ROWS)
                .isEqualTo(SEEDED_XREF_ROWS);

        // Cross-check against the physical table (named "card_xref") using the inherited
        // JdbcTemplate, bypassing the JPA persistence context entirely.
        Long jdbcCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM card_xref", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) on card_xref must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_XREF_ROWS);

        // Secondary-row spot check (cardxref.txt row 2): a second keyed read confirms the seed is
        // not a single-row coincidence and that a different account/customer pair round-trips.
        CardCrossReference secondary = cardCrossReferenceRepository.findById(SECONDARY_CARD_NUMBER)
                .orElseThrow(() -> new IllegalStateException(
                        "seed data missing secondary anchor card " + SECONDARY_CARD_NUMBER));
        assertThat(secondary.getXrefAcctId())
                .as("owning account id for card %s must be %d", SECONDARY_CARD_NUMBER, SECONDARY_ACCOUNT_ID)
                .isEqualTo(SECONDARY_ACCOUNT_ID);
        assertThat(secondary.getXrefCustId())
                .as("owning customer id for card %s must be %d", SECONDARY_CARD_NUMBER, SECONDARY_CUSTOMER_ID)
                .isEqualTo(SECONDARY_CUSTOMER_ID);
    }
}
