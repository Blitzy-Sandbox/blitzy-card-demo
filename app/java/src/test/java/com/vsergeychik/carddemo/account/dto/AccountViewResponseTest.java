package com.vsergeychik.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse.Builder;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse.FieldAttributes;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract of {@link AccountViewResponse}, asserted against the three files that define it:
 * {@code app/cpy-bms/COACTVW.CPY}, {@code app/bms/COACTVW.bms} and {@code app/cbl/COACTVWC.cbl}.
 *
 * <p>The width, line-number, screen-position and data-offset tables below are an <em>independent second
 * transcription</em> of those sources. They are written out as literals rather than read from the class
 * under test, because a test that asks the implementation what it believes and then agrees with it proves
 * nothing. Where a number here disagrees with the class, one of the two transcriptions is wrong and the
 * build says so.
 *
 * <p>The {@code PIC +ZZZ,ZZZ,ZZZ.99} expectations are likewise derived from the COBOL editing rules rather
 * than captured from the implementation, and each is asserted as a whole fifteen-character string so a
 * single misplaced space fails the build.
 *
 * <p>Nothing here needs a Spring context, a servlet container or a running application - which is itself
 * part of the contract (practice <strong>B10</strong>): {@code parity/ParityHarness} builds this type the
 * same way these tests do.
 */
@DisplayName("AccountViewResponse - COACTVW CACTVWAO, the CAVW outbound payload")
class AccountViewResponseTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the EBCDIC datasets, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** A codec over {@link #ASCII}. */
    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /** A codec over {@link #EBCDIC}, to prove nothing here assumes ASCII byte values. */
    private static final FixedWidthCodec EBCDIC_CODEC = new FixedWidthCodec(EBCDIC);

    /** The 37 {@code DFHMDF} labels, in {@code app/bms/COACTVW.bms} declaration order. */
    private static final String[] LABELS = {
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "ACSTTUS",
            "ADTOPEN", "ACRDLIM", "AEXPDT", "ACSHLIM", "AREISDT", "ACURBAL", "ACRCYCR", "AADDGRP",
            "ACRCYDB", "ACSTNUM", "ACSTSSN", "ACSTDOB", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPHN1", "ACSGOVT",
            "ACSPHN2", "ACSEFTC", "ACSPFLG", "INFOMSG", "ERRMSG",
    };

    /**
     * The 37 {@code xxxO} widths, transcribed from the {@code PICTURE} clauses of
     * {@code app/cpy-bms/COACTVW.CPY:248-464} and cross-checked against the {@code LENGTH=} operands of
     * {@code app/bms/COACTVW.bms}. They sum to 684.
     */
    private static final int[] WIDTHS = {
            4, 40, 8, 8, 40, 8, 11, 1,
            10, 15, 10, 15, 10, 15, 15, 10,
            15, 9, 12, 10, 3, 25, 25, 25,
            50, 2, 50, 5, 50, 3, 13, 20,
            13, 10, 1, 45, 78,
    };

    /**
     * The 1-based line of each {@code xxxO} item in {@code app/cpy-bms/COACTVW.CPY}, starting at the 248
     * that follows the {@code 01 CACTVWAO REDEFINES CACTVWAI.} header at 241 and its 12-byte
     * {@code FILLER} at 242, and advancing by the copybook's six-line stride.
     */
    private static final int[] COPYBOOK_LINES = {
            248, 254, 260, 266, 272, 278, 284, 290,
            296, 302, 308, 314, 320, 326, 332, 338,
            344, 350, 356, 362, 368, 374, 380, 386,
            392, 398, 404, 410, 416, 422, 428, 434,
            440, 446, 452, 458, 464,
    };

    /** The 1-based line on which each field's {@code DFHMDF} begins in {@code app/bms/COACTVW.bms}. */
    private static final int[] MAPSET_LINES = {
            34, 38, 47, 57, 61, 70, 84, 97,
            107, 117, 128, 138, 149, 159, 171, 182,
            192, 207, 216, 225, 234, 251, 256, 261,
            268, 277, 282, 291, 301, 310, 319, 326,
            335, 342, 351, 356, 365,
    };

    /** The row of each field's {@code POS=(row,column)}. */
    private static final int[] SCREEN_ROWS = {
            1, 1, 1, 2, 2, 2, 5, 5,
            6, 6, 7, 7, 8, 8, 9, 10,
            10, 12, 12, 13, 13, 15, 15, 15,
            16, 16, 17, 17, 18, 18, 19, 19,
            20, 20, 20, 22, 23,
    };

    /** The column of each field's {@code POS=(row,column)}. */
    private static final int[] SCREEN_COLUMNS = {
            7, 21, 71, 7, 21, 71, 38, 70,
            17, 61, 17, 61, 17, 61, 61, 23,
            61, 23, 54, 23, 61, 1, 28, 55,
            10, 73, 10, 73, 10, 73, 10, 58,
            10, 41, 78, 23, 1,
    };

    /**
     * Where each field's data begins in the 955-byte group, computed independently here: 12 bytes of
     * {@code TIOAPFX} prefix, then for each field 7 bytes of overhead followed by its width.
     *
     * <p>Identical to {@link AccountViewRequest.ScreenField}'s offsets, because
     * {@code CACTVWAO REDEFINES CACTVWAI} and both strides are 7.
     */
    private static final int[] DATA_OFFSETS = {
            19, 30, 77, 92, 107, 154, 169, 187,
            195, 212, 234, 251, 273, 290, 312, 334,
            351, 373, 389, 408, 425, 435, 467, 499,
            531, 588, 597, 654, 666, 723, 733, 753,
            780, 800, 817, 825, 877,
    };

    /** The five labels {@code app/bms/COACTVW.bms} declares {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} on. */
    private static final String[] MASKED_LABELS = {
            "ACRDLIM", "ACSHLIM", "ACURBAL", "ACRCYCR", "ACRCYDB",
    };

    /** The mask, transcribed from {@code app/cpy-bms/COACTVW.CPY:302}. */
    private static final String MASK = "+ZZZ,ZZZ,ZZZ.99";

    /** {@code CUST-SSN}, nine characters as {@code app/cpy/CVCUS01Y.cpy} declares it. */
    private static final String SSN = "078051120";

    /** The hyphenated form the {@code STRING} at {@code app/cbl/COACTVWC.cbl:496-504} composes. */
    private static final String HYPHENATED_SSN = "078-05-1120";

    /** A single {@code LOW-VALUES} character, as a string. */
    private static final String NUL = "\u0000";

    /** The one character {@code MOVE '*' TO ACCTSIDO} writes. */
    private static final String ASTERISK = "*";

    /** A payload with nothing but the {@code MOVE LOW-VALUES} state. */
    private static AccountViewResponse lowValues() {
        return AccountViewResponse.initialGroup();
    }

    /** A payload with every field populated, for value-semantics and rendering assertions. */
    private static AccountViewResponse populated() {
        return populatedBuilder().build();
    }

    /** The builder behind {@link #populated()}, so a test can vary one member. */
    private static Builder populatedBuilder() {
        return AccountViewResponse.builder()
                .screenTitles()
                .screenIdentity()
                .curdate("08/09/26")
                .curtime("11:22:33")
                .acctsid("00000000011")
                .acsttus("Y")
                .adtopen("2020-01-01")
                .acrdlimAmount(new BigDecimal("5000.00"))
                .aexpdt("2029-12-31")
                .acshlimAmount(new BigDecimal("500.00"))
                .areisdt("2024-06-30")
                .acurbalAmount(new BigDecimal("1234.56"))
                .acrcycrAmount(new BigDecimal("10.00"))
                .aaddgrp("ZEROAPR")
                .acrcydbAmount(new BigDecimal("20.00"))
                .acstnum("000000042")
                .acstssnFromSsn(SSN)
                .acstdob("1970-01-01")
                .acstfco("750")
                .acsfnam("PERCIVAL")
                .acsmnam("Q")
                .acslnam("QUATERMASS")
                .acsadl1("1 THE AVENUE")
                .acsstte("NY")
                .acsadl2("APT 4B")
                .acszipc("10001")
                .acscity("NEW YORK")
                .acsctry("USA")
                .acsphn1("(212)5551212")
                .acsgovt("GOVTID9988776655")
                .acsphn2("(212)5551213")
                .acseftc("EFT7654321")
                .acspflg("Y")
                .infomsg("Displaying details of given Account")
                .errmsg("")
                .thisScreenAsNextTarget();
    }

