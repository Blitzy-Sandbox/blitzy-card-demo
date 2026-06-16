package com.carddemo.unit.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.model.dto.BillPaymentRequest;
import com.carddemo.model.dto.BillPaymentResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.billing.BillPaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link BillPaymentService} focused on the CP3 MINOR security/observability finding:
 * routine bill-payment logging must not expose the account id, transaction id, or exact payment
 * amount, and the posted amount must increment {@code carddemo.transaction.amount.total}.
 *
 * <p>A Logback {@link ListAppender} captures the service's log output so the test can assert that no
 * emitted record contains the sensitive account id or amount. The repositories are mocked and the
 * {@code MeterRegistry} is a real {@link SimpleMeterRegistry}. Source commit {@code 27d6c6f}
 * (COBOL {@code COBIL00C}, REFERENCE only).</p>
 */
@DisplayName("BillPaymentService - non-sensitive logging + transaction amount metric")
class BillPaymentServiceTest {

    private static final String ACCOUNT_ID = "12345";
    private static final BigDecimal BALANCE = new BigDecimal("100.00");

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private SimpleMeterRegistry registry;
    private BillPaymentService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        registry = new SimpleMeterRegistry();
        service = new BillPaymentService(accountRepository, transactionRepository,
                cardCrossReferenceRepository, registry);

        serviceLogger = (Logger) LoggerFactory.getLogger(BillPaymentService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("successful payment records the amount metric and logs no account id or amount")
    void successfulPayment_recordsMetric_andLogsNoSensitiveData() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, BALANCE)));
        when(cardCrossReferenceRepository.findByXrefAcctId(12345L))
                .thenReturn(List.of(xref("1111222233334444", 7L, 12345L)));
        when(transactionRepository.findMaxTranId()).thenReturn(null);
        when(transactionRepository.save(any(Transaction.class))).then(returnsFirstArg());
        when(accountRepository.save(any(Account.class))).then(returnsFirstArg());

        BillPaymentResponse response = service.pay(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        // Business outcome: paid in full, balance cleared. (COBIL00C reuses the single WS-MESSAGE
        // field for the success text, surfaced here as the response message.)
        assertThat(response.errorMessage()).contains("Payment successful");
        assertThat(response.currentBalance()).isEqualByComparingTo("0.00");

        // Observability: the posted amount increments the running-total metric.
        assertThat(registry.counter(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).count())
                .isCloseTo(100.0, within(1e-9));

        // Security: no log record exposes the account id or the exact payment amount.
        assertThat(logAppender.list)
                .as("bill-payment log records")
                .isNotEmpty()
                .allSatisfy(event -> {
                    String message = event.getFormattedMessage();
                    assertThat(message).doesNotContain(ACCOUNT_ID);
                    assertThat(message).doesNotContain("100.00");
                });
        // A non-sensitive outcome record is still emitted.
        assertThat(logAppender.list)
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage()).contains("Bill payment posted successfully"));
    }

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
}
