package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse.FieldAttributes;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse.ScreenField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CardUpdateResponse} against its sources: {@code app/cpy-bms/COCRDUP.CPY} (the
 * {@code 01 CCRDUPAO} output group at L121), {@code app/bms/COCRDUP.bms} and
 * {@code app/cbl/COCRDUPC.cbl}.
 *
 * <h2>This file's signature responsibility: the {@code FKEYSC} / {@code FKEYSCC} collision</h2>
 *
 * <p>{@link FkeyscCollision} comes first because it is the point of this file. The name
 * {@code FKEYSC} is <em>overloaded</em> in {@code COCRDUP.CPY}. In the output group it is the
 * <strong>one-byte colour item of the field {@code FKEYS}</strong> ({@code COCRDUP.CPY:214}); on the
 * input side it is the name of a genuine <strong>18-byte payload field</strong>
 * ({@code app/bms/COCRDUP.bms:163}, {@code LENGTH=18 POS=(24,23)}) whose input item is
 * {@code FKEYSCI} ({@code COCRDUP.CPY:120}) and whose own colour item is {@code FKEYSCC}
 * ({@code COCRDUP.CPY:220}). Four members, two of them one byte wide:
 *
 * <table>
 *   <caption>The four members that must never fuse</caption>
 *   <tr><th>Member</th><th>Width</th><th>Belongs to</th><th>Declared at</th></tr>
 *   <tr><td>{@code FKEYSO}</td><td>21</td><td>field {@code FKEYS}</td>
 *       <td>{@code COCRDUP.CPY:218}</td></tr>
 *   <tr><td>{@code FKEYSC}</td><td>1</td><td>field {@code FKEYS} (its colour item)</td>
 *       <td>{@code COCRDUP.CPY:214}</td></tr>
 *   <tr><td>{@code FKEYSCO}</td><td>18</td><td>field {@code FKEYSC}</td>
 *       <td>{@code COCRDUP.CPY:224}</td></tr>
 *   <tr><td>{@code FKEYSCC}</td><td>1</td><td>field {@code FKEYSC} (its colour item)</td>
 *       <td>{@code COCRDUP.CPY:220}</td></tr>
 * </table>
 *
 * <p>A mapping that derived a field name by stripping the trailing item letter would fuse the
 * one-byte colour item {@code FKEYSC} with the 18-byte payload field {@code FKEYSC} and silently
 * destroy one of them. That is why every assertion below names its member explicitly rather than
 * walking properties reflectively - a reflective walker is precisely the instrument that would paper
 * the collision over (practice <strong>B11</strong>).
 *
 * <h2>Documented divergence: {@code DFHMSD} versus {@code DFHMDI} on this mapset</h2>
 *
 * <p>Recorded rather than silently corrected (practice <strong>B4</strong>). The Agent Action Plan
 * &sect;0.6.2 states that all 17 mapsets declare
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT STORAGE=AUTO TIOAPFX=YES}. This
 * mapset does not. {@code app/bms/COCRDUP.bms:20-24} declares
 * {@code COCRDUP DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM} - with
 * <strong>no {@code CTRL}, no {@code ALARM} and no {@code EXTATT}</strong>. Those keywords live one
 * level down, on the map: {@code app/bms/COCRDUP.bms:25-28} declares
 * {@code CCRDUPA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 * MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)}.
 *
 * <p>That is not a cosmetic difference. <strong>{@code DSATTS=(COLOR,HILIGHT,PS,VALIDN)} is the
 * derivation of the four attribute bytes this type carries per field</strong>: BMS emits one
 * symbolic-map item per named {@code DSATTS} attribute, in that keyword's own order, which is exactly
 * {@code xxxC} (COLOR), {@code xxxP} (PS), {@code xxxH} (HILIGHT), {@code xxxV} (VALIDN) at
 * {@code COCRDUP.CPY:124-127} and its sixteen repetitions. The quad is four items because
 * {@code DSATTS} names four attributes - a derivation, not a convention - which is why the 7-byte
 * per-field overhead is 3 + 4 and why {@code 01 CCRDUPAO REDEFINES CCRDUPAI} balances against the
 * input side's 2 + 1 + 4.
 *
 * <h2>Provenance of the highlight rule on the card screens</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} has exactly one COBOL consumer, {@code app/cbl/COACTUPC.cbl}, and
 * <strong>no card program copies it</strong>. {@code COCRDUPC} inlines the same rule at twelve
 * {@code DFHRED} sites instead. The truth table in {@link HighlightTruthTable} therefore asserts the
 * <em>bytes</em> that reach {@code xxxC} and {@code xxxO}, never a copybook mechanism.
 *
 * <h2>The unified conversational state</h2>
 *
 * <p>{@link OneAreaOneType}, {@link OneNamePerCarrier} and {@link PairRoundTrip} cover one property
 * that the review found broken: the response and the request are two halves of one CICS
 * conversation, so the state they both carry has to be <em>one</em> thing. This type used to declare
 * its own {@code ProgCommarea}, {@code CcupDetails}, {@code DetailsPrefix} and
 * {@code CardUpdateRecord} against the request's {@link CommArea}, {@link CardDetails},
 * {@link DetailGroup} and {@link CardUpdateRecord}, and published them under the member names
 * {@code progCommarea}, {@code screenState} and {@code navigation} against the request's
 * {@code commArea}, {@code cardScreenState} and {@code navigationContext}. Same 329 bytes, two JSON
 * shapes, so a client could not echo back what it received.
 *
 * <h2>Standards this test holds itself to</h2>
 *
 * <p>{@code review_rules} reports <em>"No user rules provided"</em>, so <strong>no user rule governs
 * this file</strong>; none is invented and none is implied below. The substitute standard is the
 * enterprise best practice codified by the Agent Action Plan &sect;0.10.2, cited by identifier where
 * it bears on a decision: <strong>B1</strong> only what {@code app/java/pom.xml} already declares -
 * JUnit 5 Jupiter, AssertJ and Jackson, with no version literal anywhere; <strong>B3</strong> the
 * three sources above are the contract and are cited by line, never read at run time;
 * <strong>B5</strong> the {@code EXPIRAION} misspellings, the {@code FILLER X(59)} and the collision
 * are preserved and asserted, never tidied; <strong>B6</strong> the card number, the embossed name
 * and the CVV appear verbatim - unmasked, unredacted, untruncated and unhashed - because
 * {@code COCRDUPC} handles them that way and masking would be a behaviour change; <strong>B8</strong>
 * every code page is named explicitly, no import is a wildcard and every attribute byte is compared
 * against a {@link BmsAttributes} constant rather than a hand-written char; <strong>B9</strong> no
 * static mutable state and no state shared between test methods; <strong>B11</strong> explicit
 * literals and explicit named-member checks in place of any parser, walker or reflective
 * deep-equality.
 *
 * <p>Scope: this file tests the <em>type</em>. {@code CardUpdateControllerTest} owns
 * {@code 2000-DECIDE-ACTION} and the field-edit paragraphs; {@code CardUpdateServiceTest} owns
 * {@code 9200-WRITE-PROCESSING} and {@code 9300-CHECK-CHANGE-IN-REC}; {@code CardScreenStateTest}
 * pins {@code LOW-VALUES} against spaces against {@code null} and the {@code REDEFINES} semantics of
 * {@code CVCRD01Y}; {@code CardUpdateRequestTest} pins the 17-field inventory, the exhaustive
 * nine-predicate {@code CCUP-CHANGE-ACTION} sweep and the 329-byte commarea widths from the input
 * side. None of that is duplicated here.
 */
@DisplayName("CardUpdateResponse - CCRDUPAO, 17 named fields of 34, and one shared 329-byte area")
class CardUpdateResponseTest {

    /** The text fixtures' code page. Named explicitly; the platform default is never used. */
    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** The EBCDIC datasets' code page, so every byte assertion is checked in both. */
    private static final Charset IBM037 = Charset.forName("IBM037");

    /** A codec over {@link #IBM037}. */
    private static final FixedWidthCodec EBCDIC = new FixedWidthCodec(IBM037);

    /**
     * The 17 {@code DFHMDF} labels in {@code app/bms/COCRDUP.bms} declaration order.
     *
     * <p>Written out rather than read from the type under test, so a reordering of
     * {@code CardUpdateResponse.FIELDS} is a test failure rather than a silent change of contract.
     */
    private static final List<String> FIELD_NAMES = List.of("TRNNAME", "TITLE01", "CURDATE",
            "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON",
            "EXPYEAR", "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");

    /** The three JSON members that must be spelled identically on the request and the response. */
    private static final List<String> SHARED_CARRIERS =
            List.of("commArea", "cardScreenState", "navigationContext");

    /** The member names this type published before the carriers were unified. */
    private static final List<String> RETIRED_CARRIER_NAMES =
            List.of("progCommarea", "screenState", "navigation");

    /** The nested type names this type declared before the carriers were unified. */
    private static final List<String> RETIRED_NESTED_TYPES =
            List.of("ProgCommarea", "CcupDetails", "DetailsPrefix", "CardUpdateRecord");

    /**
     * A card number that is distinguishable from padding in every assertion below.
     *
     * <p>Carried verbatim. {@code CARD-UPDATE-NUM PIC X(16)} and {@code CARDSIDO PIC X(16)} hold the
     * full sixteen digits and {@code COCRDUPC} neither masks nor truncates them, so neither does this
     * test (practice <strong>B6</strong>).
     */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The {@code CCUP-xxx-CVV-CD PIC X(3)} / {@code CARD-UPDATE-CVV-CD PIC 9(03)} value used below.
     *
     * <p>Deliberately three plain digits, asserted verbatim and never masked: the COBOL compares and
     * rewrites the card verification value in clear at its declared width, and hiding it here would
     * be a behaviour change dressed up as hygiene (practice <strong>B6</strong>).
     */
    private static final String CVV_CODE = "123";

    /**
     * {@code CARD-UPDATE-EMBOSSED-NAME PIC X(50)} / {@code CRDNAMEO PIC X(50)} content, unabbreviated.
     */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /**
     * {@code INITIAL='ENTER=Process F3=Exit'} - {@code app/bms/COCRDUP.bms:162}, the 21-byte
     * {@code FKEYS} literal. Exactly {@code FKEYSO}'s declared width, so the move neither pads nor
     * truncates, and its single interior space is byte-significant (practice <strong>B5</strong>).
     */
    private static final String FKEYS_LITERAL = "ENTER=Process F3=Exit";

    /**
     * {@code INITIAL='F5=Save F12=Cancel'} - {@code app/bms/COCRDUP.bms:167}, the 18-byte
     * {@code FKEYSC} literal. Exactly {@code FKEYSCO}'s declared width.
     */
    private static final String FKEYSC_LITERAL = "F5=Save F12=Cancel";

    /**
     * A fixed clock reading, so {@code CURDATEO} and {@code CURTIMEO} are deterministic.
     *
     * <p>{@code 2022-07-19T23:15:58Z} is the version date the sources carry
     * ({@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19}). {@link Clock#fixed} rather than
     * {@link Clock#systemUTC()}: a clock read from the host would make {@code 3100-SCREEN-INIT}'s two
     * moves unassertable. {@link Clock} is immutable, so this is a constant and not static mutable
     * state (practice <strong>B9</strong>).
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:15:58Z"), ZoneOffset.UTC);

    /** The {@code mm/dd/yy} image {@link #FIXED_CLOCK} produces, {@code CURDATEO}'s 8 bytes. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** The {@code hh:mm:ss} image {@link #FIXED_CLOCK} produces, {@code CURTIMEO}'s 8 bytes. */
    private static final String FIXED_CURTIME = "23:15:58";

    /** Collects the top-level member names of a serialised value, in emission order. */
    private static List<String> membersOf(Object value) {
        ObjectNode node = (ObjectNode) new ObjectMapper().valueToTree(value);
        List<String> members = new ArrayList<>();
        node.fieldNames().forEachRemaining(members::add);
        return members;
    }

    /**
     * A populated {@code CCUP-OLD-DETAILS} or {@code CCUP-NEW-DETAILS} snapshot.
     *
     * <p>{@code CCUP-xxx-CRDNAME PIC X(50)} pads a short sender on the right, so the embossed name is
     * passed unpadded and arrives at its declared 50 characters.
     */
    private static CardDetails details(DetailGroup group, String cardid) {
        return new CardDetails(group, "00000000011", cardid, CVV_CODE, EMBOSSED_NAME, "2026", "07",
                "19", "Y");
    }

    /** A populated {@code WS-THIS-PROGCOMMAREA} with every one of its four groups distinguishable. */
    private static CommArea populatedCommArea() {
        return new CommArea(ChangeAction.changesOkNotConfirmed(),
                details(DetailGroup.OLD, CARD_NUMBER),
                details(DetailGroup.NEW, "4111111111112222"),
                CardUpdateRecord.initialised().withCardUpdateNum(CARD_NUMBER)
                        .withCardUpdateAcctId(11L)
                        .withCardUpdateCvvCd(Integer.parseInt(CVV_CODE)));
    }

    /**
     * The date-and-time header every send-path assertion uses, read from {@link #FIXED_CLOCK}.
     *
     * <p>Built through {@link DateHeader#from(FixedWidthCodec, Clock)} - the injected-clock seam -
     * rather than from an ambient reading, so {@code CURDATEO} and {@code CURTIMEO} are assertable to
     * the byte.
     *
     * @return a header pinned to {@link #FIXED_CLOCK}
     */
    private static DateHeader fixedDateHeader() {
        return DateHeader.from(ASCII, FIXED_CLOCK);
    }

    /**
     * The 17 name-labelled {@code DFHMDF} entries as {@code (label, declared width, BMS POS)} triples,
     * in {@code app/bms/COCRDUP.bms} declaration order.
     *
     * <p>Every value is transcribed from the mapset by hand and written out here in full, so this
     * table is an independent second reading of the source rather than an echo of the type under test
     * (practice <strong>B11</strong>). The {@code POS} column is carried because it is what makes each
     * row traceable to one {@code DFHMDF} for gate <strong>G9</strong>; the widths are the
     * {@code LENGTH=} operands, which the copybook repeats as the {@code xxxO} {@code PICTURE}.
     *
     * @return one argument triple per named field
     */
    static List<Arguments> namedFieldTriples() {
        return List.of(
                Arguments.of("TRNNAME", 4, "(1,7)"),
                Arguments.of("TITLE01", 40, "(1,21)"),
                Arguments.of("CURDATE", 8, "(1,71)"),
                Arguments.of("PGMNAME", 8, "(2,7)"),
                Arguments.of("TITLE02", 40, "(2,21)"),
                Arguments.of("CURTIME", 8, "(2,71)"),
                Arguments.of("ACCTSID", 11, "(7,45)"),
                Arguments.of("CARDSID", 16, "(8,45)"),
                Arguments.of("CRDNAME", 50, "(11,25)"),
                Arguments.of("CRDSTCD", 1, "(13,25)"),
                Arguments.of("EXPMON", 2, "(15,25)"),
                Arguments.of("EXPYEAR", 4, "(15,30)"),
                Arguments.of("EXPDAY", 2, "(15,36)"),
                Arguments.of("INFOMSG", 40, "(20,25)"),
                Arguments.of("ERRMSG", 80, "(23,1)"),
                Arguments.of("FKEYS", 21, "(24,1)"),
                Arguments.of("FKEYSC", 18, "(24,23)"));
    }

    /**
     * The six cells of the highlight truth table:
     * {@code flag} &isin; {OK, NOT-OK, BLANK} &times; {@code context} &isin; {ENTER, REENTER}.
     *
     * <p>Each row carries the two expected outcomes, so the table is readable as the specification it
     * is: the colour item takes {@code DFHRED} when the flag is NOT-OK or BLANK <em>and</em> the
     * program is re-entered, and the payload item takes {@code '*'} only when the flag is BLANK.
     * Exactly one of the six rows expects the asterisk.
     *
     * @return one argument row per cell: state, reenter, colour expected, asterisk expected
     */
    static List<Arguments> highlightTruthTable() {
        return List.of(
                Arguments.of(FieldValidationState.OK, false, false, false),
                Arguments.of(FieldValidationState.NOT_OK, false, false, false),
                Arguments.of(FieldValidationState.BLANK, false, false, false),
                Arguments.of(FieldValidationState.OK, true, false, false),
                Arguments.of(FieldValidationState.NOT_OK, true, true, false),
                Arguments.of(FieldValidationState.BLANK, true, true, true));
    }

