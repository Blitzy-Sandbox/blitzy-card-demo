package com.vsergeychik.carddemo.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.CardListController.MessageArm;
import com.vsergeychik.carddemo.card.CardListController.WorkArea;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListRequest.CardKey;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FieldMetadata;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FirstListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ScreenRowTable;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionErrorFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.StopperListRow;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.config.WebConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural tests for {@link CardListController}, the migration of {@code app/cbl/COCRDLIC.cbl}
 * (CICS transaction {@code CCLI}).
 *
 * <p><strong>Every decision is driven without HTTP.</strong> The controller is instantiated directly
 * with a stubbed {@link CardRepository}, so paging arithmetic, the eight-arm dispatcher, the seven
 * message arms and the two filter editors are all asserted with no {@code MockMvc} in the path
 * (practice B10, gate G51). {@code MockMvc} appears in exactly one nested class and asserts only the
 * HTTP contract - route, status, media type and JSON shape.
 *
 * <p><strong>Provenance of the expected values.</strong> COBOL cannot be executed in this environment
 * (AAP risk R-A: no z/OS, no CICS emulator, GnuCOBOL's indexed handler disabled, the three IBM-supplied
 * copybooks absent), so every expectation below is <em>statically derived</em> from
 * {@code app/cbl/COCRDLIC.cbl}, {@code app/cpy-bms/COCRDLI.CPY}, {@code app/cpy/CVCRD01Y.cpy} and
 * {@code app/cpy/CVACT02Y.cpy}, and is cited to the source line it comes from. No captured baseline is
 * claimed (practice B12).
 *
 * <p>The clock is fixed so {@code CURDATEO} and {@code CURTIMEO} are deterministic; the charset is
 * stated explicitly rather than taken from the platform (practice B8).
 *
 * <p><strong>Rules.</strong> {@code review_rules} reports that <em>no user rules were provided</em> for
 * this project. That is not licence to lower the bar: the Agent Action Plan &sect;0.10.2 enterprise
 * practices <strong>B1-B12</strong> bind in their place, and the ones this suite exists to prove are
 * B4 (both documented conflicts are asserted, not merely described), B5 (the preserved oddities are
 * pinned by assertions so they cannot be tidied away), B6 (the unconditional
 * {@code SET CDEMO-USRTYP-USER} is asserted and no authorization the COBOL does not perform is
 * introduced), B7 (the branch gate), B10 and gate G51 (no {@code MockMvc} in any decision path) and
 * B12 (the provenance note above).
 */
@DisplayName("CardListController - COCRDLIC, transaction CCLI, GET /api/cards")
final class CardListControllerTest {

    /** The dataset code page, always named rather than defaulted. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** A fixed instant so the two header fields are assertable. 2022-07-19 is the source's own date. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** {@code EIBAID} as an unsigned integer, which is how the query parameter carries it. */
    private static final int ENTER_PARAM = CicsAid.DFHENTER & 0xFF;

