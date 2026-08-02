/*
 * ****************************************************************************
 * Program     : TransactionListServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies TransactionListService against COTRN00C paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the pageSize + 1 lookahead that stands
 *               in for the extra READNEXT of :308, the priming read of :286
 *               and :340 modelled as an exclusive key bound, the descending
 *               slot fill of :349-357, the edited amount mask of :56 including
 *               its deliberate loss of a ninth integer digit, the four
 *               deliberately preserved source defects, and the rule that a
 *               card number never reaches a log or an exception message.
 * Source      : app/cbl/COTRN00C.cbl (699 lines, 16 paragraphs)
 *               app/cpy-bms/COTRN00.CPY   (59 input fields, TDESCnnI X(26))
 *               app/cpy/CVTRA05Y.cpy      (TRAN-RECORD, TRAN-AMT S9(09)V99)
 *               app/cpy/COTTL01Y.cpy      (the two title literals)
 *               app/cpy/CSDAT01Y.cpy      (WS-TIMESTAMP 26, WS-CURDATE 8)
 *               app/cpy/CSMSG01Y.cpy      (CCDA-MSG-INVALID-KEY)
 *               app/csd/CARDDEMO.CSD      (CT00 -> COTRN00C) @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionListService;
import com.cardemo.service.transaction.TransactionListService.AttentionIdentifier;
import com.cardemo.service.transaction.TransactionListService.TransactionListScreen;
import com.cardemo.service.transaction.TransactionListService.TransactionListState;
import com.cardemo.unit.model.FixedClockProvider;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

/**
 * Unit tests for {@code TransactionListService}, the Java target of
 * {@code app/cbl/COTRN00C.cbl} and CICS transaction {@code CT00}.
 *
 * <p>The repository is a Mockito double, because the whole point of these tests is to control the
 * exact number of rows a browse window yields: the lookahead of {@code :L305-L320}, the partial-page
 * defect of {@code :L437-L439} and the descending fill of {@code :L349-L357} are only observable
 * when the row count is dictated row by row. {@code FileStatusMapper} is a real instance rather than
 * a double: it has a no-argument constructor and no collaborators of its own, so using the real
 * translation table means the exception types asserted here are the ones production raises. The
 * clock is fixed, so the two header fields the source derives from one
 * {@code MOVE FUNCTION CURRENT-DATE} at {@code :L570} are reproducible.
 *
 * <p>Four deliberately preserved source defects have a test each, and each such test names the
 * severity recorded for it in {@code DECISION_LOG.md}, so that a future contributor who "fixes" one
 * of them sees a red test rather than a silent parity divergence:
 *
 * <ul>
 *   <li><strong>High</strong> — {@code CDEMO-CT00-TRNID-LAST} is written only when slot ten is
 *       reached ({@code :L437-L439}), so a partial final page leaves the anchor stale.
 *   <li><strong>Medium</strong> — {@code :L197} and {@code :L202} are commented out, so an invalid
 *       row selector falls through into the paging logic instead of short-circuiting.
 *   <li><strong>Low</strong> — {@code INITIALIZE-TRAN-DATA} ({@code :L450-L505}) never blanks
 *       {@code SEL000nI}, so a selector is echoed back to the terminal.
 *   <li><strong>Low</strong> — the {@code PIC +99999999.99} mask at {@code :L56} renders eight
 *       integer digits while {@code TRAN-AMT} holds nine, so the high-order digit is lost.
 * </ul>
 */
@DisplayName("TransactionListService - COTRN00C / transaction CT00")
final class TransactionListServiceTest {

    /** A card number used only to prove it never escapes into a log, a projection or a message. */
    private static final String CARD = "4111111111111111";

    /** A full twenty-six character {@code TRAN-ORIG-TS}, per {@code app/cpy/CSDAT01Y.cpy:42-55}. */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** A full twenty-six character {@code TRAN-PROC-TS}. */
    private static final String PROC_TS = "2022-06-11 08:00:00.000000";

    /**
     * A blank twenty-six character timestamp. {@code app/data/ASCII/dailytran.txt} carries records
     * whose {@code TRAN-PROC-TS} is twenty-six spaces, so a blank timestamp is real data rather
     * than a synthetic edge case.
     */
    private static final String BLANK_TS = "                          ";

