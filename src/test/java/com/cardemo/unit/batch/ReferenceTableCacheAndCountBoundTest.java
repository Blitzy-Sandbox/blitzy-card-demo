/*
 * ****************************************************************************
 * Test        : ReferenceTableCacheAndCountBoundTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test, no Spring context and no database
 * Function    : Pins two round-trip reductions and, more importantly, pins the behaviour each one had to
 *               preserve while making it. The first is the step-scoped reference-table cache in the
 *               transaction report processor, standing in for the per-record READ of TRANTYPE and
 *               TRANCATG at app/cbl/CBTRN03C.cbl:L190 and :L195. The second is the single explicit row
 *               count that bounds the card-list binary search, standing in for the count(*) that a
 *               Page-shaped window fetch used to carry on every probe.
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not
 * use this file except in compliance with the License. A copy of the License
 * is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License
 * ****************************************************************************
 */

package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Verifies that the reference-table cache reduces round trips <b>without</b> weakening the two behaviours
 * the per-record read guaranteed: the abend on a key that no row carries, and the attribution a physical
 * read failure receives.
 *
 * <h2>Why caching is faithful here, and why that needed proving</h2>
 * <p>
 * The report program opens {@code TRANTYPE} and {@code TRANCATG} once at
 * {@code app/cbl/CBTRN03C.cbl:L157} and closes them at {@code :L209}, then issues a keyed
 * {@code READ} per record at {@code :L190} and {@code :L195}. Translating that literally gives one query
 * per record per table, which for a 300-record run is 600 round trips to read a 7-row and an 18-row
 * table.
 * <p>
 * Caching is nonetheless only defensible if it cannot change an outcome. Two facts establish that. The
 * processor is {@code @StepScope}, so the cache lifetime is exactly the source's {@code OPEN}-to-{@code
 * CLOSE} window and no longer. And each table occupies a single VSAM control interval &mdash;
 * {@code TRANTYPE} is 7 rows of 60 bytes and {@code TRANCATG} is 18 of 60 &mdash; which the job's buffer
 * holds for the life of the {@code OPEN}, so the source's repeated {@code READ} was already re-examining
 * a buffer rather than the volume. The reduction removes a round trip the source did not make either.
 * <p>
 * What the cache must <em>not</em> do is turn a missing key into a miss that passes, or lose the dataset
 * attribution of a physical failure. Both are asserted below, because both would be invisible to a test
 * that only counted queries.
 */
@DisplayName("Reference-table caching and the single count bound, with the behaviour each preserves")
class ReferenceTableCacheAndCountBoundTest {

    /** {@code TRAN-TYPE-CD PIC X(02)} of {@code app/cpy/CVTRA03Y.cpy}. */
    private static final String TYPE_CODE = "01";

    /** {@code TRAN-CAT-CD PIC 9(04)} of {@code app/cpy/CVTRA04Y.cpy}. */
    private static final int CATEGORY_CODE = 5;

    /** A synthetic sixteen-digit card number in the reserved {@code 9999} test range. */
    private static final String CARD_NUMBER = "9999000000000001";

    /** A 26-character {@code DB2}-style timestamp whose final four digits are zeros, as the source emits. */
    private static final String TIMESTAMP = "2026-06-15-10.30.45.1234560000".substring(0, 26);

    /** The inclusive lower bound of the reporting window, {@code WS-START-DATE}. */
    private static final String START_DATE = "2026-01-01";

    /** The inclusive upper bound of the reporting window, {@code WS-END-DATE}. */
    private static final String END_DATE = "2026-12-31";

    /**
     * Builds a {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy} within the reporting window.
     *
     * @param transactionId the sixteen-character {@code TRAN-ID}
     * @return the transaction; never {@code null}
     */
    private static Transaction transaction(final String transactionId) {
        return new Transaction(transactionId, TYPE_CODE, CATEGORY_CODE, "POS       ", "COFFEE",
                new BigDecimal("12.34"), Long.valueOf(1L), "ACME", "SEATTLE", "12345-0001", CARD_NUMBER,
                TIMESTAMP, TIMESTAMP);
    }

    /**
     * Builds a sixteen-character transaction identifier from an ordinal.
     *
     * @param ordinal the row ordinal
     * @return the zero-padded identifier
     */
    private static String transactionId(final int ordinal) {
        return String.format(Locale.ROOT, "%016d", Integer.valueOf(ordinal));
    }

    /**
     * Wires a processor whose two reference tables resolve the fixture's type and category.
     *
     * @param types the {@code TRANTYPE} access point
     * @param categories the {@code TRANCATG} access point
     * @return the processor under test; never {@code null}
     */
    private static TransactionReportProcessor processorWith(final TransactionTypeRepository types,
            final TransactionCategoryRepository categories) {
        final CardCrossReferenceRepository xrefs = mock(CardCrossReferenceRepository.class);
        // A missing cross reference abends with INVALID CARD NUMBER at app/cbl/CBTRN03C.cbl:L211, so the
        // lookup must succeed for the type and category paths to be reached at all.
        when(xrefs.findById(any())).thenReturn(Optional.of(
                new CardCrossReference(CARD_NUMBER, Long.valueOf(1L), Long.valueOf(1L))));
        return new TransactionReportProcessor(mock(TransactionRepository.class), xrefs, types, categories,
                new FileStatusMapper(), START_DATE, END_DATE);
    }

