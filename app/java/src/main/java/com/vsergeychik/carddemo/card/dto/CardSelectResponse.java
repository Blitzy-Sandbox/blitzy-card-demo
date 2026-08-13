package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ThisProgCommarea;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The outbound payload of the card-detail screen: a field-for-field projection of the output half of the
 * {@code COCRDSL} BMS symbolic map.
 *
 * <p>{@code app/bms/COCRDSL.bms} declares 31 {@code DFHMDF} fields of which exactly 15 carry a name.
 */
public final class CardSelectResponse {
    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COCRDSLC'}, {@code app/cbl/COCRDSLC.cbl:163-164}.
     */
    public static final String THIS_PROGRAM = "COCRDSLC";

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CCDL'}, {@code app/cbl/COCRDSLC.cbl:165-166}.
     */
    public static final String THIS_TRANID = "CCDL";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '}, {@code app/cbl/COCRDSLC.cbl:167-168}, as it arrives
     * in {@code CCARD-NEXT-MAPSET PIC X(7)}: right-truncated to seven characters.
     */
    public static final String THIS_MAPSET = "COCRDSL";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CCRDSLA'}, {@code app/cbl/COCRDSLC.cbl:169-170}.
     */
    public static final String MAP_NAME = "CCRDSLA";

    // Every number below is either read from a PICTURE clause or derived from ones that were, and
    // GROUP_GEOMETRY proves at class-initialisation time that they tile the group exactly.

    /**
     * The number of name-labelled {@code DFHMDF} fields, and therefore of data members: 15 of 31.
     */
    public static final int FIELD_COUNT = 15;

    /**
     * {@code 02 FILLER PIC X(12)} at {@code COCRDSL.CPY:L110} - the {@code TIOAPFX=YES} prefix.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * {@code 02 FILLER PICTURE X(3)} - the output view's per-field filler, overlaying the input view's
     * {@code xxxL COMP PIC S9(4)} plus {@code xxxF PICTURE X}.
     */
    public static final int FILLER_LENGTH = 3;

    /**
     * Each of {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} is {@code PICTURE X}: one byte.
     */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEMS_PER_FIELD = 4;

    public static final int FIELD_PREFIX_LENGTH =
            FILLER_LENGTH + (ATTRIBUTE_ITEMS_PER_FIELD * ATTRIBUTE_ITEM_LENGTH);

    /**
     * {@code 02 TRNNAMEO PIC X(4)}, {@code COCRDSL.CPY:L116}.
     */
    public static final int TRNNAMEO_LENGTH = 4;

    /**
     * {@code 02 TITLE01O PIC X(40)}, {@code COCRDSL.CPY:L122}.
     */
    public static final int TITLE01O_LENGTH = 40;

    /**
     * {@code 02 CURDATEO PIC X(8)}, {@code COCRDSL.CPY:L128}.
     */
    public static final int CURDATEO_LENGTH = 8;

    /**
     * {@code 02 PGMNAMEO PIC X(8)}, {@code COCRDSL.CPY:L134}.
     */
    public static final int PGMNAMEO_LENGTH = 8;

    /**
     * {@code 02 TITLE02O PIC X(40)}, {@code COCRDSL.CPY:L140}.
     */
    public static final int TITLE02O_LENGTH = 40;

