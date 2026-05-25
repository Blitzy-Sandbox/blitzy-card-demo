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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategory;
import com.awsm2.carddemo.domain.TransactionCategory.TransactionCategoryId;
import com.awsm2.carddemo.domain.TransactionType;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionCategoryRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.repository.TransactionTypeRepository;
import com.awsm2.carddemo.service.TransactionReportService.ReportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link TransactionReportService}.
 *
 * <p><b>COBOL provenance.</b> {@link TransactionReportService} translates
 * {@code app/cbl/CBTRN03C.cbl} (the transaction-detail-report variant
 * invoked by JCL job {@code app/jcl/TRANREPT.jcl}). The COBOL source:</p>
 * <ol>
 *   <li>Opens {@code TRANSACT-FILE}, {@code XREF-FILE},
 *       {@code TRANTYPE-FILE}, {@code TRANCATG-FILE},
 *       {@code REPORT-FILE}, and {@code DATE-PARMS-FILE}
 *       (L161-L166).</li>
 *   <li>Reads the date window from {@code DATE-PARMS-FILE} into
 *       {@code WS-START-DATE} / {@code WS-END-DATE}
 *       (L168, L220-L243).</li>
 *   <li>Reads {@code TRANSACT-FILE} sequentially and filters to
 *       transactions whose {@code TRAN-PROC-TS (1:10)} is in the
 *       <b>inclusive</b> range {@code [WS-START-DATE, WS-END-DATE]}
 *       (L173-L174).</li>
 *   <li>On every {@code TRAN-CARD-NUM} change emits an account-total
 *       line and re-reads the XREF entry for the new card
 *       ({@code 1500-A-LOOKUP-XREF}, L484-L492).</li>
 *   <li>Looks up the transaction-type description
 *       ({@code 1500-B-LOOKUP-TRANTYPE}, L494-L502) and the
 *       transaction-category description
 *       ({@code 1500-C-LOOKUP-TRANCATG}, L504-L512) for every
 *       transaction.</li>
 *   <li>Emits a fixed-width detail line per transaction
 *       ({@code 1120-WRITE-DETAIL}, L361-L374), a page-total line
 *       every {@code WS-PAGE-SIZE=20} lines
 *       ({@code 1110-WRITE-PAGE-TOTALS}, L293-L304), and a final
 *       grand-total line ({@code 1110-WRITE-GRAND-TOTALS},
 *       L318-L322).</li>
 * </ol>
 *
 * <p><b>Behavioural invariants locked by this suite</b> (per AAP
 * &sect;0.7.2 "Functionality that must be preserved exactly"):</p>
 * <ol>
 *   <li><b>Inclusive-both-ends date window</b> &mdash; matches COBOL
 *       L173-L174 ({@code &gt;=} and {@code &lt;=}). Transactions on
 *       the start date or end date are included.</li>
 *   <li><b>Sort by card then proc timestamp</b> &mdash; the
 *       {@code WS-CURR-CARD-NUM} card-change detector at L181 requires
 *       monotonic ordering for the control break to fire exactly once
 *       per card.</li>
 *   <li><b>Card-change subtotal line</b> &mdash; emitted by
 *       {@code 1120-WRITE-ACCOUNT-TOTALS} (L306-L316). The subtotal
 *       contains the cumulative {@code WS-ACCOUNT-TOTAL} for the
 *       <i>previous</i> card.</li>
 *   <li><b>Subtotal does NOT contribute to the grand total</b>
 *       &mdash; the COBOL source accumulates the grand total
 *       exclusively from page totals ({@code 1110-WRITE-PAGE-TOTALS}
 *       L297: {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL}); the
 *       account-total emission ZEROs {@code WS-ACCOUNT-TOTAL} but does
 *       not feed the grand total (would double-count).</li>
 *   <li><b>Lookup caching</b> &mdash; XREF, type, and category lookups
 *       are cached locally per report run. The repository's
 *       {@code findById} is invoked once per distinct key, matching
 *       the COBOL random-key VSAM read cost (1 disk seek per unique
 *       key).</li>
 *   <li><b>S3 output via the adapter</b> &mdash;
 *       {@link S3OutputService#writeReport(String, byte[])} is invoked
 *       once with the UTF-8 encoded report bytes; this replaces the
 *       COBOL {@code WRITE FD-REPTFILE-REC} (L343-L359). The reportId
 *       encodes the date range.</li>
 *   <li><b>PCI-DSS PAN masking</b> &mdash; report content never
 *       contains a full 16-digit PAN; only the last 4 digits prefixed
 *       with twelve asterisks (AAP &sect;0.6.6).</li>
 *   <li><b>BigDecimal + HALF_EVEN</b> &mdash; every monetary
 *       accumulator uses {@link java.math.RoundingMode#HALF_EVEN}
 *       (banker's rounding); zero {@code float} / {@code double}
 *       substitution (AAP &sect;0.6.1).</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; an accumulator exceeding the
 *       COBOL {@code PIC S9(09)V99} ceiling
 *       ({@code 999_999_999.99}) throws
 *       {@link OnSizeErrorException} per AAP &sect;0.7.1.</li>
 *   <li><b>Audit-log emission</b> &mdash;
 *       {@link AuditLogService#logAuditEvent(String, String, String,
 *       String, Map, String)} is invoked exactly once on successful
 *       upload with event type {@code REPORT_GENERATED} and a payload
 *       carrying the structured report metadata (AAP &sect;0.6.6).</li>
 *   <li><b>Input validation</b> &mdash; null start/end dates and
 *       inverted ranges throw {@link CardDemoException}.</li>
 * </ol>
 *
 * <p>External AWS interactions (S3, OpenSearch via AuditLogService)
 * are fully mocked through {@link MockitoExtension}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportService unit tests (COBOL: CBTRN03C.cbl)")
class TransactionReportServiceTest {

    // ==================================================================
    // Test constants — kept here for symbolic readability in tests
    // ==================================================================

    /**
     * Standard report-window start date used by most tests. Chosen to
     * fall inside a single, clearly-bounded calendar month so the
     * inclusive-on-both-ends semantics are unambiguous.
     */
    private static final LocalDate START_DATE = LocalDate.of(2024, 1, 1);

    /**
     * Standard report-window end date matching {@link #START_DATE}'s
     * month so transactions on either boundary land cleanly inside
     * the window.
     */
    private static final LocalDate END_DATE = LocalDate.of(2024, 1, 31);

    /**
     * Canonical card number A used as the lowest-sorted PAN in tests
     * that exercise the sort-by-card invariant. Padded to the
     * 16-character COBOL {@code TRAN-CARD-NUM} width.
     */
    private static final String CARD_A = "4111111111111111";

    /**
     * Canonical card number B sorted lexicographically after
     * {@link #CARD_A}. The two cards together drive the control-break
     * subtotal assertions.
     */
    private static final String CARD_B = "4222222222222222";

    /**
     * Canonical card number C sorted lexicographically after
     * {@link #CARD_B}, used in the 3-card subtotal test.
     */
    private static final String CARD_C = "4333333333333333";

    /**
     * COBOL: {@code TRAN-TYPE-CD PIC X(02)} — a 2-character
     * transaction-type code such as {@code "01"} (Purchase),
     * {@code "02"} (Payment), etc.
     */
    private static final String TYPE_PURCHASE = "01";

    /**
     * Second distinct type code used to verify the cache deduplicates
     * lookups by type per AAP &sect;0.4.1 ("repository caching").
     */
    private static final String TYPE_PAYMENT = "02";

    /**
     * COBOL: {@code TRAN-CAT-CD PIC 9(04)} — a 4-digit numeric
     * transaction-category code. The Java domain uses {@link Integer}
     * per the V009 schema.
     */
    private static final Integer CAT_RETAIL = 5;

    /**
     * Second distinct category code used to verify the cache
     * deduplicates category lookups by composite key.
     */
    private static final Integer CAT_GROCERY = 12;

    // ==================================================================
    // Mocks and SUT — declared at class level so all @Nested groups
    // share the same @InjectMocks-managed dependency graph.
    // ==================================================================

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private S3OutputService s3OutputService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TransactionReportService service;

    // ==================================================================
    // Test fixtures and helper builders
    // ==================================================================

    @BeforeEach
    void setUp() {
        // Mockito's strict-stubs mode enforced by MockitoExtension
        // requires that every when(...) stub be exercised by the test
        // under inspection. The lenient() helpers below would relax
        // that — used sparingly only when a stub is shared across many
        // test methods.
        //
        // No global stubbing is performed here; each test method
        // declares only the stubs it needs.
    }

    /**
     * Constructs a fresh {@link Transaction} fixture with the supplied
     * primary fields. All COBOL {@code FILLER} and merchant-side
     * columns are populated with deterministic defaults so equality
     * comparisons in the test assertions remain stable.
     *
     * @param tranId   the 16-character COBOL {@code TRAN-ID} (primary
     *                 key); padded if shorter
     * @param cardNum  the 16-character COBOL {@code TRAN-CARD-NUM}
     * @param typeCd   the 2-character COBOL {@code TRAN-TYPE-CD}
     * @param catCd    the 4-digit COBOL {@code TRAN-CAT-CD}
     * @param amount   the COBOL {@code TRAN-AMT PIC S9(09)V99} value
     * @param procTs   the COBOL {@code TRAN-PROC-TS} timestamp
     * @return the populated {@link Transaction} fixture
     */
    private Transaction buildTransaction(String tranId,
                                         String cardNum,
                                         String typeCd,
                                         Integer catCd,
                                         BigDecimal amount,
                                         LocalDateTime procTs) {
        return new Transaction(
                padId(tranId),
                typeCd,
                catCd,
                "POS_TERM",
                "Test transaction " + tranId,
                amount,
                999_999_999L,
                "Test Merchant",
                "Test City",
                "12345",
                cardNum,
                procTs,
                procTs);
    }

    /**
     * Pads or truncates a tran-ID candidate to exactly 16 characters
     * so that the rendered detail-line layout is stable across tests.
     *
     * @param raw the raw tran-ID candidate
     * @return a 16-character right-padded tran-ID
     */
    private static String padId(String raw) {
        if (raw == null) {
            return "                ";
        }
        if (raw.length() >= 16) {
            return raw.substring(0, 16);
        }
        StringBuilder sb = new StringBuilder(raw);
        while (sb.length() < 16) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Constructs a fresh {@link TransactionType} fixture.
     *
     * @param code the 2-character type code
     * @param desc the human-readable description
     * @return a populated {@link TransactionType}
     */
    private static TransactionType buildType(String code, String desc) {
        return new TransactionType(code, desc);
    }

    /**
     * Constructs a fresh {@link TransactionCategory} fixture with a
     * composite key.
     *
     * @param typeCd the type-code component of the composite key
     * @param catCd  the category-code component of the composite key
     * @param desc   the human-readable description
     * @return a populated {@link TransactionCategory}
     */
    private static TransactionCategory buildCategory(String typeCd, Integer catCd,
                                                     String desc) {
        return new TransactionCategory(new TransactionCategoryId(typeCd, catCd), desc);
    }

    /**
     * Constructs a fresh {@link CardCrossReference} fixture.
     *
     * @param cardNum the 16-character card number
     * @param acctId  the resolved owning account ID
     * @return a populated {@link CardCrossReference}
     */
    private static CardCrossReference buildXref(String cardNum, Long acctId) {
        return new CardCrossReference(cardNum, 12345L, acctId);
    }

    /**
     * Lenient happy-path stubbing that yields non-null lookup
     * responses for every type/category/xref the test fixtures might
     * reference. Used by tests that don't focus on the lookup
     * behaviour itself.
     */
    private void stubLenientLookupResponses() {
        lenient().when(transactionTypeRepository.findById(TYPE_PURCHASE))
                .thenReturn(Optional.of(buildType(TYPE_PURCHASE, "Purchase")));
        lenient().when(transactionTypeRepository.findById(TYPE_PAYMENT))
                .thenReturn(Optional.of(buildType(TYPE_PAYMENT, "Payment")));
        lenient().when(transactionCategoryRepository.findById(
                        new TransactionCategoryId(TYPE_PURCHASE, CAT_RETAIL)))
                .thenReturn(Optional.of(buildCategory(TYPE_PURCHASE, CAT_RETAIL, "Retail")));
        lenient().when(transactionCategoryRepository.findById(
                        new TransactionCategoryId(TYPE_PURCHASE, CAT_GROCERY)))
                .thenReturn(Optional.of(buildCategory(TYPE_PURCHASE, CAT_GROCERY, "Grocery")));
        lenient().when(transactionCategoryRepository.findById(
                        new TransactionCategoryId(TYPE_PAYMENT, CAT_RETAIL)))
                .thenReturn(Optional.of(buildCategory(TYPE_PAYMENT, CAT_RETAIL, "Refund")));
        lenient().when(cardCrossReferenceRepository.findById(CARD_A))
                .thenReturn(Optional.of(buildXref(CARD_A, 100_000_000_001L)));
        lenient().when(cardCrossReferenceRepository.findById(CARD_B))
                .thenReturn(Optional.of(buildXref(CARD_B, 100_000_000_002L)));
        lenient().when(cardCrossReferenceRepository.findById(CARD_C))
                .thenReturn(Optional.of(buildXref(CARD_C, 100_000_000_003L)));
    }

    /**
     * Captures the byte[] payload passed to
     * {@link S3OutputService#writeReport(String, byte[])} and decodes
     * it as a UTF-8 string. Tests use this to assert on the rendered
     * report text content (detail lines, subtotals, grand total, PAN
     * masking).
     *
     * @return the captured payload decoded as a UTF-8 string
     */
    private String captureReportText() {
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3OutputService).writeReport(anyString(), bytesCaptor.capture());
        return new String(bytesCaptor.getValue(), StandardCharsets.UTF_8);
    }

    /**
     * Captures the {@code reportId} passed to
     * {@link S3OutputService#writeReport(String, byte[])}. Tests use
     * this to assert that the start and end dates appear in the
     * report identifier.
     *
     * @return the captured reportId string
     */
    private String captureReportId() {
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3OutputService).writeReport(idCaptor.capture(), any(byte[].class));
        return idCaptor.getValue();
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    /**
     * Input-validation tests covering the COBOL date-parameter
     * preconditions ({@code WS-START-DATE} non-blank, {@code WS-END-DATE}
     * non-blank, start &lt;= end). The COBOL source enforces these
     * implicitly via record layouts; the Java target makes the
     * preconditions explicit and surfaces a typed exception.
     */
    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("null startDate throws CardDemoException")
        void runReport_nullStartDate_throwsCardDemoException() {
            // No repository stubbing — validation must fail before
            // any side effects occur.
            assertThatThrownBy(() -> service.generateReport(null, END_DATE))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("startDate");
            verifyNoInteractions(transactionRepository, transactionTypeRepository,
                    transactionCategoryRepository, cardCrossReferenceRepository,
                    s3OutputService, auditLogService);
        }

        @Test
        @DisplayName("null endDate throws CardDemoException")
        void runReport_nullEndDate_throwsCardDemoException() {
            assertThatThrownBy(() -> service.generateReport(START_DATE, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("endDate");
            verifyNoInteractions(transactionRepository, transactionTypeRepository,
                    transactionCategoryRepository, cardCrossReferenceRepository,
                    s3OutputService, auditLogService);
        }

        @Test
        @DisplayName("startDate after endDate throws CardDemoException")
        void runReport_inverseRange_throwsCardDemoException() {
            LocalDate later = LocalDate.of(2024, 2, 1);
            assertThatThrownBy(() -> service.generateReport(later, START_DATE))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("startDate");
            verifyNoInteractions(transactionRepository, transactionTypeRepository,
                    transactionCategoryRepository, cardCrossReferenceRepository,
                    s3OutputService, auditLogService);
        }
    }

    /**
     * Date-window filter tests covering the COBOL inclusion test at
     * CBTRN03C L173-L174:
     * <pre>{@code
     *   IF TRAN-PROC-TS (1:10) >= WS-START-DATE
     *      AND TRAN-PROC-TS (1:10) <= WS-END-DATE
     *      CONTINUE
     *   ELSE
     *      NEXT SENTENCE
     *   END-IF
     * }</pre>
     * <p>Boundary semantics: inclusive on BOTH ends. A transaction
     * whose {@code TRAN-PROC-TS} substring 1..10 equals
     * {@code WS-START-DATE} or {@code WS-END-DATE} is included.</p>
     */
    @Nested
    @DisplayName("DateWindowFilter — inclusive on both ends (CBTRN03C L173-L174)")
    class DateWindowFilter {

        @Test
        @DisplayName("transactions inside the window are included")
        void runReport_inWindow_includedInReport() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 12, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isEqualTo(1);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("transaction on startDate boundary is INCLUDED (>= semantic)")
        void runReport_onStartDate_included() {
            stubLenientLookupResponses();
            // tranProcTs at midnight on startDate — must be included
            // because COBOL L173 is ">=".
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("50.00"),
                    LocalDateTime.of(2024, 1, 1, 0, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isEqualTo(1);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("transaction on endDate boundary is INCLUDED (<= semantic)")
        void runReport_onEndDate_included() {
            stubLenientLookupResponses();
            // tranProcTs late on endDate — must be included because
            // COBOL L174 is "<=" against the date prefix.
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("75.00"),
                    LocalDateTime.of(2024, 1, 31, 23, 59));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isEqualTo(1);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("75.00"));
        }

        @Test
        @DisplayName("transaction one day BEFORE startDate is EXCLUDED")
        void runReport_beforeStartDate_excluded() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2023, 12, 31, 23, 59));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("transaction one day AFTER endDate is EXCLUDED")
        void runReport_afterEndDate_excluded() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 2, 1, 0, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("transaction with null tranProcTs is silently excluded")
        void runReport_nullProcTs_excluded() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"), null);
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isZero();
        }

        @Test
        @DisplayName("mixed window: only in-window txns counted")
        void runReport_mixedWindow_onlyInWindowCounted() {
            stubLenientLookupResponses();
            List<Transaction> txs = new ArrayList<>();
            txs.add(buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("10.00"),
                    LocalDateTime.of(2023, 12, 31, 12, 0))); // before
            txs.add(buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("20.00"),
                    LocalDateTime.of(2024, 1, 15, 12, 0))); // IN
            txs.add(buildTransaction("T3", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("30.00"),
                    LocalDateTime.of(2024, 2, 1, 12, 0))); // after
            txs.add(buildTransaction("T4", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("40.00"),
                    LocalDateTime.of(2024, 1, 1, 0, 0))); // IN (boundary)
            txs.add(buildTransaction("T5", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("50.00"),
                    LocalDateTime.of(2024, 1, 31, 23, 59))); // IN (boundary)
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            // Only T2, T4, T5 land inside the window.
            assertThat(result.transactionCount()).isEqualTo(3);
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("110.00")); // 20 + 40 + 50
        }

        @Test
        @DisplayName("empty in-window result still writes a report with grand total 0.00")
        void runReport_emptyInWindow_writesReportWithZeroGrandTotal() {
            // No lookup stubbing required — no transaction means no
            // type/category/xref lookups are performed.
            when(transactionRepository.findAll()).thenReturn(new ArrayList<>());

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isZero();
            assertThat(result.pageCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo("0.00");
            assertThat(result.s3Key()).isNotBlank();

            // S3 upload still happens (report-text contains the header
            // + grand-total line per the service's well-formed-report
            // semantic).
            String text = captureReportText();
            assertThat(text)
                    .contains("TRANSACTION DETAIL REPORT")
                    .contains("GRAND TOTAL");
        }
    }

    /**
     * Sort-order tests covering the COBOL implicit assumption that
     * {@code WS-CURR-CARD-NUM} sees a monotonically-changing sequence
     * (the input file is sorted on the AIX
     * {@code TRANSACT.VSAM.AIX KEYS(26 304)}). The Java target sorts
     * explicitly via {@link java.util.Comparator}.
     */
    @Nested
    @DisplayName("Sorting — by card then proc timestamp")
    class Sorting {

        @Test
        @DisplayName("detail lines sorted by tranCardNum ascending")
        void runReport_sortsByCardNum_ascending() {
            stubLenientLookupResponses();
            // Insert in REVERSE alphabetical order; expect the
            // resulting text to render CARD_A's masked PAN before
            // CARD_B's.
            List<Transaction> txs = List.of(
                    buildTransaction("T2", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.00"),
                            LocalDateTime.of(2024, 1, 10, 12, 0)),
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("200.00"),
                            LocalDateTime.of(2024, 1, 10, 12, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            int posA = text.indexOf("************" + CARD_A.substring(CARD_A.length() - 4));
            int posB = text.indexOf("************" + CARD_B.substring(CARD_B.length() - 4));
            // Both masked PANs must appear, A before B.
            assertThat(posA).isGreaterThanOrEqualTo(0);
            assertThat(posB).isGreaterThan(posA);
        }

        @Test
        @DisplayName("within same card, sorted by tranProcTs ascending")
        void runReport_sortsByProcTs_withinCard() {
            stubLenientLookupResponses();
            // Insert in REVERSE chronological order; expect earlier
            // tran ("T1") to render before later ("T2") in the report.
            Transaction tEarlier = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("10.00"),
                    LocalDateTime.of(2024, 1, 5, 8, 0));
            Transaction tLater = buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("20.00"),
                    LocalDateTime.of(2024, 1, 20, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tLater, tEarlier));

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            int posEarlier = text.indexOf("T1");
            int posLater = text.indexOf("T2");
            assertThat(posEarlier).isGreaterThanOrEqualTo(0);
            assertThat(posLater).isGreaterThan(posEarlier);
        }
    }

    /**
     * Card-change subtotal tests covering the COBOL control-break
     * logic at L181-L188 of CBTRN03C:
     * <pre>{@code
     *   IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM
     *     IF WS-FIRST-TIME = 'N'
     *       PERFORM 1120-WRITE-ACCOUNT-TOTALS
     *     END-IF
     *     MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
     *     MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM
     *     PERFORM 1500-A-LOOKUP-XREF
     *   END-IF
     * }</pre>
     *
     * <p>The {@code WS-FIRST-TIME='Y'} guard suppresses the
     * account-total emission on the very first iteration; the
     * end-of-loop emission writes the final card's total. Net effect:
     * N distinct cards yield N account-total lines.</p>
     */
    @Nested
    @DisplayName("CardChangeSubtotal — control-break logic (CBTRN03C L181-L188)")
    class CardChangeSubtotal {

        @Test
        @DisplayName("single card 3 transactions emits exactly 1 subtotal line")
        void runReport_singleCard_oneSubtotalLine() {
            stubLenientLookupResponses();
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 10, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 11, 9, 0)),
                    buildTransaction("T3", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("30.00"),
                            LocalDateTime.of(2024, 1, 12, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            long subtotalCount = countOccurrences(text, "Total for card");
            assertThat(subtotalCount).isEqualTo(1);
        }

        @Test
        @DisplayName("three distinct cards emit exactly 3 subtotal lines")
        void runReport_threeCards_threeSubtotalLines() {
            stubLenientLookupResponses();
            List<Transaction> txs = List.of(
                    // Card A: 2 txns
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    // Card B: 3 txns
                    buildTransaction("T3", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("30.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)),
                    buildTransaction("T4", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("40.00"),
                            LocalDateTime.of(2024, 1, 8, 9, 0)),
                    buildTransaction("T5", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("50.00"),
                            LocalDateTime.of(2024, 1, 9, 9, 0)),
                    // Card C: 1 txn
                    buildTransaction("T6", CARD_C, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("60.00"),
                            LocalDateTime.of(2024, 1, 10, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            long subtotalCount = countOccurrences(text, "Total for card");
            assertThat(subtotalCount).isEqualTo(3);
        }

        @Test
        @DisplayName("subtotal amount sums all transactions for that card")
        void runReport_subtotalAmount_sumsCardTransactions() {
            stubLenientLookupResponses();
            // Card A txns: 100.00 + 200.00 -> subtotal 300.00
            // Card B txns: 50.00 + 75.00 -> subtotal 125.00
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("200.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    buildTransaction("T3", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("50.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)),
                    buildTransaction("T4", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("75.00"),
                            LocalDateTime.of(2024, 1, 8, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            // 300.00 appears in the subtotal for card A; 125.00 for B.
            // Both totals must be present in the rendered text.
            assertThat(text).contains("300.00");
            assertThat(text).contains("125.00");
        }

        @Test
        @DisplayName("grand total sums ALL in-window transaction amounts")
        void runReport_grandTotal_sumsAllAmounts() {
            stubLenientLookupResponses();
            // Card A: 100 + 200 = 300
            // Card B: 200 + 250 = 450
            // Grand total: 750.00
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("200.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    buildTransaction("T3", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("200.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)),
                    buildTransaction("T4", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("250.00"),
                            LocalDateTime.of(2024, 1, 8, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            // ReportResult.grandTotal is the authoritative numeric value.
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("750.00"));

            // The rendered text contains a GRAND TOTAL line with the value.
            String text = captureReportText();
            assertThat(text).contains("GRAND TOTAL");
            assertThat(text).contains("750.00");
        }

        @Test
        @DisplayName("subtotal line for previous card precedes detail line of next card")
        void runReport_subtotalBeforeNextCardDetail_orderedCorrectly() {
            stubLenientLookupResponses();
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            // The first subtotal line ("Total for card ...") for CARD_A
            // must appear before any detail line for CARD_B (the
            // service emits the previous-card subtotal at the card
            // boundary).
            int firstSubtotal = text.indexOf("Total for card");
            int cardBDetail = text.indexOf("************" + CARD_B.substring(CARD_B.length() - 4));
            assertThat(firstSubtotal).isGreaterThanOrEqualTo(0);
            assertThat(cardBDetail).isGreaterThan(firstSubtotal);
        }
    }

    /**
     * TransactionType and TransactionCategory lookup tests covering
     * the COBOL paragraphs {@code 1500-B-LOOKUP-TRANTYPE} (L494-L502)
     * and {@code 1500-C-LOOKUP-TRANCATG} (L504-L512). Both lookups are
     * cached locally per report run, so multiple transactions sharing
     * the same key incur exactly one repository call.
     */
    @Nested
    @DisplayName("TransactionTypeAndCategoryLookup — joins with type/category tables")
    class TransactionTypeAndCategoryLookup {

        @Test
        @DisplayName("typeRepository.findById invoked for each distinct type code")
        void runReport_lookupsType_perDistinctCode() {
            stubLenientLookupResponses();
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PAYMENT, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            verify(transactionTypeRepository).findById(TYPE_PURCHASE);
            verify(transactionTypeRepository).findById(TYPE_PAYMENT);
        }

        @Test
        @DisplayName("typeRepository.findById invoked ONCE per distinct type (caching)")
        void runReport_lookupsType_cachedPerDistinctCode() {
            stubLenientLookupResponses();
            // 3 transactions, all type 01 — expect exactly 1 lookup.
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    buildTransaction("T3", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("30.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            verify(transactionTypeRepository, times(1)).findById(TYPE_PURCHASE);
        }

        @Test
        @DisplayName("categoryRepository.findById invoked with composite key (typeCd, catCd)")
        void runReport_lookupsCategory_withCompositeKey() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            ArgumentCaptor<TransactionCategoryId> idCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryId.class);
            verify(transactionCategoryRepository).findById(idCaptor.capture());
            TransactionCategoryId captured = idCaptor.getValue();
            assertThat(captured.getTranTypeCd()).isEqualTo(TYPE_PURCHASE);
            assertThat(captured.getTranCatCd()).isEqualTo(CAT_RETAIL);
        }

        @Test
        @DisplayName("categoryRepository.findById invoked ONCE per distinct (type,cat) (caching)")
        void runReport_lookupsCategory_cachedPerCompositeKey() {
            stubLenientLookupResponses();
            // 3 transactions, all type 01 + cat 5 — expect 1 lookup.
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    buildTransaction("T3", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("30.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            verify(transactionCategoryRepository, times(1)).findById(
                    new TransactionCategoryId(TYPE_PURCHASE, CAT_RETAIL));
        }

        @Test
        @DisplayName("type description appears in rendered report")
        void runReport_typeDescription_appearsInReport() {
            // Stub a custom type description and verify it surfaces in
            // the report text.
            when(transactionTypeRepository.findById(TYPE_PURCHASE))
                    .thenReturn(Optional.of(buildType(TYPE_PURCHASE, "PurchaseDesc")));
            when(transactionCategoryRepository.findById(
                    new TransactionCategoryId(TYPE_PURCHASE, CAT_RETAIL)))
                    .thenReturn(Optional.of(buildCategory(TYPE_PURCHASE, CAT_RETAIL, "Retail")));
            lenient().when(cardCrossReferenceRepository.findById(CARD_A))
                    .thenReturn(Optional.of(buildXref(CARD_A, 100_000_000_001L)));

            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            // The picked description (type description preferred) is
            // "PurchaseDesc" per the service's pickDescription helper.
            assertThat(text).contains("PurchaseDesc");
        }
    }

    /**
     * CardCrossReference lookup tests covering CBTRN03C
     * {@code 1500-A-LOOKUP-XREF} (L484-L492). The Java service caches
     * the lookup result per distinct card so a single card with N
     * transactions incurs ONE {@code findById} call. The COBOL source
     * issues the XREF lookup only on card change, but the Java
     * service's local-cache pattern achieves identical observable
     * behaviour at the repository boundary.
     */
    @Nested
    @DisplayName("CardCrossReference lookup — XREF caching per card")
    class CardCrossReferenceLookup {

        @Test
        @DisplayName("xrefRepository.findById invoked once per distinct card")
        void runReport_xrefLookup_oncePerDistinctCard() {
            stubLenientLookupResponses();
            // 4 transactions: 3 on card A, 1 on card B. Expect 1
            // findById per card.
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("10.00"),
                            LocalDateTime.of(2024, 1, 5, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("20.00"),
                            LocalDateTime.of(2024, 1, 6, 9, 0)),
                    buildTransaction("T3", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("30.00"),
                            LocalDateTime.of(2024, 1, 7, 9, 0)),
                    buildTransaction("T4", CARD_B, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("40.00"),
                            LocalDateTime.of(2024, 1, 8, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            service.generateReport(START_DATE, END_DATE);

            verify(cardCrossReferenceRepository, times(1)).findById(CARD_A);
            verify(cardCrossReferenceRepository, times(1)).findById(CARD_B);
        }

        @Test
        @DisplayName("missing XREF entry: service continues, does not abend")
        void runReport_missingXref_continuesGracefully() {
            // Type and category lookups stubbed to return values so
            // the rendered detail line is well-formed even without
            // an XREF entry.
            when(transactionTypeRepository.findById(TYPE_PURCHASE))
                    .thenReturn(Optional.of(buildType(TYPE_PURCHASE, "Purchase")));
            when(transactionCategoryRepository.findById(
                    new TransactionCategoryId(TYPE_PURCHASE, CAT_RETAIL)))
                    .thenReturn(Optional.of(buildCategory(TYPE_PURCHASE, CAT_RETAIL, "Retail")));
            // XREF returns Optional.empty — service should continue
            // (Java target degrades gracefully where COBOL would
            // ABEND).
            when(cardCrossReferenceRepository.findById(CARD_A))
                    .thenReturn(Optional.empty());

            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isEqualTo(1);
            verify(cardCrossReferenceRepository).findById(CARD_A);
        }
    }

    /**
     * S3 output tests covering the COBOL paragraph
     * {@code 1111-WRITE-REPORT-REC} (L343-L359). The Java target
     * replaces the COBOL {@code WRITE FD-REPTFILE-REC} with a single
     * {@link S3OutputService#writeReport(String, byte[])} call that
     * carries the entire assembled report as a UTF-8 encoded byte
     * array.
     */
    @Nested
    @DisplayName("S3Output — adapter invocation and PAN masking")
    class S3Output {

        @Test
        @DisplayName("writeReport invoked exactly once on success")
        void runReport_invokesS3WriteOnce() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            verify(s3OutputService, times(1))
                    .writeReport(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("reportId contains both startDate and endDate")
        void runReport_reportId_containsBothDates() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            String reportId = captureReportId();
            assertThat(reportId)
                    .contains(START_DATE.toString())
                    .contains(END_DATE.toString());
        }

        @Test
        @DisplayName("report content masks PAN — last 4 digits only")
        void runReport_panMasking_lastFourOnly() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            // Full 16-digit PAN must NEVER appear in the report
            // content (PCI-DSS — AAP §0.6.6).
            assertThat(text).doesNotContain(CARD_A);
            // Mask must be present, ending with the last 4 digits.
            String masked = "************" + CARD_A.substring(CARD_A.length() - 4);
            assertThat(text).contains(masked);
        }

        @Test
        @DisplayName("ReportResult.s3Key matches tranrept/<yyyy>/<MM>/<dd>/<reportId>.rpt pattern")
        void runReport_s3Key_followsTranreptPrefix() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.s3Key())
                    .startsWith("tranrept/")
                    .endsWith(".rpt");
        }

        @Test
        @DisplayName("report content contains header banner and grand-total line")
        void runReport_content_containsHeaderAndGrandTotal() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            String text = captureReportText();
            assertThat(text)
                    .contains("TRANSACTION DETAIL REPORT")
                    .contains("Date Range")
                    .contains("GRAND TOTAL");
        }

        @Test
        @DisplayName("UTF-8 bytes round-trip the rendered text faithfully")
        void runReport_bytePayload_isUtf8Encoded() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService).writeReport(anyString(), bytesCaptor.capture());
            byte[] payload = bytesCaptor.getValue();
            // Non-empty payload; decodes cleanly as UTF-8.
            assertThat(payload).isNotEmpty();
            String roundTrip = new String(payload, StandardCharsets.UTF_8);
            assertThat(roundTrip).contains("TRANSACTION DETAIL REPORT");
        }
    }

    /**
     * Pagination tests verifying the COBOL paragraph
     * {@code 1110-WRITE-PAGE-TOTALS} (L293-L304) and the
     * page-size boundary detection at L282
     * ({@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)}). The
     * Java target's PAGE_SIZE constant equals the COBOL
     * {@code WS-PAGE-SIZE VALUE 20}.
     */
    @Nested
    @DisplayName("Pagination — multi-page reports and page totals")
    class Pagination {

        @Test
        @DisplayName("single transaction yields pageCount == 1")
        void runReport_singleTransaction_pageCountOne() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("10.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.pageCount()).isEqualTo(1);
            assertThat(result.transactionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("more than PAGE_SIZE transactions emits multiple page-total lines")
        void runReport_largeResultSet_multiplePages() {
            stubLenientLookupResponses();
            // 25 transactions, all for the same card (avoids
            // confounding card-change side effects).
            List<Transaction> txs = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                String tranId = String.format("T%02d", i);
                txs.add(buildTransaction(tranId, CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                        new BigDecimal("1.00"),
                        LocalDateTime.of(2024, 1, 10, 9, 0).plusMinutes(i)));
            }
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.transactionCount()).isEqualTo(25);
            // Greater than 1 page because >20 detail lines on a
            // single card forces a page break at 20.
            assertThat(result.pageCount()).isGreaterThan(1);

            String text = captureReportText();
            // At least one page-total line emitted plus a GRAND TOTAL.
            long pageTotalCount = countOccurrences(text, "Page ");
            // Multiple "Page" mentions because each header has "Page"
            // and each page total contains "Page". Use a coarse
            // assertion to confirm pagination occurred.
            assertThat(pageTotalCount).isGreaterThan(1);
            assertThat(text).contains("GRAND TOTAL");
        }

        @Test
        @DisplayName("grand total equals the sum across all pages")
        void runReport_largeResultSet_grandTotalEqualsSum() {
            stubLenientLookupResponses();
            // 30 transactions of 5.00 each = 150.00
            List<Transaction> txs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String tranId = String.format("T%02d", i);
                txs.add(buildTransaction(tranId, CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                        new BigDecimal("5.00"),
                        LocalDateTime.of(2024, 1, 10, 9, 0).plusMinutes(i)));
            }
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("150.00"));
        }
    }

    /**
     * BigDecimal arithmetic tests covering AAP &sect;0.6.1
     * (decimal-precision parity with COBOL {@code PIC S9(09)V99}). All
     * monetary accumulation uses {@link java.math.BigDecimal} with
     * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding) at
     * scale 2 &mdash; never {@code double} / {@code float}.
     */
    @Nested
    @DisplayName("BigDecimalArithmetic — HALF_EVEN + scale 2 (AAP §0.6.1)")
    class BigDecimalArithmetic {

        @Test
        @DisplayName("grand total preserves scale=2 and HALF_EVEN rounding")
        void runReport_grandTotal_scaleTwoHalfEven() {
            stubLenientLookupResponses();
            // Two amounts that sum cleanly to a 2-place scale value.
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.55"),
                            LocalDateTime.of(2024, 1, 10, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("200.55"),
                            LocalDateTime.of(2024, 1, 11, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            // CRITICAL: use isEqualByComparingTo, not isEqualTo —
            // BigDecimal.equals considers scale (e.g.,
            // new BigDecimal("301.10").equals(new BigDecimal("301.1"))
            // is false).
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("301.10"));
            // The result is also at scale 2 specifically.
            assertThat(result.grandTotal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("HALF_EVEN normalises 0.125 to 0.12 (round to even-down)")
        void runReport_halfEven_roundsToEvenDown() {
            stubLenientLookupResponses();
            // 0.125 -> 0.12 per HALF_EVEN (last digit before round
            // is 2, which is even, so we round down).
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("0.125"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("0.12"));
        }

        @Test
        @DisplayName("HALF_EVEN normalises 0.135 to 0.14 (round to even-up)")
        void runReport_halfEven_roundsToEvenUp() {
            stubLenientLookupResponses();
            // 0.135 -> 0.14 per HALF_EVEN (last digit before round
            // is 3, which is odd, so we round up to 4 — even).
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("0.135"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("0.14"));
        }

        @Test
        @DisplayName("grand total ceiling: amounts summing past PIC S9(09)V99 throw OnSizeErrorException")
        void runReport_overflow_throwsOnSizeErrorException() {
            stubLenientLookupResponses();
            // Two amounts whose sum exceeds 999_999_999.99 — the
            // COBOL PIC S9(09)V99 ceiling. Should trip the
            // safeAdd helper.
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("600000000.00"),
                            LocalDateTime.of(2024, 1, 10, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("600000000.00"),
                            LocalDateTime.of(2024, 1, 11, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            assertThatThrownBy(() -> service.generateReport(START_DATE, END_DATE))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("ON SIZE ERROR");
        }

        @Test
        @DisplayName("null tranAmt is treated as 0.00 (COBOL PIC S9(09)V99 default semantic)")
        void runReport_nullTranAmt_treatedAsZero() {
            stubLenientLookupResponses();
            Transaction txNullAmt = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    null,
                    LocalDateTime.of(2024, 1, 10, 9, 0));
            Transaction txWithAmt = buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("50.00"),
                    LocalDateTime.of(2024, 1, 11, 9, 0));
            when(transactionRepository.findAll())
                    .thenReturn(List.of(txNullAmt, txWithAmt));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            // Null amount contributes 0.00; total = 50.00.
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("3-decimal input amounts are normalised to scale 2 per HALF_EVEN")
        void runReport_threeDecimalInput_normalisedScaleTwo() {
            stubLenientLookupResponses();
            // 100.005 -> 100.00 (last digit before round is 0, even)
            // 100.015 -> 100.02 (last digit before round is 1, odd)
            // Sum: 100.00 + 100.02 = 200.02
            List<Transaction> txs = List.of(
                    buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.005"),
                            LocalDateTime.of(2024, 1, 10, 9, 0)),
                    buildTransaction("T2", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                            new BigDecimal("100.015"),
                            LocalDateTime.of(2024, 1, 11, 9, 0)));
            when(transactionRepository.findAll()).thenReturn(txs);

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("200.02"));
        }
    }

    /**
     * Audit-log emission tests covering AAP &sect;0.6.6
     * ("immutable audit trail via AWS CloudTrail and Amazon
     * OpenSearch"). The Java service emits exactly one
     * {@code REPORT_GENERATED} event per successful run with a
     * structured payload (start/end date, transaction count, page
     * count, grand total, S3 key).
     */
    @Nested
    @DisplayName("Audit log — REPORT_GENERATED emission (AAP §0.6.6)")
    class AuditLog {

        @Test
        @DisplayName("logAuditEvent invoked once with REPORT_GENERATED type")
        void runReport_emitsAuditEvent_once() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            verify(auditLogService, times(1)).logAuditEvent(
                    eq("REPORT_GENERATED"),
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    any());
        }

        @Test
        @DisplayName("audit payload carries transactionCount, pageCount, grandTotal, s3Key")
        void runReport_auditPayload_carriesStructuredFields() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            ReportResult result = service.generateReport(START_DATE, END_DATE);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    payloadCaptor.capture(),
                    any());
            Map<String, Object> payload = payloadCaptor.getValue();

            assertThat(payload).containsEntry("startDate", START_DATE);
            assertThat(payload).containsEntry("endDate", END_DATE);
            assertThat(payload).containsEntry("transactionCount", 1);
            assertThat(payload).containsEntry("pageCount", result.pageCount());
            assertThat(payload).containsKey("grandTotal");
            assertThat(payload).containsEntry("s3Key", result.s3Key());
        }

        @Test
        @DisplayName("audit event resourceType is TRANSACTION_REPORT")
        void runReport_auditEvent_resourceTypeIsTransactionReport() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            verify(auditLogService).logAuditEvent(
                    anyString(),
                    eq("TRANSACTION_REPORT"),
                    anyString(),
                    anyString(),
                    any(),
                    any());
        }

        @Test
        @DisplayName("audit event operator code identifies CBTRN03C batch")
        void runReport_auditEvent_operatorIdentifiesBatchProgram() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            verify(auditLogService).logAuditEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    eq("BATCH/CBTRN03C"),
                    any(),
                    any());
        }

        @Test
        @DisplayName("audit log NOT emitted when input validation fails")
        void runReport_validationFailure_doesNotEmitAudit() {
            // No repository stubbing — validation must fail before
            // any audit-log side effect.
            assertThatThrownBy(() -> service.generateReport(null, END_DATE))
                    .isInstanceOf(CardDemoException.class);
            verifyNoInteractions(auditLogService);
        }
    }

    /**
     * Repository-interaction tests verifying that the service uses
     * the expected repository methods and never strays beyond the
     * intended boundary (e.g., the service must not invoke
     * {@code findByTranCardNumAndTranProcTsBetween} since the COBOL
     * source filters in memory after a sequential read &mdash; the
     * Java target preserves that data-access pattern via
     * {@link TransactionRepository#findAll()}).
     */
    @Nested
    @DisplayName("Repository interaction — findAll() boundary")
    class RepositoryInteraction {

        @Test
        @DisplayName("transactionRepository.findAll() invoked exactly once")
        void runReport_invokesFindAllExactlyOnce() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            verify(transactionRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("transactionRepository does NOT receive any other interactions")
        void runReport_doesNotInvokeOtherRepositoryMethods() {
            stubLenientLookupResponses();
            Transaction tx = buildTransaction("T1", CARD_A, TYPE_PURCHASE, CAT_RETAIL,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2024, 1, 15, 9, 0));
            when(transactionRepository.findAll()).thenReturn(List.of(tx));

            service.generateReport(START_DATE, END_DATE);

            // The service must never call save / saveAll / delete on
            // the transaction repository (this is a read-only report).
            verify(transactionRepository, never()).save(any());
            verify(transactionRepository, never()).deleteById(anyString());
        }
    }

    // ==================================================================
    // Internal helpers used by the @Nested groups
    // ==================================================================

    /**
     * Counts non-overlapping occurrences of {@code needle} in
     * {@code haystack}. Used by the report-text assertions to count
     * subtotal lines, page-total lines, etc.
     *
     * @param haystack the source text
     * @param needle   the substring to search for; must be non-empty
     * @return the number of non-overlapping occurrences (0 if none)
     */
    private static long countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