    @Nested
    @DisplayName("The width contract - 37 fields summing to 684 in a 955-byte group")
    class WidthContract {

        @Test
        @DisplayName("the independent transcriptions agree on the field count")
        void fieldCount() {
            assertThat(LABELS).hasSize(37);
            assertThat(WIDTHS).hasSize(37);
            assertThat(COPYBOOK_LINES).hasSize(37);
            assertThat(MAPSET_LINES).hasSize(37);
            assertThat(SCREEN_ROWS).hasSize(37);
            assertThat(SCREEN_COLUMNS).hasSize(37);
            assertThat(DATA_OFFSETS).hasSize(37);
            assertThat(AccountViewResponse.FIELD_COUNT).isEqualTo(37);
            assertThat(ScreenField.values()).hasSize(37);
        }

        @Test
        @DisplayName("the 37 xxxO PICTURE widths sum to 684")
        void payloadLength() {
            int sum = 0;
            for (int width : WIDTHS) {
                sum += width;
            }
            assertThat(sum).isEqualTo(684);
            assertThat(AccountViewResponse.PAYLOAD_LENGTH).isEqualTo(684);
        }

        @Test
        @DisplayName("the group is 12 + 37 * 7 + 684 = 955 bytes")
        void groupLength() {
            assertThat(AccountViewResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(AccountViewResponse.FIELD_OVERHEAD).isEqualTo(7);
            assertThat(AccountViewResponse.GROUP_LENGTH)
                    .isEqualTo(12 + 37 * 7 + 684)
                    .isEqualTo(955);
        }

        @Test
        @DisplayName("the output overhead is FILLER X(3) plus four one-byte attribute items")
        void overheadComposition() {
            assertThat(AccountViewResponse.FILLER_LENGTH).isEqualTo(3);
            assertThat(AccountViewResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountViewResponse.ATTRIBUTE_ITEMS_PER_FIELD).isEqualTo(4);
            assertThat(AccountViewResponse.FILLER_LENGTH
                    + AccountViewResponse.ATTRIBUTE_ITEMS_PER_FIELD
                            * AccountViewResponse.ATTRIBUTE_ITEM_LENGTH)
                    .isEqualTo(AccountViewResponse.FIELD_OVERHEAD);
        }

        @Test
        @DisplayName("it agrees with AccountViewRequest field for field: label, order, width and offset")
        void agreesWithTheRequest() {
            AccountViewRequest.ScreenField[] inbound = AccountViewRequest.ScreenField.values();
            ScreenField[] outbound = ScreenField.values();
            assertThat(outbound).hasSameSizeAs(inbound);
            for (int index = 0; index < outbound.length; index++) {
                assertThat(outbound[index].label())
                        .as("label at position %d", index)
                        .isEqualTo(inbound[index].label());
                assertThat(outbound[index].length())
                        .as("width of %s", outbound[index].label())
                        .isEqualTo(inbound[index].length());
                assertThat(outbound[index].dataOffset())
                        .as("data offset of %s", outbound[index].label())
                        .isEqualTo(inbound[index].dataOffset());
            }
            assertThat(AccountViewResponse.PAYLOAD_LENGTH)
                    .isEqualTo(AccountViewRequest.PAYLOAD_LENGTH);
            assertThat(AccountViewResponse.GROUP_LENGTH).isEqualTo(AccountViewRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the next-screen triple takes its widths from CVCRD01Y, mapset 7 rather than 8")
        void nextScreenWidths() {
            assertThat(AccountViewResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(AccountViewResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AccountViewResponse.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(AccountViewResponse.THIS_MAPSET)
                    .as("LIT-THISMAPSET is PIC X(8) VALUE 'COACTVW ' - the trailing space is the literal")
                    .isEqualTo("COACTVW ")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the two message lines carry the map's widths, not working storage's")
        void messageLineWidths() {
            assertThat(AccountViewResponse.INFOMSG_LENGTH).isEqualTo(45);
            assertThat(AccountViewResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(AccountViewResponse.WS_INFO_MSG_LENGTH).isEqualTo(40);
            assertThat(AccountViewResponse.WS_RETURN_MSG_LENGTH).isEqualTo(75);
            assertThat(AccountViewResponse.INFOMSG_INFO_MESSAGE_PADDING).isEqualTo(5);
            assertThat(AccountViewResponse.ERRMSG_RETURN_MESSAGE_PADDING).isEqualTo(3);
            assertThat(AccountViewResponse.ERRMSG_STANDARD_MESSAGE_PADDING)
                    .isEqualTo(78 - SystemMessages.MESSAGE_LENGTH)
                    .isEqualTo(28);
            assertThat(AccountViewResponse.INFOMSG_STANDARD_MESSAGE_TRUNCATION)
                    .as("INFOMSGO is the one receiver narrower than a standard 50-byte message")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the screen identity is transcribed from app/cbl/COACTVWC.cbl:143-149")
        void screenIdentity() {
            assertThat(AccountViewResponse.THIS_PROGRAM).isEqualTo("COACTVWC").hasSize(8);
            assertThat(AccountViewResponse.THIS_TRANID).isEqualTo("CAVW").hasSize(4);
            assertThat(AccountViewResponse.MAP_NAME).isEqualTo("CACTVWA").hasSize(7);
        }

        @Test
        @DisplayName("the SSN geometry is 3 + 1 + 2 + 1 + 4 = 11 into a 12-byte receiver")
        void ssnGeometry() {
            assertThat(AccountViewResponse.SSN_LENGTH).isEqualTo(9);
            assertThat(AccountViewResponse.SSN_GROUP_SEPARATOR).isEqualTo("-");
            assertThat(AccountViewResponse.ACSTSSN_STRING_LENGTH).isEqualTo(11);
            assertThat(AccountViewResponse.ACSTSSN_LENGTH).isEqualTo(12);
            assertThat(HYPHENATED_SSN).hasSize(AccountViewResponse.ACSTSSN_STRING_LENGTH);
        }

        @Test
        @DisplayName("the blank-field marker is CSSETATY's own asterisk, not a local copy")
        void blankFieldMarker() {
            assertThat(AccountViewResponse.BLANK_FIELD_MARKER)
                    .isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo(ASTERISK);
        }
    }

    @Nested
    @DisplayName("ScreenField - the 37 name-labelled DFHMDF entries of 100")
    class Fields {

        @Test
        @DisplayName("labels are in mapset declaration order, which is copybook storage order")
        void order() {
            for (int index = 0; index < LABELS.length; index++) {
                assertThat(ScreenField.values()[index].label())
                        .as("position %d", index)
                        .isEqualTo(LABELS[index]);
            }
        }

        @Test
        @DisplayName("every constant carries the width, both source lines and the screen position")
        void provenance() {
            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(field.length()).as("%s width", field).isEqualTo(WIDTHS[index]);
                assertThat(field.copybookLine()).as("%s copybook line", field)
                        .isEqualTo(COPYBOOK_LINES[index]);
                assertThat(field.mapsetLine()).as("%s mapset line", field)
                        .isEqualTo(MAPSET_LINES[index]);
                assertThat(field.screenRow()).as("%s row", field).isEqualTo(SCREEN_ROWS[index]);
                assertThat(field.screenColumn()).as("%s column", field)
                        .isEqualTo(SCREEN_COLUMNS[index]);
                assertThat(field.dataOffset()).as("%s data offset", field)
                        .isEqualTo(DATA_OFFSETS[index]);
            }
        }

        @Test
        @DisplayName("the copybook line advances by the six-line stride, from 248 to 464")
        void copybookStride() {
            ScreenField[] fields = ScreenField.values();
            assertThat(fields[0].copybookLine()).isEqualTo(248);
            assertThat(fields[fields.length - 1].copybookLine()).isEqualTo(464);
            for (int index = 1; index < fields.length; index++) {
                assertThat(fields[index].copybookLine() - fields[index - 1].copybookLine())
                        .as("stride before %s", fields[index])
                        .isEqualTo(6);
            }
        }

        @Test
        @DisplayName("the strides tile the group with no gap and no overlap, ending exactly on 955")
        void strideTiling() {
            int cursor = AccountViewResponse.TIOAPFX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.fillerOffset()).as("%s FILLER begins", field).isEqualTo(cursor);
                assertThat(field.colourOffset()).isEqualTo(field.fillerOffset() + 3);
                assertThat(field.psOffset()).isEqualTo(field.colourOffset() + 1);
                assertThat(field.hilightOffset()).isEqualTo(field.psOffset() + 1);
                assertThat(field.validnOffset()).isEqualTo(field.hilightOffset() + 1);
                assertThat(field.dataOffset()).isEqualTo(field.validnOffset() + 1);
                cursor = field.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(AccountViewResponse.GROUP_LENGTH).isEqualTo(955);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each field names its five items by suffixing its label")
        void itemNames(ScreenField field) {
            assertThat(field.symbolicItemName()).isEqualTo(field.label() + "O");
            assertThat(field.colourItemName()).isEqualTo(field.label() + "C");
            assertThat(field.psItemName()).isEqualTo(field.label() + "P");
            assertThat(field.hilightItemName()).isEqualTo(field.label() + "H");
            assertThat(field.validnItemName()).isEqualTo(field.label() + "V");
        }

        @Test
        @DisplayName("the four attribute suffixes come from DSATTS=(COLOR,HILIGHT,PS,VALIDN)")
        void attributeSuffixes() {
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
            assertThat(AccountViewResponse.PS_ITEM_SUFFIX).isEqualTo("P");
            assertThat(AccountViewResponse.HILIGHT_ITEM_SUFFIX).isEqualTo("H");
            assertThat(AccountViewResponse.VALIDN_ITEM_SUFFIX).isEqualTo("V");
        }

        @Test
        @DisplayName("exactly five fields are numeric-edited, and each is 15 wide")
        void numericEdited() {
            assertThat(AccountViewResponse.AMOUNT_MASK_FIELD_COUNT).isEqualTo(5);
            assertThat(MASKED_LABELS).hasSize(5);
            for (String label : MASKED_LABELS) {
                ScreenField field = ScreenField.byLabel(label);
                assertThat(field.isNumericEdited()).as("%s is PICOUT masked", label).isTrue();
                assertThat(field.picture()).isEqualTo(MASK);
                assertThat(field.length()).isEqualTo(15);
            }
            long masked = 0;
            for (ScreenField field : ScreenField.values()) {
                if (field.isNumericEdited()) {
                    masked++;
                } else {
                    assertThat(field.picture())
                            .as("%s should be plain alphanumeric", field)
                            .startsWith("X(");
                }
            }
            assertThat(masked).isEqualTo(5);
        }

        @Test
        @DisplayName("ACCTSID is PIC X(11) on the output side although its input twin is numeric")
        void acctsidPictureAsymmetry() {
            assertThat(ScreenField.ACCTSID.picture())
                    .as("app/cpy-bms/COACTVW.CPY:284 declares ACCTSIDO plain alphanumeric")
                    .isEqualTo("X(11)");
            assertThat(AccountViewRequest.ScreenField.ACCTSID.picture())
                    .as("app/cpy-bms/COACTVW.CPY:60 declares ACCTSIDI numeric")
                    .isEqualTo("99999999999");
            assertThat(ScreenField.ACCTSID.length())
                    .isEqualTo(AccountViewRequest.ScreenField.ACCTSID.length());
        }

        @ParameterizedTest
        @ValueSource(strings = {"FKEYS", "FKEY05", "FKEY12", "PAGENO", "CARDSID", "acctsid", ""})
        @DisplayName("byLabel rejects a label COACTVW does not declare")
        void byLabelRejectsUnknown(String label) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.byLabel(label))
                    .withMessageContaining("no name-labelled DFHMDF");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("byLabel round-trips every declared label")
        void byLabelRoundTrips(ScreenField field) {
            assertThat(ScreenField.byLabel(field.label())).isSameAs(field);
        }

        @Test
        @DisplayName("byLabel rejects null rather than reporting no match")
        void byLabelRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> ScreenField.byLabel(null));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("describe names the item, its PICTURE, both sources and the screen position")
        void describe(ScreenField field) {
            assertThat(field.describe())
                    .startsWith(field.symbolicItemName() + " PIC " + field.picture())
                    .contains("app/cpy-bms/COACTVW.CPY:" + field.copybookLine())
                    .contains("DFHMDF " + field.label())
                    .contains("app/bms/COACTVW.bms:" + field.mapsetLine())
                    .endsWith("POS=(" + field.screenRow() + "," + field.screenColumn() + ")");
        }
    }


    @Nested
    @DisplayName("PIC +ZZZ,ZZZ,ZZZ.99 - the numeric-edited mask, character by character")
    class EditMask {

        @Test
        @DisplayName("the mask geometry is 1 sign + 9 digits + 2 commas + 1 point + 2 digits = 15")
        void geometry() {
            assertThat(AccountViewResponse.AMOUNT_PICTURE).isEqualTo(MASK).hasSize(15);
            assertThat(AccountViewResponse.AMOUNT_MASK_WIDTH).isEqualTo(15);
            assertThat(AccountViewResponse.AMOUNT_INTEGER_POSITIONS).isEqualTo(9);
            assertThat(AccountViewResponse.AMOUNT_FRACTION_DIGITS).isEqualTo(2);
            assertThat(AccountViewResponse.AMOUNT_GROUP_SIZE).isEqualTo(3);
            assertThat(AccountViewResponse.AMOUNT_DIGIT_POSITIONS).isEqualTo(11);
            assertThat(AccountViewResponse.SOURCE_INTEGER_DIGITS)
                    .as("PIC S9(10)V99 has one more integer digit than the mask can show")
                    .isEqualTo(10)
                    .isGreaterThan(AccountViewResponse.AMOUNT_INTEGER_POSITIONS);
        }

        @ParameterizedTest(name = "[{index}] {0} -> \"{1}\"")
        @CsvSource(delimiter = '|', textBlock = """
                0.00            | '+           .00'
                0               | '+           .00'
                -0.00           | '+           .00'
                0.01            | '+           .01'
                -0.01           | '-           .01'
                0.99            | '+           .99'
                1.00            | '+          1.00'
                -1.00           | '-          1.00'
                9.99            | '+          9.99'
                99.99           | '+         99.99'
                123.45          | '+        123.45'
                999.99          | '+        999.99'
                1000.00         | '+      1,000.00'
                -1000.00        | '-      1,000.00'
                10000.00        | '+     10,000.00'
                100000.00       | '+    100,000.00'
                999999.99       | '+    999,999.99'
                1000000.00      | '+  1,000,000.00'
                1234567.89      | '+  1,234,567.89'
                -1234567.89     | '-  1,234,567.89'
                10000000.00     | '+ 10,000,000.00'
                100000000.00    | '+100,000,000.00'
                999999999.99    | '+999,999,999.99'
                -999999999.99   | '-999,999,999.99'
                1000000000.00   | '+           .00'
                1234567890.12   | '+234,567,890.12'
                -1234567890.12  | '-234,567,890.12'
                9999999999.99   | '+999,999,999.99'
                """)
        @DisplayName("every documented case renders exactly, as a whole 15-character string")
        void renders(String amount, String expected) {
            String edited = AccountViewResponse.editAmount(new BigDecimal(amount));
            assertThat(edited)
                    .as("PIC %s applied to %s", MASK, amount)
                    .isEqualTo(expected)
                    .hasSize(15);
        }

        @Test
        @DisplayName("zero blanks all nine integer positions and both commas, but never the decimals")
        void zeroSuppressesTheIntegerPartOnly() {
            String edited = AccountViewResponse.editAmount(BigDecimal.ZERO);
            assertThat(edited).hasSize(15);
            assertThat(edited.charAt(0)).as("the sign position always emits").isEqualTo('+');
            for (int index = 1; index <= 11; index++) {
                assertThat(edited.charAt(index))
                        .as("mask position %d - a Z position or a comma left of the point", index)
                        .isEqualTo(' ');
            }
            assertThat(edited.charAt(12)).as("the decimal point is not suppressible").isEqualTo('.');
            assertThat(edited.substring(13))
                    .as(".99 forces both decimal digits; they are 9s, not Zs")
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("the sign is fixed, not floating: it stays in position 0 at every magnitude")
        void signIsFixed() {
            assertThat(AccountViewResponse.editAmount(new BigDecimal("0.00")).charAt(0)).isEqualTo('+');
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1.00")).charAt(0)).isEqualTo('+');
            assertThat(AccountViewResponse.editAmount(new BigDecimal("-1.00")).charAt(0)).isEqualTo('-');
            assertThat(AccountViewResponse.editAmount(new BigDecimal("999999999.99")).charAt(0))
                    .isEqualTo('+');
            assertThat(AccountViewResponse.editAmount(new BigDecimal("-999999999.99")).charAt(0))
                    .isEqualTo('-');
        }

        @Test
        @DisplayName("a comma survives only when a digit prints to its left")
        void commaSuppression() {
            assertThat(AccountViewResponse.editAmount(new BigDecimal("999.99")))
                    .as("neither group is reached, so both commas blank")
                    .doesNotContain(",");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1000.00")))
                    .as("the low comma prints, the high one does not")
                    .contains(",")
                    .isEqualTo("+      1,000.00");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1000000.00")))
                    .as("both commas print")
                    .isEqualTo("+  1,000,000.00");
        }

        @Test
        @DisplayName("a tenth integer digit is discarded on the left, and nothing is thrown")
        void leftTruncation() {
            assertThatNoException()
                    .isThrownBy(() -> AccountViewResponse.editAmount(new BigDecimal("9999999999.99")));
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1234567890.12")))
                    .as("the leading 1 of ten integer digits is lost, as a COBOL numeric MOVE loses it")
                    .isEqualTo("+234,567,890.12");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1000000000.00")))
                    .as("truncation happens before editing, so the nine surviving zeros are leading "
                            + "zeros in Z positions and suppression blanks them to the decimal point")
                    .isEqualTo("+           .00");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("9999999999.99")))
                    .as("the same truncation, with nine significant digits left behind")
                    .isEqualTo("+999,999,999.99");
        }

        @Test
        @DisplayName("truncation precedes editing, so a truncated value can render as blank-and-zero")
        void truncationPrecedesEditing() {
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1000000000.00")))
                    .as("a milliard renders identically to zero, because the only significant digit "
                            + "was in the tenth integer position the mask does not provide")
                    .isEqualTo(AccountViewResponse.editAmount(BigDecimal.ZERO));
            assertThat(AccountViewResponse.editAmount(new BigDecimal("-1000000000.00")))
                    .as("the sign survives truncation - it is not a digit position")
                    .isEqualTo("-           .00");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1000000001.00")))
                    .as("a digit inside the nine positions still prints")
                    .isEqualTo("+          1.00");
        }

        @ParameterizedTest(name = "[{index}] {0} truncates to a fraction of {1}")
        @CsvSource({
                "1.239, 23",
                "1.231, 23",
                "1.235, 23",
                "1.999, 99",
                "-1.239, 23",
                "-1.999, 99",
        })
        @DisplayName("a third decimal digit is truncated, never rounded - ROUNDED appears nowhere")
        void truncatesRatherThanRounds(String amount, String fraction) {
            assertThat(AccountViewResponse.editAmount(new BigDecimal(amount)))
                    .as("RoundingMode.DOWN, because the keyword ROUNDED appears zero times in the COBOL")
                    .endsWith("." + fraction);
        }

        @Test
        @DisplayName("a scale below 2 is padded to two forced decimals")
        void scalesUp() {
            assertThat(AccountViewResponse.editAmount(new BigDecimal("7"))).isEqualTo("+          7.00");
            assertThat(AccountViewResponse.editAmount(new BigDecimal("7.5"))).isEqualTo("+          7.50");
        }

        @Test
        @DisplayName("the separators are literal, never a Locale's choice")
        void separatorsAreLiteral() {
            String edited = AccountViewResponse.editAmount(new BigDecimal("1234567.89"));
            assertThat(edited.charAt(4)).isEqualTo(',');
            assertThat(edited.charAt(8)).isEqualTo(',');
            assertThat(edited.charAt(12)).isEqualTo('.');
        }

        @Test
        @DisplayName("an absent amount is rejected; LOW-VALUES goes in as an image instead")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.editAmount(null))
                    .withMessageContaining("LOW-VALUES");
        }

        @Test
        @DisplayName("the five builder amount paths render through the same mask")
        void builderAmountPaths() {
            BigDecimal amount = new BigDecimal("1234567.89");
            String expected = AccountViewResponse.editAmount(amount);
            AccountViewResponse response = AccountViewResponse.builder()
                    .acrdlimAmount(amount)
                    .acshlimAmount(amount)
                    .acurbalAmount(amount)
                    .acrcycrAmount(amount)
                    .acrcydbAmount(amount)
                    .build();
            assertThat(response.getAcrdlim()).isEqualTo(expected);
            assertThat(response.getAcshlim()).isEqualTo(expected);
            assertThat(response.getAcurbal()).isEqualTo(expected);
            assertThat(response.getAcrcycr()).isEqualTo(expected);
            assertThat(response.getAcrcydb()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a money field the program never wrote stays LOW-VALUES, not a rendered zero")
        void unwrittenMoneyFieldIsLowValues() {
            AccountViewResponse response = AccountViewResponse.initialGroup();
            assertThat(response.getAcurbal())
                    .as("MOVE LOW-VALUES TO CACTVWAO covers the money items too")
                    .isEqualTo(NUL.repeat(15))
                    .isNotEqualTo(AccountViewResponse.editAmount(BigDecimal.ZERO));
        }
    }

    @Nested
    @DisplayName("LOW-VALUES, spaces and a bare asterisk - three distinct field states")
    class FieldStates {

        @Test
        @DisplayName("initialGroup reproduces MOVE LOW-VALUES TO CACTVWAO across all 37 fields")
        void initialGroupIsLowValues() {
            AccountViewResponse response = lowValues();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.value(field))
                        .as("%s after MOVE LOW-VALUES", field)
                        .isEqualTo(NUL.repeat(field.length()))
                        .hasSize(field.length());
            }
        }

        @Test
        @DisplayName("LOW-VALUES is neither spaces nor absent")
        void lowValuesIsDistinct() {
            String stored = lowValues().getAcctsid();
            assertThat(stored).isNotNull().isNotEqualTo(" ".repeat(11)).isNotBlank();
            assertThat(stored.chars().allMatch(character -> character == 0)).isTrue();
        }

        @Test
        @DisplayName("ACCTSIDO carries an account number, LOW-VALUES or an asterisk")
        void acctsidThreeStates() {
            assertThat(AccountViewResponse.builder().acctsid("00000000011").build().getAcctsid())
                    .isEqualTo("00000000011");
            assertThat(AccountViewResponse.builder()
                    .acctsid(CardScreenState.lowValues(11))
                    .build()
                    .getAcctsid())
                    .isEqualTo(NUL.repeat(11));
            assertThat(AccountViewResponse.builder().acctsid(ASTERISK).build().getAcctsid())
                    .as("MOVE '*' TO ACCTSIDO leaves an asterisk and ten spaces")
                    .isEqualTo("*          ")
                    .hasSize(11);
        }

        @Test
        @DisplayName("null means LOW-VALUES on a payload field, because that is the group's initial fill")
        void nullBecomesLowValues() {
            assertThat(AccountViewResponse.builder().acsttus(null).build().getAcsttus())
                    .isEqualTo(NUL);
            assertThat(AccountViewResponse.builder().errmsg(null).build().getErrmsg())
                    .isEqualTo(NUL.repeat(78));
        }

        @Test
        @DisplayName("null means spaces on the next-screen triple, which lives in the work area")
        void nullBecomesSpacesOnTheTriple() {
            AccountViewResponse response = AccountViewResponse.builder()
                    .nextTarget(null, null, null)
                    .build();
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(response.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(response.getNextMap()).isEqualTo(" ".repeat(7));
        }

        @Test
        @DisplayName("a value is stored at its declared width - padded on the right when short")
        void padsShortValues() {
            assertThat(AccountViewResponse.builder().acsfnam("PERCIVAL").build().getAcsfnam())
                    .isEqualTo("PERCIVAL" + " ".repeat(17))
                    .hasSize(25);
        }

        @Test
        @DisplayName("a value is truncated on the right when long, as a PIC X receiver truncates")
        void truncatesLongValues() {
            assertThat(AccountViewResponse.builder().acszipc("1000199999").build().getAcszipc())
                    .as("CUST-ADDR-ZIP PIC X(10) into ACSZIPCO PIC X(5)")
                    .isEqualTo("10001");
            assertThat(AccountViewResponse.builder().acsphn1("(212)555121299").build().getAcsphn1())
                    .as("CUST-PHONE-NUM-1 PIC X(15) into ACSPHN1O PIC X(13)")
                    .isEqualTo("(212)55512129");
        }

        @Test
        @DisplayName("the two message lines widen on the right, and are not trimmed on read")
        void messageLinesWiden() {
            String returnMessage = "Account number not provided";
            AccountViewResponse response = AccountViewResponse.builder()
                    .errmsg(returnMessage)
                    .infomsg("Enter or update id of account to display")
                    .build();
            assertThat(response.getErrmsg())
                    .isEqualTo(returnMessage + " ".repeat(78 - returnMessage.length()))
                    .hasSize(78);
            assertThat(response.getInfomsg()).hasSize(45).endsWith("  ");
        }

        @Test
        @DisplayName("the titles arrive at their exact 40 characters from COTTL01Y")
        void screenTitles() {
            AccountViewResponse response = AccountViewResponse.builder().screenTitles().build();
            assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @Test
        @DisplayName("the identity moves put CAVW and COACTVWC on the screen")
        void screenIdentity() {
            AccountViewResponse response = AccountViewResponse.builder().screenIdentity().build();
            assertThat(response.getTrnname()).isEqualTo("CAVW");
            assertThat(response.getPgmname()).isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("the date header supplies mm/dd/yy and hh:mm:ss without reading a clock")
        void dateHeader() {
            DateHeader header =
                    DateHeader.of(ASCII_CODEC, LocalDateTime.of(2026, 8, 9, 11, 22, 33));
            AccountViewResponse response = AccountViewResponse.builder().dateHeader(header).build();
            assertThat(response.getCurdate()).isEqualTo(header.wsCurdateMmDdYy()).hasSize(8);
            assertThat(response.getCurtime()).isEqualTo(header.wsCurtimeHhMmSs()).hasSize(8);
        }

        @Test
        @DisplayName("the date header is required rather than defaulted to now")
        void dateHeaderRejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().dateHeader(null));
        }
    }

    @Nested
    @DisplayName("ACSTSSNO - STRING writes 11 of 12 bytes and leaves the twelfth alone")
    class SsnComposition {

        @Test
        @DisplayName("the hyphenated form is 3-2-4, taken from CUST-SSN one-based")
        void hyphenates() {
            AccountViewResponse response = AccountViewResponse.builder()
                    .acstssnFromSsn(SSN)
                    .build();
            assertThat(response.getAcstssn()).startsWith(HYPHENATED_SSN).hasSize(12);
        }

        @Test
        @DisplayName("the twelfth byte keeps the LOW-VALUES the map initialisation put there")
        void twelfthByteIsUntouched() {
            String stored = AccountViewResponse.builder().acstssnFromSsn(SSN).build().getAcstssn();
            assertThat(stored.charAt(11))
                    .as("a COBOL STRING does not blank its receiver's tail")
                    .isEqualTo('\u0000');
            assertThat(stored).isEqualTo(HYPHENATED_SSN + NUL);
        }

        @Test
        @DisplayName("the twelfth byte keeps whatever preceded it - it is not forced to LOW-VALUES either")
        void twelfthByteKeepsAnyPriorContent() {
            String stored = AccountViewResponse.builder()
                    .acstssn("XXXXXXXXXXX!")
                    .acstssnFromSsn(SSN)
                    .build()
                    .getAcstssn();
            assertThat(stored).isEqualTo(HYPHENATED_SSN + "!");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "1", "07805112", "0780511200", "078-05-1120"})
        @DisplayName("a CUST-SSN that is not nine characters is rejected, not silently padded")
        void rejectsWrongWidth(String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewResponse.builder().acstssnFromSsn(candidate))
                    .withMessageContaining("PIC 9(09)");
        }

        @Test
        @DisplayName("an absent CUST-SSN is rejected")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().acstssnFromSsn(null));
        }

