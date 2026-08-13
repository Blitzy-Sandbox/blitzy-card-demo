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
 * The single Java type for COBOL copybook {@code app/cpy/COADM02Y.cpy} - the administrator menu option
 * table of the CardDemo admin transaction {@code CA00}.
 */
public final class AdminMenuOptions {
    /**
     * {@code CDEMO-ADMIN-OPT-COUNT} - the active option count.
     */
    public static final String ADMIN_OPT_COUNT_FIELD = "CDEMO-ADMIN-OPT-COUNT";

    /**
     * {@code CDEMO-ADMIN-OPTIONS-DATA} - the literal-storage group holding only the populated entries.
     */
    public static final String ADMIN_OPTIONS_DATA_FIELD = "CDEMO-ADMIN-OPTIONS-DATA";

    /**
     * {@code CDEMO-ADMIN-OPTIONS} - the group that redefines {@code CDEMO-ADMIN-OPTIONS-DATA} as an
     * {@code OCCURS} table.
     */
    public static final String ADMIN_OPTIONS_FIELD = "CDEMO-ADMIN-OPTIONS";

    /**
     * The suffix naming the unspecified tail descriptor: {@link #UNSPECIFIED_TAIL_SUFFIX}.
     */
    public static final String UNSPECIFIED_TAIL_SUFFIX = "-UNSPECIFIED-TAIL";

    /**
     * {@code CDEMO-ADMIN-OPT} - one entry of the {@code OCCURS 9} table.
     */
    public static final String ADMIN_OPT_FIELD = "CDEMO-ADMIN-OPT";

    /**
     * {@code CDEMO-ADMIN-OPT-NUM} - an entry's option number, {@code PIC 9(02)}.
     */
    public static final String ADMIN_OPT_NUM_FIELD = "CDEMO-ADMIN-OPT-NUM";

    /**
     * {@code CDEMO-ADMIN-OPT-NAME} - an entry's display name, {@code PIC X(35)}.
     */
    public static final String ADMIN_OPT_NAME_FIELD = "CDEMO-ADMIN-OPT-NAME";

    /**
     * {@code CDEMO-ADMIN-OPT-PGMNAME} - an entry's target program name, {@code PIC X(08)}.
     */
    public static final String ADMIN_OPT_PGMNAME_FIELD = "CDEMO-ADMIN-OPT-PGMNAME";

    /**
     * Declared width of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02)}: 2 bytes.
     */
    public static final int OPT_COUNT_LENGTH = 2;

    /**
     * Declared width of {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}: 2 bytes.
     */
    public static final int OPT_NUM_LENGTH = 2;

    /**
     * Declared width of {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}: 35 bytes.
     */
    public static final int OPT_NAME_LENGTH = 35;

    /**
     * Declared width of {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}: 8 bytes.
     */
    public static final int OPT_PGMNAME_LENGTH = 8;

    /**
     * Width of one {@code CDEMO-ADMIN-OPT} entry: {@value #OPT_NUM_LENGTH} + {@value #OPT_NAME_LENGTH} +
     * {@value #OPT_PGMNAME_LENGTH} = {@link #ENTRY_LENGTH} bytes.
     */
    public static final int ENTRY_LENGTH = OPT_NUM_LENGTH + OPT_NAME_LENGTH + OPT_PGMNAME_LENGTH;

    /**
     * The {@code OCCURS} count of {@code CDEMO-ADMIN-OPT}: {@value #TABLE_SIZE} entries.
     */
    public static final int TABLE_SIZE = 9;

    /**
     * The value of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}: {@value #ACTIVE_OPTION_COUNT}.
     */
    public static final int ACTIVE_OPTION_COUNT = 4;

    /**
     * The first subscript the copybook declares no {@code VALUE} for:
     * {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE}.
     */
    public static final int SPECIFIED_OPTION_COUNT_PLUS_ONE = ACTIVE_OPTION_COUNT + 1;

