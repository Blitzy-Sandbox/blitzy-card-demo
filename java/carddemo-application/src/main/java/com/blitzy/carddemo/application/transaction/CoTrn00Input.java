/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and the modules it reads). This brings in java.lang.String — the
// type of the searchTransactionId component and of every entry in rowSelections — together
// with java.util.List and java.util.ArrayList (used by the empty() factory and the
// defensive List.copyOf in the compact constructor), and java.util.Objects (used by
// requireNonNull for non-null validation). It also brings in the exception classes
// IllegalArgumentException and NullPointerException raised by the compact constructor's
// invariant checks. No additional imports are required or permitted on this file (the
// internal_imports list in the file schema is empty and the only external import allowed
// is java.base itself).
import module java.base;

/**
 * BMS input record carrying every value received from the 3270 terminal for the
 * <strong>COTRN00</strong> (Transaction List) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN00.bms} (mapset {@code COTRN00},
 *       map {@code COTRN0A}, size 24x80, FREEKB, ALARM).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN00.CPY} (input group
 *       {@code 01 COTRN0AI} containing 10 SEL/TRNID/TDATE/TDESC/TAMT row clusters,
 *       the TRNIDIN search field, header echoes, and ERRMSG).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN00C.cbl} (transaction
 *       {@code CT00}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the input view of the BMS symbolic structure
 * {@code COTRN0AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn00C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Transaction-list semantics</h2>
 * <p>COTRN00 is the paginated <em>list / browse</em> screen for transactions. On
 * entry the operator may:
 * <ol>
 *   <li>Type a transaction identifier into the {@code TRNIDIN} field (mapped to
 *       {@link #searchTransactionId()}) and press ENTER to <em>reposition</em> the
 *       list at that key &mdash; equivalent to the COBOL
 *       {@code EXEC CICS STARTBR ... RIDFLD(TRNIDINI)} call.</li>
 *   <li>Type {@code 'S'} (uppercase or lowercase) into one of the ten SEL columns
 *       ({@code SEL0001..SEL0010}, mapped to {@link #rowSelections()} positions
 *       0&hellip;9) to mark that row for detailed view. The COBOL
 *       {@code PROCESS-ENTER-KEY} paragraph scans the rows in order and dispatches
 *       to {@code COTRN01C} (Transaction View) on the first match.</li>
 *   <li>Press <strong>PF7</strong> to paginate backward or <strong>PF8</strong> to
 *       paginate forward (mapped to the {@link AidKey} component).</li>
 *   <li>Press <strong>PF3</strong> to return to the main menu ({@code COMEN01C}).</li>
 *   <li>Press <strong>PF4</strong> to clear filters and start a fresh listing.</li>
 * </ol>
 * Any other AID key produces an invalid-key error in the controller.
 *
 * <h2>Why only three components for twelve BMS leaves?</h2>
 * <p>The COBOL {@code COTRN0AI} structure has 1 search field + 10 selection flags
 * + 1 AID code &mdash; twelve effective leaves. The COBOL idiom is to declare those
 * ten selection slots as separate {@code SEL0001I..SEL0010I} {@code PIC X(1)} fields
 * with no array indexing (BMS does not support {@code OCCURS} natively). Per AAP
 * &sect;0.3.2 the Java translation collapses those ten parallel-named leaves into a
 * single {@link java.util.List List&lt;String&gt;}; the COBOL fixed cardinality of
 * ten is enforced by the compact constructor's size invariant
 * ({@link #ROWS_PER_PAGE} entries exactly). The header echo fields ({@code TRNNAME},
 * {@code TITLE01}, {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME},
 * {@code PAGENUM}) and the row display columns ({@code TRNID01..10},
 * {@code TDATE01..10}, {@code TDESC01..10}, {@code TAMT001..010}) are deliberately
 * <em>not</em> represented on this <em>input</em> DTO: they are display-only
 * {@code ATTRB=(ASKIP,FSET,NORM)} fields populated by {@code SEND-MAP} and ignored
 * by {@code RECEIVE-MAP}. The COBOL controller never reads them back, so the Java
 * input DTO omits them. This is consistent with the AAP &sect;0.7.1 rule that
 * BMS-to-Java translation preserves the program's observable behavior and not
 * the literal BMS field list.
 *
 * <h2>Null vs empty discipline</h2>
 * <p>This record is <em>strict</em> about nulls: the compact constructor rejects any
 * {@code null} component with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}, and any {@code null}
 * entry inside the {@code rowSelections} list with an
 * {@link IllegalArgumentException}. The agent prompt specifies that an unselected
 * row is represented by the empty {@link String} {@code ""} (or by a single space
 * character, which the controller treats as equivalent), never by {@code null}. The
 * legacy COBOL idiom is that an unselected SEL field arrives as SPACES; the Java
 * convention here is that the upstream caller (the CICS RECEIVE-MAP adapter in
 * future code, or the operator-driven test harness today) normalizes those SPACES to
 * {@code ""} or leaves them as a literal {@code " "} string before constructing this
 * record. Either form satisfies {@link #firstSelectedRow()}'s scan: only the exact
 * strings {@code "S"} and {@code "s"} match.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and accessors
 * are auto-generated; there are no setters and no mutable internal state. The
 * {@code rowSelections} list is defensively copied via {@link java.util.List#copyOf}
 * in the compact constructor, yielding a fully immutable {@link java.util.List}
 * whose mutator methods throw {@link UnsupportedOperationException}. The resulting
 * instance is safe to publish across virtual threads (per AAP &sect;0.6.6) without
 * synchronization.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the compact
 *       constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; date/time
 *       strings stay as raw BMS bytes on this DTO and are parsed via {@code java.time}
 *       only inside the application logic.</li>
 *   <li>No {@code double} or {@code float} &mdash; this DTO carries no monetary
 *       fields.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features (records,
 *       JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * @param searchTransactionId  TRNIDIN value (16 chars in the BMS map; the operator may
 *                             type any length up to 16 inclusive) &mdash; used by the
 *                             COBOL {@code STARTBR} call as the list positioning key.
 *                             May be empty (no filter); must not be {@code null}.
 * @param rowSelections        Exactly {@link #ROWS_PER_PAGE} entries, one per SEL
 *                             column. Each entry is {@code "S"} or {@code "s"} for a
 *                             selected row, the empty string for an unselected row, or
 *                             a single space {@code " "} (the SPACES idiom in COBOL).
 *                             Must not be {@code null}; entries must not be
 *                             {@code null}. The list is defensively copied.
 * @param aidKey               The 3270 Attention Identifier (AID byte) decoded from
 *                             {@code EIBAID} into one of the seventeen
 *                             {@link AidKey} values; must not be {@code null}.
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Input.AidKey
 * @since 1.0.0
 */
