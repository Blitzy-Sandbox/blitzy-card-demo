package com.cardemo.unit.service.transaction;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionDto.TransactionListItem;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.transaction.TransactionListService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fully-mocked unit tests for {@link TransactionListService} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot
 * 3.x migration of the online CICS program <strong>{@code app/cbl/COTRN00C.cbl}</strong> (CICS
 * transaction <strong>{@code CT00}</strong>, BMS map {@code COTRN0A}). The legacy program performed a
 * <strong>paginated forward/backward browse of the {@code TRANSACT} VSAM KSDS, exactly ten rows per
 * page</strong>, optionally positioned by a starting transaction-id filter. These tests assert that the
 * migrated service reproduces that observable behavior <em>exactly</em> (100% behavioral-parity gate,
 * AAP &sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring, no I/O</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em>
 * {@code @SpringBootTest}, no Spring context, no Testcontainers, no database and no I/O. The
 * {@link TransactionRepository} collaborator is a Mockito {@code @Mock}; the system under test is wired
 * by constructor injection through {@code @InjectMocks} (matching the real single-argument constructor
 * {@code TransactionListService(TransactionRepository)}). The class runs under {@link MockitoExtension}
 * (default {@code STRICT_STUBS}), so the validation tests stub <em>nothing</em> and assert
 * {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions} to prove the early
 * throw, while every browse test stubs <em>exactly one</em> repository method
 * ({@link TransactionRepository#findByTranIdGreaterThanEqual(String, Pageable)}) &mdash; no
 * {@code lenient()}.</p>
 *
 * <h2>The two make-or-break parity points</h2>
 * <ol>
 *   <li><strong>Page size is EXACTLY ten.</strong> The legacy {@code PROCESS-PAGE-FORWARD} filled the
 *       {@code TRNID01..TRNID10} row array under {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX
 *       &gt; 10} / {@code PERFORM UNTIL WS-IDX &gt;= 11} ({@code COTRN00C} L290, L297). The make-or-break
 *       assertion captures the {@link Pageable} this service builds and checks {@code getPageSize() ==
 *       10} with an ascending {@code tranId} sort ({@code STARTBR(GTEQ)}/{@code READNEXT} order).</li>
 *   <li><strong>The start-key transformation</strong> ({@code PROCESS-ENTER-KEY}, {@code COTRN00C}
 *       L206-219): a blank/absent filter maps to {@code "0000000000000000"} (the {@code MOVE LOW-VALUES
 *       TO TRAN-ID} analog), a numeric filter is left-zero-padded to sixteen digits, and a non-numeric
 *       filter raises {@link ValidationException} carrying {@code 'Tran ID must be Numeric ...'} (note
 *       the single space before the three-dot ellipsis).</li>
 * </ol>
 *
 * <h2>Verified collaborator contracts (compiled against the actual generated sources, STEP 0)</h2>
 * <ul>
 *   <li>{@code Transaction.getTranOrigTs()} returns a {@link LocalDateTime} (NOT a 26-character
 *       {@link String}); the service renders it with a {@code MM/dd/yy} formatter, so the
 *       {@link #txn(String, LocalDateTime, String, String) fixture factory} sets a {@link LocalDateTime}.
 *       Because the field is a {@link LocalDateTime} it can never be a "short" (&lt;10 char) string, so
 *       only the {@code null}&rarr;{@code "00/00/00"} fallback is exercised.</li>
 *   <li>{@code TransactionRepository.findByTranIdGreaterThanEqual(String, Pageable)} returns
 *       {@code Page<Transaction>}, so it is stubbed with {@link PageImpl}.</li>
 *   <li>{@code TransactionDto.getPageNumber()} is a {@link String} ({@code PAGENUM PIC X(8)}); the
 *       0-based page-index derivation is deterministic, so it is asserted directly.</li>
 *   <li>{@code TransactionDto.TransactionListItem} is a {@code public static} nested type with
 *       {@code getSelectionFlag()}, {@code getTransactionId()}, {@code getDate()}, {@code getDescription()}
 *       and {@code getAmount()} accessors.</li>
 *   <li>{@code ValidationException} is an unchecked {@code RuntimeException} subtype whose
 *       {@code getMessage()} carries the verbatim COBOL literal.</li>
 * </ul>
 *
 * <p>Golden values are drawn from the first record of {@code app/data/ASCII/dailytran.txt} (transaction
 * id {@code 0000000000683580}, origination timestamp {@code 2022-06-10 19:27:53} &rarr; rendered date
 * {@code "06/10/22"}) plus the documented parity seed {@code 2024-03-09 12.00.00.000000} &rarr;
 * {@code "03/09/24"}. The COBOL is read-only reference at the frozen baseline commit SHA
 * {@code 27d6c6f} and is <strong>never copied</strong> into this repository &mdash; only its observable
 * contract is asserted (AAP &sect;0.7.2).</p>
 *
 * @see TransactionListService
 * @see TransactionRepository
 * @see TransactionDto
 * @see TransactionDto.TransactionListItem
 * @see Transaction
 * @see ValidationException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COTRN00C transaction-list browse (CT00, 10 rows/page)")
class TransactionListServiceTest {

    /** The fixed browse page size mandated by {@code COTRN00C} (L290/L297). */
    private static final int EXPECTED_PAGE_SIZE = 10;

    /** The {@link Transaction} property the browse is ordered by (the 16-char {@code TRAN-ID} key). */
    private static final String SORT_PROPERTY = "tranId";

    /** {@code MOVE LOW-VALUES TO TRAN-ID} analog &mdash; the lowest 16-char numeric key ({@code COTRN00C} L207). */
    private static final String LOWEST_TRAN_ID = "0000000000000000";

    /** Distinctive phrase of the verbatim {@code 'Tran ID must be Numeric ...'} edit ({@code COTRN00C} L213-215). */
    private static final String MSG_TRAN_ID_NOT_NUMERIC_PHRASE = "Tran ID must be Numeric";

    /**
     * Golden transaction id from {@code app/data/ASCII/dailytran.txt} line&nbsp;1 ({@code TRAN-ID}, the
     * 16-character key) &mdash; grounds the row-mapping assertions in real fixture data.
     */
    private static final String GOLDEN_TRAN_ID = "0000000000683580";

    /**
     * Origination timestamp of {@code app/data/ASCII/dailytran.txt} line&nbsp;1
     * ({@code 2022-06-10 19:27:53.000000}); {@code POPULATE-TRAN-DATA} renders it {@code MM/DD/YY} as
     * {@code "06/10/22"}.
     */
    private static final LocalDateTime GOLDEN_ORIG_TS = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** The {@code MM/DD/YY} date the golden origination timestamp must render to ({@code COTRN00C} L383-388). */
    private static final String GOLDEN_TRAN_DATE = "06/10/22";

    /** Documented AAP parity seed timestamp ({@code 2024-03-09 12.00.00.000000}). */
    private static final LocalDateTime SEED_ORIG_TS = LocalDateTime.of(2024, 3, 9, 12, 0, 0);

    /** The {@code MM/DD/YY} date the parity-seed timestamp must render to. */
    private static final String SEED_TRAN_DATE = "03/09/24";

    /** {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} &mdash; the null-timestamp fallback ({@code COTRN00C} L57). */
    private static final String DEFAULT_TRAN_DATE = "00/00/00";

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionListService service;

    // ================================================================================================
    // Test-data factories. Built via the REAL entity/DTO setters confirmed in STEP 0. tranOrigTs is a
    // LocalDateTime (NOT a String); tranAmt is a BigDecimal parsed from the supplied decimal text.
    // ================================================================================================

    /**
     * Builds an in-memory {@link Transaction} fixture for the browse row mapping.
     *
     * @param id     the 16-character transaction id ({@code TRAN-ID}, primary key)
     * @param origTs the origination timestamp ({@code TRAN-ORIG-TS}); a {@link LocalDateTime} (may be
     *               {@code null} to exercise the {@code "00/00/00"} fallback)
     * @param desc   the transaction description ({@code TRAN-DESC}); may be {@code null}
     * @param amt    the decimal amount text ({@code TRAN-AMT}); parsed to {@link BigDecimal} (may be
     *               {@code null})
     * @return a populated {@link Transaction} test fixture
     */
    private static Transaction txn(String id, LocalDateTime origTs, String desc, String amt) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranOrigTs(origTs);
        t.setTranDesc(desc);
        t.setTranAmt(amt == null ? null : new BigDecimal(amt));
        return t;
    }

    /**
     * Builds a list request DTO carrying the optional starting-id filter ({@code TRNIDIN}) and the
     * one-based page indicator ({@code PAGENUM}).
     *
     * @param filter     the {@code transactionIdFilter} value (may be {@code null}/blank)
     * @param pageNumber the {@code pageNumber} value (a {@link String}; may be {@code null}/blank)
     * @return a populated request {@link TransactionDto}
     */
    private static TransactionDto request(String filter, String pageNumber) {
        TransactionDto dto = new TransactionDto();
        dto.setTransactionIdFilter(filter);
        dto.setPageNumber(pageNumber);
        return dto;
    }

    /**
     * Wraps the supplied transactions in a {@link PageImpl} matching the repository's
     * {@code Page<Transaction>} return type.
     *
     * @param content the page content (in browse order)
     * @return a {@link PageImpl} over {@code content}
     */
    private static PageImpl<Transaction> page(List<Transaction> content) {
        return new PageImpl<>(content);
    }

    /**
     * An empty {@code Page<Transaction>} (no rows), used where only the {@link Pageable}/start-key
     * argument is under test.
     *
     * @return an empty {@link PageImpl}
     */
    private static PageImpl<Transaction> emptyPage() {
        return new PageImpl<>(List.of());
    }

    // ================================================================================================
    // Shared capture helpers. Each stubs the single browse method to an empty page (STRICT_STUBS: the
    // stub is always consumed by the service call), runs the service, and returns the captured argument.
    // ================================================================================================

    /**
     * Runs {@link TransactionListService#listTransactions(TransactionDto)} against an empty stubbed page
     * and returns the start-key {@link String} the service passed to the repository &mdash; the relational
     * analog of the {@code STARTBR RIDFLD(TRAN-ID)} positioning key ({@code COTRN00C} L206-219).
     *
     * @param req the inbound list request
     * @return the captured start key
     */
    private String runAndCaptureStartId(TransactionDto req) {
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(emptyPage());

        service.listTransactions(req);

        ArgumentCaptor<String> startId = ArgumentCaptor.forClass(String.class);
        verify(transactionRepository).findByTranIdGreaterThanEqual(startId.capture(), any(Pageable.class));
        return startId.getValue();
    }

    /**
     * Runs {@link TransactionListService#listTransactions(TransactionDto)} against an empty stubbed page
     * and returns the {@link Pageable} the service built &mdash; the relational analog of the
     * fixed ten-row page-fill loop and the ascending-key {@code STARTBR}/{@code READNEXT} walk
     * ({@code COTRN00C} L290/L297).
     *
     * @param req the inbound list request
     * @return the captured pageable
     */
    private Pageable runAndCapturePageable(TransactionDto req) {
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(emptyPage());

        service.listTransactions(req);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByTranIdGreaterThanEqual(anyString(), pageable.capture());
        return pageable.getValue();
    }

    // ================================================================================================
    // Start-key derivation — PROCESS-ENTER-KEY start-key logic (COTRN00C L206-219).
    // ================================================================================================

    /**
     * The {@code TRNIDIN} starting-id filter to start-key transformation: blank/absent &rarr; the lowest
     * 16-char key, numeric &rarr; left-zero-padded to sixteen digits, non-numeric &rarr; rejected.
     */
    @Nested
    @DisplayName("Start-key derivation — PROCESS-ENTER-KEY (COTRN00C L206-219)")
    class StartKeyDerivation {

        @Test
        @DisplayName("blank filter -> LOW-VALUES start key \"0000000000000000\" [COTRN00C L206-207]")
        void blankFilter_startsAtLowestKey() {
            // IF TRNIDINI = SPACES OR LOW-VALUES -> MOVE LOW-VALUES TO TRAN-ID.
            assertThat(runAndCaptureStartId(request("", null))).isEqualTo(LOWEST_TRAN_ID);
        }

        @Test
        @DisplayName("null filter -> LOW-VALUES start key \"0000000000000000\"")
        void nullFilter_startsAtLowestKey() {
            assertThat(runAndCaptureStartId(request(null, null))).isEqualTo(LOWEST_TRAN_ID);
        }

        @Test
        @DisplayName("all-spaces filter -> LOW-VALUES start key \"0000000000000000\"")
        void allSpacesFilter_startsAtLowestKey() {
            assertThat(runAndCaptureStartId(request("   ", null))).isEqualTo(LOWEST_TRAN_ID);
        }

        @Test
        @DisplayName("numeric filter \"42\" -> zero-padded to 16 \"0000000000000042\" [COTRN00C L209-210]")
        void numericFilter_zeroPaddedTo16() {
            // IF TRNIDINI IS NUMERIC -> MOVE TRNIDINI TO TRAN-ID (left zero-pad to the 16-char key width).
            assertThat(runAndCaptureStartId(request("42", null))).isEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("numeric filter with surrounding spaces \" 42 \" -> trimmed then padded \"0000000000000042\"")
        void numericFilterWithSpaces_trimmedThenPadded() {
            assertThat(runAndCaptureStartId(request(" 42 ", null))).isEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("non-numeric filter \"ABC\" -> ValidationException, repository NEVER called [COTRN00C L211-215]")
        void nonNumericFilter_throwsValidationException_andRepositoryNeverCalled() {
            // ELSE -> 'Tran ID must be Numeric ...': a genuine input-validation failure raised BEFORE any
            // database access. The distinctive phrase is asserted (robust to trailing-space/ellipsis
            // rendering); the exception TYPE is asserted exactly.
            TransactionDto req = request("ABC", null);

            assertThatThrownBy(() -> service.listTransactions(req))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(MSG_TRAN_ID_NOT_NUMERIC_PHRASE);

            // Non-numeric edit short-circuits before the STARTBR analog -> no repository interaction.
            verifyNoInteractions(transactionRepository);
        }
    }

    // ================================================================================================
    // Page size — the single most important parity assertion: PERFORM UNTIL WS-IDX > 10 (COTRN00C L290/L297).
    // ================================================================================================

    /**
     * The make-or-break parity check: the {@link Pageable} the service builds requests EXACTLY ten rows,
     * ordered by the ascending {@code tranId} key.
     */
    @Nested
    @DisplayName("Page size is EXACTLY ten — PERFORM UNTIL WS-IDX > 10 (COTRN00C L290/L297)")
    class PageSizeIsExactlyTen {

        @Test
        @DisplayName("builds Pageable size=10 [HARD PARITY: 10 rows/page]")
        void browse_buildsPageableOfSizeTen() {
            Pageable captured = runAndCapturePageable(request(null, null));

            // HARD PARITY: COBOL capped the TRNID01..TRNID10 row array at ten (PERFORM UNTIL WS-IDX > 10).
            assertThat(captured.getPageSize()).isEqualTo(EXPECTED_PAGE_SIZE);
        }

        @Test
        @DisplayName("Pageable sorts by tranId ascending (STARTBR(GTEQ)/READNEXT key order)")
        void browse_sortsByTranIdAscending() {
            Pageable captured = runAndCapturePageable(request(null, null));

            // STARTBR(GTEQ)/READNEXT walked the TRANSACT cluster key (TRAN-ID) in ascending order.
            Sort.Order order = captured.getSort().getOrderFor(SORT_PROPERTY);
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }
    }

    // ================================================================================================
    // Row mapping — POPULATE-TRAN-DATA per-row MOVEs (COTRN00C L381-445).
    // ================================================================================================

    /**
     * One browsed {@link Transaction} maps to one {@link TransactionListItem}: blank selection flag,
     * pass-through id, {@code MM/DD/YY} date from {@code TRAN-ORIG-TS}, 26-char-truncated description and
     * a scale-2 {@link BigDecimal} amount.
     */
    @Nested
    @DisplayName("Row mapping — POPULATE-TRAN-DATA (COTRN00C L381-445)")
    class RowMapping {

        /**
         * Stubs a single-row page, runs the browse and returns the one mapped row.
         *
         * @param entity the browsed transaction fixture
         * @return the single mapped {@link TransactionListItem}
         */
        private TransactionListItem firstRow(Transaction entity) {
            when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                    .thenReturn(page(List.of(entity)));

            TransactionDto result = service.listTransactions(request(null, null));

            assertThat(result.getTransactions()).hasSize(1);
            return result.getTransactions().get(0);
        }

        @Test
        @DisplayName("date from golden TRAN-ORIG-TS 2022-06-10 -> \"06/10/22\" [dailytran.txt L1]")
        void date_fromGoldenTimestamp_rendersMmDdYy() {
            // POPULATE-TRAN-DATA: mm=ts(5:7), dd=ts(8:10), yy=ts(3:2) -> MM/DD/YY (COTRN00C L383-388).
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, "Purchase", "50.47"));
            assertThat(row.getDate()).isEqualTo(GOLDEN_TRAN_DATE);
        }

        @Test
        @DisplayName("date from parity-seed TRAN-ORIG-TS 2024-03-09 -> \"03/09/24\" [AAP seed]")
        void date_fromParitySeed_rendersMmDdYy() {
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, SEED_ORIG_TS, "Purchase", "1.00"));
            assertThat(row.getDate()).isEqualTo(SEED_TRAN_DATE);
        }

        @Test
        @DisplayName("null TRAN-ORIG-TS -> WS-TRAN-DATE fallback \"00/00/00\" [COTRN00C L57]")
        void date_nullTimestamp_fallsBackToZeroDate() {
            // WS-TRAN-DATE PIC X(08) VALUE '00/00/00' -> a missing timestamp never throws on this field.
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, null, "Purchase", "1.00"));
            assertThat(row.getDate()).isEqualTo(DEFAULT_TRAN_DATE);
        }

        @Test
        @DisplayName("TRAN-DESC of 40 chars truncated to 26 (TDESC0n PIC X(26)) [COTRN00C L395]")
        void description_truncatedTo26() {
            // MOVE TRAN-DESC PIC X(100) TO TDESC0n PIC X(26) truncates to the leftmost 26 characters.
            String longDesc = "X".repeat(40);
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, longDesc, "1.00"));
            assertThat(row.getDescription()).hasSize(26);
            assertThat(row.getDescription()).isEqualTo(longDesc.substring(0, 26));
        }

        @Test
        @DisplayName("TRAN-DESC shorter than 26 passes through unchanged")
        void description_shorterThan26_passesThrough() {
            String shortDesc = "0123456789"; // 10 chars (< 26) -> unchanged
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, shortDesc, "1.00"));
            assertThat(row.getDescription()).isEqualTo(shortDesc);
            assertThat(row.getDescription()).hasSize(10);
        }

        @Test
        @DisplayName("TRAN-AMT mapped as BigDecimal; compared via compareTo NEVER equals [AAP §0.7.3]")
        void amount_mappedAsBigDecimal_comparedViaCompareTo() {
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, "Purchase", "12.34"));
            // Decimal fidelity: compare monetary values with compareTo (scale-insensitive), never equals.
            assertThat(row.getAmount())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("12.34"));
        }

        @Test
        @DisplayName("TRAN-AMT normalized to scale 2, value preserved [TRAN-AMT PIC S9(09)V99]")
        void amount_normalizedToScaleTwo() {
            // Entity amount of scale 1 ("12.3"); the service applies setScale(2, HALF_EVEN) -> "12.30".
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, "Purchase", "12.3"));
            assertThat(row.getAmount().scale()).isEqualTo(2);                       // scale normalized to 2
            assertThat(row.getAmount().compareTo(new BigDecimal("12.3"))).isZero(); // value preserved (compareTo)
        }

        @Test
        @DisplayName("selection flag is blank \"\" (SEL000n cleared on page paint) [COTRN00C L482-484]")
        void selectionFlag_isBlank() {
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, "Purchase", "1.00"));
            assertThat(row.getSelectionFlag()).isEqualTo("");
        }

        @Test
        @DisplayName("transaction id passes through from TRAN-ID [COTRN00C L392]")
        void transactionId_passesThrough() {
            TransactionListItem row = firstRow(txn(GOLDEN_TRAN_ID, GOLDEN_ORIG_TS, "Purchase", "1.00"));
            assertThat(row.getTransactionId()).isEqualTo(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("three browsed records -> three rows, browse order preserved")
        void listSize_andOrderPreserved() {
            Transaction t1 = txn("0000000000000001", GOLDEN_ORIG_TS, "First", "1.00");
            Transaction t2 = txn("0000000000000002", GOLDEN_ORIG_TS, "Second", "2.00");
            Transaction t3 = txn("0000000000000003", GOLDEN_ORIG_TS, "Third", "3.00");
            when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                    .thenReturn(page(List.of(t1, t2, t3)));

            TransactionDto result = service.listTransactions(request(null, null));

            assertThat(result.getTransactions()).hasSize(3);
            assertThat(result.getTransactions())
                    .extracting(TransactionListItem::getTransactionId)
                    .containsExactly(
                            "0000000000000001", "0000000000000002", "0000000000000003");
        }
    }

    // ================================================================================================
    // Page-number mapping — CDEMO-CT00-PAGE-NUM (1-based) -> Spring Data 0-based index (COTRN00C L224/L307).
    // pageNumber is a String (PAGENUM PIC X(8)); the derivation is deterministic, so it is asserted here.
    // ================================================================================================

    /**
     * The one-based {@code PAGENUM} indicator to Spring Data 0-based page index conversion: blank/absent
     * and any value at or below one is the first page (index 0); page {@code N} maps to index {@code N-1}.
     */
    @Nested
    @DisplayName("Page-number mapping — PAGENUM 1-based to 0-based index (COTRN00C L224/L307)")
    class PageNumberMapping {

        @Test
        @DisplayName("null page number -> index 0 (first page)")
        void nullPageNumber_indexZero() {
            assertThat(runAndCapturePageable(request(null, null)).getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("page \"1\" (1-based) -> index 0")
        void pageOne_indexZero() {
            assertThat(runAndCapturePageable(request(null, "1")).getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("page \"2\" (1-based) -> index 1")
        void pageTwo_indexOne() {
            assertThat(runAndCapturePageable(request(null, "2")).getPageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("page \" 3 \" (trimmed, 1-based) -> index 2 (confirms trim())")
        void pageThreeWithSpaces_indexTwo() {
            assertThat(runAndCapturePageable(request(null, " 3 ")).getPageNumber()).isEqualTo(2);
        }
    }
}
