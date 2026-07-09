package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.dto.AccountViewResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Pure, fast unit test for {@link AccountViewService}, the account-view service
 * (online transaction {@code CAVW}) migrated from the COBOL/CICS program
 * {@code COACTVWC} ({@code app/cbl/COACTVWC.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>All three collaborators ({@link AccountRepository},
 * {@link CustomerRepository}, {@link CrossReferenceService}) are Mockito mocks
 * and the service is created via constructor injection, so the suite loads
 * <strong>no</strong> Spring context and touches no database, Testcontainers,
 * Docker, or live AWS. Every branch of {@link AccountViewService#viewAccount(Long)}
 * &mdash; the id edit, the cross-reference resolve, the account and customer keyed
 * reads, and the field projection (including nine-digit SSN rendering) &mdash; is
 * exercised, feeding the JaCoCo line-coverage gate (Gate&nbsp;8).</p>
 *
 * <p>Behavioural-parity anchors (vs. {@code COACTVWC} @ SHA {@code 27d6c6f}):
 * the {@code 2210-EDIT-ACCOUNT} non-zero-11-digit edit, the
 * {@code 9200-GETCARDXREF-BYACCT} cross-reference navigation, and the
 * {@code 9300-GETACCTDATA-BYACCT}/{@code 9400-GETCUSTDATA-BYCUST}
 * {@code DFHRESP(NOTFND)} branches. Verbatim message text is asserted so the
 * external message contract (Gate&nbsp;5) is preserved byte-for-byte.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService — COBOL COACTVWC / CAVW account view (SHA 27d6c6f)")
class AccountViewServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @InjectMocks
    private AccountViewService service;

    /** A representative, strictly-positive account id used across the happy paths. */
    private static final Long ACCT_ID = 1234567890L;

    /** The customer id the cross-reference resolves to for {@link #ACCT_ID}. */
    private static final Long CUST_ID = 42L;

    @Nested
    @DisplayName("2210-EDIT-ACCOUNT — id edit rejects null/zero/negative")
    class IdEdit {

        @Test
        @DisplayName("null id → ValidationException, no collaborator touched")
        void nullId() {
            assertThatThrownBy(() -> service.viewAccount(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(AccountViewService.MSG_ACCT_INVALID);
            verifyNoInteractions(crossReferenceService, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("zero id → ValidationException")
        void zeroId() {
            assertThatThrownBy(() -> service.viewAccount(0L))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(AccountViewService.MSG_ACCT_INVALID);
            verifyNoInteractions(crossReferenceService, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("negative id → ValidationException")
        void negativeId() {
            assertThatThrownBy(() -> service.viewAccount(-7L))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(AccountViewService.MSG_ACCT_INVALID);
            verifyNoInteractions(crossReferenceService, accountRepository, customerRepository);
        }
    }

    @Nested
    @DisplayName("Not-found branches (DFHRESP(NOTFND))")
    class NotFound {

        @Test
        @DisplayName("cross-reference miss propagates the navigator's ResourceNotFoundException")
        void xrefMiss() {
            when(crossReferenceService.resolveCustomerId(ACCT_ID))
                    .thenThrow(new ResourceNotFoundException(AccountViewService.MSG_XREF_NOT_FOUND));

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage(AccountViewService.MSG_XREF_NOT_FOUND);
        }

        @Test
        @DisplayName("account master miss → ResourceNotFoundException (MSG_ACCT_NOT_FOUND)")
        void accountMiss() {
            when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage(AccountViewService.MSG_ACCT_NOT_FOUND);
        }

        @Test
        @DisplayName("customer master miss → ResourceNotFoundException (MSG_CUST_NOT_FOUND)")
        void customerMiss() {
            when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(mock(Account.class)));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage(AccountViewService.MSG_CUST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Happy path — field projection + SSN rendering")
    class HappyPath {

        @Test
        @DisplayName("maps account+customer, normalizes money to scale 2, renders 9-digit SSN")
        void mapsAllFields() {
            Account account = mock(Account.class);
            lenient().when(account.getAcctId()).thenReturn(ACCT_ID);
            lenient().when(account.getAcctActiveStatus()).thenReturn("Y");
            lenient().when(account.getAcctCurrBal()).thenReturn(new BigDecimal("100.5"));
            lenient().when(account.getAcctGroupId()).thenReturn("GRP1");

            Customer customer = mock(Customer.class);
            lenient().when(customer.getCustId()).thenReturn(CUST_ID);
            lenient().when(customer.getCustFirstName()).thenReturn("ADA");
            lenient().when(customer.getCustLastName()).thenReturn("LOVELACE");
            lenient().when(customer.getCustAddrLine3()).thenReturn("LONDON");
            lenient().when(customer.getCustSsn()).thenReturn(42L);

            when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountViewResponse response = service.viewAccount(ACCT_ID);

            assertThat(response.accountId()).isEqualTo("1234567890");
            assertThat(response.activeStatus()).isEqualTo("Y");
            // PIC S9(10)V99 → BigDecimal scale 2 (money normalization).
            assertThat(response.currentBalance()).isEqualByComparingTo("100.50");
            assertThat(response.currentBalance().scale()).isEqualTo(2);
            assertThat(response.accountGroupId()).isEqualTo("GRP1");
            assertThat(response.customerId()).isEqualTo("42");
            assertThat(response.firstName()).isEqualTo("ADA");
            assertThat(response.lastName()).isEqualTo("LOVELACE");
            // Screen ACSCITY = CUST-ADDR-LINE-3.
            assertThat(response.city()).isEqualTo("LONDON");
            // The service renders CUST-SSN PIC 9(09) as nine left-zero-padded
            // digits ("000000042"); the DTO canonical constructor then masks all
            // but the last four (decision log D-023), so the wire value shows
            // five mask characters followed by the last four digits.
            assertThat(response.ssn()).isEqualTo("*****0042");
        }

        @Test
        @DisplayName("null SSN yields null (never the literal \"null\")")
        void nullSsnYieldsNull() {
            Account account = mock(Account.class);
            lenient().when(account.getAcctId()).thenReturn(ACCT_ID);

            Customer customer = mock(Customer.class);
            lenient().when(customer.getCustId()).thenReturn(CUST_ID);
            lenient().when(customer.getCustSsn()).thenReturn(null);

            when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountViewResponse response = service.viewAccount(ACCT_ID);

            assertThat(response.ssn()).isNull();
            // Null money fields survive normalization as null (no NPE).
            assertThat(response.currentBalance()).isNull();
        }
    }
}
