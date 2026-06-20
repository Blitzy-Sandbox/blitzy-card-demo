package com.carddemo.service.account;

import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.AccountUpdateRequest;
import com.carddemo.model.dto.AccountUpdateResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.ValidationLookupService;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-maintenance service: the Java translation of the CICS online program
 * {@code COACTUPC} (source commit {@code 27d6c6f}), the largest program in
 * CardDemo. It validates submitted account and customer field changes and
 * atomically rewrites BOTH the account master and the customer master under the
 * JPA {@code @Version} optimistic lock.
 *
 * <p>This service houses the system's <strong>sole</strong> CICS
 * {@code SYNCPOINT ROLLBACK} ({@code COACTUPC} 9600-WRITE-PROCESSING): when the
 * customer rewrite fails after the account rewrite has already been applied, the
 * account rewrite is undone. That guarantee is reproduced here by executing the
 * dual {@link AccountRepository#saveAndFlush(Object)} and
 * {@link CustomerRepository#saveAndFlush(Object)} inside a single
 * {@link Transactional} method whose rollback-on-exception semantics undo the
 * account write if the customer write throws.
 *
 * <p>Traceability: {@code COACTUPC.PROCESS-UPDATE-ACCT} /
 * {@code 9600-WRITE-PROCESSING} maps to {@link #updateAccount(AccountUpdateRequest)};
 * the {@code 1200-EDIT-MAP-INPUTS} field-edit cascade maps to the ordered
 * private validation methods; {@code 9000-READ-ACCT} maps to the read chain; and
 * the {@code 9700-CHECK-CHANGE-IN-REC} before/after record-image comparison maps
 * to the JPA {@code @Version} optimistic lock on {@link Account}.
 *
 * <p>The COBOL pseudo-conversational confirm-then-commit turn-taking collapses
 * into this single stateless call: it validates the request and, when every edit
 * passes, persists the change. The COBOL source is referenced for lineage only
 * and is never copied into this project.
 */
@Service
public class AccountUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    /** Online date picture consumed by {@link DateValidationService}. */
    private static final String ONLINE_DATE_FORMAT = "YYYY-MM-DD";

    /** Scale of the COBOL {@code PIC S9(10)V99} money fields. */
    private static final int MONEY_SCALE = 2;

    /** Exclusive upper bound on money magnitude ({@code 10^10}; {@code PIC S9(10)V99}). */
    private static final BigDecimal MAX_MONEY_EXCLUSIVE = new BigDecimal("10000000000");

    /** Informational message echoed on a successful commit ({@code COACTUPC} L475). */
    private static final String SUCCESS_MESSAGE = "Changes committed to database";

    /** Optimistic-lock conflict message ({@code COACTUPC} L522). */
    private static final String CONCURRENCY_MESSAGE = "Record changed by some one else. Please review";

    /** Entity label carried on the {@link ConcurrencyException}. */
    private static final String ENTITY_ACCOUNT = "Account";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;

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

    /**
     * Validates and commits an account-maintenance request, mirroring
     * {@code COACTUPC.PROCESS-UPDATE-ACCT} / {@code 9600-WRITE-PROCESSING}.
     *
     * <p>Processing follows the COBOL order: (1) the read chain resolves the
     * managed {@link Account} and {@link Customer} via the card cross-reference;
     * (2) every input is validated in {@code 1200-EDIT-MAP-INPUTS} order with
     * first-error-wins semantics; (3) the validated values are applied to the
     * managed entities; (4) both entities are flushed inside this transaction so
     * the {@code @Version} optimistic lock fires and the dual write is atomic;
     * and (5) the persisted state is echoed back.
     *
     * <p>The dual update is atomic: because the method is {@link Transactional},
     * a failure of the customer write rolls back the already-flushed account
     * write, reproducing the CICS {@code SYNCPOINT ROLLBACK}.
     *
     * @param request the split-field account/customer update payload
     * @return the persisted values echoed back with a success message
     * @throws ValidationException     when any field edit fails (first failure)
     * @throws RecordNotFoundException when the cross-reference, account, or
     *                                 customer record cannot be located
     * @throws ConcurrencyException    when the record was changed concurrently
     *                                 (JPA {@code @Version} optimistic-lock
     *                                 failure on flush)
     */
    @Transactional
    public AccountUpdateResponse updateAccount(AccountUpdateRequest request) {
        Long acctId = parseAccountId(request.accountId());
        LOG.debug("Processing account update for account id {}", acctId);

        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw RecordNotFoundException.forKey("Account cross-reference", acctId);
        }
        Long custId = xrefs.get(0).getXrefCustId();

        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Account", acctId));
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Customer", custId));

        String openDate = assembleDate(request.openYear(), request.openMonth(), request.openDay());
        String expiryDate = assembleDate(request.expirationYear(), request.expirationMonth(),
                request.expirationDay());
        String reissueDate = assembleDate(request.reissueYear(), request.reissueMonth(),
                request.reissueDay());
        String dateOfBirth = assembleDate(request.dobYear(), request.dobMonth(), request.dobDay());

        validateInputs(request, openDate, expiryDate, reissueDate, dateOfBirth);

        applyAccountChanges(account, request, openDate, expiryDate, reissueDate);
        applyCustomerChanges(customer, request, dateOfBirth);

        persist(account, customer);

        return buildResponse(request, account);
    }

    /**
     * Parses the 11-digit account key ({@code 1210-EDIT-ACCOUNT}); a non-numeric
     * value is rejected as a field error rather than allowed to escape as an
     * unchecked {@link NumberFormatException}.
     */
    private Long parseAccountId(String accountId) {
        try {
            return Long.parseLong(accountId.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new ValidationException(
                    "Account Filter must be a non-zero 11 digit number", "accountId");
        }
    }

    /**
     * Runs the {@code 1200-EDIT-MAP-INPUTS} field-edit cascade in the exact COBOL
     * order with first-error-wins semantics: the first failing edit throws a
     * {@link ValidationException} carrying the COBOL message, so no update occurs
     * (the COBOL captured only the first message and skipped the update on any
     * error). The cross-field state/ZIP check runs last and is reached only when
     * the individual state and ZIP edits have both passed.
     */
    private void validateInputs(AccountUpdateRequest request, String openDate, String expiryDate,
                                String reissueDate, String dateOfBirth) {
        requireYesNo(request.accountStatus(), "Account Status", "accountStatus");

        validateDateField(openDate, "Open Date", "openDate");
        requireMoney(request.creditLimit(), "Credit Limit", "creditLimit");
        validateDateField(expiryDate, "Expiry Date", "expiryDate");
        requireMoney(request.cashCreditLimit(), "Cash Credit Limit", "cashCreditLimit");
        validateDateField(reissueDate, "Reissue Date", "reissueDate");
        requireMoney(request.currentBalance(), "Current Balance", "currentBalance");
        requireMoney(request.currentCycleCredit(), "Current Cycle Credit Limit", "currentCycleCredit");
        requireMoney(request.currentCycleDebit(), "Current Cycle Debit Limit", "currentCycleDebit");

        validateSsn(request);
        validateDateOfBirth(dateOfBirth);
        validateFicoScore(request.ficoScore());

        requireAlpha(request.firstName(), "First Name", "firstName", 25, false);
        requireAlpha(request.middleName(), "Middle Name", "middleName", 25, true);
        requireAlpha(request.lastName(), "Last Name", "lastName", 25, false);

        requireMandatory(request.addressLine1(), "Address Line 1", "addressLine1");

        requireAlpha(request.stateCode(), "State", "stateCode", 2, false);
        if (!validationLookupService.isValidStateCode(request.stateCode().trim())) {
            throw new ValidationException("State: is not a valid state code", "stateCode");
        }

        requireNumeric(request.zipCode(), "Zip", "zipCode", 5);

        // COBOL labels customer Address-Line-3 as "City".
        requireAlpha(request.city(), "City", "city", 50, false);
        requireAlpha(request.countryCode(), "Country", "countryCode", 3, false);

        validatePhone(request.phone1Area(), request.phone1Prefix(), request.phone1Line(),
                "Phone Number 1", "phone1");
        validatePhone(request.phone2Area(), request.phone2Prefix(), request.phone2Line(),
                "Phone Number 2", "phone2");

        requireNumeric(request.eftAccountId(), "EFT Account Id", "eftAccountId", 10);
        requireYesNo(request.primaryCardHolderIndicator(), "Primary Card Holder",
                "primaryCardHolderIndicator");

        // Cross-field edit (1280-EDIT-US-STATE-ZIP-CD): state and ZIP already passed.
        if (!validationLookupService.isValidStateZip(request.stateCode().trim(),
                request.zipCode().trim())) {
            throw new ValidationException("Invalid zip code for state", "zipCode");
        }
    }

    /**
     * Assembles the split year/month/day parts into the {@code YYYY-MM-DD} string
     * the database stores and {@link DateValidationService} consumes (the
     * {@code 9600} build block and {@code EDIT-DATE-CCYYMMDD} use this layout).
     */
    private static String assembleDate(String year, String month, String day) {
        return nz(year) + "-" + nz(month) + "-" + nz(day);
    }

    /**
     * Validates an assembled date via {@link DateValidationService}
     * ({@code EDIT-DATE-CCYYMMDD} which delegated to {@code CSUTLDTC}/{@code CEEDAYS}).
     */
    private void validateDateField(String assembledDate, String label, String field) {
        if (!dateValidationService.validateDate(assembledDate, ONLINE_DATE_FORMAT).isAcceptable()) {
            throw new ValidationException(label + " is not valid.", field);
        }
    }

    /**
     * Validates the date of birth ({@code EDIT-DATE-OF-BIRTH}): a valid calendar
     * date that lies strictly in the past. The date string parses cleanly because
     * {@link #validateDateField} has already accepted it.
     */
    private void validateDateOfBirth(String dateOfBirth) {
        validateDateField(dateOfBirth, "Date of Birth", "dateOfBirth");
        LocalDate dob = LocalDate.parse(dateOfBirth);
        if (!dob.isBefore(LocalDate.now())) {
            throw new ValidationException("Date of Birth:cannot be in the future ", "dateOfBirth");
        }
    }


    /** Required Y/N flag ({@code 1220-EDIT-YESNO}). */
    private void requireYesNo(String value, String label, String field) {
        if (isBlank(value)) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        String token = value.trim();
        if (!"Y".equals(token) && !"N".equals(token)) {
            throw new ValidationException(label + " must be Y or N.", field);
        }
    }

    /**
     * Alphabetic field ({@code 1225-EDIT-ALPHA-REQD} / {@code 1235-EDIT-ALPHA-OPT}):
     * letters and spaces only. When {@code optional}, a blank value is accepted.
     */
    private void requireAlpha(String value, String label, String field, int maxLength,
                              boolean optional) {
        if (isBlank(value)) {
            if (optional) {
                return;
            }
            throw new ValidationException(label + " must be supplied.", field);
        }
        String token = value.trim();
        if (token.length() > maxLength || !isAlpha(token)) {
            throw new ValidationException(label + " can have alphabets only.", field);
        }
    }

    /** Required non-blank field ({@code 1215-EDIT-MANDATORY}). */
    private void requireMandatory(String value, String label, String field) {
        if (isBlank(value)) {
            throw new ValidationException(label + " must be supplied.", field);
        }
    }

    /**
     * Required, fixed-width, non-zero numeric field ({@code 1245-EDIT-NUM-REQD}):
     * a value with embedded blanks or the wrong width fails the COBOL
     * {@code IS NUMERIC} test exactly as a too-short fixed-width field would.
     */
    private void requireNumeric(String value, String label, String field, int exactLength) {
        if (isBlank(value)) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        String token = value.trim();
        if (token.length() != exactLength || !isAllDigits(token)) {
            throw new ValidationException(label + " must be all numeric.", field);
        }
        if (isZero(token)) {
            throw new ValidationException(label + " must not be zero.", field);
        }
    }

    /**
     * Signed money field ({@code 1250-EDIT-SIGNED-9V2}): present, scale at most 2,
     * and magnitude below {@code 10^10} ({@code PIC S9(10)V99}). Magnitude is
     * compared with {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}).
     */
    private void requireMoney(BigDecimal value, String label, String field) {
        if (value == null) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        if (value.scale() > MONEY_SCALE || value.abs().compareTo(MAX_MONEY_EXCLUSIVE) >= 0) {
            throw new ValidationException(label + " is not valid", field);
        }
    }

    /**
     * Three-part SSN ({@code 1265-EDIT-US-SSN}): each part is a required, non-zero
     * fixed-width number; the area part must not be {@code 000}, {@code 666}, or in
     * {@code 900}-{@code 999}.
     */
    private void validateSsn(AccountUpdateRequest request) {
        requireNumeric(request.ssnPart1(), "SSN: First 3 chars", "ssnPart1", 3);
        if (isInvalidSsnPart1(request.ssnPart1().trim())) {
            throw new ValidationException(
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssnPart1");
        }
        requireNumeric(request.ssnPart2(), "SSN 4th & 5th chars", "ssnPart2", 2);
        requireNumeric(request.ssnPart3(), "SSN Last 4 chars", "ssnPart3", 4);
    }

    private static boolean isInvalidSsnPart1(String part1) {
        int value = Integer.parseInt(part1);
        return value == 0 || value == 666 || (value >= 900 && value <= 999);
    }

    /**
     * FICO score ({@code 1245-EDIT-NUM-REQD} then {@code 1275-EDIT-FICO-SCORE}):
     * a 3-digit number in the inclusive range 300-850.
     */
    private void validateFicoScore(String ficoScore) {
        requireNumeric(ficoScore, "FICO Score", "ficoScore", 3);
        int score = Integer.parseInt(ficoScore.trim());
        if (score < 300 || score > 850) {
            throw new ValidationException("FICO Score: should be between 300 and 850", "ficoScore");
        }
    }

    /**
     * US telephone number ({@code 1260-EDIT-US-PHONE-NUM}): optional, so all three
     * parts blank is accepted; otherwise each part is validated in area, prefix,
     * line order with first-error-wins semantics.
     */
    private void validatePhone(String area, String prefix, String line, String label, String field) {
        if (isBlank(area) && isBlank(prefix) && isBlank(line)) {
            return;
        }
        validatePhoneArea(area, label, field);
        validatePhonePrefix(prefix, label, field);
        validatePhoneLine(line, label, field);
    }

    private void validatePhoneArea(String area, String label, String field) {
        if (isBlank(area)) {
            throw new ValidationException(label + ": Area code must be supplied.", field);
        }
        String token = area.trim();
        if (token.length() != 3 || !isAllDigits(token)) {
            throw new ValidationException(label + ": Area code must be A 3 digit number.", field);
        }
        if (isZero(token)) {
            throw new ValidationException(label + ": Area code cannot be zero", field);
        }
        if (!validationLookupService.isValidAreaCode(token)) {
            throw new ValidationException(
                    label + ": Not valid North America general purpose area code", field);
        }
    }

    private void validatePhonePrefix(String prefix, String label, String field) {
        if (isBlank(prefix)) {
            throw new ValidationException(label + ": Prefix code must be supplied.", field);
        }
        String token = prefix.trim();
        if (token.length() != 3 || !isAllDigits(token)) {
            throw new ValidationException(label + ": Prefix code must be A 3 digit number.", field);
        }
        if (isZero(token)) {
            throw new ValidationException(label + ": Prefix code cannot be zero", field);
        }
    }

    private void validatePhoneLine(String line, String label, String field) {
        if (isBlank(line)) {
            throw new ValidationException(label + ": Line number code must be supplied.", field);
        }
        String token = line.trim();
        if (token.length() != 4 || !isAllDigits(token)) {
            throw new ValidationException(label + ": Line number code must be A 4 digit number.", field);
        }
        if (isZero(token)) {
            throw new ValidationException(label + ": Line number code cannot be zero", field);
        }
    }


    /**
     * Applies the validated values to the managed {@link Account}
     * ({@code 9600-WRITE-PROCESSING} account-master build block). The
     * {@code acctAddrZip} column is intentionally left untouched because the COBOL
     * {@code ACCT-UPDATE-RECORD} does not include it.
     */
    private void applyAccountChanges(Account account, AccountUpdateRequest request, String openDate,
                                     String expiryDate, String reissueDate) {
        account.setAcctActiveStatus(request.accountStatus());
        account.setAcctCurrBal(normalizeMoney(request.currentBalance()));
        account.setAcctCreditLimit(normalizeMoney(request.creditLimit()));
        account.setAcctCashCreditLimit(normalizeMoney(request.cashCreditLimit()));
        account.setAcctCurrCycCredit(normalizeMoney(request.currentCycleCredit()));
        account.setAcctCurrCycDebit(normalizeMoney(request.currentCycleDebit()));
        account.setAcctOpenDate(openDate);
        account.setAcctExpiraionDate(expiryDate);
        account.setAcctReissueDate(reissueDate);
        account.setAcctGroupId(request.accountGroupId());
    }

    /**
     * Applies the validated values to the managed {@link Customer}
     * ({@code 9600-WRITE-PROCESSING} customer-master build block). City maps to
     * address line 3, the SSN parts concatenate into the numeric SSN, and each
     * phone number is formatted {@code (AAA)PPP-LLLL}.
     */
    private void applyCustomerChanges(Customer customer, AccountUpdateRequest request,
                                      String dateOfBirth) {
        customer.setCustFirstName(request.firstName());
        customer.setCustMiddleName(request.middleName());
        customer.setCustLastName(request.lastName());
        customer.setCustAddrLine1(request.addressLine1());
        customer.setCustAddrLine2(request.addressLine2());
        customer.setCustAddrLine3(request.city());
        customer.setCustAddrStateCd(request.stateCode());
        customer.setCustAddrCountryCd(request.countryCode());
        customer.setCustAddrZip(request.zipCode());
        customer.setCustPhoneNum1(formatPhone(request.phone1Area(), request.phone1Prefix(),
                request.phone1Line()));
        customer.setCustPhoneNum2(formatPhone(request.phone2Area(), request.phone2Prefix(),
                request.phone2Line()));
        customer.setCustSsn(Long.parseLong(
                request.ssnPart1().trim() + request.ssnPart2().trim() + request.ssnPart3().trim()));
        customer.setCustGovtIssuedId(request.governmentIssuedId());
        customer.setCustDobYyyyMmDd(dateOfBirth);
        customer.setCustEftAccountId(request.eftAccountId());
        customer.setCustPriCardHolderInd(request.primaryCardHolderIndicator());
        customer.setCustFicoCreditScore(Integer.valueOf(request.ficoScore().trim()));
    }

    /** Formats a phone number {@code (AAA)PPP-LLLL}; all-blank parts yield empty. */
    private static String formatPhone(String area, String prefix, String line) {
        if (isBlank(area) && isBlank(prefix) && isBlank(line)) {
            return "";
        }
        return "(" + nz(area) + ")" + nz(prefix) + "-" + nz(line);
    }

    /** Normalizes a money amount to scale 2 with banker's rounding. */
    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Persists both masters inside this transaction ({@code 9600} REWRITE ACCT +
     * REWRITE CUST + the sole {@code SYNCPOINT ROLLBACK}). The forced dual flush
     * makes the {@code @Version} optimistic lock fire deterministically; a flush
     * failure of either entity rolls back the whole transaction, so a failed
     * customer write undoes the already-flushed account write.
     */
    private void persist(Account account, Customer customer) {
        try {
            accountRepository.saveAndFlush(account);
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            throw new ConcurrencyException(CONCURRENCY_MESSAGE, ENTITY_ACCOUNT, ex);
        }
    }

    /**
     * Builds the success response ({@code COACTUPC} redisplay path): the split
     * fields are echoed and the money amounts are taken from the persisted
     * account, and the informational message confirms the commit. No version
     * token is surfaced; concurrency is enforced by the JPA {@code @Version}
     * lock that fired during {@link #persist(Account, Customer)}.
     */
    private AccountUpdateResponse buildResponse(AccountUpdateRequest request, Account account) {
        return new AccountUpdateResponse(
                request.accountId(),
                request.accountStatus(),
                request.openYear(), request.openMonth(), request.openDay(),
                account.getAcctCreditLimit(),
                request.expirationYear(), request.expirationMonth(), request.expirationDay(),
                account.getAcctCashCreditLimit(),
                request.reissueYear(), request.reissueMonth(), request.reissueDay(),
                account.getAcctCurrBal(),
                account.getAcctCurrCycCredit(),
                request.accountGroupId(),
                account.getAcctCurrCycDebit(),
                request.customerId(),
                request.ssnPart1(), request.ssnPart2(), request.ssnPart3(),
                request.dobYear(), request.dobMonth(), request.dobDay(),
                request.ficoScore(),
                request.firstName(), request.middleName(), request.lastName(),
                request.addressLine1(), request.stateCode(), request.addressLine2(),
                request.zipCode(), request.city(), request.countryCode(),
                request.phone1Area(), request.phone1Prefix(), request.phone1Line(),
                request.governmentIssuedId(),
                request.phone2Area(), request.phone2Prefix(), request.phone2Line(),
                request.eftAccountId(),
                request.primaryCardHolderIndicator(),
                SUCCESS_MESSAGE,
                null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isZero(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlpha(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean letter = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            if (!letter && ch != ' ') {
                return false;
            }
        }
        return true;
    }

}
