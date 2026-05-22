/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports every package exported
// by the java.base module (and by the modules it reads). This brings in:
//   * java.lang.String         — used by every text-bearing record component (title1,
//                                title2, transactionName, programName, currentDate,
//                                currentTime, searchTransactionId, pageNum, errMsg,
//                                and every field of the nested TransactionRow record);
//   * java.util.List           — the row container type for the 10 TransactionRow
//                                entries (modeling the COBOL OCCURS 10 TIMES idiom per
//                                AAP §0.6.3);
//   * java.util.List#copyOf    — invoked inside the compact constructor to defensively
//                                snapshot the inbound row list into an unmodifiable
//                                List, guaranteeing record immutability even when the
//                                caller retains a reference to the original list;
//   * java.util.ArrayList      — the mutable builder type used by empty() to allocate
//                                a 10-element list before passing it to the canonical
//                                constructor (the constructor then defensively copies
//                                it, so the ArrayList does not escape);
//   * java.util.Objects        — Objects.requireNonNull(...) for per-component null
//                                checks in both the outer record's compact constructor
//                                and the nested TransactionRow's compact constructor
//                                (per AAP §0.6.3 / JEP 513 "validate before bind");
//   * java.lang.IllegalArgumentException — raised when rows.size() != ROWS_PER_PAGE,
//                                enforcing the COBOL fixed-cardinality OCCURS 10 TIMES
//                                contract at construction time;
//   * java.lang.NullPointerException     — raised (via Objects.requireNonNull) on any
//                                null component, with the component name as the
//                                exception detail message for fast diagnosis.
// The file schema's external_imports list specifies exactly one entry — the java.base
// module — and the internal_imports list is empty; no other import is permitted or
// required on this file.
import module java.base;

