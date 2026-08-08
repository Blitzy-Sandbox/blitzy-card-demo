package com.vsergeychik.carddemo.admin.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * The main-menu option table of the COBOL copybook {@code app/cpy/COMEN02Y.cpy}, transcribed
 * byte-for-byte: {@code 01 CARDDEMO-MAIN-MENU-OPTIONS}, its {@code CDEMO-MENU-OPT-COUNT} field, the
 * ten literal entries of {@code CDEMO-MENU-OPTIONS-DATA}, and the twelve-element
 * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} table that redefines them.
 *
 * <p>This is the sole Java type for {@code COMEN02Y}. It is a pure, immutable, copybook-derived
 * constant table: it performs no I/O, holds no framework state, carries no annotation of any kind,
 * and takes no decision. It is a like-for-like translation of the copybook, so where the copybook
 * does something odd this class does the same odd thing and records why.
 *
 * <h2>The source, reproduced verbatim</h2>
 *
 * {@code app/cpy/COMEN02Y.cpy} lines 19 to 92 declare:
 *
 * <pre>
 *  01 CARDDEMO-MAIN-MENU-OPTIONS.
 *
 *    05 CDEMO-MENU-OPT-COUNT           PIC 9(02) VALUE 10.
 *
 *    05 CDEMO-MENU-OPTIONS-DATA.
 *
 *      10 FILLER                       PIC 9(02) VALUE 1.
 *      10 FILLER                       PIC X(35) VALUE
 *          'Account View                       '.
 *      10 FILLER                       PIC X(08) VALUE 'COACTVWC'.
 *      10 FILLER                       PIC X(01) VALUE 'U'.
 *      ... eight further entries ...
 *      10 FILLER                       PIC 9(02) VALUE 10.
 *      10 FILLER                       PIC X(35) VALUE
 *          'Bill Payment                       '.
 *      10 FILLER                       PIC X(08) VALUE 'COBIL00C'.
 *      10 FILLER                       PIC X(01) VALUE 'U'.
 *
 *    05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA.
 *      10 CDEMO-MENU-OPT OCCURS 12 TIMES.
 *        15 CDEMO-MENU-OPT-NUM           PIC 9(02).
 *        15 CDEMO-MENU-OPT-NAME          PIC X(35).
 *        15 CDEMO-MENU-OPT-PGMNAME       PIC X(08).
 *        15 CDEMO-MENU-OPT-USRTYPE       PIC X(01).
 * </pre>
 *
 * <h2>The table holds TWELVE slots and TEN are populated - these are two different facts</h2>
 *
 * {@link #TABLE_SIZE} is {@value #TABLE_SIZE} and {@link #ACTIVE_OPTION_COUNT} is
 * {@value #ACTIVE_OPTION_COUNT}. They are kept as two separate constants on purpose, because
 * conflating them is the single defect this class exists to prevent: {@code VALUE 10} on line 21 is
 * the <em>active count</em> the program validates a typed option against, while
 * {@code OCCURS 12 TIMES} on line 88 is the <em>table size</em> the program subscripts into. The
 * twelve-slot table is not a typo, and it is corroborated three independent ways:
 *
 * <ol>
 *   <li>{@code app/cpy/COMEN02Y.cpy:88} reads {@code 10 CDEMO-MENU-OPT OCCURS 12 TIMES.} literally.</li>
 *   <li>The mapset {@code app/bms/COMEN01.bms} defines twelve display slots, {@code OPTN001} at line
 *       80 through {@code OPTN012} at line 135, each {@code DFHMDF ... LENGTH=40}; the symbolic map
 *       {@code app/cpy-bms/COMEN01.CPY} declares twelve matching {@code OPTN00nI PIC X(40)} items,
 *       {@code OPTN001I} at line 60 through {@code OPTN012I} at line 126.</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:248-273} dispatches {@code EVALUATE WS-IDX} to all twelve
 *       ({@code WHEN 1} through {@code WHEN 12}, then {@code WHEN OTHER CONTINUE}), whereas the
 *       sibling admin menu's equivalent stops at ten.</li>
 * </ol>
 *
 * Slots 11 and 12 are therefore <strong>present as slots and absent as entries</strong>, and are never
 * trimmed away. What they contain at run time is not something this copybook determines: they carry no
 * {@code VALUE} clause, and they lie past the very end of the group the overlay redefines, so IBM
 * Enterprise COBOL's rule - storage is initialised from applicable {@code VALUE} clauses - reaches them
 * with nothing to apply. This class therefore reports them as absent rather than asserting a
 * zero-and-blanks entry, which is what it used to do and what nothing in the copybook supports. See
 * {@link #isSpecified(int)} and {@link #unspecifiedTailSpan()}.
 *
 * <h2>The REDEFINES overlay is LARGER than the storage it redefines - documented, not corrected</h2>
 *
 * {@code CDEMO-MENU-OPTIONS-DATA} spells out only the ten populated entries, so its literal storage
 * is {@value #POPULATED_DATA_LENGTH} bytes ({@value #ACTIVE_OPTION_COUNT} x
 * {@value #ENTRY_LENGTH}). The overlay {@code CDEMO-MENU-OPTIONS} declared over it at line 87 is
 * {@value #TABLE_LENGTH} bytes ({@value #TABLE_SIZE} x {@value #ENTRY_LENGTH}) - ninety-two bytes
 * wider. That asymmetry is exactly why slots 11 and 12 exist and are empty, and it is recorded here
 * rather than "fixed": a like-for-like migration documents a conflict it finds in the source instead
 * of silently resolving it. The whole group is consequently {@value #GROUP_LENGTH} bytes
 * ({@value #MENU_OPT_COUNT_LENGTH} + {@value #TABLE_LENGTH}), which {@link #GROUP_LAYOUT} asserts
 * mechanically the moment this class initialises.
 *
 * <p>Both views are exposed, over one backing span at offset {@value #TABLE_OFFSET}:
 * {@link #populatedDataSpan()} is the {@value #POPULATED_DATA_LENGTH}-byte literal-storage view and
 * {@link #tableSpan()} is the {@value #TABLE_LENGTH}-byte {@code OCCURS} projection. As typed lists
 * they are {@link #activeOptions()} and {@link #options()}.
 *
 * <h2>Line 69 is a comment and is deliberately NOT used</h2>
 *
 * Option 8's {@code PIC X(35) VALUE} is declared on line 68 and carries <em>two</em> candidate
 * literals:
 *
 * <pre>
 *  10 FILLER                       PIC X(35) VALUE
 * *        'Transaction Add (Admin Only)       '.
 *          'Transaction Add                    '.
 * </pre>
 *
 * The first, on line 69, has an asterisk in column 7, which makes it a COBOL comment: an abandoned
 * earlier wording sitting directly above the live value on line 70. This class exposes
 * <strong>only</strong> the live line 70 value, {@code Transaction Add} padded to
 * {@value #OPT_NAME_LENGTH}. The commented wording is recorded here so that nobody mistakes the
 * live value for a truncation and nobody "restores" it; it is not offered as an alternative
 * constant, is not blended with the live value, and must never be substituted for it.
 *
 * <p><strong>Nothing may be inferred from that dead comment.</strong> In particular option 8's live
 * {@code CDEMO-MENU-OPT-USRTYPE} is {@code 'U'} - line 72 - and <em>not</em> {@code 'A'}. All ten
 * populated entries carry {@code 'U'}. Setting option 8 to {@code 'A'} because the comment says
 * "Admin Only" would be a behaviour change, because the consuming program compares this column
 * against {@code 'A'} to deny access, and it would wrongly lock every regular user out of
 * Transaction Add.
 *
 * <p>The same pattern occurs twice more in this call chain and is resolved the same way each time:
 * {@code app/cpy/COTTL01Y.cpy:21} against the live line 22 - already handled that way in the
 * sibling {@code common.ScreenTitles} - and the two commented {@code MOVE} statements at
 * {@code app/cbl/COMEN01C.cbl:149-150}. In every case the live value wins and the dead one is
 * documented.
 *
 * <h2>Values are handed back untrimmed, and that is load-bearing</h2>
 *
 * {@link MenuOption#menuOptName()}, {@link MenuOption#menuOptPgmName()} and
 * {@link MenuOption#menuOptUsrType()} return their full declared widths, right-space-padded, exactly
 * as the copybook stores them. Never trim, strip or normalise them. Two verified consumer behaviours
 * depend on the padding:
 *
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl:146} evaluates
 *       {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} - a five-byte reference
 *       modification of the eight-byte column. A trimmed value of fewer than five characters cannot
 *       be sliced that way.</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:159-163} composes its message with
 *       {@code CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE}, which stops at the <em>first</em>
 *       space. For option 1 that yields {@code Account}, so the assembled text reads
 *       {@code This option Accountis coming soon ...} with no space before {@code is}. Trimming the
 *       name here would make that quirk unreproducible.</li>
 * </ul>
 *
 * The option number is exposed twice for the same reason: as a scaleless {@code int}
 * ({@link MenuOption#menuOptNum()}) and as its raw two-byte zero-filled zoned image
 * ({@link MenuOption#menuOptNumImage()}). {@code app/cbl/COMEN01C.cbl:243} moves
 * {@code CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE} into a {@code PIC X(40)} field, which
 * transfers the raw display image, so option 1 contributes {@code "01"} and option 10 contributes
 * {@code "10"}. An {@code int} alone cannot reproduce the leading zero.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * All decision logic belongs to the consuming service, not here, so that it remains reachable by a
 * plain unit test with no HTTP layer in the path. This class contains no option-count validation, no
 * {@code 'DUMMY'} prefix test, no user-type authorisation decision, no option-line composition and
 * no "coming soon" message assembly. The user-type column is published as <em>data</em>; the
 * comparison against {@code 'A'} - whose vocabulary is the {@code CDEMO-USRTYP-ADMIN 'A'} and
 * {@code CDEMO-USRTYP-USER 'U'} condition names of {@code app/cpy/COCOM01Y.cpy:26-28}, owned by the
 * navigation-context type - stays in the service.
 *
 * <p>Everything a consumer needs is nonetheless present: {@link #ACTIVE_OPTION_COUNT} to bound both
 * the build loop and the typed-option validation, {@link #optionBySubscript(int)} for checked
 * one-based access, {@link MenuOption#menuOptNumImage()} and {@link MenuOption#menuOptName()} to
 * compose the thirty-nine byte {@code number + ". " + name} line into a space-cleared
 * {@code PIC X(40)} field, and the untrimmed {@link MenuOption#menuOptPgmName()} to slice for the
 * {@code 'DUMMY'} test.
 *
 * <h2>Subscripts are one-based, and an out-of-range subscript is rejected rather than clamped</h2>
 *
 * COBOL {@code OCCURS} subscripts start at 1; Java list positions start at 0. The two access styles
 * are named so they cannot be confused: {@link #options()} is the zero-based list and
 * {@link #optionBySubscript(int)} takes the one-based COBOL subscript. The latter <strong>rejects</strong>
 * anything outside {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE} instead of clamping it, which is
 * load-bearing rather than stylistic: the user-type filter at {@code app/cbl/COMEN01C.cbl:136-143}
 * is <em>not</em> guarded by the program's error flag, and {@code WS-OPTION PIC 9(02)} holds 0 to 99,
 * so the legacy program can and does subscript outside 1 to 12 after a failed numeric validation.
 * The consuming service is the place that bounds the access; this class's job is to give it a checked
 * accessor to bound with, and never to paper over the condition.
 *
 * <h2>Immutability and thread safety</h2>
 *
 * Every member is an immutable {@code static final} value: {@link MenuOption} is a record of
 * {@code int} and {@link String} components, the two option lists are unmodifiable, the descriptor
 * list is unmodifiable, and {@link #GROUP_LAYOUT} defensively copies its own span list. No array
 * escapes without being copied, no setter exists, and there is no mutable static state whatsoever,
 * so the table is safe to read concurrently from any number of request threads, batch steps or
 * chunks. The class is {@code final} and cannot be instantiated.
 *
 * <h2>Byte images</h2>
 *
 * The consuming program reads this table from {@code WORKING-STORAGE} rather than from a dataset, so
 * a byte image is not needed at run time. One is provided anyway, for the parity harness's
 * field-name-keyed diffing and to make the geometry provable: {@link #encode(Charset)},
 * {@link #declaredImage(Charset)}, {@link #decode(byte[], Charset)} and
 * {@link #fieldImages(byte[], Charset)}. Every one of them takes the {@link Charset} as an explicit
 * parameter and routes all byte handling through {@link FixedWidthCodec}. No method derives a
 * charset from the platform, and this class does not reference the configuration type that resolves
 * the code pages.
 *
 * <p>Provenance: {@code app/cpy/COMEN02Y.cpy}, version footer
 * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}. The copybook is a
 * read-only parity oracle and is never modified. Its own header comment on line 2 reads
 * "CardDemo - Admin Menu Options" even though the group it declares is
 * {@code CARDDEMO-MAIN-MENU-OPTIONS} and its sole consumer is the <em>main</em> menu program
 * {@code app/cbl/COMEN01C.cbl}; that mislabelling is recorded here and left alone too.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 */
public final class MenuOptions {

    // =================================================================================================
    // Copybook item names, verbatim. The parity differ compares field by field BY NAME, so these are
    // part of the migration contract and are never spelled any other way. They are published as
    // constants so that no consumer, test or differ has to retype a hyphenated COBOL name.
    // =================================================================================================

    /** {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} - the whole group, {@code app/cpy/COMEN02Y.cpy:19}. */
    public static final String CARDDEMO_MAIN_MENU_OPTIONS = "CARDDEMO-MAIN-MENU-OPTIONS";

    /**
     * {@code 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} - {@code app/cpy/COMEN02Y.cpy:21}. The
     * active option count, which {@code app/cbl/COMEN01C.cbl:128} validates a typed option against.
     * It is emphatically not the table size; see {@link #TABLE_SIZE}.
     */
    public static final String CDEMO_MENU_OPT_COUNT = "CDEMO-MENU-OPT-COUNT";

    /**
     * {@code 05 CDEMO-MENU-OPTIONS-DATA} - {@code app/cpy/COMEN02Y.cpy:23}. The literal-storage
     * group, {@value #POPULATED_DATA_LENGTH} bytes, holding only the populated entries.
     */
    public static final String CDEMO_MENU_OPTIONS_DATA = "CDEMO-MENU-OPTIONS-DATA";

    /**
     * {@code 05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA} -
     * {@code app/cpy/COMEN02Y.cpy:87}. The {@value #TABLE_LENGTH}-byte {@code OCCURS} projection over
     * the same backing bytes.
     */
    public static final String CDEMO_MENU_OPTIONS = "CDEMO-MENU-OPTIONS";

    /**
     * {@code 10 CDEMO-MENU-OPT OCCURS 12 TIMES} - {@code app/cpy/COMEN02Y.cpy:88}. One
     * {@value #ENTRY_LENGTH}-byte element of the table.
     */
    public static final String CDEMO_MENU_OPT = "CDEMO-MENU-OPT";

    /** {@code 15 CDEMO-MENU-OPT-NUM PIC 9(02)} - {@code app/cpy/COMEN02Y.cpy:89}. */
    public static final String CDEMO_MENU_OPT_NUM = "CDEMO-MENU-OPT-NUM";

    /** {@code 15 CDEMO-MENU-OPT-NAME PIC X(35)} - {@code app/cpy/COMEN02Y.cpy:90}. */
    public static final String CDEMO_MENU_OPT_NAME = "CDEMO-MENU-OPT-NAME";

    /** {@code 15 CDEMO-MENU-OPT-PGMNAME PIC X(08)} - {@code app/cpy/COMEN02Y.cpy:91}. */
    public static final String CDEMO_MENU_OPT_PGMNAME = "CDEMO-MENU-OPT-PGMNAME";

    /** {@code 15 CDEMO-MENU-OPT-USRTYPE PIC X(01)} - {@code app/cpy/COMEN02Y.cpy:92}. */
    public static final String CDEMO_MENU_OPT_USRTYPE = "CDEMO-MENU-OPT-USRTYPE";

    /**
     * The reserved COBOL name for an unnamed span. Every one of the forty-eight sub-spans of the
     * literal-storage group is declared {@code FILLER} in the copybook, which is legal precisely
     * because {@code FILLER} can never be referenced; the {@code OCCURS} overlay is what gives those
     * same bytes referable names.
     */
    private static final String FILLER = "FILLER";

    // =================================================================================================
    // Geometry. Every width, offset and count the copybook declares is a NAMED constant, so no bare
    // numeric literal ever appears at a use site and every number below can be traced to one source
    // line. All values were taken by direct inspection of app/cpy/COMEN02Y.cpy, not recomputed from
    // prose, and the derived totals are re-proved mechanically by GROUP_LAYOUT at class initialisation.
    // =================================================================================================

    /**
     * {@code CDEMO-MENU-OPT-NUM PIC 9(02)} - {@value #OPT_NUM_LENGTH} bytes.
     * {@code app/cpy/COMEN02Y.cpy:89}.
     */
    public static final int OPT_NUM_LENGTH = 2;

    /**
     * {@code CDEMO-MENU-OPT-NAME PIC X(35)} - {@value #OPT_NAME_LENGTH} bytes.
     * {@code app/cpy/COMEN02Y.cpy:90}. Every one of the ten declared literals is already exactly this
     * long in the copybook, so the declared width applies no COBOL padding of its own; the trailing
     * spaces are part of the value.
     */
    public static final int OPT_NAME_LENGTH = 35;

    /**
     * {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} - {@value #OPT_PGMNAME_LENGTH} bytes.
     * {@code app/cpy/COMEN02Y.cpy:91}. This is the width the {@code 'DUMMY'} test at
     * {@code app/cbl/COMEN01C.cbl:146} slices its leading five bytes out of.
     */
    public static final int OPT_PGMNAME_LENGTH = 8;

    /**
     * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} - {@value #OPT_USRTYPE_LENGTH} byte.
     * {@code app/cpy/COMEN02Y.cpy:92}. This column is the reason an entry here is
     * {@value #ENTRY_LENGTH} bytes rather than the forty-five of the sibling admin-menu table, whose
     * copybook has no user-type column at all.
     */
    public static final int OPT_USRTYPE_LENGTH = 1;

    /**
     * One {@code CDEMO-MENU-OPT} element: {@value #ENTRY_LENGTH} bytes, being
     * {@value #OPT_NUM_LENGTH} + {@value #OPT_NAME_LENGTH} + {@value #OPT_PGMNAME_LENGTH} +
     * {@value #OPT_USRTYPE_LENGTH}.
     *
     * <p>Named explicitly rather than left to be recomputed at each use site. Note the value:
     * {@value #ENTRY_LENGTH}, one byte wider than the forty-five of the sibling admin-menu option
     * table. Copying that sibling's width here would shift every field of every entry after the
     * first, so the two are never assumed interchangeable.
     */
    public static final int ENTRY_LENGTH =
            OPT_NUM_LENGTH + OPT_NAME_LENGTH + OPT_PGMNAME_LENGTH + OPT_USRTYPE_LENGTH;

    /**
     * The number of slots the table declares: {@value #TABLE_SIZE}, from
     * {@code OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88}.
     *
     * <p><strong>Not</strong> {@link #ACTIVE_OPTION_COUNT}. This is how many slots may be
     * subscripted; that is how many carry a declared value. See the class documentation for the three
     * independent confirmations of the twelve.
     */
    public static final int TABLE_SIZE = 12;

    /**
     * The active option count: {@value #ACTIVE_OPTION_COUNT}, the value of
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}.
     *
     * <p><strong>Not</strong> {@link #TABLE_SIZE}. This is the bound the consuming program uses in
     * two places - the build loop at {@code app/cbl/COMEN01C.cbl:238-239} and the typed-option
     * validation at {@code app/cbl/COMEN01C.cbl:128} - and it is smaller than the table it indexes
     * into.
     */
    public static final int ACTIVE_OPTION_COUNT = 10;

    /**
     * The first subscript the copybook declares no {@code VALUE} for:
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE}.
     *
     * <p>Named so that the boundary between the storage this copybook determines and the storage it
     * does not is stated once, as a constant, rather than written out as "11" wherever it is needed.
     */
    public static final int SPECIFIED_OPTION_COUNT_PLUS_ONE = ACTIVE_OPTION_COUNT + 1;

    /**
     * The lowest legal COBOL subscript: {@value #FIRST_SUBSCRIPT}. Published so that a caller
     * iterating the table never writes a bare {@code 1} and never accidentally starts at Java's 0.
     */
    public static final int FIRST_SUBSCRIPT = 1;

    /**
     * {@code CDEMO-MENU-OPT-COUNT} occupies bytes {@value #MENU_OPT_COUNT_OFFSET} to
     * {@value #OPT_NUM_LENGTH} of the group, exclusive - {@value #MENU_OPT_COUNT_LENGTH} bytes.
     */
    public static final int MENU_OPT_COUNT_LENGTH = OPT_NUM_LENGTH;

    /** The absolute zero-based offset of {@code CDEMO-MENU-OPT-COUNT} within the group. */
    public static final int MENU_OPT_COUNT_OFFSET = 0;

    /**
     * The absolute zero-based offset at which both {@code CDEMO-MENU-OPTIONS-DATA} and its
     * {@code CDEMO-MENU-OPTIONS} overlay begin: {@value #TABLE_OFFSET}, immediately after
     * {@code CDEMO-MENU-OPT-COUNT}. That the two views share this offset is what makes them two
     * accessors over one backing span.
     */
    public static final int TABLE_OFFSET = MENU_OPT_COUNT_OFFSET + MENU_OPT_COUNT_LENGTH;

    /**
     * {@code CDEMO-MENU-OPTIONS-DATA} - {@value #POPULATED_DATA_LENGTH} bytes, being
     * {@value #ACTIVE_OPTION_COUNT} populated entries of {@value #ENTRY_LENGTH} bytes. This is the
     * storage the copybook actually spells out with {@code VALUE} clauses, on lines 25 to 84.
     */
    public static final int POPULATED_DATA_LENGTH = ACTIVE_OPTION_COUNT * ENTRY_LENGTH;

    /**
     * {@code CDEMO-MENU-OPTIONS} - {@value #TABLE_LENGTH} bytes, being {@value #TABLE_SIZE} slots of
     * {@value #ENTRY_LENGTH} bytes. Ninety-two bytes wider than the
     * {@value #POPULATED_DATA_LENGTH} it redefines, which is the documented asymmetry described in
     * the class documentation and the reason slots 11 and 12 exist.
     */
    public static final int TABLE_LENGTH = TABLE_SIZE * ENTRY_LENGTH;

    /**
     * The whole {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} group: {@value #GROUP_LENGTH} bytes, being
     * {@value #MENU_OPT_COUNT_LENGTH} + {@value #TABLE_LENGTH}. This is the width of every image
     * {@link #encode(Charset)} produces and every image {@link #decode(byte[], Charset)} accepts.
     */
    public static final int GROUP_LENGTH = MENU_OPT_COUNT_LENGTH + TABLE_LENGTH;

    /**
     * The suffix that names the unspecified tail descriptor: {@value #UNSPECIFIED_TAIL_SUFFIX}.
     *
     * <p>A suffix rather than a copybook name, because the copybook has no name for this region - it is
     * the part of the {@code OCCURS} overlay that reaches past the group it redefines. Naming it after
     * the overlay makes the relationship legible without implying the copybook declared it.
     */
    public static final String UNSPECIFIED_TAIL_SUFFIX = "-UNSPECIFIED-TAIL";

    /** {@code CDEMO-MENU-OPT-NUM} - offset {@value #OPT_NUM_OFFSET} within an entry. */
    public static final int OPT_NUM_OFFSET = 0;

    /** {@code CDEMO-MENU-OPT-NAME} - offset {@value #OPT_NAME_OFFSET} within an entry. */
    public static final int OPT_NAME_OFFSET = OPT_NUM_OFFSET + OPT_NUM_LENGTH;

    /** {@code CDEMO-MENU-OPT-PGMNAME} - offset {@value #OPT_PGMNAME_OFFSET} within an entry. */
    public static final int OPT_PGMNAME_OFFSET = OPT_NAME_OFFSET + OPT_NAME_LENGTH;

    /** {@code CDEMO-MENU-OPT-USRTYPE} - offset {@value #OPT_USRTYPE_OFFSET} within an entry. */
    public static final int OPT_USRTYPE_OFFSET = OPT_PGMNAME_OFFSET + OPT_PGMNAME_LENGTH;

    /**
     * The lowest value {@code CDEMO-MENU-OPT-NUM PIC 9(02)} can hold: {@value #MIN_OPT_NUM}.
     *
     * <p>It is a range bound and nothing more. It is deliberately <strong>not</strong> "the number an
     * unvalued slot reads back as": the copybook declares no {@code VALUE} for slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} and {@value #TABLE_SIZE}, and those slots lie beyond
     * the {@value #POPULATED_DATA_LENGTH} bytes of {@code CDEMO-MENU-OPTIONS-DATA} that the overlay
     * redefines, so what they contain at run time is not something this copybook, or this class, can
     * state. Asserting {@code "00"} there would be inventing a fact. See
     * {@link #isSpecified(int)}.
     */
    public static final int MIN_OPT_NUM = 0;

    /**
     * The highest value {@code CDEMO-MENU-OPT-NUM PIC 9(02)} can hold: {@value #MAX_OPT_NUM}. Two
     * digits of unsigned zoned display, so the range is {@value #MIN_OPT_NUM} to
     * {@value #MAX_OPT_NUM} inclusive. The same {@code PIC 9(02)} range applies to the program's own
     * {@code WS-OPTION} at {@code app/cbl/COMEN01C.cbl:46}, which is why a subscript far outside the
     * table is reachable at all.
     */
    public static final int MAX_OPT_NUM = 99;

    /**
     * The position of {@code CDEMO-MENU-OPT-NUM} within {@link #entryFieldSpans(int)}, which returns
     * the four sub-fields in copybook declaration order.
     */
    public static final int NUM_SUBFIELD = 0;

    /** The position of {@code CDEMO-MENU-OPT-NAME} within {@link #entryFieldSpans(int)}. */
    public static final int NAME_SUBFIELD = 1;

    /** The position of {@code CDEMO-MENU-OPT-PGMNAME} within {@link #entryFieldSpans(int)}. */
    public static final int PGMNAME_SUBFIELD = 2;

    /** The position of {@code CDEMO-MENU-OPT-USRTYPE} within {@link #entryFieldSpans(int)}. */
    public static final int USRTYPE_SUBFIELD = 3;

    /**
     * The number of sub-fields one {@code CDEMO-MENU-OPT} element declares:
     * {@value #SUBFIELDS_PER_ENTRY}, from {@code app/cpy/COMEN02Y.cpy:89-92}.
     */
    public static final int SUBFIELDS_PER_ENTRY = 4;

    // =================================================================================================
    // One entry of the OCCURS table.
    // =================================================================================================

    /**
     * One element of {@code CDEMO-MENU-OPT OCCURS 12 TIMES} - the four sub-fields of
     * {@code app/cpy/COMEN02Y.cpy:89-92} as one immutable value.
     *
     * <p>The component names are the copybook's own, camel-cased and nothing more:
     * {@code CDEMO-MENU-OPT-NUM} to {@link #menuOptNum()}, {@code CDEMO-MENU-OPT-NAME} to
     * {@link #menuOptName()}, {@code CDEMO-MENU-OPT-PGMNAME} to {@link #menuOptPgmName()} and
     * {@code CDEMO-MENU-OPT-USRTYPE} to {@link #menuOptUsrType()}. No field is renamed, reordered or
     * dropped, because the parity differ compares field by field by name.
     *
     * <p>{@code CDEMO-MENU-OPT-NUM} is carried twice, and both forms are required. The copybook
     * declares it {@code PIC 9(02)}, which is <em>scaleless</em>, so the numeric form is an
     * {@code int} - never a floating-point type, and no fixed-point decimal type is involved because
     * this copybook declares no decimal picture at all. The image form is the two-byte zero-filled
     * zoned representation the legacy program actually moves; see the class documentation.
     *
     * <p>Every component is validated at construction, so an entry either exists and is provably the
     * right shape or does not exist at all. Widths are exact rather than maximal: a value shorter than
     * its declared width is rejected instead of being quietly padded, because these are transcribed
     * copybook literals and a short one is a transcription error, not data. The strings are handed
     * back exactly as stored - untrimmed, fully padded.
     *
     * @param menuOptNum      {@code CDEMO-MENU-OPT-NUM} as a number, {@value #MIN_OPT_NUM} to
     *                        {@value #MAX_OPT_NUM} - the whole range {@code PIC 9(02)} admits
     * @param menuOptNumImage {@code CDEMO-MENU-OPT-NUM} as its raw {@value #OPT_NUM_LENGTH}-byte
     *                        zero-filled zoned image, which must decode to {@code menuOptNum}
     * @param menuOptName     {@code CDEMO-MENU-OPT-NAME}, exactly {@value #OPT_NAME_LENGTH}
     *                        characters, right-space-padded and never trimmed
     * @param menuOptPgmName  {@code CDEMO-MENU-OPT-PGMNAME}, exactly {@value #OPT_PGMNAME_LENGTH}
     *                        characters, right-space-padded and never trimmed
     * @param menuOptUsrType  {@code CDEMO-MENU-OPT-USRTYPE}, exactly {@value #OPT_USRTYPE_LENGTH}
     *                        character. All ten entries the copybook values carry {@code "U"}.
     *                        Interpreting this column is the consuming service's decision, not this
     *                        type's
     */
    public record MenuOption(int menuOptNum,
                             String menuOptNumImage,
                             String menuOptName,
                             String menuOptPgmName,
                             String menuOptUsrType) {

        /**
         * Validates every component against its declared {@code PICTURE}, including that the numeric
         * form and the image form agree.
         *
         * @throws NullPointerException     if any string component is {@code null}
         * @throws IllegalArgumentException if {@code menuOptNum} is outside
         *                                  {@value #MIN_OPT_NUM} to {@value #MAX_OPT_NUM}, if
         *                                  any string component is not exactly its declared width, if
         *                                  {@code menuOptNumImage} is not all digits, or if
         *                                  {@code menuOptNumImage} does not decode to
         *                                  {@code menuOptNum}
         */
        public MenuOption {
            if (menuOptNum < MIN_OPT_NUM || menuOptNum > MAX_OPT_NUM) {
                throw new IllegalArgumentException("CDEMO-MENU-OPT-NUM is " + menuOptNum
                        + "; the copybook declares it PIC 9(02), so it holds " + MIN_OPT_NUM
                        + " to " + MAX_OPT_NUM + " inclusive");
            }
            requireExactWidth(menuOptNumImage, OPT_NUM_LENGTH, CDEMO_MENU_OPT_NUM);
            requireDigits(menuOptNumImage);
            int decoded = Integer.parseInt(menuOptNumImage);
            if (decoded != menuOptNum) {
                throw new IllegalArgumentException("CDEMO-MENU-OPT-NUM image '" + menuOptNumImage
                        + "' decodes to " + decoded + " but the numeric form is " + menuOptNum
                        + "; the two views of one PIC 9(02) field must agree exactly");
            }
            requireExactWidth(menuOptName, OPT_NAME_LENGTH, CDEMO_MENU_OPT_NAME);
            requireExactWidth(menuOptPgmName, OPT_PGMNAME_LENGTH, CDEMO_MENU_OPT_PGMNAME);
            requireExactWidth(menuOptUsrType, OPT_USRTYPE_LENGTH, CDEMO_MENU_OPT_USRTYPE);
        }

        /**
         * Builds an entry from the copybook's declared values, deriving the zoned image and applying
         * each field's declared width.
         *
         * <p>The {@code PIC X} widths are applied by right-space-padding, which is how COBOL stores a
         * {@code VALUE} literal shorter than its picture, and the {@code PIC 9(02)} image by
         * left-zero-filling. Passing text longer than its declared width is rejected rather than
         * truncated, because every caller of this factory is transcribing a copybook literal.
         *
         * @param menuOptNum     {@code CDEMO-MENU-OPT-NUM}, {@value #MIN_OPT_NUM} to
         *                       {@value #MAX_OPT_NUM}
         * @param menuOptName    {@code CDEMO-MENU-OPT-NAME}, at most {@value #OPT_NAME_LENGTH}
         *                       characters; padded to exactly that width
         * @param menuOptPgmName {@code CDEMO-MENU-OPT-PGMNAME}, at most
         *                       {@value #OPT_PGMNAME_LENGTH} characters; padded to exactly that width
         * @param menuOptUsrType {@code CDEMO-MENU-OPT-USRTYPE}, at most
         *                       {@value #OPT_USRTYPE_LENGTH} character; padded to exactly that width
         * @return the validated, fully padded entry
         * @throws NullPointerException     if any string argument is {@code null}
         * @throws IllegalArgumentException if {@code menuOptNum} is out of range or any string
         *                                  argument is wider than its declared width
         */
        public static MenuOption of(int menuOptNum,
                                    String menuOptName,
                                    String menuOptPgmName,
                                    String menuOptUsrType) {
            return new MenuOption(menuOptNum,
                    pic9Image(menuOptNum, OPT_NUM_LENGTH),
                    picXImage(menuOptName, OPT_NAME_LENGTH, CDEMO_MENU_OPT_NAME),
                    picXImage(menuOptPgmName, OPT_PGMNAME_LENGTH, CDEMO_MENU_OPT_PGMNAME),
                    picXImage(menuOptUsrType, OPT_USRTYPE_LENGTH, CDEMO_MENU_OPT_USRTYPE));
        }

        private static void requireExactWidth(String value, int declaredWidth, String cobolName) {
            Objects.requireNonNull(value, cobolName + " is required; the copybook declares it as "
                    + declaredWidth + " byte(s) of storage, so its absence has no COBOL counterpart");
            if (value.length() != declaredWidth) {
                throw new IllegalArgumentException(cobolName + " is '" + value + "', which is "
                        + value.length() + " character(s); the copybook declares exactly "
                        + declaredWidth + ", and a fixed-width field is stored padded to its full "
                        + "declared width rather than trimmed");
            }
        }

        private static void requireDigits(String image) {
            for (int position = 0; position < image.length(); position++) {
                char digit = image.charAt(position);
                if (digit < '0' || digit > '9') {
                    throw new IllegalArgumentException("CDEMO-MENU-OPT-NUM image '" + image
                            + "' holds '" + digit + "' at position " + position
                            + "; PIC 9(02) is unsigned zoned DISPLAY and holds digits only");
                }
            }
        }
    }

    // =================================================================================================
    // The table itself: the single source of truth for this copybook's values.
    //
    // Transcribed one Java line per copybook entry, in copybook order, each citing the source lines it
    // came from. Only the significant text is written out; the trailing padding to each declared width
    // is applied by MenuOption.of through the named width constants, so no run of hand-typed spaces
    // can silently be one character short. Every resulting value is nonetheless byte-identical to the
    // copybook literal: MenuOption's constructor rejects any component that is not EXACTLY its
    // declared width, so a name that is not precisely OPT_NAME_LENGTH characters prevents this class
    // from initialising at all. GROUP_LAYOUT then proves the count: were a slot missing from the list
    // below, the layout would declare fewer than GROUP_LENGTH bytes of storage and refuse to exist.
    // =================================================================================================

    /**
     * All {@value #TABLE_SIZE} slots of {@code CDEMO-MENU-OPT OCCURS 12 TIMES}, in subscript order,
     * with slots {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} and {@value #TABLE_SIZE} <strong>absent
     * rather than empty</strong>.
     *
     * <p>An {@link Optional} rather than a zero-and-blanks entry, because those two slots have no
     * content this copybook determines and an entry claiming option number 0 with blank text would be
     * a fact invented here. Absence is the accurate answer, and it is the one shape a caller cannot
     * read a fabricated value out of by accident.
     */
    private static final List<Optional<MenuOption>> OPTIONS = List.of(
            //             num  CDEMO-MENU-OPT-NAME     PGMNAME       USRTYPE   copybook lines
            specified(1, "Account View", "COACTVWC", "U"),        // L25-L29
            specified(2, "Account Update", "COACTUPC", "U"),      // L31-L35
            specified(3, "Credit Card List", "COCRDLIC", "U"),    // L37-L41
            specified(4, "Credit Card View", "COCRDSLC", "U"),    // L43-L47
            specified(5, "Credit Card Update", "COCRDUPC", "U"),  // L49-L53
            specified(6, "Transaction List", "COTRN00C", "U"),    // L55-L59
            // Options 7 and 8 pair 'Transaction View' with COTRN01C and 'Transaction Add' with
            // COTRN02C. That is what the copybook says, and it is transcribed exactly as it stands.
            // The pairing is the source of the documented view/add naming inversion elsewhere in this
            // migration and must not be "corrected" here.
            specified(7, "Transaction View", "COTRN01C", "U"),    // L61-L65
            // Option 8's name comes from the LIVE line 70 only. Line 69 immediately above it -
            // *        'Transaction Add (Admin Only)       '.
            // is commented out (asterisk in column 7) and is an abandoned earlier wording. It is
            // recorded here so nobody restores it, and nothing is inferred from it: the live user-type
            // column on line 72 is 'U', not 'A'. See the class documentation.
            specified(8, "Transaction Add", "COTRN02C", "U"),     // L67-L68, L70-L72
            specified(9, "Transaction Reports", "CORPT00C", "U"), // L74-L78
            specified(10, "Bill Payment", "COBIL00C", "U"),       // L80-L84
            // Slots 11 and 12 carry no copybook VALUE. They exist because the CDEMO-MENU-OPTIONS
            // overlay on line 87 is 552 bytes over the 460 bytes of CDEMO-MENU-OPTIONS-DATA that it
            // redefines - so they are storage NOTHING WAS EVER MOVED INTO, lying past the end of the
            // group being redefined, and what a compiler and a run-time actually leave there is not
            // determined by this copybook. They are kept as slots and never trimmed, because the table
            // really is twelve elements wide and a subscript of 11 or 12 really is reachable; but they
            // are absent rather than valued, because "zero-filled PIC 9(02) and space-filled PIC X"
            // was a fact this file used to state and the copybook never does.
            unspecified(),
            unspecified());

    /**
     * The {@code CDEMO-MENU-OPTIONS-DATA} view: the {@value #ACTIVE_OPTION_COUNT} entries the
     * copybook spells out with {@code VALUE} clauses, being the leading
     * {@value #POPULATED_DATA_LENGTH} bytes of the same backing storage {@link #OPTIONS} projects in
     * full.
     */
    private static final List<MenuOption> ACTIVE_OPTIONS = OPTIONS.subList(0, ACTIVE_OPTION_COUNT)
            .stream()
            .map(slot -> slot.orElseThrow(() -> new IllegalStateException(
                    "Every one of the first " + ACTIVE_OPTION_COUNT + " slots of "
                            + CDEMO_MENU_OPTIONS + " carries a copybook VALUE, so none may be absent; "
                            + "this class is mis-transcribed if one is")))
            .toList();

    // =================================================================================================
    // Descriptors. Declared once, immutable, and re-proved at class initialisation by RecordLayout's
    // own geometry self-check.
    // =================================================================================================

    /**
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} as a positioned descriptor - real storage, not
     * an overlay, carrying its declared literal.
     */
    private static final FieldSpan MENU_OPT_COUNT_SPAN =
            FieldSpan.unsignedNumeric(CDEMO_MENU_OPT_COUNT, MENU_OPT_COUNT_OFFSET,
                            MENU_OPT_COUNT_LENGTH)
                    .withInitialValue(Integer.toString(ACTIVE_OPTION_COUNT));

    /**
     * {@code CDEMO-MENU-OPTIONS-DATA} as a descriptor: the {@value #POPULATED_DATA_LENGTH}-byte
     * literal-storage view, at offset {@value #TABLE_OFFSET}. Declared as an overlay because the
     * flattened layout accounts for the same bytes through the forty-eight {@code FILLER} sub-spans;
     * this descriptor exists so the group has a name a caller can address it by.
     */
    private static final FieldSpan POPULATED_DATA_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS_DATA, TABLE_OFFSET, POPULATED_DATA_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * {@code CDEMO-MENU-OPTIONS} as a descriptor: the {@value #TABLE_LENGTH}-byte {@code OCCURS}
     * projection, at the same offset {@value #TABLE_OFFSET} as {@link #POPULATED_DATA_SPAN} and over
     * the same backing bytes. Wider than the storage it redefines, which is the documented asymmetry.
     */
    private static final FieldSpan TABLE_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS, TABLE_OFFSET, TABLE_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * The unspecified tail as a descriptor: the bytes of slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}. Declared as a
     * {@code FILLER}-kind overlay with no initial value, which is the honest shape for storage the
     * copybook neither values nor accounts for. Exposed by {@link #unspecifiedTailSpan()}.
     */
    private static final FieldSpan UNSPECIFIED_TAIL_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS + UNSPECIFIED_TAIL_SUFFIX, TABLE_OFFSET + POPULATED_DATA_LENGTH,
            TABLE_LENGTH - POPULATED_DATA_LENGTH, PictureKind.FILLER);

    /**
     * The complete, self-checking layout of {@code 01 CARDDEMO-MAIN-MENU-OPTIONS}:
     * {@value #GROUP_LENGTH} bytes, {@code CDEMO-MENU-OPT-COUNT} followed by the forty-eight
     * {@code FILLER} sub-spans of the {@value #TABLE_SIZE} slots, then the two {@code REDEFINES}
     * overlays.
     *
     * <p>Constructing it <em>is</em> the geometry proof, and it runs the moment this class is
     * initialised. The layout refuses to exist unless its storage spans are contiguous from byte 0
     * with no gap and no overlap, unless both overlays fall entirely inside storage declared ahead of
     * them, and unless the storage sums to exactly {@value #GROUP_LENGTH}. Dropping a single pad byte,
     * mis-stating an offset, or trimming the table to {@value #ACTIVE_OPTION_COUNT} slots all fail
     * here immediately and precisely rather than shifting bytes silently.
     *
     * <p>Each populated sub-span carries its copybook {@code VALUE}; each of the eight sub-spans of
     * slots 11 and 12 carries none and therefore initialises to its kind's pad byte - zeros for the
     * {@code PIC 9(02)} column, spaces for the {@code PIC X} columns. The numeric {@code FILLER}
     * spans are declared with a numeric kind so that {@code VALUE 1} is stored right-justified as
     * {@code "01"} rather than left-justified as {@code "1 "}.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    /**
     * Every elementary field of the table view, named the way COBOL references it, in copybook order:
     * {@code CDEMO-MENU-OPT-COUNT} followed by four descriptors per slot, keyed
     * {@code CDEMO-MENU-OPT-NAME(3)} and so on. This is the descriptor table the parity differ walks.
     */
    private static final List<FieldSpan> FIELD_SPANS = buildFieldSpans();

    // =================================================================================================
    // Accessors. Two access styles, named so they cannot be confused: options() is the ZERO-based Java
    // list, optionBySubscript(int) takes the ONE-based COBOL subscript.
    // =================================================================================================

    /**
     * The {@code CDEMO-MENU-OPTIONS} table view: all {@value #TABLE_SIZE} slots in subscript order,
     * <strong>zero-based</strong> as a Java list, with slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} and {@value #TABLE_SIZE} <strong>empty</strong>.
     *
     * <p>Empty means exactly that: the copybook gives those two slots no {@code VALUE} and they sit
     * past the end of the group the {@code OCCURS} overlay redefines, so there is no entry to report
     * and none is invented. A caller that walks all twelve slots must decide what to do about an absent
     * one, which is the point - the previous shape handed back option number 0 with blank text and let
     * that be mistaken for data.
     *
     * <p>The list is unmodifiable and its elements are immutable, so nothing a caller does can alter
     * the table. To address a slot by its COBOL subscript, use {@link #optionBySubscript(int)} rather
     * than subtracting one here; for the {@value #ACTIVE_OPTION_COUNT} slots that do carry values, use
     * {@link #activeOptions()} and avoid the question altogether.
     *
     * @return an unmodifiable list of exactly {@value #TABLE_SIZE} slots, of which the first
     *         {@value #ACTIVE_OPTION_COUNT} are present
     */
    public static List<Optional<MenuOption>> options() {
        return OPTIONS;
    }

    /**
     * The {@code CDEMO-MENU-OPTIONS-DATA} literal-storage view: the {@value #ACTIVE_OPTION_COUNT}
     * entries the copybook declares a {@code VALUE} for, <strong>zero-based</strong> as a Java list.
     *
     * <p>These are the leading {@value #POPULATED_DATA_LENGTH} bytes of the same backing storage
     * {@link #options()} projects in full, which is what makes the two lists the typed form of the
     * {@code REDEFINES} pair. This is the view a build loop bounded by {@link #ACTIVE_OPTION_COUNT}
     * walks; it is not a filter, and it applies no user-type or availability rule.
     *
     * @return an unmodifiable list of exactly {@value #ACTIVE_OPTION_COUNT} entries
     */
    public static List<MenuOption> activeOptions() {
        return ACTIVE_OPTIONS;
    }

    /**
     * Addresses one slot by its <strong>one-based COBOL subscript</strong>, exactly as
     * {@code CDEMO-MENU-OPT(WS-IDX)} does.
     *
     * <p>An out-of-range subscript is <strong>rejected, never clamped</strong>. That is deliberate and
     * load-bearing: the user-type filter at {@code app/cbl/COMEN01C.cbl:136-143} is not guarded by the
     * program's error flag, and {@code WS-OPTION PIC 9(02)} holds {@value #MIN_OPT_NUM} to
     * {@value #MAX_OPT_NUM}, so the legacy program can reach this access with a subscript far outside
     * the table after a failed numeric validation. Bounding the access is the consuming service's
     * decision; silently substituting slot 1 or slot {@value #TABLE_SIZE} here would hide the very
     * condition the service has to handle.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to
     *                       {@value #TABLE_SIZE} inclusive
     * @return the addressed entry, or empty for subscript
     *         {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} or {@value #TABLE_SIZE}, which the copybook
     *         gives no {@code VALUE}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below {@value #FIRST_SUBSCRIPT}
     *                                   or above {@value #TABLE_SIZE}
     */
    public static Optional<MenuOption> optionBySubscript(int cobolSubscript) {
        requireSubscript(cobolSubscript);
        return OPTIONS.get(cobolSubscript - FIRST_SUBSCRIPT);
    }

    /**
     * Whether the copybook determines the content of a slot.
     *
     * <p>True for subscripts {@value #FIRST_SUBSCRIPT} to {@value #ACTIVE_OPTION_COUNT}, which carry
     * {@code VALUE} clauses; false for {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} and
     * {@value #TABLE_SIZE}, which do not. Offered as a question a caller can ask <em>before</em>
     * addressing a slot, so that a subscript arriving from
     * {@code app/cbl/COMEN01C.cbl}'s {@code WS-OPTION} can be classified without an
     * {@link Optional} round trip.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to
     *                       {@value #TABLE_SIZE} inclusive
     * @return {@code true} when the copybook declares a {@code VALUE} for that slot
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below {@value #FIRST_SUBSCRIPT}
     *                                   or above {@value #TABLE_SIZE}
     */
    public static boolean isSpecified(int cobolSubscript) {
        requireSubscript(cobolSubscript);
        return cobolSubscript <= ACTIVE_OPTION_COUNT;
    }

    /**
     * The value of {@code CDEMO-MENU-OPT-COUNT} as a number: {@value #ACTIVE_OPTION_COUNT}.
     *
     * <p>Identical to {@link #ACTIVE_OPTION_COUNT} and offered as a method so that a caller reading
     * the field out of a decoded image and a caller reading it from the constant table use the same
     * name. It is the active count, not {@link #TABLE_SIZE}.
     *
     * @return {@value #ACTIVE_OPTION_COUNT}
     */
    public static int menuOptCount() {
        return ACTIVE_OPTION_COUNT;
    }

    /**
     * The value of {@code CDEMO-MENU-OPT-COUNT} as its raw {@value #MENU_OPT_COUNT_LENGTH}-byte
     * zero-filled zoned image - {@code "10"}.
     *
     * @return the two-character display image of the active option count
     */
    public static String menuOptCountImage() {
        return pic9Image(ACTIVE_OPTION_COUNT, MENU_OPT_COUNT_LENGTH);
    }

    /**
     * The descriptor of {@code CDEMO-MENU-OPT-COUNT}: real storage at offset
     * {@value #MENU_OPT_COUNT_OFFSET}, {@value #MENU_OPT_COUNT_LENGTH} bytes, carrying its declared
     * {@code VALUE}.
     *
     * @return the immutable descriptor
     */
    public static FieldSpan menuOptCountSpan() {
        return MENU_OPT_COUNT_SPAN;
    }

    /**
     * The first of the two {@code REDEFINES} accessors: the {@code CDEMO-MENU-OPTIONS-DATA}
     * literal-storage view, {@value #POPULATED_DATA_LENGTH} bytes at offset {@value #TABLE_OFFSET}.
     *
     * @return the immutable descriptor of the redefined storage
     */
    public static FieldSpan populatedDataSpan() {
        return POPULATED_DATA_SPAN;
    }

    /**
     * The second of the two {@code REDEFINES} accessors: the {@code CDEMO-MENU-OPTIONS} table view,
     * {@value #TABLE_LENGTH} bytes at the same offset {@value #TABLE_OFFSET} as
     * {@link #populatedDataSpan()} and over the same backing bytes.
     *
     * <p>The two descriptors share an offset and differ in width, which is precisely the copybook's
     * documented asymmetry: the redefining item is ninety-two bytes wider than the item it redefines.
     *
     * @return the immutable descriptor of the redefining overlay
     */
    public static FieldSpan tableSpan() {
        return TABLE_SPAN;
    }

    /**
     * The descriptor of one whole {@value #ENTRY_LENGTH}-byte {@code CDEMO-MENU-OPT} element,
     * addressed by its one-based COBOL subscript.
     *
     * <p>The one-based to zero-based conversion is delegated to
     * {@link FixedWidthRecord#occursElementSpan}, so it is never written inline here and cannot drift.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to
     *                       {@value #TABLE_SIZE} inclusive
     * @return the immutable descriptor of that element
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside
     *                                   {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     */
    public static FieldSpan entrySpan(int cobolSubscript) {
        return FixedWidthRecord.occursElementSpan(TABLE_SPAN, TABLE_SIZE, cobolSubscript,
                CDEMO_MENU_OPT, PictureKind.ALPHANUMERIC);
    }

    /**
     * The {@value #SUBFIELDS_PER_ENTRY} sub-field descriptors of one element, in copybook declaration
     * order: {@code CDEMO-MENU-OPT-NUM}, {@code -NAME}, {@code -PGMNAME}, {@code -USRTYPE}, each named
     * with its subscript.
     *
     * <p>The positions are published as {@link #NUM_SUBFIELD}, {@link #NAME_SUBFIELD},
     * {@link #PGMNAME_SUBFIELD} and {@link #USRTYPE_SUBFIELD}, so no caller indexes this list with a
     * bare number.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to
     *                       {@value #TABLE_SIZE} inclusive
     * @return an unmodifiable list of exactly {@value #SUBFIELDS_PER_ENTRY} descriptors
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside
     *                                   {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     */
    public static List<FieldSpan> entryFieldSpans(int cobolSubscript) {
        FieldSpan element = entrySpan(cobolSubscript);
        int base = element.offset();
        return List.of(
                FieldSpan.redefining(subscriptedName(CDEMO_MENU_OPT_NUM, cobolSubscript),
                        base + OPT_NUM_OFFSET, OPT_NUM_LENGTH, PictureKind.UNSIGNED_NUMERIC),
                FieldSpan.redefining(subscriptedName(CDEMO_MENU_OPT_NAME, cobolSubscript),
                        base + OPT_NAME_OFFSET, OPT_NAME_LENGTH, PictureKind.ALPHANUMERIC),
                FieldSpan.redefining(subscriptedName(CDEMO_MENU_OPT_PGMNAME, cobolSubscript),
                        base + OPT_PGMNAME_OFFSET, OPT_PGMNAME_LENGTH, PictureKind.ALPHANUMERIC),
                FieldSpan.redefining(subscriptedName(CDEMO_MENU_OPT_USRTYPE, cobolSubscript),
                        base + OPT_USRTYPE_OFFSET, OPT_USRTYPE_LENGTH, PictureKind.ALPHANUMERIC));
    }

    /**
     * Every elementary field of the group as a descriptor, in copybook order:
     * {@code CDEMO-MENU-OPT-COUNT} followed by four per slot, one to {@value #TABLE_SIZE} - forty-nine
     * in total. This is the descriptor table the parity differ walks field by field.
     *
     * @return an unmodifiable list of forty-nine descriptors
     */
    public static List<FieldSpan> fieldSpans() {
        return FIELD_SPANS;
    }

    /**
     * The COBOL reference form of a subscripted field name, as the parity differ keys it -
     * {@code CDEMO-MENU-OPT-NAME(3)}.
     *
     * @param cobolName      one of {@link #CDEMO_MENU_OPT_NUM}, {@link #CDEMO_MENU_OPT_NAME},
     *                       {@link #CDEMO_MENU_OPT_PGMNAME} or {@link #CDEMO_MENU_OPT_USRTYPE}
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to
     *                       {@value #TABLE_SIZE} inclusive
     * @return {@code cobolName} followed by the subscript in parentheses
     * @throws NullPointerException      if {@code cobolName} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside
     *                                   {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     */
    public static String subscriptedName(String cobolName, int cobolSubscript) {
        Objects.requireNonNull(cobolName, "A copybook field name is required to subscript it");
        requireSubscript(cobolSubscript);
        return cobolName + "(" + cobolSubscript + ")";
    }

    // =================================================================================================
    // Fixed-width images. The consuming program reads this table from WORKING-STORAGE rather than from
    // a dataset, so these exist for the parity harness's field-name-keyed diffing and to make the
    // geometry provable end to end. Every one of them takes the Charset as an EXPLICIT parameter and
    // routes all byte handling through FixedWidthCodec: no platform default is ever consulted and the
    // configuration type that resolves the code pages is not referenced.
    // =================================================================================================

    /**
     * Serialises the table to its {@value #GROUP_LENGTH}-byte image, writing every value from the
     * in-memory entries.
     *
     * <p>The record is first allocated over {@link #GROUP_LAYOUT}, so every byte - including the
     * reserved bytes of slots 11 and 12 - is already correct before anything is written; the entries
     * then overwrite the named table-view spans. Numeric columns are written zero-filled on the left
     * and character columns space-padded on the right, both by the codec's own {@code MOVE} rules, so
     * a pad byte cannot go missing without the layout's width check catching it.
     *
     * <p>This image must be byte-identical to {@link #declaredImage(Charset)}, which arrives at the
     * same bytes down an independent path - through the copybook {@code VALUE} clauses declared on the
     * layout rather than through the entry list. Comparing the two is a genuine cross-check that the
     * transcribed entries and the transcribed layout agree.
     *
     * @param charset the code page to encode into, named explicitly by the caller - {@code IBM037} for
     *                EBCDIC, {@code US-ASCII} for the text fixtures. It must encode every digit and
     *                the space to exactly one byte
     * @return exactly {@value #GROUP_LENGTH} bytes, a fresh array on every call
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} cannot encode the required characters to one
     *                                  byte each
     */
    public static byte[] encode(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.newRecord(GROUP_LAYOUT);
        codec.writePic9(area, MENU_OPT_COUNT_SPAN, menuOptCountImage());
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            Optional<MenuOption> slot = OPTIONS.get(subscript - FIRST_SUBSCRIPT);
            if (slot.isEmpty()) {
                // Nothing is written for a slot the copybook gives no VALUE. Its bytes are whatever
                // newRecord left there for a span with no declared literal, and that padding is this
                // module's own choice of a deterministic image - it is not, and must not be read as, a
                // statement about what the legacy program's storage holds. See unspecifiedTailSpan().
                continue;
            }
            MenuOption option = slot.get();
            List<FieldSpan> spans = entryFieldSpans(subscript);
            codec.writePic9(area, spans.get(NUM_SUBFIELD), option.menuOptNumImage());
            codec.writePicX(area, spans.get(NAME_SUBFIELD), option.menuOptName());
            codec.writePicX(area, spans.get(PGMNAME_SUBFIELD), option.menuOptPgmName());
            codec.writePicX(area, spans.get(USRTYPE_SUBFIELD), option.menuOptUsrType());
        }
        return area.toByteArray();
    }

    /**
     * The {@value #GROUP_LENGTH}-byte image produced by initialising a record from
     * {@link #GROUP_LAYOUT} alone - that is, from the copybook's {@code VALUE} clauses and, for a span
     * that has none, this module's own pad byte, with no entry ever consulted. The pad is determinism
     * this class chooses so that its output is reproducible; for the tail it is not a claim about what
     * the legacy program's storage holds. See {@link #unspecifiedTailSpan()}.
     *
     * <p>This is the copybook's declared initial state, and it is the independent half of the
     * cross-check described on {@link #encode(Charset)}. The two paths differ in more than plumbing:
     * here option 1's number reaches storage as the copybook's {@code VALUE 1} right-justified into
     * {@code PIC 9(02)}, whereas {@link #encode(Charset)} writes the already-formed image
     * {@code "01"}. Both must land the same two bytes.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return exactly {@value #GROUP_LENGTH} bytes, a fresh array on every call
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space and zero characters
     *                                  to exactly one byte each
     */
    public static byte[] declaredImage(Charset charset) {
        return GROUP_LAYOUT.newRecord(charset).toByteArray();
    }

    /**
     * Deserialises a {@value #GROUP_LENGTH}-byte image into the {@value #TABLE_SIZE}-slot table view.
     *
     * <p>Every column is read <strong>raw</strong>: names and program names keep their trailing spaces
     * and the option number keeps its leading zero, because the raw image is what the parity differ
     * compares and what makes a decode-then-encode round trip byte-identical. The result therefore
     * satisfies {@code decode(encode(cs), cs).equals(options())} for any usable charset, which is the
     * lossless round trip through the shared backing span that the {@code REDEFINES} pair requires.
     *
     * @param image   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an unmodifiable list of exactly {@value #TABLE_SIZE} entries in subscript order
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #GROUP_LENGTH} bytes, or
     *                                  if a decoded column is not valid for its declared
     *                                  {@code PICTURE}
     */
    public static List<Optional<MenuOption>> decode(byte[] image, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.wrap(image, GROUP_LAYOUT);
        List<Optional<MenuOption>> decoded = new ArrayList<>(TABLE_SIZE);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            if (!isSpecified(subscript)) {
                // The tail is read as BYTES, never as an entry. Two reasons, and both matter. It cannot
                // be attributed to the copybook's table, because the copybook assigns it nothing; and
                // it need not be well formed at all - real storage may hold anything there, including
                // bytes that are not digits, which decodePic9AsInt would reject and thereby fail a
                // decode of a perfectly valid image. Those bytes remain fully available through
                // fieldImages(byte[], Charset) and unspecifiedTailSpan(), which is where a caller that
                // genuinely wants them should look.
                decoded.add(Optional.empty());
                continue;
            }
            List<FieldSpan> spans = entryFieldSpans(subscript);
            String numImage = codec.readPicX(area, spans.get(NUM_SUBFIELD));
            decoded.add(Optional.of(new MenuOption(codec.decodePic9AsInt(numImage),
                    numImage,
                    codec.readPicX(area, spans.get(NAME_SUBFIELD)),
                    codec.readPicX(area, spans.get(PGMNAME_SUBFIELD)),
                    codec.readPicX(area, spans.get(USRTYPE_SUBFIELD)))));
        }
        return List.copyOf(decoded);
    }

    /**
     * The span of the table the copybook determines nothing about: slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}, as opaque storage.
     *
     * <p>{@value #TABLE_LENGTH} bytes of {@code CDEMO-MENU-OPTIONS} overlay
     * {@value #POPULATED_DATA_LENGTH} bytes of {@code CDEMO-MENU-OPTIONS-DATA}, and this descriptor
     * names the difference. Its {@code PICTURE} kind is deliberately {@code FILLER} and it carries no
     * initial value: it is a region of bytes whose meaning is not this copybook's to give, offered so
     * that a caller wanting to inspect or compare it - a parity case exercising an out-of-range option
     * subscript, for instance - reads the real bytes rather than trusting a synthesized entry.
     *
     * @return the descriptor for the unspecified tail, {@value #TABLE_SIZE} minus
     *         {@value #ACTIVE_OPTION_COUNT} entries wide
     */
    public static FieldSpan unspecifiedTailSpan() {
        return UNSPECIFIED_TAIL_SPAN;
    }

    /**
     * Decomposes a {@value #GROUP_LENGTH}-byte image into raw field images keyed by the COBOL
     * reference form of each field name, in copybook order - the map the parity differ compares field
     * by field rather than as one long string.
     *
     * <p>Keys are {@link #CDEMO_MENU_OPT_COUNT} followed by {@code CDEMO-MENU-OPT-NUM(1)},
     * {@code CDEMO-MENU-OPT-NAME(1)} and so on through subscript {@value #TABLE_SIZE} - forty-nine
     * entries. Values are untrimmed. The table view is used deliberately in preference to the
     * flattened storage view, whose forty-eight sub-spans are all named {@code FILLER} and so cannot
     * be distinct keys; nothing is thereby unverified, because {@link #GROUP_LAYOUT} already proves
     * those spans are present and account for their bytes.
     *
     * @param image   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an insertion-ordered, unmodifiable map of forty-nine field name to raw image pairs
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #GROUP_LENGTH} bytes
     */
    public static Map<String, String> fieldImages(byte[] image, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.wrap(image, GROUP_LAYOUT);
        Map<String, String> images = new LinkedHashMap<>();
        for (FieldSpan field : FIELD_SPANS) {
            images.put(field.name(), area.readSpan(field));
        }
        // Wrapped rather than Map.copyOf: a copy would be unmodifiable but its iteration order is
        // unspecified, and a differ that walks this map has to see the fields in copybook order.
        return Collections.unmodifiableMap(images);
    }

    // =================================================================================================
    // Private construction and character-level helpers.
    //
    // These two helpers apply the PIC X and PIC 9 width rules as CHARACTER operations, which is what a
    // constant table needs: the entries are built while this class initialises, long before any caller
    // has named a code page, and inventing one here would be exactly the implicit charset choice this
    // module forbids. Every BYTE operation still goes through FixedWidthCodec with a caller-supplied
    // Charset, and encode() deliberately re-applies the codec's own MOVE to these same images, so the
    // codec remains the single authority on byte placement.
    // =================================================================================================

    /**
     * Applies the {@code PIC X(n)} width rule to a transcribed copybook literal: right-space-pad to
     * exactly {@code declaredWidth}.
     *
     * <p>Over-long text is rejected rather than truncated. COBOL truncates a {@code MOVE} into a
     * {@code PIC X} receiver on the right, but this helper serves only transcribed {@code VALUE}
     * literals, and a literal longer than its picture is a transcription error the COBOL compiler
     * would itself reject - so silently discarding characters here would hide the defect.
     */
    private static String picXImage(String literal, int declaredWidth, String cobolName) {
        Objects.requireNonNull(literal, cobolName + " literal is required; the copybook declares a "
                + "VALUE for every populated entry");
        if (literal.length() > declaredWidth) {
            throw new IllegalArgumentException(cobolName + " literal '" + literal + "' is "
                    + literal.length() + " character(s) but the copybook declares PIC X("
                    + declaredWidth + "); a VALUE literal wider than its picture is a transcription "
                    + "error and is never truncated silently");
        }
        return literal + " ".repeat(declaredWidth - literal.length());
    }

    /**
     * Applies the {@code PIC 9(n)} width rule to an option number: left-zero-fill to exactly
     * {@code declaredWidth}, so 1 becomes {@code "01"} and 10 becomes {@code "10"}.
     *
     * <p>A negative value and a value needing more digits than the picture allows are both rejected.
     * {@code PIC 9(02)} is unsigned and has no sign position, and a value too wide for it is a
     * transcription error rather than data to be trimmed.
     */
    private static String pic9Image(int value, int declaredWidth) {
        if (value < 0) {
            throw new IllegalArgumentException("Option number " + value + " is negative; the copybook "
                    + "declares PIC 9(" + declaredWidth + "), which is unsigned and has no sign "
                    + "position");
        }
        String digits = Integer.toString(value);
        if (digits.length() > declaredWidth) {
            throw new IllegalArgumentException("Option number " + value + " needs "
                    + digits.length() + " digit(s) but the copybook declares PIC 9(" + declaredWidth
                    + ")");
        }
        return "0".repeat(declaredWidth - digits.length()) + digits;
    }

    /**
     * Rejects a subscript outside the {@code OCCURS} range, deferring to the shared one-based
     * {@code OCCURS} primitive so that the range check and its message live in exactly one place for
     * the whole migration.
     */
    private static void requireSubscript(int cobolSubscript) {
        FixedWidthRecord.occursElementOffsetOneBased(TABLE_OFFSET, ENTRY_LENGTH, TABLE_SIZE,
                cobolSubscript);
    }

    /**
     * A slot the copybook declares a {@code VALUE} for.
     *
     * @param menuOptNum     {@code CDEMO-MENU-OPT-NUM}
     * @param menuOptName    {@code CDEMO-MENU-OPT-NAME}, padded to its declared width
     * @param menuOptPgmName {@code CDEMO-MENU-OPT-PGMNAME}, padded to its declared width
     * @param menuOptUsrType {@code CDEMO-MENU-OPT-USRTYPE}
     * @return the entry, present
     */
    private static Optional<MenuOption> specified(int menuOptNum,
                                                  String menuOptName,
                                                  String menuOptPgmName,
                                                  String menuOptUsrType) {
        return Optional.of(MenuOption.of(menuOptNum, menuOptName, menuOptPgmName, menuOptUsrType));
    }

    /**
     * A slot the copybook declares no {@code VALUE} for, and about which nothing is therefore claimed.
     *
     * <p>This replaced a factory that built an entry carrying option number 0 and blank text. That
     * entry was indistinguishable from real data and was not derived from anything: IBM Enterprise
     * COBOL initialises storage from applicable {@code VALUE} clauses, and storage with none - here,
     * storage beyond the very end of the group being redefined - is not documented to receive any
     * particular content.
     *
     * @return an empty slot
     */
    private static Optional<MenuOption> unspecified() {
        return Optional.empty();
    }

    /**
     * Declares the {@value #GROUP_LENGTH}-byte layout of the whole group.
     *
     * <p>The forty-eight sub-spans are generated from {@link #OPTIONS} so that the copybook's values
     * are transcribed exactly once, in the table above, and cannot drift between the two. The element
     * offsets come from the shared one-based {@code OCCURS} primitive rather than from arithmetic
     * written out here.
     */
    private static RecordLayout buildGroupLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(MENU_OPT_COUNT_SPAN);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= OPTIONS.size(); subscript++) {
            Optional<MenuOption> slot = OPTIONS.get(subscript - FIRST_SUBSCRIPT);
            int base = FixedWidthRecord.occursElementOffsetOneBased(TABLE_OFFSET, ENTRY_LENGTH,
                    OPTIONS.size(), subscript);
            // A slot the copybook values contributes its literal. A slot it does not contributes NONE -
            // the descriptor carries a null initial value, which is what "the copybook says nothing
            // about these bytes" looks like in a layout. What newRecord then pads them with is this
            // module's determinism and not a claim about the legacy program's storage.
            spans.add(fillerSpan(base + OPT_NUM_OFFSET, OPT_NUM_LENGTH, PictureKind.UNSIGNED_NUMERIC,
                    slot.map(option -> Integer.toString(option.menuOptNum())).orElse(null)));
            spans.add(fillerSpan(base + OPT_NAME_OFFSET, OPT_NAME_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptName).orElse(null)));
            spans.add(fillerSpan(base + OPT_PGMNAME_OFFSET, OPT_PGMNAME_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptPgmName).orElse(null)));
            spans.add(fillerSpan(base + OPT_USRTYPE_OFFSET, OPT_USRTYPE_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptUsrType).orElse(null)));
        }
        // The REDEFINES pair, declared last so both overlays fall inside storage already accounted for.
        spans.add(POPULATED_DATA_SPAN);
        spans.add(TABLE_SPAN);
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    /**
     * One {@code FILLER} sub-span of the literal-storage group. The numeric columns are declared with a
     * numeric kind - {@code FILLER} remains their non-referable COBOL name - so that {@code VALUE 1}
     * is stored right-justified as {@code "01"} rather than left-justified as {@code "1 "}.
     */
    private static FieldSpan fillerSpan(int offset, int length, PictureKind kind, String literal) {
        return new FieldSpan(FILLER, offset, length, kind, literal, false);
    }

    /**
     * Declares the forty-nine elementary descriptors of the table view, in copybook order.
     */
    private static List<FieldSpan> buildFieldSpans() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(MENU_OPT_COUNT_SPAN);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            spans.addAll(entryFieldSpans(subscript));
        }
        return List.copyOf(spans);
    }

    /**
     * Not instantiable: this class holds one copybook's constant table and has no state.
     *
     * @throws AssertionError always, if reflection is used to invoke it
     */
    private MenuOptions() {
        throw new AssertionError("MenuOptions is a copybook-derived constant table and must not be "
                + "instantiated");
    }
}
