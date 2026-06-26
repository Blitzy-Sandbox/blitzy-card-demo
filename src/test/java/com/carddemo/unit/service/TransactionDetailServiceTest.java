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
import java.util.Optional;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.TransactionDetailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link TransactionDetailService}, the
 * Java realization of the CICS pseudo-conversational program {@code COTRN01C}
 * (view a transaction, transaction {@code CT01}) at source commit
 * {@code 27d6c6f}. The service backs {@code GET /api/transactions/{id}}: it
 * rejects a blank identifier ({@code PROCESS-ENTER-KEY}), reads the
 * {@code TRANSACT} record keyed on {@code TRAN-ID} ({@code READ-TRANSACT-FILE})
 * through the {@link TransactionRepository}, and maps the {@code TRAN-RECORD}
 * (copybook {@code CVTRA05Y}) onto a {@link TransactionDto.Detail} projection
 * (AAP&nbsp;&sect;0.4.1.1).
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps
 * <strong>no</strong> Spring {@code ApplicationContext}: there is no
 * {@code @SpringBootTest}, no {@code MockMvc}, no Testcontainers, and no
 * database, AWS, or network access. The sole collaborator,
 * {@link TransactionRepository}, is a Mockito {@code @Mock} injected into the
 * {@code @InjectMocks} service via constructor injection.
 * {@link MockitoExtension} runs with its default {@code STRICT_STUBS}
 * strictness, so {@code findById(...)} is stubbed only in the tests that
 * actually reach the keyed read; the empty-identifier tests assert the lookup
 * is never invoked.</p>
 *
 * <p><strong>Byte-exact message parity (Gate&nbsp;1 / Gate&nbsp;4).</strong> Every
 * asserted message is verbatim from the compiled service, which in turn
 * preserves the {@code COTRN01C} literals: {@code "Tran ID can NOT be empty..."}
 * ({@code app/cbl/COTRN01C.cbl} line&nbsp;149) and
 * {@code "Transaction ID NOT found..."} (line&nbsp;285). The {@code WHEN OTHER}
 * access-error literal {@code "Unable to lookup Transaction..."} (line&nbsp;292)
 * is intentionally not asserted here: the compiled service leaves that branch to
 * the persistence layer (a {@code DataAccessException} propagates to the
 * centralized handler), so no service code path emits it under a mocked
 * repository.</p>
 *
 * <p><strong>Documented contract observation ("compiled source wins").</strong>
 * The {@link TransactionDto.Detail#transactionType()} component is a
 * {@link String} carrying the two-character {@code TRAN-TYPE-CD} code (for
 * example {@code "01"}), <em>not</em> a {@link TransactionTypeCode} enum value.
 * The compiled service renders it via
 * {@code transaction.getTransactionType().getCode()}. Accordingly this suite
 * asserts {@code detail.transactionType()} equals
 * {@code TransactionTypeCode.PURCHASE.getCode()} ({@code "01"}) rather than
 * asserting enum identity, in keeping with the compiled
 * {@link TransactionDto.Detail} record being the authoritative contract.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService - COTRN01C (CT01) GET /api/transactions/{id} @ 27d6c6f")
class TransactionDetailServiceTest {

    /** Canonical sixteen-character transaction identifier ({@code TRAN-ID}). */
    private static final String TXN_ID = "0000000000000001";

    /** A transaction identifier guaranteed to be absent from the mocked store. */
    private static final String MISSING_TXN_ID = "0000000000000009";

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionDetailService service;

    /**
     * Builds the canonical posted-transaction fixture used by the happy-path
     * tests: identifier {@link #TXN_ID}, type {@code PURCHASE} (code
     * {@code "01"}), category {@code 5}, source {@code "POS"}, a
     * {@link BigDecimal} amount of {@code 123.45}, merchant {@code 42}
     * ("ACME GROCERY", Seattle, 98101), card {@code "5500000000000004"}, and the
     * 26-character origination/processing timestamps preserved verbatim. A fresh
     * instance is returned per call so no mutable state is shared between tests.
     *
     * @return a fully populated {@link Transaction}
     */
    private static Transaction sampleTransaction() {
        Transaction transaction = new Transaction();
        transaction.setTranId(TXN_ID);
        transaction.setTransactionType(TransactionTypeCode.PURCHASE);
        transaction.setTranCatCd(5);
        transaction.setTranSource("POS");
        transaction.setTranDesc("GROCERY STORE PURCHASE");
        transaction.setTranAmt(new BigDecimal("123.45"));
        transaction.setMerchantId(42L);
        transaction.setMerchantName("ACME GROCERY");
        transaction.setMerchantCity("SEATTLE");
        transaction.setMerchantZip("98101");
        transaction.setCardNum("5500000000000004");
        transaction.setOrigTs("2023-01-15 10:30:00.000000");
        transaction.setProcTs("2023-01-16 02:00:00.000000");
        return transaction;
    }

    // -----------------------------------------------------------------
    // Phase 1 - happy path (READ-TRANSACT-FILE success, COTRN01C detail map)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getTransaction: maps every Detail field one-to-one from the persisted transaction")
    void getTransactionHappyPathMapsAllFields() {
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(sampleTransaction()));

        TransactionDto.Detail detail = service.getTransaction(TXN_ID);

        assertThat(detail.transactionId()).isEqualTo(TXN_ID);
        assertThat(detail.cardNumber()).isEqualTo("5500000000000004");
        // transactionType is the two-character TRAN-TYPE-CD String, not the enum.
        assertThat(detail.transactionType()).isEqualTo(TransactionTypeCode.PURCHASE.getCode());
        assertThat(detail.transactionType()).isEqualTo("01");
        // tranCatCd 5 -> PIC 9(04) zero-padded code.
        assertThat(detail.categoryCode()).isEqualTo("0005");
        assertThat(detail.source()).isEqualTo("POS");
        assertThat(detail.description()).isEqualTo("GROCERY STORE PURCHASE");
        // Monetary amount compared by value (compareTo), never scale-sensitive equals.
        assertThat(detail.amount()).isEqualByComparingTo(new BigDecimal("123.45"));
        // Origination/processing dates surface the verbatim 26-character timestamps.
        assertThat(detail.originDate()).isEqualTo("2023-01-15 10:30:00.000000");
        assertThat(detail.processDate()).isEqualTo("2023-01-16 02:00:00.000000");
        // merchantId 42 -> PIC 9(09) zero-padded code.
        assertThat(detail.merchantId()).isEqualTo("000000042");
        assertThat(detail.merchantName()).isEqualTo("ACME GROCERY");
        assertThat(detail.merchantCity()).isEqualTo("SEATTLE");
        assertThat(detail.merchantZip()).isEqualTo("98101");

        // The keyed read uses the supplied transaction identifier exactly once.
        verify(transactionRepository).findById(TXN_ID);
    }

    @Test
    @DisplayName("getTransaction: amount preserves scale 2 and compares equal regardless of trailing-zero scale")
    void getTransactionAmountComparedByValueNotScale() {
        Transaction transaction = sampleTransaction();
        transaction.setTranAmt(new BigDecimal("123.45"));
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(transaction));

        TransactionDto.Detail detail = service.getTransaction(TXN_ID);

        // compareTo-based equality: 123.45 == 123.450 by value though scales differ.
        assertThat(detail.amount()).isEqualByComparingTo(new BigDecimal("123.450"));
        assertThat(detail.amount().compareTo(new BigDecimal("123.45"))).isZero();
    }

    @Test
    @DisplayName("getTransaction: a different transaction type renders its own two-character code (PAYMENT -> '02')")
    void getTransactionTransactionTypeRenderedAsTwoCharCode() {
        Transaction transaction = sampleTransaction();
        transaction.setTransactionType(TransactionTypeCode.PAYMENT);
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(transaction));

        TransactionDto.Detail detail = service.getTransaction(TXN_ID);

        assertThat(detail.transactionType()).isEqualTo(TransactionTypeCode.PAYMENT.getCode());
        assertThat(detail.transactionType()).isEqualTo("02");
    }

    @Test
    @DisplayName("getTransaction: null optional fields (type/category/merchant id) map to null Detail components")
    void getTransactionNullOptionalFieldsMapToNull() {
        Transaction transaction = sampleTransaction();
        transaction.setTransactionType(null);
        transaction.setTranCatCd(null);
        transaction.setMerchantId(null);
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(transaction));

        TransactionDto.Detail detail = service.getTransaction(TXN_ID);

        assertThat(detail.transactionType()).isNull();
        assertThat(detail.categoryCode()).isNull();
        assertThat(detail.merchantId()).isNull();
        // Non-null neighbours remain mapped, confirming only the null guards fired.
        assertThat(detail.transactionId()).isEqualTo(TXN_ID);
        assertThat(detail.merchantName()).isEqualTo("ACME GROCERY");
    }

    // -----------------------------------------------------------------
    // Phase 2 - empty identifier (PROCESS-ENTER-KEY blank-id reject)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "blank transactionId [{0}] -> \"Tran ID can NOT be empty...\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("getTransaction: null/empty/blank id -> ValidationException 'Tran ID can NOT be empty...' (no lookup)")
    void getTransactionBlankIdThrowsValidation(String transactionId) {
        assertThatThrownBy(() -> service.getTransaction(transactionId))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID can NOT be empty...");

        verify(transactionRepository, never()).findById(any());
    }

    // -----------------------------------------------------------------
    // Phase 3 - record not found (DFHRESP(NOTFND) branch)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getTransaction: unknown id -> RecordNotFoundException 'Transaction ID NOT found...'")
    void getTransactionNotFoundThrowsRecordNotFound() {
        when(transactionRepository.findById(MISSING_TXN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransaction(MISSING_TXN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Transaction ID NOT found...");

        verify(transactionRepository).findById(MISSING_TXN_ID);
    }
}
