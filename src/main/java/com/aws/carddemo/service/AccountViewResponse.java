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

import java.math.BigDecimal;

/**
 * Result DTO for {@link AccountViewService#getAccount(String)} — the Java
 * replacement for the {@code CACTVWAO} BMS-mapped output record emitted
 * by {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}). Encodes either a
 * <em>success</em> outcome carrying the hydrated account / card / customer
 * fields ready for the REST controller layer to serialise, or a
 * <em>failure</em> outcome carrying the COBOL-equivalent reject message
 * (validation reject or one of the three NOTFND reject paths).
 *
 * <h2>COBOL Provenance — COACTVWC.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL
 * {@code 1200-SETUP-SCREEN-VARS} paragraph (lines 460–535) that populates
 * the {@code CACTVWAO} output map fields:
 * <ul>
 *   <li>{@code ACCT-ID} → {@link #accountId}</li>
 *   <li>{@code CDEMO-CARD-NUM} (from CARDAIX) → {@link #cardNumber}</li>
 *   <li>{@code ACCT-ACTIVE-STATUS} → {@link #activeStatus}</li>
 *   <li>{@code ACCT-CURR-BAL} → {@link #currentBalance} (BigDecimal scale 2)</li>
 *   <li>{@code ACCT-CREDIT-LIMIT} → {@link #creditLimit} (BigDecimal scale 2)</li>
 *   <li>{@code ACCT-CASH-CREDIT-LIMIT} → {@link #cashCreditLimit} (BigDecimal scale 2)</li>
 *   <li>{@code ACCT-CURR-CYC-CREDIT} → {@link #currentCycleCredit} (BigDecimal scale 2)</li>
 *   <li>{@code ACCT-CURR-CYC-DEBIT} → {@link #currentCycleDebit} (BigDecimal scale 2)</li>
 *   <li>{@code ACCT-OPEN-DATE} → {@link #openDate}</li>
 *   <li>{@code ACCT-EXPIRAION-DATE} → {@link #expirationDate}</li>
 *   <li>{@code ACCT-REISSUE-DATE} → {@link #reissueDate}</li>
 *   <li>{@code ACCT-ADDR-ZIP} → {@link #accountAddressZip}</li>
 *   <li>{@code ACCT-GROUP-ID} → {@link #groupId}</li>
 *   <li>{@code CUST-ID} → {@link #customerId}</li>
 *   <li>{@code CUST-FIRST-NAME} → {@link #customerFirstName}</li>
 *   <li>{@code CUST-MIDDLE-NAME} → {@link #customerMiddleName}</li>
 *   <li>{@code CUST-LAST-NAME} → {@link #customerLastName}</li>
 *   <li>{@code CUST-FICO-CREDIT-SCORE} → {@link #ficoCreditScore}</li>
 * </ul>
 *
 * <p>The failure outcome corresponds to one of the four reject paths in
 * {@code COACTVWC.cbl}:
 * <ul>
 *   <li>Non-numeric account ID validation reject (§2210-EDIT-ACCOUNT,
 *       lines 666–680): {@code "Account Filter must be a non-zero 11
 *       digit number"}</li>
 *   <li>{@code CARDAIX} {@code DFHRESP(NOTFND)} reject (§9200-GETCARDXREF-BYACCT,
 *       lines 741–758): {@code "Did not find this account in account card
 *       xref file"}</li>
 *   <li>{@code ACCTDAT} {@code DFHRESP(NOTFND)} reject (§9300-GETACCTDATA-BYACCT,
 *       lines 789–807): {@code "Did not find this account in account
 *       master file"}</li>
 *   <li>{@code CUSTDAT} {@code DFHRESP(NOTFND)} reject (§9400-GETCUSTDATA-BYCUST,
 *       lines 839–857): {@code "Did not find associated customer in
 *       master file"}</li>
 * </ul>
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so that the invariant between {@link #success} and {@link #message}
 * (and between {@link #success} and the populated data fields) cannot be
 * violated: {@link #failure(String)} returns a failure outcome carrying only
 * the reject message (all data fields {@code null}); {@link #success(Account,
 * Customer, CardXref)} returns a success outcome with all data fields
 * populated from the three hydrated entities. The constructor is
 * package-private; no production or test code instantiates this class
 * directly.
 *
 * <p>The class deliberately exposes <strong>only getters</strong> — no
 * setters and no public no-args constructor — so the response is
 * effectively read-only after factory construction. This is in line with
 * AAP §0.10.2 (Minimal Change Clause: "Do not introduce patterns,
 * abstractions, or optimizations beyond what the migration requires") and
 * AAP §0.10.4 (Immutable Boundaries: downstream consumers see the
 * hydrated fields exactly as the COBOL baseline produced them).
 *
 * <p>Jackson — the JSON serialiser used by the Spring MVC REST controller
 * layer — serialises out-bound responses via the public getter methods
 * only, so removing setters has no impact on the wire-format contract.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link AccountViewService} in
 * {@code com.aws.carddemo.service} (rather than under a dedicated DTO
 * subpackage), matching the convention established by {@link MainMenuResponse}
 * and {@link AdminMenuResponse} for service/request/response triples that
 * are tightly coupled to a single service contract.
 *
 * @see AccountViewService
 */
