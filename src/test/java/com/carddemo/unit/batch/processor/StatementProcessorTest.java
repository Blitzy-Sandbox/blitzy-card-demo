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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.processor.StatementProcessor;
import com.carddemo.dto.StatementDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link StatementProcessor}, the
 * Spring Batch {@code ItemProcessor} translation of the legacy COBOL statement
 * generator {@code app/cbl/CBSTM03A.CBL} (924&nbsp;LOC) together with its
 * file-service subroutine {@code app/cbl/CBSTM03B.CBL} (230&nbsp;LOC), the JCL
 * {@code app/jcl/CREASTMT.JCL}, and the statement copybook
 * {@code app/cpy/COSTM01.CPY}, all frozen at source commit SHA {@code 27d6c6f}.
 *
 * <p>The legacy program drives the run from the card cross-reference and, for
 * each card, performs three keyed reads &mdash; customer master, account master,
 * then the card's transactions &mdash; before rendering an 80-column plain-text
 * statement ({@code FD-STMTFILE-REC PIC X(80)}) and a 100-column HTML statement
 * ({@code FD-HTMLFILE-REC PIC X(100)}) and accumulating a running total
 * ({@code WS-TOTAL-AMT PIC S9(9)V99}). The static {@code CALL 'CBSTM03B'}
 * file-service subroutine is modernised into constructor-injected Spring Data
 * repositories, so these tests drive {@link StatementProcessor#process(CardXref)}
 * directly with all repositories mocked.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong>
 * {@code @SpringBootTest}, {@code spring-batch-test}, {@code JobLauncherTestUtils},
 * Testcontainers, database, or AWS resource. The three repository collaborators
 * are Mockito mocks injected into the processor; {@link MockitoExtension} runs in
 * its default strict-stub mode, so each test stubs only the finders it actually
 * exercises (repository wiring is applied per-test via {@link #wire} rather than
 * leniently in {@code @BeforeEach}).</p>
 *
 * <p>Parity-sensitive facts pinned against the <em>compiled</em> contract
 * ("compiled source wins"):</p>
 * <ul>
 *   <li>The carrier returned is the nested record
 *       {@link StatementProcessor.StatementBundle} with accessors
 *       {@code text()}, {@code html()}, and {@code total()}.</li>
 *   <li>The processor's constructor injects exactly three repositories
 *       ({@link TransactionRepository}, {@link CustomerRepository},
 *       {@link AccountRepository}); these are the only mocks.</li>
 *   <li>Repository delegation order is customer &rarr; account &rarr;
 *       transactions, mirroring the {@code CBSTM03B} open/read/read-key/close
 *       sequence; verified with {@link InOrder}.</li>
 *   <li>Every rendered record is normalised to its exact fixed width (80 for
 *       text, 100 for HTML), reproducing the COBOL fixed-length output records.</li>
 *   <li>{@link StatementDto} is a record with exactly the 13 {@code COSTM01
 *       TRNX-RECORD} components, in order; its {@code transactionType} component
 *       is a {@link String} (the two-character {@code TRAN-TYPE} code), not the
 *       {@link TransactionTypeCode} enum, and {@code amount} is a
 *       {@link BigDecimal}.</li>
 *   <li>Monetary values are {@link BigDecimal} with scale&nbsp;2 and are compared
 *       with {@code compareTo} semantics (AssertJ {@code isEqualByComparingTo}),
 *       never {@code equals}, and never {@code double}/{@code float}.</li>
 * </ul>
 *
 * <p>The job-level, end-to-end statement behaviour (the full {@code CREASTMT}
 * pipeline against real PostgreSQL + LocalStack, writing to S3 and comparing to a
 * byte-equivalent COBOL golden file) is covered separately by the integration
 * suite ({@code StatementJobIT}); the two are complementary, not redundant. The
 * whole suite is clean under the project's zero-warning ({@code -Xlint:all
 * -Werror}) Java&nbsp;25 build.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor — CBSTM03A/CBSTM03B statement generation parity @ 27d6c6f")
class StatementProcessorTest {

    /** Exact record width of the plain-text statement ({@code FD-STMTFILE-REC PIC X(80)}). */
    private static final int TEXT_WIDTH = 80;

    /** Exact record width of the HTML statement ({@code FD-HTMLFILE-REC PIC X(100)}). */
    private static final int HTML_WIDTH = 100;

    /** Owning customer identifier carried by the cross-reference ({@code XREF-CUST-ID}). */
    private static final Long CUST_ID = 100L;

    /** Owning account identifier carried by the cross-reference ({@code XREF-ACCT-ID}). */
    private static final Long ACCT_ID = 10_000_000_011L;