    /**
     * Width of {@code CDEMO-ADMIN-OPTIONS-DATA}, the literal-storage view: {@value #ACTIVE_OPTION_COUNT} x
     * {@link #ENTRY_LENGTH} = {@link #POPULATED_DATA_LENGTH} bytes.
     */
    public static final int POPULATED_DATA_LENGTH = ACTIVE_OPTION_COUNT * ENTRY_LENGTH;

    /**
     * Width of {@code CDEMO-ADMIN-OPTIONS}, the {@code OCCURS} table view: {@value #TABLE_SIZE} x
     * {@link #ENTRY_LENGTH} = {@link #TABLE_LENGTH} bytes.
     */
    public static final int TABLE_LENGTH = TABLE_SIZE * ENTRY_LENGTH;

    /**
     * Width of the whole {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS} group as it is physically laid out:
     * {@value #OPT_COUNT_LENGTH} + {@link #TABLE_LENGTH} = {@link #GROUP_LENGTH} bytes.
     */
    public static final int GROUP_LENGTH = OPT_COUNT_LENGTH + TABLE_LENGTH;

    /**
     * Absolute 0-based byte offset of the first {@code OCCURS} entry within the group: immediately after
     * {@code CDEMO-ADMIN-OPT-COUNT}, so {@link #OPTIONS_OFFSET}.
     */
    public static final int OPTIONS_OFFSET = OPT_COUNT_LENGTH;

    /**
     * The highest value {@code PIC 9(02)} can hold, and therefore the inclusive upper bound this class
     * accepts for an option number: 99.
     */
    public static final int MAX_OPT_NUM = 99;

    // Section 3 - the four populated entries, exactly as the copybook declares them.

    private static final String OPTION_1_TEXT = "User List (Security)";

    private static final String OPTION_2_TEXT = "User Add (Security)";

    private static final String OPTION_3_TEXT = "User Update (Security)";

    private static final String OPTION_4_TEXT = "User Delete (Security)";

    private static final String OPTION_1_PGMNAME = "COUSR00C";

    private static final String OPTION_2_PGMNAME = "COUSR01C";

    private static final String OPTION_3_PGMNAME = "COUSR02C";

    private static final String OPTION_4_PGMNAME = "COUSR03C";

    private static final String PIC_X_PAD = " ";

    private static final String PIC_9_PAD = "0";

    private static final String SUBSCRIPT_OPEN = "(";

    private static final String SUBSCRIPT_CLOSE = ")";

    /**
     * One {@code CDEMO-ADMIN-OPT} entry - the three elementary items of {@code app/cpy/COADM02Y.cpy:46-48},
     * and nothing else.
     *
     * @param adminOptNum {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} - the option number as a scale-free
     *     {@code int}, because the picture declares no decimal position
     * @param adminOptName {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} - the display name, exactly
     *     {@value AdminMenuOptions#OPT_NAME_LENGTH} characters, right-space-padded and never trimmed
     * @param adminOptPgmName {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} - the target program name, exactly
     *     {@value AdminMenuOptions#OPT_PGMNAME_LENGTH} characters, right-space-padded and never trimmed
     */
    public record AdminMenuOption(int adminOptNum, String adminOptName, String adminOptPgmName) {
        /**
         * Validates the entry against the copybook's declared widths and picture range, so a transcription
         * error is caught where the entry is built rather than where a byte later reads back wrong.
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
         * @param adminOptNum {@code CDEMO-ADMIN-OPT-NUM}, from 0 to {@value AdminMenuOptions#MAX_OPT_NUM}
         *     inclusive
         * @param optionText the display name, padded or truncated here to
         *     {@value AdminMenuOptions#OPT_NAME_LENGTH} characters
         * @param programName the target program name, padded or truncated here to
         *     {@value AdminMenuOptions#OPT_PGMNAME_LENGTH} characters
         * @return the entry, with both names at their full declared widths
         * @throws NullPointerException if {@code optionText} or {@code programName} is {@code null}
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
         * <p>{@code app/cbl/COADM01C.cbl:233} moves this image with
         * {@code STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE}, so the leading zero is part of the
         * observable output; an {@code int} alone cannot reproduce it.
         *
         * @return exactly {@value AdminMenuOptions#OPT_NUM_LENGTH} digits
         */
        public String adminOptNumImage() {
            return picNineImage(adminOptNum, OPT_NUM_LENGTH);
        }
    }

