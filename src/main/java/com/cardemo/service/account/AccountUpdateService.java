package com.cardemo.service.account;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.ValidationLookupService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Account Update Service — migrated from COBOL program COACTUPC.cbl (4,236 lines).
 *
 * <p>Performs dual-dataset atomic updates (Account + Customer) within a single
 * {@code @Transactional} boundary, with optimistic concurrency control via JPA
 * {@code @Version}, extensive field-level validation, and SYNCPOINT ROLLBACK semantics.</p>
 *
 * <h3>COBOL Paragraph Mapping:</h3>
 * <ul>
 *   <li>9000-READ-ACCT chain → {@link #getAccount(String)}</li>
 *   <li>PROCESS-UPDATE-ACCT → {@link #updateAccount(String, AccountDto)}</li>
 *   <li>1200-EDIT-MAP-INPUTS → {@link #validateUpdateFields(AccountDto)}</li>
 *   <li>9600-WRITE-PROCESSING → save section in updateAccount</li>
 *   <li>9700-CHECK-CHANGE-IN-REC → JPA {@code @Version} optimistic locking</li>
 *   <li>SYNCPOINT ROLLBACK → {@code @Transactional(rollbackFor = Exception.class)}</li>
 * </ul>
 *
 * @see Account
 * @see Customer
 * @see AccountDto
 */
@Service
public class AccountUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(AccountUpdateService.class);

    /** Date formatter matching COBOL CCYYMMDD date string format. */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** COBOL ACCT-ID length: PIC X(11). */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** COBOL SSN raw digits count: 3 + 2 + 4 = 9. */
    private static final int SSN_LENGTH = 9;

    /** US phone number total digits: area(3) + prefix(3) + line(4) = 10. */
    private static final int PHONE_DIGITS_LENGTH = 10;

    /** FICO score minimum value per COBOL 1275-EDIT-FICO-SCORE. */
    private static final int FICO_MIN = 300;

    /** FICO score maximum value per COBOL 1275-EDIT-FICO-SCORE. */
    private static final int FICO_MAX = 850;

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;

    /**
     * Constructor injection of all 5 dependencies.
     *
     * @param accountRepository           JPA repository for Account entity (ACCTDAT VSAM)
     * @param customerRepository          JPA repository for Customer entity (CUSTDAT VSAM)
     * @param cardCrossReferenceRepository JPA repository for cross-reference (CXACAIX alternate index)
     * @param dateValidationService       Shared date validation (replaces CSUTLDTC.cbl + CEEDAYS)
     * @param validationLookupService     Shared lookup service (replaces CSLKPCDY.cpy tables)
     */
    public AccountUpdateService(AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                CardCrossReferenceRepository cardCrossReferenceRepository,
                                DateValidationService dateValidationService,
                                ValidationLookupService validationLookupService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
    }

    // =========================================================================
    // Public Methods
    // =========================================================================

    /**
     * Fetches account data for the update form.
     * Maps COBOL COACTUPC.cbl paragraphs 9000-READ-ACCT → 9100-GETACCT-REQUEST →
     * 9200-GETCARDXREF-REQUEST (CXACAIX) → 9400-GETCUSTDATA.
     *
     * <p>Performs a 3-step read chain: cross-reference lookup → account fetch → customer fetch.</p>
     *
     * @param acctId the 11-digit account identifier
     * @return populated AccountDto combining account and customer data
     * @throws RecordNotFoundException if account, cross-reference, or customer not found
     * @throws ValidationException     if account ID is invalid
     */
    public AccountDto getAccount(String acctId) {
        logger.info("Fetching account for update: acctId='{}'", acctId);

        validateAccountId(acctId);

        // Step 1: Cross-reference lookup to resolve customer ID (← CXACAIX alternate index path)
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            logger.warn("No cross-reference found for account: {}", acctId);
            throw new RecordNotFoundException("CardCrossReference", acctId);
        }
        CardCrossReference xref = xrefs.getFirst();
        String custId = xref.getXrefCustId();
        logger.debug("Cross-reference resolved: acctId='{}' → custId='{}'", acctId, custId);

        // Step 2: Fetch account record (← EXEC CICS READ FILE(ACCTFILENAME))
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.error("Account not found: {}", acctId);
                    return new RecordNotFoundException("Account", acctId);
                });

        // Step 3: Fetch customer record (← EXEC CICS READ FILE(CUSTFILENAME))
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.error("Customer not found: {}", custId);
                    return new RecordNotFoundException("Customer", custId);
                });

        logger.info("Account fetched successfully for update: acctId='{}', version={}", acctId, account.getVersion());
        return assembleAccountDto(account, customer);
    }

    /**
     * Performs the full account update with atomic dual-dataset persistence.
     * Maps COBOL COACTUPC.cbl PROCESS-UPDATE-ACCT → 9600-WRITE-PROCESSING.
     *
     * <p>The {@code @Transactional(rollbackFor = Exception.class)} annotation maps the COBOL
     * EXEC CICS SYNCPOINT ROLLBACK semantics — if the customer REWRITE fails after the
     * account REWRITE succeeds, the entire transaction is rolled back.</p>
     *
     * <p>Optimistic concurrency control is enforced via JPA {@code @Version} on the
     * Account entity, mapping COACTUPC 9700-CHECK-CHANGE-IN-REC (DATA-WAS-CHANGED-BEFORE-UPDATE).</p>
     *
     * @param acctId      the 11-digit account identifier
     * @param updatedData the DTO containing updated field values
     * @return AccountDto with the persisted updated values
     * @throws ValidationException               if field validation fails (25+ field cascade)
     * @throws RecordNotFoundException           if account, cross-reference, or customer not found
     * @throws ConcurrentModificationException   if optimistic lock conflict detected
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountDto updateAccount(String acctId, AccountDto updatedData) {
        logger.info("Starting account update: acctId='{}'", acctId);

        // Step 1: Validate ALL input fields (← 1200-EDIT-MAP-INPUTS)
        // Aggregates all errors before throwing — matches COBOL aggregate validation pattern
        validateUpdateFields(updatedData);

        // Step 2: Fetch current records (← 9600-WRITE-PROCESSING READ UPDATE)
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.error("Account not found during update: {}", acctId);
                    return new RecordNotFoundException("Account", acctId);
                });

        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            logger.error("Cross-reference not found for account during update: {}", acctId);
            throw new RecordNotFoundException("CardCrossReference", acctId);
        }
        String custId = xrefs.getFirst().getXrefCustId();

        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.error("Customer not found during update: {}", custId);
                    return new RecordNotFoundException("Customer", custId);
                });

        logger.debug("Current account version before update: {}", account.getVersion());

        // Step 3: Apply updates to Account entity (← prepare ACCT-UPDATE-RECORD)
        applyAccountUpdates(account, updatedData);

        // Step 4: Apply updates to Customer entity (← prepare CUST-UPDATE-RECORD)
        applyCustomerUpdates(customer, updatedData);

        // Step 5: Persist both records atomically (← REWRITE ACCTDAT + REWRITE CUSTDAT)
        // If ObjectOptimisticLockingFailureException occurs → maps 9700-CHECK-CHANGE-IN-REC
        // @Transactional ensures SYNCPOINT ROLLBACK on any exception
        try {
            accountRepository.save(account);
            customerRepository.save(customer);
        } catch (ObjectOptimisticLockingFailureException ex) {
            logger.error("Concurrent modification detected for account: {}", acctId, ex);
            throw new ConcurrentModificationException(
                    "Account " + acctId + " was modified by another transaction. "
                            + "Please refresh and retry.", ex);
        }

        logger.info("Account update completed successfully: acctId='{}'", acctId);

        // Step 6: Return updated AccountDto
        return assembleAccountDto(account, customer);
    }

    // =========================================================================
    // Entity Update Helpers
    // =========================================================================

    /**
     * Applies field updates from DTO to Account entity.
     * Maps COBOL COACTUPC prepare ACCT-UPDATE-RECORD section.
     * Only updates fields that are non-null in the DTO (preserves COBOL LOW-VALUES sentinel pattern).
     */
    private void applyAccountUpdates(Account account, AccountDto dto) {
        if (dto.getAcctActiveStatus() != null) {
            account.setAcctActiveStatus(dto.getAcctActiveStatus().toUpperCase());
        }
        if (dto.getAcctCurrBal() != null) {
            account.setAcctCurrBal(dto.getAcctCurrBal());
        }
        if (dto.getAcctCreditLimit() != null) {
            account.setAcctCreditLimit(dto.getAcctCreditLimit());
        }
        if (dto.getAcctCashCreditLimit() != null) {
            account.setAcctCashCreditLimit(dto.getAcctCashCreditLimit());
        }
        if (dto.getAcctOpenDate() != null) {
            account.setAcctOpenDate(dto.getAcctOpenDate());
        }
        if (dto.getAcctExpDate() != null) {
            account.setAcctExpDate(dto.getAcctExpDate());
        }
        if (dto.getAcctReissueDate() != null) {
            account.setAcctReissueDate(dto.getAcctReissueDate());
        }
        if (dto.getAcctCurrCycCredit() != null) {
            account.setAcctCurrCycCredit(dto.getAcctCurrCycCredit());
        }
        if (dto.getAcctCurrCycDebit() != null) {
            account.setAcctCurrCycDebit(dto.getAcctCurrCycDebit());
        }
        if (dto.getAcctGroupId() != null) {
            account.setAcctGroupId(dto.getAcctGroupId());
        }
    }

    /**
     * Applies field updates from DTO to Customer entity.
     * Maps COBOL COACTUPC prepare CUST-UPDATE-RECORD section.
     * Only updates fields that are non-null in the DTO (preserves COBOL LOW-VALUES sentinel pattern).
     *
     * <p>Note: Customer entity does NOT have {@code @Version} — only Account uses optimistic locking.</p>
     */
    private void applyCustomerUpdates(Customer customer, AccountDto dto) {
        if (dto.getCustFname() != null) {
            customer.setCustFirstName(dto.getCustFname());
        }
        if (dto.getCustMname() != null) {
            customer.setCustMiddleName(dto.getCustMname());
        }
        if (dto.getCustLname() != null) {
            customer.setCustLastName(dto.getCustLname());
        }
        if (dto.getCustAddr1() != null) {
            customer.setCustAddrLine1(dto.getCustAddr1());
        }
        if (dto.getCustAddr2() != null) {
            customer.setCustAddrLine2(dto.getCustAddr2());
        }
        // City is stored in address line 3 per COBOL CUST-ADDR-LINE-3 mapping
        if (dto.getCustCity() != null) {
            customer.setCustAddrLine3(dto.getCustCity());
        }
        if (dto.getCustState() != null) {
            customer.setCustAddrStateCd(dto.getCustState().toUpperCase());
        }
        if (dto.getCustZip() != null) {
            customer.setCustAddrZip(dto.getCustZip());
        }
        if (dto.getCustCountry() != null) {
            customer.setCustAddrCountryCd(dto.getCustCountry().toUpperCase());
        }
        if (dto.getCustPhone1() != null) {
            customer.setCustPhoneNum1(dto.getCustPhone1());
        }
        if (dto.getCustPhone2() != null) {
            customer.setCustPhoneNum2(dto.getCustPhone2());
        }
        // SSN: strip formatting dashes for entity storage (entity field is 9 raw digits)
        if (dto.getCustSsn() != null) {
            customer.setCustSsn(stripNonDigits(dto.getCustSsn()));
        }
        if (dto.getCustDob() != null) {
            customer.setCustDob(dto.getCustDob());
        }
        // FICO: DTO stores as String, entity stores as Short
        if (!isBlankOrNull(dto.getCustFicoScore())) {
            customer.setCustFicoCreditScore(Short.parseShort(dto.getCustFicoScore().trim()));
        }
        if (dto.getCustGovtId() != null) {
            customer.setCustGovtIssuedId(dto.getCustGovtId());
        }
        if (dto.getCustEftAcct() != null) {
            customer.setCustEftAccountId(dto.getCustEftAcct());
        }
        // Profile flag maps to Primary Card Holder Indicator
        if (dto.getCustProfileFlag() != null) {
            customer.setCustPriCardHolderInd(dto.getCustProfileFlag().toUpperCase());
        }
    }

    /**
     * Assembles an AccountDto from Account and Customer entities.
     * Combines data from both datasets into a single DTO for API response,
     * mirroring the COBOL BMS map send operation that displays data from
     * both ACCTDAT and CUSTDAT simultaneously.
     */
    private AccountDto assembleAccountDto(Account account, Customer customer) {
        AccountDto dto = new AccountDto();

        // Account fields
        dto.setAcctId(account.getAcctId());
        dto.setAcctActiveStatus(account.getAcctActiveStatus());
        dto.setAcctCurrBal(account.getAcctCurrBal());
        dto.setAcctCreditLimit(account.getAcctCreditLimit());
        dto.setAcctCashCreditLimit(account.getAcctCashCreditLimit());
        dto.setAcctOpenDate(account.getAcctOpenDate());
        dto.setAcctExpDate(account.getAcctExpDate());
        dto.setAcctReissueDate(account.getAcctReissueDate());
        dto.setAcctCurrCycCredit(account.getAcctCurrCycCredit());
        dto.setAcctCurrCycDebit(account.getAcctCurrCycDebit());
        dto.setAcctGroupId(account.getAcctGroupId());

        // Customer fields
        dto.setCustId(customer.getCustId());
        dto.setCustFname(customer.getCustFirstName());
        dto.setCustMname(customer.getCustMiddleName());
        dto.setCustLname(customer.getCustLastName());
        dto.setCustAddr1(customer.getCustAddrLine1());
        dto.setCustAddr2(customer.getCustAddrLine2());
        dto.setCustCity(customer.getCustAddrLine3()); // City stored in addr-line-3
        dto.setCustState(customer.getCustAddrStateCd());
        dto.setCustZip(customer.getCustAddrZip());
        dto.setCustCountry(customer.getCustAddrCountryCd());
        dto.setCustPhone1(customer.getCustPhoneNum1());
        dto.setCustPhone2(customer.getCustPhoneNum2());
        dto.setCustSsn(customer.getCustSsn());
        dto.setCustDob(customer.getCustDob());
        dto.setCustFicoScore(customer.getCustFicoCreditScore() != null
                ? String.valueOf(customer.getCustFicoCreditScore()) : null);
        dto.setCustGovtId(customer.getCustGovtIssuedId());
        dto.setCustEftAcct(customer.getCustEftAccountId());
        dto.setCustProfileFlag(customer.getCustPriCardHolderInd());

        return dto;
    }

    // =========================================================================
    // Validation Methods
    // =========================================================================

    /**
     * Validates account ID input.
     * Maps COBOL 1210-EDIT-ACCOUNT (lines 1783-1822).
     * Account ID must be an 11-digit non-zero numeric string.
     *
     * @param acctId the account ID to validate
     * @throws ValidationException if the account ID is invalid
     */
    private void validateAccountId(String acctId) {
        if (isBlankOrNull(acctId)) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number must be supplied.");
        }
        String trimmed = acctId.trim();
        if (!trimmed.matches("\\d+")) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
        if (trimmed.length() != ACCOUNT_ID_LENGTH) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
        if (trimmed.chars().allMatch(c -> c == '0')) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
    }

    /**
     * Comprehensive field validation cascade — MUST match exact COBOL validation order.
     * Maps COBOL COACTUPC 1200-EDIT-MAP-INPUTS (lines 1429-1676).
     *
     * <p>Aggregates ALL validation errors before throwing a single {@link ValidationException},
     * matching the COBOL pattern of setting error flags and checking at the end.</p>
     *
     * <p>Validation order (25+ fields, preserved from COBOL):</p>
     * <ol>
     *   <li>Account Status (Y/N)</li>
     *   <li>Open Date (CCYYMMDD)</li>
     *   <li>Credit Limit (signed decimal)</li>
     *   <li>Expiry Date (CCYYMMDD)</li>
     *   <li>Cash Credit Limit (signed decimal)</li>
     *   <li>Reissue Date (CCYYMMDD)</li>
     *   <li>Current Balance (signed decimal)</li>
     *   <li>Current Cycle Credit (signed decimal)</li>
     *   <li>Current Cycle Debit (signed decimal)</li>
     *   <li>SSN (3-part: invalid 000/666/900-999)</li>
     *   <li>Date of Birth (date + not-in-future)</li>
     *   <li>FICO Score (range 300-850)</li>
     *   <li>First Name (required alpha)</li>
     *   <li>Middle Name (optional alpha)</li>
     *   <li>Last Name (required alpha)</li>
     *   <li>Address Line 1 (mandatory)</li>
     *   <li>State (alpha + state code lookup)</li>
     *   <li>ZIP (numeric required)</li>
     *   <li>City (required alpha)</li>
     *   <li>Country (required alpha)</li>
     *   <li>Phone 1 (optional, NANPA area code)</li>
     *   <li>Phone 2 (optional, NANPA area code)</li>
     *   <li>EFT Account ID (numeric required)</li>
     *   <li>Primary Card Holder (Y/N)</li>
     *   <li>Cross-field: State/ZIP prefix validation</li>
     * </ol>
     *
     * @param dto the AccountDto containing fields to validate
     * @throws ValidationException with aggregated list of all field errors
     */
    private void validateUpdateFields(AccountDto dto) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        boolean stateValid = false;
        boolean zipValid = false;

        // 1. Account Status (← 1220-EDIT-YESNO for ACUP-ACCT-STATUS)
        validateYesNo(dto.getAcctActiveStatus(), "acctActiveStatus", "Account Status", errors);

        // 2. Open Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctOpenDate(), "acctOpenDate", "Open Date", errors);

        // 3. Credit Limit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCreditLimit(), "acctCreditLimit", "Credit Limit", errors);

        // 4. Expiry Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctExpDate(), "acctExpDate", "Expiry Date", errors);

        // 5. Cash Credit Limit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCashCreditLimit(), "acctCashCreditLimit", "Cash Credit Limit", errors);

        // 6. Reissue Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctReissueDate(), "acctReissueDate", "Reissue Date", errors);

        // 7. Current Balance (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrBal(), "acctCurrBal", "Current Balance", errors);

        // 8. Current Cycle Credit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrCycCredit(), "acctCurrCycCredit", "Current Cycle Credit", errors);

        // 9. Current Cycle Debit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrCycDebit(), "acctCurrCycDebit", "Current Cycle Debit", errors);

        // 10. SSN (← 1265-EDIT-US-SSN, 3-part validation)
        validateSsn(dto.getCustSsn(), errors);

        // 11. Date of Birth (← EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH, not-in-future check)
        validateDateOfBirthField(dto.getCustDob(), errors);

        // 12. FICO Score (← 1275-EDIT-FICO-SCORE, range 300-850)
        validateFicoScore(dto.getCustFicoScore(), errors);

        // 13. First Name (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustFname(), "custFname", "First Name", 25, errors);

        // 14. Middle Name (← 1235-EDIT-ALPHA-OPT — optional)
        validateAlphaOptional(dto.getCustMname(), "custMname", "Middle Name", 25, errors);

        // 15. Last Name (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustLname(), "custLname", "Last Name", 25, errors);

        // 16. Address Line 1 (← 1215-EDIT-MANDATORY)
        validateMandatory(dto.getCustAddr1(), "custAddr1", "Address Line 1", errors);

        // 17. State (← 1225-EDIT-ALPHA-REQD + 1270-EDIT-US-STATE-CD)
        stateValid = validateStateField(dto.getCustState(), errors);

        // 18. ZIP (← 1245-EDIT-NUM-REQD, 5 digits)
        zipValid = validateZipField(dto.getCustZip(), errors);

        // 19. City (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustCity(), "custCity", "City", 50, errors);

        // 20. Country (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustCountry(), "custCountry", "Country", 3, errors);

        // 21. Phone 1 (← 1260-EDIT-US-PHONE-NUM — optional, 3-part NANPA)
        validatePhoneNumber(dto.getCustPhone1(), "custPhone1", "Phone 1", errors);

        // 22. Phone 2 (← 1260-EDIT-US-PHONE-NUM — optional, 3-part NANPA)
        validatePhoneNumber(dto.getCustPhone2(), "custPhone2", "Phone 2", errors);

        // 23. EFT Account ID (← 1245-EDIT-NUM-REQD)
        validateNumericRequired(dto.getCustEftAcct(), "custEftAcct", "EFT Account ID", 10, errors);

        // 24. Primary Card Holder Indicator (← 1220-EDIT-YESNO)
        validateYesNo(dto.getCustProfileFlag(), "custProfileFlag", "Primary Card Holder Indicator", errors);

        // 25. Cross-field: State/ZIP prefix (← 1280-EDIT-US-STATE-ZIP-CD)
        // Only validate if BOTH state AND ZIP passed individual validation
        if (stateValid && zipValid
                && !isBlankOrNull(dto.getCustState()) && !isBlankOrNull(dto.getCustZip())) {
            if (!validationLookupService.isValidStateZipPrefix(
                    dto.getCustState().toUpperCase(), dto.getCustZip())) {
                errors.add(new ValidationException.FieldError(
                        "custZip", dto.getCustZip(), "Invalid zip code for state " + dto.getCustState()));
            }
        }

        // Throw aggregated errors if any validation failed
        if (!errors.isEmpty()) {
            logger.warn("Account update validation failed with {} error(s)", errors.size());
            throw new ValidationException(errors);
        }
    }

    // =========================================================================
    // Field-Level Validation Helpers
    // =========================================================================

    /**
     * Validates a yes/no field (must be 'Y' or 'N', case-insensitive).
     * Maps COBOL 1220-EDIT-YESNO paragraph.
     */
    private void validateYesNo(String value, String fieldKey, String fieldLabel,
                               List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String upper = value.trim().toUpperCase();
        if (!"Y".equals(upper) && !"N".equals(upper)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be Y or N."));
        }
    }

    /**
     * Validates a required alphabetic field (letters and spaces only).
     * Maps COBOL 1225-EDIT-ALPHA-REQD paragraph.
     */
    private void validateAlphaRequired(String value, String fieldKey, String fieldLabel,
                                       int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " characters."));
            return;
        }
        if (!trimmed.matches("^[a-zA-Z ]+$")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " can have alphabets only."));
        }
    }

    /**
     * Validates an optional alphabetic field (letters and spaces only, if provided).
     * Maps COBOL 1235-EDIT-ALPHA-OPT paragraph.
     * Blank/null is valid for optional fields.
     */
    private void validateAlphaOptional(String value, String fieldKey, String fieldLabel,
                                       int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            return; // Optional field — blank is valid
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " characters."));
            return;
        }
        if (!trimmed.matches("^[a-zA-Z ]+$")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " can have alphabets only."));
        }
    }

    /**
     * Validates a required numeric field (digits only, non-zero, max length).
     * Maps COBOL 1245-EDIT-NUM-REQD paragraph.
     */
    private void validateNumericRequired(String value, String fieldKey, String fieldLabel,
                                         int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("\\d+")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be all numeric."));
            return;
        }
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " digits."));
            return;
        }
        if (trimmed.chars().allMatch(c -> c == '0')) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not be zero."));
        }
    }

    /**
     * Validates a mandatory field (any characters, must not be blank).
     * Maps COBOL 1215-EDIT-MANDATORY paragraph.
     */
    private void validateMandatory(String value, String fieldKey, String fieldLabel,
                                   List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
        }
    }

    /**
     * Validates a monetary BigDecimal field (must not be null).
     * Maps COBOL 1250-EDIT-SIGNED-9V2 paragraph.
     * In Java, the BigDecimal type already guarantees valid numeric format;
     * validation ensures the field is present.
     */
    private void validateMonetaryField(BigDecimal value, String fieldKey, String fieldLabel,
                                       List<ValidationException.FieldError> errors) {
        if (value == null) {
            errors.add(new ValidationException.FieldError(fieldKey, null,
                    fieldLabel + " must be supplied."));
        }
    }

    /**
     * Validates a US Social Security Number (3-part structure).
     * Maps COBOL 1265-EDIT-US-SSN (lines 2431-2491).
     *
     * <p>SSN structure: Part1(3) + Part2(2) + Part3(4) = 9 digits.</p>
     * <ul>
     *   <li>Part 1: Cannot be 000, 666, or 900-999 (INVALID-SSN-PART1)</li>
     *   <li>Part 2: Cannot be 00 (range 01-99)</li>
     *   <li>Part 3: Cannot be 0000 (range 0001-9999)</li>
     * </ul>
     */
    private void validateSsn(String ssn, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(ssn)) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be supplied."));
            return;
        }

        // Strip formatting characters (dashes, spaces) for validation
        String digits = stripNonDigits(ssn);

        if (digits.length() != SSN_LENGTH) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be exactly " + SSN_LENGTH + " digits."));
            return;
        }

        if (!digits.matches("\\d{9}")) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be all numeric."));
            return;
        }

        // Part 1: first 3 digits — cannot be 000, 666, or 900-999
        String part1 = digits.substring(0, 3);
        int part1Val = Integer.parseInt(part1);
        if (part1Val == 0 || part1Val == 666 || (part1Val >= 900 && part1Val <= 999)) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"));
        }

        // Part 2: middle 2 digits — cannot be 00
        String part2 = digits.substring(3, 5);
        int part2Val = Integer.parseInt(part2);
        if (part2Val == 0) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN 4th & 5th chars must not be zero."));
        }

        // Part 3: last 4 digits — cannot be 0000
        String part3 = digits.substring(5, 9);
        int part3Val = Integer.parseInt(part3);
        if (part3Val == 0) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN Last 4 chars must not be zero."));
        }
    }

    /**
     * Validates FICO credit score (numeric, range 300-850 inclusive).
     * Maps COBOL 1275-EDIT-FICO-SCORE (lines 2514-2533).
     */
    private void validateFicoScore(String ficoStr, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(ficoStr)) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score must be supplied."));
            return;
        }

        String trimmed = ficoStr.trim();
        if (!trimmed.matches("\\d+")) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score must be numeric."));
            return;
        }

        int ficoValue;
        try {
            ficoValue = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score is not a valid number."));
            return;
        }

        if (ficoValue < FICO_MIN || ficoValue > FICO_MAX) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score: should be between " + FICO_MIN + " and " + FICO_MAX));
        }
    }

    /**
     * Validates a US phone number (optional, 3-part structure with NANPA area code).
     * Maps COBOL 1260-EDIT-US-PHONE-NUM paragraph.
     *
     * <p>Phone format: area(3) + prefix(3) + line(4) = 10 digits.</p>
     * <ul>
     *   <li>Phone is OPTIONAL — blank/null is valid</li>
     *   <li>Area code: 3 digits, non-zero, validated against NANPA table</li>
     *   <li>Prefix: 3 digits, non-zero</li>
     *   <li>Line number: 4 digits, non-zero</li>
     * </ul>
     */
    private void validatePhoneNumber(String phone, String fieldKey, String fieldLabel,
                                     List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(phone)) {
            return; // Phone is optional — blank is valid
        }

        // Strip formatting characters to extract raw digits
        String digits = stripNonDigits(phone);

        if (digits.isEmpty()) {
            return; // All non-digit characters (treated as blank)
        }

        if (digits.length() != PHONE_DIGITS_LENGTH) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " must be a 10-digit phone number."));
            return;
        }

        // Area code: first 3 digits
        String areaCode = digits.substring(0, 3);
        int areaVal = Integer.parseInt(areaCode);
        if (areaVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " area code must not be zero."));
        } else if (!validationLookupService.isValidAreaCode(areaCode)) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + ": Not valid North America general purpose area code"));
        }

        // Prefix: middle 3 digits — numeric, non-zero
        String prefix = digits.substring(3, 6);
        int prefixVal = Integer.parseInt(prefix);
        if (prefixVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " prefix must not be zero."));
        }

        // Line number: last 4 digits — numeric, non-zero
        String lineNum = digits.substring(6, 10);
        int lineVal = Integer.parseInt(lineNum);
        if (lineVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " line number must not be zero."));
        }
    }

    /**
     * Validates a date field by formatting LocalDate to CCYYMMDD and delegating
     * to {@link DateValidationService#validateDate(String, String)}.
     * Maps COBOL EDIT-DATE-CCYYMMDD paragraph.
     */
    private void validateDateField(LocalDate date, String fieldKey, String fieldLabel,
                                   List<ValidationException.FieldError> errors) {
        if (date == null) {
            errors.add(new ValidationException.FieldError(fieldKey, null,
                    fieldLabel + " must be supplied."));
            return;
        }
        String dateStr = formatDate(date);
        DateValidationService.DateValidationResult result =
                dateValidationService.validateDate(dateStr, fieldLabel);
        if (!result.valid()) {
            errors.add(new ValidationException.FieldError(fieldKey, dateStr, result.fullMessage()));
        }
    }

    /**
     * Validates date of birth: date format plus not-in-future check.
     * Maps COBOL EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH paragraphs.
     */
    private void validateDateOfBirthField(LocalDate dob, List<ValidationException.FieldError> errors) {
        if (dob == null) {
            errors.add(new ValidationException.FieldError("custDob", null,
                    "Date of Birth must be supplied."));
            return;
        }
        String dateStr = formatDate(dob);
        DateValidationService.DateValidationResult result =
                dateValidationService.validateDateOfBirth(dateStr, "Date of Birth");
        if (!result.valid()) {
            errors.add(new ValidationException.FieldError("custDob", dateStr, result.fullMessage()));
        }
    }

    /**
     * Validates state code: required alphabetic field + lookup against CSLKPCDY state table.
     * Maps COBOL 1225-EDIT-ALPHA-REQD + 1270-EDIT-US-STATE-CD paragraphs.
     *
     * @return true if state is individually valid (used for cross-field validation)
     */
    private boolean validateStateField(String state, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(state)) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State must be supplied."));
            return false;
        }
        String trimmed = state.trim();
        if (!trimmed.matches("^[a-zA-Z]+$")) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State can have alphabets only."));
            return false;
        }
        if (!validationLookupService.isValidStateCode(trimmed.toUpperCase())) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State: is not a valid state code"));
            return false;
        }
        return true;
    }

    /**
     * Validates ZIP code: required, numeric, 5 digits, non-zero.
     * Maps COBOL 1245-EDIT-NUM-REQD for ZIP field.
     *
     * @return true if ZIP is individually valid (used for cross-field validation)
     */
    private boolean validateZipField(String zip, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(zip)) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must be supplied."));
            return false;
        }
        String trimmed = zip.trim();
        if (!trimmed.matches("\\d+")) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must be all numeric."));
            return false;
        }
        if (trimmed.length() < 5) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must be at least 5 digits."));
            return false;
        }
        if (trimmed.substring(0, 5).chars().allMatch(c -> c == '0')) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must not be zero."));
            return false;
        }
        return true;
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Formats a LocalDate to CCYYMMDD string for DateValidationService.
     * Maps COBOL PIC X(10) CCYYMMDD date string format.
     *
     * @param date the date to format
     * @return CCYYMMDD string representation, or null if date is null
     */
    private String formatDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(CCYYMMDD_FORMATTER);
    }

    /**
     * Strips all non-digit characters from a string.
     * Used to normalize phone numbers and SSNs that may contain
     * formatting characters (dashes, parentheses, spaces).
     *
     * @param value the input string
     * @return string containing only digit characters
     */
    private String stripNonDigits(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     * Maps COBOL SPACES / LOW-VALUES sentinel check.
     *
     * @param value the string to check
     * @return true if the value is effectively blank
     */
    private boolean isBlankOrNull(String value) {
        return value == null || value.trim().isEmpty();
    }
}
