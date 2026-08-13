package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.AdminMenuService.ReceiveOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The Spring MVC slice test for {@link AdminMenuController} - CSD transaction {@code CA00}, program
 * {@code app/cbl/COADM01C.cbl}, projected onto the single endpoint {@code GET /api/admin/menu}.
 */
@DisplayName("AdminMenuController - GET /api/admin/menu, CSD transaction CA00, program COADM01C")
class AdminMenuControllerTest {
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(MESSAGE_CHARSET);

    private static final String USER_LIST_PROGRAM = "COUSR00C";

    private static final String SIGNON_PROGRAM = "COSGN00C";

    private static final String SPACE = " ";

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

    private static AdminMenuService stubbedService() {
        AdminMenuService stub = mock(AdminMenuService.class);
        when(stub.codec()).thenReturn(CODEC);
        return stub;
    }

    private static AdminMenuController controllerOver(final AdminMenuService service) {
        return new AdminMenuController(service, FIXED_CLOCK);
    }

    private static List<String> blankOptionLines() {
        List<String> lines = new ArrayList<>(AdminMenuResponse.OPTION_LINE_COUNT);
        for (int slot = 1; slot <= AdminMenuResponse.OPTION_LINE_COUNT; slot++) {
            lines.add(CODEC.movePicX(SPACE, AdminMenuResponse.OPTION_LINE_LENGTH));
        }
        return lines;
    }

    private static List<String> paintedOptionLines() {
        List<String> lines = blankOptionLines();
        lines.set(0, CODEC.movePicX("01. User List (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(1, CODEC.movePicX("02. User Add (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(2, CODEC.movePicX("03. User Update (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(3, CODEC.movePicX("04. User Delete (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        return lines;
    }

    private static AdminMenuOutcome outcome(final List<String> optionLines,
            final String message80,
            final byte colour,
            final String nextProgram,
            final boolean reset,
            final NavigationContext context) {
        boolean transferring = !nextProgram.isBlank();
        return new AdminMenuOutcome(optionLines,
                message80,
                colour,
                !message80.isBlank(),
                CODEC.movePicX(SPACE, AdminMenuService.OPTION_LENGTH),
                CODEC.movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH),
                transferring,
                !transferring,
                reset,
                context,
                AdminMenuService.TRANSACTION_ID,
                transferring ? CODEC.movePicX(SPACE, AdminMenuResponse.NEXT_MAPSET_LENGTH)
                        : AdminMenuService.MAPSET_NAME,
                transferring ? CODEC.movePicX(SPACE, AdminMenuResponse.NEXT_MAP_LENGTH)
                        : AdminMenuService.MAP_NAME,
                transferring ? ReceiveOutcome.NORMAL : ReceiveOutcome.NOT_PERFORMED);
    }

    private static AdminMenuOutcome paintedOutcome(final NavigationContext context) {
        return outcome(paintedOptionLines(),
                CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                AdminMenuService.MAP_MESSAGE_COLOUR,
                SPACE,
                true,
                context);
    }

    private static AdminMenuOutcome reportingOutcome(final String message, final byte colour) {
        return outcome(paintedOptionLines(),
                CODEC.movePicX(message, AdminMenuService.MESSAGE_LENGTH),
                colour,
                SPACE,
                false,
                NavigationContext.empty().withPgmReenter());
    }

    private static AdminMenuOutcome signOnTransferOutcome(final NavigationContext context) {
        return new AdminMenuOutcome(paintedOptionLines(),
                CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                AdminMenuService.MAP_MESSAGE_COLOUR,
                false,
                CODEC.movePicX(SPACE, AdminMenuService.OPTION_LENGTH),
                CODEC.movePicX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH),
                false,
                false,
                false,
                context,
                AdminMenuService.TRANSACTION_ID,
                CODEC.movePicX(SPACE, AdminMenuResponse.NEXT_MAPSET_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuResponse.NEXT_MAP_LENGTH),
                ReceiveOutcome.NORMAL);
    }

    private static AdminMenuOutcome optionTransferOutcome(final String nextProgram,
            final NavigationContext context) {
        return outcome(paintedOptionLines(),
                CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                AdminMenuService.MAP_MESSAGE_COLOUR,
                nextProgram,
                false,
                context);
    }

    private static ScreenResponse<AdminMenuResponse> answerFor(final AdminMenuOutcome outcome,
            final AdminMenuRequest request) {
        AdminMenuService service = stubbedService();
        doReturn(outcome).when(service).handle(any(AdminMenuInput.class));
        return controllerOver(service).getAdminMenu(request);
    }

    private static ObjectMapper productionMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new com.vsergeychik.carddemo.config.WebConfig()
                .carddemoJacksonCustomizer(MESSAGE_CHARSET).customize(builder);
        return builder.build();
    }

    private static AdminMenuRequest blankScreen(final NavigationContext context, final byte aid) {
        String optionLine = CODEC.movePicX(SPACE, AdminMenuRequest.OPTION_LINE_LENGTH);
        return new AdminMenuRequest(CODEC.movePicX(SPACE, AdminMenuRequest.TRN_NAME_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.TITLE_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.CUR_DATE_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.PGM_NAME_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.TITLE_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.CUR_TIME_LENGTH),
                optionLine, optionLine, optionLine, optionLine, optionLine, optionLine,
                optionLine, optionLine, optionLine, optionLine, optionLine, optionLine,
                CODEC.movePicX(SPACE, AdminMenuRequest.OPTION_LENGTH),
                CODEC.movePicX(SPACE, AdminMenuRequest.ERR_MSG_LENGTH),
                context,
                aid);
    }

    private static ScreenResponse<AdminMenuResponse> answerFor(final AdminMenuOutcome outcome) {
        AdminMenuService service = stubbedService();
        doReturn(outcome).when(service).handle(any(AdminMenuInput.class));
        return controllerOver(service).getAdminMenu(
                blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));
    }

    private static AdminMenuResponse paint() {
        return answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter())).screen();
    }

