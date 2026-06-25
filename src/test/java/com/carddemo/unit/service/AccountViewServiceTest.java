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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link AccountViewService}, the read
 * path that joins the account master with its owning customer through the card
 * cross-reference (the Java realization of the COBOL {@code COACTVWC}
 * {@code 9000-READ-ACCT} driver @ {@code 27d6c6f}).
 *
 * <p>The three repositories are mocked; the tests assert the COBOL short-circuit
 * order &mdash; cross-reference, then account master, then customer master &mdash;
 * the exact not-found messages, and the field projection of the consolidated
 * {@link AccountDto.ViewResponse}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService - COACTVWC 9000-READ-ACCT consolidated account view @ 27d6c6f")
class AccountViewServiceTest {

    private static final Long ACCOUNT_ID = 123L;
    private static final Long CUSTOMER_ID = 456L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AccountViewService service;

    private CardXref xref() {
        CardXref xref = new CardXref();
        xref.setXrefAcctId(ACCOUNT_ID);
        xref.setXrefCardNum("1234567890123456");
        xref.setXrefCustId(CUSTOMER_ID);
        return xref;
    }

    private Account account() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setActiveStatus("Y");
        account.setGroupId("GRP1");
        return account;
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setFirstName("John");
        customer.setMiddleName("Q");
        customer.setLastName("Public");
        customer.setFicoCreditScore(700);
        return customer;
    }

    @Test
    @DisplayName("getAccount: empty cross-reference raises the xref not-found message first")
    void getAccountMissingXrefRaisesXrefNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account card xref file");
    }

    @Test
    @DisplayName("getAccount: missing account master record raises the account not-found message")
    void getAccountMissingAccountRaisesAccountNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account master file");
    }

    @Test
    @DisplayName("getAccount: missing customer master record raises the customer not-found message")
    void getAccountMissingCustomerRaisesCustomerNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find associated customer in master file");
    }

    @Test
    @DisplayName("getAccount: resolved records project onto the view response with numeric ids rendered as text")
    void getAccountResolvesConsolidatedView() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));

        AccountDto.ViewResponse response = service.getAccount(ACCOUNT_ID);

        assertThat(response).isNotNull();
        assertThat(response.accountId()).isEqualTo("123");
        assertThat(response.accountStatus()).isEqualTo("Y");
        assertThat(response.accountGroupId()).isEqualTo("GRP1");
        assertThat(response.customerId()).isEqualTo("456");
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.middleName()).isEqualTo("Q");
        assertThat(response.lastName()).isEqualTo("Public");
        assertThat(response.ficoScore()).isEqualTo("700");
    }
}
