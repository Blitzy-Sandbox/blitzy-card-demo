package com.carddemo.service.account;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.dto.AccountViewResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account inquiry service. Java equivalent of COBOL online program COACTVWC
 * (source commit {@code 27d6c6f}): reads the card cross-reference (by account
 * alternate index), account master, and customer master, and assembles a
 * combined account view.
 */
@Service
public class AccountViewService {

    private static final Logger log = LoggerFactory.getLogger(AccountViewService.class);

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    /**
     * Creates the service with its required repositories.
     *
     * @param cardCrossReferenceRepository card cross-reference repository (CXACAIX account path)
     * @param accountRepository            account master repository (ACCTDAT)
     * @param customerRepository           customer master repository (CUSTDAT)
     */
    public AccountViewService(CardCrossReferenceRepository cardCrossReferenceRepository,
                              AccountRepository accountRepository,
                              CustomerRepository customerRepository) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Reads an account view by account id. Maps to {@code COACTVWC.9000-READ-ACCT}:
     * resolves the card cross-reference by account id, then the account master, then
     * the customer master (keyed by the cross-reference customer id), and assembles
     * the combined view.
     *
     * @param accountId the account identifier
     * @return the assembled account view
     * @throws RecordNotFoundException (FILE STATUS {@code "23"}) when the account id is
     *                                 absent or non-positive, or when the cross-reference,
     *                                 account-master, or customer-master record is not found
     */
    @Transactional(readOnly = true)
    public AccountViewResponse getAccountView(Long accountId) {
        log.debug("Reading account view for id={}", accountId);
        validateAccountId(accountId);
        CardCrossReference crossReference = getCardCrossReferenceByAccount(accountId);
        Long customerId = crossReference.getXrefCustId();
        Account account = getAccountById(accountId);
        Customer customer = getCustomerById(customerId);
        return buildResponse(account, customer);
    }

    /**
     * Validates the account id before any read. Maps to {@code COACTVWC.2210-EDIT-ACCOUNT}:
     * a blank or non-numeric/zero account filter is rejected before the read chain runs.
     *
     * @param accountId the account identifier to validate
     * @throws RecordNotFoundException when {@code accountId} is {@code null} or not positive
     */
    private static void validateAccountId(Long accountId) {
        if (accountId == null || accountId <= 0L) {
            throw RecordNotFoundException.forKey("Account", accountId);
        }
    }

    /**
     * Reads the card cross-reference by account id. Maps to
     * {@code COACTVWC.9200-GETCARDXREF-BYACCT}. The account alternate index is
     * non-unique, so the first matching row is consumed.
     *
     * @param accountId the account identifier
     * @return the first matching cross-reference record
     * @throws RecordNotFoundException when no cross-reference exists for the account
     */
    private CardCrossReference getCardCrossReferenceByAccount(Long accountId) {
        List<CardCrossReference> crossReferences = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (crossReferences.isEmpty()) {
            throw RecordNotFoundException.forKey("Account cross-reference", accountId);
        }
        return crossReferences.get(0);
    }

    /**
     * Reads the account master record by account id. Maps to
     * {@code COACTVWC.9300-GETACCTDATA-BYACCT}.
     *
     * @param accountId the account identifier
     * @return the account master record
     * @throws RecordNotFoundException when no account master record exists
     */
    private Account getAccountById(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Account", accountId));
    }

    /**
     * Reads the customer master record by customer id. Maps to
     * {@code COACTVWC.9400-GETCUSTDATA-BYCUST}.
     *
     * @param customerId the customer identifier resolved from the cross-reference
     * @return the customer master record
     * @throws RecordNotFoundException when no customer master record exists
     */
    private Customer getCustomerById(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Customer", customerId));
    }

    /**
     * Assembles the combined account view from the account and customer records.
     * Maps to {@code COACTVWC.1200-SETUP-SCREEN-VARS}. Monetary fields are carried
     * through as {@link java.math.BigDecimal} (scale 2); the customer city is sourced
     * from address line 3; informational and error message components are unset on a
     * successful read (errors are signalled via {@link RecordNotFoundException}).
     *
     * @param account  the account master record
     * @param customer the customer master record
     * @return the assembled account view
     */
    private static AccountViewResponse buildResponse(Account account, Customer customer) {
        return new AccountViewResponse(
                String.valueOf(account.getAcctId()),
                account.getAcctActiveStatus(),
                account.getAcctOpenDate(),
                account.getAcctCreditLimit(),
                account.getAcctExpiraionDate(),
                account.getAcctCashCreditLimit(),
                account.getAcctReissueDate(),
                account.getAcctCurrBal(),
                account.getAcctCurrCycCredit(),
                account.getAcctGroupId(),
                account.getAcctCurrCycDebit(),
                String.valueOf(customer.getCustId()),
                formatSsn(customer.getCustSsn()),
                customer.getCustDobYyyyMmDd(),
                customer.getCustFicoCreditScore() == null
                        ? ""
                        : String.valueOf(customer.getCustFicoCreditScore()),
                customer.getCustFirstName(),
                customer.getCustMiddleName(),
                customer.getCustLastName(),
                customer.getCustAddrLine1(),
                customer.getCustAddrStateCd(),
                customer.getCustAddrLine2(),
                customer.getCustAddrZip(),
                customer.getCustAddrLine3(),
                customer.getCustAddrCountryCd(),
                customer.getCustPhoneNum1(),
                customer.getCustGovtIssuedId(),
                customer.getCustPhoneNum2(),
                customer.getCustEftAccountId(),
                customer.getCustPriCardHolderInd(),
                account.getVersion(),
                null,
                null);
    }

    /**
     * Formats a nine-digit social-security number as {@code XXX-XX-XXXX}. Maps to the
     * {@code STRING CUST-SSN} edit in {@code COACTVWC.1200-SETUP-SCREEN-VARS}.
     *
     * @param ssn the numeric social-security number, or {@code null}
     * @return the formatted SSN, or an empty string when {@code ssn} is {@code null}
     */
    private static String formatSsn(Long ssn) {
        if (ssn == null) {
            return "";
        }
        String digits = String.format("%09d", ssn);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
    }
}
