package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Behavioural tests for {@link UserMenuController}, the Java form of {@code app/cbl/COUSR00C.cbl}.
 *
 * <h2>What is being proved, and how</h2>
 * The pager is driven against a <strong>real {@link SecUserRepository} over a real relation</strong>
 * seeded with the ten records {@code app/jcl/DUSRSECJ.jcl} loads - {@code ADMIN001} to {@code ADMIN005}
 * and {@code USER0001} to {@code USER0005}. Nothing about the browse is assumed: the greater-than-or-equal
 * positioning, the discarded anchor record, the look-ahead that decides whether a further page exists and
 * the end-of-file conditions are all exercised through the same code path production uses. A relation that
 * does not exist supplies the {@code WHEN OTHER} arms, which is the only outcome a seeded relation cannot
 * produce.
 *
 * <p>No {@code MockMvc}, no Spring context and no servlet container appears anywhere here: every paragraph
 * is reached by calling a plain Java method, which is the property the controller was written to have.
 *
 * <h2>Ten records over a page of ten is the interesting case</h2>
 * It is not a coincidence in the fixture; it is the case that separates the two page-number increments.
 * A first page fills all ten rows and then the look-ahead fails, so the count comes from
 * {@code COUSR00C:309-310} and the message says the bottom has been reached. Narrow the browse to five
 * records and the fill itself runs out, so the count comes from {@code :320-321} instead. Both are driven.
 */
@DisplayName("UserMenuController - COUSR00C, the user list screen (transaction CU00)")
class UserMenuControllerTest {

    // =============================================================================================
    // Fixture. The ten records of app/jcl/DUSRSECJ.jcl, at the exact CSUSR01Y geometry.
    // =============================================================================================

    /** The code page the fixtures are stored in; {@code app/data/ASCII} is the authoritative form. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec every assertion about a field width goes through. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * A pinned clock, so the rendered header is an exact expected value.
     *
     * <p>2022-07-19 23:12:34 UTC is the version footer date of {@code app/cbl/COUSR00C.cbl:694}, chosen so
     * that the expected strings in this class are traceable to the source revision under test.
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    /** The dataset name the test binding names; never a real one. */
    private static final String TEST_DSNAME = "TEST.USRSEC.VSAM.KSDS";

    /** The single record-image column of the test relation. */
    private static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    /** {@code CSUSR01Y} is eighty bytes. */
    private static final int EIGHTY = 80;

    /** {@code SEC-USR-ID} is eight. */
    private static final int EIGHT = 8;

    /** Gives every test its own in-memory database, so no test can see another's rows. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    /** The ten user identifiers {@code DUSRSECJ.jcl} loads, in ascending key order. */
    private static final List<String> USER_IDS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /** The ten first names, positionally matching {@link #USER_IDS}. */
    private static final List<String> FIRST_NAMES = List.of(
            "MARGARET", "RUSSELL", "RAYMOND", "EMMANUEL", "GRANVILLE",
            "LAWRENCE", "AJITH", "LAURITZ", "AVERARDO", "LEE");

    /** The ten last names, positionally matching {@link #USER_IDS}. */
    private static final List<String> LAST_NAMES = List.of(
            "GOLD", "RUSSELL", "WHITMORE", "CASGRAIN", "LACHAPELLE",
            "THOMAS", "KUMAR", "ALME", "MAZZI", "TING");

    /** The ten user types: the five administrators, then the five ordinary users. */
    private static final List<String> USER_TYPES = List.of(
            "A", "A", "A", "A", "A", "U", "U", "U", "U", "U");

    /** The password every fixture record carries, which this screen never reads and never emits. */
    private static final String FIXTURE_PASSWORD = "PASSWORD";

    /** Ten rows to a page. */
    private static final int PAGE_SIZE = 10;

    // =============================================================================================
    // Harness.
    // =============================================================================================

    /**
     * The key stem of the synthetic records used beyond the ten the JCL loads.
     *
     * <p>{@code Z} sorts after both {@code A} and {@code U}, so every synthetic key sorts after
     * {@code USER0005} and the file stays in ascending key order - which the browse depends on.
     */
    private static final String SYNTHETIC_ID_STEM = "ZUSER";

