/*
 * ******************************************************************
 * Program     : PageResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Pagination metadata replacing screen-resident page state; no server-side session.
 * Source      : app/cpy-bms/COCRDLI.CPY:60, COTRN00.CPY:60, COUSR00.CPY:60 +
 *               app/cbl/COCRDLIC.cbl:239-244, COTRN00C.cbl:65-68 @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable pagination metadata returned beside a page of rows, replacing the screen-resident page state of the
 * three legacy CardDemo list transactions.
 *
 * <p>The legacy card list, transaction list and user list each kept their paging state on the 3270 screen and
 * in the pseudo-conversational COMMAREA, so the state survived between terminal interactions on the server.
 * The target replaces {@code RETURN TRANSID ... COMMAREA} with stateless request handling and keeps no session
 * state on the server, so paging state splits in two: the inbound
 * half travels as request parameters, and the outbound half - the current page number, whether a further page
 * exists, the page size actually applied, and optionally the keyset boundaries of the page - is carried by this
 * type in the response body.
 *
 * <p>This is a pure data holder. It performs no comparison, no normalisation, no mapping, no paging and no
 * query, and it does not compute the next-page indicator: the calling service determines that and supplies it.
 * Every field is assigned once in the constructor and never mutated afterwards, the row list is defensively
 * copied on the way in and exposed only as an unmodifiable view, and the class is {@code final}, so an instance
 * is safe to share across threads without further synchronisation. There are no side effects of any kind.
 *
 * <p><b>Error modes.</b> Nothing is thrown during normal operation. The constructors reject
 * structurally impossible arguments with {@code IllegalArgumentException} whose message names the
 * offending field; see the constructor documentation for the exact conditions. Serialisation is
 * outbound only - the type deliberately declares no no-argument constructor and no setter - because
 * pagination metadata is produced by the server and never accepted from a client.</p>
 *
 * <p><b>Source attribution correction - severity Medium.</b> The technical specification attributes
 * this type's pagination metadata to {@code app/cpy/COCOM01Y.cpy}. That attribution is incorrect.
 * The COMMAREA copybook was read in full and a case-insensitive search for the string "page" across
 * it returns nothing: it declares exactly sixteen elementary fields - {@code CDEMO-FROM-TRANID},
 * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID}, {@code CDEMO-TO-PROGRAM},
 * {@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE}, {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-CUST-ID},
 * {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME}, {@code CDEMO-CUST-LNAME},
 * {@code CDEMO-ACCT-ID}, {@code CDEMO-ACCT-STATUS}, {@code CDEMO-CARD-NUM},
 * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} - and not one of them is a page-number field
 * or a next-page flag. The likely origin of the error is textual adjacency: each list program
 * declares its own paging fields as level-05 items placed immediately after its
 * {@code COPY COCOM01Y.} statement, so those items extend the copybook's
 * {@code 01 CARDDEMO-COMMAREA} group while being declared in the program rather than in the
 * copybook. See {@code app/cbl/COTRN00C.cbl}:61 followed by 62-70, and
 * {@code app/cbl/COUSR00C.cbl}:66 followed by 67-75. The sources cited below are the real ones, and
 * the finding is reported for the repository-root decision log.</p>
 *
 * <p><b>Source A - the BMS symbolic maps.</b> The three paging screens agree on neither the field
 * name nor the field width, which is a verified inconsistency in the legacy corpus rather than an
 * inference:</p>
 * <ul>
 *   <li>{@code app/cpy-bms/COCRDLI.CPY}:60 declares {@code PAGENOI} as {@code PIC X(3)}.</li>
 *   <li>{@code app/cpy-bms/COTRN00.CPY}:60 declares {@code PAGENUMI} as {@code PIC X(8)}.</li>
 *   <li>{@code app/cpy-bms/COUSR00.CPY}:60 declares {@code PAGENUMI} as {@code PIC X(8)}.</li>
 * </ul>
 * <p>Because the three widths disagree, no single fixed-width character contract can be honoured on
 * the wire. The page number is therefore carried as an {@code int}, which represents every value
 * those three widths can hold without loss, and each screen is left to render it under its own
 * mask.</p>
 *
 * <p><b>Source B - program WORKING-STORAGE, and a genuine divergence.</b> The two reference paging
 * programs use mutually incompatible sentinels for "no next page":</p>
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl}:242-244 declares {@code WS-CA-NEXT-PAGE-IND} as
 *       {@code PIC X(1)} with {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} and
 *       {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'}. The absent-page sentinel is {@code LOW-VALUES},
 *       that is binary zeros, and specifically not spaces, which makes the field a three-state
 *       indicator: binary zeros, {@code 'Y'}, or anything else.</li>
 *   <li>{@code app/cbl/COTRN00C.cbl}:66-68 declares {@code CDEMO-CT00-NEXT-PAGE-FLG} as
 *       {@code PIC X(01) VALUE 'N'} with {@code 88 NEXT-PAGE-YES VALUE 'Y'} and
 *       {@code 88 NEXT-PAGE-NO VALUE 'N'}. Here the absent-page sentinel is the literal
 *       {@code 'N'}.</li>
 * </ul>
 * <p>The two conventions are deliberately not unified, because unifying them would require choosing
 * one sentinel and thereby misrepresenting the other program. Instead <b>no legacy sentinel
 * character is transported on the wire at all</b>: the indicator is modelled as a {@code boolean},
 * and each adapter maps that boolean onto whichever sentinel its own screen contract requires.
 * {@code app/cbl/COCRDLIC.cbl}:239-241 additionally declares {@code WS-CA-LAST-PAGE-DISPLAYED} as
 * {@code PIC 9(1)} with {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} and
 * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9}; that field is a screen-resident scroll marker, so it
 * has no counterpart here - it is precisely the session state transformation rule 7 forbids, and it
 * is derivable by a caller from the next-page indicator.</p>
 *
 * <p><b>Page sizes.</b> Three sizes exist in the source and no more. They are exposed as the three
 * constants on this class, each carrying its own anchor: 7 for the card list from
 * {@code app/cbl/COCRDLIC.cbl}:177-178, 10 for the transaction list from the ten-row table in
 * {@code app/cpy-bms/COTRN00.CPY}, and 10 for the user list from {@code app/cbl/COUSR00C.cbl}:57.
 * <b>There is no fourth page size.</b> In particular the twenty-lines-per-page figure found
 * elsewhere in the corpus is a batch report-line count belonging to the transaction report program
 * and is unrelated to these three online lists; it must never be used as a page size here.</p>
 *
 * <p><b>Page numbering is one-based.</b> {@code app/cbl/COCRDLIC.cbl}:237-238 declares
 * {@code WS-CA-SCREEN-NUM} as {@code PIC 9(1)} with {@code 88 CA-FIRST-PAGE VALUE 1}, so the first
 * page is numbered 1 rather than 0. The constructors enforce that lower bound.</p>
 *
 * <p><b>Total element count and total page count: Not available.</b> Neither is offered, because no
 * source field for either exists. The legacy programs page by browsing a VSAM key forwards and
 * backwards; they learn only whether a further record exists beyond the current screen and never
 * compute a cardinality. Publishing a total would require a {@code COUNT} aggregate the source never
 * performs, so the figure would be an invention rather than a migration. What would be needed to
 * supply them: an explicit product decision to add a count, an agreed behaviour for the extra
 * database aggregate on every page request, and a corresponding entry in the decision log recording
 * the deviation from parity.</p>
 *
 * <p><b>Row ordering is significant and deterministic.</b> Rows are held in a {@code List} and are
 * returned in exactly the order supplied. No hash-ordered collection is used anywhere in this type,
 * so iteration order can never vary between runs or between JVMs.</p>
 *
 * <p><b>Building, testing and troubleshooting.</b> This type is part of the single Maven module at
 * the repository root; build it with {@code ./mvnw -B clean compile} and exercise it with
 * {@code ./mvnw -B clean test}. The module compiles under {@code -Xlint:all} with {@code -Werror}, so
 * any compiler warning introduced here fails the build rather than being reported. Unit tests for
 * this type live under {@code src/test/java/com/cardemo/unit/model}. The defaults this type
 * publishes are the four constants below, namely the page sizes 7, 10 and 10 and the page-number
 * origin 1; it reads no configuration file, no environment variable and no system property, so there
 * is nothing to configure. The failure most likely to be seen in practice is an
 * {@code IllegalArgumentException} from a calling service that assembled a page whose row count
 * exceeds the page size it passed, or that passed a zero-based page number; the exception message
 * names the offending field and reports the value received, so the remedy is to correct the caller
 * rather than to relax the check.</p>
 *
 * <p><b>Intended usage.</b> The type is generic so that a single implementation serves all three
 * paged lists: the card list returns PageResponse&lt;CardDto&gt; with a page size of 7, the
 * transaction list returns PageResponse&lt;TransactionDto&gt; with a page size of 10, and the user
 * list returns PageResponse&lt;UserSecurityDto&gt; with a page size of 10. Internally the rows are
 * held as a List&lt;T&gt;. Nothing in this class inspects, converts or orders a row, so any row type
 * is acceptable.</p>
 *
 * @param <T> the row type carried by this page, for example the card, transaction or user
 *            projection appropriate to the list being returned; this type imposes no bound on
 *            {@code T} and never inspects a row
 */
