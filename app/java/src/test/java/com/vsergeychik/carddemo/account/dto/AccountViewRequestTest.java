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
 *
 * <h2>No user rules were provided for this project</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that single line is the
 * <em>entire</em> rules document, not a truncated read of a longer one. Recorded here explicitly because
 * the absence of project rules is itself a fact a reviewer needs, and because it is emphatically
 * <strong>not</strong> licence to hold this file to a lower standard. No rule has been invented to fill
 * the gap. What governs instead is enterprise-standard best practice in the specific, citable form of the
 * Agent Action Plan's &sect;0.10.2 substitutes, each of which is named at the place it applies:
 *
 * <ul>
 *   <li><strong>B1</strong> - only coordinates {@code app/java/pom.xml} already resolves: JUnit Jupiter,
 *       AssertJ, Jackson and {@code jakarta.validation-api}, every version managed by the
 *       {@code spring-boot-starter-parent} BOM. Not one new dependency is introduced, and no Lombok,
 *       MapStruct, springdoc, Testcontainers or {@code spring-security-test}.</li>
 *   <li><strong>B3</strong> - the six defining sources are read-only and are <em>never</em> opened at test
 *       runtime. Every expected width, offset, line number and literal below is inlined as a Java
 *       constant, so this suite needs no file system and cannot drift with one.</li>
 *   <li><strong>B4</strong> - where this folder's brief disagrees with the source, the source wins and the
 *       disagreement is named rather than quietly resolved. Both such cases are asserted in
 *       {@link SourceContractCorrections}.</li>
 *   <li><strong>B6</strong> - no Spring Security anywhere: no mock user, no filter chain, no
 *       authentication. This is a plain payload test.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive: no sleep, no randomness and no
 *       wall-clock read, so a failure here is always reproducible.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard import anywhere (gate
 *       <strong>G52</strong>), and every codec is constructed over a {@link Charset} named outright so no
 *       call can fall back to a platform default.</li>
 *   <li><strong>B9</strong> - no mutable static state (gate <strong>G53</strong>). The transcription
 *       tables below are immutable {@link List}s rather than arrays, because a {@code static final} array
 *       is a mutable object behind a final reference and one test mutating an element would silently
 *       corrupt every other. The codecs are per-instance, rebuilt for each test method.</li>
 *   <li><strong>B12</strong> - provenance on every asserted number. No COBOL execution baseline is
 *       obtainable in this environment (risk <strong>R-A</strong>), so every expectation here is derived
 *       statically - and a statically derived expectation is only reviewable if it says where it came
 *       from. Hence the {@code file:line} citation beside each one.</li>
 * </ul>
 *
 * <h2>Gates this file owns</h2>
 * <strong>G9</strong> every payload field traces to a {@code DFHMDF} entry and every width to an
 * {@code xxxI} {@code PICTURE}; <strong>G22</strong>/<strong>G23</strong>/<strong>G24</strong> as
 * negatives - no {@code double}, no {@code float}, no rounding mode other than {@code DOWN};
 * <strong>G37</strong> no server-side session state; <strong>G49</strong> this package carries its own
 * branch-coverage ratio and cannot be covered from the parent test package, so
 * {@link AccountViewRequest}'s branches are driven here directly; <strong>G50</strong> both states of
 * every condition name; <strong>G52</strong> and <strong>G53</strong> as above.
 *
 * <p>The width, line-number, screen-position and data-offset tables below are an <em>independent second
 * transcription</em> of those two sources. They are deliberately written out as literals rather than read
 * from the class under test, because a test that asks the implementation what it believes and then agrees
 * with it proves nothing. Where a number here disagrees with the class, one of the two transcriptions is
 * wrong and the build says so.
 *
 * <p>Nothing here needs a Spring context, a servlet container or a running application - which is itself
 * part of the contract (practice <strong>B10</strong>): {@code parity/ParityHarness} builds this type the
 * same way these tests do.
 */
