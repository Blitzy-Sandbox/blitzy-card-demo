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
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.dto.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.AccountViewService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link AccountViewService}, the Java
 * realization of the CICS online program {@code app/cbl/COACTVWC.cbl} (account
 * view, transaction {@code CAVW} &rarr; {@code GET /api/accounts/{id}}) at source
 * commit SHA {@code 27d6c6f}.
 *
 * <p>The service reproduces the program's ordered, short-circuiting record reads
 * &mdash; the card cross-reference ({@code 9200-GETCARDXREF-BYACCT}), then the
 * account master ({@code 9300-GETACCTDATA-BYACCT}), then the customer master
 * ({@code 9400-GETCUSTDATA-BYCUST}) &mdash; and folds the joined account and
 * customer fields into the 30-field {@link AccountDto.ViewResponse} (29 data fields
 * plus the optimistic-locking {@code version} token) exactly as the
 * COBOL screen-population paragraph {@code 1200-SETUP-SCREEN-VARS} does. The three
 * not-found outcomes preserve the program's user-visible message text verbatim
 * (confirmed against {@code COACTVWC.cbl} lines&nbsp;130/132/134), which is
 * Gate&nbsp;1 / Gate&nbsp;4 critical.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong> {@code @SpringBootTest},
 * Testcontainers, or database, and touches no AWS or network resource. The three
 * repository collaborators are Mockito mocks injected into the service under test;
 * {@link MockitoExtension} runs in its default strict-stub mode, so each test stubs
 * only the finders it actually exercises.</p>
 *
 * <p>Parity-sensitive assertions are pinned exactly against the compiled contract:
 * the identifier and score fields {@code accountId}, {@code customerId} and
 * {@code ficoScore} are surfaced as {@link String} text (the service renders the
 * numeric entity values through its {@code asText(Number)} helper), the customer
 * SSN keeps its leading zero, the view's {@code city} maps from
 * {@code CUST-ADDR-LINE-3}, and every monetary amount is a {@link BigDecimal}
 * asserted with {@code compareTo} semantics (never {@code equals}, never
 * {@code double}/{@code float}) so a difference in scale alone never fails the
 * comparison. The whole suite is clean under the project's zero-warning
 * ({@code -Xlint:all -Werror}) build.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService — COACTVWC (CAVW) xref→account→customer join @ 27d6c6f")
class AccountViewServiceTest {

    /** Account identifier used as the {@code GET /api/accounts/{id}} path value. */
    private static final Long ACCOUNT_ID = 1L;

    /** Owning customer identifier carried by the cross-reference row. */
    private static final Long CUSTOMER_ID = 100L;

    /** A representative 16-character card number for the cross-reference key. */
    private static final String CARD_NUM = "0500000000000001";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AccountViewService accountViewService;

    /** Fully populated account master fixture (all 13 persistent fields). */
    private Account account;

    /** Fully populated customer master fixture (all 19 persistent fields). */
    private Customer customer;

    /** Cross-reference row linking the card to {@link #ACCOUNT_ID}/{@link #CUSTOMER_ID}. */
    private CardXref cardXref;

    @BeforeEach
    void setUp() {
        // Account master: acctId, activeStatus, currBal, creditLimit, cashCreditLimit,
        // openDate, expirationDate, reissueDate, currCycCredit, currCycDebit,
        // addrZip, groupId, version.
        account = new Account(
                ACCOUNT_ID,
                "Y",
                new BigDecimal("1234.56"),
                new BigDecimal("5000.00"),
                new BigDecimal("2000.00"),
                "2020-01-15",
                "2025-01-15",
                "2023-01-15",
                new BigDecimal("789.01"),
                new BigDecimal("234.56"),
                "90210",
                "GRP01",
                0L);

        // Customer master: custId, firstName, middleName, lastName, addrLine1,
        // addrLine2, addrLine3, addrStateCd, addrCountryCd, addrZip, phoneNum1,
        // phoneNum2, ssn, govtIssuedId, dob, eftAccountId, priCardHolderInd,
        // ficoCreditScore, version. Note: the view's "city" maps from addrLine3.
        customer = new Customer(
                CUSTOMER_ID,
                "JOHN",
                "Q",
                "PUBLIC",
                "123 MAIN ST",
                "APT 4B",
                "ANYTOWN",
                "CA",
                "USA",
                "90210",
                "5551234567",
                "5559876543",
                "020973888",
                "DL123456789",
                "1980-06-15",
                "EFT0000001",
                "Y",
                720,
                0L);

        cardXref = new CardXref(CARD_NUM, CUSTOMER_ID, ACCOUNT_ID);
    }

    // -----------------------------------------------------------------
    // Happy path — full 30-field mapping (xref → account → customer join)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getAccount: maps the joined account+customer onto all 30 ViewResponse fields")
    void getAccountMapsAllViewResponseFields() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        AccountDto.ViewResponse response = accountViewService.getAccount(ACCOUNT_ID);

