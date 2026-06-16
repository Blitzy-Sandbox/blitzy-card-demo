package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.StatementProcessor;
import com.carddemo.batch.processors.StatementProcessor.StatementResult;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link StatementProcessor}, the Spring Batch
 * {@link org.springframework.batch.item.ItemProcessor} that renders one customer account
 * statement in two variants (plain text and HTML) from a single aggregated data set.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * {@code app/cbl/CBSTM03A.CBL} is the mainframe statement-generation main program and
 * {@code app/cbl/CBSTM03B.CBL} its generic file-access subroutine; the customer record layout
 * is {@code app/cpy/CVCUS01Y.cpy}. The job context {@code app/jcl/CREASTMT.JCL} produces a text
 * statement ({@code STMTFILE}, {@code PIC X(80)}) and an HTML statement ({@code HTMLFILE},
 * {@code PIC X(100)}). These tests pin the migrated behaviour to the production contract: the
 * Template-Method dual rendering (text and HTML produced from the same {@code StatementData}),
 * the {@code 5000-CREATE-STATEMENT} customer-name composition, the current-balance
 * passthrough, the {@link BigDecimal} statement total at scale&nbsp;2, the
 * {@link RecordNotFoundException} raised on a cross-reference that points at a missing customer
 * (the COBOL {@code 9999-ABEND-PROGRAM} keyed-read failure path), the read-only nature of the
 * processor, and the per-statement {@code carddemo.batch.records.processed} increment (AAP
 * sections 0.4.3, 0.7.1, 0.8.2).</p>
 *
 * <p>This is a pure-JVM unit test: the four repository collaborators are Mockito mocks and a
 * real {@link SimpleMeterRegistry} records the metric. No Spring context, Testcontainers, or
 * LocalStack is involved. The production constructor takes no {@code CardRepository}, so none is
 * mocked here. Monetary assertions use {@code isEqualByComparingTo} (never scale-sensitive
 * {@code BigDecimal.equals}); structural assertions check presence rather than column-exact
 * formatting, which is an integration concern.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor - Template-Method dual text/HTML statement rendering")
class StatementProcessorTest {

    /** 16-digit card number shared by the cross-reference and the in-scope transactions. */
    private static final String CARD_NUM = "1234567890123456";

    /** Account id of the statement subject ({@code XREF-ACCT-ID} / {@code ACCT-ID}). */
    private static final Long ACCT_ID = 1L;

    /** Customer id resolved from the cross-reference ({@code XREF-CUST-ID}). */
    private static final Long CUST_ID = 7L;

