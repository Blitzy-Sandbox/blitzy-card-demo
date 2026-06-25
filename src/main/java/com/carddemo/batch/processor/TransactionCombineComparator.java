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
package com.carddemo.batch.processor;

import com.carddemo.entity.Transaction;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Ordering rule for the transaction-combine batch step, realizing the sort stage
 * of the legacy JCL job {@code COMBTRAN} (member {@code app/jcl/COMBTRAN.jcl} at
 * source commit {@code 27d6c6f}).
 *
 * <p>The COBOL job's {@code STEP05R} executes {@code PGM=SORT} over the
 * concatenation of two generation-data-group inputs and applies the control card
 * {@code SORT FIELDS=(TRAN-ID,A)}, where {@code SYMNAMES} declares {@code TRAN-ID}
 * as a 16-character field at offset 1. This component reproduces that single sort
 * key: {@link Transaction} instances are ordered in <em>ascending</em> natural
 * order of {@link Transaction#getTranId() tranId} ({@code TRAN-ID PIC X(16)}).</p>
 *
 * <p>The control card declares neither {@code SUM FIELDS} nor {@code XSUM}: two
 * transactions sharing the same {@code tranId} compare as equal (result
 * {@code 0}) and the comparator applies no secondary tie-breaker, so duplicate
 * keys are neither reordered nor discarded. A {@code null} {@code tranId} is
 * ordered after any non-{@code null} value.</p>
 *
 * <p>The component is stateless and therefore thread-safe; it holds no mutable
 * state and is registered as a Spring bean so the combine job configuration can
 * constructor-inject it.</p>
 */
@Component
public class TransactionCombineComparator implements Comparator<Transaction> {

    /**
     * Single-key ordering delegate: ascending natural order of {@code tranId}
     * with {@code null} identifiers ordered last.
     */
    private static final Comparator<Transaction> BY_TRAN_ID =
            Comparator.comparing(Transaction::getTranId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Compares two transactions by their {@code tranId} in ascending natural
     * order, returning {@code 0} for equal identifiers so that duplicate-key
     * records are preserved rather than discarded.
     *
     * @param first  the first transaction to compare
     * @param second the second transaction to compare
     * @return a negative integer, zero, or a positive integer as {@code first}'s
     *         {@code tranId} orders before, equal to, or after {@code second}'s;
     *         a {@code null} {@code tranId} sorts last
     */
    @Override
    public int compare(Transaction first, Transaction second) {
        return BY_TRAN_ID.compare(first, second);
    }
}
