/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.processor.TransactionReportProcessor;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import com.carddemo.entity.TransactionType;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioral-parity unit tests for {@link TransactionReportProcessor}, the Java
 * realization of the COBOL daily-transaction-report engine {@code CBTRN03C}
 * (JCL {@code app/jcl/TRANREPT.jcl}, report layout copybook
 * {@code app/cpy/CVTRA07Y.cpy}) at source commit {@code 27d6c6f}.
 *
 * <p>This is a <em>pure</em> in-memory unit test: the three reference
 * repositories are Mockito mocks and the {@code @StepScope} control-break state
 * is driven manually by invoking {@link TransactionReportProcessor#process(Transaction)}
 * and {@link TransactionReportProcessor#getReportTrailerLines()} directly. No
 * Spring context, no {@code spring-batch-test}, no step-scope bootstrap, no
 * Testcontainers, and no real database or AWS service is involved. The
 * complementary end-to-end byte-equivalence check against a COBOL golden file
 * lives in {@code TransactionReportJobIT}.</p>
 *
 * <p>The suite pins the {@code CVTRA07Y} fixed-width contract: the {@code 133}-byte
 * detail line and its field offsets, the {@code 133}-hyphen {@code TRANSACTION-HEADER-2}
 * separator, the {@code -ZZZ,ZZZ,ZZZ.ZZ} detail and {@code +ZZZ,ZZZ,ZZZ.ZZ} total
 * edit masks (with all-{@code Z} zero suppression), the
 * {@code 1500-A/B/C} XREF&rarr;TRANTYPE&rarr;TRANCATG enrichment order, the
 * {@code INVALID KEY} abend on a missing reference, and the
 * page&rarr;account&rarr;grand control-break roll-up (page size {@code 20}).
 * Monetary values use {@link BigDecimal} scale {@code 2} with
 * {@link RoundingMode#HALF_EVEN} and are compared with {@code compareTo}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor — CBTRN03C / CVTRA07Y report layout & control break")
class TransactionReportProcessorTest {

    /** COBOL {@code FD-REPTFILE-REC PIC X(133)} fixed record width. */
    private static final int RECORD_WIDTH = 133;

    /** COBOL {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}. */
    private static final int PAGE_SIZE = 20;

    // Baseline in-window transaction values shared by the @BeforeEach fixture.
    private static final String CARD_NUM = "4111111111111111"; // TRAN-CARD-NUM X(16)
    private static final String TRAN_ID = "0000000000000001";  // TRAN-ID X(16)
    private static final String TYPE_CODE = "01";              // TransactionTypeCode.PURCHASE
    private static final Integer CAT_CD = 1;                   // TRAN-CAT-CD
    private static final long ACCT_ID = 1001L;                 // XREF-ACCT-ID
    private static final long CUST_ID = 9001L;                 // XREF-CUST-ID (not printed)
    private static final String TYPE_DESC = "Purchase";        // TRAN-TYPE-DESC X(15)
    private static final String CAT_DESC = "Regular Sales";    // TRAN-CAT-TYPE-DESC X(29)
    private static final String SOURCE = "POS";                // TRAN-SOURCE X(10)
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");
    private static final String START_DATE = "2022-01-01";     // DATEPARM start
    private static final String END_DATE = "2022-07-06";       // DATEPARM end
    private static final String IN_WINDOW_PROC_TS = "2022-03-15 10:00:00.000000";

    // Detail-line field offsets (CVTRA07Y TRANSACTION-DETAIL-REPORT), 0-indexed, half-open.
    private static final int OFF_FILLER_1 = 16;        // space after TRANS-ID
    private static final int OFF_ACCOUNT_ID = 17;      // ACCOUNT-ID X(11) start
    private static final int OFF_FILLER_2 = 28;        // space after ACCOUNT-ID
    private static final int OFF_TYPE_CD = 29;         // TYPE-CD X(02) start
    private static final int OFF_SEP_TYPE = 31;        // '-' between TYPE-CD and TYPE-DESC
    private static final int OFF_TYPE_DESC = 32;       // TYPE-DESC X(15) start
    private static final int OFF_FILLER_3 = 47;        // space after TYPE-DESC
    private static final int OFF_CAT_CD = 48;          // CAT-CD 9(04) start
    private static final int OFF_SEP_CAT = 52;         // '-' between CAT-CD and CAT-DESC
    private static final int OFF_CAT_DESC = 53;        // CAT-DESC X(29) start
    private static final int OFF_FILLER_4 = 82;        // space after CAT-DESC
    private static final int OFF_SOURCE = 83;          // SOURCE X(10) start
    private static final int OFF_SOURCE_END = 93;      // FILLER X(04) start
    private static final int OFF_AMOUNT = 97;          // edited amount X(15) start
    private static final int OFF_AMOUNT_END = 112;     // FILLER X(02) start

    // Golden edited-amount strings (15 chars) reproduced from the CVTRA07Y masks.
    private static final String BLANK_AMOUNT = " ".repeat(15);

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    private TransactionReportProcessor processor;

    private Transaction transaction;
    private CardXref cardXref;
    private TransactionType transactionType;
    private TransactionCategory transactionCategory;

    /**
     * Builds the baseline in-window fixture and a freshly constructed,
     * step-scope-equivalent processor for every test. The processor is created
     * manually (rather than with {@code @InjectMocks}) because the production
     * constructor binds the two {@code DATEPARM} window dates from
     * {@code @Value("#{jobParameters[...]}")} — values that only exist at step
     * runtime — so the test supplies them explicitly. No stubbing happens here:
     * each test stubs exactly the lookups it exercises to keep Mockito strict.
     */
    @BeforeEach
    void setUp() {
        transaction = newTransaction(TRAN_ID, CARD_NUM, CAT_CD, AMOUNT);
        cardXref = new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
        transactionType = new TransactionType(TYPE_CODE, TYPE_DESC);
        transactionCategory =
                new TransactionCategory(new TransactionCategoryId(TYPE_CODE, CAT_CD), CAT_DESC);
        processor = new TransactionReportProcessor(
                cardXrefRepository,
                transactionTypeRepository,
                transactionCategoryRepository,
                START_DATE,
                END_DATE);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Builds a posted {@link Transaction} with an in-window processing timestamp,
     * type {@code 01} (PURCHASE), and the shared {@code POS} source.
     *
     * @param tranId  the transaction id ({@code TRAN-ID})
     * @param cardNum the card number ({@code TRAN-CARD-NUM})
     * @param catCd   the category code ({@code TRAN-CAT-CD})
     * @param amount  the monetary amount ({@code TRAN-AMT})
     * @return a fully populated transaction
     */
    private static Transaction newTransaction(String tranId, String cardNum, Integer catCd,
            BigDecimal amount) {
        Transaction tran = new Transaction();
        tran.setTranId(tranId);
        tran.setCardNum(cardNum);
        tran.setTranTypeCd(TransactionTypeCode.PURCHASE.getCode());
        tran.setTranCatCd(catCd);
        tran.setTranSource(SOURCE);
        tran.setTranAmt(amount);
        tran.setProcTs(IN_WINDOW_PROC_TS);
        return tran;
    }

    /**
     * Stubs the three keyed enrichment reads for the baseline fixture
     * (XREF&rarr;account id, TRANTYPE&rarr;description, TRANCATG&rarr;description).
     */
    private void stubBaselineLookups() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.of(transactionType));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CAT_CD)))
                .thenReturn(Optional.of(transactionCategory));
    }

    /**
     * Right-pads (or truncates) {@code value} to exactly {@code width} characters
     * with spaces — the byte-for-byte behaviour of a COBOL {@code MOVE ... TO PIC
     * X(width)}, used to build expected field segments.
     *
     * @param value the value to coerce; {@code null} is treated as empty
     * @param width the exact target width
     * @return a left-justified string of exactly {@code width} characters
     */
    private static String padRight(String value, int width) {
        String text = (value == null) ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Reconstructs a {@link BigDecimal} (scale {@code 2}) from a 15-character
     * edited amount field so totals can be compared with {@code compareTo}. The
     * all-{@code Z} blank field (a zero value) parses back to zero.
     *
     * @param editedField the 15-character edited amount
     * @return the numeric value with scale {@code 2}
     */
    private static BigDecimal parseEditedAmount(String editedField) {
        // The mask right-justifies the integer part with leading spaces and keeps
        // the sign in a fixed leftmost column, so every space and grouping comma
        // must be stripped before parsing (not merely trimmed).
        String compact = editedField.replace(",", "").replace(" ", "");
        if (compact.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return new BigDecimal(compact).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Returns the detail line for a processed row — always the last element of
     * the emitted list, since the detail line is appended after any control-break
     * block.
     *
     * @param lines the lines returned by {@code process}
     * @return the detail line
     */
    private static String detailLineOf(List<String> lines) {
        return lines.get(lines.size() - 1);
    }

    /**
     * Returns the single line in {@code lines} that contains {@code token},
     * failing the assertion when zero or more than one match is found.
     *
     * @param lines the lines to search
     * @param token the substring identifying the desired line
     * @return the matching line
     */
    private static String lineContaining(List<String> lines, String token) {
        List<String> matches = new ArrayList<>();
        for (String line : lines) {
            if (line.contains(token)) {
                matches.add(line);
            }
        }
        assertThat(matches)
                .as("exactly one line containing '%s'", token)
                .hasSize(1);
        return matches.get(0);
    }

    // ==================================================================
    // Phase 2 — Detail line layout (the 133-byte CVTRA07Y contract)
    // ==================================================================

    @Test
    @DisplayName("detail line is exactly 133 bytes (FD-REPTFILE-REC PIC X(133))")
    void detailLineIsExactly133Characters() {
        stubBaselineLookups();

        List<String> lines = processor.process(transaction);

        assertThat(detailLineOf(lines)).hasSize(RECORD_WIDTH);
    }

    @Test
    @DisplayName("detail line places every field at its CVTRA07Y offset with the '-' separators")
    void detailLineFieldPlacementMatchesCvtra07yOffsets() {
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        assertThat(detail).hasSize(RECORD_WIDTH);
        // TRAN-REPORT-TRANS-ID X(16)
        assertThat(detail.substring(0, OFF_FILLER_1)).isEqualTo(TRAN_ID);
        assertThat(detail.charAt(OFF_FILLER_1)).isEqualTo(' ');
        // TRAN-REPORT-ACCOUNT-ID X(11) — XREF-ACCT-ID zero-padded to 11 digits
        assertThat(detail.substring(OFF_ACCOUNT_ID, OFF_FILLER_2)).isEqualTo("00000001001");
        assertThat(detail.charAt(OFF_FILLER_2)).isEqualTo(' ');
        // TRAN-REPORT-TYPE-CD X(02) then FILLER '-'
        assertThat(detail.substring(OFF_TYPE_CD, OFF_SEP_TYPE)).isEqualTo(TYPE_CODE);
        assertThat(detail.charAt(OFF_SEP_TYPE)).isEqualTo('-');
        // TRAN-REPORT-TYPE-DESC X(15)
        assertThat(detail.substring(OFF_TYPE_DESC, OFF_FILLER_3)).isEqualTo(padRight(TYPE_DESC, 15));
        assertThat(detail.charAt(OFF_FILLER_3)).isEqualTo(' ');
        // TRAN-REPORT-CAT-CD 9(04) zero-padded then FILLER '-'
        assertThat(detail.substring(OFF_CAT_CD, OFF_SEP_CAT)).isEqualTo("0001");
        assertThat(detail.charAt(OFF_SEP_CAT)).isEqualTo('-');
        // TRAN-REPORT-CAT-DESC X(29)
        assertThat(detail.substring(OFF_CAT_DESC, OFF_FILLER_4)).isEqualTo(padRight(CAT_DESC, 29));
        assertThat(detail.charAt(OFF_FILLER_4)).isEqualTo(' ');
        // TRAN-REPORT-SOURCE X(10) then FILLER X(04) spaces
        assertThat(detail.substring(OFF_SOURCE, OFF_SOURCE_END)).isEqualTo(padRight(SOURCE, 10));
        assertThat(detail.substring(OFF_SOURCE_END, OFF_AMOUNT)).isEqualTo("    ");
        // TRAN-REPORT-AMT (edited) then trailing FILLER + record pad
        assertThat(detail.substring(OFF_AMOUNT, OFF_AMOUNT_END)).hasSize(15);
        assertThat(detail.substring(OFF_AMOUNT_END)).containsOnlyWhitespaces();
    }

    @Test
    @DisplayName("detail line equals the byte-exact CVTRA07Y golden record")
    void detailLineEqualsGoldenRecord() {
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        String expectedContent =
                TRAN_ID                       // TRAN-REPORT-TRANS-ID X(16)
                + " "                         // FILLER X(01)
                + "00000001001"               // TRAN-REPORT-ACCOUNT-ID X(11)
                + " "                         // FILLER X(01)
                + TYPE_CODE                   // TRAN-REPORT-TYPE-CD X(02)
                + "-"                         // FILLER X(01) '-'
                + padRight(TYPE_DESC, 15)     // TRAN-REPORT-TYPE-DESC X(15)
                + " "                         // FILLER X(01)
                + "0001"                      // TRAN-REPORT-CAT-CD 9(04)
                + "-"                         // FILLER X(01) '-'
                + padRight(CAT_DESC, 29)      // TRAN-REPORT-CAT-DESC X(29)
                + " "                         // FILLER X(01)
                + padRight(SOURCE, 10)        // TRAN-REPORT-SOURCE X(10)
                + "    "                      // FILLER X(04)
                + "       1,234.56"           // TRAN-REPORT-AMT -ZZZ,ZZZ,ZZZ.ZZ
                + "  ";                       // FILLER X(02)
        String expected = padRight(expectedContent, RECORD_WIDTH);

        assertThat(detail).isEqualTo(expected);
    }

    @Test
    @DisplayName("TRANSACTION-HEADER-2 separator is exactly 133 hyphens (CVTRA07Y line 48)")
    void header2SeparatorLineIsExactly133Hyphens() {
        stubBaselineLookups();

        // The first row emits the header block: name header, blank, column header, separator.
        List<String> lines = processor.process(transaction);
        String separator = lines.get(3);

        assertThat(separator)
                .as("TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'")
                .hasSize(RECORD_WIDTH)
                .isEqualTo("-".repeat(RECORD_WIDTH));
    }

    @Test
    @DisplayName("first row emits the 4-line header block then the detail line, all 133 wide")
    void firstRowEmitsHeaderBlockThenDetail() {
        stubBaselineLookups();

        List<String> lines = processor.process(transaction);

        // REPORT-NAME-HEADER, blank spacer, TRANSACTION-HEADER-1, TRANSACTION-HEADER-2, detail.
        assertThat(lines).hasSize(5);
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(RECORD_WIDTH));

        String nameHeader = lines.get(0);
        assertThat(nameHeader)
                .contains("DALYREPT")
                .contains("Daily Transaction Report")
                .contains("Date Range: ")
                .contains(START_DATE)
                .contains(END_DATE);

        assertThat(lines.get(1))
                .as("blank spacer line is 133 spaces")
                .isEqualTo(" ".repeat(RECORD_WIDTH));

        String columnHeader = lines.get(2);
        assertThat(columnHeader)
                .contains("Transaction ID")
                .contains("Account ID")
                .contains("Transaction Type")
                .contains("Tran Category")
                .contains("Tran Source")
                .contains("Amount");

        assertThat(lines.get(3)).isEqualTo("-".repeat(RECORD_WIDTH));
    }

    // ==================================================================
    // Phase 3 — Edit masks (-ZZZ,ZZZ,ZZZ.ZZ detail / +ZZZ,ZZZ,ZZZ.ZZ total)
    // ==================================================================

    @Test
    @DisplayName("positive detail amount uses the -ZZZ,ZZZ,ZZZ.ZZ mask with a blank sign position")
    void detailAmountPositiveUsesMinusMaskWithBlankSign() {
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        // 1234.56 -> sign blank, comma-grouped & right-justified in 11, '.', 2 fraction digits.
        assertThat(detail.substring(OFF_AMOUNT, OFF_AMOUNT_END)).isEqualTo("       1,234.56");
    }

    @Test
    @DisplayName("negative detail amount shows the leading minus sign of the -ZZZ,ZZZ,ZZZ.ZZ mask")
    void detailAmountNegativeShowsLeadingMinus() {
        transaction.setTranAmt(new BigDecimal("-1234.56"));
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        assertThat(detail.substring(OFF_AMOUNT, OFF_AMOUNT_END)).isEqualTo("-      1,234.56");
    }

    @Test
    @DisplayName("zero detail amount is blanked to spaces (all-Z zero suppression)")
    void detailAmountZeroIsBlankedToSpaces() {
        transaction.setTranAmt(BigDecimal.ZERO);
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        assertThat(detail.substring(OFF_AMOUNT, OFF_AMOUNT_END)).isEqualTo(BLANK_AMOUNT);
    }

    @Test
    @DisplayName("positive total amount uses the +ZZZ,ZZZ,ZZZ.ZZ mask with a leading plus")
    void totalAmountPositiveUsesPlusMask() {
        transaction.setTranAmt(new BigDecimal("5000.00"));
        stubBaselineLookups();

        processor.process(transaction);
        String grandTotalLine = lineContaining(processor.getReportTrailerLines(), "Grand Total");

        assertThat(grandTotalLine).hasSize(RECORD_WIDTH);
        assertThat(grandTotalLine.substring(OFF_AMOUNT, OFF_AMOUNT_END)).isEqualTo("+      5,000.00");
    }

    // ==================================================================
    // Phase 4 — Enrichment lookups (1500-A/B/C order and INVALID KEY abend)
    // ==================================================================

    @Test
    @DisplayName("first row of a card performs XREF -> TRANTYPE -> TRANCATG lookups in COBOL order")
    void firstRowPerformsThreeLookupsInCobolOrder() {
        stubBaselineLookups();

        String detail = detailLineOf(processor.process(transaction));

        InOrder order =
                inOrder(cardXrefRepository, transactionTypeRepository, transactionCategoryRepository);
        order.verify(cardXrefRepository).findById(CARD_NUM);
        order.verify(transactionTypeRepository).findById(TYPE_CODE);
        order.verify(transactionCategoryRepository)
                .findById(new TransactionCategoryId(TYPE_CODE, CAT_CD));
        order.verifyNoMoreInteractions();

        // The enriched values land in their detail-line slots.
        assertThat(detail.substring(OFF_ACCOUNT_ID, OFF_FILLER_2)).isEqualTo("00000001001");
        assertThat(detail.substring(OFF_TYPE_DESC, OFF_FILLER_3)).isEqualTo(padRight(TYPE_DESC, 15));
        assertThat(detail.substring(OFF_CAT_DESC, OFF_FILLER_4)).isEqualTo(padRight(CAT_DESC, 29));
    }

    @Test
    @DisplayName("XREF lookup is performed once per card and reused for later rows of the same card")
    void xrefLookupIsReusedAcrossRowsOfTheSameCard() {
        stubBaselineLookups();

        processor.process(newTransaction(TRAN_ID, CARD_NUM, CAT_CD, new BigDecimal("10.00")));
        processor.process(newTransaction("0000000000000002", CARD_NUM, CAT_CD, new BigDecimal("20.00")));

        // 1500-A runs only on the card-number control break; 1500-B/C run for every row.
        verify(cardXrefRepository, times(1)).findById(CARD_NUM);
        verify(transactionTypeRepository, times(2)).findById(TYPE_CODE);
        verify(transactionCategoryRepository, times(2))
                .findById(new TransactionCategoryId(TYPE_CODE, CAT_CD));
    }

    @Test
    @DisplayName("missing cross-reference raises the INVALID KEY abend (IllegalStateException)")
    void missingXrefRaisesIllegalState() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(transaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVALID CARD NUMBER")
                .hasMessageContaining(CARD_NUM);
    }

    @Test
    @DisplayName("missing transaction type raises the INVALID KEY abend (IllegalStateException)")
    void missingTransactionTypeRaisesIllegalState() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(transaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVALID TRANSACTION TYPE")
                .hasMessageContaining(TYPE_CODE);
    }

    @Test
    @DisplayName("missing transaction category raises the INVALID KEY abend (IllegalStateException)")
    void missingTransactionCategoryRaisesIllegalState() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.of(transactionType));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CAT_CD)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(transaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVALID TRAN CATG KEY")
                .hasMessageContaining(TYPE_CODE + "/" + CAT_CD);
    }

    // ==================================================================
    // Phase 5 — Paging and control breaks (driven manually, no step scope)
    // ==================================================================

    @Test
    @DisplayName("a full page emits a page total and reprints the headers, rolling into the grand total")
    void pageBreakEmitsPageTotalAndReprintsHeaders() {
        stubBaselineLookups();

        List<String> emitted = new ArrayList<>();
        for (int i = 1; i <= PAGE_SIZE; i++) {
            emitted.addAll(processor.process(
                    newTransaction(String.format("%016d", i), CARD_NUM, CAT_CD, new BigDecimal("1.00"))));
        }

        long pageTotalLines = emitted.stream().filter(line -> line.contains("Page Total")).count();
        long reprintedHeaders =
                emitted.stream().filter(line -> line.contains("Daily Transaction Report")).count();
        assertThat(pageTotalLines)
                .as("page-total line emitted on the WS-LINE-COUNTER MOD WS-PAGE-SIZE break")
                .isGreaterThanOrEqualTo(1);
        // The page-total line itself preserves the 133-byte record width.
        assertThat(lineContaining(emitted, "Page Total")).hasSize(RECORD_WIDTH);
        assertThat(reprintedHeaders)
                .as("name header printed initially and reprinted after the page break")
                .isGreaterThanOrEqualTo(2);

        // Every page total rolls into the grand total; the trailer flushes the residual.
        String grandTotalLine = lineContaining(processor.getReportTrailerLines(), "Grand Total");
        assertThat(parseEditedAmount(grandTotalLine.substring(OFF_AMOUNT, OFF_AMOUNT_END)))
                .isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("a card-number change flushes the account total and resets the accumulator")
    void cardNumberChangeFlushesAccountTotalAndResets() {
        String cardA = CARD_NUM;
        String cardB = "5555444433332222";
        CardXref xrefB = new CardXref(cardB, CUST_ID, 2002L);
        when(cardXrefRepository.findById(cardA)).thenReturn(Optional.of(cardXref));
        when(cardXrefRepository.findById(cardB)).thenReturn(Optional.of(xrefB));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.of(transactionType));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CAT_CD)))
                .thenReturn(Optional.of(transactionCategory));

        // Card A: 100.00 + 200.00 + 300.00 = 600.00.
        processor.process(newTransaction("0000000000000001", cardA, CAT_CD, new BigDecimal("100.00")));
        processor.process(newTransaction("0000000000000002", cardA, CAT_CD, new BigDecimal("200.00")));
        processor.process(newTransaction("0000000000000003", cardA, CAT_CD, new BigDecimal("300.00")));
        // Card B (50.00) triggers the account-total flush for card A.
        List<String> cardBlines =
                processor.process(newTransaction("0000000000000004", cardB, CAT_CD, new BigDecimal("50.00")));

        String accountTotalForA = lineContaining(cardBlines, "Account Total");
        assertThat(accountTotalForA).hasSize(RECORD_WIDTH);
        assertThat(parseEditedAmount(accountTotalForA.substring(OFF_AMOUNT, OFF_AMOUNT_END)))
                .as("flushed account total equals the sum of card A's amounts")
                .isEqualByComparingTo(new BigDecimal("600.00"));

        // The accumulator reset means the trailing account total reflects only card B.
        String accountTotalForB = lineContaining(processor.getReportTrailerLines(), "Account Total");
        assertThat(accountTotalForB).hasSize(RECORD_WIDTH);
        assertThat(parseEditedAmount(accountTotalForB.substring(OFF_AMOUNT, OFF_AMOUNT_END)))
                .as("account accumulator reset after the flush")
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("the end-of-report grand total equals the sum of every detail amount")
    void trailerGrandTotalEqualsSumOfAllDetailAmounts() {
        stubBaselineLookups();

        processor.process(newTransaction("0000000000000001", CARD_NUM, CAT_CD, new BigDecimal("100.00")));
        processor.process(newTransaction("0000000000000002", CARD_NUM, CAT_CD, new BigDecimal("200.00")));

        List<String> trailer = processor.getReportTrailerLines();
        String grandTotalLine = lineContaining(trailer, "Grand Total");

        // Exact +ZZZ,ZZZ,ZZZ.ZZ rendering plus a value comparison, at the full record width.
        assertThat(grandTotalLine).hasSize(RECORD_WIDTH);
        assertThat(grandTotalLine.substring(OFF_AMOUNT, OFF_AMOUNT_END)).isEqualTo("+        300.00");
        assertThat(parseEditedAmount(grandTotalLine.substring(OFF_AMOUNT, OFF_AMOUNT_END)))
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("page->account->grand roll-up is consistent: per-account totals sum to the grand total")
    void pageAccountGrandRollUpHierarchyIsConsistent() {
        String cardA = CARD_NUM;
        String cardB = "5555444433332222";
        CardXref xrefB = new CardXref(cardB, CUST_ID, 2002L);
        when(cardXrefRepository.findById(cardA)).thenReturn(Optional.of(cardXref));
        when(cardXrefRepository.findById(cardB)).thenReturn(Optional.of(xrefB));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.of(transactionType));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CAT_CD)))
                .thenReturn(Optional.of(transactionCategory));

        // Card A: 10 + 20 + 30 = 60. Card B: 40 + 50 = 90. Grand = 150.
        processor.process(newTransaction("0000000000000001", cardA, CAT_CD, new BigDecimal("10.00")));
        processor.process(newTransaction("0000000000000002", cardA, CAT_CD, new BigDecimal("20.00")));
        processor.process(newTransaction("0000000000000003", cardA, CAT_CD, new BigDecimal("30.00")));
        List<String> firstOfB =
                processor.process(newTransaction("0000000000000004", cardB, CAT_CD, new BigDecimal("40.00")));
        processor.process(newTransaction("0000000000000005", cardB, CAT_CD, new BigDecimal("50.00")));

        BigDecimal accountTotalA = parseEditedAmount(
                lineContaining(firstOfB, "Account Total").substring(OFF_AMOUNT, OFF_AMOUNT_END));

        List<String> trailer = processor.getReportTrailerLines();
        BigDecimal accountTotalB = parseEditedAmount(
                lineContaining(trailer, "Account Total").substring(OFF_AMOUNT, OFF_AMOUNT_END));
        BigDecimal grandTotal = parseEditedAmount(
                lineContaining(trailer, "Grand Total").substring(OFF_AMOUNT, OFF_AMOUNT_END));

        assertThat(accountTotalA).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(accountTotalB).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(grandTotal).isEqualByComparingTo(new BigDecimal("150.00"));
        // The hierarchy is internally consistent: account totals sum to the grand total.
        assertThat(accountTotalA.add(accountTotalB)).isEqualByComparingTo(grandTotal);
    }
}
