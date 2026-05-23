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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionListDto;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Paged transaction-list service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COTRN00C.cbl} (CICS transaction id {@code CT00}).
 *
 * <p>This service produces a paginated transaction list mirroring the
 * 3270 transaction-list screen rendered by {@code COTRN00C.cbl}. In the
 * COBOL source the program issues {@code EXEC CICS STARTBR} on the
 * {@code TRANSACT} VSAM KSDS and then iterates with
 * {@code EXEC CICS READNEXT} to materialize the 10-row {@code CT00LSTA}
 * BMS map (see {@code app/bms/COTRN00.bms}, fields
 * {@code TRNID01..TRNID10}, {@code TDATE01..TDATE10},
 * {@code TDESC01..TDESC10}, {@code TAMT01..TAMT10}). Paging is driven by
 * the {@code DFHPF7}/{@code DFHPF8} AID keys (page backward/forward). In
 * the Java target, the equivalent semantics are achieved through Spring
 * Data {@link Pageable} pagination (page size <b>fixed at 10</b> to
 * preserve the legacy contract).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COTRN00C.cbl} (CICS TRANID
 *       {@code 'CT00'}, file {@code 'TRANSACT'}; AAP &sect;0.4.1).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COTRN00.bms} (mapset
 *       {@code COTRN00}, map {@code CT00LSTA}, 10-row transaction
 *       table).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COTRN00.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes) &mdash; mapped to JPA entity
 *       {@link Transaction}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (KEYS(16 0), RECORDSIZE(350 350)).</li>
 *   <li><b>Page size 10:</b> verbatim transcription of the COBOL
 *       {@code 02 TRNS-DATA OCCURS 10 TIMES} working-storage array in
 *       {@code COTRN00C.cbl} (AAP &sect;0.7.1 Minimal Change Clause).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COTRN00C.cbl &harr; TransactionListService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td>
 *       <td>{@link #listTransactions(String, int)}</td></tr>
 *   <tr><td>{@code STARTBR-TRANSACT-FILE} (positioning) +
 *       {@code READNEXT-TRANSACT-FILE} (ascending traversal)</td>
 *       <td>{@code transactionRepository.findByOrderByTranIdAsc(Pageable)}
 *       (or {@code findByTranCardNumAndTranProcTsBetween} with wide-open
 *       boundary constants when a card-number filter is supplied)</td></tr>
 *   <tr><td>{@code PROCESS-PF7-KEY} / {@code PROCESS-PF8-KEY}</td>
 *       <td>{@link Pageable#previousOrFirst()} / {@link Pageable#next()}
 *       driven by client-supplied {@code page}; this service is
 *       stateless per AAP &sect;0.3.4</td></tr>
 *   <tr><td>{@code 1100-POPULATE-TRAN-LIST-ROWS}</td>
 *       <td>{@link #toRow(Transaction)}</td></tr>
 * </table>
 *
 * <h2>Page size invariant</h2>
 * <p>The COBOL source hard-codes 10 rows per page via the 10-row
 * {@code CT00LSTA} BMS table. This service preserves that invariant via
 * {@link #PAGE_SIZE} (10) per AAP &sect;0.7.1 Minimal Change Clause.
 * Card-number filter on this list view is interpreted as the COBOL
 * &quot;positioning&quot; field analogous to the {@code TRNIDIN} screen
 * input field used by the COBOL program.</p>
 *
 * <h2>PCI-DSS guard rails (PAN masking)</h2>
 * <p>Each row's {@link TransactionListDto.TransactionRow#cardNumber()}
 * is <b>masked</b> via the {@code ****-****-****-LAST4} format before
 * being returned. The legacy 3270 screen does not render the card
 * number at all in this list view (only transaction ID, date, and
 * amount); the Java DTO surfaces the masked PAN for richer client
 * renderings while remaining PCI-DSS-compliant.</p>
 *
 * <h2>Concurrency and transactional behavior</h2>
 * <p>This service is read-only and declares
 * {@code @Transactional(readOnly = true)} on its public method.</p>
 *
 * @see TransactionRepository
 * @see TransactionListDto
 * @see Transaction
 */
@Service
public class TransactionListService {

    /**
     * Page size constant. Fixed at 10 to preserve the legacy
     * {@code CT00LSTA} BMS map's 10-row table per AAP &sect;0.7.1
     * Minimal Change Clause.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * Wide-open lower-bound on {@code tran_proc_ts} used when scoping
     * the schema-mandated
     * {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(
     * String, LocalDateTime, LocalDateTime, Pageable)} to a card with
     * no date window — preserves the COBOL COTRN00C semantic of an
     * unbounded card-scoped browse.
     */
    static final LocalDateTime LIST_MIN_TS = LocalDateTime.of(1900, 1, 1, 0, 0);

    /**
     * Wide-open upper-bound counterpart to {@link #LIST_MIN_TS}.
     */
    static final LocalDateTime LIST_MAX_TS = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

    private static final Logger LOG = LoggerFactory.getLogger(TransactionListService.class);

    private final TransactionRepository transactionRepository;

    /** Constructor injection per AAP &sect;0.7.1. */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
    }

    /**
     * Lists transactions on the page identified by {@code page} (0-indexed),
     * optionally filtered by card number.
     *
     * <p>Equivalent to {@code COTRN00C.cbl}'s
     * {@code 0000-MAIN}/{@code STARTBR-TRANSACT-FILE}/{@code READNEXT}
     * loop. When {@code cardNumberFilter} is supplied, this method uses
     * the schema-mandated derived query
     * {@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(
     * String, LocalDateTime, LocalDateTime, Pageable)} with
     * wide-open timestamp bounds to scope the browse to the card's
     * history (sorted by {@code tran_proc_ts} DESC via the
     * {@link Pageable}); otherwise it browses the full
     * {@code TRANSACT} cluster via the schema-mandated derived query
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}.</p>
     *
     * @param cardNumberFilter optional 16-digit card number to scope
     *                         the result; {@code null} for unfiltered
     * @param page             0-indexed page number
     * @return a {@link TransactionListDto} containing up to
     *         {@link #PAGE_SIZE} rows plus paging metadata
     */
    @Transactional(readOnly = true)
    public TransactionListDto listTransactions(String cardNumberFilter, int page) {
        if (page < 0) {
            LOG.debug("Negative page index {} coerced to 0 per legacy COTRN00C parity", page);
            page = 0;
        }

        // Spring Data Pageable replaces CICS STARTBR/READNEXT cursor.
        Page<Transaction> result;
        if (cardNumberFilter != null && !cardNumberFilter.isBlank()) {
            // Card-scoped browse mirrors the COBOL ALT-INDEX walk
            // ordered by tran_proc_ts DESC (most-recent first).
            // Per AAP §0.7.3, this uses the schema-mandated
            // findByTranCardNumAndTranProcTsBetween(...) with
            // wide-open timestamp boundaries — equivalent to the
            // unfiltered per-card walk because every transaction
            // necessarily falls between LIST_MIN_TS and LIST_MAX_TS.
            Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                    Sort.by(Sort.Direction.DESC, "tranProcTs"));
            LOG.debug("Listing transactions for card (masked) page {} size {}", page, PAGE_SIZE);
            result = transactionRepository.findByTranCardNumAndTranProcTsBetween(
                    cardNumberFilter, LIST_MIN_TS, LIST_MAX_TS, pageable);
        } else {
            // Unfiltered browse ordered by primary key tran_id ASC, the
            // legacy COTRN00C default behavior. Uses the
            // schema-mandated derived query
            // findByOrderByTranIdAsc(Pageable) so the ORDER BY clause
            // is encoded in the method name (PageRequest sort is
            // unused for this method).
            Pageable pageable = PageRequest.of(page, PAGE_SIZE);
            LOG.debug("Listing all transactions page {} size {}", page, PAGE_SIZE);
            result = transactionRepository.findByOrderByTranIdAsc(pageable);
        }

        List<TransactionListDto.TransactionRow> rows = result.getContent().stream()
                .map(this::toRow)
                .toList();

        return new TransactionListDto(
                rows,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast(),
                cardNumberFilter);
    }

    /**
     * Maps a {@link Transaction} entity to a
     * {@link TransactionListDto.TransactionRow}. The card number is
     * masked at the producer per AAP &sect;0.6.6 PCI-DSS discipline.
     */
    private TransactionListDto.TransactionRow toRow(Transaction t) {
        return new TransactionListDto.TransactionRow(
                t.getTranId(),
                maskCardNumber(t.getTranCardNum()),
                t.getTranProcTs(),
                t.getTranTypeCd(),
                t.getTranCatCd(),
                t.getTranSource(),
                t.getTranDesc(),
                t.getTranAmt());
    }

    /**
     * Returns {@code ****-****-****-LAST4} for non-null 16-character
     * PANs; defensive against shorter or null inputs. Mirrors the
     * masking used on the entity's {@code toString()} (PCI-DSS v4.0
     * Requirement 3.4.1, AAP &sect;0.6.6).
     */
    private String maskCardNumber(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}
