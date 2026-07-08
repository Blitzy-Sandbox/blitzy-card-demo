package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionListResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast Mockito unit test for {@link TransactionListService}, the Java&nbsp;25
 * / Spring&nbsp;Boot translation of the CICS online program {@code COTRN00C}
 * (transaction {@code CT00}, BMS map {@code COTRN00}; frozen COBOL reference
 * SHA {@code 27d6c6f}, {@code app/cbl/COTRN00C.cbl}, read-only &mdash; not copied
 * into this repository).
 *
 * <p>The single collaborator, {@link TransactionRepository}, is supplied as a
 * Mockito {@code @Mock} and injected through the service's constructor via
 * {@code @InjectMocks}, so the suite loads <strong>no</strong> Spring context and
 * touches no database, Testcontainers, Docker, or live AWS. Every branch of the
 * public API is exercised, feeding the JaCoCo line-coverage gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural parity assertions (COTRN00C &rarr; Java)</h2>
 * <ul>
 *   <li><b>Fixed ten-row page</b> &mdash; the legacy {@code COTRN00} map renders a
 *       fixed ten-row group (the {@code PROCESS-PAGE-FORWARD} loop stops at
 *       {@code WS-IDX &gt;= 11}); the service must request a page of size 10 and
 *       surface {@code pageSize == 10}. Asserted via a captured {@link Pageable}
 *       ({@link #listTransactions_pageSizeIsTen()}).</li>
 *   <li><b>Browse scope</b> &mdash; when a card-number filter is supplied the read
 *       is scoped through {@link TransactionRepository#findByTranCardNum}; otherwise
 *       the whole file is browsed through the inherited {@code findAll(Pageable)}.</li>
 *   <li><b>Filter edit</b> &mdash; {@code PROCESS-ENTER-KEY} (COTRN00C
 *       L206&ndash;L219) rejects a non-blank, non-numeric transaction-id filter with
 *       the verbatim message {@code 'Tran ID must be Numeric ...'}; that branch is a
 *       {@link ValidationException} here and the message is asserted byte-for-byte
 *       (Gate&nbsp;5).</li>
 *   <li><b>Decimal fidelity</b> &mdash; monetary amounts ({@code TRAN-AMT
 *       PIC S9(09)V99}) are carried as {@link BigDecimal} normalised to scale&nbsp;2;
 *       every assertion uses {@code scale()} and {@code compareTo} and no
 *       {@code float}/{@code double} appears anywhere in this suite.</li>
 *   <li><b>Screen date preservation</b> &mdash; the transaction date column is a
 *       verbatim leading slice of the stored processing-timestamp text and is never
 *       reparsed or reformatted.</li>
 *   <li><b>One-based paging</b> &mdash; Spring's zero-based page index is surfaced as
 *       a one-based page number, mirroring the legacy {@code PAGENUM} display.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COBOL COTRN00C / CT00 transaction-list browse (SHA 27d6c6f)")
class TransactionListServiceTest {

    /**
     * The verbatim edit message emitted by {@code COTRN00C}'s {@code PROCESS-ENTER-KEY}
     * when a non-blank transaction-id filter is not numeric (COTRN00C L213&ndash;L214).
     * Held as a local literal (independent of the production constant) so the test
     * pins the exact character sequence for interface parity (Gate&nbsp;5).
     */
    private static final String MSG_TRANID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /**
     * The fixed page size the legacy {@code COTRN00} map renders (ten rows). The
     * service must both request this size from the repository and echo it back in
     * the {@link PageResponse}.
     */
    private static final int EXPECTED_PAGE_SIZE = 10;

    /**
     * A realistic 26-character {@code TRAN-PROC-TS} processing-timestamp value
     * ({@code yyyy-mm-dd-hh.mm.ss.ffffff}) reused by fixtures that do not exercise
     * the date-preservation logic directly.
     */
    private static final String SAMPLE_TIMESTAMP = "2024-06-15-12.30.45.678901";

    /** The mocked repository collaborator. */
    @Mock
    private TransactionRepository transactionRepository;

    /** The class under test, with the mock injected through its single constructor. */
    @InjectMocks
    private TransactionListService service;

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /**
     * Builds a {@link Transaction} fixture carrying only the fields the service's
     * row projection reads ({@code TRAN-ID}, {@code TRAN-PROC-TS},
     * {@code TRAN-DESC}, {@code TRAN-AMT}).
     *
     * @param tranId    the transaction id ({@code TRAN-ID PIC X(16)})
     * @param procTs    the processing-timestamp text ({@code TRAN-PROC-TS PIC X(26)});
     *                  may be {@code null}
     * @param desc      the transaction description ({@code TRAN-DESC})
     * @param amt       the transaction amount ({@code TRAN-AMT PIC S9(09)V99}); may be
     *                  {@code null}
     * @return a populated {@link Transaction}
     */
    private static Transaction txn(String tranId, String procTs, String desc, BigDecimal amt) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranProcTs(procTs);
        t.setTranDesc(desc);
        t.setTranAmt(amt);
        return t;
    }

    /**
     * Wraps the supplied content in a paged {@link PageImpl} so the mocked
     * repository returns a page whose {@code getNumber()}, {@code getSize()} and
     * {@code getTotalElements()} are fully controlled by the test (the values the
     * service copies into the response metadata).
     *
     * @param content       the page content (must not contain {@code null} elements)
     * @param zeroBasedPage  the zero-based Spring page index
     * @param size          the page size
     * @param total         the total element count across all pages
     * @return a {@link Page} of transactions backed by {@link PageImpl}
     */
    private static Page<Transaction> pageOf(List<Transaction> content, int zeroBasedPage, int size, long total) {
        return new PageImpl<Transaction>(content, PageRequest.of(zeroBasedPage, size), total);
    }

    /**
     * Convenience single-row page used by tests that do not care about the row
     * payload, only about paging behaviour or delegation.
     *
     * @param zeroBasedPage the zero-based Spring page index
     * @param total         the total element count across all pages
     * @return a one-row {@link Page} of transactions
     */
    private static Page<Transaction> oneRowPage(int zeroBasedPage, long total) {
        return pageOf(
                List.of(txn("0000000000000001", SAMPLE_TIMESTAMP, "Purchase", new BigDecimal("1.00"))),
                zeroBasedPage, EXPECTED_PAGE_SIZE, total);
    }

    // ------------------------------------------------------------------
    // Page size — COTRN00 fixed 10-row map
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions requests a page of exactly 10 rows and echoes pageSize 10")
    void listTransactions_pageSizeIsTen() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(oneRowPage(0, 1L));

        TransactionListResponse response = service.listTransactions(null, null, 0);

        // The service must build its PageRequest with PAGE_SIZE == 10.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);

        // ...and the size must survive into the response envelope.
        assertThat(response.page().pageSize()).isEqualTo(10);
    }

    // ------------------------------------------------------------------
    // Browse scope — card filter routes to findByTranCardNum, else findAll
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions scopes the browse to findByTranCardNum when a card-number filter is supplied")
    void listTransactions_withCardFilter_usesFindByTranCardNum() {
        String card = "4111111111111111";
        when(transactionRepository.findByTranCardNum(eq(card), any(Pageable.class)))
                .thenReturn(oneRowPage(0, 1L));

        service.listTransactions(null, card, 0);

        verify(transactionRepository).findByTranCardNum(eq(card), any(Pageable.class));
        verify(transactionRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("listTransactions browses the whole file via findAll when no card-number filter is supplied")
    void listTransactions_noCardFilter_usesFindAll() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(oneRowPage(0, 1L));

        service.listTransactions(null, null, 0);

        verify(transactionRepository).findAll(any(Pageable.class));
        verify(transactionRepository, never()).findByTranCardNum(any(), any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Filter edit — non-numeric transaction id is rejected (Gate 5 parity)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions rejects a non-numeric transaction-id filter with the verbatim COTRN00C edit message")
    void listTransactions_nonNumericTranIdFilter_throwsValidation() {
        assertThatThrownBy(() -> service.listTransactions("ABC", null, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_TRANID_NOT_NUMERIC);

        // The edit fails before any browse, so the repository is never consulted.
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("listTransactions accepts a numeric transaction-id filter and echoes it back unchanged")
    void listTransactions_numericTranIdFilter_isAcceptedAndEchoed() {
        String numericFilter = "0000000000000042";
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(oneRowPage(0, 1L));

        TransactionListResponse response = service.listTransactions(numericFilter, null, 0);

        // A numeric filter passes the edit and is echoed verbatim into the response.
        assertThat(response.transactionIdFilter()).isEqualTo(numericFilter);
        verify(transactionRepository).findAll(any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Decimal fidelity — TRAN-AMT PIC S9(09)V99 -> BigDecimal scale 2
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions normalises every amount to BigDecimal scale 2, preserving sign and rounding HALF_UP")
    void listTransactions_amountScaleTwo() {
        List<Transaction> content = List.of(
                txn("0000000000000001", SAMPLE_TIMESTAMP, "Scale up", new BigDecimal("100.5")),
                txn("0000000000000002", SAMPLE_TIMESTAMP, "Integer", new BigDecimal("42")),
                txn("0000000000000003", SAMPLE_TIMESTAMP, "Negative", new BigDecimal("-15.2")),
                txn("0000000000000004", SAMPLE_TIMESTAMP, "Rounded", new BigDecimal("1234.567")),
                txn("0000000000000005", SAMPLE_TIMESTAMP, "No amount", null));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(content, 0, EXPECTED_PAGE_SIZE, content.size()));

        List<TransactionListItem> rows = service.listTransactions(null, null, 0).page().content();

        assertThat(rows).hasSize(5);

        // Every populated amount is scale 2 and equal by compareTo (never float/double).
        assertThat(rows.get(0).amount().scale()).isEqualTo(2);
        assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("100.50"));

        assertThat(rows.get(1).amount().scale()).isEqualTo(2);
        assertThat(rows.get(1).amount()).isEqualByComparingTo(new BigDecimal("42.00"));

        // Sign is preserved through the scale normalisation.
        assertThat(rows.get(2).amount().scale()).isEqualTo(2);
        assertThat(rows.get(2).amount()).isEqualByComparingTo(new BigDecimal("-15.20"));

        // A third-decimal value rounds HALF_UP to two places.
        assertThat(rows.get(3).amount().scale()).isEqualTo(2);
        assertThat(rows.get(3).amount()).isEqualByComparingTo(new BigDecimal("1234.57"));

        // A null amount is passed through null-safely (no scale applied).
        assertThat(rows.get(4).amount()).isNull();
    }

    // ------------------------------------------------------------------
    // Page metadata mapping — multi-page result
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions maps page metadata for a multi-page result (25 elements, page 1, size 10 -> 3 pages)")
    void listTransactions_mapsPageMetadata() {
        // The metadata is derived from the total element count and the fixed page
        // size, so it is independent of how many rows this stub page carries.
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(oneRowPage(0, 25L));

        PageResponse<TransactionListItem> page = service.listTransactions(null, null, 0).page();

        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
    }

    @Test
    @DisplayName("listTransactions converts Spring's zero-based page index to a one-based page number")
    void listTransactions_pageNumberIsOneBased() {
        // Spring page index 1 (the second page) must surface as one-based number 2.
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(oneRowPage(1, 25L));

        PageResponse<TransactionListItem> page = service.listTransactions(null, null, 1).page();

        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page.first()).isFalse();
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.last()).isFalse();
    }

    // ------------------------------------------------------------------
    // Screen date preservation — verbatim leading slice, never reformatted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions preserves the transaction date as a verbatim screen string (never reparsed/reformatted)")
    void listTransactions_preservesTransactionDateString() {
        String fullTimestamp = "2024-06-15-12.30.45.678901";
        String shortScreenDate = "24/06/15"; // an already-short screen date must pass through unchanged
        List<Transaction> content = List.of(
                txn("0000000000000001", fullTimestamp, "Full timestamp", new BigDecimal("1.00")),
                txn("0000000000000002", shortScreenDate, "Short screen date", new BigDecimal("2.00")),
                txn("0000000000000003", null, "Null timestamp", new BigDecimal("3.00")));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(content, 0, EXPECTED_PAGE_SIZE, content.size()));

        List<TransactionListItem> rows = service.listTransactions(null, null, 0).page().content();

        // A full 26-char timestamp yields its leading date, taken verbatim from the
        // source text (a prefix of the original) rather than parsed and reformatted.
        assertThat(rows.get(0).transactionDate()).isEqualTo("2024-06-15");
        assertThat(fullTimestamp).startsWith(rows.get(0).transactionDate());

        // An already-short screen date is returned exactly as stored (no reformatting).
        assertThat(rows.get(1).transactionDate()).isEqualTo(shortScreenDate);

        // A null timestamp is preserved as null (null-safe, no substitution).
        assertThat(rows.get(2).transactionDate()).isNull();
    }
}