public class AccountViewResponse {

    /**
     * {@code true} when the lookup succeeded and all three hydration stages
     * (CARDAIX → ACCTDAT → CUSTDAT) returned a record; {@code false} for any
     * reject branch (non-numeric account ID, missing CARDAIX,
     * missing ACCTDAT, missing CUSTDAT).
     *
     * <p>Tests assert via {@link #isSuccess()}: {@code .isTrue()} for the
     * happy-path test, {@code .isFalse()} for every reject-path test.
     */
    private boolean success;

    /**
     * Reject message populated when {@link #success} is {@code false};
     * {@code null} when {@link #success} is {@code true}. Carries the
     * Java-migration equivalent of the COBOL {@code WS-RETURN-MSG} reject
     * string verbatim (per AAP §0.10.4 immutable-boundaries clause —
     * downstream consumers reading the JSON error envelope must continue
     * to see the same textual reasons as the COBOL baseline).
     */
    private String message;

    /** 11-character zero-padded {@code ACCT-ID}; {@code null} on reject. */
    private String accountId;
    /** 16-character {@code XREF-CARD-NUM} from CARDAIX; {@code null} on reject. */
    private String cardNumber;
    /** {@code ACCT-ACTIVE-STATUS} ({@code 'Y'}/{@code 'N'}); {@code null} on reject. */
    private String activeStatus;

    /** {@code ACCT-CURR-BAL} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal currentBalance;
    /** {@code ACCT-CREDIT-LIMIT} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal creditLimit;
    /** {@code ACCT-CASH-CREDIT-LIMIT} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal cashCreditLimit;
    /** {@code ACCT-CURR-CYC-CREDIT} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal currentCycleCredit;
    /** {@code ACCT-CURR-CYC-DEBIT} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal currentCycleDebit;

    /** {@code ACCT-OPEN-DATE} (ISO YYYY-MM-DD); {@code null} on reject. */
    private String openDate;
    /** {@code ACCT-EXPIRAION-DATE} (ISO YYYY-MM-DD); {@code null} on reject. */
    private String expirationDate;
    /** {@code ACCT-REISSUE-DATE} (ISO YYYY-MM-DD); {@code null} on reject. */
    private String reissueDate;
    /** {@code ACCT-ADDR-ZIP}; {@code null} on reject. */
    private String accountAddressZip;
    /** {@code ACCT-GROUP-ID}; {@code null} on reject. */
    private String groupId;

    /** {@code CUST-ID}; {@code null} on reject. */
    private String customerId;
    /** {@code CUST-FIRST-NAME}; {@code null} on reject. */
    private String customerFirstName;
    /** {@code CUST-MIDDLE-NAME}; {@code null} on reject. */
    private String customerMiddleName;
    /** {@code CUST-LAST-NAME}; {@code null} on reject. */
    private String customerLastName;
    /** {@code CUST-FICO-CREDIT-SCORE}; {@code null} on reject. */
    private Integer ficoCreditScore;

    /**
     * Package-private no-args constructor. The only callers are the two
     * static factory methods on this class ({@link #success(Account,
     * Customer, CardXref)} and {@link #failure(String)}). Production
     * code paths inside {@link AccountViewService} always invoke a
     * factory method so the invariant between {@link #success}, the
     * {@link #message}, and the data fields cannot be violated by an
     * external caller.
     */
    AccountViewResponse() {
        // intentionally empty — fields are populated by the factory
        // methods through direct field assignment.
    }

