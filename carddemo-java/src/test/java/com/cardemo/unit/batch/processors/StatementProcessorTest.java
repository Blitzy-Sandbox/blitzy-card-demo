package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.exception.RecordNotFoundException;
// AccountStatement import confirmed from the production StatementProcessor.process(...) return
// type: the processor declares ItemProcessor<CardCrossReference, AccountStatement> and returns a
// com.cardemo.model.dto.AccountStatement (an immutable record); the carrier is NOT owned by this
// test folder.
import com.cardemo.model.dto.AccountStatement;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked unit test for {@link StatementProcessor} &mdash; the Spring Batch
 * {@code ItemProcessor<CardCrossReference, AccountStatement>} that reproduces the per-account
 * statement-assembly logic of the legacy AWS CardDemo batch program {@code app/cbl/CBSTM03A.CBL}
 * (the Statement Generator).
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source {@code app/cbl/CBSTM03A.CBL} (and its file-service helper
 * {@code CBSTM03B.CBL}) is <strong>read-only reference</strong> material at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}; it is <strong>never copied</strong> into this repository and
 * is referenced here only by SHA and paragraph/line locator. Per the Minimal Change Clause
 * (AAP &sect;0.7.1), the 100% behavioural-parity requirement (AAP &sect;0.7.2) and the decimal-precision
 * rules (AAP &sect;0.7.3), these tests assert COBOL-identical statement content, the running expense
 * total and the picture-edited monetary formats, and invent nothing. The application base package is
 * {@code com.cardemo} (decision D-006, not {@code com.carddemo}).</p>
 *
 * <h2>The defining CBSTM03A behaviours this test locks down</h2>
 * <ol>
 *   <li><strong>Dual text + HTML rendering.</strong> CBSTM03A writes both a plain-text statement
 *       ({@code STMT-FILE}, {@code PIC X(80)}) and an HTML statement ({@code HTML-FILE},
 *       {@code PIC X(100)}). The processor assembles <em>both</em> bodies for the same account and
 *       hands them to the writer via the {@link AccountStatement} carrier.</li>
 *   <li><strong>Running expense total that resets per statement.</strong> {@code WS-TOTAL-AMT}
 *       ({@code PIC S9(09)V99}, L65) is reset to zero at the start of each statement
 *       ({@code MOVE ZERO TO WS-TOTAL-AMT}, L325) and accumulated per transaction
 *       ({@code ADD TRNX-AMT TO WS-TOTAL-AMT}, L429), at scale 2.</li>
 *   <li><strong>Picture-edited monetary formats.</strong> The current balance is edited
 *       {@code ST-CURR-BAL PIC 9(9).99-} (L113: leading-zero <em>retained</em>, fixed two decimals,
 *       trailing sign) and each transaction amount is edited {@code ST-TRANAMT PIC Z(9).99-}
 *       (L137: leading-zero <em>suppressed</em>, trailing sign).</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>This is a pure unit test: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock}
 * repositories and the processor instantiated directly through its constructor &mdash;
 * <strong>no</strong> Spring context, database, AWS, Testcontainers or {@code spring-batch-test}, and
 * no new dependencies (JUnit&nbsp;5, Mockito and AssertJ all arrive via
 * {@code spring-boot-starter-test}). Mockito runs in its default {@code STRICT_STUBS} mode, so each
 * test stubs only the lookups its path actually consumes (happy-path stubbing is factored into
 * {@link #stubHappyPath} and invoked solely by the tests that read all three datasets). Every monetary
 * value is a {@link BigDecimal} built from a {@link String} literal and compared with
 * {@code isEqualByComparingTo} &mdash; never {@code equals} &mdash; per AAP &sect;0.7.3.</p>
 *
 * <h2>Scope boundaries (CBSTM03B and statement persistence are out of scope here)</h2>
 * <p>CBSTM03A delegated all file OPEN/READ/CLOSE to the generic subroutine {@code CBSTM03B}; in the
 * migrated stack that I/O is served by the three injected repositories, so there is no file service to
 * mock or assert. Likewise, writing the assembled statement out (to the sequential statement file /
 * S3 objects) is the statement <em>writer</em>'s responsibility, not the processor's; these tests
 * therefore assert that the processor performs <strong>no</strong> persistence (see
 * {@link OmittedAndDeferredBehavior}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor — CBSTM03A dual text+HTML statement assembly (SHA 27d6c6f)")
class StatementProcessorTest {

    // --- Shared fixture constants -----------------------------------------------------------------

    /** Card number used as the cross-reference key (clean, recognisable test PAN). */
    private static final String CARD_NUM = "4111111111111111";

    /** Owning customer id carried on the cross-reference (resolves CUSTFILE / 2000-CUSTFILE-GET). */
    private static final Long CUST_ID = 100000001L;

    /** Owning account id carried on the cross-reference (resolves ACCTFILE / 3000-ACCTFILE-GET). */
    private static final Long ACCT_ID = 1L;

    /** First name token rendered into {@code ST-NAME} (composed with the middle and last names). */
    private static final String FIRST_NAME = "JOHN";

    /** Middle name token (a single word so {@code DELIMITED BY ' '} keeps it whole). */
    private static final String MIDDLE_NAME = "QUINCY";

    /** Last name token rendered into {@code ST-NAME}. */
    private static final String LAST_NAME = "DOE";

    /** The composed customer name as CBSTM03A's {@code 5000-CREATE-STATEMENT} STRING builds it. */
    private static final String COMPOSED_NAME = "JOHN QUINCY DOE";

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    /** The system under test, rebuilt fresh before every test (stateless processor). */
    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        // Constructor argument order confirmed against the production processor:
        // StatementProcessor(CustomerRepository, AccountRepository, TransactionRepository).
        processor = new StatementProcessor(customerRepository, accountRepository, transactionRepository);
    }

    // =================================================================================================
    // Phase A — fixtures
    // =================================================================================================

    /**
     * Builds a card cross-reference (CARDXREF/PATH record) carrying the card number plus the owning
     * customer and account ids, exactly as the COBOL {@code 1000-XREFFILE-GET-NEXT} browse supplies
     * one record at a time.
     */
    private CardCrossReference xref(String cardNum, Long custId, Long acctId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNum);
        xref.setXrefCustId(custId);
        xref.setXrefAcctId(acctId);
        return xref;
    }

    /**
     * Builds a customer with deterministic name and address fields so the rendered name/address can be
     * asserted in both the text and HTML statements. Each name part is a single token so the COBOL
     * {@code STRING ... DELIMITED BY ' '} composition yields the clean {@link #COMPOSED_NAME}.
     */
    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setCustId(id);
        customer.setCustFirstName(FIRST_NAME);
        customer.setCustMiddleName(MIDDLE_NAME);
        customer.setCustLastName(LAST_NAME);
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("APT 4");
        customer.setCustAddrLine3("SEATTLE");
        customer.setCustAddrStateCd("WA");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("98101");
        customer.setCustFicoCreditScore(750);
        return customer;
    }

    /**
     * Builds an account with a known current balance (the {@code String} is parsed to a
     * {@link BigDecimal} so the test exercises the {@code ST-CURR-BAL PIC 9(9).99-} edit precisely).
     */
    private Account account(Long id, String currBal) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctCurrBal(new BigDecimal(currBal));
        return account;
    }

    /**
     * Builds a transaction whose amount is parsed from a {@code String} to a {@link BigDecimal} (no
     * floating point), reproducing the {@code TRNX-RECORD} row CBSTM03A buffers per card.
     */
    private Transaction txn(String tranId, String desc, String amt, String cardNum) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranDesc(desc);
        txn.setTranAmt(new BigDecimal(amt));
        txn.setTranCardNum(cardNum);
        return txn;
    }

    /**
     * Stubs the happy-path reads consumed by a full statement assembly: the keyed customer read
     * ({@code 2000-CUSTFILE-GET}), the keyed account read ({@code 3000-ACCTFILE-GET}) and the per-card
     * transaction scan (the {@code WS-TRNX-TABLE} replacement). Invoked only by tests that exercise all
     * three lookups, keeping Mockito's STRICT_STUBS mode satisfied.
     */
    private void stubHappyPath(CardCrossReference xref, Customer customer, Account account,
            List<Transaction> transactions) {
        when(customerRepository.findById(xref.getXrefCustId())).thenReturn(Optional.of(customer));
        when(accountRepository.findById(xref.getXrefAcctId())).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNum(xref.getXrefCardNum())).thenReturn(transactions);
    }

    // =================================================================================================
    // Phase B — @Nested StatementBodies (text + HTML)
    // =================================================================================================

    /**
     * Verifies the dual-format output of one full {@code 1000-MAINLINE} pass: a non-null
     * {@link AccountStatement} whose identity matches the input cross-reference, a plain-text body
     * carrying the CBSTM03A banners / customer / transaction lines, and an HTML body rendering the same
     * account and transaction data (plus the institution header) as markup.
     */
    @Nested
    @DisplayName("StatementBodies — text + HTML assembly (CBSTM03A 5000/5100/5200/6000)")
    class StatementBodies {

        @Test
        @DisplayName("process returns a non-null statement whose accountId/cardNumber match the xref")
        void returnsNonNullCarrierMatchingXref() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.50"),
                    List.of(txn("TXN0000000000001", "GROCERY STORE PURCHASE", "10.00", CARD_NUM)));

            AccountStatement stmt = processor.process(xref);

            assertThat(stmt).isNotNull();
            assertThat(stmt.accountId()).isEqualTo(ACCT_ID);
            assertThat(stmt.cardNumber()).isEqualTo(CARD_NUM);
        }

        @Test
        @DisplayName("text statement carries header/footer, customer, and transaction lines (CBSTM03A L88/L145)")
        void textBodyCarriesBannersCustomerAndTransactions() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            Transaction grocery = txn("TXN0000000000001", "GROCERY STORE PURCHASE", "10.00", CARD_NUM);
            Transaction fuel = txn("TXN0000000000002", "FUEL STATION", "25.50", CARD_NUM);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.50"), List.of(grocery, fuel));

            AccountStatement stmt = processor.process(xref);

            // L88 'START OF STATEMENT' banner and L145 'END OF STATEMENT' banner bracket the statement.
            assertThat(stmt.textBody())
                    .contains("START OF STATEMENT")
                    .contains("END OF STATEMENT")
                    // ST-NAME composed by 5000-CREATE-STATEMENT (STRING ... DELIMITED BY ' ').
                    .contains(COMPOSED_NAME)
                    .contains(FIRST_NAME)
                    .contains(LAST_NAME)
                    // 6000-WRITE-TRANS detail rows carry each transaction description.
                    .contains("GROCERY STORE PURCHASE")
                    .contains("FUEL STATION");
        }

        @Test
        @DisplayName("HTML statement renders the same account/transaction data as markup, incl. institution (L168/L170)")
        void htmlBodyRendersSameDataAsMarkup() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            Transaction grocery = txn("TXN0000000000001", "GROCERY STORE PURCHASE", "10.00", CARD_NUM);
            Transaction fuel = txn("TXN0000000000002", "FUEL STATION", "25.50", CARD_NUM);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.50"), List.of(grocery, fuel));

            AccountStatement stmt = processor.process(xref);

            assertThat(stmt.htmlBody())
                    .isNotBlank()
                    // HTML structural markup (5100/5200 SET/WRITE chain).
                    .contains("<html")
                    .contains("<table")
                    .contains("<tr")
                    .contains("<td")
                    // CBSTM03A L168/L170: the institution header lives in the HTML statement.
                    .contains("Bank of XYZ")
                    .contains("410 Terry Ave N")
                    // Same customer and transaction data as the text body.
                    .contains(COMPOSED_NAME)
                    .contains("GROCERY STORE PURCHASE")
                    .contains("FUEL STATION")
                    // The same picture-edited amounts appear in the HTML detail cells.
                    .contains("10.00")
                    .contains("25.50");
        }

        @Test
        @DisplayName("both text AND HTML bodies are present for the same account (dual-format requirement)")
        void bothBodiesPresentForSameAccount() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.50"),
                    List.of(txn("TXN0000000000001", "GROCERY STORE PURCHASE", "10.00", CARD_NUM)));

            AccountStatement stmt = processor.process(xref);

            // CBSTM03A writes STMT-FILE and HTML-FILE in lock-step; both must be non-blank and distinct.
            assertThat(stmt.textBody()).isNotBlank();
            assertThat(stmt.htmlBody()).isNotBlank();
            assertThat(stmt.htmlBody()).isNotEqualTo(stmt.textBody());
        }
    }

    // =================================================================================================
    // Phase C — @Nested RunningExpenseTotal (WS-TOTAL-AMT)
    // =================================================================================================

    /**
     * Verifies the {@code WS-TOTAL-AMT} running expense total: summed at scale 2 across the card's
     * transactions ({@code ADD TRNX-AMT TO WS-TOTAL-AMT}, L429), reset to zero at the start of every
     * statement ({@code MOVE ZERO TO WS-TOTAL-AMT}, L325) so totals never leak across accounts, and
     * {@code 0.00} for an account with no transactions.
     */
    @Nested
    @DisplayName("RunningExpenseTotal — WS-TOTAL-AMT accumulation/reset (CBSTM03A L65/L325/L429)")
    class RunningExpenseTotal {

        @Test
        @DisplayName("totalExpense = Σ transaction amounts, scale 2 (L65/L429)")
        void totalIsSumAtScaleTwo() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.50"),
                    List.of(
                            txn("TXN0000000000001", "PURCHASE ONE", "10.00", CARD_NUM),
                            txn("TXN0000000000002", "PURCHASE TWO", "25.50", CARD_NUM),
                            txn("TXN0000000000003", "PURCHASE THREE", "4.50", CARD_NUM)));

            AccountStatement stmt = processor.process(xref);

            // 10.00 + 25.50 + 4.50 = 40.00, preserved at the WS-TOTAL-AMT scale of 2.
            assertThat(stmt.totalExpense()).isEqualByComparingTo("40.00");
            assertThat(stmt.totalExpense().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("running total resets per statement and does NOT leak across accounts (L325)")
        void totalResetsPerStatement() {
            // First statement: account ACCT_ID, transactions summing to 40.00.
            CardCrossReference xref1 = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref1, customer(CUST_ID), account(ACCT_ID, "100.00"),
                    List.of(
                            txn("TXN0000000000001", "PURCHASE ONE", "10.00", CARD_NUM),
                            txn("TXN0000000000002", "PURCHASE TWO", "25.50", CARD_NUM),
                            txn("TXN0000000000003", "PURCHASE THREE", "4.50", CARD_NUM)));

            // Second statement: a DIFFERENT account/card, transactions summing to only 15.00.
            String card2 = "5555444433332222";
            Long cust2 = 200000002L;
            Long acct2 = 2L;
            CardCrossReference xref2 = xref(card2, cust2, acct2);
            stubHappyPath(xref2, customer(cust2), account(acct2, "50.00"),
                    List.of(
                            txn("TXN0000000000004", "PURCHASE FOUR", "5.00", card2),
                            txn("TXN0000000000005", "PURCHASE FIVE", "10.00", card2)));

            // Same processor instance drives both statements, as the batch step would.
            AccountStatement stmt1 = processor.process(xref1);
            AccountStatement stmt2 = processor.process(xref2);

            assertThat(stmt1.totalExpense()).isEqualByComparingTo("40.00");
            // The second total reflects ONLY its own transactions: the running total reset per statement.
            assertThat(stmt2.totalExpense()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("account with no transactions -> total 0.00, statement banners still present")
        void emptyTransactionAccountTotalsZero() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            // findByTranCardNum returns an empty list (no rows matched the card).
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "0.00"), List.of());

            AccountStatement stmt = processor.process(xref);

            // MOVE ZERO TO WS-TOTAL-AMT with no ADD -> zero (scale not asserted: ZERO has scale 0).
            assertThat(stmt.totalExpense()).isEqualByComparingTo("0.00");
            // A statement is still produced with its header/footer banners (no transaction detail rows).
            assertThat(stmt.textBody())
                    .contains("START OF STATEMENT")
                    .contains("END OF STATEMENT");
        }
    }

    // =================================================================================================
    // Phase D — @Nested PictureEditedFormats (the edited numeric fields)
    // =================================================================================================

    /**
     * Verifies the two COBOL numeric-edited monetary fields are reproduced exactly: the current
     * balance {@code ST-CURR-BAL PIC 9(9).99-} (L113, leading zeros retained, fixed two decimals,
     * trailing sign) and the per-transaction amount {@code ST-TRANAMT PIC Z(9).99-} (L137, leading-zero
     * suppression, trailing sign). The production helpers are private, so the edited substrings are
     * asserted via the rendered text body.
     */
    @Nested
    @DisplayName("PictureEditedFormats — 9(9).99- and Z(9).99- edits (CBSTM03A L113/L137)")
    class PictureEditedFormats {

        @Test
        @DisplayName("current balance edited as 9(9).99- (trailing minus, fixed 2 decimals)")
        void currentBalanceNegativeRendersTrailingMinus() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            // Negative balance exercises the trailing-sign position of the 9(9).99- edit.
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "-12.34"), List.of());

            AccountStatement stmt = processor.process(xref);

            // 9(9).99-: nine integer digits with LEADING ZEROS, two decimals, trailing '-' for negative.
            assertThat(stmt.textBody()).contains("000000012.34-");
        }

        @Test
        @DisplayName("positive current balance carries NO trailing minus (sign position is a space)")
        void currentBalancePositiveRendersNoTrailingMinus() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "12.34"), List.of());

            AccountStatement stmt = processor.process(xref);

            // Positive value: same digits, but the trailing sign position is a space, not a '-'.
            assertThat(stmt.textBody()).contains("000000012.34 ");
            assertThat(stmt.textBody()).doesNotContain("000000012.34-");
        }

        @Test
        @DisplayName("transaction amount edited as Z(9).99- (leading-zero suppression; trailing minus)")
        void transactionAmountSuppressesLeadingZerosAndShowsTrailingMinus() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            Transaction positive = txn("TXN0000000000001", "POS PURCHASE", "7.05", CARD_NUM);
            Transaction negative = txn("TXN0000000000002", "REFUND CREDIT", "-3.50", CARD_NUM);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.00"),
                    List.of(positive, negative));

            AccountStatement stmt = processor.process(xref);
            String text = stmt.textBody();

            // Z(9).99-: the small positive amount 7.05 renders with leading zeros SUPPRESSED to spaces
            // (eight spaces ahead of the value), immediately after the COBOL '$' literal in ST-LINE14.
            assertThat(text).contains("$" + " ".repeat(8) + "7.05");
            // It is NOT zero-padded the way the 9(9) (non-Z) edit would render it.
            assertThat(text).doesNotContain("000000007.05");
            // A negative amount carries the trailing '-' sign.
            assertThat(text).contains("3.50-");
        }
    }

    // =================================================================================================
    // Phase E — @Nested MissingReferenceData (FILE STATUS '23' -> fatal)
    // =================================================================================================

    /**
     * Verifies the production-aligned handling of an absent master record. CBSTM03A treats a missing
     * customer or account as a fatal condition (a non-zero {@code WS-M03B-RC} drives
     * {@code 9999-ABEND-PROGRAM}); the migrated processor maps that to a
     * {@link RecordNotFoundException} (VSAM {@code FILE STATUS '23'}). The COBOL read order
     * (customer, then account, then the per-card transaction scan) is preserved, so an earlier failure
     * never reaches the later reads.
     */
    @Nested
    @DisplayName("MissingReferenceData — FILE STATUS '23' fatal + read order (CBSTM03A 2000/3000/4000)")
    class MissingReferenceData {

        @Test
        @DisplayName("missing customer -> RecordNotFoundException, before account/transaction reads")
        void missingCustomerIsFatalAndStopsEarly() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            // 2000-CUSTFILE-GET keyed read misses -> abend -> RecordNotFoundException.
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> processor.process(xref))
                    .isInstanceOf(RecordNotFoundException.class);

            // The customer read fails first, so neither the account read nor the transaction scan runs.
            verify(accountRepository, never()).findById(any());
            verify(transactionRepository, never()).findByTranCardNum(any());
        }

        @Test
        @DisplayName("missing account -> RecordNotFoundException, before the transaction scan")
        void missingAccountIsFatalAndStopsBeforeTransactions() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            // Customer resolves, but the 3000-ACCTFILE-GET keyed read misses.
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> processor.process(xref))
                    .isInstanceOf(RecordNotFoundException.class);

            // The account read fails, so the per-card transaction scan is never reached.
            verify(transactionRepository, never()).findByTranCardNum(any());
        }

        @Test
        @DisplayName("happy path reads customer -> account -> transactions in COBOL order (InOrder)")
        void readsCustomerThenAccountThenTransactions() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.00"),
                    List.of(txn("TXN0000000000001", "PURCHASE", "10.00", CARD_NUM)));

            processor.process(xref);

            // 2000-CUSTFILE-GET -> 3000-ACCTFILE-GET -> 4000-TRNXFILE-GET (WS-TRNX-TABLE replacement).
            InOrder inOrder = inOrder(customerRepository, accountRepository, transactionRepository);
            inOrder.verify(customerRepository).findById(CUST_ID);
            inOrder.verify(accountRepository).findById(ACCT_ID);
            inOrder.verify(transactionRepository).findByTranCardNum(CARD_NUM);
        }
    }

    // =================================================================================================
    // Phase F — @Nested OmittedAndDeferredBehavior
    // =================================================================================================

    /**
     * Documents and verifies the behaviour that is intentionally NOT this processor's responsibility.
     * The COBOL file-service helper {@code CBSTM03B} (generic OPEN/READ/READ-K/CLOSE) is replaced by the
     * three injected repositories, so there is no file service to mock or assert. Persisting the
     * assembled statement (the sequential statement file / S3 objects) belongs to the statement
     * <em>writer</em>; the processor only builds the {@link AccountStatement} carrier and saves nothing.
     */
    @Nested
    @DisplayName("OmittedAndDeferredBehavior — CBSTM03B replaced by repositories; persistence is the writer's job")
    class OmittedAndDeferredBehavior {

        @Test
        @DisplayName("processor performs NO persistence (no save/saveAll) — writing is the writer's job")
        void processorPerformsNoPersistence() {
            CardCrossReference xref = xref(CARD_NUM, CUST_ID, ACCT_ID);
            stubHappyPath(xref, customer(CUST_ID), account(ACCT_ID, "100.00"),
                    List.of(txn("TXN0000000000001", "PURCHASE", "10.00", CARD_NUM)));

            processor.process(xref);

            // The processor only assembles the carrier; statement persistence (text/HTML -> S3) is the
            // StatementWriter's responsibility (asserted in integration/batch, not here). No file service
            // is mocked because CBSTM03B's file I/O is served by the repositories above.
            verify(customerRepository, never()).save(any(Customer.class));
            verify(accountRepository, never()).save(any(Account.class));
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(transactionRepository, never()).saveAll(any());
        }
    }
}