    /** Rows per page, from the loop bound {@code UNTIL WS-IDX > 10} at {@code :L290}. */
    private static final int PAGE_SIZE = 10;

    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";
    private static final String MSG_NOT_NUMERIC = "Tran ID must be Numeric ...";
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";
    private static final String MSG_AT_TOP = "You are at the top of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    private static final String MSG_UNABLE = "Unable to lookup transaction...";
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    private TransactionRepository repository;
    private TransactionListService service;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        service = new TransactionListService(repository, new FileStatusMapper(),
                FixedClockProvider.canonicalClock(), PAGE_SIZE);
    }

    // ---------------------------------------------------------------- fixtures

    /** Renders {@code n} as a sixteen-character {@code TRAN-ID PIC X(16)} value. */
    private static String id(final int n) {
        return String.format(Locale.ROOT, "%016d", n);
    }

    private static Transaction tx(final int n) {
        return tx(n, new BigDecimal("123.45"), "DESCRIPTION " + n, ORIG_TS);
    }

    private static Transaction tx(final int n, final BigDecimal amount, final String description,
            final String origTs) {
        return new Transaction(id(n), "01", 5, "POS       ", description, amount, 123L,
                "ACME", "SEATTLE", "12345-0001", CARD, origTs, PROC_TS);
    }

    /** {@code count} records with ascending keys, as an ascending browse window would yield. */
    private static List<Transaction> ascending(final int count) {
        final List<Transaction> rows = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            rows.add(tx(n));
        }
        return rows;
    }

    /** {@code count} records descending from {@code highest}, as a backward browse would yield. */
    private static List<Transaction> descendingFrom(final int highest, final int count) {
        final List<Transaction> rows = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            rows.add(tx(highest - n));
        }
        return rows;
    }

    /** Stubs the inclusive ascending finder, the bound ENTER uses because it never primes. */
    private void stubInclusive(final List<Transaction> rows) {
        when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    /** Stubs the exclusive ascending finder, the bound PF8 uses because it primes past the anchor. */
    private void stubExclusive(final List<Transaction> rows) {
        when(repository.findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    /** Stubs the descending finder, the bound PF7 uses. */
    private void stubDescending(final List<Transaction> rows) {
        when(repository.findByTransactionIdLessThanOrderByTransactionIdDesc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    private static List<String> selectors(final int oneBasedSlot, final String flag) {
        final String[] flags = new String[PAGE_SIZE];
        Arrays.fill(flags, "");
        flags[oneBasedSlot - 1] = flag;
        return Arrays.asList(flags);
    }

    private static List<String> displayedIds() {
        final List<String> ids = new ArrayList<>();
        for (int n = 1; n <= PAGE_SIZE; n++) {
            ids.add(id(n));
        }
        return ids;
    }

    private static List<String> blankIds() {
        final List<String> ids = new ArrayList<>();
        for (int n = 0; n < PAGE_SIZE; n++) {
            ids.add("");
        }
        return ids;
    }

    private static List<String> rowIds(final TransactionListScreen screen) {
        final List<String> ids = new ArrayList<>();
        for (final TransactionDto.TransactionListRow row : screen.page().getRows()) {
            ids.add(row.transactionId());
        }
        return ids;
    }

    private TransactionListScreen enter(final String searchKey) {
        return service.submitScreen(AttentionIdentifier.ENTER, searchKey, List.of(), List.of(),
                TransactionListState.initial());
    }

    private TransactionListScreen single(final BigDecimal amount, final String description,
            final String origTs) {
        stubInclusive(List.of(tx(1, amount, description, origTs)));
        return service.openList();
    }

    // ---------------------------------------------------------------- configuration

    @Nested
    @DisplayName("Configuration - the page size is policy, never a literal")
    final class Configuration {

        @Test
        @DisplayName("the applied page size is ten and the browse window asks for pageSize + 1")
        void pageSizeAndLookaheadWindow() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.openList();
            assertThat(screen.page().getPageSize()).isEqualTo(PAGE_SIZE);
            assertThat(screen.page().getPageSize()).isEqualTo(PageResponse.PAGE_SIZE_TRANSACTION_LIST);
            final ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(),
                    window.capture());
            assertThat(window.getValue().getPageSize()).isEqualTo(PAGE_SIZE + 1);
            assertThat(window.getValue().getPageNumber()).isZero();
        }

        @Test
        @DisplayName("a configured page size below one or wider than the symbolic map is rejected")
        void constructorValidatesPageSize() {
            final FileStatusMapper mapper = new FileStatusMapper();
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new TransactionListService(repository, mapper,
                            FixedClockProvider.canonicalClock(), 0));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new TransactionListService(repository, mapper,
                            FixedClockProvider.canonicalClock(), PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("every collaborator is mandatory")
        void constructorRejectsNullCollaborators() {
            final FileStatusMapper mapper = new FileStatusMapper();
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionListService(null, mapper, FixedClockProvider.canonicalClock(),
                            PAGE_SIZE));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionListService(repository, null,
                            FixedClockProvider.canonicalClock(), PAGE_SIZE));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionListService(repository, mapper, null, PAGE_SIZE));
        }
    }

    // ---------------------------------------------------------------- MAIN-PARA dispatch

    @Nested
    @DisplayName("MAIN-PARA dispatch, :95-141")
    final class MainParaDispatch {

        @Test
        @DisplayName("EIBCALEN = 0 targets the sign-on program and performs no browse, :107-109")
        void noContextTargetsSignOn() {
            final TransactionListScreen screen = service.openWithoutContext();
            assertThat(screen.navigationTarget()).isEqualTo("COSGN00C");
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.state().pageNumber()).isZero();
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF3 returns to the main menu without browsing, :122-123")
        void pf3ReturnsToMainMenu() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF3, null,
                    List.of(), List.of(), TransactionListState.initial());
            assertThat(screen.navigationTarget()).isEqualTo("COMEN01C");
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("any other attention identifier raises CCDA-MSG-INVALID-KEY, :130-133")
        void otherKeyIsRejected() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.OTHER,
                    null, List.of(), List.of(), TransactionListState.initial());
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_INVALID_KEY);
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the attention identifier is mandatory on a re-entry")
        void attentionIdentifierIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    service.submitScreen(null, null, List.of(), List.of(), null));
        }

        @Test
        @DisplayName("a null state and null row lists are tolerated and treated as a first display")
        void nullRequestCollectionsAreTolerated() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, null, null, null, null);
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("all five attention identifiers of :119-134 are modelled")
        void fiveAttentionIdentifiers() {
            assertThat(AttentionIdentifier.values()).containsExactly(AttentionIdentifier.ENTER,
                    AttentionIdentifier.PF3, AttentionIdentifier.PF7, AttentionIdentifier.PF8,
                    AttentionIdentifier.OTHER);
        }

        @Test
        @DisplayName("the initial state is blank anchors, page zero and the next-page flag off, :63-68")
        void initialState() {
            final TransactionListState initial = TransactionListState.initial();
            assertThat(initial.firstKey()).isNull();
            assertThat(initial.lastKey()).isNull();
            assertThat(initial.pageNumber()).isZero();
            assertThat(initial.nextPageAvailable()).isFalse();
        }
    }

    // ---------------------------------------------------------------- POPULATE-HEADER-INFO

    @Nested
    @DisplayName("POPULATE-HEADER-INFO, :567-586")
    final class HeaderInfo {

        @Test
        @DisplayName("the six recurring header fields carry their COTTL01Y and CSDAT01Y values")
        void headerFields() {
            stubInclusive(ascending(11));
            final TransactionDto list = service.openList().list();
            assertThat(list.transactionName()).isEqualTo("CT00");
            assertThat(list.programName()).isEqualTo("COTRN00C");
            assertThat(list.title01()).isEqualTo("      AWS Mainframe Modernization       ").hasSize(40);
            assertThat(list.title02()).isEqualTo("              CardDemo                  ").hasSize(40);
            assertThat(list.currentDate()).isEqualTo("06/10/22").hasSize(8);
            assertThat(list.currentTime()).isEqualTo("19:27:53").hasSize(8);
        }

        @Test
        @DisplayName("the header is populated even on a path that never reads a row")
        void headerPopulatedOnEmptyBrowse() {
            stubInclusive(List.of());
            final TransactionDto list = service.openList().list();
            assertThat(list.transactionName()).isEqualTo("CT00");
            assertThat(list.currentDate()).isEqualTo("06/10/22");
        }
    }

    // ---------------------------------------------------------------- forward paging

    @Nested
    @DisplayName("PROCESS-PAGE-FORWARD, :279-328")
    final class ForwardPaging {

        @Test
        @DisplayName("a full page renders ten rows, both anchors and the next-page flag on")
        void fullPage() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.openList();
            assertThat(rowIds(screen)).containsExactly(id(1), id(2), id(3), id(4), id(5),
                    id(6), id(7), id(8), id(9), id(10));
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            assertThat(screen.page().getFirstKey()).isEqualTo(id(1));
            assertThat(screen.page().getLastKey()).isEqualTo(id(10));
            assertThat(screen.state().pageNumber()).isEqualTo(1);
            assertThat(screen.list().pageNumber()).isEqualTo("00000001");
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("exactly ten rows fills slot ten, so the last-key anchor IS written")
        void exactlyTenRows() {
            stubInclusive(ascending(10));
            final TransactionListScreen screen = service.openList();
            assertThat(rowIds(screen)).hasSize(PAGE_SIZE);
            assertThat(screen.page().getLastKey()).isEqualTo(id(10));
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("High: a partial final page leaves TRNID-LAST stale, exactly as :437-439 does")
        void partialPageLeavesLastKeyStale() {
            stubInclusive(ascending(4));
            final TransactionListScreen screen = service.openList();
            assertThat(rowIds(screen)).containsExactly(id(1), id(2), id(3), id(4));
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            assertThat(screen.page().getFirstKey()).isEqualTo(id(1));
            assertThat(screen.page().getLastKey()).isNull();
            assertThat(screen.state().lastKey()).isNull();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("the WS-IDX > 1 guard of :317 increments the page only when a row was read")
        void pageNumberGuard() {
            stubInclusive(ascending(1));
            assertThat(service.openList().state().pageNumber()).isEqualTo(1);
            setUp();
            stubInclusive(List.of());
            assertThat(service.openList().state().pageNumber()).isZero();
        }

        @Test
        @DisplayName("an empty browse reports :608 and leaves the page number at zero")
        void emptyBrowse() {
            stubInclusive(List.of());
            final TransactionListScreen screen = service.openList();
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.state().pageNumber()).isZero();
            assertThat(screen.page().getPageNumber()).isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().pageNumber()).isEqualTo("00000000");
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_AT_TOP);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("the map always carries exactly ten positional slots, blank ones included")
        void tenPositionalSlotsAlways() {
            stubInclusive(ascending(3));
            final TransactionListScreen screen = service.openList();
            assertThat(screen.list().rows()).hasSize(PAGE_SIZE);
            assertThat(screen.list().rows().get(2).transactionId()).isEqualTo(id(3));
            assertThat(screen.list().rows().get(3).transactionId()).isEmpty();
            assertThat(screen.list().rows().get(9).transactionId()).isEmpty();
            assertThat(screen.page().getRows()).hasSize(3);
        }

        @Test
        @DisplayName("ENTER performs no priming read, so it uses the inclusive key bound, :285-287")
        void enterUsesInclusiveBound() {
            stubInclusive(ascending(11));
            service.openList();
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                    key.capture(), any());
            assertThat(key.getValue()).isEmpty();
            verify(repository, never()).findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(),
                    any());
            verify(repository, never()).findByTransactionIdLessThanOrderByTransactionIdDesc(any(),
                    any());
        }

        @Test
        @DisplayName("ENTER pages forward regardless of the carried next-page flag, :225")
        void enterIgnoresCarriedNextPageFlag() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, List.of(), List.of(), new TransactionListState(id(9), id(9), 7, false));
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.state().pageNumber()).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- PF8

    @Nested
    @DisplayName("PROCESS-PF8-KEY, :257-274")
    final class Pf8Forward {

        @Test
        @DisplayName("PF8 primes past the last-key anchor, so it uses the exclusive key bound")
        void pf8UsesExclusiveBound() {
            final List<Transaction> window = new ArrayList<>();
            for (int n = 11; n <= 21; n++) {
                window.add(tx(n));
            }
            stubExclusive(window);
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF8, null,
                    List.of(), List.of(), new TransactionListState(id(1), id(10), 1, true));
            assertThat(rowIds(screen)).containsExactly(id(11), id(12), id(13), id(14), id(15),
                    id(16), id(17), id(18), id(19), id(20));
            assertThat(screen.page().getPageNumber()).isEqualTo(2);
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(screen.page().getFirstKey()).isEqualTo(id(11));
            assertThat(screen.page().getLastKey()).isEqualTo(id(20));
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanOrderByTransactionIdAsc(key.capture(),
                    any());
            assertThat(key.getValue()).isEqualTo(id(10));
            verify(repository, never())
                    .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any());
        }

        @Test
        @DisplayName("PF8 with the next-page flag off reports :270 and performs no browse")
        void pf8AtBottom() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF8, null,
                    List.of(), List.of(), new TransactionListState(id(1), id(10), 1, false));
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_BOTTOM);
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("High consequence: a stale blank last-key anchors past the end and yields no rows")
        void pf8OnStaleAnchor() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF8, null,
                    List.of(), List.of(), new TransactionListState(id(1), null, 1, true));
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a PF8 window that runs dry immediately reports :642, not :608, because it primed")
        void pf8EmptyWindowReportsBottom() {
            stubExclusive(List.of());
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF8, null,
                    List.of(), List.of(), new TransactionListState(id(1), id(10), 1, true));
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM);
            assertThat(screen.state().pageNumber()).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- PF7

    @Nested
    @DisplayName("PROCESS-PF7-KEY and PROCESS-PAGE-BACKWARD, :234-252 and :333-376")
    final class Pf7Backward {

        @Test
        @DisplayName("PF7 fills slots ten down to one and presents them ascending, decrementing the page")
        void pf7FillsDescendingAndPresentsAscending() {
            stubDescending(descendingFrom(11, 11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7, null,
                    List.of(), List.of(), new TransactionListState(id(12), id(21), 2, true));
            assertThat(rowIds(screen)).containsExactly(id(2), id(3), id(4), id(5), id(6),
                    id(7), id(8), id(9), id(10), id(11));
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
            assertThat(screen.page().getFirstKey()).isEqualTo(id(2));
            assertThat(screen.page().getLastKey()).isEqualTo(id(11));
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdLessThanOrderByTransactionIdDesc(key.capture(),
                    any());
            assertThat(key.getValue()).isEqualTo(id(12));
        }

        @Test
        @DisplayName("a backward window with no eleventh row clamps the page to one, :366-367")
        void backwardWindowWithoutSpareRowClampsToOne() {
            stubDescending(descendingFrom(10, 10));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7, null,
                    List.of(), List.of(), new TransactionListState(id(11), id(20), 5, true));
            assertThat(rowIds(screen)).hasSize(PAGE_SIZE);
            assertThat(screen.state().pageNumber()).isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_TOP);
        }

        @Test
        @DisplayName("PF7 on page one reports :248 and still sets NEXT-PAGE-YES unconditionally, :242")
        void pf7AtTopStillSetsNextPageYes() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7, null,
                    List.of(), List.of(), new TransactionListState(id(1), id(10), 1, false));
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP);
            assertThat(screen.state().nextPageAvailable()).isTrue();
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a partial backward window leaves TRNID-FIRST stale and the page number untouched")
        void pf7PartialWindow() {
            stubDescending(descendingFrom(4, 4));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7, null,
                    List.of(), List.of(), new TransactionListState(id(5), id(14), 3, true));
            assertThat(rowIds(screen)).containsExactly(id(1), id(2), id(3), id(4));
            assertThat(screen.list().rows().get(0).transactionId()).isEmpty();
            assertThat(screen.list().rows().get(5).transactionId()).isEmpty();
            assertThat(screen.list().rows().get(6).transactionId()).isEqualTo(id(1));
            assertThat(screen.list().rows().get(9).transactionId()).isEqualTo(id(4));
            assertThat(screen.state().firstKey()).isEqualTo(id(5));
            assertThat(screen.state().lastKey()).isEqualTo(id(4));
            assertThat(screen.state().pageNumber()).isEqualTo(3);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_TOP);
        }

        @Test
        @DisplayName("Low: the backward path does not blank the search field, unlike :325")
        void backwardPathDoesNotBlankSearchField() {
            stubDescending(descendingFrom(11, 11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7,
                    id(4), List.of(), List.of(), new TransactionListState(id(12), id(21), 2, true));
            assertThat(screen.list().transactionIdInput()).isEqualTo(id(4));
            stubInclusive(ascending(11));
            assertThat(enter(id(4)).list().transactionIdInput()).isEmpty();
        }

        @Test
        @DisplayName("a blank first-key anchor starts the backward browse from low values, :236-240")
        void blankFirstKeyStartsFromLowValues() {
            stubDescending(descendingFrom(11, 11));
            service.submitScreen(AttentionIdentifier.PF7, null, List.of(), List.of(),
                    new TransactionListState(null, id(21), 2, true));
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdLessThanOrderByTransactionIdDesc(key.capture(),
                    any());
            assertThat(key.getValue()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- selection scan

    @Nested
    @DisplayName("PROCESS-ENTER-KEY selection scan and dispatch, :148-204")
    final class SelectionScan {

        @Test
        @DisplayName("'S' at slot three navigates to COTRN01C and performs no browse, :186-193")
        void validSelectionNavigates() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, selectors(3, "S"), displayedIds(), TransactionListState.initial());
            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(3));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("lower-case 's' is accepted identically, :186")
        void lowerCaseSelectorAccepted() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, selectors(7, "s"), displayedIds(), TransactionListState.initial());
            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(7));
        }

        @Test
        @DisplayName("the first non-blank selector in positional order one to ten wins, :148-182")
        void firstNonBlankSelectorWins() {
            final String[] flags = new String[PAGE_SIZE];
            Arrays.fill(flags, "");
            flags[4] = "S";
            flags[1] = "S";
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, Arrays.asList(flags), displayedIds(), TransactionListState.initial());
            assertThat(screen.selectedTransactionId()).isEqualTo(id(2));
        }

        @Test
        @DisplayName("each of the ten slots is scanned, and slot ten is reachable")
        void everySlotIsScanned() {
            for (int slot = 1; slot <= PAGE_SIZE; slot++) {
                setUp();
                final TransactionListScreen screen = service.submitScreen(
                        AttentionIdentifier.ENTER, null, selectors(slot, "S"), displayedIds(),
                        TransactionListState.initial());
                assertThat(screen.selectedTransactionId()).isEqualTo(id(slot));
            }
        }

        @Test
        @DisplayName("Medium: an invalid selector falls through to paging - :197 and :202 are commented out")
        void invalidSelectionFallsThrough() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, selectors(1, "X"), displayedIds(), TransactionListState.initial());
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.selectedTransactionId()).isNull();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_INVALID_SELECTION);
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().getPageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("Low: INITIALIZE-TRAN-DATA never blanks SEL000nI, so the selector is echoed back")
        void selectorSurvivesRowBlanking() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, selectors(1, "X"), displayedIds(), TransactionListState.initial());
            assertThat(screen.list().rows().get(0).selectionFlag()).isEqualTo("X");
            assertThat(screen.list().rows().get(1).selectionFlag()).isEmpty();
        }

        @Test
        @DisplayName("a selector with no displayed identifier skips the dispatch entirely, :183")
        void selectorWithoutIdentifierSkipsDispatch() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, selectors(1, "S"), blankIds(), TransactionListState.initial());
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.list().errorMessage()).isEmpty();
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("a shorter selector list than ten slots is tolerated")
        void shortSelectorListTolerated() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, List.of("", ""), List.of(id(1), id(2)), TransactionListState.initial());
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
        }
    }

    // ---------------------------------------------------------------- search key

    @Nested
    @DisplayName("PROCESS-ENTER-KEY search key handling, :206-229")
    final class SearchKey {

        @Test
        @DisplayName("a sixteen-digit key is used verbatim as the browse anchor, :209-210")
        void numericSearchKeyUsed() {
            stubInclusive(ascending(11));
            enter(id(5));
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                    key.capture(), any());
            assertThat(key.getValue()).isEqualTo(id(5));
        }

        @Test
        @DisplayName("a blank key starts from low values, :206-207")
        void blankKeyStartsFromLowValues() {
            stubInclusive(ascending(11));
            enter("   ");
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                    key.capture(), any());
            assertThat(key.getValue()).isEmpty();
        }

        @Test
        @DisplayName("a non-numeric key raises the :214 literal, keeps the field and still runs STARTBR")
        void nonNumericSearchKey() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = enter("12345");
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC);
            assertThat(screen.list().transactionIdInput()).isEqualTo("12345");
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.state().pageNumber()).isZero();
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(),
                    any());
        }

        @Test
        @DisplayName("fifteen digits and embedded letters both fail the PIC X(16) IS NUMERIC test")
        void widthAndAlphabeticKeysRejected() {
            stubInclusive(ascending(11));
            assertThat(enter("000000000000001").errorFlagOn()).isTrue();
            assertThat(enter("00000000000000ab").errorFlagOn()).isTrue();
            assertThat(enter("00000000000000 1").errorFlagOn()).isTrue();
            assertThat(enter(id(5)).errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("an over-width search key is bounded to the X(16) map field, :559, not rejected")
        void overWidthSearchKeyIsBoundedToMapWidth() {
            stubInclusive(ascending(11));
            final TransactionListScreen accepted = enter("00000000000000001");
            assertThat(accepted.errorFlagOn()).isFalse();
            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                    key.capture(), any());
            assertThat(key.getValue()).isEqualTo("0000000000000000")
                    .hasSize(TransactionDto.TRANSACTION_ID_LENGTH);
        }

        @Test
        @DisplayName("an over-width key whose leading sixteen bytes are not digits still raises :214")
        void overWidthNonNumericKeyStillRejected() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = enter("0000000000000abcd");
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC);
            assertThat(screen.list().transactionIdInput()).isEqualTo("0000000000000abc");
        }

        @Test
        @DisplayName("an over-width selector and an over-width row id are bounded, never a crash")
        void overWidthRowFieldsAreBounded() {
            final List<String> wideSelectors = new ArrayList<>(selectors(1, "SS"));
            final List<String> wideIds = new ArrayList<>(displayedIds());
            wideIds.set(0, id(1) + "9");
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, wideSelectors, wideIds, TransactionListState.initial());
            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(1))
                    .hasSize(TransactionDto.TRANSACTION_ID_LENGTH);
            assertThat(screen.list().rows().get(0).selectionFlag()).isEqualTo("S")
                    .hasSize(TransactionDto.SELECTION_FLAG_LENGTH);
        }

        @Test
        @DisplayName("the page number is reset to zero on ENTER and reaches one via the forward page, :224")
        void pageNumberResetsOnEnter() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, List.of(), List.of(), new TransactionListState(id(1), id(10), 9, true));
            assertThat(screen.state().pageNumber()).isEqualTo(1);
            assertThat(screen.list().pageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the search field is blanked only when no error was raised, :227-229")
        void searchFieldBlankedOnlyOnSuccess() {
            stubInclusive(ascending(11));
            assertThat(enter(id(5)).list().transactionIdInput()).isEmpty();
            assertThat(enter("12345").list().transactionIdInput()).isEqualTo("12345");
        }
    }

    // ---------------------------------------------------------------- projections

    @Nested
    @DisplayName("POPULATE-TRAN-DATA projections, :381-445")
    final class Projections {

        @Test
        @DisplayName("the amount renders on the +99999999.99 mask - twelve characters, mandatory sign")
        void amountMask() {
            assertThat(single(new BigDecimal("123.45"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("+00000123.45").hasSize(12);
            assertThat(single(new BigDecimal("-9.5"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("-00000009.50");
            assertThat(single(BigDecimal.ZERO, "D", ORIG_TS).page().getRows().get(0).amount())
                    .isEqualTo("+00000000.00");
            assertThat(single(new BigDecimal("-0.01"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("-00000000.01");
        }

        @Test
        @DisplayName("Low: the eight-digit mask truncates the high-order digit of a nine-digit amount")
        void amountMaskTruncatesHighOrderDigit() {
            assertThat(single(new BigDecimal("123456789.99"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("+23456789.99");
            assertThat(single(new BigDecimal("-999999999.99"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("-99999999.99");
            assertThat(single(new BigDecimal("99999999.99"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).isEqualTo("+99999999.99");
        }

        @Test
        @DisplayName("a negative amount is never normalised to its absolute value")
        void negativeAmountsArePreserved() {
            assertThat(single(new BigDecimal("-250.00"), "D", ORIG_TS).page().getRows().get(0)
                    .amount()).startsWith("-");
        }

        @Test
        @DisplayName("the date projection is origTs[5,7) / origTs[8,10) / origTs[2,4), :384-388")
        void dateProjection() {
            assertThat(single(BigDecimal.ONE, "D", ORIG_TS).page().getRows().get(0)
                    .transactionDate()).isEqualTo("06/10/22").hasSize(8);
            assertThat(single(BigDecimal.ONE, "D", "1999-12-31 00:00:00.000000").page().getRows()
                    .get(0).transactionDate()).isEqualTo("12/31/99");
            assertThat(single(BigDecimal.ONE, "D", "2000-01-02 03:04:05.000006").page().getRows()
                    .get(0).transactionDate()).isEqualTo("01/02/00");
        }

        @Test
        @DisplayName("a blank or short timestamp yields the unset literal 00/00/00")
        void blankTimestampYieldsUnsetDate() {
            assertThat(single(BigDecimal.ONE, "D", BLANK_TS).page().getRows().get(0)
                    .transactionDate()).isEqualTo("00/00/00");
            assertThat(single(BigDecimal.ONE, "D", "2022-06").page().getRows().get(0)
                    .transactionDate()).isEqualTo("00/00/00");
            assertThat(single(BigDecimal.ONE, "D", "").page().getRows().get(0).transactionDate())
                    .isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("the row description truncates to twenty-six characters, never sixty, :395")
        void descriptionTruncatesToTwentySix() {
            final String projected = single(BigDecimal.ONE, "A".repeat(100), ORIG_TS).page()
                    .getRows().get(0).description();
            assertThat(projected).hasSize(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .isEqualTo("A".repeat(26));
            assertThat(single(BigDecimal.ONE, "B".repeat(26), ORIG_TS).page().getRows().get(0)
                    .description()).hasSize(26);
            assertThat(single(BigDecimal.ONE, "SHORT", ORIG_TS).page().getRows().get(0)
                    .description()).isEqualTo("SHORT");
        }

        @Test
        @DisplayName("the page number narrows 9(08) to an eight-character display field, :324")
        void pageNumberRendering() {
            stubInclusive(ascending(11));
            assertThat(service.openList().list().pageNumber()).isEqualTo("00000001").hasSize(8);
            setUp();
            stubExclusive(ascending(11));
            assertThat(service.submitScreen(AttentionIdentifier.PF8, null, List.of(), List.of(),
                    new TransactionListState(id(1), id(10), 41, true)).list().pageNumber())
                    .isEqualTo("00000042");
        }

        @Test
        @DisplayName("a page number past 9(08) truncates to its low eight digits, as a COBOL ADD does")
        void pageNumberOverflowTruncates() {
            stubExclusive(ascending(11));
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF8, null,
                    List.of(), List.of(),
                    new TransactionListState(id(1), id(10), 99_999_999, true));
            assertThat(screen.state().pageNumber()).isEqualTo(100_000_000);
            assertThat(screen.list().pageNumber()).isEqualTo("00000000")
                    .hasSize(TransactionDto.PAGE_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("the list projection leaves every detail-only field unset")
        void detailFieldsAreNotProjected() {
            stubInclusive(ascending(11));
            final TransactionDto list = service.openList().list();
            assertThat(list.transactionId()).isNull();
            assertThat(list.cardNumber()).isNull();
            assertThat(list.typeCode()).isNull();
            assertThat(list.categoryCode()).isNull();
            assertThat(list.source()).isNull();
            assertThat(list.description()).isNull();
            assertThat(list.amount()).isNull();
            assertThat(list.originatingDate()).isNull();
            assertThat(list.processingDate()).isNull();
            assertThat(list.merchantId()).isNull();
            assertThat(list.merchantName()).isNull();
            assertThat(list.merchantCity()).isNull();
            assertThat(list.merchantZip()).isNull();
            assertThat(list.amountValue()).isNull();
        }
    }

    // ---------------------------------------------------------------- security

    @Nested
    @DisplayName("Security - no card number escapes this service")
    final class Security {

        @Test
        @DisplayName("no card number reaches the projection, the metadata or any toString")
        void noCardNumberLeaks() {
            stubInclusive(ascending(11));
            final TransactionListScreen screen = service.openList();
            assertThat(screen.list().cardNumber()).isNull();
            assertThat(screen.toString()).doesNotContain(CARD);
            assertThat(screen.page().toString()).doesNotContain(CARD);
            assertThat(screen.list().toString()).doesNotContain(CARD);
            assertThat(screen.state().toString()).doesNotContain(CARD);
        }

        @Test
        @DisplayName("no card number reaches an exception message on the failure path")
        void noCardNumberInFailureMessage() {
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(new QueryTimeoutException("statement timed out"));
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.openList())
                    .withMessageNotContaining(CARD);
        }
    }

    // ---------------------------------------------------------------- failure routing

    @Nested
    @DisplayName("Browse failure routing through FileStatusMapper, :612-618, :646-652, :680-686")
    final class FailureRouting {

        @Test
        @DisplayName("a STARTBR infrastructure failure becomes a typed exception preserving the cause")
        void startbrFailure() {
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(cause);
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.openList())
                    .isInstanceOf(FileAccessException.class)
                    .withCause(cause)
                    .withMessageContaining("STARTBR")
                    .withMessageContaining("TRANSACT")
                    .withMessageContaining("90");
        }

        @Test
        @DisplayName("the message is the mapper's rendering verbatim - the service reformats nothing")
        void failureMessageComesFromTheSingleTranslationPoint() {
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(cause);
            final String expected = new FileStatusMapper()
                    .toException("90", "TRANSACT", "STARTBR", cause).orElseThrow().getMessage();
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.openList())
                    .withMessage(expected);
            assertThat(MSG_UNABLE).isEqualTo("Unable to lookup transaction...");
        }

        @Test
        @DisplayName("a PF8 exclusive-finder failure is routed identically")
        void pf8Failure() {
            final QueryTimeoutException cause = new QueryTimeoutException("timeout");
            when(repository.findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(cause);
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF8, null,
                            List.of(), List.of(), new TransactionListState(id(1), id(10), 1, true)))
                    .isInstanceOf(FileAccessException.class)
                    .withCause(cause);
        }

        @Test
        @DisplayName("a PF7 descending-finder failure is routed identically")
        void pf7Failure() {
            final QueryTimeoutException cause = new QueryTimeoutException("timeout");
            when(repository.findByTransactionIdLessThanOrderByTransactionIdDesc(any(), any()))
                    .thenThrow(cause);
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF7, null,
                            List.of(), List.of(), new TransactionListState(id(11), id(20), 2, true)))
                    .isInstanceOf(FileAccessException.class)
                    .withCause(cause);
        }

        @Test
        @DisplayName("a hole in an ascending browse window is a READNEXT failure, not an end of data")
        void nullRowInForwardWindow() {
            stubInclusive(Arrays.asList(tx(1), null));
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.openList())
                    .isInstanceOf(FileAccessException.class)
                    .withMessageContaining("READNEXT");
        }

        @Test
        @DisplayName("a hole in a descending browse window is a READPREV failure")
        void nullRowInBackwardWindow() {
            stubDescending(Arrays.asList(tx(9), null));
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF7, null,
                            List.of(), List.of(), new TransactionListState(id(11), id(20), 2, true)))
                    .isInstanceOf(FileAccessException.class)
                    .withMessageContaining("READPREV");
        }

        @Test
        @DisplayName("end of data is loop termination, never an exception")
        void endOfDataIsNotAnError() {
            stubInclusive(List.of());
            final TransactionListScreen screen = service.openList();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_AT_TOP);
        }
    }
}