/**
 * BMS output record carrying every value sent to the 3270 terminal for the
 * <strong>COTRN00</strong> (Transaction List) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN00.bms} (mapset {@code COTRN00},
 *       map {@code COTRN0A}, size 24x80, FREEKB, ALARM).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN00.CPY} (output group
 *       {@code 01 COTRN0AO REDEFINES COTRN0AI} containing 8 header echoes, 10
 *       SEL/TRNID/TDATE/TDESC/TAMT row clusters, and ERRMSG with its color
 *       attribute byte {@code ERRMSGC}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN00C.cbl} (transaction
 *       {@code CT00}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the output view of the BMS symbolic structure
 * {@code COTRN0AO}: it carries the field values written to the terminal by the
 * controller via the equivalent of {@code EXEC CICS SEND MAP} after
 * {@code CoTrn00C.run(...)} returns an outcome of {@code SendMap}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Transaction-list semantics</h2>
 * <p>COTRN00 is the paginated <em>list / browse</em> screen for transactions. The
 * output side displays:
 * <ol>
 *   <li>A two-line title bar populated from the {@link com.blitzy.carddemo.domain.text.ScreenTitle}
 *       constants (mapped to {@link #title1()} and {@link #title2()}, both YELLOW).</li>
 *   <li>Static header chrome &mdash; the transaction id, the program name, the
 *       current date in MM/DD/YY format, and the current time in HH:MM:SS format
 *       (mapped to {@link #transactionName()}, {@link #programName()},
 *       {@link #currentDate()}, {@link #currentTime()}).</li>
 *   <li>The pagination state &mdash; the current page number, typically formatted
 *       right-justified inside the {@code PAGENUM} field (mapped to {@link #pageNum()}).</li>
 *   <li>The echoed transaction-id search filter, which on entry is the operator's
 *       typed value and on re-display is preserved across PF7/PF8 paging cycles
 *       (mapped to {@link #searchTransactionId()}, GREEN UNDERLINE).</li>
 *   <li>Ten rows of transaction data &mdash; one row per visible page entry &mdash;
 *       each composed of a selection cell, transaction id, formatted date,
 *       description, and signed-decimal amount (mapped to {@link #rows()}).</li>
 *   <li>A single-line error/info message field at row 23, 78 characters wide, BRT
 *       RED on error (mapped to {@link #errMsg()} and {@link #errMsgColor()}). The
 *       message is empty when no error has occurred.</li>
 * </ol>
 *
 * <h2>OCCURS 10 TIMES &rArr; {@code List<TransactionRow>}</h2>
 * <p>The COBOL symbolic copybook {@code COTRN0AO} declares ten parallel record
 * clusters &mdash; {@code SEL0001O/TRNID01O/TDATE01O/TDESC01O/TAMT001O} through
 * {@code SEL0010O/TRNID10O/TDATE10O/TDESC10O/TAMT010O}. Per AAP &sect;0.6.3 the
 * Java translation collapses those ten parallel-named leaves into a single
 * {@link java.util.List List&lt;TransactionRow&gt;}; the COBOL fixed cardinality of
 * ten is enforced by the compact constructor's size invariant
 * ({@link #ROWS_PER_PAGE} entries exactly). This is the same OCCURS-style
 * translation pattern documented for the sibling input DTO
 * {@link CoTrn00Input}; the only difference is that the output record carries five
 * fields per row (selection echo, transaction id, date, description, amount) while
 * the input record carries one field per row (the operator-entered selection).
 *
 * <h2>Date display format &mdash; MM/DD/YY (8 characters)</h2>
 * <p>The COTRN00 list displays dates in the short MM/DD/YY format (8 characters),
 * which is <em>different</em> from the YYYY-MM-DD format used by the detail screens
 * COTRN01 (view) and COTRN02 (add). The COBOL {@code POPULATE-TRAN-DATA} paragraph
 * extracts MM/DD/YY by indexing into the source timestamp string
 * {@code TRAN-ORIG-TS PIC X(26)} (format
 * {@code YYYY-MM-DD HH:MM:SS.SSSSSS}) at character offsets 6,7 (month), 9,10 (day),
 * and 3,4 (year). The Java translation in {@code CoTrn00C} performs the same
 * substring extraction; this DTO simply carries the pre-formatted MM/DD/YY string.
 *
 * <h2>Amount display format &mdash; +99999999.99 (12 characters)</h2>
 * <p>The amount column displays a signed amount with leading sign character, eight
 * digits, decimal point, and two fractional digits, totaling 12 characters. The
 * COBOL idiom uses an edited PICTURE clause such as {@code PIC +99999999.99}; the
 * Java translation in {@code CoTrn00C} formats the {@link java.math.BigDecimal}
 * amount via the {@code Decimals} utility (with explicit
 * {@link java.math.RoundingMode}) into the same fixed-width string. This DTO
 * carries only the pre-formatted string &mdash; no {@code BigDecimal}, no
 * {@code double}, no {@code float}, in compliance with AAP &sect;0.7.4.
 *
 * <h2>Null-handling discipline</h2>
 * <p>This record is <em>strict</em> about nulls. The compact constructor rejects any
 * {@code null} component with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}; empty strings are
 * always accepted (they represent "no value" / SPACES in the COBOL idiom). The same
 * discipline applies to the nested {@link TransactionRow} record: every one of its
 * five components must be non-null, but the empty {@link String} {@code ""} is
 * accepted everywhere. This contract matches that of the sibling DTO
 * {@link CoTrn00Input}.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and accessors
 * are auto-generated; there are no setters and no mutable internal state. The
 * {@code rows} list is defensively copied via {@link java.util.List#copyOf} in the
 * compact constructor, yielding a fully immutable {@link java.util.List} whose
 * mutator methods throw {@link UnsupportedOperationException}. Each
 * {@link TransactionRow} is itself a record (immutable). The resulting instance is
 * safe to publish across virtual threads (per AAP &sect;0.6.6) without
 * synchronization.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the compact
 *       constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; date and time
 *       values are carried as preformatted {@link String} fields.</li>
 *   <li>No {@code double} or {@code float} &mdash; the amount field per row is
 *       carried as a preformatted {@link String} (the upstream code converts from
 *       {@link java.math.BigDecimal} via the {@code Decimals} utility).</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features (records,
 *       JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * <h2>Field-by-field mapping</h2>
 * <p>Mapping from BMS symbolic copybook {@code COTRN0AO} (output view, the
 * REDEFINES of {@code COTRN0AI}) to Java record components. The PIC column shows
 * the COBOL PICTURE clause; lengths are fixed and preserved by the runtime that
 * converts this Java record into the 3270 SEND-MAP wire format.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; X(4)  &mdash; {@link #transactionName()}</li>
 *   <li>{@code TITLE01O} &mdash; X(40) &mdash; {@link #title1()}</li>
 *   <li>{@code CURDATEO} &mdash; X(8)  &mdash; {@link #currentDate()} (MM/DD/YY)</li>
 *   <li>{@code PGMNAMEO} &mdash; X(8)  &mdash; {@link #programName()}</li>
 *   <li>{@code TITLE02O} &mdash; X(40) &mdash; {@link #title2()}</li>
 *   <li>{@code CURTIMEO} &mdash; X(8)  &mdash; {@link #currentTime()} (HH:MM:SS)</li>
 *   <li>{@code PAGENUMO} &mdash; X(8)  &mdash; {@link #pageNum()}</li>
 *   <li>{@code TRNIDINO} &mdash; X(16) &mdash; {@link #searchTransactionId()}</li>
 *   <li>{@code SEL{NN}O / TRNID{NN}O / TDATE{NN}O / TDESC{NN}O / TAMT{NN}O} for NN =
 *       01..10 &mdash; {@link #rows()} entries 0..9, each a {@link TransactionRow}
 *       with components {@code selection}, {@code transactionId}, {@code date},
 *       {@code description}, {@code amount}</li>
 *   <li>{@code ERRMSGO} &mdash; X(78) &mdash; {@link #errMsg()}</li>
 *   <li>{@code ERRMSGC} &mdash; PIC X (BMS color attribute byte) &mdash;
 *       {@link #errMsgColor()} (a typed {@link FieldColor} value)</li>
 * </ul>
 *
 * @param title1               TITLE01O echo &mdash; line 1 of the screen title
 *                             (40 chars, YELLOW). Populated by the controller from
 *                             {@code ScreenTitle.TITLE_01}. Must not be
 *                             {@code null}; empty string is accepted.
 * @param title2               TITLE02O echo &mdash; line 2 of the screen title
 *                             (40 chars, YELLOW). Populated by the controller from
 *                             {@code ScreenTitle.TITLE_02}. Must not be
 *                             {@code null}; empty string is accepted.
 * @param transactionName      TRNNAMEO echo &mdash; the four-character transaction
 *                             id ({@code "CT00"} for this screen), header bar.
 *                             Must not be {@code null}; empty string is accepted.
 * @param programName          PGMNAMEO echo &mdash; the eight-character program
 *                             name ({@code "COTRN00C"} for this screen), header
 *                             bar. Must not be {@code null}; empty string is
 *                             accepted.
 * @param currentDate          CURDATEO echo &mdash; the current date formatted as
 *                             MM/DD/YY (8 chars). Must not be {@code null}; empty
 *                             string is accepted.
 * @param currentTime          CURTIMEO echo &mdash; the current time formatted as
 *                             HH:MM:SS (8 chars). Must not be {@code null}; empty
 *                             string is accepted.
 * @param searchTransactionId  TRNIDINO echo &mdash; the operator's most recently
 *                             entered search filter (16 chars). Re-displayed across
 *                             PF7/PF8 paging cycles. Must not be {@code null};
 *                             empty string is accepted.
 * @param pageNum              PAGENUMO display &mdash; the current page number
 *                             string (8 chars, typically right-justified
 *                             {@code "      1"}, {@code "      2"}, ...). Must not
 *                             be {@code null}; empty string is accepted.
 * @param rows                 Exactly {@link #ROWS_PER_PAGE} {@link TransactionRow}
 *                             entries, one per page row. Must not be {@code null};
 *                             list is defensively copied to an unmodifiable
 *                             snapshot in the compact constructor.
 * @param errMsg               ERRMSGO display &mdash; the single-line error or
 *                             informational message (78 chars, BRT RED on error).
 *                             Must not be {@code null}; empty string means
 *                             no error.
 * @param errMsgColor          ERRMSGC display &mdash; the BMS color attribute byte
 *                             for the {@link #errMsg()} field. Set to
 *                             {@link FieldColor#RED} when the controller emits an
 *                             error condition; {@link FieldColor#DEFAULT} otherwise.
 *                             Must not be {@code null}.
 *
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Input
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Output.TransactionRow
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Output.FieldColor
 * @since 1.0.0
 */