public record CoTrn00Input(
        String searchTransactionId,
        List<String> rowSelections,
        AidKey aidKey) {

    /**
     * The number of selectable rows per COTRN00 page.
     *
     * <p>Mirrors the fixed COBOL constant in {@code COTRN00C} and the static layout
     * of {@code COTRN00.bms}: ten {@code SEL{NN}} columns (SEL0001 through SEL0010)
     * each backed by a {@code PIC X(1)} input field in the symbolic copybook
     * {@code COTRN0AI}. Used by:
     * <ul>
     *   <li>The compact constructor's size invariant (rejects any list whose size
     *       differs from this constant).</li>
     *   <li>{@link #empty()} as the loop count for pre-populating ten blank
     *       entries.</li>
     *   <li>External callers (the {@code CoTrn00C} controller) when they iterate the
     *       row list or compute page offsets.</li>
     * </ul>
     */
    public static final int ROWS_PER_PAGE = 10;

    /**
     * 3270 Attention Identifier (AID) decoded from the {@code EIBAID} byte returned
     * by CICS at RECEIVE-MAP time.
     *
     * <p>The COTRN00 controller dispatches on a five-value subset of this enum:
     * <ul>
     *   <li>{@link #ENTER} &mdash; reposition the list to {@code searchTransactionId}
     *       if filled, then process any selection (first {@code 'S'} / {@code 's'}
     *       in {@code rowSelections}) by transferring to {@code COTRN01C}.</li>
     *   <li>{@link #PF3} &mdash; transfer back to the main menu
     *       ({@code COMEN01C}).</li>
     *   <li>{@link #PF4} &mdash; clear the search field and selections.</li>
     *   <li>{@link #PF7} &mdash; paginate backward (decrement page number).</li>
     *   <li>{@link #PF8} &mdash; paginate forward (increment page number).</li>
     * </ul>
     * Any other value (including {@link #CLEAR}, {@link #PA1}, {@link #PA2},
     * {@link #PF1}, {@link #PF2}, {@link #PF5}, {@link #PF6}, {@link #PF9},
     * {@link #PF10}, {@link #PF11}, {@link #PF12}, and {@link #OTHER}) yields the
     * {@code CCDA-MSG-INVALID-KEY} error in the controller's dispatch switch.
     *
     * <p>The full seventeen-value enum is preserved here (rather than a smaller
     * COTRN00-only subset like {@link com.blitzy.carddemo.application.card.CoCrdLiInput})
     * so that the adapter layer that translates {@code EIBAID} into Java can emit the
     * same enum constant on this DTO as it does on every other COTRN0xInput record,
     * keeping the AID-decoder logic uniform across the transaction-package screens.
     * It also matches the agent-prompt validation phase that demands exactly the
     * seventeen values listed below in this exact order.
     *
     * <p>Why an enum rather than a sealed interface? AAP &sect;0.6.10 reserves sealed
     * interfaces for COBOL constructs that partition a value space (REDEFINES and
     * 88-level data taxonomies). The AID key is a closed set of opaque dispatch
     * tokens with no payload, which is exactly the case for which a plain
     * {@code enum} is idiomatic.
     */
    public enum AidKey {

        /** The user pressed ENTER. */
        ENTER,

        /** The user pressed CLEAR (terminal clear key). */
        CLEAR,

        /** The user pressed PA1. */
        PA1,

        /** The user pressed PA2. */
        PA2,

        /** PF1. */
        PF1,

        /** PF2. */
        PF2,

        /** PF3 &mdash; back to main menu ({@code COMEN01C}) on the COTRN00 screen. */
        PF3,

        /** PF4 &mdash; clear filters on the COTRN00 screen. */
        PF4,

        /** PF5. */
        PF5,

        /** PF6. */
        PF6,

        /** PF7 &mdash; paginate backward on the COTRN00 screen. */
        PF7,

        /** PF8 &mdash; paginate forward on the COTRN00 screen. */
        PF8,

        /** PF9. */
        PF9,

        /** PF10. */
        PF10,

        /** PF11. */
        PF11,

        /** PF12. */
        PF12,

        /** Any AID byte that does not decode to one of the named keys. */
        OTHER
    }

    /**
     * Compact (canonical) constructor.
     *
     * <p>Enforces three invariants on every constructed instance:
     * <ol>
     *   <li><strong>Non-null components.</strong> Each of {@code searchTransactionId},
     *       {@code rowSelections}, and {@code aidKey} must be non-null. A
     *       {@code null} argument is rejected with a {@link NullPointerException}
     *       carrying the offending component name. The COBOL idiom (RECEIVE-MAP
     *       fields are always SPACES, never undefined) translates to the Java
     *       discipline of carrying the empty {@link String} for "no value" rather
     *       than {@code null}.</li>
     *   <li><strong>Fixed row count.</strong> {@code rowSelections} must contain
     *       exactly {@link #ROWS_PER_PAGE} entries. The COBOL symbolic copybook
     *       {@code COTRN0AI} declares ten parallel {@code SEL{NN}I PIC X(1)}
     *       fields, so any other list length would silently misalign the
     *       Java-to-COBOL row mapping.</li>
     *   <li><strong>No null row entries.</strong> Every position in
     *       {@code rowSelections} must be a non-{@code null} {@link String}. Empty
     *       strings and single-space strings are accepted (they represent an
     *       unselected row); only {@code null} is rejected (with an
     *       {@link IllegalArgumentException} naming the offending index).</li>
     * </ol>
     * After the invariants are satisfied, the {@code rowSelections} parameter is
     * <strong>reassigned</strong> to a defensive immutable copy via
     * {@link java.util.List#copyOf(java.util.Collection)}. This ensures the record's
     * stored list is unaffected by any subsequent mutation the caller may perform on
     * the original list reference, and that consumers iterating {@link #rowSelections()}
     * cannot modify the underlying storage.
     *
     * <p>This constructor takes advantage of <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): each validation statement runs before
     * the implicit canonical field-assignment, which is exactly the place to capture
     * COBOL-style "validate before bind" semantics described in AAP &sect;0.6.3.
     *
     * @throws NullPointerException     if {@code searchTransactionId},
     *                                  {@code rowSelections}, or {@code aidKey}
     *                                  is {@code null}
     * @throws IllegalArgumentException if {@code rowSelections.size()} is not
     *                                  {@link #ROWS_PER_PAGE}, or any entry in
     *                                  {@code rowSelections} is {@code null}
     */
    public CoTrn00Input {
        Objects.requireNonNull(searchTransactionId, "searchTransactionId");
        Objects.requireNonNull(rowSelections, "rowSelections");
        Objects.requireNonNull(aidKey, "aidKey");
        if (rowSelections.size() != ROWS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "rowSelections must contain exactly " + ROWS_PER_PAGE
                            + " entries, but received " + rowSelections.size());
        }
        // Validate that no entry is null (empty string is OK; single space is OK).
        // The loop variable is declared with explicit type to avoid any reliance on
        // local-variable type inference, keeping the construct unambiguously
        // pre-Java-10 compatible at the language level (though the file as a whole
        // requires --release 25 for the JEP 511 module import directive above).
        for (int i = 0; i < rowSelections.size(); i++) {
            if (rowSelections.get(i) == null) {
                throw new IllegalArgumentException(
                        "rowSelections[" + i + "] must not be null"
                                + " (use empty string for unselected)");
            }
        }
        // Defensive immutable copy. List.copyOf returns an unmodifiable List whose
        // contents are a snapshot of the argument at copy time; subsequent mutation
        // of the original list does not affect the stored copy.
        rowSelections = List.copyOf(rowSelections);
    }

    /**
     * Returns an "empty" input record suitable for the first dispatch into the
     * COTRN00 screen: the search transaction id is the empty {@link String}, the
     * ten {@code rowSelections} entries are each the empty {@link String}, and the
     * {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * {@code DFHCOMMAREA} has zero length (no inbound state), and the controller
     * treats the screen as a fresh listing with no filter and no row selection. The
     * AID key defaults to {@link AidKey#ENTER} rather than {@link AidKey#OTHER}
     * because the controller's first-invocation path is functionally identical to
     * an explicit ENTER press with empty input.
     *
     * <p>Implementation note: the helper allocates a new {@link java.util.ArrayList}
     * of capacity {@link #ROWS_PER_PAGE}, fills it with ten empty strings, and
     * passes it to the canonical constructor. The constructor defensively copies the
     * list via {@link java.util.List#copyOf(java.util.Collection)}, so the
     * {@link java.util.ArrayList} allocated here is discarded after construction
     * and never escapes the method scope.
     *
     * @return a fully-blank {@code CoTrn00Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn00Input empty() {
        List<String> emptySelections = new ArrayList<>(ROWS_PER_PAGE);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            emptySelections.add("");
        }
        return new CoTrn00Input("", emptySelections, AidKey.ENTER);
    }

    /**
     * Returns the 1-based index of the first row marked {@code "S"} or {@code "s"}
     * in {@link #rowSelections()}, or {@code -1} if no row is selected.
     *
     * <p>Mirrors the COBOL {@code PROCESS-ENTER-KEY} paragraph in
     * {@code app/cbl/COTRN00C.cbl}, which scans {@code SEL0001I} through
     * {@code SEL0010I} in order and dispatches on the first match by moving the
     * row's {@code TRNID} into {@code CDEMO-CT00-TRN-SELECTED} and issuing
     * {@code EXEC CICS XCTL PROGRAM('COTRN01C')}. Subsequent selected rows are
     * <em>ignored</em> &mdash; only the first match drives navigation.
     *
     * <p>The match is exact and case-sensitive in the two-character set
     * {@code {"S", "s"}}. The COBOL paragraph uses an equivalent
     * {@code IF SEL0001I = 'S' OR SEL0001I = 's'} check, which accepts both upper
     * and lower-case but rejects any longer string, any other letter, and the
     * COBOL-padded value {@code 'S '} (with trailing space). The Java helper
     * preserves this behavior verbatim by using {@link String#equals(Object)} on
     * the bare letter only.
     *
     * @return a value in {@code [1..ROWS_PER_PAGE]} if a row is selected;
     *         {@code -1} if no row is selected
     */
    public int firstSelectedRow() {
        for (int i = 0; i < rowSelections.size(); i++) {
            String sel = rowSelections.get(i);
            if ("S".equals(sel) || "s".equals(sel)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Returns {@code true} when {@link #searchTransactionId()} is blank &mdash;
     * either the empty {@link String} or a string consisting only of whitespace
     * characters as defined by {@link String#isBlank()}.
     *
     * <p>Used by the COBOL {@code PROCESS-ENTER-KEY} paragraph (and by its Java
     * translation in {@code CoTrn00C}) to decide whether the operator typed a
     * specific transaction-id positioning key (in which case the controller calls
     * {@code STARTBR} at that key) or simply pressed ENTER to refresh the listing
     * from its current position (in which case the controller reuses the page's
     * existing first-row key as the STARTBR positioning key).
     *
     * <p>The implementation includes a defensive {@code null} check for the
     * theoretical case where a {@link CoTrn00Input} reference is held by code that
     * bypassed the canonical constructor (e.g., serialization frameworks that
     * reflect over the record's fields). The compact constructor's
     * {@link java.util.Objects#requireNonNull(Object, String)} guarantees that the
     * value cannot be {@code null} for any record produced by the canonical path,
     * but layered defenses are cheap and clarify the helper's contract.
     *
     * @return {@code true} when the search id is null, empty, or whitespace-only;
     *         {@code false} otherwise
     */
    public boolean isSearchIdBlank() {
        return searchTransactionId == null || searchTransactionId.isBlank();
    }
}
