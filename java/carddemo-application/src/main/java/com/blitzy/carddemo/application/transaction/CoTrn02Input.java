/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and the modules it reads). This brings in java.lang.String --
// the type of the fourteen operator-editable text components and the source of the
// isBlank() utility invoked by isAllBlank() -- and java.util.Objects (the source of the
// requireNonNull() null-safety guard inside the compact constructor). It also brings in
// the exception classes (java.lang.NullPointerException raised by requireNonNull).
//
// No additional imports are required or permitted on this file. The internal_imports
// list in the file schema is empty (this record carries no domain references), and the
// only external import allowed by the schema is java.base itself. The single
// "import module java.base;" line replaces the verbose pair of
// "import java.lang.String;" (implicit in every compilation unit) and
// "import java.util.Objects;", and aligns the file with the AAP §0.6.7 / §0.7.3
// mandate to use Module Import Declarations finalized in Java 25.
import module java.base;

/**
 * BMS input record carrying every operator-typed value received from the 3270
 * terminal for the <strong>COTRN02</strong> (Add Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN02.bms} (mapset {@code COTRN02},
 *       map {@code COTRN2A}, size 24x80, {@code CTRL=(ALARM,FREEKB)},
 *       {@code EXTATT=YES}, {@code LANG=COBOL}, {@code MODE=INOUT},
 *       {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN02.CPY} (input group
 *       {@code 01 COTRN2AI}). The copybook declares twenty-one
 *       length/flag/attribute/value clusters &mdash; six header echoes
 *       ({@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
 *       {@code TITLE02}, {@code CURTIME}) which are populated by
 *       {@code SEND MAP} and never typed by the operator, fourteen
 *       operator-editable data fields enumerated below, and one error-message
 *       field ({@code ERRMSG}) which is also output-only ({@code ATTRB=ASKIP}).
 *       Only the fourteen operator-editable fields are carried by this record;
 *       the seven output-only fields belong to {@code CoTrn02Output}.</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN02C.cbl} (transaction
 *       {@code CT02}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated
 * into <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the input view of the BMS symbolic
 * structure {@code COTRN2AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn02C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a
 * plain Java carrier built around finalized Java 25 language features only
 * (records, sealed types, pattern matching, JEP 511 module imports, JEP 513
 * flexible constructor bodies).
 *
 * <h2>Add Transaction semantics</h2>
 * <p>COTRN02 is the <em>add / data entry</em> screen for a single new
 * transaction. The operator types into <strong>fourteen</strong> input fields
 * (BMS {@code ATTRB=(FSET,NORM,UNPROT)}, colour {@code GREEN}, highlight
 * {@code UNDERLINE}); every other BMS field on this screen is
 * {@code ATTRB=(ASKIP,...)} &mdash; read-only, populated by {@code SEND MAP}
 * and ignored by {@code RECEIVE MAP}.
 *
 * <h2>Field inventory</h2>
 * <p>The fourteen operator-editable BMS fields, in screen order (top-to-bottom,
 * left-to-right), with the BMS positions and lengths verified against
 * {@code app/bms/COTRN02.bms} lines 85 through 285:
 * <table border="1" style="border-collapse:collapse">
 *   <caption>COTRN02 input-field inventory</caption>
 *   <tr><th>BMS name</th><th>Java component</th><th>Row,Col</th>
 *       <th>Length</th><th>Format hint</th></tr>
 *   <tr><td>{@code ACTIDIN}</td><td>{@link #accountId()}</td>
 *       <td>6,21</td><td>11</td><td>numeric</td></tr>
 *   <tr><td>{@code CARDNIN}</td><td>{@link #cardNumber()}</td>
 *       <td>6,55</td><td>16</td><td>numeric</td></tr>
 *   <tr><td>{@code TTYPCD}</td><td>{@link #typeCode()}</td>
 *       <td>10,15</td><td>2</td><td>alpha</td></tr>
 *   <tr><td>{@code TCATCD}</td><td>{@link #categoryCode()}</td>
 *       <td>10,36</td><td>4</td><td>numeric</td></tr>
 *   <tr><td>{@code TRNSRC}</td><td>{@link #source()}</td>
 *       <td>10,54</td><td>10</td><td>alpha</td></tr>
 *   <tr><td>{@code TDESC}</td><td>{@link #description()}</td>
 *       <td>12,19</td><td>60</td><td>free text</td></tr>
 *   <tr><td>{@code TRNAMT}</td><td>{@link #amount()}</td>
 *       <td>14,14</td><td>12</td><td>-99999999.99</td></tr>
 *   <tr><td>{@code TORIGDT}</td><td>{@link #origDate()}</td>
 *       <td>14,42</td><td>10</td><td>YYYY-MM-DD</td></tr>
 *   <tr><td>{@code TPROCDT}</td><td>{@link #procDate()}</td>
 *       <td>14,68</td><td>10</td><td>YYYY-MM-DD</td></tr>
 *   <tr><td>{@code MID}</td><td>{@link #merchantId()}</td>
 *       <td>16,19</td><td>9</td><td>numeric</td></tr>
 *   <tr><td>{@code MNAME}</td><td>{@link #merchantName()}</td>
 *       <td>16,48</td><td>30</td><td>free text</td></tr>
 *   <tr><td>{@code MCITY}</td><td>{@link #merchantCity()}</td>
 *       <td>18,21</td><td>25</td><td>free text</td></tr>
 *   <tr><td>{@code MZIP}</td><td>{@link #merchantZip()}</td>
 *       <td>18,67</td><td>10</td><td>numeric</td></tr>
 *   <tr><td>{@code CONFIRM}</td><td>{@link #confirmation()}</td>
 *       <td>21,63</td><td>1</td><td>Y / N / blank</td></tr>
 * </table>
 *
 * <h2>Two competing identifier inputs (ACTIDIN <em>or</em> CARDNIN)</h2>
 * <p>The COTRN02 screen presents two side-by-side identifiers separated by the
 * BMS literal {@code '(or)'} at row 6, column 37. The operator types into
 * exactly one of them &mdash; either {@code ACTIDIN} (an 11-character account
 * identifier) or {@code CARDNIN} (a 16-character card number) &mdash; or
 * leaves both blank to trigger a missing-key error. This DTO accepts both as
 * independent string components; the COBOL paragraph
 * {@code VALIDATE-INPUT-KEY-FIELDS} (whose translation lives in
 * {@code CoTrn02C}) is responsible for enforcing the mutual-exclusion
 * invariant. Per Agent Action Plan &sect;0.4.1 the DTO is purely a structural
 * carrier &mdash; field-content validation is the application class's
 * responsibility, not the DTO's.
 *
 * <h2>Three-state confirmation</h2>
 * <p>{@link #confirmation()} is a one-character tri-state: blank (initial,
 * shown before the operator has reviewed the entered values), {@code 'Y'}
 * (confirm and commit), or {@code 'N'} (cancel and clear). The
 * {@code processEnterKey()} method on {@code CoTrn02C} switches on this value
 * via a Java pattern-matching {@code switch} with exhaustive coverage.
 *
 * <h2>What this record does <em>not</em> carry</h2>
 * <ul>
 *   <li>The six header echoes ({@code TRNNAMEI}, {@code TITLE01I},
 *       {@code CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I},
 *       {@code CURTIMEI}) &mdash; the BMS structure declares input slots for
 *       them because every BMS named field has both an input and an output
 *       leaf, but every header field is {@code ATTRB=(ASKIP,FSET,NORM)} so the
 *       operator cannot edit it; the values are sent down by {@code SEND MAP}
 *       and the COBOL program ignores their input copies after
 *       {@code RECEIVE MAP}. They belong to {@code CoTrn02Output}.</li>
 *   <li>The error-message field ({@code ERRMSGI}) &mdash; output-only,
 *       {@code ATTRB=(ASKIP,BRT,FSET)} in red at row 23, column 1; populated by
 *       {@code SEND MAP} when a validation error must be reported back to the
 *       operator. Belongs to {@code CoTrn02Output}.</li>
 *   <li>Per-field length/attribute/colour echo flags ({@code *L}, {@code *F},
 *       {@code *A}, {@code *C}, {@code *P}, {@code *H}, {@code *V} suffix
 *       sub-fields generated by the BMS preprocessor). These are mainframe
 *       implementation details &mdash; on z/OS they convey whether the operator
 *       actually typed into the field, what colour to display the field on the
 *       next {@code SEND MAP}, etc. They have no analog in this Java DTO
 *       because the Java translation does not emulate the 3270 protocol; the
 *       only operator intent surface is the value string itself plus the
 *       single AID byte.</li>
 * </ul>
 *
 * <h2>3270 Attention Identifier handling</h2>
 * <p>The {@link #aidKey()} component carries the 3270 AID byte decoded from
 * {@code EIBAID} into one of the seventeen {@link AidKey} enum values.
 * The COTRN02 BMS map's PF-key footer at row 24 documents the four
 * keys the operator may press on this screen:
 * <blockquote>
 *   {@code ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.}
 * </blockquote>
 * The Java translation does not narrow the {@link AidKey} type to those four
 * values &mdash; the operator may press any of the seventeen 3270 AID keys at
 * any time, and the controller in {@code CoTrn02C} is responsible for
 * dispatching valid keys and producing an invalid-key error message for the
 * rest. Pattern-matching switches in {@code CoTrn02C} can branch on this enum
 * exhaustively (per Agent Action Plan &sect;0.6.10 the
 * compiler enforces exhaustiveness on every {@code switch} expression that
 * consumes an {@link AidKey}, so adding a new AID never silently slips past a
 * dispatcher).
 *
 * <h2>Immutability</h2>
 * <p>This is a Java {@code record}: every component is implicitly
 * {@code private final}, the accessor methods are auto-generated, the
 * {@code equals()} / {@code hashCode()} / {@code toString()} contracts are
 * derived component-wise, and the canonical constructor is the only mutation
 * surface. Once constructed, an instance is immutable; the {@link #empty()}
 * factory returns a fresh instance each call (records are not cached).
 *
 * <h2>Null safety</h2>
 * <p>The compact (canonical) constructor invokes
 * {@link java.util.Objects#requireNonNull(Object, String)} on every reference
 * component before binding any field, leveraging the JEP 513 Flexible
 * Constructor Bodies finalized in Java 25. A {@code null} for any component
 * raises {@link NullPointerException} with the component name as the message
 * &mdash; the same shape as the legacy COBOL {@code 0000-PERFORM-INITIAL-CHECK}
 * paragraph that rejects an uninitialised field on the receive side. <em>Empty
 * strings are permitted</em>: BMS sends spaces for any field the operator did
 * not type into, and the Java translation honours that contract by allowing
 * (but not requiring) the empty string. Numeric / date / Y-N validation lives
 * in {@code CoTrn02C.validateInputDataFields()} per the COBOL paragraph of the
 * same name, not in this DTO.
 *
 * <h2>Thread-safety</h2>
 * <p>Records with only immutable reference components (the fourteen
 * {@link String}s and one {@link AidKey}) are inherently thread-safe.
 * Concurrent reads from multiple virtual threads are safe and free of any
 * synchronisation requirement; this is essential for the virtual-thread
 * fan-out documented in Agent Action Plan &sect;0.6.6.
 *
 * <h2>External imports</h2>
 * <p>This file declares a <strong>single</strong> external import:
 * {@code import module java.base;} per the schema's {@code external_imports}
 * (Agent Action Plan &sect;0.6.7 / &sect;0.7.3, JEP 511 finalized in Java 25).
 * No internal modules are imported; the record carries no domain references.
 *
 * @param accountId      {@code ACTIDIN} value typed at BMS row 6 column 21
 *                       (length 11, attributes {@code FSET,IC,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Mutually exclusive with {@code cardNumber}: at runtime
 *                       exactly one of the two is filled (or both are blank to
 *                       trigger a missing-key error reported back via
 *                       {@code ERRMSG}). Must not be {@code null}; the empty
 *                       string is permitted and denotes "the operator did not
 *                       type into this field". Numeric-content validation is
 *                       performed by {@code CoTrn02C}, not by this DTO.
 * @param cardNumber     {@code CARDNIN} value typed at BMS row 6 column 55
 *                       (length 16, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Mutually exclusive with {@code accountId} (see above).
 *                       Must not be {@code null}; the empty string is
 *                       permitted.
 * @param typeCode       {@code TTYPCD} value typed at BMS row 10 column 15
 *                       (length 2, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Two-character transaction-type code; the operator
 *                       picks from the codes loaded into the TRANTYPE
 *                       reference dataset. Must not be {@code null}; the empty
 *                       string is permitted.
 * @param categoryCode   {@code TCATCD} value typed at BMS row 10 column 36
 *                       (length 4, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Four-character transaction-category code; the operator
 *                       picks from the codes loaded into the TRANCATG
 *                       reference dataset. Must not be {@code null}; the empty
 *                       string is permitted.
 * @param source         {@code TRNSRC} value typed at BMS row 10 column 54
 *                       (length 10, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Free-form transaction source token (e.g.
 *                       {@code POS}, {@code WEB}, {@code MAIL}). Must not be
 *                       {@code null}; the empty string is permitted.
 * @param description    {@code TDESC} value typed at BMS row 12 column 19
 *                       (length 60, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Free-form transaction description. Must not be
 *                       {@code null}; the empty string is permitted.
 * @param amount         {@code TRNAMT} value typed at BMS row 14 column 14
 *                       (length 12, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Signed-decimal transaction amount in the operator's
 *                       raw typed form &mdash; the BMS literal at row 15 column
 *                       13 documents the format hint {@code (-99999999.99)}.
 *                       Held as a {@link String} because the DTO is purely a
 *                       structural carrier; {@code CoTrn02C} performs the
 *                       parse via {@code Decimals.parsePicS9V99} (see Agent
 *                       Action Plan &sect;0.3.3) to produce a
 *                       {@link java.math.BigDecimal} with scale 2 and
 *                       {@code MathContext.DECIMAL128}. Must not be
 *                       {@code null}; the empty string is permitted.
 * @param origDate       {@code TORIGDT} value typed at BMS row 14 column 42
 *                       (length 10, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Origination date in the operator's raw typed form; the
 *                       BMS literal at row 15 column 41 documents the format
 *                       hint {@code (YYYY-MM-DD)}. Held as a {@link String}
 *                       because the DTO is purely a structural carrier;
 *                       {@code CoTrn02C} performs the parse via
 *                       {@link java.time.LocalDate#parse(CharSequence)}
 *                       (Agent Action Plan &sect;0.6.4) using a strict
 *                       resolver. Must not be {@code null}; the empty string
 *                       is permitted.
 * @param procDate       {@code TPROCDT} value typed at BMS row 14 column 68
 *                       (length 10, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Processing date in the operator's raw typed form; the
 *                       BMS literal at row 15 column 67 documents the format
 *                       hint {@code (YYYY-MM-DD)}. Held as a {@link String}
 *                       (see {@link #origDate} for parsing notes). Must not be
 *                       {@code null}; the empty string is permitted.
 * @param merchantId     {@code MID} value typed at BMS row 16 column 19
 *                       (length 9, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Nine-character merchant identifier. Must not be
 *                       {@code null}; the empty string is permitted.
 * @param merchantName   {@code MNAME} value typed at BMS row 16 column 48
 *                       (length 30, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Free-form merchant name. Must not be {@code null};
 *                       the empty string is permitted.
 * @param merchantCity   {@code MCITY} value typed at BMS row 18 column 21
 *                       (length 25, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Free-form merchant city. Must not be {@code null};
 *                       the empty string is permitted.
 * @param merchantZip    {@code MZIP} value typed at BMS row 18 column 67
 *                       (length 10, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Merchant ZIP code (typically a US 5-digit ZIP or
 *                       9-digit ZIP+4 in {@code 99999-9999} form, but the DTO
 *                       does not enforce a format). Must not be {@code null};
 *                       the empty string is permitted.
 * @param confirmation   {@code CONFIRM} value typed at BMS row 21 column 63
 *                       (length 1, attributes {@code FSET,NORM,UNPROT},
 *                       colour {@code GREEN}, highlight {@code UNDERLINE}).
 *                       Tri-state confirmation: blank means "operator has not
 *                       yet reviewed", {@code 'Y'} means "commit the
 *                       transaction", {@code 'N'} means "cancel and clear".
 *                       Must not be {@code null}; the empty string is
 *                       permitted.
 * @param aidKey         The 3270 Attention Identifier (AID byte) decoded from
 *                       {@code EIBAID} into one of the seventeen
 *                       {@link AidKey} values; must not be {@code null}.
 * @see com.blitzy.carddemo.application.transaction.CoTrn02Input.AidKey
 * @since 1.0.0
 */
