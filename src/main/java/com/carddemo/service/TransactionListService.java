/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import java.util.ArrayList;
import java.util.List;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated, read-only transaction-list service backing {@code GET /api/transactions}.
 *
 * <p>This service is the Java translation of the CICS pseudo-conversational
 * program {@code COTRN00C} (transaction {@code CT00}, list transactions) at
 * source commit {@code 27d6c6f}. The legacy VSAM browse machinery over the
 * {@code TRANSACT} KSDS &mdash; {@code STARTBR}/{@code READNEXT}/{@code READPREV}
 * across a ten-row display array with PF7/PF8 page navigation
 * ({@code PROCESS-PF7-KEY}/{@code PROCESS-PF8-KEY},
 * {@code PROCESS-PAGE-FORWARD}/{@code PROCESS-PAGE-BACKWARD}) &mdash; is replaced
 * by stateless Spring Data pagination: a zero-based page-number request parameter
 * drives a {@link PageRequest} of fixed size {@value #PAGE_SIZE}, so no
 * server-side browse cursor or conversational state is retained.</p>
 *
 * <p>Records are read in {@code TRAN-ID} ascending order, reproducing the VSAM
 * key-sequenced browse order of the legacy {@code TRANSACT} dataset. An optional
 * card-number filter narrows the list to a single card's transactions; when it
 * is absent the full transaction set is browsed. An empty page (for example, a
 * page index beyond the available data) is a normal outcome and yields an
 * empty-row {@link TransactionDto.ListResponse} rather than an exception,
 * mirroring the legacy end-of-data screen behavior; a genuine lookup failure
 * surfaces as the repository's {@code DataAccessException}, which the centralized
 * exception handler translates.</p>
 *
 * <p>Monetary amounts are carried as {@link java.math.BigDecimal} end to end, so
 * the {@code TRAN-AMT PIC S9(09)V99} scale is preserved without floating-point
 * substitution.</p>
 *
 * <p>The component is stateless and therefore thread-safe; it holds only the
 * injected repository reference.</p>
 */
@Service
public class TransactionListService {

    /**
     * Number of transaction rows returned per page, equal to the ten-row display
     * array of the {@code COTRN00} screen ({@code TRNID01I}..{@code TRNID10I},
     * the {@code PERFORM UNTIL WS-IDX >= 11} loop in {@code PROCESS-PAGE-FORWARD}).
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Width of the {@code COTRN00} list-row description field
     * ({@code TDESCnn PIC X(26)}); the underlying {@code TRAN-DESC PIC X(100)}
     * is truncated to this width for the list view.
     */
    private static final int DESCRIPTION_DISPLAY_WIDTH = 26;

    /**
     * Entity property used to order the browse, equal to the {@code TRAN-ID}
     * primary key on which the legacy {@code TRANSACT} KSDS is sequenced.
     */
    private static final String ORDER_PROPERTY = "tranId";

    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param transactionRepository the repository used to read {@link Transaction} rows
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns one page of transactions, optionally constrained to a single card.
     *
     * <p>Behavior mirrors {@code COTRN00C} at commit {@code 27d6c6f}:</p>
     * <ul>
     *   <li>When {@code cardNumberFilter} is supplied, the card's transactions are
     *       read in {@code TRAN-ID} ascending order and sliced to the requested
     *       page (the {@code TRNX} card-scoped access path).</li>
     *   <li>Otherwise the full {@code TRANSACT} dataset is browsed in
     *       {@code TRAN-ID} ascending order, reproducing the VSAM key-sequenced
     *       {@code STARTBR}/{@code READNEXT} browse.</li>
     *   <li>Both paths page at {@value #PAGE_SIZE} rows, the ten-row
     *       {@code COTRN00} display array. A page index beyond the available data
     *       yields an empty row list (the legacy end-of-data outcome), never an
     *       exception.</li>
     * </ul>
     *
     * @param cardNumberFilter the optional card number to scope the list to;
     *                         {@code null} or blank browses all transactions
     * @param pageNumber       the zero-based page index; negative values are
     *                         clamped to the first page
     * @return the requested page mapped to a {@link TransactionDto.ListResponse}
     */
    @Transactional(readOnly = true)
    public TransactionDto.ListResponse listTransactions(String cardNumberFilter, int pageNumber) {
        int safePage = Math.max(pageNumber, 0);
        String cardFilter = normalizeFilter(cardNumberFilter);

        List<Transaction> transactions;
        if (cardFilter != null) {
            transactions = pageCardTransactions(cardFilter, safePage);
        } else {
            Pageable pageable =
                    PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, ORDER_PROPERTY));
            Page<Transaction> page = transactionRepository.findAll(pageable);
            transactions = page.getContent();
        }

        return buildResponse(safePage, transactions);
    }

    /**
     * Normalizes the optional card-number filter.
     *
     * @param cardNumberFilter the raw filter value
     * @return the trimmed card number when supplied, or {@code null} when the
     *         filter is absent (null or blank)
     */
    private static String normalizeFilter(String cardNumberFilter) {
        if (cardNumberFilter == null) {
            return null;
        }
        String trimmed = cardNumberFilter.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Reads a single card's transactions in {@code TRAN-ID} ascending order and
     * returns the slice for the requested page.
     *
     * @param cardNumber the card number to scope the read to
     * @param pageNumber the zero-based page index
     * @return the transactions on the requested page, or an empty list when the
     *         page lies beyond the available data
     */
    private List<Transaction> pageCardTransactions(String cardNumber, int pageNumber) {
        List<Transaction> ordered = transactionRepository.findByCardNumOrderByTranIdAsc(cardNumber);
        long fromIndex = (long) pageNumber * PAGE_SIZE;
        if (fromIndex >= ordered.size()) {
            return List.of();
        }
        int start = (int) fromIndex;
        int end = Math.min(start + PAGE_SIZE, ordered.size());
        return ordered.subList(start, end);
    }

    /**
     * Builds the list response from the resolved transactions and the page index.
     *
     * @param pageNumber   the zero-based page index being returned
     * @param transactions the transactions on the current page
     * @return the populated {@link TransactionDto.ListResponse}
     */
    private static TransactionDto.ListResponse buildResponse(int pageNumber, List<Transaction> transactions) {
        List<TransactionDto.TransactionSummary> summaries = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            summaries.add(toSummary(transaction));
        }
        return new TransactionDto.ListResponse(Integer.toString(pageNumber), null, summaries);
    }

    /**
     * Maps a {@link Transaction} entity to a {@link TransactionDto.TransactionSummary}
     * list row, reproducing the {@code COTRN00C} paragraph {@code POPULATE-TRAN-DATA}
     * field population: {@code TRAN-ID}, the {@code MM/DD/YY} date derived from
     * {@code TRAN-ORIG-TS}, the {@code TRAN-DESC} truncated to the list width, and
     * {@code TRAN-AMT}.
     *
     * @param transaction the source entity
     * @return the mapped summary row
     */
    private static TransactionDto.TransactionSummary toSummary(Transaction transaction) {
        return new TransactionDto.TransactionSummary(
                transaction.getTranId(),
                formatTransactionDate(transaction.getOrigTs()),
                truncateDescription(transaction.getTranDesc()),
                transaction.getTranAmt());
    }

    /**
     * Derives the {@code MM/DD/YY} list date from a twenty-six-character
     * origination timestamp, reproducing the {@code POPULATE-TRAN-DATA}
     * reference-modification extraction ({@code WS-TIMESTAMP-DT-YYYY(3:2)},
     * {@code -MM}, {@code -DD} recomposed as {@code MM/DD/YY}).
     *
     * @param origTs the origination timestamp text ({@code TRAN-ORIG-TS}, format
     *               {@code YYYY-MM-DD HH:MM:SS.mmmmmm})
     * @return the eight-character {@code MM/DD/YY} date, or {@code null} when the
     *         timestamp is absent or too short to parse
     */
    private static String formatTransactionDate(String origTs) {
        if (origTs == null || origTs.length() < 10) {
            return null;
        }
        String year = origTs.substring(2, 4);
        String month = origTs.substring(5, 7);
        String day = origTs.substring(8, 10);
        return month + "/" + day + "/" + year;
    }

    /**
     * Truncates the transaction description to the {@code COTRN00} list-row width.
     *
     * @param description the full description ({@code TRAN-DESC PIC X(100)})
     * @return the description limited to {@value #DESCRIPTION_DISPLAY_WIDTH}
     *         characters, or {@code null} when the description is absent
     */
    private static String truncateDescription(String description) {
        if (description == null) {
            return null;
        }
        return description.length() > DESCRIPTION_DISPLAY_WIDTH
                ? description.substring(0, DESCRIPTION_DISPLAY_WIDTH)
                : description;
    }
}