public final class PageResponse<T> {

    /**
     * Rows displayed per page by the card list, namely 7, from
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:177-178}.
     */
    public static final int PAGE_SIZE_CARD_LIST = 7;

    /**
     * Rows displayed per page by the transaction list, namely 10, from the row loop bound
     * {@code UNTIL WS-IDX > 10} at {@code app/cbl/COTRN00C.cbl:290}.
     */
    public static final int PAGE_SIZE_TRANSACTION_LIST = 10;

    /**
     * Rows displayed per page by the user list, namely 10, from {@code USER-REC OCCURS 10 TIMES} at
     * {@code app/cbl/COUSR00C.cbl:57}.
     */
    public static final int PAGE_SIZE_USER_LIST = 10;

    /**
     * The number of the first page, namely 1, because legacy page numbering is one-based.
     */
    public static final int FIRST_PAGE_NUMBER = 1;

    /**
     * The rows of this page, in the order supplied, held as an unmodifiable view over a private defensive copy.
     * Never {@code null}; may be empty. The legacy counterpart is the fixed row table of whichever list map is
     * being served: seven groups on {@code app/cpy-bms/COCRDLI.CPY}, ten on {@code app/cpy-bms/COTRN00.CPY} and
     * ten on {@code app/cpy-bms/COUSR00.CPY}.
     */
    private final List<T> rows;

