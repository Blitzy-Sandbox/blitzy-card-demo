package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.MainMenuService.ReceiveOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The Spring MVC slice test for {@link MainMenuController} - CSD transaction {@code CM00}
 * ({@code app/csd/CARDDEMO.CSD:399-400}), program {@code app/cbl/COMEN01C.cbl}, "Main Menu for the Regular
 * users", projected onto the single endpoint {@code GET /api/menu}.
 */
@DisplayName("MainMenuController - GET /api/menu, CSD transaction CM00, program COMEN01C")
class MainMenuControllerTest {
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final String EXPECTED_CURDATE = "07/19/22";

    private static final String EXPECTED_CURTIME = "23:12:33";

    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(MESSAGE_CHARSET);

    private static final String SPACE = " ";

    private static final String SCREEN_METADATA_MEMBER = "screenMetadata";

    private static final int MENU_OPT_NAME_LENGTH = 35;

    private static final int MENU_OPT_NUM_LENGTH = 2;

    private static final String MENU_OPT_SEPARATOR = ". ";

    private static final List<String> MENU_OPT_NAMES = List.of(
            "Account View                       ",
            "Account Update                     ",
            "Credit Card List                   ",
            "Credit Card View                   ",
            "Credit Card Update                 ",
            "Transaction List                   ",
            "Transaction View                   ",
            "Transaction Add                    ",
            "Transaction Reports                ",
            "Bill Payment                       ");

    private static final List<String> MENU_OPT_PROGRAMS = List.of("COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C");

    private static final String COMING_SOON_OPTION_ONE = "This option Accountis coming soon ...";

    private static final String EIGHTY_BYTE_PROBE =
            "#".repeat(MainMenuResponse.ERR_MSG_LENGTH) + "AB";

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(MainMenuService.USRSEC_DATASET_KEY,
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

    private static MainMenuService stubbedService() {
        MainMenuService stub = mock(MainMenuService.class);
        when(stub.codec()).thenReturn(CODEC);
        return stub;
    }

    private static MainMenuController controllerOver(final MainMenuService service) {
        return new MainMenuController(service, FIXED_CLOCK);
    }

    private static List<String> blankOptionLines() {
        List<String> lines = new ArrayList<>(MainMenuResponse.OPTION_LINE_COUNT);
        for (int slot = MainMenuResponse.FIRST_OPTION_LINE_SLOT;
                slot <= MainMenuResponse.LAST_OPTION_LINE_SLOT;
                slot++) {
            lines.add(CODEC.movePicX(SPACE, MainMenuResponse.OPTION_LINE_LENGTH));
        }
        return lines;
    }

    private static String menuLine(final int cobolSubscript) {
        String number = CODEC.movePic9(cobolSubscript, MENU_OPT_NUM_LENGTH);
        String label = MENU_OPT_NAMES.get(cobolSubscript - 1);
        return CODEC.movePicX(number + MENU_OPT_SEPARATOR + label,
                MainMenuResponse.OPTION_LINE_LENGTH);
    }

    private static List<String> paintedOptionLines() {
        List<String> lines = blankOptionLines();
        for (int subscript = 1; subscript <= MENU_OPT_NAMES.size(); subscript++) {
            lines.set(subscript - 1, menuLine(subscript));
        }
        return lines;
    }

    private static String message80(final String text) {
        return CODEC.movePicX(text, MainMenuService.MESSAGE_LENGTH);
    }

    private static MainMenuOutcome outcome(final List<String> optionLines,
            final String message80,
            final byte colour,
            final String option,
            final String nextProgram,
            final boolean reset,
            final NavigationContext context) {
        boolean transferring = !nextProgram.isBlank();
        return new MainMenuOutcome(optionLines,
                message80,
                colour,
                !message80.isBlank(),
                CODEC.movePicX(option, MainMenuService.OPTION_LENGTH),
                CODEC.movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH),
                transferring,
                !transferring,
                reset,
                context,
                MainMenuService.TRANSACTION_ID,
                transferring ? CODEC.movePicX(SPACE, MainMenuResponse.NEXT_MAPSET_LENGTH)
                        : MainMenuService.MAPSET_NAME,
                transferring ? CODEC.movePicX(SPACE, MainMenuResponse.NEXT_MAP_LENGTH)
                        : MainMenuService.MAP_NAME,
                transferring ? ReceiveOutcome.NORMAL : ReceiveOutcome.NOT_PERFORMED);
    }

    private static MainMenuOutcome paintedOutcome(final NavigationContext context) {
        return outcome(paintedOptionLines(),
                message80(SPACE),
                MainMenuService.MAP_MESSAGE_COLOUR,
                SPACE,
                SPACE,
                true,
                context);
    }

    private static MainMenuOutcome reportingOutcome(final String message, final byte colour) {
        return outcome(paintedOptionLines(),
                message80(message),
                colour,
                SPACE,
                SPACE,
                false,
                NavigationContext.empty().withPgmReenter());
    }

    private static MainMenuOutcome transferringOutcome(final String nextProgram,
            final NavigationContext context) {
        return outcome(paintedOptionLines(),
                message80(SPACE),
                MainMenuService.MAP_MESSAGE_COLOUR,
                SPACE,
                nextProgram,
                false,
                context);
    }

    private static MainMenuOutcome signOnTransferOutcome(final NavigationContext context) {
        return new MainMenuOutcome(paintedOptionLines(),
                message80(SPACE),
                MainMenuService.MAP_MESSAGE_COLOUR,
                false,
                CODEC.movePicX(SPACE, MainMenuService.OPTION_LENGTH),
                CODEC.movePicX(MainMenuService.SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH),
                false,
                false,
                false,
                context,
                MainMenuService.TRANSACTION_ID,
                CODEC.movePicX(SPACE, MainMenuResponse.NEXT_MAPSET_LENGTH),
                CODEC.movePicX(SPACE, MainMenuResponse.NEXT_MAP_LENGTH),
                ReceiveOutcome.NORMAL);
    }

