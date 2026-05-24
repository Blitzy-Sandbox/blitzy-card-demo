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
package com.awsm2.carddemo.batch.processor;

import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that maps a single
 * {@link DailyTransaction} staging row to a canonical {@link Transaction}
 * journal entity.
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP05R DFSORT field copy
 * (TRAN-ID → TRAN-ID, TRAN-TYPE-CD → TRAN-TYPE-CD, …)</b> — the per-
 * record byte-identical field copy from the {@code DALYTRAN-RECORD}
 * (CVTRA06Y.cpy) 350-byte layout to the {@code TRAN-RECORD}
 * (CVTRA05Y.cpy) 350-byte layout per AAP &sect;0.4.1.</p>
 *
 * <h2>Statelessness Discipline</h2>
 *
 * <p>This processor is intentionally <b>stateless</b> per AAP
 * &sect;0.4.1 Spring Batch chunk-oriented processing standards:</p>
 * <ul>
 *   <li><b>No mutable fields</b> — all state is local to the
 *       {@link #process(DailyTransaction)} method invocation.</li>
 *   <li><b>No I/O</b> — the processor performs no JDBC, JMS, S3, or
 *       OpenSearch calls; it returns a pure transformation of the
 *       input.</li>
 *   <li><b>Reentrant</b> — the same instance may be invoked concurrently
 *       by multiple threads (relevant for partitioned step execution)
 *       without synchronization.</li>
 *   <li><b>Idempotent</b> — repeated invocations with the same input
 *       produce equal output objects (the {@link Transaction#getTranId()}
 *       equals the source {@link DailyTransaction#getDalytranId()}).</li>
 * </ul>
 *
 * <h2>BigDecimal Preservation (AAP &sect;0.6.1)</h2>
 *
 * <p>The {@code dalytranAmt} (COBOL {@code DALYTRAN-AMT PIC S9(09)V99})
 * is copied as a {@link java.math.BigDecimal} value — the same
 * precision/scale ({@code 11,2}) and same arithmetic discipline
 * (banker's rounding via {@code RoundingMode.HALF_EVEN}) apply on both
 * sides because the column type, JPA mapping, and Java type are
 * identical. <b>NEVER</b> substitute {@code double} / {@code float} for
 * monetary fields in this codebase.</p>
 *
 * <h2>Null Handling</h2>
 *
 * <p>The processor accepts a {@code null} input and returns {@code null}
 * to filter the item out of the chunk per Spring Batch
 * {@link ItemProcessor} contract. This matches the COBOL semantic of
 * skipping a record whose entire 350-byte fixed-width buffer is empty
 * (which would be a control-character artifact in the SORTIN
 * concatenation rather than a real record).</p>
 *
 * <h2>Source Lineage (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JCL:</b> {@code app/jcl/COMBTRAN.jcl} STEP05R DFSORT</li>
 *   <li><b>COBOL:</b> none — pure DFSORT field copy</li>
 *   <li><b>Copybooks:</b>
 *       <ul>
 *         <li>{@code app/cpy/CVTRA06Y.cpy} (DALYTRAN-RECORD, 350 bytes)</li>
 *         <li>{@code app/cpy/CVTRA05Y.cpy} (TRAN-RECORD, 350 bytes)</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.reader.DailyTransactionItemReader
 * @see com.awsm2.carddemo.batch.writer.TransactionJpaItemWriter
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 */
@Component
public class DailyTransactionToTransactionProcessor
        implements ItemProcessor<DailyTransaction, Transaction> {

    /**
     * Maps a single {@link DailyTransaction} to a {@link Transaction}.
     *
     * <p>Field-for-field copy per AAP &sect;0.4.1 (CVTRA05Y.cpy ↔
     * CVTRA06Y.cpy identical 350-byte record layouts). BigDecimal
     * preserved end-to-end per AAP &sect;0.6.1 (NEVER use
     * float/double for monetary values).</p>
     *
     * @param item the source {@link DailyTransaction} staging row; may
     *             be {@code null} to filter the item out per Spring
     *             Batch contract
     * @return a new {@link Transaction} entity with every business
     *         field copied verbatim from {@code item}; {@code null} if
     *         {@code item} is {@code null}
     */
    @Override
    public Transaction process(DailyTransaction item) {
        if (item == null) {
            // Spring Batch ItemProcessor contract: returning null
            // filters the item out of the chunk. This matches the COBOL
            // semantic of skipping an empty/blank record buffer in the
            // SORTIN concatenation.
            return null;
        }

        // Field-for-field mapping per AAP §0.4.1.
        // BigDecimal preserved end-to-end per AAP §0.6.1.
        final Transaction t = new Transaction();
        t.setTranId(item.getDalytranId());                       // COBOL: DALYTRAN-ID    → TRAN-ID
        t.setTranTypeCd(item.getDalytranTypeCd());               // COBOL: DALYTRAN-TYPE-CD → TRAN-TYPE-CD
        t.setTranCatCd(item.getDalytranCatCd());                 // COBOL: DALYTRAN-CAT-CD  → TRAN-CAT-CD
        t.setTranSource(item.getDalytranSource());               // COBOL: DALYTRAN-SOURCE  → TRAN-SOURCE
        t.setTranDesc(item.getDalytranDesc());                   // COBOL: DALYTRAN-DESC    → TRAN-DESC
        t.setTranAmt(item.getDalytranAmt());                     // COBOL: DALYTRAN-AMT PIC S9(09)V99 — BigDecimal
        t.setTranMerchantId(item.getDalytranMerchantId());       // COBOL: DALYTRAN-MERCHANT-ID
        t.setTranMerchantName(item.getDalytranMerchantName());   // COBOL: DALYTRAN-MERCHANT-NAME
        t.setTranMerchantCity(item.getDalytranMerchantCity());   // COBOL: DALYTRAN-MERCHANT-CITY
        t.setTranMerchantZip(item.getDalytranMerchantZip());     // COBOL: DALYTRAN-MERCHANT-ZIP
        t.setTranCardNum(item.getDalytranCardNum());             // COBOL: DALYTRAN-CARD-NUM (PAN — PCI-DSS scope)
        t.setTranOrigTs(item.getDalytranOrigTs());               // COBOL: DALYTRAN-ORIG-TS
        t.setTranProcTs(item.getDalytranProcTs());               // COBOL: DALYTRAN-PROC-TS
        return t;
    }
}
