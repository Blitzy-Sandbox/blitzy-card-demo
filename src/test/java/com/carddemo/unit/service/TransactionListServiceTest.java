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
import com.carddemo.exception.ValidationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * size&nbsp;{@code 10}, ordered by {@code TRAN-ID} ascending.</p>
 *
 * <p><strong>Transaction-id filter ({@code TRNIDINI}).</strong> The suite pins
 * the three filter outcomes of {@code COTRN00C} paragraph
 * {@code PROCESS-ENTER-KEY}: a blank filter browses the whole dataset via
 * {@code findAll(Pageable)} ({@code MOVE LOW-VALUES TO TRAN-ID}); a numeric
 * filter positions the browse via
 * {@code findByTranIdGreaterThanEqual(key, Pageable)} ({@code MOVE TRNIDINI TO
 * TRAN-ID}, {@code STARTBR ... GTEQ}), left-zero-padding a short value to the
 * sixteen-character {@code TRAN-ID} key width; and a non-numeric filter is
 * rejected with {@link ValidationException} carrying
 * {@code 'Tran ID must be Numeric ...'} ({@code COTRN00C} line&nbsp;214). The
 * row-selection edit {@code 'Invalid selection. Valid value is S'}
 * ({@code COTRN00C} line&nbsp;199) acts on the {@code SEL00nnI} flags that drive
 * the {@code XCTL} to the detail program {@code COTRN01C}; on this stateless list
 * contract that navigation is the separate {@code GET /api/transactions/{id}}
 * resource, so the list service exposes no row-select field and the suite does
 * not assert that message here.</p>
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps no Spring
 * {@code ApplicationContext}; it uses no {@code @SpringBootTest}, {@code MockMvc},
 * Testcontainers, database, or AWS resource. The single collaborator,
 * {@link TransactionRepository}, is Mockito-mocked and constructor-injected into
 * the service, keeping every test fast, deterministic, and decoupled from JPA.</p>
 *
 * <p><strong>Page indexing.</strong> The service treats the {@code pageNumber}
 * argument as a zero-based index, clamped with {@code Math.max(pageNumber, 0)},
 * and passes it into {@code PageRequest.of(index, 10, Sort.by(ASC, "tranId"))} on
 * both the unfiltered and positioned paths.
 * {@link TransactionDto.ListResponse#pageNumber()} echoes that clamped index as a
 * {@link String}; {@link TransactionDto.ListResponse#transactionIdFilter()} is
 * {@code null} for an unfiltered browse and echoes the supplied filter otherwise.
 * The {@link ArgumentCaptor} tests lock the size ({@code 10}), the zero-based
 * index, the ascending {@code tranId} sort, and the positioning key reaching the
 * repository.</p>
 *
 * <p><strong>Empty pages are normal.</strong> A filter with no matches, an empty
 * unfiltered page, and a page index beyond the available data all yield an empty
 * {@code transactions()} list with no thrown exception, mirroring the legacy
 * end-of-data screen behavior rather than an error.</p>
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
 * <p>{@code MockitoExtension} runs in its default strict-stub mode, so each test
 * registers only the stub its exercised path consumes.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COTRN00C (CT00) paginated transaction list @ 27d6c6f")
class TransactionListServiceTest {

    /**
     * Sixteen-digit numeric {@code TRNIDINI} positioning filter used across the
     * filtered scenarios, matching the {@code TRAN-ID PIC X(16)} key width.
     */
    private static final String TRAN_ID_FILTER = "0000000000000001";

    /**
     * Sixteen-character card number carried on the sample rows
     * ({@code TRAN-CARD-NUM PIC X(16)}); the list projection does not read it.
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
     * Builds a fully populated {@link Transaction}. Only the fields the list
     * projection reads &mdash; {@code tranId}, {@code origTs} (date source),
     * {@code tranDesc} (truncated), and {@code tranAmt} &mdash; vary per call; the
     * remaining fields carry representative, non-null values that the projection
     * ignores.
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
                TransactionTypeCode.PURCHASE.getCode(),
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
    // Phase 1 — numeric filter: positioned browse, rows mapped to summaries
    // -----------------------------------------------------------------

    @Test
    @DisplayName("numeric filter: positioned page maps every row to a TransactionSummary and echoes the filter")
    void numericFilterMapsRowsToSummaries() {
        Transaction t1 = tx("0000000000000001", CARD, "GROCERY STORE",
                new BigDecimal("12.34"), "2023-05-17 12:34:56.123456");
        Transaction t2 = tx("0000000000000002", CARD, "GAS STATION REFUEL",
                new BigDecimal("-50.00"), "2023-06-01 08:00:00.000000");
        // A 30-character description (A-Z then 0123); the list view truncates it
        // to the 26-character TDESCnn width, leaving exactly the 26 letters.
        Transaction t3 = tx("0000000000000003", CARD, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123",
                new BigDecimal("100.5"), "2023-12-25 23:59:59.999999");
        List<Transaction> rows = List.of(t1, t2, t3);
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        TransactionDto.ListResponse response = service.listTransactions(TRAN_ID_FILTER, 0);

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
        assertThat(response.transactionIdFilter()).isEqualTo(TRAN_ID_FILTER);
        verify(transactionRepository).findByTranIdGreaterThanEqual(anyString(), any(Pageable.class));
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
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        TransactionDto.ListResponse response = service.listTransactions(TRAN_ID_FILTER, 0);

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
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        TransactionDto.ListResponse response = service.listTransactions(TRAN_ID_FILTER, 0);

        assertThat(response.transactions().get(0).date()).isEqualTo("05/17/23");
        assertThat(response.transactions().get(1).date()).isEqualTo("12/31/99");
        assertThat(response.transactions().get(2).date()).isEqualTo("02/29/24");
        assertThat(response.transactions().get(3).date()).isNull();
        assertThat(response.transactions().get(4).date()).isNull();
    }

    // -----------------------------------------------------------------
    // Phase 2 — positioning + paging: PAGE_SIZE = 10, zero-based index, sort
    // -----------------------------------------------------------------

    @Test
    @DisplayName("numeric filter: positions via findByTranIdGreaterThanEqual with size 10, the page index, and ascending tranId sort")
    void numericFilterPositionsBrowseWithCapturedPageable() {
        List<Transaction> ten = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ten.add(tx(String.format("%016d", i), CARD, "TXN " + i,
                    new BigDecimal(i + ".00"), "2023-03-15 10:00:00.000000"));
        }
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(ten));

        TransactionDto.ListResponse response = service.listTransactions(TRAN_ID_FILTER, 1);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository)
                .findByTranIdGreaterThanEqual(keyCaptor.capture(), pageableCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(TRAN_ID_FILTER);
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageSize()).isEqualTo(10);
        assertThat(captured.getPageNumber()).isEqualTo(1);
        assertThat(captured.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "tranId"));
        assertThat(response.transactions()).hasSize(10);
        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.transactionIdFilter()).isEqualTo(TRAN_ID_FILTER);
    }

    @Test
    @DisplayName("short numeric filter is left-zero-padded to the sixteen-character TRAN-ID key before positioning")
    void shortNumericFilterIsLeftZeroPaddedToSixteen() {
        List<Transaction> rows = List.of(tx("0000000000000005", CARD, "TXN",
                new BigDecimal("5.00"), "2023-03-15 10:00:00.000000"));
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        TransactionDto.ListResponse response = service.listTransactions("5", 0);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(transactionRepository)
                .findByTranIdGreaterThanEqual(keyCaptor.capture(), any(Pageable.class));
        // "5" positions at the zero-padded key, but the echoed filter is the user's input.
        assertThat(keyCaptor.getValue()).isEqualTo("0000000000000005");
        assertThat(response.transactionIdFilter()).isEqualTo("5");
        assertThat(response.transactions()).hasSize(1);
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
    @DisplayName("blank filter is normalized to 'not supplied' and routes to findAll, not the positioning finder")
    void blankFilterRoutesToFindAll() {
        List<Transaction> one = List.of(tx("0000000000000001", CARD, "TXN",
                new BigDecimal("9.99"), "2023-03-15 10:00:00.000000"));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(one));

        TransactionDto.ListResponse response = service.listTransactions("   ", 0);

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactionIdFilter()).isNull();
        verify(transactionRepository).findAll(any(Pageable.class));
        verify(transactionRepository, never())
                .findByTranIdGreaterThanEqual(anyString(), any(Pageable.class));
    }

    // -----------------------------------------------------------------
    // Phase 3 — empty result sets are a normal (non-exceptional) outcome
    // -----------------------------------------------------------------

    @Test
    @DisplayName("numeric filter with no matches: empty list, no exception (legacy end-of-data outcome)")
    void numericFilterEmptyReturnsEmptyWithoutThrowing() {
        when(transactionRepository.findByTranIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        TransactionDto.ListResponse response =
                assertDoesNotThrow(() -> service.listTransactions(TRAN_ID_FILTER, 0));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.transactionIdFilter()).isEqualTo(TRAN_ID_FILTER);
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
    // Phase 4 — transaction-id numeric edit (COTRN00C line 214)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("non-numeric filter is rejected with 'Tran ID must be Numeric ...' and never touches the repository")
    void nonNumericFilterThrowsTranIdMustBeNumeric() {
        // COTRN00C PROCESS-ENTER-KEY: IF TRNIDINI NOT NUMERIC -> 'Tran ID must be Numeric ...'
        // (line 214). The edit fires before any STARTBR, so the repository is untouched.
        assertThatThrownBy(() -> service.listTransactions("12AB", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID must be Numeric ...");

        verifyNoInteractions(transactionRepository);
    }
}
