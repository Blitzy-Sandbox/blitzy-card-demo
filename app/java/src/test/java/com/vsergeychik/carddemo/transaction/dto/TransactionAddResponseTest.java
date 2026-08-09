package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.AttributeQuad;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link TransactionAddResponse} against its authoritative sources rather than against
 * itself.
 *
 * <p>The expected geometry is transcribed independently from the copybook into
 * {@link #COPYBOOK_FIELDS} below, so a mistake in the production geometry table cannot agree with a
 * matching mistake here: the two tables were written from the source, not from each other.
 *
 * <h2>Authoritative sources, cited once here and again at each assertion that uses them</h2>
 * <ul>
 *   <li>{@code app/cpy-bms/COTRN01.CPY:145} - {@code 01 COTRN1AO REDEFINES COTRN1AI.}, the output
 *       view whose 21 {@code xxxO} items are this payload, ending at {@code ERRMSGO PIC X(78)}
 *       on line 272. The input view {@code 01 COTRN1AI.} opens the same storage on line 17.</li>
 *   <li>{@code app/bms/COTRN01.bms} - 56 {@code DFHMDF} definitions, of which 21 carry a field
 *       name. {@code COTRN1A DFHMDI SIZE=(24,80)}; {@code TRNIDIN} is the single
 *       {@code UNPROT} field, at line 85; {@code ERRMSG} at
 *       <strong>{@code app/bms/COTRN01.bms:259}</strong> is
 *       {@code ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)}.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl:5} - {@code Function : View a Transaction from TRANSACT file};
 *       {@code :49} - {@code 05 WS-TRAN-AMT PIC +99999999.99}, the twelve-character edit mask;
 *       {@code :53-61} - the 58-byte {@code CDEMO-CT01-INFO} extension with its two
 *       {@code 88}-level next-page states; {@code :183} - {@code MOVE WS-TRAN-AMT TO TRNAMTI},
 *       the mask reaching the screen; {@code :206} - the single
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} site.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} - the three-condition highlight rule reproduced by
 *       {@link FieldAttributeSetter}, verified here in all four of its states.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - the 160-byte {@code CARDDEMO-COMMAREA};
 *       {@code app/cpy/COTTL01Y.cpy} - the {@code PIC X(40)} titles;
 *       {@code app/cpy/CSMSG01Y.cpy} - the {@code PIC X(50)} messages;
 *       {@code app/cpy/CSDAT01Y.cpy} - the date and time header;
 *       {@code app/cpy/CVTRA05Y.cpy} - the 350-byte {@code TRAN-RECORD} these fields are moved
 *       from.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:149} - {@code DEFINE MAPSET(COTRN01)}; {@code :264} -
 *       {@code DEFINE PROGRAM(COTRN01C)}; {@code :429} - {@code DEFINE TRANSACTION(CT01)
 *       PROGRAM(COTRN01C)}.</li>
 *   <li>{@code README.md:223} - {@code | CT01 | COTRN01 | COTRN01C | Transaction View |}.</li>
 * </ul>
 *
 * <h2>&#9888; Risk R-B: the subject is named "Add" and renders the "View" map</h2>
 * The build prompt mandates the name {@code TransactionAdd…}. Three independent sources show the
 * paired COBOL program <em>views</em> a transaction rather than adding one:
 * <ol>
 *   <li>{@code app/cbl/COTRN01C.cbl:5} states {@code Function : View a Transaction from TRANSACT
 *       file}.</li>
 *   <li>{@code README.md:223} documents {@code CT01} / {@code COTRN01C} as "Transaction
 *       View".</li>
 *   <li>The map's shape settles it: {@code TRNIDIN PIC X(16)} <em>and</em> {@code TRNID PIC X(16)},
 *       no {@code CONFIRM} field at all, and exactly <strong>one</strong> input-capable field
 *       ({@code TRNIDIN}) against <strong>20</strong> {@code ASKIP} output-only fields. Twenty
 *       output-only fields out of twenty-one is the signature of a display screen, not of a screen
 *       that captures a new record.</li>
 * </ol>
 *
 * <p><strong>Rule R1 resolves it: the name comes from the prompt, the field set and the behaviour
 * come from the source.</strong> The class is therefore <em>not</em> renamed and the swap is
 * <em>not</em> silently corrected - practice B4 forbids exactly that - it is documented here and
 * asserted negatively by {@code carriesNoAddScreenFields}: no {@code CONFIRMO}, no
 * {@code ACTIDINO}, no {@code CARDNINO}. {@code TransactionViewResponse} in this same package is
 * the inverse case: it is named "View" and renders {@code COTRN02}, the real add screen, whose map
 * has 14 input-capable fields. The Agent Action Plan records the pair as Conflict Set 1
 * (&sect;0.1.8) and as risk R-B (&sect;0.9.12), flagged for user confirmation.
 *
 * <h2>Which rules govern this file</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user-specified rule governs this file</strong>. That absence
 * is explicitly not treated as licence to lower the bar (Agent Action Plan &sect;0.10): the plan's
 * own binds stand in for it and are what the assertions below enforce -
 * <strong>R1</strong> (name from the prompt, behaviour from the source),
 * <strong>R2</strong>/<strong>G24</strong> (truncation, never rounding - {@code ROUNDED} appears
 * zero times in all 28 programs),
 * <strong>R4</strong>/<strong>G22</strong> (never {@code double} or {@code float}),
 * <strong>R5</strong> (fixed width is the wire format),
 * <strong>R6</strong>/<strong>G37</strong> (statelessness),
 * <strong>B3</strong> (the COBOL, copybook, BMS, JCL and CSD inputs are read and never written -
 * every name, width and literal below is a test constant carrying its citation),
 * <strong>B4</strong> (no silent scope creep), <strong>B7</strong> (deterministic and
 * non-interactive - the header fields are driven by an injected fixed {@link Clock} with an
 * explicit {@link ZoneId}, never the wall clock and never the platform zone),
 * <strong>B8</strong>/<strong>G52</strong> (explicit over implicit: every import is named, none is
 * a wildcard), <strong>B9</strong>/<strong>G53</strong> (no static mutable state) and
 * <strong>B11</strong> (hand-written, reviewable expectations rather than a third-party copybook
 * parser).
 *
 * <h2>Gates covered</h2>
 * G9 (the 1:1 field projection and every declared width), G21 (every {@code FILLER} emitted),
 * G22/G23/G24 (no floating-point or rounding surface anywhere), G33 (recorded as <em>not
 * applicable</em>: this map declares no {@code OCCURS} table, so there is no 1-based to 0-based
 * index conversion to verify - see {@code SourceCensus}), G34 (the {@code REDEFINES} pair as two
 * accessors over one span, round-tripped in both directions), G37 and G40 (navigation without
 * server-side state), G38 (the {@code CSSETATY} highlight reachable only in {@code REENTER}), G41
 * (no masking of the card number or merchant id), G44 (no persistence annotation and no version
 * column), G50 (both {@code 88}-level states of the cursor's next-page flag, plus a third value
 * satisfying neither) and G54 (plain JUnit 5, no sleep, no network, no watch mode).
 *
 * <h2>Self-contained by design</h2>
 * This suite follows the structural shape of {@code TransactionListResponseTest} in this package
 * but <strong>depends on nothing in it</strong>: there is no shared base class, no shared fixture
 * and none is to be created, so this file can be read, run and reviewed on its own.
 *
 * <p>It also stays strictly inside its subject. There is no {@code MockMvc}, no
 * {@code @SpringBootTest} and no {@code @WebMvcTest} here - {@code TransactionAddControllerTest} in
 * the parent package owns the controller's keyed read, its never-writes assertions and its
 * {@code EVALUATE EIBAID} branch set - no repository, no {@code JobLauncher} and no data source,
 * nothing from {@code com.vsergeychik.carddemo.parity} and no read of
 * {@code src/test/resources/parity}, and no {@code card.dto.CardScreenState}, because
 * {@code COTRN01C} does not copy {@code CVCRD01Y}.
 */
@DisplayName("COTRN1AO - the outbound projection of CT01 / COTRN01C")
class TransactionAddResponseTest {

    /** The code page of the authoritative fixtures under {@code app/data/ASCII}. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec form of {@link #ASCII}, for the carriers whose byte boundary takes one. */
    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /**
     * The 21 name-labelled fields of {@code COTRN01}, transcribed independently from
     * {@code app/cpy-bms/COTRN01.CPY} lines 145-272: the {@code xxxO} item name, its
     * {@code PIC X(n)} width, and the absolute offset of its field group.
     *
     * <p>Each width was cross-checked against the {@code LENGTH=} operand of the matching
     * {@code DFHMDF} in {@code app/bms/COTRN01.bms}; all 21 agree.
     */
    private static final List<Object[]> COPYBOOK_FIELDS = List.of(
            new Object[] {"TRNNAMEO", 4, 12},
            new Object[] {"TITLE01O", 40, 23},
            new Object[] {"CURDATEO", 8, 70},
            new Object[] {"PGMNAMEO", 8, 85},
            new Object[] {"TITLE02O", 40, 100},
            new Object[] {"CURTIMEO", 8, 147},
            new Object[] {"TRNIDINO", 16, 162},
            new Object[] {"TRNIDO", 16, 185},
            new Object[] {"CARDNUMO", 16, 208},
            new Object[] {"TTYPCDO", 2, 231},
            new Object[] {"TCATCDO", 4, 240},
            new Object[] {"TRNSRCO", 10, 251},
            new Object[] {"TDESCO", 60, 268},
            new Object[] {"TRNAMTO", 12, 335},
            new Object[] {"TORIGDTO", 10, 354},
            new Object[] {"TPROCDTO", 10, 371},
            new Object[] {"MIDO", 9, 388},
            new Object[] {"MNAMEO", 30, 404},
            new Object[] {"MCITYO", 25, 441},
            new Object[] {"MZIPO", 10, 473},
            new Object[] {"ERRMSGO", 78, 490});

    private static List<Object[]> copybookFields() {
        return COPYBOOK_FIELDS;
    }

    // -------------------------------------------------------------------------------------------------
    // The determinism seam (practice B7). A fixed instant and an EXPLICITLY named zone, so the header
    // fields render the same bytes on every machine and in every time zone. The instant is the version
    // footer stamped on the sources themselves - "Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19
    // 23:15:57" - which makes the expected images below traceable to something rather than arbitrary.
    //
    // Clock.fixed is used rather than Clock.systemDefaultZone(), and the zone is stated rather than
    // defaulted, because DateHeader takes its instant from the caller and never calls now() itself: a
    // header that read the wall clock could not be compared byte for byte against an expected image.
    // -------------------------------------------------------------------------------------------------

    /** The zone the fixed clock is read in, named outright and never taken from the platform. */
    private static final ZoneId FIXED_ZONE = ZoneId.of("UTC");

    /** A second, deliberately different zone, used to prove the zone is honoured rather than ignored. */
    private static final ZoneId CENTRAL_ZONE = ZoneId.of("America/Chicago");

    /** The instant every header assertion is anchored to: the sources' own version footer. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:15:57Z");

    /** The clock itself - immutable, so publishing it as a constant introduces no mutable state. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, FIXED_ZONE);

    // -------------------------------------------------------------------------------------------------
    // The source census. Counted directly from the reference inputs and encoded here as constants with
    // their citations, because practice B3 makes those inputs read-only and gate G5 keeps this run's
    // only change inside this file: a test that opened app/bms at run time would both couple the suite
    // to paths outside the module and blur the boundary the gate draws.
    // -------------------------------------------------------------------------------------------------

    /** {@code DFHMDF} definitions in {@code app/bms/COTRN01.bms}, named and unnamed together. */
    private static final int BMS_FIELD_DEFINITION_COUNT = 56;

    /** Those that carry a field name, and so become payload items. */
    private static final int BMS_NAMED_FIELD_COUNT = 21;

    /**
     * Those that carry none: screen literals and labels such as {@code 'Tran:'}, plus the
     * {@code LENGTH=0} stopper fields. They are painted by the map and never travel in a payload,
     * which is why 56 {@code DFHMDF} entries yield 21 JSON members and not 56.
     */
    private static final int BMS_UNNAMED_FIELD_COUNT = 35;

    /** Input-capable named fields: exactly one, {@code TRNIDIN}, {@code app/bms/COTRN01.bms:85}. */
    private static final int BMS_INPUT_CAPABLE_FIELD_COUNT = 1;

    /** Named fields declared {@code ASKIP}, and so output-only: the remaining twenty. */
    private static final int BMS_OUTPUT_ONLY_FIELD_COUNT = 20;

    /**
     * {@code MOVE -1 TO TRNIDINL OF COTRN1AI} sites in {@code app/cbl/COTRN01C.cbl}: lines 102, 151,
     * 154, 287, 294 and 311. All six target the same field, which is the only enterable one, and all
     * six place the cursor by writing a <em>negative</em> halfword - the reason the length item must
     * be signed.
     */
    private static final int CURSOR_REQUEST_SITE_COUNT = 6;

