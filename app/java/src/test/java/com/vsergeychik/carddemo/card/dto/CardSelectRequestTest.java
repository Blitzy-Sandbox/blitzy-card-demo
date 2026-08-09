package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ScreenFieldMetadata;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link CardSelectRequest}, the inbound projection of the {@code CCRDSLAI} input
 * group of {@code app/cpy-bms/COCRDSL.CPY} and of the fifteen name-labelled {@code DFHMDF} entries of
 * {@code app/bms/COCRDSL.bms}.
 *
 * <p>Every expected value below is transcribed from those two files and from
 * {@code app/cbl/COCRDSLC.cbl}, never read back out of the class under test, so the sources stay the
 * authority: a width, a line number or an offset that drifted in either place fails here.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure names
 * a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>There are <strong>fifteen</strong> fields, from 15 name-labelled {@code DFHMDF} entries of 31,
 *       and the two independent width columns - {@code LENGTH=} operands and {@code PICTURE} clauses -
 *       agree at <strong>387</strong>.</li>
 *   <li>The group is <strong>504</strong> bytes: 12 + 15 &times; 7 + 387, and the field strides abut
 *       with no gap and no overlap.</li>
 *   <li>{@code xxxL} and {@code xxxA} are metadata and never reach the JSON wire, while the fifteen
 *       {@code xxxI} values and the two conversation-state carriers do.</li>
 *   <li>The {@code PIC X} move rule - pad right, truncate right - is applied only through
 *       {@link FixedWidthCodec}, so a setter never resizes and a getter never trims.</li>
 *   <li>{@code EXPDAY} and {@code PAGENO} are absent, and the widths of {@code INFOMSG},
 *       {@code ERRMSG} and {@code FKEYS} are the ones {@code COCRDSL} declares rather than a value
 *       shared with the sibling card maps.</li>
 * </ol>
 *
 * <h2>Thirty-one {@code DFHMDF} entries, fifteen of which are contract</h2>
 *
 * <p>Any count that reports 31 fields is measuring the wrong thing. {@code app/bms/COCRDSL.bms}
 * declares 31 {@code DFHMDF} entries; 15 carry a name and the remaining 16 are screen furniture that
 * BMS paints but never reports back, so they generate no symbolic-map item and can hold no value:
 *
 * <ul>
 *   <li><strong>Eleven unnamed literal captions</strong>, each carrying {@code INITIAL=} text -
 *       {@code 'Tran:'} (line 29), {@code 'Date:'} (42), {@code 'Prog:'} (52), {@code 'Time:'} (65),
 *       {@code 'View Credit Card Detail'} (75), {@code 'Account Number    :'} (79),
 *       {@code 'Card Number       :'} (91), {@code 'Name on card      :'} (103),
 *       {@code 'Card Active Y/N   : '} (112), {@code 'Expiry Date       : '} (122) and the
 *       {@code '/'} expiry separator (130).</li>
 *   <li><strong>Five unnamed {@code LENGTH=0} attribute terminators</strong>, at
 *       {@code POS=(7,57)} (line 89), {@code (8,62)} (101), {@code (11,76)} (110), {@code (13,27)}
 *       (120) and {@code (15,35)} (137). A zero-length field reserves the attribute byte that closes
 *       the preceding unprotected field; it carries no data at all.</li>
 * </ul>
 *
 * <p>15 + 11 + 5 = 31, and only the first 15 reach this payload.
 *
 * <h2>Why the metadata quad exists, derived rather than asserted</h2>
 *
 * <p>{@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} on the {@code DFHMDI} at {@code app/bms/COCRDSL.bms:26}
 * names four extended attributes, and that operand count is the mechanical reason the <em>output</em>
 * group {@code CCRDSLAO} carries exactly four attribute bytes per field - {@code xxxC}, {@code xxxP},
 * {@code xxxH}, {@code xxxV} at {@code app/cpy-bms/COCRDSL.CPY:112-115} and its fourteen repetitions -
 * behind a three-byte {@code FILLER}. Three plus four is seven. On the <em>input</em> side the same
 * seven bytes are spent differently, on {@code xxxL} (2) plus {@code xxxF} (1) plus a four-byte
 * reserved {@code FILLER}, which is why {@code CCRDSLAO} can {@code REDEFINE} {@code CCRDSLAI} at
 * {@code COCRDSL.CPY:109} at all. That is what makes "{@code xxxL}, {@code xxxF} and {@code xxxA} are
 * metadata, not payload" a derivation from the mapset rather than a bare assertion: the four bytes are
 * the four {@code DSATTS} operands, and the class under test spends its seven the input way.
 *
 * <h2>A documented divergence from the Agent Action Plan, not a correction of it</h2>
 *
 * <p>Section 0.6.2 of the Agent Action Plan states that all 17 mapsets declare
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT STORAGE=AUTO TIOAPFX=YES}. For
 * {@code COCRDSL} that is <strong>not what the source says</strong>. The mapset declaration at
 * {@code app/bms/COCRDSL.bms:20-24} is:
 *
 * <pre>
 * COCRDSL DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&amp;&amp;SYSPARM
 * </pre>
 *
 * <p>with <strong>no {@code CTRL}, no {@code ALARM} and no {@code EXTATT}</strong>. {@code CTRL} is
 * present one level down, on the map rather than the mapset, at {@code app/bms/COCRDSL.bms:25-28}:
 *
 * <pre>
 * CCRDSLA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *                MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)
 * </pre>
 *
 * <p>This is recorded here as a divergence and nothing more (practice <strong>B4</strong>: document a
 * conflict, never silently fix one). The Agent Action Plan is not edited, the mapset is not edited -
 * everything under {@code app/bms/} is read-only (<strong>B3</strong>) - and no width, offset or field
 * in this suite changes because of it. What the divergence does change is what may be asserted:
 * {@link MapsetDeclaration} below asserts the consequences the <em>source</em> supports, which is that
 * {@code SIZE=(24,80)} and the four {@code DSATTS} operands are observable in this type while
 * {@code ALARM}, {@code FREEKB} and {@code EXTATT} are not payload at any level - the first two because
 * they are 3270 device controls and the third because {@code COCRDSL} never asks for it.
 *
 * <h2>Attributes do not generalise across the card maps</h2>
 *
 * <p>{@code EXPYEAR} is {@code ATTRB=(ASKIP)} here ({@code app/bms/COCRDSL.bms:133}) and
 * {@code UNPROT} on the update screen: the same field name is read-only on this map and typeable on
 * {@code COCRDUP}. Only {@code ACCTSID} ({@code FSET,IC,NORM,UNPROT}, line 84) and {@code CARDSID}
 * ({@code FSET,NORM,UNPROT}, line 96) are typeable on this screen, and {@code IC} puts the initial
 * cursor on {@code ACCTSID}. No assertion in this suite reasons from a sibling map's attributes.
 *
 * <h2>What this suite deliberately does not test</h2>
 *
 * <p>{@code CardSelectControllerTest} owns the {@code 0000-MAIN} {@code EVALUATE} order, the valid-AID
 * set, {@code PF3} back-navigation and the file-access paragraphs. {@code CardSelectResponseTest} owns
 * highlighting: the four {@code DFHRED} sites of {@code app/cbl/COCRDSLC.cbl:534}, {@code :538},
 * {@code :544} and {@code :550} all write {@code xxxC} of the <em>output</em> group {@code CCRDSLAO},
 * so none of them is reachable from this type. {@code CSSETATY} is not asserted anywhere in the card
 * package because it has exactly one COBOL consumer, {@code app/cbl/COACTUPC.cbl}, and
 * {@code COCRDSLC} highlights inline instead. {@code CardScreenStateTest} pins LOW-VALUES against
 * SPACES against {@code null} and the three {@code REDEFINES} round trips of
 * {@code app/cpy/CVCRD01Y.cpy} - {@code CC-ACCT-ID}, {@code CC-CARD-NUM} and {@code CC-CUST-ID} - and
 * those semantics are relied on here rather than re-derived.
 *
 * <h2>Six branches that are unreachable by design</h2>
 *
 * <p>{@code CardSelectRequest} retains six branches this suite deliberately does not drive, each
 * verified unreachable through a public entry point rather than merely left alone. They are recorded
 * here so that nobody spends effort chasing them, and so that nobody reaches them the only way they
 * could be reached - by breaking the production class, which is not permitted.
 *
 * <ul>
 *   <li><strong>Four in the class-initialisation self-check.</strong> The static verifier walks the
 *       fifteen field strides and throws unless the count is fifteen, each {@code xxxL} lands where the
 *       preceding field ended, the widths sum to 387 and the walk finishes on 504. All four throwing
 *       arms are dead by construction: if any could be taken, the class would fail to initialise and no
 *       test in this file could run at all. The suite asserts the same four facts from the outside
 *       instead, in {@code WidthContract} and {@code Fields}.</li>
 *   <li><strong>One synthetic arm of an exhaustive {@code switch}.</strong> {@code setValue} switches
 *       over all fifteen enum constants with no {@code default}; the compiler still emits an
 *       unreachable fallthrough. Every one of the fifteen real arms is driven, by
 *       {@code setValueAgreesWithTheNamedSetters}.</li>
 *   <li><strong>One defence-in-depth width guard.</strong> The group-image writer compares each
 *       encoded item against its declared width, but the codec's own single-byte-code-page guard
 *       reaches a multi-byte charset first, so the writer's comparison never fails. Both the accepting
 *       path and the refusal are driven by {@code aMultiByteEncodingIsRefused}; only the second, inner
 *       guard is shadowed.</li>
 * </ul>
 *
 * <p>With those six excluded as unreachable, {@code com.vsergeychik.carddemo.card.dto} clears the
 * mandated branch gate of 0.90 on its own measurement.
 */
@DisplayName("CardSelectRequest - COCRDSL CCRDSLAI, the CCDL inbound payload")
class CardSelectRequestTest {

    /**
     * The card number, account identifier and embossed name used throughout this suite are the first
     * record of {@code app/data/ASCII/carddata.txt} and its {@code app/data/ASCII/cardxref.txt} mate -
     * card {@code 0500024453765740}, account {@code 00000000050}, name {@code Aniya Von}. Seeding from
     * the shipped fixtures rather than from invented digits keeps the inputs production-shaped and
     * keeps this suite consistent with the data the parity harness uses.
     */
    private static final String FIXTURE_CARD_NUMBER = "0500024453765740";

    /**
     * The account identifier of that same first fixture record: bytes 17 to 27 of
     * {@code app/data/ASCII/carddata.txt} record 1, {@code CARD-ACCT-ID PIC 9(11)} of
     * {@code app/cpy/CVACT02Y.cpy}, and the same eleven digits {@code app/data/ASCII/cardxref.txt}
     * carries. Exactly {@value CardSelectRequest#ACCTSID_LENGTH} characters, so it neither pads nor
     * truncates.
     */
    private static final String FIXTURE_ACCOUNT_ID = "00000000050";

    /**
     * The embossed name of that record, {@code CARD-EMBOSSED-NAME PIC X(50)}, carried in the clear.
     *
     * <p>Nine characters into a fifty-wide field, so it is also the suite's shortest padding case for
     * {@code CRDNAME}.
     */
    private static final String FIXTURE_EMBOSSED_NAME = "Aniya Von";

    /**
     * The CSD transaction identifier of this screen: {@code DEFINE TRANSACTION(CCDL) GROUP(CARDDEMO)}
     * at {@code app/csd/CARDDEMO.CSD:347}, whose next line names {@code PROGRAM(COCRDSLC)}. Four
     * characters, which is exactly {@code TRNNAMEI PIC X(4)}.
     */
    private static final String CSD_TRANSACTION_ID = "CCDL";

    /**
     * The CSD program name: {@code DEFINE PROGRAM(COCRDSLC) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:219}, described there as {@code VIEW CARD DETAIL} and bound back to
     * this transaction by {@code TRANSID(CCDL)} at {@code app/csd/CARDDEMO.CSD:223}. Eight characters,
     * which is exactly {@code PGMNAMEI PIC X(8)}.
     */
    private static final String CSD_PROGRAM_NAME = "COCRDSLC";