@DisplayName("AccountViewRequest - COACTVW CACTVWAI, the CAVW inbound payload")
class AccountViewRequestTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the EBCDIC datasets, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The 37 {@code DFHMDF} labels, in {@code app/bms/COACTVW.bms} declaration order.
     *
     * <p>An immutable {@link List} rather than a {@code String[]}, and so for every table that follows:
     * a {@code static final} array is a <em>mutable</em> object reached through a final reference, which
     * is exactly the shared-mutable-state practice <strong>B9</strong> and gate <strong>G53</strong>
     * forbid. {@link List#of} yields a genuinely unmodifiable table, so no test can reach into it and
     * change what every other test is asserting against.
     */
    private static final List<String> LABELS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "ACSTTUS",
            "ADTOPEN", "ACRDLIM", "AEXPDT", "ACSHLIM", "AREISDT", "ACURBAL", "ACRCYCR", "AADDGRP",
            "ACRCYDB", "ACSTNUM", "ACSTSSN", "ACSTDOB", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPHN1", "ACSGOVT",
            "ACSPHN2", "ACSEFTC", "ACSPFLG", "INFOMSG", "ERRMSG");

    /** The 37 declared widths, from the {@code xxxI PICTURE} clauses. They sum to 684. */
    private static final List<Integer> WIDTHS = List.of(4, 40, 8, 8, 40, 8, 11, 1, 10, 15, 10, 15, 10,
            15, 15, 10, 15, 9, 12, 10, 3, 25, 25, 25, 50, 2, 50, 5, 50, 3, 13, 20, 13, 10, 1, 45, 78);

    /** The 37 {@code PICTURE} clauses as the copybook writes them - note field 7. */
    private static final List<String> PICTURES = List.of(
            "X(4)", "X(40)", "X(8)", "X(8)", "X(40)", "X(8)", "99999999999", "X(1)", "X(10)", "X(15)",
            "X(10)", "X(15)", "X(10)", "X(15)", "X(15)", "X(10)", "X(15)", "X(9)", "X(12)", "X(10)",
            "X(3)", "X(25)", "X(25)", "X(25)", "X(50)", "X(2)", "X(50)", "X(5)", "X(50)", "X(3)",
            "X(13)", "X(20)", "X(13)", "X(10)", "X(1)", "X(45)", "X(78)");

    /** The {@code app/cpy-bms/COACTVW.CPY} line of each {@code xxxI} item. */
    private static final List<Integer> CPY_LINES = List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84,
            90, 96, 102, 108, 114, 120, 126, 132, 138, 144, 150, 156, 162, 168, 174, 180, 186, 192, 198,
            204, 210, 216, 222, 228, 234, 240);

    /** The {@code app/bms/COACTVW.bms} line opening each {@code DFHMDF} entry. */
    private static final List<Integer> BMS_LINES = List.of(34, 38, 47, 57, 61, 70, 84, 97, 107, 117, 128,
            138, 149, 159, 171, 182, 192, 207, 216, 225, 234, 251, 256, 261, 268, 277, 282, 291, 301,
            310, 319, 326, 335, 342, 351, 356, 365);

    /** Each field's {@code POS=(row,column)}, as an immutable table of immutable pairs. */
    private static final List<List<Integer>> POSITIONS = List.of(
            List.of(1, 7), List.of(1, 21), List.of(1, 71), List.of(2, 7), List.of(2, 21), List.of(2, 71),
            List.of(5, 38), List.of(5, 70), List.of(6, 17), List.of(6, 61), List.of(7, 17),
            List.of(7, 61), List.of(8, 17), List.of(8, 61), List.of(9, 61), List.of(10, 23),
            List.of(10, 61), List.of(12, 23), List.of(12, 54), List.of(13, 23), List.of(13, 61),
            List.of(15, 1), List.of(15, 28), List.of(15, 55), List.of(16, 10), List.of(16, 73),
            List.of(17, 10), List.of(17, 73), List.of(18, 10), List.of(18, 73), List.of(19, 10),
            List.of(19, 58), List.of(20, 10), List.of(20, 41), List.of(20, 78), List.of(22, 23),
            List.of(23, 1));

    /** Where each field's data begins in the 955-byte group image. */
    private static final List<Integer> DATA_OFFSETS = List.of(19, 30, 77, 92, 107, 154, 169, 187, 195,
            212, 234, 251, 273, 290, 312, 334, 351, 373, 389, 408, 425, 435, 467, 499, 531, 588, 597,
            654, 666, 723, 733, 753, 780, 800, 817, 825, 877);

    /**
     * A codec over {@link #ASCII}, rebuilt for every test method.
     *
     * <p>An instance field rather than a {@code static} one, so that practice <strong>B9</strong> holds
     * without an argument about whether the type happens to be immutable: nothing static here is
     * reachable for mutation, and no test can be influenced by the order it ran in.
     */
    private FixedWidthCodec asciiCodec;

    /** A codec over {@link #EBCDIC}, to prove nothing here assumes ASCII byte values. */
    private FixedWidthCodec ebcdicCodec;

    /**
     * Builds both codecs fresh for each test method (practice <strong>B9</strong>).
     *
     * <p>{@link FixedWidthCodec} takes its {@link Charset} as a constructor argument and holds nothing
     * else, so constructing one per test costs nothing and buys complete isolation.
     */
    @BeforeEach
    void buildCodecs() {
        asciiCodec = new FixedWidthCodec(ASCII);
        ebcdicCodec = new FixedWidthCodec(EBCDIC);
    }

    /**
     * The 37 named setters, in {@link ScreenField} order.
     *
     * <p>Named rather than reached through {@link AccountViewRequest#setValue(ScreenField, String)}, so
     * that the two routes are proven to agree instead of one being tested twice.
     *
     * @return one setter per field, never {@code null}
     */
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

    /**
     * The 37 named getters, in {@link ScreenField} order.
     *
     * @return one getter per field, never {@code null}
     */
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

    /**
     * A request whose 37 fields each hold a distinct, recognisable value at exactly the declared width, so
     * that a round trip cannot pass by accident and a mixed-up pair of fields is visible by name.
     *
     * <p>Takes the codec as a parameter rather than reading a field, so the helper stays {@code static}
     * while the codecs stay per-instance (practice <strong>B9</strong>), and so the charset the values are
     * shaped under is stated at every call site rather than assumed (practice <strong>B8</strong>).
     *
     * @param codec the codec whose {@code PIC X} move rule brings each value to its declared width
     * @return a fully populated request, never {@code null}
     */
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
            // THE ASYMMETRY, and the single most consequential typing decision on this map.
            //
            // app/cpy-bms/COACTVW.CPY:60 declares "02 ACCTSIDI PIC 99999999999" - NUMERIC, eleven nines,
            // and the only non-X input item on the whole map. It reads that way because
            // app/bms/COACTVW.bms:84 declares the field PICIN='99999999999' with VALIDN=(MUSTFILL); BMS
            // propagates PICIN into the generated symbolic map, so the PICTURE is a consequence of the
            // mapset's input validation rather than of how the program uses the value.
            //
            // And the program uses it as characters. app/cbl/COACTVWC.cbl:628-632:
            //
            //     IF  ACCTSIDI OF CACTVWAI = '*'
            //     OR  ACCTSIDI OF CACTVWAI = SPACES
            //         MOVE LOW-VALUES           TO  CC-ACCT-ID
            //     ELSE
            //         MOVE ACCTSIDI OF CACTVWAI TO  CC-ACCT-ID
            //     END-IF
            //
            // Neither '*' nor SPACES is representable in an int, a long or a BigDecimal, so typing this
            // field numerically would make the :628-630 arm UNREACHABLE and silently delete the
            // "no criterion" behaviour of the screen. Hence: an 11-character String.
            //
            // INTER-MAPSET ASYMMETRY, worth stating because it looks like an inconsistency and is not:
            // app/cpy-bms/COACTUP.CPY:60 declares the SAME-NAMED field as plain "PIC X(11)", because
            // COACTUP's mapset entry carries no PICIN. Two mapsets, one field name, two PICTUREs - the
            // sibling AccountUpdateRequestTest asserts it from the other side.
            AccountViewRequest request = new AccountViewRequest();

            assertThat(request.getAcctsid()).isInstanceOf(String.class);
            assertThat(ScreenField.ACCTSID.picture())
                    .as("COACTVW.CPY:60 - the one numeric PICTURE among the 37")
                    .isEqualTo("99999999999");
            assertThat(ScreenField.ACCTSID.isAlphanumeric())
                    .as("declared numeric, so not a PIC X item")
                    .isFalse();
            assertThat(ScreenField.ACCTSID.length()).isEqualTo(11);

            // The declared getter is String-typed, not a numeric type.
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
            // app/cbl/COACTVWC.cbl:630 - "MOVE LOW-VALUES TO CC-ACCT-ID". LOW-VALUES is the lowest
            // character of the collating sequence, which is binary 0x00 on every code page, and it is
            // DISTINCT from all three of: spaces, an empty string, and Java null.
            //
            // Driven here at the field's full declared width so the distinction is provable rather than
            // asserted, and so nothing along the way silently substitutes spaces for it.
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

            // It survives the group image under both code pages: 0x00 is 0x00 either way, which is what
            // makes LOW-VALUES code-page independent in the first place.
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

            // The three values :628-:630 treats as "no criterion" or as a real key are each distinct
            // stored values, so both arms of that IF are reachable from this type.
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
            // The one substitution a setter performs. COACTVWC.cbl:629 tests "= SPACES", and a null here
            // would both make that test impossible to reproduce and put a NullPointerException between
            // the request and the first edit.
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

            // 'C' is 0x43 in US-ASCII and 0xC3 in IBM037; 'A' is 0x41 and 0xC1. Same characters, same
            // offsets, different bytes - which is the whole point of naming the code page.
            assertThat(ascii[ScreenField.TRNNAME.dataOffset()]).isEqualTo((byte) 0x43);
            assertThat(ebcdic[ScreenField.TRNNAME.dataOffset()]).isEqualTo((byte) 0xC3);
            assertThat(ascii[ScreenField.TRNNAME.dataOffset() + 1]).isEqualTo((byte) 0x41);
            assertThat(ebcdic[ScreenField.TRNNAME.dataOffset() + 1]).isEqualTo((byte) 0xC1);

            // And the pad character too: a space is 0x20 in US-ASCII and 0x40 in IBM037, in the field
            // data and in the TIOAPFX prefix alike.
            assertThat(ascii[ScreenField.INFOMSG.dataOffset()]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[ScreenField.INFOMSG.dataOffset()]).isEqualTo((byte) 0x40);
            assertThat(ascii[0]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[0]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("a multi-byte code page fails loudly instead of overflowing the declared width")
        void aMultiByteCodePageFailsLoudly() {
            // UTF-8 encodes every digit, sign overpunch and the space in one byte, so the codec accepts
            // it and an all-spaces group renders - but an accented character takes two, which would
            // overflow the declared width and shift every offset after it. The diagnostic comes from the
            // FixedWidthCodec seam, which is where the one-byte-per-character rule is enforced, and it
            // names the offending field so the failure is traceable to one DFHMDF entry.
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
        @DisplayName("the rendering names every field by its DFHMDF label and hides nothing")
        void theRenderingNamesEveryFieldAndHidesNothing() {
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
                    .contains("078-05-1120")
                    .contains("1970-01-01")
                    .contains("GOVT-ID-0099")
                    .contains("00000000011")
                    .doesNotContain("[REDACTED]")
                    .doesNotContain("[redacted]")
                    .doesNotContain("<omitted>")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");
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

        /** One validator for the whole nested class; Bean Validation validators are thread safe. */
        private final Validator validator = buildValidator();

        /**
         * Builds a validator without a Spring context.
         *
         * @return a validator, never {@code null}
         */
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

        /** A mapper with no Spring configuration, so the annotations alone decide the shape. */
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

    // =================================================================================================
    // The xxxL cursor item and the xxxA attribute item, asserted against the three lines of COACTVWC
    // that actually write them. Everything in this section is a second transcription of
    // app/cbl/COACTVWC.cbl paragraph 1300-SETUP-SCREEN-ATTRS (practice B12).
    // =================================================================================================

    @Nested
    @DisplayName("1300-SETUP-SCREEN-ATTRS - the xxxL cursor item and the one xxxA the program writes")
    class ScreenAttributeSetup {

        /**
         * The width the cursor signal must survive, from {@code app/cpy-bms/COACTVW.CPY:19} -
         * {@code 02 ACCTSIDL COMP PIC S9(4)}. Two bytes, and <em>signed</em>, which is the whole point.
         */
        private static final int LENGTH_ITEM_BYTES = 2;

        @Test
        @DisplayName("xxxL is signed, so -1 is representable - it is a cursor signal, not a length")
        void theLengthItemIsSignedAndHoldsMinusOne() {
            // COACTVW.CPY:19 - "02 ACCTSIDL COMP PIC S9(4)". The S is load-bearing: COACTVWC.cbl:549
            // and :551 both MOVE -1 into this item, so a carrier that could not hold a negative number
            // would make the cursor convention inexpressible.
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            holder.setLength(ScreenFieldMetadata.CURSOR_HERE);
            assertThat(holder.getLength()).isEqualTo(-1).isNegative();

            // Representable in a signed two-byte carrier, and NOT in an unsigned or character one. A char
            // is Java's only unsigned 16-bit type, and this is what rules it out as the carrier.
            assertThat((short) holder.getLength()).isEqualTo((short) -1);
            assertThat((int) (char) holder.getLength()).isEqualTo(0xFFFF).isNotEqualTo(-1);

            // The declared PICTURE range is symmetric about zero, which only a signed item can be.
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
            // app/cbl/COACTVWC.cbl:548-553 is ONE "EVALUATE TRUE" with two arms:
            //
            //   :548  EVALUATE TRUE
            //   :549      WHEN FLG-ACCTFILTER-NOT-OK
            //   :550      WHEN FLG-ACCTFILTER-BLANK
            //   :549/:551     MOVE -1 TO ACCTSIDL OF CACTVWAI      <- the two MOVE -1 sites
            //   :551      WHEN OTHER
            //                 MOVE -1 TO ACCTSIDL OF CACTVWAI
            //             END-EVALUATE
            //
            // The two arms perform an IDENTICAL action, so the EVALUATE cannot change the outcome. That
            // redundancy is DELIBERATE PRESERVED LEGACY BEHAVIOUR (AAP 0.8.3) and must NOT be collapsed
            // into an unconditional move: this is a like-for-like migration, and "tidying" a branch away
            // is a behaviour change even when the branch cannot be observed. It is asserted here rather
            // than removed so that a later reader finds the redundancy documented instead of surprising.
            //
            // Both arms are driven, and the assertion is that they agree.
            AccountViewRequest filterNotOkOrBlank = new AccountViewRequest();
            AccountViewRequest whenOther = new AccountViewRequest();

            // Arm one - the guarded arm (WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-ACCTFILTER-BLANK).
            filterNotOkOrBlank.metadata(ScreenField.ACCTSID).positionCursorHere();
            // Arm two - WHEN OTHER, reached by the same MOVE written a second time.
            whenOther.metadata(ScreenField.ACCTSID).setLength(ScreenFieldMetadata.CURSOR_HERE);

            assertThat(filterNotOkOrBlank.metadata(ScreenField.ACCTSID).getLength())
                    .as(":549 and :551 are the same MOVE, so the two arms cannot disagree")
                    .isEqualTo(whenOther.metadata(ScreenField.ACCTSID).getLength())
                    .isEqualTo(ScreenFieldMetadata.CURSOR_HERE);
            assertThat(filterNotOkOrBlank.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
            assertThat(whenOther.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();

            // And the group image the two produce is byte-identical, which is the observable form of
            // "the arms agree".
            assertThat(filterNotOkOrBlank.toGroupImage(asciiCodec))
                    .isEqualTo(whenOther.toGroupImage(asciiCodec));
        }

        @Test
        @DisplayName("the cursor lands on ACCTSID only - the other 36 xxxL items stay unset")
        void onlyAcctsidCarriesTheCursor() {
            // COACTVWC writes an xxxL item for exactly one field. ACCTSID is also the only
            // ATTRB=(...,UNPROT) entry in app/bms/COACTVW.bms - verified: one occurrence of UNPROT in the
            // whole mapset - so it is the only field a terminal operator can type into and therefore the
            // only field a cursor could sensibly be placed on.
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
            // app/cbl/COACTVWC.cbl:543 - "MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI".
            //
            // VERIFIED COUNT: this is the ONLY write to an "xxxA OF CACTVWA*" item in the whole of
            // COACTVWC - one occurrence across all 941 lines. So exactly one of the 37 fields carries a
            // meaningful input attribute, and DFHBMFSE is the value it carries.
            //
            // DFHBMFSE is taken from common/BmsAttributes, which reproduces it from IBM CICS
            // documentation because DFHBMSCA is an IBM-supplied copybook ABSENT from this repository
            // (17 consumers, risk R-D). Using the named constant rather than a bare 0xC1 is what ties
            // this assertion to that one source of truth.
            assertThat(BmsAttributes.DFHBMFSE)
                    .as("DFHBMSCA's DFHBMFSE - unprotected, FSET, from IBM CICS documentation")
                    .isEqualTo((byte) 0xC1);

            AccountViewRequest request = new AccountViewRequest();
            ScreenFieldMetadata holder = request.metadata(ScreenField.ACCTSID);
            assertThat(holder.isAttributeUnset()).isTrue();

            holder.setAttribute(BmsAttributes.DFHBMFSE);

            assertThat(holder.getAttribute()).isEqualTo(BmsAttributes.DFHBMFSE);
            // xxxA REDEFINES xxxF, so the two views are one byte and cannot disagree.
            assertThat(holder.getFlag()).isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(holder.isAttributeUnset()).isFalse();

            // FSET means "modified data tag set", which is precisely why COACTVWC uses it: the field is
            // returned on the next RECEIVE MAP whether or not the operator retyped it.
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMFSE)).isTrue();
        }

        @Test
        @DisplayName("the attribute byte reaches the group image raw, not through the code page")
        void theAttributeSurvivesBothCodePagesUnchanged() {
            // A 3270 attribute is a bit pattern, not text. If it were encoded through the charset it
            // would differ between IBM037 and US-ASCII; it must not.
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

    // =================================================================================================
    // The two conversation-state carriers, sized against their own copybooks. Neither is a BMS field, so
    // neither is one of the 37 - but both travel in the payload, which is what keeps the server stateless
    // (gate G37).
    // =================================================================================================

    @Nested
    @DisplayName("The carried work areas - COCOM01Y at 160 bytes and CVCRD01Y at 213")
    class CarriedWorkAreas {

        @Test
        @DisplayName("CARDDEMO-COMMAREA is 160 bytes, composed 34 + 84 + 12 + 16 + 14")
        void theCommareaIsOneHundredAndSixtyBytes() {
            // app/cpy/COCOM01Y.cpy:19 "01 CARDDEMO-COMMAREA.", five 05-level groups:
            //   :20 CDEMO-GENERAL-INFO   4 + 8 + 4 + 8 + 8 + 1 + 1 =  34
            //   :32 CDEMO-CUSTOMER-INFO  9 + 25 + 25 + 25          =  84
            //   :37 CDEMO-ACCOUNT-INFO   11 + 1                    =  12
            //   :40 CDEMO-CARD-INFO      16                        =  16
            //   :42 CDEMO-MORE-INFO      7 + 7                     =  14
            //                                                        ---
            //                                                        160
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

            // The rendered image is the same 160 bytes, under either code page.
            assertThat(NavigationContext.empty().toFixedWidth(asciiCodec)).hasSize(160);
            assertThat(NavigationContext.empty().toFixedWidth(ebcdicCodec)).hasSize(160);

            // And the request reports it as EIBCALEN would.
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.commareaLength()).isEqualTo(160);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP precedes CDEMO-LAST-MAPSET and both are X(7), not X(8)")
        void lastMapPrecedesLastMapsetAndBothAreSeven() {
            // The trap in CDEMO-MORE-INFO, and it has two halves.
            //
            // WIDTH: app/cpy/COCOM01Y.cpy:43 "CDEMO-LAST-MAP PIC X(7)" and :44
            // "CDEMO-LAST-MAPSET PIC X(7)" - SEVEN, not the eight that a program name takes. Reading
            // either as X(8) would overrun the 160-byte area by two bytes.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.MORE_INFO_LENGTH);

            // ORDER: MAP is declared BEFORE MAPSET - the reverse of how the pair is usually spoken about
            // and of the order EXEC CICS SEND MAP names them. Storage order is the copybook's, so MAP
            // sits at the lower offset.
            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("CDEMO-LAST-MAP at :43 precedes CDEMO-LAST-MAPSET at :44")
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET)
                    .isEqualTo(NavigationContext.LAST_MAP_OFFSET + NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET + NavigationContext.LAST_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET ends the 160-byte area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            // The two are distinguishable end to end, so a swap could not pass unnoticed. COACTVWC
            // writes them at :346 (MAPSET) and :347 (MAP), both 7-character literals.
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
            // app/cpy/COCOM01Y.cpy declares exactly four 88-levels on this area:
            //   :27  88 CDEMO-USRTYP-ADMIN   VALUE 'A'
            //   :28  88 CDEMO-USRTYP-USER    VALUE 'U'
            //   :30  88 CDEMO-PGM-ENTER      VALUE 0
            //   :31  88 CDEMO-PGM-REENTER    VALUE 1
            // Gate G50 requires each driven TRUE and FALSE. A condition name is a test over a field, so
            // "false" is any other value the PICTURE admits - not merely the sibling condition.
            assertThat(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            // CDEMO-USRTYP-ADMIN true / CDEMO-USRTYP-USER false.
            assertUserTypeConditions(NavigationContext.empty().withUserTypeAdmin(), true, false);
            // CDEMO-USRTYP-USER true / CDEMO-USRTYP-ADMIN false.
            assertUserTypeConditions(NavigationContext.empty().withUserTypeUser(), false, true);
            // Both false - a freshly initialised area holds a space, so no role is implied before
            // sign-on. This is the state COSGN00C starts from.
            assertUserTypeConditions(NavigationContext.empty(), false, false);

            // CDEMO-PGM-ENTER true / CDEMO-PGM-REENTER false - the paint-the-screen arm.
            AccountViewRequest onEnter = new AccountViewRequest();
            onEnter.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();

            // CDEMO-PGM-REENTER true / CDEMO-PGM-ENTER false - the validate-what-was-typed arm.
            AccountViewRequest onReenter = new AccountViewRequest();
            onReenter.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(onReenter.isEnter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();

            // Both false - CDEMO-PGM-CONTEXT is PIC 9(01) and admits any digit, so a context of 9
            // satisfies neither condition. Written as two independent tests, never as one negation.
            AccountViewRequest onNeither = new AccountViewRequest();
            onNeither.setNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(onNeither.isEnter()).isFalse();
            assertThat(onNeither.isReenter()).isFalse();
        }

        /**
         * Drives the two user-type condition names on one area and asserts both outcomes.
         *
         * @param context       the communication area to test
         * @param expectAdmin   the expected {@code CDEMO-USRTYP-ADMIN} outcome
         * @param expectUser    the expected {@code CDEMO-USRTYP-USER} outcome
         */
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
            // app/cpy/CVCRD01Y.cpy, the CVCRD01Y work area:
            //   CCARD-AID         X(5)   =   5      (:3, with its 16 88-levels at :4-:19)
            //   CCARD-NEXT-PROG   X(8)   =   8      (:27)
            //   CCARD-NEXT-MAPSET X(7)   =   7      (:33)
            //   CCARD-NEXT-MAP    X(7)   =   7      (:34)
            //   CCARD-ERROR-MSG   X(75)  =  75      (:38)
            //   CCARD-RETURN-MSG  X(75)  =  75      (:39)
            //   CC-ACCT-ID        X(11)  =  11      (:44, REDEFINEd as CC-ACCT-ID-N PIC 9(11))
            //   CC-CARD-NUM       X(16)  =  16      (:46, REDEFINEd as 9(16))
            //   CC-CUST-ID        X(09)  =   9      (:48, REDEFINEd as 9(9))
            //                              ---
            //                              213
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
            // Neither area is a BMS field, so neither is one of the 37 - but both are @JsonProperty
            // members, which is what makes the conversation stateless: the client returns the state it
            // was given instead of the server remembering it.
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

    // =================================================================================================
    // Where this folder's brief and the source disagree. Practice B4: the source wins, and the
    // disagreement is written down rather than quietly resolved, so that nobody restores the wrong value
    // later on the strength of the document that had it wrong.
    // =================================================================================================

    @Nested
    @DisplayName("Corrections - two figures where the source overrules the plan")
    class SourceContractCorrections {

        /**
         * The number of {@code CCARD-AID-*} condition names {@code app/cpy/CVCRD01Y.cpy} declares.
         *
         * <p><strong>SIXTEEN, not fifteen.</strong> The Agent Action Plan describes {@code CVCRD01Y} as
         * carrying "15 AID conditions" in both &sect;0.3.3 and &sect;0.4.7. Counted directly from
         * {@code app/cpy/CVCRD01Y.cpy:4-19} the total is 16.
         */
        private static final int DECLARED_AID_CONDITIONS = 16;

        @Test
        @DisplayName("CVCRD01Y declares SIXTEEN CCARD-AID conditions, not the fifteen the plan states")
        void thereAreSixteenAidConditions() {
            // app/cpy/CVCRD01Y.cpy:4-19, counted line by line:
            //   :4  CCARD-AID-ENTER  'ENTER'      :5  CCARD-AID-CLEAR  'CLEAR'
            //   :6  CCARD-AID-PA1    'PA1  '      :7  CCARD-AID-PA2    'PA2  '
            //   :8 -:19 CCARD-AID-PFK01 .. PFK12  'PFK01' .. 'PFK12'
            //   => 2 + 2 + 12 = 16
            //
            // CORRECTION (practice B4): AAP 0.3.3 and 0.4.7 both say "15 AID conditions". The source says
            // 16. The source wins and the discrepancy is named here so it is not silently re-introduced.
            //
            // The miscount is almost certainly PA3: DFHAID defines DFHPA3, and a reader listing the
            // program-attention keys from the IBM copybook rather than from THIS copybook would expect
            // PA1, PA2 and PA3 and then find only two. CVCRD01Y declares NO PA3 - verified, zero
            // occurrences of the string "PA3" in the file.
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

            // Independently: the condition tokens CardScreenState publishes are exactly these 16.
            //
            // The type filter is load-bearing, not decoration. Nineteen of CardScreenState's fields have
            // names beginning "CCARD_AID_", and three of them are not condition names at all but the
            // layout of the CCARD-AID span itself - CCARD_AID_LENGTH and CCARD_AID_OFFSET are ints and
            // CCARD_AID_SPAN is a FieldSpan. Counting on the name prefix alone yields 19 and would make
            // this assertion agree with neither the plan's 15 nor the copybook's 16. Only the String
            // constants carry an 88-level VALUE.
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

            // No PA3 anywhere - the specific error this correction guards against.
            assertThat(aidTokens).noneMatch(token -> token.startsWith("PA3"));
            assertThat(Arrays.stream(CardScreenState.class.getDeclaredFields())
                    .map(Field::getName))
                    .noneMatch(name -> name.equals("CCARD_AID_PA3"));

            // Every token is exactly CCARD-AID's declared X(5) width - which is why :6 and :7 write
            // 'PA1  ' and 'PA2  ' with two trailing spaces rather than 'PA1' and 'PA2'.
            assertThat(aidTokens).allMatch(token -> token.length() == CardScreenState.CCARD_AID_LENGTH);
            assertThat(CardScreenState.CCARD_AID_PA1).isEqualTo("PA1  ");
            assertThat(CardScreenState.CCARD_AID_PA2).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("the XCTL in COACTVWC begins at :349, and the Request carries no next-program field")
        void theXctlStatementSpansThreeLines() {
            // CORRECTION (practice B4): AAP 0.4.11 cites this transfer as "COACTVWC:L350". That line is
            // the PROGRAM continuation, not the start of the statement. Read from the source, the
            // statement occupies THREE lines:
            //
            //   :349  EXEC CICS XCTL
            //   :350            PROGRAM (CDEMO-TO-PROGRAM)
            //   :351            COMMAREA(CARDDEMO-COMMAREA)
            //   :352  END-EXEC
            //
            // so the citation is :349-:351. It matters because :349 is where the statement can be found
            // and because the COMMAREA operand on :351 - not on :350 - is the evidence that the whole of
            // CARDDEMO-COMMAREA is what crosses the transfer.
            //
            // The transfer itself is NOT this type's concern. XCTL becomes a nextProgram field on the
            // RESPONSE, resolved client-side so the server stays stateless (rule R6), and
            // AccountViewResponseTest asserts it. What belongs here is the negative: the REQUEST has no
            // navigation target of its own, and its only carried navigation state is the communication
            // area the transfer hands over.
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(new AccountViewRequest());

            assertThat(json.has("nextProgram")).isFalse();
            assertThat(json.has("nextMapset")).isFalse();
            assertThat(json.has("nextMap")).isFalse();

            // What it does carry is CDEMO-TO-PROGRAM, inside the area named on :351 - the operand the
            // corrected citation makes visible.
            AccountViewRequest request = new AccountViewRequest();
            request.setNavigationContext(NavigationContext.empty().withToProgram("COACTVWC"));
            assertThat(request.getNavigationContext().toProgram()).isEqualTo("COACTVWC");
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("COACTVW declares no FKEYS, FKEY05 or FKEY12 - those belong to COACTUP")
        void theFunctionKeyFieldsBelongToTheOtherMapset() {
            // app/bms/COACTVW.bms contains ZERO occurrences of the string "FKEY" - verified by count.
            // The three fields exist only on the sibling mapset, at app/bms/COACTUP.bms:493 (FKEYS),
            // :498 (FKEY05) and :503 (FKEY12).
            //
            // Asserted as an ABSENCE, deliberately. The failure this guards against is not a missing
            // field but a tolerated extra one: a suite that only checks "the 37 I expect are present"
            // passes just as happily over 40 members, and adding FKEY fields "for symmetry with
            // COACTUP" is exactly the plausible edit that would do it. A 38th member breaks gate G9 as
            // surely as a missing one.
            Set<String> declared = EnumSet.allOf(ScreenField.class).stream()
                    .map(ScreenField::label)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(declared)
                    .hasSize(37)
                    .doesNotContain("FKEYS", "FKEY05", "FKEY12", "PAGENO");
            assertThat(declared)
                    .as("no COACTVW field name begins with FKEY")
                    .noneMatch(label -> label.startsWith("FKEY"));

            // And no such member reaches the wire either.
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(new AccountViewRequest());
            List<String> members = new ArrayList<>();
            json.fieldNames().forEachRemaining(members::add);
            assertThat(members).noneMatch(member -> member.toUpperCase(Locale.ROOT)
                    .startsWith("FKEY"));
        }
    }

    // =================================================================================================
    // Structural guards. These assert properties of the TYPE rather than of any one value, which is the
    // only way to state a gate that is about what must NOT be there.
    // =================================================================================================

    @Nested
    @DisplayName("Structural guards - the gates that are satisfied by an absence")
    class StructuralGuards {

        /** {@link AccountViewRequest} and both of its nested types, which the guards walk together. */
        private List<Class<?>> typeAndNestedTypes() {
            return List.of(AccountViewRequest.class, ScreenField.class, ScreenFieldMetadata.class);
        }

        @Test
        @DisplayName("no HttpSession, no @SessionAttributes, no ThreadLocal anywhere in the type (G37)")
        void nothingHoldsServerSideState() {
            // Gate G37. Checked by TYPE NAME rather than by importing the servlet or Spring Web types,
            // for two reasons: the assertion then holds even where those types are absent from the
            // classpath, and it keeps this test's imports inside the declared dependency set.
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

            // The positive counterpart: two instances share nothing, so there is no per-user state for a
            // second request to observe. This is what statelessness buys.
            AccountViewRequest first = AccountViewRequest.withAccountFilter("00000000011");
            AccountViewRequest second = AccountViewRequest.withAccountFilter("00000000022");
            first.metadata(ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);
            assertThat(second.metadata(ScreenField.ACCTSID).isAttributeUnset()).isTrue();
            assertThat(second.getAcctsid()).isEqualTo("00000000022");
        }

        @Test
        @DisplayName("no double, no float and no rounding mode anywhere in the type (G22, G23, G24)")
        void noBinaryFloatingPointAndNoRoundingDecision() {
            // Gates G22/G23/G24 reach this file as a NEGATIVE. All 37 COACTVW input items are character
            // storage - 36 are PIC X and ACCTSID is PIC 99999999999, which is eleven characters of
            // display storage rather than a computational field - so there is no arithmetic here at all.
            // The edited monetary masks of CVTRA07Y live on the Response side.
            //
            // The guard is therefore that no numeric-decision type has crept in: a double or a float
            // would break numeric parity outright (rule R4), and a RoundingMode would mean a rounding
            // decision was being taken in a payload, where COBOL takes none. Note that COBOL truncates
            // absent ROUNDED - which appears zero times in all 28 programs - so the only faithful mode
            // anywhere in this system is RoundingMode.DOWN; the correct number of rounding decisions in
            // THIS type is none.
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
                    // Guarded on emptiness: most of these methods take no argument at all, and AssertJ
                    // rejects an empty "values to look for" rather than treating it as vacuously true.
                    List<Class<?>> parameters = Arrays.asList(method.getParameterTypes());
                    if (!parameters.isEmpty()) {
                        assertThat(banned)
                                .as("%s.%s takes %s", type.getSimpleName(), method.getName(),
                                        Arrays.toString(method.getParameterTypes()))
                                .doesNotContainAnyElementsOf(parameters);
                    }
                }
            }

            // The 37 values are String-shaped end to end, which is the reason none of the above is
            // needed. ACCTSID included - see the Accessors group for why its numeric PICTURE does not
            // make it a numeric field.
            for (ScreenField field : ScreenField.values()) {
                assertThat(new AccountViewRequest().value(field)).isInstanceOf(String.class);
            }
        }

        @Test
        @DisplayName("this test class itself holds no mutable static state (G53)")
        void theSuiteItselfKeepsNoMutableStaticState() {
            // Gate G53 and practice B9, turned on the test rather than the subject. A suite whose
            // expectations live in a mutable static table can be corrupted by whichever test runs first,
            // and the resulting failure points anywhere but at the cause. Every table here is an
            // unmodifiable List, and the two codecs are per-instance fields rebuilt by @BeforeEach.
            //
            // A static final ARRAY would satisfy the compiler and fail this assertion, which is the whole
            // reason the tables are Lists.
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

            // The tables reject mutation rather than merely discouraging it.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> LABELS.set(0, "TAMPERED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> WIDTHS.set(0, 999));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> POSITIONS.get(0).set(0, 99));

            // And the per-test codecs are genuinely present and distinct.
            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
            assertThat(asciiCodec).isNotSameAs(ebcdicCodec);
        }

        @Test
        @DisplayName("no import in this suite is a wildcard, and every table is 37 long (G52)")
        void theTranscriptionTablesAgreeOnThirtySeven() {
            // Gate G52 is a source-level property - no "import ...*;" line - and it is enforced by
            // review and by the explicit import block at the head of this file. What is assertable at
            // runtime is the reason G52 exists: that every name in play is one specific, named type and
            // every table lines up field for field.
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