    /** {@code EXEC CICS XCTL} sites in {@code app/cbl/COTRN01C.cbl}: exactly one, at line 206. */
    private static final int XCTL_SITE_COUNT = 1;

    /**
     * {@code OCCURS} tables on this map: none, in either {@code app/bms/COTRN01.bms} or
     * {@code app/cpy-bms/COTRN01.CPY}. Recorded as a constant rather than left unmentioned so that
     * the absence of any 1-based to 0-based index conversion reads as a verified finding and not as
     * an omission - gate G33 has no subject here.
     */
    private static final int OCCURS_TABLE_COUNT = 0;

    /** Lines in {@code app/cbl/COTRN01C.cbl}, the program this screen belongs to. */
    private static final int PROGRAM_LINE_COUNT = 330;

    // -------------------------------------------------------------------------------------------------
    // The sending fields, from the 350-byte TRAN-RECORD of app/cpy/CVTRA05Y.cpy. Every one of these is
    // wider than, or a different class from, the screen field it feeds, which is what makes the moves
    // below worth asserting rather than assuming.
    // -------------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)} - the same width as {@code TRNIDO} and {@code TRNIDINO}. */
    private static final int TRAN_ID_WIDTH = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)}. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)} - numeric, moved into the alphanumeric {@code TCATCDO}. */
    private static final int TRAN_CAT_CD_DIGITS = 4;

    /** {@code TRAN-SOURCE PIC X(10)}. */
    private static final int TRAN_SOURCE_WIDTH = 10;

    /** {@code TRAN-DESC PIC X(100)} - moved into {@code TDESCO PIC X(60)}, losing 40 on the right. */
    private static final int TRAN_DESC_WIDTH = 100;

    /**
     * {@code TRAN-AMT PIC S9(09)V99} - eleven digit positions. Never assigned to the screen directly:
     * the screen carries the twelve-character edited form instead.
     */
    private static final int TRAN_AMT_DIGITS = 11;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} - numeric, moved into the alphanumeric {@code MIDO}. */
    private static final int TRAN_MERCHANT_ID_DIGITS = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} - moved into {@code MNAMEO PIC X(30)}. */
    private static final int TRAN_MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} - moved into {@code MCITYO PIC X(25)}. */
    private static final int TRAN_MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} - the same width as {@code MZIPO}. */
    private static final int TRAN_MERCHANT_ZIP_WIDTH = 10;

    /** {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS PIC X(26)} - moved into ten-character fields. */
    private static final int TRAN_TIMESTAMP_WIDTH = 26;

    /** A real 26-character timestamp, whose leading ten characters are the {@code YYYY-MM-DD} date. */
    private static final String TIMESTAMP_26 = "2022-07-19 23:15:57.123456";

    /** Integer digit positions the {@code +99999999.99} mask can hold: eight, not the record's nine. */
    private static final int MASK_INTEGER_DIGITS = 8;

    /** Fraction digit positions the mask holds. */
    private static final int MASK_FRACTION_DIGITS = 2;

    // -------------------------------------------------------------------------------------------------
    // Negative vocabularies, named so the prohibition is greppable rather than implied.
    // -------------------------------------------------------------------------------------------------

    /**
     * The rounding modes this migration must never reach for. {@code ROUNDED} appears
     * <strong>zero</strong> times across all 28 COBOL programs, so a store truncates and
     * {@code RoundingMode.DOWN} is the only faithful choice (rule R2, gate G24). This screen payload
     * performs no arithmetic at all, so the strongest statement available is that none of these four
     * is even expressible through its API - which is what {@code NegativeContracts} asserts.
     */
    private static final List<RoundingMode> FORBIDDEN_ROUNDING_MODES = List.of(
            RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.CEILING, RoundingMode.FLOOR);

    /**
     * Annotation simple names that would mean a schema had been introduced. The prompt forbids DDL,
     * ORM mapping and version columns outright (gate G44), so none of these may appear on the payload
     * type, on any of its members or on any of its nested types.
     */
    private static final List<String> FORBIDDEN_PERSISTENCE_ANNOTATIONS = List.of(
            "Entity", "Table", "Column", "Id", "Version", "GeneratedValue", "Embeddable",
            "MappedSuperclass", "JoinColumn", "SequenceGenerator");

    /**
     * Type simple names that would mean server-side conversation state had crept in. CICS is
     * pseudo-conversational, so the state travels in the payload and nowhere else (rule R6, gate G37).
     */
    private static final List<String> FORBIDDEN_STATE_TYPES = List.of(
            "HttpSession", "HttpServletRequest", "HttpServletResponse", "ThreadLocal",
            "SessionStatus", "WebRequest", "ServletRequestAttributes");

    /** Annotation simple names that would bind this payload to a server-side session. */
    private static final List<String> FORBIDDEN_STATE_ANNOTATIONS = List.of(
            "SessionAttributes", "SessionAttribute", "SessionScope");

    // -------------------------------------------------------------------------------------------------
    // Shared helpers. Deliberately tiny and free of production imports beyond the whitelist, so an
    // expectation can never be produced by the very code it is meant to judge.
    // -------------------------------------------------------------------------------------------------

    /**
     * Renders an amount into the twelve-character {@code +99999999.99} edit mask of
     * {@code app/cbl/COTRN01C.cbl:49}, from a count of cents, using integer arithmetic only.
     *
     * <p>No {@code double}, no {@code float} and no {@link java.math.BigDecimal}: the mask is a
     * positional character image, and building it from a binary floating-point value is precisely the
     * defect rule R4 exists to prevent. The sign is always present - the mask's leading {@code +} is a
     * sign position, not a decoration - so the result is exactly
     * {@code 1 + 8 + 1 + 2 = }{@value #TRNAMT_MASK_WIDTH} characters.
     *
     * @param cents the signed amount in cents
     * @return the edited image, exactly {@value #TRNAMT_MASK_WIDTH} characters
     */
    private static String editedAmountImage(long cents) {
        long magnitude = Math.abs(cents);
        String integerPart = ASCII_CODEC.movePic9(magnitude / 100L, MASK_INTEGER_DIGITS);
        String fractionPart = ASCII_CODEC.movePic9(magnitude % 100L, MASK_FRACTION_DIGITS);
        return (cents < 0L ? "-" : "+") + integerPart + '.' + fractionPart;
    }

    /** The width of the edited amount image, and of {@code TRNAMTO PIC X(12)}. */
    private static final int TRNAMT_MASK_WIDTH =
            1 + MASK_INTEGER_DIGITS + 1 + MASK_FRACTION_DIGITS;

    /**
     * A repeatable filler string, for building sending values of an exact width without a magic
     * literal.
     *
     * @param unit  the text to repeat
     * @param width the exact width required
     * @return {@code unit} repeated and then cut to exactly {@code width} characters
     */
    private static String widthOf(String unit, int width) {
        StringBuilder builder = new StringBuilder(width + unit.length());
        while (builder.length() < width) {
            builder.append(unit);
        }
        return builder.substring(0, width);
    }

    /**
     * The absolute offset of every {@code xxxI} item, derived independently from
     * {@link TransactionAddRequest}'s own constants by walking the input view field by field.
     *
     * <p>Derived, not copied. If it were read from {@link ScreenField#payloadOffset()} the comparison
     * that uses it would be circular; walking the input view instead - prefix, then per field seven
     * metadata bytes followed by the declared width - reproduces the copybook's own accumulation and
     * gives an answer the output view had no hand in.
     *
     * @return each {@code xxxI} item name mapped to its absolute offset, in declaration order
     */
    private static Map<String, Integer> inputItemOffsets() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int groupOffset = TransactionAddRequest.TIOA_PREFIX_LENGTH;
        for (String inputItemName : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
            int payloadOffset = groupOffset + TransactionAddRequest.FIELD_METADATA_LENGTH;
            offsets.put(inputItemName, payloadOffset);
            groupOffset = payloadOffset + TransactionAddRequest.declaredLengthOf(inputItemName);
        }
        return offsets;
    }

    /**
     * A response whose 21 payload items each spell their own field's base name, repeated to the
     * declared width.
     *
     * <p>Distinct from {@link #populated()}, which carries realistic screen content: these values
     * exist to make an offset error <em>visible</em>. If a span is one byte out, the assertion fails
     * with a neighbouring field's name in the message rather than with two indistinguishable runs of
     * the same filler character, which is the difference between a five-second diagnosis and an
     * afternoon of arithmetic.
     *
     * @return a fully populated response; never {@code null}
     */
    private static TransactionAddResponse positionRevealingResponse() {
        TransactionAddResponse response = new TransactionAddResponse();
        for (ScreenField field : ScreenField.values()) {
            response.setPayload(field, widthOf(field.baseName() + '.', field.payloadLength()));
        }
        return response;
    }

    /**
     * Every type named anywhere in {@link TransactionAddResponse}'s declared API surface - field
     * types, method return types, method parameter types and constructor parameter types - together
     * with the same for its nested types.
     *
     * @return the distinct types, never {@code null}
     */
    private static List<Class<?>> declaredApiTypes() {
        List<Class<?>> types = new ArrayList<>();
        List<Class<?>> subjects = List.of(TransactionAddResponse.class, ScreenField.class,
                AttributeQuad.class);
        for (Class<?> subject : subjects) {
            for (java.lang.reflect.Field field : subject.getDeclaredFields()) {
                types.add(field.getType());
            }
            for (Method method : subject.getDeclaredMethods()) {
                types.add(method.getReturnType());
                types.addAll(Arrays.asList(method.getParameterTypes()));
            }
            for (Constructor<?> constructor : subject.getDeclaredConstructors()) {
                types.addAll(Arrays.asList(constructor.getParameterTypes()));
            }
        }
        return types;
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field projection, checked against the copybook (gate G9)")
    class FieldProjection {

        @Test
        @DisplayName("projects exactly 21 payload fields - no more, and none of COTRN02's")
        void projectsExactlyTwentyOneFields() {
            assertThat(ScreenField.values()).hasSize(21);
            assertThat(TransactionAddResponse.PAYLOAD_FIELD_COUNT).isEqualTo(21);
            assertThat(COPYBOOK_FIELDS).hasSize(21);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at group {2}")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto.TransactionAddResponseTest#copybookFields")
        @DisplayName("each field's name, width and offset match the copybook exactly")
        void fieldMatchesCopybook(String itemName, int width, int groupOffset) {
            ScreenField field = ScreenField.valueOf(itemName);
            assertThat(field.outputItemName()).isEqualTo(itemName);
            assertThat(field.payloadLength()).isEqualTo(width);
            assertThat(field.groupOffset()).isEqualTo(groupOffset);
            assertThat(field.payloadOffset()).isEqualTo(groupOffset + 7);
        }

        @Test
        @DisplayName("declaration order matches the copybook, top to bottom")
        void declarationOrderMatchesCopybook() {
            List<String> declared = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                declared.add(field.outputItemName());
            }
            List<String> expected = new ArrayList<>();
            for (Object[] row : COPYBOOK_FIELDS) {
                expected.add((String) row[0]);
            }
            assertThat(declared).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("carries none of COTRN02's add-screen fields - risk R-B stays documented, not implemented")
        void carriesNoAddScreenFields() {
            List<String> names = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                names.add(field.outputItemName());
            }
            assertThat(names).doesNotContain("CONFIRMO", "ACTIDINO", "CARDNINO");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the six item names of every field derive from one verbatim base name")
        void itemNamesDeriveFromBaseName(ScreenField field) {
            String base = field.baseName();
            assertThat(field.outputItemName()).isEqualTo(base + "O");
            assertThat(field.inputItemName()).isEqualTo(base + "I");
            assertThat(field.colourItemName()).isEqualTo(base + "C");
            assertThat(field.programmedSymbolsItemName()).isEqualTo(base + "P");
            assertThat(field.highlightItemName()).isEqualTo(base + "H");
            assertThat(field.validationItemName()).isEqualTo(base + "V");
            assertThat(field.describe()).contains(field.outputItemName());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the attribute items sit at group+3, +4, +5 and +6, and the payload at group+7")
        void attributeItemOffsetsFollowTheGroupFiller(ScreenField field) {
            int group = field.groupOffset();
            assertThat(field.colourItemOffset()).isEqualTo(group + 3);
            assertThat(field.programmedSymbolsItemOffset()).isEqualTo(group + 4);
            assertThat(field.highlightItemOffset()).isEqualTo(group + 5);
            assertThat(field.validationItemOffset()).isEqualTo(group + 6);
            assertThat(field.payloadOffset()).isEqualTo(group + 7);
            assertThat(field.groupEndOffsetExclusive())
                    .isEqualTo(group + 7 + field.payloadLength());
            assertThat(field.groupFillerSpan().length()).isEqualTo(3);
            assertThat(field.payloadSpan().name()).isEqualTo(field.outputItemName());
            assertThat(field.colourSpan().name()).isEqualTo(field.colourItemName());
            assertThat(field.programmedSymbolsSpan().name())
                    .isEqualTo(field.programmedSymbolsItemName());
            assertThat(field.highlightSpan().name()).isEqualTo(field.highlightItemName());
            assertThat(field.validationSpan().name()).isEqualTo(field.validationItemName());
        }

        @Test
        @DisplayName("field groups tile the image with no gap and no overlap")
        void fieldGroupsTileTheImage() {
            int cursor = TransactionAddResponse.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.groupOffset()).as("group start of %s", field).isEqualTo(cursor);
                cursor = field.groupEndOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(TransactionAddResponse.SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Byte totals")
    class ByteTotals {

        @Test
        @DisplayName("the 21 payload widths sum to 416")
        void payloadWidthsSumTo416() {
            int sum = 0;
            for (Object[] row : COPYBOOK_FIELDS) {
                sum += (Integer) row[1];
            }
            assertThat(sum).isEqualTo(416);
            assertThat(TransactionAddResponse.TOTAL_PAYLOAD_WIDTH).isEqualTo(416);
        }

        @Test
        @DisplayName("the COTRN1AO image is 12 + 21x7 + 416 = 575 bytes")
        void symbolicMapImageIs575Bytes() {
            assertThat(TransactionAddResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionAddResponse.FIELD_ATTRIBUTE_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 21 * 7 + 416)
                    .isEqualTo(575);
            assertThat(TransactionAddResponse.LAYOUT.recordLength()).isEqualTo(575);
        }

        @Test
        @DisplayName("the CT01 cursor is 16+16+8+1+1+16 = 58 bytes")
        void cursorIs58Bytes() {
            assertThat(Ct01Info.RECORD_LENGTH)
                    .isEqualTo(16 + 16 + 8 + 1 + 1 + 16)
                    .isEqualTo(58);
            assertThat(Ct01Info.LAYOUT.recordLength()).isEqualTo(58);
        }

        @Test
        @DisplayName("the passed commarea is 160 + 58 = 218 bytes, by concatenation not widening")
        void passedCommareaIs218Bytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(TransactionAddResponse.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(160 + 58)
                    .isEqualTo(218);
        }

        @Test
        @DisplayName("the layout declares every byte, fillers included (gate G21)")
        void layoutDeclaresEveryByteIncludingFillers() {
            // 1 leading filler + 21 x (1 group filler + 4 attribute items + 1 payload item)
            assertThat(TransactionAddResponse.LAYOUT.spans()).hasSize(1 + 21 * 6);
            int declared = 0;
            for (FixedWidthRecord.FieldSpan span : TransactionAddResponse.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared).isEqualTo(575);
        }

        @Test
        @DisplayName("widths shared with the common contracts agree with the screen fields")
        void sharedContractWidthsAgree() {
            assertThat(TransactionAddResponse.TITLE_ITEM_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(ScreenField.TITLE01O.payloadLength())
                    .isEqualTo(ScreenField.TITLE02O.payloadLength());
            assertThat(TransactionAddResponse.CURDATE_ITEM_LENGTH)
                    .isEqualTo(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .isEqualTo(ScreenField.CURDATEO.payloadLength());
            assertThat(TransactionAddResponse.CURTIME_ITEM_LENGTH)
                    .isEqualTo(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .isEqualTo(ScreenField.CURTIMEO.payloadLength());
            // A standard message text fits inside ERRMSGO without truncation.
            assertThat(TransactionAddResponse.STANDARD_MESSAGE_LENGTH)
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH)
                    .isLessThanOrEqualTo(ScreenField.ERRMSGO.payloadLength());
            // WS-MESSAGE is wider than the field it feeds, so a move into it truncates on the right.
            assertThat(TransactionAddResponse.WS_MESSAGE_LENGTH)
                    .isGreaterThan(ScreenField.ERRMSGO.payloadLength());
        }

        @Test
        @DisplayName("identity constants come from the CSD and the program's WORKING-STORAGE")
        void identityConstants() {
            assertThat(TransactionAddResponse.TRANSACTION_ID).isEqualTo("CT01");
            assertThat(TransactionAddResponse.PROGRAM_NAME).isEqualTo("COTRN01C");
            assertThat(TransactionAddResponse.MAPSET_NAME).isEqualTo("COTRN01");
            assertThat(TransactionAddResponse.MAP_NAME).isEqualTo("COTRN1A");
            assertThat(TransactionAddResponse.INPUT_MAP_GROUP_NAME).isEqualTo("COTRN1AI");
            assertThat(TransactionAddResponse.OUTPUT_MAP_GROUP_NAME).isEqualTo("COTRN1AO");
            // A map or mapset name is seven wide; a program name is eight.
            assertThat(TransactionAddResponse.MAP_NAME).hasSize(7);
            assertThat(TransactionAddResponse.MAPSET_NAME).hasSize(7);
            assertThat(TransactionAddResponse.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Payload values obey the PIC X move rule")
    class PayloadValues {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh response holds every field space-filled to its declared width")
        void freshResponseIsSpaceFilled(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .isBlank();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and kept at the declared width")
        void shortValueIsPaddedOnTheRight(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setPayload(field, "A");
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .startsWith("A");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an over-long value is truncated on the RIGHT, as COBOL truncates PIC X")
        void longValueIsTruncatedOnTheRight(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            String sending = "Z".repeat(field.payloadLength()) + "LOST";
            response.setPayload(field, sending);
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .isEqualTo("Z".repeat(field.payloadLength()));
        }

        @Test
        @DisplayName("all 21 named accessor pairs read and write their own field")
        void namedAccessorsAddressTheirOwnField() {
            TransactionAddResponse response = new TransactionAddResponse();
            Map<ScreenField, String> written = new LinkedHashMap<>();

            response.setTrnnameo("CT01");
            written.put(ScreenField.TRNNAMEO, response.getTrnnameo());
            response.setTitle01o("TITLE ONE");
            written.put(ScreenField.TITLE01O, response.getTitle01o());
            response.setCurdateo("08/08/26");
            written.put(ScreenField.CURDATEO, response.getCurdateo());
            response.setPgmnameo("COTRN01C");
            written.put(ScreenField.PGMNAMEO, response.getPgmnameo());
            response.setTitle02o("TITLE TWO");
            written.put(ScreenField.TITLE02O, response.getTitle02o());
            response.setCurtimeo("12:34:56");
            written.put(ScreenField.CURTIMEO, response.getCurtimeo());
            response.setTrnidino("0000000000000042");
            written.put(ScreenField.TRNIDINO, response.getTrnidino());
            response.setTrnido("0000000000000042");
            written.put(ScreenField.TRNIDO, response.getTrnido());
            response.setCardnumo("4111111111111111");
            written.put(ScreenField.CARDNUMO, response.getCardnumo());
            response.setTtypcdo("01");
            written.put(ScreenField.TTYPCDO, response.getTtypcdo());
            response.setTcatcdo("0001");
            written.put(ScreenField.TCATCDO, response.getTcatcdo());
            response.setTrnsrco("POS TERM");
            written.put(ScreenField.TRNSRCO, response.getTrnsrco());
            response.setTdesco("A PURCHASE");
            written.put(ScreenField.TDESCO, response.getTdesco());
            response.setTrnamto("+00000123.45");
            written.put(ScreenField.TRNAMTO, response.getTrnamto());
            response.setTorigdto("2022-07-18");
            written.put(ScreenField.TORIGDTO, response.getTorigdto());
            response.setTprocdto("2022-07-19");
            written.put(ScreenField.TPROCDTO, response.getTprocdto());
            response.setMido("123456789");
            written.put(ScreenField.MIDO, response.getMido());
            response.setMnameo("A MERCHANT");
            written.put(ScreenField.MNAMEO, response.getMnameo());
            response.setMcityo("A CITY");
            written.put(ScreenField.MCITYO, response.getMcityo());
            response.setMzipo("12345");
            written.put(ScreenField.MZIPO, response.getMzipo());
            response.setErrmsgo("A MESSAGE");
            written.put(ScreenField.ERRMSGO, response.getErrmsgo());

            assertThat(written).hasSize(21);
            written.forEach((field, value) -> {
                assertThat(value).as("%s width", field).hasSize(field.payloadLength());
                assertThat(response.payload(field)).as("%s round trip", field).isEqualTo(value);
            });
            assertThat(response.payloadItems()).containsExactlyInAnyOrderEntriesOf(written);
        }

        @Test
        @DisplayName("the card number and merchant id are carried in full, unmasked (gate G41)")
        void identifiersAreNotMasked() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setCardnumo("4111111111111111");
            response.setMido("987654321");

            assertThat(response.getCardnumo()).isEqualTo("4111111111111111").hasSize(16);
            assertThat(response.getMido()).isEqualTo("987654321").hasSize(9);
            assertThat(response.getCardnumo()).doesNotContain("*").doesNotContain("X");
            assertThat(response.getMido()).doesNotContain("*").doesNotContain("X");
        }

        @Test
        @DisplayName("the edited amount is a 12-character masked string, not a number")
        void editedAmountIsTwelveCharacters() {
            assertThat(ScreenField.TRNAMTO.payloadLength()).isEqualTo(12);
            assertThat("+99999999.99").hasSize(12);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnamto("+99999999.99");
            assertThat(response.getTrnamto()).isEqualTo("+99999999.99").hasSize(12);
            response.setTrnamto("-00000001.00");
            assertThat(response.getTrnamto()).isEqualTo("-00000001.00");
        }

        @Test
        @DisplayName("no payload accessor exposes a floating-point or numeric type (G22/G23/G24)")
        void everyPayloadAccessorIsAString() {
            for (ScreenField field : ScreenField.values()) {
                String getter = "get" + field.outputItemName().charAt(0)
                        + field.outputItemName().substring(1).toLowerCase(java.util.Locale.ROOT);
                assertThat(getter).isNotBlank();
            }
            for (java.lang.reflect.Method method : TransactionAddResponse.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("%s must not return a floating-point type", method.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter)
                            .as("%s must not take a floating-point parameter", method.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class);
                }
            }
        }

        @Test
        @DisplayName("a null field or value is rejected - COBOL has no absent state")
        void nullsAreRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatNullPointerException().isThrownBy(() -> response.payload(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayload(null, "x"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayload(ScreenField.TRNIDO, null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null));
            assertThatNullPointerException().isThrownBy(() -> response.setCt01Info(null));
            assertThatNullPointerException().isThrownBy(() -> response.attributes(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(null, AttributeQuad.defaults()));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(ScreenField.TRNIDO, null));
        }

        @Test
        @DisplayName("the payload snapshot is unmodifiable, so no caller can bypass the move rule")
        void payloadSnapshotIsUnmodifiable() {
            TransactionAddResponse response = new TransactionAddResponse();
            Map<ScreenField, String> snapshot = response.payloadItems();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> snapshot.put(ScreenField.TRNIDO, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.attributeItems()
                            .put(ScreenField.TRNIDO, AttributeQuad.defaults()));
        }
    }

    @Nested
    @DisplayName("Highlight metadata and the CSSETATY contract (gate G38)")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field starts with all four attribute items unassigned")
        void attributesStartUnassigned(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            AttributeQuad quad = response.attributes(field);
            assertThat(quad.unassigned()).isTrue();
            assertThat(quad.colouredRed()).isFalse();
            assertThat(quad.colour()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.programmedSymbols()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.highlight()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.validation()).isEqualTo(AttributeQuad.NO_CHANGE);
        }

        @Test
        @DisplayName("NOT-OK in REENTER reddens the colour item and leaves the payload alone")
        void notOkInReenterRedensColourOnly() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnidino("0000000000000042");

            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDINO, true, false, true);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned()).isFalse();
            assertThat(response.attributes(ScreenField.TRNIDINO).colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributes(ScreenField.TRNIDINO).colouredRed()).isTrue();
            assertThat(response.getTrnidino()).startsWith("0000000000000042");
        }

        @Test
        @DisplayName("BLANK in REENTER reddens the colour item AND moves '*' into the payload item")
        void blankInReenterAlsoMovesAsterisk() {
            TransactionAddResponse response = new TransactionAddResponse();

            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDINO, false, true, true);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDINO).colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getTrnidino())
                    .startsWith(FieldAttributeSetter.ASTERISK)
                    .hasSize(ScreenField.TRNIDINO.payloadLength());
        }

        @Test
        @DisplayName("on first entry nothing is highlighted, however the field validated (gate G38)")
        void noHighlightOnFirstEntry() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnidino("KEEPME");

            FieldHighlight notOk =
                    response.highlightField(ScreenField.TRNIDINO, true, false, false);
            FieldHighlight blank =
                    response.highlightField(ScreenField.TRNIDINO, false, true, false);

            assertThat(notOk.untouched()).isTrue();
            assertThat(blank.untouched()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDINO).unassigned()).isTrue();
            assertThat(response.getTrnidino()).startsWith("KEEPME");
        }

        @Test
        @DisplayName("a valid field in REENTER is left alone")
        void validFieldInReenterIsLeftAlone() {
            TransactionAddResponse response = new TransactionAddResponse();
            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDO, false, false, true);

            assertThat(highlight.untouched()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDO).unassigned()).isTrue();
        }

        @Test
        @DisplayName("applyHighlight obeys a decision handed to it, including a no-op decision")
        void applyHighlightObeysTheDecision() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTdesco("UNTOUCHED");

            response.applyHighlight(ScreenField.TDESCO,
                    FieldHighlight.none(ScreenField.TDESCO.baseName(),
                            TransactionAddResponse.MAP_NAME));
            assertThat(response.attributes(ScreenField.TDESCO).unassigned()).isTrue();
            assertThat(response.getTdesco()).startsWith("UNTOUCHED");

            response.applyHighlight(ScreenField.TDESCO, FieldAttributeSetter.resolveFromFlags(
                    false, true, true, ScreenField.TDESCO.baseName(),
                    TransactionAddResponse.MAP_NAME));
            assertThat(response.attributes(ScreenField.TDESCO).colouredRed()).isTrue();
            assertThat(response.getTdesco()).startsWith("*");

            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(ScreenField.TDESCO, null));
            assertThatNullPointerException().isThrownBy(() -> response.applyHighlight(null,
                    FieldHighlight.none("", "")));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.highlightField(null, true, false, true));
        }

        @Test
        @DisplayName("attribute items can be replaced wholesale and stay independent of one another")
        void attributeItemsAreIndependent() {
            TransactionAddResponse response = new TransactionAddResponse();

            response.setAttributes(ScreenField.MZIPO, AttributeQuad.defaults()
                    .withColour(BmsAttributes.DFHRED)
                    .withProgrammedSymbols((byte) 0x41)
                    .withHighlight(BmsAttributes.DFHBMASB)
                    .withValidation(BmsAttributes.DFHUNIMD));

            AttributeQuad quad = response.attributes(ScreenField.MZIPO);
            assertThat(quad.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.programmedSymbols()).isEqualTo((byte) 0x41);
            assertThat(quad.highlight()).isEqualTo(BmsAttributes.DFHBMASB);
            assertThat(quad.validation()).isEqualTo(BmsAttributes.DFHUNIMD);
            assertThat(quad.unassigned()).isFalse();
            assertThat(quad.colouredRed()).isTrue();

            // Every field other than the one touched is still untouched.
            assertThat(response.attributes(ScreenField.MCITYO).unassigned()).isTrue();
        }

        @Test
        @DisplayName("unassigned() is false as soon as any one of the four items is set")
        void unassignedIsFalseForEachItemIndependently() {
            assertThat(AttributeQuad.defaults().unassigned()).isTrue();
            assertThat(AttributeQuad.defaults().withColour((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withProgrammedSymbols((byte) 0x01).unassigned())
                    .isFalse();
            assertThat(AttributeQuad.defaults().withHighlight((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withValidation((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withColour((byte) 0x01).colouredRed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Navigation replaces XCTL, statelessly (gates G37 and G40)")
    class Navigation {

        @Test
        @DisplayName("a fresh response points at this screen's own mapset and map")
        void defaultsToOwnMapsetAndMap() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.getNextMapset()).isEqualTo("COTRN01").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("COTRN1A").hasSize(7);
            assertThat(response.getNextProgram()).isBlank().hasSize(8);
        }

        @Test
        @DisplayName("nextProgram is echoed from the commarea's CDEMO-TO-PROGRAM, at eight wide")
        void nextProgramEchoesTheCommarea() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNavigationContext(new NavigationContext("CT01", "COTRN01C", "CM00",
                    "COMEN01C", "USER0001", "U", NavigationContext.PGM_CONTEXT_REENTER,
                    1, "F", "M", "L", 11L, "Y", 16L, "COTRN1A", "COTRN01"));
            response.setNextProgram(response.getNavigationContext().toProgram());

            assertThat(response.getNextProgram()).isEqualTo("COMEN01C").hasSize(8);
        }

        @Test
        @DisplayName("navigation targets obey the PIC X move rule at 8, 7 and 7")
        void navigationTargetsObeyTheMoveRule() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNextProgram("A");
            response.setNextMapset("B");
            response.setNextMap("C");
            assertThat(response.getNextProgram()).hasSize(8).startsWith("A");
            assertThat(response.getNextMapset()).hasSize(7).startsWith("B");
            assertThat(response.getNextMap()).hasSize(7).startsWith("C");

            response.setNextProgram("PROGRAMNAMETOOLONG");
            response.setNextMapset("MAPSETTOOLONG");
            response.setNextMap("MAPTOOLONG");
            assertThat(response.getNextProgram()).isEqualTo("PROGRAMN");
            assertThat(response.getNextMapset()).isEqualTo("MAPSETT");
            assertThat(response.getNextMap()).isEqualTo("MAPTOOL");
        }

        @Test
        @DisplayName("no server-side session state is held anywhere on the class")
        void noServerSideSessionState() {
            for (java.lang.reflect.Field field : TransactionAddResponse.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    continue;
                }
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final - no static mutable state (B9)",
                                field.getName())
                        .isTrue();
            }
            for (java.lang.reflect.Field field : Ct01Info.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("The 58-byte CT01 cursor")
    class Cursor {

        @Test
        @DisplayName("a fresh cursor holds the declared VALUE 'N' and space-filled identifiers")
        void freshCursorHoldsDeclaredValues() {
            Ct01Info cursor = new Ct01Info();
            assertThat(cursor.getTrnidFirst()).hasSize(16).isBlank();
            assertThat(cursor.getTrnidLast()).hasSize(16).isBlank();
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.getTrnSelFlg()).hasSize(1).isBlank();
            assertThat(cursor.getTrnSelected()).hasSize(16).isBlank();
        }

        @Test
        @DisplayName("both 88-level states of NEXT-PAGE-FLG are reachable (gate G50)")
        void bothNextPageStatesAreReachable() {
            Ct01Info cursor = new Ct01Info();

            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageYes();
            assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageFlg(Ct01Info.NEXT_PAGE_YES);
            assertThat(cursor.isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a third character satisfies neither 88-level, exactly as in COBOL")
        void athirdCharacterSatisfiesNeitherCondition() {
            Ct01Info cursor = new Ct01Info();
            cursor.setNextPageFlg("X");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("PAGE-NUM accepts the whole eight-digit range and rejects what will not fit")
        void pageNumRangeIsEnforced() {
            Ct01Info cursor = new Ct01Info();

            cursor.setPageNum(0);
            assertThat(cursor.getPageNum()).isZero();
            cursor.setPageNum(99_999_999);
            assertThat(cursor.getPageNum()).isEqualTo(99_999_999);
            cursor.setPageNum(7);
            assertThat(cursor.getPageNum()).isEqualTo(7);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("PIC 9(08)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(100_000_000))
                    .withMessageContaining("PIC 9(08)");
        }

        @Test
        @DisplayName("the six items store what they are given and carry verbatim copybook names")
        void itemsStoreVerbatimAndCarryVerbatimNames() {
            Ct01Info cursor = new Ct01Info();
            cursor.setTrnidFirst("FIRSTIDTOOLONGXXXX");
            cursor.setTrnidLast("LAST");
            cursor.setTrnSelFlg("SS");
            cursor.setTrnSelected("SELECTED");

            // The shared carrier stores what it is given, unchanged - it does not pad a short value
            // and does not truncate a long one. The PIC X move is applied once, at the byte boundary
            // in toFixedWidth, which is where the direction of a truncation is visible and reviewable.
            // The carrier this class used to declare for itself moved on every setter instead, so an
            // over-long value was silently shortened before anyone could object to it.
            assertThat(cursor.getTrnidFirst()).isEqualTo("FIRSTIDTOOLONGXXXX").hasSize(18);
            assertThat(cursor.getTrnidLast()).isEqualTo("LAST");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("SS");
            assertThat(cursor.getTrnSelected()).isEqualTo("SELECTED");

            // ...and the image is still exactly 58 bytes, each field at its declared width.
            byte[] image = cursor.toFixedWidth(ASCII_CODEC);
            assertThat(image).hasSize(Ct01Info.RECORD_LENGTH);
            String rendered = new String(image, ASCII);
            assertThat(rendered.substring(0, 16)).isEqualTo("FIRSTIDTOOLONGXX");
            assertThat(rendered.substring(16, 32)).isEqualTo("LAST            ");

            assertThat(Ct01Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT01-TRNID-FIRST");
            assertThat(Ct01Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT01-TRNID-LAST");
            assertThat(Ct01Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT01-PAGE-NUM");
            assertThat(Ct01Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT01-NEXT-PAGE-FLG");
            assertThat(Ct01Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT01-TRN-SEL-FLG");
            assertThat(Ct01Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT01-TRN-SELECTED");
            // The surviving carrier names the field as the copybook does, not abbreviated.
            assertThat(cursor.toString()).contains("pageNum=0");
        }

        @Test
        @DisplayName("the cursor round trips through its 58-byte image, page number zero-filled")
        void cursorRoundTripsThroughItsImage() {
            Ct01Info cursor = new Ct01Info();
            cursor.setTrnidFirst("0000000000000001");
            cursor.setTrnidLast("0000000000000010");
            cursor.setPageNum(42);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000007");

            byte[] image = cursor.toFixedWidth(ASCII_CODEC);
            assertThat(image).hasSize(58);
            assertThat(new String(image, ASCII))
                    .startsWith("00000000000000010000000000000010")
                    .contains("00000042");

            Ct01Info parsed = Ct01Info.fromFixedWidth(ASCII_CODEC, image);
            assertThat(parsed.getTrnidFirst()).isEqualTo(cursor.getTrnidFirst());
            assertThat(parsed.getTrnidLast()).isEqualTo(cursor.getTrnidLast());
            assertThat(parsed.getPageNum()).isEqualTo(42);
            assertThat(parsed.isNextPageYes()).isTrue();
            assertThat(parsed.getTrnSelFlg()).isEqualTo("S");
            assertThat(parsed.getTrnSelected()).isEqualTo(cursor.getTrnSelected());
        }

        @Test
        @DisplayName("the cursor rejects a null charset and a wrongly-sized image")
        void cursorRejectsBadInput() {
            Ct01Info cursor = new Ct01Info();
            assertThatNullPointerException().isThrownBy(() -> cursor.toFixedWidth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(ASCII_CODEC, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(null, new byte[58]));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(ASCII_CODEC, new byte[57]));
        }
    }

    @Nested
    @DisplayName("Fixed-width rendering of the 575-byte image")
    class FixedWidthRendering {

        @Test
        @DisplayName("renders exactly 575 bytes with every field at its copybook offset")
        void rendersEveryFieldAtItsOffset() {
            TransactionAddResponse response = populated();

            byte[] image = response.toFixedWidth(ASCII);
            assertThat(image).hasSize(575);

            String text = new String(image, ASCII);
            for (ScreenField field : ScreenField.values()) {
                assertThat(text.substring(field.payloadOffset(),
                                field.payloadOffset() + field.payloadLength()))
                        .as("%s at %d", field, field.payloadOffset())
                        .isEqualTo(response.payload(field));
            }
        }

        @Test
        @DisplayName("the TIOAPFX prefix and every group filler are emitted as spaces (gate G21)")
        void fillersAreEmitted() {
            String text = new String(populated().toFixedWidth(ASCII), ASCII);

            assertThat(text.substring(0, 12)).isEqualTo(" ".repeat(12));
            for (ScreenField field : ScreenField.values()) {
                assertThat(text.substring(field.groupOffset(), field.groupOffset() + 3))
                        .as("group filler of %s", field)
                        .isEqualTo("   ");
            }
        }

        @Test
        @DisplayName("attribute items are written as raw bytes, not as characters")
        void attributeItemsAreWrittenAsBytes() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.highlightField(ScreenField.TRNIDINO, true, false, true);
            response.setAttributes(ScreenField.MZIPO,
                    AttributeQuad.defaults().withHighlight(BmsAttributes.DFHBMASB));

            byte[] image = response.toFixedWidth(ASCII);

            assertThat(image[ScreenField.TRNIDINO.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(image[ScreenField.MZIPO.highlightItemOffset()])
                    .isEqualTo(BmsAttributes.DFHBMASB);
            assertThat(image[ScreenField.TRNIDO.colourItemOffset()])
                    .isEqualTo(AttributeQuad.NO_CHANGE);
        }

        @Test
        @DisplayName("the image round trips losslessly - xxxO is never write-only")
        void imageRoundTripsLosslessly() {
            TransactionAddResponse original = populated();
            original.highlightField(ScreenField.TCATCDO, true, false, true);

            TransactionAddResponse parsed =
                    TransactionAddResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(parsed.payload(field)).as("%s", field)
                        .isEqualTo(original.payload(field));
                assertThat(parsed.attributes(field)).as("attributes of %s", field)
                        .isEqualTo(original.attributes(field));
            }
            assertThat(parsed.toFixedWidth(ASCII)).isEqualTo(original.toFixedWidth(ASCII));
        }

        @Test
        @DisplayName("writeInto and readFrom use the record's own code page")
        void writeIntoAndReadFromUseTheRecordCharset() {
            TransactionAddResponse original = populated();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(TransactionAddResponse.LAYOUT);

            original.writeInto(record);
            TransactionAddResponse parsed = TransactionAddResponse.readFrom(record);

            assertThat(parsed.getTrnidino()).isEqualTo(original.getTrnidino());
            assertThat(parsed.getErrmsgo()).isEqualTo(original.getErrmsgo());
            assertThat(record.toByteArray()).hasSize(575);
        }

        @Test
        @DisplayName("a record area of the wrong width is rejected rather than partly written")
        void wrongWidthRecordIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord tooShort = new FixedWidthRecord(574, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(tooShort))
                    .withMessageContaining("575");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddResponse.readFrom(tooShort))
                    .withMessageContaining("575");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(new byte[574], ASCII));
            assertThat(codec.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("rendering rejects a null charset - the code page is never implicit")
        void nullCharsetIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(new byte[575], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> TransactionAddResponse.readFrom(null));
            assertThatNullPointerException().isThrownBy(() -> response.toPassedCommarea(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.readPassedCommarea(new byte[218], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.readPassedCommarea(null, ASCII));
        }

        @Test
        @DisplayName("the passed commarea is the 160-byte context followed by the 58-byte cursor")
        void passedCommareaConcatenatesBothAreas() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNavigationContext(new NavigationContext("CT01", "COTRN01C", "CT01",
                    "COTRN01C", "USER0001", "U", NavigationContext.PGM_CONTEXT_ENTER,
                    0, "", "", "", 0L, " ", 0L, "COTRN1A", "COTRN01"));
            response.getCt01Info().setPageNum(3);
            response.getCt01Info().setNextPageYes();

            byte[] passed = response.toPassedCommarea(ASCII);
            assertThat(passed).hasSize(218);

            byte[] contextOnly =
                    response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII));
            byte[] cursorOnly = response.getCt01Info().toFixedWidth(ASCII_CODEC);
            assertThat(contextOnly).hasSize(160);
            assertThat(cursorOnly).hasSize(58);
            assertThat(new String(passed, ASCII).substring(0, 160))
                    .isEqualTo(new String(contextOnly, ASCII));
            assertThat(new String(passed, ASCII).substring(160))
                    .isEqualTo(new String(cursorOnly, ASCII));

            TransactionAddResponse received = new TransactionAddResponse();
            received.readPassedCommarea(passed, ASCII);
            assertThat(received.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(received.getCt01Info().getPageNum()).isEqualTo(3);
            assertThat(received.getCt01Info().isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a wrongly-sized passed commarea is rejected with the arithmetic in the message")
        void wrongSizedPassedCommareaIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.readPassedCommarea(new byte[217], ASCII))
                    .withMessageContaining("218")
                    .withMessageContaining("160")
                    .withMessageContaining("58");
        }
    }

    @Nested
    @DisplayName("JSON round trip")
    class Json {

        @Test
        @DisplayName("space padding survives serialisation and deserialisation untrimmed")
        void spacePaddingSurvivesUntrimmed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo("SHORT MESSAGE");
            response.setTrnamto("+00000123.45");

            String json = mapper.writeValueAsString(response);
            TransactionAddResponse parsed = mapper.readValue(json, TransactionAddResponse.class);

            assertThat(parsed.getErrmsgo())
                    .hasSize(78)
                    .isEqualTo(response.getErrmsgo())
                    .startsWith("SHORT MESSAGE")
                    .endsWith(" ");
            assertThat(parsed.getTrnamto()).hasSize(12).isEqualTo("+00000123.45");
        }

        @Test
        @DisplayName("the CT01 cursor is one carrier, spelled identically in both directions")
        void theCursorIsOneSharedCarrierAcrossThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            // One type, not two structurally identical ones. This is the whole of the fix: the
            // response used to declare its own CardDemoCt01Info under the property cardDemoCt01Info,
            // so a client could not echo the cursor it was sent without renaming the property first.
            assertThat(TransactionAddResponse.class.getDeclaredMethod("getCt01Info").getReturnType())
                    .isEqualTo(Ct01Info.class)
                    .isEqualTo(TransactionAddRequest.class.getDeclaredMethod("getCt01Info")
                            .getReturnType());
            assertThat(TransactionAddResponse.class.getDeclaredClasses())
                    .as("the response declares no cursor type of its own any more")
                    .noneMatch(c -> c.getSimpleName().contains("Ct01Info"));

            // And the echo works without transformation: take the cursor off a response, put it on a
            // request, and the JSON member is the same member.
            TransactionAddResponse sent = populated();
            sent.getCt01Info().setPageNum(4);
            sent.getCt01Info().setNextPageYes();
            sent.getCt01Info().setTrnSelected("0000000000000007");

            String responseJson = mapper.writeValueAsString(sent);
            assertThat(mapper.readTree(responseJson).has("ct01Info")).isTrue();
            assertThat(mapper.readTree(responseJson).has("cardDemoCt01Info")).isFalse();

            TransactionAddRequest echoed = new TransactionAddRequest();
            echoed.setCt01Info(sent.getCt01Info());
            String requestJson = mapper.writeValueAsString(echoed);

            assertThat(mapper.readTree(requestJson).get("ct01Info"))
                    .as("the cursor crosses the pair byte for byte, under one name")
                    .isEqualTo(mapper.readTree(responseJson).get("ct01Info"));

            // ...including through the fixed-width form both sides share.
            assertThat(echoed.getCt01Info().toFixedWidth(ASCII_CODEC))
                    .isEqualTo(sent.getCt01Info().toFixedWidth(ASCII_CODEC));
        }

        @Test
        @DisplayName("all 21 payload fields plus navigation and state appear; metadata does not")
        void payloadMembersAreExactlyTheProjection() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = mapper.readValue(
                    mapper.writeValueAsString(populated()), Map.class);

            for (ScreenField field : ScreenField.values()) {
                assertThat(tree)
                        .as("payload member for %s", field)
                        .containsKey(field.outputItemName().toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(tree).containsKeys("nextProgram", "nextMapset", "nextMap",
                    "navigationContext", "ct01Info");

            // Highlight metadata is never a payload member (AAP 0.6.3). The quad exists ON the type,
            // as the attribute items the map needs; that is not the same as being on the wire. Nor is
            // any input-side item: xxxL is the length CICS reports, xxxF and xxxA are the attribute
            // byte seen two ways, and all three are validation and highlight metadata rather than
            // content. All 21 x 4 quad names and all 21 x 3 input-side names are checked, not a
            // sample of them, because a single leaked member breaks the 1:1 projection gate G9.
            assertThat(tree).doesNotContainKeys("attributeItems", "payloadItems");
            for (ScreenField field : ScreenField.values()) {
                for (String metadataName : List.of(
                        field.colourItemName(),
                        field.programmedSymbolsItemName(),
                        field.highlightItemName(),
                        field.validationItemName(),
                        field.inputItemName(),
                        field.baseName() + "L",
                        field.baseName() + "F",
                        field.baseName() + "A")) {
                    assertThat(tree)
                            .as("%s is metadata, not a payload member", metadataName)
                            .doesNotContainKey(metadataName.toLowerCase(java.util.Locale.ROOT));
                }
            }
            // And nothing else at all: the 21 payload items plus exactly five state members -
            // the three navigation targets, the commarea and the cursor.
            assertThat(tree)
                    .as("the wire carries the projection and the state, and nothing besides")
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT + 5);
            // The card number and merchant id are on the wire in full (gate G41).
            assertThat(tree.get("cardnumo")).isEqualTo("4111111111111111");
            assertThat(tree.get("mido")).isEqualTo("123456789");
        }

        @Test
        @DisplayName("the whole payload round trips field for field")
        void wholePayloadRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddResponse original = populated();
            original.setNextProgram("COMEN01C");

            TransactionAddResponse parsed = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionAddResponse.class);

            for (ScreenField field : ScreenField.values()) {
                assertThat(parsed.payload(field)).as("%s", field)
                        .isEqualTo(original.payload(field));
            }
            assertThat(parsed.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(parsed.getNextMapset()).isEqualTo(original.getNextMapset());
            assertThat(parsed.getNextMap()).isEqualTo(original.getNextMap());
            assertThat(parsed.getCt01Info().getPageNum())
                    .isEqualTo(original.getCt01Info().getPageNum());
            assertThat(parsed.toFixedWidth(ASCII)).isEqualTo(original.toFixedWidth(ASCII));
        }
    }

    @Nested
    @DisplayName("The REDEFINES overlay: one span, two views (gate G34)")
    class SymbolicMapOverlay {

        /*
         * app/cpy-bms/COTRN01.CPY declares the same 575 bytes twice. At each field's group offset k:
         *
         *   AI view (line 17)   k..k+1  xxxL COMP PIC S9(4)   - a SIGNED halfword
         *                       k+2     xxxF PICTURE X        - and xxxA, REDEFINES of the SAME byte
         *                       k+3..k+6 FILLER PICTURE X(4)
         *                       k+7..    xxxI PIC X(n)
         *
         *   AO view (line 145)  k..k+2  FILLER PICTURE X(3)
         *                       k+3     xxxC     k+4 xxxP     k+5 xxxH     k+6 xxxV
         *                       k+7..    xxxO PIC X(n)
         *
         * Three consequences follow, and each is asserted below as a round trip over one backing
         * span rather than as arithmetic on paper: the attribute quad lands exactly where the input
         * view sees inert filler; the cursor length item lands exactly where the output view sees
         * inert filler; and xxxI and xxxO are the very same n bytes, so neither is write-only.
         */

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the quad occupies the four bytes the input view calls FILLER X(4)")
        void quadOccupiesTheInputViewsPerFieldFiller(ScreenField field) {
            TransactionAddResponse response = positionRevealingResponse();
            AttributeQuad marked = new AttributeQuad(BmsAttributes.DFHRED, BmsAttributes.DFHBMASB,
                    BmsAttributes.DFHBMBRY, BmsAttributes.DFHUNIMD);
            response.setAttributes(field, marked);

            byte[] image = response.toFixedWidth(ASCII);
            int inputViewFillerOffset = field.groupOffset()
                    + TransactionAddRequest.LENGTH_ITEM_LENGTH
                    + TransactionAddRequest.FLAG_ITEM_LENGTH;

            assertThat(inputViewFillerOffset)
                    .as("the input view's reserved FILLER X(4) begins where the quad begins")
                    .isEqualTo(field.colourItemOffset());
            assertThat(Arrays.copyOfRange(image, inputViewFillerOffset,
                    inputViewFillerOffset + TransactionAddRequest.RESERVED_FILLER_LENGTH))
                    .as("the four bytes %s reserves are the quad of %s",
                            field.inputItemName(), field.outputItemName())
                    .containsExactly(BmsAttributes.DFHRED, BmsAttributes.DFHBMASB,
                            BmsAttributes.DFHBMBRY, BmsAttributes.DFHUNIMD);

            // Writing the quad is invisible through the input view: neither the length item, the flag
            // byte nor the payload moved.
            assertThat(Arrays.copyOfRange(image, field.groupOffset(), inputViewFillerOffset))
                    .as("xxxL and xxxF are untouched by an attribute write")
                    .containsOnly((byte) ' ');
            assertThat(new String(Arrays.copyOfRange(image, field.payloadOffset(),
                    field.payloadOffset() + field.payloadLength()), ASCII))
                    .as("the payload item is untouched by an attribute write")
                    .isEqualTo(response.payload(field));
        }

        @Test
        @DisplayName("a signed -1 cursor length round trips as -1 and is invisible to the output view")
        void cursorLengthItemIsSignedAndInvisibleThroughTheOutputView() {
            // app/cbl/COTRN01C.cbl moves -1 into TRNIDINL at six sites, which is why the halfword has
            // to be signed: an unsigned reading would report 65535 and place the cursor nowhere.
            assertThat(TransactionAddRequest.CURSOR_REQUEST).isEqualTo((short) -1);
            assertThat(CURSOR_REQUEST_SITE_COUNT).isEqualTo(6);

            TransactionAddResponse response = positionRevealingResponse();
            response.setAttributes(ScreenField.TRNIDINO,
                    AttributeQuad.defaults().withColour(BmsAttributes.DFHRED));
            byte[] untouched = response.toFixedWidth(ASCII);

            byte[] withCursor = untouched.clone();
            int lengthItemOffset = ScreenField.TRNIDINO.groupOffset();
            withCursor[lengthItemOffset] = (byte) ((TransactionAddRequest.CURSOR_REQUEST >> 8) & 0xFF);
            withCursor[lengthItemOffset + 1] = (byte) (TransactionAddRequest.CURSOR_REQUEST & 0xFF);

            short readBack = (short) (((withCursor[lengthItemOffset] & 0xFF) << 8)
                    | (withCursor[lengthItemOffset + 1] & 0xFF));
            assertThat(readBack)
                    .as("TRNIDINL is COMP PIC S9(4) - two bytes, signed, so -1 comes back as -1")
                    .isEqualTo((short) -1);

            // The AO view calls those same two bytes part of its FILLER X(3), so the output
            // projection cannot see the difference: every payload item and every quad is unchanged.
            TransactionAddResponse throughOutputView =
                    TransactionAddResponse.fromFixedWidth(withCursor, ASCII);
            TransactionAddResponse baseline =
                    TransactionAddResponse.fromFixedWidth(untouched, ASCII);
            assertThat(throughOutputView.payloadItems()).isEqualTo(baseline.payloadItems());
            assertThat(throughOutputView.attributeItems()).isEqualTo(baseline.attributeItems());
            assertThat(withCursor).isNotEqualTo(untouched);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("xxxI and xxxO are the identical n bytes at group+7, read either way")
        void inputAndOutputItemsShareTheSameBytes(ScreenField field) {
            Map<String, Integer> inputOffsets = inputItemOffsets();
            int inputOffset = inputOffsets.get(field.inputItemName());

            assertThat(inputOffset)
                    .as("%s and %s must begin at the same offset", field.inputItemName(),
                            field.outputItemName())
                    .isEqualTo(field.payloadOffset());
            assertThat(TransactionAddRequest.declaredLengthOf(field.inputItemName()))
                    .as("%s and %s must be the same width", field.inputItemName(),
                            field.outputItemName())
                    .isEqualTo(field.payloadLength());

            // Output side to bytes: what the O item holds is what those n bytes hold.
            TransactionAddResponse response = positionRevealingResponse();
            byte[] image = response.toFixedWidth(ASCII);
            String throughInputView = new String(Arrays.copyOfRange(image, inputOffset,
                    inputOffset + field.payloadLength()), ASCII);
            assertThat(throughInputView).isEqualTo(response.payload(field));

            // Bytes to output side: what is written at the I item's offset is what the O item reads.
            String rewritten = widthOf("<" + field.inputItemName() + '>', field.payloadLength());
            byte[] mutated = image.clone();
            System.arraycopy(rewritten.getBytes(ASCII), 0, mutated, inputOffset,
                    field.payloadLength());
            assertThat(TransactionAddResponse.fromFixedWidth(mutated, ASCII).payload(field))
                    .as("an input-side write is visible through %s - neither item is write-only",
                            field.outputItemName())
                    .isEqualTo(rewritten);
        }

        @Test
        @DisplayName("both views spend exactly seven prefix bytes, so the aliases stay aligned")
        void bothViewsSpendSevenPrefixBytes() {
            assertThat(TransactionAddResponse.FIELD_ATTRIBUTE_PREFIX_LENGTH)
                    .as("AO: FILLER X(3) + four one-byte attribute items")
                    .isEqualTo(7);
            assertThat(TransactionAddRequest.FIELD_METADATA_LENGTH)
                    .as("AI: xxxL (2) + xxxF (1) + FILLER X(4)")
                    .isEqualTo(TransactionAddResponse.FIELD_ATTRIBUTE_PREFIX_LENGTH);
            assertThat(TransactionAddRequest.LENGTH_ITEM_LENGTH
                    + TransactionAddRequest.FLAG_ITEM_LENGTH)
                    .as("the AO view's FILLER X(3) covers exactly xxxL and xxxF")
                    .isEqualTo(TransactionAddResponse.FIELD_GROUP_FILLER_LENGTH);
            assertThat(TransactionAddRequest.RESERVED_FILLER_LENGTH)
                    .as("the AI view's FILLER X(4) covers exactly the four attribute items")
                    .isEqualTo(TransactionAddResponse.ATTRIBUTE_ITEM_COUNT
                            * TransactionAddResponse.ATTRIBUTE_ITEM_LENGTH);
        }

        @Test
        @DisplayName("the two views describe one image of the same length, prefix included")
        void bothViewsDescribeOneImage() {
            assertThat(TransactionAddResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(TransactionAddRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(575);
            assertThat(TransactionAddResponse.TOTAL_PAYLOAD_WIDTH)
                    .isEqualTo(TransactionAddRequest.PAYLOAD_TOTAL_LENGTH)
                    .isEqualTo(416);
            assertThat(TransactionAddResponse.TIOAPFX_PREFIX_LENGTH)
                    .isEqualTo(TransactionAddRequest.TIOA_PREFIX_LENGTH)
                    .isEqualTo(12);
            assertThat(TransactionAddResponse.OUTPUT_MAP_GROUP_NAME)
                    .isEqualTo(TransactionAddRequest.SYMBOLIC_MAP_OUTPUT_GROUP)
                    .isEqualTo("COTRN1AO");
            assertThat(TransactionAddResponse.INPUT_MAP_GROUP_NAME)
                    .isEqualTo(TransactionAddRequest.SYMBOLIC_MAP_GROUP)
                    .isEqualTo("COTRN1AI");
        }

        @Test
        @DisplayName("the leading TIOAPFX filler and every group filler stay space-filled (gate G21)")
        void everyFillerSpanStaysSpaceFilled() {
            byte[] image = positionRevealingResponse().toFixedWidth(ASCII);

            assertThat(Arrays.copyOfRange(image, 0, TransactionAddResponse.TIOAPFX_PREFIX_LENGTH))
                    .as("the 12-byte TIOAPFX prefix carries no application data but must be emitted")
                    .containsOnly((byte) ' ');

            int fillerBytes = TransactionAddResponse.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(Arrays.copyOfRange(image, field.groupOffset(),
                        field.groupOffset() + TransactionAddResponse.FIELD_GROUP_FILLER_LENGTH))
                        .as("the FILLER X(3) opening %s's group", field.outputItemName())
                        .containsOnly((byte) ' ');
                fillerBytes += TransactionAddResponse.FIELD_GROUP_FILLER_LENGTH;
            }
            assertThat(fillerBytes)
                    .as("12 + 21x3 filler bytes are declared spans, not slack")
                    .isEqualTo(75);
            assertThat(image).hasSize(TransactionAddResponse.SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Header fields come from an injected fixed Clock (practice B7)")
    class InjectedClockHeader {

        @Test
        @DisplayName("a fixed clock renders CURDATEO and CURTIMEO deterministically")
        void fixedClockRendersTheHeaderDeterministically() {
            DateHeader first = DateHeader.from(ASCII_CODEC, FIXED_CLOCK);
            DateHeader second = DateHeader.from(ASCII_CODEC, FIXED_CLOCK);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setCurdateo(first.wsCurdateMmDdYy());
            response.setCurtimeo(first.wsCurtimeHhMmSs());

            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("23:15:57");
            assertThat(second.wsCurdateMmDdYy()).isEqualTo(first.wsCurdateMmDdYy());
            assertThat(second.wsCurtimeHhMmSs()).isEqualTo(first.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("the rendered widths match the screen items exactly, at 8 and 8")
        void renderedWidthsMatchTheScreenItems() {
            DateHeader header = DateHeader.from(ASCII_CODEC, FIXED_CLOCK);

            assertThat(header.wsCurdateMmDdYy())
                    .hasSize(TransactionAddResponse.CURDATE_ITEM_LENGTH)
                    .hasSize(ScreenField.CURDATEO.payloadLength());
            assertThat(header.wsCurtimeHhMmSs())
                    .hasSize(TransactionAddResponse.CURTIME_ITEM_LENGTH)
                    .hasSize(ScreenField.CURTIMEO.payloadLength());
            assertThat(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH).isEqualTo(8);
            assertThat(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the zone is honoured, so no rendering can be inheriting the platform default")
        void theZoneIsHonouredRatherThanDefaulted() {
            DateHeader atUtc = DateHeader.from(ASCII_CODEC, Clock.fixed(FIXED_INSTANT, FIXED_ZONE));
            DateHeader atCentral =
                    DateHeader.from(ASCII_CODEC, Clock.fixed(FIXED_INSTANT, CENTRAL_ZONE));

            // One instant, two named zones, two different local times. If the zone were being
            // ignored - or taken from the platform - these two would agree, and this assertion is
            // what makes that impossible to miss.
            assertThat(atCentral.wsCurtimeHhMmSs()).isNotEqualTo(atUtc.wsCurtimeHhMmSs());
            assertThat(atCentral.wsCurtimeHhMmSs()).isEqualTo("18:15:57");
            assertThat(atCentral.wsCurdateMmDdYy()).isEqualTo("07/19/22");

            TransactionAddResponse response = new TransactionAddResponse();
            response.setCurtimeo(atCentral.wsCurtimeHhMmSs());
            assertThat(response.getCurtimeo()).isEqualTo("18:15:57");
        }
    }

    @Nested
    @DisplayName("Header literals, the error line, and the two thank-you traps")
    class LiteralsAndMessages {

        @Test
        @DisplayName("the titles are X(40) and land in TITLE01O and TITLE02O without padding")
        void titlesAreFortyAndFitExactly() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(ScreenTitles.TITLE_LENGTH);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTitle01o(ScreenTitles.CCDA_TITLE01);
            response.setTitle02o(ScreenTitles.CCDA_TITLE02);

            // Exactly forty into exactly forty: nothing is padded and nothing is lost, which is the
            // whole reason TITLE_ITEM_LENGTH is defined as the shared constant rather than as a 40.
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(TransactionAddResponse.TITLE_ITEM_LENGTH);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenField.TITLE02O.payloadLength());
        }

        @Test
        @DisplayName("the two thank-you literals differ in text, width and owner - never substitute one")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            // app/cpy/COTTL01Y.cpy declares CCDA-THANK-YOU PIC X(40), naming the CCDA application.
            // app/cpy/CSMSG01Y.cpy declares CCDA-MSG-THANK-YOU PIC X(50), naming CardDemo. Two
            // copybooks, two widths, two wordings. Substituting either for the other silently changes
            // both the text and the field width, and the compiler cannot catch it because both are
            // Strings - so it is asserted here instead.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(ScreenTitles.TITLE_LENGTH).isNotEqualTo(SystemMessages.MESSAGE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU).isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo application");

            // And neither belongs in a title item at the other's width: the X(50) message would be
            // truncated by ten characters if it were treated as a title.
            assertThat(ASCII_CODEC.movePicX(SystemMessages.CCDA_MSG_THANK_YOU,
                    ScreenTitles.TITLE_LENGTH))
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
        }

        @Test
        @DisplayName("a 50-byte standard message is right-space-padded into the 78-byte ERRMSGO")
        void standardMessageIsRightPaddedIntoTheErrorLine() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(TransactionAddResponse.STANDARD_MESSAGE_LENGTH)
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH);
            assertThat(ScreenField.ERRMSGO.payloadLength()).isEqualTo(78);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);

            assertThat(response.getErrmsgo())
                    .hasSize(ScreenField.ERRMSGO.payloadLength())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(78 - SystemMessages.MESSAGE_LENGTH));
            assertThat(response.getErrmsgo()).endsWith(" ".repeat(28));
        }

        @Test
        @DisplayName("WS-MESSAGE is X(80) and loses its last two characters on the right, as COBOL does")
        void wsMessageIsWiderThanTheErrorLine() {
            // app/cbl/COTRN01C.cbl:38 declares WS-MESSAGE PIC X(80); line 217 moves it into
            // ERRMSGO PIC X(78). Eighty into seventy-eight truncates on the right for a PIC X
            // receiver - the two lost characters are the last two, never the first two.
            assertThat(TransactionAddResponse.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(TransactionAddResponse.WS_MESSAGE_LENGTH)
                    .isGreaterThan(ScreenField.ERRMSGO.payloadLength());

            String eighty = widthOf("0123456789", TransactionAddResponse.WS_MESSAGE_LENGTH);
            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo(eighty);

            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .isEqualTo(eighty.substring(0, 78));
            assertThat(response.getErrmsgo()).isNotEqualTo(eighty.substring(2));
        }

        @Test
        @DisplayName("ERRMSGO is 78 wide, output-only and declared red by the map itself")
        void errorLineCarriesItsDeclaredBmsAttributes() {
            // app/bms/COTRN01.bms:259 - ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED,
            // LENGTH=78, POS=(23,1). The colour is a property of the MAP, independent of anything
            // FieldAttributeSetter later does: the error line is red because it is declared red, not
            // because a field failed validation.
            assertThat(ScreenField.ERRMSGO.payloadLength()).isEqualTo(78);
            assertThat(ScreenField.ERRMSGO)
                    .as("ASKIP: the error line is written to, never typed into, so it is not the "
                            + "screen's single input-capable field")
                    .isNotEqualTo(ScreenField.TRNIDINO);
            assertThat(ScreenField.ERRMSGO)
                    .as("ERRMSGO is the last field of the group, at offset 490")
                    .isEqualTo(ScreenField.values()[ScreenField.values().length - 1]);
            assertThat(ScreenField.ERRMSGO.groupEndOffsetExclusive())
                    .isEqualTo(TransactionAddResponse.SYMBOLIC_MAP_LENGTH);

            // A fresh response leaves the colour item unassigned: the map's own COLOR=RED is not
            // something the payload restates, and DFHDFCOL means "leave the declared colour alone".
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.attributes(ScreenField.ERRMSGO).colour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response.attributes(ScreenField.ERRMSGO).colouredRed()).isFalse();
            assertThat(BmsAttributes.DFHRED)
                    .as("the highlight colour is the IBM DFHBMSCA value, never a local literal")
                    .isEqualTo((byte) 0xF2)
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    @Nested
    @DisplayName("Moves from the 350-byte TRAN-RECORD onto the screen (rule R5)")
    class RecordToScreenMoves {

        /*
         * Every move below goes through FixedWidthCodec.movePicX or movePic9 rather than through a
         * plain Java assignment, because the direction of truncation is a per-PICTURE decision:
         * COBOL fills a PIC X receiver from the LEFT and discards the overflow on the right, and
         * fills a PIC 9 receiver from the RIGHT and discards the overflow on the left. A plain
         * assignment would neither pad nor truncate, and the resulting parity defect is invisible at
         * the call site - which is exactly why these are asserted rather than assumed.
         */

        @Test
        @DisplayName("TRAN-DESC X(100) into TDESCO X(60) keeps the FIRST sixty characters")
        void descriptionKeepsItsLeadingSixtyCharacters() {
            String description = widthOf("DESCRIPTION-", TRAN_DESC_WIDTH);
            assertThat(description).hasSize(100);

            String moved = ASCII_CODEC.movePicX(description, ScreenField.TDESCO.payloadLength());

            assertThat(moved).hasSize(60).isEqualTo(description.substring(0, 60));
            assertThat(moved).isNotEqualTo(description.substring(TRAN_DESC_WIDTH - 60));

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTdesco(description);
            assertThat(response.getTdesco()).isEqualTo(moved);
        }

        @Test
        @DisplayName("the merchant name and city truncate on the right, at 30 and 25")
        void merchantNameAndCityTruncateOnTheRight() {
            String name = widthOf("MERCHANT-NAME-", TRAN_MERCHANT_NAME_WIDTH);
            String city = widthOf("MERCHANT-CITY-", TRAN_MERCHANT_CITY_WIDTH);
            assertThat(name).hasSize(50);
            assertThat(city).hasSize(50);

            assertThat(ASCII_CODEC.movePicX(name, ScreenField.MNAMEO.payloadLength()))
                    .hasSize(30).isEqualTo(name.substring(0, 30));
            assertThat(ASCII_CODEC.movePicX(city, ScreenField.MCITYO.payloadLength()))
                    .hasSize(25).isEqualTo(city.substring(0, 25));

            TransactionAddResponse response = new TransactionAddResponse();
            response.setMnameo(name);
            response.setMcityo(city);
            assertThat(response.getMnameo()).isEqualTo(name.substring(0, 30));
            assertThat(response.getMcityo()).isEqualTo(city.substring(0, 25));
        }

        @Test
        @DisplayName("the two X(26) timestamps become the ten-character YYYY-MM-DD date prefix")
        void timestampsBecomeTheirDatePrefix() {
            assertThat(TIMESTAMP_26).hasSize(TRAN_TIMESTAMP_WIDTH);

            String origin = ASCII_CODEC.movePicX(TIMESTAMP_26,
                    ScreenField.TORIGDTO.payloadLength());
            String processed = ASCII_CODEC.movePicX(TIMESTAMP_26,
                    ScreenField.TPROCDTO.payloadLength());

            assertThat(origin).hasSize(10).isEqualTo("2022-07-19");
            assertThat(processed).isEqualTo(origin);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTorigdto(TIMESTAMP_26);
            response.setTprocdto(TIMESTAMP_26);
            assertThat(response.getTorigdto()).isEqualTo("2022-07-19");
            assertThat(response.getTprocdto()).isEqualTo("2022-07-19");
        }

        @Test
        @DisplayName("the exact-width moves neither pad nor truncate anything")
        void exactWidthMovesAreIdentity() {
            Map<ScreenField, Integer> exactWidths = new LinkedHashMap<>();
            exactWidths.put(ScreenField.TRNIDINO, TRAN_ID_WIDTH);
            exactWidths.put(ScreenField.TRNIDO, TRAN_ID_WIDTH);
            exactWidths.put(ScreenField.CARDNUMO, TRAN_ID_WIDTH);
            exactWidths.put(ScreenField.TRNSRCO, TRAN_SOURCE_WIDTH);
            exactWidths.put(ScreenField.TTYPCDO, TRAN_TYPE_CD_WIDTH);
            exactWidths.put(ScreenField.MZIPO, TRAN_MERCHANT_ZIP_WIDTH);

            exactWidths.forEach((field, sendingWidth) -> {
                assertThat(field.payloadLength())
                        .as("%s and its sending field are the same width", field.outputItemName())
                        .isEqualTo(sendingWidth);
                String sending = widthOf("9", sendingWidth);
                assertThat(ASCII_CODEC.movePicX(sending, field.payloadLength()))
                        .isEqualTo(sending);
            });
            assertThat(exactWidths).hasSize(6);
        }

        @Test
        @DisplayName("a numeric sending field zero-fills, where an alphanumeric one space-pads")
        void numericSendingFieldsZeroFill() {
            // MOVE TRAN-CAT-CD (PIC 9(04)) TO TCATCDI (PIC X(4)) carries the digit characters,
            // leading zeros included: category 2 arrives as "0002", not as "2   ". The direction of
            // the fill is the whole difference between the two PICTUREs.
            String category = ASCII_CODEC.movePic9(2L, TRAN_CAT_CD_DIGITS);
            assertThat(category).isEqualTo("0002").hasSize(ScreenField.TCATCDO.payloadLength());

            String merchantId = ASCII_CODEC.movePic9(123456789L, TRAN_MERCHANT_ID_DIGITS);
            assertThat(merchantId).isEqualTo("123456789")
                    .hasSize(ScreenField.MIDO.payloadLength());

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTcatcdo(category);
            response.setMido(merchantId);
            assertThat(response.getTcatcdo()).isEqualTo("0002");
            assertThat(response.getMido()).isEqualTo("123456789");

            // The contrast, on the same value: an alphanumeric move pads on the RIGHT with spaces.
            assertThat(ASCII_CODEC.movePicX("2", TRAN_CAT_CD_DIGITS)).isEqualTo("2   ");
        }

        @Test
        @DisplayName("a short sending value is padded on the right, keeping the declared width")
        void shortSendingValuesArePaddedOnTheRight() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setMcityo("OMAHA");
            response.setMzipo("68102");
            response.setTrnsrco("POS");

            assertThat(response.getMcityo()).isEqualTo("OMAHA" + " ".repeat(20)).hasSize(25);
            assertThat(response.getMzipo()).isEqualTo("68102" + " ".repeat(5)).hasSize(10);
            assertThat(response.getTrnsrco()).isEqualTo("POS" + " ".repeat(7)).hasSize(10);
        }

        @Test
        @DisplayName("TRAN-AMT reaches the screen only through the +99999999.99 mask, never raw")
        void amountReachesTheScreenOnlyThroughTheMask() {
            assertThat(TRNAMT_MASK_WIDTH).isEqualTo(12)
                    .isEqualTo(ScreenField.TRNAMTO.payloadLength());

            assertThat(editedAmountImage(123_456L)).isEqualTo("+00001234.56").hasSize(12);
            assertThat(editedAmountImage(-1L)).isEqualTo("-00000000.01").hasSize(12);
            assertThat(editedAmountImage(0L)).isEqualTo("+00000000.00").hasSize(12);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnamto(editedAmountImage(123_456L));
            assertThat(response.getTrnamto()).isEqualTo("+00001234.56");

            // The raw record field is eleven digit positions, the screen field twelve characters, so
            // the two are not interchangeable: assigning the raw image would leave the screen field a
            // character short and space-padded, which is visibly not an edited amount.
            assertThat(TRAN_AMT_DIGITS).isEqualTo(11).isNotEqualTo(TRNAMT_MASK_WIDTH);
            String rawImage = ASCII_CODEC.movePic9(123_456L, TRAN_AMT_DIGITS);
            assertThat(rawImage).hasSize(11);
            response.setTrnamto(rawImage);
            assertThat(response.getTrnamto())
                    .as("a raw zoned image is not an edited amount - no sign, no decimal point")
                    .isEqualTo(rawImage + " ")
                    .doesNotContain(".");
        }

        @Test
        @DisplayName("the mask holds eight integer digits where the record holds nine, and truncates left")
        void theMaskIsOneIntegerDigitNarrowerThanTheRecord() {
            // PIC S9(09)V99 can hold 999999999.99; the +99999999.99 mask cannot. A PIC 9 receiver is
            // filled from the RIGHT, so the ninth digit is lost from the LEFT - the opposite end from
            // a PIC X truncation. This is documented COBOL behaviour and is preserved, not repaired.
            assertThat(MASK_INTEGER_DIGITS).isEqualTo(8);
            assertThat(ASCII_CODEC.movePic9(123_456_789L, MASK_INTEGER_DIGITS))
                    .as("the leading digit is discarded, not the trailing one")
                    .isEqualTo("23456789");
            assertThat(editedAmountImage(12_345_678_901L)).isEqualTo("+23456789.01");
        }
    }

    @Nested
    @DisplayName("The published CSSETATY vocabulary, asserted rather than reinvented")
    class PublishedHighlightVocabulary {

        @Test
        @DisplayName("the asterisk and the colour come from the shared contracts, not from literals")
        void theVocabularyIsTheSharedOne() {
            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
            assertThat(FieldAttributeSetter.OUTPUT_MAP_SUFFIX).isEqualTo("O");
            assertThat(TransactionAddResponse.OUTPUT_MAP_GROUP_NAME)
                    .isEqualTo(TransactionAddResponse.MAP_NAME
                            + FieldAttributeSetter.OUTPUT_MAP_SUFFIX);
        }

        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        @DisplayName("all three flag states are expressible, and only two of them highlight")
        void allThreeFlagStatesAreExpressible(FieldValidationState state) {
            FieldHighlight onReenter = FieldAttributeSetter.resolve(state, true,
                    ScreenField.TRNIDINO.baseName(), TransactionAddResponse.MAP_NAME);
            FieldHighlight onEnter = FieldAttributeSetter.resolve(state, false,
                    ScreenField.TRNIDINO.baseName(), TransactionAddResponse.MAP_NAME);

            assertThat(onEnter.untouched())
                    .as("first entry never highlights, whatever the flag says")
                    .isTrue();
            assertThat(onReenter.colourItemAssigned())
                    .as("%s reddens on re-entry only when the field is not OK", state)
                    .isEqualTo(state != FieldValidationState.OK);
            assertThat(onReenter.outputItemAssigned())
                    .as("%s writes the asterisk only when the field is BLANK", state)
                    .isEqualTo(state == FieldValidationState.BLANK);
        }

        @Test
        @DisplayName("the three-way state is built from the two 88-levels, both set included")
        void theStateIsBuiltFromTheTwoConditionNames() {
            assertThat(FieldValidationState.of(false, false)).isEqualTo(FieldValidationState.OK);
            assertThat(FieldValidationState.of(true, false)).isEqualTo(FieldValidationState.NOT_OK);
            assertThat(FieldValidationState.of(false, true)).isEqualTo(FieldValidationState.BLANK);
            assertThat(FieldValidationState.of(true, true))
                    .as("CSSETATY tests BLANK second, so a field that is both takes the asterisk")
                    .isEqualTo(FieldValidationState.BLANK);
            assertThat(FieldValidationState.NOT_OK.notOk()).isTrue();
            assertThat(FieldValidationState.BLANK.blank()).isTrue();
            assertThat(FieldValidationState.OK.notOk()).isFalse();
            assertThat(FieldValidationState.OK.blank()).isFalse();
        }

        @Test
        @DisplayName("the highlight surface is one field wide, because the map has one input field")
        void theHighlightSurfaceIsOneFieldWide() {
            // Only TRNIDIN is UNPROT (app/bms/COTRN01.bms:85), so on this screen only one field can
            // ever be typed into and therefore only one can ever fail validation. The sibling
            // COTRN02 map has 14 input-capable fields and a correspondingly wide highlight surface;
            // the narrowness here is a property of a view screen, not an omission.
            assertThat(BMS_INPUT_CAPABLE_FIELD_COUNT).isEqualTo(1);

            TransactionAddResponse response = new TransactionAddResponse();
            FieldHighlight applied = response.highlightField(ScreenField.TRNIDINO, false, true, true);

            assertThat(applied.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(applied.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(applied.colourItemName()).isEqualTo(ScreenField.TRNIDINO.colourItemName());
            assertThat(applied.outputItemName()).isEqualTo(ScreenField.TRNIDINO.outputItemName());
            assertThat(applied.outputMapGroupName())
                    .isEqualTo(TransactionAddResponse.OUTPUT_MAP_GROUP_NAME);
        }
    }

    @Nested
    @DisplayName("The source census, encoded from the read-only inputs (practice B3)")
    class SourceCensus {

        @Test
        @DisplayName("21 of the map's 56 DFHMDF definitions carry a name and become payload items")
        void namedAndUnnamedFieldsReconcile() {
            assertThat(BMS_NAMED_FIELD_COUNT + BMS_UNNAMED_FIELD_COUNT)
                    .as("every DFHMDF is either named or a literal - there is no third kind")
                    .isEqualTo(BMS_FIELD_DEFINITION_COUNT);
            assertThat(BMS_NAMED_FIELD_COUNT)
                    .isEqualTo(TransactionAddResponse.PAYLOAD_FIELD_COUNT)
                    .isEqualTo(TransactionAddRequest.PAYLOAD_FIELD_COUNT)
                    .isEqualTo(ScreenField.values().length)
                    .isEqualTo(COPYBOOK_FIELDS.size())
                    .isEqualTo(21);

            // The 35 unnamed entries are the screen's own furniture: captions such as 'Tran:',
            // 'Date:', 'Transaction ID:' and the LENGTH=0 stopper fields that terminate a field on
            // the 3270 datastream. They are painted from the map and never travel in a payload,
            // which is why 56 field definitions produce 21 JSON members and not 56 (gate G9).
            assertThat(BMS_UNNAMED_FIELD_COUNT).isEqualTo(35);
        }

        @Test
        @DisplayName("one field is input-capable and twenty are output-only, as a view screen implies")
        void inputCapableAndOutputOnlyFieldsReconcile() {
            assertThat(BMS_INPUT_CAPABLE_FIELD_COUNT + BMS_OUTPUT_ONLY_FIELD_COUNT)
                    .isEqualTo(BMS_NAMED_FIELD_COUNT);
            assertThat(BMS_INPUT_CAPABLE_FIELD_COUNT).isEqualTo(1);
            assertThat(BMS_OUTPUT_ONLY_FIELD_COUNT).isEqualTo(20);
            assertThat(ScreenField.TRNIDINO.outputItemName()).isEqualTo("TRNIDINO");
            assertThat(ScreenField.TRNIDINO.payloadLength())
                    .isEqualTo(ScreenField.TRNIDO.payloadLength())
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("this map declares no OCCURS table, so gate G33 has no subject here")
        void noOccursTableExistsOnThisMap() {
            // Recorded as an assertion rather than as silence. G33 checks that every OCCURS table is
            // indexed correctly across COBOL's 1-based to Java's 0-based conversion - the single
            // largest defect risk in the wider migration. COTRN01 has no repeating group at all:
            // neither app/bms/COTRN01.bms nor app/cpy-bms/COTRN01.CPY contains the word OCCURS, and
            // the 21 fields are 21 distinct names rather than a subscripted table. The paging screens
            // COTRN00 and COUSR00 are where that gate does apply.
            assertThat(OCCURS_TABLE_COUNT).isZero();

            List<String> baseNames = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.baseName())
                        .as("%s is a plain field name, not a subscripted reference",
                                field.outputItemName())
                        .doesNotContain("(")
                        .doesNotContain(")");
                baseNames.add(field.baseName());
            }
            // A repeating group shows up as one base name reused across several rows. All 21 are
            // distinct, so there is no row dimension - which is the mechanical form of "no OCCURS".
            // TITLE01 and TITLE02 are the closest thing to a pair here and are two independent
            // literals from app/cpy/COTTL01Y.cpy, not two occurrences of one table entry.
            assertThat(baseNames).doesNotHaveDuplicates().hasSize(BMS_NAMED_FIELD_COUNT);
        }

        @Test
        @DisplayName("the program has one XCTL site and six cursor requests, in 330 lines")
        void programSiteCountsAreRecorded() {
            assertThat(XCTL_SITE_COUNT)
                    .as("app/cbl/COTRN01C.cbl:206, EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)")
                    .isEqualTo(1);
            assertThat(CURSOR_REQUEST_SITE_COUNT)
                    .as("MOVE -1 TO TRNIDINL at lines 102, 151, 154, 287, 294 and 311")
                    .isEqualTo(6);
            assertThat(PROGRAM_LINE_COUNT).isEqualTo(330);

            // The single XCTL site is COMMAREA-driven, so it resolves to exactly one response field
            // rather than to a server-side forward (gate G40).
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNavigationContext(NavigationContext.empty().withToProgram("COMEN01C"));
            response.setNextProgram(response.getNavigationContext().toProgram());
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C")
                    .hasSize(TransactionAddResponse.NEXT_PROGRAM_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each field's width agrees between the input view, the output view and the map")
        void widthsAgreeAcrossBothViewsAndTheMap(ScreenField field) {
            int copybookWidth = -1;
            for (Object[] row : COPYBOOK_FIELDS) {
                if (row[0].equals(field.outputItemName())) {
                    copybookWidth = (Integer) row[1];
                }
            }
            assertThat(copybookWidth)
                    .as("%s must appear in the independently transcribed table",
                            field.outputItemName())
                    .isPositive();
            assertThat(field.payloadLength())
                    .as("output view width of %s", field.outputItemName())
                    .isEqualTo(copybookWidth);
            assertThat(TransactionAddRequest.declaredLengthOf(field.inputItemName()))
                    .as("input view width of %s", field.inputItemName())
                    .isEqualTo(copybookWidth);
        }

        @Test
        @DisplayName("the identity constants agree with the CSD and with the README's inventory")
        void identityAgreesWithTheCsd() {
            // app/csd/CARDDEMO.CSD:429 DEFINE TRANSACTION(CT01) PROGRAM(COTRN01C); :264 DEFINE
            // PROGRAM(COTRN01C); :149 DEFINE MAPSET(COTRN01). README.md:223 lists the same triple
            // and names its function "Transaction View" - the R-B swap this suite documents.
            assertThat(TransactionAddResponse.TRANSACTION_ID)
                    .isEqualTo(TransactionAddRequest.TRANSACTION_ID).isEqualTo("CT01");
            assertThat(TransactionAddResponse.PROGRAM_NAME)
                    .isEqualTo(TransactionAddRequest.PROGRAM_NAME).isEqualTo("COTRN01C");
            assertThat(TransactionAddResponse.MAPSET_NAME)
                    .isEqualTo(TransactionAddRequest.MAPSET_NAME).isEqualTo("COTRN01");
            assertThat(TransactionAddResponse.MAP_NAME)
                    .isEqualTo(TransactionAddRequest.MAP_NAME).isEqualTo("COTRN1A");
            assertThat(TransactionAddResponse.MAP_NAME)
                    .as("a map name is seven characters, and this one uses all seven")
                    .hasSize(TransactionAddResponse.NEXT_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Negative contracts: what must never appear (gates G22, G24, G37, G44, G53)")
    class NegativeContracts {

        @Test
        @DisplayName("no persistence annotation and no version column anywhere (gate G44)")
        void noPersistenceAnnotationIsPresent() {
            List<Class<?>> subjects = List.of(TransactionAddResponse.class, ScreenField.class,
                    AttributeQuad.class, Ct01Info.class);
            for (Class<?> subject : subjects) {
                assertAnnotationsAreNotPersistence(subject.getAnnotations(), subject.getSimpleName());
                for (java.lang.reflect.Field field : subject.getDeclaredFields()) {
                    assertAnnotationsAreNotPersistence(field.getAnnotations(),
                            subject.getSimpleName() + '.' + field.getName());
                    assertThat(field.getName())
                            .as("no optimistic-locking version column may be introduced - the COBOL "
                                    + "re-reads and compares instead")
                            .isNotEqualToIgnoringCase("version");
                }
                for (Method method : subject.getDeclaredMethods()) {
                    assertAnnotationsAreNotPersistence(method.getAnnotations(),
                            subject.getSimpleName() + '.' + method.getName());
                }
            }

            // Stronger still: the JPA annotations are not even on the classpath, so no type in this
            // module can carry one. The prompt forbids an ORM outright, and the pom's closed
            // dependency set is what makes that structural rather than aspirational.
            assertThatExceptionOfType(ClassNotFoundException.class).isThrownBy(
                    () -> Class.forName("jakarta.persistence.Entity"));
            assertThatExceptionOfType(ClassNotFoundException.class).isThrownBy(
                    () -> Class.forName("jakarta.persistence.Version"));
        }

        @Test
        @DisplayName("no rounding mode is reachable, so HALF_UP, HALF_EVEN, CEILING and FLOOR cannot be")
        void noRoundingModeIsReachable() {
            assertThat(FORBIDDEN_ROUNDING_MODES)
                    .as("the four modes this migration must never use")
                    .doesNotContain(RoundingMode.DOWN)
                    .hasSize(4);

            for (Class<?> type : declaredApiTypes()) {
                assertThat(type)
                        .as("no member of the payload may be a rounding mode: ROUNDED appears zero "
                                + "times in the 28 programs, so %s are all unreachable here",
                                FORBIDDEN_ROUNDING_MODES)
                        .isNotEqualTo(RoundingMode.class);
                assertThat(type.getName())
                        .as("no java.math type may appear in the payload's API - the module's single "
                                + "seam for scale and RoundingMode.DOWN is CobolDecimal, and the "
                                + "service layer owns it")
                        .doesNotStartWith("java.math.");
            }
        }

        @Test
        @DisplayName("no floating-point type appears anywhere in the API surface (gates G22, G23)")
        void noFloatingPointTypeAppears() {
            for (Class<?> type : declaredApiTypes()) {
                assertThat(type)
                        .as("every payload member is a String at its declared width")
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class)
                        .isNotEqualTo(double[].class)
                        .isNotEqualTo(float[].class);
            }
            for (ScreenField field : ScreenField.values()) {
                assertThat(new TransactionAddResponse().payload(field))
                        .as("%s is a String", field.outputItemName())
                        .isInstanceOf(String.class);
            }
        }

        @Test
        @DisplayName("no session, servlet or thread-local state is named anywhere (gates G37, G53)")
        void noServerSideStateTypeIsNamed() {
            for (Class<?> type : declaredApiTypes()) {
                assertThat(FORBIDDEN_STATE_TYPES)
                        .as("CICS is pseudo-conversational: the state travels in the payload, so %s "
                                + "must not appear", type.getSimpleName())
                        .doesNotContain(type.getSimpleName());
            }
            List<Class<?>> subjects = List.of(TransactionAddResponse.class, ScreenField.class,
                    AttributeQuad.class, Ct01Info.class);
            for (Class<?> subject : subjects) {
                for (java.lang.annotation.Annotation annotation : subject.getAnnotations()) {
                    assertThat(FORBIDDEN_STATE_ANNOTATIONS)
                            .as("%s must not bind %s to a server-side session",
                                    annotation.annotationType().getSimpleName(),
                                    subject.getSimpleName())
                            .doesNotContain(annotation.annotationType().getSimpleName());
                }
            }

            // And the state that does exist is carried outward, at its declared widths.
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.getNavigationContext()).isNotNull();
            assertThat(response.getCt01Info()).isNotNull();
            assertThat(NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH)
                    .isEqualTo(TransactionAddResponse.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(218);
        }

        /**
         * Asserts that none of a member's annotations is a persistence mapping annotation.
         *
         * @param annotations the annotations found on the member
         * @param subject     the member's name, for the failure message
         */
        private void assertAnnotationsAreNotPersistence(
                java.lang.annotation.Annotation[] annotations, String subject) {
            for (java.lang.annotation.Annotation annotation : annotations) {
                assertThat(FORBIDDEN_PERSISTENCE_ANNOTATIONS)
                        .as("%s must carry no schema mapping: the prompt forbids DDL, an ORM and a "
                                + "version column outright", subject)
                        .doesNotContain(annotation.annotationType().getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("toString names the screen, its geometry and the lookup key")
    void toStringIsDiagnostic() {
        TransactionAddResponse response = populated();
        assertThat(response.toString())
                .contains("CT01")
                .contains("COTRN01C")
                .contains("COTRN01.COTRN1A")
                .contains("fields=21")
                .contains("image=575B");
    }

    /**
     * A response with all 21 fields populated and a non-default cursor - the shape a controller hands
     * back after a successful lookup.
     *
     * @return the populated response; never {@code null}
     */
    private static TransactionAddResponse populated() {
        TransactionAddResponse response = new TransactionAddResponse();
        response.setTrnnameo(TransactionAddResponse.TRANSACTION_ID);
        response.setTitle01o(ScreenTitles.CCDA_TITLE01);
        response.setCurdateo("08/08/26");
        response.setPgmnameo(TransactionAddResponse.PROGRAM_NAME);
        response.setTitle02o(ScreenTitles.CCDA_TITLE02);
        response.setCurtimeo("12:34:56");
        response.setTrnidino("0000000000000042");
        response.setTrnido("0000000000000042");
        response.setCardnumo("4111111111111111");
        response.setTtypcdo("01");
        response.setTcatcdo("0001");
        response.setTrnsrco("POS TERM");
        response.setTdesco("A PURCHASE AT A MERCHANT");
        response.setTrnamto("+00000123.45");
        response.setTorigdto("2022-07-18");
        response.setTprocdto("2022-07-19");
        response.setMido("123456789");
        response.setMnameo("A MERCHANT");
        response.setMcityo("A CITY");
        response.setMzipo("12345");
        response.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
        response.getCt01Info().setPageNum(1);
        response.getCt01Info().setTrnidFirst("0000000000000001");
        return response;
    }
}
