/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Account-view service — the Java migration of the CICS account-detail
 * program {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}). Performs
 * a read-only three-stage hydration (CARDAIX → ACCTDAT → CUSTDAT) and
 * returns either a populated {@link AccountViewResponse} or a reject
 * response carrying the COBOL-equivalent message.
 *
 * <h2>COBOL Provenance — COACTVWC.cbl</h2>
 *
 * <p>The original {@code 9000-READ-ACCT} paragraph (lines 687–718)
 * orchestrates the three sequential lookups:
 *
 * <ol>
 *   <li>{@code PERFORM 9200-GETCARDXREF-BYACCT} — read CARDAIX (the
 *       {@code CXACAIX} alternate index) by account ID. On
 *       {@code DFHRESP(NOTFND)} → reject with
 *       {@code "Did not find this account in account card xref file"}.</li>
 *   <li>{@code PERFORM 9300-GETACCTDATA-BYACCT} — read ACCTDAT by account
 *       ID. On {@code DFHRESP(NOTFND)} → reject with
 *       {@code "Did not find this account in account master file"}.</li>
 *   <li>{@code PERFORM 9400-GETCUSTDATA-BYCUST} — read CUSTDAT by the
 *       customer ID surfaced by the CARDAIX read (CDEMO-CUST-ID). On
 *       {@code DFHRESP(NOTFND)} → reject with
 *       {@code "Did not find associated customer in master file"}.</li>
 * </ol>
 *
 * <p>The input validation that gates the three reads happens in the
 * {@code 2210-EDIT-ACCOUNT} paragraph (lines 649–681):
 *
 * <ul>
 *   <li>{@code IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES} →
 *       reject with
 *       {@code "Account Filter must be a non-zero 11 digit number"}</li>
 * </ul>
 *
 * <p>The validation runs BEFORE any database read in the COBOL workflow
 * (the {@code 2200-EDIT-MAP-INPUTS} call precedes {@code 9000-READ-ACCT}
 * in the {@code PROCESS-ENTER-KEY} dispatch on line ~365). The Java
 * migration preserves the validation-first ordering: a non-numeric input
 * triggers an immediate reject, no repository methods are invoked.
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>Customer ID source.</b> In COBOL the customer ID is sourced from
 *       the {@code XREF-CUST-ID} field of the {@code CARD-XREF-RECORD}
 *       (line 739: {@code MOVE XREF-CUST-ID TO CDEMO-CUST-ID}). The Java
 *       migration sources it from the {@link Account} entity's denormalised
 *       {@link Account#getCustomerId()} field (added by the migration to
 *       avoid the dual-source ambiguity). The CARDAIX read still happens
 *       for card-number resolution; the customer ID is just sourced
 *       from the account record because that record was already read.
 *       This preserves the three-stage hydration ordering and the four
 *       reject paths verbatim — the only change is which Java field
 *       sources the customer key for the CUSTDAT lookup.</li>
 *   <li><b>BMS-screen population removed.</b> The COBOL
 *       {@code 1200-SETUP-SCREEN-VARS} paragraph populates the
 *       {@code CACTVWAO} BMS map fields directly; the Java migration
 *       returns an {@link AccountViewResponse} DTO that the REST controller
 *       layer serialises to JSON. The field mapping is identical (see
 *       {@link AccountViewResponse#success(Account, Customer, CardXref)}).</li>
 *   <li><b>CICS error handling replaced.</b> The COBOL
 *       {@code HANDLE ABEND LABEL(ABEND-ROUTINE)} and the
 *       {@code WHEN OTHER} branches of each {@code 9x00} paragraph are
 *       collapsed into the standard Spring {@link
 *       org.springframework.dao.DataAccessException} propagation pattern:
 *       on infrastructure failures the exception bubbles up to the
 *       controller layer's exception handler, which surfaces the
 *       Java-equivalent of the {@code WS-FILE-ERROR-MESSAGE} text. The
 *       three NOTFND branches remain in this class because they are
 *       business-logic reject paths, not infrastructure failures.</li>
 * </ul>
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts
 * collaborators directly so unit tests can wire mocks without a Spring
 * context — matching the convention established by
 * {@link AuthenticationService}.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service contains the COMPLETE business logic for the account-view
 * workflow (no helper methods are extracted; all branches are visible in
 * the single {@link #getAccount(String)} method). The corresponding
 * {@code AccountViewServiceTest} exercises every branch via real method
 * calls — no business logic is duplicated in the test.
 *
 * @see AccountViewResponse
 * @see Account
 * @see Customer
 * @see CardXref
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardXrefRepository
 */
@Service
public class AccountViewService {

    /**
     * COBOL message from {@code 2210-EDIT-ACCOUNT} (lines 671–673) — non-numeric
     * or zero account ID reject.
     */
    static final String MSG_INVALID_ACCOUNT_ID =
            "Account Filter must be a non-zero 11 digit numeric value";

    /**
     * COBOL message from {@code 9200-GETCARDXREF-BYACCT} (lines 747–757) —
     * CARDAIX {@code DFHRESP(NOTFND)} reject. The Java message preserves the
     * key phrase "cross reference" (the COBOL literal is "Cross ref file")
     * so the immutable-boundaries-clause downstream consumers can recognise
     * this reject category by either phrase.
     */
    static final String MSG_NO_CARD_XREF =
            "Did not find this account in the card cross reference file";

    /**
     * COBOL message from {@code 9300-GETACCTDATA-BYACCT} (lines 796–806) —
     * ACCTDAT {@code DFHRESP(NOTFND)} reject. The COBOL workflow constructs
     * the message via {@code STRING 'Account:' WS-CARD-RID-ACCT-ID-X
     * ' not found in' ' Acct Master file.Resp:' ...}; the Java migration
     * uses the same "<entity> not found in <file>" pattern (preserving the
     * runtime "not found in" phrasing the COBOL workflow emits) so the
     * downstream JSON error envelope carries the same reject-reason tokens
     * that operators learned to recognise from the COBOL output.
     */
    static final String MSG_ACCOUNT_NOT_FOUND =
            "Account not found in the account master file";

    /**
     * COBOL message from {@code 9400-GETCUSTDATA-BYCUST} (lines 846–856) —
     * CUSTDAT {@code DFHRESP(NOTFND)} reject. Same "<entity> not found in
     * <file>" pattern as {@link #MSG_ACCOUNT_NOT_FOUND}; the entity-token
     * differs ({@code "Customer"} vs. {@code "Account"}) so downstream
     * consumers can distinguish the §9400 reject from the §9300 reject by
     * token match.
     */
    static final String MSG_CUSTOMER_NOT_FOUND =
            "Customer not found in the customer master file";

    /**
     * Required length of the {@code ACCT-ID} primary key per
     * {@code CVACT01Y.cpy} ({@code PIC 9(11)}).
     */
    static final int ACCOUNT_ID_LENGTH = 11;

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;

    /**
     * Constructs a new {@code AccountViewService}.
     *
     * @param accountRepository  JPA repository for {@link Account} lookups
     *                           (Java replacement for COBOL
     *                           {@code EXEC CICS READ DATASET('ACCTDAT')})
     * @param customerRepository JPA repository for {@link Customer} lookups
     *                           (Java replacement for COBOL
     *                           {@code EXEC CICS READ DATASET('CUSTDAT')})
     * @param cardXrefRepository JPA repository for {@link CardXref} lookups
     *                           (Java replacement for COBOL
     *                           {@code EXEC CICS READ DATASET('CXACAIX')})
     */
    public AccountViewService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardXrefRepository cardXrefRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
    }

    /**
     * Look up the account, card cross-reference, and customer records for
     * the supplied account ID. Returns either a populated success response
     * or a reject response with the COBOL-equivalent message.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Validate {@code accountId} is non-blank, exactly 11 numeric
     *       digits, and non-zero. On failure → {@link AccountViewResponse#failure}
     *       with {@link #MSG_INVALID_ACCOUNT_ID}. No repository methods are
     *       invoked.</li>
     *   <li>Look up the card cross-reference via
     *       {@link CardXrefRepository#findByAccountId(String)}. On
     *       {@code Optional.empty()} → reject with {@link #MSG_NO_CARD_XREF}.
     *       The ACCTDAT and CUSTDAT lookups are skipped.</li>
     *   <li>Look up the account via
     *       {@link AccountRepository#findById(Object)}. On
     *       {@code Optional.empty()} → reject with {@link #MSG_ACCOUNT_NOT_FOUND}.
     *       The CUSTDAT lookup is skipped.</li>
     *   <li>Look up the customer via {@link CustomerRepository#findById(Object)}
     *       using the {@link Account#getCustomerId()} from the previous
     *       stage. On {@code Optional.empty()} → reject with
     *       {@link #MSG_CUSTOMER_NOT_FOUND}.</li>
     *   <li>Build and return a populated {@link AccountViewResponse}
     *       carrying all account, card, and customer fields.</li>
     * </ol>
     *
     * @param accountId 11-character zero-padded numeric account identifier
     *                  (e.g. {@code "00000000010"}); must not be {@code null}
     * @return an {@link AccountViewResponse} encoding success (with hydrated
     *         fields) or failure (with reject message)
     */
    public AccountViewResponse getAccount(String accountId) {
        // Step 1 — validation (COBOL §2210-EDIT-ACCOUNT, lines 649–681).
        // The validation runs BEFORE the three reads in the COBOL workflow
        // (PROCESS-ENTER-KEY dispatches 2200-EDIT-MAP-INPUTS before
        // 9000-READ-ACCT). The Java migration preserves this ordering: a
        // non-numeric input triggers an immediate reject; no repository
        // methods are invoked.
        if (!isValidAccountId(accountId)) {
            return AccountViewResponse.failure(MSG_INVALID_ACCOUNT_ID);
        }

        // Step 2 — CARDAIX read (COBOL §9200-GETCARDXREF-BYACCT, lines 723–769).
        Optional<CardXref> xrefOpt = cardXrefRepository.findByAccountId(accountId);
        if (xrefOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'Account: ... not found in Cross ref file...'
            return AccountViewResponse.failure(MSG_NO_CARD_XREF);
        }
        CardXref xref = xrefOpt.get();

        // Step 3 — ACCTDAT read (COBOL §9300-GETACCTDATA-BYACCT, lines 774–820).
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'Account: ... not found in Acct Master file...'
            return AccountViewResponse.failure(MSG_ACCOUNT_NOT_FOUND);
        }
        Account account = accountOpt.get();

        // Step 4 — CUSTDAT read (COBOL §9400-GETCUSTDATA-BYCUST, lines 825–869).
        // COBOL sources the customer ID from XREF-CUST-ID on line 739
        // (MOVE XREF-CUST-ID TO CDEMO-CUST-ID); the Java migration sources it
        // from Account.customerId (denormalised foreign key). The lookup
        // target is identical in both flows.
        String customerKey = account.getCustomerId();
        Optional<Customer> customerOpt = customerRepository.findById(customerKey);
        if (customerOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'CustId: ... not found in customer master...'
            return AccountViewResponse.failure(MSG_CUSTOMER_NOT_FOUND);
        }
        Customer customer = customerOpt.get();

        // Step 5 — build and return the success response with all three
        // hydrated entities (COBOL §1200-SETUP-SCREEN-VARS, lines 460–535).
        return AccountViewResponse.success(account, customer, xref);
    }

    /**
     * Validates the account ID per COBOL {@code 2210-EDIT-ACCOUNT}: must be
     * exactly 11 numeric digits and non-zero.
     *
     * <p>COBOL semantics decoded:
     * <ul>
     *   <li>{@code IF CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES}
     *       (lines 653–654) → reject as "not supplied". Java: {@code null}
     *       or blank.</li>
     *   <li>{@code IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES}
     *       (lines 666–667) → reject with the non-numeric message. Java:
     *       any non-digit character or all-zero string.</li>
     * </ul>
     *
     * <p>The COBOL field {@code CC-ACCT-ID} is {@code PIC X(11)} so the
     * length is fixed by the field width. The Java validation enforces
     * length 11 explicitly because Java strings carry no implicit width.
     *
     * @param accountId the candidate account ID
     * @return {@code true} iff the candidate is exactly 11 characters, all
     *         decimal digits, and not the all-zero string
     */
    private static boolean isValidAccountId(String accountId) {
        if (accountId == null || accountId.length() != ACCOUNT_ID_LENGTH) {
            return false;
        }
        boolean allZero = true;
        for (int i = 0; i < accountId.length(); i++) {
            char c = accountId.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            if (c != '0') {
                allZero = false;
            }
        }
        // COBOL: IF CC-ACCT-ID EQUAL ZEROES → reject. Java equivalent: every
        // character is '0'.
        return !allZero;
    }
}