        @Test
        @DisplayName("the value survives the group image round trip byte for byte")
        void survivesTheGroupImage() {
            AccountViewResponse response = AccountViewResponse.builder().acstssnFromSsn(SSN).build();
            byte[] image = response.toGroupImage(ASCII_CODEC);
            assertThat(AccountViewResponse.fromGroupImage(image, ASCII_CODEC).getAcstssn())
                    .isEqualTo(HYPHENATED_SSN + NUL);
        }
    }


    @Nested
    @DisplayName("The attribute quad - xxxC, xxxP, xxxH and xxxV from DSATTS, not from EXTATT")
    class AttributeQuads {

        @Test
        @DisplayName("all four items of all 37 fields start at LOW-VALUES")
        void startAtLowValues() {
            AccountViewResponse response = lowValues();
            for (ScreenField field : ScreenField.values()) {
                FieldAttributes quad = response.attributes(field);
                assertThat(quad.getColour()).isEqualTo(FieldAttributes.UNSET);
                assertThat(quad.getPs()).isEqualTo(FieldAttributes.UNSET);
                assertThat(quad.getHilight()).isEqualTo(FieldAttributes.UNSET);
                assertThat(quad.getValidn()).isEqualTo(FieldAttributes.UNSET);
            }
        }

        @Test
        @DisplayName("the quad is writable in place, which is what COACTVWC:555-571 needs")
        void writableInPlace() {
            AccountViewResponse response = lowValues();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHDFCOL);
            assertThat(response.attributes(ScreenField.ACCTSID).isDefaultColour()).isTrue();

            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(response.attributes(ScreenField.ACCTSID).isDefaultColour()).isFalse();
        }

        @Test
        @DisplayName("INFOMSGC is a visibility switch: DFHBMDAR hides the line, DFHNEUTR shows it")
        void informationLineVisibility() {
            AccountViewResponse response = lowValues();
            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR);
            assertThat(response.attributes(ScreenField.INFOMSG).isNonDisplay()).isTrue();
            assertThat(response.attributes(ScreenField.INFOMSG).isNeutral()).isFalse();

            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHNEUTR);
            assertThat(response.attributes(ScreenField.INFOMSG).isNeutral()).isTrue();
            assertThat(response.attributes(ScreenField.INFOMSG).isNonDisplay()).isFalse();
            assertThat(response.attributes(ScreenField.INFOMSG).isRedHighlighted()).isFalse();
        }

        @Test
        @DisplayName("all four items are settable and readable independently")
        void allFourItems() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setPs(BmsAttributes.DFHBLUE);
            quad.setHilight(BmsAttributes.DFHUNDLN);
            quad.setValidn(BmsAttributes.DFHBMFSE);
            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isEqualTo(BmsAttributes.DFHBLUE);
            assertThat(quad.getHilight()).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(quad.getValidn()).isEqualTo(BmsAttributes.DFHBMFSE);

            quad.resetToLowValues();
            assertThat(quad).isEqualTo(new FieldAttributes());
        }

        @Test
        @DisplayName("the four-argument constructor and the copy constructor agree")
        void constructors() {
            FieldAttributes quad = new FieldAttributes(BmsAttributes.DFHRED, BmsAttributes.DFHBLUE,
                    BmsAttributes.DFHUNDLN, BmsAttributes.DFHBMFSE);
            FieldAttributes copy = new FieldAttributes(quad);
            assertThat(copy).isEqualTo(quad).hasSameHashCodeAs(quad).isNotSameAs(quad);

            copy.setColour(BmsAttributes.DFHGREEN);
            assertThat(copy).isNotEqualTo(quad);
            assertThatNullPointerException().isThrownBy(() -> new FieldAttributes(null));
        }

        @Test
        @DisplayName("value equality covers all four bytes, and only quads compare equal")
        void valueEquality() {
            FieldAttributes quad = new FieldAttributes(BmsAttributes.DFHRED, BmsAttributes.DFHBLUE,
                    BmsAttributes.DFHUNDLN, BmsAttributes.DFHBMFSE);
            assertThat(quad).isEqualTo(quad);
            assertThat(quad).isNotEqualTo("not a quad");
            assertThat(quad).isNotEqualTo(null);
            assertThat(quad).isNotEqualTo(new FieldAttributes(BmsAttributes.DFHGREEN,
                    BmsAttributes.DFHBLUE, BmsAttributes.DFHUNDLN, BmsAttributes.DFHBMFSE));
            assertThat(quad).isNotEqualTo(new FieldAttributes(BmsAttributes.DFHRED,
                    BmsAttributes.DFHGREEN, BmsAttributes.DFHUNDLN, BmsAttributes.DFHBMFSE));
            assertThat(quad).isNotEqualTo(new FieldAttributes(BmsAttributes.DFHRED,
                    BmsAttributes.DFHBLUE, BmsAttributes.DFHREVRS, BmsAttributes.DFHBMFSE));
            assertThat(quad).isNotEqualTo(new FieldAttributes(BmsAttributes.DFHRED,
                    BmsAttributes.DFHBLUE, BmsAttributes.DFHUNDLN, BmsAttributes.DFHBMPRO));
        }

        @Test
        @DisplayName("toString names the four bytes in hexadecimal with the colour's mnemonic")
        void rendering() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            assertThat(quad.toString())
                    .startsWith("{C=" + BmsAttributes.toHex(BmsAttributes.DFHRED))
                    .contains(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED))
                    .contains("P=", "H=", "V=")
                    .endsWith("}");
        }

        @Test
        @DisplayName("attributeItems names all 148 items exactly as the copybook does")
        void attributeItems() {
            Map<String, Byte> items = lowValues().attributeItems();
            assertThat(items).hasSize(37 * 4);
            assertThat(items).containsKeys("TRNNAMEC", "TRNNAMEP", "TRNNAMEH", "TRNNAMEV",
                    "ACCTSIDC", "INFOMSGC", "ERRMSGV");
            assertThat(items.values()).allMatch(value -> value == 0);
            assertThat(items.keySet().iterator().next()).isEqualTo("TRNNAMEC");
        }

        @Test
        @DisplayName("attributeQuads exposes the live holders in copybook order, unmodifiably")
        void attributeQuadsView() {
            AccountViewResponse response = lowValues();
            Map<ScreenField, FieldAttributes> quads = response.attributeQuads();
            assertThat(quads).hasSize(37);
            assertThat(quads.keySet()).containsExactly(ScreenField.values());
            response.attributes(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            assertThat(quads.get(ScreenField.ERRMSG).isRedHighlighted()).isTrue();
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> quads.put(ScreenField.ERRMSG, new FieldAttributes()));
        }

        @Test
        @DisplayName("addressing a quad needs a field")
        void requiresAField() {
            assertThatNullPointerException().isThrownBy(() -> lowValues().attributes(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().attributes(null));
        }

        @Test
        @DisplayName("a quad set before build survives the build")
        void quadSetOnTheBuilderSurvives() {
            Builder builder = AccountViewResponse.builder();
            builder.attributes(ScreenField.ACSTTUS).setColour(BmsAttributes.DFHRED);
            assertThat(builder.build().attributes(ScreenField.ACSTTUS).isRedHighlighted()).isTrue();
        }
    }

    @Nested
    @DisplayName("CSSETATY applied - the decision stays in FieldAttributeSetter")
    class Highlighting {

        @Test
        @DisplayName("NOT_OK on re-entry paints the field red and leaves its content alone")
        void notOkOnReenter() {
            AccountViewResponse before = AccountViewResponse.builder()
                    .acctsid("00000000011")
                    .build();
            AccountViewResponse after =
                    before.withHighlight(ScreenField.ACCTSID, FieldValidationState.NOT_OK, true);
            assertThat(after.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(after.getAcctsid())
                    .as("only a BLANK field gets the asterisk")
                    .isEqualTo("00000000011");
        }

        @Test
        @DisplayName("BLANK on re-entry paints the field red and writes '*' padded to the width")
        void blankOnReenter() {
            AccountViewResponse after = lowValues()
                    .withHighlight(ScreenField.ACCTSID, FieldValidationState.BLANK, true);
            assertThat(after.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(after.getAcctsid()).isEqualTo("*          ").hasSize(11);
        }

        @Test
        @DisplayName("on first entry nothing is painted and nothing is marked - gate G38")
        void nothingOnFirstEntry() {
            AccountViewResponse before = lowValues();
            AccountViewResponse afterBlank =
                    before.withHighlight(ScreenField.ACCTSID, FieldValidationState.BLANK, false);
            AccountViewResponse afterNotOk =
                    before.withHighlight(ScreenField.ACCTSID, FieldValidationState.NOT_OK, false);
            assertThat(afterBlank.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(afterBlank.getAcctsid()).isEqualTo(NUL.repeat(11));
            assertThat(afterNotOk.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(afterBlank).isEqualTo(before);
            assertThat(afterNotOk).isEqualTo(before);
        }

        @Test
        @DisplayName("OK changes nothing, on either entry")
        void okChangesNothing() {
            AccountViewResponse before = populated();
            assertThat(before.withHighlight(ScreenField.ACCTSID, FieldValidationState.OK, true))
                    .isEqualTo(before);
            assertThat(before.withHighlight(ScreenField.ACCTSID, FieldValidationState.OK, false))
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the payload is immutable: the original is untouched by a highlight")
        void originalIsUntouched() {
            AccountViewResponse before = lowValues();
            AccountViewResponse after =
                    before.withHighlight(ScreenField.ACCTSID, FieldValidationState.BLANK, true);
            assertThat(before.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(before.getAcctsid()).isEqualTo(NUL.repeat(11));
            assertThat(after).isNotSameAs(before).isNotEqualTo(before);
        }

        @Test
        @DisplayName("a decision resolved for one field cannot be applied to another")
        void decisionIsFieldQualified() {
            FieldHighlight decision = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ScreenField.ACCTSID.label(), AccountViewResponse.MAP_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> lowValues().withHighlight(ScreenField.ACSTTUS, decision))
                    .withMessageContaining("ACCTSID")
                    .withMessageContaining("ACSTTUS");
        }

        @Test
        @DisplayName("an anonymous decision - no field named - is accepted for any field")
        void anonymousDecisionIsAccepted() {
            FieldHighlight decision = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true);
            assertThat(decision.screenFieldPrefix()).isEmpty();
            AccountViewResponse after = lowValues().withHighlight(ScreenField.ACSTTUS, decision);
            assertThat(after.attributes(ScreenField.ACSTTUS).isRedHighlighted()).isTrue();
        }

        @Test
        @DisplayName("an untouched decision leaves an equal payload")
        void untouchedDecision() {
            FieldHighlight decision = FieldHighlight.none(ScreenField.ACCTSID.label(),
                    AccountViewResponse.MAP_NAME);
            assertThat(decision.untouched()).isTrue();
            AccountViewResponse before = populated();
            assertThat(before.withHighlight(ScreenField.ACCTSID, decision)).isEqualTo(before);
        }

        @Test
        @DisplayName("both arguments are required, on both overloads")
        void argumentsAreRequired() {
            AccountViewResponse response = lowValues();
            FieldHighlight decision = FieldAttributeSetter.resolve(FieldValidationState.OK, true);
            assertThatNullPointerException().isThrownBy(() -> response.withHighlight(null, decision));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.withHighlight(ScreenField.ACCTSID, (FieldHighlight) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.withHighlight(null, FieldValidationState.OK, true));
            assertThatNullPointerException().isThrownBy(
                    () -> response.withHighlight(ScreenField.ACCTSID, (FieldValidationState) null, true));
        }
    }

    @Nested
    @DisplayName("The 955-byte group image")
    class GroupImage {

        @Test
        @DisplayName("it is exactly 955 bytes under either code page")
        void length() {
            assertThat(populated().toGroupImage(ASCII_CODEC)).hasSize(955);
            assertThat(populated().toGroupImage(EBCDIC_CODEC)).hasSize(955);
        }

        @Test
        @DisplayName("the TIOAPFX prefix and every FILLER span are written as LOW-VALUES")
        void prefixAndFillerAreLowValues() {
            byte[] image = populated().toGroupImage(ASCII_CODEC);
            for (int index = 0; index < AccountViewResponse.TIOAPFX_LENGTH; index++) {
                assertThat(image[index]).as("TIOAPFX byte %d", index).isEqualTo((byte) 0);
            }
            for (ScreenField field : ScreenField.values()) {
                for (int offset = 0; offset < AccountViewResponse.FILLER_LENGTH; offset++) {
                    assertThat(image[field.fillerOffset() + offset])
                            .as("%s FILLER byte %d", field, offset)
                            .isEqualTo((byte) 0);
                }
            }
        }

        @Test
        @DisplayName("each field's data lands at its declared offset, in the codec's code page")
        void dataLandsAtItsOffset() {
            AccountViewResponse response = populated();
            byte[] image = response.toGroupImage(EBCDIC_CODEC);
            for (ScreenField field : ScreenField.values()) {
                byte[] span = java.util.Arrays.copyOfRange(image, field.dataOffset(),
                        field.endOffsetExclusive());
                assertThat(new String(span, EBCDIC))
                        .as("%s", field.describe())
                        .isEqualTo(response.value(field));
            }
        }

        @Test
        @DisplayName("the four attribute bytes are written raw, not through the charset")
        void attributeBytesAreRaw() {
            AccountViewResponse response = lowValues();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            response.attributes(ScreenField.ACCTSID).setPs(BmsAttributes.DFHBLUE);
            response.attributes(ScreenField.ACCTSID).setHilight(BmsAttributes.DFHUNDLN);
            response.attributes(ScreenField.ACCTSID).setValidn(BmsAttributes.DFHBMFSE);
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                byte[] image = response.toGroupImage(codec);
                assertThat(image[ScreenField.ACCTSID.colourOffset()]).isEqualTo(BmsAttributes.DFHRED);
                assertThat(image[ScreenField.ACCTSID.psOffset()]).isEqualTo(BmsAttributes.DFHBLUE);
                assertThat(image[ScreenField.ACCTSID.hilightOffset()]).isEqualTo(BmsAttributes.DFHUNDLN);
                assertThat(image[ScreenField.ACCTSID.validnOffset()]).isEqualTo(BmsAttributes.DFHBMFSE);
            }
        }

        @Test
        @DisplayName("the round trip is exact for a payload at its initial carrier state")
        void roundTrips() {
            AccountViewResponse response = AccountViewResponse.builder()
                    .screenTitles()
                    .screenIdentity()
                    .acctsid("00000000011")
                    .acurbalAmount(new BigDecimal("1234.56"))
                    .acstssnFromSsn(SSN)
                    .errmsg("Account number not provided")
                    .build();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR);

            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                AccountViewResponse read =
                        AccountViewResponse.fromGroupImage(response.toGroupImage(codec), codec);
                assertThat(read).isEqualTo(response);
                assertThat(read.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
                assertThat(read.attributes(ScreenField.INFOMSG).isNonDisplay()).isTrue();
            }
        }

        @Test
        @DisplayName("LOW-VALUES survives the round trip on both code pages")
        void lowValuesRoundTrip() {
            AccountViewResponse response = lowValues();
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                byte[] image = response.toGroupImage(codec);
                assertThat(image).containsOnly((byte) 0);
                assertThat(AccountViewResponse.fromGroupImage(image, codec)).isEqualTo(response);
            }
        }

        @Test
        @DisplayName("an asterisk survives the round trip")
        void asteriskRoundTrip() {
            AccountViewResponse response = AccountViewResponse.builder().acctsid(ASTERISK).build();
            assertThat(AccountViewResponse.fromGroupImage(response.toGroupImage(ASCII_CODEC),
                    ASCII_CODEC).getAcctsid()).isEqualTo("*          ");
        }

        @Test
        @DisplayName("an image of the wrong length is rejected by name and by number")
        void rejectsAWrongLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(new byte[954], ASCII_CODEC))
                    .withMessageContaining("955")
                    .withMessageContaining("954");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(new byte[0], ASCII_CODEC));
        }

        @Test
        @DisplayName("both arguments are required")
        void argumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().toGroupImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(null, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(new byte[955], null));
        }

        @Test
        @DisplayName("fieldImages names the 37 xxxO items in copybook order and does not trim")
        void fieldImages() {
            Map<String, String> images = populated().fieldImages();
            assertThat(images).hasSize(37);
            assertThat(images.keySet().iterator().next()).isEqualTo("TRNNAMEO");
            assertThat(images).containsKeys("ACCTSIDO", "ACURBALO", "ACSTSSNO", "ERRMSGO");
            for (ScreenField field : ScreenField.values()) {
                assertThat(images.get(field.symbolicItemName()))
                        .as("%s", field)
                        .hasSize(field.length());
            }
        }
    }

    @Nested
    @DisplayName("Conversation state - it travels in the payload, never in a session")
    class ConversationState {

        @Test
        @DisplayName("a fresh payload carries an empty commarea and a fresh work area")
        void initialCarriers() {
            AccountViewResponse response = lowValues();
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.getCardScreenState()).isEqualTo(new CardScreenState());
        }

        @Test
        @DisplayName("ENTER and REENTER are read from the carried commarea")
        void enterAndReenter() {
            AccountViewResponse enter = AccountViewResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmEnter())
                    .build();
            AccountViewResponse reenter = AccountViewResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .build();
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();
            assertThat(reenter.isReenter()).isTrue();
            assertThat(reenter.isEnter()).isFalse();
        }

        @Test
        @DisplayName("the work area is copied in and copied out, so the payload cannot be mutated")
        void workAreaIsCopied() {
            CardScreenState supplied = new CardScreenState();
            supplied.setCcardErrorMsg("Account number not provided");
            AccountViewResponse response =
                    AccountViewResponse.builder().cardScreenState(supplied).build();

            supplied.setCcardErrorMsg("changed after the build");
            assertThat(response.getCardScreenState().getCcardErrorMsg())
                    .as("the payload copied the area on store")
                    .startsWith("Account number not provided");

            CardScreenState handedOut = response.getCardScreenState();
            handedOut.setCcardErrorMsg("changed via the getter");
            assertThat(response.getCardScreenState().getCcardErrorMsg())
                    .as("the payload copied the area on read")
                    .startsWith("Account number not provided");
        }

        @Test
        @DisplayName("the error text reaches the client through ERRMSGO and the work area both")
        void errorTextTravelsTwice() {
            String returnMessage = "Account number not provided";
            CardScreenState workArea = new CardScreenState();
            workArea.setCcardErrorMsg(returnMessage);
            AccountViewResponse response = AccountViewResponse.builder()
                    .errmsg(returnMessage)
                    .cardScreenState(workArea)
                    .build();
            assertThat(response.getErrmsg())
                    .as("ERRMSGO PIC X(78) widens WS-RETURN-MSG PIC X(75) by three spaces")
                    .hasSize(78)
                    .startsWith(returnMessage);
            assertThat(response.getCardScreenState().getCcardErrorMsg())
                    .as("CCARD-ERROR-MSG carries the same text; the work area stores it unaltered")
                    .isEqualTo(returnMessage);
        }

        @Test
        @DisplayName("thisScreenAsNextTarget re-displays CAVW, and the mapset loses its eighth byte")
        void thisScreenAsNextTarget() {
            AccountViewResponse response =
                    AccountViewResponse.builder().thisScreenAsNextTarget().build();
            assertThat(response.getNextProgram()).isEqualTo("COACTVWC");
            assertThat(response.getNextMapset())
                    .as("LIT-THISMAPSET PIC X(8) into CCARD-NEXT-MAPSET PIC X(7)")
                    .isEqualTo("COACTVW")
                    .hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("CACTVWA");
        }

        @Test
        @DisplayName("the XCTL target is echoed rather than decided, and is space-padded to width")
        void xctlTargetIsEchoed() {
            AccountViewResponse response = AccountViewResponse.builder()
                    .nextTarget("COMEN01C", "COMEN01", "COMEN1A")
                    .build();
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextMapset()).isEqualTo("COMEN01");
            assertThat(response.getNextMap()).isEqualTo("COMEN1A");

            AccountViewResponse shortTarget =
                    AccountViewResponse.builder().nextProgram("COADM01C").nextMapset("A").build();
            assertThat(shortTarget.getNextMapset()).isEqualTo("A      ").hasSize(7);
        }

        @Test
        @DisplayName("neither carrier may be absent")
        void carriersAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().cardScreenState(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().navigationContext(null));
        }

        @Test
        @DisplayName("every declared field is final, and none is a session type - gates G37 and G53")
        void noSessionTypesAndNoMutableState() {
            java.util.List<java.lang.reflect.Field> declared =
                    java.util.Arrays.stream(AccountViewResponse.class.getDeclaredFields())
                            .filter(field -> !field.isSynthetic())
                            .toList();
            assertThat(declared).isNotEmpty();
            assertThat(declared)
                    .as("no HttpSession, no @SessionAttributes, no server-side conversation")
                    .noneMatch(field -> field.getType().getName().contains("HttpSession"));
            assertThat(declared)
                    .as("no mutable static state and no reassignable member")
                    .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        }
    }

    @Nested
    @DisplayName("Construction, derivation and JSON - no Spring context anywhere")
    class Construction {

        @Test
        @DisplayName("a payload is built with a plain builder and no framework")
        void plainBuilder() {
            assertThatNoException().isThrownBy(() -> AccountViewResponse.builder().build());
            assertThat(new Builder().build()).isEqualTo(AccountViewResponse.initialGroup());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field is addressable by ScreenField, for read and for write")
        void addressableByField(ScreenField field) {
            String candidate = "Z".repeat(field.length());
            AccountViewResponse response =
                    AccountViewResponse.builder().value(field, candidate).build();
            assertThat(response.value(field)).isEqualTo(candidate);
            for (ScreenField other : ScreenField.values()) {
                if (other != field) {
                    assertThat(response.value(other))
                            .as("%s must be untouched", other)
                            .isEqualTo(NUL.repeat(other.length()));
                }
            }
        }

        @Test
        @DisplayName("addressing a field for read or write needs a field")
        void addressingRequiresAField() {
            assertThatNullPointerException().isThrownBy(() -> lowValues().value(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.builder().value(null, "x"));
        }

        @Test
        @DisplayName("toBuilder rebuilds an equal payload, quads and carriers included")
        void toBuilderRebuilds() {
            AccountViewResponse response = populated();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHNEUTR);
            AccountViewResponse rebuilt = response.toBuilder().build();
            assertThat(rebuilt).isEqualTo(response).isNotSameAs(response);
            assertThat(rebuilt.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(rebuilt.attributes(ScreenField.INFOMSG).isNeutral()).isTrue();
        }

        @Test
        @DisplayName("toBuilder lets one field change without disturbing the other 36")
        void toBuilderDerives() {
            AccountViewResponse response = populated();
            AccountViewResponse derived = response.toBuilder().acsttus("N").build();
            assertThat(derived.getAcsttus()).isEqualTo("N");
            assertThat(derived.getAcctsid()).isEqualTo(response.getAcctsid());
            assertThat(derived).isNotEqualTo(response);
        }

        @Test
        @DisplayName("build is idempotent - a normalised value is not re-padded")
        void buildIsIdempotent() {
            AccountViewResponse once = populated();
            assertThat(once.toBuilder().build().toBuilder().build()).isEqualTo(once);
        }

        @Test
        @DisplayName("it serialises to the 37 lower-cased DFHMDF labels plus five envelope members")
        void serialises() throws Exception {
            JsonNode json = new ObjectMapper().valueToTree(populated());
            for (String label : LABELS) {
                assertThat(json.has(label.toLowerCase(java.util.Locale.ROOT)))
                        .as("JSON member for %s", label)
                        .isTrue();
            }
            assertThat(json.has("nextProgram")).isTrue();
            assertThat(json.has("nextMapset")).isTrue();
            assertThat(json.has("nextMap")).isTrue();
            assertThat(json.has("cardScreenState")).isTrue();
            assertThat(json.has("navigationContext")).isTrue();
            assertThat(json.size()).isEqualTo(37 + 5);
        }

        @Test
        @DisplayName("the attribute quad never reaches the wire - it is metadata, not payload")
        void quadIsNotSerialised() throws Exception {
            AccountViewResponse response = populated();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            String json = new ObjectMapper().writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("attributes")
                    .doesNotContain("attributeItems")
                    .doesNotContain("fieldImages")
                    .doesNotContain("ACCTSIDC");
        }

        @Test
        @DisplayName("it deserialises through the builder, so the immutable type still round-trips")
        void deserialises() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AccountViewResponse response = populated();
            String json = mapper.writeValueAsString(response);
            AccountViewResponse read = mapper.readValue(json, AccountViewResponse.class);
            for (ScreenField field : ScreenField.values()) {
                assertThat(read.value(field)).as("%s", field).isEqualTo(response.value(field));
            }
            assertThat(read.getNextProgram()).isEqualTo(response.getNextProgram());
            assertThat(read.getNextMapset()).isEqualTo(response.getNextMapset());
            assertThat(read.getNextMap()).isEqualTo(response.getNextMap());
            assertThat(read.getNavigationContext()).isEqualTo(response.getNavigationContext());
        }

        @Test
        @DisplayName("a sparse body leaves every omitted field at LOW-VALUES")
        void sparseBody() throws Exception {
            AccountViewResponse read = new ObjectMapper()
                    .readValue("{\"acctsid\":\"00000000011\"}", AccountViewResponse.class);
            assertThat(read.getAcctsid()).isEqualTo("00000000011");
            assertThat(read.getErrmsg()).isEqualTo(NUL.repeat(78));
            assertThat(read.getNextProgram()).isEqualTo(" ".repeat(8));
        }
    }

    @Nested
    @DisplayName("Value semantics and diagnostics")
    class ValueSemantics {

        @Test
        @DisplayName("equality covers the 37 fields, the triple, both carriers and all 37 quads")
        void equality() {
            AccountViewResponse response = populated();
            assertThat(response).isEqualTo(response).isEqualTo(populated());
            assertThat(response).hasSameHashCodeAs(populated());
            assertThat(response).isNotEqualTo(null).isNotEqualTo("not a payload");

            assertThat(response).isNotEqualTo(response.toBuilder().acsttus("N").build());
            assertThat(response).isNotEqualTo(response.toBuilder().nextProgram("COMEN01C").build());
            assertThat(response).isNotEqualTo(response.toBuilder().nextMapset("COMEN01").build());
            assertThat(response).isNotEqualTo(response.toBuilder().nextMap("COMEN1A").build());
            assertThat(response).isNotEqualTo(response.toBuilder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .build());

            CardScreenState other = new CardScreenState();
            other.setCcardErrorMsg("different");
            assertThat(response).isNotEqualTo(response.toBuilder().cardScreenState(other).build());

            AccountViewResponse repainted = response.toBuilder().build();
            repainted.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            assertThat(response)
                    .as("a red field is not the same screen as a default-coloured one")
                    .isNotEqualTo(repainted);
        }

        @Test
        @DisplayName("describe names the map, the transaction, the target and the two messages")
        void describe() {
            String described = populated().describe();
            assertThat(described)
                    .startsWith("CACTVWAO[map=CACTVWA")
                    .contains("tran=CAVW")
                    .contains("next=COACTVWC/COACTVW/CACTVWA")
                    .contains("infomsg='Displaying details of given Account'")
                    .endsWith("]");
        }

        @Test
        @DisplayName("toString names every field by its DFHMDF label and quotes every value")
        void rendering() {
            String rendered = populated().toString();
            assertThat(rendered).startsWith("AccountViewResponse[").endsWith("]");
            for (String label : LABELS) {
                assertThat(rendered).as("%s is named", label).contains(label + "='");
            }
            assertThat(rendered)
                    .contains("nextProgram='")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");
        }

        @Test
        @DisplayName("nothing is masked - practice B6, the same divergence AccountViewRequest records")
        void nothingIsMasked() {
            AccountViewResponse response = populated();
            assertThat(response.getAcstssn()).startsWith(HYPHENATED_SSN);
            assertThat(response.getAcstdob()).startsWith("1970-01-01");
            assertThat(response.getAcsgovt()).startsWith("GOVTID9988776655");
            assertThat(response.toString())
                    .as("COACTVWC puts these on a 3270 in the clear; hiding them would change behaviour")
                    .contains(HYPHENATED_SSN)
                    .contains("1970-01-01")
                    .contains("GOVTID9988776655")
                    .doesNotContain("[REDACTED]");
        }

        @Test
        @DisplayName("the getters are the DFHMDF labels lower-cased, one rule applied 37 times")
        void gettersFollowTheLabels() throws Exception {
            AccountViewResponse response = populated();
            for (ScreenField field : ScreenField.values()) {
                String label = field.label();
                String getter = "get" + label.charAt(0)
                        + label.substring(1).toLowerCase(java.util.Locale.ROOT);
                assertThat(AccountViewResponse.class.getMethod(getter).invoke(response))
                        .as("%s", getter)
                        .isEqualTo(response.value(field));
            }
        }
    }

}
