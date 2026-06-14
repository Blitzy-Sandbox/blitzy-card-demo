package com.cardemo.service.account;

import java.util.List;

import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;

/**
 * Account-inquiry service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COACTVWC.cbl}</strong> (CICS transaction {@code CAVW}, BMS mapset
 * {@code COACTVW}). It resolves a single account by id and returns the joined account + customer view
 * that the legacy 3270 screen displayed.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COACTVWC}'s observable behavior <em>exactly</em>: the same account-id
 * edit (and the same rejection messages), the same three-step keyed read performed in the same order,
 * the same not-found semantics at each step, and the same field-by-field screen assembly. Per the
 * Minimal Change Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology
 * transition. The COBOL is read-only reference material at the frozen baseline commit SHA
 * {@code 27d6c6f} and is <strong>never</strong> copied into this repository &mdash; only its behavior is
 * reproduced.</p>
 *
 * <p>This is a pure inquiry: {@code COACTVWC} issues only CICS {@code READ} operations and never a
 * {@code REWRITE}, so {@link #viewAccount(String)} is annotated
 * {@link Transactional @Transactional(readOnly = true)} and performs no {@code save}/{@code saveAll}.</p>
 *
 * <h2>The COBOL read chain ({@code 9000-READ-ACCT})</h2>
 * <p>{@code COACTVWC} resolves the account through three keyed reads, in this exact order
 * (AAP &sect;0.4.1 L622 "ACCTDAT+CUSTDAT+CXACAIX multi-read"; &sect;0.6.2):</p>
 * <ol>
 *   <li><strong>{@code 9200-GETCARDXREF-BYACCT}</strong> &mdash; reads {@code CARDXREF} through the
 *       {@code CXACAIX} alternate-index path keyed on the account id, yielding the owning customer id.
 *       Absent &rarr; "not found in cross-reference file".</li>
 *   <li><strong>{@code 9300-GETACCTDATA-BYACCT}</strong> &mdash; reads the {@code ACCTDAT} account master
 *       keyed on the account id. Absent &rarr; "not found in account master".</li>
 *   <li><strong>{@code 9400-GETCUSTDATA-BYCUST}</strong> &mdash; reads the {@code CUSTDAT} customer master
 *       keyed on the customer id obtained from the cross-reference (not from the account). Absent &rarr;
 *       "not found in customer master".</li>
 * </ol>
 * <p>The order is preserved and the reads are never reordered or parallelized (control-flow
 * preservation, AAP &sect;0.7.4).</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM {@code CXACAIX} alternate-index {@code READ} &rarr; derived query.</strong> The
 *       account&rarr;cards alternate-index lookup becomes
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}. Because that index was
 *       {@code NONUNIQUEKEY} the query returns a {@link List}; the first element mirrors the single
 *       keyed {@code READ} the COBOL performed, and an empty list is the {@code NOTFND} condition.</li>
 *   <li><strong>VSAM KSDS keyed {@code READ} &rarr; JPA {@code findById}.</strong> The {@code ACCTDAT}
 *       and {@code CUSTDAT} keyed reads become {@link AccountRepository#findById(Object)} and
 *       {@link CustomerRepository#findById(Object)}.</li>
 *   <li><strong>{@code FILE STATUS '23'} / {@code INVALID KEY} / {@code NOTFND} &rarr;
 *       {@link RecordNotFoundException}</strong> (HTTP&nbsp;404) at each read step.</li>
 *   <li><strong>{@code 2210-EDIT-ACCOUNT} input edit &rarr; {@link ValidationException}</strong>
 *       (HTTP&nbsp;400) for a blank, non-numeric or zero account id.</li>
 * </ul>
 *
 * <h2>Scope &mdash; pure business logic</h2>
 * <p>The CICS pseudo-conversational plumbing of {@code COACTVWC} &mdash; the {@code COCOM01Y} COMMAREA,
 * {@code SEND MAP}/{@code RECEIVE MAP}, {@code RETURN TRANSID}, PF-key handling and {@code 0000-MAIN}
 * dispatch &mdash; lives in the controller layer ({@code AccountController}), <strong>not</strong> here.
 * This service is stateless business logic over the data layer.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Every monetary amount is carried as a {@link java.math.BigDecimal} end to end (entity getter
 * &rarr; DTO setter); there is no {@code float}/{@code double} anywhere in this class, and no numeric
 * comparison is performed (so the scale-sensitive {@code BigDecimal.equals} pitfall does not arise).</p>
 *
 * @see AccountDto
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardCrossReferenceRepository
 */
