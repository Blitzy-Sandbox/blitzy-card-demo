package com.vsergeychik.carddemo.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.billing.BillPaymentController.Invocation;
import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.WebConfig;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The web-boundary suite for {@link BillPaymentController} - CSD transaction {@code CB00}, program
 * {@code app/cbl/COBIL00C.cbl} (572 lines), projected onto the single endpoint {@code POST /api/billpay}.
 */
@DisplayName("BillPaymentController - COBIL00C, the CB00 bill-payment screen, POST /api/billpay")
class BillPaymentControllerTest {
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final String EXPECTED_CUR_DATE = "07/19/22";

    private static final String EXPECTED_CUR_TIME = "23:12:32";

    private static final String ACCT_KEY = "00000000011";

    private static final String ACCT_KEY_IN_CARRIER = "00000000011     ";

    private static final String EDITED_BALANCE = "+0000000019.40";

    private static final Map<String, Integer> MAP_FIELD_WIDTHS = mapFieldWidths();

    private static final Set<String> RESPONSE_MEMBERS = Set.of(
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime",
            "actidin", "curbal", "confirm", "errmsg",
            "navigationContext", "nextProgram", "nextMapset", "nextMap",
            "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg", "trnSelected");

    private static final Set<String> REQUEST_MEMBERS = Set.of(
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime",
            "actidin", "curbal", "confirm", "errmsg",
            "navigationContext", "aid",
            "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg", "trnSelected");

    private static final Set<String> COMMAREA_MEMBERS = Set.of(
            "fromTranid", "fromProgram", "toTranid", "toProgram", "userId", "userType", "pgmContext",
            "custId", "custFname", "custMname", "custLname",
            "acctId", "acctStatus", "cardNum", "lastMap", "lastMapset");

    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    private FixedWidthCodec codec;

    private BillPaymentService service;

    private BillPaymentController controller;

    @BeforeEach
    void setUp() {
        codec = new FixedWidthCodec(CHARSET);
        service = mock(BillPaymentService.class);
        when(service.codec()).thenReturn(codec);
        installScreenParagraphs(service, codec);
        controller = new BillPaymentController(service, FIXED_CLOCK);
    }

    private static Map<String, Integer> mapFieldWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnname", BillPaymentResponse.TRN_NAME_LENGTH);
        widths.put("title01", BillPaymentResponse.TITLE01_LENGTH);
        widths.put("curdate", BillPaymentResponse.CUR_DATE_LENGTH);
        widths.put("pgmname", BillPaymentResponse.PGM_NAME_LENGTH);
        widths.put("title02", BillPaymentResponse.TITLE02_LENGTH);
        widths.put("curtime", BillPaymentResponse.CUR_TIME_LENGTH);
        widths.put("actidin", BillPaymentResponse.ACT_ID_IN_LENGTH);
        widths.put("curbal", BillPaymentResponse.CUR_BAL_LENGTH);
        widths.put("confirm", BillPaymentResponse.CONFIRM_LENGTH);
        widths.put("errmsg", BillPaymentResponse.ERR_MSG_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    private static void installScreenParagraphs(BillPaymentService target,
                                                FixedWidthCodec targetCodec) {
        doAnswer(invocation -> {
            sendBillpayScreen(invocation.getArgument(0), targetCodec);
            return null;
        }).when(target).sendBillpayScreen(any(PaymentState.class));

        doAnswer(invocation -> {
            PaymentState state = invocation.getArgument(0);
            initializeAllFields(state);
            sendBillpayScreen(state, targetCodec);
            return null;
        }).when(target).clearCurrentScreen(any(PaymentState.class));

        doAnswer(invocation -> {
            PaymentState state = invocation.getArgument(0);
            state.setErrFlagOn();
            state.setMessage(SystemMessages.CCDA_MSG_INVALID_KEY);
            sendBillpayScreen(state, targetCodec);
            return null;
        }).when(target).invalidKeyPressed(any(PaymentState.class));
    }

    private static void sendBillpayScreen(PaymentState state, FixedWidthCodec targetCodec) {
        state.setErrMsg(targetCodec.movePicX(state.message(), BillPaymentResponse.ERR_MSG_LENGTH));
        state.recordSend();
    }

    private static void initializeAllFields(PaymentState state) {
        state.setCursorField(CursorField.ACTIDIN);
        state.setActIdIn(spaces(BillPaymentResponse.ACT_ID_IN_LENGTH));
        state.setCurBal(spaces(BillPaymentResponse.CUR_BAL_LENGTH));
        state.setConfirm(spaces(BillPaymentResponse.CONFIRM_LENGTH));
        state.setMessageSpaces();
    }

    private static void installEnterKeyOutcome(BillPaymentService target,
                                               FixedWidthCodec targetCodec,
                                               Consumer<PaymentState> painter) {
        when(target.processEnterKey(any(), any(), any(NavigationContext.class)))
                .thenAnswer(invocation -> {
                    PaymentState outcome =
                            new PaymentState(targetCodec, invocation.getArgument(2));
                    outcome.setActIdIn(invocation.getArgument(0));
                    outcome.setConfirm(invocation.getArgument(1));
                    painter.accept(outcome);
                    sendBillpayScreen(outcome, targetCodec);
                    return outcome;
                });
    }

    private void stubEnterKeyOutcome(Consumer<PaymentState> painter) {
        installEnterKeyOutcome(service, codec, painter);
    }

    private static Consumer<PaymentState> paidOutcome(String tranId) {
        return state -> {
            state.setCurBal(EDITED_BALANCE);
            state.setMessageHighlight(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN);
            state.setMessage(BillPaymentService.SUCCESS_PREFIX
                    + BillPaymentService.SUCCESS_INFIX + tranId
                    + BillPaymentService.SUCCESS_SUFFIX);
            state.setCursorField(CursorField.NONE);
        };
    }

    private static Consumer<PaymentState> unconfirmedOutcome() {
        return state -> {
            state.setCurBal(EDITED_BALANCE);
            state.setMessage(BillPaymentService.MSG_CONFIRM_TO_PAY);
            state.setCursorField(CursorField.CONFIRM);
        };
    }

    private static Consumer<PaymentState> emptyAccountIdOutcome() {
        return state -> {
            state.setErrFlagOn();
            state.setMessage(BillPaymentService.MSG_ACCT_ID_EMPTY);
            state.setCursorField(CursorField.ACTIDIN);
        };
    }

    private static BillPaymentRequest firstEntry() {
        BillPaymentRequest request = new BillPaymentRequest();
        request.setNavigationContext(NavigationContext.empty());
        return request;
    }

    private static BillPaymentRequest reentry(String aid, String actIdIn, String confirm) {
        BillPaymentRequest request = new BillPaymentRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(aid);
        request.setActIdIn(actIdIn);
        request.setConfirm(confirm);
        return request;
    }

    private static NavigationContext populatedCommarea() {
        return NavigationContext.empty()
                .withFromTranid("CM00")
                .withFromProgram("COMEN01C")
                .withToTranid("CB00")
                .withToProgram("COBIL00C")
                .withUserId("USER0001")
                .withUserTypeUser()
                .withCustId(9)
                .withCustFname("JOHN")
                .withCustMname("Q")
                .withCustLname("PUBLIC")
                .withAcctId(11L)
                .withAcctStatus("Y")
                .withCardNum(4111111111111111L)
                .withLastMap("COMEN1A")
                .withLastMapset("COMEN01");
    }

    private static BillPaymentRequest withExtension(BillPaymentRequest request) {
        request.setTrnIdFirst("0000000000000001");
        request.setTrnIdLast("0000000000000099");
        request.setPageNum(7);
        request.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);
        request.setTrnSelFlg("S");
        request.setTrnSelected(ACCT_KEY_IN_CARRIER);
        return request;
    }

