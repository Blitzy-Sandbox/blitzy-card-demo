/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and the modules it reads). This brings in java.lang.String --
// the type of the transactionIdIn component and the source of the isBlank() utility
// invoked by isTransactionIdBlank() -- and java.util.Objects (the source of the
// requireNonNull() null-safety guard inside the compact constructor). It also brings in
// the exception classes (java.lang.NullPointerException raised by requireNonNull).
//
// No additional imports are required or permitted on this file. The internal_imports
// list in the file schema is empty (this record carries no domain references), and the
// only external import allowed by the schema is java.base itself. The single
// "import module java.base;" line replaces the verbose pair of
// "import java.lang.String;" (implicit in every compilation unit) and
// "import java.util.Objects;", and aligns the file with the AAP §0.6.7 / §0.7.3 mandate
// to use Module Import Declarations finalized in Java 25.
import module java.base;

/**
 * BMS input record carrying every value received from the 3270 terminal for the
 * <strong>COTRN01</strong> (View Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN01.bms} (mapset {@code COTRN01},
 *       map {@code COTRN1A}, size 24x80, {@code CTRL=(ALARM,FREEKB)},
 *       {@code EXTATT=YES}, {@code LANG=COBOL}, {@code MODE=INOUT},
 *       {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN01.CPY} (input group
 *       {@code 01 COTRN1AI} containing one operator-editable field
 *       {@code TRNIDINI PIC X(16)} plus echo-back leaves for the header
 *       columns and the thirteen display-only detail fields).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN01C.cbl} (transaction
 *       {@code CT01}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the input view of the BMS symbolic structure
 * {@code COTRN1AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn01C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Transaction-view semantics</h2>
 * <p>COTRN01 is the <em>view / detail</em> screen for a single transaction. Unlike
 * Add Transaction (COTRN02) which has fourteen operator-editable fields, View
 * Transaction has <strong>exactly one</strong>: the transaction identifier the
 * operator types into the {@code TRNIDIN} field at BMS row 6, column 21
 * ({@code LENGTH=16}, attributes {@code UNPROT,FSET,IC,NORM}, colour
 * {@code GREEN}, highlight {@code UNDERLINE}). Every other BMS field on this
 * screen is {@code ATTRB=(ASKIP,...)} &mdash; read-only, populated by
 * {@code SEND-MAP} and ignored by {@code RECEIVE-MAP}.
 *
 * <p>On entry the operator may:
 * <ol>
 *   <li>Type a transaction identifier into the {@code TRNIDIN} field (mapped to
 *       {@link #transactionIdIn()}) and press {@link AidKey#ENTER} to <em>fetch</em>
 *       the transaction record. The COBOL {@code PROCESS-ENTER-KEY} paragraph in
 *       {@code app/cbl/COTRN01C.cbl} first verifies the field is non-blank
 *       (otherwise it returns the message
 *       {@code "Tran ID can NOT be empty..."}), then issues
 *       {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRNIDINI)} and populates
 *       the display fields from the resulting record.</li>
 *   <li>Press <strong>{@link AidKey#PF3 PF3}</strong> to return to the previous
 *       program (typically {@code COMEN01C} main menu, but possibly
 *       {@code COTRN00C} if the user arrived from the browse list).</li>
 *   <li>Press <strong>{@link AidKey#PF4 PF4}</strong> to <em>clear</em> the
 *       {@code TRNIDIN} field and re-display the screen.</li>
 *   <li>Press <strong>{@link AidKey#PF5 PF5}</strong> to transfer
 *       ({@code XCTL}) to {@code COTRN00C}, the transaction-browse list screen.</li>
 * </ol>
 * Any other AID key produces an invalid-key error in the controller; see the BMS
 * footer line at row 24:
 * {@code "ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran."}.
 *
 * <h2>Why only two components for nineteen BMS leaves?</h2>
 * <p>The COBOL {@code COTRN1AI} structure declares nineteen {@code "I"}-suffixed
 * leaves &mdash; one for each {@code DFHMDF} field on the map. Of those nineteen,
 * <strong>eighteen are display-only</strong> (header echoes {@code TRNNAME},
 * {@code TITLE01}, {@code CURDATE}, {@code PGMNAME}, {@code TITLE02},
 * {@code CURTIME}; transaction-detail outputs {@code TRNID}, {@code CARDNUM},
 * {@code TTYPCD}, {@code TCATCD}, {@code TRNSRC}, {@code TDESC}, {@code TRNAMT},
 * {@code TORIGDT}, {@code TPROCDT}, {@code MID}, {@code MNAME}, {@code MCITY},
 * {@code MZIP}; plus the {@code ERRMSG} status line at row 23). The COBOL
 * controller writes those fields via the {@code COTRN1AO} output projection but
 * never reads them back via {@code COTRN1AI}, so the Java input DTO omits them.
 * Only {@code TRNIDIN} (operator-typed) and the {@code EIBAID} byte (which AID
 * key the operator pressed) materially affect the controller's behaviour. The
 * record therefore declares exactly two components &mdash;
 * {@link #transactionIdIn()} and {@link #aidKey()} &mdash; in faithful
 * preservation of the COBOL program's observable behaviour, consistent with
 * AAP &sect;0.7.1 (minimal change &amp; preserve-as-is).
 *
 * <h2>Null vs empty discipline</h2>
 * <p>This record is <em>strict</em> about nulls: the compact constructor rejects
 * any {@code null} component with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}. An empty string is
 * <em>permitted</em> for {@code transactionIdIn} and represents the "no value
 * typed" case (the legacy COBOL idiom is that an unfilled BMS field arrives as
 * {@code SPACES} or {@code LOW-VALUES}; the Java convention here is that the
 * upstream CICS-RECEIVE-MAP adapter normalises that to either {@code ""} or
 * a string of spaces). Either form satisfies {@link #isTransactionIdBlank()}.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The record is therefore safe to share across threads without
 * synchronisation. Combined with the virtual-thread fan-out mandated by AAP
 * &sect;0.6.6 for I/O-bound parallelism and the {@link java.lang.ScopedValue}
 * propagation of batch-run context mandated by JEP 506 in AAP &sect;0.6.6,
 * sharing this DTO across worker threads carries no risk.
 *
 * <h2>Cross-screen consistency with {@code CoTrn02Input}</h2>
 * <p>The {@link AidKey} enum below is intentionally identical (same seventeen
 * values in the same order) to the {@code AidKey} enum on the sibling
 * {@code CoTrn02Input} record (Add Transaction screen) and to the
 * {@code AidKey} enum on {@code CoTrn00Input} (Transaction List screen). This
 * uniformity lets the future CICS-RECEIVE-MAP adapter use a single
 * {@code EIBAID}-to-{@code AidKey} mapping function across the entire
 * transaction sub-package, and lets {@code CoTrn01C}'s pattern-matching
 * dispatch switch use exhaustiveness checking against the same enum on every
 * transaction screen.
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the
 *       compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; the
 *       {@code transactionIdIn} field is the raw BMS string, never a date type;
 *       any date parsing happens inside the application logic via
 *       {@code java.time}.</li>
 *   <li>No {@code double} or {@code float} &mdash; this DTO carries no monetary
 *       fields.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features
 *       (records, JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * @param transactionIdIn  the {@code TRNIDIN} value typed by the operator
 *                         (mapped from {@code TRNIDINI PIC X(16)} in the
 *                         COBOL symbolic copybook) &mdash; the transaction
 *                         identifier to look up. May be empty (no value
 *                         typed yet); must not be {@code null}.
 * @param aidKey           the 3270 Attention Identifier (AID byte) decoded
 *                         from {@code EIBAID} into one of the seventeen
 *                         {@link AidKey} values; must not be {@code null}.
 * @see com.blitzy.carddemo.application.transaction.CoTrn01Input.AidKey
 * @since 1.0.0
 */
