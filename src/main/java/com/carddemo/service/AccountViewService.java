package com.carddemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.AccountViewResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Application service for the <strong>Account View</strong> use case (online
 * transaction {@code CAVW}).
 *
 * <p>This {@code @Service} is the idiomatic Spring translation of the legacy
 * COBOL/CICS program {@code COACTVWC} ({@code app/cbl/COACTVWC.cbl}, 941&nbsp;LOC,
 * frozen reference commit SHA {@code 27d6c6f}), whose function is to
 * <em>"View the details of a given account"</em>. It returns a single,
 * denormalized account&nbsp;+&nbsp;customer projection &mdash; the same flattened
 * shape the 3270 BMS panel {@code COACTVW} presented to the mainframe operator.</p>
 *
 * <h2>Why customer resolution navigates the cross-reference</h2>
 * <p>In the mainframe design the {@code ACCOUNT-RECORD} ({@code CVACT01Y}) carries
 * <em>no</em> customer identifier; the linkage between an account and its owning
 * customer lives exclusively in the {@code CARDXREF} VSAM cluster
 * ({@code CVACT03Y}). Consequently {@link Account} likewise exposes no
 * {@code custId}, and this service must resolve the customer id through the
 * cross-reference exactly as {@code COACTVWC} does &mdash; delegating to the
 * shared {@link CrossReferenceService} rather than duplicating the browse.</p>
 *
 * <h2>Control-flow provenance ({@code 9000-READ-ACCT}, ordered)</h2>
 * <p>{@link #viewAccount(Long)} preserves the exact paragraph ordering and
 * not-found semantics of the COBOL {@code 9000-READ-ACCT} driver
 * (SHA {@code 27d6c6f}). Each COBOL {@code GO TO 9000-READ-ACCT-EXIT} guard
 * becomes an early-terminating typed exception, and the branch order is never
 * reordered (AAP&nbsp;&sect;0.8.3):</p>
 * <ol>
 *   <li><strong>{@code 2210-EDIT-ACCOUNT}</strong> &mdash; the account-number
 *       input edit. The {@code 88 SEARCHED-ACCT-ZEROES} /
 *       {@code 88 SEARCHED-ACCT-NOT-NUMERIC} conditions (which share one message)
 *       collapse into a single {@link ValidationException} carrying
 *       {@link #MSG_ACCT_INVALID}. Because the id arrives already typed as a
 *       {@link Long}, the surviving service-layer edit is "supplied and strictly
 *       positive" (non-{@code null}, {@code > 0}).</li>
 *   <li><strong>{@code 9200-GETCARDXREF-BYACCT}</strong> &mdash; the account
 *       alternate-index ({@code CXACAIX}) read of {@code CARDXREF}; on
 *       {@code DFHRESP(NORMAL)} it moves {@code XREF-CUST-ID} into
 *       {@code CDEMO-CUST-ID}, and on {@code DFHRESP(NOTFND)} it raises
 *       {@link #MSG_XREF_NOT_FOUND}. Delegated to
 *       {@link CrossReferenceService#resolveCustomerId(Long)}, whose
 *       {@link ResourceNotFoundException} already carries the identical text.</li>
 *   <li><strong>{@code 9300-GETACCTDATA-BYACCT}</strong> &mdash; the keyed read
 *       of the account master by {@code ACCT-ID}; the {@code DFHRESP(NOTFND)}
 *       branch ({@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT}) becomes a
 *       {@link ResourceNotFoundException} carrying {@link #MSG_ACCT_NOT_FOUND}.</li>
 *   <li><strong>{@code 9400-GETCUSTDATA-BYCUST}</strong> &mdash; the keyed read
 *       of the customer master by the resolved {@code XREF-CUST-ID}; the
 *       {@code DFHRESP(NOTFND)} branch ({@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT})
 *       becomes a {@link ResourceNotFoundException} carrying
 *       {@link #MSG_CUST_NOT_FOUND}.</li>
 * </ol>
 *
 * <h2>Fidelity and security invariants</h2>
 * <ul>
 *   <li><strong>Decimal precision (AAP&nbsp;&sect;0.8.2).</strong> Every monetary
 *       field is passed through as {@link java.math.BigDecimal} unchanged; no
 *       {@code float}/{@code double} is ever introduced. The
 *       {@link AccountViewResponse} canonical constructor independently pins each
 *       money value to scale&nbsp;2.</li>
 *   <li><strong>Screen "city" mapping.</strong> The panel city field
 *       ({@code ACSCITY}) is populated from record address line&nbsp;3, so
 *       {@link Customer#getCustAddrLine3()} maps to the response {@code city}
 *       component.</li>
 *   <li><strong>SSN parity vs. masking.</strong> For behavioral parity with
 *       {@code COACTVW} (which displayed the full {@code CUST-SSN}), this service
 *       renders the numeric {@code CUST-SSN PIC 9(09)} as a nine-digit,
 *       zero-padded {@link String} and applies <em>no</em> masking itself. SSN
 *       masking is a security concern handled &mdash; and logged (decision log
 *       D-023) &mdash; at the DTO boundary by
 *       {@link AccountViewResponse#maskSsn(String)}; masking here would
 *       double-apply and is therefore intentionally omitted.</li>
 *   <li><strong>No PII in logs.</strong> The logger emits only the numeric
 *       account key at {@code DEBUG}; it never logs the SSN, names, address,
 *       date of birth, phone numbers, government id, or FICO score.</li>
 * </ul>
 *
 * <p>The service is read-only, holds no mutable state, and receives its three
 * collaborators through constructor injection, so it is thread-safe and can be
 * unit-tested with mocks without bootstrapping a Spring context.</p>
 */
@Service
public class AccountViewService {

    /**
     * User-facing message emitted when the supplied account number fails the
     * input edit. Transcribed verbatim from the {@code 88 SEARCHED-ACCT-ZEROES}
     * and {@code 88 SEARCHED-ACCT-NOT-NUMERIC} conditions on {@code WS-RETURN-MSG}
     * in {@code COACTVWC} (SHA {@code 27d6c6f}); preserved character-for-character
     * to maintain the external message contract (Gate&nbsp;5).
     */
    public static final String MSG_ACCT_INVALID =
            "Account number must be a non zero 11 digit number";

    /**
     * User-facing message emitted when the account has no card cross-reference
     * row (the migrated {@code 9200-GETCARDXREF-BYACCT} {@code DFHRESP(NOTFND)}
     * branch, {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}). This is the exact text
     * that {@link CrossReferenceService#resolveCustomerId(Long)} already raises;
     * it is declared here so callers and tests can reference the canonical value.
     */
    public static final String MSG_XREF_NOT_FOUND =
            "Did not find this account in account card xref file";

    /**
     * User-facing message emitted when no account exists for the supplied id (the
     * migrated {@code 9300-GETACCTDATA-BYACCT} {@code DFHRESP(NOTFND)} branch,
     * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT}). Transcribed verbatim from
     * {@code COACTVWC} (SHA {@code 27d6c6f}).
     */
    public static final String MSG_ACCT_NOT_FOUND =
            "Did not find this account in account master file";

    /**
     * User-facing message emitted when the resolved customer id has no customer
     * master record (the migrated {@code 9400-GETCUSTDATA-BYCUST}
     * {@code DFHRESP(NOTFND)} branch, {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT}).
     * Transcribed verbatim from {@code COACTVWC} (SHA {@code 27d6c6f}).
     */
    public static final String MSG_CUST_NOT_FOUND =
            "Did not find associated customer in master file";

    /**
     * Left-zero-padded, nine-digit format for the numeric {@code CUST-SSN
     * PIC 9(09)} field, reproducing the fixed-width rendering the legacy screen
     * used before any masking is applied downstream.
     */
    private static final String SSN_FORMAT = "%09d";

    /** SLF4J logger; emits only coarse, non-PII diagnostics (never the SSN or other PII). */
    private static final Logger log = LoggerFactory.getLogger(AccountViewService.class);

    /**
     * Account master repository (migrated {@code ACCTDATA} VSAM KSDS). Final and
     * constructor-injected for immutability and testability.
     */
    private final AccountRepository accountRepository;

    /**
     * Customer master repository (migrated {@code CUSTDATA} VSAM KSDS). Final and
     * constructor-injected for immutability and testability.
     */
    private final CustomerRepository customerRepository;

    /**
     * Shared cross-reference navigator that resolves an account's owning customer
     * id through {@code CARDXREF}, reproducing {@code COACTVWC}'s
     * {@code 9200-GETCARDXREF-BYACCT}. Final and constructor-injected.
     */
    private final CrossReferenceService crossReferenceService;

    /**
     * Creates the service with its three collaborating beans.
     *
     * <p>Constructor injection is used deliberately (no field injection) so the
     * class can be instantiated directly in unit tests with mocks. A single
     * constructor means no {@code @Autowired} annotation is required.</p>
     *
     * @param accountRepository     the account master repository; must not be {@code null}
     * @param customerRepository    the customer master repository; must not be {@code null}
     * @param crossReferenceService the shared card cross-reference service; must not be {@code null}
     */
    public AccountViewService(AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CrossReferenceService crossReferenceService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.crossReferenceService = crossReferenceService;
    }

    /**
     * Returns the flattened account&nbsp;+&nbsp;customer view for the supplied
     * account id, reproducing {@code COACTVWC}'s {@code 9000-READ-ACCT} sequence.
     *
     * <p>Behaviour, in strict COBOL order:</p>
     * <ol>
     *   <li><strong>Input edit</strong> ({@code 2210-EDIT-ACCOUNT}): the account
     *       id must be supplied and strictly positive; otherwise a
     *       {@link ValidationException} with {@link #MSG_ACCT_INVALID} is thrown
     *       (HTTP&nbsp;400).</li>
     *   <li><strong>Cross-reference read</strong> ({@code 9200-GETCARDXREF-BYACCT}):
     *       the owning customer id is resolved via
     *       {@link CrossReferenceService#resolveCustomerId(Long)}. When the account
     *       has no cross-reference row this raises a {@link ResourceNotFoundException}
     *       carrying {@link #MSG_XREF_NOT_FOUND} (HTTP&nbsp;404).</li>
     *   <li><strong>Account read</strong> ({@code 9300-GETACCTDATA-BYACCT}): the
     *       account is loaded by id; a missing row raises a
     *       {@link ResourceNotFoundException} with {@link #MSG_ACCT_NOT_FOUND}
     *       (HTTP&nbsp;404).</li>
     *   <li><strong>Customer read</strong> ({@code 9400-GETCUSTDATA-BYCUST}): the
     *       customer is loaded by the resolved id; a missing row raises a
     *       {@link ResourceNotFoundException} with {@link #MSG_CUST_NOT_FOUND}
     *       (HTTP&nbsp;404).</li>
     *   <li><strong>Projection</strong>: the account and customer are mapped onto
     *       an immutable {@link AccountViewResponse} (see {@link #toResponse}).</li>
     * </ol>
     *
     * <p>The method is transactional and read-only, so no write lock is taken and
     * the persistence provider may apply read optimizations.</p>
     *
     * @param accountId the account identifier to view (COBOL {@code ACCT-ID});
     *                  must be non-{@code null} and strictly positive
     * @return the flattened, immutable {@link AccountViewResponse} for the account
     * @throws ValidationException       if {@code accountId} is {@code null} or not
     *                                   strictly positive
     * @throws ResourceNotFoundException if the account has no cross-reference row,
     *                                   no account master row, or no customer
     *                                   master row (each with its verbatim message)
     */
    @Transactional(readOnly = true)
    public AccountViewResponse viewAccount(Long accountId) {
        // 2210-EDIT-ACCOUNT: "Not supplied" and "Not numeric / zeros" branches.
        // The id is already a Long (guaranteed numeric), so the surviving edit is
        // "supplied and strictly positive". The raw value is not logged here.
        if (accountId == null || accountId <= 0L) {
            log.debug("Rejected account view request: account id failed the non-zero 11-digit numeric edit");
            throw new ValidationException(MSG_ACCT_INVALID);
        }

        // 9200-GETCARDXREF-BYACCT: resolve XREF-CUST-ID through the shared
        // cross-reference navigator. Its ResourceNotFoundException already carries
        // the verbatim MSG_XREF_NOT_FOUND text, so the not-found contract is preserved.
        Long custId = crossReferenceService.resolveCustomerId(accountId);

        // 9300-GETACCTDATA-BYACCT: keyed read of the account master.
        // Optional.orElseThrow reproduces the DFHRESP(NOTFND) branch.
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_ACCT_NOT_FOUND));

        // 9400-GETCUSTDATA-BYCUST: keyed read of the customer master by the
        // resolved customer id. Optional.orElseThrow reproduces DFHRESP(NOTFND).
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_CUST_NOT_FOUND));

        log.debug("Resolved account+customer view for account id {}", accountId);
        return toResponse(account, customer);
    }

    /**
     * Projects a resolved {@link Account} and {@link Customer} onto the immutable
     * {@link AccountViewResponse}, constructing it <em>positionally</em> in the
     * exact component order declared by the record (account attributes first, then
     * customer attributes).
     *
     * <p>Mapping notes:</p>
     * <ul>
     *   <li>Numeric identifiers ({@code ACCT-ID}, {@code CUST-ID}) are rendered
     *       with {@link String#valueOf(Object)} to preserve fixed-width string
     *       semantics on the wire.</li>
     *   <li>Monetary fields are forwarded as {@link java.math.BigDecimal} unchanged
     *       (scale enforcement happens in the DTO's canonical constructor).</li>
     *   <li>The panel {@code city} is record address line&nbsp;3
     *       ({@link Customer#getCustAddrLine3()}).</li>
     *   <li>The SSN is rendered full and nine-digit here (see
     *       {@link #formatSsn(Long)}); masking is applied by the DTO, not here.</li>
     * </ul>
     *
     * @param account  the resolved account master record; must not be {@code null}
     * @param customer the resolved customer master record; must not be {@code null}
     * @return the flattened, immutable account-view projection
     */
    private AccountViewResponse toResponse(Account account, Customer customer) {
        return new AccountViewResponse(
                // ----- Account attributes (CVACT01Y — ACCOUNT-RECORD) -----
                String.valueOf(account.getAcctId()),
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
                // ----- Customer attributes (CVCUS01Y — CUSTOMER-RECORD) -----
                String.valueOf(customer.getCustId()),
                customer.getCustFirstName(),
                customer.getCustMiddleName(),
                customer.getCustLastName(),
                customer.getCustAddrLine1(),
                customer.getCustAddrLine2(),
                customer.getCustAddrLine3(), // city (screen ACSCITY) = CUST-ADDR-LINE-3
                customer.getCustAddrStateCd(),
                customer.getCustAddrCountryCd(),
                customer.getCustAddrZip(),
                customer.getCustPhoneNum1(),
                customer.getCustPhoneNum2(),
                formatSsn(customer.getCustSsn()),
                customer.getCustGovtIssuedId(),
                customer.getCustDobYyyyMmDd(),
                customer.getCustEftAccountId(),
                customer.getCustPriCardHolderInd(),
                customer.getCustFicoCreditScore());
    }

    /**
     * Renders the numeric {@code CUST-SSN PIC 9(09)} as a nine-digit,
     * left-zero-padded {@link String} (for example {@code 42L} becomes
     * {@code "000000042"}), reproducing the legacy fixed-width rendering.
     *
     * <p>The method is <strong>null-safe</strong>: a {@code null} SSN yields
     * {@code null} rather than the literal {@code "null"} that
     * {@link String#format(String, Object...)} would otherwise produce for a
     * {@code null} argument. No masking is applied here &mdash; SSN masking is a
     * DTO-boundary security concern (decision log D-023).</p>
     *
     * @param ssn the raw numeric SSN ({@code CUST-SSN}); may be {@code null}
     * @return the nine-digit zero-padded SSN string, or {@code null} when
     *         {@code ssn} is {@code null}
     */
    private static String formatSsn(Long ssn) {
        return ssn == null ? null : String.format(SSN_FORMAT, ssn);
    }
}
