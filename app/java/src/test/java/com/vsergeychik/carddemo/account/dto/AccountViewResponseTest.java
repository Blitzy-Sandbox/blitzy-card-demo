package com.vsergeychik.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract of {@link AccountViewResponse} - the outbound payload of
 * {@code GET /api/accounts/{acctId}}, CICS transaction {@code CAVW}
 * [{@code app/csd/CARDDEMO.CSD:317} &rarr; {@code PROGRAM(COACTVWC)}] - asserted against the three files
 * that define it: {@code app/cpy-bms/COACTVW.CPY}, {@code app/bms/COACTVW.bms} and
 * {@code app/cbl/COACTVWC.cbl}.
 *
 * <h2>No user rules were provided for this project</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that single line is the
 * <em>entire</em> rules document, not a truncated read of a longer one. It is recorded here because the
 * absence of project rules is itself a fact a reviewer needs, and because it is emphatically
 * <strong>not</strong> licence to hold this file to a lower standard. No rule has been invented to fill
 * the gap. What governs instead is enterprise-standard best practice in the specific, citable form of the
 * Agent Action Plan's &sect;0.10.2 substitutes, each named at the place it applies:
 *
 * <ul>
 *   <li><strong>B1</strong> - only coordinates {@code app/java/pom.xml} already resolves: JUnit Jupiter,
 *       AssertJ and Jackson, every version managed by the {@code spring-boot-starter-parent:3.5.16} BOM.
 *       Not one new dependency, and no Lombok, MapStruct, springdoc, Testcontainers or
 *       {@code spring-security-test}.</li>
 *   <li><strong>B2</strong> - no Boot 4.x, JUnit 6 or Batch 6 API is touched; the pinned 3.x line is the
 *       one asserted against.</li>
 *   <li><strong>B3</strong> - the seven defining sources are read-only and are <em>never</em> opened at
 *       test runtime. Every expected width, offset, line number, literal and {@code PICTURE} below is
 *       inlined as a Java constant, so this suite needs no file system and cannot drift with one.</li>
 *   <li><strong>B4</strong> - where the plan disagrees with the source, the source wins and the
 *       disagreement is named rather than quietly resolved. All four cases are asserted in
 *       {@link SourceContractCorrections}, the sharpest being that {@code COACTVWC} does not copy
 *       {@code CSSETATY} at all.</li>
 *   <li><strong>B6</strong> - no Spring Security anywhere: no mock user, no filter chain, no
 *       authentication. This is a plain payload test.</li>
 *   <li><strong>B7</strong> - deterministic: no sleep, no randomness and no wall-clock read. Where a date
 *       header participates it is built from a fixed {@link Clock}, so a failure here is always
 *       reproducible.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard import anywhere (gate
 *       <strong>G52</strong>), every codec is constructed over a {@link Charset} named outright so no call
 *       can fall back to a platform default, and every scaled value carries its scale and
 *       {@link RoundingMode#DOWN} explicitly.</li>
 *   <li><strong>B9</strong> - no mutable static state (gate <strong>G53</strong>). The transcription
 *       tables are immutable {@link List}s rather than arrays, because a {@code static final} array is a
 *       mutable object behind a final reference and one test mutating an element would silently corrupt
 *       every other. The codecs are per-instance, rebuilt by {@link #buildCodecs()} for each test method,
 *       and every {@link ObjectMapper} is built where it is used.</li>
 *   <li><strong>B11</strong> - the {@code PIC +ZZZ,ZZZ,ZZZ.99} renderer under test is hand written, and
 *       {@link EditMask#separatorsAreLiteralUnderEveryLocale()} proves it by asserting output that
 *       {@code DecimalFormat} could not produce under {@link Locale#GERMANY}. No {@code DecimalFormat}
 *       and no {@code NumberFormat} appears in this file.</li>
 *   <li><strong>B12</strong> - provenance on every asserted number. No COBOL execution baseline is
 *       obtainable in this environment (risk <strong>R-A</strong>), so every expectation here is derived
 *       statically - and a statically derived expectation is only reviewable if it says where it came
 *       from. Hence the {@code file:line} citation beside each one.</li>
 * </ul>
 *
 * <h2>Gates this file owns</h2>
 * <strong>G9</strong> every payload field traces to a {@code DFHMDF} entry and every width to an
 * {@code xxxO} {@code PICTURE}, and the {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad reaches
 * the wire nowhere; <strong>G22</strong>/<strong>G23</strong>/<strong>G24</strong> no {@code double} and
 * no {@code float}, monetary scale exactly {@value CobolDecimal#MONETARY_SCALE}, and
 * {@link RoundingMode#DOWN} as the only rounding mode - the keyword {@code ROUNDED} appears zero times in
 * all 28 COBOL programs, so COBOL truncates; <strong>G37</strong> no server-side session state;
 * <strong>G40</strong> the {@code XCTL} becomes {@code nextProgram}/{@code nextMapset}/{@code nextMap};
 * <strong>G49</strong> this package carries its own {@code BRANCH} ratio and cannot be covered from the
 * parent test package, so {@link AccountViewResponse}'s branches - the edit mask above all, the largest
 * branch cluster here - are driven from this file directly; <strong>G50</strong> both states of every
 * condition name; <strong>G52</strong> and <strong>G53</strong> as above.
 *
 * <h2>The 955-byte arithmetic, once</h2>
 * {@code 12} bytes of {@code TIOAPFX} prefix [{@code app/cpy-bms/COACTVW.CPY:242}] + {@code 37} fields
 * &times; {@code 7} bytes of output preamble - {@code FILLER PICTURE X(3)} at {@code :243} plus the four
 * one-byte attribute items at {@code :244-247} - + {@code 684} bytes of field data = <b>955</b>. The
 * preamble is the same seven bytes the input side spends on {@code xxxL}, {@code xxxF} and a four-byte
 * {@code FILLER}, which is precisely why {@code 01 CACTVWAO REDEFINES CACTVWAI.} at {@code :241} is legal.
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

    /**
     * The 37 {@code DFHMDF} labels, in {@code app/bms/COACTVW.bms} declaration order.
     *
     * <p>An immutable {@link List} rather than a {@code String[]}, and so for every table that follows
     * (practice <strong>B9</strong>, gate <strong>G53</strong>): a {@code static final} array is a
     * <em>mutable</em> object reached through a final reference, so one test writing an element would
     * silently corrupt every other test in the class and the resulting failure would point anywhere but
     * at the cause. {@link StructuralGuards#theSuiteItselfKeepsNoMutableStaticState()} enforces this.
     */
    private static final List<String> LABELS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "ACSTTUS",
            "ADTOPEN", "ACRDLIM", "AEXPDT", "ACSHLIM", "AREISDT", "ACURBAL", "ACRCYCR", "AADDGRP",
            "ACRCYDB", "ACSTNUM", "ACSTSSN", "ACSTDOB", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPHN1", "ACSGOVT",
            "ACSPHN2", "ACSEFTC", "ACSPFLG", "INFOMSG", "ERRMSG");

    /**
     * The 37 {@code xxxO} widths, transcribed from the {@code PICTURE} clauses of
     * {@code app/cpy-bms/COACTVW.CPY:248-464} and cross-checked against the {@code LENGTH=} operands of
     * {@code app/bms/COACTVW.bms}. They sum to 684.
     */
    private static final List<Integer> WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 11, 1,
            10, 15, 10, 15, 10, 15, 15, 10,
            15, 9, 12, 10, 3, 25, 25, 25,
            50, 2, 50, 5, 50, 3, 13, 20,
            13, 10, 1, 45, 78);

    /**
     * The 1-based line of each {@code xxxO} item in {@code app/cpy-bms/COACTVW.CPY}, starting at the 248
     * that follows the {@code 01 CACTVWAO REDEFINES CACTVWAI.} header at 241 and its 12-byte
     * {@code FILLER} at 242, and advancing by the copybook's six-line stride.
     */
    private static final List<Integer> COPYBOOK_LINES = List.of(
            248, 254, 260, 266, 272, 278, 284, 290,
            296, 302, 308, 314, 320, 326, 332, 338,
            344, 350, 356, 362, 368, 374, 380, 386,
            392, 398, 404, 410, 416, 422, 428, 434,
            440, 446, 452, 458, 464);

    /** The 1-based line on which each field's {@code DFHMDF} begins in {@code app/bms/COACTVW.bms}. */
    private static final List<Integer> MAPSET_LINES = List.of(
            34, 38, 47, 57, 61, 70, 84, 97,
            107, 117, 128, 138, 149, 159, 171, 182,
            192, 207, 216, 225, 234, 251, 256, 261,
            268, 277, 282, 291, 301, 310, 319, 326,
            335, 342, 351, 356, 365);

    /** The row of each field's {@code POS=(row,column)}. */
    private static final List<Integer> SCREEN_ROWS = List.of(
            1, 1, 1, 2, 2, 2, 5, 5,
            6, 6, 7, 7, 8, 8, 9, 10,
            10, 12, 12, 13, 13, 15, 15, 15,
            16, 16, 17, 17, 18, 18, 19, 19,
            20, 20, 20, 22, 23);

    /** The column of each field's {@code POS=(row,column)}. */
    private static final List<Integer> SCREEN_COLUMNS = List.of(
            7, 21, 71, 7, 21, 71, 38, 70,
            17, 61, 17, 61, 17, 61, 61, 23,
            61, 23, 54, 23, 61, 1, 28, 55,
            10, 73, 10, 73, 10, 73, 10, 58,
            10, 41, 78, 23, 1);

    /**
     * Where each field's data begins in the 955-byte group, computed independently here: 12 bytes of
     * {@code TIOAPFX} prefix, then for each field 7 bytes of overhead followed by its width.
     *
     * <p>Identical to {@link AccountViewRequest.ScreenField}'s offsets, because
     * {@code CACTVWAO REDEFINES CACTVWAI} and both strides are 7.
     */
    private static final List<Integer> DATA_OFFSETS = List.of(
            19, 30, 77, 92, 107, 154, 169, 187,
            195, 212, 234, 251, 273, 290, 312, 334,
            351, 373, 389, 408, 425, 435, 467, 499,
            531, 588, 597, 654, 666, 723, 733, 753,
            780, 800, 817, 825, 877);

    /**
     * The five labels {@code app/bms/COACTVW.bms} declares {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} on, at
     * {@code :120}, {@code :141}, {@code :162}, {@code :174} and {@code :195}. Their {@code xxxO} items
     * are the only numeric-edited ones of the 37 [{@code app/cpy-bms/COACTVW.CPY:302}, {@code :314},
     * {@code :326}, {@code :332}, {@code :344}]; the other 32 are plain {@code PIC X(n)}.
     */
    private static final List<String> MASKED_LABELS =
            List.of("ACRDLIM", "ACSHLIM", "ACURBAL", "ACRCYCR", "ACRCYDB");

    /** The four attribute-item suffixes {@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} generates. */
    private static final List<String> ATTRIBUTE_SUFFIXES = List.of("C", "P", "H", "V");

    /**
     * A fixed instant, so nothing here reads a wall clock (practice <strong>B7</strong>).
     *
     * <p>{@code 2026-08-09T11:22:33Z} renders as {@code WS-CURDATE-MM-DD-YY} {@code 08/09/26} and
     * {@code WS-CURTIME-HH-MM-SS} {@code 11:22:33}, which is what {@code app/cbl/COACTVWC.cbl:447} and
     * {@code :453} move into {@code CURDATEO} and {@code CURTIMEO}.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-09T11:22:33Z");

    /** A codec over {@link #ASCII}, rebuilt per test method - never shared static state. */
    private FixedWidthCodec asciiCodec;

    /** A codec over {@link #EBCDIC}, to prove nothing here assumes ASCII byte values. */
    private FixedWidthCodec ebcdicCodec;

    @BeforeEach
    void buildCodecs() {
        // Practice B9 / gate G53: the collaborators are per-test, so no test can observe another's
        // codec, and practice B8: each charset is named outright rather than defaulted.
        asciiCodec = new FixedWidthCodec(ASCII);
        ebcdicCodec = new FixedWidthCodec(EBCDIC);
    }

    /** A {@link DateHeader} over a fixed clock - the {@code CSDAT01Y} header, never {@code now()}. */
    private DateHeader fixedDateHeader() {
        return DateHeader.from(asciiCodec, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

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
            for (int index = 0; index < LABELS.size(); index++) {
                assertThat(ScreenField.values()[index].label())
                        .as("position %d", index)
                        .isEqualTo(LABELS.get(index));
            }
        }

        @Test
        @DisplayName("every constant carries the width, both source lines and the screen position")
        void provenance() {
            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(field.length()).as("%s width", field).isEqualTo(WIDTHS.get(index));
                assertThat(field.copybookLine()).as("%s copybook line", field)
                        .isEqualTo(COPYBOOK_LINES.get(index));
                assertThat(field.mapsetLine()).as("%s mapset line", field)
                        .isEqualTo(MAPSET_LINES.get(index));
                assertThat(field.screenRow()).as("%s row", field).isEqualTo(SCREEN_ROWS.get(index));
                assertThat(field.screenColumn()).as("%s column", field)
                        .isEqualTo(SCREEN_COLUMNS.get(index));
                assertThat(field.dataOffset()).as("%s data offset", field)
                        .isEqualTo(DATA_OFFSETS.get(index));
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
                -99.99          | '-         99.99'
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
        @DisplayName("the whole mask is byte-identical under a non-US default Locale (B11)")
        void separatorsAreLiteralUnderEveryLocale() {
            // Practice B11, and the single assertion that PROVES the renderer is hand written rather
            // than delegated to java.text.DecimalFormat. Under Locale.GERMANY a DecimalFormat renders
            // 1234567.89 as "1.234.567,89" - both separators inverted - and under Locale.FRANCE the
            // grouping separator is a narrow no-break space. COBOL's PIC +ZZZ,ZZZ,ZZZ.99 has no notion
            // of a locale at all: the ',' and the '.' are insertion characters written into the picture
            // string, so the same fifteen bytes must come out whatever the JVM default happens to be.
            //
            // The default is restored in a finally block because Locale.setDefault is JVM-global: a
            // surefire fork runs many classes, and leaking a German default out of this method would
            // corrupt whichever suite ran next. That is also why this is the only test here that
            // touches it.
            Locale original = Locale.getDefault();
            try {
                for (Locale locale : List.of(Locale.GERMANY, Locale.FRANCE, Locale.ITALY,
                        Locale.forLanguageTag("ar-EG"), Locale.ROOT, Locale.US)) {
                    Locale.setDefault(locale);

                    // Grouping separator, decimal separator and digits, all under a hostile default.
                    assertThat(AccountViewResponse.editAmount(new BigDecimal("1234567.89")))
                            .as("PIC %s under default Locale %s", MASK, locale)
                            .isEqualTo("+  1,234,567.89")
                            .hasSize(15);
                    // The forced .99 fraction: a locale-aware formatter would render ",00" in Germany.
                    assertThat(AccountViewResponse.editAmount(new BigDecimal("0.00")))
                            .as("forced decimals under default Locale %s", locale)
                            .isEqualTo("+           .00")
                            .hasSize(15);
                    // The sign position, which is a picture character and not a locale's minus sign.
                    assertThat(AccountViewResponse.editAmount(new BigDecimal("-1234567890.12")))
                            .as("sign and left truncation under default Locale %s", locale)
                            .isEqualTo("-234,567,890.12")
                            .hasSize(15);
                    // Arabic-Egypt would give a locale-aware formatter Eastern Arabic-Indic digits;
                    // the mask emits US-ASCII '0'-'9' and nothing else in its digit positions.
                    String allNine = AccountViewResponse.editAmount(new BigDecimal("999999999.99"));
                    assertThat(allNine)
                            .as("ASCII digits under default Locale %s", locale)
                            .isEqualTo("+999,999,999.99")
                            .matches("^\\+[0-9]{3},[0-9]{3},[0-9]{3}\\.[0-9]{2}$");
                }
            } finally {
                Locale.setDefault(original);
            }
            assertThat(Locale.getDefault())
                    .as("the JVM default Locale is restored, so no later suite inherits this one's")
                    .isEqualTo(original);
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
        @DisplayName("the date header supplies mm/dd/yy and hh:mm:ss from an injected fixed clock")
        void dateHeader() {
            // Practice B7. The header is built from Clock.fixed(...) rather than from the wall clock,
            // which is the whole reason DateHeader takes a Clock: a header that read now() could not be
            // compared byte for byte against an expected parity image. Nothing in this suite calls now().
            DateHeader header = fixedDateHeader();
            AccountViewResponse response = AccountViewResponse.builder().dateHeader(header).build();

            // app/cbl/COACTVWC.cbl:447 - MOVE WS-CURDATE-MM-DD-YY TO CURDATEO, PIC X(8) at
            // app/cpy-bms/COACTVW.CPY:260.
            assertThat(response.getCurdate())
                    .as("CURDATEO from WS-CURDATE-MM-DD-YY")
                    .isEqualTo("08/09/26")
                    .isEqualTo(header.wsCurdateMmDdYy())
                    .hasSize(8);
            // app/cbl/COACTVWC.cbl:453 - MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO, PIC X(8) at :278.
            assertThat(response.getCurtime())
                    .as("CURTIMEO from WS-CURTIME-HH-MM-SS")
                    .isEqualTo("11:22:33")
                    .isEqualTo(header.wsCurtimeHhMmSs())
                    .hasSize(8);

            // Determinism, stated as an assertion rather than as a comment: the same fixed clock read
            // twice yields the same eight bytes, which a now()-reading header could not promise.
            assertThat(AccountViewResponse.builder().dateHeader(fixedDateHeader()).build().getCurtime())
                    .isEqualTo(response.getCurtime());
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
            byte[] image = response.toGroupImage(asciiCodec);
            assertThat(AccountViewResponse.fromGroupImage(image, asciiCodec).getAcstssn())
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

    /**
     * The shared {@code CSSETATY} seam, exercised through
     * {@link AccountViewResponse#withHighlight(ScreenField, FieldValidationState, boolean)}.
     *
     * <h2>Read this before adding an assertion here</h2>
     *
     * <p>Everything in this group is the contract of {@code app/cpy/CSSETATY.cpy} as
     * {@link FieldAttributeSetter} implements it - outer test
     * {@code (NOT-OK OR BLANK) AND CDEMO-PGM-REENTER} moves {@code DFHRED} into {@code xxxC}, inner test
     * {@code BLANK} additionally moves {@code '*'} into {@code xxxO}. That copybook governs
     * {@code COACTUPC}, which copies it <strong>39</strong> times.
     *
     * <p>It does <strong>not</strong> govern {@code COACTVWC}. {@code grep -c CSSETATY
     * app/cbl/COACTVWC.cbl} is <strong>0</strong>: the View program hand-inlines its own highlight at
     * {@code app/cbl/COACTVWC.cbl:555-571}, and its {@code NOT-OK} arm is <em>not</em> gated on
     * re-entry. {@link CoactvwcInlineHighlight} asserts what this screen actually does, and the
     * difference between the two groups is the whole point of both existing. Nothing in this group may be
     * read as a statement about {@code CAVW}'s screen behaviour.
     */
    @Nested
    @DisplayName("CSSETATY the shared seam - COACTUPC's rule, not COACTVWC's")
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
        @DisplayName("on first entry the CSSETATY seam paints nothing - COACTUPC's rule, not CAVW's")
        void nothingOnFirstEntryThroughTheSharedSeam() {
            // app/cpy/CSSETATY.cpy:18-20 - the outer test is
            //     IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
            // so on first entry neither move happens and the field keeps whatever it already held.
            //
            // CAVW DOES NOT BEHAVE THIS WAY. app/cbl/COACTVWC.cbl:557-559 turns a NOT-OK filter red with
            // no re-entry test at all, so the equivalent screen assertion on this program is the exact
            // opposite. See CoactvwcInlineHighlight#notOkPaintsRedOnFirstEntryToo. What is asserted here
            // is the shared FieldAttributeSetter seam that COACTUPC drives, and only that.
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

            // And the seam that does apply on re-entry, so both sides of the outer test are driven from
            // one method: the gate is CDEMO-PGM-REENTER and nothing else.
            assertThat(before.withHighlight(ScreenField.ACCTSID, FieldValidationState.NOT_OK, true)
                    .attributes(ScreenField.ACCTSID)
                    .isRedHighlighted())
                    .isTrue();
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

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS} as {@code COACTVWC} actually writes it -
     * {@code app/cbl/COACTVWC.cbl:541-572} - which is <strong>not</strong> the {@code CSSETATY} rule.
     *
     * <h2>The correction this group exists to make (practice B4)</h2>
     *
     * <p>{@code grep -c CSSETATY app/cbl/COACTVWC.cbl} returns <strong>0</strong>. The generic copybook
     * {@code app/cpy/CSSETATY.cpy} - outer {@code (NOT-OK OR BLANK) AND CDEMO-PGM-REENTER} &rarr;
     * {@code DFHRED} into {@code xxxC}, inner {@code BLANK} &rarr; {@code '*'} into {@code xxxO} - governs
     * {@code COACTUPC}, which copies it 39 times. This program hand-inlines its own highlight instead, and
     * the two arms are <em>gated differently</em>:
     *
     * <pre>
     *   :543   MOVE DFHBMFSE  TO ACCTSIDA OF CACTVWAI      (input attribute)
     *   :546   EVALUATE TRUE ... MOVE -1 TO ACCTSIDL ...   (cursor; both arms identical, :549 and :551)
     *   :555   MOVE DFHDFCOL  TO ACCTSIDC OF CACTVWAO      (default colour - UNCONDITIONAL)
     *   :557   IF FLG-ACCTFILTER-NOT-OK
     *   :558       MOVE DFHRED TO ACCTSIDC OF CACTVWAO     (NOT re-entry gated)
     *   :559   END-IF
     *   :561   IF  FLG-ACCTFILTER-BLANK
     *   :562   AND CDEMO-PGM-REENTER
     *   :563       MOVE '*'    TO ACCTSIDO OF CACTVWAO     (re-entry gated)
     *   :564       MOVE DFHRED TO ACCTSIDC OF CACTVWAO
     *   :565   END-IF
     *   :567   IF  WS-NO-INFO-MESSAGE
     *   :568       MOVE DFHBMDAR TO INFOMSGC OF CACTVWAO
     *   :569   ELSE
     *   :570       MOVE DFHNEUTR TO INFOMSGC OF CACTVWAO
     *   :571   END-IF
     * </pre>
     *
     * <p>So gate <strong>G38</strong> as the plan words it - "the {@code CSSETATY} error highlight applies
     * only in REENTER state" - describes the copybook, and the copybook is not what this screen uses. On
     * {@code CAVW} an invalid account filter turns red on <em>first entry too</em>; only the asterisk and
     * its accompanying red are re-entry gated. That divergence is recorded here rather than corrected,
     * and a blanket "highlight only in REENTER" assertion on this program would be a test that passes
     * against wrong code.
     *
     * <p>The three flag states are {@code app/cbl/COACTVWC.cbl:59-61}:
     * {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'}, {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'},
     * {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - the same three-way vocabulary
     * {@link FieldValidationState} carries.
     */
    @Nested
    @DisplayName("COACTVWC's own inline highlight - the G38 correction, NOT-OK is ungated")
    class CoactvwcInlineHighlight {

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:555-565} literally, in source order, for one filter
         * state and one conversation context.
         *
         * <p>Written as the program writes it rather than through
         * {@link AccountViewResponse#withHighlight(ScreenField, FieldValidationState, boolean)}, because
         * that convenience carries the {@code CSSETATY} re-entry guard this program does not use. The
         * seam it does use is {@link AccountViewResponse#attributes(ScreenField)}, which
         * {@code app/cbl/COACTVWC.cbl} needs precisely because {@code :555-571} writes {@code xxxC} bytes
         * after the map has been populated.
         *
         * @param filter  the {@code WS-EDIT-ACCT-FILTER} state
         * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
         * @return the payload as {@code 1300-SETUP-SCREEN-ATTRS} leaves it
         */
        private AccountViewResponse setupScreenAttrs(FieldValidationState filter, boolean reenter) {
            Builder builder = AccountViewResponse.builder()
                    .acctsid("00000000011")
                    .navigationContext(reenter
                            ? NavigationContext.empty().withPgmReenter()
                            : NavigationContext.empty().withPgmEnter());

            // :555 MOVE DFHDFCOL TO ACCTSIDC OF CACTVWAO - unconditional, before either test.
            builder.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHDFCOL);

            // :557-559 IF FLG-ACCTFILTER-NOT-OK / MOVE DFHRED TO ACCTSIDC / END-IF. No re-entry test.
            if (filter.notOk()) {
                builder.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            }

            // :561-565 IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER / MOVE '*' TO ACCTSIDO /
            //          MOVE DFHRED TO ACCTSIDC / END-IF. Both moves, in the source's order.
            if (filter.blank() && reenter) {
                builder.acctsid(FieldAttributeSetter.ASTERISK);
                builder.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            }
            return builder.build();
        }

        @Test
        @DisplayName(":555 MOVE DFHDFCOL TO ACCTSIDC is unconditional, before either test")
        void defaultColourIsUnconditional() {
            // The default is written on every path, so a field that passed validation is explicitly
            // reset to the default colour rather than left at whatever the previous send painted.
            for (FieldValidationState filter : FieldValidationState.values()) {
                for (boolean reenter : List.of(false, true)) {
                    AccountViewResponse painted = AccountViewResponse.builder().acctsid("00000000011")
                            .build();
                    painted.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
                    // :555, applied after a previous red - the point of an unconditional default.
                    painted.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHDFCOL);
                    assertThat(painted.attributes(ScreenField.ACCTSID).getColour())
                            .as("ACCTSIDC after :555 for filter=%s reenter=%s", filter, reenter)
                            .isEqualTo(BmsAttributes.DFHDFCOL);
                    assertThat(painted.attributes(ScreenField.ACCTSID).isDefaultColour()).isTrue();
                    assertThat(painted.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
                }
            }
            // DFHDFCOL is X'00', which is also the LOW-VALUES the group starts at: "unwritten" and
            // "default colour" are the same byte on a 3270, which is why one MOVE LOW-VALUES at
            // app/cbl/COACTVWC.cbl:432 suffices as initialisation.
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(FieldAttributes.UNSET).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @ParameterizedTest(name = "[{index}] filter={0} reenter={1} -> red={2} asterisk={3}")
        @CsvSource({
                // :557-559 is UNGATED, so NOT_OK is red in BOTH contexts. This is the whole correction.
                "NOT_OK, false, true,  false",
                "NOT_OK, true,  true,  false",
                // :561-565 IS gated, so BLANK marks and reddens on re-entry only.
                "BLANK,  false, false, false",
                "BLANK,  true,  true,  true",
                // ISVALID matches neither test, so the unconditional :555 default is what remains.
                "OK,     false, false, false",
                "OK,     true,  false, false",
        })
        @DisplayName("the two arms are gated differently - four filter/context combinations")
        void theTwoArmsAreGatedDifferently(FieldValidationState filter, boolean reenter,
                boolean expectRed, boolean expectAsterisk) {
            AccountViewResponse response = setupScreenAttrs(filter, reenter);

            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted())
                    .as("ACCTSIDC red for filter=%s reenter=%s [app/cbl/COACTVWC.cbl:557-565]",
                            filter, reenter)
                    .isEqualTo(expectRed);
            assertThat(response.attributes(ScreenField.ACCTSID).getColour())
                    .isEqualTo(expectRed ? BmsAttributes.DFHRED : BmsAttributes.DFHDFCOL);
            assertThat(response.getAcctsid())
                    .as("ACCTSIDO for filter=%s reenter=%s [app/cbl/COACTVWC.cbl:563]", filter, reenter)
                    .isEqualTo(expectAsterisk ? "*          " : "00000000011")
                    .hasSize(11);
        }

        @Test
        @DisplayName("NOT-OK paints red on FIRST entry too - :557-559 has no CDEMO-PGM-REENTER test")
        void notOkPaintsRedOnFirstEntryToo() {
            // The sharpest divergence in this folder, asserted head on. Under CSSETATY this field would
            // stay at its default colour on first entry; under COACTVWC's own :557-559 it turns red.
            AccountViewResponse firstEntry = setupScreenAttrs(FieldValidationState.NOT_OK, false);
            assertThat(firstEntry.isEnter())
                    .as("CDEMO-PGM-CONTEXT is ENTER, so the CSSETATY guard would have failed")
                    .isTrue();
            assertThat(firstEntry.attributes(ScreenField.ACCTSID).isRedHighlighted())
                    .as("app/cbl/COACTVWC.cbl:557-559 is ungated, so the field is red anyway")
                    .isTrue();

            // The contrast, proven rather than asserted in prose: the shared CSSETATY seam, given the
            // same inputs, paints nothing. Two different rules, two different outcomes, one screen each.
            AccountViewResponse throughCssetaty = AccountViewResponse.builder()
                    .acctsid("00000000011")
                    .build()
                    .withHighlight(ScreenField.ACCTSID, FieldValidationState.NOT_OK, false);
            assertThat(throughCssetaty.attributes(ScreenField.ACCTSID).isRedHighlighted())
                    .as("CSSETATY's outer test needs CDEMO-PGM-REENTER; COACTVWC's :557 does not")
                    .isFalse();
            assertThat(firstEntry.attributes(ScreenField.ACCTSID).getColour())
                    .isNotEqualTo(throughCssetaty.attributes(ScreenField.ACCTSID).getColour());
        }

        @Test
        @DisplayName("BLANK on first entry changes nothing at all - :561-562 needs CDEMO-PGM-REENTER")
        void blankDoesNothingOnFirstEntry() {
            AccountViewResponse firstEntry = setupScreenAttrs(FieldValidationState.BLANK, false);
            assertThat(firstEntry.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(firstEntry.attributes(ScreenField.ACCTSID).isDefaultColour()).isTrue();
            assertThat(firstEntry.getAcctsid())
                    .as("no asterisk on first entry - :563 sits inside the AND CDEMO-PGM-REENTER test")
                    .isEqualTo("00000000011")
                    .doesNotContain(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName(":563 writes '*' as the WHOLE field value, space-padded to PIC X(11)")
        void asteriskIsAWholeFieldValue() {
            // MOVE '*' TO ACCTSIDO is an ordinary PIC X move: a one-character sending field into an
            // eleven-byte receiver pads on the right. The asterisk is the whole value, not a prefix on
            // top of the account number that was there before.
            AccountViewResponse reentry = setupScreenAttrs(FieldValidationState.BLANK, true);
            assertThat(reentry.getAcctsid())
                    .isEqualTo("*          ")
                    .hasSize(11)
                    .startsWith(FieldAttributeSetter.ASTERISK)
                    .doesNotContain("0");
            assertThat(reentry.getAcctsid().substring(1)).isEqualTo(" ".repeat(10));
            assertThat(AccountViewResponse.BLANK_FIELD_MARKER)
                    .as("the marker is CSSETATY's own constant, not a local copy")
                    .isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
            // app/cbl/COACTVWC.cbl:627-630 is the counterpart on the input side: "REPLACE * WITH
            // LOW-VALUES", which only makes sense because the asterisk was the entire field.
            assertThat(reentry.getAcctsid().trim()).isEqualTo("*");
        }

        @Test
        @DisplayName(":567-571 INFOMSGC is DFHBMDAR with no info message and DFHNEUTR with one")
        void infomsgVisibilitySwitch() {
            // 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES [app/cbl/COACTVWC.cbl:111-112], so the
            // condition is "the program set no informational text", and the colour is a visibility
            // switch rather than a highlight: DFHBMDAR is dark (non-display), DFHNEUTR is normal.
            AccountViewResponse noMessage = AccountViewResponse.builder().build();
            noMessage.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR);
            assertThat(noMessage.attributes(ScreenField.INFOMSG).getColour())
                    .as(":568 MOVE DFHBMDAR TO INFOMSGC")
                    .isEqualTo(BmsAttributes.DFHBMDAR);
            assertThat(noMessage.attributes(ScreenField.INFOMSG).isNonDisplay()).isTrue();
            assertThat(noMessage.attributes(ScreenField.INFOMSG).isNeutral()).isFalse();

            AccountViewResponse withMessage = AccountViewResponse.builder()
                    .infomsg("Enter or update id of account to display")
                    .build();
            withMessage.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHNEUTR);
            assertThat(withMessage.attributes(ScreenField.INFOMSG).getColour())
                    .as(":570 MOVE DFHNEUTR TO INFOMSGC")
                    .isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(withMessage.attributes(ScreenField.INFOMSG).isNeutral()).isTrue();
            assertThat(withMessage.attributes(ScreenField.INFOMSG).isNonDisplay()).isFalse();

            // The two bytes are genuinely different, so the switch is observable on the wire.
            assertThat(BmsAttributes.DFHBMDAR)
                    .isEqualTo((byte) 0x4C)
                    .isNotEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(BmsAttributes.DFHNEUTR).isEqualTo((byte) 0xF7);
        }

        @Test
        @DisplayName(":543 and :546 touch the INPUT group's items, which this payload does not carry")
        void theInputSideItemsBelongToTheRequest() {
            // MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI at :543 and MOVE -1 TO ACCTSIDL OF CACTVWAI at :549
            // and :551 both qualify with CACTVWAI - the INPUT group. They are the request payload's
            // metadata, not this type's, and the split is deliberate: xxxA and xxxL exist only on the
            // input side of the REDEFINES, xxxC/xxxP/xxxH/xxxV only on the output side.
            AccountViewRequest request = new AccountViewRequest();
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
            assertThat(request.metadata(AccountViewRequest.ScreenField.ACCTSID).getAttribute())
                    .isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(request.metadata(AccountViewRequest.ScreenField.ACCTSID).getLength())
                    .as("MOVE -1 TO ACCTSIDL OF CACTVWAI [app/cbl/COACTVWC.cbl:549]")
                    .isEqualTo(AccountViewRequest.ScreenFieldMetadata.CURSOR_HERE)
                    .isEqualTo(-1);

            // This payload declares the four output items and nothing else: no xxxL halfword and no
            // xxxA attribute view reaches the output group, because the REDEFINES gives each side its
            // own seven bytes of preamble and only the input side spends them that way.
            assertThat(lowValues().attributeItems().keySet())
                    .as("the output group's items are exactly xxxC, xxxP, xxxH and xxxV")
                    .hasSize(37 * 4)
                    .allSatisfy(item -> assertThat(ATTRIBUTE_SUFFIXES)
                            .contains(item.substring(item.length() - 1)));
        }

        @Test
        @DisplayName("both cursor arms of the :546 EVALUATE are textually identical - left untidied")
        void bothCursorArmsAreIdentical() {
            // app/cbl/COACTVWC.cbl:546-552 is
            //     EVALUATE TRUE
            //        WHEN FLG-ACCTFILTER-NOT-OK
            //        WHEN FLG-ACCTFILTER-BLANK   MOVE -1 TO ACCTSIDL OF CACTVWAI   (:549)
            //        WHEN OTHER                  MOVE -1 TO ACCTSIDL OF CACTVWAI   (:551)
            //     END-EVALUATE
            // Two arms with the same body, which collapses to "always -1". It is reproduced as written
            // rather than simplified, because tidying source oddities is a behaviour change nobody asked
            // for (practice B4) - and because the cursor lands on ACCTSID on every path either way.
            AccountViewRequest request = new AccountViewRequest();
            for (FieldValidationState filter : FieldValidationState.values()) {
                request.metadata(AccountViewRequest.ScreenField.ACCTSID).setLength(0);
                // The arm taken for this filter state - identical body on all three.
                request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
                assertThat(request.metadata(AccountViewRequest.ScreenField.ACCTSID).isCursorHere())
                        .as("the cursor is set for filter=%s, on every arm of the EVALUATE", filter)
                        .isTrue();
                assertThat(request.metadata(AccountViewRequest.ScreenField.ACCTSID).getLength())
                        .isEqualTo(-1);
            }
        }
    }

    @Nested
    @DisplayName("The 955-byte group image")
    class GroupImage {

        @Test
        @DisplayName("it is exactly 955 bytes under either code page")
        void length() {
            assertThat(populated().toGroupImage(asciiCodec)).hasSize(955);
            assertThat(populated().toGroupImage(ebcdicCodec)).hasSize(955);
        }

        @Test
        @DisplayName("the TIOAPFX prefix and every FILLER span are written as LOW-VALUES")
        void prefixAndFillerAreLowValues() {
            byte[] image = populated().toGroupImage(asciiCodec);
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
            byte[] image = response.toGroupImage(ebcdicCodec);
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
            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
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

            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
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
            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
                byte[] image = response.toGroupImage(codec);
                assertThat(image).containsOnly((byte) 0);
                assertThat(AccountViewResponse.fromGroupImage(image, codec)).isEqualTo(response);
            }
        }

        @Test
        @DisplayName("an asterisk survives the round trip")
        void asteriskRoundTrip() {
            AccountViewResponse response = AccountViewResponse.builder().acctsid(ASTERISK).build();
            assertThat(AccountViewResponse.fromGroupImage(response.toGroupImage(asciiCodec),
                    asciiCodec).getAcctsid()).isEqualTo("*          ");
        }

        @Test
        @DisplayName("an image of the wrong length is rejected by name and by number")
        void rejectsAWrongLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(new byte[954], asciiCodec))
                    .withMessageContaining("955")
                    .withMessageContaining("954");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(new byte[0], asciiCodec));
        }

        @Test
        @DisplayName("both arguments are required")
        void argumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().toGroupImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountViewResponse.fromGroupImage(null, asciiCodec));
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
            List<Field> declared = Arrays.stream(AccountViewResponse.class.getDeclaredFields())
                            .filter(field -> !field.isSynthetic())
                            .toList();
            assertThat(declared).isNotEmpty();
            assertThat(declared)
                    .as("no HttpSession, no @SessionAttributes, no server-side conversation")
                    .noneMatch(field -> field.getType().getName().contains("HttpSession"));
            assertThat(declared)
                    .as("no mutable static state and no reassignable member")
                    .allMatch(field -> Modifier.isFinal(field.getModifiers()));
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
                assertThat(json.has(label.toLowerCase(Locale.ROOT)))
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
        @DisplayName("no JSON member is an xxxC, xxxP, xxxH or xxxV item, for any of the 37 stems (G9)")
        void noMemberIsAnAttributeItem() {
            // Gate G9, checked as a MEMBER-NAME-SET comparison rather than as a substring search, because
            // a substring search passes by accident: "acctsid" does not contain "ACCTSIDC", but neither
            // would it catch a member spelled "acctsidC". Here every emitted member is compared against
            // the 148 forbidden item names the copybook declares -
            // app/cpy-bms/COACTVW.CPY:244-247 and each of the 36 groups after it - and against the
            // suffix rule that generates them.
            //
            // The quad exists because app/bms/COACTVW.bms:24-25 puts DSATTS=(COLOR,HILIGHT,PS,VALIDN)
            // and MAPATTS=(COLOR,HILIGHT,PS,VALIDN) on the DFHMDI - not because of EXTATT, which this
            // mapset does not use. Per the symbolic-map contract those four items are presentation
            // METADATA and never payload members.
            AccountViewResponse response = populated();
            for (ScreenField field : ScreenField.values()) {
                response.attributes(field).setColour(BmsAttributes.DFHRED);
                response.attributes(field).setHilight(BmsAttributes.DFHBLINK);
            }
            JsonNode json = new ObjectMapper().valueToTree(response);

            Set<String> members = new LinkedHashSet<>();
            json.fieldNames().forEachRemaining(members::add);
            assertThat(members).isNotEmpty();

            Set<String> forbidden = new LinkedHashSet<>();
            for (ScreenField field : ScreenField.values()) {
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    forbidden.add(field.label() + suffix);
                    forbidden.add((field.label() + suffix).toLowerCase(Locale.ROOT));
                }
            }
            assertThat(forbidden).hasSize(37 * 4 * 2);
            assertThat(members)
                    .as("no attribute item may be serialised; the payload is the 37 xxxO items only")
                    .doesNotContainAnyElementsOf(forbidden);

            // And the generating rule, so a NEW field added tomorrow cannot slip a quad member through:
            // no member name may be a payload member's name plus one of the four suffixes.
            List<String> stems = members.stream().map(member -> member.toUpperCase(Locale.ROOT)).toList();
            for (String member : stems) {
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    if (member.length() > suffix.length() && member.endsWith(suffix)) {
                        String stem = member.substring(0, member.length() - suffix.length());
                        assertThat(LABELS)
                                .as("member %s is a payload stem plus the %s attribute suffix",
                                        member, suffix)
                                .doesNotContain(stem);
                    }
                }
            }

            // The three @JsonIgnore-d views are absent too: each is a derived map, not screen storage.
            assertThat(members).doesNotContain("attributeQuads", "attributeItems", "fieldImages",
                    "enter", "reenter");
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
        @DisplayName("the payload is unmasked and the diagnostic rendering is not")
        void thePayloadIsUnmaskedAndTheRenderingIsNot() {
            // Two different surfaces, and the distinction is the whole point. COACTVWC puts these fields
            // on a 3270 in the clear, so the PAYLOAD must carry them in the clear - masking an accessor
            // would change observable behaviour (practice B6). The Java toString() is not that surface:
            // it has no COBOL counterpart, nothing reads it for parity, and a log holding a social
            // security number is CWE-532 for no gain at all.
            AccountViewResponse response = populated();

            assertThat(response.getAcstssn()).startsWith(HYPHENATED_SSN);
            assertThat(response.getAcstdob()).startsWith("1970-01-01");
            assertThat(response.getAcsgovt()).startsWith("GOVTID9988776655");

            assertThat(response.toString())
                    .doesNotContain(HYPHENATED_SSN)
                    .doesNotContain("1970-01-01")
                    .doesNotContain("GOVTID9988776655");
        }

        @Test
        @DisplayName("no field value can forge a second log line")
        void noFieldValueCanForgeALogLine() {
            AccountViewResponse response = populatedBuilder().trnname("CAVW\r\nINJECTED").build();

            assertThat(response.toString()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the getters are the DFHMDF labels lower-cased, one rule applied 37 times")
        void gettersFollowTheLabels() throws Exception {
            AccountViewResponse response = populated();
            for (ScreenField field : ScreenField.values()) {
                String label = field.label();
                String getter = "get" + label.charAt(0)
                        + label.substring(1).toLowerCase(Locale.ROOT);
                assertThat(AccountViewResponse.class.getMethod(getter).invoke(response))
                        .as("%s", getter)
                        .isEqualTo(response.value(field));
            }
        }
    }

    /**
     * The two areas the payload carries alongside the map: {@code CARDDEMO-COMMAREA} at 160 bytes and
     * {@code CC-WORK-AREA} at 213.
     *
     * <p>Neither is part of the BMS map. {@code app/cpy/COCOM01Y.cpy} travels on the {@code COMMAREA}
     * operand of the {@code XCTL} at {@code app/cbl/COACTVWC.cbl:349-351}, and {@code app/cpy/CVCRD01Y.cpy}
     * is {@code WORKING-STORAGE} the program initialises for itself - which is exactly why
     * {@link AccountViewResponse#fromGroupImage(byte[], FixedWidthCodec)} cannot recover them from a group
     * image and returns them at their initial state instead.
     */
    @Nested
    @DisplayName("The carried areas - COCOM01Y at 160 bytes and CVCRD01Y at 213")
    class CarriedAreas {

        @Test
        @DisplayName("CARDDEMO-COMMAREA is 160 bytes, composed 34 + 84 + 12 + 16 + 14")
        void commareaIs160Bytes() {
            // app/cpy/COCOM01Y.cpy:19 declares the group; its five sub-groups are CDEMO-GENERAL-INFO 34,
            // CDEMO-CUSTOMER-INFO 84, CDEMO-ACCOUNT-INFO 12, CDEMO-CARD-INFO 16 and CDEMO-MORE-INFO 14.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("34 + 84 + 12 + 16 + 14")
                    .isEqualTo(34 + 84 + 12 + 16 + 14)
                    .isEqualTo(160);

            // The sub-groups abut with no gap and no overlap, which is what makes the total meaningful.
            assertThat(NavigationContext.GENERAL_INFO_OFFSET).isZero();
            assertThat(NavigationContext.CUSTOMER_INFO_OFFSET).isEqualTo(34);
            assertThat(NavigationContext.ACCOUNT_INFO_OFFSET).isEqualTo(34 + 84);
            assertThat(NavigationContext.CARD_INFO_OFFSET).isEqualTo(34 + 84 + 12);
            assertThat(NavigationContext.MORE_INFO_OFFSET).isEqualTo(34 + 84 + 12 + 16);

            // And it survives a round trip through the codec at exactly that width, on both code pages.
            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
                byte[] image = NavigationContext.empty().toFixedWidth(codec);
                assertThat(image).hasSize(160);
                assertThat(NavigationContext.fromFixedWidth(codec, image))
                        .isEqualTo(NavigationContext.empty());
            }
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP precedes CDEMO-LAST-MAPSET and both are X(7), not X(8)")
        void lastMapPrecedesLastMapset() {
            // app/cpy/COCOM01Y.cpy:43-44 declares CDEMO-LAST-MAP first and CDEMO-LAST-MAPSET second -
            // the reverse of the order app/cbl/COACTVWC.cbl:346-347 writes them in, which is legal
            // because a MOVE names its receiver rather than relying on position.
            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("CDEMO-LAST-MAP is declared at :43, before CDEMO-LAST-MAPSET at :44")
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_FIELD).isEqualTo("CDEMO-LAST-MAP");
            assertThat(NavigationContext.LAST_MAPSET_FIELD).isEqualTo("CDEMO-LAST-MAPSET");

            // Seven, not eight: a symbolic group name is a 7-character map name plus an I or O suffix,
            // so LIT-THISMAPSET PIC X(8) VALUE 'COACTVW ' loses its trailing space on the way in. The
            // response's own triple takes the same widths from CVCRD01Y.
            assertThat(AccountViewResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isNotEqualTo(AccountViewResponse.NEXT_PROGRAM_LENGTH);
            assertThat(AccountViewResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);

            // MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET [app/cbl/COACTVWC.cbl:346] is an X(8) sending
            // field into an X(7) receiver, so the eighth byte - the literal's trailing space - is
            // discarded on the RIGHT, which is what a COBOL alphanumeric MOVE does. The narrowing is
            // spelled out through movePicX rather than left implicit: NavigationContext refuses an
            // over-long value outright, so the truncation has to be a decision somebody made.
            String moved = asciiCodec.movePicX(AccountViewResponse.THIS_MAPSET,
                    NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(moved)
                    .as("X(8) 'COACTVW ' into X(7) drops the eighth byte")
                    .isEqualTo("COACTVW")
                    .hasSize(7);
            assertThat(NavigationContext.empty().withLastMapset(moved).lastMapset())
                    .isEqualTo("COACTVW")
                    .hasSize(7);
            assertThatIllegalArgumentException()
                    .as("and the un-narrowed literal is rejected rather than silently trimmed")
                    .isThrownBy(() -> NavigationContext.empty()
                            .withLastMapset(AccountViewResponse.THIS_MAPSET))
                    .withMessageContaining("CDEMO-LAST-MAPSET");
        }

        @Test
        @DisplayName("all four COCOM01Y condition names are driven in both states (G50)")
        void allFourConditionNamesInBothStates() {
            // Gate G50. app/cpy/COCOM01Y.cpy:27-28 and :30-31 declare
            //     88 CDEMO-USRTYP-ADMIN VALUE 'A'.   88 CDEMO-USRTYP-USER  VALUE 'U'.
            //     88 CDEMO-PGM-ENTER    VALUE 0.     88 CDEMO-PGM-REENTER  VALUE 1.
            // None is the negation of another - a user type of neither 'A' nor 'U' satisfies neither
            // condition - so each is driven true AND false rather than inferred from its partner.
            assertThat(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            NavigationContext user = NavigationContext.empty().withUserTypeUser();
            NavigationContext neither = NavigationContext.empty().withUserType("X");

            // CDEMO-USRTYP-ADMIN, both states.
            assertThat(admin.isAdmin()).isTrue();
            assertThat(user.isAdmin()).isFalse();
            // CDEMO-USRTYP-USER, both states.
            assertThat(user.isUser()).isTrue();
            assertThat(admin.isUser()).isFalse();
            // Neither, which is the state app/cbl/COACTVWC.cbl:344 SET CDEMO-USRTYP-USER TO TRUE exists
            // to leave behind, and the reason the two conditions are not one boolean.
            assertThat(neither.isAdmin()).isFalse();
            assertThat(neither.isUser()).isFalse();

            // CDEMO-PGM-ENTER and CDEMO-PGM-REENTER, both states, read through the payload as well as
            // through the commarea - app/cbl/COACTVWC.cbl:345 sets ENTER before the XCTL and :581 sets
            // REENTER before sending the map.
            AccountViewResponse enter = AccountViewResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmEnter()).build();
            AccountViewResponse reenter = AccountViewResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter()).build();
            assertThat(enter.getNavigationContext().isEnter()).isTrue();
            assertThat(enter.getNavigationContext().isReenter()).isFalse();
            assertThat(reenter.getNavigationContext().isReenter()).isTrue();
            assertThat(reenter.getNavigationContext().isEnter()).isFalse();
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();
            assertThat(reenter.isReenter()).isTrue();
            assertThat(reenter.isEnter()).isFalse();
        }

        @Test
        @DisplayName("CC-WORK-AREA is 213 bytes, composed 5+8+7+7+75+75+11+16+9")
        void workAreaIs213Bytes() {
            // app/cpy/CVCRD01Y.cpy - CCARD-AID X(5), CCARD-NEXT-PROG X(8), CCARD-NEXT-MAPSET X(7),
            // CCARD-NEXT-MAP X(7), CCARD-ERROR-MSG X(75), CCARD-RETURN-MSG X(75), CC-ACCT-ID X(11),
            // CC-CARD-NUM X(16), CC-CUST-ID X(9).
            assertThat(CardScreenState.CCARD_AID_LENGTH).isEqualTo(5);
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_ERROR_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CCARD_RETURN_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CC_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardScreenState.CC_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardScreenState.CC_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardScreenState.RECORD_LENGTH)
                    .as("5+8+7+7+75+75+11+16+9")
                    .isEqualTo(5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9)
                    .isEqualTo(213);

            for (FixedWidthCodec codec : List.of(asciiCodec, ebcdicCodec)) {
                assertThat(new CardScreenState().toFixedWidth(codec.charset())).hasSize(213);
            }

            // CC-ACCT-ID X(11) is the same width as ACCTSIDO, which is what makes
            // MOVE CC-ACCT-ID TO ACCTSIDO at app/cbl/COACTVWC.cbl:468 a straight move.
            assertThat(CardScreenState.CC_ACCT_ID_LENGTH)
                    .isEqualTo(AccountViewResponse.ACCTSID_LENGTH)
                    .isEqualTo(11);
            // CCARD-ERROR-MSG X(75) is WS-RETURN-MSG's width, and ERRMSGO is 78 - so the message is
            // widened by three spaces on its way to the screen, never truncated.
            assertThat(CardScreenState.CCARD_ERROR_MSG_LENGTH)
                    .isEqualTo(AccountViewResponse.WS_RETURN_MSG_LENGTH)
                    .isEqualTo(75);
            assertThat(AccountViewResponse.ERRMSG_RETURN_MESSAGE_PADDING).isEqualTo(3);
        }

        @Test
        @DisplayName("two payloads share nothing - there is no session for a second request to see (G37)")
        void twoPayloadsShareNothing() {
            // Gate G37, as a positive rather than as an absence. Conversation state travels in the
            // payload, so two payloads built from different commareas must be observably independent -
            // that independence is what statelessness buys, and it is what a server-side session or a
            // static map would destroy.
            NavigationContext firstContext = NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter()
                    .withAcctId(11L)
                    .withToProgram("COADM01C");
            NavigationContext secondContext = NavigationContext.empty()
                    .withUserId("USER0002")
                    .withUserTypeUser()
                    .withPgmEnter()
                    .withAcctId(22L)
                    .withToProgram("COMEN01C");

            AccountViewResponse first = AccountViewResponse.builder()
                    .acctsid("00000000011")
                    .navigationContext(firstContext)
                    .nextProgram(firstContext.toProgram())
                    .build();
            AccountViewResponse second = AccountViewResponse.builder()
                    .acctsid("00000000022")
                    .navigationContext(secondContext)
                    .nextProgram(secondContext.toProgram())
                    .build();

            // Mutating everything mutable on the first payload leaves the second untouched.
            first.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            first.getCardScreenState().setCcardErrorMsg("first payload only");

            assertThat(second.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(second.attributes(ScreenField.ACCTSID).getColour())
                    .isEqualTo(FieldAttributes.UNSET);
            assertThat(second.getCardScreenState().getCcardErrorMsg())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_ERROR_MSG_LENGTH));
            assertThat(second.getAcctsid()).isEqualTo("00000000022");
            assertThat(second.isEnter()).isTrue();
            assertThat(second.getNavigationContext().isUser()).isTrue();
            assertThat(second.getNextProgram()).isEqualTo("COMEN01C");

            // And the first still holds its own state, so nothing was swapped either way.
            assertThat(first.isReenter()).isTrue();
            assertThat(first.getNavigationContext().isAdmin()).isTrue();
            assertThat(first.getNextProgram()).isEqualTo("COADM01C");
            assertThat(first.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
        }
    }

    /**
     * Figures where the source overrules the plan. Each is recorded rather than silently reconciled
     * (practice <strong>B4</strong>), because a plan corrected in silence is a plan nobody can review.
     */
    @Nested
    @DisplayName("Corrections - four figures where the source overrules the plan")
    class SourceContractCorrections {

        @Test
        @DisplayName("CVCRD01Y declares SIXTEEN CCARD-AID conditions, not the fifteen the plan states")
        void sixteenAidConditionsNotFifteen() {
            // The plan for this folder says "CCARD-AID X(5) + 15 AID conditions". The copybook declares
            // SIXTEEN: ENTER, CLEAR, PA1, PA2 and PFK01 through PFK12 - that is 2 + 2 + 12. There is no
            // PA3 condition, which is the likely source of the miscount, but removing PA3 from a list of
            // seventeen leaves sixteen rather than fifteen. Sixteen is what is encoded.
            List<String> declared = List.of(
                    CardScreenState.CCARD_AID_ENTER, CardScreenState.CCARD_AID_CLEAR,
                    CardScreenState.CCARD_AID_PA1, CardScreenState.CCARD_AID_PA2,
                    CardScreenState.CCARD_AID_PFK01, CardScreenState.CCARD_AID_PFK02,
                    CardScreenState.CCARD_AID_PFK03, CardScreenState.CCARD_AID_PFK04,
                    CardScreenState.CCARD_AID_PFK05, CardScreenState.CCARD_AID_PFK06,
                    CardScreenState.CCARD_AID_PFK07, CardScreenState.CCARD_AID_PFK08,
                    CardScreenState.CCARD_AID_PFK09, CardScreenState.CCARD_AID_PFK10,
                    CardScreenState.CCARD_AID_PFK11, CardScreenState.CCARD_AID_PFK12);
            assertThat(declared)
                    .as("2 + 2 + 12 = 16 condition names, and the plan's 15 is wrong")
                    .hasSize(16)
                    .doesNotHaveDuplicates();

            // Every token is exactly five characters, because CCARD-AID is PIC X(5) and a VALUE literal
            // shorter than its item is space-padded: 'PA1' is stored as 'PA1  '. That padding is part of
            // the value a comparison sees, so it is asserted rather than trimmed away.
            assertThat(declared).allSatisfy(token -> assertThat(token).hasSize(5));
            assertThat(CardScreenState.CCARD_AID_PA1).isEqualTo("PA1  ");
            assertThat(CardScreenState.CCARD_AID_PA2).isEqualTo("PA2  ");
            assertThat(CardScreenState.CCARD_AID_ENTER).isEqualTo("ENTER");
            assertThat(CardScreenState.CCARD_AID_CLEAR).isEqualTo("CLEAR");
            assertThat(declared).doesNotContain("PA3  ", "PA3");

            // Both states of each condition, on the carrier the payload actually holds.
            CardScreenState area = new CardScreenState();
            area.setCcardAid(CardScreenState.CCARD_AID_ENTER);
            assertThat(area.isCcardAidEnter()).isTrue();
            assertThat(area.isCcardAidClear()).isFalse();
            area.setCcardAid(CardScreenState.CCARD_AID_PA1);
            assertThat(area.isCcardAidPa1()).isTrue();
            assertThat(area.isCcardAidPa2()).isFalse();
            assertThat(area.isCcardAidEnter()).isFalse();
        }

        @Test
        @DisplayName("COACTVW declares no FKEYS, FKEY05 or FKEY12 - those belong to COACTUP")
        void noFunctionKeyFields() {
            // grep -c FKEY app/bms/COACTVW.bms is 0. The plan's field inventory for the update screen
            // lists function-key fields; this screen has none, and asserting their absence is what stops
            // one being added here by analogy. The bottom line of CAVW is ERRMSGO at POS=(23,1) and
            // nothing follows it.
            assertThat(LABELS).noneMatch(label -> label.startsWith("FKEY"));
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.label())
                        .as("%s must not be a function-key field", field)
                        .doesNotStartWith("FKEY");
            }
            for (String absent : List.of("FKEYS", "FKEY05", "FKEY12")) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> ScreenField.byLabel(absent))
                        .withMessageContaining("no name-labelled DFHMDF");
            }
            assertThat(ScreenField.ERRMSG.screenRow())
                    .as("ERRMSGO is the last field, at row 23 of the 24-row screen")
                    .isEqualTo(23);
            assertThat(ScreenField.ERRMSG.endOffsetExclusive())
                    .as("and its data ends exactly on the group's 955th byte")
                    .isEqualTo(955);
        }

        @Test
        @DisplayName("the XCTL statement begins at :349 - :350 is its PROGRAM continuation line")
        void theXctlStatementBeginsAt349() {
            // The plan cites app/cbl/COACTVWC.cbl:350 for this program's XCTL. That line carries
            // PROGRAM (CDEMO-TO-PROGRAM); the statement itself opens with EXEC CICS XCTL on :349 and
            // closes with COMMAREA(CARDDEMO-COMMAREA) on :351. It is the ONLY live XCTL in the program -
            // the other mention, at :326, is a comment. What the Java form asserts is the consequence:
            // the target is echoed into the response for the client to follow, and the server keeps no
            // forward, no redirect chain and no session affinity.
            // :346 moves LIT-THISMAPSET PIC X(8) into CDEMO-LAST-MAPSET PIC X(7), so the literal is
            // narrowed on the right before it is stored - see CarriedAreas#lastMapPrecedesLastMapset.
            NavigationContext commarea = NavigationContext.empty()
                    .withToProgram("COMEN01C")
                    .withLastMapset(asciiCodec.movePicX(AccountViewResponse.THIS_MAPSET,
                            NavigationContext.LAST_MAPSET_LENGTH))
                    .withLastMap(AccountViewResponse.MAP_NAME);
            AccountViewResponse response = AccountViewResponse.builder()
                    .navigationContext(commarea)
                    .nextProgram(commarea.toProgram())
                    .nextMapset(commarea.lastMapset())
                    .nextMap(commarea.lastMap())
                    .build();

            assertThat(response.getNextProgram())
                    .as("PROGRAM (CDEMO-TO-PROGRAM) at :350, echoed rather than acted on")
                    .isEqualTo("COMEN01C")
                    .hasSize(AccountViewResponse.NEXT_PROGRAM_LENGTH);
            // :346-347 MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET / MOVE LIT-THISMAP TO CDEMO-LAST-MAP,
            // performed immediately before the XCTL so the target knows where it came from.
            assertThat(response.getNextMapset()).isEqualTo("COACTVW").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("CACTVWA").hasSize(7);
            assertThat(response.getNavigationContext().lastMapset()).isEqualTo("COACTVW");
            assertThat(response.getNavigationContext().lastMap()).isEqualTo("CACTVWA");
        }

        @ParameterizedTest(name = "[{index}] CDEMO-FROM-PROGRAM=[{0}] -> nextProgram={1}")
        @CsvSource(delimiter = '|', textBlock = """
                LOW-VALUES  | COMEN01C
                SPACES      | COMEN01C
                COCRDLIC    | COCRDLIC
                """)
        @DisplayName("the PF3 fallback: LOW-VALUES or SPACES means the menu, anything else goes back")
        void theCallerFallbackCombinations(String fromProgramState, String expectedTarget) {
            // app/cbl/COACTVWC.cbl:334-339, inside WHEN CCARD-AID-PFK03:
            //     IF CDEMO-FROM-PROGRAM EQUAL LOW-VALUES
            //     OR CDEMO-FROM-PROGRAM EQUAL SPACES
            //        MOVE LIT-MENUPGM        TO CDEMO-TO-PROGRAM      ('COMEN01C', :168-169)
            //     ELSE
            //        MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
            //     END-IF
            // A compound OR over TWO figurative constants, so BOTH fallback operands are driven here
            // and not just the one a caller happens to send. The distinction matters because the two
            // are different bytes - X'00' and X'40'/X'20' - and only the OR makes them equivalent.
            String fromProgram = switch (fromProgramState) {
                case "LOW-VALUES" -> CardScreenState.lowValues(NavigationContext.FROM_PROGRAM_LENGTH);
                case "SPACES" -> CardScreenState.spaces(NavigationContext.FROM_PROGRAM_LENGTH);
                default -> fromProgramState;
            };
            boolean fallsBackToMenu =
                    fromProgram.equals(CardScreenState.lowValues(NavigationContext.FROM_PROGRAM_LENGTH))
                            || fromProgram.isBlank();
            String toProgram = fallsBackToMenu ? "COMEN01C" : fromProgram;

            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram(fromProgram)
                    .withToProgram(toProgram)
                    // :341-342 MOVE LIT-THISTRANID/LIT-THISPGM TO CDEMO-FROM-*, so the target learns
                    // who transferred to it - performed after the fallback decision, not before.
                    .withFromTranid(AccountViewResponse.THIS_TRANID)
                    // :344-345 SET CDEMO-USRTYP-USER TO TRUE / SET CDEMO-PGM-ENTER TO TRUE.
                    .withUserTypeUser()
                    .withPgmEnter();

            AccountViewResponse response = AccountViewResponse.builder()
                    .navigationContext(commarea)
                    .nextProgram(commarea.toProgram())
                    .build();

            assertThat(response.getNextProgram())
                    .as("PROGRAM (CDEMO-TO-PROGRAM) at :350 for CDEMO-FROM-PROGRAM=%s", fromProgramState)
                    .isEqualTo(expectedTarget)
                    .hasSize(AccountViewResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.getNavigationContext().isUser()).isTrue();
            assertThat(response.isEnter()).isTrue();
            assertThat(response.getNavigationContext().fromTranid()).isEqualTo("CAVW");
        }

        @Test
        @DisplayName("the X(40) title and the X(50) message are never substituted for one another")
        void titlesAndMessagesAreDifferentContracts() {
            // Two thank-you texts exist in this system and they are not interchangeable:
            // CCDA-THANK-YOU is PIC X(40) in app/cpy/COTTL01Y.cpy and names the CCDA application;
            // CCDA-MSG-THANK-YOU is PIC X(50) in app/cpy/CSMSG01Y.cpy and names CardDemo. Different
            // text, different width, different owner. Substituting one for the other would change bytes
            // on the screen, so the difference is asserted rather than trusted.
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("the X(40) title names CCDA")
                    .contains("CCDA application")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("the X(50) message names CardDemo")
                    .contains("CardDemo application")
                    .doesNotContain("CCDA application");

            // The two title lines this screen does use are both exactly 40, which is why TITLE01O and
            // TITLE02O are declared PIC X(40) at app/cpy-bms/COACTVW.CPY:254 and :272.
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(AccountViewResponse.TITLE01_LENGTH)
                    .isEqualTo(AccountViewResponse.TITLE02_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(40);

            // A 50-byte standard message into the 78-byte ERRMSGO is PADDED, not truncated - 28 spaces
            // of headroom - while the same message into the 45-byte INFOMSGO would lose five bytes,
            // which is why the two receivers are not interchangeable either.
            assertThat(AccountViewResponse.ERRMSG_STANDARD_MESSAGE_PADDING)
                    .as("78 - 50")
                    .isEqualTo(AccountViewResponse.ERRMSG_LENGTH - SystemMessages.MESSAGE_LENGTH)
                    .isEqualTo(28);
            assertThat(AccountViewResponse.INFOMSG_STANDARD_MESSAGE_TRUNCATION)
                    .as("50 - 45")
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH - AccountViewResponse.INFOMSG_LENGTH)
                    .isEqualTo(5);
            assertThat(AccountViewResponse.builder()
                    .errmsg(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .build()
                    .getErrmsg())
                    .as("the message is widened on the right, and nothing is lost")
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }
    }

    /**
     * The gates that are satisfied by an <em>absence</em>, plus the suite's guard on itself.
     *
     * <p>An absence cannot be demonstrated by exercising behaviour, so these walk the type reflectively.
     * Type NAMES are matched rather than the types themselves, for two reasons: the assertion then holds
     * even where the servlet or Spring Web classes are absent from the test classpath, and it keeps this
     * file's imports inside its declared dependency set.
     */
    @Nested
    @DisplayName("Structural guards - the gates satisfied by an absence")
    class StructuralGuards {

        /** The type and its three nested types, which every guard walks together. */
        private List<Class<?>> typeAndNestedTypes() {
            return List.of(AccountViewResponse.class, ScreenField.class, FieldAttributes.class,
                    Builder.class);
        }

        @Test
        @DisplayName("nothing holds server-side state - no session, no request, no thread local (G37)")
        void nothingHoldsServerSideState() {
            List<String> forbidden = List.of("HttpSession", "HttpServletRequest", "HttpServletResponse",
                    "ThreadLocal", "SessionAttributes", "SessionAttribute", "SessionStatus", "WebSession",
                    "RequestContextHolder", "SecurityContext", "Authentication");

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
                    for (Class<?> parameter : method.getParameterTypes()) {
                        String taken = parameter.getName();
                        assertThat(forbidden)
                                .as("%s.%s takes %s", type.getSimpleName(), method.getName(), taken)
                                .noneMatch(taken::contains);
                    }
                }
            }
        }

        @Test
        @DisplayName("no double and no float anywhere in the type or its nested types (G22)")
        void noBinaryFloatingPoint() {
            // Gate G22 and rule R4. Every PIC 9...V... field in this system is BigDecimal at its declared
            // scale; a double or a float would break numeric parity outright, because neither can
            // represent a decimal fraction exactly. BigDecimal itself is expected here - the five money
            // items are rendered from one - so it is emphatically NOT on this list.
            List<Class<?>> banned = List.of(double.class, float.class, Double.class, Float.class,
                    double[].class, float[].class);

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

            // The money route is BigDecimal end to end: in as BigDecimal, out as a String image.
            assertThat(AccountViewResponse.class.getDeclaredMethods())
                    .filteredOn(method -> "editAmount".equals(method.getName()))
                    .singleElement()
                    .satisfies(method -> {
                        assertThat(method.getParameterTypes()).containsExactly(BigDecimal.class);
                        assertThat(method.getReturnType()).isEqualTo(String.class);
                    });
        }

        @Test
        @DisplayName("the only rounding mode in play is DOWN, at scale 2 (G23, G24)")
        void theOnlyRoundingModeIsDown() {
            // Gates G23 and G24. The keyword ROUNDED appears ZERO times in all 28 COBOL programs, so
            // COBOL truncates excess fractional digits on store - which makes RoundingMode.DOWN the only
            // faithful choice and HALF_UP, HALF_EVEN, CEILING and FLOOR all wrong. The policy lives in
            // one place, CobolDecimal, and this payload routes through it rather than restating it.
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isEqualTo(RoundingMode.DOWN)
                    .isNotIn(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.CEILING,
                            RoundingMode.FLOOR, RoundingMode.UP);
            assertThat(AccountViewResponse.AMOUNT_FRACTION_DIGITS)
                    .as("the mask's forced .99 is the same scale as the store")
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);

            // The divergence case, so a rounding regression fails loudly rather than silently: 1.235
            // renders 1.23 under DOWN and would render 1.24 under HALF_UP.
            assertThat(AccountViewResponse.editAmount(new BigDecimal("1.235")))
                    .as("DOWN truncates the third decimal; HALF_UP would carry into the second")
                    .isEqualTo("+          1.23")
                    .isNotEqualTo("+          1.24");
            assertThat(new BigDecimal("1.235").setScale(2, RoundingMode.DOWN))
                    .as("and the two modes genuinely disagree on this value")
                    .isNotEqualTo(new BigDecimal("1.235").setScale(2, RoundingMode.HALF_UP));
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("1.235")))
                    .isEqualTo(new BigDecimal("1.23"));
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("-1.235")))
                    .as("truncation is toward zero, so a negative value truncates upward in magnitude")
                    .isEqualTo(new BigDecimal("-1.23"));

            // Every rendered money image carries exactly two fraction digits, on all five items.
            for (String label : MASKED_LABELS) {
                ScreenField field = ScreenField.byLabel(label);
                String rendered = AccountViewResponse.editAmount(new BigDecimal("1.239"));
                assertThat(rendered)
                        .as("%s at scale %d", field, CobolDecimal.MONETARY_SCALE)
                        .hasSize(field.length())
                        .endsWith(".23");
            }
        }

        @Test
        @DisplayName("this test class itself holds no mutable static state (G53)")
        void theSuiteItselfKeepsNoMutableStaticState() {
            // Gate G53 and practice B9, turned on the test rather than on the subject. A suite whose
            // expectations live in a mutable static table can be corrupted by whichever test runs first,
            // and the resulting failure points anywhere but at the cause. Every table here is an
            // unmodifiable List, and both codecs are per-instance fields rebuilt by @BeforeEach.
            //
            // A static final ARRAY would satisfy the compiler and fail this assertion, which is the whole
            // reason the tables are Lists.
            for (Field field : AccountViewResponseTest.class.getDeclaredFields()) {
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
                    .isThrownBy(() -> MASKED_LABELS.set(0, "TAMPERED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> ATTRIBUTE_SUFFIXES.set(0, "Z"));

            // And the per-test codecs are genuinely present, distinct, and over the charsets named.
            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
            assertThat(asciiCodec).isNotSameAs(ebcdicCodec);
        }

        @Test
        @DisplayName("every transcription table is 37 long and lines up field for field (G52)")
        void theTranscriptionTablesAgreeOnThirtySeven() {
            // Gate G52 is a source-level property - no "import ...*;" line - enforced by review and by
            // the explicit import block at the head of this file. What is assertable at runtime is the
            // reason G52 exists: that every name in play is one specific, named type and every table
            // lines up field for field.
            assertThat(LABELS).hasSize(37).doesNotHaveDuplicates();
            assertThat(WIDTHS).hasSize(37);
            assertThat(COPYBOOK_LINES).hasSize(37).doesNotHaveDuplicates();
            assertThat(MAPSET_LINES).hasSize(37).doesNotHaveDuplicates();
            assertThat(SCREEN_ROWS).hasSize(37);
            assertThat(SCREEN_COLUMNS).hasSize(37);
            assertThat(DATA_OFFSETS).hasSize(37).doesNotHaveDuplicates();
            assertThat(MASKED_LABELS).hasSize(5).doesNotHaveDuplicates();
            assertThat(ATTRIBUTE_SUFFIXES).hasSize(4).doesNotHaveDuplicates();
            assertThat(ScreenField.values()).hasSize(37);
            assertThat(AccountViewResponse.FIELD_COUNT).isEqualTo(37);
            assertThat(AccountViewResponse.AMOUNT_MASK_FIELD_COUNT).isEqualTo(MASKED_LABELS.size());
            assertThat(LABELS).containsAll(MASKED_LABELS);

            // 32 plain PIC X(n) items and 5 numeric-edited ones, which must account for all 37.
            List<ScreenField> plain = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                if (!field.isNumericEdited()) {
                    plain.add(field);
                }
            }
            assertThat(plain)
                    .as("37 - 5 = 32 items get no mask treatment at all")
                    .hasSize(32)
                    .allSatisfy(field -> assertThat(field.picture())
                            .startsWith("X(")
                            .endsWith(")")
                            .isEqualTo("X(" + field.length() + ")"));
        }
    }

}