    /**
     * {@code 02 CURTIMEO PIC X(8)}, {@code COCRDSL.CPY:L146}.
     */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code 02 ACCTSIDO PIC X(11)}, {@code COCRDSL.CPY:L152}.
     */
    public static final int ACCTSIDO_LENGTH = 11;

    /**
     * {@code 02 CARDSIDO PIC X(16)}, {@code COCRDSL.CPY:L158}.
     */
    public static final int CARDSIDO_LENGTH = 16;

    /**
     * {@code 02 CRDNAMEO PIC X(50)}, {@code COCRDSL.CPY:L164}.
     */
    public static final int CRDNAMEO_LENGTH = 50;

    /**
     * {@code 02 CRDSTCDO PIC X(1)}, {@code COCRDSL.CPY:L170}.
     */
    public static final int CRDSTCDO_LENGTH = 1;

    /**
     * {@code 02 EXPMONO PIC X(2)}, {@code COCRDSL.CPY:L176}.
     */
    public static final int EXPMONO_LENGTH = 2;

    /**
     * {@code 02 EXPYEARO PIC X(4)}, {@code COCRDSL.CPY:L182}.
     */
    public static final int EXPYEARO_LENGTH = 4;

    /**
     * {@code 02 INFOMSGO PIC X(40)}, {@code COCRDSL.CPY:L188}.
     */
    public static final int INFOMSGO_LENGTH = 40;

    /**
     * {@code 02 ERRMSGO PIC X(80)}, {@code COCRDSL.CPY:L194}.
     */
    public static final int ERRMSGO_LENGTH = 80;

    /**
     * {@code 02 FKEYSO PIC X(75)}, {@code COCRDSL.CPY:L200}.
     */
    public static final int FKEYSO_LENGTH = 75;

    public static final int DATA_LENGTH =
            TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH + TITLE02O_LENGTH
            + CURTIMEO_LENGTH + ACCTSIDO_LENGTH + CARDSIDO_LENGTH + CRDNAMEO_LENGTH + CRDSTCDO_LENGTH
            + EXPMONO_LENGTH + EXPYEARO_LENGTH + INFOMSGO_LENGTH + ERRMSGO_LENGTH + FKEYSO_LENGTH;

    /**
     * The width of {@code 01 CCRDSLAO} in full: {@value #TIOAPFX_LENGTH} + ({@value #FIELD_COUNT} x
     * {@link #FIELD_PREFIX_LENGTH}) + {@link #DATA_LENGTH} = 12 + 105 + 387 = 504 bytes.
     */
    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + (FIELD_COUNT * FIELD_PREFIX_LENGTH) + DATA_LENGTH;

    // Navigation carrier widths, taken from app/cpy/CVCRD01Y.cpy rather than restated, so the payload and
    // the work area cannot drift apart.

    /**
     * {@code CCARD-NEXT-PROG PIC X(8)}, {@code app/cpy/CVCRD01Y.cpy:L21}.
     */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /**
     * {@code CCARD-NEXT-MAPSET PIC X(7)}, {@code app/cpy/CVCRD01Y.cpy:L23}.
     */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)}, {@code app/cpy/CVCRD01Y.cpy:L24}.
     */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    /**
     * {@code CCDA-MSG-THANK-YOU} and {@code CCDA-MSG-INVALID-KEY} are both {@code PIC X(50)}.
     */
    public static final int STANDARD_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    /**
     * Characters a standard message loses on the right when it is moved into {@code INFOMSGO PIC X(40)}:
     * {@link #STANDARD_MESSAGE_LENGTH} - {@value #INFOMSGO_LENGTH} = 10.
     */
    public static final int INFOMSGO_STANDARD_MESSAGE_TRUNCATION =
            STANDARD_MESSAGE_LENGTH - INFOMSGO_LENGTH;

    /**
     * Spaces a standard message gains on the right when it is moved into {@code ERRMSGO PIC X(80)}:
     * {@value #ERRMSGO_LENGTH} - {@link #STANDARD_MESSAGE_LENGTH} = 30.
     */
    public static final int ERRMSGO_STANDARD_MESSAGE_PADDING =
            ERRMSGO_LENGTH - STANDARD_MESSAGE_LENGTH;

    // US-ASCII is named explicitly because the codec's constructor demands a charset and a platform default
    // is never acceptable in this system; the choice cannot influence any value this class produces.

    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final byte LOW_VALUES_BYTE = 0x00;

    /**
     * The {@code FKEYS} legend as the mapset declares it, space-padded to the item's declared
     * {@value #FKEYSO_LENGTH} characters:
     * {@code DFHMDF ... LENGTH=75, POS=(24,1), INITIAL='ENTER=Search Cards F3=Exit'} at
     * {@code app/bms/COCRDSL.bms:148-152}.
     */
    public static final String BMS_INITIAL_FKEYS =
            PIC_X_CODEC.movePicX("ENTER=Search Cards  F3=Exit", FKEYSO_LENGTH);

    /**
     * One of the fifteen name-labelled {@code DFHMDF} fields of mapset {@code COCRDSL}, in copybook
     * declaration order.
     *
     * <p>The order below is the order of the {@code xxxO} declarations at {@code COCRDSL.CPY} {@code L116},
     * {@code L122}, {@code L128}, {@code L134}, {@code L140}, {@code L146}, {@code L152}, {@code L158},
     * {@code L164}, {@code L170}, {@code L176}, {@code L182}, {@code L188}, {@code L194} and {@code L200}.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAME} - the transaction identifier, {@code app/bms/COCRDSL.bms:34}
         * ({@code ATTRB=(ASKIP,FSET,NORM) COLOR=BLUE POS=(1,7)}); {@code TRNNAMEO PIC X(4)},
         * {@code COCRDSL.CPY:L116}.
         */
        TRNNAME("TRNNAME", "TRNNAMEO", TRNNAMEO_LENGTH, 116, 34),

        /**
         * {@code TITLE01} - the upper heading, {@code app/bms/COCRDSL.bms:38}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(1,21)}); {@code TITLE01O PIC X(40)},
         * {@code COCRDSL.CPY:L122}.
         */
        TITLE01("TITLE01", "TITLE01O", TITLE01O_LENGTH, 122, 38),

        /**
         * {@code CURDATE} - the current date, {@code app/bms/COCRDSL.bms:47}
         * ({@code COLOR=BLUE POS=(1,71) INITIAL='mm/dd/yy'}); {@code CURDATEO PIC X(8)},
         * {@code COCRDSL.CPY:L128}.
         */
        CURDATE("CURDATE", "CURDATEO", CURDATEO_LENGTH, 128, 47),

        /**
         * {@code PGMNAME} - the program name, {@code app/bms/COCRDSL.bms:57}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=BLUE POS=(2,7)}); {@code PGMNAMEO PIC X(8)},
         * {@code COCRDSL.CPY:L134}.
         */
        PGMNAME("PGMNAME", "PGMNAMEO", PGMNAMEO_LENGTH, 134, 57),

        /**
         * {@code TITLE02} - the lower heading, {@code app/bms/COCRDSL.bms:61}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(2,21)}); {@code TITLE02O PIC X(40)},
         * {@code COCRDSL.CPY:L140}.
         */
        TITLE02("TITLE02", "TITLE02O", TITLE02O_LENGTH, 140, 61),

        /**
         * {@code CURTIME} - the current time, {@code app/bms/COCRDSL.bms:70}
         * ({@code COLOR=BLUE POS=(2,71) INITIAL='hh:mm:ss'}); {@code CURTIMEO PIC X(8)},
         * {@code COCRDSL.CPY:L146}.
         */
        CURTIME("CURTIME", "CURTIMEO", CURTIMEO_LENGTH, 146, 70),

        /**
         * {@code ACCTSID} - the account number the user searched on, {@code app/bms/COCRDSL.bms:84}
         * ({@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=DEFAULT HILIGHT=UNDERLINE POS=(7,45)});
         * {@code ACCTSIDO PIC X(11)}, {@code COCRDSL.CPY:L152}.
         */
        ACCTSID("ACCTSID", "ACCTSIDO", ACCTSIDO_LENGTH, 152, 84),

        /**
         * {@code CARDSID} - the card number the user searched on, {@code app/bms/COCRDSL.bms:96}
         * ({@code ATTRB=(FSET,NORM,UNPROT) COLOR=DEFAULT HILIGHT=UNDERLINE POS=(8,45)});
         * {@code CARDSIDO PIC X(16)}, {@code COCRDSL.CPY:L158}.
         */
        CARDSID("CARDSID", "CARDSIDO", CARDSIDO_LENGTH, 158, 96),

        /**
         * {@code CRDNAME} - the embossed name, {@code app/bms/COCRDSL.bms:107}
         * ({@code HILIGHT=UNDERLINE POS=(11,25)}); {@code CRDNAMEO PIC X(50)}, {@code COCRDSL.CPY:L164}.
         */
        CRDNAME("CRDNAME", "CRDNAMEO", CRDNAMEO_LENGTH, 164, 107),

        /**
         * {@code CRDSTCD} - the active status, {@code app/bms/COCRDSL.bms:116}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(13,25)}); {@code CRDSTCDO PIC X(1)},
         * {@code COCRDSL.CPY:L170}.
         */
        CRDSTCD("CRDSTCD", "CRDSTCDO", CRDSTCDO_LENGTH, 170, 116),

        /**
         * {@code EXPMON} - the expiry month, {@code app/bms/COCRDSL.bms:126}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(15,25)}); {@code EXPMONO PIC X(2)},
         * {@code COCRDSL.CPY:L176}.
         */
        EXPMON("EXPMON", "EXPMONO", EXPMONO_LENGTH, 176, 126),

        /**
         * {@code EXPYEAR} - the expiry year, {@code app/bms/COCRDSL.bms:133}
         * ({@code ATTRB=(ASKIP) HILIGHT=UNDERLINE POS=(15,30)}); {@code EXPYEARO PIC X(4)},
         * {@code COCRDSL.CPY:L182}.
         */
        EXPYEAR("EXPYEAR", "EXPYEARO", EXPYEARO_LENGTH, 182, 133),

        /**
         * {@code INFOMSG} - the informational line, {@code app/bms/COCRDSL.bms:139}
         * ({@code ATTRB=(PROT) COLOR=NEUTRAL HILIGHT=OFF POS=(20,25)}); {@code INFOMSGO PIC X(40)},
         * {@code COCRDSL.CPY:L188}.
         */
        INFOMSG("INFOMSG", "INFOMSGO", INFOMSGO_LENGTH, 188, 139),

        /**
         * {@code ERRMSG} - the error line, {@code app/bms/COCRDSL.bms:144}
         * ({@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED POS=(23,1)}); {@code ERRMSGO PIC X(80)},
         * {@code COCRDSL.CPY:L194}.
         */
        ERRMSG("ERRMSG", "ERRMSGO", ERRMSGO_LENGTH, 194, 144),

        /**
         * {@code FKEYS} - the function-key legend, {@code app/bms/COCRDSL.bms:148}
         * ({@code ATTRB=(ASKIP,NORM) COLOR=YELLOW POS=(24,1) INITIAL='ENTER=Search Cards F3=Exit'});
         * {@code FKEYSO PIC X(75)}, {@code COCRDSL.CPY:L200}.
         */
        FKEYS("FKEYS", "FKEYSO", FKEYSO_LENGTH, 200, 148);

        private final String dfhmdfLabel;
        private final String cobolName;
        private final int length;
        private final int copybookLine;
        private final int mapsetLine;

        ScreenField(String dfhmdfLabel, String cobolName, int length, int copybookLine,
                int mapsetLine) {
            this.dfhmdfLabel = dfhmdfLabel;
            this.cobolName = cobolName;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
        }

        /**
         * The field's {@code DFHMDF} label in {@code app/bms/COCRDSL.bms}, which is also the
         * {@code (SCRNVAR2)} token {@code app/cpy/CSSETATY.cpy} substitutes and the prefix every
         * symbolic-map item name is built from.
         *
         * @return the label, for example {@code "ACCTSID"}; never {@code null} and never empty
         */
        public String dfhmdfLabel() {
            return dfhmdfLabel;
        }

        /**
         * The output data item's name exactly as {@code app/cpy-bms/COCRDSL.CPY} spells it - the label
         * followed by {@code O}.
         *
         * @return the item name, for example {@code "ACCTSIDO"}; never {@code null} and never empty
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The name of the field's colour item, the label followed by {@code C}: the receiver of
         * {@code MOVE DFHRED} in {@code app/cpy/CSSETATY.cpy:L21-L22}.
         *
         * @return the item name, for example {@code "ACCTSIDC"}; never {@code null}
         */
        public String colourItemName() {
            return dfhmdfLabel + "C";
        }

        /**
         * The name of the field's programmed-symbol item, the label followed by {@code P}.
         *
         * @return the item name, for example {@code "ACCTSIDP"}; never {@code null}
         */
        public String psItemName() {
            return dfhmdfLabel + "P";
        }

        /**
         * The name of the field's highlight item, the label followed by {@code H}.
         *
         * @return the item name, for example {@code "ACCTSIDH"}; never {@code null}
         */
        public String hilightItemName() {
            return dfhmdfLabel + "H";
        }

        /**
         * The name of the field's validation item, the label followed by {@code V}.
         *
         * @return the item name, for example {@code "ACCTSIDV"}; never {@code null}
         */
        public String validnItemName() {
            return dfhmdfLabel + "V";
        }

        /**
         * The declared width of the {@code xxxO} item, read from its {@code PICTURE} clause.
         *
         * @return the width in characters; always at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The line of {@code app/cpy-bms/COCRDSL.CPY} the {@code xxxO} declaration was read from.
         *
         * @return the one-based line number
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of {@code app/bms/COCRDSL.bms} the name-labelled {@code DFHMDF} was read from.
         *
         * @return the one-based line number
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The offset of the field's whole seven-plus-{@code n} byte span within {@code 01 CCRDSLAO},
         * counted from zero.
         *
         * @return the zero-based offset of the field's leading {@code FILLER}
         */
        public int fieldOffset() {
            int offset = TIOAPFX_LENGTH;
            ScreenField[] fields = values();
            for (int index = 0; index < ordinal(); index++) {
                offset += FIELD_PREFIX_LENGTH + fields[index].length;
            }
            return offset;
        }

        /**
         * The offset of the field's leading {@code 02 FILLER PICTURE X(3)}, which is the same as
         * {@link #fieldOffset()} and is named separately so geometry code reads as the copybook does.
         *
         * @return the zero-based offset of the three filler bytes
         */
        public int fillerOffset() {
            return fieldOffset();
        }

        /**
         * The offset of the {@code xxxC} colour item - the first byte after the filler.
         *
         * @return the zero-based offset of the colour byte
         */
        public int colourOffset() {
            return fieldOffset() + FILLER_LENGTH;
        }

        /**
         * The offset of the {@code xxxP} programmed-symbol item.
         *
         * @return the zero-based offset of the programmed-symbol byte
         */
        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int hilightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int validnOffset() {
            return hilightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset of the {@code xxxO} data item, which begins immediately after the four attribute
         * items.
         *
         * @return the zero-based offset of the first data character
         */
        public int dataOffset() {
            return fieldOffset() + FIELD_PREFIX_LENGTH;
        }

        /**
         * Renders this field's full provenance in one line, for a parity report or a review note.
         *
         * @return the description; never {@code null} and never empty
         */
        public String describe() {
            int start = dataOffset();
            return dfhmdfLabel + ": " + cobolName + " PIC X(" + length + ") at COCRDSL.CPY:"
                    + copybookLine + ", DFHMDF at COCRDSL.bms:" + mapsetLine + ", bytes " + start
                    + "-" + (start + length - 1) + " of CCRDSLAO";
        }
    }

    /**
     * The name COBOL gives an unnamed span.
     */
    public static final String FILLER_ITEM_NAME = "FILLER";

    public enum SpanKind {
        /**
         * The leading {@code 02 FILLER PIC X(12)} that {@code TIOAPFX=YES} prepends, once per group.
         */
        TIOAPFX_PREFIX,

        /**
         * A per-field {@code 02 FILLER PICTURE X(3)}, overlaying the input view's {@code xxxL} plus
         * {@code xxxF}.
         */
        FILLER,

        COLOUR,

        PS,

        HILIGHT,

        VALIDN,

        DATA
    }

    /**
     * One span of {@code 01 CCRDSLAO}, named as the copybook names it.
     *
     * @param cobolName the item name, or {@link CardSelectResponse#FILLER_ITEM_NAME} for an unnamed span
     * @param kind what the span carries
     * @param offset the zero-based offset of its first byte within the 504-byte group
     * @param length its width in bytes
     */
    public record SpanDescriptor(String cobolName, SpanKind kind, int offset, int length) {
        public SpanDescriptor {
            Objects.requireNonNull(cobolName, "A span must be named, even when the name is FILLER");
            Objects.requireNonNull(kind, "A span must state what it carries");
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "Span " + cobolName + " cannot start at negative offset " + offset);
            }
            if (length < 1) {
                throw new IllegalArgumentException("Span " + cobolName + " cannot be " + length
                        + " bytes wide; a PICTURE clause always declares at least one");
            }
        }

        /**
         * The offset one past the span's last byte, which is where the next span must begin.
         *
         * @return {@code offset + length}
         */
        public int endOffset() {
            return offset + length;
        }
    }

    private static final List<SpanDescriptor> GROUP_GEOMETRY = buildGroupGeometry();

    /**
     * Enumerates every span of the output group from the copybook's declared widths and proves that they
     * tile the group exactly.
     *
     * @return the spans in ascending offset order
     * @throws IllegalStateException if the spans leave a gap, overlap, or do not total
     *     {@link #GROUP_LENGTH} bytes - any of which would mean a constant above disagrees with
     *     {@code app/cpy-bms/COCRDSL.CPY}
     */
    private static List<SpanDescriptor> buildGroupGeometry() {
        List<SpanDescriptor> spans =
                new ArrayList<>(1 + (FIELD_COUNT * (2 + ATTRIBUTE_ITEMS_PER_FIELD)));

        spans.add(new SpanDescriptor(FILLER_ITEM_NAME, SpanKind.TIOAPFX_PREFIX, 0, TIOAPFX_LENGTH));

        for (ScreenField field : ScreenField.values()) {
            spans.add(new SpanDescriptor(
                    FILLER_ITEM_NAME, SpanKind.FILLER, field.fillerOffset(), FILLER_LENGTH));
            spans.add(new SpanDescriptor(field.colourItemName(), SpanKind.COLOUR,
                    field.colourOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.psItemName(), SpanKind.PS,
                    field.psOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.hilightItemName(), SpanKind.HILIGHT,
                    field.hilightOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.validnItemName(), SpanKind.VALIDN,
                    field.validnOffset(), ATTRIBUTE_ITEM_LENGTH));
            spans.add(new SpanDescriptor(field.cobolName(), SpanKind.DATA,
                    field.dataOffset(), field.length()));
        }

        return List.copyOf(verifyGroupTiling(spans));
    }

    /**
     * Proves that a list of spans tiles a {@link #GROUP_LENGTH}-byte group exactly: the first span starts
     * at offset zero, each subsequent span starts where its predecessor ended, and the last one ends at
     * {@link #GROUP_LENGTH}.
     *
     * @param spans the spans to check, in the order they are meant to occupy the group
     * @return {@code spans}, unchanged, so the call can wrap a construction expression
     * @throws NullPointerException if {@code spans} or any element is {@code null}
     * @throws IllegalStateException if the spans leave a gap, overlap, or do not total
     *     {@link #GROUP_LENGTH} bytes
     */
    public static List<SpanDescriptor> verifyGroupTiling(List<SpanDescriptor> spans) {
        Objects.requireNonNull(spans, "A span list is required to verify a group's tiling");

        int expectedOffset = 0;
        for (SpanDescriptor span : spans) {
            Objects.requireNonNull(span, "A span list may not contain a null span");
            if (span.offset() != expectedOffset) {
                throw new IllegalStateException("Span " + span.cobolName() + " (" + span.kind()
                        + ") starts at offset " + span.offset() + " but the preceding span ends at "
                        + expectedOffset + "; the spans of 01 CCRDSLAO must tile the group with no gap "
                        + "and no overlap");
            }
            expectedOffset = span.endOffset();
        }
        if (expectedOffset != GROUP_LENGTH) {
            throw new IllegalStateException("The spans of 01 CCRDSLAO total " + expectedOffset
                    + " bytes but the group is declared " + GROUP_LENGTH + "; check the fifteen xxxO "
                    + "PICTURE widths against app/cpy-bms/COCRDSL.CPY");
        }

        return spans;
    }

    /**
     * The complete byte geometry of {@code 01 CCRDSLAO}, in ascending offset order, {@code FILLER} spans
     * included.
     *
     * @return an unmodifiable list of 91 spans totalling {@link #GROUP_LENGTH} bytes; never {@code null}
     */
    @JsonIgnore
    public static List<SpanDescriptor> groupGeometry() {
        return GROUP_GEOMETRY;
    }

    /**
     * The {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} items of one screen field - the
     * {@code DSATTS}/{@code MAPATTS=(COLOR,HILIGHT,PS,VALIDN)} set the {@code CCRDSLA DFHMDI} declares,
     * held in the copybook's byte order: colour, then programmed symbols, then highlight, then validation.
     *
     * <p>{@link BmsAttributes#DFHDFCOL} and {@link BmsAttributes#DFHDFHI} are both that same byte, so the
     * initial state is simultaneously "untouched" and "default", exactly as on the terminal.
     */
    public static final class FieldAttributes {
        private byte colour = LOW_VALUES_BYTE;

        private byte ps = LOW_VALUES_BYTE;

        private byte hilight = LOW_VALUES_BYTE;

        private byte validn = LOW_VALUES_BYTE;

        /**
         * Creates a quad in the state {@code MOVE LOW-VALUES TO CCRDSLAO} leaves it in.
         */
        public FieldAttributes() {
        }

        /**
         * Copies an existing quad, so echoing a request's attributes into a response cannot alias them.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A source attribute quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /**
         * The {@code xxxC} colour byte - the item {@code app/cpy/CSSETATY.cpy:L21-L22} moves {@code DFHRED}
         * into, and the one {@code COCRDSLC} writes at {@code L529-L530}, {@code L534}, {@code L538},
         * {@code L544}, {@code L550}, {@code L554} and {@code L556}.
         *
         * @return the colour attribute byte
         */
        public byte getColour() {
            return colour;
        }

        public void setColour(byte colour) {
            this.colour = colour;
        }

        public byte getPs() {
            return ps;
        }

        public void setPs(byte ps) {
            this.ps = ps;
        }

        public byte getHilight() {
            return hilight;
        }

        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        public byte getValidn() {
            return validn;
        }

        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Whether the colour item currently holds {@link BmsAttributes#DFHRED}, that is whether this field
         * is painted as being in error.
         *
         * @return {@code true} when the colour item holds the red attribute byte
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the colour item still holds the default-colour byte, which is also {@code LOW-VALUES}.
         *
         * @return {@code true} when the colour item holds {@link BmsAttributes#DFHDFCOL}
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Restores all four items to {@code LOW-VALUES}, reproducing the effect of
         * {@code MOVE LOW-VALUES TO CCRDSLAO} on this field's quad.
         */
        public void resetToLowValues() {
            this.colour = LOW_VALUES_BYTE;
            this.ps = LOW_VALUES_BYTE;
            this.hilight = LOW_VALUES_BYTE;
            this.validn = LOW_VALUES_BYTE;
        }

        /**
         * Renders the quad with the {@code DFHBMSCA} mnemonics where one is known, for a parity report.
         *
         * @return the description; never {@code null} and never empty
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        @Override
        public String toString() {
            return "FieldAttributes[" + describe() + "]";
        }
    }

    // Each is held at exactly its declared width, space-padded on the right and truncated on the right,
    // because that is what a PIC X receiver does - and each is stored through FixedWidthCodec rather than
    // by assignment so the direction of the truncation is a decision the code makes rather than one it
    // stumbles into.

    private String trnnameo;

    private String title01o;

    private String curdateo;

    private String pgmnameo;

    private String title02o;

    private String curtimeo;

    private String acctsido;

    private String cardsido;

    private String crdnameo;

    private String crdstcdo;

    private String expmono;

    private String expyearo;

    private String infomsgo;

    private String errmsgo;

    private String fkeyso;

    private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

    private CardScreenState cardScreenState;

    private NavigationContext navigationContext;

    private ThisProgCommarea thisProgCommarea = ThisProgCommarea.initialized();

    private String nextProgram;

    private String nextMapset;

    private String nextMap;

    private String cursorField;

    /**
     * Creates the payload in the state {@code app/cbl/COCRDSLC.cbl:428}'s
     * {@code MOVE LOW-VALUES TO CCRDSLAO} leaves the group in: every one of the fifteen data items
     * {@code LOW-VALUES}-filled to its declared width, and all sixty attribute bytes {@code 0x00}.
     *
     * <p>{@code LOW-VALUES} rather than spaces, deliberately.
     */
    public CardSelectResponse() {
        initializeGroup();
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
        this.nextProgram = CardScreenState.spaces(NEXT_PROGRAM_LENGTH);
        this.nextMapset = CardScreenState.spaces(NEXT_MAPSET_LENGTH);
        this.nextMap = CardScreenState.spaces(NEXT_MAP_LENGTH);
    }

    /**
     * Creates the payload with all fifteen data items supplied, each stored through the {@code PIC X} move
     * rule and therefore held at exactly its declared width.
     *
     * @param trnnameo {@code TRNNAMEO PIC X(4)}
     * @param title01o {@code TITLE01O PIC X(40)}
     * @param curdateo {@code CURDATEO PIC X(8)}
     * @param pgmnameo {@code PGMNAMEO PIC X(8)}
     * @param title02o {@code TITLE02O PIC X(40)}
     * @param curtimeo {@code CURTIMEO PIC X(8)}
     * @param acctsido {@code ACCTSIDO PIC X(11)}
     * @param cardsido {@code CARDSIDO PIC X(16)}
     * @param crdnameo {@code CRDNAMEO PIC X(50)}
     * @param crdstcdo {@code CRDSTCDO PIC X(1)}
     * @param expmono {@code EXPMONO PIC X(2)}
     * @param expyearo {@code EXPYEARO PIC X(4)}
     * @param infomsgo {@code INFOMSGO PIC X(40)}
     * @param errmsgo {@code ERRMSGO PIC X(80)}
     * @param fkeyso {@code FKEYSO PIC X(75)}
     * @throws NullPointerException if any argument is {@code null}; COBOL has no absent state, so the
     *     caller must say whether it means spaces or {@code LOW-VALUES}
     */
    public CardSelectResponse(String trnnameo,
                              String title01o,
                              String curdateo,
                              String pgmnameo,
                              String title02o,
                              String curtimeo,
                              String acctsido,
                              String cardsido,
                              String crdnameo,
                              String crdstcdo,
                              String expmono,
                              String expyearo,
                              String infomsgo,
                              String errmsgo,
                              String fkeyso) {
        this();
        setTrnnameo(trnnameo);
        setTitle01o(title01o);
        setCurdateo(curdateo);
        setPgmnameo(pgmnameo);
        setTitle02o(title02o);
        setCurtimeo(curtimeo);
        setAcctsido(acctsido);
        setCardsido(cardsido);
        setCrdnameo(crdnameo);
        setCrdstcdo(crdstcdo);
        setExpmono(expmono);
        setExpyearo(expyearo);
        setInfomsgo(infomsgo);
        setErrmsgo(errmsgo);
        setFkeyso(fkeyso);
    }

    /**
     * Copies an existing payload member for member, including the fifteen attribute quads.
     *
     * @param other the payload to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardSelectResponse(CardSelectResponse other) {
        Objects.requireNonNull(other, "A source payload is required to copy one");
        this.trnnameo = other.trnnameo;
        this.title01o = other.title01o;
        this.curdateo = other.curdateo;
        this.pgmnameo = other.pgmnameo;
        this.title02o = other.title02o;
        this.curtimeo = other.curtimeo;
        this.acctsido = other.acctsido;
        this.cardsido = other.cardsido;
        this.crdnameo = other.crdnameo;
        this.crdstcdo = other.crdstcdo;
        this.expmono = other.expmono;
        this.expyearo = other.expyearo;
        this.infomsgo = other.infomsgo;
        this.errmsgo = other.errmsgo;
        this.fkeyso = other.fkeyso;
        for (ScreenField field : ScreenField.values()) {
            this.attributes.put(field, new FieldAttributes(other.attributes.get(field)));
        }
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.thisProgCommarea = other.thisProgCommarea;
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.cursorField = other.cursorField;
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCRDSLAO} ({@code app/cbl/COCRDSLC.cbl:428}): every data item
     * {@code LOW-VALUES}-filled to its declared width and every attribute quad back to {@code 0x00}.
     *
     * <p>The navigation carriers are deliberately untouched: the COBOL statement covers the map area only,
     * and {@code CC-WORK-AREA} and the commarea live outside it.
     */
    public void initializeGroup() {
        this.trnnameo = CardScreenState.lowValues(TRNNAMEO_LENGTH);
        this.title01o = CardScreenState.lowValues(TITLE01O_LENGTH);
        this.curdateo = CardScreenState.lowValues(CURDATEO_LENGTH);
        this.pgmnameo = CardScreenState.lowValues(PGMNAMEO_LENGTH);
        this.title02o = CardScreenState.lowValues(TITLE02O_LENGTH);
        this.curtimeo = CardScreenState.lowValues(CURTIMEO_LENGTH);
        this.acctsido = CardScreenState.lowValues(ACCTSIDO_LENGTH);
        this.cardsido = CardScreenState.lowValues(CARDSIDO_LENGTH);
        this.crdnameo = CardScreenState.lowValues(CRDNAMEO_LENGTH);
        this.crdstcdo = CardScreenState.lowValues(CRDSTCDO_LENGTH);
        this.expmono = CardScreenState.lowValues(EXPMONO_LENGTH);
        this.expyearo = CardScreenState.lowValues(EXPYEARO_LENGTH);
        this.infomsgo = CardScreenState.lowValues(INFOMSGO_LENGTH);
        this.errmsgo = CardScreenState.lowValues(ERRMSGO_LENGTH);
        this.fkeyso = CardScreenState.lowValues(FKEYSO_LENGTH);
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            if (quad == null) {
                attributes.put(field, new FieldAttributes());
            } else {
                quad.resetToLowValues();
            }
        }
    }

    /**
     * Applies the COBOL alphanumeric {@code MOVE} rule for one named item: right-pad with spaces when the
     * value is short, truncate on the right when it is long, so the stored image is always exactly the
     * declared width.
     *
     * @param value the sending value; may be shorter or longer than the receiver, and may be empty
     * @param itemName the receiving item's COBOL name, used only in the diagnostic
     * @param itemWidth the receiver's declared width
     * @return an image of exactly {@code itemWidth} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String moveIntoPicX(String value, String itemName, int itemWidth) {
        Objects.requireNonNull(value, () -> "A value is required for " + itemName + " PIC X("
                + itemWidth + "); COBOL has no absent state, so move an empty string for SPACES or "
                + "CardScreenState.lowValues(" + itemWidth + ") for LOW-VALUES");
        return PIC_X_CODEC.movePicX(value, itemWidth);
    }

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown after the {@code 'Tran:'} literal.
     *
     * @return the image, exactly {@value #TRNNAMEO_LENGTH} characters
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return trnnameo;
    }

    /**
     * Stores {@code TRNNAMEO}, padded or right-truncated to {@value #TRNNAMEO_LENGTH} characters.
     *
     * @param trnnameo the sending value
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        this.trnnameo = moveIntoPicX(trnnameo, ScreenField.TRNNAME.cobolName(), TRNNAMEO_LENGTH);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the upper heading.
     *
     * @return the image, exactly {@value #TITLE01O_LENGTH} characters
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return title01o;
    }

    /**
     * Stores {@code TITLE01O}, padded or right-truncated to {@value #TITLE01O_LENGTH} characters.
     *
     * @param title01o the sending value
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        this.title01o = moveIntoPicX(title01o, ScreenField.TITLE01.cobolName(), TITLE01O_LENGTH);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}.
     *
     * @return the image, exactly {@value #CURDATEO_LENGTH} characters
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return curdateo;
    }

    /**
     * Stores {@code CURDATEO}, padded or right-truncated to {@value #CURDATEO_LENGTH} characters.
     *
     * @param curdateo the sending value
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        this.curdateo = moveIntoPicX(curdateo, ScreenField.CURDATE.cobolName(), CURDATEO_LENGTH);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown after the {@code 'Prog:'} literal.
     *
     * @return the image, exactly {@value #PGMNAMEO_LENGTH} characters
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return pgmnameo;
    }

    /**
     * Stores {@code PGMNAMEO}, padded or right-truncated to {@value #PGMNAMEO_LENGTH} characters.
     *
     * @param pgmnameo the sending value
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        this.pgmnameo = moveIntoPicX(pgmnameo, ScreenField.PGMNAME.cobolName(), PGMNAMEO_LENGTH);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the lower heading.
     *
     * @return the image, exactly {@value #TITLE02O_LENGTH} characters
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return title02o;
    }

    /**
     * Stores {@code TITLE02O}, padded or right-truncated to {@value #TITLE02O_LENGTH} characters.
     *
     * @param title02o the sending value
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        this.title02o = moveIntoPicX(title02o, ScreenField.TITLE02.cobolName(), TITLE02O_LENGTH);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}.
     *
     * @return the image, exactly {@value #CURTIMEO_LENGTH} characters
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return curtimeo;
    }

    /**
     * Stores {@code CURTIMEO}, padded or right-truncated to {@value #CURTIMEO_LENGTH} characters.
     *
     * @param curtimeo the sending value
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        this.curtimeo = moveIntoPicX(curtimeo, ScreenField.CURTIME.cobolName(), CURTIMEO_LENGTH);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} - the account number, in the clear exactly as the symbolic map carries it.
     *
     * @return the image, exactly {@value #ACCTSIDO_LENGTH} characters
     */
    @JsonProperty("acctsid")
    public String getAcctsido() {
        return acctsido;
    }

    /**
     * Stores {@code ACCTSIDO}, padded or right-truncated to {@value #ACCTSIDO_LENGTH} characters.
     *
     * @param acctsido the sending value
     * @throws NullPointerException if {@code acctsido} is {@code null}
     */
    public void setAcctsido(String acctsido) {
        this.acctsido = moveIntoPicX(acctsido, ScreenField.ACCTSID.cobolName(), ACCTSIDO_LENGTH);
    }

    /**
     * {@code CARDSIDO PIC X(16)} - the full sixteen-digit card number, in the clear exactly as the symbolic
     * map carries it.
     *
     * @return the image, exactly {@value #CARDSIDO_LENGTH} characters
     */
    @JsonProperty("cardsid")
    public String getCardsido() {
        return cardsido;
    }

    /**
     * Stores {@code CARDSIDO}, padded or right-truncated to {@value #CARDSIDO_LENGTH} characters.
     *
     * @param cardsido the sending value
     * @throws NullPointerException if {@code cardsido} is {@code null}
     */
    public void setCardsido(String cardsido) {
        this.cardsido = moveIntoPicX(cardsido, ScreenField.CARDSID.cobolName(), CARDSIDO_LENGTH);
    }

    /**
     * {@code CRDNAMEO PIC X(50)} - the embossed name.
     *
     * @return the image, exactly {@value #CRDNAMEO_LENGTH} characters
     */
    @JsonProperty("crdname")
    public String getCrdnameo() {
        return crdnameo;
    }

    /**
     * Stores {@code CRDNAMEO}, padded or right-truncated to {@value #CRDNAMEO_LENGTH} characters.
     *
     * @param crdnameo the sending value
     * @throws NullPointerException if {@code crdnameo} is {@code null}
     */
    public void setCrdnameo(String crdnameo) {
        this.crdnameo = moveIntoPicX(crdnameo, ScreenField.CRDNAME.cobolName(), CRDNAMEO_LENGTH);
    }

    /**
     * {@code CRDSTCDO PIC X(1)} - the active status shown after {@code 'Card Active Y/N : '}.
     *
     * @return the image, exactly {@value #CRDSTCDO_LENGTH} character
     */
    @JsonProperty("crdstcd")
    public String getCrdstcdo() {
        return crdstcdo;
    }

    /**
     * Stores {@code CRDSTCDO}, padded or right-truncated to {@value #CRDSTCDO_LENGTH} character.
     *
     * @param crdstcdo the sending value
     * @throws NullPointerException if {@code crdstcdo} is {@code null}
     */
    public void setCrdstcdo(String crdstcdo) {
        this.crdstcdo = moveIntoPicX(crdstcdo, ScreenField.CRDSTCD.cobolName(), CRDSTCDO_LENGTH);
    }

    /**
     * {@code EXPMONO PIC X(2)} - the expiry month.
     *
     * @return the image, exactly {@value #EXPMONO_LENGTH} characters
     */
    @JsonProperty("expmon")
    public String getExpmono() {
        return expmono;
    }

    /**
     * Stores {@code EXPMONO}, padded or right-truncated to {@value #EXPMONO_LENGTH} characters.
     *
     * @param expmono the sending value
     * @throws NullPointerException if {@code expmono} is {@code null}
     */
    public void setExpmono(String expmono) {
        this.expmono = moveIntoPicX(expmono, ScreenField.EXPMON.cobolName(), EXPMONO_LENGTH);
    }

    /**
     * {@code EXPYEARO PIC X(4)} - the expiry year.
     *
     * @return the image, exactly {@value #EXPYEARO_LENGTH} characters
     */
    @JsonProperty("expyear")
    public String getExpyearo() {
        return expyearo;
    }

    /**
     * Stores {@code EXPYEARO}, padded or right-truncated to {@value #EXPYEARO_LENGTH} characters.
     *
     * @param expyearo the sending value
     * @throws NullPointerException if {@code expyearo} is {@code null}
     */
    public void setExpyearo(String expyearo) {
        this.expyearo = moveIntoPicX(expyearo, ScreenField.EXPYEAR.cobolName(), EXPYEARO_LENGTH);
    }

    /**
     * {@code INFOMSGO PIC X(40)} - the informational line.
     *
     * @return the image, exactly {@value #INFOMSGO_LENGTH} characters
     */
    @JsonProperty("infomsg")
    public String getInfomsgo() {
        return infomsgo;
    }

    /**
     * Stores {@code INFOMSGO}, padded or right-truncated to {@value #INFOMSGO_LENGTH} characters.
     *
     * @param infomsgo the sending value
     * @throws NullPointerException if {@code infomsgo} is {@code null}
     */
    public void setInfomsgo(String infomsgo) {
        this.infomsgo = moveIntoPicX(infomsgo, ScreenField.INFOMSG.cobolName(), INFOMSGO_LENGTH);
    }

    /**
     * {@code ERRMSGO PIC X(80)} - the error line.
     *
     * @return the image, exactly {@value #ERRMSGO_LENGTH} characters
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return errmsgo;
    }

    /**
     * Stores {@code ERRMSGO}, padded or right-truncated to {@value #ERRMSGO_LENGTH} characters.
     *
     * @param errmsgo the sending value
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        this.errmsgo = moveIntoPicX(errmsgo, ScreenField.ERRMSG.cobolName(), ERRMSGO_LENGTH);
    }

    /**
     * {@code FKEYSO PIC X(75)} - the function-key legend.
     *
     * <p>{@code COCRDSLC} never writes it, so it holds {@code LOW-VALUES} unless a caller assigns
     * {@link #BMS_INITIAL_FKEYS}, which is the literal the mapset paints.
     *
     * @return the image, exactly {@value #FKEYSO_LENGTH} characters
     */
    @JsonProperty("fkeys")
    public String getFkeyso() {
        return fkeyso;
    }

    /**
     * Stores {@code FKEYSO}, padded or right-truncated to {@value #FKEYSO_LENGTH} characters.
     *
     * @param fkeyso the sending value
     * @throws NullPointerException if {@code fkeyso} is {@code null}
     */
    public void setFkeyso(String fkeyso) {
        this.fkeyso = moveIntoPicX(fkeyso, ScreenField.FKEYS.cobolName(), FKEYSO_LENGTH);
    }

    public String get(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required to read a data item");
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case ACCTSID -> acctsido;
            case CARDSID -> cardsido;
            case CRDNAME -> crdnameo;
            case CRDSTCD -> crdstcdo;
            case EXPMON -> expmono;
            case EXPYEAR -> expyearo;
            case INFOMSG -> infomsgo;
            case ERRMSG -> errmsgo;
            case FKEYS -> fkeyso;
        };
    }

    /**
     * Stores one data item by field, applying the same {@code PIC X} move rule the named setter applies.
     *
     * @param field the field to write
     * @param value the sending value
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    public void set(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required to write a data item");
        switch (field) {
            case TRNNAME -> setTrnnameo(value);
            case TITLE01 -> setTitle01o(value);
            case CURDATE -> setCurdateo(value);
            case PGMNAME -> setPgmnameo(value);
            case TITLE02 -> setTitle02o(value);
            case CURTIME -> setCurtimeo(value);
            case ACCTSID -> setAcctsido(value);
            case CARDSID -> setCardsido(value);
            case CRDNAME -> setCrdnameo(value);
            case CRDSTCD -> setCrdstcdo(value);
            case EXPMON -> setExpmono(value);
            case EXPYEAR -> setExpyearo(value);
            case INFOMSG -> setInfomsgo(value);
            case ERRMSG -> setErrmsgo(value);
            case FKEYS -> setFkeyso(value);
        }
    }

    /**
     * The mutable attribute quad of one field: its {@code xxxC}, {@code xxxP}, {@code xxxH} and
     * {@code xxxV} items.
     *
     * @param field the field whose quad is wanted
     * @return that field's quad, never {@code null} and never a copy
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required to reach its attribute quad");
        return attributes.get(field);
    }

    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * The sixty attribute items as a flat map from COBOL item name to byte, in the copybook's offset order:
     * {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV}, then {@code TITLE01C} and so
     * on.
     *
     * @return an unmodifiable, insertion-ordered snapshot of 60 entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, Byte> attributeItems() {
        Map<String, Byte> items = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            items.put(field.colourItemName(), quad.getColour());
            items.put(field.psItemName(), quad.getPs());
            items.put(field.hilightItemName(), quad.getHilight());
            items.put(field.validnItemName(), quad.getValidn());
        }
        return Collections.unmodifiableMap(items);
    }

    /**
     * The fifteen data items as a map from COBOL item name to image, in the copybook's declaration order.
     *
     * @return an unmodifiable, insertion-ordered snapshot of {@value #FIELD_COUNT} entries; never
     *     {@code null}
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.cobolName(), get(field));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:432-433}: {@code MOVE CCDA-TITLE01 TO TITLE01O} and
     * {@code MOVE CCDA-TITLE02 TO TITLE02O}.
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:434-435}: {@code MOVE LIT-THISTRANID TO TRNNAMEO} and
     * {@code MOVE LIT-THISPGM TO PGMNAMEO}.
     */
    public void applyScreenIdentity() {
        setTrnnameo(THIS_TRANID);
        setPgmnameo(THIS_PROGRAM);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:443} and {@code L449}:
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} and {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO}.
     *
     * @param dateHeader the {@code CSDAT01Y} header {@code COCRDSLC} copies at {@code L218}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A date header is required; this payload never reads a clock "
                + "of its own, so the instant must be supplied by the caller");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * Applies a highlight decision to one field: {@code DFHRED} into its {@code xxxC} item and, when the
     * decision says so, {@code '*'} into its {@code xxxO} item.
     *
     * @param field the field to repaint
     * @param highlight the decision to apply; {@link FieldHighlight#untouched()} decisions change nothing
     * @throws NullPointerException if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a different field, which would mean it was
     *     resolved for one field and applied to another
     */
    public void applyHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A screen field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; use "
                + "FieldHighlight.none(...) to express 'change nothing'");

        String decidedFor = highlight.screenFieldPrefix();
        if (!decidedFor.isEmpty() && !decidedFor.equals(field.dfhmdfLabel())) {
            throw new IllegalArgumentException("The highlight was resolved for " + decidedFor
                    + " but is being applied to " + field.dfhmdfLabel()
                    + "; CSSETATY qualifies both of its moves with one field, so a decision cannot be "
                    + "carried across fields");
        }

        if (highlight.colourItemAssigned()) {
            attributes(field).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            set(field, highlight.outputItemValue());
        }
    }

    /**
     * Resolves the highlight for one field and applies it, in one call.
     *
     * @param field the field whose edit outcome is being reported
     * @param state the field's validation state
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is {@link NavigationContext#PGM_CONTEXT_REENTER}
     * @return the decision that was applied, so a caller can log or assert it; never {@code null}
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public FieldHighlight applyHighlight(ScreenField field, FieldValidationState state,
            boolean reenter) {
        Objects.requireNonNull(field, "A screen field is required to resolve a highlight");
        Objects.requireNonNull(state, "A validation state is required; use "
                + "FieldValidationState.of(notOk, blank) to derive one from the two 88-levels");

        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.dfhmdfLabel(), MAP_NAME);
        applyHighlight(field, highlight);
        return highlight;
    }

    /**
     * The {@code CVCRD01Y} work area - {@code CCARD-AID}, the next-screen triple, the two message lines and
     * the three search identifiers - which {@code COCRDSLC} copies at {@code app/cbl/COCRDSLC.cbl:194}.
     *
     * @return the work area, never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = Objects.requireNonNull(cardScreenState,
                "A card screen state is required; COCRDSLC always has an initialised CC-WORK-AREA");
    }

    /**
     * {@code WS-THIS-PROGCOMMAREA} as this response returns it - the twelve bytes
     * {@code app/cbl/COCRDSLC.cbl:397-400} appends to the communication area.
     *
     * @return the trailer; never {@code null}
     */
    public ThisProgCommarea getThisProgCommarea() {
        return thisProgCommarea;
    }

    /**
     * Sets {@code WS-THIS-PROGCOMMAREA}.
     *
     * @param thisProgCommarea the trailer, or {@code null} for the initialised twelve spaces
     */
    public void setThisProgCommarea(ThisProgCommarea thisProgCommarea) {
        this.thisProgCommarea =
                thisProgCommarea == null ? ThisProgCommarea.initialized() : thisProgCommarea;
    }

    /**
     * This screen's presentation metadata, projected into the shared envelope every online response
     * publishes: the fifteen attribute quads, keyed by {@code DFHMDF} label in copybook order, and the
     * colour of the error line.
     *
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata() {
        return screenMetadata(null);
    }

    /**
     * The same metadata with the basic attribute byte taken from the input area, which is where
     * {@code 1300-SETUP-SCREEN-ATTRS} actually writes it.
     *
     * @param inputArea the input map area {@code CCRDSLAI} as the program left it, or {@code null}
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata(CardSelectRequest inputArea) {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            quads.put(field.dfhmdfLabel(),
                    ScreenMetadata.FieldMetadata.of(quad.getColour(),
                            basicAttributeOf(inputArea, field, quad),
                            quad.getHilight(),
                            quad.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                attributes.get(ScreenField.ERRMSG).getColour(),
                false,
                quads);
    }

    private static byte basicAttributeOf(CardSelectRequest inputArea, ScreenField field,
            FieldAttributes quad) {
        if (inputArea == null) {
            return quad.getPs();
        }
        for (CardSelectRequest.ScreenField inputField : CardSelectRequest.ScreenField.values()) {
            if (inputField.name().equals(field.name())) {
                return inputArea.metadata(inputField).getAttribute();
            }
        }
        return quad.getPs();
    }

    /**
     * The {@code DFHMDF} label of the field {@code 1300-SETUP-SCREEN-ATTRS} aimed the cursor at, or
     * {@code null} when this turn made no cursor request.
     *
     * @return the label, for example {@code ACCTSID}; or {@code null} for no cursor request
     */
    @JsonIgnore
    public String getCursorField() {
        return cursorField;
    }

    /**
     * Records where {@code MOVE -1 TO xxxL OF CCRDSLAI} aimed the cursor, so {@link #screenMetadata()} can
     * publish it.
     *
     * @param cursorField the {@code DFHMDF} label, or {@code null} for no cursor request
     */
    public void setCursorField(String cursorField) {
        this.cursorField = cursorField;
    }

    /**
     * The {@code CARDDEMO-COMMAREA} that {@code COCRDSLC} copies at {@code app/cbl/COCRDSLC.cbl:198} and
     * passes on the {@code XCTL} at {@code L333}: the from and to transaction and program, the user
     * identity and type, {@code CDEMO-PGM-CONTEXT}, and the carried customer, account and card identifiers.
     *
     * @return the commarea, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A navigation context is required; use NavigationContext.empty() when no commarea was "
                + "received");
    }

    /**
     * The program the client should call next - the substitute for
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COCRDSLC.cbl:331}.
     *
     * @return the image, exactly {@link #NEXT_PROGRAM_LENGTH} characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Stores the next program, padded or right-truncated to {@link #NEXT_PROGRAM_LENGTH} characters.
     *
     * @param nextProgram the program name
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram =
                moveIntoPicX(nextProgram, "CCARD-NEXT-PROG", NEXT_PROGRAM_LENGTH);
    }

    /**
     * The mapset of the next screen.
     *
     * @return the image, exactly {@link #NEXT_MAPSET_LENGTH} characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset, padded or right-truncated to {@link #NEXT_MAPSET_LENGTH} characters.
     *
     * @param nextMapset the mapset name
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = moveIntoPicX(nextMapset, "CCARD-NEXT-MAPSET", NEXT_MAPSET_LENGTH);
    }

    public String getNextMap() {
        return nextMap;
    }

    /**
     * Stores the next map, padded or right-truncated to {@link #NEXT_MAP_LENGTH} characters.
     *
     * <p>An opaque token, and the one the preserved {@code LIT-CCLISTMAP} defect travels in: a caller
     * routing back to the card list legitimately sends {@code "CCRDSLA"} here, because that is the value
     * {@code app/cbl/COCRDSLC.cbl:178} declares.
     *
     * @param nextMap the map name
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = moveIntoPicX(nextMap, "CCARD-NEXT-MAP", NEXT_MAP_LENGTH);
    }

    public void applyNextTarget(String nextProgram, String nextMapset, String nextMap) {
        setNextProgram(nextProgram);
        setNextMapset(nextMapset);
        setNextMap(nextMap);
    }

    /**
     * Reproduces {@code app/cbl/COCRDSLC.cbl:588-590}, where the program names itself as the next target so
     * the user stays on the card-detail screen: {@code MOVE LIT-THISPGM TO CCARD-NEXT-PROG},
     * {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET} and {@code MOVE LIT-THISMAP TO CCARD-NEXT-MAP}.
     */
    public void applyThisScreenAsNextTarget() {
        applyNextTarget(THIS_PROGRAM, THIS_MAPSET, MAP_NAME);
    }

    /**
     * Renders every field's provenance and current image, one line each, for a parity report or a review
     * note.
     *
     * @return the description; never {@code null} and never empty
     */
    @JsonIgnore
    public String describe() {
        StringBuilder rendered = new StringBuilder(2048)
                .append("CCRDSLAO (")
                .append(GROUP_LENGTH)
                .append(" bytes, ")
                .append(FIELD_COUNT)
                .append(" named DFHMDF fields of 31, map ")
                .append(MAP_NAME)
                .append(", program ")
                .append(THIS_PROGRAM)
                .append(", transaction ")
                .append(THIS_TRANID)
                .append(')');

        for (ScreenField field : ScreenField.values()) {
            rendered.append(System.lineSeparator())
                    .append("  ")
                    .append(field.describe())
                    .append(" = [")
                    .append(REDACTED_FIELDS.contains(field) ? redacted(get(field)) : get(field))
                    .append("] ")
                    .append(attributes.get(field).describe());
        }

        return rendered.append(System.lineSeparator())
                .append("  next: program=[")
                .append(nextProgram)
                .append("] mapset=[")
                .append(nextMapset)
                .append("] map=[")
                .append(nextMap)
                .append(']')
                .toString();
    }

    /**
     * Two payloads are equal when all fifteen data items, all fifteen attribute quads, both carriers and
     * the next-screen triple are equal.
     *
     * @param other the object to compare with
     * @return {@code true} when every member is equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardSelectResponse response)) {
            return false;
        }
        return trnnameo.equals(response.trnnameo)
                && title01o.equals(response.title01o)
                && curdateo.equals(response.curdateo)
                && pgmnameo.equals(response.pgmnameo)
                && title02o.equals(response.title02o)
                && curtimeo.equals(response.curtimeo)
                && acctsido.equals(response.acctsido)
                && cardsido.equals(response.cardsido)
                && crdnameo.equals(response.crdnameo)
                && crdstcdo.equals(response.crdstcdo)
                && expmono.equals(response.expmono)
                && expyearo.equals(response.expyearo)
                && infomsgo.equals(response.infomsgo)
                && errmsgo.equals(response.errmsgo)
                && fkeyso.equals(response.fkeyso)
                && attributes.equals(response.attributes)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap);
    }

    /**
     * A hash consistent with {@link #equals(Object)} over the same members.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo, acctsido,
                cardsido, crdnameo, crdstcdo, expmono, expyearo, infomsgo, errmsgo, fkeyso, attributes,
                cardScreenState, navigationContext, nextProgram, nextMapset, nextMap);
    }

    /**
     * A single-line rendering naming the map and the four fields that identify the screen's subject.
     *
     * @return the rendering; never {@code null} and never empty
     */
    @Override
    public String toString() {
        return "CardSelectResponse[map=" + MAP_NAME
                + ", ACCTSIDO=" + SensitiveDiagnostics.maskIdentifier(acctsido)
                + ", CARDSIDO=" + SensitiveDiagnostics.maskPan(cardsido)
                + ", CRDSTCDO=" + crdstcdo
                + ", ERRMSGO=" + errmsgo
                + ']';
    }

    private static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<ScreenField> REDACTED_FIELDS =
            Collections.unmodifiableSet(EnumSet.of(ScreenField.ACCTSID, ScreenField.CARDSID,
                    ScreenField.CRDNAME));

    private static String redacted(String value) {
        return REDACTED_VALUE + "(" + value.length() + ")";
    }
}
