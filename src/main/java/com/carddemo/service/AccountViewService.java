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
package com.carddemo.service;

import com.carddemo.dto.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;

import io.micrometer.observation.annotation.Observed;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-view service backing {@code GET /api/accounts/{id}}.
 *
 * <p>Translates (never copies) the CardDemo online program
 * {@code app/cbl/COACTVWC.cbl} (CICS transaction {@code CAVW}) at source commit
 * SHA {@code 27d6c6f}. The account-view read driver paragraph
 * {@code 9000-READ-ACCT} is realised by {@link #getAccount(Long)}, which
 * reproduces the program's ordered, short-circuiting record reads:</p>
 * <ol>
 *   <li>{@code 9200-GETCARDXREF-BYACCT} &mdash; resolve the owning customer for
 *       the account through the card cross-reference, via
 *       {@link CardXrefRepository#findByXrefAcctId(Long)} (the legacy
 *       {@code CXACAIX} account alternate-index path).</li>
 *   <li>{@code 9300-GETACCTDATA-BYACCT} &mdash; read the account master from
 *       {@link AccountRepository} by primary key.</li>
 *   <li>{@code 9400-GETCUSTDATA-BYCUST} &mdash; read the customer master from
 *       {@link CustomerRepository} by primary key.</li>
 * </ol>
 *
 * <p>The legacy VSAM {@code READ} plus {@code FILE STATUS} machinery is replaced
 * by repository finders and {@link RecordNotFoundException}; the three not-found
 * outcomes preserve the exact user-visible message text of the COBOL program.
 * The screen-population paragraph {@code 1200-SETUP-SCREEN-VARS} is realised by
 * {@link #toViewResponse(Account, Customer)}, which maps each account and
 * customer field one-to-one onto {@link AccountDto.ViewResponse}. Monetary
 * amounts remain {@link java.math.BigDecimal} at their stored scale of two, and
 * the customer SSN is surfaced verbatim so its leading zeros are preserved.</p>
 *
 * <p>The lookup is read-only and runs inside a single read-only transaction;
 * collaborators are supplied by constructor injection.</p>
 */
@Service
public class AccountViewService {

    private final AccountRepository accountRepository;

    private final CardXrefRepository cardXrefRepository;

    private final CustomerRepository customerRepository;

    /**
     * Creates the service with its repository collaborators.
     *
     * @param accountRepository  the account master repository
     * @param cardXrefRepository the card cross-reference repository
     * @param customerRepository the customer master repository
     */
    public AccountViewService(AccountRepository accountRepository,
                              CardXrefRepository cardXrefRepository,
                              CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Retrieves the consolidated account view for the supplied account
     * identifier, joining the account master with its owning customer through
     * the card cross-reference.
     *
     * <p>The reads are performed in the same order as the COBOL driver
     * {@code 9000-READ-ACCT} and short-circuit on the first miss:</p>
     * <ol>
     *   <li>no cross-reference exists for the account &rarr;
     *       {@code "Did not find this account in account card xref file"};</li>
     *   <li>no account master record exists &rarr;
     *       {@code "Did not find this account in account master file"};</li>
     *   <li>no customer master record exists &rarr;
     *       {@code "Did not find associated customer in master file"}.</li>
     * </ol>
     *
     * @param accountId the account identifier, already validated by the caller
     * @return the populated account view combining account and customer data
     * @throws RecordNotFoundException if the cross-reference, account master, or
     *                                 customer master record cannot be located
     */
    // Emit a service-layer Observation (span + timer) for this online read so traces show
    // controller -> service, not just the HTTP/security spans. The ObservedAspect registered
    // in ObservabilityConfig intercepts this @Observed method when invoked through the Spring
    // proxy from the controller. The contextualName is the span name surfaced in Jaeger.
    @Observed(name = "carddemo.account.view", contextualName = "account-view")
    @Transactional(readOnly = true)
    public AccountDto.ViewResponse getAccount(Long accountId) {
        List<CardXref> crossReferences = cardXrefRepository.findByXrefAcctId(accountId);
        if (crossReferences.isEmpty()) {
            throw new RecordNotFoundException("Did not find this account in account card xref file");
        }
        Long customerId = crossReferences.get(0).getXrefCustId();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Did not find this account in account master file"));

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Did not find associated customer in master file"));

        return toViewResponse(account, customer);
    }

    /**
     * Maps a resolved account and customer onto the view response, preserving
     * the field order and one-to-one field assignment of the COBOL
     * screen-population paragraph {@code 1200-SETUP-SCREEN-VARS}.
     *
     * @param account  the resolved account master record
     * @param customer the resolved customer master record
     * @return the populated view response
     */
    private static AccountDto.ViewResponse toViewResponse(Account account, Customer customer) {
        return new AccountDto.ViewResponse(
                asText(account.getAcctId()),
                account.getActiveStatus(),
                account.getOpenDate(),
                account.getCreditLimit(),
                account.getExpirationDate(),
                account.getCashCreditLimit(),
                account.getReissueDate(),
                account.getCurrBal(),
                account.getCurrCycCredit(),
                account.getGroupId(),
                account.getCurrCycDebit(),
                asText(customer.getCustId()),
                customer.getSsn(),
                customer.getDob(),
                asText(customer.getFicoCreditScore()),
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

    /**
     * Renders a numeric identifier or score as its decimal text without
     * altering its value, returning {@code null} for a {@code null} input so
     * that no literal {@code "null"} text reaches the response.
     *
     * @param value the numeric value to render, which may be {@code null}
     * @return the decimal text, or {@code null} when the input is {@code null}
     */
    private static String asText(Number value) {
        return value == null ? null : value.toString();
    }

    /**
     * Truncates a stored ZIP/postal code to the five-character account-view
     * screen width, reproducing the COBOL move
     * {@code MOVE CUST-ADDR-ZIP TO ACSZIPCO} at {@code COACTVWC.cbl:515}: the
     * persistent {@code CUST-ADDR-ZIP PIC X(10)} field (copybook
     * {@code CVCUS01Y}) is moved into the view map's output field
     * {@code ACSZIPCO PIC X(5)} (symbolic map {@code COACTVW}), and a COBOL
     * alphanumeric {@code MOVE} is left-justified and truncates the surplus on
     * the right, so the legacy screen displayed only the first five characters
     * (for example {@code "19852-6716"} was shown as {@code "19852"}).
     * Reproducing that truncation keeps the response byte-exact with the legacy
     * view and within the declared {@link AccountDto.ViewResponse#zipCode()}
     * {@code @Size(max = 5)} contract. The five characters are returned verbatim
     * (no trimming) so they match the legacy display byte-for-byte; a
     * {@code null} or already five-or-fewer-character value is returned
     * unchanged. The full {@code X(10)} value remains intact in the persistent
     * record and is unaffected by this view-time projection.
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
}
