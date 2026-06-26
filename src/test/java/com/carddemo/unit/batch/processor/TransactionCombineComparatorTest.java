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
package com.carddemo.unit.batch.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.carddemo.batch.processor.TransactionCombineComparator;
import com.carddemo.entity.Transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure in-memory unit tests for {@link TransactionCombineComparator}, the ordering
 * rule that realizes the sort stage of the legacy JCL job {@code COMBTRAN}
 * (member {@code app/jcl/COMBTRAN.jcl} at source commit {@code 27d6c6f}).
 *
 * <p>The verified source-parity facts these tests pin down are:
 * <ul>
 *   <li>{@code STEP05R EXEC PGM=SORT} with {@code SYSIN} control card
 *       {@code SORT FIELDS=(TRAN-ID,A)} and {@code SYMNAMES TRAN-ID,1,16,CH}
 *       &rarr; an <em>ascending</em> sort on the 16-character {@code tranId},
 *       equivalent to {@link String} natural order.</li>
 *   <li>The control card declares neither {@code SUM FIELDS}, {@code NODUPS},
 *       nor {@code XSUM} &rarr; <strong>duplicate keys are retained</strong> and
 *       equal-key records keep their relative input order because
 *       {@link List#sort(java.util.Comparator)} is a guaranteed-stable sort and
 *       the comparator returns {@code 0} for equal ids (no tie-breaker).</li>
 *   <li>The compiled SUT keys off
 *       {@code Comparator.nullsLast(Comparator.naturalOrder())}, so a
 *       {@code null} {@code tranId} is in contract and orders <em>last</em>; the
 *       null tests below assert exactly that nulls-last behaviour.</li>
 * </ul>
 *
 * <p>This is a low-dependency unit test: no mocks, no Spring context, no
 * Testcontainers, and no I/O. The job-level, end-to-end combine behaviour is
 * covered separately by the integration suite ({@code CombineTransactionsJobIT}).
 *
 * <p>Because {@link Transaction#equals(Object)}/{@link Transaction#hashCode()}
 * are derived solely from {@code tranId}, two distinct instances that share a
 * {@code tranId} are value-equal; the stability test therefore asserts relative
 * order by reference identity ({@code isSameAs}) rather than value equality.
 */
@DisplayName("TransactionCombineComparator — COMBTRAN sort parity (ascending tranId, duplicates retained)")
class TransactionCombineComparatorTest {

    /** System under test; stateless and thread-safe, so a single instance suffices. */
    private final TransactionCombineComparator comparator = new TransactionCombineComparator();

    @Test
    @DisplayName("sorts ascending by tranId and retains duplicate keys (no SUM/NODUPS/XSUM in COMBTRAN)")
    void sortsAscendingAndRetainsDuplicateKeys() {
        // Four records, two of which share id 0001, supplied out of order.
        List<Transaction> transactions = mutableList(
                txn("0000000000000003"),
                txn("0000000000000001"),
                txn("0000000000000002"),
                txn("0000000000000001"));

        transactions.sort(comparator);

        // Ascending order AND both 0001 duplicates retained: 4 in, 4 out, zero dedup.
        assertThat(transactions)
                .extracting(Transaction::getTranId)
                .containsExactly(
                        "0000000000000001",
                        "0000000000000001",
                        "0000000000000002",
                        "0000000000000003");
    }

    @Test
    @DisplayName("equal tranId keys keep their relative input order (stable sort does not reorder duplicates)")
    void stableSortPreservesRelativeOrderOfEqualKeys() {
        // Two DISTINCT instances sharing id 0001 but differing by cardNum. They are
        // value-equal (equals() is tranId-only), so order is asserted by identity.
        Transaction firstDuplicate = txn("0000000000000001", "1111111111111111");
        Transaction secondDuplicate = txn("0000000000000001", "2222222222222222");
        Transaction higherKey = txn("0000000000000003");

        // BKUP-before-SYSTRAN analog: firstDuplicate precedes secondDuplicate on input,
        // and the higher key must migrate behind them once sorted ascending.
        List<Transaction> transactions = mutableList(higherKey, firstDuplicate, secondDuplicate);

        transactions.sort(comparator);

        // Stability: the equal-key pair preserves its original relative order...
        assertThat(transactions.get(0)).isSameAs(firstDuplicate);
        assertThat(transactions.get(1)).isSameAs(secondDuplicate);
        // ...and the higher key sorted to the tail, proving the sort actually ran.
        assertThat(transactions.get(2)).isSameAs(higherKey);
        // The distinguishing field corroborates the order independently of identity.
        assertThat(transactions)
                .extracting(Transaction::getCardNum)
                .containsExactly("1111111111111111", "2222222222222222", null);
    }

    @Test
    @DisplayName("compare() returns 0 for equal tranIds so duplicates are preserved, not discarded")
    void compareReturnsZeroForEqualTranIds() {
        assertThat(comparator.compare(txn("0000000000000005"), txn("0000000000000005"))).isZero();
    }

    @Test
    @DisplayName("compare() sign follows String natural order: negative when before, positive when after")
    void compareSignFollowsNaturalOrder() {
        Transaction lower = txn("0000000000000001");
        Transaction higher = txn("0000000000000002");

        assertThat(comparator.compare(lower, higher)).isNegative();
        assertThat(comparator.compare(higher, lower)).isPositive();
    }

    @Test
    @DisplayName("null tranId sorts last (nulls-last) within a sorted list")
    void nullTranIdSortsLast() {
        Transaction nullId = txn(null);

        List<Transaction> transactions = mutableList(
                nullId,
                txn("0000000000000002"),
                txn("0000000000000001"));

        transactions.sort(comparator);

        assertThat(transactions)
                .extracting(Transaction::getTranId)
                .containsExactly("0000000000000001", "0000000000000002", null);
        assertThat(transactions.get(2)).isSameAs(nullId);
    }

    @Test
    @DisplayName("compare() orders a null tranId after a non-null one and never throws NullPointerException")
    void compareTreatsNullTranIdAsGreatestWithoutThrowing() {
        Transaction nullId = txn(null);
        Transaction nonNull = txn("0000000000000001");

        assertThat(comparator.compare(nullId, nonNull)).isPositive();
        assertThat(comparator.compare(nonNull, nullId)).isNegative();
        assertThat(comparator.compare(txn(null), txn(null))).isZero();
        assertThatCode(() -> comparator.compare(nullId, nonNull)).doesNotThrowAnyException();
    }

    /**
     * Builds a {@link Transaction} carrying only the supplied {@code tranId} — the
     * sole field the comparator inspects.
     *
     * @param tranId the transaction identifier to set (may be {@code null} to
     *               exercise the nulls-last contract)
     * @return a transaction whose {@code tranId} is {@code tranId}
     */
    private static Transaction txn(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    /**
     * Builds a {@link Transaction} with the supplied {@code tranId} and a
     * distinguishing {@code cardNum}, used to tell apart two value-equal
     * duplicate-key records when asserting stable-sort ordering.
     *
     * @param tranId  the transaction identifier to set
     * @param cardNum the distinguishing card number to set
     * @return a transaction with both fields populated
     */
    private static Transaction txn(String tranId, String cardNum) {
        Transaction transaction = txn(tranId);
        transaction.setCardNum(cardNum);
        return transaction;
    }

    /**
     * Collects the supplied transactions into a new mutable {@link ArrayList} so
     * the order-asserting tests can invoke {@link List#sort(java.util.Comparator)}.
     *
     * @param transactions the transactions to collect, in input order
     * @return a mutable, sortable list containing the given transactions
     */
    private static List<Transaction> mutableList(Transaction... transactions) {
        return new ArrayList<>(Arrays.asList(transactions));
    }
}
