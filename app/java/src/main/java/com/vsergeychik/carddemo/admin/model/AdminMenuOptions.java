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
import java.util.Objects;
import java.util.Optional;

/**
 * The single Java type for COBOL copybook {@code app/cpy/COADM02Y.cpy} - the administrator menu
 * option table of the CardDemo admin transaction {@code CA00}.
 *
 * <h2>What this class is</h2>
 * A pure, immutable, copybook-derived constant table. It performs no I/O, holds no framework state
 * and carries no framework annotation of any kind. Every value below is transcribed by hand from the
 * copybook, so a reviewer holding {@code app/cpy/COADM02Y.cpy} open beside this file can confirm each
 * width, each offset and each literal line by line. No copybook parser, no reflection-driven mapper
 * and no third-party library is involved (practice B11).
 *
 * <p>This is a <strong>like-for-like language migration, not a redesign</strong>. Where the copybook
 * does something odd, this file does the same odd thing and records why, rather than tidying it.
 *
 * <h2>The copybook, verbatim</h2>
 * <pre>
 *   L19  01 CARDDEMO-ADMIN-MENU-OPTIONS.
 *   L20    05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 4.
 *   L22    05 CDEMO-ADMIN-OPTIONS-DATA.
 *   L24      10 FILLER                        PIC 9(02) VALUE 1.
 *   L25      10 FILLER                        PIC X(35) VALUE
 *   L26          'User List (Security)               '.
 *   L27      10 FILLER                        PIC X(08) VALUE 'COUSR00C'.
 *   L29      10 FILLER                        PIC 9(02) VALUE 2.       (User Add,    COUSR01C)
 *   L34      10 FILLER                        PIC 9(02) VALUE 3.       (User Update, COUSR02C)
 *   L39      10 FILLER                        PIC 9(02) VALUE 4.       (User Delete, COUSR03C)
 *   L44    05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.
 *   L45      10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
 *   L46        15 CDEMO-ADMIN-OPT-NUM           PIC 9(02).
 *   L47        15 CDEMO-ADMIN-OPT-NAME          PIC X(35).
 *   L48        15 CDEMO-ADMIN-OPT-PGMNAME       PIC X(08).
 * </pre>
 * Exactly <strong>three</strong> subfields per entry. There is deliberately <strong>no user-type
 * authorisation column</strong> here: that column belongs to the sibling copybook
 * {@code app/cpy/COMEN02Y.cpy} and its own Java type, and adding one by analogy would be inventing
 * data this copybook does not carry.
 *
 * <h2>Nine slots, four populated - and both facts are kept</h2>
 * The copybook uses an idiom that is easy to mistake for a typo and is not one. The literal storage
 * group {@code CDEMO-ADMIN-OPTIONS-DATA} declares only the <em>populated</em> entries - four of them,
 * {@value #ACTIVE_OPTION_COUNT} x {@value #ENTRY_LENGTH} = {@value #POPULATED_DATA_LENGTH} bytes -
 * while the redefining group {@code CDEMO-ADMIN-OPTIONS} declares an {@code OCCURS} table of
 * {@value #TABLE_SIZE} entries, {@value #TABLE_SIZE} x {@value #ENTRY_LENGTH} =
 * {@value #TABLE_LENGTH} bytes. So the redefining item is <strong>larger than the item it
 * redefines</strong>, and slots 5 through {@value #TABLE_SIZE} exist as slots while no {@code VALUE}
 * clause reaches them - they lie past the end of the group being redefined. This class therefore reports
 * them as <strong>absent</strong>, not as entries carrying option number 0 and blank names: IBM
 * Enterprise COBOL initialises storage from applicable {@code VALUE} clauses, and there is no applicable
 * clause here, so any specific content this class asserted for those bytes would be its own invention.
 * See {@link #isSpecified(int)} and {@link #unspecifiedTailSpan()}.
 *
 * <p>Both numbers are therefore real, distinct facts and are held as two separate constants:
 * {@link #TABLE_SIZE} is the table's size and {@link #ACTIVE_OPTION_COUNT} is the active count that
 * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} carries. Conflating them - trimming the table to
 * four entries, or looping to nine - is precisely the defect this class exists to prevent. The five
 * empty slots are preserved exactly as the copybook leaves them - which is to say, without content
 * (practice B5: dead and unvalued declarations are preserved, never cleaned up). The size asymmetry is recorded here and in
 * {@link #GROUP_LAYOUT} rather than "corrected" (practice B4).
 *
 * <h2>Two views over one set of bytes</h2>
 * {@code CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA} is one of the migration's
 * {@code REDEFINES} sites, and it is exposed as two typed views over a single backing span rather
 * than as two independent copies:
 * <ul>
 *   <li>{@link #ADMIN_OPTIONS_DATA_SPAN} - the literal-storage view, {@value #POPULATED_DATA_LENGTH}
 *       bytes, read by {@link #adminOptionsDataImage(byte[], Charset)};</li>
 *   <li>{@link #ADMIN_OPTIONS_SPAN} - the {@code OCCURS} table view, {@value #TABLE_LENGTH} bytes,
 *       read by {@link #adminOptionsImage(byte[], Charset)}.</li>
 * </ul>
 * Both address the same bytes of the same record, so the literal-storage image is always exactly the
 * leading {@value #POPULATED_DATA_LENGTH} characters of the table image, and a table view encoded to
 * bytes and decoded back is byte-for-byte and field-for-field identical.
 *
 * <h2>Why the flattened layout inverts the copybook's storage and overlay roles</h2>
 * {@link RecordLayout} runs a geometry self-check that requires every byte of a record to be declared
 * exactly once as contiguous storage, and requires a {@code REDEFINES} overlay to fall entirely
 * inside storage declared ahead of it. The {@code 01} group here physically occupies
 * {@value #GROUP_LENGTH} bytes - {@value #OPT_COUNT_LENGTH} for the count plus the
 * {@value #TABLE_LENGTH} the {@code OCCURS} overlay needs - because the group is extended to hold
 * its largest redefinition. The elementary items that actually name those {@value #TABLE_LENGTH}
 * bytes are the {@code OCCURS} elements, and the copybook declares no storage at all for slots 5
 * through {@value #TABLE_SIZE}.
 *
 * <p>The only geometry that satisfies the self-check is therefore to declare the
 * {@value #TABLE_SIZE} x 3 = 27 {@code OCCURS} elementary items as the storage spans and <em>both</em>
 * {@code 05}-level group items as named views over them. That inverts the copybook's storage and
 * overlay roles at group level. The inversion is recorded here rather than hidden, and it
 * <strong>changes no byte</strong>: every offset, every width and every declared {@code VALUE} below
 * is the copybook's own.
 *
 * <h2>Field names are the copybook's, never renamed</h2>
 * {@code CDEMO-ADMIN-OPT-COUNT}, {@code CDEMO-ADMIN-OPT-NUM}, {@code CDEMO-ADMIN-OPT-NAME} and
 * {@code CDEMO-ADMIN-OPT-PGMNAME} survive verbatim as the {@code *_FIELD} constants and, in idiomatic
 * camelCase with the COBOL spelling quoted in the documentation, as the accessors of
 * {@link AdminMenuOption}. The parity differ compares field by field <em>by name</em>, so a renamed
 * field would make a real difference invisible.
 *
 * <p>Because {@link RecordLayout} rejects a duplicated referable name, the 27 element spans carry the
 * COBOL subscript in their names - {@code CDEMO-ADMIN-OPT-NUM(1)} through
 * {@code CDEMO-ADMIN-OPT-PGMNAME(9)} - which is exactly how {@code app/cbl/COADM01C.cbl} itself
 * refers to them, for instance {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)} at line 143. Build such a
 * key with {@link #subscriptedName(String, int)} rather than by string concatenation at a call site.
 *
 * <h2>Values are never trimmed</h2>
 * {@link AdminMenuOption#adminOptName()} and {@link AdminMenuOption#adminOptPgmName()} return their
 * full declared widths, right-space-padded, and {@link AdminMenuOption#adminOptNumImage()} returns
 * the raw zero-filled two-byte display image. The sole consumer depends on all three:
 * <ul>
 *   <li>{@code app/cbl/COADM01C.cbl:138} takes {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5)} - a
 *       five-byte reference modification of the eight-byte field - to test it against
 *       {@code 'DUMMY'}, which needs the untrimmed eight bytes;</li>
 *   <li>{@code app/cbl/COADM01C.cbl:143} passes the whole eight-byte name to
 *       {@code EXEC CICS XCTL PROGRAM(...)};</li>
 *   <li>{@code app/cbl/COADM01C.cbl:233} does
 *       {@code STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE} into
 *       {@code WS-ADMIN-OPT-TXT PIC X(40)}, which moves the raw two-byte display image - so option 1
 *       must render {@code 01}, with its leading zero, and an {@code int} alone could not reproduce
 *       that.</li>
 * </ul>
 * A fourth quirk depends on the name surviving unmodified: {@code app/cbl/COADM01C.cbl:150-151}
 * comments the option name out of the "coming soon" {@code STRING}, so that program emits
 * {@code This option is coming soon ...} with no name in it. Reproducing that is the service's
 * concern; exposing the name faithfully so it can be, is this class's.
 *
 * <h2>What deliberately is not here</h2>
 * No decision logic. The option-count validation of {@code app/cbl/COADM01C.cbl:127-134}, the
 * {@code 'DUMMY'} prefix test of line 138, and the composition of the forty-byte option line of lines
 * 231-236 all belong to the admin menu service, which is where the branches they contain must live to
 * be reachable by a service-level test without an HTTP layer in the path. This class exposes data and
 * accessors only.
 *
 * <p>For the record, that option line is forty bytes wide, proven three independent ways -
 * {@code WS-ADMIN-OPT-TXT PIC X(40)} at {@code app/cbl/COADM01C.cbl:48}, {@code OPTN00nI PIC X(40)}
 * in {@code app/cpy-bms/COADM01.CPY}, and {@code DFHMDF ... LENGTH=40} in
 * {@code app/bms/COADM01.bms} - and is composed as the two-byte number image, then {@code '. '}, then
 * the thirty-five-byte name: 39 bytes moved into a field the program has just cleared to spaces.
 * Everything this class must supply for that is public; the composition itself is not performed here.
 *
 * <p>Also absent, deliberately: no scaled-decimal handling, because {@code CDEMO-ADMIN-OPT-NUM PIC
 * 9(02)} is scale-free and this copybook declares no decimal {@code PICTURE} at all - the option
 * number is an {@code int}, and no binary floating-point primitive appears anywhere below; no
 * persistence mapping of any kind, since the sole consumer reads this table from
 * {@code WORKING-STORAGE} rather than from a dataset; and no server-side conversational state, since
 * this is a compile-time constant table, not per-request data.
 *
 * <h2>Thread safety</h2>
 * Every member is a static final immutable value: {@code int} constants, {@link String} constants,
 * immutable {@link FieldSpan} and {@link RecordLayout} records, and an unmodifiable {@link List} of
 * immutable {@link AdminMenuOption} records. There is no mutable static state, nothing is lazily
 * initialised, and no array is ever handed out. The class is safe to read from any number of threads
 * and cannot be instantiated.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 */