        // Numeric identifiers/score are surfaced as decimal text via asText(Number).
        assertThat(response.accountId()).isEqualTo("1");
        assertThat(response.customerId()).isEqualTo("100");
        assertThat(response.ficoScore()).isEqualTo("720");

        // Account-sourced text fields.
        assertThat(response.accountStatus()).isEqualTo("Y");
        assertThat(response.openDate()).isEqualTo("2020-01-15");
        assertThat(response.expirationDate()).isEqualTo("2025-01-15");
        assertThat(response.reissueDate()).isEqualTo("2023-01-15");
        assertThat(response.accountGroupId()).isEqualTo("GRP01");

        // Account-sourced monetary fields — compareTo semantics, never equals.
        assertThat(response.currentBalance()).isEqualByComparingTo(account.getCurrBal());
        assertThat(response.creditLimit()).isEqualByComparingTo(account.getCreditLimit());
        assertThat(response.cashCreditLimit()).isEqualByComparingTo(account.getCashCreditLimit());
        assertThat(response.currentCycleCredit()).isEqualByComparingTo(account.getCurrCycCredit());
        assertThat(response.currentCycleDebit()).isEqualByComparingTo(account.getCurrCycDebit());
        // Explicit compareTo == 0 form (required by the parity checklist); the
        // differing scale proves the assertion is value-based, not scale-based.
        assertThat(response.currentBalance().compareTo(new BigDecimal("1234.5600"))).isZero();

        // Customer-sourced text fields.
        assertThat(response.ssn()).isEqualTo("020973888"); // leading zero preserved
        assertThat(response.dateOfBirth()).isEqualTo("1980-06-15");
        assertThat(response.firstName()).isEqualTo("JOHN");
        assertThat(response.middleName()).isEqualTo("Q");
        assertThat(response.lastName()).isEqualTo("PUBLIC");
        assertThat(response.addressLine1()).isEqualTo("123 MAIN ST");
        assertThat(response.addressLine2()).isEqualTo("APT 4B");
        assertThat(response.city()).isEqualTo("ANYTOWN"); // city <- CUST-ADDR-LINE-3
        assertThat(response.state()).isEqualTo("CA");
        assertThat(response.zipCode()).isEqualTo("90210");
        assertThat(response.country()).isEqualTo("USA");
        assertThat(response.phone1()).isEqualTo("5551234567");
        assertThat(response.phone2()).isEqualTo("5559876543");
        assertThat(response.governmentId()).isEqualTo("DL123456789");
        assertThat(response.eftAccountId()).isEqualTo("EFT0000001");
        assertThat(response.primaryCardHolder()).isEqualTo("Y");
        // F-05-1: the optimistic-locking version token is surfaced so the client can
        // echo it back on a subsequent update (9300-CHECK-CHANGE-IN-REC parity).
        assertThat(response.version()).isEqualTo(0L);
    }

    // -----------------------------------------------------------------
    // Not-found cascade — ordered short-circuit with verbatim messages
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getAccount: empty cross-reference throws the xref not-found message and never reads the account master")
    void getAccountThrowsWhenCrossReferenceMissing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> accountViewService.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account card xref file");

        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getAccount: missing account master throws the account not-found message and never reads the customer master")
    void getAccountThrowsWhenAccountMasterMissing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountViewService.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account master file");

        verify(customerRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getAccount: missing customer master throws the associated-customer not-found message")
    void getAccountThrowsWhenCustomerMasterMissing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountViewService.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find associated customer in master file");
    }

    // -----------------------------------------------------------------
    // ZIP screen-width parity — COACTVWC.cbl:515 X(10) -> ACSZIPCO X(5)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getAccount: truncates a 10-character stored ZIP to the 5-character view-screen width (COACTVWC ACSZIPCO X(5) parity)")
    void getAccountTruncatesTenCharacterZipToFiveForScreen() {
        // The persistent CUST-ADDR-ZIP is PIC X(10) (copybook CVCUS01Y); the COACTVW
        // view map output field ACSZIPCO is PIC X(5), so the legacy COBOL alphanumeric
        // MOVE at COACTVWC.cbl:515 truncated to the first five characters, displaying
        // e.g. "19852-6716" as "19852". The view response must preserve that byte-exact
        // 5-char screen contract (and its own @Size(max = 5)), not leak the full record.
        customer.setAddrZip("19852-6716");
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        AccountDto.ViewResponse response = accountViewService.getAccount(ACCOUNT_ID);

        assertThat(response.zipCode()).isEqualTo("19852");
        assertThat(response.zipCode()).hasSize(5);
    }

    @Test
    @DisplayName("getAccount: returns a ZIP of 5 or fewer characters unchanged (no padding, no trimming)")
    void getAccountReturnsShortZipUnchanged() {
        // A stored ZIP already within the 5-char screen width is surfaced verbatim;
        // truncation only removes the surplus of an over-width X(10) value.
        customer.setAddrZip("90210");
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        AccountDto.ViewResponse response = accountViewService.getAccount(ACCOUNT_ID);

        assertThat(response.zipCode()).isEqualTo("90210");
    }
}