    private static ObjectMapper productionMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        Jackson2ObjectMapperBuilderCustomizer customizer =
                new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET);
        customizer.customize(builder);
        return builder.build();
    }

    private MockMvc mockMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    @Nested
    @DisplayName("construction - two collaborators, constructor injection only")
    class Construction {
        @Test
        @DisplayName("the decision core is required, because this class makes no decision itself")
        void serviceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(null, FIXED_CLOCK))
                    .withMessageContaining("BillPaymentService");
        }

        @Test
        @DisplayName("the clock is required, because POPULATE-HEADER-INFO must be assertable")
        void clockIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(service, null))
                    .withMessageContaining("Clock");
        }

        @Test
        @DisplayName("a collaborator answering no codec is refused at construction, not at first use")
        void codecIsRequired() {
            BillPaymentService codecless = mock(BillPaymentService.class);
            when(codecless.codec()).thenReturn(null);

            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(codecless, FIXED_CLOCK))
                    .withMessageContaining("codec");
        }

        @Test
        @DisplayName("the identity constants come from the source's own definitions, not from retyping")
        void identityConstants() {
            assertThat(BillPaymentController.BILL_PAY_PATH).isEqualTo("/api/billpay");
            assertThat(BillPaymentController.PROGRAM_NAME).isEqualTo("COBIL00C");
            assertThat(BillPaymentController.TRANSACTION_ID).isEqualTo("CB00");
            assertThat(BillPaymentController.MAPSET_NAME).isEqualTo("COBIL00");
            assertThat(BillPaymentController.MAP_NAME).isEqualTo("COBIL0A");
            assertThat(BillPaymentController.SIGN_ON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(BillPaymentController.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("a null payload is refused rather than silently read as a cold start")
        void nullPayloadIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> controller.mainPara(null))
                    .withMessageContaining("payload is required");
        }

        @Test
        @DisplayName("the Invocation carrier refuses a half-built outcome")
        void invocationRefusesAHalfBuiltOutcome() {
            BillPaymentResponse response = new BillPaymentResponse();
            PaymentState state = new PaymentState(codec, NavigationContext.empty());

            assertThatNullPointerException()
                    .isThrownBy(() -> new Invocation(null, state))
                    .withMessageContaining("response is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Invocation(response, null))
                    .withMessageContaining("PaymentState is required");
            assertThat(new Invocation(response, state).response()).isSameAs(response);
            assertThat(new Invocation(response, state).state()).isSameAs(state);
        }
    }

    @Nested
    @DisplayName("routing - transaction CB00 is exactly one verb on exactly one path")
    class Routing {
        @Test
        @DisplayName("POST /api/billpay is answered, with JSON produced")
        void postIsAnswered() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            ObjectMapper mapper = productionMapper();

            mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.trnname").value(BillPaymentResponse.TRANSACTION_ID));
        }

        @ParameterizedTest(name = "{0} /api/billpay is not answered")
        @DisplayName("no other verb on this path is answered")
        @ValueSource(strings = {"GET", "PUT", "DELETE"})
        void noOtherVerbIsAnswered(String verb) throws Exception {
            MockMvc mvc = mockMvc(productionMapper());

            switch (verb) {
                case "GET" -> mvc.perform(get(BillPaymentController.BILL_PAY_PATH))
                        .andExpect(status().isMethodNotAllowed());
                case "PUT" -> mvc.perform(put(BillPaymentController.BILL_PAY_PATH))
                        .andExpect(status().isMethodNotAllowed());
                default -> mvc.perform(delete(BillPaymentController.BILL_PAY_PATH))
                        .andExpect(status().isMethodNotAllowed());
            }

            verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
        }

        @Test
        @DisplayName("no other path on this controller is answered")
        void noOtherPathIsAnswered() throws Exception {
            mockMvc(productionMapper())
                    .perform(post(BillPaymentController.BILL_PAY_PATH + "/confirm"))
                    .andExpect(status().isNotFound());
            mockMvc(productionMapper())
                    .perform(post("/api/billpay/" + ACCT_KEY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a body that is not JSON is refused, because only JSON can carry the map")
        void aNonJsonBodyIsRefused() throws Exception {
            mockMvc(productionMapper()).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("ACTIDIN=" + ACCT_KEY))
                    .andExpect(status().isUnsupportedMediaType());

            verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
        }

        @Test
        @DisplayName("the class declares exactly one request-mapped method, and it is a POST")
        void exactlyOneMappedMethod() {
            List<Class<? extends Annotation>> mappings = List.of(RequestMapping.class,
                    GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class,
                    PatchMapping.class);
            List<Method> mapped = new ArrayList<>();
            for (Method method : BillPaymentController.class.getDeclaredMethods()) {
                for (Class<? extends Annotation> mapping : mappings) {
                    if (method.isAnnotationPresent(mapping)) {
                        mapped.add(method);
                        break;
                    }
                }
            }

            assertThat(mapped)
                    .as("one BMS map, one attention identifier, one route")
                    .hasSize(1);
            Method handler = mapped.get(0);
            assertThat(handler.getName()).isEqualTo("payBill");
            assertThat(handler.getReturnType()).isEqualTo(ScreenResponse.class);
            PostMapping mapping = handler.getAnnotation(PostMapping.class);
            assertThat(mapping.path()).containsExactly(BillPaymentController.BILL_PAY_PATH);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA - :99-149, the ordered dispatch")
    class MainParaDispatch {
        @Nested
        @DisplayName("IF EIBCALEN = 0 - :107-109, the cold start")
        class ColdStart {
            @Test
            @DisplayName("a payload with no communication area transfers to COSGN00C")
            void transfersToSignOn() {
                BillPaymentRequest request = new BillPaymentRequest();

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
                assertThat(response.getNavigationContext().toProgram())
                        .isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
            }

            @Test
            @DisplayName("the decision core is never consulted, because :107 diverts before :112")
            void consultsNoDecisionCore() {
                controller.mainPara(new BillPaymentRequest());

                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                verify(service, never()).sendBillpayScreen(any(PaymentState.class));
                verify(service, never()).clearCurrentScreen(any(PaymentState.class));
                verify(service, never()).invalidKeyPressed(any(PaymentState.class));
            }

            @Test
            @DisplayName("no screen is sent, so only :105 has written to the map")
            void sendsNoScreen() {
                Invocation invocation = controller.mainPara(new BillPaymentRequest());

                assertThat(invocation.state().screensSent()).isZero();
                assertThat(invocation.response().getErrMsg())
                        .isEqualTo(spaces(BillPaymentResponse.ERR_MSG_LENGTH));
                assertThat(invocation.response().getActIdIn())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.ACT_ID_IN_LENGTH));
                assertThat(invocation.response().getCurBal())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.CUR_BAL_LENGTH));
                assertThat(invocation.response().getConfirm())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.CONFIRM_LENGTH));
                assertThat(invocation.response().getNextMapset()).isBlank();
                assertThat(invocation.response().getNextMap()).isBlank();
            }

            @Test
            @DisplayName("POPULATE-HEADER-INFO never runs, so the header stays unpainted")
            void leavesTheHeaderUnpainted() {
                BillPaymentResponse response = controller.mainPara(new BillPaymentRequest()).response();

                assertThat(response.getTitle01())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.TITLE01_LENGTH));
                assertThat(response.getTitle02())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.TITLE02_LENGTH));
                assertThat(response.getTrnName())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.TRN_NAME_LENGTH));
                assertThat(response.getPgmName())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.PGM_NAME_LENGTH));
                assertThat(response.getCurDate())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.CUR_DATE_LENGTH));
                assertThat(response.getCurTime())
                        .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.CUR_TIME_LENGTH));
            }

            @Test
            @DisplayName("the extension is not echoed, because :111 never loaded a communication area")
            void doesNotEchoTheExtension() {
                BillPaymentRequest request = withExtension(new BillPaymentRequest());

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getTrnIdFirst()).isBlank()
                        .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
                assertThat(response.getTrnIdLast()).isBlank()
                        .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
                assertThat(response.getTrnSelFlg()).isBlank()
                        .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
                assertThat(response.getTrnSelected()).isBlank()
                        .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
                assertThat(response.getPageNum()).isZero();
                assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
            }
        }

        @Nested
        @DisplayName("IF NOT CDEMO-PGM-REENTER - :112-122, first entry")
        class FirstEntry {
            @Test
            @DisplayName(":113 advances the context, so the next keystroke takes the other branch")
            void advancesTheContext() {
                BillPaymentRequest request = firstEntry();
                assertThat(request.getNavigationContext().isEnter()).isTrue();

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getNavigationContext().isReenter()).isTrue();
                assertThat(response.getNavigationContext().pgmContext())
                        .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            }

            @Test
            @DisplayName(":114 clears the map and :115 parks the cursor in ACTIDIN")
            void clearsTheMapAndParksTheCursor() {
                BillPaymentResponse response = controller.mainPara(firstEntry()).response();

                assertThat(response.getActIdIn())
                        .isEqualTo(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
                assertThat(response.getCurBal())
                        .isEqualTo(lowValues(BillPaymentResponse.CUR_BAL_LENGTH));
                assertThat(response.getConfirm())
                        .isEqualTo(lowValues(BillPaymentResponse.CONFIRM_LENGTH));
                assertThat(response.getErrMsg())
                        .isEqualTo(spaces(BillPaymentResponse.ERR_MSG_LENGTH));
                assertThat(response.getCursorField()).isEqualTo(CursorField.ACTIDIN);
            }

            @Test
            @DisplayName(":122 sends once and consults no decision core when nothing was selected")
            void sendsOnceWithNoSelection() {
                Invocation invocation = controller.mainPara(firstEntry());

                assertThat(invocation.state().screensSent()).isEqualTo(1);
                verify(service, times(1)).sendBillpayScreen(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @ParameterizedTest(name = "a selection of {0} blanks performs no lookup")
            @DisplayName(":116-117 a selection equal to a figurative constant performs no lookup")
            @ValueSource(ints = {1, 8, 16})
            void aBlankSelectionPerformsNoLookup(int blankWidth) {
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(spaces(blankWidth));

                controller.mainPara(request);

                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @Test
            @DisplayName(":116-117 a LOW-VALUES selection performs no lookup either")
            void aLowValuesSelectionPerformsNoLookup() {
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(lowValues(BillPaymentRequest.TRN_SELECTED_LENGTH));

                controller.mainPara(request);

                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @Test
            @DisplayName(":116-117 an absent selection performs no lookup either")
            void anAbsentSelectionPerformsNoLookup() {
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(null);

                controller.mainPara(request);

                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @Test
            @DisplayName(":118-120 a selected transaction seeds ACTIDIN and is processed at once")
            void aSelectedTransactionIsProcessed() {
                stubEnterKeyOutcome(unconfirmedOutcome());
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(ACCT_KEY_IN_CARRIER);

                Invocation invocation = controller.mainPara(request);

                verify(service, times(1)).processEnterKey(eq(ACCT_KEY),
                        eq(lowValues(BillPaymentResponse.CONFIRM_LENGTH)),
                        eq(NavigationContext.empty().withPgmReenter()));
                assertThat(invocation.response().getActIdIn()).isEqualTo(ACCT_KEY);
                assertThat(invocation.response().getCurBal()).isEqualTo(EDITED_BALANCE);
            }

            @Test
            @DisplayName("the source sends TWICE on that path - :242 inside, then :122 - and both stand")
            void theSelectedTransactionPathSendsTwice() {
                stubEnterKeyOutcome(unconfirmedOutcome());
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(ACCT_KEY_IN_CARRIER);

                Invocation invocation = controller.mainPara(request);

                assertThat(invocation.state().screensSent()).isEqualTo(2);
                verify(service, times(1)).sendBillpayScreen(any(PaymentState.class));
            }

            @Test
            @DisplayName(":114 leaves CONFIRMI at LOW-VALUES, so the switch reads rather than pays")
            void confirmStaysAtLowValues() {
                stubEnterKeyOutcome(unconfirmedOutcome());
                BillPaymentRequest request = firstEntry();
                request.setTrnSelected(ACCT_KEY_IN_CARRIER);
                request.setConfirm("Y");

                controller.mainPara(request);

                verify(service).processEnterKey(eq(ACCT_KEY),
                        eq(lowValues(BillPaymentResponse.CONFIRM_LENGTH)),
                        any(NavigationContext.class));
            }
        }

        @Nested
        @DisplayName("EVALUATE EIBAID - :125-142, four arms in the source's order")
        class KeyDispatch {
            @Test
            @DisplayName("DFHENTER at :126-127 processes the screen and sends nothing of its own")
            void enterProcessesTheScreen() {
                stubEnterKeyOutcome(unconfirmedOutcome());

                Invocation invocation =
                        controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "));

                verify(service, times(1)).processEnterKey(eq(ACCT_KEY), eq(" "),
                        eq(NavigationContext.empty().withPgmReenter()));
                verify(service, never()).sendBillpayScreen(any(PaymentState.class));
                assertThat(invocation.state().screensSent()).isEqualTo(1);
                assertThat(invocation.response().getCurBal()).isEqualTo(EDITED_BALANCE);
            }

            @Test
            @DisplayName("the received map reaches the decision core at the declared widths")
            void theReceivedMapReachesTheCoreAtDeclaredWidths() {
                stubEnterKeyOutcome(unconfirmedOutcome());

                controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), "1", ""));

                verify(service).processEnterKey(eq("1          "), eq(" "),
                        any(NavigationContext.class));
            }

            @Test
            @DisplayName("DFHPF3 at :129-130 falls back to COMEN01C when CDEMO-FROM-PROGRAM is blank")
            void pf3FallsBackToTheMainMenu() {
                BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " ");

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                verify(service, never()).sendBillpayScreen(any(PaymentState.class));
            }

            @Test
            @DisplayName("DFHPF3 at :132-133 returns to CDEMO-FROM-PROGRAM when one is named")
            void pf3ReturnsToTheCaller() {
                BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " ");
                request.setNavigationContext(
                        populatedCommarea().withPgmReenter().withFromProgram("COMEN01C"));

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @Test
            @DisplayName("DFHPF3 with a LOW-VALUES caller also falls back to COMEN01C")
            void pf3WithALowValuesCallerFallsBack() {
                BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " ");
                request.setNavigationContext(NavigationContext.empty().withPgmReenter()
                        .withFromProgram(lowValues(NavigationContext.FROM_PROGRAM_LENGTH)));

                BillPaymentResponse response = controller.mainPara(request).response();

                assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            }

            @Test
            @DisplayName("DFHPF3 sends no screen, so the map travels back exactly as RECEIVE left it")
            void pf3EchoesTheReceivedMap() {
                BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, "Y");

                Invocation invocation = controller.mainPara(request);

                assertThat(invocation.state().screensSent()).isZero();
                assertThat(invocation.response().getActIdIn()).isEqualTo(ACCT_KEY);
                assertThat(invocation.response().getConfirm()).isEqualTo("Y");
                assertThat(invocation.response().getTitle01())
                        .isEqualTo(lowValues(BillPaymentResponse.TITLE01_LENGTH))
                        .isNotEqualTo(ScreenTitles.CCDA_TITLE01);
                assertThat(invocation.response().getCurDate())
                        .isEqualTo(lowValues(BillPaymentResponse.CUR_DATE_LENGTH));
            }

            @Test
            @DisplayName("DFHPF4 at :136-137 clears the current screen")
            void pf4ClearsTheScreen() {
                Invocation invocation =
                        controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y"));

                verify(service, times(1)).clearCurrentScreen(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                assertThat(invocation.state().screensSent()).isEqualTo(1);
            }

            @Test
            @DisplayName("WHEN OTHER at :138-141 raises the flag and reports the standard text")
            void whenOtherReportsAnInvalidKey() {
                Invocation invocation =
                        controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF12), ACCT_KEY, " "));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                assertThat(invocation.state().isErrFlagOn()).isTrue();
                assertThat(invocation.response().getErrMsg()).isEqualTo(
                        codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                                BillPaymentResponse.ERR_MSG_LENGTH));
                assertThat(invocation.response().getErrMsg().strip())
                        .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
                assertThat(invocation.response().getErrMsg())
                        .doesNotContain(ScreenTitles.CCDA_THANK_YOU.strip());
            }

            @ParameterizedTest(name = "{0} reaches WHEN OTHER")
            @DisplayName("every key the source does not name reaches WHEN OTHER - no arm is invented")
            @ValueSource(strings = {"CLEAR", "PA1  ", "PA2  ", "PFK01", "PFK02", "PFK05", "PFK06",
                                    "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12"})
            void unnamedKeysReachWhenOther(String token) {
                controller.mainPara(reentry(token, ACCT_KEY, " "));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                verify(service, never()).clearCurrentScreen(any(PaymentState.class));
            }

            @Test
            @DisplayName("a raw PF15 byte reaches WHEN OTHER, where the folded token took the PF3 arm")
            void aRawUpperKeyIsNotItsFoldedPartner() {
                assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(AidKey.PFK03);

                Invocation invocation = controller.mainPara(reentry(null, ACCT_KEY, " "),
                        Byte.toUnsignedInt(CicsAid.DFHPF15));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                verify(service, never()).clearCurrentScreen(any(PaymentState.class));
                assertThat(invocation.state().isErrFlagOn()).isTrue();
            }

            @Test
            @DisplayName("a raw PF3 byte still takes the :128 back exit, so the lower key is unaffected")
            void aRawLowerKeyStillTakesItsArm() {
                Invocation invocation = controller.mainPara(reentry(null, ACCT_KEY, " "),
                        Byte.toUnsignedInt(CicsAid.DFHPF3));

                verify(service, never()).invalidKeyPressed(any(PaymentState.class));
                assertThat(invocation.state().isErrFlagOn()).isFalse();
                assertThat(invocation.response().getNextProgram().strip()).isNotEmpty();
            }

            @ParameterizedTest(name = "a raw DFHPF{0} byte is an invalid key here")
            @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
            @DisplayName("all twelve upper function keys reach WHEN OTHER when stated as raw bytes")
            void everyUpperKeyIsInvalidHere(int pfNumber) {
                controller.mainPara(reentry(null, ACCT_KEY, " "),
                        Byte.toUnsignedInt(upperFunctionKey(pfNumber)));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
                verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
                verify(service, never()).clearCurrentScreen(any(PaymentState.class));
            }

            @Test
            @DisplayName("the byte wins over the token, and a token restating it is accepted")
            void theByteWinsAndAConsistentTokenIsAccepted() {
                controller.mainPara(reentry(AidKey.PFK03.token(), ACCT_KEY, " "),
                        Byte.toUnsignedInt(CicsAid.DFHPF15));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
            }

            @Test
            @DisplayName("a token naming a different key is refused rather than discarded")
            void aDisagreeingTokenIsRefused() {
                assertThatThrownBy(() -> controller.mainPara(
                        reentry(AidKey.PFK04.token(), ACCT_KEY, " "),
                        Byte.toUnsignedInt(CicsAid.DFHPF3)))
                        .isInstanceOf(ScreenInputRejectedException.class)
                        .hasMessageContaining("aid");

                verify(service, never()).invalidKeyPressed(any(PaymentState.class));
                verify(service, never()).clearCurrentScreen(any(PaymentState.class));
            }

            @ParameterizedTest(name = "a stated {0} is refused")
            @ValueSource(ints = {-1, 256, 4096})
            @DisplayName("a value that is not one byte is refused rather than narrowed")
            void anImpossibleByteIsRefused(int stated) {
                assertThatThrownBy(() -> controller.mainPara(reentry(null, ACCT_KEY, " "), stated))
                        .isInstanceOf(ScreenInputRejectedException.class);

                verify(service, never()).invalidKeyPressed(any(PaymentState.class));
            }

            @Test
            @DisplayName("both spellings of the parameter reach the same byte through the route")
            void bothSpellingsAreHonoured() {
                controller.payBill(reentry(null, ACCT_KEY, " "),
                        Byte.toUnsignedInt(CicsAid.DFHPF15), null);
                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));

                controller.payBill(reentry(null, ACCT_KEY, " "), null,
                        Byte.toUnsignedInt(CicsAid.DFHPF15));
                verify(service, times(2)).invalidKeyPressed(any(PaymentState.class));
            }

            private static byte upperFunctionKey(int pfNumber) {
                try {
                    return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
                } catch (ReflectiveOperationException absent) {
                    throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
                }
            }

            @Test
            @DisplayName("a token naming no key at all reaches WHEN OTHER rather than throwing")
            void anUnknownTokenReachesWhenOther() {
                assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();

                Invocation invocation = controller.mainPara(reentry("ZZZZZ", ACCT_KEY, " "));

                verify(service, times(1)).invalidKeyPressed(any(PaymentState.class));
                assertThat(invocation.state().isErrFlagOn()).isTrue();
            }

            @Test
            @DisplayName("an absent attention identifier defaults to DFHENTER, the only honest default")
            void anAbsentAidDefaultsToEnter() {
                stubEnterKeyOutcome(unconfirmedOutcome());

                controller.mainPara(reentry(null, ACCT_KEY, " "));

                verify(service, times(1)).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @Test
            @DisplayName("an empty attention identifier defaults to DFHENTER too")
            void anEmptyAidDefaultsToEnter() {
                stubEnterKeyOutcome(unconfirmedOutcome());

                controller.mainPara(reentry("", ACCT_KEY, " "));

                verify(service, times(1)).processEnterKey(any(), any(), any(NavigationContext.class));
            }

            @ParameterizedTest(name = "{0} with actidin=[{1}] confirm=[{2}] consults the core: {3}")
            @DisplayName("the ENTER/REENTER split against the two writable fields, as a matrix")
            @CsvSource(value = {
                "ENTER,'','',false",
                "ENTER,'00000000011','',false",
                "ENTER,'00000000011','Y',false",
                "REENTER,'','',true",
                "REENTER,'','Y',true",
                "REENTER,'00000000011','',true",
                "REENTER,'00000000011','Y',true",
                "REENTER,'00000000011','Q',true"}, quoteCharacter = '\'')
            void theEnterReenterMatrix(String context, String actIdIn, String confirm,
                                       boolean consultsTheCore) {
                stubEnterKeyOutcome(unconfirmedOutcome());
                BillPaymentRequest request = reentry(
                        PfKeyResolver.aidImage(CicsAid.DFHENTER), actIdIn, confirm);
                boolean firstEntry = "ENTER".equals(context);
                if (firstEntry) {
                    request.setNavigationContext(NavigationContext.empty());
                }

                BillPaymentResponse response = controller.mainPara(request).response();

                verify(service, times(consultsTheCore ? 1 : 0))
                        .processEnterKey(any(), any(), any(NavigationContext.class));
                if (firstEntry) {
                    assertThat(response.getMessageHighlight()).isNull();
                    assertThat(response.getNavigationContext().isReenter())
                            .as(":113 advances the context on the way out")
                            .isTrue();
                }
            }

            @Test
            @DisplayName("RECEIVE's RESP is captured and never examined - no failure arm is invented")
            void receiveResponseCodesAreCapturedAndNeverExamined() {
                stubEnterKeyOutcome(unconfirmedOutcome());

                Invocation invocation =
                        controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "));

                assertThat(invocation.state().respCd()).isEqualTo(FileStatus.NORMAL);
                assertThat(invocation.state().reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            }
        }
    }

    @Nested
    @DisplayName("RETURN-TO-PREV-SCREEN - :273-284, the only XCTL")
    class ReturnToPrevScreen {
        @Test
        @DisplayName(":275-276 substitutes COSGN00C for a blank CDEMO-TO-PROGRAM")
        void substitutesSignOnForABlankTarget() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped = controller.returnToPrevScreen(response,
                    NavigationContext.empty()
                            .withToProgram(spaces(NavigationContext.TO_PROGRAM_LENGTH)));

            assertThat(stamped.toProgram()).isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
            assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName(":275-276 substitutes COSGN00C for a LOW-VALUES CDEMO-TO-PROGRAM as well")
        void substitutesSignOnForALowValuesTarget() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped = controller.returnToPrevScreen(response,
                    NavigationContext.empty()
                            .withToProgram(lowValues(NavigationContext.TO_PROGRAM_LENGTH)));

            assertThat(stamped.toProgram()).isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("a CDEMO-TO-PROGRAM that names a target is kept verbatim")
        void keepsANamedTarget() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped = controller.returnToPrevScreen(response,
                    NavigationContext.empty().withToProgram("COCRDSLC"));

            assertThat(stamped.toProgram()).isEqualTo("COCRDSLC");
            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName(":278-280 stamps this transaction, this program, and resets the context to ENTER")
        void stampsTheIdentityAndResetsTheContext() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped =
                    controller.returnToPrevScreen(response, populatedCommarea().withPgmReenter());

            assertThat(stamped.fromTranid()).isEqualTo(BillPaymentController.TRANSACTION_ID);
            assertThat(stamped.fromProgram()).isEqualTo(BillPaymentController.PROGRAM_NAME);
            assertThat(stamped.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(stamped.isEnter()).isTrue();
            assertThat(stamped.isReenter()).isFalse();
        }

        @Test
        @DisplayName("nothing else in the communication area is disturbed")
        void disturbsNothingElse() {
            NavigationContext inbound = populatedCommarea().withPgmReenter();

            NavigationContext stamped =
                    controller.returnToPrevScreen(new BillPaymentResponse(), inbound);

            assertThat(stamped.toTranid()).isEqualTo(inbound.toTranid());
            assertThat(stamped.userId()).isEqualTo(inbound.userId());
            assertThat(stamped.userType()).isEqualTo(inbound.userType());
            assertThat(stamped.custId()).isEqualTo(inbound.custId());
            assertThat(stamped.custFname()).isEqualTo(inbound.custFname());
            assertThat(stamped.custMname()).isEqualTo(inbound.custMname());
            assertThat(stamped.custLname()).isEqualTo(inbound.custLname());
            assertThat(stamped.acctId()).isEqualTo(inbound.acctId());
            assertThat(stamped.acctStatus()).isEqualTo(inbound.acctStatus());
            assertThat(stamped.cardNum()).isEqualTo(inbound.cardNum());
            assertThat(stamped.lastMap()).isEqualTo(inbound.lastMap());
            assertThat(stamped.lastMapset()).isEqualTo(inbound.lastMapset());
        }

        @Test
        @DisplayName("the reachable targets are exactly the four the source can produce")
        void theReachableTargetsAreTheFourTheSourceCanProduce() {
            assertThat(controller.mainPara(new BillPaymentRequest()).response().getNextProgram())
                    .isEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
            assertThat(controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " "))
                    .response().getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            BillPaymentRequest fromCardList = reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " ");
            fromCardList.setNavigationContext(NavigationContext.empty().withPgmReenter()
                    .withFromProgram("COCRDLIC"));
            assertThat(controller.mainPara(fromCardList).response().getNextProgram())
                    .isEqualTo("COCRDLIC");
            assertThat(controller.returnToPrevScreen(new BillPaymentResponse(),
                    NavigationContext.empty().withToProgram("COMEN01C")).toProgram())
                    .isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the transfer is a response member, never a server-side forward or redirect")
        void theTransferIsAResponseMemberOnly() throws Exception {
            ObjectMapper mapper = productionMapper();

            MvcResult result = mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram")
                            .value(BillPaymentResponse.MAIN_MENU_PROGRAM))
                    .andReturn();

            assertThat(result.getResponse().getForwardedUrl()).isNull();
            assertThat(result.getResponse().getRedirectedUrl()).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO - :319-338, the six header moves")
    class PopulateHeaderInfo {
        @Test
        @DisplayName(":323-324 the two titles are byte-exact X(40) literals from COTTL01Y")
        void theTwoTitlesAreByteExact() {
            BillPaymentResponse response = new BillPaymentResponse();

            controller.populateHeaderInfo(response);

            assertThat(response.getTitle01())
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(BillPaymentResponse.TITLE01_LENGTH);
            assertThat(response.getTitle02())
                    .isEqualTo("              CardDemo                  ")
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(BillPaymentResponse.TITLE02_LENGTH);
        }

        @Test
        @DisplayName(":325-326 the transaction and program identities come from WS-TRANID and WS-PGMNAME")
        void theIdentitiesComeFromWorkingStorage() {
            BillPaymentResponse response = new BillPaymentResponse();

            controller.populateHeaderInfo(response);

            assertThat(response.getTrnName()).isEqualTo("CB00")
                    .hasSize(BillPaymentResponse.TRN_NAME_LENGTH);
            assertThat(response.getPgmName()).isEqualTo("COBIL00C")
                    .hasSize(BillPaymentResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName(":328-338 the date is MM/DD/YY on the last two year digits, the time is HH:MM:SS")
        void theDateAndTimeAreRenderedFromTheClock() {
            BillPaymentResponse response = new BillPaymentResponse();

            controller.populateHeaderInfo(response);

            assertThat(response.getCurDate()).isEqualTo(EXPECTED_CUR_DATE)
                    .hasSize(BillPaymentResponse.CUR_DATE_LENGTH);
            assertThat(response.getCurTime()).isEqualTo(EXPECTED_CUR_TIME)
                    .hasSize(BillPaymentResponse.CUR_TIME_LENGTH);
            assertThat(response.getCurDate())
                    .isEqualTo(DateHeader.from(codec, FIXED_CLOCK).wsCurdateMmDdYy());
            assertThat(response.getCurTime())
                    .isEqualTo(DateHeader.from(codec, FIXED_CLOCK).wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("the clock really is the seam: a different clock renders a different header")
        void theClockIsTheSeam() {
            Clock newYearsEve = Clock.fixed(Instant.parse("1999-12-31T00:01:02Z"), ZoneOffset.UTC);
            BillPaymentResponse response = new BillPaymentResponse();

            new BillPaymentController(service, newYearsEve).populateHeaderInfo(response);

            assertThat(response.getCurDate()).isEqualTo("12/31/99");
            assertThat(response.getCurTime()).isEqualTo("00:01:02");
        }

        @Test
        @DisplayName("the titles are never confused with the two thank-you literals")
        void theTitlesAreNeverConfusedWithTheThankYouLiterals() {
            BillPaymentResponse response = new BillPaymentResponse();

            controller.populateHeaderInfo(response);

            assertThat(response.getTitle01()).isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(response.getTitle02()).isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(response.getTitle01()).isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getTitle02()).isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("every send paints the header, and it is the SEND that paints it")
        void everySendPaintsTheHeader() {
            BillPaymentResponse painted = controller.mainPara(firstEntry()).response();

            assertThat(painted.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.getCurDate()).isEqualTo(EXPECTED_CUR_DATE);
            assertThat(painted.getNextMapset()).isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(painted.getNextMap()).isEqualTo(BillPaymentResponse.MAP_NAME);
        }
    }

    @Nested
    @DisplayName("CLEAR-CURRENT-SCREEN - :552-566, the PF4 arm")
    class ClearCurrentScreen {
        @Test
        @DisplayName(":563-566 blanks the three fields and the message, and :562 parks the cursor")
        void blanksEveryFieldAndParksTheCursor() {
            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y")).response();

            assertThat(response.getActIdIn())
                    .isEqualTo(spaces(BillPaymentResponse.ACT_ID_IN_LENGTH));
            assertThat(response.getCurBal())
                    .isEqualTo(spaces(BillPaymentResponse.CUR_BAL_LENGTH));
            assertThat(response.getConfirm())
                    .isEqualTo(spaces(BillPaymentResponse.CONFIRM_LENGTH));
            assertThat(response.getErrMsg())
                    .isEqualTo(spaces(BillPaymentResponse.ERR_MSG_LENGTH));
            assertThat(response.getCursorField()).isEqualTo(CursorField.ACTIDIN);
        }

        @Test
        @DisplayName("the header is repainted, because :555 performs SEND-BILLPAY-SCREEN")
        void repaintsTheHeader() {
            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y")).response();

            assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTrnName()).isEqualTo(BillPaymentResponse.TRANSACTION_ID);
            assertThat(response.getCurDate()).isEqualTo(EXPECTED_CUR_DATE);
        }

        @Test
        @DisplayName("clearing the screen raises no error flag and reads no account")
        void raisesNoErrorAndReadsNothing() {
            Invocation invocation =
                    controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y"));

            assertThat(invocation.state().isErrFlagOn()).isFalse();
            verify(service, never()).processEnterKey(any(), any(), any(NavigationContext.class));
            verify(service, never()).invalidKeyPressed(any(PaymentState.class));
        }

        @Test
        @DisplayName("the context stays at REENTER, because :137 does not return to a previous screen")
        void keepsTheContextAtReenter() {
            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y")).response();

            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNextProgram())
                    .as("no transfer was decided, so the carrier names nothing - spaces, not null")
                    .isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
        }
    }

    @Nested
    @DisplayName("the cursor and the single attribute write - :526 and the seventeen MOVE -1 sites")
    class CursorAndAttributes {
        @Test
        @DisplayName("the cursor indicator is metadata, and no xxxL member exists to carry it")
        void theCursorIsMetadataAndNoLengthItemIsPublished() throws Exception {
            Map<String, Object> wire = wireForm(
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y")).response());

            assertThat(wire).doesNotContainKey("cursorField");
            assertThat(controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y"))
                    .response().getCursorField()).isSameAs(CursorField.ACTIDIN);
            assertThat(wire).doesNotContainKeys("actIdInL", "confirmL", "ACTIDINL", "CONFIRML");
            assertThat(CursorField.values())
                    .containsExactly(CursorField.NONE, CursorField.ACTIDIN, CursorField.CONFIRM);
        }

        @Test
        @DisplayName("the fifteen ACTIDINL sites land on ACTIDIN - the clear path, :562")
        void theClearPathParksTheCursorInTheAccountField() {
            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, "Y")).response();

            assertThat(response.getCursorField()).isEqualTo(CursorField.ACTIDIN);
        }

        @Test
        @DisplayName("the fifteen ACTIDINL sites land on ACTIDIN - the empty-identifier path, :163")
        void theEmptyIdentifierPathParksTheCursorInTheAccountField() {
            stubEnterKeyOutcome(emptyAccountIdOutcome());

            Invocation invocation = controller.mainPara(reentry(
                    PfKeyResolver.aidImage(CicsAid.DFHENTER), "", " "));

            assertThat(invocation.response().getCursorField()).isEqualTo(CursorField.ACTIDIN);
            assertThat(invocation.state().isErrFlagOn()).isTrue();
        }

        @Test
        @DisplayName("the two CONFIRML sites land on CONFIRM - the unconfirmed prompt, :239")
        void theUnconfirmedPromptParksTheCursorInTheConfirmField() {
            stubEnterKeyOutcome(unconfirmedOutcome());

            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ")).response();

            assertThat(response.getCursorField()).isEqualTo(CursorField.CONFIRM);
            assertThat(response.getErrMsg().strip())
                    .isEqualTo(BillPaymentService.MSG_CONFIRM_TO_PAY);
        }

        @Test
        @DisplayName("the two CONFIRML sites land on CONFIRM - the invalid confirmation value, :189")
        void theInvalidConfirmationValueParksTheCursorInTheConfirmField() {
            stubEnterKeyOutcome(state -> {
                state.setErrFlagOn();
                state.setMessage(BillPaymentService.MSG_INVALID_CONFIRM_VALUE);
                state.setCursorField(CursorField.CONFIRM);
            });

            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Q")).response();

            assertThat(response.getCursorField()).isEqualTo(CursorField.CONFIRM);
        }

        @Test
        @DisplayName(":526 a completed payment is the program's ONLY attribute write - DFHGREEN")
        void aCompletedPaymentColoursTheMessageLineGreen() {
            stubEnterKeyOutcome(paidOutcome("0000000000000001"));

            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y")).response();

            assertThat(response.getMessageHighlight())
                    .isEqualTo(Character.toString(BmsAttributes.unsigned(BmsAttributes.DFHGREEN)))
                    .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
            assertThat(response.getErrMsg()).contains("Payment successful.");
        }

        @Test
        @DisplayName("no error path colours anything, because :526 is on the successful arm alone")
        void noErrorPathColoursAnything() {
            stubEnterKeyOutcome(emptyAccountIdOutcome());

            assertThat(controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), "", " "))
                    .response().getMessageHighlight()).isNull();
            assertThat(controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF12), ACCT_KEY, " "))
                    .response().getMessageHighlight()).isNull();
            assertThat(controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, " "))
                    .response().getMessageHighlight()).isNull();
            assertThat(controller.mainPara(firstEntry()).response().getMessageHighlight()).isNull();
        }

        @Test
        @DisplayName("no path writes DFHRED and no path writes an asterisk - CSSETATY is not copied")
        void noPathWritesTheErrorHighlight() {
            stubEnterKeyOutcome(emptyAccountIdOutcome());
            String red = Character.toString(BmsAttributes.unsigned(BmsAttributes.DFHRED));

            BillPaymentResponse response =
                    controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), "", " ")).response();

            assertThat(response.getMessageHighlight()).isNotEqualTo(red);
            assertThat(response.getActIdIn()).doesNotContain("*");
            assertThat(response.getConfirm()).doesNotContain("*");
        }

        @Test
        @DisplayName("G38: the shared highlight helper applies only in REENTER, and this screen is ENTER")
        void theSharedHighlightHelperAppliesOnlyInReenter() {
            FieldHighlight onFirstEntry = FieldAttributeSetter.resolveFromFlags(true, true, false);
            FieldHighlight onReentry = FieldAttributeSetter.resolveFromFlags(true, true, true);

            assertThat(onFirstEntry.untouched()).isTrue();
            assertThat(onReentry.untouched()).isFalse();
            assertThat(onReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(onReentry.outputItemValue()).isEqualTo("*");
            assertThat(FieldAttributeSetter
                    .resolve(FieldValidationState.of(false, false), true).untouched()).isTrue();
        }
    }

    @Nested
    @DisplayName("payload projection - the ten DFHMDF fields and nothing else")
    class PayloadProjection {
        @Test
        @DisplayName("the response publishes exactly the members the screen contract declares")
        void theResponsePublishesExactlyTheDeclaredMembers() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());

            Map<String, Object> wire = wireForm(controller
                    .mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ")).response());

            assertThat(wire.keySet()).containsExactlyInAnyOrderElementsOf(RESPONSE_MEMBERS);
            assertThat(BillPaymentResponse.MAP_FIELD_COUNT).isEqualTo(MAP_FIELD_WIDTHS.size());
        }

        @Test
        @DisplayName("the request accepts exactly the same ten fields, plus the state it carries")
        void theRequestAcceptsExactlyTheDeclaredMembers() throws Exception {
            Map<String, Object> wire = wireForm(withExtension(
                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y")));

            assertThat(wire.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_MEMBERS);
            assertThat(wire.keySet()).containsAll(MAP_FIELD_WIDTHS.keySet());
        }

        @Test
        @DisplayName("every projected field is exactly its symbolic-map PICTURE width, on both sides")
        void everyProjectedFieldIsAtItsDeclaredWidth() throws Exception {
            stubEnterKeyOutcome(paidOutcome("0000000000000001"));

            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y")).response();
            Map<String, Object> wire = wireForm(response);

            MAP_FIELD_WIDTHS.forEach((field, width) -> {
                assertThat(wire.get(field))
                        .as("%s is PIC X(%d) and travels as text", field, width)
                        .isInstanceOf(String.class);
                assertThat((String) wire.get(field))
                        .as("%s is PIC X(%d)", field, width)
                        .hasSize(width);
            });
            assertThat(BillPaymentRequest.TRN_NAME_LENGTH)
                    .isEqualTo(BillPaymentResponse.TRN_NAME_LENGTH);
            assertThat(BillPaymentRequest.ACT_ID_IN_LENGTH)
                    .isEqualTo(BillPaymentResponse.ACT_ID_IN_LENGTH);
            assertThat(BillPaymentRequest.CUR_BAL_LENGTH)
                    .isEqualTo(BillPaymentResponse.CUR_BAL_LENGTH);
            assertThat(BillPaymentRequest.CONFIRM_LENGTH)
                    .isEqualTo(BillPaymentResponse.CONFIRM_LENGTH);
            assertThat(BillPaymentRequest.ERR_MSG_LENGTH)
                    .isEqualTo(BillPaymentResponse.ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("the request carries the same ten widths, because xxxI and xxxO share their bytes")
        void theRequestCarriesTheSameTenWidths() throws Exception {
            BillPaymentRequest filled = new BillPaymentRequest();
            filled.setTrnName(codec.movePicX("CB00", BillPaymentRequest.TRN_NAME_LENGTH));
            filled.setTitle01(ScreenTitles.CCDA_TITLE01);
            filled.setCurDate(EXPECTED_CUR_DATE);
            filled.setPgmName(codec.movePicX("COBIL00C", BillPaymentRequest.PGM_NAME_LENGTH));
            filled.setTitle02(ScreenTitles.CCDA_TITLE02);
            filled.setCurTime(EXPECTED_CUR_TIME);
            filled.setActIdIn(codec.movePicX(ACCT_KEY, BillPaymentRequest.ACT_ID_IN_LENGTH));
            filled.setCurBal(EDITED_BALANCE);
            filled.setConfirm("Y");
            filled.setErrMsg(spaces(BillPaymentRequest.ERR_MSG_LENGTH));

            Map<String, Object> wire = wireForm(filled);

            MAP_FIELD_WIDTHS.forEach((field, width) -> {
                assertThat(wire.get(field))
                        .as("the request's %s is PIC X(%d) and travels as text", field, width)
                        .isInstanceOf(String.class);
                assertThat((String) wire.get(field))
                        .as("the request's %s is PIC X(%d)", field, width)
                        .hasSize(width);
            });
        }

        @Test
        @DisplayName("no length, flag, attribute, colour, symbol, highlight or validation item is published")
        void noMetadataItemIsPublished() throws Exception {
            stubEnterKeyOutcome(paidOutcome("0000000000000001"));
            Map<String, Object> wire = wireForm(controller
                    .mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y")).response());

            for (String field : MAP_FIELD_WIDTHS.keySet()) {
                for (String suffix : METADATA_SUFFIXES) {
                    assertThat(wire).doesNotContainKey(field + suffix);
                    assertThat(wire).doesNotContainKey(field.toUpperCase(Locale.ROOT)
                            + suffix);
                }
            }
            assertThat(BillPaymentResponse.FIELD_PROLOGUE_LENGTH).isEqualTo(7);
            assertThat(BillPaymentResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(BillPaymentResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(
                    BillPaymentResponse.TIOAPFX_PREFIX_LENGTH
                            + BillPaymentResponse.MAP_FIELD_COUNT
                                    * BillPaymentResponse.FIELD_PROLOGUE_LENGTH
                            + BillPaymentResponse.MAP_DATA_LENGTH);
        }

        @Test
        @DisplayName("G22: curBal is edited TEXT from PIC +9999999999.99 - a string, never a number")
        void curBalIsTextAndNeverANumber() throws Exception {
            stubEnterKeyOutcome(paidOutcome("0000000000000001"));
            ObjectMapper mapper = productionMapper();

            String json = mapper.writeValueAsString(controller
                    .mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y")).response());

            assertThat(json).contains("\"curbal\":\"" + EDITED_BALANCE + "\"");
            assertThat(json).doesNotContain("\"curbal\":" + EDITED_BALANCE);
            assertThat(wireForm(controller.mainPara(reentry(
                    PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y"))
                    .response()).get("curbal")).isInstanceOf(String.class);
        }

        @Test
        @DisplayName("errMsg carries the X(80) message truncated on the right into X(78) - :293")
        void errMsgCarriesTheTruncatedMessage() {
            String eighty = "M".repeat(BillPaymentResponse.ERR_MSG_LENGTH) + "XY";
            assertThat(eighty).hasSize(BillPaymentResponse.WS_MESSAGE_LENGTH);
            stubEnterKeyOutcome(state -> state.setMessage(eighty));

            BillPaymentResponse response =
                    controller.mainPara(reentry(
                            PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ")).response();

            assertThat(response.getErrMsg())
                    .hasSize(BillPaymentResponse.ERR_MSG_LENGTH)
                    .isEqualTo("M".repeat(BillPaymentResponse.ERR_MSG_LENGTH))
                    .doesNotEndWith("XY");
        }

        @Test
        @DisplayName("a blank account identifier is answered with a message, never with a 400")
        void aBlankAccountIdentifierIsAnsweredWithAMessage() throws Exception {
            stubEnterKeyOutcome(emptyAccountIdOutcome());
            ObjectMapper mapper = productionMapper();

            mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), "", ""))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(codec.movePicX(
                            BillPaymentService.MSG_ACCT_ID_EMPTY,
                            BillPaymentResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.screenMetadata.cursorField")
                            .value(CursorField.ACTIDIN.name()));
        }

        @Test
        @DisplayName("a blank confirmation is the read-the-account path, and is answered with a 200")
        void aBlankConfirmationIsTheReadPath() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            ObjectMapper mapper = productionMapper();

            mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.screenMetadata.cursorField")
                            .value(CursorField.CONFIRM.name()));

            verify(service).processEnterKey(eq(ACCT_KEY), eq(" "), any(NavigationContext.class));
        }

        @Test
        @DisplayName("the two writable fields carry a @Size maximum and no presence constraint at all")
        void onlyTheTwoWritableFieldsCarryAWidthConstraintAndNoneCarriesAPresenceConstraint()
                throws Exception {
            assertThat(BillPaymentRequest.class.getDeclaredField("actIdIn")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(BillPaymentRequest.ACT_ID_IN_LENGTH);
            assertThat(BillPaymentRequest.class.getDeclaredField("confirm")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(BillPaymentRequest.CONFIRM_LENGTH);

            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                assertThat(field.getAnnotation(NotBlank.class))
                        .as("%s must carry no @NotBlank", field.getName())
                        .isNull();
                assertThat(field.getAnnotation(NotNull.class))
                        .as("%s must carry no @NotNull", field.getName())
                        .isNull();
            }
        }

        @Test
        @DisplayName("a space-padded PIC X(n) value survives the round trip with its padding intact")
        void aPaddedValueSurvivesTheRoundTrip() throws Exception {
            ObjectMapper mapper = productionMapper();
            String paddedAccount = "1          ";
            String paddedConfirm = " ";
            assertThat(paddedAccount).hasSize(BillPaymentRequest.ACT_ID_IN_LENGTH);

            MvcResult result = mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentry(
                                    PfKeyResolver.aidImage(CicsAid.DFHPF3), paddedAccount, paddedConfirm))))
                    .andExpect(status().isOk())
                    .andReturn();

            String json = result.getResponse().getContentAsString();
            assertThat(json).contains("\"actidin\":\"" + paddedAccount + "\"");
            assertThat(json).contains("\"confirm\":\"" + paddedConfirm + "\"");
            BillPaymentResponse returned = mapper.readValue(json, BillPaymentResponse.class);
            assertThat(returned.getActIdIn()).isEqualTo(paddedAccount);
            assertThat(returned.getConfirm()).isEqualTo(paddedConfirm);
        }

        @Test
        @DisplayName("an all-blank message line travels as seventy-eight blanks, not as null or empty")
        void anAllBlankMessageTravelsAsBlanks() throws Exception {
            ObjectMapper mapper = productionMapper();

            String json = mapper.writeValueAsString(controller.mainPara(firstEntry()).response());

            assertThat(json).contains(
                    "\"errmsg\":\"" + spaces(BillPaymentResponse.ERR_MSG_LENGTH) + "\"");
            assertThat(json).contains("\"title01\":\"" + ScreenTitles.CCDA_TITLE01 + "\"");
        }
    }

    private static Map<String, Object> wireForm(Object payload) throws Exception {
        ObjectMapper mapper = productionMapper();
        return mapper.readValue(mapper.writeValueAsString(payload),
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @Nested
    @DisplayName("statelessness - the conversation lives in the payload and nowhere else")
    class Statelessness {
        @Test
        @DisplayName("no session is created and no cookie is set")
        void noSessionAndNoCookie() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            ObjectMapper mapper = productionMapper();

            MvcResult result = mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("no HttpSession may be created: the COMMAREA is the session")
                    .isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getResponse().getHeaderNames())
                    .noneMatch(name -> name.equalsIgnoreCase(HttpHeaders.SET_COOKIE));
            assertThat(result.getResponse().getContentAsString()).doesNotContain("JSESSIONID");
        }

        @Test
        @DisplayName("two identical requests produce two byte-identical responses")
        void twoIdenticalRequestsProduceIdenticalResponses() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            ObjectMapper mapper = productionMapper();
            String body = mapper.writeValueAsString(reentry(
                    PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "));
            MockMvc mvc = mockMvc(mapper);

            String first = mvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a brand-new controller answers identically, because nothing was retained")
        void aFreshControllerAnswersIdentically() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ");
            ObjectMapper mapper = productionMapper();

            String fromTheFirst = mapper.writeValueAsString(
                    controller.mainPara(request).response());
            String fromASecond = mapper.writeValueAsString(
                    new BillPaymentController(service, FIXED_CLOCK).mainPara(request).response());

            assertThat(fromASecond).isEqualTo(fromTheFirst);
        }

        @Test
        @DisplayName("the controller holds no mutable state at all - practice B9, gate G53")
        void theControllerHoldsNoMutableState() {
            for (Field field : BillPaymentController.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(field.getType())
                            .as("%s must be a collaborator or a value type, never a repository",
                                    field.getName())
                            .isIn(BillPaymentService.class, Clock.class, FixedWidthCodec.class);
                }
            }
        }

        @Test
        @DisplayName("all three pieces of conversation state arrive in the payload and leave in it")
        void everyPieceOfConversationStateTravelsInThePayload() throws Exception {
            stubEnterKeyOutcome(unconfirmedOutcome());
            BillPaymentRequest request = withExtension(reentry(
                    PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "));
            request.setNavigationContext(populatedCommarea().withPgmReenter());

            Map<String, Object> wire = wireForm(controller.mainPara(request).response());

            assertThat(wire).containsKey("navigationContext");
            assertThat(wireForm(request)).containsKey("aid");
            assertThat(wire.get("actidin")).isEqualTo(ACCT_KEY);
            Map<String, Object> commarea = wireForm(
                    controller.mainPara(request).response().getNavigationContext());
            assertThat(commarea.get("pgmContext")).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }
    }

    @Nested
    @DisplayName("the communication area - 160 bytes, plus a 58-byte extension that stays outside it")
    class CommareaRoundTrip {
        @Test
        @DisplayName("CARDDEMO-COMMAREA is exactly 160 bytes, and the five sections account for all of it")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(populatedCommarea().toFixedWidth(codec))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7) - not the X(8) they resemble")
        void theLastMapMembersAreSevenBytesEach() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.MORE_INFO_LENGTH);
            assertThat(populatedCommarea().lastMap()).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(populatedCommarea().lastMapset()).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
        }

        @Test
        @DisplayName("the fixed-width image round-trips through the codec unchanged")
        void theImageRoundTrips() {
            byte[] image = populatedCommarea().toFixedWidth(codec);
            NavigationContext canonical = NavigationContext.fromFixedWidth(codec, image);

            assertThat(canonical.toFixedWidth(codec)).isEqualTo(image);
            assertThat(NavigationContext.fromFixedWidth(codec, canonical.toFixedWidth(codec)))
                    .isEqualTo(canonical);
            assertThat(canonical.custFname()).hasSize(NavigationContext.CUST_FNAME_LENGTH);
            assertThat(canonical.custLname()).hasSize(NavigationContext.CUST_LNAME_LENGTH);
            assertThat(canonical.custFname().strip()).isEqualTo("JOHN");
        }

        @Test
        @DisplayName("the three members COBIL00C never reads pass through untouched")
        void theThreeUnreadMembersPassThroughUntouched() {
            stubEnterKeyOutcome(unconfirmedOutcome());
            NavigationContext inbound = populatedCommarea().withPgmReenter();
            BillPaymentRequest request = reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ");
            request.setNavigationContext(inbound);

            NavigationContext returned =
                    controller.mainPara(request).response().getNavigationContext();

            assertThat(returned.userType()).isEqualTo(inbound.userType());
            assertThat(returned.lastMap()).isEqualTo(inbound.lastMap());
            assertThat(returned.lastMapset()).isEqualTo(inbound.lastMapset());
            assertThat(returned).isEqualTo(inbound);
        }

        @Test
        @DisplayName("all six CDEMO-CB00-INFO members are carried, including the five nothing reads")
        void allSixExtensionMembersAreCarried() {
            stubEnterKeyOutcome(unconfirmedOutcome());
            BillPaymentRequest request =
                    withExtension(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "));

            BillPaymentResponse response = controller.mainPara(request).response();

            assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(response.getTrnIdLast()).isEqualTo("0000000000000099");
            assertThat(response.getPageNum()).isEqualTo(7);
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(response.getTrnSelFlg()).isEqualTo("S");
            assertThat(response.getTrnSelected()).isEqualTo(ACCT_KEY_IN_CARRIER);
        }

        @Test
        @DisplayName("the extension is 58 bytes and stays OUTSIDE the shared 160-byte area")
        void theExtensionStaysOutsideTheSharedArea() throws Exception {
            assertThat(BillPaymentResponse.CB00_INFO_LENGTH).isEqualTo(58);
            assertThat(BillPaymentResponse.CB00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + 58)
                    .isEqualTo(218);

            Map<String, Object> commarea = wireForm(populatedCommarea());

            assertThat(commarea.keySet()).containsExactlyInAnyOrderElementsOf(COMMAREA_MEMBERS);
            assertThat(commarea).doesNotContainKeys("trnIdFirst", "trnIdLast", "pageNum",
                    "nextPageFlg", "trnSelFlg", "trnSelected");
        }
    }

    @Nested
    @DisplayName("condition names - the four this class owns, and the six the program cannot reach")
    class ConditionNames {
        @Test
        @DisplayName("CDEMO-PGM-ENTER 0 and CDEMO-PGM-REENTER 1, both states, and both drive :112")
        void theProgramContextConditionsDriveTheDispatch() {
            NavigationContext onEntry = NavigationContext.empty();
            NavigationContext onReentry = onEntry.withPgmReenter();

            assertThat(onEntry.isEnter()).isTrue();
            assertThat(onEntry.isReenter()).isFalse();
            assertThat(onEntry.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(onReentry.isReenter()).isTrue();
            assertThat(onReentry.isEnter()).isFalse();
            assertThat(onReentry.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);

            controller.mainPara(firstEntry());
            verify(service, times(1)).sendBillpayScreen(any(PaymentState.class));
            controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, " "));
            verify(service, times(1)).clearCurrentScreen(any(PaymentState.class));
        }

        @Test
        @DisplayName("CDEMO-USRTYP-ADMIN 'A' and CDEMO-USRTYP-USER 'U', both states, both unread here")
        void theUserTypeConditionsAreCarriedButUnread() {
            NavigationContext administrator = NavigationContext.empty().withUserTypeAdmin();
            NavigationContext ordinary = NavigationContext.empty().withUserTypeUser();

            assertThat(administrator.isAdmin()).isTrue();
            assertThat(administrator.isUser()).isFalse();
            assertThat(administrator.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(ordinary.isUser()).isTrue();
            assertThat(ordinary.isAdmin()).isFalse();
            assertThat(ordinary.userType()).isEqualTo(NavigationContext.USER_TYPE_USER);

            stubEnterKeyOutcome(unconfirmedOutcome());
            BillPaymentRequest asAdmin = reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ");
            asAdmin.setNavigationContext(administrator.withPgmReenter());
            BillPaymentRequest asUser = reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " ");
            asUser.setNavigationContext(ordinary.withPgmReenter());

            assertThat(controller.mainPara(asAdmin).response().getErrMsg())
                    .isEqualTo(controller.mainPara(asUser).response().getErrMsg());
            assertThat(controller.mainPara(asAdmin).response().getNavigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(controller.mainPara(asUser).response().getNavigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
        }

        @Test
        @DisplayName("ERR-FLG-ON :44 is reachable; ERR-FLG-OFF :45 is the state :101 always sets")
        void theErrorFlagConditions() {
            PaymentState state = new PaymentState(codec, NavigationContext.empty());

            assertThat(state.isErrFlagOn())
                    .as("SET ERR-FLG-OFF TO TRUE at :101 runs before every arm")
                    .isFalse();
            assertThat(state.errFlg()).isEqualTo(BillPaymentService.ERR_FLG_OFF);
            state.setErrFlagOn();
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.errFlg()).isEqualTo(BillPaymentService.ERR_FLG_ON);
        }

        @Test
        @DisplayName("CONF-PAY-YES :52 and CONF-PAY-NO :53, exercised as predicates")
        void theConfirmationFlagConditions() {
            PaymentState state = new PaymentState(codec, NavigationContext.empty());

            assertThat(state.isConfPayYes()).isFalse();
            assertThat(state.confPayFlg()).isEqualTo(BillPaymentService.CONF_PAY_NO);
            state.setConfPayYes();
            assertThat(state.isConfPayYes()).isTrue();
            assertThat(state.confPayFlg()).isEqualTo(BillPaymentService.CONF_PAY_YES);
            state.setConfPayNo();
            assertThat(state.isConfPayYes()).isFalse();
        }

        @Test
        @DisplayName("USR-MODIFIED-NO :50 is set at :102; USR-MODIFIED-YES :49 is never reachable")
        void theUserModifiedConditions() {
            PaymentState state = new PaymentState(codec, NavigationContext.empty());

            assertThat(state.usrModified()).isEqualTo(BillPaymentService.USR_MODIFIED_NO);
            state.setUsrModifiedNo();
            assertThat(state.usrModified()).isEqualTo(BillPaymentService.USR_MODIFIED_NO);
            assertThat(BillPaymentService.USR_MODIFIED_YES)
                    .isNotEqualTo(BillPaymentService.USR_MODIFIED_NO);
        }

        @ParameterizedTest(name = "NEXT-PAGE-FLG {0}: yes={1} no={2}")
        @DisplayName("NEXT-PAGE-YES :69 and NEXT-PAGE-NO :70 - predicates on a member nothing reads")
        @CsvSource({"Y,true,false", "N,false,true", "S,false,false"})
        void theNextPageConditions(String flag, boolean yes, boolean no) {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNextPageFlg(flag);

            assertThat(request.nextPageYes()).isEqualTo(yes);
            assertThat(request.nextPageNo()).isEqualTo(no);
            assertThat(BillPaymentRequest.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(BillPaymentRequest.NEXT_PAGE_NO).isEqualTo("N");
        }
    }

    @Nested
    @DisplayName("the figurative-constant test and the EIBAID resolution")
    class FigurativeConstantsAndAidResolution {
        @ParameterizedTest(name = "[{0}] equals a figurative constant: {1}")
        @DisplayName("IF <field> = SPACES OR LOW-VALUES - both constants, tested separately")
        @CsvSource(value = {
            "'        ',true",
            "'',true",
            "'COMEN01C',false",
            "'COMEN01C  ',false",
            "' X      ',false"}, quoteCharacter = '\'')
        void spacesOrLowValues(String value, boolean expected) {
            assertThat(BillPaymentController.isSpacesOrLowValues(value)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an absent field equals a figurative constant, because an unfilled item is blank")
        void anAbsentFieldEqualsAFigurativeConstant() {
            assertThat(BillPaymentController.isSpacesOrLowValues(null)).isTrue();
        }

        @Test
        @DisplayName("a field of X'00' equals LOW-VALUES, and one of blanks equals SPACES")
        void bothConstantsAreRecognisedAtAnyWidth() {
            assertThat(BillPaymentController.isSpacesOrLowValues(
                    lowValues(NavigationContext.TO_PROGRAM_LENGTH))).isTrue();
            assertThat(BillPaymentController.isSpacesOrLowValues(
                    spaces(NavigationContext.TO_PROGRAM_LENGTH))).isTrue();
            assertThat(BillPaymentController.isSpacesOrLowValues(
                    lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH))).isTrue();
            assertThat(BillPaymentController.isSpacesOrLowValues(
                    lowValues(BillPaymentResponse.CONFIRM_LENGTH))).isTrue();
            assertThat(BillPaymentController.isSpacesOrLowValues(" \u0000")).isFalse();
        }

        @Test
        @DisplayName("the two figurative-constant renderers produce exactly their declared widths")
        void theRenderersProduceDeclaredWidths() {
            assertThat(BillPaymentController.spaces(BillPaymentResponse.ERR_MSG_LENGTH))
                    .hasSize(BillPaymentResponse.ERR_MSG_LENGTH)
                    .isBlank();
            assertThat(BillPaymentController.lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH))
                    .hasSize(BillPaymentResponse.ACT_ID_IN_LENGTH)
                    .isEqualTo(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
            assertThat(BillPaymentController.SPACE).isEqualTo(' ');
            assertThat(BillPaymentController.LOW_VALUE).isEqualTo('\u0000');
        }

        @ParameterizedTest(name = "the byte {0} is read back as itself")
        @DisplayName("the payload's one character IS the raw EIBAID byte the EVALUATE compares")
        @ValueSource(ints = {0x40, 0x6C, 0x6D, 0x6E, 0x7D, 0xC3, 0xF1, 0xF3, 0xF4, 0x7C, 0xFF})
        void theTokenResolvesToTheEibAidByte(int unsigned) {
            byte stated = (byte) unsigned;

            assertThat(controller.eibAidOf(PfKeyResolver.aidImage(stated)))
                    .as("nothing is folded, so PF15 stays distinct from PF3 and reaches WHEN OTHER")
                    .isEqualTo(stated);
        }

        @Test
        @DisplayName("the four arms of the EVALUATE are the four PfKeyResolver predicates, unchanged")
        void theFourArmsAreTheFourPredicates() {
            assertThat(PfKeyResolver.isEnter(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))).isTrue();
            assertThat(PfKeyResolver.isPf3(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHPF3)))).isTrue();
            assertThat(PfKeyResolver.isPf4(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHPF4)))).isTrue();
            assertThat(PfKeyResolver.isEnter(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHPF3)))).isFalse();
            assertThat(PfKeyResolver.isPf3(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHPF4)))).isFalse();
            assertThat(PfKeyResolver.isPf4(controller.eibAidOf(
                    PfKeyResolver.aidImage(CicsAid.DFHCLEAR)))).isFalse();
            assertThat(PfKeyResolver.resolve(controller.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF12))))
                    .contains(AidKey.PFK12);
        }

        @Test
        @DisplayName("a CCARD-AID token is not one byte, so it names no key: WHEN OTHER at :138")
        void aFoldedTokenIsNoLongerDecoded() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH).isEqualTo(5);
            assertThat(controller.eibAidOf("PFK03")).isEqualTo(CicsAid.DFHNULL);
            assertThat(controller.eibAidOf("PA1  ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(controller.eibAidOf("PA1")).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("PF15 is not PF3: it navigates nowhere and takes WHEN OTHER, as on a terminal")
        void highFunctionKeysAreNotFolded() {
            byte pf15 = controller.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF15));

            assertThat(pf15).isEqualTo(CicsAid.DFHPF15);
            assertThat(PfKeyResolver.isPf3(pf15)).isFalse();
            assertThat(PfKeyResolver.resolve(pf15))
                    .as("CSSTRPFY does fold it onto PFK03 - which is why the token is not the input")
                    .contains(AidKey.PFK03);
        }

        @Test
        @DisplayName("a character above the one-byte AID space is DFHNULL, never narrowed onto PF3")
        void aCharacterAboveTheAidSpaceIsDfhnull() {
            assertThat(controller.eibAidOf(String.valueOf((char) 0x01F3))).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("an absent, empty or unknown token resolves the way MAIN-PARA needs it to")
        void absentEmptyAndUnknownTokens() {
            assertThat(controller.eibAidOf(null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.eibAidOf("")).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.eibAidOf("NOPE!")).isEqualTo(CicsAid.DFHNULL);
            assertThat(controller.eibAidOf("ENTER")).isEqualTo(CicsAid.DFHNULL);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHNULL)).isFalse();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHNULL)).isFalse();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHNULL)).isFalse();
        }
    }

    @TestConfiguration
    static class SliceCollaborators {
        @Bean
        BillPaymentService billPaymentService() {
            BillPaymentService stub = mock(BillPaymentService.class);
            when(stub.codec()).thenReturn(new FixedWidthCodec(CHARSET));
            return stub;
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Nested
    @WebMvcTest(BillPaymentController.class)
    @ActiveProfiles("test")
    @Import({SliceCollaborators.class, CobolCharsetConfig.class})
    @DisplayName("the Spring MVC slice - real dispatcher, real WebConfig, mocked decision core")
    class TheSpringSlice {
        @Autowired
        private BillPaymentService sliceService;

        @Autowired
        private MockMvc sliceMvc;

        @Autowired
        private ApplicationContext context;

        private FixedWidthCodec sliceCodec;

        @BeforeEach
        void resetTheSliceCollaborator() {
            sliceCodec = sliceService.codec();
            reset(sliceService);
            when(sliceService.codec()).thenReturn(sliceCodec);
            installScreenParagraphs(sliceService, sliceCodec);
        }

        @Test
        @DisplayName("the real dispatcher maps POST /api/billpay onto this controller")
        void theRealDispatcherMapsTheRoute() throws Exception {
            installEnterKeyOutcome(sliceService, sliceCodec, unconfirmedOutcome());
            ObjectMapper mapper = productionMapper();

            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.trnname").value(BillPaymentResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmname").value(BillPaymentResponse.PROGRAM_NAME))
                    .andExpect(jsonPath("$.nextMapset").value(BillPaymentResponse.MAPSET_NAME))
                    .andExpect(jsonPath("$.nextMap").value(BillPaymentResponse.MAP_NAME));

            verify(sliceService).processEnterKey(eq(ACCT_KEY), eq(" "),
                    any(NavigationContext.class));
        }

        @Test
        @DisplayName("no other verb reaches it, and the container answers 405 rather than the handler")
        void noOtherVerbReachesTheHandler() throws Exception {
            sliceMvc.perform(get(BillPaymentController.BILL_PAY_PATH))
                    .andExpect(status().isMethodNotAllowed());

            verify(sliceService, never()).processEnterKey(any(), any(),
                    any(NavigationContext.class));
        }

        @Test
        @DisplayName("B7: the pinned clock really is the one in the path, so the header is assertable")
        void thePinnedClockIsInThePath() throws Exception {
            installEnterKeyOutcome(sliceService, sliceCodec, unconfirmedOutcome());

            assertThat(context.getBean(WebConfig.class))
                    .as("WebConfig is a WebMvcConfigurer, so the slice picks it up with its Jackson "
                            + "customisation and its @RestControllerAdvice")
                    .isNotNull();
            assertThat(context.getBean(Clock.class).instant()).isEqualTo(FIXED_INSTANT);

            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productionMapper().writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.curdate").value(EXPECTED_CUR_DATE))
                    .andExpect(jsonPath("$.curtime").value(EXPECTED_CUR_TIME));
        }

        @Test
        @DisplayName("the module's own mapper keeps the padding: 78 blanks travel as 78 blanks")
        void theConfiguredMapperKeepsThePadding() throws Exception {
            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productionMapper().writeValueAsString(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(spaces(BillPaymentResponse.ERR_MSG_LENGTH)))
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.actidin")
                            .value(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH)));
        }

        @Test
        @DisplayName("Bean Validation is honoured: an account field wider than X(11) is refused")
        void beanValidationRejectsAnOverWideAccountField() throws Exception {
            BillPaymentRequest tooWide = reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER),
                    "9".repeat(BillPaymentRequest.ACT_ID_IN_LENGTH + 1), " ");

            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productionMapper().writeValueAsString(tooWide)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("actidin"));

            verify(sliceService, never()).processEnterKey(any(), any(),
                    any(NavigationContext.class));
        }

        @Test
        @DisplayName("a blank account field is NOT refused - the program answers it with a message")
        void aBlankAccountFieldIsNotRefused() throws Exception {
            installEnterKeyOutcome(sliceService, sliceCodec, emptyAccountIdOutcome());

            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productionMapper().writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), "", ""))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(sliceCodec.movePicX(
                            BillPaymentService.MSG_ACCT_ID_EMPTY,
                            BillPaymentResponse.ERR_MSG_LENGTH)));
        }

        @Test
        @DisplayName("an absent body is the cold start, exactly as a body with no commarea is")
        void anAbsentBodyIsTheColdStart() throws Exception {
            sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram")
                            .value(BillPaymentResponse.SIGN_ON_PROGRAM));

            verify(sliceService, never()).processEnterKey(any(), any(),
                    any(NavigationContext.class));
            verify(sliceService, never()).sendBillpayScreen(any(PaymentState.class));
        }

        @Test
        @DisplayName("G37 and G41: the real slice creates no session and mounts no security filter")
        void theRealSliceIsStatelessAndUnsecured() throws Exception {
            installEnterKeyOutcome(sliceService, sliceCodec, unconfirmedOutcome());

            MvcResult result = sliceMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productionMapper().writeValueAsString(
                                    reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, " "))))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(context.containsBean("springSecurityFilterChain"))
                    .as("Spring Security is not a dependency of this module at all, so there is no "
                            + "filter chain to mount (practice B6, gate G41)")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("scope and provenance - the division of labour, proven")
    class ScopeAndProvenance {
        @Test
        @DisplayName("G51: the decision core really is a mock, so no arithmetic runs in this suite")
        void theDecisionCoreIsAMock() {
            assertThat(mockingDetails(service).isMock()).isTrue();
        }

        @Test
        @DisplayName("the controller consults nothing but the four paragraphs and the codec")
        void theControllerConsultsNothingElse() {
            stubEnterKeyOutcome(paidOutcome("0000000000000001"));

            controller.mainPara(new BillPaymentRequest());
            controller.mainPara(firstEntry());
            controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHENTER), ACCT_KEY, "Y"));
            controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF3), ACCT_KEY, " "));
            controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF4), ACCT_KEY, " "));
            controller.mainPara(reentry(PfKeyResolver.aidImage(CicsAid.DFHPF12), ACCT_KEY, " "));

            List<String> consulted = new ArrayList<>();
            mockingDetails(service).getInvocations()
                    .forEach(invocation -> consulted.add(invocation.getMethod().getName()));

            assertThat(consulted).isNotEmpty();
            assertThat(consulted).containsOnly("codec", "processEnterKey", "sendBillpayScreen",
                    "clearCurrentScreen", "invalidKeyPressed");
            assertThat(consulted).doesNotContain("readAcctdatFile", "updateAcctdatFile",
                    "readCxacaixFile", "writeTransactFile", "startbrTransactFile",
                    "readprevTransactFile", "endbrTransactFile", "editCurrBal", "isNothingToPay");
        }

        @Test
        @DisplayName("the gates that do not apply to this package, recorded as facts about the source")
        void theGatesThatDoNotApplyToThisPackage() {
            assertThat(RESPONSE_MEMBERS.stream()
                    .filter(member -> member.toLowerCase(Locale.ROOT).contains("page")).toList())
                    .containsExactlyInAnyOrder("pageNum", "nextPageFlg");
            assertThat(RESPONSE_MEMBERS).doesNotContain("pageSize", "pageCount", "totalPages");
            assertThat(RESPONSE_MEMBERS).doesNotContain("abendCode", "abendReason", "returnCode");
            assertThat(RESPONSE_MEMBERS).doesNotContain("version", "recordVersion", "recordImage");
            assertThat(REQUEST_MEMBERS).doesNotContain("password", "token", "userToken");
            assertThat(RESPONSE_MEMBERS).doesNotContain("password", "token", "userToken");
        }

        @Test
        @DisplayName("B12: the fixed instant is the source's own footer, not an invented value")
        void theFixedInstantIsTheSourcesOwnFooter() {
            assertThat(FIXED_INSTANT).isEqualTo(Instant.parse("2022-07-19T23:12:32Z"));
            assertThat(EXPECTED_CUR_DATE).isEqualTo("07/19/22");
            assertThat(EXPECTED_CUR_TIME).isEqualTo("23:12:32");
            assertThat(MAP_FIELD_WIDTHS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the ten widths are exactly the copybook's, restated once for the reader")
        void theTenWidthsAreExactlyTheCopybooks() {
            assertThat(MAP_FIELD_WIDTHS).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "trnname", 4, "title01", 40, "curdate", 8, "pgmname", 8, "title02", 40,
                    "curtime", 8, "actidin", 11, "curbal", 14, "confirm", 1, "errmsg", 78));
            assertThat(MAP_FIELD_WIDTHS.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(BillPaymentResponse.MAP_DATA_LENGTH)
                    .isEqualTo(212);
            assertThat(BillPaymentResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(294);
        }
    }
}
