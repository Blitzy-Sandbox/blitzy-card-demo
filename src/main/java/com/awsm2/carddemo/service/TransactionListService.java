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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Paged transaction-list service &mdash; the Java target for the
 * COBOL/CICS program {@code app/cbl/COTRN00C.cbl} (CICS transaction id
 * {@code 'CT00'}, BMS mapset {@code COTRN00}, map {@code COTRN0A};
 * source program name {@code COTRN00C}, file
 * {@code 'TRANSACT'}).
 *
 * <p>This service produces a paginated transaction list mirroring the
 * 3270 transaction-list screen rendered by {@code COTRN00C.cbl}. In the
 * COBOL source the program issues {@code EXEC CICS STARTBR} on the
 * {@code TRANSACT} VSAM KSDS and then iterates with
 * {@code EXEC CICS READNEXT} to materialize the 10-row {@code COTRN0A}
 * BMS map (see {@code app/bms/COTRN00.bms}, fields
 * {@code TRNID01..TRNID10}, {@code TDATE01..TDATE10},
 * {@code TDESC01..TDESC10}, {@code TAMT001..TAMT010}). Paging is driven
 * by the {@code DFHPF7} / {@code DFHPF8} AID keys (page backward /
 * forward). In the Java target, the equivalent semantics are achieved
 * through Spring Data {@link Pageable} pagination &mdash; page size is
 * <b>fixed at 10</b> to preserve the legacy contract.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COTRN00C.cbl} (CICS
 *       TRANID {@code 'CT00'}, file {@code 'TRANSACT'}; AAP
 *       &sect;0.4.1 online-programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COTRN00.bms} (mapset
 *       {@code COTRN00}, map {@code COTRN0A}, 10-row transaction
 *       table).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COTRN00.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes) &mdash; mapped to JPA
 *       entity {@link Transaction}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} (KEYS(16 0),
 *       RECORDSIZE(350 350)).</li>
 *   <li><b>Page size 10:</b> verbatim transcription of the COBOL
 *       10-occurrence pattern in the {@code COTRN0AI} symbolic map
 *       (fields {@code TRNID01I..TRNID10I}, {@code TDATE01I..TDATE10I},
 *       {@code TDESC01I..TDESC10I}, {@code TAMT001I..TAMT010I}) and
 *       the {@code PERFORM ... UNTIL WS-IDX &gt;= 11} browse loop in
 *       {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD}. AAP
 *       &sect;0.7.1 Minimal Change Clause forbids deviation from
 *       this 10-row contract.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COTRN00C.cbl &harr; TransactionListService</caption>
 *   <tr><th>COBOL paragraph / construct</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code MAIN-PARA} (entry, PF-key dispatch, COMMAREA
 *           hydration)</td>
 *       <td>{@link #listTransactions(String, int)} entry point;
 *           PF-key dispatch is replaced by REST query parameters;
 *           COMMAREA paging state ({@code CDEMO-CT00-PAGE-NUM},
 *           {@code CDEMO-CT00-TRNID-FIRST},
 *           {@code CDEMO-CT00-TRNID-LAST}) is replaced by the
 *           client-supplied 0-indexed page number and the Spring
 *           Data {@link Page} envelope returned in the response
 *           DTO.</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (TRNIDINI validation &rarr;
 *           {@code TRAN-ID})</td>
 *       <td>The {@code tranIdFilter} parameter; null/blank means
 *           unfiltered ({@code LOW-VALUES} positioning); non-blank
 *           means prefix filter applied on the result set per
 *           AAP &sect;0.4.1 (&quot;filter by transaction ID&quot;).</td></tr>
 *   <tr><td>{@code STARTBR-TRANSACT-FILE} +
 *           {@code READNEXT-TRANSACT-FILE} (ascending traversal)</td>
 *       <td>{@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
 *           &mdash; the explicit derived query that preserves the
 *           VSAM primary-key ASC browse order.</td></tr>
 *   <tr><td>{@code PROCESS-PAGE-FORWARD} (10-row loop) /
 *           {@code PROCESS-PAGE-BACKWARD}</td>
 *       <td>{@link Pageable} page navigation driven by the
 *           client-supplied page number; this service is stateless
 *           per AAP &sect;0.3.4.</td></tr>
 *   <tr><td>{@code POPULATE-TRAN-DATA} (per-row screen field
 *           population)</td>
 *       <td>{@link #toRow(Transaction)} &mdash; produces one
 *           {@link TransactionListDto.TransactionRow} per
 *           {@link Transaction} entity.</td></tr>
 * </table>
 *
 * <h2>Filter semantic (AAP &sect;0.4.1 + Minimal Change Clause)</h2>
 *
 * <p>The single filter parameter is a <b>transaction-ID prefix</b>
 * (mirrors the BMS {@code TRNIDINI PIC X(16)} field that the COBOL
 * source moves into {@code TRAN-ID} before issuing the
 * {@code STARTBR}). The repository
 * {@link TransactionRepository} does not declare a
 * {@code findByTranIdStartingWith(...)} derived query (per AAP
 * &sect;0.7.1 Minimal Change Clause &mdash; the repository's API
 * surface is fixed), so the filter path is implemented via
 * <b>client-side filtering</b> on the results of
 * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)},
 * pulling pages in order until at least the requested page boundary
 * has been satisfied (or the underlying browse is exhausted) and
 * then slicing the filtered list per the {@link #PAGE_SIZE} page
 * size invariant. This preserves the COBOL semantics exactly: the
 * physical browse traverses {@code TRAN-ID} ASC, and the filter
 * narrows what the operator sees on the 10-row screen.</p>
 *
 * <p>The fallback safety cap ({@link #MAX_PREFIX_SCAN_PAGES}) bounds
 * the worst-case server-side scan if a poorly chosen prefix matches
 * a very small subset late in the journal. This is a defensive
 * production safeguard and is documented in
 * {@link #MAX_PREFIX_SCAN_PAGES} below; once exceeded the service
 * returns the rows that were materialised up to that boundary and
 * marks the {@code last} flag {@code true} so the client does not
 * attempt to page beyond the safe scan window.</p>
 *
 * <h2>Page size invariant</h2>
 * <p>The COBOL source hard-codes 10 rows per page via the 10-row
 * {@code COTRN0A} BMS table and the {@code PERFORM UNTIL WS-IDX &gt;= 11}
 * loop. This service preserves that invariant via
 * {@link #PAGE_SIZE} (10) per AAP &sect;0.7.1 Minimal Change
 * Clause.</p>
 *
 * <h2>PCI-DSS guard rails (PAN masking)</h2>
 * <p>{@link TransactionListDto.TransactionRow#cardNumber()} carries a
 * <b>pre-masked</b> PAN. Although the legacy 3270 BMS screen
 * {@code COTRN0A} did NOT display the card number on the list view at
 * all (only transaction ID, date, description, and amount), the Java
 * DTO surfaces a masked PAN for richer client renderings while
 * remaining PCI-DSS-compliant. PAN masking is applied at the producer
 * via the local {@link #maskPan(String)} helper invoked from
 * {@link #toRow(Transaction)} so the row always emits the canonical
 * {@code ************nnnn} form (12 leading asterisks + the trailing
 * 4 digits) per AAP &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1. The
 * {@link TransactionListDto.TransactionRow#toString()} method
 * additionally re-applies the same masking as defense in depth (the
 * masking helper is idempotent &mdash; values already in masked form
 * pass through unchanged).</p>
 *
 * <h2>Concurrency and transactional behaviour</h2>
 * <p>This service is read-only and declares
 * {@link Transactional @Transactional(readOnly = true)} on its public
 * method. The underlying paged queries on
 * {@link TransactionRepository} run inside a {@code READ COMMITTED}
 * JPA / RDS PostgreSQL transaction for the duration of the request
 * &mdash; matching the COBOL source which performs
 * {@code STARTBR}/{@code READNEXT} without {@code UPDATE}-mode locks.
 * The {@code readOnly = true} flag enables the JPA provider's
 * flush-mode optimisation (no dirty-checking on the persistence
 * context) per AAP &sect;0.7.1 transactional-integrity rules.</p>
 *
 * <h2>Observability (AAP &sect;0.6.6)</h2>
 * <p>SLF4J INFO/DEBUG log statements emit traceability events for the
 * browse (e.g., {@code "listTransactions tranIdFilter=... page=..."}).
 * Routed through Logback + {@code logstash-logback-encoder} to
 * CloudWatch Logs. <b>PII discipline:</b> never logs raw card
 * numbers, full transaction record contents, or any field beyond
 * non-sensitive identifiers (transaction ID, page number, total
 * elements).</p>
 *
 * @see TransactionRepository
 * @see TransactionListDto
 * @see Transaction
 */
@Service
public class TransactionListService {

    /**
     * Verbatim &quot;NO RECORDS FOUND FOR THIS SEARCH CONDITION.&quot;
     * message reused across paginated browse services in this
     * codebase (see {@code CardListService},
     * {@code UserListService}). The COBOL {@code COTRN00C.cbl}
     * source uses page-context messages instead
     * (&quot;You are at the top of the page...&quot; from the
     * {@code STARTBR} {@code NOTFND} branch at L608, &quot;You have
     * reached the bottom of the page...&quot; from the
     * {@code READNEXT} {@code ENDFILE} branch at L642), but the AAP
     * &sect;0.7.1 alignment with sibling list services standardises
     * on this single message for the empty-result case in the REST
     * world. This constant is logged at INFO level when an empty
     * result page is returned so operators can correlate empty
     * responses with their queries.
     */
    static final String NO_RECORDS_FOUND_MSG =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * Per-screen row count. <b>Fixed at 10</b> to preserve the
     * legacy {@code COTRN0A} BMS map's 10-row table and the COBOL
     * {@code PERFORM ... UNTIL WS-IDX &gt;= 11} browse loop in
     * {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD} per AAP
     * &sect;0.7.1 Minimal Change Clause. Callers do NOT supply a
     * {@code size} parameter on the public method &mdash; this is
     * the fixed, intentional, non-overridable contract.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * Defensive safety cap on the number of underlying repository
     * pages this service will scan when a transaction-ID prefix
     * filter is supplied.
     *
     * <p>Because the repository does NOT expose a
     * {@code findByTranIdStartingWith(...)} derived query (per AAP
     * &sect;0.7.1 Minimal Change Clause), the filter path performs
     * client-side filtering on the results of
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}.
     * To prevent an unbounded scan when a poorly chosen prefix
     * matches only a very small subset of records late in the
     * journal, this cap bounds the worst-case server-side scan
     * window.</p>
     *
     * <p>500 underlying pages of size 10 = 5,000 records scanned
     * per request maximum &mdash; sufficient to satisfy any
     * reasonable prefix while protecting the database from
     * runaway scans on adversarial input. The cap applies only to
     * the filtered path; the unfiltered path uses the native
     * repository pagination directly with no scan amplification.</p>
     */
    static final int MAX_PREFIX_SCAN_PAGES = 500;

    /**
     * Sort name (entity property) used for the ascending primary-key
     * browse. Preserves the COBOL VSAM KSDS {@code TRAN-ID}
     * ascending traversal order from {@code COTRN00C.cbl}
     * {@code STARTBR-TRANSACT-FILE} / {@code READNEXT-TRANSACT-FILE}.
     */
    private static final String SORT_PROPERTY_TRAN_ID = "tranId";

    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionListService.class);

    /**
     * Spring Data JPA repository for the {@link Transaction} entity.
     * Replaces the CICS {@code STARTBR}/{@code READNEXT} browse on
     * the {@code TRANSACT} VSAM KSDS.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Constructor injection (AAP &sect;0.3.3 design pattern
     * &mdash; constructor injection only; no field
     * {@code @Autowired}, no setter injection).
     *
     * @param transactionRepository the Spring Data JPA repository
     *                              for the {@link Transaction}
     *                              entity; never {@code null}
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository,
                "transactionRepository must not be null");
    }

    /**
     * Returns a paginated transaction list, optionally narrowed by a
     * transaction-ID prefix filter.
     *
     * <p>Equivalent to {@code COTRN00C.cbl}'s {@code MAIN-PARA} +
     * {@code PROCESS-ENTER-KEY} + {@code PROCESS-PAGE-FORWARD}
     * flow. When {@code tranIdFilter} is null, empty, or
     * whitespace-only, this method browses the full
     * {@code TRANSACT} cluster in primary-key ASC order via
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}.
     * When a non-blank filter is supplied, the same underlying
     * browse is performed and the results are filtered client-side
     * to retain only transactions whose {@code tranId} starts with
     * the trimmed filter prefix &mdash; mirroring the COBOL
     * positioning read that copies the {@code TRNIDINI PIC X(16)}
     * screen input into {@code TRAN-ID} before issuing the
     * {@code STARTBR}, but rendered in the Java target as a
     * stable client-side prefix match.</p>
     *
     * <p>The page-number parameter is 0-indexed per Spring Data
     * convention; the COBOL source maintained
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} as 1-indexed and the
     * Java target normalises to 0-indexed paging. Negative values
     * are coerced to 0 (the COBOL source never produces a negative
     * page number; this is a defensive REST contract guard).</p>
     *
     * <h4>Empty-result handling</h4>
     * <p>When the underlying browse returns no rows (or the
     * filtered slice for the requested page is empty), the
     * verbatim message {@link #NO_RECORDS_FOUND_MSG} is logged at
     * INFO level (so operators can correlate empty responses with
     * their queries) and an empty {@link TransactionListDto} is
     * returned with the supplied {@code tranIdFilter} echoed back
     * on the {@code idFilter} component. Both {@code first} and
     * {@code last} are set to {@code true} on an empty result (a
     * zero-element collection is logically both the first and the
     * last page).</p>
     *
     * @param tranIdFilter the optional transaction-ID prefix filter;
     *                     {@code null}, empty, or whitespace-only
     *                     means &quot;list all transactions&quot;
     *                     (the COBOL {@code SPACES / LOW-VALUES}
     *                     branch of {@code PROCESS-ENTER-KEY} at
     *                     {@code COTRN00C.cbl}:L206-L207); leading
     *                     and trailing whitespace are trimmed
     *                     before the prefix match is applied
     * @param page         the 0-indexed page number; negative values
     *                     are coerced to 0 (defensive)
     * @return a non-{@code null} {@link TransactionListDto} with up
     *         to {@link #PAGE_SIZE} (10) rows plus the standard
     *         Spring Data paging metadata (total elements, total
     *         pages, first/last indicators)
     */
    @Transactional(readOnly = true)
    public TransactionListDto listTransactions(String tranIdFilter, int page) {
        // COBOL: COTRN00C:MAIN-PARA entry point. Defensive page
        // normalisation — Spring Data uses 0-indexed pages; clients
        // that pass a negative value get coerced to 0 (the COBOL
        // source maintains CDEMO-CT00-PAGE-NUM 1-indexed and never
        // produces a negative value).
        final int safePage = Math.max(0, page);

        // COBOL: COTRN00C:PROCESS-ENTER-KEY at L206-L207 —
        //   IF TRNIDINI OF COTRN0AI = SPACES OR LOW-VALUES
        //       MOVE LOW-VALUES TO TRAN-ID
        //   ELSE ...
        // Spaces/low-values branch: unfiltered browse.
        // Non-blank branch: trimmed prefix filter.
        final String normalizedFilter =
                (tranIdFilter == null || tranIdFilter.isBlank())
                        ? null
                        : tranIdFilter.trim();

        LOG.debug(
                "listTransactions tranIdFilter={} page={} size={}",
                normalizedFilter, safePage, PAGE_SIZE);

        if (normalizedFilter == null) {
            // Unfiltered path: native repository pagination preserves
            // the VSAM primary-key ASC browse order verbatim.
            return listUnfiltered(safePage);
        }
        // Filtered path: client-side prefix match because
        // TransactionRepository does not expose
        // findByTranIdStartingWith(...) per AAP §0.7.1 Minimal
        // Change Clause.
        return listWithPrefixFilter(normalizedFilter, safePage);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Executes the unfiltered paginated browse path.
     *
     * <p>Issues a single
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
     * call &mdash; the explicit derived query that preserves the
     * VSAM primary-key ASC browse order from
     * {@code COTRN00C.cbl} {@code STARTBR-TRANSACT-FILE} /
     * {@code READNEXT-TRANSACT-FILE}. The
     * {@link Page} envelope returned by Spring Data carries the
     * total-element count and the first/last page indicators
     * directly &mdash; no client-side post-processing required.</p>
     *
     * @param page the 0-indexed page number (already normalised
     *             non-negative by the public method)
     * @return a non-{@code null} DTO carrying the requested page;
     *         empty page rows when the journal is empty or the
     *         page is past the last page
     */
    private TransactionListDto listUnfiltered(int page) {
        // COBOL: COTRN00C:STARTBR-TRANSACT-FILE +
        //        READNEXT-TRANSACT-FILE (unfiltered ASC browse).
        final Pageable pageable = PageRequest.of(
                page,
                PAGE_SIZE,
                Sort.by(SORT_PROPERTY_TRAN_ID).ascending());

        final Page<Transaction> result =
                transactionRepository.findByOrderByTranIdAsc(pageable);

        if (result.isEmpty()) {
            // COBOL: COTRN00C:STARTBR L605-L611 NOTFND branch +
            //        READNEXT L639-L645 ENDFILE branch — empty
            //        result handling. The COBOL source uses
            //        per-direction messages ("You are at the top of
            //        the page..." / "You have reached the bottom of
            //        the page...") whereas the REST world
            //        standardises on the verbatim
            //        NO_RECORDS_FOUND_MSG across sibling list
            //        services per AAP §0.7.1 alignment.
            LOG.info("{}", NO_RECORDS_FOUND_MSG);
            return emptyDto(page, /* idFilter */ null);
        }

        return toDto(result, /* idFilter */ null);
    }

    /**
     * Executes the prefix-filtered browse path with bounded
     * client-side filtering.
     *
     * <p>Because the repository does NOT expose a
     * {@code findByTranIdStartingWith(...)} derived query (per AAP
     * &sect;0.7.1 Minimal Change Clause), this path iterates the
     * underlying {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
     * in chunks of {@link #PAGE_SIZE} and accumulates entries whose
     * {@code tranId} starts with the supplied prefix. Iteration
     * stops as soon as one of the following holds:</p>
     * <ul>
     *   <li>The underlying browse is exhausted
     *       ({@link Page#isLast()}).</li>
     *   <li>{@link #MAX_PREFIX_SCAN_PAGES} underlying pages have
     *       been scanned (defensive cap against adversarial
     *       prefixes).</li>
     *   <li>Enough filtered rows have been accumulated to satisfy
     *       the requested page boundary plus one extra row (used to
     *       compute the {@code last} flag) &mdash; an early-exit
     *       optimisation that avoids scanning further pages once
     *       the caller's window has been fully materialised.</li>
     * </ul>
     *
     * <p>The accumulated filtered list is then sliced to the
     * requested page boundary, the slice is converted to
     * {@link TransactionListDto.TransactionRow} entries with PAN
     * masking applied at the DTO boundary, and the result is
     * packaged as a {@link TransactionListDto} with the supplied
     * filter echoed on the {@code idFilter} component.</p>
     *
     * <p><b>Note on totals.</b> Because we scan only up to
     * {@link #MAX_PREFIX_SCAN_PAGES} underlying pages, the
     * {@code totalElements} reflects the filtered row count
     * observed within the scan window, not necessarily the
     * absolute total across the entire journal. The
     * {@code totalPages} value is derived from the observed
     * {@code totalElements} so the client's page-navigation
     * arithmetic remains consistent with what it can actually
     * page through.</p>
     *
     * @param prefix the trimmed, non-blank transaction-ID prefix
     * @param page   the 0-indexed page number (already normalised
     *               non-negative by the public method)
     * @return a non-{@code null} DTO carrying the requested page
     *         of filtered rows; empty when no rows match the
     *         prefix within the scan window
     */
    private TransactionListDto listWithPrefixFilter(String prefix, int page) {
        // COBOL: COTRN00C:PROCESS-ENTER-KEY at L209-L218 (numeric
        //        check + positioning read) — the COBOL source uses
        //        the TRNIDINI screen input as a STARTBR positioning
        //        key; the Java target interprets it as a prefix
        //        match on the result of the underlying ASC browse
        //        (per AAP §0.4.1 "filter by transaction ID").
        final Pageable underlyingPageable = PageRequest.of(
                0,
                PAGE_SIZE,
                Sort.by(SORT_PROPERTY_TRAN_ID).ascending());

        // Accumulator for filtered rows discovered across the
        // underlying ASC browse. The requested slice starts at
        // index (page * PAGE_SIZE) and runs for PAGE_SIZE entries;
        // we also want ONE more entry beyond the slice to detect
        // whether further pages exist (the `last` flag).
        final long sliceStart = (long) page * PAGE_SIZE;
        final long sliceEndExclusive = sliceStart + PAGE_SIZE;
        // Early-exit target: stop scanning once we have observed
        // (sliceEndExclusive + 1) filtered rows — enough to fill
        // the requested page AND know whether a next page exists.
        final long earlyExitObservedCount = sliceEndExclusive + 1L;

        final List<Transaction> accumulated = new ArrayList<>();
        int underlyingPagesScanned = 0;
        Pageable cursor = underlyingPageable;
        boolean underlyingExhausted = false;

        while (underlyingPagesScanned < MAX_PREFIX_SCAN_PAGES) {
            // COBOL: COTRN00C:READNEXT-TRANSACT-FILE — one underlying
            //        page = one paragraph of READNEXT calls in the
            //        legacy source.
            final Page<Transaction> chunk =
                    transactionRepository.findByOrderByTranIdAsc(cursor);
            underlyingPagesScanned++;

            for (Transaction tx : chunk.getContent()) {
                final String tranId = tx.getTranId();
                if (tranId != null && tranId.startsWith(prefix)) {
                    accumulated.add(tx);
                }
            }

            if (chunk.isLast()) {
                underlyingExhausted = true;
                break;
            }
            if (accumulated.size() >= earlyExitObservedCount) {
                // Early exit: we have already materialised more
                // filtered rows than the caller's window needs.
                // Further scanning would only refine totals
                // beyond what the client can navigate to.
                break;
            }
            cursor = chunk.nextPageable();
        }

        if (accumulated.isEmpty()) {
            // No rows match the prefix within the scan window.
            // COBOL: COTRN00C:STARTBR NOTFND branch (L605-L611) —
            // empty browse result. AAP §0.7.1 standardises on the
            // NO_RECORDS_FOUND_MSG verbatim message across sibling
            // list services.
            LOG.info("{}", NO_RECORDS_FOUND_MSG);
            return emptyDto(page, prefix);
        }

        // Compute paging metadata from the observed filtered count.
        // When the underlying browse was exhausted, this is the
        // absolute total; when we early-exited, this is the
        // observed total within the scan window. In either case the
        // computed totalPages reflects what the client can paginate
        // through given the rows currently materialised.
        final long observedTotalElements = accumulated.size();
        final int observedTotalPages = computeTotalPages(observedTotalElements);

        // Slice the accumulated filtered list to the requested
        // page. If the requested page is past the end of the
        // filtered slice, return an empty page (consistent with
        // Spring Data's PageImpl behaviour).
        if (sliceStart >= observedTotalElements) {
            LOG.info("{}", NO_RECORDS_FOUND_MSG);
            return new TransactionListDto(
                    Collections.emptyList(),
                    page,
                    PAGE_SIZE,
                    observedTotalElements,
                    observedTotalPages,
                    /* first */ page == 0,
                    /* last  */ true,
                    prefix);
        }

        final int fromIndex = (int) sliceStart;
        final int toIndex =
                (int) Math.min((long) accumulated.size(), sliceEndExclusive);
        final List<Transaction> pageEntities =
                accumulated.subList(fromIndex, toIndex);

        // Determine the `last` flag. The slice is the last page
        // within the observed window iff the slice's exclusive
        // upper bound consumes the accumulated list. When the
        // underlying browse was exhausted, this is genuinely the
        // last page; when we early-exited, this is the last page
        // within the safety-capped window (consistent with the
        // observedTotalElements / observedTotalPages reported on
        // the DTO).
        final boolean isFirst = page == 0;
        final boolean isLast = toIndex >= accumulated.size();
        // underlyingExhausted is informational only — referenced
        // by callers via the observed totals; the boolean is not
        // emitted on the response per the existing DTO contract.
        if (!underlyingExhausted && isLast) {
            LOG.debug(
                    "Prefix-filter scan stopped early after {} underlying pages "
                            + "(safety cap {} / matches {}); reported last=true "
                            + "reflects the observed window.",
                    underlyingPagesScanned,
                    MAX_PREFIX_SCAN_PAGES,
                    accumulated.size());
        }

        final List<TransactionListDto.TransactionRow> rows =
                pageEntities.stream().map(this::toRow).toList();

        return new TransactionListDto(
                rows,
                page,
                PAGE_SIZE,
                observedTotalElements,
                observedTotalPages,
                isFirst,
                isLast,
                prefix);
    }

    /**
     * Converts a non-empty Spring Data {@link Page} of
     * {@link Transaction} entities to a {@link TransactionListDto}.
     *
     * <p>Used by the unfiltered path where the repository's
     * {@link Page} envelope carries the authoritative totals and
     * first/last indicators directly. Each entity is converted to
     * a {@link TransactionListDto.TransactionRow} via
     * {@link #toRow(Transaction)} with PAN masking applied at the
     * DTO boundary.</p>
     *
     * @param result   the non-empty page of transaction entities
     * @param idFilter the filter to echo on the response DTO; may
     *                 be {@code null} (unfiltered path)
     * @return a non-{@code null} DTO with the page's rows and
     *         metadata
     */
    private TransactionListDto toDto(Page<Transaction> result, String idFilter) {
        // COBOL: COTRN00C:POPULATE-TRAN-DATA — per-row screen field
        //        population. The Java target produces one DTO row
        //        per Transaction entity; the DTO layer is
        //        responsible for PAN masking on the cardNumber
        //        component per AAP §0.6.6.
        final List<TransactionListDto.TransactionRow> rows =
                result.getContent().stream().map(this::toRow).toList();

        return new TransactionListDto(
                rows,
                result.getNumber(),          // 0-indexed page number
                PAGE_SIZE,                   // fixed page size per AAP
                result.getTotalElements(),   // total rows across all pages
                result.getTotalPages(),      // total page count
                result.isFirst(),            // first-page indicator
                result.isLast(),             // last-page indicator
                idFilter);                   // echo the filter back
    }

    /**
     * Constructs an empty {@link TransactionListDto} for
     * &quot;no records found&quot; responses.
     *
     * <p>The DTO carries an empty {@code rows} list,
     * {@code totalElements = 0L}, {@code totalPages = 0}, and
     * {@code first = true} / {@code last = true} (a zero-element
     * collection is logically both the first and the last page).
     * The {@code idFilter} is echoed back so the client can
     * correlate the empty result with its request.</p>
     *
     * @param page     the requested (already non-negative) page
     *                 number, echoed back on the response
     * @param idFilter the supplied transaction-ID filter, echoed
     *                 back; may be {@code null} (unfiltered
     *                 branch)
     * @return a non-{@code null} {@link TransactionListDto} with
     *         no rows
     */
    private TransactionListDto emptyDto(int page, String idFilter) {
        return new TransactionListDto(
                Collections.emptyList(),
                page,
                PAGE_SIZE,
                0L,                          // totalElements
                0,                           // totalPages
                true,                        // first
                true,                        // last
                idFilter);
    }

    /**
     * Computes the total page count given the observed total
     * element count.
     *
     * <p>Mirrors the standard Spring Data
     * {@code PageImpl#getTotalPages()} arithmetic for consistency
     * with the unfiltered path: {@code ceil(totalElements /
     * PAGE_SIZE)} when {@code totalElements &gt; 0}; otherwise 0.</p>
     *
     * @param totalElements the observed filtered element count
     * @return the corresponding total page count
     */
    private static int computeTotalPages(long totalElements) {
        if (totalElements <= 0L) {
            return 0;
        }
        // Equivalent to (int) Math.ceil(totalElements / (double) PAGE_SIZE)
        // computed without floating-point arithmetic for exactness.
        final long pages = (totalElements + PAGE_SIZE - 1) / PAGE_SIZE;
        // Defensive narrowing — totalElements is bounded by the
        // safety cap above (MAX_PREFIX_SCAN_PAGES * PAGE_SIZE) so
        // this never overflows int range in practice.
        return (int) Math.min(pages, (long) Integer.MAX_VALUE);
    }

    /**
     * Maps a {@link Transaction} JPA entity to a
     * {@link TransactionListDto.TransactionRow}.
     *
     * <p>PAN masking is applied at the producer here via
     * {@link #maskPan(String)} so the row always emits the
     * canonical {@code ************nnnn} form per AAP &sect;0.6.6
     * PCI-DSS Requirement 3.4.1. This is the primary line of
     * defense; the DTO's own {@code toString()} further applies
     * the same masking as defense in depth (the masking helper is
     * idempotent &mdash; a value already in masked form passes
     * through unchanged).</p>
     *
     * @param t the {@link Transaction} entity to convert; never
     *          {@code null}
     * @return a non-{@code null}
     *         {@link TransactionListDto.TransactionRow} with the
     *         PAN masked
     */
    private TransactionListDto.TransactionRow toRow(Transaction t) {
        // COBOL: COTRN00C:POPULATE-TRAN-DATA — per-row screen field
        //        population. The legacy 3270 row carried
        //        TRNIDnnI / TDATEnnI / TDESCnnI / TAMTnnnI only;
        //        the Java DTO surfaces additional non-sensitive
        //        fields (transactionType, transactionCategory,
        //        source) per AAP §0.7.3 information enrichment.
        return new TransactionListDto.TransactionRow(
                t.getTranId(),
                // PAN masking — PCI-DSS Requirement 3.4.1 enforced
                // at the producer (this service) so the wire DTO
                // never carries the full PAN.
                maskPan(t.getTranCardNum()),
                t.getTranProcTs(),
                t.getTranTypeCd(),
                t.getTranCatCd(),
                t.getTranSource(),
                t.getTranDesc(),
                t.getTranAmt());
    }

    /**
     * Masks a card-number string for PCI-DSS-compliant
     * transmission.
     *
     * <p>Applies the canonical {@code "************XXXX"} format
     * (12 leading asterisks + the trailing 4 characters of the
     * input) matching:</p>
     * <ul>
     *   <li>The PCI-DSS v4.0 Requirement 3.4.1 PAN-masking
     *       guidance (a maximum of the first 6 and last 4 digits
     *       may be displayed; CardDemo's policy is to display only
     *       the last 4 to be conservative, AAP &sect;0.6.6).</li>
     *   <li>The {@link TransactionListDto} producer expectation
     *       (the cardNumber is pre-masked at the producer so only
     *       the last 4 digits are retained; the leading 12
     *       characters are {@code '*'} per industry-standard PAN
     *       masking).</li>
     *   <li>The defensive masking applied by
     *       {@link TransactionListDto.TransactionRow#toString()}
     *       (same 12-asterisk + last-4-digit format) so the
     *       producer's masking is idempotent under the DTO's
     *       {@code toString()}.</li>
     * </ul>
     *
     * <p>Defensive handling:</p>
     * <ul>
     *   <li>{@code null} input &rarr; returns {@code "****"}
     *       (fully masked) rather than {@code null} or empty
     *       string &mdash; guarantees the response never carries a
     *       {@code null} PAN in a list row.</li>
     *   <li>Input shorter than 4 characters &rarr; returns
     *       {@code "****"} (fully masked) rather than leaking
     *       partial digit information.</li>
     *   <li>Input of any other length &rarr; returns the
     *       12-asterisk-prefixed trailing 4 characters of the
     *       input. The COBOL source always supplies a
     *       16-character {@code TRAN-CARD-NUM PIC X(16)} value, so
     *       this branch is the overwhelmingly common path.</li>
     * </ul>
     *
     * <p>This helper mirrors the implementation in
     * {@code CardListService.maskPan(String)} to keep PAN-masking
     * behavior identical across paginated list services
     * (consistency requirement for log-line redaction and DTO
     * serialization).</p>
     *
     * @param pan the card-number string to mask; may be
     *            {@code null} or shorter than 4 characters
     *            (defensive)
     * @return a non-{@code null} masked representation; never
     *         leaks more than the last 4 characters of the input
     */
    // COBOL: COTRN00C has no equivalent — the legacy 3270
    // transaction-list screen did not display TRAN-CARD-NUM at all
    // (only TRNIDnnI, TDATEnnI, TDESCnnI, TAMTnnnI). PAN masking
    // is a Java-side PCI-DSS guard rail added per AAP §0.6.6 so
    // the richer REST DTO can surface a masked card number for
    // client convenience without leaking the full PAN.
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            // Defensive fallback — never leak any digit on a
            // malformed input. PCI-DSS v4.0 3.4.1 (mask all but
            // the last 4) is satisfied vacuously when no digits
            // are revealed.
            return "****";
        }
        // PCI-DSS v4.0 Requirement 3.4.1: mask all but the last 4
        // characters. 12 asterisks + 4 trailing characters matches
        // the TransactionListDto producer/consumer contract for
        // the masked PAN.
        return "************" + pan.substring(pan.length() - 4);
    }
}
