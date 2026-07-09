package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import com.carddemo.exception.DateValidationException;
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
 *   <li>the full {@code 1200-EDIT-MAP-INPUTS} field-edit suite in copybook order
 *       &mdash; Y/N flags ({@code 1220-EDIT-YESNO}), calendar dates
 *       ({@code EDIT-DATE-CCYYMMDD} / {@code EDIT-DATE-OF-BIRTH}), signed money
 *       ({@code 1250-EDIT-SIGNED-9V2}), the three-part SSN
 *       ({@code 1265-EDIT-US-SSN}), the FICO score
 *       ({@code 1245-EDIT-NUM-REQD} / {@code 1275-EDIT-FICO-SCORE}), alphabetic
 *       names/city/country ({@code 1225-EDIT-ALPHA-REQD} /
 *       {@code 1235-EDIT-ALPHA-OPT}), the mandatory address line
 *       ({@code 1215-EDIT-MANDATORY}), numeric ZIP and EFT account
 *       ({@code 1245-EDIT-NUM-REQD}), the state code
 *       ({@code 1270-EDIT-US-STATE-CD}), phone area codes
 *       ({@code 1260-EDIT-US-PHONE-NUM}) and the cross-field state{@code +}ZIP
 *       combo ({@code 1280-EDIT-US-STATE-ZIP-CD}, evaluated last); every failing
 *       field is accumulated and reported together;</li>
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

    // ---------------------------------------------------------------------
    // 1200-EDIT-MAP-INPUTS field labels (COACTUPC WS-EDIT-VARIABLE-NAME literals,
    // verbatim, SHA 27d6c6f). These are the exact strings the legacy edit
    // paragraphs prepend (via FUNCTION TRIM) to each field-level message, so the
    // migrated messages are byte-identical to the mainframe contract.
    // ---------------------------------------------------------------------

    /** Field label "Account Status" ({@code 1220-EDIT-YESNO}). */
    private static final String FIELD_ACCOUNT_STATUS = "Account Status";

    /** Field label "Open Date" ({@code EDIT-DATE-CCYYMMDD}). */
    private static final String FIELD_OPEN_DATE = "Open Date";

    /** Field label "Credit Limit" ({@code 1250-EDIT-SIGNED-9V2}). */
    private static final String FIELD_CREDIT_LIMIT = "Credit Limit";

    /** Field label "Expiry Date" ({@code EDIT-DATE-CCYYMMDD}). */
    private static final String FIELD_EXPIRY_DATE = "Expiry Date";

    /** Field label "Cash Credit Limit" ({@code 1250-EDIT-SIGNED-9V2}). */
    private static final String FIELD_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    /** Field label "Reissue Date" ({@code EDIT-DATE-CCYYMMDD}). */
    private static final String FIELD_REISSUE_DATE = "Reissue Date";

    /** Field label "Current Balance" ({@code 1250-EDIT-SIGNED-9V2}). */
    private static final String FIELD_CURRENT_BALANCE = "Current Balance";

    /** Field label "Current Cycle Credit Limit" ({@code 1250-EDIT-SIGNED-9V2}). */
    private static final String FIELD_CURRENT_CYCLE_CREDIT = "Current Cycle Credit Limit";

    /** Field label "Current Cycle Debit Limit" ({@code 1250-EDIT-SIGNED-9V2}). */
    private static final String FIELD_CURRENT_CYCLE_DEBIT = "Current Cycle Debit Limit";

    /** Field label for SSN part&nbsp;1 &mdash; "SSN: First 3 chars" ({@code 1265-EDIT-US-SSN}). */
    private static final String FIELD_SSN_PART1 = "SSN: First 3 chars";

    /** Field label for SSN part&nbsp;2 &mdash; "SSN 4th &amp; 5th chars" ({@code 1265-EDIT-US-SSN}). */
    private static final String FIELD_SSN_PART2 = "SSN 4th & 5th chars";

    /** Field label for SSN part&nbsp;3 &mdash; "SSN Last 4 chars" ({@code 1265-EDIT-US-SSN}). */
    private static final String FIELD_SSN_PART3 = "SSN Last 4 chars";

    /** Field label "Date of Birth" ({@code EDIT-DATE-CCYYMMDD} then {@code EDIT-DATE-OF-BIRTH}). */
    private static final String FIELD_DATE_OF_BIRTH = "Date of Birth";

    /** Field label "FICO Score" ({@code 1245-EDIT-NUM-REQD} then {@code 1275-EDIT-FICO-SCORE}). */
    private static final String FIELD_FICO_SCORE = "FICO Score";

    /** Field label "First Name" ({@code 1225-EDIT-ALPHA-REQD}). */
    private static final String FIELD_FIRST_NAME = "First Name";

    /** Field label "Middle Name" ({@code 1235-EDIT-ALPHA-OPT}). */
    private static final String FIELD_MIDDLE_NAME = "Middle Name";

    /** Field label "Last Name" ({@code 1225-EDIT-ALPHA-REQD}). */
    private static final String FIELD_LAST_NAME = "Last Name";

    /** Field label "Address Line 1" ({@code 1215-EDIT-MANDATORY}). */
    private static final String FIELD_ADDRESS_LINE_1 = "Address Line 1";

    /** Field label "City" &mdash; the screen "City" maps to {@code CUST-ADDR-LINE-3} ({@code 1225-EDIT-ALPHA-REQD}). */
    private static final String FIELD_CITY = "City";

    /** Field label "Country" ({@code 1225-EDIT-ALPHA-REQD}). */
    private static final String FIELD_COUNTRY = "Country";

    /** Field label "EFT Account Id" ({@code 1245-EDIT-NUM-REQD}). */
    private static final String FIELD_EFT_ACCOUNT_ID = "EFT Account Id";

    /** Field label "Primary Card Holder" ({@code 1220-EDIT-YESNO}). */
    private static final String FIELD_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    // ---------------------------------------------------------------------
    // 1200-EDIT-MAP-INPUTS message suffixes (COACTUPC / CSUTLDPY STRING literals,
    // verbatim, SHA 27d6c6f). Each is concatenated to the trimmed field label,
    // reproducing the exact COBOL WS-RETURN-MSG text.
    // ---------------------------------------------------------------------

    /** "&nbsp;must be supplied." &mdash; blank branch of 1215/1220/1225/1245/1250 (COACTUPC L1841 &amp;c). */
    private static final String MSG_MUST_BE_SUPPLIED = " must be supplied.";

    /** "&nbsp;must be Y or N." &mdash; invalid branch of {@code 1220-EDIT-YESNO} (COACTUPC L1886). */
    private static final String MSG_MUST_BE_Y_OR_N = " must be Y or N.";

    /** "&nbsp;can have alphabets only." &mdash; invalid branch of {@code 1225}/{@code 1235} (COACTUPC L1941, L2047). */
    private static final String MSG_ALPHABETS_ONLY = " can have alphabets only.";

    /** "&nbsp;must be all numeric." &mdash; non-numeric branch of {@code 1245-EDIT-NUM-REQD} (COACTUPC L2146). */
    private static final String MSG_ALL_NUMERIC = " must be all numeric.";

    /** "&nbsp;must not be zero." &mdash; zero branch of {@code 1245-EDIT-NUM-REQD} (COACTUPC L2163). */
    private static final String MSG_MUST_NOT_BE_ZERO = " must not be zero.";

    /**
     * ": should not be 000, 666, or between 900 and 999" &mdash; part-1 exclusion
     * branch of {@code 1265-EDIT-US-SSN} (COACTUPC L2457).
     */
    private static final String MSG_SSN_PART1_EXCLUSION = ": should not be 000, 666, or between 900 and 999";

    /** ": should be between 300 and 850" &mdash; {@code 1275-EDIT-FICO-SCORE} (COACTUPC L2523). */
    private static final String MSG_FICO_RANGE = ": should be between 300 and 850";

    /**
     * ":cannot be in the future " (colon with no leading space, one trailing
     * space) &mdash; {@code EDIT-DATE-OF-BIRTH} future branch (CSUTLDPY L363).
     */
    private static final String MSG_DOB_FUTURE = ":cannot be in the future ";

    /** Inclusive lower bound of a valid FICO score ({@code 88 FICO-RANGE-IS-VALID VALUES 300}). */
    private static final int FICO_MIN = 300;

    /** Inclusive upper bound of a valid FICO score ({@code THROUGH 850}). */
    private static final int FICO_MAX = 850;

    /** Length of SSN part&nbsp;1 (area number): first three digits. */
    private static final int SSN_PART1_END = 3;

    /** Length of SSN part&nbsp;2 (group number): the fourth and fifth digits. */
    private static final int SSN_PART2_END = 5;

    /** Length of the full nine-digit SSN (part&nbsp;3, the serial number, runs to here). */
    private static final int SSN_FULL_LENGTH = 9;

    /** SSN part-1 excluded area number 666 ({@code 88 INVALID-SSN-PART1}). */
    private static final int SSN_PART1_EXCLUDED_666 = 666;

    /** Lower bound of the excluded SSN part-1 area-number band 900&ndash;999 ({@code 88 INVALID-SSN-PART1}). */
    private static final int SSN_PART1_EXCLUDED_900 = 900;

    /**
     * Formatter rendering a {@link LocalDate} as the canonical COBOL
     * {@code CCYYMMDD} string (eight digits, no separators) consumed by
     * {@link DateValidationService#validateDateCcyyMmDd(String, String)}.
     */
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

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
     * Calendar-date validator migrated from {@code CSUTLDTC}/{@code CSUTLDPY}
     * ({@code EDIT-DATE-CCYYMMDD}). Used by the Open&nbsp;Date, Expiry&nbsp;Date,
     * Reissue&nbsp;Date and Date-of-Birth edits to reproduce the exact COBOL
     * year/century/month/day messages. Never {@code null}.
     */
    private final DateValidationService dateValidationService;

    /**
     * Creates the service with its collaborating repositories, lookup helpers and
     * the calendar-date validator.
     *
     * @param accountRepository     repository for the {@link Account} aggregate; must not be {@code null}
     * @param customerRepository    repository for the {@link Customer} aggregate; must not be {@code null}
     * @param crossReferenceService cross-reference resolver for account&rarr;customer; must not be {@code null}
     * @param lookupService         reference-data validator for state/ZIP/area-code edits; must not be {@code null}
     * @param dateValidationService calendar-date validator for the date-field edits
     *                              ({@code EDIT-DATE-CCYYMMDD}); must not be {@code null}
     */
    public AccountUpdateService(final AccountRepository accountRepository,
            final CustomerRepository customerRepository,
            final CrossReferenceService crossReferenceService,
            final LookupService lookupService,
            final DateValidationService dateValidationService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.crossReferenceService = crossReferenceService;
        this.lookupService = lookupService;
        this.dateValidationService = dateValidationService;
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

        // Input-contract guard (immediately after the account-id edit): a null request
        // body is a broken contract, surfaced as a typed HTTP-400 validation failure
        // rather than an unhandled NullPointerException / HTTP 500 on the
        // request.version() dereference in the optimistic-lock precheck below.
        if (request == null) {
            throw new ValidationException(MSG_VALIDATION_SUMMARY);
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
     * Runs the complete {@code COACTUPC} {@code 1200-EDIT-MAP-INPUTS} field-edit
     * suite in exact source order and accumulates every failure into
     * {@code fieldErrors}, preserving the legacy branch/evaluation order
     * (AAP&nbsp;G3). The 3270 program surfaced only the first {@code WS-RETURN-MSG};
     * the REST boundary reports every failing field together (one message per
     * {@code WS-EDIT-VARIABLE-NAME} label), so the caller can correct the whole
     * form in a single round-trip.
     *
     * <p>The twenty-five edits, in the order {@code COACTUPC} performs them:</p>
     * <ol>
     *   <li>Account Status &mdash; {@code 1220-EDIT-YESNO}</li>
     *   <li>Open Date &mdash; {@code EDIT-DATE-CCYYMMDD}</li>
     *   <li>Credit Limit &mdash; {@code 1250-EDIT-SIGNED-9V2}</li>
     *   <li>Expiry Date &mdash; {@code EDIT-DATE-CCYYMMDD}</li>
     *   <li>Cash Credit Limit &mdash; {@code 1250-EDIT-SIGNED-9V2}</li>
     *   <li>Reissue Date &mdash; {@code EDIT-DATE-CCYYMMDD}</li>
     *   <li>Current Balance &mdash; {@code 1250-EDIT-SIGNED-9V2}</li>
     *   <li>Current Cycle Credit Limit &mdash; {@code 1250-EDIT-SIGNED-9V2}</li>
     *   <li>Current Cycle Debit Limit &mdash; {@code 1250-EDIT-SIGNED-9V2}</li>
     *   <li>SSN &mdash; {@code 1265-EDIT-US-SSN} (three sub-parts + part-1 exclusion)</li>
     *   <li>Date of Birth &mdash; {@code EDIT-DATE-CCYYMMDD} then {@code EDIT-DATE-OF-BIRTH}</li>
     *   <li>FICO Score &mdash; {@code 1245-EDIT-NUM-REQD} then {@code 1275-EDIT-FICO-SCORE}</li>
     *   <li>First Name &mdash; {@code 1225-EDIT-ALPHA-REQD}</li>
     *   <li>Middle Name &mdash; {@code 1235-EDIT-ALPHA-OPT}</li>
     *   <li>Last Name &mdash; {@code 1225-EDIT-ALPHA-REQD}</li>
     *   <li>Address Line 1 &mdash; {@code 1215-EDIT-MANDATORY}</li>
     *   <li>State &mdash; {@code 1225-EDIT-ALPHA-REQD} then {@code 1270-EDIT-US-STATE-CD}</li>
     *   <li>Zip &mdash; {@code 1245-EDIT-NUM-REQD}</li>
     *   <li>City &mdash; {@code 1225-EDIT-ALPHA-REQD} (screen "City" = {@code CUST-ADDR-LINE-3})</li>
     *   <li>Country &mdash; {@code 1225-EDIT-ALPHA-REQD}</li>
     *   <li>Phone Number 1 &mdash; {@code 1260-EDIT-US-PHONE-NUM}</li>
     *   <li>Phone Number 2 &mdash; {@code 1260-EDIT-US-PHONE-NUM}</li>
     *   <li>EFT Account Id &mdash; {@code 1245-EDIT-NUM-REQD}</li>
     *   <li>Primary Card Holder &mdash; {@code 1220-EDIT-YESNO}</li>
     *   <li>cross-field State{@code +}ZIP &mdash; {@code 1280-EDIT-US-STATE-ZIP-CD}
     *       (evaluated last, only when {@code FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID})</li>
     * </ol>
     *
     * @param request     the submitted changes; must not be {@code null}
     * @param fieldErrors the accumulator of field&rarr;message failures; must not be {@code null}
     */
    private void editFields(final AccountUpdateRequest request, final Map<String, String> fieldErrors) {
        // 1. Account Status (1220-EDIT-YESNO)
        editYesNo(request.activeStatus(), FIELD_ACCOUNT_STATUS, fieldErrors);
        // 2. Open Date (EDIT-DATE-CCYYMMDD)
        editDate(request.openDate(), FIELD_OPEN_DATE, fieldErrors);
        // 3. Credit Limit (1250-EDIT-SIGNED-9V2)
        editSignedMoney(request.creditLimit(), FIELD_CREDIT_LIMIT, fieldErrors);
        // 4. Expiry Date (EDIT-DATE-CCYYMMDD)
        editDate(request.expirationDate(), FIELD_EXPIRY_DATE, fieldErrors);
        // 5. Cash Credit Limit (1250-EDIT-SIGNED-9V2)
        editSignedMoney(request.cashCreditLimit(), FIELD_CASH_CREDIT_LIMIT, fieldErrors);
        // 6. Reissue Date (EDIT-DATE-CCYYMMDD)
        editDate(request.reissueDate(), FIELD_REISSUE_DATE, fieldErrors);
        // 7. Current Balance (1250-EDIT-SIGNED-9V2)
        editSignedMoney(request.currentBalance(), FIELD_CURRENT_BALANCE, fieldErrors);
        // 8. Current Cycle Credit Limit (1250-EDIT-SIGNED-9V2)
        editSignedMoney(request.currentCycleCredit(), FIELD_CURRENT_CYCLE_CREDIT, fieldErrors);
        // 9. Current Cycle Debit Limit (1250-EDIT-SIGNED-9V2)
        editSignedMoney(request.currentCycleDebit(), FIELD_CURRENT_CYCLE_DEBIT, fieldErrors);
        // 10. SSN (1265-EDIT-US-SSN: three numeric sub-parts + part-1 exclusion)
        editSsn(request.ssn(), fieldErrors);
        // 11. Date of Birth (EDIT-DATE-CCYYMMDD then, if valid, EDIT-DATE-OF-BIRTH)
        editDateOfBirth(request.dateOfBirth(), fieldErrors);
        // 12. FICO Score (1245-EDIT-NUM-REQD then, if valid, 1275-EDIT-FICO-SCORE)
        editFicoScore(request.ficoScore(), fieldErrors);
        // 13. First Name (1225-EDIT-ALPHA-REQD)
        editAlphaRequired(request.firstName(), FIELD_FIRST_NAME, fieldErrors);
        // 14. Middle Name (1235-EDIT-ALPHA-OPT)
        editAlphaOptional(request.middleName(), FIELD_MIDDLE_NAME, fieldErrors);
        // 15. Last Name (1225-EDIT-ALPHA-REQD)
        editAlphaRequired(request.lastName(), FIELD_LAST_NAME, fieldErrors);
        // 16. Address Line 1 (1215-EDIT-MANDATORY)
        editMandatory(request.addressLine1(), FIELD_ADDRESS_LINE_1, fieldErrors);

        // 17. State (1225-EDIT-ALPHA-REQD, then 1270-EDIT-US-STATE-CD only if the
        // alpha edit passed, matching the COBOL guard IF FLG-ALPHA-ISVALID).
        final String stateCode = trimToNull(request.stateCode());
        final boolean stateAlphaValid = editAlphaRequired(request.stateCode(), FIELD_STATE, fieldErrors);
        boolean stateValid = false;
        if (stateAlphaValid) {
            stateValid = editStateCode(stateCode, fieldErrors);
        }

        // 18. Zip (1245-EDIT-NUM-REQD)
        final boolean zipValid = editNumRequired(request.zipCode(), FIELD_ZIP, fieldErrors);
        // 19. City (1225-EDIT-ALPHA-REQD) — screen "City" maps to CUST-ADDR-LINE-3
        editAlphaRequired(request.city(), FIELD_CITY, fieldErrors);
        // 20. Country (1225-EDIT-ALPHA-REQD)
        editAlphaRequired(request.countryCode(), FIELD_COUNTRY, fieldErrors);
        // 21. Phone Number 1 (1260-EDIT-US-PHONE-NUM)
        editPhoneAreaCode(request.phoneNumber1(), FIELD_PHONE_1, fieldErrors);
        // 22. Phone Number 2 (1260-EDIT-US-PHONE-NUM)
        editPhoneAreaCode(request.phoneNumber2(), FIELD_PHONE_2, fieldErrors);
        // 23. EFT Account Id (1245-EDIT-NUM-REQD)
        editNumRequired(request.eftAccountId(), FIELD_EFT_ACCOUNT_ID, fieldErrors);
        // 24. Primary Card Holder (1220-EDIT-YESNO)
        editYesNo(request.primaryCardHolderIndicator(), FIELD_PRIMARY_CARD_HOLDER, fieldErrors);

        // 25. Cross-field State+ZIP combo (1280-EDIT-US-STATE-ZIP-CD), evaluated
        // last and only when both the state code and the ZIP passed their own
        // edits (COBOL: IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID).
        final String zipCode = trimToNull(request.zipCode());
        if (stateValid && zipValid && zipCode != null
                && !lookupService.isValidStateZipCombo(stateCode, zipCode)) {
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
     * Validates a Y/N flag ({@code 1220-EDIT-YESNO}). A blank value (absent,
     * empty or whitespace &mdash; the COBOL {@code LOW-VALUES}/{@code SPACES}
     * branch) records <em>"&lt;label&gt; must be supplied."</em>; any present
     * value other than {@code "Y"} or {@code "N"} records
     * <em>"&lt;label&gt; must be Y or N."</em>. Only the uppercase forms are
     * valid, matching {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'}.
     *
     * @param value       the raw flag value; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editYesNo(final String value, final String label, final Map<String, String> fieldErrors) {
        final String trimmed = trimToNull(value);
        if (trimmed == null) {
            fieldErrors.put(label, label + MSG_MUST_BE_SUPPLIED);
            return;
        }
        if (!"Y".equals(trimmed) && !"N".equals(trimmed)) {
            fieldErrors.put(label, label + MSG_MUST_BE_Y_OR_N);
        }
    }

    /**
     * Validates a mandatory free-text field ({@code 1215-EDIT-MANDATORY}). A blank
     * value records <em>"&lt;label&gt; must be supplied."</em>; any non-blank value
     * is accepted (no character-class restriction).
     *
     * @param value       the raw value; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editMandatory(final String value, final String label, final Map<String, String> fieldErrors) {
        if (trimToNull(value) == null) {
            fieldErrors.put(label, label + MSG_MUST_BE_SUPPLIED);
        }
    }

    /**
     * Validates a mandatory alphabetic field ({@code 1225-EDIT-ALPHA-REQD}). A
     * blank value records <em>"&lt;label&gt; must be supplied."</em>; a present
     * value containing anything other than letters ({@code A-Z}, {@code a-z}) and
     * spaces records <em>"&lt;label&gt; can have alphabets only."</em> (the COBOL
     * {@code INSPECT CONVERTING} alphabet-to-space test).
     *
     * @param value       the raw value; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     * @return {@code true} only when a non-blank, letters-and-spaces-only value was supplied
     */
    private boolean editAlphaRequired(final String value, final String label,
            final Map<String, String> fieldErrors) {
        if (trimToNull(value) == null) {
            fieldErrors.put(label, label + MSG_MUST_BE_SUPPLIED);
            return false;
        }
        if (!isAlphaOrSpace(value)) {
            fieldErrors.put(label, label + MSG_ALPHABETS_ONLY);
            return false;
        }
        return true;
    }

    /**
     * Validates an optional alphabetic field ({@code 1235-EDIT-ALPHA-OPT}). A blank
     * value is accepted (the COBOL blank branch sets the field valid and returns);
     * a present value containing anything other than letters and spaces records
     * <em>"&lt;label&gt; can have alphabets only."</em>.
     *
     * @param value       the raw value; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editAlphaOptional(final String value, final String label,
            final Map<String, String> fieldErrors) {
        if (trimToNull(value) == null) {
            return;
        }
        if (!isAlphaOrSpace(value)) {
            fieldErrors.put(label, label + MSG_ALPHABETS_ONLY);
        }
    }

    /**
     * Validates a mandatory all-numeric field ({@code 1245-EDIT-NUM-REQD}) in the
     * COBOL branch order: blank &rarr; <em>"&lt;label&gt; must be supplied."</em>;
     * non-numeric &rarr; <em>"&lt;label&gt; must be all numeric."</em>; a value of
     * zero ({@code FUNCTION NUMVAL = 0}) &rarr;
     * <em>"&lt;label&gt; must not be zero."</em>.
     *
     * @param value       the raw value; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     * @return {@code true} only when a non-blank, all-numeric, non-zero value was supplied
     */
    private boolean editNumRequired(final String value, final String label,
            final Map<String, String> fieldErrors) {
        final String trimmed = trimToNull(value);
        if (trimmed == null) {
            fieldErrors.put(label, label + MSG_MUST_BE_SUPPLIED);
            return false;
        }
        if (!isAllDigits(trimmed)) {
            fieldErrors.put(label, label + MSG_ALL_NUMERIC);
            return false;
        }
        if (isAllZeros(trimmed)) {
            fieldErrors.put(label, label + MSG_MUST_NOT_BE_ZERO);
            return false;
        }
        return true;
    }

    /**
     * Validates a mandatory signed-decimal monetary field
     * ({@code 1250-EDIT-SIGNED-9V2}). A {@code null} value (the COBOL
     * {@code LOW-VALUES}/{@code SPACES} branch) records
     * <em>"&lt;label&gt; must be supplied."</em>.
     *
     * <p>The COBOL {@code TEST-NUMVAL-C != 0} branch (message <em>"&lt;label&gt; is
     * not valid"</em>, no trailing period) is structurally unreachable at this
     * boundary: the amount arrives as a typed {@link BigDecimal} constrained by
     * {@code @Digits(integer = 10, fraction = 2)}, so any present value is already
     * a well-formed signed decimal, and {@link #scale2(BigDecimal)} later
     * normalises its scale on write. Only the blank branch is reachable here.</p>
     *
     * @param value       the monetary amount; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editSignedMoney(final BigDecimal value, final String label,
            final Map<String, String> fieldErrors) {
        if (value == null) {
            fieldErrors.put(label, label + MSG_MUST_BE_SUPPLIED);
        }
    }

    /**
     * Validates the FICO score ({@code 1245-EDIT-NUM-REQD} on the three-character
     * field, then {@code 1275-EDIT-FICO-SCORE}). A {@code null} value records
     * <em>"FICO Score must be supplied."</em>; a value of zero records
     * <em>"FICO Score must not be zero."</em> (the numeric edit runs first, so zero
     * is reported as such rather than as an out-of-range value); otherwise a value
     * outside the inclusive band {@value #FICO_MIN}&ndash;{@value #FICO_MAX} records
     * <em>"FICO Score: should be between 300 and 850"</em> ({@code 88
     * FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}). The <em>"must be all
     * numeric"</em> branch is unreachable for a typed {@link Integer}.
     *
     * @param ficoScore   the FICO score; may be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editFicoScore(final Integer ficoScore, final Map<String, String> fieldErrors) {
        if (ficoScore == null) {
            fieldErrors.put(FIELD_FICO_SCORE, FIELD_FICO_SCORE + MSG_MUST_BE_SUPPLIED);
            return;
        }
        final int score = ficoScore;
        if (score == 0) {
            fieldErrors.put(FIELD_FICO_SCORE, FIELD_FICO_SCORE + MSG_MUST_NOT_BE_ZERO);
            return;
        }
        if (score < FICO_MIN || score > FICO_MAX) {
            fieldErrors.put(FIELD_FICO_SCORE, FIELD_FICO_SCORE + MSG_FICO_RANGE);
        }
    }

    /**
     * Validates the nine-digit SSN ({@code 1265-EDIT-US-SSN}). The legacy edit
     * treats the SSN as three independent sub-fields, each run through
     * {@code 1245-EDIT-NUM-REQD}, plus a part-1 exclusion:
     * <ul>
     *   <li><strong>Part&nbsp;1</strong> ("SSN: First 3 chars", digits 1&ndash;3):
     *       the numeric edit, then &mdash; only when it passes &mdash; the exclusion
     *       of area numbers {@value #SSN_PART1_EXCLUDED_666} and
     *       {@value #SSN_PART1_EXCLUDED_900}&ndash;999, recording
     *       <em>": should not be 000, 666, or between 900 and 999"</em>. Area number
     *       {@code 000} is reported by the numeric edit itself as
     *       <em>"must not be zero."</em> (it fails {@code 1245} first, so the
     *       exclusion is not reached), exactly as the COBOL {@code IF
     *       FLG-EDIT-US-SSN-PART1-ISVALID} guard dictates.</li>
     *   <li><strong>Part&nbsp;2</strong> ("SSN 4th &amp; 5th chars", digits
     *       4&ndash;5): the numeric edit.</li>
     *   <li><strong>Part&nbsp;3</strong> ("SSN Last 4 chars", digits 6&ndash;9):
     *       the numeric edit.</li>
     * </ul>
     * A {@code null}/blank SSN therefore records the blank message under all three
     * part labels, matching the three independent COBOL edits.
     *
     * @param ssn         the raw nine-digit SSN string; may be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editSsn(final String ssn, final Map<String, String> fieldErrors) {
        final String trimmed = trimToNull(ssn);

        final String part1 = subField(trimmed, 0, SSN_PART1_END);
        final boolean part1Valid = editNumRequired(part1, FIELD_SSN_PART1, fieldErrors);
        if (part1Valid) {
            final int area = Integer.parseInt(part1);
            if (area == SSN_PART1_EXCLUDED_666 || area >= SSN_PART1_EXCLUDED_900) {
                fieldErrors.put(FIELD_SSN_PART1, FIELD_SSN_PART1 + MSG_SSN_PART1_EXCLUSION);
            }
        }

        editNumRequired(subField(trimmed, SSN_PART1_END, SSN_PART2_END), FIELD_SSN_PART2, fieldErrors);
        editNumRequired(subField(trimmed, SSN_PART2_END, SSN_FULL_LENGTH), FIELD_SSN_PART3, fieldErrors);
    }

    /**
     * Validates a calendar date ({@code EDIT-DATE-CCYYMMDD}) by delegating to
     * {@link DateValidationService}, which reproduces the ordered CSUTLDPY
     * year/century/month/day messages. A {@code null} date is passed through as a
     * missing {@code CCYYMMDD} field, which the validator reports as
     * <em>"&lt;label&gt; : Year must be supplied."</em>; a present date is rendered
     * to the canonical eight-digit {@code CCYYMMDD} string (enforcing the COBOL
     * 19xx/20xx century rule).
     *
     * @param value       the date; may be {@code null}
     * @param label       the {@code WS-EDIT-VARIABLE-NAME} label; must not be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     * @return {@code true} only when a present date passed every calendar edit
     */
    private boolean editDate(final LocalDate value, final String label,
            final Map<String, String> fieldErrors) {
        try {
            dateValidationService.validateDateCcyyMmDd(toCcyyMmDd(value), label);
            return true;
        } catch (final DateValidationException ex) {
            fieldErrors.put(label, ex.getMessage());
            return false;
        }
    }

    /**
     * Validates the date of birth ({@code EDIT-DATE-CCYYMMDD} then, when that
     * passes, {@code EDIT-DATE-OF-BIRTH}). After the calendar edits, the date of
     * birth must be strictly in the past &mdash; the COBOL guard {@code IF
     * WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY CONTINUE ELSE} error &mdash; so a
     * date equal to or after today records <em>"Date of Birth:cannot be in the
     * future "</em> (colon with no leading space and a single trailing space,
     * verbatim from CSUTLDPY).
     *
     * @param dob         the date of birth; may be {@code null}
     * @param fieldErrors the accumulator; must not be {@code null}
     */
    private void editDateOfBirth(final LocalDate dob, final Map<String, String> fieldErrors) {
        final boolean valid = editDate(dob, FIELD_DATE_OF_BIRTH, fieldErrors);
        if (valid && !LocalDate.now().isAfter(dob)) {
            fieldErrors.put(FIELD_DATE_OF_BIRTH, FIELD_DATE_OF_BIRTH + MSG_DOB_FUTURE);
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

    /**
     * Reports whether every character of a non-empty string is an ASCII digit
     * ({@code 0-9}), reproducing the COBOL {@code IS NUMERIC} test used by
     * {@code 1245-EDIT-NUM-REQD}. An empty string is not numeric.
     *
     * @param value the value to test; must not be {@code null}
     * @return {@code true} only when {@code value} is non-empty and all digits
     */
    private static boolean isAllDigits(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether an all-digit string represents the numeric value zero,
     * reproducing the COBOL {@code FUNCTION NUMVAL(...) = 0} test of
     * {@code 1245-EDIT-NUM-REQD} (for example {@code "000"} &rarr; {@code true},
     * {@code "007"} &rarr; {@code false}).
     *
     * @param digits an all-digit string; must not be {@code null}
     * @return {@code true} only when every character is {@code '0'}
     */
    private static boolean isAllZeros(final String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character of a string is a letter ({@code A-Z} or
     * {@code a-z}) or a space, reproducing the COBOL {@code 1225}/{@code 1235}
     * {@code INSPECT CONVERTING} alphabet-to-space test (the 52-character
     * {@code LIT-ALL-ALPHA-FROM} plus embedded spaces).
     *
     * @param value the value to test; must not be {@code null}
     * @return {@code true} when {@code value} contains only letters and spaces
     */
    private static boolean isAlphaOrSpace(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean alpha = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!alpha && c != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts a fixed sub-field of an SSN string ({@code 1265-EDIT-US-SSN}),
     * returning {@code null} when the value is absent or too short to reach
     * {@code start} (so the sub-field is treated as blank by
     * {@link #editNumRequired}). The returned slice is clamped to the value length.
     *
     * @param value the source SSN string; may be {@code null}
     * @param start the inclusive start index
     * @param end   the exclusive end index
     * @return the sub-field, or {@code null} when {@code value} is {@code null} or
     *         does not extend to {@code start}
     */
    private static String subField(final String value, final int start, final int end) {
        if (value == null || start >= value.length()) {
            return null;
        }
        return value.substring(start, Math.min(end, value.length()));
    }

    /**
     * Renders a {@link LocalDate} as the canonical eight-digit COBOL
     * {@code CCYYMMDD} string consumed by {@link DateValidationService}; a
     * {@code null} date yields {@code null}, which the validator reports as a
     * missing date.
     *
     * @param date the date (may be {@code null})
     * @return the {@code CCYYMMDD} rendering, or {@code null} when {@code date} is {@code null}
     */
    private static String toCcyyMmDd(final LocalDate date) {
        return date == null ? null : date.format(CCYYMMDD);
    }
}
