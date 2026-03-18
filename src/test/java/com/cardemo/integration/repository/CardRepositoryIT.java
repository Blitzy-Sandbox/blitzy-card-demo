package com.cardemo.integration.repository;

import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link CardRepository} against a real PostgreSQL 16 database
 * managed by Testcontainers.
 *
 * <p>Validates JPA entity mapping for the Card entity derived from the COBOL CARDDAT
 * VSAM KSDS dataset (CARDFILE.jcl KEYS(16 0) RECORDSIZE(150 150), CVACT02Y.cpy
 * 150-byte fixed-width record layout).</p>
 *
 * <p>Tests exercise the following access patterns:</p>
 * <ul>
 *   <li>Primary key read by 16-char card number (VSAM KSDS keyed read)</li>
 *   <li>Alternate index query by 11-char account ID
 *       (CARDDATA.VSAM.AIX KEYS(11,16) NONUNIQUEKEY)</li>
 *   <li>Create and round-trip retrieval of all Card entity fields</li>
 *   <li>{@code @Version} optimistic locking for concurrent card update detection
 *       (preserving COCRDUPC.cbl before/after image comparison pattern)</li>
 *   <li>Paginated browse (COCRDLIC.cbl card list screen)</li>
 * </ul>
 *
 * <p>Seed data: 50 card records from {@code app/data/ASCII/carddata.txt} loaded via
 * Flyway V3 migration into the {@code cards} table.</p>
 *
 * @see Card
 * @see CardRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardRepositoryIT {

    /**
     * PostgreSQL 16 container providing a real database for integration tests.
     * Matches the production target database version specified in the migration plan.
     */
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("carddemo")
            .withUsername("test")
            .withPassword("test");

    /**
     * Injects Testcontainers-managed PostgreSQL connection properties into the Spring
     * context, overriding the TC JDBC URL mode from application-test.yml with explicit
     * container coordinates for deterministic connection management.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
    }

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ========================================================================
    // Test 1: Primary Key Read — VSAM KSDS KEYS(16 0)
    // Verifies keyed read by CARD-NUM PIC X(16), the primary index of the
    // CARDDATA VSAM KSDS cluster.
    // ========================================================================

    @Test
    @DisplayName("findById returns existing card with all fields correctly mapped from seed data")
    void testFindById_ExistingCard() {
        // First card in carddata.txt / V3 seed data:
        // card_num='0500024453765740', card_acct_id='00000000050', card_cvv_cd='747',
        // card_embossed_name='Aniya Von', expiration_date='2023-03-09', active_status='Y'
        Optional<Card> result = cardRepository.findById("0500024453765740");

        // Verify Optional presence (maps VSAM RESP=NORMAL for successful keyed read)
        assertThat(result.isPresent()).isTrue();

        Card card = result.get();

        // CARD-NUM: PIC X(16) — primary key, 16-character string
        assertThat(card.getCardNum()).isEqualTo("0500024453765740");

        // CARD-ACCT-ID: PIC 9(11) — 11-digit account identifier with leading zeros preserved
        assertThat(card.getCardAcctId()).isEqualTo("00000000050");

        // CARD-CVV-CD: PIC 9(03) — 3-digit CVV code preserved as String (leading zeros)
        assertThat(card.getCardCvvCd()).isEqualTo("747");

        // CARD-EMBOSSED-NAME: PIC X(50) — cardholder embossed name
        assertThat(card.getCardEmbossedName()).isEqualTo("Aniya Von");

        // CARD-EXPIRAION-DATE: PIC X(10) → Java LocalDate
        assertThat(card.getCardExpDate()).isNotNull();
        assertThat(card.getCardExpDate()).isEqualTo(LocalDate.of(2023, 3, 9));

        // CARD-ACTIVE-STATUS: PIC X(01) — single character status flag
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");
    }

    // ========================================================================
    // Test 2: Primary Key Read — Non-Existent Record
    // Verifies empty result for invalid key (maps VSAM RESP=NOTFND / STATUS=23)
    // ========================================================================

    @Test
    @DisplayName("findById returns empty Optional for non-existent card number")
    void testFindById_NonExistent() {
        Optional<Card> result = cardRepository.findById("9999999999999999");

        // Maps VSAM FILE STATUS 23 (record not found) — no exception, just empty result
        assertThat(result.isEmpty()).isTrue();
    }

    // ========================================================================
    // Test 3: Alternate Index Query — CARDDATA.VSAM.AIX KEYS(11,16) NONUNIQUEKEY
    // Verifies account-based card lookup via the alternate index path, which
    // returns a list since NONUNIQUEKEY allows multiple cards per account.
    // ========================================================================

    @Test
    @DisplayName("findByCardAcctId returns cards for existing account via AIX alternate index")
    void testFindByCardAcctId() {
        // Account '00000000050' has card(s) in seed data — exercises AIX NONUNIQUEKEY path
        List<Card> cards = cardRepository.findByCardAcctId("00000000050");

        // Verify list is populated (VSAM AIX BROWSE returned records)
        assertThat(cards.isEmpty()).isFalse();
        assertThat(cards.size()).isGreaterThanOrEqualTo(1);

        // Verify all returned cards belong to the queried account
        // Uses stream() to validate the alternate index filter correctness
        assertThat(cards.stream()
                .map(Card::getCardAcctId)
                .allMatch(id -> "00000000050".equals(id))).isTrue();
    }

    // ========================================================================
    // Test 4: Alternate Index Query — No Matching Records
    // Verifies empty list for account with no cards (AIX returns zero records)
    // ========================================================================

    @Test
    @DisplayName("findByCardAcctId returns empty list for non-existent account")
    void testFindByCardAcctId_NoCards() {
        List<Card> cards = cardRepository.findByCardAcctId("99999999999");

        // No matching records — empty list, no exception (VSAM ENDFILE equivalent)
        assertThat(cards).isEmpty();
    }

    // ========================================================================
    // Test 5: Save and Retrieve — Round-Trip Persistence Verification
    // Creates a new Card entity using no-args constructor and setters,
    // persists it, clears the first-level cache, and verifies all fields
    // survive the round-trip through PostgreSQL.
    // ========================================================================

    @Test
    @DisplayName("save persists new card and findById retrieves it with all fields intact")
    void testSaveAndRetrieve() {
        // Build card using no-args constructor + individual setters
        // to exercise all setter methods on the Card entity
        LocalDate futureExpDate = LocalDate.of(2028, 6, 15);
        Card newCard = new Card();
        newCard.setCardNum("0000000000000001");          // 16-char card number (not in seed data)
        newCard.setCardAcctId("00000000001");             // 11-char account ID (exists in seed data FK)
        newCard.setCardCvvCd("999");                      // 3-char CVV
        newCard.setCardEmbossedName("Integration Test User"); // Up to 50-char name
        newCard.setCardExpDate(futureExpDate);             // Future expiration date
        newCard.setCardActiveStatus("Y");                  // Active status flag

        // Save to database
        Card saved = cardRepository.save(newCard);

        // Flush pending SQL to PostgreSQL, then clear first-level cache
        // to force a true database round-trip on the subsequent findById
        entityManager.flush();
        entityManager.clear();

        // Retrieve from database — guaranteed not from JPA cache
        Optional<Card> retrieved = cardRepository.findById("0000000000000001");

        assertThat(retrieved.isPresent()).isTrue();
        Card card = retrieved.get();

        // Verify all fields survived the round-trip through PostgreSQL
        assertThat(card.getCardNum()).isEqualTo("0000000000000001");
        assertThat(card.getCardAcctId()).isEqualTo("00000000001");
        assertThat(card.getCardCvvCd()).isEqualTo("999");
        assertThat(card.getCardEmbossedName()).isEqualTo("Integration Test User");
        assertThat(card.getCardExpDate()).isEqualTo(futureExpDate);
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");

        // @Version field should be initialized to 0 by database DEFAULT
        assertThat(card.getVersion()).isEqualTo(0);
    }

    // ========================================================================
    // Test 6: @Version Optimistic Locking — COCRDUPC.cbl Concurrent Modification
    // Preserves the COBOL before/after record image comparison pattern for
    // detecting concurrent card updates. AAP §0.8.4 requires JPA @Version to
    // replace CICS READ UPDATE snapshot comparison semantics.
    // ========================================================================

    @Test
    @DisplayName("@Version optimistic locking detects concurrent card modifications (COCRDUPC.cbl pattern)")
    void testOptimisticLocking() {
        // Step 1: Create and persist a new card — version starts at 0
        Card card = new Card(
                "0000000000000002",   // Unique 16-char card number for this test
                "00000000001",        // Existing account ID in seed data (FK constraint)
                "456",                // CVV code
                "Locking Test User",  // Embossed name
                LocalDate.of(2028, 12, 31),  // Future expiration
                "Y"                   // Active
        );
        card = cardRepository.saveAndFlush(card);
        assertThat(card.getVersion()).isEqualTo(0);

        // Step 2: Update the card — version should auto-increment to 1
        card.setCardEmbossedName("Updated Locking User");
        card = cardRepository.saveAndFlush(card);
        assertThat(card.getVersion()).isEqualTo(1);

        // Step 3: Simulate concurrent modification scenario
        // Detach the entity to create a "stale" snapshot (version=1 in detached object)
        // Capture a final reference for lambda capture before detaching
        final Card staleCard = card;
        entityManager.detach(staleCard);

        // Reload a fresh managed copy from the database (also version=1)
        Card freshCopy = cardRepository.findById("0000000000000002").orElseThrow();
        assertThat(freshCopy.getVersion()).isEqualTo(1);

        // First concurrent update succeeds: fresh copy → version increments to 2
        freshCopy.setCardEmbossedName("Fresh Concurrent Update");
        cardRepository.saveAndFlush(freshCopy);

        // Step 4: Second concurrent update must fail — stale copy has version=1
        // but the database row is now at version=2. JPA detects the mismatch and
        // throws ObjectOptimisticLockingFailureException, preserving the COCRDUPC.cbl
        // before/after image comparison concurrency control pattern.
        staleCard.setCardEmbossedName("Stale Concurrent Update");
        assertThatThrownBy(() -> cardRepository.saveAndFlush(staleCard))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ========================================================================
    // Test 7: Pagination — COCRDLIC.cbl Paginated Card Browse
    // Verifies Spring Data JPA pagination over the cards table, replacing
    // the COBOL paginated browse pattern (7 rows/page in BMS screen).
    // ========================================================================

    @Test
    @DisplayName("findAll with pagination returns correct page of cards from seed data")
    void testPagination() {
        // Verify total seed data count via count() — 50 cards from carddata.txt
        long totalCards = cardRepository.count();
        assertThat(totalCards).isGreaterThanOrEqualTo(50L);

        // Request first page of 10 cards (configurable page size; COCRDLIC.cbl uses 7/page)
        Page<Card> page = cardRepository.findAll(PageRequest.of(0, 10));

        // Verify page contains data
        assertThat(page.hasContent()).isTrue();

        // Verify page content respects the requested page size
        assertThat(page.getContent().size()).isLessThanOrEqualTo(10);
        assertThat(page.getContent().size()).isEqualTo(10);

        // Verify total element count matches seed data
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(50L);
    }
}
