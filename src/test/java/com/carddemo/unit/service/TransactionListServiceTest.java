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
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.TransactionListService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link TransactionListService}, the
 * read-only, paginated transaction-list service that backs
 * {@code GET /api/transactions}.
 *
 * <p>{@code TransactionListService} is the Java realization of the CICS
 * pseudo-conversational program {@code COTRN00C} (transaction {@code CT00}, list
 * transactions) at source commit {@code 27d6c6f}. The legacy VSAM browse
 * machinery over the {@code TRANSACT} KSDS &mdash;
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV} across the ten-row
 * {@code COTRN00} display array ({@code TRNID01I}..{@code TRNID10I}) with PF7/PF8
 * page navigation &mdash; becomes stateless Spring Data pagination of fixed page
 * size&nbsp;{@code 10}. An optional card-number filter narrows the browse to one
 * card's transactions; when absent the full transaction set is browsed in
 * {@code TRAN-ID} ascending order, reproducing the key-sequenced VSAM read.</p>
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps no Spring
 * {@code ApplicationContext}; it uses no {@code @SpringBootTest}, {@code MockMvc},
 * Testcontainers, database, or AWS resource. The single collaborator,
 * {@link TransactionRepository}, is Mockito-mocked and constructor-injected into
 * the service, keeping every test fast, deterministic, and decoupled from JPA.</p>
 *
 * <p><strong>Page indexing.</strong> The compiled service treats the
 * {@code pageNumber} argument as a zero-based index, clamped with
 * {@code Math.max(pageNumber, 0)}. For the unfiltered browse the clamped index
 * is passed straight into {@code PageRequest.of(index, 10, Sort.by(ASC,
 * "tranId"))}; for the card-filtered path the same index slices the
 * {@code findByCardNumOrderByTranIdAsc} result in memory at the
 * {@code PAGE_SIZE}&nbsp;=&nbsp;10 boundary. {@link TransactionDto.ListResponse#pageNumber()}
 * echoes that clamped index as a {@link String}, and
 * {@link TransactionDto.ListResponse#transactionIdFilter()} is always
 * {@code null} because the compiled {@code buildResponse} sets it so. The
 * {@link ArgumentCaptor} tests lock both the size ({@code 10}) and the zero-based
 * index that reach the repository.</p>
 *
 * <p><strong>Empty pages are normal.</strong> A card with no transactions, an
 * empty unfiltered page, and a page index beyond the available data all yield an
 * empty {@code transactions()} list with no thrown exception, mirroring the
 * legacy end-of-data screen behavior rather than an error.</p>
 *
 * <p><strong>Parity assertions (Gate&nbsp;1 / Gate&nbsp;4).</strong> Each list
 * row is mapped exactly as {@code COTRN00C} paragraph {@code POPULATE-TRAN-DATA}
 * populates it: {@code TRAN-ID} verbatim, the {@code MM/DD/YY} date derived from
 * the {@code YYYY-MM-DD HH:MM:SS.mmmmmm} {@code TRAN-ORIG-TS}
 * ({@code WS-TIMESTAMP-DT-YYYY(3:2)} recomposed as {@code MM/DD/YY}), the
 * {@code TRAN-DESC} truncated to the 26-character {@code TDESCnn} list width, and
 * {@code TRAN-AMT}. Monetary amounts are {@link BigDecimal}, asserted with
 * {@code compareTo} semantics ({@code isEqualByComparingTo}) and never as
 * {@code double}/{@code float}.</p>
 *
 * <p><strong>Validation divergence (compiled source authoritative).</strong>
 * {@code COTRN00C} carries two screen-field edits &mdash;
 * {@code 'Invalid selection. Valid value is S'} (line&nbsp;199, for a row-select
 * flag other than {@code S}) and {@code 'Tran ID must be Numeric ...'}
 * (line&nbsp;214, for a non-numeric {@code TRNIDINI} filter). Both act on
 * {@code COTRN00} terminal fields (the {@code SEL00nnI} selection flags and the
 * numeric tran-id filter) that the stateless {@code listTransactions(String,
 * int)} API does not expose: row selection is re-homed to the detail endpoint
 * ({@code GET /api/transactions/{id}}), and this service accepts only a
 * card-number filter. The compiled service therefore performs neither edit and
 * throws no {@code ValidationException}. Per "assert verbatim where reachable",
 * those messages have no reachable code path here; the suite instead pins the
 * actual behavior &mdash; a supplied filter is treated as an opaque card token
 * and flows straight to the by-card finder without a numeric edit.</p>
 *
 * <p>{@code MockitoExtension} runs in its default strict-stub mode, so each test
 * registers only the stub its exercised path consumes.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COTRN00C (CT00) paginated transaction list @ 27d6c6f")
class TransactionListServiceTest {

    /**
     * Sixteen-character card number used across the card-filtered scenarios,
     * matching the {@code TRAN-CARD-NUM PIC X(16)} width.
     */
    private static final String CARD = "0500000000000001";

    /** Mocked repository: the service's only collaborator. */
    @Mock
    private TransactionRepository transactionRepository;

    /** System under test, constructor-injected with the mocked repository. */
    @InjectMocks
    private TransactionListService service;

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Builds a fully populated {@link Transaction} on {@link #CARD}. Only the
     * fields the list projection reads &mdash; {@code tranId}, {@code origTs}
     * (date source), {@code tranDesc} (truncated), and {@code tranAmt} &mdash;
     * vary per call; the remaining fields carry representative, non-null values
     * that the projection ignores.
     *
     * @param tranId   the sixteen-character transaction identifier (primary key)
     * @param cardNum  the sixteen-character card number
     * @param tranDesc the transaction description ({@code TRAN-DESC}); may be {@code null}
     * @param tranAmt  the monetary amount ({@code TRAN-AMT}), scale 2
     * @param origTs   the origination timestamp text ({@code TRAN-ORIG-TS}); may be {@code null}
     * @return a populated {@link Transaction}
     */
    private static Transaction tx(String tranId, String cardNum, String tranDesc,
            BigDecimal tranAmt, String origTs) {
        return new Transaction(
                tranId,
                TransactionTypeCode.PURCHASE,
                100,
                "POS",
                tranDesc,
                tranAmt,
                123_456_789L,
                "MERCHANT NAME",
                "ANYTOWN",
                "12345",
                cardNum,
                origTs,
                origTs);
    }

    // -----------------------------------------------------------------
    // Phase 1 — list by card filter: rows mapped to TransactionSummary
    // -----------------------------------------------------------------

    @Test
    @DisplayName("card filter: page 0 returns every card transaction mapped to a TransactionSummary")
    void cardFilterMapsRowsToSummaries() {
        Transaction t1 = tx("0000000000000001", CARD, "GROCERY STORE",
                new BigDecimal("12.34"), "2023-05-17 12:34:56.123456");
        Transaction t2 = tx("0000000000000002", CARD, "GAS STATION REFUEL",
                new BigDecimal("-50.00"), "2023-06-01 08:00:00.000000");
        // A 30-character description (A-Z then 0123); the list view truncates it
        // to the 26-character TDESCnn width, leaving exactly the 26 letters.
        Transaction t3 = tx("0000000000000003", CARD, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123",
                new BigDecimal("100.5"), "2023-12-25 23:59:59.999999");
        List<Transaction> rows = List.of(t1, t2, t3);
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD)).thenReturn(rows);

        TransactionDto.ListResponse response = service.listTransactions(CARD, 0);

        assertThat(response.transactions()).hasSize(3);
        assertThat(response.transactions())
                .extracting(TransactionDto.TransactionSummary::transactionId)
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
        assertThat(response.transactions())
                .extracting(TransactionDto.TransactionSummary::date)
                .containsExactly("05/17/23", "06/01/23", "12/25/23");
        assertThat(response.transactions())
                .extracting(TransactionDto.TransactionSummary::description)
                .containsExactly("GROCERY STORE", "GAS STATION REFUEL",
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        // Amounts are BigDecimal: assert with compareTo semantics, never double/float.
        assertThat(response.transactions().get(0).amount())
                .isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(response.transactions().get(1).amount())
                .isEqualByComparingTo(new BigDecimal("-50.00"));
        // Stored scale 1 ("100.5") still compares equal to "100.50" via compareTo,
        // proving the assertion uses compareTo rather than BigDecimal.equals.
        assertThat(response.transactions().get(2).amount())
                .isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.transactionIdFilter()).isNull();
        verify(transactionRepository).findByCardNumOrderByTranIdAsc(CARD);
    }

    @Test
    @DisplayName("description truncation: > 26 chars clipped to 26; <= 26, short, and null preserved")
    void descriptionTruncatedToListWidth() {
        String exactly26 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";   // 26 characters
        String twentySeven = exactly26 + "7";              // 27 characters
        Transaction tExact = tx("0000000000000001", CARD, exactly26,
                new BigDecimal("1.00"), "2023-01-02 00:00:00.000000");
        Transaction tOver = tx("0000000000000002", CARD, twentySeven,
                new BigDecimal("2.00"), "2023-01-02 00:00:00.000000");
        Transaction tShort = tx("0000000000000003", CARD, "SHORT",
                new BigDecimal("3.00"), "2023-01-02 00:00:00.000000");
        Transaction tNull = tx("0000000000000004", CARD, null,
                new BigDecimal("4.00"), "2023-01-02 00:00:00.000000");
        List<Transaction> rows = List.of(tExact, tOver, tShort, tNull);
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD)).thenReturn(rows);

        TransactionDto.ListResponse response = service.listTransactions(CARD, 0);

        assertThat(response.transactions().get(0).description()).isEqualTo(exactly26);
        assertThat(response.transactions().get(1).description()).isEqualTo(exactly26);
        assertThat(response.transactions().get(2).description()).isEqualTo("SHORT");
        assertThat(response.transactions().get(3).description()).isNull();
    }

    @Test
    @DisplayName("date formatting: TRAN-ORIG-TS becomes MM/DD/YY; short or null timestamps yield null")
    void originTimestampFormattedAsMmDdYy() {
        Transaction sample = tx("0000000000000001", CARD, "D",
                new BigDecimal("1.00"), "2023-05-17 12:34:56.123456");
        Transaction century = tx("0000000000000002", CARD, "D",
                new BigDecimal("1.00"), "1999-12-31 00:00:00.000000");
        Transaction tenChars = tx("0000000000000003", CARD, "D",
                new BigDecimal("1.00"), "2024-02-29");        // exactly 10 characters
        Transaction tooShort = tx("0000000000000004", CARD, "D",
                new BigDecimal("1.00"), "2023-05-1");         // 9 characters -> null
        Transaction noTs = tx("0000000000000005", CARD, "D",
                new BigDecimal("1.00"), null);                // null -> null
        List<Transaction> rows = List.of(sample, century, tenChars, tooShort, noTs);
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD)).thenReturn(rows);

        TransactionDto.ListResponse response = service.listTransactions(CARD, 0);

        assertThat(response.transactions().get(0).date()).isEqualTo("05/17/23");
        assertThat(response.transactions().get(1).date()).isEqualTo("12/31/99");
        assertThat(response.transactions().get(2).date()).isEqualTo("02/29/24");
        assertThat(response.transactions().get(3).date()).isNull();
        assertThat(response.transactions().get(4).date()).isNull();
    }

    // -----------------------------------------------------------------
    // Phase 2 — paging: PAGE_SIZE = 10, zero-based index, page echo
    // -----------------------------------------------------------------

    @Test
    @DisplayName("card filter paging: PAGE_SIZE=10 sliced in memory over the zero-based page index")
    void cardFilterPagesInMemoryAtPageSizeTen() {
        List<Transaction> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String id = String.format("%016d", i);
            twelve.add(tx(id, CARD, "TXN " + i, new BigDecimal(i + ".00"),
                    "2023-03-15 10:00:00.000000"));
        }
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD)).thenReturn(twelve);

        // Page 0 -> the first ten rows (TRAN-ID 0000000000000001..0000000000000010).
        TransactionDto.ListResponse page0 = service.listTransactions(CARD, 0);
        assertThat(page0.transactions()).hasSize(10);
        assertThat(page0.transactions().get(0).transactionId()).isEqualTo("0000000000000001");
        assertThat(page0.transactions().get(9).transactionId()).isEqualTo("0000000000000010");
        assertThat(page0.pageNumber()).isEqualTo("0");

        // Page 1 -> the remaining two rows.
        TransactionDto.ListResponse page1 = service.listTransactions(CARD, 1);
        assertThat(page1.transactions()).hasSize(2);
        assertThat(page1.transactions().get(0).transactionId()).isEqualTo("0000000000000011");
        assertThat(page1.transactions().get(1).transactionId()).isEqualTo("0000000000000012");
        assertThat(page1.pageNumber()).isEqualTo("1");

        // Page 2 -> beyond the available data: an empty page, not an exception.
        TransactionDto.ListResponse page2 = service.listTransactions(CARD, 2);
        assertThat(page2.transactions()).isEmpty();
        assertThat(page2.pageNumber()).isEqualTo("2");
    }

    @Test
    @DisplayName("no filter: browses via findAll(Pageable) — size 10, zero-based index, ascending tranId sort")
    void noFilterBrowsesViaFindAllWithCapturedPageable() {
        List<Transaction> ten = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ten.add(tx(String.format("%016d", i), CARD, "TXN " + i,
                    new BigDecimal(i + ".00"), "2023-03-15 10:00:00.000000"));
        }
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(ten));

        TransactionDto.ListResponse response = service.listTransactions(null, 2);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageSize()).isEqualTo(10);
        assertThat(captured.getPageNumber()).isEqualTo(2);
        assertThat(captured.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "tranId"));
        assertThat(response.transactions()).hasSize(10);
        assertThat(response.pageNumber()).isEqualTo("2");
        assertThat(response.transactionIdFilter()).isNull();
    }

    @Test
    @DisplayName("no filter: a negative page number is clamped to the first page (index 0)")
    void negativePageClampedToFirstPage() {
        List<Transaction> none = List.of();
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(none));

        TransactionDto.ListResponse response = service.listTransactions(null, -3);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.transactions()).isEmpty();
    }

    @Test
    @DisplayName("blank filter is normalized to 'not supplied' and routes to findAll, not the by-card finder")
    void blankFilterRoutesToFindAll() {
        List<Transaction> one = List.of(tx("0000000000000001", CARD, "TXN",
                new BigDecimal("9.99"), "2023-03-15 10:00:00.000000"));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(one));

        TransactionDto.ListResponse response = service.listTransactions("   ", 0);

        assertThat(response.transactions()).hasSize(1);
        verify(transactionRepository).findAll(any(Pageable.class));
        verify(transactionRepository, never()).findByCardNumOrderByTranIdAsc(anyString());
    }

    // -----------------------------------------------------------------
    // Phase 3 — empty result sets are a normal (non-exceptional) outcome
    // -----------------------------------------------------------------

    @Test
    @DisplayName("card filter with no matches: empty list, no exception (legacy end-of-data outcome)")
    void cardFilterEmptyReturnsEmptyWithoutThrowing() {
        List<Transaction> none = List.of();
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD)).thenReturn(none);

        TransactionDto.ListResponse response =
                assertDoesNotThrow(() -> service.listTransactions(CARD, 0));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.transactionIdFilter()).isNull();
    }

    @Test
    @DisplayName("no filter with an empty page: empty list, no exception")
    void noFilterEmptyPageReturnsEmptyWithoutThrowing() {
        List<Transaction> none = List.of();
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(none));

        TransactionDto.ListResponse response =
                assertDoesNotThrow(() -> service.listTransactions(null, 0));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.pageNumber()).isEqualTo("0");
    }

    // -----------------------------------------------------------------
    // Phase 4 — validation divergence (compiled source authoritative)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("divergence: a supplied filter is an opaque card token — the legacy numeric/selection edits are not reachable here")
    void suppliedFilterIsNotNumericallyValidated() {
        // COTRN00C raised 'Tran ID must be Numeric ...' (line 214) for a non-numeric
        // TRNIDINI filter and 'Invalid selection. Valid value is S' (line 199) for a
        // bad row-select flag. Both edits act on COTRN00 terminal fields that the
        // stateless listTransactions(String, int) API does not expose, so the
        // compiled service performs neither edit and raises no ValidationException.
        // This test pins that divergence: a non-numeric filter flows straight to the
        // by-card finder and returns normally.
        List<Transaction> none = List.of();
        when(transactionRepository.findByCardNumOrderByTranIdAsc("NOT-NUMERIC")).thenReturn(none);

        TransactionDto.ListResponse response =
                assertDoesNotThrow(() -> service.listTransactions("NOT-NUMERIC", 0));

        assertThat(response.transactions()).isEmpty();
        verify(transactionRepository).findByCardNumOrderByTranIdAsc("NOT-NUMERIC");
    }
}
