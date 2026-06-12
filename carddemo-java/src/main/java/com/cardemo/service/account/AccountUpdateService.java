package com.cardemo.service.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.OptimisticLockException;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.ValidationLookupService;

/**
 * Account-maintenance service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the
 * online CICS program <strong>{@code app/cbl/COACTUPC.cbl}</strong> (CICS transaction {@code CAUP},
 * BMS mapset {@code COACTUP}). At 4,236 lines {@code COACTUPC} is the single most complex program in
 * the AWS CardDemo estate, and it is the <strong>sole {@code SYNCPOINT ROLLBACK} site</strong> in the
 * whole system: it performs a dual update of the {@code ACCTDAT} (account) and {@code CUSTDAT}
 * (customer) VSAM records that must commit together or roll back together.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COACTUPC}'s observable behavior <em>exactly</em> &mdash; the same
 * field-edit cascade, in the same order, producing the same messages, the same change-detection
 * short-circuit, and the same transactional/optimistic-locking semantics. Per the Minimal Change
 * Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology transition.
 * The COBOL is read-only reference material at the frozen baseline commit SHA {@code 27d6c6f} and is
 * never copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>Technology substitutions (documented at each point of change)</h2>
 * <ul>
 *   <li><strong>VSAM {@code READ ... UPDATE} &rarr; JPA {@code findById}.</strong> The keyed
 *       read-for-update of {@code ACCTDAT}/{@code CUSTDAT} becomes
 *       {@link AccountRepository#findById(Object)} / {@link CustomerRepository#findById(Object)};
 *       the returned managed entities are the working copies.</li>
 *   <li><strong>{@code SYNCPOINT ROLLBACK} &rarr; {@link Transactional @Transactional}.</strong> The
 *       {@link #updateAccount(String, AccountDto)} method is annotated
 *       {@code @Transactional(rollbackFor = Exception.class)} so the account write and the customer
 *       write either both commit or both roll back &mdash; the direct translation of the COBOL
 *       {@code REWRITE ACCTDAT} / {@code REWRITE CUSTDAT} guarded by {@code SYNCPOINT ROLLBACK}
 *       (AAP &sect;0.6.4, &sect;0.7.5).</li>
 *   <li><strong>Read-update before/after image compare ({@code 9700-CHECK-CHANGE-IN-REC}) &rarr; JPA
 *       {@code @Version}.</strong> {@code COACTUPC} snapshotted the fetched record and re-compared it
 *       under the update lock to detect a concurrent change; that snapshot comparison is replaced by
 *       the {@code @Version} column on {@link Account}. A stale write surfaces as an
 *       {@link ObjectOptimisticLockingFailureException}, mapped here to a typed
 *       {@link ConcurrentModificationException} (HTTP&nbsp;409).</li>
 *   <li><strong>{@code CALL 'CSUTLDTC'} / {@code CEEDAYS} date edits &rarr;
 *       {@link DateValidationService}.</strong> Open/Expiry/Reissue/Date-of-Birth validation is
 *       delegated to the shared date service (which uses {@code java.time}).</li>
 *   <li><strong>{@code CSLKPCDY} NANPA/state/ZIP lookups &rarr; {@link ValidationLookupService}.</strong></li>
 * </ul>
 *
 * <h2>Pseudo-conversational confirm flow &rarr; one REST call (PHASE&nbsp;0)</h2>
 * <p>{@code COACTUPC} is pseudo-conversational with a two-step confirm state machine
 * ({@code 2000-DECIDE-ACTION}): it first displays the record, then on PF05 ("confirm") performs the
 * write. In REST this collapses into a <strong>single version-carrying {@code PUT}</strong> handled by
 * {@link #updateAccount(String, AccountDto)}. The CICS plumbing &mdash; COMMAREA, {@code SEND}/
 * {@code RECEIVE MAP}, PF-key dispatch, {@code 3000-SEND-MAP}, {@code 3200-SETUP-SCREEN-VARS} &mdash;
 * belongs to the controller layer ({@code AccountController}), not this service.</p>
 *
 * <h2>Message fidelity</h2>
 * <p>The validation messages emitted here are the <em>verbatim</em> {@code COACTUPC}
 * {@code WS-RETURN-MSG} texts (with the COBOL {@code WS-EDIT-VARIABLE-NAME} field labels such as
 * {@code "Account Status"}, {@code "Expiry Date"} and {@code "Current Cycle Credit Limit"}), which is
 * what "100% behavioral parity / verbatim messages" (AAP &sect;0.7.2) requires. Whereas {@code COACTUPC}
 * displays only the first failing message per screen round-trip (a single {@code WS-RETURN-MSG} guarded
 * by {@code WS-RETURN-MSG-OFF}), this REST translation accumulates one message per failed field, in the
 * exact cascade order, and raises them together in a single {@link ValidationException} &mdash; the
 * same fields are flagged ({@code INPUT-ERROR}); only the delivery is batched, suited to a stateless
 * {@code PUT}.</p>
 *
 * <h2>DTO/entity reconciliation</h2>
 * <p>{@link Account} stores the three account dates as {@link LocalDate}; {@link Customer} stores the
 * SSN as a 9-digit {@link Long}, the FICO score as an {@link Integer}, the date of birth as a
 * {@link LocalDate} and the two phones as {@code (999)999-9999} text. The {@link AccountDto} uses
 * {@link LocalDate} for dates, a dash-formatted {@code XXX-XX-XXXX} SSN string and a {@link String}
 * FICO score. The COBOL {@code COACTUP} screen further decomposed each date into year/month/day and
 * each SSN/phone into components; this service reconstructs those components from the DTO so the
 * component-level edits can run, then re-composes the canonical forms on write &mdash; the
 * BMS-map&rarr;DTO reconciliation noted in the file plan.</p>
 *
 * @see AccountRepository
 * @see CustomerRepository
 * @see DateValidationService
 * @see ValidationLookupService
 */
@Service
public class AccountUpdateService {

