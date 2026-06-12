package com.cardemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountViewService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountViewService} — the {@code COACTVWC} account-view translation. Directly
 * covers the CP3 behavioral-parity findings: the account-key validation messages and the three
 * stage-specific not-found prompts MUST be the verbatim COACTVWC literals (AAP §0.7.2), and the
 * not-found prompts must be carried as the {@code clientSafeMessage} so the central advice surfaces
 * them (rather than sanitizing to a generic 404).
 */
class AccountViewServiceTest {

    private static final Long ACCT_ID = 1L;
    private static final Long CUST_ID = 100L;

    private AccountRepository accountRepository;
    private CustomerRepository customerRepository;
    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private AccountViewService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        customerRepository = mock(CustomerRepository.class);
        cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        service = new AccountViewService(accountRepository, customerRepository,
                cardCrossReferenceRepository);
    }

    private CardCrossReference xref() {
        CardCrossReference x = new CardCrossReference();
        x.setXrefAcctId(ACCT_ID);
        x.setXrefCardNum("1234567890123456");
        x.setXrefCustId(CUST_ID);
        return x;
    }

    @Nested
    @DisplayName("2210-EDIT-ACCOUNT key validation (verbatim COACTVWC messages)")
    class KeyValidation {

        @Test
        @DisplayName("blank key -> 'Account number not provided' on field 'Account Number'")
        void blankKey() {
            assertThatThrownBy(() -> service.viewAccount(null))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> {
                        ValidationException ve = (ValidationException) ex;
                        assertThat(ve.getFieldName()).isEqualTo("Account Number");
                        assertThat(ve.getValidationErrors()).contains("Account number not provided");
                    });
            assertThatThrownBy(() -> service.viewAccount("   "))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> assertThat(((ValidationException) ex).getValidationErrors())
                            .contains("Account number not provided"));
        }

        @Test
        @DisplayName("non-numeric key -> 'Account number must be a non zero 11 digit number'")
        void nonNumericKey() {
            assertThatThrownBy(() -> service.viewAccount("abc"))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> assertThat(((ValidationException) ex).getValidationErrors())
                            .contains("Account number must be a non zero 11 digit number"));
        }

        @Test
        @DisplayName("zero key -> 'Account number must be a non zero 11 digit number'")
        void zeroKey() {
            assertThatThrownBy(() -> service.viewAccount("00000000000"))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> assertThat(((ValidationException) ex).getValidationErrors())
                            .contains("Account number must be a non zero 11 digit number"));
        }
    }

    @Nested
    @DisplayName("9000-READ-ACCT stage-specific not-found prompts (carried as clientSafeMessage)")
    class NotFoundStages {

        @Test
        @DisplayName("no cross-reference -> 'Did not find this account in account card xref file'")
        void xrefNotFound() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.viewAccount("1"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .satisfies(ex -> assertThat(((RecordNotFoundException) ex).getClientSafeMessage())
                            .isEqualTo("Did not find this account in account card xref file"));
        }

        @Test
        @DisplayName("no account master -> 'Did not find this account in account master file'")
        void accountNotFound() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount("1"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .satisfies(ex -> assertThat(((RecordNotFoundException) ex).getClientSafeMessage())
                            .isEqualTo("Did not find this account in account master file"));
        }

        @Test
        @DisplayName("no customer master -> 'Did not find associated customer in master file'")
        void customerNotFound() {
            Account account = new Account();
            account.setAcctId(ACCT_ID);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount("1"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .satisfies(ex -> assertThat(((RecordNotFoundException) ex).getClientSafeMessage())
                            .isEqualTo("Did not find associated customer in master file"));
        }
    }

    @Test
    @DisplayName("happy path: assembles the DTO and carries the account @Version as the optimistic-lock token")
    void happyPathCarriesVersion() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setVersion(7L);
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        AccountDto dto = service.viewAccount("1");

        assertThat(dto).isNotNull();
        assertThat(dto.getAccountId()).isEqualTo("00000000001");
        assertThat(dto.getVersion()).isEqualTo(7L);
    }
}
