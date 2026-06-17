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

/**
 * Account inquiry service. Java equivalent of COBOL online program {@code COACTVWC}
 * (source commit {@code 27d6c6f}): reads the card cross-reference (by account
 * alternate index), account master, and customer master, and assembles a combined
 * account view.
 *
 * <p>The COBOL program is a behavioral reference and is not copied. The component is
 * stateless: each {@link #getAccountView(Long)} call is a self-contained
 * request/response, so the COBOL {@code CARDDEMO-COMMAREA} is not reproduced as
 * server state.</p>
 */
@Service
public class AccountViewService {

    private static final Logger log = LoggerFactory.getLogger(AccountViewService.class);

    /** Logical entity name used in {@link RecordNotFoundException} messages for the account-master read. */
    private static final String ENTITY_ACCOUNT = "Account";

    /** Logical entity name used in {@link RecordNotFoundException} messages for the cross-reference read. */
    private static final String ENTITY_ACCOUNT_XREF = "Account cross-reference";

    /** Logical entity name used in {@link RecordNotFoundException} messages for the customer-master read. */
    private static final String ENTITY_CUSTOMER = "Customer";

    /** Empty display string used where the source map leaves a field blank (no value present). */
    private static final String EMPTY = "";

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    /**
     * Creates the service with its data-access collaborators.
     *
     * @param cardCrossReferenceRepository card cross-reference store (VSAM {@code CARDXREF} / {@code CXACAIX} alternate index)
     * @param accountRepository            account-master store (VSAM {@code ACCTDAT})
     * @param customerRepository           customer-master store (VSAM {@code CUSTDAT})
     */
    public AccountViewService(CardCrossReferenceRepository cardCrossReferenceRepository,
                              AccountRepository accountRepository,
                              CustomerRepository customerRepository) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Reads an account by id across the card cross-reference, account master, and
     * customer master, and assembles a combined view. Maps to {@code COACTVWC}
     * paragraph {@code 9000-READ-ACCT}.
     *
     * @param accountId the account id to look up
     * @return the assembled account view
     * @throws RecordNotFoundException (FILE STATUS {@code "23"}) when the account id is
     *         absent/non-positive, or when the account has no cross-reference, no
     *         account-master record, or no customer-master record
     */
    public AccountViewResponse getAccountView(Long accountId) {
        log.debug("Reading account view for id={}", accountId);

        validateAccountId(accountId);

        Long customerId = getCardXrefByAccount(accountId);
        Account account = getAccountDataByAccount(accountId);
        Customer customer = getCustomerDataByCustomer(customerId);

        return assembleResponse(account, customer);
    }

    /**
     * Defensive account-id guard. Maps to {@code COACTVWC} paragraph
     * {@code 2210-EDIT-ACCOUNT}: the source rejects a blank, non-numeric, or zero
     * account filter before any read. The remaining meaningful checks for a typed
     * {@code Long} are null and non-positive.
     *
     * @param accountId the account id to validate
     * @throws RecordNotFoundException when {@code accountId} is null or not positive
     */
    private static void validateAccountId(Long accountId) {
        if (accountId == null || accountId <= 0L) {
            throw RecordNotFoundException.forKey(ENTITY_ACCOUNT, accountId);
        }
    }

    /**
     * Reads the card cross-reference by account id. Maps to {@code COACTVWC} paragraph
     * {@code 9200-GETCARDXREF-BYACCT}, which accesses the cross-reference via the
     * non-unique account alternate index and consumes the first matching record.
     *
     * @param accountId the account id used as the alternate-index key
     * @return the customer id captured from the first cross-reference record
     * @throws RecordNotFoundException when no cross-reference record exists for the account id
     */
    private Long getCardXrefByAccount(Long accountId) {
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (xrefs.isEmpty()) {
            throw RecordNotFoundException.forKey(ENTITY_ACCOUNT_XREF, accountId);
        }
        CardCrossReference xref = xrefs.get(0);
        return xref.getXrefCustId();
    }

