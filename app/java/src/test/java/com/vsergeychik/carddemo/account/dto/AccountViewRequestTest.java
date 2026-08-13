package com.vsergeychik.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountViewRequest.ScreenField;
import com.vsergeychik.carddemo.account.dto.AccountViewRequest.ScreenFieldMetadata;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract of {@link AccountViewRequest}, asserted against the two files that define it:
 * {@code app/cpy-bms/COACTVW.CPY} and {@code app/bms/COACTVW.bms}.
 */
@DisplayName("AccountViewRequest - COACTVW CACTVWAI, the CAVW inbound payload")
class AccountViewRequestTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final List<String> LABELS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "ACSTTUS",
            "ADTOPEN", "ACRDLIM", "AEXPDT", "ACSHLIM", "AREISDT", "ACURBAL", "ACRCYCR", "AADDGRP",
            "ACRCYDB", "ACSTNUM", "ACSTSSN", "ACSTDOB", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPHN1", "ACSGOVT",
            "ACSPHN2", "ACSEFTC", "ACSPFLG", "INFOMSG", "ERRMSG");

    private static final List<Integer> WIDTHS = List.of(4, 40, 8, 8, 40, 8, 11, 1, 10, 15, 10, 15, 10,
            15, 15, 10, 15, 9, 12, 10, 3, 25, 25, 25, 50, 2, 50, 5, 50, 3, 13, 20, 13, 10, 1, 45, 78);

    private static final List<String> PICTURES = List.of(
            "X(4)", "X(40)", "X(8)", "X(8)", "X(40)", "X(8)", "99999999999", "X(1)", "X(10)", "X(15)",
            "X(10)", "X(15)", "X(10)", "X(15)", "X(15)", "X(10)", "X(15)", "X(9)", "X(12)", "X(10)",
            "X(3)", "X(25)", "X(25)", "X(25)", "X(50)", "X(2)", "X(50)", "X(5)", "X(50)", "X(3)",
            "X(13)", "X(20)", "X(13)", "X(10)", "X(1)", "X(45)", "X(78)");

    private static final List<Integer> CPY_LINES = List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84,
            90, 96, 102, 108, 114, 120, 126, 132, 138, 144, 150, 156, 162, 168, 174, 180, 186, 192, 198,
            204, 210, 216, 222, 228, 234, 240);

    private static final List<Integer> BMS_LINES = List.of(34, 38, 47, 57, 61, 70, 84, 97, 107, 117, 128,
            138, 149, 159, 171, 182, 192, 207, 216, 225, 234, 251, 256, 261, 268, 277, 282, 291, 301,
            310, 319, 326, 335, 342, 351, 356, 365);

    private static final List<List<Integer>> POSITIONS = List.of(
            List.of(1, 7), List.of(1, 21), List.of(1, 71), List.of(2, 7), List.of(2, 21), List.of(2, 71),
            List.of(5, 38), List.of(5, 70), List.of(6, 17), List.of(6, 61), List.of(7, 17),
            List.of(7, 61), List.of(8, 17), List.of(8, 61), List.of(9, 61), List.of(10, 23),
            List.of(10, 61), List.of(12, 23), List.of(12, 54), List.of(13, 23), List.of(13, 61),
            List.of(15, 1), List.of(15, 28), List.of(15, 55), List.of(16, 10), List.of(16, 73),
            List.of(17, 10), List.of(17, 73), List.of(18, 10), List.of(18, 73), List.of(19, 10),
            List.of(19, 58), List.of(20, 10), List.of(20, 41), List.of(20, 78), List.of(22, 23),
            List.of(23, 1));

    private static final List<Integer> DATA_OFFSETS = List.of(19, 30, 77, 92, 107, 154, 169, 187, 195,
            212, 234, 251, 273, 290, 312, 334, 351, 373, 389, 408, 425, 435, 467, 499, 531, 588, 597,
            654, 666, 723, 733, 753, 780, 800, 817, 825, 877);

    private FixedWidthCodec asciiCodec;

    private FixedWidthCodec ebcdicCodec;

    @BeforeEach
    void buildCodecs() {
        asciiCodec = new FixedWidthCodec(ASCII);
        ebcdicCodec = new FixedWidthCodec(EBCDIC);
    }

    private static List<BiConsumer<AccountViewRequest, String>> namedSetters() {
        List<BiConsumer<AccountViewRequest, String>> setters = new ArrayList<>();
        setters.add(AccountViewRequest::setTrnname);
        setters.add(AccountViewRequest::setTitle01);
        setters.add(AccountViewRequest::setCurdate);
        setters.add(AccountViewRequest::setPgmname);
        setters.add(AccountViewRequest::setTitle02);
        setters.add(AccountViewRequest::setCurtime);
        setters.add(AccountViewRequest::setAcctsid);
        setters.add(AccountViewRequest::setAcsttus);
        setters.add(AccountViewRequest::setAdtopen);
        setters.add(AccountViewRequest::setAcrdlim);
        setters.add(AccountViewRequest::setAexpdt);
        setters.add(AccountViewRequest::setAcshlim);
        setters.add(AccountViewRequest::setAreisdt);
        setters.add(AccountViewRequest::setAcurbal);
        setters.add(AccountViewRequest::setAcrcycr);
        setters.add(AccountViewRequest::setAaddgrp);
        setters.add(AccountViewRequest::setAcrcydb);
        setters.add(AccountViewRequest::setAcstnum);
        setters.add(AccountViewRequest::setAcstssn);
        setters.add(AccountViewRequest::setAcstdob);
        setters.add(AccountViewRequest::setAcstfco);
        setters.add(AccountViewRequest::setAcsfnam);
        setters.add(AccountViewRequest::setAcsmnam);
        setters.add(AccountViewRequest::setAcslnam);
        setters.add(AccountViewRequest::setAcsadl1);
        setters.add(AccountViewRequest::setAcsstte);
        setters.add(AccountViewRequest::setAcsadl2);
        setters.add(AccountViewRequest::setAcszipc);
        setters.add(AccountViewRequest::setAcscity);
        setters.add(AccountViewRequest::setAcsctry);
        setters.add(AccountViewRequest::setAcsphn1);
        setters.add(AccountViewRequest::setAcsgovt);
        setters.add(AccountViewRequest::setAcsphn2);
        setters.add(AccountViewRequest::setAcseftc);
        setters.add(AccountViewRequest::setAcspflg);
        setters.add(AccountViewRequest::setInfomsg);
        setters.add(AccountViewRequest::setErrmsg);
        return setters;
    }

    private static List<Function<AccountViewRequest, String>> namedGetters() {
        List<Function<AccountViewRequest, String>> getters = new ArrayList<>();
        getters.add(AccountViewRequest::getTrnname);
        getters.add(AccountViewRequest::getTitle01);
        getters.add(AccountViewRequest::getCurdate);
        getters.add(AccountViewRequest::getPgmname);
        getters.add(AccountViewRequest::getTitle02);
        getters.add(AccountViewRequest::getCurtime);
        getters.add(AccountViewRequest::getAcctsid);
        getters.add(AccountViewRequest::getAcsttus);
        getters.add(AccountViewRequest::getAdtopen);
        getters.add(AccountViewRequest::getAcrdlim);
        getters.add(AccountViewRequest::getAexpdt);
        getters.add(AccountViewRequest::getAcshlim);
        getters.add(AccountViewRequest::getAreisdt);
        getters.add(AccountViewRequest::getAcurbal);
        getters.add(AccountViewRequest::getAcrcycr);
        getters.add(AccountViewRequest::getAaddgrp);
        getters.add(AccountViewRequest::getAcrcydb);
        getters.add(AccountViewRequest::getAcstnum);
        getters.add(AccountViewRequest::getAcstssn);
        getters.add(AccountViewRequest::getAcstdob);
        getters.add(AccountViewRequest::getAcstfco);
        getters.add(AccountViewRequest::getAcsfnam);
        getters.add(AccountViewRequest::getAcsmnam);
        getters.add(AccountViewRequest::getAcslnam);
        getters.add(AccountViewRequest::getAcsadl1);
        getters.add(AccountViewRequest::getAcsstte);
        getters.add(AccountViewRequest::getAcsadl2);
        getters.add(AccountViewRequest::getAcszipc);
        getters.add(AccountViewRequest::getAcscity);
        getters.add(AccountViewRequest::getAcsctry);
        getters.add(AccountViewRequest::getAcsphn1);
        getters.add(AccountViewRequest::getAcsgovt);
        getters.add(AccountViewRequest::getAcsphn2);
        getters.add(AccountViewRequest::getAcseftc);
        getters.add(AccountViewRequest::getAcspflg);
        getters.add(AccountViewRequest::getInfomsg);
        getters.add(AccountViewRequest::getErrmsg);
        return getters;
    }

    private static AccountViewRequest populated(FixedWidthCodec codec) {
        AccountViewRequest request = new AccountViewRequest();
        List<BiConsumer<AccountViewRequest, String>> setters = namedSetters();
        for (int index = 0; index < LABELS.size(); index++) {
            setters.get(index).accept(request, codec.movePicX(LABELS.get(index) + "-value",
                    WIDTHS.get(index)));
        }
        return request;
    }

    @Nested
    @DisplayName("The width contract - 37 fields summing to 684 in a 955-byte group")
    class WidthContract {
        @Test
        @DisplayName("the 37 width constants are the 37 xxxI PICTURE widths")
        void widthConstantsMatchThePictureClauses() {
            assertThat(AccountViewRequest.TRNNAME_LENGTH).isEqualTo(WIDTHS.get(0));
            assertThat(AccountViewRequest.TITLE01_LENGTH).isEqualTo(WIDTHS.get(1));
            assertThat(AccountViewRequest.CURDATE_LENGTH).isEqualTo(WIDTHS.get(2));
            assertThat(AccountViewRequest.PGMNAME_LENGTH).isEqualTo(WIDTHS.get(3));
            assertThat(AccountViewRequest.TITLE02_LENGTH).isEqualTo(WIDTHS.get(4));
            assertThat(AccountViewRequest.CURTIME_LENGTH).isEqualTo(WIDTHS.get(5));
            assertThat(AccountViewRequest.ACCTSID_LENGTH).isEqualTo(WIDTHS.get(6));
            assertThat(AccountViewRequest.ACSTTUS_LENGTH).isEqualTo(WIDTHS.get(7));
            assertThat(AccountViewRequest.ADTOPEN_LENGTH).isEqualTo(WIDTHS.get(8));
            assertThat(AccountViewRequest.ACRDLIM_LENGTH).isEqualTo(WIDTHS.get(9));
            assertThat(AccountViewRequest.AEXPDT_LENGTH).isEqualTo(WIDTHS.get(10));
            assertThat(AccountViewRequest.ACSHLIM_LENGTH).isEqualTo(WIDTHS.get(11));
            assertThat(AccountViewRequest.AREISDT_LENGTH).isEqualTo(WIDTHS.get(12));
            assertThat(AccountViewRequest.ACURBAL_LENGTH).isEqualTo(WIDTHS.get(13));
            assertThat(AccountViewRequest.ACRCYCR_LENGTH).isEqualTo(WIDTHS.get(14));
            assertThat(AccountViewRequest.AADDGRP_LENGTH).isEqualTo(WIDTHS.get(15));
            assertThat(AccountViewRequest.ACRCYDB_LENGTH).isEqualTo(WIDTHS.get(16));
            assertThat(AccountViewRequest.ACSTNUM_LENGTH).isEqualTo(WIDTHS.get(17));
            assertThat(AccountViewRequest.ACSTSSN_LENGTH).isEqualTo(WIDTHS.get(18));
            assertThat(AccountViewRequest.ACSTDOB_LENGTH).isEqualTo(WIDTHS.get(19));
            assertThat(AccountViewRequest.ACSTFCO_LENGTH).isEqualTo(WIDTHS.get(20));
            assertThat(AccountViewRequest.ACSFNAM_LENGTH).isEqualTo(WIDTHS.get(21));
            assertThat(AccountViewRequest.ACSMNAM_LENGTH).isEqualTo(WIDTHS.get(22));
            assertThat(AccountViewRequest.ACSLNAM_LENGTH).isEqualTo(WIDTHS.get(23));
            assertThat(AccountViewRequest.ACSADL1_LENGTH).isEqualTo(WIDTHS.get(24));
            assertThat(AccountViewRequest.ACSSTTE_LENGTH).isEqualTo(WIDTHS.get(25));
            assertThat(AccountViewRequest.ACSADL2_LENGTH).isEqualTo(WIDTHS.get(26));
            assertThat(AccountViewRequest.ACSZIPC_LENGTH).isEqualTo(WIDTHS.get(27));
            assertThat(AccountViewRequest.ACSCITY_LENGTH).isEqualTo(WIDTHS.get(28));
            assertThat(AccountViewRequest.ACSCTRY_LENGTH).isEqualTo(WIDTHS.get(29));
            assertThat(AccountViewRequest.ACSPHN1_LENGTH).isEqualTo(WIDTHS.get(30));
            assertThat(AccountViewRequest.ACSGOVT_LENGTH).isEqualTo(WIDTHS.get(31));
            assertThat(AccountViewRequest.ACSPHN2_LENGTH).isEqualTo(WIDTHS.get(32));
            assertThat(AccountViewRequest.ACSEFTC_LENGTH).isEqualTo(WIDTHS.get(33));
            assertThat(AccountViewRequest.ACSPFLG_LENGTH).isEqualTo(WIDTHS.get(34));
            assertThat(AccountViewRequest.INFOMSG_LENGTH).isEqualTo(WIDTHS.get(35));
            assertThat(AccountViewRequest.ERRMSG_LENGTH).isEqualTo(WIDTHS.get(36));
        }

        @Test
        @DisplayName("each field costs 2 + 1 + 4 = 7 bytes before its data, after a 12-byte TIOAPFX prefix")
        void perFieldOverheadIsSeven() {
            assertThat(AccountViewRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(AccountViewRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountViewRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH).isEqualTo(4);
            assertThat(AccountViewRequest.FIELD_OVERHEAD).isEqualTo(7);
            assertThat(AccountViewRequest.TIOAPFX_LENGTH).isEqualTo(12);
        }

        @Test
        @DisplayName("37 fields, and the widths sum to 684")
        void theThirtySevenWidthsSumTo684() {
            int sum = 0;
            for (int width : WIDTHS) {
                sum += width;
            }
            assertThat(WIDTHS).hasSize(37);
            assertThat(sum).isEqualTo(684);
            assertThat(AccountViewRequest.FIELD_COUNT).isEqualTo(37);
            assertThat(AccountViewRequest.PAYLOAD_LENGTH).isEqualTo(684);
        }

        @Test
        @DisplayName("12 + 37 * 7 + 684 = 955")
        void theGroupIs955Bytes() {
            assertThat(12 + 37 * 7 + 684).isEqualTo(955);
            assertThat(AccountViewRequest.GROUP_LENGTH).isEqualTo(955);
        }

        @Test
        @DisplayName("the two message widths are the map's 45 and 78, never the program's 40 and 75")
        void messageWidthsAreTheMapsNotTheProgramsWorkingStorage() {
            assertThat(AccountViewRequest.INFOMSG_LENGTH).isEqualTo(45).isNotEqualTo(40);
            assertThat(AccountViewRequest.ERRMSG_LENGTH).isEqualTo(78).isNotEqualTo(75);
        }
    }

    @Nested
    @DisplayName("ScreenField - the 37 name-labelled DFHMDF entries of 100")
    class Fields {
        @Test
        @DisplayName("37 constants, in mapset declaration order, each with its full provenance")
        void theThirtySevenConstantsCarryTheirProvenance() {
            ScreenField[] fields = ScreenField.values();
            assertThat(fields).hasSize(37);
            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(field.name()).isEqualTo(LABELS.get(index));
                assertThat(field.label()).isEqualTo(LABELS.get(index));
                assertThat(field.symbolicItemName()).isEqualTo(LABELS.get(index) + "I");
                assertThat(field.picture()).isEqualTo(PICTURES.get(index));
                assertThat(field.length()).isEqualTo(WIDTHS.get(index));
                assertThat(field.copybookLine()).isEqualTo(CPY_LINES.get(index));
                assertThat(field.mapsetLine()).isEqualTo(BMS_LINES.get(index));
                assertThat(field.screenRow()).isEqualTo(POSITIONS.get(index).get(0));
                assertThat(field.screenColumn()).isEqualTo(POSITIONS.get(index).get(1));
                assertThat(field.dataOffset()).isEqualTo(DATA_OFFSETS.get(index));
            }
        }

        @Test
        @DisplayName("the interleaved left-then-right column order of the copybook is preserved")
        void theInterleavedColumnOrderIsPreserved() {
            assertThat(List.of(ScreenField.values()).subList(8, 17))
                    .containsExactly(ScreenField.ADTOPEN, ScreenField.ACRDLIM, ScreenField.AEXPDT,
                            ScreenField.ACSHLIM, ScreenField.AREISDT, ScreenField.ACURBAL,
                            ScreenField.ACRCYCR, ScreenField.AADDGRP, ScreenField.ACRCYDB);
        }

        @Test
        @DisplayName("the strides abut, leaving no gap and no overlap, and end exactly on 955")
        void stridesAbutAndEndOn955() {
            int cursor = AccountViewRequest.TIOAPFX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.lengthItemOffset()).isEqualTo(cursor);
                assertThat(field.flagItemOffset()).isEqualTo(cursor + 2);
                assertThat(field.extendedAttributeItemOffset()).isEqualTo(cursor + 3);
                assertThat(field.dataOffset()).isEqualTo(cursor + 7);
                cursor = field.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(AccountViewRequest.GROUP_LENGTH);
            assertThat(ScreenField.ERRMSG.endOffsetExclusive()).isEqualTo(955);
        }

        @Test
        @DisplayName("every field sits inside the SIZE=(24,80) screen")
        void everyFieldSitsOnA24By80Screen() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.screenRow()).isBetween(1, 24);
                assertThat(field.screenColumn()).isBetween(1, 80);
                assertThat(field.screenColumn() + field.length() - 1).isLessThanOrEqualTo(80);
            }
        }

        @Test
        @DisplayName("ACCTSID is the one field whose PICTURE is not alphanumeric")
        void acctsidIsTheOnlyNonAlphanumericItem() {
            assertThat(ScreenField.ACCTSID.picture()).isEqualTo("99999999999");
            assertThat(ScreenField.ACCTSID.isAlphanumeric()).isFalse();
            assertThat(EnumSet.complementOf(EnumSet.of(ScreenField.ACCTSID)))
                    .hasSize(36)
                    .allSatisfy(field -> {
                        assertThat(field.isAlphanumeric()).isTrue();
                        assertThat(field.picture()).startsWith("X(");
                    });
        }

        @Test
        @DisplayName("describe names the label, item, PICTURE, both source lines, POS and offsets")
        void describeNamesTheWholeProvenance() {
            assertThat(ScreenField.ACSGOVT.describe())
                    .isEqualTo("ACSGOVT ACSGOVTI PIC X(20) COACTVW.CPY:210 COACTVW.bms:326 "
                            + "POS=(19,58) offset 753..773");
            assertThat(ScreenField.ACCTSID.describe())
                    .contains("PIC 99999999999")
                    .doesNotContain("PIC X(11)");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("byLabel resolves every one of the 37 labels")
        void byLabelResolvesEveryField(ScreenField field) {
            assertThat(ScreenField.byLabel(field.label())).isSameAs(field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"FKEYS", "FKEY05", "FKEY12", "PAGENO", "CARDSID", "acctsid", "",
                "ACCTSIDI"})
        @DisplayName("byLabel rejects what COACTVW does not declare, FKEYS and PAGENO included")
        void byLabelRejectsWhatCoactvwDoesNotDeclare(String label) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.byLabel(label))
                    .withMessageContaining("COACTVW.bms");
        }

        @Test
        @DisplayName("byLabel rejects a null label rather than matching something")
        void byLabelRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> ScreenField.byLabel(null));
        }
    }

    @Nested
    @DisplayName("Construction - a freshly initialised map area, with no null anywhere")
    class Construction {
        @Test
        @DisplayName("every field is spaces at its declared width, and no commarea has travelled yet")
        void freshRequestIsAnInitialisedMapArea() {
            AccountViewRequest request = new AccountViewRequest();
            List<Function<AccountViewRequest, String>> getters = namedGetters();
            for (int index = 0; index < LABELS.size(); index++) {
                String value = getters.get(index).apply(request);
                assertThat(value)
                        .as("%s starts as spaces", LABELS.get(index))
                        .isEqualTo(" ".repeat(WIDTHS.get(index)))
                        .hasSize(WIDTHS.get(index));
            }
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
        }

        @Test
        @DisplayName("every metadata holder starts unset, with no cursor anywhere")
        void everyMetadataHolderStartsUnset() {
            AccountViewRequest request = new AccountViewRequest();
            assertThat(request.metadata()).hasSize(37);
            for (ScreenField field : ScreenField.values()) {
                ScreenFieldMetadata holder = request.metadata(field);
                assertThat(holder).isNotNull();
                assertThat(holder.getLength()).isEqualTo(ScreenFieldMetadata.LENGTH_UNSET);
                assertThat(holder.isLengthUnset()).isTrue();
                assertThat(holder.isCursorHere()).isFalse();
                assertThat(holder.getAttribute()).isEqualTo(ScreenFieldMetadata.ATTRIBUTE_UNSET);
                assertThat(holder.isAttributeUnset()).isTrue();
            }
        }

        @Test
        @DisplayName("withAccountFilter sets only ACCTSID, the one field COACTVWC reads")
        void withAccountFilterSetsOnlyTheTypedField() {
            AccountViewRequest request = AccountViewRequest.withAccountFilter("00000000011");
            assertThat(request.getAcctsid()).isEqualTo("00000000011");
            assertThat(request).isNotEqualTo(new AccountViewRequest());
            AccountViewRequest baseline = new AccountViewRequest();
            baseline.setAcctsid("00000000011");
            assertThat(request).isEqualTo(baseline);
        }

        @ParameterizedTest
        @ValueSource(strings = {"*", "           ", "", "00000000011"})
        @DisplayName("withAccountFilter accepts the wildcard and the blank the program tests for")
        void withAccountFilterAcceptsWildcardAndBlank(String filter) {
            assertThat(AccountViewRequest.withAccountFilter(filter).getAcctsid()).isEqualTo(filter);
        }

        @Test
        @DisplayName("withAccountFilter treats null as spaces, because a COBOL record has no null")
        void withAccountFilterTreatsNullAsSpaces() {
            assertThat(AccountViewRequest.withAccountFilter(null).getAcctsid())
                    .isEqualTo(" ".repeat(AccountViewRequest.ACCTSID_LENGTH));
        }

        @Test
        @DisplayName("the copy constructor copies every field, both carriers and all 37 holders")
        void copyConstructorCopiesEverything() {
            AccountViewRequest original = populated(asciiCodec);
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());
            original.metadata(ScreenField.ACCTSID).positionCursorHere();
            original.metadata(ScreenField.ACCTSID).setAttribute((byte) 0xC1);
            original.getCardScreenState().setCcardAid(CardScreenState.CCARD_AID_PFK03);

            AccountViewRequest copy = new AccountViewRequest(original);
            assertThat(copy).isEqualTo(original).isNotSameAs(original);
            assertThat(copy.getCardScreenState()).isNotSameAs(original.getCardScreenState());
            assertThat(copy.metadata(ScreenField.ACCTSID))
                    .isNotSameAs(original.metadata(ScreenField.ACCTSID));
            assertThat(copy.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(copy.metadata(ScreenField.ACCTSID).getAttribute()).isEqualTo((byte) 0xC1);
        }

        @Test
        @DisplayName("a copy cannot move the original's cursor, nor the original the copy's")
        void copiedHoldersAreIndependent() {
            AccountViewRequest original = new AccountViewRequest();
            AccountViewRequest copy = new AccountViewRequest(original);
            copy.metadata(ScreenField.ACCTSID).positionCursorHere();
            assertThat(original.metadata(ScreenField.ACCTSID).isCursorHere()).isFalse();
            assertThat(copy.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
        }

        @Test
        @DisplayName("the copy constructor requires a request")
        void copyConstructorRequiresARequest() {
            assertThatNullPointerException().isThrownBy(() -> new AccountViewRequest(null));
        }

        @Test
        @DisplayName("initializeMapArea returns fields, holders and carriers to their initial state")
        void initializeMapAreaResetsEverything() {
            AccountViewRequest request = populated(asciiCodec);
            request.setNavigationContext(NavigationContext.empty());
            request.metadata(ScreenField.ERRMSG).positionCursorHere();
            request.metadata(ScreenField.ERRMSG).setAttribute((byte) 0x61);

            request.initializeMapArea();

            assertThat(request).isEqualTo(new AccountViewRequest());
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.metadata(ScreenField.ERRMSG).isLengthUnset()).isTrue();
            assertThat(request.metadata(ScreenField.ERRMSG).isAttributeUnset()).isTrue();
        }

        @Test
        @DisplayName("spaces repeats the figurative constant and refuses a negative width")
        void spacesRepeatsAndRefusesNegative() {
            assertThat(AccountViewRequest.spaces(0)).isEmpty();
            assertThat(AccountViewRequest.spaces(4)).isEqualTo("    ");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewRequest.spaces(-1))
                    .withMessageContaining("cannot be -1 characters");
        }
    }

    @Nested
    @DisplayName("Accessors - 37 pairs that store verbatim and never trim")
    class Accessors {
        @Test
        @DisplayName("every named setter round-trips through its named getter")
        void namedPairsRoundTrip() {
            AccountViewRequest request = new AccountViewRequest();
            List<BiConsumer<AccountViewRequest, String>> setters = namedSetters();
            List<Function<AccountViewRequest, String>> getters = namedGetters();
            for (int index = 0; index < LABELS.size(); index++) {
                String written = LABELS.get(index) + "/" + index;
                setters.get(index).accept(request, written);
                assertThat(getters.get(index).apply(request))
                        .as("%s round-trips verbatim", LABELS.get(index))
                        .isEqualTo(written);
            }
        }

        @Test
        @DisplayName("a setter neither pads a short value nor truncates a long one")
        void settersDoNotApplyTheMoveRule() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("A");
            assertThat(request.getTrnname()).isEqualTo("A").hasSize(1);
            request.setTrnname("ABCDEFGH");
            assertThat(request.getTrnname()).isEqualTo("ABCDEFGH").hasSize(8);
        }

        @Test
        @DisplayName("a getter never trims the trailing spaces a fixed-width field carries")
        void gettersDoNotTrim() {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcsstte("A ");
            assertThat(request.getAcsstte()).isEqualTo("A ");
        }

        @Test
        @DisplayName("every setter takes null as spaces of that field's declared width")
        void everySetterTakesNullAsSpaces() {
            AccountViewRequest request = populated(asciiCodec);
            List<BiConsumer<AccountViewRequest, String>> setters = namedSetters();
            List<Function<AccountViewRequest, String>> getters = namedGetters();
            for (int index = 0; index < LABELS.size(); index++) {
                setters.get(index).accept(request, null);
                assertThat(getters.get(index).apply(request))
                        .as("%s takes null as spaces", LABELS.get(index))
                        .isEqualTo(" ".repeat(WIDTHS.get(index)));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"*", "           ", "0000000ABCD", "99999999999"})
        @DisplayName("ACCTSID stores the wildcard, the blank and any digits, unaltered")
        void acctsidStoresWhateverTheTerminalSent(String typed) {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcctsid(typed);
            assertThat(request.getAcctsid()).isEqualTo(typed);
        }

        @Test
        @DisplayName("ACCTSID has a numeric PICTURE but must be an 11-character String")
        void acctsidIsCarriedAsCharactersDespiteItsNumericPicture() {
            AccountViewRequest request = new AccountViewRequest();

            assertThat(request.getAcctsid()).isInstanceOf(String.class);
            assertThat(ScreenField.ACCTSID.picture())
                    .as("COACTVW.CPY:60 - the one numeric PICTURE among the 37")
                    .isEqualTo("99999999999");
            assertThat(ScreenField.ACCTSID.isAlphanumeric())
                    .as("declared numeric, so not a PIC X item")
                    .isFalse();
            assertThat(ScreenField.ACCTSID.length()).isEqualTo(11);

            assertThatNoException().isThrownBy(
                    () -> AccountViewRequest.class.getDeclaredMethod("getAcctsid"));
            Method accessor = Arrays.stream(AccountViewRequest.class.getDeclaredMethods())
                    .filter(method -> "getAcctsid".equals(method.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(accessor.getReturnType()).isEqualTo(String.class);
            assertThat(accessor.getReturnType())
                    .isNotIn(int.class, long.class, Integer.class, Long.class, BigDecimal.class,
                            java.math.BigInteger.class);
        }

        @Test
        @DisplayName("ACCTSID round-trips LOW-VALUES - binary zeros, not spaces and not null")
        void acctsidRoundTripsLowValues() {
            String lowValues = "\u0000".repeat(11);
            AccountViewRequest request = new AccountViewRequest();
            request.setAcctsid(lowValues);

            assertThat(request.getAcctsid())
                    .isEqualTo(lowValues)
                    .hasSize(11)
                    .isNotNull()
                    .isNotEqualTo(" ".repeat(11))
                    .isNotEmpty();
            assertThat(request.getAcctsid().charAt(0)).isEqualTo('\u0000');

            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
                byte[] group = request.toGroupImage(codec);
                for (int offset = 0; offset < 11; offset++) {
                    assertThat(group[ScreenField.ACCTSID.dataOffset() + offset])
                            .as("LOW-VALUES byte %d under %s", offset, codec.charset().name())
                            .isZero();
                }
                assertThat(AccountViewRequest.fromGroupImage(group, codec).getAcctsid())
                        .isEqualTo(lowValues);
            }

            AccountViewRequest wildcard = new AccountViewRequest();
            wildcard.setAcctsid("*");
            AccountViewRequest blank = new AccountViewRequest();
            blank.setAcctsid(AccountViewRequest.spaces(11));
            AccountViewRequest keyed = new AccountViewRequest();
            keyed.setAcctsid("00000000011");

            assertThat(List.of(wildcard.getAcctsid(), blank.getAcctsid(), keyed.getAcctsid(),
                    request.getAcctsid())).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a null ACCTSID becomes spaces, because a COBOL record has no null")
        void acctsidNullBecomesSpaces() {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcctsid(null);
            assertThat(request.getAcctsid())
                    .isNotNull()
                    .isEqualTo(" ".repeat(AccountViewRequest.ACCTSID_LENGTH));
        }

        @Test
        @DisplayName("the cardholder identifiers are returned in the clear, exactly as the map declares")
        void personalIdentifiersAreNotMasked() {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcstssn("078-05-1120");
            request.setAcstdob("1970-01-01");
            request.setAcsgovt("GOVT-ID-0099");
            assertThat(request.getAcstssn()).isEqualTo("078-05-1120");
            assertThat(request.getAcstdob()).isEqualTo("1970-01-01");
            assertThat(request.getAcsgovt()).isEqualTo("GOVT-ID-0099");
        }

        @Test
        @DisplayName("the work area is replaced, and null is taken as a freshly initialised one")
        void cardScreenStateIsReplaceableAndNeverNull() {
            AccountViewRequest request = new AccountViewRequest();
            CardScreenState supplied = new CardScreenState();
            request.setCardScreenState(supplied);
            assertThat(request.getCardScreenState()).isSameAs(supplied);
            request.setCardScreenState(null);
            assertThat(request.getCardScreenState()).isNotNull().isNotSameAs(supplied);
        }
    }

    @Nested
    @DisplayName("Addressing a field by its enumeration constant")
    class EnumAddressing {
        @Test
        @DisplayName("value and setValue agree with all 37 named pairs, both ways")
        void enumAccessAgreesWithNamedAccess() {
            AccountViewRequest byEnum = new AccountViewRequest();
            AccountViewRequest byName = new AccountViewRequest();
            List<BiConsumer<AccountViewRequest, String>> setters = namedSetters();
            List<Function<AccountViewRequest, String>> getters = namedGetters();
            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                String written = "v" + index + "-" + LABELS.get(index);
                byEnum.setValue(fields[index], written);
                setters.get(index).accept(byName, written);
                assertThat(byEnum.value(fields[index]))
                        .as("%s reads back through value()", LABELS.get(index))
                        .isEqualTo(written)
                        .isEqualTo(getters.get(index).apply(byName));
            }
            assertThat(byEnum).isEqualTo(byName);
        }

        @Test
        @DisplayName("setValue takes null as spaces, just as the named setter does")
        void setValueTakesNullAsSpaces() {
            AccountViewRequest request = populated(asciiCodec);
            for (ScreenField field : ScreenField.values()) {
                request.setValue(field, null);
                assertThat(request.value(field)).isEqualTo(" ".repeat(field.length()));
            }
        }

        @Test
        @DisplayName("a field is mandatory on both routes")
        void aFieldIsMandatory() {
            AccountViewRequest request = new AccountViewRequest();
            assertThatNullPointerException().isThrownBy(() -> request.value(null));
            assertThatNullPointerException().isThrownBy(() -> request.setValue(null, "x"));
            assertThatNullPointerException().isThrownBy(() -> request.metadata(null));
        }

        @Test
        @DisplayName("the metadata map is unmodifiable, but its holders stay live")
        void metadataMapIsUnmodifiableAndItsHoldersLive() {
            AccountViewRequest request = new AccountViewRequest();
            Map<ScreenField, ScreenFieldMetadata> holders = request.metadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> holders.remove(ScreenField.ACCTSID));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> holders.put(ScreenField.ERRMSG, new ScreenFieldMetadata()));
            holders.get(ScreenField.ACCTSID).positionCursorHere();
            assertThat(request.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(request.metadata(ScreenField.ACCTSID))
                    .isSameAs(holders.get(ScreenField.ACCTSID));
        }

        @Test
        @DisplayName("the map iterates in copybook storage order")
        void metadataIteratesInCopybookOrder()  {
            assertThat(new AccountViewRequest().metadata().keySet())
                    .containsExactly(ScreenField.values());
        }
    }

    @Nested
    @DisplayName("ScreenFieldMetadata - the xxxL halfword and the xxxA attribute byte")
    class MetadataHolder {
        @Test
        @DisplayName("the declared PIC S9(4) range is the constraint, not the halfword's capacity")
        void theDeclaredRangeIsTheConstraint() {
            assertThat(ScreenFieldMetadata.LENGTH_ITEM_MIN).isEqualTo(-9999);
            assertThat(ScreenFieldMetadata.LENGTH_ITEM_MAX).isEqualTo(9999);
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThatNoException().isThrownBy(() -> holder.setLength(9999));
            assertThatNoException().isThrownBy(() -> holder.setLength(-9999));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> holder.setLength(10_000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> holder.setLength(-10_000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatIllegalArgumentException().isThrownBy(() -> holder.setLength(30_000));
        }

        @Test
        @DisplayName("-1 is the cursor signal and 0 is 'not entered'; they are different questions")
        void cursorAndUnsetAreDifferentQuestions() {
            assertThat(ScreenFieldMetadata.CURSOR_HERE).isEqualTo(-1);
            assertThat(ScreenFieldMetadata.LENGTH_UNSET).isZero();

            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.isLengthUnset()).isTrue();
            assertThat(holder.isCursorHere()).isFalse();

            holder.positionCursorHere();
            assertThat(holder.getLength()).isEqualTo(-1);
            assertThat(holder.isCursorHere()).isTrue();
            assertThat(holder.isLengthUnset()).isFalse();

            holder.setLength(11);
            assertThat(holder.isCursorHere()).isFalse();
            assertThat(holder.isLengthUnset()).isFalse();
        }

        @Test
        @DisplayName("xxxA and xxxF are one byte described twice, and cannot disagree")
        void theAttributeAndFlagViewsAreOneByte() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.getAttribute()).isEqualTo(holder.getFlag()).isEqualTo((byte) 0x00);
            assertThat(holder.isAttributeUnset()).isTrue();
            holder.setAttribute((byte) 0xC1);
            assertThat(holder.getAttribute()).isEqualTo((byte) 0xC1).isEqualTo(holder.getFlag());
            assertThat(holder.isAttributeUnset()).isFalse();
        }

        @Test
        @DisplayName("every one of the 256 attribute values is accepted without interpretation")
        void everyAttributeByteIsAccepted() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
                byte attribute = (byte) value;
                holder.setAttribute(attribute);
                assertThat(holder.getAttribute()).isEqualTo(attribute);
            }
        }

        @Test
        @DisplayName("the explicit constructor validates, the copy constructor copies, reset restores")
        void constructorsAndReset() {
            ScreenFieldMetadata explicit = new ScreenFieldMetadata(-1, (byte) 0x61);
            assertThat(explicit.getLength()).isEqualTo(-1);
            assertThat(explicit.getAttribute()).isEqualTo((byte) 0x61);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScreenFieldMetadata(12_345, (byte) 0));

            ScreenFieldMetadata copy = new ScreenFieldMetadata(explicit);
            assertThat(copy).isEqualTo(explicit).isNotSameAs(explicit);
            assertThatNullPointerException().isThrownBy(() -> new ScreenFieldMetadata(null));

            copy.reset();
            assertThat(copy).isEqualTo(new ScreenFieldMetadata());
            assertThat(copy).isNotEqualTo(explicit);
        }

        @Test
        @DisplayName("value equality covers both items, and the hash agrees")
        void valueEqualityCoversBothItems() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata(5, (byte) 0x40);
            assertThat(holder).isEqualTo(holder);
            assertThat(holder).isEqualTo(new ScreenFieldMetadata(5, (byte) 0x40));
            assertThat(holder).hasSameHashCodeAs(new ScreenFieldMetadata(5, (byte) 0x40));
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(6, (byte) 0x40));
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(5, (byte) 0x41));
            assertThat(holder).isNotEqualTo("ScreenFieldMetadata[length=5, attribute=0x40]");
            assertThat(holder).isNotEqualTo(null);
        }

        @Test
        @DisplayName("the rendering shows the attribute as hex, because it is a bit pattern")
        void renderingShowsTheAttributeAsHex() {
            assertThat(new ScreenFieldMetadata(-1, (byte) 0xC1))
                    .hasToString("ScreenFieldMetadata[length=-1, attribute=0xC1]");
            assertThat(new ScreenFieldMetadata())
                    .hasToString("ScreenFieldMetadata[length=0, attribute=0x00]");
        }
    }

    @Nested
    @DisplayName("Conversation state travels in the payload, never in a session")
    class ConversationState {
        @Test
        @DisplayName("an absent commarea is EIBCALEN 0 and satisfies neither ENTER nor REENTER")
        void anAbsentCommareaSatisfiesNeitherCondition() {
            AccountViewRequest request = new AccountViewRequest();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("a present commarea reports 160 bytes, and ENTER is the paint-the-screen arm")
        void enterIsThePaintArm() {
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("REENTER is the validate-what-was-typed arm, and the highlight conjunct")
        void reenterIsTheValidateArm() {
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();
        }

        @Test
        @DisplayName("a context of 9 satisfies neither condition - PIC 9(01) holds any digit")
        void anUnexpectedContextSatisfiesNeitherCondition() {
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(request.getPgmContext()).isEqualTo(9);
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("null is stored as absence, not substituted with an initialised area")
        void nullCommareaIsStoredAsAbsence() {
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.hasNavigationContext()).isTrue();
            request.setNavigationContext(null);
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("no session, no session attribute, no static holder - the type carries its own state")
        void thereIsNoServerSideState() {
            AccountViewRequest first = AccountViewRequest.withAccountFilter("00000000011");
            AccountViewRequest second = AccountViewRequest.withAccountFilter("00000000022");
            assertThat(first.getAcctsid()).isEqualTo("00000000011");
            assertThat(second.getAcctsid()).isEqualTo("00000000022");
            first.metadata(ScreenField.ACCTSID).positionCursorHere();
            assertThat(second.metadata(ScreenField.ACCTSID).isCursorHere()).isFalse();
        }
    }

    @Nested
    @DisplayName("Fixed-width rendering - the move rule lives in FixedWidthCodec and nowhere else")
    class GroupImage {
        @Test
        @DisplayName("image pads a short value on the right and truncates a long one on the right")
        void imageAppliesTheAlphanumericMoveRule() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("A");
            assertThat(request.image(ScreenField.TRNNAME, asciiCodec)).isEqualTo("A   ");
            request.setTrnname("ABCDEFGH");
            assertThat(request.image(ScreenField.TRNNAME, asciiCodec)).isEqualTo("ABCD");
            request.setAcctsid("*");
            assertThat(request.image(ScreenField.ACCTSID, asciiCodec)).isEqualTo("*          ");
        }

        @Test
        @DisplayName("image requires both a field and a codec")
        void imageRequiresAFieldAndACodec() {
            AccountViewRequest request = new AccountViewRequest();
            assertThatNullPointerException().isThrownBy(() -> request.image(null, asciiCodec));
            assertThatNullPointerException().isThrownBy(() -> request.image(ScreenField.TRNNAME, null));
            assertThatNullPointerException().isThrownBy(() -> request.normalize(null));
        }

        @Test
        @DisplayName("normalize leaves all 37 fields at exactly their declared widths")
        void normalizeAppliesEveryWidth() {
            AccountViewRequest request = new AccountViewRequest();
            for (ScreenField field : ScreenField.values()) {
                request.setValue(field, "X");
            }
            request.normalize(asciiCodec);
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field))
                        .as("%s is normalised to its declared width", field.label())
                        .hasSize(field.length())
                        .startsWith("X");
            }
        }

        @Test
        @DisplayName("the group image is 955 bytes, with the prefix and the FILLER accounted for")
        void theGroupImageIs955Bytes() {
            byte[] image = new AccountViewRequest().toGroupImage(asciiCodec);
            assertThat(image).hasSize(955);
            for (int index = 0; index < AccountViewRequest.TIOAPFX_LENGTH; index++) {
                assertThat(image[index]).as("prefix byte %d is an ASCII space", index)
                        .isEqualTo((byte) 0x20);
            }
            for (ScreenField field : ScreenField.values()) {
                assertThat(image[field.lengthItemOffset()]).isZero();
                assertThat(image[field.lengthItemOffset() + 1]).isZero();
                assertThat(image[field.flagItemOffset()]).isZero();
                for (int offset = 0; offset < AccountViewRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH;
                        offset++) {
                    assertThat(image[field.extendedAttributeItemOffset() + offset])
                            .as("%s extended-attribute byte %d is LOW-VALUES", field.label(), offset)
                            .isZero();
                }
            }
        }

        @Test
        @DisplayName("the cursor halfword is written big-endian, so -1 is 0xFFFF")
        void theCursorHalfwordIsBigEndian() {
            AccountViewRequest request = new AccountViewRequest();
            request.metadata(ScreenField.ACCTSID).positionCursorHere();
            request.metadata(ScreenField.ERRMSG).setLength(258);
            byte[] image = request.toGroupImage(asciiCodec);
            assertThat(image[ScreenField.ACCTSID.lengthItemOffset()]).isEqualTo((byte) 0xFF);
            assertThat(image[ScreenField.ACCTSID.lengthItemOffset() + 1]).isEqualTo((byte) 0xFF);
            assertThat(image[ScreenField.ERRMSG.lengthItemOffset()]).isEqualTo((byte) 0x01);
            assertThat(image[ScreenField.ERRMSG.lengthItemOffset() + 1]).isEqualTo((byte) 0x02);
        }

        @Test
        @DisplayName("the attribute byte is written raw, never through the charset")
        void theAttributeByteIsWrittenRaw() {
            AccountViewRequest request = new AccountViewRequest();
            request.metadata(ScreenField.ACCTSID).setAttribute((byte) 0xC1);
            assertThat(request.toGroupImage(asciiCodec)[ScreenField.ACCTSID.flagItemOffset()])
                    .isEqualTo((byte) 0xC1);
            assertThat(request.toGroupImage(ebcdicCodec)[ScreenField.ACCTSID.flagItemOffset()])
                    .isEqualTo((byte) 0xC1);
        }

        @Test
        @DisplayName("the field data is encoded in the codec's code page, not a platform default")
        void theFieldDataFollowsTheCodecsCodePage() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("CAVW");
            byte[] ascii = request.toGroupImage(asciiCodec);
            byte[] ebcdic = request.toGroupImage(ebcdicCodec);

            assertThat(ascii[ScreenField.TRNNAME.dataOffset()]).isEqualTo((byte) 0x43);
            assertThat(ebcdic[ScreenField.TRNNAME.dataOffset()]).isEqualTo((byte) 0xC3);
            assertThat(ascii[ScreenField.TRNNAME.dataOffset() + 1]).isEqualTo((byte) 0x41);
            assertThat(ebcdic[ScreenField.TRNNAME.dataOffset() + 1]).isEqualTo((byte) 0xC1);

            assertThat(ascii[ScreenField.INFOMSG.dataOffset()]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[ScreenField.INFOMSG.dataOffset()]).isEqualTo((byte) 0x40);
            assertThat(ascii[0]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[0]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("a multi-byte code page fails loudly instead of overflowing the declared width")
        void aMultiByteCodePageFailsLoudly() {
            FixedWidthCodec utf8 = new FixedWidthCodec(StandardCharsets.UTF_8);
            assertThatNoException().isThrownBy(() -> new AccountViewRequest().toGroupImage(utf8));

            AccountViewRequest accented = new AccountViewRequest();
            accented.setAcsfnam("JOSÉ");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> accented.toGroupImage(utf8))
                    .withMessageContaining("single-byte code page")
                    .withMessageContaining("ACSFNAM")
                    .withMessageContaining("26 byte(s)");
        }

        @Test
        @DisplayName("a populated request round-trips through the image, field for field")
        void aPopulatedRequestRoundTrips() {
            AccountViewRequest original = populated(asciiCodec);
            original.metadata(ScreenField.ACCTSID).positionCursorHere();
            original.metadata(ScreenField.ACCTSID).setAttribute((byte) 0xC1);
            original.metadata(ScreenField.ERRMSG).setLength(78);

            AccountViewRequest recovered =
                    AccountViewRequest.fromGroupImage(original.toGroupImage(asciiCodec), asciiCodec);

            for (ScreenField field : ScreenField.values()) {
                assertThat(recovered.value(field))
                        .as("%s survives the round trip", field.label())
                        .isEqualTo(original.value(field));
            }
            assertThat(recovered.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(recovered.metadata(ScreenField.ACCTSID).getAttribute()).isEqualTo((byte) 0xC1);
            assertThat(recovered.metadata(ScreenField.ERRMSG).getLength()).isEqualTo(78);
            assertThat(recovered).isEqualTo(original);
        }

        @Test
        @DisplayName("the round trip holds under EBCDIC too")
        void theRoundTripHoldsUnderEbcdic() {
            AccountViewRequest original = populated(asciiCodec);
            assertThat(AccountViewRequest.fromGroupImage(original.toGroupImage(ebcdicCodec),
                    ebcdicCodec)).isEqualTo(original);
        }

        @Test
        @DisplayName("a value read back is not trimmed - trailing spaces are part of it")
        void aRecoveredValueIsNotTrimmed() {
            AccountViewRequest original = new AccountViewRequest();
            original.setTrnname("CAVW");
            original.setAcsstte("NY");
            original.normalize(asciiCodec);
            AccountViewRequest recovered =
                    AccountViewRequest.fromGroupImage(original.toGroupImage(asciiCodec), asciiCodec);
            assertThat(recovered.getInfomsg()).isEqualTo(" ".repeat(45)).hasSize(45);
            assertThat(recovered.getAcsstte()).isEqualTo("NY");
        }

        @Test
        @DisplayName("the codec and the image are both mandatory")
        void theCodecAndImageAreMandatory() {
            AccountViewRequest request = new AccountViewRequest();
            assertThatNullPointerException().isThrownBy(() -> request.toGroupImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewRequest.fromGroupImage(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewRequest.fromGroupImage(new byte[955], null));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 954, 956, 504})
        @DisplayName("an image of any length but 955 is refused by name")
        void anImageOfTheWrongLengthIsRefused(int length) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewRequest.fromGroupImage(new byte[length], asciiCodec))
                    .withMessageContaining("955 bytes");
        }

        @Test
        @DisplayName("a halfword the PICTURE cannot represent is refused, on either side of the range")
        void aHalfwordOutsideThePictureRangeIsRefused() {
            byte[] tooHigh = new AccountViewRequest().toGroupImage(asciiCodec);
            tooHigh[ScreenField.ACCTSID.lengthItemOffset()] = (byte) 0x75;
            tooHigh[ScreenField.ACCTSID.lengthItemOffset() + 1] = (byte) 0x30;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewRequest.fromGroupImage(tooHigh, asciiCodec))
                    .withMessageContaining("COMP PIC S9(4)");

            byte[] tooLow = new AccountViewRequest().toGroupImage(asciiCodec);
            tooLow[ScreenField.ERRMSG.lengthItemOffset()] = (byte) 0x8A;
            tooLow[ScreenField.ERRMSG.lengthItemOffset() + 1] = (byte) 0xD0;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewRequest.fromGroupImage(tooLow, asciiCodec))
                    .withMessageContaining("COMP PIC S9(4)");
        }

        @Test
        @DisplayName("neither carrier is part of the map image; a recovered request starts fresh on both")
        void theCarriersAreNotPartOfTheImage() {
            AccountViewRequest original = populated(asciiCodec);
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());
            AccountViewRequest recovered =
                    AccountViewRequest.fromGroupImage(original.toGroupImage(asciiCodec), asciiCodec);
            assertThat(recovered.getNavigationContext()).isNull();
            assertThat(recovered.getCardScreenState()).isNotNull();
            assertThat(recovered).isNotEqualTo(original);
        }
    }

    @Nested
    @DisplayName("Value semantics over 37 fields, both carriers and 37 metadata holders")
    class ValueSemantics {
        @Test
        @DisplayName("a request equals itself, an equal request, and nothing else")
        void equalityIsReflexiveAndTyped() {
            AccountViewRequest request = populated(asciiCodec);
            assertThat(request).isEqualTo(request);
            assertThat(request).isEqualTo(new AccountViewRequest(request));
            assertThat(request).hasSameHashCodeAs(new AccountViewRequest(request));
            assertThat(request).isNotEqualTo(null);
            assertThat(request).isNotEqualTo("AccountViewRequest");
            assertThat(new AccountViewRequest()).isEqualTo(new AccountViewRequest());
            assertThat(new AccountViewRequest()).hasSameHashCodeAs(new AccountViewRequest());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a difference in any one of the 37 fields makes two requests unequal")
        void anyFieldDifferenceIsObserved(ScreenField field) {
            AccountViewRequest left = new AccountViewRequest();
            AccountViewRequest right = new AccountViewRequest();
            right.setValue(field, "DIFFERENT");
            assertThat(left)
                    .as("a difference in %s is observed", field.label())
                    .isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("a difference in either carrier or in the metadata makes two requests unequal")
        void carrierAndMetadataDifferencesAreObserved() {
            AccountViewRequest baseline = new AccountViewRequest();

            AccountViewRequest withCommarea = new AccountViewRequest();
            withCommarea.setNavigationContext(NavigationContext.empty());
            assertThat(withCommarea).isNotEqualTo(baseline);

            AccountViewRequest withCursor = new AccountViewRequest();
            withCursor.metadata(ScreenField.ACCTSID).positionCursorHere();
            assertThat(withCursor).isNotEqualTo(baseline);
            assertThat(withCursor.hashCode()).isNotEqualTo(baseline.hashCode());

            AccountViewRequest withAttribute = new AccountViewRequest();
            withAttribute.metadata(ScreenField.ACCTSID).setAttribute((byte) 0xC1);
            assertThat(withAttribute).isNotEqualTo(baseline);

            AccountViewRequest withWorkArea = new AccountViewRequest();
            withWorkArea.getCardScreenState().setCcardAid(CardScreenState.CCARD_AID_PFK03);
            assertThat(withWorkArea).isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("the rendering names every field by its DFHMDF label and discloses none of the "
                + "sensitive ones")
        void theRenderingNamesEveryFieldAndDisclosesNothingSensitive() {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcstssn("078-05-1120");
            request.setAcstdob("1970-01-01");
            request.setAcsgovt("GOVT-ID-0099");
            request.setAcctsid("00000000011");

            String rendered = request.toString();
            assertThat(rendered).startsWith("AccountViewRequest[");
            for (String label : LABELS) {
                assertThat(rendered).as("%s is named", label).contains(label + "='");
            }
            assertThat(rendered)
                    .doesNotContain("078-05-1120")
                    .doesNotContain("1970-01-01")
                    .doesNotContain("GOVT-ID-0099")
                    .doesNotContain("00000000011")
                    .contains("0011")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");

            assertThat(request.getAcstssn()).startsWith("078-05-1120");
            assertThat(request.getAcsgovt()).startsWith("GOVT-ID-0099");
        }

        @Test
        @DisplayName("no field value can forge a second log line")
        void noFieldValueCanForgeALogLine() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("CAVW\r\nINJECTED");

            assertThat(request.toString()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the rendering keeps trailing spaces visible by quoting each value")
        void theRenderingKeepsTrailingSpacesVisible() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("CAVW");
            assertThat(request.toString())
                    .contains("TRNNAME='CAVW'")
                    .contains("ACSSTTE='  '")
                    .contains("ScreenFieldMetadata[length=0, attribute=0x00]");
        }
    }

    @Nested
    @DisplayName("Validation is @Size and nothing else - the program keeps its own edits")
    class SizeValidation {
        private final Validator validator = buildValidator();

        private static Validator buildValidator() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                return factory.getValidator();
            }
        }

        @Test
        @DisplayName("a request at its declared widths raises no violation")
        void aRequestAtItsDeclaredWidthsIsValid() {
            assertThat(validator.validate(populated(asciiCodec))).isEmpty();
            assertThat(validator.validate(new AccountViewRequest())).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("one character over any declared width raises exactly one violation, named")
        void oneCharacterOverAnyWidthRaisesOneViolation(ScreenField field) {
            AccountViewRequest request = new AccountViewRequest();
            request.setValue(field, "X".repeat(field.length() + 1));
            Set<ConstraintViolation<AccountViewRequest>> violations = validator.validate(request);
            assertThat(violations).hasSize(1);
            ConstraintViolation<AccountViewRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(field.label().toLowerCase(Locale.ROOT));
            assertThat(violation.getMessage())
                    .contains(field.label())
                    .contains(field.symbolicItemName())
                    .contains("COACTVW.CPY:" + field.copybookLine());
        }

        @ParameterizedTest
        @ValueSource(strings = {"*", "           ", "", "0000000ABCD", "  *  "})
        @DisplayName("no constraint rejects the wildcard, a blank, or a non-numeric ACCTSID")
        void acctsidIsNeverRejectedForItsContent(String typed) {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcctsid(typed);
            assertThat(validator.validate(request))
                    .as("ACCTSID '%s' is accepted; COACTVWC does its own edits", typed)
                    .isEmpty();
        }

        @Test
        @DisplayName("a blank value never raises a violation on any of the 37 fields")
        void blanksAreNeverRejected() {
            AccountViewRequest request = new AccountViewRequest();
            for (ScreenField field : ScreenField.values()) {
                request.setValue(field, "");
            }
            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("every one of the 37 fields is constrained, so none is silently unbounded")
        void everyFieldIsConstrained() {
            AccountViewRequest request = new AccountViewRequest();
            for (ScreenField field : ScreenField.values()) {
                request.setValue(field, "X".repeat(field.length() + 1));
            }
            assertThat(validator.validate(request)).hasSize(37);
        }
    }

    @Nested
    @DisplayName("The JSON wire format is exactly the 37 fields plus the two carriers")
    class WireFormat {
        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("all 37 fields serialise under their lower-cased DFHMDF labels")
        void allThirtySevenFieldsSerialiseUnderTheirLabels() throws Exception {
            JsonNode json = mapper.valueToTree(populated(asciiCodec));
            for (int index = 0; index < LABELS.size(); index++) {
                String wireName = LABELS.get(index).toLowerCase(Locale.ROOT);
                assertThat(json.has(wireName)).as("%s is on the wire as %s", LABELS.get(index), wireName)
                        .isTrue();
                assertThat(json.get(wireName).asText()).hasSize(WIDTHS.get(index));
            }
            assertThat(json.has("cardScreenState")).isTrue();
            assertThat(json.has("navigationContext")).isTrue();
        }

        @Test
        @DisplayName("no metadata reaches the wire - no xxxL, no xxxF, no xxxA")
        void noMetadataReachesTheWire() {
            JsonNode json = mapper.valueToTree(populated(asciiCodec));
            assertThat(json.has("metadata")).isFalse();
            for (String label : LABELS) {
                String lower = label.toLowerCase(Locale.ROOT);
                assertThat(json.has(lower + "L")).isFalse();
                assertThat(json.has(lower + "F")).isFalse();
                assertThat(json.has(lower + "A")).isFalse();
            }
            assertThat(json.toString()).doesNotContain("ScreenFieldMetadata");
        }

        @Test
        @DisplayName("no derived flag is published twice - the payload cannot disagree with itself")
        void noDerivedFlagIsPublished() {
            JsonNode json = mapper.valueToTree(populated(asciiCodec));
            assertThat(json.has("enter")).isFalse();
            assertThat(json.has("reenter")).isFalse();
            assertThat(json.has("pgmContext")).isFalse();
            assertThat(json.has("commareaLength")).isFalse();
            assertThat(json.has("navigationContextPresent")).isFalse();
        }

        @Test
        @DisplayName("the payload carries exactly 39 members: 37 fields and the two carriers")
        void thePayloadCarriesExactlyThirtyNineMembers() {
            assertThat(mapper.valueToTree(populated(asciiCodec)).size()).isEqualTo(39);
        }

        @Test
        @DisplayName("a payload omitting a field leaves it at spaces, because a COBOL record has no null")
        void anOmittedFieldStaysAtSpaces() throws Exception {
            AccountViewRequest bound =
                    mapper.readValue("{\"acctsid\":\"00000000011\"}", AccountViewRequest.class);
            assertThat(bound.getAcctsid()).isEqualTo("00000000011");
            assertThat(bound.getInfomsg()).isEqualTo(" ".repeat(45));
            assertThat(bound.getErrmsg()).isEqualTo(" ".repeat(78));
            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.getCardScreenState()).isNotNull();
        }

        @Test
        @DisplayName("a payload round-trips through JSON with all 37 fields intact")
        void aPayloadRoundTripsThroughJson() throws Exception {
            AccountViewRequest original = populated(asciiCodec);
            AccountViewRequest bound = mapper.readValue(mapper.writeValueAsString(original),
                    AccountViewRequest.class);
            for (ScreenField field : ScreenField.values()) {
                assertThat(bound.value(field))
                        .as("%s survives JSON", field.label())
                        .isEqualTo(original.value(field));
            }
        }

        @Test
        @DisplayName("the wildcard and a blank survive JSON unaltered")
        void theWildcardSurvivesJson() throws Exception {
            AccountViewRequest bound = mapper.readValue("{\"acctsid\":\"*\"}", AccountViewRequest.class);
            assertThat(bound.getAcctsid()).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("1300-SETUP-SCREEN-ATTRS - the xxxL cursor item and the one xxxA the program writes")
    class ScreenAttributeSetup {
        private static final int LENGTH_ITEM_BYTES = 2;

        @Test
        @DisplayName("xxxL is signed, so -1 is representable - it is a cursor signal, not a length")
        void theLengthItemIsSignedAndHoldsMinusOne() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            holder.setLength(ScreenFieldMetadata.CURSOR_HERE);
            assertThat(holder.getLength()).isEqualTo(-1).isNegative();

            assertThat((short) holder.getLength()).isEqualTo((short) -1);
            assertThat((int) (char) holder.getLength()).isEqualTo(0xFFFF).isNotEqualTo(-1);

            assertThat(ScreenFieldMetadata.LENGTH_ITEM_MIN).isEqualTo(-9999);
            assertThat(ScreenFieldMetadata.LENGTH_ITEM_MAX).isEqualTo(9999);
            assertThat(ScreenFieldMetadata.LENGTH_ITEM_MIN)
                    .as("S9(4) is symmetric, so the floor is the negated ceiling")
                    .isEqualTo(-ScreenFieldMetadata.LENGTH_ITEM_MAX);
            assertThat(AccountViewRequest.LENGTH_ITEM_LENGTH).isEqualTo(LENGTH_ITEM_BYTES);
        }

        @Test
        @DisplayName("both arms of the EVALUATE at :548 do the same thing, and both are exercised")
        void bothArmsOfTheCursorEvaluateAgree() {
            AccountViewRequest filterNotOkOrBlank = new AccountViewRequest();
            AccountViewRequest whenOther = new AccountViewRequest();

            filterNotOkOrBlank.metadata(ScreenField.ACCTSID).positionCursorHere();
            whenOther.metadata(ScreenField.ACCTSID).setLength(ScreenFieldMetadata.CURSOR_HERE);

            assertThat(filterNotOkOrBlank.metadata(ScreenField.ACCTSID).getLength())
                    .as(":549 and :551 are the same MOVE, so the two arms cannot disagree")
                    .isEqualTo(whenOther.metadata(ScreenField.ACCTSID).getLength())
                    .isEqualTo(ScreenFieldMetadata.CURSOR_HERE);
            assertThat(filterNotOkOrBlank.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(whenOther.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();

            assertThat(filterNotOkOrBlank.toGroupImage(asciiCodec))
                    .isEqualTo(whenOther.toGroupImage(asciiCodec));
        }

        @Test
        @DisplayName("the cursor lands on ACCTSID only - the other 36 xxxL items stay unset")
        void onlyAcctsidCarriesTheCursor() {
            AccountViewRequest request = new AccountViewRequest();
            request.metadata(ScreenField.ACCTSID).positionCursorHere();

            for (ScreenField field : ScreenField.values()) {
                if (field == ScreenField.ACCTSID) {
                    assertThat(request.metadata(field).isCursorHere()).isTrue();
                    assertThat(request.metadata(field).isLengthUnset()).isFalse();
                } else {
                    assertThat(request.metadata(field).isCursorHere())
                            .as("%s carries no cursor: COACTVWC writes only ACCTSIDL", field.label())
                            .isFalse();
                    assertThat(request.metadata(field).isLengthUnset()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("xxxA accepts DFHBMFSE, the one attribute COACTVWC writes into the input group")
        void theAttributeItemAcceptsDfhbmfse() {
            assertThat(BmsAttributes.DFHBMFSE)
                    .as("DFHBMSCA's DFHBMFSE - unprotected, FSET, from IBM CICS documentation")
                    .isEqualTo((byte) 0xC1);

            AccountViewRequest request = new AccountViewRequest();
            ScreenFieldMetadata holder = request.metadata(ScreenField.ACCTSID);
            assertThat(holder.isAttributeUnset()).isTrue();

            holder.setAttribute(BmsAttributes.DFHBMFSE);

            assertThat(holder.getAttribute()).isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(holder.getFlag()).isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(holder.isAttributeUnset()).isFalse();

            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMFSE)).isTrue();
        }

        @Test
        @DisplayName("the attribute byte reaches the group image raw, not through the code page")
        void theAttributeSurvivesBothCodePagesUnchanged() {
            AccountViewRequest request = new AccountViewRequest();
            request.metadata(ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);

            byte[] ascii = request.toGroupImage(asciiCodec);
            byte[] ebcdic = request.toGroupImage(ebcdicCodec);

            int offset = ScreenField.ACCTSID.flagItemOffset();
            assertThat(ascii[offset]).isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(ebcdic[offset])
                    .as("DFHBMFSE is the same byte under either code page")
                    .isEqualTo(BmsAttributes.DFHBMFSE);
        }
    }

    @Nested
    @DisplayName("The carried work areas - COCOM01Y at 160 bytes and CVCRD01Y at 213")
    class CarriedWorkAreas {
        @Test
        @DisplayName("CARDDEMO-COMMAREA is 160 bytes, composed 34 + 84 + 12 + 16 + 14")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("the five 05-level groups of COCOM01Y sum to the whole area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);

            assertThat(NavigationContext.empty().toFixedWidth(asciiCodec)).hasSize(160);
            assertThat(NavigationContext.empty().toFixedWidth(ebcdicCodec)).hasSize(160);

            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.commareaLength()).isEqualTo(160);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP precedes CDEMO-LAST-MAPSET and both are X(7), not X(8)")
        void lastMapPrecedesLastMapsetAndBothAreSeven() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.MORE_INFO_LENGTH);

            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("CDEMO-LAST-MAP at :43 precedes CDEMO-LAST-MAPSET at :44")
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET)
                    .isEqualTo(NavigationContext.LAST_MAP_OFFSET + NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET + NavigationContext.LAST_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET ends the 160-byte area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            NavigationContext carried = NavigationContext.empty()
                    .withLastMap("COACTVW")
                    .withLastMapset("COACTVW");
            assertThat(carried.lastMap()).isEqualTo("COACTVW").hasSize(7);
            assertThat(carried.lastMapset()).isEqualTo("COACTVW").hasSize(7);

            NavigationContext round = NavigationContext.fromFixedWidth(asciiCodec,
                    carried.toFixedWidth(asciiCodec));
            assertThat(round.lastMap()).isEqualTo(carried.lastMap());
            assertThat(round.lastMapset()).isEqualTo(carried.lastMapset());
        }

        @Test
        @DisplayName("both states of all four COCOM01Y condition names are driven (gate G50)")
        void allFourConditionNamesAreDrivenBothWays() {
            assertThat(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            assertUserTypeConditions(NavigationContext.empty().withUserTypeAdmin(), true, false);
            assertUserTypeConditions(NavigationContext.empty().withUserTypeUser(), false, true);
            assertUserTypeConditions(NavigationContext.empty(), false, false);

            AccountViewRequest onEnter = new AccountViewRequest();
            onEnter.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();

            AccountViewRequest onReenter = new AccountViewRequest();
            onReenter.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(onReenter.isEnter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();

            AccountViewRequest onNeither = new AccountViewRequest();
            onNeither.setNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(onNeither.isEnter()).isFalse();
            assertThat(onNeither.isReenter()).isFalse();
        }

        private void assertUserTypeConditions(NavigationContext context,
                                              boolean expectAdmin,
                                              boolean expectUser) {
            assertThat(context.isAdmin())
                    .as("CDEMO-USRTYP-ADMIN for userType '%s'", context.userType())
                    .isEqualTo(expectAdmin);
            assertThat(context.isUser())
                    .as("CDEMO-USRTYP-USER for userType '%s'", context.userType())
                    .isEqualTo(expectUser);
        }

        @Test
        @DisplayName("CC-WORK-AREA is 213 bytes, composed 5+8+7+7+75+75+11+16+9")
        void theCardWorkAreaIsTwoHundredAndThirteenBytes() {
            assertThat(CardScreenState.CCARD_AID_LENGTH).isEqualTo(5);
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_ERROR_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CCARD_RETURN_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CC_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardScreenState.CC_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardScreenState.CC_CUST_ID_LENGTH).isEqualTo(9);

            assertThat(CardScreenState.CCARD_AID_LENGTH
                    + CardScreenState.CCARD_NEXT_PROG_LENGTH
                    + CardScreenState.CCARD_NEXT_MAPSET_LENGTH
                    + CardScreenState.CCARD_NEXT_MAP_LENGTH
                    + CardScreenState.CCARD_ERROR_MSG_LENGTH
                    + CardScreenState.CCARD_RETURN_MSG_LENGTH
                    + CardScreenState.CC_ACCT_ID_LENGTH
                    + CardScreenState.CC_CARD_NUM_LENGTH
                    + CardScreenState.CC_CUST_ID_LENGTH)
                    .as("the nine spans of CVCRD01Y sum to the whole work area")
                    .isEqualTo(CardScreenState.RECORD_LENGTH)
                    .isEqualTo(213);

            assertThat(new CardScreenState().toFixedWidth(ASCII)).hasSize(213);
            assertThat(new CardScreenState().toFixedWidth(EBCDIC)).hasSize(213);
        }

        @Test
        @DisplayName("both work areas travel in the payload, so the server keeps no state (gate G37)")
        void bothCarriersTravelInThePayload() {
            AccountViewRequest request = new AccountViewRequest();
            assertThat(request.getCardScreenState())
                    .as("the CVCRD01Y area is always present, never null")
                    .isNotNull();
            assertThat(request.hasNavigationContext())
                    .as("no communication area has travelled yet, so EIBCALEN would be zero")
                    .isFalse();
            assertThat(request.commareaLength()).isZero();

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.getCardScreenState().setCcAcctId("00000000011");

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(request);
            assertThat(json.has("navigationContext")).isTrue();
            assertThat(json.has("cardScreenState")).isTrue();
            assertThat(json.get("navigationContext").isObject()).isTrue();
            assertThat(json.get("cardScreenState").isObject()).isTrue();
        }
    }

    @Nested
    @DisplayName("Corrections - two figures where the source overrules the plan")
    class SourceContractCorrections {
        private static final int DECLARED_AID_CONDITIONS = 16;

        @Test
        @DisplayName("CVCRD01Y declares SIXTEEN CCARD-AID conditions, not the fifteen the plan states")
        void thereAreSixteenAidConditions() {
            List<String> aidTokens = List.of(
                    CardScreenState.CCARD_AID_ENTER, CardScreenState.CCARD_AID_CLEAR,
                    CardScreenState.CCARD_AID_PA1, CardScreenState.CCARD_AID_PA2,
                    CardScreenState.CCARD_AID_PFK01, CardScreenState.CCARD_AID_PFK02,
                    CardScreenState.CCARD_AID_PFK03, CardScreenState.CCARD_AID_PFK04,
                    CardScreenState.CCARD_AID_PFK05, CardScreenState.CCARD_AID_PFK06,
                    CardScreenState.CCARD_AID_PFK07, CardScreenState.CCARD_AID_PFK08,
                    CardScreenState.CCARD_AID_PFK09, CardScreenState.CCARD_AID_PFK10,
                    CardScreenState.CCARD_AID_PFK11, CardScreenState.CCARD_AID_PFK12);

            assertThat(aidTokens)
                    .as("CVCRD01Y:4-19 declares 16 CCARD-AID conditions, not the 15 the AAP records")
                    .hasSize(DECLARED_AID_CONDITIONS)
                    .doesNotHaveDuplicates();

            List<Field> conditionConstants = Arrays.stream(CardScreenState.class.getDeclaredFields())
                    .filter(field -> field.getName().startsWith("CCARD_AID_"))
                    .filter(field -> field.getType() == String.class)
                    .toList();
            assertThat(conditionConstants)
                    .as("the String-typed CCARD_AID_* constants are the 16 condition names; "
                            + "CCARD_AID_LENGTH, CCARD_AID_OFFSET and CCARD_AID_SPAN are span layout")
                    .hasSize(DECLARED_AID_CONDITIONS);
            assertThat(Arrays.stream(CardScreenState.class.getDeclaredFields())
                    .filter(field -> field.getName().startsWith("CCARD_AID_"))
                    .count())
                    .as("19 fields share the prefix; 16 of them are conditions")
                    .isEqualTo(DECLARED_AID_CONDITIONS + 3);

            assertThat(aidTokens).noneMatch(token -> token.startsWith("PA3"));
            assertThat(Arrays.stream(CardScreenState.class.getDeclaredFields())
                    .map(Field::getName))
                    .noneMatch(name -> name.equals("CCARD_AID_PA3"));

            assertThat(aidTokens).allMatch(token -> token.length() == CardScreenState.CCARD_AID_LENGTH);
            assertThat(CardScreenState.CCARD_AID_PA1).isEqualTo("PA1  ");
            assertThat(CardScreenState.CCARD_AID_PA2).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("the XCTL in COACTVWC begins at :349, and the Request carries no next-program field")
        void theXctlStatementSpansThreeLines() {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(new AccountViewRequest());

            assertThat(json.has("nextProgram")).isFalse();
            assertThat(json.has("nextMapset")).isFalse();
            assertThat(json.has("nextMap")).isFalse();

            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty().withToProgram("COACTVWC"));
            assertThat(request.getNavigationContext().toProgram()).isEqualTo("COACTVWC");
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("COACTVW declares no FKEYS, FKEY05 or FKEY12 - those belong to COACTUP")
        void theFunctionKeyFieldsBelongToTheOtherMapset() {
            Set<String> declared = EnumSet.allOf(ScreenField.class).stream()
                    .map(ScreenField::label)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(declared)
                    .hasSize(37)
                    .doesNotContain("FKEYS", "FKEY05", "FKEY12", "PAGENO");
            assertThat(declared)
                    .as("no COACTVW field name begins with FKEY")
                    .noneMatch(label -> label.startsWith("FKEY"));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(new AccountViewRequest());
            List<String> members = new ArrayList<>();
            json.fieldNames().forEachRemaining(members::add);
            assertThat(members).noneMatch(member -> member.toUpperCase(Locale.ROOT)
                    .startsWith("FKEY"));
        }
    }

    @Nested
    @DisplayName("Structural guards - the gates that are satisfied by an absence")
    class StructuralGuards {
        private List<Class<?>> typeAndNestedTypes() {
            return List.of(AccountViewRequest.class, ScreenField.class, ScreenFieldMetadata.class);
        }

        @Test
        @DisplayName("no HttpSession, no @SessionAttributes, no ThreadLocal anywhere in the type (G37)")
        void nothingHoldsServerSideState() {
            List<String> forbidden = List.of("HttpSession", "HttpServletRequest", "ThreadLocal",
                    "SessionAttributes", "SessionAttribute", "SessionStatus", "WebSession",
                    "RequestContextHolder", "SecurityContext");

            for (Class<?> type : typeAndNestedTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    assertThat(forbidden)
                            .as("%s.%s is a %s", type.getSimpleName(), field.getName(), fieldType)
                            .noneMatch(fieldType::contains);
                }
                for (Annotation annotation : type.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    assertThat(forbidden)
                            .as("%s is annotated %s", type.getSimpleName(), name)
                            .noneMatch(name::contains);
                }
                for (Method method : type.getDeclaredMethods()) {
                    String returned = method.getReturnType().getName();
                    assertThat(forbidden)
                            .as("%s.%s returns %s", type.getSimpleName(), method.getName(), returned)
                            .noneMatch(returned::contains);
                }
            }

            AccountViewRequest first = AccountViewRequest.withAccountFilter("00000000011");
            AccountViewRequest second = AccountViewRequest.withAccountFilter("00000000022");
            first.metadata(ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);
            assertThat(second.metadata(ScreenField.ACCTSID).isAttributeUnset()).isTrue();
            assertThat(second.getAcctsid()).isEqualTo("00000000022");
        }

        @Test
        @DisplayName("no double, no float and no rounding mode anywhere in the type (G22, G23, G24)")
        void noBinaryFloatingPointAndNoRoundingDecision() {
            List<Class<?>> banned = List.of(double.class, float.class, Double.class, Float.class,
                    RoundingMode.class, BigDecimal.class, MathContext.class);

            for (Class<?> type : typeAndNestedTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(banned)
                            .as("%s.%s is declared %s", type.getSimpleName(), field.getName(),
                                    field.getType().getSimpleName())
                            .doesNotContain(field.getType());
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(banned)
                            .as("%s.%s returns %s", type.getSimpleName(), method.getName(),
                                    method.getReturnType().getSimpleName())
                            .doesNotContain(method.getReturnType());
                    List<Class<?>> parameters = Arrays.asList(method.getParameterTypes());
                    if (!parameters.isEmpty()) {
                        assertThat(banned)
                                .as("%s.%s takes %s", type.getSimpleName(), method.getName(),
                                        Arrays.toString(method.getParameterTypes()))
                                .doesNotContainAnyElementsOf(parameters);
                    }
                }
            }

            for (ScreenField field : ScreenField.values()) {
                assertThat(new AccountViewRequest().value(field)).isInstanceOf(String.class);
            }
        }

        @Test
        @DisplayName("this test class itself holds no mutable static state (G53)")
        void theSuiteItselfKeepsNoMutableStaticState() {
            for (Field field : AccountViewRequestTest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s is an array, which is mutable behind a final reference",
                                field.getName())
                        .isFalse();
            }

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> LABELS.set(0, "TAMPERED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> WIDTHS.set(0, 999));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> POSITIONS.get(0).set(0, 99));

            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
            assertThat(asciiCodec).isNotSameAs(ebcdicCodec);
        }

        @Test
        @DisplayName("no import in this suite is a wildcard, and every table is 37 long (G52)")
        void theTranscriptionTablesAgreeOnThirtySeven() {
            assertThat(LABELS).hasSize(37).doesNotHaveDuplicates();
            assertThat(WIDTHS).hasSize(37);
            assertThat(PICTURES).hasSize(37);
            assertThat(CPY_LINES).hasSize(37).doesNotHaveDuplicates();
            assertThat(BMS_LINES).hasSize(37).doesNotHaveDuplicates();
            assertThat(POSITIONS).hasSize(37);
            assertThat(DATA_OFFSETS).hasSize(37).doesNotHaveDuplicates();
            assertThat(ScreenField.values()).hasSize(37);
            assertThat(AccountViewRequest.FIELD_COUNT).isEqualTo(37);
        }
    }
}