    /**
     * The one-based number of this page, carried on the screen as {@code PAGENOI PIC X(3)} at
     * {@code app/cpy-bms/COCRDLI.CPY:60} and as {@code PAGENUMI PIC X(8)} at
     * {@code app/cpy-bms/COTRN00.CPY:60} and {@code app/cpy-bms/COUSR00.CPY:60}.
     */
    private final int pageNumber;

    /**
     * The page size actually applied when this page was assembled, which is the maximum number of rows the page
     * could have contained. It is one of the three parity sizes published above, never a client-chosen value.
     */
    private final int pageSize;

    /**
     * Whether a further page exists after this one, as determined by the calling service. It replaces
     * {@code WS-CA-NEXT-PAGE-IND PIC X(1)} at {@code app/cbl/COCRDLIC.cbl:242}, whose condition names at
     * {@code app/cbl/COCRDLIC.cbl:243-244} distinguish {@code LOW-VALUES} from {@code 'Y'}.
     */
    private final boolean nextPageAvailable;

    /**
     * The key of the first row of this page, or {@code null} when the page carries no boundary key. It replaces
     * {@code WS-CA-FIRST-CARDKEY} at {@code app/cbl/COCRDLIC.cbl:233-235}, which the legacy program held across
     * pseudo-conversational turns in order to reposition a backward browse.
     */
    private final String firstKey;

    /**
     * The key of the last row of this page, or {@code null} when the page carries no boundary key. It replaces
     * {@code WS-CA-LAST-CARDKEY} at {@code app/cbl/COCRDLIC.cbl:230-232}, the forward-browse counterpart.
     */
    private final String lastKey;

    /**
     * Constructs a page that carries no keyset boundaries, for callers that page by number alone.
     *
     * @param rows the rows of this page in display order.
     * @param pageNumber the one-based number of this page.
     * @param pageSize the page size applied; must be at least 1 and must not be less than the number of rows
     * supplied
     * @param nextPageAvailable {@code true} when a further page exists after this one
     * @throws IllegalArgumentException if any argument violates the conditions above.
     */
    public PageResponse(final List<T> rows, final int pageNumber, final int pageSize,
            final boolean nextPageAvailable) {
        this(rows, pageNumber, pageSize, nextPageAvailable, null, null);
    }