    /**
     * Reads the account-master record by account id. Maps to {@code COACTVWC} paragraph
     * {@code 9300-GETACCTDATA-BYACCT} (single keyed read).
     *
     * @param accountId the account-master key
     * @return the account-master record
     * @throws RecordNotFoundException when no account-master record exists for the account id
     */
    private Account getAccountDataByAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> RecordNotFoundException.forKey(ENTITY_ACCOUNT, accountId));
    }

    /**
     * Reads the customer-master record by customer id. Maps to {@code COACTVWC} paragraph
     * {@code 9400-GETCUSTDATA-BYCUST} (single keyed read).
     *
     * @param customerId the customer-master key captured from the cross-reference
     * @return the customer-master record
     * @throws RecordNotFoundException when no customer-master record exists for the customer id
     */
    private Customer getCustomerDataByCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> RecordNotFoundException.forKey(ENTITY_CUSTOMER, customerId));
    }

    /**
     * Assembles the combined view from the account-master and customer-master records.
     * Maps to {@code COACTVWC} paragraph {@code 1200-SETUP-SCREEN-VARS}: the five money
     * fields stay {@link java.math.BigDecimal}; {@code city} is sourced from customer
     * address line 3; the SSN is rendered {@code XXX-XX-XXXX}. The info/error message
     * slots are empty on a successful read (errors surface as thrown exceptions).
     *
     * @param account  the account-master record
     * @param customer the customer-master record
     * @return the populated {@link AccountViewResponse}
     */
    private static AccountViewResponse assembleResponse(Account account, Customer customer) {
        return new AccountViewResponse(
                formatAccountId(account.getAcctId()),
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
                formatCustomerId(customer.getCustId()),
                formatSsn(customer.getCustSsn()),
                customer.getCustDobYyyyMmDd(),
                formatFicoScore(customer.getCustFicoCreditScore()),
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
                null,
                null,
                account.getVersion());
    }

    /**
     * Formats a customer SSN as {@code XXX-XX-XXXX}. Maps to the {@code COACTVWC}
     * {@code STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)} expression over the
     * 9-digit {@code CUST-SSN PIC 9(09)} field.
     *
     * @param ssn the 9-digit SSN value, or null
     * @return the formatted SSN, or an empty string when {@code ssn} is null
     */
    private static String formatSsn(Long ssn) {
        if (ssn == null) {
            return EMPTY;
        }
        String digits = String.format("%09d", ssn);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
    }

    /**
     * Renders the FICO credit score as a display string. Maps to the {@code COACTVWC}
     * move of {@code CUST-FICO-CREDIT-SCORE} to the screen field.
     *
     * @param ficoScore the credit score, or null
     * @return the score as a string, or an empty string when {@code ficoScore} is null
     */
    private static String formatFicoScore(Integer ficoScore) {
        return ficoScore == null ? EMPTY : String.valueOf(ficoScore);
    }

    /**
     * Formats the account identifier as the COBOL {@code ACCT-ID PIC 9(11)} 11-digit
     * zero-padded display value, matching {@code COACTVWC} {@code MOVE CC-ACCT-ID TO
     * ACCTSIDO} into the symbolic field {@code ACCTSIDO PIC X(11)}. Mirrors the account-id
     * rendering used by the card endpoints ({@code CardService.formatAccountId}) so the same
     * logical key is represented identically across endpoints (cross-endpoint consistency).
     *
     * @param accountId the account identifier, or null
     * @return the 11-digit zero-padded id, or an empty string when {@code accountId} is null
     */
    private static String formatAccountId(Long accountId) {
        return accountId == null ? EMPTY : String.format("%011d", accountId);
    }

    /**
     * Formats the customer identifier as the COBOL {@code CUST-ID PIC 9(09)} 9-digit
     * zero-padded display value, matching {@code COACTVWC} {@code MOVE CUST-ID TO ACSTNUMO}
     * into the symbolic field {@code ACSTNUMO PIC X(9)}.
     *
     * @param customerId the customer identifier, or null
     * @return the 9-digit zero-padded id, or an empty string when {@code customerId} is null
     */
    private static String formatCustomerId(Long customerId) {
        return customerId == null ? EMPTY : String.format("%09d", customerId);
    }
}
