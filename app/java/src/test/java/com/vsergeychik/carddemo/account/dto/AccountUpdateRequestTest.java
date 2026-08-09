package com.vsergeychik.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.AcctSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CommArea;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CustSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.Details;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.FieldMetadata;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract of {@link AccountUpdateRequest}, asserted against the three files that define it:
 * {@code app/cpy-bms/COACTUP.CPY}, {@code app/bms/COACTUP.bms} and
 * {@code app/cbl/COACTUPC.cbl:652-849}.
 *
 * <p>The width, line-number, screen-position and data-offset tables below are an <em>independent second
 * transcription</em> of those sources. They are deliberately written out as literals rather than read from
 * the class under test, because a test that asks the implementation what it believes and then agrees with
 * it proves nothing. Where a number here disagrees with the class, one of the two transcriptions is wrong
 * and the build says so.
 *
 * <p>Nothing here needs a Spring context, a servlet container or a running application - which is itself
 * part of the contract (practice <strong>B10</strong>): {@code parity/ParityHarness} and
 * {@code AccountUpdateService}'s own tests build this type exactly the way these tests do.
 */
@DisplayName("AccountUpdateRequest - COACTUP CACTUPAI, the CAUP inbound payload")
class AccountUpdateRequestTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the EBCDIC datasets, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** A codec over {@link #ASCII}. */
    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /** A codec over {@link #EBCDIC}, to prove nothing here assumes ASCII byte values. */
    private static final FixedWidthCodec EBCDIC_CODEC = new FixedWidthCodec(EBCDIC);

    /** The 54 {@code DFHMDF} labels, in {@code app/bms/COACTUP.bms} declaration order. */
    private static final String[] LABELS = {
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "ACSTTUS",
            "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM", "EXPYEAR", "EXPMON", "EXPDAY", "ACSHLIM",
            "RISYEAR", "RISMON", "RISDAY", "ACURBAL", "ACRCYCR", "AADDGRP", "ACRCYDB", "ACSTNUM",
            "ACTSSN1", "ACTSSN2", "ACTSSN3", "DOBYEAR", "DOBMON", "DOBDAY", "ACSTFCO", "ACSFNAM",
            "ACSMNAM", "ACSLNAM", "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY",
            "ACSPH1A", "ACSPH1B", "ACSPH1C", "ACSGOVT", "ACSPH2A", "ACSPH2B", "ACSPH2C", "ACSEFTC",
            "ACSPFLG", "INFOMSG", "ERRMSG", "FKEYS", "FKEY05", "FKEY12"};

    /**
     * The 54 declared widths, from the {@code xxxI PICTURE} clauses of
     * {@code app/cpy-bms/COACTUP.CPY}, cross-checked against the {@code DFHMDF LENGTH=} operands of
     * {@code app/bms/COACTUP.bms}. They sum to 705.
     */
    private static final int[] WIDTHS = {4, 40, 8, 8, 40, 8, 11, 1, 4, 2, 2, 15, 4, 2, 2, 15, 4, 2, 2,
            15, 15, 10, 15, 9, 3, 2, 4, 4, 2, 2, 3, 25, 25, 25, 50, 2, 50, 5, 50, 3, 3, 3, 4, 20, 3, 3,
            4, 10, 1, 45, 78, 21, 7, 10};

    /** The 54 declaring lines of {@code app/cpy-bms/COACTUP.CPY}: 24, then every sixth line to 342. */
    private static final int[] COPYBOOK_LINES = {24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102,
            108, 114, 120, 126, 132, 138, 144, 150, 156, 162, 168, 174, 180, 186, 192, 198, 204, 210,
            216, 222, 228, 234, 240, 246, 252, 258, 264, 270, 276, 282, 288, 294, 300, 306, 312, 318,
            324, 330, 336, 342};

    /** The 54 declaring lines of {@code app/bms/COACTUP.bms}. */
    private static final int[] MAPSET_LINES = {34, 38, 47, 57, 61, 70, 84, 94, 104, 112, 120, 132, 142,
            150, 158, 170, 180, 188, 196, 208, 219, 229, 240, 254, 264, 272, 280, 291, 299, 307, 318,
            336, 342, 348, 356, 366, 372, 382, 392, 402, 412, 417, 422, 433, 443, 448, 453, 464, 474,
            480, 489, 493, 498, 503};

    /** The 54 {@code POS} rows. */
    private static final int[] ROWS = {1, 1, 1, 2, 2, 2, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 10,
            10, 12, 12, 12, 12, 13, 13, 13, 13, 15, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19, 19, 19, 20,
            20, 20, 20, 20, 22, 23, 24, 24, 24};

    /** The 54 {@code POS} columns. */
    private static final int[] COLUMNS = {7, 21, 71, 7, 21, 71, 38, 70, 17, 24, 29, 61, 17, 24, 29, 61,
            17, 24, 29, 61, 61, 23, 61, 23, 55, 61, 66, 23, 30, 35, 62, 1, 28, 55, 10, 73, 10, 73, 10,
            73, 10, 14, 18, 58, 10, 14, 18, 41, 78, 23, 1, 1, 23, 31};

    /** The 54 data offsets within the 1095-byte group image. */
    private static final int[] DATA_OFFSETS = {19, 30, 77, 92, 107, 154, 169, 187, 195, 206, 215, 224,
            246, 257, 266, 275, 297, 308, 317, 326, 348, 370, 387, 409, 425, 435, 444, 455, 466, 475,
            484, 494, 526, 558, 590, 647, 656, 713, 725, 782, 792, 802, 812, 823, 850, 860, 870, 881,
            898, 906, 958, 1043, 1071, 1085};

