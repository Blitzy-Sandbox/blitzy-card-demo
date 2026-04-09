package com.cardemo.service.account;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Account View Service — migrated from COBOL program COACTVWC.cbl (941 lines).
 *
 * <p>Implements read-only account view functionality by performing a multi-dataset
 * read chain combining Account, Customer, and CardCrossReference data to produce
 * a comprehensive account view. This is the Java equivalent of the COBOL
 * 9000-READ-ACCT paragraph chain:
 * <ul>
 *   <li>9200-GETCARDXREF-BYACCT — Cross-reference lookup via CXACAIX alternate index</li>
 *   <li>9300-GETACCTDATA-BYACCT — Account master data read by primary key</li>
 *   <li>9400-GETCUSTDATA-BYCUST — Customer master data read by customer ID from xref</li>
 * </ul>
 *
 * <p>Input validation maps COBOL paragraph 2210-EDIT-ACCOUNT, enforcing:
 * non-null, non-blank, numeric, non-zero, exactly 11-digit account IDs.
 *
 * <p>DTO assembly maps COBOL paragraph 1200-SETUP-SCREEN-VARS, populating
 * account and customer fields from their respective entities into a single
 * {@link AccountDto} for the REST API response layer.
 *
 * <p><strong>Key Design Decisions:</strong>
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)} on the read method ensures
 *       read consistency across the multi-dataset join and enables connection reuse.</li>
 *   <li>All monetary fields (balance, limits, credits, debits) use {@link java.math.BigDecimal}
 *       with zero floating-point substitution per AAP §0.8.2.</li>
 *   <li>{@link RecordNotFoundException} maps COBOL FILE STATUS 23 (INVALID KEY / NOTFND)
 *       for all three not-found conditions in the read chain.</li>
 * </ul>
 *
 * @see com.cardemo.model.entity.Account
 * @see com.cardemo.model.entity.Customer
 * @see com.cardemo.model.entity.CardCrossReference
 * @see com.cardemo.model.dto.AccountDto
 */
@Service
public class AccountViewService {

    private static final Logger logger = LoggerFactory.getLogger(AccountViewService.class);

