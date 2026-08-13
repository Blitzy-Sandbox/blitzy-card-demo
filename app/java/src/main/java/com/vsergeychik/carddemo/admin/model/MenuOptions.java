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
 * The main-menu option table of the COBOL copybook {@code app/cpy/COMEN02Y.cpy}, transcribed byte-for-byte:
 * {@code 01 CARDDEMO-MAIN-MENU-OPTIONS}, its {@code CDEMO-MENU-OPT-COUNT} field, the ten literal entries of
 * {@code CDEMO-MENU-OPTIONS-DATA}, and the twelve-element {@code CDEMO-MENU-OPT OCCURS 12 TIMES} table that
 * redefines them.
 *
 * <p>{@link MenuOption#menuOptName()}, {@link MenuOption#menuOptPgmName()} and
 * {@link MenuOption#menuOptUsrType()} return their full declared widths, right-space-padded, exactly as the
 * copybook stores them.
 */
public final class MenuOptions {
    /**
     * {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} - the whole group, {@code app/cpy/COMEN02Y.cpy:19}.
     */
    public static final String CARDDEMO_MAIN_MENU_OPTIONS = "CARDDEMO-MAIN-MENU-OPTIONS";

    /**
     * {@code 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} - {@code app/cpy/COMEN02Y.cpy:21}.
     */
    public static final String CDEMO_MENU_OPT_COUNT = "CDEMO-MENU-OPT-COUNT";

    /**
     * {@code 05 CDEMO-MENU-OPTIONS-DATA} - {@code app/cpy/COMEN02Y.cpy:23}.
     */
    public static final String CDEMO_MENU_OPTIONS_DATA = "CDEMO-MENU-OPTIONS-DATA";

    /**
     * {@code 05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA} - {@code app/cpy/COMEN02Y.cpy:87}.
     */
    public static final String CDEMO_MENU_OPTIONS = "CDEMO-MENU-OPTIONS";

    /**
     * {@code 10 CDEMO-MENU-OPT OCCURS 12 TIMES} - {@code app/cpy/COMEN02Y.cpy:88}.
     */
    public static final String CDEMO_MENU_OPT = "CDEMO-MENU-OPT";

    /**
     * {@code 15 CDEMO-MENU-OPT-NUM PIC 9(02)} - {@code app/cpy/COMEN02Y.cpy:89}.
     */
    public static final String CDEMO_MENU_OPT_NUM = "CDEMO-MENU-OPT-NUM";

    /**
     * {@code 15 CDEMO-MENU-OPT-NAME PIC X(35)} - {@code app/cpy/COMEN02Y.cpy:90}.
     */
    public static final String CDEMO_MENU_OPT_NAME = "CDEMO-MENU-OPT-NAME";

    /**
     * {@code 15 CDEMO-MENU-OPT-PGMNAME PIC X(08)} - {@code app/cpy/COMEN02Y.cpy:91}.
     */
    public static final String CDEMO_MENU_OPT_PGMNAME = "CDEMO-MENU-OPT-PGMNAME";

    /**
     * {@code 15 CDEMO-MENU-OPT-USRTYPE PIC X(01)} - {@code app/cpy/COMEN02Y.cpy:92}.
     */
    public static final String CDEMO_MENU_OPT_USRTYPE = "CDEMO-MENU-OPT-USRTYPE";

    private static final String FILLER = "FILLER";

    /**
     * {@code CDEMO-MENU-OPT-NUM PIC 9(02)} - {@value #OPT_NUM_LENGTH} bytes.
     */
    public static final int OPT_NUM_LENGTH = 2;

    /**
     * {@code CDEMO-MENU-OPT-NAME PIC X(35)} - {@value #OPT_NAME_LENGTH} bytes.
     */
    public static final int OPT_NAME_LENGTH = 35;

    /**
     * {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} - {@value #OPT_PGMNAME_LENGTH} bytes.
     */
    public static final int OPT_PGMNAME_LENGTH = 8;

    /**
     * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} - {@value #OPT_USRTYPE_LENGTH} byte.
     */
    public static final int OPT_USRTYPE_LENGTH = 1;

    /**
     * One {@code CDEMO-MENU-OPT} element: {@link #ENTRY_LENGTH} bytes, being {@value #OPT_NUM_LENGTH} +
     * {@value #OPT_NAME_LENGTH} + {@value #OPT_PGMNAME_LENGTH} + {@value #OPT_USRTYPE_LENGTH}.
     */
    public static final int ENTRY_LENGTH =
            OPT_NUM_LENGTH + OPT_NAME_LENGTH + OPT_PGMNAME_LENGTH + OPT_USRTYPE_LENGTH;

    /**
     * The number of slots the table declares: {@value #TABLE_SIZE}, from {@code OCCURS 12 TIMES} at
     * {@code app/cpy/COMEN02Y.cpy:88}.
     */
    public static final int TABLE_SIZE = 12;

    /**
     * The active option count: {@value #ACTIVE_OPTION_COUNT}, the value of
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}.
     */
    public static final int ACTIVE_OPTION_COUNT = 10;

    /**
     * The first subscript the copybook declares no {@code VALUE} for:
     * {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE}.
     */
    public static final int SPECIFIED_OPTION_COUNT_PLUS_ONE = ACTIVE_OPTION_COUNT + 1;

    /**
     * The lowest legal COBOL subscript: {@value #FIRST_SUBSCRIPT}.
     */
    public static final int FIRST_SUBSCRIPT = 1;

    /**
     * {@code CDEMO-MENU-OPT-COUNT} occupies bytes {@value #MENU_OPT_COUNT_OFFSET} to
     * {@value #OPT_NUM_LENGTH} of the group, exclusive - {@link #MENU_OPT_COUNT_LENGTH} bytes.
     */
    public static final int MENU_OPT_COUNT_LENGTH = OPT_NUM_LENGTH;

    /**
     * The absolute zero-based offset of {@code CDEMO-MENU-OPT-COUNT} within the group.
     */
    public static final int MENU_OPT_COUNT_OFFSET = 0;

    /**
     * The absolute zero-based offset at which both {@code CDEMO-MENU-OPTIONS-DATA} and its
     * {@code CDEMO-MENU-OPTIONS} overlay begin: {@link #TABLE_OFFSET}, immediately after
     * {@code CDEMO-MENU-OPT-COUNT}.
     */
    public static final int TABLE_OFFSET = MENU_OPT_COUNT_OFFSET + MENU_OPT_COUNT_LENGTH;

    /**
     * {@code CDEMO-MENU-OPTIONS-DATA} - {@link #POPULATED_DATA_LENGTH} bytes, being
     * {@value #ACTIVE_OPTION_COUNT} populated entries of {@link #ENTRY_LENGTH} bytes.
     */
    public static final int POPULATED_DATA_LENGTH = ACTIVE_OPTION_COUNT * ENTRY_LENGTH;

    /**
     * {@code CDEMO-MENU-OPTIONS} - {@link #TABLE_LENGTH} bytes, being {@value #TABLE_SIZE} slots of
     * {@link #ENTRY_LENGTH} bytes.
     */
    public static final int TABLE_LENGTH = TABLE_SIZE * ENTRY_LENGTH;

    /**
     * The whole {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} group: {@link #GROUP_LENGTH} bytes, being
     * {@link #MENU_OPT_COUNT_LENGTH} + {@link #TABLE_LENGTH}.
     */
    public static final int GROUP_LENGTH = MENU_OPT_COUNT_LENGTH + TABLE_LENGTH;

    /**
     * The suffix that names the unspecified tail descriptor: {@link #UNSPECIFIED_TAIL_SUFFIX}.
     */
    public static final String UNSPECIFIED_TAIL_SUFFIX = "-UNSPECIFIED-TAIL";

    /**
     * {@code CDEMO-MENU-OPT-NUM} - offset {@value #OPT_NUM_OFFSET} within an entry.
     */
    public static final int OPT_NUM_OFFSET = 0;

    /**
     * {@code CDEMO-MENU-OPT-NAME} - offset {@link #OPT_NAME_OFFSET} within an entry.
     */
    public static final int OPT_NAME_OFFSET = OPT_NUM_OFFSET + OPT_NUM_LENGTH;

    /**
     * {@code CDEMO-MENU-OPT-PGMNAME} - offset {@link #OPT_PGMNAME_OFFSET} within an entry.
     */
    public static final int OPT_PGMNAME_OFFSET = OPT_NAME_OFFSET + OPT_NAME_LENGTH;

    /**
     * {@code CDEMO-MENU-OPT-USRTYPE} - offset {@link #OPT_USRTYPE_OFFSET} within an entry.
     */
    public static final int OPT_USRTYPE_OFFSET = OPT_PGMNAME_OFFSET + OPT_PGMNAME_LENGTH;

    /**
     * The lowest value {@code CDEMO-MENU-OPT-NUM PIC 9(02)} can hold: {@value #MIN_OPT_NUM}.
     */
    public static final int MIN_OPT_NUM = 0;

    /**
     * The highest value {@code CDEMO-MENU-OPT-NUM PIC 9(02)} can hold: {@value #MAX_OPT_NUM}.
     */
    public static final int MAX_OPT_NUM = 99;

    /**
     * The position of {@code CDEMO-MENU-OPT-NUM} within {@link #entryFieldSpans(int)}, which returns the
     * four sub-fields in copybook declaration order.
     */
    public static final int NUM_SUBFIELD = 0;

    /**
     * The position of {@code CDEMO-MENU-OPT-NAME} within {@link #entryFieldSpans(int)}.
     */
    public static final int NAME_SUBFIELD = 1;

    /**
     * The position of {@code CDEMO-MENU-OPT-PGMNAME} within {@link #entryFieldSpans(int)}.
     */
    public static final int PGMNAME_SUBFIELD = 2;

    /**
     * The position of {@code CDEMO-MENU-OPT-USRTYPE} within {@link #entryFieldSpans(int)}.
     */
    public static final int USRTYPE_SUBFIELD = 3;

    /**
     * The number of sub-fields one {@code CDEMO-MENU-OPT} element declares: {@value #SUBFIELDS_PER_ENTRY},
     * from {@code app/cpy/COMEN02Y.cpy:89-92}.
     */
    public static final int SUBFIELDS_PER_ENTRY = 4;

    /**
     * One element of {@code CDEMO-MENU-OPT OCCURS 12 TIMES} - the four sub-fields of
     * {@code app/cpy/COMEN02Y.cpy:89-92} as one immutable value.
     *
     * <p>{@code CDEMO-MENU-OPT-NUM} is carried twice, and both forms are required.
     *
     * @param menuOptNum {@code CDEMO-MENU-OPT-NUM} as a number, {@value #MIN_OPT_NUM} to
     *     {@value #MAX_OPT_NUM} - the whole range {@code PIC 9(02)} admits
     * @param menuOptNumImage {@code CDEMO-MENU-OPT-NUM} as its raw {@value #OPT_NUM_LENGTH}-byte
     *     zero-filled zoned image, which must decode to {@code menuOptNum}
     * @param menuOptName {@code CDEMO-MENU-OPT-NAME}, exactly {@value #OPT_NAME_LENGTH} characters,
     *     right-space-padded and never trimmed
     * @param menuOptPgmName {@code CDEMO-MENU-OPT-PGMNAME}, exactly {@value #OPT_PGMNAME_LENGTH}
     *     characters, right-space-padded and never trimmed
     * @param menuOptUsrType {@code CDEMO-MENU-OPT-USRTYPE}, exactly {@value #OPT_USRTYPE_LENGTH} character
     */
    public record MenuOption(int menuOptNum,
                             String menuOptNumImage,
                             String menuOptName,
                             String menuOptPgmName,
                             String menuOptUsrType) {
        /**
         * Validates every component against its declared {@code PICTURE}, including that the numeric form
         * and the image form agree.
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
         * Builds an entry from the copybook's declared values, deriving the zoned image and applying each
         * field's declared width.
         *
         * <p>Passing text longer than its declared width is rejected rather than truncated, because every
         * caller of this factory is transcribing a copybook literal.
         *
         * @param menuOptNum {@code CDEMO-MENU-OPT-NUM}, {@value #MIN_OPT_NUM} to {@value #MAX_OPT_NUM}
         * @param menuOptName {@code CDEMO-MENU-OPT-NAME}, at most {@value #OPT_NAME_LENGTH} characters;
         *     padded to exactly that width
         * @param menuOptPgmName {@code CDEMO-MENU-OPT-PGMNAME}, at most {@value #OPT_PGMNAME_LENGTH}
         *     characters; padded to exactly that width
         * @param menuOptUsrType {@code CDEMO-MENU-OPT-USRTYPE}, at most {@value #OPT_USRTYPE_LENGTH}
         *     character; padded to exactly that width
         * @return the validated, fully padded entry
         * @throws NullPointerException if any string argument is {@code null}
         * @throws IllegalArgumentException if {@code menuOptNum} is out of range or any string argument is
         *     wider than its declared width
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

    // Every resulting value is nonetheless byte-identical to the copybook literal: MenuOption's constructor
    // rejects any component that is not EXACTLY its declared width, so a name that is not precisely
    // OPT_NAME_LENGTH characters prevents this class from initialising at all.

    private static final List<Optional<MenuOption>> OPTIONS = List.of(
            specified(1, "Account View", "COACTVWC", "U"),
            specified(2, "Account Update", "COACTUPC", "U"),
            specified(3, "Credit Card List", "COCRDLIC", "U"),
            specified(4, "Credit Card View", "COCRDSLC", "U"),
            specified(5, "Credit Card Update", "COCRDUPC", "U"),
            specified(6, "Transaction List", "COTRN00C", "U"),
            // That is what the copybook says, and it is transcribed exactly as it stands. The pairing is
            // the source of the documented view/add naming inversion elsewhere in this migration and must
            // not be "corrected" here.
            specified(7, "Transaction View", "COTRN01C", "U"),
            specified(8, "Transaction Add", "COTRN02C", "U"),
            specified(9, "Transaction Reports", "CORPT00C", "U"),
            specified(10, "Bill Payment", "COBIL00C", "U"),
            unspecified(),
            unspecified());

    private static final List<MenuOption> ACTIVE_OPTIONS = OPTIONS.subList(0, ACTIVE_OPTION_COUNT)
            .stream()
            .map(slot -> slot.orElseThrow(() -> new IllegalStateException(
                    "Every one of the first " + ACTIVE_OPTION_COUNT + " slots of "
                            + CDEMO_MENU_OPTIONS + " carries a copybook VALUE, so none may be absent; "
                            + "this class is mis-transcribed if one is")))
            .toList();

    private static final FieldSpan MENU_OPT_COUNT_SPAN =
            FieldSpan.unsignedNumeric(CDEMO_MENU_OPT_COUNT, MENU_OPT_COUNT_OFFSET,
                            MENU_OPT_COUNT_LENGTH)
                    .withInitialValue(Integer.toString(ACTIVE_OPTION_COUNT));

    private static final FieldSpan POPULATED_DATA_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS_DATA, TABLE_OFFSET, POPULATED_DATA_LENGTH, PictureKind.ALPHANUMERIC);

    private static final FieldSpan TABLE_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS, TABLE_OFFSET, TABLE_LENGTH, PictureKind.ALPHANUMERIC);

    private static final FieldSpan UNSPECIFIED_TAIL_SPAN = FieldSpan.redefining(
            CDEMO_MENU_OPTIONS + UNSPECIFIED_TAIL_SUFFIX, TABLE_OFFSET + POPULATED_DATA_LENGTH,
            TABLE_LENGTH - POPULATED_DATA_LENGTH, PictureKind.FILLER);

    /**
     * The complete, self-checking layout of {@code 01 CARDDEMO-MAIN-MENU-OPTIONS}: {@link #GROUP_LENGTH}
     * bytes, {@code CDEMO-MENU-OPT-COUNT} followed by the forty-eight {@code FILLER} sub-spans of the
     * {@value #TABLE_SIZE} slots, then the two {@code REDEFINES} overlays.
     */
    public static final RecordLayout GROUP_LAYOUT = buildGroupLayout();

    private static final List<FieldSpan> FIELD_SPANS = buildFieldSpans();

    // Two access styles, named so they cannot be confused: options() is the ZERO-based Java list,
    // optionBySubscript(int) takes the ONE-based COBOL subscript.

    /**
     * The {@code CDEMO-MENU-OPTIONS} table view: all {@value #TABLE_SIZE} slots in subscript order,
     * zero-based as a Java list, with slots {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE} and
     * {@value #TABLE_SIZE} empty.
     *
     * <p>Empty means exactly that: the copybook gives those two slots no {@code VALUE} and they sit past
     * the end of the group the {@code OCCURS} overlay redefines, so there is no entry to report and none is
     * invented.
     *
     * @return an unmodifiable list of exactly {@value #TABLE_SIZE} slots, of which the first
     *     {@value #ACTIVE_OPTION_COUNT} are present
     */
    public static List<Optional<MenuOption>> options() {
        return OPTIONS;
    }

    /**
     * The {@code CDEMO-MENU-OPTIONS-DATA} literal-storage view: the {@value #ACTIVE_OPTION_COUNT} entries
     * the copybook declares a {@code VALUE} for, zero-based as a Java list.
     *
     * @return an unmodifiable list of exactly {@value #ACTIVE_OPTION_COUNT} entries
     */
    public static List<MenuOption> activeOptions() {
        return ACTIVE_OPTIONS;
    }

    /**
     * Addresses one slot by its one-based COBOL subscript, exactly as {@code CDEMO-MENU-OPT(WS-IDX)} does.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     *     inclusive
     * @return the addressed entry, or empty for subscript {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE} or
     *     {@value #TABLE_SIZE}, which the copybook gives no {@code VALUE}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below {@value #FIRST_SUBSCRIPT} or
     *     above {@value #TABLE_SIZE}
     */
    public static Optional<MenuOption> optionBySubscript(int cobolSubscript) {
        requireSubscript(cobolSubscript);
        return OPTIONS.get(cobolSubscript - FIRST_SUBSCRIPT);
    }

    /**
     * Whether the copybook determines the content of a slot.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     *     inclusive
     * @return {@code true} when the copybook declares a {@code VALUE} for that slot
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below {@value #FIRST_SUBSCRIPT} or
     *     above {@value #TABLE_SIZE}
     */
    public static boolean isSpecified(int cobolSubscript) {
        requireSubscript(cobolSubscript);
        return cobolSubscript <= ACTIVE_OPTION_COUNT;
    }

    /**
     * The value of {@code CDEMO-MENU-OPT-COUNT} as a number: {@value #ACTIVE_OPTION_COUNT}.
     *
     * @return {@value #ACTIVE_OPTION_COUNT}
     */
    public static int menuOptCount() {
        return ACTIVE_OPTION_COUNT;
    }

    /**
     * The value of {@code CDEMO-MENU-OPT-COUNT} as its raw {@link #MENU_OPT_COUNT_LENGTH}-byte zero-filled
     * zoned image - {@code "10"}.
     *
     * @return the two-character display image of the active option count
     */
    public static String menuOptCountImage() {
        return pic9Image(ACTIVE_OPTION_COUNT, MENU_OPT_COUNT_LENGTH);
    }

    /**
     * The descriptor of {@code CDEMO-MENU-OPT-COUNT}: real storage at offset
     * {@value #MENU_OPT_COUNT_OFFSET}, {@link #MENU_OPT_COUNT_LENGTH} bytes, carrying its declared
     * {@code VALUE}.
     *
     * @return the immutable descriptor
     */
    public static FieldSpan menuOptCountSpan() {
        return MENU_OPT_COUNT_SPAN;
    }

    /**
     * The first of the two {@code REDEFINES} accessors: the {@code CDEMO-MENU-OPTIONS-DATA} literal-storage
     * view, {@link #POPULATED_DATA_LENGTH} bytes at offset {@link #TABLE_OFFSET}.
     *
     * @return the immutable descriptor of the redefined storage
     */
    public static FieldSpan populatedDataSpan() {
        return POPULATED_DATA_SPAN;
    }

    /**
     * The second of the two {@code REDEFINES} accessors: the {@code CDEMO-MENU-OPTIONS} table view,
     * {@link #TABLE_LENGTH} bytes at the same offset {@link #TABLE_OFFSET} as {@link #populatedDataSpan()}
     * and over the same backing bytes.
     *
     * @return the immutable descriptor of the redefining overlay
     */
    public static FieldSpan tableSpan() {
        return TABLE_SPAN;
    }

    /**
     * The descriptor of one whole {@link #ENTRY_LENGTH}-byte {@code CDEMO-MENU-OPT} element, addressed by
     * its one-based COBOL subscript.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     *     inclusive
     * @return the immutable descriptor of that element
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside {@value #FIRST_SUBSCRIPT} to
     *     {@value #TABLE_SIZE}
     */
    public static FieldSpan entrySpan(int cobolSubscript) {
        return FixedWidthRecord.occursElementSpan(TABLE_SPAN, TABLE_SIZE, cobolSubscript,
                CDEMO_MENU_OPT, PictureKind.ALPHANUMERIC);
    }

    /**
     * The {@value #SUBFIELDS_PER_ENTRY} sub-field descriptors of one element, in copybook declaration
     * order: {@code CDEMO-MENU-OPT-NUM}, {@code -NAME}, {@code -PGMNAME}, {@code -USRTYPE}, each named with
     * its subscript.
     *
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     *     inclusive
     * @return an unmodifiable list of exactly {@value #SUBFIELDS_PER_ENTRY} descriptors
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside {@value #FIRST_SUBSCRIPT} to
     *     {@value #TABLE_SIZE}
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
     * Every elementary field of the group as a descriptor, in copybook order: {@code CDEMO-MENU-OPT-COUNT}
     * followed by four per slot, one to {@value #TABLE_SIZE} - forty-nine in total.
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
     * @param cobolName one of {@link #CDEMO_MENU_OPT_NUM}, {@link #CDEMO_MENU_OPT_NAME},
     *     {@link #CDEMO_MENU_OPT_PGMNAME} or {@link #CDEMO_MENU_OPT_USRTYPE}
     * @param cobolSubscript the one-based subscript, {@value #FIRST_SUBSCRIPT} to {@value #TABLE_SIZE}
     *     inclusive
     * @return {@code cobolName} followed by the subscript in parentheses
     * @throws NullPointerException if {@code cobolName} is {@code null}
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside {@value #FIRST_SUBSCRIPT} to
     *     {@value #TABLE_SIZE}
     */
    public static String subscriptedName(String cobolName, int cobolSubscript) {
        Objects.requireNonNull(cobolName, "A copybook field name is required to subscript it");
        requireSubscript(cobolSubscript);
        return cobolName + "(" + cobolSubscript + ")";
    }

    // The consuming program reads this table from WORKING-STORAGE rather than from a dataset, so these
    // exist for the parity harness's field-name-keyed diffing and to make the geometry provable end to end.

    /**
     * Serialises the table to its {@link #GROUP_LENGTH}-byte image, writing every value from the in-memory
     * entries.
     *
     * @param charset the code page to encode into, named explicitly by the caller - {@code IBM037} for
     *     EBCDIC, {@code US-ASCII} for the text fixtures
     * @return exactly {@link #GROUP_LENGTH} bytes, a fresh array on every call
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} cannot encode the required characters to one byte
     *     each
     */
    public static byte[] encode(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.newRecord(GROUP_LAYOUT);
        codec.writePic9(area, MENU_OPT_COUNT_SPAN, menuOptCountImage());
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            Optional<MenuOption> slot = OPTIONS.get(subscript - FIRST_SUBSCRIPT);
            if (slot.isEmpty()) {
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
     * The {@link #GROUP_LENGTH}-byte image produced by initialising a record from {@link #GROUP_LAYOUT}
     * alone - that is, from the copybook's {@code VALUE} clauses and, for a span that has none, this
     * module's own pad byte, with no entry ever consulted.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return exactly {@link #GROUP_LENGTH} bytes, a fresh array on every call
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space and zero characters to
     *     exactly one byte each
     */
    public static byte[] declaredImage(Charset charset) {
        return GROUP_LAYOUT.newRecord(charset).toByteArray();
    }

    /**
     * Deserialises a {@link #GROUP_LENGTH}-byte image into the {@value #TABLE_SIZE}-slot table view.
     *
     * @param image exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an unmodifiable list of exactly {@value #TABLE_SIZE} entries in subscript order
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #GROUP_LENGTH} bytes, or if a
     *     decoded column is not valid for its declared {@code PICTURE}
     */
    public static List<Optional<MenuOption>> decode(byte[] image, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.wrap(image, GROUP_LAYOUT);
        List<Optional<MenuOption>> decoded = new ArrayList<>(TABLE_SIZE);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            if (!isSpecified(subscript)) {
                // It cannot be attributed to the copybook's table, because the copybook assigns it nothing;
                // and it need not be well formed at all - real storage may hold anything there, including
                // bytes that are not digits, which decodePic9AsInt would reject and thereby fail a decode
                // of a perfectly valid image.
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
     * {@link #SPECIFIED_OPTION_COUNT_PLUS_ONE} through {@value #TABLE_SIZE}, as opaque storage.
     *
     * @return the descriptor for the unspecified tail, {@value #TABLE_SIZE} minus
     *     {@value #ACTIVE_OPTION_COUNT} entries wide
     */
    public static FieldSpan unspecifiedTailSpan() {
        return UNSPECIFIED_TAIL_SPAN;
    }

    /**
     * Decomposes a {@link #GROUP_LENGTH}-byte image into raw field images keyed by the COBOL reference form
     * of each field name, in copybook order - the map the parity differ compares field by field rather than
     * as one long string.
     *
     * @param image exactly {@link #GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return an insertion-ordered, unmodifiable map of forty-nine field name to raw image pairs
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #GROUP_LENGTH} bytes
     */
    public static Map<String, String> fieldImages(byte[] image, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.wrap(image, GROUP_LAYOUT);
        Map<String, String> images = new LinkedHashMap<>();
        for (FieldSpan field : FIELD_SPANS) {
            images.put(field.name(), area.readSpan(field));
        }
        return Collections.unmodifiableMap(images);
    }

    // These two helpers apply the PIC X and PIC 9 width rules as CHARACTER operations, which is what a
    // constant table needs: the entries are built while this class initialises, long before any caller has
    // named a code page, and inventing one here would be exactly the implicit charset choice this module
    // forbids.

    /**
     * Applies the {@code PIC X(n)} width rule to a transcribed copybook literal: right-space-pad to exactly
     * {@code declaredWidth}.
     *
     * @param literal the copybook {@code VALUE} literal being rendered
     * @param declaredWidth the width the copybook's {@code PICTURE} declares
     * @param cobolName the COBOL item name to name in a failure message
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
     * @param value the value being stored
     * @param declaredWidth the width the copybook's {@code PICTURE} declares
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

    private static void requireSubscript(int cobolSubscript) {
        FixedWidthRecord.occursElementOffsetOneBased(TABLE_OFFSET, ENTRY_LENGTH, TABLE_SIZE,
                cobolSubscript);
    }

    private static Optional<MenuOption> specified(int menuOptNum,
                                                  String menuOptName,
                                                  String menuOptPgmName,
                                                  String menuOptUsrType) {
        return Optional.of(MenuOption.of(menuOptNum, menuOptName, menuOptPgmName, menuOptUsrType));
    }

    private static Optional<MenuOption> unspecified() {
        return Optional.empty();
    }

    private static RecordLayout buildGroupLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(MENU_OPT_COUNT_SPAN);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= OPTIONS.size(); subscript++) {
            Optional<MenuOption> slot = OPTIONS.get(subscript - FIRST_SUBSCRIPT);
            int base = FixedWidthRecord.occursElementOffsetOneBased(TABLE_OFFSET, ENTRY_LENGTH,
                    OPTIONS.size(), subscript);
            spans.add(fillerSpan(base + OPT_NUM_OFFSET, OPT_NUM_LENGTH, PictureKind.UNSIGNED_NUMERIC,
                    slot.map(option -> Integer.toString(option.menuOptNum())).orElse(null)));
            spans.add(fillerSpan(base + OPT_NAME_OFFSET, OPT_NAME_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptName).orElse(null)));
            spans.add(fillerSpan(base + OPT_PGMNAME_OFFSET, OPT_PGMNAME_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptPgmName).orElse(null)));
            spans.add(fillerSpan(base + OPT_USRTYPE_OFFSET, OPT_USRTYPE_LENGTH, PictureKind.FILLER,
                    slot.map(MenuOption::menuOptUsrType).orElse(null)));
        }
        spans.add(POPULATED_DATA_SPAN);
        spans.add(TABLE_SPAN);
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    private static FieldSpan fillerSpan(int offset, int length, PictureKind kind, String literal) {
        return new FieldSpan(FILLER, offset, length, kind, literal, false);
    }

    private static List<FieldSpan> buildFieldSpans() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(MENU_OPT_COUNT_SPAN);
        for (int subscript = FIRST_SUBSCRIPT; subscript <= TABLE_SIZE; subscript++) {
            spans.addAll(entryFieldSpans(subscript));
        }
        return List.copyOf(spans);
    }

    private MenuOptions() {
        throw new AssertionError("MenuOptions is a copybook-derived constant table and must not be "
                + "instantiated");
    }
}