    /** A bean validator, built without a Spring context. */
    private static Validator validator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }

    /**
     * The declaration-order index of one label in {@link #LABELS}.
     *
     * @param field the field
     * @return its 0-based index
     */
    private static int indexOf(ScreenField field) {
        for (int index = 0; index < LABELS.length; index++) {
            if (LABELS[index].equals(field.label())) {
                return index;
            }
        }
        throw new AssertionError("Label " + field.label() + " is not in the independent transcription");
    }

    @Nested
    @DisplayName("Screen identity and the group geometry")
    class Identity {

        @Test
        @DisplayName("names the CSD transaction, the program and the mapset and map as BMS spells them")
        void namesTheScreen() {
            assertThat(AccountUpdateRequest.TRANSACTION_ID).isEqualTo("CAUP");
            assertThat(AccountUpdateRequest.PROGRAM_NAME).isEqualTo("COACTUPC");
            assertThat(AccountUpdateRequest.MAPSET_NAME).isEqualTo("COACTUP");
            assertThat(AccountUpdateRequest.MAP_NAME).isEqualTo("CACTUPA");
            assertThat(AccountUpdateRequest.INPUT_GROUP_NAME).isEqualTo("CACTUPAI");
            assertThat(AccountUpdateRequest.SCREEN_ROWS).isEqualTo(24);
            assertThat(AccountUpdateRequest.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("declares 54 of the mapset's 128 DFHMDF entries")
        void declaresFiftyFourOfOneHundredAndTwentyEight() {
            assertThat(AccountUpdateRequest.FIELD_COUNT).isEqualTo(54);
            assertThat(AccountUpdateRequest.DFHMDF_ENTRY_COUNT).isEqualTo(128);
            assertThat(ScreenField.values()).hasSize(54);
        }

        @Test
        @DisplayName("computes 12 + 54 * 7 + 705 = 1095 for the CACTUPAI group")
        void computesTheGroupLength() {
            assertThat(AccountUpdateRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(AccountUpdateRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(AccountUpdateRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH).isEqualTo(4);
            assertThat(AccountUpdateRequest.FIELD_OVERHEAD).isEqualTo(7);
            assertThat(AccountUpdateRequest.PAYLOAD_LENGTH).isEqualTo(705);
            assertThat(AccountUpdateRequest.GROUP_LENGTH).isEqualTo(1095);
        }

        @Test
        @DisplayName("transcribes 54 entries in every one of the six independent tables")
        void transcribesFiftyFourOfEverything() {
            assertThat(LABELS).hasSize(54);
            assertThat(WIDTHS).hasSize(54);
            assertThat(COPYBOOK_LINES).hasSize(54);
            assertThat(MAPSET_LINES).hasSize(54);
            assertThat(ROWS).hasSize(54);
            assertThat(COLUMNS).hasSize(54);
            assertThat(DATA_OFFSETS).hasSize(54);
        }

        @Test
        @DisplayName("sums the independently transcribed widths to 705 as well")
        void sumsTheIndependentWidthsToSevenHundredAndFive() {
            assertThat(WIDTHS).hasSize(54);
            assertThat(LABELS).hasSize(54);
            int total = 0;
            for (int width : WIDTHS) {
                total += width;
            }
            assertThat(total).isEqualTo(AccountUpdateRequest.PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("uses 1033 of WS-COMMAREA's 2000 bytes: 160 of COMMAREA plus 873 of work area")
        void usesOneThousandAndThirtyThreeOfTwoThousand() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(873);
            assertThat(AccountUpdateRequest.TOTAL_COMMAREA_LENGTH).isEqualTo(1033);
            assertThat(AccountUpdateRequest.COMMAREA_CAPACITY).isEqualTo(2000);
            assertThat(AccountUpdateRequest.TOTAL_COMMAREA_LENGTH)
                    .isLessThan(AccountUpdateRequest.COMMAREA_CAPACITY);
        }
    }

    @Nested
    @DisplayName("The 54 fields, traced field by field to both sources")
    class Fields {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("carries the label, item name, width, both source lines, POS and data offset")
        void carriesItsProvenance(ScreenField field) {
            int index = indexOf(field);
            assertThat(field.label()).isEqualTo(LABELS[index]);
            assertThat(field.symbolicItemName()).isEqualTo(LABELS[index] + "I");
            assertThat(field.length()).isEqualTo(WIDTHS[index]);
            assertThat(field.picture()).isEqualTo("X(" + WIDTHS[index] + ")");
            assertThat(field.isAlphanumeric()).isTrue();
            assertThat(field.copybookLine()).isEqualTo(COPYBOOK_LINES[index]);
            assertThat(field.mapsetLine()).isEqualTo(MAPSET_LINES[index]);
            assertThat(field.screenRow()).isEqualTo(ROWS[index]);
            assertThat(field.screenColumn()).isEqualTo(COLUMNS[index]);
            assertThat(field.dataOffset()).isEqualTo(DATA_OFFSETS[index]);
            assertThat(AccountUpdateRequest.declaredLength(field)).isEqualTo(WIDTHS[index]);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("places its five items at 7 + n bytes, ending where the next field begins")
        void placesItsItemsSevenBytesAhead(ScreenField field) {
            assertThat(field.lengthItemOffset()).isEqualTo(field.dataOffset() - 7);
            assertThat(field.flagItemOffset()).isEqualTo(field.lengthItemOffset() + 2);
            assertThat(field.extendedAttributeItemOffset()).isEqualTo(field.flagItemOffset() + 1);
            assertThat(field.endOffsetExclusive()).isEqualTo(field.dataOffset() + field.length());
            assertThat(field.describe()).contains(field.label(), field.symbolicItemName(),
                    "app/bms/COACTUP.bms:" + field.mapsetLine());
        }

        @Test
        @DisplayName("declares the 24 x 80 screen only: every POS is within SIZE=(24,80)")
        void keepsEveryPositionOnTheScreen() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.screenRow()).isBetween(1, AccountUpdateRequest.SCREEN_ROWS);
                assertThat(field.screenColumn()).isBetween(1, AccountUpdateRequest.SCREEN_COLUMNS);
            }
        }

        @Test
        @DisplayName("declares COACTUP's own fields and none of COACTVW's undivided ones")
        void declaresNeitherPagenoNorTheUndividedViewFields() {
            List<String> labels = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                labels.add(field.label());
            }
            assertThat(labels).contains("FKEYS", "FKEY05", "FKEY12")
                    .doesNotContain("PAGENO", "ADTOPEN", "AEXPDT", "AREISDT", "ACSTSSN", "ACSTDOB");
        }

        @Test
        @DisplayName("looks a field up by its DFHMDF label and rejects one the mapset does not declare")
        void looksUpByLabel() {
            assertThat(ScreenField.ofLabel("ACCTSID")).isEqualTo(ScreenField.ACCTSID);
            assertThat(ScreenField.ofLabel("FKEY12")).isEqualTo(ScreenField.FKEY12);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.ofLabel("PAGENO"))
                    .withMessageContaining("declares no name-labelled DFHMDF");
            assertThatNullPointerException().isThrownBy(() -> ScreenField.ofLabel(null));
        }

        @Test
        @DisplayName("keeps all 20 composite parts separate and adds no merged convenience field")
        void keepsEveryCompositeSplit() {
            List<String> labels = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                labels.add(field.label());
            }
            assertThat(labels).contains("OPNYEAR", "OPNMON", "OPNDAY",
                    "EXPYEAR", "EXPMON", "EXPDAY",
                    "RISYEAR", "RISMON", "RISDAY",
                    "DOBYEAR", "DOBMON", "DOBDAY",
                    "ACTSSN1", "ACTSSN2", "ACTSSN3",
                    "ACSPH1A", "ACSPH1B", "ACSPH1C",
                    "ACSPH2A", "ACSPH2B", "ACSPH2C");
            assertThat(labels).doesNotContain("OPNDATE", "EXPDATE", "RISDATE", "DOBDATE", "ACTSSN",
                    "ACSPHN1", "ACSPHN2");
        }

        @Test
        @DisplayName("reads every field through value() and writes it through withValue()")
        void readsAndWritesEveryFieldByEnumeration() {
            AccountUpdateRequest request = AccountUpdateRequest.initial();
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).isEqualTo(AccountUpdateRequest.spaces(field.length()));
                AccountUpdateRequest changed = request.withValue(field, "Z");
                assertThat(changed.value(field)).isEqualTo("Z");
                assertThat(request.value(field)).isEqualTo(AccountUpdateRequest.spaces(field.length()));
                assertThat(changed.withValue(field, null).value(field))
                        .isEqualTo(AccountUpdateRequest.spaces(field.length()));
            }
            assertThatNullPointerException().isThrownBy(() -> request.value(null));
            assertThatNullPointerException().isThrownBy(() -> request.withValue(null, "Z"));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.declaredLength(null));
        }

        @Test
        @DisplayName("exposes all 54 values keyed by DFHMDF label, in declaration order")
        void exposesFieldValuesByLabel() {
            Map<String, String> values = AccountUpdateRequest.initial()
                    .withValue(ScreenField.ACCTSID, "00000000011")
                    .fieldValues();
            assertThat(values).hasSize(54).containsEntry("ACCTSID", "00000000011");
            assertThat(values.keySet()).containsExactly(LABELS);
        }

        @Test
        @DisplayName("gives every one of the 54 accessors the value the builder was given")
        void givesEveryAccessorItsValue() {
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .trnname("CAUP").title01("T1").curdate("07/18/22").pgmname("COACTUPC")
                    .title02("T2").curtime("12:00:00").acctsid("00000000011").acsttus("Y")
                    .opnyear("2022").opnmon("07").opnday("18").acrdlim("      1000.00")
                    .expyear("2027").expmon("07").expday("18").acshlim("       500.00")
                    .risyear("2024").rismon("01").risday("02").acurbal("       250.00")
                    .acrcycr("        10.00").aaddgrp("GROUP01").acrcydb("        20.00")
                    .acstnum("000000011").actssn1("123").actssn2("45").actssn3("6789")
                    .dobyear("1980").dobmon("02").dobday("29").acstfco("750")
                    .acsfnam("FIRST").acsmnam("MIDDLE").acslnam("LAST")
                    .acsadl1("LINE ONE").acsstte("NY").acsadl2("LINE TWO").acszipc("10001")
                    .acscity("NEW YORK").acsctry("USA")
                    .acsph1a("212").acsph1b("555").acsph1c("0100").acsgovt("GOVT-ID-1")
                    .acsph2a("718").acsph2b("555").acsph2c("0200").acseftc("EFT0000001")
                    .acspflg("Y").infomsg("INFO").errmsg("ERR").fkeys("ENTER=Process F3=Exit")
                    .fkey05("F5=Save").fkey12("F12=Cancel")
                    .build();
            assertThat(request.getTrnname()).isEqualTo("CAUP");
            assertThat(request.getTitle01()).isEqualTo("T1");
            assertThat(request.getCurdate()).isEqualTo("07/18/22");
            assertThat(request.getPgmname()).isEqualTo("COACTUPC");
            assertThat(request.getTitle02()).isEqualTo("T2");
            assertThat(request.getCurtime()).isEqualTo("12:00:00");
            assertThat(request.getAcctsid()).isEqualTo("00000000011");
            assertThat(request.getAcsttus()).isEqualTo("Y");
            assertThat(request.getOpnyear()).isEqualTo("2022");
            assertThat(request.getOpnmon()).isEqualTo("07");
            assertThat(request.getOpnday()).isEqualTo("18");
            assertThat(request.getAcrdlim()).isEqualTo("      1000.00");
            assertThat(request.getExpyear()).isEqualTo("2027");
            assertThat(request.getExpmon()).isEqualTo("07");
            assertThat(request.getExpday()).isEqualTo("18");
            assertThat(request.getAcshlim()).isEqualTo("       500.00");
            assertThat(request.getRisyear()).isEqualTo("2024");
            assertThat(request.getRismon()).isEqualTo("01");
            assertThat(request.getRisday()).isEqualTo("02");
            assertThat(request.getAcurbal()).isEqualTo("       250.00");
            assertThat(request.getAcrcycr()).isEqualTo("        10.00");
            assertThat(request.getAaddgrp()).isEqualTo("GROUP01");
            assertThat(request.getAcrcydb()).isEqualTo("        20.00");
            assertThat(request.getAcstnum()).isEqualTo("000000011");
            assertThat(request.getActssn1()).isEqualTo("123");
            assertThat(request.getActssn2()).isEqualTo("45");
            assertThat(request.getActssn3()).isEqualTo("6789");
            assertThat(request.getDobyear()).isEqualTo("1980");
            assertThat(request.getDobmon()).isEqualTo("02");
            assertThat(request.getDobday()).isEqualTo("29");
            assertThat(request.getAcstfco()).isEqualTo("750");
            assertThat(request.getAcsfnam()).isEqualTo("FIRST");
            assertThat(request.getAcsmnam()).isEqualTo("MIDDLE");
            assertThat(request.getAcslnam()).isEqualTo("LAST");
            assertThat(request.getAcsadl1()).isEqualTo("LINE ONE");
            assertThat(request.getAcsstte()).isEqualTo("NY");
            assertThat(request.getAcsadl2()).isEqualTo("LINE TWO");
            assertThat(request.getAcszipc()).isEqualTo("10001");
            assertThat(request.getAcscity()).isEqualTo("NEW YORK");
            assertThat(request.getAcsctry()).isEqualTo("USA");
            assertThat(request.getAcsph1a()).isEqualTo("212");
            assertThat(request.getAcsph1b()).isEqualTo("555");
            assertThat(request.getAcsph1c()).isEqualTo("0100");
            assertThat(request.getAcsgovt()).isEqualTo("GOVT-ID-1");
            assertThat(request.getAcsph2a()).isEqualTo("718");
            assertThat(request.getAcsph2b()).isEqualTo("555");
            assertThat(request.getAcsph2c()).isEqualTo("0200");
            assertThat(request.getAcseftc()).isEqualTo("EFT0000001");
            assertThat(request.getAcspflg()).isEqualTo("Y");
            assertThat(request.getInfomsg()).isEqualTo("INFO");
            assertThat(request.getErrmsg()).isEqualTo("ERR");
            assertThat(request.getFkeys()).isEqualTo("ENTER=Process F3=Exit");
            assertThat(request.getFkey05()).isEqualTo("F5=Save");
            assertThat(request.getFkey12()).isEqualTo("F12=Cancel");
        }

        @Test
        @DisplayName("FKEYS's INITIAL literal is exactly its declared 21 characters")
        void functionKeyLegendsMatchTheirInitialLiterals() {
            assertThat("ENTER=Process F3=Exit").hasSize(AccountUpdateRequest.FKEYS_LENGTH);
            assertThat("F5=Save").hasSize(AccountUpdateRequest.FKEY05_LENGTH);
            assertThat("F12=Cancel").hasSize(AccountUpdateRequest.FKEY12_LENGTH);
            assertThat("mm/dd/yy").hasSize(AccountUpdateRequest.CURDATE_LENGTH);
            assertThat("hh:mm:ss").hasSize(AccountUpdateRequest.CURTIME_LENGTH);
            assertThat("999").hasSize(AccountUpdateRequest.ACTSSN1_LENGTH);
            assertThat("99").hasSize(AccountUpdateRequest.ACTSSN2_LENGTH);
            assertThat("9999").hasSize(AccountUpdateRequest.ACTSSN3_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction, immutability and the figurative constants")
    class Construction {

        @Test
        @DisplayName("fills every field with its declared width in spaces on a first entry")
        void fillsEveryFieldWithSpaces() {
            AccountUpdateRequest request = AccountUpdateRequest.initial();
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).hasSize(field.length()).isBlank();
            }
            assertThat(request.getCommArea().changeAction().isDetailsNotFetched()).isTrue();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isZero();
        }

        @Test
        @DisplayName("withAccountFilter stores the filter verbatim and puts the cursor on ACCTSID")
        void withAccountFilterPositionsTheCursor() {
            AccountUpdateRequest request = AccountUpdateRequest.withAccountFilter("*");
            assertThat(request.getAcctsid()).isEqualTo("*");
            assertThat(request.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(request.metadata(ScreenField.ACSTTUS).isCursorHere()).isFalse();
            assertThat(AccountUpdateRequest.withAccountFilter(null).getAcctsid())
                    .isEqualTo(AccountUpdateRequest.spaces(AccountUpdateRequest.ACCTSID_LENGTH));
        }

        @Test
        @DisplayName("produces an equal request through toBuilder and an unequal one after a change")
        void roundTripsThroughTheBuilder() {
            AccountUpdateRequest request = AccountUpdateRequest.withAccountFilter("00000000011")
                    .withNavigationContext(NavigationContext.empty().withPgmReenter())
                    .withCommArea(CommArea.initialised().withChangeAction(ChangeAction.showDetails()));
            assertThat(request.toBuilder().build()).isEqualTo(request)
                    .hasSameHashCodeAs(request);
            assertThat(request.withValue(ScreenField.ACSTTUS, "N")).isNotEqualTo(request);
        }

        @Test
        @DisplayName("never shares mutable state: the card work area is copied both ways")
        void copiesTheMutableCardWorkArea() {
            CardScreenState supplied = new CardScreenState();
            supplied.setCcardNextProg("COACTUPC");
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .cardScreenState(supplied)
                    .build();
            supplied.setCcardNextProg("SOMEELSE");
            assertThat(request.getCardScreenState().getCcardNextProg().trim()).isEqualTo("COACTUPC");
            CardScreenState handedOut = request.getCardScreenState();
            handedOut.setCcardNextProg("MUTATED!");
            assertThat(request.getCardScreenState().getCcardNextProg().trim()).isEqualTo("COACTUPC");
            assertThat(AccountUpdateRequest.builder().cardScreenState(null).build()
                    .getCardScreenState()).isNotNull();
        }

        @Test
        @DisplayName("keeps a null navigation context, because EIBCALEN = 0 is a state and not a gap")
        void keepsTheColdStartDistinct() {
            AccountUpdateRequest cold = AccountUpdateRequest.builder().navigationContext(null).build();
            assertThat(cold.getNavigationContext()).isNull();
            assertThat(cold.hasNavigationContext()).isFalse();
            AccountUpdateRequest warm = cold.withNavigationContext(NavigationContext.empty());
            assertThat(warm.hasNavigationContext()).isTrue();
            assertThat(warm.commareaLength()).isEqualTo(1033);
            assertThat(warm.isEnter()).isTrue();
            assertThat(warm.withNavigationContext(NavigationContext.empty().withPgmReenter())
                    .isReenter()).isTrue();
        }

        @Test
        @DisplayName("reads ENTER and REENTER from the communication area in all four states")
        void readsBothProgramContexts() {
            AccountUpdateRequest cold = AccountUpdateRequest.initial();
            assertThat(cold.getPgmContext()).isZero();
            assertThat(cold.isEnter()).isTrue();
            assertThat(cold.isReenter()).isFalse();
            AccountUpdateRequest onEnter =
                    cold.withNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(onEnter.getPgmContext()).isZero();
            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();
            AccountUpdateRequest onReenter =
                    cold.withNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(onReenter.getPgmContext()).isEqualTo(1);
            assertThat(onReenter.isEnter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();
        }

        @Test
        @DisplayName("substitutes an initialised work area for a null one")
        void substitutesAnInitialisedWorkArea() {
            assertThat(AccountUpdateRequest.builder().commArea(null).build().getCommArea())
                    .isEqualTo(CommArea.initialised());
            CommArea replacement = CommArea.initialised()
                    .withChangeAction(ChangeAction.changesOkayedAndDone());
            assertThat(AccountUpdateRequest.initial().withCommArea(replacement).getCommArea())
                    .isEqualTo(replacement);
        }

        @Test
        @DisplayName("renders spaces and LOW-VALUES at any width and rejects a negative one")
        void rendersFigurativeConstants() {
            assertThat(AccountUpdateRequest.spaces(0)).isEmpty();
            assertThat(AccountUpdateRequest.spaces(3)).isEqualTo("   ");
            assertThat(AccountUpdateRequest.lowValues(0)).isEmpty();
            assertThat(AccountUpdateRequest.lowValues(2)).isEqualTo("\u0000\u0000");
            assertThatIllegalArgumentException().isThrownBy(() -> AccountUpdateRequest.spaces(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> AccountUpdateRequest.lowValues(-1));
        }

        @Test
        @DisplayName("equals is reflexive, type-checked and sensitive to every carrier")
        void comparesByValue() {
            AccountUpdateRequest request = AccountUpdateRequest.initial();
            assertThat(request).isEqualTo(request)
                    .isNotEqualTo(null)
                    .isNotEqualTo("not a request");
            assertThat(request.withMetadata(ScreenField.ERRMSG, FieldMetadata.cursorHere()))
                    .isNotEqualTo(request);
            assertThat(request.withNavigationContext(NavigationContext.empty())).isNotEqualTo(request);
            CardScreenState other = new CardScreenState();
            other.setCcardErrorMsg("X");
            assertThat(request.withCardScreenState(other)).isNotEqualTo(request);
            assertThat(request.withCommArea(
                    CommArea.initialised().withChangeAction(ChangeAction.showDetails())))
                    .isNotEqualTo(request);
        }

        @Test
        @DisplayName("discloses none of the sensitive values, and still names every field")
        void disclosesNoSensitiveValue() {
            // The payload keeps every value - the builder and the accessors are the parity surface. This
            // rendering is Java-only, so a social security number, a date of birth and a passport number
            // in it are CWE-532 exposure with no parity benefit.
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .actssn1("123").actssn2("45").actssn3("6789")
                    .dobyear("1980").dobmon("02").dobday("29")
                    .acsgovt("PASSPORT-9911")
                    .build();

            String rendered = request.toString();

            assertThat(rendered).startsWith("AccountUpdateRequest[")
                    .contains("ACTSSN1='", "ACTSSN2='", "ACTSSN3='",
                            "DOBYEAR='", "DOBMON='", "DOBDAY='",
                            "ACSGOVT='", "commArea=", "cardScreenState=", "navigationContext=")
                    .doesNotContain("PASSPORT-9911")
                    .doesNotContain("6789");

            // Unchanged: the values are still there to be read.
            assertThat(request.getAcsgovt()).startsWith("PASSPORT-9911");
            assertThat(request.getActssn3()).startsWith("6789");
        }

        @Test
        @DisplayName("no field value can forge a second log line")
        void noFieldValueCanForgeALogLine() {
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .trnname("CAUP\r\nINJECTED")
                    .build();

            assertThat(request.toString()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("brings every work-area item to its declared width, padding a short value")
        void bringsEveryWorkAreaItemToItsDeclaredWidth() {
            AcctSnapshot account = new AcctSnapshot("11", "Y", "1", "2", "3", "2022", "2027", "2024",
                    "4", "5", "DEFAULT");
            assertThat(account.acctIdX()).isEqualTo("11         ");
            assertThat(account.groupId()).isEqualTo("DEFAULT   ");
            assertThat(account.openDate()).isEqualTo("2022    ");
            assertThat(account.currBal()).hasSize(AcctSnapshot.MONEY_LENGTH);
            CustSnapshot customer = new CustSnapshot("42", "A", "B", "C", "D", "E", "F", "G", "H", "I",
                    "J", "K", "L", "M", "N", "O", "P", "Q");
            assertThat(customer.firstName()).isEqualTo("A" + " ".repeat(24));
            assertThat(customer.addrStateCd()).isEqualTo("G ");
            assertThat(customer.ficoScoreX()).isEqualTo("Q  ");
            assertThat(customer.govtIssuedId()).hasSize(CustSnapshot.GOVT_ISSUED_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("The xxxL, xxxF and xxxA metadata")
    class Metadata {

        @Test
        @DisplayName("starts every field unset and enforces the COMP PIC S9(4) range")
        void enforcesThePictureRange() {
            assertThat(FieldMetadata.unset().lengthItem()).isZero();
            assertThat(FieldMetadata.unset().attribute()).isEqualTo((byte) 0x00);
            assertThat(FieldMetadata.unset().isAttributeUnset()).isTrue();
            assertThat(FieldMetadata.unset().isEntered()).isFalse();
            assertThat(new FieldMetadata(9999, (byte) 0).lengthItem()).isEqualTo(9999);
            assertThat(new FieldMetadata(-9999, (byte) 0).lengthItem()).isEqualTo(-9999);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FieldMetadata(10_000, (byte) 0))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FieldMetadata(-10_000, (byte) 0));
        }

        @Test
        @DisplayName("treats -1 as 'cursor here' and any positive length as 'entered'")
        void treatsMinusOneAsTheCursor() {
            assertThat(FieldMetadata.cursorHere().isCursorHere()).isTrue();
            assertThat(FieldMetadata.cursorHere().isEntered()).isFalse();
            assertThat(FieldMetadata.unset().withCursorHere().lengthItem()).isEqualTo(-1);
            assertThat(FieldMetadata.unset().withLengthItem(11).isEntered()).isTrue();
            assertThat(FieldMetadata.unset().withLengthItem(11).isCursorHere()).isFalse();
        }

        @Test
        @DisplayName("holds one byte under two names, xxxF and xxxA, as a REDEFINES does")
        void holdsOneByteUnderTwoNames() {
            FieldMetadata pair = FieldMetadata.withAttributeOnly(BmsAttributes.DFHBMPRF);
            assertThat(pair.attribute()).isEqualTo(pair.flag()).isEqualTo(BmsAttributes.DFHBMPRF);
            assertThat(pair.isProtectedField()).isTrue();
            assertThat(pair.isBright()).isFalse();
            assertThat(pair.isAttributeUnset()).isFalse();
            FieldMetadata unprotected = pair.withAttribute(BmsAttributes.DFHBMFSE);
            assertThat(unprotected.isProtectedField()).isFalse();
            assertThat(pair.isProtectedField()).isTrue();
        }

        @Test
        @DisplayName("reports FKEY05 and FKEY12 revealed only once DFHBMASB is moved into them")
        void revealsTheFunctionKeyLegends() {
            AccountUpdateRequest hidden = AccountUpdateRequest.initial();
            assertThat(hidden.isSaveLegendRevealed()).isFalse();
            assertThat(hidden.isCancelLegendRevealed()).isFalse();
            AccountUpdateRequest revealed = hidden
                    .withAttribute(ScreenField.FKEY05, BmsAttributes.DFHBMASB)
                    .withAttribute(ScreenField.FKEY12, BmsAttributes.DFHBMASB);
            assertThat(revealed.isSaveLegendRevealed()).isTrue();
            assertThat(revealed.isCancelLegendRevealed()).isTrue();
            assertThat(revealed.metadata(ScreenField.FKEY05).isBright()).isTrue();
        }

        @Test
        @DisplayName("hands out an unmodifiable map with an entry for every field")
        void handsOutAnUnmodifiableMap() {
            Map<ScreenField, FieldMetadata> metadata = AccountUpdateRequest.initial().metadata();
            assertThat(metadata).hasSize(54);
            assertThat(metadata.keySet()).containsExactly(ScreenField.values());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> metadata.put(ScreenField.ACCTSID, FieldMetadata.unset()));
            assertThatNoException().isThrownBy(() -> metadata.get(ScreenField.ACCTSID));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.initial().metadata(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.initial()
                            .withMetadata(ScreenField.ACCTSID, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.initial()
                            .withMetadata(null, FieldMetadata.unset()));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.initial().withCursorOn(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.initial()
                            .withAttribute(null, BmsAttributes.DFHBMPRF));
        }

        @Test
        @DisplayName("restores a field to its unset pair when the builder is handed a null")
        void clearsAPairWhenGivenNull() {
            AccountUpdateRequest request = AccountUpdateRequest.initial()
                    .withCursorOn(ScreenField.ACCTSID)
                    .toBuilder()
                    .metadata(ScreenField.ACCTSID, null)
                    .build();
            assertThat(request.metadata(ScreenField.ACCTSID)).isEqualTo(FieldMetadata.unset());
        }

        @Test
        @DisplayName("names the attribute byte by mnemonic in its rendering")
        void namesTheAttributeByMnemonic() {
            assertThat(FieldMetadata.cursorHere().toString()).contains("cursor here");
            assertThat(FieldMetadata.withAttributeOnly(BmsAttributes.DFHBMPRF).toString())
                    .contains(BmsAttributes.toHex(BmsAttributes.DFHBMPRF));
        }
    }

    @Nested
    @DisplayName("ACUP-CHANGE-ACTION and its nine 88-level conditions")
    class ChangeActionConditions {

        @Test
        @DisplayName("declares LOW-VALUES as its initial value, per VALUE LOW-VALUES")
        void startsAtLowValues() {
            assertThat(ChangeAction.initial().value()).isEqualTo("\u0000");
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(ChangeAction.FIELD_NAME).isEqualTo("ACUP-CHANGE-ACTION");
            assertThat(ChangeAction.GROUP_NAME).isEqualTo("ACCT-UPDATE-SCREEN-DATA");
        }

        @Test
        @DisplayName("covers LOW-VALUES and SPACES with ACUP-DETAILS-NOT-FETCHED and nothing else")
        void detailsNotFetchedCoversTwoBytes() {
            assertThat(ChangeAction.DETAILS_NOT_FETCHED_VALUES).containsExactly("\u0000", " ");
            assertThat(ChangeAction.initial().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.spacesState().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.spacesState()).isNotEqualTo(ChangeAction.initial());
            assertThat(ChangeAction.showDetails().isDetailsNotFetched()).isFalse();
        }

        @Test
        @DisplayName("covers FIVE values with ACUP-CHANGES-MADE, as COACTUPC.cbl:660-662 declares")
        void changesMadeCoversFiveValues() {
            assertThat(ChangeAction.CHANGES_MADE_VALUES)
                    .containsExactly("E", "N", "C", "L", "F");
            assertThat(ChangeAction.CHANGES_FAILED_VALUES).containsExactly("L", "F");
            for (String value : ChangeAction.CHANGES_MADE_VALUES) {
                assertThat(ChangeAction.of(value).isChangesMade()).isTrue();
            }
            assertThat(ChangeAction.initial().isChangesMade()).isFalse();
            assertThat(ChangeAction.showDetails().isChangesMade()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\u0000", " ", "S", "E", "N", "C", "L", "F", "?"})
        @DisplayName("drives all nine conditions to true and to false for every reachable byte")
        void drivesEveryConditionBothWays(String value) {
            ChangeAction action = ChangeAction.of(value);
            boolean detailsNotFetched = "\u0000".equals(value) || " ".equals(value);
            assertThat(action.isDetailsNotFetched()).isEqualTo(detailsNotFetched);
            assertThat(action.isShowDetails()).isEqualTo("S".equals(value));
            assertThat(action.isChangesMade())
                    .isEqualTo(List.of("E", "N", "C", "L", "F").contains(value));
            assertThat(action.isChangesNotOk()).isEqualTo("E".equals(value));
            assertThat(action.isChangesOkNotConfirmed()).isEqualTo("N".equals(value));
            assertThat(action.isChangesOkayedAndDone()).isEqualTo("C".equals(value));
            assertThat(action.isChangesFailed()).isEqualTo(List.of("L", "F").contains(value));
            assertThat(action.isChangesOkayedLockError()).isEqualTo("L".equals(value));
            assertThat(action.isChangesOkayedButFailed()).isEqualTo("F".equals(value));
            assertThat(action.isUnrecognised()).isEqualTo("?".equals(value));
            assertThat(action.toString()).contains("ACUP-CHANGE-ACTION=");
        }

        @Test
        @DisplayName("names a factory for each of the eight named states")
        void namesAFactoryForEachState() {
            assertThat(ChangeAction.showDetails().isShowDetails()).isTrue();
            assertThat(ChangeAction.changesNotOk().isChangesNotOk()).isTrue();
            assertThat(ChangeAction.changesOkNotConfirmed().isChangesOkNotConfirmed()).isTrue();
            assertThat(ChangeAction.changesOkayedAndDone().isChangesOkayedAndDone()).isTrue();
            assertThat(ChangeAction.changesOkayedLockError().isChangesOkayedLockError()).isTrue();
            assertThat(ChangeAction.changesOkayedButFailed().isChangesOkayedButFailed()).isTrue();
            assertThat(ChangeAction.of('S')).isEqualTo(ChangeAction.showDetails());
        }

        @Test
        @DisplayName("rejects a null byte and any width but one")
        void rejectsAWrongWidth() {
            assertThatNullPointerException().isThrownBy(() -> new ChangeAction(null));
            assertThatIllegalArgumentException().isThrownBy(() -> new ChangeAction(""))
                    .withMessageContaining("PIC X(1)");
            assertThatIllegalArgumentException().isThrownBy(() -> new ChangeAction("SS"));
        }
    }

    @Nested
    @DisplayName("The 873-byte WS-THIS-PROGCOMMAREA")
    class WorkArea {

        @Test
        @DisplayName("sums to 1 + 436 + 436 = 873, with 106 of account and 330 of customer per group")
        void sumsToEightHundredAndSeventyThree() {
            assertThat(AcctSnapshot.RECORD_LENGTH).isEqualTo(106);
            assertThat(CustSnapshot.RECORD_LENGTH).isEqualTo(330);
            assertThat(Details.RECORD_LENGTH).isEqualTo(436);
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(873);
            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(437);
            assertThat(Details.ACCT_DATA_OFFSET).isZero();
            assertThat(Details.CUST_DATA_OFFSET).isEqualTo(106);
        }

        @Test
        @DisplayName("declares a layout of exactly 873 bytes of storage, every byte accounted for")
        void declaresEveryByte() {
            FixedWidthRecord.RecordLayout layout = CommArea.LAYOUT;
            assertThat(layout.recordLength()).isEqualTo(873);
            int storage = 0;
            for (FixedWidthRecord.FieldSpan span : layout.storageSpans()) {
                storage += span.length();
            }
            assertThat(storage).isEqualTo(873);
            assertThat(layout.redefinitions()).isNotEmpty();
            assertThat(layout.hasSpan("ACUP-CHANGE-ACTION")).isTrue();
            assertThat(layout.hasSpan("ACCT-UPDATE-SCREEN-DATA")).isTrue();
            assertThat(layout.hasSpan("ACUP-OLD-DETAILS")).isTrue();
            assertThat(layout.hasSpan("ACUP-NEW-DETAILS")).isTrue();
            assertThat(layout.hasSpan("ACUP-OLD-ACCT-DATA")).isTrue();
            assertThat(layout.hasSpan("ACUP-NEW-CUST-DATA")).isTrue();
        }

        @Test
        @DisplayName("preserves the EXPIRAION misspelling on both groups")
        void preservesTheMisspelling() {
            for (DetailGroup group : DetailGroup.values()) {
                assertThat(group.layout().hasSpan(group.prefix() + "EXPIRAION-DATE")).isTrue();
                assertThat(group.layout().hasSpan(group.prefix() + "EXPIRAION-DATE-PARTS")).isTrue();
                assertThat(group.layout().hasSpan(group.prefix() + "EXPIRATION-DATE")).isFalse();
                assertThat(group.qualify("EXPIRAION-DATE"))
                        .isEqualTo(group.prefix() + "EXPIRAION-DATE");
            }
            assertThatNullPointerException().isThrownBy(() -> DetailGroup.OLD.qualify(null));
        }

        @Test
        @DisplayName("splits the NEW group's SSN into three named sub-items and leaves OLD's flat")
        void keepsTheSsnAsymmetry() {
            assertThat(DetailGroup.NEW.declaresSsnParts()).isTrue();
            assertThat(DetailGroup.OLD.declaresSsnParts()).isFalse();
            assertThat(DetailGroup.NEW.custLayout().hasSpan("ACUP-NEW-CUST-SSN-1")).isTrue();
            assertThat(DetailGroup.NEW.custLayout().hasSpan("ACUP-NEW-CUST-SSN-2")).isTrue();
            assertThat(DetailGroup.NEW.custLayout().hasSpan("ACUP-NEW-CUST-SSN-3")).isTrue();
            assertThat(DetailGroup.NEW.custLayout().hasSpan("ACUP-NEW-CUST-SSN-X")).isTrue();
            assertThat(DetailGroup.NEW.custLayout().hasSpan("ACUP-NEW-CUST-SSN")).isTrue();
            assertThat(DetailGroup.OLD.custLayout().hasSpan("ACUP-OLD-CUST-SSN-1")).isFalse();
            assertThat(DetailGroup.OLD.custLayout().hasSpan("ACUP-OLD-CUST-SSN-X")).isTrue();
            assertThat(DetailGroup.OLD.custLayout().hasSpan("ACUP-OLD-CUST-SSN")).isTrue();
            // Both forms are nine bytes, which is why the split has to be asserted by name.
            assertThat(DetailGroup.OLD.custLayout().span("ACUP-OLD-CUST-SSN-X").length())
                    .isEqualTo(DetailGroup.NEW.custLayout().span("ACUP-NEW-CUST-SSN-X").length())
                    .isEqualTo(9);
        }

        @Test
        @DisplayName("declares the FICO range condition on NEW only")
        void keepsTheFicoAsymmetry() {
            assertThat(DetailGroup.NEW.declaresFicoRangeCondition()).isTrue();
            assertThat(DetailGroup.OLD.declaresFicoRangeCondition()).isFalse();
            assertThat(CustSnapshot.FICO_RANGE_MINIMUM).isEqualTo(300);
            assertThat(CustSnapshot.FICO_RANGE_MAXIMUM).isEqualTo(850);
        }

        @Test
        @DisplayName("declares every telephone FILLER span, so the parts land at 250, 254 and 258")
        void declaresEveryPhoneFiller() {
            FixedWidthRecord.RecordLayout layout = DetailGroup.OLD.custLayout();
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-1").offset()).isEqualTo(249);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-1A").offset()).isEqualTo(250);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-1B").offset()).isEqualTo(254);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-1C").offset()).isEqualTo(258);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-2A").offset()).isEqualTo(265);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-2B").offset()).isEqualTo(269);
            assertThat(layout.span("ACUP-OLD-CUST-PHONE-NUM-2C").offset()).isEqualTo(273);
            long fillers = layout.redefinitions().stream()
                    .filter(span -> "FILLER".equals(span.name()))
                    .count();
            assertThat(fillers).isEqualTo(8);
            assertThat(CustSnapshot.PHONE_LEADING_FILLER_LENGTH
                    + CustSnapshot.PHONE_AREA_CODE_LENGTH
                    + CustSnapshot.PHONE_INNER_FILLER_LENGTH
                    + CustSnapshot.PHONE_PREFIX_LENGTH
                    + CustSnapshot.PHONE_INNER_FILLER_LENGTH
                    + CustSnapshot.PHONE_LINE_NUMBER_LENGTH
                    + CustSnapshot.PHONE_TRAILING_FILLER_LENGTH)
                    .isEqualTo(CustSnapshot.PHONE_NUM_LENGTH);
        }

        @Test
        @DisplayName("refuses a snapshot stored under the wrong group's names")
        void refusesASnapshotInTheWrongPosition() {
            Details old = Details.initialised(DetailGroup.OLD);
            Details fresh = Details.initialised(DetailGroup.NEW);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CommArea(ChangeAction.initial(), fresh, fresh))
                    .withMessageContaining("ACUP-OLD-DETAILS");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CommArea(ChangeAction.initial(), old, old))
                    .withMessageContaining("ACUP-NEW-DETAILS");
            assertThatNullPointerException()
                    .isThrownBy(() -> new CommArea(null, old, fresh));
            assertThat(new CommArea(ChangeAction.initial(), null, null))
                    .isEqualTo(CommArea.initialised());
            assertThat(old.asGroup(DetailGroup.OLD)).isSameAs(old);
            assertThat(old.asGroup(DetailGroup.NEW).group()).isEqualTo(DetailGroup.NEW);
            assertThat(old.groupName()).isEqualTo("ACUP-OLD-DETAILS");
            assertThat(fresh.groupName()).isEqualTo("ACUP-NEW-DETAILS");
            assertThatNullPointerException().isThrownBy(() -> old.asGroup(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new Details(null, null, null));
            // An omitted half becomes an initialised one, so INITIALIZE ACUP-NEW-DETAILS is expressible.
            assertThat(new Details(DetailGroup.OLD, null, null))
                    .isEqualTo(Details.initialised(DetailGroup.OLD));
        }

        @Test
        @DisplayName("replaces one component at a time and leaves the others alone")
        void replacesOneComponentAtATime() {
            CommArea area = CommArea.initialised();
            assertThat(area.withChangeAction(ChangeAction.showDetails()).changeAction().isShowDetails())
                    .isTrue();
            assertThatNullPointerException().isThrownBy(() -> area.withChangeAction(null));
            Details replacement = new Details(DetailGroup.OLD,
                    new AcctSnapshot("00000000011", "Y", null, null, null, "20220718", "20270718",
                            "20240102", null, null, "GROUP01"),
                    CustSnapshot.initialised());
            assertThat(area.withOldDetails(replacement).oldDetails()).isEqualTo(replacement);
            assertThat(area.withOldDetails(replacement).newDetails()).isEqualTo(area.newDetails());
            Details fresh = replacement.asGroup(DetailGroup.NEW);
            assertThat(area.withNewDetails(fresh).newDetails()).isEqualTo(fresh);
        }
    }

    @Nested
    @DisplayName("Every REDEFINES is two typed accessors over one span")
    class Redefines {

        /** A snapshot whose numeric spans hold real digits, so the overlays have something to read. */
        private AcctSnapshot populatedAccount() {
            return new AcctSnapshot("00000000011", "Y",
                    "000000012345", "000000100000", "000000050000",
                    "20220718", "20270718", "20240102",
                    "000000001000", "000000002000", "GROUP01");
        }

        /** A customer snapshot with digits in every numeric span. */
        private CustSnapshot populatedCustomer() {
            return new CustSnapshot("000000011", "FIRST", "MIDDLE", "LAST",
                    "LINE ONE", "LINE TWO", "NEW YORK", "NY", "USA", "10001-0000",
                    " 212 555 0100  ", " 718 555 0200  ", "123456789", "PASSPORT-9911",
                    "19800229", "EFT0000001", "Y", "750");
        }

        @Test
        @DisplayName("reads the account identifier as characters and as PIC 9(11) over one span")
        void readsTheAccountIdentifierBothWays() {
            AcctSnapshot snapshot = populatedAccount();
            assertThat(snapshot.acctIdX()).isEqualTo("00000000011");
            assertThat(snapshot.acctId()).isEqualTo(11L);
            assertThat(AcctSnapshot.initialised().acctId()).isZero();
            assertThat(new AcctSnapshot("\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
                    + "\u0000", null, null, null, null, null, null, null, null, null, null).acctId())
                    .isZero();
        }

        @Test
        @DisplayName("reads the five money spans as PIC S9(10)V99 at scale 2, truncating never rounding")
        void readsTheMoneySpansAtScaleTwo() {
            AcctSnapshot snapshot = populatedAccount();
            assertThat(snapshot.currBalN()).isEqualByComparingTo("123.45");
            assertThat(snapshot.currBalN().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(snapshot.creditLimitN()).isEqualByComparingTo("1000.00");
            assertThat(snapshot.cashCreditLimitN()).isEqualByComparingTo("500.00");
            assertThat(snapshot.currCycCreditN()).isEqualByComparingTo("10.00");
            assertThat(snapshot.currCycDebitN()).isEqualByComparingTo("20.00");
            assertThat(AcctSnapshot.initialised().currBalN())
                    .isEqualByComparingTo(BigDecimal.ZERO)
                    .satisfies(zero -> assertThat(zero.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("reads a negative money span from the trailing-byte sign overpunch")
        void readsANegativeMoneySpan() {
            // 12 bytes: eleven digits and a trailing 'J', the zone-D overpunch for a negative 1.
            AcctSnapshot snapshot = new AcctSnapshot(null, null, "00000001234J", null, null, null, null,
                    null, null, null, null);
            assertThat(snapshot.currBalN()).isEqualByComparingTo("-123.41");
        }

        @Test
        @DisplayName("reads the three account dates as year, month and day over the same eight bytes")
        void readsTheAccountDatePartsOverOneSpan() {
            AcctSnapshot snapshot = populatedAccount();
            assertThat(snapshot.openDate()).isEqualTo("20220718").hasSize(AcctSnapshot.DATE_LENGTH);
            assertThat(snapshot.openYear()).isEqualTo("2022");
            assertThat(snapshot.openMon()).isEqualTo("07");
            assertThat(snapshot.openDay()).isEqualTo("18");
            assertThat(snapshot.expiraionDate()).isEqualTo("20270718");
            assertThat(snapshot.expYear()).isEqualTo("2027");
            assertThat(snapshot.expMon()).isEqualTo("07");
            assertThat(snapshot.expDay()).isEqualTo("18");
            assertThat(snapshot.reissueDate()).isEqualTo("20240102");
            assertThat(snapshot.reissueYear()).isEqualTo("2024");
            assertThat(snapshot.reissueMon()).isEqualTo("01");
            assertThat(snapshot.reissueDay()).isEqualTo("02");
            assertThat(snapshot.openYear() + snapshot.openMon() + snapshot.openDay())
                    .isEqualTo(snapshot.openDate());
        }

        @Test
        @DisplayName("reads the customer identifier, SSN and FICO score over their own spans")
        void readsTheCustomerNumericOverlays() {
            CustSnapshot snapshot = populatedCustomer();
            assertThat(snapshot.custIdX()).isEqualTo("000000011");
            assertThat(snapshot.custId()).isEqualTo(11L);
            assertThat(snapshot.ssnX()).isEqualTo("123456789");
            assertThat(snapshot.ssn()).isEqualTo(123456789L);
            assertThat(snapshot.ssn1()).isEqualTo("123");
            assertThat(snapshot.ssn2()).isEqualTo("45");
            assertThat(snapshot.ssn3()).isEqualTo("6789");
            assertThat(snapshot.ssn1() + snapshot.ssn2() + snapshot.ssn3()).isEqualTo(snapshot.ssnX());
            assertThat(snapshot.ficoScoreX()).isEqualTo("750");
            assertThat(snapshot.ficoScore()).isEqualTo(750);
            assertThat(snapshot.ficoRangeIsValid()).isTrue();
            assertThat(CustSnapshot.initialised().ficoScore()).isZero();
            assertThat(CustSnapshot.initialised().ficoRangeIsValid()).isFalse();
            assertThat(CustSnapshot.initialised().custId()).isZero();
            assertThat(CustSnapshot.initialised().ssn()).isZero();
        }

        @ParameterizedTest
        @ValueSource(ints = {299, 300, 500, 850, 851})
        @DisplayName("drives 88 FICO-RANGE-IS-VALID to both sides of both bounds")
        void drivesTheFicoRangeBothWays(int score) {
            CustSnapshot snapshot = new CustSnapshot(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, String.format("%03d", score));
            assertThat(snapshot.ficoScore()).isEqualTo(score);
            assertThat(snapshot.ficoRangeIsValid()).isEqualTo(score >= 300 && score <= 850);
        }

        @Test
        @DisplayName("reads the telephone parts past their FILLER punctuation")
        void readsTheTelephoneParts() {
            CustSnapshot snapshot = populatedCustomer();
            assertThat(snapshot.phoneNum1()).hasSize(CustSnapshot.PHONE_NUM_LENGTH);
            assertThat(snapshot.phoneNum1A()).isEqualTo("212");
            assertThat(snapshot.phoneNum1B()).isEqualTo("555");
            assertThat(snapshot.phoneNum1C()).isEqualTo("0100");
            assertThat(snapshot.phoneNum2A()).isEqualTo("718");
            assertThat(snapshot.phoneNum2B()).isEqualTo("555");
            assertThat(snapshot.phoneNum2C()).isEqualTo("0200");
        }

        @Test
        @DisplayName("reads the date of birth in the parts 9700 compares at 1:4, 5:2 and 7:2")
        void readsTheDateOfBirthParts() {
            CustSnapshot snapshot = populatedCustomer();
            assertThat(snapshot.dobYyyyMmDd()).isEqualTo("19800229").hasSize(CustSnapshot.DOB_LENGTH);
            assertThat(snapshot.dobYear()).isEqualTo("1980");
            assertThat(snapshot.dobMon()).isEqualTo("02");
            assertThat(snapshot.dobDay()).isEqualTo("29");
        }

        @Test
        @DisplayName("rejects an over-wide item by name rather than truncating it")
        void rejectsAnOverWideItem() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AcctSnapshot("000000000000", null, null, null, null, null,
                            null, null, null, null, null))
                    .withMessageContaining("ACCT-ID-X");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CustSnapshot(null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, "1980-02-29", null, null, null))
                    .withMessageContaining("CUST-DOB-YYYY-MM-DD");
        }

        @Test
        @DisplayName("fills an omitted item with its declared width in spaces")
        void fillsAnOmittedItemWithSpaces() {
            AcctSnapshot account = AcctSnapshot.initialised();
            assertThat(account.acctIdX()).hasSize(11).isBlank();
            assertThat(account.groupId()).hasSize(10).isBlank();
            assertThat(account.activeStatus()).hasSize(1).isBlank();
            CustSnapshot customer = CustSnapshot.initialised();
            assertThat(customer.addrZip()).hasSize(10).isBlank();
            assertThat(customer.priHolderInd()).hasSize(1).isBlank();
            assertThat(customer.govtIssuedId()).hasSize(20).isBlank();
        }
    }

    @Nested
    @DisplayName("Byte-for-byte round trips")
    class RoundTrips {

        @Test
        @DisplayName("renders and reads back the 873-byte work area under either code page")
        void roundTripsTheWorkArea() {
            CommArea area = new CommArea(ChangeAction.changesOkNotConfirmed(),
                    new Details(DetailGroup.OLD,
                            new AcctSnapshot("00000000011", "Y", "000000012345", "000000100000",
                                    "000000050000", "20220718", "20270718", "20240102",
                                    "000000001000", "000000002000", "GROUP01"),
                            new CustSnapshot("000000011", "FIRST", "MIDDLE", "LAST", "LINE ONE",
                                    "LINE TWO", "NEW YORK", "NY", "USA", "10001-0000",
                                    " 212 555 0100  ", " 718 555 0200  ", "123456789", "PASSPORT",
                                    "19800229", "EFT0000001", "Y", "750")),
                    Details.initialised(DetailGroup.NEW));
            for (FixedWidthCodec codec : List.of(ASCII_CODEC, EBCDIC_CODEC)) {
                byte[] image = area.encode(codec);
                assertThat(image).hasSize(873);
                assertThat(CommArea.decode(image, codec)).isEqualTo(area);
            }
            assertThat(area.encode(ASCII)).hasSize(873);
            assertThat(CommArea.decode(area.encode(EBCDIC), EBCDIC)).isEqualTo(area);
        }

        @Test
        @DisplayName("renders and reads back a 436-byte detail group on its own")
        void roundTripsOneDetailGroup() {
            Details details = new Details(DetailGroup.NEW,
                    new AcctSnapshot("00000000042", "N", "000000000001", "000000000002",
                            "000000000003", "19990101", "20010203", "20050607", "000000000004",
                            "000000000005", "DEFAULT"),
                    new CustSnapshot("000000042", "A", "B", "C", "D", "E", "F", "GH", "IJK",
                            "0123456789", " 987 654 3210  ", " 111 222 3333  ", "987654321", "ID",
                            "20000101", "EFT9999999", "N", "301"));
            byte[] image = details.encode(ASCII_CODEC);
            assertThat(image).hasSize(436);
            assertThat(Details.decode(image, DetailGroup.NEW, ASCII_CODEC)).isEqualTo(details);
            assertThat(Details.decode(details.encode(EBCDIC), DetailGroup.NEW, EBCDIC))
                    .isEqualTo(details);
            assertThat(details.toFixedWidthRecord(ASCII_CODEC).recordLength()).isEqualTo(436);
        }

        @Test
        @DisplayName("reads any span of a rendered group by its COBOL name, overlays included")
        void readsAnySpanByName() {
            Details details = new Details(DetailGroup.OLD,
                    new AcctSnapshot("00000000011", "Y", null, null, null, "20220718", null, null,
                            null, null, "GROUP01"),
                    CustSnapshot.initialised());
            FixedWidthRecord area = details.toFixedWidthRecord(ASCII_CODEC);
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-ACCT-ID-X")))
                    .isEqualTo("00000000011");
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-ACCT-ID")))
                    .isEqualTo("00000000011");
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-OPEN-YEAR")))
                    .isEqualTo("2022");
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-OPEN-MON")))
                    .isEqualTo("07");
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-OPEN-DAY")))
                    .isEqualTo("18");
            assertThat(area.readSpan(DetailGroup.OLD.layout().span("ACUP-OLD-GROUP-ID")))
                    .isEqualTo("GROUP01   ");
            assertThat(DetailGroup.OLD.acctLayout().recordLength()).isEqualTo(106);
            assertThat(DetailGroup.OLD.custLayout().recordLength()).isEqualTo(330);
        }

        @Test
        @DisplayName("rejects an image of the wrong width, and a null image or codec")
        void rejectsAWrongWidthImage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CommArea.decode(new byte[872], ASCII_CODEC))
                    .withMessageContaining("873");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Details.decode(new byte[435], DetailGroup.OLD, ASCII_CODEC))
                    .withMessageContaining("436");
            assertThatNullPointerException().isThrownBy(() -> CommArea.decode(null, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() -> CommArea.decode(new byte[873],
                    (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Details.decode(null, DetailGroup.OLD, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> Details.decode(new byte[436], null, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> Details.decode(new byte[436], DetailGroup.OLD,
                            (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CommArea.initialised().toFixedWidthRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Details.initialised(DetailGroup.OLD).toFixedWidthRecord(null));
        }

        @Test
        @DisplayName("renders the 1095-byte CACTUPAI image and reads it back exactly")
        void roundTripsTheGroupImage() {
            AccountUpdateRequest request = AccountUpdateRequest.withAccountFilter("00000000011")
                    .withValue(ScreenField.ACSTTUS, "Y")
                    .withValue(ScreenField.FKEYS, "ENTER=Process F3=Exit")
                    .withAttribute(ScreenField.FKEY05, BmsAttributes.DFHBMASB)
                    .normalize(ASCII_CODEC);
            byte[] image = request.toGroupImage(ASCII_CODEC);
            assertThat(image).hasSize(1095);
            AccountUpdateRequest recovered = AccountUpdateRequest.fromGroupImage(image, ASCII_CODEC);
            assertThat(recovered).isEqualTo(request);
            assertThat(recovered.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(recovered.isSaveLegendRevealed()).isTrue();
            // The TIOAPFX prefix is spaces and the extended-attribute FILLER spans are LOW-VALUES.
            for (int index = 0; index < AccountUpdateRequest.TIOAPFX_LENGTH; index++) {
                assertThat(image[index]).isEqualTo((byte) ' ');
            }
            int filler = ScreenField.TRNNAME.extendedAttributeItemOffset();
            for (int index = filler; index < filler + 4; index++) {
                assertThat(image[index]).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("round trips the group image under IBM037 as well as US-ASCII")
        void roundTripsTheGroupImageInEbcdic() {
            AccountUpdateRequest request = AccountUpdateRequest.withAccountFilter("*")
                    .normalize(EBCDIC_CODEC);
            byte[] image = request.toGroupImage(EBCDIC_CODEC);
            assertThat(image).hasSize(1095);
            assertThat(image[0]).isEqualTo((byte) 0x40);
            assertThat(AccountUpdateRequest.fromGroupImage(image, EBCDIC_CODEC)).isEqualTo(request);
        }

        @Test
        @DisplayName("moves each field to its declared width, padding right and truncating right")
        void appliesThePicXMoveRule() {
            AccountUpdateRequest request = AccountUpdateRequest.initial()
                    .withValue(ScreenField.ACSTTUS, "YES")
                    .withValue(ScreenField.AADDGRP, "AB");
            assertThat(request.image(ScreenField.ACSTTUS, ASCII_CODEC)).isEqualTo("Y");
            assertThat(request.image(ScreenField.AADDGRP, ASCII_CODEC)).isEqualTo("AB        ");
            AccountUpdateRequest normalised = request.normalize(ASCII_CODEC);
            assertThat(normalised.getAcsttus()).isEqualTo("Y");
            assertThat(normalised.getAaddgrp()).isEqualTo("AB        ");
            assertThatNullPointerException()
                    .isThrownBy(() -> request.image(ScreenField.ACSTTUS, null));
            assertThatNullPointerException().isThrownBy(() -> request.image(null, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() -> request.normalize(null));
            assertThatNullPointerException().isThrownBy(() -> request.toGroupImage(null));
        }

        @Test
        @DisplayName("rejects a group image of the wrong length or an unrepresentable length item")
        void rejectsABadGroupImage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountUpdateRequest.fromGroupImage(new byte[1094], ASCII_CODEC))
                    .withMessageContaining("1095");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.fromGroupImage(null, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateRequest.fromGroupImage(new byte[1095], null));
            byte[] tooHigh = AccountUpdateRequest.initial().toGroupImage(ASCII_CODEC);
            int offset = ScreenField.TRNNAME.lengthItemOffset();
            tooHigh[offset] = (byte) 0x7F;
            tooHigh[offset + 1] = (byte) 0xFF;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountUpdateRequest.fromGroupImage(tooHigh, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4)");
            // The other end of the PICTURE range: a halfword holds -32768 but S9(4) stops at -9999.
            byte[] tooLow = AccountUpdateRequest.initial().toGroupImage(ASCII_CODEC);
            int lastOffset = ScreenField.FKEY12.lengthItemOffset();
            tooLow[lastOffset] = (byte) 0x80;
            tooLow[lastOffset + 1] = (byte) 0x00;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountUpdateRequest.fromGroupImage(tooLow, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4)");
        }

        @Test
        @DisplayName("refuses to render the group under a charset that is not one byte per character")
        void refusesAMultiByteCharset() {
            // UTF-8 passes FixedWidthCodec's repertoire check - every digit, overpunch character and the
            // space is one byte in it - and then encodes a non-ASCII character to two, which is exactly
            // the case that would silently shift every offset after the field.
            FixedWidthCodec utf8 = new FixedWidthCodec(StandardCharsets.UTF_8);
            AccountUpdateRequest request = AccountUpdateRequest.initial()
                    .withValue(ScreenField.ACSFNAM,
                            "\u00e9" + AccountUpdateRequest.spaces(
                                    AccountUpdateRequest.ACSFNAM_LENGTH - 1));
            // Which layer refuses it is not the contract; that it is refused, naming the field and the
            // code page rather than quoting the data, is. FixedWidthCodec's own transcoder happens to
            // catch it first, and toGroupImage carries a second width check behind that one.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.toGroupImage(utf8))
                    .withMessageContaining("ACSFNAM")
                    .withMessageContaining("UTF-8");
        }
    }

    @Nested
    @DisplayName("Validation, serialisation and the absence of server-side state")
    class Contract {

        @Test
        @DisplayName("reports one Size violation per over-wide field and nothing else")
        void reportsOnlySizeViolations() {
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .acctsid("000000000112")
                    .acsttus("YY")
                    .build();
            Set<ConstraintViolation<AccountUpdateRequest>> violations = validator().validate(request);
            assertThat(violations).hasSize(2);
            List<String> paths = new ArrayList<>();
            for (ConstraintViolation<AccountUpdateRequest> violation : violations) {
                paths.add(violation.getPropertyPath().toString());
                assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                        .isEqualTo(Size.class);
            }
            assertThat(paths).containsExactlyInAnyOrder("acctsid", "acsttus");
        }

        @Test
        @DisplayName("accepts '*', spaces and LOW-VALUES on every field, as COACTUPC:1051-1058 requires")
        void acceptsTheWildcardAndTheFigurativeConstants() {
            AccountUpdateRequest.Builder builder = AccountUpdateRequest.builder();
            for (ScreenField field : ScreenField.values()) {
                builder.value(field, "*");
            }
            assertThat(validator().validate(builder.build())).isEmpty();
            AccountUpdateRequest.Builder blanks = AccountUpdateRequest.builder();
            for (ScreenField field : ScreenField.values()) {
                blanks.value(field, AccountUpdateRequest.lowValues(field.length()));
            }
            assertThat(validator().validate(blanks.build())).isEmpty();
            assertThat(validator().validate(AccountUpdateRequest.initial())).isEmpty();
            // The three SSN placeholder masks are legitimate values, not validation failures.
            assertThat(validator().validate(AccountUpdateRequest.builder()
                    .actssn1("999").actssn2("99").actssn3("9999").build())).isEmpty();
        }

        @Test
        @DisplayName("binds inbound JSON through the builder and serialises the 54 fields")
        void bindsThroughTheBuilder() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .acctsid("00000000011")
                    .actssn1("123").actssn2("45").actssn3("6789")
                    .dobyear("1980").dobmon("02").dobday("29")
                    .acsgovt("PASSPORT-9911")
                    .build();
            String json = mapper.writeValueAsString(request);
            JsonNode tree = mapper.readTree(json);
            assertThat(tree.get("acctsid").asText()).isEqualTo("00000000011");
            for (ScreenField field : ScreenField.values()) {
                assertThat(tree.has(field.label().toLowerCase(Locale.ROOT)))
                        .as("payload member for %s", field.label())
                        .isTrue();
            }
            // The metadata never reaches the wire; the three carriers do.
            assertThat(tree.has("metadata")).isFalse();
            assertThat(tree.has("commArea")).isTrue();
            assertThat(tree.has("cardScreenState")).isTrue();
            assertThat(mapper.readValue(json, AccountUpdateRequest.class).getAcctsid())
                    .isEqualTo("00000000011");
            assertThat(mapper.readValue(json, AccountUpdateRequest.class).getActssn3())
                    .isEqualTo("6789");
            assertThat(json).contains("123456789".substring(0, 3), "PASSPORT-9911");
        }

        @Test
        @DisplayName("holds no session, no static mutable state and no server-side cache")
        void holdsNoServerSideState() throws Exception {
            for (Field field : AccountUpdateRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            assertThat(AccountUpdateRequest.class.getMethods())
                    .noneMatch(method -> method.getName().startsWith("set"));
            assertThat(AccountUpdateRequest.class.getDeclaredConstructors()).hasSize(1);
            assertThat(Modifier
                    .isPrivate(AccountUpdateRequest.class.getDeclaredConstructors()[0].getModifiers()))
                    .isTrue();
        }

        @Test
        @DisplayName("declares no persistence mapping of any kind")
        void declaresNoPersistenceMapping() {
            for (Annotation annotation
                    : AccountUpdateRequest.class.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .doesNotContain("persistence")
                        .doesNotContain("Entity")
                        .doesNotContain("Table");
            }
        }

        @Test
        @DisplayName("is constructible with no Spring context, which parity cases rely on")
        void isConstructibleWithoutSpring() {
            assertThatNoException().isThrownBy(() -> {
                AccountUpdateRequest.initial();
                AccountUpdateRequest.withAccountFilter("00000000011");
                AccountUpdateRequest.builder().build();
                CommArea.initialised();
                Details.initialised(DetailGroup.OLD);
                AcctSnapshot.initialised();
                CustSnapshot.initialised();
                FieldMetadata.unset();
                ChangeAction.initial();
            });
        }
    }
}