    /**
     * Constructs a page together with the keyset boundaries the legacy programs used to page.
     *
     * @param rows the rows of this page in display order.
     * @param pageNumber the one-based number of this page.
     * @param pageSize the page size applied; must be at least 1 and must not be less than the number of rows
     * supplied, because a page can never hold more rows than the size applied to it
     * @param nextPageAvailable {@code true} when a further page exists after this one, as determined by the
     * calling service.
     * @param firstKey the key of the first row, or {@code null} when none is reported
     * @param lastKey the key of the last row, or {@code null} when none is reported
     * @throws IllegalArgumentException if {@code rows} is {@code null} or contains a {@code null} element, if
     * {@code pageNumber} is less than {@link #FIRST_PAGE_NUMBER}, if {@code pageSize} is less than 1, or if
     * more rows were supplied than {@code pageSize} permits.
     */
    public PageResponse(final List<T> rows, final int pageNumber, final int pageSize,
            final boolean nextPageAvailable, final String firstKey, final String lastKey) {

        if (rows == null) {
            throw new IllegalArgumentException(
                    "rows must not be null; supply an empty list to represent a page with no rows");
        }
        if (pageNumber < FIRST_PAGE_NUMBER) {
            throw new IllegalArgumentException("pageNumber must be at least " + FIRST_PAGE_NUMBER
                    + " because page numbering is one-based, but was " + pageNumber);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1, but was " + pageSize);
        }
        if (rows.size() > pageSize) {
            throw new IllegalArgumentException("rows holds " + rows.size()
                    + " elements, which exceeds pageSize " + pageSize + "; a page cannot hold more rows than its size");
        }

        // Pre-sized so the copy is allocated once at its final length and is never resized while it
        // is built. The invariant checked immediately above proves the page size is an upper bound on
        // the row count, so this reservation stays bounded by the page size while never reserving
        // space that a partial or empty page could not use.
        final List<T> defensiveCopy = new ArrayList<>(rows.size());
        for (final T row : rows) {
            if (row == null) {
                // defensiveCopy.size() is the index within rows of the element being rejected.
                throw new IllegalArgumentException(
                        "rows must not contain a null element, but index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(row);
        }

        this.rows = Collections.unmodifiableList(defensiveCopy);
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.nextPageAvailable = nextPageAvailable;
        this.firstKey = firstKey;
        this.lastKey = lastKey;
    }

    /**
     * Returns the rows of this page in the order they were supplied.
     *
     * @return an unmodifiable, order-preserving view of this page's rows, never {@code null}
     */
    public List<T> getRows() {
        return rows;
    }

    /**
     * Returns the one-based number of this page.
     *
     * @return the page number, always at least {@link #FIRST_PAGE_NUMBER}
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * Returns the page size that was applied when this page was assembled.
     *
     * @return the page size applied, always at least 1
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Returns whether a further page exists after this one.
     *
     * @return {@code true} when a further page exists, {@code false} when this is the last page
     */
    public boolean isNextPageAvailable() {
        return nextPageAvailable;
    }

    /**
     * Returns the key of the first row of this page, if one was reported.
     *
     * @return the opaque first-row key, or {@code null} when no boundary key is reported
     */
    public String getFirstKey() {
        return firstKey;
    }

    /**
     * Returns the key of the last row of this page, if one was reported.
     *
     * @return the opaque last-row key, or {@code null} when no boundary key is reported
     */
    public String getLastKey() {
        return lastKey;
    }

    /**
     * Compares this page with another for value equality across every field, rows included.
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} when {@code other} is a page with equal rows in the same order and equal page
     * number, page size, next-page indicator and boundary keys
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PageResponse<?> that)) {
            return false;
        }
        return this.pageNumber == that.pageNumber
                && this.pageSize == that.pageSize
                && this.nextPageAvailable == that.nextPageAvailable
                && this.rows.equals(that.rows)
                && Objects.equals(this.firstKey, that.firstKey)
                && Objects.equals(this.lastKey, that.lastKey);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)} over every field.
     *
     * @return the hash code for this page
     */
    @Override
    public int hashCode() {
        return Objects.hash(rows, pageNumber, pageSize, nextPageAvailable, firstKey, lastKey);
    }

    /**
     * Returns a diagnostic rendering of the page metadata only.
     *
     * @return a locale-independent, personally-identifiable-information-free summary of this page
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PageResponse[pageNumber=%d, pageSize=%d, nextPageAvailable=%b]",
                pageNumber, pageSize, nextPageAvailable);
    }
}