    /**
     * Expected length of account IDs matching COBOL PIC 9(11).
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * All-zeros account ID representing an invalid zero-value input.
     * Maps COBOL ZEROES literal check in paragraph 2210-EDIT-ACCOUNT.
     */
    private static final String ALL_ZEROS_ACCOUNT_ID = "00000000000";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Constructs the AccountViewService with required repository dependencies.
     *
     * <p>Spring constructor injection is used (no {@code @Autowired} annotation needed
     * for a single constructor). All three repositories are required for the
     * multi-dataset read chain that mirrors the COBOL COACTVWC program.
     *
     * @param accountRepository           repository for Account entity (maps ACCTDAT VSAM dataset)
     * @param customerRepository          repository for Customer entity (maps CUSTDAT VSAM dataset)
     * @param cardCrossReferenceRepository repository for CardCrossReference entity
     *                                      (maps CXACAIX alternate index path)
     */
    public AccountViewService(AccountRepository accountRepository,
                              CustomerRepository customerRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Retrieves a comprehensive account view by performing a multi-dataset read chain.
     *
     * <p>Maps COBOL paragraph 9000-READ-ACCT which orchestrates three sequential reads:
     * <ol>
     *   <li><strong>Cross-Reference Lookup (9200-GETCARDXREF-BYACCT):</strong>
     *       Reads the CXACAIX alternate index by account ID to obtain the customer ID.
     *       The CXACAIX alternate index uses KEYS(11,25) NONUNIQUEKEY, meaning multiple
     *       card cross-reference records may exist for one account.</li>
     *   <li><strong>Account Data Read (9300-GETACCTDATA-BYACCT):</strong>
     *       Reads the ACCTDAT primary key by account ID to obtain account details
     *       including balances, limits, dates, and status.</li>
     *   <li><strong>Customer Data Read (9400-GETCUSTDATA-BYCUST):</strong>
     *       Reads the CUSTDAT primary key by customer ID (obtained from Step 1)
     *       to obtain customer personal and contact information.</li>
     * </ol>
     *
     * <p>After all three reads succeed, the results are assembled into an {@link AccountDto}
     * (mapping COBOL paragraph 1200-SETUP-SCREEN-VARS).
     *
     * @param acctId the 11-digit account identifier (maps COBOL PIC 9(11))
     * @return an {@link AccountDto} containing combined account and customer data
     * @throws IllegalArgumentException  if the account ID fails validation
     *                                    (null, blank, non-numeric, all zeros, or wrong length)
     * @throws RecordNotFoundException   if the card cross-reference, account, or customer
     *                                    record is not found in the database
     */
    @Transactional(readOnly = true)
    public AccountDto getAccountView(String acctId) {
        logger.debug("Entering getAccountView for account ID: {}", acctId);

        // Step 1: Input Validation (← 2210-EDIT-ACCOUNT)
        validateAccountId(acctId);

        // Step 2: Cross-Reference Lookup (← 9200-GETCARDXREF-BYACCT)
        // Maps the CXACAIX alternate index read in COBOL — uses findByXrefAcctId
        // which corresponds to the NONUNIQUEKEY alternate index on XREF-ACCT-ID.
        // COBOL: EXEC CICS READ DATASET(CXACAIX) RIDFLD(WS-CARD-RID-ACCT-ID)
        List<CardCrossReference> crossRefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (crossRefs.isEmpty()) {
            // Report as "Account" not found since the user is looking up an account —
            // the cross-reference absence means the account is not linked/does not exist
            // in the system (no card-to-account mapping found).
            logger.warn("No card cross-reference found for account ID: {} — reporting as account not found", acctId);
            throw new RecordNotFoundException("Account", acctId);
        }

        // Extract the first cross-reference record to obtain customer ID and card number.
        // COBOL extracts: MOVE XREF-CUST-ID TO WS-CARD-RID-CUST-ID,
        //                 MOVE XREF-CARD-NUM TO WS-CARD-RID-CARD-NUM
        CardCrossReference primaryXref = crossRefs.get(0);
        String xrefAcctId = primaryXref.getXrefAcctId();
        String custId = primaryXref.getXrefCustId();
        logger.debug("Cross-reference found — xrefAcctId: {}, customer ID: {}, "
                + "total cross-references: {}", xrefAcctId, custId, crossRefs.size());

        // Step 3: Account Data Lookup (← 9300-GETACCTDATA-BYACCT)
        // Maps CICS READ DATASET(ACCTDAT) with RIDFLD(WS-CARD-RID-ACCT-ID-X)
        // using account ID as primary key to retrieve the full ACCOUNT-RECORD.
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.warn("Account data not found for account ID: {}", acctId);
                    return new RecordNotFoundException("Account", acctId);
                });
        logger.debug("Account data retrieved for account ID: {}, status: {}",
                acctId, account.getAcctActiveStatus());

