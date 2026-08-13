package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three overlay sites, and each is a different shape: an alphanumeric item viewed as numeric, a flat item
 * viewed as a group of parts, and - the one that looks like a mistake and is not - an item that redefines
 * itself by its own name.
 */
@DisplayName("G34: CICS-OUTPUT-EDIT-VARS' three REDEFINES overlays - one span, every view")
class AccountUpdateRedefinesCensusTest {
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final FieldSpan ACCT_ID_X = FieldSpan.alphanumeric("CUST-ACCT-ID-X", 0,
            AccountUpdateController.CUST_ACCT_ID_LENGTH);

    private static final FieldSpan ACCT_ID_N = FieldSpan.unsignedNumeric("CUST-ACCT-ID-N", 0,
            AccountUpdateController.CUST_ACCT_ID_LENGTH);

    private static final FieldSpan DATE_X = FieldSpan.alphanumeric("WS-EDIT-DATE-X", 0,
            AccountUpdateController.WS_EDIT_DATE_X_LENGTH);

    private static final FieldSpan DATE_N = FieldSpan.unsignedNumeric("WS-EDIT-DATE-X-N", 0,
            AccountUpdateController.WS_EDIT_DATE_X_LENGTH);

    private static final FieldSpan DATE_YEAR = FieldSpan.alphanumeric("WS-EDIT-DATE-X-YEAR",
            AccountUpdateController.WS_EDIT_DATE_YEAR_OFFSET,
            AccountUpdateController.WS_EDIT_DATE_YEAR_LENGTH);
    private static final FieldSpan DATE_MONTH = FieldSpan.alphanumeric("WS-EDIT-DATE-MONTH",
            AccountUpdateController.WS_EDIT_DATE_MONTH_OFFSET,
            AccountUpdateController.WS_EDIT_DATE_MONTH_LENGTH);
    private static final FieldSpan DATE_DAY = FieldSpan.alphanumeric("WS-EDIT-DATE-DAY",
            AccountUpdateController.WS_EDIT_DATE_DAY_OFFSET,
            AccountUpdateController.WS_EDIT_DATE_DAY_LENGTH);

