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
import com.vsergeychik.carddemo.card.dto.CardListResponse.MapField;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * (practice B10, gate G51). {@code MockMvc} is confined to three nested classes and each of them
 * asserts a property that is only observable over HTTP and nowhere else: {@code HttpContract} covers
 * the route, status, media type and JSON shape; {@code PageSizeIsNotExternallyTunable} covers the
 * absence of any header or query parameter that could move the page size; and
 * {@code NavigationIsClientDriven} covers the absence of a redirect, a {@code Location} header, a
 * server-side forward and a servlet session. No business decision is asserted through any of them.
 *
 * <p><strong>Provenance of the expected values.</strong> COBOL cannot be executed in this environment
 * (AAP risk R-A: no z/OS, no CICS emulator, GnuCOBOL's indexed handler disabled, the three IBM-supplied
 * copybooks absent), so every expectation below is <em>statically derived</em> from
 * {@code app/cbl/COCRDLIC.cbl}, {@code app/cpy-bms/COCRDLI.CPY}, {@code app/bms/COCRDLI.bms},
 * {@code app/cpy/CVCRD01Y.cpy}, {@code app/cpy/CVACT02Y.cpy}, {@code app/cpy/CSSTRPFY.cpy} and
 * {@code app/csd/CARDDEMO.CSD}, and is cited to the source line it comes from. No captured baseline is
 * claimed (practice B12).
 *
 * <p>Those files are <strong>reference material only</strong> (practice B3). They are named in comments
 * so a reviewer can read a test beside the statement it came from, and not one of them is opened at
 * runtime: this suite performs no file, classpath-resource or network read of any kind, and it writes
 * nothing anywhere.
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
 *
 * <p><strong>The two preserved defects (practice B5).</strong> Both are asserted here and neither is
 * corrected, because correcting either would change observable behaviour:
 * <ol>
 *   <li><strong>The duplicated {@code PF7}-on-the-first-page {@code WHEN}</strong>
 *       [{@code app/cbl/COCRDLIC.cbl:439-454}]. {@code :439-440} declares
 *       {@code WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE} with an <em>empty</em> body and {@code :444-445}
 *       declares the identical condition again, this time carrying the body. Consecutive {@code WHEN}
 *       phrases share the statements that follow them, so the comment at {@code :441-443} - "PAGE UP -
 *       PF7 - BUT ALREADY ON FIRST PAGE" - reads as though the arm were meant to do nothing, while what
 *       actually runs is a fresh forward read from {@code WS-CA-FIRST-CARD-NUM} and a repaint. The
 *       behaviour, not the comment's intent, is what {@code Dispatcher} asserts - and the same
 *       duplication is why one branch of arm 5 at {@code :501-502} is unreachable, which
 *       {@code Dispatcher} also records.</li>
 *   <li><strong>The mapset/map mismatch on the {@code PF3}-to-menu path</strong>
 *       [{@code app/cbl/COCRDLIC.cbl:394-395}]. {@code :394} moves {@code LIT-MENUMAPSET}
 *       ({@code 'COMEN01'}) into {@code CCARD-NEXT-MAPSET} but {@code :395} moves {@code LIT-THISMAP}
 *       ({@code 'CCRDLIA'}) - <em>this</em> program's own map - into {@code CCARD-NEXT-MAP}, where the
 *       menu's own map {@code LIT-MENUMAP} ({@code 'COMEN1A'}) is declared at {@code :195-196} and never
 *       used. The pair disagrees in the source and is asserted disagreeing.</li>
 * </ol>
 *
 * <p><strong>Gates.</strong> This suite is the acceptance evidence for
 * <strong>G9</strong> (every payload field traces to a {@code DFHMDF} entry - see
 * {@code SymbolicMapPayloadContract}), <strong>G30</strong> (the {@code EVALUATE} arms in source order
 * with {@code WHEN OTHER} last), <strong>G33</strong> (the 1-based {@code OCCURS} tables converted at
 * both ends - see {@code OccursSubscriptBoundaries}), <strong>G37</strong> (no server-side state - see
 * {@code Statelessness} and {@code ClientMustReturnTheCursor}), <strong>G38</strong> (both
 * {@code ENTER} and {@code REENTER}, with the error highlight only on re-entry), <strong>G39</strong>
 * (page size exactly 7 and not externally tunable - see {@code PageSizeIsBehaviour} and
 * {@code PageSizeIsNotExternallyTunable}), <strong>G40</strong> (all three {@code XCTL} sites become a
 * {@code nextProgram} response field - see {@code NavigationIsClientDriven}), <strong>G47</strong>
 * (every {@code FileStatus} outcome per browse call site), <strong>G50</strong> (both states of every
 * {@code 88}-level), <strong>G51</strong>, <strong>G52</strong> (no wildcard imports),
 * <strong>G53</strong> (no static mutable state) and <strong>G54</strong> (the suite is
 * non-interactive and order-independent).
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
        return cardRead(card(n));
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
        @DisplayName("every query parameter this handler binds is an EIBAID spelling, so none names a "
                + "page size")
        void noRequestParameterCanChangeThePageSize() throws ReflectiveOperationException {
            Method handler = CardListController.class.getDeclaredMethod("getCards",
                    CardListRequest.class, Integer.class, Integer.class);

            // Named exhaustively rather than counted. The count moved once already, when the second
            // accepted spelling of the EIBAID parameter was bound, and a count assertion would have
            // failed for that without saying anything about the page size - which is what gate G39 is
            // actually about. Every bound query parameter is enumerated instead, and the set may contain
            // only the two AID names.
            assertThat(handler.getParameterCount()).isEqualTo(3);
            List<String> bound = Arrays.stream(handler.getParameters())
                    .map(parameter -> parameter.getAnnotation(RequestParam.class))
                    .filter(java.util.Objects::nonNull)
                    .map(RequestParam::name)
                    .toList();

            assertThat(bound)
                    .as("the only query parameters are the two accepted spellings of the EIBAID byte")
                    .containsExactlyElementsOf(AidRequestParameter.ACCEPTED_NAMES);
            assertThat(bound).contains(CardListController.EIBAID_PARAM);
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
            when(browse.readNext()).thenReturn(cardReadDuplicate(card(4)),
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
                    normal(6), normal(7), cardReadDuplicate(card(8)));
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
            when(browse.readPrev()).thenReturn(cardReadDuplicate(card(15)),
                    cardReadDuplicate(card(14)), CardReadResult.endOfFile());
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageThree(), CicsAid.DFHPF7, ws);

            assertThat(response.screenRow(7).rowCardNum()).isEqualTo(String.format("%016d", 14));
            assertThat(ws.wsScrnCounter).isEqualTo(6);
        }

        @Test
        @DisplayName("a duplicate-key first read decrements, then normal reads fill the page")
        void duplicateKeyFirstReadThenNormalReads() {
            when(browse.readPrev()).thenReturn(cardReadDuplicate(card(15)), normal(14),
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

            CardListController.recordResponse(ws, cardRead(card(5)));
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
        @DisplayName("the alternate spelling of the AID parameter names the same key, so a caller coming "
                + "from the card-detail screen is not silently given ENTER")
        void theAlternateAidSpellingSelectsTheSameKey() throws Exception {
            String body = json.writeValueAsString(showing(0, " "));
            String pf3 = String.valueOf(CicsAid.DFHPF3 & 0xFF);

            // GET /api/cards/{cardNum} declared the alternate spelling, so a client that learned the name
            // there and then listed cards had its PF3 discarded by Spring and the list repainted instead.
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(AidRequestParameter.ALTERNATE_NAME, pf3)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COMEN01C"));

            // Both together, agreeing, are one statement made twice.
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(AidRequestParameter.CANONICAL_NAME, pf3)
                            .param(AidRequestParameter.ALTERNATE_NAME, pf3)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COMEN01C"));
        }

        @Test
        @DisplayName("the two AID spellings carrying different keys is refused with 400")
        void contradictoryAidSpellingsAreRejected() throws Exception {
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(AidRequestParameter.CANONICAL_NAME,
                                    String.valueOf(CicsAid.DFHPF3 & 0xFF))
                            .param(AidRequestParameter.ALTERNATE_NAME,
                                    String.valueOf(CicsAid.DFHPF8 & 0xFF)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an out-of-range eibaid is rejected with 400 and echoes no value back, through "
                + "either spelling")
        void outOfRangeEibaidIsRejected() throws Exception {
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM, "300"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM_ALIAS, "300"))
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
        @DisplayName("the reply is the screen unwrapped, with its metadata beside it under screenMetadata")
        void theEnvelopeCarriesTheMetadataBesideTheScreen() throws Exception {
            forward(1, 2, 3);

            mockMvc.perform(get(CardListController.CARD_LIST_PATH))
                    .andExpect(status().isOk())
                    // The screen is still at the top level, so no client field moves.
                    .andExpect(jsonPath("$.trnnameo").value("CCLI"))
                    .andExpect(jsonPath("$.acctsido").exists())
                    // Finding F3: the 45 quads and the cursor request are published rather than dropped.
                    .andExpect(jsonPath("$.screenMetadata.fields.ACCTSID.colour").exists())
                    .andExpect(jsonPath("$.screenMetadata.fields.CRDSEL1.protection").exists())
                    .andExpect(jsonPath("$.screenMetadata.messageColour").exists())
                    // 1300-SETUP-SCREEN-ATTRS:885 aims the cursor at the account filter when the screen
                    // edited cleanly, and that request now reaches the client.
                    .andExpect(jsonPath("$.screenMetadata.cursorField").value("ACCTSID"))
                    // And they are NOT siblings of the 45 values.
                    .andExpect(jsonPath("$.acctsidc").doesNotExist())
                    .andExpect(jsonPath("$.cursorField").doesNotExist());
        }

        @Test
        @DisplayName("the handler answers the shared envelope, so every online screen has one shape")
        void theHandlerAnswersTheSharedEnvelope() throws ReflectiveOperationException {
            forward(1);

            Method handler = CardListController.class.getDeclaredMethod("getCards",
                    CardListRequest.class, Integer.class, Integer.class);
            assertThat(handler.getReturnType()).isEqualTo(ScreenResponse.class);

            ScreenResponse<CardListResponse> envelope =
                    controller.getCards(null, Byte.toUnsignedInt(CicsAid.DFHENTER), null);
            assertThat(envelope.screen()).isNotNull();
            assertThat(envelope.screenMetadata().fields())
                    .hasSize(CardListResponse.PAYLOAD_FIELD_COUNT);
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

    // =============================================================================================

    /**
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} sits inside {@code 01 WS-CONSTANTS} at
     * {@code app/cbl/COCRDLIC.cbl:177-178}. A {@code VALUE} clause on a constants group is not a
     * tuneable: {@code :1191} compares the row counter against it and {@code :1284-1286} primes the
     * backward browse to {@code WS-MAX-SCREEN-LINES + 1}, so a different value is a different program.
     *
     * <p>{@code PageSizeIsBehaviour} above proves the <em>declaration</em> is a private constant with
     * no {@code @Value} and no bean-name injection. This class proves the complementary half - that
     * nothing reachable by a <em>caller or an operator</em> can move it: not a system property, not a
     * request header, not a stray query parameter, and not reflection (gate G39).
     */
    @Nested
    @DisplayName("Page size 7 is not externally tunable (gate G39)")
    final class PageSizeIsNotExternallyTunable {

        @Test
        @DisplayName("no system property can move the page size off seven")
        void systemPropertiesCannotMoveThePageSize() {
            // Every plausible key an operator might reach for, including the ones the sibling paged
            // screens would use. None of them is read anywhere, and this asserts that by consequence
            // rather than by inspection: the browse offers twenty records and still fills seven rows.
            List<String> candidateKeys = List.of(
                    "carddemo.card.list.page-size",
                    "carddemo.page-size",
                    "carddemo.card.page-size",
                    "spring.data.web.pageable.default-page-size",
                    "WS_MAX_SCREEN_LINES");
            List<String> previous = new ArrayList<>(candidateKeys.size());
            for (String key : candidateKeys) {
                previous.add(System.getProperty(key));
            }
            try {
                for (String key : candidateKeys) {
                    System.setProperty(key, "3");
                }
                forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
                WorkArea ws = new WorkArea();

                CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

                assertThat(ws.wsScrnCounter)
                        .as("app/cbl/COCRDLIC.cbl:1191 stops the loop at WS-MAX-SCREEN-LINES, which is "
                                + "7 and cannot be configured")
                        .isEqualTo(7);
                assertThat(response.screenRow(7).rowCardNum())
                        .as("row 7 still holds the seventh record")
                        .isEqualTo(String.format("%016d", 7));
                assertThat(response.getPageCursor().isNextPageExists())
                        .as("an eighth record still exists, so the next-page indicator is still 'Y'")
                        .isTrue();
            } finally {
                // The suite holds no static mutable state of its own (gate G53); a property this test
                // set is host state, so it is put back whatever the outcome, leaving the JVM as found.
                for (int index = 0; index < candidateKeys.size(); index++) {
                    String key = candidateKeys.get(index);
                    String before = previous.get(index);
                    if (before == null) {
                        System.clearProperty(key);
                    } else {
                        System.setProperty(key, before);
                    }
                }
            }
        }

        @Test
        @DisplayName("no request header and no extra query parameter can move the page size")
        void requestHeadersAndQueryParametersCannotMoveThePageSize() throws Exception {
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .header("X-Page-Size", "3")
                            .header("Range", "rows=0-2")
                            .param("pageSize", "3")
                            .param("size", "3")
                            .param("limit", "3"))
                    .andExpect(status().isOk())
                    // Rows 1 and 7 are both painted, so the page is seven deep whatever was asked for.
                    .andExpect(jsonPath("$.crdnum1o").value(String.format("%016d", 1)))
                    .andExpect(jsonPath("$.crdnum7o").value(String.format("%016d", 7)));
        }

        @Test
        @DisplayName("the constant is final, so reflection cannot rewrite it either")
        void reflectionCannotRewriteTheConstant() throws ReflectiveOperationException {
            Field field = CardListController.class.getDeclaredField("WS_MAX_SCREEN_LINES");
            field.setAccessible(true);

            assertThat(field.getInt(null)).isEqualTo(7);
            assertThatExceptionOfType(IllegalAccessException.class)
                    .as("setAccessible does not unlock a static final primitive; the literal 7 is also "
                            + "inlined at every use site by the compiler")
                    .isThrownBy(() -> field.setInt(null, 3));
            assertThat(field.getInt(null))
                    .as("the failed write left it at seven")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("the row table, the request DTO and the response DTO all agree that seven is seven")
        void everyDeclarationOfSevenAgrees() {
            assertThat(CardListRequest.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListRequest.SCREEN_ROW_COUNT).isEqualTo(7);
            assertThat(CardListResponse.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListResponse.ROW_COUNT).isEqualTo(7);
            // app/cbl/COCRDLIC.cbl:250 states the arithmetic in a comment: 28 CHARS X 7 ROWS = 196.
            assertThat(CardListResponse.SCREEN_ROW_LENGTH)
                    .as("WS-ROW-ACCTNO X(11) + WS-ROW-CARD-NUM X(16) + WS-ROW-CARD-STATUS X(1)")
                    .isEqualTo(11 + 16 + 1);
            assertThat(CardListResponse.SCREEN_ARRAY_LENGTH)
                    .as("WS-ALL-ROWS PIC X(196) - 28 x 7")
                    .isEqualTo(28 * 7);
        }
    }

    // =============================================================================================

    /**
     * {@code COCRDLIC} reaches {@code CCARD-AID} through {@code COPY 'CSSTRPFY'} at
     * {@code app/cbl/COCRDLIC.cbl:1416}, and {@code app/cpy/CSSTRPFY.cpy:54-77} folds {@code DFHPF13}
     * through {@code DFHPF24} back onto {@code CCARD-AID-PFK01} through {@code CCARD-AID-PFK12} -
     * twelve further {@code WHEN} clauses that repeat the twelve above them with the high keys.
     *
     * <p>The fold is behaviour, not a convenience: because {@code :371-377} admits exactly
     * {@code ENTER}, {@code PFK03}, {@code PFK07} and {@code PFK08}, folding is what makes
     * <strong>{@code PF15} exit, {@code PF19} page up and {@code PF20} page down</strong>. Dropping it
     * would send all three down the invalid-key path at {@code :379} and repaint page one instead.
     */
    @Nested
    @DisplayName("PF13-PF24 fold onto PFK01-PFK12 - app/cpy/CSSTRPFY.cpy:54-77")
    final class FoldedProgramFunctionKeys {

        @ParameterizedTest(name = "PF{0} and PF{1} are the same AID token")
        @CsvSource({"13,1", "14,2", "15,3", "16,4", "17,5", "18,6",
                    "19,7", "20,8", "21,9", "22,10", "23,11", "24,12"})
        void highKeysFoldOntoLowKeys(int high, int low) throws ReflectiveOperationException {
            byte highAid = CicsAid.class.getDeclaredField("DFHPF" + high).getByte(null);
            byte lowAid = CicsAid.class.getDeclaredField("DFHPF" + low).getByte(null);

            assertThat(highAid)
                    .as("the two AIDs are distinct bytes; only the mapping folds them")
                    .isNotEqualTo(lowAid);

            AidKey foldedFromHigh = PfKeyResolver.resolve(highAid).orElseThrow();
            AidKey foldedFromLow = PfKeyResolver.resolve(lowAid).orElseThrow();

            assertThat(foldedFromHigh)
                    .as("CSSTRPFY sets the same 88-level for PF%d as for PF%d", high, low)
                    .isSameAs(foldedFromLow);
            assertThat(foldedFromHigh.token())
                    .isEqualTo(String.format("PFK%02d", low));
            assertThat(foldedFromHigh.token().length())
                    .as("CCARD-AID is PIC X(5), so every token is five characters")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("PF15 exits to the main menu exactly as PF3 does - :384-406")
        void pf15ExitsLikePf3() {
            WorkArea viaPf3 = new WorkArea();
            WorkArea viaPf15 = new WorkArea();

            CardListResponse fromPf3 = controller.listCards(continuing(), CicsAid.DFHPF3, viaPf3);
            CardListResponse fromPf15 = controller.listCards(continuing(), CicsAid.DFHPF15, viaPf15);

            assertThat(viaPf15.ccWorkArea.isCcardAidPfk03())
                    .as("CSSTRPFY.cpy:58-59 sets CCARD-AID-PFK03 for DFHPF15")
                    .isTrue();
            assertThat(fromPf15.getNextProgram()).isEqualTo(fromPf3.getNextProgram())
                    .isEqualTo("COMEN01C");
            assertThat(fromPf15.getNextMapset()).isEqualTo(fromPf3.getNextMapset());
            assertThat(fromPf15.getNextMap()).isEqualTo(fromPf3.getNextMap());
            verifyNoInteractions(browse);
        }

        @Test
        @DisplayName("PF19 pages up exactly as PF7 does - arm 5, :502-514")
        void pf19PagesUpLikePf7() {
            CardListRequest onPageTwo = showing(0, " ");
            onPageTwo.setPageCursor(onPageTwo.getPageCursor().withScreenNum(2));
            backward(7, 6, 5, 4, 3, 2, 1);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(onPageTwo, CicsAid.DFHPF19, ws);

            assertThat(ws.ccWorkArea.isCcardAidPfk07())
                    .as("CSSTRPFY.cpy:66-67 sets CCARD-AID-PFK07 for DFHPF19")
                    .isTrue();
            assertThat(response.getPageCursor().screenNum())
                    .as(":508 SUBTRACT 1 FROM WS-CA-SCREEN-NUM")
                    .isEqualTo(1);
            verify(browse, never()).readNext();
            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("PF20 pages down exactly as PF8 does - arm 4, :486-498")
        void pf20PagesDownLikePf8() {
            forward(8, 9, 10, 11, 12, 13, 14, 15);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHPF20, ws);

            assertThat(ws.ccWorkArea.isCcardAidPfk08())
                    .as("CSSTRPFY.cpy:68-69 sets CCARD-AID-PFK08 for DFHPF20")
                    .isTrue();
            assertThat(response.getPageCursor().screenNum())
                    .as(":491 ADD +1 TO WS-CA-SCREEN-NUM")
                    .isEqualTo(2);
            assertThat(response.screenRow(1).rowCardNum())
                    .as(":488-489 browses from WS-CA-LAST-CARD-NUM")
                    .isEqualTo(String.format("%016d", 8));
        }

        @ParameterizedTest(name = "PF{0} folds onto a key this program does not handle, so it is ENTER")
        @ValueSource(ints = {13, 14, 16, 17, 18, 21, 22, 23, 24})
        void foldedKeysOutsideTheHandledSetBecomeEnter(int high) throws ReflectiveOperationException {
            byte aid = CicsAid.class.getDeclaredField("DFHPF" + high).getByte(null);
            forward(1);
            WorkArea ws = new WorkArea();

            controller.listCards(continuing(), aid, ws);

            assertThat(ws.isPfkInvalid())
                    .as(":371-377 admits only ENTER, PFK03, PFK07 and PFK08")
                    .isTrue();
            assertThat(ws.ccWorkArea.isCcardAidEnter())
                    .as(":378-380 IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE - coerced, not rejected")
                    .isTrue();
        }
    }

    // =============================================================================================

    /**
     * {@code app/cbl/COCRDLIC.cbl:410-414}:
     * <pre>IF CCARD-AID-PFK08 CONTINUE ELSE SET CA-LAST-PAGE-NOT-SHOWN TO TRUE END-IF</pre>
     *
     * <p>The flag exists so {@code 1400-SETUP-MESSAGE} can tell a <em>first</em> {@code PF8} that lands
     * on the last page ({@code :910-916}, which flips {@code CA-LAST-PAGE-SHOWN} on) from a
     * <em>second</em> one ({@code :905-909}, which answers "no more pages"). Resetting it for every
     * other key is what stops a stale {@code 0} carried in the payload from suppressing a legitimate
     * page-down later, so both sides of the {@code IF} are asserted.
     */
    @Nested
    @DisplayName("The last-page flag is reset for every key except PF8 - :410-414")
    final class LastPageFlagReset {

        /** A continuing request that carries {@code CA-LAST-PAGE-SHOWN}, the resettable state. */
        private CardListRequest carryingLastPageShown() {
            CardListRequest request = showing(0, " ");
            request.setPageCursor(request.getPageCursor()
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN));
            return request;
        }

        @ParameterizedTest(name = "EIBAID {0} is not PF8, so the carried flag is discarded")
        @ValueSource(ints = {125, 247, 108, 109, 110})
        void everyNonPf8KeyResetsTheFlag(int aid) {
            // 125 ENTER, 247 PF7, 108 CLEAR, 109 PA1, 110 PA2. The last three fold to ENTER at :379,
            // which is still not PF8, so the ELSE arm runs for them too.
            forward(1, 2, 3, 4, 5, 6, 7);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(carryingLastPageShown(), (byte) aid, ws);

            assertThat(ws.pfk08Continued)
                    .as(":410-411 CONTINUE runs only for PF8")
                    .isFalse();
            assertThat(response.getPageCursor().lastPageDisplayed())
                    .as(":413 SET CA-LAST-PAGE-NOT-SHOWN TO TRUE")
                    .isEqualTo(CardListRequest.LAST_PAGE_NOT_SHOWN);
            assertThat(response.getPageCursor().isLastPageNotShown()).isTrue();
            assertThat(response.getPageCursor().isLastPageShown()).isFalse();
        }

        @Test
        @DisplayName("PF8 takes the CONTINUE arm, so the carried flag survives into 1400-SETUP-MESSAGE")
        void pf8LeavesTheFlagAlone() {
            // The carried flag says the last page has already been shown, and CA-NEXT-PAGE-EXISTS says
            // there is more to come. :410-411 CONTINUE preserves the flag, so arm 3 of
            // 1400-SETUP-MESSAGE (:905-909) is reachable and answers "no more pages".
            forward();
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(carryingLastPageShown(), CicsAid.DFHPF8, ws);

            assertThat(ws.pfk08Continued)
                    .as(":410-411 IF CCARD-AID-PFK08 CONTINUE")
                    .isTrue();
            assertThat(response.getPageCursor().lastPageDisplayed())
                    .as("the ELSE arm at :413 did not run, so the carried CA-LAST-PAGE-SHOWN survived")
                    .isEqualTo(CardListRequest.LAST_PAGE_SHOWN);
            assertThat(ws.messageArm)
                    .as("the flag survived, so the second-PF8 arm at :905-909 is the one that fires")
                    .isEqualTo(MessageArm.NO_MORE_PAGES);
            assertThat(response.getErrmsgo().trim())
                    .isEqualTo(CardListController.MSG_NO_MORE_PAGES);
        }

        @Test
        @DisplayName("PF20 folds onto PFK08 and therefore also takes the CONTINUE arm")
        void theFoldedPageDownKeyAlsoTakesTheContinueArm() {
            forward();
            WorkArea ws = new WorkArea();

            controller.listCards(carryingLastPageShown(), CicsAid.DFHPF20, ws);

            assertThat(ws.pfk08Continued)
                    .as(":410 tests the 88-level CCARD-AID-PFK08, which PF20 sets just as PF8 does")
                    .isTrue();
        }

        @Test
        @DisplayName("a cold start reaches :410-414 already holding CA-LAST-PAGE-NOT-SHOWN - :325")
        void aColdStartIsAlreadyNotShown() {
            forward(1);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER, ws);

            assertThat(ws.eibcalenZero).isTrue();
            assertThat(response.getPageCursor().lastPageDisplayed())
                    .as(":325 SET CA-LAST-PAGE-NOT-SHOWN TO TRUE, and :413 sets it again")
                    .isEqualTo(CardListRequest.LAST_PAGE_NOT_SHOWN);
        }
    }

    // =============================================================================================

    /**
     * {@code app/cbl/COCRDLIC.cbl:586-601} - the block that follows {@code END-EVALUATE}.
     *
     * <p>It is <strong>unreachable</strong>. All eight arms of the {@code EVALUATE} terminate the
     * program: six end in {@code GO TO COMMON-RETURN} and two in {@code EXEC CICS XCTL}, so nothing
     * falls through to {@code :586}. Its statements are not lost, though - {@code :587-594} are
     * character-for-character the seven moves the input-error arm already performs at {@code :423-430},
     * and {@code :600} repeats the {@code MOVE LIT-THISPGM TO CCARD-NEXT-PROG} of {@code :428}.
     *
     * <p><strong>{@code :595-596} is commented out.</strong> The source reads
     * {@code *       PERFORM 1000-SEND-MAP} / {@code *          THRU 1000-SEND-MAP}, so even if the
     * block were reachable it would <em>not</em> repaint. Re-introducing that {@code PERFORM} in the
     * Java form would send the map a second time on the error path and is therefore forbidden
     * (practice B5). What makes the duplication harmless is that the seven moves are idempotent, which
     * is what this class asserts rather than merely asserting that the block is absent.
     */
    @Nested
    @DisplayName("The block after END-EVALUATE is unreachable and its send is commented out - :586-601")
    final class PostEvaluateTerminal {

        @Test
        @DisplayName("the seven moves of :587-594 are exactly what the input-error arm performs")
        void theSevenMovesAreTheInputErrorArmsOwnPreamble() {
            WorkArea ws = new WorkArea();
            ws.commarea = NavigationContext.empty().withFromProgram("COMEN01C");
            ws.setWsErrorMsg(moved(CardListController.WS_INVALID_ACTION_CODE));

            controller.applyInputErrorReturnState(ws);

            // :424  MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG          (= :587)
            assertThat(ws.ccWorkArea.getCcardErrorMsg())
                    .isEqualTo(moved(CardListController.WS_INVALID_ACTION_CODE));
            // :425-427 the three CDEMO moves                       (= :588-590)
            assertThat(ws.commarea.fromProgram()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.commarea.lastMapset()).isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.commarea.lastMap()).isEqualTo(CardListController.LIT_THISMAP);
            // :428-430 the three CCARD-NEXT moves                  (= :592-594, and :428 = :600)
            assertThat(ws.ccWorkArea.getCcardNextProg()).isEqualTo(CardListController.LIT_THISPGM);
            assertThat(ws.ccWorkArea.getCcardNextMapset()).isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(ws.ccWorkArea.getCcardNextMap()).isEqualTo(CardListController.LIT_THISMAP);
        }

        @Test
        @DisplayName("performing them twice changes nothing, which is why the duplicate block is inert")
        void theSevenMovesAreIdempotent() {
            WorkArea ws = new WorkArea();
            ws.commarea = NavigationContext.empty().withFromProgram("COMEN01C");
            ws.setWsErrorMsg(moved(CardListController.WS_MORE_THAN_1_ACTION));

            controller.applyInputErrorReturnState(ws);
            NavigationContext afterFirst = ws.commarea;
            String errorAfterFirst = ws.ccWorkArea.getCcardErrorMsg();
            String progAfterFirst = ws.ccWorkArea.getCcardNextProg();
            String mapsetAfterFirst = ws.ccWorkArea.getCcardNextMapset();
            String mapAfterFirst = ws.ccWorkArea.getCcardNextMap();

            // The unreachable block, had it run, would have done precisely this again.
            controller.applyInputErrorReturnState(ws);

            assertThat(ws.commarea).isEqualTo(afterFirst);
            assertThat(ws.ccWorkArea.getCcardErrorMsg()).isEqualTo(errorAfterFirst);
            assertThat(ws.ccWorkArea.getCcardNextProg()).isEqualTo(progAfterFirst);
            assertThat(ws.ccWorkArea.getCcardNextMapset()).isEqualTo(mapsetAfterFirst);
            assertThat(ws.ccWorkArea.getCcardNextMap()).isEqualTo(mapAfterFirst);
        }

        @Test
        @DisplayName("the error path paints once, so the commented-out :595-596 send is not restored")
        void theErrorPathPaintsExactlyOnce() {
            // A filter that failed edit means :431-435 skips the re-read, so the browse is never opened
            // and 1000-SEND-MAP runs once, from :436-437. 1400-SETUP-MESSAGE arm 1 (:898-900) is a
            // CONTINUE that keeps the filter's own message - so a SECOND send would leave ERRMSGO
            // unchanged and be invisible in the payload. What IS visible is the information line: a
            // second pass through 1000-SEND-MAP would re-run 1200-SCREEN-ARRAY-INIT over a row table
            // that the first pass already wrote, so the single-pass outcome is pinned here instead.
            CardListRequest request = continuing();
            request.setAcctsid("NOTANUMBER1");
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER, ws);

            assertThat(ws.isInputError()).isTrue();
            assertThat(ws.isFlgAcctfilterNotOk()).isTrue();
            verifyNoInteractions(browse);
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
            assertThat(ws.messageArm)
                    .as("arm 1 at :898-900 keeps the filter's own message")
                    .isEqualTo(MessageArm.FILTER_IN_ERROR);
            assertThat(response.getErrmsgo().trim())
                    .isEqualTo(CardListController.MSG_ACCOUNT_FILTER);
            // :428-430 write CCARD-NEXT-*, which live in CC-WORK-AREA - WORKING-STORAGE that
            // COMMON-RETURN at :604-615 attaches, not the XCTL target field. The dedicated
            // nextProgram member stands for an actual transfer of control, and no transfer happened
            // here, so it is correctly still spaces.
            assertThat(response.getCardScreenState().getCcardNextProg())
                    .as(":428 and its unreachable twin :600 both name this program")
                    .isEqualTo(CardListController.LIT_THISPGM);
            assertThat(response.getCardScreenState().getCcardNextMapset())
                    .as(":429 = :593")
                    .isEqualTo(CardListController.LIT_THISMAPSET);
            assertThat(response.getCardScreenState().getCcardNextMap())
                    .as(":430 = :594")
                    .isEqualTo(CardListController.LIT_THISMAP);
            assertThat(response.getNextProgram().trim())
                    .as("no XCTL ran, so the transfer field stays empty")
                    .isEmpty();
        }
    }

    // =============================================================================================

    /**
     * Gate G40 and rule R6. {@code COCRDLIC} issues three {@code EXEC CICS XCTL}s -
     * {@code :402-403} to {@code LIT-MENUPGM}, {@code :538-539} to {@code LIT-CARDDTLPGM} and
     * {@code :566-567} to {@code LIT-CARDUPDPGM} - and every one of them becomes a
     * {@code nextProgram} field in the response body. The server performs <strong>no</strong> forward,
     * <strong>no</strong> redirect and <strong>no</strong> session hand-off; the client reads the
     * triple and issues the next call itself.
     *
     * <p><strong>Practice B4 - the {@code COCRDSL} reconciliation.</strong> AAP &sect;0.4.11 and
     * &sect;0.6.4 state that {@code COCRDLIC} copies {@code COCRDSL} and that {@code CardListController}
     * therefore imports the card-select DTO. The verified source disagrees:
     * {@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - <em>commented out</em>, as is
     * {@code *COPY CSMSG02Y.} at {@code :283} - and the only live screen copy is {@code COPY COCRDLI.}
     * at {@code :276}. The program sends and receives one map and one only:
     * {@code SEND MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET)} at {@code :939-940} and {@code RECEIVE MAP}
     * at {@code :963-964}, with {@code LIT-THISMAP = 'CCRDLIA'}. Commented-out COBOL is not behaviour.
     * The conflict is recorded, not silently resolved: {@link CardSelectRequest} and
     * {@link CardSelectResponse} are legitimate as <em>navigation-target references</em> - the triple
     * {@code 'COCRDSLC'}/{@code 'COCRDSL'}/{@code 'CCRDSLA'} names that screen - and the tests below
     * assert the navigation while asserting that no {@code COCRDSL} symbolic-map field is ever
     * populated by this controller.
     */
    @Nested
    @DisplayName("Navigation is client-driven - three XCTLs, no forward, no redirect (gate G40)")
    final class NavigationIsClientDriven {

        private MockMvc mockMvc;
        private ObjectMapper json;

        @BeforeEach
        void standaloneSetup() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
            json = new ObjectMapper();
        }

        /** Asserts the reply is a plain 200 body and not any form of server-side hand-off. */
        private void assertNoServerSideHandOff(MvcResult result) {
            MockHttpServletResponse http = result.getResponse();
            assertThat(http.getStatus())
                    .as("a transfer of control is data in the body, not a 3xx")
                    .isEqualTo(200);
            assertThat(http.getRedirectedUrl())
                    .as("EXEC CICS XCTL becomes a response field, never a redirect")
                    .isNull();
            assertThat(http.getHeader("Location"))
                    .as("no Location header, so nothing drives the client but the payload")
                    .isNull();
            assertThat(http.getForwardedUrl())
                    .as("no server-side forward either")
                    .isNull();
            assertThat(result.getRequest().getSession(false))
                    .as("gate G37 - the handler creates no servlet session, so there is nowhere for "
                            + "conversation state to hide")
                    .isNull();
        }

        @Test
        @DisplayName("XCTL :402-403 to the menu is a body field, not a redirect")
        void menuTransferIsABodyField() throws Exception {
            MvcResult result = mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM,
                                    String.valueOf(CicsAid.DFHPF3 & 0xFF))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(showing(0, " "))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COMEN01C"))
                    // Preserved defect 2: :394 names the menu's mapset, :395 names THIS map.
                    .andExpect(jsonPath("$.nextMapset").value("COMEN01"))
                    .andExpect(jsonPath("$.nextMap").value("CCRDLIA"))
                    .andReturn();

            assertNoServerSideHandOff(result);
        }

        @Test
        @DisplayName("XCTL :538-539 to the card detail view is a body field, not a redirect")
        void detailTransferIsABodyField() throws Exception {
            MvcResult result = mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM, String.valueOf(ENTER_PARAM))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(showing(1, "S"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COCRDSLC"))
                    .andExpect(jsonPath("$.nextMapset").value("COCRDSL"))
                    .andExpect(jsonPath("$.nextMap").value("CCRDSLA"))
                    .andReturn();

            assertNoServerSideHandOff(result);
        }

        @Test
        @DisplayName("XCTL :566-567 to the card update program is a body field, not a redirect")
        void updateTransferIsABodyField() throws Exception {
            MvcResult result = mockMvc.perform(get(CardListController.CARD_LIST_PATH)
                            .param(CardListController.EIBAID_PARAM, String.valueOf(ENTER_PARAM))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(showing(7, "U"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COCRDUPC"))
                    .andExpect(jsonPath("$.nextMapset").value("COCRDUP"))
                    .andExpect(jsonPath("$.nextMap").value("CCRDUPA"))
                    .andReturn();

            assertNoServerSideHandOff(result);
        }

        @Test
        @DisplayName("a plain listing is a 200 body with no hand-off either")
        void aPlainListingIsAlsoAPlainBody() throws Exception {
            forward(1, 2, 3);

            MvcResult result = mockMvc.perform(get(CardListController.CARD_LIST_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            assertNoServerSideHandOff(result);
        }

        @Test
        @DisplayName("the three XCTL targets are the three literals :183-214 declares, at their widths")
        void theThreeTargetsAreTheDeclaredLiterals() {
            assertThat(CardListController.LIT_MENUPGM).isEqualTo("COMEN01C").hasSize(8);
            assertThat(CardListController.LIT_MENUMAPSET).isEqualTo("COMEN01").hasSize(7);
            assertThat(CardListController.LIT_CARDDTLPGM).isEqualTo("COCRDSLC").hasSize(8);
            assertThat(CardListController.LIT_CARDDTLMAPSET).isEqualTo("COCRDSL").hasSize(7);
            assertThat(CardListController.LIT_CARDDTLMAP).isEqualTo("CCRDSLA").hasSize(7);
            assertThat(CardListController.LIT_CARDUPDPGM).isEqualTo("COCRDUPC").hasSize(8);
            assertThat(CardListController.LIT_CARDUPDMAPSET).isEqualTo("COCRDUP").hasSize(7);
            assertThat(CardListController.LIT_CARDUPDMAP).isEqualTo("CCRDUPA").hasSize(7);
            // Declared at :195-196 and moved nowhere, which is what preserved defect 2 consists of.
            assertThat(CardListController.LIT_MENUMAP).isEqualTo("COMEN1A").hasSize(7);
        }

        @Test
        @DisplayName("no COCRDSL symbolic-map field is populated - *COPY COCRDSL. at :274 (practice B4)")
        void noCardSelectMapFieldIsPopulated() throws Exception {
            forward(1, 2, 3);
            CardListResponse response = controller.listCards(showing(1, "S"), CicsAid.DFHENTER);

            String serialised = json.writeValueAsString(response);

            // The five members COCRDSL declares that COCRDLI does not. FKEYSO is among them, which is
            // also the proof that this map has no function-key legend field at all.
            assertThat(serialised)
                    .as("COCRDLIC copies only its own map, so none of COCRDSL's own items appears")
                    .doesNotContain("\"crdnameo\"")
                    .doesNotContain("\"crdstcdo\"")
                    .doesNotContain("\"expmono\"")
                    .doesNotContain("\"expyearo\"")
                    .doesNotContain("\"fkeyso\"");
            assertThat(response).isInstanceOf(CardListResponse.class);
            assertThat(CardListResponse.MAP_FIELDS)
                    .as("and no descriptor names one either")
                    .noneMatch(field -> field.itemName().startsWith("CRDNAME")
                            || field.itemName().startsWith("CRDSTCD")
                            || field.itemName().startsWith("EXPMON")
                            || field.itemName().startsWith("EXPYEAR")
                            || field.itemName().startsWith("FKEYS"));
        }
    }

    // =============================================================================================

    /**
     * Gate G9 - every payload field traces to a name-labelled {@code DFHMDF} entry.
     *
     * <p>{@code app/cpy-bms/COCRDLI.CPY} declares {@code 01 CCRDLIAI} and, at line 289,
     * {@code 01 CCRDLIAO REDEFINES CCRDLIAI}: two views of the same 797 bytes. It carries exactly
     * <strong>45</strong> {@code xxxI PIC X(n)} data items, matching the 45 <em>name-labelled</em>
     * {@code DFHMDF} entries in {@code app/bms/COCRDLI.bms} - the raw {@code DFHMDF} count of 72
     * includes the unnamed literal fields, which have no symbolic-map item and so no payload member.
     * The 45 reconcile as 9 header + 4 row 1 + 6 x 5 rows 2-7 + 2 footer.
     *
     * <p>The {@code xxxL} length item, the {@code xxxF} flag byte and its {@code xxxA} redefinition on
     * the input side, and {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} on the output side,
     * are validation and highlight <strong>metadata</strong>. They travel in
     * {@code ScreenMetadata}, never as JSON payload members, and that is asserted for all 45 prefixes
     * rather than spot-checked.
     */
    @Nested
    @DisplayName("The symbolic-map payload contract - 45 fields, exactly (gate G9)")
    final class SymbolicMapPayloadContract {

        /**
         * The 45 items in copybook order with the width each {@code PICTURE} clause declares, read off
         * {@code app/cpy-bms/COCRDLI.CPY}. The order <strong>is</strong> the byte layout, so this table
         * is deliberately written out one row per field rather than generated from a loop.
         *
         * @return 45 {@code {itemName, width}} pairs
         */
        private List<String[]> declaredFields() {
            List<String[]> declared = new ArrayList<>(CardListResponse.PAYLOAD_FIELD_COUNT);
            // Header - 9 fields. PAGENOO sits seventh, between CURTIMEO and ACCTSIDO.
            declared.add(new String[] {"TRNNAMEO", "4"});
            declared.add(new String[] {"TITLE01O", "40"});
            declared.add(new String[] {"CURDATEO", "8"});
            declared.add(new String[] {"PGMNAMEO", "8"});
            declared.add(new String[] {"TITLE02O", "40"});
            declared.add(new String[] {"CURTIMEO", "8"});
            declared.add(new String[] {"PAGENOO", "3"});
            declared.add(new String[] {"ACCTSIDO", "11"});
            declared.add(new String[] {"CARDSIDO", "16"});
            // Row 1 - FOUR fields. There is no CRDSTP1O; CRDSEL1O runs straight into ACCTNO1O.
            declared.add(new String[] {"CRDSEL1O", "1"});
            declared.add(new String[] {"ACCTNO1O", "11"});
            declared.add(new String[] {"CRDNUM1O", "16"});
            declared.add(new String[] {"CRDSTS1O", "1"});
            // Rows 2-7 - FIVE fields each, with CRDSTPnO SECOND in the row.
            for (int row = 2; row <= 7; row++) {
                declared.add(new String[] {"CRDSEL" + row + "O", "1"});
                declared.add(new String[] {"CRDSTP" + row + "O", "1"});
                declared.add(new String[] {"ACCTNO" + row + "O", "11"});
                declared.add(new String[] {"CRDNUM" + row + "O", "16"});
                declared.add(new String[] {"CRDSTS" + row + "O", "1"});
            }
            // Footer - 2 fields. COCRDLI's own widths: 45 and 78, not the sibling maps' 40 and 80.
            declared.add(new String[] {"INFOMSGO", "45"});
            declared.add(new String[] {"ERRMSGO", "78"});
            return declared;
        }

        @Test
        @DisplayName("there are exactly 45 payload fields, in copybook order, at their declared widths")
        void fortyFiveFieldsInCopybookOrder() {
            List<String[]> declared = declaredFields();

            assertThat(declared)
                    .as("9 header + 4 row-1 + 6 x 5 rows-2-to-7 + 2 footer")
                    .hasSize(45);
            assertThat(CardListResponse.MAP_FIELDS).hasSize(declared.size());
            assertThat(CardListResponse.PAYLOAD_FIELD_COUNT).isEqualTo(45);

            for (int index = 0; index < declared.size(); index++) {
                MapField actual = CardListResponse.MAP_FIELDS.get(index);
                assertThat(actual.itemName())
                        .as("field %d of the symbolic map, in copybook order", index + 1)
                        .isEqualTo(declared.get(index)[0]);
                assertThat(actual.length())
                        .as("%s PICTURE width", actual.itemName())
                        .isEqualTo(Integer.parseInt(declared.get(index)[1]));
            }
        }

        @Test
        @DisplayName("the 45 widths sum to 470 data bytes inside a 797-byte group")
        void theWidthsSumToTheDeclaredLengths() {
            int sum = 0;
            for (String[] field : declaredFields()) {
                sum += Integer.parseInt(field[1]);
            }

            assertThat(sum)
                    .as("header 138 + row 1 29 + rows 2-7 180 + footer 123")
                    .isEqualTo(470)
                    .isEqualTo(CardListResponse.PAYLOAD_LENGTH);
            assertThat(CardListResponse.GROUP_LENGTH)
                    .as("TIOAPFX 12 + 45 x 7-byte prefix + 470 - the group at COCRDLI.CPY:289")
                    .isEqualTo(12 + (45 * CardListResponse.FIELD_PREFIX_LENGTH) + 470)
                    .isEqualTo(797);
        }

        @Test
        @DisplayName("row 1 has four members and no CRDSTP1O - COCRDLI.CPY row-1 asymmetry")
        void rowOneHasNoStopperField() {
            assertThat(CardListResponse.rowFieldCount(1))
                    .as("CRDSEL1O, ACCTNO1O, CRDNUM1O, CRDSTS1O and nothing else")
                    .isEqualTo(4);
            assertThat(CardListResponse.hasCrdstpItem(1)).isFalse();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("asking row 1 for a stopper is a programming error, not an empty string")
                    .isThrownBy(() -> CardListResponse.crdstpItem(1));
            assertThat(CardListResponse.MAP_FIELDS)
                    .as("no descriptor is named CRDSTP1O anywhere in the table")
                    .noneMatch(field -> "CRDSTP1O".equals(field.itemName()));
        }

        @ParameterizedTest(name = "row {0} has five members with CRDSTP{0}O second")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        void rowsTwoToSevenCarryAStopperFieldSecond(int row) {
            assertThat(CardListResponse.rowFieldCount(row)).isEqualTo(5);
            assertThat(CardListResponse.hasCrdstpItem(row)).isTrue();
            assertThat(CardListResponse.crdstpItem(row)).isEqualTo("CRDSTP" + row + "O");

            int selection = CardListResponse.MAP_FIELDS.indexOf(
                    CardListResponse.mapField(CardListResponse.crdselItem(row)));
            int stopper = CardListResponse.MAP_FIELDS.indexOf(
                    CardListResponse.mapField(CardListResponse.crdstpItem(row)));
            int account = CardListResponse.MAP_FIELDS.indexOf(
                    CardListResponse.mapField(CardListResponse.acctnoItem(row)));

            assertThat(stopper)
                    .as("CRDSTP%dO sits between the selection column and the account id", row)
                    .isEqualTo(selection + 1)
                    .isEqualTo(account - 1);
        }

        @Test
        @DisplayName("this map declares no function-key legend field - no FKEYS in the copybook or mapset")
        void theMapHasNoFunctionKeyLegendField() {
            assertThat(CardListResponse.MAP_FIELDS)
                    .as("the sibling COCRDSL and COCRDUP maps declare FKEYS; COCRDLI does not")
                    .noneMatch(field -> field.itemName().contains("FKEY"));
            assertThat(CardListRequest.FIELD_COUNT)
                    .as("the input view has the same 45 items")
                    .isEqualTo(CardListResponse.PAYLOAD_FIELD_COUNT);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item is a JSON member, for any field")
        void noMetadataSuffixIsEverAJsonMember() throws Exception {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse response = controller.listCards(showing(0, " "), CicsAid.DFHENTER);

            String serialised = new ObjectMapper().writeValueAsString(response);

            for (MapField field : CardListResponse.MAP_FIELDS) {
                String prefix = field.screenFieldPrefix().toLowerCase(Locale.ROOT);
                for (String suffix : List.of("l", "f", "a", "c", "p", "h", "v")) {
                    assertThat(serialised)
                            .as("%s%s is metadata, never a payload member (gate G9)",
                                    field.screenFieldPrefix(), suffix.toUpperCase(Locale.ROOT))
                            .doesNotContain("\"" + prefix + suffix + "\"");
                }
                assertThat(serialised)
                        .as("%s IS a payload member", field.itemName())
                        .contains("\"" + field.itemName().toLowerCase(Locale.ROOT) + "\"");
            }
        }

        @Test
        @DisplayName("every metadata quad is published under screenMetadata, keyed by DFHMDF label")
        void theMetadataQuadsTravelBesideTheScreen() {
            forward(1);
            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.screenMetadata().fields())
                    .as("one quad per payload field, and no more")
                    .hasSize(CardListResponse.PAYLOAD_FIELD_COUNT);
            for (MapField field : CardListResponse.MAP_FIELDS) {
                assertThat(response.screenMetadata().fields())
                        .as("the quad is keyed by the DFHMDF label, not the symbolic-map item name")
                        .containsKey(field.screenFieldPrefix());
            }
        }

        @Test
        @DisplayName("TITLE01O and TITLE02O come from ScreenTitles at their declared X(40)")
        void theTitlesComeFromTheSharedCopybook() {
            forward(1);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            // app/cbl/COCRDLIC.cbl:646-649 moves CCDA-TITLE01 and CCDA-TITLE02, which COPY COTTL01Y
            // at :270 supplies; both are PIC X(40).
            assertThat(response.getTitle01o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(response.getTitle02o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(CardListResponse.TITLE01O_LENGTH)
                    .isEqualTo(CardListResponse.TITLE02O_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);
        }

        @Test
        @DisplayName("CURDATEO and CURTIMEO come from DateHeader under the injected clock, never now()")
        void theDateAndTimeComeFromTheInjectedClock() {
            forward(1);
            DateHeader expected = DateHeader.from(CODEC, CLOCK);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getCurdateo())
                    .as("1100-SCREEN-INIT:645 FUNCTION CURRENT-DATE, formatted mm/dd/yy")
                    .isEqualTo(expected.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22")
                    .hasSize(CardListResponse.CURDATEO_LENGTH);
            assertThat(response.getCurtimeo())
                    .as("1100-SCREEN-INIT:652 hh:mm:ss")
                    .isEqualTo(expected.wsCurtimeHhMmSs())
                    .isEqualTo("23:12:33")
                    .hasSize(CardListResponse.CURTIMEO_LENGTH);
        }

        @Test
        @DisplayName("TRNNAMEO and PGMNAMEO carry this transaction and program at their declared widths")
        void theIdentityFieldsCarryThisScreensOwnNames() {
            forward(1);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.getTrnnameo())
                    .isEqualTo(CardListController.LIT_THISTRANID)
                    .hasSize(CardListResponse.TRNNAMEO_LENGTH);
            assertThat(response.getPgmnameo())
                    .isEqualTo(CardListController.LIT_THISPGM)
                    .hasSize(CardListResponse.PGMNAMEO_LENGTH);
        }
    }

    // =============================================================================================

    /**
     * Gate G33 - the {@code OCCURS} subscript conversion, checked at both ends of the table.
     *
     * <p>{@code COCRDLIC} declares three seven-element tables: the {@code WS-ROW-*} group at
     * {@code :76}, {@code WS-EDIT-SELECT-ERRORS} at {@code :86} and {@code WS-SCREEN-ROWS} at
     * {@code :255}. A fourth {@code OCCURS} at {@code :295} is
     * {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN} - the {@code DFHCOMMAREA} overlay, not a
     * screen table, and deliberately not treated as one here.
     *
     * <p>COBOL subscripts start at 1 and Java indices at 0. The AAP calls this the top defect risk of
     * the whole migration, because an off-by-one shifts every row's data by one row and neither the
     * compiler nor a round-trip test would notice. So the first element, the last element and the
     * first value <em>past</em> the last are all asserted.
     */
    @Nested
    @DisplayName("OCCURS 7 TIMES - 1-based COBOL to 0-based Java, at both ends (gate G33)")
    final class OccursSubscriptBoundaries {

        @Test
        @DisplayName("COBOL 1 is Java 0 and COBOL 7 is Java 6")
        void bothEndsConvert() {
            assertThat(CardListResponse.toJavaIndex(1)).isZero();
            assertThat(CardListResponse.toJavaIndex(7)).isEqualTo(6);
            assertThat(CardListResponse.toCobolSubscript(0)).isEqualTo(1);
            assertThat(CardListResponse.toCobolSubscript(6)).isEqualTo(7);
        }

        @ParameterizedTest(name = "COBOL subscript {0} is outside OCCURS 7 TIMES")
        @ValueSource(ints = {-1, 0, 8, 9, 196})
        void outOfRangeCobolSubscriptsAreRefused(int subscript) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("COBOL has no element zero and no element eight")
                    .isThrownBy(() -> CardListResponse.toJavaIndex(subscript));
        }

        @ParameterizedTest(name = "Java index {0} is outside the seven-element array")
        @ValueSource(ints = {-1, 7, 8})
        void outOfRangeJavaIndicesAreRefused(int index) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("Java index 7 is the first one past the end of a seven-element table")
                    .isThrownBy(() -> CardListResponse.toCobolSubscript(index));
        }

        @Test
        @DisplayName("subscript 8 is refused by every table accessor, not silently clamped")
        void everyAccessorRefusesSubscriptEight() {
            forward(1, 2, 3, 4, 5, 6, 7);
            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.screenRow(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.editSelect(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.wsRowCrdselectError(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdselItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.acctnoItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdnumItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdstsItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.rowFieldCount(8));
        }

        @ParameterizedTest(name = "subscript {0} round-trips through both conversions")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        void theConversionIsAnExactRoundTrip(int subscript) {
            int javaIndex = CardListResponse.toJavaIndex(subscript);

            assertThat(javaIndex).isBetween(0, 6);
            assertThat(CardListResponse.toCobolSubscript(javaIndex)).isEqualTo(subscript);
        }

        @Test
        @DisplayName("the first and the last row of a full page both hold their own record")
        void theFirstAndLastRowsAreBothCorrect() {
            // 1200-SCREEN-ARRAY-INIT [app/cbl/COCRDLIC.cbl:678-742] moves WS-ROW-ACCTNO(n),
            // WS-ROW-CARD-NUM(n) and WS-ROW-CARD-STATUS(n) into row n's three payload members. Each
            // element is 11 + 16 + 1 = 28 bytes, and 28 x 7 = 196 - the WS-ALL-ROWS width at :253.
            forward(1, 2, 3, 4, 5, 6, 7, 8);

            CardListResponse response = controller.listCards(null, CicsAid.DFHENTER);

            assertThat(response.screenRow(1).rowCardNum())
                    .as("COBOL subscript 1, Java index 0")
                    .isEqualTo(String.format("%016d", 1));
            assertThat(response.screenRow(1).rowAcctno())
                    .isEqualTo(String.format("%011d", 10_000_000_001L));
            assertThat(response.screenRow(7).rowCardNum())
                    .as("COBOL subscript 7, Java index 6")
                    .isEqualTo(String.format("%016d", 7));
            assertThat(response.screenRow(7).rowAcctno())
                    .isEqualTo(String.format("%011d", 10_000_000_007L));
            assertThat(response.screenRows())
                    .as("and the list view is the same seven elements")
                    .hasSize(7);
            assertThat(response.screenRows().get(0)).isEqualTo(response.screenRow(1));
            assertThat(response.screenRows().get(6)).isEqualTo(response.screenRow(7));
        }

        @Test
        @DisplayName("I-SELECTED is 1-based, so row 7 resolves through Java index 6 - :531-534")
        void theSelectedRowResolvesThroughTheConversion() {
            forward(1, 2, 3, 4, 5, 6, 7);
            WorkArea ws = new WorkArea();

            CardListResponse response = controller.listCards(showing(7, "S"), CicsAid.DFHENTER, ws);

            assertThat(ws.iSelected)
                    .as("I-SELECTED holds the COBOL subscript, not the Java index")
                    .isEqualTo(7);
            // :533-534 MOVE WS-ROW-CARD-NUM(I-SELECTED) TO CDEMO-CARD-NUM - X(16) into 9(16).
            assertThat(response.getNavigationContext().cardNum())
                    .as("WS-ROW-CARD-NUM(7), which is element 6 of the Java array")
                    .isEqualTo(7L);
            // :531-532 MOVE WS-ROW-ACCTNO(I-SELECTED) TO CDEMO-ACCT-ID - X(11) into 9(11).
            assertThat(response.getNavigationContext().acctId())
                    .isEqualTo(10_000_000_007L);
        }
    }

    // =============================================================================================

    /**
     * Gate G37 - {@code WS-THIS-PROGCOMMAREA} is 254 bytes of caller state and the server keeps none
     * of it.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:228-250} declares the 58-byte cursor - two
     * {@code X(16)} + {@code 9(11)} key pairs, {@code WS-CA-SCREEN-NUM}, {@code WS-CA-LAST-PAGE-DISPLAYED},
     * {@code WS-CA-NEXT-PAGE-IND} and {@code WS-RETURN-FLAG} - and {@code :252-260} adds
     * {@code WS-SCREEN-DATA}, whose {@code WS-ALL-ROWS PIC X(196)} is redefined as
     * {@code WS-SCREEN-ROWS OCCURS 7 TIMES}. The {@code 05} level closes the preceding {@code 10}
     * group, so the row table is part of the same communication area: 58 + 196 = 254.
     *
     * <p>{@code :328-331} splits the inbound {@code DFHCOMMAREA} into the 160-byte
     * {@code CARDDEMO-COMMAREA} and then this 254-byte area, and {@code COMMON-RETURN} at
     * {@code :604-615} concatenates them again on the way out. A client that does not return the area
     * has not preserved the conversation - and paging is exactly the operation that then cannot work.
     */
    @Nested
    @DisplayName("The client must return the cursor for paging to work (gate G37)")
    final class ClientMustReturnTheCursor {

        @Test
        @DisplayName("dropping the cursor makes the next page unreachable - PF8 repaints page one")
        void droppingTheCursorMakesTheNextPageUnreachable() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse pageOne = controller.listCards(null, CicsAid.DFHENTER);
            assertThat(pageOne.getPageCursor().isNextPageExists())
                    .as("page two does exist, so nothing but the missing cursor can hide it")
                    .isTrue();

            // The client returns the 160-byte navigation context but not the 254-byte area, which is
            // what a caller that treats the reply as a view model rather than a conversation does.
            CardListRequest amnesiac = new CardListRequest();
            amnesiac.setNavigationContext(pageOne.getNavigationContext());
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            WorkArea ws = new WorkArea();

            CardListResponse afterPageDown = controller.listCards(amnesiac, CicsAid.DFHPF8, ws);

            assertThat(ws.pfk08Continued)
                    .as("the key WAS PF8; only the cursor is missing")
                    .isTrue();
            assertThat(afterPageDown.getPageCursor().screenNum())
                    .as("arm 4 at :486 also needs CA-NEXT-PAGE-EXISTS, which the fresh cursor does not "
                            + "carry, so WHEN OTHER at :573-583 lists from the first key instead")
                    .isNotEqualTo(2);
            assertThat(afterPageDown.screenRow(1).rowCardNum())
                    .as("page one again")
                    .isEqualTo(String.format("%016d", 1));
        }

        @Test
        @DisplayName("returning the cursor is the only thing that makes PF8 advance")
        void returningTheCursorIsWhatMakesPagingWork() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse pageOne = controller.listCards(null, CicsAid.DFHENTER);

            CardListRequest faithful = new CardListRequest();
            faithful.setNavigationContext(pageOne.getNavigationContext());
            faithful.setPageCursor(pageOne.getPageCursor());
            forward(8, 9, 10, 11, 12, 13, 14, 15);

            CardListResponse pageTwo = controller.listCards(faithful, CicsAid.DFHPF8);

            assertThat(pageTwo.getPageCursor().screenNum()).isEqualTo(2);
            assertThat(pageTwo.screenRow(1).rowCardNum())
                    .as(":488-489 browses from WS-CA-LAST-CARD-NUM, which the client returned")
                    .isEqualTo(String.format("%016d", 8));
        }

        @Test
        @DisplayName("two identical calls answer identically, whatever order they run in")
        void identicalCallsAnswerIdentically() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse first = controller.listCards(showing(0, " "), CicsAid.DFHENTER);
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListResponse second = controller.listCards(showing(0, " "), CicsAid.DFHENTER);

            assertThat(second)
                    .as("no server-side state, and a fixed clock, so the two replies are equal")
                    .isEqualTo(first);
            assertThat(second.hashCode()).isEqualTo(first.hashCode());
            assertThat(second.fieldImages()).isEqualTo(first.fieldImages());
        }

        @Test
        @DisplayName("the outbound area is the same 254 bytes the inbound one was")
        void theAreaKeepsItsDeclaredWidth() {
            forward(1, 2, 3, 4, 5, 6, 7, 8);
            CardListRequest request = showing(0, " ");

            CardListResponse response = controller.listCards(request, CicsAid.DFHENTER);

            assertThat(request.getPageCursor().declaredLength())
                    .as("WS-CA-LAST-CARDKEY 27 + WS-CA-FIRST-CARDKEY 27 + four PIC X(1)/9(1) items")
                    .isEqualTo(CardListRequest.CURSOR_LENGTH)
                    .isEqualTo(58);
            assertThat(response.getPageCursor().declaredLength()).isEqualTo(58);
            assertThat(CardListRequest.PROG_COMMAREA_LENGTH)
                    .as("58 + WS-ALL-ROWS 196 = 254, the trailing part of the split at :328-331")
                    .isEqualTo(58 + 196)
                    .isEqualTo(254);
            // EIBCALEN reports the CARDDEMO-COMMAREA width, which is the first part of that split.
            assertThat(request.commareaLength())
                    .as("a request that carries an area reports 160, and one that does not reports 0")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(new CardListRequest().commareaLength())
                    .as("app/cbl/COCRDLIC.cbl:315 IF EIBCALEN = 0")
                    .isZero();
        }
    }

    // =================================================================================================
    // Synthesised read outcomes. A CardReadResult carries the decoded record AND the bytes it was
    // decoded from, because DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the record area and the
    // area's FILLER X(59) holds whatever the row held. A test constructing an outcome has no row, so the
    // image it supplies is the one a row of exactly this record would carry - which is what these two
    // helpers state, once, rather than at every call site.
    // =================================================================================================

    /**
     * The normal arm over a synthesised row of this record.
     *
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    /**
     * The duplicate-key arm over a synthesised row of this record.
     *
     * @param record the first record sharing the alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }
}