    private static AdminMenuRequest withOption(final String option, final NavigationContext context) {
        AdminMenuRequest blank = blankScreen(context, CicsAid.DFHENTER);
        return new AdminMenuRequest(blank.trnName(), blank.title01(), blank.curDate(),
                blank.pgmName(), blank.title02(), blank.curTime(),
                blank.optn001(), blank.optn002(), blank.optn003(), blank.optn004(),
                blank.optn005(), blank.optn006(), blank.optn007(), blank.optn008(),
                blank.optn009(), blank.optn010(), blank.optn011(), blank.optn012(),
                option, blank.errMsg(), context, blank.eibAid());
    }

    @Nested
    @DisplayName("Construction - two collaborators, both required, and no state of its own")
    class Construction {
        @Test
        @DisplayName("both arguments are required, and each message says what it is for")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuController(null, FIXED_CLOCK))
                    .withMessageContaining("AdminMenuService");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuController(stubbedService(), null))
                    .withMessageContaining("Clock");
        }

        @Test
        @DisplayName("the codec comes from the service, so the package makes one code-page decision")
        void theCodecIsTheServicesOwn() {
            AdminMenuService service = stubbedService();

            controllerOver(service);

            verify(service).codec();
        }

        @Test
        @DisplayName("G37: two requests through one controller cannot see each other's screen")
        void twoRequestsShareNothing() {
            AdminMenuService service = stubbedService();
            AdminMenuController shared = controllerOver(service);
            doReturn(reportingOutcome(AdminMenuService.INVALID_OPTION_MESSAGE,
                    AdminMenuService.MAP_MESSAGE_COLOUR))
                    .doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            AdminMenuResponse first =
                    shared.getAdminMenu(withOption("99", NavigationContext.empty()
                            .withPgmReenter())).screen();
            AdminMenuResponse second =
                    shared.getAdminMenu(withOption(SPACE.repeat(AdminMenuRequest.OPTION_LENGTH),
                            NavigationContext.empty().withPgmEnter())).screen();

            assertThat(first.errMsg()).isNotBlank();
            assertThat(second.errMsg())
                    .as("the second answer carries nothing of the first: no field, no flag, no message")
                    .isBlank()
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(second.screenMetadata().messageColour())
                    .isEqualTo(first.screenMetadata().messageColour());
        }
    }

    @Nested
    @DisplayName("The handler - one GET, one path, and EIBCALEN = 0 for an absent body")
    class TheHandlerContract {
        @Test
        @DisplayName("the mapping is the GET the plan assigns to transaction CA00")
        void theMappingIsTheAssignedOne() throws NoSuchMethodException {
            assertThat(AdminMenuController.ADMIN_MENU_PATH).isEqualTo("/api/admin/menu");

            Method handler = handlerMethod();
            GetMapping mapping = handler.getAnnotation(GetMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly(AdminMenuController.ADMIN_MENU_PATH);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        }

        @Test
        @DisplayName("it answers the shared online envelope over this screen's payload")
        void itAnswersTheSharedEnvelope() throws NoSuchMethodException {
            Method handler = handlerMethod();

            assertThat(handler.getReturnType()).isEqualTo(ScreenResponse.class);
            assertThat(((ParameterizedType) handler.getGenericReturnType()).getActualTypeArguments())
                    .containsExactly(AdminMenuResponse.class);
        }

        @Test
        @DisplayName("the body is optional and validated: an absent one is EIBCALEN = 0 - L82")
        void theBodyIsOptionalAndValidated() throws NoSuchMethodException {
            Parameter payload = handlerMethod().getParameters()[0];

            RequestBody body = payload.getAnnotation(RequestBody.class);
            assertThat(body).isNotNull();
            assertThat(body.required())
                    .as("app/cbl/COADM01C.cbl:82 tests IF EIBCALEN = 0, so no payload is a legal call")
                    .isFalse();
            assertThat(payload.getAnnotation(Valid.class))
                    .as("the twenty @Size maxima come from the symbolic map, and MVC must enforce them")
                    .isNotNull();
        }

        @Test
        @DisplayName("an absent body reaches the service with no communication area and nothing sent")
        void anAbsentBodyBecomesTheColdStart() {
            AdminMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));
            ArgumentCaptor<AdminMenuInput> captured = ArgumentCaptor.forClass(AdminMenuInput.class);

            controllerOver(service).getAdminMenu(null);

            verify(service).handle(captured.capture());
            assertThat(captured.getValue().isCommareaPresent())
                    .as("a null communication area is how EIBCALEN = 0 is encoded")
                    .isFalse();
            assertThat(captured.getValue().option())
                    .as("nothing arrived, so nothing was sent for OPTIONI, and RECEIVE MAP leaves a field "
                            + "the terminal did not send at LOW-VALUES")
                    .isEqualTo(ScreenFieldImage.unpainted(AdminMenuRequest.OPTION_LENGTH));
        }

        @Test
        @DisplayName("the projection seam refuses null: the absent body has its own representation")
        void theSeamRefusesNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> controllerOver(stubbedService()).showAdminMenu(null))
                    .withMessageContaining("cold-start request");
        }

        @Test
        @DisplayName("toInput passes the three values COADM01C reads through untouched")
        void toInputPassesTheThreeValuesThrough() {
            NavigationContext inbound = NavigationContext.empty().withPgmReenter();
            AdminMenuRequest received = withOption("1 ", inbound);

            AdminMenuInput input = controllerOver(stubbedService()).toInput(received);

            assertThat(input.navigationContext()).isSameAs(inbound);
            assertThat(input.eibAid()).isEqualTo(CicsAid.DFHENTER);
            assertThat(input.option())
                    .as("unnormalised: L117-L123 does the trailing-space scan, and it is the service's")
                    .isEqualTo("1 ");
        }

        private Method handlerMethod() throws NoSuchMethodException {
            return AdminMenuController.class.getMethod("getAdminMenu", AdminMenuRequest.class);
        }
    }

    @Nested
    @DisplayName("The payload contract (G9) - twenty members, at the widths the symbolic map declares")
    class ThePayloadContract {
        @Test
        @DisplayName("twenty named DFHMDF fields of twenty-eight, and twenty payload members")
        void twentyOfTwentyEightFieldsTravel() {
            assertThat(AdminMenuResponse.MAPSET_FIELD_COUNT).isEqualTo(28);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT).isEqualTo(8);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT)
                    .isEqualTo(AdminMenuRequest.MAPPED_FIELD_COUNT)
                    .isEqualTo(AdminMenuResponse.PAYLOAD_FIELDS.size())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("the widths are the xxxI PICTURE clauses, in map order (B8: named constants only)")
        void theWidthsAreTheDeclaredOnes() {
            List<Integer> expected = new ArrayList<>(List.of(AdminMenuRequest.TRN_NAME_LENGTH,
                    AdminMenuRequest.TITLE_LENGTH,
                    AdminMenuRequest.CUR_DATE_LENGTH,
                    AdminMenuRequest.PGM_NAME_LENGTH,
                    AdminMenuRequest.TITLE_LENGTH,
                    AdminMenuRequest.CUR_TIME_LENGTH));
            for (int slot = 1; slot <= AdminMenuRequest.OPTION_LINE_COUNT; slot++) {
                expected.add(AdminMenuRequest.OPTION_LINE_LENGTH);
            }
            expected.add(AdminMenuRequest.OPTION_LENGTH);
            expected.add(AdminMenuRequest.ERR_MSG_LENGTH);

            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS).isEqualTo(expected);
            assertThat(AdminMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuRequest.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("the rendered screen is exactly those widths, member by member")
        void theRenderedScreenIsExactlyThoseWidths() {
            AdminMenuResponse painted = paint();

            assertThat(painted.trnName()).hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(painted.title01()).hasSize(AdminMenuResponse.TITLE_LENGTH);
            assertThat(painted.curDate()).hasSize(AdminMenuResponse.CUR_DATE_LENGTH);
            assertThat(painted.pgmName()).hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
            assertThat(painted.title02()).hasSize(AdminMenuResponse.TITLE_LENGTH);
            assertThat(painted.curTime()).hasSize(AdminMenuResponse.CUR_TIME_LENGTH);
            assertThat(painted.option()).hasSize(AdminMenuResponse.OPTION_LENGTH);
            assertThat(painted.errMsg()).hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(painted.optionLines())
                    .hasSize(AdminMenuResponse.OPTION_LINE_COUNT)
                    .allSatisfy(line ->
                            assertThat(line).hasSize(AdminMenuResponse.OPTION_LINE_LENGTH));
        }

        @ParameterizedTest(name = "OPTN0{0,number,00}O is carried")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("B5: all twelve option slots are carried, including the two nothing can write")
        void allTwelveSlotsAreCarried(final int slot) {
            AdminMenuResponse painted = paint();

            assertThat(painted.optionLine(slot))
                    .as("app/cpy-bms/COADM01.CPY declares OPTN001I to OPTN012I at :60 to :126")
                    .isNotNull()
                    .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH);
        }

        @Test
        @DisplayName("B5: four lines are written, eight stay blank, and 11 and 12 have no EVALUATE arm")
        void theUnwritableSlotsStayPresentAndBlank() {
            AdminMenuResponse painted = paint();

            assertThat(painted.optionLines().subList(0, 4)).noneMatch(String::isBlank);
            assertThat(painted.optionLines().subList(4, AdminMenuResponse.OPTION_LINE_COUNT))
                    .as("slots 5-10 have an EVALUATE arm but no data; 11-12 have no arm at all "
                            + "(app/cbl/COADM01C.cbl:238-261). Both stay present and blank.")
                    .allMatch(String::isBlank);
            assertThat(painted.optionLine(11)).isBlank();
            assertThat(painted.optionLine(12)).isBlank();
        }

        @Test
        @DisplayName("G9: no record component is an xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item")
        void noMetadataItemIsAMember() {
            List<String> members = new ArrayList<>();
            for (RecordComponent component : AdminMenuResponse.class.getRecordComponents()) {
                members.add(component.getName());
            }

            assertThat(members).containsAll(AdminMenuResponse.PAYLOAD_FIELDS.stream()
                    .map(this::memberNameOf)
                    .toList());
            assertThat(members)
                    .as("the length, flag and attribute items are validation and highlight metadata: "
                            + "app/cpy-bms/COADM01.CPY declares xxxL COMP PIC S9(4), xxxF PICTURE X and "
                            + "its xxxA REDEFINES on the input side, and xxxC/xxxP/xxxH/xxxV on the "
                            + "output side at :139-:250. None is a payload member.")
                    .doesNotContain("optionL", "optionF", "optionA", "errMsgL", "errMsgF", "errMsgA",
                            "errMsgC", "errMsgP", "errMsgH", "errMsgV", "trnNameC", "title01A");
        }

        @Test
        @DisplayName("the message colour travels as metadata beside the screen, not as an errMsgC field")
        void theColourTravelsAsMetadata() {
            ScreenResponse<AdminMenuResponse> answer = answerFor(paintedOutcome(
                    NavigationContext.empty().withPgmReenter()));

            ScreenMetadata metadata = answer.screenMetadata();
            assertThat(metadata.messageColour())
                    .as("published unsigned, because DFHRED is 0xF2 and a signed byte would read -14")
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            assertThat(metadata.fields())
                    .as("COADM01C copies neither CSSETATY nor CSSTRPFY, so it sets no per-field "
                            + "attribute quad and the map is accurately empty rather than absent")
                    .isEmpty();
            assertThat(metadata.cursorField())
                    .as("the program contains no MOVE -1 TO <field>L, so it requests no cursor")
                    .isNull();
            assertThat(answer.screenMetadata()).isEqualTo(answer.screen().screenMetadata());
        }

        @Test
        @DisplayName("option is the only editable field, so the header the client sends is overwritten")
        void onlyTheOptionIsEditable() {
            AdminMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));
            AdminMenuRequest tampered = new AdminMenuRequest("ZZZZ",
                    "tampered title one                      ",
                    "99/99/99", "ZZZZZZZZ", "tampered title two                      ", "99:99:99",
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    "01", "tampered message", NavigationContext.empty().withPgmReenter(),
                    CicsAid.DFHENTER);

            AdminMenuResponse painted = controllerOver(service).getAdminMenu(tampered).screen();

            assertThat(painted.trnName()).isEqualTo(AdminMenuService.TRANSACTION_ID);
            assertThat(painted.pgmName()).isEqualTo(AdminMenuService.PROGRAM_NAME);
            assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(painted.curDate()).isNotEqualTo("99/99/99");
            assertThat(painted.curTime()).isNotEqualTo("99:99:99");
            assertThat(painted.errMsg()).doesNotContain("tampered");
        }

        private String memberNameOf(final String dfhmdfItem) {
            String withoutSuffix = dfhmdfItem.substring(0, dfhmdfItem.length() - 1);
            return switch (withoutSuffix) {
                case "TRNNAME" -> "trnName";
                case "TITLE01" -> "title01";
                case "CURDATE" -> "curDate";
                case "PGMNAME" -> "pgmName";
                case "TITLE02" -> "title02";
                case "CURTIME" -> "curTime";
                case "OPTION" -> "option";
                case "ERRMSG" -> "errMsg";
                default -> withoutSuffix.toLowerCase(Locale.ROOT);
            };
        }
    }

    @Nested
    @DisplayName("The message image - WS-MESSAGE X(80) into ERRMSGO X(78), truncated on the RIGHT")
    class TheMessageTruncation {
        @Test
        @DisplayName("the two widths disagree by two bytes, and that is the whole point")
        void theTwoWidthsDisagree() {
            assertThat(AdminMenuService.MESSAGE_LENGTH).isEqualTo(80);
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("a short message is right-space-padded to exactly 78, never trimmed")
        void aShortMessageIsPaddedNotTrimmed() {
            String message = AdminMenuService.INVALID_OPTION_MESSAGE;

            AdminMenuResponse reported = answerFor(reportingOutcome(message,
                    AdminMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(reported.errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .isEqualTo(CODEC.movePicX(message, AdminMenuResponse.ERR_MSG_LENGTH))
                    .startsWith(message)
                    .endsWith(SPACE);
            assertThat(reported.errMsg().strip()).isEqualTo(message);
        }

        @Test
        @DisplayName("an eighty-byte image loses exactly its last two bytes - right, not left")
        void anEightyByteImageLosesItsLastTwoBytes() {
            String surviving = "H" + "-".repeat(76) + "T";
            String discarded = "#$";
            String probe = surviving + discarded;
            assertThat(probe).hasSize(AdminMenuService.MESSAGE_LENGTH);

            AdminMenuResponse reported =
                    answerFor(reportingOutcome(probe, AdminMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(reported.errMsg())
                    .as("COBOL fills a PIC X receiver from the left and discards the overflow")
                    .isEqualTo(surviving)
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .startsWith("H")
                    .endsWith("T")
                    .doesNotContain("#")
                    .doesNotContain("$");
            assertThat(reported.errMsg())
                    .as("left truncation would have kept the tail instead")
                    .isNotEqualTo(probe.substring(discarded.length()));
        }

        @Test
        @DisplayName("the codec, not String surgery, does it - and it is handed an explicit charset")
        void theCodecDoesItWithAnExplicitCharset() {
            assertThat(CODEC.charset())
                    .isEqualTo(MESSAGE_CHARSET)
                    .isEqualTo(Charset.forName(AdminMenuService.DEFAULT_MESSAGE_CHARSET_NAME));
            assertThat(CODEC.movePicX("A".repeat(AdminMenuService.MESSAGE_LENGTH),
                    AdminMenuResponse.ERR_MSG_LENGTH))
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("errMsg is exactly 78 characters on every path, blank included")
        void errMsgIsAlwaysSeventyEight() {
            assertThat(paint().errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .isBlank();
            assertThat(answerFor(reportingOutcome(SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuService.MAP_MESSAGE_COLOUR)).screen().errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(answerFor(reportingOutcome(AdminMenuService.COMING_SOON_MESSAGE,
                    AdminMenuService.COMING_SOON_MESSAGE_COLOUR)).screen().errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("the colour the service chose is the colour the envelope publishes")
        void theColourIsCarriedThrough() {
            assertThat(answerFor(reportingOutcome(AdminMenuService.COMING_SOON_MESSAGE,
                    AdminMenuService.COMING_SOON_MESSAGE_COLOUR)).screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHGREEN));
            assertThat(answerFor(reportingOutcome(SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuService.MAP_MESSAGE_COLOUR)).screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
        }
    }

    @Nested
    @DisplayName("The header - the two identity literals, the two titles, and the date and time")
    class TheHeaderFields {
        @Test
        @DisplayName("TRNNAMEO is 'CA00' and PGMNAMEO is 'COADM01C' - L208-L209")
        void theTwoIdentityLiterals() {
            AdminMenuResponse painted = paint();

            assertThat(painted.trnName())
                    .isEqualTo(AdminMenuService.TRANSACTION_ID)
                    .isEqualTo("CA00")
                    .hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(painted.pgmName())
                    .isEqualTo(AdminMenuService.PROGRAM_NAME)
                    .isEqualTo("COADM01C")
                    .hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("B5: the ACTIVE titles travel - not the commented-out decoy at COTTL01Y:21")
        void theActiveTitlesTravel() {
            AdminMenuResponse painted = paint();

            assertThat(painted.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(SPACE)
                    .endsWith(SPACE);
            assertThat(painted.title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CardDemo")
                    .doesNotContain("Credit Card Demo Application");
        }

        @Test
        @DisplayName("the invalid-key text is CCDA-MSG-INVALID-KEY: not either thank-you literal")
        void theInvalidKeyTextIsNotConflated() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);

            AdminMenuResponse reported = answerFor(reportingOutcome(
                    SystemMessages.CCDA_MSG_INVALID_KEY, AdminMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(reported.errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .contains("Invalid key pressed")
                    .doesNotContain("Thank you");
        }

        @Test
        @DisplayName("B7: CURDATEO is MM/DD/YY and CURTIMEO is HH:MM:SS under the fixed clock")
        void theDateAndTimeAreDeterministic() {
            AdminMenuResponse painted = paint();

            assertThat(painted.curDate())
                    .isEqualTo("07/19/22")
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(painted.curTime())
                    .isEqualTo("23:12:32")
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
            assertThat(painted.curDate().charAt(2)).isEqualTo(DateHeader.DATE_SEPARATOR);
            assertThat(painted.curTime().charAt(2)).isEqualTo(DateHeader.TIME_SEPARATOR);
        }

        @Test
        @DisplayName("the header is the shared DateHeader's rendering, read once from the one clock")
        void theHeaderIsTheSharedRendering() {
            DateHeader expected = DateHeader.from(CODEC, FIXED_CLOCK);
            AdminMenuResponse painted = paint();

            assertThat(painted.curDate()).isEqualTo(expected.wsCurdateMmDdYy());
            assertThat(painted.curTime()).isEqualTo(expected.wsCurtimeHhMmSs());
            assertThat(painted.curDate()).endsWith(expected.wsCurdateYy());
        }

        @Test
        @DisplayName("B7: the same request rendered twice is byte-identical, header included")
        void theRenderIsRepeatable() {
            assertThat(paint()).isEqualTo(paint());
        }
    }

    @Nested
    @DisplayName("ENTER versus REENTER (G38, G50) - both contexts carried, and no invented highlight")
    class EnterAndReenter {
        @Test
        @DisplayName("G50: CDEMO-PGM-ENTER and CDEMO-PGM-REENTER are both driven, true and false")
        void bothContextConditionNamesAreDriven() {
            AdminMenuController controller = controllerOver(stubbedService());
            NavigationContext entering = NavigationContext.empty().withPgmEnter();
            NavigationContext reentering = NavigationContext.empty().withPgmReenter();

            AdminMenuInput first = controller.toInput(withOption("  ", entering));
            AdminMenuInput second = controller.toInput(withOption("  ", reentering));

            assertThat(first.navigationContext().isEnter()).isTrue();
            assertThat(first.navigationContext().isReenter()).isFalse();
            assertThat(first.isReenter()).isFalse();
            assertThat(second.navigationContext().isReenter()).isTrue();
            assertThat(second.navigationContext().isEnter()).isFalse();
            assertThat(second.isReenter()).isTrue();
            assertThat(entering.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(reentering.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("G50: both user-type condition names are carried, and neither is interpreted")
        void bothUserTypesAreCarriedUninterpreted() {
            AdminMenuController controller = controllerOver(stubbedService());
            NavigationContext admin = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();
            NavigationContext user = NavigationContext.empty().withPgmReenter().withUserTypeUser();

            assertThat(controller.toInput(withOption("  ", admin)).navigationContext().isAdmin())
                    .isTrue();
            assertThat(controller.toInput(withOption("  ", admin)).navigationContext().isUser())
                    .isFalse();
            assertThat(controller.toInput(withOption("  ", user)).navigationContext().isUser())
                    .isTrue();
            assertThat(controller.toInput(withOption("  ", user)).navigationContext().isAdmin())
                    .isFalse();
        }

        @Test
        @DisplayName("a first entry paints without a message and reports the LOW-VALUES clear - L89")
        void aFirstEntryPaintsWithoutAMessage() {
            AdminMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            ScreenResponse<AdminMenuResponse> answer = controllerOver(service).getAdminMenu(
                    blankScreen(NavigationContext.empty().withPgmEnter(), CicsAid.DFHENTER));

            assertThat(answer.screen().errMsg())
                    .isBlank()
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(answer.screenMetadata().resetAllOutputFields())
                    .as("MOVE LOW-VALUES TO COADM1AO at app/cbl/COADM01C.cbl:89 is the first-entry path")
                    .isTrue();
            assertThat(answer.screen().optn001()).isNotBlank();
        }

        @Test
        @DisplayName("a re-entry may carry a message, and sends without the ERASE-style clear")
        void aReentryMayCarryAMessage() {
            ScreenResponse<AdminMenuResponse> answer = answerFor(reportingOutcome(
                    SystemMessages.CCDA_MSG_INVALID_KEY, AdminMenuService.MAP_MESSAGE_COLOUR));

            assertThat(answer.screen().errMsg()).isNotBlank();
            assertThat(answer.screenMetadata().resetAllOutputFields())
                    .as("only the first-entry path clears the output fields")
                    .isFalse();
        }

        @ParameterizedTest(name = "notOk={0} blank={1} reenter={2} -> colour={3} asterisk={4}")
        @CsvSource({
            "true,  false, false, false, false",
            "false, true,  false, false, false",
            "true,  true,  false, false, false",
            "false, false, false, false, false",
            "true,  false, true,  true,  false",
            "false, true,  true,  true,  true",
            "true,  true,  true,  true,  true",
            "false, false, true,  false, false",
        })
        @DisplayName("G38: the CSSETATY highlight applies only in REENTER, and '*' only when BLANK")
        void theHighlightAppliesOnlyInReenter(final boolean notOk,
                final boolean blank,
                final boolean reenter,
                final boolean colourExpected,
                final boolean asteriskExpected) {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter);

            assertThat(highlight.colourItemAssigned()).isEqualTo(colourExpected);
            assertThat(highlight.outputItemAssigned()).isEqualTo(asteriskExpected);
            assertThat(highlight.untouched()).isEqualTo(!colourExpected);
            if (colourExpected) {
                assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            }
            if (asteriskExpected) {
                assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            }
        }

        @Test
        @DisplayName("B5: this screen invents no highlight, because COADM01C copies no CSSETATY")
        void thisScreenAppliesNoHighlight() {
            ScreenMetadata metadata = answerFor(reportingOutcome(
                    SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuService.MAP_MESSAGE_COLOUR)).screenMetadata();

            assertThat(metadata.fields()).isEmpty();
            assertThat(metadata.field(AdminMenuResponse.OPTION_FIELD))
                    .as("an unhighlighted field has no quad, and asking for one must not invent it")
                    .isNull();
        }

        @Test
        @DisplayName("the raw EIBAID byte travels uninterpreted - COADM01C compares it inline at L93")
        void theRawAidTravels() {
            AdminMenuController controller = controllerOver(stubbedService());

            byte entered = controller.toInput(blankScreen(NavigationContext.empty().withPgmReenter(),
                    CicsAid.DFHENTER)).eibAid();
            byte exited = controller.toInput(blankScreen(NavigationContext.empty().withPgmReenter(),
                    CicsAid.DFHPF3)).eibAid();
            byte unhandled = controller.toInput(blankScreen(NavigationContext.empty().withPgmReenter(),
                    CicsAid.DFHPF15)).eibAid();

            assertThat(entered).isEqualTo(CicsAid.DFHENTER);
            assertThat(exited).isEqualTo(CicsAid.DFHPF3);
            assertThat(unhandled).isEqualTo(CicsAid.DFHPF15);
            assertThat(PfKeyResolver.isEnter(entered)).isTrue();
            assertThat(PfKeyResolver.isPf3(exited)).isTrue();
            assertThat(PfKeyResolver.isEnter(unhandled)).isFalse();
            assertThat(PfKeyResolver.isPf3(unhandled))
                    .as("PF15 matches neither arm, so it lands on WHEN OTHER - a service decision")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Navigation (G40) - XCTL as three response fields, and never a redirect")
    class Navigation {
        @Test
        @DisplayName("a painted screen names its own mapset and map - L180-L181")
        void aPaintedScreenNamesItsOwnMap() {
            AdminMenuResponse painted = paint();

            assertThat(painted.nextMapset())
                    .isEqualTo(AdminMenuResponse.MAPSET_NAME)
                    .isEqualTo("COADM01")
                    .hasSize(AdminMenuResponse.NEXT_MAPSET_LENGTH);
            assertThat(painted.nextMap())
                    .isEqualTo(AdminMenuResponse.MAP_NAME)
                    .isEqualTo("COADM1A")
                    .hasSize(AdminMenuResponse.NEXT_MAP_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET is PIC X(7), not X(8) - app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(7);
            assertThat(painted.nextProgram())
                    .as("no transfer, so no successor is named")
                    .isBlank()
                    .hasSize(AdminMenuResponse.NEXT_PROGRAM_LENGTH);
        }

        @ParameterizedTest(name = "XCTL PROGRAM({0}) is echoed verbatim")
        @ValueSource(strings = {USER_LIST_PROGRAM, SIGNON_PROGRAM})
        @DisplayName("the successor is echoed verbatim: the controller derives nothing")
        void theSuccessorIsEchoedVerbatim(final String target) {
            NavigationContext held = NavigationContext.empty().withPgmEnter().withToProgram(target);
            AdminMenuOutcome transferring = SIGNON_PROGRAM.equals(target)
                    ? signOnTransferOutcome(held)
                    : optionTransferOutcome(target, held);

            AdminMenuResponse transferred = answerFor(transferring).screen();

            assertThat(transferred.nextProgram())
                    .isEqualTo(transferring.nextProgram())
                    .hasSize(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                    .startsWith(target);
            assertThat(transferred.nextMapset())
                    .as("an XCTL sends no map, so neither name is claimed")
                    .isBlank();
            assertThat(transferred.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the no-COMMAREA transfer hands back NO communication area, so sign-on cold-starts")
        void theSignOnTransferCarriesNoCommunicationArea() {
            NavigationContext held = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();

            AdminMenuResponse painted = answerFor(signOnTransferOutcome(held),
                    blankScreen(held, CicsAid.DFHPF3)).screen();

            assertThat(painted.navigationContext())
                    .as("stated absence: there is no area to carry forward")
                    .isNull();
            assertThat(painted.nextProgram())
                    .as("and the successor is still named, so the client knows where to go")
                    .startsWith(SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("the option transfer DOES hand back the area, because :142-145 names COMMAREA")
        void theOptionTransferStillCarriesTheArea() {
            NavigationContext held = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();

            AdminMenuResponse painted = answerFor(optionTransferOutcome(USER_LIST_PROGRAM, held),
                    blankScreen(held, CicsAid.DFHENTER)).screen();

            assertThat(painted.navigationContext()).isEqualTo(held);
        }

        @Test
        @DisplayName("the painted RETURN still hands back the area, because :107-110 names COMMAREA")
        void thePaintedReturnStillCarriesTheArea() {
            NavigationContext held = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();

            AdminMenuOutcome painted = paintedOutcome(held);
            assertThat(painted.nextProgramCarriesCommarea())
                    .as("false on the RETURN path too, which is why it cannot be the test on its own")
                    .isFalse();
            assertThat(painted.hasNextProgram())
                    .as("but no successor is named, which is what separates the two")
                    .isFalse();

            assertThat(answerFor(painted, blankScreen(held, CicsAid.DFHENTER)).screen()
                    .navigationContext())
                    .isEqualTo(held);
        }

        @Test
        @DisplayName("the wire says \"navigationContext\": null - a stated member, not an omitted one")
        void theWireStatesTheAbsenceRatherThanOmittingIt() throws Exception {
            NavigationContext held = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();
            ObjectMapper mapper = productionMapper();

            var body = mapper.readTree(mapper.writeValueAsString(answerFor(
                    signOnTransferOutcome(held), blankScreen(held, CicsAid.DFHPF3))));

            assertThat(body.has("navigationContext")).isTrue();
            assertThat(body.get("navigationContext").isNull()).isTrue();
        }

        @Test
        @DisplayName("a client following the transfer reaches sign-on's EIBCALEN = 0 cold start")
        void theClientFollowingTheTransferReachesTheColdStart() throws Exception {
            NavigationContext held = NavigationContext.empty().withPgmReenter().withUserTypeAdmin();
            ObjectMapper mapper = productionMapper();

            var body = mapper.readTree(mapper.writeValueAsString(answerFor(
                    signOnTransferOutcome(held), blankScreen(held, CicsAid.DFHPF3))));
            NavigationContext carriedForward =
                    mapper.treeToValue(body.get("navigationContext"), NavigationContext.class);

            assertThat(carriedForward).isNull();
            assertThat(new com.vsergeychik.carddemo.user.SignOnService.SignOnInput(carriedForward,
                    CicsAid.DFHENTER, null, null).isCommareaPresent())
                    .as("EIBCALEN = 0, which app/cbl/COSGN00C.cbl:80-83 answers with the cold start")
                    .isFalse();
        }

        @Test
        @DisplayName("G37: the outbound communication area is the 160-byte COMMAREA, carried in payload")
        void theOutboundCommareaIsCarried() {
            NavigationContext outbound = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserTypeAdmin()
                    .withLastMap(AdminMenuResponse.MAP_NAME)
                    .withLastMapset(AdminMenuResponse.MAPSET_NAME);

            AdminMenuResponse painted = answerFor(paintedOutcome(outbound)).screen();

            assertThat(painted.navigationContext()).isEqualTo(outbound);
            assertThat(outbound.toFixedWidth(CODEC)).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(painted.navigationContext().lastMap())
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(painted.navigationContext().lastMapset())
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("The wire - the screen stays flat, the padding survives, and nothing is stashed")
    class TheWire {
        private MockMvc mockMvcOver(final AdminMenuService service) {
            return MockMvcBuilders.standaloneSetup(controllerOver(service)).build();
        }

        private AdminMenuService serviceReturning(final AdminMenuOutcome outcome) {
            AdminMenuService service = stubbedService();
            doReturn(outcome).when(service).handle(any(AdminMenuInput.class));
            return service;
        }

        private String bodyOf(final AdminMenuRequest request) throws Exception {
            return new ObjectMapper().writeValueAsString(request);
        }

        private MvcResult perform(final AdminMenuService service, final AdminMenuRequest request)
                throws Exception {
            return mockMvcOver(service).perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyOf(request)))
                    .andExpect(status().isOk())
                    .andReturn();
        }

        @Test
        @DisplayName("a GET with no body answers the cold start over HTTP - L82")
        void aColdStartOverHttp() throws Exception {
            AdminMenuOutcome diverting = outcome(blankOptionLines(),
                    CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                    AdminMenuService.MAP_MESSAGE_COLOUR,
                    SIGNON_PROGRAM,
                    false,
                    NavigationContext.empty().withToProgram(SIGNON_PROGRAM));

            mockMvcOver(serviceReturning(diverting))
                    .perform(get(AdminMenuController.ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(
                            CODEC.movePicX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH)));
        }

        @Test
        @DisplayName("the twenty items stay at the top level and screenMetadata is one sibling member")
        void theEnvelopeLeavesTheScreenFlat() throws Exception {
            AdminMenuService service = serviceReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()));

            mockMvcOver(service).perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyOf(blankScreen(NavigationContext.empty().withPgmEnter(),
                                    CicsAid.DFHENTER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(AdminMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmname").value(AdminMenuResponse.PROGRAM_NAME))
                    .andExpect(jsonPath("$.nextMapset").value(AdminMenuResponse.MAPSET_NAME))
                    .andExpect(jsonPath("$.nextMap").value(AdminMenuResponse.MAP_NAME))
                    .andExpect(jsonPath("$.screenMetadata.resetAllOutputFields").value(true))
                    .andExpect(jsonPath("$.screenMetadata.messageColour")
                            .value(BmsAttributes.unsigned(BmsAttributes.DFHRED)))
                    .andExpect(jsonPath("$.messageColour").doesNotExist())
                    .andExpect(jsonPath("$.resetAllOutputFields").doesNotExist())
                    .andExpect(jsonPath("$.screen").doesNotExist());
        }

        @Test
        @DisplayName("space-padded PIC X(n) values survive the round trip untrimmed and unomitted")
        void spacePaddedValuesSurvive() throws Exception {
            AdminMenuService service = serviceReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()));

            mockMvcOver(service).perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyOf(blankScreen(NavigationContext.empty().withPgmReenter(),
                                    CicsAid.DFHENTER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.errmsg").exists())
                    .andExpect(jsonPath("$.errmsg")
                            .value(SPACE.repeat(AdminMenuResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.optn005").exists())
                    .andExpect(jsonPath("$.optn005")
                            .value(SPACE.repeat(AdminMenuResponse.OPTION_LINE_LENGTH)))
                    .andExpect(jsonPath("$.optn012").exists())
                    .andExpect(jsonPath("$.option")
                            .value(SPACE.repeat(AdminMenuResponse.OPTION_LENGTH)));
        }

        @Test
        @DisplayName("member names are the DTO's own: no snake_case, no kebab-case, no re-capitalising")
        void memberNamesAreUntransformed() throws Exception {
            String json = perform(serviceReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter())),
                    blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER))
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(json).contains("\"trnname\"", "\"title01\"", "\"curdate\"", "\"pgmname\"",
                    "\"title02\"", "\"curtime\"", "\"optn001\"", "\"optn012\"", "\"option\"",
                    "\"errmsg\"", "\"nextProgram\"", "\"nextMapset\"", "\"nextMap\"",
                    "\"navigationContext\"", "\"screenMetadata\"");
            assertThat(json).doesNotContain("trn_name", "TrnName", "trn-name", "err_msg", "ErrMsg");
        }

        @Test
        @DisplayName("G9: no length, flag or attribute item reaches the wire under any spelling")
        void noMetadataMemberReachesTheWire() throws Exception {
            String json = perform(serviceReturning(reportingOutcome(
                    SystemMessages.CCDA_MSG_INVALID_KEY, AdminMenuService.MAP_MESSAGE_COLOUR)),
                    blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER))
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(json).doesNotContain("optionL", "optionF", "optionA",
                    "errMsgL", "errMsgF", "errMsgA",
                    "errMsgC", "errMsgP", "errMsgH", "errMsgV",
                    "trnNameC", "title01A", "curDateH", "optn001V");
        }

        @Test
        @DisplayName("G37: no HttpSession is created and no JSESSIONID is set")
        void noSessionIsCreated() throws Exception {
            MvcResult result = perform(serviceReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter())),
                    blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));

            assertThat(result.getRequest().getSession(false))
                    .as("CICS is pseudo-conversational: the COMMAREA travels in the payload, and there "
                            + "is no server-side conversation to keep")
                    .isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getResponse().getHeaderNames())
                    .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase(HttpHeaders.SET_COOKIE));
        }

        @Test
        @DisplayName("G41: nothing authenticates - no challenge header, and no Spring Security at all")
        void noSecurityIsIntroduced() throws Exception {
            MvcResult result = perform(serviceReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter())),
                    blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));

            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .as("the closed dependency set excludes Spring Security, JWT and BCrypt entirely; "
                            + "COSGN00C compares SEC-USR-PWD in plaintext and this screen "
                            + "authenticates nothing (practice B6)")
                    .isThrownBy(() -> Class.forName("org.springframework.security.core.Authentication"));
        }

        @Test
        @DisplayName("the wire is UTF-8 JSON, and never a redirect - XCTL is a field, not a 3xx")
        void theWireIsUtf8JsonAndNeverARedirect() throws Exception {
            AdminMenuOutcome transferring = outcome(paintedOptionLines(),
                    CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                    AdminMenuService.MAP_MESSAGE_COLOUR,
                    USER_LIST_PROGRAM,
                    false,
                    NavigationContext.empty().withToProgram(USER_LIST_PROGRAM));

            MvcResult result = perform(serviceReturning(transferring),
                    withOption("01", NavigationContext.empty().withPgmReenter()));

            assertThat(result.getResponse().getContentType())
                    .contains(MediaType.APPLICATION_JSON_VALUE);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("the payload is UTF-8 on the wire; the record code pages belong to dataset I/O")
                    .contains(ScreenTitles.CCDA_TITLE01);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                    .as("a transfer is named in nextProgram and resolved by the client")
                    .isNull();
        }
    }

    @TestConfiguration
    static class SliceCollaborators {
        @Bean
        AdminMenuService adminMenuService() {
            return new AdminMenuService(bindings());
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Nested
    @WebMvcTest(AdminMenuController.class)
    @ActiveProfiles("test")
    @Import({SliceCollaborators.class, CobolCharsetConfig.class})
    @DisplayName("The Spring MVC slice - real dispatcher, real WebConfig, stubbed decision core")
    class TheSpringSlice {
        @MockitoSpyBean
        private AdminMenuService service;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("the real dispatcher maps GET /api/admin/menu onto this controller")
        void theRealDispatcherMapsTheRoute() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            mockMvc.perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(
                                    blankScreen(NavigationContext.empty().withPgmEnter(),
                                            CicsAid.DFHENTER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(AdminMenuResponse.TRANSACTION_ID));

            assertThat(mockingDetails(service).isSpy()).isTrue();
            verify(service).handle(any(AdminMenuInput.class));
        }

        @Test
        @DisplayName("B7: the fixed clock really is the one in the path, so the header is assertable")
        void theFixedClockIsInThePath() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            assertThat(context.getBean(WebConfig.class))
                    .as("WebConfig is a WebMvcConfigurer, so the slice picks it up with its Jackson "
                            + "customisation and its @RestControllerAdvice")
                    .isNotNull();
            assertThat(context.getBean(Clock.class).instant()).isEqualTo(FIXED_INSTANT);

            mockMvc.perform(get(AdminMenuController.ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.curdate").value("07/19/22"))
                    .andExpect(jsonPath("$.curtime").value("23:12:32"));
        }

        @Test
        @DisplayName("the configured mapper keeps the padding: 78 spaces travel as 78 spaces")
        void theConfiguredMapperKeepsThePadding() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            mockMvc.perform(get(AdminMenuController.ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(SPACE.repeat(AdminMenuResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.optn011").exists())
                    .andExpect(jsonPath("$.optn012").exists());
        }

        @Test
        @DisplayName("Bean Validation rejects an option wider than the map's LENGTH=2")
        void beanValidationRejectsAnOverLongOption() throws Exception {
            String tooWide = "1".repeat(AdminMenuRequest.OPTION_LENGTH + 1);

            mockMvc.perform(get(AdminMenuController.ADMIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(withOption(tooWide,
                                    NavigationContext.empty().withPgmReenter()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("option"));

            verify(service, never()).handle(any(AdminMenuInput.class));
        }

        @Test
        @DisplayName("G37 and G41: the real slice creates no session and mounts no security filter")
        void theRealSliceIsStatelessAndUnsecured() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(AdminMenuInput.class));

            MvcResult result = mockMvc.perform(get(AdminMenuController.ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(context.containsBean("springSecurityFilterChain"))
                    .as("no security filter chain is mounted, because Spring Security is not a "
                            + "dependency of this module at all (gate G41, practice B6)")
                    .isFalse();
        }
    }
}
