package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.StatementProcessor;
import com.carddemo.batch.processors.StatementProcessor.StatementResult;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link StatementProcessor}, focused on the CP3 statement-aggregation contract.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the processor
 * migrates {@code app/cbl/CBSTM03A.CBL} (statement generation main), whose {@code 4000-TRNXFILE-GET}
 * gathers a card's transactions in {@code TRNXFILE} (card + transaction-id) key order. The Java
 * design is account-driven (one statement per account, aggregating the account's cross-referenced
 * cards); for the migration fixtures the {@code cardxref} data is strictly one card per account, so
 * account-driven aggregation is byte-equivalent to the card-driven COBOL.</p>
 *
 * <p>These tests pin the N+1 remediation: the processor must push the card-set filter down to the
 * database via {@link TransactionRepository#findByTranCardNumIn(Collection)} (a single indexed
 * {@code IN} query) and must never perform a per-account {@code findAll()} full-table scan. They
 * also verify multi-card aggregation, the preserved card-then-id ordering, and the {@code BigDecimal}
 * total.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor — statement aggregation (CBSTM03A)")
class StatementProcessorTest {

    private static final Long ACCOUNT_ID = 12345678901L;
    private static final Long CUSTOMER_ID = 99L;
    private static final String CARD_ONE = "4111111111111111";
    private static final String CARD_TWO = "4222222222222222";