@Service
public class AccountViewService {

    /**
     * Display width of the account id, matching {@code ACCT-ID PIC 9(11)} and the view map field
     * {@code ACCTSIDO PIC X(11)}: the id is rendered zero-padded to eleven digits.
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Display width of the customer id, matching {@code CUST-ID PIC 9(09)} and the view map field
     * {@code ACSTNUMO PIC X(9)}: the id is rendered zero-padded to nine digits.
     */
    private static final int CUSTOMER_ID_LENGTH = 9;

    /**
     * Display width of the ZIP code on the view screen. The map field {@code ACSZIPC} is
     * {@code PIC X(5)}, so {@code COACTVWC}'s {@code MOVE CUST-ADDR-ZIP TO ACSZIPCO} truncates the
     * stored 10-character {@code CUST-ADDR-ZIP} to its leftmost five characters.
     */
    private static final int ZIP_DISPLAY_LENGTH = 5;

    /** Entity label for the cross-reference not-found case (COBOL {@code DID-NOT-FIND-ACCT-IN-CARDXREF}). */
    private static final String ENTITY_CARD_XREF = "CardCrossReference";

    /** Entity label for the account not-found case (COBOL {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}). */
    private static final String ENTITY_ACCOUNT = "Account";

    /** Entity label for the customer not-found case (COBOL {@code DID-NOT-FIND-CUST-IN-CUSTDAT}). */
    private static final String ENTITY_CUSTOMER = "Customer";

    /** Account master repository &mdash; the {@code ACCTDAT} VSAM KSDS replacement. */
    private final AccountRepository accountRepository;

    /** Customer master repository &mdash; the {@code CUSTDAT} VSAM KSDS replacement. */
    private final CustomerRepository customerRepository;