    private static FixedWidthRecord areaOf(int width, FieldSpan primary) {
        return FixedWidthRecord.forLayout(RecordLayout.of(width, primary), StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("CUST-ACCT-ID-X and CUST-ACCT-ID-N are one eleven-byte span seen two ways")
    void theAccountIdOverlayRoundTripsBothWays() {
        FixedWidthRecord area = areaOf(AccountUpdateController.CUST_ACCT_ID_LENGTH, ACCT_ID_X);
        CODEC.writePicX(area, ACCT_ID_X, "00000000011");
        assertThat(CODEC.readPic9(area, ACCT_ID_N))
                .as("CUST-ACCT-ID-N PIC 9(11) reads the same bytes CUST-ACCT-ID-X PIC X(11) holds")
                .isEqualTo(11L);

        FixedWidthRecord other = areaOf(AccountUpdateController.CUST_ACCT_ID_LENGTH, ACCT_ID_N);
        CODEC.writePic9(other, ACCT_ID_N, 99999999999L);
        assertThat(CODEC.readPicX(other, ACCT_ID_X))
                .as("and the alphanumeric view sees the zero-filled digits, not a trimmed number")
                .isEqualTo("99999999999");
        assertThat(CODEC.readPic9(other, ACCT_ID_N)).isEqualTo(99999999999L);

        assertThat(ACCT_ID_X.offset()).as("an overlay starts where the item it redefines starts")
                .isEqualTo(ACCT_ID_N.offset());
        assertThat(ACCT_ID_X.length()).as("and is exactly as long")
                .isEqualTo(ACCT_ID_N.length());
    }

    @Test
    @DisplayName("the FILLER REDEFINES group splits the ten bytes at 0, 5 and 8")
    void theDateGroupOverlayAddressesThePartsInPlace() {
        FixedWidthRecord area = areaOf(AccountUpdateController.WS_EDIT_DATE_X_LENGTH, DATE_X);
        CODEC.writePicX(area, DATE_X, "2022-07-19");

        assertThat(CODEC.readPicX(area, DATE_YEAR))
                .as("WS-EDIT-DATE-X-YEAR PIC X(4) at offset 0").isEqualTo("2022");
        assertThat(CODEC.readPicX(area, DATE_MONTH))
                .as("WS-EDIT-DATE-MONTH PIC X(2) at offset 5, past the one-byte FILLER separator")
                .isEqualTo("07");
        assertThat(CODEC.readPicX(area, DATE_DAY))
                .as("WS-EDIT-DATE-DAY PIC X(2) at offset 8, past the second separator")
                .isEqualTo("19");

        FixedWidthRecord composed = areaOf(AccountUpdateController.WS_EDIT_DATE_X_LENGTH, DATE_X);
        CODEC.writePicX(composed, DATE_X, "----------");
        CODEC.writePicX(composed, DATE_YEAR, "1955");
        CODEC.writePicX(composed, DATE_MONTH, "08");
        CODEC.writePicX(composed, DATE_DAY, "16");
        assertThat(CODEC.readPicX(composed, DATE_X))
                .as("the flat view sees the parts written through the overlay, and the separator bytes "
                        + "the overlay's own FILLERs cover are untouched")
                .isEqualTo("1955-08-16");
    }

    @Test
    @DisplayName("WS-EDIT-DATE-X redefines itself: the same name, the same ten bytes, a numeric view")
    void theSelfRedefiningOverlayIsPreservedAsDeclared() {
        FixedWidthRecord area = areaOf(AccountUpdateController.WS_EDIT_DATE_X_LENGTH, DATE_N);
        CODEC.writePic9(area, DATE_N, 2022071900L);

        assertThat(CODEC.readPic9(area, DATE_N))
                .as("the PIC 9(10) view").isEqualTo(2022071900L);
        assertThat(CODEC.readPicX(area, DATE_X))
                .as("the PIC X(10) view of the very same bytes").isEqualTo("2022071900");
        assertThat(CODEC.readPicX(area, DATE_YEAR) + CODEC.readPicX(area, DATE_MONTH)
                + CODEC.readPicX(area, DATE_DAY))
                .as("and the group view, which slices those digits at the separator offsets - the shape "
                        + "only lines up for a value carrying separators, which is why the numeric view "
                        + "and the group view are alternatives rather than a hierarchy")
                .isEqualTo("2022" + "71" + "00");

        assertThat(DATE_N.offset()).isEqualTo(DATE_X.offset());
        assertThat(DATE_N.length()).isEqualTo(DATE_X.length());
    }

    @Test
    @DisplayName("all three overlays are inside the group's declared span and none is a field")
    void theOverlaysAreViewsRatherThanState() {
        assertThat(AccountUpdateController.CUST_ACCT_ID_LENGTH)
                .as("CUST-ACCT-ID-X PIC X(11)").isEqualTo(11);
        assertThat(AccountUpdateController.WS_EDIT_DATE_X_LENGTH)
                .as("WS-EDIT-DATE-X PIC X(10)").isEqualTo(10);
        assertThat(AccountUpdateController.WS_EDIT_DATE_YEAR_LENGTH
                + 1 + AccountUpdateController.WS_EDIT_DATE_MONTH_LENGTH
                + 1 + AccountUpdateController.WS_EDIT_DATE_DAY_LENGTH)
                .as("the group's five items - X(4), X(1), X(2), X(1), X(2) - fill the ten bytes exactly, "
                        + "which is the check that catches a wrong offset")
                .isEqualTo(AccountUpdateController.WS_EDIT_DATE_X_LENGTH);
        assertThat(AccountUpdateController.WS_EDIT_DATE_DAY_OFFSET
                + AccountUpdateController.WS_EDIT_DATE_DAY_LENGTH)
                .as("and the last part ends on the boundary")
                .isEqualTo(AccountUpdateController.WS_EDIT_DATE_X_LENGTH);

        assertThat(java.util.Arrays.stream(AccountUpdateController.Conversation.class
                        .getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> name.startsWith("custAcctId") || name.startsWith("wsEditDate"))
                .toList())
                .as("no conversation field holds either item: COACTUPC never sets them, so storage for "
                        + "them would be state this translation invented")
                .isEmpty();
    }
}
