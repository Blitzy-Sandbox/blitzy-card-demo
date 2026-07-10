package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Response payload for the Card List screen (online transaction {@code CCLI}).
 *
 * <p>This DTO is the idiomatic Spring translation of the legacy 3270 BMS symbolic
 * map {@code COCRDLI} ({@code app/cpy-bms/COCRDLI.CPY}), which the online program
 * {@code COCRDLIC} drives via {@code CardListService} (see AAP &sect;0.5.1,
 * "CardListService &larr; COCRDLIC; pagination 7 rows/page"). The mainframe screen
 * renders the account-id and card-number search criteria at the top of the map
 * ({@code ACCTSID} {@code PIC X(11)} and {@code CARDSID} {@code PIC X(16)}) and a
 * fixed block of up to seven repeated card rows
 * ({@code ACCTNOn}/{@code CRDNUMn}/{@code CRDSTSn}, {@code n = 1..7}) with the
 * current page indicator {@code PAGENO} and PF7/PF8 backward/forward scroll keys.
 * Each populated row becomes one {@link CardListItem}; the whole page plus its
 * pagination metadata is carried by {@link PageResponse}.</p>
 *
 * <p><strong>Fixed page size &mdash; seven rows.</strong> The legacy Card List map
 * displays a fixed seven card rows per page, so the {@link PageResponse#pageSize()}
 * of the enclosed {@link #page} is always {@value #PAGE_SIZE} when produced by
 * {@code CardListService}. The {@link #PAGE_SIZE} constant publishes this invariant
 * as a single source of truth for the producing service and its tests, mirroring
 * the {@code COCRDLI} PF7/PF8 scroll behaviour exposed through
 * {@link PageResponse#hasPrevious()} and {@link PageResponse#hasNext()}.</p>
 *
 * <h2>Field mapping</h2>
 * <table>
 *   <caption>COBOL source-to-Java field mapping</caption>
 *   <thead>
 *     <tr><th>Java component</th><th>BMS field ({@code COCRDLI})</th><th>Notes</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code accountIdFilter}</td><td>{@code ACCTSID} {@code PIC X(11)}</td>
 *         <td>echoed account-id search filter; nullable when unfiltered</td></tr>
 *     <tr><td>{@code cardNumberFilter}</td><td>{@code CARDSID} {@code PIC X(16)}</td>
 *         <td>echoed card-number search filter; nullable when unfiltered</td></tr>
 *     <tr><td>{@code page}</td><td>{@code ACCTNOn}/{@code CRDNUMn}/{@code CRDSTSn} + {@code PAGENO}</td>
 *         <td>the page of {@link CardListItem} rows plus pagination metadata</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Design contract</h2>
 * <ul>
 *   <li><strong>Echoed search filters.</strong> {@code accountIdFilter} and
 *       {@code cardNumberFilter} are the criteria the caller submitted, returned
 *       verbatim so a stateless REST client can re-render the search form without
 *       re-supplying them. Both are optional: a {@code null} value denotes "no
 *       filter applied" for that field, replacing the CICS pseudo-conversational
 *       {@code COMMAREA} state that carried these values between screen
 *       interactions.</li>
 *   <li><strong>PAN masking (security, D-023).</strong> The card numbers reachable
 *       through {@link #page} are already masked by {@link CardListItem}; a full
 *       unmasked Primary Account Number is never present. The
 *       {@code cardNumberFilter} echo is likewise masked to its last four digits by
 *       the canonical constructor, so even a caller who filters by a complete
 *       sixteen-digit PAN never sees that PAN reflected back &mdash; the masking is
 *       enforced structurally here rather than relying on any single producer, and
 *       a partial filter of four or fewer characters passes through unchanged so
 *       the search form can still be re-rendered.</li>
 *   <li><strong>Stateless &amp; immutable.</strong> As a Java {@code record} the
 *       type is a thread-safe, side-effect-free value holder carrying no business
 *       logic and retaining no server-side conversational session state.</li>
 * </ul>
 *
 * @param accountIdFilter  the echoed account-id search filter ({@code ACCTSID}
 *                         {@code PIC X(11)}); up to eleven characters, or
 *                         {@code null} when no account-id filter was applied
 * @param cardNumberFilter the echoed card-number search filter ({@code CARDSID}
 *                         {@code PIC X(16)}); up to sixteen characters, or
 *                         {@code null} when no card-number filter was applied.
 *                         Masked to its last four digits by the canonical
 *                         constructor (D-023) so a full PAN is never echoed back;
 *                         a filter of four or fewer characters is preserved
 *                         verbatim
 * @param page             the page of {@link CardListItem} rows with pagination
 *                         metadata; its {@link PageResponse#pageSize() pageSize} is
 *                         {@value #PAGE_SIZE} for the Card List screen
 */
public record CardListResponse(

        @Size(max = 11)
        String accountIdFilter,

        @Size(max = 16)
        String cardNumberFilter,

        PageResponse<CardListItem> page) {

    /**
     * Number of trailing card-number digits left visible when masking the echoed
     * {@code cardNumberFilter} (D-023). Matches the {@code CardListItem} row
     * masking so the whole response uses one consistent last-four convention.
     */
    private static final int FILTER_VISIBLE_DIGITS = 4;

    /**
     * Canonical constructor that enforces the documented PAN-masking security
     * contract (D-023) on the echoed {@code cardNumberFilter}: any value longer
     * than {@value #FILTER_VISIBLE_DIGITS} characters has every character except
     * its last four replaced with {@code '*'}, so a full sixteen-digit Primary
     * Account Number can never be externally observable through this response.
     * Masking here (rather than in a single producer) makes the invariant
     * structural: every construction path &mdash; including JSON deserialization,
     * which Jackson performs through this constructor &mdash; is protected. The
     * masking is null-safe, short-safe (a filter of four or fewer characters is
     * returned unchanged), and idempotent (an already-masked value is unchanged).
     */
    public CardListResponse {
        cardNumberFilter = maskCardNumberFilter(cardNumberFilter);
    }

    /**
     * Masks the echoed card-number filter to its last {@value #FILTER_VISIBLE_DIGITS}
     * digits, mirroring the {@code CardListItem} PAN-masking convention.
     *
     * @param filter the caller-supplied card-number filter; may be {@code null}
     * @return {@code null} for a {@code null} input; the stripped value unchanged
     *         when it has four or fewer characters; otherwise the value with every
     *         character before the last four replaced by {@code '*'}
     */
    private static String maskCardNumberFilter(String filter) {
        if (filter == null) {
            return null;
        }
        final String normalized = filter.strip();
        final int length = normalized.length();
        if (length <= FILTER_VISIBLE_DIGITS) {
            return normalized;
        }
        return "*".repeat(length - FILTER_VISIBLE_DIGITS)
                + normalized.substring(length - FILTER_VISIBLE_DIGITS);
    }

    /**
     * The fixed number of card rows rendered per page by the legacy {@code COCRDLI}
     * Card List screen. Published so that {@code CardListService} and its tests
     * share a single source of truth for the page size carried on {@link #page}
     * (AAP &sect;0.5.1: "pagination 7 rows/page").
     */
    public static final int PAGE_SIZE = 7;
}
