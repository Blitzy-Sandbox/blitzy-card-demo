/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.TransactionAddService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionAddService} verifying the CP4 COTRN02C
 * cross-reference parity fix: account-only input resolves the card through the
 * account alternate index ({@code READ-CXACAIX-FILE}); card-only input is
 * validated through the cross-reference primary key ({@code READ-CCXREF-FILE});
 * the legacy "Account ID NOT found..." / "Card Number NOT found..." messages are
 * emitted; key-field validation (including the xref read) runs before data-field
 * validation; and a non not-found lookup failure maps to {@link FileAccessException}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C account/card cross-reference resolution")
class TransactionAddServiceTest {

    private static final String ACCOUNT_ID = "11";
    private static final long ACCOUNT_ID_N = 11L;
    private static final String SUBMITTED_CARD = "4111111111111111";
    private static final String DERIVED_CARD = "4222222222222222";
    private static final String FIRST_TRAN_ID = "0000000000000001";

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CardXrefRepository cardXrefRepository;
    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private TransactionAddService service;

    @BeforeEach
    void setUp() {
        // Dates in the canonical request are well-formed and valid; the calendar
        // check is not the focus of these cross-reference tests.
        lenient().when(dateValidationService.isValidDate(anyString())).thenReturn(true);
    }

    /** Builds a request whose data fields are all valid; only the key fields vary. */
    private TransactionDto.AddRequest request(String accountId, String cardNumber, String typeCode) {
        return new TransactionDto.AddRequest(
                accountId,
                cardNumber,
                typeCode,
                "0001",
                "POS",
                "Test purchase",
                new BigDecimal("100.00"),
                "2023-01-15",
                "2023-01-16",
                "123456789",
                "ACME STORE",
                "SEATTLE",
                "98101",
                "Y");
    }

    private void stubNewIdAvailable() {
        when(transactionRepository.findTopByOrderByTranIdDesc()).thenReturn(Optional.empty());
        when(transactionRepository.existsById(FIRST_TRAN_ID)).thenReturn(false);
    }

    @Test
    @DisplayName("account-only input resolves the card via the account alternate index")
    void accountOnlyResolvesCardViaAix() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenReturn(List.of(new CardXref(SUBMITTED_CARD, 1L, ACCOUNT_ID_N)));
        stubNewIdAvailable();
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, "", "01"));

        assertThat(detail.cardNumber()).isEqualTo(SUBMITTED_CARD);
        assertThat(detail.transactionId()).isEqualTo(FIRST_TRAN_ID);
        verify(cardXrefRepository, never()).findById(anyString());
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getCardNum()).isEqualTo(SUBMITTED_CARD);
    }

    @Test
    @DisplayName("card-only input is validated via the cross-reference primary key")
    void cardOnlyValidatesViaPrimaryKey() {
        when(cardXrefRepository.findById(SUBMITTED_CARD))
                .thenReturn(Optional.of(new CardXref(SUBMITTED_CARD, 1L, ACCOUNT_ID_N)));
        stubNewIdAvailable();

        TransactionDto.Detail detail = service.addTransaction(request("", SUBMITTED_CARD, "01"));

        assertThat(detail.cardNumber()).isEqualTo(SUBMITTED_CARD);
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("both supplied: account branch wins and overrides the submitted card")
    void accountTakesPrecedenceOverCard() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenReturn(List.of(new CardXref(DERIVED_CARD, 1L, ACCOUNT_ID_N)));
        stubNewIdAvailable();

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, SUBMITTED_CARD, "01"));

        assertThat(detail.cardNumber()).isEqualTo(DERIVED_CARD);
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("missing account xref yields the legacy 'Account ID NOT found...'")
    void accountNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", "01")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID NOT found...");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("missing card xref yields the legacy 'Card Number NOT found...'")
    void cardNotFound() {
        when(cardXrefRepository.findById(SUBMITTED_CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTransaction(request("", SUBMITTED_CARD, "01")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Number NOT found...");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("neither account nor card yields 'Account or Card Number must be entered...'")
    void neitherKeyProvided() {
        assertThatThrownBy(() -> service.addTransaction(request("", "", "01")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account or Card Number must be entered...");
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("non-numeric account id short-circuits before any xref read")
    void nonNumericAccountSkipsXref() {
        assertThatThrownBy(() -> service.addTransaction(request("12X", "", "01")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID must be Numeric...");
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    }

    @Test
    @DisplayName("non-numeric card number short-circuits before any xref read")
    void nonNumericCardSkipsXref() {
        assertThatThrownBy(() -> service.addTransaction(request("", "4111-BAD", "01")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Number must be Numeric...");
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("key-field xref error takes precedence over a data-field error")
    void keyErrorPrecedesDataError() {
        // Account not found (key error) AND an empty type code (data error): the
        // surfaced message must be the key error because key validation runs first.
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", "")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account ID NOT found...");
    }

    @Test
    @DisplayName("non not-found account lookup failure maps to FileAccessException")
    void accountLookupFailureMapsToFileAccess() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", "01")))
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Unable to lookup Acct in XREF AIX file...");
    }

    @Test
    @DisplayName("non not-found card lookup failure maps to FileAccessException")
    void cardLookupFailureMapsToFileAccess() {
        when(cardXrefRepository.findById(SUBMITTED_CARD))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThatThrownBy(() -> service.addTransaction(request("", SUBMITTED_CARD, "01")))
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Unable to lookup Card # in XREF file...");
    }
}