    private CardRepository repository;
    private CardBrowse browse;
    private CardListController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CardRepository.class);
        browse = mock(CardBrowse.class);
        when(repository.startBrowse(anyString(), any(BrowseDirection.class))).thenReturn(browse);
        controller = new CardListController(repository, CODEC, CLOCK);
    }

    // =============================================================================================
    // Fixtures. Card n has card number n zero-filled to sixteen and account id 10000000000 + n, so a
    // row's identity is readable from its assertion.
    // =============================================================================================

    private static CardRecord card(int n) {
        return CardRecord.moving(String.format("%016d", n), 10_000_000_000L + n, 123,
                "CARDHOLDER " + n, "2025-01-01", "Y", CODEC);
    }

    private static CardReadResult normal(int n) {
        return CardReadResult.normal(card(n));
    }

    /** Stubs the forward browse to hand back these cards and then end the file. */
    private void forward(int... cardNumbers) {
        CardReadResult[] rest = new CardReadResult[cardNumbers.length];
        for (int index = 0; index < cardNumbers.length; index++) {
            rest[index] = normal(cardNumbers[index]);
        }
        stub(true, rest);
    }

    /** Stubs the backward browse to hand back these cards and then run off the front of the file. */
    private void backward(int... cardNumbers) {
        CardReadResult[] rest = new CardReadResult[cardNumbers.length];
        for (int index = 0; index < cardNumbers.length; index++) {
            rest[index] = normal(cardNumbers[index]);
        }
        stub(false, rest);
    }

    private void stub(boolean forwards, CardReadResult... results) {
        CardReadResult[] withTail = new CardReadResult[results.length + 1];
        System.arraycopy(results, 0, withTail, 0, results.length);
        // Running off either end of the browse reports ENDFILE; the backward direction has no ENDFILE
        // arm, so this same result lands on its WHEN OTHER at :1361, exactly as the source has it.
        withTail[results.length] = CardReadResult.endOfFile();
        CardReadResult first = withTail[0];
        CardReadResult[] tail = new CardReadResult[withTail.length - 1];
        System.arraycopy(withTail, 1, tail, 0, tail.length);
        if (forwards) {
            when(browse.readNext()).thenReturn(first, tail);
        } else {
            when(browse.readPrev()).thenReturn(first, tail);
        }
    }

    /** A continuing request whose communication area names this program, i.e. {@code EIBCALEN > 0}. */
    private static CardListRequest continuing() {
        CardListRequest request = new CardListRequest();
        request.setNavigationContext(NavigationContext.empty()
                .withFromProgram(CardListController.LIT_THISPGM)
                .withPgmEnter());
        request.setPageCursor(PageCursor.initialised()
                .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)
                .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN));
        request.setRows(rows(0, " "));
        return request;
    }

    /**
     * Seven rows carrying cards 1..7, with {@code selection} typed on {@code selectedRow}. Row 1 is a
     * {@link FirstListRow} - four members - and rows 2..7 are {@link StopperListRow}s with five, which
     * is the asymmetry {@code app/cpy-bms/COCRDLI.CPY:78-79} declares.
     */
    private static List<ListRow> rows(int selectedRow, String selection) {
        List<ListRow> built = new ArrayList<>(CardListRequest.PAGE_SIZE);
        for (int row = 1; row <= CardListRequest.PAGE_SIZE; row++) {
            String typed = row == selectedRow ? selection : " ";
            String acctNo = String.format("%011d", 10_000_000_000L + row);
            String cardNum = String.format("%016d", row);
            built.add(row == 1
                    ? new FirstListRow(typed, acctNo, cardNum, "Y")
                    : new StopperListRow(typed, " ", acctNo, cardNum, "Y"));
        }
        return built;
    }

    /** A continuing request showing cards 1..7 with {@code selection} typed on {@code selectedRow}. */
    private static CardListRequest showing(int selectedRow, String selection) {
        CardListRequest request = continuing();
        request.setPageCursor(PageCursor.initialised()
                .withScreenNum(1)
                .withFirstCardKey(new CardKey(String.format("%016d", 1), 10_000_000_001L))
                .withLastCardKey(new CardKey(String.format("%016d", 7), 10_000_000_007L))
                .withNextPageExists());
        request.setRows(rows(selectedRow, selection));
        return request;
    }

    /** A text already moved to the declared width of {@code WS-ERROR-MSG}, {@code PIC X(75)}. */
    private static String moved(String message) {
        return CODEC.movePicX(message, CardScreenState.CCARD_ERROR_MSG_LENGTH);
    }

    /** Renders {@code LOW-VALUES} visibly so a failure message is readable. */
    private static String visible(String value) {
        return value == null ? "<null>" : value.replace('\u0000', '.');
    }

    // =============================================================================================

    @Nested
    @DisplayName("Page size 7 is behaviour, not configuration (gate G39)")
    final class PageSizeIsBehaviour {

        @Test
        @DisplayName("WS-MAX-SCREEN-LINES is a private static final int holding the literal 7")
        void constantIsSevenAndImmutable() throws ReflectiveOperationException {
            Field field = CardListController.class.getDeclaredField("WS_MAX_SCREEN_LINES");
            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("app/cbl/COCRDLIC.cbl:177-178 is WORKING-STORAGE, so nothing outside the "
                            + "program may reach it")
                    .isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(int.class);
            field.setAccessible(true);
            assertThat(field.getInt(null))
                    .as("WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7, app/cbl/COCRDLIC.cbl:177-178")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("no field of this controller is annotated @Value and none is injected by name")
        void nothingIsExternallyConfigured() {
            for (Field field : CardListController.class.getDeclaredFields()) {
                assertThat(field.getAnnotations())
                        .as("field %s must carry no annotation at all; a page size that could be "
                                + "bound from configuration would break gate G39", field.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the handler takes exactly two parameters and neither names a page size")
        void noRequestParameterCanChangeThePageSize() throws ReflectiveOperationException {
            Method handler = CardListController.class.getDeclaredMethod("getCards",
                    CardListRequest.class, Integer.class);
            assertThat(handler.getParameterCount()).isEqualTo(2);
            RequestParam param = handler.getParameters()[1].getAnnotation(RequestParam.class);
            assertThat(param).isNotNull();
            assertThat(param.name())
                    .as("the only query parameter is the EIBAID byte")
                    .isEqualTo(CardListController.EIBAID_PARAM);
        }

        @Test
        @DisplayName("twenty available records still fill exactly seven rows")
        void sevenRowsWhateverTheFileHolds() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            for (int row = 1; row <= 7; row++) {
                assertThat(response.screenRow(row).isLowValues())
                        .as("row %d must be filled", row)
                        .isFalse();
            }
            assertThat(response.screenRow(7).rowCardNum()).isEqualTo(String.format("%016d", 7));
        }

        @Test
        @DisplayName("the two DTOs agree with the controller on the page size")
        void dtosAgree() {
            assertThat(CardListRequest.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListResponse.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListResponse.LAST_ROW).isEqualTo(7);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Construction-time contract guards")
    final class ContractGuards {

        @Test
        @DisplayName("both guards pass against the collaborators as they stand")
        void guardsPass() {
            CardListController.verifyScreenContract();
            CardListController.verifyNavigationContract();
        }

        @Test
        @DisplayName("the wiring constructor accepts a charset and builds its own codec")
        void wiringConstructor() {
            CardListController wired = new CardListController(repository, StandardCharsets.US_ASCII,
                    CLOCK);
            assertThat(wired).isNotNull();
        }

        @Test
        @DisplayName("the wiring constructor refuses a null charset rather than defaulting")
        void wiringConstructorRejectsNullCharset() {
            Charset absent = null;
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardListController(repository, absent, CLOCK))
                    .withMessageContaining("dataset charset");
        }

        @Test
        @DisplayName("an integer disagreement is reported, an agreement is not")
        void requireAgreementOnIntegers() {
            CardListController.requireAgreement(7, 7, "a matching width");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardListController.requireAgreement(7, 8, "a card row"))
                    .withMessageContaining("a card row")
                    .withMessageContaining("app/cbl/COCRDLIC.cbl");
        }

        @Test
        @DisplayName("a name disagreement is reported, an agreement is not")
        void requireAgreementOnNames() {
            CardListController.requireAgreement("COCRDSLC", "COCRDSLC", "a matching program");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardListController.requireAgreement("COCRDSLC", "COCRDUPC",
                            "the card-detail program"))
                    .withMessageContaining("the card-detail program");
        }

        @Test
        @DisplayName("an ordering violation is reported, a satisfied ordering is not")
        void requireOrdering() {
            CardListController.requireOrdering(45, 50, "narrower than");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardListController.requireOrdering(50, 45, "the message line"))
                    .withMessageContaining("the message line");
        }

        @Test
        @DisplayName("Conflict 1: the card-select DTO is referenced only as a navigation target")
        void cardSelectIsANavigationReferenceOnly() {
            // app/cbl/COCRDLIC.cbl:274 is `*COPY COCRDSL.` - commented out - so this program has no
            // COCRDSL data area. The AAP's import directive is honoured by cross-checking the
            // navigation triple, and no COCRDSL map field is ever populated (practice B4).
            assertThat(CardListController.LIT_CARDDTLPGM).isEqualTo(CardSelectResponse.THIS_PROGRAM);
            assertThat(CardListController.LIT_CARDDTLMAPSET).isEqualTo(CardSelectResponse.THIS_MAPSET);
            assertThat(CardListController.LIT_CARDDTLMAP).isEqualTo(CardSelectResponse.MAP_NAME);
            assertThat(CardSelectRequest.ACCTSID_LENGTH)
                    .isEqualTo(NavigationContext.ACCT_ID_LENGTH);
            assertThat(CardSelectRequest.CARDSID_LENGTH)
                    .isEqualTo(NavigationContext.CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("Conflict 2: this program keeps its own correct CCRDLIA list map")
        void listMapIsNotTheSiblingsDefect() {
            // COCRDSLC:178 and COCRDUPC:234 set LIT-CCLISTMAP to 'CCRDSLA'. That defect is preserved
            // where it lives and is not propagated here (practice B4).
            assertThat(CardListController.LIT_THISMAP).isEqualTo("CCRDLIA");
            assertThat(CardListController.LIT_THISMAP).isNotEqualTo(CardSelectResponse.MAP_NAME);
        }

        @Test
        @DisplayName("the navigation literals carry their COBOL widths")
        void navigationLiteralWidths() {
            assertThat(CardListController.LIT_THISPGM).hasSize(8);
            assertThat(CardListController.LIT_THISTRANID).hasSize(4);
            assertThat(CardListController.LIT_THISMAPSET).hasSize(7);
            assertThat(CardListController.LIT_THISMAP).hasSize(7);
            assertThat(CardListController.LIT_MENUPGM).hasSize(8);
            assertThat(CardListController.LIT_MENUTRANID).hasSize(4);
            assertThat(CardListController.LIT_MENUMAPSET).hasSize(7);
            assertThat(CardListController.LIT_MENUMAP).hasSize(7);
            assertThat(CardListController.LIT_CARDUPDPGM).hasSize(8);
            assertThat(CardListController.LIT_CARDUPDTRANID).hasSize(4);
            assertThat(CardListController.LIT_CARDUPDMAPSET).hasSize(7);
            assertThat(CardListController.LIT_CARDUPDMAP).hasSize(7);
            assertThat(CardListController.LIT_CARDDTLTRANID).hasSize(4);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("EIBAID resolution")
    final class EibAidResolution {

        @Test
        @DisplayName("an absent parameter is ENTER, the key the program itself falls back to")
        void absentIsEnter() {
            assertThat(CardListController.resolveEibAid(null)).isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "eibaid={0} narrows to the same byte")
        @ValueSource(ints = {0, 1, 108, 125, 240, 255})
        void inRangeNarrows(int value) {
            assertThat(CardListController.resolveEibAid(value)).isEqualTo((byte) value);
        }

        @ParameterizedTest(name = "eibaid={0} is refused rather than wrapped")
        @ValueSource(ints = {-1, -256, 256, 300})
        void outOfRangeIsRefused(int value) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardListController.resolveEibAid(value))
                    .withMessageContaining(CardListController.EIBAID_PARAM);
        }

        @Test
        @DisplayName("an unrecognised AID becomes ENTER, which is the CSSTRPFY no-match outcome")
        void unrecognisedAidBecomesEnter() {
            // app/cbl/COCRDLIC.cbl:370-380. CSSTRPFY has no WHEN OTHER and no DFHPA3 branch and does
            // not clear CCARD-AID first, so PA3 matches no condition; :379 then forces ENTER.
            forward();
            WorkArea ws = new WorkArea();

            controller.listCards(null, CicsAid.DFHPA3, ws);

            assertThat(ws.isPfkInvalid()).isTrue();
            assertThat(ws.ccWorkArea.isCcardAidEnter()).isTrue();
        }

        @ParameterizedTest(name = "EIBAID 0x{1} is a key this program handles")
        @CsvSource({"125,7D,ENTER", "243,F3,PFK03", "247,F7,PFK07", "248,F8,PFK08"})
        void handledKeysAreValid(int aid, String hex, String expectedToken) {
            forward();
            WorkArea ws = new WorkArea();

            controller.listCards(continuing(), (byte) aid, ws);

            assertThat(ws.isPfkValid())
                    .as("app/cbl/COCRDLIC.cbl:371-377 lists exactly ENTER, PF3, PF7 and PF8")
                    .isTrue();
            assertThat(ws.ccWorkArea.getCcardAid().trim()).isEqualTo(expectedToken.trim());
            assertThat(Integer.toHexString(aid).toUpperCase()).isEqualTo(hex);
        }

        @ParameterizedTest(name = "EIBAID {0} is recognised but not handled, so it becomes ENTER")
        @ValueSource(ints = {241, 242, 244, 245, 246, 249, 250})
        void recognisedButUnhandledKeysBecomeEnter(int aid) {
            forward();
            WorkArea ws = new WorkArea();

            controller.listCards(continuing(), (byte) aid, ws);

            assertThat(ws.isPfkInvalid()).isTrue();
            assertThat(ws.ccWorkArea.isCcardAidEnter()).isTrue();
        }

        @Test
        @DisplayName("CLEAR is a recognised AID but not one this program handles, so it becomes ENTER")
        void clearIsNotHandled() {
            forward(1);
            WorkArea ws = new WorkArea();

            controller.listCards(continuing(), CicsAid.DFHCLEAR, ws);

            assertThat(ws.isPfkInvalid()).isTrue();
            assertThat(ws.ccWorkArea.isCcardAidEnter()).isTrue();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Cold start - EIBCALEN = 0, app/cbl/COCRDLIC.cbl:315-332")
    final class ColdStart {

        @Test
        @DisplayName("an absent payload initialises the commarea and the cursor")
        void absentPayloadInitialises() {
            forward();
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(ws.eibcalenZero).isTrue();
            assertThat(response.getNavigationContext().fromTranid())
                    .isEqualTo(CardListController.LIT_THISTRANID);
            assertThat(response.getNavigationContext().fromProgram())
                    .isEqualTo(CardListController.LIT_THISPGM);
            assertThat(response.getNavigationContext().isUser())
                    .as(":320 SET CDEMO-USRTYP-USER TO TRUE - unconditional (practice B6)")
                    .isTrue();
            assertThat(response.getNavigationContext().isEnter()).isTrue();
            assertThat(response.getNavigationContext().lastMap())
                    .isEqualTo(CardListController.LIT_THISMAP);
            assertThat(response.getNavigationContext().lastMapset())
                    .isEqualTo(CardListController.LIT_THISMAPSET);
        }

        @Test
        @DisplayName("a payload with no communication area is also EIBCALEN = 0")
        void emptyRequestIsAlsoColdStart() {
            forward();
            WorkArea ws = new WorkArea();

            controller.listCards(new CardListRequest(), CicsAid.DFHENTER, ws);

            assertThat(ws.eibcalenZero).isTrue();
        }

        @Test
        @DisplayName("the header fields are painted from the injected clock, never from now()")
        void headerFromInjectedClock() {
            forward();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getTrnnameo()).isEqualTo("CCLI");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDLIC");
            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("23:12:33");
        }

        @Test
        @DisplayName("arriving from another program forgets the carried page - :336-343")
        void arrivingFromElsewhereResetsThePage() {
            forward(1);
            CardListRequest request = new CardListRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withFromProgram(CardListController.LIT_MENUPGM)
                    .withPgmEnter());
            request.setPageCursor(PageCursor.initialised()
                    .withScreenNum(5)
                    .withFirstCardKey(new CardKey(String.format("%016d", 40), 40L)));
            request.setRows(rows(0, " "));
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.commarea.lastMap())
                    .as(":340 MOVE LIT-THISMAP TO CDEMO-LAST-MAP")
                    .isEqualTo(CardListController.LIT_THISMAP);
            assertThat(ws.commarea.lastMapset())
                    .as(":336-343 sets CDEMO-LAST-MAP but NOT CDEMO-LAST-MAPSET; COMMON-RETURN "
                            + "sets the mapset afterwards")
                    .isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.cursor.screenNum())
                    .as("the carried page 5 was discarded and rebuilt from row one")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a cold start does not receive the map, so no filter edit runs")
        void coldStartDoesNotReceiveTheMap() {
            forward();
            CardListRequest request = new CardListRequest();
            request.setAcctsid("NOTANUMBER!");
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.isInputOk())
                    .as(":357-362 only receives when EIBCALEN > 0 and the caller is this program")
                    .isTrue();
            assertThat(ws.isFlgAcctfilterNotOk()).isFalse();
        }

        @Test
        @DisplayName("a payload from a foreign program does not receive the map either")
        void foreignProgramDoesNotReceiveTheMap() {
            forward();
            CardListRequest request = new CardListRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withFromProgram("COMEN01C")
                    .withPgmReenter());
            request.setPageCursor(PageCursor.initialised());
            request.setRows(rows(3, "S"));
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.selectionFlags.selectedRowCount())
                    .as("nothing was received, so no action code reached WS-EDIT-SELECT")
                    .isZero();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("9000-READ-FORWARD - counts up and stops at seven, app/cbl/COCRDLIC.cbl:1123-1263")
    final class ForwardPaging {

        @Test
        @DisplayName("eight available records fill exactly seven rows and report a next page")
        void sevenRowsAndANextPage() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(ws.wsScrnCounter).isEqualTo(7);
            for (int row = 1; row <= 7; row++) {
                assertThat(response.screenRow(row).rowCardNum())
                        .as("row %d", row)
                        .isEqualTo(String.format("%016d", row));
                assertThat(response.screenRow(row).rowAcctno())
                        .isEqualTo(String.format("%011d", 10_000_000_000L + row));
                assertThat(response.screenRow(row).rowCardStatus()).isEqualTo("Y");
            }
            assertThat(response.getPageCursor().isNextPageExists())
                    .as(":1210-1211 the look-ahead read found a record")
                    .isTrue();
            assertThat(response.getPageCursor().lastCardNum())
                    .as(":1212-1214 the look-ahead record becomes the page's last key")
                    .isEqualTo(String.format("%016d", 8));
            assertThat(response.getPageCursor().firstCardNum())
                    .isEqualTo(String.format("%016d", 1));
            assertThat(response.getPageCursor().screenNum())
                    .as(":1177-1181 the guarded ADD +1 gives a cold start its page number")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("exactly seven records fill the page and report no next page")
        void exactlySevenRecords() {
            forward(1, 2, 3, 4, 5, 6, 7);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getPageCursor().isNextPageNotExists())
                    .as(":1216 the look-ahead read hit ENDFILE")
                    .isTrue();
            assertThat(response.getErrmsgo().trim()).isEqualTo("NO MORE RECORDS TO SHOW");
        }

        @ParameterizedTest(name = "{0} available record(s) fill {0} row(s) and leave the rest empty")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6})
        void fewerThanSevenAtEndOfFile(int available) {
            int[] cards = new int[available];
            for (int index = 0; index < available; index++) {
                cards[index] = index + 1;
            }
            forward(cards);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(ws.wsScrnCounter).isEqualTo(available);
            for (int row = 1; row <= available; row++) {
                assertThat(response.screenRow(row).isLowValues()).isFalse();
            }
            for (int row = available + 1; row <= 7; row++) {
                assertThat(response.screenRow(row).isLowValues())
                        .as("row %d was never written, so it keeps the LOW-VALUES of :643", row)
                        .isTrue();
            }
            assertThat(response.getPageCursor().isNextPageNotExists()).isTrue();
            assertThat(response.getPageCursor().lastCardNum())
                    .as(":1236-1237 CARD-RECORD still holds the final record returned")
                    .isEqualTo(String.format("%016d", available));
        }

        @Test
        @DisplayName("the empty-result branch: page one with no row placed - :1241-1245")
        void emptyResult() {
            forward();
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(ws.wsScrnCounter).isZero();
            assertThat(response.getErrmsgo().trim())
                    .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
            assertThat(response.getPageCursor().lastCardNum())
                    .as("no record was ever returned, so CARD-RECORD is the untouched area")
                    .isEqualTo(String.valueOf('\u0000').repeat(16));
        }

        @Test
        @DisplayName("a duplicate-key response is treated exactly like a normal one - :1157-1158")
        void duplicateKeyIsANormalRead() {
            when(browse.readNext()).thenReturn(CardReadResult.duplicateKey(card(4)),
                    CardReadResult.endOfFile());

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.screenRow(1).rowCardNum()).isEqualTo(String.format("%016d", 4));
        }

        @Test
        @DisplayName("a failing first read composes the file-error diagnostic - :1246-1254")
        void failingReadReportsTheDiagnostic() {
            when(browse.readNext()).thenReturn(CardReadResult.reportedFailure(16, 3));
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(response.getErrmsgo())
                    .startsWith("File Error: READ     on CARDDAT   returned RESP 000000016 ,RESP2 ");
            assertThat(ws.wsRespCd)
                    .as("1500-SEND-SCREEN's own RESP(WS-RESP-CD) at :944 overwrites what the browse "
                            + "left there, so the failing code survives only in the message")
                    .isEqualTo(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("the browse's response codes are recorded before the send overwrites them")
        void responseCodesAreRecordedByTheBrowse() {
            when(browse.readNext()).thenReturn(CardReadResult.reportedFailure(16, 3));
            WorkArea ws = new WorkArea();

            controller.readForward(ws);

            assertThat(ws.wsRespCd).isEqualTo(16);
            assertThat(ws.wsReasCd).isEqualTo(3);
            assertThat(ws.lastOutcome).isEqualTo(Outcome.OTHER);
            assertThat(ws.isReadLoopExit()).isTrue();
        }

        @Test
        @DisplayName("a failing look-ahead read reports the diagnostic - :1222-1230")
        void failingLookAheadReportsTheDiagnostic() {
            when(browse.readNext()).thenReturn(normal(1), normal(2), normal(3), normal(4), normal(5),
                    normal(6), normal(7), CardReadResult.reportedFailure(16, 9));

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getErrmsgo()).contains("returned RESP 000000016");
            assertThat(response.getErrmsgo()).contains(",RESP2 000000009");
            assertThat(response.screenRow(7).isLowValues())
                    .as("the seven displayed rows survive a failed look-ahead")
                    .isFalse();
        }

        @Test
        @DisplayName("a not-found look-ahead lands on WHEN OTHER, which the source has no arm for")
        void notFoundLookAheadIsWhenOther() {
            when(browse.readNext()).thenReturn(normal(1), normal(2), normal(3), normal(4), normal(5),
                    normal(6), normal(7), CardReadResult.notFound());
            WorkArea ws = new WorkArea();

            controller.readForward(ws);

            assertThat(ws.lastOutcome).isEqualTo(Outcome.NOT_FOUND);
            assertThat(ws.isReadLoopExit()).isTrue();
            assertThat(ws.wsErrorMsg).startsWith("File Error: READ");
        }

        @Test
        @DisplayName("a not-found first read is WHEN OTHER too - the source has only NORMAL, DUPREC "
                + "and ENDFILE arms")
        void notFoundFirstReadIsWhenOther() {
            when(browse.readNext()).thenReturn(CardReadResult.notFound());
            WorkArea ws = new WorkArea();

            controller.readForward(ws);

            assertThat(ws.lastOutcome).isEqualTo(Outcome.NOT_FOUND);
            assertThat(ws.wsScrnCounter).isZero();
            assertThat(ws.wsErrorMsg).startsWith("File Error: READ");
        }

        @Test
        @DisplayName("a duplicate-key look-ahead also reports a next page - :1208-1214")
        void duplicateKeyLookAhead() {
            when(browse.readNext()).thenReturn(normal(1), normal(2), normal(3), normal(4), normal(5),
                    normal(6), normal(7), CardReadResult.duplicateKey(card(8)));
            WorkArea ws = new WorkArea();

            controller.readForward(ws);

            assertThat(ws.cursor.isNextPageExists()).isTrue();
            assertThat(ws.cursor.lastCardNum()).isEqualTo(String.format("%016d", 8));
        }

        @Test
        @DisplayName("the look-ahead's end of file keeps an existing message too - :1218")
        void lookAheadDoesNotOverwriteAnExistingMessage() {
            when(browse.readNext()).thenReturn(normal(1), normal(2), normal(3), normal(4), normal(5),
                    normal(6), normal(7), CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();
            ws.setWsErrorMsg(moved("A FILTER MESSAGE"));

            controller.readForward(ws);

            assertThat(ws.wsErrorMsg).isEqualTo(moved("A FILTER MESSAGE"));
            assertThat(ws.cursor.isNextPageNotExists()).isTrue();
        }

        @Test
        @DisplayName("ENDBR runs even when a read throws - the -EXIT paragraph is always reached")
        void endBrowseRunsOnAnException() {
            when(browse.readNext()).thenThrow(new IllegalStateException("the browse broke"));
            WorkArea ws = new WorkArea();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> controller.readForward(ws))
                    .withMessage("the browse broke");

            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("end of file keeps an error message that is already set - :1238")
        void endOfFileDoesNotOverwriteAnExistingMessage() {
            when(browse.readNext()).thenReturn(normal(1), CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();
            ws.setWsErrorMsg(moved("AN EARLIER MESSAGE"));
            ws.cursor = ws.cursor.withScreenNum(2);

            controller.readForward(ws);

            assertThat(ws.wsErrorMsg)
                    .as("IF WS-ERROR-MSG-OFF guards the move, so an existing message survives")
                    .isEqualTo(moved("AN EARLIER MESSAGE"));
        }

        @Test
        @DisplayName("a later page that finds nothing is not the empty-result branch - :1241")
        void aLaterEmptyPageIsNotTheEmptyResultBranch() {
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();
            ws.cursor = ws.cursor.withScreenNum(3);

            controller.readForward(ws);

            assertThat(ws.wsScrnCounter).isZero();
            assertThat(ws.wsErrorMsg)
                    .as("the branch needs page one AND no row placed; this has only the second")
                    .isEqualTo(moved("NO MORE RECORDS TO SHOW"));
        }

        @Test
        @DisplayName("the browse is opened on the base file and closed exactly once")
        void browseIsOpenedForwardAndClosed() {
            forward(1);

            controller.listCards(null, CicsAid.DFHENTER);

            verify(repository).startBrowse(anyString(), eq(BrowseDirection.FORWARD));
            verify(browse, times(1)).close();
            verify(browse, never()).readPrev();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("9100-READ-BACKWARDS - primes to eight and counts down, :1264-1380")
    final class BackwardPaging {

        /** A request positioned on page 3 so {@code NOT CA-FIRST-PAGE} holds. */
        private CardListRequest onPageThree() {
            CardListRequest request = continuing();
            request.setPageCursor(PageCursor.initialised()
                    .withScreenNum(3)
                    .withFirstCardKey(new CardKey(String.format("%016d", 15), 10_000_000_015L))
                    .withLastCardKey(new CardKey(String.format("%016d", 21), 10_000_000_021L))
                    .withNextPageExists());
            return request;
        }

        @Test
        @DisplayName("the first READPREV is discarded and only decrements the counter to seven")
        void firstReadIsDiscarded() {
            backward(15, 14, 13, 12, 11, 10, 9, 8);

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7);

            assertThat(response.screenRow(7).rowCardNum())
                    .as(":1307 the discarded record is card 15, already at the top of page 3")
                    .isEqualTo(String.format("%016d", 14));
            assertThat(response.screenRow(1).rowCardNum())
                    .as("rows fill from 7 down to 1")
                    .isEqualTo(String.format("%016d", 8));
        }

        @Test
        @DisplayName("the counter terminates at zero and the last record becomes the first key")
        void countsDownToZero() {
            backward(15, 14, 13, 12, 11, 10, 9, 8);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(ws.wsScrnCounter)
                    .as(":1347 the loop stops when the counter reaches 0")
                    .isZero();
            assertThat(response.getPageCursor().firstCardNum())
                    .as(":1350-1353 the record that filled row 1")
                    .isEqualTo(String.format("%016d", 8));
            assertThat(response.getPageCursor().screenNum())
                    .as(":508 SUBTRACT 1 FROM WS-CA-SCREEN-NUM")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the browse opens backward, never forward, and closes once")
        void browseIsOpenedBackwardAndClosed() {
            backward(15, 14, 13, 12, 11, 10, 9, 8);

            controller.listCards(onPageThree(), CicsAid.DFHPF7);

            verify(repository).startBrowse(anyString(), eq(BrowseDirection.BACKWARD));
            verify(browse, never()).readNext();
            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("running off the front of the file lands on WHEN OTHER - :1361-1369")
        void runningOffTheFrontIsWhenOther() {
            backward(15, 14, 13);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(ws.isReadLoopExit()).isTrue();
            assertThat(response.getErrmsgo()).startsWith("File Error: READ");
            assertThat(response.screenRow(7).rowCardNum()).isEqualTo(String.format("%016d", 14));
            assertThat(response.screenRow(6).rowCardNum()).isEqualTo(String.format("%016d", 13));
            assertThat(response.screenRow(5).isLowValues()).isTrue();
        }

        @Test
        @DisplayName("a failing first READPREV returns early - :1308-1317")
        void failingFirstReadReturnsEarly() {
            when(browse.readPrev()).thenReturn(CardReadResult.reportedFailure(16, 4));
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(ws.wsScrnCounter)
                    .as("the counter was primed to 8 and never decremented")
                    .isEqualTo(8);
            assertThat(response.getErrmsgo()).contains(",RESP2 000000004");
            verify(browse, times(1)).readPrev();
            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("a duplicate-key READPREV is a normal read in both loops")
        void duplicateKeyIsANormalRead() {
            when(browse.readPrev()).thenReturn(CardReadResult.duplicateKey(card(15)),
                    CardReadResult.duplicateKey(card(14)), CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(response.screenRow(7).rowCardNum()).isEqualTo(String.format("%016d", 14));
            assertThat(ws.wsScrnCounter).isEqualTo(6);
        }

        @Test
        @DisplayName("a duplicate-key first read decrements, then normal reads fill the page")
        void duplicateKeyFirstReadThenNormalReads() {
            when(browse.readPrev()).thenReturn(CardReadResult.duplicateKey(card(15)), normal(14),
                    normal(13), normal(12), normal(11), normal(10), normal(9), normal(8),
                    CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(ws.wsScrnCounter).isZero();
            assertThat(response.screenRow(1).rowCardNum()).isEqualTo(String.format("%016d", 8));
        }

        @Test
        @DisplayName("a not-found read inside the loop lands on WHEN OTHER - :1361")
        void notFoundInsideTheLoopIsWhenOther() {
            when(browse.readPrev()).thenReturn(normal(15), normal(14), CardReadResult.notFound());
            WorkArea ws = new WorkArea();

            controller.readBackwards(ws);

            assertThat(ws.lastOutcome).isEqualTo(Outcome.NOT_FOUND);
            assertThat(ws.isReadLoopExit()).isTrue();
            assertThat(ws.wsErrorMsg).startsWith("File Error: READ");
        }

        @Test
        @DisplayName("a not-found FIRST read also lands on WHEN OTHER and returns early - :1308")
        void notFoundFirstReadReturnsEarly() {
            when(browse.readPrev()).thenReturn(CardReadResult.notFound());
            WorkArea ws = new WorkArea();

            controller.readBackwards(ws);

            assertThat(ws.wsScrnCounter).isEqualTo(8);
            assertThat(ws.isReadLoopExit()).isTrue();
            verify(browse, times(1)).readPrev();
        }

        @Test
        @DisplayName("ENDBR runs even when a backward read throws - :1375-1377 sits in the -EXIT")
        void endBrowseRunsOnAnExceptionBackwards() {
            when(browse.readPrev()).thenReturn(normal(15))
                    .thenThrow(new IllegalStateException("the browse broke"));
            WorkArea ws = new WorkArea();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> controller.readBackwards(ws))
                    .withMessage("the browse broke");

            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("a filtered-out record does not consume a row on the way back - :1337")
        void filteredRecordsDoNotConsumeARowBackwards() {
            when(browse.readPrev()).thenReturn(normal(15), normal(14), normal(13), normal(12),
                    CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("10000000013");
            ws.setFlgAcctfilterIsValid();
            ws.cursor = ws.cursor.withFirstCardKey(new CardKey(String.format("%016d", 15),
                    10_000_000_015L));

            controller.readBackwards(ws);

            assertThat(ws.screenRows.row(7).cardNum())
                    .as("card 14 was excluded, so card 13 took row 7")
                    .isEqualTo(String.format("%016d", 13));
            assertThat(ws.wsScrnCounter).isEqualTo(6);
        }

        @Test
        @DisplayName("the backward browse starts from the page's own first key - :1268")
        void startsFromTheFirstKey() {
            backward(15, 14, 13, 12, 11, 10, 9, 8);

            controller.listCards(onPageThree(), CicsAid.DFHPF7);

            verify(repository).startBrowse(eq(String.format("%016d", 15)),
                    eq(BrowseDirection.BACKWARD));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("0000-MAIN's eight-arm EVALUATE TRUE - order is load-bearing, :418-583 (gate G30)")
    final class Dispatcher {

        @Test
        @DisplayName("arm 1 WHEN INPUT-ERROR: repaint with the message and this program as the target")
        void armOneInputError() {
            forward(1, 2, 3);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(3, "X"), CicsAid.DFHENTER, ws);

            assertThat(ws.isInputError()).isTrue();
            assertThat(response.getErrmsgo().trim()).isEqualTo("INVALID ACTION CODE");
            assertThat(ws.ccWorkArea.getCcardNextProg()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.ccWorkArea.getCcardNextMapset()).isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.ccWorkArea.getCcardNextMap()).isEqualTo(CardListController.LIT_THISMAP);
            assertThat(ws.ccWorkArea.getCcardErrorMsg().trim())
                    .as(":423 MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG")
                    .isEqualTo("INVALID ACTION CODE");
            assertThat(response.getNextProgram().trim())
                    .as("no XCTL, so no navigation target is named")
                    .isEmpty();
            verify(repository).startBrowse(anyString(), eq(BrowseDirection.FORWARD));
        }

        @Test
        @DisplayName("arm 1 skips the re-read when the account filter is the thing that failed")
        void armOneSkipsTheReadOnAFilterError() {
            CardListRequest request = showing(0, " ");
            request.setAcctsid("NOTANUMBER!");
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.isFlgAcctfilterNotOk()).isTrue();
            verifyNoInteractions(browse);
            assertThat(response.getErrmsgo())
                    .startsWith("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        }

        @Test
        @DisplayName("arm 1 skips the re-read when the card filter is the thing that failed")
        void armOneSkipsTheReadOnACardFilterError() {
            CardListRequest request = showing(0, " ");
            request.setCardsid("NOT-A-CARD-NUMBR");
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.isFlgCardfilterNotOk()).isTrue();
            verifyNoInteractions(browse);
            assertThat(response.getErrmsgo())
                    .startsWith("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        }

        @Test
        @DisplayName("arm 2 WHEN PFK07 AND CA-FIRST-PAGE: re-read forward from the page's first key")
        void armTwoPageUpOnTheFirstPage() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest request = showing(0, " ");
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHPF7, ws);

            assertThat(ws.cursor.isFirstPage()).isTrue();
            verify(repository).startBrowse(eq(String.format("%016d", 1)),
                    eq(BrowseDirection.FORWARD));
            assertThat(response.getErrmsgo().trim())
                    .as(":903-904 the message arm for PF7 on the first page")
                    .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
        }

        @Test
        @DisplayName("arm 3 WHEN PFK03 arriving from another program: start afresh from the top")
        void armThreePf3FromElsewhere() {
            forward(1, 2);
            CardListRequest request = new CardListRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withFromProgram("COMEN01C")
                    .withPgmReenter());
            request.setPageCursor(PageCursor.initialised().withScreenNum(4));
            request.setRows(rows(0, " "));
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHPF3, ws);

            assertThat(response.getNextProgram().trim())
                    .as("the pre-dispatch menu transfer at :384-406 needs CDEMO-FROM-PROGRAM to be "
                            + "this program, so it does not fire here")
                    .isEmpty();
            assertThat(ws.commarea.fromProgram()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.commarea.isUser()).isTrue();
            assertThat(ws.cursor.screenNum())
                    .as("the page was reset and then rebuilt from row one")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("arm 3 WHEN CDEMO-PGM-REENTER and not from this program")
        void armThreeReenterFromElsewhere() {
            forward(1);
            CardListRequest request = new CardListRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withFromProgram("COCRDSLC")
                    .withPgmReenter());
            request.setPageCursor(PageCursor.initialised().withScreenNum(6));
            request.setRows(rows(0, " "));
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.commarea.lastMapset()).isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.cursor.screenNum()).isEqualTo(1);
        }

        @Test
        @DisplayName("arm 4 WHEN PFK08 AND CA-NEXT-PAGE-EXISTS: page down from the page's last key")
        void armFourPageDown() {
            forward(8, 9, 10, 11, 12, 13, 14, 15);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHPF8, ws);

            verify(repository).startBrowse(eq(String.format("%016d", 7)),
                    eq(BrowseDirection.FORWARD));
            assertThat(response.getPageCursor().screenNum())
                    .as(":492 ADD +1 TO WS-CA-SCREEN-NUM")
                    .isEqualTo(2);
            assertThat(response.screenRow(1).rowCardNum()).isEqualTo(String.format("%016d", 8));
            assertThat(ws.pfk08Continued)
                    .as(":410-412 PF8 leaves the last-page flag as the caller sent it")
                    .isTrue();
        }

        @Test
        @DisplayName("PF8 with no next page falls through to WHEN OTHER, not to arm 4")
        void pf8WithoutANextPageFallsThrough() {
            forward(1, 2);
            CardListRequest request = showing(0, " ");
            request.setPageCursor(request.getPageCursor().withNextPageNotExists());
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHPF8, ws);

            assertThat(response.getPageCursor().screenNum())
                    .as("WHEN OTHER does not advance the page number")
                    .isEqualTo(1);
            verify(repository).startBrowse(eq(String.format("%016d", 1)),
                    eq(BrowseDirection.FORWARD));
        }

        @Test
        @DisplayName("arm 8 WHEN OTHER: list from the page's own first key")
        void armEightWhenOther() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHENTER);

            verify(repository).startBrowse(eq(String.format("%016d", 1)),
                    eq(BrowseDirection.FORWARD));
            assertThat(response.getInfomsgo().trim())
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("PF3 from this program transfers to the main menu - :384-406")
        void pf3FromThisProgramTransfersToTheMenu() {
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHPF3, ws);

            assertThat(response.getNextProgram()).isEqualTo(CardListController.LIT_MENUPGM);
            assertThat(response.getNextMapset()).isEqualTo(CardListController.LIT_MENUMAPSET);
            assertThat(response.getNextMap())
                    .as(":395 moves LIT-THISMAP, not LIT-MENUMAP - preserved (practice B4)")
                    .isEqualTo(CardListController.LIT_THISMAP);
            assertThat(response.getNavigationContext().toProgram())
                    .isEqualTo(CardListController.LIT_MENUPGM);
            assertThat(response.getNavigationContext().isUser())
                    .as(":388 SET CDEMO-USRTYP-USER TO TRUE (practice B6)")
                    .isTrue();
            assertThat(ws.wsErrorMsg.trim())
                    .as(":396 SET WS-EXIT-MESSAGE - set and then discarded, because the transfer "
                            + "skips 1000-SEND-MAP")
                    .isEqualTo("PF03 PRESSED.EXITING");
            assertThat(response.getErrmsgo())
                    .as("no map was painted, so ERRMSGO stays at the LOW-VALUES it was born with")
                    .isEqualTo(String.valueOf('\u0000')
                            .repeat(CardListResponse.ERRMSGO_LENGTH));
            assertThat(ws.mapSent).isFalse();
            verifyNoInteractions(browse);
        }

        @Test
        @DisplayName("a cold-start PF3 DOES transfer, because :319 has just named this program")
        void coldStartPf3AlsoTransfers() {
            // :315-332 moves LIT-THISPGM into CDEMO-FROM-PROGRAM before the guard at :385 tests it,
            // so a first-ever PF3 satisfies CDEMO-FROM-PROGRAM = LIT-THISPGM and leaves for the menu.
            // The reading is counter-intuitive and is asserted so it cannot be "tidied" later.
            CardListResponse response = controller.listCards(null, CicsAid.DFHPF3);

            assertThat(response.getNextProgram()).isEqualTo(CardListController.LIT_MENUPGM);
            verifyNoInteractions(browse);
        }

        @Test
        @DisplayName("COMMON-RETURN restates this program's identity on every non-transferring arm")
        void commonReturnRestatesIdentity() {
            forward(1);

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHENTER);

            assertThat(response.getNavigationContext().fromTranid())
                    .isEqualTo(CardListController.LIT_THISTRANID);
            assertThat(response.getNavigationContext().fromProgram())
                    .isEqualTo(CardListController.LIT_THISPGM);
            assertThat(response.getNavigationContext().lastMapset())
                    .isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(response.getNavigationContext().lastMap())
                    .isEqualTo(CardListController.LIT_THISMAP);
            assertThat(response.getPageCursor()).isNotNull();
            assertThat(response.getCardScreenState()).isNotNull();
        }

        @Test
        @DisplayName("REENTER from THIS program does not take arm 3 - the guard is 'not from here'")
        void reenterFromThisProgramIsNotArmThree() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest request = showing(0, " ");
            request.setNavigationContext(request.getNavigationContext().withPgmReenter());
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.commarea.isReenter())
                    .as("the arm requires CDEMO-PGM-REENTER *and* a foreign caller, so this reaches "
                            + "WHEN OTHER instead")
                    .isTrue();
            assertThat(ws.cursor.screenNum())
                    .as("the page was NOT reset, because arm 3 did not run")
                    .isEqualTo(1);
            assertThat(ws.cursor.isNextPageExists()).isTrue();
        }

        @Test
        @DisplayName("'U' from a foreign program does not transfer either - :547")
        void updateFromAForeignProgramDoesNotTransfer() {
            forward(1);
            CardListRequest request = showing(4, "U");
            request.setNavigationContext(request.getNavigationContext().withFromProgram("COMEN01C"));

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.getNextProgram().trim()).isEmpty();
        }

        @Test
        @DisplayName("paging past the end of the file leaves a later page with no rows at all")
        void pagingPastTheEndLeavesALaterPageEmpty() {
            forward();
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHPF8, ws);

            assertThat(ws.cursor.screenNum())
                    .as("the page number advanced before the browse found nothing")
                    .isEqualTo(2);
            assertThat(ws.wsScrnCounter).isZero();
            assertThat(response.getErrmsgo().trim())
                    .as(":1241-1245 is guarded by page one, so this is the 'no more records' text "
                            + "rather than 'no records found'")
                    .isEqualTo("NO MORE PAGES TO DISPLAY");
        }

        @ParameterizedTest(name = "a '{0}' selection carried from a foreign caller does not transfer")
        @CsvSource({"S", "U"})
        void aSelectionWithAForeignCallerDoesNotTransfer(String selection) {
            // Unreachable through 0000-MAIN, because :357-362 receives the map only when the caller
            // is this program, so a selection cannot exist while CDEMO-FROM-PROGRAM names someone
            // else. The dispatcher is driven directly so the third condition of both arms - :519 and
            // :547, CDEMO-FROM-PROGRAM = LIT-THISPGM - is proved to be load-bearing rather than
            // decorative. Preserving it verbatim is practice B5.
            forward(1, 2);
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
            ws.commarea = NavigationContext.empty().withFromProgram("COMEN01C");
            ws.iSelected = 3;
            ws.selectionFlags = SelectionFlags.spacesFilled().withSelection(3, selection.charAt(0));
            ws.screenRows = ScreenRowTable.lowValues().withRow(3,
                    new CardListRequest.ScreenRow("10000000003", "0000000000000003", "Y"));

            CardListResponse response = controller.dispatch(new CardListRequest(), ws,
                    new CardListResponse());

            assertThat(response.getNextProgram().trim())
                    .as("the arm requires the caller to be this program, so WHEN OTHER runs instead")
                    .isEmpty();
            assertThat(ws.mapSent).isTrue();
        }

        @Test
        @DisplayName("PF7 reaching arm 5 always has NOT CA-FIRST-PAGE, because arm 2 consumed the rest")
        void armFiveIsOnlyReachableOffTheFirstPage() {
            // Arm 2 at :439-454 matches PF7 AND CA-FIRST-PAGE and returns, so by the time arm 5 tests
            // NOT CA-FIRST-PAGE the answer is already settled. The redundant test is preserved
            // verbatim (practice B5) and its outcome is asserted from both sides through arms 2 and 5.
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest firstPage = showing(0, " ");
            CardListResponse onFirstPage = controller.listCards(firstPage, CicsAid.DFHPF7);
            assertThat(onFirstPage.getErrmsgo().trim()).isEqualTo("NO PREVIOUS PAGES TO DISPLAY");

            backward(15, 14, 13, 12, 11, 10, 9, 8);
            CardListRequest laterPage = showing(0, " ");
            laterPage.setPageCursor(laterPage.getPageCursor()
                    .withScreenNum(3)
                    .withFirstCardKey(new CardKey(String.format("%016d", 15), 10_000_000_015L)));
            CardListResponse onLaterPage = controller.listCards(laterPage, CicsAid.DFHPF7);
            assertThat(onLaterPage.getPageCursor().screenNum()).isEqualTo(2);
        }

        @ParameterizedTest(name = "page {0} {1} 1 stores {2} in a PIC 9(1) item")
        @CsvSource({"1,+,2", "8,+,9", "9,+,0", "3,-,2", "1,-,0", "0,-,1"})
        void screenNumberIsASingleDigit(int current, String sign, int expected) {
            int delta = "+".equals(sign) ? 1 : -1;

            assertThat(CardListController.addToScreenNum(current, delta))
                    .as(":237 WS-CA-SCREEN-NUM PIC 9(1); :492 and :508 carry no ON SIZE ERROR")
                    .isEqualTo(expected);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Row selection - 2250-EDIT-ARRAY and the two transfer arms, :1073-1117, :517-569")
    final class RowSelection {

        @Test
        @DisplayName("'S' on row 1 transfers to the card detail view - :517-541 (gate G40)")
        void viewRequestedOnRowOne() {
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(1, "S"), CicsAid.DFHENTER, ws);

            assertThat(ws.iSelected)
                    .as("the COBOL subscript is 1-based (gate G33)")
                    .isEqualTo(1);
            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA");
            assertThat(response.getNavigationContext().acctId()).isEqualTo(10_000_000_001L);
            assertThat(response.getNavigationContext().cardNum()).isEqualTo(1L);
            assertThat(response.getNavigationContext().isUser())
                    .as(":522 SET CDEMO-USRTYP-USER TO TRUE (practice B6)")
                    .isTrue();
            verifyNoInteractions(browse);
        }

        @Test
        @DisplayName("'U' on row 7 transfers to the card update program - :545-569 (gate G40)")
        void updateRequestedOnRowSeven() {
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(7, "U"), CicsAid.DFHENTER, ws);

            assertThat(ws.iSelected)
                    .as("row 7 is the last OCCURS element, which proves the conversion at both ends")
                    .isEqualTo(7);
            assertThat(response.getNextProgram()).isEqualTo("COCRDUPC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDUP");
            assertThat(response.getNextMap()).isEqualTo("CCRDUPA");
            assertThat(response.getNavigationContext().acctId()).isEqualTo(10_000_000_007L);
            assertThat(response.getNavigationContext().cardNum()).isEqualTo(7L);
        }

        @ParameterizedTest(name = "'S' on row {0} carries that row's own account and card")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        void everyRowCarriesItsOwnKey(int row) {
            CardListResponse response = controller.listCards(showing(row, "S"), CicsAid.DFHENTER);

            assertThat(response.getNavigationContext().acctId())
                    .isEqualTo(10_000_000_000L + row);
            assertThat(response.getNavigationContext().cardNum()).isEqualTo(row);
        }

        @Test
        @DisplayName("an unrecognised action code is an error and reddens only that row - :1108-1113")
        void invalidActionCode() {
            forward(1, 2, 3);

            CardListResponse response = controller.listCards(showing(3, "X"), CicsAid.DFHENTER);

            assertThat(response.getErrmsgo().trim()).isEqualTo("INVALID ACTION CODE");
            assertThat(response.editSelectErrorFlags()).isEqualTo("  1    ");
            assertThat(response.fieldAttributes("CRDSEL3").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getNextProgram().trim()).isEmpty();
        }

        @Test
        @DisplayName("two marked rows are rejected and both are reddened - :1084-1093")
        void twoSelectionsAreRejected() {
            forward(1, 2);
            CardListRequest request = showing(1, "S");
            List<ListRow> rows = new ArrayList<>(request.getRows());
            StopperListRow second = (StopperListRow) rows.get(1);
            rows.set(1, new StopperListRow("U", second.crdStp(), second.acctNo(), second.crdNum(),
                    second.crdSts()));
            request.setRows(rows);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.i)
                    .as("PERFORM VARYING leaves I at 8")
                    .isEqualTo(8);
            assertThat(response.getErrmsgo().trim())
                    .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
            assertThat(response.editSelectErrorFlags()).isEqualTo("1100000");
            assertThat(response.fieldAttributes("CRDSEL1").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.fieldAttributes("CRDSEL2").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getNextProgram().trim())
                    .as("an input error never transfers")
                    .isEmpty();
        }

        @Test
        @DisplayName("I-SELECTED ends up holding the LAST marked row, not the first - :1099-1105")
        void lastMarkedRowWins() {
            forward(1);
            CardListRequest request = showing(2, "S");
            List<ListRow> rows = new ArrayList<>(request.getRows());
            StopperListRow fifth = (StopperListRow) rows.get(4);
            rows.set(4, new StopperListRow("S", fifth.crdStp(), fifth.acctNo(), fifth.crdNum(),
                    fifth.crdSts()));
            request.setRows(rows);
            WorkArea ws = new WorkArea();

            controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.iSelected).isEqualTo(5);
        }

        @Test
        @DisplayName("nothing marked leaves I-SELECTED at zero and reaches WHEN OTHER")
        void nothingMarked() {
            forward(1, 2, 3);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHENTER, ws);

            assertThat(ws.iSelected).isZero();
            assertThat(ws.isViewRequestedOnSelected())
                    .as("subscript 0 is undefined in COBOL; 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7 "
                            + "at :94 is the guard the program itself uses")
                    .isFalse();
            assertThat(ws.isUpdateRequestedOnSelected()).isFalse();
            assertThat(ws.isDetailWasRequested()).isFalse();
            assertThat(response.getNextProgram().trim()).isEmpty();
        }

        @Test
        @DisplayName("a selection made from a foreign program does not transfer - :519, :547")
        void selectionFromAForeignProgramDoesNotTransfer() {
            forward(1);
            CardListRequest request = showing(1, "S");
            request.setNavigationContext(request.getNavigationContext().withFromProgram("COMEN01C"));

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.getNextProgram().trim()).isEmpty();
        }

        @Test
        @DisplayName("a selection on a row holding no card carries zeros, the only defined reading")
        void selectionOnAnEmptyRowCarriesZeros() {
            CardListRequest request = continuing();
            List<ListRow> rows = new ArrayList<>();
            rows.add(new FirstListRow("S", "           ", "                ", " "));
            for (int row = 2; row <= 7; row++) {
                rows.add(new StopperListRow(" ", " ", "           ", "                ", " "));
            }
            request.setRows(rows);
            request.setPageCursor(PageCursor.initialised().withScreenNum(1));

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(response.getNavigationContext().acctId()).isZero();
            assertThat(response.getNavigationContext().cardNum()).isZero();
        }

        @Test
        @DisplayName("a blank row is skipped rather than reported - :1106-1107")
        void blankRowsAreSkipped() {
            forward(1, 2, 3, 4, 5, 6, 7);

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHENTER);

            assertThat(response.editSelectErrorFlags()).isEqualTo("       ");
            assertThat(response.getErrmsgo().trim()).isEqualTo("NO MORE RECORDS TO SHOW");
        }

        @Test
        @DisplayName("a second invalid action code does not overwrite the first message - :1111")
        void secondInvalidCodeKeepsTheFirstMessage() {
            forward(1, 2, 3);
            CardListRequest request = showing(2, "X");
            List<ListRow> rows = new ArrayList<>(request.getRows());
            StopperListRow fifth = (StopperListRow) rows.get(4);
            rows.set(4, new StopperListRow("Z", fifth.crdStp(), fifth.acctNo(), fifth.crdNum(),
                    fifth.crdSts()));
            request.setRows(rows);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(response.editSelectErrorFlags())
                    .as("both rows are marked in error even though only one message is shown")
                    .isEqualTo(" 1  1  ");
            assertThat(response.getErrmsgo().trim())
                    .as("IF WS-ERROR-MSG-OFF guards the move, so the first message stands")
                    .isEqualTo("INVALID ACTION CODE");
            assertThat(ws.i).isEqualTo(8);
        }

        @Test
        @DisplayName("2250-EDIT-ARRAY returns immediately when an earlier editor already failed")
        void editArrayReturnsOnAnEarlierError() {
            WorkArea ws = new WorkArea();
            ws.setInputError();
            ws.selectionFlags = SelectionFlags.spacesFilled().withSelection(2, 'X');

            controller.editArray(ws);

            assertThat(ws.iSelected)
                    .as(":1075-1077 GO TO 2250-EDIT-ARRAY-EXIT, so I-SELECTED is never even zeroed")
                    .isZero();
            assertThat(ws.selectionErrorFlags).isEqualTo(SelectionErrorFlags.none());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("2210-EDIT-ACCOUNT and 2220-EDIT-CARD - the three outcomes each, :1003-1066")
    final class FilterEditing {

        @Test
        @DisplayName("LOW-VALUES is 'not supplied' for the account filter - :1007-1013")
        void accountLowValuesIsBlank() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctIdToLowValues();
            ws.commarea = ws.commarea.withAcctId(42L);

            controller.editAccount(ws);

            assertThat(ws.isFlgAcctfilterBlank()).isTrue();
            assertThat(ws.commarea.acctId())
                    .as(":1011 MOVE ZEROES TO CDEMO-ACCT-ID")
                    .isZero();
        }

        @Test
        @DisplayName("spaces are 'not supplied' for the account filter")
        void accountSpacesIsBlank() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId(" ".repeat(11));

            controller.editAccount(ws);

            assertThat(ws.isFlgAcctfilterBlank()).isTrue();
        }

        @Test
        @DisplayName("eleven zeros are 'not supplied' for the account filter")
        void accountZerosIsBlank() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("0".repeat(11));

            controller.editAccount(ws);

            assertThat(ws.isFlgAcctfilterBlank()).isTrue();
            assertThat(ws.isInputOk()).isTrue();
        }

        @Test
        @DisplayName("a non-numeric account filter is an error and protects the selection column")
        void accountNotNumeric() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("ABCDEFGHIJK");

            controller.editAccount(ws);

            assertThat(ws.isInputError()).isTrue();
            assertThat(ws.isFlgAcctfilterNotOk()).isTrue();
            assertThat(ws.isFlgProtectSelectRowsYes())
                    .as(":1020 SET FLG-PROTECT-SELECT-ROWS-YES TO TRUE")
                    .isTrue();
            assertThat(ws.wsErrorMsg)
                    .startsWith("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(ws.commarea.acctId()).isZero();
        }

        @Test
        @DisplayName("a valid eleven-digit account filter is carried into the commarea - :1027-1028")
        void accountValid() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("10000000042");

            controller.editAccount(ws);

            assertThat(ws.isFlgAcctfilterIsValid()).isTrue();
            assertThat(ws.commarea.acctId()).isEqualTo(10_000_000_042L);
            assertThat(ws.isInputOk()).isTrue();
        }

        @Test
        @DisplayName("LOW-VALUES, spaces and sixteen zeros are all 'not supplied' for the card filter")
        void cardBlankForms() {
            WorkArea lowValues = new WorkArea();
            lowValues.ccWorkArea.setCcCardNumToLowValues();
            controller.editCard(lowValues);
            assertThat(lowValues.isFlgCardfilterBlank()).isTrue();

            WorkArea spaces = new WorkArea();
            spaces.ccWorkArea.setCcCardNum(" ".repeat(16));
            controller.editCard(spaces);
            assertThat(spaces.isFlgCardfilterBlank()).isTrue();

            WorkArea zeros = new WorkArea();
            zeros.ccWorkArea.setCcCardNum("0".repeat(16));
            zeros.commarea = zeros.commarea.withCardNum(9L);
            controller.editCard(zeros);
            assertThat(zeros.isFlgCardfilterBlank()).isTrue();
            assertThat(zeros.commarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("a valid sixteen-digit card filter is carried into the commarea - :1064-1065")
        void cardValid() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcCardNum("0000000000000042");

            controller.editCard(ws);

            assertThat(ws.isFlgCardfilterIsValid()).isTrue();
            assertThat(ws.commarea.cardNum()).isEqualTo(42L);
        }

        @Test
        @DisplayName("both filters bad shows the ACCOUNT message - the card one is guarded, :1056")
        void bothFiltersBadShowsTheAccountMessage() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("ABCDEFGHIJK");
            ws.ccWorkArea.setCcCardNum("NOT-A-CARD-NUMB!");

            controller.editInputs(ws);

            assertThat(ws.isFlgAcctfilterNotOk()).isTrue();
            assertThat(ws.isFlgCardfilterNotOk()).isTrue();
            assertThat(ws.wsErrorMsg)
                    .as("2220-EDIT-CARD moves its message only IF WS-ERROR-MSG-OFF; 2210 has no "
                            + "such guard, so the account message wins")
                    .startsWith("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        }

        @Test
        @DisplayName("a bad card filter alone shows the CARD message")
        void cardFilterAloneShowsTheCardMessage() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcCardNum("NOT-A-CARD-NUMB!");

            controller.editInputs(ws);

            assertThat(ws.wsErrorMsg)
                    .startsWith("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        }

        @Test
        @DisplayName("a valid account filter excludes every record with a different account")
        void accountFilterExcludes() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest request = showing(0, " ");
            request.setAcctsid("10000000003");

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.screenRow(1).rowAcctno()).isEqualTo("10000000003");
            assertThat(response.screenRow(2).isLowValues())
                    .as("only one record survives the filter")
                    .isTrue();
        }

        @Test
        @DisplayName("a valid card filter excludes every record with a different card number")
        void cardFilterExcludes() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest request = showing(0, " ");
            request.setCardsid("0000000000000005");

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.screenRow(1).rowCardNum()).isEqualTo("0000000000000005");
            assertThat(response.screenRow(2).isLowValues()).isTrue();
        }

        @Test
        @DisplayName("9500-FILTER-RECORDS excludes nothing when neither filter was supplied")
        void noFilterExcludesNothing() {
            WorkArea ws = new WorkArea();

            controller.filterRecord(ws, card(3));

            assertThat(ws.isDonotExcludeThisRecord()).isTrue();
        }

        @Test
        @DisplayName("a matching account filter keeps the record and then checks the card filter")
        void matchingAccountThenCard() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("10000000003");
            ws.setFlgAcctfilterIsValid();
            ws.ccWorkArea.setCcCardNum("0000000000000003");
            ws.setFlgCardfilterIsValid();

            controller.filterRecord(ws, card(3));

            assertThat(ws.isDonotExcludeThisRecord()).isTrue();
        }

        @Test
        @DisplayName("a matching account but a mismatched card excludes the record - :1396-1401")
        void matchingAccountMismatchedCard() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("10000000003");
            ws.setFlgAcctfilterIsValid();
            ws.ccWorkArea.setCcCardNum("0000000000000009");
            ws.setFlgCardfilterIsValid();

            controller.filterRecord(ws, card(3));

            assertThat(ws.isExcludeThisRecord()).isTrue();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("1400-SETUP-MESSAGE - a seven-arm ordered EVALUATE, :895-932 (gate G30)")
    final class MessageSelection {

        private WorkArea ws;
        private CardListResponse response;

        @BeforeEach
        void freshArea() {
            ws = new WorkArea();
            response = new CardListResponse();
        }

        @Test
        @DisplayName("arm 1 :898-900 a filter in error keeps its own message - CONTINUE")
        void filterInError() {
            ws.setFlgAcctfilterNotOk();
            ws.setWsErrorMsg(moved("A FILTER MESSAGE"));

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.FILTER_IN_ERROR);
            assertThat(response.getErrmsgo()).startsWith("A FILTER MESSAGE");
        }

        @Test
        @DisplayName("arm 1 is reached by the card filter too")
        void cardFilterInError() {
            ws.setFlgCardfilterNotOk();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.FILTER_IN_ERROR);
        }

        @Test
        @DisplayName("arm 2 :901-904 PF7 on the first page")
        void noPreviousPages() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK07);
            ws.cursor = ws.cursor.withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM);

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.NO_PREVIOUS_PAGES);
            assertThat(response.getErrmsgo().trim()).isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
        }

        @Test
        @DisplayName("arm 3 :905-909 a second PF8 once the last page has already been shown")
        void noMorePages() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK08);
            ws.cursor = ws.cursor.withNextPageNotExists()
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN);

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.NO_MORE_PAGES);
            assertThat(response.getErrmsgo().trim()).isEqualTo("NO MORE PAGES TO DISPLAY");
        }

        @Test
        @DisplayName("arm 4 :910-916 the first PF8 onto the last page flips CA-LAST-PAGE-SHOWN on")
        void lastPageReached() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK08);
            ws.cursor = ws.cursor.withNextPageNotExists()
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.LAST_PAGE_REACHED);
            assertThat(ws.cursor.isLastPageShown())
                    .as(":915 so the NEXT PF8 reaches the arm above")
                    .isTrue();
            assertThat(response.getInfomsgo().trim())
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("arm 5 :917-919 the ordinary row-action prompt")
        void informRecordActions() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
            ws.cursor = ws.cursor.withNextPageExists();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.INFORM_REC_ACTIONS);
            assertThat(response.getInfomsgo().trim())
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("arm 6 :920-921 WHEN OTHER, which 0000-MAIN cannot reach because :669 runs first")
        void whenOtherClearsTheInformationLine() {
            // 1100-SCREEN-INIT sets WS-NO-INFO-MESSAGE at :669, which makes arm 5's first condition
            // always true, so this arm is dead through 1000-SEND-MAP. It is preserved (practice B5)
            // and driven directly, because a paragraph is the smallest thing the source can express.
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
            ws.setWsInfoMsg(CODEC.movePicX("SOMETHING WAS ALREADY HERE",
                    CardListResponse.INFOMSGO_LENGTH));
            ws.cursor = ws.cursor.withNextPageNotExists();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.NO_INFO_MESSAGE);
            assertThat(CardListController.isWsNoInfoMessage(ws)).isTrue();
            assertThat(response.getInfomsgo().trim())
                    .as("the guarded block at :927-930 is skipped when there is no message")
                    .isEmpty();
        }

        @Test
        @DisplayName("the 'no records found' error suppresses the information line - :926")
        void noRecordsFoundSuppressesTheInformationLine() {
            ws.setWsErrorMsg(moved("NO RECORDS FOUND FOR THIS SEARCH CONDITION."));
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
            ws.cursor = ws.cursor.withNextPageExists();

            controller.setupMessage(ws, response);

            assertThat(controller.isWsNoRecordsFound(ws)).isTrue();
            assertThat(response.getInfomsgo().trim()).isEmpty();
            assertThat(response.getErrmsgo())
                    .startsWith("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        }

        @Test
        @DisplayName("PF7 on a later page does not take arm 2")
        void pf7OnALaterPageIsNotArmTwo() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK07);
            ws.cursor = ws.cursor.withScreenNum(3).withNextPageExists();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.INFORM_REC_ACTIONS);
        }

        @Test
        @DisplayName("PF8 with a next page available does not take arms 3 or 4")
        void pf8WithANextPageIsNotArmThreeOrFour() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK08);
            ws.cursor = ws.cursor.withNextPageExists();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.INFORM_REC_ACTIONS);
        }

        @Test
        @DisplayName("arm 4's inner guard does not fire when the flag holds neither 88-level value")
        void armFourInnerGuardNeedsLastPageNotShown() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.PFK08);
            // Neither 0 nor 9, which INITIALIZE cannot produce but a client round-trip can.
            ws.cursor = ws.cursor.withNextPageNotExists().withLastPageDisplayed(5);

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.LAST_PAGE_REACHED);
            assertThat(ws.cursor.lastPageDisplayed())
                    .as(":913-914 guards the flip, so a value that is neither 88-level is left alone")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("arm 5 is also reached by the right-hand side of its OR - :917-918")
        void armFiveViaTheNextPageCondition() {
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
            ws.setWsInfoMsg(CODEC.movePicX("ALREADY SOMETHING",
                    CardListResponse.INFOMSGO_LENGTH));
            ws.cursor = ws.cursor.withNextPageExists();

            controller.setupMessage(ws, response);

            assertThat(ws.messageArm).isEqualTo(MessageArm.INFORM_REC_ACTIONS);
            assertThat(response.getInfomsgo().trim())
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("a second PF8 at the end of the file reports 'no more pages' end to end")
        void secondPf8ReportsNoMorePages() {
            forward();
            CardListRequest request = showing(0, " ");
            request.setPageCursor(request.getPageCursor()
                    .withNextPageNotExists()
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN));

            CardListResponse response = controller.listCards(request, CicsAid.DFHPF8);

            assertThat(response.getErrmsgo().trim()).isEqualTo("NO MORE PAGES TO DISPLAY");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("1100/1200/1250/1300 - the screen-painting paragraphs, :642-889")
    final class ScreenPainting {

        private char attributeOf(CardListRequest request, String label) {
            return request.fieldMetadataOf(label)
                    .map(FieldMetadata::attributeByte)
                    .orElseThrow(() -> new AssertionError("no metadata for " + label));
        }

        @Test
        @DisplayName("1100-SCREEN-INIT reads the clock twice and keeps the second reading - :645, :652")
        void screenInitReadsTheClockTwice() {
            WorkArea ws = new WorkArea();
            CardListResponse response = new CardListResponse();

            controller.screenInit(ws, response);

            assertThat(ws.wsCurdateData)
                    .as(":645's reading is stored and then :652 reads again - both are performed")
                    .isNotEmpty();
            assertThat(ws.dateHeader).isNotNull();
            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(CardListController.isWsNoInfoMessage(ws))
                    .as(":669 SET WS-NO-INFO-MESSAGE TO TRUE")
                    .isTrue();
        }

        @Test
        @DisplayName("1200-SCREEN-ARRAY-INIT skips a row still holding LOW-VALUES - :680 and siblings")
        void clearedRowsAreSkipped() {
            WorkArea ws = new WorkArea();
            ws.screenRows = ScreenRowTable.lowValues()
                    .withRow(2, new CardListRequest.ScreenRow("10000000002",
                            "0000000000000002", "Y"));
            ws.selectionFlags = SelectionFlags.spacesFilled();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.screenArrayInit(ws, response);

            assertThat(response.screenRow(1).isLowValues())
                    .as("row 1 was never filled, so it keeps the LOW-VALUES of :643")
                    .isTrue();
            assertThat(response.screenRow(2).rowAcctno()).isEqualTo("10000000002");
            assertThat(response.screenRow(7).isLowValues()).isTrue();
        }

        @Test
        @DisplayName("1250 gives row 1 DFHBMPRF and rows 2-7 DFHBMPRO when the row is empty")
        void rowOneUsesADifferentProtectedAttribute() {
            WorkArea ws = new WorkArea();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupArrayAttribs(request, ws, response);

            assertThat(attributeOf(request, "CRDSEL1"))
                    .as(":753 row 1 alone uses DFHBMPRF - the first row-1 asymmetry")
                    .isEqualTo((char) (BmsAttributes.DFHBMPRF & 0xFF));
            for (int row = 2; row <= 7; row++) {
                assertThat(attributeOf(request, "CRDSEL" + row))
                        .as("row %d uses DFHBMPRO", row)
                        .isEqualTo((char) (BmsAttributes.DFHBMPRO & 0xFF));
            }
        }

        @Test
        @DisplayName("1250 gives a filled, unprotected row DFHBMFSE so the operator can type in it")
        void filledRowsAreEnterable() {
            WorkArea ws = new WorkArea();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();
            for (int row = 1; row <= 7; row++) {
                response.setScreenRow(row, CardListResponse.ScreenRow.of(
                        String.format("%011d", 10_000_000_000L + row),
                        String.format("%016d", row), "Y"));
            }

            controller.setupArrayAttribs(request, ws, response);

            for (int row = 1; row <= 7; row++) {
                assertThat(attributeOf(request, "CRDSEL" + row))
                        .as("row %d", row)
                        .isEqualTo((char) (BmsAttributes.DFHBMFSE & 0xFF));
            }
        }

        @Test
        @DisplayName("1250 protects every row when a filter failed edit, filled or not")
        void protectedRowsIgnoreTheirContent() {
            WorkArea ws = new WorkArea();
            ws.setFlgProtectSelectRowsYes();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();
            response.setScreenRow(3, CardListResponse.ScreenRow.of("10000000003",
                    "0000000000000003", "Y"));

            controller.setupArrayAttribs(request, ws, response);

            assertThat(attributeOf(request, "CRDSEL3"))
                    .isEqualTo((char) (BmsAttributes.DFHBMPRO & 0xFF));
        }

        @Test
        @DisplayName("1250 aims the cursor at a row in error, but only on rows 2-7 - :770 and siblings")
        void cursorIsAimedAtRowsTwoToSeven() {
            WorkArea ws = new WorkArea();
            ws.selectionErrorFlags = SelectionErrorFlags.none().withRowInError(4);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();
            for (int row = 1; row <= 7; row++) {
                response.setScreenRow(row, CardListResponse.ScreenRow.of("10000000001",
                        "0000000000000001", "Y"));
            }

            controller.setupArrayAttribs(request, ws, response);

            assertThat(ws.cursorField)
                    .as("row 1 moves '*' instead of aiming the cursor - the second asymmetry")
                    .isEqualTo("CRDSEL4");
        }

        @Test
        @DisplayName("1250 on row 1 in error moves '*' and never aims the cursor")
        void rowOneInErrorMovesTheAsterisk() {
            WorkArea ws = new WorkArea();
            ws.selectionErrorFlags = SelectionErrorFlags.none().withRowInError(1);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();
            response.setScreenRow(1, CardListResponse.ScreenRow.of("10000000001",
                    "0000000000000001", "Y"));

            controller.setupArrayAttribs(request, ws, response);

            assertThat(ws.cursorField).isNull();
            assertThat(response.fieldAttributes("CRDSEL1").colour()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("1300 skips both EVALUATEs on a cold start - :839-842")
        void coldStartSkipsTheFilterRepaint() {
            WorkArea ws = new WorkArea();
            ws.eibcalenZero = true;
            ws.commarea = ws.commarea.withAcctId(42L);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido())
                    .as("nothing was painted, so the field keeps the LOW-VALUES of :643")
                    .isEqualTo(String.valueOf('\u0000').repeat(11));
            assertThat(ws.cursorField)
                    .as(":884-886 IF INPUT-OK still parks the cursor on the account filter")
                    .isEqualTo("ACCTSID");
        }

        @Test
        @DisplayName("1300 skips both EVALUATEs on arrival from the menu - :841")
        void arrivalFromTheMenuSkipsTheFilterRepaint() {
            WorkArea ws = new WorkArea();
            ws.commarea = NavigationContext.empty()
                    .withFromProgram(CardListController.LIT_MENUPGM)
                    .withPgmEnter()
                    .withAcctId(42L);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido()).isEqualTo(String.valueOf('\u0000').repeat(11));
        }

        @Test
        @DisplayName("1300 prefers what the operator typed when the filter edited valid - :845-848")
        void validFilterIsEchoedBack() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("10000000042");
            ws.setFlgAcctfilterIsValid();
            ws.ccWorkArea.setCcCardNum("0000000000000042");
            ws.setFlgCardfilterIsValid();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido()).isEqualTo("10000000042");
            assertThat(response.getCardsido()).isEqualTo("0000000000000042");
            assertThat(attributeOf(request, "ACCTSID"))
                    .isEqualTo((char) (BmsAttributes.DFHBMFSE & 0xFF));
            assertThat(attributeOf(request, "CARDSID"))
                    .isEqualTo((char) (BmsAttributes.DFHBMFSE & 0xFF));
        }

        @Test
        @DisplayName("1300 echoes a filter that failed edit and reddens it - :845-848, :872-880")
        void failedFilterIsEchoedAndReddened() {
            WorkArea ws = new WorkArea();
            ws.ccWorkArea.setCcAcctId("ABCDEFGHIJK");
            ws.setFlgAcctfilterNotOk();
            ws.ccWorkArea.setCcCardNum("NOT-A-CARD-NUMB!");
            ws.setFlgCardfilterNotOk();
            ws.setInputError();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido()).isEqualTo("ABCDEFGHIJK");
            assertThat(response.getCardsido()).isEqualTo("NOT-A-CARD-NUMB!");
            assertThat(response.fieldAttributes("ACCTSID").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.fieldAttributes("CARDSID").colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(ws.cursorField)
                    .as(":877-880 the card filter's placement runs after the account filter's, and "
                            + ":884-886 does not run because INPUT-OK is false")
                    .isEqualTo("CARDSID");
        }

        @Test
        @DisplayName("1300 shows nothing when the carried filter is zero - :849-850, :861-862")
        void zeroCarriedFilterShowsNothing() {
            WorkArea ws = new WorkArea();
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();
            response.setAcctsido("99999999999");
            response.setCardsido("9999999999999999");

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido()).isEqualTo(String.valueOf('\u0000').repeat(11));
            assertThat(response.getCardsido()).isEqualTo(String.valueOf('\u0000').repeat(16));
        }

        @Test
        @DisplayName("1300 falls back to the carried filter when there is one - :851-853, :863-866")
        void carriedFilterIsShown() {
            WorkArea ws = new WorkArea();
            ws.commarea = ws.commarea.withAcctId(10_000_000_042L).withCardNum(42L);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido())
                    .as("MOVE CDEMO-ACCT-ID TO ACCTSIDO - 9(11) into X(11)")
                    .isEqualTo("10000000042");
            assertThat(response.getCardsido()).isEqualTo("0000000000000042");
            assertThat(attributeOf(request, "ACCTSID"))
                    .isEqualTo((char) (BmsAttributes.DFHBMFSE & 0xFF));
        }

        @Test
        @DisplayName("1300 repaints on a REENTER arrival, because fromMenu tests CDEMO-PGM-ENTER")
        void reenterArrivalStillRepaints() {
            WorkArea ws = new WorkArea();
            ws.commarea = NavigationContext.empty()
                    .withFromProgram(CardListController.LIT_MENUPGM)
                    .withPgmReenter()
                    .withAcctId(10_000_000_042L);
            CardListRequest request = new CardListRequest();
            CardListResponse response = new CardListResponse();
            response.moveLowValuesToMap();

            controller.setupScreenAttrs(request, ws, response);

            assertThat(response.getAcctsido())
                    .as(":841 needs CDEMO-PGM-ENTER as well as the menu name, so a REENTER arrival "
                            + "from the menu is repainted")
                    .isEqualTo("10000000042");
        }

        @Test
        @DisplayName("1500-SEND-SCREEN records the send's own RESP and attaches both areas")
        void sendScreenRecordsItsResponse() {
            WorkArea ws = new WorkArea();
            ws.wsRespCd = 16;
            ws.wsReasCd = 9;
            ws.lastOutcome = Outcome.OTHER;
            CardListResponse response = new CardListResponse();

            controller.sendScreen(ws, response);

            assertThat(ws.wsRespCd).isEqualTo(FileStatus.NORMAL);
            assertThat(ws.wsReasCd).isZero();
            assertThat(ws.lastOutcome).isEqualTo(Outcome.OK);
            assertThat(ws.mapSent).isTrue();
            assertThat(response.getPageCursor()).isNotNull();
            assertThat(response.getCardScreenState()).isNotNull();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("WS-FILE-ERROR-MESSAGE - exactly eighty characters, :153-171")
    final class FileErrorDiagnostic {

        @Test
        @DisplayName("the nine spans compose to exactly eighty characters, byte for byte")
        void eightyCharactersExactly() {
            WorkArea ws = new WorkArea();
            ws.wsRespCd = 16;
            ws.wsReasCd = 3;

            String message = controller.fileErrorMessage(ws);

            assertThat(message).hasSize(CardListController.FILE_ERROR_MESSAGE_LENGTH);
            assertThat(message).hasSize(80);
            assertThat(message).isEqualTo(
                    "File Error: READ     on CARDDAT   returned RESP 000000016 ,RESP2 000000003      ");
            assertThat(ws.errorOpname).isEqualTo("READ    ");
            assertThat(ws.errorFile).isEqualTo("CARDDAT  ");
            assertThat(ws.errorResp).isEqualTo("000000016 ");
            assertThat(ws.errorResp2).isEqualTo("000000003 ");
        }

        @Test
        @DisplayName("each of the nine spans sits at its declared offset")
        void spansSitAtTheirOffsets() {
            WorkArea ws = new WorkArea();
            ws.wsRespCd = 1;
            ws.wsReasCd = 2;

            String message = controller.fileErrorMessage(ws);

            assertThat(message.substring(0, 12)).isEqualTo("File Error: ");
            assertThat(message.substring(12, 20)).isEqualTo("READ    ");
            assertThat(message.substring(20, 24)).isEqualTo(" on ");
            assertThat(message.substring(24, 33)).isEqualTo("CARDDAT  ");
            assertThat(message.substring(33, 48)).isEqualTo(" returned RESP ");
            assertThat(message.substring(48, 58)).isEqualTo("000000001 ");
            assertThat(message.substring(58, 65)).isEqualTo(",RESP2 ");
            assertThat(message.substring(65, 75)).isEqualTo("000000002 ");
            assertThat(message.substring(75, 80)).isEqualTo("     ");
        }

        @Test
        @DisplayName("nine digits are enough for every response code the browse can report")
        void nineDigitsOfResponseCode() {
            WorkArea ws = new WorkArea();
            ws.wsRespCd = 999_999_999;
            ws.wsReasCd = 123_456_789;

            String message = controller.fileErrorMessage(ws);

            assertThat(message).contains("returned RESP 999999999 ");
            assertThat(message).contains(",RESP2 123456789 ");
            assertThat(message).hasSize(80);
        }

        @Test
        @DisplayName("the receiving WS-ERROR-MSG is PIC X(75), so five spaces are truncated away")
        void theCallerTruncatesToSeventyFive() {
            when(browse.readNext()).thenReturn(CardReadResult.reportedFailure(16, 3));
            WorkArea ws = new WorkArea();

            controller.readForward(ws);

            assertThat(ws.wsErrorMsg).hasSize(75);
            assertThat(ws.wsErrorMsg)
                    .isEqualTo("File Error: READ     on CARDDAT   returned RESP 000000016 ,RESP2 "
                            + "000000003 ");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Statelessness - the whole cursor travels in the payload (rule R6, gate G37)")
    final class Statelessness {

        @Test
        @DisplayName("the controller declares no session state of any kind")
        void noServerSideState() {
            for (Field field : CardListController.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(field.getType().getName())
                            .as("instance field %s must be a collaborator, not state", field.getName())
                            .isIn(CardRepository.class.getName(), FixedWidthCodec.class.getName(),
                                    Clock.class.getName());
                }
                assertThat(Modifier.isStatic(field.getModifiers())
                                && !Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final (gate G53)", field.getName())
                        .isFalse();
            }
            assertThat(CardListController.class.getAnnotations())
                    .noneMatch(annotation -> annotation.annotationType().getSimpleName()
                            .contains("SessionAttributes"));
        }

        @Test
        @DisplayName("two calls on one controller instance do not see each other's work area")
        void callsAreIsolated() {
            WorkArea first = new WorkArea();
            WorkArea second = new WorkArea();

            forward(1, 2, 3, 4, 5, 6, 7, 8);
            controller.listCards(null, CicsAid.DFHENTER, first);
            // The same browse, re-stubbed, so the second call sees the same file as the first.
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, second);

            assertThat(first.wsScrnCounter).isEqualTo(7);
            assertThat(second.wsScrnCounter).isEqualTo(first.wsScrnCounter);
            assertThat(response.screenRow(1).rowCardNum())
                    .as("the second call restarted from the top, not from where the first stopped")
                    .isEqualTo(String.format("%016d", 1));
        }

        @Test
        @DisplayName("CA-NEXT-PAGE-NOT-EXISTS is binary x'00', not a space and not null")
        void lowValuesAreNotSpaces() {
            PageCursor initialised = PageCursor.initialised();
            assertThat(initialised.nextPageInd()).isEqualTo(" ");
            assertThat(initialised.isNextPageNotExists())
                    .as("INITIALIZE leaves a space, which is neither 88-level's value")
                    .isFalse();
            assertThat(initialised.isNextPageExists()).isFalse();

            PageCursor notExists = initialised.withNextPageNotExists();
            assertThat(notExists.nextPageInd()).isEqualTo(String.valueOf('\u0000'));
            assertThat(notExists.isNextPageNotExists()).isTrue();

            PageCursor exists = initialised.withNextPageExists();
            assertThat(exists.nextPageInd()).isEqualTo("Y");
            assertThat(exists.isNextPageExists()).isTrue();
        }

        @Test
        @DisplayName("WS-RETURN-FLAG is declared, never used, and still round-trips - :246-248")
        void returnFlagRoundTrips() {
            forward(1);
            CardListRequest request = showing(0, " ");
            request.setPageCursor(request.getPageCursor().withReturnFlagOn());

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(response.getPageCursor().isReturnFlagOn())
                    .as("the procedure division never reads it, so it must survive untouched "
                            + "(practice B5)")
                    .isTrue();
            assertThat(PageCursor.initialised().withReturnFlagOff().returnFlag())
                    .isEqualTo(String.valueOf('\u0000'));
        }

        @Test
        @DisplayName("the response's cursor and rows drive the next call - the 254-byte area")
        void cursorRoundTripsAcrossCalls() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse first = controller.listCards(null, CicsAid.DFHENTER);

            CardListRequest second = new CardListRequest();
            second.setNavigationContext(first.getNavigationContext());
            second.setPageCursor(first.getPageCursor());
            List<ListRow> carried = new ArrayList<>();
            for (int row = 1; row <= 7; row++) {
                CardListResponse.ScreenRow shown = first.screenRow(row);
                carried.add(row == 1
                        ? new FirstListRow(" ", shown.rowAcctno(), shown.rowCardNum(),
                                shown.rowCardStatus())
                        : new StopperListRow(" ", " ", shown.rowAcctno(), shown.rowCardNum(),
                                shown.rowCardStatus()));
            }
            second.setRows(carried);

            forward(8, 9, 10, 11, 12, 13, 14, 15);
            CardListResponse paged = controller.listCards(second, CicsAid.DFHPF8);

            assertThat(paged.getPageCursor().screenNum()).isEqualTo(2);
            assertThat(paged.screenRow(1).rowCardNum()).isEqualTo(String.format("%016d", 8));
        }

        @Test
        @DisplayName("a selection made on a carried row still resolves its account and card")
        void carriedRowsFeedTheTransfer() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse first = controller.listCards(null, CicsAid.DFHENTER);

            CardListRequest second = new CardListRequest();
            second.setNavigationContext(first.getNavigationContext());
            second.setPageCursor(first.getPageCursor());
            List<ListRow> carried = new ArrayList<>();
            for (int row = 1; row <= 7; row++) {
                CardListResponse.ScreenRow shown = first.screenRow(row);
                String typed = row == 4 ? "S" : " ";
                carried.add(row == 1
                        ? new FirstListRow(typed, shown.rowAcctno(), shown.rowCardNum(),
                                shown.rowCardStatus())
                        : new StopperListRow(typed, " ", shown.rowAcctno(), shown.rowCardNum(),
                                shown.rowCardStatus()));
            }
            second.setRows(carried);

            CardListResponse transferred = controller.listCards(second, CicsAid.DFHENTER);

            assertThat(transferred.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(transferred.getNavigationContext().acctId()).isEqualTo(10_000_000_004L);
            assertThat(transferred.getNavigationContext().cardNum()).isEqualTo(4L);
        }

        @Test
        @DisplayName("restoreScreenRowTable keeps an unwritten row at LOW-VALUES, not at spaces")
        void restoreKeepsUnwrittenRowsEmpty() {
            CardListRequest request = new CardListRequest();
            List<ListRow> mixed = new ArrayList<>();
            mixed.add(new FirstListRow(" ", "10000000001", "0000000000000001", "Y"));
            for (int row = 2; row <= 7; row++) {
                mixed.add(new StopperListRow(" ", " ", "           ", "                ", " "));
            }
            request.setRows(mixed);

            ScreenRowTable restored = controller.restoreScreenRowTable(request);

            assertThat(restored.row(1).isCleared()).isFalse();
            assertThat(restored.row(1).acctNo()).isEqualTo("10000000001");
            for (int row = 2; row <= 7; row++) {
                assertThat(restored.row(row).isCleared())
                        .as("row %d was never written", row)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("restoreScreenRowTable keeps a row that carries any one of its three members")
        void restoreKeepsAPartiallyFilledRow() {
            CardListRequest onlyCardNumber = new CardListRequest();
            List<ListRow> cardOnly = new ArrayList<>();
            cardOnly.add(new FirstListRow(" ", "           ", "0000000000000001", " "));
            for (int row = 2; row <= 7; row++) {
                cardOnly.add(new StopperListRow(" ", " ", "           ", "                ", " "));
            }
            onlyCardNumber.setRows(cardOnly);

            assertThat(controller.restoreScreenRowTable(onlyCardNumber).row(1).isCleared())
                    .as("a row carrying only a card number was still written")
                    .isFalse();

            CardListRequest onlyStatus = new CardListRequest();
            List<ListRow> statusOnly = new ArrayList<>();
            statusOnly.add(new FirstListRow(" ", "           ", "                ", "Y"));
            for (int row = 2; row <= 7; row++) {
                statusOnly.add(new StopperListRow(" ", " ", "           ", "                ", " "));
            }
            onlyStatus.setRows(statusOnly);

            assertThat(controller.restoreScreenRowTable(onlyStatus).row(1).isCleared())
                    .as("a row carrying only a status was still written")
                    .isFalse();
        }

        @Test
        @DisplayName("INITIALIZE fills the row table with spaces, which is not LOW-VALUES")
        void initializeIsSpacesNotLowValues() {
            ScreenRowTable initialised = CardListController.initializedScreenRowTable();

            assertThat(initialised.rows()).hasSize(7);
            for (int row = 1; row <= 7; row++) {
                assertThat(initialised.row(row).acctNo()).isEqualTo(" ".repeat(11));
                assertThat(initialised.row(row).cardNum()).isEqualTo(" ".repeat(16));
                assertThat(initialised.row(row).cardStatus()).isEqualTo(" ");
                assertThat(initialised.row(row).isCleared())
                        .as("spaces are not LOW-VALUES, so :680's test does not fire")
                        .isFalse();
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Helpers and the WORKING-STORAGE condition names (gate G50)")
    final class Helpers {

        @Test
        @DisplayName("isEvery treats an empty value as vacuously uniform")
        void isEveryEdgeCases() {
            assertThat(CardListController.isEvery("", 'x')).isTrue();
            assertThat(CardListController.isEvery("aaa", 'a')).isTrue();
            assertThat(CardListController.isEvery("aab", 'a')).isFalse();
            assertThat(CardListController.isEvery("baa", 'a')).isFalse();
        }

        @Test
        @DisplayName("isBlankOrLowValues accepts spaces and binary zeros and nothing else")
        void blankOrLowValues() {
            assertThat(CardListController.isBlankOrLowValues("   ")).isTrue();
            assertThat(CardListController.isBlankOrLowValues("\u0000\u0000")).isTrue();
            assertThat(CardListController.isBlankOrLowValues("")).isTrue();
            assertThat(CardListController.isBlankOrLowValues(" A ")).isFalse();
            assertThat(CardListController.isBlankOrLowValues(" \u0000"))
                    .as("a mixture is neither, which is what a COBOL comparison would report")
                    .isFalse();
        }

        @Test
        @DisplayName("isWsNoInfoMessage is true for spaces and for LOW-VALUES")
        void noInfoMessage() {
            WorkArea ws = new WorkArea();
            assertThat(CardListController.isWsNoInfoMessage(ws)).isTrue();

            ws.setWsInfoMsg("\u0000".repeat(CardListResponse.INFOMSGO_LENGTH));
            assertThat(CardListController.isWsNoInfoMessage(ws)).isTrue();

            ws.setWsInfoMsg(CODEC.movePicX("SOMETHING", CardListResponse.INFOMSGO_LENGTH));
            assertThat(CardListController.isWsNoInfoMessage(ws)).isFalse();
        }

        @Test
        @DisplayName("the two error-message condition names test the field's content")
        void errorMessageConditionNames() {
            WorkArea ws = new WorkArea();
            assertThat(controller.isWsNoRecordsFound(ws)).isFalse();
            assertThat(controller.isWsMoreThan1Action(ws)).isFalse();

            ws.setWsErrorMsg(moved("NO RECORDS FOUND FOR THIS SEARCH CONDITION."));
            assertThat(controller.isWsNoRecordsFound(ws)).isTrue();

            ws.setWsErrorMsg(moved("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE"));
            assertThat(controller.isWsMoreThan1Action(ws)).isTrue();
            assertThat(controller.isWsNoRecordsFound(ws)).isFalse();
        }

        @ParameterizedTest(name = "row {0} maps to CRDSEL{0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        void rowSelectionLabels(int row) {
            assertThat(CardListController.rowSelectionLabel(row)).isEqualTo("CRDSEL" + row);
        }

        @Test
        @DisplayName("fieldLabel strips the output suffix from a map item name")
        void fieldLabels() {
            assertThat(CardListController.fieldLabel(CardListResponse.ACCTSIDO_ITEM))
                    .isEqualTo("ACCTSID");
            assertThat(CardListController.fieldLabel(CardListResponse.CARDSIDO_ITEM))
                    .isEqualTo("CARDSID");
        }

        @ParameterizedTest(name = "\"{0}\" at width {1} reads as {2}")
        @CsvSource({"10000000042,11,10000000042", "00000000000,11,0", "ABCDEFGHIJK,11,0",
                    "1000000004 ,11,0", "1000000042,11,0", "0000000000000042,16,42"})
        void digitsOfReadsOnlyDigits(String image, int width, long expected) {
            assertThat(CardListController.digitsOf(image, width))
                    .as("a numeric item receiving non-digits is undefined in COBOL; zero is the "
                            + "only defined reading and is what the program itself moves")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("putFieldAttribute keeps the length CICS reported and replaces the attribute")
        void putFieldAttributeKeepsTheReportedLength() {
            CardListRequest request = new CardListRequest();
            request.putFieldMetadata(new FieldMetadata("ACCTSID", 11, ' '));

            CardListController.putFieldAttribute(request, "ACCTSID", BmsAttributes.DFHBMFSE);

            FieldMetadata stored = request.fieldMetadataOf("ACCTSID").orElseThrow();
            assertThat(stored.inputLength()).isEqualTo(11);
            assertThat(stored.attributeByte()).isEqualTo((char) (BmsAttributes.DFHBMFSE & 0xFF));
        }

        @Test
        @DisplayName("putFieldAttribute reports length zero for a field CICS never mentioned")
        void putFieldAttributeDefaultsTheLength() {
            CardListRequest request = new CardListRequest();

            CardListController.putFieldAttribute(request, "CRDSEL5", BmsAttributes.DFHBMPRO);

            FieldMetadata stored = request.fieldMetadataOf("CRDSEL5").orElseThrow();
            assertThat(stored.inputLength())
                    .as("CICS reports 0 for a field the operator did not touch, and FieldMetadata "
                            + "forbids the -1 the source moves - the limitation is recorded, not hidden")
                    .isZero();
            assertThat(stored.isUntouched()).isTrue();
        }

        @Test
        @DisplayName("placeCursor records the field the MOVE -1 was aimed at")
        void placeCursorRecordsTheTarget() {
            WorkArea ws = new WorkArea();
            assertThat(ws.cursorField).isNull();

            CardListController.placeCursor(ws, "CRDSEL2");

            assertThat(ws.cursorField).isEqualTo("CRDSEL2");
        }

        @Test
        @DisplayName("transferred attaches the three areas an XCTL makes available")
        void transferredAttachesTheAreas() {
            WorkArea ws = new WorkArea();
            ws.commarea = ws.commarea.withToProgram("COCRDSLC");
            ws.ccWorkArea.setCcardNextProg("COCRDSLC");
            CardListResponse response = new CardListResponse();

            CardListResponse returned = CardListController.transferred(ws, response);

            assertThat(returned).isSameAs(response);
            assertThat(returned.getNavigationContext().toProgram()).isEqualTo("COCRDSLC");
            assertThat(returned.getCardScreenState().getCcardNextProg()).isEqualTo("COCRDSLC");
            assertThat(returned.getPageCursor()).isNotNull();
        }

        @Test
        @DisplayName("recordResponse keeps the last record returned, not the last read attempted")
        void recordResponseKeepsTheLastRecord() {
            WorkArea ws = new WorkArea();

            CardListController.recordResponse(ws, CardReadResult.normal(card(5)));
            assertThat(ws.lastCardRead.cardAcctId()).isEqualTo(10_000_000_005L);

            CardListController.recordResponse(ws, CardReadResult.endOfFile());
            assertThat(ws.lastCardRead)
                    .as(":1236-1237 reads CARD-RECORD after ENDFILE, which still holds card 5")
                    .isNotNull();
            assertThat(ws.lastCardRead.cardAcctId()).isEqualTo(10_000_000_005L);
            assertThat(ws.lastOutcome).isEqualTo(Outcome.END_OF_FILE);
        }

        @Test
        @DisplayName("keyOf takes the card number at its declared width and the account id as digits")
        void keyOfProjectsTheTwoKeyMembers() {
            CardKey key = controller.keyOf(card(6));

            assertThat(key.cardNum()).isEqualTo(String.format("%016d", 6));
            assertThat(key.acctId()).isEqualTo(10_000_000_006L);
        }

        @Test
        @DisplayName("writeRow projects only three of the fifteen record members")
        void writeRowProjectsThreeMembers() {
            WorkArea ws = new WorkArea();

            controller.writeRow(ws, 3, card(9));

            assertThat(ws.screenRows.row(3).acctNo()).isEqualTo("10000000009");
            assertThat(ws.screenRows.row(3).cardNum()).isEqualTo(String.format("%016d", 9));
            assertThat(ws.screenRows.row(3).cardStatus()).isEqualTo("Y");
            assertThat(ws.screenRows.row(3).acctNo() + ws.screenRows.row(3).cardNum()
                    + ws.screenRows.row(3).cardStatus())
                    .as("28 bytes, which is 196 / 7")
                    .hasSize(28);
        }

        @Test
        @DisplayName("selectionCharOf takes the first character, and a space for an absent one")
        void selectionCharacter() {
            CardListRequest request = showing(2, "S");

            assertThat(controller.selectionCharOf(request, 2)).isEqualTo('S');
            assertThat(controller.selectionCharOf(request, 3)).isEqualTo(' ');
        }

        @Test
        @DisplayName("isThisProgram compares at the declared PIC X(8) width")
        void isThisProgramComparesAtWidth() {
            assertThat(controller.isThisProgram(
                    NavigationContext.empty().withFromProgram("COCRDLIC"))).isTrue();
            assertThat(controller.isThisProgram(
                    NavigationContext.empty().withFromProgram("COMEN01C"))).isFalse();
            assertThat(controller.isThisProgram(NavigationContext.empty()))
                    .as("an empty name is spaces, which is not this program")
                    .isFalse();
        }

        @Test
        @DisplayName("applyInputErrorReturnState performs all seven moves of :423-430")
        void inputErrorReturnState() {
            WorkArea ws = new WorkArea();
            ws.setWsErrorMsg(moved("SOMETHING WENT WRONG"));

            controller.applyInputErrorReturnState(ws);

            assertThat(ws.ccWorkArea.getCcardErrorMsg()).isEqualTo(moved("SOMETHING WENT WRONG"));
            assertThat(ws.commarea.fromProgram()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.commarea.lastMapset()).isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.commarea.lastMap()).isEqualTo(CardListController.LIT_THISMAP);
            assertThat(ws.ccWorkArea.getCcardNextProg()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.ccWorkArea.getCcardNextMapset())
                    .isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.ccWorkArea.getCcardNextMap()).isEqualTo(CardListController.LIT_THISMAP);
        }

        @Test
        @DisplayName("every WORKING-STORAGE condition name reports both of its states")
        void everyConditionNameHasBothStates() {
            WorkArea ws = new WorkArea();

            ws.setInputOk();
            assertThat(ws.isInputOk()).isTrue();
            assertThat(ws.isInputError()).isFalse();
            ws.setInputError();
            assertThat(ws.isInputError()).isTrue();
            assertThat(ws.isInputOk()).isFalse();

            ws.setFlgAcctfilterBlank();
            assertThat(ws.isFlgAcctfilterBlank()).isTrue();
            assertThat(ws.isFlgAcctfilterIsValid()).isFalse();
            assertThat(ws.isFlgAcctfilterNotOk()).isFalse();
            ws.setFlgAcctfilterIsValid();
            assertThat(ws.isFlgAcctfilterIsValid()).isTrue();
            assertThat(ws.isFlgAcctfilterBlank()).isFalse();
            ws.setFlgAcctfilterNotOk();
            assertThat(ws.isFlgAcctfilterNotOk()).isTrue();
            assertThat(ws.isFlgAcctfilterIsValid()).isFalse();

            ws.setFlgCardfilterBlank();
            assertThat(ws.isFlgCardfilterBlank()).isTrue();
            assertThat(ws.isFlgCardfilterIsValid()).isFalse();
            assertThat(ws.isFlgCardfilterNotOk()).isFalse();
            ws.setFlgCardfilterIsValid();
            assertThat(ws.isFlgCardfilterIsValid()).isTrue();
            ws.setFlgCardfilterNotOk();
            assertThat(ws.isFlgCardfilterNotOk()).isTrue();
            assertThat(ws.isFlgCardfilterBlank()).isFalse();

            ws.setFlgProtectSelectRowsNo();
            assertThat(ws.isFlgProtectSelectRowsNo()).isTrue();
            assertThat(ws.isFlgProtectSelectRowsYes()).isFalse();
            ws.setFlgProtectSelectRowsYes();
            assertThat(ws.isFlgProtectSelectRowsYes()).isTrue();
            assertThat(ws.isFlgProtectSelectRowsNo()).isFalse();

            ws.setPfkValid();
            assertThat(ws.isPfkValid()).isTrue();
            assertThat(ws.isPfkInvalid()).isFalse();
            ws.setPfkInvalid();
            assertThat(ws.isPfkInvalid()).isTrue();
            assertThat(ws.isPfkValid()).isFalse();

            ws.setDonotExcludeThisRecord();
            assertThat(ws.isDonotExcludeThisRecord()).isTrue();
            assertThat(ws.isExcludeThisRecord()).isFalse();
            ws.setExcludeThisRecord();
            assertThat(ws.isExcludeThisRecord()).isTrue();
            assertThat(ws.isDonotExcludeThisRecord()).isFalse();

            ws.setMoreRecordsToRead();
            assertThat(ws.isMoreRecordsToRead()).isTrue();
            assertThat(ws.isReadLoopExit()).isFalse();
            ws.setReadLoopExit();
            assertThat(ws.isReadLoopExit()).isTrue();
            assertThat(ws.isMoreRecordsToRead()).isFalse();

            ws.setWsErrorMsg(moved("A MESSAGE"));
            assertThat(ws.isWsErrorMsgOff()).isFalse();
            ws.setWsErrorMsgOff();
            assertThat(ws.isWsErrorMsgOff()).isTrue();
        }

        @Test
        @DisplayName("88 INPUT-OK names three values, and LOW-VALUES is the third - :57-59")
        void inputOkNamesThreeValues() {
            WorkArea ws = new WorkArea();

            ws.wsInputFlag = '0';
            assertThat(ws.isInputOk()).isTrue();
            ws.wsInputFlag = ' ';
            assertThat(ws.isInputOk()).isTrue();
            ws.wsInputFlag = '\u0000';
            assertThat(ws.isInputOk())
                    .as("VALUES '0' ' ' LOW-VALUES - all three, and LOW-VALUES is what INITIALIZE "
                            + "of a redefined span can leave behind")
                    .isTrue();
            ws.wsInputFlag = '1';
            assertThat(ws.isInputOk()).isFalse();
            assertThat(ws.isInputError()).isTrue();
        }

        @Test
        @DisplayName("a row marked 'S' requests a view and not an update, and vice versa")
        void viewAndUpdateAreDistinct() {
            WorkArea view = new WorkArea();
            view.iSelected = 3;
            view.selectionFlags = SelectionFlags.spacesFilled().withSelection(3, 'S');
            assertThat(view.isDetailWasRequested()).isTrue();
            assertThat(view.isViewRequestedOnSelected()).isTrue();
            assertThat(view.isUpdateRequestedOnSelected())
                    .as("arm 7 is evaluated only when arm 6 did not match, so both readings matter")
                    .isFalse();

            WorkArea update = new WorkArea();
            update.iSelected = 6;
            update.selectionFlags = SelectionFlags.spacesFilled().withSelection(6, 'U');
            assertThat(update.isUpdateRequestedOnSelected()).isTrue();
            assertThat(update.isViewRequestedOnSelected()).isFalse();
        }

        @Test
        @DisplayName("the two filter states are expressed in the shared FieldValidationState vocabulary")
        void filterStatesUseTheSharedVocabulary() {
            WorkArea ws = new WorkArea();

            ws.setFlgAcctfilterNotOk();
            assertThat(ws.acctFilterState().notOk()).isTrue();
            ws.setFlgAcctfilterBlank();
            assertThat(ws.acctFilterState().notOk()).isFalse();

            ws.setFlgCardfilterNotOk();
            assertThat(ws.cardFilterState().notOk()).isTrue();
            ws.setFlgCardfilterIsValid();
            assertThat(ws.cardFilterState().notOk()).isFalse();
        }

        @Test
        @DisplayName("the seven message arms are all distinct and start at NONE")
        void messageArms() {
            assertThat(new WorkArea().messageArm).isEqualTo(MessageArm.NONE);
            assertThat(MessageArm.values()).hasSize(7);
            assertThat(MessageArm.valueOf("NO_INFO_MESSAGE")).isEqualTo(MessageArm.NO_INFO_MESSAGE);
        }

        @Test
        @DisplayName("a work area is required rather than defaulted")
        void workAreaIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> controller.listCards(null, CicsAid.DFHENTER, null))
                    .withMessageContaining("work area");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The HTTP contract only - route, status, media type and JSON shape")
    final class HttpContract {

        private MockMvc mockMvc;
        private ObjectMapper json;

        @BeforeEach
        void standaloneSetup() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
            json = new ObjectMapper();
        }

        @Test
        @DisplayName("GET /api/cards answers 200 with the painted map as JSON")
        void routeAnswersJson() throws Exception {
            forward(1, 2, 3);

            mockMvc.perform(get(CardListController.CARD_LIST_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.trnnameo").value("CCLI"))
                    .andExpect(jsonPath("$.pgmnameo").value("COCRDLIC"))
                    .andExpect(jsonPath("$.curdateo").value("07/19/22"));
        }

        @Test
        @DisplayName("the eibaid parameter selects the key, and PF3 names the menu as the next target")
        void eibaidParameterSelectsTheKey() throws Exception {
            String body = json.writeValueAsString(showing(0, " "));

            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM,
                                    String.valueOf(CicsAid.DFHPF3 & 0xFF))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COMEN01C"));
        }

        @Test
        @DisplayName("an out-of-range eibaid is rejected with 400 and echoes no value back")
        void outOfRangeEibaidIsRejected() throws Exception {
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM, "300"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an eibaid that is not a number is rejected with 400")
        void nonNumericEibaidIsRejected() throws Exception {
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM, "ENTER"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("no metadata item reaches the JSON payload (gate G9)")
        void metadataIsNotAPayloadMember() throws Exception {
            forward(1);
            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            String serialised = json.writeValueAsString(response);

            assertThat(serialised)
                    .as("xxxL, xxxF, xxxA, xxxC, xxxP, xxxH and xxxV are validation and highlight "
                            + "metadata, never payload members")
                    .doesNotContain("\"acctsidl\"")
                    .doesNotContain("\"acctsidf\"")
                    .doesNotContain("\"acctsida\"")
                    .doesNotContain("\"crdsel1l\"")
                    .doesNotContain("\"crdsel1f\"")
                    .doesNotContain("\"crdsel1a\"")
                    .doesNotContain("\"fieldMetadata\"")
                    .doesNotContain("\"screenRowTable\"");
            assertThat(serialised)
                    .as("the output items ARE payload members")
                    .contains("\"acctsido\"")
                    .contains("\"cardsido\"")
                    .contains("\"pagenoo\"")
                    .contains("\"errmsgo\"")
                    .contains("\"infomsgo\"");
        }

        @Test
        @DisplayName("COCRDLI's own widths are used, not the sibling card maps' widths")
        void mapSpecificWidths() {
            assertThat(CardListResponse.PAGENOO_LENGTH)
                    .as("PAGENO X(3)")
                    .isEqualTo(3);
            assertThat(CardListResponse.INFOMSGO_LENGTH)
                    .as("INFOMSG X(45) - COCRDSL and COCRDUP use 40")
                    .isEqualTo(45);
            assertThat(CardListResponse.ERRMSGO_LENGTH)
                    .as("ERRMSG X(78) - COCRDSL and COCRDUP use 80")
                    .isEqualTo(78);
        }

        @Test
        @DisplayName("the painted page number is left-justified in three characters")
        void pageNumberIsAlphanumeric() {
            forward(1, 2, 3);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getPagenoo())
                    .as("PAGENOO is PIC X(3), so a one-digit page pads on the right")
                    .isEqualTo("1  ");
            assertThat(visible(response.getPagenoo())).isEqualTo("1  ");
        }
    }
}