    /**
     * Factory method for failure outcomes. Returns a response with
     * {@link #success} {@code false}, the supplied reject message set,
     * and every data field left {@code null}.
     *
     * @param message the COBOL-equivalent reject message
     *                (e.g. {@code "Account Filter must be a non-zero 11
     *                digit number"})
     * @return a populated failure response
     */
    public static AccountViewResponse failure(String message) {
        AccountViewResponse response = new AccountViewResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    /**
     * Factory method for success outcomes. Returns a response with
     * {@link #success} {@code true}, {@link #message} {@code null}, and
     * every data field hydrated from the three supplied entities.
     *
     * <p>Maps every field that {@code COACTVWC.cbl}
     * {@code 1200-SETUP-SCREEN-VARS} populates on the {@code CACTVWAO}
     * BMS map (preserving AAP §0.10.4 immutable-boundaries clause —
     * downstream consumers see the same fields as the COBOL baseline).
     *
     * @param account  the hydrated {@link Account} entity from ACCTDAT
     * @param customer the hydrated {@link Customer} entity from CUSTDAT
     * @param xref     the hydrated {@link CardXref} entity from CARDAIX
     * @return a populated success response
     */
    public static AccountViewResponse success(Account account, Customer customer, CardXref xref) {
        AccountViewResponse response = new AccountViewResponse();
        response.success = true;
        // Account fields
        response.accountId = account.getAccountId();
        response.activeStatus = account.getActiveStatus();
        response.currentBalance = account.getCurrentBalance();
        response.creditLimit = account.getCreditLimit();
        response.cashCreditLimit = account.getCashCreditLimit();
        response.currentCycleCredit = account.getCurrentCycleCredit();
        response.currentCycleDebit = account.getCurrentCycleDebit();
        response.openDate = account.getOpenDate();
        response.expirationDate = account.getExpirationDate();
        response.reissueDate = account.getReissueDate();
        response.accountAddressZip = account.getAddressZip();
        response.groupId = account.getGroupId();
        // Card cross-reference field
        response.cardNumber = xref.getCardNumber();
        // Customer fields
        response.customerId = customer.getCustomerId();
        response.customerFirstName = customer.getFirstName();
        response.customerMiddleName = customer.getMiddleName();
        response.customerLastName = customer.getLastName();
        response.ficoCreditScore = customer.getFicoCreditScore();
        return response;
    }

    /** @return {@code true} when the lookup succeeded, {@code false} on any reject path */
    public boolean isSuccess() {
        return success;
    }

    /** @return the reject message ({@code null} on success) */
    public String getMessage() {
        return message;
    }

    /** @return the 11-character zero-padded account ID ({@code null} on reject) */
    public String getAccountId() {
        return accountId;
    }

    /** @return the 16-character card number from CARDAIX ({@code null} on reject) */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @return the single-character active status flag ({@code null} on reject) */
    public String getActiveStatus() {
        return activeStatus;
    }

    /** @return the current balance (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /** @return the total credit limit (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /** @return the cash advance credit limit (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /** @return the current-cycle credit total (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /** @return the current-cycle debit total (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /** @return the account opening date (ISO YYYY-MM-DD; {@code null} on reject) */
    public String getOpenDate() {
        return openDate;
    }

    /** @return the account expiration date (ISO YYYY-MM-DD; {@code null} on reject) */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @return the most-recent card reissue date (ISO YYYY-MM-DD; {@code null} on reject) */
    public String getReissueDate() {
        return reissueDate;
    }

    /** @return the account billing address ZIP ({@code null} on reject) */
    public String getAccountAddressZip() {
        return accountAddressZip;
    }

    /** @return the disclosure-group identifier ({@code null} on reject) */
    public String getGroupId() {
        return groupId;
    }

    /** @return the customer identifier ({@code null} on reject) */
    public String getCustomerId() {
        return customerId;
    }

    /** @return the customer first name ({@code null} on reject) */
    public String getCustomerFirstName() {
        return customerFirstName;
    }

    /** @return the customer middle name ({@code null} on reject) */
    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    /** @return the customer last name ({@code null} on reject) */
    public String getCustomerLastName() {
        return customerLastName;
    }

    /** @return the FICO credit score (300–850; {@code null} on reject) */
    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }
}