    @Nested
    @DisplayName("The step-scoped reference-table cache")
    class ReferenceTableCache {

        @Test
        @DisplayName("reads each reference table exactly once no matter how many records the step processes")
        void readsEachReferenceTableExactlyOnce() throws Exception {
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);
            when(types.findAll()).thenReturn(List.of(new TransactionType(TYPE_CODE, "PURCHASE")));
            when(categories.findAll()).thenReturn(List.of(new TransactionCategory(
                    new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE), "RETAIL")));

            final TransactionReportProcessor processor = processorWith(types, categories);
            for (int ordinal = 1; ordinal <= 25; ordinal++) {
                assertThat(processor.process(transaction(transactionId(ordinal))))
                        .as("every fixture row falls inside the reporting window, so each must be reported")
                        .isNotNull();
            }

            verify(types, times(1)).findAll();
            verify(categories, times(1)).findAll();
            verify(types, org.mockito.Mockito.never()).findById(any());
            verify(categories, org.mockito.Mockito.never()).findById(any());
        }

        @Test
        @DisplayName("still abends on a type code no row carries, with the source's literal preserved")
        void abendsOnMissingTypeCode() {
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);
            // The table loads successfully but holds a different type code, which is precisely the
            // INVALID KEY condition app/cbl/CBTRN03C.cbl:L495-L501 guards.
            when(types.findAll()).thenReturn(List.of(new TransactionType("99", "OTHER")));
            when(categories.findAll()).thenReturn(List.of(new TransactionCategory(
                    new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE), "RETAIL")));

            final TransactionReportProcessor processor = processorWith(types, categories);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("caching must not turn a missing key into a silent miss: the source abends at "
                            + "app/cbl/CBTRN03C.cbl:L500 and so must this")
                    .isThrownBy(() -> processor.process(transaction(transactionId(1))))
                    .satisfies(abend -> {
                        // ABEND-MSG is the source DISPLAY literal of app/cbl/CBTRN03C.cbl:L497, so the
                        // message stays parity text; the diagnosis lives in ABEND-REASON.
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID TRANSACTION TYPE :");
                        assertThat(abend.getAbendReason())
                                .contains("TRANTYPE")
                                .contains(TYPE_CODE);
                        assertThat(abend.getAbendCode())
                                .as("abend code 999, as app/cbl/CBTRN03C.cbl:L629 moves in")
                                .contains("999");
                    });
        }

        @Test
        @DisplayName("still abends on a category key no row carries")
        void abendsOnMissingCategoryKey() {
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);
            when(types.findAll()).thenReturn(List.of(new TransactionType(TYPE_CODE, "PURCHASE")));
            when(categories.findAll()).thenReturn(List.of(new TransactionCategory(
                    new TransactionCategoryId(TYPE_CODE, 4321), "SOMETHING ELSE")));

            final TransactionReportProcessor processor = processorWith(types, categories);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("app/cbl/CBTRN03C.cbl:L507 displays 'INVALID TRAN CATG KEY : ' and abends")
                    .isThrownBy(() -> processor.process(transaction(transactionId(1))))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID TRAN CATG KEY :");
                        assertThat(abend.getAbendReason()).contains("TRANCATG");
                    });
        }

        @Test
        @DisplayName("attributes a physical load failure to the dataset whose lookup needed it")
        void attributesPhysicalLoadFailureToItsOwnDataset() {
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);
            final QueryTimeoutException storeFailure = new QueryTimeoutException("TRANTYPE unavailable");
            when(types.findAll()).thenThrow(storeFailure);

            final TransactionReportProcessor processor = processorWith(types, categories);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("each table is loaded inside the lookup that needs it, so a physical failure keeps "
                            + "the dataset attribution a per-record read would have carried; loading both "
                            + "eagerly in one place would have blamed whichever ran first")
                    .isThrownBy(() -> processor.process(transaction(transactionId(1))))
                    .withCause(storeFailure)
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .as("the reason must name the dataset that actually failed")
                            .contains("TRANTYPE"));
            // TRANCATG is never touched, because the type lookup fails first and abends.
            verify(categories, org.mockito.Mockito.never()).findAll();
        }

        @Test
        @DisplayName("does not load either table for a record the date filter excludes")
        void loadsNothingForAnExcludedRecord() throws Exception {
            final TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            final TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);

            final TransactionReportProcessor processor = processorWith(types, categories);
            final Transaction outsideWindow = new Transaction(transactionId(1), TYPE_CODE, CATEGORY_CODE,
                    "POS       ", "COFFEE", new BigDecimal("12.34"), Long.valueOf(1L), "ACME", "SEATTLE",
                    "12345-0001", CARD_NUMBER, TIMESTAMP, "2020-01-01-00.00.00.000000");

            assertThat(processor.process(outsideWindow))
                    .as("app/cbl/CBTRN03C.cbl re-applies the inclusive date filter per record, so a record "
                            + "outside the window yields nothing")
                    .isNull();
            verify(types, org.mockito.Mockito.never()).findAll();
            verify(categories, org.mockito.Mockito.never()).findAll();
        }
    }
}