public final class AdminMenuOptions {

    // =================================================================================================
    // Section 1 - the copybook's own field names, verbatim.
    //
    // These are the keys the parity differ compares by and the keys RecordLayout.span(String) resolves,
    // so they are declared once, as constants, rather than spelled out at call sites where a typo would
    // silently miss a field (practice B8).
    // =================================================================================================

    /** {@code CDEMO-ADMIN-OPT-COUNT} - the active option count. Source: {@code COADM02Y.cpy:20}. */
    public static final String ADMIN_OPT_COUNT_FIELD = "CDEMO-ADMIN-OPT-COUNT";

    /**
     * {@code CDEMO-ADMIN-OPTIONS-DATA} - the literal-storage group holding only the populated
     * entries. Source: {@code COADM02Y.cpy:22}.
     */
    public static final String ADMIN_OPTIONS_DATA_FIELD = "CDEMO-ADMIN-OPTIONS-DATA";

    /**
     * {@code CDEMO-ADMIN-OPTIONS} - the group that redefines {@code CDEMO-ADMIN-OPTIONS-DATA} as an
     * {@code OCCURS} table. Source: {@code COADM02Y.cpy:44}.
     */
    public static final String ADMIN_OPTIONS_FIELD = "CDEMO-ADMIN-OPTIONS";

    /**
     * The suffix naming the unspecified tail descriptor: {@value #UNSPECIFIED_TAIL_SUFFIX}.
     *
     * <p>A suffix rather than a copybook name, because the copybook has none for this region: it is the
     * part of the {@code OCCURS} overlay reaching past the group it redefines.
     */
    public static final String UNSPECIFIED_TAIL_SUFFIX = "-UNSPECIFIED-TAIL";

    /**
     * {@code CDEMO-ADMIN-OPT} - one entry of the {@code OCCURS 9} table. Source:
     * {@code COADM02Y.cpy:45}. Subscript it with {@link #subscriptedName(String, int)}.
     */
    public static final String ADMIN_OPT_FIELD = "CDEMO-ADMIN-OPT";

    /**
     * {@code CDEMO-ADMIN-OPT-NUM} - an entry's option number, {@code PIC 9(02)}. Source:
     * {@code COADM02Y.cpy:46}. Subscript it with {@link #subscriptedName(String, int)}.
     */
    public static final String ADMIN_OPT_NUM_FIELD = "CDEMO-ADMIN-OPT-NUM";

    /**
     * {@code CDEMO-ADMIN-OPT-NAME} - an entry's display name, {@code PIC X(35)}. Source:
     * {@code COADM02Y.cpy:47}. Subscript it with {@link #subscriptedName(String, int)}.
     */
    public static final String ADMIN_OPT_NAME_FIELD = "CDEMO-ADMIN-OPT-NAME";

    /**
     * {@code CDEMO-ADMIN-OPT-PGMNAME} - an entry's target program name, {@code PIC X(08)}. Source:
     * {@code COADM02Y.cpy:48}. Subscript it with {@link #subscriptedName(String, int)}.
     */
    public static final String ADMIN_OPT_PGMNAME_FIELD = "CDEMO-ADMIN-OPT-PGMNAME";

    // =================================================================================================
    // Section 2 - widths, counts and offsets.
    //
    // Every width is a named constant, so no bare numeric literal appears at any use site below
    // (practice B8, gate G52). The composite widths are DERIVED from their parts rather than restated,
    // which makes an arithmetic self-check unnecessary: ENTRY_LENGTH cannot disagree with the three
    // subfield widths, and TABLE_LENGTH cannot disagree with TABLE_SIZE.
    // =================================================================================================

    /** Declared width of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02)}: 2 bytes. {@code COADM02Y.cpy:20}. */
    public static final int OPT_COUNT_LENGTH = 2;

    /** Declared width of {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}: 2 bytes. {@code COADM02Y.cpy:46}. */
    public static final int OPT_NUM_LENGTH = 2;

    /** Declared width of {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}: 35 bytes. {@code COADM02Y.cpy:47}. */
    public static final int OPT_NAME_LENGTH = 35;

    /**
     * Declared width of {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}: 8 bytes.
     * {@code COADM02Y.cpy:48}.
     */
    public static final int OPT_PGMNAME_LENGTH = 8;

    /**
     * Width of one {@code CDEMO-ADMIN-OPT} entry: {@value #OPT_NUM_LENGTH} +
     * {@value #OPT_NAME_LENGTH} + {@value #OPT_PGMNAME_LENGTH} = {@value #ENTRY_LENGTH} bytes.
     * Derived from its three parts so it can never drift from them.
     */
    public static final int ENTRY_LENGTH = OPT_NUM_LENGTH + OPT_NAME_LENGTH + OPT_PGMNAME_LENGTH;