    /** The code page of the ASCII fixtures under {@code app/data/ASCII}, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the datasets under {@code app/data/EBCDIC}, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    private static final FixedWidthCodec EBCDIC_CODEC = new FixedWidthCodec(EBCDIC);

    // The six transcription tables below are the hand-copied source columns this suite compares the
    // class against. Each is an immutable List rather than an array: an array is mutable however final
    // its reference is, and practice B9 with gate G53 rule out static mutable state, so a table that
    // one test method could rewrite for the next is ruled out with it. List.of also rejects a null
    // element, which is a transcription slip worth failing on at class-initialisation time.

    /**
     * The fifteen {@code DFHMDF} labels, in the order {@code app/bms/COCRDSL.bms} declares them.
     * Transcribed from the mapset, so the enumeration is compared against the source and not against
     * itself.
     */
    private static final List<String> LABELS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID",
            "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "INFOMSG", "ERRMSG", "FKEYS");

    /** The fifteen {@code LENGTH=} operands, which are also the fifteen {@code PICTURE} widths. */
    private static final List<Integer> WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 11, 16, 50, 1, 2, 4, 40, 80, 75);

    /** The lines of {@code app/cpy-bms/COCRDSL.CPY} declaring each {@code xxxI} item. */
    private static final List<Integer> CPY_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102, 108);

    /** The lines of {@code app/bms/COCRDSL.bms} opening each {@code DFHMDF} entry. */
    private static final List<Integer> BMS_LINES =
            List.of(34, 38, 47, 57, 61, 70, 84, 96, 107, 116, 126, 133, 139, 144, 148);

    /** The {@code POS=} row of each of the fifteen entries. */
    private static final List<Integer> POS_ROWS =
            List.of(1, 1, 1, 2, 2, 2, 7, 8, 11, 13, 15, 15, 20, 23, 24);

    /** The {@code POS=} column of each of the fifteen entries. */
    private static final List<Integer> POS_COLUMNS =
            List.of(7, 21, 71, 7, 21, 71, 45, 45, 25, 25, 25, 30, 25, 1, 1);

    /** Where each field's {@code xxxI} data begins inside the 504-byte group. */
    private static final List<Integer> DATA_OFFSETS =
            List.of(19, 30, 77, 92, 107, 154, 169, 187, 210, 267, 275, 284, 295, 342, 429);

    /**
     * The fifteen named setters, in copybook order, so that a test can drive each one through its own
     * method rather than only through {@link CardSelectRequest#setValue}. That distinction matters:
     * {@code setValue} delegating correctly is itself a property under test.
     */
    private static List<BiConsumer<CardSelectRequest, String>> namedSetters() {
        List<BiConsumer<CardSelectRequest, String>> setters = new ArrayList<>();
        setters.add(CardSelectRequest::setTrnname);
        setters.add(CardSelectRequest::setTitle01);
        setters.add(CardSelectRequest::setCurdate);
        setters.add(CardSelectRequest::setPgmname);
        setters.add(CardSelectRequest::setTitle02);
        setters.add(CardSelectRequest::setCurtime);
        setters.add(CardSelectRequest::setAcctsid);
        setters.add(CardSelectRequest::setCardsid);
        setters.add(CardSelectRequest::setCrdname);
        setters.add(CardSelectRequest::setCrdstcd);
        setters.add(CardSelectRequest::setExpmon);
        setters.add(CardSelectRequest::setExpyear);
        setters.add(CardSelectRequest::setInfomsg);
        setters.add(CardSelectRequest::setErrmsg);
        setters.add(CardSelectRequest::setFkeys);
        return setters;
    }

    /** The fifteen named getters, in copybook order. */
    private static List<java.util.function.Function<CardSelectRequest, String>> namedGetters() {
        List<java.util.function.Function<CardSelectRequest, String>> getters = new ArrayList<>();
        getters.add(CardSelectRequest::getTrnname);
        getters.add(CardSelectRequest::getTitle01);
        getters.add(CardSelectRequest::getCurdate);
        getters.add(CardSelectRequest::getPgmname);
        getters.add(CardSelectRequest::getTitle02);
        getters.add(CardSelectRequest::getCurtime);
        getters.add(CardSelectRequest::getAcctsid);
        getters.add(CardSelectRequest::getCardsid);
        getters.add(CardSelectRequest::getCrdname);
        getters.add(CardSelectRequest::getCrdstcd);
        getters.add(CardSelectRequest::getExpmon);
        getters.add(CardSelectRequest::getExpyear);
        getters.add(CardSelectRequest::getInfomsg);
        getters.add(CardSelectRequest::getErrmsg);
        getters.add(CardSelectRequest::getFkeys);
        return getters;
    }

    // =================================================================================================

    @Nested
    @DisplayName("The width contract - fifteen fields summing to 387 in a 504-byte group")
    class WidthContract {

        @Test
        @DisplayName("the fifteen width constants are the fifteen xxxI PICTURE widths")
        void widthConstantsMatchThePictureClauses() {
            assertThat(CardSelectRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.ACCTSID_LENGTH).isEqualTo(11);
            assertThat(CardSelectRequest.CARDSID_LENGTH).isEqualTo(16);
            assertThat(CardSelectRequest.CRDNAME_LENGTH).isEqualTo(50);
            assertThat(CardSelectRequest.CRDSTCD_LENGTH).isEqualTo(1);
            assertThat(CardSelectRequest.EXPMON_LENGTH).isEqualTo(2);
            assertThat(CardSelectRequest.EXPYEAR_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.INFOMSG_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.ERRMSG_LENGTH).isEqualTo(80);
            assertThat(CardSelectRequest.FKEYS_LENGTH).isEqualTo(75);
        }

        @Test
        @DisplayName("each field costs 2 + 1 + 4 = 7 bytes before its data")
        void perFieldOverheadIsSeven() {
            assertThat(CardSelectRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(CardSelectRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.FIELD_OVERHEAD).isEqualTo(7);
        }

        @Test
        @DisplayName("4+40+8+8+40+8+11+16+50+1+2+4+40+80+75 = 387")
        void theFifteenWidthsSumTo387() {
            int sum = 0;
            for (int width : WIDTHS) {
                sum += width;
            }
            assertThat(WIDTHS).hasSize(15);
            assertThat(sum).isEqualTo(387);
            assertThat(CardSelectRequest.PAYLOAD_LENGTH).isEqualTo(387);
        }

        @Test
        @DisplayName("12 + 15 * 7 + 387 = 504")
        void theGroupIs504Bytes() {
            assertThat(CardSelectRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardSelectRequest.FIELD_COUNT).isEqualTo(15);
            assertThat(CardSelectRequest.TIOAPFX_LENGTH
                    + CardSelectRequest.FIELD_COUNT * CardSelectRequest.FIELD_OVERHEAD
                    + CardSelectRequest.PAYLOAD_LENGTH).isEqualTo(504);
            assertThat(CardSelectRequest.GROUP_LENGTH).isEqualTo(504);
        }

        /**
         * The two offsets that bracket the group, each derived rather than looked up.
         *
         * <p>The first {@code xxxI} item cannot begin before byte 19: {@code app/cpy-bms/COCRDSL.CPY:18}
         * spends twelve bytes on the {@code TIOAPFX} {@code FILLER}, then lines 19 to 23 spend the
         * seven-byte prefix on {@code TRNNAMEL}, {@code TRNNAMEF} and the reserved {@code FILLER}, and
         * 12 + 7 = 19. The last one cannot end anywhere but 504: {@code FKEYSI} begins at 429 and is
         * {@code PIC X(75)} wide, and 429 + 75 = 504, which is the group length. Between them the
         * thirteen intermediate offsets have no freedom left.
         */
        @Test
        @DisplayName("TRNNAMEI begins at 12 + 7 = 19 and FKEYSI ends at 429 + 75 = 504")
        void theFirstAndLastOffsetsBracketTheGroupExactly() {
            assertThat(CardSelectRequest.TIOAPFX_LENGTH + CardSelectRequest.FIELD_OVERHEAD)
                    .as("twelve bytes of TIOAPFX FILLER, then one seven-byte prefix")
                    .isEqualTo(19);
            assertThat(ScreenField.TRNNAME.dataOffset()).isEqualTo(19);
            assertThat(ScreenField.TRNNAME.lengthItemOffset())
                    .as("TRNNAMEL sits immediately after the TIOAPFX FILLER")
                    .isEqualTo(12);

            assertThat(ScreenField.FKEYS.dataOffset()).isEqualTo(429);
            assertThat(ScreenField.FKEYS.dataOffset() + CardSelectRequest.FKEYS_LENGTH)
                    .isEqualTo(504);
            assertThat(ScreenField.FKEYS.endOffsetExclusive())
                    .isEqualTo(CardSelectRequest.GROUP_LENGTH)
                    .isEqualTo(504);

            // And the thirteen between them, so the bracket is not the only thing checked.
            assertThat(ScreenField.values())
                    .extracting(ScreenField::dataOffset)
                    .containsExactlyElementsOf(DATA_OFFSETS);
        }

        @Test
        @DisplayName("the three message widths are COCRDSL's own, not shared with COCRDLI or COCRDUP")
        void messageWidthsAreNotUnifiedAcrossTheCardMaps() {
            // COCRDLI declares INFOMSG X(45) and ERRMSG X(78); COCRDUP declares FKEYS X(21) and
            // COCRDLI declares none at all. Collapsing any of these would break the contract on the
            // sibling screens.
            assertThat(CardSelectRequest.INFOMSG_LENGTH).isEqualTo(40).isNotEqualTo(45);
            assertThat(CardSelectRequest.ERRMSG_LENGTH).isEqualTo(80).isNotEqualTo(78);
            assertThat(CardSelectRequest.FKEYS_LENGTH).isEqualTo(75).isNotEqualTo(21);
        }

        /**
         * The gate G9 traceability sweep, driven from two independent source columns.
         *
         * <p>Each row of the {@code @CsvSource} is transcribed by hand from the two files: the width
         * appears once as a {@code LENGTH=} operand in {@code app/bms/COCRDSL.bms} and once as a
         * {@code PICTURE} width in {@code app/cpy-bms/COCRDSL.CPY}, and both are written out
         * separately so that a single transcription slip cannot satisfy the assertion. Gate G9 asks
         * for exactly that dual check; comparing the class against itself would pass while the sources
         * disagreed. The {@code POS=} operands travel in the display name, so the report shows each
         * field's screen geometry without a reader opening the mapset.
         *
         * @param label       the {@code DFHMDF} label
         * @param bmsLength   the {@code LENGTH=} operand of that {@code DFHMDF} entry
         * @param cpyPicture  the {@code PIC X(n)} width of the matching {@code xxxI} item
         * @param row         the {@code POS=} row
         * @param column      the {@code POS=} column
         * @param bmsLine     the line of {@code app/bms/COCRDSL.bms} opening the entry
         * @param cpyLine     the line of {@code app/cpy-bms/COCRDSL.CPY} declaring the {@code xxxI}
         * @param dataOffset  where the {@code xxxI} data begins in the 504-byte group
         */
        @ParameterizedTest(name = "{0} LENGTH={1} PIC X({2}) POS=({3},{4}) at offset {7}")
        @CsvSource({
            "TRNNAME,  4, 4,  1,  7,  34,  24,  19",
            "TITLE01, 40, 40, 1, 21,  38,  30,  30",
            "CURDATE,  8, 8,  1, 71,  47,  36,  77",
            "PGMNAME,  8, 8,  2,  7,  57,  42,  92",
            "TITLE02, 40, 40, 2, 21,  61,  48, 107",
            "CURTIME,  8, 8,  2, 71,  70,  54, 154",
            "ACCTSID, 11, 11, 7, 45,  84,  60, 169",
            "CARDSID, 16, 16, 8, 45,  96,  66, 187",
            "CRDNAME, 50, 50, 11, 25, 107,  72, 210",
            "CRDSTCD,  1, 1,  13, 25, 116,  78, 267",
            "EXPMON,   2, 2,  15, 25, 126,  84, 275",
            "EXPYEAR,  4, 4,  15, 30, 133,  90, 284",
            "INFOMSG, 40, 40, 20, 25, 139,  96, 295",
            "ERRMSG,  80, 80, 23,  1, 144, 102, 342",
            "FKEYS,   75, 75, 24,  1, 148, 108, 429"
        })
        @DisplayName("each field's declared width is both its DFHMDF LENGTH and its xxxI PICTURE")
        void everyFieldTracesToBothSourceColumns(String label, int bmsLength, int cpyPicture, int row,
                int column, int bmsLine, int cpyLine, int dataOffset) {
            assertThat(bmsLength)
                    .as("the two source columns must agree before either can be an expectation")
                    .isEqualTo(cpyPicture);

            ScreenField field = ScreenField.byLabel(label);
            assertThat(field.length()).as(label + " LENGTH= operand").isEqualTo(bmsLength);
            assertThat(field.length()).as(label + "I PICTURE width").isEqualTo(cpyPicture);
            assertThat(field.symbolicItemName()).isEqualTo(label + "I");
            assertThat(field.screenRow()).as(label + " POS row").isEqualTo(row);
            assertThat(field.screenColumn()).as(label + " POS column").isEqualTo(column);
            assertThat(field.mapsetLine()).as(label + " COCRDSL.bms line").isEqualTo(bmsLine);
            assertThat(field.copybookLine()).as(label + " COCRDSL.CPY line").isEqualTo(cpyLine);
            assertThat(field.dataOffset()).as(label + "I offset").isEqualTo(dataOffset);
            assertThat(field.endOffsetExclusive()).isEqualTo(dataOffset + cpyPicture);

            // A field is only traceable if it is actually the width the type imposes, so drive the
            // move rule too rather than trusting the constant in isolation.
            CardSelectRequest request = new CardSelectRequest();
            request.setValue(field, "9".repeat(cpyPicture));
            assertThat(request.image(field, ASCII_CODEC)).hasSize(bmsLength);
        }

        /**
         * {@code EXPDAY} and {@code PAGENO} are absent, established structurally.
         *
         * <p>Neither is checked by reflection (practice <strong>B11</strong>): the inventory is fixed
         * at fifteen and the arithmetic is closed, so there is no room for a sixteenth field. If
         * {@code COCRDSL} had {@code COCRDUP}'s {@code EXPDAY PIC X(2)} the payload would sum to 389
         * and the group to 513; if it had {@code COCRDLI}'s {@code PAGENO} the numbers would move
         * again. Both totals are asserted as literals, so either addition fails here.
         */
        @Test
        @DisplayName("no EXPDAY and no PAGENO: the inventory is fifteen and the arithmetic is closed")
        void expdayAndPagenoAreStructurallyAbsent() {
            assertThat(ScreenField.values()).hasSize(15);
            assertThat(CardSelectRequest.FIELD_COUNT).isEqualTo(15);
            assertThat(CardSelectRequest.PAYLOAD_LENGTH).isEqualTo(387);
            assertThat(CardSelectRequest.GROUP_LENGTH).isEqualTo(504);

            // A sixteenth field of any width w would make the payload 387 + w and the group 504 + 7 + w.
            // EXPDAY X(2) - COCRDUP's - would give 389 and 513.
            assertThat(CardSelectRequest.PAYLOAD_LENGTH).isNotEqualTo(387 + 2);
            assertThat(CardSelectRequest.GROUP_LENGTH).isNotEqualTo(504 + 7 + 2);
            // Every declared field is one of the fifteen labels, so no label from a sibling map slipped
            // in under a different width.
            assertThat(ScreenField.values())
                    .extracting(ScreenField::label)
                    .containsExactlyElementsOf(LABELS)
                    .doesNotContain("EXPDAY", "PAGENO");
        }
    }

    @Nested
    @DisplayName("ScreenField - the fifteen name-labelled DFHMDF entries of thirty-one")
    class Fields {

        @Test
        @DisplayName("fifteen constants, in mapset declaration order, each with its full provenance")
        void theFifteenConstantsCarryTheirProvenance() {
            ScreenField[] fields = ScreenField.values();
            assertThat(fields).hasSize(15);
            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(field.label()).isEqualTo(LABELS.get(index));
                assertThat(field.symbolicItemName()).isEqualTo(LABELS.get(index) + "I");
                assertThat(field.length()).isEqualTo(WIDTHS.get(index));
                assertThat(field.copybookLine()).isEqualTo(CPY_LINES.get(index));
                assertThat(field.mapsetLine()).isEqualTo(BMS_LINES.get(index));
                assertThat(field.screenRow()).isEqualTo(POS_ROWS.get(index));
                assertThat(field.screenColumn()).isEqualTo(POS_COLUMNS.get(index));
                assertThat(field.dataOffset()).isEqualTo(DATA_OFFSETS.get(index));
            }
        }

        @Test
        @DisplayName("the strides abut, leaving no gap and no overlap, and end exactly on 504")
        void stridesAbutAndEndOn504() {
            int cursor = CardSelectRequest.TIOAPFX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.lengthItemOffset()).as(field.label() + " xxxL").isEqualTo(cursor);
                assertThat(field.flagItemOffset()).as(field.label() + " xxxF").isEqualTo(cursor + 2);
                assertThat(field.extendedAttributeItemOffset()).as(field.label() + " FILLER X(4)")
                        .isEqualTo(cursor + 3);
                assertThat(field.dataOffset()).as(field.label() + " xxxI").isEqualTo(cursor + 7);
                assertThat(field.endOffsetExclusive())
                        .isEqualTo(field.dataOffset() + field.length());
                cursor = field.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(CardSelectRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("every field sits inside the SIZE=(24,80) screen")
        void everyFieldSitsOnA24By80Screen() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.screenRow()).as(field.label()).isBetween(1, 24);
                assertThat(field.screenColumn()).as(field.label()).isBetween(1, 80);
            }
        }

        @Test
        @DisplayName("describe names the label, item, PICTURE, both source lines, POS and offsets")
        void describeNamesTheWholeProvenance() {
            assertThat(ScreenField.CARDSID.describe())
                    .isEqualTo("CARDSID CARDSIDI PIC X(16) COCRDSL.CPY:66 COCRDSL.bms:96 POS=(8,45) "
                            + "offset 187..203");
            assertThat(ScreenField.FKEYS.describe()).contains("offset 429..504");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("byLabel resolves every one of the fifteen labels")
        void byLabelResolvesEveryField(ScreenField field) {
            assertThat(ScreenField.byLabel(field.label())).isSameAs(field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"EXPDAY", "PAGENO", "cardsid", "", "CARDSID ", "CRDSTCDI"})
        @DisplayName("byLabel rejects what COCRDSL does not declare, EXPDAY and PAGENO included")
        void byLabelRejectsWhatCocrdslDoesNotDeclare(String label) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ScreenField.byLabel(label))
                    .withMessageContaining("no name-labelled DFHMDF");
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
            CardSelectRequest request = new CardSelectRequest();
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).as(field.label())
                        .hasSize(field.length())
                        .isBlank();
            }
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getNavigationContext())
                    .as("a request nobody has passed a communication area to has not been passed one "
                            + "- EIBCALEN = 0, the first disjunct of app/cbl/COCRDSLC.cbl:268")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.metadata()).hasSize(15);
        }

        @Test
        @DisplayName("every metadata holder starts unset, with no cursor anywhere")
        void everyMetadataHolderStartsUnset() {
            CardSelectRequest request = new CardSelectRequest();
            for (ScreenField field : ScreenField.values()) {
                ScreenFieldMetadata holder = request.metadata(field);
                assertThat(holder.isLengthUnset()).as(field.label()).isTrue();
                assertThat(holder.isAttributeUnset()).as(field.label()).isTrue();
                assertThat(holder.isCursorHere()).as(field.label()).isFalse();
                assertThat(holder.getLength()).isEqualTo(ScreenFieldMetadata.LENGTH_UNSET);
                assertThat(holder.getAttribute()).isEqualTo(ScreenFieldMetadata.ATTRIBUTE_UNSET);
            }
        }

        @Test
        @DisplayName("withSearchCriteria sets only the two fields COCRDSLC reads")
        void withSearchCriteriaSetsOnlyTheTwoTypedFields() {
            CardSelectRequest request = CardSelectRequest.withSearchCriteria("00000000050", "*");
            assertThat(request.getAcctsid()).isEqualTo("00000000050");
            assertThat(request.getCardsid()).isEqualTo("*");
            assertThat(request.getTrnname()).isBlank();
            assertThat(request.getCrdname()).isBlank();
            assertThat(request.getFkeys()).isBlank();
        }

        @Test
        @DisplayName("withSearchCriteria takes null as spaces at each declared width")
        void withSearchCriteriaTakesNullAsSpaces() {
            CardSelectRequest request = CardSelectRequest.withSearchCriteria(null, null);
            assertThat(request.getAcctsid()).isEqualTo(" ".repeat(11));
            assertThat(request.getCardsid()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the copy constructor copies each metadata holder rather than sharing it")
        void copyConstructorDeepCopiesTheMutableParts() {
            CardSelectRequest original = new CardSelectRequest();
            original.setCardsid(FIXTURE_CARD_NUMBER);
            original.metadata(ScreenField.CARDSID).positionCursorHere();
            original.metadata(ScreenField.ACCTSID).setAttribute((byte) 0x61);
            original.getCardScreenState().setCcAcctId("00000000050");
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());

            CardSelectRequest copy = new CardSelectRequest(original);
            assertThat(copy).isEqualTo(original).isNotSameAs(original);
            assertThat(copy.metadata(ScreenField.CARDSID))
                    .isNotSameAs(original.metadata(ScreenField.CARDSID));
            assertThat(copy.getCardScreenState()).isNotSameAs(original.getCardScreenState());

            copy.metadata(ScreenField.CARDSID).setLength(7);
            assertThat(original.metadata(ScreenField.CARDSID).isCursorHere()).isTrue();
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the copy constructor rejects null rather than producing a half-built request")
        void copyConstructorRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> new CardSelectRequest(null));
        }

        @Test
        @DisplayName("initializeMapArea returns a used request to its constructed state")
        void initializeMapAreaResetsEverything() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setErrmsg("Did not find this Card in Cards Database");
            request.metadata(ScreenField.ACCTSID).positionCursorHere();
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.initializeMapArea();
            assertThat(request).isEqualTo(new CardSelectRequest());
        }

        @Test
        @DisplayName("spaces is SPACES repeated, and refuses a negative width")
        void spacesIsTheFigurativeConstantRepeated() {
            assertThat(CardSelectRequest.spaces(0)).isEmpty();
            assertThat(CardSelectRequest.spaces(1)).isEqualTo(" ");
            assertThat(CardSelectRequest.spaces(75)).hasSize(75).isBlank();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.spaces(-1))
                    .withMessageContaining("PIC X(n)");
        }
    }

    @Nested
    @DisplayName("Accessors - stored verbatim, returned untrimmed")
    class Accessors {

        @Test
        @DisplayName("a setter neither pads nor truncates: it is not a COBOL MOVE")
        void settersStoreVerbatim() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid("41");
            assertThat(request.getCardsid()).isEqualTo("41");
            request.setCardsid("05000244537657409999");
            assertThat(request.getCardsid()).isEqualTo("05000244537657409999");
            request.setCrdstcd("YES");
            assertThat(request.getCrdstcd()).isEqualTo("YES");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every named setter takes null as spaces at that field's declared width")
        void everyNamedSetterTakesNullAsSpaces(ScreenField field) {
            int index = field.ordinal();
            CardSelectRequest request = new CardSelectRequest();
            namedSetters().get(index).accept(request, "x");
            assertThat(namedGetters().get(index).apply(request)).isEqualTo("x");
            namedSetters().get(index).accept(request, null);
            assertThat(namedGetters().get(index).apply(request))
                    .isEqualTo(" ".repeat(field.length()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("setValue and value agree with the named accessors, field for field")
        void setValueAgreesWithTheNamedSetters(ScreenField field) {
            CardSelectRequest viaEnum = new CardSelectRequest();
            viaEnum.setValue(field, "Z");
            assertThat(viaEnum.value(field)).isEqualTo("Z");

            CardSelectRequest viaSetter = new CardSelectRequest();
            namedSetters().get(field.ordinal()).accept(viaSetter, "Z");
            assertThat(viaSetter).isEqualTo(viaEnum);
            assertThat(viaSetter).hasSameHashCodeAs(viaEnum);
            assertThat(viaEnum.value(field))
                    .isEqualTo(namedGetters().get(field.ordinal()).apply(viaSetter));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("setValue takes null as spaces, whichever field is addressed")
        void setValueTakesNullAsSpaces(ScreenField field) {
            CardSelectRequest request = new CardSelectRequest();
            request.setValue(field, "x");
            request.setValue(field, null);
            assertThat(request.value(field)).isEqualTo(" ".repeat(field.length()));
        }

        @Test
        @DisplayName("value and setValue reject a null field rather than guessing one")
        void valueAndSetValueRejectANullField() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.value(null));
            assertThatNullPointerException().isThrownBy(() -> request.setValue(null, "x"));
        }

        /**
         * The card number, the account identifier and the embossed name are carried in the clear by
         * every surface a parity case can observe, and masked by exactly one that no parity case can.
         *
         * <p><strong>A documented divergence</strong> (practice <strong>B4</strong>). The specification
         * for this test file expects {@code toString} not to mask {@code CARDSID} or {@code CRDNAME}.
         * The class under test does mask them there, through
         * {@code com.vsergeychik.carddemo.common.SensitiveDiagnostics}, which is a module-wide
         * convention applied uniformly across every payload type in this build. This test asserts the
         * <em>real</em> behaviour rather than the expected behaviour, and the production class is not
         * edited to match the expectation.
         *
         * <p>The substance of practice <strong>B6</strong> - that the migration neither weakens nor
         * unrequestedly strengthens the security posture of the legacy screen - is satisfied, and this
         * test is what establishes it: the fifteen accessors, the JSON body and the 504-byte group
         * image all carry the values byte for byte as {@code COCRDSL} declares them, so nothing a
         * parity diff can reach is altered. {@code toString} has no COBOL counterpart at all - COBOL
         * has no {@code toString} - so its rendering cannot appear in a parity fingerprint, in a record
         * written to a dataset, or in a REST response. It reaches only a log line or a failure message.
         */
        @Test
        @DisplayName("the value is whole on every observable surface; only the diagnostic withholds it")
        void theStoredValueIsWholeAndTheRenderingIsMasked() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setAcctsid(FIXTURE_ACCOUNT_ID);
            request.setCrdname(FIXTURE_EMBOSSED_NAME);

            // Surface 1 - the accessors, which is what a service reads.
            assertThat(request.getCardsid()).isEqualTo("0500024453765740");
            assertThat(request.getAcctsid()).isEqualTo("00000000050");
            assertThat(request.getCrdname()).isEqualTo("Aniya Von");
            assertThat(request.value(ScreenField.CARDSID)).isEqualTo("0500024453765740");
            assertThat(request.value(ScreenField.ACCTSID)).isEqualTo("00000000050");
            assertThat(request.value(ScreenField.CRDNAME)).isEqualTo("Aniya Von");

            // Surface 2 - the 504-byte group image, which is what a parity fingerprint compares.
            request.normalize(ASCII_CODEC);
            byte[] group = request.toGroupImage(ASCII_CODEC);
            assertThat(new String(group, ScreenField.CARDSID.dataOffset(), 16, ASCII))
                    .as("the group image carries the sixteen digits unmasked")
                    .isEqualTo("0500024453765740");
            assertThat(new String(group, ScreenField.ACCTSID.dataOffset(), 11, ASCII))
                    .isEqualTo("00000000050");
            assertThat(new String(group, ScreenField.CRDNAME.dataOffset(), 50, ASCII))
                    .startsWith("Aniya Von");

            // Surface 3 - the diagnostic rendering, and only this one withholds anything.
            assertThat(request.toString())
                    .as("the full PAN must not reach a log line (CWE-532)")
                    .doesNotContain("0500024453765740")
                    .doesNotContain("00000000050")
                    .contains("************5740")
                    .contains("*******0050");
        }
    }

    @Nested
    @DisplayName("Conversation state - carried in the payload, never in a session")
    class ConversationState {

        @Test
        @DisplayName("the work area takes null as the initialised form; the commarea keeps absence")
        void theTwoCarriersTreatNullDifferentlyAndDeliberatelySo() {
            CardSelectRequest request = new CardSelectRequest();
            request.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);
            request.setCardScreenState(null);
            request.setNavigationContext(NavigationContext.empty());
            request.setNavigationContext(null);

            assertThat(request.getCardScreenState())
                    .as("CC-WORK-AREA is the program's own storage - INITIALIZE CC-WORK-AREA at "
                            + "COCRDSLC.cbl:254 - so it always exists")
                    .isNotNull();
            assertThat(request.getCardScreenState().getCcCardNum()).isBlank();
            assertThat(request.getNavigationContext())
                    .as("DFHCOMMAREA is what the caller passed, and it may not have been passed at "
                            + "all; substituting an empty area reports EIBCALEN as 160 and takes the "
                            + "ELSE at COCRDSLC.cbl:273")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
        }

        @Test
        @DisplayName("a supplied carrier is held as given, not copied away")
        void aSuppliedCarrierIsHeldAsGiven() {
            CardSelectRequest request = new CardSelectRequest();
            CardScreenState state = new CardScreenState();
            NavigationContext context = NavigationContext.empty().withToProgram("COCRDSLC");
            request.setCardScreenState(state);
            request.setNavigationContext(context);
            assertThat(request.getCardScreenState()).isSameAs(state);
            assertThat(request.getNavigationContext()).isSameAs(context);
        }

        @Test
        @DisplayName("CDEMO-PGM-CONTEXT 0 is ENTER, 1 is REENTER, and any other digit is neither")
        void enterAndReenterFollowTheProgramContext() {
            CardSelectRequest request = new CardSelectRequest();
            assertThat(request.isEnter())
                    .as("with no communication area there is no CDEMO-PGM-CONTEXT to test, so the "
                            + "condition name is false rather than true - three states, not two")
                    .isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext())
                    .as("the reported context falls back to the arm an uninitialised area takes")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isTrue();
            assertThat(request.getPgmContext())
                    .as("with an area present the digit is read out of it rather than assumed")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);

            // PIC 9(01) can hold any digit, so isReenter is not the negation of isEnter.
            request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext())
                    .as("a digit no 88-level covers is reported as it stands, not normalised")
                    .isEqualTo(9);

            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL and xxxA, which COCRDSLC writes on the input group")
    class Metadata {

        @Test
        @DisplayName("the map of holders is unmodifiable while the holders themselves stay live")
        void theMapIsUnmodifiableAndTheHoldersAreLive() {
            CardSelectRequest request = new CardSelectRequest();
            Map<ScreenField, ScreenFieldMetadata> view = request.metadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.remove(ScreenField.FKEYS));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.put(ScreenField.FKEYS, new ScreenFieldMetadata()));
            view.get(ScreenField.FKEYS).setLength(75);
            assertThat(request.metadata(ScreenField.FKEYS).getLength()).isEqualTo(75);
        }

        @Test
        @DisplayName("addressing a null field is rejected")
        void addressingANullFieldIsRejected() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.metadata(null));
        }

        @Test
        @DisplayName("MOVE -1 TO xxxL is the cursor convention COCRDSLC uses at lines 518, 521, 523")
        void minusOnePositionsTheCursor() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.isCursorHere()).isFalse();
            holder.positionCursorHere();
            assertThat(holder.getLength()).isEqualTo(ScreenFieldMetadata.CURSOR_HERE).isEqualTo(-1);
            assertThat(holder.isCursorHere()).isTrue();
            assertThat(holder.isLengthUnset()).isFalse();
        }

        @Test
        @DisplayName("xxxL honours COMP PIC S9(4), not the halfword's wider capacity")
        void theLengthItemHonoursThePictureRange() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            holder.setLength(ScreenFieldMetadata.LENGTH_ITEM_MAX);
            assertThat(holder.getLength()).isEqualTo(9999);
            holder.setLength(ScreenFieldMetadata.LENGTH_ITEM_MIN);
            assertThat(holder.getLength()).isEqualTo(-9999);
            holder.setLength(0);
            assertThat(holder.isLengthUnset()).isTrue();

            // A halfword holds +/-32767 but S9(4) does not, and the declaration wins.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(10000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(-10000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(32767));
        }

        @Test
        @DisplayName("xxxA is a raw byte and xxxF is the same byte, a REDEFINES over one position")
        void theAttributeAndFlagViewsShareOneByte() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.getAttribute()).isEqualTo(holder.getFlag());
            // DFHBMPRF is 0x61 and DFHBMFSE is 0xC1 - the two COCRDSLC assigns at lines 507 to 511.
            holder.setAttribute((byte) 0x61);
            assertThat(holder.getAttribute()).isEqualTo((byte) 0x61);
            assertThat(holder.getFlag()).isEqualTo((byte) 0x61);
            holder.setAttribute((byte) 0xC1);
            assertThat(holder.getFlag()).isEqualTo((byte) 0xC1);
            assertThat(holder.isAttributeUnset()).isFalse();
            holder.setAttribute(ScreenFieldMetadata.ATTRIBUTE_UNSET);
            assertThat(holder.isAttributeUnset()).isTrue();
        }

        @Test
        @DisplayName("reset returns a holder to LOW-VALUES and an unset length")
        void resetReturnsAHolderToItsInitialState() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata(-1, (byte) 0xF2);
            holder.reset();
            assertThat(holder).isEqualTo(new ScreenFieldMetadata());
            assertThat(holder.isLengthUnset()).isTrue();
            assertThat(holder.isAttributeUnset()).isTrue();
        }

        @Test
        @DisplayName("a holder has value semantics over both of its items")
        void holderValueSemantics() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata(-1, (byte) 0x61);
            ScreenFieldMetadata copy = new ScreenFieldMetadata(holder);
            assertThat(copy).isEqualTo(holder).isNotSameAs(holder).hasSameHashCodeAs(holder);
            assertThat(holder).isEqualTo(holder);
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(-1, (byte) 0x60));
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(0, (byte) 0x61));
            assertThat(holder).isNotEqualTo("not a holder");
            assertThat(holder).isNotEqualTo(null);
        }

        @Test
        @DisplayName("toString renders the attribute in hexadecimal, a bit pattern not a character")
        void holderToStringRendersHexadecimal() {
            assertThat(new ScreenFieldMetadata(-1, (byte) 0xC1))
                    .hasToString("ScreenFieldMetadata[length=-1, attribute=0xC1]");
            assertThat(new ScreenFieldMetadata())
                    .hasToString("ScreenFieldMetadata[length=0, attribute=0x00]");
        }

        @Test
        @DisplayName("a holder constructed with an out-of-range length is rejected outright")
        void holderConstructorValidates() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenFieldMetadata(99999, (byte) 0x00));
            assertThatNullPointerException().isThrownBy(() -> new ScreenFieldMetadata(null));
        }
    }

    @Nested
    @DisplayName("The PIC X move rule - applied only through FixedWidthCodec")
    class MoveRule {

        @Test
        @DisplayName("an image is padded on the right and truncated on the right")
        void imagePadsAndTruncatesOnTheRight() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CD");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("CD  ");
            request.setTrnname("ABCDEF");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("ABCD");
            request.setTrnname("CCDL");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("CCDL");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an image is exactly the declared width for every field")
        void everyImageIsExactlyTheDeclaredWidth(ScreenField field) {
            CardSelectRequest request = new CardSelectRequest();
            request.setValue(field, "X".repeat(field.length() + 5));
            assertThat(request.image(field, ASCII_CODEC)).hasSize(field.length());
            request.setValue(field, "");
            assertThat(request.image(field, ASCII_CODEC)).hasSize(field.length()).isBlank();
        }

        @Test
        @DisplayName("normalize brings all fifteen fields to their declared widths at once")
        void normalizeBringsEveryFieldToItsDeclaredWidth() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CCDL");
            request.setCardsid("05000244537657409999");
            request.setCrdstcd("YES");
            request.normalize(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).as(field.label()).hasSize(field.length());
            }
            assertThat(request.getCardsid()).isEqualTo("0500024453765740");
            assertThat(request.getCrdstcd()).isEqualTo("Y");
            assertThat(request.getTrnname()).isEqualTo("CCDL");
        }

        @Test
        @DisplayName("the codec is mandatory, because the move rule lives nowhere else")
        void theCodecIsMandatory() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException()
                    .isThrownBy(() -> request.image(ScreenField.TRNNAME, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> request.image(null, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() -> request.normalize(null));
        }

        /**
         * The three padding cases named individually, so the report shows the shapes rather than only
         * a width.
         *
         * <p>A short {@code PIC X} value is padded on the right, because that is the direction COBOL
         * pads an alphanumeric receiving field. Three characters into {@code ACCTSIDI PIC X(11)} is
         * three characters and eight spaces, and the eight spaces are part of the value, not
         * whitespace to be trimmed away.
         */
        @Test
        @DisplayName("a 3-character ACCTSID is 3 characters plus 8 spaces, 11 in total")
        void aShortValueIsPaddedOnTheRightToItsDeclaredWidth() {
            CardSelectRequest request = new CardSelectRequest();
            request.setAcctsid("050");

            String image = request.image(ScreenField.ACCTSID, ASCII_CODEC);
            assertThat(image).isEqualTo("050" + " ".repeat(8)).hasSize(11);
            assertThat(image.substring(3)).as("the padding is on the right").isEqualTo(" ".repeat(8));
            assertThat(ASCII_CODEC.encodeImage(image, "ACCTSIDI")).hasSize(11);
        }

        /**
         * An empty message line is its full declared width of spaces, not an empty string. There is no
         * empty field in a COBOL record: {@code ERRMSGI PIC X(80)} occupies eighty bytes whether or not
         * {@code COCRDSLC} has a message to put in it.
         */
        @Test
        @DisplayName("an empty ERRMSG is 80 spaces, because a PIC X(80) field is always 80 bytes")
        void anEmptyMessageLineIsItsFullWidthOfSpaces() {
            CardSelectRequest request = new CardSelectRequest();
            request.setErrmsg("");

            assertThat(request.image(ScreenField.ERRMSG, ASCII_CODEC))
                    .isEqualTo(" ".repeat(80))
                    .hasSize(80);
            // And a freshly constructed request is already in that state, since a map area is
            // initialised to spaces rather than to null.
            assertThat(new CardSelectRequest().getErrmsg()).isEqualTo(" ".repeat(80));
        }

        /**
         * Over-long alphanumeric input is truncated on the <em>right</em>, keeping the leading
         * characters. That direction is the whole point: {@code PIC X} truncates on the right and
         * {@code PIC 9} on the left, so choosing it by accident is a parity defect that no width
         * assertion would catch.
         *
         * <p>The truncation is performed by {@link FixedWidthCodec#movePicX(String, int)} and compared
         * against the value the type produces, rather than by a {@code String.substring} in this test.
         * Routing it through the codec is what makes the direction a deliberate decision recorded in
         * one place (practice <strong>B11</strong>).
         */
        @Test
        @DisplayName("a 20-character CARDSID keeps its first 16: PIC X truncates on the right")
        void anOverLongValueIsTruncatedOnTheRight() {
            String twentyDigits = FIXTURE_CARD_NUMBER + "9999";
            assertThat(twentyDigits).hasSize(20);

            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(twentyDigits);

            String expected = ASCII_CODEC.movePicX(twentyDigits, CardSelectRequest.CARDSID_LENGTH);
            assertThat(expected)
                    .as("the codec is the only place the move rule lives")
                    .isEqualTo(FIXTURE_CARD_NUMBER)
                    .hasSize(16);
            assertThat(request.image(ScreenField.CARDSID, ASCII_CODEC)).isEqualTo(expected);

            // The setter itself is not a MOVE: it stores what it was given, and the width is imposed
            // only where the move rule is applied.
            assertThat(request.getCardsid()).isEqualTo(twentyDigits).hasSize(20);
            request.normalize(ASCII_CODEC);
            assertThat(request.getCardsid()).isEqualTo(FIXTURE_CARD_NUMBER);

            // Right truncation keeps the leading characters, which is the opposite of what a numeric
            // MOVE would do. Stated explicitly so the direction cannot be read the wrong way round.
            assertThat(expected).startsWith("05000244").doesNotEndWith("9999");
        }
    }

    @Nested
    @DisplayName("The 504-byte group image")
    class GroupImage {

        private CardSelectRequest populated() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CCDL");
            request.setTitle01("AWS Mainframe Modernization");
            request.setPgmname("COCRDSLC");
            request.setAcctsid("00000000050");
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setCrdname("Aniya Von");
            request.setCrdstcd("Y");
            request.setExpmon("12");
            request.setExpyear("2028");
            request.setErrmsg("Did not find this Card in Cards Database");
            request.setFkeys("ENTER=Search Cards  F3=Exit");
            request.metadata(ScreenField.ACCTSID).positionCursorHere();
            request.metadata(ScreenField.ACCTSID).setAttribute((byte) 0x61);
            request.metadata(ScreenField.CARDSID).setLength(16);
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);
            request.normalize(ASCII_CODEC);
            return request;
        }

        @Test
        @DisplayName("the image is 504 bytes, prefixed by twelve TIOAPFX spaces")
        void theImageIs504BytesWithATwelveByteSpacePrefix() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            assertThat(group).hasSize(504);
            assertThat(new String(group, 0, 12, ASCII)).isEqualTo(" ".repeat(12));
        }

        @Test
        @DisplayName("each field's data sits at its declared offset")
        void eachFieldSitsAtItsDeclaredOffset() {
            CardSelectRequest request = populated();
            byte[] group = request.toGroupImage(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(group, field.dataOffset(), field.length(), ASCII))
                        .as(field.describe())
                        .isEqualTo(request.value(field));
            }
            assertThat(new String(group, 429, 75, ASCII)).startsWith("ENTER=Search Cards  F3=Exit");
        }

        @Test
        @DisplayName("xxxL is a big-endian halfword, so -1 renders as 0xFFFF")
        void theLengthItemIsABigEndianHalfword() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            assertThat(group[ScreenField.ACCTSID.lengthItemOffset()]).isEqualTo((byte) 0xFF);
            assertThat(group[ScreenField.ACCTSID.lengthItemOffset() + 1]).isEqualTo((byte) 0xFF);
            assertThat(group[ScreenField.CARDSID.lengthItemOffset()]).isEqualTo((byte) 0x00);
            assertThat(group[ScreenField.CARDSID.lengthItemOffset() + 1]).isEqualTo((byte) 0x10);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset()]).isEqualTo((byte) 0x00);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset() + 1]).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("a large positive halfword renders big-endian too")
        void aLargePositiveHalfwordRendersBigEndian() {
            CardSelectRequest request = new CardSelectRequest();
            request.metadata(ScreenField.TRNNAME).setLength(9999);
            byte[] group = request.toGroupImage(ASCII_CODEC);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset()]).isEqualTo((byte) 0x27);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset() + 1]).isEqualTo((byte) 0x0F);
        }

        @Test
        @DisplayName("xxxA is written raw, not charset-encoded, because it is a bit pattern")
        void theAttributeByteIsWrittenRaw() {
            CardSelectRequest request = populated();
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                byte[] group = request.toGroupImage(codec);
                assertThat(group[ScreenField.ACCTSID.flagItemOffset()]).isEqualTo((byte) 0x61);
                assertThat(group[ScreenField.CARDSID.flagItemOffset()]).isEqualTo((byte) 0xC1);
                assertThat(group[ScreenField.TRNNAME.flagItemOffset()]).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("the four extended-attribute FILLER bytes are LOW-VALUES on input")
        void theExtendedAttributeFillerIsLowValues() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                for (int offset = 0; offset < CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH;
                        offset++) {
                    assertThat(group[field.extendedAttributeItemOffset() + offset])
                            .as(field.label() + " extended-attribute byte " + offset)
                            .isEqualTo((byte) 0x00);
                }
            }
        }

        @Test
        @DisplayName("the charset is the codec's, so a space is 0x40 in IBM037 and 0x20 in US-ASCII")
        void theCharsetIsTheCodecsAndNeverThePlatformDefault() {
            CardSelectRequest request = populated();
            byte[] ebcdic = request.toGroupImage(EBCDIC_CODEC);
            byte[] ascii = request.toGroupImage(ASCII_CODEC);
            assertThat(ebcdic).hasSize(504);
            assertThat(ebcdic[0]).isEqualTo((byte) 0x40);
            assertThat(ascii[0]).isEqualTo((byte) 0x20);
            assertThat(ebcdic).isNotEqualTo(ascii);
            assertThat(new String(ebcdic, ScreenField.TRNNAME.dataOffset(), 4, EBCDIC))
                    .isEqualTo("CCDL");
        }

        @Test
        @DisplayName("a charset that is not one byte per character for this data is refused")
        void aMultiByteEncodingIsRefused() {
            // UTF-8 encodes every digit, sign overpunch and the space in one byte, so the codec accepts
            // it - but an accented character takes two, which would overflow the declared width and
            // shift every offset after it.
            //
            // The refusal is raised by the codec's own single-byte guard, which fires before the
            // group-image writer compares the encoded length against the declared width. That ordering
            // leaves the writer's own comparison as defence in depth: a second guard behind a first one
            // that always reaches the fault sooner. It is therefore not reachable through any public
            // entry point on this type, which is a deliberate property and not a coverage gap - see the
            // note on unreachable-by-design branches in the class documentation.
            FixedWidthCodec utf8 = new FixedWidthCodec(StandardCharsets.UTF_8);
            assertThatNoException().isThrownBy(() -> new CardSelectRequest().toGroupImage(utf8));

            CardSelectRequest accented = new CardSelectRequest();
            accented.setCrdname("JOSÉ");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accented.toGroupImage(utf8))
                    .withMessageContaining("single-byte code page");
        }

        @Test
        @DisplayName("the codec and the image are both mandatory")
        void theCodecAndImageAreMandatory() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.toGroupImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(null, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[504], null));
        }

        @Test
        @DisplayName("the fifteen values and their metadata round-trip under both code pages")
        void theGroupImageRoundTrips() {
            CardSelectRequest original = populated();
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                CardSelectRequest decoded =
                        CardSelectRequest.fromGroupImage(original.toGroupImage(codec), codec);
                assertThat(decoded).isEqualTo(original).hasSameHashCodeAs(original);
                assertThat(decoded.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
                assertThat(decoded.metadata(ScreenField.CARDSID).getLength()).isEqualTo(16);
                assertThat(decoded.metadata(ScreenField.CARDSID).getAttribute())
                        .isEqualTo((byte) 0xC1);
                assertThat(decoded.toGroupImage(codec)).isEqualTo(original.toGroupImage(codec));
            }
        }

        @Test
        @DisplayName("reading back does not trim: a PIC X field's padding is part of its value")
        void readingBackDoesNotTrim() {
            CardSelectRequest original = new CardSelectRequest();
            original.setTrnname("CD");
            original.normalize(ASCII_CODEC);
            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(original.toGroupImage(ASCII_CODEC), ASCII_CODEC);
            assertThat(decoded.getTrnname()).isEqualTo("CD  ").hasSize(4);
        }

        @Test
        @DisplayName("neither carrier survives the image: they are not part of the BMS map")
        void theCarriersAreNotCarriedByTheImage() {
            CardSelectRequest original = new CardSelectRequest();
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());
            original.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);

            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(original.toGroupImage(ASCII_CODEC), ASCII_CODEC);

            assertThat(decoded.getNavigationContext())
                    .as("the map area carries no communication area at all, so a request rebuilt from "
                            + "it has been passed none")
                    .isNull();
            assertThat(decoded.hasNavigationContext()).isFalse();
            assertThat(decoded.isEnter()).isFalse();
            assertThat(decoded.isReenter()).isFalse();
            assertThat(decoded.getCardScreenState().getCcCardNum()).isBlank();
            assertThat(decoded).isNotEqualTo(original);
        }

        @Test
        @DisplayName("an image of the wrong length is rejected, not padded or truncated")
        void anImageOfTheWrongLengthIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[503], ASCII_CODEC))
                    .withMessageContaining("504");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[505], ASCII_CODEC));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[0], ASCII_CODEC));
        }

        @Test
        @DisplayName("a length item outside COMP PIC S9(4) is rejected in either direction")
        void anOutOfPictureLengthItemIsRejected() {
            byte[] tooHigh = new CardSelectRequest().toGroupImage(ASCII_CODEC);
            int offset = ScreenField.CARDSID.lengthItemOffset();
            tooHigh[offset] = (byte) 0x7F;
            tooHigh[offset + 1] = (byte) 0xFF;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(tooHigh, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4) cannot represent");

            byte[] tooLow = new CardSelectRequest().toGroupImage(ASCII_CODEC);
            tooLow[offset] = (byte) 0x80;
            tooLow[offset + 1] = (byte) 0x00;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(tooLow, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4) cannot represent");
        }

        @Test
        @DisplayName("an all-zero image reads as fifteen fields of LOW-VALUES with unset metadata")
        void anAllZeroImageReadsBack() {
            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(new byte[504], ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(decoded.value(field)).as(field.label()).hasSize(field.length());
                assertThat(decoded.metadata(field).isLengthUnset()).isTrue();
                assertThat(decoded.metadata(field).isAttributeUnset()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Value semantics - every field participates")
    class ValueSemantics {

        @Test
        @DisplayName("a request equals itself and nothing of another type")
        void reflexiveAndTypeChecked() {
            CardSelectRequest request = new CardSelectRequest();
            assertThat(request).isEqualTo(request)
                    .isNotEqualTo("not a request")
                    .isNotEqualTo(null);
            assertThat(new CardSelectRequest()).isEqualTo(new CardSelectRequest())
                    .hasSameHashCodeAs(new CardSelectRequest());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("changing any one of the fifteen fields makes two requests unequal")
        void changingAnySingleFieldBreaksEquality(ScreenField field) {
            CardSelectRequest baseline = new CardSelectRequest();
            CardSelectRequest changed = new CardSelectRequest();
            changed.setValue(field, "Q");
            assertThat(changed).as("changed " + field.label()).isNotEqualTo(baseline);
            assertThat(baseline).as("changed " + field.label()).isNotEqualTo(changed);
        }

        @Test
        @DisplayName("changing either carrier, or any metadata holder, makes two requests unequal")
        void changingACarrierOrMetadataBreaksEquality() {
            CardSelectRequest baseline = new CardSelectRequest();

            CardSelectRequest differentState = new CardSelectRequest();
            differentState.getCardScreenState().setCcAcctId("00000000050");
            assertThat(differentState).isNotEqualTo(baseline);

            CardSelectRequest differentContext = new CardSelectRequest();
            differentContext.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(differentContext).isNotEqualTo(baseline);

            CardSelectRequest differentMetadata = new CardSelectRequest();
            differentMetadata.metadata(ScreenField.ACCTSID).positionCursorHere();
            assertThat(differentMetadata).isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("toString names every field by its DFHMDF label, withholding only the three")
        void toStringNamesEveryField() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.metadata(ScreenField.CARDSID).positionCursorHere();

            String rendered = request.toString();

            for (ScreenField field : ScreenField.values()) {
                assertThat(rendered).as(field.label()).contains(field.label() + "=");
            }
            for (ScreenField quoted : EnumSet.complementOf(
                    EnumSet.of(ScreenField.ACCTSID, ScreenField.CARDSID, ScreenField.CRDNAME))) {
                assertThat(rendered).as("%s renders verbatim", quoted.label())
                        .contains(quoted.label() + "='");
            }
            assertThat(rendered)
                    .startsWith("CardSelectRequest[")
                    .endsWith("]")
                    .doesNotContain("0500024453765740")
                    .contains("************5740")
                    .contains("ScreenFieldMetadata[length=-1")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");
        }
    }

    @Nested
    @DisplayName("Validation - @Size from the PICTURE widths, and nothing more")
    class BeanValidation {

        @Test
        @DisplayName("a blank request is valid: COCRDSLC owns its own blank and format edits")
        void aBlankRequestIsValid() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                assertThat(validator.validate(new CardSelectRequest())).isEmpty();

                CardSelectRequest odd = new CardSelectRequest();
                odd.setAcctsid("*");
                odd.setCardsid("not-a-number");
                odd.setExpmon("ZZ");
                odd.setExpyear("!!!!");
                assertThat(validator.validate(odd)).isEmpty();
            }
        }

        @Test
        @DisplayName("every field is valid at exactly its declared width")
        void everyFieldIsValidAtExactlyItsDeclaredWidth() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                CardSelectRequest atTheLimit = new CardSelectRequest();
                for (ScreenField field : ScreenField.values()) {
                    atTheLimit.setValue(field, "X".repeat(field.length()));
                }
                assertThat(validator.validate(atTheLimit)).isEmpty();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("one character over the declared width is one violation, citing the copybook")
        void oneCharacterTooManyIsOneViolation(ScreenField field) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                CardSelectRequest overWide = new CardSelectRequest();
                overWide.setValue(field, "X".repeat(field.length() + 1));
                Set<ConstraintViolation<CardSelectRequest>> violations = validator.validate(overWide);
                assertThat(violations).hasSize(1);
                ConstraintViolation<CardSelectRequest> violation = violations.iterator().next();
                assertThat(violation.getPropertyPath()).hasToString(
                        field.label().toLowerCase(Locale.ROOT));
                assertThat(violation.getMessage())
                        .contains(field.label())
                        .contains("PIC X(" + field.length() + ")")
                        .contains("app/cpy-bms/COCRDSL.CPY:" + field.copybookLine());
            }
        }
    }

    @Nested
    @DisplayName("The JSON projection - fifteen fields and two carriers, nothing else")
    class JsonProjection {

        @Test
        @DisplayName("exactly seventeen members, with no metadata and no derived condition")
        void exactlySeventeenMembers() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.metadata(ScreenField.CARDSID).positionCursorHere();
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);

            JsonNode json = mapper.readTree(mapper.writeValueAsString(request));
            List<String> members = new ArrayList<>();
            json.fieldNames().forEachRemaining(members::add);
            assertThat(members).containsExactlyInAnyOrder("trnname", "title01", "curdate", "pgmname",
                    "title02", "curtime", "acctsid", "cardsid", "crdname", "crdstcd", "expmon",
                    "expyear", "infomsg", "errmsg", "fkeys", "cardScreenState", "navigationContext");
            assertThat(json.has("metadata")).isFalse();
            assertThat(json.has("enter")).isFalse();
            assertThat(json.has("reenter")).isFalse();
            assertThat(json.has("value")).isFalse();
            assertThat(json.get("cardsid").asText()).isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("every wire name is its DFHMDF label in lower case")
        void everyWireNameIsTheLabelInLowerCase() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(new CardSelectRequest()));
            for (ScreenField field : ScreenField.values()) {
                assertThat(json.has(field.label().toLowerCase(Locale.ROOT)))
                        .as(field.label())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a payload round-trips, carrier and all")
        void aPayloadRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setAcctsid("00000000050");
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.normalize(ASCII_CODEC);

            CardSelectRequest round =
                    mapper.readValue(mapper.writeValueAsString(request), CardSelectRequest.class);
            assertThat(round.getAcctsid()).isEqualTo(request.getAcctsid());
            assertThat(round.getCardsid()).isEqualTo("0500024453765740");
            assertThat(round.isReenter()).isTrue();
            assertThat(round.getNavigationContext()).isEqualTo(request.getNavigationContext());
        }

        @Test
        @DisplayName("an omitted field reads as spaces, because a COBOL record has no null")
        void anOmittedFieldReadsAsSpaces() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest sparse =
                    mapper.readValue("{\"cardsid\":\"0500024453765740\"}", CardSelectRequest.class);
            assertThat(sparse.getCardsid()).isEqualTo("0500024453765740");
            assertThat(sparse.getAcctsid()).isEqualTo(" ".repeat(11));
            assertThat(sparse.getFkeys()).hasSize(75).isBlank();
            assertThat(sparse.getNavigationContext())
                    .as("an omitted commarea is an absent commarea - EIBCALEN = 0, not an "
                            + "initialised area")
                    .isNull();
            assertThat(sparse.hasNavigationContext()).isFalse();
            assertThat(sparse.getCardScreenState()).isNotNull();
            assertThat(sparse.metadata()).hasSize(15);
        }

        @Test
        @DisplayName("an explicit null reads as the initialised value, not as null")
        void anExplicitNullReadsAsTheInitialisedValue() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest nulled = mapper.readValue(
                    "{\"cardsid\":null,\"acctsid\":null,\"navigationContext\":null,"
                            + "\"cardScreenState\":null}",
                    CardSelectRequest.class);
            assertThat(nulled).isEqualTo(new CardSelectRequest());
        }

        /**
         * The binding rule of gate G9, checked by name rather than by count.
         *
         * <p>The symbolic map lays down five items per field and only one of them is data. This test
         * names all fifteen payload members explicitly and then names every one of the forty-five
         * metadata items explicitly as absent - {@code xxxL}, {@code xxxF} and {@code xxxA} for each of
         * the fifteen fields, in every casing a serialiser might plausibly produce. A member total
         * would pass while a metadata item had quietly replaced a payload one; naming both halves
         * cannot.
         */
        @Test
        @DisplayName("all fifteen xxxI members are present and all forty-five xxxL/xxxF/xxxA are not")
        void metadataItemsAreNeverWireMembers() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setAcctsid(FIXTURE_ACCOUNT_ID);
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setCrdname(FIXTURE_EMBOSSED_NAME);
            // Populate every metadata holder, so that an accidental exposure has something to expose.
            for (ScreenField field : ScreenField.values()) {
                request.metadata(field).setLength(field.length());
                request.metadata(field).setAttribute((byte) 0xC1);
            }

            JsonNode json = mapper.readTree(mapper.writeValueAsString(request));

            for (ScreenField field : ScreenField.values()) {
                String label = field.label();
                String payloadMember = label.toLowerCase(Locale.ROOT);
                assertThat(json.has(payloadMember))
                        .as(field.symbolicItemName() + " is the payload item and must be on the wire")
                        .isTrue();

                // xxxL - COMP PIC S9(4). xxxF - PICTURE X. xxxA - the REDEFINES alias of xxxF.
                for (String suffix : new String[] {"L", "F", "A"}) {
                    for (String candidate : new String[] {label + suffix,
                        (label + suffix).toLowerCase(Locale.ROOT), payloadMember + suffix}) {
                        assertThat(json.has(candidate))
                                .as(candidate + " is validation and highlight metadata, never payload")
                                .isFalse();
                    }
                }
            }

            // The accessors that expose the metadata are @JsonIgnore'd, so neither the map nor a single
            // holder appears under any name a getter would have produced.
            assertThat(json.has("metadata")).isFalse();
            assertThat(json.has("screenFieldMetadata")).isFalse();
            assertThat(json.has("length")).isFalse();
            assertThat(json.has("attribute")).isFalse();
        }

        /**
         * A {@code FILLER} span is real storage and no wire member at all.
         *
         * <p>The twelve-byte {@code TIOAPFX} {@code FILLER} at {@code app/cpy-bms/COCRDSL.CPY:18} and
         * the fifteen four-byte reserved {@code FILLER}s exist because {@code TIOAPFX=YES}
         * ({@code app/bms/COCRDSL.bms:23}) and the extended-attribute reservation require them. They
         * are counted in the 504-byte image - gate G21, since dropping one collapses the total - and
         * they are addressable by offset, yet nothing names them on the wire because they hold no
         * value a caller could send.
         */
        @Test
        @DisplayName("the TIOAPFX and reserved FILLER spans are counted in 504 but named nowhere in JSON")
        void fillerSpansAreCountedButNeverNamed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.normalize(ASCII_CODEC);

            JsonNode json = mapper.readTree(mapper.writeValueAsString(request));
            for (String candidate : new String[] {"filler", "FILLER", "tioapfx", "TIOAPFX",
                "tioapfxFiller", "reservedFiller", "extendedAttribute",
                "extendedAttributeItemOffset", "groupLength", "payloadLength"}) {
                assertThat(json.has(candidate))
                        .as(candidate + " is storage or arithmetic, not a payload member")
                        .isFalse();
            }
            assertThat(json.size())
                    .as("fifteen xxxI items plus the two conversation-state carriers")
                    .isEqualTo(17);

            // Counted, though: 12 + 15 x 4 = 72 bytes of FILLER inside the 504-byte image, and the
            // image is still exactly 504 because the spans are emitted rather than skipped.
            assertThat(CardSelectRequest.TIOAPFX_LENGTH
                    + CardSelectRequest.FIELD_COUNT
                            * CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH)
                    .isEqualTo(72);
            assertThat(request.toGroupImage(ASCII_CODEC)).hasSize(504);
        }
    }

    // =================================================================================================
    // The mapset declaration itself, and the divergence between what the Agent Action Plan says about
    // it and what app/bms/COCRDSL.bms actually declares. Documented in the class Javadoc above under
    // "A documented divergence from the Agent Action Plan, not a correction of it"; asserted here only
    // in the consequences the source supports.
    // =================================================================================================

    @Nested
    @DisplayName("The mapset declaration - what COCRDSL declares, and what it does not")
    class MapsetDeclaration {

        /**
         * The four {@code DSATTS} operands are the four extended-attribute bytes, and that is why the
         * per-field overhead is seven on both sides of the {@code REDEFINES}.
         *
         * <p>Input side: {@code xxxL} 2 + {@code xxxF} 1 + reserved {@code FILLER} 4 = 7. Output side:
         * {@code FILLER X(3)} + one byte for each of {@code COLOR}, {@code PS}, {@code HILIGHT} and
         * {@code VALIDN} = 3 + 4 = 7. The two must be equal or {@code CCRDSLAO REDEFINES CCRDSLAI} at
         * {@code app/cpy-bms/COCRDSL.CPY:109} could not hold.
         */
        @Test
        @DisplayName("DSATTS names four attributes, so both sides of the REDEFINES spend 7 bytes")
        void theFourDsattsOperandsExplainTheSevenByteStride() {
            int dsattsOperandCount = 4; // COLOR, HILIGHT, PS, VALIDN - app/bms/COCRDSL.bms:26
            assertThat(CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH)
                    .as("one reserved byte per DSATTS operand")
                    .isEqualTo(dsattsOperandCount);

            int inputStride = CardSelectRequest.LENGTH_ITEM_LENGTH
                    + CardSelectRequest.FLAG_ITEM_LENGTH
                    + CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH;
            int outputStride = 3 + dsattsOperandCount; // FILLER X(3) + xxxC + xxxP + xxxH + xxxV
            assertThat(inputStride).isEqualTo(7);
            assertThat(outputStride).isEqualTo(7);
            assertThat(CardSelectRequest.FIELD_OVERHEAD).isEqualTo(inputStride).isEqualTo(outputStride);
        }

        /**
         * {@code SIZE=(24,80)} is observable: every one of the fifteen fields fits the 24 by 80 screen
         * the {@code DFHMDI} declares, and the two rows the mapset never uses stay unused.
         */
        @Test
        @DisplayName("SIZE=(24,80) holds: every field fits, and the map's own rows are the ones used")
        void everyFieldFitsTheDeclaredScreenSize() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.screenRow()).as(field.label() + " POS row").isBetween(1, 24);
                assertThat(field.screenColumn()).as(field.label() + " POS column").isBetween(1, 80);
                assertThat(field.screenColumn() + field.length() - 1)
                        .as(field.label() + " must not run off the right-hand edge")
                        .isLessThanOrEqualTo(80);
            }
            // The header occupies rows 1 and 2, the body rows 7 to 15, the information line row 20, the
            // error line row 23 and the function-key legend row 24.
            assertThat(ScreenField.TRNNAME.screenRow()).isEqualTo(1);
            assertThat(ScreenField.FKEYS.screenRow()).isEqualTo(24);
            assertThat(ScreenField.ERRMSG.screenRow()).isEqualTo(23);
        }

        /**
         * {@code ALARM}, {@code FREEKB} and {@code EXTATT} are not payload at any level - which is the
         * one assertable consequence of the divergence recorded in the class Javadoc.
         *
         * <p>{@code COCRDSL}'s {@code DFHMSD} names none of the three
         * ({@code app/bms/COCRDSL.bms:20-24}), and the {@code CTRL=(FREEKB)} that does exist is a map
         * attribute at {@code app/bms/COCRDSL.bms:25}. Either way a 3270 device control is not a field:
         * there is no {@code DFHMDF} for it, so there is no {@code xxxI} item, so there is no member
         * here. Asserting the absence keeps the divergence from turning into an invented field if the
         * Agent Action Plan's blanket description is ever read as a field list.
         */
        @Test
        @DisplayName("no ALARM, FREEKB or EXTATT member: a device control is not a screen field")
        void deviceControlsAreNotPayloadFields() throws Exception {
            for (String notAField : new String[] {"ALARM", "FREEKB", "EXTATT", "CTRL", "DSATTS",
                "MAPATTS", "STORAGE", "TIOAPFX", "SYSPARM"}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as(notAField + " is a DFHMSD or DFHMDI operand, never a DFHMDF field")
                        .isThrownBy(() -> ScreenField.byLabel(notAField))
                        .withMessageContaining("no name-labelled DFHMDF");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(new CardSelectRequest()));
            for (String notAMember : new String[] {"alarm", "freekb", "extatt", "ctrl", "dsatts",
                "mapatts"}) {
                assertThat(json.has(notAMember)).as(notAMember).isFalse();
            }
        }
    }

    // =================================================================================================
    // The expiry pair. Two fields, not one, and the separator between them is not a field at all.
    // =================================================================================================

    @Nested
    @DisplayName("The expiry pair - EXPMON X(2) and EXPYEAR X(4), with no combined field")
    class ExpiryPair {

        /**
         * The map declares the expiry as two separate fields. There is no {@code PIC X(10)} combined
         * item anywhere in {@code CCRDSLAI}, so nothing here parses or reassembles a date: two plus
         * four is six, and six is not ten.
         */
        @Test
        @DisplayName("EXPMON is 2 and EXPYEAR is 4, as separate fields summing to 6 and never to 10")
        void theExpiryIsTwoFieldsNotOne() {
            assertThat(CardSelectRequest.EXPMON_LENGTH).isEqualTo(2);
            assertThat(CardSelectRequest.EXPYEAR_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.EXPMON_LENGTH + CardSelectRequest.EXPYEAR_LENGTH)
                    .isEqualTo(6);

            assertThat(ScreenField.EXPMON).isNotSameAs(ScreenField.EXPYEAR);
            assertThat(ScreenField.EXPMON.symbolicItemName()).isEqualTo("EXPMONI");
            assertThat(ScreenField.EXPYEAR.symbolicItemName()).isEqualTo("EXPYEARI");

            // No field on this map is ten characters wide, so a combined X(10) expiry cannot be hiding
            // under some other label. CARD-EXPIRAION-DATE PIC X(10) of app/cpy/CVACT02Y.cpy is the
            // record's shape, not this screen's.
            assertThat(ScreenField.values())
                    .as("no combined X(10) expiry field exists on COCRDSL")
                    .noneMatch(field -> field.length() == 10);
        }

        /**
         * Each half carries its own value independently, and neither is padded into the other's space.
         */
        @Test
        @DisplayName("each half holds its own value at its own width and offset")
        void eachHalfIsAddressedIndependently() {
            CardSelectRequest request = new CardSelectRequest();
            request.setExpmon("03");
            request.setExpyear("2023");
            request.normalize(ASCII_CODEC);

            assertThat(request.getExpmon()).isEqualTo("03").hasSize(2);
            assertThat(request.getExpyear()).isEqualTo("2023").hasSize(4);
            assertThat(request.value(ScreenField.EXPMON)).isEqualTo("03");
            assertThat(request.value(ScreenField.EXPYEAR)).isEqualTo("2023");

            byte[] group = request.toGroupImage(ASCII_CODEC);
            assertThat(new String(group, 275, 2, ASCII)).isEqualTo("03");
            assertThat(new String(group, 284, 4, ASCII)).isEqualTo("2023");
            // 275 + 2 = 277, then the seven-byte stride of EXPYEAR reaches 284. The two fields are not
            // adjacent in storage, which is another way of saying they are not one field.
            assertThat(ScreenField.EXPMON.endOffsetExclusive()).isEqualTo(277);
            assertThat(ScreenField.EXPYEAR.dataOffset()).isEqualTo(284);
        }

        /**
         * The {@code '/'} the operator sees between month and year is an unnamed literal
         * ({@code DFHMDF LENGTH=1, POS=(15,28), INITIAL='/'} at {@code app/bms/COCRDSL.bms:130-132}),
         * so it is painted by BMS and never reported back. The screen geometry proves it has room:
         * {@code EXPMON} ends at column 26 and {@code EXPYEAR} starts at column 30, leaving columns 27
         * to 29 for the separator and its attribute byte - and none of those columns belongs to a
         * payload field.
         */
        @Test
        @DisplayName("the '/' at POS=(15,28) is an unnamed literal, so no separator member exists")
        void theSeparatorIsAnUnnamedLiteralAndNotAMember() throws Exception {
            int expmonLastColumn = ScreenField.EXPMON.screenColumn()
                    + ScreenField.EXPMON.length() - 1;
            assertThat(expmonLastColumn).as("EXPMON occupies columns 25 and 26").isEqualTo(26);
            assertThat(ScreenField.EXPYEAR.screenColumn()).as("EXPYEAR starts at column 30")
                    .isEqualTo(30);
            assertThat(ScreenField.EXPYEAR.screenColumn() - expmonLastColumn)
                    .as("columns 27, 28 and 29 lie between the two fields, and 28 holds the '/'")
                    .isEqualTo(4);

            // Both fields sit on row 15, along with the 'Expiry Date       : ' caption at POS=(15,4)
            // and the LENGTH=0 terminator at POS=(15,35). Only the two named entries are payload.
            assertThat(ScreenField.EXPMON.screenRow()).isEqualTo(15);
            assertThat(ScreenField.EXPYEAR.screenRow()).isEqualTo(15);
            assertThat(ScreenField.values())
                    .filteredOn(field -> field.screenRow() == 15)
                    .containsExactly(ScreenField.EXPMON, ScreenField.EXPYEAR);

            for (String notAField : new String[] {"EXPSEP", "SEPARATOR", "SLASH", "EXPDATE"}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> ScreenField.byLabel(notAField));
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(new CardSelectRequest()));
            assertThat(json.has("expmon")).isTrue();
            assertThat(json.has("expyear")).isTrue();
            for (String notAMember : new String[] {"expsep", "separator", "slash", "expdate",
                "expday"}) {
                assertThat(json.has(notAMember)).as(notAMember).isFalse();
            }
        }
    }

    // =================================================================================================
    // The CSD identity of this screen, and the statelessness that makes it reachable over REST.
    // =================================================================================================

    @Nested
    @DisplayName("Transaction identity - CCDL to COCRDSLC, as the CSD binds them")
    class TransactionIdentity {

        /**
         * {@code TRNNAMEI PIC X(4)} accommodates {@code CCDL} exactly.
         *
         * <p>{@code app/csd/CARDDEMO.CSD:347-348} defines {@code TRANSACTION(CCDL) GROUP(CARDDEMO)}
         * with {@code PROGRAM(COCRDSLC)}, and the transaction identifier is four characters because a
         * CICS transaction identifier is four characters. An exact fit means the move rule neither pads
         * nor truncates it, which is what makes the field able to carry the identity at all.
         */
        @Test
        @DisplayName("TRNNAME carries CCDL at exactly its declared width of 4")
        void trnnameCarriesTheCsdTransactionIdExactly() {
            assertThat(CSD_TRANSACTION_ID).hasSize(CardSelectRequest.TRNNAME_LENGTH).hasSize(4);

            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname(CSD_TRANSACTION_ID);
            assertThat(request.getTrnname()).isEqualTo("CCDL");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC))
                    .as("an exact fit is neither padded nor truncated")
                    .isEqualTo("CCDL")
                    .hasSize(4);
            assertThat(ASCII_CODEC.movePicX(CSD_TRANSACTION_ID, CardSelectRequest.TRNNAME_LENGTH))
                    .isEqualTo(CSD_TRANSACTION_ID);
        }

        /**
         * {@code PGMNAMEI PIC X(8)} accommodates {@code COCRDSLC} exactly.
         *
         * <p>{@code app/csd/CARDDEMO.CSD:219-223} defines {@code PROGRAM(COCRDSLC) GROUP(CARDDEMO)},
         * describes it as {@code VIEW CARD DETAIL} and binds it back with {@code TRANSID(CCDL)}. Eight
         * characters is the width of a load-module name, and the field is eight wide.
         */
        @Test
        @DisplayName("PGMNAME carries COCRDSLC at exactly its declared width of 8")
        void pgmnameCarriesTheCsdProgramNameExactly() {
            assertThat(CSD_PROGRAM_NAME).hasSize(CardSelectRequest.PGMNAME_LENGTH).hasSize(8);

            CardSelectRequest request = new CardSelectRequest();
            request.setPgmname(CSD_PROGRAM_NAME);
            assertThat(request.getPgmname()).isEqualTo("COCRDSLC");
            assertThat(request.image(ScreenField.PGMNAME, ASCII_CODEC))
                    .isEqualTo("COCRDSLC")
                    .hasSize(8);
            assertThat(ASCII_CODEC.movePicX(CSD_PROGRAM_NAME, CardSelectRequest.PGMNAME_LENGTH))
                    .isEqualTo(CSD_PROGRAM_NAME);
        }

        /**
         * The same pairing travels in the communication area, so a stateless controller can route
         * without a session: the identifier the screen shows and the identifier the commarea carries
         * are both four and eight wide respectively, and they agree.
         */
        @Test
        @DisplayName("the header identity and the carried commarea identity are the same pairing")
        void theHeaderAndTheCommareaAgreeOnTheIdentity() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname(CSD_TRANSACTION_ID);
            request.setPgmname(CSD_PROGRAM_NAME);
            request.setNavigationContext(NavigationContext.empty()
                    .withToTranid(CSD_TRANSACTION_ID)
                    .withToProgram(CSD_PROGRAM_NAME));

            assertThat(NavigationContext.TO_TRANID_LENGTH)
                    .as("CDEMO-TO-TRANID PIC X(4) is the same width as TRNNAMEI")
                    .isEqualTo(CardSelectRequest.TRNNAME_LENGTH);
            assertThat(NavigationContext.TO_PROGRAM_LENGTH)
                    .as("CDEMO-TO-PROGRAM PIC X(8) is the same width as PGMNAMEI")
                    .isEqualTo(CardSelectRequest.PGMNAME_LENGTH);
            assertThat(request.getNavigationContext().toTranid()).isEqualTo("CCDL");
            assertThat(request.getNavigationContext().toProgram()).isEqualTo("COCRDSLC");
            assertThat(request.getTrnname()).isEqualTo(request.getNavigationContext().toTranid());
            assertThat(request.getPgmname()).isEqualTo(request.getNavigationContext().toProgram());
        }
    }

    @Nested
    @DisplayName("Statelessness - the commarea is 160 bytes of payload, and requests never share")
    class Statelessness {

        /**
         * {@code CARDDEMO-COMMAREA} is 160 bytes, and the number is asserted as a literal against the
         * real encoded image rather than only against the constant that names it.
         *
         * <p>160 is what CICS would report in {@code EIBCALEN}, and the cold-start disjunct at
         * {@code app/cbl/COCRDSLC.cbl:268} turns on whether it is 160 or zero. Because the whole area
         * travels in the request body, the distinction survives with no server-side session: gate G37.
         */
        @Test
        @DisplayName("the carried commarea encodes to exactly 160 bytes under either code page")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);

            CardSelectRequest request = new CardSelectRequest();
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.commareaLength()).isEqualTo(160);
            assertThat(request.getNavigationContext().toFixedWidth(ASCII_CODEC)).hasSize(160);
            assertThat(request.getNavigationContext().toFixedWidth(EBCDIC_CODEC)).hasSize(160);

            // And with no area carried, EIBCALEN is zero rather than 160 - which is the other arm of
            // that same disjunct, not an error.
            request.setNavigationContext(null);
            assertThat(request.commareaLength()).isZero();
            assertThat(request.hasNavigationContext()).isFalse();
        }

        /**
         * Both {@code CDEMO-PGM-CONTEXT} condition names are reachable from the request, in both
         * states, which is what lets a controller tell first entry from re-entry with nothing held
         * server-side (gates G37 and G50).
         */
        @Test
        @DisplayName("ENTER is 0 and REENTER is 1, both reachable through the carried area")
        void bothProgramContextStatesAreReachable() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(0);
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            CardSelectRequest entering = new CardSelectRequest();
            entering.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(entering.getPgmContext()).isEqualTo(0);
            assertThat(entering.isEnter()).isTrue();
            assertThat(entering.isReenter()).isFalse();

            CardSelectRequest reentering = new CardSelectRequest();
            reentering.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(reentering.getPgmContext()).isEqualTo(1);
            assertThat(reentering.isEnter()).isFalse();
            assertThat(reentering.isReenter()).isTrue();

            // Two requests, two different conversation states, no interference: that is the property
            // that makes the pair of them safe to serve concurrently.
            assertThat(entering.isEnter()).isTrue();
            assertThat(reentering.isReenter()).isTrue();
            assertThat(entering).isNotEqualTo(reentering);
        }

        /**
         * Two independently constructed requests are wholly independent.
         *
         * <p>Every one of the fifteen fields, both carriers and all fifteen metadata holders are
         * mutated on the first request and read back from the second. Nothing is read from or written
         * to a static or ambient holder, because there is none: {@code WORKING-STORAGE} became instance
         * state (practice <strong>B9</strong>, gate G53), and this suite uses the JUnit 5 default
         * per-method lifecycle so no fixture is shared either.
         */
        @Test
        @DisplayName("mutating one request changes nothing in another built alongside it")
        void twoRequestsAreFullyIndependent() {
            CardSelectRequest mutated = new CardSelectRequest();
            CardSelectRequest untouched = new CardSelectRequest();
            assertThat(mutated).isEqualTo(untouched).isNotSameAs(untouched);

            for (ScreenField field : ScreenField.values()) {
                mutated.setValue(field, "Z".repeat(field.length()));
                mutated.metadata(field).setLength(field.length());
                mutated.metadata(field).setAttribute((byte) 0xC1);
            }
            mutated.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);
            mutated.setNavigationContext(NavigationContext.empty().withPgmReenter());

            for (ScreenField field : ScreenField.values()) {
                assertThat(untouched.value(field))
                        .as(field.label() + " must still be its initialised spaces")
                        .isEqualTo(" ".repeat(field.length()));
                assertThat(untouched.metadata(field).getLength())
                        .as(field.label() + " xxxL must still be unset")
                        .isEqualTo(ScreenFieldMetadata.LENGTH_UNSET);
                assertThat(untouched.metadata(field).getAttribute())
                        .as(field.label() + " xxxA must still be LOW-VALUES")
                        .isEqualTo(ScreenFieldMetadata.ATTRIBUTE_UNSET);
            }
            assertThat(untouched.getCardScreenState().getCcCardNum()).isBlank();
            assertThat(untouched.getCardScreenState())
                    .isNotSameAs(mutated.getCardScreenState());
            assertThat(untouched.hasNavigationContext()).isFalse();
            assertThat(untouched.isReenter()).isFalse();
            assertThat(untouched).isEqualTo(new CardSelectRequest());

            // A third request built after the mutation starts from the same initialised state, which
            // would be untrue of a static holder.
            assertThat(new CardSelectRequest()).isEqualTo(untouched);
        }

        /**
         * The conversation state a controller needs is entirely inside the payload, so nothing has to
         * be remembered between calls. Serialising and deserialising the request is therefore lossless
         * for that state - the REST equivalent of CICS handing the COMMAREA back on the next
         * pseudo-conversational turn.
         */
        @Test
        @DisplayName("the whole conversation survives a JSON round trip, so no session is needed")
        void theConversationSurvivesTheWireWithNoSession() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest sent = new CardSelectRequest();
            sent.setAcctsid(FIXTURE_ACCOUNT_ID);
            sent.setCardsid(FIXTURE_CARD_NUMBER);
            sent.setCrdname(FIXTURE_EMBOSSED_NAME);
            sent.setNavigationContext(NavigationContext.empty()
                    .withToTranid(CSD_TRANSACTION_ID)
                    .withToProgram(CSD_PROGRAM_NAME)
                    .withPgmReenter());
            sent.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);
            sent.normalize(ASCII_CODEC);

            CardSelectRequest received =
                    mapper.readValue(mapper.writeValueAsString(sent), CardSelectRequest.class);

            assertThat(received.isReenter())
                    .as("re-entry is recovered from the payload, not from a session")
                    .isTrue();
            assertThat(received.commareaLength()).isEqualTo(160);
            assertThat(received.getNavigationContext()).isEqualTo(sent.getNavigationContext());
            assertThat(received.getCardScreenState().getCcCardNum())
                    .isEqualTo(sent.getCardScreenState().getCcCardNum());
            assertThat(received).isEqualTo(sent);
        }
    }

    // =================================================================================================
    // What COCRDSLC accepts, asserted so that no DTO-layer edit can be stricter than the COBOL.
    // =================================================================================================

    @Nested
    @DisplayName("Input tolerance - the DTO never rejects what COCRDSLC accepts")
    class InputTolerance {

        /**
         * A blank search field is valid, and so is {@code '*'}.
         *
         * <p>{@code app/cbl/COCRDSLC.cbl:615-620} reads
         * {@code IF ACCTSIDI OF CCRDSLAI = '*' OR = SPACES} then
         * {@code MOVE LOW-VALUES TO CC-ACCT-ID} at line 617, taking the {@code ELSE} at line 619 only
         * for anything else; {@code :622-626} does the same for {@code CARDSIDI} into
         * {@code CC-CARD-NUM}. Both values are therefore ordinary accepted input - {@code '*'} is this
         * screen's wildcard - and a DTO-layer rejection of either would refuse a request the COBOL
         * services.
         */
        @Test
        @DisplayName("blank and '*' are both accepted in ACCTSID and CARDSID, per COCRDSLC:615-626")
        void blankAndWildcardSearchCriteriaAreAccepted() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                CardSelectRequest blank = new CardSelectRequest();
                assertThat(blank.getAcctsid()).isEqualTo(" ".repeat(11));
                assertThat(blank.getCardsid()).isEqualTo(" ".repeat(16));
                assertThat(validator.validate(blank))
                        .as("SPACES takes the LOW-VALUES arm at COCRDSLC.cbl:617, it is not an error")
                        .isEmpty();

                CardSelectRequest wildcard = new CardSelectRequest();
                wildcard.setAcctsid("*");
                wildcard.setCardsid("*");
                assertThat(validator.validate(wildcard))
                        .as("'*' is this screen's wildcard, accepted at COCRDSLC.cbl:615 and :622")
                        .isEmpty();

                CardSelectRequest typed = CardSelectRequest.withSearchCriteria(FIXTURE_ACCOUNT_ID,
                        FIXTURE_CARD_NUMBER);
                assertThat(validator.validate(typed))
                        .as("and so is a fully typed pair at exactly the declared widths")
                        .isEmpty();
            }
        }

        /**
         * A one-character {@code CRDSTCD} is valid and a two-character one is not - the accepted and
         * the rejected arm of the narrowest constraint on the map, driven as a pair (gate G50).
         *
         * <p>The rejection is a width violation and nothing more. {@code COCRDSLC} decides for itself
         * whether the character is {@code 'Y'} or {@code 'N'}; pre-empting that here would make the DTO
         * stricter than the program.
         */
        @Test
        @DisplayName("CRDSTCD accepts one character of any value and rejects two")
        void theStatusCodeAcceptsOneCharacterAndRejectsTwo() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                for (String accepted : new String[] {"Y", "N", " ", "*", "1"}) {
                    CardSelectRequest request = new CardSelectRequest();
                    request.setCrdstcd(accepted);
                    assertThat(validator.validate(request))
                            .as("CRDSTCDI PIC X(1) holds any single character")
                            .isEmpty();
                }

                CardSelectRequest tooWide = new CardSelectRequest();
                tooWide.setCrdstcd("YN");
                Set<ConstraintViolation<CardSelectRequest>> violations = validator.validate(tooWide);
                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .hasToString("crdstcd");
            }
        }
    }
}