    private static MainMenuRequest blankScreen(final NavigationContext context, final byte aid) {
        String optionLine = CODEC.movePicX(SPACE, MainMenuRequest.OPTION_LINE_LENGTH);
        return new MainMenuRequest(CODEC.movePicX(SPACE, MainMenuRequest.TRN_NAME_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.TITLE_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.CUR_DATE_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.PGM_NAME_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.TITLE_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.CUR_TIME_LENGTH),
                optionLine, optionLine, optionLine, optionLine, optionLine, optionLine,
                optionLine, optionLine, optionLine, optionLine, optionLine, optionLine,
                CODEC.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH),
                CODEC.movePicX(SPACE, MainMenuRequest.ERR_MSG_LENGTH),
                context,
                aid);
    }

    private static MainMenuRequest withOption(final String option, final NavigationContext context) {
        MainMenuRequest blank = blankScreen(context, CicsAid.DFHENTER);
        return new MainMenuRequest(blank.trnName(), blank.title01(), blank.curDate(),
                blank.pgmName(), blank.title02(), blank.curTime(),
                blank.optn001(), blank.optn002(), blank.optn003(), blank.optn004(),
                blank.optn005(), blank.optn006(), blank.optn007(), blank.optn008(),
                blank.optn009(), blank.optn010(), blank.optn011(), blank.optn012(),
                option, blank.errMsg(), context, blank.eibAid());
    }

    private static NavigationContext signOnHandoffContext() {
        return NavigationContext.empty()
                .withFromProgram(CODEC.movePicX(MainMenuService.SIGNON_PROGRAM,
                        NavigationContext.FROM_PROGRAM_LENGTH))
                .withUserId(CODEC.movePicX("USER0001", NavigationContext.USER_ID_LENGTH))
                .withUserTypeUser()
                .withPgmEnter();
    }

    private static ScreenResponse<MainMenuResponse> answerFor(final MainMenuOutcome outcome) {
        return answerFor(outcome,
                blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));
    }

    private static ScreenResponse<MainMenuResponse> answerFor(final MainMenuOutcome outcome,
            final MainMenuRequest request) {
        MainMenuService service = stubbedService();
        doReturn(outcome).when(service).handle(any(MainMenuInput.class));
        return controllerOver(service).getMainMenu(request);
    }

    private static MainMenuResponse paint() {
        return answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter())).screen();
    }

    private static List<String> payloadWireNames() {
        return payloadMemberNames().stream()
                .map(member -> member.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    private static List<String> payloadMemberNames() {
        List<String> names = new ArrayList<>(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT);
        names.add("trnName");
        names.add("title01");
        names.add("curDate");
        names.add("pgmName");
        names.add("title02");
        names.add("curTime");
        for (int slot = MainMenuResponse.FIRST_OPTION_LINE_SLOT;
                slot <= MainMenuResponse.LAST_OPTION_LINE_SLOT;
                slot++) {
            names.add("optn" + CODEC.movePic9(slot, 3));
        }
        names.add("option");
        names.add("errMsg");
        return names;
    }

    private static ObjectNode envelopeOf(final String body) throws JsonProcessingException {
        return (ObjectNode) new ObjectMapper().readTree(body);
    }

    private static ObjectMapper productionMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WebConfig().carddemoJacksonCustomizer(MESSAGE_CHARSET).customize(builder);
        return builder.build();
    }

    private static ObjectNode screenNodeOf(final String body) throws JsonProcessingException {
        ObjectNode screenOnly = envelopeOf(body).deepCopy();
        screenOnly.remove(SCREEN_METADATA_MEMBER);
        return screenOnly;
    }

    private static MainMenuResponse screenFromWire(final String body) throws JsonProcessingException {
        return new ObjectMapper().treeToValue(screenNodeOf(body), MainMenuResponse.class);
    }

    private static List<Field> declaredFieldsOfController() {
        List<Field> fields = new ArrayList<>();
        for (Field field : MainMenuController.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<Class<?>> declaredTypesOfController() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : declaredFieldsOfController()) {
            types.add(field.getType());
        }
        for (var constructor : MainMenuController.class.getDeclaredConstructors()) {
            types.addAll(List.of(constructor.getParameterTypes()));
        }
        for (Method method : MainMenuController.class.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(List.of(method.getParameterTypes()));
        }
        return types;
    }

    @Nested
    @DisplayName("Construction - two collaborators, both required, and no state of its own")
    class Construction {
        @Test
        @DisplayName("both arguments are required, and each message says what it is for")
        void bothCollaboratorsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuController(null, FIXED_CLOCK))
                    .withMessageContaining("MainMenuService")
                    .withMessageContaining("translated COMEN01C");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuController(stubbedService(), null))
                    .withMessageContaining("Clock")
                    .withMessageContaining("POPULATE-HEADER-INFO");
        }

        @Test
        @DisplayName("the codec comes from the service, so a bare bean override cannot stand in")
        void theCodecComesFromTheService() {
            MainMenuService service = stubbedService();

            controllerOver(service);

            verify(service).codec();
        }

        @Test
        @DisplayName("G53/B9: every declared field is final, and no static field is mutable")
        void theControllerHoldsNoMutableState() {
            for (Field field : declaredFieldsOfController()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final: COBOL WORKING-STORAGE must not become mutable Java "
                                + "state, because this bean is a singleton serving concurrent requests",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers())
                        || Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(
                                field.getModifiers()))
                        .as("%s is either private instance state or a published constant", field.getName())
                        .isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(field.getType())
                            .as("%s is static, so it must be a deeply immutable value", field.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("G37: two requests through one controller cannot see each other's screen")
        void twoRequestsAreIndependent() {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            MainMenuController controller = controllerOver(service);

            MainMenuResponse first = controller.getMainMenu(
                    blankScreen(NavigationContext.empty().withPgmEnter(), CicsAid.DFHENTER)).screen();
            MainMenuResponse second = controller.getMainMenu(
                    blankScreen(NavigationContext.empty().withPgmEnter(), CicsAid.DFHENTER)).screen();

            assertThat(second)
                    .as("the same request twice must produce the same response, because the controller "
                            + "retains nothing between calls")
                    .isEqualTo(first)
                    .isNotSameAs(first);
        }

        @Test
        @DisplayName("G37: a 'U' request following an 'A' request is not contaminated")
        void aUserRequestAfterAnAdminRequestIsClean() {
            MainMenuService service = stubbedService();
            MainMenuController controller = controllerOver(service);
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin().withPgmReenter();
            NavigationContext user = NavigationContext.empty().withUserTypeUser().withPgmReenter();
            doReturn(paintedOutcome(admin), paintedOutcome(user))
                    .when(service).handle(any(MainMenuInput.class));

            MainMenuResponse adminScreen =
                    controller.getMainMenu(blankScreen(admin, CicsAid.DFHENTER)).screen();
            MainMenuResponse userScreen =
                    controller.getMainMenu(blankScreen(user, CicsAid.DFHENTER)).screen();

            assertThat(adminScreen.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(userScreen.navigationContext().userType())
                    .as("the second response carries the second caller's user type, with nothing left "
                            + "over from the first")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(userScreen.navigationContext().isAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("The handler - one GET, one path, and EIBCALEN = 0 for an absent body")
    class TheHandlerContract {
        @Test
        @DisplayName("the mapping is the GET the plan assigns to transaction CM00")
        void theMappingIsTheDocumentedRoute() throws NoSuchMethodException {
            Method handler =
                    MainMenuController.class.getDeclaredMethod("getMainMenu", MainMenuRequest.class);
            GetMapping mapping = handler.getAnnotation(GetMapping.class);

            assertThat(MainMenuController.MAIN_MENU_PATH).isEqualTo("/api/menu");
            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly(MainMenuController.MAIN_MENU_PATH);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(MainMenuController.class.getDeclaredMethods())
                    .as("one route only: a second way in would be a second contract to keep in parity")
                    .filteredOn(method -> method.isAnnotationPresent(GetMapping.class))
                    .hasSize(1);
        }

        @Test
        @DisplayName("it answers the shared online envelope over this screen's payload")
        void itAnswersTheSharedEnvelope() throws NoSuchMethodException {
            Method handler =
                    MainMenuController.class.getDeclaredMethod("getMainMenu", MainMenuRequest.class);

            assertThat(handler.getReturnType()).isEqualTo(ScreenResponse.class);
            ParameterizedType returned = (ParameterizedType) handler.getGenericReturnType();
            assertThat(returned.getActualTypeArguments())
                    .as("the envelope is parameterised on THIS screen's response, not the sibling's")
                    .containsExactly(MainMenuResponse.class);
        }

        @Test
        @DisplayName("the body is optional and validated: an absent one is EIBCALEN = 0 - L82")
        void theBodyIsOptionalAndValidated() throws NoSuchMethodException {
            Method handler =
                    MainMenuController.class.getDeclaredMethod("getMainMenu", MainMenuRequest.class);
            Parameter payload = handler.getParameters()[0];

            assertThat(payload.getAnnotation(RequestBody.class)).isNotNull();
            assertThat(payload.getAnnotation(RequestBody.class).required())
                    .as("app/cbl/COMEN01C.cbl:82 tests IF EIBCALEN = 0, and a GET carrying no payload "
                            + "is exactly that invocation")
                    .isFalse();
            assertThat(payload.getAnnotation(Valid.class))
                    .as("the declared field widths are enforced before the handler body runs")
                    .isNotNull();
        }

        @Test
        @DisplayName("an absent body reaches the service with no communication area and spaces typed")
        void anAbsentBodyIsTheColdStart() {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            ArgumentCaptor<MainMenuInput> captor = ArgumentCaptor.forClass(MainMenuInput.class);

            controllerOver(service).getMainMenu(null);

            verify(service).handle(captor.capture());
            MainMenuInput seen = captor.getValue();
            assertThat(seen.navigationContext())
                    .as("a null communication area IS the representation of EIBCALEN = 0")
                    .isNull();
            assertThat(seen.option())
                    .as("a body that arrived with nothing in it sent no OPTIONI, and RECEIVE MAP leaves a "
                            + "field the terminal did not send at LOW-VALUES - not at spaces the operator "
                            + "never typed")
                    .isEqualTo(ScreenFieldImage.unpainted(MainMenuRequest.OPTION_LENGTH));
            assertThat(seen.eibAid()).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("the projection seam refuses null: the absent body has its own representation")
        void theProjectionSeamRefusesNull() {
            MainMenuController controller = controllerOver(stubbedService());

            assertThatNullPointerException()
                    .isThrownBy(() -> controller.showMainMenu(null))
                    .withMessageContaining("cold-start request");
        }

        @Test
        @DisplayName("toInput passes the three values COMEN01C reads through untouched")
        void toInputPassesTheThreeValuesThrough() {
            NavigationContext inbound = NavigationContext.empty().withUserTypeUser().withPgmReenter();
            MainMenuController controller = controllerOver(stubbedService());

            MainMenuInput translated = controller.toInput(withOption("3 ", inbound));

            assertThat(translated.navigationContext())
                    .as("passed by reference, so 'untouched' is structural rather than merely intended")
                    .isSameAs(inbound);
            assertThat(translated.eibAid())
                    .as("the raw byte, uninterpreted: COMEN01C compares EIBAID inline at :93 and copies "
                            + "no CSSTRPFY")
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(translated.option())
                    .as("raw, spaces and all - '3 ' has not become '03' here")
                    .isEqualTo("3 ");
        }

        @Test
        @DisplayName("an omitted OPTIONI member becomes the not-transmitted image, not spaces")
        void anOmittedOptionBecomesTheNotTransmittedImage() {
            MainMenuController controller = controllerOver(stubbedService());

            MainMenuInput translated = controller.toInput(
                    withOption(null, NavigationContext.empty().withPgmReenter()));

            assertThat(translated.option())
                    .as("a PIC X(2) field is never absent, so an image is supplied - and the image CICS "
                            + "leaves for a field the terminal did not send is LOW-VALUES. Spaces would "
                            + "assert the operator pressed the space bar twice. The backwards scan at "
                            + ":117-120 stops at the first position on either image, so the message the "
                            + "operator sees is unchanged; the echoed OPTIONO is what differs")
                    .isEqualTo(ScreenFieldImage.unpainted(MainMenuRequest.OPTION_LENGTH))
                    .hasSize(MainMenuRequest.OPTION_LENGTH);
        }

        @Test
        @DisplayName("the decision core is called exactly once per request")
        void theServiceIsCalledOncePerRequest() {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            MainMenuController controller = controllerOver(service);

            controller.getMainMenu(blankScreen(NavigationContext.empty(), CicsAid.DFHENTER));
            controller.getMainMenu(blankScreen(NavigationContext.empty(), CicsAid.DFHENTER));

            verify(service, times(2)).handle(any(MainMenuInput.class));
        }

        @Test
        @DisplayName("the outcome the service returns is required: every path produces one")
        void theOutcomeIsRequired() {
            MainMenuController controller = controllerOver(stubbedService());

            assertThatNullPointerException()
                    .isThrownBy(() -> controller.toResponse(null))
                    .withMessageContaining("An outcome is required");
        }
    }

    @Nested
    @DisplayName("The payload contract (G9) - twenty members, at the widths the symbolic map declares")
    class ThePayloadContract {
        @Test
        @DisplayName("twenty named DFHMDF fields of twenty-eight, and twenty payload members")
        void twentyOfTwentyEightFieldsTravel() {
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT).isEqualTo(28);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT).isEqualTo(20);
            assertThat(payloadMemberNames())
                    .hasSize(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(MainMenuRequest.MAP_FIELD_COUNT)
                    .as("the request projects the same twenty items as the response")
                    .isEqualTo(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the widths are the xxxI PICTURE clauses, in map order (B8: named constants only)")
        void theWidthsAreTheSymbolicMapPictures() {
            assertThat(MainMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(MainMenuResponse.TITLE_LENGTH).isEqualTo(40);
            assertThat(MainMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuResponse.OPTION_LENGTH).isEqualTo(2);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(MainMenuResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET is X(7), not X(8) - app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(7);
            assertThat(MainMenuResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is X(7), not X(8) - app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(7);
            assertThat(MainMenuResponse.NEXT_PROGRAM_LENGTH)
                    .as("CDEMO-TO-PROGRAM is X(8) - app/cpy/COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the rendered screen is exactly those widths, member by member")
        void theRenderedScreenHoldsThoseWidths() {
            MainMenuResponse painted = paint();

            assertThat(painted.trnName()).hasSize(MainMenuResponse.TRN_NAME_LENGTH);
            assertThat(painted.title01()).hasSize(MainMenuResponse.TITLE_LENGTH);
            assertThat(painted.curDate()).hasSize(MainMenuResponse.CUR_DATE_LENGTH);
            assertThat(painted.pgmName()).hasSize(MainMenuResponse.PGM_NAME_LENGTH);
            assertThat(painted.title02()).hasSize(MainMenuResponse.TITLE_LENGTH);
            assertThat(painted.curTime()).hasSize(MainMenuResponse.CUR_TIME_LENGTH);
            assertThat(painted.optionLines())
                    .hasSize(MainMenuResponse.OPTION_LINE_COUNT)
                    .allSatisfy(line ->
                            assertThat(line).hasSize(MainMenuResponse.OPTION_LINE_LENGTH));
            assertThat(painted.option()).hasSize(MainMenuResponse.OPTION_LENGTH);
            assertThat(painted.errMsg()).hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(painted.nextProgram()).hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            assertThat(painted.nextMapset()).hasSize(MainMenuResponse.NEXT_MAPSET_LENGTH);
            assertThat(painted.nextMap()).hasSize(MainMenuResponse.NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("B5: all twelve option slots are carried, including the two nothing can write")
        void allTwelveSlotsAreCarried() {
            MainMenuResponse painted = paint();

            assertThat(payloadMemberNames())
                    .contains("optn001", "optn010", "optn011", "optn012");
            assertThat(painted.optionLines()).hasSize(MainMenuResponse.OPTION_LINE_COUNT);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT)
                    .as("CDEMO-MENU-OPT-COUNT is 10 - app/cpy/COMEN02Y.cpy:21")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("B5: ten lines are written and slots 11 and 12 stay blank though arms exist for them")
        void theLastTwoSlotsAreBlankButPresent() {
            MainMenuResponse painted = paint();

            assertThat(painted.optionLine(11))
                    .isNotNull()
                    .isBlank()
                    .hasSize(MainMenuResponse.OPTION_LINE_LENGTH);
            assertThat(painted.optionLine(12))
                    .isNotNull()
                    .isBlank()
                    .hasSize(MainMenuResponse.OPTION_LINE_LENGTH);
            assertThat(painted.isPopulatedByProgram(10)).isTrue();
            assertThat(painted.isPopulatedByProgram(11)).isFalse();
            assertThat(painted.isPopulatedByProgram(12)).isFalse();
        }

        @Test
        @DisplayName("G9: no record component is an xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item")
        void noMetadataItemIsAMember() {
            List<String> members = new ArrayList<>();
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                members.add(component.getName());
            }

            assertThat(members).containsAll(payloadMemberNames());
            assertThat(members)
                    .as("the length, flag and attribute items are validation and highlight metadata. "
                            + "app/cpy-bms/COMEN01.CPY declares xxxL COMP PIC S9(4), xxxF PICTURE X and "
                            + "its xxxA REDEFINES on the input side, and the output group at :139-:260 "
                            + "adds xxxC, xxxP, xxxH and xxxV. None of them is a payload member.")
                    .doesNotContain("optionL", "optionF", "optionA",
                            "errMsgL", "errMsgF", "errMsgA",
                            "errMsgC", "errMsgP", "errMsgH", "errMsgV",
                            "trnNameL", "trnNameC", "title01A", "curDateH", "optn001V");
        }

        @Test
        @DisplayName("the message colour travels as metadata beside the screen, not as an errMsgC field")
        void theColourTravelsAsMetadata() {
            ScreenResponse<MainMenuResponse> answer =
                    answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter()));

            ScreenMetadata metadata = answer.screenMetadata();
            assertThat(metadata.messageColour())
                    .as("published unsigned, because DFHRED is 0xF2 and a signed byte would read -14")
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            assertThat(metadata.fields())
                    .as("COMEN01C copies neither CSSETATY nor CSSTRPFY, so it sets no per-field "
                            + "attribute quad; the map is accurately empty rather than absent")
                    .isEmpty();
            assertThat(metadata.cursorField())
                    .as("the program contains no MOVE -1 TO <field>L, so it requests no cursor - the IC "
                            + "on OPTION at app/bms/COMEN01.bms:145 is a static mapset attribute")
                    .isNull();
        }

        @Test
        @DisplayName("option is the only editable field, so the header the client sends is overwritten")
        void onlyTheOptionIsEditable() {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            MainMenuRequest tampered = new MainMenuRequest("ZZZZ",
                    "tampered title one                      ",
                    "99/99/99", "ZZZZZZZZ", "tampered title two                      ", "99:99:99",
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    "01", "tampered message", NavigationContext.empty().withPgmReenter(),
                    CicsAid.DFHENTER);

            MainMenuResponse painted = controllerOver(service).getMainMenu(tampered).screen();

            assertThat(painted.trnName()).isEqualTo(MainMenuService.TRANSACTION_ID);
            assertThat(painted.pgmName()).isEqualTo(MainMenuService.PROGRAM_NAME);
            assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(painted.curDate()).isEqualTo(EXPECTED_CURDATE).isNotEqualTo("99/99/99");
            assertThat(painted.curTime()).isEqualTo(EXPECTED_CURTIME).isNotEqualTo("99:99:99");
            assertThat(painted.errMsg()).doesNotContain("tampered");
        }

        @Test
        @DisplayName("the typed option reaches the service raw, and comes back as the service normalised it")
        void theOptionGoesInRawAndComesBackNormalised() {
            MainMenuService service = stubbedService();
            doReturn(outcome(paintedOptionLines(),
                    message80(SPACE),
                    MainMenuService.MAP_MESSAGE_COLOUR,
                    "03",
                    SPACE,
                    false,
                    NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            ArgumentCaptor<MainMenuInput> captor = ArgumentCaptor.forClass(MainMenuInput.class);

            MainMenuResponse painted = controllerOver(service)
                    .getMainMenu(withOption(" 3", NavigationContext.empty().withPgmReenter())).screen();

            verify(service).handle(captor.capture());
            assertThat(captor.getValue().option())
                    .as("raw: the JUST RIGHT receiver and the INSPECT at COMEN01C:122-124 are the "
                            + "service's work, not the controller's")
                    .isEqualTo(" 3");
            assertThat(painted.option())
                    .as("echoed exactly as the service resolved it - COMEN01C:125")
                    .isEqualTo("03");
        }

        @Test
        @DisplayName("B4: the two menu screens keep separate types, though their shapes match")
        void theTwoMenuScreensKeepSeparateTypes() {
            assertThat(MainMenuResponse.class).isNotEqualTo(AdminMenuResponse.class);
            assertThat(AdminMenuResponse.class.isAssignableFrom(MainMenuResponse.class)).isFalse();
            assertThat(MainMenuResponse.class.getInterfaces())
                    .as("neither type is expressed through a shared screen interface")
                    .isEmpty();
            assertThat(MainMenuResponse.class.getRecordComponents().length)
                    .isEqualTo(AdminMenuResponse.class.getRecordComponents().length);
            assertThat(MainMenuResponse.class.getRecordComponents()[24].getName())
                    .as("even the ignored colour member is spelled differently on the two screens - "
                            + "errMsgColor here against messageColour on the sibling - so the shapes are "
                            + "not interchangeable even where the widths agree")
                    .isEqualTo("errMsgColor")
                    .isNotEqualTo(AdminMenuResponse.class.getRecordComponents()[24].getName());
            assertThat(MainMenuResponse.MAPSET_NAME).isEqualTo("COMEN01");
            assertThat(MainMenuResponse.MAP_NAME).isEqualTo("COMEN1A");
            assertThat(MainMenuResponse.TRANSACTION_ID)
                    .as("the identity constants are what actually differ between the two screens")
                    .isNotEqualTo(AdminMenuResponse.TRANSACTION_ID);
        }

        @Test
        @DisplayName("the ten CDEMO-MENU-OPT-NAME literals are each the declared thirty-five characters")
        void theTenOptionLabelsAreTheDeclaredWidth() {
            assertThat(MENU_OPT_NAMES).hasSize(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT);
            assertThat(MENU_OPT_NAMES)
                    .as("CDEMO-MENU-OPT-NAME is PIC X(35) - app/cpy/COMEN02Y.cpy:90")
                    .allSatisfy(label -> assertThat(label).hasSize(MENU_OPT_NAME_LENGTH));
            assertThat(MENU_OPT_PROGRAMS)
                    .hasSize(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT)
                    .as("CDEMO-MENU-OPT-PGMNAME is PIC X(08) - app/cpy/COMEN02Y.cpy:91")
                    .allSatisfy(program ->
                            assertThat(program).hasSize(NavigationContext.TO_PROGRAM_LENGTH));
        }
    }

    @Nested
    @DisplayName("The message image - WS-MESSAGE X(80) into ERRMSGO X(78), truncated on the RIGHT")
    class TheMessageTruncation {
        @Test
        @DisplayName("the two widths disagree by two bytes, and that is the whole point")
        void theTwoWidthsDisagree() {
            assertThat(MainMenuService.MESSAGE_LENGTH)
                    .as("WS-MESSAGE PIC X(80) - app/cbl/COMEN01C.cbl:38")
                    .isEqualTo(80);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH)
                    .as("ERRMSG LENGTH=78 - app/bms/COMEN01.bms:156; ERRMSGO PIC X(78) - "
                            + "app/cpy-bms/COMEN01.CPY:260")
                    .isEqualTo(78);
            assertThat(MainMenuService.MESSAGE_LENGTH - MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(2);
        }

        @Test
        @DisplayName("an eighty-byte image loses exactly its last two bytes - right, not left")
        void anEightyByteImageLosesItsLastTwoBytes() {
            assertThat(EIGHTY_BYTE_PROBE).hasSize(MainMenuService.MESSAGE_LENGTH);

            MainMenuResponse painted = answerFor(reportingOutcome(EIGHTY_BYTE_PROBE,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH)
                    .isEqualTo("#".repeat(MainMenuResponse.ERR_MSG_LENGTH))
                    .as("the surviving text is the LEADING seventy-eight characters: a COBOL "
                            + "alphanumeric MOVE fills the receiver from its leftmost position and "
                            + "discards the overflow")
                    .doesNotEndWith("AB")
                    .doesNotContain("A")
                    .doesNotContain("B");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @DisplayName("every message the program can raise lands right-space-padded to exactly 78")
        @ValueSource(strings = {
                "Please enter a valid option number...",
                "No access - Admin Only option... ",
                "This option Accountis coming soon ...",
        })
        void everyMessageIsRightPaddedToSeventyEight(final String text) {
            MainMenuResponse painted =
                    answerFor(reportingOutcome(text, MainMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH)
                    .startsWith(text)
                    .isEqualTo(CODEC.movePicX(text, MainMenuResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("B5: 'No access - Admin Only option... ' keeps its trailing space before padding")
        void theRefusalMessageKeepsItsTrailingSpace() {
            String refusal = MainMenuService.NO_ACCESS_MESSAGE;
            assertThat(refusal).endsWith("option... ").hasSize(33);

            MainMenuResponse painted =
                    answerFor(reportingOutcome(refusal, MainMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg().charAt(refusal.length() - 1))
                    .as("the space the source literal ends with is still the thirty-third character")
                    .isEqualTo(' ');
            assertThat(painted.errMsg()).startsWith(refusal);
            assertThat(painted.errMsg().substring(0, refusal.length())).isEqualTo(refusal);
        }

        @Test
        @DisplayName("B5: the 'Accountis' defect survives the projection byte for byte")
        void theComingSoonDefectSurvivesTheProjection() {
            assertThat(COMING_SOON_OPTION_ONE)
                    .contains("Accountis")
                    .doesNotContain("Account is")
                    .isEqualTo(MainMenuService.COMING_SOON_PREFIX + "Account"
                            + MainMenuService.COMING_SOON_SUFFIX);

            MainMenuResponse painted = answerFor(reportingOutcome(COMING_SOON_OPTION_ONE,
                    MainMenuService.COMING_SOON_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg())
                    .startsWith(COMING_SOON_OPTION_ONE)
                    .contains("Accountis")
                    .doesNotContain("Account is")
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(painted.errMsg().substring(0, COMING_SOON_OPTION_ONE.length()))
                    .as("byte for byte, with no space inserted, nothing trimmed and nothing re-joined")
                    .isEqualTo(COMING_SOON_OPTION_ONE);
        }

        @Test
        @DisplayName("the invalid-key text is CCDA-MSG-INVALID-KEY: fifty bytes, and neither thank-you")
        void theInvalidKeyTextIsTheRightFiftyByteLiteral() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Invalid key pressed. Please see below...")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo application");

            MainMenuResponse painted = answerFor(reportingOutcome(
                    SystemMessages.CCDA_MSG_INVALID_KEY,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("B8: the codec does it, and it is handed an explicit charset")
        void theCodecWithAnExplicitCharsetDoesTheNarrowing() {
            assertThat(CODEC.charset())
                    .as("never the platform default: app/cbl/COMEN01C.cbl composes its messages in the "
                            + "code page MainMenuService.DEFAULT_MESSAGE_CHARSET_NAME names")
                    .isEqualTo(MESSAGE_CHARSET)
                    .isEqualTo(Charset.forName(MainMenuService.DEFAULT_MESSAGE_CHARSET_NAME));

            MainMenuResponse painted = answerFor(reportingOutcome(EIGHTY_BYTE_PROBE,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screen();

            assertThat(painted.errMsg())
                    .as("identical to the codec's own movePicX, which is what the controller calls - the "
                            + "rule lives in one auditable place rather than in a substring at the call "
                            + "site")
                    .isEqualTo(CODEC.movePicX(message80(EIGHTY_BYTE_PROBE),
                            MainMenuResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("errMsg is exactly 78 characters on every path, blank included")
        void errMsgIsAlwaysSeventyEightCharacters() {
            assertThat(paint().errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH)
                    .isEqualTo(SPACE.repeat(MainMenuResponse.ERR_MSG_LENGTH));
            assertThat(answerFor(reportingOutcome(MainMenuService.INVALID_OPTION_MESSAGE,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screen().errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(answerFor(transferringOutcome(MENU_OPT_PROGRAMS.get(0),
                    NavigationContext.empty().withPgmReenter())).screen().errMsg())
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
        }
    }

    @Nested
    @DisplayName("The header and the option lines - identity literals, titles, clock, and ten lines")
    class TheHeaderFields {
        @Test
        @DisplayName("TRNNAMEO is 'CM00' and PGMNAMEO is 'COMEN01C' - L218-L219")
        void theTwoIdentityLiteralsAreTheProgramsOwn() {
            MainMenuResponse painted = paint();

            assertThat(painted.trnName())
                    .isEqualTo("CM00")
                    .isEqualTo(MainMenuService.TRANSACTION_ID)
                    .isEqualTo(MainMenuResponse.TRANSACTION_ID)
                    .hasSize(MainMenuResponse.TRN_NAME_LENGTH);
            assertThat(painted.pgmName())
                    .isEqualTo("COMEN01C")
                    .isEqualTo(MainMenuService.PROGRAM_NAME)
                    .isEqualTo(MainMenuResponse.PROGRAM_NAME)
                    .hasSize(MainMenuResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("B5: the ACTIVE titles travel - not the commented-out decoy at COTTL01Y:21")
        void theActiveTitlesTravel() {
            MainMenuResponse painted = paint();

            assertThat(painted.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .hasSize(MainMenuResponse.TITLE_LENGTH)
                    .startsWith(SPACE)
                    .endsWith(SPACE);
            assertThat(painted.title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo("              CardDemo                  ")
                    .hasSize(MainMenuResponse.TITLE_LENGTH)
                    .doesNotContain("Credit Card Demo Application")
                    .doesNotContain("(CCDA)");
        }

        @Test
        @DisplayName("B7: CURDATEO is MM/DD/YY and CURTIMEO is HH:MM:SS under the fixed clock")
        void theHeaderRenderingsAreDeterministic() {
            MainMenuResponse painted = paint();

            assertThat(painted.curDate())
                    .isEqualTo(EXPECTED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(painted.curTime())
                    .isEqualTo(EXPECTED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .matches("\\d{2}:\\d{2}:\\d{2}");
        }

        @Test
        @DisplayName("the header is the shared DateHeader's rendering, read once from the one clock")
        void theHeaderIsTheSharedRendering() {
            DateHeader expected = DateHeader.from(CODEC, FIXED_CLOCK);
            MainMenuResponse painted = paint();

            assertThat(painted.curDate()).isEqualTo(expected.wsCurdateMmDdYy());
            assertThat(painted.curTime()).isEqualTo(expected.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("B7: the same request rendered twice is byte-identical, header included")
        void theSameRequestRenderedTwiceIsIdentical() {
            assertThat(paint()).isEqualTo(paint());
        }

        @ParameterizedTest(name = "[{index}] subscript {0} renders {1}")
        @DisplayName("the ten option lines project verbatim into optn001 through optn010")
        @CsvSource(delimiter = '|', value = {
                " 1|01. Account View",
                " 2|02. Account Update",
                " 3|03. Credit Card List",
                " 4|04. Credit Card View",
                " 5|05. Credit Card Update",
                " 6|06. Transaction List",
                " 7|07. Transaction View",
                " 8|08. Transaction Add",
                " 9|09. Transaction Reports",
                "10|10. Bill Payment",
        })
        void theTenOptionLinesProjectVerbatim(final int subscript, final String expectedText) {
            MainMenuResponse painted = paint();

            assertThat(painted.optionLine(subscript))
                    .isEqualTo(menuLine(subscript))
                    .isEqualTo(CODEC.movePicX(expectedText, MainMenuResponse.OPTION_LINE_LENGTH))
                    .startsWith(expectedText)
                    .hasSize(MainMenuResponse.OPTION_LINE_LENGTH);
        }

        @Test
        @DisplayName("B5: option 8 renders the ACTIVE label, never the COMEN02Y:69 admin-only decoy")
        void optionEightRendersTheActiveLabel() {
            MainMenuResponse painted = paint();

            assertThat(painted.optionLine(8))
                    .as("the active app/cpy/COMEN02Y.cpy:70 value; the commented-out :69 alternative is "
                            + "not reproduced, because inventing an admin-only restriction the shipped "
                            + "table does not declare would be a behaviour change")
                    .startsWith("08. Transaction Add")
                    .doesNotContain("Admin Only")
                    .doesNotContain("(Admin");
        }

        @Test
        @DisplayName("the lines are rendered unmodified: whatever the service supplies is what travels")
        void theLinesAreRenderedUnmodified() {
            List<String> supplied = blankOptionLines();
            for (int slot = 1; slot <= MainMenuResponse.OPTION_LINE_COUNT; slot++) {
                supplied.set(slot - 1, CODEC.movePicX("line-" + slot + " verbatim",
                        MainMenuResponse.OPTION_LINE_LENGTH));
            }

            MainMenuResponse painted = answerFor(outcome(supplied,
                    message80(SPACE),
                    MainMenuService.MAP_MESSAGE_COLOUR,
                    SPACE,
                    SPACE,
                    false,
                    NavigationContext.empty().withPgmReenter())).screen();

            assertThat(painted.optionLines())
                    .as("no reordering, no re-composition, no trimming")
                    .containsExactlyElementsOf(supplied);
        }
    }

    @Nested
    @DisplayName("CDEMO-USER-TYPE passes through UNALTERED - COMEN01C:149-150 are commented out")
    class UserTypePassesThroughUnaltered {
        @ParameterizedTest(name = "[{index}] user type ''{0}'' echoes unaltered")
        @DisplayName("G50: every inbound user type is echoed byte-identically, defined or not")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
                "A|true|false",
                "U|false|true",
                " |false|false",
                "X|false|false",
        })
        void everyInboundUserTypeIsEchoed(final String userType,
                final boolean expectedAdmin,
                final boolean expectedUser) {
            NavigationContext inbound =
                    NavigationContext.empty().withUserType(userType).withPgmReenter();

            MainMenuResponse painted = answerFor(paintedOutcome(inbound),
                    blankScreen(inbound, CicsAid.DFHENTER)).screen();

            assertThat(painted.navigationContext().userType())
                    .as("echoed byte for byte: no default substituted, nothing blanked, nothing mapped "
                            + "and nothing rejected")
                    .isEqualTo(userType)
                    .hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(painted.navigationContext().isAdmin()).isEqualTo(expectedAdmin);
            assertThat(painted.navigationContext().isUser()).isEqualTo(expectedUser);
        }

        @Test
        @DisplayName("a single space satisfies NEITHER 88-level, and no default is put in its place")
        void aBlankUserTypeIsNotDefaulted() {
            NavigationContext inbound = NavigationContext.empty().withPgmReenter();
            assertThat(inbound.userType()).isEqualTo(SPACE);

            MainMenuResponse painted = answerFor(paintedOutcome(inbound),
                    blankScreen(inbound, CicsAid.DFHENTER)).screen();

            assertThat(painted.navigationContext().userType())
                    .as("still a space: not 'U', which is the value a well-meaning default would pick")
                    .isEqualTo(SPACE)
                    .isNotEqualTo(NavigationContext.USER_TYPE_USER)
                    .isNotEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(painted.navigationContext().isAdmin()).isFalse();
            assertThat(painted.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("an undefined 'X' is echoed as 'X': not rejected, not blanked, not mapped")
        void anUndefinedUserTypeIsEchoedAsGiven() {
            NavigationContext inbound = NavigationContext.empty().withUserType("X").withPgmReenter();

            ScreenResponse<MainMenuResponse> answer =
                    answerFor(paintedOutcome(inbound), blankScreen(inbound, CicsAid.DFHENTER));

            assertThat(answer.screen().navigationContext().userType()).isEqualTo("X");
            assertThat(answer.screen()).isNotNull();
        }

        @Test
        @DisplayName("CDEMO-USER-ID is echoed unaltered too, blank included - L149 is commented out")
        void theUserIdIsEchoedUnaltered() {
            String signedOn = CODEC.movePicX("ADMIN001", NavigationContext.USER_ID_LENGTH);
            NavigationContext named =
                    NavigationContext.empty().withUserId(signedOn).withPgmReenter();
            NavigationContext blank = NavigationContext.empty().withPgmReenter();

            assertThat(answerFor(paintedOutcome(named), blankScreen(named, CicsAid.DFHENTER))
                    .screen().navigationContext().userId())
                    .isEqualTo(signedOn)
                    .hasSize(NavigationContext.USER_ID_LENGTH);
            assertThat(answerFor(paintedOutcome(blank), blankScreen(blank, CicsAid.DFHENTER))
                    .screen().navigationContext().userId())
                    .as("a blank identifier stays blank; line 149 would have filled it and it is "
                            + "commented out")
                    .isEqualTo(SPACE.repeat(NavigationContext.USER_ID_LENGTH));
        }

        @Test
        @DisplayName("the controller has no collaborator it could derive a user type from")
        void theControllerCannotDeriveAUserType() {
            assertThat(MainMenuController.class.getDeclaredConstructors()).hasSize(1);
            assertThat(MainMenuController.class.getDeclaredConstructors()[0].getParameterTypes())
                    .containsExactly(MainMenuService.class, Clock.class);
            assertThat(declaredTypesOfController())
                    .as("SecUserRecord is on the classpath - the record layout exists and is shared - but "
                            + "the controller names it nowhere, and no test here asserts a read of it "
                            + "(practice B6)")
                    .doesNotContain(SecUserRecord.class);
        }
    }

    @Nested
    @DisplayName("The controller does NOT filter - COMEN01C:136-143 lives in the service")
    class TheControllerDoesNotFilter {
        @Test
        @DisplayName("all ten options render for a 'U' caller: nothing is hidden, blanked or reordered")
        void allTenOptionsRenderForARegularUser() {
            NavigationContext regularUser =
                    NavigationContext.empty().withUserTypeUser().withPgmReenter();
            assertThat(regularUser.isUser()).isTrue();

            MainMenuResponse painted = answerFor(paintedOutcome(regularUser),
                    blankScreen(regularUser, CicsAid.DFHENTER)).screen();

            for (int subscript = 1;
                    subscript <= MainMenuResponse.ACTIVE_OPTION_LINE_COUNT;
                    subscript++) {
                assertThat(painted.optionLine(subscript))
                        .as("option %d must be rendered in full for a regular user", subscript)
                        .isEqualTo(menuLine(subscript))
                        .isNotBlank();
            }
            assertThat(painted.optionLines().subList(0, MainMenuResponse.ACTIVE_OPTION_LINE_COUNT))
                    .as("in subscript order, with none suppressed")
                    .containsExactlyElementsOf(paintedOptionLines()
                            .subList(0, MainMenuResponse.ACTIVE_OPTION_LINE_COUNT));
        }

        @Test
        @DisplayName("an admin and a user given the SAME outcome receive the SAME screen")
        void bothUserTypesReceiveTheSameProjection() {
            List<String> lines = paintedOptionLines();
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin().withPgmReenter();
            NavigationContext user = NavigationContext.empty().withUserTypeUser().withPgmReenter();

            MainMenuResponse asAdmin = answerFor(outcome(lines, message80(SPACE),
                    MainMenuService.MAP_MESSAGE_COLOUR, SPACE, SPACE, false, admin),
                    blankScreen(admin, CicsAid.DFHENTER)).screen();
            MainMenuResponse asUser = answerFor(outcome(lines, message80(SPACE),
                    MainMenuService.MAP_MESSAGE_COLOUR, SPACE, SPACE, false, user),
                    blankScreen(user, CicsAid.DFHENTER)).screen();

            assertThat(asUser.optionLines())
                    .as("the projection does not vary with the user type, because the controller does "
                            + "not read it")
                    .isEqualTo(asAdmin.optionLines());
            assertThat(asUser.errMsg()).isEqualTo(asAdmin.errMsg());
            assertThat(asUser.navigationContext().userType())
                    .isNotEqualTo(asAdmin.navigationContext().userType());
        }

        @Test
        @DisplayName("the refusal message is merely PROJECTED, never re-derived")
        void theRefusalMessageIsOnlyProjected() {
            NavigationContext administrator =
                    NavigationContext.empty().withUserTypeAdmin().withPgmReenter();

            MainMenuResponse painted = answerFor(outcome(paintedOptionLines(),
                    message80(MainMenuService.NO_ACCESS_MESSAGE),
                    MainMenuService.MAP_MESSAGE_COLOUR,
                    SPACE,
                    SPACE,
                    false,
                    administrator),
                    blankScreen(administrator, CicsAid.DFHENTER)).screen();

            assertThat(painted.errMsg())
                    .startsWith(MainMenuService.NO_ACCESS_MESSAGE)
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(painted.navigationContext().isAdmin())
                    .as("projected for an administrator, which no faithful filter would ever refuse - so "
                            + "the message can only have come from the stub")
                    .isTrue();
            assertThat(painted.optionLines())
                    .as("and the options are still all there: the controller neither suppresses nor "
                            + "re-composes them when a refusal is reported")
                    .containsExactlyElementsOf(paintedOptionLines());
        }

        @Test
        @DisplayName("the controller never consults MenuOptions and compares no user-type column")
        void theControllerNeverConsultsTheOptionTable() {
            assertThat(declaredTypesOfController())
                    .as("no field, no constructor parameter and no method parameter or return type of "
                            + "MainMenuController is the option table")
                    .doesNotContain(MenuOptions.class)
                    .doesNotContain(MainMenuService.MainMenuOptionTable.class);
            assertThat(MenuOptions.OPT_USRTYPE_LENGTH)
                    .as("the authorisation column is one byte of ordinary data - app/cpy/COMEN02Y.cpy:92 "
                            + "declares CDEMO-MENU-OPT-USRTYPE PIC X(01) - and the byte is compared in "
                            + "the service, exactly as line 137 compares it")
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH);
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(MainMenuResponse.OPTION_LINE_COUNT);
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT)
                    .isEqualTo(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT);
        }

        @Test
        @DisplayName("G41: the filter is authorisation over a data byte - no credential is involved")
        void nothingAuthenticates() {
            assertThat(payloadMemberNames())
                    .doesNotContain("passwd", "password", "secUsrPwd", "token", "authorization");
            assertThat(declaredTypesOfController()).doesNotContain(SecUserRecord.class);
        }
    }

    @Nested
    @DisplayName("ENTER versus REENTER (G38, G50) - both contexts carried, and no invented highlight")
    class EnterAndReenter {
        @Test
        @DisplayName("G50: CDEMO-PGM-ENTER and CDEMO-PGM-REENTER are both driven, true and false")
        void bothProgramContextConditionsAreDriven() {
            NavigationContext enter = NavigationContext.empty().withPgmEnter();
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();

            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();
            assertThat(enter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(reenter.isReenter()).isTrue();
            assertThat(reenter.isEnter()).isFalse();
            assertThat(reenter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);

            assertThat(answerFor(paintedOutcome(enter), blankScreen(enter, CicsAid.DFHENTER))
                    .screen().navigationContext().isEnter()).isTrue();
            assertThat(answerFor(paintedOutcome(reenter), blankScreen(reenter, CicsAid.DFHENTER))
                    .screen().navigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("ENTER: a freshly painted screen carries no message and reports the L89 clear")
        void theFirstEntryPathPaintsWithoutAMessage() {
            NavigationContext handedBack = NavigationContext.empty().withPgmReenter();

            ScreenResponse<MainMenuResponse> answer = answerFor(paintedOutcome(handedBack),
                    blankScreen(NavigationContext.empty().withPgmEnter(), CicsAid.DFHENTER));

            assertThat(answer.screen().errMsg())
                    .isEqualTo(SPACE.repeat(MainMenuResponse.ERR_MSG_LENGTH));
            assertThat(answer.screen().resetAllOutputFields())
                    .as("MOVE LOW-VALUES TO COMEN1AO ran, so the client clears its rendered screen")
                    .isTrue();
            assertThat(answer.screenMetadata().resetAllOutputFields()).isTrue();
            assertThat(answer.screen().navigationContext().isReenter()).isTrue();
            assertThat(answer.screen().optionLines())
                    .as("the option lines are painted on this path too - BUILD-MENU-OPTIONS runs inside "
                            + "SEND-MENU-SCREEN at :184-185")
                    .containsExactlyElementsOf(paintedOptionLines());
        }

        @Test
        @DisplayName("REENTER: a re-entry may carry a message, and sends without the LOW-VALUES clear")
        void theReEntryPathMayCarryAMessage() {
            ScreenResponse<MainMenuResponse> answer = answerFor(
                    reportingOutcome(MainMenuService.INVALID_OPTION_MESSAGE,
                            MainMenuService.MAP_MESSAGE_COLOUR),
                    blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));

            assertThat(answer.screen().errMsg())
                    .startsWith(MainMenuService.INVALID_OPTION_MESSAGE)
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(answer.screen().resetAllOutputFields())
                    .as("only the first-entry path runs MOVE LOW-VALUES")
                    .isFalse();
            assertThat(answer.screenMetadata().resetAllOutputFields()).isFalse();
        }

        @ParameterizedTest(name = "[{index}] notOk={0} blank={1} reenter={2}")
        @DisplayName("G38: the CSSETATY highlight applies only in REENTER, and '*' only when BLANK")
        @CsvSource({
                "true,  false, false, false, false",
                "true,  false, true,  true,  false",
                "false, true,  false, false, false",
                "false, true,  true,  true,  true",
                "false, false, true,  false, false",
                "false, false, false, false, false",
        })
        void theHighlightAppliesOnlyOnReEntry(final boolean notOk,
                final boolean blank,
                final boolean reenter,
                final boolean expectColour,
                final boolean expectAsterisk) {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter);

            assertThat(highlight.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(expectAsterisk);
            assertThat(highlight.untouched()).isEqualTo(!expectColour);
            if (expectColour) {
                assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            }
            if (expectAsterisk) {
                assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            }
        }

        @Test
        @DisplayName("B5: this screen invents no highlight, because COMEN01C copies no CSSETATY")
        void thisScreenAppliesNoHighlight() {
            assertThat(answerFor(paintedOutcome(NavigationContext.empty().withPgmEnter()))
                    .screenMetadata().fields()).isEmpty();
            assertThat(answerFor(reportingOutcome(MainMenuService.INVALID_OPTION_MESSAGE,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screenMetadata().fields()).isEmpty();
        }

        @Test
        @DisplayName("the message colour defaults to DFHRED and carries DFHGREEN when the service says so")
        void theMessageColourFollowsTheService() {
            assertThat(MainMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
            assertThat(MainMenuService.COMING_SOON_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHGREEN);

            ScreenResponse<MainMenuResponse> red =
                    answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter()));
            ScreenResponse<MainMenuResponse> green = answerFor(reportingOutcome(
                    COMING_SOON_OPTION_ONE, MainMenuService.COMING_SOON_MESSAGE_COLOUR));

            assertThat(red.screen().errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(red.screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            assertThat(green.screen().errMsgColor())
                    .as("passed through exactly as the service resolved it: the controller chooses no "
                            + "colour of its own")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(green.screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHGREEN));
        }

        @ParameterizedTest(name = "[{index}] AID 0x{0}")
        @DisplayName("the raw EIBAID byte travels uninterpreted - COMEN01C compares it inline at L93")
        @ValueSource(bytes = {CicsAid.DFHENTER, CicsAid.DFHPF3, CicsAid.DFHCLEAR, CicsAid.DFHPF4})
        void theRawAidByteTravelsUninterpreted(final byte aid) {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            ArgumentCaptor<MainMenuInput> captor = ArgumentCaptor.forClass(MainMenuInput.class);

            controllerOver(service).getMainMenu(
                    blankScreen(NavigationContext.empty().withPgmReenter(), aid));

            verify(service).handle(captor.capture());
            assertThat(captor.getValue().eibAid())
                    .as("the byte the terminal sent, not a decoded key token: resolving it here would "
                            + "risk making one key behave like another in a program that has no such "
                            + "behaviour")
                    .isEqualTo(aid);
            assertThat(PfKeyResolver.isAid(captor.getValue().eibAid(), aid))
                    .as("and it still resolves as itself, so nothing was lost in transit")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Navigation (G40) - XCTL as three response fields, and never a redirect")
    class Navigation {
        @Test
        @DisplayName("a painted screen names its own mapset and map - L190-L191")
        void aPaintedScreenNamesItsOwnMapAndMapset() {
            MainMenuResponse painted = paint();

            assertThat(painted.nextMapset())
                    .isEqualTo("COMEN01")
                    .isEqualTo(MainMenuService.MAPSET_NAME)
                    .hasSize(MainMenuResponse.NEXT_MAPSET_LENGTH);
            assertThat(painted.nextMap())
                    .isEqualTo("COMEN1A")
                    .isEqualTo(MainMenuService.MAP_NAME)
                    .hasSize(MainMenuResponse.NEXT_MAP_LENGTH);
            assertThat(painted.nextProgram())
                    .as("spaces, because the program returned to CICS rather than transferring")
                    .isBlank();
        }

        @ParameterizedTest(name = "[{index}] XCTL PROGRAM({0})")
        @DisplayName("the successor is echoed verbatim: the controller derives nothing")
        @ValueSource(strings = {"COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C", "COSGN00C"})
        void theSuccessorIsEchoedVerbatim(final String target) {
            NavigationContext context = NavigationContext.empty().withUserTypeUser().withPgmReenter();

            MainMenuResponse painted = answerFor(transferringOutcome(target, context),
                    blankScreen(context, CicsAid.DFHENTER)).screen();

            assertThat(painted.nextProgram())
                    .isEqualTo(target)
                    .hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            assertThat(painted.nextMapset())
                    .as("no map was sent on a transfer path, so the mapset and map are spaces")
                    .isBlank();
            assertThat(painted.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the no-COMMAREA transfer hands back NO communication area, so sign-on cold-starts")
        void theSignOnTransferCarriesNoCommunicationArea() {
            NavigationContext held = signOnHandoffContext().withPgmReenter();

            MainMenuResponse painted = answerFor(signOnTransferOutcome(held),
                    blankScreen(held, CicsAid.DFHPF3)).screen();

            assertThat(painted.navigationContext())
                    .as("stated absence: there is no area to carry forward")
                    .isNull();
            assertThat(painted.nextProgram())
                    .as("and the successor is still named, so the client knows where to go")
                    .isEqualTo(MainMenuService.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("the option transfer DOES hand back the area, because :152-155 names COMMAREA")
        void theOptionTransferStillCarriesTheArea() {
            NavigationContext held = signOnHandoffContext().withPgmReenter();

            MainMenuResponse painted = answerFor(transferringOutcome("COACTVWC", held),
                    blankScreen(held, CicsAid.DFHENTER)).screen();

            assertThat(painted.navigationContext())
                    .as("the option XCTL passes CARDDEMO-COMMAREA, so the client carries it on")
                    .isEqualTo(held);
        }

        @Test
        @DisplayName("the painted RETURN still hands back the area, because :107-110 names COMMAREA")
        void thePaintedReturnStillCarriesTheArea() {
            NavigationContext held = signOnHandoffContext().withPgmReenter();

            MainMenuOutcome painted = paintedOutcome(held);
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
            NavigationContext held = signOnHandoffContext().withPgmReenter();
            ObjectMapper mapper = productionMapper();

            String body = mapper.writeValueAsString(answerFor(signOnTransferOutcome(held),
                    blankScreen(held, CicsAid.DFHPF3)));

            assertThat(envelopeOf(body).has("navigationContext")).isTrue();
            assertThat(envelopeOf(body).get("navigationContext").isNull()).isTrue();
        }

        @Test
        @DisplayName("a client following the transfer reaches sign-on's EIBCALEN = 0 cold start")
        void theClientFollowingTheTransferReachesTheColdStart() throws Exception {
            NavigationContext held = signOnHandoffContext().withPgmReenter();
            ObjectMapper mapper = productionMapper();

            String body = mapper.writeValueAsString(answerFor(signOnTransferOutcome(held),
                    blankScreen(held, CicsAid.DFHPF3)));
            NavigationContext carriedForward = mapper.treeToValue(
                    envelopeOf(body).get("navigationContext"), NavigationContext.class);

            assertThat(carriedForward).isNull();
            assertThat(new com.vsergeychik.carddemo.user.SignOnService.SignOnInput(carriedForward,
                    CicsAid.DFHENTER, null, null).isCommareaPresent())
                    .as("EIBCALEN = 0, which app/cbl/COSGN00C.cbl:80-83 answers with the cold start")
                    .isFalse();
        }

        @Test
        @DisplayName("all ten option targets are covered, and the eleventh is the sign-on return")
        void theTargetInventoryIsComplete() {
            assertThat(MENU_OPT_PROGRAMS)
                    .hasSize(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT)
                    .doesNotHaveDuplicates()
                    .doesNotContain(MainMenuService.SIGNON_PROGRAM);
            assertThat(MainMenuService.SIGNON_PROGRAM)
                    .isEqualTo("COSGN00C")
                    .isEqualTo(MainMenuResponse.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("G37: the outbound communication area is the 160-byte COMMAREA, carried in payload")
        void theOutboundContextIsTheWholeCommarea() {
            NavigationContext context = signOnHandoffContext().withPgmReenter();

            NavigationContext echoed = answerFor(paintedOutcome(context),
                    blankScreen(context, CicsAid.DFHENTER)).screen().navigationContext();

            assertThat(echoed).isEqualTo(context);
            assertThat(echoed.toFixedWidth(CODEC))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(echoed.lastMap())
                    .as("CDEMO-LAST-MAP is X(7), not X(8) - app/cpy/COCOM01Y.cpy:43")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(echoed.lastMapset())
                    .as("CDEMO-LAST-MAPSET is X(7), not X(8) - app/cpy/COCOM01Y.cpy:44")
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
        }

        @Test
        @DisplayName("the 'U'-role sign-on context is accepted, and the ENTER path is rendered")
        void theSignOnHandoffContextIsAccepted() {
            NavigationContext handoff = signOnHandoffContext();
            assertThat(handoff.fromProgram()).isEqualTo("COSGN00C");
            assertThat(handoff.userType()).isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(handoff.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(handoff.isEnter()).isTrue();

            ScreenResponse<MainMenuResponse> answer = answerFor(
                    paintedOutcome(handoff.withPgmReenter()),
                    blankScreen(handoff, CicsAid.DFHENTER));

            assertThat(answer.screen().errMsg())
                    .as("the first-entry paint carries no message")
                    .isEqualTo(SPACE.repeat(MainMenuResponse.ERR_MSG_LENGTH));
            assertThat(answer.screen().resetAllOutputFields()).isTrue();
            assertThat(answer.screen().navigationContext().fromProgram()).isEqualTo("COSGN00C");
            assertThat(answer.screen().navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(answer.screen().optionLines())
                    .containsExactlyElementsOf(paintedOptionLines());
        }
    }

    @Nested
    @DisplayName("The wire - the screen stays flat, the padding survives, and nothing is stashed")
    class TheWire {
        private MockMvc mockMvcOver(final MainMenuService service) {
            return MockMvcBuilders.standaloneSetup(controllerOver(service)).build();
        }

        private MockMvc mockMvcReturning(final MainMenuOutcome outcome) {
            MainMenuService service = stubbedService();
            doReturn(outcome).when(service).handle(any(MainMenuInput.class));
            return mockMvcOver(service);
        }

        @Test
        @DisplayName("a GET with no body answers the cold start over HTTP - L82")
        void aGetWithNoBodyAnswersTheColdStart() throws Exception {
            mockMvcReturning(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(MainMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmname").value(MainMenuResponse.PROGRAM_NAME));
        }

        @Test
        @DisplayName("the twenty items stay at the top level and screenMetadata is one sibling member")
        void theScreenStaysFlat() throws Exception {
            MvcResult result = mockMvcReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            ObjectNode envelope = envelopeOf(result.getResponse().getContentAsString());
            for (String member : payloadWireNames()) {
                assertThat(envelope.has(member))
                        .as("%s traces to a named DFHMDF field, so it is a top-level member", member)
                        .isTrue();
            }
            assertThat(envelope.has(SCREEN_METADATA_MEMBER))
                    .as("the presentation values COMEN01C sets that are NOT DFHMDF fields travel beside "
                            + "the screen, not inside it")
                    .isTrue();
            assertThat(envelope.has("screen"))
                    .as("the envelope unwraps the screen, so there is no nesting level to unwrap client "
                            + "side")
                    .isFalse();
            assertThat(envelope.has("navigationContext")).isTrue();
        }

        @Test
        @DisplayName("space-padded PIC X(n) values survive the round trip untrimmed and unomitted")
        void thePaddingSurvivesTheRoundTrip() throws Exception {
            MvcResult result = mockMvcReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.errmsg")
                            .value(SPACE.repeat(MainMenuResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.optn011").exists())
                    .andExpect(jsonPath("$.optn012").exists())
                    .andReturn();

            MainMenuResponse round = screenFromWire(result.getResponse().getContentAsString());

            assertThat(round.title01())
                    .as("forty characters with leading AND trailing spaces, all of them intact")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(MainMenuResponse.TITLE_LENGTH);
            assertThat(round.errMsg())
                    .as("seventy-eight spaces: present, not null, and not the empty string")
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(MainMenuResponse.ERR_MSG_LENGTH);
            assertThat(round.optn011())
                    .as("an unwritten slot is spaces and is NOT dropped from the payload")
                    .isNotNull()
                    .hasSize(MainMenuResponse.OPTION_LINE_LENGTH);
        }

        @Test
        @DisplayName("B5: the trailing space and the 'Accountis' defect survive the full round trip")
        void theTwoTextualQuirksSurviveTheRoundTrip() throws Exception {
            MvcResult refusal = mockMvcReturning(reportingOutcome(MainMenuService.NO_ACCESS_MESSAGE,
                    MainMenuService.MAP_MESSAGE_COLOUR))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            MvcResult comingSoon = mockMvcReturning(reportingOutcome(COMING_SOON_OPTION_ONE,
                    MainMenuService.COMING_SOON_MESSAGE_COLOUR))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            String refusalText =
                    screenFromWire(refusal.getResponse().getContentAsString()).errMsg();
            String comingSoonText =
                    screenFromWire(comingSoon.getResponse().getContentAsString()).errMsg();

            assertThat(refusalText.substring(0, MainMenuService.NO_ACCESS_MESSAGE.length()))
                    .as("the trailing space inside app/cbl/COMEN01C.cbl:140's literal is still there")
                    .isEqualTo(MainMenuService.NO_ACCESS_MESSAGE)
                    .endsWith(SPACE);
            assertThat(comingSoonText)
                    .as("no trimming, no normalising and no re-joining anywhere in the path")
                    .contains("Accountis")
                    .doesNotContain("Account is");
        }

        @Test
        @DisplayName("member names are the DTO's own: no snake_case, no kebab-case, no re-capitalising")
        void memberNamesAreUntransformed() throws Exception {
            MvcResult result = mockMvcReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .contains("\"trnname\"", "\"curdate\"", "\"pgmname\"", "\"curtime\"",
                            "\"errmsg\"", "\"optn001\"", "\"nextMapset\"")
                    .doesNotContain("\"trn_name\"", "\"cur-date\"", "\"TrnName\"", "\"PgmName\"",
                            "\"err_msg\"", "\"next_mapset\"");
        }

        @Test
        @DisplayName("G9: no length, flag or attribute item reaches the wire under any spelling")
        void noMetadataItemReachesTheWire() throws Exception {
            MvcResult result = mockMvcReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andReturn();

            String screenJson = screenNodeOf(result.getResponse().getContentAsString()).toString();
            assertThat(screenJson)
                    .as("app/cpy-bms/COMEN01.CPY's xxxL, xxxF and xxxA items on the input side, and the "
                            + "xxxC, xxxP, xxxH and xxxV items in the output group at :139-:260, are "
                            + "validation and highlight metadata and never payload")
                    .doesNotContain("\"optionL\"", "\"optionF\"", "\"optionA\"",
                            "\"errMsgL\"", "\"errMsgF\"", "\"errMsgA\"",
                            "\"errMsgC\"", "\"errMsgP\"", "\"errMsgH\"", "\"errMsgV\"",
                            "\"trnNameL\"", "\"title01A\"", "\"curDateH\"", "\"optn001V\"");
            assertThat(screenJson)
                    .as("the ERRMSGC colour byte and the L89 repaint signal are @JsonIgnore on the screen: "
                            + "they are not DFHMDF fields, so they never appear as screen members")
                    .doesNotContain("\"errMsgColor\"", "\"resetAllOutputFields\"");

            ObjectNode envelope = envelopeOf(result.getResponse().getContentAsString());
            assertThat(envelope.get(SCREEN_METADATA_MEMBER).has("resetAllOutputFields"))
                    .as("both instead travel in the metadata envelope, which is the only way they can "
                            + "reach a client at all")
                    .isTrue();
            assertThat(envelope.get(SCREEN_METADATA_MEMBER).has("messageColour")).isTrue();
        }

        @Test
        @DisplayName("G37: no HttpSession is created and no JSESSIONID is set")
        void noSessionIsCreated() throws Exception {
            MvcResult result = mockMvcReturning(
                    paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("COMEN01C is pseudo-conversational: the COMMAREA, the AID and the ENTER/REENTER "
                            + "context all travel in the payload, so no server-side session exists")
                    .isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }

        @Test
        @DisplayName("the wire is UTF-8 JSON, and never a redirect - XCTL is a field, not a 3xx")
        void theWireIsJsonAndNeverARedirect() throws Exception {
            MvcResult result = mockMvcReturning(transferringOutcome(MENU_OPT_PROGRAMS.get(0),
                    NavigationContext.empty().withUserTypeUser().withPgmReenter()))
                    .perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(MENU_OPT_PROGRAMS.get(0)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getRedirectedUrl())
                    .as("EXEC CICS XCTL becomes a response field the client acts on; there is no "
                            + "server-side forward and no redirect chain")
                    .isNull();
            assertThat(result.getResponse().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
            assertThat(result.getResponse().getContentAsString())
                    .as("HTTP and JSON are UTF-8; IBM037 and US-ASCII belong to dataset input and "
                            + "output, which this screen performs none of")
                    .isEqualTo(new String(result.getResponse().getContentAsByteArray(),
                            StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("a request carrying a payload reaches the service with that payload's own values")
        void aRequestCarryingAPayloadIsBound() throws Exception {
            MainMenuService service = stubbedService();
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));
            ArgumentCaptor<MainMenuInput> captor = ArgumentCaptor.forClass(MainMenuInput.class);
            NavigationContext sent = signOnHandoffContext();

            mockMvcOver(service)
                    .perform(get(MainMenuController.MAIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(withOption("07", sent))))
                    .andExpect(status().isOk());

            verify(service).handle(captor.capture());
            assertThat(captor.getValue().option()).isEqualTo("07");
            assertThat(captor.getValue().navigationContext()).isNotNull();
            assertThat(captor.getValue().navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(captor.getValue().navigationContext().fromProgram())
                    .isEqualTo(MainMenuService.SIGNON_PROGRAM);
        }
    }

    @TestConfiguration
    static class SliceCollaborators {
        @Bean
        MainMenuService mainMenuService() {
            return new MainMenuService(bindings());
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Nested
    @WebMvcTest(MainMenuController.class)
    @ActiveProfiles("test")
    @Import({SliceCollaborators.class, CobolCharsetConfig.class})
    @DisplayName("The Spring MVC slice - real dispatcher, real WebConfig, stubbed decision core")
    class TheSpringSlice {
        @MockitoSpyBean
        private MainMenuService service;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("the real dispatcher maps GET /api/menu onto this controller")
        void theRealDispatcherMapsTheRoute() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));

            mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(
                                    blankScreen(signOnHandoffContext(), CicsAid.DFHENTER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(MainMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmname").value(MainMenuResponse.PROGRAM_NAME))
                    .andExpect(jsonPath("$.nextMapset").value(MainMenuResponse.MAPSET_NAME))
                    .andExpect(jsonPath("$.nextMap").value(MainMenuResponse.MAP_NAME));

            assertThat(mockingDetails(service).isSpy()).isTrue();
            verify(service).handle(any(MainMenuInput.class));
        }

        @Test
        @DisplayName("B7: the fixed clock really is the one in the path, so the header is assertable")
        void theFixedClockIsInThePath() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));

            assertThat(context.getBean(WebConfig.class))
                    .as("WebConfig is a WebMvcConfigurer, so the slice picks it up with its Jackson "
                            + "customisation and its @RestControllerAdvice")
                    .isNotNull();
            assertThat(context.getBean(Clock.class).instant()).isEqualTo(FIXED_INSTANT);

            mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.curdate").value(EXPECTED_CURDATE))
                    .andExpect(jsonPath("$.curtime").value(EXPECTED_CURTIME));
        }

        @Test
        @DisplayName("the configured mapper keeps the padding: 78 spaces travel as 78 spaces")
        void theConfiguredMapperKeepsThePadding() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));

            mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(SPACE.repeat(MainMenuResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.optn001").value(menuLine(1)))
                    .andExpect(jsonPath("$.optn008").value(menuLine(8)))
                    .andExpect(jsonPath("$.optn011").exists())
                    .andExpect(jsonPath("$.optn012").exists());
        }

        @Test
        @DisplayName("Bean Validation rejects an option wider than the map's LENGTH=2")
        void beanValidationRejectsAnOverLongOption() throws Exception {
            String tooWide = "1".repeat(MainMenuRequest.OPTION_LENGTH + 1);

            mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(withOption(tooWide,
                                    NavigationContext.empty().withPgmReenter()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("option"));

            verify(service, never()).handle(any(MainMenuInput.class));
        }

        @Test
        @DisplayName("G37 and G41: the real slice creates no session and mounts no security filter")
        void theRealSliceIsStatelessAndUnsecured() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));

            MvcResult result = mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH))
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

        @Test
        @DisplayName("B6: the security-user record is on the classpath and nothing here reads one")
        void theSecurityUserRecordIsPresentButUnread() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("app/cpy/CSUSR01Y.cpy declares eighty bytes")
                    .isEqualTo(80);
            assertThat(context.getBeanNamesForType(MainMenuService.class)).hasSize(1);
            assertThat(context.containsBean("secUserRepository"))
                    .as("no security-user repository is wired into this slice, because there is no "
                            + "repository call site to wire it for (gate G47 has no subject here)")
                    .isFalse();
        }
    }
}
