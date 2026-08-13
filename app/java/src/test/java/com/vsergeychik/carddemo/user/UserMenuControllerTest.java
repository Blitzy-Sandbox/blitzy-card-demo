package com.vsergeychik.carddemo.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.SecUserRepository.BrowseCursor;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.UserMenuController.SentScreen;
import com.vsergeychik.carddemo.user.UserMenuController.SymbolicMap;
import com.vsergeychik.carddemo.user.UserMenuController.WorkArea;
import com.vsergeychik.carddemo.user.dto.UserListRequest;
import com.vsergeychik.carddemo.user.dto.UserListResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural tests for {@link UserMenuController}, the Java form of {@code app/cbl/COUSR00C.cbl}.
 */
@DisplayName("UserMenuController - COUSR00C, the user list screen (transaction CU00)")
class UserMenuControllerTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    private static final String TEST_DSNAME = "TEST.USRSEC.VSAM.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    private static final int EIGHTY = 80;

    private static final int EIGHT = 8;

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private static final List<String> USER_IDS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    private static final List<String> FIRST_NAMES = List.of(
            "MARGARET", "RUSSELL", "RAYMOND", "EMMANUEL", "GRANVILLE",
            "LAWRENCE", "AJITH", "LAURITZ", "AVERARDO", "LEE");

    private static final List<String> LAST_NAMES = List.of(
            "GOLD", "RUSSELL", "WHITMORE", "CASGRAIN", "LACHAPELLE",
            "THOMAS", "KUMAR", "ALME", "MAZZI", "TING");

    private static final List<String> USER_TYPES = List.of(
            "A", "A", "A", "A", "A", "U", "U", "U", "U", "U");

    private static final String FIXTURE_PASSWORD = "PASSWORD";

    private static final int PAGE_SIZE = 10;

    private static final String SYNTHETIC_ID_STEM = "ZUSER";

    private static List<String> fixtureImages(int count) {
        List<String> images = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            images.add(new String(SecUserRecord.encode(record(index), ASCII), ASCII));
        }
        return images;
    }

    private static SecUserRecord record(int index) {
        if (index < USER_IDS.size()) {
            return SecUserRecord.of(USER_IDS.get(index),
                    FIRST_NAMES.get(index),
                    LAST_NAMES.get(index),
                    FIXTURE_PASSWORD,
                    USER_TYPES.get(index),
                    ASCII);
        }
        int sequence = index - USER_IDS.size() + 1;
        return SecUserRecord.of(SYNTHETIC_ID_STEM + String.format("%03d", sequence),
                "SYNTHETIC" + sequence,
                "EXTRA" + sequence,
                FIXTURE_PASSWORD,
                "U",
                ASCII);
    }

    private static String userId(int index) {
        return record(index).secUsrId().strip();
    }

    private static DatasetBindings validBindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(SecUserRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, EIGHTY, "CSUSR01Y", EIGHT, null, null, null));
        return catalogue;
    }

    private static UserMenuController controllerOver(int count) {
        return new UserMenuController(new SecUserRepository(seeded(fixtureImages(count)),
                validBindings(), ASCII, RecordImageForm.CHARACTER), CODEC, CLOCK);
    }

    private static UserMenuController controllerOverMissingRelation() {
        return new UserMenuController(new SecUserRepository(emptyRelation(null), validBindings(), ASCII,
                RecordImageForm.CHARACTER), CODEC, CLOCK);
    }

    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyRelation("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                + RECORD_IMAGE_COLUMN + " VARCHAR(" + EIGHTY + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    private static JdbcTemplate emptyRelation(String ddl) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:usermenu" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        if (ddl != null) {
            template.execute(ddl);
        }
        return template;
    }

    private static UserListRequest reentering() {
        return UserListRequest.empty()
                .withNavigationContext(NavigationContext.empty().withPgmReenter());
    }

    private static UserListRequest entering() {
        return UserListRequest.empty()
                .withNavigationContext(NavigationContext.empty().withPgmEnter());
    }

    private static String rowUserId(UserListResponse response, int rowNumber) {
        return response.row(rowNumber).userId().strip();
    }

    private static String message(UserListResponse response) {
        return response.errMsg().strip();
    }

    @Nested
    @DisplayName("MAIN-PARA - :98-144")
    class MainPara {
        @Test
        @DisplayName(":110-112 an absent payload is EIBCALEN = 0 and returns to the sign-on screen")
        void anAbsentPayloadIsTheColdStart() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(null, CicsAid.DFHENTER, ws);

            assertThat(response.nextProgram().strip()).isEqualTo("COSGN00C");
            assertThat(ws.transferred()).isTrue();
            assertThat(ws.sends())
                    .as("an XCTL does not send a map, so no screen was transmitted")
                    .isEmpty();
        }

        @Test
        @DisplayName(":110 a payload carrying no communication area is the same EIBCALEN = 0 state")
        void anAbsentCommunicationAreaIsAlsoTheColdStart() {
            UserListRequest noArea = UserListRequest.empty().withoutNavigationContext();
            assertThat(noArea.hasNavigationContext()).isFalse();

            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(noArea, CicsAid.DFHENTER);

            assertThat(response.nextProgram().strip()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName(":508-510 a blank CDEMO-TO-PROGRAM defaults to the sign-on program")
        void aBlankTargetDefaultsToSignOn() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(PAGE_SIZE).returnToPrevScreen(ws);

            assertThat(response.nextProgram().strip()).isEqualTo("COSGN00C");
            assertThat(ws.commarea().fromTranid()).isEqualTo("CU00");
            assertThat(ws.commarea().fromProgram()).isEqualTo("COUSR00C");
            assertThat(ws.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }

        @Test
        @DisplayName(":508-510 a named CDEMO-TO-PROGRAM is honoured rather than defaulted")
        void aNamedTargetIsHonoured() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(UserListRequest.empty()
                    .withNavigationContext(NavigationContext.empty().withToProgram("COADM01C")));

            assertThat(controllerOver(PAGE_SIZE).returnToPrevScreen(ws).nextProgram().strip())
                    .isEqualTo("COADM01C");
        }

        @Test
        @DisplayName(":115-119 the ENTER path sets REENTER and lists from the top of the file")
        void firstEntrySetsReenterAndListsFromTheTop() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(entering(),
                    CicsAid.DFHENTER, ws);

            assertThat(ws.commarea().isReenter())
                    .as(":116 SET CDEMO-PGM-REENTER TO TRUE")
                    .isTrue();
            assertThat(response.navigationContext().isReenter()).isTrue();
            assertThat(rowUserId(response, 1)).isEqualTo("ADMIN001");
            assertThat(response.cdemoCu00PageNum()).isEqualTo(1);
        }

        @Test
        @DisplayName(":117 first entry discards the payload's screen fields, so a tick is not acted on")
        void firstEntryDiscardsTheScreenFields() {
            UserListRequest ticked = entering()
                    .withRow(3, UserListRequest.UserListRow.blank().withSel("U").withUsrId("ADMIN003"))
                    .withUsrIdIn("USER0003");

            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(ticked, CicsAid.DFHENTER);

            assertThat(response.nextProgram().strip())
                    .as("MOVE LOW-VALUES TO COUSR0AO blanked SEL0003 before it was read")
                    .isEmpty();
            assertThat(rowUserId(response, 1))
                    .as("and blanked USRIDINI too, so the browse started at LOW-VALUES")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName(":125-127 PF3 returns to the admin menu")
        void pf3ReturnsToTheAdminMenu() {
            WorkArea ws = new WorkArea();
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHPF3, ws);

            assertThat(response.nextProgram().strip()).isEqualTo("COADM01C");
            assertThat(ws.sends()).isEmpty();
        }

        @Test
        @DisplayName(":132-136 WHEN OTHER raises the error flag and shows CCDA-MSG-INVALID-KEY")
        void anUnhandledKeyIsRefused() {
            WorkArea ws = new WorkArea();
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHPF12, ws);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.usrIdInLength()).isEqualTo(UserMenuController.CURSOR_ON_USRIDIN);
            assertThat(message(response)).isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            assertThat(ws.sends()).hasSize(1);
            assertThat(ws.aidKey()).contains(AidKey.PFK12);
        }

        @Test
        @DisplayName(":132-136 an AID the resolver does not recognise also reaches WHEN OTHER")
        void anUnrecognisedAidAlsoReachesWhenOther() {
            WorkArea ws = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(reentering(), (byte) 0x01, ws);

            assertThat(ws.aidKey()).isEmpty();
            assertThat(ws.errFlgOn()).isTrue();
        }

        @Test
        @DisplayName(":100-103 then :114 - the inbound NEXT-PAGE-FLG survives the SET NEXT-PAGE-NO")
        void theInboundNextPageFlagSurvivesThePrologue() {
            WorkArea ws = new WorkArea();
            UserListRequest carryingYes = reentering().withNextPageYes()
                    .withCdemoCu00UsrIdLast("ADMIN005");

            controllerOver(PAGE_SIZE).listUsers(carryingYes, CicsAid.DFHPF8, ws);

            assertThat(ws.sends())
                    .as("PF8 paged forward, which it can only do if NEXT-PAGE-YES survived :102")
                    .isNotEmpty();
            assertThat(message(ws.sends().get(ws.sends().size() - 1).screen()))
                    .isNotEqualTo(UserMenuController.MSG_ALREADY_AT_BOTTOM);
        }

        @Test
        @DisplayName("two successive requests share no state at all (statelessness, rule R6)")
        void twoRequestsShareNothing() {
            UserMenuController controller = controllerOver(PAGE_SIZE);

            UserListResponse first = controller.listUsers(entering(), CicsAid.DFHENTER);
            UserListResponse second = controller.listUsers(entering(), CicsAid.DFHENTER);

            assertThat(second.cdemoCu00PageNum())
                    .as("the second request restarted the count, so nothing carried over")
                    .isEqualTo(first.cdemoCu00PageNum())
                    .isEqualTo(1);
            assertThat(rowUserId(second, 1)).isEqualTo(rowUserId(first, 1));
        }
    }

    @Nested
    @DisplayName("PROCESS-PAGE-FORWARD - :282-331")
    class ForwardPager {
        @Test
        @DisplayName("the first page fills all ten rows in ascending key order from LOW-VALUES")
        void theFirstPageFillsTenRowsInKeyOrder() {
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER);

            for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
                assertThat(rowUserId(response, rowNumber)).isEqualTo(USER_IDS.get(rowNumber - 1));
                assertThat(response.row(rowNumber).firstName().strip())
                        .isEqualTo(FIRST_NAMES.get(rowNumber - 1));
                assertThat(response.row(rowNumber).lastName().strip())
                        .isEqualTo(LAST_NAMES.get(rowNumber - 1));
                assertThat(response.row(rowNumber).userType()).isEqualTo(USER_TYPES.get(rowNumber - 1));
            }
        }

        @Test
        @DisplayName(":309-310 a full page whose look-ahead fails counts the page and says NEXT-PAGE-NO")
        void aFullFinalPageTakesTheL309Increment() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(entering(),
                    CicsAid.DFHENTER, ws);

            assertThat(ws.idx())
                    .as("the fill loop ran out of ROWS, so WS-IDX reached 11")
                    .isEqualTo(PAGE_SIZE + 1);
            assertThat(response.cdemoCu00PageNum()).isEqualTo(1);
            assertThat(response.nextPageNo()).isTrue();
            assertThat(message(response)).isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
        }

        @Test
        @DisplayName(":320-321 a short page counts through the WS-IDX > 1 guard instead")
        void aShortPageTakesTheL320Increment() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(5).listUsers(entering(), CicsAid.DFHENTER, ws);

            assertThat(ws.idx())
                    .as("the fill loop ran out of RECORDS after five, so WS-IDX stopped at six")
                    .isEqualTo(6);
            assertThat(response.cdemoCu00PageNum()).isEqualTo(1);
            assertThat(response.nextPageNo()).isTrue();
            assertThat(rowUserId(response, 5)).isEqualTo("ADMIN005");
            assertThat(rowUserId(response, 6))
                    .as("rows the fill never reached stay blank rather than being dropped")
                    .isEmpty();
        }

        @Test
        @DisplayName(":312-313 a page with a record beyond it says NEXT-PAGE-YES and no bottom message")
        void aPageWithMoreToComeSaysNextPageYes() {
            UserListResponse response = controllerOver(PAGE_SIZE + 1)
                    .listUsers(entering(), CicsAid.DFHENTER);

            assertThat(response.nextPageYes()).isTrue();
            assertThat(message(response)).isEmpty();
        }

        @Test
        @DisplayName(":319 WS-IDX = 1 leaves the page count alone, so an empty page does not advance it")
        void anEmptyPageDoesNotAdvanceTheCount() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(4));
            ws.map().usrIdIn(CODEC.movePicX("ZZZZZZZZ", EIGHT));

            controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(ws.idx()).isEqualTo(1);
            assertThat(ws.cu00PageNum())
                    .as(":227 zeroed it and :319's guard refused to advance it")
                    .isZero();
        }

        @Test
        @DisplayName(":288 PF8 discards the anchor record; ENTER does not")
        void theAnchorIsDiscardedOnlyForTheKeysThatNeedIt() {
            WorkArea forPf8 = new WorkArea();
            forPf8.acceptCommarea(reentering().withNextPageYes().withCdemoCu00UsrIdLast("ADMIN005"));
            controllerOver(PAGE_SIZE).processPf8Key(forPf8, CicsAid.DFHPF8);

            assertThat(forPf8.map().usrId(1))
                    .as("ADMIN005 was the anchor and was discarded, so the page starts after it")
                    .isEqualTo(CODEC.movePicX("USER0001", EIGHT));

            WorkArea forEnter = new WorkArea();
            forEnter.acceptCommarea(reentering());
            forEnter.map().usrIdIn(CODEC.movePicX("ADMIN005", EIGHT));
            controllerOver(PAGE_SIZE).processEnterKey(forEnter, CicsAid.DFHENTER);

            assertThat(forEnter.map().usrId(1))
                    .as("ENTER keeps the record the start key names")
                    .isEqualTo(CODEC.movePicX("ADMIN005", EIGHT));
        }

        @Test
        @DisplayName(":292 the ten rows are NOT blanked when the guard fails, so stale rows survive")
        void staleRowsSurviveWhenTheBlankingGuardFails() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageYes());
            ws.map().usrId(4, CODEC.movePicX("STALEROW", EIGHT));

            controllerOver(PAGE_SIZE).processPf8Key(ws, CicsAid.DFHPF8);

            assertThat(ws.userSecEof()).isTrue();
            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.map().usrId(4))
                    .as("the blanking loop at :293 was skipped, so row 4 kept what it held")
                    .isEqualTo(CODEC.movePicX("STALEROW", EIGHT));
        }

        @Test
        @DisplayName(":289 a successful STARTBR always yields a record, so the discard read never ends file")
        void theDiscardReadCannotEndTheFileAfterASuccessfulStartbr() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageYes().withCdemoCu00UsrIdLast("USER0005"));
            ws.map().usrId(4, CODEC.movePicX("STALEROW", EIGHT));

            controllerOver(PAGE_SIZE).processPf8Key(ws, CicsAid.DFHPF8);

            assertThat(ws.map().usrId(4))
                    .as("USER0005 exists, so the read at :289 found it and the blanking loop DID run")
                    .isBlank();
            assertThat(ws.userSecEof())
                    .as("end of file then arrived from the fill loop instead, one read later")
                    .isTrue();
            assertThat(ws.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName(":328 the forward page blanks USRIDINO once the page it asked for exists")
        void theForwardPageClearsTheStartKeyField() {
            UserListResponse response = controllerOver(PAGE_SIZE)
                    .listUsers(reentering().withUsrIdIn("ADMIN003"), CicsAid.DFHENTER);

            assertThat(response.usrIdIn().strip()).isEmpty();
        }

        @Test
        @DisplayName(":327 PAGENUMO is the PIC 9(08) page number zero-filled to eight characters")
        void thePageNumberIsRenderedZeroFilled() {
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER);

            assertThat(response.pageNum()).isEqualTo("00000001");
            assertThat(response.pageNum()).hasSize(UserListResponse.PAGENUM_LENGTH);
        }
    }

    @Nested
    @DisplayName("PROCESS-PAGE-BACKWARD, PF7 and PF8 - :237-277 and :336-379")
    class BackwardPager {
        @Test
        @DisplayName(":248-254 PF7 on page 1 refuses with 'already at the top' and no ERASE")
        void pf7OnTheFirstPageRefuses() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(1));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_ALREADY_AT_TOP);
            assertThat(ws.sendEraseYes())
                    .as(":253 SET SEND-ERASE-NO TO TRUE")
                    .isFalse();
            assertThat(ws.sends()).hasSize(1);
            assertThat(ws.sends().get(0).erase()).isFalse();
            assertThat(ws.nextPageYes())
                    .as(":245 sets it before the page test, unconditionally")
                    .isTrue();
        }

        @Test
        @DisplayName(":248-249 PF7 beyond page 1 pages back onto a full previous page")
        void pf7BeyondTheFirstPagePagesBack() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(2)
                    .withCdemoCu00UsrIdFirst(userId(PAGE_SIZE + 1)));

            controllerOver(PAGE_SIZE + 5).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.cu00PageNum())
                    .as(":367 decremented the count")
                    .isEqualTo(1);
            assertThat(ws.map().usrId(PAGE_SIZE).strip())
                    .as("the fill DESCENDS, so the highest key below the anchor lands in row 10")
                    .isEqualTo(userId(PAGE_SIZE));
            assertThat(ws.map().usrId(1).strip())
                    .as("and the lowest of the ten lands in row 1, so the page still reads ascending")
                    .isEqualTo("ADMIN002");
            assertThat(ws.cu00UsrIdFirst().strip())
                    .as(":388-389 row 1 also sets CDEMO-CU00-USRID-FIRST")
                    .isEqualTo("ADMIN002");
            assertThat(ws.cu00UsrIdLast().strip())
                    .as(":434-435 row 10 also sets CDEMO-CU00-USRID-LAST")
                    .isEqualTo(userId(PAGE_SIZE));
        }

        @Test
        @DisplayName(":362 a backward page that reached the start of the file leaves the count UNCHANGED")
        void aBackwardPageThatHitsTheStartLeavesTheCountAlone() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(9).withCdemoCu00UsrIdFirst("ADMIN004"));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.userSecEof()).isTrue();
            assertThat(ws.cu00PageNum())
                    .as("the fill loop ended the file, so the guard at :362 skipped :363-371 entirely "
                            + "and neither :367 nor :369 ran")
                    .isEqualTo(9);
        }

        @Test
        @DisplayName(":369 a full backward page whose look-behind ends the file is renumbered page 1")
        void aFullBackwardPageWhoseLookBehindEndsTheFileBecomesPageOne() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(9)
                    .withCdemoCu00UsrIdFirst(userId(PAGE_SIZE)).withNextPageYes());

            controllerOver(PAGE_SIZE + 1).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.idx()).isZero();
            assertThat(ws.cu00PageNum()).isEqualTo(1);
        }

        @Test
        @DisplayName(":367 a full backward page with a record still behind it decrements the count")
        void aFullBackwardPageDecrementsTheCount() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(3)
                    .withCdemoCu00UsrIdFirst(userId(PAGE_SIZE + 1)).withNextPageYes());

            controllerOver(PAGE_SIZE + 5).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.cu00PageNum()).isEqualTo(2);
        }

        @Test
        @DisplayName(":369 a full backward page already numbered 1 stays at 1")
        void aFullBackwardPageAlreadyNumberedOneStaysThere() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(1).withNextPageYes());
            ws.secUsrId(CODEC.movePicX(userId(PAGE_SIZE + 1), EIGHT));

            controllerOver(PAGE_SIZE + 5).processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.cu00PageNum())
                    .as("the > 1 guard at :366 fails, so :369 puts it at 1 rather than decrementing to 0 "
                            + "- which an unsigned PIC 9(08) could not have represented")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(":352-358 a short backward page leaves its LEADING rows blank")
        void aShortBackwardPageLeavesLeadingRowsBlank() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(2).withCdemoCu00UsrIdFirst("ADMIN004"));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.map().usrId(PAGE_SIZE).strip()).isEqualTo("ADMIN003");
            assertThat(ws.map().usrId(1).strip())
                    .as("the fill stopped before reaching row 1")
                    .isEmpty();
            assertThat(ws.cu00UsrIdFirst().strip())
                    .as("row 1 is what sets CDEMO-CU00-USRID-FIRST, and it was never reached - so the "
                            + "anchor keeps the value it arrived with, because INITIALIZE-USER-DATA does "
                            + "not clear it either")
                    .isEqualTo("ADMIN004");
        }

        @Test
        @DisplayName(":377 the backward page does NOT blank USRIDINO, unlike :328")
        void theBackwardPageLeavesTheStartKeyFieldAlone() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(2).withCdemoCu00UsrIdFirst("USER0001"));
            ws.map().usrIdIn(CODEC.movePicX("KEPT", EIGHT));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.map().usrIdIn().strip()).isEqualTo("KEPT");
        }

        @Test
        @DisplayName(":270-276 PF8 with NEXT-PAGE-NO refuses with 'already at the bottom' and no ERASE")
        void pf8AtTheBottomRefuses() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageNo());

            controllerOver(PAGE_SIZE).processPf8Key(ws, CicsAid.DFHPF8);

            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_ALREADY_AT_BOTTOM);
            assertThat(ws.sendEraseYes()).isFalse();
            assertThat(ws.sends()).hasSize(1);
        }

        @Test
        @DisplayName(":262-263 PF8 with no last identifier anchors on HIGH-VALUES, which finds nothing")
        void pf8WithoutAnAnchorUsesHighValues() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageYes());

            controllerOver(PAGE_SIZE).processPf8Key(ws, CicsAid.DFHPF8);

            assertThat(ws.secUsrId()).isEqualTo(SecUserRepository.HIGH_VALUES_KEY);
            assertThat(ws.userSecEof())
                    .as("no key is at or after high values, so the STARTBR reported NOTFND")
                    .isTrue();
            assertThat(ws.sends()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(message(ws.sends().get(0).screen()))
                    .as(":603 the STARTBR NOTFND arm paints the 'top of the page' text first")
                    .isEqualTo(UserMenuController.MSG_AT_TOP);
            assertThat(ws.errFlgOn())
                    .as(":288 then read from a browse that was never opened, which is WHEN OTHER")
                    .isTrue();
            assertThat(ws.message().strip())
                    .as("so the LAST message the operator is left with is the lookup failure")
                    .isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
        }

        @Test
        @DisplayName(":239-240 PF7 with no first identifier anchors on LOW-VALUES")
        void pf7WithoutAnAnchorUsesLowValues() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(1));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.secUsrId()).isEqualTo(SecUserRepository.LOW_VALUES_KEY);
        }

        @Test
        @DisplayName(":242 PF7 with a first identifier anchors on it")
        void pf7WithAnAnchorUsesIt() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(1).withCdemoCu00UsrIdFirst("USER0003"));

            controllerOver(PAGE_SIZE).processPf7Key(ws, CicsAid.DFHPF7);

            assertThat(ws.secUsrId()).isEqualTo(CODEC.movePicX("USER0003", EIGHT));
        }

        @Test
        @DisplayName(":265 PF8 with a last identifier anchors on it rather than on HIGH-VALUES")
        void pf8WithAnAnchorUsesIt() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageNo().withCdemoCu00UsrIdLast("USER0002"));

            controllerOver(PAGE_SIZE).processPf8Key(ws, CicsAid.DFHPF8);

            assertThat(ws.secUsrId()).isEqualTo(CODEC.movePicX("USER0002", EIGHT));
        }

        @Test
        @DisplayName("RIDFLD advances with the browse, as CICS updates it on every READNEXT")
        void theRidfieldAdvancesWithTheBrowse() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());

            controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(ws.secUsrId().strip())
                    .as("the last record the browse returned was the tenth")
                    .isEqualTo("USER0005");
            assertThat(ws.secUserData().secUsrId().strip()).isEqualTo("USER0005");
        }

        @Test
        @DisplayName(":342 the two-operand relation lets PF7 through and holds ENTER and PF8 back")
        void theBackwardRelationNamesTwoOperands() {
            WorkArea viaEnter = new WorkArea();
            viaEnter.acceptCommarea(reentering().withCdemoCu00PageNum(2).withNextPageYes());
            viaEnter.secUsrId(CODEC.movePicX("USER0001", EIGHT));
            controllerOver(PAGE_SIZE + 5).processPageBackward(viaEnter, CicsAid.DFHENTER);

            assertThat(viaEnter.map().usrId(PAGE_SIZE).strip())
                    .as("ENTER keeps the anchor, so USER0001 itself lands in row 10")
                    .isEqualTo("USER0001");

            WorkArea viaPf7 = new WorkArea();
            viaPf7.acceptCommarea(reentering().withCdemoCu00PageNum(2).withNextPageYes());
            viaPf7.secUsrId(CODEC.movePicX("USER0001", EIGHT));
            controllerOver(PAGE_SIZE + 5).processPageBackward(viaPf7, CicsAid.DFHPF7);

            assertThat(viaPf7.map().usrId(PAGE_SIZE).strip())
                    .as("PF7 discards it, so the record below it lands in row 10")
                    .isEqualTo("ADMIN005");
        }

        @Test
        @DisplayName(":364 IF NEXT-PAGE-YES is what gates the page-number change")
        void theBackwardPageOnlyRenumbersWhenNextPageYesHolds() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(7).withNextPageNo());
            ws.secUsrId(CODEC.movePicX(userId(PAGE_SIZE + 1), EIGHT));

            controllerOver(PAGE_SIZE + 5).processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.userSecEof())
                    .as("the fill ran out of rows, not records, so :362 held")
                    .isFalse();
            assertThat(ws.cu00PageNum())
                    .as("with NEXT-PAGE-NO the whole IF at :364-371 is skipped")
                    .isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY selection - :151-216")
    class Selection {
        @ParameterizedTest(name = "row {0} ticked in isolation is the row acted on")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName(":152-181 each of the ten arms, driven on its own")
        void everyRowCanBeTheSelectedRow(int rowNumber) {
            WorkArea ws = new WorkArea();
            ws.map().sel(rowNumber, "U");
            ws.map().usrId(rowNumber, CODEC.movePicX(USER_IDS.get(rowNumber - 1), EIGHT));

            controllerOver(PAGE_SIZE).selectTickedRow(ws);

            assertThat(ws.selectedRow()).isEqualTo(rowNumber);
            assertThat(ws.cu00UsrSelFlg()).isEqualTo("U");
            assertThat(ws.cu00UsrSelected().strip()).isEqualTo(USER_IDS.get(rowNumber - 1));
        }

        @Test
        @DisplayName(":151 EVALUATE TRUE is ordered, so with two rows ticked the FIRST wins")
        void theFirstTickedRowWins() {
            WorkArea ws = new WorkArea();
            ws.map().sel(4, "U");
            ws.map().usrId(4, CODEC.movePicX("ADMIN004", EIGHT));
            ws.map().sel(9, "D");
            ws.map().usrId(9, CODEC.movePicX("USER0004", EIGHT));

            controllerOver(PAGE_SIZE).selectTickedRow(ws);

            assertThat(ws.selectedRow())
                    .as("row 4's WHEN precedes row 9's, and only the first matching arm executes")
                    .isEqualTo(4);
            assertThat(ws.cu00UsrSelected().strip()).isEqualTo("ADMIN004");
        }

        @Test
        @DisplayName(":182-184 WHEN OTHER clears both the flag and the identifier")
        void nothingTickedClearsBothFields() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00UsrSelFlg("U")
                    .withCdemoCu00UsrSelected("ADMIN001"));

            controllerOver(PAGE_SIZE).selectTickedRow(ws);

            assertThat(ws.selectedRow()).isZero();
            assertThat(ws.cu00UsrSelFlg()).isBlank();
            assertThat(ws.cu00UsrSelected()).isBlank();
        }

        @Test
        @DisplayName("a low-value cell counts as blank, exactly as SPACES does")
        void aLowValueCellIsBlank() {
            WorkArea ws = new WorkArea();
            ws.map().sel(2, "\u0000");
            ws.map().usrId(2, CODEC.movePicX("ADMIN002", EIGHT));

            controllerOver(PAGE_SIZE).selectTickedRow(ws);

            assertThat(ws.selectedRow()).isZero();
        }

        @ParameterizedTest(name = "selection ''{0}'' transfers to {1}")
        @CsvSource({"U,COUSR02C", "u,COUSR02C", "D,COUSR03C", "d,COUSR03C"})
        @DisplayName(":190-209 both cases of both selection characters transfer, and to the right program")
        void bothCasesOfBothSelectionsTransfer(String selection, String expectedProgram) {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            ws.map().sel(1, selection);
            ws.map().usrId(1, CODEC.movePicX("ADMIN001", EIGHT));

            UserListResponse response = controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(response).as("an XCTL leaves the paragraph immediately").isNotNull();
            assertThat(response.nextProgram().strip()).isEqualTo(expectedProgram);
            assertThat(response.cdemoCu00UsrSelected().strip()).isEqualTo("ADMIN001");
            assertThat(response.navigationContext().fromTranid()).isEqualTo("CU00");
            assertThat(response.navigationContext().fromProgram()).isEqualTo("COUSR00C");
            assertThat(response.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(ws.transferred()).isTrue();
            assertThat(ws.sends())
                    .as("the transfer skips :228's page and :119's send entirely")
                    .isEmpty();
        }

        @Test
        @DisplayName(":510-517 a transfer carries the whole CU00 extension to the next program")
        void aTransferCarriesThePagingState() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(3)
                    .withCdemoCu00UsrIdFirst("USER0001").withCdemoCu00UsrIdLast("USER0005")
                    .withNextPageYes());
            ws.map().sel(1, "D");
            ws.map().usrId(1, CODEC.movePicX("USER0003", EIGHT));

            UserListResponse response = controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(response.cdemoCu00PageNum()).isEqualTo(3);
            assertThat(response.cdemoCu00UsrIdFirst().strip()).isEqualTo("USER0001");
            assertThat(response.cdemoCu00UsrIdLast().strip()).isEqualTo("USER0005");
            assertThat(response.nextPageYes()).isTrue();
        }

        @ParameterizedTest(name = "selection ''{0}'' is refused")
        @ValueSource(strings = {"X", "1", "x", "?", "Y", "N"})
        @DisplayName(":210-214 WHEN OTHER shows the exact invalid-selection text and still pages forward")
        void anInvalidSelectionIsRefusedButStillLists(String selection) {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            ws.map().sel(1, selection);
            ws.map().usrId(1, CODEC.movePicX("ADMIN001", EIGHT));

            UserListResponse response = controllerOver(PAGE_SIZE + 1)
                    .processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(response).as("no transfer, so the paragraph ran to its end").isNull();
            assertThat(ws.usrIdInLength()).isEqualTo(UserMenuController.CURSOR_ON_USRIDIN);
            UserListResponse screen = ws.sends().get(ws.sends().size() - 1).screen();
            assertThat(message(screen))
                    .isEqualTo("Invalid selection. Valid values are U and D")
                    .isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE)
                    .doesNotEndWith("...");
            assertThat(rowUserId(screen, 1))
                    .as("and the page was still listed underneath the complaint")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName(":187-188 a tick on a blank row is not acted on, because the identifier is blank")
        void aTickOnABlankRowIsNotActedOn() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            ws.map().sel(7, "U");

            UserListResponse response = controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(response).as("no transfer and no complaint - the guard simply failed").isNull();
            assertThat(ws.message().strip()).isNotEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
        }

        @Test
        @DisplayName(":218-221 a typed start key repositions the browse; :227 restarts the page count")
        void aTypedStartKeyRepositionsTheBrowse() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(6));
            ws.map().usrIdIn(CODEC.movePicX("USER0002", EIGHT));

            controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(ws.map().usrId(1).strip()).isEqualTo("USER0002");
            assertThat(ws.cu00PageNum())
                    .as(":227 zeroed the count, and the short page then took the :320 increment")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("The four browse paragraphs - :586-691")
    class BrowseArms {
        @Test
        @DisplayName(":598 STARTBR NORMAL sets no flag and paints no message")
        void startBrowseNormalIsSilent() {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = controllerOver(PAGE_SIZE).startBrowseUserSecFile(ws);

            assertThat(cursor.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.errFlgOn()).isFalse();
            assertThat(ws.message().strip()).isEmpty();
            assertThat(ws.sends()).isEmpty();
            assertThat(ws.respCd()).isEqualTo(FileStatus.NORMAL);
        }

        @Test
        @DisplayName(":600-606 STARTBR NOTFND ends the file, paints 'at the top' and sets NO error flag")
        void startBrowseNotFoundEndsTheFileWithoutAnError() {
            WorkArea ws = new WorkArea();
            ws.secUsrId(SecUserRepository.HIGH_VALUES_KEY);

            BrowseCursor cursor = controllerOver(PAGE_SIZE).startBrowseUserSecFile(ws);

            assertThat(cursor.openOutcome()).isEqualTo(FileStatus.Outcome.NOT_FOUND);
            assertThat(ws.userSecEof())
                    .as(":602 - the bare CONTINUE at :601 is a no-op, so this DID run")
                    .isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_AT_TOP);
            assertThat(ws.errFlgOn())
                    .as("only WHEN OTHER raises the error flag, which is what lets :286 carry on")
                    .isFalse();
            assertThat(ws.usrIdInLength()).isEqualTo(UserMenuController.CURSOR_ON_USRIDIN);
            assertThat(ws.sends()).hasSize(1);
        }

        @Test
        @DisplayName(":607-613 STARTBR WHEN OTHER raises the error flag and displays RESP and REAS")
        void startBrowseOtherRaisesTheErrorFlag() {
            WorkArea ws = new WorkArea();

            controllerOverMissingRelation().startBrowseUserSecFile(ws);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
            assertThat(ws.usrIdInLength()).isEqualTo(UserMenuController.CURSOR_ON_USRIDIN);
            assertThat(ws.sends()).hasSize(1);
            assertThat(ws.displays())
                    .as(":608 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD")
                    .hasSize(1);
            assertThat(ws.displays().get(0))
                    .startsWith(UserMenuController.DISPLAY_RESP)
                    .contains(UserMenuController.DISPLAY_REAS);

            assertThat(ws.respCd())
                    .isEqualTo(FileStatus.RESP_NOT_REPORTED)
                    .isNotEqualTo(FileStatus.NORMAL);
            assertThat(FileStatus.respReported(ws.respCd())).isFalse();
            assertThat(ws.displays().get(0))
                    .as("the response operand is not a number, because there is no response code")
                    .isEqualTo(UserMenuController.DISPLAY_RESP
                            + FileStatus.respNotReportedImage(UserMenuController.WS_RESP_CD_DIGITS)
                            + UserMenuController.DISPLAY_REAS + FileStatus.NO_REASON_CODE)
                    .doesNotContain(UserMenuController.DISPLAY_RESP + FileStatus.NORMAL
                            + UserMenuController.DISPLAY_REAS);
        }

        @Test
        @DisplayName("a READNEXT that DOES report a response renders that response as its number")
        void aReportedResponseStaysNumeric() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOver(1);
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);
            controller.readNextUserSecFile(ws, cursor);

            controller.readNextUserSecFile(ws, cursor);

            assertThat(ws.respCd()).isEqualTo(FileStatus.ENDFILE);
            assertThat(FileStatus.respReported(ws.respCd())).isTrue();
        }

        @Test
        @DisplayName(":632 READNEXT NORMAL returns the record and stores it in SEC-USER-DATA")
        void readNextNormalStoresTheRecord() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOver(PAGE_SIZE);
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);

            ReadResult read = controller.readNextUserSecFile(ws, cursor);

            assertThat(read.isFound()).isTrue();
            assertThat(ws.secUserData().secUsrId().strip()).isEqualTo("ADMIN001");
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.errFlgOn()).isFalse();
            assertThat(ws.sends()).isEmpty();
        }

        @Test
        @DisplayName(":634-640 READNEXT ENDFILE ends the file and paints 'reached the bottom'")
        void readNextEndOfFilePaintsTheBottomMessage() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOver(1);
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);
            controller.readNextUserSecFile(ws, cursor);

            ReadResult past = controller.readNextUserSecFile(ws, cursor);

            assertThat(past.isEndOfFile()).isTrue();
            assertThat(ws.userSecEof()).isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
            assertThat(ws.errFlgOn()).isFalse();
            assertThat(ws.secUserData().secUsrId().strip())
                    .as("the record area is not blanked by a failed read")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName(":641-647 READNEXT WHEN OTHER raises the error flag and displays the codes")
        void readNextOtherRaisesTheErrorFlag() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOverMissingRelation();
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);
            ws.displays().size();

            controller.readNextUserSecFile(ws, cursor);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
            assertThat(ws.displays()).hasSize(2);
        }

        @Test
        @DisplayName(":666 READPREV NORMAL returns the record")
        void readPreviousNormalReturnsTheRecord() {
            WorkArea ws = new WorkArea();
            ws.secUsrId(CODEC.movePicX("ADMIN003", EIGHT));
            UserMenuController controller = controllerOver(PAGE_SIZE);
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);

            ReadResult read = controller.readPrevUserSecFile(ws, cursor);

            assertThat(read.isFound()).isTrue();
            assertThat(read.requireRecord().secUsrId().strip()).isEqualTo("ADMIN003");
        }

        @Test
        @DisplayName(":668-674 READPREV ENDFILE paints 'reached the TOP', not 'the bottom'")
        void readPreviousEndOfFilePaintsTheTopMessage() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOver(PAGE_SIZE);
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);
            controller.readPrevUserSecFile(ws, cursor);

            ReadResult past = controller.readPrevUserSecFile(ws, cursor);

            assertThat(past.isEndOfFile()).isTrue();
            assertThat(ws.userSecEof()).isTrue();
            assertThat(ws.message().strip())
                    .isEqualTo(UserMenuController.MSG_REACHED_TOP)
                    .isNotEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
        }

        @Test
        @DisplayName(":675-681 READPREV WHEN OTHER raises the error flag")
        void readPreviousOtherRaisesTheErrorFlag() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOverMissingRelation();
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);

            controller.readPrevUserSecFile(ws, cursor);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
        }

        @Test
        @DisplayName(":687-691 ENDBR reports nothing and can be called on a browse that never opened")
        void endBrowseReportsNothing() {
            WorkArea ws = new WorkArea();
            UserMenuController controller = controllerOverMissingRelation();
            BrowseCursor cursor = controller.startBrowseUserSecFile(ws);
            boolean errorBefore = ws.errFlgOn();
            int sendsBefore = ws.sends().size();

            controller.endBrowse(cursor);

            assertThat(cursor.isEnded()).isTrue();
            assertThat(ws.errFlgOn())
                    .as("ENDBR specifies no RESP, so it can neither set nor clear a flag")
                    .isEqualTo(errorBefore);
            assertThat(ws.sends()).hasSize(sendsBefore);
        }

        @Test
        @DisplayName("every FileStatus outcome the three commands can report is distinguished")
        void everyOutcomeIsDistinguished() {
            assertThat(List.of(FileStatus.Outcome.OK, FileStatus.Outcome.NOT_FOUND,
                            FileStatus.Outcome.END_OF_FILE, FileStatus.Outcome.OTHER))
                    .as("the four the arms of this screen branch on")
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("SEND-USRLST-SCREEN and POPULATE-HEADER-INFO - :522-581")
    class ScreenContract {
        @Test
        @DisplayName(":566-581 the header carries both titles, the transaction, the program and the clock")
        void theHeaderIsPopulatedFromItsSixSources() {
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER);

            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.trnName()).isEqualTo("CU00");
            assertThat(response.pgmName()).isEqualTo("COUSR00C");
            assertThat(response.curDate())
                    .as(":571-575 WS-CURDATE-MM-DD-YY, with the year reference-modified to two digits")
                    .isEqualTo("07/19/22");
            assertThat(response.curTime())
                    .as(":577-581 WS-CURTIME-HH-MM-SS")
                    .isEqualTo("23:12:34");
        }

        @Test
        @DisplayName("the clock is injected, so the header is deterministic rather than wall-clock")
        void theHeaderComesFromTheInjectedClock() {
            UserMenuController atAnotherInstant = new UserMenuController(
                    new SecUserRepository(seeded(fixtureImages(PAGE_SIZE)), validBindings(), ASCII,
                            RecordImageForm.CHARACTER),
                    CODEC,
                    Clock.fixed(Instant.parse("2001-02-03T04:05:06Z"), ZoneOffset.UTC));

            UserListResponse response = atAnotherInstant.listUsers(entering(), CicsAid.DFHENTER);

            assertThat(response.curDate()).isEqualTo("02/03/01");
            assertThat(response.curTime()).isEqualTo("04:05:06");
        }

        @Test
        @DisplayName("all 59 map fields are present at exactly their declared widths")
        void everyMapFieldIsAtItsDeclaredWidth() {
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER);

            assertThat(response.trnName()).hasSize(UserListResponse.TRNNAME_LENGTH);
            assertThat(response.title01()).hasSize(UserListResponse.TITLE01_LENGTH);
            assertThat(response.curDate()).hasSize(UserListResponse.CURDATE_LENGTH);
            assertThat(response.pgmName()).hasSize(UserListResponse.PGMNAME_LENGTH);
            assertThat(response.title02()).hasSize(UserListResponse.TITLE02_LENGTH);
            assertThat(response.curTime()).hasSize(UserListResponse.CURTIME_LENGTH);
            assertThat(response.pageNum()).hasSize(UserListResponse.PAGENUM_LENGTH);
            assertThat(response.usrIdIn()).hasSize(UserListResponse.USRIDIN_LENGTH);
            assertThat(response.errMsg()).hasSize(UserListResponse.ERRMSG_LENGTH);

            for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
                UserListResponse.Row row = response.row(rowNumber);
                assertThat(row.selection()).hasSize(UserListResponse.SEL_LENGTH);
                assertThat(row.userId()).hasSize(UserListResponse.USRID_LENGTH);
                assertThat(row.firstName()).hasSize(UserListResponse.FNAME_LENGTH);
                assertThat(row.lastName()).hasSize(UserListResponse.LNAME_LENGTH);
                assertThat(row.userType()).hasSize(UserListResponse.UTYPE_LENGTH);
            }

            assertThat(UserListResponse.MAP_FIELD_COUNT)
                    .as("8 header + 10 rows of 5 + 1 message, matching the labelled DFHMDF count")
                    .isEqualTo(8 + PAGE_SIZE * 5 + 1)
                    .isEqualTo(59);
        }

        @Test
        @DisplayName(":526 WS-MESSAGE is 80 and ERRMSGO is 78, so the move truncates on the RIGHT")
        void theMessageIsNarrowedOnTheRight() {
            WorkArea ws = new WorkArea();
            String tooLong = "X".repeat(UserMenuController.WS_MESSAGE_LENGTH);
            ws.acceptCommarea(reentering());
            controllerOver(PAGE_SIZE).populateHeaderInfo(ws);

            assertThat(UserListResponse.ERRMSG_LENGTH)
                    .as("the narrowing must stay a narrowing")
                    .isLessThan(UserMenuController.WS_MESSAGE_LENGTH);
            assertThat(CODEC.movePicX(CODEC.movePicX(tooLong, UserMenuController.WS_MESSAGE_LENGTH),
                    UserListResponse.ERRMSG_LENGTH))
                    .hasSize(UserListResponse.ERRMSG_LENGTH)
                    .isEqualTo("X".repeat(UserListResponse.ERRMSG_LENGTH));
        }

        @Test
        @DisplayName("every message this screen can display fits ERRMSGO without losing a character")
        void everyMessageFits() {
            for (String text : UserMenuController.messageTexts()) {
                assertThat(text.length())
                        .as("message '%s'", text)
                        .isLessThanOrEqualTo(UserListResponse.ERRMSG_LENGTH);
            }
            assertThat(UserMenuController.messageTexts()).hasSize(8).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName(":528-544 the ERASE choice is recorded on every send, not discarded")
        void theEraseChoiceIsRecorded() {
            WorkArea erasing = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER, erasing);
            assertThat(erasing.sends()).isNotEmpty();
            assertThat(erasing.sends()).allSatisfy(sent -> assertThat(sent.erase())
                    .as("nothing cleared the flag, so every send took the ERASE arm")
                    .isTrue());

            WorkArea notErasing = new WorkArea();
            notErasing.acceptCommarea(reentering().withCdemoCu00PageNum(1));
            controllerOver(PAGE_SIZE).processPf7Key(notErasing, CicsAid.DFHPF7);
            assertThat(notErasing.sends()).hasSize(1);
            assertThat(notErasing.sends().get(0).erase())
                    .as(":541 the ERASE operand is commented out in that arm")
                    .isFalse();
        }

        @Test
        @DisplayName("the CURSOR operand travels with each send, as USRIDINL not as a payload member")
        void theCursorOperandIsRecorded() {
            WorkArea ws = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER, ws);

            assertThat(ws.sends()).isNotEmpty();
            assertThat(ws.sends()).allSatisfy(sent -> assertThat(sent.cursorPosition())
                    .isEqualTo(UserMenuController.CURSOR_ON_USRIDIN));
        }

        @Test
        @DisplayName("every send is recorded, and the body is the last of them - first entry sends 3 times")
        void everySendIsRecordedAndTheLastIsReturned() {
            WorkArea ws = new WorkArea();
            UserListResponse body = controllerOver(PAGE_SIZE).listUsers(entering(),
                    CicsAid.DFHENTER, ws);

            assertThat(ws.sends()).hasSize(3);
            assertThat(body)
                    .as("the terminal is left displaying the last transmission")
                    .isSameAs(ws.sends().get(2).screen());
            assertThat(message(ws.sends().get(0).screen()))
                    .isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
            assertThat(message(body))
                    .as("WS-MESSAGE is not cleared between sends, so the text survives to the last one")
                    .isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("the REENTER path has no :119 send, so the same page transmits twice rather than 3 times")
        void theReenterPathSendsOneFewerTime() {
            WorkArea ws = new WorkArea();
            UserListResponse body = controllerOver(PAGE_SIZE).listUsers(reentering(),
                    CicsAid.DFHENTER, ws);

            assertThat(ws.sends())
                    .as(":124 performs PROCESS-ENTER-KEY and, unlike :118-119, does not send after it")
                    .hasSize(2);
            assertThat(body).isSameAs(ws.sends().get(1).screen());
        }

        @Test
        @DisplayName("EXEC CICS RETURN renders the current screen when nothing was sent")
        void nothingSentStillRendersAScreen() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());

            UserListResponse response = controllerOver(PAGE_SIZE).lastSentScreen(ws);

            assertThat(response).isNotNull();
            assertThat(response.errMsg()).hasSize(UserListResponse.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the selection cells are echoed back, because the program never blanks them")
        void theSelectionCellsAreEchoed() {
            UserListRequest ticked = reentering()
                    .withRow(2, UserListRequest.UserListRow.blank().withSel("Q"));

            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(ticked, CicsAid.DFHPF12);

            assertThat(response.row(2).selection())
                    .as("neither POPULATE-USER-DATA nor INITIALIZE-USER-DATA touches SEL000n")
                    .isEqualTo("Q");
        }

        @Test
        @DisplayName("nextMapset and nextMap stay blank, because COUSR00C names neither")
        void theNavigationTripleIsOnlyAProgram() {
            UserListResponse response = controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHPF3);

            assertThat(response.nextProgram().strip()).isEqualTo("COADM01C");
            assertThat(response.nextMapset()).isBlank();
            assertThat(response.nextMap()).isBlank();
        }
    }

    @Nested
    @DisplayName("POPULATE-USER-DATA and INITIALIZE-USER-DATA - :384-501")
    class RowWriters {
        @ParameterizedTest(name = "WS-IDX = {0} writes row {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("WS-IDX is one-based: WHEN 1 writes USRID01 and WHEN 10 writes USRID10")
        void theSubscriptIsOneBased(int rowNumber) {
            WorkArea ws = new WorkArea();
            ws.idx(rowNumber);

            controllerOver(1).populateUserData(ws, record(rowNumber - 1));

            assertThat(ws.map().usrId(rowNumber).strip()).isEqualTo(USER_IDS.get(rowNumber - 1));
            for (int other = 1; other <= PAGE_SIZE; other++) {
                if (other != rowNumber) {
                    assertThat(ws.map().usrId(other))
                            .as("row %d must be untouched, and an untouched row of COUSR0AO holds the "
                                    + "storage image :117 moves there, not spaces", other)
                            .isEqualTo(ScreenFieldImage.unpainted(UserListResponse.USRID_LENGTH));
                }
            }
        }

        @Test
        @DisplayName(":388-389 row 1 also sets CDEMO-CU00-USRID-FIRST - the FIRST element")
        void rowOneSetsTheBackwardAnchor() {
            WorkArea ws = new WorkArea();
            ws.idx(1);

            controllerOver(1).populateUserData(ws, record(0));

            assertThat(ws.cu00UsrIdFirst().strip()).isEqualTo("ADMIN001");
            assertThat(ws.cu00UsrIdLast()).as("row 1 must not touch the forward anchor").isBlank();
        }

        @Test
        @DisplayName(":434-435 row 10 also sets CDEMO-CU00-USRID-LAST - the LAST element")
        void rowTenSetsTheForwardAnchor() {
            WorkArea ws = new WorkArea();
            ws.idx(PAGE_SIZE);

            controllerOver(1).populateUserData(ws, record(9));

            assertThat(ws.cu00UsrIdLast().strip()).isEqualTo("USER0005");
            assertThat(ws.cu00UsrIdFirst()).as("row 10 must not touch the backward anchor").isBlank();
        }

        @ParameterizedTest(name = "WS-IDX = {0} reaches WHEN OTHER")
        @ValueSource(ints = {-1, 0, 11, 12})
        @DisplayName(":439-440 WHEN OTHER is a real arm and leaves the screen untouched")
        void aSubscriptOutsideTheRangeIsANoOp(int subscript) {
            WorkArea ws = new WorkArea();
            ws.idx(subscript);

            controllerOver(1).populateUserData(ws, record(0));
            controllerOver(1).initializeUserData(ws);

            assertThat(ws.cu00UsrIdFirst()).isBlank();
            assertThat(ws.cu00UsrIdLast()).isBlank();
            for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
                assertThat(ws.map().usrId(rowNumber))
                        .as("WHEN OTHER -> CONTINUE, so row %d was never written and holds the "
                                + "unpainted image", rowNumber)
                        .isEqualTo(ScreenFieldImage.unpainted(UserListResponse.USRID_LENGTH));
            }
        }

        @Test
        @DisplayName(":446-501 blanking a row leaves the selection cell and both anchors alone")
        void blankingARowTouchesOnlyItsFourDataCells() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00UsrIdFirst("ADMIN001")
                    .withCdemoCu00UsrIdLast("USER0005"));
            ws.map().sel(3, "U");
            ws.idx(3);
            controllerOver(1).populateUserData(ws, record(2));

            controllerOver(1).initializeUserData(ws);

            assertThat(ws.map().usrId(3)).isBlank();
            assertThat(ws.map().fname(3)).isBlank();
            assertThat(ws.map().lname(3)).isBlank();
            assertThat(ws.map().utype(3)).isBlank();
            assertThat(ws.map().sel(3)).as("SEL000n is the operator's own input").isEqualTo("U");
            assertThat(ws.cu00UsrIdFirst().strip()).isEqualTo("ADMIN001");
            assertThat(ws.cu00UsrIdLast().strip()).isEqualTo("USER0005");
        }

        @Test
        @DisplayName("a null record is refused rather than written as a blank row")
        void aNullRecordIsRefused() {
            WorkArea ws = new WorkArea();
            ws.idx(1);
            UserMenuController controller = controllerOver(1);

            assertThatNullPointerException().isThrownBy(() -> controller.populateUserData(ws, null));
        }

        @ParameterizedTest(name = "row {0} is outside the OCCURS range")
        @ValueSource(ints = {0, 11})
        @DisplayName("the map refuses a subscript outside 1 to 10 rather than wrapping it")
        void theMapRefusesABadSubscript(int rowNumber) {
            SymbolicMap map = new WorkArea().map();

            assertThatIllegalArgumentException().isThrownBy(() -> map.usrId(rowNumber));
            assertThatIllegalArgumentException().isThrownBy(() -> map.sel(rowNumber));
            assertThatIllegalArgumentException().isThrownBy(() -> map.fname(rowNumber));
            assertThatIllegalArgumentException().isThrownBy(() -> map.lname(rowNumber));
            assertThatIllegalArgumentException().isThrownBy(() -> map.utype(rowNumber));
        }
    }

    @Nested
    @DisplayName("Construction, the HTTP seam and the structural gates")
    class Structure {
        @Test
        @DisplayName("the wiring constructor accepts a charset and builds an equivalent controller")
        void theWiringConstructorWorks() {
            SecUserRepository repository = new SecUserRepository(seeded(fixtureImages(PAGE_SIZE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER);

            UserMenuController wired = new UserMenuController(repository, StandardCharsets.US_ASCII,
                    CLOCK);

            assertThat(rowUserId(wired.listUsers(entering(), CicsAid.DFHENTER), 1))
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("every collaborator is required, so none can be silently absent")
        void everyCollaboratorIsRequired() {
            SecUserRepository repository = new SecUserRepository(seeded(fixtureImages(1)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER);

            assertThatNullPointerException()
                    .isThrownBy(() -> new UserMenuController(null, CODEC, CLOCK));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserMenuController(repository, (FixedWidthCodec) null, CLOCK));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserMenuController(repository, CODEC, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserMenuController(repository, (Charset) null, CLOCK));
        }

        @Test
        @DisplayName("a work area is required, so the INITIALIZEd state cannot be omitted")
        void aWorkAreaIsRequired() {
            UserMenuController controller = controllerOver(1);

            assertThatNullPointerException()
                    .isThrownBy(() -> controller.listUsers(entering(), CicsAid.DFHENTER, null));
        }

        @Test
        @DisplayName("the metadata reports the cursor request and the erase, which had no other route")
        void theMetadataReportsWhatTheSendCarried() {
            ScreenMetadata painted = controllerOver(PAGE_SIZE).getUsers(entering(), null, null)
                    .screenMetadata();

            assertThat(painted.cursorField()).isEqualTo(UserListResponse.USRIDIN_FIELD);
            assertThat(painted.resetAllOutputFields())
                    .as("SEND ... ERASE at :288 clears the screen before painting")
                    .isTrue();
            assertThat(painted.messageColour()).isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHDFCOL));
            assertThat(painted.fields()).as("COUSR00 declares no per-field attribute quads").isEmpty();

            assertThat(controllerOver(PAGE_SIZE)
                    .getUsers(UserListRequest.empty().withoutNavigationContext(), null, null)
                    .screenMetadata().cursorField())
                    .isEqualTo(UserListResponse.USRIDIN_FIELD);

            ScreenMetadata untouched = UserMenuController.screenMetadataOf(new WorkArea());
            assertThat(untouched.cursorField()).isNull();
            assertThat(untouched.resetAllOutputFields())
                    .as("SEND-ERASE-YES is the declared initial state of the 88-level")
                    .isTrue();

            ScreenMetadata atTheBottom = controllerOver(PAGE_SIZE)
                    .getUsers(reentering(), Byte.toUnsignedInt(CicsAid.DFHPF8), null)
                    .screenMetadata();
            assertThat(atTheBottom.resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("the metadata refuses to be read from no work area at all")
        void theMetadataNeedsAWorkArea() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserMenuController.screenMetadataOf(null))
                    .withMessageContaining("work area");
        }

        @Test
        @DisplayName("both contract guards pass against the copybooks they defend")
        void theContractGuardsPass() {
            UserMenuController.verifyScreenContract();
            UserMenuController.verifyNavigationContract();
        }

        @Test
        @DisplayName("GET /api/users is the mapped route and the AID arrives as a query parameter")
        void theRouteIsTheOneTheCsdTransactionProjectsTo() throws NoSuchMethodException {
            Method mapped = UserMenuController.class.getMethod("getUsers", UserListRequest.class,
                    Integer.class, Integer.class);

            assertThat(UserMenuController.USER_LIST_PATH).isEqualTo("/api/users");
            assertThat(mapped.getReturnType()).isEqualTo(ScreenResponse.class);
            assertThat(((ParameterizedType) mapped.getGenericReturnType()).getActualTypeArguments())
                    .containsExactly(UserListResponse.class);
            for (Class<?> parameter : mapped.getParameterTypes()) {
                assertThat(parameter.getName())
                        .as("no servlet type may appear in the signature")
                        .doesNotContain("javax.servlet")
                        .doesNotContain("jakarta.servlet");
            }
        }

        @Test
        @DisplayName("the parameter wins when supplied, and an out-of-range one is refused")
        void theAidParameterIsNarrowedSafely() {
            assertThat(UserMenuController.resolveEibAid(0xF7, null)).isEqualTo(CicsAid.DFHPF7);
            assertThat(UserMenuController.resolveEibAid(0xF8, null)).isEqualTo(CicsAid.DFHPF8);
            assertThat(UserMenuController.resolveEibAid(0, null)).isZero();
            assertThat(UserMenuController.resolveEibAid(255, null)).isEqualTo((byte) 0xFF);
            assertThat(UserMenuController.resolveEibAid(0xF7,
                    entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF8))))
                    .isEqualTo(CicsAid.DFHPF7);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserMenuController.resolveEibAid(-1, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserMenuController.resolveEibAid(256, null));
        }

        @Test
        @DisplayName("either accepted spelling of the AID parameter reaches the key, and a contradiction "
                + "between them is refused")
        void eitherAidSpellingReachesTheKey() {
            int pf7 = 0xF7;

            ScreenResponse<UserListResponse> throughCanonical =
                    controllerOver(PAGE_SIZE).getUsers(reentering(), pf7, null);
            ScreenResponse<UserListResponse> throughAlternate =
                    controllerOver(PAGE_SIZE).getUsers(reentering(), null, pf7);
            ScreenResponse<UserListResponse> throughBoth =
                    controllerOver(PAGE_SIZE).getUsers(reentering(), pf7, pf7);
            ScreenResponse<UserListResponse> noKeyNamed =
                    controllerOver(PAGE_SIZE).getUsers(reentering(), null, null);

            assertThat(throughAlternate.screen()).isEqualTo(throughCanonical.screen());
            assertThat(throughBoth.screen()).isEqualTo(throughCanonical.screen());
            assertThat(noKeyNamed.screen()).isNotEqualTo(throughCanonical.screen());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controllerOver(PAGE_SIZE).getUsers(reentering(), pf7, 0xF8));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controllerOver(PAGE_SIZE).getUsers(reentering(), null, 300));
        }

        @Test
        @DisplayName("with no parameter, the payload's one-character EIBAID image names the key")
        void theTokenIsTheSecondCarrier() {
            assertThat(UserMenuController.resolveEibAid(null,
                    entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF7))))
                    .isEqualTo(CicsAid.DFHPF7);
            assertThat(UserMenuController.resolveEibAid(null,
                    entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF8))))
                    .isEqualTo(CicsAid.DFHPF8);
            assertThat(UserMenuController.resolveEibAid(null,
                    entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHCLEAR))))
                    .isEqualTo(CicsAid.DFHCLEAR);
            assertThat(UserMenuController.resolveEibAid(null,
                    entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF20))))
                    .isEqualTo(CicsAid.DFHPF20);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF20)).contains(AidKey.PFK08);
        }

        @Test
        @DisplayName("ENTER is the default only when neither carrier names a key")
        void enterIsTheLastResort() {
            assertThat(UserMenuController.resolveEibAid(null, null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(UserMenuController.resolveEibAid(null, entering()))
                    .as("UserListRequest.empty() carries no token")
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(UserMenuController.resolveEibAid(null, entering().withAid("     ")))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(UserMenuController.resolveEibAid(null, entering().withAid("\u0000".repeat(5))))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(UserMenuController.resolveEibAid(null, entering().withAid("PFK99")))
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(UserMenuController.resolveEibAid(null, entering().withAid("PF8  ")))
                    .as("a plausible misspelling of this screen's own paging key is not ENTER")
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(UserMenuController.resolveEibAid(null, entering().withAid("PFK08")))
                    .as("nor is the folded token itself a byte: it names no key this program can identify")
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @ParameterizedTest(name = "a byte producing {0} is read back as itself")
        @EnumSource(AidKey.class)
        @DisplayName("every key a response can name is readable back as its own byte, unfolded")
        void everyTokenRoundTripsThroughTheResolver(AidKey key) {
            byte source = someByteResolvingTo(key);

            int mapped = UserMenuController.aidByteOfToken(PfKeyResolver.aidImage(source))
                    .orElseThrow();

            assertThat((byte) mapped).isEqualTo(source);
            assertThat(PfKeyResolver.resolve((byte) mapped)).contains(key);
        }

        private byte someByteResolvingTo(AidKey key) {
            for (int unsigned = 0; unsigned <= 0xFF; unsigned++) {
                if (PfKeyResolver.resolve((byte) unsigned).filter(key::equals).isPresent()) {
                    return (byte) unsigned;
                }
            }
            throw new AssertionError("PfKeyResolver maps no byte at all onto " + key);
        }

        @Test
        @DisplayName("stating no key and naming a key this program does not handle are different "
                + "outcomes: the first is empty, the second is DFHNULL")
        void theInputsThatNameNoKeyAreEmpty() {
            assertThat(UserMenuController.aidByteOfToken(null)).isEmpty();
            assertThat(UserMenuController.aidByteOfToken("     ")).isEmpty();
            assertThat(UserMenuController.aidByteOfToken("\u0000".repeat(5))).isEmpty();

            assertThat(UserMenuController.aidByteOfToken("PFK99")).hasValue(CicsAid.DFHNULL & 0xFF);
            assertThat(UserMenuController.aidByteOfToken("ZZZZZ")).hasValue(CicsAid.DFHNULL & 0xFF);

            assertThat(UserMenuController.aidByteOfToken(PfKeyResolver.aidImage(CicsAid.DFHPF7)))
                    .hasValue(CicsAid.DFHPF7 & 0xFF);
        }

        @Test
        @DisplayName("the PF7 and PF8 paging arms are reachable from the token alone, end to end")
        void thePagingArmsAreReachableFromTheTokenAlone() {
            UserMenuController controller = controllerOver(PAGE_SIZE);

            UserListResponse forward = controller
                    .getUsers(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF8)),
                            null, null).screen();
            UserListResponse backward = controller
                    .getUsers(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF7)),
                            null, null).screen();

            assertThat(forward.errMsg().strip())
                    .as("PF8 from page one either pages or says it cannot; either way it is not ENTER")
                    .isNotEqualTo(UserMenuController.MSG_INVALID_KEY);
            assertThat(backward.errMsg().strip())
                    .isEqualTo(UserMenuController.MSG_ALREADY_AT_TOP);
        }

        @Test
        @DisplayName("the request mapping delegates without deciding anything itself")
        void theRequestMappingDelegates() {
            ScreenResponse<UserListResponse> answer =
                    controllerOver(PAGE_SIZE).getUsers(entering(), null, null);

            UserListResponse throughHttp = answer.screen();
            assertThat(rowUserId(throughHttp, 1)).isEqualTo("ADMIN001");
            assertThat(throughHttp.pgmName()).isEqualTo("COUSR00C");
            assertThat(answer.screenMetadata()).as("the envelope carries the presentation state")
                    .isNotNull();
        }

        @Test
        @DisplayName("the class holds three final collaborators and no mutable state at all")
        void theClassHoldsNoMutableState() {
            for (Field field : UserMenuController.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(field.getType())
                        .as("instance field %s", field.getName())
                        .isIn(SecUserRepository.class, FixedWidthCodec.class, Clock.class);
            }
            long instanceFields = List.of(UserMenuController.class.getDeclaredFields()).stream()
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .count();
            assertThat(instanceFields).isEqualTo(3);
        }

        @Test
        @DisplayName("exactly one method beyond the constructors is public, so every decision is drivable")
        void onlyTheRequestMappingIsPublic() {
            List<String> publicMethods = new ArrayList<>();
            for (Method method : UserMenuController.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    publicMethods.add(method.getName());
                }
            }
            assertThat(publicMethods).containsExactly("getUsers");
        }

        @Test
        @DisplayName("the page size is a private static final constant, not anything tunable")
        void thePageSizeIsNotConfigurable() throws NoSuchFieldException {
            Field pageSize = UserMenuController.class.getDeclaredField("PAGE_SIZE");

            assertThat(Modifier.isPrivate(pageSize.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(pageSize.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(pageSize.getModifiers())).isTrue();
            assertThat(pageSize.getType()).isEqualTo(int.class);
            assertThat(pageSize.getAnnotations())
                    .as("no @Value, so it cannot be overridden from configuration")
                    .isEmpty();
        }

        @Test
        @DisplayName("the dead working storage of :52-64 was not modelled")
        void theDeadWorkingStorageWasNotModelled() {
            List<String> names = new ArrayList<>();
            for (Field field : WorkArea.class.getDeclaredFields()) {
                names.add(field.getName().toLowerCase());
            }
            for (Field field : SymbolicMap.class.getDeclaredFields()) {
                names.add(field.getName().toLowerCase());
            }

            assertThat(names)
                    .as("WS-REC-COUNT, WS-PAGE-NUM and WS-USER-DATA are never referenced in the "
                            + "PROCEDURE DIVISION, so no field stands for them")
                    .doesNotContain("reccount", "wsreccount", "wspagenum", "userdata", "wsuserdata",
                            "userrec", "username");
        }

        @Test
        @DisplayName("the CU00 extension is 34 bytes on top of 160, and NavigationContext is still 160")
        void theCommunicationAreaGeometryHolds() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("widening the shared area would change every other screen's byte image")
                    .isEqualTo(160);
            assertThat(UserListResponse.CU00_INFO_LENGTH).isEqualTo(34);
            assertThat(UserListResponse.CU00_COMMAREA_LENGTH).isEqualTo(194);
        }

        @Test
        @DisplayName("isBlank and isNotBlank are exact negations, over spaces and low values alike")
        void theFigurativeConstantTestsAreExactNegations() {
            for (String value : List.of("", "        ", " ", "\u0000", "\u0000\u0000\u0000\u0000\u0000"
                    + "\u0000\u0000\u0000", "A", "ADMIN001", " A", "A\u0000")) {
                assertThat(UserMenuController.isNotBlank(value))
                        .as("value %s", value.replace('\u0000', '.'))
                        .isEqualTo(!UserMenuController.isBlank(value));
            }
            assertThat(UserMenuController.isBlank(null)).isTrue();
            assertThat(UserMenuController.isBlank("        ")).isTrue();
            assertThat(UserMenuController.isBlank("\u0000\u0000")).isTrue();
            assertThat(UserMenuController.isBlank("A\u0000"))
                    .as("neither entirely spaces nor entirely low values, so not blank")
                    .isFalse();
            assertThat(UserMenuController.isBlank("ADMIN001")).isFalse();
        }

        @Test
        @DisplayName("a send carries a screen, so SentScreen refuses to be built without one")
        void aSendCarriesAScreen() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SentScreen(null, true, UserMenuController.CURSOR_ON_USRIDIN));
            SentScreen sent = new SentScreen(UserListResponse.blank(), false,
                    UserMenuController.NO_CURSOR);
            assertThat(sent.erase()).isFalse();
            assertThat(sent.cursorPosition()).isEqualTo(UserMenuController.NO_CURSOR);
            assertThat(sent.screen()).isNotNull();
        }

        @Test
        @DisplayName("the file name is the eight-character CICS name, never a dataset name")
        void theFileNameIsTheCicsName() {
            assertThat(UserMenuController.WS_USRSEC_FILE)
                    .isEqualTo("USRSEC  ")
                    .hasSize(8)
                    .doesNotContain("AWS.M2.CARDDEMO");
        }

        @Test
        @DisplayName("each contract guard really does fail when the property it defends is violated")
        void eachGuardFailsWhenItShould() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> UserMenuController.requireAgreement(10, 7, "a page size"))
                    .withMessageContaining("must agree")
                    .withMessageContaining("10")
                    .withMessageContaining("7");

            assertThatIllegalStateException()
                    .isThrownBy(() -> UserMenuController.requireNarrowing(80, 78, "a message field"))
                    .withMessageContaining("is not below");

            assertThatIllegalStateException()
                    .isThrownBy(() -> UserMenuController.requireFits("X".repeat(79),
                            UserListResponse.ERRMSG_LENGTH))
                    .withMessageContaining("cannot be displayed");

            UserMenuController.requireAgreement(10, 10, "a page size");
            UserMenuController.requireNarrowing(78, 80, "a message field");
            UserMenuController.requireFits("X".repeat(UserListResponse.ERRMSG_LENGTH),
                    UserListResponse.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("a receive with no payload leaves the map holding what it held")
        void aReceiveWithNoPayloadIsANoOp() {
            WorkArea ws = new WorkArea();
            ws.map().usrId(5, CODEC.movePicX("KEPT0005", EIGHT));

            controllerOver(1).receiveUsrlstScreen(ws, null);

            assertThat(ws.map().usrId(5).strip()).isEqualTo("KEPT0005");
            assertThat(ws.respCd()).isEqualTo(FileStatus.NORMAL);
            assertThat(ws.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("every symbolic-map accessor reads the field it names")
        void everyMapAccessorReadsItsOwnField() {
            WorkArea ws = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(entering(), CicsAid.DFHENTER, ws);
            SymbolicMap map = ws.map();

            assertThat(map.trnName()).isEqualTo("CU00");
            assertThat(map.pgmName()).isEqualTo("COUSR00C");
            assertThat(map.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(map.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(map.curDate()).isEqualTo("07/19/22");
            assertThat(map.curTime()).isEqualTo("23:12:34");
            assertThat(map.pageNum()).isEqualTo("00000001");
            assertThat(map.usrIdIn()).isBlank();
            assertThat(map.errMsg().strip()).isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
            assertThat(map.sel(1))
                    .as("neither POPULATE-USER-DATA nor INITIALIZE-USER-DATA touches a selection cell, "
                            + "so it still holds what :117 moved into the area")
                    .isEqualTo(ScreenFieldImage.unpainted(UserListResponse.SEL_LENGTH));
            assertThat(map.usrId(1).strip()).isEqualTo("ADMIN001");
            assertThat(map.fname(1).strip()).isEqualTo("MARGARET");
            assertThat(map.lname(1).strip()).isEqualTo("GOLD");
            assertThat(map.utype(1)).isEqualTo("A");
        }
    }

    @Nested
    @DisplayName("The error-flag paths - WHEN OTHER, at each of its consequences")
    class ErrorFlagPaths {
        private static final String UNDECODABLE = "AAAA" + " ".repeat(36);

        private static UserMenuController controllerWithAnUndecodableTail(int leadingGoodRecords) {
            List<String> rows = new ArrayList<>(fixtureImages(leadingGoodRecords));
            rows.add("ZZZZ" + " ".repeat(36));
            return new UserMenuController(new SecUserRepository(seeded(rows), validBindings(), ASCII,
                    RecordImageForm.CHARACTER), CODEC, CLOCK);
        }

        private static UserMenuController controllerWithOnlyAnUndecodableRow() {
            return new UserMenuController(new SecUserRepository(seeded(List.of(UNDECODABLE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER), CODEC, CLOCK);
        }

        @Test
        @DisplayName("a read that fails outright still ends the browse, and adds no send")
        void anUnmodelledReadFailureStillEndsTheBrowse() {
            SecUserRepository repository = Mockito.spy(new SecUserRepository(seeded(fixtureImages(PAGE_SIZE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER));
            BrowseCursor cursor = Mockito.mock(BrowseCursor.class);
            Mockito.when(cursor.openOutcome()).thenReturn(FileStatus.Outcome.OK);
            Mockito.when(cursor.openCicsResp()).thenReturn(OptionalInt.of(FileStatus.NORMAL));
            Mockito.when(cursor.isOpen()).thenReturn(true);
            Mockito.when(cursor.readNext()).thenThrow(new IllegalStateException("the read was refused"));
            Mockito.doReturn(cursor).when(repository).startBrowse(Mockito.any());
            UserMenuController controller = new UserMenuController(repository, CODEC, CLOCK);
            WorkArea ws = new WorkArea();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> controller.processPageForward(ws, CicsAid.DFHPF8))
                    .withMessage("the read was refused");

            Mockito.verify(cursor).endBrowse();
            assertThat(ws.sends())
                    .as("the release is silent: no map is sent and no message set")
                    .isEmpty();
        }

        @Test
        @DisplayName("a backward read that fails outright also ends the browse")
        void anUnmodelledBackwardReadFailureStillEndsTheBrowse() {
            SecUserRepository repository = Mockito.spy(new SecUserRepository(seeded(fixtureImages(PAGE_SIZE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER));
            BrowseCursor cursor = Mockito.mock(BrowseCursor.class);
            Mockito.when(cursor.openOutcome()).thenReturn(FileStatus.Outcome.OK);
            Mockito.when(cursor.openCicsResp()).thenReturn(OptionalInt.of(FileStatus.NORMAL));
            Mockito.when(cursor.isOpen()).thenReturn(true);
            Mockito.when(cursor.readPrevious()).thenThrow(new IllegalStateException("refused"));
            Mockito.doReturn(cursor).when(repository).startBrowse(Mockito.any());
            UserMenuController controller = new UserMenuController(repository, CODEC, CLOCK);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> controller.processPageBackward(new WorkArea(), CicsAid.DFHPF7));

            Mockito.verify(cursor).endBrowse();
        }

        @Test
        @DisplayName("a request that reached its own ENDBR is not ended a second time")
        void aCompletedRequestIsNotEndedTwice() {
            SecUserRepository repository = Mockito.spy(new SecUserRepository(seeded(fixtureImages(PAGE_SIZE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER));
            UserMenuController controller = new UserMenuController(repository, CODEC, CLOCK);
            WorkArea ws = new WorkArea();

            controller.processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName(":286 a STARTBR that raised the error flag skips the whole forward paragraph")
        void aFailedStartBrowseSkipsTheForwardPage() {
            WorkArea ws = new WorkArea();

            controllerOverMissingRelation().processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.sends())
                    .as("only the STARTBR arm's own send; :329 was never reached")
                    .hasSize(1);
            assertThat(ws.map().pageNum())
                    .as(":327 was not reached either, so PAGENUMO is untouched and holds the unpainted "
                            + "image rather than spaces nothing wrote")
                    .isEqualTo(ScreenFieldImage.unpainted(UserListResponse.PAGENUM_LENGTH));
        }

        @Test
        @DisplayName(":340 a STARTBR that raised the error flag skips the whole backward paragraph")
        void aFailedStartBrowseSkipsTheBackwardPage() {
            WorkArea ws = new WorkArea();

            controllerOverMissingRelation().processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.sends()).hasSize(1);
        }

        @Test
        @DisplayName(":292 the error operand of the blanking guard - a failed pre-loop read")
        void aFailedPreLoopReadAlsoSkipsTheBlanking() {
            WorkArea ws = new WorkArea();
            ws.map().usrId(6, CODEC.movePicX("STALE006", EIGHT));

            controllerWithOnlyAnUndecodableRow().processPageForward(ws, CicsAid.DFHPF8);

            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.map().usrId(6).strip()).isEqualTo("STALE006");
        }

        @Test
        @DisplayName(":300-305 the error operand of the fill loop - a failed read stops it and fills nothing")
        void aFailedFillReadStopsTheLoop() {
            WorkArea ws = new WorkArea();

            controllerWithOnlyAnUndecodableRow().processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.idx())
                    .as("the subscript only advances on a record, and no record was decoded")
                    .isEqualTo(1);
            assertThat(ws.map().usrId(1)).isBlank();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
        }

        @Test
        @DisplayName(":308 the error operand of the page-count guard, and :319's WS-IDX > 1 with it")
        void aFailedReadPartWayThroughStillCountsThePage() {
            WorkArea ws = new WorkArea();

            controllerWithAnUndecodableTail(3).processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.idx()).isEqualTo(4);
            assertThat(ws.map().usrId(3).strip()).isEqualTo("ADMIN003");
            assertThat(ws.nextPageYes()).isFalse();
            assertThat(ws.cu00PageNum())
                    .as(":320 counted the page, because WS-IDX had passed 1")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(":312 the error operand of the look-ahead test - a full page whose read-ahead fails")
        void aFailedLookAheadStillReportsNoFurtherPage() {
            WorkArea ws = new WorkArea();

            controllerWithAnUndecodableTail(PAGE_SIZE).processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.idx()).isEqualTo(PAGE_SIZE + 1);
            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.nextPageYes())
                    .as(":312-316 - a look-ahead that failed is not a further page")
                    .isFalse();
            assertThat(ws.cu00PageNum())
                    .as(":309 had already counted the page before the look-ahead ran")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(":346-360 the error operand of both backward guards")
        void aFailedBackwardReadStopsTheDescendingFill() {
            WorkArea ws = new WorkArea();
            ws.map().usrId(2, CODEC.movePicX("STALE002", EIGHT));
            ws.secUsrId(SecUserRepository.LOW_VALUES_KEY);

            controllerWithOnlyAnUndecodableRow().processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.map().usrId(2).strip())
                    .as("the blanking guard failed on its error operand, so the row survived")
                    .isEqualTo("STALE002");
            assertThat(ws.idx())
                    .as("MOVE 10 TO WS-IDX ran, and the fill never decremented it")
                    .isEqualTo(PAGE_SIZE);
        }

        @Test
        @DisplayName(":362-367 the error operand of the look-behind guards")
        void aFailedLookBehindLeavesTheCountAtOne() {
            List<String> rows = new ArrayList<>();
            rows.add("AAAA" + " ".repeat(36));
            rows.addAll(fixtureImages(PAGE_SIZE + 1));
            UserMenuController controller = new UserMenuController(
                    new SecUserRepository(seeded(rows), validBindings(), ASCII,
                            RecordImageForm.CHARACTER),
                    CODEC, CLOCK);

            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withCdemoCu00PageNum(4).withNextPageYes());
            ws.secUsrId(CODEC.movePicX(userId(PAGE_SIZE), EIGHT));

            controller.processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.idx()).isZero();
            assertThat(ws.errFlgOn())
                    .as("the look-behind at :363 met the undecodable lowest row")
                    .isTrue();
            assertThat(ws.cu00PageNum())
                    .as(":369 - the error operand of :365 failed, so the count became 1")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(":230 the error flag suppresses the second MOVE SPACE TO USRIDINO")
        void anErrorFlagSuppressesTheFinalFieldBlanking() {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            ws.map().usrIdIn(CODEC.movePicX("TYPEDKEY", EIGHT));

            UserListResponse transfer = controllerOverMissingRelation().processEnterKey(ws,
                    CicsAid.DFHENTER);

            assertThat(transfer).isNull();
            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.map().usrIdIn().strip())
                    .as("with ERR-FLG-ON, :231 does not run and the operator's key stays on the screen")
                    .isEqualTo("TYPEDKEY");
        }

        @Test
        @DisplayName(":288 PF7 and PF3 both suppress the pre-loop read, like ENTER")
        void thePreLoopReadIsSuppressedForAllThreeNamedKeys() {
            for (byte aid : new byte[] {CicsAid.DFHENTER, CicsAid.DFHPF7, CicsAid.DFHPF3}) {
                WorkArea ws = new WorkArea();
                controllerOver(PAGE_SIZE).processPageForward(ws, aid);

                assertThat(ws.map().usrId(1).strip())
                        .as("AID 0x%02X keeps the record the anchor names", aid)
                        .isEqualTo("ADMIN001");
            }

            WorkArea viaPf8 = new WorkArea();
            controllerOver(PAGE_SIZE).processPageForward(viaPf8, CicsAid.DFHPF8);
            assertThat(viaPf8.map().usrId(1).strip())
                    .as("PF8 alone discards it")
                    .isEqualTo("ADMIN002");
        }

        @Test
        @DisplayName(":342 PF8 suppresses the backward pre-loop read, like ENTER")
        void theBackwardPreLoopReadIsSuppressedForPf8() {
            WorkArea ws = new WorkArea();
            ws.secUsrId(CODEC.movePicX("ADMIN003", EIGHT));

            controllerOver(PAGE_SIZE).processPageBackward(ws, CicsAid.DFHPF8);

            assertThat(ws.map().usrId(PAGE_SIZE).strip())
                    .as("PF8 is one of the two operands, so the anchor is kept")
                    .isEqualTo("ADMIN003");
        }

        @Test
        @DisplayName(":128-129 PF7 through the whole transaction, not only through its paragraph")
        void pf7ThroughTheWholeTransaction() {
            WorkArea ws = new WorkArea();
            UserListResponse response = controllerOver(PAGE_SIZE + 5).listUsers(
                    reentering().withCdemoCu00PageNum(2).withCdemoCu00UsrIdFirst(userId(PAGE_SIZE + 1)),
                    CicsAid.DFHPF7, ws);

            assertThat(ws.aidKey()).contains(AidKey.PFK07);
            assertThat(response.cdemoCu00PageNum()).isEqualTo(1);
            assertThat(rowUserId(response, PAGE_SIZE)).isEqualTo(userId(PAGE_SIZE));
        }

        @Test
        @DisplayName(":346 the end-of-file operand of the backward blanking guard - an empty file")
        void anEmptyFileEndsTheBackwardPageBeforeItBlanksAnything() {
            WorkArea ws = new WorkArea();
            ws.map().usrId(8, CODEC.movePicX("STALE008", EIGHT));

            controllerOver(0).processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.userSecEof()).isTrue();
            assertThat(ws.map().usrId(8).strip())
                    .as("the blanking guard failed on its END-OF-FILE operand this time")
                    .isEqualTo("STALE008");
            assertThat(ws.idx())
                    .as("MOVE 10 TO WS-IDX still ran; the loop simply never turned")
                    .isEqualTo(PAGE_SIZE);
        }

        @Test
        @DisplayName(":354-359 the error operand INSIDE the descending fill loop, one row down")
        void aFailedReadInsideTheBackwardFillStopsIt() {
            List<String> rows = new ArrayList<>();
            rows.add("AAAA" + " ".repeat(36));
            rows.addAll(fixtureImages(PAGE_SIZE));
            UserMenuController controller = new UserMenuController(
                    new SecUserRepository(seeded(rows), validBindings(), ASCII,
                            RecordImageForm.CHARACTER),
                    CODEC, CLOCK);

            WorkArea ws = new WorkArea();
            ws.secUsrId(CODEC.movePicX("ADMIN002", EIGHT));

            controller.processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.map().usrId(PAGE_SIZE).strip())
                    .as("the first fill read decoded ADMIN001 into row 10")
                    .isEqualTo("ADMIN001");
            assertThat(ws.idx())
                    .as("and the second stopped the loop, leaving the subscript at nine")
                    .isEqualTo(PAGE_SIZE - 1);
            assertThat(ws.errFlgOn()).isTrue();
            assertThat(ws.userSecEof()).isFalse();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
        }

        @Test
        @DisplayName(":124 a selection transfer taken through the whole transaction, on the REENTER path")
        void aSelectionTransferThroughTheWholeTransaction() {
            UserListRequest ticked = reentering()
                    .withRow(2, UserListRequest.UserListRow.blank().withSel("D").withUsrId("ADMIN002"));

            WorkArea ws = new WorkArea();
            UserListResponse response =
                    controllerOver(PAGE_SIZE).listUsers(ticked, CicsAid.DFHENTER, ws);

            assertThat(response.nextProgram().strip()).isEqualTo("COUSR03C");
            assertThat(response.cdemoCu00UsrSelected().strip()).isEqualTo("ADMIN002");
            assertThat(ws.transferred()).isTrue();
            assertThat(ws.sends()).isEmpty();
        }
    }

    private static BrowseCursor stubCursor(int recordCount) {
        BrowseCursor cursor = Mockito.mock(BrowseCursor.class);
        Mockito.when(cursor.openOutcome()).thenReturn(FileStatus.Outcome.OK);
        Mockito.when(cursor.openCicsResp()).thenReturn(OptionalInt.of(FileStatus.NORMAL));
        AtomicInteger forward = new AtomicInteger();
        Mockito.when(cursor.readNext()).thenAnswer(invocation -> {
            int position = forward.getAndIncrement();
            return position < recordCount ? ReadResult.found(record(position)) : ReadResult.endOfFile();
        });
        AtomicInteger backward = new AtomicInteger(recordCount);
        Mockito.when(cursor.readPrevious()).thenAnswer(invocation -> {
            int position = backward.decrementAndGet();
            return position >= 0 ? ReadResult.found(record(position)) : ReadResult.endOfFile();
        });
        AtomicInteger ended = new AtomicInteger();
        Mockito.when(cursor.isOpen()).thenAnswer(invocation -> ended.get() == 0);
        Mockito.doAnswer(invocation -> {
            ended.incrementAndGet();
            return null;
        }).when(cursor).endBrowse();
        return cursor;
    }

    private static SecUserRepository stubRepository(BrowseCursor cursor) {
        SecUserRepository repository = Mockito.mock(SecUserRepository.class);
        Mockito.when(repository.startBrowse(Mockito.any())).thenReturn(cursor);
        Mockito.when(repository.cicsFileName()).thenReturn(SecUserRepository.CICS_FILE_NAME);
        Mockito.when(repository.recordLength()).thenReturn(EIGHTY);
        Mockito.when(repository.keyLength()).thenReturn(EIGHT);
        Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
        return repository;
    }

    private static SecUserRepository stubRepositoryWithFreshCursors(int recordCount, int browses) {
        BrowseCursor first = stubCursor(recordCount);
        BrowseCursor[] rest = new BrowseCursor[Math.max(0, browses - 1)];
        for (int index = 0; index < rest.length; index++) {
            rest[index] = stubCursor(recordCount);
        }
        SecUserRepository repository = Mockito.mock(SecUserRepository.class);
        Mockito.when(repository.startBrowse(Mockito.any())).thenReturn(first, rest);
        Mockito.when(repository.cicsFileName()).thenReturn(SecUserRepository.CICS_FILE_NAME);
        Mockito.when(repository.recordLength()).thenReturn(EIGHTY);
        Mockito.when(repository.keyLength()).thenReturn(EIGHT);
        Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
        return repository;
    }

    private static UserMenuController controllerOverStub(SecUserRepository repository) {
        return new UserMenuController(repository, CODEC, CLOCK);
    }

    @Nested
    @DisplayName("GET /api/users - the HTTP projection of CSD transaction CU00")
    class HttpProjection {
        private MockMvc mockMvc;

        private final ObjectMapper mapper = new ObjectMapper();

        private SecUserRepository repository;

        @BeforeEach
        void standalone() {
            repository = stubRepository(stubCursor(PAGE_SIZE));
            mockMvc = MockMvcBuilders.standaloneSetup(controllerOverStub(repository)).build();
        }

        private String json(UserListRequest request) throws Exception {
            return mapper.writeValueAsString(request);
        }

        @Test
        @DisplayName("the route answers the listed page, and the whole slice really is wired")
        void theRouteAnswersTheListedPage() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usrid01").value("ADMIN001"))
                    .andExpect(jsonPath("$.usrid10").value("USER0005"))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("the body is the COUSR0AO projection, not an error document")
                    .contains("\"trnname\":\"CU00\"");
            Mockito.verify(repository).startBrowse(Mockito.any());
        }

        @ParameterizedTest(name = "{0} /api/users is refused with 405")
        @ValueSource(strings = {"POST", "PUT"})
        @DisplayName("GET is the only verb the route answers, so any other earns 405")
        void onlyGetReachesTheRoute(String verb) throws Exception {
            var request = "POST".equals(verb) ? post("/api/users") : put("/api/users");

            mockMvc.perform(request
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isMethodNotAllowed());

            Mockito.verify(repository, Mockito.never()).startBrowse(Mockito.any());
        }

        @Test
        @DisplayName("the path is exactly /api/users - no trailing segment answers in its place")
        void thePathIsExact() throws Exception {
            assertThat(UserMenuController.USER_LIST_PATH).isEqualTo("/api/users");

            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/user"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("gate G9 - the body carries exactly the 59 map fields, the 6 CU00 members, the 3 "
                + "navigation members, the area and the metadata envelope; nothing else")
        void theBodyCarriesExactlyTheProjectedNames() throws Exception {
            String body = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> wireNames = new ArrayList<>();
            mapper.readTree(body).fieldNames().forEachRemaining(wireNames::add);

            List<String> mapMembers = wireNames.stream()
                    .filter(name -> !name.startsWith("cdemoCu00"))
                    .filter(name -> !name.startsWith("next"))
                    .filter(name -> !"navigationContext".equals(name))
                    .filter(name -> !"screenMetadata".equals(name))
                    .toList();
            assertThat(mapMembers)
                    .as("8 + 10*5 + 1 = 59")
                    .hasSize(8 + PAGE_SIZE * UserListResponse.ROW_FIELD_COUNT + 1)
                    .hasSize(UserListResponse.MAP_FIELD_COUNT)
                    .hasSize(59);

            assertThat(wireNames).containsExactlyInAnyOrder(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pagenum",
                    "usridin",
                    "sel0001", "usrid01", "fname01", "lname01", "utype01",
                    "sel0002", "usrid02", "fname02", "lname02", "utype02",
                    "sel0003", "usrid03", "fname03", "lname03", "utype03",
                    "sel0004", "usrid04", "fname04", "lname04", "utype04",
                    "sel0005", "usrid05", "fname05", "lname05", "utype05",
                    "sel0006", "usrid06", "fname06", "lname06", "utype06",
                    "sel0007", "usrid07", "fname07", "lname07", "utype07",
                    "sel0008", "usrid08", "fname08", "lname08", "utype08",
                    "sel0009", "usrid09", "fname09", "lname09", "utype09",
                    "sel0010", "usrid10", "fname10", "lname10", "utype10",
                    "errmsg",
                    "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                    "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
                    "nextProgram", "nextMapset", "nextMap",
                    "navigationContext",
                    "screenMetadata");

            assertThat(wireNames).hasSize(UserListResponse.COMPONENT_COUNT + 1).hasSize(70);
        }

        @Test
        @DisplayName("gate G9 - no xxxL, xxxF or xxxA metadata item is ever a JSON member")
        void noSymbolicMapMetadataReachesTheWire() throws Exception {
            String body = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> wireNames = new ArrayList<>();
            mapper.readTree(body).fieldNames().forEachRemaining(wireNames::add);

            for (String metadataItem : List.of(
                    "trnNameL", "trnNameF", "trnNameA", "title01L", "curDateL", "pgmNameL", "title02L",
                    "curTimeL", "pageNumL", "usrIdInL", "usrIdInF", "usrIdInA", "sel0001L", "sel0001F",
                    "sel0001A", "usrId01L", "fname01L", "lname01L", "utype01L", "sel0010L", "usrId10L",
                    "utype10L", "errMsgL", "errMsgF", "errMsgA")) {
                assertThat(wireNames).as("%s is metadata, not payload", metadataItem)
                        .doesNotContain(metadataItem);
            }
            assertThat(wireNames.stream().filter(name -> name.endsWith("O") || name.endsWith("C")))
                    .as("no output-side or colour-side item is a member either")
                    .isEmpty();
            assertThat(body).doesNotContain("\"usrIdInL\"").doesNotContain("\"errMsgC\"");
        }

        @Test
        @DisplayName("CURTIME is eight characters here, unlike COSGN00's nine")
        void theTimeFieldIsEightCharactersWide() throws Exception {
            assertThat(UserListResponse.CURTIME_LENGTH).isEqualTo(8)
                    .isEqualTo(DateHeader.WS_CURTIME_LENGTH);
            assertThat(UserListResponse.CURDATE_LENGTH).isEqualTo(8)
                    .isEqualTo(DateHeader.WS_CURDATE_LENGTH);

            String body = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.curtime").value("23:12:34"))
                    .andExpect(jsonPath("$.curdate").value("07/19/22"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(mapper.readTree(body).get("curtime").asText())
                    .as("CURTIMEI PIC X(8)")
                    .hasSize(8);
            assertThat(mapper.readTree(body).get("curdate").asText())
                    .as("CURDATEI PIC X(8)")
                    .hasSize(8);
        }

        @Test
        @DisplayName(":568-569 the transaction and program names, and :566-567 the two titles, are verbatim")
        void theHeaderIdentifiesTheTransactionAndProgram() throws Exception {
            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(UserListResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.trnname").value("CU00"))
                    .andExpect(jsonPath("$.pgmname").value(UserListResponse.PROGRAM_NAME))
                    .andExpect(jsonPath("$.pgmname").value("COUSR00C"))
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02));

            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserListResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(UserListResponse.TITLE02_LENGTH);
        }

        @Test
        @DisplayName("gate G39 - a query parameter asking for a different page size is not bound at all")
        void aQueryParameterAskingForADifferentSizeIsIgnored() throws Exception {
            BrowseCursor longer = stubCursor(25);
            Mockito.when(repository.startBrowse(Mockito.any())).thenReturn(longer);

            mockMvc.perform(get("/api/users")
                            .param("pageSize", "3")
                            .param("size", "3")
                            .param("limit", "3")
                            .param("rows", "3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(reentering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usrid01").value("ADMIN001"))
                    .andExpect(jsonPath("$.usrid10").value("USER0005"))
                    .andExpect(jsonPath("$.utype10").value("U"));
        }
    }

    @Nested
    @DisplayName("The page size of ten - behaviour, never configuration (gate G39)")
    class PageSizeIsBehaviour {
        @Test
        @DisplayName("COUSR00C states ten four independent times, and all four agree")
        void tenIsStatedFourTimesAndAllFourAgree() {
            assertThat(UserListRequest.ROW_COUNT).isEqualTo(10);
            assertThat(UserListResponse.ROW_COUNT).isEqualTo(10);
            assertThat(UserListRequest.ROW_COUNT).isEqualTo(UserListResponse.ROW_COUNT);
            assertThat(UserListResponse.blank().rows()).hasSize(10);
            assertThat(UserListRequest.empty().rows()).hasSize(10);

            assertThat(UserListResponse.ROW_COUNT + 1).isEqualTo(11);
        }

        @Test
        @DisplayName("a file with more than a page in it still yields exactly ten rows")
        void aFullPageIsExactlyTenRows() {
            UserListResponse page = controllerOver(25).listUsers(reentering(), CicsAid.DFHENTER);

            assertThat(page.rows()).hasSize(10);
            for (int rowNumber = 1; rowNumber <= 10; rowNumber++) {
                assertThat(rowUserId(page, rowNumber))
                        .as("row %d carries the record at fixture position %d", rowNumber, rowNumber - 1)
                        .isEqualTo(userId(rowNumber - 1));
            }
            assertThat(page.nextPageYes()).as(":312-313 a further record exists").isTrue();
        }

        @Test
        @DisplayName("nothing on the API surface can change the page size")
        void nothingOnTheApiSurfaceCanChangeThePageSize() throws NoSuchMethodException {
            Method route = UserMenuController.class.getMethod("getUsers", UserListRequest.class,
                    Integer.class, Integer.class);
            assertThat(route.getParameterCount()).isEqualTo(3);

            for (var parameter : route.getParameters()) {
                var requestParam = parameter.getAnnotation(
                        RequestParam.class);
                if (requestParam != null) {
                    assertThat(requestParam.name().toLowerCase(Locale.ROOT))
                            .as("a bound query parameter that could resize the page")
                            .doesNotContain("size").doesNotContain("limit").doesNotContain("count")
                            .doesNotContain("page").doesNotContain("rows");
                }
            }

            for (Method method : UserMenuController.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name).as("%s looks like a page-size mutator", method.getName())
                        .doesNotStartWith("setpagesize").doesNotStartWith("setlimit")
                        .doesNotStartWith("setsize").doesNotStartWith("setrowcount")
                        .doesNotStartWith("withpagesize");
            }
            for (Field field : UserMenuController.class.getDeclaredFields()) {
                assertThat(field.getAnnotations())
                        .as("%s carries an annotation, so it could be bound from configuration",
                                field.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the request cannot smuggle a shorter row table past the DTO")
        void theRowTableCannotBeShortened() {
            List<UserListRequest.UserListRow> nine =
                    new ArrayList<>(UserListRequest.empty().rows().subList(0, 9));

            assertThatIllegalArgumentException().isThrownBy(() -> new UserListRequest(
                            null, null, null, null, null, null, null, null,
                            nine, null, null, null, 0, null, null, null, null, null))
                    .withMessageContaining("10");
        }
    }

    @Nested
    @DisplayName("The OCCURS 10 TIMES tables - :56-64 and the ten screen rows (gates G33, G21)")
    class OccursTables {
        private static final int DISPLAY_SEL_WIDTH = 1;

        private static final int DISPLAY_FILLER_WIDTH = 2;

        private static final int DISPLAY_FILLER_COUNT = 3;

        private static final int DISPLAY_ID_WIDTH = 8;

        private static final int DISPLAY_NAME_WIDTH = 25;

        private static final int DISPLAY_TYPE_WIDTH = 8;

        private static final int DISPLAY_ROW_WIDTH = 48;

        @Test
        @DisplayName(":56-64 one display row is 48 bytes and the table is 480 - FILLER included (gate G21)")
        void theDisplayRowIsFortyEightBytesAndTheTableIsFourHundredAndEighty() {
            int withoutFiller = DISPLAY_SEL_WIDTH + DISPLAY_ID_WIDTH + DISPLAY_NAME_WIDTH
                    + DISPLAY_TYPE_WIDTH;
            int filler = DISPLAY_FILLER_COUNT * DISPLAY_FILLER_WIDTH;

            assertThat(withoutFiller).as("the four named items alone").isEqualTo(42);
            assertThat(filler).as("three FILLER X(02) spans").isEqualTo(6);
            assertThat(withoutFiller + filler)
                    .as("1 + 2 + 8 + 2 + 25 + 2 + 8")
                    .isEqualTo(DISPLAY_ROW_WIDTH)
                    .isEqualTo(48);
            assertThat(DISPLAY_ROW_WIDTH * PAGE_SIZE)
                    .as("48 bytes per row over OCCURS 10 TIMES")
                    .isEqualTo(480);

            assertThat(withoutFiller).isNotEqualTo(DISPLAY_ROW_WIDTH);
            assertThat(withoutFiller * PAGE_SIZE).isNotEqualTo(480);
        }

        @Test
        @DisplayName(":59, :61, :63 the three FILLER spans are spaces, not nulls and not omitted")
        void theThreeFillerSpansAreSpaceFilled() {
            String filler = CODEC.movePicX("", DISPLAY_FILLER_WIDTH);
            String row = CODEC.movePicX("U", DISPLAY_SEL_WIDTH)
                    + filler
                    + CODEC.movePicX("ADMIN001", DISPLAY_ID_WIDTH)
                    + filler
                    + CODEC.movePicX("MARGARET GOLD", DISPLAY_NAME_WIDTH)
                    + filler
                    + CODEC.movePicX("A", DISPLAY_TYPE_WIDTH);

            assertThat(filler).as("a FILLER X(02) span is two spaces").isEqualTo("  ");

            assertThat(row).as("the assembled row is the declared width").hasSize(DISPLAY_ROW_WIDTH);
            assertThat(row.getBytes(ASCII)).as("and 48 bytes in the dataset code page")
                    .hasSize(DISPLAY_ROW_WIDTH);

            assertThat(row.substring(1, 3)).isEqualTo("  ");
            assertThat(row.substring(11, 13)).isEqualTo("  ");
            assertThat(row.substring(38, 40)).isEqualTo("  ");
            assertThat(row).as("no NUL byte reaches a PIC X span").doesNotContain("\u0000");

            assertThat(row.charAt(0)).isEqualTo('U');
            assertThat(row.substring(3, 11)).isEqualTo("ADMIN001");
            assertThat(row.substring(13, 38)).isEqualTo("MARGARET GOLD".concat(" ".repeat(12)));
            assertThat(row.substring(40)).isEqualTo("A       ");
        }

        @Test
        @DisplayName(":62 and :64 the display widths are WIDER than CSUSR01Y's, and are not conflated")
        void theDisplayWidthsDifferFromTheRecordWidths() {
            assertThat(DISPLAY_NAME_WIDTH).isEqualTo(25)
                    .isNotEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH)
                    .isNotEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH)
                    .isNotEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH
                            + SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(DISPLAY_TYPE_WIDTH).isEqualTo(8)
                    .isNotEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).as("SEC-USR-TYPE PIC X(01)").isEqualTo(1);

            assertThat(UserListResponse.FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(UserListResponse.LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(UserListResponse.UTYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserListResponse.UTYPE_LENGTH).isNotEqualTo(DISPLAY_TYPE_WIDTH);
        }

        @Test
        @DisplayName(":56-64 is dead working storage, so no Java type reproduces it")
        void theDisplayTableIsNotModelled() {
            List<String> names = new ArrayList<>();
            for (Class<?> owner : List.of(WorkArea.class, SymbolicMap.class, UserMenuController.class)) {
                for (Field field : owner.getDeclaredFields()) {
                    names.add(field.getName().toLowerCase(Locale.ROOT));
                }
            }

            assertThat(names).doesNotContain("userdata", "wsuserdata", "userrec", "userrecs",
                    "displayrow", "displayrows", "usersel", "username", "usertype");
            assertThat(UserMenuController.class.getDeclaredClasses())
                    .extracting(Class::getSimpleName)
                    .doesNotContain("UserRec", "UserRecord", "DisplayRow", "WsUserData");
        }

        @ParameterizedTest(name = "COBOL row {0} is Java index {1}")
        @CsvSource({"1,0", "2,1", "5,4", "9,8", "10,9"})
        @DisplayName("gate G33 - the one-based subscript maps onto the zero-based index, ends included")
        void theOneBasedSubscriptMapsOntoTheZeroBasedIndex(int cobolSubscript, int javaIndex) {
            UserListResponse page = controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHENTER);

            assertThat(page.row(cobolSubscript).userId().strip())
                    .as("COBOL USER-REC(%d) holds the record the Java list holds at %d",
                            cobolSubscript, javaIndex)
                    .isEqualTo(userId(javaIndex))
                    .isEqualTo(page.rows().get(javaIndex).userId().strip());
        }

        @Test
        @DisplayName("gate G33 - row 1 and row 10 are the first and last, and neither is shifted")
        void theFirstAndLastRowsAreTheEndsOfThePage() {
            UserListResponse page = controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHENTER);

            assertThat(page.row(1).userId().strip()).isEqualTo("ADMIN001");
            assertThat(page.row(PAGE_SIZE).userId().strip()).isEqualTo("USER0005");
            assertThat(page.rows().get(0)).isEqualTo(page.row(1));
            assertThat(page.rows().get(PAGE_SIZE - 1)).isEqualTo(page.row(PAGE_SIZE));

            assertThat(page.cdemoCu00UsrIdFirst().strip()).isEqualTo(page.row(1).userId().strip());
            assertThat(page.cdemoCu00UsrIdLast().strip())
                    .isEqualTo(page.row(PAGE_SIZE).userId().strip());
        }

        @ParameterizedTest(name = "subscript {0} is out of range")
        @ValueSource(ints = {-1, 0, 11, 12, 100})
        @DisplayName("gate G33 - a subscript outside 1..10 is refused, so nothing is silently padded")
        void aSubscriptOutsideTheTableIsRefused(int subscript) {
            UserListResponse page = UserListResponse.blank();
            UserListRequest request = UserListRequest.empty();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> page.row(subscript))
                    .withMessageContaining("10");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.row(subscript))
                    .withMessageContaining("10");
        }

        @Test
        @DisplayName("gate G33 - Java index 10 is past the end, which is what proves the table is ten")
        void javaIndexTenIsPastTheEnd() {
            List<UserListResponse.Row> rows = UserListResponse.blank().rows();

            assertThat(rows).hasSize(PAGE_SIZE);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("index 10 would be an eleventh row")
                    .isThrownBy(() -> rows.get(PAGE_SIZE));
            assertThat(rows.get(PAGE_SIZE - 1)).as("index 9 is the last one there is").isNotNull();
            assertThat(rows.get(PAGE_SIZE - 1).rowNumber())
                    .as("and it reports itself as COBOL row 10, so the conversion is not guessed at")
                    .isEqualTo(PAGE_SIZE);
            assertThat(rows.get(0).rowNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("gate G33 - the row list is immutable, so an eleventh row cannot be appended")
        void theRowListCannotGrow() {
            List<UserListResponse.Row> rows = UserListResponse.blank().rows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(rows.get(0)));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> UserListRequest.empty().rows()
                            .add(UserListRequest.empty().row(1)));
        }
    }

    @Nested
    @DisplayName("The paging arithmetic, counted on a stubbed browse - :300-325 and :352-374")
    class PagingCommandCounts {
        private BrowseCursor drive(int recordCount, UserListRequest incoming, byte eibAid, WorkArea ws) {
            BrowseCursor cursor = stubCursor(recordCount);
            controllerOverStub(stubRepository(cursor)).listUsers(incoming, eibAid, ws);
            return cursor;
        }

        @Test
        @DisplayName(":300-316 a full page costs eleven READNEXTs - ten to fill, one to look ahead")
        void aFullPageCostsElevenReads() {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = drive(PAGE_SIZE, reentering(), CicsAid.DFHENTER, ws);

            Mockito.verify(cursor, Mockito.times(PAGE_SIZE + 1)).readNext();
            Mockito.verify(cursor, Mockito.never()).readPrevious();
            assertThat(ws.idx()).as(":304 advanced the counter once per record read").isEqualTo(11);
            assertThat(ws.userSecEof()).as("the eleventh read ended the file").isTrue();
            assertThat(ws.cu00PageNum())
                    .as(":320-321 counted the page, because the fill itself did not run out")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(":304 the counter advances ONLY when a record was actually read")
        void theCounterAdvancesOnlyOnARecord() {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = drive(4, reentering(), CicsAid.DFHENTER, ws);

            Mockito.verify(cursor, Mockito.times(5)).readNext();
            assertThat(ws.idx()).as("four records read, so WS-IDX is 5 and never 6").isEqualTo(5);
            assertThat(ws.userSecEof()).isTrue();
        }

        @Test
        @DisplayName(":288 PF8 adds a twelfth read, because the anchor record is discarded first")
        void pf8AddsTheDiscardRead() {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = drive(PAGE_SIZE + 1,
                    reentering().withCdemoCu00UsrIdLast("ADMIN001").withNextPageYes(),
                    CicsAid.DFHPF8, ws);

            Mockito.verify(cursor, Mockito.times(PAGE_SIZE + 2)).readNext();
        }

        @Test
        @DisplayName(":311-316 the look-ahead is one extra read, and it decides the flag")
        void theLookAheadDecidesTheFlag() {
            WorkArea withMore = new WorkArea();
            BrowseCursor moreCursor = drive(PAGE_SIZE + 1, reentering(), CicsAid.DFHENTER, withMore);

            Mockito.verify(moreCursor, Mockito.times(PAGE_SIZE + 1)).readNext();
            assertThat(withMore.nextPageYes()).as(":313 SET NEXT-PAGE-YES").isTrue();
            assertThat(withMore.userSecEof()).as("the look-ahead succeeded, so no end of file").isFalse();

            WorkArea exact = new WorkArea();
            BrowseCursor exactCursor = drive(PAGE_SIZE, reentering(), CicsAid.DFHENTER, exact);

            Mockito.verify(exactCursor, Mockito.times(PAGE_SIZE + 1)).readNext();
            assertThat(exact.nextPageYes()).as(":315 SET NEXT-PAGE-NO").isFalse();
        }

        @ParameterizedTest(name = "a file of {0} record(s) still ends the browse exactly once")
        @ValueSource(ints = {0, 1, 5, 10, 11, 25})
        @DisplayName(":325 ENDBR is performed on every forward path, exactly once")
        void endBrowseHappensExactlyOnceOnEveryForwardPath(int recordCount) {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = drive(recordCount, reentering(), CicsAid.DFHENTER, ws);

            Mockito.verify(cursor, Mockito.times(1)).endBrowse();
        }

        @Test
        @DisplayName(":374 ENDBR is performed on the backward path too, exactly once")
        void endBrowseHappensOnceOnTheBackwardPath() {
            WorkArea ws = new WorkArea();
            BrowseCursor cursor = drive(PAGE_SIZE + 5,
                    reentering().withCdemoCu00PageNum(3).withCdemoCu00UsrIdFirst("USER0001")
                            .withNextPageYes(),
                    CicsAid.DFHPF7, ws);

            Mockito.verify(cursor, Mockito.times(1)).endBrowse();
            Mockito.verify(cursor, Mockito.atLeastOnce()).readPrevious();
            Mockito.verify(cursor, Mockito.never()).readNext();
        }

        @Test
        @DisplayName(":352-358 the backward fill starts at ten and counts DOWN")
        void theBackwardFillCountsDown() {
            WorkArea ws = new WorkArea();
            drive(PAGE_SIZE + 5,
                    reentering().withCdemoCu00PageNum(2).withCdemoCu00UsrIdFirst("USER0001")
                            .withNextPageYes(),
                    CicsAid.DFHPF7, ws);

            assertThat(ws.idx())
                    .as(":354's UNTIL WS-IDX <= 0 is the mirror of :300's UNTIL WS-IDX >= 11")
                    .isZero();
        }

        @Test
        @DisplayName(":365-369 the page number is decremented when above one and CLAMPED to one otherwise")
        void thePageNumberIsClampedAtOne() {
            WorkArea above = new WorkArea();
            drive(PAGE_SIZE + 5,
                    reentering().withCdemoCu00PageNum(3).withCdemoCu00UsrIdFirst("USER0001")
                            .withNextPageYes(),
                    CicsAid.DFHPF7, above);
            assertThat(above.cu00PageNum()).as(":367 decremented from 3").isEqualTo(2);

            WorkArea atOne = new WorkArea();
            drive(PAGE_SIZE + 5,
                    reentering().withCdemoCu00PageNum(1).withCdemoCu00UsrIdFirst("USER0001")
                            .withNextPageYes(),
                    CicsAid.DFHPF7, atOne);
            assertThat(atOne.cu00PageNum())
                    .as(":369 MOVE 1 - never zero and never negative")
                    .isEqualTo(1)
                    .isNotNegative();
        }

        @Test
        @DisplayName("gate G50 - both 88 states of NEXT-PAGE-FLG travel on the wire as Y and N")
        void bothStatesOfTheNextPageFlagTravel() {
            assertThat(UserListResponse.NEXT_PAGE_YES).isEqualTo("Y")
                    .hasSize(UserListResponse.CU00_NEXT_PAGE_FLG_LENGTH);
            assertThat(UserListResponse.NEXT_PAGE_NO).isEqualTo("N")
                    .hasSize(UserListResponse.CU00_NEXT_PAGE_FLG_LENGTH);

            WorkArea more = new WorkArea();
            UserListResponse withMore = controllerOverStub(stubRepository(stubCursor(PAGE_SIZE + 1)))
                    .listUsers(reentering(), CicsAid.DFHENTER, more);
            assertThat(withMore.cdemoCu00NextPageFlg()).isEqualTo(UserListResponse.NEXT_PAGE_YES);
            assertThat(withMore.nextPageYes()).isTrue();
            assertThat(withMore.nextPageNo()).as("the two conditions are exact negations").isFalse();

            WorkArea last = new WorkArea();
            UserListResponse atEnd = controllerOverStub(stubRepository(stubCursor(PAGE_SIZE)))
                    .listUsers(reentering(), CicsAid.DFHENTER, last);
            assertThat(atEnd.cdemoCu00NextPageFlg()).isEqualTo(UserListResponse.NEXT_PAGE_NO);
            assertThat(atEnd.nextPageNo()).isTrue();
            assertThat(atEnd.nextPageYes()).isFalse();
        }

        @Test
        @DisplayName(":317-322 a partial final page still counts, and an empty one does not")
        void aPartialPageCountsButAnEmptyOneDoesNot() {
            WorkArea partial = new WorkArea();
            drive(3, reentering(), CicsAid.DFHENTER, partial);
            assertThat(partial.idx()).isEqualTo(4);
            assertThat(partial.cu00PageNum()).as(":320-321 counted a page of three").isEqualTo(1);

            WorkArea empty = new WorkArea();
            drive(0, reentering(), CicsAid.DFHENTER, empty);
            assertThat(empty.idx()).isEqualTo(1);
            assertThat(empty.cu00PageNum()).as(":319 WS-IDX = 1, so no increment").isZero();
            assertThat(empty.nextPageYes()).as(":318 SET NEXT-PAGE-NO").isFalse();
        }
    }

    @Nested
    @DisplayName("Abbreviated combined relation conditions - :288 and :342, as truth tables")
    class AbbreviatedRelationConditions {
        private static final int LONGER_THAN_A_PAGE = 15;

        private static final int FORWARD_READS_WITHOUT_DISCARD = 11;

        private static final int BACKWARD_READS_WITHOUT_DISCARD = 11;

        private BrowseCursor forwardUnder(byte aid) {
            BrowseCursor cursor = stubCursor(LONGER_THAN_A_PAGE);
            controllerOverStub(stubRepository(cursor)).processPageForward(new WorkArea(), aid);
            return cursor;
        }

        private BrowseCursor backwardUnder(byte aid) {
            BrowseCursor cursor = stubCursor(LONGER_THAN_A_PAGE);
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageYes());
            controllerOverStub(stubRepository(cursor)).processPageBackward(ws, aid);
            return cursor;
        }

        @ParameterizedTest(name = ":288 is FALSE for AID {0}, so the discard read is skipped")
        @CsvSource({"ENTER", "PF7", "PF3"})
        @DisplayName(":288 each of the THREE named operands falsifies the condition on its own")
        void eachNamedOperandFalsifiesTheForwardCondition(String key) {
            byte aid = switch (key) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PF7" -> CicsAid.DFHPF7;
                default -> CicsAid.DFHPF3;
            };

            BrowseCursor cursor = forwardUnder(aid);

            Mockito.verify(cursor, Mockito.times(FORWARD_READS_WITHOUT_DISCARD)).readNext();
        }

        @ParameterizedTest(name = ":288 is TRUE for AID {0}, so the discard read happens")
        @CsvSource({"PF8", "PF1", "PF12", "CLEAR", "PA1"})
        @DisplayName(":288 any key that is NOT one of the three named operands satisfies the condition")
        void anyOtherKeySatisfiesTheForwardCondition(String key) {
            byte aid = switch (key) {
                case "PF8" -> CicsAid.DFHPF8;
                case "PF1" -> CicsAid.DFHPF1;
                case "PF12" -> CicsAid.DFHPF12;
                case "CLEAR" -> CicsAid.DFHCLEAR;
                default -> CicsAid.DFHPA1;
            };

            BrowseCursor cursor = forwardUnder(aid);

            Mockito.verify(cursor, Mockito.times(FORWARD_READS_WITHOUT_DISCARD + 1)).readNext();
        }

        @ParameterizedTest(name = ":342 is FALSE for AID {0}, so the discard read is skipped")
        @CsvSource({"ENTER", "PF8"})
        @DisplayName(":342 each of the TWO named operands falsifies the condition on its own")
        void eachNamedOperandFalsifiesTheBackwardCondition(String key) {
            byte aid = "ENTER".equals(key) ? CicsAid.DFHENTER : CicsAid.DFHPF8;

            BrowseCursor cursor = backwardUnder(aid);

            Mockito.verify(cursor, Mockito.times(BACKWARD_READS_WITHOUT_DISCARD)).readPrevious();
            Mockito.verify(cursor, Mockito.never()).readNext();
        }

        @ParameterizedTest(name = ":342 is TRUE for AID {0}, so the discard read happens")
        @CsvSource({"PF7", "PF3", "PF1", "CLEAR"})
        @DisplayName(":342 any key that is NOT one of the two named operands satisfies the condition")
        void anyOtherKeySatisfiesTheBackwardCondition(String key) {
            byte aid = switch (key) {
                case "PF7" -> CicsAid.DFHPF7;
                case "PF3" -> CicsAid.DFHPF3;
                case "PF1" -> CicsAid.DFHPF1;
                default -> CicsAid.DFHCLEAR;
            };

            BrowseCursor cursor = backwardUnder(aid);

            Mockito.verify(cursor, Mockito.times(BACKWARD_READS_WITHOUT_DISCARD + 1)).readPrevious();
        }

        @Test
        @DisplayName("the naive reading - comparing only the first operand - would invert three answers")
        void theNaiveReadingWouldInvertThreeAnswers() {
            for (byte aid : new byte[] {CicsAid.DFHPF7, CicsAid.DFHPF3}) {
                assertThat(aid).as("the naive first-operand test alone is TRUE for this key")
                        .isNotEqualTo(CicsAid.DFHENTER);
                Mockito.verify(forwardUnder(aid), Mockito.times(FORWARD_READS_WITHOUT_DISCARD))
                        .readNext();
            }
            assertThat(CicsAid.DFHPF8).isNotEqualTo(CicsAid.DFHENTER);
            Mockito.verify(backwardUnder(CicsAid.DFHPF8),
                    Mockito.times(BACKWARD_READS_WITHOUT_DISCARD)).readPrevious();

            Mockito.verify(forwardUnder(CicsAid.DFHPF8),
                    Mockito.times(FORWARD_READS_WITHOUT_DISCARD + 1)).readNext();
            Mockito.verify(backwardUnder(CicsAid.DFHPF7),
                    Mockito.times(BACKWARD_READS_WITHOUT_DISCARD + 1)).readPrevious();
        }

        @Test
        @DisplayName("the observable effect of the discard is which record row 1 shows")
        void theDiscardIsObservableAsAOneRecordShift() {
            WorkArea kept = new WorkArea();
            controllerOver(PAGE_SIZE).processPageForward(kept, CicsAid.DFHENTER);
            assertThat(kept.map().usrId(1).strip())
                    .as("the condition was FALSE, so the anchor record is row 1")
                    .isEqualTo(userId(0));

            WorkArea discarded = new WorkArea();
            controllerOver(PAGE_SIZE).processPageForward(discarded, CicsAid.DFHPF8);
            assertThat(discarded.map().usrId(1).strip())
                    .as("the condition was TRUE, so row 1 is the record AFTER the anchor")
                    .isEqualTo(userId(1));
        }
    }

    @Nested
    @DisplayName("The selection guard and the WHEN OTHER asymmetry - :187-216 (gates G40, G30)")
    class InvalidSelectionAsymmetry {
        private WorkArea enterWith(String selection, String userId) {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            if (selection != null) {
                ws.map().sel(1, selection);
            }
            if (userId != null) {
                ws.map().usrId(1, CODEC.movePicX(userId, EIGHT));
            }
            controllerOver(PAGE_SIZE + 1).processEnterKey(ws, CicsAid.DFHENTER);
            return ws;
        }

        @ParameterizedTest(name = "selection ''{0}'' complains without raising the error flag")
        @ValueSource(strings = {"X", "x", "1", "?", "Y", "N", "*", "0"})
        @DisplayName(":210-214 WHEN OTHER raises NO error flag - the asymmetry with every other arm")
        void theInvalidSelectionArmRaisesNoErrorFlag(String selection) {
            WorkArea ws = enterWith(selection, "ADMIN001");

            assertThat(ws.message().strip())
                    .isEqualTo("Invalid selection. Valid values are U and D")
                    .isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
            assertThat(ws.usrIdInLength()).isEqualTo(UserMenuController.CURSOR_ON_USRIDIN);

            assertThat(ws.errFlgOn())
                    .as(":210-214 sets no WS-ERR-FLG, unlike every other error arm in the program")
                    .isFalse();
            assertThat(ws.transferred()).as("and no XCTL was issued").isFalse();
        }

        @Test
        @DisplayName(":210-214 WHEN OTHER performs NO send of its own - the page comes from :228")
        void theInvalidSelectionArmPerformsNoSendOfItsOwn() {
            WorkArea ws = enterWith("X", "ADMIN001");

            assertThat(ws.sends())
                    .as("one send, from :329 inside PROCESS-PAGE-FORWARD, not from the arm")
                    .hasSize(1);
            SentScreen sent = ws.sends().get(0);
            assertThat(message(sent.screen())).isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
            assertThat(rowUserId(sent.screen(), 1))
                    .as("the page was listed under the complaint, because no error flag stopped it")
                    .isEqualTo("ADMIN001");
            assertThat(sent.screen().cdemoCu00PageNum())
                    .as(":227 zeroed the count and :309-310 counted this page")
                    .isEqualTo(1);

            WorkArea badKey = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHPF12, badKey);
            assertThat(badKey.errFlgOn()).as(":133 MOVE 'Y' TO WS-ERR-FLG").isTrue();
            assertThat(badKey.message().strip()).isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        @Test
        @DisplayName(":187-188 a NON-BLANK flag with a BLANK identifier does not route, and does not complain")
        void aFlagWithoutAnIdentifierFallsThrough() {
            WorkArea ws = enterWith("U", null);

            assertThat(ws.transferred()).isFalse();
            assertThat(ws.cu00UsrSelFlg().strip())
                    .as(":153 captured the flag from the ticked row")
                    .isEqualTo("U");
            assertThat(ws.cu00UsrSelected())
                    .as(":154 copied USRIDnnI of a row nothing painted, so what the anchor now holds is "
                            + "that row's unpainted image - the move is faithful either way, and this "
                            + "asserts which image was moved")
                    .isEqualTo(ScreenFieldImage.unpainted(UserListResponse.CU00_USR_SELECTED_LENGTH));
            assertThat(ws.message().strip())
                    .as("the guard failed, so the EVALUATE never ran and no complaint was made")
                    .isNotEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
        }

        @Test
        @DisplayName(":187-188 a BLANK flag with a NON-BLANK identifier does not route either")
        void anIdentifierWithoutAFlagFallsThrough() {
            WorkArea ws = enterWith(null, "ADMIN001");

            assertThat(ws.transferred()).isFalse();
            assertThat(ws.cu00UsrSelFlg().strip())
                    .as(":183 MOVE SPACES TO CDEMO-CU00-USR-SEL-FLG")
                    .isEmpty();
            assertThat(ws.cu00UsrSelected().strip())
                    .as(":184 MOVE SPACES TO CDEMO-CU00-USR-SELECTED - the identifier is NOT captured")
                    .isEmpty();
            assertThat(ws.message().strip()).isNotEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
        }

        @Test
        @DisplayName(":187-188 neither operand alone is enough - both must be non-blank")
        void bothOperandsAreRequired() {
            assertThat(enterWith(null, null).transferred()).as("neither").isFalse();
            assertThat(enterWith("U", null).transferred()).as("flag only").isFalse();
            assertThat(enterWith(null, "ADMIN001").transferred()).as("identifier only").isFalse();
            assertThat(enterWith("U", "ADMIN001").transferred()).as("both").isTrue();
        }

        @ParameterizedTest(name = "row {0} selected with U routes to COUSR02C")
        @ValueSource(ints = {1, 10})
        @DisplayName("gate G33 and G40 together - the first and last rows are both selectable")
        void theFirstAndLastRowsAreBothSelectable(int rowNumber) {
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering());
            ws.map().sel(rowNumber, "U");
            ws.map().usrId(rowNumber, CODEC.movePicX(userId(rowNumber - 1), EIGHT));

            UserListResponse response = controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

            assertThat(response).isNotNull();
            assertThat(response.nextProgram().strip())
                    .isEqualTo(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
            assertThat(response.cdemoCu00UsrSelected().strip()).isEqualTo(userId(rowNumber - 1));
            assertThat(ws.selectedRow()).isEqualTo(rowNumber);
        }

        @Test
        @DisplayName(":192-195 and :202-205 the transfer sets the tranid, the program and the context")
        void theTransferSetsTheNavigationMembers() {
            for (String selection : List.of("U", "u", "D", "d")) {
                WorkArea ws = new WorkArea();
                ws.acceptCommarea(reentering());
                ws.map().sel(1, selection);
                ws.map().usrId(1, CODEC.movePicX("ADMIN001", EIGHT));

                UserListResponse response =
                        controllerOver(PAGE_SIZE).processEnterKey(ws, CicsAid.DFHENTER);

                String expected = "U".equalsIgnoreCase(selection)
                        ? UserListResponse.NEXT_PROGRAM_USER_UPDATE
                        : UserListResponse.NEXT_PROGRAM_USER_DELETE;
                assertThat(response.nextProgram().strip())
                        .as("selection '%s'", selection).isEqualTo(expected);
                assertThat(response.navigationContext().fromTranid()).isEqualTo("CU00");
                assertThat(response.navigationContext().fromProgram()).isEqualTo("COUSR00C");
                assertThat(response.navigationContext().pgmContext())
                        .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                        .isZero();
                assertThat(response.navigationContext().isEnter())
                        .as("88 CDEMO-PGM-ENTER VALUE 0").isTrue();
                assertThat(response.navigationContext().isReenter())
                        .as("88 CDEMO-PGM-REENTER VALUE 1 - the exact negation here").isFalse();
            }
        }

        @Test
        @DisplayName("gate G30 - the selection EVALUATE's WHEN arms are ordered and WHEN OTHER is last")
        void theSelectionEvaluateIsOrdered() {
            assertThat(enterWith("U", "ADMIN001").cu00UsrSelFlg().strip()).isEqualTo("U");
            for (String updates : List.of("U", "u")) {
                assertThat(enterWith(updates, "ADMIN001").transferred())
                        .as("'%s' reaches the COUSR02C arm", updates).isTrue();
            }
            for (String deletes : List.of("D", "d")) {
                assertThat(enterWith(deletes, "ADMIN001").transferred())
                        .as("'%s' reaches the COUSR03C arm", deletes).isTrue();
            }
            for (String other : List.of("X", "x", "E", "e", "A", "1", "-")) {
                WorkArea ws = enterWith(other, "ADMIN001");
                assertThat(ws.transferred()).as("'%s' reaches WHEN OTHER", other).isFalse();
                assertThat(ws.message().strip())
                        .isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
            }
        }
    }

    @Nested
    @DisplayName("Navigation over HTTP - nextProgram is a field, not a redirect (gates G37, G40)")
    class NavigationIsAResponseField {
        private MockMvc mockMvc;

        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void standalone() {
            mockMvc = MockMvcBuilders.standaloneSetup(
                    controllerOverStub(stubRepository(stubCursor(PAGE_SIZE)))).build();
        }

        private String tickedRowOne(String selection) throws Exception {
            UserListRequest.UserListRow row = UserListRequest.empty().row(1)
                    .withSel(selection)
                    .withUsrId(CODEC.movePicX("ADMIN001", EIGHT));
            List<UserListRequest.UserListRow> rows =
                    new ArrayList<>(UserListRequest.empty().rows());
            rows.set(0, row);
            UserListRequest request = new UserListRequest(null, null, null, null, null, null, null, null,
                    rows, null, null, null, 0, null, null, null,
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.aidImage(CicsAid.DFHENTER));
            return mapper.writeValueAsString(request);
        }

        @ParameterizedTest(name = "''{0}'' answers 200 with nextProgram {1} and no Location header")
        @CsvSource({"U,COUSR02C", "u,COUSR02C", "D,COUSR03C", "d,COUSR03C"})
        @DisplayName("a transfer of control is a 200 carrying nextProgram - never a 3xx, never a redirect")
        void aTransferIsAFieldAndNotARedirect(String selection, String expectedProgram) throws Exception {
            MvcResult result = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tickedRowOne(selection)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(expectedProgram))
                    .andExpect(jsonPath("$.cdemoCu00UsrSelected").value("ADMIN001"))
                    .andReturn();

            MockHttpServletResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Location")).as("no redirect target").isNull();
            assertThat(response.getRedirectedUrl()).as("no sendRedirect").isNull();
            assertThat(response.getForwardedUrl()).as("no server-side forward").isNull();
            assertThat(result.getModelAndView()).as("no view is resolved - this is a REST body").isNull();
        }

        @Test
        @DisplayName("the navigation members travel in the body, at their declared widths")
        void theNavigationMembersTravelInTheBody() throws Exception {
            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tickedRowOne("U")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.fromTranid").value("CU00"))
                    .andExpect(jsonPath("$.navigationContext.fromProgram").value("COUSR00C"))
                    .andExpect(jsonPath("$.navigationContext.pgmContext").value(0));

            assertThat(UserListResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("The 34-byte CU00 extension, and statelessness (gates G37, G38, G22, G50)")
    class CommareaAndStatelessness {
        private MockMvc mockMvc;

        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void standalone() {
            mockMvc = MockMvcBuilders.standaloneSetup(
                    controllerOverStub(stubRepository(stubCursor(PAGE_SIZE)))).build();
        }

        @Test
        @DisplayName(":66-75 the 34 bytes live on BOTH DTO halves, and the shared area stays at 160")
        void theExtensionLivesOnTheDtosAndNotOnTheSharedArea() {
            for (int declared : new int[] {
                    UserListRequest.CU00_INFO_LENGTH, UserListResponse.CU00_INFO_LENGTH}) {
                assertThat(declared).isEqualTo(34);
            }
            assertThat(UserListRequest.CU00_USRID_FIRST_LENGTH
                    + UserListRequest.CU00_USRID_LAST_LENGTH
                    + UserListRequest.CU00_PAGE_NUM_LENGTH
                    + UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH
                    + UserListRequest.CU00_USR_SEL_FLG_LENGTH
                    + UserListRequest.CU00_USR_SELECTED_LENGTH)
                    .as("the six members add up to the declared extension")
                    .isEqualTo(34);

            assertThat(UserListRequest.CU00_COMMAREA_LENGTH)
                    .isEqualTo(UserListResponse.CU00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + 34)
                    .isEqualTo(194);

            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y is 160 bytes for every one of the seventeen online programs")
                    .isEqualTo(160);
            List<String> sharedMembers = new ArrayList<>();
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    sharedMembers.add(field.getName().toLowerCase(Locale.ROOT));
                }
            }
            assertThat(sharedMembers)
                    .as("no CU00 member was added to the shared communication area")
                    .noneMatch(name -> name.contains("cu00"))
                    .noneMatch(name -> name.contains("nextpage"))
                    .noneMatch(name -> name.contains("usrsel"))
                    .noneMatch(name -> name.contains("pagenum"));
        }

        @Test
        @DisplayName("gate G22 - the page number is an integral type on both halves, never floating point")
        void thePageNumberIsIntegral() throws NoSuchMethodException {
            assertThat(UserListResponse.class.getMethod("cdemoCu00PageNum").getReturnType())
                    .isEqualTo(int.class);
            assertThat(UserListRequest.class.getMethod("cdemoCu00PageNum").getReturnType())
                    .isEqualTo(int.class);
            assertThat(WorkArea.class.getDeclaredMethod("cu00PageNum").getReturnType())
                    .isEqualTo(int.class);

            for (Class<?> owner : List.of(UserListRequest.class, UserListResponse.class,
                    UserMenuController.class, WorkArea.class)) {
                for (Method method : owner.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s returns a floating-point type", owner.getSimpleName(),
                                    method.getName())
                            .isNotEqualTo(double.class).isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class).isNotEqualTo(Float.class);
                }
                for (Field field : owner.getDeclaredFields()) {
                    assertThat(field.getType())
                            .as("%s.%s is a floating-point field", owner.getSimpleName(),
                                    field.getName())
                            .isNotEqualTo(double.class).isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class).isNotEqualTo(Float.class);
                }
            }

            assertThat(UserListResponse.class.getMethod("pageNum").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("gate G37 - no HttpSession is created and no cookie is set")
        void noSessionAndNoCookie() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering().withAid(
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("no session was created on demand")
                    .isNull();
            MockHttpServletResponse response = result.getResponse();
            assertThat(response.getCookies()).as("no cookie, so nothing is pinned to a client").isEmpty();
            assertThat(response.getHeader("Set-Cookie")).isNull();

            for (Field field : UserMenuController.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is not final", field.getName()).isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    Class<?> type = field.getType();
                    assertThat(type.isArray())
                            .as("static %s is an array, which is mutable however final it is",
                                    field.getName())
                            .isFalse();
                    assertThat(Collection.class.isAssignableFrom(type)
                            || Map.class.isAssignableFrom(type)
                            || type.getName().startsWith("java.util.concurrent.atomic"))
                            .as("static %s could accumulate state across requests", field.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("gate G37 - two independent requests do not interfere")
        void independentRequestsDoNotInterfere() throws Exception {
            mockMvc = MockMvcBuilders.standaloneSetup(
                    controllerOverStub(stubRepositoryWithFreshCursors(PAGE_SIZE, 4))).build();

            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering().withAid(
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cdemoCu00PageNum").value(1));

            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering()
                                    .withCdemoCu00PageNum(7)
                                    .withAid(PfKeyResolver.aidImage(CicsAid.DFHPF8))
                                    .withCdemoCu00UsrIdLast("ADMIN001").withNextPageYes())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cdemoCu00PageNum").value(8));

            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering().withAid(
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cdemoCu00PageNum").value(1))
                    .andExpect(jsonPath("$.usrid01").value("ADMIN001"));
        }

        @Test
        @DisplayName("gate G50 - both 88 states of CDEMO-PGM-CONTEXT are driven, and they differ")
        void bothProgramContextStatesAreDriven() throws Exception {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
            assertThat(NavigationContext.empty().withPgmEnter().isEnter()).isTrue();
            assertThat(NavigationContext.empty().withPgmEnter().isReenter()).isFalse();
            assertThat(NavigationContext.empty().withPgmReenter().isReenter()).isTrue();
            assertThat(NavigationContext.empty().withPgmReenter().isEnter()).isFalse();

            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(entering().withAid(
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.pgmContext")
                            .value(NavigationContext.PGM_CONTEXT_REENTER));

            mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering().withAid("PFK12"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(CODEC.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                                    UserListResponse.ERRMSG_LENGTH)));
        }

        @Test
        @DisplayName("gate G38 - COUSR00 moves no attribute byte, so no CSSETATY highlight is applied")
        void noFieldHighlightIsAppliedByThisScreen() throws Exception {
            for (UserListRequest state : List.of(entering().withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER)),
                    reentering().withAid(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER)), reentering().withAid("PFK12"))) {
                String body = mockMvc.perform(get("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(state)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

                var metadata = mapper.readTree(body).get("screenMetadata");
                assertThat(metadata.get("messageColour").asInt())
                        .as("BmsAttributes.DFHDFCOL - the map's declared default, not a chosen colour")
                        .isEqualTo(BmsAttributes.DFHDFCOL);
                assertThat(metadata.has("fields") && metadata.get("fields").size() > 0)
                        .as("no xxxC, xxxP, xxxH or xxxV item is written by this program")
                        .isFalse();
            }

            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
        }

        @Test
        @DisplayName("the cursor request is the ONLY presentation fact this screen sets, and it is metadata")
        void theCursorRequestIsTheOnlyPresentationFact() throws Exception {
            String body = mockMvc.perform(get("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentering().withAid(
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var metadata = mapper.readTree(body).get("screenMetadata");
            assertThat(metadata.get("cursorField").asText())
                    .isEqualTo(UserListResponse.USRIDIN_FIELD).isEqualTo("USRIDIN");
            assertThat(mapper.readTree(body).has("usrIdInL")).isFalse();

            assertThat(metadata.get("resetAllOutputFields").asBoolean())
                    .as(":103 SET SEND-ERASE-YES, and no 'already at the ...' path cleared it")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Browse outcomes and their consequences for the paragraph - gate G47")
    class BrowseOutcomeConsequences {
        private BrowseCursor cursorOpening(FileStatus.Outcome openOutcome, int openResp) {
            BrowseCursor cursor = Mockito.mock(BrowseCursor.class);
            Mockito.when(cursor.openOutcome()).thenReturn(openOutcome);
            Mockito.when(cursor.openCicsResp()).thenReturn(OptionalInt.of(openResp));
            Mockito.when(cursor.readNext()).thenReturn(ReadResult.found(record(0)));
            Mockito.when(cursor.readPrevious()).thenReturn(ReadResult.found(record(0)));
            AtomicInteger ended = new AtomicInteger();
            Mockito.when(cursor.isOpen()).thenAnswer(invocation -> ended.get() == 0);
            Mockito.doAnswer(invocation -> {
                ended.incrementAndGet();
                return null;
            }).when(cursor).endBrowse();
            return cursor;
        }

        @Test
        @DisplayName(":597-599 STARTBR NORMAL lets the paragraph run - the reads and the ENDBR both happen")
        void aNormalOpenLetsTheParagraphRun() {
            BrowseCursor cursor = stubCursor(PAGE_SIZE);
            WorkArea ws = new WorkArea();
            controllerOverStub(stubRepository(cursor)).processPageForward(ws, CicsAid.DFHENTER);

            Mockito.verify(cursor, Mockito.atLeastOnce()).readNext();
            Mockito.verify(cursor).endBrowse();
            assertThat(ws.errFlgOn()).isFalse();
            assertThat(ws.sends()).as("and the page was sent at :329").isNotEmpty();
        }

        @Test
        @DisplayName(":286 a STARTBR that raised the error flag skips the WHOLE remainder of :282-331")
        void aFailedOpenSkipsTheRestOfTheForwardParagraph() {
            BrowseCursor cursor = cursorOpening(FileStatus.Outcome.OTHER, FileStatus.INVREQ);
            WorkArea ws = new WorkArea();

            controllerOverStub(stubRepository(cursor)).processPageForward(ws, CicsAid.DFHPF8);

            assertThat(ws.errFlgOn()).as(":609 MOVE 'Y' TO WS-ERR-FLG").isTrue();
            Mockito.verify(cursor, Mockito.never()).readNext();
            Mockito.verify(cursor, Mockito.never()).readPrevious();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_UNABLE_TO_LOOKUP);
            assertThat(ws.idx()).as("the fill loop never started, so WS-IDX was never even set to 1")
                    .isZero();
        }

        @Test
        @DisplayName(":340 the same guard protects the backward paragraph :336-379")
        void aFailedOpenSkipsTheRestOfTheBackwardParagraph() {
            BrowseCursor cursor = cursorOpening(FileStatus.Outcome.OTHER, FileStatus.INVREQ);
            WorkArea ws = new WorkArea();
            ws.acceptCommarea(reentering().withNextPageYes());

            controllerOverStub(stubRepository(cursor)).processPageBackward(ws, CicsAid.DFHPF7);

            assertThat(ws.errFlgOn()).isTrue();
            Mockito.verify(cursor, Mockito.never()).readPrevious();
            Mockito.verify(cursor, Mockito.never()).readNext();
        }

        @Test
        @DisplayName(":600-606 STARTBR NOTFND does NOT raise the flag, so the paragraph continues")
        void aNotFoundOpenDoesNotStopTheParagraph() {
            BrowseCursor cursor = cursorOpening(FileStatus.Outcome.NOT_FOUND, FileStatus.NOTFND);
            WorkArea ws = new WorkArea();

            controllerOverStub(stubRepository(cursor)).processPageForward(ws, CicsAid.DFHENTER);

            assertThat(ws.errFlgOn()).as(":600-606 sets no error flag").isFalse();
            assertThat(ws.userSecEof()).as(":602 SET USER-SEC-EOF").isTrue();
            assertThat(ws.message().strip()).isEqualTo(UserMenuController.MSG_AT_TOP);
            Mockito.verify(cursor).endBrowse();
            assertThat(ws.idx()).as(":298 MOVE 1 TO WS-IDX ran; the loop then refused to turn")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the three end-of-file messages are three DIFFERENT strings, each on its own path")
        void theThreeEndOfFileMessagesAreDistinct() {
            assertThat(List.of(UserMenuController.MSG_AT_TOP,
                            UserMenuController.MSG_REACHED_BOTTOM,
                            UserMenuController.MSG_REACHED_TOP,
                            UserMenuController.MSG_ALREADY_AT_TOP,
                            UserMenuController.MSG_ALREADY_AT_BOTTOM,
                            UserMenuController.MSG_UNABLE_TO_LOOKUP))
                    .doesNotHaveDuplicates()
                    .allSatisfy(message -> assertThat(message)
                            .hasSizeLessThanOrEqualTo(UserMenuController.WS_MESSAGE_LENGTH));

            assertThat(UserMenuController.MSG_AT_TOP).isEqualTo("You are at the top of the page...");
            assertThat(UserMenuController.MSG_REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
            assertThat(UserMenuController.MSG_REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");
        }

        @Test
        @DisplayName("gate G50 - both 88 states of WS-USER-SEC-EOF are reached, and they are exclusive")
        void bothEndOfFileStatesAreReached() {
            WorkArea notEof = new WorkArea();
            controllerOverStub(stubRepository(stubCursor(PAGE_SIZE + 1)))
                    .processPageForward(notEof, CicsAid.DFHENTER);
            assertThat(notEof.userSecEof())
                    .as("eleven records, so even the look-ahead succeeded")
                    .isFalse();

            WorkArea eof = new WorkArea();
            controllerOverStub(stubRepository(stubCursor(PAGE_SIZE)))
                    .processPageForward(eof, CicsAid.DFHENTER);
            assertThat(eof.userSecEof()).as("exactly ten, so the look-ahead ended the file").isTrue();
        }

        @Test
        @DisplayName("gate G50 - both 88 states of WS-SEND-ERASE-FLG reach the wire")
        void bothSendEraseStatesReachTheWire() {
            WorkArea erasing = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHENTER, erasing);
            assertThat(erasing.sendEraseYes()).isTrue();
            assertThat(erasing.sends()).isNotEmpty()
                    .allSatisfy(sent -> assertThat(sent.erase()).isTrue());

            WorkArea notErasing = new WorkArea();
            controllerOver(PAGE_SIZE).listUsers(
                    reentering().withCdemoCu00PageNum(1).withAid("PFK07"), CicsAid.DFHPF7, notErasing);
            assertThat(notErasing.sendEraseYes()).as(":253 SET SEND-ERASE-NO").isFalse();
            assertThat(notErasing.sends()).isNotEmpty()
                    .allSatisfy(sent -> assertThat(sent.erase()).isFalse());
            assertThat(notErasing.message().strip())
                    .isEqualTo(UserMenuController.MSG_ALREADY_AT_TOP);
        }

        @Test
        @DisplayName("the DUSRSECJ seed at ten, fewer and more gives the three page shapes")
        void theSeedGivesTheThreePageShapes() {
            UserListResponse exactlyOnePage =
                    controllerOver(PAGE_SIZE).listUsers(reentering(), CicsAid.DFHENTER);
            assertThat(exactlyOnePage.rows()).hasSize(PAGE_SIZE);
            assertThat(rowUserId(exactlyOnePage, 1)).isEqualTo("ADMIN001");
            assertThat(rowUserId(exactlyOnePage, PAGE_SIZE)).isEqualTo("USER0005");
            assertThat(exactlyOnePage.nextPageNo()).as("the look-ahead failed").isTrue();
            assertThat(exactlyOnePage.cdemoCu00PageNum()).isEqualTo(1);
            assertThat(message(exactlyOnePage)).isEqualTo(UserMenuController.MSG_REACHED_BOTTOM);

            UserListResponse partial = controllerOver(5).listUsers(reentering(), CicsAid.DFHENTER);
            assertThat(rowUserId(partial, 5)).isEqualTo("ADMIN005");
            assertThat(rowUserId(partial, 6)).as("the trailing rows stay blank").isEmpty();
            assertThat(partial.cdemoCu00PageNum()).as(":320-321 counted a partial page").isEqualTo(1);
            assertThat(partial.nextPageNo()).isTrue();

            UserListResponse multiPage =
                    controllerOver(PAGE_SIZE + 1).listUsers(reentering(), CicsAid.DFHENTER);
            assertThat(multiPage.rows()).hasSize(PAGE_SIZE);
            assertThat(multiPage.nextPageYes()).as(":313 a further record exists").isTrue();
            assertThat(message(multiPage))
                    .as("no bottom message, because the look-ahead succeeded")
                    .isNotEqualTo(UserMenuController.MSG_REACHED_BOTTOM);
        }
    }
}
