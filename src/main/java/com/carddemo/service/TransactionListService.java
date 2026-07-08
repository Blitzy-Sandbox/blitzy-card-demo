package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionListResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Paginated <em>Transaction List</em> service &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot translation of the CICS online program {@code COTRN00C}
 * (transaction {@code CT00}, BMS map {@code COTRN00}; frozen COBOL reference
 * SHA {@code 27d6c6f}, {@code app/cbl/COTRN00C.cbl}).
 *
 * <p>The legacy program browsed the VSAM KSDS {@code TRANSACT} file ten rows at a
 * time (the {@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD} loops
 * terminate at {@code WS-IDX >= 11}), tracking the current page in
 * {@code CDEMO-CT00-PAGE-NUM} and accepting an optional transaction-id start
 * filter typed into {@code TRNIDIN}. Because a KSDS is keyed on
 * {@code TRAN-ID PIC X(16)}, {@code STARTBR}/{@code READNEXT} returned records in
 * ascending {@code TRAN-ID} order.</p>
 *
 * <p>This service reproduces that behavior statelessly for the REST tier: no
 * server-side conversational ({@code COMMAREA}) state is retained; the current
 * page is supplied per call and the echoed filter travels back inside the
 * {@link TransactionListResponse}.</p>
 *
 * <h2>Behavioral parity notes (COTRN00C &rarr; Java)</h2>
 * <ul>
 *   <li><b>Filter validation</b> &mdash; {@code PROCESS-ENTER-KEY}
 *       (COTRN00C&nbsp;L206&ndash;L219) accepts a blank filter and a numeric
 *       filter but rejects a non-blank, non-numeric filter with the verbatim
 *       message {@code 'Tran ID must be Numeric ...'}. That branch is preserved
 *       here as a {@link ValidationException} (HTTP&nbsp;400). The row-select
 *       affordance ({@code 'Invalid selection. Valid value is S'}) is a 3270
 *       screen concern with no equivalent on a headless list endpoint and is
 *       intentionally not modeled.</li>
 *   <li><b>Deterministic paging order (G3)</b> &mdash; to preserve the
 *       {@code TRAN-ID} browse order of the KSDS (and therefore stable, repeatable
 *       page boundaries), the page request sorts by the {@code tranId} property.
 *       This mirrors the access-path parity documented on
 *       {@link TransactionRepository}. Without an explicit sort, SQL pagination
 *       order would be undefined, which would break parity.</li>
 *   <li><b>Fixed page size</b> &mdash; ten rows per page, matching the legacy map
 *       and the {@code pageSize == 10} contract of {@link TransactionListResponse}
 *       / {@link PageResponse}.</li>
 * </ul>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Monetary amounts ({@code TRAN-AMT PIC S9(09)V99}) are carried as
 * {@link BigDecimal} normalized to scale&nbsp;2 via
 * {@code setScale(2, RoundingMode.HALF_UP)} before the DTO is built; no
 * {@code float}/{@code double} is ever used for money.</p>
 *
 * <h2>Security</h2>
 * <p>The optional {@code cardNumberFilter} is a Primary Account Number (PAN). It
 * is used only to scope the query and is <strong>never</strong> written to logs;
 * diagnostics record only whether a card filter was present.</p>
 *
 * <p>The service is stateless and thread-safe: its single collaborator is an
 * injected, thread-safe Spring Data repository. The rationale for the design
 * choices is recorded in {@code docs/decision-log.md} rather than in verbose
 * inline commentary.</p>
 *
 * @see TransactionRepository
 * @see TransactionListResponse
 * @see TransactionListItem
 */
@Service
public class TransactionListService {

    /**
     * Verbatim edit message emitted by {@code COTRN00C}'s {@code PROCESS-ENTER-KEY}
     * when a non-blank transaction-id filter is not numeric (COTRN00C L213&ndash;L214).
     * Preserved character-for-character for interface parity (Gate&nbsp;5).
     */
    static final String MSG_TRANID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /**
     * Rows per page. The legacy {@code COTRN00} map renders a fixed ten-row group
     * (the {@code PROCESS-PAGE-FORWARD} loop stops at {@code WS-IDX >= 11}); the
     * {@link TransactionListResponse} contract likewise fixes {@code pageSize == 10}.
     */
    static final int PAGE_SIZE = 10;

    /**
     * Entity property used to order the browse, preserving the {@code TRAN-ID}
     * (KSDS base-key) sequence of the legacy VSAM read loop (G3).
     */
    private static final String SORT_PROPERTY = "tranId";

    /** SLF4J logger; emits only non-sensitive diagnostics (never a card number / PAN). */
    private static final Logger log = LoggerFactory.getLogger(TransactionListService.class);