    /** A representative 16-character card number ({@code XREF-CARD-NUM PIC X(16)}). */
    private static final String CARD_NUM = "4111111111111111";

    /** A representative 26-character origination timestamp ({@code TRAN-ORIG-TS PIC X(26)}). */
    private static final String ORIG_TS = "2023-01-01 08:00:00.000000";

    /** A representative 26-character processing timestamp ({@code TRAN-PROC-TS PIC X(26)}). */
    private static final String PROC_TS = "2023-01-02 02:00:00.000000";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private StatementProcessor processor;

    /** Baseline cross-reference item supplied to {@code process(...)}. */
    private CardXref cardXref;

    /** Baseline account master fixture (only the fields the renderer reads are set). */
    private Account account;

    /** Baseline customer master fixture (clean, metacharacter-free data). */
    private Customer customer;

    /** Baseline transactions in ascending {@code TRAN-ID} order with known amounts. */
    private List<Transaction> transactions;

    @BeforeEach
    void setUp() {
        cardXref = new CardXref(CARD_NUM, CUST_ID, ACCT_ID);

        account = new Account();
        account.setAcctId(ACCT_ID);
        account.setCurrBal(new BigDecimal("1234.56"));

        customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setFirstName("JOHN");
        customer.setMiddleName("Q");
        customer.setLastName("PUBLIC");
        customer.setAddrLine1("123 MAIN STREET");
        customer.setAddrLine2("APT 4B");
        customer.setAddrLine3("SEATTLE");
        customer.setAddrStateCd("WA");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("98101");
        customer.setFicoCreditScore(750);

        // Amounts are intentionally non-monotonic (10.00, 20.50, -5.25) while the
        // tran-ids are strictly ascending, so the ordering test proves the
        // statement follows the repository's tran-id order rather than amount.
        transactions = List.of(
                baselineTransaction("TXN0000000000001", TransactionTypeCode.PURCHASE,
                        new BigDecimal("10.00"), "GROCERY PURCHASE"),
                baselineTransaction("TXN0000000000002", TransactionTypeCode.PAYMENT,
                        new BigDecimal("20.50"), "ONLINE PAYMENT"),
                baselineTransaction("TXN0000000000003", TransactionTypeCode.REFUND,
                        new BigDecimal("-5.25"), "RETURNED ITEM"));
    }

    // ------------------------------------------------------------------
    // Phase 2 — Line widths (the core fixed-record contract)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("renders every plain-text line to exactly 80 columns (FD-STMTFILE-REC PIC X(80))")
    void process_rendersEveryPlainTextLineToEightyColumns() {
        wire(customer, transactions);

        StatementProcessor.StatementBundle bundle = processor.process(cardXref);

        String[] lines = splitLines(bundle.text());
        assertThat(lines).isNotEmpty()
                .allSatisfy(line -> assertThat(line.length()).isEqualTo(TEXT_WIDTH));
        assertThat(bundle.text())
                .contains("START OF STATEMENT")
                .contains("END OF STATEMENT");
    }

    @Test
    @DisplayName("renders every HTML line to exactly 100 columns (FD-HTMLFILE-REC PIC X(100))")
    void process_rendersEveryHtmlLineToOneHundredColumns() {
        wire(customer, transactions);

        StatementProcessor.StatementBundle bundle = processor.process(cardXref);

        String[] lines = splitLines(bundle.html());
        assertThat(lines).isNotEmpty()
                .allSatisfy(line -> assertThat(line.length()).isEqualTo(HTML_WIDTH));
        assertThat(bundle.html())
                .contains("<!DOCTYPE html>")
                .contains("</html>");
    }