public record CoTrn02Input(
        String accountId,
        String cardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        String amount,
        String origDate,
        String procDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String confirmation,
        AidKey aidKey) {

    /**
     * The 3270 Attention Identifier (AID) byte decoded into a Java enum.
     *
     * <p>The 3270 protocol identifies which "attention" key the operator
     * pressed to dispatch the screen back to the host via a one-byte AID
     * code carried in the {@code EIBAID} field of the CICS Execute Interface
     * Block. The COTRN02 BMS map's PF-key footer at row 24 documents the
     * four keys actively used by the Add Transaction screen:
     * <blockquote>
     *   {@code ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.}
     * </blockquote>
     * Any other AID byte produces an invalid-key error reported through
     * {@code ERRMSG} by the controller.
     *
     * <p>This enum mirrors the
     * {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} sealed
     * hierarchy described by Agent Action Plan &sect;0.6.10, which catalogs
     * the seventeen AID-key 88-level conditions defined in
     * {@code app/cpy/CVCRD01Y.cpy:§CCARD-AID}: ENTER, CLEAR, PA1, PA2, and
     * twelve programmed-function keys (PF1 through PF12). The
     * {@link #OTHER} member is a safety bucket for AID bytes that do not
     * decode to any of the sixteen named keys.
     *
     * <p>Per Agent Action Plan &sect;0.6.10, an enum (as opposed to a sealed
     * interface) is the appropriate representation here because the value
     * space is fully closed, the seventeen members carry no associated
     * payload data, and each operator action produces exactly one AID code
     * &mdash; an {@code enum} is idiomatic.
     */
    public enum AidKey {

        /** The user pressed ENTER &mdash; commit / continue on COTRN02. */
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

        /** PF4 &mdash; clear all input fields on the COTRN02 screen. */
        PF4,

        /** PF5 &mdash; copy the last transaction's fields into the input area. */
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
     * Canonical (compact) constructor enforcing null-safety on every
     * reference component.
     *
     * <p>Per JEP 513 (Flexible Constructor Bodies, finalized in Java 25),
     * statements that precede the implicit canonical assignment may freely
     * validate or normalise the formal parameters before they bind to
     * record components. This is the appropriate place for COBOL-style
     * input validation: the legacy {@code 0000-PERFORM-INITIAL-CHECK}
     * paragraph in {@code COTRN02C} performs the same fixed-order
     * null-equivalent check ({@code MOVE LOW-VALUES} test) on every
     * incoming field before the program proceeds.
     *
     * <p>Each component is asserted non-null via
     * {@link java.util.Objects#requireNonNull(Object, String)}. The second
     * argument is the parameter name, which appears as the
     * {@link NullPointerException} message &mdash; this makes "which field
     * was null" diagnosable directly from the stack trace without
     * additional logging.
     *
     * <p><strong>Empty strings are permitted</strong>: BMS sends a string
     * of spaces for any field the operator did not type into, and the Java
     * translation honours that contract by accepting (but not requiring)
     * the empty {@link String}. Field-content validation &mdash; numeric
     * format check on {@link #amount}, ISO date parse on {@link #origDate}
     * and {@link #procDate}, Y / N tri-state check on
     * {@link #confirmation}, mutual-exclusion check on
     * {@link #accountId} vs {@link #cardNumber} &mdash; is performed by
     * {@code CoTrn02C.validateInputDataFields()} (the Java translation of
     * the COBOL paragraph of the same name), NOT by this DTO. The DTO is
     * purely a structural carrier per Agent Action Plan &sect;0.4.1.
     *
     * @throws NullPointerException if any of the fifteen components
     *                              is {@code null}; the exception message
     *                              identifies which component
     */
    public CoTrn02Input {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(cardNumber, "cardNumber");
        Objects.requireNonNull(typeCode, "typeCode");
        Objects.requireNonNull(categoryCode, "categoryCode");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(origDate, "origDate");
        Objects.requireNonNull(procDate, "procDate");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(merchantCity, "merchantCity");
        Objects.requireNonNull(merchantZip, "merchantZip");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(aidKey, "aidKey");
    }

    /**
     * Returns an "empty" input record suitable for the first dispatch into
     * the COTRN02 screen: every operator-editable field is the empty
     * {@link String} and the {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program
     * invocation: {@code DFHCOMMAREA} has zero length (no inbound state) or
     * carries no Add-Transaction payload, and the controller treats the
     * screen as a fresh add form with no values yet entered. The AID key
     * defaults to {@link AidKey#ENTER} rather than {@link AidKey#OTHER}
     * because the controller's first-invocation path is functionally
     * identical to an explicit ENTER press with all fields blank: validate
     * (everything is blank so report missing-key error via {@code ERRMSG}),
     * re-display the empty screen.
     *
     * <p>This factory is primarily a convenience for unit tests and for
     * synthetic boot-up dispatches; it allocates a fresh instance each
     * call (records are not cached). Production code receiving a real
     * {@code RECEIVE MAP} payload populates the record directly via the
     * canonical constructor.
     *
     * @return a fully-blank {@code CoTrn02Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn02Input empty() {
        return new CoTrn02Input(
                "", "", "", "", "", "",
                "", "", "", "", "", "",
                "", "", AidKey.ENTER);
    }

    /**
     * Returns {@code true} when every operator-editable string component
     * is blank &mdash; either the empty {@link String} or a string
     * consisting only of whitespace characters as defined by
     * {@link String#isBlank()}.
     *
     * <p>This mirrors the legacy COBOL idiom in {@code COTRN02C}'s
     * {@code INITIALIZE-ALL-FIELDS} (or equivalently
     * {@code RECEIVE-INPUT-SCREEN}) paragraph, which tests every input
     * field for {@code SPACES} or {@code LOW-VALUES} to detect a
     * freshly-cleared screen. The COBOL test {@code IF field = SPACES OR
     * LOW-VALUES} is preserved here as {@code field.isBlank()}, which
     * matches both idioms:
     * <ul>
     *   <li>treating any whitespace-only string as blank
     *       (via {@link String#isBlank()}), matching the
     *       {@code SPACES} idiom; and</li>
     *   <li>treating the empty {@link String} as blank, matching the
     *       {@code LOW-VALUES} idiom (the COBOL {@code LOW-VALUES}
     *       figurative constant fills a {@code PIC X(n)} field with
     *       binary zeros, which the Java translation represents as the
     *       empty {@link String} after the upstream BMS adapter trims
     *       trailing spaces and converts {@code LOW-VALUES} to nulls /
     *       empties).</li>
     * </ul>
     *
     * <p>The helper deliberately does <em>not</em> consider the
     * {@link #aidKey} component &mdash; an {@link AidKey} is always present
     * (the canonical constructor enforces non-null), and the "is screen
     * blank?" check is by definition about the operator's typed text
     * values, not about which dispatch key was pressed.
     *
     * <p>The helper is short-circuit: it stops at the first non-blank
     * field, so the cost in the common case ({@code accountId} or
     * {@code cardNumber} populated) is one or two
     * {@link String#isBlank()} calls.
     *
     * @return {@code true} when all fourteen operator-editable string
     *         components are null-blank or whitespace-only; {@code false}
     *         when at least one carries non-whitespace content
     */
    public boolean isAllBlank() {
        return accountId.isBlank()
                && cardNumber.isBlank()
                && typeCode.isBlank()
                && categoryCode.isBlank()
                && source.isBlank()
                && description.isBlank()
                && amount.isBlank()
                && origDate.isBlank()
                && procDate.isBlank()
                && merchantId.isBlank()
                && merchantName.isBlank()
                && merchantCity.isBlank()
                && merchantZip.isBlank()
                && confirmation.isBlank();
    }
}