    /** A work area with something in every one of the members the response echoes. */
    private static CardScreenState populatedScreenState() {
        CardScreenState state = new CardScreenState();
        state.setCcardAid("PF03 ");
        state.setCcardNextProg("COCRDLIC");
        state.setCcardNextMapset("COCRDLI");
        state.setCcardNextMap("CCRDLIA");
        state.setCcAcctId("00000000011");
        state.setCcCardNum(CARD_NUMBER);
        return state;
    }

    /** A fully populated response, used wherever a non-default instance is needed. */
    private static CardUpdateResponse populatedResponse() {
        CardUpdateResponse response = new CardUpdateResponse();
        response.screenInit(fixedDateHeader());
        response.setAcctsido("00000000011");
        response.setCardsido(CARD_NUMBER);
        response.setCrdnameo(EMBOSSED_NAME);
        response.setCrdstcdo("Y");
        response.setExpmono("07");
        response.setExpyearo("2026");
        response.setExpdayo("19");
        response.setInfomsgo("Enter your changes and press ENTER");
        response.setErrmsgo("Account filter not valid");
        response.setCommArea(populatedCommArea());
        response.setCardScreenState(populatedScreenState());
        response.setNavigationContext(NavigationContext.empty());
        response.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");
        return response;
    }

    // =================================================================================================
    // Section 4.1 - the FKEYSC / FKEYSCC collision. First, because it is the point of this file.
    // =================================================================================================

    /**
     * The one place in this migration where a plausible mapping silently destroys a field.
     *
     * <p><strong>A suffix-stripping mapping would fuse a one-byte colour item with an 18-byte payload
     * field.</strong> Derive a field name by dropping the trailing item letter and
     * {@code FKEYSC PICTURE X} ({@code app/cpy-bms/COCRDUP.CPY:214}, the colour byte of {@code FKEYS})
     * collapses onto the field {@code FKEYSC} ({@code app/bms/COCRDUP.bms:163}, {@code LENGTH=18});
     * one of the two then disappears, and which one disappears depends on iteration order. Eighteen
     * bytes of payload lost, or an attribute byte published as card data.
     *
     * <p>Every assertion in this class is therefore an <strong>explicit named-member check</strong>:
     * each of the four members is named as a literal and its width asserted on its own line. No
     * reflective walk over members, no property enumeration and no generated name list appears
     * anywhere here - a walker is exactly the instrument that would paper the collision over, because
     * it would report one member where the copybook declares two (practice <strong>B11</strong>).
     */
    @Nested
    @DisplayName("FKEYSC / FKEYSCC - four members, two of them one byte wide, and none of them fused")
    class FkeyscCollision {

        @Test
        @DisplayName("all four members exist under their own names: FKEYSO, FKEYSC, FKEYSCO, FKEYSCC")
        void allFourMembersExistUnderTheirOwnNames() {
            // Two FIELDS, not one. app/bms/COCRDUP.bms:158 declares FKEYS and :163 declares FKEYSC,
            // and app/cpy-bms/COCRDUP.CPY gives each its own five-item block in the output group.
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(fkeys.name()).isEqualTo("FKEYS");
            assertThat(fkeysc.name()).isEqualTo("FKEYSC");

            // FKEYS's payload item is FKEYSO (COCRDUP.CPY:218) and its COLOUR item is spelled FKEYSC
            // (COCRDUP.CPY:214) - the one-byte item whose name collides with the other field.
            assertThat(fkeys.outputItemName()).isEqualTo("FKEYSO");
            assertThat(fkeys.colourItemName()).isEqualTo("FKEYSC");

            // FKEYSC's payload item is FKEYSCO (COCRDUP.CPY:224) and its colour item is FKEYSCC
            // (COCRDUP.CPY:220).
            assertThat(fkeysc.outputItemName()).isEqualTo("FKEYSCO");
            assertThat(fkeysc.colourItemName()).isEqualTo("FKEYSCC");

            // Four distinct names. If a suffix-stripping mapping had fused the one-byte colour item
            // with the 18-byte field, two of these four would be the same string.
            assertThat(List.of(fkeys.outputItemName(), fkeys.colourItemName(),
                    fkeysc.outputItemName(), fkeysc.colourItemName()))
                    .containsExactly("FKEYSO", "FKEYSC", "FKEYSCO", "FKEYSCC")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("their widths are 21, 1, 18 and 1 - one assertion each catches any fusion")
        void theirWidthsAreTwentyOneOneEighteenAndOne() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            // FKEYSO PIC X(21) - app/cpy-bms/COCRDUP.CPY:218, app/bms/COCRDUP.bms:160 LENGTH=21.
            assertThat(fkeys.outputItemSpan().length()).isEqualTo(21);
            assertThat(fkeys.length()).isEqualTo(CardUpdateResponse.FKEYSO_LENGTH).isEqualTo(21);

            // FKEYSC PICTURE X - app/cpy-bms/COCRDUP.CPY:214. ONE byte. Not 18.
            assertThat(fkeys.colourItemSpan().length()).isEqualTo(1);
            assertThat(fkeys.colourItemSpan().name()).isEqualTo("FKEYSC");

            // FKEYSCO PIC X(18) - app/cpy-bms/COCRDUP.CPY:224, app/bms/COCRDUP.bms:165 LENGTH=18.
            assertThat(fkeysc.outputItemSpan().length()).isEqualTo(18);
            assertThat(fkeysc.length()).isEqualTo(CardUpdateResponse.FKEYSCO_LENGTH).isEqualTo(18);

            // FKEYSCC PICTURE X - app/cpy-bms/COCRDUP.CPY:220. ONE byte. Not 21.
            assertThat(fkeysc.colourItemSpan().length()).isEqualTo(1);
            assertThat(fkeysc.colourItemSpan().name()).isEqualTo("FKEYSCC");

            // And the two payload widths differ, so no reading of the source can make them one item.
            assertThat(fkeys.length()).isNotEqualTo(fkeysc.length());
        }

        @Test
        @DisplayName("cross-pair 1 of 4: DFHRED into FKEYSC leaves FKEYSCO's 18 bytes untouched")
        void colouringFkeysDoesNotDisturbTheEighteenByteField() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(CardUpdateResponse.FKEYSC, FKEYSC_LITERAL);

            // MOVE DFHRED TO FKEYSC OF CCRDUPAO - the colour byte of FKEYS.
            response.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);

            assertThat(response.attributeImages().get("FKEYSC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            assertThat(response.outputItemOf(CardUpdateResponse.FKEYSC)).isEqualTo(FKEYSC_LITERAL);
        }

        @Test
        @DisplayName("cross-pair 2 of 4: 18 characters into FKEYSCO leave FKEYSC's byte untouched")
        void writingTheEighteenByteFieldDoesNotDisturbFkeysColour() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setFkeysco(FKEYSC_LITERAL);

            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            // FKEYSC, the one-byte colour item of FKEYS, is still the LOW-VALUES byte the group
            // started at - app/cbl/COCRDUPC.cbl:1053 MOVE LOW-VALUES TO CCRDUPAO.
            assertThat(response.colourOf(CardUpdateResponse.FKEYS))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(response.attributeImages().get("FKEYSC"))
                    .isEqualTo(BmsAttributes.toHex((byte) 0x00));
        }

        @Test
        @DisplayName("cross-pair 3 of 4: DFHRED into FKEYSCC touches neither FKEYSC nor FKEYSO")
        void colouringTheEighteenByteFieldTouchesNeitherFkeysItem() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeyso(FKEYS_LITERAL);