public record CoTrn00Output(
        String title1,
        String title2,
        String transactionName,
        String programName,
        String currentDate,
        String currentTime,
        String searchTransactionId,
        String pageNum,
        List<TransactionRow> rows,
        String errMsg,
        FieldColor errMsgColor) {

    /**
     * The number of selectable rows per COTRN00 page.
     *
     * <p>Mirrors the fixed COBOL constant in {@code COTRN00C} and the static layout
     * of {@code COTRN00.bms}: ten {@code SEL{NN}O / TRNID{NN}O / TDATE{NN}O /
     * TDESC{NN}O / TAMT{NN}O} field clusters in the symbolic copybook
     * {@code COTRN0AO}. The value is also used by the sibling input DTO
     * {@link CoTrn00Input#ROWS_PER_PAGE} so that the two sides of the BMS contract
     * remain in lock-step. Used by:
     * <ul>
     *   <li>The compact constructor's size invariant (rejects any list whose size
     *       differs from this constant).</li>
     *   <li>{@link #empty()} as the loop count for pre-populating ten blank
     *       {@link TransactionRow} entries.</li>
     *   <li>External callers (the {@code CoTrn00C} controller) when they iterate
     *       the row list to populate per-row fields or compute page offsets.</li>
     * </ul>
     */
    public static final int ROWS_PER_PAGE = 10;

    /**
     * A single row in the transaction list, mapping to one of the ten
     * {@code SEL{NN}O / TRNID{NN}O / TDATE{NN}O / TDESC{NN}O / TAMT{NN}O} clusters
     * in the BMS symbolic copybook {@code COTRN0AO}.
     *
     * <h2>BMS field origin</h2>
     * <p>Each cluster contributes five output fields to the 3270 SEND-MAP payload:
     * <ul>
     *   <li>{@code SEL{NN}O}   &mdash; PIC X(1)  &mdash; {@link #selection()}</li>
     *   <li>{@code TRNID{NN}O} &mdash; PIC X(16) &mdash; {@link #transactionId()}</li>
     *   <li>{@code TDATE{NN}O} &mdash; PIC X(8)  &mdash; {@link #date()}</li>
     *   <li>{@code TDESC{NN}O} &mdash; PIC X(26) &mdash; {@link #description()}</li>
     *   <li>{@code TAMT{NN}O}  &mdash; PIC X(12) &mdash; {@link #amount()}</li>
     * </ul>
     * The numbering {@code NN} ranges over {@code 01}..{@code 10} for the five
     * non-amount fields; the amount field is numbered {@code TAMT001O}..{@code TAMT010O}
     * (three digits) in the COBOL source. The Java translation flattens both
     * numbering schemes into the index of the enclosing {@link CoTrn00Output#rows()}
     * list.
     *
     * <h2>Selection semantics</h2>
     * <p>The {@code selection} field carries an echo of the operator-entered SEL
     * value from the input side ({@code SEL{NN}I} in {@code COTRN0AI}). On output
     * it is typically the empty {@link String} (blank cell) or a single space (the
     * COBOL SPACES idiom). The controller may also set it to {@code "S"} or
     * {@code "s"} to preserve the operator's pick across screen redraws when the
     * controller chooses not to clear the field before sending the map back.
     *
     * <h2>Display formats</h2>
     * <p>The {@code date} field is the MM/DD/YY-formatted display string (8 chars)
     * derived in the COBOL {@code POPULATE-TRAN-DATA} paragraph from
     * {@code TRAN-ORIG-TS PIC X(26)} (format {@code YYYY-MM-DD HH:MM:SS.SSSSSS}).
     * The {@code amount} field is the signed-decimal 12-character display
     * ({@code PIC +99999999.99}) derived from the {@code BigDecimal} transaction
     * amount via the {@code Decimals} utility. Per AAP &sect;0.7.4, no
     * {@code double} / {@code float} types are used at any point in the pipeline.
     *
     * <h2>Null-handling discipline</h2>
     * <p>Every component must be non-null. The compact constructor enforces this
     * via {@link java.util.Objects#requireNonNull(Object, String)} with the
     * component name as the diagnostic message. Empty strings are accepted and
     * represent SPACES in the COBOL idiom.
     *
     * @param selection      SEL value echo (1 char) &mdash; usually empty,
     *                       {@code " "}, {@code "S"}, or {@code "s"}. Must not be
     *                       {@code null}.
     * @param transactionId  TRNID display (16 chars). Must not be {@code null};
     *                       empty string represents a blank row.
     * @param date           TDATE display (8 chars, MM/DD/YY). Must not be
     *                       {@code null}; empty string represents a blank row.
     * @param description    TDESC display (26 chars) &mdash; derived from
     *                       {@code TRAN-DESC} in the underlying transaction record.
     *                       Must not be {@code null}; empty string represents a
     *                       blank row.
     * @param amount         TAMT display (12 chars, +99999999.99 format). Must not
     *                       be {@code null}; empty string represents a blank row.
     *
     * @since 1.0.0
     */
    public record TransactionRow(
            String selection,
            String transactionId,
            String date,
            String description,
            String amount) {

        /**
         * Compact (canonical) constructor for {@link TransactionRow}.
         *
         * <p>Per AAP &sect;0.6.3 / JEP 513 (Flexible Constructor Bodies, finalized
         * in Java 25), the validation logic runs before the implicit canonical
         * field-assignment. Each component is null-checked via
         * {@link java.util.Objects#requireNonNull(Object, String)}; empty strings
         * are accepted everywhere.
         *
         * @throws NullPointerException if any component is {@code null}, with the
         *                              component name as the exception message
         */
        public TransactionRow {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
        }

        /**
         * Returns a fully-blank {@link TransactionRow}: every component is the
         * empty {@link String} {@code ""}. Used by {@link CoTrn00Output#empty()} to
         * pre-populate the ten-row list, and by the controller when it needs to
         * blank-out a row beyond the last data row on the current page.
         *
         * <p>This mirrors the COBOL idiom of {@code MOVE LOW-VALUES} (or
         * equivalently {@code MOVE SPACES}) to every output field of a row cluster
         * when the row has no data to display.
         *
         * @return a {@code TransactionRow} with all five components set to
         *         {@code ""} (never {@code null})
         */
        public static TransactionRow empty() {
            return new TransactionRow("", "", "", "", "");
        }
    }

    /**
     * BMS field-color attribute, modeling the discrete set of color values
     * supported by the 3270 protocol's {@code COLOR=} clause and the symbolic
     * copybook's per-field color byte (e.g., {@code ERRMSGC PIC X}).
     *
     * <p>This enum is the typed Java equivalent of the COBOL color attribute byte:
     * <ul>
     *   <li>{@link #DEFAULT}   &mdash; "no override" (the BMS field's compile-time
     *       COLOR= setting is used). Emitted as a low-value byte in the COBOL
     *       attribute character per CICS convention.</li>
     *   <li>{@link #NEUTRAL}   &mdash; CICS-default white/cream tone (COLOR=NEUTRAL
     *       in BMS).</li>
     *   <li>{@link #BLUE}      &mdash; COLOR=BLUE.</li>
     *   <li>{@link #GREEN}     &mdash; COLOR=GREEN.</li>
     *   <li>{@link #YELLOW}    &mdash; COLOR=YELLOW.</li>
     *   <li>{@link #RED}       &mdash; COLOR=RED; used for the
     *       {@link CoTrn00Output#errMsgColor() error-message field} on error.</li>
     *   <li>{@link #TURQUOISE} &mdash; COLOR=TURQUOISE.</li>
     *   <li>{@link #PINK}      &mdash; COLOR=PINK.</li>
     *   <li>{@link #WHITE}     &mdash; COLOR=WHITE.</li>
     * </ul>
     *
     * <p>The enum is used by {@link CoTrn00Output#errMsgColor()} to map the
     * symbolic copybook field {@code ERRMSGC} (the BMS attribute character byte
     * for the ERRMSG field) into a typed Java value. Per AAP &sect;0.6.10 the
     * AID-key dispatch uses a sealed-interface hierarchy (because AID keys
     * partition a value space with no payload), but the field-color attribute is a
     * closed set of opaque dispatch tokens with no payload, which makes a plain
     * {@code enum} the idiomatic Java representation.
     *
     * @since 1.0.0
     */
    public enum FieldColor {

        /**
         * No color override &mdash; the BMS map's compile-time {@code COLOR=}
         * setting is used. The default value for fields that do not need dynamic
         * coloring.
         */
        DEFAULT,

        /** CICS-default white/cream tone (BMS {@code COLOR=NEUTRAL}). */
        NEUTRAL,

        /** Blue (BMS {@code COLOR=BLUE}). */
        BLUE,

        /** Green (BMS {@code COLOR=GREEN}). */
        GREEN,

        /** Yellow (BMS {@code COLOR=YELLOW}). */
        YELLOW,

        /**
         * Red (BMS {@code COLOR=RED}) &mdash; used for the
         * {@link CoTrn00Output#errMsgColor() error-message field} on error
         * conditions per the BMS map's {@code ERRMSG ATTRB=(ASKIP,BRT,FSET)
         * COLOR=RED} declaration.
         */
        RED,

        /** Turquoise (BMS {@code COLOR=TURQUOISE}). */
        TURQUOISE,

        /** Pink (BMS {@code COLOR=PINK}). */
        PINK,

        /** White (BMS {@code COLOR=WHITE}). */
        WHITE
    }

    /**
     * Compact (canonical) constructor.
     *
     * <p>Enforces three invariants on every constructed instance:
     * <ol>
     *   <li><strong>Non-null components.</strong> Each of the eleven components must
     *       be non-null. A {@code null} argument is rejected with a
     *       {@link NullPointerException} carrying the offending component name. The
     *       COBOL idiom (SEND-MAP fields are always SPACES, never undefined)
     *       translates to the Java discipline of carrying the empty {@link String}
     *       for "no value" rather than {@code null}. The {@link FieldColor}
     *       sentinel {@link FieldColor#DEFAULT} plays the same role for the
     *       attribute-byte component.</li>
     *   <li><strong>Fixed row count.</strong> {@code rows} must contain exactly
     *       {@link #ROWS_PER_PAGE} entries. The COBOL symbolic copybook
     *       {@code COTRN0AO} declares ten parallel row clusters, so any other list
     *       length would silently misalign the Java-to-COBOL row mapping.</li>
     *   <li><strong>No null row entries.</strong> The defensive copy via
     *       {@link java.util.List#copyOf(java.util.Collection)} additionally
     *       enforces that no entry in {@code rows} is {@code null}; {@code copyOf}
     *       throws {@link NullPointerException} on any {@code null} element. The
     *       resulting list is itself unmodifiable.</li>
     * </ol>
     * After the invariants are satisfied, the {@code rows} parameter is
     * <strong>reassigned</strong> to the defensive immutable copy. This ensures
     * the record's stored list is unaffected by any subsequent mutation the caller
     * may perform on the original list reference, and that consumers iterating
     * {@link #rows()} cannot modify the underlying storage.
     *
     * <p>This constructor takes advantage of <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): each validation statement runs before
     * the implicit canonical field-assignment, which is exactly the place to
     * capture COBOL-style "validate before bind" semantics described in AAP
     * &sect;0.6.3.
     *
     * @throws NullPointerException     if any component is {@code null}, with the
     *                                  component name as the exception detail
     *                                  message; or if any entry within
     *                                  {@code rows} is {@code null} (thrown by
     *                                  {@link java.util.List#copyOf})
     * @throws IllegalArgumentException if {@code rows.size()} is not
     *                                  {@link #ROWS_PER_PAGE}
     */
    public CoTrn00Output {
        Objects.requireNonNull(title1, "title1");
        Objects.requireNonNull(title2, "title2");
        Objects.requireNonNull(transactionName, "transactionName");
        Objects.requireNonNull(programName, "programName");
        Objects.requireNonNull(currentDate, "currentDate");
        Objects.requireNonNull(currentTime, "currentTime");
        Objects.requireNonNull(searchTransactionId, "searchTransactionId");
        Objects.requireNonNull(pageNum, "pageNum");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(errMsg, "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");
        if (rows.size() != ROWS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "rows must contain exactly " + ROWS_PER_PAGE
                            + " entries, but received " + rows.size());
        }
        // Defensive immutable copy. List.copyOf returns an unmodifiable List whose
        // contents are a snapshot of the argument at copy time; subsequent mutation
        // of the original list does not affect the stored copy. List.copyOf also
        // throws NullPointerException if any element is null, enforcing the
        // "no null row entries" invariant without an explicit per-element check.
        rows = List.copyOf(rows);
    }

    /**
     * Returns an "empty" output record suitable for the initial COTRN00 send-map
     * cycle: every text component is the empty {@link String}, the ten
     * {@link #rows()} entries are each a fully-blank {@link TransactionRow}, and
     * the error-message color is {@link FieldColor#DEFAULT}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * the controller issues {@code MOVE LOW-VALUES TO COTRN0AO} before populating
     * any header or row fields, then issues {@code EXEC CICS SEND MAP} to paint
     * the empty list screen.
     *
     * <p>Implementation note: the helper allocates a new {@link java.util.ArrayList}
     * of capacity {@link #ROWS_PER_PAGE}, fills it with ten
     * {@link TransactionRow#empty()} entries, and passes it to the canonical
     * constructor. The constructor defensively copies the list via
     * {@link java.util.List#copyOf(java.util.Collection)}, so the
     * {@link java.util.ArrayList} allocated here is discarded after construction
     * and never escapes the method scope. Each {@link TransactionRow} is itself
     * an immutable record produced by the {@link TransactionRow#empty()} factory.
     *
     * @return a fully-blank {@code CoTrn00Output} (never {@code null}) with all
     *         eleven components set to their "neutral" values (empty strings, ten
     *         blank rows, and {@link FieldColor#DEFAULT})
     */
    public static CoTrn00Output empty() {
        List<TransactionRow> emptyRows = new ArrayList<>(ROWS_PER_PAGE);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            emptyRows.add(TransactionRow.empty());
        }
        return new CoTrn00Output(
                "",                // title1
                "",                // title2
                "",                // transactionName
                "",                // programName
                "",                // currentDate
                "",                // currentTime
                "",                // searchTransactionId
                "",                // pageNum
                emptyRows,         // rows (10 blank TransactionRow entries)
                "",                // errMsg
                FieldColor.DEFAULT // errMsgColor
        );
    }

    /**
     * Returns a copy of this {@code CoTrn00Output} with a new {@link #errMsg()}
     * and {@link #errMsgColor()}, preserving all other fields unchanged.
     *
     * <p>This helper supports the canonical COBOL "decorate output with error
     * message" idiom &mdash; in the COBOL program, the controller computes the
     * row data and header values into the output map area, then conditionally
     * moves an error message string to {@code ERRMSGO} and the RED color byte to
     * {@code ERRMSGC} just before {@code EXEC CICS SEND MAP}. In the Java
     * translation, the controller builds the output record without an error
     * message via the various row/header setters and then, if an error condition
     * occurs, calls {@code output.withErrMsg(msg, FieldColor.RED)} to derive a
     * decorated copy. Because records in finalized Java 25 do not have a built-in
     * {@code with} syntax (per AAP &sect;0.1.2), this method is the hand-written
     * equivalent.
     *
     * <p>The returned record reuses the same {@link #rows()} list reference: the
     * stored list is already an unmodifiable {@link java.util.List#copyOf} result
     * from the original record's compact constructor, so the canonical constructor
     * of the new record will accept it and call {@code List.copyOf} on it a second
     * time. {@link java.util.List#copyOf} short-circuits when the input is already
     * an unmodifiable list, so the second copy is effectively a no-op and no
     * additional allocation is incurred.
     *
     * @param newMsg   the new error / informational message text (78 chars max in
     *                 the BMS map; this method does not truncate, leaving the
     *                 wire-level truncation to the SEND-MAP adapter). Must not be
     *                 {@code null}; pass the empty {@link String} {@code ""} to
     *                 clear the message.
     * @param newColor the new color attribute. Must not be {@code null}; pass
     *                 {@link FieldColor#DEFAULT} to clear the override or
     *                 {@link FieldColor#RED} to emit a hard-error message.
     * @return a new {@code CoTrn00Output} identical to this one except with the
     *         supplied {@code newMsg} and {@code newColor}
     *
     * @throws NullPointerException if {@code newMsg} or {@code newColor} is
     *                              {@code null}
     */
    public CoTrn00Output withErrMsg(String newMsg, FieldColor newColor) {
        return new CoTrn00Output(
                title1,
                title2,
                transactionName,
                programName,
                currentDate,
                currentTime,
                searchTransactionId,
                pageNum,
                rows,
                newMsg,
                newColor);
    }
}