    private static final List<Optional<AdminMenuOption>> OPTIONS = buildOptions();

    private static final List<AdminMenuOption> ACTIVE_OPTIONS =
            OPTIONS.subList(0, ACTIVE_OPTION_COUNT).stream()
                    .map(slot -> slot.orElseThrow(() -> new IllegalStateException(
                            "Each of the first " + ACTIVE_OPTION_COUNT + " slots of "
                                    + ADMIN_OPTIONS_FIELD + " carries a copybook VALUE, so none may be "
                                    + "absent; this class is mis-transcribed if one is")))
                    .toList();

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
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static boolean isSpecified(int cobolSubscript) {
        zeroBasedIndexFor(cobolSubscript);
        return cobolSubscript <= ACTIVE_OPTION_COUNT;
    }

    /**
     * The raw two-byte zero-filled display image of {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}, as the
     * field is actually stored: {@code 04}.
     *
     * @return exactly {@value #OPT_COUNT_LENGTH} digits
     */
    public static String adminOptCountImage() {
        return picNineImage(ACTIVE_OPTION_COUNT, OPT_COUNT_LENGTH);
    }

    /**
     * Converts a 1-based COBOL {@code OCCURS} subscript into the 0-based Java index of the same entry,
     * rejecting anything outside 1..{@value #TABLE_SIZE}.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the corresponding 0-based index into {@link #options()}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static int zeroBasedIndexFor(int cobolSubscript) {
        return FixedWidthRecord.occursElementOffsetOneBased(0, 1, TABLE_SIZE, cobolSubscript);
    }

    /**
     * The entry a 1-based COBOL subscript addresses - the Java form of
     * {@code CDEMO-ADMIN-OPT(cobolSubscript)}.
     *
     * <p>Subscripts {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE} return empty:
     * those slots exist and are addressable, exactly as the copybook leaves them, and what the copybook
     * leaves them is nothing.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the addressed entry, or empty for a slot the copybook gives no {@code VALUE}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static Optional<AdminMenuOption> optionBySubscript(int cobolSubscript) {
        return OPTIONS.get(zeroBasedIndexFor(cobolSubscript));
    }

    /**
     * Renders a copybook field name with a COBOL subscript, the way {@code app/cbl/COADM01C.cbl} spells one
     * and the way the {@code OCCURS} element spans of {@link #GROUP_LAYOUT} are named - for example
     * {@code CDEMO-ADMIN-OPT-NUM(1)}.
     *
     * @param fieldName one of {@link #ADMIN_OPT_FIELD}, {@link #ADMIN_OPT_NUM_FIELD},
     *     {@link #ADMIN_OPT_NAME_FIELD} or {@link #ADMIN_OPT_PGMNAME_FIELD}
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the subscripted name
     * @throws NullPointerException if {@code fieldName} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static String subscriptedName(String fieldName, int cobolSubscript) {
        Objects.requireNonNull(fieldName, "A copybook field name is required to subscript it");
        zeroBasedIndexFor(cobolSubscript);
        return fieldName + SUBSCRIPT_OPEN + cobolSubscript + SUBSCRIPT_CLOSE;
    }

    private static final int OPT_COUNT_OFFSET = 0;

    private static final FieldSpan OPTIONS_TABLE_STORAGE =
            FieldSpan.alphanumeric(ADMIN_OPT_FIELD, OPTIONS_OFFSET, TABLE_LENGTH);

    /**
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} - the first {@value #OPT_COUNT_LENGTH} bytes of the
     * group, carrying its declared value so an initialised record already reads {@code 04}.
     */
    public static final FieldSpan ADMIN_OPT_COUNT_SPAN =
            FieldSpan.unsignedNumeric(ADMIN_OPT_COUNT_FIELD, OPT_COUNT_OFFSET, OPT_COUNT_LENGTH)
                    .withInitialValue(adminOptCountImage());

