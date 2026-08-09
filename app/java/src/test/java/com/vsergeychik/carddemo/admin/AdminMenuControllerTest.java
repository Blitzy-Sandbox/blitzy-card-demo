package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests for {@link AdminMenuController}, the HTTP adapter over {@code app/cbl/COADM01C.cbl}
 * (CSD transaction {@code CA00}).
 *
 * <h2>Why this class exists, given the controller makes no decisions</h2>
 *
 * <p>{@link AdminMenuService} carries every branch of the program and is tested directly in
 * {@code AdminMenuServiceTest}. What is left here is nonetheless observable behaviour that no service
 * test can see: which HTTP method and path the screen is reached at, what an absent body is taken to
 * mean, how the twenty {@code COADM1AO} items are projected, and - the reason this file was added -
 * that the two presentation values {@code COADM01C} sets which are <em>not</em> {@code DFHMDF} fields
 * actually reach the client.
 *
 * <p>Those two are {@code ERRMSGC OF COADM1AO}, the message colour, which the mapset declares
 * {@code COLOR=RED} and line 148 overrides with {@link BmsAttributes#DFHGREEN} on the coming-soon path,
 * and the {@code MOVE LOW-VALUES TO COADM1AO} repaint signal at line 89. Both are members of
 * {@link AdminMenuResponse} and both are {@code @JsonIgnore}, so before the shared
 * {@link ScreenResponse} envelope existed neither travelled at all - the response documented a wire
 * format it did not have. They are asserted below as members of {@code screenMetadata}, which is where
 * the Agent Action Plan's section 0.3.9 puts metadata: separate from the 1:1 field projection, and
 * present.
 *
 * <p>Plain JUnit 5 throughout, with {@code MockMvc} used only where the assertion is about the wire.
 */
@DisplayName("AdminMenuController - GET /api/admin/menu, CSD transaction CA00, program COADM01C")
class AdminMenuControllerTest {

    /** A pinned instant, so the date and time header is assertable byte for byte. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-08-22T17:02:44Z"), ZoneOffset.UTC);

    /** The dataset catalogue the service needs, holding only the {@code USRSEC} entry it reads. */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AdminMenuService.USRSEC_DATASET_KEY,
                new DatasetBinding("CARDDEMO.TEST.USRSEC",
                        DatasetBinding.KSDS,
                        false,
                        "FB",
                        null,
                        SecUserRecord.RECORD_LENGTH,
                        "CSUSR01Y",
                        SecUserRecord.KEY_LENGTH,
                        null,
                        null,
                        null));
        return catalogue;
    }

    private static AdminMenuController controller() {
        return new AdminMenuController(new AdminMenuService(bindings()), FIXED_CLOCK);
    }

    /** A payload continuing the pseudo-conversation with the given option typed. */
    private static AdminMenuRequest reentry(String option) {
        AdminMenuRequest blank = blankScreen();
        return new AdminMenuRequest(blank.trnName(), blank.title01(), blank.curDate(),
                blank.pgmName(), blank.title02(), blank.curTime(),
                blank.optn001(), blank.optn002(), blank.optn003(), blank.optn004(),
                blank.optn005(), blank.optn006(), blank.optn007(), blank.optn008(),
                blank.optn009(), blank.optn010(), blank.optn011(), blank.optn012(),
                option, blank.errMsg(),
                NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER);
    }

    /** A first entry: a communication area is present and its context is {@code ENTER}. */
    private static AdminMenuRequest firstEntry() {
        AdminMenuRequest reentered = reentry("  ");
        return new AdminMenuRequest(reentered.trnName(), reentered.title01(), reentered.curDate(),
                reentered.pgmName(), reentered.title02(), reentered.curTime(),
                reentered.optn001(), reentered.optn002(), reentered.optn003(), reentered.optn004(),
                reentered.optn005(), reentered.optn006(), reentered.optn007(), reentered.optn008(),
                reentered.optn009(), reentered.optn010(), reentered.optn011(), reentered.optn012(),
                reentered.option(), reentered.errMsg(),
                NavigationContext.empty().withPgmEnter(), CicsAid.DFHENTER);
    }

    /** Every one of the twenty items space-filled to its declared width, as a {@code PIC X} is. */
    private static AdminMenuRequest blankScreen() {
        String lines = " ".repeat(AdminMenuRequest.OPTION_LINE_LENGTH);
        return new AdminMenuRequest(" ".repeat(AdminMenuRequest.TRN_NAME_LENGTH),
                " ".repeat(AdminMenuRequest.TITLE_LENGTH),
                " ".repeat(AdminMenuRequest.CUR_DATE_LENGTH),
                " ".repeat(AdminMenuRequest.PGM_NAME_LENGTH),
                " ".repeat(AdminMenuRequest.TITLE_LENGTH),
                " ".repeat(AdminMenuRequest.CUR_TIME_LENGTH),
                lines, lines, lines, lines, lines, lines, lines, lines, lines, lines, lines, lines,
                " ".repeat(AdminMenuRequest.OPTION_LENGTH),
                " ".repeat(AdminMenuRequest.ERR_MSG_LENGTH),
                NavigationContext.empty().withPgmReenter(),
                CicsAid.DFHENTER);
    }

    // =================================================================================================
    // Construction
    // =================================================================================================

    @Nested
    @DisplayName("Construction - two collaborators, both required, and no state of its own")
    class Construction {

        @Test
        @DisplayName("both arguments are required, and the message says what each is for")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuController(null, FIXED_CLOCK))
                    .withMessageContaining("AdminMenuService");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuController(new AdminMenuService(bindings()), null))
                    .withMessageContaining("Clock");
        }

        @Test
        @DisplayName("two requests through one controller cannot see each other's screen (gate G37)")
        void twoRequestsShareNothing() {
            AdminMenuController shared = controller();

            AdminMenuResponse first = shared.getAdminMenu(reentry("99")).screen();
            AdminMenuResponse second = shared.getAdminMenu(firstEntry()).screen();

            assertThat(first.errMsg()).isNotBlank();
            assertThat(second.errMsg())
                    .as("a first entry paints without a message, whatever the previous call reported")
                    .isBlank();
        }
    }

    // =================================================================================================
    // The route, and what an absent body means
    // =================================================================================================

    @Nested
    @DisplayName("The route - GET /api/admin/menu, and the cold start it answers")
    class TheRoute {

        @Test
        @DisplayName("the mapping is a GET on the path the AAP assigns transaction CA00")
        void theMappingIsTheAssignedOne() throws NoSuchMethodException {
            assertThat(AdminMenuController.ADMIN_MENU_PATH).isEqualTo("/api/admin/menu");

            Method handler = AdminMenuController.class
                    .getMethod("getAdminMenu", AdminMenuRequest.class);
            org.springframework.web.bind.annotation.GetMapping mapping = handler
                    .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly(AdminMenuController.ADMIN_MENU_PATH);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);

            // The handler answers the shared online envelope, whose payload member is this screen.
            assertThat(handler.getReturnType()).isEqualTo(ScreenResponse.class);
            assertThat(((ParameterizedType) handler.getGenericReturnType()).getActualTypeArguments())
                    .containsExactly(AdminMenuResponse.class);
        }

        @Test
        @DisplayName("an absent body is EIBCALEN = 0 and diverts to the sign-on screen - L82-84")
        void anAbsentBodyIsTheColdStart() {
            ScreenResponse<AdminMenuResponse> answer = controller().getAdminMenu(null);

            assertThat(answer.screen().nextProgram().strip()).isEqualTo("COSGN00C");
            assertThat(answer.screen().navigationContext()).isNotNull();
        }

        @Test
        @DisplayName("showAdminMenu refuses a null payload: the absent body has its own representation")
        void theSeamRefusesNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> controller().showAdminMenu(null))
                    .withMessageContaining("cold-start request");
        }

        @Test
        @DisplayName("toInput passes the three values the program reads through untouched")
        void toInputPassesTheThreeValuesThrough() {
            AdminMenuController controller = controller();
            AdminMenuRequest received = reentry("1 ");

            AdminMenuInput input = controller.toInput(received);

            assertThat(input.navigationContext()).isSameAs(received.navigationContext());
            assertThat(input.eibAid()).isEqualTo(CicsAid.DFHENTER);
            assertThat(input.option())
                    .as("unnormalised, spaces and all - the service reproduces the scan")
                    .isEqualTo("1 ");
        }
    }

    // =================================================================================================
    // The projection, and the metadata that had no way to travel
    // =================================================================================================

    @Nested
    @DisplayName("The projection - twenty screen fields, and the metadata beside them")
    class TheProjection {

        @Test
        @DisplayName("a first entry paints the header, the twelve lines and this screen's own triple")
        void aFirstEntryPaintsTheScreen() {
            AdminMenuResponse painted = controller().getAdminMenu(firstEntry()).screen();

            assertThat(painted.trnName()).isEqualTo(AdminMenuResponse.TRANSACTION_ID);
            assertThat(painted.pgmName()).isEqualTo(AdminMenuResponse.PROGRAM_NAME);
            assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(painted.curDate()).isEqualTo("08/22/22");
            assertThat(painted.curTime()).isEqualTo("17:02:44");
            assertThat(painted.optionLines()).hasSize(AdminMenuResponse.OPTION_LINE_COUNT);
            assertThat(painted.optn001()).isNotBlank();
            assertThat(painted.nextProgram()).isBlank();
            assertThat(painted.nextMapset()).isEqualTo(AdminMenuResponse.MAPSET_NAME);
            assertThat(painted.nextMap()).isEqualTo(AdminMenuResponse.MAP_NAME);
        }

        @Test
        @DisplayName("a transfer names the successor and states no map - L143 and L166 name none")
        void aTransferNamesTheSuccessorAndNoMap() {
            AdminMenuResponse transferred = controller().getAdminMenu(reentry("01")).screen();

            assertThat(transferred.nextProgram()).isNotBlank();
            assertThat(transferred.nextMapset()).isBlank()
                    .hasSize(AdminMenuResponse.NEXT_MAPSET_LENGTH);
            assertThat(transferred.nextMap()).isBlank().hasSize(AdminMenuResponse.NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("the repaint signal and the message colour travel in the envelope, not the payload")
        void theMetadataTravelsInTheEnvelope() {
            ScreenResponse<AdminMenuResponse> firstEntry = controller().getAdminMenu(firstEntry());

            ScreenMetadata metadata = firstEntry.screenMetadata();
            assertThat(metadata.resetAllOutputFields())
                    .as("MOVE LOW-VALUES TO COADM1AO at :89 is the first-entry path alone")
                    .isTrue();
            assertThat(metadata.messageColour())
                    .as("published unsigned: DFHRED is 0xF2, which as a byte would read -14")
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHRED));
            assertThat(metadata.fields())
                    .as("COADM01.CPY declares no per-field attribute quads, so the map is accurately "
                            + "empty rather than missing")
                    .isEmpty();
            assertThat(metadata.cursorField())
                    .as("COADM01C contains no MOVE -1 TO <field>L, so it makes no cursor request")
                    .isNull();

            // A re-entry sends without ERASE, and the same envelope reports that.
            assertThat(controller().getAdminMenu(reentry("99")).screenMetadata()
                    .resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("the response's own projection agrees with what the handler publishes")
        void theResponseProjectionAgreesWithTheHandler() {
            ScreenResponse<AdminMenuResponse> answer = controller().getAdminMenu(firstEntry());

            assertThat(answer.screenMetadata()).isEqualTo(answer.screen().screenMetadata());
        }

        @Test
        @DisplayName("the outcome members the screen has no field for are not projected onto it")
        void theUnprojectedOutcomeMembersStayOff() {
            AdminMenuController controller = controller();
            AdminMenuOutcome outcome = new AdminMenuService(bindings())
                    .handle(new AdminMenuInput(NavigationContext.empty().withPgmReenter(),
                            CicsAid.DFHENTER, "99"));

            AdminMenuResponse projected = controller.toResponse(outcome);

            assertThat(outcome.errorFlag()).isTrue();
            assertThat(projected.errMsg()).isNotBlank();
            assertThat(projected.messageColour()).isEqualTo(outcome.messageColour());
            assertThat(projected.resetAllOutputFields()).isEqualTo(outcome.resetAllOutputFields());
        }
    }

    // =================================================================================================
    // The wire
    // =================================================================================================

    @Nested
    @DisplayName("The wire - the screen stays flat and the metadata is one sibling member")
    class TheWire {

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller()).build();
        }

        @Test
        @DisplayName("GET with no body answers the cold start over HTTP")
        void coldStartOverHttp() throws Exception {
            mockMvc().perform(get(AdminMenuController.ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"));
        }

        @Test
        @DisplayName("the twenty items stay at the top level and screenMetadata joins them")
        void theEnvelopeLeavesTheScreenFlat() throws Exception {
            String body = new ObjectMapper().writeValueAsString(firstEntry());

            mockMvc().perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnName").value(AdminMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.optn001").exists())
                    .andExpect(jsonPath("$.nextMapset").value(AdminMenuResponse.MAPSET_NAME))
                    // The two metadata values now reach the client, which is the whole correction.
                    .andExpect(jsonPath("$.screenMetadata.resetAllOutputFields").value(true))
                    .andExpect(jsonPath("$.screenMetadata.messageColour")
                            .value(Byte.toUnsignedInt(BmsAttributes.DFHRED)))
                    // And they are still not members of the screen itself, because neither is a DFHMDF
                    // field: separate from the 1:1 projection, and present.
                    .andExpect(jsonPath("$.messageColour").doesNotExist())
                    .andExpect(jsonPath("$.resetAllOutputFields").doesNotExist())
                    .andExpect(jsonPath("$.screen").doesNotExist());
        }
    }
}