    /** Length of the account identifier ({@code ACCT-ID PIC 9(11)}). */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** Length of the customer identifier ({@code CUST-ID PIC 9(09)}). */
    private static final int CUSTOMER_ID_LENGTH = 9;

    /** Scale of every monetary field ({@code PIC S9(10)V99}) &mdash; two fractional digits (AAP &sect;0.7.3). */
    private static final int MONEY_SCALE = 2;

    /**
     * {@code CCYYMMDD} formatter ({@code yyyyMMdd}, no separators) used to render a {@link LocalDate}
     * into the 8-character argument that {@link DateValidationService#validateCcyymmdd(String, String)}
     * expects (it decomposes positionally into {@code CCYY}/{@code MM}/{@code DD}).
     */
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection with private-final fields (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;

    /**
     * Creates the service with its required collaborators.
     *
     * @param accountRepository       repository for the {@code ACCTDAT} replacement (carries
     *                                {@code @Version} on {@link Account})
     * @param customerRepository      repository for the {@code CUSTDAT} replacement (no {@code @Version};
     *                                protected by the shared transaction)
     * @param dateValidationService   shared date-edit service ({@code CSUTLDTC}/{@code CEEDAYS} translation)
     * @param validationLookupService shared NANPA/state/ZIP lookup service ({@code CSLKPCDY} translation)
     */
    public AccountUpdateService(AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                DateValidationService dateValidationService,
                                ValidationLookupService validationLookupService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
    }

    /**
     * Updates an account and its associated customer, reproducing {@code COACTUPC} end to end.
     *
     * <p>The method is annotated {@code @Transactional(rollbackFor = Exception.class)} &mdash; this is
     * the translation of the sole {@code SYNCPOINT ROLLBACK} in the estate: the account write and the
     * customer write share one transaction, so any failure (validation, optimistic lock or database)
     * rolls back BOTH, exactly as {@code COACTUPC}'s dual {@code REWRITE} + {@code SYNCPOINT ROLLBACK}
     * did.</p>
     *
     * <p>Order of processing (mirrors {@code 1200-EDIT-MAP-INPUTS} after data has been fetched):</p>
     * <ol>
     *   <li>Validate the account key ({@code 1210-EDIT-ACCOUNT}) and read the account and customer
     *       records ({@code READ ... UPDATE} &rarr; {@code findById}).</li>
     *   <li>Detect changes ({@code 1205-COMPARE-OLD-NEW}); if nothing changed, short-circuit without
     *       writing &mdash; the {@code NO-CHANGES-DETECTED} path &mdash; and redisplay the current
     *       committed values.</li>
     *   <li>Run the field-edit cascade ({@code 1200-EDIT-MAP-INPUTS}); accumulate every failure and, if
     *       any, raise a single {@link ValidationException}.</li>
     *   <li>Apply the validated values and write both records ({@code 9600-WRITE-PROCESSING}); a stale
     *       {@code @Version} maps to {@link ConcurrentModificationException}.</li>
     * </ol>
     *
     * @param accountId the account identifier from the request path (authoritative key)
     * @param request   the submitted account+customer values
     * @return the committed account, re-assembled as an {@link AccountDto} (mirrors {@code COACTUPC}
     *         redisplaying the stored values after a successful update)
     * @throws ValidationException             if the key or any field fails its edit (HTTP&nbsp;400)
     * @throws RecordNotFoundException         if the account or customer does not exist (HTTP&nbsp;404)
     * @throws ConcurrentModificationException if the account was changed concurrently (HTTP&nbsp;409)
     */
    @Transactional(rollbackFor = Exception.class)
    // COBOL SYNCPOINT ROLLBACK -> Spring @Transactional(rollbackFor=...): the dual ACCTDAT+CUSTDAT
    // REWRITE is the sole SYNCPOINT site in the estate; both writes commit or roll back together.
    public AccountDto updateAccount(String accountId, AccountDto request) {
        if (request == null) {
            // Defensive: a null body is a malformed request. COACTUPC always received a populated
            // COMMAREA; an empty payload has no COBOL analogue, so it is reported as a validation error.
            throw new ValidationException("Account", "Account details must be supplied.");
        }

        // PHASE 1 - validate the account key (1210-EDIT-ACCOUNT) before any I/O.
        Long acctKey = validateAccountKey(accountId);

        // PHASE 1 - VSAM ACCTDAT READ UPDATE -> JPA findById (managed entity; @Version captured here
        // is the "before image" that 9500-STORE-FETCHED-DATA snapshotted into ACUP-OLD-*).
        Account account = accountRepository.findById(acctKey)
                .orElseThrow(() -> new RecordNotFoundException("Account", accountId));

        // PHASE 1 - resolve the customer that belongs to the account. The DTO carries the customer id
        // (obtained from the prior view, where it was resolved via the CXACAIX cross-reference).
        Long custKey = resolveCustomerKey(request.getCustomerId());
        // VSAM CUSTDAT READ UPDATE -> JPA findById. Customer has no @Version; its consistency is
        // guaranteed by the shared @Transactional boundary and the Account version check.
        Customer customer = customerRepository.findById(custKey)
                .orElseThrow(() -> new RecordNotFoundException("Customer", String.valueOf(custKey)));

        // PHASE 3 - 1205-COMPARE-OLD-NEW: if the submitted values match the fetched ("old") values,
        // COACTUPC sets NO-CHANGES-DETECTED and performs no write. Reproduce the no-op: redisplay the
        // current committed values without touching the database.
        if (!hasChanges(request, account, customer)) {
            return assembleDto(account, customer);
        }

        // PHASE 2 - 1200-EDIT-MAP-INPUTS field-edit cascade. Accumulate one message per failed field
        // (in exact COBOL order) and raise them together (COBOL set INPUT-ERROR per field).
        List<String> errors = new ArrayList<>();
        validateInputs(request, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        // PHASE 3b - 9700-CHECK-CHANGE-IN-REC / DATA-WAS-CHANGED-BEFORE-UPDATE: detect a STALE client
        // form. COACTUPC snapshotted the record image at display time (ACUP-OLD-*) and, before the
        // REWRITE, compared it against the record re-read for update; a mismatch meant another user had
        // committed a change in between, so the update was rejected ("Record changed by some one else").
        // REST analogue: the client echoes the version it saw at view time (request.getVersion()); if it
        // no longer equals the current committed entity version, the form is stale -> reject with 409
        // BEFORE writing. This closes the gap that server-side @Version alone cannot: @Version only
        // catches a change committed AFTER this transaction's read, not a client form loaded before an
        // earlier completed update. Enforced only when the client supplies a version (opt-in); when
        // absent, the JPA @Version on saveAndFlush below remains the safety net (AAP §0.7.5).
        if (request.getVersion() != null && !request.getVersion().equals(account.getVersion())) {
            throw ConcurrentModificationException.forEntity("Account", null);
        }

        // PHASE 4 - apply the validated values and write both records inside the one transaction.
        applyToAccount(request, account);
        applyToCustomer(request, customer);

        // Dual ACCTDAT+CUSTDAT REWRITE - sole SYNCPOINT ROLLBACK in estate -> @Transactional(rollbackFor=...).
        // saveAndFlush forces the SQL (and the @Version check) to execute synchronously here, mirroring
        // COACTUPC checking WS-RESP-CD immediately after each REWRITE; with a plain save the optimistic
        // check would defer to commit, outside the catch below.
        try {
            accountRepository.saveAndFlush(account);
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            // COBOL DATA-WAS-CHANGED-BEFORE-UPDATE (optimistic snapshot mismatch) -> JPA @Version ->
            // ConcurrentModificationException (409). Use the com.cardemo.exception type, NOT java.util.
            throw ConcurrentModificationException.forEntity("Account", ex);
        }

        // Re-assemble and return the committed values (COACTUPC redisplays the updated record).
        return assembleDto(account, customer);
    }

    // =============================================================================================
    // PHASE 1 helpers - account-key edit (1210-EDIT-ACCOUNT) and customer-key resolution.
    // =============================================================================================

    /**
     * Validates the account key and returns it as a {@link Long}, reproducing {@code 1210-EDIT-ACCOUNT}:
     * a blank key (SPACES / LOW-VALUES) is the "must be supplied" prompt; a non-numeric or zero key is
     * the "11 digit Non-Zero Number" error. The check is value-based (a zero of any width fails),
     * matching {@code CC-ACCT-ID-N EQUAL ZEROS}.
     *
     * @param accountId the request-path account identifier
     * @return the parsed account key
     * @throws ValidationException if the key is blank, non-numeric or zero
     */
    private Long validateAccountKey(String accountId) {
        if (isBlank(accountId)) {
            throw new ValidationException("Account Number", "Account Number must be supplied.");
        }
        String trimmed = accountId.trim();
        if (!isAllDigits(trimmed) || isAllZeros(trimmed)) {
            throw new ValidationException("Account Number if supplied must be a 11 digit Non-Zero Number");
        }
        return Long.parseLong(trimmed);
    }

    /**
     * Resolves the customer key carried by the request. {@code COACTUPC} reads the customer that owns
     * the account; in REST the id is supplied by the client (obtained from the prior view, where it was
     * resolved via the {@code CXACAIX} cross-reference). A blank or non-numeric id cannot identify a
     * customer, so it is reported as a not-found condition &mdash; the same outcome the COBOL reached
     * when the keyed {@code CUSTDAT} read returned no record.
     *
     * @param customerId the customer id from the DTO
     * @return the parsed customer key
     * @throws RecordNotFoundException if the id is missing or not numeric
     */
    private Long resolveCustomerKey(String customerId) {
        if (isBlank(customerId) || !isAllDigits(customerId.trim())) {
            throw new RecordNotFoundException("Customer", trimToEmpty(customerId));
        }
        return Long.parseLong(customerId.trim());
    }

    // =============================================================================================
    // PHASE 2 - field-edit cascade (1200-EDIT-MAP-INPUTS). Fields are edited in the EXACT COBOL order
    // 1..24; each failed field contributes one message. The 1280 State/ZIP cross-field edit runs only
    // when both the State and ZIP per-field edits passed (COBOL: FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID).
    // =============================================================================================

    /**
     * Runs the {@code 1200-EDIT-MAP-INPUTS} cascade, appending one message per failed field (in the
     * COBOL field order 1..24) to {@code errors}, then the {@code 1280-EDIT-US-STATE-ZIP-CD} cross-field
     * edit. The single DTO date/SSN/phone strings are decomposed into the component values the COBOL
     * screen edited (the BMS-map &rarr; DTO reconciliation) so the component-level edits run unchanged;
     * the values are re-composed on write (PHASE 4).
     *
     * @param req    the submitted values
     * @param errors the accumulating error list
     */
    private void validateInputs(AccountDto req, List<String> errors) {
        // 1. Account Status -> 1220-EDIT-YESNO.
        addIfPresent(errors, editYesNo(req.getAccountStatus(), "Account Status"));
        // 2. Open Date -> EDIT-DATE-CCYYMMDD (CSUTLDTC/CEEDAYS -> DateValidationService).
        addIfPresent(errors, editDate(req.getOpenDate(), "Open Date"));
        // 3. Credit Limit -> 1250-EDIT-SIGNED-9V2.
        addIfPresent(errors, editSigned9v2(req.getCreditLimit(), "Credit Limit"));
        // 4. Expiry Date -> EDIT-DATE-CCYYMMDD.
        addIfPresent(errors, editDate(req.getExpirationDate(), "Expiry Date"));
        // 5. Cash Credit Limit -> 1250-EDIT-SIGNED-9V2.
        addIfPresent(errors, editSigned9v2(req.getCashCreditLimit(), "Cash Credit Limit"));
        // 6. Reissue Date -> EDIT-DATE-CCYYMMDD.
        addIfPresent(errors, editDate(req.getReissueDate(), "Reissue Date"));
        // 7. Current Balance -> 1250-EDIT-SIGNED-9V2.
        addIfPresent(errors, editSigned9v2(req.getCurrentBalance(), "Current Balance"));
        // 8. Current Cycle Credit -> 1250-EDIT-SIGNED-9V2 (COBOL label "Current Cycle Credit Limit").
        addIfPresent(errors, editSigned9v2(req.getCurrentCycleCredit(), "Current Cycle Credit Limit"));
        // 9. Current Cycle Debit -> 1250-EDIT-SIGNED-9V2 (COBOL label "Current Cycle Debit Limit").
        addIfPresent(errors, editSigned9v2(req.getCurrentCycleDebit(), "Current Cycle Debit Limit"));
        // 10. SSN -> 1265-EDIT-US-SSN (3 components, first failure reported).
        addIfPresent(errors, editUsSsn(req.getSsn()));
        // 11. Date of Birth -> EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH (validateDateOfBirth wraps both).
        addIfPresent(errors, editDateOfBirth(req.getDateOfBirth(), "Date of Birth"));
        // 12. FICO Score -> 1245-EDIT-NUM-REQD (len 3) then 1275-EDIT-FICO-SCORE (300..850).
        addIfPresent(errors, editFicoScore(req.getFicoScore(), "FICO Score"));
        // 13. First Name -> 1225-EDIT-ALPHA-REQD.
        addIfPresent(errors, editAlphaReqd(req.getFirstName(), "First Name"));
        // 14. Middle Name -> 1235-EDIT-ALPHA-OPT.
        addIfPresent(errors, editAlphaOpt(req.getMiddleName(), "Middle Name"));
        // 15. Last Name -> 1225-EDIT-ALPHA-REQD.
        addIfPresent(errors, editAlphaReqd(req.getLastName(), "Last Name"));
        // 16. Address Line 1 -> 1215-EDIT-MANDATORY.
        addIfPresent(errors, editMandatory(req.getAddressLine1(), "Address Line 1"));
        // 17. State -> 1225-EDIT-ALPHA-REQD then (only if alpha-valid) 1270-EDIT-US-STATE-CD.
        boolean stateValid;
        String stateAlphaMsg = editAlphaReqd(req.getState(), "State");
        if (stateAlphaMsg != null) {
            errors.add(stateAlphaMsg);
            stateValid = false;
        } else if (!validationLookupService.isValidStateCode(trimToEmpty(req.getState()))) {
            // CSLKPCDY VALID-US-STATE-CODE lookup -> ValidationLookupService.isValidStateCode.
            errors.add("State: is not a valid state code");
            stateValid = false;
        } else {
            stateValid = true;
        }
        // 18. Zip -> 1245-EDIT-NUM-REQD (len 5).
        String zipMsg = editNumReqd(req.getZipCode(), "Zip", 5);
        boolean zipValid = zipMsg == null;
        addIfPresent(errors, zipMsg);
        // 19. City (Address Line 3) -> 1225-EDIT-ALPHA-REQD.
        addIfPresent(errors, editAlphaReqd(req.getCity(), "City"));
        // 20. Country -> 1225-EDIT-ALPHA-REQD.
        addIfPresent(errors, editAlphaReqd(req.getCountryCode(), "Country"));
        // 21. Phone 1 -> 1260-EDIT-US-PHONE-NUM.
        addIfPresent(errors, editUsPhone(req.getPhoneNumber1(), "Phone Number 1"));
        // 22. Phone 2 -> 1260-EDIT-US-PHONE-NUM.
        addIfPresent(errors, editUsPhone(req.getPhoneNumber2(), "Phone Number 2"));
        // 23. EFT Account Id -> 1245-EDIT-NUM-REQD (len 10).
        addIfPresent(errors, editNumReqd(req.getEftAccountId(), "EFT Account Id", 10));
        // 24. Primary Card Holder -> 1220-EDIT-YESNO.
        addIfPresent(errors, editYesNo(req.getPrimaryCardHolderIndicator(), "Primary Card Holder"));

        // 1280-EDIT-US-STATE-ZIP-CD cross-field edit: only when BOTH State and Zip passed individually.
        // CSLKPCDY VALID-US-STATE-ZIP-CD2-COMBO lookup -> ValidationLookupService.isValidStateZip.
        if (stateValid && zipValid
                && !validationLookupService.isValidStateZip(trimToEmpty(req.getState()),
                        trimToEmpty(req.getZipCode()))) {
            errors.add("Invalid zip code for state");
        }
    }

    // =============================================================================================
    // Edit-paragraph helpers (reproduced verbatim from COACTUPC). Each returns the COBOL WS-RETURN-MSG
    // text on failure, or null when the field passes. Generic helpers (1215..1250) emit "<label> <text>"
    // (leading space); lookup-style helpers (1260/1265/1270/1275) emit "<label>: <text>".
    // =============================================================================================

    /**
     * {@code 1220-EDIT-YESNO}: blank/spaces/zeros -&gt; "must be supplied"; otherwise the value must be
     * exactly {@code 'Y'} or {@code 'N'} ({@code 88 FLG-YES-NO-ISVALID VALUES 'Y','N'}).
     */
    private String editYesNo(String value, String label) {
        if (isBlankOrZeros(value)) {
            return label + " must be supplied.";
        }
        String v = value.trim();
        if (!"Y".equals(v) && !"N".equals(v)) {
            return label + " must be Y or N.";
        }
        return null;
    }

    /**
     * {@code 1215-EDIT-MANDATORY}: blank/spaces/trim-length-0 -&gt; "must be supplied".
     */
    private String editMandatory(String value, String label) {
        if (isBlank(value)) {
            return label + " must be supplied.";
        }
        return null;
    }

    /**
     * {@code 1225-EDIT-ALPHA-REQD}: blank -&gt; "must be supplied"; any character that is not a letter
     * or space (COBOL {@code INSPECT CONVERTING} alphabetics to spaces, then a residual non-blank test)
     * -&gt; "can have alphabets only".
     */
    private String editAlphaReqd(String value, String label) {
        if (isBlank(value)) {
            return label + " must be supplied.";
        }
        if (!isAlphaSpaces(value)) {
            return label + " can have alphabets only.";
        }
        return null;
    }

    /**
     * {@code 1235-EDIT-ALPHA-OPT}: blank -&gt; VALID (optional, no error); otherwise the alphabetic-only
     * check -&gt; "can have alphabets only".
     */
    private String editAlphaOpt(String value, String label) {
        if (isBlank(value)) {
            return null;
        }
        if (!isAlphaSpaces(value)) {
            return label + " can have alphabets only.";
        }
        return null;
    }

    /**
     * {@code 1245-EDIT-NUM-REQD}: blank -&gt; "must be supplied"; not exactly {@code length} digits
     * (COBOL {@code IS NUMERIC} on the fixed-width field; a space-padded short value is non-numeric)
     * -&gt; "must be all numeric"; numeric value 0 -&gt; "must not be zero".
     */
    private String editNumReqd(String value, String label, int length) {
        if (isBlank(value)) {
            return label + " must be supplied.";
        }
        String v = value.trim();
        if (v.length() != length || !isAllDigits(v)) {
            return label + " must be all numeric.";
        }
        if (isAllZeros(v)) {
            return label + " must not be zero.";
        }
        return null;
    }

    /**
     * {@code 1250-EDIT-SIGNED-9V2}: a {@code null} amount is "must be supplied" (note: NO trailing
     * period on the "is not valid" branch, matching the COBOL literal). A non-null {@link BigDecimal}
     * is, by construction, a valid signed decimal &mdash; Jackson rejects any non-numeric body at
     * deserialization, so the {@code TEST-NUMVAL-C} "is not valid" branch cannot fire from a typed
     * amount; the DTO {@code @Digits} constraint already enforces the {@code PIC S9(n)V99} precision.
     */
    private String editSigned9v2(BigDecimal value, String label) {
        if (value == null) {
            return label + " must be supplied.";
        }
        return null;
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD} (Open / Expiry / Reissue dates): delegates to
     * {@link DateValidationService#validateCcyymmdd(String, String)} &mdash; the
     * {@code CALL 'CSUTLDTC'} / {@code CEEDAYS} LE date-validation translation. A {@code null} date
     * renders to an empty {@code yyyyMMdd} string, which the date service reports as the cascade
     * "must be supplied" failure. The service returns a result and never throws, so the message is
     * inspected and surfaced here.
     */
    private String editDate(LocalDate value, String label) {
        DateValidationResult result = dateValidationService.validateCcyymmdd(toCcyymmdd(value), label);
        return result.valid() ? null : result.message();
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD} followed by {@code EDIT-DATE-OF-BIRTH}: delegates to
     * {@link DateValidationService#validateDateOfBirth(String, String)}, which internally runs the full
     * {@code CCYYMMDD} cascade and then the not-in-the-future check, so a single call reproduces the
     * COBOL "date edit + EDIT-DATE-OF-BIRTH" sequence without re-validating.
     */
    private String editDateOfBirth(LocalDate value, String label) {
        DateValidationResult result = dateValidationService.validateDateOfBirth(toCcyymmdd(value), label);
        return result.valid() ? null : result.message();
    }

    /**
     * {@code 1245-EDIT-NUM-REQD} (length 3) followed by {@code 1275-EDIT-FICO-SCORE}: the score must be
     * a 3-digit non-zero number and then fall in the inclusive range 300..850
     * ({@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}).
     */
    private String editFicoScore(String value, String label) {
        String numMsg = editNumReqd(value, label, 3);
        if (numMsg != null) {
            return numMsg;
        }
        int score = Integer.parseInt(value.trim());
        if (score < 300 || score > 850) {
            return label + ": should be between 300 and 850";
        }
        return null;
    }

    /**
     * {@code 1260-EDIT-US-PHONE-NUM}: the phone is OPTIONAL &mdash; when all three components (area,
     * prefix, line) are blank the field is valid. Otherwise each component is edited in order (area,
     * then prefix, then line) and the first failure's message is returned. The single DTO phone string
     * is decomposed positionally into area(3)/prefix(3)/line(4), mirroring the COBOL redefinition of the
     * {@code (999)999-9999} field.
     *
     * @param phone the DTO phone string (for example {@code (123)456-7890})
     * @param label the field label ("Phone Number 1" / "Phone Number 2")
     * @return the first failing message, or {@code null} when valid (including the blank/optional case)
     */
    private String editUsPhone(String phone, String label) {
        String[] parts = splitPhone(phone);
        String area = parts[0];
        String prefix = parts[1];
        String line = parts[2];

        // Not mandatory to enter a phone number: all parts blank/low-values -> valid.
        if (area.isEmpty() && prefix.isEmpty() && line.isEmpty()) {
            return null;
        }

        // EDIT-AREA-CODE.
        if (area.isEmpty()) {
            return label + ": Area code must be supplied.";
        }
        if (area.length() != 3 || !isAllDigits(area)) {
            return label + ": Area code must be A 3 digit number.";
        }
        if (isAllZeros(area)) {
            return label + ": Area code cannot be zero";
        }
        // CSLKPCDY VALID-GENERAL-PURP-CODE lookup -> ValidationLookupService.isValidAreaCode.
        if (!validationLookupService.isValidAreaCode(area)) {
            return label + ": Not valid North America general purpose area code";
        }

        // EDIT-US-PHONE-PREFIX.
        if (prefix.isEmpty()) {
            return label + ": Prefix code must be supplied.";
        }
        if (prefix.length() != 3 || !isAllDigits(prefix)) {
            return label + ": Prefix code must be A 3 digit number.";
        }
        if (isAllZeros(prefix)) {
            return label + ": Prefix code cannot be zero";
        }

        // EDIT-US-PHONE-LINENUM.
        if (line.isEmpty()) {
            return label + ": Line number code must be supplied.";
        }
        if (line.length() != 4 || !isAllDigits(line)) {
            return label + ": Line number code must be A 4 digit number.";
        }
        if (isAllZeros(line)) {
            return label + ": Line number code cannot be zero";
        }
        return null;
    }

    /**
     * {@code 1265-EDIT-US-SSN}: validates the three SSN components in order &mdash; part 1 (3 digits)
     * via {@code 1245-EDIT-NUM-REQD} then the {@code INVALID-SSN-PART1} rule (must not be {@code 000},
     * {@code 666} or {@code 900}..{@code 999}); part 2 (2 digits) and part 3 (4 digits) via
     * {@code 1245-EDIT-NUM-REQD}. The first failure's message is returned. Each component carries its own
     * COBOL label ({@code "SSN: First 3 chars"}, {@code "SSN 4th & 5th chars"}, {@code "SSN Last 4 chars"}).
     *
     * @param ssn the DTO SSN string ({@code XXX-XX-XXXX})
     * @return the first failing message, or {@code null} when valid
     */
    private String editUsSsn(String ssn) {
        String[] parts = splitSsn(ssn);
        String part1 = parts[0];
        String part2 = parts[1];
        String part3 = parts[2];

        // Part 1 - 3 digits via 1245-EDIT-NUM-REQD.
        String p1Msg = editNumReqd(part1, "SSN: First 3 chars", 3);
        if (p1Msg != null) {
            return p1Msg;
        }
        // Part 1 - INVALID-SSN-PART1 (88-level VALUES 0, 666, 900 THRU 999). 000 was already rejected by
        // 1245 "must not be zero" above, matching the COBOL paragraph order.
        int p1 = Integer.parseInt(part1);
        if (p1 == 0 || p1 == 666 || (p1 >= 900 && p1 <= 999)) {
            return "SSN: First 3 chars: should not be 000, 666, or between 900 and 999";
        }
        // Part 2 - 2 digits via 1245-EDIT-NUM-REQD.
        String p2Msg = editNumReqd(part2, "SSN 4th & 5th chars", 2);
        if (p2Msg != null) {
            return p2Msg;
        }
        // Part 3 - 4 digits via 1245-EDIT-NUM-REQD.
        String p3Msg = editNumReqd(part3, "SSN Last 4 chars", 4);
        if (p3Msg != null) {
            return p3Msg;
        }
        return null;
    }

    // =============================================================================================
    // PHASE 3 - change detection (1205-COMPARE-OLD-NEW).
    // =============================================================================================

    /**
     * Reproduces {@code 1205-COMPARE-OLD-NEW}: returns {@code true} when any submitted value differs
     * from the corresponding fetched ("old") value, and {@code false} when every value is unchanged
     * ({@code NO-CHANGES-DETECTED}).
     *
     * <p>The comparison semantics match the COBOL exactly: monetary amounts are compared with
     * {@link BigDecimal#compareTo(BigDecimal)} (scale-insensitive &mdash; NEVER {@code equals}); dates
     * are compared as {@link LocalDate}; the active-status flag and the trimmed text fields are compared
     * case-insensitively ({@code FUNCTION UPPER-CASE}/{@code FUNCTION TRIM}); SSN and phone are compared
     * on their digits-only forms and FICO on its numeric form.</p>
     *
     * @param req     the submitted values
     * @param account the fetched account ("old image")
     * @param cust    the fetched customer ("old image")
     * @return {@code true} if anything changed, {@code false} if nothing changed
     */
    private boolean hasChanges(AccountDto req, Account account, Customer cust) {
        // ---- Account fields (1205 account-compare block) ----
        if (!eqTextCaseless(req.getAccountStatus(), account.getAcctActiveStatus())) {
            return true;
        }
        if (!eqMoney(req.getCreditLimit(), account.getAcctCreditLimit())) {
            return true;
        }
        if (!eqMoney(req.getCashCreditLimit(), account.getAcctCashCreditLimit())) {
            return true;
        }
        if (!eqMoney(req.getCurrentBalance(), account.getAcctCurrBal())) {
            return true;
        }
        if (!eqMoney(req.getCurrentCycleCredit(), account.getAcctCurrCycCredit())) {
            return true;
        }
        if (!eqMoney(req.getCurrentCycleDebit(), account.getAcctCurrCycDebit())) {
            return true;
        }
        if (!Objects.equals(req.getOpenDate(), account.getAcctOpenDate())) {
            return true;
        }
        if (!Objects.equals(req.getExpirationDate(), account.getAcctExpirationDate())) {
            return true;
        }
        if (!Objects.equals(req.getReissueDate(), account.getAcctReissueDate())) {
            return true;
        }
        if (!eqTextCaseless(req.getAccountGroupId(), account.getAcctGroupId())) {
            return true;
        }

        // ---- Customer fields (1205 customer-compare block) ----
        if (!eqTextCaseless(req.getFirstName(), cust.getCustFirstName())) {
            return true;
        }
        if (!eqTextCaseless(req.getMiddleName(), cust.getCustMiddleName())) {
            return true;
        }
        if (!eqTextCaseless(req.getLastName(), cust.getCustLastName())) {
            return true;
        }
        if (!eqTextCaseless(req.getAddressLine1(), cust.getCustAddrLine1())) {
            return true;
        }
        if (!eqTextCaseless(req.getAddressLine2(), cust.getCustAddrLine2())) {
            return true;
        }
        // City (DTO) maps to CUST-ADDR-LINE-3.
        if (!eqTextCaseless(req.getCity(), cust.getCustAddrLine3())) {
            return true;
        }
        if (!eqTextCaseless(req.getState(), cust.getCustAddrStateCd())) {
            return true;
        }
        if (!eqTextCaseless(req.getCountryCode(), cust.getCustAddrCountryCd())) {
            return true;
        }
        if (!eqTextCaseless(req.getZipCode(), cust.getCustAddrZip())) {
            return true;
        }
        if (!eqDigits(req.getPhoneNumber1(), cust.getCustPhoneNum1())) {
            return true;
        }
        if (!eqDigits(req.getPhoneNumber2(), cust.getCustPhoneNum2())) {
            return true;
        }
        String oldSsn = cust.getCustSsn() == null ? null : String.format("%09d", cust.getCustSsn());
        if (!eqDigits(req.getSsn(), oldSsn)) {
            return true;
        }
        if (!eqTextCaseless(req.getGovernmentIssuedId(), cust.getCustGovtIssuedId())) {
            return true;
        }
        if (!Objects.equals(req.getDateOfBirth(), cust.getCustDobYyyyMmDd())) {
            return true;
        }
        if (!eqTextCaseless(req.getEftAccountId(), cust.getCustEftAccountId())) {
            return true;
        }
        if (!eqTextCaseless(req.getPrimaryCardHolderIndicator(), cust.getCustPriCardHolderInd())) {
            return true;
        }
        String oldFico = cust.getCustFicoCreditScore() == null
                ? null : String.valueOf(cust.getCustFicoCreditScore());
        if (!eqTextCaseless(req.getFicoScore(), oldFico)) {
            return true;
        }
        return false;
    }

    // =============================================================================================
    // PHASE 4 - apply validated values to the managed entities (9600-WRITE-PROCESSING field moves).
    // =============================================================================================

    /**
     * Applies the validated account fields to the managed {@link Account} (the
     * {@code 9600-WRITE-PROCESSING} account moves). Dates map {@link LocalDate}-to-{@link LocalDate}
     * (the entity stores {@code DATE} columns, so the COBOL year/month/day {@code STRING} recomposition
     * is unnecessary); money is normalized to scale&nbsp;2 (AAP&nbsp;&sect;0.7.3). {@code ACCT-ADDR-ZIP}
     * is not part of the COACTUP screen and is intentionally left unchanged.
     */
    private void applyToAccount(AccountDto req, Account account) {
        account.setAcctActiveStatus(trimToNull(req.getAccountStatus()));
        account.setAcctCreditLimit(scale2(req.getCreditLimit()));
        account.setAcctCashCreditLimit(scale2(req.getCashCreditLimit()));
        account.setAcctCurrBal(scale2(req.getCurrentBalance()));
        account.setAcctCurrCycCredit(scale2(req.getCurrentCycleCredit()));
        account.setAcctCurrCycDebit(scale2(req.getCurrentCycleDebit()));
        account.setAcctOpenDate(req.getOpenDate());
        account.setAcctExpirationDate(req.getExpirationDate());
        account.setAcctReissueDate(req.getReissueDate());
        account.setAcctGroupId(trimToNull(req.getAccountGroupId()));
    }

    /**
     * Applies the validated customer fields to the managed {@link Customer} (the
     * {@code 9600-WRITE-PROCESSING} customer moves). SSN is stored as a 9-digit {@link Long} (dashes
     * stripped &mdash; the inverse of the view's dash formatting); FICO as an {@link Integer}; the two
     * phones in canonical {@code (999)999-9999} text; City maps to {@code CUST-ADDR-LINE-3}.
     */
    private void applyToCustomer(AccountDto req, Customer cust) {
        cust.setCustFirstName(trimToNull(req.getFirstName()));
        cust.setCustMiddleName(trimToNull(req.getMiddleName()));
        cust.setCustLastName(trimToNull(req.getLastName()));
        cust.setCustAddrLine1(trimToNull(req.getAddressLine1()));
        cust.setCustAddrLine2(trimToNull(req.getAddressLine2()));
        cust.setCustAddrLine3(trimToNull(req.getCity()));
        cust.setCustAddrStateCd(trimToNull(req.getState()));
        cust.setCustAddrCountryCd(trimToNull(req.getCountryCode()));
        cust.setCustAddrZip(trimToNull(req.getZipCode()));
        cust.setCustPhoneNum1(composePhone(req.getPhoneNumber1()));
        cust.setCustPhoneNum2(composePhone(req.getPhoneNumber2()));
        cust.setCustSsn(parseSsn(req.getSsn()));
        cust.setCustGovtIssuedId(trimToNull(req.getGovernmentIssuedId()));
        cust.setCustDobYyyyMmDd(req.getDateOfBirth());
        cust.setCustEftAccountId(trimToNull(req.getEftAccountId()));
        cust.setCustPriCardHolderInd(trimToNull(req.getPrimaryCardHolderIndicator()));
        cust.setCustFicoCreditScore(parseFico(req.getFicoScore()));
    }

    /**
     * Re-assembles the committed account+customer into an {@link AccountDto} (mirroring {@code COACTUPC}
     * redisplaying the stored values). SSN is dash-formatted, FICO is rendered as text, dates are
     * carried as {@link LocalDate} and money keeps scale&nbsp;2; the ids are zero-padded to their COBOL
     * picture widths.
     */
    private AccountDto assembleDto(Account account, Customer cust) {
        AccountDto dto = new AccountDto();
        dto.setAccountId(account.getAcctId() == null ? null : padId(account.getAcctId(), ACCOUNT_ID_LENGTH));
        dto.setAccountStatus(account.getAcctActiveStatus());
        dto.setOpenDate(account.getAcctOpenDate());
        dto.setCreditLimit(account.getAcctCreditLimit());
        dto.setExpirationDate(account.getAcctExpirationDate());
        dto.setCashCreditLimit(account.getAcctCashCreditLimit());
        dto.setReissueDate(account.getAcctReissueDate());
        dto.setCurrentBalance(account.getAcctCurrBal());
        dto.setCurrentCycleCredit(account.getAcctCurrCycCredit());
        dto.setCurrentCycleDebit(account.getAcctCurrCycDebit());
        dto.setAccountGroupId(account.getAcctGroupId());

        dto.setCustomerId(cust.getCustId() == null ? null : padId(cust.getCustId(), CUSTOMER_ID_LENGTH));
        dto.setFirstName(cust.getCustFirstName());
        dto.setMiddleName(cust.getCustMiddleName());
        dto.setLastName(cust.getCustLastName());
        dto.setAddressLine1(cust.getCustAddrLine1());
        dto.setAddressLine2(cust.getCustAddrLine2());
        dto.setCity(cust.getCustAddrLine3());
        dto.setState(cust.getCustAddrStateCd());
        dto.setZipCode(cust.getCustAddrZip());
        dto.setCountryCode(cust.getCustAddrCountryCd());
        dto.setPhoneNumber1(cust.getCustPhoneNum1());
        dto.setPhoneNumber2(cust.getCustPhoneNum2());
        dto.setSsn(formatSsn(cust.getCustSsn()));
        dto.setGovernmentIssuedId(cust.getCustGovtIssuedId());
        dto.setDateOfBirth(cust.getCustDobYyyyMmDd());
        dto.setEftAccountId(cust.getCustEftAccountId());
        dto.setPrimaryCardHolderIndicator(cust.getCustPriCardHolderInd());
        dto.setFicoScore(cust.getCustFicoCreditScore() == null
                ? null : String.valueOf(cust.getCustFicoCreditScore()));
        // Carry the post-write entity @Version so the redisplayed form holds the new optimistic-lock
        // token for any subsequent edit (COACTUPC redisplay parity; AAP §0.7.5).
        dto.setVersion(account.getVersion());
        return dto;
    }

    // =============================================================================================
    // Small utility helpers: blank/numeric tests, component (de)composition, formatting, comparison.
    // =============================================================================================

    /** Adds {@code message} to {@code errors} when it is non-null (the field failed its edit). */
    private static void addIfPresent(List<String> errors, String message) {
        if (message != null) {
            errors.add(message);
        }
    }

    /** COBOL "not supplied" test: {@code null}, or trims to empty (SPACES / LOW-VALUES / length 0). */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** {@code 1220-EDIT-YESNO} "not supplied" test: blank, or all ASCII zeros. */
    private static boolean isBlankOrZeros(String value) {
        return isBlank(value) || isAllZeros(value.trim());
    }

    /** {@code true} when every character is an ASCII digit (COBOL {@code IS NUMERIC} on an unsigned field). */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code true} when {@code value} is non-empty and every character is {@code '0'} ({@code NUMVAL = 0}). */
    private static boolean isAllZeros(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code true} when {@code value} contains only ASCII letters and spaces &mdash; the result of the
     * COBOL {@code INSPECT CONVERTING} of A-Z/a-z to spaces followed by the trim-length-0 residual test.
     */
    private static boolean isAlphaSpaces(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return true;
    }

    /** Trims to {@code null} (an all-spaces field becomes {@code null} rather than {@code ""}). */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trims to {@code ""} (for lookups/comparisons that must not receive {@code null}). */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Renders a {@link LocalDate} as the 8-character {@code yyyyMMdd} the date service expects; null -&gt; "". */
    private static String toCcyymmdd(LocalDate date) {
        return date == null ? "" : date.format(CCYYMMDD);
    }

    /**
     * Normalizes a money amount to scale&nbsp;2 (AAP&nbsp;&sect;0.7.3). The DTO {@code @Digits} constraint
     * already limits the input to two fraction digits, so this only fixes the stored scale and does not
     * change the value; null stays null.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Zero-pads a numeric id to the given COBOL picture width (for example {@code ACCT-ID PIC 9(11)}). */
    private static String padId(long id, int width) {
        return String.format("%0" + width + "d", id);
    }

    /**
     * Decomposes a DTO phone string into {@code {area, prefix, line}} by extracting its digits
     * positionally (3 + 3 + 4), mirroring the COBOL redefinition of the {@code (999)999-9999} field.
     * A blank string yields three empty components (the optional/blank case).
     */
    private static String[] splitPhone(String phone) {
        String digits = digitsOnly(phone);
        return new String[] {slice(digits, 0, 3), slice(digits, 3, 6), slice(digits, 6, 10)};
    }

    /**
     * Re-composes a validated phone into the canonical {@code (999)999-9999} storage form; a
     * blank/optional phone yields {@code null}.
     */
    private static String composePhone(String phone) {
        String[] parts = splitPhone(phone);
        if (parts[0].isEmpty() && parts[1].isEmpty() && parts[2].isEmpty()) {
            return null;
        }
        return "(" + parts[0] + ")" + parts[1] + "-" + parts[2];
    }

    /**
     * Decomposes a DTO SSN string ({@code XXX-XX-XXXX}) into {@code {part1(3), part2(2), part3(4)}} by
     * extracting its digits positionally, mirroring the COBOL {@code WS-EDIT-US-SSN} redefinition.
     */
    private static String[] splitSsn(String ssn) {
        String digits = digitsOnly(ssn);
        return new String[] {slice(digits, 0, 3), slice(digits, 3, 5), slice(digits, 5, 9)};
    }

    /** Strips a validated SSN to its 9 raw digits and parses to {@link Long} (the storage form); blank -&gt; null. */
    private static Long parseSsn(String ssn) {
        String digits = digitsOnly(ssn);
        return digits.isEmpty() ? null : Long.valueOf(digits);
    }

    /** Formats a stored 9-digit SSN {@link Long} back to {@code XXX-XX-XXXX}; null stays null. */
    private static String formatSsn(Long ssn) {
        if (ssn == null) {
            return null;
        }
        String digits = String.format("%09d", ssn);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
    }

    /** Parses a validated FICO score string to {@link Integer}; null/blank stays null. */
    private static Integer parseFico(String fico) {
        return isBlank(fico) ? null : Integer.valueOf(fico.trim());
    }

    /** Returns only the ASCII digits of {@code value} (null -&gt; ""). */
    private static String digitsOnly(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Safe substring of {@code [from, to)} clamped to the string length (returns "" when out of range). */
    private static String slice(String value, int from, int to) {
        if (value == null || from >= value.length()) {
            return "";
        }
        return value.substring(from, Math.min(to, value.length()));
    }

    /** Money equality with COBOL semantics: scale-insensitive {@code compareTo} (NEVER {@code equals}), null-safe. */
    private static boolean eqMoney(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    /** Text equality with COBOL {@code FUNCTION TRIM} + {@code FUNCTION UPPER-CASE} semantics (null -&gt; blank). */
    private static boolean eqTextCaseless(String a, String b) {
        return trimToEmpty(a).equalsIgnoreCase(trimToEmpty(b));
    }

    /** Digit-sequence equality (used for SSN and phone change detection); null/non-digits -&gt; "". */
    private static boolean eqDigits(String a, String b) {
        return digitsOnly(a).equals(digitsOnly(b));
    }

}