    /**
     * {@code CDEMO-ADMIN-OPTIONS-DATA} - the literal-storage view of the option area:
     * {@link #POPULATED_DATA_LENGTH} bytes from offset {@link #OPTIONS_OFFSET}, that is the
     * {@value #ACTIVE_OPTION_COUNT} populated entries only.
     */
    public static final FieldSpan ADMIN_OPTIONS_DATA_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_DATA_FIELD, OPTIONS_OFFSET, POPULATED_DATA_LENGTH,
            PictureKind.ALPHANUMERIC);

    /**
     * {@code CDEMO-ADMIN-OPTIONS} - the {@code OCCURS} table view of the option area: {@link #TABLE_LENGTH}
     * bytes from offset {@link #OPTIONS_OFFSET}, that is all {@value #TABLE_SIZE} slots.
     */
    public static final FieldSpan ADMIN_OPTIONS_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_FIELD, OPTIONS_OFFSET, TABLE_LENGTH, PictureKind.ALPHANUMERIC);

    private static final FieldSpan UNSPECIFIED_TAIL_SPAN = FieldSpan.redefining(
            ADMIN_OPTIONS_FIELD + UNSPECIFIED_TAIL_SUFFIX, OPTIONS_OFFSET + POPULATED_DATA_LENGTH,
            TABLE_LENGTH - POPULATED_DATA_LENGTH, PictureKind.FILLER);

    /**
     * The complete flattened layout of {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS}: {@link #GROUP_LENGTH} bytes,
     * declared as {@link #ADMIN_OPT_COUNT_SPAN}, then the {@value #TABLE_SIZE} x 3 = 27 {@code OCCURS}
     * elementary items in copybook order, then the two {@code REDEFINES} views.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    /**
     * The descriptor of one whole {@code CDEMO-ADMIN-OPT} entry - {@link #ENTRY_LENGTH} bytes at the offset
     * a 1-based COBOL subscript addresses.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the entry's descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
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
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static FieldSpan optNumSpanBySubscript(int cobolSubscript) {
        return FieldSpan.unsignedNumeric(subscriptedName(ADMIN_OPT_NUM_FIELD, cobolSubscript),
                optionSpanBySubscript(cobolSubscript).offset(), OPT_NUM_LENGTH);
    }

    /**
     * The descriptor of {@code CDEMO-ADMIN-OPT-NAME(cobolSubscript)} - {@value #OPT_NAME_LENGTH} bytes of
     * character data immediately after the option number.
     *
     * @param cobolSubscript the COBOL subscript, from 1 to {@value #TABLE_SIZE} inclusive
     * @return the descriptor
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
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
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above {@value #TABLE_SIZE}
     */
    public static FieldSpan optPgmNameSpanBySubscript(int cobolSubscript) {
        return FieldSpan.alphanumeric(subscriptedName(ADMIN_OPT_PGMNAME_FIELD, cobolSubscript),
                optionSpanBySubscript(cobolSubscript).offset() + OPT_NUM_LENGTH + OPT_NAME_LENGTH,
                OPT_PGMNAME_LENGTH);
    }

    // It exists so the parity harness can diff this group field by field, keyed by copybook field name,
    // exactly as it does for the persisted record types. Every byte of it goes through FixedWidthCodec, and
    // the code page is always an explicit parameter - IBM037 for the EBCDIC datasets, US-ASCII for the text
    // fixtures.

    /**
     * The {@link #GROUP_LENGTH}-byte image of {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS} as the copybook's
     * {@code VALUE} clauses leave it: {@code 04}, then the {@value #ACTIVE_OPTION_COUNT} entries the
     * copybook values.
     *
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@link #GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *     character and the space to exactly one byte
     */
    public static byte[] encode(Charset charset) {
        return encodeSlots(OPTIONS, charset);
    }

    /**
     * The {@link #GROUP_LENGTH}-byte image of the group with the supplied table in it.
     *
     * <p>{@code CDEMO-ADMIN-OPT-COUNT} is not derived from the list: it is a literal in the copybook, so it
     * is always emitted as its declared {@code 04}.
     *
     * @param options exactly {@value #TABLE_SIZE} entries, in COBOL declaration order and 0-based in the
     *     list sense
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@link #GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code options}, any of its elements, or {@code charset} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code options} does not hold exactly {@value #TABLE_SIZE}
     *     entries, or {@code charset} does not encode every digit, sign overpunch character and the space to
     *     exactly one byte
     */
    public static byte[] encode(List<AdminMenuOption> options, Charset charset) {
        Objects.requireNonNull(options, "The " + TABLE_SIZE + " entries of CDEMO-ADMIN-OPT are "
                + "required to encode the group");
        return encodeSlots(options.stream().map(Optional::ofNullable).toList(), charset);
    }

    /**
     * The {@link #GROUP_LENGTH}-byte image of the group with the supplied slots in it, an absent slot
     * contributing no bytes of its own.
     *
     * @param slots exactly {@value #TABLE_SIZE} slots, in COBOL declaration order and 0-based in the list
     *     sense; an absent slot is {@link Optional#empty()}, never {@code null}
     * @param charset the code page to encode in, named explicitly by the caller
     * @return exactly {@link #GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code slots}, any of its elements, or {@code charset} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code slots} does not hold exactly {@value #TABLE_SIZE}
     *     elements, or {@code charset} does not encode every digit, sign overpunch character and the space to
     *     exactly one byte
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
     * Decodes the {@code CDEMO-ADMIN-OPTIONS} table view of a group image into {@value #TABLE_SIZE}
     * entries.
     *
     * @param group exactly {@link #GROUP_LENGTH} bytes, as {@link #encode(Charset)} produces
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return the {@value #TABLE_SIZE} entries, unmodifiable and in COBOL declaration order
     * @throws NullPointerException if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@link #GROUP_LENGTH} bytes, if an
     *     option-number span does not hold digits - a non-digit there is a genuine data or offset defect rather
     *     than a value
     */
    public static List<Optional<AdminMenuOption>> decode(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.wrap(group, GROUP_LAYOUT);
        List<Optional<AdminMenuOption>> decoded = new ArrayList<>(TABLE_SIZE);
        for (int subscript = 1; subscript <= TABLE_SIZE; subscript++) {
            if (!isSpecified(subscript)) {
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
     * {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}, as opaque storage.
     *
     * @return the descriptor for the unspecified tail
     */
    public static FieldSpan unspecifiedTailSpan() {
        return UNSPECIFIED_TAIL_SPAN;
    }

    /**
     * The {@code CDEMO-ADMIN-OPTIONS-DATA} view of a group image - the first of the two {@code REDEFINES}
     * views, {@link #POPULATED_DATA_LENGTH} characters covering the {@value #ACTIVE_OPTION_COUNT} populated
     * entries.
     *
     * @param group exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return exactly {@link #POPULATED_DATA_LENGTH} characters, untrimmed
     * @throws NullPointerException if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     {@code charset} does not encode every digit, sign overpunch character and the space to exactly one
     *     byte
     */
    public static String adminOptionsDataImage(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPicX(codec.wrap(group, GROUP_LAYOUT), ADMIN_OPTIONS_DATA_SPAN);
    }

    /**
     * The {@code CDEMO-ADMIN-OPTIONS} view of a group image - the second of the two {@code REDEFINES}
     * views, {@link #TABLE_LENGTH} characters covering all {@value #TABLE_SIZE} slots.
     *
     * @param group exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return exactly {@link #TABLE_LENGTH} characters, untrimmed
     * @throws NullPointerException if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     {@code charset} does not encode every digit, sign overpunch character and the space to exactly one
     *     byte
     */
    public static String adminOptionsImage(byte[] group, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPicX(codec.wrap(group, GROUP_LAYOUT), ADMIN_OPTIONS_SPAN);
    }

    /**
     * Every named field of a group image as its raw stored characters, keyed by copybook field name and in
     * copybook declaration order - the input the parity differ compares field by field.
     *
     * @param group exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an unmodifiable, insertion-ordered map from field name to raw image
     * @throws NullPointerException if {@code group} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code group} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     {@code charset} does not encode every digit, sign overpunch character and the space to exactly one
     *     byte
     */
    public static Map<String, String> fieldImages(byte[] group, Charset charset) {
        return Collections.unmodifiableMap(
                new FixedWidthCodec(charset).deserialise(GROUP_LAYOUT, group));
    }

    // The two image helpers below are the character-level form of FixedWidthCodec.movePicX and
    // FixedWidthCodec.movePic9, and they state the direction each PICTURE truncates in.

    /**
     * The COBOL {@code PIC X} move rule, at character level: pad on the right with spaces when short, and
     * truncate on the right when over-wide, because a {@code PIC X} receiver is filled from its leftmost
     * position and the overflow is discarded.
     *
     * @param value the value being stored
     * @param declaredLength the length the copybook's {@code PICTURE} declares
     */
    private static String picXImage(String value, int declaredLength) {
        if (value.length() >= declaredLength) {
            return value.substring(0, declaredLength);
        }
        return value + PIC_X_PAD.repeat(declaredLength - value.length());
    }

    /**
     * The COBOL {@code PIC 9} move rule, at character level: zero-fill on the left when short, and truncate
     * on the left when over-wide, because a numeric receiver aligns on its implied decimal point and keeps
     * the low-order digits.
     *
     * @param value the value being stored
     * @param declaredLength the length the copybook's {@code PICTURE} declares
     */
    private static String picNineImage(int value, int declaredLength) {
        String digits = Integer.toString(value);
        if (digits.length() >= declaredLength) {
            return digits.substring(digits.length() - declaredLength);
        }
        return PIC_9_PAD.repeat(declaredLength - digits.length()) + digits;
    }

    private static List<Optional<AdminMenuOption>> buildOptions() {
        List<Optional<AdminMenuOption>> table = new ArrayList<>(TABLE_SIZE);
        table.add(Optional.of(AdminMenuOption.of(1, OPTION_1_TEXT, OPTION_1_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(2, OPTION_2_TEXT, OPTION_2_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(3, OPTION_3_TEXT, OPTION_3_PGMNAME)));
        table.add(Optional.of(AdminMenuOption.of(4, OPTION_4_TEXT, OPTION_4_PGMNAME)));
        for (int subscript = SPECIFIED_OPTION_COUNT_PLUS_ONE; subscript <= TABLE_SIZE; subscript++) {
            table.add(Optional.empty());
        }
        return List.copyOf(table);
    }

    /**
     * Builds {@link #GROUP_LAYOUT}: the count span, then the 27 {@code OCCURS} elementary spans in copybook
     * order, then the two {@code REDEFINES} views, which must come last because {@link RecordLayout}
     * requires an overlay to fall inside storage already declared ahead of it.
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
                spans.add(num);
                spans.add(name);
                spans.add(pgmName);
            }
        }
        spans.add(ADMIN_OPTIONS_DATA_SPAN);
        spans.add(ADMIN_OPTIONS_SPAN);
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    private AdminMenuOptions() {
        throw new AssertionError("AdminMenuOptions is the constant table of app/cpy/COADM02Y.cpy and "
                + "must not be instantiated");
    }
}
