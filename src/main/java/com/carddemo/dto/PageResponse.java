package com.carddemo.dto;

import java.util.List;

/**
 * Generic pagination envelope carrying a single page of items together with the
 * pagination metadata required by the CardDemo REST API.
 *
 * <p>This record replaces the legacy 3270 paged-list navigation of the mainframe
 * BMS maps: the current-page indicator ({@code PAGENO} on {@code COCRDLI},
 * {@code PAGENUM} on {@code COTRN00}) and the PF7/PF8 backward/forward scroll
 * keys. {@link #hasNext()} drives the "MORE" / PF8 semantics and
 * {@link #hasPrevious()} drives the PF7 semantics. Row counts differ per screen
 * in the legacy system (7 rows/page for the card list {@code COCRDLI}; 10
 * rows/page for the transaction list {@code COTRN00} and the user list
 * {@code COUSR00}), so {@code pageSize} is carried explicitly rather than fixed.</p>
 *
 * <p><strong>Page numbering is one-based:</strong> the first page is
 * {@code pageNumber == 1}, mirroring the legacy {@code PAGENO} display which
 * starts at 1. This keeps the JSON contract intuitive for API consumers and
 * aligned with the screen behaviour it replaces.</p>
 *
 * <p>Instances are immutable, stateless data holders: {@code content} is copied
 * into an unmodifiable list on construction and no server-side conversational
 * (COMMAREA / session) state is retained. Being a Java record, it is serialized
 * to and from JSON natively by Jackson. The rationale for the design choices is
 * recorded in {@code docs/decision-log.md} rather than in verbose comments.</p>
 *
 * @param <T>           the type of the items contained in the page
 * @param content       the items on this page; never {@code null} (an empty list
 *                      is substituted when {@code null} is supplied) and always
 *                      immutable
 * @param pageNumber    the one-based index of this page (the first page is 1)
 * @param pageSize      the maximum number of items per page
 * @param totalElements the total number of items across all pages
 * @param totalPages    the total number of pages
 * @param hasNext       {@code true} if a page after this one exists (PF8 / "MORE")
 * @param hasPrevious   {@code true} if a page before this one exists (PF7)
 * @param first         {@code true} if this is the first page
 * @param last          {@code true} if this is the last page
 */
public record PageResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        boolean first,
        boolean last) {

    /**
     * Canonical constructor enforcing a non-null, immutable {@code content} list.
     * A {@code null} argument is normalised to an empty list so that neither
     * callers nor serialized consumers ever encounter a {@code null} collection.
     */
    public PageResponse {
        content = (content == null) ? List.of() : List.copyOf(content);
    }

    /**
     * Builds a {@code PageResponse} from a page of items and the raw pagination
     * inputs, deriving {@code totalPages}, {@code hasNext}, {@code hasPrevious},
     * {@code first} and {@code last} so that callers supply only the essential
     * values.
     *
     * <p>The page-count arithmetic is performed in {@code long} space to remain
     * overflow-safe for large totals, and it guards against a division by zero
     * when {@code pageSize} is not positive (in which case {@code totalPages} is
     * {@code 0}).</p>
     *
     * @param <T>           the type of the items contained in the page
     * @param content       the items on this page ({@code null} becomes empty)
     * @param pageNumber    the one-based index of this page (the first page is 1)
     * @param pageSize      the maximum number of items per page
     * @param totalElements the total number of items across all pages
     * @return an immutable {@code PageResponse} with all metadata computed
     */
    public static <T> PageResponse<T> of(
            List<T> content,
            int pageNumber,
            int pageSize,
            long totalElements) {
        int totalPages = (pageSize <= 0)
                ? 0
                : (int) ((totalElements + pageSize - 1) / pageSize);
        boolean hasPrevious = pageNumber > 1;
        boolean hasNext = pageNumber < totalPages;
        boolean first = !hasPrevious;
        boolean last = !hasNext;
        return new PageResponse<>(
                content,
                pageNumber,
                pageSize,
                totalElements,
                totalPages,
                hasNext,
                hasPrevious,
                first,
                last);
    }
}