    /**
     * The {@code OCCURS} count of {@code CDEMO-ADMIN-OPT}: {@value #TABLE_SIZE} entries.
     * {@code COADM02Y.cpy:45}.
     *
     * <p>This is the <strong>size of the table</strong>, and it is not the number of populated
     * entries - see {@link #ACTIVE_OPTION_COUNT}. Iterate to this bound only when walking the whole
     * table, including the five unvalued slots.
     */
    public static final int TABLE_SIZE = 9;

    /**
     * The value of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}: {@value #ACTIVE_OPTION_COUNT}.
     * {@code COADM02Y.cpy:20}.
     *
     * <p>This is the <strong>active option count</strong>, and it is not the table size - see
     * {@link #TABLE_SIZE}. It is the bound the sole consumer iterates and validates against:
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT}
     * ({@code app/cbl/COADM01C.cbl:228-229}) and
     * {@code IF ... WS-OPTION > CDEMO-ADMIN-OPT-COUNT ...} ({@code app/cbl/COADM01C.cbl:128}).
     */
    public static final int ACTIVE_OPTION_COUNT = 4;

    /**
     * The first subscript the copybook declares no {@code VALUE} for:
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE}.
     *
     * <p>Named so that the boundary between the storage this copybook determines and the storage it does
     * not is stated once, as a constant, rather than written out as an expression wherever it is needed.
     */
    public static final int SPECIFIED_OPTION_COUNT_PLUS_ONE = ACTIVE_OPTION_COUNT + 1;

    /**
     * Width of {@code CDEMO-ADMIN-OPTIONS-DATA}, the literal-storage view:
     * {@value #ACTIVE_OPTION_COUNT} x {@value #ENTRY_LENGTH} = {@value #POPULATED_DATA_LENGTH} bytes.
     * {@code COADM02Y.cpy:22-42}.
     */
    public static final int POPULATED_DATA_LENGTH = ACTIVE_OPTION_COUNT * ENTRY_LENGTH;

    /**
     * Width of {@code CDEMO-ADMIN-OPTIONS}, the {@code OCCURS} table view: {@value #TABLE_SIZE} x
     * {@value #ENTRY_LENGTH} = {@value #TABLE_LENGTH} bytes. {@code COADM02Y.cpy:44-48}.
     *
     * <p>Larger than {@link #POPULATED_DATA_LENGTH}, the item it redefines, by 225 bytes - the five
     * unvalued slots. That asymmetry is the copybook's and is preserved, not corrected.
     */
    public static final int TABLE_LENGTH = TABLE_SIZE * ENTRY_LENGTH;

    /**
     * Width of the whole {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS} group as it is physically laid out:
     * {@value #OPT_COUNT_LENGTH} + {@value #TABLE_LENGTH} = {@value #GROUP_LENGTH} bytes.
     * {@code COADM02Y.cpy:19-48}.
     *
     * <p>The group is sized by its <em>largest</em> redefinition, which is why this is
     * {@value #OPT_COUNT_LENGTH} + {@value #TABLE_LENGTH} rather than {@value #OPT_COUNT_LENGTH} +
     * {@value #POPULATED_DATA_LENGTH}.
     */
    public static final int GROUP_LENGTH = OPT_COUNT_LENGTH + TABLE_LENGTH;

    /**
     * Absolute 0-based byte offset of the first {@code OCCURS} entry within the group: immediately
     * after {@code CDEMO-ADMIN-OPT-COUNT}, so {@value #OPTIONS_OFFSET}.
     */
    public static final int OPTIONS_OFFSET = OPT_COUNT_LENGTH;

    /**
     * The highest value {@code PIC 9(02)} can hold, and therefore the inclusive upper bound this class
     * accepts for an option number: 99. A two-digit unsigned display field cannot represent more, so a
     * larger value is a transcription error rather than data.
     */
    public static final int MAX_OPT_NUM = 99;

    // =================================================================================================
    // Section 3 - the four populated entries, exactly as the copybook declares them.
    //
    // The display texts are held UNPADDED and the trailing padding is applied from OPT_NAME_LENGTH by
    // picXImage, so the run of significant trailing spaces is produced by the named width rather than
    // typed out and miscounted (practice B8). The verbatim copybook literal is quoted in each comment
    // below so a reviewer can still diff it against the source by eye (practice B11), and the measured
    // arithmetic that proves the two agree is stated with it.
    // =================================================================================================

    /**
     * Option 1's display name. Copybook literal, {@code COADM02Y.cpy:26}:
     * {@code 'User List (Security)               '} - 20 significant characters followed by 15 spaces,
     * which is {@value #OPT_NAME_LENGTH} in total. Padding the 20 characters below to
     * {@link #OPT_NAME_LENGTH} reproduces that literal exactly.
     */
    private static final String OPTION_1_TEXT = "User List (Security)";

    /**
     * Option 2's display name. Copybook literal, {@code COADM02Y.cpy:31}:
     * {@code 'User Add (Security)                '} - 19 significant characters followed by 16 spaces,
     * which is {@value #OPT_NAME_LENGTH} in total.
     */
    private static final String OPTION_2_TEXT = "User Add (Security)";

    /**
     * Option 3's display name. Copybook literal, {@code COADM02Y.cpy:36}:
     * {@code 'User Update (Security)             '} - 22 significant characters followed by 13 spaces,
     * which is {@value #OPT_NAME_LENGTH} in total.
     */
    private static final String OPTION_3_TEXT = "User Update (Security)";

    /**
     * Option 4's display name. Copybook literal, {@code COADM02Y.cpy:41}:
     * {@code 'User Delete (Security)             '} - 22 significant characters followed by 13 spaces,
     * which is {@value #OPT_NAME_LENGTH} in total.
     */
    private static final String OPTION_4_TEXT = "User Delete (Security)";

    /** {@code COADM02Y.cpy:27} - the security user-list program. */
    private static final String OPTION_1_PGMNAME = "COUSR00C";

    /** {@code COADM02Y.cpy:32} - the security user-add program. */
    private static final String OPTION_2_PGMNAME = "COUSR01C";

    /** {@code COADM02Y.cpy:37} - the security user-update program. */
    private static final String OPTION_3_PGMNAME = "COUSR02C";

    /** {@code COADM02Y.cpy:42} - the security user-delete program. */
    private static final String OPTION_4_PGMNAME = "COUSR03C";

    /** The character COBOL pads a {@code PIC X} field with, on the right. */
    private static final String PIC_X_PAD = " ";

    /** The character COBOL pads a {@code PIC 9} display field with, on the left. */
    private static final String PIC_9_PAD = "0";

    /** Opening delimiter of a COBOL subscript, as {@code app/cbl/COADM01C.cbl} spells one. */
    private static final String SUBSCRIPT_OPEN = "(";

    /** Closing delimiter of a COBOL subscript. */
    private static final String SUBSCRIPT_CLOSE = ")";

    // =================================================================================================
    // Section 4 - one entry of the OCCURS table, as an immutable value.
    //
    // A record, so it is final, has value equality and cannot be mutated after construction (practice
    // B9, gate G53). No Lombok and no generated accessors: both are outside the closed dependency set,
    // and a generated accessor would obscure the byte-exact field mapping that parity depends on
    // (practice B2).
    // =================================================================================================

    /**
     * One {@code CDEMO-ADMIN-OPT} entry - the three elementary items of
     * {@code app/cpy/COADM02Y.cpy:46-48}, and nothing else.
     *
     * <p>Values are held at their <strong>declared widths</strong>, never trimmed. The canonical
     * constructor enforces that, which is what makes each of the four copybook names verifiably
     * exactly {@value AdminMenuOptions#OPT_NAME_LENGTH} characters and each program name exactly
     * {@value AdminMenuOptions#OPT_PGMNAME_LENGTH}: every entry in
     * {@link AdminMenuOptions#options()} is built through it, so a mis-transcribed literal cannot
     * reach the table.
     *
     * <p>To build an entry from unpadded text, use {@link #of(int, String, String)}, which applies the
     * COBOL {@code MOVE} rules for the target {@code PICTURE} first.
     *
     * @param adminOptNum     {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} - the option number as a scale-free
     *                        {@code int}, because the picture declares no decimal position. Its raw
     *                        two-byte display image is {@link #adminOptNumImage()}. From 0 to
     *                        {@value AdminMenuOptions#MAX_OPT_NUM} inclusive - the whole range
     *                        {@code PIC 9(02)} admits
     * @param adminOptName    {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} - the display name, exactly
     *                        {@value AdminMenuOptions#OPT_NAME_LENGTH} characters, right-space-padded
     *                        and never trimmed
     * @param adminOptPgmName {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} - the target program name,
     *                        exactly {@value AdminMenuOptions#OPT_PGMNAME_LENGTH} characters,
     *                        right-space-padded and never trimmed
     */
    public record AdminMenuOption(int adminOptNum, String adminOptName, String adminOptPgmName) {