public record CoTrn01Input(
        String transactionIdIn,
        AidKey aidKey) {

    /**
     * 3270 Attention Identifier (AID) decoded from the {@code EIBAID} byte
     * returned by CICS at {@code RECEIVE-MAP} time.
     *
     * <p>The COTRN01 controller dispatches on a four-value subset of this
     * enum:
     * <ul>
     *   <li>{@link #ENTER} &mdash; fetch the transaction record identified by
     *       {@link CoTrn01Input#transactionIdIn()}. If the field is blank the
     *       controller emits {@code "Tran ID can NOT be empty..."} instead of
     *       attempting the {@code READ}.</li>
     *   <li>{@link #PF3} &mdash; return to the calling program (typically
     *       {@code COMEN01C}, the main menu).</li>
     *   <li>{@link #PF4} &mdash; clear the {@code TRNIDIN} field and
     *       re-display the screen for another lookup.</li>
     *   <li>{@link #PF5} &mdash; transfer ({@code XCTL}) to
     *       {@code COTRN00C}, the transaction-browse list screen.</li>
     * </ul>
     * Any other value (including {@link #CLEAR}, {@link #PA1}, {@link #PA2},
     * {@link #PF1}, {@link #PF2}, {@link #PF6}, {@link #PF7}, {@link #PF8},
     * {@link #PF9}, {@link #PF10}, {@link #PF11}, {@link #PF12}, and
     * {@link #OTHER}) yields the {@code CCDA-MSG-INVALID-KEY} error in the
     * controller's dispatch switch and re-displays the screen.
     *
     * <p>The full seventeen-value enum is preserved here (rather than the
     * four-value COTRN01-only subset) for two reasons. First, the agent
     * prompt's Validation Phase 1 syntactic checklist explicitly mandates the
     * seventeen values in this exact order. Second, sharing the same
     * {@code AidKey} type across {@code CoTrn00Input}, {@code CoTrn01Input},
     * and {@code CoTrn02Input} lets the future CICS-RECEIVE-MAP adapter
     * decode {@code EIBAID} once and emit the same enum constant on whichever
     * input DTO it is constructing &mdash; the AID-decoder logic stays
     * uniform across all transaction-package screens.
     *
     * <p>Why an enum rather than a sealed interface? AAP &sect;0.6.10 reserves
     * sealed interfaces for COBOL constructs that partition a value space
     * (REDEFINES and 88-level data taxonomies). The AID key is a closed set
     * of opaque dispatch tokens with no payload, which is exactly the case
     * for which a plain {@code enum} is idiomatic.
     */
    public enum AidKey {

        /** The user pressed ENTER &mdash; fetch on the COTRN01 screen. */
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

        /** PF3 &mdash; back to the calling program (typically {@code COMEN01C}). */
        PF3,

        /** PF4 &mdash; clear the {@code TRNIDIN} field on the COTRN01 screen. */
        PF4,

        /** PF5 &mdash; XCTL to {@code COTRN00C} (transaction-browse list). */
        PF5,

        /** PF6. */
        PF6,

        /** PF7. */
        PF7,

        /** PF8. */
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
     * <p>Enforces a single invariant on every constructed instance:
     * <strong>non-null components</strong>. Each of {@code transactionIdIn}
     * and {@code aidKey} must be non-null. A {@code null} argument is
     * rejected with a {@link NullPointerException} carrying the offending
     * component name as the exception detail message. The COBOL idiom
     * (RECEIVE-MAP fields are always {@code SPACES} or {@code LOW-VALUES},
     * never undefined) translates to the Java discipline of carrying the
     * empty {@link String} for "no value" rather than {@code null}.
     *
     * <p>This constructor takes advantage of <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalized in Java 25): each
     * {@link java.util.Objects#requireNonNull(Object, String)} call runs
     * before the implicit canonical field-assignment, which is exactly the
     * place to capture COBOL-style "validate before bind" semantics as
     * described in AAP &sect;0.6.3.
     *
     * <p>Note that empty {@link String} arguments are permitted for
     * {@code transactionIdIn} and represent the legitimate "no transaction
     * id typed" case (handled by {@link #isTransactionIdBlank()}). Only
     * {@code null} is rejected.
     *
     * @throws NullPointerException if {@code transactionIdIn} or
     *                              {@code aidKey} is {@code null}
     */
    public CoTrn01Input {
        Objects.requireNonNull(transactionIdIn, "transactionIdIn");
        Objects.requireNonNull(aidKey, "aidKey");
    }

    /**
     * Returns an "empty" input record suitable for the first dispatch into
     * the COTRN01 screen: the {@code transactionIdIn} is the empty
     * {@link String} and the {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program
     * invocation: {@code DFHCOMMAREA} has zero length (no inbound state) or
     * carries no transaction-id payload, and the controller treats the
     * screen as a fresh view with no value yet entered. The AID key
     * defaults to {@link AidKey#ENTER} rather than {@link AidKey#OTHER}
     * because the controller's first-invocation path is functionally
     * identical to an explicit ENTER press with empty input (both fall
     * into the "{@code TRNIDINI = SPACES OR LOW-VALUES}" branch of
     * {@code PROCESS-ENTER-KEY}, which emits the empty-field message and
     * re-displays the screen).
     *
     * <p>The helper exists so that test harnesses, the future
     * CICS-RECEIVE-MAP adapter, and the application controller all share a
     * single, named way to construct the legitimate "blank" state without
     * each having to repeat the literal {@code new CoTrn01Input("",
     * AidKey.ENTER)} expression.
     *
     * @return a fully-blank {@code CoTrn01Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn01Input empty() {
        return new CoTrn01Input("", AidKey.ENTER);
    }

    /**
     * Returns {@code true} when {@link #transactionIdIn()} is blank
     * &mdash; either the empty {@link String} or a string consisting only
     * of whitespace characters as defined by {@link String#isBlank()}.
     *
     * <p>Used by the COBOL {@code PROCESS-ENTER-KEY} paragraph (and by its
     * Java translation in {@code CoTrn01C}) to detect the
     * "{@code Tran ID can NOT be empty...}" condition. The COBOL test is
     * {@code IF TRNIDINI = SPACES OR LOW-VALUES}, which the Java helper
     * preserves verbatim by:
     * <ul>
     *   <li>treating any whitespace-only string as blank
     *       (via {@link String#isBlank()}), matching the
     *       {@code SPACES} idiom; and</li>
     *   <li>treating a {@code null} reference as blank, matching the
     *       {@code LOW-VALUES} idiom (the COBOL {@code LOW-VALUES} figurative
     *       constant fills a {@code PIC X(n)} field with binary zeros, which
     *       the Java translation represents either as the empty string,
     *       a string of spaces, or {@code null} depending on whether the
     *       upstream adapter normalised the value).</li>
     * </ul>
     *
     * <p>The defensive {@code null} check here is redundant for any record
     * produced through the canonical constructor (the
     * {@link java.util.Objects#requireNonNull(Object, String)} guard rejects
     * a {@code null} {@code transactionIdIn} before the field is bound), but
     * the layered defence is cheap and clarifies the helper's contract for
     * code paths that may bypass the canonical constructor (for example,
     * serialisation frameworks that reflect over the record's fields).
     *
     * @return {@code true} when the transaction-id is null, empty, or
     *         whitespace-only; {@code false} otherwise
     */
    public boolean isTransactionIdBlank() {
        return transactionIdIn == null || transactionIdIn.isBlank();
    }
}
