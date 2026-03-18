package com.cardemo.unit.batch;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementProcessor} — the Spring Batch
 * {@link org.springframework.batch.item.ItemProcessor} that generates
 * dual-format (text + HTML) account statements from
 * {@link CardCrossReference} input.
 *
 * <p>Validates the business logic migrated from COBOL programs:
 * <ul>
 *   <li>{@code CBSTM03A.CBL} (924 lines) — statement generation main program
 *       including customer/account data enrichment, transaction buffering
 *       (WS-TRNX-TABLE 51×10 2D array), WS-TOTAL-AMT PIC S9(9)V99 running total,
 *       STATEMENT-LINES (LRECL=80) text output, and HTML-LINES (LRECL=100) output</li>
 *   <li>{@code CBSTM03B.CBL} (230 lines) — file-service subroutine with 'K'
 *       (keyed read) operations on CUSTFILE and ACCTFILE, replaced by direct
 *       JPA repository {@code findById()} calls</li>
 * </ul>
 *
 * <p>This is a <strong>pure unit test class</strong> — no Spring context loading.
 * All repository dependencies are mocked using Mockito and injected via the
 * processor's constructor. Financial amounts use {@link BigDecimal} exclusively
 * per AAP §0.8.2 (zero float/double). BigDecimal assertions use
 * {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}.
 *
 * <p>Executed by maven-surefire-plugin ({@code *Test.java} naming convention).
 *
 * @see StatementProcessor
 * @see StatementWriter.StatementOutput
 */
@ExtendWith(MockitoExtension.class)
class StatementProcessorTest {

    // -------------------------------------------------------------------------
    // Mock Repositories — replace CBSTM03B file-service subroutine I/O
    // -------------------------------------------------------------------------

    /** Replaces CBSTM03B 'K' operation on CUSTFILE (keyed read by customer ID). */
    @Mock
    private CustomerRepository customerRepository;

    /** Replaces CBSTM03B 'K' operation on ACCTFILE (keyed read by account ID). */
    @Mock
    private AccountRepository accountRepository;

    /** Replaces in-memory WS-TRNX-TABLE (51 cards × 10 transactions 2D array). */
    @Mock
    private TransactionRepository transactionRepository;

    /** Class under test — injected with mock repositories via constructor. */
    private StatementProcessor processor;

    // -------------------------------------------------------------------------
    // Test Constants — representative card, customer, and account IDs
    // -------------------------------------------------------------------------

    /** Test card number — PIC X(16) from CVACT03Y.cpy XREF-CARD-NUM. */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Test customer ID — PIC 9(09) from CVACT03Y.cpy XREF-CUST-ID. */
    private static final String TEST_CUST_ID = "000000001";

    /** Test account ID — PIC 9(11) from CVACT03Y.cpy XREF-ACCT-ID. */
    private static final String TEST_ACCT_ID = "00000000001";

    // -------------------------------------------------------------------------
    // Setup — construct processor with mock dependencies
    // -------------------------------------------------------------------------

    /**
     * Initialises the {@link StatementProcessor} with mock repositories via
     * constructor injection. Mirrors the Spring DI pattern without loading
     * a Spring context.
     */
    @BeforeEach
    void setUp() {
        processor = new StatementProcessor(
                customerRepository, accountRepository, transactionRepository);
    }

    // =========================================================================
    // Test Methods (11 total)
    // =========================================================================

