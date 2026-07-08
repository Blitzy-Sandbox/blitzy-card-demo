package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.dto.AccountUpdateResponse;
import com.carddemo.dto.AccountViewResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Application service implementing the Account Update transaction (online
 * transaction {@code CAUP}), the Java&nbsp;25 / Spring&nbsp;Boot translation of
 * the legacy CICS program {@code COACTUPC} ({@code app/cbl/COACTUPC.cbl},
 * 4&nbsp;236&nbsp;LOC &mdash; the largest program in the system, frozen source
 * commit SHA {@code 27d6c6f}).
 *
 * <p>{@code COACTUPC} performs a classic mainframe <em>read-then-rewrite</em> of
 * <strong>both</strong> the account and customer master records with explicit
 * optimistic-concurrency detection. On save it re-reads each record under an
 * update lock ({@code 9600-WRITE-PROCESSING}), compares the freshly read fields
 * against the snapshot the user was shown ({@code 9700-CHECK-CHANGE-IN-REC}) and,
 * if they differ, raises {@code DATA-WAS-CHANGED-BEFORE-UPDATE} with the message
 * <em>"Record changed by some one else. Please review"</em>. It then rewrites the
 * account and the customer; if the customer rewrite fails after the account has
 * already been rewritten, {@code EXEC CICS SYNCPOINT ROLLBACK} undoes the account
 * change so the two records never diverge.</p>
 *
 * <h2>Concurrency and transaction parity (AAP &sect;0.8.4)</h2>
 * <p>This service preserves that behaviour with JPA {@code @Version} optimistic
 * locking on {@link Account}, surfaced as an
 * {@link OptimisticLockConflictException} (HTTP&nbsp;409), plus an explicit
 * version precheck against the token the caller last observed. Both persistence
 * writes run inside a single {@link Transactional @Transactional} boundary with
 * {@code rollbackFor = Exception.class}, so a failure on the customer write rolls
 * back the account write &mdash; the exact equivalent of the legacy
 * {@code SYNCPOINT ROLLBACK}.</p>
 *
 * <h2>Control-flow parity (AAP G3)</h2>
 * <p>The single public method {@link #updateAccount(Long, AccountUpdateRequest)}
 * preserves the ordered steps of {@code COACTUPC}:</p>
 * <ol>
 *   <li>account-id search-key edit ({@code 1210-EDIT-ACCOUNT}: a non-zero
 *       eleven-digit number);</li>
 *   <li>resolve the owning customer through the card cross-reference
 *       ({@code 9200-GETCARDXREF-BYACCT});</li>
 *   <li>keyed reads of the account and customer masters
 *       ({@code 9300-GETACCTDATA-BYACCT} / {@code 9400-GETCUSTDATA-BYCUST};
 *       {@code DFHRESP(NOTFND)} &rarr; {@link ResourceNotFoundException});</li>
 *   <li>optimistic-lock precheck against the version the caller last observed
 *       ({@code 9700-CHECK-CHANGE-IN-REC});</li>
 *   <li>lookup-backed field edits in copybook order &mdash; state code
 *       ({@code 1270-EDIT-US-STATE-CD}), phone area codes
 *       ({@code 1260-EDIT-US-PHONE-NUM}) and the cross-field state{@code +}ZIP
 *       combo ({@code 1280-EDIT-US-STATE-ZIP-CD}, evaluated last);</li>
 *   <li>rewrite of both records ({@code 9600-WRITE-PROCESSING}
 *       {@code EXEC CICS REWRITE}), with a persistence-layer optimistic-lock
 *       failure mapped to the same 409 conflict.</li>
 * </ol>
 *
 * <h2>Decimal fidelity (AAP G2 / &sect;0.8.2)</h2>
 * <p>The five monetary fields ({@code ACCT-CURR-BAL},
 * {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 * {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}, each
 * {@code PIC S9(10)V99}) are carried as {@link BigDecimal} and normalised to a
 * fixed scale of two via {@link RoundingMode#HALF_UP}; binary floating-point is
 * never used for money.</p>
 *
 * <h2>Statelessness and security</h2>
 * <p>The before-image is conveyed solely by the request {@code version} token
 * (there is no server-side CICS {@code COMMAREA} session). The account-view
 * projection masks the SSN and carries no credential, and no PII (SSN, names,
 * phone numbers) is ever written to a log line; structured logs are correlated
 * through the MDC {@code correlationId} established by the request filter.</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor
 * injection.</p>
 *
 * @see AccountRepository
 * @see CustomerRepository
 * @see CrossReferenceService
 * @see LookupService
 * @see AccountUpdateRequest
 * @see AccountUpdateResponse
 * @see OptimisticLockConflictException
 */
@Service
public class AccountUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AccountUpdateService.class);

    /**
     * Invalid account-number message.
     *
     * <p>Verbatim transcription of the {@code SEARCHED-ACCT-ZEROES} /
     * {@code SEARCHED-ACCT-NOT-NUMERIC} 88-level literal in {@code COACTUPC}
     * (SHA&nbsp;{@code 27d6c6f}): <em>"Account number must be a non zero 11 digit
     * number"</em>.</p>
     */
    static final String MSG_ACCT_INVALID = "Account number must be a non zero 11 digit number";

    /**
     * Not-found message for an account absent from the account master.
     *
     * <p>Verbatim transcription of the {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}
     * 88-level literal in {@code COACTUPC}: <em>"Did not find this account in
     * account master file"</em>.</p>
     */
    static final String MSG_ACCT_NOT_FOUND = "Did not find this account in account master file";

    /**
     * Not-found message for a customer absent from the customer master.
     *
     * <p>Verbatim transcription of the {@code DID-NOT-FIND-CUST-IN-CUSTDAT}
     * 88-level literal in {@code COACTUPC}: <em>"Did not find associated customer
     * in master file"</em>.</p>
     */
    static final String MSG_CUST_NOT_FOUND = "Did not find associated customer in master file";

    /**
     * Success confirmation returned after a committed rewrite.
     *
     * <p>Verbatim transcription of the {@code CONFIRM-UPDATE-SUCCESS} 88-level
     * literal in {@code COACTUPC}: <em>"Changes committed to database"</em>.
     * Using the exact legacy text preserves the interface contract of the
     * migrated transaction.</p>
     */
    static final String MSG_SUCCESS = "Changes committed to database";

    /**
     * Aggregate summary used as the top-level detail message when one or more
     * field edits fail. Individual field failures are carried in the
     * {@link ValidationException#getFieldErrors() field-error map}; the legacy
     * 3270 screen surfaced a single {@code WS-RETURN-MSG} at a time, whereas the
     * REST boundary reports every failing field together.
     */
    static final String MSG_VALIDATION_SUMMARY = "Account update failed validation.";

    /** Field label for the state-code edit ({@code COACTUPC} {@code WS-EDIT-VARIABLE-NAME} "State"). */
    private static final String FIELD_STATE = "State";

    /** Field label for the primary phone edit ({@code COACTUPC} {@code WS-EDIT-VARIABLE-NAME} "Phone Number 1"). */
    private static final String FIELD_PHONE_1 = "Phone Number 1";

    /** Field label for the secondary phone edit ({@code COACTUPC} {@code WS-EDIT-VARIABLE-NAME} "Phone Number 2"). */
    private static final String FIELD_PHONE_2 = "Phone Number 2";

    /** Field key under which the cross-field state{@code +}ZIP combo failure is reported. */
    private static final String FIELD_ZIP = "Zip";

    /** Message suffix appended to the field label for an invalid state code ({@code 1270-EDIT-US-STATE-CD}). */
    private static final String MSG_STATE_INVALID_SUFFIX = ": is not a valid state code";

    /** Message suffix appended to the field label for an invalid phone area code ({@code 1260-EDIT-US-PHONE-NUM}). */
    private static final String MSG_AREA_CODE_INVALID_SUFFIX =
            ": Not valid North America general purpose area code";

    /** Fixed literal reported for an invalid state{@code +}ZIP pairing ({@code 1280-EDIT-US-STATE-ZIP-CD}). */
    private static final String MSG_ZIP_FOR_STATE_INVALID = "Invalid zip code for state";

    /** Fixed decimal scale for every monetary field, matching COBOL {@code V99}. */
    private static final int MONEY_SCALE = 2;

    /** Number of leading digits that form a North-American phone area code. */
    private static final int AREA_CODE_LENGTH = 3;

    /** Zero-padded rendering of {@code ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID_FORMAT = "%011d";

    /** Zero-padded rendering of a nine-digit key ({@code CUST-ID PIC 9(09)} and {@code CUST-SSN PIC 9(09)}). */
    private static final String NINE_DIGIT_FORMAT = "%09d";

    /** Regular expression matching a single non-digit character (used to isolate area-code digits). */
    private static final String NON_DIGIT_PATTERN = "\\D";

    /**
     * Repository for the {@link Account} aggregate (migrated VSAM {@code ACCTDATA}
     * keyed access). Never {@code null}; supplied by constructor injection.
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for the {@link Customer} aggregate (migrated VSAM {@code CUSTDATA}
     * keyed access). Never {@code null}; supplied by constructor injection.
     */
    private final CustomerRepository customerRepository;

    /**
     * Resolves the owning customer id for an account through the card
     * cross-reference (the migrated account alternate index). Never {@code null}.
     */
    private final CrossReferenceService crossReferenceService;

    /**
     * Reference-data lookups migrated from {@code CSLKPCDY}: valid US state codes,
     * state{@code +}ZIP pairings and phone area codes. Never {@code null}.
     */
    private final LookupService lookupService;

    /**
     * Creates the service with its collaborating repositories and lookup helpers.
     *
     * @param accountRepository     repository for the {@link Account} aggregate; must not be {@code null}
     * @param customerRepository    repository for the {@link Customer} aggregate; must not be {@code null}
     * @param crossReferenceService cross-reference resolver for account&rarr;customer; must not be {@code null}
     * @param lookupService         reference-data validator for state/ZIP/area-code edits; must not be {@code null}
     */
    public AccountUpdateService(final AccountRepository accountRepository,
            final CustomerRepository customerRepository,
            final CrossReferenceService crossReferenceService,
            final LookupService lookupService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.crossReferenceService = crossReferenceService;
        this.lookupService = lookupService;
    }

    /**
     * Updates an account and its owning customer together, preserving the
     * read-then-rewrite optimistic-concurrency semantics of {@code COACTUPC}
     * (transaction {@code CAUP}).
     *
     * <p>The method runs in a single transaction that rolls back on any
     * exception, so a failure while writing the customer undoes the account
     * write, mirroring the legacy {@code EXEC CICS SYNCPOINT ROLLBACK}. Field
     * edits are evaluated in {@code COACTUPC} copybook order and every failing
     * field is reported together through the returned validation error.</p>
     *
     * @param accountId the account identifier to update ({@code ACCT-ID}); must be
     *                  a non-{@code null} positive value
     * @param request   the requested account and customer changes together with
     *                  the optimistic-lock version the caller last observed; must
     *                  not be {@code null}
     * @return an {@link AccountUpdateResponse} carrying the refreshed, SSN-masked
     *         account view, the legacy success confirmation ({@link #MSG_SUCCESS})
     *         and the new optimistic-lock version
     * @throws ValidationException             if the account id is not a positive
     *                                         number, or a field edit fails
     *                                         (HTTP&nbsp;400)
     * @throws ResourceNotFoundException       if the account has no cross-reference
     *                                         row, or the account or customer
     *                                         record cannot be located
     *                                         (HTTP&nbsp;404)
     * @throws OptimisticLockConflictException if the account was changed by another
     *                                         actor between read and write
     *                                         (HTTP&nbsp;409)
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountUpdateResponse updateAccount(final Long accountId, final AccountUpdateRequest request) {
        log.info("Processing account update request (transaction CAUP)");

        // --- 1210-EDIT-ACCOUNT: the account-id search key must be a non-zero number
        // (SEARCHED-ACCT-ZEROES / SEARCHED-ACCT-NOT-NUMERIC -> 400).
        if (accountId == null || accountId <= 0L) {
            throw new ValidationException(MSG_ACCT_INVALID);
        }

        // --- 9200-GETCARDXREF-BYACCT: resolve the owning customer via the card cross-reference
        // (account alternate index). A missing xref row reproduces the COACTUPC
        // DID-NOT-FIND-ACCT-IN-CARDXREF branch (404), raised inside resolveCustomerId.
        final Long customerId = crossReferenceService.resolveCustomerId(accountId);

        // --- 9300-GETACCTDATA-BYACCT / 9400-GETCUSTDATA-BYCUST: keyed reads of the account
        // master then the customer master; DFHRESP(NOTFND) -> 404.
        final Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_ACCT_NOT_FOUND));
        final Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_CUST_NOT_FOUND));

        // --- 9700-CHECK-CHANGE-IN-REC: optimistic-lock precheck. If the caller echoed a version
        // that no longer matches the persisted account, the record was changed after it was read
        // (DATA-WAS-CHANGED-BEFORE-UPDATE -> 409).
        if (request.version() != null && !request.version().equals(account.getVersion())) {
            log.warn("Optimistic-lock precheck failed on account update (expected v{}, current v{})",
                    request.version(), account.getVersion());
            throw new OptimisticLockConflictException();
        }

        // --- 1200-EDIT-MAP-INPUTS: lookup-backed field edits accumulated in COACTUPC source order
        // (state code -> phone area codes -> cross-field state+ZIP combo). Report all failures at once.
        final Map<String, String> fieldErrors = new LinkedHashMap<>();
        editFields(request, fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(MSG_VALIDATION_SUMMARY, fieldErrors);
        }

        // --- 9600-WRITE-PROCESSING: apply the new values to both records.
        applyAccountEdits(account, request);
        applyCustomerEdits(customer, request);

        // --- EXEC CICS REWRITE (account then customer). Both writes run inside the single
        // @Transactional(rollbackFor = Exception.class) boundary, so a failure on the customer
        // rewrite rolls back the account rewrite exactly as the legacy SYNCPOINT ROLLBACK. A
        // persistence-layer optimistic-lock failure (Hibernate StaleObjectState, wrapped by Spring
        // as ObjectOptimisticLockingFailureException, a subtype of OptimisticLockingFailureException)
        // maps to the same typed 409 conflict.
        try {
            accountRepository.saveAndFlush(account);
            customerRepository.saveAndFlush(customer);
        } catch (final OptimisticLockingFailureException ex) {
            log.warn("Optimistic-lock conflict on account/customer rewrite: {}", ex.getClass().getSimpleName());
            throw new OptimisticLockConflictException(ex);
        }

        log.info("Account update committed (transaction CAUP)");

        // --- CONFIRM-UPDATE-SUCCESS: return the refreshed (SSN-masked) view with the legacy
        // success confirmation and the new optimistic-lock version.
        final AccountViewResponse view = toView(account, customer);
        return new AccountUpdateResponse(view, MSG_SUCCESS, account.getVersion());
    }

    /**
     * Runs the lookup-backed field edits in {@code COACTUPC} copybook order and
     * accumulates every failure into {@code fieldErrors}, preserving the legacy
     * branch/evaluation order (AAP&nbsp;G3): state code
     * ({@code 1270-EDIT-US-STATE-CD}), then the two phone area codes
     * ({@code 1260-EDIT-US-PHONE-NUM}), then the cross-field state{@code +}ZIP
     * combo ({@code 1280-EDIT-US-STATE-ZIP-CD}, evaluated last and only when both
     * the state code is valid and a ZIP was supplied, exactly as the COBOL guard
     * {@code IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID}).
     *
     * @param request     the submitted changes; must not be {@code null}
     * @param fieldErrors the accumulator of field&rarr;message failures; must not be {@code null}
     */
    private void editFields(final AccountUpdateRequest request, final Map<String, String> fieldErrors) {
        final String stateCode = trimToNull(request.stateCode());
        final boolean stateValid = editStateCode(stateCode, fieldErrors);

        editPhoneAreaCode(request.phoneNumber1(), FIELD_PHONE_1, fieldErrors);
        editPhoneAreaCode(request.phoneNumber2(), FIELD_PHONE_2, fieldErrors);

        final String zipCode = trimToNull(request.zipCode());
        if (stateValid && zipCode != null && !lookupService.isValidStateZipCombo(stateCode, zipCode)) {
            fieldErrors.put(FIELD_ZIP, MSG_ZIP_FOR_STATE_INVALID);
        }
    }

    /**
     * Validates a state code against the reference table
     * ({@code 1270-EDIT-US-STATE-CD}). The value is edited only when present: an
     * absent code contributes no error here (presence is governed by the request
     * contract) and causes the cross-field ZIP combo to be skipped, matching the
     * COBOL guard. A present-but-unrecognised code records
     * <em>"State: is not a valid state code"</em>.
     *
     * @param stateCode   the trimmed state code, or {@code null} when absent
     * @param fieldErrors the accumulator to which a failure is added; must not be {@code null}
     * @return {@code true} only when a state code was supplied and recognised
     */
    private boolean editStateCode(final String stateCode, final Map<String, String> fieldErrors) {
        if (stateCode == null) {
            return false;
        }
        if (lookupService.isValidStateCode(stateCode)) {
            return true;
        }
        fieldErrors.put(FIELD_STATE, FIELD_STATE + MSG_STATE_INVALID_SUFFIX);
        return false;
    }

    /**
     * Validates the North-American area code of a phone number
     * ({@code 1260-EDIT-US-PHONE-NUM}). Phone entry is optional, so a blank value
     * is skipped; when a value is present the leading three digits are validated
     * against the reference table and a failure records
     * <em>"&lt;field&gt;: Not valid North America general purpose area code"</em>.
     *
     * @param phone       the submitted phone string (legacy format {@code (999)999-9999}); may be {@code null}
     * @param fieldLabel  the {@code WS-EDIT-VARIABLE-NAME} label for the field; must not be {@code null}
     * @param fieldErrors the accumulator to which a failure is added; must not be {@code null}
     */
    private void editPhoneAreaCode(final String phone, final String fieldLabel,
            final Map<String, String> fieldErrors) {
        final String trimmed = trimToNull(phone);
        if (trimmed == null) {
            return;
        }
        final String areaCode = extractAreaCode(trimmed);
        if (!lookupService.isValidPhoneAreaCode(areaCode)) {
            fieldErrors.put(fieldLabel, fieldLabel + MSG_AREA_CODE_INVALID_SUFFIX);
        }
    }

    /**
     * Applies the account change-fields to the persisted {@link Account}
     * ({@code 9600-WRITE-PROCESSING}, account portion). Each monetary value is
     * normalised to scale&nbsp;2 (null-safe) so the {@code PIC S9(10)V99} contract
     * holds regardless of the scale supplied by the caller.
     *
     * @param account the account to mutate; must not be {@code null}
     * @param request the submitted changes; must not be {@code null}
     */
    private void applyAccountEdits(final Account account, final AccountUpdateRequest request) {
        account.setAcctActiveStatus(request.activeStatus());
        account.setAcctCurrBal(scale2(request.currentBalance()));
        account.setAcctCreditLimit(scale2(request.creditLimit()));
        account.setAcctCashCreditLimit(scale2(request.cashCreditLimit()));
        account.setAcctCurrCycCredit(scale2(request.currentCycleCredit()));
        account.setAcctCurrCycDebit(scale2(request.currentCycleDebit()));
        account.setAcctOpenDate(request.openDate());
        account.setAcctExpirationDate(request.expirationDate());
        account.setAcctReissueDate(request.reissueDate());
        account.setAcctGroupId(request.accountGroupId());
    }

    /**
     * Applies the customer change-fields to the persisted {@link Customer}
     * ({@code 9600-WRITE-PROCESSING}, customer portion). The screen "City" maps to
     * {@code CUST-ADDR-LINE-3}, and the nine-digit SSN string is converted to the
     * numeric {@code CUST-SSN} (null/blank-safe).
     *
     * @param customer the customer to mutate; must not be {@code null}
     * @param request  the submitted changes; must not be {@code null}
     */
    private void applyCustomerEdits(final Customer customer, final AccountUpdateRequest request) {
        customer.setCustFirstName(request.firstName());
        customer.setCustMiddleName(request.middleName());
        customer.setCustLastName(request.lastName());
        customer.setCustAddrLine1(request.addressLine1());
        customer.setCustAddrLine2(request.addressLine2());
        customer.setCustAddrLine3(request.city());
        customer.setCustAddrStateCd(request.stateCode());
        customer.setCustAddrCountryCd(request.countryCode());
        customer.setCustAddrZip(request.zipCode());
        customer.setCustPhoneNum1(request.phoneNumber1());
        customer.setCustPhoneNum2(request.phoneNumber2());
        customer.setCustSsn(parseSsn(request.ssn()));
        customer.setCustGovtIssuedId(request.govtIssuedId());
        customer.setCustDobYyyyMmDd(request.dateOfBirth());
        customer.setCustEftAccountId(request.eftAccountId());
        customer.setCustPriCardHolderInd(request.primaryCardHolderIndicator());
        customer.setCustFicoCreditScore(request.ficoScore());
    }

    /**
     * Projects the freshly written account and customer onto the read-only
     * {@link AccountViewResponse} returned to the caller, matching the flattened
     * account-view shape (the screen "City" is {@code CUST-ADDR-LINE-3}).
     *
     * <p>Identifiers are rendered fixed-width and zero-padded; the SSN is rendered
     * as a nine-digit string and then masked to its last four digits by the
     * {@link AccountViewResponse} canonical constructor, so no full SSN can be
     * emitted. The response constructor also re-normalises every monetary value to
     * scale&nbsp;2.</p>
     *
     * @param account  the persisted account (post-rewrite); must not be {@code null}
     * @param customer the persisted customer (post-rewrite); must not be {@code null}
     * @return the SSN-masked, scale-2 account view
     */
    private static AccountViewResponse toView(final Account account, final Customer customer) {
        return new AccountViewResponse(
                formatFixedWidth(account.getAcctId(), ACCOUNT_ID_FORMAT),
                account.getAcctActiveStatus(),
                account.getAcctCurrBal(),
                account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(),
                account.getAcctOpenDate(),
                account.getAcctExpirationDate(),
                account.getAcctReissueDate(),
                account.getAcctGroupId(),
                account.getVersion(),
                formatFixedWidth(customer.getCustId(), NINE_DIGIT_FORMAT),
                customer.getCustFirstName(),
                customer.getCustMiddleName(),
                customer.getCustLastName(),
                customer.getCustAddrLine1(),
                customer.getCustAddrLine2(),
                customer.getCustAddrLine3(),
                customer.getCustAddrStateCd(),
                customer.getCustAddrCountryCd(),
                customer.getCustAddrZip(),
                customer.getCustPhoneNum1(),
                customer.getCustPhoneNum2(),
                formatFixedWidth(customer.getCustSsn(), NINE_DIGIT_FORMAT),
                customer.getCustGovtIssuedId(),
                customer.getCustDobYyyyMmDd(),
                customer.getCustEftAccountId(),
                customer.getCustPriCardHolderInd(),
                customer.getCustFicoCreditScore());
    }

    /**
     * Extracts the leading three digits of a phone string as its area code,
     * ignoring any punctuation (the legacy layout is {@code (999)999-9999}). When
     * fewer than three digits are present the available digits are returned so the
     * area-code lookup rejects the value.
     *
     * @param phone the non-blank phone string; must not be {@code null}
     * @return the extracted area-code digits (at most three characters)
     */
    private static String extractAreaCode(final String phone) {
        final String digits = phone.replaceAll(NON_DIGIT_PATTERN, "");
        if (digits.length() < AREA_CODE_LENGTH) {
            return digits;
        }
        return digits.substring(0, AREA_CODE_LENGTH);
    }

    /**
     * Normalises a monetary amount to the fixed scale of two using
     * {@link RoundingMode#HALF_UP}, reproducing COBOL rounding at assignment; a
     * {@code null} amount is preserved as {@code null}.
     *
     * @param value the raw amount (may be {@code null})
     * @return the amount at scale 2, or {@code null} when {@code value} is {@code null}
     */
    private static BigDecimal scale2(final BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Converts a nine-digit SSN string to the numeric {@code CUST-SSN} value. A
     * {@code null} or blank input yields {@code null}; the request contract
     * guarantees the value is otherwise nine digits.
     *
     * @param ssn the SSN string (may be {@code null} or blank)
     * @return the numeric SSN, or {@code null} when no value was supplied
     */
    private static Long parseSsn(final String ssn) {
        final String trimmed = trimToNull(ssn);
        return trimmed == null ? null : Long.valueOf(trimmed);
    }

    /**
     * Renders a numeric key as a fixed-width, zero-padded string using the given
     * format, matching the legacy fixed-width presentation. A {@code null} key is
     * returned unchanged.
     *
     * @param value  the numeric key (may be {@code null})
     * @param format the zero-padding format string (for example {@code "%011d"})
     * @return the zero-padded rendering, or {@code null} when {@code value} is {@code null}
     */
    private static String formatFixedWidth(final Long value, final String format) {
        return value == null ? null : String.format(format, value);
    }

    /**
     * Trims a string and collapses blank input to {@code null}, so optional edits
     * treat empty and whitespace-only values the same as an absent value.
     *
     * @param value the raw value (may be {@code null})
     * @return the trimmed value, or {@code null} when {@code value} is {@code null} or blank
     */
    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
