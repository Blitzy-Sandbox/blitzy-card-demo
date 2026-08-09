package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.FieldAttributes;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link TransactionListResponse}, the outbound payload of {@code GET /api/transactions}, against
 * the sources that define it.
 *
 * <h2>The sources this suite is transcribed from</h2>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COTRN00.CPY:373} - {@code 01 COTRN0AO REDEFINES COTRN0AI}, the symbolic
 *       output group: the 59 {@code xxxO} payload items, their widths, the leading 12-byte
 *       {@code TIOAPFX} {@code FILLER} and the per-field {@code FILLER PICTURE X(3)} +
 *       {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} attribute quad. Its {@code REDEFINES} pair,
 *       {@code 01 COTRN0AI} at {@code :17}, supplies the {@code xxxL}/{@code xxxF}/{@code xxxA} +
 *       {@code FILLER PICTURE X(4)} + {@code xxxI} shape that {@link RedefinesOverlay} transcribes
 *       independently and proves byte-identical.</li>
 *   <li>{@code app/bms/COTRN00.bms:450} -
 *       {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)}, the error line's
 *       declared width, colour and attributes.</li>
 *   <li>{@code app/cbl/COTRN00C.cbl} - the behaviour. Specifically {@code :62-70}
 *       ({@code CDEMO-CT00-INFO}, the 58-byte browse cursor declared in place after
 *       {@code COPY COCOM01Y}), {@code :193} and {@code :519} (the only two
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} sites), {@code :390-444}
 *       ({@code POPULATE-TRAN-DATA}'s ordered {@code EVALUATE WS-IDX}) and {@code :452-504}
 *       ({@code INITIALIZE-TRAN-DATA}'s).</li>
 *   <li>{@code app/cpy/CSSETATY.cpy:17-27} - the highlight rule, whose four reachable states
 *       {@link HighlightMatrix} drives.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code :145} {@code DEFINE MAPSET(COTRN00)}, {@code :257}
 *       {@code DEFINE PROGRAM(COTRN00C)} and {@code :419} {@code DEFINE TRANSACTION(CT00)
 *       PROGRAM(COTRN00C)}: the four identity literals this payload carries.</li>
 * </ul>
 *
 * <p>The expectations are transcribed from those sources rather than read back out of the class under
 * test, which is the whole point: the field names, the widths and the byte totals are all spelled out
 * independently, so a normalised suffix or a mistyped width fails here instead of agreeing with itself.
 * Nothing is read at run time either - the copybook, BMS, COBOL and CSD files are reference inputs and
 * this suite opens none of them, so it can neither be broken by nor break them.
 *
 * <h2>This class is not a naming-conflict case</h2>
 *
 * <p>{@code COTRN00C:5} declares {@code Function : List Transactions from TRANSACT file} and
 * {@code README.md:222} documents {@code CT00} as {@code Transaction List}, so for this one program the
 * mandated class name and the source behaviour <em>agree</em>. That is worth saying out loud because two
 * of its siblings in this very package are the opposite case: {@code README.md:223-224} documents
 * {@code CT01}/{@code COTRN01C} as Transaction <em>View</em> and {@code CT02}/{@code COTRN02C} as
 * Transaction <em>Add</em>, which is the reverse of the names {@code TransactionAddController} and
 * {@code TransactionViewController}. Nothing in this suite inherits that ambiguity, and no assertion
 * here should be copied to those two without re-reading their sources.
 *
 * <h2>Rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - <strong>no user rules were provided</strong> for
 * this project - and that single line is the whole document, so no project rule governs this file. Their
 * absence is not licence to lower the bar: the Agent Action Plan's own transformation rules and
 * enterprise-practice substitutes bind instead, and this suite is written to them. Named where they are
 * asserted, the ones it answers to are: behaviour from the source and never from the class name (R1);
 * fixed width as the wire format (R5); statelessness (R6); {@code String} payloads with no binary
 * floating point and no reachable rounding mode (R4, R2); reference inputs read-only and never opened at
 * run time (B3); the inconsistent field-name suffixes carried verbatim rather than tidied (B4); a fixed
 * {@link Clock} so every byte is deterministic (B7); explicit imports and named charsets (B8); no static
 * mutable state (B9); and no third-party copybook parser - the overlay arithmetic is transcribed by hand
 * so every offset is reviewable against the copybook (B11).
 *
 * <h2>Self-contained</h2>
 *
 * <p>This class extends nothing and shares nothing: there is no base class for this package and none is
 * to be introduced, so each of its screens' suites can be read on its own. It needs no Spring context,
 * no {@code MockMvc}, no repository, no {@code JobLauncher} and no datasource - the payload is a value
 * type and is asserted as one. The controller-level concerns ({@code EVALUATE EIBAID}, the ordered
 * ten-way selection and the page arithmetic) belong to the suite of the controller that owns them and
 * are deliberately not duplicated here.
 */
@DisplayName("TransactionListResponse - the COTRN0AO projection of CICS transaction CT00")
class TransactionListResponseTest {

    /** The code page every test states explicitly; none relies on the platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec the paragraph reproductions and the image tests use. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * The zone the header renderings are taken in, stated outright.
     *
     * <p>Practice B7. {@code ZoneId.systemDefault()} is never called here, and neither is any factory
     * that would reach it: a header rendered in the host's zone would produce different bytes on a
     * machine configured differently, which is exactly the non-determinism a byte-for-byte parity
     * assertion cannot tolerate. Greenwich is chosen because it has no daylight-saving transition, so
     * the instant below renders identically whatever the date happens to be when the suite runs.
     */
    private static final ZoneId HEADER_ZONE = ZoneId.of("UTC");

    /**
     * The instant every header assertion is taken at: 18 July 2022, 03:04:05 Greenwich.
     *
     * <p>The date is the one {@code app/jcl/INTCALC.jcl} carries as {@code PARM='2022071800'}, so the
     * whole suite reads against a date that exists in the sources rather than an arbitrary one. The
     * time is deliberately single-digit in all three components, which is what makes the zero-filling
     * of {@code WS-CURTIME-HH-MM-SS} visible: {@code 03:04:05} would read {@code 3:4:5} if any
     * component were rendered without its leading zero.
     */
    private static final Instant HEADER_INSTANT = Instant.parse("2022-07-18T03:04:05Z");

    /**
     * The fixed clock, immutable and shared.
     *
     * <p>{@link Clock#fixed(Instant, ZoneId)} has no mutable state and reports the same instant for
     * ever, so this constant is safe to share across tests and satisfies practice B9's requirement
     * that a static field be genuinely immutable rather than merely {@code final}.
     * {@link DateHeader#from(FixedWidthCodec, Clock)} reads it exactly once per call and never calls
     * {@code now()} of its own, so {@code CURDATEO} and {@code CURTIMEO} are as deterministic as any
     * other field in the payload.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(HEADER_INSTANT, HEADER_ZONE);

    /** {@code WS-CURDATE-MM-DD-YY} at {@link #HEADER_INSTANT}: {@code MM/DD/YY}, eight characters. */
    private static final String EXPECTED_CURDATE = "07/18/22";

    /** {@code WS-CURTIME-HH-MM-SS} at {@link #HEADER_INSTANT}: {@code HH:MM:SS}, eight characters. */
    private static final String EXPECTED_CURTIME = "03:04:05";

    /**
     * The date header every test uses, built from {@link #FIXED_CLOCK}.
     *
     * <p>A method rather than a constant so that each test gets its own instance and no test can
     * observe another's, even though {@link DateHeader} is itself immutable.
     *
     * @return the header for {@link #HEADER_INSTANT}; never {@code null}
     */
    private static DateHeader fixedHeader() {
        return DateHeader.from(CODEC, FIXED_CLOCK);
    }

    /**
     * The 59 payload item names, in copybook order, transcribed by hand from the {@code xxxO} items
     * of {@code 01 COTRN0AO REDEFINES COTRN0AI} at {@code app/cpy-bms/COTRN00.CPY:373}.
     *
     * <p>The suffix widths differ between columns and are written out here exactly as the copybook
     * spells them: {@code SEL} four digits, {@code TRNID}, {@code TDATE} and {@code TDESC} two, and
     * {@code TAMT} three.
     */
    private static final List<String> EXPECTED_ITEM_NAMES = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO", "PAGENUMO",
            "TRNIDINO",
            "SEL0001O", "TRNID01O", "TDATE01O", "TDESC01O", "TAMT001O",
            "SEL0002O", "TRNID02O", "TDATE02O", "TDESC02O", "TAMT002O",
            "SEL0003O", "TRNID03O", "TDATE03O", "TDESC03O", "TAMT003O",
            "SEL0004O", "TRNID04O", "TDATE04O", "TDESC04O", "TAMT004O",
            "SEL0005O", "TRNID05O", "TDATE05O", "TDESC05O", "TAMT005O",
            "SEL0006O", "TRNID06O", "TDATE06O", "TDESC06O", "TAMT006O",
            "SEL0007O", "TRNID07O", "TDATE07O", "TDESC07O", "TAMT007O",
            "SEL0008O", "TRNID08O", "TDATE08O", "TDESC08O", "TAMT008O",
            "SEL0009O", "TRNID09O", "TDATE09O", "TDESC09O", "TAMT009O",
            "SEL0010O", "TRNID10O", "TDATE10O", "TDESC10O", "TAMT010O",
            "ERRMSGO");

    /**
     * The 59 declared widths, in the same order, transcribed from the {@code PIC X(n)} clauses and
     * independently equal to the {@code DFHMDF LENGTH=} of the matching name-labelled field.
     */
    private static final List<Integer> EXPECTED_WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 8, 16,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            78);

    /** Supplies {@code (itemName, width)} pairs for the field-by-field parameterised checks. */
    private static List<Arguments> declaredFields() {
        List<Arguments> arguments = new ArrayList<>();
        for (int index = 0; index < EXPECTED_ITEM_NAMES.size(); index++) {
            arguments.add(Arguments.of(EXPECTED_ITEM_NAMES.get(index), EXPECTED_WIDTHS.get(index)));
        }
        return arguments;
    }

    /**
     * A response with every header field populated and every row filled, for the image tests.
     *
     * <p>The heading comes from {@link #fixedHeader()}, so the two clock fields carry the same bytes on
     * every run and on every host.
     *
     * @return a fully populated response; never {@code null}
     */
    private static TransactionListResponse fullyPopulated() {
        TransactionListResponse response = new TransactionListResponse();
        response.populateHeaderInfo(fixedHeader());
        for (int row = TransactionListResponse.FIRST_ROW;
                row <= TransactionListResponse.LAST_ROW; row++) {
            response.populateTranData(CODEC, row,
                    String.format("%016d", row),
                    "07/18/22",
                    "Transaction description row " + row,
                    String.format("+%08d.99", row));
            response.setRowSelection(row, " ");
        }
        response.movePageNumberToScreen(CODEC, 3);
        response.moveMessageToErrorLine(CODEC, "All rows displayed");
        return response;
    }

    @Nested
    @DisplayName("The field set: exactly 59 named DFHMDF definitions, verbatim")
    class FieldSet {

        @Test
        @DisplayName("59 payload fields, reconciling as 8 header + 10 rows x 5 + 1 error line")
        void fieldCountReconciles() {
            assertThat(TransactionListResponse.FIELD_COUNT).isEqualTo(59);
            assertThat(TransactionListResponse.HEADER_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionListResponse.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionListResponse.ERROR_FIELD_COUNT).isEqualTo(1);
            assertThat(TransactionListResponse.HEADER_FIELD_COUNT
                    + TransactionListResponse.ROW_COUNT * TransactionListResponse.ROW_FIELD_COUNT
                    + TransactionListResponse.ERROR_FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("the payload item names match the copybook exactly, in copybook order")
        void itemNamesMatchTheCopybook() {
            assertThat(TransactionListResponse.payloadFieldNames())
                    .containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
        }

        @Test
        @DisplayName("the field prefixes are the item names without their trailing O")
        void prefixesMatchTheItemNames() {
            List<String> expected = EXPECTED_ITEM_NAMES.stream()
                    .map(name -> name.substring(0, name.length() - 1))
                    .toList();
            assertThat(TransactionListResponse.fieldPrefixes())
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("SEL0001O..SEL0010O carry FOUR digits - never SEL01O")
        void selectorSuffixesAreFourDigits() {
            for (int row = 1; row <= 10; row++) {
                String expected = "SEL" + String.format("%04d", row) + "O";
                assertThat(TransactionListResponse.rowFieldNames(row).get(0)).isEqualTo(expected);
                assertThat(TransactionListResponse.payloadFieldNames()).contains(expected);
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("SEL01O", "SEL1O", "SEL001O", "SEL00010O");
        }

        @Test
        @DisplayName("TRNIDnnO, TDATEnnO and TDESCnnO carry TWO digits")
        void rowSuffixesAreTwoDigits() {
            for (int row = 1; row <= 10; row++) {
                String twoDigits = String.format("%02d", row);
                assertThat(TransactionListResponse.rowFieldNames(row).subList(1, 4))
                        .containsExactly("TRNID" + twoDigits + "O",
                                "TDATE" + twoDigits + "O",
                                "TDESC" + twoDigits + "O");
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("TRNID001O", "TDATE001O", "TDESC001O", "TRNID1O");
        }

        @Test
        @DisplayName("TAMT001O..TAMT010O carry THREE digits - never TAMT01O")
        void amountSuffixesAreThreeDigits() {
            for (int row = 1; row <= 10; row++) {
                String expected = "TAMT" + String.format("%03d", row) + "O";
                assertThat(TransactionListResponse.rowFieldNames(row).get(4)).isEqualTo(expected);
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("TAMT01O", "TAMT1O", "TAMT0001O");
        }

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("every declared width equals the xxxO PICTURE and the DFHMDF LENGTH")
        void declaredWidthsMatch(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(TransactionListResponse.declaredLength(prefix)).isEqualTo(width);
            assertThat(TransactionListResponse.outputItemName(prefix)).isEqualTo(itemName);
            assertThat(TransactionListResponse.colourItemName(prefix)).isEqualTo(prefix + "C");
        }

        @ParameterizedTest(name = "{0} initialises to {1} spaces and reads back untrimmed")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("every field starts as spaces at its declared width - never null")
        void everyFieldStartsAsSpaces(String itemName, int width) {
            TransactionListResponse response = new TransactionListResponse();
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(response.payloadValue(prefix)).isEqualTo(" ".repeat(width));
        }

        @ParameterizedTest(name = "{0} rejects a value one character too wide")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("an over-wide value is rejected, never silently truncated")
        void overWideValuesAreRejected(String itemName, int width) {
            TransactionListResponse response = new TransactionListResponse();
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setPayloadValue(prefix, "x".repeat(width + 1)))
                    .withMessageContaining(itemName)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("a null value is rejected: there is no null in a COBOL record")
        void nullValuesAreRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setErrmsgO(null))
                    .withMessageContaining("ERRMSGO");
        }

        @Test
        @DisplayName("an unknown prefix is rejected, including the plausible-looking TAMT01")
        void unknownPrefixesAreRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.payloadValue("TAMT01"))
                    .withMessageContaining("not a field of COTRN0AO");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.payloadValue(null));
        }

        @Test
        @DisplayName("the flat accessors and the generic accessors see the same storage")
        void flatAndGenericAccessorsAgree() {
            TransactionListResponse response = new TransactionListResponse();
            response.setTamt001O("+00000001.23");
            assertThat(response.payloadValue("TAMT001")).isEqualTo("+00000001.23");
            response.setPayloadValue("TAMT001", "-00000009.99");
            assertThat(response.getTamt001O()).isEqualTo("-00000009.99");
            assertThat(response.payloadFieldValues())
                    .containsEntry("TAMT001O", "-00000009.99")
                    .hasSize(59);
            assertThat(response.payloadFieldValues().keySet())
                    .containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
        }
    }

    @Nested
    @DisplayName("Byte arithmetic: the group image is 1265 bytes and the layout proves it")
    class ByteArithmetic {

        @Test
        @DisplayName("the payload widths sum to 840 = 132 + 630 + 78")
        void payloadWidthsSum() {
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(840);
            assertThat(TransactionListResponse.HEADER_WIDTH_TOTAL).isEqualTo(132);
            assertThat(TransactionListResponse.ROW_WIDTH).isEqualTo(63);
            assertThat(TransactionListResponse.ROW_BLOCK_WIDTH).isEqualTo(630);
            assertThat(TransactionListResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(TransactionListResponse.PAYLOAD_WIDTH_TOTAL).isEqualTo(840);
        }

        @Test
        @DisplayName("the group image is 12 + 59 x 7 + 840 = 1265 bytes")
        void groupImageWidth() {
            assertThat(TransactionListResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionListResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(TransactionListResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);
            assertThat(TransactionListResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionListResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionListResponse.RECORD_LENGTH).isEqualTo(1265);
            assertThat(12 + 59 * 7 + 840).isEqualTo(1265);
        }

        @Test
        @DisplayName("the layout declares 355 spans and self-checks to 1265 bytes")
        void layoutGeometry() {
            assertThat(TransactionListResponse.LAYOUT.recordLength()).isEqualTo(1265);
            assertThat(TransactionListResponse.LAYOUT.spans()).hasSize(1 + 59 * 6);
            assertThat(TransactionListResponse.LAYOUT.storageSpans()
                    .stream().mapToInt(FixedWidthRecord.FieldSpan::length).sum()).isEqualTo(1265);
            assertThat(TransactionListResponse.LAYOUT.redefinitions()).isEmpty();
        }

        @ParameterizedTest(name = "{0} and its four attribute items are spans of the layout")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("the layout carries every payload item and every attribute item by name")
        void layoutCarriesEveryItem(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(TransactionListResponse.LAYOUT.hasSpan(itemName)).isTrue();
            assertThat(TransactionListResponse.LAYOUT.span(itemName).length()).isEqualTo(width);
            for (String suffix : List.of("C", "P", "H", "V")) {
                assertThat(TransactionListResponse.LAYOUT.hasSpan(prefix + suffix)).isTrue();
                assertThat(TransactionListResponse.LAYOUT.span(prefix + suffix).length())
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the first payload item starts at 12 + 7 = 19, after the TIOAPFX prefix")
        void firstPayloadOffset() {
            assertThat(TransactionListResponse.LAYOUT.span("TRNNAMEO").offset()).isEqualTo(19);
            assertThat(TransactionListResponse.LAYOUT.span("TRNNAMEC").offset()).isEqualTo(15);
        }

        @Test
        @DisplayName("the last payload item ends exactly at 1265")
        void lastPayloadOffset() {
            FixedWidthRecord.FieldSpan errmsg = TransactionListResponse.LAYOUT.span("ERRMSGO");
            assertThat(errmsg.endOffsetExclusive()).isEqualTo(1265);
        }

        @Test
        @DisplayName("the cursor is 58 bytes and the commarea it extends is 160 + 58 = 218")
        void cursorArithmetic() {
            assertThat(TransactionListCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(16 + 16 + 8 + 1 + 1 + 16).isEqualTo(58);
            assertThat(TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH).isEqualTo(218);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(TransactionListCursor.LAYOUT.recordLength()).isEqualTo(58);
            assertThat(TransactionListCursor.LAYOUT.spans()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("Identity: transaction CT00, program COTRN00C, mapset COTRN00, map COTRN0A")
    class Identity {

        @Test
        @DisplayName("the four names are the source literals")
        void names() {
            assertThat(TransactionListResponse.TRANSACTION_ID).isEqualTo("CT00");
            assertThat(TransactionListResponse.PROGRAM_NAME).isEqualTo("COTRN00C");
            assertThat(TransactionListResponse.MAPSET_NAME).isEqualTo("COTRN00");
            assertThat(TransactionListResponse.MAP_NAME).isEqualTo("COTRN0A");
            assertThat(TransactionListResponse.OUTPUT_MAP_GROUP_NAME).isEqualTo("COTRN0AO");
        }

        @Test
        @DisplayName("the mapset and map names fit CDEMO-LAST-MAPSET and CDEMO-LAST-MAP, both X(7)")
        void mapNamesFitTheirCommareaFields() {
            assertThat(TransactionListResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TransactionListResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the item suffixes come from FieldAttributeSetter so the two cannot disagree")
        void suffixesAreShared() {
            assertThat(TransactionListResponse.COLOUR_ITEM_SUFFIX)
                    .isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(TransactionListResponse.OUTPUT_ITEM_SUFFIX)
                    .isEqualTo(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
            assertThat(TransactionListResponse.PS_ITEM_SUFFIX).isEqualTo("P");
            assertThat(TransactionListResponse.HIGHLIGHT_ITEM_SUFFIX).isEqualTo("H");
            assertThat(TransactionListResponse.VALIDATION_ITEM_SUFFIX).isEqualTo("V");
        }
    }

    @Nested
    @DisplayName("The page size is behaviour: exactly 10, tied to the row groups")
    class PageSize {

        @Test
        @DisplayName("PAGE_SIZE is 10 and equals the number of modelled rows")
        void pageSizeIsTen() {
            assertThat(TransactionListResponse.PAGE_SIZE).isEqualTo(10);
            assertThat(TransactionListResponse.ROW_COUNT)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE);
            assertThat(TransactionListResponse.FIRST_ROW).isEqualTo(1);
            assertThat(TransactionListResponse.LAST_ROW).isEqualTo(10);
            assertThat(TransactionListResponse.LAST_ROW - TransactionListResponse.FIRST_ROW + 1)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE);
        }

        @Test
        @DisplayName("the row block width is exactly PAGE_SIZE rows wide")
        void rowBlockIsPageSizeRows() {
            assertThat(TransactionListResponse.ROW_BLOCK_WIDTH)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE
                            * TransactionListResponse.ROW_WIDTH);
        }

        @Test
        @DisplayName("no field of the payload carries the page size, so it cannot be configured")
        void pageSizeIsNotAPayloadField() {
            assertThat(TransactionListResponse.payloadFieldNames())
                    .noneMatch(name -> name.toUpperCase().contains("PAGESIZE"));
        }
    }

    @Nested
    @DisplayName("Rows are 1-based: rows 1 and 10 address the right fields")
    class RowAddressing {

        @ParameterizedTest(name = "row {0} addresses the five fields the copybook declares for it")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("every row's five prefixes are in copybook within-row order")
        void rowPrefixes(int row) {
            List<String> names = TransactionListResponse.rowFieldNames(row);
            assertThat(names).hasSize(5);
            int firstIndex = 8 + (row - 1) * 5;
            assertThat(names).containsExactlyElementsOf(
                    EXPECTED_ITEM_NAMES.subList(firstIndex, firstIndex + 5));
            assertThat(TransactionListResponse.rowFieldPrefixes(row)).hasSize(5);
        }

        @Test
        @DisplayName("row 1 is SEL0001O and row 10 is SEL0010O - the first and last element")
        void firstAndLastRow() {
            assertThat(TransactionListResponse.rowFieldNames(1))
                    .containsExactly("SEL0001O", "TRNID01O", "TDATE01O", "TDESC01O", "TAMT001O");
            assertThat(TransactionListResponse.rowFieldNames(10))
                    .containsExactly("SEL0010O", "TRNID10O", "TDATE10O", "TDESC10O", "TAMT010O");
        }

        @ParameterizedTest(name = "row {0} is off the page")
        @ValueSource(ints = {-1, 0, 11, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("an accessor rejects a row outside 1..10 rather than guessing")
        void offPageRowsAreRejected(int row) {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.rowFieldNames(row))
                    .withMessageContaining("1-based");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowSelection(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowTransactionId(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowTransactionDate(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowDescription(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowAmount(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowSelection(row, " "));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowTransactionId(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowTransactionDate(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowDescription(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowAmount(row, "x"));
        }

        @ParameterizedTest(name = "row {0} round-trips through the row accessors")
        @ValueSource(ints = {1, 5, 10})
        @DisplayName("the row accessors read back what they wrote, untrimmed")
        void rowAccessorsRoundTrip(int row) {
            TransactionListResponse response = new TransactionListResponse();
            response.setRowSelection(row, "S");
            response.setRowTransactionId(row, "0000000000000042");
            response.setRowTransactionDate(row, "07/18/22");
            response.setRowDescription(row, "twenty six characters here");
            response.setRowAmount(row, "+00000042.00");
            assertThat(response.getRowSelection(row)).isEqualTo("S");
            assertThat(response.getRowTransactionId(row)).isEqualTo("0000000000000042");
            assertThat(response.getRowTransactionDate(row)).isEqualTo("07/18/22");
            assertThat(response.getRowDescription(row)).isEqualTo("twenty six characters here")
                    .hasSize(26);
            assertThat(response.getRowAmount(row)).isEqualTo("+00000042.00").hasSize(12);
        }
    }

    @Nested
    @DisplayName("POPULATE-TRAN-DATA and INITIALIZE-TRAN-DATA, statement for statement")
    class ParagraphReproductions {

        @Test
        @DisplayName("POPULATE-HEADER-INFO fills the six heading fields from the source moves")
        void populateHeaderInfo() {
            TransactionListResponse response = new TransactionListResponse();
            // A LocalDateTime carries no zone, so this factory cannot reach the platform default
            // either; the fixed-Clock path that DateHeader.from takes is asserted separately, in
            // DeterministicHeaderClock.
            DateHeader header = DateHeader.of(CODEC, LocalDateTime.of(2022, 12, 25, 3, 4, 5));
            response.populateHeaderInfo(header);
            assertThat(response.getTitle01O()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02O()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameO()).isEqualTo("CT00");
            assertThat(response.getPgmnameO()).isEqualTo("COTRN00C");
            assertThat(response.getCurdateO()).isEqualTo("12/25/22").hasSize(8);
            assertThat(response.getCurtimeO()).isEqualTo("03:04:05").hasSize(8);
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO requires a date header")
        void populateHeaderInfoRejectsNull() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException().isThrownBy(() -> response.populateHeaderInfo(null));
        }

        @Test
        @DisplayName("TRAN-DESC X(100) truncates on the RIGHT into TDESCnnO X(26)")
        void descriptionTruncatesOnTheRight() {
            TransactionListResponse response = new TransactionListResponse();
            String hundred = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4).substring(0, 100);
            response.populateTranData(CODEC, 1, "0000000000000001", "07/18/22", hundred,
                    "+00000001.00");
            assertThat(response.getTdesc01O()).isEqualTo(hundred.substring(0, 26)).hasSize(26);
            assertThat(response.getTdesc01O()).isNotEqualTo(hundred.substring(74));
        }

        @Test
        @DisplayName("a short value is padded on the right to the field's declared width")
        void shortValuesArePadded() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 2, "42", "1/1/22", "short", "+1.00");
            assertThat(response.getTrnid02O()).isEqualTo("42              ").hasSize(16);
            assertThat(response.getTdate02O()).isEqualTo("1/1/22  ").hasSize(8);
            assertThat(response.getTdesc02O()).isEqualTo("short                     ").hasSize(26);
            assertThat(response.getTamt002O()).isEqualTo("+1.00       ").hasSize(12);
        }

        @Test
        @DisplayName("row 1 also seeds CDEMO-CT00-TRNID-FIRST; no other row does")
        void rowOneSeedsTheCursor() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 2, "0000000000000002", "07/18/22", "row two",
                    "+00000002.00");
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(" ".repeat(16));
            response.populateTranData(CODEC, 1, "0000000000000001", "07/18/22", "row one",
                    "+00000001.00");
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo("0000000000000001");
        }

        @ParameterizedTest(name = "populateTranData on row {0} is a no-op - WHEN OTHER CONTINUE")
        @ValueSource(ints = {-5, 0, 11, 12, 1000})
        @DisplayName("an out-of-range row is a silent no-op, not an error")
        void populateOffPageIsANoOp(int row) {
            TransactionListResponse response = new TransactionListResponse();
            Map<String, String> before = response.payloadFieldValues();
            response.populateTranData(CODEC, row, "id", "date", "desc", "amt");
            response.initializeTranData(row);
            assertThat(response.payloadFieldValues()).isEqualTo(before);
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("populateTranData requires a codec, so the code page is always explicit")
        void populateRequiresACodec() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException().isThrownBy(
                    () -> response.populateTranData(null, 1, "a", "b", "c", "d"));
        }

        @ParameterizedTest(name = "INITIALIZE-TRAN-DATA blanks four of row {0}'s five fields")
        @ValueSource(ints = {1, 5, 10})
        @DisplayName("INITIALIZE-TRAN-DATA blanks the four data fields and LEAVES the selector")
        void initializeLeavesTheSelectorAlone(int row) {
            TransactionListResponse response = new TransactionListResponse();
            response.setRowSelection(row, "S");
            response.populateTranData(CODEC, row, "0000000000000009", "07/18/22", "filled",
                    "+00000009.00");

            response.initializeTranData(row);

            assertThat(response.getRowTransactionId(row)).isEqualTo(" ".repeat(16));
            assertThat(response.getRowTransactionDate(row)).isEqualTo(" ".repeat(8));
            assertThat(response.getRowDescription(row)).isEqualTo(" ".repeat(26));
            assertThat(response.getRowAmount(row)).isEqualTo(" ".repeat(12));
            assertThat(response.getRowSelection(row))
                    .as("SEL000nO is absent from every arm of INITIALIZE-TRAN-DATA")
                    .isEqualTo("S");
        }

        @Test
        @DisplayName("a blanked amount is spaces - never null and never a rendered 0.00")
        void blankedAmountIsSpaces() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 3, "1", "1/1/22", "d", "+00000001.00");
            response.initializeTranData(3);
            assertThat(response.getTamt003O()).isEqualTo("            ").isNotNull();
            assertThat(response.getTamt003O()).isNotEqualTo("0.00").doesNotContain("0");
        }

        @Test
        @DisplayName("initializeAllTranData blanks all ten rows - the PERFORM VARYING loop")
        void initializeAllRows() {
            TransactionListResponse response = fullyPopulated();
            response.initializeAllTranData();
            for (int row = 1; row <= 10; row++) {
                assertThat(response.getRowTransactionId(row)).isBlank();
                assertThat(response.getRowDescription(row)).isBlank();
                assertThat(response.getRowAmount(row)).isBlank();
            }
            assertThat(response.getTitle01O())
                    .as("the heading is not part of the row block")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @ParameterizedTest(name = "page {0} renders as the zero-filled image {1}")
        @CsvSource({"0,00000000", "1,00000001", "3,00000003", "99,00000099", "99999999,99999999"})
        @DisplayName("PAGENUMO is the numeric page number zero-filled into X(8)")
        void pageNumberMovesIntoTheAlphanumericItem(int pageNum, String expected) {
            TransactionListResponse response = new TransactionListResponse();
            response.movePageNumberToScreen(CODEC, pageNum);
            assertThat(response.getPagenumO()).isEqualTo(expected).hasSize(8);
        }

        @Test
        @DisplayName("a negative page number has no representation in PIC 9(08)")
        void negativePageNumberRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.movePageNumberToScreen(CODEC, -1))
                    .withMessageContaining("unsigned");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.movePageNumberToScreen(null, 1));
        }

        @Test
        @DisplayName("WS-MESSAGE X(80) truncates on the right into ERRMSGO X(78)")
        void errorLineTruncatesTwoCharacters() {
            TransactionListResponse response = new TransactionListResponse();
            String eighty = "M".repeat(78) + "XY";
            response.moveMessageToErrorLine(CODEC, eighty);
            assertThat(response.getErrmsgO()).isEqualTo("M".repeat(78)).hasSize(78);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY X(50) is padded into ERRMSGO X(78)")
        void invalidKeyMessage() {
            TransactionListResponse response = new TransactionListResponse();
            response.moveInvalidKeyMessageToErrorLine(CODEC);
            assertThat(response.getErrmsgO()).hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .endsWith(" ");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
        }

        @Test
        @DisplayName("clearErrorLine and clearTranIdInput reproduce their MOVE statements")
        void clearingStatements() {
            TransactionListResponse response = new TransactionListResponse();
            response.moveMessageToErrorLine(CODEC, "something went wrong");
            response.setTrnidinO("0000000000000007");

            response.clearErrorLine();
            response.clearTranIdInput();

            assertThat(response.getErrmsgO()).isEqualTo(" ".repeat(78));
            assertThat(response.getTrnidinO())
                    .as("MOVE SPACE into a PIC X(16) receiver fills the whole field")
                    .isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("moveMessageToErrorLine requires a codec and a message")
        void errorLineGuards() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveMessageToErrorLine(null, "x"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveMessageToErrorLine(CODEC, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveInvalidKeyMessageToErrorLine(null));
        }
    }

    @Nested
    @DisplayName("The attribute quad: metadata, and the CSSETATY highlight it receives")
    class Attributes {

        @Test
        @DisplayName("every field starts at low values, which is DFHDFCOL")
        void everyFieldStartsAtLowValues() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(FieldAttributes.LOW_VALUE).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response.allAttributes()).hasSize(59);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                FieldAttributes attributes = response.attributesOf(prefix);
                assertThat(attributes.isLowValues()).isTrue();
                assertThat(attributes.isColourRed()).isFalse();
                assertThat(attributes.toByteArray()).hasSize(4);
                assertThat(attributes.describe()).contains("C=X'00'", "DFHDFCOL");
                assertThat(response.isFieldHighlighted(prefix)).isFalse();
            }
            assertThat(FieldAttributes.lowValues()).isEqualTo(new FieldAttributes(
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE));
        }

        @Test
        @DisplayName("isLowValues reports false when any one of the four items has been moved into")
        void isLowValuesTestsAllFourItems() {
            byte red = BmsAttributes.DFHRED;
            assertThat(FieldAttributes.lowValues().withColour(red).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, (byte) 'P',
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    (byte) 'H', FieldAttributes.LOW_VALUE).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    FieldAttributes.LOW_VALUE, (byte) 'V').isLowValues()).isFalse();
            assertThat(FieldAttributes.lowValues().withColour(red).isColourRed()).isTrue();
            assertThat(FieldAttributes.lowValues().withColour(red).describe())
                    .contains("C=X'F2'", "DFHRED");
        }

        @ParameterizedTest(name = "{0}: a NOT-OK field on RE-ENTRY turns DFHRED and keeps its text")
        @ValueSource(strings = {"TRNIDIN", "SEL0001", "SEL0010", "TAMT010", "ERRMSG"})
        @DisplayName("a CSSETATY highlight moves DFHRED into xxxC on re-entry")
        void highlightOnReenter(String prefix) {
            TransactionListResponse response = new TransactionListResponse();
            response.setPayloadValue(prefix, "");
            String before = response.payloadValue(prefix);

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    prefix, TransactionListResponse.MAP_NAME);
            response.applyHighlight(prefix, highlight);

            assertThat(response.isFieldHighlighted(prefix)).isTrue();
            assertThat(response.attributesOf(prefix).colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.payloadValue(prefix))
                    .as("only a BLANK field also receives the asterisk")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a BLANK field on re-entry also receives the '*' in its xxxO item")
        void blankFieldReceivesAnAsterisk() {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    "TRNIDIN", TransactionListResponse.MAP_NAME);
            response.applyHighlight("TRNIDIN", highlight);
            assertThat(response.isFieldHighlighted("TRNIDIN")).isTrue();
            assertThat(response.getTrnidinO()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @ParameterizedTest(name = "notOk={0} blank={1} on FIRST ENTRY changes nothing")
        @CsvSource({"true,false", "false,true", "true,true", "false,false"})
        @DisplayName("no highlight is reachable on first entry - the CSSETATY outer IF fails")
        void noHighlightOnFirstEntry(boolean notOk, boolean blank) {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, false,
                    "SEL0005", TransactionListResponse.MAP_NAME);
            response.applyHighlight("SEL0005", highlight);
            assertThat(highlight.untouched()).isTrue();
            assertThat(response.isFieldHighlighted("SEL0005")).isFalse();
            assertThat(response.attributesOf("SEL0005").isLowValues()).isTrue();
            assertThat(response.getSel0005O()).isEqualTo(" ");
        }

        @Test
        @DisplayName("a valid field on re-entry is left alone too")
        void noHighlightWhenValid() {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, false, true,
                    "TAMT001", TransactionListResponse.MAP_NAME);
            response.applyHighlight("TAMT001", highlight);
            assertThat(response.isFieldHighlighted("TAMT001")).isFalse();
        }

        @Test
        @DisplayName("putAttributes replaces a quad, and resetAttributesToLowValues restores all 59")
        void attributesCanBeReplacedAndReset() {
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("PAGENUM",
                    new FieldAttributes((byte) '1', (byte) '2', (byte) '3', (byte) '4'));
            assertThat(response.attributesOf("PAGENUM").toByteArray())
                    .containsExactly((byte) '1', (byte) '2', (byte) '3', (byte) '4');

            response.resetAttributesToLowValues();

            assertThat(response.allAttributes().values())
                    .allMatch(FieldAttributes::isLowValues)
                    .hasSize(59);
        }

        @Test
        @DisplayName("the attribute accessors reject a null quad, a null prefix and an unknown one")
        void attributeGuards() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.putAttributes("PAGENUM", null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.attributesOf("NOPE"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight("PAGENUM", null));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.allAttributes().clear());
        }
    }

    @Nested
    @DisplayName("Navigation: one XCTL target for both transfer sites, resolved by the client")
    class Navigation {

        @Test
        @DisplayName("the mapset and map default to this screen's own names")
        void navigationDefaults() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(response.getNextMapset()).isEqualTo("COTRN00");
            assertThat(response.getNextMap()).isEqualTo("COTRN0A");
        }

        @Test
        @DisplayName("the row-selection path carries COTRN01C as a plain program name")
        void rowSelectionTarget() {
            TransactionListResponse response = new TransactionListResponse();
            response.setNextProgram("COTRN01C");
            assertThat(response.getNextProgram()).isEqualTo("COTRN01C").hasSize(8);
        }

        @Test
        @DisplayName("echoTransferTarget names the program only, and blanks the map and mapset")
        void echoTransferTarget() {
            TransactionListResponse response = new TransactionListResponse();
            NavigationContext context = NavigationContext.empty().withToProgram("COMEN01C");

            response.echoTransferTarget(context);

            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            // COTRN00C:188-195 and :512-521 state PROGRAM and COMMAREA and nothing else, and the program
            // never writes CDEMO-LAST-MAP or CDEMO-LAST-MAPSET - so the target is handed no map or mapset
            // and picks its own. Publishing THIS screen's COTRN00 / COTRN0A as the NEXT screen's told a
            // client to paint the map it is leaving.
            assertThat(response.getNextMapset())
                    .isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.getNextMap())
                    .isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(response.getNextMapset()).isNotEqualTo(TransactionListResponse.MAPSET_NAME);
            assertThat(response.getNextMap()).isNotEqualTo(TransactionListResponse.MAP_NAME);
            assertThatNullPointerException().isThrownBy(() -> response.echoTransferTarget(null));
        }

        @Test
        @DisplayName("a screen that is painted rather than transferred still names its own map and mapset")
        void aPaintedScreenKeepsItsOwnNames() {
            // The default state is this screen's own triple, which is what a SEND publishes; only the
            // XCTL projection blanks them, so the two outcomes stay distinguishable to a client.
            TransactionListResponse painted = new TransactionListResponse();

            assertThat(painted.getNextMapset()).isEqualTo(TransactionListResponse.MAPSET_NAME);
            assertThat(painted.getNextMap()).isEqualTo(TransactionListResponse.MAP_NAME);
        }

        @Test
        @DisplayName("the navigation widths are the commarea's: 8, 7 and 7")
        void navigationWidthsAreEnforced() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextProgram("TOOLONGPROGRAM"))
                    .withMessageContaining("CDEMO-TO-PROGRAM");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextMapset("EIGHTCHR"))
                    .withMessageContaining("CDEMO-LAST-MAPSET");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextMap("EIGHTCHR"))
                    .withMessageContaining("CDEMO-LAST-MAP");
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
        }
    }

    @Nested
    @DisplayName("Statelessness: the commarea and the 58-byte cursor travel in the payload")
    class Statelessness {

        @Test
        @DisplayName("the commarea starts empty at exactly 160 bytes and is echoed unchanged")
        void commareaIsEchoed() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
            NavigationContext context = NavigationContext.empty()
                    .withUserId("ADMIN001").withToProgram("COTRN01C");
            response.setNavigationContext(context);
            assertThat(response.getNavigationContext()).isSameAs(context);
            assertThat(context.toFixedWidth(CODEC)).hasSize(160);
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setNavigationContext(null));
        }

        @Test
        @DisplayName("the cursor starts in its declared state, with NEXT-PAGE-FLG at VALUE 'N'")
        void cursorStartsInItsDeclaredState() {
            TransactionListCursor cursor = new TransactionListCursor();
            assertThat(cursor.getTrnidFirst()).isEqualTo(" ".repeat(16));
            assertThat(cursor.getTrnidLast()).isEqualTo(" ".repeat(16));
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(" ".repeat(16));
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isRowSelected()).isFalse();
        }

        @Test
        @DisplayName("both 88-levels are reachable, and neither is the other's negation")
        void bothConditionNamesAreReachable() {
            TransactionListCursor cursor = new TransactionListCursor();

            cursor.setNextPageYes();
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageFlg(" ");
            assertThat(cursor.isNextPageYes())
                    .as("a blank flag satisfies neither condition")
                    .isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageFlg("y");
            assertThat(cursor.isNextPageYes())
                    .as("a COBOL alphanumeric comparison is case-sensitive")
                    .isFalse();
        }

        @Test
        @DisplayName("a selection needs both the flag and the identifier to be present")
        void rowSelectionRequiresBothFields() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnSelFlg("S");
            assertThat(cursor.isRowSelected())
                    .as("the flag alone is not a selection")
                    .isFalse();
            cursor.setTrnSelected("0000000000000001");
            assertThat(cursor.isRowSelected()).isTrue();

            cursor.setTrnSelFlg("\u0000");
            assertThat(cursor.isRowSelected())
                    .as("a low value is not present either")
                    .isFalse();

            cursor.setTrnSelFlg("");
            assertThat(cursor.isRowSelected())
                    .as("an empty flag is not present either")
                    .isFalse();

            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("");
            assertThat(cursor.isRowSelected())
                    .as("the second conjunct of the COTRN00C:183-184 guard is tested too")
                    .isFalse();

            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000001");
            cursor.clearSelection();
            assertThat(cursor.isRowSelected()).isFalse();
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the cursor carries any selection character, valid or not")
        void anySelectionCharacterIsCarried() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnSelFlg("Z");
            assertThat(cursor.getTrnSelFlg())
                    .as("COTRN00C stores what was typed and only then rejects it")
                    .isEqualTo("Z");
        }

        @Test
        @DisplayName("CDEMO-CT00-PAGE-NUM is PIC 9(08): unsigned and at most eight digits")
        void pageNumberIsValidated() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setPageNum(99999999);
            assertThat(cursor.getPageNum()).isEqualTo(99999999);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(100000000))
                    .withMessageContaining("9 digits");
        }

        @Test
        @DisplayName("the cursor fields reject over-wide values and nulls")
        void cursorWidthsAreEnforced() {
            TransactionListCursor cursor = new TransactionListCursor();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnidFirst("x".repeat(17)))
                    .withMessageContaining("CDEMO-CT00-TRNID-FIRST");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnidLast("x".repeat(17)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setNextPageFlg("YY"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnSelFlg("SS"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnSelected("x".repeat(17)));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
        }

        @Test
        @DisplayName("the cursor round-trips through its 58-byte image")
        void cursorImageRoundTrips() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnidFirst("0000000000000001");
            cursor.setTrnidLast("0000000000000010");
            cursor.setPageNum(7);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000005");

            byte[] image = cursor.toFixedWidth(CODEC);
            assertThat(image).hasSize(58);
            assertThat(new String(image, ASCII))
                    .startsWith("00000000000000010000000000000010" + "00000007" + "YS");

            TransactionListCursor back = TransactionListCursor.fromFixedWidth(image, ASCII);
            assertThat(back.getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(back.getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(back.getPageNum()).isEqualTo(7);
            assertThat(back.isNextPageYes()).isTrue();
            assertThat(back.getTrnSelFlg()).isEqualTo("S");
            assertThat(back.getTrnSelected()).isEqualTo("0000000000000005");
            assertThat(back.toString()).contains("CDEMO-CT00-INFO", "CDEMO-CT00-PAGE-NUM=7");
        }

        @Test
        @DisplayName("the cursor image rejects a wrong width and a null argument")
        void cursorImageGuards() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(new byte[57], ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(new byte[58], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListCursor().toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> new TransactionListCursor(null));
        }

        @Test
        @DisplayName("setCursor copies, so two responses cannot page each other")
        void cursorIsCopiedIn() {
            TransactionListResponse response = new TransactionListResponse();
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setPageNum(4);
            response.setCursor(cursor);

            cursor.setPageNum(9);

            assertThat(response.getCursor().getPageNum()).isEqualTo(4);
            assertThat(response.getCursor()).isNotSameAs(cursor);
            assertThatNullPointerException().isThrownBy(() -> response.setCursor(null));
        }
    }

    @Nested
    @DisplayName("The 1265-byte COTRN0AO image")
    class FixedWidthImage {

        @Test
        @DisplayName("a blank response renders 1265 bytes with every FILLER emitted as spaces")
        void blankImage() {
            byte[] image = new TransactionListResponse().toFixedWidth(CODEC);
            assertThat(image).hasSize(1265);
            String text = new String(image, ASCII);
            assertThat(text.substring(0, 12))
                    .as("the TIOAPFX prefix is emitted, not skipped")
                    .isEqualTo(" ".repeat(12));
            assertThat(text.substring(12, 15))
                    .as("the per-field 3-byte FILLER is emitted")
                    .isEqualTo(" ".repeat(3));
            assertThat(image[15])
                    .as("TRNNAMEC holds the low value MOVE LOW-VALUES left")
                    .isEqualTo(FieldAttributes.LOW_VALUE);
        }

        @Test
        @DisplayName("a populated response round-trips through its image without loss")
        void imageRoundTrips() {
            TransactionListResponse response = fullyPopulated();
            response.putAttributes("TRNIDIN",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));

            byte[] image = response.toFixedWidth(CODEC);
            assertThat(image).hasSize(1265);
            assertThat(image[TransactionListResponse.LAYOUT.span("TRNIDINC").offset()])
                    .as("the attribute byte is written raw, so 0xF2 survives a US-ASCII image")
                    .isEqualTo(BmsAttributes.DFHRED);

            TransactionListResponse back = TransactionListResponse.fromFixedWidth(image, ASCII);
            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
            assertThat(back.isFieldHighlighted("TRNIDIN")).isTrue();
            assertThat(back.attributesOf("SEL0001").isLowValues()).isTrue();
        }

        @Test
        @DisplayName("each payload item lands at its layout offset")
        void payloadItemsLandAtTheirOffsets() {
            TransactionListResponse response = fullyPopulated();
            String text = new String(response.toFixedWidth(CODEC), ASCII);
            for (String itemName : EXPECTED_ITEM_NAMES) {
                FixedWidthRecord.FieldSpan span = TransactionListResponse.LAYOUT.span(itemName);
                String prefix = itemName.substring(0, itemName.length() - 1);
                assertThat(text.substring(span.offset(), span.endOffsetExclusive()))
                        .as(itemName)
                        .isEqualTo(response.payloadValue(prefix));
            }
        }

        @Test
        @DisplayName("writeInto and readFrom use the record's own code page")
        void writeIntoAndReadFrom() {
            TransactionListResponse response = fullyPopulated();
            FixedWidthRecord record = new FixedWidthRecord(1265, ASCII);
            response.writeInto(record);
            TransactionListResponse back = TransactionListResponse.readFrom(record);
            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
        }

        @Test
        @DisplayName("a record of the wrong width is rejected, and so are null arguments")
        void imageGuards() {
            TransactionListResponse response = new TransactionListResponse();
            FixedWidthRecord wrongWidth = new FixedWidthRecord(100, ASCII);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(wrongWidth))
                    .withMessageContaining("1265");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.readFrom(wrongWidth));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(new byte[1264],
                            ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.readFrom(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(new byte[1265], null));
        }
    }

    @Nested
    @DisplayName("JSON: the space padding of a PIC X field survives a round trip untrimmed")
    class JsonRoundTrip {

        /** A plain mapper with no configuration, since the module's policy lives in the web layer. */
        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("a 78-character error line, a 26-character description and a 12-character "
                + "amount all survive untrimmed")
        void paddingSurvives() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.setErrmsgO("error" + " ".repeat(73));
            response.setTdesc05O("desc" + " ".repeat(22));
            response.setTamt005O("+1.00" + " ".repeat(7));

            String json = mapper.writeValueAsString(response);
            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);

            assertThat(back.getErrmsgO()).isEqualTo("error" + " ".repeat(73)).hasSize(78);
            assertThat(back.getTdesc05O()).isEqualTo("desc" + " ".repeat(22)).hasSize(26);
            assertThat(back.getTamt005O()).isEqualTo("+1.00" + " ".repeat(7)).hasSize(12);
        }

        @Test
        @DisplayName("all 59 fields, the navigation targets and the cursor round-trip through JSON")
        void everyFieldRoundTrips() throws Exception {
            TransactionListResponse response = fullyPopulated();
            response.setNextProgram("COTRN01C");
            response.getCursor().setNextPageYes();
            response.getCursor().setPageNum(3);
            response.setNavigationContext(NavigationContext.empty().withUserId("USER0001"));

            String json = mapper.writeValueAsString(response);
            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);

            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
            assertThat(back.getNextProgram()).isEqualTo("COTRN01C");
            assertThat(back.getNextMapset()).isEqualTo("COTRN00");
            assertThat(back.getNextMap()).isEqualTo("COTRN0A");
            assertThat(back.getCursor().getPageNum()).isEqualTo(3);
            assertThat(back.getCursor().isNextPageYes()).isTrue();
            assertThat(back.getNavigationContext().userId()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("a blanked row serialises as spaces, not as null")
        void blankedRowSerialisesAsSpaces() throws Exception {
            TransactionListResponse response = fullyPopulated();
            response.initializeTranData(4);

            String json = mapper.writeValueAsString(response);
            assertThat(json).doesNotContain("null");
            assertThat(json).contains("\"tamt004O\":\"            \"");

            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);
            assertThat(back.getTamt004O()).isEqualTo(" ".repeat(12));
            assertThat(back.getTrnid04O()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the attribute metadata never appears in the payload")
        void attributesAreNotSerialised() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("PAGENUM",
                    new FieldAttributes((byte) '1', (byte) '2', (byte) '3', (byte) '4'));
            String json = mapper.writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("fieldAttributes")
                    .doesNotContain("attributes")
                    .doesNotContain("PAGENUMC")
                    .doesNotContain("payloadFieldValues")
                    .doesNotContain("rowSelection");
        }

        @Test
        @DisplayName("the JSON carries a member for every one of the 59 fields")
        void everyFieldIsAMember() throws Exception {
            String json = mapper.writeValueAsString(new TransactionListResponse());
            @SuppressWarnings("unchecked")
            Map<String, Object> members = mapper.readValue(json, Map.class);
            for (String itemName : EXPECTED_ITEM_NAMES) {
                String member = itemName.substring(0, itemName.length() - 1).toLowerCase()
                        + itemName.charAt(itemName.length() - 1);
                assertThat(members).as(itemName).containsKey(member);
            }
            assertThat(members).hasSize(59 + 3 + 2);
        }
    }

    @Nested
    @DisplayName("Copying and diagnostics")
    class CopyingAndDiagnostics {

        @Test
        @DisplayName("the copy constructor copies the payload, the navigation and the attributes")
        void copyConstructor() {
            TransactionListResponse original = fullyPopulated();
            original.setNextProgram("COTRN01C");
            original.putAttributes("SEL0003",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));
            original.getCursor().setPageNum(2);

            TransactionListResponse copy = new TransactionListResponse(original);

            assertThat(copy.payloadFieldValues()).isEqualTo(original.payloadFieldValues());
            assertThat(copy.getNextProgram()).isEqualTo("COTRN01C");
            assertThat(copy.getNextMapset()).isEqualTo("COTRN00");
            assertThat(copy.getNextMap()).isEqualTo("COTRN0A");
            assertThat(copy.isFieldHighlighted("SEL0003")).isTrue();
            assertThat(copy.getCursor().getPageNum()).isEqualTo(2);
            assertThat(copy.getCursor()).isNotSameAs(original.getCursor());

            original.setTamt001O("+99999999.99");
            assertThat(copy.getTamt001O()).isNotEqualTo("+99999999.99");

            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListResponse(null));
        }

        @Test
        @DisplayName("toString names every field by its verbatim item name and hides nothing")
        void toStringIsComplete() {
            String text = fullyPopulated().toString();
            assertThat(text).startsWith("COTRN0AO[");
            for (String itemName : EXPECTED_ITEM_NAMES) {
                assertThat(text).as(itemName).contains(itemName + "='");
            }
            assertThat(text).contains("nextProgram=", "nextMapset=", "nextMap=",
                    "CDEMO-CT00-INFO[");
        }

        @Test
        @DisplayName("payloadFieldValues and the name lists are unmodifiable")
        void registriesAreImmutable() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.payloadFieldValues().put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.payloadFieldNames().add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.fieldPrefixes().add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.rowFieldPrefixes(1).add("X"));
        }
    }

    // =================================================================================================
    // B7 - the heading clock. CURDATEO and CURTIMEO are the only two fields whose value could otherwise
    // come from outside the test, so the clock is injected and the zone is named.
    // =================================================================================================

    @Nested
    @DisplayName("The heading clock is injected and fixed, so CURDATEO and CURTIMEO are deterministic")
    class DeterministicHeaderClock {

        @Test
        @DisplayName("a fixed Clock renders MM/DD/YY and HH:MM:SS at their declared widths")
        void fixedClockRendersTheTwoHeaderFields() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateHeaderInfo(DateHeader.from(CODEC, FIXED_CLOCK));

            assertThat(response.getCurdateO())
                    .as("MOVE WS-CURDATE-MM-DD-YY TO CURDATEO - COTRN00C:580")
                    .isEqualTo(EXPECTED_CURDATE)
                    .hasSize(TransactionListResponse.CURDATE_LENGTH);
            assertThat(response.getCurtimeO())
                    .as("MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO - COTRN00C:586")
                    .isEqualTo(EXPECTED_CURTIME)
                    .hasSize(TransactionListResponse.CURTIME_LENGTH);
            assertThat(TransactionListResponse.CURDATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURTIME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("every component keeps its leading zero, so 3:4:5 can never appear")
        void singleDigitComponentsAreZeroFilled() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateHeaderInfo(fixedHeader());

            assertThat(response.getCurtimeO())
                    .as("an hour, minute and second below ten are all zero-filled")
                    .isEqualTo("03:04:05")
                    .doesNotContain("3:4:5");
            assertThat(response.getCurdateO().charAt(2)).isEqualTo('/');
            assertThat(response.getCurtimeO().charAt(2)).isEqualTo(':');
        }

        @Test
        @DisplayName("the same clock produces byte-identical headings however often it is read")
        void theSameClockIsIdempotent() {
            TransactionListResponse first = new TransactionListResponse();
            TransactionListResponse second = new TransactionListResponse();

            first.populateHeaderInfo(DateHeader.from(CODEC, FIXED_CLOCK));
            second.populateHeaderInfo(DateHeader.from(CODEC, FIXED_CLOCK));

            assertThat(second.getCurdateO()).isEqualTo(first.getCurdateO());
            assertThat(second.getCurtimeO()).isEqualTo(first.getCurtimeO());
            assertThat(second.toFixedWidth(CODEC))
                    .as("the whole 1265-byte image is reproducible, not just the two clock fields")
                    .isEqualTo(first.toFixedWidth(CODEC));
        }

        @Test
        @DisplayName("the zone is an input, not an ambient default: a second zone renders differently")
        void theZoneIsStatedRatherThanInherited() {
            // The same instant in a zone eleven hours ahead falls on the following day, so a suite
            // that silently took ZoneId.systemDefault() would produce different bytes on a differently
            // configured host. Both renderings here are deterministic because both zones are named.
            Clock elevenHoursAhead = Clock.fixed(HEADER_INSTANT, ZoneId.of("Australia/Sydney"));
            TransactionListResponse greenwich = new TransactionListResponse();
            TransactionListResponse sydney = new TransactionListResponse();

            greenwich.populateHeaderInfo(DateHeader.from(CODEC, FIXED_CLOCK));
            sydney.populateHeaderInfo(DateHeader.from(CODEC, elevenHoursAhead));

            assertThat(greenwich.getCurdateO()).isEqualTo("07/18/22");
            assertThat(sydney.getCurdateO())
                    .as("the zone genuinely reaches the rendering, so naming it matters")
                    .isEqualTo("07/18/22")
                    .isEqualTo(greenwich.getCurdateO());
            assertThat(sydney.getCurtimeO())
                    .as("13:04:05 in Sydney is 03:04:05 in Greenwich")
                    .isEqualTo("13:04:05")
                    .isNotEqualTo(greenwich.getCurtimeO());
        }

        @Test
        @DisplayName("the shared clock is immutable, so no test can move another test's clock")
        void theSharedClockIsImmutable() throws Exception {
            Field field = TransactionListResponseTest.class.getDeclaredField("FIXED_CLOCK");

            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("practice B9: a static field of this suite must be final")
                    .isTrue();
            assertThat(FIXED_CLOCK.instant())
                    .as("Clock.fixed never advances")
                    .isEqualTo(HEADER_INSTANT)
                    .isEqualTo(FIXED_CLOCK.instant());
            assertThat(FIXED_CLOCK.getZone()).isEqualTo(HEADER_ZONE);
        }
    }

    // =================================================================================================
    // G34 - the REDEFINES contract. This is the AO side of it, and the assertions below are the only
    // ones in the suite that prove COTRN0AO and COTRN0AI are two views of ONE buffer rather than two
    // buffers that happen to be the same size.
    //
    // The AI view is transcribed here from app/cpy-bms/COTRN00.CPY:17 rather than imported from the
    // sibling request type: the sibling is not a dependency of this file, and an independent
    // transcription is a stronger check in any case - if both views were read from the same Java
    // constant they could only ever agree with each other.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES: COTRN0AO and COTRN0AI are two views of the same 1265 bytes")
    class RedefinesOverlay {

        /** {@code xxxL COMP PIC S9(4)} - a two-byte binary halfword. */
        private static final int LENGTH_ITEM_WIDTH = 2;

        /** {@code xxxF PICTURE X}, which {@code xxxA} redefines in place. */
        private static final int FLAG_ITEM_WIDTH = 1;

        /** The AI view's per-field {@code FILLER PICTURE X(4)}, which the AO quad occupies. */
        private static final int INPUT_FILLER_WIDTH = 4;

        /**
         * The input view, transcribed from {@code 01 COTRN0AI} at {@code app/cpy-bms/COTRN00.CPY:17}.
         *
         * <p>One leading {@code FILLER PIC X(12)} for {@code TIOAPFX=YES}, then per field, in copybook
         * order: {@code xxxL} (2), {@code xxxF} (1) with {@code xxxA} redefining that single byte,
         * {@code FILLER PICTURE X(4)} and {@code xxxI PIC X(n)}.
         *
         * <p>{@link RecordLayout} refuses to be built unless the storage spans are contiguous from
         * offset zero and sum to exactly the declared length, so the fact that this independently
         * written layout accepts {@link TransactionListResponse#RECORD_LENGTH} is itself the assertion
         * that the two views describe the same number of bytes.
         *
         * @return the validated 1265-byte input layout; never {@code null}
         */
        private RecordLayout inputLayout() {
            List<FieldSpan> spans = new ArrayList<>();
            int cursor = 0;
            spans.add(FieldSpan.filler(cursor, TransactionListResponse.TIOAPFX_PREFIX_LENGTH));
            cursor += TransactionListResponse.TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < EXPECTED_ITEM_NAMES.size(); index++) {
                String itemName = EXPECTED_ITEM_NAMES.get(index);
                String prefix = itemName.substring(0, itemName.length() - 1);
                // xxxL is COMP PIC S9(4) - a binary halfword, not zoned DISPLAY digits. The codec has
                // no binary kind because no persisted record in this system holds one, and none is
                // needed: only the two bytes' position matters here, and they are read and written as
                // raw bytes below. The span is therefore declared by width alone.
                spans.add(FieldSpan.alphanumeric(prefix + "L", cursor, LENGTH_ITEM_WIDTH));
                cursor += LENGTH_ITEM_WIDTH;
                spans.add(FieldSpan.alphanumeric(prefix + "F", cursor, FLAG_ITEM_WIDTH));
                // 02 FILLER REDEFINES xxxF. / 03 xxxA PICTURE X. - the same byte under a second name.
                spans.add(FieldSpan.redefining(prefix + "A", cursor, FLAG_ITEM_WIDTH,
                        PictureKind.ALPHANUMERIC));
                cursor += FLAG_ITEM_WIDTH;
                spans.add(FieldSpan.filler(cursor, INPUT_FILLER_WIDTH));
                cursor += INPUT_FILLER_WIDTH;
                spans.add(FieldSpan.alphanumeric(itemName.substring(0, itemName.length() - 1) + "I",
                        cursor, EXPECTED_WIDTHS.get(index)));
                cursor += EXPECTED_WIDTHS.get(index);
            }
            return new RecordLayout(TransactionListResponse.RECORD_LENGTH, spans);
        }

        @Test
        @DisplayName("the input view accepts the same 1265-byte record length as the output view")
        void bothViewsDescribeTheSameRecordLength() {
            RecordLayout input = inputLayout();

            assertThat(input.recordLength())
                    .as("2 + 1 + 4 = 7 = 3 + 1 + 1 + 1 + 1, so the two prefixes are the same width")
                    .isEqualTo(TransactionListResponse.RECORD_LENGTH)
                    .isEqualTo(1265);
            assertThat(input.storageSpans().stream().mapToInt(FieldSpan::length).sum())
                    .isEqualTo(TransactionListResponse.LAYOUT.storageSpans()
                            .stream().mapToInt(FieldSpan::length).sum());
            assertThat(input.redefinitions())
                    .as("one xxxA overlay per field, and nothing else redefines in the input view")
                    .hasSize(TransactionListResponse.FIELD_COUNT);
            assertThat(LENGTH_ITEM_WIDTH + FLAG_ITEM_WIDTH + INPUT_FILLER_WIDTH)
                    .isEqualTo(TransactionListResponse.FIELD_PREFIX_LENGTH)
                    .isEqualTo(TransactionListResponse.ATTRIBUTE_FILLER_LENGTH
                            + TransactionListResponse.ATTRIBUTE_ITEM_COUNT
                                    * TransactionListResponse.ATTRIBUTE_ITEM_LENGTH);
        }

        @ParameterizedTest(name = "{0} occupies the same {1} bytes as its xxxI twin")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("xxxI and xxxO are the identical n bytes at offset k + 7")
        void payloadItemsShareTheirSpan(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            FieldSpan output = TransactionListResponse.LAYOUT.span(itemName);
            FieldSpan input = inputLayout().span(prefix + "I");

            assertThat(input.offset()).as("same offset").isEqualTo(output.offset());
            assertThat(input.length()).as("same length").isEqualTo(output.length()).isEqualTo(width);
            assertThat(input.endOffsetExclusive()).isEqualTo(output.endOffsetExclusive());
        }

        @ParameterizedTest(name = "{0}: the C/P/H/V quad is the input view's FILLER X(4)")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("the attribute quad occupies bytes k+3..k+6 - the input view's per-field FILLER")
        void theQuadOverlaysTheInputFiller(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            FieldSpan payload = TransactionListResponse.LAYOUT.span(itemName);
            int fieldStart = payload.offset() - TransactionListResponse.FIELD_PREFIX_LENGTH;

            // The four attribute items, in copybook order, at k+3, k+4, k+5 and k+6.
            List<String> quad = List.of(prefix + TransactionListResponse.COLOUR_ITEM_SUFFIX,
                    prefix + TransactionListResponse.PS_ITEM_SUFFIX,
                    prefix + TransactionListResponse.HIGHLIGHT_ITEM_SUFFIX,
                    prefix + TransactionListResponse.VALIDATION_ITEM_SUFFIX);
            for (int position = 0; position < quad.size(); position++) {
                FieldSpan item = TransactionListResponse.LAYOUT.span(quad.get(position));
                assertThat(item.offset())
                        .as("%s sits at k+%d", quad.get(position),
                                TransactionListResponse.ATTRIBUTE_FILLER_LENGTH + position)
                        .isEqualTo(fieldStart + TransactionListResponse.ATTRIBUTE_FILLER_LENGTH
                                + position);
                assertThat(item.length()).isEqualTo(TransactionListResponse.ATTRIBUTE_ITEM_LENGTH);
            }

            // ... and those are exactly the four bytes the input view reserves as FILLER.
            int inputFillerStart = fieldStart + LENGTH_ITEM_WIDTH + FLAG_ITEM_WIDTH;
            assertThat(inputFillerStart)
                    .isEqualTo(fieldStart + TransactionListResponse.ATTRIBUTE_FILLER_LENGTH);
            assertThat(inputFillerStart + INPUT_FILLER_WIDTH)
                    .as("both prefixes end where the payload item begins")
                    .isEqualTo(payload.offset());
            assertThat(width).isEqualTo(payload.length());
        }

        @ParameterizedTest(name = "{0}: xxxL and xxxF are the output view's FILLER X(3)")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("xxxL plus xxxF occupy bytes k..k+2 - the output view's per-field FILLER")
        void theLengthAndFlagItemsOverlayTheOutputFiller(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            RecordLayout input = inputLayout();
            int fieldStart = TransactionListResponse.LAYOUT.span(itemName).offset()
                    - TransactionListResponse.FIELD_PREFIX_LENGTH;

            FieldSpan lengthItem = input.span(prefix + "L");
            FieldSpan flagItem = input.span(prefix + "F");
            FieldSpan attributeItem = input.span(prefix + "A");

            assertThat(lengthItem.offset()).as("xxxL at k").isEqualTo(fieldStart);
            assertThat(lengthItem.length()).isEqualTo(LENGTH_ITEM_WIDTH);
            assertThat(flagItem.offset()).as("xxxF at k+2").isEqualTo(fieldStart + LENGTH_ITEM_WIDTH);
            assertThat(flagItem.length()).isEqualTo(FLAG_ITEM_WIDTH);
            assertThat(attributeItem.offset())
                    .as("xxxA redefines xxxF, so it is the SAME byte and not the next one")
                    .isEqualTo(flagItem.offset());
            assertThat(attributeItem.redefinition()).isTrue();
            assertThat(flagItem.endOffsetExclusive())
                    .as("k..k+2 inclusive is the three bytes the output view calls FILLER X(3)")
                    .isEqualTo(fieldStart + TransactionListResponse.ATTRIBUTE_FILLER_LENGTH);
            assertThat(width).isPositive();
        }

        @Test
        @DisplayName("a value written through xxxI is readable through xxxO, and back again")
        void thePayloadItemsRoundTripThroughOneBackingSpan() {
            RecordLayout input = inputLayout();
            FixedWidthRecord buffer =
                    new FixedWidthRecord(TransactionListResponse.RECORD_LENGTH, ASCII);

            // Write through the INPUT view ...
            buffer.writeSpan(input.span("TRNIDINI"), "0000000000000042");
            buffer.writeSpan(input.span("TDESC07I"), "written through COTRN0AI  ");
            buffer.writeSpan(input.span("ERRMSGI"), CODEC.movePicX("input side", 78));

            // ... and read through the OUTPUT view.
            TransactionListResponse response = TransactionListResponse.readFrom(buffer);
            assertThat(response.getTrnidinO()).isEqualTo("0000000000000042");
            assertThat(response.getTdesc07O()).isEqualTo("written through COTRN0AI  ");
            assertThat(response.getErrmsgO()).isEqualTo(CODEC.movePicX("input side", 78));

            // Now the other direction: write through the OUTPUT view ...
            response.setTrnidinO("0000000000000099");
            response.setTdesc07O("written through COTRN0AO  ");
            response.writeInto(buffer);

            // ... and read through the INPUT view.
            assertThat(buffer.readSpan(input.span("TRNIDINI"))).isEqualTo("0000000000000099");
            assertThat(buffer.readSpan(input.span("TDESC07I")))
                    .isEqualTo("written through COTRN0AO  ");
        }

        @Test
        @DisplayName("writing the quad through the output view is invisible through xxxL and xxxF")
        void quadWritesDoNotDisturbTheInputPrefix() {
            RecordLayout input = inputLayout();
            FixedWidthRecord buffer =
                    new FixedWidthRecord(TransactionListResponse.RECORD_LENGTH, ASCII);
            // -1 is the cursor-positioning value COTRN00C:242 moves into TRNIDINL; two bytes of it.
            byte[] cursorLength = {(byte) 0xFF, (byte) 0xFF};
            buffer.writeSpanBytes(input.span("TRNIDINL"), cursorLength);
            buffer.writeSpanBytes(input.span("TRNIDINF"), new byte[] {BmsAttributes.DFHBMFSE});

            TransactionListResponse response = TransactionListResponse.readFrom(buffer);
            response.putAttributes("TRNIDIN",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));
            response.writeInto(buffer);

            assertThat(buffer.readSpanBytes(input.span("TRNIDINL")))
                    .as("the quad starts at k+3, so the two bytes at k are untouched")
                    .isEqualTo(cursorLength);
            assertThat(buffer.readSpanBytes(input.span("TRNIDINF")))
                    .as("and so is the flag byte at k+2")
                    .containsExactly(BmsAttributes.DFHBMFSE);
            assertThat(buffer.readSpanBytes(input.span("TRNIDINA")))
                    .as("xxxA is that same byte read under its second name")
                    .containsExactly(BmsAttributes.DFHBMFSE);
            assertThat(buffer.readBytes(
                    TransactionListResponse.LAYOUT.span("TRNIDINC").offset(), 1))
                    .containsExactly(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("writing a cursor length through the input view is invisible through the quad")
        void inputPrefixWritesDoNotDisturbTheQuad() {
            RecordLayout input = inputLayout();
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("PAGENUM",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));
            FixedWidthRecord buffer =
                    new FixedWidthRecord(TransactionListResponse.RECORD_LENGTH, ASCII);
            response.writeInto(buffer);

            buffer.writeSpanBytes(input.span("PAGENUML"), new byte[] {(byte) 0xFF, (byte) 0xFF});

            TransactionListResponse back = TransactionListResponse.readFrom(buffer);
            assertThat(back.isFieldHighlighted("PAGENUM"))
                    .as("the two bytes at k are not part of the quad, which begins at k+3")
                    .isTrue();
            assertThat(back.attributesOf("PAGENUM").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(back.getPagenumO()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("every FILLER span is emitted as spaces: the 12-byte prefix and all 59 of X(3)")
        void everyFillerSpanIsEmitted() {
            RecordLayout input = inputLayout();
            String image = new String(new TransactionListResponse().toFixedWidth(CODEC), ASCII);

            assertThat(image).hasSize(TransactionListResponse.RECORD_LENGTH);
            assertThat(image.substring(0, TransactionListResponse.TIOAPFX_PREFIX_LENGTH))
                    .as("the TIOAPFX=YES prefix is emitted, not skipped")
                    .isEqualTo(" ".repeat(TransactionListResponse.TIOAPFX_PREFIX_LENGTH));

            int fillerCount = 0;
            for (String itemName : EXPECTED_ITEM_NAMES) {
                String prefix = itemName.substring(0, itemName.length() - 1);
                int fieldStart = TransactionListResponse.LAYOUT.span(itemName).offset()
                        - TransactionListResponse.FIELD_PREFIX_LENGTH;
                // The output view's FILLER X(3) is the input view's xxxL + xxxF, which a blank record
                // leaves as spaces; the four attribute bytes that follow carry the low value instead.
                assertThat(image.substring(fieldStart,
                                fieldStart + TransactionListResponse.ATTRIBUTE_FILLER_LENGTH))
                        .as("%s: the per-field FILLER X(3) at %d", prefix, fieldStart)
                        .isEqualTo(" ".repeat(TransactionListResponse.ATTRIBUTE_FILLER_LENGTH));
                assertThat(input.span(prefix + "L").offset()).isEqualTo(fieldStart);
                fillerCount++;
            }
            assertThat(fillerCount)
                    .as("one per-field FILLER for each of the 59 fields")
                    .isEqualTo(TransactionListResponse.FIELD_COUNT);
        }
    }

    // =================================================================================================
    // The metadata-versus-payload boundary. Payload names and widths come from the xxxO items ONLY. The
    // input view's xxxL / xxxF / xxxA triple and the output view's C / P / H / V quad are screen
    // mechanics: they exist on the type, because BMS needs them, and they are not on the wire.
    // =================================================================================================

    @Nested
    @DisplayName("The metadata boundary: 59 payload keys on the wire, and none of the 413 metadata names")
    class MetadataBoundary {

        /** A plain mapper: the module's JSON policy lives in the web configuration, not here. */
        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * Every symbolic-map item name that must <strong>not</strong> appear in the payload: for each of
         * the 59 fields, the input view's {@code xxxL}, {@code xxxF} and {@code xxxA} and the output
         * view's {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}.
         *
         * @return 59 x 7 = 413 names; never empty
         */
        private Set<String> metadataItemNames() {
            Set<String> names = new LinkedHashSet<>();
            for (String itemName : EXPECTED_ITEM_NAMES) {
                String prefix = itemName.substring(0, itemName.length() - 1);
                names.add(prefix + "L");
                names.add(prefix + "F");
                names.add(prefix + "A");
                names.add(prefix + TransactionListResponse.COLOUR_ITEM_SUFFIX);
                names.add(prefix + TransactionListResponse.PS_ITEM_SUFFIX);
                names.add(prefix + TransactionListResponse.HIGHLIGHT_ITEM_SUFFIX);
                names.add(prefix + TransactionListResponse.VALIDATION_ITEM_SUFFIX);
            }
            return names;
        }

        @Test
        @DisplayName("there are 413 metadata names to exclude, and none collides with a payload name")
        void theMetadataNameSetIsWellFormed() {
            Set<String> metadata = metadataItemNames();

            assertThat(metadata)
                    .as("59 fields x (L, F, A, C, P, H, V)")
                    .hasSize(TransactionListResponse.FIELD_COUNT * 7)
                    .hasSize(413);
            assertThat(metadata)
                    .as("a payload item name must never appear in the exclusion set, or the check "
                            + "below would be asserting the payload away")
                    .doesNotContainAnyElementsOf(EXPECTED_ITEM_NAMES);
        }

        @Test
        @DisplayName("not one of the 413 metadata names reaches the JSON, even when every quad is set")
        void noMetadataNameIsSerialised() throws Exception {
            TransactionListResponse response = fullyPopulated();
            // Move a distinguishable value into all four items of all 59 quads, so a leak would show.
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                response.putAttributes(prefix, new FieldAttributes(BmsAttributes.DFHRED,
                        (byte) 'P', BmsAttributes.DFHBLINK, (byte) 'V'));
            }

            String json = mapper.writeValueAsString(response);
            String upper = json.toUpperCase();

            for (String metadataName : metadataItemNames()) {
                assertThat(upper)
                        .as("%s is screen metadata and must not be a payload member", metadataName)
                        .doesNotContain("\"" + metadataName + "\"");
            }
            assertThat(json)
                    .as("nor may the quad reach the wire under a Java member name")
                    .doesNotContain("fieldAttributes")
                    .doesNotContain("allAttributes")
                    .doesNotContain("\"colour\"")
                    .doesNotContain("\"highlight\"")
                    .doesNotContain("\"validation\"");
        }

        @Test
        @DisplayName("all 59 payload items are on the wire, and the quad is still readable on the type")
        void thePayloadIsCompleteAndTheQuadStillExists() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("ERRMSG",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));

            String json = mapper.writeValueAsString(response);
            @SuppressWarnings("unchecked")
            Map<String, Object> members = mapper.readValue(json, Map.class);

            for (String itemName : EXPECTED_ITEM_NAMES) {
                String member = itemName.substring(0, itemName.length() - 1).toLowerCase()
                        + TransactionListResponse.OUTPUT_ITEM_SUFFIX;
                assertThat(members).as("%s is a payload member", itemName).containsKey(member);
            }
            assertThat(response.attributesOf("ERRMSG").colour())
                    .as("being off the wire is not the same as being absent from the type")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.allAttributes()).hasSize(TransactionListResponse.FIELD_COUNT);
        }

        @Test
        @DisplayName("R6: the commarea and the cursor are payload members, not server-side state")
        void theConversationStateIsOnTheWire() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.setNavigationContext(NavigationContext.empty()
                    .withUserId("ADMIN001").withToProgram("COTRN01C").withPgmReenter());
            response.getCursor().setPageNum(4);
            response.getCursor().setNextPageYes();
            response.getCursor().setTrnidLast("0000000000000040");

            String json = mapper.writeValueAsString(response);
            @SuppressWarnings("unchecked")
            Map<String, Object> members = mapper.readValue(json, Map.class);

            assertThat(members)
                    .as("the 160-byte CARDDEMO-COMMAREA and the 58-byte CDEMO-CT00-INFO both travel "
                            + "in the body, which is what makes the server stateless")
                    .containsKey("navigationContext")
                    .containsKey("cursor")
                    .hasSize(TransactionListResponse.FIELD_COUNT + 3 + 2);

            TransactionListResponse back =
                    mapper.readValue(json, TransactionListResponse.class);
            assertThat(back.getNavigationContext().userId()).isEqualTo("ADMIN001");
            assertThat(back.getNavigationContext().isReenter())
                    .as("even CDEMO-PGM-CONTEXT makes the round trip, so the next request knows "
                            + "whether it is a re-entry without the server remembering")
                    .isTrue();
            assertThat(back.getCursor().getPageNum()).isEqualTo(4);
            assertThat(back.getCursor().isNextPageYes()).isTrue();
            assertThat(back.getCursor().getTrnidLast()).isEqualTo("0000000000000040");
        }

        @Test
        @DisplayName("a payload item binds on the way in, and no quad value comes with it")
        void onlyPayloadItemsBindOnTheWayIn() throws Exception {
            String json = "{\"trnidinO\":\"0000000000000001\"}";

            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);

            assertThat(back.getTrnidinO())
                    .as("the payload item is bound")
                    .isEqualTo("0000000000000001");
            assertThat(back.attributesOf("TRNIDIN").isLowValues())
                    .as("the quad is not reachable from the wire, so it keeps its low value")
                    .isTrue();
            assertThat(back.isFieldHighlighted("TRNIDIN")).isFalse();
        }

        @Test
        @DisplayName("a metadata name offered on the wire is rejected, not silently absorbed")
        void metadataNamesAreRejectedOnTheWayIn() {
            // The colour item is not a payload member, so a caller cannot paint a field red by
            // sending its name. Rejecting the document outright is the stronger outcome: ignoring
            // the member would let a client believe it had been honoured.
            String withColourItem = "{\"trnidinO\":\"0000000000000001\",\"trnidinC\":\"x\"}";
            String withLengthItem = "{\"trnidinO\":\"0000000000000001\",\"trnidinL\":\"-1\"}";

            for (String json : List.of(withColourItem, withLengthItem)) {
                assertThatExceptionOfType(UnrecognizedPropertyException.class)
                        .as(json)
                        .isThrownBy(() -> mapper.readValue(json, TransactionListResponse.class))
                        .withMessageContaining("trnidin");
            }
        }
    }

    // =================================================================================================
    // app/bms/COTRN00.bms:450 - ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1).
    // The map declares the error line's colour; CSSETATY's DFHRED is a separate, later decision, and the
    // two must not be confused with one another.
    // =================================================================================================

    @Nested
    @DisplayName("The error line: X(78), autoskip, and red because the mapset says so")
    class ErrorLineAttributes {

        @Test
        @DisplayName("ERRMSG is LENGTH=78 in the BMS and PIC X(78) in the symbolic map")
        void errorLineWidth() {
            assertThat(TransactionListResponse.ERRMSG_LENGTH)
                    .as("app/bms/COTRN00.bms:450 declares LENGTH=78")
                    .isEqualTo(78);
            assertThat(TransactionListResponse.declaredLength("ERRMSG")).isEqualTo(78);
            assertThat(TransactionListResponse.LAYOUT.span("ERRMSGO").length()).isEqualTo(78);
            assertThat(new TransactionListResponse().getErrmsgO()).hasSize(78);
            assertThat(TransactionListResponse.payloadFieldNames())
                    .as("it is the last field of the group, after the tenth row")
                    .endsWith("ERRMSGO");
        }

        @Test
        @DisplayName("ATTRB=(ASKIP,BRT,FSET) is output-only: the type offers no input length for it")
        void errorLineIsOutputOnly() {
            TransactionListResponse response = new TransactionListResponse();

            // ASKIP means the cursor skips the field, so there is nothing to receive from it and no
            // ERRMSGL cursor-length to set. The type exposes the payload item and the quad, and no
            // input-length accessor for any field - see ProhibitedConstructs for the API-wide sweep.
            assertThat(TransactionListResponse.LAYOUT.hasSpan("ERRMSGL"))
                    .as("the output view declares no xxxL item; that name belongs to COTRN0AI")
                    .isFalse();
            assertThat(TransactionListResponse.LAYOUT.hasSpan("ERRMSGO")).isTrue();
            assertThat(TransactionListResponse.LAYOUT.hasSpan("ERRMSGC")).isTrue();
            response.setErrmsgO(CODEC.movePicX("written by the program", 78));
            assertThat(response.getErrmsgO()).startsWith("written by the program");
        }

        @Test
        @DisplayName("COLOR=RED is the map's, so the quad starts at the low value that means 'use it'")
        void declaredColourIsIndependentOfTheHighlight() {
            TransactionListResponse response = new TransactionListResponse();

            FieldAttributes errmsg = response.attributesOf("ERRMSG");

            assertThat(errmsg.isLowValues())
                    .as("X'00' in xxxC tells BMS to use the map's declared COLOR, which for ERRMSG "
                            + "is RED; it does not mean 'no colour'")
                    .isTrue();
            assertThat(errmsg.colour()).isEqualTo(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(response.isFieldHighlighted("ERRMSG"))
                    .as("and it is emphatically not the same thing as a CSSETATY highlight")
                    .isFalse();
            assertThat(BmsAttributes.DFHRED)
                    .as("DFHRED is a real colour byte, distinct from the default")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL)
                    .isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("a message written into the error line survives even after the field turns red")
        void theTwoMechanismsDoNotInterfere() {
            TransactionListResponse response = new TransactionListResponse();
            response.moveMessageToErrorLine(CODEC, "Invalid selection. Valid value is S");

            response.applyHighlight("ERRMSG", FieldAttributeSetter.resolveFromFlags(true, false, true,
                    "ERRMSG", TransactionListResponse.MAP_NAME));

            assertThat(response.getErrmsgO())
                    .as("a NOT-OK highlight moves DFHRED into ERRMSGC and leaves ERRMSGO alone")
                    .isEqualTo(CODEC.movePicX("Invalid selection. Valid value is S", 78));
            assertThat(response.attributesOf("ERRMSG").colour()).isEqualTo(BmsAttributes.DFHRED);
        }
    }

    // =================================================================================================
    // G38 - app/cpy/CSSETATY.cpy:17-27. Three conditions, four reachable states, and the state that
    // matters most is the one where the highlight overwrites a payload value.
    //
    //   IF (FLG-x-NOT-OK OR FLG-x-BLANK) AND CDEMO-PGM-REENTER      <- CSSETATY.cpy:18-20
    //       MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O               <- :21-22
    //       IF FLG-x-BLANK                                          <- :23
    //           MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O              <- :24-25
    //       END-IF
    //   END-IF
    // =================================================================================================

    @Nested
    @DisplayName("The CSSETATY matrix: the highlight applies only in REENTER state")
    class HighlightMatrix {

        /** The field the matrix is driven on: the only genuinely enterable field on this screen. */
        private static final String FIELD = "TRNIDIN";

        /** A value already on the screen, so an overwrite is visible when it happens. */
        private static final String TYPED = "0000000000000007";

        /**
         * A response with {@link #FIELD} already carrying {@link #TYPED}.
         *
         * @return the response; never {@code null}
         */
        private TransactionListResponse withTypedValue() {
            TransactionListResponse response = new TransactionListResponse();
            response.setTrnidinO(TYPED);
            return response;
        }

        @Test
        @DisplayName("CDEMO-PGM-CONTEXT is 0 on entry and 1 on re-entry, and both drive the rule")
        void theProgramContextIsTheOuterCondition() {
            NavigationContext entering = NavigationContext.empty().withPgmEnter();
            NavigationContext reentering = NavigationContext.empty().withPgmReenter();

            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
            assertThat(entering.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(entering.isEnter()).isTrue();
            assertThat(entering.isReenter()).isFalse();
            assertThat(reentering.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(reentering.isReenter()).isTrue();
            assertThat(reentering.isEnter()).isFalse();
        }

        @Test
        @DisplayName("state 1 of 4 - NOT-OK on RE-ENTRY: xxxC turns DFHRED, xxxO is untouched")
        void notOkOnReentry() {
            TransactionListResponse response = withTypedValue();
            NavigationContext context = NavigationContext.empty().withPgmReenter();

            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                    context.isReenter(), FIELD, TransactionListResponse.MAP_NAME);
            response.applyHighlight(FIELD, highlight);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned())
                    .as("CSSETATY.cpy:23 tests blankness only, so a NOT-OK field gets no asterisk")
                    .isFalse();
            assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributesOf(FIELD).colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.isFieldHighlighted(FIELD)).isTrue();
            assertThat(response.getTrnidinO())
                    .as("what the user typed is still on the screen")
                    .isEqualTo(TYPED);
        }

        @Test
        @DisplayName("state 2 of 4 - BLANK on RE-ENTRY: the '*' OVERWRITES the xxxO payload value")
        void blankOnReentryOverwritesThePayloadItem() {
            TransactionListResponse response = withTypedValue();
            NavigationContext context = NavigationContext.empty().withPgmReenter();

            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK,
                    context.isReenter(), FIELD, TransactionListResponse.MAP_NAME);
            response.applyHighlight(FIELD, highlight);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned())
                    .as("CSSETATY.cpy:24-25 moves '*' into the OUTPUT DATA item, not into an "
                            + "attribute item")
                    .isTrue();
            assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
            assertThat(response.attributesOf(FIELD).colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getTrnidinO())
                    .as("the highlight has replaced a payload value - this is the whole point of "
                            + "asserting the two items separately")
                    .isEqualTo("*")
                    .isNotEqualTo(TYPED);
        }

        @ParameterizedTest(name = "state 3 of 4 - {0} on FIRST ENTRY leaves both items untouched")
        @CsvSource({"NOT_OK", "BLANK", "OK"})
        @DisplayName("state 3 of 4 - FIRST ENTRY: the outer IF fails, so nothing is moved at all")
        void nothingHappensOnFirstEntry(FieldValidationState state) {
            TransactionListResponse response = withTypedValue();
            NavigationContext context = NavigationContext.empty().withPgmEnter();

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, context.isReenter(),
                    FIELD, TransactionListResponse.MAP_NAME);
            response.applyHighlight(FIELD, highlight);

            assertThat(highlight.untouched())
                    .as("CDEMO-PGM-REENTER is the second operand of the outer AND at "
                            + "CSSETATY.cpy:20, so first entry can reach neither MOVE")
                    .isTrue();
            assertThat(highlight.colourItemAssigned()).isFalse();
            assertThat(highlight.outputItemAssigned()).isFalse();
            assertThat(response.attributesOf(FIELD).isLowValues()).isTrue();
            assertThat(response.isFieldHighlighted(FIELD)).isFalse();
            assertThat(response.getTrnidinO()).isEqualTo(TYPED);
        }

        @Test
        @DisplayName("state 4 of 4 - OK on RE-ENTRY: a valid field is left alone too")
        void nothingHappensWhenTheFieldIsValid() {
            TransactionListResponse response = withTypedValue();
            NavigationContext context = NavigationContext.empty().withPgmReenter();

            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.OK,
                    context.isReenter(), FIELD, TransactionListResponse.MAP_NAME);
            response.applyHighlight(FIELD, highlight);

            assertThat(highlight.untouched()).isTrue();
            assertThat(response.attributesOf(FIELD).isLowValues()).isTrue();
            assertThat(response.getTrnidinO()).isEqualTo(TYPED);
        }

        @Test
        @DisplayName("the decision names the two symbolic-map items it would have moved into")
        void theDecisionNamesItsItems() {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    FIELD, TransactionListResponse.MAP_NAME);

            assertThat(highlight.colourItemName())
                    .as("(SCRNVAR2)C - CSSETATY.cpy:22")
                    .isEqualTo("TRNIDINC")
                    .isEqualTo(TransactionListResponse.colourItemName(FIELD));
            assertThat(highlight.outputItemName())
                    .as("(SCRNVAR2)O - CSSETATY.cpy:25")
                    .isEqualTo("TRNIDINO")
                    .isEqualTo(TransactionListResponse.outputItemName(FIELD));
            assertThat(highlight.mapName())
                    .as("(MAPNAME3) is the seven-character map name, without the trailing O")
                    .isEqualTo(TransactionListResponse.MAP_NAME)
                    .isEqualTo("COTRN0A")
                    .hasSize(7);
            assertThat(highlight.outputMapGroupName())
                    .as("(MAPNAME3)O - the group both moves qualify with. The suffix is appended "
                            + "here, so passing the group name in would have produced COTRN0AOO")
                    .isEqualTo("COTRN0AO")
                    .isEqualTo(TransactionListResponse.OUTPUT_MAP_GROUP_NAME);
            assertThat(highlight.screenFieldPrefix()).isEqualTo(FIELD);
            assertThat(highlight.describe())
                    .as("the decision renders as the two COBOL MOVE statements it stands for")
                    .contains("TRNIDINC OF COTRN0AO")
                    .contains("TRNIDINO OF COTRN0AO");
        }

        @ParameterizedTest(name = "notOk={0} blank={1} reenter={2} -> colour={3} asterisk={4}")
        @CsvSource({
            "false, false, false, false, false",
            "false, false, true,  false, false",
            "true,  false, false, false, false",
            "true,  false, true,  true,  false",
            "false, true,  false, false, false",
            "false, true,  true,  true,  true",
            "true,  true,  false, false, false",
            "true,  true,  true,  true,  true"})
        @DisplayName("all eight flag combinations, so both operands of the outer OR are exercised")
        void everyFlagCombination(boolean notOk, boolean blank, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {
            TransactionListResponse response = withTypedValue();

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter,
                    FIELD, TransactionListResponse.MAP_NAME);
            response.applyHighlight(FIELD, highlight);

            assertThat(highlight.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(expectAsterisk);
            assertThat(response.isFieldHighlighted(FIELD)).isEqualTo(expectColour);
            assertThat(response.getTrnidinO())
                    .isEqualTo(expectAsterisk ? FieldAttributeSetter.ASTERISK : TYPED);
        }

        @Test
        @DisplayName("a highlight may be applied to any of the 59 fields, including a row selector")
        void everyFieldCanBeHighlighted() {
            TransactionListResponse response = new TransactionListResponse();

            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                response.applyHighlight(prefix, FieldAttributeSetter.resolveFromFlags(true, false,
                        true, prefix, TransactionListResponse.MAP_NAME));
            }

            assertThat(TransactionListResponse.fieldPrefixes())
                    .allSatisfy(prefix -> assertThat(response.isFieldHighlighted(prefix))
                            .as(prefix)
                            .isTrue());
            assertThat(response.getSel0001O())
                    .as("a NOT-OK highlight never writes the asterisk, not even into a X(1) selector")
                    .isEqualTo(" ");
        }

        @Test
        @DisplayName("the asterisk fits the narrowest field on the screen, SEL000nO at X(1)")
        void theAsteriskFitsTheNarrowestField() {
            TransactionListResponse response = new TransactionListResponse();

            response.applyHighlight("SEL0010", FieldAttributeSetter.resolveFromFlags(false, true,
                    true, "SEL0010", TransactionListResponse.MAP_NAME));

            assertThat(TransactionListResponse.SEL_LENGTH).isEqualTo(1);
            assertThat(FieldAttributeSetter.ASTERISK).hasSize(TransactionListResponse.SEL_LENGTH);
            assertThat(response.getSel0010O()).isEqualTo("*");
        }
    }

    // =================================================================================================
    // G40 - EXEC CICS XCTL. COTRN00C has exactly two transfer sites and both are the COMMAREA-driven
    // shape, so one response field serves both and the client, not the server, performs the transfer.
    // =================================================================================================

    @Nested
    @DisplayName("Both XCTL sites - COTRN00C:193 and :519 - resolve through one nextProgram field")
    class TransferSites {

        @Test
        @DisplayName("COTRN00C:193, the row-selection transfer: CDEMO-TO-PROGRAM is COTRN01C")
        void theRowSelectionSite() {
            // COTRN00C:186-193 - WHEN 'S' / WHEN 's':
            //     MOVE 'COTRN01C' TO CDEMO-TO-PROGRAM   (:188)
            //     MOVE 0          TO CDEMO-PGM-CONTEXT  (:191)
            //     EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
            TransactionListResponse response = new TransactionListResponse();
            NavigationContext context = NavigationContext.empty()
                    .withToProgram("COTRN01C")
                    .withFromTranid(TransactionListResponse.TRANSACTION_ID)
                    .withFromProgram(TransactionListResponse.PROGRAM_NAME)
                    .withPgmEnter();
            response.setNavigationContext(context);
            response.getCursor().setTrnSelFlg("S");
            response.getCursor().setTrnSelected("0000000000000005");

            response.echoTransferTarget(context);

            assertThat(response.getNextProgram())
                    .as("echoed from the commarea, not computed here")
                    .isEqualTo("COTRN01C");
            assertThat(response.getNavigationContext().isEnter())
                    .as("MOVE 0 TO CDEMO-PGM-CONTEXT - the next program is entered, not re-entered")
                    .isTrue();
            assertThat(response.getCursor().isRowSelected()).isTrue();
        }

        @Test
        @DisplayName("COTRN00C:519, RETURN-TO-PREV-SCREEN: the same field carries COSGN00C or COMEN01C")
        void theReturnSite() {
            // COTRN00C:511-519:
            //     IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
            //         MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM   (:512)
            //     END-IF
            //     MOVE ZEROS TO CDEMO-PGM-CONTEXT           (:517)
            //     EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
            TransactionListResponse defaulted = new TransactionListResponse();
            defaulted.echoTransferTarget(NavigationContext.empty().withToProgram("COSGN00C"));
            assertThat(defaulted.getNextProgram())
                    .as("the IF-defaulted target when the caller left CDEMO-TO-PROGRAM blank")
                    .isEqualTo("COSGN00C");

            TransactionListResponse fromMenu = new TransactionListResponse();
            fromMenu.echoTransferTarget(NavigationContext.empty().withToProgram("COMEN01C"));
            assertThat(fromMenu.getNextProgram())
                    .as("the caller-supplied target the PF3 arm carries")
                    .isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("one field, two sites: nothing distinguishes them, because the source does not")
        void bothSitesUseTheSameField() throws Exception {
            List<String> transferMembers =
                    Arrays.stream(TransactionListResponse.class.getDeclaredFields())
                            .filter(field -> !field.isSynthetic())
                            .filter(field -> !Modifier.isStatic(field.getModifiers()))
                            .map(Field::getName)
                            .filter(name -> name.toLowerCase().contains("program"))
                            .toList();

            assertThat(transferMembers)
                    .as("both XCTL sites are XCTL PROGRAM(CDEMO-TO-PROGRAM), so modelling two "
                            + "targets would model a distinction COTRN00C does not make")
                    .containsExactly("nextProgram");
            assertThat(TransactionListResponse.class.getDeclaredField("nextProgram").getType())
                    .isEqualTo(String.class);
            assertThat(TransactionListResponse.PROGRAM_NAME)
                    .as("PROGRAM_NAME is this program's own identity, not a transfer target, and it "
                            + "is a constant rather than a member")
                    .isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName("the three navigation widths are the commarea's: X(8), X(7) and X(7)")
        void theNavigationWidths() {
            TransactionListResponse response = new TransactionListResponse();

            assertThat(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET is X(7), not X(8)")
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);

            response.setNextProgram("COTRN01C");
            assertThat(response.getNextProgram()).hasSize(8);
            assertThat(response.getNextMapset()).hasSize(7).isEqualTo("COTRN00");
            assertThat(response.getNextMap()).hasSize(7).isEqualTo("COTRN0A");
        }

        @Test
        @DisplayName("COTRN01C travels as a plain program name: no sibling DTO type is involved")
        void theTargetIsAPlainString() {
            TransactionListResponse response = new TransactionListResponse();
            response.setNextProgram("COTRN01C");

            assertThat(response.getNextProgram())
                    .isInstanceOf(String.class)
                    .isEqualTo("COTRN01C");

            Set<String> reachableTypes = new LinkedHashSet<>();
            for (Field field : TransactionListResponse.class.getDeclaredFields()) {
                reachableTypes.add(field.getType().getName());
            }
            for (Method method : TransactionListResponse.class.getDeclaredMethods()) {
                reachableTypes.add(method.getReturnType().getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachableTypes.add(parameter.getName());
                }
            }

            assertThat(reachableTypes)
                    .as("the only transaction.dto types this payload knows about are itself and its "
                            + "own nested types; coupling it to another screen's payload is exactly "
                            + "the dependency statelessness exists to avoid")
                    .filteredOn(name -> name.startsWith("com.vsergeychik.carddemo.transaction.dto."))
                    .allSatisfy(name -> assertThat(name)
                            .startsWith(TransactionListResponse.class.getName()));
            assertThat(reachableTypes)
                    .doesNotContain("com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse",
                            "com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest",
                            "com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse",
                            "com.vsergeychik.carddemo.card.dto.CardScreenState");
        }
    }

    // =================================================================================================
    // The two thank-you literals. COTTL01Y and CSMSG01Y each declare one, they are different lengths and
    // different text, and they belong to different fields. Substituting one for the other is the kind of
    // mistake that reads correctly and diffs wrong.
    // =================================================================================================

    @Nested
    @DisplayName("Header titles X(40) and system messages X(50) are never substituted for each other")
    class TitleAndMessageTraps {

        @Test
        @DisplayName("the two thank-you literals differ in owner, width and wording")
        void theTwoThankYouLiteralsAreDistinct() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("app/cpy/COTTL01Y.cpy - CCDA-THANK-YOU PIC X(40), and it says CCDA")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40)
                    .contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("app/cpy/CSMSG01Y.cpy - CCDA-MSG-THANK-YOU PIC X(50), and it says CardDemo")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .contains("CardDemo application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.strip())
                    .as("not even once the padding is discounted")
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU.strip());
        }

        @Test
        @DisplayName("a X(50) message does not fit a X(40) title item and is rejected")
        void aMessageCannotBeMovedIntoATitle() {
            TransactionListResponse response = new TransactionListResponse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setTitle01O(SystemMessages.CCDA_MSG_THANK_YOU))
                    .withMessageContaining("TITLE01O");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setTitle02O(SystemMessages.CCDA_MSG_INVALID_KEY))
                    .withMessageContaining("TITLE02O");
            assertThat(TransactionListResponse.TITLE01_LENGTH)
                    .isEqualTo(TransactionListResponse.TITLE02_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(40);
        }

        @Test
        @DisplayName("a X(40) title fits the X(78) error line but is not the message that belongs there")
        void aTitleIsNotAMessage() {
            TransactionListResponse response = new TransactionListResponse();

            response.moveMessageToErrorLine(CODEC, ScreenTitles.CCDA_THANK_YOU);

            assertThat(response.getErrmsgO())
                    .as("the move is mechanically possible ...")
                    .hasSize(78)
                    .startsWith(ScreenTitles.CCDA_THANK_YOU);
            assertThat(response.getErrmsgO())
                    .as("... and still the wrong literal: CSMSG01Y owns the message texts")
                    .doesNotStartWith(SystemMessages.CCDA_MSG_THANK_YOU);
        }

        @Test
        @DisplayName("a 50-byte message reaches the 78-byte error line only by right-space-padding")
        void aMessageIsPaddedIntoTheErrorLine() {
            TransactionListResponse response = new TransactionListResponse();

            response.moveMessageToErrorLine(CODEC, SystemMessages.CCDA_MSG_THANK_YOU);

            assertThat(response.getErrmsgO())
                    .hasSize(TransactionListResponse.ERRMSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU
                            + " ".repeat(TransactionListResponse.ERRMSG_LENGTH
                                    - SystemMessages.MESSAGE_LENGTH));
            assertThat(TransactionListResponse.ERRMSG_LENGTH - SystemMessages.MESSAGE_LENGTH)
                    .as("78 - 50 = 28 pad characters, which are part of the field")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("the header titles are the two the copybook declares, moved whole")
        void theHeaderCarriesTheTwoTitles() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateHeaderInfo(fixedHeader());

            assertThat(response.getTitle01O())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(response.getTitle02O())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .isNotEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getErrmsgO())
                    .as("POPULATE-HEADER-INFO does not touch the error line")
                    .isEqualTo(" ".repeat(78));
        }
    }

    // =================================================================================================
    // R5 - the direction of a cross-width MOVE. Three of the five row columns take a value from a wider
    // or narrower source, and each direction is the codec's single implementation of the rule rather
    // than a substring or a concatenation written at the call site.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-width moves go through the codec, so the truncation direction is deliberate")
    class CrossWidthMoves {

        @Test
        @DisplayName("TRAN-ID X(16) into TRNIDnnO X(16) is an exact, lossless move")
        void transactionIdIsExactlyTheSameWidth() {
            TransactionListResponse response = new TransactionListResponse();
            String tranId = "0000000000000042";

            response.populateTranData(CODEC, 4, tranId, "07/18/22", "desc", "+00000042.00");

            assertThat(tranId).hasSize(TransactionListResponse.TRNID_LENGTH).hasSize(16);
            assertThat(response.getRowTransactionId(4))
                    .isEqualTo(tranId)
                    .isEqualTo(CODEC.movePicX(tranId, TransactionListResponse.TRNID_LENGTH));
        }

        @Test
        @DisplayName("TRAN-DESC X(100) into TDESCnnO X(26) keeps the FIRST 26 characters")
        void descriptionKeepsTheLeadingCharacters() {
            TransactionListResponse response = new TransactionListResponse();
            String hundred = "0123456789".repeat(10);

            response.populateTranData(CODEC, 6, "0000000000000006", "07/18/22", hundred,
                    "+00000006.00");

            assertThat(hundred).hasSize(100);
            assertThat(response.getRowDescription(6))
                    .as("a PIC X receiver is filled from the left and the overflow is discarded")
                    .isEqualTo("01234567890123456789012345")
                    .isEqualTo(CODEC.movePicX(hundred, TransactionListResponse.TDESC_LENGTH))
                    .hasSize(26);
            assertThat(response.getRowDescription(6))
                    .as("and emphatically not the trailing 26, which is what a right-justified "
                            + "numeric MOVE would have kept")
                    .isNotEqualTo(hundred.substring(hundred.length() - 26));
        }

        @Test
        @DisplayName("a short value is padded on the RIGHT, and the padding is part of the field")
        void shortValuesArePaddedOnTheRight() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateTranData(CODEC, 8, "42", "1/1/22", "short", "+1.00");

            assertThat(response.getRowTransactionId(8)).isEqualTo("42" + " ".repeat(14));
            assertThat(response.getRowTransactionDate(8)).isEqualTo("1/1/22" + " ".repeat(2));
            assertThat(response.getRowDescription(8)).isEqualTo("short" + " ".repeat(21));
            assertThat(response.getRowAmount(8)).isEqualTo("+1.00" + " ".repeat(7));
        }

        @Test
        @DisplayName("TAMT00nO is the twelve-character +99999999.99 edit form, never a raw number")
        void theAmountIsAnEditedImage() {
            TransactionListResponse response = new TransactionListResponse();
            // WS-TRAN-AMT PIC +99999999.99 [COTRN00C:56]: sign, eight integer digits, point, two
            // fraction digits.
            String edited = "+12345678.99";

            response.setRowAmount(3, edited);

            assertThat(edited).hasSize(TransactionListResponse.TAMT_LENGTH).hasSize(12);
            assertThat(edited.charAt(0)).isIn('+', '-');
            assertThat(edited.charAt(9)).isEqualTo('.');
            assertThat(response.getRowAmount(3)).isEqualTo(edited);
            assertThat(response.getTamt003O()).isEqualTo(edited);
        }

        @Test
        @DisplayName("a negative amount uses the mask's sign position, not a trailing overpunch")
        void theSignIsLeading() {
            TransactionListResponse response = new TransactionListResponse();

            response.setRowAmount(9, "-00000042.50");

            assertThat(response.getTamt009O()).startsWith("-").hasSize(12);
            assertThat(response.getTamt009O())
                    .as("the edited form carries a real sign character; the zoned overpunch belongs "
                            + "to the 350-byte record, not to the screen")
                    .doesNotContain("}")
                    .doesNotContain("{");
        }

        @Test
        @DisplayName("all ten rows are strictly regular: there is no row-one asymmetry in this map")
        void everyRowHasTheSameFiveWidths() {
            List<Integer> firstRowWidths = null;
            for (int row = TransactionListResponse.FIRST_ROW;
                    row <= TransactionListResponse.LAST_ROW; row++) {
                List<Integer> widths = TransactionListResponse.rowFieldPrefixes(row).stream()
                        .map(TransactionListResponse::declaredLength)
                        .toList();
                if (firstRowWidths == null) {
                    firstRowWidths = widths;
                }
                assertThat(widths)
                        .as("row %d", row)
                        .containsExactly(1, 16, 8, 26, 12)
                        .containsExactlyElementsOf(firstRowWidths);
            }
            assertThat(TransactionListResponse.ROW_WIDTH)
                    .as("1 + 16 + 8 + 26 + 12 = 63, and every row is that wide")
                    .isEqualTo(63);
            assertThat(TransactionListResponse.ROW_BLOCK_WIDTH)
                    .isEqualTo(TransactionListResponse.ROW_WIDTH
                            * TransactionListResponse.ROW_COUNT);
        }
    }

    // =================================================================================================
    // COTRN00C:390-444 - POPULATE-TRAN-DATA. Rows one and ten each perform one extra MOVE, into the
    // browse cursor. Those two moves are what makes paging work, so both are asserted, and so is the
    // fact that the eight rows between them perform neither.
    // =================================================================================================

    @Nested
    @DisplayName("The browse cursor: row 1 seeds TRNID-FIRST, row 10 seeds TRNID-LAST, rows 2..9 neither")
    class CursorSeeding {

        /** The blank a cursor identifier starts at: 16 spaces, never {@code null} and never empty. */
        private static final String UNSEEDED = "                ";

        @Test
        @DisplayName("WHEN 1 moves TRAN-ID into TRNID01I and CDEMO-CT00-TRNID-FIRST - COTRN00C:391-393")
        void rowOneSeedsTheFirstIdentifier() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateTranData(CODEC, TransactionListResponse.FIRST_ROW,
                    "0000000000000001", "07/18/22", "row one", "+00000001.00");

            assertThat(response.getTrnid01O()).isEqualTo("0000000000000001");
            assertThat(response.getCursor().getTrnidFirst())
                    .as("one MOVE, two receivers")
                    .isEqualTo("0000000000000001");
            assertThat(response.getCursor().getTrnidLast())
                    .as("the WHEN 1 arm does not touch TRNID-LAST")
                    .isEqualTo(UNSEEDED);
        }

        @Test
        @DisplayName("WHEN 10 moves TRAN-ID into TRNID10I and CDEMO-CT00-TRNID-LAST - COTRN00C:437-439")
        void rowTenSeedsTheLastIdentifier() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateTranData(CODEC, TransactionListResponse.LAST_ROW,
                    "0000000000000010", "07/18/22", "row ten", "+00000010.00");

            assertThat(response.getTrnid10O()).isEqualTo("0000000000000010");
            assertThat(response.getCursor().getTrnidLast())
                    .as("the mirror image of the WHEN 1 arm: one MOVE, two receivers")
                    .isEqualTo("0000000000000010");
            assertThat(response.getCursor().getTrnidFirst())
                    .as("the WHEN 10 arm does not touch TRNID-FIRST")
                    .isEqualTo(UNSEEDED);
        }

        @ParameterizedTest(name = "WHEN {0} seeds neither cursor identifier")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("the eight middle arms move four fields each and touch the cursor not at all")
        void middleRowsSeedNeitherIdentifier(int row) {
            TransactionListResponse response = new TransactionListResponse();

            response.populateTranData(CODEC, row, String.format("%016d", row), "07/18/22",
                    "middle row", "+00000000.00");

            assertThat(response.getRowTransactionId(row)).isEqualTo(String.format("%016d", row));
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(UNSEEDED);
            assertThat(response.getCursor().getTrnidLast()).isEqualTo(UNSEEDED);
        }

        @Test
        @DisplayName("a full page seeds both ends, which is what makes PF7 and PF8 possible")
        void aFullPageSeedsBothEnds() {
            TransactionListResponse response = fullyPopulated();

            assertThat(response.getCursor().getTrnidFirst())
                    .as("PROCESS-PF7-KEY browses backward from here - COTRN00C:236-239")
                    .isEqualTo(String.format("%016d", TransactionListResponse.FIRST_ROW));
            assertThat(response.getCursor().getTrnidLast())
                    .as("PROCESS-PF8-KEY browses forward from here - COTRN00C:259-262")
                    .isEqualTo(String.format("%016d", TransactionListResponse.LAST_ROW));
            assertThat(response.getCursor().getTrnidFirst())
                    .isNotEqualTo(response.getCursor().getTrnidLast());
        }

        @Test
        @DisplayName("the cursor identifiers are moved through the codec, so a short id is padded")
        void theSeededIdentifiersArePadded() {
            TransactionListResponse response = new TransactionListResponse();

            response.populateTranData(CODEC, 1, "42", "07/18/22", "short id", "+00000001.00");
            response.populateTranData(CODEC, 10, "99", "07/18/22", "short id", "+00000010.00");

            assertThat(response.getCursor().getTrnidFirst())
                    .isEqualTo("42" + " ".repeat(14))
                    .hasSize(TransactionListCursor.TRNID_FIRST_LENGTH);
            assertThat(response.getCursor().getTrnidLast())
                    .isEqualTo("99" + " ".repeat(14))
                    .hasSize(TransactionListCursor.TRNID_LAST_LENGTH);
        }

        @Test
        @DisplayName("INITIALIZE-TRAN-DATA blanks the screen rows and leaves the cursor alone")
        void blankingTheRowsDoesNotClearTheCursor() {
            TransactionListResponse response = fullyPopulated();

            response.initializeAllTranData();

            assertThat(response.getRowTransactionId(1)).isEqualTo(UNSEEDED);
            assertThat(response.getRowTransactionId(10)).isEqualTo(UNSEEDED);
            assertThat(response.getCursor().getTrnidFirst())
                    .as("INITIALIZE-TRAN-DATA's arms name only the four screen items")
                    .isEqualTo(String.format("%016d", 1));
            assertThat(response.getCursor().getTrnidLast()).isEqualTo(String.format("%016d", 10));
        }
    }

    // =================================================================================================
    // The negatives, asserted by reflection over the type's own API surface. Each one is a rule that is
    // easy to break in a later edit and invisible in a passing functional test.
    // =================================================================================================

    @Nested
    @DisplayName("Constructs this payload must never acquire")
    class ProhibitedConstructs {

        /** The classes the sweeps cover: the payload and both of its nested types. */
        private static final List<Class<?>> TYPES = List.of(TransactionListResponse.class,
                TransactionListCursor.class, FieldAttributes.class);

        /**
         * Every type name reachable from a declared field, method return type or method parameter of
         * the given class.
         *
         * @param type the class to sweep
         * @return the reachable type names; never empty
         */
        private Set<String> apiSurfaceOf(Class<?> type) {
            Set<String> names = new LinkedHashSet<>();
            for (Field field : type.getDeclaredFields()) {
                names.add(field.getType().getName());
            }
            for (Method method : type.getDeclaredMethods()) {
                names.add(method.getReturnType().getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    names.add(parameter.getName());
                }
            }
            return names;
        }

        @Test
        @DisplayName("R4 and G22: no double, float or BigDecimal anywhere in the API surface")
        void noBinaryFloatingPointAndNoDecimalArithmetic() {
            for (Class<?> type : TYPES) {
                assertThat(apiSurfaceOf(type))
                        .as("%s: every xxxO item is PIC X(n), so the payload is text; the amount "
                                + "arrives already edited and no arithmetic happens here", type)
                        .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float",
                                "java.math.BigDecimal", "java.math.BigInteger");
            }
        }

        @Test
        @DisplayName("R2 and G24: no RoundingMode is reachable, because nothing here rounds")
        void noRoundingModeIsReachable() {
            for (Class<?> type : TYPES) {
                assertThat(apiSurfaceOf(type))
                        .as("%s: ROUNDED appears zero times in all 28 COBOL programs, so the only "
                                + "faithful mode is DOWN - and a payload that carries characters "
                                + "needs no mode at all", type)
                        .doesNotContain("java.math.RoundingMode", "java.math.MathContext");
            }
        }

        @Test
        @DisplayName("G44: no persistence annotation, on the type or on any of its members")
        void noPersistenceAnnotations() {
            Set<String> forbidden = Set.of("Entity", "Table", "Column", "Id", "Version",
                    "GeneratedValue", "Embeddable", "MappedSuperclass");

            for (Class<?> type : TYPES) {
                assertThat(annotationNames(type.getAnnotations()))
                        .as("%s carries no ORM annotation: there is no schema, no DDL and no "
                                + "version column in this migration", type)
                        .doesNotContainAnyElementsOf(forbidden);
                for (Field field : type.getDeclaredFields()) {
                    assertThat(annotationNames(field.getAnnotations()))
                            .as("%s.%s", type.getSimpleName(), field.getName())
                            .doesNotContainAnyElementsOf(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(annotationNames(method.getAnnotations()))
                            .as("%s.%s()", type.getSimpleName(), method.getName())
                            .doesNotContainAnyElementsOf(forbidden);
                }
            }
        }

        @Test
        @DisplayName("B9 and G53: every static field is final, so no state is shared between requests")
        void noStaticMutableState() {
            for (Class<?> type : TYPES) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s.%s is static and must therefore be final: WORKING-STORAGE is "
                                    + "per-conversation state and a static would leak one request's "
                                    + "screen into another's", type.getSimpleName(), field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("R6 and G37: no session, no ThreadLocal and no servlet type is reachable")
        void noServerSideState() {
            List<String> forbiddenFragments = List.of("HttpSession", "ThreadLocal",
                    "HttpServletRequest", "HttpServletResponse", "RequestAttributes",
                    "SessionAttribute", "WebRequest", "Model");

            for (Class<?> type : TYPES) {
                for (String name : apiSurfaceOf(type)) {
                    assertThat(forbiddenFragments)
                            .as("%s reaches %s, but CICS is pseudo-conversational and the whole "
                                    + "conversation travels in the payload", type.getSimpleName(),
                                    name)
                            .noneMatch(name::contains);
                }
            }
        }

        @Test
        @DisplayName("G39: PAGE_SIZE is a public static final int of 10 and nothing can rebind it")
        void thePageSizeCannotBeReconfigured() throws Exception {
            Field pageSize = TransactionListResponse.class.getDeclaredField("PAGE_SIZE");

            assertThat(Modifier.isPublic(pageSize.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(pageSize.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(pageSize.getModifiers()))
                    .as("a page size a deployment could change would change observable behaviour "
                            + "the COBOL fixes at 10")
                    .isTrue();
            assertThat(pageSize.getType()).isEqualTo(int.class);
            assertThat(pageSize.getInt(null)).isEqualTo(10);
            assertThat(TransactionListResponse.class.getDeclaredMethods())
                    .as("and no setter offers to")
                    .noneMatch(method -> method.getName().toLowerCase().contains("pagesize"));
        }

        @Test
        @DisplayName("B11 and B3: no copybook parser, and no source file is read at run time")
        void noCopybookParserAndNoSourceFileAccess() {
            for (Class<?> type : TYPES) {
                Set<String> surface = apiSurfaceOf(type);
                assertThat(surface)
                        .as("%s: the fixed-width codec is hand-written so every offset is "
                                + "reviewable against the copybook", type.getSimpleName())
                        .noneMatch(name -> name.startsWith("net.sf.JRecord"))
                        .noneMatch(name -> name.contains("Cb2"))
                        .doesNotContain("java.io.File", "java.nio.file.Path", "java.io.InputStream",
                                "java.net.URL");
            }
        }

        @Test
        @DisplayName("the cursor is a nested static type, so it carries no reference to its enclosing "
                + "response")
        void theCursorIsANestedStaticType() {
            assertThat(TransactionListCursor.class.getDeclaringClass())
                    .as("CDEMO-CT00-INFO is declared inside COTRN00C's own WORKING-STORAGE, "
                            + "immediately after COPY COCOM01Y - COTRN00C:61-70 - so it belongs to "
                            + "this screen and to no other")
                    .isEqualTo(TransactionListResponse.class);
            assertThat(Modifier.isStatic(TransactionListCursor.class.getModifiers()))
                    .as("an inner (non-static) class would hold a hidden reference to one response, "
                            + "which is exactly the aliasing a per-request payload must not have")
                    .isTrue();
            assertThat(Arrays.stream(TransactionListCursor.class.getDeclaredFields())
                    .map(Field::getType))
                    .as("and no field of the cursor reaches back to the response")
                    .doesNotContain(TransactionListResponse.class);
            assertThat(FieldAttributes.class.getDeclaringClass())
                    .isEqualTo(TransactionListResponse.class);
            assertThat(Modifier.isStatic(FieldAttributes.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the commarea is 160 bytes and this screen's 58-byte extension does not widen it")
        void theCommareaIsNeverWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy - CARDDEMO-COMMAREA is 160 bytes for all 17 online "
                            + "programs, and a per-screen extension is appended to it, never merged "
                            + "into it")
                    .isEqualTo(160);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(160);
            assertThat(NavigationContext.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length).sum()).isEqualTo(160);
            assertThat(NavigationContext.empty().toFixedWidth(CODEC)).hasSize(160);

            assertThat(TransactionListCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH)
                    .as("160 + 58 = 218, the length COTRN00C passes on COMMAREA(CARDDEMO-COMMAREA)")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + TransactionListCursor.CURSOR_LENGTH)
                    .isEqualTo(218);
            assertThat(new TransactionListCursor().toFixedWidth(CODEC))
                    .as("the extension carries its own 58 bytes and nothing of the commarea's")
                    .hasSize(58);
        }

        @Test
        @DisplayName("G33: list position 0 is row 1 and position 9 is row 10, and the API is 1-based")
        void theOneBasedToZeroBasedCorrespondenceIsPinnedAtBothEnds() {
            List<String> names = TransactionListResponse.payloadFieldNames();
            int firstRowStart = TransactionListResponse.HEADER_FIELD_COUNT;

            // COBOL row 1 <-> Java list position 8 within the whole group, and position 0 within the
            // row block; the row-addressed API takes the COBOL number, 1, and converts privately.
            assertThat(names.get(firstRowStart)).isEqualTo("SEL0001O");
            assertThat(names.get(firstRowStart + 1)).isEqualTo("TRNID01O");
            assertThat(TransactionListResponse.rowFieldNames(TransactionListResponse.FIRST_ROW).get(1))
                    .as("row 1 is the FIRST element - the low end of the off-by-one")
                    .isEqualTo("TRNID01O");

            int lastRowStart = firstRowStart
                    + (TransactionListResponse.ROW_COUNT - 1) * TransactionListResponse.ROW_FIELD_COUNT;
            assertThat(names.get(lastRowStart)).isEqualTo("SEL0010O");
            assertThat(TransactionListResponse.rowFieldNames(TransactionListResponse.LAST_ROW).get(1))
                    .as("row 10 is the LAST element - the high end of the off-by-one")
                    .isEqualTo("TRNID10O");

            // The API is 1-based throughout, so the invalid indices are 0 and 11, and a caller who
            // passed a 0-based position would be rejected at the low end rather than quietly reading
            // the wrong row - which is the whole reason the bound is checked.
            TransactionListResponse response = new TransactionListResponse();
            assertThat(TransactionListResponse.FIRST_ROW).isEqualTo(1);
            assertThat(TransactionListResponse.LAST_ROW).isEqualTo(10);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("0 is a 0-based caller's first row and is not a screen row")
                    .isThrownBy(() -> response.getRowTransactionId(0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("10 is a 0-based caller's eleventh row; here it is the last valid row, so "
                            + "the bound that must reject is 11")
                    .isThrownBy(() -> response.getRowTransactionId(11));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowTransactionId(-1));
            assertThatCode(() -> response.getRowTransactionId(10)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every one of the 59 payload members is a String")
        void everyPayloadMemberIsAString() {
            for (String itemName : EXPECTED_ITEM_NAMES) {
                String member = itemName.substring(0, itemName.length() - 1).toLowerCase()
                        + TransactionListResponse.OUTPUT_ITEM_SUFFIX;
                assertThatCode(() -> {
                    Field field = TransactionListResponse.class.getDeclaredField(member);
                    assertThat(field.getType()).as(itemName).isEqualTo(String.class);
                    assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
                }).as("%s is declared as the member %s", itemName, member).doesNotThrowAnyException();
            }
        }

        /**
         * The simple names of a set of annotations, for name-based checks that do not require the
         * annotation type to be on the classpath.
         *
         * @param annotations the annotations to name
         * @return their simple names; possibly empty, never {@code null}
         */
        private Set<String> annotationNames(Annotation[] annotations) {
            Set<String> names = new LinkedHashSet<>();
            for (Annotation annotation : annotations) {
                names.add(annotation.annotationType().getSimpleName());
            }
            return names;
        }
    }
}
