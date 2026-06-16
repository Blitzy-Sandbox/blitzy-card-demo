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
 * Account-maintenance service &mdash; the Java translation of the COBOL online program
 * {@code COACTUPC} (account update; at 4,236 lines the largest CardDemo program).
 * Source commit {@code 27d6c6f}; the COBOL is referenced for behavioral parity and is
 * never copied into this project.
 *
 * <p>The service validates submitted account and customer field changes, performs an
 * optimistic-concurrency check, and atomically updates BOTH the account master
 * (the VSAM {@code ACCTDAT} re-platformed to the {@code account} table) and the
 * customer master (the VSAM {@code CUSTDAT} re-platformed to the {@code customer}
 * table). It houses the system's sole {@code EXEC CICS SYNCPOINT ROLLBACK}
 * (COACTUPC L4100): {@link #updateAccount(AccountUpdateRequest)} is annotated
 * {@link Transactional}, so any propagated exception rolls back the dual update.</p>
 *
 * <p>Traceability (AAP &sect;0.7.3):</p>
 * <ul>
 *   <li>{@code COACTUPC.PROCESS-UPDATE-ACCT} &rarr;
 *       {@link #updateAccount(AccountUpdateRequest)}.</li>
 *   <li>{@code 9000-READ-ACCT} (and {@code 9200}/{@code 9300}/{@code 9400}) &rarr; the
 *       cross-reference, account, and customer reads that produce managed entities.</li>
 *   <li>{@code 1200-EDIT-MAP-INPUTS} field edits &rarr; the private
 *       {@code require*}/{@code validate*} helpers, invoked in the same order with
 *       first-failure-wins semantics.</li>
 *   <li>{@code 9600-WRITE-PROCESSING} build/rewrite block &rarr; the apply and persist
 *       steps.</li>
 *   <li>{@code 9700-CHECK-CHANGE-IN-REC} before/after image compare &rarr; JPA
 *       {@code @Version} optimistic locking on {@link Account}.</li>
 * </ul>
 *
 * <p>The pseudo-conversational confirm/commit turn-taking of the BMS screen collapses
 * into this single stateless call. Collaborators are constructor-injected.</p>
 */
@Service
public class AccountUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    /** Online date picture string consumed by {@link DateValidationService}. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /**
     * Exclusive upper bound on the magnitude of a signed {@code 9(10)V99} money field
     * (10 integer digits): a valid value satisfies {@code abs(value) < 10^10}.
     */
    private static final BigDecimal MONEY_LIMIT = new BigDecimal("10000000000");

    /** Maximum fractional scale of a signed {@code 9(10)V99} money field. */
    private static final int MONEY_SCALE = 2;

    /** Confirmation message returned on a committed update (COACTUPC L475). */
    private static final String INFO_SUCCESS = "Changes committed to database";

    /** Optimistic-lock conflict message (COACTUPC L522 / COCOM01Y). */
    private static final String MSG_CONCURRENCY = "Record changed by some one else. Please review";

    /** Account-filter edit message (COACTUPC {@code 1210-EDIT-ACCOUNT}). */
    private static final String MSG_ACCT_INVALID = "Account Number if supplied must be a 11 digit Non-Zero Number";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;

    /**
     * Creates the service with its collaborating repositories and shared edit services.
     *
     * @param accountRepository            account master repository (ACCTDAT)
     * @param customerRepository           customer master repository (CUSTDAT)
     * @param cardCrossReferenceRepository card cross-reference repository (CXACAIX)
     * @param dateValidationService        shared date editor ({@code CSUTLDTC})
     * @param validationLookupService      shared NANPA/state/ZIP lookups ({@code CSLKPCDY})
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

    /**
     * Validates and atomically applies an account-and-customer update, mirroring
     * {@code COACTUPC.PROCESS-UPDATE-ACCT} / {@code 9600-WRITE-PROCESSING}.
     *
     * <p>Processing mirrors the COBOL program: (1) read the cross-reference, account,
     * and customer records into managed entities; (2) edit every input field in
     * {@code 1200-EDIT-MAP-INPUTS} order, stopping at the first failure; (3) apply the
     * accepted values to the managed entities; (4) rewrite both records with an
     * optimistic-lock check; (5) return the redisplay payload.</p>
     *
     * <p>The dual update is atomic: because this method is {@link Transactional}, a
     * failure of the customer rewrite rolls back the already-flushed account rewrite,
     * reproducing the COBOL {@code SYNCPOINT ROLLBACK}.</p>
     *
     * @param request the submitted account/customer field values (split-part contract)
     * @return the redisplay payload echoing the saved values with a confirmation message
     * @throws ValidationException     when an input edit fails (first failure wins);
     *                                 carries the offending field name
     * @throws RecordNotFoundException when the cross-reference, account, or customer
     *                                 record does not exist
     * @throws ConcurrencyException    when the optimistic-lock check detects a competing
     *                                 update to the account
     */
    @Transactional
    public AccountUpdateResponse updateAccount(AccountUpdateRequest request) {
        Long acctId = parseAccountId(request.accountId());
        LOG.debug("Processing account update for account {}", acctId);

        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw RecordNotFoundException.forKey("Account cross-reference", acctId);
        }
        Long custId = xrefs.get(0).getXrefCustId();

        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Account", acctId));
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Customer", custId));

        applyValidatedChanges(request, account, customer);

        persist(account, customer);

        LOG.debug("Account update committed for account {}", acctId);
        return buildResponse(request);
    }

    /**
     * Parses and edits the account-filter key (COACTUPC {@code 1210-EDIT-ACCOUNT}). A
     * blank, non-numeric, or zero value is rejected with the program's account-filter
     * message.
     *
     * @param accountId the submitted account identifier
     * @return the parsed, non-zero account id
     * @throws ValidationException when the value is blank, non-numeric, or zero
     */
    private Long parseAccountId(String accountId) {
        String value = accountId == null ? "" : accountId.trim();
        if (value.isEmpty()) {
            throw new ValidationException(MSG_ACCT_INVALID, "accountId");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException(MSG_ACCT_INVALID, "accountId");
        }
        if (parsed == 0L) {
            throw new ValidationException(MSG_ACCT_INVALID, "accountId");
        }
        return parsed;
    }

    /**
     * Edits every input field in {@code 1200-EDIT-MAP-INPUTS} order and, once all edits
     * pass, applies the accepted values to the managed {@code account} and
     * {@code customer} entities ({@code 9600-WRITE-PROCESSING} build block). Edits stop
     * at the first failure, so a {@link ValidationException} short-circuits before any
     * mutation occurs.
     *
     * @param request  the submitted field values
     * @param account  the managed account entity to mutate
     * @param customer the managed customer entity to mutate
     */
    private void applyValidatedChanges(AccountUpdateRequest request, Account account, Customer customer) {
        requireYesNo(request.accountStatus(), "Account Status", "accountStatus");
        String openDate = assembleAndValidateDate(
                request.openYear(), request.openMonth(), request.openDay(), "Open Date", "openDate");
        requireMoney(request.creditLimit(), "Credit Limit", "creditLimit");
        String expiryDate = assembleAndValidateDate(
                request.expirationYear(), request.expirationMonth(), request.expirationDay(),
                "Expiry Date", "expiryDate");
        requireMoney(request.cashCreditLimit(), "Cash Credit Limit", "cashCreditLimit");
        String reissueDate = assembleAndValidateDate(
                request.reissueYear(), request.reissueMonth(), request.reissueDay(),
                "Reissue Date", "reissueDate");
        requireMoney(request.currentBalance(), "Current Balance", "currentBalance");
        requireMoney(request.currentCycleCredit(), "Current Cycle Credit Limit", "currentCycleCredit");
        requireMoney(request.currentCycleDebit(), "Current Cycle Debit Limit", "currentCycleDebit");
        validateSsn(request);
        String dob = validateDateOfBirth(request);
        validateFicoScore(request.ficoScore());
        requireAlpha(request.firstName(), "First Name", "firstName", false);
        requireAlpha(request.middleName(), "Middle Name", "middleName", true);
        requireAlpha(request.lastName(), "Last Name", "lastName", false);
        requireMandatory(request.addressLine1(), "Address Line 1", "addressLine1");
        validateState(request.stateCode());
        requireNumeric(request.zipCode(), "Zip", "zipCode", 5);
        requireAlpha(request.city(), "City", "city", false);
        requireAlpha(request.countryCode(), "Country", "countryCode", false);
        validatePhone(request.phone1Area(), request.phone1Prefix(), request.phone1Line(),
                "Phone Number 1", "phone1");
        validatePhone(request.phone2Area(), request.phone2Prefix(), request.phone2Line(),
                "Phone Number 2", "phone2");
        requireNumeric(request.eftAccountId(), "EFT Account Id", "eftAccountId", 10);
        requireYesNo(request.primaryCardHolderIndicator(), "Primary Card Holder", "primaryCardHolderIndicator");
        validateStateZip(request.stateCode(), request.zipCode());

        applyAccount(request, account, openDate, expiryDate, reissueDate);
        applyCustomer(request, customer, dob);
    }

    /**
     * Applies the accepted account-field values to the managed {@link Account} entity
     * ({@code 9600-WRITE-PROCESSING}, {@code ACCT-UPDATE-RECORD} build). The five money
     * fields are normalized to scale {@value #MONEY_SCALE}; the account ZIP
     * ({@code acctAddrZip}) is intentionally left untouched, matching the COBOL
     * {@code ACCT-UPDATE-RECORD}.
     *
     * @param request     the submitted field values
     * @param account     the managed account entity
     * @param openDate    the assembled {@code YYYY-MM-DD} open date
     * @param expiryDate  the assembled {@code YYYY-MM-DD} expiration date
     * @param reissueDate the assembled {@code YYYY-MM-DD} reissue date
     */
    private void applyAccount(AccountUpdateRequest request, Account account,
                              String openDate, String expiryDate, String reissueDate) {
        account.setAcctActiveStatus(nz(request.accountStatus()));
        account.setAcctCurrBal(scaleMoney(request.currentBalance()));
        account.setAcctCreditLimit(scaleMoney(request.creditLimit()));
        account.setAcctCashCreditLimit(scaleMoney(request.cashCreditLimit()));
        account.setAcctCurrCycCredit(scaleMoney(request.currentCycleCredit()));
        account.setAcctCurrCycDebit(scaleMoney(request.currentCycleDebit()));
        account.setAcctOpenDate(openDate);
        account.setAcctExpiraionDate(expiryDate);
        account.setAcctReissueDate(reissueDate);
        account.setAcctGroupId(nz(request.accountGroupId()));
    }

    /**
     * Applies the accepted customer-field values to the managed {@link Customer} entity
     * ({@code 9600-WRITE-PROCESSING}, {@code CUST-UPDATE-RECORD} build). The COBOL
     * mapping is preserved: the screen "City" is stored to address line 3; the SSN parts
     * are concatenated into the numeric SSN; phone numbers are formatted
     * {@code (AAA)PPP-LLLL}; the date of birth is stored as {@code YYYY-MM-DD}.
     *
     * @param request  the submitted field values
     * @param customer the managed customer entity
     * @param dob      the assembled {@code YYYY-MM-DD} date of birth
     */
    private void applyCustomer(AccountUpdateRequest request, Customer customer, String dob) {
        customer.setCustFirstName(nz(request.firstName()));
        customer.setCustMiddleName(nz(request.middleName()));
        customer.setCustLastName(nz(request.lastName()));
        customer.setCustAddrLine1(nz(request.addressLine1()));
        customer.setCustAddrLine2(nz(request.addressLine2()));
        customer.setCustAddrLine3(nz(request.city()));
        customer.setCustAddrStateCd(nz(request.stateCode()));
        customer.setCustAddrCountryCd(nz(request.countryCode()));
        customer.setCustAddrZip(nz(request.zipCode()));
        customer.setCustPhoneNum1(formatPhone(request.phone1Area(), request.phone1Prefix(), request.phone1Line()));
        customer.setCustPhoneNum2(formatPhone(request.phone2Area(), request.phone2Prefix(), request.phone2Line()));
        customer.setCustSsn(Long.parseLong(safe(request.ssnPart1()) + safe(request.ssnPart2()) + safe(request.ssnPart3())));
        customer.setCustGovtIssuedId(nz(request.governmentIssuedId()));
        customer.setCustDobYyyyMmDd(dob);
        customer.setCustEftAccountId(nz(request.eftAccountId()));
        customer.setCustPriCardHolderInd(nz(request.primaryCardHolderIndicator()));
        customer.setCustFicoCreditScore(Integer.valueOf(safe(request.ficoScore())));
    }

    /**
     * Rewrites both master records with a forced flush so the {@link Account}
     * {@code @Version} check fires deterministically ({@code REWRITE ACCTDAT} L4066 then
     * {@code REWRITE CUSTDAT} L4086). An optimistic-lock failure is translated to a
     * {@link ConcurrencyException}; any other exception propagates so the
     * {@link Transactional} boundary rolls back the already-flushed account rewrite,
     * reproducing the sole {@code SYNCPOINT ROLLBACK} (L4100).
     *
     * @param account  the managed account entity to rewrite
     * @param customer the managed customer entity to rewrite
     * @throws ConcurrencyException on an optimistic-lock conflict
     */
    private void persist(Account account, Customer customer) {
        try {
            accountRepository.saveAndFlush(account);
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            throw new ConcurrencyException(MSG_CONCURRENCY, "Account", ex);
        }
    }

    /**
     * Builds the redisplay payload, echoing the saved split-field values (which equal
     * what was persisted) and appending the confirmation message. The five money fields
     * are echoed at scale {@value #MONEY_SCALE}.
     *
     * @param request the submitted field values
     * @return the populated {@link AccountUpdateResponse}
     */
    private AccountUpdateResponse buildResponse(AccountUpdateRequest request) {
        return new AccountUpdateResponse(
                request.accountId(),
                request.accountStatus(),
                request.openYear(), request.openMonth(), request.openDay(),
                scaleMoney(request.creditLimit()),
                request.expirationYear(), request.expirationMonth(), request.expirationDay(),
                scaleMoney(request.cashCreditLimit()),
                request.reissueYear(), request.reissueMonth(), request.reissueDay(),
                scaleMoney(request.currentBalance()),
                scaleMoney(request.currentCycleCredit()),
                request.accountGroupId(),
                scaleMoney(request.currentCycleDebit()),
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
                INFO_SUCCESS,
                null);
    }

    /**
     * Edits a required Y/N flag (COACTUPC {@code 1220-EDIT-YESNO}). A blank or zero
     * value yields the "must be supplied" message; any value other than {@code "Y"} or
     * {@code "N"} yields the "must be Y or N" message.
     *
     * @param value the submitted flag
     * @param label the COBOL edit variable name used in the message
     * @param field the offending field name carried on the exception
     * @throws ValidationException when the value is blank or not {@code Y}/{@code N}
     */
    private void requireYesNo(String value, String label, String field) {
        String v = safe(value);
        if (v.isEmpty() || "0".equals(v)) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        if (!"Y".equals(v) && !"N".equals(v)) {
            throw new ValidationException(label + " must be Y or N.", field);
        }
    }

    /**
     * Edits a mandatory free-text field (COACTUPC {@code 1215-EDIT-MANDATORY}); only a
     * non-blank value is required (no character-class restriction).
     *
     * @param value the submitted value
     * @param label the COBOL edit variable name used in the message
     * @param field the offending field name carried on the exception
     * @throws ValidationException when the value is blank
     */
    private void requireMandatory(String value, String label, String field) {
        if (safe(value).isEmpty()) {
            throw new ValidationException(label + " must be supplied.", field);
        }
    }

    /**
     * Edits an alphabetic field (COACTUPC {@code 1225-EDIT-ALPHA-REQD} when
     * {@code optional} is {@code false}, {@code 1235-EDIT-ALPHA-OPT} when {@code true}).
     * Only ASCII letters and spaces are accepted; a required blank yields "must be
     * supplied" while an optional blank passes.
     *
     * @param value    the submitted value
     * @param label    the COBOL edit variable name used in the message
     * @param field    the offending field name carried on the exception
     * @param optional {@code true} to permit a blank value
     * @throws ValidationException when a required value is blank or any value contains a
     *                             non-alphabetic, non-space character
     */
    private void requireAlpha(String value, String label, String field, boolean optional) {
        String v = safe(value);
        if (v.isEmpty()) {
            if (optional) {
                return;
            }
            throw new ValidationException(label + " must be supplied.", field);
        }
        if (!isAlphaSpaces(v)) {
            throw new ValidationException(label + " can have alphabets only.", field);
        }
    }

    /**
     * Edits a required numeric field of an exact digit length (COACTUPC
     * {@code 1245-EDIT-NUM-REQD}). A blank value yields "must be supplied"; a value that
     * is not exactly {@code exactLen} digits yields "must be all numeric"; an all-zero
     * value yields "must not be zero".
     *
     * @param value    the submitted value
     * @param label    the COBOL edit variable name used in the message
     * @param field    the offending field name carried on the exception
     * @param exactLen the required digit length
     * @throws ValidationException when the value is blank, not all numeric, or zero
     */
    private void requireNumeric(String value, String label, String field, int exactLen) {
        String v = safe(value);
        if (v.isEmpty()) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        if (!v.matches("\\d{" + exactLen + "}")) {
            throw new ValidationException(label + " must be all numeric.", field);
        }
        if (isAllZeros(v)) {
            throw new ValidationException(label + " must not be zero.", field);
        }
    }

    /**
     * Edits a signed {@code 9(10)V99} money field (COACTUPC {@code 1250-EDIT-SIGNED-9V2}).
     * A {@code null} value yields "must be supplied"; a value with more than
     * {@value #MONEY_SCALE} fractional digits or a magnitude of {@value #MONEY_LIMIT} or
     * greater yields "is not valid". Comparisons use {@link BigDecimal#compareTo} and
     * {@link BigDecimal#scale}, never {@code equals}.
     *
     * @param value the submitted money value
     * @param label the COBOL edit variable name used in the message
     * @param field the offending field name carried on the exception
     * @throws ValidationException when the value is null or out of the picture range
     */
    private void requireMoney(BigDecimal value, String label, String field) {
        if (value == null) {
            throw new ValidationException(label + " must be supplied.", field);
        }
        if (value.scale() > MONEY_SCALE || value.abs().compareTo(MONEY_LIMIT) >= 0) {
            throw new ValidationException(label + " is not valid", field);
        }
    }

    /**
     * Assembles a {@code YYYY-MM-DD} value from its split parts and edits it through the
     * shared date service (COACTUPC {@code EDIT-DATE-CCYYMMDD} &rarr; {@code CSUTLDTC}).
     *
     * @param year  the year part
     * @param month the month part
     * @param day   the day part
     * @param label the COBOL edit variable name used in the message
     * @param field the offending field name carried on the exception
     * @return the assembled {@code YYYY-MM-DD} value (valid on return)
     * @throws ValidationException when the assembled date is not acceptable
     */
    private String assembleAndValidateDate(String year, String month, String day, String label, String field) {
        String date = safe(year) + "-" + safe(month) + "-" + safe(day);
        if (!dateValidationService.validateDate(date, DATE_FORMAT).isAcceptable()) {
            throw new ValidationException(label + " is not valid", field);
        }
        return date;
    }

    /**
     * Edits the date of birth (COACTUPC {@code EDIT-DATE-CCYYMMDD} then
     * {@code EDIT-DATE-OF-BIRTH}): the assembled date must be a valid calendar date and
     * must lie strictly in the past.
     *
     * @param request the submitted field values
     * @return the assembled {@code YYYY-MM-DD} date of birth (valid on return)
     * @throws ValidationException when the date is invalid or not in the past
     */
    private String validateDateOfBirth(AccountUpdateRequest request) {
        String dob = assembleAndValidateDate(
                request.dobYear(), request.dobMonth(), request.dobDay(), "Date of Birth", "dateOfBirth");
        if (!LocalDate.parse(dob).isBefore(LocalDate.now())) {
            throw new ValidationException("Date of Birth:cannot be in the future ", "dateOfBirth");
        }
        return dob;
    }

    /**
     * Edits the FICO score (COACTUPC {@code 1245-EDIT-NUM-REQD} then
     * {@code 1275-EDIT-FICO-SCORE}): a required 3-digit number that must fall in the
     * inclusive range 300&ndash;850.
     *
     * @param ficoScore the submitted score
     * @throws ValidationException when the value fails the numeric edit or the range
     */
    private void validateFicoScore(String ficoScore) {
        requireNumeric(ficoScore, "FICO Score", "ficoScore", 3);
        int score = Integer.parseInt(safe(ficoScore));
        if (score < 300 || score > 850) {
            throw new ValidationException("FICO Score: should be between 300 and 850", "ficoScore");
        }
    }

    /**
     * Edits the three Social-Security-number parts (COACTUPC {@code 1265-EDIT-US-SSN}):
     * each part is a required numeric of its fixed length, and the first part may not be
     * {@code 000}, {@code 666}, or in the range 900&ndash;999.
     *
     * @param request the submitted field values
     * @throws ValidationException when any part fails its numeric edit or the first-part
     *                             range rule
     */
    private void validateSsn(AccountUpdateRequest request) {
        requireNumeric(request.ssnPart1(), "SSN: First 3 chars", "ssnPart1", 3);
        int part1 = Integer.parseInt(safe(request.ssnPart1()));
        if (part1 == 0 || part1 == 666 || (part1 >= 900 && part1 <= 999)) {
            throw new ValidationException(
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssnPart1");
        }
        requireNumeric(request.ssnPart2(), "SSN 4th & 5th chars", "ssnPart2", 2);
        requireNumeric(request.ssnPart3(), "SSN Last 4 chars", "ssnPart3", 4);
    }

    /**
     * Edits the state code (COACTUPC {@code 1225-EDIT-ALPHA-REQD} then
     * {@code 1270-EDIT-US-STATE-CD}): a required alphabetic value that must be a known US
     * state code.
     *
     * @param stateCode the submitted state code
     * @throws ValidationException when the value is blank, non-alphabetic, or unknown
     */
    private void validateState(String stateCode) {
        requireAlpha(stateCode, "State", "stateCode", false);
        if (!validationLookupService.isValidStateCode(stateCode)) {
            throw new ValidationException("State: is not a valid state code", "stateCode");
        }
    }

    /**
     * Cross-field edit of the state code combined with the first two ZIP digits
     * (COACTUPC {@code 1280-EDIT-US-STATE-ZIP-CD}); invoked only after both the state and
     * ZIP have individually passed their edits.
     *
     * @param stateCode the validated state code
     * @param zipCode   the validated ZIP code
     * @throws ValidationException when the state/ZIP combination is not recognized
     */
    private void validateStateZip(String stateCode, String zipCode) {
        if (!validationLookupService.isValidStateZip(stateCode, zipCode)) {
            throw new ValidationException("Invalid zip code for state", "zipCode");
        }
    }

    /**
     * Edits an optional US phone number expressed as area / prefix / line parts
     * (COACTUPC {@code 1260-EDIT-US-PHONE-NUM}). When all three parts are blank the phone
     * is omitted and the edit passes. Otherwise each part is edited in order &mdash; area
     * (3 digits, non-zero, valid NANPA general-purpose code), prefix (3 digits,
     * non-zero), line (4 digits, non-zero) &mdash; stopping at the first failure.
     *
     * @param area   the area-code part
     * @param prefix the prefix part
     * @param line   the line-number part
     * @param label  the COBOL edit variable name used in the message
     * @param field  the offending field name carried on the exception
     * @throws ValidationException when a supplied phone part fails its edit
     */
    private void validatePhone(String area, String prefix, String line, String label, String field) {
        String a = safe(area);
        String p = safe(prefix);
        String l = safe(line);
        if (a.isEmpty() && p.isEmpty() && l.isEmpty()) {
            return;
        }
        if (a.isEmpty()) {
            throw new ValidationException(label + ": Area code must be supplied.", field);
        }
        if (!a.matches("\\d{3}")) {
            throw new ValidationException(label + ": Area code must be A 3 digit number.", field);
        }
        if (isAllZeros(a)) {
            throw new ValidationException(label + ": Area code cannot be zero", field);
        }
        if (!validationLookupService.isValidAreaCode(a)) {
            throw new ValidationException(label + ": Not valid North America general purpose area code", field);
        }
        if (p.isEmpty()) {
            throw new ValidationException(label + ": Prefix code must be supplied.", field);
        }
        if (!p.matches("\\d{3}")) {
            throw new ValidationException(label + ": Prefix code must be A 3 digit number.", field);
        }
        if (isAllZeros(p)) {
            throw new ValidationException(label + ": Prefix code cannot be zero", field);
        }
        if (l.isEmpty()) {
            throw new ValidationException(label + ": Line number code must be supplied.", field);
        }
        if (!l.matches("\\d{4}")) {
            throw new ValidationException(label + ": Line number code must be A 4 digit number.", field);
        }
        if (isAllZeros(l)) {
            throw new ValidationException(label + ": Line number code cannot be zero", field);
        }
    }

    /**
     * Formats a US phone number as {@code (AAA)PPP-LLLL} (COACTUPC
     * {@code 9600-WRITE-PROCESSING} L4027-4033). When all three parts are blank the empty
     * string is returned, matching the omitted-phone case.
     *
     * @param area   the area-code part
     * @param prefix the prefix part
     * @param line   the line-number part
     * @return the formatted phone number, or the empty string when no parts are supplied
     */
    private static String formatPhone(String area, String prefix, String line) {
        String a = safe(area);
        String p = safe(prefix);
        String l = safe(line);
        if (a.isEmpty() && p.isEmpty() && l.isEmpty()) {
            return "";
        }
        return "(" + a + ")" + p + "-" + l;
    }

    /**
     * Normalizes a money value to scale {@value #MONEY_SCALE} using banker's rounding
     * ({@link RoundingMode#HALF_EVEN}), preserving the fixed-point precision of the
     * originating COBOL {@code PIC} clause.
     *
     * @param value the validated, non-null money value
     * @return the value at scale {@value #MONEY_SCALE}
     */
    private static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Trims a value, mapping {@code null} to the empty string.
     *
     * @param value the value to normalize
     * @return the trimmed value, or the empty string when {@code null}
     */
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Maps {@code null} to the empty string, otherwise returns the value unchanged
     * (used when storing to non-nullable columns, mirroring COBOL space-filled fields).
     *
     * @param value the value to normalize
     * @return the value, or the empty string when {@code null}
     */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * Reports whether every character of a non-empty value is {@code '0'}.
     *
     * @param value the non-empty value to test
     * @return {@code true} when the value is all zeros
     */
    private static boolean isAllZeros(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character of the value is an ASCII letter ({@code A-Z} or
     * {@code a-z}) or a space, mirroring the COBOL alphabetic edit (which converts the
     * 52 ASCII letters to spaces and then checks for a fully blank residue).
     *
     * @param value the value to test
     * @return {@code true} when the value contains only ASCII letters and spaces
     */
    private static boolean isAlphaSpaces(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean asciiLetter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!asciiLetter && c != ' ') {
                return false;
            }
        }
        return true;
    }
}