        /**
         * Validates the entry against the copybook's declared widths and picture range, so a
         * transcription error is caught where the entry is built rather than where a byte later reads
         * back wrong.
         *
         * @throws NullPointerException     if {@code adminOptName} or {@code adminOptPgmName} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code adminOptNum} is negative or above
         *                                  {@value AdminMenuOptions#MAX_OPT_NUM}, or either name is
         *                                  not exactly its declared width
         */
        public AdminMenuOption {
            Objects.requireNonNull(adminOptName, "CDEMO-ADMIN-OPT-NAME is required; a "
                    + OPT_NAME_LENGTH + "-byte span always holds bytes, so pass spaces to blank it. A "
                    + "slot with no content at all is Optional.empty() in the table, not an entry "
                    + "carrying nulls");
            Objects.requireNonNull(adminOptPgmName, "CDEMO-ADMIN-OPT-PGMNAME is required; a "
                    + OPT_PGMNAME_LENGTH + "-byte span always holds bytes, so pass spaces to blank it. "
                    + "A slot with no content at all is Optional.empty() in the table, not an entry "
                    + "carrying nulls");
            if (adminOptNum < 0 || adminOptNum > MAX_OPT_NUM) {
                throw new IllegalArgumentException("CDEMO-ADMIN-OPT-NUM is PIC 9(0" + OPT_NUM_LENGTH
                        + "), an unsigned two-digit display field, so it holds 0 to " + MAX_OPT_NUM
                        + " inclusive; " + adminOptNum + " does not fit it");
            }
            if (adminOptName.length() != OPT_NAME_LENGTH) {
                throw new IllegalArgumentException("CDEMO-ADMIN-OPT-NAME is PIC X("
                        + OPT_NAME_LENGTH + ") and must be held at its full declared width, "
                        + "right-space-padded and untrimmed, but this value is "
                        + adminOptName.length() + " character(s). Build the entry with of(int, "
                        + "String, String) to apply the PIC X MOVE rule first");
            }
            if (adminOptPgmName.length() != OPT_PGMNAME_LENGTH) {
                throw new IllegalArgumentException("CDEMO-ADMIN-OPT-PGMNAME is PIC X(0"
                        + OPT_PGMNAME_LENGTH + ") and must be held at its full declared width, "
                        + "right-space-padded and untrimmed, but this value is "
                        + adminOptPgmName.length() + " character(s). Build the entry with of(int, "
                        + "String, String) to apply the PIC X MOVE rule first");
            }
        }

        /**
         * Builds an entry from unpadded text, applying the COBOL {@code MOVE} rule for each target
         * {@code PICTURE}: both names are padded on the right with spaces to their declared widths, and
         * truncated on the right if over-wide, exactly as a {@code PIC X} receiver behaves.
         *
         * <p>This is the form the four copybook entries are built with, which is why no run of trailing
         * spaces is typed out anywhere in this class: the padding comes from
         * {@link AdminMenuOptions#OPT_NAME_LENGTH} and
         * {@link AdminMenuOptions#OPT_PGMNAME_LENGTH} (practice B8).
         *
         * @param adminOptNum  {@code CDEMO-ADMIN-OPT-NUM}, from 0 to
         *                     {@value AdminMenuOptions#MAX_OPT_NUM} inclusive
         * @param optionText   the display name, padded or truncated here to
         *                     {@value AdminMenuOptions#OPT_NAME_LENGTH} characters
         * @param programName  the target program name, padded or truncated here to
         *                     {@value AdminMenuOptions#OPT_PGMNAME_LENGTH} characters
         * @return the entry, with both names at their full declared widths
         * @throws NullPointerException     if {@code optionText} or {@code programName} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code adminOptNum} does not fit {@code PIC 9(02)}
         */
        public static AdminMenuOption of(int adminOptNum, String optionText, String programName) {
            Objects.requireNonNull(optionText, "A display name is required for CDEMO-ADMIN-OPT-NAME");
            Objects.requireNonNull(programName,
                    "A program name is required for CDEMO-ADMIN-OPT-PGMNAME");
            return new AdminMenuOption(adminOptNum,
                    picXImage(optionText, OPT_NAME_LENGTH),
                    picXImage(programName, OPT_PGMNAME_LENGTH));
        }


        /**
         * The raw two-byte zero-filled display image of {@code CDEMO-ADMIN-OPT-NUM}, as the field is
         * actually stored: option 1 renders {@code 01} and option 10 would render {@code 10}.
         *
         * <p>Not optional, and not interchangeable with {@link #adminOptNum()}.
         * {@code app/cbl/COADM01C.cbl:233} moves this image with
         * {@code STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE}, so the leading zero is part of
         * the observable output; an {@code int} alone cannot reproduce it.
         *
         * @return exactly {@value AdminMenuOptions#OPT_NUM_LENGTH} digits
         */
        public String adminOptNumImage() {
            return picNineImage(adminOptNum, OPT_NUM_LENGTH);
        }
    }

    // =================================================================================================
    // Section 5 - the table itself: nine slots, four populated.
    //
    // Held as an unmodifiable List of immutable records, built once at class initialisation. Nothing
    // here is mutable and no array is ever handed out, so there is no static mutable state and no
    // defensive copy to forget (practice B9, gate G53).
    // =================================================================================================

    /**
     * The {@value #TABLE_SIZE} entries of {@code CDEMO-ADMIN-OPT}, in COBOL declaration order and
     * indexed from 0 in the Java sense. Unmodifiable and built exactly once.
     */
    private static final List<Optional<AdminMenuOption>> OPTIONS = buildOptions();

    /**
     * The {@value #ACTIVE_OPTION_COUNT} entries the copybook declares a {@code VALUE} for, with no
     * absent slot to consider. Exposed by {@link #activeOptions()}.
     */
    private static final List<AdminMenuOption> ACTIVE_OPTIONS =
            OPTIONS.subList(0, ACTIVE_OPTION_COUNT).stream()
                    .map(slot -> slot.orElseThrow(() -> new IllegalStateException(
                            "Each of the first " + ACTIVE_OPTION_COUNT + " slots of "
                                    + ADMIN_OPTIONS_FIELD + " carries a copybook VALUE, so none may be "
                                    + "absent; this class is mis-transcribed if one is")))
                    .toList();

    /**
     * The whole {@code CDEMO-ADMIN-OPT OCCURS 9} table: all {@value #TABLE_SIZE} slots in copybook
     * declaration order, of which the first {@value #ACTIVE_OPTION_COUNT} carry the copybook's
     * {@code VALUE} clauses and the remaining five are <strong>empty</strong> (practice B5 keeps the
     * slots; honesty keeps them empty).
     *
     * <p>Empty rather than zero-and-blanks. {@code CDEMO-ADMIN-OPTIONS REDEFINES
     * CDEMO-ADMIN-OPTIONS-DATA} declares {@value #TABLE_SIZE} entries over storage that values only
     * {@value #ACTIVE_OPTION_COUNT}, so slots {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through
     * {@value #TABLE_SIZE} lie beyond the end of the group being redefined and no {@code VALUE} clause
     * applies to them. What they hold at run time is not this copybook's to say, so this class does not
     * say it; a caller walking all nine slots has to decide what an absent one means, which is exactly
     * the decision the previous zero-and-blanks entry quietly made on its behalf.
     *
     * <p>The returned list is <strong>0-based</strong>, in the ordinary Java sense: element 0 is the
     * slot COBOL calls {@code CDEMO-ADMIN-OPT(1)}. Where a COBOL subscript is what you hold, use
     * {@link #optionBySubscript(int)} instead and never subtract one by hand - the two access styles are
     * named differently precisely so they cannot be confused (gate G33). For the
     * {@value #ACTIVE_OPTION_COUNT} slots that do carry values, {@link #activeOptions()} avoids the
     * question.
     *
     * <p>The list is unmodifiable: every mutator throws {@link UnsupportedOperationException}.
     *
     * @return the {@value #TABLE_SIZE} slots, of which the first {@value #ACTIVE_OPTION_COUNT} are
     *         present
     */
    public static List<Optional<AdminMenuOption>> options() {
        return OPTIONS;
    }

