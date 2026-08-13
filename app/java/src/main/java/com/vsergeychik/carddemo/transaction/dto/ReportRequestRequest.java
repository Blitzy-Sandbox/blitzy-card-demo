package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound REST payload of {@code POST /api/reports} - CSD transaction {@link #TRANSACTION_ID}, program
 * {@link #PROGRAM_NAME} - projected field for field from the {@code xxxI} items of {@code 01 CORPT0AI} in
 * {@code app/cpy-bms/CORPT00.CPY} and from the name-labelled {@code DFHMDF} definitions of
 * {@code app/bms/CORPT00.bms}.
 *
 * <p>{@code FUNCTION NUMVAL-C} parsing, {@code FUNCTION DATE-OF-INTEGER} / {@code INTEGER-OF-DATE} (line
 * 229) and the two {@code CALL 'CSUTLDTC'} sites (lines 392 and 412) belong to the controller and to the
 * date utility service, never to this payload.
 *
 * @param trnname {@code TRNNAMEI PIC X(4)}: the transaction identifier shown at (1,7)
 * @param title01 {@code TITLE01I PIC X(40)}: the first title line at (1,21)
 * @param curdate {@code CURDATEI PIC X(8)}: the current date at (1,71), {@code mm/dd/yy}
 * @param pgmname {@code PGMNAMEI PIC X(8)}: the program name shown at (2,7)
 * @param title02 {@code TITLE02I PIC X(40)}: the second title line at (2,21)
 * @param curtime {@code CURTIMEI PIC X(8)}: the current time at (2,71), {@code hh:mm:ss}
 * @param monthly {@code MONTHLYI PIC X(1)}: select the current-month report; tested against
 *     {@code SPACES AND LOW-VALUES} at line 213
 * @param yearly {@code YEARLYI PIC X(1)}: select the current-year report; tested at line 239
 * @param custom {@code CUSTOMI PIC X(1)}: select the custom date-range report
 * @param sdtmm {@code SDTMMI PIC X(2)}: start-date month, compared against {@code '12'}
 * @param sdtdd {@code SDTDDI PIC X(2)}: start-date day, compared against {@code '31'}
 * @param sdtyyyy {@code SDTYYYYI PIC X(4)}: start-date year, class tested only
 * @param edtmm {@code EDTMMI PIC X(2)}: end-date month, compared against {@code '12'}
 * @param edtdd {@code EDTDDI PIC X(2)}: end-date day, compared against {@code '31'}
 * @param edtyyyy {@code EDTYYYYI PIC X(4)}: end-date year, class tested only
 * @param confirm {@code CONFIRMI PIC X(1)}: {@code 'Y'}, {@code 'y'}, {@code 'N'} or {@code 'n'}; anything
 *     else is rejected at line 484
 * @param errmsg {@code ERRMSGI PIC X(78)}: the error line at (23,1), returned because the field carries
 *     {@code FSET}
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, exactly
 *     {@value NavigationContext#COMMAREA_LENGTH} bytes, or {@code null} when no communication area was passed
 * @param aid the resolved {@code EIBAID} key indication as a token, at most {@value #AID_LENGTH} characters
 *     - {@code 'ENTER'} or {@code 'PFK03'} for the two arms this screen acts on
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record ReportRequestRequest(
        @Size(max = TRNNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String trnname,
        @Size(max = TITLE01_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String title01,
        @Size(max = CURDATE_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String curdate,
        @Size(max = PGMNAME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String pgmname,
        @Size(max = TITLE02_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String title02,
        @Size(max = CURTIME_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String curtime,
        @Size(max = MONTHLY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String monthly,
        @Size(max = YEARLY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String yearly,
        @Size(max = CUSTOM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String custom,
        @Size(max = SDTMM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtmm,
        @Size(max = SDTDD_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtdd,
        @Size(max = SDTYYYY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String sdtyyyy,
        @Size(max = EDTMM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtmm,
        @Size(max = EDTDD_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtdd,
        @Size(max = EDTYYYY_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String edtyyyy,
        @Size(max = CONFIRM_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String confirm,
        @Size(max = ERRMSG_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String errmsg,
        NavigationContext navigationContext,
        @Size(max = AID_LENGTH, message = PUBLIC_LENGTH_MESSAGE) String aid) {
    /**
     * The message every width constraint above declares, and the only text a rejected field publishes.
     */
    public static final String PUBLIC_LENGTH_MESSAGE = "must be at most {max} characters";

    /**
     * The BMS mapset: {@code CORPT00 DFHMSD} at {@code app/bms/CORPT00.bms:19}.
     */
    public static final String MAPSET_NAME = "CORPT00";

    /**
     * The BMS map: {@code CORPT0A DFHMDI} at {@code app/bms/CORPT00.bms:26}.
     */
    public static final String MAP_NAME = "CORPT0A";

    /**
     * The symbolic-map input group: {@code 01 CORPT0AI} at {@code app/cpy-bms/CORPT00.CPY:17}.
     */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "CORPT0AI";

    /**
     * The CSD transaction that reaches {@link #PROGRAM_NAME}:
     * {@code DEFINE TRANSACTION(CR00) PROGRAM(CORPT00C)} at {@code app/csd/CARDDEMO.CSD:409-410}, matching
     * {@code WS-TRANID PIC X(04) VALUE 'CR00'} at {@code app/cbl/CORPT00C.cbl:38}.
     */
    public static final String TRANSACTION_ID = "CR00";

    /**
     * The COBOL program this payload is projected from: {@code PROGRAM-ID. CORPT00C} at
     * {@code app/cbl/CORPT00C.cbl:24}, matching {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} at line 37.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * Screen fields carrying a {@code DFHMDF} name label, and therefore payload members: 17.
     */
    public static final int FIELD_COUNT = 17;

    /**
     * The leading {@code 02 FILLER PIC X(12)} of {@code app/cpy-bms/CORPT00.CPY:18}.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * {@code 02 xxxL COMP PIC S9(4)} occupies two bytes, not four.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * {@code 02 xxxF PICTURE X} - one byte, redefined by {@code 03 xxxA PICTURE X}.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * {@code 02 FILLER PICTURE X(4)} - the reserved span between the flag byte and the data.
     */
    public static final int RESERVED_FILLER_LENGTH = 4;

    public static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /**
     * {@code TRNNAMEI PIC X(4)}; {@code TRNNAME DFHMDF LENGTH=4} at {@code CORPT00.bms:34-37}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}; {@code TITLE01 DFHMDF LENGTH=40} at {@code CORPT00.bms:38-41}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}; {@code CURDATE DFHMDF LENGTH=8} at {@code CORPT00.bms:47-51}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}; {@code PGMNAME DFHMDF LENGTH=8} at {@code CORPT00.bms:57-60}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}; {@code TITLE02 DFHMDF LENGTH=40} at {@code CORPT00.bms:61-64}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}; {@code CURTIME DFHMDF LENGTH=8} at {@code CORPT00.bms:70-74}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code MONTHLYI PIC X(1)}; {@code MONTHLY DFHMDF LENGTH=1} at {@code CORPT00.bms:80-85}.
     */
    public static final int MONTHLY_LENGTH = 1;

    /**
     * {@code YEARLYI PIC X(1)}; {@code YEARLY DFHMDF LENGTH=1} at {@code CORPT00.bms:94-99}.
     */
    public static final int YEARLY_LENGTH = 1;

    /**
     * {@code CUSTOMI PIC X(1)}; {@code CUSTOM DFHMDF LENGTH=1} at {@code CORPT00.bms:108-113}.
     */
    public static final int CUSTOM_LENGTH = 1;

    /**
     * {@code SDTMMI PIC X(2)}; {@code SDTMM DFHMDF LENGTH=2} at {@code CORPT00.bms:127-132}.
     */
    public static final int SDTMM_LENGTH = 2;

    /**
     * {@code SDTDDI PIC X(2)}; {@code SDTDD DFHMDF LENGTH=2} at {@code CORPT00.bms:138-143}.
     */
    public static final int SDTDD_LENGTH = 2;

    /**
     * {@code SDTYYYYI PIC X(4)}; {@code SDTYYYY DFHMDF LENGTH=4} at {@code CORPT00.bms:149-154}.
     */
    public static final int SDTYYYY_LENGTH = 4;

    /**
     * {@code EDTMMI PIC X(2)}; {@code EDTMM DFHMDF LENGTH=2} at {@code CORPT00.bms:166-171}.
     */
    public static final int EDTMM_LENGTH = 2;

    /**
     * {@code EDTDDI PIC X(2)}; {@code EDTDD DFHMDF LENGTH=2} at {@code CORPT00.bms:177-182}.
     */
    public static final int EDTDD_LENGTH = 2;

    /**
     * {@code EDTYYYYI PIC X(4)}; {@code EDTYYYY DFHMDF LENGTH=4} at {@code CORPT00.bms:188-193}.
     */
    public static final int EDTYYYY_LENGTH = 4;

    /**
     * {@code CONFIRMI PIC X(1)}; {@code CONFIRM DFHMDF LENGTH=1} at {@code CORPT00.bms:206-210}.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}; {@code ERRMSG DFHMDF LENGTH=78} at {@code CORPT00.bms:218-221}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Characters in the {@code EIBAID} token carried by {@link #aid()}: five.
     */
    public static final int AID_LENGTH = 5;

    /**
     * Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field.
     */
    public static final String AID_FIELD = "EIBAID";

    public static final int PAYLOAD_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + MONTHLY_LENGTH + YEARLY_LENGTH
            + CUSTOM_LENGTH + SDTMM_LENGTH + SDTDD_LENGTH + SDTYYYY_LENGTH + EDTMM_LENGTH
            + EDTDD_LENGTH + EDTYYYY_LENGTH + CONFIRM_LENGTH + ERRMSG_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    /**
     * The {@code 02 FILLER PIC X(12)} TIOAPFX prefix span at offset zero, emitted as spaces.
     */
    public static final FieldSpan TIOAPFX_PREFIX_SPAN = FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH);

    private static final String SPACE = " ";

    private static final String LOW_VALUE = "\u0000";

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final String FLAG_ITEM_SUFFIX = "F";

    private static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    private static final String INPUT_ITEM_SUFFIX = "I";

    /**
     * The seventeen name-labelled {@code DFHMDF} fields of {@link #MAPSET_NAME}, in symbolic-map
     * declaration order, each carrying its four copybook item names, its declared width, its absolute
     * offsets within the {@link #SYMBOLIC_MAP_LENGTH}-byte image and the four {@link FieldSpan} descriptors
     * that address it.
     */
    public enum ScreenField {
        TRNNAME("TRNNAME", 12, TRNNAME_LENGTH, false, false),

        TITLE01("TITLE01", 23, TITLE01_LENGTH, false, false),

        CURDATE("CURDATE", 70, CURDATE_LENGTH, false, false),

        PGMNAME("PGMNAME", 85, PGMNAME_LENGTH, false, false),

        TITLE02("TITLE02", 100, TITLE02_LENGTH, false, false),

        CURTIME("CURTIME", 147, CURTIME_LENGTH, false, false),

        MONTHLY("MONTHLY", 162, MONTHLY_LENGTH, true, false),

        YEARLY("YEARLY", 170, YEARLY_LENGTH, true, false),

        CUSTOM("CUSTOM", 178, CUSTOM_LENGTH, true, false),

        SDTMM("SDTMM", 186, SDTMM_LENGTH, true, true),

        SDTDD("SDTDD", 195, SDTDD_LENGTH, true, true),

        SDTYYYY("SDTYYYY", 204, SDTYYYY_LENGTH, true, true),

        EDTMM("EDTMM", 215, EDTMM_LENGTH, true, true),

        EDTDD("EDTDD", 224, EDTDD_LENGTH, true, true),

        EDTYYYY("EDTYYYY", 233, EDTYYYY_LENGTH, true, true),

        CONFIRM("CONFIRM", 244, CONFIRM_LENGTH, true, false),

        ERRMSG("ERRMSG", 252, ERRMSG_LENGTH, false, false);

        private final String bmsName;
        private final String lengthItem;
        private final String flagItem;
        private final String attributeItem;
        private final String inputItem;
        private final int declaredLength;
        private final boolean unprotected;
        private final boolean numeric;
        private final FieldSpan lengthSpan;
        private final FieldSpan flagSpan;
        private final FieldSpan attributeSpan;
        private final FieldSpan reservedFillerSpan;
        private final FieldSpan inputSpan;

        ScreenField(String bmsName,
                    int lengthItemOffset,
                    int declaredLength,
                    boolean unprotected,
                    boolean numeric) {
            this.bmsName = bmsName;
            this.lengthItem = bmsName + LENGTH_ITEM_SUFFIX;
            this.flagItem = bmsName + FLAG_ITEM_SUFFIX;
            this.attributeItem = bmsName + ATTRIBUTE_ITEM_SUFFIX;
            this.inputItem = bmsName + INPUT_ITEM_SUFFIX;
            this.declaredLength = declaredLength;
            this.unprotected = unprotected;
            this.numeric = numeric;
            this.lengthSpan = FieldSpan.alphanumeric(lengthItem, lengthItemOffset,
                    LENGTH_ITEM_LENGTH);
            this.flagSpan = FieldSpan.alphanumeric(flagItem, lengthItemOffset + LENGTH_ITEM_LENGTH,
                    FLAG_ITEM_LENGTH);
            // 02 FILLER REDEFINES xxxF. / 03 xxxA PICTURE X. - one byte viewed twice, so the overlay is
            // taken from the flag span itself and cannot be given a wrong offset.
            this.attributeSpan = flagSpan.redefinedAs(attributeItem, PictureKind.ALPHANUMERIC);
            this.reservedFillerSpan = FieldSpan.filler(
                    lengthItemOffset + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH,
                    RESERVED_FILLER_LENGTH);
            this.inputSpan = FieldSpan.alphanumeric(inputItem,
                    lengthItemOffset + FIELD_PREFIX_LENGTH, declaredLength);
        }

        /**
         * The {@code DFHMDF} name label, verbatim - {@code TRNNAME}, {@code SDTYYYY} and so on.
         *
         * @return the screen field's BMS name, never {@code null}
         */
        public String bmsName() {
            return bmsName;
        }

        /**
         * The symbolic-map length item, as in {@code TRNNAMEL}.
         *
         * @return the {@code xxxL} item name, verbatim
         */
        public String lengthItem() {
            return lengthItem;
        }

        public String flagItem() {
            return flagItem;
        }

        /**
         * The attribute view that redefines the flag item, as in {@code TRNNAMEA}.
         *
         * @return the {@code xxxA} item name, verbatim
         */
        public String attributeItem() {
            return attributeItem;
        }

        /**
         * The symbolic-map input data item, as in {@code TRNNAMEI} - the payload item this field projects
         * to.
         *
         * @return the {@code xxxI} item name, verbatim
         */
        public String inputItem() {
            return inputItem;
        }

        /**
         * {@code n} of the input item's {@code PIC X(n)}, which the mapset repeats as its
         * {@code DFHMDF LENGTH=}.
         *
         * @return the declared character width, at least 1
         */
        public int declaredLength() {
            return declaredLength;
        }

        /**
         * Whether the field's {@code DFHMDF ATTRB} list includes {@code UNPROT}.
         *
         * @return {@code true} for an operator-writable field
         */
        public boolean unprotected() {
            return unprotected;
        }

        /**
         * Whether the field's {@code DFHMDF ATTRB} list includes {@code NUM}.
         *
         * @return {@code true} for a numeric-shifted field
         */
        public boolean numeric() {
            return numeric;
        }

        public FieldSpan lengthSpan() {
            return lengthSpan;
        }

        public FieldSpan flagSpan() {
            return flagSpan;
        }

        /**
         * The {@code REDEFINES} overlay {@code xxxA}, addressing the same byte as {@link #flagSpan()}.
         *
         * @return the attribute view's descriptor
         */
        public FieldSpan attributeSpan() {
            return attributeSpan;
        }

        /**
         * The {@value ReportRequestRequest#RESERVED_FILLER_LENGTH}-byte reserved {@code FILLER} between the
         * flag byte and the data.
         *
         * @return the reserved span's descriptor
         */
        public FieldSpan reservedFillerSpan() {
            return reservedFillerSpan;
        }

        public FieldSpan inputSpan() {
            return inputSpan;
        }

        /**
         * The COBOL {@code SPACES} figurative constant sized to this field - the value {@code MOVE SPACES}
         * leaves in it.
         *
         * @return exactly {@link #declaredLength()} spaces
         */
        public String spaces() {
            return SPACE.repeat(declaredLength);
        }

        /**
         * The COBOL {@code LOW-VALUES} figurative constant sized to this field, as
         * {@code MOVE LOW-VALUES TO CORPT0AO} at {@code app/cbl/CORPT00C.cbl:179} leaves it.
         *
         * @return exactly {@link #declaredLength()} {@code X'00'} characters
         */
        public String lowValues() {
            return LOW_VALUE.repeat(declaredLength);
        }
    }

    /**
     * The complete {@link #SYMBOLIC_MAP_LENGTH}-byte geometry of {@code 01 CORPT0AI}, in copybook
     * declaration order.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + FIELD_COUNT * 5);
        spans.add(TIOAPFX_PREFIX_SPAN);
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.lengthSpan());
            spans.add(field.flagSpan());
            spans.add(field.attributeSpan());
            spans.add(field.reservedFillerSpan());
            spans.add(field.inputSpan());
        }
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    /**
     * Normalises every screen field at construction time, so an instance either exists and fits the
     * {@link #SYMBOLIC_MAP_LENGTH}-byte symbolic map or does not exist at all.
     *
     * <p>Spaces is the honest no-key-resolved state, and an unrecognised or blank token is exactly what
     * {@code app/cbl/CORPT00C.cbl:190} answers with {@code WHEN OTHER}, so nothing is lost by filling it
     * in.
     */
    public ReportRequestRequest {
        trnname = normalise(trnname, ScreenField.TRNNAME);
        title01 = normalise(title01, ScreenField.TITLE01);
        curdate = normalise(curdate, ScreenField.CURDATE);
        pgmname = normalise(pgmname, ScreenField.PGMNAME);
        title02 = normalise(title02, ScreenField.TITLE02);
        curtime = normalise(curtime, ScreenField.CURTIME);
        monthly = normalise(monthly, ScreenField.MONTHLY);
        yearly = normalise(yearly, ScreenField.YEARLY);
        custom = normalise(custom, ScreenField.CUSTOM);
        sdtmm = normalise(sdtmm, ScreenField.SDTMM);
        sdtdd = normalise(sdtdd, ScreenField.SDTDD);
        sdtyyyy = normalise(sdtyyyy, ScreenField.SDTYYYY);
        edtmm = normalise(edtmm, ScreenField.EDTMM);
        edtdd = normalise(edtdd, ScreenField.EDTDD);
        edtyyyy = normalise(edtyyyy, ScreenField.EDTYYYY);
        confirm = normalise(confirm, ScreenField.CONFIRM);
        errmsg = normalise(errmsg, ScreenField.ERRMSG);
        aid = normaliseAid(aid);
    }

    private static String normaliseAid(String value) {
        if (value == null) {
            return SPACE.repeat(AID_LENGTH);
        }
        if (value.length() > AID_LENGTH) {
            throw new IllegalArgumentException(AID_FIELD + " is carried as a PIC X(" + AID_LENGTH
                    + ") token, matching common.PfKeyResolver.AID_TOKEN_LENGTH, but was given "
                    + value.length() + " character(s). AidKey.token() already space-pads to that "
                    + "width, so a resolved token never overflows it");
        }
        return value;
    }

    /**
     * A freshly initialised request: every screen field a run of spaces of its declared width, paired with
     * a freshly initialised communication area.
     *
     * @return an initialised request, never {@code null}
     */
    public static ReportRequestRequest empty() {
        return filled(SPACE);
    }

    /**
     * A request whose screen fields are all {@code LOW-VALUES}, as {@code MOVE LOW-VALUES TO CORPT0AO} at
     * {@code app/cbl/CORPT00C.cbl:179} leaves the map on first entry, paired with a freshly initialised
     * communication area.
     *
     * @return a low-values request, never {@code null}
     */
    public static ReportRequestRequest lowValues() {
        return filled(LOW_VALUE);
    }

    private static ReportRequestRequest filled(String fillCharacter) {
        List<String> values = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            values.add(fillCharacter.repeat(field.declaredLength()));
        }
        return fromFieldValues(values, NavigationContext.empty(), SPACE.repeat(AID_LENGTH));
    }

    private static ReportRequestRequest fromFieldValues(List<String> values,
                                                        NavigationContext context,
                                                        String aid) {
        return new ReportRequestRequest(values.get(ScreenField.TRNNAME.ordinal()),
                values.get(ScreenField.TITLE01.ordinal()),
                values.get(ScreenField.CURDATE.ordinal()),
                values.get(ScreenField.PGMNAME.ordinal()),
                values.get(ScreenField.TITLE02.ordinal()),
                values.get(ScreenField.CURTIME.ordinal()),
                values.get(ScreenField.MONTHLY.ordinal()),
                values.get(ScreenField.YEARLY.ordinal()),
                values.get(ScreenField.CUSTOM.ordinal()),
                values.get(ScreenField.SDTMM.ordinal()),
                values.get(ScreenField.SDTDD.ordinal()),
                values.get(ScreenField.SDTYYYY.ordinal()),
                values.get(ScreenField.EDTMM.ordinal()),
                values.get(ScreenField.EDTDD.ordinal()),
                values.get(ScreenField.EDTYYYY.ordinal()),
                values.get(ScreenField.CONFIRM.ordinal()),
                values.get(ScreenField.ERRMSG.ordinal()),
                context,
                aid);
    }

    /**
     * The seventeen screen values in {@link ScreenField} declaration order.
     *
     * @return an immutable list of exactly {@value #FIELD_COUNT} values, none {@code null}
     */
    @JsonIgnore
    public List<String> fieldValues() {
        return List.of(trnname, title01, curdate, pgmname, title02, curtime, monthly, yearly, custom,
                sdtmm, sdtdd, sdtyyyy, edtmm, edtdd, edtyyyy, confirm, errmsg);
    }

    /**
     * The value of one screen field, untrimmed and exactly as it arrived.
     *
     * @param field the screen field to read
     * @return the field's value, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a value from "
                + SYMBOLIC_MAP_INPUT_GROUP);
        return fieldValues().get(field.ordinal());
    }

    /**
     * A copy of this request with one screen field replaced.
     *
     * @param field the screen field to replace
     * @param value the new value; {@code null} becomes the field's {@code SPACES}
     * @return a new request, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code value} is longer than the field's declared width
     */
    public ReportRequestRequest withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a value in "
                + SYMBOLIC_MAP_INPUT_GROUP);
        List<String> next = new ArrayList<>(fieldValues());
        next.set(field.ordinal(), normalise(value, field));
        return fromFieldValues(next, navigationContext, aid);
    }

    public ReportRequestRequest withTrnname(String newTrnname) {
        return withValue(ScreenField.TRNNAME, newTrnname);
    }

    public ReportRequestRequest withTitle01(String newTitle01) {
        return withValue(ScreenField.TITLE01, newTitle01);
    }

    public ReportRequestRequest withCurdate(String newCurdate) {
        return withValue(ScreenField.CURDATE, newCurdate);
    }

    public ReportRequestRequest withPgmname(String newPgmname) {
        return withValue(ScreenField.PGMNAME, newPgmname);
    }

    public ReportRequestRequest withTitle02(String newTitle02) {
        return withValue(ScreenField.TITLE02, newTitle02);
    }

    public ReportRequestRequest withCurtime(String newCurtime) {
        return withValue(ScreenField.CURTIME, newCurtime);
    }

    /**
     * A copy carrying a new {@code MONTHLYI} - the current-month report selector tested at
     * {@code app/cbl/CORPT00C.cbl:213}.
     *
     * @param newMonthly the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withMonthly(String newMonthly) {
        return withValue(ScreenField.MONTHLY, newMonthly);
    }

    /**
     * A copy carrying a new {@code YEARLYI} - the current-year report selector tested at
     * {@code app/cbl/CORPT00C.cbl:239}.
     *
     * @param newYearly the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withYearly(String newYearly) {
        return withValue(ScreenField.YEARLY, newYearly);
    }

    /**
     * A copy carrying a new {@code CUSTOMI} - the custom date-range selector tested at
     * {@code app/cbl/CORPT00C.cbl:258}.
     *
     * @param newCustom the new selector; {@code null} becomes a space
     * @return a new request
     */
    public ReportRequestRequest withCustom(String newCustom) {
        return withValue(ScreenField.CUSTOM, newCustom);
    }

    /**
     * A copy carrying a new {@code SDTMMI}, the start-date month compared against {@code '12'} at
     * {@code app/cbl/CORPT00C.cbl:330}.
     *
     * @param newSdtmm the new month characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtmm(String newSdtmm) {
        return withValue(ScreenField.SDTMM, newSdtmm);
    }

    /**
     * A copy carrying a new {@code SDTDDI}, the start-date day compared against {@code '31'} at
     * {@code app/cbl/CORPT00C.cbl:339}.
     *
     * @param newSdtdd the new day characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtdd(String newSdtdd) {
        return withValue(ScreenField.SDTDD, newSdtdd);
    }

    /**
     * A copy carrying a new {@code SDTYYYYI}, the start-date year class tested at
     * {@code app/cbl/CORPT00C.cbl:347}.
     *
     * @param newSdtyyyy the new year characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withSdtyyyy(String newSdtyyyy) {
        return withValue(ScreenField.SDTYYYY, newSdtyyyy);
    }

    /**
     * A copy carrying a new {@code EDTMMI}, the end-date month compared against {@code '12'} at
     * {@code app/cbl/CORPT00C.cbl:356}.
     *
     * @param newEdtmm the new month characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtmm(String newEdtmm) {
        return withValue(ScreenField.EDTMM, newEdtmm);
    }

    /**
     * A copy carrying a new {@code EDTDDI}, the end-date day compared against {@code '31'} at
     * {@code app/cbl/CORPT00C.cbl:365}.
     *
     * @param newEdtdd the new day characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtdd(String newEdtdd) {
        return withValue(ScreenField.EDTDD, newEdtdd);
    }

    /**
     * A copy carrying a new {@code EDTYYYYI}, the end-date year class tested at
     * {@code app/cbl/CORPT00C.cbl:373}.
     *
     * @param newEdtyyyy the new year characters; {@code null} becomes spaces
     * @return a new request
     */
    public ReportRequestRequest withEdtyyyy(String newEdtyyyy) {
        return withValue(ScreenField.EDTYYYY, newEdtyyyy);
    }

    public ReportRequestRequest withConfirm(String newConfirm) {
        return withValue(ScreenField.CONFIRM, newConfirm);
    }

    public ReportRequestRequest withErrmsg(String newErrmsg) {
        return withValue(ScreenField.ERRMSG, newErrmsg);
    }

    /**
     * A copy carrying a different communication area.
     *
     * @param newNavigationContext the communication area to carry, or {@code null} for {@code EIBCALEN = 0}
     * @return a new request
     */
    public ReportRequestRequest withNavigationContext(NavigationContext newNavigationContext) {
        return fromFieldValues(fieldValues(), newNavigationContext, aid);
    }

    /**
     * A copy carrying no communication area at all - the {@code EIBCALEN = 0} state that
     * {@code app/cbl/CORPT00C.cbl:172} tests before anything else, and on which it moves {@code 'COSGN00C'}
     * into {@code CDEMO-TO-PROGRAM} and returns to the previous screen.
     *
     * @return a new request whose {@link #navigationContext()} is {@code null}
     */
    public ReportRequestRequest withoutNavigationContext() {
        return fromFieldValues(fieldValues(), null, aid);
    }

    /**
     * A copy carrying a new resolved {@code EIBAID} token - the key the operator pressed, which
     * {@code app/cbl/CORPT00C.cbl:183} evaluates.
     *
     * @param newAid the resolved key token; {@code null} becomes spaces, meaning no key resolved
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    public ReportRequestRequest withAid(String newAid) {
        return fromFieldValues(fieldValues(), navigationContext, normaliseAid(newAid));
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value NavigationContext#COMMAREA_LENGTH} when a
     * communication area travelled with the request, and {@code 0} when none did.
     *
     * @return {@value NavigationContext#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? NavigationContext.COMMAREA_LENGTH : 0;
    }

    /**
     * {@code CDEMO-PGM-CONTEXT} of the carried communication area, or
     * {@value NavigationContext#PGM_CONTEXT_ENTER} when none was carried.
     *
     * <p>A cold start cannot be a re-entry - there is no previous invocation of this transaction to
     * re-enter from - and {@code CORPT00C}'s own return path agrees: line 547 moves {@code ZEROS} into
     * {@code CDEMO-PGM-CONTEXT} immediately before transferring control.
     *
     * @return {@value NavigationContext#PGM_CONTEXT_ENTER}, {@value NavigationContext#PGM_CONTEXT_REENTER},
     *     or whatever other digit the carried {@code PIC 9(01)} holds
     */
    @JsonIgnore
    public int pgmContext() {
        return hasNavigationContext()
                ? navigationContext.pgmContext()
                : NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds - first entry, so the screen is painted and nothing
     * is validated.
     *
     * @return {@code true} when {@link #pgmContext()} is {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return pgmContext() == NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - re-entry, so what was typed is validated.
     *
     * <p>Deliberately not the negation of {@link #isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and may hold any digit, so for a value of, say, {@code 9} both predicates are
     * false.
     *
     * @return {@code true} when {@link #pgmContext()} is {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return pgmContext() == NavigationContext.PGM_CONTEXT_REENTER;
    }

    /**
     * Renders the {@link #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI} with freshly initialised
     * metadata - every {@code xxxL} zero and every {@code xxxF} {@code LOW-VALUES}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @return exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toSymbolicMap(FixedWidthCodec codec) {
        return toSymbolicMap(codec, SymbolicMapMetadata.initial());
    }

    /**
     * Renders the {@link #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly - {@code US-ASCII} for the text
     *     fixtures, {@code IBM037} for EBCDIC data
     * @param metadata the {@code xxxL} and {@code xxxF} values to emit
     * @return exactly {@link #SYMBOLIC_MAP_LENGTH} bytes, in the codec's code page
     * @throws NullPointerException if {@code codec} or {@code metadata} is {@code null}
     */
    public byte[] toSymbolicMap(FixedWidthCodec codec, SymbolicMapMetadata metadata) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render "
                + SYMBOLIC_MAP_INPUT_GROUP + ": the code page of a fixed-width image must be stated "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(metadata, "Symbolic-map metadata is required to render "
                + SYMBOLIC_MAP_INPUT_GROUP + "; call SymbolicMapMetadata.initial() for a freshly "
                + "initialised map");
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        List<String> payload = fieldValues();
        for (ScreenField field : ScreenField.values()) {
            record.writeSpanBytes(field.lengthSpan(), halfword(metadata.length(field)));
            record.writeSpan(field.flagSpan(), metadata.flag(field));
            record.writeSpan(field.inputSpan(),
                    codec.movePicX(payload.get(field.ordinal()), field.declaredLength()));
        }
        return record.toByteArray();
    }

    /**
     * Reads a {@link #SYMBOLIC_MAP_LENGTH}-byte image of {@code 01 CORPT0AI} back into a request.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @return the request the image denotes, never {@code null}
     * @throws NullPointerException if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     *     long
     */
    public static ReportRequestRequest fromSymbolicMap(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + ": the code page of a fixed-width image must be stated "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(image, "A " + SYMBOLIC_MAP_LENGTH + "-byte image is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + "; call empty() for a freshly initialised map");
        FixedWidthRecord record = codec.wrap(image, LAYOUT);
        List<String> values = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            values.add(codec.readPicX(record, field.inputSpan()));
        }
        return fromFieldValues(values, null, SPACE.repeat(AID_LENGTH));
    }

    /**
     * Reads the {@code xxxL} and {@code xxxF} metadata out of a {@link #SYMBOLIC_MAP_LENGTH}-byte image of
     * {@code 01 CORPT0AI}.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @return the metadata the image carries, complete for all {@value #FIELD_COUNT} fields
     * @throws NullPointerException if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     *     long
     */
    public static SymbolicMapMetadata metadataFrom(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read the metadata items of "
                + SYMBOLIC_MAP_INPUT_GROUP + "; the code page is never defaulted");
        Objects.requireNonNull(image, "A " + SYMBOLIC_MAP_LENGTH + "-byte image is required to read "
                + "the metadata items of " + SYMBOLIC_MAP_INPUT_GROUP);
        FixedWidthRecord record = codec.wrap(image, LAYOUT);
        Map<ScreenField, FieldMetadata> entries = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            entries.put(field, new FieldMetadata(
                    halfwordValue(record.readSpanBytes(field.lengthSpan())),
                    record.readSpan(field.flagSpan())));
        }
        return new SymbolicMapMetadata(entries);
    }

    private static String normalise(String value, ScreenField field) {
        if (value == null) {
            return field.spaces();
        }
        if (value.length() > field.declaredLength()) {
            throw new IllegalArgumentException("Field " + field.inputItem() + " is declared PIC X("
                    + field.declaredLength() + ") but was given " + value.length()
                    + " character(s): '" + value + "'. " + SYMBOLIC_MAP_INPUT_GROUP + " is "
                    + SYMBOLIC_MAP_LENGTH + " bytes and cannot hold the surplus, and a 3270 RECEIVE "
                    + "MAP cannot deliver it. To shorten the value deliberately, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + field.declaredLength() + "), which "
                    + "truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Encodes a {@code COMP PIC S9(4)} halfword: two bytes, big-endian, two's complement, exactly as
     * z/Architecture stores a binary halfword.
     *
     * @param value the value being stored
     */
    private static byte[] halfword(short value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static short halfwordValue(byte[] image) {
        return (short) (((image[0] & 0xFF) << 8) | (image[1] & 0xFF));
    }

    /**
     * The {@code xxxL} length item and the {@code xxxF} / {@code xxxA} attribute byte of one screen field:
     * metadata, never payload.
     *
     * @param length the {@code xxxL} halfword: the received length on input, {@link #CURSOR_POSITION} to
     *     request the cursor on output
     * @param flag the {@code xxxF} byte, exactly one character; {@code null} becomes {@code LOW-VALUES}
     */
    public record FieldMetadata(short length, String flag) {
        /**
         * The CICS cursor-positioning value.
         */
        public static final short CURSOR_POSITION = -1;

        public static final short UNSET_LENGTH = 0;

        /**
         * The {@code LOW-VALUES} flag byte, {@code X'00'} - an untouched, unattributed field.
         */
        public static final String LOW_VALUE_FLAG = LOW_VALUE;

        public static final int FLAG_WIDTH = FLAG_ITEM_LENGTH;

        public FieldMetadata {
            flag = normaliseFlag(flag);
        }

        /**
         * Freshly initialised metadata: length {@value #UNSET_LENGTH}, flag {@code LOW-VALUES}.
         *
         * @return the initial metadata of one field, never {@code null}
         */
        public static FieldMetadata initial() {
            return new FieldMetadata(UNSET_LENGTH, LOW_VALUE_FLAG);
        }

        /**
         * Metadata asking for the cursor: length {@link #CURSOR_POSITION}, flag {@code LOW-VALUES}.
         *
         * @return cursor-requesting metadata, never {@code null}
         */
        public static FieldMetadata cursor() {
            return new FieldMetadata(CURSOR_POSITION, LOW_VALUE_FLAG);
        }

        /**
         * The {@code xxxA} view of the flag byte - the same byte {@link #flag()} returns, under the name a
         * program uses when it writes an attribute through it.
         *
         * @return the attribute byte, exactly {@link #FLAG_WIDTH} character
         */
        public String attribute() {
            return flag;
        }

        /**
         * Whether this field is asking for the cursor, that is whether {@link #length()} is
         * {@link #CURSOR_POSITION}.
         *
         * @return {@code true} when the cursor is requested on this field
         */
        public boolean cursorRequested() {
            return length == CURSOR_POSITION;
        }

        public FieldMetadata withLength(short newLength) {
            return new FieldMetadata(newLength, flag);
        }

        public FieldMetadata withFlag(String newFlag) {
            return new FieldMetadata(length, newFlag);
        }

        public FieldMetadata withAttribute(String newAttribute) {
            return withFlag(newAttribute);
        }

        private static String normaliseFlag(String value) {
            if (value == null) {
                return LOW_VALUE_FLAG;
            }
            if (value.length() != FLAG_WIDTH) {
                throw new IllegalArgumentException("A symbolic-map flag item is declared PICTURE X, "
                        + "so it holds exactly " + FLAG_WIDTH + " character, but was given "
                        + value.length() + ": '" + value + "'");
            }
            return value;
        }
    }

    /**
     * The {@code xxxL} and {@code xxxF} / {@code xxxA} metadata of all {@value #FIELD_COUNT} screen fields
     * of {@link #SYMBOLIC_MAP_INPUT_GROUP}.
     *
     * @param entries one {@link FieldMetadata} per {@link ScreenField}; defensively copied into an
     *     immutable {@code EnumMap}
     */
    public record SymbolicMapMetadata(Map<ScreenField, FieldMetadata> entries) {
        public SymbolicMapMetadata {
            entries = defensiveCopy(entries);
        }

        /**
         * Freshly initialised metadata for every field: length {@value FieldMetadata#UNSET_LENGTH}, flag
         * {@code LOW-VALUES}.
         *
         * @return complete initial metadata, never {@code null}
         */
        public static SymbolicMapMetadata initial() {
            Map<ScreenField, FieldMetadata> entries = new EnumMap<>(ScreenField.class);
            for (ScreenField field : ScreenField.values()) {
                entries.put(field, FieldMetadata.initial());
            }
            return new SymbolicMapMetadata(entries);
        }

        public FieldMetadata metadata(ScreenField field) {
            Objects.requireNonNull(field, "A ScreenField is required to read symbolic-map metadata");
            return entries.get(field);
        }

        public short length(ScreenField field) {
            return metadata(field).length();
        }

        public String flag(ScreenField field) {
            return metadata(field).flag();
        }

        public SymbolicMapMetadata with(ScreenField field, FieldMetadata metadata) {
            Objects.requireNonNull(field, "A ScreenField is required to replace symbolic-map "
                    + "metadata");
            Objects.requireNonNull(metadata, "FieldMetadata is required; a symbolic map has a length "
                    + "item and a flag byte for every one of its " + FIELD_COUNT + " fields");
            Map<ScreenField, FieldMetadata> next = new EnumMap<>(ScreenField.class);
            next.putAll(entries);
            next.put(field, metadata);
            return new SymbolicMapMetadata(next);
        }

        /**
         * A copy that asks for the cursor on one field, the {@code MOVE -1 TO <field>L} idiom.
         *
         * @param field the screen field to place the cursor on
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public SymbolicMapMetadata withCursorAt(ScreenField field) {
            return with(field, metadata(field).withLength(FieldMetadata.CURSOR_POSITION));
        }

        /**
         * A copy carrying a different {@code xxxL} halfword for one field.
         *
         * @param field the screen field
         * @param newLength the new length value
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public SymbolicMapMetadata withLength(ScreenField field, short newLength) {
            return with(field, metadata(field).withLength(newLength));
        }

        /**
         * A copy carrying a different {@code xxxF} byte for one field.
         *
         * @param field the screen field
         * @param newFlag the new flag byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         * @throws IllegalArgumentException if {@code newFlag} is not exactly one character
         */
        public SymbolicMapMetadata withFlag(ScreenField field, String newFlag) {
            return with(field, metadata(field).withFlag(newFlag));
        }

        /**
         * A copy carrying a different {@code xxxA} byte for one field.
         *
         * @param field the screen field
         * @param newAttribute the new attribute byte; {@code null} becomes {@code LOW-VALUES}
         * @return new metadata for the whole map, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         * @throws IllegalArgumentException if {@code newAttribute} is not exactly one character
         */
        public SymbolicMapMetadata withAttribute(ScreenField field, String newAttribute) {
            return withFlag(field, newAttribute);
        }

        private static Map<ScreenField, FieldMetadata> defensiveCopy(
                Map<ScreenField, FieldMetadata> source) {
            Objects.requireNonNull(source, "Metadata entries are required; call "
                    + "SymbolicMapMetadata.initial() for a freshly initialised symbolic map");
            Map<ScreenField, FieldMetadata> copy = new EnumMap<>(ScreenField.class);
            copy.putAll(source);
            if (copy.size() != FIELD_COUNT) {
                throw new IllegalArgumentException(SYMBOLIC_MAP_INPUT_GROUP + " declares a length "
                        + "item and a flag byte for every one of its " + FIELD_COUNT + " fields, so "
                        + "metadata must be complete, but only " + copy.size() + " field(s) were "
                        + "supplied. Start from SymbolicMapMetadata.initial() and replace what you "
                        + "need");
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
