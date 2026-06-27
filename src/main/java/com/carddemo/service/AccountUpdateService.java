/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.dto.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;

import jakarta.persistence.OptimisticLockException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account update service backing {@code PUT /api/accounts/{id}} (CICS
 * transaction {@code CAUP} / program {@code COACTUPC}) at source commit
 * {@code 27d6c6f}.
 *
 * <p>Updates an account together with its owning {@link Customer} as a single
 * atomic unit of work. The customer is resolved from the supplied account id
 * through the {@link CardXref} cross-reference; both the {@link Account}
 * ({@code CVACT01Y}) and {@link Customer} ({@code CVCUS01Y}) records are
 * rewritten together. Behavior:</p>
 * <ul>
 *   <li><strong>Load</strong> &mdash; resolve the cross-reference, account and
 *       customer records, surfacing the byte-exact not-found messages.</li>
 *   <li><strong>Validate</strong> &mdash; run the ordered field edits, delegating
 *       date checks to {@link DateValidationService} and area-code / state /
 *       state-ZIP checks to {@link ValidationLookupService}; field failures are
 *       collected into an insertion-ordered map and raised as a single
 *       {@link ValidationException}.</li>
 *   <li><strong>Apply</strong> &mdash; reassemble the segmented dates, SSN and
 *       telephone numbers and copy the validated values onto the loaded
 *       entities; monetary amounts are held as {@link BigDecimal} scaled to two
 *       fraction digits using {@link RoundingMode#HALF_EVEN}.</li>
 *   <li><strong>Persist</strong> &mdash; save both records within one
 *       {@code @Transactional} boundary; an optimistic-locking conflict is
 *       translated to {@link ConcurrentUpdateException}.</li>
 * </ul>
 *
 * <p>The component is stateless and therefore thread-safe; all collaborators are
 * supplied through constructor injection.</p>
 *
 * <p>Design rationale (decomposition of {@code COACTUPC} into focused methods,
 * the {@code @Transactional}/{@code SYNCPOINT} boundary, and the {@code @Version}
 * translation of the {@code 9700-CHECK-CHANGE-IN-REC} guard) is recorded in
 * {@code DECISION_LOG.md} (D-032, D-008, D-007); COBOL paragraph&#8594;method
 * mappings are in {@code TRACEABILITY_MATRIX.md}.</p>
 */
@Service
public class AccountUpdateService {

    /** Scale applied to every monetary amount, matching COBOL {@code PIC S9(10)V99}. */
    private static final int MONEY_SCALE = 2;

    /** Maximum integer digits permitted for a {@code PIC S9(10)V99} amount. */
    private static final int MONEY_MAX_INTEGER_DIGITS = 10;

    /** Lowest account identifier accepted by the {@code 1210-EDIT-ACCOUNT} edit. */
    private static final long ACCOUNT_ID_MIN = 1L;

    /** Highest 11-digit account identifier accepted by the {@code 1210-EDIT-ACCOUNT} edit. */
    private static final long ACCOUNT_ID_MAX = 99_999_999_999L;

    /** Inclusive lower bound of a valid FICO score ({@code 1275-EDIT-FICO-SCORE}). */
    private static final int FICO_MIN = 300;

    /** Inclusive upper bound of a valid FICO score ({@code 1275-EDIT-FICO-SCORE}). */
    private static final int FICO_MAX = 850;

    /** Invalid SSN first-group constant rejected alongside zero and 900-999. */
    private static final int SSN_INVALID_GROUP = 666;

    /** Lower bound of the invalid SSN first-group range 900-999. */
    private static final int SSN_RESERVED_RANGE_LOW = 900;

    /** Upper bound of the invalid SSN first-group range 900-999. */
    private static final int SSN_RESERVED_RANGE_HIGH = 999;

    private static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";
    private static final String SUFFIX_MUST_BE_NUMERIC = " must be all numeric.";
    private static final String SUFFIX_MUST_NOT_BE_ZERO = " must not be zero.";
    private static final String SUFFIX_IS_NOT_VALID = " is not valid";
    private static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";
    private static final String SUFFIX_MUST_BE_YES_NO = " must be Y or N.";

    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_ACCT_NOT_VALID =
            "Account number must be a non zero 11 digit number";
    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_NO_CHANGE =
            "No change detected with respect to values fetched.";
    private static final String MSG_ACCT_STATUS_YES_NO = "Account Active Status must be Y or N";
    private static final String MSG_CRED_LIMIT_BLANK = "Credit Limit must be supplied";
    private static final String MSG_CRED_LIMIT_INVALID = "Credit Limit is not valid";
    private static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";
    private static final String MSG_LAST_NAME_BLANK = "Last name not provided";
    private static final String MSG_NAME_NOT_ALPHA = "Name can only contain alphabets and spaces";
    private static final String MSG_STATE_INVALID = "State: is not a valid state code";
    private static final String MSG_FICO_RANGE = "FICO Score: should be between 300 and 850";
    private static final String MSG_SSN_PART1_RANGE =
            "SSN: First 3 chars: should not be 000, 666, or between 900 and 999";
    private static final String MSG_STATE_ZIP_INVALID = "Invalid zip code for state";

    private static final String MSG_XREF_NOT_FOUND =
            "Did not find this account in account card xref file";
    private static final String MSG_ACCT_NOT_FOUND =
            "Did not find this account in account master file";
    private static final String MSG_CUST_NOT_FOUND =
            "Did not find associated customer in master file";

    private static final String KEY_ACCOUNT_ID = "accountId";
    private static final String KEY_EXPIRATION_DATE = "expirationDate";
    private static final String KEY_SSN = "ssn";
    private static final String KEY_FICO = "ficoScore";
    private static final String KEY_STATE = "state";
    private static final String KEY_ZIP = "zipCode";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;

    /**
     * Creates the service with its mandatory collaborators.
     *
     * @param accountRepository       repository for {@link Account} master records
     * @param customerRepository      repository for {@link Customer} master records
     * @param cardXrefRepository      repository for the {@link CardXref} cross-reference
     * @param dateValidationService   service validating open / expiry / reissue / birth dates
     * @param validationLookupService service validating area codes, state codes and state-ZIP combinations
     */
    public AccountUpdateService(AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                CardXrefRepository cardXrefRepository,
                                DateValidationService dateValidationService,
                                ValidationLookupService validationLookupService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
    }

    /**
     * Updates the account and its owning customer as one atomic unit of work.
     *
     * <p>The account identifier is validated first; the cross-reference, account
     * and customer records are then loaded; the submitted field values are
     * validated in order; and the validated values are applied to both records
     * and saved within the same transaction. A version conflict on either record
     * is surfaced as a {@link ConcurrentUpdateException}.</p>
     *
     * @param accountId the account identifier from the request path
     * @param request   the submitted update payload
     * @return the refreshed account view assembled from the saved records
     * @throws ValidationException        if the account identifier or any field edit fails,
     *                                    if no input was supplied, or if nothing changed
     * @throws RecordNotFoundException    if the cross-reference, account or customer record is absent
     * @throws ConcurrentUpdateException  if another transaction changed either record first
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountDto.ViewResponse updateAccount(Long accountId, AccountDto.UpdateRequest request) {
        validateAccountId(accountId);

        if (request == null || isNoInput(request)) {
            throw new ValidationException(MSG_NO_INPUT);
        }

        CardXref xref = loadXref(accountId);
        Account account = loadAccount(accountId);
        Customer customer = loadCustomer(xref.getXrefCustId());

        Map<String, String> fieldErrors = validateFields(request);
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(firstMessage(fieldErrors), fieldErrors);
        }

        verifyVersion(account, request.version());

        boolean accountChanged = applyAccountChanges(account, request);
        boolean customerChanged = applyCustomerChanges(customer, request);
        if (!accountChanged && !customerChanged) {
            throw new ValidationException(MSG_NO_CHANGE);
        }

        persist(account, customer);

        return toViewResponse(account, customer);
    }

    /**
     * Reproduces the COBOL {@code 9300-CHECK-CHANGE-IN-REC} re-read-and-compare
     * guard on the stateless REST surface. The client echoes the {@code version}
     * it last read on {@link AccountDto.UpdateRequest}; if it no longer matches
     * the freshly loaded account's JPA {@code @Version}, another transaction
     * committed between the client's read and this write, so the update is
     * rejected with a {@link ConcurrentUpdateException} (HTTP 409) instead of
     * silently overwriting the intervening change. A {@code null} client version
     * is treated as a mismatch so a caller that omits the token cannot bypass the
     * guard. The account version is the single optimistic-locking token for the
     * dual ACCOUNT+CUSTOMER unit of work; the in-flight {@code @Version} check at
     * flush time still guards a truly concurrent parallel write.
     *
     * @param account       the freshly loaded account carrying the current version
     * @param clientVersion the version the client last read, echoed on the request
     * @throws ConcurrentUpdateException if the versions differ or the client
     *                                   version is {@code null}
     */
    private void verifyVersion(Account account, Long clientVersion) {
        Long current = account.getVersion();
        if (clientVersion == null || !clientVersion.equals(current)) {
            throw new ConcurrentUpdateException();
        }
    }

    private void validateAccountId(Long accountId) {
        if (accountId == null) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put(KEY_ACCOUNT_ID, MSG_ACCT_NOT_PROVIDED);
            throw new ValidationException(MSG_ACCT_NOT_PROVIDED, errors);
        }
        if (accountId < ACCOUNT_ID_MIN || accountId > ACCOUNT_ID_MAX) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put(KEY_ACCOUNT_ID, MSG_ACCT_NOT_VALID);
            throw new ValidationException(MSG_ACCT_NOT_VALID, errors);
        }
    }

    private CardXref loadXref(Long accountId) {
        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(accountId);
        if (matches.isEmpty()) {
            throw new RecordNotFoundException(MSG_XREF_NOT_FOUND);
        }
        return matches.get(0);
    }

    private Account loadAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
    }

    private Customer loadCustomer(Long customerId) {
        if (customerId == null) {
            throw new RecordNotFoundException(MSG_CUST_NOT_FOUND);
        }
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_CUST_NOT_FOUND));
    }

    /**
     * Runs the {@code 1200-EDIT-MAP-INPUTS} field edits in their COBOL order and
     * collects per-field failures into an insertion-ordered map.
     *
     * <p>The order is preserved exactly: account status, open date, credit
     * limit, expiry date, cash credit limit, reissue date, current balance,
     * current cycle credit, current cycle debit, SSN, date of birth, FICO score,
     * first / middle / last name, address line 1, state, ZIP, city, country,
     * telephone numbers, EFT account id, primary-card-holder flag, and finally
     * the cross-field state-ZIP combination check.</p>
     *
     * @param request the submitted update payload
     * @return a map keyed by view-response field name; empty when every edit passes
     */
    private Map<String, String> validateFields(AccountDto.UpdateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        editYesNo("Account Active Status", request.accountStatus(), "accountStatus",
                MSG_ACCT_STATUS_YES_NO, errors);
        editDate(request.openYear(), request.openMonth(), request.openDay(), "Open Date",
                "openDate", errors);
        editMoney(request.creditLimit(), "creditLimit", MSG_CRED_LIMIT_BLANK,
                MSG_CRED_LIMIT_INVALID, errors);
        editExpiry(request, errors);
        editMoney(request.cashCreditLimit(), "cashCreditLimit",
                "Cash Credit Limit" + SUFFIX_MUST_BE_SUPPLIED,
                "Cash Credit Limit" + SUFFIX_IS_NOT_VALID, errors);
        editDate(request.reissueYear(), request.reissueMonth(), request.reissueDay(),
                "Reissue Date", "reissueDate", errors);
        editMoney(request.currentBalance(), "currentBalance",
                "Current Balance" + SUFFIX_MUST_BE_SUPPLIED,
                "Current Balance" + SUFFIX_IS_NOT_VALID, errors);
        editMoney(request.currentCycleCredit(), "currentCycleCredit",
                "Current Cycle Credit Limit" + SUFFIX_MUST_BE_SUPPLIED,
                "Current Cycle Credit Limit" + SUFFIX_IS_NOT_VALID, errors);
        editMoney(request.currentCycleDebit(), "currentCycleDebit",
                "Current Cycle Debit Limit" + SUFFIX_MUST_BE_SUPPLIED,
                "Current Cycle Debit Limit" + SUFFIX_IS_NOT_VALID, errors);
        editSsn(request, errors);
        editDateOfBirth(request, errors);
        editFico(request, errors);
        editAlphaRequired("First Name", request.firstName(), "firstName", errors);
        editAlphaOptional("Middle Name", request.middleName(), "middleName", errors);
        editLastName(request.lastName(), errors);
        editMandatory("Address Line 1", request.addressLine1(), "addressLine1", errors);
        editState(request.state(), errors);
        editNumeric("Zip", request.zipCode(), KEY_ZIP, errors);
        editAlphaRequired("City", request.city(), "city", errors);
        editAlphaRequired("Country", request.country(), "country", errors);
        editPhone("Phone Number 1", request.phone1Area(), request.phone1Prefix(),
                request.phone1Line(), "phone1", errors);
        editPhone("Phone Number 2", request.phone2Area(), request.phone2Prefix(),
                request.phone2Line(), "phone2", errors);
        editNumeric("EFT Account Id", request.eftAccountId(), "eftAccountId", errors);
        editYesNo("Primary Card Holder", request.primaryCardHolder(), "primaryCardHolder",
                "Primary Card Holder" + SUFFIX_MUST_BE_YES_NO, errors);
        editStateZip(request, errors);

        return errors;
    }

    private void editYesNo(String label, String value, String key, String message,
                           Map<String, String> errors) {
        if (isBlank(value)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_BE_SUPPLIED);
            return;
        }
        String normalized = value.trim();
        if (!normalized.equals("Y") && !normalized.equals("N")) {
            errors.putIfAbsent(key, message);
        }
    }

    private void editMandatory(String label, String value, String key,
                               Map<String, String> errors) {
        if (isBlank(value)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_BE_SUPPLIED);
        }
    }

    private boolean editNumeric(String label, String value, String key,
                                Map<String, String> errors) {
        if (isBlank(value)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_BE_SUPPLIED);
            return false;
        }
        String normalized = value.trim();
        if (!isAllDigits(normalized)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_BE_NUMERIC);
            return false;
        }
        if (isAllZeros(normalized)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_NOT_BE_ZERO);
            return false;
        }
        return true;
    }

    private void editMoney(BigDecimal value, String key, String blankMessage,
                           String invalidMessage, Map<String, String> errors) {
        if (value == null) {
            errors.putIfAbsent(key, blankMessage);
            return;
        }
        if (value.precision() - value.scale() > MONEY_MAX_INTEGER_DIGITS
                || value.scale() > MONEY_SCALE) {
            errors.putIfAbsent(key, invalidMessage);
        }
    }

    private void editAlphaRequired(String label, String value, String key,
                                   Map<String, String> errors) {
        if (isBlank(value)) {
            errors.putIfAbsent(key, label + SUFFIX_MUST_BE_SUPPLIED);
            return;
        }
        if (!isAlphaWithSpaces(value)) {
            errors.putIfAbsent(key, label + SUFFIX_ALPHABETS_ONLY);
        }
    }

    private void editAlphaOptional(String label, String value, String key,
                                   Map<String, String> errors) {
        if (isBlank(value)) {
            return;
        }
        if (!isAlphaWithSpaces(value)) {
            errors.putIfAbsent(key, label + SUFFIX_ALPHABETS_ONLY);
        }
    }

    private void editLastName(String value, Map<String, String> errors) {
        if (isBlank(value)) {
            errors.putIfAbsent("lastName", MSG_LAST_NAME_BLANK);
            return;
        }
        if (!isAlphaWithSpaces(value)) {
            errors.putIfAbsent("lastName", MSG_NAME_NOT_ALPHA);
        }
    }

    private void editState(String value, Map<String, String> errors) {
        editAlphaRequired("State", value, KEY_STATE, errors);
        if (errors.containsKey(KEY_STATE)) {
            return;
        }
        if (!validationLookupService.isValidStateCode(value.trim())) {
            errors.putIfAbsent(KEY_STATE, MSG_STATE_INVALID);
        }
    }

    private void editDate(String year, String month, String day, String label, String key,
                          Map<String, String> errors) {
        try {
            dateValidationService.validateDateParts(year, month, day, label);
        } catch (ValidationException ex) {
            errors.putIfAbsent(key, ex.getMessage());
        }
    }

    private void editDateOfBirth(AccountDto.UpdateRequest request, Map<String, String> errors) {
        try {
            dateValidationService.validateDateOfBirth(request.dobYear(), request.dobMonth(),
                    request.dobDay(), "Date of Birth");
        } catch (ValidationException ex) {
            errors.putIfAbsent("dateOfBirth", ex.getMessage());
        }
    }

    private void editExpiry(AccountDto.UpdateRequest request, Map<String, String> errors) {
        Integer month = parseIntegerOrNull(request.expiryMonth());
        if (month == null || month < 1 || month > 12) {
            errors.putIfAbsent(KEY_EXPIRATION_DATE, MSG_EXPIRY_MONTH);
        }
        if (!isValidExpiryYear(request.expiryYear())) {
            errors.putIfAbsent(KEY_EXPIRATION_DATE, MSG_EXPIRY_YEAR);
        }
        if (errors.containsKey(KEY_EXPIRATION_DATE)) {
            return;
        }
        editDate(request.expiryYear(), request.expiryMonth(), request.expiryDay(),
                "Expiry Date", KEY_EXPIRATION_DATE, errors);
    }

    private void editSsn(AccountDto.UpdateRequest request, Map<String, String> errors) {
        if (editNumeric("SSN: First 3 chars", request.ssnPart1(), KEY_SSN, errors)) {
            int firstGroup = Integer.parseInt(request.ssnPart1().trim());
            if (firstGroup == SSN_INVALID_GROUP
                    || (firstGroup >= SSN_RESERVED_RANGE_LOW && firstGroup <= SSN_RESERVED_RANGE_HIGH)) {
                errors.putIfAbsent(KEY_SSN, MSG_SSN_PART1_RANGE);
            }
        }
        editNumeric("SSN 4th & 5th chars", request.ssnPart2(), KEY_SSN, errors);
        editNumeric("SSN Last 4 chars", request.ssnPart3(), KEY_SSN, errors);
    }

    private void editFico(AccountDto.UpdateRequest request, Map<String, String> errors) {
        if (editNumeric("FICO Score", request.ficoScore(), KEY_FICO, errors)) {
            int score = Integer.parseInt(request.ficoScore().trim());
            if (score < FICO_MIN || score > FICO_MAX) {
                errors.putIfAbsent(KEY_FICO, MSG_FICO_RANGE);
            }
        }
    }

    private void editPhone(String label, String area, String prefix, String line, String key,
                           Map<String, String> errors) {
        if (isBlank(area) && isBlank(prefix) && isBlank(line)) {
            return;
        }
        if (editPhoneArea(label, area, key, errors)) {
            if (!validationLookupService.isValidAreaCode(area.trim())) {
                errors.putIfAbsent(key,
                        label + ": Not valid North America general purpose area code");
            }
        }
        editPhonePrefix(label, prefix, key, errors);
        editPhoneLine(label, line, key, errors);
    }

    private boolean editPhoneArea(String label, String area, String key,
                                  Map<String, String> errors) {
        if (isBlank(area)) {
            errors.putIfAbsent(key, label + ": Area code must be supplied.");
            return false;
        }
        String normalized = area.trim();
        if (normalized.length() != 3 || !isAllDigits(normalized)) {
            errors.putIfAbsent(key, label + ": Area code must be A 3 digit number.");
            return false;
        }
        if (isAllZeros(normalized)) {
            errors.putIfAbsent(key, label + ": Area code cannot be zero");
            return false;
        }
        return true;
    }

    private void editPhonePrefix(String label, String prefix, String key,
                                 Map<String, String> errors) {
        if (isBlank(prefix)) {
            errors.putIfAbsent(key, label + ": Prefix code must be supplied.");
            return;
        }
        String normalized = prefix.trim();
        if (normalized.length() != 3 || !isAllDigits(normalized)) {
            errors.putIfAbsent(key, label + ": Prefix code must be A 3 digit number.");
            return;
        }
        if (isAllZeros(normalized)) {
            errors.putIfAbsent(key, label + ": Prefix code cannot be zero");
        }
    }

    private void editPhoneLine(String label, String line, String key,
                               Map<String, String> errors) {
        if (isBlank(line)) {
            errors.putIfAbsent(key, label + ": Line number code must be supplied.");
            return;
        }
        String normalized = line.trim();
        if (normalized.length() != 4 || !isAllDigits(normalized)) {
            errors.putIfAbsent(key, label + ": Line number code must be A 4 digit number.");
            return;
        }
        if (isAllZeros(normalized)) {
            errors.putIfAbsent(key, label + ": Line number code cannot be zero");
        }
    }

    private void editStateZip(AccountDto.UpdateRequest request, Map<String, String> errors) {
        if (errors.containsKey(KEY_STATE) || errors.containsKey(KEY_ZIP)) {
            return;
        }
        if (isBlank(request.state()) || isBlank(request.zipCode())) {
            return;
        }
        if (!validationLookupService.isValidStateZipCombo(request.state().trim(),
                request.zipCode().trim())) {
            errors.putIfAbsent(KEY_ZIP, MSG_STATE_ZIP_INVALID);
        }
    }

    private static String firstMessage(Map<String, String> errors) {
        return errors.values().iterator().next();
    }

    private static boolean isNoInput(AccountDto.UpdateRequest request) {
        return isBlank(request.accountStatus())
                && isBlank(request.openYear()) && isBlank(request.openMonth())
                && isBlank(request.openDay())
                && isBlank(request.expiryYear()) && isBlank(request.expiryMonth())
                && isBlank(request.expiryDay())
                && isBlank(request.reissueYear()) && isBlank(request.reissueMonth())
                && isBlank(request.reissueDay())
                && isBlank(request.dobYear()) && isBlank(request.dobMonth())
                && isBlank(request.dobDay())
                && request.creditLimit() == null && request.cashCreditLimit() == null
                && request.currentBalance() == null && request.currentCycleCredit() == null
                && request.currentCycleDebit() == null
                && isBlank(request.accountGroupId())
                && isBlank(request.ssnPart1()) && isBlank(request.ssnPart2())
                && isBlank(request.ssnPart3())
                && isBlank(request.ficoScore())
                && isBlank(request.firstName()) && isBlank(request.middleName())
                && isBlank(request.lastName())
                && isBlank(request.addressLine1()) && isBlank(request.state())
                && isBlank(request.addressLine2()) && isBlank(request.zipCode())
                && isBlank(request.city()) && isBlank(request.country())
                && isBlank(request.phone1Area()) && isBlank(request.phone1Prefix())
                && isBlank(request.phone1Line())
                && isBlank(request.phone2Area()) && isBlank(request.phone2Prefix())
                && isBlank(request.phone2Line())
                && isBlank(request.governmentId()) && isBlank(request.eftAccountId())
                && isBlank(request.primaryCardHolder());
    }

    /**
     * Copies the validated account fields onto the loaded {@link Account},
     * reassembling the segmented open / expiry / reissue dates into the
     * {@code YYYY-MM-DD} form held by the entity and scaling every monetary
     * amount to two fraction digits.
     *
     * @param account the loaded account record
     * @param request the validated update payload
     * @return {@code true} if at least one account field changed value
     */
    private boolean applyAccountChanges(Account account, AccountDto.UpdateRequest request) {
        boolean changed = false;

        String status = blankToNull(request.accountStatus());
        if (stringDiffers(account.getActiveStatus(), status)) {
            account.setActiveStatus(status);
            changed = true;
        }
        BigDecimal creditLimit = scale2(request.creditLimit());
        if (moneyDiffers(account.getCreditLimit(), creditLimit)) {
            account.setCreditLimit(creditLimit);
            changed = true;
        }
        BigDecimal cashCreditLimit = scale2(request.cashCreditLimit());
        if (moneyDiffers(account.getCashCreditLimit(), cashCreditLimit)) {
            account.setCashCreditLimit(cashCreditLimit);
            changed = true;
        }
        BigDecimal currentBalance = scale2(request.currentBalance());
        if (moneyDiffers(account.getCurrBal(), currentBalance)) {
            account.setCurrBal(currentBalance);
            changed = true;
        }
        BigDecimal cycleCredit = scale2(request.currentCycleCredit());
        if (moneyDiffers(account.getCurrCycCredit(), cycleCredit)) {
            account.setCurrCycCredit(cycleCredit);
            changed = true;
        }
        BigDecimal cycleDebit = scale2(request.currentCycleDebit());
        if (moneyDiffers(account.getCurrCycDebit(), cycleDebit)) {
            account.setCurrCycDebit(cycleDebit);
            changed = true;
        }
        String openDate = assembleDate(request.openYear(), request.openMonth(), request.openDay());
        if (stringDiffers(account.getOpenDate(), openDate)) {
            account.setOpenDate(openDate);
            changed = true;
        }
        String expiryDate = assembleDate(request.expiryYear(), request.expiryMonth(),
                request.expiryDay());
        if (stringDiffers(account.getExpirationDate(), expiryDate)) {
            account.setExpirationDate(expiryDate);
            changed = true;
        }
        String reissueDate = assembleDate(request.reissueYear(), request.reissueMonth(),
                request.reissueDay());
        if (stringDiffers(account.getReissueDate(), reissueDate)) {
            account.setReissueDate(reissueDate);
            changed = true;
        }
        String groupId = blankToNull(request.accountGroupId());
        if (stringDiffers(account.getGroupId(), groupId)) {
            account.setGroupId(groupId);
            changed = true;
        }

        return changed;
    }

    /**
     * Copies the validated customer fields onto the loaded {@link Customer},
     * reassembling the segmented SSN, telephone numbers and birth date.
     *
     * @param customer the loaded customer record
     * @param request  the validated update payload
     * @return {@code true} if at least one customer field changed value
     */
    private boolean applyCustomerChanges(Customer customer, AccountDto.UpdateRequest request) {
        boolean changed = false;

        String firstName = blankToNull(request.firstName());
        if (stringDiffers(customer.getFirstName(), firstName)) {
            customer.setFirstName(firstName);
            changed = true;
        }
        String middleName = blankToNull(request.middleName());
        if (stringDiffers(customer.getMiddleName(), middleName)) {
            customer.setMiddleName(middleName);
            changed = true;
        }
        String lastName = blankToNull(request.lastName());
        if (stringDiffers(customer.getLastName(), lastName)) {
            customer.setLastName(lastName);
            changed = true;
        }
        String addressLine1 = blankToNull(request.addressLine1());
        if (stringDiffers(customer.getAddrLine1(), addressLine1)) {
            customer.setAddrLine1(addressLine1);
            changed = true;
        }
        String addressLine2 = blankToNull(request.addressLine2());
        if (stringDiffers(customer.getAddrLine2(), addressLine2)) {
            customer.setAddrLine2(addressLine2);
            changed = true;
        }
        String city = blankToNull(request.city());
        if (stringDiffers(customer.getAddrLine3(), city)) {
            customer.setAddrLine3(city);
            changed = true;
        }
        String state = blankToNull(request.state());
        if (stringDiffers(customer.getAddrStateCd(), state)) {
            customer.setAddrStateCd(state);
            changed = true;
        }
        String country = blankToNull(request.country());
        if (stringDiffers(customer.getAddrCountryCd(), country)) {
            customer.setAddrCountryCd(country);
            changed = true;
        }
        String zip = blankToNull(request.zipCode());
        if (stringDiffers(customer.getAddrZip(), zip)) {
            customer.setAddrZip(zip);
            changed = true;
        }
        String phone1 = assemblePhone(request.phone1Area(), request.phone1Prefix(),
                request.phone1Line());
        if (stringDiffers(customer.getPhoneNum1(), phone1)) {
            customer.setPhoneNum1(phone1);
            changed = true;
        }
        String phone2 = assemblePhone(request.phone2Area(), request.phone2Prefix(),
                request.phone2Line());
        if (stringDiffers(customer.getPhoneNum2(), phone2)) {
            customer.setPhoneNum2(phone2);
            changed = true;
        }
        String ssn = assembleSsn(request.ssnPart1(), request.ssnPart2(), request.ssnPart3());
        if (stringDiffers(customer.getSsn(), ssn)) {
            customer.setSsn(ssn);
            changed = true;
        }
        String governmentId = blankToNull(request.governmentId());
        if (stringDiffers(customer.getGovtIssuedId(), governmentId)) {
            customer.setGovtIssuedId(governmentId);
            changed = true;
        }
        String dob = assembleDate(request.dobYear(), request.dobMonth(), request.dobDay());
        if (stringDiffers(customer.getDob(), dob)) {
            customer.setDob(dob);
            changed = true;
        }
        String eftAccountId = blankToNull(request.eftAccountId());
        if (stringDiffers(customer.getEftAccountId(), eftAccountId)) {
            customer.setEftAccountId(eftAccountId);
            changed = true;
        }
        String primaryCardHolder = blankToNull(request.primaryCardHolder());
        if (stringDiffers(customer.getPriCardHolderInd(), primaryCardHolder)) {
            customer.setPriCardHolderInd(primaryCardHolder);
            changed = true;
        }
        Integer fico = parseIntegerOrNull(request.ficoScore());
        if (!Objects.equals(customer.getFicoCreditScore(), fico)) {
            customer.setFicoCreditScore(fico);
            changed = true;
        }

        return changed;
    }

    /**
     * Persists both records inside the active transaction, reproducing the CICS
     * {@code SYNCPOINT}: if either save fails, the surrounding
     * {@code @Transactional(rollbackFor = Exception.class)} boundary rolls back
     * the entire unit of work. A version conflict on either record &mdash; the
     * modern equivalent of the legacy {@code 9700-CHECK-CHANGE-IN-REC} guard
     * &mdash; is translated to {@link ConcurrentUpdateException}.
     *
     * @param account  the modified account record
     * @param customer the modified customer record
     */
    private void persist(Account account, Customer customer) {
        try {
            accountRepository.save(account);
            customerRepository.save(customer);
        } catch (OptimisticLockingFailureException | OptimisticLockException ex) {
            throw new ConcurrentUpdateException(ex);
        }
    }

    /**
     * Assembles the refreshed account view from the saved records, mirroring the
     * field projection used by the account-view path.
     *
     * @param account  the saved account record
     * @param customer the saved customer record
     * @return the populated view response
     */
    private AccountDto.ViewResponse toViewResponse(Account account, Customer customer) {
        return new AccountDto.ViewResponse(
                toStringId(account.getAcctId()),
                account.getActiveStatus(),
                account.getOpenDate(),
                scale2(account.getCreditLimit()),
                account.getExpirationDate(),
                scale2(account.getCashCreditLimit()),
                account.getReissueDate(),
                scale2(account.getCurrBal()),
                scale2(account.getCurrCycCredit()),
                account.getGroupId(),
                scale2(account.getCurrCycDebit()),
                toStringId(customer.getCustId()),
                customer.getSsn(),
                customer.getDob(),
                toFicoString(customer.getFicoCreditScore()),
                customer.getFirstName(),
                customer.getMiddleName(),
                customer.getLastName(),
                customer.getAddrLine1(),
                customer.getAddrStateCd(),
                customer.getAddrLine2(),
                truncateZipForScreen(customer.getAddrZip()),
                customer.getAddrLine3(),
                customer.getAddrCountryCd(),
                customer.getPhoneNum1(),
                customer.getGovtIssuedId(),
                customer.getPhoneNum2(),
                customer.getEftAccountId(),
                customer.getPriCardHolderInd(),
                account.getVersion());
    }

    private static String assembleDate(String year, String month, String day) {
        if (isBlank(year) && isBlank(month) && isBlank(day)) {
            return null;
        }
        return trimToEmpty(year) + "-" + trimToEmpty(month) + "-" + trimToEmpty(day);
    }

    private static String assembleSsn(String part1, String part2, String part3) {
        if (isBlank(part1) && isBlank(part2) && isBlank(part3)) {
            return null;
        }
        return trimToEmpty(part1) + trimToEmpty(part2) + trimToEmpty(part3);
    }

    private static String assemblePhone(String area, String prefix, String line) {
        if (isBlank(area) && isBlank(prefix) && isBlank(line)) {
            return null;
        }
        return "(" + trimToEmpty(area) + ")" + trimToEmpty(prefix) + "-" + trimToEmpty(line);
    }

    private static String toStringId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static String toFicoString(Integer value) {
        return value == null ? null : Integer.toString(value);
    }

    /**
     * Truncates a stored ZIP/postal code to the five-character account-screen
     * width when re-displaying the saved record, mirroring the account-view
     * projection ({@code AccountViewService#truncateZipForScreen}) so the
     * post-update view agrees with the read endpoint. The legacy update map
     * {@code COACTUP} likewise re-displays the ZIP through an
     * {@code ACSZIPCO PIC X(5)} output field, so the persistent
     * {@code CUST-ADDR-ZIP PIC X(10)} value is shown as its first five
     * characters (for example {@code "19852-6716"} as {@code "19852"}). This
     * keeps the refreshed {@link AccountDto.ViewResponse#zipCode()} within its
     * {@code @Size(max = 5)} contract and byte-exact with the legacy screen; the
     * five characters are returned verbatim (no trimming), and a {@code null} or
     * already five-or-fewer-character value is returned unchanged. The full
     * {@code X(10)} value persisted on the customer record is unaffected.
     *
     * @param zip the stored ZIP code (up to ten characters), which may be
     *            {@code null}
     * @return the first five characters of {@code zip}, or {@code zip} unchanged
     *         when it is {@code null} or already five characters or fewer
     */
    private static String truncateZipForScreen(String zip) {
        if (zip == null || zip.length() <= 5) {
            return zip;
        }
        return zip.substring(0, 5);
    }

    private static Integer parseIntegerOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || !isAllDigits(normalized)) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isValidExpiryYear(String year) {
        if (year == null) {
            return false;
        }
        String normalized = year.trim();
        if (normalized.length() != 4 || !isAllDigits(normalized)) {
            return false;
        }
        String century = normalized.substring(0, 2);
        return century.equals("19") || century.equals("20");
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static boolean moneyDiffers(BigDecimal current, BigDecimal incoming) {
        if (current == null || incoming == null) {
            return current != incoming;
        }
        return current.compareTo(incoming) != 0;
    }

    private static boolean stringDiffers(String current, String incoming) {
        return !Objects.equals(blankToNull(current), blankToNull(incoming));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeros(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static boolean isAlphaWithSpaces(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            boolean alphaOrSpace = ch == ' '
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z');
            if (!alphaOrSpace) {
                return false;
            }
        }
        return true;
    }
}