    /** Stored-XSS probes: each carries HTML metacharacters that must be escaped in HTML output. */
    private static final String TXN_PAYLOAD = "<script>alert(1)</script>";
    private static final String NAME_PAYLOAD = "<b>EVIL</b>";
    private static final String ADDR_PAYLOAD = "<img src='x'>&\"";

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StatementProcessor(
                cardCrossReferenceRepository,
                customerRepository,
                transactionRepository,
                accountRepository,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("aggregates multiple cards under one account via a single IN query (never findAll)")
    void aggregatesMultipleCardsViaInQuery() {
        final Account account = account(new BigDecimal("250.00"));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of(xref(CARD_ONE), xref(CARD_TWO)));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        // Returned deliberately unordered to prove the processor imposes the card-then-id order.
        when(transactionRepository.findByTranCardNumIn(anyCollection())).thenReturn(List.of(
                transaction(CARD_TWO, "0000000000000002", new BigDecimal("25.00"), "CARD2 TXN"),
                transaction(CARD_ONE, "0000000000000005", new BigDecimal("50.00"), "CARD1 LATE"),
                transaction(CARD_ONE, "0000000000000001", new BigDecimal("100.00"), "CARD1 EARLY")));

        final StatementResult result = processor.process(account);

        // N+1 remediation: the card-set filter is pushed to the database; no full-table scan.
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<String>> cardsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository).findByTranCardNumIn(cardsCaptor.capture());
        verify(transactionRepository, never()).findAll();
        assertThat(cardsCaptor.getValue()).containsExactly(CARD_ONE, CARD_TWO);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);

        // Card-then-id ordering preserved across the two cards.
        final String text = result.textStatement();
        final int early = text.indexOf("0000000000000001");
        final int late = text.indexOf("0000000000000005");
        final int card2 = text.indexOf("0000000000000002");
        assertThat(early).isGreaterThanOrEqualTo(0);
        assertThat(early).isLessThan(late);
        assertThat(late).isLessThan(card2);

        // BigDecimal total = 100.00 + 50.00 + 25.00 = 175.00 (rendered PIC Z(9).99-).
        assertThat(text).contains("175.00");
    }

    @Test
    @DisplayName("skips the transaction query when the account's cross-references carry no card number")
    void skipsQueryWhenNoCardNumbers() {
        final Account account = account(new BigDecimal("0.00"));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of(xref(null)));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        final StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        // No card numbers -> no query at all (and certainly no full-table scan).
        verify(transactionRepository, never()).findByTranCardNumIn(anyCollection());
        verify(transactionRepository, never()).findAll();
        // Empty statement totals zero.
        assertThat(result.textStatement()).contains("0.00");
    }

    @Test
    @DisplayName("returns null and queries nothing when the account has no cross-references")
    void returnsNullWhenNoCrossReferences() {
        final Account account = account(new BigDecimal("0.00"));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        final StatementResult result = processor.process(account);

        assertThat(result).isNull();
        verify(transactionRepository, never()).findByTranCardNumIn(anyCollection());
        verify(transactionRepository, never()).findAll();
        verify(customerRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("HTML statement escapes malicious customer/account/transaction text (stored-XSS guard)")
    void htmlStatementEscapesMaliciousText() {
        final Account account = account(new BigDecimal("0.00"));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of(xref(CARD_ONE)));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(maliciousCustomer()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumIn(anyCollection())).thenReturn(List.of(
                transaction(CARD_ONE, "0000000000000001", new BigDecimal("10.00"), TXN_PAYLOAD)));

        final StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        final String html = result.htmlStatement();

        // Every HTML metacharacter from injected data is rendered as an entity reference, never as
        // active markup: the script payload, the name payload and all five characters of the
        // address payload (< > & ' ") are escaped.
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;b&gt;EVIL&lt;/b&gt;");
        assertThat(html).contains("&lt;img");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&#39;");
        assertThat(html).contains("&quot;");

        // No raw attacker-controlled markup survives into the HTML object written to S3: with the
        // angle brackets escaped, none of the injected tags can be parsed as active elements.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("<b>EVIL</b>");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    @DisplayName("plain-text statement keeps raw bytes for the same input (HTML escaping must not leak into text — Gate 1)")
    void textStatementPreservesRawMarkup() {
        final Account account = account(new BigDecimal("0.00"));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of(xref(CARD_ONE)));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(maliciousCustomer()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumIn(anyCollection())).thenReturn(List.of(
                transaction(CARD_ONE, "0000000000000001", new BigDecimal("10.00"), TXN_PAYLOAD)));

        final StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        final String text = result.textStatement();

        // The fixed-width text variant is byte-equivalent to the COBOL baseline: it emits the raw
        // characters verbatim and performs NO HTML escaping (no entity references appear).
        assertThat(text).contains(TXN_PAYLOAD);
        assertThat(text).contains("<b>EVIL</b>");
        assertThat(text).doesNotContain("&lt;");
        assertThat(text).doesNotContain("&amp;");
        assertThat(text).doesNotContain("&#39;");
    }

    private static Account account(final BigDecimal currentBalance) {
        final Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctCurrBal(currentBalance);
        return account;
    }

    private static CardCrossReference xref(final String cardNumber) {
        final CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefCustId(CUSTOMER_ID);
        xref.setXrefAcctId(ACCOUNT_ID);
        return xref;
    }

    private static Customer customer() {
        final Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setCustFirstName("JANE");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("1 MAIN ST");
        customer.setCustAddrLine2("APT 2");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("10001");
        customer.setCustFicoCreditScore(720);
        return customer;
    }

    /**
     * A customer whose name and first address line carry stored-XSS probe markup. The first name is
     * space-free so it survives the COBOL {@code STRING ... DELIMITED BY ' '} token reproduction in
     * {@code composeCustomerName}; the address line one is moved verbatim ({@code safe}), exercising
     * all five HTML metacharacters ({@code < > & ' "}).
     */
    private static Customer maliciousCustomer() {
        final Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setCustFirstName(NAME_PAYLOAD);
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1(ADDR_PAYLOAD);
        customer.setCustAddrLine2("APT 2");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("10001");
        customer.setCustFicoCreditScore(720);
        return customer;
    }

    private static Transaction transaction(final String cardNumber, final String id,
                                           final BigDecimal amount, final String description) {
        final Transaction transaction = new Transaction();
        transaction.setTranId(id);
        transaction.setTranCardNum(cardNumber);
        transaction.setTranAmt(amount);
        transaction.setTranDesc(description);
        return transaction;
    }
}
