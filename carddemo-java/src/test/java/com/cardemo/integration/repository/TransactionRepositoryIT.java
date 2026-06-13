package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link TransactionRepository} executed against a real PostgreSQL&nbsp;16
 * Testcontainer, validating the relational replacement of the legacy AWS CardDemo VSAM KSDS dataset
 * {@code TRANSACT} <em>together with</em> its alternate index and path
 * ({@code app/jcl/TRANFILE.jcl}: {@code DEFINE CLUSTER ... KEYS(16 0)} keyed on the 16-byte
 * {@code TRAN-ID}, {@code DEFINE ALTERNATEINDEX}/{@code DEFINE PATH} over the 26-byte processed
 * timestamp). It is the most query-rich repository in the persistence layer and the only one whose
 * table starts <strong>empty</strong>.
 *
 * <h2>Why this IT is unique &mdash; the unseeded table / save-then-assert strategy</h2>
 * <p>Unlike every sibling repository IT (whose tables are populated by the Flyway
 * {@code V3__seed_data.sql} fixtures), the {@code transaction} table is <strong>intentionally NOT
 * seeded</strong> &mdash; production transactions are produced at <em>runtime</em> by the
 * daily-posting batch ({@code app/cbl/CBTRN02C.cbl}) and the online add transaction
 * ({@code app/cbl/COTRN02C.cbl}). There is therefore no static seed to inspect for parity. Instead,
 * each test first asserts the <em>empty-table invariants</em> (the unseeded-table parity assertion),
 * then persists FK-safe test rows <em>inside the rolled-back test transaction</em> and exercises the
 * repository contract against them ("save-then-assert").</p>
 *
 * <h2>Why the class is {@link Transactional} (rollback isolation)</h2>
 * <p>The PostgreSQL container is a <strong>shared singleton</strong> across all repository IT classes
 * (see {@link AbstractRepositoryIT}). Annotating this class {@link Transactional} runs every test
 * method in its own transaction that is rolled back on completion, so the rows saved here never
 * commit and the {@code transaction} table returns to <strong>zero rows</strong> for sibling IT
 * classes that share the cached context. This rollback isolation is essential precisely because this
 * is the one mutating repository tier whose table would otherwise leak rows into the singleton DB.</p>
 *
 * <h2>The four legacy access paths under test (AAP &sect;0.4.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li>{@link TransactionRepository#findByTranIdGreaterThanEqual(String,
 *       org.springframework.data.domain.Pageable)} &mdash; the paged greater-than-or-equal
 *       {@code TRAN-ID} browse of the online transaction-list program {@code COTRN00C}
 *       ({@code STARTBR} GTEQ positioning, {@code SEL0007I}/{@code TRNID07I} 7-rows-per-page screen).</li>
 *   <li>{@link TransactionRepository#findByProcessingDateRange(String, String)} &mdash; the
 *       DFSORT-style processing-date window of the batch report {@code CBTRN03C} /
 *       {@code app/jcl/TRANREPT.jcl} (the {@code TRAN-PROC-TS (1:10)} date prefix, inclusive bounds,
 *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)}).</li>
 *   <li>{@link TransactionRepository#findMaxTransactionId()} &mdash; the highest-key discovery the
 *       online add programs perform ({@code COTRN02C} L444-451:
 *       {@code MOVE HIGH-VALUES}&nbsp;&rarr;&nbsp;{@code STARTBR}&nbsp;&rarr;&nbsp;{@code READPREV}
 *       to position on the maximum id). An empty table surfaces as {@link Optional#empty()}, the JPA
 *       analogue of the COBOL empty-file&nbsp;&rarr;&nbsp;{@code ZEROS} branch.</li>
 *   <li>{@link TransactionRepository#findByTranCardNum(String)} &mdash; the per-card transaction
 *       gather of the batch statement-generation program {@code CBSTM03A}.</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>{@code TRAN-AMT PIC S9(09)V99} maps to {@link BigDecimal} {@code NUMERIC(11,2)}. Every amount
 * assertion uses AssertJ {@code isEqualByComparingTo} (which delegates to
 * {@link BigDecimal#compareTo(BigDecimal)}), <strong>never</strong> {@link BigDecimal#equals(Object)}
 * (which is scale-sensitive), and every expected literal is built with the {@code BigDecimal(String)}
 * constructor &mdash; never from a {@code double}.</p>
 *
 * <h2>Technology-substitution note &mdash; timestamps are {@link LocalDateTime} (Minimal Change
 * Clause, AAP &sect;0.7.1)</h2>
 * <p>The legacy {@code TRAN-PROC-TS}/{@code TRAN-ORIG-TS} {@code PIC X(26)} text fields
 * ({@code YYYY-MM-DD HH:MM:SS.ffffff}) are mapped by {@link Transaction} to {@link LocalDateTime}
 * (PostgreSQL {@code TIMESTAMP}). These tests therefore build rows with {@link LocalDateTime} values
 * and assert the processing-<em>date</em> portion via {@link LocalDateTime#toLocalDate()}. The
 * production {@code findByProcessingDateRange} query reproduces the COBOL {@code (1:10)} reference
 * modification with {@code SUBSTRING(CAST(t.tranProcTs AS string), 1, 10)}; on PostgreSQL a
 * {@code TIMESTAMP} renders as {@code YYYY-MM-DD HH:MM:SS[.ffffff]}, so the first ten characters are
 * the ISO date and a lexicographic range comparison equals a chronological one.</p>
 *
 * <h2>FK-safe test data</h2>
 * <p>Although the {@code transaction} table declares no foreign-key constraint, the test rows use
 * card numbers that <strong>exist in the Flyway-seeded {@code card}/{@code card_xref} data}
 * ({@value #CARD_PRIMARY} for account&nbsp;50 and {@value #CARD_SECONDARY}) so the fixtures remain
 * valid against any future referential constraint and faithfully mirror real, postable transactions.
 * The two distinct card numbers are chosen so {@value #CARD_PRIMARY} sorts lexicographically
 * <em>before</em> {@value #CARD_SECONDARY}, which makes the {@code ORDER BY t.tranCardNum} of
 * {@code findByProcessingDateRange} observable.</p>
 *
 * <h2>Scope (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>Only the repository contract is exercised. The {@code +1} increment and 16-character
 * zero-padding that {@code COTRN02C} applies to derive the next id are <strong>service-layer</strong>
 * concerns and are deliberately <strong>not</strong> tested here.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and inherits the singleton PostgreSQL&nbsp;16
 * container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")} context configuration, the
 * {@code @DynamicPropertySource} datasource wiring, and the {@code protected}
 * {@code jdbcTemplate}/{@code entityManager} helpers &mdash; none are re-declared, so this class
 * shares the one cached Spring context and one Flyway migration with its sibling repository ITs.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source equivalent; base
 * package {@code com.cardemo} (decision D-006). Behavior under test is translated from the frozen AWS
 * CardDemo COBOL baseline at commit SHA {@code 27d6c6f}. The source-context artifacts
 * ({@code app/jcl/TRANFILE.jcl}, {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN02C.cbl},
 * {@code app/cbl/CBTRN02C.cbl}) are read-only reference material and are never copied into this
 * repository.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("TransactionRepository (TRANSACT KSDS+AIX / COTRN00C, COTRN02C, CBTRN02C) — unseeded save-then-assert parity tests")
class TransactionRepositoryIT extends AbstractRepositoryIT {

    /**
     * Primary FK-safe card number &mdash; {@code 0500024453765740} (account&nbsp;50), present in the
     * seeded {@code card}/{@code card_xref} data. Sorts lexicographically <em>before</em>
     * {@link #CARD_SECONDARY}, which makes the {@code findByProcessingDateRange} {@code ORDER BY
     * t.tranCardNum} observable.
     */
    private static final String CARD_PRIMARY = "0500024453765740";

    /**
     * Secondary FK-safe card number &mdash; {@code 0683586198171516}, also present in the seeded
     * {@code card}/{@code card_xref} data. Sorts lexicographically <em>after</em>
     * {@link #CARD_PRIMARY}.
     */
    private static final String CARD_SECONDARY = "0683586198171516";

    /**
     * Page size of the {@code COTRN00C} transaction-list screen: 7 rows per page (the COBOL screen
     * array {@code SEL0007I}/{@code TRNID07I}).
     */
    private static final int COTRN00C_PAGE_SIZE = 7;

    /**
     * Exact scale of the {@code amount} column ({@code TRAN-AMT PIC S9(09)V99} &rarr;
     * {@code NUMERIC(11,2)}): two fractional digits.
     */
    private static final int EXPECTED_AMOUNT_SCALE = 2;

    /** Inclusive start of the June processing-date window ({@code YYYY-MM-DD}). */
    private static final String JUNE_WINDOW_START = "2022-06-01";

    /** Inclusive end of the June processing-date window ({@code YYYY-MM-DD}). */
    private static final String JUNE_WINDOW_END = "2022-06-30";

    /** A processing-date window wide enough to span any conceivable row ({@code YYYY-MM-DD}). */
    private static final String ALL_WINDOW_START = "2000-01-01";

    /** Upper bound of the all-encompassing processing-date window ({@code YYYY-MM-DD}). */
    private static final String ALL_WINDOW_END = "2100-12-31";

    /** A processed timestamp whose date prefix is {@code 2022-06-10} (inside the June window). */
    private static final LocalDateTime TS_JUNE_10 = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** A processed timestamp whose date prefix is {@code 2022-06-15} (inside the June window). */
    private static final LocalDateTime TS_JUNE_15 = LocalDateTime.of(2022, 6, 15, 8, 0, 0);

    /** A processed timestamp whose date prefix is {@code 2022-07-01} (outside the June window). */
    private static final LocalDateTime TS_JULY_01 = LocalDateTime.of(2022, 7, 1, 12, 0, 0);

    /** Repository under test &mdash; the relational replacement for the {@code TRANSACT} KSDS+AIX. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Builds a 16-character zero-padded transaction id, mirroring the {@code TRAN-ID PIC X(16)}
     * external format (a zero-padded numeric key) over which {@code COTRN02C} derives the next id.
     *
     * @param value the numeric value to render as a 16-character zero-padded id
     * @return the value formatted as a 16-character, left-zero-padded {@link String}
     */
    private static String id16(long value) {
        return String.format("%016d", value);
    }

    /**
     * Creates a fully-populated, FK-safe {@link Transaction} for the save-then-assert tests.
     *
     * <p>Deviation note: the agent prompt sketched a {@code newTxn(..., String procTs, ...)} helper,
     * but the {@link Transaction} entity maps {@code TRAN-PROC-TS} to {@link LocalDateTime} (column
     * {@code processed_timestamp TIMESTAMP}), so the processed-timestamp parameter is typed
     * {@link LocalDateTime} to match the actual persistence contract. The amount is built with the
     * {@code BigDecimal(String)} constructor (never a {@code double}) per AAP &sect;0.7.3, and
     * {@code TRAN-ORIG-TS} is set equal to {@code TRAN-PROC-TS} so each row is fully deterministic.
     * The type code {@code "01"} and category {@code 1} reference the seeded {@code transaction_type}
     * / {@code transaction_category} data for realism.</p>
     *
     * @param tranId  the 16-character transaction id ({@code TRAN-ID}); becomes the primary key
     * @param cardNum the FK-safe seeded card number ({@code TRAN-CARD-NUM})
     * @param procTs  the processed timestamp ({@code TRAN-PROC-TS}); also used as the origin timestamp
     * @param amount  the monetary amount as a decimal string ({@code TRAN-AMT}); parsed via
     *                {@code new BigDecimal(String)}
     * @return a new, unsaved {@link Transaction} populated with the supplied and default field values
     */
    private Transaction newTxn(String tranId, String cardNum, LocalDateTime procTs, String amount) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd("01");
        txn.setTranCatCd(1);
        txn.setTranSource("POS TERM");
        txn.setTranDesc("Integration test transaction " + tranId);
        txn.setTranAmt(new BigDecimal(amount));
        txn.setTranMerchantId(123456789L);
        txn.setTranMerchantName("TEST MERCHANT");
        txn.setTranMerchantCity("TEST CITY");
        txn.setTranMerchantZip("00000");
        txn.setTranCardNum(cardNum);
        txn.setTranOrigTs(procTs);
        txn.setTranProcTs(procTs);
        return txn;
    }

    /**
     * Asserts the unseeded-table invariants <strong>before any save</strong>: the relational
     * {@code TRANSACT} replacement starts with zero rows because transactions are produced at runtime
     * (by {@code CBTRN02C} posting and {@code COTRN02C} add), never by the Flyway seed.
     *
     * <p>This is the parity assertion specific to the only empty table in the schema: {@code count()}
     * is zero; {@link TransactionRepository#findMaxTransactionId()} is {@link Optional#empty()} (the
     * SQL {@code MAX} over no rows &mdash; the COBOL empty-file&nbsp;&rarr;&nbsp;{@code ZEROS} branch);
     * a by-card gather returns nothing; and even an all-encompassing processing-date window returns
     * nothing. These must all hold before this transaction persists anything.</p>
     */
    @Test
    @DisplayName("Unseeded TRANSACT replacement starts empty: count 0, MAX(tranId) empty, no card/date-range rows")
    void emptyTable_invariants_holdBeforeAnySave() {
        assertThat(transactionRepository.count())
                .as("the unseeded TRANSACT replacement must start with zero rows")
                .isZero();

        assertThat(transactionRepository.findMaxTransactionId())
                .as("MAX(tranId) over an empty table must be Optional.empty() "
                        + "(COTRN02C empty-file -> ZEROS branch)")
                .isEmpty();

        assertThat(transactionRepository.findByTranCardNum(CARD_PRIMARY))
                .as("no transactions exist for any card before a save")
                .isEmpty();

        assertThat(transactionRepository.findByProcessingDateRange(ALL_WINDOW_START, ALL_WINDOW_END))
                .as("an all-encompassing processing-date window returns nothing on an empty table")
                .isEmpty();
    }

    /**
     * Saves one transaction, flushes and clears the persistence context, then re-reads it by primary
     * key &mdash; proving the keyed write/read round-trip ({@code WRITE}/{@code READ DATASET('TRANSACT')})
     * preserves monetary and key fidelity.
     *
     * <p>The amount {@code 100.00} is asserted with {@code isEqualByComparingTo} (never
     * {@link BigDecimal#equals(Object)}) and its persisted scale is asserted to be exactly
     * {@value #EXPECTED_AMOUNT_SCALE} (AAP &sect;0.7.3). The {@code entityManager.clear()} forces the
     * {@code findById} re-read to come from the database rather than the first-level cache, so a
     * mapping or round-trip defect cannot be masked. Because {@code TRAN-PROC-TS} is a
     * {@link LocalDateTime}, the date prefix that {@code findByProcessingDateRange} compares is
     * asserted via {@link LocalDateTime#toLocalDate()}.</p>
     */
    @Test
    @DisplayName("save -> flush/clear -> findById round-trips TRAN-AMT via compareTo (scale 2) and the card/proc-date")
    void save_then_findById_roundTrips_withDecimalScale() {
        String tranId = id16(1);
        transactionRepository.saveAndFlush(newTxn(tranId, CARD_PRIMARY, TS_JUNE_10, "100.00"));
        // Force a genuine DB re-read (not a first-level-cache hit) so a mapping defect cannot hide.
        entityManager.clear();

        Optional<Transaction> found = transactionRepository.findById(tranId);
        assertThat(found)
                .as("the saved transaction must be retrievable by its 16-character TRAN-ID")
                .isPresent();
        Transaction txn = found.get();

        // Decimal fidelity (AAP §0.7.3): compareTo only, never equals; persisted scale exactly 2.
        assertThat(txn.getTranAmt())
                .as("TRAN-AMT must round-trip as 100.00 compared via compareTo (scale-insensitive)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(txn.getTranAmt().scale())
                .as("TRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2): persisted scale must be exactly 2")
                .isEqualTo(EXPECTED_AMOUNT_SCALE);

        assertThat(txn.getTranCardNum())
                .as("TRAN-CARD-NUM must round-trip unchanged (FK-safe seeded card)")
                .isEqualTo(CARD_PRIMARY);

        // TRAN-PROC-TS maps to LocalDateTime; assert the YYYY-MM-DD prefix that the date-range query
        // compares (SUBSTRING(...,1,10)) round-trips as 2022-06-10.
        assertThat(txn.getTranProcTs())
                .as("TRAN-PROC-TS must round-trip as a non-null LocalDateTime")
                .isNotNull();
        assertThat(txn.getTranProcTs().toLocalDate())
                .as("processing-date portion (the SUBSTRING(...,1,10) key) must be 2022-06-10")
                .isEqualTo(LocalDate.of(2022, 6, 10));
    }

    /**
     * Saves three ascending transactions and verifies the paged greater-than-or-equal {@code TRAN-ID}
     * browse that reproduces the online transaction-list program {@code COTRN00C}.
     *
     * <p>The caller supplies a page size of {@value #COTRN00C_PAGE_SIZE} via
     * {@link PageRequest#of(int, int)} (the original 7-rows-per-page screen contract). The returned
     * {@link Page} reports that requested page size through {@link Page#getSize()} (independent of how
     * many rows the page actually holds), its content never exceeds the page size, every content row
     * satisfies the greater-than-or-equal floor (the {@code STARTBR} GTEQ positioning), and the three
     * saved rows appear on the first page. A second browse from a higher start key proves the strict
     * GTEQ semantics: raising the floor to the second id excludes the lower first id.</p>
     */
    @Test
    @DisplayName("findByTranIdGreaterThanEqual paginates the COTRN00C 7-rows/page GTEQ TRAN-ID browse")
    void findByTranIdGreaterThanEqual_paginates() {
        String id1 = id16(1);
        String id2 = id16(2);
        String id3 = id16(3);
        transactionRepository.saveAndFlush(newTxn(id1, CARD_PRIMARY, TS_JUNE_10, "10.00"));
        transactionRepository.saveAndFlush(newTxn(id2, CARD_PRIMARY, TS_JUNE_10, "20.00"));
        transactionRepository.saveAndFlush(newTxn(id3, CARD_PRIMARY, TS_JUNE_10, "30.00"));

        Page<Transaction> page =
                transactionRepository.findByTranIdGreaterThanEqual(id1, PageRequest.of(0, COTRN00C_PAGE_SIZE));

        // getSize() reflects the requested 7-row screen contract, independent of rows actually present.
        assertThat(page.getSize())
                .as("page size must reflect the requested COTRN00C 7-rows-per-page screen contract")
                .isEqualTo(COTRN00C_PAGE_SIZE);

        // A single page can hold at most the page size.
        assertThat(page.getContent())
                .as("a single page must not exceed the requested page size")
                .hasSizeLessThanOrEqualTo(COTRN00C_PAGE_SIZE);

        // Every browsed row must satisfy the STARTBR greater-than-or-equal floor (compareTo >= 0).
        assertThat(page.getContent())
                .as("every browsed row must have TRAN-ID >= the requested start key (STARTBR GTEQ)")
                .allSatisfy(txn -> assertThat(txn.getTranId().compareTo(id1)).isGreaterThanOrEqualTo(0));

        // The three saved rows must appear on the first page of the browse from the lowest id.
        assertThat(page.getContent())
                .extracting(Transaction::getTranId)
                .as("the saved rows must be included in the browse starting at the lowest id")
                .contains(id1, id2, id3);

        // Strict GTEQ: browsing from id2 includes id2/id3 but EXCLUDES the lower id1.
        Page<Transaction> fromSecond =
                transactionRepository.findByTranIdGreaterThanEqual(id2, PageRequest.of(0, COTRN00C_PAGE_SIZE));
        assertThat(fromSecond.getContent())
                .extracting(Transaction::getTranId)
                .as("browsing from id2 (GTEQ) must include id2/id3 and exclude the lower id1")
                .contains(id2, id3)
                .doesNotContain(id1);
    }

    /**
     * Saves rows with three distinct processing-date prefixes and verifies the date-window query that
     * reproduces the batch transaction report {@code CBTRN03C} / {@code app/jcl/TRANREPT.jcl}.
     *
     * <p>Two rows fall inside June&nbsp;2022 ({@code 2022-06-10} and {@code 2022-06-15}) and one falls
     * in July ({@code 2022-07-01}). Querying the inclusive window
     * {@code [2022-06-01, 2022-06-30]} returns <strong>only</strong> the two June rows &mdash; the
     * July row is excluded because its date prefix {@code "2022-07-01"} exceeds {@code "2022-06-30"}
     * &mdash; proving the {@code SUBSTRING(TRAN-PROC-TS, 1, 10)} date-prefix range semantics. The two
     * June rows are given <em>different</em> card numbers so the {@code ORDER BY t.tranCardNum} is
     * observable: the {@code 2022-06-15} row carries the lexicographically-lower {@link #CARD_PRIMARY}
     * and the {@code 2022-06-10} row carries {@link #CARD_SECONDARY}, so card-ascending ordering
     * places the {@code 06-15} row first &mdash; an order that differs from both insertion and id
     * order, confirming the {@code ORDER BY} governs the sequence.</p>
     */
    @Test
    @DisplayName("findByProcessingDateRange filters by SUBSTRING(TRAN-PROC-TS,1,10) prefix, ordered by TRAN-CARD-NUM")
    void findByProcessingDateRange_filtersBySubstringDatePrefix() {
        String juneHighCard = id16(10); // 2022-06-10, CARD_SECONDARY (higher card number)
        String juneLowCard = id16(11);  // 2022-06-15, CARD_PRIMARY   (lower card number)
        String julyRow = id16(12);      // 2022-07-01, CARD_PRIMARY   (outside the June window)
        transactionRepository.saveAndFlush(newTxn(juneHighCard, CARD_SECONDARY, TS_JUNE_10, "11.00"));
        transactionRepository.saveAndFlush(newTxn(juneLowCard, CARD_PRIMARY, TS_JUNE_15, "22.00"));
        transactionRepository.saveAndFlush(newTxn(julyRow, CARD_PRIMARY, TS_JULY_01, "33.00"));

        List<Transaction> june =
                transactionRepository.findByProcessingDateRange(JUNE_WINDOW_START, JUNE_WINDOW_END);

        // Only the two June rows fall inside [2022-06-01, 2022-06-30]; the July row is excluded.
        assertThat(june)
                .as("only the two June rows fall inside the 2022-06 processing-date window")
                .hasSize(2)
                .extracting(Transaction::getTranId)
                .doesNotContain(julyRow);

        // ORDER BY t.tranCardNum ascending: CARD_PRIMARY (0500...) precedes CARD_SECONDARY (0683...),
        // so the 06-15/lower-card row comes BEFORE the 06-10/higher-card row — proving the ORDER BY
        // (not insertion or id order) governs the result sequence.
        assertThat(june)
                .extracting(Transaction::getTranId)
                .as("results must be ordered by TRAN-CARD-NUM ascending (CARD_PRIMARY row first)")
                .containsExactly(juneLowCard, juneHighCard);
        assertThat(june)
                .extracting(Transaction::getTranCardNum)
                .as("card numbers must be in ascending order per the @Query ORDER BY t.tranCardNum")
                .containsExactly(CARD_PRIMARY, CARD_SECONDARY);
    }

    /**
     * Saves three ids out of order and verifies {@link TransactionRepository#findMaxTransactionId()}
     * returns the lexicographic maximum &mdash; the highest-key discovery {@code COTRN02C} performs by
     * positioning on {@code HIGH-VALUES} and reading the previous (greatest) key.
     *
     * <p>Because {@code TRAN-ID} is a 16-character zero-padded numeric key (see {@link #id16(long)}),
     * its lexicographic maximum equals its numeric maximum, so {@code SELECT MAX(t.tranId)} reproduces
     * the {@code MOVE HIGH-VALUES}&nbsp;&rarr;&nbsp;{@code STARTBR}&nbsp;&rarr;&nbsp;{@code READPREV}
     * positioning exactly. The {@code +1} increment that {@code COTRN02C} then applies to derive the
     * next id is a service-layer concern and is intentionally not tested here.</p>
     */
    @Test
    @DisplayName("findMaxTransactionId returns the lexicographic MAX id (COTRN02C browse-to-end)")
    void findMaxTransactionId_returnsHighestId_afterSaves() {
        // Save out of order to prove MAX (not insertion order) selects the highest zero-padded id.
        transactionRepository.saveAndFlush(newTxn(id16(2), CARD_PRIMARY, TS_JUNE_10, "2.00"));
        transactionRepository.saveAndFlush(newTxn(id16(1), CARD_PRIMARY, TS_JUNE_10, "1.00"));
        transactionRepository.saveAndFlush(newTxn(id16(3), CARD_PRIMARY, TS_JUNE_10, "3.00"));

        assertThat(transactionRepository.findMaxTransactionId())
                .as("MAX(tranId) must be the highest zero-padded 16-char id — the COTRN02C "
                        + "browse-to-end key")
                .contains(id16(3));
    }

    /**
     * Saves two transactions for one card and one for another, then verifies
     * {@link TransactionRepository#findByTranCardNum(String)} returns exactly the transactions of the
     * requested card &mdash; the per-card gather of the batch statement program {@code CBSTM03A} (the
     * {@code TRANSACT} by-card logical access path).
     *
     * <p>The lookup for {@link #CARD_PRIMARY} returns exactly its two rows (every row carrying that
     * card number) and none of {@link #CARD_SECONDARY}'s; the lookup for {@link #CARD_SECONDARY}
     * returns only its single row &mdash; confirming the card filter neither over- nor under-matches.</p>
     */
    @Test
    @DisplayName("findByTranCardNum returns only the given card's transactions (TRANSACT by-card access path)")
    void findByTranCardNum_returnsTxnsForCard() {
        String primaryA = id16(20);
        String primaryB = id16(21);
        String secondary = id16(22);
        transactionRepository.saveAndFlush(newTxn(primaryA, CARD_PRIMARY, TS_JUNE_10, "20.00"));
        transactionRepository.saveAndFlush(newTxn(primaryB, CARD_PRIMARY, TS_JUNE_15, "21.00"));
        transactionRepository.saveAndFlush(newTxn(secondary, CARD_SECONDARY, TS_JULY_01, "22.00"));

        assertThat(transactionRepository.findByTranCardNum(CARD_PRIMARY))
                .as("exactly the two CARD_PRIMARY transactions, none from CARD_SECONDARY")
                .hasSize(2)
                .allSatisfy(txn -> assertThat(txn.getTranCardNum()).isEqualTo(CARD_PRIMARY))
                .extracting(Transaction::getTranId)
                .containsExactlyInAnyOrder(primaryA, primaryB);

        assertThat(transactionRepository.findByTranCardNum(CARD_SECONDARY))
                .as("the other card returns only its single transaction")
                .extracting(Transaction::getTranId)
                .containsExactly(secondary);
    }
}