    // ------------------------------------------------------------------
    // Phase 3 — StatementDto mapping (COSTM01 TRNX-RECORD, 13 components)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toStatementDto maps all 13 COSTM01 TRNX-RECORD components from a transaction")
    void toStatementDto_mapsAllThirteenComponentsFromTransaction() {
        String descriptionTrimmed = "COFFEE SHOP PURCHASE";
        // The COBOL TRNX-DESC is PIC X(100); a fixed-width read is space-padded
        // and the processor right-trims that pad. A scale-1 amount confirms the
        // mapping normalises to scale 2.
        String descriptionPadded = descriptionTrimmed + "          ";
        Transaction transaction = new Transaction(
                "TXN0000000000099", TransactionTypeCode.PURCHASE.getCode(), 5, "POS",
                descriptionPadded, new BigDecimal("10.5"), 123_456_789L,
                "STARBUCKS", "SEATTLE", "98101", CARD_NUM, ORIG_TS, PROC_TS);

        StatementDto dto = processor.toStatementDto(transaction);

        assertThat(dto.cardNumber()).isEqualTo(CARD_NUM);
        assertThat(dto.transactionId()).isEqualTo("TXN0000000000099");
        // Compiled reality: the two-character TRAN-TYPE code surfaced as a String.
        assertThat(dto.transactionType())
                .isEqualTo(TransactionTypeCode.PURCHASE.getCode())
                .isEqualTo("01");
        // Numeric COBOL fields surface as their digit strings.
        assertThat(dto.categoryCode()).isEqualTo("5");
        assertThat(dto.merchantId()).isEqualTo("123456789");
        assertThat(dto.source()).isEqualTo("POS");
        // Trailing fixed-width pad is stripped.
        assertThat(dto.description()).isEqualTo(descriptionTrimmed);
        // Monetary amount is BigDecimal, normalised to scale 2 with HALF_EVEN.
        assertThat(dto.amount()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(dto.amount().scale()).isEqualTo(2);
        assertThat(dto.merchantName()).isEqualTo("STARBUCKS");
        assertThat(dto.merchantCity()).isEqualTo("SEATTLE");
        assertThat(dto.merchantZip()).isEqualTo("98101");
        // The 26-character timestamps are preserved verbatim (not trimmed).
        assertThat(dto.originTimestamp()).isEqualTo(ORIG_TS).hasSize(26);
        assertThat(dto.processTimestamp()).isEqualTo(PROC_TS).hasSize(26);
    }

    @Test
    @DisplayName("StatementDto is a record with exactly 13 COSTM01 components in order "
            + "(transactionType:String, amount:BigDecimal)")
    void statementDto_isRecordWithExactlyThirteenComponentsInCostm01Order() {
        assertThat(StatementDto.class.isRecord()).isTrue();

        RecordComponent[] components = StatementDto.class.getRecordComponents();
        assertThat(components).hasSize(13);

        String[] names = Arrays.stream(components)
                .map(RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(names).containsExactly(
                "cardNumber", "transactionId", "transactionType", "categoryCode", "source",
                "description", "amount", "merchantId", "merchantName", "merchantCity",
                "merchantZip", "originTimestamp", "processTimestamp");

        // Lock the coordination-point reality: transactionType is the 2-char code
        // as a String (not the enum), and amount is a BigDecimal.
        assertThat(components[2].getType()).isEqualTo(String.class);
        assertThat(components[6].getType()).isEqualTo(BigDecimal.class);
    }

    // ------------------------------------------------------------------
    // Phase 4 — Total and repository delegation order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("total equals the scale-2 sum of all transaction amounts (WS-TOTAL-AMT)")
    void process_totalEqualsSumOfTransactionAmountsWithScaleTwo() {
        wire(customer, transactions);

        StatementProcessor.StatementBundle bundle = processor.process(cardXref);

        // 10.00 + 20.50 + (-5.25) = 25.25
        assertThat(bundle.total()).isEqualByComparingTo(new BigDecimal("25.25"));
        assertThat(bundle.total().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("reads customer, then account, then transactions (CBSTM03B O/R/K/C delegation order)")
    void process_readsCustomerThenAccountThenTransactionsInOrder() {
        wire(customer, transactions);

        processor.process(cardXref);

        InOrder inOrder = inOrder(customerRepository, accountRepository, transactionRepository);
        inOrder.verify(customerRepository).findById(CUST_ID);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(transactionRepository).findByCardNumOrderByTranIdAsc(CARD_NUM);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("lists transactions in the repository's ascending tran-id order (no re-sort/reorder)")
    void process_preservesRepositoryTransactionOrderingInStatement() {
        wire(customer, transactions);

        String text = processor.process(cardXref).text();

        int firstIndex = text.indexOf("TXN0000000000001");
        int secondIndex = text.indexOf("TXN0000000000002");
        int thirdIndex = text.indexOf("TXN0000000000003");
        assertThat(firstIndex).isGreaterThanOrEqualTo(0);
        assertThat(secondIndex).isGreaterThan(firstIndex);
        assertThat(thirdIndex).isGreaterThan(secondIndex);
    }

    // ------------------------------------------------------------------
    // Edge cases — empty card and missing master records (abend parity)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with no transactions the total is zero (scale 2) and the statement still renders fixed-width")
    void process_withNoTransactions_yieldsZeroTotalAndStillRendersStatement() {
        wire(customer, List.of());

        StatementProcessor.StatementBundle bundle = processor.process(cardXref);

        assertThat(bundle.total()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bundle.total().scale()).isEqualTo(2);

        String[] lines = splitLines(bundle.text());
        assertThat(lines).isNotEmpty()
                .allSatisfy(line -> assertThat(line.length()).isEqualTo(TEXT_WIDTH));
        assertThat(bundle.text()).contains("END OF STATEMENT");
    }

    @Test
    @DisplayName("missing customer raises IllegalStateException (CBSTM03A keyed-read abend parity)")
    void process_whenCustomerMissing_throwsIllegalState() {
        // Stub only the customer read; the processor short-circuits before the
        // account/transaction reads, so no further stubbing is needed (strict).
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(cardXref))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Customer not found");
    }

    // ------------------------------------------------------------------
    // CP4 security regression — HTML statement escapes dynamic values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dynamic customer and transaction values are HTML-escaped in the HTML statement (CP4 XSS fix)")
    void process_escapesMaliciousDynamicValuesInHtml() {
        Customer malicious = new Customer();
        malicious.setCustId(CUST_ID);
        // buildName cuts at the first space, so the payload deliberately has none.
        malicious.setFirstName("<script>alert('xss')</script>");
        malicious.setAddrLine1("<img&\"bad\">");
        malicious.setFicoCreditScore(700);
        Transaction transaction = new Transaction(
                "<x>", TransactionTypeCode.PURCHASE.getCode(), 1, "POS", "<b>x</b>&\"'",
                new BigDecimal("10.00"), 123_456_789L, "M", "C", "Z",
                CARD_NUM, ORIG_TS, PROC_TS);
        wire(malicious, List.of(transaction));

        String html = processor.process(cardXref).html();

        // Escaped entities are present for every metacharacter class...
        assertThat(html)
                .contains("&lt;script&gt;")
                .contains("&lt;x&gt;")
                .contains("&lt;b&gt;x&lt;/b&gt;")
                .contains("&amp;")
                .contains("&quot;")
                .contains("&#39;");
        // ...and no raw, injectable markup from the dynamic values survives.
        assertThat(html)
                .doesNotContain("<script>")
                .doesNotContain("</script>")
                .doesNotContain("<img")
                .doesNotContain("<b>x");
    }

    @Test
    @DisplayName("clean fixture data is not over-escaped (HTML statement stays byte-equivalent)")
    void process_doesNotOverEscapeCleanData() {
        wire(customer, transactions);

        String html = processor.process(cardXref).html();

        // Clean dynamic values appear verbatim.
        assertThat(html)
                .contains("GROCERY PURCHASE")
                .contains("TXN0000000000001")
                .contains("JOHN");
        // No entity encoding is introduced when there are no metacharacters.
        assertThat(html)
                .doesNotContain("&amp;")
                .doesNotContain("&lt;")
                .doesNotContain("&gt;")
                .doesNotContain("&quot;")
                .doesNotContain("&#39;");
    }

    // ------------------------------------------------------------------
    // Test fixtures and helpers
    // ------------------------------------------------------------------

    /**
     * Builds a baseline posted transaction for {@link #CARD_NUM} with uniform
     * merchant/timestamp data, varying only the identity, type, amount, and
     * description that the per-test assertions key off.
     *
     * @param tranId      the transaction identifier ({@code TRAN-ID})
     * @param type        the transaction type ({@code TRAN-TYPE-CD})
     * @param amount      the monetary amount ({@code TRAN-AMT})
     * @param description the transaction description ({@code TRAN-DESC})
     * @return the populated transaction
     */
    private static Transaction baselineTransaction(String tranId, TransactionTypeCode type,
            BigDecimal amount, String description) {
        return new Transaction(tranId, type.getCode(), 1, "POS", description, amount, 100_000_001L,
                "MERCHANT NAME", "SEATTLE", "98101", CARD_NUM, ORIG_TS, PROC_TS);
    }

    /**
     * Wires the three repository collaborators for a {@code process(...)} call:
     * the customer and account keyed reads and the by-card transaction read. Used
     * only by tests that invoke {@code process(...)}, keeping strict Mockito happy.
     *
     * @param wiredCustomer     the customer returned for {@link #CUST_ID}
     * @param wiredTransactions the transactions returned for {@link #CARD_NUM}
     */
    private void wire(Customer wiredCustomer, List<Transaction> wiredTransactions) {
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(wiredCustomer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD_NUM))
                .thenReturn(wiredTransactions);
    }

    /**
     * Splits a newline-joined fixed-width payload into its constituent records.
     * The processor terminates every record with {@code '\n'}; the trailing empty
     * token after the final separator is dropped by {@link String#split(String)},
     * so each returned element is a full fixed-width record.
     *
     * @param payload the rendered statement payload
     * @return the individual fixed-width records
     */
    private static String[] splitLines(String payload) {
        return payload.split("\n");
    }
}