    /**
     * The {@code CDEMO-ADMIN-OPTIONS-DATA} view: the {@value #ACTIVE_OPTION_COUNT} entries the copybook
     * declares a {@code VALUE} for, with no empty slot to consider.
     *
     * @return an unmodifiable list of exactly {@value #ACTIVE_OPTION_COUNT} entries
     */
    public static List<AdminMenuOption> activeOptions() {
        return ACTIVE_OPTIONS;
    }

    /**
     * Whether the copybook determines the content of a slot.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return {@code true} for subscripts 1 to {@value #ACTIVE_OPTION_COUNT}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static boolean isSpecified(int cobolSubscript) {
        zeroBasedIndexFor(cobolSubscript);
        return cobolSubscript <= ACTIVE_OPTION_COUNT;
    }

    /**
     * The raw two-byte zero-filled display image of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4},
     * as the field is actually stored: {@code 04}.
     *
     * <p>The numeric value itself is {@link #ACTIVE_OPTION_COUNT}. This image exists for the same
     * reason {@link AdminMenuOption#adminOptNumImage()} does - a {@code PIC 9(02)} field is two zoned
     * display bytes, and its leading zero is part of the record.
     *
     * @return exactly {@value #OPT_COUNT_LENGTH} digits
     */
    public static String adminOptCountImage() {
        return picNineImage(ACTIVE_OPTION_COUNT, OPT_COUNT_LENGTH);
    }

    // =================================================================================================
    // Section 6 - 1-based COBOL subscript access (gate G33).
    //
    // COBOL OCCURS subscripts start at 1 and Java indices at 0. That one-element shift is the single
    // largest defect risk in this migration, so it is NEVER written inline here: both entry points below
    // delegate the conversion and the range rejection to FixedWidthRecord.occursElementOffsetOneBased,
    // the one method in the module that owns that convention and names it in its own signature.
    // =================================================================================================

    /**
     * Converts a 1-based COBOL {@code OCCURS} subscript into the 0-based Java index of the same entry,
     * rejecting anything outside 1..{@value #TABLE_SIZE}.
     *
     * <p>Subscript 1 maps to index 0 and subscript {@value #TABLE_SIZE} maps to index
     * {@value #TABLE_SIZE} - 1. The arithmetic is not repeated here: it is delegated to
     * {@link FixedWidthRecord#occursElementOffsetOneBased(int, int, int, int)} with a zero base and a
     * one-byte element, for which the offset that helper returns <em>is</em> the 0-based index. So the
     * module has exactly one implementation of the shift, and exactly one place where an out-of-range
     * subscript is rejected.
     *
     * <p>Out-of-range input is <strong>rejected, never clamped</strong>. The sole consumer indexes with
     * {@code WS-OPTION}, a {@code PIC 9(02)} value that can hold 0 to {@value #MAX_OPT_NUM}
     * ({@code app/cbl/COADM01C.cbl:138} and {@code :143}), and what an out-of-range subscript means is
     * the caller's decision, not this table's. Note in particular that COBOL has no subscript 0.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the corresponding 0-based index into {@link #options()}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static int zeroBasedIndexFor(int cobolSubscript) {
        return FixedWidthRecord.occursElementOffsetOneBased(0, 1, TABLE_SIZE, cobolSubscript);
    }

    /**
     * The entry a 1-based COBOL subscript addresses - the Java form of
     * {@code CDEMO-ADMIN-OPT(cobolSubscript)}.
     *
     * <p>Subscript 1 returns the first entry the copybook values. Subscripts
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE} return
     * <strong>empty</strong>: those slots exist and are addressable, exactly as the copybook leaves
     * them, and what the copybook leaves them is nothing.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the addressed entry, or empty for a slot the copybook gives no {@code VALUE}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static Optional<AdminMenuOption> optionBySubscript(int cobolSubscript) {
        return OPTIONS.get(zeroBasedIndexFor(cobolSubscript));
    }

    /**
     * Renders a copybook field name with a COBOL subscript, the way {@code app/cbl/COADM01C.cbl} spells
     * one and the way the {@code OCCURS} element spans of {@link #GROUP_LAYOUT} are named - for example
     * {@code CDEMO-ADMIN-OPT-NUM(1)}.
     *
     * <p>Use this to build a key for {@link #fieldImages(byte[], Charset)} or for
     * {@link RecordLayout#span(String)}, rather than concatenating at a call site where the format could
     * drift. The subscript is validated by {@link #zeroBasedIndexFor(int)}, so a name can never be built
     * for a slot the table does not have.
     *
     * @param fieldName      one of {@link #ADMIN_OPT_FIELD}, {@link #ADMIN_OPT_NUM_FIELD},
     *                       {@link #ADMIN_OPT_NAME_FIELD} or {@link #ADMIN_OPT_PGMNAME_FIELD}
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the subscripted name
     * @throws NullPointerException      if {@code fieldName} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static String subscriptedName(String fieldName, int cobolSubscript) {
        Objects.requireNonNull(fieldName, "A copybook field name is required to subscript it");
        zeroBasedIndexFor(cobolSubscript);
        return fieldName + SUBSCRIPT_OPEN + cobolSubscript + SUBSCRIPT_CLOSE;
    }

    // =================================================================================================
    // Section 7 - the descriptors, and the REDEFINES pair (gate G34).
    //
    // Two typed views over ONE backing span: CDEMO-ADMIN-OPTIONS-DATA sees the populated
    // POPULATED_DATA_LENGTH bytes and CDEMO-ADMIN-OPTIONS sees all TABLE_LENGTH of them, both starting
    // at OPTIONS_OFFSET, so a write through either is visible through the other.
    //
    // Field declaration ORDER here is load-bearing: OPTIONS_TABLE_STORAGE and the three span constants
    // are read while GROUP_LAYOUT initialises, and GROUP_LAYOUT reads OPTIONS from Section 5.
    // =================================================================================================

    /** Absolute 0-based offset of {@code CDEMO-ADMIN-OPT-COUNT}: the start of the group. */
    private static final int OPT_COUNT_OFFSET = 0;

    /**
     * The whole {@code CDEMO-ADMIN-OPT OCCURS 9} table as a single non-overlay span, used only as the
     * geometric basis the element descriptors are derived from.
     *
     * <p>Deliberately distinct from {@link #ADMIN_OPTIONS_SPAN}, which describes the same
     * {@value #TABLE_LENGTH} bytes as a {@code REDEFINES} overlay. The elements have to be derived from
     * a non-overlay basis because
     * {@link FixedWidthRecord#occursElementSpan(FieldSpan, int, int, String, PictureKind)} propagates
     * the table's overlay flag, and in the flattened layout the elements <em>are</em> the storage - see
     * the class documentation for why the copybook's storage and overlay roles are inverted at group
     * level.
     */
    private static final FieldSpan OPTIONS_TABLE_STORAGE =
            FieldSpan.alphanumeric(ADMIN_OPT_FIELD, OPTIONS_OFFSET, TABLE_LENGTH);