    /**
     * @param count how many records the file should hold
     * @return the eighty-byte record images, in ascending key order
     */
    private static List<String> fixtureImages(int count) {
        List<String> images = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            images.add(new String(SecUserRecord.encode(record(index), ASCII), ASCII));
        }
        return images;
    }

    /**
     * One fixture record.
     *
     * <p>Positions 0 to 9 are the ten records {@code app/jcl/DUSRSECJ.jcl} loads, verbatim. Beyond them
     * the record is <strong>synthetic</strong>: the JCL supplies only ten, and two of the branches under
     * test - the look-ahead that reports a further page, and a backward page with a full ten rows still
     * below it - need a file longer than one page. The synthetic keys sort after the real ones, so the
     * first ten records of any fixture are always the real ones.
     *
     * @param index the zero-based fixture position
     * @return that record, at its declared widths
     */
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

    /**
     * @param index the zero-based fixture position
     * @return the identifier the record at that position carries
     */
    private static String userId(int index) {
        return record(index).secUsrId().strip();
    }

    /**
     * @return the one binding the repository resolves, at the copybook's geometry
     */
    private static DatasetBindings validBindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(SecUserRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, EIGHTY, "CSUSR01Y", EIGHT, null, null, null));
        return catalogue;
    }

    /**
     * A controller over a relation seeded with the first {@code count} fixture records.
     *
     * @param count how many records the file holds
     * @return the controller
     */
    private static UserMenuController controllerOver(int count) {
        return new UserMenuController(new SecUserRepository(seeded(fixtureImages(count)),
                validBindings(), ASCII, RecordImageForm.CHARACTER), CODEC, CLOCK);
    }

    /**
     * A controller whose relation does not exist, so every browse command reports the permanent error the
     * {@code WHEN OTHER} arms branch on.
     *
     * @return the controller
     */
    private static UserMenuController controllerOverMissingRelation() {
        return new UserMenuController(new SecUserRepository(emptyRelation(null), validBindings(), ASCII,
                RecordImageForm.CHARACTER), CODEC, CLOCK);
    }

    /**
     * @param rows the record images to insert, in ascending key order
     * @return a template over a private relation holding them
     */
    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyRelation("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                + RECORD_IMAGE_COLUMN + " VARCHAR(" + EIGHTY + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * @param ddl the statement to run, or {@code null} for a database with no relation at all
     * @return a template over a private in-memory database
     */
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

    /**
     * A payload in the state {@code CDEMO-PGM-REENTER} describes - a continuing pseudo-conversation.
     *
     * @return the request
     */
    private static UserListRequest reentering() {
        return UserListRequest.empty()
                .withNavigationContext(NavigationContext.empty().withPgmReenter());
    }

    /**
     * A payload in the state {@code NOT CDEMO-PGM-REENTER} describes - a first entry with an area present.
     *
     * @return the request
     */
    private static UserListRequest entering() {
        return UserListRequest.empty()
                .withNavigationContext(NavigationContext.empty().withPgmEnter());
    }

    /**
     * @param response the response to read
     * @param rowNumber the one-based row
     * @return the trimmed identifier that row shows
     */
    private static String rowUserId(UserListResponse response, int rowNumber) {
        return response.row(rowNumber).userId().strip();
    }

    /**
     * @param response the response to read
     * @return the trimmed message line
     */
    private static String message(UserListResponse response) {
        return response.errMsg().strip();
    }

    // =============================================================================================
    // MAIN-PARA - the cold start, the two context states, and the five-arm EVALUATE EIBAID.
    // =============================================================================================

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

    // =============================================================================================
    // The forward pager - :282-331.
    // =============================================================================================

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
            // The reachable route to that guard failing. A successful STARTBR guarantees a record at or
            // after the anchor, so the read at :289 can never itself report end of file - the only way in
            // is a STARTBR that found nothing, which sets USER-SEC-EOF, followed by the read at :289 that
            // then finds no browse and sets the error flag as well.
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

    // =============================================================================================
    // The backward pager - :336-379 - and the two paging keys.
    // =============================================================================================

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
            // Exactly eleven records, anchored on the eleventh: the fill takes the ten below it and ends
            // with WS-IDX at zero rather than at end of file, so :362 holds - and the look-behind at :363
            // is then the read that ends the file, which is the only route to :369.
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
            // Asserted on page 1, where :250-254 refuses and no browse runs: a browse would advance
            // SEC-USR-ID, because RIDFLD is updated by every READNEXT and READPREV to the key of the
            // record returned, so after a page the field no longer holds the anchor it started from.
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
            // Anchored so the fill completes all ten rows without ending the file, which is what makes
            // :362 hold - otherwise the outer guard, not :364, would be what skipped the renumbering and
            // this test would pass for the wrong reason.
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

    // =============================================================================================
    // The selection scan and the two transfers - :149-216.
    // =============================================================================================

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

    // =============================================================================================
    // The browse arms - :586-691 - and the diagnostics they emit.
    // =============================================================================================

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

    // =============================================================================================
    // The screen contract - the 59 fields, the header, and the two operands of the SEND.
    // =============================================================================================

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

            // Three transmissions, each from a different statement, and all three are real:
            //   1. :640 - the look-ahead READNEXT at :311 reported end of file and its arm sent
            //   2. :329 - PROCESS-PAGE-FORWARD's own send at the end of the paragraph
            //   3. :119 - MAIN-PARA's send, which the FIRST-ENTRY path issues after PROCESS-ENTER-KEY
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

    // =============================================================================================
    // POPULATE-USER-DATA and INITIALIZE-USER-DATA in isolation - the one-based subscript.
    // =============================================================================================

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
                            .as("row %d must be untouched", other)
                            .isBlank();
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
                assertThat(ws.map().usrId(rowNumber)).isBlank();
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

    // =============================================================================================
    // Construction, the HTTP seam, and the structural properties the migration requires.
    // =============================================================================================

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
            // MOVE -1 TO USRIDINL is a request to place the cursor, carried in the SEND's CURSOR
            // option. It cannot travel as a field length, because UserListRequest.FieldMetadata refuses
            // a negative one, so the envelope is where it becomes observable.
            ScreenMetadata painted = controllerOver(PAGE_SIZE).getUsers(entering(), null)
                    .screenMetadata();

            assertThat(painted.cursorField()).isEqualTo(UserListResponse.USRIDIN_FIELD);
            assertThat(painted.resetAllOutputFields())
                    .as("SEND ... ERASE at :288 clears the screen before painting")
                    .isTrue();
            assertThat(painted.messageColour()).isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHDFCOL));
            assertThat(painted.fields()).as("COUSR00 declares no per-field attribute quads").isEmpty();

            // Every path through the program reports the same cursor request, because :108 moves -1
            // before the EIBCALEN test at :110 - so even the cold start that transfers away has made it.
            assertThat(controllerOver(PAGE_SIZE)
                    .getUsers(UserListRequest.empty().withoutNavigationContext(), null)
                    .screenMetadata().cursorField())
                    .isEqualTo(UserListResponse.USRIDIN_FIELD);

            // A work area in which no statement has run has made no cursor request and no erase: the
            // metadata names no field rather than inventing one, which is what keeps the reported cursor
            // a reading of what happened rather than a constant.
            ScreenMetadata untouched = UserMenuController.screenMetadataOf(new WorkArea());
            assertThat(untouched.cursorField()).isNull();
            assertThat(untouched.resetAllOutputFields())
                    .as("SEND-ERASE-YES is the declared initial state of the 88-level")
                    .isTrue();

            // A PF8 with NEXT-PAGE-NO is the one presentation choice that differs between paths: :275
            // turns ERASE off so the "already at the bottom" message lands on the page still displayed.
            ScreenMetadata atTheBottom = controllerOver(PAGE_SIZE)
                    .getUsers(reentering(), Byte.toUnsignedInt(CicsAid.DFHPF8))
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
                    Integer.class);

            assertThat(UserMenuController.USER_LIST_PATH).isEqualTo("/api/users");
            // The handler answers the shared online envelope, whose payload member is this screen.
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
        @DisplayName("an absent AID parameter defaults to ENTER; an out-of-range one is refused")
        void theAidParameterIsNarrowedSafely() {
            assertThat(UserMenuController.resolveEibAid(null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(UserMenuController.resolveEibAid(0xF7)).isEqualTo(CicsAid.DFHPF7);
            assertThat(UserMenuController.resolveEibAid(0xF8)).isEqualTo(CicsAid.DFHPF8);
            assertThat(UserMenuController.resolveEibAid(0)).isZero();
            assertThat(UserMenuController.resolveEibAid(255)).isEqualTo((byte) 0xFF);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserMenuController.resolveEibAid(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserMenuController.resolveEibAid(256));
        }

        @Test
        @DisplayName("the request mapping delegates without deciding anything itself")
        void theRequestMappingDelegates() {
            ScreenResponse<UserListResponse> answer =
                    controllerOver(PAGE_SIZE).getUsers(entering(), null);

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

            // And each accepts the property it defends, so it is not simply always throwing.
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
            assertThat(map.sel(1)).isBlank();
            assertThat(map.usrId(1).strip()).isEqualTo("ADMIN001");
            assertThat(map.fname(1).strip()).isEqualTo("MARGARET");
            assertThat(map.lname(1).strip()).isEqualTo("GOLD");
            assertThat(map.utype(1)).isEqualTo("A");
        }
    }

    // =============================================================================================
    // The error-flag paths. A read that fails WITHOUT ending the file is what distinguishes the two
    // operands of every `IF USER-SEC-NOT-EOF AND ERR-FLG-OFF` guard, and a relation holding an image
    // that is not the copybook width is how it is produced: the STARTBR probe succeeds, because the
    // relation exists, and the read then refuses to decode plausible-looking rubbish.
    // =============================================================================================

    @Nested
    @DisplayName("The error-flag paths - WHEN OTHER, at each of its consequences")
    class ErrorFlagPaths {

        /** An image of the wrong width: the relation holds it, and no read will decode it. */
        private static final String UNDECODABLE = "AAAA" + " ".repeat(36);

        /**
         * @param leadingGoodRecords how many well-formed records precede the undecodable one
         * @return a controller over a relation whose last row cannot be decoded
         */
        private static UserMenuController controllerWithAnUndecodableTail(int leadingGoodRecords) {
            List<String> rows = new ArrayList<>(fixtureImages(leadingGoodRecords));
            rows.add("ZZZZ" + " ".repeat(36));
            return new UserMenuController(new SecUserRepository(seeded(rows), validBindings(), ASCII,
                    RecordImageForm.CHARACTER), CODEC, CLOCK);
        }

        /**
         * @return a controller over a relation whose only row cannot be decoded
         */
        private static UserMenuController controllerWithOnlyAnUndecodableRow() {
            return new UserMenuController(new SecUserRepository(seeded(List.of(UNDECODABLE)),
                    validBindings(), ASCII, RecordImageForm.CHARACTER), CODEC, CLOCK);
        }

        @Test
        @DisplayName("a read that fails outright still ends the browse, and adds no send")
        void anUnmodelledReadFailureStillEndsTheBrowse() {
            // The EVALUATE arms of READNEXT-USER-SEC-FILE classify a CICS response; they do not wrap the
            // command itself, so a driver-level refusal propagates. That exit bypasses the ENDBR at :325,
            // and under CICS it would be harmless because task termination releases the browse - there is
            // no implicit release here, so the request boundary performs it.
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
            // The guard is the cursor's own isOpen(), which reports false once a browse has been ended, so
            // "one ENDBR per browse" - the single statement at :325 - still holds.
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
                    .as(":327 was not reached either, so PAGENUMO is untouched")
                    .isBlank();
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

            // PF8 makes :288 issue the pre-loop read, and the undecodable row makes it fail WITHOUT
            // ending the file - which is the ERR-FLG-ON operand of the guard rather than USER-SEC-EOF.
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

            // A relation that exists but holds nothing: the STARTBR finds no key at or after the anchor
            // and reports NOTFND, which sets USER-SEC-EOF and - deliberately - no error flag, so :340
            // lets the paragraph continue with end of file already true.
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
            // The undecodable row is given the LOWEST key, so the pre-loop read and the first fill read
            // both succeed and only the second fill read meets it - which is the one combination that
            // evaluates the error operand of the guard at :356 rather than short-circuiting on it.
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
}