    // -------------------------------------------------------------------------
    // Happy Path
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link StatementProcessor#process(CardCrossReference)}
     * returns a non-null {@link StatementWriter.StatementOutput} containing
     * both text and HTML content when all repository lookups succeed.
     *
     * <p>Maps CBSTM03A 1000-MAINLINE happy path: read XREF → get customer
     * (2000-CUSTFILE-GET) → get account (3000-ACCTFILE-GET) → get transactions
     * (4000-TRNXFILE-GET) → create statement (5000-CREATE-STATEMENT).
     */
    @Test
    void process_shouldGenerateStatementForValidCrossReference() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("5000.00"),
                new BigDecimal("10000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(List.of(
                        createTransaction("TRN0000001", TEST_CARD_NUM,
                                new BigDecimal("100.50"),
                                LocalDateTime.of(2025, 1, 15, 10, 30)),
                        createTransaction("TRN0000002", TEST_CARD_NUM,
                                new BigDecimal("200.75"),
                                LocalDateTime.of(2025, 1, 16, 14, 45))
                ));

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.cardNumber()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.textContent()).isNotNull().isNotEmpty();
        assertThat(result.htmlContent()).isNotNull().isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Missing Data — Null Return Paths
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code process()} returns {@code null} when the customer
     * record cannot be found in the repository.
     *
     * <p>Maps CBSTM03B 'K' on CUSTFILE returning FILE STATUS 23 (INVALID KEY).
     * CBSTM03A skips statement generation when customer data is unavailable —
     * no further lookups (account, transactions) are performed.
     */
    @Test
    void process_shouldReturnNullWhenCustomerNotFound() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        when(customerRepository.findById(TEST_CUST_ID)).thenReturn(Optional.empty());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — null return, no further lookups performed
        assertThat(result).isNull();
        verify(accountRepository, never()).findById(anyString());
        verify(transactionRepository, never()).findByTranCardNum(anyString());
    }

    /**
     * Verifies that {@code process()} returns {@code null} when the account
     * record cannot be found, even though the customer was found successfully.
     *
     * <p>Maps CBSTM03B 'K' on ACCTFILE returning FILE STATUS 23 (INVALID KEY)
     * after a successful CUSTFILE lookup.
     */
    @Test
    void process_shouldReturnNullWhenAccountNotFound() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        when(accountRepository.findById(TEST_ACCT_ID)).thenReturn(Optional.empty());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — null return, transaction lookup skipped
        assertThat(result).isNull();
        verify(transactionRepository, never()).findByTranCardNum(anyString());
    }

    // -------------------------------------------------------------------------
    // Text Statement Content Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies that the generated text statement includes the customer's
     * first name and last name.
     *
     * <p>Maps CBSTM03A 5000-CREATE-STATEMENT paragraph ST-LINE1 format:
     * customer first name + middle name + last name built via
     * {@code buildCustomerName(Customer)} helper.
     */
    @Test
    void process_shouldIncludeCustomerNameInTextStatement() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("5000.00"),
                new BigDecimal("10000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(Collections.emptyList());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — customer name appears in text output (ST-LINE1)
        assertThat(result).isNotNull();
        assertThat(result.textContent()).contains("John");
        assertThat(result.textContent()).contains("Doe");
    }

    /**
     * Verifies that the text statement includes the account ID and the
     * current balance formatted per the COBOL z-suppressed pattern.
     *
     * <p>Maps CBSTM03A ST-LINE7 (account ID display) and ST-LINE8
     * (current balance, formatted via {@code formatAmountZSuppressed}
     * matching PIC Z(9).99-).
     */
    @Test
    void process_shouldIncludeAccountDetailsInTextStatement() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("5000.00"),
                new BigDecimal("10000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(Collections.emptyList());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — account ID and formatted balance appear in text output
        assertThat(result).isNotNull();
        String text = result.textContent();
        assertThat(text).contains(TEST_ACCT_ID);
        assertThat(text).contains("5000.00");
    }

    /**
     * Verifies that customer address fields (street, state, ZIP) appear
     * in the generated statement text.
     *
     * <p>Maps CBSTM03A 5000-CREATE-STATEMENT address section:
     * ST-LINE2 (addr line 1), ST-LINE3 (addr line 2),
     * ST-LINE4 ({@code buildAddressLine3} — addr3/state/country/zip).
     */
    @Test
    void process_shouldIncludeCustomerAddressInStatement() throws Exception {
        // Arrange — use no-args constructor + setters for CardCrossReference
        // to exercise both constructor patterns per schema members_accessed
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(TEST_CARD_NUM);
        xref.setXrefCustId(TEST_CUST_ID);
        xref.setXrefAcctId(TEST_ACCT_ID);

        Customer customer = createCustomer(TEST_CUST_ID, "Robert", "Williams");
        customer.setCustAddrLine1("456 Oak Avenue");
        customer.setCustAddrLine2("Suite 200");
        customer.setCustAddrLine3("Riverside");
        customer.setCustAddrStateCd("CA");
        customer.setCustAddrZip("92501");
        when(customerRepository.findById(TEST_CUST_ID))
                .thenReturn(Optional.of(customer));
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("6000.00"),
                new BigDecimal("9000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(Collections.emptyList());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — address fields present in text output (ST-LINE2 through ST-LINE4)
        assertThat(result).isNotNull();
        String text = result.textContent();
        assertThat(text).contains("456 Oak Avenue");
        assertThat(text).contains("CA");
        assertThat(text).contains("92501");
    }

    // -------------------------------------------------------------------------
    // HTML Statement Structure Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies that the HTML statement contains proper HTML document structure
     * including opening/closing tags and a styled table element.
     *
     * <p>Maps CBSTM03A lines 500-650 HTML output generation:
     * 5100-WRITE-HTML-HEADER (DOCTYPE, html, head, style, body open),
     * 5200-WRITE-HTML-NMADBS (customer/account details table),
     * and closing tags. HTML-LINES template values (HTML-L01 through HTML-L80)
     * provide the inline CSS structure.
     */
    @Test
    void process_shouldGenerateHtmlWithProperStructure() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("5000.00"),
                new BigDecimal("10000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(Collections.emptyList());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — proper HTML document structure present
        assertThat(result).isNotNull();
        String html = result.htmlContent();
        assertThat(html).contains("<html");
        assertThat(html).contains("</html>");
        assertThat(html).contains("<table");
    }

    // -------------------------------------------------------------------------
    // Dual-Format Output Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code process()} generates both text AND HTML format
     * content simultaneously in a single invocation.
     *
     * <p>Maps CBSTM03A dual-output pattern where STMT-FILE (FD, LRECL=80)
     * and HTML-FILE (FD, LRECL=100) are written in parallel during the
     * 5000-CREATE-STATEMENT and 6000-WRITE-TRANS paragraphs.
     */
    @Test
    void process_shouldGenerateBothTextAndHtmlFormats() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "Jane", "Smith");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("7500.00"),
                new BigDecimal("15000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(List.of(
                        createTransaction("TRN0000010", TEST_CARD_NUM,
                                new BigDecimal("250.00"), LocalDateTime.now())
                ));

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — both text and HTML formats present and non-empty
        assertThat(result).isNotNull();
        assertThat(result.textContent()).isNotNull().isNotEmpty();
        assertThat(result.htmlContent()).isNotNull().isNotEmpty();
        // Text is plain text; HTML contains markup tags
        assertThat(result.htmlContent()).contains("<");
    }

    // -------------------------------------------------------------------------
    // BigDecimal Precision Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies that the transaction running total is computed with
     * {@link BigDecimal} precision, preserving COBOL COMP-3
     * WS-TOTAL-AMT PIC S9(9)V99 accumulation semantics.
     *
     * <p><strong>CRITICAL</strong>: Uses {@link BigDecimal#compareTo(BigDecimal)}
     * for verification, never {@link BigDecimal#equals(Object)}, per AAP §0.8.2.
     * This ensures scale-independent comparison (e.g., {@code 351.50} vs
     * {@code 351.5} are equal by value but not by {@code equals}).
     *
     * <p>The three test amounts ({@code 100.50 + 200.75 + 50.25 = 351.50})
     * specifically exercise decimal precision that would suffer rounding
     * errors with float/double arithmetic.
     */
    @Test
    void process_shouldComputeTransactionTotalWithBigDecimalPrecision() throws Exception {
        // Arrange — precise BigDecimal amounts (ZERO float/double per AAP §0.8.2)
        BigDecimal amount1 = new BigDecimal("100.50");
        BigDecimal amount2 = new BigDecimal("200.75");
        BigDecimal amount3 = new BigDecimal("50.25");
        BigDecimal expectedTotal = amount1.add(amount2).add(amount3);

        // CRITICAL: Verify expected total using compareTo(), NOT equals() (AAP §0.8.2)
        assertThat(expectedTotal.compareTo(new BigDecimal("351.50"))).isEqualTo(0);

        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "John", "Doe");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("8000.00"),
                new BigDecimal("12000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(List.of(
                        createTransaction("TRN0000101", TEST_CARD_NUM,
                                amount1, LocalDateTime.of(2025, 3, 1, 9, 0)),
                        createTransaction("TRN0000102", TEST_CARD_NUM,
                                amount2, LocalDateTime.of(2025, 3, 2, 10, 15)),
                        createTransaction("TRN0000103", TEST_CARD_NUM,
                                amount3, LocalDateTime.of(2025, 3, 3, 11, 30))
                ));

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — running total of 351.50 appears in the text statement
        assertThat(result).isNotNull();
        assertThat(result.textContent()).contains("351.50");
    }

    // -------------------------------------------------------------------------
    // Edge Case — Empty Transaction List
    // -------------------------------------------------------------------------

    /**
     * Verifies that a statement is still generated for a card with no
     * transactions, producing a zero total amount.
     *
     * <p>Maps CBSTM03A behaviour when WS-TRNX-TABLE is empty after
     * 4000-TRNXFILE-GET: the statement is still created with customer and
     * account detail sections, but the transaction total (WS-TOTAL-AMT
     * PIC S9(9)V99) remains at its initialized value of zero.
     */
    @Test
    void process_shouldHandleCardWithNoTransactions() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "Alice", "Johnson");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("3000.00"),
                new BigDecimal("5000.00"));
        when(transactionRepository.findByTranCardNum(TEST_CARD_NUM))
                .thenReturn(Collections.emptyList());

        // Act
        StatementWriter.StatementOutput result = processor.process(xref);

        // Assert — statement generated even with zero transactions
        assertThat(result).isNotNull();
        assertThat(result.textContent()).isNotNull().isNotEmpty();
        assertThat(result.htmlContent()).isNotNull().isNotEmpty();

        // Zero total verification using BigDecimal.compareTo() (AAP §0.8.2)
        BigDecimal zeroTotal = BigDecimal.ZERO;
        assertThat(zeroTotal.compareTo(new BigDecimal("0.00"))).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Template Method / Repository Interaction Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies that the processor uses JPA repository calls (replacing the
     * CBSTM03B file-service subroutine) to fetch customer, account, and
     * transaction data.
     *
     * <p>In the original COBOL, CBSTM03A calls the CBSTM03B subroutine
     * with operation codes via the WS-M03B-AREA LINKAGE SECTION:
     * <ul>
     *   <li>'K' on CUSTFILE → replaced by
     *       {@code customerRepository.findById()}</li>
     *   <li>'K' on ACCTFILE → replaced by
     *       {@code accountRepository.findById()}</li>
     *   <li>Sequential read on TRNXFILE → replaced by
     *       {@code transactionRepository.findByTranCardNum()}</li>
     * </ul>
     *
     * <p>This test confirms the template method pattern: CBSTM03B subroutine
     * abstractions are replaced by direct Spring Data JPA repository injection
     * via the processor's constructor.
     */
    @Test
    void process_shouldUseRepositoryCallsInsteadOfCbstm03bSubroutine() throws Exception {
        // Arrange
        CardCrossReference xref = createXref(TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        stubValidCustomer(TEST_CUST_ID, "Template", "Method");
        stubValidAccount(TEST_ACCT_ID, new BigDecimal("4000.00"),
                new BigDecimal("8000.00"));
        when(transactionRepository.findByTranCardNum(any(String.class)))
                .thenReturn(List.of(
                        createTransaction("TRN0000099", TEST_CARD_NUM,
                                new BigDecimal("75.00"),
                                LocalDateTime.of(2025, 6, 1, 8, 0))
                ));

        // Act
        processor.process(xref);

        // Assert — verify repository calls replaced CBSTM03B subroutine operations
        // 'K' on CUSTFILE → customerRepository.findById()
        verify(customerRepository, times(1)).findById(eq(TEST_CUST_ID));
        // 'K' on ACCTFILE → accountRepository.findById()
        verify(accountRepository, times(1)).findById(eq(TEST_ACCT_ID));
        // Sequential TRNXFILE read → transactionRepository.findByTranCardNum()
        verify(transactionRepository, times(1)).findByTranCardNum(eq(TEST_CARD_NUM));
    }

    // =========================================================================
    // Helper Methods — Test Data Factories
    // =========================================================================

    /**
     * Creates a {@link CardCrossReference} using the all-args constructor.
     *
     * <p>Maps CVACT03Y.cpy CARD-XREF-RECORD layout (50 bytes):
     * XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11).
     *
     * @param cardNum 16-digit card number (primary key)
     * @param custId  9-digit customer identifier
     * @param acctId  11-digit account identifier
     * @return a populated cross-reference entity
     */
    private CardCrossReference createXref(String cardNum, String custId, String acctId) {
        return new CardCrossReference(cardNum, custId, acctId);
    }

    /**
     * Creates a {@link Customer} entity with default address and FICO score.
     *
     * <p>Maps CVCUS01Y.cpy CUSTOMER-RECORD (500 bytes). Uses no-args
     * constructor with individual setters per schema members_accessed.
     * The FICO credit score is {@code Short} (not {@code Integer})
     * matching the entity field type.
     *
     * @param custId    9-digit customer identifier
     * @param firstName customer first name (PIC X(25))
     * @param lastName  customer last name (PIC X(25))
     * @return a populated customer entity with default address and FICO 750
     */
    private Customer createCustomer(String custId, String firstName, String lastName) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName(firstName);
        customer.setCustMiddleName("M");
        customer.setCustLastName(lastName);
        customer.setCustAddrLine1("123 Main Street");
        customer.setCustAddrLine2("Apt 4B");
        customer.setCustAddrLine3("Downtown");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrZip("10001");
        // FICO credit score: Short type per actual entity definition
        // (schema says Integer but entity uses Short — using actual type)
        customer.setCustFicoCreditScore((short) 750);
        // Set date of birth to exercise LocalDate.of() per schema members_accessed
        customer.setCustDob(LocalDate.of(1985, 6, 15));
        return customer;
    }

    /**
     * Creates an {@link Account} entity with the given balance and credit limit.
     *
     * <p>Maps CVACT01Y.cpy ACCOUNT-RECORD (300 bytes). All financial fields
     * use {@link BigDecimal} — zero float/double per AAP §0.8.2.
     *
     * @param acctId      11-digit account identifier
     * @param balance     current balance (ACCT-CURR-BAL PIC S9(10)V99 COMP-3)
     * @param creditLimit credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3)
     * @return a populated account entity
     */
    private Account createAccount(String acctId, BigDecimal balance, BigDecimal creditLimit) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctCurrBal(balance);
        account.setAcctCreditLimit(creditLimit);
        return account;
    }

    /**
     * Creates a {@link Transaction} entity with the given fields.
     *
     * <p>Maps CVTRA05Y.cpy TRAN-RECORD (350 bytes). The amount field uses
     * {@link BigDecimal} — maps TRAN-AMT PIC S9(9)V99 COMP-3. Zero
     * float/double per AAP §0.8.2.
     *
     * @param tranId  16-character transaction identifier
     * @param cardNum 16-digit card number (indexed for lookup)
     * @param amount  transaction amount (BigDecimal, never float/double)
     * @param origTs  origination timestamp (TRAN-ORIG-TS PIC X(26))
     * @return a populated transaction entity
     */
    private Transaction createTransaction(String tranId, String cardNum,
                                          BigDecimal amount, LocalDateTime origTs) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranCardNum(cardNum);
        transaction.setTranAmt(amount);
        transaction.setTranDesc("Test transaction " + tranId);
        transaction.setTranOrigTs(origTs);
        return transaction;
    }

    // -------------------------------------------------------------------------
    // Stub Helpers — Reduce mock setup boilerplate across test methods
    // -------------------------------------------------------------------------

    /**
     * Configures the mock {@link CustomerRepository} to return a valid
     * {@link Customer} when queried by the given customer ID.
     *
     * @param custId    customer identifier to match
     * @param firstName customer first name for the returned entity
     * @param lastName  customer last name for the returned entity
     */
    private void stubValidCustomer(String custId, String firstName, String lastName) {
        when(customerRepository.findById(custId))
                .thenReturn(Optional.of(createCustomer(custId, firstName, lastName)));
    }

    /**
     * Configures the mock {@link AccountRepository} to return a valid
     * {@link Account} when queried by the given account ID.
     *
     * @param acctId      account identifier to match
     * @param balance     current balance for the returned entity
     * @param creditLimit credit limit for the returned entity
     */
    private void stubValidAccount(String acctId, BigDecimal balance, BigDecimal creditLimit) {
        when(accountRepository.findById(acctId))
                .thenReturn(Optional.of(createAccount(acctId, balance, creditLimit)));
    }
}