    /** Card cross-reference repository &mdash; the {@code CARDXREF}/{@code CXACAIX} replacement. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Constructs the service with its required collaborators. Constructor injection (rather than field
     * {@code @Autowired}) keeps the dependencies {@code final} and the service trivially testable.
     *
     * @param accountRepository            the account master repository ({@code ACCTDAT})
     * @param customerRepository           the customer master repository ({@code CUSTDAT})
     * @param cardCrossReferenceRepository the card cross-reference repository ({@code CARDXREF}/{@code CXACAIX})
     */
    public AccountViewService(AccountRepository accountRepository,
                              CustomerRepository customerRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Resolves and returns the full account-view payload for the supplied account id, reproducing the
     * end-to-end behavior of {@code COACTVWC} (edit &rarr; three-step read &rarr; screen assembly).
     *
     * <p>The account id is accepted as a {@link String} (mirroring the BMS {@code ACCTSID} input field,
     * {@code PIC X(11)}) so the COBOL "must be numeric / non-zero" edits can be reproduced faithfully
     * before any read is attempted.</p>
     *
     * @param accountId the requested account id (the {@code ACCTSID} screen filter)
     * @return a fully-populated {@link AccountDto} joining the account and its owning customer
     * @throws ValidationException     if {@code accountId} is blank, non-numeric or zero
     *                                 ({@code 2210-EDIT-ACCOUNT})
     * @throws RecordNotFoundException if no cross-reference, account or customer record exists for the id
     *                                 ({@code 9200}/{@code 9300}/{@code 9400} {@code NOTFND})
     */
    @Observed(name = "carddemo.account.view", contextualName = "account.view")
    @Transactional(readOnly = true)
    public AccountDto viewAccount(String accountId) {
        // PHASE 1 - reproduce COACTVWC 2210-EDIT-ACCOUNT: validate the account filter before any read.
        final Long acctId = validateAccountKey(accountId);

        // PHASE 2 - reproduce COACTVWC 9000-READ-ACCT: keyed reads in the exact order xref -> account ->
        // customer (AAP 0.7.4 control-flow preservation). Each step throws on its own NOTFND condition.

        // Step 1 - COACTVWC 9200-GETCARDXREF-BYACCT (reads CARDXREF through the CXACAIX PATH on ACCT-ID).
        // VSAM CXACAIX alternate-index read -> derived query findByXrefAcctId. The alternate index was
        // NONUNIQUEKEY, so the query returns a List; take the first element to mirror the single COBOL
        // READ, and treat an empty list as the NOTFND ("not found in cross-reference file") condition.
        List<CardCrossReference> crossReferences = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (crossReferences.isEmpty()) {
            // Verbatim COACTVWC prompt (app/cbl/COACTVWC.cbl L130); carries no key/PII so it is
            // surfaced to the client by the central advice, preserving the COBOL user-visible text.
            throw new RecordNotFoundException(ENTITY_CARD_XREF, String.valueOf(acctId),
                    "Did not find this account in account card xref file");
        }
        CardCrossReference crossReference = crossReferences.get(0);
        // The customer id is taken from the cross-reference record (XREF-CUST-ID), not from the account.
        final Long customerId = crossReference.getXrefCustId();

        // Step 2 - COACTVWC 9300-GETACCTDATA-BYACCT (keyed read on ACCT-ID).
        // VSAM ACCTDAT keyed read -> JPA findById; NOTFND -> RecordNotFoundException ("account master").
        Account account = accountRepository.findById(acctId)
                // Verbatim COACTVWC prompt (app/cbl/COACTVWC.cbl L132); key-free, so surfaced to client.
                .orElseThrow(() -> new RecordNotFoundException(ENTITY_ACCOUNT, String.valueOf(acctId),
                        "Did not find this account in account master file"));

        // Step 3 - COACTVWC 9400-GETCUSTDATA-BYCUST (keyed read on the CUST-ID resolved from the xref).
        // VSAM CUSTDAT keyed read -> JPA findById; NOTFND -> RecordNotFoundException ("customer master").
        Customer customer = customerRepository.findById(customerId)
                // Verbatim COACTVWC prompt (app/cbl/COACTVWC.cbl L134); key-free, so surfaced to client.
                .orElseThrow(() -> new RecordNotFoundException(ENTITY_CUSTOMER, String.valueOf(customerId),
                        "Did not find associated customer in master file"));

        // PHASE 3 - reproduce COACTVWC 1200-SETUP-SCREEN-VARS: map account + customer onto the DTO.
        return assembleDto(account, customer);
    }

    /**
     * Validates the account filter and returns it parsed to a {@link Long}, reproducing
     * {@code COACTVWC}'s {@code 2210-EDIT-ACCOUNT} paragraph:
     * <ol>
     *   <li>a blank key (COBOL {@code SPACES}/{@code LOW-VALUES}) is the "must be supplied" prompt;</li>
     *   <li>a non-numeric key, or a zero key of any width (COBOL {@code IS NOT NUMERIC} /
     *       {@code EQUAL ZEROES}), is the "11 digit Non-Zero Number" rejection;</li>
     *   <li>otherwise the key is parsed to a {@link Long} only <em>after</em> the numeric edit passes.</li>
     * </ol>
     * <p>The rejection messages are the <strong>verbatim {@code COACTVWC} literals</strong>
     * &mdash; {@code "Account number not provided"} ({@code COACTVWC.cbl} L122) and
     * {@code "Account number must be a non zero 11 digit number"} ({@code COACTVWC.cbl}
     * L126/L128) &mdash; reproduced exactly for behavioral parity (AAP&nbsp;&sect;0.7.2). They
     * deliberately <em>differ</em> from the sibling {@code AccountUpdateService} ({@code COACTUPC},
     * which uses {@code "... must be supplied."} / {@code "... must be a 11 digit Non-Zero Number"}),
     * because each service must mirror its own source program's prompts. Both are thrown through the
     * two-argument {@link ValidationException#ValidationException(String, String)} so the centralized
     * web advice surfaces them as per-field validation errors rather than sanitizing them away. The
     * eleven-digit width itself is enforced upstream by the controller's {@code @Pattern("\\d{1,11}")}
     * (matching the BMS {@code ACCTSID PIC X(11)} field), so no extra length edit is added here
     * (Minimal Change Clause, AAP&nbsp;&sect;0.7.1).</p>
     *
     * @param accountId the raw account filter from the request
     * @return the validated account id as a {@link Long}
     * @throws ValidationException if {@code accountId} is blank, non-numeric or zero
     */
    private Long validateAccountKey(String accountId) {
        // 2210-EDIT-ACCOUNT: "Not supplied" (CC-ACCT-ID EQUAL LOW-VALUES OR SPACES). Verbatim
        // COACTVWC literal (app/cbl/COACTVWC.cbl L122); two-arg form surfaces it as a field error.
        if (isBlank(accountId)) {
            throw new ValidationException("Account Number", "Account number not provided");
        }
        // 2210-EDIT-ACCOUNT: "Not numeric" / zero (CC-ACCT-ID IS NOT NUMERIC OR EQUAL ZEROES).
        // Verbatim COACTVWC literal (app/cbl/COACTVWC.cbl L126/L128); two-arg form surfaces it as a
        // field error (previously a single-arg top-level message that the advice sanitized away).
        String trimmed = accountId.trim();
        if (!isAllDigits(trimmed) || isAllZeros(trimmed)) {
            throw new ValidationException("Account Number", "Account number must be a non zero 11 digit number");
        }
        // Numeric edit passed: parse to the keyed-read type.
        return Long.parseLong(trimmed);
    }

    /**
     * Re-assembles the account and customer records into an {@link AccountDto}, reproducing
     * {@code COACTVWC}'s {@code 1200-SETUP-SCREEN-VARS} field-by-field screen population. Every DTO field
     * that the view screen populates is set; fields the screen does not show are left {@code null}.
     *
     * <p>Mapping notes (each a faithful reproduction of the COBOL {@code MOVE}s):</p>
     * <ul>
     *   <li><strong>Dates.</strong> {@code ACCT-OPEN-DATE}/{@code ACCT-EXPIRAION-DATE}/
     *       {@code ACCT-REISSUE-DATE} and {@code CUST-DOB-YYYY-MM-DD} are already
     *       {@link java.time.LocalDate} on the entities (the {@code X(10)} text&rarr;{@code LocalDate}
     *       conversion, replacing {@code CEEDAYS}-era text handling, happens at the persistence/Flyway
     *       boundary), so they are carried through directly &mdash; no text parsing is performed here.</li>
     *   <li><strong>Money.</strong> The five {@code S9(10)V99} amounts are carried as
     *       {@link java.math.BigDecimal} at their stored scale&nbsp;2 (AAP &sect;0.7.3); they are never
     *       converted to primitives.</li>
     *   <li><strong>Ids.</strong> The account and customer ids are zero-padded to their COBOL picture
     *       widths ({@code PIC 9(11)} / {@code PIC 9(09)}), matching the {@code ACCTSIDO}/{@code ACSTNUMO}
     *       display fields.</li>
     *   <li><strong>SSN.</strong> The stored 9-digit {@code CUST-SSN} is dash-formatted to
     *       {@code XXX-XX-XXXX}, reproducing the COBOL {@code STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-'
     *       CUST-SSN(6:4)} edit.</li>
     *   <li><strong>City.</strong> {@code CUST-ADDR-LINE-3} maps to the DTO {@code city} field, exactly
     *       as {@code COACTVWC} moved it to {@code ACSCITYO}.</li>
     *   <li><strong>ZIP.</strong> {@code CUST-ADDR-ZIP} is truncated to the view screen's five-character
     *       {@code ACSZIPC} field (see {@link #truncateZip(String)}).</li>
     *   <li><strong>FICO.</strong> The numeric {@code CUST-FICO-CREDIT-SCORE} is rendered as text, as the
     *       screen displayed it.</li>
     * </ul>
     *
     * @param account  the resolved account master record
     * @param customer the resolved customer master record
     * @return the populated {@link AccountDto}
     */
    private AccountDto assembleDto(Account account, Customer customer) {
        AccountDto dto = new AccountDto();

        // --- Account fields (1200-SETUP-SCREEN-VARS, FOUND-ACCT-IN-MASTER block) ---
        // ACCT-ID -> ACCTSIDO PIC X(11): zero-padded to 11 digits.
        dto.setAccountId(account.getAcctId() == null
                ? null : padId(account.getAcctId(), ACCOUNT_ID_LENGTH));
        dto.setAccountStatus(account.getAcctActiveStatus());           // ACCT-ACTIVE-STATUS -> ACSTTUSO
        dto.setCurrentBalance(account.getAcctCurrBal());               // ACCT-CURR-BAL -> ACURBALO (BigDecimal)
        dto.setCreditLimit(account.getAcctCreditLimit());             // ACCT-CREDIT-LIMIT -> ACRDLIMO (BigDecimal)
        dto.setCashCreditLimit(account.getAcctCashCreditLimit());     // ACCT-CASH-CREDIT-LIMIT -> ACSHLIMO
        dto.setCurrentCycleCredit(account.getAcctCurrCycCredit());    // ACCT-CURR-CYC-CREDIT -> ACRCYCRO
        dto.setCurrentCycleDebit(account.getAcctCurrCycDebit());      // ACCT-CURR-CYC-DEBIT -> ACRCYDBO
        // ACCT-*-DATE PIC X(10) text date is already java.time.LocalDate on the entity (text->LocalDate
        // conversion, replacing CEEDAYS-era text handling, happens at the persistence/Flyway boundary),
        // so it is carried through directly here.
        dto.setOpenDate(account.getAcctOpenDate());                  // ACCT-OPEN-DATE -> ADTOPENO
        dto.setExpirationDate(account.getAcctExpirationDate());      // ACCT-EXPIRAION-DATE -> AEXPDTO
        dto.setReissueDate(account.getAcctReissueDate());            // ACCT-REISSUE-DATE -> AREISDTO
        dto.setAccountGroupId(account.getAcctGroupId());             // ACCT-GROUP-ID -> AADDGRPO

        // --- Customer fields (1200-SETUP-SCREEN-VARS, FOUND-CUST-IN-MASTER block) ---
        // CUST-ID -> ACSTNUMO PIC X(9): zero-padded to 9 digits.
        dto.setCustomerId(customer.getCustId() == null
                ? null : padId(customer.getCustId(), CUSTOMER_ID_LENGTH));
        // CUST-SSN (9 digits) -> ACSTSSNO via STRING (1:3)'-'(4:2)'-'(6:4): dash-formatted XXX-XX-XXXX.
        dto.setSsn(formatSsn(customer.getCustSsn()));
        // CUST-FICO-CREDIT-SCORE (numeric) -> ACSTFCOO: displayed as text (null-safe).
        dto.setFicoScore(customer.getCustFicoCreditScore() == null
                ? null : String.valueOf(customer.getCustFicoCreditScore()));
        // CUST-DOB-YYYY-MM-DD is already java.time.LocalDate on the entity: carried through directly.
        dto.setDateOfBirth(customer.getCustDobYyyyMmDd());           // -> ACSTDOBO
        dto.setFirstName(customer.getCustFirstName());               // CUST-FIRST-NAME -> ACSFNAMO
        dto.setMiddleName(customer.getCustMiddleName());             // CUST-MIDDLE-NAME -> ACSMNAMO
        dto.setLastName(customer.getCustLastName());                 // CUST-LAST-NAME -> ACSLNAMO
        dto.setAddressLine1(customer.getCustAddrLine1());            // CUST-ADDR-LINE-1 -> ACSADL1O
        dto.setAddressLine2(customer.getCustAddrLine2());            // CUST-ADDR-LINE-2 -> ACSADL2O
        dto.setCity(customer.getCustAddrLine3());                    // CUST-ADDR-LINE-3 -> ACSCITYO (city)
        dto.setState(customer.getCustAddrStateCd());                 // CUST-ADDR-STATE-CD -> ACSSTTEO
        // CUST-ADDR-ZIP PIC X(10) -> ACSZIPCO PIC X(5): the view screen truncates the zip to 5 chars.
        dto.setZipCode(truncateZip(customer.getCustAddrZip()));
        dto.setCountryCode(customer.getCustAddrCountryCd());         // CUST-ADDR-COUNTRY-CD -> ACSCTRYO
        dto.setPhoneNumber1(customer.getCustPhoneNum1());            // CUST-PHONE-NUM-1 -> ACSPHN1O
        dto.setPhoneNumber2(customer.getCustPhoneNum2());            // CUST-PHONE-NUM-2 -> ACSPHN2O
        dto.setGovernmentIssuedId(customer.getCustGovtIssuedId());   // CUST-GOVT-ISSUED-ID -> ACSGOVTO
        dto.setEftAccountId(customer.getCustEftAccountId());         // CUST-EFT-ACCOUNT-ID -> ACSEFTCO
        dto.setPrimaryCardHolderIndicator(customer.getCustPriCardHolderInd()); // CUST-PRI-CARD-HOLDER-IND -> ACSPFLGO
        // Optimistic-locking token: carry the entity @Version so the client can echo it on a
        // subsequent update, enabling COACTUPC-style stale-form detection (AAP §0.7.5).
        dto.setVersion(account.getVersion());
        return dto;
    }

    // =============================================================================================
    // Small utility helpers: blank/numeric tests, id padding, SSN formatting, zip truncation.
    // These mirror the equivalent helpers in the sibling AccountUpdateService so both account
    // screens normalize identical inputs identically.
    // =============================================================================================

    /** COBOL "not supplied" test: {@code null}, or trims to empty ({@code SPACES}/{@code LOW-VALUES}/length 0). */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    /** {@code true} when {@code value} is non-empty and every character is {@code '0'} (COBOL {@code EQUAL ZEROES}). */
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

    /** Zero-pads a numeric id to the given COBOL picture width (for example {@code ACCT-ID PIC 9(11)}). */
    private static String padId(long id, int width) {
        return String.format("%0" + width + "d", id);
    }

    /**
     * Formats a stored 9-digit SSN {@link Long} back to {@code XXX-XX-XXXX}, reproducing the
     * {@code COACTVWC} {@code STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)} edit. The value is
     * zero-padded to nine digits first (so {@code CUST-SSN PIC 9(09)} leading zeros are preserved), and
     * {@code null} stays {@code null}.
     *
     * @param ssn the stored SSN (nine-digit numeric), or {@code null}
     * @return the dash-formatted SSN, or {@code null} when {@code ssn} is {@code null}
     */
    private static String formatSsn(Long ssn) {
        if (ssn == null) {
            return null;
        }
        String digits = String.format("%09d", ssn);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
    }

    /**
     * Truncates the stored ZIP code to the view screen's display width, reproducing
     * {@code COACTVWC}'s {@code MOVE CUST-ADDR-ZIP TO ACSZIPCO} where {@code ACSZIPC} is {@code PIC X(5)}:
     * a 10-character {@code CUST-ADDR-ZIP} (for example a {@code ZIP+4} value such as
     * {@code "19852-6716"}) is shown as its leftmost five characters ({@code "19852"}). Values of five or
     * fewer characters are returned unchanged, and {@code null} stays {@code null}.
     *
     * @param zip the stored ZIP code ({@code CUST-ADDR-ZIP}, up to ten characters), or {@code null}
     * @return the first {@value #ZIP_DISPLAY_LENGTH} characters of {@code zip}, or {@code null}
     */
    private static String truncateZip(String zip) {
        if (zip == null) {
            return null;
        }
        return zip.length() <= ZIP_DISPLAY_LENGTH ? zip : zip.substring(0, ZIP_DISPLAY_LENGTH);
    }
}
