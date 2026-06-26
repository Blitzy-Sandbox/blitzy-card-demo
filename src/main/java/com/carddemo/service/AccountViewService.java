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
                customer.getAddrZip(),
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
}