        // Step 4: Customer Data Lookup (← 9400-GETCUSTDATA-BYCUST)
        // Maps CICS READ DATASET(CUSTDAT) with RIDFLD(WS-CARD-RID-CUST-ID-X)
        // using the customer ID extracted from the cross-reference record (Step 2).
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.warn("Customer data not found for customer ID: {}", custId);
                    return new RecordNotFoundException("Customer", custId);
                });
        logger.debug("Customer data retrieved for customer ID: {}", custId);

        // Step 5: DTO Assembly (← 1200-SETUP-SCREEN-VARS)
        // Combines account and customer entity fields into a single DTO
        // matching the BMS screen field population logic in COACTVWC.
        AccountDto dto = assembleAccountDto(account, customer, crossRefs);
        logger.info("Account view assembled successfully for account ID: {}", acctId);

        return dto;
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Validates the account ID input according to COBOL paragraph 2210-EDIT-ACCOUNT rules.
     *
     * <p>Validation rules (matching COBOL semantics):
     * <ul>
     *   <li>Must not be null (Java-specific null safety; COBOL fields are never null)</li>
     *   <li>Must not be blank or all whitespace (maps: IF CC-ACCT-ID = LOW-VALUES OR SPACES)</li>
     *   <li>Must be exactly 11 characters (maps: COBOL PIC 9(11) fixed-length field)</li>
     *   <li>Must be all digits (maps: IF CC-ACCT-ID NOT NUMERIC)</li>
     *   <li>Must not be all zeros (maps: IF CC-ACCT-ID = ZEROES)</li>
     * </ul>
     *
     * <p>The error message matches the COBOL original:
     * "Account Number if supplied must be a 11 digit Non-Zero Number"
     *
     * @param acctId the account ID to validate
     * @throws IllegalArgumentException if any validation rule is violated
     */
    private void validateAccountId(String acctId) {
        // Null and blank check — maps COBOL: IF CC-ACCT-ID = LOW-VALUES OR SPACES
        if (acctId == null || acctId.isBlank()) {
            logger.debug("Account ID validation failed: null or blank input");
            throw new IllegalArgumentException(
                    "Account Number if supplied must be a 11 digit Non-Zero Number");
        }

        // Length check — COBOL PIC 9(11) enforces exactly 11 digits
        if (acctId.length() != ACCOUNT_ID_LENGTH) {
            logger.debug("Account ID validation failed: length {} (expected {})",
                    acctId.length(), ACCOUNT_ID_LENGTH);
            throw new IllegalArgumentException(
                    "Account Number if supplied must be a 11 digit Non-Zero Number");
        }

        // Numeric check — maps COBOL: IF CC-ACCT-ID NOT NUMERIC
        // Uses character-level iteration matching COBOL's IS NUMERIC test
        for (int i = 0; i < acctId.length(); i++) {
            if (!Character.isDigit(acctId.charAt(i))) {
                logger.debug("Account ID validation failed: non-numeric character '{}' "
                        + "at position {}", acctId.charAt(i), i);
                throw new IllegalArgumentException(
                        "Account Number if supplied must be a 11 digit Non-Zero Number");
            }
        }

        // Zero check — maps COBOL: IF CC-ACCT-ID = ZEROES
        if (ALL_ZEROS_ACCOUNT_ID.equals(acctId)) {
            logger.debug("Account ID validation failed: all zeros");
            throw new IllegalArgumentException(
                    "Account Number if supplied must be a 11 digit Non-Zero Number");
        }

        logger.debug("Account ID validation passed: {}", acctId);
    }

    /**
     * Assembles an {@link AccountDto} from entity data, mapping COBOL paragraph
     * 1200-SETUP-SCREEN-VARS which populates BMS screen fields from both
     * ACCOUNT-RECORD (CVACT01Y.cpy) and CUSTOMER-RECORD (CVCUS01Y.cpy).
     *
     * <p>All BigDecimal monetary fields (balance, limits, credits, debits) are
     * passed through directly from the Account entity — no conversion needed
     * since entities already use BigDecimal (AAP §0.8.2 zero floating-point
     * substitution compliance).
     *
     * <p>The customer FICO credit score requires type conversion from {@code Short}
     * (entity type matching COBOL PIC 9(03)) to {@code String} (DTO type for the
     * API response layer).
     *
     * @param account   the Account entity with financial and status data
     * @param customer  the Customer entity with personal and contact data
     * @param crossRefs the list of card cross-reference records for the account
     * @return a fully populated {@link AccountDto}
     */
    private AccountDto assembleAccountDto(Account account, Customer customer,
                                          List<CardCrossReference> crossRefs) {
        AccountDto dto = new AccountDto();

        // ---- Account fields (← ACCOUNT-RECORD from CVACT01Y.cpy) ----
        // Maps COBOL MOVEs in 1200-SETUP-SCREEN-VARS for account data:
        //   MOVE ACCT-ID              TO APTS4AIO
        //   MOVE ACCT-ACTIVE-STATUS   TO APTS4BO
        //   MOVE ACCT-CURR-BAL        TO APTS4CO (edited)
        //   ... etc.
        dto.setAcctId(account.getAcctId());
        dto.setAcctActiveStatus(account.getAcctActiveStatus());

        // BigDecimal monetary fields — zero floating-point substitution (AAP §0.8.2)
        // COBOL: ACCT-CURR-BAL      PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        // COBOL: ACCT-CREDIT-LIMIT  PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        // COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        // COBOL: ACCT-CURR-CYC-CREDIT   PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        // COBOL: ACCT-CURR-CYC-DEBIT    PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        dto.setAcctCurrBal(account.getAcctCurrBal());
        dto.setAcctCreditLimit(account.getAcctCreditLimit());
        dto.setAcctCashCreditLimit(account.getAcctCashCreditLimit());
        dto.setAcctCurrCycCredit(account.getAcctCurrCycCredit());
        dto.setAcctCurrCycDebit(account.getAcctCurrCycDebit());

        // Date fields — LocalDate pass-through
        // COBOL: ACCT-OPEN-DATE        PIC X(10) → LocalDate
        // COBOL: ACCT-EXPIRAION-DATE   PIC X(10) → LocalDate
        // COBOL: ACCT-REISSUE-DATE     PIC X(10) → LocalDate
        dto.setAcctOpenDate(account.getAcctOpenDate());
        dto.setAcctExpDate(account.getAcctExpDate());
        dto.setAcctReissueDate(account.getAcctReissueDate());

        // Group identifier — COBOL: ACCT-GROUP-ID PIC X(10)
        dto.setAcctGroupId(account.getAcctGroupId());

        // ---- Customer fields (← CUSTOMER-RECORD from CVCUS01Y.cpy) ----
        // Maps COBOL MOVEs in 1200-SETUP-SCREEN-VARS for customer data:
        //   MOVE CUST-ID              TO WS-CUST-ID display field
        //   MOVE CUST-FIRST-NAME     TO screen first name field
        //   ... etc.
        dto.setCustId(customer.getCustId());
        dto.setCustFname(customer.getCustFirstName());
        dto.setCustMname(customer.getCustMiddleName());
        dto.setCustLname(customer.getCustLastName());

        // Address fields — note: COBOL stores city in CUST-ADDR-LINE-3
        // COBOL: CUST-ADDR-LINE-1 PIC X(50) → custAddr1
        // COBOL: CUST-ADDR-LINE-2 PIC X(50) → custAddr2
        // COBOL: CUST-ADDR-LINE-3 PIC X(50) → custCity (city stored in addr line 3)
        dto.setCustAddr1(customer.getCustAddrLine1());
        dto.setCustAddr2(customer.getCustAddrLine2());
        dto.setCustCity(customer.getCustAddrLine3());
        dto.setCustState(customer.getCustAddrStateCd());
        dto.setCustZip(customer.getCustAddrZip());
        dto.setCustCountry(customer.getCustAddrCountryCd());

        // Contact information
        // COBOL: CUST-PHONE-NUM-1 PIC X(15), CUST-PHONE-NUM-2 PIC X(15)
        dto.setCustPhone1(customer.getCustPhoneNum1());
        dto.setCustPhone2(customer.getCustPhoneNum2());

        // SSN — raw 9-digit value from entity; formatting to XXX-XX-XXXX is done in
        // the controller/presentation layer. COBOL paragraph 1200-SETUP-SCREEN-VARS
        // performs STRING formatting for BMS screen display — that display-level
        // formatting responsibility belongs in the controller layer in the Java architecture.
        dto.setCustSsn(customer.getCustSsn());

        // Date of birth — COBOL: CUST-DOB PIC X(10) → LocalDate
        dto.setCustDob(customer.getCustDob());

        // FICO credit score — requires type conversion: Short → String
        // Customer entity stores CUST-FICO-CREDIT-SCORE as Short (COBOL PIC 9(03))
        // AccountDto stores custFicoScore as String for the API response layer
        Short ficoScore = customer.getCustFicoCreditScore();
        dto.setCustFicoScore(ficoScore != null ? String.valueOf(ficoScore) : null);

        // Government-issued ID — COBOL: CUST-GOVT-ISSUED-ID PIC X(20)
        dto.setCustGovtId(customer.getCustGovtIssuedId());

        // EFT (Electronic Funds Transfer) account ID — COBOL: CUST-EFT-ACCOUNT-ID PIC X(10)
        dto.setCustEftAcct(customer.getCustEftAccountId());

        // Primary cardholder indicator — COBOL: CUST-PRI-CARD-HOLDER-IND PIC X(01)
        dto.setCustProfileFlag(customer.getCustPriCardHolderInd());

        // Optimistic locking version — exposes JPA @Version for concurrent modification
        // detection via API. Clients must include this in PUT requests; a mismatch
        // triggers HTTP 409 Conflict per AAP §0.8.4.
        dto.setVersion(account.getVersion());

        return dto;
    }
}
