package com.carddemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.dto.AccountViewResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.account.AccountViewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AccountViewService} (COBOL {@code COACTVWC} parity): account-id edit,
 * the cross-reference -&gt; account -&gt; customer read chain, and the SSN/FICO formatting in
 * the assembled view.
 */
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

    private static final Long ACCOUNT_ID = 12345678901L;
    private static final Long CUSTOMER_ID = 99L;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    private AccountViewService service() {
        return new AccountViewService(cardCrossReferenceRepository, accountRepository, customerRepository);
    }

    private static CardCrossReference xref() {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum("4111111111111111");
        x.setXrefCustId(CUSTOMER_ID);
        x.setXrefAcctId(ACCOUNT_ID);
        return x;
    }

    @Test
    void nullAccountIdRejected() {
        assertThatThrownBy(() -> service().getAccountView(null))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void nonPositiveAccountIdRejected() {
        assertThatThrownBy(() -> service().getAccountView(0L))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void missingCrossReferenceRejected() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service().getAccountView(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void missingAccountMasterRejected() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getAccountView(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void missingCustomerMasterRejected() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getAccountView(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void successAssemblesViewWithFormattedSsnAndBlankFico() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctGroupId("DEFAULT");
        account.setVersion(7L);

        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setCustSsn(123456789L);
        customer.setCustFicoCreditScore(null);
        customer.setCustFirstName("JANE");
        customer.setCustLastName("DOE");

        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        AccountViewResponse response = service().getAccountView(ACCOUNT_ID);

        assertThat(response.accountId()).isEqualTo("12345678901");
        assertThat(response.accountStatus()).isEqualTo("Y");
        assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
        assertThat(response.customerId()).isEqualTo("99");
        assertThat(response.ssn()).isEqualTo("123-45-6789");
        assertThat(response.ficoScore()).isEmpty();
        assertThat(response.firstName()).isEqualTo("JANE");
        assertThat(response.version()).isEqualTo(7L);
    }

    @Test
    void successWithFicoScoreRendersNumericString() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setCustSsn(null);
        customer.setCustFicoCreditScore(720);

        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        AccountViewResponse response = service().getAccountView(ACCOUNT_ID);

        assertThat(response.ssn()).isEmpty();
        assertThat(response.ficoScore()).isEqualTo("720");
    }
}