            // MOVE DFHRED TO FKEYSCC OF CCRDUPAO - the colour byte of the field FKEYSC.
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);

            assertThat(response.attributeImages().get("FKEYSCC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(response.colourOf(CardUpdateResponse.FKEYS))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(response.getFkeyso()).isEqualTo(FKEYS_LITERAL).hasSize(21);
        }

        @Test
        @DisplayName("cross-pair 4 of 4: 21 characters into FKEYSO leave FKEYSCC and FKEYSCO alone")
        void writingFkeysoLeavesTheEighteenByteFieldAndItsColourAlone() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeysco(FKEYSC_LITERAL);
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHYELLO);

            response.setFkeyso(FKEYS_LITERAL);

            assertThat(response.getFkeyso()).isEqualTo(FKEYS_LITERAL).hasSize(21);
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            assertThat(response.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(BmsAttributes.DFHYELLO);
        }

        @Test
        @DisplayName("the two attribute quads are eight distinct items, four per field")
        void theTwoAttributeQuadsAreEightDistinctItems() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            // FKEYS's quad - app/cpy-bms/COCRDUP.CPY:214-217.
            assertThat(List.of(fkeys.colourItemName(), fkeys.psItemName(), fkeys.hilightItemName(),
                    fkeys.validnItemName()))
                    .containsExactly("FKEYSC", "FKEYSP", "FKEYSH", "FKEYSV");

            // The field FKEYSC's own quad - app/cpy-bms/COCRDUP.CPY:220-223.
            assertThat(List.of(fkeysc.colourItemName(), fkeysc.psItemName(),
                    fkeysc.hilightItemName(), fkeysc.validnItemName()))
                    .containsExactly("FKEYSCC", "FKEYSCP", "FKEYSCH", "FKEYSCV");

            // Eight names, no repetition, and each exactly one byte wide - the DSATTS=(COLOR,HILIGHT,
            // PS,VALIDN) derivation at app/bms/COCRDUP.bms:26.
            assertThat(List.of("FKEYSC", "FKEYSP", "FKEYSH", "FKEYSV", "FKEYSCC", "FKEYSCP",
                    "FKEYSCH", "FKEYSCV")).doesNotHaveDuplicates();
            for (FieldSpan span : List.of(fkeys.colourItemSpan(), fkeys.psItemSpan(),
                    fkeys.hilightItemSpan(), fkeys.validnItemSpan(), fkeysc.colourItemSpan(),
                    fkeysc.psItemSpan(), fkeysc.hilightItemSpan(), fkeysc.validnItemSpan())) {
                assertThat(span.length()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the four quads and the two payloads all sit at different offsets")
        void theSixItemsSitAtSixDifferentOffsets() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            // FKEYS's block ends exactly where FKEYSC's begins: 3 + 4 + 21 then 3 + 4 + 18.
            assertThat(fkeys.endOffsetExclusive()).isEqualTo(fkeysc.fieldOffset());
            assertThat(fkeysc.endOffsetExclusive()).isEqualTo(CardUpdateResponse.GROUP_LENGTH);

            assertThat(List.of(fkeys.colourItemOffset(), fkeys.outputItemOffset(),
                    fkeysc.colourItemOffset(), fkeysc.outputItemOffset()))
                    .doesNotHaveDuplicates();
            // The one-byte colour item of FKEYS is four bytes before FKEYSO, and 25 bytes before
            // FKEYSCC - so an off-by-one in either direction lands in a different item entirely.
            assertThat(fkeys.outputItemOffset() - fkeys.colourItemOffset()).isEqualTo(4);
            assertThat(fkeysc.colourItemOffset() - fkeys.colourItemOffset()).isEqualTo(28);
        }

        @Test
        @DisplayName("the display literals are carried byte for byte, at exactly 21 and 18")
        void theDisplayLiteralsAreCarriedByteForByte() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setFkeyso(FKEYS_LITERAL);
            response.setFkeysco(FKEYSC_LITERAL);

            // app/bms/COCRDUP.bms:162 INITIAL='ENTER=Process F3=Exit' is already 21 characters, so
            // the PIC X move neither pads nor truncates; the interior space is part of the literal.
            assertThat(response.getFkeyso()).isEqualTo("ENTER=Process F3=Exit").hasSize(21);
            // app/bms/COCRDUP.bms:167 INITIAL='F5=Save F12=Cancel' is already 18 characters.
            assertThat(response.getFkeysco()).isEqualTo("F5=Save F12=Cancel").hasSize(18);

            // And the images are keyed by the copybook item names, so a reader can see both.
            assertThat(response.fieldImages())
                    .containsEntry("FKEYSO", "ENTER=Process F3=Exit")
                    .containsEntry("FKEYSCO", "F5=Save F12=Cancel");
        }

        @Test
        @DisplayName("the input side gives FKEYSC a complete quintuple: FKEYSCL/F/A and FKEYSCI X(18)")
        void theInputSideGivesTheFieldItsOwnQuintuple() {
            // app/cpy-bms/COCRDUP.CPY:115-120 - FKEYSCL COMP PIC S9(4), FKEYSCF PICTURE X,
            // FKEYSCA redefining it, FILLER X(4), then FKEYSCI PIC X(18). The 18-byte field is
            // therefore complete on both sides: a quintuple in, a quad plus a display item out.
            assertThat(CardUpdateRequest.declaredLength("FKEYSC")).isEqualTo(18);
            assertThat(CardUpdateRequest.FKEYSC_LENGTH).isEqualTo(18);
            assertThat(CardUpdateRequest.FKEYS_LENGTH).isEqualTo(21);

            CardUpdateRequest request = new CardUpdateRequest();
            CardUpdateRequest.FieldMetadata metadata = request.metadataFor("FKEYSC");

            assertThat(metadata.fieldName()).isEqualTo("FKEYSC");
            // FKEYSCL - the COMP PIC S9(4) length item at COCRDUP.CPY:115.
            assertThat(metadata.declaredLength()).isEqualTo(18);
            // FKEYSCF at :116 and FKEYSCA at :118 are one byte and are the same byte, because
            // FKEYSCA REDEFINES FKEYSCF rather than following it.
            assertThat(metadata.flagItem()).hasSize(1);
            assertThat(metadata.attributeItem()).isEqualTo(metadata.flagItem());
            // FKEYSCI PIC X(18) at :120 - and its sibling FKEYSI PIC X(21) at :114 is a different
            // item of a different width, which is the same collision seen from the input side.
            assertThat(request.getFkeysc()).hasSize(18);
            assertThat(request.getFkeys()).hasSize(21);
        }

        @Test
        @DisplayName("addressing is by DFHMDF label, so 'FKEYSO' and 'FKEYSCC' are not field names")
        void addressingIsByLabelNotByItemName() {
            // An item name is not a field name. This is the guard that makes the collision safe:
            // there is no suffix-stripping step anywhere, so no item name can ever resolve to a
            // field, and in particular the one-byte 'FKEYSC' item cannot resolve to the 18-byte
            // field by accident - it resolves because 'FKEYSC' IS also a declared DFHMDF label.
            assertThat(CardUpdateResponse.declaresField("FKEYS")).isTrue();
            assertThat(CardUpdateResponse.declaresField("FKEYSC")).isTrue();
            assertThat(CardUpdateResponse.declaresField("FKEYSO")).isFalse();
            assertThat(CardUpdateResponse.declaresField("FKEYSCO")).isFalse();
            assertThat(CardUpdateResponse.declaresField("FKEYSCC")).isFalse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf("FKEYSCO"))
                    .withMessageContaining("FKEYSCO");
        }

        @Test
        @DisplayName("both readings of FKEYSC survive the 484-byte image round trip")
        void bothReadingsSurviveTheImageRoundTrip() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeyso(FKEYS_LITERAL);
            response.setFkeysco(FKEYSC_LITERAL);
            response.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHYELLO);

            CardUpdateResponse restored = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);

            assertThat(restored.getFkeyso()).isEqualTo(FKEYS_LITERAL);
            assertThat(restored.getFkeysco()).isEqualTo(FKEYSC_LITERAL);
            // The two one-byte colour items came back as two different bytes at two different
            // offsets, which a fused member could not have done.
            assertThat(restored.colourOf(CardUpdateResponse.FKEYS)).isEqualTo(BmsAttributes.DFHRED);
            assertThat(restored.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(BmsAttributes.DFHYELLO);
        }
    }

    @Nested
    @DisplayName("One area, one type - the response declares no duplicate of the request's carriers")
    class OneAreaOneType {

        @Test
        @DisplayName("the only nested types left are ScreenField and FieldAttributes")
        void onlyScreenFieldAndFieldAttributesRemain() {
            List<String> nested = new ArrayList<>();
            for (Class<?> declared : CardUpdateResponse.class.getDeclaredClasses()) {
                nested.add(declared.getSimpleName());
            }
            assertThat(nested).containsExactlyInAnyOrder("ScreenField", "FieldAttributes");
        }

        @ParameterizedTest(name = "no nested type named {0}")
        @ValueSource(strings = {"ProgCommarea", "CcupDetails", "DetailsPrefix", "CardUpdateRecord"})
        @DisplayName("none of the four retired duplicate types is declared here any more")
        void noRetiredNestedTypeIsDeclared(String retired) {
            List<String> nested = new ArrayList<>();
            for (Class<?> declared : CardUpdateResponse.class.getDeclaredClasses()) {
                nested.add(declared.getSimpleName());
            }
            assertThat(nested).doesNotContain(retired);
            assertThat(RETIRED_NESTED_TYPES).contains(retired);
        }

        @Test
        @DisplayName("getCommArea returns the request's CommArea, not a response-local twin")
        void theProgramCommareaIsTheRequestsType() throws Exception {
            assertThat(CardUpdateResponse.class.getMethod("getCommArea").getReturnType())
                    .isEqualTo(CommArea.class)
                    .isEqualTo(CardUpdateRequest.class.getMethod("getCommArea").getReturnType());
        }

        @Test
        @DisplayName("the two 89-byte detail groups are the request's CardDetails and DetailGroup")
        void theDetailGroupsAreTheRequestsTypes() {
            CommArea area = populatedCommArea();
            assertThat(area.oldDetails()).isInstanceOf(CardDetails.class);
            assertThat(area.oldDetails().group()).isEqualTo(DetailGroup.OLD);
            assertThat(area.newDetails().group()).isEqualTo(DetailGroup.NEW);
            assertThat(CardDetails.RECORD_LENGTH).isEqualTo(89);
        }

        @Test
        @DisplayName("the embedded 150-byte record is the request's CardUpdateRecord")
        void theEmbeddedRecordIsTheRequestsType() {
            assertThat(populatedCommArea().cardUpdateRecord()).isInstanceOf(CardUpdateRecord.class);
            assertThat(CardUpdateRecord.RECORD_LENGTH).isEqualTo(150);
        }

        @Test
        @DisplayName("1 + 89 + 89 + 150 = 329 at the offsets COCRDUPC.cbl:274-321 declares")
        void theAreaIsThreeHundredAndTwentyNineBytes() {
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(329);
            assertThat(ChangeAction.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                    + CardDetails.RECORD_LENGTH + CardUpdateRecord.RECORD_LENGTH).isEqualTo(329);
            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(90);
            assertThat(CommArea.CARD_UPDATE_RECORD_OFFSET).isEqualTo(179);
        }
    }

    @Nested
    @DisplayName("One name per carrier - the request and the response spell the shared state alike")
    class OneNamePerCarrier {

        @Test
        @DisplayName("the response publishes commArea, cardScreenState and navigationContext")
        void theResponsePublishesTheSharedNames() {
            assertThat(membersOf(new CardUpdateResponse())).containsAll(SHARED_CARRIERS);
        }

        @Test
        @DisplayName("the request publishes the same three names")
        void theRequestPublishesTheSameNames() {
            assertThat(membersOf(new CardUpdateRequest())).containsAll(SHARED_CARRIERS);
        }

        @ParameterizedTest(name = "{0} is gone from the response")
        @ValueSource(strings = {"progCommarea", "screenState", "navigation"})
        @DisplayName("none of the three retired member names is emitted any more")
        void noRetiredMemberNameIsEmitted(String retired) {
            assertThat(membersOf(populatedResponse())).doesNotContain(retired);
            assertThat(RETIRED_CARRIER_NAMES).contains(retired);
        }

        @Test
        @DisplayName("the pair shares exactly the three carriers and nothing else")
        void thePairSharesExactlyTheThreeCarriers() {
            List<String> shared = new ArrayList<>(membersOf(new CardUpdateResponse()));
            shared.retainAll(membersOf(new CardUpdateRequest()));

            // The three carriers, and now every screen field too: @JsonProperty pins both views to the
            // same xxxI item in lower case (AAP 0.6.3), which is what makes a response a legal next
            // request. Before that, the response spelled each field xxxO and the pair shared only these
            // three names.
            assertThat(shared).containsAll(SHARED_CARRIERS);
            assertThat(shared)
                    .containsAll(FIELD_NAMES.stream()
                            .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList());
            assertThat(shared).hasSize(SHARED_CARRIERS.size() + FIELD_NAMES.size());
        }

        @Test
        @DisplayName("the response carries 23 members: 17 xxxO items, 3 carriers, 3 XCTL targets")
        void theResponseCarriesTwentyThreeMembers() {
            List<String> members = membersOf(new CardUpdateResponse());
            assertThat(members).hasSize(23);
            assertThat(members).containsAll(SHARED_CARRIERS);
            assertThat(members).contains("nextProgram", "nextMapset", "nextMap");
            for (String name : FIELD_NAMES) {
                // The wire name is the DFHMDF label in lower case, with no direction suffix (AAP 0.6.3).
                assertThat(members).contains(name.toLowerCase(java.util.Locale.ROOT));
            }
        }

        @Test
        @DisplayName("the metadata and image views stay off the wire")
        void theInternalViewsStayOffTheWire() {
            assertThat(membersOf(populatedResponse()))
                    .doesNotContain("fieldImages", "attributeImages", "namedFields", "attributes",
                            "payload");
        }
    }

    @Nested
    @DisplayName("The pair round-trips - what the response emits binds straight into a request")
    class PairRoundTrip {

        @Test
        @DisplayName("the response's commArea node binds into a request and compares equal")
        void theCommAreaCrossesThePairWithoutRewriting() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            JsonNode emitted = mapper.valueToTree(response).get("commArea");

            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", emitted);
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCommArea()).isEqualTo(response.getCommArea());
        }

        @Test
        @DisplayName("the 329-byte image is byte-identical on both sides of the pair")
        void theImageIsIdenticalAcrossThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", mapper.valueToTree(response).get("commArea"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCommArea().encode(ASCII))
                    .hasSize(CommArea.RECORD_LENGTH)
                    .isEqualTo(response.getCommArea().encode(ASCII));
            assertThat(bound.getCommArea().encode(EBCDIC))
                    .isEqualTo(response.getCommArea().encode(EBCDIC));
        }

        @Test
        @DisplayName("the cardScreenState node binds into a request unchanged")
        void theWorkAreaCrossesThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("cardScreenState", mapper.valueToTree(response).get("cardScreenState"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCardScreenState().getCcardNextProg())
                    .isEqualTo(response.getCardScreenState().getCcardNextProg());
            assertThat(bound.getCardScreenState().getCcCardNum())
                    .isEqualTo(response.getCardScreenState().getCcCardNum());
        }

        @Test
        @DisplayName("the navigationContext node binds into a request unchanged")
        void theNavigationCommareaCrossesThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("navigationContext", mapper.valueToTree(response).get("navigationContext"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getNavigationContext()).isEqualTo(response.getNavigationContext());
            assertThat(bound.hasNavigationContext()).isTrue();
            assertThat(bound.commareaLength())
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("a request echoing only the program area still reports the cold start")
        void echoingOnlyTheProgramAreaLeavesTheColdStartVisible() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", mapper.valueToTree(populatedResponse()).get("commArea"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.hasNavigationContext()).isFalse();
            assertThat(bound.commareaLength()).isZero();
            assertThat(bound.getCommArea()).isEqualTo(populatedResponse().getCommArea());
        }

        @Test
        @DisplayName("a whole response round-trips through JSON and compares equal")
        void aWholeResponseRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            CardUpdateResponse restored = mapper.readValue(
                    mapper.writeValueAsString(response), CardUpdateResponse.class);

            assertThat(restored).isEqualTo(response);
            assertThat(restored.hashCode()).isEqualTo(response.hashCode());
        }
    }

    @Nested
    @DisplayName("CCUP-CHANGE-ACTION - the 88-level the response reports, COCRDUPC.cbl:276-290")
    class ChangeActionReporting {

        @Test
        @DisplayName("a fresh response reports CCUP-DETAILS-NOT-FETCHED")
        void aFreshResponseReportsDetailsNotFetched() {
            assertThat(new CardUpdateResponse().toString())
                    .contains("changeAction=CCUP-DETAILS-NOT-FETCHED");
        }

        @ParameterizedTest(name = "{0} is reported as {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#changeActionStates")
        @DisplayName("every 88-level is named in the diagnostic, in declaration order")
        void everyConditionNameIsReported(ChangeAction action, String expected) {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setCommArea(CommArea.initialised().withChangeAction(action));

            assertThat(response.toString()).contains("changeAction=" + expected);
        }

        @Test
        @DisplayName("a byte no 88-level covers is reported as unrecognised rather than guessed")
        void anUncoveredByteIsReportedAsUnrecognised() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setCommArea(CommArea.initialised().withChangeAction(ChangeAction.of('Z')));

            assertThat(response.toString()).contains("changeAction=UNRECOGNISED('Z')");
        }

        @Test
        @DisplayName("the two umbrella 88-levels are never reported, the specific name always is")
        void theUmbrellaConditionsAreNotReported() {
            CardUpdateResponse response = new CardUpdateResponse();

            for (ChangeAction action : List.of(ChangeAction.changesNotOk(),
                    ChangeAction.changesOkNotConfirmed(), ChangeAction.changesOkayedAndDone(),
                    ChangeAction.changesOkayedLockError(), ChangeAction.changesOkayedButFailed())) {
                response.setCommArea(CommArea.initialised().withChangeAction(action));

                assertThat(action.isChangesMade())
                        .as("CCUP-CHANGES-MADE at COCRDUPC.cbl:282 holds for all five")
                        .isTrue();
                assertThat(response.toString())
                        .doesNotContain("CCUP-CHANGES-MADE")
                        .doesNotContain("changeAction=CCUP-CHANGES-FAILED");
            }
            assertThat(ChangeAction.changesOkayedLockError().isChangesFailed()).isTrue();
            assertThat(ChangeAction.changesOkayedButFailed().isChangesFailed()).isTrue();
        }

        @Test
        @DisplayName("the diagnostic names the map, the group width and the XCTL triple")
        void theDiagnosticNamesTheStructure() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.toString())
                    .startsWith("CardUpdateResponse[COCRDUP/CCRDUPAO 484B, txn=CCUP")
                    .contains("next=COCRDLIC/COCRDLI/CCRDLIA")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the diagnostic reports structure only - no card number reaches it")
        void theDiagnosticCarriesNoCardNumber() {
            assertThat(populatedResponse().toString()).doesNotContain(CARD_NUMBER);
        }
    }

    // =================================================================================================
    // Section 4.2 - the 17 xxxO fields, their BMS positions, and the 484-byte width identity.
    // =================================================================================================

    @Nested
    @DisplayName("The 17 xxxO items against the named DFHMDF entries, and 12 + 17 x 7 + 353 = 484")
    class NamedFieldProjection {

        @ParameterizedTest(name = "{0}O is {1} wide, DFHMDF LENGTH={1} at POS={2}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#namedFieldTriples")
        @DisplayName("every payload width equals its xxxO PICTURE and its DFHMDF LENGTH (G9)")
        void everyPayloadWidthMatchesBothSources(String label, int width, String bmsPos) {
            // The width is asserted three ways for the same field: the descriptor's declared width,
            // the xxxO span the codec writes through, and the value a PIC X move actually leaves
            // behind. The BMS POS travels in the display name so a failure names the DFHMDF entry.
            ScreenField field = CardUpdateResponse.fieldOf(label);

            assertThat(field.length()).as("%sO declared width at POS=%s", label, bmsPos)
                    .isEqualTo(width);
            assertThat(field.outputItemSpan().length()).isEqualTo(width);
            assertThat(field.outputItemSpan().name()).isEqualTo(label + "O");

            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(label, "");
            assertThat(response.outputItemOf(label)).hasSize(width);
        }

        @Test
        @DisplayName("17 named entries of the mapset's 34 DFHMDF entries, in declaration order")
        void seventeenNamedEntriesOfThirtyFour() {
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT).isEqualTo(17);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT).isEqualTo(34);
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .hasSize(17)
                    .containsExactlyElementsOf(namedFieldTriples().stream()
                            .map(row -> (String) row.get()[0])
                            .toList());
        }

        @Test
        @DisplayName("the 17 widths sum to 353 and the group image is 484 = 12 + 17 x 7 + 353")
        void theWidthsSumToThreeHundredAndFiftyThree() {
            int transcribedSum = 0;
            for (Arguments row : namedFieldTriples()) {
                transcribedSum += (int) row.get()[1];
            }

            // 4+40+8+8+40+8+11+16+50+1+2+4+2+40+80+21+18 = 353, computed from the hand-transcribed
            // table rather than from the type under test.
            assertThat(transcribedSum).isEqualTo(353);
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH).isEqualTo(353);

            // The identity in full: one 12-byte TIOAPFX FILLER, then 17 blocks of 3 + 4 metadata
            // bytes, then the 353 payload bytes.
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(4);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(12 + 17 * 7 + 353).isEqualTo(484);
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(484);
        }

        @Test
        @DisplayName("the request and the response are both 484, which is what the REDEFINES forces")
        void theRequestAndTheResponseAreTheSameWidth() {
            // app/cpy-bms/COCRDUP.CPY:121 declares 01 CCRDUPAO REDEFINES CCRDUPAI, and a REDEFINES
            // cannot change the storage it overlays - so the two views are the same number of bytes
            // by construction, not by coincidence.
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(CardUpdateRequest.GROUP_LENGTH);

            // The arithmetic holds because the two prefixes are the same size. The input side spends
            // 2 bytes on xxxL (COMP PIC S9(4)) + 1 on xxxF + 4 FILLER = 7; the output side spends
            // 3 FILLER (covering the input side's xxxL and xxxF) + 4 attribute bytes = 7.
            assertThat(CardUpdateRequest.FIELD_METADATA_LENGTH).isEqualTo(7);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH)
                    .isEqualTo(CardUpdateRequest.FIELD_METADATA_LENGTH);
            assertThat(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH
                    + CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(2 + 1 + 4);

            // Same TIOAPFX span, same payload total, therefore same group width.
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(CardUpdateRequest.TIOAPFX_LENGTH);
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH)
                    .isEqualTo(CardUpdateRequest.NAMED_FIELD_TOTAL_LENGTH);
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT)
                    .isEqualTo(CardUpdateRequest.NAMED_FIELD_COUNT);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT)
                    .isEqualTo(CardUpdateRequest.TOTAL_DFHMDF_COUNT);
        }

        @Test
        @DisplayName("EXPMONO 2, EXPYEARO 4 and EXPDAYO 2 are three fields, and '/' is not a member")
        void theExpiryDateIsThreeSeparateFields() {
            ScreenField expmon = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPMON);
            ScreenField expyear = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPYEAR);
            ScreenField expday = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPDAY);

            // app/cpy-bms/COCRDUP.CPY:188, :194 and :200 - three separate xxxO items, so three
            // separate payload members and three separate attribute quads.
            assertThat(expmon.length()).isEqualTo(2);
            assertThat(expyear.length()).isEqualTo(4);
            assertThat(expday.length()).isEqualTo(2);
            assertThat(List.of(expmon.name(), expyear.name(), expday.name()))
                    .containsExactly("EXPMON", "EXPYEAR", "EXPDAY").doesNotHaveDuplicates();
            assertThat(List.of(expmon.fieldOffset(), expyear.fieldOffset(), expday.fieldOffset()))
                    .doesNotHaveDuplicates();

            // The slash between the month and the year is an UNNAMED DFHMDF - app/bms/COCRDUP.bms
            // declares it with LENGTH=1, POS=(15,28) and INITIAL='/' and no label at all, so it is
            // one of the 17 entries that never reaches the symbolic map. It is not addressable and it
            // is not a payload member; the screen paints it, the program never moves to it.
            assertThat(CardUpdateResponse.declaresField("/")).isFalse();
            assertThat(CardUpdateResponse.namedFieldPrefixes()).doesNotContain("/");

            // EXPDAY exists, at width 2, and is protected+dark on this map - ATTRB=(DRK,FSET,PROT)
            // at app/bms/COCRDUP.bms:142 - but it is still a full payload member.
            assertThat(CardUpdateResponse.declaresField("EXPDAY")).isTrue();
            assertThat(new CardUpdateResponse().getExpdayo()).hasSize(2);
        }

        @ParameterizedTest(name = "{0}O pads a short sender to {1} and truncates a long one on the right")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#namedFieldTriples")
        @DisplayName("the PIC X move rule holds for all 17: right-pad short, right-truncate long")
        void thePicXMoveRuleHoldsForAllSeventeen(String label, int width, String bmsPos) {
            CardUpdateResponse response = new CardUpdateResponse();

            // Short sender: padded on the right with spaces, which is the lossless half of the rule.
            response.setOutputItem(label, "A");
            assertThat(response.outputItemOf(label))
                    .as("%sO at POS=%s pads on the right", label, bmsPos)
                    .isEqualTo(ASCII.movePicX("A", width))
                    .isEqualTo("A" + " ".repeat(width - 1));

            // Over-wide sender: truncated on the RIGHT, the PIC X direction, never the PIC 9 one.
            String overWide = "B".repeat(width + 3);
            response.setOutputItem(label, overWide);
            assertThat(response.outputItemOf(label))
                    .isEqualTo(ASCII.movePicX(overWide, width))
                    .isEqualTo("B".repeat(width))
                    .hasSize(width);
        }

        @Test
        @DisplayName("every FILLER span is declared and the image is exactly 484 bytes (G21)")
        void everyFillerSpanIsDeclaredAndCounted() {
            // 1 opening TIOAPFX FILLER + 17 per-field FILLER X(3) = 18 filler spans, and they are
            // 12 + 17 x 3 = 63 of the 484 bytes. Omitting them would not shorten the record by a
            // rounding error - it would shift every offset after the first field.
            List<FieldSpan> spans = CardUpdateResponse.OUTPUT_GROUP_LAYOUT.spans();
            long fillerSpans = spans.stream().filter(span -> span.kind().filler()).count();
            int fillerBytes = spans.stream().filter(span -> span.kind().filler())
                    .mapToInt(FieldSpan::length).sum();

            assertThat(fillerSpans).isEqualTo(18);
            assertThat(fillerBytes).isEqualTo(12 + 17 * 3).isEqualTo(63);

            // 103 spans in all: 1 + 17 x 6. RecordLayout has already refused any gap, overlap or
            // shortfall at class-initialisation time; this asserts the total it accepted.
            assertThat(spans).hasSize(1 + 17 * 6);
            assertThat(CardUpdateResponse.OUTPUT_GROUP_LAYOUT.recordLength()).isEqualTo(484);
            assertThat(spans.stream().mapToInt(FieldSpan::length).sum()).isEqualTo(484);
            assertThat(new CardUpdateResponse().toFixedWidth(StandardCharsets.US_ASCII))
                    .hasSize(484);
        }
    }

    // =================================================================================================
    // Section 4.3 - the attribute quad is metadata. It is addressable, and it is never on the wire.
    // =================================================================================================

    @Nested
    @DisplayName("The wire projection - 17 display members, and not one attribute byte among them")
    class WireProjection {

        @Test
        @DisplayName("JSON carries a member for each of the 17 display items, FKEYSCO included")
        void jsonCarriesEveryDisplayItem() {
            JsonNode node = new ObjectMapper().valueToTree(populatedResponse());

            // One member per xxxO item. Jackson derives the member name from the getter, so
            // getFkeysco() is "fkeysco" - the 18-byte field's payload, present and separate.
            assertThat(node.has("trnname")).isTrue();
            assertThat(node.has("title01")).isTrue();
            assertThat(node.has("curdate")).isTrue();
            assertThat(node.has("pgmname")).isTrue();
            assertThat(node.has("title02")).isTrue();
            assertThat(node.has("curtime")).isTrue();
            assertThat(node.has("acctsid")).isTrue();
            assertThat(node.has("cardsid")).isTrue();
            assertThat(node.has("crdname")).isTrue();
            assertThat(node.has("crdstcd")).isTrue();
            assertThat(node.has("expmon")).isTrue();
            assertThat(node.has("expyear")).isTrue();
            assertThat(node.has("expday")).isTrue();
            assertThat(node.has("infomsg")).isTrue();
            assertThat(node.has("errmsg")).isTrue();
            assertThat(node.has("fkeys")).isTrue();
            assertThat(node.has("fkeysc")).isTrue();

            // The card number and the embossed name cross the wire in full - B6.
            assertThat(node.get("cardsid").asText()).isEqualTo(CARD_NUMBER);
            assertThat(node.get("crdname").asText()).startsWith(EMBOSSED_NAME);
        }

        @Test
        @DisplayName("no xxxC, xxxP, xxxH or xxxV item is a JSON member - FKEYSC and FKEYSCC included")
        void noAttributeItemIsAJsonMember() {
            List<String> members = membersOf(populatedResponse());

            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                for (String suffix : List.of(CardUpdateResponse.COLOUR_ITEM_SUFFIX,
                        CardUpdateResponse.PS_ITEM_SUFFIX, CardUpdateResponse.HILIGHT_ITEM_SUFFIX,
                        CardUpdateResponse.VALIDN_ITEM_SUFFIX)) {
                    String item = label + suffix;
                    assertThat(members).doesNotContain(item);
                    // FKEYS + C is FKEYSC, which is ALSO a named field of this map, so its lower-case
                    // form is a legitimate wire name. Every other attribute item's lower-case form must
                    // be absent, and the FKEYS/FKEYSC pair is asserted by name below.
                    if (!CardUpdateResponse.namedFieldPrefixes().contains(item)) {
                        assertThat(members).doesNotContain(item.toLowerCase(Locale.ROOT));
                    }
                }
            }

            // Named outright, because these two are where a naive mapping goes wrong in both
            // directions: 'FKEYSC' is the one-byte colour item of FKEYS (COCRDUP.CPY:214) and
            // 'FKEYSCC' is the one-byte colour item of the field FKEYSC (:220). Leaking either onto
            // the wire would publish a 3270 attribute byte as if it were card data; losing 'fkeysco'
            // would drop an 18-byte payload field. All three assertions are explicit.
            // With the direction suffix dropped from the wire name, "fkeysc" IS the FKEYSC field and
            // must be present; what must stay absent is every ATTRIBUTE item of either field.
            assertThat(members).doesNotContain("FKEYSC", "FKEYSCC", "fkeyscc", "fkeyscp",
                    "fkeysch", "fkeyscv", "fkeysp", "fkeysh", "fkeysv");
            assertThat(members).contains("fkeysc", "fkeys");
        }

        @Test
        @DisplayName("the quad is exactly four items per field - the DSATTS derivation, 68 in all")
        void theQuadIsExactlyFourItemsPerField() {
            // app/bms/COCRDUP.bms:26 declares DSATTS=(COLOR,HILIGHT,PS,VALIDN) on CCRDUPA. BMS emits
            // one symbolic-map item per named attribute, which is why the output block carries four
            // one-byte items and not three or five, and why the group balances at 3 + 4 against the
            // input side's 2 + 1 + 4. Four attributes named, four bytes emitted.
            assertThat(CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(4);
            assertThat(List.of(CardUpdateResponse.COLOUR_ITEM_SUFFIX,
                    CardUpdateResponse.PS_ITEM_SUFFIX, CardUpdateResponse.HILIGHT_ITEM_SUFFIX,
                    CardUpdateResponse.VALIDN_ITEM_SUFFIX)).containsExactly("C", "P", "H", "V");

            Map<String, String> attributes = new CardUpdateResponse().attributeImages();
            assertThat(attributes).hasSize(17 * 4).hasSize(68);
            for (ScreenField field : CardUpdateResponse.namedFields()) {
                assertThat(attributes).containsKeys(field.colourItemName(), field.psItemName(),
                        field.hilightItemName(), field.validnItemName());
            }
        }

        @Test
        @DisplayName("the FILLER spans are not JSON members yet are counted in the 484-byte image")
        void theFillerSpansAreOffTheWireAndInTheImage() {
            List<String> members = membersOf(new CardUpdateResponse());

            // No member is named for a FILLER, in either the copybook's spelling or a Java one.
            assertThat(members).doesNotContain("FILLER", "filler", "tioapfx", "TIOAPFX");
            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                assertThat(members).doesNotContain(label + "FILLER");
            }

            // And yet the image is 484, which is only true because all 63 FILLER bytes are written.
            byte[] image = new CardUpdateResponse().toFixedWidth(StandardCharsets.US_ASCII);
            assertThat(image).hasSize(484);
            assertThat(484 - 12 - 17 * 3).isEqualTo(353 + 17 * 4);
        }

        @Test
        @DisplayName("an attribute byte reaches the image as a raw byte, never as decoded text")
        void anAttributeByteIsNeverCodePageDecoded() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            // DFHRED is X'F2'. In IBM037 that byte is the character '2' and in US-ASCII it is not a
            // character at all, so decoding it would destroy it. The same byte must appear at the
            // same offset in both code pages.
            byte[] ascii = response.toFixedWidth(StandardCharsets.US_ASCII);
            byte[] ebcdic = response.toFixedWidth(IBM037);

            assertThat(ascii[fkeysc.colourItemOffset()]).isEqualTo(BmsAttributes.DFHRED);
            assertThat(ebcdic[fkeysc.colourItemOffset()]).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributeImages().get("FKEYSCC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
        }
    }

    @Nested
    @DisplayName("Declared geometry - 17 xxxO items of 34 DFHMDF entries, 353 and 484")
    class DeclaredGeometry {

        @Test
        @DisplayName("17 name-labelled fields of 34 entries, in mapset declaration order")
        void seventeenNamedFieldsInDeclarationOrder() {
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT).isEqualTo(17);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT).isEqualTo(34);
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .containsExactlyElementsOf(FIELD_NAMES);
            assertThat(CardUpdateResponse.namedFields()).hasSize(17);
        }

        @Test
        @DisplayName("the widths come from the xxxO PICTURE clauses")
        void everyWidthComesFromItsPictureClause() {
            assertThat(CardUpdateResponse.namedFields())
                    .extracting(ScreenField::name, ScreenField::length)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("TRNNAME", 4),
                            org.assertj.core.groups.Tuple.tuple("TITLE01", 40),
                            org.assertj.core.groups.Tuple.tuple("CURDATE", 8),
                            org.assertj.core.groups.Tuple.tuple("PGMNAME", 8),
                            org.assertj.core.groups.Tuple.tuple("TITLE02", 40),
                            org.assertj.core.groups.Tuple.tuple("CURTIME", 8),
                            org.assertj.core.groups.Tuple.tuple("ACCTSID", 11),
                            org.assertj.core.groups.Tuple.tuple("CARDSID", 16),
                            org.assertj.core.groups.Tuple.tuple("CRDNAME", 50),
                            org.assertj.core.groups.Tuple.tuple("CRDSTCD", 1),
                            org.assertj.core.groups.Tuple.tuple("EXPMON", 2),
                            org.assertj.core.groups.Tuple.tuple("EXPYEAR", 4),
                            org.assertj.core.groups.Tuple.tuple("EXPDAY", 2),
                            org.assertj.core.groups.Tuple.tuple("INFOMSG", 40),
                            org.assertj.core.groups.Tuple.tuple("ERRMSG", 80),
                            org.assertj.core.groups.Tuple.tuple("FKEYS", 21),
                            org.assertj.core.groups.Tuple.tuple("FKEYSC", 18));
        }

        @Test
        @DisplayName("12 + 353 + 17 x 7 = 484, and RecordLayout machine-checks it")
        void theGroupIsFourHundredAndEightyFourBytes() {
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH).isEqualTo(353);
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(484);
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH + CardUpdateResponse.PAYLOAD_LENGTH
                    + 17 * CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(484);
        }

        @Test
        @DisplayName("the item names are built from the copybook suffixes, not spelled out")
        void theItemNamesAreBuiltFromTheSuffixes() {
            ScreenField acctsid = CardUpdateResponse.fieldOf("ACCTSID");

            assertThat(acctsid.outputItemName()).isEqualTo("ACCTSIDO");
            assertThat(acctsid.colourItemName()).isEqualTo("ACCTSIDC");
            assertThat(acctsid.psItemName()).isEqualTo("ACCTSIDP");
            assertThat(acctsid.hilightItemName()).isEqualTo("ACCTSIDH");
            assertThat(acctsid.validnItemName()).isEqualTo("ACCTSIDV");
        }

        @Test
        @DisplayName("every field's spans are contiguous and end where the next field begins")
        void theSpansAreContiguous() {
            int expected = CardUpdateResponse.TIOAPFX_LENGTH;
            for (ScreenField field : CardUpdateResponse.namedFields()) {
                assertThat(field.fieldOffset()).isEqualTo(expected);
                assertThat(field.prefixFillerOffset()).isEqualTo(expected);
                assertThat(field.colourItemOffset())
                        .isEqualTo(expected + CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
                assertThat(field.psItemOffset()).isEqualTo(field.colourItemOffset() + 1);
                assertThat(field.hilightItemOffset()).isEqualTo(field.psItemOffset() + 1);
                assertThat(field.validnItemOffset()).isEqualTo(field.hilightItemOffset() + 1);
                assertThat(field.outputItemOffset()).isEqualTo(field.validnItemOffset() + 1);
                assertThat(field.endOffsetExclusive())
                        .isEqualTo(field.outputItemOffset() + field.length());
                expected = field.endOffsetExclusive();
            }
            assertThat(expected).isEqualTo(CardUpdateResponse.GROUP_LENGTH);
        }

        @Test
        @DisplayName("each ScreenField describes itself with its own spans")
        void eachScreenFieldDescribesItself() {
            ScreenField trnname = CardUpdateResponse.fieldOf("TRNNAME");

            assertThat(trnname.describe()).contains("TRNNAME").contains("TRNNAMEO");
            assertThat(trnname.prefixFillerSpan().length())
                    .isEqualTo(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
            assertThat(trnname.colourItemSpan().length()).isEqualTo(1);
            assertThat(trnname.psItemSpan().length()).isEqualTo(1);
            assertThat(trnname.hilightItemSpan().length()).isEqualTo(1);
            assertThat(trnname.validnItemSpan().length()).isEqualTo(1);
        }

        @ParameterizedTest(name = "declaresField(\"{0}\") is true")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY",
                "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC"})
        @DisplayName("every declared label is recognised by name")
        void everyDeclaredLabelIsRecognised(String name) {
            assertThat(CardUpdateResponse.declaresField(name)).isTrue();
            assertThat(CardUpdateResponse.fieldOf(name).name()).isEqualTo(name);
        }

        @Test
        @DisplayName("a label this map does not declare is refused by name, not defaulted")
        void anUndeclaredLabelIsRefused() {
            assertThat(CardUpdateResponse.declaresField("CRDSTP1")).isFalse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf("CRDSTP1"))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("declaresField takes null as simply not a label, and fieldOf refuses it")
        void declaresFieldTakesNullAsNotALabel() {
            assertThat(CardUpdateResponse.declaresField(null)).isFalse();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf(null));
        }

        @Test
        @DisplayName("the field list and the prefix list are unmodifiable views")
        void theFieldListsAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateResponse.namedFields().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateResponse.namedFieldPrefixes().clear());
        }

        @Test
        @DisplayName("a ScreenField refuses a blank name, a non-positive width or line, or an "
                + "offset inside the TIOAPFX prefix")
        void screenFieldRefusesAnImpossibleDeclaration() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("  ", 4, 128, 12))
                    .withMessageContaining("name");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 0, 128, 12));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 4, 0, 12));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 4, 128, 11))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.TIOAPFX_LENGTH));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ScreenField(null, 4, 128, 12));
        }

        @Test
        @DisplayName("FKEYS and FKEYSC are two fields with two independent quads")
        void theFkeyscCollisionIsModelledAsTwoFields() {
            ScreenField fkeys = CardUpdateResponse.fieldOf("FKEYS");
            ScreenField fkeysc = CardUpdateResponse.fieldOf("FKEYSC");

            assertThat(fkeys.length()).isEqualTo(21);
            assertThat(fkeysc.length()).isEqualTo(18);
            assertThat(fkeys.colourItemName()).isEqualTo("FKEYSC");
            assertThat(fkeysc.colourItemName()).isEqualTo("FKEYSCC");
            assertThat(fkeys.outputItemName()).isEqualTo("FKEYSO");
            assertThat(fkeysc.outputItemName()).isEqualTo("FKEYSCO");
            assertThat(fkeys.fieldOffset()).isNotEqualTo(fkeysc.fieldOffset());
        }

        @Test
        @DisplayName("both FKEYSC readings appear in the attribute image and neither shadows the other")
        void bothFkeyscReadingsAppearInTheAttributeImage() {
            Map<String, String> attributes = new CardUpdateResponse().attributeImages();

            assertThat(attributes).hasSize(68);
            assertThat(attributes).containsKeys("FKEYSC", "FKEYSCC", "FKEYSCP", "FKEYSCH",
                    "FKEYSCV", "FKEYSP", "FKEYSH", "FKEYSV");
        }

        @Test
        @DisplayName("the identity constants come from the mapset and the CSD")
        void theIdentityConstantsAreTheSourceOnes() {
            assertThat(CardUpdateResponse.MAPSET_NAME).isEqualTo("COCRDUP");
            assertThat(CardUpdateResponse.MAP_NAME).isEqualTo("CCRDUPA");
            assertThat(CardUpdateResponse.INPUT_GROUP_NAME).isEqualTo("CCRDUPAI");
            assertThat(CardUpdateResponse.OUTPUT_GROUP_NAME).isEqualTo("CCRDUPAO");
            assertThat(CardUpdateResponse.TRANSACTION_ID).isEqualTo("CCUP");
            assertThat(CardUpdateResponse.PROGRAM_NAME).isEqualTo("COCRDUPC");
        }
    }

    @Nested
    @DisplayName("The send path - 3100-SCREEN-INIT and the PIC X moves it performs")
    class SendPath {

        @Test
        @DisplayName("a fresh response holds LOW-VALUES in all 17 items and 0x00 in all 68 bytes")
        void aFreshResponseIsLowValues() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThat(response.getTrnnameo()).isEqualTo("\u0000\u0000\u0000\u0000");
            assertThat(response.getCardsido()).isEqualTo("\u0000".repeat(16));
            for (String hex : response.attributeImages().values()) {
                assertThat(hex).isEqualTo(BmsAttributes.toHex((byte) 0x00));
            }
        }

        @Test
        @DisplayName("screenInit moves LOW-VALUES, the titles, CCUP and COCRDUPC, then the clock")
        void screenInitReproducesTheSendPath() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setAcctsido("00000000011");

            response.screenInit(fixedDateHeader());

            assertThat(response.getTrnnameo()).isEqualTo("CCUP");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDUPC");
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getCurdateo()).isEqualTo(FIXED_CURDATE).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo(FIXED_CURTIME).isEqualTo("23:15:58");
            assertThat(response.getAcctsido()).isEqualTo("\u0000".repeat(11));
        }

        @Test
        @DisplayName("screenInit refuses a missing clock reading rather than taking one itself")
        void screenInitRefusesAMissingClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().screenInit(null))
                    .withMessageContaining("3100-SCREEN-INIT");
        }

        @Test
        @DisplayName("applyDateTimeHeader refuses a missing clock reading")
        void applyDateTimeHeaderRefusesAMissingClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().applyDateTimeHeader(null))
                    .withMessageContaining("CURDATEO");
        }

        @Test
        @DisplayName("moveLowValuesToGroup resets the group and leaves the carriers alone")
        void moveLowValuesToGroupIsScopedToTheGroup() {
            CardUpdateResponse response = populatedResponse();
            CommArea area = response.getCommArea();
            CardScreenState state = response.getCardScreenState();

            response.moveLowValuesToGroup();

            assertThat(response.getCardsido()).isEqualTo("\u0000".repeat(16));
            assertThat(response.getTitle01o()).isEqualTo("\u0000".repeat(40));
            assertThat(response.getCommArea()).isSameAs(area);
            assertThat(response.getCardScreenState()).isSameAs(state);
            assertThat(response.getNextProgram()).isEqualTo("COCRDLIC");
        }

        @Test
        @DisplayName("applyScreenTitles takes the literals from COTTL01Y rather than retyping them")
        void applyScreenTitlesUsesTheSharedLiterals() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyScreenTitles();

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @ParameterizedTest(name = "a short value is padded to {1} on {0}")
        @CsvSource({"ACCTSID,11", "CARDSID,16", "CRDNAME,50", "INFOMSG,40", "ERRMSG,80",
                "FKEYS,21", "FKEYSC,18"})
        @DisplayName("a short value is right-padded to the declared width, the PIC X move rule")
        void aShortValueIsPadded(String name, int width) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setOutputItem(name, "X");

            assertThat(response.outputItemOf(name)).isEqualTo("X" + " ".repeat(width - 1));
        }

        @ParameterizedTest(name = "an over-wide value is right-truncated to {1} on {0}")
        @CsvSource({"ACCTSID,11", "CARDSID,16", "CRDSTCD,1", "EXPMON,2"})
        @DisplayName("an over-wide value is truncated on the right, the PIC X move rule")
        void anOverWideValueIsTruncated(String name, int width) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setOutputItem(name, "9".repeat(width + 5));

            assertThat(response.outputItemOf(name)).isEqualTo("9".repeat(width));
        }

        @Test
        @DisplayName("ERRMSGO is 80 while WS-RETURN-MSG is 75, so the move pads by five")
        void theErrorMessageMoveIsAPad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setErrmsgo("Y".repeat(75));

            assertThat(response.getErrmsgo()).hasSize(80).endsWith("     ");
        }

        @Test
        @DisplayName("every payload setter rejects null, because COBOL has no absent state")
        void everyPayloadSetterRejectsNull() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCardsido(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setOutputItem("ACCTSID", null));
        }

        @Test
        @DisplayName("setOutputItem and outputItemOf refuse a label this map does not declare")
        void theOutputItemAccessorsRefuseAnUnknownLabel() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setOutputItem("NOSUCH", "x"))
                    .withMessageContaining("NOSUCH");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.outputItemOf("NOSUCH"));
        }

        @Test
        @DisplayName("fieldImages is keyed by xxxO name, in declaration order, 17 entries")
        void fieldImagesIsKeyedByOutputItemName() {
            Map<String, String> images = populatedResponse().fieldImages();

            assertThat(images).hasSize(17);
            assertThat(images.keySet()).containsExactly("TRNNAMEO", "TITLE01O", "CURDATEO",
                    "PGMNAMEO", "TITLE02O", "CURTIMEO", "ACCTSIDO", "CARDSIDO", "CRDNAMEO",
                    "CRDSTCDO", "EXPMONO", "EXPYEARO", "EXPDAYO", "INFOMSGO", "ERRMSGO", "FKEYSO",
                    "FKEYSCO");
            assertThat(images.get("CARDSIDO")).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("fieldImages and attributeImages are unmodifiable views")
        void theImageViewsAreUnmodifiable() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldImages().put("TRNNAMEO", "x"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.attributeImages().put("TRNNAMEC", "x"));
        }
    }

    /**
     * The addressable side of the four {@code DSATTS} bytes: construction, name-addressed access and
     * the guards on {@link CardUpdateResponse#applyHighlight}.
     *
     * <p>The card screens do not copy {@code app/cpy/CSSETATY.cpy} - its only COBOL consumer is
     * {@code app/cbl/COACTUPC.cbl} - so nothing here asserts a copybook mechanism.
     * {@code app/cbl/COCRDUPC.cbl} inlines the equivalent rule at twelve {@code DFHRED} sites, and
     * {@link HighlightTruthTable} covers its six outcomes. What this class covers is the plumbing the
     * outcomes travel through.
     */
    @Nested
    @DisplayName("The attribute quad - addressable metadata, never a JSON member")
    class AttributeQuad {

        @Test
        @DisplayName("a fresh quad is four 0x00 bytes and describes itself as such")
        void aFreshQuadIsAllZero() {
            FieldAttributes quad = new FieldAttributes();

            assertThat(quad.getColour()).isZero();
            assertThat(quad.getPs()).isZero();
            assertThat(quad.getHilight()).isZero();
            assertThat(quad.getValidn()).isZero();
            assertThat(quad.describe()).isNotBlank();
        }

        @Test
        @DisplayName("the four-argument form and the copy form both carry all four bytes")
        void theQuadConstructorsCarryAllFourBytes() {
            FieldAttributes quad = new FieldAttributes((byte) 0xF2, (byte) 0x01, (byte) 0x02,
                    (byte) 0x03);
            FieldAttributes copy = new FieldAttributes(quad);

            assertThat(copy).isEqualTo(quad);
            assertThat(copy.hashCode()).isEqualTo(quad.hashCode());
            assertThat(copy.getColour()).isEqualTo((byte) 0xF2);
            assertThat(copy.getPs()).isEqualTo((byte) 0x01);
            assertThat(copy.getHilight()).isEqualTo((byte) 0x02);
            assertThat(copy.getValidn()).isEqualTo((byte) 0x03);
        }

        @Test
        @DisplayName("isDefault is true only when all four bytes are 0x00 - one per byte, four ways")
        void isDefaultAnswersForEachOfTheFourBytesIndependently() {
            // MOVE LOW-VALUES TO CCRDUPAO (app/cbl/COCRDUPC.cbl:1053) leaves all four at 0x00, and
            // under the 3270 conventions 0x00 is DFHDFCOL in a colour item and DFHDFHI in a highlight
            // item - so "untouched" and "default" are the same byte by design, not by coincidence.
            assertThat(new FieldAttributes().isDefault()).isTrue();
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo(FieldAttributes.DEFAULT);
            assertThat(BmsAttributes.DFHDFHI).isEqualTo(FieldAttributes.DEFAULT);

            // Each of the four bytes on its own is enough to make the quad non-default, so all four
            // conditions of the test are exercised in both directions.
            FieldAttributes colourMoved = new FieldAttributes();
            colourMoved.setColour(BmsAttributes.DFHRED);
            assertThat(colourMoved.isDefault()).isFalse();

            FieldAttributes psMoved = new FieldAttributes();
            psMoved.setPs((byte) 0x01);
            assertThat(psMoved.isDefault()).isFalse();

            FieldAttributes hilightMoved = new FieldAttributes();
            hilightMoved.setHilight(BmsAttributes.DFHBLINK);
            assertThat(hilightMoved.isDefault()).isFalse();

            FieldAttributes validnMoved = new FieldAttributes();
            validnMoved.setValidn((byte) 0x04);
            assertThat(validnMoved.isDefault()).isFalse();

            // And reset puts a moved quad back into the default state.
            colourMoved.reset();
            assertThat(colourMoved.isDefault()).isTrue();
        }

        @Test
        @DisplayName("isDefault and isRed are independent readings of the same colour byte")
        void isDefaultAndIsRedReadTheSameByteDifferently() {
            CardUpdateResponse response = new CardUpdateResponse();

            // A field the program has not touched: default, and not red.
            assertThat(response.attributesOf(CardUpdateResponse.FKEYSC).isDefault()).isTrue();
            assertThat(response.attributesOf(CardUpdateResponse.FKEYSC).isRed()).isFalse();

            // DFHBMDAR into EXPDAYC (app/cbl/COCRDUPC.cbl:1284) is a move of something OTHER than
            // red, so the quad stops being default without becoming red - which is why the colour
            // item is a byte and never a boolean "in error" flag.
            response.setColour(CardUpdateResponse.EXPDAY, BmsAttributes.DFHBMDAR);
            assertThat(response.attributesOf(CardUpdateResponse.EXPDAY).isDefault()).isFalse();
            assertThat(response.attributesOf(CardUpdateResponse.EXPDAY).isRed()).isFalse();

            // And DFHDFCOL into ACCTSIDC (app/cbl/COCRDUPC.cbl:1238-1240) restores the default byte.
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHRED);
            assertThat(response.attributesOf(CardUpdateResponse.ACCTSID).isRed()).isTrue();
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHDFCOL);
            assertThat(response.attributesOf(CardUpdateResponse.ACCTSID).isDefault()).isTrue();
        }

        @Test
        @DisplayName("reset returns every byte to 0x00")
        void resetReturnsEveryByteToZero() {
            FieldAttributes quad = new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4);

            quad.reset();

            assertThat(quad).isEqualTo(new FieldAttributes());
        }

        @Test
        @DisplayName("the four setters are independent of one another")
        void theFourSettersAreIndependent() {
            FieldAttributes quad = new FieldAttributes();

            quad.setColour(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isZero();
            quad.setPs((byte) 0x11);
            quad.setHilight((byte) 0x22);
            quad.setValidn((byte) 0x33);

            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isEqualTo((byte) 0x11);
            assertThat(quad.getHilight()).isEqualTo((byte) 0x22);
            assertThat(quad.getValidn()).isEqualTo((byte) 0x33);
            assertThat(quad.toString()).isNotBlank();
        }

        @Test
        @DisplayName("quad equality is reflexive, type-checked, and covers all four bytes")
        void quadEqualityCoversAllFourBytes() {
            FieldAttributes quad = new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4);

            assertThat(quad.equals(quad)).isTrue();
            assertThat(quad.equals(null)).isFalse();
            assertThat(quad.equals("X'01'")).isFalse();
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 9, (byte) 2, (byte) 3,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 9, (byte) 3,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 9,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 3,
                    (byte) 9));
            assertThat(quad).isEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4));
        }

        @Test
        @DisplayName("attributesOf hands out the live quad so a colour move is observable")
        void attributesOfHandsOutTheLiveQuad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.attributesOf("ACCTSID").setColour(BmsAttributes.DFHRED);

            assertThat(response.colourOf("ACCTSID")).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributeImages().get("ACCTSIDC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
        }

        @Test
        @DisplayName("setColour writes the same byte the quad reports")
        void setColourWritesThroughToTheQuad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setColour("CARDSID", BmsAttributes.DFHRED);

            assertThat(response.attributesOf("CARDSID").getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.colourOf("CARDSID")).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the attribute accessors refuse a label this map does not declare")
        void theAttributeAccessorsRefuseAnUnknownLabel() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.attributesOf("NOSUCH"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.colourOf("NOSUCH"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setColour("NOSUCH", BmsAttributes.DFHRED));
        }

        @Test
        @DisplayName("a REENTER decision moves the colour byte the decision itself resolved")
        void theHighlightAppliesInReenterState() {
            CardUpdateResponse response = new CardUpdateResponse();

            FieldHighlight applied = response.applyHighlight("ACCTSID",
                    FieldValidationState.NOT_OK, true);

            assertThat(applied.colourItemAssigned()).isTrue();
            assertThat(response.colourOf("ACCTSID")).isEqualTo(applied.colourItemValue());
        }

        @Test
        @DisplayName("an ENTER decision moves nothing, because the rule is gated on re-entry")
        void theHighlightDoesNothingInEnterState() {
            CardUpdateResponse response = new CardUpdateResponse();

            FieldHighlight applied = response.applyHighlight("ACCTSID",
                    FieldValidationState.NOT_OK, false);

            assertThat(applied.colourItemAssigned()).isFalse();
            assertThat(response.colourOf("ACCTSID")).isZero();
        }

        @Test
        @DisplayName("applyHighlight refuses a decision resolved for another card map")
        void applyHighlightRefusesAForeignMap() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight foreign = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "ACCTSID", "CCRDLIA");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CCRDLIA")
                    .withMessageContaining("CCRDUPA");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision naming a field this map does not declare")
        void applyHighlightRefusesAForeignField() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight foreign = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "CRDSTP1", CardUpdateResponse.MAP_NAME);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision that names no field at all")
        void applyHighlightRefusesAPrefixlessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight prefixless = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(prefixless))
                    .withMessageContaining("SCRNVAR2");
        }

        @Test
        @DisplayName("a decision that names a field but no map is accepted, since it cannot clash")
        void applyHighlightAcceptsAMaplessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight mapless = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "ACCTSID", "");

            response.applyHighlight(mapless);

            assertThat(response.colourOf("ACCTSID")).isEqualTo(mapless.colourItemValue());
        }

        @Test
        @DisplayName("a decision that assigns no output item leaves the payload alone")
        void applyHighlightLeavesThePayloadAloneWhenNoOutputIsAssigned() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem("ACCTSID", "00000000011");
            FieldHighlight colourOnly = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    "ACCTSID", CardUpdateResponse.MAP_NAME);

            response.applyHighlight(colourOnly);

            assertThat(colourOnly.outputItemAssigned()).isFalse();
            assertThat(response.outputItemOf("ACCTSID")).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("applyHighlight rejects a null decision, state or field name")
        void applyHighlightRejectsNulls() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight((FieldHighlight) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight(null,
                            FieldValidationState.NOT_OK, true));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight("ACCTSID", null, true));
        }
    }

    // =================================================================================================
    // Section 4.4 - the highlight truth table. Six cells, one asterisk, and the REENTER gate.
    //
    // Provenance, stated once here so no assertion below has to imply a mechanism: app/cpy/CSSETATY.cpy
    // has exactly ONE COBOL consumer, app/cbl/COACTUPC.cbl, and NO card program copies it. COCRDUPC
    // inlines the same two-level rule at twelve DFHRED sites of its own. What follows therefore asserts
    // the BYTES that land in xxxC and the CHARACTERS that land in xxxO - never a copybook.
    // =================================================================================================

    @Nested
    @DisplayName("Highlighting - DFHRED on NOT-OK or BLANK, '*' on BLANK, nothing unless REENTER")
    class HighlightTruthTable {

        @ParameterizedTest(name = "EXPYEAR: {0} + reenter={1} -> colour={2}, asterisk={3}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#highlightTruthTable")
        @DisplayName("all six cells, on the editable EXPYEAR field (ATTRB=(UNPROT), bms:135)")
        void allSixCellsOnAnEditableField(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {
            assertHighlightCell(CardUpdateResponse.EXPYEAR, CardUpdateResponse.EXPYEARO_LENGTH,
                    state, reenter, colourExpected, asteriskExpected);
        }

        @ParameterizedTest(name = "ACCTSID: {0} + reenter={1} -> colour={2}, asterisk={3}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#highlightTruthTable")
        @DisplayName("all six cells again, on ACCTSID, so the rule is not specific to one field")
        void allSixCellsOnASecondField(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {
            assertHighlightCell(CardUpdateResponse.ACCTSID, CardUpdateResponse.ACCTSIDO_LENGTH,
                    state, reenter, colourExpected, asteriskExpected);
        }

        /**
         * Drives one cell of the truth table against one field and asserts both outcomes.
         *
         * @param label            the {@code DFHMDF} label being edited
         * @param width            that field's declared {@code xxxO} width
         * @param state            the validation flag, the {@code (TESTVAR1)} analogue
         * @param reenter          whether {@code CDEMO-PGM-REENTER} holds
         * @param colourExpected   whether {@code DFHRED} must reach the {@code xxxC} item
         * @param asteriskExpected whether {@code '*'} must reach the {@code xxxO} item
         */
        private void assertHighlightCell(String label, int width, FieldValidationState state,
                boolean reenter, boolean colourExpected, boolean asteriskExpected) {

            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(label, "7");
            String before = response.outputItemOf(label);

            FieldHighlight applied = response.applyHighlight(label, state, reenter);

            assertThat(applied.colourItemAssigned()).isEqualTo(colourExpected);
            assertThat(applied.outputItemAssigned()).isEqualTo(asteriskExpected);

            if (colourExpected) {
                // DFHRED compared against the BmsAttributes constant, never a hand-written char.
                assertThat(response.colourOf(label)).isEqualTo(BmsAttributes.DFHRED);
                assertThat(response.attributesOf(label).isRed()).isTrue();
            } else {
                // Untouched is not the same as reset to a default: the field keeps whatever colour
                // the program had already put there, which here is the LOW-VALUES byte.
                assertThat(response.colourOf(label))
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                assertThat(response.attributesOf(label).isRed()).isFalse();
            }

            if (asteriskExpected) {
                // The asterisk lands in the DISPLAY item, xxxO - not in the colour item - and it is
                // moved as a PIC X value, so it pads to the declared width like any other.
                assertThat(response.outputItemOf(label))
                        .isEqualTo(ASCII.movePicX(FieldAttributeSetter.ASTERISK, width))
                        .startsWith("*");
            } else {
                assertThat(response.outputItemOf(label)).isEqualTo(before).startsWith("7");
            }

            // Whatever happened, the declared width did not change and no offset moved (G21).
            assertThat(response.outputItemOf(label)).hasSize(width);
            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)).hasSize(484);
        }

        @Test
        @DisplayName("exactly one of the six cells writes an asterisk, and it is (BLANK, REENTER)")
        void exactlyOneCellWritesAnAsterisk() {
            long asteriskCells = highlightTruthTable().stream()
                    .filter(row -> (boolean) row.get()[3])
                    .count();

            assertThat(asteriskCells).isEqualTo(1);
            assertThat(highlightTruthTable()).hasSize(6);

            // And it is the blank one under re-entry, never the not-ok one: the inner test checks
            // blankness only, so a field that was supplied and failed goes red without an asterisk.
            CardUpdateResponse blank = new CardUpdateResponse();
            blank.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.BLANK, true);
            CardUpdateResponse notOk = new CardUpdateResponse();
            notOk.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.NOT_OK, true);

            assertThat(blank.getExpyearo()).isEqualTo("*   ");
            assertThat(notOk.getExpyearo()).doesNotContain("*");
            assertThat(blank.colourOf(CardUpdateResponse.EXPYEAR))
                    .isEqualTo(notOk.colourOf(CardUpdateResponse.EXPYEAR))
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the asterisk never reaches a colour item, and DFHRED never reaches a payload")
        void theTwoMovesNeverSwapTargets() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyHighlight(CardUpdateResponse.CRDNAME, FieldValidationState.BLANK, true);

            // '*' into CRDNAMEO, DFHRED into CRDNAMEC - each into the item its own move names.
            assertThat(response.getCrdnameo()).startsWith("*").hasSize(50);
            assertThat(response.colourOf(CardUpdateResponse.CRDNAME))
                    .isEqualTo(BmsAttributes.DFHRED);
            // The colour item holds a byte, so it can never hold the asterisk character; and the
            // payload item is a String, so it can never hold an attribute byte. The types make the
            // swap unrepresentable, and this pins the byte that is actually there.
            assertThat(response.attributeImages().get("CRDNAMEC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED))
                    .isNotEqualTo(BmsAttributes.toHex((byte) '*'));
        }

        @Test
        @DisplayName("a highlight on one field leaves the other sixteen fields entirely alone")
        void aHighlightIsScopedToItsOwnField() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeysco(FKEYSC_LITERAL);

            response.applyHighlight(CardUpdateResponse.ACCTSID, FieldValidationState.BLANK, true);

            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                if (!CardUpdateResponse.ACCTSID.equals(label)) {
                    assertThat(response.colourOf(label))
                            .as("%sC after highlighting ACCTSID", label)
                            .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                }
            }
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL);
        }

        @Test
        @DisplayName("ERRMSGO is 80 and pads a shorter message; the mapset already declares COLOR=RED")
        void theErrorMessageFieldIsEightyAndAlreadyRed() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setErrmsgo("Card number not found");

            // app/cpy-bms/COCRDUP.CPY:212 ERRMSGO PIC X(80); app/bms/COCRDUP.bms:154-157 declares
            // ERRMSG ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=80, POS=(23,1) - so the error line is
            // red by mapset declaration, independently of any per-field highlight decision. Nothing
            // has to move DFHRED into ERRMSGC for the message to appear red on the terminal.
            assertThat(CardUpdateResponse.ERRMSGO_LENGTH).isEqualTo(80);
            assertThat(response.getErrmsgo())
                    .isEqualTo("Card number not found" + " ".repeat(80 - 21))
                    .hasSize(80);
            assertThat(response.colourOf(CardUpdateResponse.ERRMSG))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
        }
    }

    // =================================================================================================
    // Section 4.5 - the echoed 329-byte WS-THIS-PROGCOMMAREA, app/cbl/COCRDUPC.cbl:274-321.
    // =================================================================================================

    @Nested
    @DisplayName("The echoed commarea - 1 + 89 + 89 + 150 = 329, FILLER X(59) and all")
    class EchoedCommareaGeometry {

        @Test
        @DisplayName("the echoed image is exactly 329 bytes: 1 + 89 + 89 + 150")
        void theEchoedImageIsThreeHundredAndTwentyNine() {
            CommArea echoed = populatedResponse().getCommArea();

            // The arithmetic, written out: CCUP-CHANGE-ACTION PIC X(1) at :276, CCUP-OLD-DETAILS at
            // :291-301, CCUP-NEW-DETAILS at :303-313, CARD-UPDATE-RECORD at :314-321.
            assertThat(1 + 89 + 89 + 150).isEqualTo(329);
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(CardDetails.RECORD_LENGTH).isEqualTo(89);
            assertThat(CardUpdateRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(329);

            assertThat(echoed.encode(ASCII)).hasSize(329);
            assertThat(echoed.encode(IBM037)).hasSize(329);

            // And at the declared offsets 0, 1, 90 and 179 - so the three groups abut with no gap.
            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(1 + 89).isEqualTo(90);
            assertThat(CommArea.CARD_UPDATE_RECORD_OFFSET).isEqualTo(1 + 89 + 89).isEqualTo(179);
        }

        @Test
        @DisplayName("each details group is 89 = 11+16+3+50+8+1, with a separator-free 8-byte expiry")
        void eachDetailsGroupIsEightyNine() {
            CardDetails old = populatedResponse().getCommArea().oldDetails();

            assertThat(11 + 16 + 3 + 50 + 8 + 1).isEqualTo(89);
            assertThat(CardDetails.ACCTID_LENGTH).isEqualTo(11);
            assertThat(CardDetails.CARDID_LENGTH).isEqualTo(16);
            assertThat(CardDetails.CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardDetails.CRDNAME_LENGTH).isEqualTo(50);
            // CCUP-xxx-EXPIRAION-DATE is a GROUP of X(4) + X(2) + X(2) with no separator item, so it
            // occupies 8 bytes - two fewer than the 10-byte string in the card record below.
            assertThat(CardDetails.EXPYEAR_LENGTH + CardDetails.EXPMON_LENGTH
                    + CardDetails.EXPDAY_LENGTH).isEqualTo(8);
            assertThat(CardDetails.EXPIRAION_DATE_LENGTH).isEqualTo(8);
            assertThat(CardDetails.CRDSTCD_LENGTH).isEqualTo(1);

            assertThat(old.encode(ASCII)).hasSize(89);
            assertThat(old.ccupExpiraionDate()).isEqualTo("20260719").hasSize(8);
            assertThat(old.ccupExpiraionDate()).doesNotContain("-").doesNotContain("/");
        }

        @Test
        @DisplayName("the record is 150 = 16+11+3+50+10+1+59, with a separator-bearing 10-byte date")
        void theEmbeddedRecordIsOneHundredAndFifty() {
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord()
                    .withCardUpdateExpiraionDate("2026-07-19");

            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59).isEqualTo(150);
            assertThat(CardUpdateRecord.CARD_UPDATE_NUM_LENGTH).isEqualTo(16);
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH).isEqualTo(50);
            // CARD-UPDATE-EXPIRAION-DATE PIC X(10) at :319 - the same expiry, two bytes wider,
            // because this one carries its separators inside the field.
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH).isEqualTo(1);

            assertThat(record.encode(ASCII)).hasSize(150);
            assertThat(record.cardUpdateExpiraionDate()).isEqualTo("2026-07-19").hasSize(10);
            assertThat(record.cardUpdateExpiraionDateYear()).isEqualTo("2026");
            assertThat(record.cardUpdateExpiraionDateMonth()).isEqualTo("07");
            assertThat(record.cardUpdateExpiraionDateDay()).isEqualTo("19");
        }

        @Test
        @DisplayName("FILLER X(59) is present and space-filled; without it the record would be 91")
        void theFillerIsPresentAndSpaceFilled() {
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord();

            // app/cbl/COCRDUPC.cbl:321 - 10 FILLER PIC X(59). It is 59 of the 150 bytes, so nearly
            // two fifths of the record. Dropping it is not a rounding error: 16+11+3+50+10+1 = 91,
            // and every offset downstream of this record in the 329-byte area would shift by 59.
            assertThat(CardUpdateRecord.FILLER_LENGTH).isEqualTo(59);
            assertThat(CardUpdateRecord.FILLER_OFFSET).isEqualTo(91);
            assertThat(16 + 11 + 3 + 50 + 10 + 1).isEqualTo(91);
            assertThat(91 + 59).isEqualTo(150);

            byte[] image = record.encode(ASCII);
            assertThat(image).hasSize(150);
            String tail = new String(image, CardUpdateRecord.FILLER_OFFSET,
                    CardUpdateRecord.FILLER_LENGTH, StandardCharsets.US_ASCII);
            assertThat(tail).isEqualTo(" ".repeat(59)).hasSize(59);

            // The FILLER span is declared in the layout, which is what makes it emitted rather than
            // merely tolerated (G21).
            assertThat(CardUpdateRecord.FILLER.length()).isEqualTo(59);
            assertThat(CardUpdateRecord.FILLER.kind().filler()).isTrue();
            assertThat(CardUpdateRecord.LAYOUT.recordLength()).isEqualTo(150);
        }

        @Test
        @DisplayName("the EXPIRAION misspelling is preserved and EXPIRATION appears nowhere")
        void theMisspellingIsPreserved() {
            CardDetails old = populatedResponse().getCommArea().oldDetails();
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord();

            // Three sites, all preserved verbatim: CCUP-OLD-EXPIRAION-DATE at COCRDUPC.cbl:297,
            // CCUP-NEW-EXPIRAION-DATE at :309 and CARD-UPDATE-EXPIRAION-DATE at :319. Correcting the
            // spelling would rename a field, and a field-for-field diff compares names.
            assertThat(DetailGroup.OLD.expiraionDateSpan().name())
                    .isEqualTo("CCUP-OLD-EXPIRAION-DATE");
            assertThat(DetailGroup.NEW.expiraionDateSpan().name())
                    .isEqualTo("CCUP-NEW-EXPIRAION-DATE");
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE.name())
                    .isEqualTo("CARD-UPDATE-EXPIRAION-DATE");

            assertThat(old.itemValues()).containsKey("CCUP-OLD-EXPIRAION-DATE");
            assertThat(record.itemValues(ASCII)).containsKey("CARD-UPDATE-EXPIRAION-DATE");

            // Nothing anywhere in either group spells it correctly. The four assertions below are the
            // ONLY places the correct spelling appears in this file, and every one of them is a
            // NEGATIVE assertion: no item name, in either group or in the embedded record, may carry
            // it. A reviewer grepping for "EXPIRATION" should find exactly these, and no field.
            assertThat(old.itemValues().keySet()).noneMatch(name -> name.contains("EXPIRATION"));
            assertThat(record.itemValues(ASCII).keySet())
                    .noneMatch(name -> name.contains("EXPIRATION"));
            assertThat(DetailGroup.OLD.layout().spans()).noneMatch(
                    span -> span.name().contains("EXPIRATION"));
            assertThat(CardUpdateRecord.LAYOUT.spans()).noneMatch(
                    span -> span.name().contains("EXPIRATION"));
        }

        @Test
        @DisplayName("X(11)/X(3) pad right with spaces while 9(11)/9(03) fill left with zeros")
        void thePictureKindAsymmetrySurvivesTheRoundTrip() {
            CommArea echoed = populatedResponse().getCommArea();
            CardDetails old = echoed.oldDetails();
            CardUpdateRecord record = echoed.cardUpdateRecord();

            // CCUP-OLD-ACCTID PIC X(11) at COCRDUPC.cbl:292 - alphanumeric, so "11" would pad on the
            // RIGHT. The fixture supplies a full-width value, which is what the screen sends.
            assertThat(old.acctid()).isEqualTo("00000000011").hasSize(11);
            assertThat(old.withAcctid("11").acctid()).isEqualTo("11         ").hasSize(11);

            // CCUP-OLD-CVV-CD PIC X(3) at :293 - alphanumeric, right-padded, and carried in clear.
            assertThat(old.cvvCd()).isEqualTo(CVV_CODE).isEqualTo("123").hasSize(3);
            assertThat(old.withCvvCd("7").cvvCd()).isEqualTo("7  ").hasSize(3);

            // CARD-UPDATE-ACCT-ID PIC 9(11) at :316 - NUMERIC, so the same account identifier is
            // zero-filled from the LEFT. Opposite direction, identical width, adjacent offsets.
            assertThat(record.cardUpdateAcctId()).isEqualTo(11L);
            assertThat(record.cardUpdateAcctIdImage(ASCII)).isEqualTo("00000000011").hasSize(11);

            // CARD-UPDATE-CVV-CD PIC 9(03) at :317 - numeric, left-zero-filled, and again in clear.
            assertThat(record.cardUpdateCvvCd()).isEqualTo(123);
            assertThat(record.cardUpdateCvvCdImage(ASCII)).isEqualTo("123").hasSize(3);
            assertThat(record.withCardUpdateCvvCd(7).cardUpdateCvvCdImage(ASCII)).isEqualTo("007");

            // The card number itself is PIC X(16) in both groups and is carried whole in both.
            assertThat(old.cardid()).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(record.cardUpdateNum()).isEqualTo(CARD_NUMBER).hasSize(16);
        }

        @ParameterizedTest(name = "the response carries CCUP-CHANGE-ACTION = {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#changeActionBytes")
        @DisplayName("every one of the nine 88-level states can be carried and read back")
        void everyChangeActionStateCanBeCarried(String value, String expectedName) {
            CardUpdateResponse response = populatedResponse();

            response.setCommArea(populatedCommArea().withChangeAction(ChangeAction.of(value)));

            assertThat(response.getCommArea().changeAction().value()).isEqualTo(value);
            assertThat(response.getCommArea().changeAction().describe()).isEqualTo(expectedName);
            assertThat(response.getCommArea().changeAction().isRecognised()).isTrue();
            // Nine 88-levels over eight byte values, because CCUP-DETAILS-NOT-FETCHED covers two.
            assertThat(ChangeAction.DETAILS_NOT_FETCHED_VALUES).containsExactly("\u0000", " ");
        }

        @Test
        @DisplayName("'L' and 'F' each satisfy CHANGES-FAILED and CHANGES-MADE; 'S' satisfies neither")
        void theOverlappingGroupingsAreCarriedFaithfully() {
            // The three states the service actually signals on the way out, with their full predicate
            // vectors. 88 CCUP-CHANGES-MADE VALUES 'E','N','C','L','F' and 88 CCUP-CHANGES-FAILED
            // VALUES 'L','F' overlap, so a failed update is also a change made - two names, one byte.
            CardUpdateResponse lockError = responseCarrying(ChangeAction.changesOkayedLockError());
            ChangeAction lock = lockError.getCommArea().changeAction();
            assertThat(lock.value()).isEqualTo("L");
            assertThat(lock.isChangesFailed()).isTrue();
            assertThat(lock.isChangesMade()).isTrue();
            assertThat(lock.isChangesOkayedLockError()).isTrue();
            assertThat(lock.isChangesOkayedButFailed()).isFalse();
            assertThat(lock.isShowDetails()).isFalse();
            assertThat(lock.isDetailsNotFetched()).isFalse();

            CardUpdateResponse updateFailed = responseCarrying(ChangeAction.changesOkayedButFailed());
            ChangeAction failed = updateFailed.getCommArea().changeAction();
            assertThat(failed.value()).isEqualTo("F");
            assertThat(failed.isChangesFailed()).isTrue();
            assertThat(failed.isChangesMade()).isTrue();
            assertThat(failed.isChangesOkayedButFailed()).isTrue();
            assertThat(failed.isChangesOkayedLockError()).isFalse();

            CardUpdateResponse showDetails = responseCarrying(ChangeAction.showDetails());
            ChangeAction show = showDetails.getCommArea().changeAction();
            assertThat(show.value()).isEqualTo("S");
            assertThat(show.isShowDetails()).isTrue();
            assertThat(show.isChangesFailed()).isFalse();
            assertThat(show.isChangesMade()).isFalse();

            assertThat(ChangeAction.CHANGES_MADE_VALUES).containsExactly("E", "N", "C", "L", "F");
            assertThat(ChangeAction.CHANGES_FAILED_VALUES).containsExactly("L", "F");
        }

        @Test
        @DisplayName("the initial state is LOW-VALUES, which is not a space even though both qualify")
        void theInitialStateIsLowValuesAndNotASpace() {
            // 10 CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES at app/cbl/COCRDUPC.cbl:276-277, and
            // 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES at :278-280. Both bytes satisfy
            // the condition; they remain two different bytes, and the response carries whichever it
            // was given rather than normalising one into the other.
            ChangeAction lowValues = new CardUpdateResponse().getCommArea().changeAction();
            ChangeAction spaces = responseCarrying(ChangeAction.spacesState())
                    .getCommArea().changeAction();

            assertThat(lowValues.value()).isEqualTo("\u0000").isEqualTo(ChangeAction.LOW_VALUES);
            assertThat(spaces.value()).isEqualTo(" ").isEqualTo(ChangeAction.SPACES);
            assertThat(lowValues.value()).isNotEqualTo(spaces.value());
            assertThat(lowValues.isDetailsNotFetched()).isTrue();
            assertThat(spaces.isDetailsNotFetched()).isTrue();
            assertThat(lowValues).isNotEqualTo(spaces);
        }

        /**
         * A response echoing a commarea in one particular {@code CCUP-CHANGE-ACTION} state.
         *
         * <p>Built fresh per call, so no state crosses between test methods (practice
         * <strong>B9</strong>).
         *
         * @param action the state to carry
         * @return a populated response echoing that state
         */
        private CardUpdateResponse responseCarrying(ChangeAction action) {
            CardUpdateResponse response = populatedResponse();
            response.setCommArea(populatedCommArea().withChangeAction(action));
            return response;
        }
    }

    // =================================================================================================
    // Section 4.6 - navigation replaces XCTL, and it travels in the payload (rule R6, gate G37).
    // =================================================================================================

    @Nested
    @DisplayName("Navigation instead of XCTL - 8, 7 and 7 characters, all of it in the payload")
    class StatelessNavigation {

        @ParameterizedTest(name = "nextProgram accepts {0} at 8 characters")
        @ValueSource(strings = {"COCRDUPC", "COCRDLIC", "COMEN01C"})
        @DisplayName("nextProgram is 8, the width of LIT-CARDUPDPGM at COCRDLIC.cbl:203-204")
        void theNextProgramTokenIsEightCharacters(String program) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setNextProgram(program);

            assertThat(response.getNextProgram()).isEqualTo(program).hasSize(8);
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
        }

        @ParameterizedTest(name = "nextMapset {0} and nextMap {1} are both 7 characters")
        @CsvSource({"COCRDUP,CCRDUPA", "COCRDLI,CCRDLIA"})
        @DisplayName("nextMapset and nextMap are 7, per LIT-CARDUPDMAPSET and LIT-CARDUPDMAP")
        void theMapTokensAreSevenCharacters(String mapset, String map) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDUPC", mapset, map);

            assertThat(response.getNextMapset()).isEqualTo(mapset).hasSize(7);
            assertThat(response.getNextMap()).isEqualTo(map).hasSize(7);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("7 is not widened to 8 and 8 is not truncated to 7")
        void theTwoWidthsAreNotConflated() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDUPC", "COCRDUP", "CCRDUPA");

            // The program token is one character wider than the mapset token. A single "screen name"
            // width would either pad the mapset to 8 or clip the program to 7, and both would be
            // visible on the wire.
            assertThat(response.getNextProgram()).hasSize(8);
            assertThat(response.getNextMapset()).hasSize(7);
            assertThat(response.getNextProgram()).isNotEqualTo(response.getNextMapset());
            assertThat(response.getNextProgram()).startsWith(response.getNextMapset());

            // An 8-character value moved into a 7-character receiver is truncated on the right, the
            // PIC X rule; a 7-character value moved into the 8-character one is padded.
            response.setNextMapset("COCRDUPC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDUP").hasSize(7);
            response.setNextProgram("COCRDUP");
            assertThat(response.getNextProgram()).isEqualTo("COCRDUP ").hasSize(8);
        }

        @Test
        @DisplayName("the three carriers and the 329-byte area all travel in the payload, not a session")
        void everyPieceOfStateTravelsInThePayload() {
            // app/cbl/COCRDUPC.cbl:473 performs EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM), which hands
            // control on inside CICS. There is no server-side equivalent here: the response names its
            // successor and carries every byte of conversational state, and the client calls again.
            List<String> members = membersOf(populatedResponse());

            assertThat(members).containsAll(SHARED_CARRIERS);
            assertThat(members).contains("nextProgram", "nextMapset", "nextMap");

            CardUpdateResponse response = populatedResponse();
            assertThat(response.getCommArea()).isNotNull();
            assertThat(response.getCommArea().encode(ASCII)).hasSize(329);
            assertThat(response.getCardScreenState()).isNotNull();
            assertThat(response.getNavigationContext()).isNotNull();
        }

        @Test
        @DisplayName("two responses built independently share nothing, so there is no ambient holder")
        void twoResponsesAreFullyIndependent() {
            CardUpdateResponse first = new CardUpdateResponse();
            CardUpdateResponse second = new CardUpdateResponse();

            first.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");
            first.setCardsido(CARD_NUMBER);
            first.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            first.setCommArea(populatedCommArea());
            first.getCardScreenState().setCcardNextProg("COMEN01C");

            // Nothing the first response did is visible in the second, in any of the three carriers,
            // the payload, the attribute bytes or the XCTL tokens.
            assertThat(second.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(second.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(second.getNextMap()).isEqualTo(" ".repeat(7));
            assertThat(second.getCardsido()).isEqualTo("\u0000".repeat(16));
            assertThat(second.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(second.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(second.getCardScreenState().getCcardNextProg()).isEqualTo(" ".repeat(8));
            assertThat(second.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }
    }

    // =================================================================================================
    // Section 4.7 - the shared literals, the clock, and the value semantics.
    // =================================================================================================

    @Nested
    @DisplayName("Titles, messages, the clock and value semantics")
    class TitlesMessagesClockAndValueSemantics {

        @Test
        @DisplayName("TITLE01O and TITLE02O are 40, and the COTTL01Y literals fit them exactly")
        void theTitleFieldsAreFortyAndTheLiteralsFit() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyScreenTitles();

            assertThat(CardUpdateResponse.TITLE01O_LENGTH).isEqualTo(40);
            assertThat(CardUpdateResponse.TITLE02O_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(40);
            // Exactly 40 each, so app/cbl/COCRDUPC.cbl:1058-1059 neither pads nor truncates.
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @Test
        @DisplayName("CCDA-THANK-YOU X(40) and CCDA-MSG-THANK-YOU X(50) are different literals")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            // Two copybooks, two owners, two widths, two wordings. app/cpy/COTTL01Y.cpy owns the
            // X(40) screen-title literal, which says "CCDA application"; app/cpy/CSMSG01Y.cpy owns
            // the X(50) message, which says "CardDemo application". Substituting one for the other
            // would change both the text and the width of whatever field received it, so neither
            // belongs where the other goes - and in particular the X(50) messages do not fit a
            // 40-byte title item.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo application");

            // Moved into a 40-byte title item, the 50-byte message would lose its last ten bytes -
            // which is exactly why the two are kept apart rather than treated as one message.
            CardUpdateResponse response = new CardUpdateResponse();
            response.setTitle01o(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getTitle01o()).hasSize(40)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.substring(0, 40));
        }

        @Test
        @DisplayName("CURDATEO and CURTIMEO are 8 each, filled from a fixed Clock")
        void theDateAndTimeFieldsAreEightEach() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyDateTimeHeader(DateHeader.from(ASCII, FIXED_CLOCK));

            // app/cpy-bms/COCRDUP.CPY:140 and :158 declare X(8) each, matching the mapset's own
            // INITIAL='mm/dd/yy' at app/bms/COCRDUP.bms:51 and INITIAL='hh:mm:ss' at :74.
            assertThat(CardUpdateResponse.CURDATEO_LENGTH).isEqualTo(8);
            assertThat(CardUpdateResponse.CURTIMEO_LENGTH).isEqualTo(8);
            // A fixed instant, never a reading of the host clock, so the eight characters are
            // assertable exactly.
            assertThat(response.getCurdateo()).isEqualTo(FIXED_CURDATE).isEqualTo("07/19/22")
                    .hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo(FIXED_CURTIME).isEqualTo("23:15:58")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the same fixed Clock through screenInit gives the same eight-character images")
        void theFixedClockIsStableThroughScreenInit() {
            CardUpdateResponse first = new CardUpdateResponse();
            CardUpdateResponse second = new CardUpdateResponse();

            first.screenInit(DateHeader.from(ASCII, FIXED_CLOCK));
            second.screenInit(DateHeader.from(ASCII, FIXED_CLOCK));

            assertThat(first.getCurdateo()).isEqualTo(second.getCurdateo()).isEqualTo("07/19/22");
            assertThat(first.getCurtimeo()).isEqualTo(second.getCurtimeo()).isEqualTo("23:15:58");
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("equal field sets are equal and hash alike; one byte anywhere breaks it")
        void equalityIsByValueAcrossEveryMember() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

            // One byte inside the nested commarea - the CVV, three characters of 329.
            CardUpdateResponse cvvDiffers = populatedResponse();
            cvvDiffers.setCommArea(populatedCommArea().withCardUpdateRecord(
                    populatedCommArea().cardUpdateRecord().withCardUpdateCvvCd(124)));
            assertThat(left).isNotEqualTo(cvvDiffers);
        }

        @Test
        @DisplayName("FKEYSC and FKEYSCC each break equality on their own, one byte at a time")
        void theTwoColourItemsEachBreakEqualityIndependently() {
            CardUpdateResponse left = populatedResponse();

            // The one-byte colour item of FKEYS, COCRDUP.CPY:214.
            CardUpdateResponse fkeysColourDiffers = populatedResponse();
            fkeysColourDiffers.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(fkeysColourDiffers);

            // The one-byte colour item of the field FKEYSC, COCRDUP.CPY:220. A different byte at a
            // different offset, and it breaks equality independently of the one above - which a
            // single fused member could not do.
            CardUpdateResponse fkeyscColourDiffers = populatedResponse();
            fkeyscColourDiffers.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(fkeyscColourDiffers);
            assertThat(fkeysColourDiffers).isNotEqualTo(fkeyscColourDiffers);

            // And so does each of the two payload items.
            CardUpdateResponse fkeysoDiffers = populatedResponse();
            fkeysoDiffers.setFkeyso(FKEYS_LITERAL);
            assertThat(left).isNotEqualTo(fkeysoDiffers);

            CardUpdateResponse fkeyscoDiffers = populatedResponse();
            fkeyscoDiffers.setFkeysco(FKEYSC_LITERAL);
            assertThat(left).isNotEqualTo(fkeyscoDiffers);
            assertThat(fkeysoDiffers).isNotEqualTo(fkeyscoDiffers);
        }

        @Test
        @DisplayName("toString reports structure and masks nothing it reports")
        void toStringMasksNothing() {
            CardUpdateResponse response = populatedResponse();

            String rendered = response.toString();

            // It names the screen, the group width, the change action and the XCTL triple. It does
            // not copy the card number or the CVV into a line the COBOL never wrote - that would be
            // new exposure, not preserved behaviour - and equally it applies no mask, no ellipsis and
            // no redaction marker to anything it does report (practice B6).
            assertThat(rendered).contains("COCRDUP", "CCRDUPAO", "484", "CCUP",
                    "CCUP-CHANGES-OK-NOT-CONFIRMED", "COCRDLIC", "COCRDLI", "CCRDLIA");
            assertThat(rendered).doesNotContain("****", "[REDACTED]", "...");

            // The content itself is reachable, unmasked, through the accessors and the image view -
            // which is where a caller that wants it should look.
            assertThat(response.getCardsido()).isEqualTo(CARD_NUMBER);
            assertThat(response.fieldImages()).containsEntry("CARDSIDO", CARD_NUMBER);
            assertThat(response.getCommArea().oldDetails().cvvCd()).isEqualTo(CVV_CODE);
        }
    }

    @Nested
    @DisplayName("The 484-byte image - written and read at the declared offsets, in both code pages")
    class GroupImage {

        @Test
        @DisplayName("toFixedWidth writes exactly 484 bytes in both code pages")
        void theImageIsAlwaysFourHundredAndEightyFourBytes() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)).hasSize(484);
            assertThat(response.toFixedWidth(IBM037)).hasSize(484);
        }

        @Test
        @DisplayName("a payload item lands at its declared offset")
        void aPayloadItemLandsAtItsDeclaredOffset() {
            CardUpdateResponse response = populatedResponse();
            ScreenField cardsid = CardUpdateResponse.fieldOf("CARDSID");
            byte[] image = response.toFixedWidth(StandardCharsets.US_ASCII);

            String slice = new String(image, cardsid.outputItemOffset(), cardsid.length(),
                    StandardCharsets.US_ASCII);

            assertThat(slice).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("an attribute byte lands at its declared offset and is not code-page decoded")
        void anAttributeByteLandsAtItsDeclaredOffset() {
            CardUpdateResponse response = populatedResponse();
            response.setColour("ACCTSID", BmsAttributes.DFHRED);
            ScreenField acctsid = CardUpdateResponse.fieldOf("ACCTSID");

            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)[acctsid.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.toFixedWidth(IBM037)[acctsid.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the image round-trips through fromFixedWidth in both code pages")
        void theImageRoundTrips() {
            CardUpdateResponse response = populatedResponse();
            response.setColour("ACCTSID", BmsAttributes.DFHRED);

            CardUpdateResponse ascii = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
            CardUpdateResponse ebcdic = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(IBM037), IBM037);

            assertThat(ascii.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(ascii.attributeImages()).isEqualTo(response.attributeImages());
            assertThat(ebcdic.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(ebcdic.attributeImages()).isEqualTo(response.attributeImages());
        }

        @Test
        @DisplayName("fromFixedWidth leaves the carriers in their initial state")
        void fromFixedWidthLeavesTheCarriersInitial() {
            CardUpdateResponse decoded = CardUpdateResponse.fromFixedWidth(
                    populatedResponse().toFixedWidth(StandardCharsets.US_ASCII),
                    StandardCharsets.US_ASCII);

            assertThat(decoded.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(decoded.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(decoded.getNextProgram()).isBlank();
        }

        @Test
        @DisplayName("fromFixedWidth refuses an image that is not exactly 484 bytes")
        void fromFixedWidthRefusesAWrongLengthImage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(new byte[483],
                            StandardCharsets.US_ASCII))
                    .withMessageContaining("484");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(null,
                            StandardCharsets.US_ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(new byte[484], null));
        }

        @Test
        @DisplayName("writeInto and readFrom agree with toFixedWidth and fromFixedWidth")
        void writeIntoAndReadFromAgreeWithTheByteForms() {
            CardUpdateResponse response = populatedResponse();
            FixedWidthRecord record = new FixedWidthRecord(CardUpdateResponse.GROUP_LENGTH, StandardCharsets.US_ASCII);

            response.writeInto(record);

            assertThat(record.toByteArray())
                    .isEqualTo(response.toFixedWidth(StandardCharsets.US_ASCII));
            assertThat(CardUpdateResponse.readFrom(record).fieldImages())
                    .isEqualTo(response.fieldImages());
        }

        @Test
        @DisplayName("writeInto and readFrom reject a null record")
        void writeIntoAndReadFromRejectNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().writeInto(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.readFrom(null));
        }

        @Test
        @DisplayName("writeInto and readFrom refuse a record that is not 484 bytes wide")
        void writeIntoAndReadFromRefuseAWrongWidthRecord() {
            FixedWidthRecord tooNarrow = new FixedWidthRecord(CardUpdateResponse.GROUP_LENGTH - 1,
                    StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardUpdateResponse().writeInto(tooNarrow))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.GROUP_LENGTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.readFrom(tooNarrow))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.GROUP_LENGTH));
        }

        @Test
        @DisplayName("toFixedWidth rejects a null charset rather than taking the platform default")
        void toFixedWidthRejectsANullCharset() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().toFixedWidth(null));
        }
    }

    @Nested
    @DisplayName("Statelessness and the object contract")
    class StatelessnessAndObjectContract {

        /**
         * Gate <strong>G53</strong>: {@code WORKING-STORAGE} must never have become a static field,
         * because a shared mutable field would break request isolation and test determinism alike.
         *
         * <p>This is one of only three reflective checks in this file, and all three ask a
         * <em>structural</em> question that has no non-reflective answer - which fields are static,
         * and which nested types are declared. None of them compares values, walks bean properties or
         * stands in for an explicit assertion; in particular the {@code FKEYSC} / {@code FKEYSCC}
         * collision is asserted entirely by name, because a reflective walk over members is exactly
         * what would paper that collision over (practice <strong>B11</strong>).
         */
        @Test
        @DisplayName("no static mutable state: every static field is final")
        void everyStaticFieldIsFinal() {
            List<String> mutable = new ArrayList<>();
            for (Field field : CardUpdateResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }
            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the three carrier setters refuse null and name the empty-state factory")
        void theCarrierSettersRefuseNull() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCommArea(null))
                    .withMessageContaining("CommArea.initialised()");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCardScreenState(null))
                    .withMessageContaining("new CardScreenState()");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setNavigationContext(null))
                    .withMessageContaining("NavigationContext.empty()");
        }

        @Test
        @DisplayName("transferTo names the next program, mapset and map at their declared widths")
        void transferToNamesTheNextTarget() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");

            assertThat(response.getNextProgram()).isEqualTo("COCRDLIC")
                    .hasSize(CardScreenState.CCARD_NEXT_PROG_LENGTH);
            assertThat(response.getNextMapset()).isEqualTo("COCRDLI")
                    .hasSize(CardScreenState.CCARD_NEXT_MAPSET_LENGTH);
            assertThat(response.getNextMap()).isEqualTo("CCRDLIA")
                    .hasSize(CardScreenState.CCARD_NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("a short XCTL token is padded and an over-wide one truncated")
        void theXctlTokensFollowThePicXRule() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setNextProgram("CO");
            response.setNextMapset("TOOLONGMAPSET");
            response.setNextMap("X");

            assertThat(response.getNextProgram()).isEqualTo("CO      ");
            assertThat(response.getNextMapset()).isEqualTo("TOOLONG");
            assertThat(response.getNextMap()).isEqualTo("X      ");
        }

        @Test
        @DisplayName("the copy constructor is deep enough to mutate the copy safely")
        void theCopyConstructorIsDeepEnough() {
            CardUpdateResponse original = populatedResponse();
            CardUpdateResponse copy = new CardUpdateResponse(original);

            assertThat(copy).isEqualTo(original);
            copy.setCardsido("5555555555554444");
            copy.setColour("ACCTSID", BmsAttributes.DFHRED);
            copy.getCardScreenState().setCcardNextProg("COMEN01C");

            assertThat(original.getCardsido()).isEqualTo(CARD_NUMBER);
            assertThat(original.colourOf("ACCTSID")).isZero();
            assertThat(original.getCardScreenState().getCcardNextProg()).isEqualTo("COCRDLIC");
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the copy constructor rejects a null source")
        void theCopyConstructorRejectsNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse(null));
        }

        @Test
        @DisplayName("equality covers the payload, the attributes and all three carriers")
        void equalityCoversEverything() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();
            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

            right.setCommArea(CommArea.initialised());
            assertThat(left).isNotEqualTo(right);

            CardUpdateResponse attributeDiffers = populatedResponse();
            attributeDiffers.setColour("ACCTSID", BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(attributeDiffers);
        }

        @Test
        @DisplayName("equality is reflexive and refuses an unrelated type and null")
        void equalityIsWellBehaved() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.equals(response)).isTrue();
            assertThat(response.equals(null)).isFalse();
            assertThat(response.equals("CCRDUPAO")).isFalse();
        }

        @Test
        @DisplayName("each of the three XCTL targets takes part in equality on its own")
        void eachXctlTargetTakesPartInEquality() {
            CardUpdateResponse left = populatedResponse();

            CardUpdateResponse mapDiffers = populatedResponse();
            mapDiffers.setNextMap("CCRDUPA");
            assertThat(left).isNotEqualTo(mapDiffers);

            CardUpdateResponse mapsetDiffers = populatedResponse();
            mapsetDiffers.setNextMapset("COCRDUP");
            assertThat(left).isNotEqualTo(mapsetDiffers);

            CardUpdateResponse programDiffers = populatedResponse();
            programDiffers.setNextProgram("COMEN01C");
            assertThat(left).isNotEqualTo(programDiffers);
        }

        @Test
        @DisplayName("each of the three carriers takes part in equality on its own")
        void eachCarrierTakesPartInEquality() {
            CardUpdateResponse left = populatedResponse();

            CardUpdateResponse workAreaDiffers = populatedResponse();
            workAreaDiffers.getCardScreenState().setCcardNextProg("COMEN01C");
            assertThat(left).isNotEqualTo(workAreaDiffers);

            CardUpdateResponse navigationDiffers = populatedResponse();
            navigationDiffers.setNavigationContext(
                    NavigationContext.empty().withToProgram("COCRDLIC"));
            assertThat(left).isNotEqualTo(navigationDiffers);

            CardUpdateResponse commAreaDiffers = populatedResponse();
            commAreaDiffers.setCommArea(
                    populatedCommArea().withChangeAction(ChangeAction.showDetails()));
            assertThat(left).isNotEqualTo(commAreaDiffers);
        }

        @Test
        @DisplayName("a payload item difference alone breaks equality")
        void aPayloadDifferenceAloneBreaksEquality() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();

            right.setCrdnameo("JANE Q PUBLIC");

            assertThat(left).isNotEqualTo(right);
        }
    }

    /**
     * The seven {@code 88}-level states of {@code CCUP-CHANGE-ACTION} paired with the name the
     * diagnostic must report, in {@code app/cbl/COCRDUPC.cbl:276-290} declaration order.
     *
     * @return one argument pair per condition name
     */
    /**
     * The eight distinct byte values {@code CCUP-CHANGE-ACTION} can hold, each paired with the
     * {@code 88}-level name it must report, in {@code app/cbl/COCRDUPC.cbl:276-290} order.
     *
     * <p>Eight values for nine condition names, because {@code CCUP-DETAILS-NOT-FETCHED} covers two of
     * them - {@code LOW-VALUES} and {@code SPACES} - and the two umbrella conditions,
     * {@code CCUP-CHANGES-MADE} and {@code CCUP-CHANGES-FAILED}, share their bytes with the specific
     * names below rather than owning any of their own.
     *
     * <p>Supplied as a method source rather than a CSV literal because the first value is {@code x'00'},
     * which no CSV dialect carries intact.
     *
     * @return one argument pair per byte value
     */
    static List<Arguments> changeActionBytes() {
        return List.of(
                Arguments.of(ChangeAction.LOW_VALUES, "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.SPACES, "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.SHOW_DETAILS, "CCUP-SHOW-DETAILS"),
                Arguments.of(ChangeAction.CHANGES_NOT_OK, "CCUP-CHANGES-NOT-OK"),
                Arguments.of(ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                        "CCUP-CHANGES-OK-NOT-CONFIRMED"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_AND_DONE, "CCUP-CHANGES-OKAYED-AND-DONE"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                        "CCUP-CHANGES-OKAYED-LOCK-ERROR"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                        "CCUP-CHANGES-OKAYED-BUT-FAILED"));
    }

    static List<Arguments> changeActionStates() {
        return List.of(
                Arguments.of(ChangeAction.initial(),
                        "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.showDetails(),
                        "CCUP-SHOW-DETAILS"),
                Arguments.of(ChangeAction.changesNotOk(),
                        "CCUP-CHANGES-NOT-OK"),
                Arguments.of(ChangeAction.changesOkNotConfirmed(),
                        "CCUP-CHANGES-OK-NOT-CONFIRMED"),
                Arguments.of(ChangeAction.changesOkayedAndDone(),
                        "CCUP-CHANGES-OKAYED-AND-DONE"),
                Arguments.of(ChangeAction.changesOkayedLockError(),
                        "CCUP-CHANGES-OKAYED-LOCK-ERROR"),
                Arguments.of(ChangeAction.changesOkayedButFailed(),
                        "CCUP-CHANGES-OKAYED-BUT-FAILED"));
    }

    /**
     * The order in which this type publishes its members.
     *
     * <p>Every member here traces to a {@code DFHMDF} field of {@code app/bms/COCRDUP.bms} or to a
     * transport extension, and the projection is only faithful if it is published in the order the
     * screen declares - a client reading the object top to bottom must read the screen top to bottom.
     * Jackson does not give that for free: with no explicit order it derives one from reflection over
     * the accessors and moves every member renamed with {@code @JsonProperty} behind the ones that
     * were not renamed, which put this type's map fields out of screen order.
     */
    @Nested
    @DisplayName("the published member order is the order app/cpy-bms/COCRDUP.CPY declares")
    class BmsSerialisationOrder {

        /**
         * The 17 {@code xxxI} items of {@code app/cpy-bms/COCRDUP.CPY}, in that file's own
         * declaration order.
         */
        private static final List<String> MAP_PROJECTION = List.of(
                "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "acctsid",
                "cardsid", "crdname", "crdstcd", "expmon", "expyear", "expday", "infomsg",
                "errmsg", "fkeys", "fkeysc");

        /**
         * The 6 members that are not {@code DFHMDF} fields: the commarea in both the shapes this type publishes, the CVCRD01Y screen state, and the target COCRDUPC names through CDEMO-TO-PROGRAM at :474. They follow
         * the map and never interleave with it, so the screen reads as one contiguous run.
         */
        private static final List<String> TRANSPORT_EXTENSIONS = List.of(
                "commArea", "cardScreenState", "navigationContext", "nextProgram", "nextMapset",
                "nextMap");

        /** The map projection followed by the transport extensions - the whole published object. */
        private static final List<String> PUBLISHED_ORDER =
                joined(MAP_PROJECTION, TRANSPORT_EXTENSIONS);

        private static List<String> joined(List<String> first, List<String> second) {
            List<String> all = new ArrayList<>(first);
            all.addAll(second);
            return List.copyOf(all);
        }

        private static List<String> publishedMembers() {
            JsonNode body = new ObjectMapper().valueToTree(new CardUpdateResponse());
            List<String> published = new ArrayList<>();
            body.fieldNames().forEachRemaining(published::add);
            return published;
        }

        @Test
        @DisplayName("every member is published exactly once, in exactly that order")
        void theOrderIsTheMapsOwnOrder() {
            assertThat(publishedMembers()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }

        @Test
        @DisplayName("the map projection leads and the transport extensions follow it")
        void theMapProjectionLeadsAndTransportFollows() {
            List<String> published = publishedMembers();
            assertThat(published.subList(0, MAP_PROJECTION.size()))
                    .containsExactlyElementsOf(MAP_PROJECTION);
            assertThat(published.subList(MAP_PROJECTION.size(), published.size()))
                    .containsExactlyElementsOf(TRANSPORT_EXTENSIONS);
        }

        @Test
        @DisplayName("the order is declared on the type, so it cannot be derived from reflection")
        void theOrderIsDeclaredAndNotDerived() {
            JsonPropertyOrder declared = CardUpdateResponse.class.getAnnotation(JsonPropertyOrder.class);
            assertThat(declared).as("the published order must be fixed by annotation").isNotNull();
            assertThat(declared.value()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }
    }
}