    /**
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} - the first {@value #OPT_COUNT_LENGTH} bytes of
     * the group, carrying its declared value so an initialised record already reads {@code 04}.
     *
     * <p>The copybook writes {@code VALUE 4}; COBOL right-aligns a numeric {@code VALUE} in its field
     * and zero-fills to the left, which stores {@code 04}. The declared literal below is that stored
     * image, so no alignment rule has to be re-derived when the span is read back.
     */
    public static final FieldSpan ADMIN_OPT_COUNT_SPAN =
            FieldSpan.unsignedNumeric(ADMIN_OPT_COUNT_FIELD, OPT_COUNT_OFFSET, OPT_COUNT_LENGTH)
                    .withInitialValue(adminOptCountImage());

    /**
     * {@code CDEMO-ADMIN-OPTIONS-DATA} - the literal-storage view of the option area:
     * {@value #POPULATED_DATA_LENGTH} bytes from offset {@value #OPTIONS_OFFSET}, that is the
     * {@value #ACTIVE_OPTION_COUNT} populated entries only.
     *
     * <p>The first of the two {@code REDEFINES} views. Read it with
     * {@link #adminOptionsDataImage(byte[], Charset)}.
     */
    public static final FieldSpan ADMIN_OPTIONS_DATA_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_DATA_FIELD, OPTIONS_OFFSET, POPULATED_DATA_LENGTH,
            PictureKind.ALPHANUMERIC);

    /**
     * {@code CDEMO-ADMIN-OPTIONS} - the {@code OCCURS} table view of the option area:
     * {@value #TABLE_LENGTH} bytes from offset {@value #OPTIONS_OFFSET}, that is all
     * {@value #TABLE_SIZE} slots.
     *
     * <p>The second of the two {@code REDEFINES} views, and the larger one. Read it with
     * {@link #adminOptionsImage(byte[], Charset)}. Because both views start at the same offset, the
     * literal-storage image is always exactly the leading {@value #POPULATED_DATA_LENGTH} characters of
     * this one.
     */
    public static final FieldSpan ADMIN_OPTIONS_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_FIELD, OPTIONS_OFFSET, TABLE_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * The unspecified tail as a descriptor: the bytes of slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}, which no {@code VALUE}
     * clause reaches. A {@code FILLER}-kind overlay carrying no initial value, exposed by
     * {@link #unspecifiedTailSpan()}.
     */
    private static final FieldSpan UNSPECIFIED_TAIL_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_FIELD + UNSPECIFIED_TAIL_SUFFIX, OPTIONS_OFFSET + POPULATED_DATA_LENGTH,
            TABLE_LENGTH - POPULATED_DATA_LENGTH, PictureKind.FILLER);

    /**
     * The complete flattened layout of {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS}: {@value #GROUP_LENGTH}
     * bytes, declared as {@link #ADMIN_OPT_COUNT_SPAN}, then the {@value #TABLE_SIZE} x 3 = 27
     * {@code OCCURS} elementary items in copybook order, then the two {@code REDEFINES} views.
     *
     * <p>{@link RecordLayout}'s own self-check runs when this constant initialises, so the widths and
     * offsets transcribed above are verified before this class can be used at all: a dropped span, an
     * overlapping span, a gap, a duplicated referable name, or a total that is not
     * {@value #GROUP_LENGTH} would fail class initialisation and name the offending descriptor. That is
     * the check gate G21 relies on - a missing pad byte cannot pass it unnoticed.
     *
     * <p>The first {@value #ACTIVE_OPTION_COUNT} entries' spans carry the {@code VALUE} literals the
     * copybook declares on the corresponding {@code FILLER} items of
     * {@code CDEMO-ADMIN-OPTIONS-DATA} - they are the same bytes under two names. Slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE} carry no literal, which is
     * how a layout records that the copybook determines nothing about those bytes. Building a record from
     * this layout then pads them - the {@code PIC 9} span with the zero character, the two {@code PIC X}
     * spans with spaces - and that padding is <strong>this module's own choice</strong>, made so its
     * output is reproducible. It is not a claim about what the legacy program's storage contains, and
     * {@link #unspecifiedTailSpan()} exists so a caller can read those bytes rather than trust them.
     *
     * <p>{@link RecordLayout} is an immutable record and {@link RecordLayout#spans()} returns an
     * unmodifiable list, so publishing this constant hands out no mutable state.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    /**
     * The descriptor of one whole {@code CDEMO-ADMIN-OPT} entry - {@value #ENTRY_LENGTH} bytes at the
     * offset a 1-based COBOL subscript addresses.
     *
     * <p>The element offset is computed by
     * {@link FixedWidthRecord#occursElementSpan(FieldSpan, int, int, String, PictureKind)}, so the
     * subscript shift is not repeated here (gate G33). That helper also proves the table width divides
     * exactly by {@value #TABLE_SIZE}, which it does: {@value #TABLE_LENGTH} / {@value #TABLE_SIZE} =
     * {@value #ENTRY_LENGTH}.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the entry's descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static FieldSpan optionSpanBySubscript(int cobolSubscript) {
        return FixedWidthRecord.occursElementSpan(OPTIONS_TABLE_STORAGE, TABLE_SIZE, cobolSubscript,
                subscriptedName(ADMIN_OPT_FIELD, cobolSubscript), PictureKind.ALPHANUMERIC);
    }

    /**
     * The descriptor of {@code CDEMO-ADMIN-OPT-NUM(cobolSubscript)} - the entry's first
     * {@value #OPT_NUM_LENGTH} bytes, an unsigned zoned display field.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static FieldSpan optNumSpanBySubscript(int cobolSubscript) {
        return FieldSpan.unsignedNumeric(subscriptedName(ADMIN_OPT_NUM_FIELD, cobolSubscript),
                optionSpanBySubscript(cobolSubscript).offset(), OPT_NUM_LENGTH);
    }

    /**
     * The descriptor of {@code CDEMO-ADMIN-OPT-NAME(cobolSubscript)} - {@value #OPT_NAME_LENGTH} bytes
     * of character data immediately after the option number.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static FieldSpan optNameSpanBySubscript(int cobolSubscript) {
        return FieldSpan.alphanumeric(subscriptedName(ADMIN_OPT_NAME_FIELD, cobolSubscript),
                optionSpanBySubscript(cobolSubscript).offset() + OPT_NUM_LENGTH, OPT_NAME_LENGTH);
    }

    /**
     * The descriptor of {@code CDEMO-ADMIN-OPT-PGMNAME(cobolSubscript)} - the entry's trailing
     * {@value #OPT_PGMNAME_LENGTH} bytes of character data.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
     *                                   {@value #TABLE_SIZE}
     */
    public static FieldSpan optPgmNameSpanBySubscript(int cobolSubscript) {
        return FieldSpan.alphanumeric(subscriptedName(ADMIN_OPT_PGMNAME_FIELD, cobolSubscript),
                optionSpanBySubscript(cobolSubscript).offset() + OPT_NUM_LENGTH + OPT_NAME_LENGTH,
                OPT_PGMNAME_LENGTH);
    }

    // =================================================================================================
    // Section 8 - the fixed-width byte image.
    //
    // The sole consumer reads this table from WORKING-STORAGE rather than from a dataset, so a byte
    // image is not needed to run the admin menu. It exists so the parity harness can diff this group
    // field by field, keyed by copybook field name, exactly as it does for the persisted record types.
    //
    // Every byte of it goes through FixedWidthCodec, and the code page is always an explicit parameter -
    // IBM037 for the EBCDIC datasets, US-ASCII for the text fixtures. Nothing below consults a platform
    // default, and this class never imports the configuration that resolves those code pages (practice
    // B8): a caller states the charset, or there is no byte image.
    // =================================================================================================

    /**
     * The {@value #GROUP_LENGTH}-byte image of {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS} as the copybook's
     * {@code VALUE} clauses leave it: {@code 04}, then the {@value #ACTIVE_OPTION_COUNT} entries the
     * copybook values.
     *
     * <p>Nothing is written for slots {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through
     * {@value #TABLE_SIZE}. Those bytes come back as whatever {@link #GROUP_LAYOUT} pads a span with no
     * declared literal with, which is this module's own choice of a reproducible image and
     * <strong>not</strong> a statement about what the legacy program's storage holds - the copybook
     * gives them no {@code VALUE} at all. A caller that needs those bytes reads them through
     * {@link #unspecifiedTailSpan()} and {@link #fieldImages(byte[], Charset)}.
     *
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@value #GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public static byte[] encode(Charset charset) {
        return encodeSlots(OPTIONS, charset);
    }

    /**
     * The {@value #GROUP_LENGTH}-byte image of the group with the supplied table in it.
     *
     * <p>Exactly {@value #TABLE_SIZE} entries are required - the empty slots included - because the
     * {@code OCCURS} table is a fixed-size area and a short list would leave part of it holding whatever
     * the layout initialised rather than what the caller meant. A {@code null} element is read as an
     * absent slot; {@link #encodeSlots(List, Charset)} is the form that says so explicitly and is what
     * {@link #encode(Charset)} itself uses.
     *
     * <p>{@code CDEMO-ADMIN-OPT-COUNT} is <strong>not</strong> derived from the list: it is a literal in
     * the copybook, so it is always emitted as its declared {@code 04}. That is why this method takes
     * only the table.
     *
     * @param options exactly {@value #TABLE_SIZE} entries, in COBOL declaration order and 0-based in
     *                the list sense
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@value #GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code options}, any of its elements, or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code options} does not hold exactly {@value #TABLE_SIZE}
     *                                  entries, or {@code charset} does not encode every digit, sign
     *                                  overpunch character and the space to exactly one byte
     */
    public static byte[] encode(List<AdminMenuOption> options, Charset charset) {
        Objects.requireNonNull(options, "The " + TABLE_SIZE + " entries of CDEMO-ADMIN-OPT are "
                + "required to encode the group");
        return encodeSlots(options.stream().map(Optional::ofNullable).toList(), charset);
    }

    /**
     * The {@value #GROUP_LENGTH}-byte image of the group with the supplied <em>slots</em> in it, an
     * absent slot contributing no bytes of its own.
     *
     * <p>This is the form {@link #options()} hands back and therefore the form
     * {@link #encode(Charset)} uses. An absent slot leaves its three spans holding whatever
     * {@link RecordLayout} initialised them with, which for a span carrying no copybook {@code VALUE} is
     * this module's own pad byte - determinism chosen here so the output is reproducible, and not a
     * claim about the legacy program's storage. {@link #unspecifiedTailSpan()} names those bytes for a
     * caller that needs to look at them rather than trust them.
     *
     * @param slots   exactly {@value #TABLE_SIZE} slots, in COBOL declaration order and 0-based in the
     *                list sense; an absent slot is {@link Optional#empty()}, never {@code null}
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@value #GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code slots}, any of its elements, or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code slots} does not hold exactly {@value #TABLE_SIZE}
     *                                  elements, or {@code charset} does not encode every digit, sign
     *                                  overpunch character and the space to exactly one byte
     */
    public static byte[] encodeSlots(List<Optional<AdminMenuOption>> slots, Charset charset) {
        Objects.requireNonNull(slots, "The " + TABLE_SIZE + " slots of CDEMO-ADMIN-OPT are required "
                + "to encode the group");
        if (slots.size() != TABLE_SIZE) {
            throw new IllegalArgumentException("CDEMO-ADMIN-OPT is declared OCCURS " + TABLE_SIZE
                    + " TIMES, a fixed-size area, so exactly " + TABLE_SIZE + " slots are required; "
                    + slots.size() + " were supplied. The " + (TABLE_SIZE - ACTIVE_OPTION_COUNT)
                    + " slots the copybook gives no VALUE are part of the table and are passed as "
                    + "absent slots, never omitted");
        }
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        Map<String, String> images = new LinkedHashMap<>();
        for (int subscript = 1; subscript <= TABLE_SIZE; subscript++) {
            Optional<AdminMenuOption> slot = Objects.requireNonNull(
                    slots.get(zeroBasedIndexFor(subscript)),
                    "CDEMO-ADMIN-OPT(" + subscript + ") is null; an absent slot is Optional.empty(), "
                            + "not a null element");
            if (slot.isEmpty()) {
                continue;
            }
            AdminMenuOption option = slot.get();
            images.put(subscriptedName(ADMIN_OPT_NUM_FIELD, subscript), option.adminOptNumImage());
            images.put(subscriptedName(ADMIN_OPT_NAME_FIELD, subscript), option.adminOptName());
            images.put(subscriptedName(ADMIN_OPT_PGMNAME_FIELD, subscript),
                    option.adminOptPgmName());
        }
        return codec.serialise(GROUP_LAYOUT, images);
    }

    /**
     * Decodes the {@code CDEMO-ADMIN-OPTIONS} table view of a group image into
     * {@value #TABLE_SIZE} entries.
     *
     * <p>This is the inverse of {@link #encode(List, Charset)}, and the round trip is lossless in both
     * directions: decoding an encoded table returns an equal table, and re-encoding a decoded table
     * returns identical bytes. Names come back at their full declared widths, untrimmed, and option
     * numbers come back from their raw two-byte display images.
     *
     * @param group   exactly {@value #GROUP_LENGTH} bytes, as {@link #encode(Charset)} produces
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return the {@value #TABLE_SIZE} entries, unmodifiable and in COBOL declaration order
     * @throws NullPointerException     if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@value #GROUP_LENGTH} bytes, if
     *                                  an option-number span does not hold digits - a non-digit there
     *                                  is a genuine data or offset defect rather than a value - or if
     *                                  {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public static List<Optional<AdminMenuOption>> decode(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.wrap(group, GROUP_LAYOUT);
        List<Optional<AdminMenuOption>> decoded = new ArrayList<>(TABLE_SIZE);
        for (int subscript = 1; subscript <= TABLE_SIZE; subscript++) {
            if (!isSpecified(subscript)) {
                // Read as BYTES, never as an entry, for two independent reasons. Nothing decoded there
                // can be attributed to the copybook's table, because the copybook assigns it nothing;
                // and real storage may hold anything, including bytes that are not digits, which
                // readPic9AsInt would reject - so decoding them as an entry would fail a decode of a
                // perfectly valid group image. The bytes stay available through
                // fieldImages(byte[], Charset) and unspecifiedTailSpan().
                decoded.add(Optional.empty());
                continue;
            }
            decoded.add(Optional.of(new AdminMenuOption(
                    codec.readPic9AsInt(record, optNumSpanBySubscript(subscript)),
                    codec.readPicX(record, optNameSpanBySubscript(subscript)),
                    codec.readPicX(record, optPgmNameSpanBySubscript(subscript)))));
        }
        return List.copyOf(decoded);
    }

    /**
     * The span of the table the copybook determines nothing about: slots
     * {@value #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}, as opaque storage.
     *
     * <p>The {@code CDEMO-ADMIN-OPTIONS} overlay is {@value #TABLE_LENGTH} bytes over the
     * {@value #POPULATED_DATA_LENGTH} bytes of {@code CDEMO-ADMIN-OPTIONS-DATA} it redefines, and this
     * descriptor names the difference. Its {@code PICTURE} kind is {@code FILLER} and it carries no
     * initial value, which is the honest shape for storage the copybook neither values nor accounts for.
     * It exists so that a caller wanting to inspect or compare those bytes - a parity case exercising an
     * out-of-range option subscript, for instance - reads the real bytes rather than trusting a
     * synthesized entry.
     *
     * @return the descriptor for the unspecified tail
     */
    public static FieldSpan unspecifiedTailSpan() {
        return UNSPECIFIED_TAIL_SPAN;
    }

    /**
     * The {@code CDEMO-ADMIN-OPTIONS-DATA} view of a group image - the first of the two
     * {@code REDEFINES} views, {@value #POPULATED_DATA_LENGTH} characters covering the
     * {@value #ACTIVE_OPTION_COUNT} populated entries.
     *
     * @param group   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return exactly {@value #POPULATED_DATA_LENGTH} characters, untrimmed
     * @throws NullPointerException     if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@value #GROUP_LENGTH} bytes, or
     *                                  {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public static String adminOptionsDataImage(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPicX(codec.wrap(group, GROUP_LAYOUT), ADMIN_OPTIONS_DATA_SPAN);
    }

    /**
     * The {@code CDEMO-ADMIN-OPTIONS} view of a group image - the second of the two {@code REDEFINES}
     * views, {@value #TABLE_LENGTH} characters covering all {@value #TABLE_SIZE} slots.
     *
     * <p>Both views address the same bytes of the same record, so the result of
     * {@link #adminOptionsDataImage(byte[], Charset)} on the same group is always exactly the leading
     * {@value #POPULATED_DATA_LENGTH} characters of this one.
     *
     * @param group   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return exactly {@value #TABLE_LENGTH} characters, untrimmed
     * @throws NullPointerException     if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@value #GROUP_LENGTH} bytes, or
     *                                  {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public static String adminOptionsImage(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPicX(codec.wrap(group, GROUP_LAYOUT), ADMIN_OPTIONS_SPAN);
    }

    /**
     * Every named field of a group image as its raw stored characters, keyed by copybook field name and
     * in copybook declaration order - the input the parity differ compares field by field.
     *
     * <p>The keys are {@link #ADMIN_OPT_COUNT_FIELD}, then the 27 subscripted element names built by
     * {@link #subscriptedName(String, int)}, then {@link #ADMIN_OPTIONS_DATA_FIELD} and
     * {@link #ADMIN_OPTIONS_FIELD} for the two {@code REDEFINES} views. Images are untrimmed, with
     * leading zeros intact, because those bytes are exactly what a field-for-field diff has to compare.
     *
     * @param group   exactly {@value #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an unmodifiable, insertion-ordered map from field name to raw image
     * @throws NullPointerException     if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@value #GROUP_LENGTH} bytes, or
     *                                  {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte
     */
    public static Map<String, String> fieldImages(byte[] group, Charset charset) {
        return Collections.unmodifiableMap(
                new FixedWidthCodec(charset).deserialise(GROUP_LAYOUT, group));
    }

    // =================================================================================================
    // Section 9 - private construction helpers.
    //
    // The two image helpers below are the character-level form of FixedWidthCodec.movePicX and
    // FixedWidthCodec.movePic9, and they state the direction each PICTURE truncates in. They exist
    // rather than delegating because FixedWidthCodec requires a code page at construction and this table
    // is a compile-time table of CHARACTERS, which has none: manufacturing an arbitrary charset just to
    // reach two charset-independent String operations would be exactly the implicit default that
    // practice B8 forbids. Their agreement with the codec is asserted by test, and every BYTE-level
    // operation in Section 8 goes through the codec itself.
    // =================================================================================================

    /**
     * The COBOL {@code PIC X} move rule, at character level: pad on the right with spaces when short,
     * and truncate on the <strong>right</strong> when over-wide, because a {@code PIC X} receiver is
     * filled from its leftmost position and the overflow is discarded.
     *
     * <p>The pad run is produced from {@code declaredLength}, so no literal run of spaces is typed
     * anywhere in this class and no space can be miscounted (practice B8).
     */
    private static String picXImage(String value, int declaredLength) {
        if (value.length() >= declaredLength) {
            return value.substring(0, declaredLength);
        }
        return value + PIC_X_PAD.repeat(declaredLength - value.length());
    }

    /**
     * The COBOL {@code PIC 9} move rule, at character level: zero-fill on the left when short, and
     * truncate on the <strong>left</strong> when over-wide, because a numeric receiver aligns on its
     * implied decimal point and keeps the low-order digits.
     *
     * <p>Called only with values their caller has already checked against the field's picture - 0 to
     * {@value #MAX_OPT_NUM} for an option number, and the compile-time
     * {@value #ACTIVE_OPTION_COUNT} for the count - so the left-truncating path is the equal-width case
     * for a two-digit value rather than a real loss of digits.
     */
    private static String picNineImage(int value, int declaredLength) {
        String digits = Integer.toString(value);
        if (digits.length() >= declaredLength) {
            return digits.substring(digits.length() - declaredLength);
        }
        return PIC_9_PAD.repeat(declaredLength - digits.length()) + digits;
    }

    /**
     * Builds the {@value #TABLE_SIZE}-entry table: the {@value #ACTIVE_OPTION_COUNT} entries the
     * copybook values, followed by the five slots it leaves without any value at all.
     *
     * <p>The option numbers 1 to {@value #ACTIVE_OPTION_COUNT} are the copybook's own
     * {@code FILLER PIC 9(02) VALUE n} literals at {@code COADM02Y.cpy:24}, {@code :29}, {@code :34}
     * and {@code :39}. The five empty slots exist because
     * {@code CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA} declares more entries than the
     * redefined storage holds values for; they are preserved as slots, never trimmed away (practice
     * B5), and left absent rather than given a fabricated value.
     */
    private static List<Optional<AdminMenuOption>> buildOptions() {
        List<Optional<AdminMenuOption>> table = new ArrayList<>(TABLE_SIZE);
        table.add(Optional.of(AdminMenuOption.of(1, OPTION_1_TEXT, OPTION_1_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(2, OPTION_2_TEXT, OPTION_2_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(3, OPTION_3_TEXT, OPTION_3_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(4, OPTION_4_TEXT, OPTION_4_PGMNAME)));
        for (int subscript = SPECIFIED_OPTION_COUNT_PLUS_ONE; subscript <= TABLE_SIZE; subscript++) {
            // Absent, not empty-valued. These slots lie past the end of CDEMO-ADMIN-OPTIONS-DATA, so
            // no VALUE clause applies to them and their run-time content is undetermined. The entry
            // this used to add - option number 0 with blank names - was indistinguishable from data.
            table.add(Optional.empty());
        }
        return List.copyOf(table);
    }

    /**
     * Builds {@link #GROUP_LAYOUT}: the count span, then the 27 {@code OCCURS} elementary spans in
     * copybook order, then the two {@code REDEFINES} views, which must come last because
     * {@link RecordLayout} requires an overlay to fall inside storage already declared ahead of it.
     *
     * <p>The first {@value #ACTIVE_OPTION_COUNT} entries' spans carry the {@code VALUE} literals from
     * {@code CDEMO-ADMIN-OPTIONS-DATA}; the rest carry none, which is how a layout says the copybook
     * determines nothing about those bytes. They then initialise to this module's own pad character for
     * their {@code PICTURE} - reproducibility chosen here, not a property of the legacy storage.
     */
    private static RecordLayout buildGroupLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(ADMIN_OPT_COUNT_SPAN);
        for (int subscript = 1; subscript <= TABLE_SIZE; subscript++) {
            FieldSpan num = optNumSpanBySubscript(subscript);
            FieldSpan name = optNameSpanBySubscript(subscript);
            FieldSpan pgmName = optPgmNameSpanBySubscript(subscript);
            if (subscript <= ACTIVE_OPTION_COUNT) {
                AdminMenuOption declared = ACTIVE_OPTIONS.get(zeroBasedIndexFor(subscript));
                spans.add(num.withInitialValue(declared.adminOptNumImage()));
                spans.add(name.withInitialValue(declared.adminOptName()));
                spans.add(pgmName.withInitialValue(declared.adminOptPgmName()));
            } else {
                // No initial value: "the copybook says nothing about these bytes", expressed in the
                // layout itself rather than as a comment. Whatever newRecord later pads them with is
                // this module's determinism, not a statement about the legacy program's storage.
                spans.add(num);
                spans.add(name);
                spans.add(pgmName);
            }
        }
        spans.add(ADMIN_OPTIONS_DATA_SPAN);
        spans.add(ADMIN_OPTIONS_SPAN);
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    /**
     * Not instantiable: this class holds only the copybook's constants and its immutable table, and has
     * no instance state of any kind.
     *
     * @throws AssertionError always, if reflection is used to invoke it
     */
    private AdminMenuOptions() {
        throw new AssertionError("AdminMenuOptions is the constant table of app/cpy/COADM02Y.cpy and "
                + "must not be instantiated");
    }
}
