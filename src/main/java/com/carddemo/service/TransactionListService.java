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
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.observation.annotation.Observed;
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
 * key-sequenced browse order of the legacy {@code TRANSACT} dataset. The
 * {@code COTRN00C} transaction-id filter ({@code TRNIDINI}) is honored exactly as
 * the legacy paragraph {@code PROCESS-ENTER-KEY} edits it: a blank filter browses
 * from the start of the dataset ({@code MOVE LOW-VALUES TO TRAN-ID}); a numeric
 * filter positions the browse at the first key greater than or equal to it
 * ({@code MOVE TRNIDINI TO TRAN-ID} then {@code STARTBR ... GTEQ}); and a
 * non-numeric filter is rejected with the message {@value #TRAN_ID_NOT_NUMERIC_MESSAGE}
 * ({@code COTRN00C} line&nbsp;214). An empty page (for example, a page index
 * beyond the available data) is a normal outcome and yields an empty-row
 * {@link TransactionDto.ListResponse} rather than an exception, mirroring the
 * legacy end-of-data screen behavior; a genuine lookup failure surfaces as the
 * repository's {@code DataAccessException}, which the centralized exception
 * handler translates.</p>
 *
 * <p>The {@code COTRN00} row-selection edit ({@code 'Invalid selection. Valid
 * value is S'}, {@code COTRN00C} line&nbsp;199) acts on the {@code SEL00nnI}
 * terminal flags that drive an {@code XCTL} to the detail program
 * {@code COTRN01C}; that navigation is realized by the distinct REST resource
 * {@code GET /api/transactions/{id}}, so it has no field on this list contract.</p>
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
     * Width of the {@code TRAN-ID PIC X(16)} key. A shorter numeric filter is
     * left-zero-padded to this width so it positions correctly within the
     * sixteen-digit zero-padded {@code TRANSACT} key space.
     */
    private static final int TRAN_ID_KEY_WIDTH = 16;

    /**
     * Entity property used to order the browse, equal to the {@code TRAN-ID}
     * primary key on which the legacy {@code TRANSACT} KSDS is sequenced.
     */
    private static final String ORDER_PROPERTY = "tranId";

    /**
     * Validation message emitted for a non-numeric transaction-id filter,
     * reproduced verbatim from {@code COTRN00C} line&nbsp;214
     * ({@code 'Tran ID must be Numeric ...'}).
     */
    private static final String TRAN_ID_NOT_NUMERIC_MESSAGE = "Tran ID must be Numeric ...";

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
     * Returns one page of transactions, optionally positioned at a transaction-id
     * filter.
     *
     * <p>Behavior mirrors {@code COTRN00C} paragraph {@code PROCESS-ENTER-KEY} at
     * commit {@code 27d6c6f}:</p>
     * <ul>
     *   <li>A blank ({@code null}, empty, or whitespace) filter browses the full
     *       {@code TRANSACT} dataset from the start in {@code TRAN-ID} ascending
     *       order ({@code MOVE LOW-VALUES TO TRAN-ID}), reproducing the VSAM
     *       key-sequenced {@code STARTBR}/{@code READNEXT} browse.</li>
     *   <li>A numeric filter positions the browse at the first {@code TRAN-ID}
     *       greater than or equal to it ({@code MOVE TRNIDINI TO TRAN-ID}, then
     *       {@code STARTBR ... GTEQ}); the value is left-zero-padded to the
     *       sixteen-character key width before positioning.</li>
     *   <li>A non-numeric filter is rejected with a {@link ValidationException}
     *       carrying {@value #TRAN_ID_NOT_NUMERIC_MESSAGE} ({@code COTRN00C}
     *       line&nbsp;214), which the centralized handler maps to
     *       {@code 400 Bad Request}.</li>
     *   <li>Every path pages at {@value #PAGE_SIZE} rows, the ten-row
     *       {@code COTRN00} display array. A page index beyond the available data
     *       yields an empty row list (the legacy end-of-data outcome), never an
     *       exception.</li>
     * </ul>
     *
     * @param transactionIdFilter the optional {@code TRAN-ID} positioning filter;
     *                            {@code null} or blank browses from the start, a
     *                            numeric value positions the browse, and a
     *                            non-numeric value is rejected
     * @param pageNumber          the zero-based page index; negative values are
     *                            clamped to the first page
     * @return the requested page mapped to a {@link TransactionDto.ListResponse}
     * @throws ValidationException when {@code transactionIdFilter} is supplied but
     *                             not numeric
     */
    // Service-layer Observation (span + timer) so online txn-list traces show controller -> service.
    @Observed(name = "carddemo.transaction.list", contextualName = "transaction-list")
    @Transactional(readOnly = true)
    public TransactionDto.ListResponse listTransactions(String transactionIdFilter, int pageNumber) {
        int safePage = Math.max(pageNumber, 0);
        String filter = normalizeFilter(transactionIdFilter);
        Pageable pageable =
                PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, ORDER_PROPERTY));

        List<Transaction> transactions;
        String echoedFilter;
        if (filter == null) {
            // TRNIDINI = SPACES OR LOW-VALUES -> MOVE LOW-VALUES TO TRAN-ID: browse from start.
            Page<Transaction> page = transactionRepository.findAll(pageable);
            transactions = page.getContent();
            echoedFilter = null;
        } else {
            // ELSE IF TRNIDINI IS NUMERIC -> position browse; otherwise reject.
            if (!isNumeric(filter)) {
                throw new ValidationException(TRAN_ID_NOT_NUMERIC_MESSAGE);
            }
            Page<Transaction> page =
                    transactionRepository.findByTranIdGreaterThanEqual(positioningKey(filter), pageable);
            transactions = page.getContent();
            echoedFilter = filter;
        }

        return buildResponse(safePage, echoedFilter, transactions);
    }

    /**
     * Normalizes the optional transaction-id filter.
     *
     * @param transactionIdFilter the raw filter value
     * @return the trimmed filter when supplied, or {@code null} when the filter is
     *         absent (null or blank), mirroring the {@code TRNIDINI = SPACES OR
     *         LOW-VALUES} test
     */
    private static String normalizeFilter(String transactionIdFilter) {
        if (transactionIdFilter == null) {
            return null;
        }
        String trimmed = transactionIdFilter.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Reports whether the supplied filter is composed exclusively of ASCII digits,
     * reproducing the COBOL {@code TRNIDINI IS NUMERIC} class test (which accepts
     * only the characters {@code '0'} through {@code '9'}).
     *
     * @param filter the non-null, non-empty trimmed filter
     * @return {@code true} when every character is an ASCII digit
     */
    private static boolean isNumeric(String filter) {
        for (int i = 0; i < filter.length(); i++) {
            char c = filter.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the sixteen-character {@code TRAN-ID} positioning key from a numeric
     * filter, left-zero-padding shorter values so they align with the zero-padded
     * {@code TRANSACT} key space. A value already at least sixteen characters wide
     * is used unchanged, so it positions at or past the end of the key space.
     *
     * @param numericFilter the validated, digits-only filter
     * @return the positioning key for {@code STARTBR ... GTEQ}
     */
    private static String positioningKey(String numericFilter) {
        if (numericFilter.length() >= TRAN_ID_KEY_WIDTH) {
            return numericFilter;
        }
        StringBuilder key = new StringBuilder(TRAN_ID_KEY_WIDTH);
        for (int i = numericFilter.length(); i < TRAN_ID_KEY_WIDTH; i++) {
            key.append('0');
        }
        key.append(numericFilter);
        return key.toString();
    }

    /**
     * Builds the list response from the resolved transactions, the page index, and
     * the echoed transaction-id filter.
     *
     * @param pageNumber          the zero-based page index being returned
     * @param transactionIdFilter the filter to echo ({@code TRNIDIN}), or
     *                            {@code null} for the unfiltered browse
     * @param transactions        the transactions on the current page
     * @return the populated {@link TransactionDto.ListResponse}
     */
    private static TransactionDto.ListResponse buildResponse(int pageNumber, String transactionIdFilter,
            List<Transaction> transactions) {
        List<TransactionDto.TransactionSummary> summaries = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            summaries.add(toSummary(transaction));
        }
        return new TransactionDto.ListResponse(Integer.toString(pageNumber), transactionIdFilter, summaries);
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
