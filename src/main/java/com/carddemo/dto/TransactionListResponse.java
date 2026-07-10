package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Immutable response for the CardDemo <em>Transaction List</em> screen (BMS map
 * {@code COTRN00}, CICS transaction {@code CT00}, legacy program
 * {@code COTRN00C}).
 *
 * <p>This DTO is the Java 25 / Spring Boot translation of the paged transaction
 * browse that the mainframe rendered on the {@code COTRN00} 3270 map. It bundles
 * the echoed search filter together with a single page of transaction rows so a
 * REST caller can reproduce the legacy screen without any server-side
 * conversational ({@code COMMAREA}) state.</p>
 *
 * <h2>Page size</h2>
 * <p>The legacy {@code COTRN00} map displays a <strong>fixed 10 rows per
 * page</strong> (the repeated {@code TRNIDnn} / {@code TDATEnn} / {@code TDESCnn}
 * / {@code TAMTnnn} group, rows {@code 01}&ndash;{@code 10}). The producing
 * {@code TransactionListService} therefore builds the {@link #page()} with
 * {@code pageSize == 10}, preserving the original scroll granularity and the
 * PF7/PF8 backward/forward navigation semantics now exposed through
 * {@link PageResponse#hasPrevious()} / {@link PageResponse#hasNext()}.</p>
 *
 * <h2>COBOL source mapping</h2>
 * <ul>
 *   <li>{@code transactionIdFilter} &larr; {@code TRNIDIN PIC X(16)} of the
 *       {@code COTRN00} symbolic map &mdash; the optional transaction-id search
 *       key the user typed, echoed back so the client can keep the input field
 *       populated across page navigations.</li>
 *   <li>{@code page} &larr; the repeated transaction rows plus the
 *       {@code PAGENUM PIC X(8)} current-page indicator of {@code COTRN00}; each
 *       row derives from the {@code TRAN-RECORD} layout of copybook
 *       {@code CVTRA05Y} (record length 350) via {@link TransactionListItem}.</li>
 * </ul>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Monetary amounts are carried inside each {@link TransactionListItem} as a
 * {@link java.math.BigDecimal} of scale 2 (matching {@code TRAN-AMT
 * PIC S9(09)V99}); no {@code float}/{@code double} is used for money. Scale
 * normalization is the responsibility of {@code TransactionListItem} and the
 * producing service, so this envelope adds no numeric handling of its own.</p>
 *
 * <p>The type is a stateless, immutable {@code record}: it is safe to share
 * across threads and is serialized to and from JSON natively by Jackson. The
 * rationale for the design choices is recorded in {@code docs/decision-log.md}
 * rather than in verbose comments.</p>
 *
 * @param transactionIdFilter the optional, echoed transaction-id search filter
 *                            ({@code TRNIDIN PIC X(16)}); may be {@code null} or
 *                            blank when no filter was supplied
 * @param page                the current page of transaction rows (legacy page
 *                            size 10) wrapped in a generic {@link PageResponse}
 */
public record TransactionListResponse(

        @Size(max = 16)
        String transactionIdFilter,

        PageResponse<TransactionListItem> page) {
}