    /** Spring Data repository for the migrated {@code TRANSACT} KSDS. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param transactionRepository the transaction repository; must not be {@code null}
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns one page of transactions, reproducing the {@code COTRN00C} browse.
     *
     * <p>Processing order preserves the legacy paragraph order: the transaction-id
     * filter is validated first (mirroring {@code PROCESS-ENTER-KEY}), then a single
     * page is read in {@code TRAN-ID} order (mirroring
     * {@code PROCESS-PAGE-FORWARD}). When a card-number filter is supplied the read
     * is scoped to that card; otherwise the full transaction file is browsed.</p>
     *
     * @param transactionIdFilter optional transaction-id start filter typed into
     *                            {@code TRNIDIN}; may be {@code null} or blank (no
     *                            filter). When non-blank it must be all digits, or a
     *                            {@link ValidationException} is thrown. The value is
     *                            echoed back verbatim in the response.
     * @param cardNumberFilter    optional card number (PAN) used to scope the browse
     *                            to a single card; may be {@code null} or blank. Never
     *                            logged.
     * @param page                zero-based page index to retrieve
     * @return the requested page of transactions wrapped in a
     *         {@link TransactionListResponse} whose {@link PageResponse} uses
     *         one-based page numbering and a fixed page size of {@value #PAGE_SIZE}
     * @throws ValidationException if {@code transactionIdFilter} is non-blank and not
     *                             numeric (HTTP&nbsp;400)
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(String transactionIdFilter,
                                                    String cardNumberFilter,
                                                    int page) {
        // COTRN00C PROCESS-ENTER-KEY (L206-L219): a blank filter is accepted; a
        // non-blank filter must be numeric, else the verbatim edit message is raised.
        if (transactionIdFilter != null
                && !transactionIdFilter.isBlank()
                && !isAllDigits(transactionIdFilter.trim())) {
            throw new ValidationException(MSG_TRANID_NOT_NUMERIC);
        }

        // Browse ten rows at a time in TRAN-ID (KSDS base-key) order so page
        // boundaries are deterministic and match the legacy read sequence (G3).
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(SORT_PROPERTY));

        // COTRN00C browses the whole TRANSACT file; the REST tier additionally
        // supports scoping the browse to a single card (PAN) when requested.
        boolean byCard = cardNumberFilter != null && !cardNumberFilter.isBlank();
        Page<Transaction> source = byCard
                ? transactionRepository.findByTranCardNum(cardNumberFilter, pageable)
                : transactionRepository.findAll(pageable);

        // Project each TRAN-RECORD onto the screen-row DTO (POPULATE-TRAN-DATA).
        Page<TransactionListItem> mapped = source.map(this::toItem);

        // PageResponse uses one-based page numbers (first page == 1); Spring's
        // Page.getNumber() is zero-based, so it is offset by one here.
        PageResponse<TransactionListItem> pageResponse = PageResponse.of(
                mapped.getContent(),
                mapped.getNumber() + 1,
                mapped.getSize(),
                mapped.getTotalElements());

        // Diagnostics only — the card number (PAN) is never logged.
        log.debug("Transaction list page {} of {} returned {} row(s) (scopedByCard={})",
                pageResponse.pageNumber(),
                pageResponse.totalPages(),
                pageResponse.content().size(),
                byCard);

        return new TransactionListResponse(transactionIdFilter, pageResponse);
    }

    /**
     * Maps a persisted {@link Transaction} to a single {@link TransactionListItem}
     * screen row, reproducing the field selection of {@code COTRN00C}'s
     * {@code POPULATE-TRAN-DATA} paragraph.
     *
     * <p>The card number is deliberately omitted: the list-row contract carries no
     * PAN and the legacy map never displayed it on this screen.</p>
     *
     * @param t the source transaction entity; never {@code null}
     * @return the immutable list-row DTO
     */
    private TransactionListItem toItem(Transaction t) {
        // Financial value: preserve COBOL scale 2 (PIC S9(09)V99); no float/double.
        BigDecimal amount = t.getTranAmt();
        if (amount != null) {
            amount = amount.setScale(2, RoundingMode.HALF_UP);
        }
        return new TransactionListItem(
                t.getTranId(),
                toDisplayDate(t.getTranProcTs()),
                t.getTranDesc(),
                amount);
    }

    /**
     * Extracts the {@code yyyy-MM-dd} date portion of a 26-character processing
     * timestamp ({@code TRAN-PROC-TS}, format {@code yyyy-mm-dd-hh.mm.ss.ffffff}).
     *
     * <p>The extraction is null- and short-safe: a {@code null} timestamp yields
     * {@code null}, and a value shorter than ten characters is returned unchanged so
     * that malformed or absent timestamps never raise an exception.</p>
     *
     * @param timestamp the raw processing-timestamp text; may be {@code null}
     * @return the leading {@code yyyy-MM-dd} date, or the original value when it is
     *         {@code null} or shorter than ten characters
     */
    private static String toDisplayDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return timestamp;
        }
        return timestamp.substring(0, 10);
    }

    /**
     * Tests whether every character of {@code value} is an ASCII digit
     * ({@code '0'}&ndash;{@code '9'}), reproducing the COBOL {@code IS NUMERIC}
     * class test used by {@code PROCESS-ENTER-KEY}.
     *
     * <p>A locale-independent range check is used rather than
     * {@link Character#isDigit(char)} so that only {@code 0}&ndash;{@code 9} qualify
     * (Unicode decimal digits from other scripts are rejected), matching the legacy
     * numeric edit exactly.</p>
     *
     * @param value the (non-empty) candidate string to test
     * @return {@code true} if every character is {@code '0'}&ndash;{@code '9'}
     */
    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
