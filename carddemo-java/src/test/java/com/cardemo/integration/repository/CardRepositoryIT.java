package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link CardRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational
 * replacement of the legacy AWS CardDemo VSAM KSDS dataset {@code CARDDAT}
 * <em>together with</em> its alternate index {@code CARDAIX}.
 *
 * <p>On the mainframe, {@code CARDDAT} was provisioned by
 * {@code app/jcl/CARDFILE.jcl}: a base cluster
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS KEYS(16 0)
 * RECORDSIZE(150 150) INDEXED}) keyed on the 16-byte {@code CARD-NUM}, plus a
 * <strong>non-unique</strong> alternate index
 * ({@code DEFINE ALTERNATEINDEX ... CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY}
 * with its {@code DEFINE PATH} + {@code BLDINDEX}) keyed on the 11-byte
 * {@code CARD-ACCT-ID} at offset&nbsp;16 &mdash; the {@code CARDAIX} access path.
 * The 150-byte record layout is defined by copybook {@code app/cpy/CVACT02Y.cpy}.
 * The dataset was reached by keyed CICS file control in the online programs
 * {@code COCRDLIC} (account-filtered, paginated card list), {@code COCRDSLC}
 * (single keyed card detail) and {@code COCRDUPC} (read-before-update card
 * change), and was read sequentially by the batch program {@code CBACT02C}
 * ({@code ACCESS MODE IS SEQUENTIAL}, a {@code READ ... NEXT} dump). In the
 * migrated stack the same data lives in the PostgreSQL {@code card} table mapped
 * by {@link Card}, and every access path is served through the Spring Data
 * {@link CardRepository}.</p>
 *
 * <h2>What these tests prove</h2>
 * <ul>
 *   <li><strong>Primary-key access</strong> &mdash; {@code findById(String)} over
 *       the 16-digit Primary Account Number reproduces the {@code COCRDSLC} keyed
 *       detail read (and the {@code COCRDUPC} read-before-update).</li>
 *   <li><strong>{@code CARDAIX} alternate-index access</strong> &mdash; the two
 *       {@code findByCardAcctId} overloads (unpaginated {@link List} and paginated
 *       {@link Page}) reproduce the non-unique alternate-index lookup by owning
 *       account id. The paginated overload preserves the exact {@code COCRDLIC}
 *       online browse, whose 3270 screen displayed {@code OCCURS 7 TIMES} rows with
 *       PF7/PF8 page navigation &mdash; hence the page size of {@value #COCRDLIC_PAGE_SIZE}.</li>
 *   <li><strong>Count parity</strong> &mdash; {@code count()} equals the
 *       {@value #SEEDED_CARD_ROWS} fixture rows, the count equivalent of the
 *       sequential {@code CBACT02C} dump.</li>
 *   <li><strong>{@code @Version} optimistic locking</strong> &mdash; a stale write
 *       is rejected, reproducing the {@code COCRDUPC} read-update snapshot-comparison
 *       concurrency control.</li>
 * </ul>
 *
 * <h2>Behavioral-parity ground truth</h2>
 * <p>The assertions pin the <em>exact</em> values that {@code COCRDSLC} would have
 * displayed for the anchor card {@value #ANCHOR_CARD_NUMBER}, taken from the
 * canonical ASCII fixture {@code app/data/ASCII/carddata.txt} (the COBOL parity
 * ground truth) which the Flyway {@code V3__seed_data.sql} migration loads into the
 * throwaway container. That fixture holds {@value #SEEDED_CARD_ROWS} card records,
 * so {@code count()} must return exactly {@value #SEEDED_CARD_ROWS}. The anchor card
 * belongs to account&nbsp;50 (Aniya&nbsp;Von), the {@code carddata.txt} parity
 * anchor.</p>
 *
 * <h2>Authoritative-source reconciliation (assertion types)</h2>
 * <p>The assertions follow the authoritative dependency artifacts (the {@link Card}
 * entity and {@code V1__create_schema.sql} / {@code V3__seed_data.sql}) rather than
 * any looser prose, in three respects derived from the fixed 150-byte
 * {@code CVACT02Y} layout:</p>
 * <ul>
 *   <li>{@code CARD-CVV-CD PIC 9(03)} maps to an {@link Integer} ({@code cvv_code
 *       INTEGER}); the CVV is therefore asserted as the numeric value {@code 747},
 *       not as a {@code String}.</li>
 *   <li>{@code CARD-EXPIRAION-DATE PIC X(10)} maps to a {@link LocalDate}
 *       ({@code expiration_date DATE}); the expiration is therefore asserted as
 *       {@link LocalDate#parse(CharSequence) LocalDate.parse("2023-03-09")}.</li>
 *   <li>Raw-SQL cross-checks target the table named {@code card} (singular),
 *       matching {@code @Table(name = "card")} on {@link Card} and the
 *       {@code V1__create_schema.sql} DDL &mdash; <em>not</em> a pluralized name.</li>
 * </ul>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the
 * singleton PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} +
 * {@code @ActiveProfiles("test")} context configuration, the
 * {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers. None of those are re-declared
 * here, so this class shares the one cached Spring context and one Flyway migration
 * with its sibling repository ITs (the single most important performance decision in
 * the suite). The class is annotated {@link Transactional} so each test method runs
 * in its own transaction that is rolled back on completion &mdash; this isolates the
 * mutating optimistic-lock test from the read-only tests and leaves the seeded data
 * pristine for sibling classes.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source
 * equivalent; behavior under test is translated from the frozen AWS CardDemo COBOL
 * baseline at commit SHA {@code 27d6c6f}. The COBOL/JCL sources and the ASCII
 * fixtures are read-only reference material and are never copied into this
 * repository.</p>
 *
 * @see CardRepository
 * @see Card
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("CardRepository (CARDDAT + CARDAIX / COCRDLIC, COCRDSLC, COCRDUPC, CBACT02C) — behavioral-parity integration tests")
class CardRepositoryIT extends AbstractRepositoryIT {

    /**
     * The 16-digit card number (Primary Account Number) of the parity anchor row
     * seeded from {@code carddata.txt}. Leading zeros are significant and are
     * preserved exactly because the key is a {@link String} ({@code VARCHAR(16)}),
     * mirroring the {@code CARD-NUM PIC X(16)} keyed VSAM access of {@code COCRDSLC}.
     */
    private static final String ANCHOR_CARD_NUMBER = "0500024453765740";

    /**
     * The owning account id of {@link #ANCHOR_CARD_NUMBER} ({@code CARD-ACCT-ID}).
     * It is the {@code CARDAIX} alternate-key value exercised by the
     * {@code findByCardAcctId} overloads.
     */
    private static final Long ANCHOR_ACCOUNT_ID = 50L;

    /** The exact number of card rows seeded by {@code V3__seed_data.sql}. */
    private static final long SEEDED_CARD_ROWS = 50L;

    /**
     * The {@code COCRDLIC} online-browse page size: the 3270 card-list screen
     * displayed {@code OCCURS 7 TIMES} rows per page (PF7/PF8 navigation).
     */
    private static final int COCRDLIC_PAGE_SIZE = 7;

    /**
     * An account id that is guaranteed absent from the {@value #SEEDED_CARD_ROWS}-row
     * fixture, used to assert the empty-result alternate-index path.
     */
    private static final Long UNKNOWN_ACCOUNT_ID = 999_999L;

    /** Repository under test &mdash; the relational replacement for {@code CARDDAT} + {@code CARDAIX}. */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Verifies that a primary-key fetch of the anchor card returns the row with the
     * exact field values seeded from {@code carddata.txt} &mdash; the keyed read that
     * {@code COCRDSLC} performed against {@code CARDDAT}.
     *
     * <p>The {@code String} primary key preserves the 16-digit PAN (including any
     * leading zeros); the CVV is asserted as an {@link Integer} and the expiration as
     * a {@link LocalDate}, matching the authoritative {@link Card} mapping and the
     * {@code V1} DDL ({@code cvv_code INTEGER}, {@code expiration_date DATE}).</p>
     */
    @Test
    @DisplayName("findById(\"0500024453765740\") returns the seeded card (COCRDSLC keyed detail read)")
    void findById_returnsSeededCard() {
        Optional<Card> found = cardRepository.findById(ANCHOR_CARD_NUMBER);

        assertThat(found)
                .as("anchor card %s must be present in the Flyway-seeded CARDDAT replacement",
                        ANCHOR_CARD_NUMBER)
                .isPresent();

        Card card = found.get();

        // CARDAIX alternate key — owning account id (CARD-ACCT-ID PIC 9(11) -> BIGINT).
        assertThat(card.getCardAcctId()).isEqualTo(ANCHOR_ACCOUNT_ID);

        // CARD-CVV-CD PIC 9(03) -> Integer (cvv_code INTEGER); numeric value, not "747".
        assertThat(card.getCardCvvCd()).isEqualTo(747);

        // CARD-EMBOSSED-NAME PIC X(50) -> String; the 50-char field is space-padded in
        // the fixture, so assert containment rather than exact equality.
        assertThat(card.getCardEmbossedName()).contains("Aniya Von");

        // CARD-EXPIRAION-DATE PIC X(10) 'YYYY-MM-DD' -> LocalDate (expiration_date DATE).
        assertThat(card.getCardExpirationDate()).isEqualTo(LocalDate.parse("2023-03-09"));

        // CARD-ACTIVE-STATUS PIC X(01) -> single-character String flag.
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");
    }

    /**
     * Verifies full-table count parity: the repository reports exactly the
     * {@value #SEEDED_CARD_ROWS} rows that {@code carddata.txt} /
     * {@code V3__seed_data.sql} provide &mdash; the count equivalent of the
     * sequential {@code READ ... NEXT} dump that {@code CBACT02C} performed over
     * {@code CARDDAT}. A raw-JDBC {@code COUNT(*)} cross-check confirms the JPA count
     * matches the physical table {@code card} (singular).
     */
    @Test
    @DisplayName("count() equals the 50 seeded rows (cross-checked via raw JDBC against table \"card\")")
    void count_matchesSeededRowCount() {
        assertThat(cardRepository.count())
                .as("CARDDAT replacement must contain exactly the 50 seeded fixture rows")
                .isEqualTo(SEEDED_CARD_ROWS);

        // Cross-check against the physical table (named "card", singular) using the
        // inherited JdbcTemplate, bypassing the JPA persistence context entirely.
        Long jdbcCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM card", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) FROM card must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_CARD_ROWS);
    }

    /**
     * Verifies the unpaginated {@code CARDAIX} alternate-index lookup
     * {@code findByCardAcctId(Long)}: every returned row belongs to the requested
     * account and the anchor card is present.
     *
     * <p>Because the legacy {@code CARDAIX} alternate index was {@code NONUNIQUEKEY},
     * the method returns a (possibly multi-row) {@link List}. In this fixture the
     * card&harr;account mapping is 1:1, so account&nbsp;50 owns exactly one card;
     * the test asserts non-emptiness, per-row account
     * membership, and anchor membership rather than hardcoding the cardinality, so it
     * stays robust if the fixture later gains additional cards for the account.</p>
     */
    @Test
    @DisplayName("findByCardAcctId(50L) returns the account's cards (CARDAIX list form)")
    void findByCardAcctId_list_returnsCardsForAccount() {
        List<Card> cards = cardRepository.findByCardAcctId(ANCHOR_ACCOUNT_ID);

        assertThat(cards)
                .as("the CARDAIX lookup for account %d must return at least one card", ANCHOR_ACCOUNT_ID)
                .isNotEmpty();

        // Every row produced by the alternate-index lookup must belong to the account.
        assertThat(cards)
                .allSatisfy(card -> assertThat(card.getCardAcctId()).isEqualTo(ANCHOR_ACCOUNT_ID));

        // The anchor card must be among the account's cards.
        assertThat(cards)
                .extracting(Card::getCardNum)
                .contains(ANCHOR_CARD_NUMBER);
    }

    /**
     * Verifies the paginated {@code CARDAIX} alternate-index lookup
     * {@code findByCardAcctId(Long, Pageable)} that reproduces the {@code COCRDLIC}
     * online browse.
     *
     * <p>The caller supplies a page size of {@value #COCRDLIC_PAGE_SIZE} via
     * {@link PageRequest#of(int, int)} (the original 7-rows-per-page screen
     * contract). The returned {@link Page} reports that requested page size through
     * {@link Page#getSize()} (independent of how many rows the page actually holds),
     * its {@link Page#getContent() content} never exceeds the page size, and every
     * content row belongs to the requested account &mdash; confirming the paginated
     * alternate-index browse contract.</p>
     */
    @Test
    @DisplayName("findByCardAcctId(50L, PageRequest.of(0,7)) honors the COCRDLIC 7-rows/page browse")
    void findByCardAcctId_page_respectsPageable() {
        Pageable firstPage = PageRequest.of(0, COCRDLIC_PAGE_SIZE);

        Page<Card> page = cardRepository.findByCardAcctId(ANCHOR_ACCOUNT_ID, firstPage);

        // getSize() is the requested page size (the COCRDLIC 7-row screen contract),
        // independent of the number of rows actually returned on this page.
        assertThat(page.getSize())
                .as("page size must reflect the requested COCRDLIC 7-rows-per-page contract")
                .isEqualTo(COCRDLIC_PAGE_SIZE);

        // A single page can hold at most the page size; account 50 owns one card here.
        assertThat(page.getContent())
                .as("a single page must not exceed the requested page size")
                .hasSizeLessThanOrEqualTo(COCRDLIC_PAGE_SIZE)
                .isNotEmpty();

        // Every row on the page must belong to the requested account.
        assertThat(page.getContent())
                .allSatisfy(card -> assertThat(card.getCardAcctId()).isEqualTo(ANCHOR_ACCOUNT_ID));

        // The anchor card must appear on the first page of its account's browse.
        assertThat(page.getContent())
                .extracting(Card::getCardNum)
                .contains(ANCHOR_CARD_NUMBER);
    }

    /**
     * Verifies that the {@code CARDAIX} lookup for an account that owns no cards
     * yields empty results rather than throwing &mdash; the JPA equivalent of an
     * alternate-index browse that positions past end-of-data with no matching
     * records. Both overloads are exercised: the {@link List} form returns an empty
     * list and the {@link Page} form returns an empty page (zero total elements).
     */
    @Test
    @DisplayName("findByCardAcctId(unknown) returns an empty list and an empty page")
    void findByCardAcctId_unknownAccount_returnsEmpty() {
        assertThat(cardRepository.findByCardAcctId(UNKNOWN_ACCOUNT_ID))
                .as("an account with no cards must produce an empty list, not an error")
                .isEmpty();

        Page<Card> page =
                cardRepository.findByCardAcctId(UNKNOWN_ACCOUNT_ID, PageRequest.of(0, COCRDLIC_PAGE_SIZE));

        assertThat(page)
                .as("the paginated CARDAIX lookup for an unknown account must be an empty page")
                .isEmpty();
        assertThat(page.getTotalElements())
                .as("an empty page must report zero total elements")
                .isZero();
    }

    /**
     * Verifies the JPA {@code @Version} optimistic-locking semantics that replace the
     * {@code COCRDUPC} read-before-update snapshot comparison: a write based on a
     * stale in-memory copy is rejected with
     * {@link ObjectOptimisticLockingFailureException}.
     *
     * <p><strong>Technique (documented per the file specification, mirroring
     * {@code AccountRepositoryIT}).</strong> Because this class is
     * {@link Transactional}, a competing update is simulated <em>in&nbsp;process</em>
     * via the inherited {@code jdbcTemplate}, which shares this test's
     * transaction-bound JDBC connection. The sequence is:</p>
     * <ol>
     *   <li>Load the anchor card through the repository, capturing its managed
     *       {@code version} (the seeded value is {@code 0}, per the {@code V1} DDL
     *       {@code version BIGINT NOT NULL DEFAULT 0}).</li>
     *   <li>Bump the {@code version} column out-of-band with a direct
     *       {@code UPDATE card SET version = version + 1 WHERE card_number = ?};
     *       on the shared connection the row's version becomes {@code 1}.</li>
     *   <li>Mutate the now-stale managed entity (whose in-memory version is still
     *       {@code 0}) and force a flush with {@code saveAndFlush}.</li>
     * </ol>
     * <p>Hibernate's versioned {@code UPDATE} carries {@code WHERE version = 0}, but
     * the row already holds version {@code 1}, so zero rows are affected and Hibernate
     * raises a {@code StaleObjectStateException}, which Spring Data translates to
     * {@link ObjectOptimisticLockingFailureException}. {@code saveAndFlush} (rather
     * than {@code save}) is required so the failure is raised synchronously inside the
     * assertion rather than being deferred to transaction commit. This mirrors the
     * exact concurrent-update rejection (<q>Record changed by some one else. Please
     * review</q>) that {@code COCRDUPC} produced on the mainframe.</p>
     */
    @Test
    @DisplayName("saveAndFlush of a stale entity throws ObjectOptimisticLockingFailureException (@Version / COCRDUPC parity)")
    void saveAndFlush_staleVersion_throwsOptimisticLockException() {
        Card card = cardRepository.findById(ANCHOR_CARD_NUMBER)
                .orElseThrow(() -> new IllegalStateException(
                        "seed data missing anchor card " + ANCHOR_CARD_NUMBER));

        Long originalVersion = card.getVersion();
        assertThat(originalVersion)
                .as("seeded @Version must be initialized (V1 DDL default 0)")
                .isNotNull();

        // Simulate a competing transaction committing a newer image of the row. The
        // inherited JdbcTemplate runs on this test's transaction-bound connection, so
        // the bumped version is visible to Hibernate's subsequent versioned UPDATE.
        // NOTE: the table is named "card" (singular), matching @Table(name = "card").
        int rowsBumped = jdbcTemplate.update(
                "UPDATE card SET version = version + 1 WHERE card_number = ?", ANCHOR_CARD_NUMBER);
        assertThat(rowsBumped)
                .as("the out-of-band version bump must affect exactly the anchor card")
                .isEqualTo(1);

        // The in-memory entity is now stale (its version still equals originalVersion).
        // Mutating it and flushing must be rejected by the optimistic-lock check.
        card.setCardActiveStatus("N");

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> cardRepository.saveAndFlush(card),
                "a stale @Version write must be rejected, reproducing the COCRDUPC "
                        + "read-before-update concurrency control");
    }
}
