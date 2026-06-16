package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatementProcessor} focused on the CP3 performance finding:
 * statement generation must fetch transactions through the {@code tran_card_num}
 * alternate index ({@code idx_tran_card_num}, Flyway V2) via
 * {@link TransactionRepository#findByTranCardNumInOrderByTranCardNumAscTranIdAsc(java.util.Collection)}
 * rather than scanning the entire TRANSACT table in memory with {@code findAll()}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the
 * processor re-platforms the per-account statement logic of {@code app/cbl/CBSTM03A.CBL}
 * (transaction grouping {@code 8500-READTRNX-READ} / {@code 4000-TRNXFILE-GET}). These
 * tests assert the access pattern (targeted indexed read, never a full scan) and that the
 * rows returned by the query flow into the rendered statement in the database-provided
 * order. The processor's collaborators are mocked because the assertion is about the
 * repository interaction, not persistence; the lone {@code MeterRegistry} uses a real
 * {@link SimpleMeterRegistry}.</p>
 */
@DisplayName("StatementProcessor - indexed card-number query replaces the full-table scan")
class StatementProcessorTest {

    private CardCrossReferenceRepository xrefRepository;
    private CustomerRepository customerRepository;
    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        xrefRepository = mock(CardCrossReferenceRepository.class);
        customerRepository = mock(CustomerRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        processor = new StatementProcessor(xrefRepository, customerRepository,
                transactionRepository, accountRepository, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("process() fetches transactions via the card-number index query, never findAll()")
    void process_usesTargetedCardNumberQuery_neverFullScan() {
        Account account = account(100L, new BigDecimal("250.00"));
        when(xrefRepository.findByXrefAcctId(100L))
                .thenReturn(List.of(xref("1111222233334444", 7L, 100L)));
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(7L)));
        when(accountRepository.findById(100L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumInOrderByTranCardNumAscTranIdAsc(
                Set.of("1111222233334444")))
                .thenReturn(List.of(
                        txn("00000000000000001", "1111222233334444", "FIRST PURCHASE",
                                new BigDecimal("10.00")),
                        txn("00000000000000002", "1111222233334444", "SECOND PURCHASE",
                                new BigDecimal("20.00"))));

        StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(100L);
        // The rows returned by the indexed query appear in the rendered statement, in order.
        assertThat(result.textStatement()).contains("FIRST PURCHASE", "SECOND PURCHASE");
        assertThat(result.textStatement().indexOf("FIRST PURCHASE"))
                .isLessThan(result.textStatement().indexOf("SECOND PURCHASE"));

        // Performance fix: the targeted indexed query is used and the full-table scan is gone.
        verify(transactionRepository)
                .findByTranCardNumInOrderByTranCardNumAscTranIdAsc(Set.of("1111222233334444"));
        verify(transactionRepository, never()).findAll();
    }

    @Test
    @DisplayName("process() with no resolvable card numbers emits an empty summary without querying")
    void process_withNoCardNumbers_skipsQueryEntirely() {
        Account account = account(200L, new BigDecimal("0.00"));
        // A cross-reference with a null card number resolves to no card numbers to fetch.
        when(xrefRepository.findByXrefAcctId(200L))
                .thenReturn(List.of(xref(null, 9L, 200L)));
        when(customerRepository.findById(9L)).thenReturn(Optional.of(customer(9L)));
        when(accountRepository.findById(200L)).thenReturn(Optional.of(account));

        StatementResult result = processor.process(account);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(200L);
        // Neither the targeted query (empty input is short-circuited) nor a full scan runs.
        verify(transactionRepository, never())
                .findByTranCardNumInOrderByTranCardNumAscTranIdAsc(any());
        verify(transactionRepository, never()).findAll();
    }

    // ---------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------

    private static Account account(Long id, BigDecimal balance) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctCurrBal(balance);
        return account;
    }

    private static CardCrossReference xref(String cardNumber, Long custId, Long acctId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefCustId(custId);
        xref.setXrefAcctId(acctId);
        return xref;
    }

    private static Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setCustId(id);
        customer.setCustFirstName("JANE");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("1 MAIN ST");
        customer.setCustAddrLine2("APT 2");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrZip("10001");
        customer.setCustFicoCreditScore(720);
        return customer;
    }

    private static Transaction txn(String id, String cardNumber, String description,
            BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(id);
        transaction.setTranCardNum(cardNumber);
        transaction.setTranDesc(description);
        transaction.setTranAmt(amount);
        return transaction;
    }
}