    @Mock
    private CardCrossReferenceRepository xrefRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    /** Real registry: the processor records {@code carddemo.batch.records.processed} on it. */
    private SimpleMeterRegistry registry;

    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new StatementProcessor(
                xrefRepository,
                customerRepository,
                transactionRepository,
                accountRepository,
                registry);
    }

    // -- Phase 2: dual rendering from one shared data set (Template Method) -----------------------

    @Test
    @DisplayName("process() renders both a text and an HTML statement from the same data")
    void producesBothTextAndHtmlStatements() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        assertThat(result.textStatement()).isNotBlank();
        assertThat(result.htmlStatement()).isNotBlank();
        // The two renderers emit distinct layouts (80-col text vs. 100-col HTML table).
        assertThat(result.textStatement()).isNotEqualTo(result.htmlStatement());
    }

    @Test
    @DisplayName("HTML variant wraps lines in <p> tags and carries HTML scaffolding; text does not")
    void htmlVariant_wrapsLinesInParagraphTags() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        assertThat(result.htmlStatement())
                .contains("<p>")
                .contains("</p>")
                .contains("<html")
                .contains("<table");
        // The plain-text variant is the COBOL ST-LINE0..ST-LINE15 layout, never HTML.
        assertThat(result.textStatement()).doesNotContain("<p>");
    }

    @Test
    @DisplayName("Both variants carry the customer name (one shared data set feeds both)")
    void bothVariantsContainCustomerName() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        assertThat(result.textStatement()).contains("JANE").contains("DOE");
        assertThat(result.htmlStatement()).contains("JANE").contains("DOE");
    }

    // -- Phase 3: customer name, current balance, statement total ---------------------------------

    @Test
    @DisplayName("Customer name is composed first + middle + last (5000-CREATE-STATEMENT)")
    void composesCustomerName() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        // composeName joins the leading token of each name part with single spaces, then strip().
        assertThat(result.customerName()).isEqualTo("JANE Q DOE");
    }

    @Test
    @DisplayName("Current balance passes through the account balance at scale 2")
    void currentBalance_passesThroughAccountBalance() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        assertThat(result.currentBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("Statement total is the BigDecimal sum of transaction amounts at scale 2")
    void total_isSumScale2() {
        Account account = stubHappyPath();

        StatementResult result = processor.process(account);

        // 10.00 + 20.00 = 30.00 accumulated in BigDecimal; the text statement renders the total.
        assertThat(result.textStatement()).contains("30.00");
    }

    // -- Phase 4: missing customer (fatal) and read-only guarantee --------------------------------

    @Test
    @DisplayName("A cross-reference pointing at a missing customer raises RecordNotFoundException")
    void missingCustomer_throwsRecordNotFound() {
        Account account = account(ACCT_ID, "250.00");
        when(xrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(ACCT_ID, CUST_ID, CARD_NUM)));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(account))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Customer");
    }

    @Test
    @DisplayName("Processor is read-only: it never persists to any repository")
    void readsOnly_noPersistence() {
        Account account = stubHappyPath();

        processor.process(account);

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // -- Phase 5: processed-records metric --------------------------------------------------------

    @Test
    @DisplayName("Each produced statement increments carddemo.batch.records.processed once")
    void incrementsProcessedCounter() {
        Account account = stubHappyPath();

        processor.process(account);

        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count())
                .isEqualTo(1.0);
    }

    // -- Test fixtures ----------------------------------------------------------------------------

    /**
     * Wires the common happy-path stubbing: one cross-reference for {@link #ACCT_ID} linking
     * customer {@link #CUST_ID} and card {@link #CARD_NUM}, an existing customer named
     * {@code JANE Q DOE}, and two transactions on that card ({@code 10.00} and {@code 20.00}).
     * The account re-read ({@code accountRepository.findById}) is intentionally left unstubbed so
     * Mockito returns an empty {@link Optional}, exercising the production {@code orElse(account)}
     * fallback to the supplied row.
     *
     * @return the account passed to {@code process(...)} (balance {@code 250.00})
     */
    private Account stubHappyPath() {
        Account account = account(ACCT_ID, "250.00");
        when(xrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(ACCT_ID, CUST_ID, CARD_NUM)));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(customer(CUST_ID, "JANE", "Q", "DOE")));
        when(transactionRepository.findAll()).thenReturn(List.of(
                tran("0000000000000001", CARD_NUM, "10.00"),
                tran("0000000000000002", CARD_NUM, "20.00")));
        return account;
    }

    /**
     * Builds an {@link Account} with the fields the processor reads.
     *
     * @param id      the account id ({@code ACCT-ID})
     * @param balance the current balance ({@code ACCT-CURR-BAL}) as a decimal string
     * @return a new account row
     */
    private Account account(Long id, String balance) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctCurrBal(new BigDecimal(balance));
        return account;
    }

    /**
     * Builds a {@link CardCrossReference} linking a card to its account and owning customer.
     *
     * @param acctId  the account id ({@code XREF-ACCT-ID})
     * @param custId  the customer id ({@code XREF-CUST-ID})
     * @param cardNum the card number ({@code XREF-CARD-NUM})
     * @return a new cross-reference row
     */
    private CardCrossReference xref(Long acctId, Long custId, String cardNum) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefAcctId(acctId);
        xref.setXrefCustId(custId);
        xref.setXrefCardNum(cardNum);
        return xref;
    }

    /**
     * Builds a {@link Customer} from the {@code CVCUS01Y} layout. Address and FICO fields are
     * populated with realistic values so the renderers exercise the address and basic-details
     * blocks, but only the name parts are asserted on.
     *
     * @param id     the customer id ({@code CUST-ID})
     * @param first  the first name ({@code CUST-FIRST-NAME})
     * @param middle the middle name ({@code CUST-MIDDLE-NAME})
     * @param last   the last name ({@code CUST-LAST-NAME})
     * @return a new customer row
     */
    private Customer customer(Long id, String first, String middle, String last) {
        Customer customer = new Customer();
        customer.setCustId(id);
        customer.setCustFirstName(first);
        customer.setCustMiddleName(middle);
        customer.setCustLastName(last);
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
     * Builds a {@link Transaction} on the given card with the supplied amount.
     *
     * @param id      the transaction id ({@code TRAN-ID})
     * @param cardNum the card number ({@code TRAN-CARD-NUM}) used to group the statement
     * @param amount  the transaction amount ({@code TRAN-AMT}) as a decimal string
     * @return a new transaction row
     */
    private Transaction tran(String id, String cardNum, String amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(id);
        transaction.setTranCardNum(cardNum);
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setTranDesc("PURCHASE - GROCERY STORE");
        return transaction;
    }
}
